package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.util.BoundingBox
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsCitizenFoodRouteRetentionTest : FunSpec({
    test("physical food pickup waits for body transit to finish and its accepted receipt survives later player occupancy") {
        residentRuntime(workerId = UUID(0, 20)) { h ->
            for (x in -2..3) for (z in -2..2) h.world.loadChunk(x, z)
            val originalQuarry = h.assignQuarry()
            val quarry = originalQuarry.copy(anchor = CivPosition(20, 64, 10), entrance = CivPosition(20, 64, 10),
                control = CivPosition(20, 64, 10), bounds = CivBox(CivPosition(18, 64, 8), CivPosition(26, 68, 12)))
            h.city = CivsRules.validate(h.city.copy(buildings = h.city.buildings.map {
                if (it.id == quarry.id) quarry else it
            }))
            // Keep the ordinary work control valid and outside the tested corridor, which contains only the food barrel.
            h.world.getBlockAt(20, 64, 0).type = Material.AIR
            h.world.getBlockAt(20, 64, 10).type = Material.BARREL
            val hall = h.city.buildings.first { it.type == BuildingType.TOWN_HALL }.copy(control = CivPosition(0, 64, 0))
            h.world.getBlockAt(0, 64, 0).type = Material.BARREL
            h.city = h.city.copy(buildings = h.city.buildings.map { if (it.id == hall.id) hall else it },
                workers = h.city.workers.map { it.copy(hunger = 12) })
            h.work = CivWorldWork(h.workerId, 0, quarry.id, CivPosition(21, 64, 0), emptyList(),
                durationMillis = 60_000, status = "mining")
            for (x in -96..96) for (z in listOf(-1, 1)) for (y in 64..66)
                h.world.getBlockAt(x, y, z).type = Material.STONE
            val food = CivResidentFoodSource(hall.id, requireNotNull(hall.control), CivPosition(1, 64, 0))
            h.physicalFoodSource = food
            val npc = h.owner.collisionActors().single()
            npc.teleport(Location(h.world, 21.5, 64.0, .5))
            // Contact straddles the container reach boundary, exercising both navigation and coarse food fastpaths.
            val player = h.playerAt(Location(h.world, 3.8, 64.0, .5))
            val receipt = CompletableFuture<Settlement>()
            var pickups = 0
            fun overlapping(): Boolean {
                val at = npc.location
                return BoundingBox(at.x - .3, at.y, at.z - .3, at.x + .3, at.y + 1.8, at.z + .3)
                    .overlaps(player.boundingBox)
            }
            h.physicalFoodPickup = { selected ->
                selected shouldBe food
                civsCanReachContainer(npc.location, food.container) shouldBe true
                overlapping() shouldBe false
                pickups++
                receipt
            }
            h.nativeCollisions().use { collisions ->
                h.owner.residentStepAllowed = collisions::canResidentMove
                h.navigationNow = 2_000
                h.owner.pulse(2_000)
                val actor = h.owner.javaClass.getDeclaredField("actors").run {
                    isAccessible = true
                    requireNotNull((get(h.owner) as Map<*, *>)[h.workerId]) as CitizenActor
                }
                actor.plan.idleReason shouldBe null
                val canonical = h.city
                var contact = false
                var reachedWhileOverlapping = false
                for (tick in 1..1_000) {
                    val previous = npc.location
                    h.navigate()
                    if (tick % 20 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                    withClue("tick=$tick feet=${npc.location} ${h.navigationState()}") {
                        (previous.distance(npc.location) <= .215001) shouldBe true
                        citizenPacketSupported(h.world, CitizenPoint(npc.location.x, npc.location.y, npc.location.z)) shouldBe true
                        if (overlapping()) {
                            contact = true
                            reachedWhileOverlapping = reachedWhileOverlapping || civsCanReachContainer(npc.location, food.container)
                            h.owner.passingBodies(npc) shouldBe true
                            actor.supplyPickup shouldBe null
                            pickups shouldBe 0
                        }
                    }
                    if (pickups > 0) break
                }
                withClue("feet=${npc.location} ${h.navigationState()}") { pickups shouldBe 1 }
                contact shouldBe true
                reachedWhileOverlapping shouldBe true
                overlapping() shouldBe false
                val accepted = requireNotNull(actor.supplyPickup)
                val acceptedAt = npc.location
                player.teleport(acceptedAt)
                repeat(3) {
                    h.owner.pulse(requireNotNull(h.navigationNow) + (it + 1) * 1_000)
                    npc.location shouldBe acceptedAt
                    (actor.supplyPickup === accepted) shouldBe true
                    pickups shouldBe 1
                }
                h.city shouldBe canonical
                h.deliveries shouldBe 0
                h.pickups shouldBe 0
                h.unloads shouldBe 0
                h.observedWork() shouldBe 0L
            }
        }
    }

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
