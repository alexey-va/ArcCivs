package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class CivsSupplyDispatcherTest : FunSpec({
    test("construction inputs may use a hall producer's output headroom") {
        val original = supplyFixture()
        val warehouse = original.stores.getValue(SUPPLY_WAREHOUSE).copy(stock = mapOf("COBBLESTONE" to 2L))
        val hall = original.stores.getValue(SUPPLY_TARGET).copy(type = BuildingType.TOWN_HALL,
            outputMaterial = "BOOK", capacity = CivSupplyCapacity(2, emptyMap()))
        val input = original.copy(stores = mapOf(warehouse.id to warehouse, hall.id to hall),
            requests = listOf(CivSupplyRequest(hall.id, "COBBLESTONE", 2, 0, true)),
            couriers = original.couriers.take(1).map { it.copy(bagRoom = mapOf("COBBLESTONE" to 64L)) },
            stackSizes = mapOf("COBBLESTONE" to 64))
        allocateCivSupplies(input).plans.values.single().cargo.amount shouldBe 2L
    }

    test("production headroom admits a needed tool but no new output backfill shipment") {
        val original = supplyFixture()
        val warehouse = original.stores.getValue(SUPPLY_WAREHOUSE).copy(stock = mapOf("OAK_LOG" to 64L, "IRON_AXE" to 1L))
        val forest = original.stores.getValue(SUPPLY_TARGET).copy(type = BuildingType.FORESTER,
            desiredStock = mapOf("IRON_AXE" to 1L), outputMaterial = "OAK_LOG", capacity = CivSupplyCapacity(2, emptyMap()))
        val input = original.copy(stores = mapOf(warehouse.id to warehouse, forest.id to forest),
            requests = listOf(CivSupplyRequest(forest.id, "OAK_LOG", 64, 0), CivSupplyRequest(forest.id, "IRON_AXE", 1, 0)),
            couriers = original.couriers.map { it.copy(bagRoom = mapOf("OAK_LOG" to 64L, "IRON_AXE" to 1L)) },
            stackSizes = mapOf("OAK_LOG" to 64, "IRON_AXE" to 1))
        val plans = allocateCivSupplies(input).plans.values
        plans.size shouldBe 1
        plans.single().cargo.material shouldBe "IRON_AXE"
        plans.single().cargo.amount shouldBe 1L
        plans.single().destinationBuildingId shouldBe forest.id
    }

    test("a saturated producer exports existing output before an ordinary supply trip to recover tool room") {
        val original = constructionCollectionFixture()
        val fullForest = original.stores.getValue(SUPPLY_QUARRY).copy(type = BuildingType.FORESTER,
            stock = mapOf("OAK_LOG" to 27L), desiredStock = mapOf("IRON_AXE" to 1L), outputMaterial = "OAK_LOG")
        val input = original.copy(stores = original.stores + (fullForest.id to fullForest),
            requests = listOf(CivSupplyRequest(SUPPLY_TAVERN, "BREAD", 4, 0), CivSupplyRequest(fullForest.id, "IRON_AXE", 1, 0)),
            couriers = original.couriers.map { it.copy(bagRoom = mapOf("OAK_LOG" to 64L, "IRON_AXE" to 1L, "BREAD" to 64L)) },
            stackSizes = original.stackSizes + mapOf("OAK_LOG" to 64, "IRON_AXE" to 1))
        val selected = allocateCivSupplies(input).plans.values.single()
        selected.cargo.material shouldBe "OAK_LOG"
        selected.cargo.sourceBuildingId shouldBe fullForest.id
        selected.destinationBuildingId shouldBe SUPPLY_WAREHOUSE
        selected.cargo.amount shouldBe 27L

        val olderMeal = CivSupplyTask(null, null, SUPPLY_TAVERN, "BREAD", false)
        val newerRecovery = CivSupplyTask(fullForest.id, null, SUPPLY_WAREHOUSE, "OAK_LOG", false)
        allocateCivSupplies(input, ages = mapOf(olderMeal to 0L, newerRecovery to 10L), now = 20L)
            .plans.values.single().cargo.material shouldBe "BREAD"

        val noDestinationRoom = input.copy(stores = input.stores + (SUPPLY_WAREHOUSE to
            input.stores.getValue(SUPPLY_WAREHOUSE).copy(capacity = CivSupplyCapacity(0, emptyMap()))))
        allocateCivSupplies(noDestinationRoom).plans.values.any { it.cargo.sourceBuildingId == fullForest.id } shouldBe false
        fullForest.stock shouldBe mapOf("OAK_LOG" to 27L)
    }

    test("construction shortage collects its missing quarry goods before ordinary tavern supply") {
        val input = constructionCollectionFixture()
        val collection = allocateCivSupplies(input).plans.values.single()
        collection.cargo.material shouldBe "COBBLESTONE"
        collection.cargo.amount shouldBe 2L
        collection.cargo.sourceBuildingId shouldBe SUPPLY_QUARRY
        collection.destinationBuildingId shouldBe SUPPLY_WAREHOUSE

        val warehouse = input.stores.getValue(SUPPLY_WAREHOUSE)
        val unloaded = input.copy(stores = input.stores + (SUPPLY_WAREHOUSE to warehouse.copy(
            stock = warehouse.stock + ("COBBLESTONE" to collection.cargo.amount))),
            couriers = input.couriers.map { it.copy(cycle = it.cycle + 1) })
        val delivery = allocateCivSupplies(unloaded).plans.values.single()
        delivery.cargo.material shouldBe "COBBLESTONE"
        delivery.cargo.amount shouldBe 2L
        delivery.cargo.sourceBuildingId shouldBe SUPPLY_WAREHOUSE
        delivery.destinationBuildingId shouldBe SUPPLY_TARGET
    }

    test("priority collection is bounded by construction stock and incoming goods") {
        val fixture = constructionCollectionFixture()
        // Isolate construction demand from the independent recovery of a saturated producer.
        val original = fixture.copy(stores = fixture.stores + (SUPPLY_QUARRY to
            fixture.stores.getValue(SUPPLY_QUARRY).copy(capacity = CivSupplyCapacity(3, emptyMap()))))
        val input = original.copy(couriers = original.couriers + original.couriers.single().copy(workerId = SUPPLY_SECOND))
        val plans = allocateCivSupplies(input).plans
        plans.values.filter { it.cargo.material == "COBBLESTONE" }.sumOf { it.cargo.amount } shouldBe 2L
        plans.values.single { it.cargo.material == "BREAD" }.cargo.amount shouldBe 4L

        val picked = plans.getValue(SUPPLY_COURIER).let { it.copy(carrying = true,
            cargo = it.cargo.copy(inventoryBacked = true)) }
        val carrying = input.copy(couriers = input.couriers.filterNot { it.workerId == SUPPLY_COURIER },
            retained = mapOf(SUPPLY_COURIER to picked))
        allocateCivSupplies(carrying).plans.getValue(SUPPLY_SECOND).cargo.material shouldBe "BREAD"

        val supplied = input.copy(requests = input.requests.map { if (it.construction) it.copy(inTransit = 2) else it })
        allocateCivSupplies(supplied).plans.getValue(SUPPLY_COURIER).cargo.material shouldBe "BREAD"
    }

    test("busy couriers retain collection age while vanished source excess is pruned") {
        val original = constructionCollectionFixture()
        val nearby = original.stores.getValue(SUPPLY_TAVERN).copy(stock = mapOf("BOOK" to 8L), outputMaterial = "BOOK")
        val input = original.copy(requests = emptyList(), stores = original.stores + (SUPPLY_TAVERN to nearby),
            couriers = original.couriers.map { it.copy(bagRoom = it.bagRoom + ("BOOK" to 64L)) },
            stackSizes = original.stackSizes + ("BOOK" to 64))
        val distantTask = CivSupplyTask(SUPPLY_QUARRY, null, SUPPLY_WAREHOUSE, "COBBLESTONE", false)
        val nearbyTask = CivSupplyTask(SUPPLY_TAVERN, null, SUPPLY_WAREHOUSE, "BOOK", false)
        val ages = mapOf(distantTask to 0L, nearbyTask to 100L)
        val carrying = allocateCivSupplies(input).plans.values.single().let { it.copy(carrying = true,
            cargo = it.cargo.copy(inventoryBacked = true)) }
        val busy = input.copy(couriers = emptyList(), retained = mapOf(SUPPLY_COURIER to carrying))
        val seen = allocateCivSupplies(busy, ages, now = 200).seen
        (distantTask in seen) shouldBe true
        val retainedAges = ages.filterKeys { it in seen }
        allocateCivSupplies(input, retainedAges, now = 300).plans.values.single().cargo.sourceBuildingId shouldBe SUPPLY_QUARRY

        val exhausted = busy.copy(stores = busy.stores + (SUPPLY_QUARRY to busy.stores.getValue(SUPPLY_QUARRY)
            .copy(stock = mapOf("COBBLESTONE" to 32L))))
        (distantTask in allocateCivSupplies(exhausted, ages, now = 400).seen) shouldBe false
    }

    test("workshop incidental excess leaves while ingredients and destination room remain reserved") {
        val original = supplyFixture()
        val workshop = original.stores.getValue(SUPPLY_TARGET).copy(
            stock = mapOf("DIRT" to 96L, "OAK_LOG" to 8L),
            desiredStock = mapOf("OAK_LOG" to 8L), outputMaterial = "OAK_PLANKS")
        val input = original.copy(requests = emptyList(), stores = original.stores + (SUPPLY_TARGET to workshop),
            couriers = original.couriers.map { it.copy(bagRoom = mapOf("DIRT" to 64L, "OAK_LOG" to 64L)) },
            stackSizes = original.stackSizes + mapOf("DIRT" to 64, "OAK_LOG" to 64))
        val plans = allocateCivSupplies(input).plans
        plans.size shouldBe 2
        plans.values.map { it.cargo.material }.toSet() shouldBe setOf("DIRT")
        plans.values.sumOf { it.cargo.amount } shouldBe 96L
        allocateCivSupplies(input.copy(retained = plans)).plans shouldBe plans

        val warehouse = input.stores.getValue(SUPPLY_WAREHOUSE)
        val full = input.copy(stores = input.stores + (SUPPLY_WAREHOUSE to warehouse.copy(
            capacity = CivSupplyCapacity(0, emptyMap()))))
        allocateCivSupplies(full).plans shouldBe emptyMap()
        val partial = full.copy(stores = full.stores + (SUPPLY_WAREHOUSE to warehouse.copy(
            capacity = CivSupplyCapacity(0, mapOf("DIRT" to 5L)))))
        allocateCivSupplies(partial).plans.values.sumOf { it.cargo.amount } shouldBe 5L

        val personalStore = input.copy(stores = input.stores + (SUPPLY_TARGET to workshop.copy(outputMaterial = null)))
        allocateCivSupplies(personalStore).plans shouldBe emptyMap()
    }

    test("different materials cannot independently reserve the same empty destination slot") {
        val input = supplyFixture().let { it.copy(
            requests = listOf(CivSupplyRequest(SUPPLY_TARGET, "IRON_INGOT", 64, 0), CivSupplyRequest(SUPPLY_TARGET, "STICK", 64, 0)),
            stores = it.stores + (SUPPLY_TARGET to it.stores.getValue(SUPPLY_TARGET).copy(capacity = CivSupplyCapacity(1, emptyMap())))) }
        val result = allocateCivSupplies(input)
        result.plans.size shouldBe 1
        result.plans.values.sumOf { it.cargo.amount } shouldBe 64L

        val withPartial = input.copy(requests = listOf(CivSupplyRequest(SUPPLY_TARGET, "IRON_INGOT", 16, 0),
            CivSupplyRequest(SUPPLY_TARGET, "STICK", 64, 0)), stores = input.stores +
            (SUPPLY_TARGET to input.stores.getValue(SUPPLY_TARGET).copy(capacity = CivSupplyCapacity(1, mapOf("IRON_INGOT" to 16)))))
        allocateCivSupplies(withPartial).plans.values.associate { it.cargo.material to it.cargo.amount } shouldBe
            mapOf("IRON_INGOT" to 16L, "STICK" to 64L)
    }

    test("two partial shipments share their material stack but never exceed source stock or demand") {
        val input = supplyFixture().let { it.copy(
            stores = it.stores + (SUPPLY_TARGET to it.stores.getValue(SUPPLY_TARGET).copy(capacity = CivSupplyCapacity(1, emptyMap()))),
            couriers = it.couriers.map { courier -> courier.copy(bagRoom = mapOf("IRON_INGOT" to 32L)) }) }
        val plans = allocateCivSupplies(input).plans
        plans.size shouldBe 2
        plans.values.map { it.cargo.amount } shouldBe listOf(32L, 32L)
        val scarce = input.copy(stores = input.stores + (SUPPLY_WAREHOUSE to input.stores.getValue(SUPPLY_WAREHOUSE)
            .copy(stock = mapOf("IRON_INGOT" to 40L))))
        allocateCivSupplies(scarce).plans.values.sumOf { it.cargo.amount } shouldBe 40L
        val smallDemand = input.copy(requests = listOf(CivSupplyRequest(SUPPLY_TARGET, "IRON_INGOT", 35, 0)))
        allocateCivSupplies(smallDemand).plans.values.sumOf { it.cargo.amount } shouldBe 35L
    }

    test("virtual destination room is one shared unit allowance across materials") {
        val input = supplyFixture().let { it.copy(
            requests = listOf(CivSupplyRequest(SUPPLY_TARGET, "IRON_INGOT", 32, 0), CivSupplyRequest(SUPPLY_TARGET, "STICK", 32, 0)),
            stores = it.stores + (SUPPLY_TARGET to it.stores.getValue(SUPPLY_TARGET)
                .copy(capacity = CivSupplyCapacity(0, emptyMap(), 40)))) }
        val plans = allocateCivSupplies(input).plans.values
        plans.size shouldBe 2
        plans.sumOf { it.cargo.amount } shouldBe 40L
    }

    test("retained trips survive recomputation and an invalid combined reservation is not silently shrunk") {
        val input = supplyFixture().copy(requests = listOf(CivSupplyRequest(SUPPLY_TARGET, "IRON_INGOT", 128, 0)))
        val original = allocateCivSupplies(input).plans
        original.size shouldBe 2
        val retained = input.copy(retained = original)
        allocateCivSupplies(retained).plans shouldBe original
        val changed = retained.copy(stores = retained.stores + (SUPPLY_WAREHOUSE to retained.stores.getValue(SUPPLY_WAREHOUSE)
            .copy(stock = mapOf("IRON_INGOT" to 70L))))
        val reduced = allocateCivSupplies(changed).plans
        reduced.size shouldBe 1
        reduced.values.single() shouldBe original.getValue(SUPPLY_COURIER)
        reduced.values.single().cargo.amount shouldBe 64L
    }

    test("carried cargo consumes destination capacity and in-transit demand exactly once even if now full") {
        val input = supplyFixture()
        val picked = allocateCivSupplies(input).plans.getValue(SUPPLY_COURIER).let {
            it.copy(carrying = true, cargo = it.cargo.copy(inventoryBacked = true)) }
        val carrying = input.copy(retained = mapOf(SUPPLY_COURIER to picked),
            couriers = input.couriers.filterNot { it.workerId == SUPPLY_COURIER },
            requests = listOf(CivSupplyRequest(SUPPLY_TARGET, "IRON_INGOT", 128, 64)))
        val result = allocateCivSupplies(carrying)
        result.plans.size shouldBe 2
        result.plans.getValue(SUPPLY_COURIER) shouldBe picked
        result.plans.values.filterNot { it.carrying }.sumOf { it.cargo.amount } shouldBe 64L
        val full = carrying.copy(stores = carrying.stores + (SUPPLY_TARGET to carrying.stores.getValue(SUPPLY_TARGET)
            .copy(capacity = CivSupplyCapacity(0, emptyMap()))))
        allocateCivSupplies(full).plans shouldBe mapOf(SUPPLY_COURIER to picked)
    }

    test("ordinary worker bags cannot be offered even when two couriers are free") {
        val input = supplyFixture().copy(requests = emptyList(),
            workerSources = listOf(CivSupplyWorkerSource(UUID(0, 300), SUPPLY_TARGET, CivPosition(80, 64, 0),
                mapOf("IRON_INGOT" to 32L, "STICK" to 32L))))
        allocateCivSupplies(input).plans shouldBe emptyMap()
    }

    test("building stock uses its entrance and remains offered independently of its producer actor") {
        val base = supplyFixture()
        val building = base.stores.getValue(SUPPLY_TARGET).copy(outputMaterial = "IRON_INGOT",
            stock = mapOf("IRON_INGOT" to 32L, "STICK" to 32L))
        val producer = CivSupplyWorkerSource(UUID(0, 300), SUPPLY_TARGET, CivPosition(80, 64, 0), mapOf("IRON_INGOT" to 64L))
        val input = base.copy(stores = base.stores + (building.id to building), requests = emptyList(), workerSources = listOf(producer))
        val plans = allocateCivSupplies(input).plans
        plans.size shouldBe 2
        plans.values.forEach {
            it.cargo.sourceWorkerId shouldBe null
            it.cargo.sourceBuildingId shouldBe building.id
            it.cargo.sourceEntrance shouldBe building.entrance
            it.cargo.amount shouldBe 32L
        }
        allocateCivSupplies(input.copy(retained = plans, workerSources = emptyList())).plans shouldBe plans
        allocateCivSupplies(input.copy(retained = plans, workerSources = listOf(producer.copy(meeting = CivPosition(-80, 64, 0))))).plans shouldBe plans
    }

    test("construction keeps priority while age and courier waiting order prevent ordinary first-match bias") {
        val input = supplyFixture()
        val far = UUID(0, 30)
        val stores = input.stores + (far to input.stores.getValue(SUPPLY_TARGET).copy(id = far,
            entrance = CivPosition(20, 64, 0), control = CivPosition(20, 64, 0)))
        val requests = listOf(CivSupplyRequest(SUPPLY_TARGET, "IRON_INGOT", 64, 0), CivSupplyRequest(far, "IRON_INGOT", 64, 0))
        val scarce = input.copy(stores = stores + (SUPPLY_WAREHOUSE to stores.getValue(SUPPLY_WAREHOUSE)
            .copy(stock = mapOf("IRON_INGOT" to 64))), requests = requests)
        val aged = mapOf(CivSupplyTask(null, null, far, "IRON_INGOT", false) to 0L)
        val fair = allocateCivSupplies(scarce, aged, mapOf(SUPPLY_COURIER to 100L), now = 200)
        fair.plans.keys shouldBe setOf(SUPPLY_SECOND)
        fair.plans.values.single().destinationBuildingId shouldBe far
        val construction = scarce.copy(requests = listOf(requests[0].copy(construction = true), requests[1]),
            constructionTargets = mapOf(SUPPLY_WAREHOUSE to SUPPLY_TARGET))
        allocateCivSupplies(construction, aged, now = 200).plans.values.single().destinationBuildingId shouldBe SUPPLY_TARGET
    }

    test("explicit updates coalesce promptly and cached lookups do not repeat capture") {
        MockBukkitTestRuntime.open().use { paper ->
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("SupplyCoalescing")))
            val work = BudgetedTaskQueue(tasks, maximumSteps = 1, nanoTime = { 0L })
            var tick = 0L
            var now = 0L
            work.drainAvailable { tick }
            var captures = 0
            var valid = true
            val dispatcher = CivsSupplyDispatcher(tasks, work, { _, retained ->
                captures++
                { supplyFixture().copy(retained = retained) }
            }, { _, _ -> valid }, Executor(Runnable::run), { now })
            fun pulse() { tick++; paper.performTicks(1); dispatcher.pulse() }
            try {
                dispatcher.request(SUPPLY_CITY)
                pulse()
                captures shouldBe 1
                val first = dispatcher.plan(SUPPLY_CITY, SUPPLY_COURIER)
                (first != null) shouldBe true
                repeat(20) { dispatcher.plan(SUPPLY_CITY, SUPPLY_COURIER); pulse() }
                captures shouldBe 1
                repeat(20) { dispatcher.request(SUPPLY_CITY) }
                now = 249; pulse(); captures shouldBe 1
                now = 250; pulse(); captures shouldBe 2
                dispatcher.plan(SUPPLY_CITY, SUPPLY_COURIER) shouldBe first
                valid = false
                dispatcher.request(SUPPLY_CITY)
                now = 500; pulse()
                dispatcher.failure(SUPPLY_CITY) shouldBe "stale-input"
                dispatcher.plan(SUPPLY_CITY, SUPPLY_COURIER) shouldBe first
                valid = true
                now = 750; pulse()
                dispatcher.failure(SUPPLY_CITY) shouldBe null
                captures shouldBe 4
            } finally { dispatcher.close(); work.close(); tasks.close() }
        }
    }

    test("the forty-ninth city is admitted when an earlier job completes rather than held by cached plans") {
        MockBukkitTestRuntime.open().use { paper ->
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("SupplyAdmission")))
            val work = BudgetedTaskQueue(tasks, maximumSteps = 1, nanoTime = { 0L })
            var tick = 0L
            work.drainAvailable { tick }
            val admitted = linkedSetOf<UUID>()
            val dispatcher = CivsSupplyDispatcher(tasks, work, { id, _ ->
                admitted += id
                { supplyFixture().copy(cityId = id) }
            }, { _, _ -> true }, Executor(Runnable::run), { 0L })
            val ids = (1..49).map { UUID(1, it.toLong()) }
            try {
                ids.forEach { dispatcher.request(it) }
                dispatcher.pulse()
                admitted.size shouldBe 48
                tick++; paper.performTicks(1); dispatcher.pulse()
                admitted.size shouldBe 49
                while (ids.any { dispatcher.plan(it, SUPPLY_COURIER) == null } && tick < 100) {
                    tick++; paper.performTicks(1); dispatcher.pulse()
                }
                ids.all { dispatcher.plan(it, SUPPLY_COURIER) != null } shouldBe true
            } finally { dispatcher.close(); work.close(); tasks.close() }
        }
    }

    test("busy capture steps release all admission slots and retry only after the short cooldown") {
        MockBukkitTestRuntime.open().use { paper ->
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("SupplyBusyAdmission")))
            val work = BudgetedTaskQueue(tasks, maximumSteps = 64, nanoTime = { 0L })
            var tick = 0L
            var now = 0L
            var busy = true
            val ids = (1..49).map { UUID(2, it.toLong()) }
            val busyIds = ids.take(48).toSet()
            val attempts = mutableMapOf<UUID, Int>()
            work.drainAvailable { tick }
            val dispatcher = CivsSupplyDispatcher(tasks, work, { id, _ ->
                attempts[id] = (attempts[id] ?: 0) + 1
                val step: () -> CivSupplySnapshot? = {
                    if (busy && id in busyIds) throw CivsRejection("city-busy")
                    supplyFixture().copy(cityId = id)
                }
                step
            }, { _, _ -> true }, Executor(Runnable::run), { now })
            fun pulse() { tick++; paper.performTicks(1); dispatcher.pulse() }
            try {
                ids.forEach { dispatcher.request(it) }
                pulse()
                attempts.size shouldBe 48
                busyIds.all { dispatcher.failure(it) == "city-busy" } shouldBe true
                pulse()
                dispatcher.plan(ids.last(), SUPPLY_COURIER)?.cargo?.amount shouldBe 64L
                attempts[ids.last()] shouldBe 1
                now = 249; pulse()
                busyIds.all { attempts[it] == 1 && dispatcher.failure(it) == "city-busy" } shouldBe true
                busy = false
                now = 250; pulse()
                busyIds.all { attempts[it] == 2 && dispatcher.failure(it) == null } shouldBe true
                ids.all { dispatcher.plan(it, SUPPLY_COURIER) != null } shouldBe true
            } finally { dispatcher.close(); work.close(); tasks.close() }
        }
    }

    test("real worker receives detached data and a removed city's completed result cannot apply") {
        MockBukkitTestRuntime.open().use { paper ->
            val owner = Thread.currentThread()
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("SupplyThreads")))
            val work = BudgetedTaskQueue(tasks, nanoTime = { 0L })
            var tick = 0L
            work.drainAvailable { tick }
            val worker = BoundedAsyncExecutor("SupplyTest", 1, 48, false)
            val pending = ArrayDeque<Runnable>()
            val executor = Executor { pending += it }
            val key = Any()
            var validations = 0
            val capturedStock = mutableMapOf("IRON_INGOT" to 64L)
            val input = supplyFixture().let { it.copy(inputKey = key,
                stores = it.stores + (SUPPLY_WAREHOUSE to it.stores.getValue(SUPPLY_WAREHOUSE).copy(stock = capturedStock))) }
            val dispatcher = CivsSupplyDispatcher(tasks, work, { _, _ ->
                check(Thread.currentThread() === owner)
                val step: () -> CivSupplySnapshot? = { check(Thread.currentThread() === owner); input }
                step
            }, { snapshot, _ ->
                check(Thread.currentThread() === owner)
                (snapshot.inputKey === key) shouldBe true
                validations++; true
            }, executor, { 0L })
            fun runWorker() = worker.submit {
                check(Thread.currentThread() !== owner)
                pending.removeFirst().run()
            }.get(5, TimeUnit.SECONDS)
            try {
                dispatcher.request(SUPPLY_CITY); dispatcher.pulse()
                capturedStock["IRON_INGOT"] = 0
                runWorker()
                dispatcher.remove(SUPPLY_CITY)
                dispatcher.pulse()
                validations shouldBe 0
                dispatcher.plan(SUPPLY_CITY, SUPPLY_COURIER) shouldBe null
                capturedStock["IRON_INGOT"] = 64
                dispatcher.request(SUPPLY_CITY)
                tick++; paper.performTicks(1); dispatcher.pulse()
                capturedStock["IRON_INGOT"] = 0
                runWorker(); dispatcher.pulse()
                validations shouldBe 1
                dispatcher.plan(SUPPLY_CITY, SUPPLY_COURIER)?.cargo?.amount shouldBe 64L
            } finally {
                dispatcher.close(); work.close(); tasks.close()
                worker.closeAsync().get(5, TimeUnit.SECONDS)
            }
        }
    }

    test("reload clears accepted plans and fences old computation before the same city is requested again") {
        MockBukkitTestRuntime.open().use { paper ->
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("SupplyReload")))
            val work = BudgetedTaskQueue(tasks, nanoTime = { 0L })
            var tick = 0L
            var now = 0L
            var stock = 64L
            var validations = 0
            val pending = ArrayDeque<Runnable>()
            work.drainAvailable { tick }
            val dispatcher = CivsSupplyDispatcher(tasks, work, { _, _ ->
                val input = supplyFixture().let { it.copy(couriers = it.couriers.take(1),
                    stores = it.stores + (SUPPLY_WAREHOUSE to it.stores.getValue(SUPPLY_WAREHOUSE)
                        .copy(stock = mapOf("IRON_INGOT" to stock)))) }
                val step: () -> CivSupplySnapshot? = { input }
                step
            }, { _, _ -> validations++; true }, Executor { pending += it }, { now })
            fun pulse() { tick++; paper.performTicks(1); dispatcher.pulse() }
            try {
                dispatcher.request(SUPPLY_CITY); pulse()
                pending.removeFirst().run(); pulse()
                dispatcher.plan(SUPPLY_CITY, SUPPLY_COURIER)?.cargo?.amount shouldBe 64L
                validations shouldBe 1
                now = 250
                dispatcher.request(SUPPLY_CITY); pulse()
                pending.size shouldBe 1
                dispatcher.clear()
                dispatcher.cityIds() shouldBe emptySet()
                dispatcher.retained(SUPPLY_CITY) shouldBe emptyMap()
                stock = 7
                dispatcher.request(SUPPLY_CITY); pulse()
                dispatcher.cityIds() shouldBe setOf(SUPPLY_CITY)
                pending.size shouldBe 2
                pending.removeFirst().run(); pulse()
                validations shouldBe 1
                dispatcher.plan(SUPPLY_CITY, SUPPLY_COURIER) shouldBe null
                pending.removeFirst().run(); pulse()
                validations shouldBe 2
                dispatcher.plan(SUPPLY_CITY, SUPPLY_COURIER)?.cargo?.amount shouldBe 7L
            } finally { dispatcher.close(); work.close(); tasks.close() }
        }
    }

    test("executor overload backs off without an owner-thread fallback and retries when the deadline expires") {
        MockBukkitTestRuntime.open().use { paper ->
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("SupplyBackoff")))
            val work = BudgetedTaskQueue(tasks, maximumSteps = 1, nanoTime = { 0L })
            var tick = 0L
            var now = 0L
            var captures = 0
            var reject = true
            work.drainAvailable { tick }
            val executor = Executor { command ->
                if (reject) throw RejectedExecutionException("test saturation")
                command.run()
            }
            val dispatcher = CivsSupplyDispatcher(tasks, work, { _, _ ->
                captures++
                val step: () -> CivSupplySnapshot? = { supplyFixture() }
                step
            }, { _, _ -> true }, executor, { now })
            fun pulse() { tick++; paper.performTicks(1); dispatcher.pulse() }
            try {
                dispatcher.request(SUPPLY_CITY); pulse()
                captures shouldBe 1
                dispatcher.failure(SUPPLY_CITY) shouldBe "planner-busy"
                dispatcher.plan(SUPPLY_CITY, SUPPLY_COURIER) shouldBe null
                reject = false
                now = 4_999
                dispatcher.request(SUPPLY_CITY); pulse()
                captures shouldBe 1
                dispatcher.plan(SUPPLY_CITY, SUPPLY_COURIER) shouldBe null
                now = 5_000; pulse()
                captures shouldBe 2
                dispatcher.failure(SUPPLY_CITY) shouldBe null
                dispatcher.plan(SUPPLY_CITY, SUPPLY_COURIER)?.cargo?.amount shouldBe 64L
            } finally { dispatcher.close(); work.close(); tasks.close() }
        }
    }
})

private val SUPPLY_CITY = UUID(0, 1)
private val SUPPLY_WAREHOUSE = UUID(0, 10)
private val SUPPLY_TARGET = UUID(0, 20)
private val SUPPLY_QUARRY = UUID(0, 30)
private val SUPPLY_TAVERN = UUID(0, 40)
private val SUPPLY_COURIER = UUID(0, 100)
private val SUPPLY_SECOND = UUID(0, 101)

private fun constructionCollectionFixture(): CivSupplySnapshot {
    val warehouse = CivSupplyStore(SUPPLY_WAREHOUSE, CivPosition(-1896, 64, -2774), CivPosition(-1905, 65, -2775),
        mapOf("BREAD" to 4L), emptyMap(), null, CivSupplyCapacity(314, emptyMap()), BuildingType.WAREHOUSE)
    val builder = CivSupplyStore(SUPPLY_TARGET, CivPosition(-1896, 64, -2754), CivPosition(-1900, 65, -2753),
        emptyMap(), emptyMap(), null, CivSupplyCapacity(27, emptyMap()), BuildingType.BUILDER_HUT)
    val quarry = CivSupplyStore(SUPPLY_QUARRY, CivPosition(-1844, 71, -2696), CivPosition(-1844, 71, -2689),
        mapOf("COBBLESTONE" to 1472L), mapOf("COBBLESTONE" to 32L), "COBBLESTONE",
        CivSupplyCapacity(0, emptyMap()), BuildingType.QUARRY)
    val tavern = CivSupplyStore(SUPPLY_TAVERN, CivPosition(-1850, 63, -2763), CivPosition(-1848, 63, -2773),
        emptyMap(), mapOf("BREAD" to 4L), null, CivSupplyCapacity(27, emptyMap()), BuildingType.TAVERN)
    return CivSupplySnapshot(SUPPLY_CITY, Unit, listOf(warehouse, builder, quarry, tavern).associateBy { it.id },
        listOf(CivSupplyRequest(builder.id, "COBBLESTONE", 2, 0, construction = true),
            CivSupplyRequest(tavern.id, "BREAD", 4, 0)),
        listOf(CivSupplyCourier(SUPPLY_COURIER, warehouse.id, 0, warehouse.control,
            mapOf("COBBLESTONE" to 64L, "BREAD" to 64L))),
        emptyList(), emptyMap(), mapOf("COBBLESTONE" to 64, "BREAD" to 64),
        constructionTargets = mapOf(warehouse.id to builder.id), maximumTransferDistance = 256)
}

private fun supplyFixture(): CivSupplySnapshot {
    val warehouse = CivSupplyStore(SUPPLY_WAREHOUSE, CivPosition(0, 64, 0), CivPosition(0, 64, 0),
        mapOf("IRON_INGOT" to 128L, "STICK" to 128L), emptyMap(), null, CivSupplyCapacity(4, emptyMap()))
    val target = CivSupplyStore(SUPPLY_TARGET, CivPosition(5, 64, 0), CivPosition(5, 64, 0),
        emptyMap(), emptyMap(), null, CivSupplyCapacity(4, emptyMap()))
    return CivSupplySnapshot(SUPPLY_CITY, Unit, mapOf(warehouse.id to warehouse, target.id to target),
        listOf(CivSupplyRequest(target.id, "IRON_INGOT", 64, 0)), listOf(SUPPLY_COURIER, SUPPLY_SECOND).map {
            CivSupplyCourier(it, warehouse.id, 0, warehouse.control, mapOf("IRON_INGOT" to 64L, "STICK" to 64L)) },
        emptyList(), emptyMap(), mapOf("IRON_INGOT" to 64, "STICK" to 64))
}
