package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import java.util.UUID

class CivsCollisionLocalityTest : FunSpec({
    test("distant bodies do not change crossing sliding overlap or boundary contact") {
        val near = BoundingBox(-.3, 64.0, -.3, .3, 65.8, .3)
        val distant = List(92) { index -> near.clone().shift(100.0 + index, 0.0, 100.0) }
        val cases = listOf(
            Vector(-2.0, 64.0, 0.0) to Vector(2.0, 64.0, 0.0),
            Vector(-2.0, 64.0, -.4) to Vector(2.0, 64.0, 1.2),
            Vector(.4, 64.0, 0.0) to Vector(-1.5, 64.0, 0.0),
            Vector(-.6, 64.0, 0.0) to Vector(1.0, 64.0, 0.0),
            Vector(-.6, 64.0, -2.0) to Vector(-.6, 64.0, 2.0),
        )
        for ((from, to) in cases) {
            val expected = citizenSlideBody(from, to, .3, 1.8, .3, listOf(near))
            citizenSlideBody(from, to, .3, 1.8, .3, distant + near) shouldBe expected
            citizenSlideBody(from, to, .3, 1.8, .3, distant) shouldBe null
        }
    }

    test("movement keeps nearby shelter reservations even when their resident is far away") {
        residentRuntime(workerId = UUID(0, 1)) { h ->
            val workers = listOf(CivWorker(UUID(0, 2), "Resident 2", null))
            h.city = h.city.copy(workers = h.city.workers + workers)
            h.owner.pulse(2_000L)
            h.owner.collisionActors().size shouldBe 2
            val actor = h.actor()
            val others = workers.map { h.actor(it.id) }
            val routes = localityOwner(h, "routes") as CivsCitizenRoutes
            val collisions = localityOwner(h, "collisions") as CivsCitizenCollisions
            (listOf(actor) + others).forEach {
                routes.cancelRoute(it)
                it.mealVisit = null
                it.idleNap = null
                it.sleeping = false
                it.sheltering = false
            }
            val from = Location(h.world, 3.5, 64.0, .5)
            val to = from.clone().add(1.0, 0.0, 0.0)
            actor.npc.teleport(from)
            others.forEachIndexed { index, other ->
                other.npc.teleport(Location(h.world, 100.0 + index, 64.0, 100.0))
            }
            val sweep = citizenResidentSweep(actor.npc, from, to)
            collisions.shelterOccupied(actor).size shouldBe others.size
            collisions.shelterOccupied(actor, sweep) shouldBe emptyList()
            collisions.routeMoveAllowed(actor, from, to) shouldBe true

            val reserved = others.last()
            val resting = from.clone().add(.5, 0.0, 0.0)
            val bed = BoundingBox(resting.x - .4, 64.0, resting.z - .8,
                resting.x + .4, 64.8, resting.z + .8)
            reserved.sheltering = true
            reserved.shelterPose = CitizenSleepPose(resting, bed)
            reserved.sleepOrigin = resting
            val occupied = collisions.shelterOccupied(actor, sweep)
            occupied.size shouldBe 2 // Both the lying reservation and its standing approach remain protected.
            occupied.contains(bed) shouldBe true
            collisions.routeMoveAllowed(actor, from, to) shouldBe false

            reserved.sheltering = false
            collisions.shelterOccupied(actor, sweep) shouldBe emptyList()
            collisions.routeMoveAllowed(actor, from, to) shouldBe true
            // An actor moved into the path is observed immediately; there is no cached occupancy.
            reserved.npc.teleport(resting)
            collisions.shelterOccupied(actor, sweep).size shouldBe 1
            collisions.routeMoveAllowed(actor, from, to) shouldBe false
        }
    }
})

private fun localityOwner(h: ResidentRuntime, name: String): Any =
    h.owner.javaClass.getDeclaredField(name).run {
        isAccessible = true
        get(h.owner)
    }
