package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import java.nio.file.Files
import java.util.concurrent.Executor

class CivsAutonomousStorageTest : FunSpec({
    test("the Auto2 384 shared stock cannot block a producer with room in its own building") {
        val city = localCapacityCity(Stock(384, 3, 382))
        val worker = city.workers.first()
        val produced = CivsAutonomousLogistics.produce(city, worker, Resource.WOOD, 6)
        produced.first shouldBe city.stock
        produced.second shouldBe Stock(wood = 6)
    }

    test("a full workplace cannot borrow empty containers from a warehouse or a different producer") {
        val base = localCapacityCity(Stock())
        val worker = base.workers.first()
        val city = base.copy(autonomy = base.autonomy!!.copy(storage = base.autonomy.storage!! +
            (worker.assignment.toString() to CivAutonomousStore(0, 27, 1))))
        shouldThrow<CivsRejection> { CivsAutonomousLogistics.produce(city, worker, Resource.WOOD, 6) }
            .reason shouldBe "storage-full"
    }

    test("a workplace reserves shared stack slots for all of its pending resources") {
        val base = localCapacityCity(Stock())
        val worker = base.workers.first().copy(pendingStock = Stock(wood = 63, food = 1))
        val city = base.copy(workers = listOf(worker, base.workers.last()),
            autonomy = base.autonomy!!.copy(storage = base.autonomy.storage!! +
                (worker.assignment.toString() to CivAutonomousStore(2, 27, 1))))
        CivsAutonomousLogistics.produce(city, worker, Resource.WOOD, 1).second shouldBe Stock(wood = 64, food = 1)
        shouldThrow<CivsRejection> { CivsAutonomousLogistics.produce(city, worker, Resource.WOOD, 2) }
            .reason shouldBe "storage-full"
    }

    test("one ordinary chest has room for 1728 stackable units rather than 256 abstract units") {
        CivsAutonomousStorage.room(27, Stock(wood = 384), Resource.WOOD) shouldBe 1_344L
        CivsAutonomousStorage.room(27, Stock(wood = 1_728), Resource.WOOD) shouldBe 0L
    }

    test("an unstaffed warehouse does not make a farmer reserve a forester's shared wood in the farm") {
        val base = localCapacityCity(Stock(wood = 64))
        val worker = base.workers.first()
        val city = base.copy(workers = listOf(worker), autonomy = base.autonomy!!.copy(storage = base.autonomy.storage!! +
            (worker.assignment.toString() to CivAutonomousStore(1, 27, 1))))
        val produced = CivsAutonomousLogistics.produce(city, worker, Resource.FOOD, 3)
        produced.first shouldBe city.stock
        produced.second shouldBe Stock(food = 3)
    }

    test("one warehouse slot cannot be reserved once for wood and again for stone") {
        val base = localCapacityCity(Stock())
        val warehouse = base.buildings.single { it.type == BuildingType.WAREHOUSE }
        val city = base.copy(autonomy = base.autonomy!!.copy(storage = base.autonomy.storage!! +
            (warehouse.id.toString() to CivAutonomousStore(1, 27, 1))))
        val capacity = CivsAutonomousStorage.capacities(city).getValue(warehouse.id)
        val reserved = CivSupplyRoom(capacity, mapOf("WOOD" to 64, "STONE" to 64))
        reserved.fits("WOOD") shouldBe 64L
        reserved.take("WOOD", 64)
        reserved.fits("STONE") shouldBe 0L
    }

    test("two warehouses subtract the existing city stock only once") {
        val base = localCapacityCity(Stock(wood = 64))
        val first = base.buildings.single { it.type == BuildingType.WAREHOUSE }
        val second = first.copy(id = UUID(1, 30), anchor = CivPosition(30, 64, 0),
            bounds = CivBox(CivPosition(28, 62, -2), CivPosition(32, 68, 2)), entrance = CivPosition(30, 64, -3),
            control = CivPosition(30, 64, 0))
        val city = base.copy(buildings = base.buildings + second, autonomy = base.autonomy!!.copy(
            storage = base.autonomy.storage!! + mapOf(first.id.toString() to CivAutonomousStore(1, 27, 1),
                second.id.toString() to CivAutonomousStore(1, 27, 1))))
        val capacities = CivsAutonomousStorage.capacities(city)
        capacities.values.sumOf { it.emptySlots } shouldBe 1
        CivsAutonomousStorage.cityRoom(city, Resource.WOOD) shouldBe 64L
    }

    test("the native observation and goods above the obsolete cap survive the production store") {
        val city = localCapacityCity(Stock(wood = 20_000)).let { base ->
            val worker = base.workers.first().copy(pendingStock = Stock(wood = 1_728))
            CivsRules.validate(base.copy(workers = listOf(worker, base.workers.last())))
        }
        val root = Files.createTempDirectory("civs-local-capacity-")
        try {
            CivsStore(root, Executor { it.run() }).use { it.save(city).join() }
            val restored = CivsStore(root, Executor { it.run() }).use { it.load().join().single() }
            restored shouldBe city
            restored.stock.wood shouldBe 20_000L
            restored.workers.first().pendingStock shouldBe Stock(wood = 1_728)
            // Losing native space only stops new intake; it does not invalidate already-earned goods.
            CivsRules.validate(restored.copy(autonomy = restored.autonomy!!.copy(storage = emptyMap())))
                .stock shouldBe city.stock
        } finally { root.toFile().deleteRecursively() }
    }
})

private fun localCapacityCity(stock: Stock): Settlement {
    fun building(type: BuildingType, x: Int) = CivBuilding(UUID(1, x.toLong()), type, 1,
        CivPosition(x, 64, 0), 0, "autonomy/${type.name.lowercase()}/1",
        CivBox(CivPosition(x - 2, 62, -2), CivPosition(x + 2, 68, 2)), CivPosition(x, 64, -3),
        BuildingPhase.ACTIVE, CivPosition(x, 64, 0))
    val hall = building(BuildingType.TOWN_HALL, 0)
    val mill = building(BuildingType.FORESTER, 10)
    val warehouse = building(BuildingType.WAREHOUSE, 20)
    return CivsRules.validate(CivsAutonomy.found(UUID(2, 1), UUID(2, 2), "Local capacity", UUID(2, 3),
        CivPosition(0, 64, 0), 64, "medievaloak", hall, 1_000).copy(stock = stock, inventory = null,
        buildings = listOf(hall, mill, warehouse), workers = listOf(
            CivWorker(UUID(3, 1), "Forester", mill.id, nextDeliveryAt = 1),
            CivWorker(UUID(3, 2), "Courier", warehouse.id, nextDeliveryAt = 1)),
        autonomy = CivAutonomy(storage = mapOf(hall.id.toString() to CivAutonomousStore(27, 27, 1),
            mill.id.toString() to CivAutonomousStore(27, 27, 1), warehouse.id.toString() to CivAutonomousStore(54, 54, 2)))))
}
