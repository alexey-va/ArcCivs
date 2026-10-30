package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.Executor
import java.util.UUID

class CivsWarehouseRedistributionTest : FunSpec({
    test("a native full warehouse evacuates one whole batch to a depot that retains headroom") {
        val input = redistributionSnapshot()
        val plan = allocateCivSupplies(input).plans.values.single()
        plan.cargo.redistribution shouldBe true
        plan.cargo.sourceBuildingId shouldBe REDISTRIBUTION_SOURCE
        plan.destinationBuildingId shouldBe REDISTRIBUTION_TARGET
        plan.cargo.amount shouldBe 64L
        plan.cargo.warehouseId shouldBe REDISTRIBUTION_SOURCE
        input.stores.getValue(REDISTRIBUTION_SOURCE).stock shouldBe mapOf("STONE" to 64L)
    }

    test("one slot left unavailable virtual legacy or ordinary depots cannot start evacuation") {
        val input = redistributionSnapshot()
        val source = input.stores.getValue(REDISTRIBUTION_SOURCE)
        val target = input.stores.getValue(REDISTRIBUTION_TARGET)
        listOf(
            input.copy(stores = input.stores + (target.id to target.copy(capacity = CivSupplyCapacity(1, emptyMap())))),
            input.copy(stores = input.stores + (target.id to target.copy(redistributionDepot = false))),
            input.copy(stores = input.stores + (source.id to source.copy(redistributionDepot = false))),
            input.copy(stores = input.stores + (source.id to source.copy(capacity = CivSupplyCapacity(1, emptyMap())))),
            input.copy(stores = input.stores + (source.id to source.copy(capacity = CivSupplyCapacity(0, emptyMap(), 64)))),
            input.copy(autonomousStock = true),
        ).forEach { changed -> allocateCivSupplies(changed).plans shouldBe emptyMap() }
    }

    test("a full partial stack can leave in full but a reserved remainder is never assumed to release its slot") {
        val input = redistributionSnapshot()
        val source = input.stores.getValue(REDISTRIBUTION_SOURCE)
        val partial = input.copy(stores = input.stores + (source.id to source.copy(stock = mapOf("STONE" to 10L))))
        allocateCivSupplies(partial).plans.values.single().cargo.amount shouldBe 10L
        val reserved = partial.copy(stockTargets = mapOf(source.id to mapOf("STONE" to 1L)))
        allocateCivSupplies(reserved).plans shouldBe emptyMap()
        val tinyBag = input.copy(couriers = input.couriers.map { it.copy(bagRoom = mapOf("STONE" to 63L)) })
        allocateCivSupplies(tinyBag).plans shouldBe emptyMap()
    }

    test("construction and craft obligations retain their materials before an optional evacuation") {
        val input = redistributionSnapshot()
        val inaccessible = UUID(0, 810)
        val construction = input.copy(requests = listOf(CivSupplyRequest(inaccessible, "STONE", 64, 0, true)))
        allocateCivSupplies(construction).plans shouldBe emptyMap()
        val ordinary = input.copy(requests = listOf(CivSupplyRequest(inaccessible, "STONE", 64, 0)))
        allocateCivSupplies(ordinary).plans shouldBe emptyMap()
        val source = input.stores.getValue(REDISTRIBUTION_SOURCE)
        allocateCivSupplies(input.copy(stockTargets = mapOf(source.id to mapOf("STONE" to 64L)))).plans shouldBe emptyMap()
    }

    test("competing full warehouses share real destination slots and never consume its last free slot") {
        val input = redistributionSnapshot()
        val second = input.stores.getValue(REDISTRIBUTION_SOURCE).copy(id = UUID(0, 811),
            entrance = CivPosition(0, 64, 8), control = CivPosition(0, 64, 8))
        val courier = input.couriers.single().copy(workerId = UUID(0, 812), warehouseId = second.id, position = second.control)
        val competing = input.copy(stores = input.stores + (second.id to second), couriers = input.couriers + courier)
        allocateCivSupplies(competing).plans.size shouldBe 1
        val threeSlots = competing.copy(stores = competing.stores + (REDISTRIBUTION_TARGET to
            competing.stores.getValue(REDISTRIBUTION_TARGET).copy(capacity = CivSupplyCapacity(3, emptyMap()))))
        // Each accepted evacuation keeps its promised last slot reserved until completion.
        allocateCivSupplies(threeSlots).plans.size shouldBe 1
    }

    test("paid carried cargo wins its capacity and an unpicked evacuation retains its exact offer") {
        val input = redistributionSnapshot()
        val selected = allocateCivSupplies(input).plans.entries.single()
        val unpicked = input.copy(retained = mapOf(selected.key to selected.value))
        allocateCivSupplies(unpicked).plans shouldBe mapOf(selected.key to selected.value)
        val carried = selected.value.copy(cargo = selected.value.cargo.copy(inventoryBacked = true), carrying = true)
        val otherCourier = input.couriers.single().copy(workerId = UUID(0, 813))
        val paid = input.copy(retained = mapOf(selected.key to carried), couriers = listOf(otherCourier))
        allocateCivSupplies(paid).plans shouldBe mapOf(selected.key to carried)
    }

    test("a completed evacuation cannot reverse in the next unchanged capture") {
        val input = redistributionSnapshot()
        val source = input.stores.getValue(REDISTRIBUTION_SOURCE)
        val target = input.stores.getValue(REDISTRIBUTION_TARGET)
        val after = input.copy(stores = mapOf(
            source.id to source.copy(stock = emptyMap(), capacity = CivSupplyCapacity(1, emptyMap())),
            target.id to target.copy(stock = mapOf("STONE" to 64L), capacity = CivSupplyCapacity(1, emptyMap()))),
            couriers = listOf(input.couriers.single().copy(cycle = 1),
                input.couriers.single().copy(workerId = UUID(0, 814), warehouseId = target.id, position = target.control)))
        repeat(3) { allocateCivSupplies(after).plans shouldBe emptyMap() }
    }

    test("accepted request debug remains bounded and visible after the capture job has retired") {
        MockBukkitTestRuntime.open().use { paper ->
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(paper.createSimplePlugin("RedistributionDebug")))
            val input = redistributionSnapshot().copy(requests = (0L..10L).map {
                CivSupplyRequest(UUID(0, 900 + it), "STONE", it + 1, 0)
            })
            var captures = 0
            try {
                CivsSupplyDispatcher(tasks, null, { _, _ -> captures++; { input } },
                    { _, _ -> true }, Executor { it.run() }, { 1_000 }).use { dispatcher ->
                    dispatcher.request(input.cityId)
                    dispatcher.pulse()
                    val city = (dispatcher.debug(input.cityId)["entries"] as List<*>).single() as Map<*, *>
                    city["job"] shouldBe null
                    city["capturedRequests"] shouldBe null
                    val accepted = city["acceptedRequests"] as Map<*, *>
                    accepted["total"] shouldBe 11
                    accepted["truncated"] shouldBe true
                    (accepted["entries"] as List<*>).size shouldBe 8
                    captures shouldBe 1
                }
            } finally { tasks.close() }
        }
    }

    test("a home warehouse may send to a hall and an accessible other warehouse may collect into its own store") {
        val input = redistributionSnapshot()
        val hall = input.stores.getValue(REDISTRIBUTION_TARGET).copy(type = BuildingType.TOWN_HALL)
        allocateCivSupplies(input.copy(stores = input.stores + (hall.id to hall))).plans.values.single()
            .destinationBuildingId shouldBe hall.id
        val collector = input.couriers.single().copy(warehouseId = REDISTRIBUTION_TARGET,
            position = input.stores.getValue(REDISTRIBUTION_TARGET).control)
        val pulled = allocateCivSupplies(input.copy(couriers = listOf(collector))).plans.values.single()
        pulled.cargo.warehouseId shouldBe REDISTRIBUTION_TARGET
        pulled.cargo.sourceBuildingId shouldBe REDISTRIBUTION_SOURCE
        pulled.destinationBuildingId shouldBe REDISTRIBUTION_TARGET
        val far = input.stores.getValue(REDISTRIBUTION_TARGET).copy(control = CivPosition(300, 64, 0))
        allocateCivSupplies(input.copy(stores = input.stores + (far.id to far))).plans shouldBe emptyMap()
    }
})

private val REDISTRIBUTION_CITY = UUID(0, 800)
private val REDISTRIBUTION_SOURCE = UUID(0, 801)
private val REDISTRIBUTION_TARGET = UUID(0, 802)
private val REDISTRIBUTION_COURIER = UUID(0, 803)

private fun redistributionSnapshot(): CivSupplySnapshot {
    val source = CivSupplyStore(REDISTRIBUTION_SOURCE, CivPosition(0, 64, 0), CivPosition(0, 64, 0),
        mapOf("STONE" to 64L), emptyMap(), null, CivSupplyCapacity(0, emptyMap()), BuildingType.WAREHOUSE,
        redistributionDepot = true)
    val target = CivSupplyStore(REDISTRIBUTION_TARGET, CivPosition(8, 64, 0), CivPosition(8, 64, 0),
        emptyMap(), emptyMap(), null, CivSupplyCapacity(2, emptyMap()), BuildingType.WAREHOUSE,
        redistributionDepot = true)
    return CivSupplySnapshot(REDISTRIBUTION_CITY, Unit, mapOf(source.id to source, target.id to target), emptyList(),
        listOf(CivSupplyCourier(REDISTRIBUTION_COURIER, source.id, 0, source.control, mapOf("STONE" to 64L))),
        emptyList(), emptyMap(), mapOf("STONE" to 64))
}
