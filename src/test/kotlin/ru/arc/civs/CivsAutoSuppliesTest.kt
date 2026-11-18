package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
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

    test("warehouse stock without a collection route does not block a needed workshop grant") {
        val base = supplyCity(BuildingType.SMELTERY, "STONE")
        val nearby = base.buildings[1]
        val workshop = base.buildings[2]
        val source = nearby.copy(id = UUID.randomUUID(), anchor = CivPosition(48, 64, 0),
            control = CivPosition(48, 64, 0), entrance = CivPosition(48, 64, -3),
            bounds = CivBox(CivPosition(46, 62, -2), CivPosition(50, 68, 2)))
        val world = paper.addSimpleWorld("auto-warehouse-route")
        val city = CivsRules.validate(base.copy(worldId = world.uid, buildings = base.buildings + source))
        val sourceBarrel = supplyBarrel(world, source)
        supplyBarrel(world, base.buildings.first())
        supplyBarrel(world, nearby)
        supplyBarrel(world, workshop).setItem(0, ItemStack(Material.CHARCOAL, 1))
        repeat(25) { sourceBarrel.setItem(it, ItemStack(Material.STONE, 64)) }
        sourceBarrel.setItem(25, ItemStack(Material.COBBLESTONE, 8))
        CivsStorage(paper.createSimplePlugin("AutoWarehouseRouteTest")) { emptyArray() }.use { storage ->
            storage.foodCanModify = { _, _ -> true }
            val unstaffed = captureAutoSupply(city, storage)
            unstaffed.stores.getValue(source.id).capacity.emptySlots shouldBe 1
            allocateCivSupplies(unstaffed.detached()).plans shouldBe emptyMap()
            CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, unstaffed) shouldBe
                listOf(CivAutoSupplyDemand(workshop.id, mapOf("COBBLESTONE" to 8L)))

            val porter = CivWorker(UUID.randomUUID(), "Source porter", source.id, nextDeliveryAt = 1)
            val staffed = city.copy(workers = city.workers + porter)
            val observed = captureAutoSupply(staffed, storage)
            val delivery = allocateCivSupplies(observed.detached()).plans.getValue(porter.id)
            delivery.cargo.sourceBuildingId shouldBe source.id
            delivery.destinationBuildingId shouldBe workshop.id
            delivery.cargo.material shouldBe "COBBLESTONE"
            delivery.cargo.amount shouldBe 8L
            CivsAutoSupplies.demands(staffed, null, CivsResidentFoods.BREAD_ONLY, observed) shouldBe emptyList()
        }
    }

    test("a tavern grant counts only bread its busy courier can collect from a full neighbouring warehouse") {
        val base = physicalAutoFixture()
        val relay = base.buildings.single { it.type == BuildingType.WAREHOUSE }
        val source = base.buildings.single { it.type == BuildingType.QUARRY }.copy(type = BuildingType.WAREHOUSE)
        val tavern = base.buildings.single { it.type == BuildingType.FARM }.copy(type = BuildingType.TAVERN)
        val forester = base.buildings.single { it.type == BuildingType.FORESTER }
        val courier = CivWorker(UUID.randomUUID(), "Courier", relay.id, nextDeliveryAt = 1)
        val world = paper.addSimpleWorld("auto-bread-busy-courier")
        val city = CivsRules.validate(base.copy(worldId = world.uid, workers = listOf(courier),
            buildings = listOf(base.buildings.first(), relay, source, tavern, forester)))
        city.buildings.forEach { supplyBarrel(world, it) }
        val full = supplyBarrel(world, source)
        val keepsake = ItemStack(Material.STONE, 64).apply {
            itemMeta = itemMeta.apply { displayName(Component.text("Named stone")) }
        }
        repeat(full.size - 1) { full.setItem(it, keepsake.clone()) }
        full.setItem(full.size - 1, ItemStack(Material.BREAD, 4))

        CivsStorage(paper.createSimplePlugin("BusyBreadCourier")) { emptyArray() }.use { storage ->
            storage.foodCanModify = { _, _ -> true }
            val reserved = captureAutoSupply(city, storage)
            reserved.requests.single { it.material == "BREAD" }.needed shouldBe 4L
            allocateCivSupplies(reserved.detached()).plans shouldBe emptyMap()
            CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, reserved) shouldBe
                listOf(CivAutoSupplyDemand(relay.id, mapOf("BREAD" to 4L)))

            full.setItem(full.size - 2, ItemStack(Material.BREAD, 64))
            full.setItem(full.size - 1, ItemStack(Material.BREAD, 64))
            val free = captureAutoSupply(city, storage)
            val collection = allocateCivSupplies(free.detached()).plans.getValue(courier.id)
            collection.cargo.sourceBuildingId shouldBe source.id
            collection.destinationBuildingId shouldBe relay.id
            collection.cargo.material shouldBe "BREAD"
            collection.cargo.amount shouldBe 64L

            val timber = requireNotNull(CivsProduction.material(forester))
            val cargo = CivCargo(CivsLogistics.shipmentId(city.id, courier.id, courier.completedCycles, forester.id, relay.id, timber),
                relay.id, forester.id, relay.id, forester.entrance, timber, 1L)
            val busy = CivsLogistics.pickup(city, courier.id, cargo)
            val observed = captureAutoSupply(busy, storage)
            allocateCivSupplies(observed.detached()).plans.getValue(courier.id).cargo.id shouldBe cargo.id
            CivsAutoSupplies.demands(busy, null, CivsResidentFoods.BREAD_ONLY, observed)
                .mapNotNull { it.required["BREAD"] } shouldBe emptyList()
        }
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

    test("a full warehouse still lets the hall top up only bread missing from both queues") {
        val world = paper.addSimpleWorld("queued-auto-bread")
        val base = physicalAutoFixture()
        val hall = base.buildings.first()
        val warehouse = base.buildings.single { it.type == BuildingType.WAREHOUSE }
        val hallBarrel = supplyBarrel(world, hall)
        val full = supplyBarrel(world, warehouse)
        hallBarrel.setItem(0, ItemStack(Material.BREAD, 1))
        repeat(full.size) { full.setItem(it, ItemStack(Material.STONE, 64)) }
        val sharedReserve = mapOf("BREAD" to 1L, "OAK_LOG" to 5L)
        var city = CivsRules.validate(base.copy(worldId = world.uid, buildings = listOf(hall, warehouse),
            workers = listOf(CivWorker(UUID.randomUUID(), "Hungry", null, hunger = 10, homeId = hall.id),
                CivWorker(UUID.randomUUID(), "Courier", warehouse.id, nextDeliveryAt = 1)),
            inventory = CivInventory(CivStorageMode.PHYSICAL, sharedReserve,
                buildingMaterials = mapOf(hall.id.toString() to mapOf("BREAD" to 1L)))))
        CivsStorage(paper.createSimplePlugin("AutoBreadGrantTest")) { emptyArray() }.use { storage ->
            storage.foodCanModify = { _, _ -> true }
            fun demands() = CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, captureAutoSupply(city, storage))
            fun apply(prepare: () -> CivStoragePlan) {
                val id = UUID.randomUUID()
                storage.reserve(city, id)
                val plan = prepare()
                val next = city.copy(inventory = plan.inventoryAfter, revision = city.revision + 1)
                val tx = CivInventoryTransaction(id, city.id, city.revision, next, plan.changes,
                    autoSupplyBuildingId = plan.autoSupplyBuildingId)
                storage.seal(tx)
                storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
                storage.unlock(id)
                city = next
            }

            val demand = demands().single()
            demand shouldBe CivAutoSupplyDemand(hall.id, mapOf("BREAD" to 2L))
            apply { requireNotNull(storage.prepareAutoSupply(city, demand) { true }) }
            hallBarrel.getItem(0) shouldBe ItemStack(Material.BREAD, 2)
            city.inventory!!.buildingStock(hall.id) shouldBe mapOf("BREAD" to 1L)
            storage.buildingAvailable(city, warehouse.id)["BREAD"] shouldBe null
            demands() shouldBe emptyList()

            apply { storage.prepareMaterialization(city) }
            hallBarrel.getItem(0) shouldBe ItemStack(Material.BREAD, 3)
            city.inventory!!.buildingStock(hall.id) shouldBe emptyMap()
            city.inventory!!.materials shouldBe sharedReserve
            full.contents.all { it == ItemStack(Material.STONE, 64) } shouldBe true
            demands() shouldBe emptyList()

            full.setItem(0, null)
            apply { storage.prepareMaterialization(city) }
            hallBarrel.getItem(0) shouldBe ItemStack(Material.BREAD, 3)
            full.getItem(0) shouldBe ItemStack(Material.BREAD, 1)
            city.inventory!!.materials shouldBe mapOf("OAK_LOG" to 5L)
            demands() shouldBe emptyList()
        }
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
        CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, observed) shouldBe
            listOf(CivAutoSupplyDemand(other.id, mapOf("DIRT" to 4L)))
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

    test("a one dirt construction row stocks a useful batch for subsequent rows") {
        val base = supplyCity(BuildingType.BUILDER_HUT, "")
        val target = base.buildings.first().copy(level = 2)
        val order = CivConstruction(UUID.randomUUID(), target.id, 2, 100, 0,
            ConstructionPhase.WAITING_MATERIALS, Stock(), plannedBuilding = target,
            batches = CivConstructionBatches(100, 16),
            materials = mapOf("DIRT" to 80L, "OAK_PLANKS" to 32L, "COBBLESTONE" to 100L),
            payment = CivConstructionPayment(awaiting = mapOf("DIRT" to 1L)))
        val city = base.copy(construction = order)
        val demand = CivsAutoSupplies.demands(city, mapOf("DIRT" to 1L),
            CivsResidentFoods.BREAD_ONLY, supplyObservation(city)).single()
        demand shouldBe CivAutoSupplyDemand(base.buildings[2].id,
            mapOf("COBBLESTONE" to 64L, "DIRT" to 64L, "OAK_PLANKS" to 32L))
    }

    test("an already paid row can prepare the next trip without duplicating carried or incoming materials") {
        val base = supplyCity(BuildingType.BUILDER_HUT, "")
        val target = base.buildings.first().copy(level = 2)
        val hut = base.buildings[2]
        val builder = CivsEquipment.collectSupplies(base.workers.last(), mapOf("DIRT" to 20L))
        val courier = base.workers.first().copy(cargo = CivCargo(UUID.randomUUID(), base.buildings[1].id,
            base.buildings[1].id, hut.id, base.buildings[1].entrance, "DIRT", 8L, inventoryBacked = true))
        val order = CivConstruction(UUID.randomUUID(), target.id, 2, 100, 0,
            ConstructionPhase.PLACING, Stock(), plannedBuilding = target,
            batches = CivConstructionBatches(100, 16), materials = mapOf("DIRT" to 40L),
            payment = CivConstructionPayment(reservedBatch = 0))
        val city = base.copy(workers = listOf(courier, builder), construction = order)
        CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY,
            supplyObservation(city, mapOf(hut.id to mapOf("DIRT" to 3L)))).single() shouldBe
            CivAutoSupplyDemand(hut.id, mapOf("DIRT" to 12L)) // Local three leaves nine new items.
        val ordinary = city.copy(autonomy = null)
        CivsAutoSupplies.demands(ordinary, null, CivsResidentFoods.BREAD_ONLY,
            supplyObservation(ordinary)) shouldBe emptyList()
    }

    test("the last paid construction row never refills its already consumed bill") {
        val base = supplyCity(BuildingType.BUILDER_HUT, "")
        val target = base.buildings.first().copy(level = 2)
        val order = CivConstruction(UUID.randomUUID(), target.id, 2, 1, 0,
            ConstructionPhase.PLACING, Stock(), plannedBuilding = target,
            batches = CivConstructionBatches(1, 16), materials = emptyMap(),
            payment = CivConstructionPayment(reservedBatch = 0))
        val city = base.copy(construction = order)
        CivsAutoSupplies.demands(city, mapOf("DIRT" to 1L), CivsResidentFoods.BREAD_ONLY,
            supplyObservation(city)) shouldBe emptyList()
    }
})

private fun supplyBarrel(world: org.bukkit.World, building: CivBuilding) = requireNotNull(building.control).let { point ->
    world.loadChunk(point.x shr 4, point.z shr 4)
    val block = world.getBlockAt(point.x, point.y, point.z).also { it.type = Material.BARREL }
    (block.state as Barrel).inventory
}

private fun captureAutoSupply(city: Settlement, storage: CivsStorage): CivSupplySnapshot {
    storage.updateCity(city)
    val planning = CivsSupplyPlanning(city, storage.startSupplyCapture(city, forceNative = true),
        emptyMap(), { null }, { false })
    return (1..20).firstNotNullOf { planning.step() }
}

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
