package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import java.util.UUID

class CivsFoodApproachAdmissionTest : FunSpec({
    test("a sole reachable food endpoint is reserved only after one resident's search confirms it") {
        residentRuntime(server = CivsWorksiteServerFixture()) { h ->
            val second = UUID(0, 28)
            val barrel = CivPosition(8, 64, 0)
            val standing = CivPosition(10, 64, 2)
            val tavern = CivBuilding(UUID(0, 29), BuildingType.TAVERN, 1, barrel, 0, "tavern1",
                CivBox(CivPosition(7, 63, -2), CivPosition(13, 68, 2)), standing, BuildingPhase.ACTIVE, barrel)
            h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings + tavern,
                workers = listOf(h.city.workers.single().copy(hunger = 0), CivWorker(second, "Hungry", null, hunger = 0))))
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            h.owner.collisionActors().size shouldBe 2
            val actors = listOf(h.actor(), h.actor(second))
            actors[0].npc.teleport(Location(h.world, 14.5, 64.0, 2.5))
            actors[1].npc.teleport(Location(h.world, 15.5, 64.0, 2.5))
            h.world.getBlockAt(barrel.x, barrel.y, barrel.z).type = Material.BARREL
            // Only the end of this corridor enters pickup range. All other nearby cells are solid.
            for (x in 6..16) for (z in -2..3) {
                if (x == barrel.x && z == barrel.z || x in 10..15 && z == 2) continue
                for (y in 64..67) h.world.getBlockAt(x, y, z).type = Material.STONE
            }
            val source = CivResidentFoodSource(tavern.id, barrel, standing)
            h.physicalFoodSource = source
            h.holdNavigation = true
            h.navigationNow = 3_000
            h.owner.pulse(3_000)

            val routes = actors.map { actor ->
                actor.foodPickupSource shouldBe source
                requireNotNull(actor.route).also { it.motion.reservedDestination(it.target) shouldBe null }
            }
            h.releaseNavigation()
            for (tick in 1..400) {
                h.navigate()
                if (routes.none { it.motion.searching }) break
            }
            withClue(h.owner.debug(h.city.id, null)) {
                routes.none { it.motion.searching } shouldBe true
                routes.mapNotNull { it.motion.pickupEndpoint } shouldBe listOf(standing.point())
                val waiting = routes.single { it.motion.pickupEndpoint == null }
                waiting.motion.reservedDestination(waiting.target) shouldBe null
                waiting.motion.failure shouldBe "no-path"
                routes.count { it.motion.failure != null } shouldBe 1
            }
            actors.forEach { it.supplyPickup shouldBe null }
        }
    }
})
