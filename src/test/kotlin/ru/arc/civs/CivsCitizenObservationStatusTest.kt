package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material

class CivsCitizenObservationStatusTest : FunSpec({
    test("travel captions distinguish preparation contact ready movement and stopped segments") {
        val motion = mockk<CitizenPacketRoute>(relaxed = true) {
            every { failure } returns null
            every { navigating } returns true
        }
        val route = mockk<CitizenRoute>(relaxed = true) {
            every { completion } returns null
            every { preparationRetryAt } returns null
            every { bodyBlockedSince } returns null
        }
        every { route.motion } returns motion
        val travel = "moving-to-storage"
        citizenObservedTravelStatus(travel, null) shouldBe "route-preparing"
        for (phase in listOf("queued", "capturing", "searching")) {
            every { motion.preparationPhase } returns phase
            every { motion.searching } returns true
            citizenObservedTravelStatus(travel, route) shouldBe "route-searching"
        }
        every { motion.searching } returns false
        for (intent in listOf(travel, "moving-to-work", "moving-to-town-hall", "moving-to-tavern",
            "moving-to-pickup", "carrying-cargo", "returning-output", "mine-descending", "mine-returning")) {
            citizenObservedTravelStatus(intent, route) shouldBe intent
        }
        every { motion.waitingForCollision } returns true
        citizenObservedTravelStatus(travel, route) shouldBe "route-blocked"
        every { motion.waitingForCollision } returns false
        every { route.nativeCollisionTicks } returns 1
        citizenObservedTravelStatus(travel, route) shouldBe "route-blocked"
        every { route.nativeCollisionTicks } returns 0
        citizenObservedTravelStatus(travel, route, yielding = true) shouldBe "route-blocked"
        every { motion.turning } returns true
        citizenObservedTravelStatus(travel, route) shouldBe "route-preparing"
        every { motion.turning } returns false
        every { motion.finished } returns true
        citizenObservedTravelStatus(travel, route) shouldBe "route-stopped"
        every { motion.continuation } returns true
        citizenObservedTravelStatus(travel, route) shouldBe "route-preparing"
        every { motion.continuation } returns false
        every { route.intermediate } returns true
        citizenObservedTravelStatus(travel, route) shouldBe "route-preparing"
        every { route.intermediate } returns false
        every { motion.failure } returns "no-path"
        citizenObservedTravelStatus(travel, route) shouldBe "route-unreachable"
        every { route.preparationRetryAt } returns 5_000L
        citizenObservedTravelStatus(travel, route) shouldBe "route-preparing"
    }

    test("work pauses and pending native receipts retain priority over travel observation") {
        val route = mockk<CitizenRoute>()
        for (activity in listOf("working", "building", "eating", "serving-guests", "collecting-supplies",
            "loading-cargo", "delivering", "mine-waiting", "storage-full", "waiting-inputs", "paused", "injury-pending")) {
            citizenObservedTravelStatus(activity, route, yielding = true) shouldBe activity
        }
        verify { route wasNot Called }
    }

    test("status and hologram share the held-search caption without starting work or another plan") {
        residentRuntime { h ->
            h.assignFarm()
            val hall = h.city.buildings.first().copy(control = h.city.buildings.first().entrance)
            h.city = h.city.copy(buildings = h.city.buildings.map { if (it.id == hall.id) hall else it })
            val control = requireNotNull(hall.control)
            h.world.getBlockAt(control.x, control.y, control.z).type = Material.BARREL
            h.owner.pulse(2_000)
            h.owner.collisionActors().single().teleport(Location(h.world, 21.5, 64.0, .5))
            h.foodSource = hall.id
            h.holdNavigation = true
            h.owner.pulse(3_000)
            val route = requireNotNull(h.routeIdentity())
            val workPlans = h.workPlans
            val city = h.city
            repeat(3) {
                h.owner.status(h.workerId) shouldBe "workers.status.route-searching"
                h.owner.hologramSnapshots().single().status shouldBe "workers.status.route-searching"
            }
            h.routeIdentity() shouldBe route
            h.city shouldBe city
            h.workPlans shouldBe workPlans
            h.supplyRequests shouldBe 0
            h.observedWork() shouldBe 0L
            val text = CivsText.fromYaml(civsLocaleSource())
            for ((key, label) in mapOf("route-preparing" to "Готовится идти", "route-searching" to "Ищет путь",
                "route-blocked" to "Ждёт свободный проход", "route-stopped" to "Остановился")) {
                plainText(text.status(null, key)) shouldBe label
                for (locale in listOf("ru", "en")) {
                    text.keys(locale).contains("workers.status.$key") shouldBe true
                    text.keys(locale).contains("building-status.$key") shouldBe true
                }
            }
        }
    }
})
