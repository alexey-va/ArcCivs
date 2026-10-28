package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsTavernDiningTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    for (auto in listOf(false, true)) test("an active unstaffed tavern is the common meal venue Auto=$auto") {
        val base = tavernDiningCity(auto)
        val worker = base.workers.last().copy(hunger = 14, items = List(CivsEquipment.SLOTS) {
            if (it == CivsEquipment.BAG) CivResidentItem("BREAD", 4) else null
        })
        val city = base.copy(workers = base.workers.dropLast(1) + worker)
        val tavern = city.buildings.last()
        CivsRules.staffedTavern(city, tavern) shouldBe false
        val meal = requireNotNull(CivsResidentLife.mealPlan(city, worker))
        meal.buildingId shouldBe tavern.id
        val after = CivsResidentLife.finishMeal(city, meal)
        after.stock shouldBe city.stock
        CivsEquipment.plainCount(after.workers.last(), "BREAD") shouldBe 3
        after.workers.last().hunger shouldBe 20
        shouldThrow<CivsRejection> { CivsResidentLife.finishMeal(after, meal) }.reason shouldBe "stale-meal"
        val restarted = Gson().fromJson(Gson().toJson(after), Settlement::class.java).let(CivsRules::validate)
        var resident = restarted.workers.last()
        repeat(5) { resident = CivsResidents.activeMinute(resident, true) }
        CivsResidentLife.mealPlan(restarted.copy(workers = restarted.workers.dropLast(1) + resident), resident) shouldBe null
        resident = CivsResidents.activeMinute(resident, true)
        CivsResidentLife.mealPlan(restarted.copy(workers = restarted.workers.dropLast(1) + resident), resident)!!.buildingId shouldBe tavern.id
    }

    test("an unstaffed tavern requests twenty real bread for five residents through normal courier logistics") {
        val city = tavernDiningCity(false)
        val tavern = city.buildings.last()
        CivsLogistics.desiredStock(city, tavern) shouldBe mapOf("BREAD" to 20L)
        val requests = CivsLogistics.requests(city) { emptyMap() }
        requests.single { it.buildingId == tavern.id } shouldBe CivSupplyRequest(tavern.id, "BREAD", 20L, 0L)
        CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, supplyObservation(city)) shouldBe emptyList()
    }

    test("missing Auto tavern food is issued at the staffed warehouse and delivered by a real cargo plan") {
        val city = tavernDiningCity(true)
        val warehouse = city.buildings[1]
        val tavern = city.buildings.last()
        CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, supplyObservation(city)).single() shouldBe
            CivAutoSupplyDemand(warehouse.id, mapOf("BREAD" to 20L))
        val observation = supplyObservation(city, mapOf(warehouse.id to mapOf("BREAD" to 20L)))
        CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, observation) shouldBe emptyList()
        val input = observation.copy(requests = CivsLogistics.requests(city) { observation.stores[it]?.stock.orEmpty() },
            couriers = observation.couriers.map { it.copy(bagRoom = mapOf("BREAD" to 64L)) }, stackSizes = mapOf("BREAD" to 64)).detached()
        val plan = allocateCivSupplies(input).plans.values.single()
        plan.cargo.sourceBuildingId shouldBe warehouse.id
        plan.cargo.sourceWorkerId shouldBe null
        plan.destinationBuildingId shouldBe tavern.id
        plan.cargo.material shouldBe "BREAD"
        plan.cargo.amount shouldBe 20L
    }

    test("tavern stock local bread and incoming cargo reduce only the genuine warehouse shortage") {
        val base = tavernDiningCity(true)
        val warehouse = base.buildings[1]
        val tavern = base.buildings.last()
        val courier = base.workers.first()
        val cargo = CivCargo(CivsLogistics.shipmentId(base.id, courier.id, courier.completedCycles,
            warehouse.id, tavern.id, "BREAD"), warehouse.id, warehouse.id, tavern.id,
            warehouse.entrance, "BREAD", 2L, inventoryBacked = true)
        val city = base.copy(workers = listOf(CivsEquipment.collectSupplies(courier, mapOf("BREAD" to 2L)).copy(cargo = cargo)) +
            base.workers.drop(1).map { it.copy(hunger = 0) })
        CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, supplyObservation(city,
            mapOf(warehouse.id to mapOf("BREAD" to 10L), tavern.id to mapOf("BREAD" to 3L)))).single() shouldBe
            CivAutoSupplyDemand(warehouse.id, mapOf("BREAD" to 15L)) // Five new bread; normal delivery completes the twenty.
        val fullCargo = cargo.copy(amount = 20L)
        val carrying = base.copy(workers = listOf(CivsEquipment.collectSupplies(courier, mapOf("BREAD" to 20L)).copy(cargo = fullCargo)) +
            base.workers.drop(1).map { it.copy(hunger = 0) })
        CivsAutoSupplies.demands(carrying, null, CivsResidentFoods.BREAD_ONLY, supplyObservation(carrying)) shouldBe emptyList()
    }

    test("no courier retains emergency food support and a large tavern buffer stops at one stack") {
        val base = tavernDiningCity(true)
        val city = base.copy(workers = base.workers.map { it.copy(assignment = null, hunger = 0) })
        CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, supplyObservation(city)).single() shouldBe
            CivAutoSupplyDemand(city.buildings.first().id, mapOf("BREAD" to 4L))
        val many = city.copy(workers = (0..19).map { city.workers.first().copy(id = UUID(0, 800L + it)) })
        CivsLogistics.desiredStock(many, many.buildings.last()) shouldBe mapOf("BREAD" to 64L)
    }

    test("a full non-food depot preserves emergency food while existing depot bread suppresses extra grants") {
        val city = tavernDiningCity(true).let { it.copy(workers = it.workers.map { worker -> worker.copy(hunger = 0) }) }
        val warehouse = city.buildings[1]
        val observed = supplyObservation(city)
        val full = observed.copy(stores = observed.stores + (warehouse.id to observed.stores.getValue(warehouse.id)
            .copy(capacity = CivSupplyCapacity(0, emptyMap()))))
        CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, full).single() shouldBe
            CivAutoSupplyDemand(city.buildings.first().id, mapOf("BREAD" to 4L))
        val stocked = full.copy(stores = full.stores + (warehouse.id to full.stores.getValue(warehouse.id)
            .copy(stock = mapOf("BREAD" to 20L))))
        CivsAutoSupplies.demands(city, null, CivsResidentFoods.BREAD_ONLY, stocked) shouldBe emptyList()
    }
})

private fun tavernDiningCity(auto: Boolean): Settlement {
    val base = physicalAutoFixture()
    val warehouse = base.buildings.first { it.type == BuildingType.WAREHOUSE }
    val tavern = base.buildings.first { it.type == BuildingType.FORESTER }.copy(type = BuildingType.TAVERN, production = null)
    return CivsRules.validate(base.copy(buildings = listOf(base.buildings.first(), warehouse, tavern),
        autonomy = CivAutonomy().takeIf { auto }, workers = (0..4).map { index ->
            CivWorker(UUID(0, 700L + index), "Diner $index", warehouse.id.takeIf { index == 0 }, nextDeliveryAt = 1)
        }))
}
