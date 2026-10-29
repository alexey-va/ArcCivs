package ru.arc.civs

import com.google.gson.Gson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.api.ArcSceneBounds
import ru.arc.paper.api.ArcSceneDisplay
import ru.arc.paper.api.ArcSceneDisplayContent
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.paper.scene.PaperSceneObservationServiceProvider
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID

class CivsConstructionSceneDisplaysTest : FunSpec({
    val world = UUID(0, 1)
    fun site() = CivBuilding(UUID(0, 2), BuildingType.UNIVERSITY, 1, CivPosition(100, 40, 100), 0, "test",
        CivBox(CivPosition(100, 40, 100), CivPosition(111, 80, 107)), CivPosition(106, 70, 99), BuildingPhase.CONSTRUCTING)
    fun city(building: CivBuilding = site()) = Settlement(UUID(0, 3), UUID(9, 1), "PRIVATE_CITY", world,
        CivPosition(0, 64, 0), 256, "nordic", Stock(9, 8, 7), listOf(building),
        listOf(CivWorker(UUID(9, 2), "PRIVATE_WORKER", null, inventory = mapOf("DIAMOND" to 99))),
        memberNames = mapOf(UUID(9, 3).toString() to "PRIVATE_MEMBER"))
    val query = ArcSceneBounds(world, -100.0, 0.0, 90.0, 120.0, 100.0, 120.0)
    fun extent(display: ArcSceneDisplay): List<Double> = display.pose.let {
        listOf(it.x - it.scale.x / 2.0, it.y - it.scale.y / 2.0, it.z - it.scale.z / 2.0,
            it.scale.x.toDouble(), it.scale.y.toDouble(), it.scale.z.toDouble())
    }

    test("public oak item extents exactly match the existing two-rail fence and its grounded entrance gap") {
        val current = city()
        val building = site()
        val native = civsConstructionFenceGeometry(current, 106.0, 70.0, 104.0)
        val displays = civsConstructionSceneDisplays(listOf(current), query, 128)
        displays.size shouldBe native.size
        displays.map(::extent).toSet() shouldBe native.map { it.cuboid }.map {
            listOf(it.x, it.y, it.z, it.sx.toDouble(), it.sy.toDouble(), it.sz.toDouble())
        }.toSet()
        (displays.size > 20) shouldBe true
        displays.forEach {
            val content = it.content as ArcSceneDisplayContent.Item
            content.item.material shouldBe "minecraft:oak_planks"
            content.item.amount shouldBe 1
            content.item.customModelData shouldBe null
            content.item.itemModel shouldBe null
            content.mode shouldBe "NONE"
            it.billboard shouldBe "FIXED"
            it.blockLight shouldBe null
            it.skyLight shouldBe null
            it.timeline shouldBe null
            listOf(it.pose.translation.x, it.pose.translation.y, it.pose.translation.z) shouldBe listOf(0f, 0f, 0f)
            listOf(it.pose.leftRotation.x, it.pose.leftRotation.y, it.pose.leftRotation.z, it.pose.leftRotation.w) shouldBe listOf(0f, 0f, 0f, 1f)
            listOf(it.pose.rightRotation.x, it.pose.rightRotation.y, it.pose.rightRotation.z, it.pose.rightRotation.w) shouldBe listOf(0f, 0f, 0f, 1f)
            (it.pose.y >= building.entrance.y) shouldBe true
        }
        val json = Gson().toJson(displays)
        listOf("PRIVATE_CITY", "PRIVATE_WORKER", "PRIVATE_MEMBER", "DIAMOND").forEach { json.contains(it) shouldBe false }
    }

    test("intersecting construction is sampled at its own centre when the camera-query centre is far away") {
        val current = city()
        val cameraX = (query.minX + query.maxX) / 2
        civsConstructionFenceGeometry(current, cameraX, 70.0, 104.0) shouldBe emptyList()
        (civsConstructionSceneDisplays(listOf(current), query, 128).isNotEmpty()) shouldBe true
    }

    test("display identity deduplication query bounds world and requested caps stay stable") {
        val current = city()
        val all = civsConstructionSceneDisplays(listOf(current), query, 128)
        all.map { it.id }.distinct().size shouldBe all.size
        all.all { it.id.length <= 128 && query.contains(it.worldId, it.pose.x, it.pose.y, it.pose.z) } shouldBe true
        civsConstructionSceneDisplays(listOf(current, current), query, 128).map { it.id } shouldBe all.map { it.id }
        civsConstructionSceneDisplays(listOf(current.copy(name = "Renamed", revision = 90)), query, 128).map { it.id } shouldBe all.map { it.id }
        civsConstructionSceneDisplays(listOf(current), query, 3).map { it.id } shouldBe all.take(3).map { it.id }
        civsConstructionSceneDisplays(listOf(current), query, 0) shouldBe emptyList()
        civsConstructionSceneDisplays(listOf(current), query, -1) shouldBe emptyList()
        civsConstructionSceneDisplays(listOf(current.copy(worldId = UUID(0, 99))), query, 128) shouldBe emptyList()
        val first = all.first()
        val tiny = ArcSceneBounds(world, first.pose.x, first.pose.y, first.pose.z,
            first.pose.x + .0001, first.pose.y + .0001, first.pose.z + .0001)
        civsConstructionSceneDisplays(listOf(current), tiny, 128).map { it.id } shouldBe listOf(first.id)
        val excluded = ArcSceneBounds(world, query.minX, query.minY, query.minZ,
            first.pose.x, query.maxY, query.maxZ)
        civsConstructionSceneDisplays(listOf(current), excluded, 128).any { it.id == first.id } shouldBe false
        val large = site().copy(bounds = CivBox(CivPosition(90, 40, 90), CivPosition(137, 80, 137)))
        val wide = ArcSceneBounds(world, 0.0, 0.0, 0.0, 200.0, 100.0, 200.0)
        civsConstructionSceneDisplays(listOf(city(large)), wide, 512).size shouldBe 128
    }

    test("Auto upgrades and paused orders use planned dimensions while completed cancelled and deleted sites disappear") {
        val old = site().copy(type = BuildingType.WAREHOUSE, phase = BuildingPhase.ACTIVE)
        val planned = old.copy(level = 2, phase = BuildingPhase.CONSTRUCTING,
            bounds = old.bounds.copy(max = old.bounds.max.copy(x = 115)))
        for (phase in listOf(ConstructionPhase.CONSTRUCTING, ConstructionPhase.WAITING_MATERIALS, ConstructionPhase.PAUSED)) {
            val order = CivConstruction(UUID(0, 4), old.id, 2, 100, 20, phase, Stock(), plannedBuilding = planned)
            val upgrading = city(old).copy(autonomy = CivAutonomy(), construction = order)
            val native = civsConstructionFenceGeometry(upgrading, 108.0, planned.entrance.y.toDouble(), 104.0)
            civsConstructionSceneDisplays(listOf(upgrading), query, 128).map(::extent).toSet() shouldBe
                native.map { it.cuboid }.map { listOf(it.x, it.y, it.z, it.sx.toDouble(), it.sy.toDouble(), it.sz.toDouble()) }.toSet()
            (native.isNotEmpty()) shouldBe true
            civsConstructionSceneDisplays(listOf(upgrading.copy(pendingDeletion = CivDeletion(old.id))), query, 128) shouldBe emptyList()
            civsConstructionSceneDisplays(listOf(upgrading.copy(construction = null)), query, 128) shouldBe emptyList()
            val completed = upgrading.copy(buildings = listOf(planned.copy(phase = BuildingPhase.ACTIVE)), construction = null)
            civsConstructionSceneDisplays(listOf(completed), query, 128) shouldBe emptyList()
        }
    }

    test("the passive public source obeys readiness and its existing lifecycle lease retires every display") {
        failOnUnsupportedMockBukkitOperation { MockBukkitTestRuntime.open().use { paper ->
            val hostPlugin = paper.createSimplePlugin("ConstructionSceneHost")
            val plugin = paper.createSimplePlugin("ConstructionSceneOwner")
            var ready = false
            var reads = 0
            var current = listOf(city())
            val source = CivsConstructionSceneDisplays({ reads++; current }, { ready })
            source.displays(query, 128) shouldBe emptyList()
            reads shouldBe 0
            source.snapshot(query, 128) shouldBe emptyList()
            ready = true
            PaperSceneObservationServiceProvider(hostPlugin).use { host ->
                val lifecycle = PaperPluginRuntime(plugin, "construction-scene-test", TestTaskScheduler()).apply { start() }
                lifecycle.own(host.registerEntitySource(plugin, "civs-construction", source))
                (host.displays(query, 128).isNotEmpty()) shouldBe true
                host.entities(query, 128) shouldBe emptyList()
                current = emptyList()
                host.displays(query, 128) shouldBe emptyList()
                current = listOf(city())
                (host.displays(query, 128).isNotEmpty()) shouldBe true
                lifecycle.close()
                host.displays(query, 128) shouldBe emptyList()
                lifecycle.close()
                host.displays(query, 128) shouldBe emptyList()
            }
        } }
    }
})
