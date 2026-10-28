package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executor

class CivsAutonomousLogisticsTest : FunSpec({
    test("observed producer sessions queue two batches and train once each without a depot trip or native items") {
        val before = autonomousCargoCity()
        val producer = before.workers.first()
        val plan = requireNotNull(citizenPlan(before, producer))
        plan.completeAtWorkplace shouldBe true
        plan.storage shouldBe before.building(producer.assignment!!)!!.entrance
        val cycle = plan.cycle()
        cycle.start()
        cycle.phase shouldBe CitizenPhase.TO_WORK
        cycle.arrive(1_000)
        for (second in 1..60) cycle.work(1_000L + second * 1_000)
        cycle.phase shouldBe CitizenPhase.READY
        val first = produceAutonomousCargo(before)
        val second = produceAutonomousCargo(first)
        first.stock shouldBe before.stock
        second.stock shouldBe before.stock
        second.workers.first().pendingStock shouldBe Stock(food = 6)
        second.workers.first().completedCycles shouldBe 2L
        second.workers.first().skills!!["FARM"] shouldBe 2L
        second.workers.last().skills shouldBe null
        second.inventory shouldBe null
        second.workers.all { it.items == null && it.inventory == null } shouldBe true
        shouldThrow<CivsRejection> { CivsRules.deliver(first, first.owner, producer.id, producer.assignment!!,
            producer.completedCycles, producer.nextDeliveryAt, 200_000) }.reason shouldBe "stale-delivery"
    }

    test("the existing allocator reserves one producer for one courier and limits each internal shipment to 64") {
        val base = autonomousCargoCity()
        val warehouse = base.buildings.first { it.type == BuildingType.WAREHOUSE }.copy(level = 2)
        val second = base.workers.last().copy(id = UUID(3, 3), name = "Second courier")
        val city = CivsRules.validate(base.copy(buildings = base.buildings.map { if (it.id == warehouse.id) warehouse else it },
            workers = listOf(base.workers.first().copy(pendingStock = Stock(food = 100)), base.workers.last(), second)))
        val allocated = allocateAutonomousCargo(city)
        allocated.size shouldBe 1
        val plan = allocated.values.single()
        plan.cargo.amount shouldBe 64L
        plan.cargo.stockResource shouldBe Resource.FOOD
        plan.cargo.inventoryBacked shouldBe false
        plan.cargo.sourceWorkerId shouldBe city.workers.first().id
    }

    test("pending production pickup and unload survive the real store and cannot be credited twice") {
        val root = Files.createTempDirectory("civs-autonomous-cargo-")
        val executor = Executor { it.run() }
        try {
            val before = autonomousCargoCity()
            val produced = produceAutonomousCargo(before)
            val courier = before.workers.last()
            val plan = allocateAutonomousCargo(produced).getValue(courier.id)
            CivsStore(root, executor).use { store ->
                store.save(before).join()
                store.save(produced).join()
            }
            val pending = CivsStore(root, executor).use { it.load().join().single() }
            pending shouldBe produced
            val picked = CivsLogistics.pickup(pending, courier.id, plan.cargo)
            picked.stock shouldBe before.stock
            picked.workers.first().pendingStock shouldBe null
            picked.workers.last().cargo shouldBe plan.cargo
            CivsLogistics.pickup(picked, courier.id, plan.cargo) shouldBe picked
            CivsLogistics.materializeCargo(picked) shouldBe picked
            CivsStore(root, executor).use { it.save(picked).join() }
            val restored = CivsStore(root, executor).use { it.load().join().single() }
            restored shouldBe picked
            val unloaded = CivsLogistics.unload(restored, courier.id, plan.cargo.id, 200_000)
            unloaded.stock shouldBe before.stock.copy(food = before.stock.food + 3)
            unloaded.workers.first().skills!!["FARM"] shouldBe 1L
            unloaded.workers.last().skills!!["WAREHOUSE"] shouldBe 1L
            unloaded.workers.last().cargo shouldBe null
            unloaded.workers.all { it.items == null && it.inventory == null } shouldBe true
            shouldThrow<CivsRejection> { CivsLogistics.unload(unloaded, courier.id, plan.cargo.id, 201_000) }
                .reason shouldBe "stale-delivery"
            CivsStore(root, executor).use { store -> store.save(unloaded).join(); store.save(unloaded).join() shouldBe unloaded }
            CivsStore(root, executor).use { it.load().join().single() } shouldBe unloaded
        } finally { root.toFile().deleteRecursively() }
    }

    test("the producer keeps earning while its previous batch travels and every location shares the existing cap") {
        val before = autonomousCargoCity()
        val produced = produceAutonomousCargo(before)
        val plan = allocateAutonomousCargo(produced).values.single()
        val picked = CivsLogistics.pickup(produced, before.workers.last().id, plan.cargo)
        val working = produceAutonomousCargo(picked)
        working.workers.first().pendingStock shouldBe Stock(food = 3)
        working.workers.last().cargo!!.amount shouldBe 3L
        CivsAutonomousLogistics.held(working, Resource.FOOD) shouldBe before.stock.food + 6
        val full = working.copy(stock = working.stock.copy(food = CivsRules.capacity(working) - 6))
        CivsAutonomousLogistics.room(full, Resource.FOOD) shouldBe 0L
        shouldThrow<CivsRejection> { produceAutonomousCargo(full) }.reason shouldBe "storage-full"
        val arrived = CivsLogistics.unload(full, before.workers.last().id, plan.cargo.id, 300_000)
        CivsAutonomousLogistics.room(arrived, Resource.FOOD) shouldBe 0L
        arrived.workers.first().pendingStock shouldBe Stock(food = 3)
        val spent = arrived.copy(stock = arrived.stock.copy(food = arrived.stock.food - 3))
        produceAutonomousCargo(spent).workers.first().pendingStock shouldBe Stock(food = 6)
    }

    test("removed warehouse preserves carried stock and redirects the same shipment to the hall") {
        val before = produceAutonomousCargo(autonomousCargoCity())
        val plan = allocateAutonomousCargo(before).values.single()
        val courier = before.workers.last()
        val picked = CivsLogistics.pickup(before, courier.id, plan.cargo)
        val retired = CivsRules.validate(picked.copy(buildings = picked.buildings.filterNot { it.id == plan.cargo.warehouseId },
            workers = picked.workers.map { if (it.id == courier.id) it.copy(assignment = null) else it }))
        CivsLogistics.materializeCargo(retired) shouldBe retired
        CivsLogistics.destination(retired, plan.cargo)?.type shouldBe BuildingType.TOWN_HALL
        val unloaded = CivsLogistics.unload(retired, courier.id, plan.cargo.id, 200_000)
        unloaded.stock.food shouldBe before.stock.food + 3
        unloaded.workers.last().cargo shouldBe null
    }

    test("loss of a courier falls back to the existing depot trip and settles old pending stock only once") {
        val queued = produceAutonomousCargo(autonomousCargoCity())
        val absent = CivsRules.validate(queued.copy(workers = queued.workers.dropLast(1)))
        val producer = absent.workers.first()
        val plan = requireNotNull(citizenPlan(absent, producer))
        plan.completeAtWorkplace shouldBe false
        plan.pendingStockDelivery shouldBe producer.pendingStock
        val settled = CivsAutonomousLogistics.unloadPending(absent, producer.id, producer.assignment!!,
            producer.completedCycles, producer.nextDeliveryAt, producer.pendingStock!!, 100_001)
        settled.stock.food shouldBe queued.stock.food + 3
        settled.workers.first().pendingStock shouldBe null
        settled.workers.first().skills shouldBe producer.skills
        settled.workers.first().completedCycles shouldBe producer.completedCycles
        produceAutonomousCargo(settled).stock.food shouldBe queued.stock.food + 6
    }

    test("a full queue returns directly and unloads all 384 earned units before its future production schedule") {
        val base = autonomousCargoCity()
        val original = base.workers.first().copy(completedCycles = 12, nextDeliveryAt = 999_999,
            pendingStock = Stock(food = CivsRules.capacity(base)), skills = mapOf("FARM" to 12L))
        val city = CivsRules.validate(base.copy(stock = Stock(), workers = listOf(original)))
        CivsAutonomousLogistics.room(city, Resource.FOOD) shouldBe 0L
        val plan = requireNotNull(citizenPlan(city, original))
        plan.pendingStockDelivery shouldBe Stock(food = 384)
        val cycle = plan.cycle()
        cycle.start()
        cycle.phase shouldBe CitizenPhase.RETURNING
        cycle.beginDelivery(2_000) shouldBe null
        cycle.arrive(2_000)
        (cycle.beginDelivery(2_000) != null) shouldBe true
        cycle.observedWorkMillis shouldBe 0L
        val after = CivsAutonomousLogistics.unloadPending(city, original.id, original.assignment!!,
            original.completedCycles, original.nextDeliveryAt, original.pendingStock!!, 2_000)
        after.stock shouldBe Stock(food = 384)
        after.workers.single() shouldBe original.copy(pendingStock = null, nextDeliveryAt = 1_000_000)
        after.inventory shouldBe null
        shouldThrow<CivsRejection> { CivsAutonomousLogistics.unloadPending(after, original.id, original.assignment!!,
            original.completedCycles, original.nextDeliveryAt, original.pendingStock!!, 2_001) }.reason shouldBe "stale-delivery"
    }

    test("a smaller surviving depot accepts part of a large saved queue and keeps the rest without rewards") {
        val base = autonomousCargoCity()
        val producer = base.workers.first().copy(pendingStock = Stock(food = 300), completedCycles = 20,
            skills = mapOf("FARM" to 20L))
        val city = CivsRules.validate(base.copy(stock = Stock(), workers = listOf(producer),
            buildings = base.buildings.filterNot { it.type == BuildingType.WAREHOUSE }))
        val first = CivsAutonomousLogistics.unloadPending(city, producer.id, producer.assignment!!,
            producer.completedCycles, producer.nextDeliveryAt, producer.pendingStock!!, 2_000)
        first.stock.food shouldBe 128L
        first.workers.single().pendingStock shouldBe Stock(food = 172)
        CivsAutonomousLogistics.held(first, Resource.FOOD) shouldBe 300L
        CivsAutonomousLogistics.pendingRoom(first, first.workers.single().pendingStock!!) shouldBe false
        val spent = first.copy(stock = Stock(food = 64))
        val worker = spent.workers.single()
        val second = CivsAutonomousLogistics.unloadPending(spent, worker.id, worker.assignment!!,
            worker.completedCycles, worker.nextDeliveryAt, worker.pendingStock!!, 2_001)
        second.stock.food shouldBe 128L
        second.workers.single().pendingStock shouldBe Stock(food = 108)
        second.workers.single().completedCycles shouldBe producer.completedCycles
        second.workers.single().skills shouldBe producer.skills
        second.workers.single().items shouldBe producer.items
    }

    test("courier death and resurrection keep closed cargo without native loot or double credit") {
        val produced = produceAutonomousCargo(autonomousCargoCity())
        val plan = allocateAutonomousCargo(produced).values.single()
        val courier = produced.workers.last()
        val picked = CivsLogistics.pickup(produced, courier.id, plan.cargo)
        val death = CivDeathContext(CivPosition(10, 64, 0), 200_000)
        val dead = CivsRules.hurtResident(picked, picked.owner, courier.id, 100.0, death)
        dead.corpses!!.single().worker.cargo shouldBe plan.cargo
        dead.corpses.single().worker.items shouldBe null
        CivsAutonomousLogistics.held(dead, Resource.FOOD) shouldBe CivsAutonomousLogistics.held(picked, Resource.FOOD)
        val restored = CivsRules.resurrect(dead, dead.owner, courier.id, death.at, death.at + 1, administrator = true)
        restored.workers.last().cargo shouldBe plan.cargo
        restored.workers.last().assignment shouldBe null
        val unloaded = CivsLogistics.unload(restored, courier.id, plan.cargo.id, death.at + 2)
        unloaded.stock.food shouldBe produced.stock.food + 3
        unloaded.workers.last().cargo shouldBe null
    }

    test("default staffing fills warehouse before guard and safely recovers a missing courier") {
        val base = autonomousCargoCity()
        val types = listOf(BuildingType.TOWN_HALL, BuildingType.BUILDER_HUT, BuildingType.FORESTER,
            BuildingType.QUARRY, BuildingType.FARM, BuildingType.WAREHOUSE, BuildingType.GUARD_TOWER)
        val buildings = types.mapIndexed { index, type -> autonomousCargoBuilding(type, index * 10) }
        val residents = (0..4).map { CivWorker(UUID(20, it.toLong()), "Worker $it", null, nextDeliveryAt = 1) }
        val city = CivsRules.validate(base.copy(buildings = buildings, workers = residents))
        val assigned = CivsRules.autoAssign(city, city.owner, 1_000)
        assigned.workers.map { assigned.building(it.assignment!!)!!.type } shouldBe listOf(
            BuildingType.BUILDER_HUT, BuildingType.FORESTER, BuildingType.QUARRY, BuildingType.FARM, BuildingType.WAREHOUSE)
        val courier = assigned.workers.last()
        val guard = buildings.first { it.type == BuildingType.GUARD_TOWER }
        val missing = CivsRules.validate(assigned.copy(workers = assigned.workers.map { if (it.id == courier.id) it.copy(assignment = guard.id) else it }))
        val repaired = CivsRules.autoAssign(missing, missing.owner, 200_000) { it == courier.id }
        repaired.workers.last().assignment shouldBe buildings.first { it.type == BuildingType.WAREHOUSE }.id
        repaired.workers.dropLast(1) shouldBe assigned.workers.dropLast(1)
    }

    test("legacy snapshots have no queued goods and malformed internal cargo cannot become native items") {
        val before = autonomousCargoCity()
        val root = Files.createTempDirectory("civs-legacy-autonomous-cargo-")
        try {
            CivsStore(root, Executor { it.run() }).use { it.save(before).join() }
            CivsStore(root, Executor { it.run() }).use { store ->
                store.load().join().single().workers.all { it.pendingStock == null && it.cargo == null } shouldBe true
            }
        } finally { root.toFile().deleteRecursively() }
        val plan = allocateAutonomousCargo(produceAutonomousCargo(before)).values.single()
        CivsLogistics.validCargo(before, plan.cargo.copy(inventoryBacked = true)) shouldBe false
        CivsLogistics.validCargo(before, plan.cargo.copy(material = "DIAMOND")) shouldBe false
        CivsLogistics.validCargo(before, plan.cargo.copy(sourceWorkerId = null)) shouldBe false
        shouldThrow<CivsRejection> { CivsRules.validate(before.copy(workers = before.workers.map {
            it.copy(pendingStock = Stock(food = -1))
        })) }.reason shouldBe "invalid-state"
    }

    test("native forestry keeps its existing net seed cost and exact receipt while earned wood waits for a courier") {
        val base = autonomousCargoCity()
        val forester = base.buildings.last().copy(type = BuildingType.FORESTER)
        val city = CivsRules.validate(base.copy(stock = Stock(20, 0, 20),
            buildings = base.buildings.dropLast(1) + forester))
        val worker = city.workers.first()
        val root = city.center.copy(y = city.center.y + 1)
        val centre = (15 shl 5) or 15
        val job = CivForestryJob(UUID.randomUUID(), root, "OAK_SAPLING", listOf(root),
            listOf("minecraft:oak_log[axis=y]", "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]"),
            listOf((1 shl 16) or (5 shl 10) or centre) + (4 downTo 0).map { (it shl 10) or centre })
        val work = CivWorldWork(worker.id, worker.completedCycles, forester.id, root.copy(x = root.x - 1),
            job.cells.map { code -> CivPlanBlock(job.position(code), job.state(code),
                if (job.position(code) == root) "minecraft:oak_sapling[stage=0]" else "minecraft:air") },
            inputs = mapOf("OAK_SAPLING" to 1L), status = "felling-tree", focus = root,
            forestry = CivForestryProgress(job, job.cells.size, harvestedLogs = 5))
        val after = CivsRules.finishWorldWork(city, work, 100_000)
        after.stock shouldBe city.stock
        after.workers.first().pendingStock shouldBe Stock(wood = 3)
        after.workers.first().skills!!["FORESTER"] shouldBe 1L
        CivForestryReceipt(worker, city.stock, null, true, work, 100_000, city.forestrySeeded).validated(after)
        val plan = allocateAutonomousCargo(after).values.single()
        val picked = CivsLogistics.pickup(after, city.workers.last().id, plan.cargo)
        CivsLogistics.unload(picked, city.workers.last().id, plan.cargo.id, 200_000).stock.wood shouldBe 23L
    }
})

private fun autonomousCargoBuilding(type: BuildingType, x: Int): CivBuilding = CivBuilding(UUID(1, x.toLong()), type,
    if (type == BuildingType.TOWN_HALL) 2 else 1, CivPosition(x, 64, 0), 0,
    "autonomy/${type.name.lowercase()}/1", CivBox(CivPosition(x - 2, 62, -2), CivPosition(x + 2, 68, 2)),
    CivPosition(x, 64, -3), BuildingPhase.ACTIVE, CivPosition(x, 64, 0))

private fun autonomousCargoCity(): Settlement {
    val hall = autonomousCargoBuilding(BuildingType.TOWN_HALL, 0)
    val warehouse = autonomousCargoBuilding(BuildingType.WAREHOUSE, 10)
    val farm = autonomousCargoBuilding(BuildingType.FARM, 20)
    return CivsRules.validate(CivsAutonomy.found(UUID.randomUUID(), UUID.randomUUID(), "Courier village", UUID.randomUUID(),
        CivPosition(0, 64, 0), 128, "medievaloak", hall.copy(level = 1), 1_000).copy(inventory = null, stock = Stock(food = 20),
        buildings = listOf(hall, warehouse, farm), workers = listOf(
            CivWorker(UUID(2, 1), "Farmer", farm.id, nextDeliveryAt = 1),
            CivWorker(UUID(2, 2), "Courier", warehouse.id, nextDeliveryAt = 1))))
}

private fun produceAutonomousCargo(city: Settlement): Settlement {
    val worker = city.workers.first()
    return CivsRules.deliver(city, city.owner, worker.id, requireNotNull(worker.assignment), worker.completedCycles,
        worker.nextDeliveryAt, maxOf(100_000, worker.nextDeliveryAt))
}

private fun allocateAutonomousCargo(city: Settlement): Map<UUID, CivCourierPlan> {
    val sources = city.workers.filter { it.pendingStock != null }.associate { it.id to
        requireNotNull(city.building(requireNotNull(it.assignment))).entrance }
    val snapshot = CivsAutonomousLogistics.snapshot(city, emptyMap(), { null }, sources) { false }
    return allocateCivSupplies(snapshot.detached()).plans
}
