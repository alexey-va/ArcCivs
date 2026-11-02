package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import java.util.UUID

class CivsResidentEarlyYieldTest : FunSpec({
    for (obstruction in listOf("clear", "solid", "player"))
    test("a ready resident contact keeps its route and native terrain while its neighbour stays fixed with $obstruction side corridors") {
        residentRuntime { h ->
            val quarry = h.assignQuarry().copy(control = null, production = null,
                bounds = CivBox(CivPosition(18, 64, -4), CivPosition(26, 68, -1)))
            h.world.getBlockAt(20, 64, 0).type = Material.AIR
            val hall = h.city.buildings.first().copy(bounds = CivBox(CivPosition(-2, 64, -4), CivPosition(2, 68, -2)))
            val forester = quarry.copy(id = UUID.randomUUID(), type = BuildingType.FORESTER,
                anchor = CivPosition(10, 64, 10), entrance = CivPosition(10, 64, 10),
                bounds = CivBox(CivPosition(8, 64, 7), CivPosition(12, 68, 9)))
            val blocker = UUID(0, 99)
            h.city = CivsRules.validate(h.city.copy(autonomy = CivAutonomy(), inventory = null,
                buildings = listOf(hall, quarry, forester),
                workers = h.city.workers + CivWorker(blocker, "Waiting forester", forester.id, nextDeliveryAt = 1)))
            h.idleReason = "searching-work"
            h.owner.collisionActors().single().teleport(Location(h.world, .5, 64.0, .5))
            h.navigationNow = 2_000
            // Hold movement without rejecting the clear initial storage arrival probe.
            h.owner.residentStepAllowed = { _, from, to -> from.x == to.x && from.y == to.y && from.z == to.z }
            repeat(2) { h.owner.pulse(2_000) }
            val actors = h.owner.collisionActors().associateBy { it.uniqueId }
            actors.getValue(blocker).teleport(Location(h.world, 10.5, 64.0, 10.5))
            for (tick in 1..200) {
                h.navigate(0)
                if (h.routeMotion(h.workerId)?.let { !it.searching && it.waitingForCollision } == true) break
            }
            h.routeMotion(h.workerId)?.waitingForCollision shouldBe true
            for (x in -2..3) for (z in -2..2) h.world.loadChunk(x, z)
            actors.getValue(blocker).teleport(Location(h.world, 1.25, 64.0, .5))
            when (obstruction) {
                "solid" -> {
                    for (x in -2..3) for (z in listOf(-1, 1)) for (y in 64..66)
                        h.world.getBlockAt(x, y, z).type = Material.STONE
                    for (x in listOf(-1, 2)) for (z in -1..1) for (y in 64..66)
                        h.world.getBlockAt(x, y, z).type = Material.STONE
                }
                "player" -> listOf(1.25 to 1.75, 1.25 to -.75, 2.5 to .5).forEachIndexed { index, (x, z) ->
                    h.paper.addPlayer("EarlyYield$index").also { player ->
                        player.teleport(Location(h.world, x, 64.0, z))
                        h.world.threatEntities += player
                    }
                }
            }
            val before = h.city
            val fixed = actors.getValue(blocker).location
            val original = requireNotNull(h.routeMotion(h.workerId))
            val route = requireNotNull(h.routeIdentity(h.workerId)) as CitizenRoute
            val phase = h.phase()
            val collisions = h.nativeCollisions()
            try {
                h.owner.residentStepAllowed = { npc, from, to -> npc.uniqueId != blocker && collisions.canResidentMove(npc, from, to) }
                fun nativeRefreshes(): Any? = ((h.owner.debug(h.city.id, null)["planner_global"] as Map<*, *>)["snapshot_cache"] as Map<*, *>)["refresh_batches"]
                val nativeRefreshesBefore = nativeRefreshes()
                var passingAt: Int? = null
                repeat(8) { tick ->
                    val previous = actors.mapValues { it.value.location }
                    h.navigate()
                    withClue("contact tick=$tick ${h.navigationState()}") {
                        (h.routeMotion(h.workerId) === original) shouldBe true
                        route.replanAttempts shouldBe 0
                        route.previousMotion shouldBe null
                        original.searching shouldBe false
                        nativeRefreshes() shouldBe nativeRefreshesBefore
                    }
                    if (h.owner.passingBodies(actors.getValue(h.workerId)) && passingAt == null) passingAt = tick + 1
                    actors.getValue(blocker).location shouldBe fixed
                    actors.keys.any(h::yielding) shouldBe false
                    actors.forEach { (id, npc) ->
                        (previous.getValue(id).distance(npc.location) <= .172001) shouldBe true
                        citizenPacketBodyClear(h.world, npc.location.point(), false) shouldBe true
                        citizenPacketSupported(h.world, npc.location.point()) shouldBe true
                    }
                }
                withClue(h.owner.debug(h.city.id, null)) { (passingAt != null && passingAt!! <= 8) shouldBe true }
                h.routeIdentity(h.workerId) shouldBe route
                h.phase() shouldBe phase
                h.deliveries shouldBe 0
                h.unloads shouldBe 0
                h.pickups shouldBe 0
                h.city shouldBe before
            } finally { collisions.close() }
        }
    }
    test("a resident at the finished endpoint waits without replanning or work until its neighbour leaves") {
        residentRuntime { h ->
            h.prepareMineWork(CivPosition(23, 64, 0), focus = CivPosition(26, 64, 0))
            val blocker = UUID(0, 99)
            h.city = h.city.copy(workers = h.city.workers + CivWorker(blocker, "Waiting neighbour", null))
            h.idleReason = "searching-work"
            h.navigationNow = 2_000
            repeat(2) { h.owner.pulse(2_000) }
            val actors = h.owner.collisionActors().associateBy { it.uniqueId }
            val walker = actors.getValue(h.workerId)
            val neighbour = actors.getValue(blocker)
            val aside = Location(h.world, 10.5, 64.0, 10.5)
            neighbour.teleport(aside)
            h.nativeCollisions().use { collisions ->
                h.owner.residentStepAllowed = { npc, from, to ->
                    npc.uniqueId != blocker && collisions.canResidentMove(npc, from, to)
                }
                for (tick in 1..500) {
                    h.navigate()
                    if (h.routeFinished(h.workerId)) break
                }
                withClue(h.navigationState()) {
                    h.routeFinished(h.workerId) shouldBe true
                    h.phase() shouldBe CitizenPhase.TO_WORK
                }
                val route = requireNotNull(h.routeIdentity(h.workerId)) as CitizenRoute
                val motion = route.motion
                // A real endpoint was reached, but its next coarse work pulse must still see the new occupant.
                neighbour.teleport(walker.location.clone().add(-.2, 0.0, 0.0))
                val occupied = neighbour.location
                repeat(6) {
                    val previous = walker.location
                    h.navigate()
                    h.owner.pulse(requireNotNull(h.navigationNow))
                    withClue(h.navigationState()) {
                        (previous.distance(walker.location) <= .050001) shouldBe true
                        neighbour.location shouldBe occupied
                        (h.routeMotion(h.workerId) === motion) shouldBe true
                        route.replanAttempts shouldBe 0
                        route.previousMotion shouldBe null
                        motion.searching shouldBe false
                        route.completion shouldBe null
                        h.phase() shouldBe CitizenPhase.TO_WORK
                        h.observedWork() shouldBe 0
                        h.deliveries shouldBe 0
                    }
                }
                neighbour.teleport(aside)
                h.navigate()
                h.owner.pulse(requireNotNull(h.navigationNow))
                withClue(h.navigationState()) { h.phase() shouldBe CitizenPhase.WORKING }
                h.deliveries shouldBe 0
            }
        }
    }

})
