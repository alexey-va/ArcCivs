package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe

class CivsCitizenUpkeepMaterialsTest : FunSpec({
    test("a retained native upkeep with an empty bag returns to its hut and waits for the supply receipt") {
        residentRuntime { h ->
            val selected = h.assignRoadUpkeep().copy(inputs = mapOf("COBBLESTONE" to 1L), durationMillis = 3_000)
            h.work = selected
            h.city = h.city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL))
            val before = h.city
            h.workReady = false
            h.navigationNow = 2_000
            h.owner.collisionActors().single().teleport(selected.standing.location(h.world))
            h.owner.pulse(2_000)
            h.phase() shouldBe CitizenPhase.TO_STORAGE
            for (tick in 1..400) {
                if (h.supplyRequests == 1) break
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
            }
            withClue(h.navigationState()) { h.supplyRequests shouldBe 1 }
            val actor = h.owner.javaClass.getDeclaredField("actors").run {
                isAccessible = true
                requireNotNull((get(h.owner) as Map<*, *>)[h.workerId]) as CitizenActor
            }
            actor.plan.worldWork shouldBe selected
            actor.plan.token.completedCycles shouldBe selected.cycle
            h.phase() shouldBe CitizenPhase.TO_STORAGE
            (actor.supplyPickup != null) shouldBe true
            h.observedWork() shouldBe 0L
            h.deliveries shouldBe 0
            h.city shouldBe before
            repeat(3) {
                h.navigationNow = requireNotNull(h.navigationNow) + 1_000
                h.owner.pulse(requireNotNull(h.navigationNow))
                h.phase() shouldBe CitizenPhase.TO_STORAGE
                h.supplyRequests shouldBe 1
                h.deliveries shouldBe 0
                h.city shouldBe before
            }
        }
    }

    test("other captured world work still goes directly to its selected workplace") {
        residentRuntime { h ->
            val selected = h.assignRoadUpkeep().copy(upkeep = null, status = "working-field")
            val plan = CitizenPlan(CitizenToken(selected.buildingId, selected.cycle, 0, null),
                CivPosition(20, 64, 0), selected.standing, BuildingType.FARM, 3_000, null,
                completeAtWorkplace = true, worldWork = selected, suppliesNeeded = true)
            val cycle = plan.cycle()
            cycle.start()
            cycle.phase shouldBe CitizenPhase.TO_WORK
        }
    }
})
