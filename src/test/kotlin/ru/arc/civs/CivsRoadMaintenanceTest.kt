package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.BukkitTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsRoadMaintenanceTest : FunSpec({
    test("Auto2 saved hill receives both stair directions and keeps the crest grade") {
        val ground = listOf(63, 63, 63, 64, 65, 66, 66, 66, 65, 64, 63, 63, 63)
        val floors = ground.mapIndexed { index, y -> CivPosition(-1868, y, -2718 + index) }
        val profile = floors.associate { civsTerrainKey(it.x, it.z) to it.y }
        val finish = floors.associate { it.z to CivsSettlementLayout.pavement(it, profile) }
        for (z in -2715..-2713) finish[z] shouldBe
            "minecraft:cobblestone_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]"
        for (z in -2711..-2709) finish[z] shouldBe
            "minecraft:cobblestone_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"
        finish[-2712] shouldBe "minecraft:cobblestone"
        floors.map { it.y } shouldBe ground
    }

    test("legacy route paving ends at the outside entrance and excludes current and future building territory") {
        val city = upkeepCity()
        val source = city.buildings.last().copy(type = BuildingType.FORESTER, level = 1,
            schematicId = CivsBlueprints.schematicId(city.style, BuildingType.FORESTER, 1),
            anchor = CivPosition(10, 63, 10), bounds = CivBox(CivPosition(10, 63, 8), CivPosition(10, 65, 9)),
            entrance = CivPosition(10, 64, 10), transit = null)
        val other = source.copy(id = UUID(0, 40), type = BuildingType.HOUSE,
            schematicId = CivsBlueprints.schematicId(city.style, BuildingType.HOUSE, 1),
            anchor = CivPosition(10, 63, 0), bounds = CivBox(CivPosition(10, 63, -1), CivPosition(10, 65, -1)),
            entrance = CivPosition(10, 64, 0))
        val route = (-15..10).map { CivPosition(10, 64, it) }
        val town = city.copy(buildings = city.buildings.dropLast(1) + other + source)
        val plan = upkeepApproach(town, source, route)
        val before = Gson().toJson(plan)
        val network = CivsRoadMaintenance.prepare(town, listOf(plan))
        val reserved = CivsAutonomousGeometry.futureBounds(town, other)
        network.targets.none { CivsAutonomousGeometry.horizontalContains(reserved, it.floor) } shouldBe true
        // No blanket three-block gap outside an entrance: the first exterior cell remains eligible.
        network.targets.any { it.floor == source.entrance.copy(y = source.entrance.y - 1) } shouldBe true
        Gson().toJson(plan) shouldBe before
    }

    test("accepted ROAD widths and captured finishes are retained while conflicting grades are omitted") {
        val city = upkeepCity()
        val road = city.buildings.last().copy(bounds = CivBox(CivPosition(0, 63, -1), CivPosition(2, 65, 1)),
            transit = (0..2).flatMap { x -> (-1..1).map { z -> CivPosition(x, 64, z) } })
        val town = city.copy(buildings = city.buildings.dropLast(1) + road)
        val cells = road.transit!!.map { CivPlanBlock(it.copy(y = it.y - 1), "minecraft:grass_block[snowy=false]", "minecraft:cobblestone") }
        val plan = CivPlacementPlan(road.id, town.id, town.worldId, road, cells)
        val network = CivsRoadMaintenance.prepare(town, listOf(plan))
        network.targets.map { it.floor }.toSet() shouldBe cells.map { it.position }.toSet()
        val foreign = plan.copy(blocks = cells.map { it.copy(after = "minecraft:stone_bricks") })
        CivsRoadMaintenance.prepare(town, listOf(foreign)).targets shouldBe emptyList()
    }

    test("one pavement cell pays one stone preserves the next building reserve and grants no items or training") {
        val city = upkeepCity()
        val work = upkeepWork(city)
        val reserve = CivsAutonomy.nextGoal(city)!!.cost.stone
        val before = city.copy(stock = Stock(20, reserve + 1, 7))
        val after = CivsRoadMaintenance.complete(before, work, 3_000)
        after.stock shouldBe Stock(20, reserve, 7)
        after.workers.single().skills shouldBe before.workers.single().skills
        after.workers.single().items shouldBe before.workers.single().items
        after.workers.single().inventory shouldBe before.workers.single().inventory
        after.workers.single().completedCycles shouldBe 1
        val receipt = CivRoadUpkeepReceipt(before.workers.single(), before.stock, work, 3_000)
        receipt.before(after) shouldBe before
        receipt.validated(after)
        shouldThrow<IllegalArgumentException> { CivsRoadMaintenance.complete(before.copy(stock = Stock(stone = reserve)), work, 3_000) }
        shouldThrow<IllegalArgumentException> { CivsRoadMaintenance.complete(after, work, 4_000) }
        for (invalid in listOf(work.copy(outputs = mapOf("COBBLESTONE" to 1L)),
            work.copy(changes = listOf(work.changes.single().let { it.copy(before = it.after) })),
            work.copy(changes = work.changes + work.changes), work.copy(equipmentAfter = emptyList())))
            shouldThrow<IllegalArgumentException> { CivsRoadMaintenance.complete(before, invalid, 3_000) }
        CivsRoadMaintenance.eligible(before.copy(constructionQueue = listOf(before.buildings.first().id))) shouldBe false
        CivsRoadMaintenance.eligible(before.copy(construction = CivConstruction(UUID.randomUUID(), before.buildings.first().id,
            2, 1, 0, ConstructionPhase.CONSTRUCTING, Stock()))) shouldBe false
    }

    test("fixed city iteration fairly shares the eight native probes with later towns and cached builders") {
        var tick = 0
        val native = mockk<CivsWorld>()
        val probes = mutableListOf<Pair<Int, UUID>>()
        every { native.captureRoadUpkeep(any(), any(), any()) } answers {
            probes += tick to firstArg<Settlement>().id
            null // A healthy town still needs to move its bounded inspection cursor.
        }
        every { native.roadUpkeepPending(any(), any()) } answers {
            probes += tick to firstArg<Settlement>().id
            true
        }
        val maintenance = CivsRoadMaintenance(native) { tick }
        val towns = (0..1).map { index ->
            val original = upkeepCity()
            val road = original.buildings.last().copy(bounds = CivBox(CivPosition(0, 63, 0), CivPosition(15, 65, 0)),
                transit = (0..15).map { CivPosition(it, 64, 0) })
            val city = original.copy(id = UUID(1, index.toLong()), buildings = original.buildings.dropLast(1) + road,
                workers = listOf(original.workers.single().copy(id = UUID(2, index.toLong()))))
            val plan = CivPlacementPlan(road.id, city.id, city.worldId, road, road.transit!!.map {
                CivPlanBlock(it.copy(y = it.y - 1), "minecraft:stone", "minecraft:cobblestone")
            })
            maintenance.publish(city, CivsRoadMaintenance.prepare(city, listOf(plan)))
            city
        }
        repeat(5) {
            towns.forEach { maintenance.plan(it, it.workers.single()) }
            tick++
        }
        probes.any { it.second == towns[1].id } shouldBe true
        probes.groupBy { it.first }.values.all { it.size <= 8 } shouldBe true
        // A travelling builder's repeated native-before probe joins the same fair queue.
        every { native.captureRoadUpkeep(any(), any(), any()) } answers {
            val city = firstArg<Settlement>()
            probes += tick to city.id
            val target = thirdArg<CivRoadUpkeep>()
            val worker = secondArg<CivWorker>()
            CivWorldWork(worker.id, worker.completedCycles, worker.assignment!!, target.floor.copy(z = 1, y = 64),
                listOf(CivPlanBlock(target.floor, "minecraft:stone", target.after)), status = "repairing-road",
                focus = target.floor, upkeep = target)
        }
        repeat(4) {
            towns.forEach { maintenance.plan(it, it.workers.single()) }
            tick++
        }
        val selected = towns.map { maintenance.plan(it, it.workers.single()) }
        selected.all { it != null } shouldBe true
        val before = probes.size
        repeat(4) {
            towns.forEach { maintenance.plan(it, it.workers.single()) }
            tick++
        }
        probes.drop(before).map { it.second }.toSet() shouldBe towns.map { it.id }.toSet()
        probes.groupBy { it.first }.values.all { it.size <= 8 } shouldBe true
    }

    test("native missing pavement needs immediate solid support and foreign blocks or body obstructions remain untouched") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = CivsWorksiteWorldFixture("upkeep-native").also(paper.server::addWorld)
            for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
            val city = upkeepCity().copy(worldId = world.uid)
            val cities = mutableListOf(city)
            val plugin = paper.createSimplePlugin("UpkeepNative")
            LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                CivsWorld(plugin, tasks, { 0L }) { cities }.use { native ->
                    val work = upkeepWork(city, "minecraft:air")
                    val p = work.changes.single().position
                    val floor = world.getBlockAt(p.x, p.y, p.z)
                    floor.type = Material.AIR
                    native.validateRoadUpkeep(city, work)
                    native.roadUpkeepPending(city, work) shouldBe true
                    val player = paper.addPlayer("OnRoad")
                    player.teleport(Location(world, p.x + .5, p.y.toDouble(), p.z + .5))
                    shouldThrow<CivsRejection> { native.validateRoadUpkeep(city, work) }.reason shouldBe "road-occupied"
                    player.teleport(Location(world, 4.5, 64.0, .5))
                    native.roadUpkeepResidentOccupied = { _, at -> at == p }
                    shouldThrow<CivsRejection> { native.validateRoadUpkeep(city, work) }.reason shouldBe "road-occupied"
                    native.roadUpkeepResidentOccupied = { _, _ -> false }
                    world.getBlockAt(p.x, p.y - 1, p.z).type = Material.AIR
                    shouldThrow<CivsRejection> { native.validateRoadUpkeep(city, work) }.reason shouldBe "autonomy-no-path"
                    world.getBlockAt(p.x, p.y - 1, p.z).type = Material.STONE
                    for (foreign in listOf(Material.CHEST, Material.STONE_BRICKS, Material.WATER)) {
                        floor.type = foreign
                        shouldThrow<CivsRejection> { native.validateRoadUpkeep(city,
                            work.copy(changes = listOf(work.changes.single().copy(before = floor.blockData.asString)))) }
                    }
                    floor.type = Material.AIR
                    world.getBlockAt(p.x, p.y + 1, p.z).type = Material.STONE
                    shouldThrow<CivsRejection> { native.validateRoadUpkeep(city, work) }.reason shouldBe "world-changed"
                    world.getBlockAt(p.x, p.y + 1, p.z).type = Material.AIR
                    floor.type = Material.GRASS_BLOCK
                    val lower = world.getBlockAt(p.x, p.y + 1, p.z)
                    val upper = world.getBlockAt(p.x, p.y + 2, p.z)
                    lower.type = Material.SHORT_GRASS
                    val target = requireNotNull(work.upkeep)
                    val mowing = requireNotNull(native.captureRoadUpkeep(city, city.workers.single(), target))
                    mowing.changes.size shouldBe 2
                    mowing.changes.last().after shouldBe "minecraft:air"
                    native.validateRoadUpkeep(city, mowing)
                    lower.type = Material.DANDELION
                    native.captureRoadUpkeep(city, city.workers.single(), target) shouldBe null
                    shouldThrow<CivsRejection> { native.validateRoadUpkeep(city, mowing) }.reason shouldBe "world-changed"
                    lower.blockData = Bukkit.createBlockData("minecraft:tall_grass[half=lower]")
                    upper.blockData = Bukkit.createBlockData("minecraft:tall_grass[half=upper]")
                    val tall = requireNotNull(native.captureRoadUpkeep(city, city.workers.single(), target))
                    tall.changes.size shouldBe 3
                    upper.type = Material.AIR
                    native.captureRoadUpkeep(city, city.workers.single(), target) shouldBe null // Never clear an orphan plant.
                    native.validateRoadUpkeep(city, tall) // A sealed receipt can resume its already cleared upper half.
                    lower.type = Material.AIR
                    val stairState = "minecraft:cobblestone_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"
                    val step = work.copy(changes = listOf(CivPlanBlock(p, floor.blockData.asString, stairState)),
                        upkeep = target.copy(after = stairState))
                    val attachment = world.getBlockAt(p.x - 1, p.y, p.z)
                    attachment.blockData = Bukkit.createBlockData("minecraft:wall_torch[facing=west]")
                    shouldThrow<CivsRejection> { native.validateRoadUpkeep(city, step) }.reason shouldBe "protected-terrain"
                    attachment.type = Material.STONE
                    native.validateRoadUpkeep(city, step)
                    floor.type = Material.COBBLESTONE
                    native.roadUpkeepPending(city, work) shouldBe false
                    native.validateRoadUpkeep(city, work) // Recovery accepts its already written endpoint.
                    val house = city.buildings.first().copy(id = UUID(0, 90), type = BuildingType.HOUSE,
                        anchor = p, bounds = CivBox(p, p.copy(x = 1, y = 66, z = 1)), entrance = p.copy(z = 2, y = 64))
                    val changed = city.copy(buildings = city.buildings + house)
                    shouldThrow<CivsRejection> { native.validateRoadUpkeep(changed, work) }.reason shouldBe "building-overlap"
                }
            }
        }
    }

    test("weed clearance stays in the exact paid floor receipt and flowers or incomplete tall plants are never admitted") {
        val city = upkeepCity()
        val work = upkeepWork(city)
        val p = work.changes.single().position
        for ((lower, upper, count) in listOf(Triple("minecraft:short_grass", "minecraft:air", 1),
            Triple("minecraft:fern", "minecraft:air", 1),
            Triple("minecraft:tall_grass[half=lower]", "minecraft:tall_grass[half=upper]", 2),
            Triple("minecraft:large_fern[half=lower]", "minecraft:large_fern[half=upper]", 2))) {
            val cover = requireNotNull(CivsRoadMaintenance.covers(p, lower, upper))
            cover.size shouldBe count
            val mowing = work.copy(changes = work.changes + cover)
            CivsRoadMaintenance.complete(city, mowing, 3_000).stock.stone shouldBe city.stock.stone - 1
            shouldThrow<IllegalArgumentException> { CivsRoadMaintenance.complete(city,
                mowing.copy(changes = work.changes + cover.map { it.copy(after = "minecraft:stone") }), 3_000) }
        }
        for ((lower, upper) in listOf("minecraft:dandelion" to "minecraft:air",
            "minecraft:tall_grass[half=lower]" to "minecraft:air",
            "minecraft:air" to "minecraft:tall_grass[half=upper]",
            "minecraft:fern" to "minecraft:torch")) CivsRoadMaintenance.covers(p, lower, upper) shouldBe null
    }

    test("paving stair collision keeps a half-height downhill entry in all four directions and native corners") {
        MockBukkitTestRuntime.open().use {
            for ((facing, low) in listOf("north" to (.5 to .75), "south" to (.5 to .25),
                "east" to (.25 to .5), "west" to (.75 to .5))) {
                val data = Bukkit.createBlockData("minecraft:cobblestone_stairs[facing=$facing,half=bottom,shape=straight,waterlogged=false]")
                val boxes = roadUpkeepBoxes(data)
                fun top(x: Double, z: Double) = boxes.filter { x in it.minX..it.maxX && z in it.minZ..it.maxZ }.maxOf { it.maxY }
                top(low.first, low.second) shouldBe .5
                top(1 - low.first, 1 - low.second) shouldBe 1.0
                for (shape in listOf("inner_left", "inner_right", "outer_left", "outer_right")) {
                    val corner = roadUpkeepBoxes(Bukkit.createBlockData(data.asString.replace("straight", shape)))
                    corner.first() shouldBe CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, .5, 1.0)
                    corner.all { it.minX >= 0 && it.minZ >= 0 && it.maxX <= 1 && it.maxZ <= 1 && it.maxY <= 1 } shouldBe true
                }
            }
        }
    }
})

private fun upkeepCity(): Settlement {
    val hall = CivBuilding(UUID(0, 1), BuildingType.TOWN_HALL, 1, CivPosition(-60, 63, -60), 0, "fixture/hall",
        CivBox(CivPosition(-60, 63, -60), CivPosition(-58, 68, -58)), CivPosition(-59, 64, -57), BuildingPhase.ACTIVE)
    val builder = hall.copy(id = UUID(0, 2), type = BuildingType.BUILDER_HUT, anchor = CivPosition(-60, 63, 30),
        bounds = CivBox(CivPosition(-60, 63, 30), CivPosition(-58, 68, 32)), entrance = CivPosition(-59, 64, 33), schematicId = "fixture/hut")
    val road = hall.copy(id = UUID(0, 3), type = BuildingType.ROAD, anchor = CivPosition(0, 63, 0),
        bounds = CivBox(CivPosition(0, 63, 0), CivPosition(0, 65, 0)), entrance = CivPosition(0, 64, 0),
        schematicId = "fixture/road", transit = listOf(CivPosition(0, 64, 0)))
    return CivsRules.validate(Settlement(UUID(0, 10), UUID(0, 11), "Upkeep", UUID(0, 12), CivPosition(0, 64, 0), 96,
        "caledonia", Stock(1_000, 1_000, 1_000), listOf(hall, builder, road),
        listOf(CivWorker(UUID(0, 13), "Builder", builder.id, nextDeliveryAt = 1)), autonomy = CivAutonomy()))
}

private fun upkeepWork(city: Settlement, before: String = "minecraft:grass_block[snowy=false]"): CivWorldWork {
    val road = city.buildings.last()
    val p = road.transit!!.single().copy(y = 63)
    val plan = CivPlacementPlan(road.id, city.id, city.worldId, road, listOf(CivPlanBlock(p, before, "minecraft:cobblestone")))
    val target = CivsRoadMaintenance.prepare(city, listOf(plan)).targets.single()
    val worker = city.workers.single()
    return CivWorldWork(worker.id, worker.completedCycles, worker.assignment!!, p.copy(x = 1, y = 64), plan.blocks,
        status = "repairing-road", focus = p, upkeep = target)
}

private fun upkeepApproach(city: Settlement, building: CivBuilding, route: List<CivPosition>): CivPlacementPlan =
    CivPlacementPlan(building.id, city.id, city.worldId, building, emptyList(), terrain = CivTerraformPlan(
        CivBox(CivPosition(route.minOf { it.x }, route.minOf { it.y } - 1, route.minOf { it.z }),
            CivPosition(route.maxOf { it.x }, route.maxOf { it.y } + 1, route.maxOf { it.z })),
        emptyList(), route, building.anchor.y, 0, 0))
