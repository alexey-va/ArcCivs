package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import java.util.UUID

class CivsFoodApproachAdmissionTest : FunSpec({
    test("a sole food stance belongs to one walking resident while another waits") {
        residentRuntime(server = CivsWorksiteServerFixture()) { h ->
            val second = UUID(0, 28)
            val barrel = CivPosition(8, 64, 0)
            val standing = barrel.copy(x = 9)
            val tavern = CivBuilding(UUID(0, 29), BuildingType.TAVERN, 1, barrel, 0, "tavern1",
                CivBox(CivPosition(7, 63, -2), CivPosition(13, 68, 2)), standing, BuildingPhase.ACTIVE, barrel)
            h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings + tavern,
                workers = listOf(h.city.workers.single().copy(hunger = 0), CivWorker(second, "Hungry", null, hunger = 0))))
            h.owner.pulse(2_000)
            h.owner.collisionActors().size shouldBe 2
            h.actor().npc.teleport(Location(h.world, 13.5, 64.0, .5))
            h.actor(second).npc.teleport(Location(h.world, 14.5, 64.0, .5))
            h.world.getBlockAt(barrel.x, barrel.y, barrel.z).type = Material.BARREL
            for ((x, z) in listOf(7 to 0, 8 to -1, 8 to 1)) {
                for (y in 64..67) h.world.getBlockAt(x, y, z).type = Material.STONE
            }
            val source = CivResidentFoodSource(tavern.id, barrel, standing)
            citizenCollectedHomeTarget(h.city, tavern, h.world, barrel) shouldBe standing
            h.physicalFoodSource = source
            h.owner.pulse(3_000)

            val actors = listOf(h.actor(), h.actor(second))
            val routed = actors.filter { it.foodPickupSource == source && it.route != null }
            routed.size shouldBe 1
            val admitted = routed.single()
            val waiting = actors.single { it !== admitted }
            waiting.foodPickupSource shouldBe null
            (waiting.supplyRetryAt > 3_000L) shouldBe true
            val route = admitted.route
            h.owner.pulse(4_000)
            (admitted.route === route) shouldBe true
        }
    }
})
