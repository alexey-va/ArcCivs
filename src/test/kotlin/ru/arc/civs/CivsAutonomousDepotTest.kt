package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsAutonomousDepotTest : FunSpec({
    test("self delivery chooses the farther warehouse with room and rejects credit at the full nearer one") {
        val base = depotCargoCity()
        val near = base.buildings.first { it.type == BuildingType.WAREHOUSE }
        val far = base.buildings.last { it.type == BuildingType.WAREHOUSE }
        val producer = base.workers.first().copy(assignment = base.buildings.last().id, cargo = null,
            pendingStock = Stock(stone = 128))
        val city = CivsRules.validate(base.copy(workers = listOf(producer), autonomy = base.autonomy!!.copy(
            storage = base.autonomy.storage!! + (near.id.toString() to CivAutonomousStore(0, 27, 1)))))
        CivsAutonomousStorage.pendingDestination(city, producer.pendingStock!!, near.entrance)?.id shouldBe far.id
        shouldThrow<CivsRejection> { CivsAutonomousLogistics.unloadPending(city, producer.id, producer.assignment!!,
            producer.completedCycles, producer.nextDeliveryAt, producer.pendingStock!!, 2_000, near.id) }
            .reason shouldBe "storage-full"
        val after = CivsAutonomousLogistics.unloadPending(city, producer.id, producer.assignment!!,
            producer.completedCycles, producer.nextDeliveryAt, producer.pendingStock!!, 2_000, far.id)
        after.stock shouldBe Stock(stone = 64)
        after.workers.single().pendingStock shouldBe Stock(stone = 64)
        CivsAutonomousStorage.pendingDestination(city.copy(autonomy = CivAutonomy()), producer.pendingStock!!,
            near.entrance)?.id shouldBe near.id
    }

    test("one exact self-delivery destination shares its slot across all pending resources") {
        val base = depotCargoCity()
        val destination = base.buildings.last { it.type == BuildingType.WAREHOUSE }
        val producer = base.workers.first().copy(assignment = base.buildings.last().id, cargo = null,
            pendingStock = Stock(wood = 1, stone = 1))
        val city = CivsRules.validate(base.copy(workers = listOf(producer)))
        val after = CivsAutonomousLogistics.unloadPending(city, producer.id, producer.assignment!!,
            producer.completedCycles, producer.nextDeliveryAt, producer.pendingStock!!, 2_000, destination.id)
        after.stock shouldBe Stock(wood = 1)
        after.workers.single().pendingStock shouldBe Stock(stone = 1)
    }

    test("a 27-slot depot exposes 1728 units and self delivery transfers more than one courier stack") {
        val base = depotCargoCity()
        val destination = base.buildings.first { it.type == BuildingType.WAREHOUSE }
        val producer = base.workers.first().copy(assignment = base.buildings.last().id, cargo = null,
            pendingStock = Stock(wood = 300))
        val city = CivsRules.validate(base.copy(workers = listOf(producer),
            buildings = base.buildings.filterNot { it.type == BuildingType.WAREHOUSE && it.id != destination.id },
            autonomy = base.autonomy!!.copy(storage = base.autonomy.storage!! +
                (destination.id.toString() to CivAutonomousStore(27, 27, 1)))))
        CivsAutonomousStorage.cityRoom(city, Resource.WOOD) shouldBe 1_728L
        CivsAutonomousStorage.destinationRoom(city, destination.id, Resource.WOOD) shouldBe 1_728L
        val after = CivsAutonomousLogistics.unloadPending(city, producer.id, producer.assignment!!,
            producer.completedCycles, producer.nextDeliveryAt, producer.pendingStock!!, 2_000, destination.id)
        after.stock shouldBe Stock(wood = 300)
        after.workers.single().pendingStock shouldBe null
    }

    test("a saved shipment unloads at its surviving actual destination after its warehouse is removed") {
        val before = depotCargoCity().let { it.copy(workers = listOf(it.workers.last())) }
        val courier = before.workers.last()
        val cargo = requireNotNull(courier.cargo)
        val city = CivsRules.validate(before.copy(buildings = before.buildings.filterNot { it.id == cargo.warehouseId },
            workers = before.workers.map { if (it.id == courier.id) it.copy(assignment = null) else it }))
        val destination = requireNotNull(CivsLogistics.destination(city, cargo))
        destination.id shouldBe city.buildings.single { it.type == BuildingType.WAREHOUSE }.id
        val after = CivsLogistics.unload(city, courier.id, cargo.id, 2_000)
        after.stock shouldBe Stock(wood = 64)
        after.workers.last().cargo shouldBe null
        CivsAutonomousLogistics.held(after, Resource.WOOD) shouldBe CivsAutonomousLogistics.held(city, Resource.WOOD)
    }

    test("reverse warehouse arrival cannot consume the other durable shipment's reserved slot") {
        val city = depotCargoCity()
        val second = city.workers.last()
        val afterSecond = CivsLogistics.unload(city, second.id, second.cargo!!.id, 2_000)
        afterSecond.stock shouldBe Stock(wood = 64)
        val first = afterSecond.workers.first()
        val afterBoth = CivsLogistics.unload(afterSecond, first.id, first.cargo!!.id, 2_001)
        afterBoth.stock shouldBe Stock(wood = 64, stone = 64)
        afterBoth.workers.all { it.cargo == null } shouldBe true
        CivsAutonomousLogistics.held(afterBoth, Resource.WOOD) shouldBe 64L
        CivsAutonomousLogistics.held(afterBoth, Resource.STONE) shouldBe 64L
    }

    test("self delivery cannot take capacity already reserved by carried goods") {
        val city = depotCargoCity()
        CivsAutonomousLogistics.pendingRoom(city, Stock(food = 1)) shouldBe false
        val snapshot = CivsAutonomousLogistics.snapshot(city, emptyMap(), { null }, emptyMap()) { false }
        val allocation = allocateCivSupplies(snapshot.detached())
        allocation.plans.size shouldBe 2
        allocation.plans.values.all { it.carrying } shouldBe true
    }

    test("the allocator reserves carried cargo once and still fills a separate free warehouse") {
        val base = depotCargoCity()
        val source = base.buildings.single { it.type == BuildingType.FORESTER }
        val worker = CivWorker(UUID(6, 1), "Producer", source.id, nextDeliveryAt = 1, pendingStock = Stock(wood = 64))
        val second = base.workers.last().copy(cargo = null)
        val city = CivsRules.validate(base.copy(workers = listOf(base.workers.first(), second, worker)))
        val snapshot = CivsAutonomousLogistics.snapshot(city, emptyMap(), { null }, mapOf(worker.id to source.entrance)) { false }
        val allocation = allocateCivSupplies(snapshot.detached())
        allocation.plans.size shouldBe 2
        allocation.plans.getValue(second.id).let { plan ->
            plan.carrying shouldBe false
            plan.cargo.stockResource shouldBe Resource.WOOD
            plan.cargo.amount shouldBe 64L
        }
        val picked = CivsLogistics.pickup(city, second.id, allocation.plans.getValue(second.id).cargo)
        val delivered = CivsLogistics.unload(picked, second.id, picked.workers[1].cargo!!.id, 2_000)
        delivered.stock shouldBe Stock(wood = 64)
        CivsAutonomousLogistics.held(delivered, Resource.STONE) shouldBe 64L
    }

    test("stored stock can share an incoming partial stack without stealing another resource's reservation") {
        val base = depotCargoCity()
        val second = base.workers.last().let { it.copy(cargo = it.cargo!!.copy(amount = 3)) }
        val city = CivsRules.validate(base.copy(stock = Stock(wood = 61), workers = listOf(base.workers.first(), second)))
        CivsAutonomousStorage.cityRoom(city, Resource.FOOD) shouldBe 0L
        val afterSecond = CivsLogistics.unload(city, second.id, second.cargo!!.id, 2_000)
        val first = afterSecond.workers.first()
        CivsLogistics.unload(afterSecond, first.id, first.cargo!!.id, 2_001).stock shouldBe Stock(wood = 64, stone = 64)
    }

    test("corpse cargo keeps its reservation until the same shipment is resurrected and unloaded") {
        val city = depotCargoCity()
        val courier = city.workers.last()
        val death = CivDeathContext(CivPosition(20, 64, 0), 2_000)
        val dead = CivsRules.hurtResident(city, city.owner, courier.id, 100.0, death)
        CivsAutonomousStorage.capacities(dead).values.sumOf { it.emptySlots } shouldBe 1
        CivsAutonomousStorage.cityRoom(dead, Resource.FOOD) shouldBe 0L
        val restored = CivsRules.resurrect(dead, dead.owner, courier.id, death.at, death.at + 1, administrator = true)
        val after = CivsLogistics.unload(restored, courier.id, courier.cargo!!.id, death.at + 2)
        after.stock shouldBe Stock(wood = 64)
        CivsAutonomousLogistics.held(after, Resource.STONE) shouldBe 64L
    }

    test("capacity shrink retains stock and shipments and blocks intake until they fit again") {
        val before = depotCargoCity().copy(stock = Stock(food = 1))
        val city = CivsRules.validate(before)
        CivsAutonomousStorage.cityRoom(city, Resource.WOOD) shouldBe 0L
        CivsAutonomousStorage.capacities(city).values.all { it.emptySlots == 0 && it.partialRoom.isEmpty() } shouldBe true
        val courier = city.workers.last()
        shouldThrow<CivsRejection> { CivsLogistics.unload(city, courier.id, courier.cargo!!.id, 2_000) }
            .reason shouldBe "storage-full"
        city.stock shouldBe Stock(food = 1)
        city.workers.last().cargo shouldBe courier.cargo
        val available = city.copy(stock = Stock())
        CivsLogistics.unload(available, courier.id, courier.cargo!!.id, 2_001).stock shouldBe Stock(wood = 64)
    }
})

private fun depotCargoCity(): Settlement {
    fun building(type: BuildingType, x: Int) = CivBuilding(UUID(1, x.toLong()), type, 1,
        CivPosition(x, 64, 0), 0, "autonomy/${type.name.lowercase()}/1",
        CivBox(CivPosition(x - 2, 62, -2), CivPosition(x + 2, 68, 2)), CivPosition(x, 64, -3),
        BuildingPhase.ACTIVE, CivPosition(x, 64, 0))
    val hall = building(BuildingType.TOWN_HALL, 0)
    val first = building(BuildingType.WAREHOUSE, 10)
    val second = building(BuildingType.WAREHOUSE, 20)
    val source = building(BuildingType.FORESTER, 30)
    fun courier(id: Long, warehouse: CivBuilding, resource: Resource) = CivWorker(UUID(2, id), "Courier $id",
        warehouse.id, nextDeliveryAt = 1, cargo = CivCargo(UUID(3, id), warehouse.id, source.id, warehouse.id,
            source.entrance, resource.name, 64, sourceWorkerId = UUID(4, id), stockResource = resource))
    return CivsRules.validate(CivsAutonomy.found(UUID(5, 1), UUID(5, 2), "Depot recovery", UUID(5, 3),
        CivPosition(0, 64, 0), 64, "medievaloak", hall, 1_000).copy(stock = Stock(),
        buildings = listOf(hall, first, second, source), workers = listOf(
            courier(1, first, Resource.STONE), courier(2, second, Resource.WOOD)),
        autonomy = CivAutonomy(storage = listOf(hall, first, second, source).associate {
            it.id.toString() to CivAutonomousStore(1, 27, 1)
        })))
}
