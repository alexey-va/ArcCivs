package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.block.Barrel
import org.bukkit.block.BlockState
import org.bukkit.block.Chest
import org.bukkit.block.DoubleChest
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsStorageViewLocksTest : FunSpec({
    for (recovered in listOf(false, true))
    test("an ordinary right-half receipt allows the full Auto chest view but fences every mutation recovered=$recovered") {
        storageViewLocks { f ->
            val tx = f.debit()
            if (recovered) {
                f.storage.unlock(tx.id)
                f.storage.lock(tx)
            }
            // Native item locks are separate from terrain locks; Protection must not
            // accidentally turn this ordinary transfer into a forbidden Auto view.
            f.storage.isWorldLocked(f.city.worldId, f.source) shouldBe false
            f.open(denied = false)
            f.mutations(denied = true)
            f.sourceInventory.getItem(0) shouldBe ItemStack(Material.OAK_LOG, 4)
            verify(exactly = 0) { f.player.closeInventory() }
            f.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
            f.sourceInventory.getItem(0) shouldBe ItemStack(Material.OAK_LOG, 3)
            f.open(denied = false)
            f.mutations(denied = true)
            f.storage.unlock(tx.id)
            f.open(denied = false)
            f.mutations(denied = false)
            f.sourceInventory.getItem(0) shouldBe ItemStack(Material.OAK_LOG, 3)
        }
    }

    test("relocation denies a right-half view and its unchanged control even when keep-open is requested and after recovery") {
        storageViewLocks { f ->
            val tx = f.relocation()
            val holders = requireNotNull(tx.containerRelocation).containers
            val before = holders.associate { it.position to it.before }
            val after = holders.associate { it.position to it.after }
            fun current() = holders.associate { it.position to f.items(it.position) }
            fun totals(values: Collection<List<CivResidentItem?>>) = values.flatten().filterNotNull()
                .groupingBy { it.material to it.data }.fold(0L) { count, item -> count + item.amount }
            verify(exactly = 1) { f.player.closeInventory() }
            f.open(denied = true)
            f.mutations(denied = true)
            shouldThrow<CivsRejection> { f.storage.openControl(f.player, f.city, f.building) }.reason shouldBe "storage-busy"
            current() shouldBe before
            f.storage.unlock(tx.id)
            f.storage.lock(tx)
            verify(exactly = 2) { f.player.closeInventory() }
            f.open(denied = true)
            f.mutations(denied = true)
            shouldThrow<CivsRejection> { f.storage.openControl(f.player, f.city, f.building) }.reason shouldBe "storage-busy"
            f.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
            f.storage.reconcile(tx) shouldBe CivInventoryApplyResult.Applied
            f.sourceInventory.storageContents.all { it == null } shouldBe true
            current() shouldBe after
            totals(current().values) shouldBe totals(before.values)
            f.open(denied = true) // The receipt retains its fence until explicit retirement.
            f.storage.unlock(tx.id)
            f.open(denied = false)
            f.mutations(denied = false)
            f.storage.openControl(f.player, f.city, f.building)
            verify(exactly = 1) { f.player.openInventory(f.controlInventory) }
            current() shouldBe after
            totals(current().values) shouldBe totals(before.values)
        }
    }
})

private fun storageViewLocks(action: (StorageViewLocks) -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        val world = paper.addSimpleWorld("storage-view-locks")
        world.loadChunk(0, 0); world.loadChunk(-1, 0)
        val nativePlugin = paper.createSimplePlugin("StorageViewLocks")
        val nativePlayer = paper.addPlayer("Viewer").also {
            it.teleport(Location(world, 2.5, 64.0, 2.5))
            it.addAttachment(nativePlugin, "arccivs.use", true)
            it.addAttachment(nativePlugin, "arccivs.admin", true)
        }
        val control = CivPosition(0, 64, 0)
        val source = CivPosition(2, 64, 0)
        val destination = CivPosition(4, 64, 0)
        val controlHolder = world.getBlockAt(0, 64, 0).also { it.type = Material.BARREL }.state as Barrel
        val right = world.getBlockAt(2, 64, 0).also { it.type = Material.CHEST }.state as Chest
        val target = world.getBlockAt(4, 64, 0).also { it.type = Material.CHEST }.state as Chest
        val foreign = world.getBlockAt(-4, 64, 0).also { it.type = Material.CHEST }.state as Chest
        right.blockInventory.setItem(0, ItemStack(Material.OAK_LOG, 4))
        val holder = mockk<DoubleChest> {
            every { getLeftSide(false) } returns foreign
            every { getRightSide(false) } returns right
        }
        // The pinned MockBukkit container does not implement getHolder(false).
        // Only that event boundary is mocked; planning and application use native slots.
        val inventory = mockk<Inventory>(relaxed = true) {
            every { getHolder(false) } returns holder
            every { getHolder() } returns holder
            every { size } returns 54
            every { location } returns right.location
        }
        val free = mockk<Inventory>(relaxed = true) { every { getHolder(false) } returns null }
        val player = mockk<Player>(relaxed = true)
        val view = mockk<InventoryView>(relaxed = true)
        every { player.uniqueId } returns nativePlayer.uniqueId
        every { player.world } returns world
        every { player.isOnline } returns true
        every { player.openInventory } returns view
        every { player.openInventory(any<Inventory>()) } returns view
        every { view.topInventory } returns inventory
        every { view.player } returns player
        val server = mockk<Server>(relaxed = true)
        every { server.onlinePlayers } returns listOf(player)
        every { server.getWorld(world.uid) } returns world
        val plugin = mockk<Plugin>(relaxed = true)
        every { plugin.server } returns server
        every { plugin.logger } returns nativePlugin.logger
        val building = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, control, 0, "test",
            CivBox(CivPosition(-1, 64, -1), CivPosition(5, 68, 1)), CivPosition(0, 64, -2),
            BuildingPhase.ACTIVE, control = control)
        val city = CivsRules.validate(Settlement(UUID.randomUUID(), nativePlayer.uniqueId, "Storage", world.uid,
            control, 32, "nordic", Stock(), listOf(building), emptyList(), createdAt = 1_000,
            inventory = CivInventory(CivStorageMode.PHYSICAL, containers = listOf(source, destination)), autonomy = CivAutonomy()))
        CivsStorage(plugin) { arrayOf<BlockState>(controlHolder, right, target) }.use { storage ->
            storage.foodCanModify = { _, _ -> true }
            storage.relocationCurrentCity = { city.takeIf { current -> current.id == it } }
            val protection = CivsProtection({ listOf(city) }, worldLocked = storage::isWorldLocked)
            action(StorageViewLocks(storage, protection, city, building, source, destination, right.blockInventory,
                target.blockInventory, target.blockData.asString, controlHolder.inventory, player, inventory, free, view))
        }
    }
}

private data class StorageViewLocks(val storage: CivsStorage, val protection: CivsProtection,
    val city: Settlement, val building: CivBuilding, val source: CivPosition, val destination: CivPosition,
    val sourceInventory: Inventory, val destinationInventory: Inventory, val destinationData: String, val controlInventory: Inventory,
    val player: Player, val inventory: Inventory, val free: Inventory, val view: InventoryView) {
    fun items(position: CivPosition): List<CivResidentItem?> = when (position) {
        source -> sourceInventory
        destination -> destinationInventory
        building.control -> controlInventory
        else -> error("Unexpected native holder $position")
    }.storageContents.map(CivResidentItem::of)
    fun debit(): CivInventoryTransaction {
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val plan = storage.prepareDebit(city, mapOf("OAK_LOG" to 1L))
        plan.changes.single().container shouldBe source
        return CivInventoryTransaction(id, city.id, city.revision, city.copy(revision = city.revision + 1,
            inventory = plan.inventoryAfter), plan.changes).validated().also { storage.seal(it) }
    }
    fun relocation(): CivInventoryTransaction {
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val proof = requireNotNull(storage.prepareUpgradeRelocation(city, building.copy(level = 2,
            phase = BuildingPhase.CONSTRUCTING), mapOf(source to "minecraft:air", destination to destinationData)))
        return CivInventoryTransaction(id, city.id, city.revision, city.copy(revision = city.revision + 1),
            emptyList(), containerRelocation = proof).validated().also { storage.seal(it, keepViewersOpen = true) }
    }
    fun open(denied: Boolean) {
        val event = InventoryOpenEvent(view)
        protection.open(event)
        event.isCancelled shouldBe false
        storage.onOpen(event)
        event.isCancelled shouldBe denied
    }
    fun mutations(denied: Boolean) {
        for (click in listOf(ClickType.LEFT, ClickType.SHIFT_LEFT, ClickType.NUMBER_KEY,
            ClickType.DOUBLE_CLICK, ClickType.DROP, ClickType.SWAP_OFFHAND)) {
            val event = InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, click, InventoryAction.UNKNOWN)
            protection.click(event); storage.onClick(event)
            event.isCancelled shouldBe denied
        }
        val creative = InventoryCreativeEvent(view, InventoryType.SlotType.CONTAINER, 0, ItemStack(Material.DIAMOND))
        protection.click(creative); storage.onClick(creative)
        creative.isCancelled shouldBe denied
        val drag = InventoryDragEvent(view, null, ItemStack(Material.STONE, 2), false, mapOf(0 to ItemStack(Material.STONE)))
        protection.drag(drag); storage.onDrag(drag)
        drag.isCancelled shouldBe denied
        for ((from, to) in listOf(inventory to free, free to inventory)) {
            val move = InventoryMoveItemEvent(from, ItemStack(Material.OAK_LOG), to, true)
            storage.onMove(move)
            move.isCancelled shouldBe denied
        }
    }
}
