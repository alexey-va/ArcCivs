package ru.arc.civs

import com.google.gson.Gson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import java.util.UUID

class CivsForestrySurplusTest : FunSpec({
    test("the family reserve stops at 512 and resumes at 256 while partial observations preserve its latch") {
        fun policy(count: Long, paused: Boolean = false, complete: Boolean = true) =
            CivsForestrySurplus.decide(mapOf("OAK_LOG" to count), emptyMap(), paused, complete)
        policy(511).paused shouldBe false
        policy(512).paused shouldBe true
        policy(300, true).paused shouldBe true
        policy(256, true).paused shouldBe false
        policy(600, false, complete = false).paused shouldBe false
        policy(0, true, complete = false).paused shouldBe true
        CivsForestrySurplus.decide(mapOf("STRIPPED_OAK_LOG" to 256L, "SPRUCE_WOOD" to 256L), emptyMap(), false, true).paused shouldBe true
    }

    test("the shared city review orders only the missing species after exhausting real intermediates") {
        val city = surplusDemand(surplusCity(), mapOf("SPRUCE_STAIRS" to 4L))
        val stocked = mapOf("OAK_LOG" to 512L)
        val selected = city.review(stocked)
        selected.timber?.neededUnits shouldBe mapOf("SPRUCE_LOG" to 2L)
        val order = selected.orders.getValue(city.workers.single().id)
        order.allows("SPRUCE_LOG") shouldBe true
        order.allows("OAK_LOG") shouldBe false
        city.review(stocked + ("SPRUCE_PLANKS" to 6L)).orders shouldBe emptyMap()
        city.review(stocked + ("SPRUCE_WOOD" to 2L)).orders shouldBe emptyMap()
    }

    test("a pinned workshop batch covers its promised stairs and requests only its real missing plank inputs") {
        val base = surplusCity()
        val mill = base.buildings.last().copy(id = UUID(0, 8), type = BuildingType.SAWMILL)
        val carpenter = base.workers.single().copy(id = UUID(0, 9), assignment = mill.id)
        val city = surplusDemand(base.copy(buildings = base.buildings + mill, workers = base.workers + carpenter),
            mapOf("SPRUCE_STAIRS" to 4L))
        val recipe = CivsCarpentry.recipes.single { it.id == "spruce_stairs" }
        val job = CivCraftJob(UUID.randomUUID(), carpenter.id, mill.id, carpenter.completedCycles, recipe.id,
            CivsCarpentry.VERSION, recipe.material, recipe.amount, recipe.inputs, recipe.material, city.buildings.first().id)
        fun need(stock: Map<String, Long>, batch: CivCraftJob): Map<String, Long> {
            val snapshot = surplusSnapshot(city, stock).copy(craftJobs = mapOf(carpenter.id to batch))
            return requireNotNull(CivsProductionOrders.allocate(CivsProductionOrders.survey(city, snapshot, SURPLUS_NOW),
                snapshot, emptyMap(), CivProductionBoard(), SURPLUS_NOW).timber).neededUnits
        }
        need(mapOf("OAK_LOG" to 512L, "SPRUCE_PLANKS" to 6L), job) shouldBe emptyMap()
        need(mapOf("OAK_LOG" to 512L), job) shouldBe mapOf("SPRUCE_LOG" to 2L)
        need(mapOf("OAK_LOG" to 512L, "SPRUCE_STAIRS" to 4L), job.copy(cycle = 1)) shouldBe emptyMap()
    }

    test("only the matching supported species is ordered for an unsupported carpentry recipe") {
        for ((material, species) in listOf("CHERRY_PLANKS" to "CHERRY", "MANGROVE_STAIRS" to "MANGROVE",
            "PALE_OAK_PLANKS" to "PALE_OAK", "CRIMSON_PLANKS" to "CRIMSON", "WARPED_PLANKS" to "WARPED",
            "SPRUCE_HANGING_SIGN" to "SPRUCE")) {
            val city = surplusDemand(surplusCity(), mapOf(material to 4L))
            val decision = city.review(mapOf("OAK_LOG" to 600L))
            decision.timber?.uncertainSpecies shouldBe setOf(species)
            decision.orders.values.map { it.material } shouldBe if (species == "SPRUCE") listOf("SPRUCE_LOG") else emptyList()
            city.review(mapOf("OAK_LOG" to 600L, material to 4L)).orders shouldBe emptyMap()
        }
    }

    test("one short species request is shared and an existing tree reservation survives a delivery") {
        val base = surplusDemand(surplusCity(), mapOf("SPRUCE_LOG" to 1L))
        val worker = base.workers.single()
        val city = base.copy(workers = listOf(worker, worker.copy(id = UUID(0, 8), name = "Second")))
        val first = city.review(mapOf("OAK_LOG" to 512L))
        first.orders.keys shouldBe setOf(worker.id)
        val full = mapOf("OAK_LOG" to 512L, "SPRUCE_LOG" to 64L)
        city.review(full, first).orders shouldBe first.orders
        val finished = city.copy(workers = listOf(worker.copy(completedCycles = 1L), city.workers.last()))
        finished.review(full, first).orders shouldBe emptyMap()
        finished.review(full).orders shouldBe emptyMap()
    }

    test("a local timber shortage permits another tree without paying an outstanding spruce bill with oak") {
        val city = surplusDemand(surplusCity(), mapOf("SPRUCE_LOG" to 1L))
        val worker = city.workers.single()
        val full = mapOf("OAK_LOG" to 512L)
        val strict = city.review(full)
        val spruce = strict.orders.getValue(worker.id)
        spruce.allows("OAK_LOG") shouldBe false
        val hungry = city.review(mapOf("OAK_LOG" to 256L), strict)
        hungry.orders.getValue(worker.id) shouldBe spruce.copy(anyTimber = true)
        val finished = city.copy(workers = listOf(worker.copy(completedCycles = 1)))
        val stillOwes = finished.review(full, hungry)
        stillOwes.timber?.neededUnits shouldBe mapOf("SPRUCE_LOG" to 1L)
        stillOwes.orders.getValue(worker.id).allows("OAK_LOG") shouldBe false

        val other = worker.copy(id = UUID(0, 8), name = "Second")
        val nearlyFull = city.copy(workers = listOf(worker, other)).review(mapOf("OAK_LOG" to 511L))
        nearlyFull.orders.getValue(worker.id).anyTimber shouldBe true
        nearlyFull.orders.getValue(other.id).material shouldBe "SPRUCE_LOG"
        nearlyFull.orders.getValue(other.id).anyTimber shouldBe false
    }

    test("freshly restored paid tree accounts for its remaining logs and leaves its native cursor untouched") {
        val base = surplusDemand(surplusCity(), mapOf("SPRUCE_LOG" to 1L))
        val workplace = base.buildings.last()
        val other = workplace.copy(id = UUID(1, 3))
        val paid = CivForestryJob(UUID(2, 4), workplace.anchor, "SPRUCE_SAPLING", listOf(workplace.anchor),
            listOf("minecraft:spruce_log[axis=y]"), listOf(0, 1), next = 1, harvestedLogs = 1)
        val city = base.copy(buildings = base.buildings + other, workers = base.workers +
            base.workers.single().copy(id = UUID(3, 4), assignment = other.id),
            forestryJobs = mapOf(workplace.id.toString() to paid))
        val restored = Gson().fromJson(Gson().toJson(city), Settlement::class.java)
        restored.review(mapOf("OAK_LOG" to 512L)).orders shouldBe emptyMap()
        restored.forestryJobs shouldBe city.forestryJobs
    }

    test("plain carried goods are counted once and historical pre-bag cargo remains owned") {
        val base = surplusCity()
        val source = base.buildings.last()
        val warehouse = source.copy(id = UUID.randomUUID(), type = BuildingType.WAREHOUSE)
        val cargo = CivCargo(UUID.randomUUID(), warehouse.id, source.id, warehouse.id, source.entrance,
            "OAK_LOG", 64L, inventoryBacked = true)
        val courier = CivWorker(UUID.randomUUID(), "Courier", warehouse.id, cargo = cargo, items =
            MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { null }.also { it[CivsEquipment.BAG] = CivResidentItem("OAK_LOG", 64) })
        val city = base.copy(buildings = base.buildings + warehouse, workers = base.workers + courier)
        val held = mapOf("OAK_LOG" to 400L)
        city.review(held).timber?.familyStock shouldBe 464L
        city.copy(workers = base.workers + courier.copy(cargo = cargo.copy(inventoryBacked = false), items = null))
            .review(held).timber?.familyStock shouldBe 464L
    }

    test("a durable virtual store is counted once and historical closed stock keeps its own work path") {
        val base = surplusCity()
        val city = base.copy(inventory = CivInventory(CivStorageMode.VIRTUAL, materials = mapOf("OAK_LOG" to 200L),
            buildingMaterials = mapOf(base.buildings.last().id.toString() to mapOf("SPRUCE_WOOD" to 200L))))
        city.review(mapOf("OAK_LOG" to 400L)).timber?.familyStock shouldBe 400L
        CivsProductionOrders.applies(city, city.buildings.last()) shouldBe true
        CivsProductionOrders.applies(city.copy(inventory = null, autonomy = CivAutonomy()), city.buildings.last()) shouldBe false
    }

    test("an unrelated missing holder allows local work until the small known reserve fills and local consumption reopens it") {
        val base = surplusCity()
        val farm = base.buildings.last().copy(type = BuildingType.FARM, production = "WHEAT")
        val bakery = farm.copy(id = UUID(0, 7), type = BuildingType.BAKERY, production = null)
        val baker = base.workers.single().copy(id = UUID(0, 9), assignment = bakery.id)
        val city = base.copy(buildings = listOf(base.buildings.first(), farm, bakery), workers = base.workers + baker)
        val farmer = city.workers.first()
        fun review(city: Settlement, held: Long, previous: CivProductionBoard): CivProductionBoard {
            val snapshot = surplusSnapshot(city, mapOf("WHEAT" to held))
            val evidence = snapshot.inputKey as CivSupplyObservation
            val capture = evidence.capture.copy(complete = false, stores = evidence.capture.stores.mapValues { (id, store) ->
                if (id == city.buildings.first().id) store.copy(unavailable = 1) else store
            })
            val partial = snapshot.copy(inputKey = evidence.copy(capture = capture),
                requests = listOf(CivSupplyRequest(bakery.id, "WHEAT", 3, 0)))
            return CivsProductionOrders.allocate(CivsProductionOrders.survey(city, partial, SURPLUS_NOW), partial, emptyMap(), previous, SURPLUS_NOW)
        }
        val first = review(city, 0, CivProductionBoard())
        first.partial shouldBe true
        first.orders.getValue(farmer.id).untilCycle shouldBe 4L
        val worked = city.copy(workers = listOf(farmer.copy(completedCycles = 4), baker))
        val next = review(worked, 4, first)
        next.orders[farmer.id] shouldBe null
        review(worked, 4, next).orders[farmer.id] shouldBe null
        val consumed = worked.copy(workers = listOf(worked.workers.first(), baker.copy(completedCycles = 1)))
        review(consumed, 1, next).orders.getValue(farmer.id).fromCycle shouldBe 4L
    }

    test("a quarry cannot endlessly make cobblestone for a selected native variant and the paid stone recipe stays separate") {
        val base = surplusCity()
        val quarry = base.buildings.last().copy(type = BuildingType.QUARRY, production = "STONE")
        val smelter = quarry.copy(id = UUID(0, 11), type = BuildingType.SMELTERY)
        val miner = base.workers.single()
        val smelterWorker = miner.copy(id = UUID(0, 12), assignment = smelter.id)
        val city = surplusDemand(base.copy(buildings = base.buildings.dropLast(1) + listOf(quarry, smelter),
            workers = listOf(miner, smelterWorker)), mapOf("STONE" to 16L))
        val lots = city.review(mapOf("COBBLESTONE" to 128L))
        lots.orders[miner.id] shouldBe null
        val exact = lots.orders.getValue(smelterWorker.id)
        exact.material shouldBe "STONE"
        exact.untilCycle shouldBe 2L
        val short = city.review(emptyMap())
        short.orders.getValue(miner.id).material shouldBe "COBBLESTONE"
        city.construction?.materials shouldBe mapOf("STONE" to 16L)
    }

    test("unknown or stale observations retain awarded lots and cannot start more work") {
        val city = surplusCity()
        val snapshot = surplusSnapshot(city, emptyMap())
        val first = city.review(emptyMap())
        val evidence = snapshot.inputKey as CivSupplyObservation
        val missing = snapshot.copy(inputKey = evidence.copy(capture = evidence.capture.copy(plainStock = null)))
        for ((candidate, now) in listOf(missing to SURPLUS_NOW, snapshot to SURPLUS_NOW + 15_001L)) {
            val survey = CivsProductionOrders.survey(city, candidate, now)
            (survey.unavailable != null) shouldBe true
            CivsProductionOrders.allocate(survey, candidate, emptyMap(), first, now).orders shouldBe first.orders
            CivsProductionOrders.allocate(survey, candidate, emptyMap(), CivProductionBoard(), now).orders shouldBe emptyMap()
        }
    }

    test("one native holder shared by two building keys is read and aggregated once") {
        val city = surplusCity()
        val holder = requireNotNull(city.buildings.first().control)
        val keys = city.buildings.associate { building -> building.id to CivSupplyStorageKey(city.id, city.worldId,
            city.owner, CivStorageMode.PHYSICAL, building.id, building.type, building.phase, building.level,
            building.bounds, building.control, mapOf(holder to "BARREL"), emptyMap(), 1) }
        var reads = 0
        val capture = CivsSupplyCapture(city, keys, { _, _, _ ->
            reads++
            CivSupplyCapturedContainer("BARREL", listOf(CivSupplyCapturedSlot("OAK_LOG", 64, true, 64)))
        }, { key, _ -> key }, coverage = { true })
        while (!capture.step()) Unit
        val result = requireNotNull(capture.result)
        reads shouldBe 1
        result.stores.values.sumOf { it.stock["OAK_LOG"] ?: 0L } shouldBe 128L
        result.plainStock shouldBe mapOf("OAK_LOG" to 64L)
        (result.startedAtMillis != null) shouldBe true
    }

    test("registered outside holders count once while implicit long-road holders cannot fake complete family stock") {
        val base = surplusCity()
        val holder = requireNotNull(base.buildings.first().control)
        val registered = CivPosition(40, 64, 40)
        val implicit = CivPosition(10_000, 64, 0)
        val road = base.buildings.first().copy(id = UUID.randomUUID(), type = BuildingType.ROAD, control = null,
            bounds = CivBox(CivPosition(0, 63, 0), CivPosition(10_000, 66, 1)))
        val city = base.copy(buildings = base.buildings + road,
            inventory = CivInventory(CivStorageMode.PHYSICAL, containers = listOf(holder, registered)))
        val keys = city.buildings.associate { building -> building.id to CivSupplyStorageKey(city.id, city.worldId,
            city.owner, CivStorageMode.PHYSICAL, building.id, building.type, building.phase, building.level,
            building.bounds, building.control, mapOf((if (building == road) implicit else holder) to "BARREL"), emptyMap(), 1) }
        var reads = 0
        var available = true
        fun capture(): CivSupplyCaptureResult {
            val cursor = CivsSupplyCapture(city, keys, { _, position, _ ->
                reads++
                if (position == registered && !available) null else
                    CivSupplyCapturedContainer("BARREL", listOf(CivSupplyCapturedSlot("OAK_LOG", 64, true, 64)))
            }, { key, _ -> key }, coverage = { true })
            while (!cursor.step()) Unit
            return requireNotNull(cursor.result)
        }
        val complete = capture()
        reads shouldBe 3
        complete.plainStock shouldBe mapOf("OAK_LOG" to 128L)
        complete.complete shouldBe true
        available = false
        capture().complete shouldBe false
    }

    test("a coverage loss during a bounded capture never publishes a complete stock proof") {
        val city = surplusCity()
        val keys = city.buildings.associate { building -> building.id to CivSupplyStorageKey(city.id, city.worldId,
            city.owner, CivStorageMode.PHYSICAL, building.id, building.type, building.phase, building.level,
            building.bounds, building.control, mapOf(requireNotNull(building.control) to "BARREL"), emptyMap(), 1) }
        var covered = true
        val cursor = CivsSupplyCapture(city, keys, { _, _, _ ->
            CivSupplyCapturedContainer("BARREL", emptyList())
        }, { key, _ -> key }, coverage = { covered })
        cursor.step() shouldBe false
        covered = false
        while (!cursor.step()) Unit
        requireNotNull(cursor.result).complete shouldBe false
    }

    test("a published tree order admits a fresh tree and its selected action survives withdrawal") {
        forestryWorksite("forestry-surplus-selected", autonomous = false, strict = true) { world, runtime, initial ->
            val city = initial.copy(inventory = CivInventory(CivStorageMode.PHYSICAL), workers = initial.workers.map {
                CivsEquipment.collectSupplies(it, mapOf("OAK_SAPLING" to 1L))
            })
            val worker = city.workers.single()
            val root = CivPosition(10, 64, 10)
            forestrySmallOak(world, root)
            val building = requireNotNull(worker.assignment)
            var order: CivProductionOrder? = null
            runtime.productionOrder = { _, current -> order?.takeIf { it.matches(current) } }
            repeat(8) { runtime.pulse(listOf(city)) }
            runtime.plan(city, worker) shouldBe null
            runtime.idleReason(city, worker) shouldBe "waiting-orders"
            world.getBlockAt(root.x, root.y, root.z).type shouldBe Material.OAK_LOG
            order = CivProductionOrder(building, "OAK_LOG", "OAK_LOG", worker.completedCycles, worker.completedCycles + 1, 1)
            repeat(2_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val selected = requireNotNull(runtime.plan(city, worker)) { runtime.debug().toString() }
            selected.status shouldBe "felling-tree"
            order = null
            repeat(8) { runtime.pulse(listOf(city)) }
            runtime.plan(city, worker) shouldBe selected
            runtime.validate(city, selected)
            val after = CivsRules.finishWorldWork(city, selected, 10_000)
            val receipt = CivInventoryTransaction(UUID.randomUUID(), city.id, city.revision, after, emptyList(),
                worldChanges = selected.changes)
            Gson().fromJson(Gson().toJson(receipt), CivInventoryTransaction::class.java).validated() shouldBe receipt
            after.workers.single().completedCycles shouldBe worker.completedCycles + 1
        }
    }

    test("a paid multi-batch tree completes without another order or a new cursor") {
        forestryWorksite("forestry-surplus-paid-tree", autonomous = false, strict = true) { world, runtime, initial ->
            var city = initial.copy(inventory = CivInventory(CivStorageMode.PHYSICAL), workers = initial.workers.map {
                CivsEquipment.collectSupplies(it, mapOf("OAK_SAPLING" to 1L))
            })
            val root = CivPosition(10, 64, 10)
            forestrySmallOak(world, root)
            for (x in root.x - 3..root.x + 3) for (z in root.z - 3..root.z + 3) for (y in root.y + 3..root.y + 5)
                world.getBlockAt(x, y, z).blockData = Bukkit.createBlockData("minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]")
            var worker = city.workers.single()
            repeat(3_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val first = requireNotNull(runtime.plan(city, worker)) { runtime.debug().toString() }
            val job = requireNotNull(first.forestry).job
            (first.forestry!!.next < job.cells.size) shouldBe true
            first.changes.forEach { cell -> world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData = Bukkit.createBlockData(cell.after) }
            city = CivsRules.finishWorldWork(city, first, 10_000)
            runtime.invalidate(worker.id, first)
            worker = city.workers.single()
            val saved = requireNotNull(city.forestryJobs)[jobKey(first)]
            runtime.productionOrder = { _, _ -> null }
            repeat(2_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val next = requireNotNull(runtime.plan(city, worker)) { runtime.debug().toString() }
            next.forestry?.job shouldBe saved
            runtime.validate(city, next)
            city.forestryJobs?.get(jobKey(first)) shouldBe saved
            next.forestry!!.next shouldBe requireNotNull(saved).cells.size
            next.changes.forEach { cell -> world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData = Bukkit.createBlockData(cell.after) }
            CivsRules.finishWorldWork(city, next, 20_000).forestryJobs shouldBe null
        }
    }
})

private const val SURPLUS_NOW = 50_000L
private fun jobKey(work: CivWorldWork) = work.buildingId.toString()
private fun surplusCity(): Settlement = forestryCity(autonomous = false).let { city ->
    city.copy(inventory = CivInventory(CivStorageMode.PHYSICAL), buildings = city.buildings.map {
        it.copy(control = it.anchor.copy(y = it.anchor.y + 1))
    })
}
private fun surplusDemand(city: Settlement, required: Map<String, Long>): Settlement {
    val target = city.buildings.last()
    return city.copy(construction = CivConstruction(UUID.randomUUID(), target.id, target.level + 1, 1, 0,
        ConstructionPhase.WAITING_MATERIALS, Stock(), materials = required))
}
private fun surplusSnapshot(city: Settlement, stock: Map<String, Long>): CivSupplySnapshot {
    val snapshot = supplyObservation(city, mapOf(city.buildings.first().id to stock))
    val evidence = snapshot.inputKey as CivSupplyObservation
    return snapshot.copy(inputKey = evidence.copy(capture = evidence.capture.copy(plainStock = stock, startedAtMillis = SURPLUS_NOW, complete = true)))
}
private fun Settlement.review(stock: Map<String, Long>, previous: CivProductionBoard = CivProductionBoard()): CivProductionBoard {
    val snapshot = surplusSnapshot(this, stock)
    return CivsProductionOrders.allocate(CivsProductionOrders.survey(this, snapshot, SURPLUS_NOW),
        snapshot, emptyMap(), previous, SURPLUS_NOW)
}
