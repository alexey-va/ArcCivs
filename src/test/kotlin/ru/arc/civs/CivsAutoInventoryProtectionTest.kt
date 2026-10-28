package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Container
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.DoubleChest
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.inventory.*
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsAutoInventoryProtectionTest : FunSpec({
    for (registered in listOf(false, true)) test("physical Auto ${if (registered) "registered" else "workplace"} chest is public read-only including its owner") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("native-auto-$registered")
            val plugin = paper.createSimplePlugin("AutoInventoryProtection")
            val owner = paper.addPlayer("Owner")
            val visitor = paper.addPlayer("Visitor")
            val admin = paper.addPlayer("Admin").apply { addAttachment(plugin, "arccivs.admin", true) }
            val point = CivPosition(if (registered) 8 else 2, 64, 1)
            val city = inventoryProtectionCity(world.uid, owner.uniqueId, point.takeIf { registered })
            val protection = CivsProtection({ listOf(city) })
            val block = world.getBlockAt(point.x, point.y, point.z).apply { type = Material.CHEST }
            val holder = block.state as Container
            val inventory = nativeInventory(holder)
            val clicked = mockk<Block> {
                every { location } returns block.location
                every { getState(false) } returns mockk<Container> { every { this@mockk.inventory } returns inventory }
            }
            val outside = mockk<Inventory> {
                every { getHolder(false) } returns null
                every { location } returns Location(world, 30.0, 64.0, 0.0)
            }
            for (actor in listOf(owner, visitor, admin)) {
                actor.addAttachment(plugin, "arccivs.use", true)
                actor.teleport(Location(world, point.x + .5, 64.0, point.z + 2.5))
                val view = nativeView(inventory, actor)
                InventoryOpenEvent(view).also(protection::open).isCancelled shouldBe false
                val interact = PlayerInteractEvent(actor, Action.RIGHT_CLICK_BLOCK, null, clicked, BlockFace.NORTH, EquipmentSlot.HAND)
                val priorBlockUse = interact.useInteractedBlock()
                protection.interact(interact)
                interact.useInteractedBlock() shouldBe priorBlockUse
                interact.useItemInHand() shouldBe Event.Result.DENY
                for (click in listOf(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT,
                    ClickType.NUMBER_KEY, ClickType.SWAP_OFFHAND, ClickType.DOUBLE_CLICK, ClickType.DROP, ClickType.CONTROL_DROP)) {
                    InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, click, InventoryAction.UNKNOWN)
                        .also(protection::click).isCancelled shouldBe (actor != admin)
                }
                InventoryCreativeEvent(view, InventoryType.SlotType.CONTAINER, 0, ItemStack(Material.DIAMOND))
                    .also(protection::click).isCancelled shouldBe (actor != admin)
                InventoryDragEvent(view, null, ItemStack(Material.STONE, 2), false, mapOf(0 to ItemStack(Material.STONE)))
                    .also(protection::drag).isCancelled shouldBe (actor != admin)
                BlockBreakEvent(block, actor).also(protection::breakBlock).isCancelled shouldBe (actor != admin)
                BlockDropItemEvent(block, holder, actor, mutableListOf()).also(protection::drops).isCancelled shouldBe (actor != admin)
                if (actor != admin) {
                    actor.addAttachment(plugin, "arccivs.use", false)
                    InventoryOpenEvent(view).also(protection::open).isCancelled shouldBe true
                }
            }
            for ((source, destination) in listOf(inventory to outside, outside to inventory))
                InventoryMoveItemEvent(source, ItemStack(Material.STONE), destination, true)
                    .also(protection::moveItem).isCancelled shouldBe true
            InventoryPickupItemEvent(inventory, mockk()).also(protection::pickup).isCancelled shouldBe true
            // Native inventory ownership must not change owner-attributed block/path permission.
            protection.isProtected(world.uid, point.x, point.y, point.z, owner.uniqueId) shouldBe false
        }
    }

    for (point in listOf(CivPosition(-1, 64, 0), CivPosition(2, 64, 1), CivPosition(8, 64, 1)))
    test("Auto native admin access at $point rechecks receipt locks deletion and unavailable state") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("native-auto-locks")
            val plugin = paper.createSimplePlugin("AutoInventoryLocks")
            val admin = paper.addPlayer("Admin").apply { addAttachment(plugin, "arccivs.admin", true) }
            var city = inventoryProtectionCity(world.uid, UUID.randomUUID(), CivPosition(8, 64, 1))
            var available = true
            var locked = false
            val protection = CivsProtection({ listOf(city) }, { available }, worldLocked = { worldId, at ->
                worldId == world.uid && at == point && locked
            })
            val inventory = nativeInventory(world.getBlockAt(point.x, point.y, point.z).apply { type = Material.CHEST }.state as Container)
            val view = nativeView(inventory, admin)
            fun check(denied: Boolean) {
                InventoryOpenEvent(view).also(protection::open).isCancelled shouldBe denied
                InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL)
                    .also(protection::click).isCancelled shouldBe denied
                InventoryDragEvent(view, null, ItemStack(Material.STONE), false, mapOf(0 to ItemStack(Material.STONE)))
                    .also(protection::drag).isCancelled shouldBe denied
                if (point.x != -1) {
                    val block = world.getBlockAt(point.x, point.y, point.z)
                    BlockBreakEvent(block, admin).also(protection::breakBlock).isCancelled shouldBe denied
                    BlockDropItemEvent(block, block.state, admin, mutableListOf()).also(protection::drops).isCancelled shouldBe denied
                }
            }
            check(false)
            locked = true; check(true)
            locked = false; available = false; check(true)
            available = true; city = city.copy(pendingDeletion = CivDeletion(null)); protection.refresh(); check(true)
            city = city.copy(pendingDeletion = null); protection.refresh(); check(false)
        }
    }

    test("ordinary native chest retains owner and member writes and visitor denial") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("ordinary-inventory-rights")
            val owner = paper.addPlayer("Owner")
            val member = paper.addPlayer("Member")
            val visitor = paper.addPlayer("Visitor")
            val city = inventoryProtectionCity(world.uid, owner.uniqueId).copy(autonomy = null, members = setOf(member.uniqueId))
            val protection = CivsProtection({ listOf(city) })
            val inventory = nativeInventory(world.getBlockAt(2, 64, 1).apply { type = Material.CHEST }.state as Container)
            for (actor in listOf(owner, member, visitor)) {
                val view = nativeView(inventory, actor)
                InventoryOpenEvent(view).also(protection::open).isCancelled shouldBe (actor == visitor)
                InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY)
                    .also(protection::click).isCancelled shouldBe (actor == visitor)
                InventoryDragEvent(view, null, ItemStack(Material.STONE), false, mapOf(0 to ItemStack(Material.STONE)))
                    .also(protection::drag).isCancelled shouldBe (actor == visitor)
                val block = world.getBlockAt(2, 64, 1)
                BlockBreakEvent(block, actor).also(protection::breakBlock).isCancelled shouldBe (actor == visitor)
                BlockDropItemEvent(block, block.state, actor, mutableListOf()).also(protection::drops).isCancelled shouldBe (actor == visitor)
            }
        }
    }

    test("Auto inspection never opens the protected personal half of a combined chest") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("mixed-double-chest")
            val plugin = paper.createSimplePlugin("MixedInventoryProtection")
            val visitor = paper.addPlayer("Visitor").apply {
                addAttachment(plugin, "arccivs.use", true)
                teleport(Location(world, 2.5, 64.0, 2.5))
            }
            val auto = inventoryProtectionCity(world.uid, UUID.randomUUID()).let { city ->
                city.copy(buildings = city.buildings.map { it.copy(bounds = CivBox(it.bounds.min, it.bounds.max.copy(x = 2))) })
            }
            val personal = auto.copy(id = UUID.randomUUID(), owner = UUID.randomUUID(), autonomy = null,
                buildings = auto.buildings.map { it.copy(id = UUID.randomUUID(), control = null,
                    bounds = CivBox(CivPosition(3, 64, 0), CivPosition(5, 66, 4))) })
            val left = world.getBlockAt(2, 64, 1).apply { type = Material.CHEST }.state as Container
            val right = world.getBlockAt(3, 64, 1).apply { type = Material.CHEST }.state as Container
            val double = mockk<DoubleChest> {
                every { getLeftSide(false) } returns left
                every { getRightSide(false) } returns right
            }
            val inventory = mockk<Inventory> {
                every { getHolder(false) } returns double
                every { location } returns left.location
            }
            val protection = CivsProtection({ listOf(auto, personal) })
            InventoryOpenEvent(nativeView(inventory, visitor)).also(protection::open).isCancelled shouldBe true
        }
    }
})

private fun inventoryProtectionCity(worldId: UUID, owner: UUID, registered: CivPosition? = null): Settlement {
    val building = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(0, 64, 0), 0, "test",
        CivBox(CivPosition(0, 64, 0), CivPosition(4, 66, 4)), CivPosition(-1, 64, 0), BuildingPhase.ACTIVE,
        control = CivPosition(-1, 64, 0))
    return Settlement(UUID.randomUUID(), owner, "Native", worldId, CivPosition(0, 64, 0), 16, "test", Stock(),
        listOf(building), emptyList(), inventory = CivInventory(CivStorageMode.PHYSICAL, containers = listOfNotNull(registered)),
        autonomy = CivAutonomy())
}

private fun nativeInventory(holder: Container): Inventory = mockk {
    every { getHolder(false) } returns holder
    every { location } returns holder.location
}

private fun nativeView(inventory: Inventory, actor: Player): InventoryView = mockk(relaxed = true) {
    every { topInventory } returns inventory
    every { player } returns actor
}
