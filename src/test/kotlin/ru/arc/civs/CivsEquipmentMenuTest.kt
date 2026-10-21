package ru.arc.civs

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CompletableFuture

class CivsEquipmentMenuTest : FunSpec({
    test("gear and bag occupy 33 unique cells and furniture cannot become an inventory slot") {
        (0..32).map(::civsEquipmentViewSlot).distinct().size shouldBe 33
        (0..32).forEach { civsEquipmentLogicalSlot(civsEquipmentViewSlot(it)) shouldBe it }
        listOf(-999, 0, 5, 8, 9, 13, 17, 45, 49, 53, 54, 89).forEach { civsEquipmentLogicalSlot(it) shouldBe null }
    }

    test("native gear retains exact metadata and transfers once while a receipt is pending") {
        nativeEquipment { h ->
            val hoe = ItemStack(Material.IRON_HOE).apply {
                editMeta { meta ->
                    meta.displayName(Component.text("Старая мотыга"))
                    meta.lore(listOf(Component.text("Подарок мэра")))
                    (meta as Damageable).damage = 13
                    meta.persistentDataContainer.set(NamespacedKey("test", "owner"), PersistentDataType.STRING, "resident")
                }
            }
            h.items[4] = hoe
            h.items[0] = ItemStack(Material.IRON_HELMET)
            h.items[1] = ItemStack(Material.IRON_CHESTPLATE)
            h.items[2] = ItemStack(Material.IRON_LEGGINGS)
            h.items[3] = ItemStack(Material.IRON_BOOTS)
            h.items[5] = ItemStack(Material.SHIELD)
            h.items[6] = ItemStack(Material.WHEAT_SEEDS, 32)
            h.items[7] = ItemStack(Material.BREAD, 8)
            h.items[8] = ItemStack(Material.OAK_SAPLING, 12)
            h.items[9] = ItemStack(Material.TORCH, 16)
            h.items[10] = ItemStack(Material.LADDER, 24)
            h.items[11] = ItemStack(Material.WATER_BUCKET)
            h.player.inventory.setItem(9, ItemStack(Material.IRON_AXE))
            h.open()
            val view = h.player.openInventory.topInventory
            view.size shouldBe 54
            view.getItem(53)!!.type shouldBe Material.GRAY_STAINED_GLASS_PANE
            view.getItem(6) shouldBe hoe
            view.getItem(18) shouldBe ItemStack(Material.WHEAT_SEEDS, 32)
            h.export("equipment-equipped")
            h.click(6, ClickType.SHIFT_RIGHT).isCancelled shouldBe true
            h.calls shouldBe listOf(Triple(4, null, ClickType.SHIFT_RIGHT))
            h.player.openInventory.topInventory shouldBe view
            view.getItem(49)!!.type shouldBe Material.CLOCK
            h.export("equipment-pending")
            h.click(6)
            h.click(54)
            h.calls.size shouldBe 1
            h.items[4] = null
            h.future.complete(Unit)
            h.paper.performTicks(2)
            view.getItem(6)!!.type shouldBe Material.GRAY_DYE
            hoe.itemMeta.persistentDataContainer.get(NamespacedKey("test", "owner"), PersistentDataType.STRING) shouldBe "resident"
            (hoe.itemMeta as Damageable).damage shouldBe 13
            h.future = CompletableFuture()
            h.click(54, ClickType.LEFT)
            h.calls.last() shouldBe Triple(null, 9, ClickType.LEFT)
        }
    }

    test("cursor contents unsupported clicks placeholders and failed snapshots never transfer") {
        nativeEquipment { h ->
            h.items[6] = ItemStack(Material.BREAD, 8)
            h.open()
            h.player.setItemOnCursor(ItemStack(Material.DIAMOND))
            h.click(18).isCancelled shouldBe true
            h.calls.size shouldBe 0
            h.player.itemOnCursor.type shouldBe Material.DIAMOND
            h.export("equipment-cursor")
            h.player.setItemOnCursor(null)
            for (click in listOf(ClickType.NUMBER_KEY, ClickType.DOUBLE_CLICK, ClickType.DROP, ClickType.CONTROL_DROP,
                ClickType.SWAP_OFFHAND, ClickType.MIDDLE, ClickType.WINDOW_BORDER_LEFT)) h.click(18, click).isCancelled shouldBe true
            h.click(1); h.click(13); h.click(49)
            h.calls.size shouldBe 0
            h.failSnapshot = true
            h.paper.performTicks(20)
            h.player.inventory.setItem(9, ItemStack(Material.DIAMOND))
            h.click(54)
            h.calls.size shouldBe 0
        }
    }

    test("closing and replacing the window cannot let an old completion rewrite the new view") {
        nativeEquipment { h ->
            h.items[6] = ItemStack(Material.BREAD)
            h.open()
            h.click(18)
            val pending = h.future
            h.items[6] = ItemStack(Material.WHEAT_SEEDS)
            h.open()
            pending.completeExceptionally(CivsRejection("city-busy"))
            h.paper.performTicks(2)
            h.player.openInventory.topInventory.getItem(18)!!.type shouldBe Material.WHEAT_SEEDS
            h.player.openInventory.topInventory.getItem(49)!!.type shouldBe Material.BOOK
            h.click(45)
            h.paper.performTicks(2)
            h.backs shouldBe 1
        }
    }
})

private fun nativeEquipment(action: (EquipmentHarness) -> Unit) = MockBukkitTestRuntime.open().use { paper ->
    val h = EquipmentHarness(paper)
    try { action(h) } finally { h.menu.close(); h.tasks.close() }
}

private class EquipmentHarness(val paper: MockBukkitTestRuntime) {
    private val plugin = paper.createSimplePlugin("CivsEquipmentTest")
    val player = paper.addPlayer("Mayor").apply { setLocale(Locale.forLanguageTag("ru-RU")) }
    val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    val menu = CivsEquipmentMenu(plugin, tasks, CivsText.fromYaml(civsLocaleSource()))
    val items = MutableList<ItemStack?>(33) { null }
    val calls = mutableListOf<Triple<Int?, Int?, ClickType>>()
    var future = CompletableFuture<Unit>()
    var backs = 0
    var failSnapshot = false

    fun open() = menu.open(player, "Василий", {
        if (failSnapshot) throw CivsRejection("inventory-recovery")
        items
    }, { _, npc, slot, click -> calls += Triple(npc, slot, click); future }, { backs++ })

    fun click(raw: Int, click: ClickType = ClickType.LEFT): InventoryClickEvent = InventoryClickEvent(
        player.openInventory, InventoryType.SlotType.CONTAINER, raw, click, InventoryAction.PICKUP_ALL,
    ).also(menu::onClick)

    fun export(id: String) {
        val directory = System.getenv("CIVS_NATIVE_PREVIEW_OUT")?.let(Path::of) ?: return
        Files.createDirectories(directory)
        val legacy = LegacyComponentSerializer.builder().character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build()
        val view = player.openInventory
        fun item(value: ItemStack?): Any? = value?.takeUnless { it.type.isAir }?.let {
            mapOf("material" to it.type.name, "amount" to it.amount,
                "name" to it.itemMeta.displayName()?.let(legacy::serialize),
                "lore" to it.itemMeta.lore().orEmpty().map(legacy::serialize),
                "damage" to (it.itemMeta as? Damageable)?.damage)
        }
        val payload = mapOf("evidence" to "compiled native Inventory fixture, not a Minecraft client screenshot",
            "id" to id, "title" to legacy.serialize(view.title()),
            "size" to view.topInventory.size, "slots" to (0 until 54).map { item(view.topInventory.getItem(it)) },
            "playerSlots" to (0 until 36).map { item(player.inventory.getItem(it)) },
            "logicalToView" to (0..32).map(::civsEquipmentViewSlot))
        Files.writeString(directory.resolve("$id.json"), GsonBuilder().setPrettyPrinting().create().toJson(payload))
    }
}
