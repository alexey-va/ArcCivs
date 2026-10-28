package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsAutoSuppliesTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("goods without an available courier cannot deadlock autonomous bootstrap") {
        val base = supplyCity(BuildingType.SMELTERY, "STONE")
        val city = base.copy(workers = base.workers.filterNot { it.assignment == base.buildings[1].id })
        val observed = supplyObservation(city, mapOf(city.buildings[1].id to mapOf("COBBLESTONE" to 384L),
            city.buildings[2].id to mapOf("CHARCOAL" to 1L)))
        CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, observed).single().required shouldBe
            mapOf("COBBLESTONE" to 8L)
    }

    test("existing goods can reach selected work through two permitted warehouse legs") {
        val base = supplyCity(BuildingType.BUILDER_HUT, "")
        fun at(building: CivBuilding, x: Int, z: Int) = building.copy(anchor = CivPosition(x, 64, z),
            control = CivPosition(x, 64, z), entrance = CivPosition(x, 64, z - 3),
            bounds = CivBox(CivPosition(x - 2, 62, z - 2), CivPosition(x + 2, 68, z + 2)))
        val source = at(physicalAutoFixture().buildings.first { it.type == BuildingType.FORESTER }, -80, -80)
        val target = at(base.buildings[2], 80, 80)
        val city = CivsRules.validate(base.copy(buildings = listOf(at(base.buildings[0], -80, 80),
            at(base.buildings[1], 0, 0), target, source)))
        val observed = supplyObservation(city, mapOf(source.id to mapOf("OAK_LOG" to 8L)))
        CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, observed,
            selectedInputs = mapOf(city.workers.last().id to mapOf("OAK_LOG" to 8L)))
            .none { "OAK_LOG" in it.required } shouldBe true
    }

    test("a temporarily busy assigned courier still makes existing warehouse goods available") {
        val city = supplyCity(BuildingType.SMELTERY, "STONE")
        val observed = supplyObservation(city, mapOf(city.buildings[1].id to mapOf("COBBLESTONE" to 384L),
            city.buildings[2].id to mapOf("CHARCOAL" to 1L))).copy(couriers = emptyList())
        CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, observed) shouldBe emptyList()
    }

    test("existing warehouse goods satisfy global demand before a local grant") {
        val city = supplyCity(BuildingType.SMELTERY, "STONE")
        val warehouse = city.buildings[1]; val workplace = city.buildings[2]
        val observed = supplyObservation(city, mapOf(warehouse.id to mapOf("COBBLESTONE" to 384L),
            workplace.id to mapOf("CHARCOAL" to 1L)))
        CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, observed) shouldBe emptyList()
    }

    test("only the exact global shortage is issued while the local planner subtracts its existing stock") {
        val city = supplyCity(BuildingType.SMELTERY, "STONE")
        val warehouse = city.buildings[1]; val workplace = city.buildings[2]
        val observed = supplyObservation(city, mapOf(warehouse.id to mapOf("COBBLESTONE" to 3L),
            workplace.id to mapOf("COBBLESTONE" to 1L, "CHARCOAL" to 1L)))
        val demand = CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, observed).single()
        demand.buildingId shouldBe workplace.id
        demand.required shouldBe mapOf("COBBLESTONE" to 5L) // Existing local one leaves four new items.
    }

    test("a pinned sawmill batch uses its exact ingredients instead of configured output") {
        val city = supplyCity(BuildingType.SAWMILL, "OAK_PLANKS")
        val worker = city.workers.last(); val workplace = city.buildings[2]
        val recipe = CivsCarpentry.recipes.single { it.id == "spruce_stairs" }
        val job = supplyJob(city, worker, recipe)
        val demand = CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY,
            supplyObservation(city), mapOf(worker.id to job)).single()
        demand shouldBe CivAutoSupplyDemand(workplace.id, mapOf("SPRUCE_PLANKS" to 6L))
    }

    test("a pinned blacksmith batch subtracts its own held ingredients and replaces the configured recipe") {
        val base = supplyCity(BuildingType.BLACKSMITH, "IRON_SHOVEL")
        val worker = CivsEquipment.collectSupplies(base.workers.last(), mapOf("IRON_INGOT" to 2L, "STICK" to 1L))
        val city = base.copy(workers = base.workers.dropLast(1) + worker)
        val recipe = CivsCarpentry.recipes.single { it.id == "blacksmith/iron_pickaxe" }
        val job = supplyJob(city, worker, recipe)
        CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, supplyObservation(city), mapOf(worker.id to job))
            .single().required shouldBe mapOf("IRON_INGOT" to 1L, "STICK" to 1L)
    }

    test("another workplace retains its native recipe reserve before exporting excess") {
        val base = supplyCity(BuildingType.SMELTERY, "CHARCOAL")
        val other = base.buildings[2].copy(id = UUID.randomUUID(), anchor = CivPosition(12, 64, 0),
            entrance = CivPosition(12, 64, -3), control = CivPosition(12, 64, 0),
            bounds = CivBox(CivPosition(10, 62, -2), CivPosition(14, 68, 2)))
        val city = base.copy(buildings = base.buildings + other,
            workers = base.workers + CivWorker(UUID.randomUUID(), "Other smelter", other.id, nextDeliveryAt = 1))
        val observed = supplyObservation(city, mapOf(other.id to mapOf("OAK_LOG" to 10L, "OAK_PLANKS" to 6L),
            base.buildings[2].id to mapOf("OAK_PLANKS" to 6L)))
        CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, observed)
            .single { it.buildingId == base.buildings[2].id }.required shouldBe mapOf("OAK_LOG" to 6L)
    }

    test("accepted craft ingredients at another workshop stay reserved and only its surplus leaves") {
        val base = supplyCity(BuildingType.SMELTERY, "CHARCOAL")
        val other = base.buildings[2].copy(id = UUID.randomUUID(), type = BuildingType.SAWMILL,
            anchor = CivPosition(12, 64, 0), entrance = CivPosition(12, 64, -3), control = CivPosition(12, 64, 0),
            bounds = CivBox(CivPosition(10, 62, -2), CivPosition(14, 68, 2)))
        val worker = CivWorker(UUID.randomUUID(), "Carpenter", other.id, nextDeliveryAt = 1)
        val city = base.copy(buildings = base.buildings + other, workers = base.workers + worker)
        val recipe = CivsCarpentry.recipes.single { it.id == "oak_wood" }
        val job = supplyJob(city, worker, recipe)
        val observed = supplyObservation(city, mapOf(other.id to mapOf("OAK_LOG" to 9L),
            base.buildings[2].id to mapOf("OAK_PLANKS" to 6L)))
        CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, observed, mapOf(worker.id to job))
            .single { it.buildingId == base.buildings[2].id }.required shouldBe mapOf("OAK_LOG" to 3L)
    }

    test("physical producer bag surplus returning to its building prevents duplicate issuance") {
        val base = supplyCity(BuildingType.SMELTERY, "CHARCOAL")
        val other = base.buildings[2].copy(id = UUID.randomUUID(), type = BuildingType.FORESTER, production = "OAK_LOG",
            anchor = CivPosition(12, 64, 0), entrance = CivPosition(12, 64, -3), control = CivPosition(12, 64, 0),
            bounds = CivBox(CivPosition(10, 62, -2), CivPosition(14, 68, 2)))
        val worker = CivsEquipment.collectSupplies(CivWorker(UUID.randomUUID(), "Forester", other.id, nextDeliveryAt = 1),
            mapOf("OAK_LOG" to 8L, "OAK_SAPLING" to 4L))
        val city = base.copy(buildings = base.buildings + other, workers = base.workers + worker)
        val observed = supplyObservation(city, mapOf(base.buildings[2].id to mapOf("OAK_PLANKS" to 6L),
            other.id to mapOf("IRON_AXE" to 1L)))
        CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, observed) shouldBe emptyList()
    }

    test("carried cargo is counted once and does not also become producer bag surplus") {
        val base = supplyCity(BuildingType.SMELTERY, "CHARCOAL")
        val warehouse = base.buildings[1]; val workplace = base.buildings[2]
        val cargo = CivCargo(UUID.randomUUID(), warehouse.id, warehouse.id, workplace.id, warehouse.entrance,
            "OAK_LOG", 4L, inventoryBacked = true)
        val courier = CivsEquipment.collectSupplies(base.workers.first(), mapOf("OAK_LOG" to 4L)).copy(cargo = cargo)
        val city = base.copy(workers = listOf(courier, base.workers.last()))
        val observed = supplyObservation(city, mapOf(workplace.id to mapOf("OAK_PLANKS" to 6L)))
        CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, observed).single().required shouldBe mapOf("OAK_LOG" to 4L)
    }

    test("nonexportable and unreachable native stock cannot satisfy a present request") {
        val city = supplyCity(BuildingType.SMELTERY, "STONE")
        val warehouse = city.buildings[1]; val workplace = city.buildings[2]
        val observed = supplyObservation(city, mapOf(warehouse.id to mapOf("COBBLESTONE" to 384L),
            workplace.id to mapOf("CHARCOAL" to 1L)))
        for (source in listOf(observed.stores.getValue(warehouse.id).copy(control = CivPosition(300, 64, 0)),
                observed.stores.getValue(warehouse.id).copy(type = BuildingType.HOUSE))) {
            val changed = observed.copy(stores = observed.stores + (warehouse.id to source))
            CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, changed).single().required shouldBe mapOf("COBBLESTONE" to 8L)
        }
    }

    test("missing unavailable or stale observations cannot issue resources") {
        val city = supplyCity(BuildingType.SMELTERY, "STONE")
        val observed = supplyObservation(city)
        CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY) shouldBe emptyList()
        val capture = (observed.inputKey as CivSupplyObservation).capture
        val unavailable = capture.copy(stores = capture.stores.mapValues { (_, store) -> store.copy(unavailable = 1) })
        CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY,
            observed.copy(inputKey = CivSupplyObservation(civSupplyInputs(city), unavailable))) shouldBe emptyList()
        val changed = city.copy(workers = city.workers.map { it.copy(completedCycles = it.completedCycles + 1) })
        CivsAutoSupplies.demands(changed, null, CivsResidentFoods.BREAD_ONLY, observed) shouldBe emptyList()
    }

    test("selected native upkeep inputs use global stock and subtract their own worker bag") {
        val city = supplyCity(BuildingType.BUILDER_HUT, "")
        val worker = city.workers.last(); val workplace = city.buildings[2]; val warehouse = city.buildings[1]
        val inputs = mapOf(worker.id to mapOf("COBBLESTONE" to 1L))
        CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, supplyObservation(city),
            selectedInputs = inputs).single() shouldBe CivAutoSupplyDemand(workplace.id, mapOf("COBBLESTONE" to 1L))
        CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY,
            supplyObservation(city, mapOf(warehouse.id to mapOf("COBBLESTONE" to 384L))), selectedInputs = inputs) shouldBe emptyList()
        val carried = city.copy(workers = city.workers.dropLast(1) + CivsEquipment.collectSupplies(worker, mapOf("COBBLESTONE" to 1L)))
        CivsAutoSupplies.demands(carried, null, CivsResidentFoods.BREAD_ONLY, supplyObservation(carried),
            selectedInputs = inputs) shouldBe emptyList()
    }
})

private fun supplyCity(type: BuildingType, production: String): Settlement {
    val base = physicalAutoFixture()
    val warehouse = base.buildings.first { it.type == BuildingType.WAREHOUSE }
    val workplace = base.buildings.first { it.type == BuildingType.FORESTER }.copy(type = type, production = production.takeIf { it.isNotEmpty() })
    return CivsRules.validate(base.copy(buildings = listOf(base.buildings.first(), warehouse, workplace), workers = listOf(
        CivWorker(UUID.randomUUID(), "Courier", warehouse.id, nextDeliveryAt = 1),
        CivWorker(UUID.randomUUID(), "Worker", workplace.id, nextDeliveryAt = 1))))
}

private fun supplyJob(city: Settlement, worker: CivWorker, recipe: CivCraftRecipe) = CivCraftJob(UUID.randomUUID(),
    worker.id, requireNotNull(worker.assignment), worker.completedCycles, recipe.id, CivsCarpentry.VERSION,
    recipe.material, recipe.amount, recipe.inputs, recipe.material, city.buildings[1].id)

internal fun supplyObservation(city: Settlement, stock: Map<UUID, Map<String, Long>> = emptyMap()): CivSupplySnapshot {
    val stores = city.buildings.filter { it.phase == BuildingPhase.ACTIVE && it.control != null }.associate { building ->
        building.id to CivSupplyStore(building.id, building.entrance, requireNotNull(building.control), stock[building.id].orEmpty(),
            CivsLogistics.desiredStock(city, building), CivsProduction.material(building), CivSupplyCapacity(27, emptyMap()), building.type)
    }
    val capture = CivSupplyCaptureResult(city.id, stores.mapValues { (id, store) ->
        val building = requireNotNull(city.building(id))
        CivSupplyCapturedStore(store.stock, store.capacity, CivSupplyStorageKey(city.id, city.worldId, city.owner,
            CivStorageMode.PHYSICAL, id, building.type, building.phase, building.level, building.bounds, building.control,
            mapOf(store.control to "BARREL"), emptyMap(), 1), containers = 1)
    })
    return CivSupplySnapshot(city.id, CivSupplyObservation(civSupplyInputs(city), capture), stores, emptyList(),
        city.workers.mapNotNull { worker -> worker.assignment?.let(city::building)?.takeIf {
            it.type == BuildingType.WAREHOUSE && it.phase == BuildingPhase.ACTIVE && it.control != null
        }?.let { CivSupplyCourier(worker.id, it.id, worker.completedCycles, requireNotNull(it.control), emptyMap()) } },
        emptyList(), emptyMap(), emptyMap(), maximumTransferDistance = CivsLogistics.maximumTransferDistance(city))
}
