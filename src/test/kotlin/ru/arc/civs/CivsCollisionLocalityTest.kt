package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
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

    test("disabled entity collisions pass residents and players without nudges but keep terrain support and permissions") {
        residentRuntime(workerId = UUID(0, 1)) { h ->
            val neighbourId = UUID(0, 2)
            h.city = h.city.copy(workers = h.city.workers + CivWorker(neighbourId, "Neighbour", null))
            h.owner.pulse(2_000L)
            val actor = h.actor()
            val neighbour = h.actor(neighbourId)
            val routes = localityOwner(h, "routes") as CivsCitizenRoutes
            for (resident in listOf(actor, neighbour)) {
                routes.cancelRoute(resident)
                resident.mealVisit = null
                resident.idleNap = null
                resident.sleeping = false
                resident.sheltering = false
            }
            val from = Location(h.world, 3.5, 64.0, .5, -90f, 0f)
            val to = from.clone().add(1.0, 0.0, 0.0)
            actor.npc.teleport(from)
            neighbour.npc.teleport(to)
            val player = h.paper.addPlayer("CollisionPolicyPlayer")
            player.teleport(to)
            h.world.threatEntities += player
            h.nativeCollisions().use { native ->
                // The same resident and native-player geometry blocks the enabled policy.
                val enabled = localityOwner(h, "collisions") as CivsCitizenCollisions
                h.owner.residentStepAllowed = native::canResidentMove
                enabled.routeMoveAllowed(actor, from, to) shouldBe false
                neighbour.npc.teleport(to.clone().add(20.0, 0.0, 0.0))
                enabled.routeMoveAllowed(actor, from, to) shouldBe false
                neighbour.npc.teleport(to)

                val runtime = CitizenRuntime(h.paper.createSimplePlugin("CollisionPolicyTest"), entityCollisions = false) { listOf(h.city) }
                runtime.actors[actor.workerId] = actor
                runtime.actors[neighbour.workerId] = neighbour
                val disabled = CivsCitizenCollisions(runtime,
                    routesOwner = { error("Body policy must not replan") },
                    leisureOwner = { error("Body policy must not change leisure") },
                    workOwner = { error("Body policy must not change work") },
                    logisticsOwner = { error("Body policy must not change cargo") },
                    audienceOwner = { error("Body policy must not request admission") },
                    lifeOwner = { error("Body policy must not change life") },
                    recoveryClock = { 0L },
                    residentStepAllowed = { _, _, _ -> error("Disabled policy must not query player bodies") })
                disabled.shelterOccupied(actor) shouldBe emptyList()
                disabled.idleDestinationFree(actor, to) shouldBe true
                disabled.residentMoveAllowed(actor, to, to) shouldBe true
                disabled.routeMoveAllowed(actor, from, to) shouldBe true
                disabled.residentMoveAllowed(actor, from, from.clone().add(3.0, 0.0, 0.0)) shouldBe false
                disabled.residentMoveAllowed(actor, from, to.clone().also { it.x = Double.NaN }) shouldBe false
                disabled.nudgeResident(actor.npc, Vector(.05, 0.0, 0.0)) shouldBe false
                disabled.beginResidentYield(actor) shouldBe false
                disabled.advanceResidentYield(actor) shouldBe false
                disabled.separateResident(actor) shouldBe null
                actor.npc.location shouldBe from
                neighbour.npc.location shouldBe to
                player.location shouldBe to

                val nap = CivsCitizenNap(runtime) { disabled }
                nap.show = { _, _, _ -> CivsIdleNapVisual({}, {}) }
                h.world.time = 1_000L
                val worker = h.city.workers.single { it.id == neighbourId }
                actor.npc.teleport(to)
                player.teleport(from.clone().add(-2.0, 0.0, 0.0))
                neighbour.nextIdleNapAt = 1L
                nap.start(neighbour, h.city, worker, h.world, 2_100L) shouldBe false
                actor.npc.teleport(from)
                neighbour.nextIdleNapAt = 1L
                nap.start(neighbour, h.city, worker, h.world, 2_200L) shouldBe true
                disabled.idleDestinationFree(actor, to) shouldBe false
                disabled.routeMoveAllowed(actor, from, to) shouldBe false
                disabled.routeMoveAllowed(actor, from, from.clone().add(-1.0, 0.0, 0.0)) shouldBe true
                nap.stop(neighbour) shouldBe true
                disabled.idleDestinationFree(actor, to) shouldBe true
                disabled.routeMoveAllowed(actor, from, to) shouldBe true

                fun route(permitted: Boolean = true) = CitizenPacketRoute(h.world, from, to,
                    { true }, { _, _ -> permitted }, { 1.0 }).also { it.readyWanderStep() }
                fun advance(motion: CitizenPacketRoute) = motion.move(from, 5.0,
                    { a, b -> disabled.routeMoveAllowed(actor, a, b) }) { true }
                advance(route())?.point() shouldBe to.point()
                h.world.getBlockAt(4, 64, 0).type = Material.STONE
                val wall = route()
                advance(wall) shouldBe null
                wall.failure shouldBe "movement-blocked"
                h.world.getBlockAt(4, 64, 0).type = Material.AIR
                h.world.getBlockAt(4, 63, 0).type = Material.AIR
                val unsupported = route()
                advance(unsupported) shouldBe null
                unsupported.failure shouldBe "path-changed"
                h.world.getBlockAt(4, 63, 0).type = Material.STONE
                val forbidden = route(permitted = false)
                advance(forbidden) shouldBe null
                forbidden.failure shouldBe "movement-blocked"
                h.deliveries shouldBe 0
                h.pickups shouldBe 0
                h.unloads shouldBe 0
            }
        }
    }
})

private fun localityOwner(h: ResidentRuntime, name: String): Any =
    h.owner.javaClass.getDeclaredField(name).run {
        isAccessible = true
        get(h.owner)
    }
