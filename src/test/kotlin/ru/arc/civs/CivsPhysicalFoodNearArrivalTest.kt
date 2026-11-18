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

class CivsPhysicalFoodNearArrivalTest : FunSpec({
    test("a hungry resident transfers food from its nearby barrel through a wall without planning a route") {
        residentRuntime(server = CivsWorksiteServerFixture()) { h ->
            val container = CivPosition(0, 64, 0)
            val hall = h.city.buildings.single().copy(control = container)
            h.city = h.city.copy(buildings = listOf(hall), inventory = CivInventory(CivStorageMode.PHYSICAL),
                workers = h.city.workers.map { it.copy(hunger = 14) })
            val block = h.world.getBlockAt(container.x, container.y, container.z).also { it.type = Material.BARREL }
            val food = (block.state as Barrel).inventory
            food.setItem(0, ItemStack(Material.BREAD, 8))
            for (y in 64..65) h.world.getBlockAt(1, y, 0).type = Material.STONE
            val feet = Location(h.world, 2.5, 64.0, .5)
            val actor = h.actor()
            actor.npc.teleport(feet)
            civsCanReachContainer(feet, container) shouldBe false

            CivsStorage(h.paper.createSimplePlugin("NearFoodReceipt")) { emptyArray() }.use { storage ->
                storage.foodCanModify = { _, point -> point == container }
                storage.replaceCities(listOf(h.city))
                storage.capturePlanningStorage(h.city)
                val source = requireNotNull(storage.residentFoodSource(h.city, h.city.workers.single(), feet))
                source shouldBe CivResidentFoodSource(hall.id, container, CivPosition(2, 64, 0))
                h.physicalFoodSource = source
                var pickups = 0
                h.physicalFoodPickup = { selected ->
                    selected shouldBe source
                    pickups++
                    applyFoodReceipt(h, storage, selected, actor.npc.location)
                }
                val captures = h.world.chunkSnapshotCaptures
                h.holdNavigation = true
                h.navigationNow = 2_000
                h.owner.pulse(2_000)
                h.navigate()
                pickups shouldBe 1
                food.getItem(0) shouldBe ItemStack(Material.BREAD, 4)
                CivsEquipment.usableCount(h.city.workers.single(), "BREAD") shouldBe 4
                actor.npc.location.point() shouldBe feet.point()
                actor.route shouldBe null
                h.pendingNavigationWorkers shouldBe 0
                h.world.chunkSnapshotCaptures shouldBe captures
            }
        }
    }

    test("a distant resident walks around a wall to food even when all four adjacent stances are blocked") {
        residentRuntime(server = CivsWorksiteServerFixture()) { h ->
            for (x in -2..1) for (z in -2..1) h.world.loadChunk(x, z)
            val container = CivPosition(0, 64, 0)
            val hall = h.city.buildings.single().copy(control = container)
            h.city = h.city.copy(buildings = listOf(hall), inventory = CivInventory(CivStorageMode.PHYSICAL),
                workers = h.city.workers.map { it.copy(hunger = 14) })
            val block = h.world.getBlockAt(0, 64, 0).also { it.type = Material.BARREL }
            val food = (block.state as Barrel).inventory
            food.setItem(0, ItemStack(Material.BREAD, 8))

            // The only walkable route bends south then west. The far end is within food reach through stone.
            val corridor = (2..6).map { it to 2 }.toSet() + setOf(6 to 0, 6 to 1)
            // This closer stance is usable for pickup but cannot be reached from the corridor.
            val isolated = CivPosition(-1, 64, 1)
            for (x in -1..7) for (z in -1..3) {
                if (x == 0 && z == 0 || (x to z) in corridor || x == isolated.x && z == isolated.z) continue
                for (y in 64..66) h.world.getBlockAt(x, y, z).type = Material.STONE
            }
            citizenCollectedHomeTarget(h.city, hall, h.world, container) shouldBe null
            civsCanReachContainer(isolated.location(h.world), container, nearbyFoodPickup = true) shouldBe true
            val start = Location(h.world, 6.5, 64.0, .5)
            val actor = h.actor()
            actor.npc.teleport(start)
            civsWithinFoodPickupReach(start, container) shouldBe false
            civsCanReachContainer(start, container) shouldBe false

            CivsStorage(h.paper.createSimplePlugin("DistantFoodReceipt")) { emptyArray() }.use { storage ->
                storage.foodCanModify = { _, point -> point == container }
                storage.replaceCities(listOf(h.city))
                storage.capturePlanningStorage(h.city)
                val source = requireNotNull(storage.residentFoodSource(h.city, h.city.workers.single(), start))
                source.container shouldBe container
                source.buildingId shouldBe hall.id
                h.physicalFoodSource = source
                var pickups = 0
                h.physicalFoodPickup = { selected ->
                    selected.copy(standing = source.standing) shouldBe source
                    val feet = actor.npc.location
                    (feet.blockX to feet.blockZ) shouldBe (2 to 2)
                    civsCanReachContainer(feet, container) shouldBe false
                    civsCanReachContainer(feet, container, nearbyFoodPickup = true) shouldBe true
                    pickups++
                    applyFoodReceipt(h, storage, selected, feet)
                }
                h.navigationNow = 2_000
                h.owner.pulse(2_000)
                val approach = withClue(h.navigationState()) { requireNotNull(actor.route) }
                pickups shouldBe 0
                for (tick in 1..400) {
                    val before = actor.npc.location
                    h.navigate()
                    val feet = actor.npc.location
                    (feet.distance(before) <= .215001) shouldBe true
                    ((feet.blockX to feet.blockZ) in corridor) shouldBe true
                    citizenPacketBodyClear(h.world, feet.point(), false) shouldBe true
                    if (tick % 5 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                    if (pickups != 0) break
                    (actor.route === approach) shouldBe true
                }
                withClue(h.navigationState()) { pickups shouldBe 1 }
                food.getItem(0) shouldBe ItemStack(Material.BREAD, 4)
                CivsEquipment.usableCount(h.city.workers.single(), "BREAD") shouldBe 4
            }
        }
    }

    test("a hungry worker inside the stance tolerance still walks into native container reach then returns to work") {
        residentRuntime(server = CivsWorksiteServerFixture()) { h ->
            h.navigationNow = 2_000
            val quarry = h.assignQuarry()
            val container = CivPosition(0, 64, 0)
            val hall = h.city.buildings.first().copy(control = container)
            h.world.getBlockAt(0, 64, 0).type = Material.BARREL
            val food = (h.world.getBlockAt(0, 64, 0).state as Barrel).inventory
            food.setItem(0, ItemStack(Material.BREAD, 4))
            h.city = CivsRules.validate(h.city.copy(buildings = listOf(hall, quarry),
                workers = h.city.workers.map { it.copy(hunger = 14) }))
            val workPoint = CivPosition(21, 64, 0)
            val workControl = requireNotNull(quarry.control)
            h.work = CivWorldWork(h.workerId, 0, quarry.id, workPoint, emptyList(),
                outputs = mapOf("COBBLESTONE" to 1L), durationMillis = 60_000, status = "digging-mine")
            h.workReady = false
            val source = CivResidentFoodSource(hall.id, container, CivPosition(3, 64, 2))
            h.physicalFoodSource = source
            val npc = h.owner.collisionActors().single()
            npc.teleport(Location(h.world, 3.75, 64.0, 2.5))
            val before = npc.location
            citizenAtSupportedEndpoint(h.world, before.point(), source.standing) shouldBe true
            civsCanReachContainer(before, container) shouldBe false
            civsCanReachContainer(source.standing.location(h.world), container) shouldBe true
            var pickups = 0
            h.physicalFoodPickup = { selected ->
                selected shouldBe source
                civsCanReachContainer(npc.location, container) shouldBe true
                pickups++
                pickups shouldBe 1
                food.getItem(0) shouldBe ItemStack(Material.BREAD, 4)
                val worker = h.city.workers.single()
                CivsEquipment.usableCount(worker, "BREAD") shouldBe 0
                val supplied = CivsEquipment.collectSupplies(worker, mapOf("BREAD" to 4L))
                food.setItem(0, null)
                h.city = h.city.copy(revision = h.city.revision + 1, workers = listOf(supplied))
                CompletableFuture.completedFuture(h.city)
            }
            h.owner.pulse(2_000)
            pickups shouldBe 0
            requireNotNull(h.routeIdentity()) // The coarse stance tolerance must not suppress the final native approach.
            for (tick in 1..1_000) {
                h.navigate()
                if (citizenAtStorage(h.world, npc.location.point(), workControl) &&
                    citizenCanReachBlock(npc, workControl)) h.workReady = true
                if (tick % 10 == 0) h.owner.pulse(requireNotNull(h.navigationNow))
                if (pickups == 0) {
                    food.getItem(0) shouldBe ItemStack(Material.BREAD, 4)
                    CivsEquipment.usableCount(h.city.workers.single(), "BREAD") shouldBe 0
                }
                if (pickups == 1 && h.workReady && h.phase() == CitizenPhase.WORKING) break
            }
            withClue(h.owner.debug(h.city.id, h.workerId)) {
                pickups shouldBe 1
                h.workReady shouldBe true
                h.phase() shouldBe CitizenPhase.WORKING
            }
            (npc.location.distance(before) > 2.0) shouldBe true
            citizenAtStorage(h.world, npc.location.point(), workControl) shouldBe true
            citizenCanReachBlock(npc, workControl) shouldBe true
            food.isEmpty shouldBe true
            CivsEquipment.usableCount(h.city.workers.single(), "BREAD") shouldBe 4
            h.deliveries shouldBe 0
            h.supplyRequests shouldBe 0
        }
    }
})

private fun applyFoodReceipt(h: ResidentRuntime, storage: CivsStorage, source: CivResidentFoodSource,
    feet: Location): CompletableFuture<Settlement> {
    val id = UUID.randomUUID()
    val before = h.city
    storage.reserve(before, id)
    try {
        val plan = storage.prepareFoodPickup(before, h.workerId, source, feet)
        val after = before.copy(workers = listOf(plan.worker), revision = before.revision + 1)
        val receipt = CivInventoryTransaction(id, before.id, before.revision, after, emptyList(),
            collected = requireNotNull(plan.transfer))
        storage.seal(receipt)
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        h.city = after
        return CompletableFuture.completedFuture(after)
    } finally {
        storage.unlock(id)
    }
}
