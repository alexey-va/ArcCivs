package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import java.util.concurrent.CompletableFuture

class CivsCitizenArrivalBodyTest : FunSpec({
    test("an unmarked player overlap blocks a new mine arrival until clear without revoking its later accepted work receipt") {
        residentRuntime { h ->
            val work = h.prepareMineWork(CivPosition(23, 64, 0), focus = CivPosition(26, 64, 0))
            val npc = h.owner.collisionActors().single()
            h.holdNavigation = true
            h.navigationNow = 2_000
            h.owner.pulse(2_000)
            val actor = arrivalActor(h)
            h.phase() shouldBe CitizenPhase.TO_WORK
            actor.plan.worldWork shouldBe work
            actor.bodyContactToken shouldBe null
            requireNotNull(h.routeIdentity())

            // The actor is already at a valid authored stance when a player enters before any collision sweep.
            npc.teleport(Location(h.world, 23.5, 64.0, .5))
            citizenPacketBodyClear(h.world, npc.location.point(), false) shouldBe true
            citizenPacketSupported(h.world, npc.location.point()) shouldBe true
            citizenCanReachBlock(npc, requireNotNull(work.focus), CITIZEN_MINE_REACH) shouldBe true
            val player = h.playerAt(npc.location)
            val canonical = h.city
            h.nativeCollisions().use { collisions ->
                h.owner.residentStepAllowed = collisions::canResidentMove
                for (now in listOf(2_500L, 3_000L, 4_000L)) {
                    h.owner.pulse(now)
                    actor.bodyContactToken shouldBe null
                    h.phase() shouldBe CitizenPhase.TO_WORK
                    h.observedWork() shouldBe 0L
                    h.deliveries shouldBe 0
                    h.pickups shouldBe 0
                    h.unloads shouldBe 0
                    h.work shouldBe work
                    h.city shouldBe canonical
                }

                player.teleport(Location(h.world, 24.5, 68.0, 4.5))
                h.owner.pulse(4_500)
                h.phase() shouldBe CitizenPhase.WORKING
                h.routeIdentity() shouldBe null
                h.observedWork() shouldBe 0L
                h.work shouldBe work

                // Once arrival is accepted, later player occupancy cannot invalidate the same paid work receipt.
                player.teleport(npc.location)
                h.owner.pulse(5_000)
                h.phase() shouldBe CitizenPhase.WORKING
                h.observedWork() shouldBe 500L
                h.owner.pulse(6_000)
                h.phase() shouldBe CitizenPhase.DELIVERING
                h.deliveries shouldBe 1
                h.pendingDelivery.isDone shouldBe false
                h.owner.requireWorldWorkArrival(h.city.id, h.workerId, work)
                h.work shouldBe work
                h.city shouldBe canonical
            }
        }
    }

    test("an unmarked route-null food arrival waits for a clear body then retains its accepted pickup through later occupancy") {
        residentRuntime { h ->
            val quarry = h.assignQuarry()
            h.city = CivsRules.validate(h.city.copy(workers = h.city.workers.map { it.copy(hunger = 12) }))
            h.work = CivWorldWork(h.workerId, 0, quarry.id, CivPosition(21, 64, 0), emptyList(),
                durationMillis = 60_000, status = "mining")
            val food = CivResidentFoodSource(quarry.id, requireNotNull(quarry.control), CivPosition(21, 64, 0))
            h.physicalFoodSource = food
            val npc = h.owner.collisionActors().single()
            npc.teleport(Location(h.world, 21.5, 64.0, .5))
            civsCanReachContainer(npc.location, food.container) shouldBe true
            val actor = arrivalActor(h)
            actor.bodyContactToken shouldBe null
            val player = h.playerAt(npc.location)
            val canonical = h.city
            val receipt = CompletableFuture<Settlement>()
            var pickups = 0
            h.physicalFoodPickup = { selected ->
                selected shouldBe food
                h.owner.requireStorageArrival(h.city.id, h.workerId, food.buildingId)
                pickups++
                receipt
            }
            h.nativeCollisions().use { collisions ->
                h.owner.residentStepAllowed = collisions::canResidentMove
                h.holdNavigation = true
                h.navigationNow = 2_000
                h.owner.pulse(2_000)
                actor.bodyContactToken shouldBe null
                actor.foodPickupSource shouldBe food
                actor.supplyPickup shouldBe null
                pickups shouldBe 0
                h.observedWork() shouldBe 0L
                h.city shouldBe canonical

                player.teleport(Location(h.world, 24.5, 68.0, 4.5))
                h.owner.pulse(3_000)
                pickups shouldBe 1
                val accepted = requireNotNull(actor.supplyPickup)
                actor.route shouldBe null
                h.owner.requireStorageArrival(h.city.id, h.workerId, food.buildingId)
                player.teleport(npc.location)
                h.owner.pulse(3_500)
                (actor.supplyPickup === accepted) shouldBe true
                h.owner.requireStorageArrival(h.city.id, h.workerId, food.buildingId)
                pickups shouldBe 1
                receipt.isDone shouldBe false
                h.observedWork() shouldBe 0L
                h.deliveries shouldBe 0
                h.pickups shouldBe 0
                h.unloads shouldBe 0
                h.city shouldBe canonical
            }
        }
    }
})

private fun arrivalActor(h: ResidentRuntime): CitizenActor = h.owner.javaClass.getDeclaredField("actors").run {
    isAccessible = true
    requireNotNull((get(h.owner) as Map<*, *>)[h.workerId]) as CitizenActor
}
