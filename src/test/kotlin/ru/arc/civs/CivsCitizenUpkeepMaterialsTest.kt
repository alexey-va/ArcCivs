package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CivsCitizenUpkeepMaterialsTest : FunSpec({
    test("a builder already in work reach reports missing upkeep materials until its native hut payment arrives") {
        residentRuntime { h ->
            val selected = h.assignRoadUpkeep().copy(inputs = mapOf("COBBLESTONE" to 1L), durationMillis = 3_000)
            h.work = selected
            h.city = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL))
            val before = h.city
            h.workReady = false
            val actor = h.owner.collisionActors().single()
            actor.teleport(selected.standing.location(h.world))
            val atWork = actor.location
            for (now in listOf(2_000L, 3_000L, 4_000L)) {
                h.owner.pulse(now)
                h.owner.status(h.workerId) shouldBe "workers.status.waiting-materials"
                h.phase() shouldBe CitizenPhase.TO_WORK
                h.routeIdentity() shouldBe null
                h.observedWork() shouldBe 0L
                h.deliveries shouldBe 0
                h.supplyRequests shouldBe 0
                actor.location shouldBe atWork
                h.city shouldBe before
            }
            // The existing input authority may be satisfied by a real local receipt;
            // a carried-item pickup is not required by upkeep's payment contract.
            h.workReady = true
            h.owner.pulse(5_000)
            h.phase() shouldBe CitizenPhase.WORKING
            h.owner.status(h.workerId) shouldBe "workers.status.repairing-road"
            h.observedWork() shouldBe 0L
            h.deliveries shouldBe 0
            for (now in listOf(6_000L, 7_000L, 8_000L)) h.owner.pulse(now)
            h.deliveries shouldBe 1
            h.phase() shouldBe CitizenPhase.DELIVERING
            h.city shouldBe before
            h.supplyRequests shouldBe 0
        }
    }
})
