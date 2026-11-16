package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.util.BoundingBox
import org.mockbukkit.mockbukkit.block.data.BlockDataMock
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

    test("the captured Auto4 flight completes every tread landing and grass bank through replayable paid receipts") {
        val fixture = CivsRoadTerracesTest::class.java.getResourceAsStream("/fixtures/auto4-forester-terrace-native.json")!!
            .bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
        val palette = fixture.getAsJsonArray("palette").map { raw -> raw.asJsonObject.let { cell ->
            cell["block_data"].asString to cell.getAsJsonArray("collision").map { box ->
                box.asJsonArray.let { BoundingBox(it[0].asDouble, it[1].asDouble, it[2].asDouble,
                    it[3].asDouble, it[4].asDouble, it[5].asDouble) }
            }
        } }
        val nativeShapes = palette.toMap()
        MockBukkitTestRuntime.open(object : CivsWorksiteServerFixture() {
            override fun createBlockData(data: String): BlockData =
                if (data.startsWith("minecraft:leaf_litter[") && data in nativeShapes) Auto4LeafLitter(data)
                else super.createBlockData(data)
        }).use { paper ->
            val world = CivsWorksiteWorldFixture("auto4-native-terrace", ground = Material.AIR, collisionForData = { data ->
                nativeShapes[data.asString]?.map { it.clone() } ?: if (CivsRoadMaintenance.surface(data.asString) || data.material == Material.GRASS_BLOCK)
                    roadUpkeepBoxes(data).map { it.at(CivPosition(0, 0, 0)) } else null
            }).also(paper.server::addWorld)
            for (x in 784..785) for (z in 646..650) world.loadChunk(x, z)
            val owners = fixture.getAsJsonArray("owners").map { row -> row.asJsonObject.let { owner ->
                Gson().fromJson(owner["building"], CivBuilding::class.java) to owner.getAsJsonArray("paid_route").map { raw ->
                    raw.asJsonArray.let { CivPosition(it[0].asInt, it[1].asInt, it[2].asInt) }
                }
            } }
            val original = owners.first().second
            val repaired = original.map { p -> when (p.z) {
                10390 -> p.copy(y = 70)
                in 10392..10397 -> p.copy(y = 72)
                else -> p
            } }
            val oldProfile = CivsAutonomousTerraform.roadProfile(original)
            val oldLevels = oldProfile.mapValues { it.value.y }
            // The 891-cell capture plus four native boundary rows cover the complete local
            // receipt/recovery footprint. Distant paid rows only close this isolated test world.
            for (x in 12557..12567) for (z in 10347..10404) {
                val floor = oldProfile[civsTerrainKey(x, z)]
                val top = floor?.y ?: (original.minBy { abs(it.z - z) }.y - 1)
                for (y in 64..top) world.getBlockAt(x, y, z).type = if (y == top) Material.GRASS_BLOCK else Material.DIRT
                if (floor != null) world.getBlockAt(x, top, z).blockData = Bukkit.createBlockData(
                    CivsSettlementLayout.pathPavement(floor, oldLevels, "original", downhill = CivsSettlementLayout.routeDownhill(floor, original)))
            }
            val min = fixture.getAsJsonObject("bounds").getAsJsonObject("min")
            fixture.getAsJsonArray("voxels").forEach { raw -> raw.asJsonArray.let { v ->
                world.getBlockAt(min["x"].asInt + v[0].asInt, min["y"].asInt + v[1].asInt, min["z"].asInt + v[2].asInt)
                    .blockData = Bukkit.createBlockData(palette[v[3].asInt].first)
            } }
            val hall = CivBuilding(UUID(294, 1), BuildingType.TOWN_HALL, 1, CivPosition(12525, 66, 10310), 0, "fixture/hall",
                CivBox(CivPosition(12525, 66, 10310), CivPosition(12527, 72, 10312)), CivPosition(12526, 67, 10313), BuildingPhase.ACTIVE)
            val hut = hall.copy(id = UUID(294, 2), type = BuildingType.BUILDER_HUT, anchor = CivPosition(12530, 66, 10310),
                bounds = CivBox(CivPosition(12530, 66, 10310), CivPosition(12532, 72, 10312)), entrance = CivPosition(12531, 67, 10313))
            val source = owners.first().first
            val worker = CivsEquipment.replace(CivWorker(UUID(294, 4), "Builder", hut.id, nextDeliveryAt = 1),
                MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { null }.also { it[CivsEquipment.BAG] = CivResidentItem("COBBLESTONE", 64) })
            var city = Settlement(UUID(294, 5), UUID(294, 6), "Auto4 native flight", world.uid, CivPosition(12563, 68, 10349), 96,
                "original", Stock(), listOf(hall, hut) + owners.map { it.first }, listOf(worker),
                autonomy = CivAutonomy(), inventory = CivInventory(CivStorageMode.PHYSICAL))
            val plans = owners.map { (building, route) -> CivPlacementPlan(building.id, city.id, city.worldId, building, emptyList(),
                terrain = CivTerraformPlan(building.bounds, emptyList(), route, building.anchor.y, 0, 0)) }
            val profile = CivsAutonomousTerraform.roadProfile(repaired)
            val floor = CivPosition(12562, 69, 10390)
            val target = CivsRoadMaintenance.prepare(city, plans).targets.first { it.floor == floor && it.routeOverride != null }
            target.route shouldBe repaired
            target.linkedRouteOverrides!!.keys shouldBe setOf(owners.last().first.id.toString())
            target.validated(city)
            val native = CivsRoadWorld(paper.createSimplePlugin("Auto4RoadTerraceTest"), { listOf(city) }, { _, _, _ -> true }, { false })
            val beside = listOf(12560, 12564).flatMap { x -> (10393..10398).map { z -> CivPosition(x, 71, z) } }
                .associateWith { p -> world.getBlockAt(p.x, p.y, p.z).blockData.asString }
            val captured = native.captureRoadUpkeep(city, worker, target)
            withClue("The captured owned DIRT_PATH at12562,70,10391 must be projected as the new stair") { (captured != null) shouldBe true }
            val work = captured!!.copy(inputs = CivsRoadMaintenance.inputs(city, captured))
            native.validateRoadUpkeep(city, work)
            val oldStep = world.getBlockAt(12562, 70, 10390)
            val beforeStep = oldStep.blockData.clone()
            oldStep.blockData = Bukkit.createBlockData(beforeStep.asString.replace("facing=south", "facing=north"))
            native.captureRoadUpkeep(city, worker, target) shouldBe null
            shouldThrow<CivsRejection> { native.validateRoadUpkeep(city, work) }.reason shouldBe "world-changed"
            oldStep.blockData = beforeStep
            val tread = world.getBlockAt(12562, 70, 10391)
            val beforeTread = tread.blockData.clone()
            tread.type = Material.BEDROCK
            native.captureRoadUpkeep(city, worker, target) shouldBe null
            shouldThrow<CivsRejection> { native.validateRoadUpkeep(city, work) }.reason shouldBe "protected-terrain"
            tread.blockData = beforeTread
            val originalCity = city
            val foreign = source.copy(id = UUID(294, 7), type = BuildingType.ROAD, anchor = CivPosition(12562, 70, 10391),
                entrance = CivPosition(12562, 71, 10391), bounds = CivBox(CivPosition(12562, 70, 10391), CivPosition(12562, 71, 10391)),
                transit = listOf(CivPosition(12562, 71, 10391)))
            city = city.copy(buildings = city.buildings + foreign)
            native.captureRoadUpkeep(city, worker, target) shouldBe null
            shouldThrow<CivsRejection> { native.validateRoadUpkeep(city, work) }.reason shouldBe "building-overlap"
            city = originalCity
            // A co-owner independently publishing the linked grade invalidates this old proposal.
            city = city.copy(roadRouteOverrides = target.linkedRouteOverrides)
            native.captureRoadUpkeep(city, worker, target) shouldBe null
            shouldThrow<CivsRejection> { native.validateRoadUpkeep(city, work) }.reason shouldBe "stale-worksite"
            city = originalCity
            listOf("minecraft:oak_log[axis=y]", "minecraft:oak_leaves[distance=7,persistent=false,waterlogged=false]",
                "minecraft:torch", "minecraft:water[level=0]", "minecraft:lava[level=0]").forEach {
                CivsRoadMaintenance.bankBefore(it) shouldBe false
            }
            var receipts = 0
            fun apply(capturedWork: CivWorldWork) {
                val paid = capturedWork.copy(inputs = CivsRoadMaintenance.inputs(city, capturedWork))
                val after = CivsRoadMaintenance.complete(city, paid, 3_000L * (receipts + 1))
                CivRoadUpkeepReceipt(city.workers.single(), city.stock, paid, 3_000L * (receipts + 1)).validated(after)
                native.validateRoadUpkeep(city, paid)
                paid.changes.asReversed().forEach { c ->
                    world.getBlockAt(c.position.x, c.position.y, c.position.z).setBlockData(Bukkit.createBlockData(c.after), false)
                    withClue("receipt=$receipts floor=${paid.upkeep?.floor} applied=$c changes=${paid.changes}") {
                        native.validateRoadUpkeep(city, paid)
                    }
                }
                city = after
                receipts++
            }
            apply(work)
            val expected = linkedMapOf<CivPosition, String>()
            for (z in 10390..10398) for (x in 12561..12563) {
                val p = profile.getValue(civsTerrainKey(x, z))
                expected[p] = CivsSettlementLayout.pathPavement(p, profile.mapValues { it.value.y }, city.style,
                    downhill = CivsSettlementLayout.routeDownhill(p, repaired))
            }
            CivsRoadTerraces.banks(repaired).values.filter { it.z in 10390..10398 }.forEach { expected[it] = CivsEntrancePath.GRASS }
            expected.size shouldBe 31
            while (receipts < 64) {
                val pending = CivsRoadMaintenance.prepare(city, plans).targets.filter { it.floor in expected }
                    .firstNotNullOfOrNull { native.captureRoadUpkeep(city, city.workers.single(), it) } ?: break
                apply(pending)
            }
            receipts shouldBe 31
            val unfinished = expected.filter { (p, data) -> world.getBlockAt(p.x, p.y, p.z).blockData.asString != data }
            withClue("paid receipts=$receipts unfinished=$unfinished accepted=${city.roadRouteOverrides?.get(source.id.toString())?.changed()}") {
                expected.forEach { (p, data) -> withClue("floor=$p") { world.getBlockAt(p.x, p.y, p.z).blockData.asString shouldBe data } }
            }
            beside.forEach { (p, before) -> world.getBlockAt(p.x, p.y, p.z).blockData.asString shouldBe before }
            (roadHalfStepWalk(world, repaired.subList(39, 50)) != null) shouldBe true
            (roadHalfStepWalk(world, repaired.subList(39, 50).reversed()) != null) shouldBe true
        }
    }
})

/** Pinned MockBukkit omits leaf-litter properties; the fixture retains its captured empty collision. */
private class Auto4LeafLitter(private val data: String) : BlockDataMock(Material.LEAF_LITTER) {
    override fun getAsString(): String = data
    override fun getAsString(hideUnspecified: Boolean): String = data
    override fun matches(other: BlockData?): Boolean = other?.asString == data
    override fun clone(): BlockDataMock = Auto4LeafLitter(data)
}
