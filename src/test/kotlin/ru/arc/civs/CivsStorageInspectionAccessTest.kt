package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.block.Barrel
import org.bukkit.block.BlockState
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsStorageInspectionAccessTest : FunSpec({
    test("ordinary native transfers keep the real storage view open while its items remain locked") {
        inspectionStorage { h ->
            val id = UUID.randomUUID()
            h.storage.reserve(h.city, id)
            val plan = h.storage.prepareDebit(h.city, mapOf("OAK_LOG" to 1L))
            val tx = CivInventoryTransaction(id, h.city.id, h.city.revision,
                h.city.copy(inventory = plan.inventoryAfter, revision = h.city.revision + 1), plan.changes)
            h.storage.seal(tx)
            verify(exactly = 0) { h.player.closeInventory() }
            h.storage.openControl(h.player, h.city, h.city.buildings.single())
            verify(exactly = 1) { h.player.openInventory(h.nativeInventory) }
            val opened = InventoryOpenEvent(h.view)
            h.storage.onOpen(opened)
            opened.isCancelled shouldBe false
            val click = h.click()
            h.storage.onClick(click)
            click.isCancelled shouldBe true
            val hopper = InventoryMoveItemEvent(h.inventory, ItemStack(Material.OAK_LOG, 1), h.inventory, true)
            h.storage.onMove(hopper)
            hopper.isCancelled shouldBe true
            h.nativeInventory.getItem(0)!!.amount shouldBe 4
            h.storage.apply(tx) shouldBe CivInventoryApplyResult.Applied
            h.storage.unlock(id)
            h.nativeInventory.getItem(0)!!.amount shouldBe 3
            val resumed = h.click()
            h.storage.onClick(resumed)
            resumed.isCancelled shouldBe false
            verify(exactly = 0) { h.player.closeInventory() }
        }
    }

    test("an unrelated city claim does not block opening an unchanged control barrel") {
        inspectionStorage { h ->
            val id = UUID.randomUUID()
            h.storage.reserve(h.city, id)
            val tx = CivInventoryTransaction(id, h.city.id, h.city.revision,
                h.city.copy(revision = h.city.revision + 1), emptyList())
            h.storage.seal(tx)
            h.storage.openControl(h.player, h.city, h.city.buildings.single())
            verify(exactly = 1) { h.player.openInventory(h.nativeInventory) }
            val opened = InventoryOpenEvent(h.view)
            h.storage.onOpen(opened)
            opened.isCancelled shouldBe false
            val click = h.click()
            h.storage.onClick(click)
            click.isCancelled shouldBe false
            h.storage.unlock(id)
        }
    }

    test("player receipt and bootstrap locks still reject a new storage view") {
        inspectionStorage { h ->
            val id = UUID.randomUUID()
            h.storage.reserve(h.city, id, h.player)
            shouldThrow<CivsRejection> {
                h.storage.openControl(h.player, h.city, h.city.buildings.single())
            }.reason shouldBe "storage-busy"
            val playerOpen = InventoryOpenEvent(h.view)
            h.storage.onOpen(playerOpen)
            playerOpen.isCancelled shouldBe true
            h.storage.unlock(id)
            h.storage.beginReceiptBootstrap()
            val bootstrapOpen = InventoryOpenEvent(h.view)
            h.storage.onOpen(bootstrapOpen)
            bootstrapOpen.isCancelled shouldBe true
        }
    }
})

private fun inspectionStorage(action: (InspectionStorage) -> Unit) {
    MockBukkitTestRuntime.open().use { paper ->
        val world = paper.addSimpleWorld("inspection-storage")
        world.loadChunk(0, 0)
        val control = CivPosition(0, 64, 0)
        world.getBlockAt(0, 64, 0).type = Material.BARREL
        val holder = world.getBlockAt(0, 64, 0).state as Barrel
        val native = holder.inventory
        native.setItem(0, ItemStack(Material.OAK_LOG, 4))
        val inventory = mockk<Inventory>(relaxed = true)
        every { inventory.getHolder(false) } returns holder
        every { inventory.size } returns 27
        val player = mockk<Player>(relaxed = true)
        val view = mockk<InventoryView>(relaxed = true)
        val owner = UUID.randomUUID()
        every { player.uniqueId } returns owner
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
        every { plugin.logger } returns paper.createSimplePlugin("InspectionStorage").logger
        val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(3, 64, 0), 0,
            "test", CivBox(CivPosition(2, 64, 0), CivPosition(4, 65, 2)), CivPosition(3, 64, 3),
            BuildingPhase.SITE, control = control)
        val city = CivsRules.found(UUID.randomUUID(), owner, "Storage", world.uid, control, 16,
            "nordic", hall, 1_000).copy(inventory = CivInventory(CivStorageMode.PHYSICAL))
        CivsStorage(plugin) { arrayOf<BlockState>(world.getBlockAt(0, 64, 0).state) }.use { storage ->
            action(InspectionStorage(storage, city, native, inventory, player, view))
        }
    }
}

private data class InspectionStorage(val storage: CivsStorage, val city: Settlement, val nativeInventory: Inventory,
    val inventory: Inventory, val player: Player, val view: InventoryView) {
    fun click() = InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL)
}
