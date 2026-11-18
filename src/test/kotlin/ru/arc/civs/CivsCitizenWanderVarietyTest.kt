package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import java.util.UUID
import kotlin.math.abs

class CivsCitizenWanderVarietyTest : FunSpec({
    test("free wandering keeps cardinal fallbacks and offers gentler turns to either side") {
        val wander = CitizenWander(UUID(0, 9))
        val choices = List(48) { wander.directions(0f, allowDiagonal = true) }
        choices.map { it.first() }.toSet() shouldBe setOf(0 to 1, -1 to 1, 1 to 1)
        val cardinal = setOf(0 to 1, -1 to 0, 0 to -1, 1 to 0)
        choices.all { it.size in 4..5 && it.takeLast(4).toSet() == cardinal } shouldBe true
    }

    test("a supported diagonal advances while turning and the next leg prefers a new cell") {
        residentRuntime(workerId = UUID(0, 9)) { h ->
            val actor = startFlatWander(h)
            val first = requireNotNull(actor.route)
            val npc = actor.npc
            first.target.point() shouldBe CitizenPoint(3.5, 64.0, 1.5)
            val before = npc.location
            h.navigate()
            (npc.location.distance(before) in .10.. .118251) shouldBe true
            (abs(citizenAngleChange(before.yaw, npc.bodyYaw)) in 0.01f..CITIZEN_TURN_PER_TICK) shouldBe true
            repeat(24) { if (!first.motion.finished) h.navigate() }
            npc.location.point() shouldBe first.target.point()

            // Face the previous cell at the handoff. It is safe but another step is available.
            npc.bodyYaw = 225f
            h.navigate()
            val second = requireNotNull(actor.route)
            (second !== first) shouldBe true
            (second.target.point() != first.from) shouldBe true
            h.pendingNavigationWorkers shouldBe 0
            h.deliveries shouldBe 0
            h.steps shouldBe emptyList()
        }
    }

    for (corner in listOf("wall", "missing floor")) {
        test("a diagonal past a corner with $corner falls back to a cardinal step") {
            residentRuntime(workerId = UUID(0, 9)) { h ->
                val levels = if (corner == "wall") 64..65 else 62..63
                val material = if (corner == "wall") Material.STONE else Material.AIR
                for (y in levels) h.world.getBlockAt(3, y, 0).type = material
                val route = requireNotNull(startFlatWander(h).route)
                val target = route.target.point()
                (abs(target.x - route.from.x) + abs(target.z - route.from.z)) shouldBe 1.0
                target.y shouldBe route.from.y
            }
        }
    }

    test("an unassigned resident in the shared hall walks around furniture blocking the door direction") {
        residentRuntime(workerId = UUID(0, 42)) { h ->
            val hall = h.city.buildings.single().copy(entrance = CivPosition(2, 64, 3))
            val entrance = CivPosition(0, 64, 14)
            val home = hall.copy(id = UUID(0, 43), type = BuildingType.HOUSE, anchor = entrance, entrance = entrance,
                bounds = CivBox(CivPosition(-2, 64, 15), CivPosition(2, 68, 18)))
            h.city = h.city.copy(buildings = listOf(hall, home), workers = h.city.workers.map { it.copy(homeId = home.id) })
            // East and south lead toward the hall door. The only clear cardinal step is west.
            for ((x, z) in listOf(1 to 1, 0 to 2, 0 to 0)) for (y in 64..65)
                h.world.getBlockAt(x, y, z).type = Material.BOOKSHELF

            val npc = h.actor().npc
            npc.teleport(Location(h.world, .5, 64.0, 1.5, 0f, 0f))
            h.holdNavigation = true
            val captures = h.world.chunkSnapshotCaptures
            h.navigationNow = 3_000
            h.owner.pulse(3_000)

            val destination = CitizenPoint(-.5, 64.0, 1.5)
            for (tick in 1..20) {
                val before = npc.location
                h.navigate()
                (npc.location.distance(before) <= .118251) shouldBe true
                if (npc.location.point() == destination) break
            }
            npc.location.point() shouldBe destination
            h.pendingNavigationWorkers shouldBe 0
            h.world.chunkSnapshotCaptures shouldBe captures
        }
    }
})

private fun startFlatWander(h: ResidentRuntime): CitizenActor {
    val actor = h.actor()
    actor.npc.teleport(Location(h.world, 4.5, 64.0, .5, 0f, 0f))
    h.city = h.city.copy(workers = h.city.workers.map { it.copy(nextDeliveryAt = 2) })
    h.navigationNow = 2_000
    h.owner.pulse(2_000)
    h.navigationNow = 2_500
    h.owner.pulse(2_500)
    return actor
}
