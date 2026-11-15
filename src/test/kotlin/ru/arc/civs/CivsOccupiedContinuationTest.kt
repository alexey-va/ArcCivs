package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import java.util.UUID

class CivsOccupiedContinuationTest : FunSpec({
    test("a stationary resident at a partial endpoint is walked around before the actual work receipt") {
        occupiedContinuation(captureBudget = 1_024) { h, route, blocker ->
            val actor = h.actor()
            val stationary = blocker.npc.location.point()
            val token = actor.plan.token
            var replanned = false
            for (tick in 1..600) {
                val before = actor.npc.location
                h.navigate()
                replanned = replanned || route.replanAttempts > 0
                withClue("tick=$tick from=$before actual=${actor.npc.location} blocker=${blocker.npc.location} " +
                    "passing=${h.owner.passingBodies(actor.npc)} original_replans=${route.replanAttempts} " +
                    "current=${h.owner.debug(h.city.id, h.workerId)}") {
                    (before.distance(actor.npc.location) <= .215001) shouldBe true
                    citizenStandingBounds(actor.npc.location, actor.npc.scale)
                        .overlaps(citizenStandingBounds(blocker.npc.location, blocker.npc.scale)) shouldBe false
                    h.owner.passingBodies(actor.npc) shouldBe false
                    blocker.npc.location.point() shouldBe stationary
                }
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                if (h.deliveries > 0) break
            }
            withClue(h.owner.debug(h.city.id, null)) {
                replanned shouldBe true
                h.deliveries shouldBe 1
                actor.plan.token shouldBe token
                h.city.workers.first { it.id == h.workerId }.completedCycles shouldBe 0
                h.owner.requireWorldWorkArrival(h.city.id, h.workerId, requireNotNull(h.work))
            }
        }
    }

    test("a sealed partial endpoint retires after bounded body-aware attempts without moving the occupant or earning work") {
        occupiedContinuation { h, route, blocker ->
            val actor = h.actor()
            val stationary = blocker.npc.location.point()
            // The authored test corridor has a full floor and enough headroom, but no side passage.
            for (x in -1..29) for (y in 64..66) for (z in listOf(-1, 1))
                h.world.getBlockAt(x, y, z).type = Material.STONE
            var retired = false
            for (tick in 1..600) {
                val before = actor.npc.location
                h.navigate()
                (before.distance(actor.npc.location) <= .215001) shouldBe true
                citizenStandingBounds(actor.npc.location, actor.npc.scale)
                    .overlaps(citizenStandingBounds(blocker.npc.location, blocker.npc.scale)) shouldBe false
                h.owner.passingBodies(actor.npc) shouldBe false
                blocker.npc.location.point() shouldBe stationary
                h.deliveries shouldBe 0
                h.observedWork() shouldBe 0L
                if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                if (actor.route !== route) { retired = true; break }
            }
            withClue(h.owner.debug(h.city.id, null)) {
                retired shouldBe true
                route.replanAttempts shouldBe 2
                route.completion?.cancelReason shouldBe "resident-blocked"
                h.phase() shouldBe CitizenPhase.PAUSED
                h.city.workers.first { it.id == h.workerId }.completedCycles shouldBe 0
                h.deliveries shouldBe 0
            }
        }
    }
})

private fun occupiedContinuation(captureBudget: Int = 256, run: (ResidentRuntime, CitizenRoute, CitizenActor) -> Unit) {
    residentRuntime(workerId = UUID(0, 71)) { h ->
        val farm = h.assignFarm()
        val blockerId = UUID(0, 72)
        h.city = h.city.copy(workers = h.city.workers + CivWorker(blockerId, "Idle neighbour", null))
        val work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(27, 64, 0),
            listOf(CivPlanBlock(CivPosition(28, 63, 0), "minecraft:stone", "minecraft:air")),
            durationMillis = 1_000, status = "working-field", focus = CivPosition(28, 63, 0))
        h.work = work
        h.workProvider = { _, worker -> work.takeIf { worker.id == h.workerId } }
        h.owner.residentStepAllowed = { npc, _, _ -> npc.uniqueId != blockerId }
        val actor = h.actor()
        actor.npc.teleport(Location(h.world, 3.5, 64.0, .5, -90f, 0f))
        h.navigationNow = 2_000
        h.owner.pulse(2_000)
        h.phase() shouldBe CitizenPhase.TO_WORK
        val route = requireNotNull(actor.route)
        route.motion.cancel()
        val partial = CitizenPacketRoute(h.world, actor.npc.location, route.target,
            { it.x in 3..28 && it.z in -3..3 && it.y == 64 }, { _, _ -> true }, { 1.0 },
            settings = CivsNavigationSettings(maximumCapturedBlocks = captureBudget))
        repeat(10_000) { if (partial.searching) partial.expand() }
        partial.searching shouldBe false
        partial.failure shouldBe null
        partial.continuation shouldBe true
        route.motion = partial
        val endpoint = requireNotNull(partial.endpoint)
        val blocker = h.actor(blockerId)
        blocker.route?.motion?.cancel()
        blocker.route = null
        blocker.npc.teleport(Location(h.world, endpoint.x, endpoint.y, endpoint.z))
        run(h, route, blocker)
    }
}
