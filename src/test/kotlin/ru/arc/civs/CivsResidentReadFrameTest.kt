package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsResidentReadFrameTest : FunSpec({
    test("resident pulses detach actual feet and reuse unchanged rows while receipt checks stay live") {
        residentRuntime { h ->
            val owner = h.owner
            val cityId = h.city.id
            val workerId = h.workerId
            h.world.time = 6_000
            val actor = h.actor()
            val original = owner.residentReadFrame()
            val first = original.rows.getValue(workerId)
            val absentWorker = UUID.randomUUID()
            original.courierPosition(cityId, absentWorker) shouldBe null
            original.courierUnavailable(cityId, absentWorker) shouldBe false
            original.sequence shouldBe 1L
            original.observedAtMillis shouldBe 1_000L
            first.cityId shouldBe cityId
            first.worldId shouldBe h.world.uid
            first.workerId shouldBe workerId
            first.actorId shouldBe actor.instanceId
            first.actual shouldBe actor.npc.location.point()
            first.phase shouldBe actor.cycle.phase
            first.assignmentId shouldBe h.city.workers.single().assignment
            first.intent shouldBe actor.plan.token
            original.courierPosition(cityId, workerId) shouldBe owner.courierPosition(cityId, workerId)
            original.courierUnavailable(cityId, workerId) shouldBe false
            original.courierPosition(UUID.randomUUID(), workerId) shouldBe null
            shouldThrow<UnsupportedOperationException> { (original.rows as MutableMap<UUID, CivResidentRead>).clear() }

            val changedFeet = actor.npc.location.clone().add(2.25, 0.0, 0.0)
            actor.npc.teleport(changedFeet)
            actor.supplyPickup = CitizenSupplyPickup(UUID.randomUUID(), h.city.buildings.single().id, resumeWork = false)
            owner.courierUnavailable(cityId, workerId) shouldBe true
            owner.courierPosition(cityId, workerId) shouldBe citizenCourierPosition(changedFeet.point())
            (owner.residentReadFrame() === original) shouldBe true
            original.courierUnavailable(cityId, workerId) shouldBe false
            original.courierPosition(cityId, workerId) shouldBe citizenCourierPosition(requireNotNull(first.actual))

            owner.pulse(2_000)
            val observed = owner.residentReadFrame()
            observed.sequence shouldBe 2L
            observed.observedAtMillis shouldBe 2_000L
            observed.rows.getValue(workerId).actual shouldBe changedFeet.point()
            observed.courierUnavailable(cityId, workerId) shouldBe true
            owner.pulse(3_000)
            val unchanged = owner.residentReadFrame()
            unchanged.sequence shouldBe 3L
            (unchanged.rows === observed.rows) shouldBe true
            (unchanged.rows.getValue(workerId) === observed.rows.getValue(workerId)) shouldBe true

            owner.close()
            owner.residentReadFrame().rows shouldBe emptyMap()
            observed.rows.getValue(workerId).actual shouldBe changedFeet.point()
        }
    }

    test("courier estimates retain the existing fractional and negative grid rounding") {
        citizenCourierPosition(CitizenPoint(-0.25, 68.9375, -1.01)) shouldBe CivPosition(-1, 69, -2)
    }
})
