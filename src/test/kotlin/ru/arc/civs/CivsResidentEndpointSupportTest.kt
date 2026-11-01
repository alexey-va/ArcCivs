package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.entity.Item
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID

class CivsResidentEndpointSupportTest : FunSpec({
    test("a resident on a native dirt path can receive exactly four existing bread while corpse support stays exact") {
        endpointFloor { paper, world ->
            val feet = Location(world, 1.5, 65.0, .5)
            val container = CivPosition(0, 65, 0)
            world.getBlockAt(1, 64, 0).type = Material.DIRT_PATH
            world.getBlockAt(0, 65, 0).type = Material.BARREL
            world.getBlockAt(1, 64, 0).collisionShape.boundingBoxes.single().maxY shouldBe .9375
            citizenPacketSupported(world, feet.point()) shouldBe true
            civsResidentStandingSafe(feet) shouldBe true
            civsCorpseStandingSafe(feet) shouldBe false
            civsCorpseStandingSafe(feet.clone().apply { y = 64.9375 }) shouldBe true
            civsCanReachContainer(feet, container) shouldBe true
            val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, container, 0, "test",
                CivBox(container.copy(x = -2, z = -2), container.copy(x = 2, y = 69, z = 2)),
                container.copy(z = -2), BuildingPhase.ACTIVE, container)
            val worker = CivWorker(UUID.randomUUID(), "Hungry resident", null, hunger = 14)
            val city = CivsRules.validate(Settlement(UUID.randomUUID(), UUID.randomUUID(), "Path food", world.uid,
                container, 16, "nordic", Stock(), listOf(hall), listOf(worker), inventory = CivInventory(CivStorageMode.PHYSICAL)))
            CivsStorage(paper.createSimplePlugin("PathFood")) { emptyArray() }.use { storage ->
                storage.foodCanModify = { _, _ -> true }
                storage.replaceCities(listOf(city))
                val food = (world.getBlockAt(0, 65, 0).state as Barrel).inventory
                food.setItem(0, ItemStack(Material.BREAD, 4))
                val id = UUID.randomUUID()
                storage.reserve(city, id)
                val prepared = storage.prepareFoodPickup(city, worker.id,
                    CivResidentFoodSource(hall.id, container, CivPosition(1, 65, 0)), feet)
                val tx = CivInventoryTransaction(id, city.id, city.revision,
                    city.copy(workers = listOf(prepared.worker), revision = city.revision + 1),
                    emptyList(), collected = requireNotNull(prepared.transfer))
                food.getItem(0) shouldBe ItemStack(Material.BREAD, 4)
                CivsEquipment.plainCount(prepared.worker, "BREAD") shouldBe 4
                storage.seal(tx)
                storage.apply(tx) shouldBe CivInventoryApplyResult.Applied
                storage.apply(tx) shouldBe CivInventoryApplyResult.Applied
                food.isEmpty shouldBe true
                CivsEquipment.plainCount(tx.after.workers.single(), "BREAD") shouldBe 4
                tx.after.stock shouldBe city.stock
                storage.unlock(id)
            }
        }
    }

    test("fractional slab support uses the packet tolerance but floating beyond thirteen hundredths fails") {
        endpointFloor { _, world ->
            world.getBlockAt(1, 64, 0).type = Material.STONE_SLAB
            val feet = Location(world, 1.5, 64.6, .5)
            citizenPacketSupported(world, feet.point()) shouldBe true
            civsResidentStandingSafe(feet) shouldBe true
            civsCorpseStandingSafe(feet) shouldBe false
            civsCorpseStandingSafe(feet.clone().apply { y = 64.5 }) shouldBe true
            civsResidentStandingSafe(feet.clone().apply { y = 64.629 }) shouldBe true
            civsResidentStandingSafe(feet.clone().apply { y = 64.631 }) shouldBe false
            civsResidentStandingSafe(feet.clone().apply { y = 64.7 }) shouldBe false
        }
    }

    for (condition in listOf("missing-support", "body-edge-unsupported", "magma", "liquid-support", "liquid-body", "head-blocked", "fire"))
    test("resident inventory support still rejects $condition") {
        endpointFloor { _, world ->
            world.getBlockAt(1, 64, 0).type = Material.DIRT_PATH
            world.getBlockAt(0, 65, 0).type = Material.BARREL
            val feet = Location(world, if (condition == "body-edge-unsupported") 1.95 else 1.5, 65.0, .5)
            when (condition) {
                "missing-support" -> world.getBlockAt(1, 64, 0).type = Material.AIR
                "magma" -> world.getBlockAt(1, 64, 0).type = Material.MAGMA_BLOCK
                "liquid-support" -> world.getBlockAt(1, 64, 0).type = Material.WATER
                "liquid-body" -> world.getBlockAt(1, 65, 0).type = Material.WATER
                "head-blocked" -> world.getBlockAt(1, 66, 0).type = Material.STONE
                "fire" -> world.getBlockAt(1, 66, 0).type = Material.FIRE
            }
            if (condition == "body-edge-unsupported") citizenPacketSupported(world, feet.point()) shouldBe true
            civsResidentStandingSafe(feet) shouldBe false
            civsCanReachContainer(feet, CivPosition(0, 65, 0)) shouldBe false
        }
    }

    test("an otherwise safe endpoint still requires the native container ray and loaded chunks") {
        endpointFloor { _, world ->
            val feet = Location(world, 1.5, 65.0, .5)
            world.getBlockAt(1, 64, 0).type = Material.DIRT_PATH
            world.getBlockAt(-1, 65, 0).type = Material.BARREL
            val target = CivPosition(-1, 65, 0)
            civsCanReachContainer(feet, target) shouldBe true
            world.getBlockAt(0, 66, 0).type = Material.STONE
            civsResidentStandingSafe(feet) shouldBe true
            civsCanReachContainer(feet, target) shouldBe false
            world.isChunkLoaded(16, 0) shouldBe false
            civsCanReachContainer(feet, CivPosition(256, 65, 0)) shouldBe false
            civsResidentStandingSafe(Location(world, 256.5, 65.0, .5)) shouldBe false
            world.isChunkLoaded(16, 0) shouldBe false
        }
    }

    test("living ground collection uses the same full support guard on a dirt path") {
        endpointFloor { _, world ->
            val feet = Location(world, 1.5, 65.0, .5)
            world.getBlockAt(1, 64, 0).type = Material.DIRT_PATH
            val item = mockk<Item>()
            every { item.world } returns world
            every { item.location } returns Location(world, 1.5, 65.0, .6)
            every { item.isValid } returns true
            every { item.isDead } returns false
            every { item.isOnGround } returns true
            every { item.pickupDelay } returns 0
            every { item.canMobPickup() } returns true
            every { item.canPlayerPickup() } returns true
            every { item.owner } returns null
            every { item.itemStack } returns ItemStack(Material.BREAD, 1)
            civsCanCollect(feet, item, UUID.randomUUID()) shouldBe true
            world.getBlockAt(1, 64, 0).type = Material.AIR
            civsCanCollect(feet, item, UUID.randomUUID()) shouldBe false
        }
    }
})

private fun endpointFloor(action: (MockBukkitTestRuntime, CivsWorksiteWorldFixture) -> Unit) =
    failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open().use { paper ->
            val world = CivsWorksiteWorldFixture("resident-endpoint", collisionForData = { data ->
                if (data.material == Material.STONE_SLAB) listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, .5, 1.0)) else null
            }).also(paper.server::addWorld)
            for (x in -1..1) for (z in -1..1) world.loadChunk(x, z)
            action(paper, world)
        }
    }
