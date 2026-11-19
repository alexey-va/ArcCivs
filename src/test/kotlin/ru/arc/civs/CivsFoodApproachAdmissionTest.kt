package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import java.util.UUID

class CivsFoodApproachAdmissionTest : FunSpec({
    fun foodApproachAdmission(roofed: Boolean) {
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
            // Only the end of this corridor can reach the barrel. Public walls at its far end are hookable.
            for (x in 6..16) for (z in -2..3) {
                if (x == barrel.x && z == barrel.z || x in 10..15 && z == 2) continue
                for (y in 64..67) h.world.getBlockAt(x, y, z).type = Material.STONE
            }
            if (roofed) for (x in 10..15) h.world.getBlockAt(x, 66, 2).type = Material.STONE
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
                val winner = routes.single { it.motion.pickupEndpoint != null }
                winner.motion.reservedDestination(winner.target)?.point() shouldBe standing.point()
                val waiting = routes.single { it.motion.pickupEndpoint == null }
                waiting.motion.reservedDestination(waiting.target) shouldBe null
                if (roofed) {
                    waiting.motion.failure shouldBe "no-path"
                    waiting.motion.endpoint shouldBe null
                    routes.count { it.motion.failure != null } shouldBe 1
                } else {
                    waiting.motion.continuationReason shouldBe "chunk-unloaded"
                    // The closest remaining approach is outside the south wall, beyond pickup reach.
                    val approach = CitizenPoint(8.5, 64.0, 4.5)
                    waiting.motion.endpoint shouldBe approach
                    citizenPacketSupported(h.world, approach) shouldBe true
                    citizenPacketBodyClear(h.world, approach, false) shouldBe true
                    routes.count { it.motion.failure != null } shouldBe 0
                    val resident = actors.single { it.route === waiting }
                    for (tick in 1..450) {
                        h.navigate()
                        if (resident.route !== waiting) break
                    }
                    resident.npc.location.point() shouldBe approach
                    waiting.motion.pickupEndpoint shouldBe null
                }
            }
            actors.forEach { it.supplyPickup shouldBe null }
        }
    }

    test("a sole reachable food endpoint is reserved only after one resident's search confirms it") {
        foodApproachAdmission(roofed = true)
    }

    test("an unloaded frontier preserves food approach progress without walking to rejected pickup feet") {
        foodApproachAdmission(roofed = false)
    }

    test("a rejected pickup with no other progress keeps the unloaded frontier failure") {
        residentRuntime(server = CivsWorksiteServerFixture()) { h ->
            val holder = CivPosition(27, 64, 0)
            h.world.getBlockAt(holder.x, holder.y, holder.z).type = Material.BARREL
            val rejected = mutableListOf<CitizenPoint>()
            // The resident starts at the edge of the loaded chunk with one step towards the barrel.
            val route = CitizenPacketRoute(h.world, Location(h.world, 31.5, 64.0, .5), holder.location(h.world),
                { it.x in 30..31 && it.z == 0 }, { _, _ -> true }, { 1.0 },
                pickup = CitizenContainerRouteGoal(holder) { feet ->
                    rejected += feet
                    false
                })
            for (batch in 1..128) {
                if (!route.searching) break
                route.expand()
            }
            rejected shouldBe listOf(CitizenPoint(30.5, 64.0, .5))
            route.failure shouldBe "chunk-unloaded"
            route.endpoint shouldBe null
            route.pickupEndpoint shouldBe null
        }
    }
})
