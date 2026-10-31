package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.type.Door
import org.bukkit.block.data.type.Gate
import org.bukkit.entity.Entity
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.util.BoundingBox
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID

class CivsCitizensTest : FunSpec({
    for (rotation in listOf(0, 90, 180, 270))
    test("V4 mine waypoints at rotation $rotation keep every vertical leg on the original ladder column") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("single-ladder-waypoints").also(paper.server::addWorld)
            val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(0, 80, 0), rotation)
            val geometry = requireNotNull(CivsMinePlan.forBuilding(mine))
            fun data(spec: String) = org.bukkit.Bukkit.createBlockData(spec).also { if (!it.material.isAir) it.rotate(when (rotation) {
                90 -> org.bukkit.block.structure.StructureRotation.CLOCKWISE_90
                180 -> org.bukkit.block.structure.StructureRotation.CLOCKWISE_180
                270 -> org.bukkit.block.structure.StructureRotation.COUNTERCLOCKWISE_90
                else -> org.bukkit.block.structure.StructureRotation.NONE
            }) }
            for (x in -2..2) for (z in -2..2) world.loadChunk(x, z)
            for (step in geometry.steps.take(geometry.shaftEnd)) {
                val point = CivsWorksiteLayouts.point(mine, step.point)
                world.getBlockAt(point.x, point.y, point.z).blockData =
                    data(step.after ?: "minecraft:air")
            }
            val bottom = CivsWorksiteLayouts.point(mine, geometry.bottom)
            val upperTransfer = bottom.copy(y = mine.anchor.y + geometry.transferFeetY)
            val lower = bottom.copy(y = mine.anchor.y + geometry.floors.last().feetY)
            val above = bottom.copy(y = bottom.y + 2)
            for (y in bottom.y..above.y) {
                val backing = CivsWorksiteLayouts.point(mine, geometry.backing).copy(y = y)
                world.getBlockAt(backing.x, y, backing.z).type = Material.COBBLESTONE
                world.getBlockAt(bottom.x, y, bottom.z).blockData = data(
                    "minecraft:ladder[facing=${geometry.facing},waterlogged=false]")
            }
            val city = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "IRON_PICKAXE")).copy(
                minePlans = mapOf(mine.id.toString() to CivMinePlanState(4, geometry.shaftEnd.toLong())))
            fun at(point: CivPosition) = Location(world, point.x + .5, point.y.toDouble(), point.z + .5)
            val extension = CivsWorksiteLayouts.point(mine, geometry.extensionLadder)
            extension.x shouldBe bottom.x
            extension.z shouldBe bottom.z
            citizenMineWaypoint(city, world, at(above), lower) shouldBe bottom
            citizenMineWaypoint(city, world, at(bottom), lower) shouldBe null
            citizenMineWaypoint(city, world, at(upperTransfer), lower) shouldBe null
            citizenMineWaypoint(city, world, at(lower), above) shouldBe bottom
            citizenMineWaypoint(city, world, at(bottom), above) shouldBe null
            val firstFloor = geometry.floors.first()
            val offColumn = CivsWorksiteLayouts.point(mine, firstFloor.cells.first().copy(y = firstFloor.feetY))
            citizenMineWaypoint(city, world, at(offColumn), lower) shouldBe bottom.copy(y = offColumn.y)
        }
    }

    test("a retained V3 step 83 escapes through its built corner transfer without entering the future lower stance") {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("retained-v3-waypoints").also(paper.server::addWorld)
            val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(-85, 68, -40), 270)
            val old = requireNotNull(CivsMinePlan.forBuilding(mine, 3))
            fun data(spec: String) = org.bukkit.Bukkit.createBlockData(spec).also {
                if (!it.material.isAir) it.rotate(org.bukkit.block.structure.StructureRotation.COUNTERCLOCKWISE_90)
            }
            for (x in -8..-3) for (z in -5..-1) world.loadChunk(x, z)
            for (step in old.steps.take(83)) {
                val point = CivsWorksiteLayouts.point(mine, step.point)
                world.getBlockAt(point.x, point.y, point.z).blockData = data(step.after ?: "minecraft:air")
            }
            val bottom = CivsWorksiteLayouts.point(mine, old.bottom)
            val upperTransfer = bottom.copy(y = mine.anchor.y + old.transferFeetY)
            val cornerTransfer = CivsWorksiteLayouts.point(mine, old.extensionLadder)
            val above = bottom.copy(y = bottom.y + 2)
            for (y in bottom.y..above.y) {
                val backing = CivsWorksiteLayouts.point(mine, old.backing).copy(y = y)
                world.getBlockAt(backing.x, y, backing.z).type = Material.COBBLESTONE
                world.getBlockAt(bottom.x, y, bottom.z).blockData = data(
                    "minecraft:ladder[facing=${old.facing},waterlogged=false]")
            }
            val state = CivMinePlanState(3, 83)
            val city = spatialCity(world.uid, listOf(mine), spatialWorker(mine, "IRON_PICKAXE")).copy(
                minePlans = mapOf(mine.id.toString() to state))
            fun at(point: CivPosition) = Location(world, point.x + .5, point.y.toDouble(), point.z + .5)
            val observed = Location(world, -95.6805, 59.312, -46.3006)
            citizenPacketBodyClear(world, CitizenPoint(observed.x, observed.y, observed.z), false) shouldBe true
            citizenMineWorkRouteAllowed(mine, CitizenPoint(observed.x, observed.y, observed.z), 0, state) shouldBe true
            citizenMineWaypoint(city, world, observed, above) shouldBe cornerTransfer
            citizenMineWaypoint(city, world, at(cornerTransfer), above) shouldBe upperTransfer
            citizenMineWaypoint(city, world, at(upperTransfer), above) shouldBe bottom
            val future = CivPosition(-96, 55, -47)
            citizenMineWorkRouteAllowed(mine, CitizenPoint(future.x + .5, future.y.toDouble(), future.z + .5),
                0, state) shouldBe false
            citizenMineWaypoint(city, world, at(future), above) shouldBe null
        }
    }

    test("only a mine leg passes beneath another foundation with its complete headroom gap") {
        val mine = spatialBuilding("nordic", "miner", 1, CivPosition(0, 80, 0))
        val bottom = CivsWorksiteLayouts.point(mine, requireNotNull(CivsMinePlan.forBuilding(mine)).bottom)
        val point = CivPosition(10, bottom.y - 5, 10)
        val home = building(BuildingType.HOUSE, CivPosition(10, 80, 10)).copy(
            bounds = CivBox(CivPosition(9, 80, 9), CivPosition(12, 85, 12)))
        val current = city(listOf(mine, home), CivWorker(UUID.randomUUID(), "Miner", mine.id), null)
        val allowed = setOf(mine.id)
        citizenRouteCellAllowed(current, allowed, point.x, point.z) shouldBe false
        citizenRoutePointAllowed(current, allowed, point) shouldBe false
        citizenRoutePointAllowed(current, allowed, point, mine) shouldBe true
        citizenRoutePointAllowed(current, allowed, point, home) shouldBe false
        val tooShallow = home.copy(bounds = home.bounds.copy(min = home.bounds.min.copy(y = point.y + 4)))
        citizenRoutePointAllowed(current.copy(buildings = listOf(mine, tooShallow)), allowed, point, mine) shouldBe false
        citizenRoutePointAllowed(current.copy(buildings = listOf(mine, home.copy(type = BuildingType.QUARRY))), allowed, point, mine) shouldBe false
    }

    test("a courier waits for durable pickup before walking to unload and retries full storage without losing cargo") {
        val cycle = CitizenCycle(token(90_000), 1_000, pickupAtWorkplace = true)
        cycle.start()
        cycle.phase shouldBe CitizenPhase.TO_WORK
        cycle.beginPickup() shouldBe null
        cycle.arrive(0)
        cycle.work(1_000)
        cycle.phase shouldBe CitizenPhase.PICKUP_READY
        val pickup = requireNotNull(cycle.beginPickup())
        cycle.beginPickup() shouldBe null
        cycle.arrive(2_000)
        cycle.phase shouldBe CitizenPhase.PICKING_UP
        cycle.acceptsPickup(UUID.randomUUID()) shouldBe false
        cycle.acceptsPickup(pickup) shouldBe true
        cycle.pickedUp()
        cycle.phase shouldBe CitizenPhase.RETURNING
        cycle.beginDelivery(3_000) shouldBe null
        cycle.pause("route-unreachable", 3_000, false)
        cycle.retry(8_000)
        cycle.phase shouldBe CitizenPhase.RETURNING
        cycle.arrive(9_000)
        cycle.beginDelivery(9_000)?.let(cycle::acceptsCompletion) shouldBe true
        cycle.failDelivery("storage-full", 9_000)
        cycle.retry(13_999)
        cycle.phase shouldBe CitizenPhase.PAUSED
        cycle.retry(14_000)
        cycle.phase shouldBe CitizenPhase.READY
        cycle.failedAttempts shouldBe 1 // Only the genuine route failure consumed a navigation attempt.
        cycle.beginDelivery(14_000)?.let(cycle::acceptsCompletion) shouldBe true
    }

    test("pickup shortages retry at the source and a persisted shipment resumes only its destination leg") {
        val fresh = CitizenCycle(token(), 1_000, pickupAtWorkplace = true)
        fresh.start(); fresh.arrive(0); fresh.work(1_000)
        repeat(10) { attempt ->
            requireNotNull(fresh.beginPickup())
            val now = 1_000 + attempt * 5_000L
            fresh.failPickup("materials-missing", now)
            fresh.retry(now + 4_999)
            fresh.phase shouldBe CitizenPhase.PAUSED
            fresh.retry(now + 5_000)
            fresh.phase shouldBe CitizenPhase.PICKUP_READY
        }
        fresh.failedAttempts shouldBe 0
        val resumed = CitizenCycle(token(), 1_000, pickupAtWorkplace = true, carrying = true)
        resumed.start()
        resumed.phase shouldBe CitizenPhase.RETURNING
        resumed.beginPickup() shouldBe null
        resumed.beginDelivery(100_000) shouldBe null
        resumed.arrive(100_000)
        resumed.beginDelivery(100_000)?.let(resumed::acceptsCompletion) shouldBe true
    }

    test("shipment plans retain their source spawn and identity across pickup quantity and reassignment") {
        val hall = building(BuildingType.TOWN_HALL, CivPosition(0, 64, 0))
        val warehouse = building(BuildingType.WAREHOUSE, CivPosition(8, 64, 0))
        val worker = CivWorker(UUID.randomUUID(), "Кладовщик", warehouse.id, nextDeliveryAt = 60_000)
        val city = city(listOf(hall, warehouse), worker, null).copy(inventory = CivInventory(CivStorageMode.VIRTUAL))
        val cargo = CivCargo(UUID.randomUUID(), warehouse.id, warehouse.id, hall.id, warehouse.entrance, "PAPER", 3)
        val requested = CivCourierPlan(cargo, hall.id, hall.entrance, false)
        val before = requireNotNull(citizenPlan(city, worker, requested))
        val carrying = worker.copy(assignment = hall.id, nextDeliveryAt = 99_000, cargo = cargo.copy(amount = 2))
        val after = requireNotNull(citizenPlan(city, carrying, requested.copy(cargo = carrying.cargo!!, carrying = true)))
        citizenPlansContinue(before, after) shouldBe true
        // A native debit can empty the source before the canonical cargo publication; do not select another task yet.
        citizenCurrentPlan(before, CitizenPhase.PICKING_UP) { error("Must not reread a pending source") } shouldBe before
        citizenCurrentPlan(after, CitizenPhase.DELIVERING) { error("Must await the unload receipt") } shouldBe after
        citizenCurrentPlan(after, CitizenPhase.RETURNING) { null } shouldBe null
        after.spawnOrigin shouldBe warehouse.entrance
        after.workplace shouldBe warehouse.control
        after.storage shouldBe hall.control
        after.type shouldBe BuildingType.WAREHOUSE
        (hall.id in citizenRouteBuildings(city, before, before.storage, warehouse.control!!)) shouldBe true
        val relocated = city.copy(buildings = listOf(hall.copy(control = CivPosition(-6, 64, -2)),
            warehouse.copy(control = CivPosition(8, 64, -6))))
        val moved = requireNotNull(citizenPlan(relocated, carrying, requested.copy(cargo = carrying.cargo!!, carrying = true)))
        moved.workplace shouldBe relocated.buildings[1].control
        moved.storage shouldBe relocated.buildings[0].control
        moved.courier!!.cargo.sourceEntrance shouldBe warehouse.entrance
        citizenPlansContinue(after, after.copy(storage = CivPosition(20, 64, 0))) shouldBe false
        citizenPlan(city, carrying) shouldBe null // A missing logistics plan cannot start the newly assigned job.
        for (phase in CitizenPhase.entries) {
            citizenCourierCart(before, phase, false) shouldBe (phase in setOf(
                CitizenPhase.TO_WORK, CitizenPhase.WORKING, CitizenPhase.PICKUP_READY, CitizenPhase.PICKING_UP,
                CitizenPhase.RETURNING, CitizenPhase.READY, CitizenPhase.DELIVERING))
            citizenCourierCart(before, phase, true) shouldBe false
            citizenCourierCart(before.copy(courier = null, idleReason = "waiting-orders"), phase, false) shouldBe false
        }
    }

    test("item production finishes at its own building without sending a worker to the shared depot") {
        val hall = building(BuildingType.TOWN_HALL, CivPosition(0, 64, 0))
        val factory = building(BuildingType.SAWMILL, CivPosition(20, 64, 0))
        val worker = CivWorker(UUID.randomUUID(), "Плотник", factory.id, nextDeliveryAt = 60_000)
        val city = city(listOf(hall, factory), worker, null).copy(inventory = CivInventory(CivStorageMode.VIRTUAL))
        val plan = requireNotNull(citizenPlan(city, worker))
        plan.storage shouldBe factory.control
        plan.workplace shouldBe factory.control
        plan.completeAtWorkplace shouldBe true
        val cycle = plan.cycle()
        reachWork(cycle)
        repeat(60) { cycle.work((it + 1) * 1_000L) }
        cycle.phase shouldBe CitizenPhase.READY
    }

    test("abstract autonomous producers use prepared entrances while personal producers retain their barrels") {
        val hall = building(BuildingType.TOWN_HALL, CivPosition(0, 64, 0)).copy(
            phase = BuildingPhase.SITE, control = CivPosition(15, 67, -1))
        for (type in listOf(BuildingType.QUARRY, BuildingType.FARM)) {
            val factory = building(type, CivPosition(20, 64, 0)).copy(control = CivPosition(24, 67, -1))
            val worker = CivWorker(UUID.randomUUID(), "Producer", factory.id, nextDeliveryAt = 60_000)
            val village = city(listOf(hall, factory), worker, null).copy(autonomy = CivAutonomy())
            val plan = requireNotNull(citizenPlan(village, worker))
            plan.storage shouldBe hall.entrance
            plan.workplace shouldBe factory.entrance
            plan.autonomousWork shouldBe factory
            plan.completeAtWorkplace shouldBe false
            val cycle = plan.cycle()
            reachWork(cycle)
            repeat(60) { cycle.work((it + 1) * 1_000L) }
            cycle.phase shouldBe CitizenPhase.RETURNING
            cycle.beginDelivery(60_000) shouldBe null
            cycle.arrive(61_000)
            (cycle.beginDelivery(61_000) != null) shouldBe true

            val personal = requireNotNull(citizenPlan(village.copy(autonomy = null,
                inventory = CivInventory(CivStorageMode.PHYSICAL)), worker))
            personal.storage shouldBe factory.control
            personal.workplace shouldBe factory.control
            personal.autonomousWork shouldBe null
            personal.completeAtWorkplace shouldBe true
        }
    }

    test("an Auto search placeholder yields to resumed work without discarding an admitted production session") {
        val farm = building(BuildingType.FARM, CivPosition(20, 64, 0))
        val searching = CitizenPlan(CitizenToken(farm.id, 241, 60_000, null), CivPosition(0, 64, 0),
            farm.entrance, BuildingType.FARM, 0, "searching-work", autonomousWork = farm)
        val resumed = searching.copy(idleReason = null)
        citizenCurrentPlan(searching, CitizenPhase.RETURNING) { resumed } shouldBe resumed
        for (reason in listOf("waiting-reassignment", "waiting-orders", "waiting-inputs")) {
            citizenCurrentPlan(resumed, CitizenPhase.RETURNING) { resumed.copy(idleReason = reason) } shouldBe resumed
        }
    }

    test("item builders start at their own hut even when the hall is nearer the construction") {
        val hall = building(BuildingType.TOWN_HALL, CivPosition(0, 64, 0))
        val hut = building(BuildingType.BUILDER_HUT, CivPosition(24, 64, 0))
        val target = building(BuildingType.HOUSE, CivPosition(4, 64, 0)).copy(phase = BuildingPhase.SITE)
        val worker = CivWorker(UUID.randomUUID(), "Строитель", hut.id)
        val order = CivConstruction(UUID.randomUUID(), target.id, 1, 2, 0, ConstructionPhase.WAITING_MATERIALS,
            Stock(), plannedBuilding = target, payment = CivConstructionPayment(awaiting = mapOf("STONE" to 1)))
        val city = city(listOf(hall, hut, target), worker, order).copy(inventory = CivInventory(CivStorageMode.PHYSICAL))
        for (buildings in listOf(city.buildings, listOf(hut, target))) {
            val plan = requireNotNull(citizenPlan(city.copy(buildings = buildings), worker))
            plan.storage shouldBe hut.control
            plan.spawnOrigin shouldBe hut.control
            plan.workplace shouldBe target.control
            plan.idleReason shouldBe "waiting-materials"
        }
        val site = hut.copy(phase = BuildingPhase.SITE, control = null)
        val bootstrap = city.copy(buildings = listOf(hall, site), construction = order.copy(buildingId = site.id, plannedBuilding = site))
        val plan = requireNotNull(citizenPlan(bootstrap, worker))
        plan.storage shouldBe site.entrance
        plan.spawnOrigin shouldBe site.entrance
        plan.idleReason shouldBe "waiting-materials"
    }

    test("physical work picks up its missing tool once then starts each field action directly at the worksite") {
        val farm = building(BuildingType.FARM, CivPosition(20, 64, 0))
        val barrelSide = CivPosition(21, 64, -2)
        val tool = CivWorldWork(UUID.randomUUID(), 0, farm.id, barrelSide, emptyList(),
            inputs = mapOf("IRON_HOE" to 1L), durationMillis = 1_000, status = "waiting-tool")
        val plan = CitizenPlan(CitizenToken(farm.id, 0, 0, null), farm.control!!, tool.standing, BuildingType.FARM, tool.durationMillis,
            null, completeAtWorkplace = true, worldWork = tool)
        val pickup = plan.cycle()
        pickup.start()
        pickup.phase shouldBe CitizenPhase.TO_WORK
        pickup.arrive(0); pickup.work(1_000)
        pickup.phase shouldBe CitizenPhase.READY
        pickup.beginDelivery(1_000)!!.let(pickup::acceptsCompletion) shouldBe true
        val field = CivPosition(30, 64, -12)
        val next = plan.copy(token = plan.token.copy(completedCycles = 1), workplace = field,
            worldWork = tool.copy(cycle = 1, standing = field, inputs = emptyMap(), status = "working-field"))
        val till = next.cycle()
        till.start()
        till.phase shouldBe CitizenPhase.TO_WORK
        till.arrive(2_000); till.work(3_000)
        till.phase shouldBe CitizenPhase.READY
        // A route retry also returns to the real worksite rather than imposing another barrel visit.
        till.pause("route-unreachable", 3_000, false)
        till.retry(8_000)
        till.phase shouldBe CitizenPhase.TO_WORK
    }

    test("native field work keeps its status focus and plan while its durable delivery is pending") {
        val farm = building(BuildingType.FARM, CivPosition(20, 64, 0))
        val work = CivWorldWork(UUID.randomUUID(), 0, farm.id, CivPosition(23, 64, 2), emptyList(),
            durationMillis = 1_500, status = "working-field")
        val plan = CitizenPlan(CitizenToken(farm.id, 0, 0, null), farm.control!!, work.standing,
            BuildingType.FARM, work.durationMillis, null, completeAtWorkplace = true, worldWork = work)
        val cycle = plan.cycle()
        cycle.start()
        cycle.arrive(0)
        cycle.work(1_500)
        cycle.phase shouldBe CitizenPhase.READY
        citizenCurrentPlan(plan, cycle.phase) { error("Completed native work must keep its captured focus") } shouldBe plan
        citizenWorkStatus(plan, cycle.phase) shouldBe "working-field"
        citizenWorking(plan, cycle.phase) shouldBe true
        val ticket = requireNotNull(cycle.beginDelivery(1_500))
        citizenCurrentPlan(plan, cycle.phase) { error("Do not reread an in-flight item receipt") } shouldBe plan
        citizenWorkStatus(plan, cycle.phase) shouldBe "working-field"
        citizenWorking(plan, cycle.phase) shouldBe true
        cycle.beginDelivery(1_500) shouldBe null
        cycle.acceptsCompletion(ticket) shouldBe true
        cycle.close()
        citizenCurrentPlan(plan, cycle.phase) { null } shouldBe null
        citizenWorkStatus(plan, cycle.phase) shouldBe null
    }

    test("ready crop care can yield to a ripe harvest without donating its work time or changing a pending receipt") {
        val farm = building(BuildingType.FARM, CivPosition(20, 64, 0))
        val care = CivWorldWork(UUID.randomUUID(), 0, farm.id, CivPosition(23, 64, 2), emptyList(),
            durationMillis = 4_000, status = "watering", focus = CivPosition(24, 64, 2))
        val previous = CitizenPlan(CitizenToken(farm.id, 0, 0, null), farm.control!!, care.standing,
            BuildingType.FARM, care.durationMillis, null, completeAtWorkplace = true, worldWork = care)
        val harvest = care.copy(standing = CivPosition(25, 64, 2), durationMillis = 1_500,
            status = "working-field", focus = CivPosition(26, 64, 2))
        val next = previous.copy(workplace = harvest.standing, workMillis = harvest.durationMillis, worldWork = harvest)
        val oldCycle = previous.cycle()
        oldCycle.start(); oldCycle.arrive(0); oldCycle.work(2_000); oldCycle.work(4_000)
        oldCycle.phase shouldBe CitizenPhase.READY
        citizenCurrentPlan(previous, oldCycle.phase) { next } shouldBe next
        citizenPlansContinue(previous, next) shouldBe false
        val newCycle = next.cycle()
        newCycle.start(); newCycle.arrive(4_000); newCycle.work(5_000)
        newCycle.phase shouldBe CitizenPhase.WORKING
        newCycle.work(5_500)
        newCycle.phase shouldBe CitizenPhase.READY
        val receipt = requireNotNull(oldCycle.beginDelivery(4_000))
        citizenCurrentPlan(previous, oldCycle.phase) { error("An admitted receipt owns its exact crop changes") } shouldBe previous
        oldCycle.acceptsCompletion(receipt) shouldBe true
    }

    test("a nearby committed field handoff stays one activity while a long search or failure remains visible") {
        val farm = building(BuildingType.FARM, CivPosition(20, 64, 0))
        val at = CitizenPoint(23.5, 64.0, 2.5)
        val plan = CitizenPlan(CitizenToken(farm.id, 1, 0, null), farm.control!!, null,
            BuildingType.FARM, 0, "searching-work", completeAtWorkplace = true)
        val handoff = CitizenWorkHandoff(farm.id, 1, at, 100, "working-field")
        citizenWorkActivityStatus(plan, CitizenPhase.IDLE, at, handoff, 101) shouldBe "working-field"
        citizenWorkActivityStatus(plan, CitizenPhase.IDLE, at, handoff, 120) shouldBe "working-field"
        citizenWorkActivityStatus(plan, CitizenPhase.IDLE, at, handoff, 121) shouldBe null
        citizenWorkActivityStatus(plan, CitizenPhase.IDLE, at, null, 101) shouldBe null
        citizenWorkActivityStatus(plan, CitizenPhase.PAUSED, at, handoff, 101) shouldBe null
        citizenWorkActivityStatus(plan.copy(idleReason = "waiting-inputs"), CitizenPhase.IDLE, at, handoff, 101) shouldBe null
        citizenWorkActivityStatus(plan.copy(token = plan.token.copy(assignmentId = UUID.randomUUID())),
            CitizenPhase.IDLE, at, handoff, 101) shouldBe null
        val work = CivWorldWork(UUID.randomUUID(), 1, farm.id, CivPosition(24, 64, 2), emptyList(), status = "working-field")
        val walking = plan.copy(workplace = work.standing, worldWork = work, idleReason = null)
        citizenWorkActivityStatus(walking, CitizenPhase.TO_WORK, at, handoff, 150) shouldBe "working-field"
        citizenWorkActivityStatus(walking, CitizenPhase.TO_WORK, CitizenPoint(.5, 64.0, .5), handoff, 150) shouldBe null
    }

    test("a committed quarry action spans only its brief nearby discovery gap and matching next activity") {
        val quarry = building(BuildingType.QUARRY, CivPosition(20, 64, 0))
        val at = CitizenPoint(23.5, 56.0, 2.5)
        val plan = CitizenPlan(CitizenToken(quarry.id, 4, 0, null), quarry.control!!, null,
            BuildingType.QUARRY, 0, "searching-work", completeAtWorkplace = true)
        for (activity in listOf("digging-mine", "securing-mine")) {
            val handoff = CitizenWorkHandoff(quarry.id, 4, at, 100, activity)
            citizenWorkActivityStatus(plan, CitizenPhase.IDLE, at, handoff, 101) shouldBe activity
            citizenWorkActivityStatus(plan, CitizenPhase.IDLE, at, handoff, 120) shouldBe activity
            citizenWorkActivityStatus(plan, CitizenPhase.IDLE, at, handoff, 121) shouldBe null
            citizenWorkActivityStatus(plan, CitizenPhase.IDLE, at.copy(x = at.x + 5), handoff, 101) shouldBe null
            citizenWorkActivityStatus(plan.copy(token = plan.token.copy(completedCycles = 5)),
                CitizenPhase.IDLE, at, handoff, 101) shouldBe null
            citizenWorkActivityStatus(plan, CitizenPhase.PAUSED, at, handoff, 101) shouldBe null
            for (reason in listOf("waiting-tool", "waiting-materials", "protected", "route-unreachable"))
                citizenWorkActivityStatus(plan.copy(idleReason = reason), CitizenPhase.IDLE, at, handoff, 101) shouldBe null
            val work = CivWorldWork(UUID.randomUUID(), 4, quarry.id, CivPosition(24, 56, 2), emptyList(), status = activity)
            val walking = plan.copy(workplace = work.standing, worldWork = work, idleReason = null)
            citizenWorkActivityStatus(walking, CitizenPhase.TO_WORK, at, handoff, 101) shouldBe activity
            citizenWorkActivityStatus(walking, CitizenPhase.TO_WORK, at, null, 101) shouldBe null
            citizenWorkActivityStatus(walking, CitizenPhase.TO_WORK, at, handoff, 121) shouldBe null
            citizenWorkActivityStatus(walking.copy(worldWork = work.copy(status = "working-field")),
                CitizenPhase.TO_WORK, at, handoff, 101) shouldBe null
        }
    }

    test("children plan play at their persisted home and summon never selects them as workers") {
        val hall = building(BuildingType.TOWN_HALL, CivPosition(0, 64, 0))
        val firstHome = building(BuildingType.HOUSE, CivPosition(10, 64, 0))
        val ownHome = building(BuildingType.HOUSE, CivPosition(20, 64, 0)).copy(
            bounds = CivBox(CivPosition(17, 62, -4), CivPosition(24, 68, 4)))
        val hut = building(BuildingType.BUILDER_HUT, CivPosition(30, 64, 0))
        val child = CivWorker(UUID.randomUUID(), "Child", null, sex = CivSex.FEMALE, homeId = ownHome.id,
            parentIds = setOf(UUID.randomUUID(), UUID.randomUUID()), childMinutes = 10)
        val city = city(listOf(hall, firstHome, ownHome, hut), child, null)
        val plan = requireNotNull(citizenPlan(city, child))
        plan.idleReason shouldBe "playing"
        plan.storage shouldBe ownHome.entrance
        plan.workplace shouldBe null
        plan.courier shouldBe null
        plan.workMillis shouldBe 0L
        citizenIdleBuilding(city, child) shouldBe ownHome
        val target = requireNotNull(citizenShelterTarget(city, child) { true })
        ownHome.bounds.contains(target.x, target.y, target.z) shouldBe true
        citizenShelterTarget(city.copy(workers = city.workers.reversed()), child) { true } shouldBe target
        citizenSummonEligible(city, child, ownHome.id) shouldBe false
        val adult = child.copy(childMinutes = null, assignment = hut.id)
        citizenSummonEligible(city, adult, hut.id) shouldBe true
        citizenSummonEligible(city, adult, firstHome.id) shouldBe false
        val order = CivConstruction(UUID.randomUUID(), firstHome.id, 2, 2, 0, ConstructionPhase.CONSTRUCTING,
            Stock(), plannedBuilding = firstHome.copy(level = 2))
        citizenSummonEligible(city.copy(construction = order), adult, firstHome.id) shouldBe true
    }

    test("work facing uses real eye height so a barrel below the eyes cannot tilt the head into the sky") {
        val focus = Location(null, 0.5, 64.7, -3.5)
        val eye = Location(null, 1.5, 65.62, -3.5)
        val (yaw, pitch) = citizenLookDirection(eye, focus)
        yaw shouldBe 90f
        (pitch > 0f && pitch < 90f) shouldBe true
        val lowerPitch = citizenLookDirection(eye.clone().also { it.y = 65.0 }, focus).second
        (pitch > lowerPitch && lowerPitch > 0f) shouldBe true
        (citizenLookDirection(eye, focus.clone().also { it.y = eye.y }).second == 0f) shouldBe true
        eye.y shouldBe 65.62
    }

    test("only working travel earns road footsteps while idle wander and night sheltering stay slow") {
        val plan = CitizenPlan(token(), CivPosition(0, 64, 0), CivPosition(10, 64, 0), BuildingType.FARM, 1_000, null)
        for (phase in CitizenPhase.entries) {
            citizenWorkingTravel(plan, phase, false) shouldBe
                (phase in setOf(CitizenPhase.TO_STORAGE, CitizenPhase.TO_WORK, CitizenPhase.RETURNING))
            citizenWorkingTravel(plan.copy(idleReason = "waiting-growth"), phase, false) shouldBe false
            citizenWorkingTravel(plan, phase, true) shouldBe false
        }
    }

    test("a nearby native path door opens during construction and keeps cancellation ownership and receipt locks") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val world = paper.addSimpleWorld("citizen-door")
                world.loadChunk(0, 0)
                val hall = building(BuildingType.TOWN_HALL, CivPosition(0, 64, 0))
                val worker = CivWorker(UUID.randomUUID(), "Писарь", hall.id)
                val city = city(listOf(hall), worker, null).copy(worldId = world.uid)
                val lower = world.getBlockAt(0, 64, 0)
                val upper = world.getBlockAt(0, 65, 0)
                fun close() {
                    lower.setBlockData(org.bukkit.Bukkit.createBlockData("minecraft:oak_door[half=lower,open=false]"), false)
                    upper.setBlockData(org.bukkit.Bukkit.createBlockData("minecraft:oak_door[half=upper,open=false]"), false)
                }
                var action: (CivsResidentOpenDoorEvent) -> Unit = {}
                var events = 0
                paper.server.pluginManager.registerEvents(object : Listener {
                    @EventHandler
                    fun door(event: CivsResidentOpenDoorEvent) { events++; action(event) }
                }, paper.createSimplePlugin("CitizenDoors"))
                val at = Location(world, 0.5, 64.0, -1.5)
                val door = CivPosition(0, 64, 0)
                fun open(current: Settlement = city, blocked: (UUID, CivPosition) -> Boolean = { _, _ -> false }) =
                    citizenOpenRouteDoor(current, setOf(hall.id), at, door, blocked)
                close()
                open() shouldBe true
                events shouldBe 1
                (lower.blockData as Door).isOpen shouldBe true
                (upper.blockData as Door).isOpen shouldBe true
                close()
                action = { it.isCancelled = true }
                open() shouldBe false
                (lower.blockData as Door).isOpen shouldBe false
                (upper.blockData as Door).isOpen shouldBe false
                action = {}
                open(blocked = { _, point -> point == door.copy(y = 65) }) shouldBe false
                events shouldBe 2
                for (phase in ConstructionPhase.entries) {
                    close()
                    val construction = CivConstruction(UUID.randomUUID(), hall.id, 2, 1, 0,
                        phase, Stock(), plannedBuilding = hall.copy(level = 2))
                    open(city.copy(buildings = listOf(hall.copy(phase = BuildingPhase.CONSTRUCTING)),
                        construction = construction)) shouldBe true
                    (lower.blockData as Door).isOpen shouldBe true
                    (upper.blockData as Door).isOpen shouldBe true
                }
                close()
                open(city.copy(pendingDeletion = CivDeletion(null))) shouldBe false
                citizenOpenRouteDoor(city, setOf(hall.id), at.clone().also { it.x = 30.0 }, door) { _, _ -> false } shouldBe false
                citizenOpenRouteDoor(city, emptySet(), at, door) { _, _ -> false } shouldBe false
                action = { upper.type = Material.STONE }
                open() shouldBe false
                upper.type shouldBe Material.STONE
                (lower.blockData as Door).isOpen shouldBe false
                // A missing or replaced companion is a collision, never a permission to walk through it.
                open() shouldBe false
            }
        }
    }

    test("a route opens the gate below its feet while preserving native cancellation ownership and receipt locks") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val world = CivsWorksiteWorldFixture("citizen-gate").also(paper.server::addWorld)
                world.loadChunk(0, 0)
                val hall = building(BuildingType.TOWN_HALL, CivPosition(0, 64, 0))
                val worker = CivWorker(UUID.randomUUID(), "Писарь", hall.id)
                val city = city(listOf(hall), worker, null).copy(worldId = world.uid)
                val point = CivPosition(0, 64, 0)
                val block = world.getBlockAt(0, 64, 0)
                val closed = "minecraft:oak_fence_gate[facing=north,in_wall=false,open=false,powered=false]"
                fun close() { block.setBlockData(org.bukkit.Bukkit.createBlockData(closed), false) }
                var action: (CivsResidentOpenDoorEvent) -> Unit = {}
                var events = 0
                paper.server.pluginManager.registerEvents(object : Listener {
                    @EventHandler
                    fun passage(event: CivsResidentOpenDoorEvent) { events++; action(event) }
                }, paper.createSimplePlugin("CitizenGates"))
                val at = Location(world, .5, 65.0, -1.5)
                val destination = Location(world, .5, 65.0, .5)
                fun open(current: Settlement = city, blocked: (UUID, CivPosition) -> Boolean = { _, _ -> false }) =
                    citizenOpenRouteDoors(current, setOf(hall.id), at, destination, blocked)
                close()
                open() shouldBe true
                events shouldBe 1
                block.blockData.asString shouldBe closed.replace("open=false", "open=true")
                // Repeated movement through an already open gate does not redispatch the interaction.
                open() shouldBe true
                events shouldBe 1
                close()
                // A gate below the body's collision range is not an interaction target.
                citizenOpenRouteDoors(city, setOf(hall.id), at, destination.clone().also { it.y = 65.5 }) { _, _ -> false } shouldBe true
                block.blockData.asString shouldBe closed
                events shouldBe 1
                action = { it.isCancelled = true }
                open() shouldBe false
                block.blockData.asString shouldBe closed
                action = {}
                open(blocked = { _, p -> p == point }) shouldBe false
                events shouldBe 2
                for (phase in ConstructionPhase.entries) {
                    close()
                    val construction = CivConstruction(UUID.randomUUID(), hall.id, 2, 1, 0,
                        phase, Stock(), plannedBuilding = hall.copy(level = 2))
                    open(city.copy(buildings = listOf(hall.copy(phase = BuildingPhase.CONSTRUCTING)),
                        construction = construction)) shouldBe true
                    (block.blockData as Gate).isOpen shouldBe true
                }
                close()
                open(city.copy(pendingDeletion = CivDeletion(null))) shouldBe false
                citizenOpenRouteDoors(city, emptySet(), at, destination) { _, _ -> false } shouldBe false
                citizenOpenRouteDoors(city, setOf(hall.id), at.clone().also { it.z = -8.0 }, destination) { _, _ -> false } shouldBe false
                for (replacement in listOf("minecraft:stone", closed.replace("oak_fence_gate", "spruce_fence_gate"))) {
                    close()
                    action = { block.blockData = org.bukkit.Bukkit.createBlockData(replacement) }
                    open() shouldBe false
                    block.blockData.asString shouldBe replacement
                }
                close()
                var receiptLocked = false
                action = { receiptLocked = true }
                open(blocked = { _, _ -> receiptLocked }) shouldBe false
                block.blockData.asString shouldBe closed
                action = {}
                val trapdoor = org.bukkit.Bukkit.createBlockData("minecraft:oak_trapdoor[open=false]")
                block.blockData = trapdoor
                val previousEvents = events
                citizenOpenRouteDoor(city, setOf(hall.id), at, point) { _, _ -> false } shouldBe true
                citizenOpenRouteDoors(city, setOf(hall.id), at, destination) { _, _ -> false } shouldBe true
                events shouldBe previousEvents
                block.blockData.asString shouldBe trapdoor.asString
                citizenRouteOpenable(block.blockData) shouldBe null
            }
        }
    }

    test("footsteps use actual short grounded moves and reset across teleport spawn and world changes") {
        val world = mockk<World> { every { uid } returns UUID.randomUUID() }
        val other = mockk<World> { every { uid } returns UUID.randomUUID() }
        val steps = CitizenFootsteps()
        val start = Location(world, 0.5, 65.0, 0.5)
        steps.observe(start, true) shouldBe null
        steps.observe(start.clone(), true) shouldBe null
        val moved = start.clone().add(0.2, 0.0, 0.0)
        val actual = requireNotNull(steps.observe(moved, true))
        actual.first shouldBe start
        actual.second shouldBe moved
        moved.x = 100.0 // The caller cannot mutate the tracker's stored sample.
        steps.observe(start.clone().add(0.4, 0.0, 0.0), true)?.first shouldBe actual.second
        steps.observe(Location(world, 30.5, 65.0, 0.5), true) shouldBe null
        steps.reset()
        steps.observe(Location(world, 30.7, 65.0, 0.5), true) shouldBe null
        steps.observe(Location(other, 30.9, 65.0, 0.5), true) shouldBe null
        steps.observe(Location(other, 31.1, 65.1, 0.5), false) shouldBe null
        steps.observe(Location(other, 31.3, 65.0, 0.5), true) shouldBe null
        steps.observe(Location(other, Double.NaN, 65.0, 0.5), true) shouldBe null
        steps.observe(Location(other, 31.5, 65.0, 0.5), true) shouldBe null
        steps.reset()
        val edge = Location(world, .95, 64.9375, .5)
        val first = Location(world, 1.15, 64.9375, .5)
        steps.prime(edge, true)
        val crossing = requireNotNull(steps.observe(first, true))
        crossing.first shouldBe edge
        crossing.second shouldBe first
        steps.prime(edge, true) // Seeding cannot rewind an already observed journey.
        steps.observe(Location(world, 1.35, 64.9375, .5), true)?.first shouldBe first
    }

    test("completed trips wait indefinitely for inputs storage commits and bounded builder credit") {
        for ((reason, status) in mapOf("materials-missing" to "waiting-inputs",
            "storage-busy" to "storage-busy", "city-busy" to "waiting", "construction-credit-full" to "waiting")) {
            val cycle = CitizenCycle(token(), 1_000)
            reachWork(cycle)
            cycle.work(1_000)
            cycle.arrive(1_000)
            var now = 1_000L
            repeat(20) {
                (cycle.beginDelivery(now) != null) shouldBe true
                cycle.failDelivery(reason, now)
                cycle.status() shouldBe status
                cycle.failedAttempts shouldBe 0
                cycle.retry(now + 4_999)
                cycle.phase shouldBe CitizenPhase.PAUSED
                now += 5_000
                cycle.retry(now)
                cycle.phase shouldBe CitizenPhase.READY
            }
            (cycle.beginDelivery(now) != null) shouldBe true
        }
    }

    test("chunk readiness distinguishes loaded terrain from entity ticking without loading chunks") {
        val world = mockk<World>()
        val chunk = mockk<Chunk>()
        every { world.isChunkLoaded(-2, 3) } returns false
        citizenChunkReady(world, -2, 3) shouldBe false
        verify(exactly = 0) { world.getChunkAt(any<Int>(), any<Int>(), any<Boolean>()) }

        every { world.isChunkLoaded(-2, 3) } returns true
        every { world.getChunkAt(-2, 3, false) } returns chunk
        for (level in Chunk.LoadLevel.entries) {
            every { chunk.loadLevel } returns level
            citizenChunkReady(world, -2, 3) shouldBe (level == Chunk.LoadLevel.ENTITY_TICKING)
        }
        verify(exactly = 0) { world.getChunkAt(any<Int>(), any<Int>()) }
        verify(exactly = 0) { world.getChunkAt(any<Int>(), any<Int>(), true) }
    }

    test("a route sleeps while any checked chunk is non ticking and recovers when simulation returns") {
        val world = mockk<World>()
        val active = mockk<Chunk>()
        val boundary = mockk<Chunk>()
        val chunks = CitizenChunks.between(15, -12, 47, -12)
        chunks shouldBe CitizenChunks(-1, 3, -2, 0)
        every { world.isChunkLoaded(any(), any()) } returns true
        every { world.getChunkAt(any<Int>(), any<Int>(), false) } returns active
        every { active.loadLevel } returns Chunk.LoadLevel.ENTITY_TICKING
        every { world.getChunkAt(2, -1, false) } returns boundary
        every { boundary.loadLevel } returns Chunk.LoadLevel.TICKING
        repeat(5) { chunks.ready(world) shouldBe false }
        every { boundary.loadLevel } returns Chunk.LoadLevel.ENTITY_TICKING
        chunks.ready(world) shouldBe true
        verify(exactly = 0) { world.getChunkAt(-1, -2, false) }
        verify(exactly = 0) { world.getChunkAt(3, -1, false) }
        every { world.isChunkLoaded(-1, -2) } returns false
        chunks.ready(world) shouldBe false
        every { world.isChunkLoaded(-1, -2) } returns true
        chunks.ready(world) shouldBe true
        verify(exactly = 0) { world.getChunkAt(any<Int>(), any<Int>(), true) }
    }

    test("view activation is bounded in three dimensions and never crosses worlds") {
        val world = UUID.randomUUID()
        val origin = CitizenPoint(0.0, 64.0, 0.0)
        citizenHasViewer(world, origin, emptyList(), 96) shouldBe false
        citizenHasViewer(world, origin, listOf(CitizenViewer(world, CitizenPoint(96.0, 64.0, 0.0))), 96) shouldBe true
        citizenHasViewer(world, origin, listOf(CitizenViewer(world, CitizenPoint(96.1, 64.0, 0.0))), 96) shouldBe false
        citizenHasViewer(world, origin, listOf(CitizenViewer(world, CitizenPoint(0.0, 161.0, 0.0))), 96) shouldBe false
        citizenHasViewer(world, origin, listOf(CitizenViewer(UUID.randomUUID(), origin)), 96) shouldBe false
    }

    test("admission preserves both the server cap and two spawns per pulse") {
        citizenSpawnBudget(0, 48) shouldBe 2
        citizenSpawnBudget(47, 48) shouldBe 1
        citizenSpawnBudget(48, 48) shouldBe 0
        citizenSpawnBudget(48, 200) shouldBe 2
        citizenSpawnBudget(63, 64) shouldBe 1
        citizenSpawnBudget(64, 200) shouldBe 0
        citizenSpawnBudget(8, 8) shouldBe 0
        citizenSpawnBudget(7, 8) shouldBe 1
    }

    test("actual routes allow arrival slack without exceeding the 89 block limit") {
        val origin = CitizenPoint(0.0, 64.0, 0.0)
        CivsRules.MAX_WORK_ROUTE_DISTANCE shouldBe 88
        CITIZEN_ROUTE_ARRIVAL_TOLERANCE shouldBe 1.0
        citizenRouteWithinReach(origin, CitizenPoint(88.65, 64.0, 0.0)) shouldBe true
        citizenRouteWithinReach(origin, CitizenPoint(89.0, 64.0, 0.0)) shouldBe true
        citizenRouteWithinReach(origin, CitizenPoint(89.001, 64.0, 0.0)) shouldBe false
        citizenRouteWithinReach(origin, CitizenPoint(0.0, 153.001, 0.0)) shouldBe false
    }

    test("a successful route completion remains distinct from a pending route and requires verified arrival") {
        val completed = CitizenNavigationCompletion(null)
        citizenNavigationOutcome(completed, false, 1_000, 10_000, false) shouldBe
            CitizenNavigationOutcome.COMPLETED_OUTSIDE_ARRIVAL
        citizenNavigationOutcome(completed, true, 1_000, 10_000, false) shouldBe CitizenNavigationOutcome.ARRIVED
        citizenNavigationOutcome(null, true, 1_000, 10_000, false) shouldBe CitizenNavigationOutcome.ARRIVED
        citizenNavigationOutcome(null, true, 1_000, 10_000, true) shouldBe CitizenNavigationOutcome.ARRIVED
    }

    test("route cancellation retains its reason and cannot count as an arrival") {
        val cancelled = CitizenNavigationCompletion("STUCK")
        cancelled.cancelReason shouldBe "STUCK"
        citizenNavigationOutcome(cancelled, true, 90_001, 90_000, false) shouldBe CitizenNavigationOutcome.CANCELLED
        citizenNavigationOutcome(null, false, 90_001, 90_000, false) shouldBe CitizenNavigationOutcome.TIMED_OUT
        citizenNavigationOutcome(null, false, 90_000, 90_000, true) shouldBe CitizenNavigationOutcome.PENDING
    }

    test("early packet route completion cannot count work and recovery starts a fresh observed interval") {
        val cycle = CitizenCycle(token(), 1_000)
        cycle.start()
        cycle.arrive(0) // Storage reached; the worker has not reached its workplace yet.
        cycle.phase shouldBe CitizenPhase.TO_WORK
        citizenNavigationOutcome(CitizenNavigationCompletion(null), false, 1_000, 10_000, false) shouldBe
            CitizenNavigationOutcome.COMPLETED_OUTSIDE_ARRIVAL
        cycle.work(60_000)
        cycle.phase shouldBe CitizenPhase.TO_WORK
        cycle.beginDelivery(60_000) shouldBe null

        cycle.arrive(61_000) // Only a verified real arrival or safe recovery may make this transition.
        cycle.work(61_000)
        cycle.phase shouldBe CitizenPhase.WORKING
        cycle.work(61_999)
        cycle.phase shouldBe CitizenPhase.WORKING
        cycle.work(62_000)
        cycle.phase shouldBe CitizenPhase.RETURNING
        cycle.beginDelivery(62_000) shouldBe null
    }

    test("storage reassignment resets an actor only beyond the bounded new leg") {
        val previous = CivPosition(0, 64, 0)
        val actual = CitizenPoint(0.5, 64.0, 0.5)
        citizenStorageNeedsReset(previous, CivPosition(89, 64, 0), actual) shouldBe false
        citizenStorageNeedsReset(previous, CivPosition(90, 64, 0), actual) shouldBe true
        citizenStorageNeedsReset(previous, previous, CitizenPoint(500.5, 64.0, 0.5)) shouldBe false
        citizenStorageNeedsReset(previous, CivPosition(90, 64, 0), CitizenPoint(1.5, 64.0, 0.5)) shouldBe false
        citizenStorageNeedsReset(previous, CivPosition(90, 64, 0), CitizenPoint(1.49, 64.0, 0.5)) shouldBe true
    }

    test("a production timer cannot deliver before work arrival and the return trip") {
        val cycle = CitizenCycle(token(), 60_000)
        cycle.work(100_000)
        cycle.beginDelivery(100_000) shouldBe null
        cycle.start()
        cycle.work(100_000)
        cycle.phase shouldBe CitizenPhase.TO_STORAGE
        cycle.arrive(100_000)
        cycle.phase shouldBe CitizenPhase.TO_WORK
        cycle.arrive(100_000)
        repeat(59) { cycle.work(101_000L + it * 1_000) }
        cycle.phase shouldBe CitizenPhase.WORKING
        cycle.beginDelivery(200_000) shouldBe null
        cycle.work(160_000)
        cycle.phase shouldBe CitizenPhase.RETURNING
        cycle.beginDelivery(200_000) shouldBe null
        cycle.arrive(161_000)
        cycle.beginDelivery(161_000)?.let(cycle::acceptsCompletion) shouldBe true
        cycle.beginDelivery(161_001) shouldBe null
    }

    test("incremental builder ends a two second observed session at work without a return delivery trip") {
        val cycle = CitizenCycle(token(), CivsRules.BUILDER_SESSION_INTERVAL, completeAtWorkplace = true)
        cycle.start()
        cycle.work(100_000)
        cycle.beginDelivery(100_000) shouldBe null
        cycle.arrive(100_000)
        cycle.arrive(100_000)
        cycle.work(101_000)
        cycle.phase shouldBe CitizenPhase.WORKING
        cycle.work(102_000)
        cycle.phase shouldBe CitizenPhase.READY
        cycle.beginDelivery(102_000)?.let(cycle::acceptsCompletion) shouldBe true
        cycle.beginDelivery(102_001) shouldBe null
    }

    test("paid ready and placing orders send the builder straight to a stationary worksite hold") {
        val hall = building(BuildingType.TOWN_HALL, CivPosition(0, 64, 0))
        val hut = building(BuildingType.BUILDER_HUT, CivPosition(8, 64, 0))
        val worker = CivWorker(UUID.randomUUID(), "Builder", hut.id)
        for (phase in listOf(ConstructionPhase.READY_TO_PLACE, ConstructionPhase.PLACING)) {
            val order = CivConstruction(UUID.randomUUID(), hut.id, 2, 2, 0, phase, Stock(),
                plannedBuilding = hut.copy(level = 2), batches = CivConstructionBatches(2),
                payment = CivConstructionPayment(reservedBatch = 0), materials = emptyMap())
            val plan = citizenPlan(city(listOf(hall, hut), worker, order), worker)!!
            plan.idleReason shouldBe "placing"
            val cycle = plan.cycle()
            cycle.start()
            cycle.phase shouldBe CitizenPhase.TO_WORK
            cycle.arrive(1_000)
            cycle.phase shouldBe CitizenPhase.WORKING
            cycle.beginDelivery(100_000) shouldBe null
            citizenConstructionWorking(plan, cycle.phase, order.id) shouldBe true
        }
    }

    test("a builder renewing work credit visits its hut only when carried supplies are missing") {
        val plan = CitizenPlan(token(), CivPosition(0, 64, 0), CivPosition(10, 64, 0),
            BuildingType.BUILDER_HUT, CivsRules.BUILDER_SESSION_INTERVAL, null, completeAtWorkplace = true)
        for (missing in listOf(false, true)) {
            val cycle = plan.copy(suppliesNeeded = missing).cycle()
            cycle.start()
            cycle.phase shouldBe if (missing) CitizenPhase.TO_STORAGE else CitizenPhase.TO_WORK
        }
    }

    test("arrival after a recovery teleport never credits time known to have been away from work") {
        val cycle = CitizenCycle(token(), 2_000, completeAtWorkplace = true)
        reachWork(cycle)
        cycle.arrive(1_000) // Recovery after observing the NPC away from the workplace.
        cycle.work(2_000)
        cycle.phase shouldBe CitizenPhase.WORKING
        cycle.beginDelivery(2_000) shouldBe null
        cycle.work(3_000)
        cycle.phase shouldBe CitizenPhase.READY
    }

    test("navigation keeps retrying safely after three failures instead of an eternal pause") {
        val cycle = CitizenCycle(token(), 2_000, completeAtWorkplace = true)
        cycle.start()
        repeat(5) { attempt ->
            val now = attempt * 20_000L
            cycle.pause("route-unreachable", now, false)
            cycle.retry(now + 15_000)
            cycle.phase shouldBe CitizenPhase.TO_STORAGE
            cycle.beginDelivery(now + 15_000) shouldBe null
        }
        cycle.arrive(100_000)
        cycle.arrive(100_000)
        cycle.work(101_000)
        cycle.work(102_000)
        cycle.phase shouldBe CitizenPhase.READY
    }

    test("two unsafe recovery targets cannot starve a third safe actor on synchronized retries") {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val safe = UUID.randomUUID()
        val actors = linkedMapOf(first to false, second to false, safe to true)
        val examined = mutableListOf<UUID>()
        fun pulse(): UUID? {
            var remaining = 2
            for (id in actors.keys.toList()) {
                if (remaining == 0) break
                remaining--
                citizenRotateAfterAdmission(actors, id)
                examined += id
                if (actors.getValue(id)) return id
            }
            return null
        }
        pulse() shouldBe null
        examined shouldBe listOf(first, second)
        pulse() shouldBe safe
        examined shouldBe listOf(first, second, safe)
    }

    test("safe teleport selection is bounded near the real target and excludes building interiors and void") {
        val hall = building(BuildingType.TOWN_HALL, CivPosition(0, 64, 0))
        val worker = CivWorker(UUID.randomUUID(), "Builder", hall.id)
        val settlement = city(listOf(hall), worker, null)
        val target = CivPosition(20, 64, 20)
        var checks = 0
        citizenRecoveryTarget(settlement, target) { checks++; false } shouldBe null
        checks shouldBe 27
        citizenRecoveryTarget(settlement, target) { it == target } shouldBe target
        val alternate = CivPosition(21, 65, 20)
        citizenRecoveryTarget(settlement, target) { it == alternate } shouldBe alternate
        citizenRecoveryTarget(settlement, hall.bounds.min) { hall.bounds.contains(it.x, it.y, it.z) } shouldBe null
        citizenRecoveryTarget(settlement, CivPosition(settlement.center.x + settlement.radius + 2, 64, 0)) { true } shouldBe null
    }

    test("builder work is ten observed seconds and an inactive interval never catches up") {
        val cycle = CitizenCycle(token(nextDeliveryAt = 100_000), 10_000)
        reachWork(cycle)
        cycle.work(60_000)
        cycle.phase shouldBe CitizenPhase.WORKING
        repeat(10) { cycle.work(61_000L + it * 1_000) }
        cycle.phase shouldBe CitizenPhase.RETURNING
        cycle.arrive(71_000)
        cycle.beginDelivery(99_999) shouldBe null
        cycle.beginDelivery(100_000)?.let(cycle::acceptsCompletion) shouldBe true
    }

    test("a failed trip discards partial work and retries from storage") {
        val cycle = CitizenCycle(token(), 10_000)
        reachWork(cycle)
        repeat(9) { cycle.work(1_000L + it * 1_000) }
        cycle.pause("route-unreachable", 9_000, false)
        cycle.retry(13_999)
        cycle.phase shouldBe CitizenPhase.PAUSED
        cycle.retry(14_000)
        cycle.phase shouldBe CitizenPhase.TO_STORAGE
        cycle.arrive(14_000)
        cycle.arrive(15_000)
        cycle.work(16_000)
        cycle.phase shouldBe CitizenPhase.WORKING
        cycle.beginDelivery(16_000) shouldBe null
    }

    test("closed and replaced deliveries cannot revive an actor") {
        val token = token()
        val old = CitizenCycle(token, 1_000)
        reachWork(old)
        old.work(1_000)
        old.arrive(2_000)
        val ticket = requireNotNull(old.beginDelivery(2_000))
        old.acceptsCompletion(ticket) shouldBe true
        old.close()
        old.acceptsCompletion(ticket) shouldBe false
        old.retry(Long.MAX_VALUE)
        old.resume()
        old.start()
        old.phase shouldBe CitizenPhase.CLOSED
        val replacement = CitizenCycle(token.copy(nextDeliveryAt = token.nextDeliveryAt + 1), 1_000)
        replacement.acceptsCompletion(ticket) shouldBe false
        (replacement.token == old.token) shouldBe false
        (token.copy(assignmentId = UUID.randomUUID()) == token) shouldBe false
        (token.copy(completedCycles = 1) == token) shouldBe false
        (token.copy(constructionId = UUID.randomUUID()) == token) shouldBe false
    }

    test("storage-full retains one completed trip but replaces its failed delivery identity") {
        val cycle = CitizenCycle(token(), 1_000)
        reachWork(cycle)
        cycle.work(1_000)
        cycle.arrive(2_000)
        val old = requireNotNull(cycle.beginDelivery(2_000))
        cycle.pause("storage-full", 3_000, true)
        cycle.acceptsCompletion(old) shouldBe false
        cycle.retry(8_000)
        cycle.phase shouldBe CitizenPhase.READY
        val retry = requireNotNull(cycle.beginDelivery(8_000))
        (old == retry) shouldBe false
        cycle.acceptsCompletion(old) shouldBe false
        cycle.acceptsCompletion(retry) shouldBe true
    }

    for (atWorkplace in listOf(false, true))
    test("storage-full leisure preserves completed work and requires a new arrival atWorkplace=$atWorkplace") {
        val cycle = CitizenCycle(token(), 1_000, completeAtWorkplace = atWorkplace)
        reachWork(cycle)
        cycle.work(1_000)
        if (!atWorkplace) cycle.arrive(2_000)
        val old = requireNotNull(cycle.beginDelivery(2_000))
        cycle.pause("storage-full", 3_000, true)
        cycle.beginDelivery(30_000) shouldBe null
        cycle.resumeAfterWander()
        cycle.phase shouldBe CitizenPhase.RETURNING
        cycle.beginDelivery(30_000) shouldBe null
        cycle.acceptsCompletion(old) shouldBe false
        cycle.arrive(30_000)
        cycle.phase shouldBe CitizenPhase.READY
        val retry = requireNotNull(cycle.beginDelivery(30_000))
        (retry == old) shouldBe false
        cycle.acceptsCompletion(retry) shouldBe true

        val unstarted = CitizenCycle(token(), 1_000, completeAtWorkplace = atWorkplace, directToWork = atWorkplace)
        unstarted.pause("storage-full", 3_000, false)
        unstarted.resumeAfterWander()
        unstarted.phase shouldBe if (atWorkplace) CitizenPhase.TO_WORK else CitizenPhase.TO_STORAGE
        unstarted.beginDelivery(30_000) shouldBe null
    }

    test("four workers retain completed trips through repeated busy commits and earn only once") {
        val hall = building(BuildingType.TOWN_HALL, CivPosition(0, 64, 0))
        val foresters = (1..4).map { building(BuildingType.FORESTER, CivPosition(it * 5, 64, 0)) }
        val workers = foresters.mapIndexed { index, building ->
            CivWorker(UUID.randomUUID(), "Worker ${index + 1}", building.id, nextDeliveryAt = 1)
        }
        var city = CivsRules.validate(city(listOf(hall) + foresters, workers.first(), null).copy(workers = workers))
        val cycles = workers.map { worker ->
            CitizenCycle(requireNotNull(citizenPlan(city, worker)).token, 1_000).also {
                reachWork(it)
                it.work(1_000)
                it.arrive(2_000)
            }
        }
        var now = 2_000L
        repeat(5) {
            cycles.forEach { cycle ->
                val ticket = requireNotNull(cycle.beginDelivery(now))
                cycle.acceptsCompletion(ticket) shouldBe true
                cycle.failDelivery("city-busy", now)
                cycle.acceptsCompletion(ticket) shouldBe false
                cycle.status() shouldBe "waiting"
                cycle.retry(now + 4_999)
                cycle.phase shouldBe CitizenPhase.PAUSED
                cycle.beginDelivery(now + 4_999) shouldBe null
                cycle.retry(now + 5_000)
                cycle.phase shouldBe CitizenPhase.READY
            }
            now += 5_000
        }
        city.stock shouldBe Stock()
        cycles.forEachIndexed { index, cycle ->
            val worker = workers[index]
            val ticket = requireNotNull(cycle.beginDelivery(now))
            cycle.acceptsCompletion(ticket) shouldBe true
            city = CivsRules.deliver(city, city.owner, worker.id, requireNotNull(worker.assignment),
                worker.completedCycles, worker.nextDeliveryAt, now)
            cycle.close()
            cycle.acceptsCompletion(ticket) shouldBe false
            cycle.failDelivery("city-busy", now)
            cycle.retry(now + 5_000)
            cycle.phase shouldBe CitizenPhase.CLOSED
            cycle.beginDelivery(now + 5_000) shouldBe null
            shouldThrow<CivsRejection> {
                CivsRules.deliver(city, city.owner, worker.id, requireNotNull(worker.assignment),
                    worker.completedCycles, worker.nextDeliveryAt, now)
            }.reason shouldBe "stale-delivery"
        }
        city.stock shouldBe Stock(wood = 16)
        city.workers.map { it.completedCycles } shouldBe listOf(1L, 1L, 1L, 1L)
        city.revision shouldBe 4L
    }

    test("route failures get only two timed automatic retries") {
        val retry = CitizenRetry()
        retry.ready(0) shouldBe true
        retry.fail(100)
        retry.ready(5_099) shouldBe false
        retry.ready(5_100) shouldBe true
        retry.fail(5_100)
        retry.ready(20_099) shouldBe false
        retry.ready(20_100) shouldBe true
        retry.fail(20_100)
        retry.exhausted shouldBe true
        retry.ready(Long.MAX_VALUE) shouldBe false
    }

    test("spawn retry exhaustion permits a slow native reevaluation without changing the plan") {
        val retry = CitizenRetry(exhaustedCooldownMillis = 60_000)
        retry.fail(100)
        retry.fail(5_100)
        retry.fail(20_100)
        retry.exhausted shouldBe true
        retry.nextAttemptAt shouldBe 80_100L
        retry.ready(80_099) shouldBe false
        retry.ready(80_100) shouldBe true
        retry.fail(80_100)
        retry.nextAttemptAt shouldBe 140_100L
        retry.ready(140_099) shouldBe false
        retry.ready(140_100) shouldBe true
    }

    test("spawn candidates retain the original floor before bounded nearby heights") {
        val origin = CivPosition(-408, 81, -505)
        val candidates = citizenSpawnCandidates(origin, 0).toList()
        candidates.size shouldBe CITIZEN_SPAWN_CANDIDATE_LIMIT
        candidates.distinct().size shouldBe CITIZEN_SPAWN_CANDIDATE_LIMIT
        candidates.first() shouldBe origin
        candidates.chunked(13).map { floor -> floor.map { it.y }.distinct().single() } shouldBe
            listOf(81, 82, 80, 83, 79, 84, 78, 85, 77)
        candidates.all { kotlin.math.abs(it.x - origin.x) <= 2 && kotlin.math.abs(it.z - origin.z) <= 2 } shouldBe true
        citizenSpawnCandidates(origin, 1).first() shouldBe origin.copy(x = origin.x + 1)
    }

    test("builder captures construction identity and the site's real barrel with entrance fallback only when absent") {
        val hall = building(BuildingType.TOWN_HALL, CivPosition(0, 64, 0))
        val farm = building(BuildingType.FARM, CivPosition(8, 64, 0))
        val upgradedFarm = farm.copy(level = 2, entrance = CivPosition(12, 64, 0))
        val worker = CivWorker(UUID.randomUUID(), "Иван", hall.id, 3, 25_000)
        val order = CivConstruction(UUID.randomUUID(), farm.id, 2, 6, 0,
            ConstructionPhase.CONSTRUCTING, Stock(), plannedBuilding = upgradedFarm)
        val city = city(listOf(hall, farm), worker, order)
        val plan = requireNotNull(citizenPlan(city, worker))
        plan.workplace shouldBe farm.control
        val bareSite = city.copy(buildings = listOf(hall, farm.copy(control = null)),
            construction = order.copy(plannedBuilding = upgradedFarm.copy(control = null)))
        citizenPlan(bareSite, worker)?.workplace shouldBe upgradedFarm.entrance
        plan.token shouldBe CitizenToken(hall.id, 3, 25_000, order.id)
        plan.workMillis shouldBe CivsRules.BUILDER_INTERVAL
        plan.completeAtWorkplace shouldBe true
        plan.idleReason shouldBe null
        citizenPlan(city.copy(construction = null), worker)?.idleReason shouldBe "no-construction"
        citizenPlan(city.copy(construction = order.copy(phase = ConstructionPhase.PLACING)), worker)?.idleReason shouldBe "placing"
        val missingDepot = city.copy(buildings = listOf(hall.copy(control = null), farm))
        citizenPlan(missingDepot.copy(construction = order.copy(phase = ConstructionPhase.PAUSED,
            failure = "protected-area")), worker)?.idleReason shouldBe "construction-paused"
        citizenPlan(missingDepot.copy(construction = order.copy(phase = ConstructionPhase.WAITING_MATERIALS)), worker)
            ?.idleReason shouldBe "waiting-materials"
        citizenPlan(city, worker.copy(assignment = null))?.idleReason shouldBe "unassigned"
    }

    test("a site hall supplies a depot while a builder assigned to its unfinished hut can build that hut") {
        val hall = building(BuildingType.TOWN_HALL, CivPosition(0, 64, 0)).copy(phase = BuildingPhase.SITE)
        val hut = building(BuildingType.BUILDER_HUT, CivPosition(8, 64, 0)).copy(phase = BuildingPhase.CONSTRUCTING)
        val worker = CivWorker(UUID.randomUUID(), "Строитель", hut.id, nextDeliveryAt = 10_000)
        val order = CivConstruction(UUID.randomUUID(), hut.id, 1, 2, 0, ConstructionPhase.CONSTRUCTING,
            Stock(), plannedBuilding = hut, batches = CivConstructionBatches(2), materials = emptyMap())
        val city = city(listOf(hall, hut), worker, order)
        val plan = requireNotNull(citizenPlan(city, worker))
        plan.storage shouldBe hall.control
        plan.workplace shouldBe hut.control
        plan.workMillis shouldBe CivsRules.BUILDER_INTERVAL
        plan.idleReason shouldBe null
        citizenPlan(city, worker.copy(assignment = hall.id))?.idleReason shouldBe "building-unavailable"
        val otherOrder = order.copy(buildingId = hall.id, plannedBuilding = hall.copy(phase = BuildingPhase.CONSTRUCTING))
        citizenPlan(city.copy(construction = otherOrder), worker)?.idleReason shouldBe "building-unavailable"
        citizenPlan(city.copy(construction = null), worker.copy(assignment = null))?.idleReason shouldBe "unassigned"
    }

    test("upgrading producers retain their current work while a builder rebuilds its own active hut") {
        val hall = building(BuildingType.TOWN_HALL, CivPosition(0, 64, 0))
        val hut = building(BuildingType.BUILDER_HUT, CivPosition(8, 64, 0))
        val farm = building(BuildingType.FARM, CivPosition(12, 64, 0))
        val worker = CivWorker(UUID.randomUUID(), "Житель", farm.id, nextDeliveryAt = 10_000)
        val order = CivConstruction(UUID.randomUUID(), farm.id, 2, 2, 0, ConstructionPhase.CONSTRUCTING,
            Stock(), plannedBuilding = farm.copy(level = 2, phase = BuildingPhase.CONSTRUCTING),
            batches = CivConstructionBatches(2), materials = emptyMap())
        val city = city(listOf(hall, hut, farm), worker, order)
        citizenPlan(city, worker)?.idleReason shouldBe null
        val selfUpgrade = order.copy(buildingId = hut.id, plannedBuilding = hut.copy(level = 2, phase = BuildingPhase.CONSTRUCTING))
        citizenPlan(city.copy(construction = selfUpgrade), worker.copy(assignment = hut.id))?.idleReason shouldBe null
    }

    test("idle wander remains bounded and timed without advancing a production cycle") {
        val hall = building(BuildingType.TOWN_HALL, CivPosition(0, 64, 0)).copy(phase = BuildingPhase.SITE)
        val worker = CivWorker(UUID.fromString("9d0a2c2a-2abb-4d5b-9150-7659bfb1df84"), "Свободный", null)
        val city = city(listOf(hall), worker, null)
        val plan = requireNotNull(citizenPlan(city, worker))
        plan.idleReason shouldBe "unassigned"
        val wander = CitizenWander(worker.id)
        val cycle = CitizenCycle(plan.token, plan.workMillis)
        wander.ready(0) shouldBe false
        wander.ready(400) shouldBe true
        val targets = (0..39).map { requireNotNull(citizenWanderTarget(city, hall.entrance, worker.id, it)) }
        (targets.distinct().size >= 30) shouldBe true
        targets.all { city.contains(it.x, it.z) && it.x * it.x + it.z * it.z <= 64 && it.y in 62..66 } shouldBe true
        targets.take(5).map { it.x to it.z }.distinct().size shouldBe 1
        targets.take(5).map { it.y } shouldBe listOf(64, 65, 63, 66, 62)
        targets[5].let { it.x to it.z } shouldBe targets[9].let { it.x to it.z }
        (targets[0].let { it.x to it.z } != targets[5].let { it.x to it.z }) shouldBe true
        wander.reject(4_000)
        wander.ready(4_099) shouldBe false
        wander.ready(4_350) shouldBe true
        wander.step shouldBe 1
        wander.finish(6_000, true, resumeImmediately = true)
        wander.step shouldBe 5
        wander.ready(6_000) shouldBe true
        wander.finish(13_000, false)
        wander.ready(15_499) shouldBe false
        wander.ready(17_000) shouldBe true
        cycle.phase shouldBe CitizenPhase.IDLE
        cycle.beginDelivery(Long.MAX_VALUE) shouldBe null
        worker.completedCycles shouldBe 0L
        city.stock shouldBe Stock()
        (0..39).all { citizenWanderTarget(city.copy(radius = 1), hall.entrance, worker.id, it) == null } shouldBe true
        repeat(40) { wander.reject(Long.MAX_VALUE) }
        wander.ready(Long.MAX_VALUE - 1) shouldBe false
        wander.ready(Long.MAX_VALUE) shouldBe true
    }

    test("residents that shared a ring start now have individual random destinations and rest times") {
        val hall = building(BuildingType.TOWN_HALL, CivPosition(0, 64, 0)).copy(phase = BuildingPhase.SITE, control = null)
        val ids = listOf("9d0a2c2a-2abb-4d5b-9150-7659bfb1df84", "06254fa5-4795-4a8e-a7c7-783f9fc90cfd")
            .map(UUID::fromString)
        val city = city(listOf(hall), CivWorker(ids.first(), "Idle", null), null)
        val targets = ids.map { id -> (0 until 24).map { round ->
            requireNotNull(citizenWanderTarget(city, hall.entrance, id, round * 5))
        } }
        (targets[0] != targets[1]) shouldBe true
        targets.forEach { sequence ->
            (sequence.distinct().size >= 16) shouldBe true
            (sequence.take(8) != sequence.drop(8).take(8)) shouldBe true
            (sequence.map { it.x * it.x + it.z * it.z }.distinct().size >= 6) shouldBe true
        }
        fun timing(id: UUID): List<Long> {
            val wander = CitizenWander(id)
            var now = 0L
            return (0..8).map {
                while (!wander.ready(now)) now += 50
                now.also { wander.finish(now, true) }
            }
        }
        timing(ids[0]) shouldBe timing(ids[0])
        (timing(ids[0]) != timing(ids[1])) shouldBe true
        timing(ids[0]).zipWithNext().all { (a, b) -> b - a in 2_000L..6_550L } shouldBe true
    }

    test("idle walks keep their workplace or home anchor and reject climbs without limiting a working route") {
        MockBukkitTestRuntime.open().use { paper ->
            val hall = building(BuildingType.TOWN_HALL, CivPosition(0, 64, 0))
            val farm = building(BuildingType.FARM, CivPosition(25, 64, 0))
            val home = building(BuildingType.HOUSE, CivPosition(-20, 64, 0))
            val worker = CivWorker(UUID.randomUUID(), "Farmer", farm.id)
            val city = city(listOf(hall, farm, home), worker, null)
            citizenIdleBuilding(city, worker) shouldBe farm
            citizenIdleBuilding(city, worker.copy(assignment = null)) shouldBe home
            citizenIdleBuilding(city.copy(buildings = listOf(hall, farm)), worker.copy(assignment = null)) shouldBe hall
            val anchor = farm.control!!
            val at = CitizenPoint(anchor.x + .5, anchor.y.toDouble(), anchor.z + .5)
            citizenIdleRouteAllowed(anchor, at, anchor.copy(x = anchor.x + 8)) shouldBe true
            citizenIdleRouteAllowed(anchor, at, anchor.copy(x = anchor.x + 9)) shouldBe false
            citizenIdleRouteAllowed(anchor, at, anchor.copy(y = anchor.y + 3)) shouldBe false
            val distant = at.copy(x = at.x + 20)
            citizenIdleRouteAllowed(anchor, distant, anchor.copy(x = anchor.x + 19)) shouldBe true
            citizenIdleRouteAllowed(anchor, distant, anchor.copy(x = anchor.x + 21)) shouldBe false
            citizenRouteWithinReach(at, at.copy(x = at.x + 60)) shouldBe true
            val world = paper.addSimpleWorld("idle-ground")
            world.loadChunk(0, 0)
            val point = CivPosition(2, 64, 2)
            val floor = world.getBlockAt(2, 63, 2)
            floor.type = Material.DIRT
            citizenIdleTerrain(world, point) shouldBe true
            for (material in listOf(Material.VINE, Material.LADDER, Material.SCAFFOLDING)) {
                world.getBlockAt(2, 64, 2).type = material
                citizenIdleTerrain(world, point) shouldBe false
            }
            world.getBlockAt(2, 64, 2).type = Material.AIR
            for (material in listOf(Material.OAK_LOG, Material.OAK_LEAVES)) {
                floor.type = material
                citizenIdleTerrain(world, point) shouldBe false
            }
            val authored = hall.copy(bounds = CivBox(CivPosition(0, 63, 0), CivPosition(4, 66, 4)))
            for (material in listOf(Material.OAK_LOG, Material.STRIPPED_OAK_LOG)) {
                floor.type = material
                citizenIdleTerrain(world, point, listOf(authored)) shouldBe true
                citizenIdleTerrain(world, point, listOf(authored.copy(phase = BuildingPhase.SITE))) shouldBe false
                citizenIdleTerrain(world, point, listOf(authored.copy(
                    bounds = CivBox(CivPosition(5, 63, 0), CivPosition(9, 66, 4))))) shouldBe false
                citizenIdleTerrain(world, point, listOf(authored.copy(
                    bounds = CivBox(CivPosition(0, 65, 0), CivPosition(4, 68, 4))))) shouldBe false
            }
            for (material in listOf(Material.OAK_LEAVES, Material.MAGMA_BLOCK, Material.CAMPFIRE)) {
                floor.type = material
                citizenIdleTerrain(world, point, listOf(authored)) shouldBe false
            }
        }
    }

    test("wander finds nearby raised and lowered floors before moving to another horizontal target") {
        MockBukkitTestRuntime.open().use {
            val world = mockk<World>()
            val chunk = mockk<Chunk>()
            every { world.isChunkLoaded(any(), any()) } returns true
            every { world.getChunkAt(any<Int>(), any<Int>(), false) } returns chunk
            every { chunk.loadLevel } returns Chunk.LoadLevel.ENTITY_TICKING
            every { world.minHeight } returns -64
            every { world.maxHeight } returns 320
            var floorY = 63
            var blockedY: Int? = null
            var liquidY: Int? = null
            every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
                val x = firstArg<Int>(); val y = secondArg<Int>(); val z = thirdArg<Int>()
                val solid = y <= floorY || y == blockedY
                mockk<Block> {
                    every { type } returns if (solid) Material.STONE else Material.AIR
                    every { isPassable } returns !solid
                    every { isLiquid } returns (y == liquidY)
                    every { boundingBox } returns BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(),
                        x + 1.0, y + if (solid) 1.0 else 0.0, z + 1.0)
                }
            }
            // This fixture tests floor heights; a randomly selected barrel column is a
            // separate geometric rejection and must not make its candidate disappear.
            val hall = building(BuildingType.TOWN_HALL, CivPosition(0, 64, 0)).copy(control = null)
            val worker = CivWorker(UUID.randomUUID(), "Idle", null)
            val city = city(listOf(hall), worker, null)
            for (height in listOf(64, 65, 63, 66, 62)) {
                floorY = height - 1
                val wander = CitizenWander(worker.id)
                var selected: CivPosition? = null
                var now = 0L
                for (pulse in 0..4) {
                    while (!wander.ready(now)) now += 50
                    val target = requireNotNull(citizenWanderTarget(city, hall.entrance, worker.id, wander.step))
                    if (citizenStandable(world, target)) { selected = target; break }
                    wander.reject(now)
                }
                selected?.y shouldBe height
            }
            val target = requireNotNull(citizenWanderTarget(city, hall.entrance, worker.id, 0))
            floorY = 63
            blockedY = 64
            citizenStandable(world, target) shouldBe false
            blockedY = 65
            citizenStandable(world, target) shouldBe false
            blockedY = null
            liquidY = 64
            citizenStandable(world, target) shouldBe false
            liquidY = 63
            citizenStandable(world, target) shouldBe false
            liquidY = null
            citizenStandable(world, target) shouldBe true
            every { world.isChunkLoaded(any(), any()) } returns false
            every { world.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } throws AssertionError("Unloaded terrain must not be read")
            citizenStandable(world, target) shouldBe false
            verify(exactly = 0) { world.getChunkAt(any<Int>(), any<Int>(), true) }
        }
    }
})

private fun token(nextDeliveryAt: Long = 0) = CitizenToken(UUID.randomUUID(), 0, nextDeliveryAt, null)
private fun reachWork(cycle: CitizenCycle) { cycle.start(); cycle.arrive(0); cycle.arrive(0) }
private fun building(type: BuildingType, entrance: CivPosition) = CivBuilding(UUID.randomUUID(), type, 1,
    entrance, 0, "test", CivBox(entrance, entrance), entrance, BuildingPhase.ACTIVE, control = entrance.copy(z = entrance.z - 2))
private fun city(buildings: List<CivBuilding>, worker: CivWorker, order: CivConstruction?) = Settlement(
    UUID.randomUUID(), UUID.randomUUID(), "Test", UUID.randomUUID(), CivPosition(0, 64, 0), 48,
    "test", Stock(), buildings, listOf(worker), construction = order)
