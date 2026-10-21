package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsConstructionQueueTest : FunSpec({
    test("queue is ordered idempotent restart safe and placing another site preserves the current order") {
        val fixture = QueueFixture()
        val active = fixture.start(fixture.city, fixture.first)
        val withSite = CivsRules.placeSite(active, active.owner, fixture.second)
        withSite.constructionQueue shouldBe listOf(fixture.second.id)
        withSite.revision shouldBe active.revision + 1
        val queued = CivsRules.enqueue(withSite, active.owner, fixture.second.id)
        CivsRules.enqueue(queued, active.owner, fixture.second.id) shouldBe queued
        val reloaded = CivsRules.validate(Gson().fromJson(Gson().toJson(queued), Settlement::class.java))
        reloaded.construction shouldBe active.construction
        reloaded.constructionQueue shouldBe listOf(fixture.second.id)
        reloaded.building(fixture.second.id)!!.phase shouldBe BuildingPhase.SITE
        shouldThrow<CivsRejection> { CivsRules.enqueue(queued, active.owner, fixture.first.id) }.reason shouldBe "invalid-building"
        shouldThrow<CivsRejection> { CivsRules.validate(queued.copy(constructionQueue = listOf(fixture.second.id, fixture.second.id))) }.reason shouldBe "invalid-state"
    }

    test("idle sites wait for a request and new confirmations append behind a waiting head") {
        val f = QueueFixture()
        val idle = CivsRules.placeSite(f.city, f.city.owner, f.second)
        idle.constructionQueue shouldBe null
        val waiting = CivsRules.enqueue(idle, idle.owner, f.first.id)
        val confirmed = CivsRules.placeSite(waiting, waiting.owner, f.third)
        confirmed.constructionQueue shouldBe listOf(f.first.id, f.third.id)
        CivsRules.placeSite(confirmed, confirmed.owner, f.third) shouldBe confirmed
        val reloaded = CivsRules.validate(Gson().fromJson(Gson().toJson(confirmed), Settlement::class.java))
        shouldThrow<CivsRejection> { f.start(reloaded, f.third) }.reason shouldBe "construction-busy"
        val next = f.start(reloaded, f.first)
        next.construction!!.buildingId shouldBe f.first.id
        next.constructionQueue shouldBe listOf(f.third.id)
        next.building(f.second.id)!!.phase shouldBe BuildingPhase.SITE
    }

    test("active building upgrades share the persisted FIFO with sites without changing their current level") {
        val f = QueueFixture()
        val building = f.first.copy(phase = BuildingPhase.ACTIVE)
        val city = f.city.copy(buildings = f.city.buildings.map { if (it.id == building.id) building else it })
        val queued = CivsRules.enqueue(city, city.owner, building.id)
        val withSite = CivsRules.placeSite(queued, queued.owner, f.second)
        val reloaded = CivsRules.validate(Gson().fromJson(Gson().toJson(withSite), Settlement::class.java))
        reloaded.constructionQueue shouldBe listOf(building.id, f.second.id)
        CivsRules.enqueue(reloaded, reloaded.owner, building.id) shouldBe reloaded
        shouldThrow<CivsRejection> { f.start(reloaded, f.second) }.reason shouldBe "construction-busy"
        val target = building.copy(level = 2, phase = BuildingPhase.CONSTRUCTING)
        val order = CivConstruction(UUID.randomUUID(), target.id, 2, 1, 0, ConstructionPhase.CONSTRUCTING,
            Stock(), plannedBuilding = target, batches = CivConstructionBatches(1, 16), materials = mapOf("STONE" to 1L),
            payment = CivConstructionPayment())
        val started = CivsRules.reserveBuild(reloaded, reloaded.owner, target, order)
        started.building(building.id) shouldBe building
        started.construction!!.targetLevel shouldBe 2
        started.constructionQueue shouldBe listOf(f.second.id)
        shouldThrow<CivsRejection> { CivsRules.enqueue(started, started.owner, building.id) }.reason shouldBe "construction-busy"
        val maxed = city.copy(buildings = city.buildings.map {
            if (it.id == building.id || it.type == BuildingType.TOWN_HALL) it.copy(level = 3) else it
        })
        shouldThrow<CivsRejection> { CivsRules.validate(maxed.copy(constructionQueue = listOf(building.id))) }.reason shouldBe "invalid-state"
    }

    test("one observed builder session preserves sixty four paid row boundaries across shortage pause and restart") {
        val f = QueueFixture()
        var city = f.start(f.city, f.first, rows = 70).let { started ->
            started.copy(workers = started.workers.mapIndexed { index, resident -> if (index == 0)
                resident.copy(items = List(CivsEquipment.SLOTS) { if (it == CivsEquipment.BAG) CivResidentItem("STONE", 64) else null })
                else resident })
        }
        val worker = city.workers.first()
        city = CivsRules.deliver(city, city.owner, worker.id, worker.assignment!!, worker.completedCycles, worker.nextDeliveryAt, worker.nextDeliveryAt)
        city.construction!!.workCredit shouldBe 64
        city.workers.first().nextDeliveryAt shouldBe worker.nextDeliveryAt + 2_000L
        for (index in 0 until 64) {
            val order = city.construction!!
            order.workDone shouldBe index
            city = CivsRules.awaitMaterials(city, city.owner, order.id, index, mapOf("STONE" to 1L))
            city = CivsRules.validate(Gson().fromJson(Gson().toJson(city), Settlement::class.java))
            city.construction!!.workCredit shouldBe 64 - index
            city = CivsRules.payBatch(city, city.owner, order.id, index, mapOf("STONE" to 1L))
            shouldThrow<CivsRejection> { CivsRules.payBatch(city, city.owner, order.id, index, mapOf("STONE" to 1L)) }.reason shouldBe "stale-construction"
            city = CivsRules.pauseConstruction(city, city.owner, order.id, "placement-cancelled")
            city = CivsRules.retryConstruction(city, city.owner, order.id)
            city.construction!!.payment!!.reservedBatch shouldBe index
            city = CivsRules.completeBatch(CivsRules.beginPlacement(city, city.owner, order.id), city.owner, order.id)
        }
        city.construction!!.workDone shouldBe 64
        city.construction!!.phase shouldBe ConstructionPhase.CONSTRUCTING
        city.construction!!.workCredit shouldBe 0
        city.construction!!.materials shouldBe mapOf("STONE" to 6L)
        city.workers.first().completedCycles shouldBe 1L
    }

    test("old prepaid row remains a single row with no newly introduced payment") {
        val f = QueueFixture()
        var city = f.start(f.city, f.first, incremental = false)
        val worker = city.workers.first()
        city = CivsRules.deliver(city, city.owner, worker.id, worker.assignment!!, 0, worker.nextDeliveryAt, worker.nextDeliveryAt)
        city.construction!!.payment shouldBe null
        city.construction!!.workCredit shouldBe null
        city = CivsRules.completeBatch(CivsRules.beginPlacement(city, city.owner, city.construction!!.id), city.owner, city.construction!!.id)
        city.construction!!.workDone shouldBe 1
        city.construction!!.phase shouldBe ConstructionPhase.CONSTRUCTING
        city.construction!!.materials shouldBe mapOf("STONE" to 10L)
    }

    test("deletion intent freezes changes and removes only its target while retaining queue and building items") {
        val f = QueueFixture()
        val city = f.start(f.city, f.first).let { CivsRules.placeSite(it, it.owner, f.second) }
            .let { CivsRules.enqueue(it, it.owner, f.second.id) }
            .let { it.copy(inventory = it.inventory!!.copy(materials = mapOf("STONE" to 7L),
                buildingMaterials = mapOf(f.first.id.toString() to mapOf("STONE" to 5L)))) }
        val pending = CivsRules.requestDeletion(city, city.owner, f.first.id)
        CivsRules.requestDeletion(pending, pending.owner, f.first.id) shouldBe pending
        shouldThrow<CivsRejection> { CivsRules.assign(pending, pending.owner, pending.workers.first().id, null, 10L) }.reason shouldBe "deletion-pending"
        val reloaded = CivsRules.validate(Gson().fromJson(Gson().toJson(pending), Settlement::class.java))
        val deleted = CivsRules.finishBuildingDeletion(reloaded, reloaded.owner)
        deleted.construction shouldBe null
        deleted.pendingDeletion shouldBe null
        deleted.building(f.first.id) shouldBe null
        deleted.constructionQueue shouldBe listOf(f.second.id)
        deleted.inventory!!.materials shouldBe mapOf("STONE" to 12L)
        deleted.inventory!!.buildingMaterials shouldBe emptyMap()
        val next = f.start(deleted, f.second)
        next.construction!!.buildingId shouldBe f.second.id
        next.constructionQueue shouldBe emptyList()
        shouldThrow<CivsRejection> { CivsRules.requestDeletion(next, next.owner, next.buildings.first().id) }.reason shouldBe "town-hall-delete"
    }

    test("retiring housing preserves existing residents and retiring legacy storage preserves its stock") {
        val f = QueueFixture()
        val house = f.first.copy(phase = BuildingPhase.ACTIVE)
        val city = f.city.copy(buildings = f.city.buildings.filterNot { it.id == house.id } + house,
            workers = f.city.workers + (1..2).map { CivWorker(UUID.randomUUID(), "Resident $it", null) })
        val deleted = CivsRules.finishBuildingDeletion(CivsRules.requestDeletion(city, city.owner, house.id), city.owner)
        deleted.workers.size shouldBe 6
        CivsRules.beds(deleted) shouldBe 5
        val warehouse = f.second.copy(type = BuildingType.WAREHOUSE, phase = BuildingPhase.ACTIVE)
        val legacy = CivsRules.validate(f.city.copy(inventory = null, stock = Stock(350, 350, 350), buildings = f.city.buildings + warehouse))
        val without = CivsRules.finishBuildingDeletion(CivsRules.requestDeletion(legacy, legacy.owner, warehouse.id), legacy.owner)
        without.stock shouldBe legacy.stock
        CivsRules.capacity(without) shouldBe 128L
    }
})

private class QueueFixture {
    private fun building(x: Int, type: BuildingType, phase: BuildingPhase = BuildingPhase.SITE) = CivBuilding(UUID.randomUUID(), type,
        if (type == BuildingType.TOWN_HALL) 2 else 1, CivPosition(x, 64, 0), 0, "test/building",
        CivBox(CivPosition(x - 1, 63, 0), CivPosition(x + 1, 67, 2)), CivPosition(x, 64, -1), phase,
        control = CivPosition(x, 64, 0))
    val first = building(30, BuildingType.HOUSE)
    val second = building(40, BuildingType.FORESTER)
    val third = building(50, BuildingType.QUARRY)
    private val hut = building(10, BuildingType.BUILDER_HUT, BuildingPhase.ACTIVE)
    val city = CivsRules.validate(Settlement(UUID.randomUUID(), UUID.randomUUID(), "Queue City", UUID.randomUUID(),
        CivPosition(0, 64, 0), 96, "nordic", Stock(),
        listOf(building(0, BuildingType.TOWN_HALL, BuildingPhase.ACTIVE), hut, first),
        List(4) { index -> CivWorker(UUID.randomUUID(), "Resident $index", if (index == 0) hut.id else null, nextDeliveryAt = 1) },
        inventory = CivInventory(CivStorageMode.PHYSICAL)))
    fun start(city: Settlement, site: CivBuilding, incremental: Boolean = true, rows: Int = 10): Settlement {
        val building = site.copy(phase = BuildingPhase.CONSTRUCTING)
        val order = CivConstruction(UUID.randomUUID(), site.id, site.level, rows, 0, ConstructionPhase.CONSTRUCTING, Stock(),
            plannedBuilding = building, batches = CivConstructionBatches(rows, 16), materials = mapOf("STONE" to rows.toLong()),
            payment = if (incremental) CivConstructionPayment() else null)
        return CivsRules.startSite(city, city.owner, building, order)
    }
}
