package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Barrel
import org.bukkit.block.Chest
import org.bukkit.enchantments.Enchantment
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class CivsStorageTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var world: WorldMock
    lateinit var plugin: Plugin
    lateinit var storage: CivsStorage
    lateinit var city: Settlement
    lateinit var barrel: Inventory
    lateinit var chest: Inventory
    val control = CivPosition(0, 64, 0)
    val registered = CivPosition(1, 64, 0)

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        world = paper.addSimpleWorld("storage")
        world.loadChunk(0, 0)
        plugin = paper.createSimplePlugin("CivsStorageTest")
        storage = CivsStorage(plugin)
        world.getBlockAt(0, 64, 0).type = Material.BARREL
        world.getBlockAt(1, 64, 0).type = Material.CHEST
        barrel = (world.getBlockAt(0, 64, 0).state as Barrel).inventory
        chest = (world.getBlockAt(1, 64, 0).state as Chest).blockInventory
        val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(3, 64, 0), 0,
            "test", CivBox(CivPosition(2, 64, 0), CivPosition(4, 65, 2)), CivPosition(3, 64, 3),
            BuildingPhase.SITE, control = control)
        city = CivsRules.found(UUID.randomUUID(), UUID.randomUUID(), "Storage", world.uid,
            CivPosition(0, 64, 0), 16, "nordic", hall, 1_000).let {
            it.copy(inventory = requireNotNull(it.inventory).copy(materials = mapOf("OAK_LOG" to 3L), containers = listOf(registered)))
        }
    }
    afterEach { storage.close(); paper.close() }

    test("physical debit uses plain containers then reserve and never spends named cargo") {
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 4))
        val custom = named(Material.OAK_LOG, 64)
        barrel.setItem(1, custom)
        chest.setItem(0, ItemStack(Material.OAK_LOG, 2))
        storage.available(city) shouldBe mapOf("OAK_LOG" to 9L)
        val id = UUID.randomUUID()
        storage.lock(city, id)
        val plan = storage.prepareDebit(city, mapOf("OAK_LOG" to 8L))
        plan.inventoryAfter.materials shouldBe mapOf("OAK_LOG" to 1L)
        plan.changes.size shouldBe 2
        barrel.getItem(0)?.amount shouldBe 4
        chest.getItem(0)?.amount shouldBe 2
        val transaction = transaction(id, city, plan)
        storage.apply(transaction) shouldBe CivInventoryApplyResult.Applied
        storage.apply(transaction) shouldBe CivInventoryApplyResult.Applied
        barrel.getItem(0) shouldBe null
        chest.getItem(0) shouldBe null
        barrel.getItem(1) shouldBe custom
    }

    test("insufficient debit reports the exact shortage without applying a partial removal") {
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 4))
        storage.lock(city, UUID.randomUUID())
        val failure = shouldThrow<CivsRejection> { storage.prepareDebit(city, mapOf("OAK_LOG" to 10L)) }
        failure.reason shouldBe "materials-missing"
        failure.values["material"] shouldBe "OAK_LOG"
        failure.values["count"] shouldBe "3"
        barrel.getItem(0)?.amount shouldBe 4
        requireNotNull(city.inventory).materials shouldBe mapOf("OAK_LOG" to 3L)
    }

    test("default prototype comparison excludes display PDC enchantment and damage without spending custom items") {
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 4))
        val display = named(Material.OAK_LOG, 12)
        val tagged = ItemStack(Material.OAK_LOG, 12).also { item ->
            val meta = item.itemMeta
            meta.persistentDataContainer.set(NamespacedKey(plugin, "custom_resource"), PersistentDataType.STRING, "retained")
            item.itemMeta = meta
        }
        val enchanted = ItemStack(Material.OAK_LOG, 12).also { it.addUnsafeEnchantment(Enchantment.UNBREAKING, 1) }
        val damaged = ItemStack(Material.IRON_PICKAXE).also { item ->
            val meta = item.itemMeta as Damageable
            meta.damage = 1
            item.itemMeta = meta
        }
        val custom = listOf(display, tagged, enchanted, damaged)
        custom.forEachIndexed { index, item -> barrel.setItem(index + 1, item) }
        storage.available(city) shouldBe mapOf("OAK_LOG" to 7L)
        val id = UUID.randomUUID()
        storage.lock(city, id)
        val debit = storage.prepareDebit(city, mapOf("OAK_LOG" to 7L))
        storage.apply(transaction(id, city, debit)) shouldBe CivInventoryApplyResult.Applied
        barrel.getItem(0) shouldBe null
        custom.forEachIndexed { index, item -> barrel.getItem(index + 1) shouldBe item }
    }

    test("partial receipt recovery finishes only known states and duplicate application consumes nothing else") {
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 3))
        chest.setItem(0, ItemStack(Material.OAK_LOG, 2))
        val id = UUID.randomUUID()
        storage.lock(city, id)
        val plan = storage.prepareDebit(city, mapOf("OAK_LOG" to 5L))
        barrel.setItem(0, null) // A crash after the first journalled slot write.
        val receipt = transaction(id, city, plan)
        storage.lock(receipt.after, id) // Recovery can know the committed revision while this claim began before it.
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        storage.reconcile(receipt) shouldBe CivInventoryApplyResult.Applied
        chest.getItem(0) shouldBe null
        requireNotNull(receipt.after.inventory).materials shouldBe mapOf("OAK_LOG" to 3L)
    }

    test("a custom item introduced after capture conflicts before any other planned slot is changed") {
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 3))
        chest.setItem(0, ItemStack(Material.OAK_LOG, 2))
        val id = UUID.randomUUID()
        storage.lock(city, id)
        val plan = storage.prepareDebit(city, mapOf("OAK_LOG" to 5L))
        val custom = named(Material.OAK_LOG, 2)
        chest.setItem(0, custom)
        val result = storage.apply(transaction(id, city, plan)) as CivInventoryApplyResult.Conflict
        result.values["reason"] shouldBe "slot-drift"
        result.values["x"] shouldBe "1"
        result.values["slot"] shouldBe "0"
        barrel.getItem(0)?.amount shouldBe 3
        chest.getItem(0) shouldBe custom
    }

    test("physical credit requires full capacity and leaves a partly fillable stack unchanged on rejection") {
        for (slot in 0..26) {
            barrel.setItem(slot, ItemStack(Material.DIRT, 64))
            chest.setItem(slot, ItemStack(Material.DIRT, 64))
        }
        barrel.setItem(0, ItemStack(Material.BREAD, 63))
        storage.lock(city, UUID.randomUUID())
        shouldThrow<CivsRejection> { storage.prepareCredit(city, "BREAD", 2) }.reason shouldBe "storage-full"
        barrel.getItem(0)?.amount shouldBe 63
        chest.getItem(0)?.type shouldBe Material.DIRT
    }

    test("building warehouse total capacity bounds deposits and withdrawal also exposes the starting reserve") {
        val building = city.buildings.single()
        city = city.copy(inventory = requireNotNull(city.inventory).copy(materials = mapOf("BREAD" to 24L),
            buildingMaterials = mapOf(building.id.toString() to mapOf("OAK_LOG" to 1_720L))))
        barrel.setItem(0, ItemStack(Material.BREAD, 16))
        val custom = named(Material.COBBLESTONE, 5)
        barrel.setItem(1, custom)
        val depositId = UUID.randomUUID()
        storage.lock(city, depositId)
        val deposit = storage.depositFromControl(city, building)
        deposit.inventoryAfter.materials shouldBe mapOf("BREAD" to 24L)
        deposit.inventoryAfter.buildingStock(building.id) shouldBe mapOf("OAK_LOG" to 1_720L, "BREAD" to 8L)
        storage.apply(transaction(depositId, city, deposit)) shouldBe CivInventoryApplyResult.Applied
        barrel.getItem(0)?.amount shouldBe 8
        barrel.getItem(1) shouldBe custom
        storage.unlock(depositId)
        city = city.copy(inventory = deposit.inventoryAfter, revision = city.revision + 1)
        val withdrawalId = UUID.randomUUID()
        storage.lock(city, withdrawalId)
        val withdrawal = storage.withdrawToControl(city, building, "BREAD", 16)
        withdrawal.inventoryAfter.buildingStock(building.id) shouldBe mapOf("OAK_LOG" to 1_720L)
        withdrawal.inventoryAfter.materials shouldBe mapOf("BREAD" to 16L)
        storage.apply(transaction(withdrawalId, city, withdrawal)) shouldBe CivInventoryApplyResult.Applied
        barrel.getItem(0)?.amount shouldBe 24
    }

    test("virtual availability and debit combine reserve with building warehouses while ignoring native cargo") {
        val building = city.buildings.single()
        city = city.copy(inventory = requireNotNull(city.inventory).copy(mode = CivStorageMode.VIRTUAL,
            buildingMaterials = mapOf(building.id.toString() to mapOf("OAK_LOG" to 5L))))
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 64))
        storage.available(city) shouldBe mapOf("OAK_LOG" to 8L)
        storage.lock(city, UUID.randomUUID())
        val debit = storage.prepareDebit(city, mapOf("OAK_LOG" to 6L))
        debit.inventoryAfter.materials shouldBe emptyMap()
        debit.inventoryAfter.buildingStock(building.id) shouldBe mapOf("OAK_LOG" to 2L)
        debit.changes shouldBe emptyList()
        barrel.getItem(0)?.amount shouldBe 64
    }

    test("virtual production needs its building and counts every resource toward that building capacity") {
        val building = city.buildings.single()
        city = city.copy(inventory = requireNotNull(city.inventory).copy(mode = CivStorageMode.VIRTUAL,
            buildingMaterials = mapOf(building.id.toString() to mapOf("OAK_LOG" to 1_700L, "COBBLESTONE" to 20L))))
        storage.lock(city, UUID.randomUUID())
        shouldThrow<CivsRejection> { storage.prepareCredit(city, "BREAD", 1) }.reason shouldBe "storage-building-required"
        shouldThrow<CivsRejection> { storage.prepareCredit(city, "BREAD", 9, building.id) }.reason shouldBe "storage-full"
        val credit = storage.prepareCredit(city, "BREAD", 8, building.id)
        credit.inventoryAfter.buildingStock(building.id)["BREAD"] shouldBe 8L
        credit.inventoryAfter.materials shouldBe mapOf("OAK_LOG" to 3L)
        credit.changes shouldBe emptyList()
    }

    test("unloaded containers retry without loading chunks and replacement by another block conflicts") {
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 3))
        val id = UUID.randomUUID()
        storage.lock(city, id)
        val receipt = transaction(id, city, storage.prepareDebit(city, mapOf("OAK_LOG" to 3L)))
        world.unloadChunk(0, 0)
        (storage.apply(receipt) is CivInventoryApplyResult.Retry) shouldBe true
        world.isChunkLoaded(0, 0) shouldBe false
        world.loadChunk(0, 0)
        world.getBlockAt(0, 64, 0).type = Material.STONE
        (storage.apply(receipt) is CivInventoryApplyResult.Conflict) shouldBe true
    }

    test("unloaded physical sources are skipped while an available virtual reserve remains spendable") {
        barrel.setItem(0, ItemStack(Material.OAK_LOG, 64))
        world.unloadChunk(0, 0)
        storage.available(city) shouldBe mapOf("OAK_LOG" to 3L)
        storage.lock(city, UUID.randomUUID())
        val debit = storage.prepareDebit(city, mapOf("OAK_LOG" to 3L))
        debit.inventoryAfter.materials shouldBe emptyMap()
        debit.changes shouldBe emptyList()
        world.isChunkLoaded(0, 0) shouldBe false
    }

    test("locking closes viewers and blocks clicks and hopper moves until explicit unlock") {
        val player = paper.addPlayer("StorageViewer")
        player.teleport(Location(world, 0.5, 65.0, 0.5))
        val view = requireNotNull(player.openInventory(barrel))
        val id = UUID.randomUUID()
        storage.lock(city, id)
        (player.openInventory.topInventory === barrel) shouldBe false
        val click = InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL)
        storage.onClick(click)
        click.isCancelled shouldBe true
        val hopper = InventoryMoveItemEvent(barrel, ItemStack(Material.OAK_LOG), chest, true)
        storage.onMove(hopper)
        hopper.isCancelled shouldBe true
        storage.unlock(id)
        val unlocked = InventoryMoveItemEvent(barrel, ItemStack(Material.OAK_LOG), chest, true)
        storage.onMove(unlocked)
        unlocked.isCancelled shouldBe false
    }

    test("a registered chest never includes an unregistered adjacent half") {
        world.getBlockAt(2, 64, 0).type = Material.CHEST
        val foreign = (world.getBlockAt(2, 64, 0).state as Chest).blockInventory
        chest.setItem(0, ItemStack(Material.OAK_LOG, 2))
        foreign.setItem(0, ItemStack(Material.OAK_LOG, 64))
        storage.available(city) shouldBe mapOf("OAK_LOG" to 5L)
        storage.lock(city, UUID.randomUUID())
        shouldThrow<CivsRejection> { storage.prepareDebit(city, mapOf("OAK_LOG" to 6L)) }.reason shouldBe "materials-missing"
        foreign.getItem(0)?.amount shouldBe 64
    }

    test("native storage entrypoints reject async access before touching inventories") {
        val failure = shouldThrow<CompletionException> {
            CompletableFuture.supplyAsync { storage.available(city) }.join()
        }
        (failure.cause is IllegalStateException) shouldBe true
        shouldThrow<CompletionException> { CompletableFuture.runAsync { storage.lock(city, UUID.randomUUID()) }.join() }
        barrel.isEmpty shouldBe true
    }
})

private fun transaction(id: UUID, city: Settlement, plan: CivStoragePlan) = CivInventoryTransaction(
    id, city.id, city.revision, city.copy(inventory = plan.inventoryAfter, revision = city.revision + 1), plan.changes,
)

private fun named(material: Material, count: Int): ItemStack = ItemStack(material, count).also { item ->
    val meta = item.itemMeta
    meta.displayName(Component.text("Custom cargo"))
    item.itemMeta = meta
}
