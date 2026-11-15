package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import kotlin.math.abs

class CivsRoadTerracesTest : FunSpec({
    test("a maximum length wavy road scores bounded windows without retaining full candidate routes") {
        val route = (0 until 256).map { z -> CivPosition(0, 65 + (z % 12).let { minOf(it, 12 - it) }, z) }
        repeat(20) { CivsRoadTerraces.candidates(route) }
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().threadId()
        val bytes = bean.getThreadAllocatedBytes(thread)
        val started = System.nanoTime()
        repeat(100) { CivsRoadTerraces.candidates(route).all { CivsRoadTerraces.gradeCost(it) < CivsRoadTerraces.gradeCost(route) } shouldBe true }
        val perCall = (bean.getThreadAllocatedBytes(thread) - bytes) / 100
        println("terrace-256 candidate bytes=$perCall nanos=${(System.nanoTime() - started) / 100}")
        (perCall < 1_000_000) shouldBe true
    }

    test("an existing long hollow fills in bounded passes without changing its endpoints or footprint") {
        val original = (listOf(65, 65, 64) + List(12) { 63 } + listOf(64, 65, 65))
            .mapIndexed { z, y -> CivPosition(0, y, z) }
        var route = original
        repeat(2) {
            val repaired = CivsRoadTerraces.candidates(route).first()
            repaired.first() shouldBe original.first()
            repaired.last() shouldBe original.last()
            repaired.zip(route).all { (a, b) -> a.x == b.x && a.z == b.z && abs(a.y - b.y) <= 1 } shouldBe true
            (CivsRoadTerraces.gradeCost(repaired) < CivsRoadTerraces.gradeCost(route)) shouldBe true
            route = repaired
        }
        route.map { it.y }.distinct() shouldBe listOf(65)
    }

    test("a scattered hillside becomes one straight flight between broad level landings") {
        val original = (0..20).map { z -> CivPosition(0, 65 + (z / 4).coerceAtMost(4), z) }
        var route = original
        repeat(4) { CivsRoadTerraces.candidates(route).firstOrNull()?.let { route = it } }
        route.first() shouldBe original.first()
        route.last() shouldBe original.last()
        route.zipWithNext().all { (a, b) -> b.y - a.y in 0..1 } shouldBe true
        CivsRoadTerraces.gradeCost(route) shouldBe 4 * 40 + 48
    }

    test("four treads have continuous grass cheeks in two-level bands without widening the walking ribbon") {
        val route = (listOf(69, 69, 68, 67, 66, 65, 65, 65)).mapIndexed { z, y -> CivPosition(0, y, z) }
        val banks = CivsRoadTerraces.banks(route)
        banks.size shouldBe 8
        for (x in listOf(-2, 2)) {
            (1..4).map { z -> banks.getValue(civsTerrainKey(x, z)).y } shouldBe listOf(68, 68, 66, 66)
        }
        val profile = CivsAutonomousTerraform.roadProfile(route)
        banks.keys.none { it in profile } shouldBe true
        (1..4).all { z -> (-1..1).all { x -> civsTerrainKey(x, z) in profile } } shouldBe true
        CivsRoadTerraces.banks(route.reversed()) shouldBe banks
    }

    test("an existing paved hollow is filled through exact paid receipts and remains walkable") {
        val route = listOf(65, 65, 64, 64, 64, 65, 65, 65).mapIndexed { z, y -> CivPosition(0, y, z) }
        MockBukkitTestRuntime.open().use { paper ->
            val world = CivsWorksiteWorldFixture("terrace-fill", ground = Material.AIR, collisionForData = { data ->
                if (CivsRoadMaintenance.surface(data.asString) || data.material == Material.GRASS_BLOCK)
                    roadUpkeepBoxes(data).map { it.at(CivPosition(0, 0, 0)) } else null
            }).also(paper.server::addWorld)
            for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
            val profile = CivsAutonomousTerraform.roadProfile(route)
            val levels = profile.mapValues { it.value.y }
            for (x in -6..6) for (z in -5..12) {
                val floor = profile[civsTerrainKey(x, z)]
                val top = floor?.y ?: 64
                for (y in 60..top) world.getBlockAt(x, y, z).type = if (y == top) Material.GRASS_BLOCK else Material.DIRT
                if (floor != null) world.getBlockAt(x, top, z).blockData = Bukkit.createBlockData(
                    CivsSettlementLayout.pathPavement(floor, levels, "caledonia", downhill = CivsSettlementLayout.routeDownhill(floor, route)))
            }
            val hut = CivBuilding(UUID(291, 1), BuildingType.BUILDER_HUT, 1, CivPosition(-30, 64, -30), 0, "fixture/hut",
                CivBox(CivPosition(-30, 64, -30), CivPosition(-28, 69, -28)), CivPosition(-29, 65, -27), BuildingPhase.ACTIVE)
            val source = hut.copy(id = UUID(291, 2), type = BuildingType.HOUSE, anchor = CivPosition(10, 64, 10),
                bounds = CivBox(CivPosition(10, 64, 10), CivPosition(12, 69, 12)), entrance = route.last())
            val hall = hut.copy(id = UUID(291, 6), type = BuildingType.TOWN_HALL, anchor = CivPosition(-50, 64, -50),
                bounds = CivBox(CivPosition(-50, 64, -50), CivPosition(-48, 69, -48)), entrance = CivPosition(-49, 65, -47))
            val worker = CivsEquipment.replace(CivWorker(UUID(291, 3), "Builder", hut.id, nextDeliveryAt = 1),
                MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { null }.also { it[CivsEquipment.BAG] = CivResidentItem("COBBLESTONE", 64) })
            var city = Settlement(UUID(291, 4), UUID(291, 5), "Terrace", world.uid, route.first(), 96, "caledonia", Stock(),
                listOf(hall, hut, source), listOf(worker), autonomy = CivAutonomy(), inventory = CivInventory(CivStorageMode.PHYSICAL))
            val plan = CivPlacementPlan(source.id, city.id, city.worldId, source, emptyList(),
                terrain = CivTerraformPlan(source.bounds, emptyList(), route, source.anchor.y, 0, 0))
            val native = CivsRoadWorld(paper.createSimplePlugin("RoadTerraceTest"), { listOf(city) }, { _, _, _ -> true }, { false })
            var applied = 0
            repeat(32) {
                val targets = CivsRoadMaintenance.prepare(city, listOf(plan)).targets
                val captured = targets.firstNotNullOfOrNull { native.captureRoadUpkeep(city, city.workers.single(), it) } ?: return@repeat
                val work = captured.copy(inputs = CivsRoadMaintenance.inputs(city, captured))
                val after = CivsRoadMaintenance.complete(city, work, 3_000L * (applied + 1))
                CivRoadUpkeepReceipt(city.workers.single(), city.stock, work, 3_000L * (applied + 1)).validated(after)
                work.changes.asReversed().forEach { c -> world.getBlockAt(c.position.x, c.position.y, c.position.z)
                    .setBlockData(Bukkit.createBlockData(c.after), false) }
                native.validateRoadUpkeep(city, work)
                city = after
                applied++
            }
            val final = CivAutonomousAccess.from(city, listOf(plan)).routes.getValue(source.id)
            withClue("receipts=$applied profile=$final") { final.map { it.y }.distinct() shouldBe listOf(65) }
            (roadHalfStepWalk(world, final) != null) shouldBe true
            (roadHalfStepWalk(world, final.reversed()) != null) shouldBe true
            for (x in -1..1) for (z in 2..4) world.getBlockAt(x, 64, z).type shouldBe Material.DIRT_PATH
        }
    }
})
