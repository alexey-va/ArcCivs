package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.util.BoundingBox
import ru.arc.paper.npc.PacketNpcPose
import java.util.UUID

class CivsDynamicBodyRoutingTest : FunSpec({
    test("body contact recovery provenance survives route replacement and clears only with a new accepted plan token") {
        residentRuntime { h ->
            val actor = h.owner.javaClass.getDeclaredField("actors").run {
                isAccessible = true
                requireNotNull((get(h.owner) as Map<*, *>)[h.workerId]) as CitizenActor
            }
            val token = actor.plan.token
            actor.bodyContactToken = token
            h.owner.pulse(2_000)
            actor.bodyContactToken shouldBe token
            actor.route?.motion?.cancel()
            actor.route = null
            h.owner.pulse(2_500)
            actor.bodyContactToken shouldBe token
            h.city = CivsRules.validate(h.city.copy(workers = h.city.workers.map {
                it.copy(completedCycles = it.completedCycles + 1)
            }))
            h.owner.pulse(3_000)
            (actor.plan.token != token) shouldBe true
            actor.bodyContactToken shouldBe null
            h.deliveries shouldBe 0
            h.unloads shouldBe 0
        }
    }

    for (corridor in listOf(false, true))
    test("a player entering the live route is ${if (corridor) "passed softly in a sealed corridor" else "walked around on an open floor"}") {
        residentRuntime { h ->
            val home = dynamicBodyHome(h)
            if (corridor) dynamicBodyCorridor(h)
            val npc = h.owner.collisionActors().single()
            val player = h.playerAt(Location(h.world, 6.5, 64.0, 3.5))
            val velocity = player.velocity.clone()
            val collisionOwner = h.nativeCollisions()
            try {
                h.owner.residentStepAllowed = collisionOwner::canResidentMove
                h.navigationNow = 2_000
                h.owner.pulse(2_000)
                requireNotNull(h.routeIdentity()) { "No initial shelter route: feet=${npc.location} ${h.navigationState()}" }
                var entered = false
                var passed = false
                var contact = false
                var slept = false
                val before = h.city
                for (tick in 1..800) {
                    if (!entered && npc.location.x >= 2.5) {
                        player.teleport(Location(h.world, 6.5, 64.0, .5))
                        entered = true
                    }
                    val previous = requireNotNull(h.owner.residentFeet(h.workerId))
                    h.navigate()
                    if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                    val actual = requireNotNull(h.owner.residentFeet(h.workerId))
                    withClue("tick=$tick previous=$previous actual=$actual ${h.navigationState()}") {
                        (previous.distance(actual) <= .118251) shouldBe true
                    }
                    val soft = h.owner.passingBodies(npc)
                    passed = passed || soft
                    val body = BoundingBox(actual.x - .3, actual.y, actual.z - .3,
                        actual.x + .3, actual.y + 1.8, actual.z + .3)
                    if (body.overlaps(player.boundingBox)) {
                        contact = true
                        soft shouldBe true
                    }
                    if (entered) player.location shouldBe Location(h.world, 6.5, 64.0, .5)
                    player.velocity shouldBe velocity
                    if (npc.pose == PacketNpcPose.SLEEPING) { slept = true; break }
                }
                withClue("feet=${npc.location} home=$home player=${player.location} ${h.navigationState()}") { slept shouldBe true }
                entered shouldBe true
                passed shouldBe corridor
                contact shouldBe corridor
                h.owner.passingBodies(npc) shouldBe false
                val feet = requireNotNull(h.owner.residentFeet(h.workerId))
                home.bounds.contains(feet.blockX, feet.blockY, feet.blockZ) shouldBe true
                h.city shouldBe before
                h.deliveries shouldBe 0
                h.unloads shouldBe 0
            } finally { collisionOwner.close() }
        }
    }

    for (hazard in listOf("wall", "void", "denied-step"))
    test("short body transit still refuses a newly observed $hazard") {
        residentRuntime { h ->
            dynamicBodyHome(h)
            dynamicBodyCorridor(h)
            val npc = h.owner.collisionActors().single()
            val player = h.playerAt(Location(h.world, 6.5, 64.0, .5))
            val collisionOwner = h.nativeCollisions()
            try {
                h.owner.residentStepAllowed = collisionOwner::canResidentMove
                h.navigationNow = 2_000
                h.owner.pulse(2_000)
                requireNotNull(h.routeIdentity()) { "No initial shelter route: feet=${npc.location} ${h.navigationState()}" }
                var activated = false
                var after = 0
                for (tick in 1..800) {
                    h.navigate()
                    if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                    if (!activated && h.owner.passingBodies(npc)) {
                        activated = true
                        when (hazard) {
                            "wall" -> for (y in 64..66) h.world.getBlockAt(8, y, 0).type = Material.STONE
                            "void" -> for (y in h.world.minHeight..63) h.world.getBlockAt(8, y, 0).type = Material.AIR
                            else -> h.owner.residentStepAllowed = { actor, from, to ->
                                to.x < 7.7 && collisionOwner.canResidentMove(actor, from, to)
                            }
                        }
                    }
                    if (activated) {
                        withClue("hazard=$hazard tick=$tick actual=${npc.location} ${h.navigationState()}") {
                            (npc.location.x < 7.8) shouldBe true
                            citizenPacketSupported(h.world, CitizenPoint(npc.location.x, npc.location.y, npc.location.z)) shouldBe true
                        }
                        if (++after >= 100) break
                    }
                }
                withClue("hazard=$hazard feet=${npc.location} player=${player.location} ${h.navigationState()}") {
                    activated shouldBe true
                }
                player.location shouldBe Location(h.world, 6.5, 64.0, .5)
                h.deliveries shouldBe 0
                h.unloads shouldBe 0
            } finally { collisionOwner.close() }
        }
    }
})

private fun dynamicBodyHome(h: ResidentRuntime): CivBuilding {
    val home = CivBuilding(UUID.randomUUID(), BuildingType.HOUSE, 1, CivPosition(12, 64, 0), 0,
        "house1", CivBox(CivPosition(10, 64, -2), CivPosition(14, 68, 2)), CivPosition(12, 64, 0), BuildingPhase.ACTIVE)
    h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings + home,
        workers = h.city.workers.map { it.copy(homeId = home.id) }))
    h.world.time = 13_000
    // A detour through block z=-1 requires the normal one-chunk route margin at chunk z=-2.
    for (x in -2..2) for (z in -2..2) h.world.loadChunk(x, z)
    return home
}

private fun dynamicBodyCorridor(h: ResidentRuntime) {
    // The narrow transit opens into a real bedroom; a sleeping body is wider than the one-block corridor.
    for (x in -96..9) for (z in listOf(-1, 1)) for (y in 64..66)
        h.world.getBlockAt(x, y, z).type = Material.STONE
}
