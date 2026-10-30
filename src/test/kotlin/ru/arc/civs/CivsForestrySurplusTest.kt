package ru.arc.civs

import com.google.gson.Gson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import java.util.UUID

class CivsForestrySurplusTest : FunSpec({
    test("one city family latch stops at 512 resumes at 256 and does not couple other cities") {
        val city = surplusCity()
        val other = city.copy(id = UUID.randomUUID())
        val policy = CivsForestrySurplus()
        policy.decision(city, mapOf("OAK_LOG" to 511L)).paused shouldBe false
        policy.decision(city, mapOf("OAK_LOG" to 256L, "SPRUCE_LOG" to 256L)).paused shouldBe true
        policy.decision(city, mapOf("BIRCH_LOG" to 300L)).paused shouldBe true
        policy.decision(other, mapOf("OAK_LOG" to 300L)).paused shouldBe false
        policy.decision(city, mapOf("OAK_LOG" to 256L)).paused shouldBe false
        policy.decision(city, mapOf("OAK_LOG" to 300L)).paused shouldBe false
        policy.decision(city, mapOf("STRIPPED_OAK_LOG" to 256L, "SPRUCE_WOOD" to 256L)).paused shouldBe true
        policy.retain(setOf(other.id))
        policy.decision(city, mapOf("OAK_LOG" to 300L)).paused shouldBe false
    }

    test("an autonomous city with null inventory uses the physical capture mode fallback") {
        val city = surplusCity().copy(inventory = null, autonomy = CivAutonomy())
        val decision = CivsForestrySurplus().decision(city, mapOf("OAK_LOG" to 512L))
        decision.familyStock shouldBe 512L
        decision.blocks("OAK_LOG") shouldBe true
        val virtual = city.copy(inventory = CivInventory(CivStorageMode.VIRTUAL))
        CivsForestrySurplus().decision(virtual, mapOf("OAK_LOG" to 512L)).reason shouldBe "not-physical"
        CivsForestrySurplus().decision(city.copy(autonomy = null), mapOf("OAK_LOG" to 512L)).reason shouldBe "not-physical"
    }

    test("a direct confirmed species deficit bypasses only that species despite another full timber stock") {
        val city = surplusDemand(surplusCity(), mapOf("SPRUCE_LOG" to 64L))
        val policy = CivsForestrySurplus()
        val deficit = policy.decision(city, mapOf("OAK_LOG" to 512L))
        deficit.blocks("OAK_LOG") shouldBe true
        deficit.blocks("SPRUCE_LOG") shouldBe false
        deficit.needed shouldBe setOf("SPRUCE_LOG")
        policy.decision(city, mapOf("OAK_LOG" to 512L, "SPRUCE_LOG" to 64L)).blocks("SPRUCE_LOG") shouldBe true
        city.construction!!.materials shouldBe mapOf("SPRUCE_LOG" to 64L)
    }

    test("pinned species recipes use existing intermediates and allow matching logs only for a real shortage") {
        val city = surplusDemand(surplusCity(), mapOf("SPRUCE_STAIRS" to 4L))
        val policy = CivsForestrySurplus()
        val deficit = policy.decision(city, mapOf("OAK_LOG" to 512L))
        deficit.blocks("SPRUCE_LOG") shouldBe false
        deficit.blocks("BIRCH_LOG") shouldBe true
        policy.decision(city, mapOf("OAK_LOG" to 512L, "SPRUCE_PLANKS" to 6L)).blocks("SPRUCE_LOG") shouldBe true
        policy.decision(city, mapOf("OAK_LOG" to 512L, "SPRUCE_WOOD" to 2L)).blocks("SPRUCE_LOG") shouldBe true
    }

    test("a valid accepted craft batch preserves its species deficit without speculative workshop demand") {
        val base = surplusCity()
        val mill = base.buildings.last().copy(type = BuildingType.SAWMILL, production = "SPRUCE_PLANKS")
        val city = base.copy(buildings = base.buildings.dropLast(1) + mill)
        val worker = city.workers.single()
        val recipe = CivsCarpentry.recipes.single { it.id == "spruce_planks/spruce_log" }
        val job = CivCraftJob(UUID.randomUUID(), worker.id, mill.id, worker.completedCycles, recipe.id,
            CivsCarpentry.VERSION, recipe.material, recipe.amount, recipe.inputs, recipe.material, city.buildings.first().id)
        val snapshot = surplusSnapshot(city, mapOf("OAK_LOG" to 512L))
        val policy = CivsForestrySurplus()
        policy.observe(city, snapshot, mapOf(worker.id to job), SURPLUS_NOW) { true }.needed shouldBe setOf("SPRUCE_LOG")
        policy.observe(city, snapshot, emptyMap(), SURPLUS_NOW) { true }.blocks("SPRUCE_LOG") shouldBe true
        policy.observe(city, snapshot, mapOf(worker.id to job.copy(cycle = worker.completedCycles + 1)), SURPLUS_NOW) { true }
            .blocks("SPRUCE_LOG") shouldBe true
    }

    test("carried cargo and unpicked reservations are not counted on top of their physical holders") {
        val base = surplusCity()
        val source = base.buildings.last()
        val warehouse = source.copy(id = UUID.randomUUID(), type = BuildingType.WAREHOUSE,
            anchor = CivPosition(40, 64, 40), entrance = CivPosition(41, 64, 40),
            bounds = CivBox(CivPosition(40, 63, 40), CivPosition(43, 68, 43)), control = CivPosition(40, 65, 40))
        val courierId = UUID.randomUUID()
        val cargo = CivCargo(UUID.randomUUID(), warehouse.id, source.id, warehouse.id, source.entrance,
            "OAK_LOG", 64L, inventoryBacked = true)
        val courier = CivWorker(courierId, "Courier", warehouse.id, cargo = cargo,
            items = MutableList<CivResidentItem?>(CivsEquipment.SLOTS) { null }.also {
                it[CivsEquipment.BAG] = CivResidentItem("OAK_LOG", 64)
            })
        val city = base.copy(buildings = base.buildings + warehouse, workers = base.workers + courier)
        val snapshot = surplusSnapshot(city, mapOf("OAK_LOG" to 400L))
        val reserved = CivCourierPlan(cargo.copy(inventoryBacked = false), warehouse.id, warehouse.entrance, false)
        val evidence = snapshot.copy(retained = mapOf(UUID.randomUUID() to reserved, courierId to
            CivCourierPlan(cargo, warehouse.id, warehouse.entrance, true)))
        val decision = CivsForestrySurplus().observe(city, evidence, emptyMap(), SURPLUS_NOW) { true }
        decision.familyStock shouldBe 464L
        decision.paused shouldBe false
    }

    test("unknown stale unavailable and incomplete stock neither starts nor releases a city latch") {
        val city = surplusCity()
        val policy = CivsForestrySurplus()
        val snapshot = surplusSnapshot(city, mapOf("OAK_LOG" to 600L))
        policy.observe(city, null, emptyMap(), SURPLUS_NOW) { true }.paused shouldBe false
        policy.observe(city, snapshot, emptyMap(), SURPLUS_NOW) { true }.paused shouldBe true
        policy.observe(city, null, emptyMap(), SURPLUS_NOW) { true }.paused shouldBe true
        policy.observe(city, snapshot, emptyMap(), SURPLUS_NOW) { false }.paused shouldBe true
        policy.observe(city, snapshot, emptyMap(), SURPLUS_NOW + 15_001) { true }.paused shouldBe true
        val evidence = snapshot.inputKey as CivSupplyObservation
        for (capture in listOf(evidence.capture.copy(plainStock = null), evidence.capture.copy(startedAtMillis = null),
            evidence.capture.copy(complete = false, plainStock = emptyMap()),
            evidence.capture.copy(stores = evidence.capture.stores.mapValues { (_, store) -> store.copy(unavailable = 1) }))) {
            val held = policy.observe(city, snapshot.copy(inputKey = evidence.copy(capture = capture)), emptyMap(), SURPLUS_NOW) { true }
            held.familyStock shouldBe null
            held.paused shouldBe true
        }
        val changed = city.copy(buildings = city.buildings + city.buildings.last().copy(id = UUID.randomUUID()))
        policy.observe(changed, snapshot, emptyMap(), SURPLUS_NOW) { true }.paused shouldBe true
        val requested = surplusDemand(city, mapOf("SPRUCE_LOG" to 64L))
        val held = policy.observe(requested, snapshot, emptyMap(), SURPLUS_NOW) { true }
        held.familyStock shouldBe null
        held.blocks("SPRUCE_LOG") shouldBe false
        held.blocks("OAK_LOG") shouldBe true
        policy.decision(city, mapOf("OAK_LOG" to 256L)).paused shouldBe false
        policy.observe(city, null, emptyMap(), SURPLUS_NOW) { true }.paused shouldBe false
    }

    test("unsupported extended recipes exempt only their exact species without inventing an oak substitute") {
        val policy = CivsForestrySurplus()
        for ((material, timber) in listOf("CHERRY_PLANKS" to "CHERRY_LOG", "MANGROVE_STAIRS" to "MANGROVE_LOG",
            "PALE_OAK_PLANKS" to "PALE_OAK_LOG", "CRIMSON_PLANKS" to "CRIMSON_STEM", "WARPED_PLANKS" to "WARPED_STEM",
            "SPRUCE_HANGING_SIGN" to "SPRUCE_LOG")) {
            val city = surplusDemand(surplusCity(), mapOf(material to 4L))
            val unknownRecipe = policy.decision(city, mapOf("OAK_LOG" to 600L))
            unknownRecipe.paused shouldBe true
            unknownRecipe.blocks(timber) shouldBe false
            unknownRecipe.blocks("OAK_LOG") shouldBe true
            unknownRecipe.blocks("BIRCH_LOG") shouldBe true
            val supplied = policy.decision(city, mapOf("OAK_LOG" to 600L, material to 4L))
            supplied.blocks(timber) shouldBe true
        }
        val direct = surplusDemand(surplusCity(), mapOf("CHERRY_LOG" to 4L))
        val missing = policy.decision(direct, mapOf("OAK_LOG" to 600L))
        missing.needed shouldBe setOf("CHERRY_LOG")
        missing.blocks("CHERRY_LOG") shouldBe false
        missing.blocks("OAK_LOG") shouldBe true
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

    test("surplus pauses fresh trees without resetting a selected native work action or changing its receipt") {
        forestryWorksite("forestry-surplus-selected", autonomous = false, strict = true) { world, runtime, initial ->
            val city = initial.copy(inventory = CivInventory(CivStorageMode.PHYSICAL), workers = initial.workers.map {
                CivsEquipment.collectSupplies(it, mapOf("OAK_SAPLING" to 1L))
            })
            val worker = city.workers.single()
            val root = CivPosition(10, 64, 10)
            forestrySmallOak(world, root)
            runtime.forestrySurplus = { _, _ -> true }
            repeat(8) { runtime.pulse(listOf(city)) }
            runtime.plan(city, worker) shouldBe null
            runtime.idleReason(city, worker) shouldBe "storage-surplus"
            world.getBlockAt(root.x, root.y, root.z).type shouldBe Material.OAK_LOG
            runtime.forestrySurplus = { _, _ -> false }
            repeat(2_000) { if (runtime.plan(city, worker) == null) runtime.pulse(listOf(city)) }
            val selected = requireNotNull(runtime.plan(city, worker)) { runtime.debug().toString() }
            selected.status shouldBe "felling-tree"
            runtime.forestrySurplus = { _, _ -> true }
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

    test("a paid multi-batch tree completes after the family pause without rewriting its saved cursor") {
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
            runtime.forestrySurplus = { _, _ -> true }
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
private fun CivsForestrySurplus.decision(city: Settlement, stock: Map<String, Long>) =
    observe(city, surplusSnapshot(city, stock), emptyMap(), SURPLUS_NOW) { true }
