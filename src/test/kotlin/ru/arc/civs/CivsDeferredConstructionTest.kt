package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class CivsDeferredConstructionTest : FunSpec({
    lateinit var root: Path
    val direct = Executor { it.run() }
    beforeEach { root = Files.createTempDirectory("civs-deferred-") }
    afterEach { root.toFile().deleteRecursively() }

    test("another queued site finishes while a paid batch, its capture and remaining work survive restart") {
        val first = deferredSite(8)
        val head = deferredSite(12)
        val next = deferredSite(18)
        val outside = deferredSite(24)
        var city = deferredCity(first, head, next, outside)
        val order = capturedOrder(first, 2)
        city = CivsRules.reserveBuild(city, city.owner, first, order)
        city = CivsRules.enqueue(city, city.owner, head.id)
        city = CivsRules.enqueue(city, city.owner, next.id)
        city = builderSession(city)
        city = CivsRules.payBatch(city, city.owner, order.id, 0, mapOf("STONE" to 1L))
        city = CivsRules.completeBatch(city, city.owner, order.id)
        city = CivsRules.payBatch(city, city.owner, order.id, 1, mapOf("STONE" to 1L))
        val placing = city.construction!!
        CivsConstructionRules.resume(CivsConstructionRules.defer(city, placing), first.id).construction shouldBe placing
        city = CivsRules.pauseConstruction(city, city.owner, order.id, "chunk-unloaded")
        val paid = city.construction!!
        paid.payment?.reservedBatch shouldBe 1
        paid.workCredit shouldBe 1
        shouldThrow<CivsRejection> { CivsConstructionRules.defer(city, placing) }.reason shouldBe "stale-construction"

        val checkpoint = city.copy(revision = 0)
        val waiting = CivsConstructionRules.defer(checkpoint, paid)
        waiting.constructionQueue shouldBe listOf(head.id, next.id, first.id)
        waiting.deferredConstruction shouldBe listOf(paid)
        CivsConstructionRules.defer(waiting, paid) shouldBe waiting
        CivsStore(root, direct).use { store ->
            store.save(checkpoint).join()
            store.save(waiting).join()
            val changedOrder = waiting.copy(deferredConstruction = listOf(paid.copy(failure = "another-obstruction")),
                revision = waiting.revision + 1)
            shouldThrow<CompletionException> { store.stage(changedOrder).join() }.cause?.message shouldBe
                "World mutation intents require a durable save"
        }
        val reloaded = CivsStore(root, direct).use { it.load().join().single() }
        reloaded shouldBe waiting
        shouldThrow<CivsRejection> {
            CivsConstructionRules.reserveBuild(reloaded, reloaded.owner, outside, capturedOrder(outside), outside.id)
        }.reason shouldBe "construction-busy"
        shouldThrow<CivsRejection> {
            CivsConstructionRules.reserveBuild(reloaded, reloaded.owner, first, capturedOrder(first), first.id)
        }.reason shouldBe "construction-busy"
        val nextOrder = capturedOrder(next)
        city = CivsConstructionRules.reserveBuild(reloaded, reloaded.owner, next, nextOrder, next.id)
        city.constructionQueue shouldBe listOf(head.id, first.id)
        shouldThrow<CivsRejection> { CivsConstructionRules.resume(city, first.id) }.reason shouldBe "construction-busy"
        city = CivsRules.payBatch(builderSession(city), city.owner, nextOrder.id, 0, mapOf("STONE" to 1L))
        city = CivsRules.completeBatch(city, city.owner, nextOrder.id)
        city.building(next.id)?.phase shouldBe BuildingPhase.ACTIVE

        city = CivsConstructionRules.resume(city, first.id)
        city.construction shouldBe paid
        city.deferredConstruction shouldBe null
        city.constructionQueue shouldBe listOf(head.id)
        CivsConstructionRules.resume(city, first.id) shouldBe city
        city = CivsRules.retryConstruction(city, city.owner, order.id)
        shouldThrow<CivsRejection> { CivsRules.payBatch(city, city.owner, order.id, 1, mapOf("STONE" to 1L)) }
            .reason shouldBe "stale-construction"
        city = CivsRules.completeBatch(CivsRules.beginPlacement(city, city.owner, order.id), city.owner, order.id)
        city.building(first.id)?.phase shouldBe BuildingPhase.ACTIVE
        CivsEquipment.plainCount(city.workers.single(), "STONE") shouldBe 0
    }

    test("a pre-queue stock order round trips without a second price or a new batch payment") {
        val house = deferredSite(8).copy(phase = BuildingPhase.CONSTRUCTING)
        val order = CivConstruction(UUID.randomUUID(), house.id, 1, CivsRules.workRequired(house.type, 1), 2,
            ConstructionPhase.CONSTRUCTING, CivsRules.cost(house.type, 1), plannedBuilding = house)
        val original = deferredCity(house).copy(construction = order, inventory = null, stock = Stock(51, 19, 17))
        CivsStore(root, direct).use { it.save(original).join() }
        Files.readString(root.resolve("cities/${original.id}.json")).contains("deferredConstruction") shouldBe false
        CivsStore(root, direct).use { store ->
            val previous = store.load().join().single()
            previous shouldBe original
            store.save(CivsConstructionRules.defer(previous, order)).join()
        }
        val parked = CivsStore(root, direct).use { it.load().join().single() }
        val resumed = CivsConstructionRules.resume(parked, house.id)
        resumed.construction shouldBe order
        resumed.stock shouldBe original.stock
        resumed.workers shouldBe original.workers

        val hall = original.buildings.first { it.type == BuildingType.TOWN_HALL }.copy(level = 1, phase = BuildingPhase.CONSTRUCTING)
        val foundingOrder = CivConstruction(UUID.randomUUID(), hall.id, 1, 0, 0, ConstructionPhase.READY_TO_PLACE,
            Stock(), plannedBuilding = hall)
        val founding = original.copy(buildings = listOf(hall), workers = original.workers.map { it.copy(assignment = hall.id) },
            construction = foundingOrder)
        val waitingHall = CivsConstructionRules.defer(founding, foundingOrder)
        CivsRules.isFounding(waitingHall) shouldBe true
        CivsConstructionRules.resume(waitingHall, hall.id).workers shouldBe founding.workers
    }

    test("deleting a parked site retires only its paid order while the next building continues") {
        val first = deferredSite(8)
        val next = deferredSite(18)
        val tail = deferredSite(24)
        var city = deferredCity(first, next, tail)
        val order = capturedOrder(first, 3)
        city = CivsRules.reserveBuild(city, city.owner, first, order)
        city = CivsRules.enqueue(city, city.owner, next.id)
        city = CivsRules.enqueue(city, city.owner, tail.id)
        city = builderSession(city)
        val stone = mapOf("STONE" to 1L)
        city = CivsRules.payBatch(city, city.owner, order.id, 0, stone)
        city = CivsRules.completeBatch(city, city.owner, order.id)
        city = CivsRules.payBatch(city, city.owner, order.id, 1, stone)
        city = CivsConstructionRules.defer(city, city.construction!!)
        val replacement = capturedOrder(next)
        city = CivsRules.reserveBuild(city, city.owner, next, replacement)
        city.constructionOrder(first.id)?.payment?.reservedBatch shouldBe 1
        CivsRules.requireDeletion(city, city.owner, city.id, city.name)
        CivsRules.requestDeletion(city, city.owner, null).pendingDeletion shouldBe CivDeletion(null)
        val pending = CivsRules.requestDeletion(city, city.owner, first.id)
        val removed = CivsRules.finishBuildingDeletion(pending, city.owner)
        removed.construction shouldBe city.construction
        removed.building(next.id) shouldBe city.building(next.id)
        removed.building(first.id) shouldBe null
        removed.deferredConstruction shouldBe null
        removed.constructionQueue shouldBe listOf(tail.id)
        removed.workers shouldBe city.workers
        CivsEquipment.plainCount(removed.workers.single(), "STONE") shouldBe 1
        removed.inventory shouldBe city.inventory
        removed.stock shouldBe city.stock
        val paidNext = CivsRules.payBatch(builderSession(removed), city.owner, replacement.id, 0, stone)
        val finished = CivsRules.completeBatch(paidNext, city.owner, replacement.id)
        finished.building(next.id)?.phase shouldBe BuildingPhase.ACTIVE
        shouldThrow<CivsRejection> { CivsConstructionRules.resume(finished, first.id) }.reason shouldBe "stale-construction"
    }

    test("asynchronous persistence detaches a paused site's materials, awaited batch and order list") {
        val house = deferredSite(8).copy(phase = BuildingPhase.CONSTRUCTING)
        val materials = mutableMapOf("STONE" to 3L)
        val awaiting = mutableMapOf("STONE" to 1L)
        val order = capturedOrder(house, 3).copy(phase = ConstructionPhase.WAITING_MATERIALS,
            materials = materials, payment = CivConstructionPayment(awaiting = awaiting))
        val orders = mutableListOf(order)
        val city = deferredCity(house).copy(constructionQueue = listOf(house.id), deferredConstruction = orders)
        val pending = ArrayDeque<Runnable>()
        val store = CivsStore(root, Executor { pending.addLast(it) })
        val result = store.save(city)
        materials.clear()
        awaiting.clear()
        orders.clear()
        pending.removeFirst().run()
        result.join()
        store.close()
        pending.removeFirst().run()
        val restored = CivsStore(root, direct).use { it.load().join().single() }
        val resumed = CivsConstructionRules.resume(restored, house.id).construction!!
        resumed.id shouldBe order.id
        resumed.materials shouldBe mapOf("STONE" to 3L)
        resumed.payment?.awaiting shouldBe mapOf("STONE" to 1L)
    }

    test("the saved queue owns every parked capture and protects the full footprints of deferred upgrades") {
        val first = deferredSite(8).copy(phase = BuildingPhase.ACTIVE)
        val next = deferredSite(18).copy(phase = BuildingPhase.ACTIVE)
        var city = deferredCity(first, next)
        val extended = first.copy(level = 2, bounds = first.bounds.copy(max = first.bounds.max.copy(x = 14)))
        val order = capturedOrder(extended)
        city = CivsRules.reserveBuild(city, city.owner, extended, order)
        city = CivsRules.enqueue(city, city.owner, next.id)
        val parked = CivsConstructionRules.defer(city, city.construction!!)
        shouldThrow<CivsRejection> { CivsRules.placeSite(parked, parked.owner, deferredSite(14)) }.reason shouldBe "invalid-building"
        val overlap = next.copy(level = 2, bounds = next.bounds.copy(min = next.bounds.min.copy(x = 14)))
        shouldThrow<CivsRejection> {
            CivsConstructionRules.reserveBuild(parked, parked.owner, overlap, capturedOrder(overlap), next.id)
        }.reason shouldBe "invalid-building"
        val other = capturedOrder(next.copy(level = 2))
        listOf(
            parked.copy(constructionQueue = listOf(next.id)),
            parked.copy(deferredConstruction = listOf(order, order)),
            parked.copy(constructionQueue = listOf(first.id), construction = other.copy(id = order.id)),
            parked.copy(constructionQueue = listOf(first.id), construction = capturedOrder(overlap)),
            parked.copy(deferredConstruction = listOf(order.copy(payment = CivConstructionPayment(4)))),
        ).forEach { damaged -> shouldThrow<CivsRejection> { CivsRules.validate(damaged) }.reason shouldBe "invalid-state" }
    }
})

private fun deferredSite(x: Int, type: BuildingType = BuildingType.HOUSE): CivBuilding {
    val anchor = CivPosition(x, 64, 0)
    return CivBuilding(UUID.randomUUID(), type, 1, anchor, 0, "fixture/${type.name}",
        CivBox(anchor, anchor.copy(y = 66)), anchor.copy(z = -1), BuildingPhase.SITE, control = anchor)
}

private fun deferredCity(vararg sites: CivBuilding): Settlement {
    val hall = deferredSite(0, BuildingType.TOWN_HALL).copy(level = 2, phase = BuildingPhase.ACTIVE)
    val hut = deferredSite(-5, BuildingType.BUILDER_HUT).copy(phase = BuildingPhase.ACTIVE)
    val worker = CivWorker(UUID.randomUUID(), "Builder", hut.id, nextDeliveryAt = 1,
        items = List(CivsEquipment.SLOTS) { if (it == CivsEquipment.BAG) CivResidentItem("STONE", 3) else null })
    return Settlement(UUID.randomUUID(), UUID.randomUUID(), "Deferred", UUID.randomUUID(), hall.anchor, 64, "fixture",
        Stock(), listOf(hall, hut) + sites, listOf(worker), inventory = CivInventory(CivStorageMode.PHYSICAL))
}

private fun capturedOrder(building: CivBuilding, rows: Int = 1) = CivConstruction(UUID.randomUUID(), building.id,
    building.level, rows, 0, ConstructionPhase.CONSTRUCTING, Stock(), plannedBuilding = building.copy(phase = BuildingPhase.CONSTRUCTING),
    batches = CivConstructionBatches(rows, 16), materials = mapOf("STONE" to rows.toLong()), payment = CivConstructionPayment(),
    blocksRequired = rows * 16, blocksPlaced = 0)

private fun builderSession(city: Settlement): Settlement {
    val worker = city.workers.single()
    return CivsRules.deliver(city, city.owner, worker.id, worker.assignment!!, worker.completedCycles,
        worker.nextDeliveryAt, worker.nextDeliveryAt)
}
