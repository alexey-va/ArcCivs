package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsCitizenFoodRouteRetentionTest : FunSpec({
    test("an idle quarry food route survives work cache changes until its physical pickup receipt") {
        residentRuntime(workerId = UUID(0, 20)) { h ->
            for (x in -2..3) for (z in -2..2) h.world.loadChunk(x, z)
            val quarry = h.assignQuarry()
            val hall = h.city.buildings.first { it.type == BuildingType.TOWN_HALL }.copy(control = CivPosition(0, 64, 0))
            h.world.getBlockAt(0, 64, 0).type = Material.BARREL
            h.city = h.city.copy(buildings = h.city.buildings.map { if (it.id == hall.id) hall else it },
                workers = h.city.workers.map { it.copy(hunger = 12) })
            val waiting = CivWorldWork(h.workerId, 0, quarry.id, CivPosition(21, 64, 0), emptyList(),
                inputs = mapOf("IRON_PICKAXE" to 1L), status = "waiting-tool", durationMillis = 1_000)
            h.work = waiting
            val food = CivResidentFoodSource(hall.id, requireNotNull(hall.control), CivPosition(1, 64, 0))
            val foodReceipt = CompletableFuture<Settlement>()
            var foodPickups = 0
            h.physicalFoodSource = food
            h.physicalFoodPickup = { selected ->
                selected shouldBe food
                h.owner.requireStorageArrival(h.city.id, h.workerId, hall.id)
                foodPickups++
                foodReceipt
            }
            val npc = h.owner.collisionActors().single()
            npc.teleport(Location(h.world, 21.5, 64.0, .5))
            h.holdNavigation = true
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            val actor = h.owner.javaClass.getDeclaredField("actors").run {
                isAccessible = true
                requireNotNull((get(h.owner) as Map<*, *>)[h.workerId]) as CitizenActor
            }
            h.phase() shouldBe CitizenPhase.IDLE
            actor.workDetour shouldBe null // IDLE did not earn an accepted production interval.
            actor.foodSource shouldBe hall.id
            val plan = actor.plan
            val cycle = actor.cycle
            val route = requireNotNull(h.routeIdentity())
            val searches = h.workPlans
            val position = npc.location
            for (pulse in 1..6) {
                h.work = if (pulse % 2 == 0) waiting.copy(standing = CivPosition(22 + pulse, 64, 0)) else null
                h.idleReason = "searching-work"
                h.navigationNow = 2_000L + pulse * 1_000
                h.owner.pulse(requireNotNull(h.navigationNow))
                withClue("food route replaced at pulse=$pulse ${h.navigationState()}") {
                    (h.routeIdentity() === route) shouldBe true
                    (actor.cycle === cycle) shouldBe true
                    actor.plan shouldBe plan
                }
                h.phase() shouldBe CitizenPhase.IDLE
                h.workPlans shouldBe searches
                npc.location shouldBe position
                foodPickups shouldBe 0
                h.owner.status(h.workerId) shouldBe "workers.status.moving-to-town-hall"
            }
            // The ordinary arrival gate admits exactly one native pickup; planning waits for its receipt.
            npc.teleport(Location(h.world, 1.5, 64.0, .5))
            h.owner.pulse(9_000)
            foodPickups shouldBe 1
            actor.supplyPickup?.returnOutputs shouldBe false
            h.workPlans shouldBe searches
            h.owner.pulse(10_000)
            foodPickups shouldBe 1
            h.workPlans shouldBe searches
            val before = h.city.workers.single()
            h.city = h.city.copy(workers = listOf(CivsEquipment.collectSupplies(before, mapOf("BREAD" to 2L))))
            h.physicalFoodSource = null
            foodReceipt.complete(h.city)
            h.owner.pulse(11_000)
            actor.foodSource shouldBe null
            actor.supplyPickup shouldBe null
            actor.plan.worldWork shouldBe h.work
            (h.workPlans > searches) shouldBe true
            (h.routeIdentity() === route) shouldBe false
            CivsEquipment.usableCount(h.city.workers.single(), "BREAD") shouldBe 2
            h.city.workers.single().completedCycles shouldBe before.completedCycles
            h.city.workers.single().nextDeliveryAt shouldBe before.nextDeliveryAt
            h.deliveries shouldBe 0
            h.pickups shouldBe 0
            h.unloads shouldBe 0
            h.observedWork() shouldBe 0L
        }
    }
})
