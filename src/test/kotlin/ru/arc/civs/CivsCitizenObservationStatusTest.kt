package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import java.util.Locale
import java.util.concurrent.CompletableFuture

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

    test("raw held search remains diagnostic while public nameplates show waiting without replanning") {
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
                plainText(text.status(null, key, hasWork = true)) shouldBe "Ждёт"
                plainText(text.status(null, key)) shouldBe "Ждёт"
                plainText(text.render("workers.status.$key")) shouldBe label
                for (locale in listOf("ru", "en")) {
                    text.keys(locale).contains("workers.status.$key") shouldBe true
                    text.keys(locale).contains("building-status.$key") shouldBe true
                }
            }
            plainText(civsNpcHologramText(text, h.owner.hologramSnapshots().single())).lines()[2] shouldBe "Ждёт"
            h.owner.status(h.workerId) shouldBe "workers.status.route-searching"
            h.routeIdentity() shouldBe route
            h.city shouldBe city
            h.workPlans shouldBe workPlans
            h.observedWork() shouldBe 0L
        }
    }

    test("public captions distinguish waiting, ordinary travel, guard duty and work in both languages") {
        val text = CivsText.fromYaml(civsLocaleSource())
        for ((locale, waiting, walking, guarding, working) in listOf(
            listOf("ru", "Ждёт", "Идёт", "На посту", "Работает"),
            listOf("en", "Waiting", "Walking", "On watch", "Working"),
        )) {
            for (state in listOf("route-preparing", "route-searching", "route-blocked", "route-stopped",
                "searching-work", "workstation-unavailable", "mine-waiting", "paused", "waiting", "idle")) {
                plainText(text.status(null, state, locale = locale, hasWork = true)) shouldBe waiting
            }
            for (state in listOf("walking", "moving-to-work", "moving-to-storage", "carrying-cargo", "patrolling")) {
                plainText(text.status(null, state, locale = locale, hasWork = true)) shouldBe walking
            }
            plainText(text.status(null, "guarding", locale = locale, hasWork = true)) shouldBe guarding
            for (state in listOf("working", "working-field", "building", "placing")) {
                plainText(text.status(null, state, locale = locale, hasWork = true)) shouldBe working
            }
        }
    }

    test("an assigned worker awaiting work leaves the building waiting") {
        serviceScenario { h ->
            val home = h.city.buildings.last()
            val forester = home.copy(type = BuildingType.FORESTER, control = CivPosition(10, 63, 0),
                schematicId = CivsBlueprints.schematicId(h.city.style, BuildingType.FORESTER, 1))
            val worker = h.city.workers.first().copy(assignment = forester.id)
            val city = h.city.copy(buildings = listOf(h.city.buildings.first(), forester),
                workers = listOf(worker) + h.city.workers.drop(1))
            every { h.store.load() } returns CompletableFuture.completedFuture(listOf(city))
            every { h.citizens.status(worker.id) } returns "workers.status.waiting"
            every { h.player.locale() } returns Locale.forLanguageTag("ru-RU")
            h.start()
            h.service.workerStatus(worker.id) shouldBe "workers.status.waiting"
            val status = h.service.buildingStatus(forester.id)
            status shouldBe "building-status.waiting"
            val text = CivsText.fromYaml(civsLocaleSource())
            plainText(text.buildingStatus(h.player, city, forester, status)) shouldBe "Ждёт"
        }
    }
})
