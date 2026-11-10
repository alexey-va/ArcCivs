package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID

class CivsCitizenNapTest : FunSpec({
    test("an idle adult sits above its actual ground and gives the place back when hungry") {
        residentRuntime { h ->
            val visual = NapVisualProbe(h)
            val ground = h.idleNapGround()
            val npc = h.actor().npc

            visual.opened shouldBe 0
            h.owner.pulse(100_000)

            visual.opened shouldBe 1
            visual.ground shouldBe ground
            visual.ages shouldBe listOf(0)
            npc.isSeated shouldBe true
            h.owner.status(h.workerId) shouldBe "workers.status.resting"
            npc.location.y shouldBe ground.y + (CIVS_IDLE_NAP_SEAT_TOP - .7040625)
            h.owner.residentFeet(h.workerId) shouldBe ground
            h.owner.residentReadFrame().rows.getValue(h.workerId).actual shouldBe ground.point()
            h.owner.growthBlocked(h.world.uid, CivPosition(ground.blockX, ground.blockY, ground.blockZ + 1)) shouldBe true
            h.world.getBlockAt(ground.blockX, ground.blockY, ground.blockZ).type shouldBe Material.AIR

            h.paper.performTicks(24)
            h.owner.pulse(101_200)
            visual.ages shouldBe listOf(0, 24)
            h.city = h.city.copy(workers = h.city.workers.map { it.copy(hunger = 14) })
            h.owner.pulse(101_300)

            visual.closed shouldBe 1
            npc.isSeated shouldBe false
            npc.location shouldBe ground
            h.owner.closeAsync().join()
            visual.closed shouldBe 1
        }
    }

    test("a viewer who comes closer after a missed attempt can see a nap at a completed local walk") {
        residentRuntime(workerId = UUID(0, 42)) { h ->
            h.background = true
            val visual = NapVisualProbe(h)
            val viewer = h.paper.server.onlinePlayers.single()
            viewer.teleport(Location(h.world, 40.5, 64.0, .5))
            h.idleNapGround()
            val due = requireNotNull(h.actor().nextIdleNapAt)
            h.navigationNow = due
            h.owner.pulse(due)
            visual.opened shouldBe 0
            val walk = requireNotNull(h.actor().route?.wanderWalk)

            viewer.teleport(Location(h.world, 12.5, 64.0, .5))
            repeat(600) { tick ->
                if (visual.opened == 0) {
                    h.navigate()
                    if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                }
            }
            walk.legs shouldBe walk.legLimit
            visual.opened shouldBe 1
            h.actor().npc.isSeated shouldBe true
            h.routeIdentity() shouldBe null
            val seatedAt = requireNotNull(h.navigationNow)
            val cooldown = seatedAt + 120_000L + Math.floorMod(h.workerId.hashCode(), 45_000)
            h.actor().nextIdleNapAt shouldBe cooldown

            h.paper.performTicks(161)
            h.owner.pulse(seatedAt + 8_050)
            visual.closed shouldBe 1
            visual.opened shouldBe 1
            h.actor().nextIdleNapAt shouldBe cooldown
        }
    }

    test("an idle adult cannot sit in the air") {
        residentRuntime { h ->
            val visual = NapVisualProbe(h)
            val ground = h.idleNapGround()
            val npc = h.actor().npc
            val floor = h.world.getBlockAt(ground.blockX, ground.blockY - 1, ground.blockZ)
            floor.type = Material.AIR
            h.owner.pulse(100_000)
            visual.opened shouldBe 0
            npc.isSeated shouldBe false
            (requireNotNull(h.actor().nextIdleNapAt) - 100_000L in 5_000L..10_000L) shouldBe true
        }
    }

    test("an idle adult cannot stand in newly obstructed headroom") {
        residentRuntime { h ->
            val visual = NapVisualProbe(h)
            val ground = h.idleNapGround()
            val npc = h.actor().npc
            h.owner.pulse(100_000)
            visual.opened shouldBe 1
            npc.isSeated shouldBe true
            val teleports = h.teleports
            h.world.getBlockAt(ground.blockX, ground.blockY + 1, ground.blockZ).type = Material.STONE
            h.paper.performTicks(21)
            h.owner.pulse(101_050)
            visual.closed shouldBe 1
            npc.isValid shouldBe false
            h.teleports shouldBe teleports
        }
    }

    test("the last nearby viewer quitting closes the nap for a retained background resident") {
        residentRuntime { h ->
            h.background = true
            val visual = NapVisualProbe(h)
            val ground = h.idleNapGround()
            val npc = h.actor().npc
            h.owner.pulse(100_000)
            npc.isSeated shouldBe true

            val viewer = h.paper.server.onlinePlayers.single()
            h.owner.onQuit(PlayerQuitEvent(viewer, null as Component?))

            visual.closed shouldBe 1
            npc.isValid shouldBe true
            npc.isSeated shouldBe false
            npc.location shouldBe ground
        }
    }

    test("ordinary work admission releases the chair before assigning its route") {
        residentRuntime { h ->
            val visual = NapVisualProbe(h)
            h.idleNapGround()
            val npc = h.actor().npc
            h.owner.pulse(100_000)
            npc.isSeated shouldBe true
            val farm = h.assignFarm(h.workerId)
            h.work = CivWorldWork(h.workerId, 0, farm.id, CivPosition(19, 64, 0),
                listOf(CivPlanBlock(CivPosition(19, 63, 0), "minecraft:stone", "minecraft:farmland")),
                durationMillis = 1_000, status = "tilling")
            h.owner.pulse(101_000)

            visual.closed shouldBe 1
            npc.isSeated shouldBe false
            h.actor().plan.token.assignmentId shouldBe farm.id
            h.phase() shouldBe CitizenPhase.TO_WORK
        }
    }

    test("a forester stopped by stored timber surplus can take the same idle nap") {
        residentRuntime { h ->
            val visual = NapVisualProbe(h)
            val farm = h.assignFarm(h.workerId)
            val mill = farm.copy(type = BuildingType.FORESTER, schematicId = "forester1", production = "OAK_LOG")
            h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings.map { if (it.id == mill.id) mill else it }))
            h.idleReason = "storage-surplus"
            h.idleNapGround(22.5)
            h.actor().plan.idleReason shouldBe "storage-surplus"
            h.owner.pulse(100_000)

            withClue("next=${h.actor().nextIdleNapAt} route=${h.routeIdentity()} phase=${h.phase()}") {
                visual.opened shouldBe 1
            }
            h.actor().npc.isSeated shouldBe true
        }
    }

    test("daytime nap ends as the normal night routine takes over") {
        residentRuntime { h ->
            val visual = NapVisualProbe(h)
            h.idleNapGround()
            val npc = h.actor().npc
            h.owner.pulse(100_000)
            npc.isSeated shouldBe true
            h.world.time = 13_000
            h.owner.pulse(101_000)
            visual.closed shouldBe 1
            npc.isSeated shouldBe false
            h.actor().sheltering shouldBe true
        }
    }
})

private fun ResidentRuntime.idleNapGround(x: Double = 5.5): Location {
    world.time = 1_000
    val ground = Location(world, x, 64.0, .5)
    actor().npc.teleport(ground)
    owner.pulse(1_000)
    routeIdentity() shouldBe null
    return ground
}

private class NapVisualProbe(h: ResidentRuntime) {
    var opened = 0
        private set
    var closed = 0
        private set
    var ground: Location? = null
        private set
    val ages = mutableListOf<Int>()

    init {
        h.owner.showIdleNap = { at, _, _ ->
            opened++
            ground = at
            CivsIdleNapVisual({ ages += it }, { closed++ })
        }
    }
}
