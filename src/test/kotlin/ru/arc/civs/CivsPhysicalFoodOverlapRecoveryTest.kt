package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsPhysicalFoodOverlapRecoveryTest : FunSpec({
    for ((playerAtSource, barrelX) in listOf(false to 0, true to 0, false to 1)) {
        test("finished food approach escapes actual resident overlap without borrowing arrival: player=$playerAtSource barrelX=$barrelX") {
            residentRuntime(server = CivsWorksiteServerFixture()) { h ->
                h.navigationNow = 2_000
                h.holdNavigation = true
                h.world.time = 0
                for (x in -2..2) for (z in -2..2) h.world.loadChunk(x, z)
                val quarry = h.assignQuarry()
                val barrel = CivPosition(barrelX, 64, 0)
                val hall = h.city.buildings.first().copy(control = barrel, entrance = CivPosition(0, 64, -2))
                val blockers = (1L..4L).map { CivWorker(UUID(12, it), "Fed resident $it", null, hunger = 20) }
                h.city = CivsRules.validate(h.city.copy(buildings = listOf(hall, quarry),
                    workers = h.city.workers.map { it.copy(hunger = 0) } + blockers))
                h.work = CivWorldWork(h.workerId, 0, quarry.id, CivPosition(21, 64, 0), emptyList(),
                    outputs = mapOf("COBBLESTONE" to 1L), durationMillis = 60_000, status = "digging-mine")
                h.world.getBlockAt(barrel.x, barrel.y, barrel.z).type = Material.BARREL
                val inventory = (h.world.getBlockAt(barrel.x, barrel.y, barrel.z).state as Barrel).inventory
                inventory.setItem(0, ItemStack(Material.BREAD, 4))
                repeat(4) {
                    h.navigationNow = requireNotNull(h.navigationNow) + 1_000
                    h.owner.pulse(requireNotNull(h.navigationNow))
                }
                h.owner.collisionActors().size shouldBe 5
                val routes = foodOverlapField(h, "routes") as CivsCitizenRoutes
                val logistics = foodOverlapField(h, "logistics") as CivsCitizenLogistics
                val actor = h.actor()
                val others = blockers.map { h.actor(it.id) }
                (listOf(actor) + others).forEach { routes.cancelRoute(it) }
                h.releaseNavigation()

                val stance = CivPosition(3, 64, 0)
                val origin = stance.location(h.world)
                val offsets = listOf(.25 to 0.0, -.25 to 0.0, 0.0 to .25, 0.0 to -.25)
                actor.npc.teleport(origin)
                others.zip(offsets).forEach { (other, offset) ->
                    other.npc.teleport(origin.clone().add(offset.first, 0.0, offset.second))
                }
                val occupied = others.map { citizenStandingBounds(it.npc.location, it.npc.scale) }
                occupied.all { it.overlaps(citizenStandingBounds(origin, actor.npc.scale)) } shouldBe true
                offsets.forEach { (x, z) ->
                    citizenResidentStepClear(actor.npc, origin, origin.clone().add(x / 5, 0.0, z / 5), occupied) shouldBe false
                }
                val staging = stance.copy(x = stance.x + 1)
                citizenStandable(h.world, staging) shouldBe true
                civsCanReachContainer(origin, barrel) shouldBe true
                val reachableFromStaging = barrelX == 1
                civsCanReachContainer(staging.location(h.world), barrel) shouldBe reachableFromStaging

                val source = CivResidentFoodSource(hall.id, barrel, stance)
                h.physicalFoodSource = source
                actor.foodPickupSource = source
                actor.foodSource = hall.id
                actor.supplyRetryAt = 0
                val now = requireNotNull(h.navigationNow)
                actor.cycle.deferWork(now)
                // Only the already-finished planner result is injected. The motion owner still
                // sees real feet, collision boxes, food identity, timers and an unaccepted arrival.
                val motion = mockk<CitizenPacketRoute>(relaxed = true) {
                    every { finished } returns true
                    every { failure } returns null
                    every { searching } returns false
                    every { continuation } returns false
                    every { waitingForCollision } returns false
                    every { remainingNodes } returns 0
                    every { nodeCount } returns 2
                }
                val finished = CitizenRoute(origin.point(), origin, CitizenChunks.between(stance.x, stance.z, stance.x, stance.z),
                    now, 12_000, setOf(hall.id), motion, null, stance)
                actor.route = finished
                val before = h.city
                var foodPickups = 0
                h.physicalFoodPickup = { selected ->
                    selected.buildingId shouldBe hall.id
                    selected.container shouldBe barrel
                    routes.arrivalBodyClear(actor) shouldBe true
                    civsCanReachContainer(actor.npc.location, barrel) shouldBe true
                    // The real food owner snapshots feet and checks container reach, not cargo arrival.
                    h.owner.residentFeet(actor.workerId)?.point() shouldBe actor.npc.location.point()
                    inventory.getItem(0) shouldBe ItemStack(Material.BREAD, 4)
                    inventory.setItem(0, null)
                    val worker = h.city.workers.single { it.id == actor.workerId }
                    val supplied = CivsEquipment.collectSupplies(worker, mapOf("BREAD" to 4L))
                    h.city = h.city.copy(revision = h.city.revision + 1,
                        workers = h.city.workers.map { if (it.id == supplied.id) supplied else it })
                    foodPickups++
                    CompletableFuture.completedFuture(h.city)
                }
                fun foodPulse() {
                    val at = requireNotNull(h.navigationNow)
                    logistics.drainSupplies(at)
                    logistics.supplyFood(actor, h.city, h.city.workers.single { it.id == actor.workerId }, h.world, at)
                }
                h.nativeCollisions().use { native ->
                    h.owner.residentStepAllowed = native::canResidentMove
                    val player = if (playerAtSource) h.playerAt(origin) else null
                    routes.arrivalBodyClear(actor) shouldBe false
                    h.clearRecordedCalls()
                    h.navigate()
                    withClue(h.owner.debug(h.city.id, actor.workerId)) {
                        blockers.any { it.id == actor.collisionResident } shouldBe true
                    }
                    routes.arrivalBodyClear(actor) shouldBe false
                    finished.motion.remainingNodes shouldBe 0
                    finished.completion shouldBe null
                    for (tick in 1..199) {
                        h.navigate()
                        if (actor.npc.location.distanceSquared(origin) >= .25) break
                    }
                    finished.timedOut(requireNotNull(h.navigationNow)) shouldBe false
                    foodPickups shouldBe 0
                    inventory.getItem(0) shouldBe ItemStack(Material.BREAD, 4)
                    actor.supplyPickup shouldBe null
                    h.observedWork() shouldBe 0L
                    h.deliveries shouldBe 0
                    h.pickups shouldBe 0
                    h.unloads shouldBe 0
                    h.city shouldBe before
                    if (player != null) {
                        actor.npc.location.point() shouldBe origin.point()
                        player.location.point() shouldBe origin.point()
                        verify(exactly = 0) { actor.npc.teleport(any()) }
                    } else {
                        val after = actor.npc.location
                        withClue(h.owner.debug(h.city.id, actor.workerId)) { after.point() shouldBe staging.point() }
                        verify(exactly = 1) { actor.npc.teleport(any()) }
                        routes.arrivalBodyClear(actor) shouldBe true
                        civsCanReachContainer(after, barrel) shouldBe reachableFromStaging
                        (actor.route !== finished) shouldBe true
                        actor.foodSource shouldBe hall.id
                        if (!reachableFromStaging) {
                            // A fresh route earns reach after the test crowd has left.
                            others.forEachIndexed { index, other ->
                                other.npc.teleport(Location(h.world, 8.5, 64.0, 4.5 + index))
                            }
                        }
                        h.navigationNow = requireNotNull(h.navigationNow) + 5_001
                        foodPulse()
                        if (!reachableFromStaging) {
                            requireNotNull(actor.route).from shouldBe after.point()
                            foodPickups shouldBe 0
                            for (tick in 1..220) {
                                h.navigate()
                                if (tick % 10 == 0) foodPulse()
                                if (foodPickups == 1) break
                            }
                        } else actor.npc.location.point() shouldBe after.point()
                        withClue(h.owner.debug(h.city.id, actor.workerId)) { foodPickups shouldBe 1 }
                        inventory.isEmpty shouldBe true
                        CivsEquipment.usableCount(h.city.workers.single { it.id == actor.workerId }, "BREAD") shouldBe 4
                        h.observedWork() shouldBe 0L
                        h.deliveries shouldBe 0
                        h.pickups shouldBe 0
                        h.unloads shouldBe 0
                    }
                }
            }
        }
    }
})

private fun foodOverlapField(h: ResidentRuntime, field: String): Any = h.owner.javaClass.getDeclaredField(field).run {
    isAccessible = true
    requireNotNull(get(h.owner))
}
