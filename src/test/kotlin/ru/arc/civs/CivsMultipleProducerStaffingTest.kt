package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.Executor

class CivsMultipleProducerStaffingTest : FunSpec({
    test("twenty four adults staff each resource building and depot before extra slots while children keep their belongings") {
        val base = staffingCity()
        val home = base.buildings.first { it.type == BuildingType.HOUSE }
        val adults = (0 until 24).map { index -> staffingWorker(index).copy(assignment = null, nextDeliveryAt = 0,
            homeId = home.id.takeIf { index < 2 }) }
        val children = (0 until 4).map { index -> CivWorker(UUID(139, 300 + index.toLong()), "Child $index", null,
            sex = CivSex.MALE, homeId = home.id, parentIds = setOf(adults[0].id, adults[1].id), childMinutes = 0, hunger = 20) }
        val before = CivsRules.validate(base.copy(workers = adults + children))
        var current = before
        repeat(24) { current = CivsRules.autoAssign(current, current.owner, 1_000) }
        val jobs = current.buildings.filter { it.type in staffingEssentialTypes }
        jobs.size shouldBe 9
        jobs.all { job -> current.workers.any { it.assignment == job.id } } shouldBe true
        current.workers.filter(CivsResidents::isChild) shouldBe children
        current.workers.filterNot(CivsResidents::isChild).forEach { worker ->
            val original = adults.single { it.id == worker.id }
            worker.copy(assignment = original.assignment, nextDeliveryAt = original.nextDeliveryAt) shouldBe original
        }
        current.stock shouldBe before.stock
        current.inventory shouldBe before.inventory
        current.construction shouldBe null
    }

    test("a staffed physical village does not request automatic assignment for manual vacancies") {
        val staffed = staffedVillage()

        staffed.workers.count { it.assignment == null } shouldBe 8
        CivsRules.needsAutoAssignment(staffed) shouldBe false
        CivsRules.autoAssign(staffed, staffed.owner, 2_000) shouldBe staffed

        val idle = staffed.workers.first { it.assignment == null }
        val forge = staffed.buildings.single { it.type == BuildingType.BLACKSMITH }
        CivsRules.assign(staffed, staffed.owner, idle.id, forge.id, 2_000)
            .workers.first { it.id == idle.id }.assignment shouldBe forge.id
    }

    test("an automatic vacancy waits for an adult who can be reassigned safely") {
        val worker = staffingWorker(0)
        val city = staffingCity().copy(workers = listOf(worker))

        CivsRules.needsAutoAssignment(city) { false } shouldBe false
        CivsRules.autoAssign(city, city.owner, 1_000) { false } shouldBe city
        CivsRules.needsAutoAssignment(city) { it == worker.id } shouldBe true
        CivsRules.autoAssign(city, city.owner, 1_000) { it == worker.id }.workers.single().assignment shouldBe
            city.buildings.first { it.type == BuildingType.BUILDER_HUT }.id
    }

    test("open provisions can staff both stocked depots and additional funded scribes without moving assigned residents") {
        val city = staffedVillage()
        val (first, second) = city.buildings.filter { it.type == BuildingType.WAREHOUSE }
        val tower = city.buildings.single { it.type == BuildingType.GUARD_TOWER }
        val hall = city.buildings.single { it.type == BuildingType.TOWN_HALL }
        val forge = city.buildings.single { it.type == BuildingType.BLACKSMITH }
        val snapshot = staffingObservation(city, listOf(
            CivSupplyRequest(tower.id, "WHEAT", 128, 0), CivSupplyRequest(tower.id, "GRAVEL", 128, 0),
            CivSupplyRequest(tower.id, "BOOK", 3, 0), CivSupplyRequest(tower.id, "IRON_SHOVEL", 3, 0),
        ), mapOf(first.id to mapOf("WHEAT" to 128), second.id to mapOf("GRAVEL" to 128),
            hall.id to mapOf("PAPER" to 9, "LEATHER" to 3), forge.id to mapOf("STICK" to 6)))
        val staffing = allocateCivSupplies(snapshot).provisionStaffing
        staffing shouldBe mapOf(first.id to 2, second.id to 2, hall.id to 3)

        val unavailable = city.workers.first { it.assignment == null }
        val staffed = CivsRules.autoAssign(city, city.owner, 2_000, staffing) { it != unavailable.id }
        staffed.workers.first { it.id == unavailable.id } shouldBe unavailable
        staffed.workers.take(19) shouldBe city.workers.take(19)
        staffed.workers.count { it.assignment == null } shouldBe 4
        CivsRules.needsAutoAssignment(staffed, staffing) shouldBe false
        CivsRules.autoAssign(staffed, staffed.owner, 3_000, staffing) shouldBe staffed
        staffed.stock shouldBe city.stock
        staffed.inventory shouldBe city.inventory
    }

    test("provisions already in transit, promised or blocked by full storage do not add staff") {
        val city = staffedVillage()
        val warehouse = city.buildings.first { it.type == BuildingType.WAREHOUSE }
        val courier = city.workers.single { it.assignment == warehouse.id }
        val tower = city.buildings.single { it.type == BuildingType.GUARD_TOWER }
        val hall = city.buildings.single { it.type == BuildingType.TOWN_HALL }
        val scribe = city.workers.single { it.assignment == hall.id }
        val snapshot = staffingObservation(city, listOf(
            CivSupplyRequest(tower.id, "WHEAT", 128, 64), CivSupplyRequest(tower.id, "BOOK", 3, 0),
        ), mapOf(warehouse.id to mapOf("WHEAT" to 128), hall.id to mapOf("PAPER" to 9, "LEATHER" to 3)))
        val cargo = CivCargo(
            CivsLogistics.shipmentId(city.id, courier.id, courier.completedCycles, warehouse.id, tower.id, "WHEAT"),
            warehouse.id, warehouse.id, tower.id, warehouse.entrance, "WHEAT", 64)
        val reserved = mapOf(courier.id to CivCourierPlan(cargo, tower.id, tower.entrance, carrying = false))
        val order = CivProductionOrder(hall.id, "BOOK", "BOOK", scribe.completedCycles, scribe.completedCycles + 3, 1)
        val promised = CivProductionBoard(orders = mapOf(scribe.id to order))
        allocateCivSupplies(snapshot.copy(retained = reserved), previousProduction = promised).provisionStaffing shouldBe emptyMap()
        val stores = snapshot.stores + (tower.id to snapshot.stores.getValue(tower.id).copy(capacity = CivSupplyCapacity(0, emptyMap())))
        allocateCivSupplies(snapshot.copy(stores = stores)).provisionStaffing shouldBe emptyMap()
        allocateCivSupplies(snapshot.copy(provisions = null)).provisionStaffing shouldBe emptyMap()
    }

    test("only a current ordinary supply result publishes staffing") {
        val city = staffedVillage()
        val depot = city.buildings.first { it.type == BuildingType.WAREHOUSE }
        val tower = city.buildings.single { it.type == BuildingType.GUARD_TOWER }
        val snapshot = staffingObservation(city, listOf(CivSupplyRequest(tower.id, "WHEAT", 128, 0)),
            mapOf(depot.id to mapOf("WHEAT" to 128)))
        MockBukkitTestRuntime.open().use { paper ->
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("ProvisionStaffing")))
            val nativeWork = BudgetedTaskQueue(tasks, maximumSteps = 1, nanoTime = { 0L })
            var tick = 0L
            nativeWork.drainAvailable { tick }
            val worker = ArrayDeque<Runnable>()
            var now = 1_000L
            var receiptOnly = false
            val dispatcher = CivsSupplyDispatcher(tasks, nativeWork, { _, retained ->
                val observation = if (!receiptOnly) snapshot else snapshot.copy(
                    inputKey = CivSupplyObservation(Unit, CivSupplyCaptureResult(city.id, emptyMap()), UUID(0, 7)),
                    productionSurvey = CivProductionSurvey(city, emptyMap(), now, null))
                val step: () -> CivSupplySnapshot? = { observation.copy(retained = retained) }
                step
            }, { _, _ -> true }, Executor { worker.addLast(it) }, { now })
            fun pulse() { tick++; paper.performTicks(1); dispatcher.pulse() }
            try {
                dispatcher.request(city.id)
                pulse()
                dispatcher.request(city.id)
                worker.removeFirst().run()
                pulse()
                dispatcher.provisionStaffing(city.id) shouldBe emptyMap()
                now += CivsSupplyDispatcher.COALESCE_MILLIS
                pulse()
                worker.removeFirst().run()
                pulse()
                dispatcher.provisionStaffing(city.id) shouldBe mapOf(depot.id to 2)

                receiptOnly = true
                dispatcher.request(city.id)
                dispatcher.provisionStaffing(city.id) shouldBe emptyMap()
                now += CivsSupplyDispatcher.COALESCE_MILLIS
                pulse()
                worker.removeFirst().run()
                pulse()
                dispatcher.provisionStaffing(city.id) shouldBe emptyMap()
            } finally {
                dispatcher.close()
                nativeWork.close()
                tasks.close()
            }
        }
    }

    test("ordinary recovery preserves one worker in every raw site and depot and borrows only a safe surplus") {
        val base = staffingCity()
        val jobs = base.buildings.filter { it.type in staffingEssentialTypes }
        val workers = jobs.mapIndexed { index, job -> staffingWorker(index).copy(assignment = job.id) }
        val staffed = CivsRules.validate(base.copy(workers = workers))
        // The unstaffed tavern cannot take the sole worker of a second building of the same type.
        CivsAutonomy.criticalAssignment(staffed) { true } shouldBe null
        val target = jobs.last { it.type == BuildingType.FARM }
        val forest = jobs.first { it.type == BuildingType.FORESTER }
        val spare = staffingWorker(50).copy(assignment = forest.id)
        val before = CivsRules.validate(staffed.copy(workers = workers.filterNot { it.assignment == target.id } + spare))
        CivsAutonomy.criticalAssignment(before) { it == spare.id } shouldBe (spare.id to target.id)
        CivsRules.autoAssign(before, before.owner, 1_000) { false } shouldBe before
        val after = CivsRules.autoAssign(before, before.owner, 1_000) { it == spare.id }
        after.workers.single { it.id == spare.id }.assignment shouldBe target.id
        after.workers.filterNot { it.id == spare.id } shouldBe before.workers.filterNot { it.id == spare.id }
        after.workers.single { it.id == spare.id }.copy(assignment = spare.assignment,
            nextDeliveryAt = spare.nextDeliveryAt) shouldBe spare
        after.stock shouldBe before.stock
        after.inventory shouldBe before.inventory
    }

    test("accepted builder recovery may borrow a forester or miner while each last farmer and depot keeper stays") {
        val base = staffingCity()
        val workers = base.buildings.filter { it.type in staffingEssentialTypes }.mapIndexed { index, job ->
            staffingWorker(index).copy(assignment = job.id)
        }
        val house = base.buildings.first { it.type == BuildingType.HOUSE }.copy(level = 1, phase = BuildingPhase.SITE)
        val city = CivsRules.validate(base.copy(workers = workers, constructionQueue = listOf(house.id),
            buildings = base.buildings.map { if (it.id == house.id) house else it }))
        val hut = city.buildings.first { it.type == BuildingType.BUILDER_HUT }
        for (type in listOf(BuildingType.FORESTER, BuildingType.QUARRY)) {
            val worker = workers.last { city.building(requireNotNull(it.assignment))?.type == type }
            CivsAutonomy.criticalAssignment(city) { it == worker.id } shouldBe (worker.id to hut.id)
        }
        for (worker in workers.filter { city.building(requireNotNull(it.assignment))?.type in
            setOf(BuildingType.FARM, BuildingType.WAREHOUSE) })
            CivsAutonomy.criticalAssignment(city) { it == worker.id } shouldBe null
        city.constructionQueue shouldBe listOf(house.id)
        city.workers shouldBe workers
    }
})

private val staffingEssentialTypes = setOf(BuildingType.FORESTER, BuildingType.QUARRY, BuildingType.FARM, BuildingType.WAREHOUSE)
private fun staffingWorker(index: Int) = CivWorker(UUID(139, 100 + index.toLong()), "Adult $index", null,
    completedCycles = 4, nextDeliveryAt = 1, sex = if (index % 2 == 0) CivSex.MALE else CivSex.FEMALE,
    hunger = 20, skills = mapOf("FARM" to 3L), items = List(CivsEquipment.SLOTS) {
        if (it == CivsEquipment.BAG) CivResidentItem("BREAD", 2) else null
    })

private fun staffedVillage(): Settlement {
    val base = staffingCity()
    val primarySites = base.buildings.filter { it.type.isWorldWork }.distinctBy(CivBuilding::type).mapTo(hashSetOf(), CivBuilding::id)
    val buildings = base.buildings.filter { !it.type.isWorldWork || it.id in primarySites }
    val city = CivsRules.validate(base.copy(buildings = buildings, workers = List(27, ::staffingWorker)))
    return CivsRules.autoAssign(city, city.owner, 1_000)
}

private fun staffingObservation(city: Settlement, requests: List<CivSupplyRequest>, stock: Map<UUID, Map<String, Long>>): CivSupplySnapshot {
    val stores = city.buildings.associate { building ->
        building.id to CivSupplyStore(building.id, building.entrance, requireNotNull(building.control),
            stock[building.id].orEmpty(), emptyMap(), CivsProduction.material(building), CivSupplyCapacity(12, emptyMap()), building.type)
    }
    val stacks = requests.associate { it.material to if (it.material == "IRON_SHOVEL") 1 else 64 }
    val couriers = city.workers.mapNotNull { worker ->
        val depot = worker.assignment?.let(city::building)?.takeIf { it.type == BuildingType.WAREHOUSE } ?: return@mapNotNull null
        CivSupplyCourier(worker.id, depot.id, worker.completedCycles, depot.entrance, stacks.mapValues { 64L })
    }
    val tower = city.buildings.single { it.type == BuildingType.GUARD_TOWER }
    return CivSupplySnapshot(city.id, Unit, stores, requests, couriers, emptyList(), emptyMap(), stacks,
        provisions = CivProvisionStaffing(city, tower.id))
}

private fun staffingCity(): Settlement {
    val types = listOf(BuildingType.TOWN_HALL) + CivsAutonomy.completeProfile.toMutableList().apply { remove(BuildingType.TOWN_HALL) }
    val buildings = types.mapIndexed { index, type ->
        val point = CivPosition(-48 + index % 8 * 12, 64, -24 + index / 8 * 12)
        CivBuilding(UUID(139, index + 1L), type, type.maxLevel, point, 0,
            if (type == BuildingType.PLAN_BOARD) CivsPlanBoardBlueprint.SCHEMATIC_ID else "fixture/$type",
            CivBox(point.copy(x = point.x - 2, y = 63, z = point.z - 3), point.copy(x = point.x + 2, y = 69, z = point.z - 1)),
            point, BuildingPhase.ACTIVE, point.copy(z = point.z - 1))
    }
    return CivsRules.validate(Settlement(UUID(139, 400), UUID(139, 401), "Staffing", UUID(139, 402),
        CivPosition(0, 64, 0), 128, "fixture", Stock(), buildings, emptyList(),
        autonomy = CivAutonomy(), inventory = CivInventory(CivStorageMode.PHYSICAL)))
}
