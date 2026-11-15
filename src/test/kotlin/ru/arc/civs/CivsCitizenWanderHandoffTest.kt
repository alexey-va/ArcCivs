package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import java.util.UUID

class CivsCitizenWanderHandoffTest : FunSpec({
    test("a clear straight local walk has no empty handoff frame or double stride and stops at its leg limit") {
        residentRuntime(workerId = UUID(0, 42)) { h ->
            h.wanderCorridor(4)
            val actor = h.actor()
            val walk = h.readyLocalWalk(actor, 4.5, .5, legLimit = 3)
            val original = h.city
            var ticks = 0
            while (actor.route != null && ticks < 40) {
                val before = actor.npc.location
                h.navigate()
                ticks++
                withClue("tick=$ticks legs=${walk.legs} before=$before actual=${actor.npc.location}") {
                    (before.distance(actor.npc.location) in .000001.. .118251) shouldBe true
                    actor.npc.location.x shouldBe 4.5
                    actor.npc.location.y shouldBe 64.0
                }
            }
            ticks shouldBe 27 // Three unit legs, each eight full strides and one remainder.
            walk.legs shouldBe 3
            walk.maxHandoffMillis shouldBe 50L
            actor.route shouldBe null
            actor.npc.location.point() shouldBe CitizenPoint(4.5, 64.0, 3.5)
            h.navigate()
            actor.npc.location.point() shouldBe CitizenPoint(4.5, 64.0, 3.5)
            h.city shouldBe original
            h.requireNoWanderCredit()
        }
    }

    test("a newly sealed endpoint rejects another local leg without moving twice or granting work") {
        residentRuntime(workerId = UUID(0, 42)) { h ->
            h.wanderCorridor(4)
            val actor = h.actor()
            val walk = h.readyLocalWalk(actor, 4.5, .5, legLimit = 3)
            repeat(8) { h.navigate() }
            val before = actor.npc.location
            // The remaining current edge is clear, but every next destination is now enclosed.
            for (y in 64..65) for (z in listOf(0, 2)) h.world.getBlockAt(4, y, z).type = Material.STONE
            h.navigate()
            (before.distance(actor.npc.location) in .000001.. .118251) shouldBe true
            actor.npc.location.point() shouldBe CitizenPoint(4.5, 64.0, 1.5)
            walk.legs shouldBe 1
            actor.route shouldBe null
            h.navigate()
            actor.npc.location.point() shouldBe CitizenPoint(4.5, 64.0, 1.5)
            h.requireNoWanderCredit()
        }
    }

    test("simultaneous local arrivals retain the shared two handoff budget and admit the waiting resident next tick") {
        residentRuntime(workerId = UUID(0, 42)) { h ->
            val neighbours = listOf(UUID(0, 43), UUID(0, 44))
            h.city = h.city.copy(workers = h.city.workers + neighbours.map { CivWorker(it, "Neighbour", null) })
            h.owner.pulse(2_000)
            h.wanderCorridor(4)
            h.wanderCorridor(-5)
            val actors = listOf(h.actor()) + neighbours.map(h::actor)
            val origins = listOf(4.5 to .5, 4.5 to 4.5, -4.5 to .5)
            val walks = actors.zip(origins).map { (actor, origin) ->
                h.readyLocalWalk(actor, origin.first, origin.second, legLimit = 3)
            }
            val originalRoutes = actors.map { requireNotNull(it.route) }
            repeat(8) { h.navigate() }
            val beforeArrival = actors.map { it.npc.location }
            h.navigate()
            walks.map { it.legs } shouldBe listOf(1, 1, 1)
            actors.indices.count { actors[it].route !== originalRoutes[it] } shouldBe 2
            actors.zip(beforeArrival).forEach { (actor, before) ->
                (before.distance(actor.npc.location) in .000001.. .118251) shouldBe true
            }
            val waiting = actors.indices.single { actors[it].route === originalRoutes[it] }
            val atEndpoint = actors[waiting].npc.location.point()
            h.navigate()
            actors.indices.count { actors[it].route !== originalRoutes[it] } shouldBe 3
            actors[waiting].npc.location.point() shouldBe atEndpoint
            h.navigate()
            (actors[waiting].npc.location.point().distanceSquared(atEndpoint) in .000001.. .014) shouldBe true
            walks[waiting].maxHandoffMillis shouldBe 100L
            h.requireNoWanderCredit()
        }
    }
})

private fun ResidentRuntime.wanderCorridor(x: Int) {
    for (wall in listOf(x - 1, x + 1)) for (y in 64..65) for (z in -1..8)
        world.getBlockAt(wall, y, z).type = Material.STONE
}

private fun ResidentRuntime.readyLocalWalk(actor: CitizenActor, x: Double, z: Double, legLimit: Int): CitizenWanderWalk {
    actor.route?.motion?.cancel()
    val from = Location(world, x, 64.0, z, 0f, 0f)
    actor.npc.teleport(from)
    val target = from.clone().add(0.0, 0.0, 1.0)
    val home = city.buildings.single()
    navigationNow = 2_000
    val walk = CitizenWanderWalk(home, true, CivRouteTerritory(city), actor.plan.token, 2_000, legLimit, null)
    val motion = CitizenPacketRoute(world, from, target, { true }, { _, _ -> true }, { 1.0 })
    motion.readyWanderStep()
    actor.route = CitizenRoute(from.point(), target,
        CitizenChunks.between(from.blockX, from.blockZ, target.blockX, target.blockZ),
        2_000, 3_000, emptySet(), motion, null, CivPosition(target.blockX, target.blockY, target.blockZ),
        idleAnchor = home.entrance, wanderWalk = walk)
    return walk
}

private fun ResidentRuntime.requireNoWanderCredit() {
    deliveries shouldBe 0
    pickups shouldBe 0
    unloads shouldBe 0
    observedWork() shouldBe 0L
    city.workers.all { it.completedCycles == 0L } shouldBe true
    steps shouldBe emptyList()
    pendingNavigationWorkers shouldBe 0
}
