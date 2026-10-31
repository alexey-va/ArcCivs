package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import java.util.UUID

class CivsResidentEarlyYieldTest : FunSpec({
    for (obstruction in listOf("clear", "solid", "player"))
    test("a ready resident collision starts an early detour while its neighbour stays fixed with $obstruction side corridors") {
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
            val route = requireNotNull(h.routeIdentity(h.workerId))
            val phase = h.phase()
            val collisions = h.nativeCollisions()
            try {
                h.owner.residentStepAllowed = { npc, from, to -> npc.uniqueId != blocker && collisions.canResidentMove(npc, from, to) }
                var detouredAt: Int? = null
                repeat(8) { tick ->
                    val previous = actors.mapValues { it.value.location }
                    h.navigate()
                    if (h.routeMotion(h.workerId)?.let { it !== original } == true && detouredAt == null) detouredAt = tick + 1
                    actors.getValue(blocker).location shouldBe fixed
                    actors.keys.any(h::yielding) shouldBe false
                    actors.forEach { (id, npc) ->
                        (previous.getValue(id).distance(npc.location) <= .172001) shouldBe true
                        citizenPacketBodyClear(h.world, npc.location.point(), false) shouldBe true
                        citizenPacketSupported(h.world, npc.location.point()) shouldBe true
                    }
                }
                withClue(h.owner.debug(h.city.id, null)) { (detouredAt != null && detouredAt!! <= 8) shouldBe true }
                h.routeIdentity(h.workerId) shouldBe route
                h.phase() shouldBe phase
                h.deliveries shouldBe 0
                h.unloads shouldBe 0
                h.pickups shouldBe 0
                h.city shouldBe before
            } finally { collisions.close() }
        }
    }

})
