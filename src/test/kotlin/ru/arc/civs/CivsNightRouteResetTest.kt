package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.World
import ru.arc.paper.npc.PacketNpcPose
import ru.arc.paper.npc.PacketPlayerNpc
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsNightRouteResetTest : FunSpec({
    test("physical progress home changes and receipt holds restart the thirty-second window") {
        val progress = CitizenNightHomeProgress()
        val home = nightHome()
        val at = CitizenPoint(.5, 64.0, .5)
        progress.stalled(home, at, 1_000) shouldBe false
        progress.stalled(home, at, 30_999) shouldBe false
        progress.stalled(home, at, 31_000) shouldBe true
        progress.stalled(home, at.copy(x = .8), 31_001) shouldBe false
        progress.stalled(home, at.copy(x = .8), 61_000) shouldBe false
        progress.stalled(home.copy(id = UUID.randomUUID()), at, 61_001) shouldBe false
        progress.clear() // Pending native receipts, daylight and completed sleep use this same reset.
        progress.stalled(home, at, 120_000) shouldBe false
        progress.stalled(home, at, 149_999) shouldBe false
        progress.stalled(home, at, 150_000) shouldBe true
    }

    test("a stationary night route is released after thirty seconds then a fresh route physically reaches home") {
        residentRuntime { h ->
            val home = nightHome().copy(anchor = CivPosition(29, 64, 0), entrance = CivPosition(29, 64, 0),
                bounds = CivBox(CivPosition(27, 64, -2), CivPosition(31, 68, 2)))
            h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings + home,
                workers = h.city.workers.map { it.copy(homeId = home.id) }))
            for (x in -1..3) for (z in -1..1) h.world.loadChunk(x, z)
            h.world.time = 13_000
            h.navigationNow = 2_000
            h.holdNavigation = true
            h.owner.pulse(2_000)
            val npc = h.owner.collisionActors().single()
            val origin = requireNotNull(h.owner.residentFeet(h.workerId))
            val route = requireNotNull(h.routeIdentity())
            val before = h.city
            repeat(100) { h.navigate(0) }
            h.owner.pulse(31_999)
            h.routeIdentity() shouldBe route
            h.owner.pulse(32_000)
            h.routeIdentity() shouldBe null
            h.owner.residentFeet(h.workerId) shouldBe origin
            h.city shouldBe before
            h.deliveries shouldBe 0
            h.unloads shouldBe 0

            h.owner.pulse(33_000)
            val fresh = requireNotNull(h.routeIdentity())
            (fresh === route) shouldBe false
            h.navigationNow = 33_000
            h.releaseNavigation()
            var slept = false
            for (tick in 1..800) {
                val previous = requireNotNull(h.owner.residentFeet(h.workerId))
                h.navigate()
                if (tick % 20 == 0) h.owner.pulse(33_000 + tick * 50L)
                val actual = requireNotNull(h.owner.residentFeet(h.workerId))
                withClue("tick=$tick previous=$previous actual=$actual ${h.navigationState()}") {
                    (previous.distance(actual) <= .118251) shouldBe true
                }
                if (npc.pose == PacketNpcPose.SLEEPING) { slept = true; break }
            }
            withClue(h.navigationState()) { slept shouldBe true }
            val feet = requireNotNull(h.owner.residentFeet(h.workerId))
            home.bounds.contains(feet.blockX, feet.blockY, feet.blockZ) shouldBe true
            h.city shouldBe before
            h.deliveries shouldBe 0
            h.unloads shouldBe 0
            h.steps shouldBe emptyList()
        }
    }

    test("pending harm life collection and transfer receipts cannot be reset or consume recovery budget") {
        MockBukkitTestRuntime.open().use { paper ->
            val targetWorld = mockk<World>()
            every { targetWorld.time } returns 13_000
            val npc = mockk<PacketPlayerNpc>(relaxed = true)
            every { npc.world } returns targetWorld
            every { npc.location } returns Location(targetWorld, .5, 64.0, .5)
            val life = mockk<CivsCitizenLife>()
            val logistics = mockk<CivsCitizenLogistics>()
            var lifePending = false
            var collectedPending = false
            every { life.residentLifePending(any()) } answers { lifePending }
            every { logistics.collectedPending(any()) } answers { collectedPending }
            val worker = UUID.randomUUID()
            val runtime = CitizenRuntime(paper.createSimplePlugin("NightPendingTest")) { emptyList() }
            runtime.recoveryBudget = 2
            val plan = CitizenPlan(CitizenToken(null, 0, 0, null), CivPosition(0, 64, 0), null, null, 0, "waiting")
            val actor = CitizenActor(UUID.randomUUID(), worker, UUID.randomUUID(), npc, plan)
            actor.sheltering = true
            actor.shelter = CivPosition(29, 64, 0)
            val routes = CivsCitizenRoutes(runtime, { mockk() }, { mockk() }, { mockk() }, { mockk() },
                { mockk() }, { logistics }, { life }, mockk(), mockk(), { 1.0 }, { _, _, _ -> false },
                { 0L }, { _, _, _ -> }, { _, _ -> })
            routes.canResetNightShelter(actor) shouldBe true
            for (hold in listOf("harm", "life", "collection", "transfer")) {
                when (hold) {
                    "harm" -> runtime.pendingHarm += worker
                    "life" -> lifePending = true
                    "collection" -> collectedPending = true
                    else -> actor.cycle = CitizenCycle(plan.token, 0).also { cycle ->
                        cycle.start(); cycle.arrive(0); cycle.arrive(0); cycle.work(0); cycle.arrive(0)
                        requireNotNull(cycle.beginDelivery(0))
                    }
                }
                routes.canResetNightShelter(actor) shouldBe false
                routes.resetNightShelter(actor, 120_000) shouldBe false
                runtime.recoveryBudget shouldBe 2
                actor.shelter shouldBe CivPosition(29, 64, 0)
                runtime.pendingHarm.clear()
                lifePending = false
                collectedPending = false
                actor.cycle = plan.cycle()
            }
        }
    }
})

private fun nightHome() = CivBuilding(UUID.randomUUID(), BuildingType.HOUSE, 1, CivPosition(12, 64, 0),
    0, "house1", CivBox(CivPosition(10, 64, -2), CivPosition(14, 68, 2)), CivPosition(12, 64, 0), BuildingPhase.ACTIVE)
