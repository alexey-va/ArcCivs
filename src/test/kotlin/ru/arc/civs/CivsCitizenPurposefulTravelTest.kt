package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsCitizenPurposefulTravelTest : FunSpec({
    test("a blocked return trip retains completed observed work and still requires real delivery arrival") {
        val cycle = CitizenCycle(CitizenToken(UUID.randomUUID(), 0, 1, null), 1_000)
        cycle.start()
        cycle.arrive(0)
        cycle.arrive(0)
        cycle.work(1_000)
        cycle.phase shouldBe CitizenPhase.RETURNING
        cycle.pause("route-unreachable", 2_000, false)
        cycle.retry(7_000)
        cycle.phase shouldBe CitizenPhase.RETURNING
        cycle.observedWorkMillis shouldBe 1_000
        cycle.beginDelivery(7_000) shouldBe null
        cycle.arrive(8_000)
        val delivery = requireNotNull(cycle.beginDelivery(8_000))
        cycle.acceptsCompletion(delivery) shouldBe true
        cycle.beginDelivery(8_000) shouldBe null
    }

    for (reason in listOf("building-unavailable", "waiting-materials", "construction-paused"))
        test("a real supply or output trip stays purposeful while its work plan is $reason") {
            val plan = CitizenPlan(CitizenToken(UUID.randomUUID(), 0, 1, null), CivPosition(0, 64, 0),
                CivPosition(10, 64, 0), BuildingType.FORESTER, 1_000, reason)
            citizenWorkingTravel(plan, CitizenPhase.IDLE, false, purposefulDetour = true) shouldBe true
            citizenWorkingTravel(plan, CitizenPhase.IDLE, false) shouldBe false
            citizenWorkingTravel(plan, CitizenPhase.IDLE, true, purposefulDetour = true) shouldBe false
        }
})
