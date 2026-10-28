package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.FunSpec
import ru.arc.civs.scenarios.pinnedScenarioResource
import java.util.UUID
import kotlin.math.sqrt

class CivsAuto2SupplyRegressionTest : FunSpec({
    for ((producerType, kind, pending, at) in listOf(
        Auto2SupplyCase(BuildingType.FARM, Resource.FOOD, 219L, CivPosition(-1813, 64, -2735)),
        Auto2SupplyCase(BuildingType.FORESTER, Resource.WOOD, 3L, CivPosition(-1887, 63, -2687)),
    )) {
        test("Auto2 $producerType goods reach the staffed warehouse from its real distant work position") {
            val city = auto2SupplyCity(producerType, kind, pending)
            val producer = city.workers.first()
            val courier = city.workers.last()
            val warehouse = requireNotNull(city.building(courier.assignment!!))
            val target = requireNotNull(warehouse.control)
            val distance = sqrt((at.x.toDouble() - target.x).let { it * it } +
                (at.y.toDouble() - target.y).let { it * it } + (at.z.toDouble() - target.z).let { it * it })
            (distance > CivsRules.MAX_WORK_ROUTE_DISTANCE) shouldBe true
            city.contains(at.x, at.z) shouldBe true
            city.contains(target.x, target.z) shouldBe true
            val input = CivsAutonomousLogistics.snapshot(city, emptyMap(), { null }, mapOf(producer.id to at)) { false }
            input.workerSources.size shouldBe 1
            input.couriers.size shouldBe 1
            val plans = allocateCivSupplies(input.detached()).plans
            check(plans.size == 1) { "No shipment for $producerType: distance=$distance stock=${city.stock} pending=${producer.pendingStock}" }
            val cargo = plans.getValue(courier.id).cargo
            val actual = CitizenPoint(at.x + .5, at.y.toDouble(), at.z + .5)
            val destination = CitizenPoint(target.x + .5, target.y.toDouble(), target.z + .5)
            citizenRouteWithinReach(actual, destination) shouldBe false
            citizenRouteWithinReach(actual, destination, input.maximumTransferDistance) shouldBe true
            citizenStorageNeedsReset(at, target, actual, input.maximumTransferDistance) shouldBe false
            cargo.stockResource shouldBe kind
            cargo.sourceEntrance shouldBe at
            cargo.amount shouldBe minOf(pending, CivsLogistics.MAX_CARGO)
            val total = CivsAutonomousLogistics.held(city, kind)
            val picked = CivsLogistics.pickup(city, courier.id, cargo)
            picked.stock shouldBe city.stock
            CivsAutonomousLogistics.held(picked, kind) shouldBe total
            CivsLogistics.pickup(picked, courier.id, cargo) shouldBe picked
            val delivered = CivsLogistics.unload(picked, courier.id, cargo.id, 100_000)
            CivsAutonomousLogistics.held(delivered, kind) shouldBe total
            delivered.stock[kind] shouldBe city.stock[kind] + cargo.amount
            delivered.workers.first().completedCycles shouldBe producer.completedCycles
            delivered.workers.last().completedCycles shouldBe courier.completedCycles + 1
            shouldThrow<CivsRejection> { CivsLogistics.unload(delivered, courier.id, cargo.id, 100_001) }.reason shouldBe "stale-delivery"
            println("Auto2 $producerType warehouse transfer distance=$distance amount=${cargo.amount}; model allocation/pickup/unload, not NPC arrival")
        }
    }

    test("ordinary supply keeps its existing 88-block limit and an arbitrarily distant source is never dispatched") {
        val city = auto2SupplyCity(BuildingType.FARM, Resource.FOOD, 219)
        val source = city.workers.first()
        val base = CivsAutonomousLogistics.snapshot(city, emptyMap(), { null },
            mapOf(source.id to CivPosition(-1813, 64, -2735))) { false }.detached()
        val ordinary = CivSupplySnapshot(base.cityId, Unit, base.stores, base.requests, base.couriers,
            base.workerSources, base.retained, base.stackSizes)
        allocateCivSupplies(ordinary).plans shouldBe emptyMap()
        val remote = base.copy(workerSources = base.workerSources.map { it.copy(meeting = it.meeting.copy(x = 10_000)) })
        allocateCivSupplies(remote).plans shouldBe emptyMap()
        city.workers.first().pendingStock shouldBe Stock(food = 219)
    }

    test("automatic transfer context is bounded for large territories and malformed detached limits are refused") {
        val city = auto2SupplyCity(BuildingType.FARM, Resource.FOOD, 219)
        CivsLogistics.maximumTransferDistance(city) shouldBe 192
        CivsLogistics.maximumTransferDistance(city.copy(radius = Int.MAX_VALUE)) shouldBe 256
        CivsLogistics.maximumTransferDistance(city.copy(autonomy = null)) shouldBe 88
        val source = city.workers.first()
        val input = CivsAutonomousLogistics.snapshot(city, emptyMap(), { null },
            mapOf(source.id to CivPosition(-1813, 64, -2735))) { false }.detached()
        input.maximumTransferDistance shouldBe 192
        shouldThrow<IllegalArgumentException> { allocateCivSupplies(input.copy(maximumTransferDistance = 257)) }
        shouldThrow<IllegalArgumentException> { allocateCivSupplies(input.copy(maximumTransferDistance = 0)) }
    }
})

private data class Auto2SupplyCase(val type: BuildingType, val resource: Resource, val pending: Long, val meeting: CivPosition)

private fun auto2SupplyCity(type: BuildingType, resource: Resource, quantity: Long): Settlement {
    val gson = Gson()
    val geometry = JsonParser.parseString(pinnedScenarioResource("/scenarios/auto2-forest-city-geometry.json",
        "404bb8db3f0c5b70fb64cb810f40ace14a02e45c2d193ae212a353c2d91164a7")).asJsonObject
    val buildings = geometry.getAsJsonArray("buildings").map { value ->
        val b = value.asJsonObject.deepCopy()
        b.add("bounds", b.remove("actual_bounds")); b.add("schematicId", b.remove("schematic"))
        if (b.getAsJsonArray("transit").isEmpty) b.remove("transit")
        gson.fromJson(b, CivBuilding::class.java)
    }
    val relevant = buildings.filter { it.type in setOf(type, BuildingType.TOWN_HALL, BuildingType.WAREHOUSE) }
    val source = relevant.single { it.type == type }
    val warehouse = buildings.single { it.type == BuildingType.WAREHOUSE }
    val meta = geometry.getAsJsonObject("city")
    return CivsRules.validate(Settlement(UUID(0, 2), UUID(0, 1), "Auto2 supply geometry", UUID(0, 3),
        gson.fromJson(meta["center"], CivPosition::class.java), meta["radius"].asInt, meta["style"].asString,
        Stock(243, 12, 165), relevant, listOf(
            CivWorker(UUID(0, 4), "Producer", source.id, nextDeliveryAt = 1, pendingStock = Stock().with(resource, quantity)),
            CivWorker(UUID(0, 5), "Courier", warehouse.id, nextDeliveryAt = 1)), autonomy = CivAutonomy()))
}
