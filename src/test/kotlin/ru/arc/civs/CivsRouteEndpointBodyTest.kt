package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import java.util.UUID

class CivsRouteEndpointBodyTest : FunSpec({
    for (partial in listOf(false, true)) test("soft transit cannot enter the occupant of a planned endpoint partial=$partial") {
        residentRuntime(workerId = UUID(0, 1)) { h ->
            val otherId = UUID(0, 2)
            h.city = h.city.copy(workers = h.city.workers + CivWorker(otherId, "Neighbour", null))
            h.owner.pulse(2_000)
            val actor = h.actor()
            val other = h.actor(otherId)
            val routes = endpointBodyOwner(h, "routes") as CivsCitizenRoutes
            val collisions = endpointBodyOwner(h, "collisions") as CivsCitizenCollisions
            routes.cancelRoute(actor)
            routes.cancelRoute(other)

            val end = Location(h.world, 8.5, 64.0, .5)
            val before = end.clone().add(-.65, 0.0, 0.0)
            val enters = end.clone().add(-.55, 0.0, 0.0)
            actor.npc.teleport(before)
            other.npc.teleport(end)
            val motion = mockk<CitizenPacketRoute>(relaxed = true) {
                every { endpoint } returns end.point()
                every { waitingForCollision } returns true
                every { continuation } returns partial
            }
            val authored = CivPosition(8, 64, if (partial) 2 else 0)
            val route = CitizenRoute(before.point(), authored.location(h.world), CitizenChunks.between(7, 0, 8, authored.z), 2_000,
                12_000, emptySet(), motion, null, authored).also { it.bodyPassUntil = Long.MAX_VALUE }
            actor.route = route

            collisions.routeMoveAllowed(actor, before, enters) shouldBe false
            actor.collisionResident shouldBe otherId
            collisions.endpointResident(actor, route) shouldBe other
            actor.npc.location.point() shouldBe before.point()
            route.completion shouldBe null

            // Even a legal move out of an old overlap cannot stop with the endpoint still occupied.
            route.bodyPassUntil = 0
            other.npc.teleport(end.clone().add(.2, 0.0, 0.0))
            val overlap = end.clone().add(.1, 0.0, 0.0)
            actor.npc.teleport(overlap)
            citizenResidentStepClear(actor.npc, overlap, end,
                listOf(citizenStandingBounds(other.npc.location, other.npc.scale))) shouldBe true
            collisions.routeMoveAllowed(actor, overlap, end) shouldBe false
            route.bodyPassUntil = Long.MAX_VALUE

            // The same resident can still be passed on a different part of the route.
            val middle = Location(h.world, 4.5, 64.0, .5)
            other.npc.teleport(middle)
            val approach = middle.clone().add(-.65, 0.0, 0.0)
            actor.npc.teleport(approach)
            collisions.endpointResident(actor, route) shouldBe null
            collisions.routeMoveAllowed(actor, approach, middle.clone().add(-.55, 0.0, 0.0)) shouldBe true
            route.completion shouldBe null
            h.deliveries shouldBe 0
            h.unloads shouldBe 0
        }
    }

    test("a coincident resident waiting for food tries the fourth native escape direction") {
        residentRuntime(workerId = UUID(0, 1)) { h ->
            val others = (2L..5L).map { CivWorker(UUID(0, it), "Resident $it", null) }
            h.city = h.city.copy(workers = h.city.workers + others)
            repeat(3) { h.owner.pulse(2_000L + it * 1_000) }
            val routes = endpointBodyOwner(h, "routes") as CivsCitizenRoutes
            val collisions = endpointBodyOwner(h, "collisions") as CivsCitizenCollisions
            val actor = h.actor()
            val neighbours = others.map { h.actor(it.id) }
            (listOf(actor) + neighbours).forEach(routes::cancelRoute)
            val center = Location(h.world, 4.5, 64.0, 4.5)
            actor.npc.teleport(center)
            val offsets = listOf(0.0 to 0.0, -.649 to 0.0, .034 to .193, .035 to -.456)
            neighbours.zip(offsets).forEach { (resident, offset) ->
                resident.npc.teleport(center.clone().add(offset.first, 0.0, offset.second))
            }
            val bodies = neighbours.map { citizenStandingBounds(it.npc.location, it.npc.scale) }
            for ((x, z) in listOf(-.05 to 0.0, 0.0 to -.05, 0.0 to .05))
                citizenResidentStepClear(actor.npc, center, center.clone().add(x, 0.0, z), bodies) shouldBe false
            val east = center.clone().add(.05, 0.0, 0.0)
            citizenResidentStepClear(actor.npc, center, east, bodies) shouldBe true
            citizenResidentNudgeClear(actor.npc, center, east) shouldBe true
            actor.foodSource = h.city.buildings.first().id
            val city = h.city

            collisions.separateResident(actor) shouldBe true
            actor.npc.location.point() shouldBe east.point()
            actor.route shouldBe null
            actor.supplyPickup shouldBe null
            h.city shouldBe city
            h.deliveries shouldBe 0
            h.unloads shouldBe 0
        }
    }
})

private fun endpointBodyOwner(h: ResidentRuntime, name: String): Any =
    h.owner.javaClass.getDeclaredField(name).run {
        isAccessible = true
        get(h.owner)
    }
