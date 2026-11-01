package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsPhysicalFoodStanceContentionTest : FunSpec({
    test("hungry residents avoid an occupied food side and each other's reserved arrival while retaining their chosen side") {
        residentRuntime(server = CivsWorksiteServerFixture()) { h ->
            h.navigationNow = 2_000
            h.holdNavigation = true
            val quarry = h.assignQuarry()
            val container = CivPosition(0, 64, 0)
            val hall = h.city.buildings.first().copy(control = container, entrance = CivPosition(0, 64, -2))
            val blockerId = UUID(0, 101)
            val guestId = UUID(0, 102)
            h.city = CivsRules.validate(h.city.copy(buildings = listOf(hall, quarry),
                workers = h.city.workers.map { it.copy(hunger = 14) } +
                    CivWorker(blockerId, "Standing resident", null, hunger = 20) +
                    CivWorker(guestId, "Hungry guest", null, hunger = 14)))
            h.work = CivWorldWork(h.workerId, 0, quarry.id, CivPosition(21, 64, 0), emptyList(),
                outputs = mapOf("COBBLESTONE" to 1L), durationMillis = 60_000, status = "digging-mine")
            h.world.getBlockAt(0, 64, 0).type = Material.BARREL
            val food = (h.world.getBlockAt(0, 64, 0).state as Barrel).inventory
            food.setItem(0, ItemStack(Material.BREAD, 8))
            admitFoodStanceActors(h)
            val first = foodStanceActor(h, h.workerId)
            val second = foodStanceActor(h, guestId)
            val blocker = foodStanceActor(h, blockerId)
            first.npc.teleport(Location(h.world, 6.5, 64.0, .5))
            second.npc.teleport(Location(h.world, 6.5, 64.0, 3.5))
            blocker.npc.teleport(Location(h.world, 1.5, 64.0, .5))
            val source = CivResidentFoodSource(hall.id, container, CivPosition(1, 64, 0))
            civsCanReachContainer(first.npc.location, container) shouldBe false
            civsCanReachContainer(second.npc.location, container) shouldBe false
            h.physicalFoodSource = source
            var pickups = 0
            h.physicalFoodPickup = { selected ->
                val actor = listOf(first, second).single { it.foodPickupSource == selected }
                selected shouldBe requireNotNull(actor.foodPickupSource)
                foodStanceRoutes(h).arrivalBodyClear(actor) shouldBe true
                civsCanReachContainer(actor.npc.location, container) shouldBe true
                val worker = h.city.workers.single { it.id == actor.workerId }
                CivsEquipment.usableCount(worker, "BREAD") shouldBe 0
                val before = requireNotNull(food.getItem(0)).amount
                (before >= 4) shouldBe true
                food.setItem(0, (before - 4).takeIf { it > 0 }?.let { ItemStack(Material.BREAD, it) })
                val supplied = CivsEquipment.collectSupplies(worker, mapOf("BREAD" to 4L))
                h.city = h.city.copy(revision = h.city.revision + 1,
                    workers = h.city.workers.map { if (it.id == supplied.id) supplied else it })
                pickups++
                CompletableFuture.completedFuture(h.city)
            }
            val logistics = foodStanceLogistics(h)
            fun pulseFood() {
                val now = requireNotNull(h.navigationNow)
                logistics.drainSupplies(now)
                for (actor in listOf(first, second)) logistics.supplyFood(actor, h.city,
                    h.city.workers.single { it.id == actor.workerId }, h.world, now)
            }
            h.navigationNow = requireNotNull(h.navigationNow) + 1_000
            pulseFood()
            val firstSource = withClue(h.owner.debug(h.city.id, h.workerId)) { requireNotNull(first.foodPickupSource) }
            val secondSource = withClue(h.owner.debug(h.city.id, guestId)) { requireNotNull(second.foodPickupSource) }
            setOf(firstSource.standing, secondSource.standing) shouldBe setOf(CivPosition(-1, 64, 0), CivPosition(0, 64, 1))
            val firstRoute = requireNotNull(first.route)
            val secondRoute = requireNotNull(second.route)
            firstRoute.authored shouldBe first.foodPickupSource!!.standing
            secondRoute.authored shouldBe second.foodPickupSource!!.standing
            first.npc.location.blockX shouldBe 6 // The reservation, rather than a body at that side, excludes the second resident.
            pickups shouldBe 0
            food.getItem(0) shouldBe ItemStack(Material.BREAD, 8)
            // Releasing the old first side must not send both residents back to it.
            blocker.npc.teleport(Location(h.world, 6.5, 64.0, 6.5))
            h.navigationNow = requireNotNull(h.navigationNow) + 1_000
            pulseFood()
            (first.route === firstRoute) shouldBe true
            (second.route === secondRoute) shouldBe true
            first.foodPickupSource shouldBe firstSource
            second.foodPickupSource shouldBe secondSource
            h.releaseNavigation()
            for (tick in 1..1_000) {
                h.navigate()
                if (tick % 10 == 0) pulseFood()
                if (pickups == 2) break
            }
            withClue(h.owner.debug(h.city.id, null)) { pickups shouldBe 2 }
            food.isEmpty shouldBe true
            h.city.workers.filter { it.id in setOf(h.workerId, guestId) }.sumOf {
                CivsEquipment.usableCount(it, "BREAD")
            } shouldBe 8
            CivsEquipment.usableCount(h.city.workers.single { it.id == blockerId }, "BREAD") shouldBe 0
            h.city.stock shouldBe Stock()
            h.deliveries shouldBe 0
            h.supplyRequests shouldBe 0
        }
    }

    for (alreadyInReach in listOf(false, true))
    test("occupied canonical food sides preserve native actual-feet admission: alreadyInReach=$alreadyInReach") {
        residentRuntime(server = CivsWorksiteServerFixture()) { h ->
            h.navigationNow = 2_000
            h.holdNavigation = true
            val quarry = h.assignQuarry()
            val container = CivPosition(0, 64, 0)
            val hall = h.city.buildings.first().copy(control = container, entrance = CivPosition(0, 64, -2))
            val sides = listOf(CivPosition(1, 64, 0), CivPosition(-1, 64, 0), CivPosition(0, 64, 1), CivPosition(0, 64, -1))
            val blockers = sides.mapIndexed { index, _ -> CivWorker(UUID(0, 200L + index), "Standing resident $index", null, hunger = 20) }
            h.city = CivsRules.validate(h.city.copy(buildings = listOf(hall, quarry),
                workers = h.city.workers.map { it.copy(hunger = 14) } + blockers))
            h.work = CivWorldWork(h.workerId, 0, quarry.id, CivPosition(21, 64, 0), emptyList(),
                durationMillis = 60_000, status = "digging-mine")
            h.world.getBlockAt(0, 64, 0).type = Material.BARREL
            val food = (h.world.getBlockAt(0, 64, 0).state as Barrel).inventory
            food.setItem(0, ItemStack(Material.BREAD, 4))
            admitFoodStanceActors(h)
            val actor = foodStanceActor(h, h.workerId)
            actor.npc.teleport(if (alreadyInReach) Location(h.world, 2.5, 64.0, 1.5) else Location(h.world, 6.5, 64.0, 6.5))
            blockers.zip(sides).forEach { (worker, side) -> foodStanceActor(h, worker.id).npc.teleport(side.location(h.world)) }
            val source = CivResidentFoodSource(hall.id, container, sides.first())
            h.physicalFoodSource = source
            civsCanReachContainer(actor.npc.location, container) shouldBe alreadyInReach
            foodStanceRoutes(h).foodStanding(actor, h.city, source) shouldBe null
            var pickups = 0
            h.physicalFoodPickup = { selected ->
                alreadyInReach shouldBe true
                selected shouldBe source
                foodStanceRoutes(h).arrivalBodyClear(actor) shouldBe true
                civsCanReachContainer(actor.npc.location, container) shouldBe true
                food.getItem(0) shouldBe ItemStack(Material.BREAD, 4)
                val worker = h.city.workers.single { it.id == h.workerId }
                CivsEquipment.usableCount(worker, "BREAD") shouldBe 0
                food.setItem(0, null)
                val supplied = CivsEquipment.collectSupplies(worker, mapOf("BREAD" to 4L))
                h.city = h.city.copy(revision = h.city.revision + 1,
                    workers = h.city.workers.map { if (it.id == supplied.id) supplied else it })
                pickups++
                CompletableFuture.completedFuture(h.city)
            }
            val before = h.city
            val logistics = foodStanceLogistics(h)
            repeat(if (alreadyInReach) 1 else 3) {
                h.navigationNow = requireNotNull(h.navigationNow) + 6_000
                logistics.supplyFood(actor, h.city, h.city.workers.single { it.id == h.workerId },
                    h.world, requireNotNull(h.navigationNow))
                actor.foodPickupSource shouldBe source
                actor.foodSource shouldBe hall.id
                if (alreadyInReach) {
                    withClue(mapOf("yield" to actor.residentYield, "phase" to actor.cycle.phase,
                        "retryAt" to actor.supplyRetryAt, "now" to h.navigationNow,
                        "nativeReach" to civsCanReachContainer(actor.npc.location, container),
                        "arrivalBodyClear" to foodStanceRoutes(h).arrivalBodyClear(actor),
                        "bodies" to h.owner.collisionActors().map { it.uniqueId to it.location.point() })) { pickups shouldBe 1 }
                    (actor.supplyPickup != null) shouldBe true
                    actor.route shouldBe null
                    food.isEmpty shouldBe true
                    CivsEquipment.usableCount(h.city.workers.single { it.id == h.workerId }, "BREAD") shouldBe 4
                    h.city.stock shouldBe before.stock
                } else {
                    pickups shouldBe 0
                    actor.supplyPickup shouldBe null
                    requireNotNull(actor.route).authored shouldBe source.standing // Keep existing motion separation available.
                    food.getItem(0) shouldBe ItemStack(Material.BREAD, 4)
                    h.city shouldBe before
                }
                h.deliveries shouldBe 0
                h.supplyRequests shouldBe 0
            }
        }
    }
})

private fun foodStanceActor(h: ResidentRuntime, workerId: UUID): CitizenActor = h.owner.javaClass.getDeclaredField("actors").run {
    isAccessible = true
    requireNotNull((get(h.owner) as Map<*, *>)[workerId]) as CitizenActor
}

private fun foodStanceLogistics(h: ResidentRuntime): CivsCitizenLogistics = h.owner.javaClass.getDeclaredField("logistics").run {
    isAccessible = true
    get(h.owner) as CivsCitizenLogistics
}

private fun foodStanceRoutes(h: ResidentRuntime): CivsCitizenRoutes = h.owner.javaClass.getDeclaredField("routes").run {
    isAccessible = true
    get(h.owner) as CivsCitizenRoutes
}

private fun admitFoodStanceActors(h: ResidentRuntime) {
    h.world.time = 0
    // Food's western/northern alternatives cross block zero; their existing route margin includes chunk -2.
    for (x in -2..2) for (z in -2..2) h.world.loadChunk(x, z)
    // The production owner admits at most two new actors per pulse; blockers must exist before the tested food choice.
    repeat(4) {
        if (h.owner.collisionActors().size < h.city.workers.size) {
            h.navigationNow = requireNotNull(h.navigationNow) + 1_000
            h.owner.pulse(requireNotNull(h.navigationNow))
        }
    }
    withClue(h.owner.debug(h.city.id, null)) { h.owner.collisionActors().size shouldBe h.city.workers.size }
    val life = h.owner.javaClass.getDeclaredField("life").run {
        isAccessible = true
        get(h.owner) as CivsCitizenLife
    }
    h.city.workers.forEach {
        val actor = foodStanceActor(h, it.id)
        life.stopMeal(actor)
        foodStanceRoutes(h).cancelRoute(actor)
        actor.supplyRetryAt = 0
    }
}
