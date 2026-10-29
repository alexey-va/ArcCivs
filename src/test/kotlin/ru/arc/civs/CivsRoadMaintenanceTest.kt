package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.GsonBuilder
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
import java.nio.file.Files
import java.nio.file.Path

class CivsRoadMaintenanceTest : FunSpec({
    test("physical Auto road upkeep consumes one carried cobblestone without closed stock or production credit") {
        val base = upkeepCity()
        val worker = CivsEquipment.replace(base.workers.single(), MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { null }
            .also { it[CivsEquipment.BAG] = CivResidentItem("COBBLESTONE", 2) })
        val city = CivsRules.validate(base.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL),
            workers = listOf(worker)))
        CivsRoadMaintenance.eligible(city) shouldBe true
        val work = upkeepWork(city).copy(inputs = mapOf("COBBLESTONE" to 1L))
        val after = CivsRoadMaintenance.complete(city, work, 3_000)
        after.stock shouldBe Stock()
        CivsEquipment.usableCount(after.workers.single(), "COBBLESTONE") shouldBe 1
        after.workers.single().skills shouldBe worker.skills
        after.workers.single().completedCycles shouldBe 1L
        val receipt = CivRoadUpkeepReceipt(worker, city.stock, work, 3_000)
        receipt.before(after) shouldBe city
        receipt.validated(after)
        Gson().fromJson(Gson().toJson(receipt), CivRoadUpkeepReceipt::class.java).validated(after)
        shouldThrow<IllegalArgumentException> { CivsRoadMaintenance.complete(city, work.copy(inputs = emptyMap()), 3_000) }
        shouldThrow<IllegalArgumentException> { CivsRoadMaintenance.complete(city.copy(
            workers = listOf(worker.copy(items = null, inventory = emptyMap()))), work, 3_000) }
        CivsRoadMaintenance.eligible(city.copy(autonomy = null)) shouldBe false
    }

    test("native upkeep requests supplies only after a damaged column is captured and drops stale worker requests") {
        var tick = 0
        val native = mockk<CivsWorld>()
        val maintenance = CivsRoadMaintenance(native) { tick }
        val city = upkeepCity().copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL))
        val worker = city.workers.single()
        val work = upkeepWork(city)
        val road = city.buildings.last()
        val retained = CivPlacementPlan(road.id, city.id, city.worldId, road, work.changes)
        maintenance.publish(city, CivsRoadMaintenance.prepare(city, listOf(retained)))
        maintenance.requirements(city) shouldBe emptyMap()
        every { native.captureRoadUpkeep(any(), any(), any()) } returns null
        maintenance.plan(city, worker) shouldBe null
        maintenance.requirements(city) shouldBe emptyMap()
        tick++
        every { native.captureRoadUpkeep(any(), any(), any()) } returns work
        every { native.roadUpkeepPending(any(), any()) } returns true
        maintenance.plan(city, worker) shouldBe work.copy(inputs = mapOf("COBBLESTONE" to 1L))
        maintenance.requirements(city) shouldBe mapOf(worker.id to mapOf("COBBLESTONE" to 1L))
        maintenance.requirements(city.copy(workers = listOf(worker.copy(completedCycles = 1)))) shouldBe emptyMap()
        maintenance.requirements(city.copy(autonomy = CivAutonomy(paused = true))) shouldBe emptyMap()
        maintenance.requirements(city.copy(autonomy = null)) shouldBe emptyMap()
        maintenance.invalidate(worker.id)
        maintenance.requirements(city) shouldBe emptyMap()
    }

    test("native upkeep receipt binds the exact local hut debit and rejects unpaid or foreign supplies") {
        val city = upkeepCity().copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL))
        val work = upkeepWork(city).copy(inputs = mapOf("COBBLESTONE" to 1L))
        val worker = city.workers.single()
        val hut = city.building(work.buildingId)!!
        val debit = CivInventorySlotChange(hut.anchor, "CHEST", 0, CivPlainItem("COBBLESTONE", 2), CivPlainItem("COBBLESTONE", 1))
        val after = CivsRoadMaintenance.complete(city, work, 3_000, nativePaid = true)
        val receipt = CivRoadUpkeepReceipt(worker, city.stock, work, 3_000)
        receipt.validated(after, listOf(debit))
        for (invalid in listOf(emptyList(), listOf(debit.copy(container = city.buildings.first().anchor)),
            listOf(debit.copy(after = null)), listOf(debit.copy(before = null, after = CivPlainItem("COBBLESTONE", 1))),
            listOf(debit.copy(before = CivPlainItem("STONE", 2), after = CivPlainItem("STONE", 1)))))
            shouldThrow<IllegalArgumentException> { receipt.validated(after, invalid) }
        shouldThrow<IllegalArgumentException> { receipt.validated(after.copy(stock = Stock(stone = 1)), listOf(debit)) }
    }

    test("Auto2 short saved hill is flattened once for compatible paid owners and survives exact receipt replay") {
        val originalRoute = listOf(64, 64, 64, 65, 66, 67, 67, 67, 66, 65, 64, 64, 64)
            .mapIndexed { z, y -> CivPosition(0, y, z) }
        val base = upkeepCity()
        val source = base.buildings.last().copy(type = BuildingType.HOUSE, anchor = CivPosition(0, 63, 20),
            bounds = CivBox(CivPosition(0, 63, 20), CivPosition(5, 69, 25)), entrance = originalRoute.last(), transit = null,
            schematicId = CivsBlueprints.schematicId(base.style, BuildingType.HOUSE, 1))
        val otherRoute = originalRoute + (1..5).map { CivPosition(it, 64, 12) }
        val other = source.copy(id = UUID(0, 40), type = BuildingType.FORESTER, anchor = CivPosition(30, 63, 20),
            bounds = CivBox(CivPosition(30, 63, 20), CivPosition(35, 69, 25)), entrance = otherRoute.last(),
            schematicId = CivsBlueprints.schematicId(base.style, BuildingType.FORESTER, 1))
        val town = base.copy(buildings = base.buildings.dropLast(1) + source + other)
        val plans = listOf(upkeepApproach(town, source, originalRoute), upkeepApproach(town, other, otherRoute))
        val retained = Gson().toJson(plans)
        val first = CivsRoadMaintenance.prepare(town, plans).targets.single { it.routeOverride != null }
        first.routeOverride!!.route.map { it.y } shouldBe List(13) { 64 }
        first.linkedRouteOverrides!!.keys shouldBe setOf(other.id.toString())
        first.after shouldBe "minecraft:dirt_path"
        val worker = town.workers.single()
        val work = CivWorldWork(worker.id, worker.completedCycles, worker.assignment!!, CivPosition(0, 64, 2),
            listOf(CivPlanBlock(first.floor, "minecraft:dirt", first.after)),
            status = "repairing-road", focus = first.floor, upkeep = first)
        val after = CivsRoadMaintenance.complete(town, work, 3_000)
        after.stock.stone shouldBe town.stock.stone - 1
        after.workers.single().skills shouldBe worker.skills
        after.roadRouteOverrides!!.keys shouldBe setOf(source.id.toString(), other.id.toString())
        val receipt = CivRoadUpkeepReceipt(worker, town.stock, work, 3_000)
        receipt.before(after) shouldBe town
        Gson().fromJson(Gson().toJson(receipt), CivRoadUpkeepReceipt::class.java).validated(after)
        CivsRules.validate(Gson().fromJson(Gson().toJson(after), Settlement::class.java)) shouldBe after
        CivAutonomousAccess.from(after, plans).routes.values.all { route -> route.all { it.y == 64 } } shouldBe true
        Gson().toJson(plans) shouldBe retained
        CivsRoadMaintenance.prepare(after, plans).targets.none { it.routeOverride != null } shouldBe true

        // A genuine crossing and an authored road keep their paid grade, even on the same small hill.
        val crossingRoute = (-3..5).map { CivPosition(it, 67, 6) }
        val crossing = other.copy(entrance = crossingRoute.last())
        val crossed = town.copy(buildings = town.buildings.dropLast(1) + crossing)
        CivsRoadMaintenance.prepare(crossed, listOf(plans.first(), upkeepApproach(crossed, crossing, crossingRoute)))
            .targets.none { it.routeOverride != null } shouldBe true
        val road = base.buildings.last().copy(id = UUID(0, 41), anchor = CivPosition(0, 66, 6),
            bounds = CivBox(CivPosition(0, 66, 6), CivPosition(0, 68, 6)), entrance = CivPosition(0, 67, 6),
            transit = listOf(CivPosition(0, 67, 6)))
        val withRoad = town.copy(buildings = town.buildings + road)
        val roadPlan = CivPlacementPlan(road.id, withRoad.id, withRoad.worldId, road,
            listOf(CivPlanBlock(road.anchor, "minecraft:stone", "minecraft:cobblestone")))
        CivsRoadMaintenance.prepare(withRoad, plans + roadPlan).targets.none { it.routeOverride != null } shouldBe true

        MockBukkitTestRuntime.open().use { paper ->
            val world = CivsWorksiteWorldFixture("upkeep-short-hill", collisionForData = { data ->
                if (CivsRoadMaintenance.surface(data.asString)) roadUpkeepBoxes(data).map { it.at(CivPosition(0, 0, 0)) } else null
            }).also(paper.server::addWorld)
            for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
            val oldProfile = CivsAutonomousTerraform.roadProfile(originalRoute)
            val levels = oldProfile.mapValues { it.value.y }
            for (floor in oldProfile.values) {
                for (y in 64..floor.y) world.getBlockAt(floor.x, y, floor.z).type = Material.DIRT
                world.getBlockAt(floor.x, floor.y, floor.z).blockData = Bukkit.createBlockData(CivsSettlementLayout.pavement(floor, levels))
            }
            var city = town.copy(worldId = world.uid)
            val nativePlans = plans.map { it.copy(worldId = world.uid) }
            val nativeFirst = CivsRoadMaintenance.prepare(city, nativePlans).targets.single { it.routeOverride != null }
            val plugin = paper.createSimplePlugin("ShortHill")
            val bounds = CivBox(CivPosition(-3, 61, -2), CivPosition(3, 70, 14))
            fun cells() = (-3..3).flatMap { x -> (-2..14).flatMap { z -> (61..70).map { y ->
                mapOf("position" to CivPosition(x, y, z), "data" to world.getBlockAt(x, y, z).blockData.asString)
            } } }
            val before = cells()
            LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                CivsWorld(plugin, tasks, { 0L }) { listOf(city) }.use { native ->
                    for (unsafe in listOf(Material.CHEST, Material.WATER, Material.GRAVEL, Material.OAK_PLANKS)) {
                        val p = CivPosition(1, 66, 6)
                        val old = world.getBlockAt(p.x, p.y, p.z).blockData
                        world.getBlockAt(p.x, p.y, p.z).type = unsafe
                        native.captureRoadUpkeep(city, city.workers.single(), nativeFirst) shouldBe null
                        world.getBlockAt(p.x, p.y, p.z).blockData = old
                    }
                    var count = 0
                    var tick = 0
                    val maintenance = CivsRoadMaintenance(native) { tick }
                    maintenance.publish(city, CivsRoadMaintenance.prepare(city, nativePlans))
                    maintenance.admitDevelopment(city) shouldBe false
                    var admitted = false
                    repeat(128) {
                        if (admitted) return@repeat
                        tick++
                        val current = maintenance.plan(city, city.workers.single())
                        maintenance.backfillSurfaces(city)
                        if (current == null) {
                            admitted = maintenance.admitDevelopment(city)
                            return@repeat
                        }
                        maintenance.admitDevelopment(city) shouldBe false
                        if (count == 1) repeat(2) {
                            // Walking has not applied the lower column; peak probes still have no standing position.
                            tick++
                            maintenance.plan(city, city.workers.single()) shouldBe current
                            maintenance.backfillSurfaces(city)
                        }
                        val updated = CivsRoadMaintenance.complete(city, current, 3_000L + count * 3_000L)
                        CivRoadUpkeepReceipt(city.workers.single(), city.stock, current, 3_000L + count * 3_000L).validated(updated)
                        current.changes.asReversed().forEach { change ->
                            val block = world.getBlockAt(change.position.x, change.position.y, change.position.z)
                            block.blockData = Bukkit.createBlockData(change.after)
                        }
                        native.validateRoadUpkeep(city, current)
                        city = updated
                        val upkeep = requireNotNull(current.upkeep)
                        if (upkeep.routeOverride != null) {
                            maintenance.awaitGradeNetwork(city)
                            maintenance.admitDevelopment(city) shouldBe false
                            val network = CivsRoadMaintenance.prepare(city, nativePlans)
                            val peak = network.targets.single { it.floor == CivPosition(0, 63, 6) }
                            native.captureRoadUpkeep(city, city.workers.single(), peak) shouldBe null
                            maintenance.publish(city, network)
                        } else {
                            // This is the existing inventory-retirement hook, after exact native application.
                            maintenance.upkeepRetired(city, upkeep)
                        }
                        count++
                    }
                    admitted shouldBe true
                    (count > 1) shouldBe true
                    city.stock.stone shouldBe town.stock.stone - count
                    val flat = requireNotNull(city.roadRouteOverrides)[source.id.toString()]!!.route
                    flat.all { it.y == 64 } shouldBe true
                    for (z in 3..9) for (x in -1..1) {
                        world.getBlockAt(x, 63, z).type shouldBe Material.DIRT_PATH
                        (64..67).all { y -> world.getBlockAt(x, y, z).type.isAir } shouldBe true
                    }
                    (roadHalfStepWalk(world, flat) != null) shouldBe true
                    (roadHalfStepWalk(world, flat.asReversed()) != null) shouldBe true
                    System.getenv("CIVS_ROAD_PREVIEW_OUT")?.let { directory ->
                        val out = Path.of(directory).also(Files::createDirectories)
                        Files.writeString(out.resolve("saved-short-hill.json"), GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                            "kind" to "production-algorithm-offline-fixture-not-live-acceptance", "bounds" to bounds,
                            "worldOffset" to CivPosition(-1868, 0, -2718), "originalRoute" to originalRoute, "route" to flat,
                            "before" to before, "after" to cells(), "paidColumns" to count)))
                    }
                }
            }
        }
    }

    test("a saved gate approach shares a short hill cut without changing gate transit or its immutable journal") {
        val original = listOf(64, 64, 64, 65, 66, 67, 67, 67, 66, 65, 64, 64, 64)
            .mapIndexed { z, y -> CivPosition(0, y, z) }
        val base = upkeepCity()
        val source = base.buildings.last().copy(type = BuildingType.FORESTER, anchor = CivPosition(0, 63, 40),
            bounds = CivBox(CivPosition(0, 63, 40), CivPosition(5, 72, 45)), entrance = original.last(), transit = null,
            schematicId = CivsBlueprints.schematicId(base.style, BuildingType.FORESTER, 1))
        val gateRoute = original + (1..5).map { CivPosition(it, 64, 12) }
        val gate = source.copy(id = UUID(0, 42), type = BuildingType.GATE, anchor = CivPosition(4, 63, 11),
            bounds = CivBox(CivPosition(4, 63, 11), CivPosition(8, 68, 15)), entrance = gateRoute.last(),
            schematicId = "fixture/gate", transit = (4..8).flatMap { x -> (11..15).map { z -> CivPosition(x, 64, z) } })
        val city = base.copy(buildings = base.buildings.dropLast(1) + source + gate)
        val plans = listOf(upkeepApproach(city, source, original), upkeepApproach(city, gate, gateRoute))
        val immutable = Gson().toJson(plans)
        val targets = CivsRoadMaintenance.prepare(city, plans).targets.filter { it.routeOverride != null }
        targets.size shouldBe 1
        val target = targets.single()
        target.linkedRouteOverrides!!.keys shouldBe setOf(gate.id.toString())
        val worker = city.workers.single()
        val work = CivWorldWork(worker.id, worker.completedCycles, worker.assignment!!, CivPosition(0, 64, 2),
            listOf(CivPlanBlock(target.floor, "minecraft:dirt", target.after)),
            status = "repairing-road", focus = target.floor, upkeep = target)
        val after = CivsRoadMaintenance.complete(city, work, 3_000)
        after.building(gate.id) shouldBe gate
        val access = CivAutonomousAccess.from(after, plans)
        access.routes.getValue(source.id).all { it.y == 64 } shouldBe true
        access.routes.getValue(gate.id).all { it.y == 64 } shouldBe true
        val receipt = CivRoadUpkeepReceipt(worker, city.stock, work, 3_000)
        Gson().fromJson(Gson().toJson(receipt), CivRoadUpkeepReceipt::class.java).validated(after)
        receipt.before(after) shouldBe city
        Gson().toJson(plans) shouldBe immutable
        // Authored road transit still owns its grade independently of an approach journal.
        shouldThrow<IllegalArgumentException> { target.linkedRouteOverrides!!.getValue(gate.id.toString())
            .validated(city, gate.copy(type = BuildingType.ROAD)) }
    }

    test("local road cuts compose against the immutable journal but reject long slopes and invented grades") {
        val town = upkeepCity()
        val source = town.buildings.last().copy(type = BuildingType.HOUSE, entrance = CivPosition(0, 64, 17), transit = null)
        val original = listOf(64, 64, 65, 66, 65, 64, 64, 64, 64, 65, 66, 65, 64, 64, 64, 64, 64, 64)
            .mapIndexed { z, y -> CivPosition(0, y, z) }
        val first = requireNotNull(CivsRoadMaintenance.humpOverride(town, source, original))
        val nextCity = town.copy(roadRouteOverrides = mapOf(source.id.toString() to first))
        val next = requireNotNull(CivsRoadMaintenance.humpOverride(nextCity, source, first.route))
        next.originalRoute shouldBe original
        next.runs() shouldBe listOf(2..4, 9..11)
        next.route.all { it.y == 64 } shouldBe true
        next.validated(town, source)
        val before = nextCity.copy(buildings = nextCity.buildings.dropLast(1) + source)
        val floor = next.route[9].let { it.copy(y = it.y - 1) }
        val profile = CivsAutonomousTerraform.roadProfile(next.route)
        val neighbours = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1).mapNotNull { (x, z) ->
            profile[civsTerrainKey(floor.x + x, floor.z + z)]
        }
        val target = CivRoadUpkeep(before.id, before.worldId, before.owner, source.id, source, floor,
            "minecraft:dirt_path", next.route, neighbours, next, previousRouteOverrides = before.roadRouteOverrides)
        val worker = before.workers.single()
        val work = CivWorldWork(worker.id, worker.completedCycles, worker.assignment!!, floor.copy(x = 1, y = 64),
            listOf(CivPlanBlock(floor, "minecraft:dirt", target.after)), status = "repairing-road", focus = floor, upkeep = target)
        val after = CivsRoadMaintenance.complete(before, work, 3_000)
        val receipt = CivRoadUpkeepReceipt(worker, before.stock, work, 3_000)
        receipt.before(after) shouldBe before
        Gson().fromJson(Gson().toJson(receipt), CivRoadUpkeepReceipt::class.java).validated(after)
        val long = (listOf(64) + List(12) { 65 } + listOf(64, 64, 64, 64, 64)).mapIndexed { z, y -> CivPosition(0, y, z) }
        CivsRoadMaintenance.humpOverride(town, source, long) shouldBe null
        val slope = (0..17).map { CivPosition(0, 64 + it / 3, it) }
        CivsRoadMaintenance.humpOverride(town, source.copy(entrance = slope.last()), slope) shouldBe null
        shouldThrow<IllegalArgumentException> { next.copy(route = next.route.map { it.copy(y = 63) }).validated(town, source) }
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

    test("accepted ROAD columns retain their grade and adopt dirt path while foreign finishes are omitted") {
        val city = upkeepCity()
        val road = city.buildings.last().copy(bounds = CivBox(CivPosition(0, 63, -1), CivPosition(2, 65, 1)),
            transit = (0..2).flatMap { x -> (-1..1).map { z -> CivPosition(x, 64, z) } })
        val town = city.copy(buildings = city.buildings.dropLast(1) + road)
        val cells = road.transit!!.map { CivPlanBlock(it.copy(y = it.y - 1), "minecraft:grass_block[snowy=false]", "minecraft:cobblestone") }
        val plan = CivPlacementPlan(road.id, town.id, town.worldId, road, cells)
        val network = CivsRoadMaintenance.prepare(town, listOf(plan))
        network.targets.map { it.floor }.toSet() shouldBe cells.map { it.position }.toSet()
        network.targets.all { it.after == "minecraft:dirt_path" } shouldBe true
        val foreign = plan.copy(blocks = cells.map { it.copy(after = "minecraft:stone_bricks") })
        CivsRoadMaintenance.prepare(town, listOf(foreign)).targets shouldBe emptyList()
    }

    test("ROAD upkeep retains its exact authored plank landing without inventing an ordered turn") {
        val city = upkeepCity()
        val floor = CivPosition(0, 63, 0)
        val floors = listOf(CivPosition(-1, 62, 0), floor, CivPosition(0, 63, 1))
        val road = city.buildings.last().copy(anchor = floor,
            bounds = CivBox(CivPosition(-1, 62, 0), CivPosition(0, 65, 1)),
            transit = floors.map { it.copy(y = it.y + 1) })
        val town = city.copy(buildings = city.buildings.dropLast(1) + road)
        val cells = floors.map { CivPlanBlock(it, "minecraft:stone",
            if (it == floor) "minecraft:dark_oak_planks" else "minecraft:cobblestone") }
        val plan = CivPlacementPlan(road.id, town.id, town.worldId, road, cells)
        val network = CivsRoadMaintenance.prepare(town, listOf(plan))
        val landing = network.targets.single { it.floor == floor }
        landing.after shouldBe "minecraft:dark_oak_planks"
        landing.route shouldBe null
        landing.validated(town)
        shouldThrow<IllegalArgumentException> { landing.copy(authoredAfter = null).validated(town) }
        shouldThrow<IllegalArgumentException> { landing.copy(after = "minecraft:spruce_planks").validated(town) }
        val ordinary = network.targets.single { it.floor == floors.first() }
        shouldThrow<IllegalArgumentException> { ordinary.copy(after = "minecraft:dark_oak_planks").validated(town) }
        val old = plan.copy(blocks = cells.map { it.copy(after = "minecraft:cobblestone") })
        CivsRoadMaintenance.prepare(town, listOf(old)).targets.single { it.floor == floor }.after shouldBe
            "minecraft:dark_oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"
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
        CivsRoadMaintenance.eligible(before.copy(constructionQueue = listOf(before.buildings.first().id))) shouldBe true
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
                    native.validateRoadUpkeep(city, work) // Occupants do not veto ordinary road resurfacing.
                    player.teleport(Location(world, 4.5, 64.0, .5))
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
                    floor.type = Material.DIRT_PATH
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
            roadUpkeepBoxes(Bukkit.createBlockData("minecraft:dirt_path")) shouldBe
                listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, 15.0 / 16.0, 1.0))
            for (wood in listOf("oak", "spruce", "dark_oak"))
                roadUpkeepBoxes(Bukkit.createBlockData("minecraft:${wood}_planks")) shouldBe
                    listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
            for (wood in listOf("cobblestone", "oak", "spruce", "dark_oak"))
                for ((facing, low) in listOf("north" to (.5 to .75), "south" to (.5 to .25),
                "east" to (.25 to .5), "west" to (.75 to .5))) {
                val data = Bukkit.createBlockData("minecraft:${wood}_stairs[facing=$facing,half=bottom,shape=straight,waterlogged=false]")
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

    test("a paid turn correction preserves historical routes and builds a flat intermediate block between straight flights") {
        val (town, source, originalRoute) = upkeepBend()
        val plan = upkeepApproach(town, source, originalRoute)
        val saved = Gson().toJson(plan)
        val repair = requireNotNull(CivsRoadMaintenance.turnOverride(town, source, originalRoute))
        repair.changed() shouldBe listOf(4, 5)
        repair.route[4] shouldBe CivPosition(0, 69, 1)
        repair.route[5] shouldBe CivPosition(1, 70, 1)
        val first = CivsRoadMaintenance.prepare(town, listOf(plan)).targets.single { it.routeOverride != null }
        first.floor shouldBe CivPosition(0, 68, 1)
        first.after shouldBe "minecraft:dark_oak_planks"
        val worker = town.workers.single()
        val work = CivWorldWork(worker.id, worker.completedCycles, worker.assignment!!, CivPosition(0, 69, 0),
            listOf(CivPlanBlock(first.floor, "minecraft:dirt", first.after)),
            status = "repairing-road", focus = first.floor, upkeep = first)
        val after = CivsRoadMaintenance.complete(town, work, 3_000)
        after.stock.stone shouldBe town.stock.stone - 1
        after.roadRouteOverrides shouldBe mapOf(source.id.toString() to repair)
        val receipt = CivRoadUpkeepReceipt(worker, town.stock, work, 3_000)
        receipt.before(after) shouldBe town
        receipt.validated(after)
        shouldThrow<IllegalArgumentException> { CivsRoadMaintenance.complete(after, work, 3_000) }
        val gson = Gson()
        CivsRules.validate(gson.fromJson(gson.toJson(after), Settlement::class.java)) shouldBe after
        gson.fromJson(gson.toJson(town), Settlement::class.java).roadRouteOverrides shouldBe null
        gson.fromJson(gson.toJson(receipt), CivRoadUpkeepReceipt::class.java).validated(after)
        shouldThrow<IllegalArgumentException> { CivsRules.validate(town.copy(roadRouteOverrides = emptyMap())) }
        shouldThrow<IllegalArgumentException> { CivsRules.validate(after.copy(roadRouteOverrides =
            mapOf(source.id.toString() to repair.copy(route = repair.route.dropLast(1))))) }
        CivAutonomousAccess.from(after, listOf(plan)).routes.getValue(source.id) shouldBe repair.route
        Gson().toJson(plan) shouldBe saved

        val network = CivsRoadMaintenance.prepare(after, listOf(plan))
        network.targets.none { it.routeOverride != null } shouldBe true
        val profile = network.targets.associateBy { civsTerrainKey(it.floor.x, it.floor.z) }
        for (point in repair.route) profile.getValue(civsTerrainKey(point.x, point.z)).floor shouldBe point.copy(y = point.y - 1)
        for (x in -1..1) profile.getValue(civsTerrainKey(x, -1)).floor.y shouldBe 67
        val bend = profile.getValue(civsTerrainKey(0, 1))
        bend.after shouldBe "minecraft:dark_oak_planks"
        val upper = profile.getValue(civsTerrainKey(1, 1))
        upper.floor.y shouldBe 69
        upper.after shouldBe "minecraft:dark_oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"
        profile.getValue(civsTerrainKey(2, 1)).after shouldBe upper.after
        val reserved = CivsAutonomousGeometry.futureBounds(after, source)
        network.targets.none { CivsAutonomousGeometry.horizontalContains(reserved, it.floor) } shouldBe true
        val retained = upkeepCity().buildings.last().copy(id = UUID(0, 91), anchor = CivPosition(-1, 69, 0),
            bounds = CivBox(CivPosition(-1, 69, 0), CivPosition(-1, 71, 0)),
            entrance = CivPosition(-1, 70, 0), transit = listOf(CivPosition(-1, 70, 0)))
        val withRoad = after.copy(buildings = after.buildings + retained)
        val roadPlan = CivPlacementPlan(retained.id, withRoad.id, withRoad.worldId, retained,
            listOf(CivPlanBlock(retained.anchor, "minecraft:stone", "minecraft:cobblestone")))
        val retainedNetwork = CivsRoadMaintenance.prepare(withRoad, listOf(plan, roadPlan))
        retainedNetwork.targets.single { it.floor.x == -1 && it.floor.z == 0 }.floor.y shouldBe 69
        retainedNetwork.targets.none { it.floor == CivPosition(-1, 68, 0) } shouldBe true
        // Recovery of historical cobble/.89 wood receipts uses the original grade and original city snapshot.
        val old = bend.copy(floor = CivPosition(0, 69, 1), route = originalRoute,
            neighbours = listOf(CivPosition(0, 68, 0), CivPosition(-1, 69, 1), CivPosition(1, 70, 1)))
        old.copy(after = CivsSettlementLayout.pavement(old.floor,
            (old.neighbours + old.floor).associate { civsTerrainKey(it.x, it.z) to it.y })).validated(town)
        old.copy(after = "minecraft:dark_oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]").validated(town)
        shouldThrow<IllegalArgumentException> { upper.copy(after = upper.after.replace("east", "north")).validated(after) }
        shouldThrow<IllegalArgumentException> { bend.copy(after = bend.after.replace("dark_oak", "spruce")).validated(after) }

        MockBukkitTestRuntime.open().use { paper ->
            val world = CivsWorksiteWorldFixture("upkeep-bend", collisionForData = { data ->
                if (CivsRoadMaintenance.surface(data.asString)) roadUpkeepBoxes(data).map { it.at(CivPosition(0, 0, 0)) } else null
            }).also(paper.server::addWorld)
            for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
            val states = network.targets.associate { it.floor to Bukkit.createBlockData(it.after) }
            states.forEach { (p, data) -> world.getBlockAt(p.x, p.y, p.z).setBlockData(data, false) }
            val walk = requireNotNull(roadHalfStepWalk(world, repair.route))
            (roadHalfStepWalk(world, repair.route.reversed()) != null) shouldBe true
            walk.zipWithNext().all { (a, b) -> kotlin.math.abs(a.y - b.y) <= 9.0 / 16.0 } shouldBe true
            // Merely putting a full landing at the old raised floor leaves a one-block jump.
            val oldFloor = CivPosition(0, 69, 1)
            world.getBlockAt(oldFloor.x, oldFloor.y, oldFloor.z).type = Material.DARK_OAK_PLANKS
            roadHalfStepWalk(world, repair.route) shouldBe null
            world.getBlockAt(oldFloor.x, oldFloor.y, oldFloor.z).type = Material.AIR
            System.getenv("CIVS_ROAD_PREVIEW_OUT")?.let { directory ->
                val out = Path.of(directory)
                Files.createDirectories(out)
                Files.writeString(out.resolve("bend-profile.json"), GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                    "kind" to "production-algorithm-offline-fixture-not-live-acceptance",
                    "worldOffset" to CivPosition(-1846, 0, -2697), "originalRoute" to originalRoute, "route" to repair.route,
                    "walk" to walk, "targets" to network.targets.map { target -> mapOf("floor" to target.floor, "after" to target.after,
                        "boxes" to roadUpkeepBoxes(states.getValue(target.floor))) }, "reserved" to reserved)))
            }
        }
    }

    test("bank clearance of healthy path journals only real cuts and rejects physics dependencies fluids containers and deep support") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = CivsWorksiteWorldFixture("upkeep-bank").also(paper.server::addWorld)
            for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
            val city = upkeepCity().copy(worldId = world.uid)
            val plugin = paper.createSimplePlugin("UpkeepBank")
            LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                CivsWorld(plugin, tasks, { 0L }) { listOf(city) }.use { native ->
                    val target = requireNotNull(upkeepWork(city).upkeep)
                    val p = target.floor
                    fun block(dx: Int = 0, dy: Int = 0) = world.getBlockAt(p.x + dx, p.y + dy, p.z)
                    block().type = Material.DIRT_PATH
                    for (dy in 1..4) block(dy = dy).type = Material.DIRT
                    val work = requireNotNull(native.captureRoadUpkeep(city, city.workers.single(), target))
                    work.changes.map { it.position } shouldBe (1..4).map { p.copy(y = p.y + it) }
                    (work.focus in work.changes.map { it.position }) shouldBe true
                    val after = CivsRoadMaintenance.complete(city, work, 3_000)
                    after.stock.stone shouldBe city.stock.stone - 1
                    CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, after, emptyList(),
                        worldChanges = work.changes, upkeep = CivRoadUpkeepReceipt(city.workers.single(), city.stock, work, 3_000)).validated()
                    native.validateRoadUpkeep(city, work)
                    block(dy = 5).type = Material.GRAVEL
                    shouldThrow<CivsRejection> { native.validateRoadUpkeep(city, work) }.reason shouldBe "protected-terrain"
                    block(dy = 5).type = Material.DIRT
                    native.captureRoadUpkeep(city, city.workers.single(), target) shouldBe null // Do not leave a roof over a capped open cut.
                    block(dy = 5).type = Material.AIR
                    block(1, 1).blockData = Bukkit.createBlockData("minecraft:wall_torch[facing=east]")
                    shouldThrow<CivsRejection> { native.validateRoadUpkeep(city, work) }.reason shouldBe "protected-terrain"
                    block(1, 1).type = Material.WATER
                    shouldThrow<CivsRejection> { native.validateRoadUpkeep(city, work) }.reason shouldBe "protected-terrain"
                    block(1, 1).type = Material.SHORT_GRASS
                    native.validateRoadUpkeep(city, work) // This unchanged plant attaches below, not to the cut bank.
                    block(1, 1).type shouldBe Material.SHORT_GRASS
                    block(1, 1).type = Material.AIR
                    block(dy = 1).type = Material.CHEST
                    native.captureRoadUpkeep(city, city.workers.single(), target) shouldBe null
                    block(dy = 1).type = Material.DIRT
                    block(dy = -1).type = Material.AIR
                    shouldThrow<CivsRejection> { native.validateRoadUpkeep(city, work) }.reason shouldBe "autonomy-no-path"
                    block(dy = -1).type = Material.STONE
                    block(dy = 4).type = Material.AIR
                    native.validateRoadUpkeep(city, work) // Sealed partial cut resumes from its exact after image.
                    for (dy in 1..4) block(dy = dy).type = Material.AIR
                    native.validateRoadUpkeep(city, work)
                    native.roadUpkeepPending(city, work) shouldBe false
                    native.captureRoadUpkeep(city, city.workers.single(), target) shouldBe null
                    // An old paid cobble receipt remains recoverable with its original desired material.
                    val old = upkeepWork(city).let { current -> current.copy(
                        changes = current.changes.map { it.copy(after = "minecraft:cobblestone") },
                        upkeep = current.upkeep!!.copy(after = "minecraft:cobblestone")) }
                    block().type = Material.COBBLESTONE
                    native.validateRoadUpkeep(city, old)
                    // A retained dry road stair touches the new floor at Y=1 and is kept as the support.
                    block().type = Material.AIR
                    val supportData = "minecraft:cobblestone_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"
                    block(dy = -1).blockData = Bukkit.createBlockData(supportData)
                    val neighbours = listOf(p.copy(x = p.x - 1, y = p.y - 1), p.copy(x = p.x + 1))
                    val stepAfter = CivsSettlementLayout.pathPavement(p,
                        (neighbours + p).associate { civsTerrainKey(it.x, it.z) to it.y }, city.style)
                    val supportedStep = upkeepWork(city, "minecraft:air").let { current -> current.copy(
                        changes = listOf(CivPlanBlock(p, "minecraft:air", stepAfter)),
                        upkeep = current.upkeep!!.copy(after = stepAfter, neighbours = neighbours)) }
                    native.validateRoadUpkeep(city, supportedStep)
                    block(dy = -1).blockData.asString shouldBe supportData
                    block(-1).type = Material.FERN
                    native.validateRoadUpkeep(city, supportedStep)
                    block(-1).type shouldBe Material.FERN
                    block(-1).type = Material.STONE
                    for (foreign in listOf("minecraft:stone_brick_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]",
                        "minecraft:cobblestone_slab[type=bottom,waterlogged=false]")) {
                        block(dy = -1).blockData = Bukkit.createBlockData(foreign)
                        shouldThrow<CivsRejection> { native.validateRoadUpkeep(city, supportedStep) }.reason shouldBe "autonomy-no-path"
                    }
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
    return CivWorldWork(worker.id, worker.completedCycles, worker.assignment!!, p.copy(x = 1, y = 64),
        plan.blocks.map { it.copy(after = target.after) },
        status = "repairing-road", focus = p, upkeep = target)
}

private fun upkeepApproach(city: Settlement, building: CivBuilding, route: List<CivPosition>): CivPlacementPlan =
    CivPlacementPlan(building.id, city.id, city.worldId, building, emptyList(), terrain = CivTerraformPlan(
        CivBox(CivPosition(route.minOf { it.x }, route.minOf { it.y } - 1, route.minOf { it.z }),
            CivPosition(route.maxOf { it.x }, route.maxOf { it.y } + 1, route.maxOf { it.z })),
        emptyList(), route, building.anchor.y, 0, 0))

private fun upkeepBend(): Triple<Settlement, CivBuilding, List<CivPosition>> {
    val city = upkeepCity()
    val source = city.buildings.last().copy(type = BuildingType.QUARRY, rotation = 180,
        schematicId = CivsBlueprints.schematicId(city.style, BuildingType.QUARRY, 1),
        anchor = CivPosition(2, 70, 1), bounds = CivBox(CivPosition(-7, 65, 2), CivPosition(11, 87, 20)),
        entrance = CivPosition(2, 71, 1), transit = null)
    val route = listOf(CivPosition(0, 67, -3), CivPosition(0, 67, -2), CivPosition(0, 68, -1),
        CivPosition(0, 69, 0), CivPosition(0, 70, 1), CivPosition(1, 71, 1), source.entrance)
    return Triple(city.copy(buildings = city.buildings.dropLast(1) + source), source, route)
}
