package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsIdleWarehouseCollectionTest : FunSpec({
    test("physical Auto idle courier captures real distant forester stock and collects without an order or producer actor") {
        MockBukkitTestRuntime.open().use {
            val city = idleCollectionCity(auto = true, distance = 150)
            val forester = city.buildings.last()
            val warehouse = city.buildings[1]
            val snapshot = idleCollectionSnapshot(city, mapOf("OAK_LOG" to 176L, "OAK_SAPLING" to 4L,
                "IRON_AXE" to 1L, "STICK" to 5L, "APPLE" to 1L))
            snapshot.requests shouldBe emptyList()
            snapshot.workerSources shouldBe emptyList()
            snapshot.maximumTransferDistance shouldBe CivsLogistics.maximumTransferDistance(city)
            val plan = allocateCivSupplies(snapshot.detached()).plans.values.single()
            plan.cargo.sourceBuildingId shouldBe forester.id
            plan.cargo.sourceEntrance shouldBe forester.entrance
            plan.cargo.sourceWorkerId shouldBe null
            plan.cargo.stockResource shouldBe null
            plan.destinationBuildingId shouldBe warehouse.id
            (plan.cargo.material in setOf("OAK_LOG", "STICK", "APPLE")) shouldBe true
            (plan.cargo.amount in 1..64L) shouldBe true
            snapshot.stores.getValue(forester.id).stock["OAK_LOG"] shouldBe 176L
        }
    }

    test("an existing ordinary request wins over an older closer background collection") {
        MockBukkitTestRuntime.open().use {
            val city = idleCollectionCity(auto = false, distance = 40)
            val source = city.buildings.last()
            val warehouse = city.buildings[1]
            val base = idleCollectionSnapshot(city, mapOf("OAK_LOG" to 176L, "OAK_SAPLING" to 4L, "IRON_AXE" to 1L),
                mapOf("STICK" to 64L))
            val request = CivSupplyRequest(source.id, "STICK", 2L, 0L)
            val oldCollection = CivSupplyTask(source.id, null, warehouse.id, "OAK_LOG", false)
            val snapshot = base.copy(requests = listOf(request), couriers = base.couriers.map { it.copy(position = source.control!!) })
            val plan = allocateCivSupplies(snapshot.detached(), ages = mapOf(oldCollection to 0L), now = 10_000).plans.values.single()
            plan.cargo.sourceBuildingId shouldBe warehouse.id
            plan.destinationBuildingId shouldBe source.id
            plan.cargo.material shouldBe "STICK"
            plan.cargo.amount shouldBe 2L
        }
    }

    for (auto in listOf(false, true)) test("idle native collection is shared by ordinary and Auto towns Auto=$auto") {
        MockBukkitTestRuntime.open().use {
            val city = idleCollectionCity(auto, 40)
            val snapshot = idleCollectionSnapshot(city, mapOf("OAK_LOG" to 176L, "OAK_SAPLING" to 4L, "IRON_AXE" to 1L))
            snapshot.requests shouldBe emptyList()
            snapshot.workerSources shouldBe emptyList()
            val plan = allocateCivSupplies(snapshot.detached()).plans.values.single()
            plan.cargo.material shouldBe "OAK_LOG"
            plan.cargo.amount shouldBe 64L
            plan.cargo.sourceWorkerId shouldBe null
            snapshot.stores.getValue(city.buildings.last().id).desiredStock shouldBe
                mapOf("OAK_SAPLING" to 4L, "IRON_AXE" to 1L)
            CivsEquipment.plainCount(city.workers.last(), "OAK_LOG") shouldBe 64
        }
    }

    test("idle collection shares real capacity and source stock without reassigning reserved or carried goods") {
        MockBukkitTestRuntime.open().use {
            val city = idleCollectionCity(false, 40)
            val source = city.buildings.last().id
            val warehouse = city.buildings[1].id
            val base = idleCollectionSnapshot(city, mapOf("OAK_LOG" to 176L, "OAK_SAPLING" to 4L, "IRON_AXE" to 1L)).detached()
            val input = base.copy(couriers = (0..3).map { index -> base.couriers.single().copy(
                workerId = UUID(0, 100L + index), bagRoom = mapOf("OAK_LOG" to 64L)) })
            val allocated = allocateCivSupplies(input).plans
            allocated.size shouldBe 3
            allocated.values.sumOf { it.cargo.amount } shouldBe 176L
            allocateCivSupplies(input.copy(retained = allocated)).plans shouldBe allocated
            val pickedId = allocated.keys.first()
            val carried = allocated.getValue(pickedId).let { it.copy(carrying = true, cargo = it.cargo.copy(inventoryBacked = true)) }
            val afterPickup = input.copy(retained = mapOf(pickedId to carried),
                couriers = input.couriers.filterNot { it.workerId == pickedId }, stores = input.stores +
                    (source to input.stores.getValue(source).copy(stock = mapOf("OAK_LOG" to 112L, "OAK_SAPLING" to 4L, "IRON_AXE" to 1L))))
            val next = allocateCivSupplies(afterPickup).plans
            next.getValue(pickedId) shouldBe carried
            next.values.filterNot { it.carrying }.sumOf { it.cargo.amount } shouldBe 112L
            val full = input.copy(stores = input.stores + (warehouse to input.stores.getValue(warehouse)
                .copy(capacity = CivSupplyCapacity(0, emptyMap()))))
            allocateCivSupplies(full).plans shouldBe emptyMap()
            val partial = full.copy(stores = full.stores + (warehouse to full.stores.getValue(warehouse)
                .copy(capacity = CivSupplyCapacity(0, mapOf("OAK_LOG" to 5L)))))
            allocateCivSupplies(partial).plans.values.sumOf { it.cargo.amount } shouldBe 5L
            val inputsOnly = input.copy(stores = input.stores + (source to input.stores.getValue(source)
                .copy(stock = mapOf("OAK_SAPLING" to 4L, "IRON_AXE" to 1L))))
            allocateCivSupplies(inputsOnly).plans shouldBe emptyMap()
        }
    }

    test("background source age rotates past a continuously replenished nearer producer") {
        MockBukkitTestRuntime.open().use {
            val city = idleCollectionCity(false, 40)
            val base = idleCollectionSnapshot(city, mapOf("OAK_LOG" to 176L, "OAK_SAPLING" to 4L, "IRON_AXE" to 1L)).detached()
            val source = base.stores.getValue(city.buildings.last().id)
            val far = source.copy(id = UUID(0, 40), entrance = CivPosition(60, 64, -3), control = CivPosition(60, 64, 0))
            val input = base.copy(stores = base.stores + (far.id to far))
            val first = allocateCivSupplies(input, now = 0)
            first.plans.values.single().cargo.sourceBuildingId shouldBe source.id
            val ages = (first.seen - first.served).associateWith { 0L }
            val second = allocateCivSupplies(input.copy(couriers = input.couriers.map { it.copy(cycle = 1) }), ages, now = 10_000)
            second.plans.values.single().cargo.sourceBuildingId shouldBe far.id
        }
    }

    test("idle collection admits only active producers and keeps the ordinary distance boundary") {
        MockBukkitTestRuntime.open().use {
            val ordinary = idleCollectionCity(false, 150)
            val distant = idleCollectionSnapshot(ordinary, mapOf("OAK_LOG" to 176L))
            distant.maximumTransferDistance shouldBe CivsRules.MAX_WORK_ROUTE_DISTANCE
            allocateCivSupplies(distant.detached()).plans shouldBe emptyMap()
            val city = idleCollectionCity(false, 40)
            val inactive = city.copy(buildings = city.buildings.map { if (it.type == BuildingType.FORESTER)
                it.copy(phase = BuildingPhase.SITE) else it })
            val snapshot = idleCollectionSnapshot(inactive, mapOf("OAK_LOG" to 176L))
            snapshot.stores.getValue(city.buildings.last().id).outputMaterial shouldBe null
            allocateCivSupplies(snapshot.detached()).plans shouldBe emptyMap()
        }
    }
})

private fun idleCollectionCity(auto: Boolean, distance: Int): Settlement {
    val warehouse = CivBuilding(UUID(0, 10), BuildingType.WAREHOUSE, 1, CivPosition(0, 64, 0), 0, "fixture/warehouse",
        CivBox(CivPosition(-2, 64, -2), CivPosition(2, 68, 2)), CivPosition(0, 64, -3), BuildingPhase.ACTIVE,
        control = CivPosition(0, 64, 0))
    val hall = warehouse.copy(id = UUID(0, 11), type = BuildingType.TOWN_HALL,
        anchor = CivPosition(20, 64, 0), bounds = CivBox(CivPosition(18, 64, -2), CivPosition(22, 68, 2)),
        entrance = CivPosition(20, 64, -3), control = CivPosition(20, 64, 0))
    val forester = warehouse.copy(id = UUID(0, 12), type = BuildingType.FORESTER,
        anchor = CivPosition(distance, 64, 0), bounds = CivBox(CivPosition(distance - 2, 64, -2), CivPosition(distance + 2, 68, 2)),
        entrance = CivPosition(distance, 64, -3), control = CivPosition(distance, 64, 0))
    return CivsRules.validate(Settlement(UUID(0, 1), UUID(0, 2), "Idle collection", UUID(0, 3),
        CivPosition(distance / 2, 64, 0), 96, "nordic", Stock(), listOf(hall, warehouse, forester),
        listOf(CivWorker(UUID(0, 20), "Courier", warehouse.id, nextDeliveryAt = 1), CivWorker(UUID(0, 21), "Forester", forester.id, nextDeliveryAt = 1,
            items = List(CivsEquipment.SLOTS) { if (it == CivsEquipment.BAG) CivResidentItem("OAK_LOG", 64) else null })),
        inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = CivAutonomy().takeIf { auto }))
}

private fun idleCollectionSnapshot(city: Settlement, producer: Map<String, Long>, warehouse: Map<String, Long> = emptyMap()): CivSupplySnapshot {
    val stock = mapOf(city.buildings.last().id to producer, city.buildings[1].id to warehouse)
    val keys = city.buildings.associate { building -> building.id to CivSupplyStorageKey(city.id, city.worldId, city.owner,
        CivStorageMode.PHYSICAL, building.id, building.type, building.phase, building.level, building.bounds, building.control,
        mapOf(building.control!! to "BARREL"), emptyMap(), 0) }
    val capture = CivsSupplyCapture(city, keys, { _, position, _ ->
        val building = city.buildings.single { it.control == position }
        val slots = stock[building.id].orEmpty().flatMap { (material, count) ->
            val size = org.bukkit.Material.valueOf(material).maxStackSize
            (0 until ((count + size - 1) / size).toInt()).map { part ->
                CivSupplyCapturedSlot(material, minOf(size.toLong(), count - part * size).toInt(), true, size)
            }
        }
        CivSupplyCapturedContainer("BARREL", slots + List(27 - slots.size) { CivSupplyCapturedSlot(null, 0, false, 0) })
    }, { key, _ -> key })
    val planning = CivsSupplyPlanning(city, capture, emptyMap(), { null }, { false })
    repeat(100) { planning.step()?.let { return it } }
    error("Supply capture did not finish")
}
