package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsLabGrowthTest : FunSpec({
    test("a diagnostic run belongs to one exact city owner and world and expires without renewal") {
        val city = roadAdmissionCity()
        val other = city.copy(id = UUID(300, 1))
        var at = 10_000L
        val growth = CivsLabGrowth { at }

        growth.cityId() shouldBe null
        growth.start(city) shouldBe true
        growth.active(city) shouldBe true
        growth.active(other) shouldBe false
        growth.active(city.copy(worldId = UUID(300, 2))) shouldBe false
        growth.active(city.copy(owner = UUID(300, 3))) shouldBe false
        shouldThrow<CivsRejection> { growth.start(other) }.reason shouldBe "lab-growth-other-city-active"
        growth.stop(other.id) shouldBe false

        at = 609_999
        growth.start(city) shouldBe false
        growth.active(city) shouldBe true
        at++
        growth.active(city) shouldBe false
        growth.start(other) shouldBe true
        growth.stop(other.id) shouldBe true
        growth.cityId() shouldBe null
    }

    test("bounded physical work timing keeps shorter actions and still applies the ordinary hunger penalty") {
        CivsLabGrowth.workMillis(0) shouldBe 0L
        CivsLabGrowth.workMillis(300) shouldBe 300L
        CivsLabGrowth.workMillis(1_500) shouldBe 500L
        CivsLabGrowth.workMillis(3_000) shouldBe 750L
        CivsLabGrowth.workMillis(4_000) shouldBe 1_000L
        val hungry = roadAdmissionCity().workers.single().copy(hunger = 6)
        CivsResidents.workDuration(hungry, CivsLabGrowth.workMillis(CivsRules.BUILDER_SESSION_INTERVAL)) shouldBe 1_000L
    }

    test("only an exact physical paid construction order qualifies for builder timing") {
        val city = roadAdmissionCity()
        val order = CivConstruction(UUID(300, 4), city.buildings.last().id, 1, 1, 0,
            ConstructionPhase.CONSTRUCTING, Stock(), payment = CivConstructionPayment())
        val paid = city.copy(construction = order)
        CivsLabGrowth.paidConstruction(paid, order.id) shouldBe true
        CivsLabGrowth.paidConstruction(city, order.id) shouldBe false
        CivsLabGrowth.paidConstruction(paid, UUID(300, 5)) shouldBe false
        CivsLabGrowth.paidConstruction(paid.copy(inventory = null), order.id) shouldBe false
        CivsLabGrowth.paidConstruction(paid.copy(construction = order.copy(payment = null)), order.id) shouldBe false
    }

    test("only a selected reserved authored row gets two ticks until the run stops or expires") {
        val base = roadAdmissionCity()
        val building = base.buildings.last()
        val order = CivConstruction(UUID(300, 7), building.id, 1, 2, 0, ConstructionPhase.PLACING, Stock(),
            plannedBuilding = building, batches = CivConstructionBatches(2, 16), payment = CivConstructionPayment(0))
        val city = base.copy(construction = order)
        val plan = CivPlacementPlan(order.id, city.id, city.worldId, building, emptyList())
        val batch = CivBuildBatch(building.anchor, building.anchor)
        var at = 10_000L
        val growth = CivsLabGrowth { at }
        fun interval(current: Settlement = city, capture: CivPlacementPlan = plan, row: CivBuildBatch? = batch) =
            CivsLabGrowth.placementTicks(current, order, capture, row, growth::active)

        interval() shouldBe 5
        growth.start(city)
        interval() shouldBe 2
        interval(city.copy(id = UUID(300, 8))) shouldBe 5
        interval(city.copy(autonomy = null)) shouldBe 5
        interval(city.copy(inventory = null)) shouldBe 5
        interval(city.copy(construction = order.copy(payment = CivConstructionPayment()))) shouldBe 5
        interval(city.copy(construction = order.copy(automaticPreparationBatches = 1))) shouldBe 5
        interval(capture = plan.copy(adminPlacement = true)) shouldBe 5
        interval(row = batch.copy(terrainStart = 0, terrainCount = 1)) shouldBe 5
        interval(row = null) shouldBe 5

        growth.stop(city.id)
        interval() shouldBe 5
        growth.start(city)
        at += 600_000
        interval() shouldBe 5
    }

    test("the faster observed builder schedules only the next interval and owes the same paid row") {
        val base = roadAdmissionCity()
        val site = base.buildings.last().copy(type = BuildingType.HOUSE, phase = BuildingPhase.SITE, transit = null)
        val city = base.copy(buildings = base.buildings.dropLast(1) + site)
        val order = CivConstruction(UUID(300, 6), site.id, 1, 65, 0, ConstructionPhase.CONSTRUCTING, Stock(),
            batches = CivConstructionBatches(65, 1), materials = mapOf("COBBLESTONE" to 1L),
            payment = CivConstructionPayment())
        val working = CivsRules.startSite(city, city.owner, site, order)
        val worker = working.workers.single()
        val at = worker.nextDeliveryAt
        fun finish(now: Long, fast: Boolean) = CivsRules.deliver(working, city.owner, worker.id,
            requireNotNull(worker.assignment), worker.completedCycles, at, now, acceleratedBuilder = fast)

        shouldThrow<CivsRejection> { finish(at - 1, fast = true) }.reason shouldBe "delivery-too-early"
        val ordinary = finish(at, fast = false)
        val faster = finish(at, fast = true)
        faster.workers.single().nextDeliveryAt shouldBe at + 500
        ordinary.workers.single().nextDeliveryAt shouldBe at + 2_000
        faster.copy(workers = faster.workers.map { it.copy(nextDeliveryAt = at + 2_000) }) shouldBe ordinary
        faster.construction?.workCredit shouldBe 64
        CivsEquipment.usableCount(faster.workers.single(), "COBBLESTONE") shouldBe 64

        val paid = CivsRules.payBatch(faster, city.owner, order.id, 0, mapOf("COBBLESTONE" to 1L))
        CivsEquipment.usableCount(paid.workers.single(), "COBBLESTONE") shouldBe 63
        val committed = CivsRules.completeBatch(paid, city.owner, order.id)
        committed.construction?.workDone shouldBe 1
        committed.construction?.workCredit shouldBe 63
    }
})
