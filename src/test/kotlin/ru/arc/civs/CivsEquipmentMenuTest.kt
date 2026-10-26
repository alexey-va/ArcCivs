package ru.arc.civs

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.GameMode
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
        listOf(-999, 0, 5, 8, 9, 13, 15, 17, 45, 49, 53, 54, 89).forEach { civsEquipmentLogicalSlot(it) shouldBe null }
    }

    test("autonomous stock badge refreshes exact quantities without offering a transferable inventory slot") {
        nativeEquipment { h ->
            var pending = 9
            h.open(stockDetails = { if (pending > 0) listOf(Component.text("Ждёт кладовщика: Еда × $pending")) else emptyList() })
            h.name(15) shouldBe "Ресурсы поселения"
            h.player.openInventory.topInventory.getItem(15)!!.type shouldBe Material.BARREL
            h.click(15).isCancelled shouldBe true
            h.click(15, ClickType.SHIFT_LEFT).isCancelled shouldBe true
            h.calls shouldBe emptyList()
            h.player.itemOnCursor.type shouldBe Material.AIR
            pending = 3
            h.paper.performTicks(20)
            PlainTextComponentSerializer.plainText().serialize(
                h.player.openInventory.topInventory.getItem(15)!!.itemMeta.lore()!!.first()) shouldBe "Ждёт кладовщика: Еда × 3"
            pending = 0
            h.paper.performTicks(20)
            h.player.openInventory.topInventory.getItem(15)!!.type shouldBe Material.GRAY_STAINED_GLASS_PANE
        }
    }

    test("overflow pages preserve gear positions and each saved bag slot has an exact round trip") {
        listOf(33, 34, 60, 61, 87, 88, 97).map(::civsEquipmentPages) shouldBe listOf(1, 2, 2, 3, 3, 4, 4)
        for (page in 0..3) {
            for (gear in 0..5) {
                civsEquipmentViewSlot(gear, page) shouldBe civsEquipmentViewSlot(gear)
                civsEquipmentLogicalSlot(civsEquipmentViewSlot(gear), page) shouldBe gear
            }
            for (logical in (6 + page * 27) until minOf(33 + page * 27, CivsEquipment.MAX_SLOTS)) {
                val view = civsEquipmentViewSlot(logical, page)
                (view in 18..44) shouldBe true
                civsEquipmentLogicalSlot(view, page) shouldBe logical
            }
            listOf(13, 45, 47, 49, 51, 54).forEach { civsEquipmentLogicalSlot(it, page) shouldBe null }
        }
    }

    test("resident and corpse pages expose all 97 native slots and route withdrawals to their saved indices") {
        for (canDeposit in listOf(true, false)) nativeEquipment { h ->
            h.items.addAll(List(CivsEquipment.MAX_SLOTS - h.items.size) { null })
            h.items[4] = ItemStack(Material.DIAMOND_PICKAXE)
            for (slot in 6 until h.items.size) h.items[slot] = ItemStack(Material.IRON_PICKAXE).apply {
                editMeta { it.displayName(Component.text("Кирка $slot")); (it as Damageable).damage = slot }
            }
            h.open(canDeposit = canDeposit)
            val view = h.player.openInventory.topInventory
            for (page in 0..3) {
                if (page > 0) h.click(51).isCancelled shouldBe true
                view.getItem(6) shouldBe h.items[4]
                h.name(13) shouldBe "Сумка · ${page + 1}/4"
                view.getItem(47)!!.type shouldBe if (page > 0) Material.ARROW else Material.GRAY_STAINED_GLASS_PANE
                view.getItem(51)!!.type shouldBe if (page < 3) Material.ARROW else Material.GRAY_STAINED_GLASS_PANE
                val first = 6 + page * 27
                for (offset in 0..26) view.getItem(18 + offset) shouldBe h.items.getOrNull(first + offset)
                if (page == 1 || page == 3) h.export("equipment-${if (canDeposit) "resident" else "corpse"}-page-${page + 1}", page)
                h.click(18, ClickType.RIGHT)
                h.calls.last() shouldBe Triple(first, null, ClickType.RIGHT)
                h.click(51)
                h.name(13) shouldBe "Сумка · ${page + 1}/4"
                h.complete()
            }
            h.click(27, ClickType.SHIFT_LEFT)
            h.calls.last() shouldBe Triple(96, null, ClickType.SHIFT_LEFT)
            h.complete()
            val calls = h.calls.size
            h.click(28)
            h.click(51)
            h.calls.size shouldBe calls
            h.name(13) shouldBe "Сумка · 4/4"
            h.click(47)
            h.name(13) shouldBe "Сумка · 3/4"
            view.getItem(18) shouldBe h.items[60]
            h.click(6)
            h.calls.last() shouldBe Triple(4, null, ClickType.LEFT)
        }
    }

    test("overflow pages reject every deposit but cursor items can return to the player and normal first-page giving remains available") {
        nativeEquipment { h ->
            h.items += ItemStack(Material.IRON_PICKAXE)
            h.player.inventory.setItem(9, ItemStack(Material.DIAMOND))
            h.open()
            h.click(51)
            h.player.setItemOnCursor(ItemStack(Material.IRON_HELMET))
            h.click(1, ClickType.LEFT, InventoryAction.SWAP_WITH_CURSOR).isCancelled shouldBe true
            h.click(18, ClickType.RIGHT, InventoryAction.PLACE_ONE).isCancelled shouldBe true
            h.click(19, ClickType.LEFT, InventoryAction.PLACE_ALL).isCancelled shouldBe true
            h.calls shouldBe emptyList()
            h.player.itemOnCursor.type shouldBe Material.IRON_HELMET
            h.click(55, ClickType.LEFT, InventoryAction.PLACE_ALL).isCancelled shouldBe false
            h.player.setItemOnCursor(null)
            h.click(54, ClickType.SHIFT_LEFT).isCancelled shouldBe true
            h.calls shouldBe emptyList()
            h.name(49) shouldBe "Здесь можно только забрать вещи."
            h.click(47)
            h.click(54)
            h.calls shouldBe listOf(Triple(null, 9, ClickType.LEFT))
            h.complete()
            h.player.setItemOnCursor(ItemStack(Material.IRON_HELMET))
            h.click(1, ClickType.LEFT, InventoryAction.SWAP_WITH_CURSOR)
            h.calls.last() shouldBe Triple(0, CIVS_RESIDENT_CURSOR_SLOT, ClickType.LEFT)
        }
    }

    test("stale changed and truncated overflow snapshots refresh without transferring a different item") {
        nativeEquipment { h ->
            h.items += ItemStack(Material.IRON_PICKAXE)
            h.open()
            h.click(51)
            h.items[33] = ItemStack(Material.DIAMOND_PICKAXE)
            h.click(18)
            h.calls shouldBe emptyList()
            h.player.openInventory.topInventory.getItem(18) shouldBe h.items[33]
            h.click(18)
            h.calls shouldBe listOf(Triple(33, null, ClickType.LEFT))
            h.complete()
            h.items.removeAt(33)
            h.items[6] = ItemStack(Material.BREAD)
            h.click(18)
            h.calls.size shouldBe 1
            h.name(13) shouldBe "Сумка · 27 ячеек"
            h.player.openInventory.topInventory.getItem(18) shouldBe h.items[6]
            h.player.openInventory.topInventory.getItem(47)!!.type shouldBe Material.GRAY_STAINED_GLASS_PANE
            h.player.openInventory.topInventory.getItem(51)!!.type shouldBe Material.GRAY_STAINED_GLASS_PANE
            h.click(18)
            h.calls.last() shouldBe Triple(6, null, ClickType.LEFT)
        }
    }

    test("failed or out-of-range overflow snapshots clear every displayed item and block transfers") {
        nativeEquipment { h ->
            h.items += ItemStack(Material.IRON_PICKAXE)
            h.open(canDeposit = false)
            h.click(51)
            h.failSnapshot = true
            h.click(18)
            h.calls shouldBe emptyList()
            for (slot in (0..32).map(::civsEquipmentViewSlot)) h.player.openInventory.topInventory.getItem(slot) shouldBe null
            h.failSnapshot = false
            h.items.addAll(List(CivsEquipment.MAX_SLOTS + 1 - h.items.size) { ItemStack(Material.DIAMOND) })
            h.paper.performTicks(20)
            h.click(18)
            h.click(51)
            h.calls shouldBe emptyList()
            h.player.openInventory.topInventory.getItem(51)!!.type shouldBe Material.GRAY_STAINED_GLASS_PANE
            h.items.subList(33, h.items.size).clear()
            h.items[6] = ItemStack(Material.BREAD)
            h.paper.performTicks(20)
            h.click(18)
            h.calls shouldBe listOf(Triple(6, null, ClickType.LEFT))
        }
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

    test("creative cursor tools target the clicked resident slot once and the service owns item mutation") {
        nativeEquipment { h ->
            h.player.gameMode = GameMode.CREATIVE
            val pickaxe = ItemStack(Material.DIAMOND_PICKAXE).apply {
                editMeta { it.displayName(Component.text("Кирка шахтёра")); (it as Damageable).damage = 17 }
            }
            h.open()
            h.player.setItemOnCursor(pickaxe)
            h.click(6, ClickType.LEFT, InventoryAction.SWAP_WITH_CURSOR).isCancelled shouldBe true
            h.calls shouldBe listOf(Triple(4, CIVS_RESIDENT_CURSOR_SLOT, ClickType.LEFT))
            h.player.itemOnCursor shouldBe pickaxe
            h.click(6, ClickType.RIGHT)
            h.calls.size shouldBe 1
            h.player.setItemOnCursor(null)
            h.items[4] = pickaxe
            h.future.complete(Unit)
            h.paper.performTicks(2)
            h.player.openInventory.topInventory.getItem(6) shouldBe pickaxe
            h.future = CompletableFuture()
            h.player.setItemOnCursor(ItemStack(Material.IRON_HELMET))
            h.click(1, ClickType.RIGHT, InventoryAction.SWAP_WITH_CURSOR)
            h.calls.last() shouldBe Triple(0, CIVS_RESIDENT_CURSOR_SLOT, ClickType.RIGHT)
        }
    }

    test("cursor items can return to player slots while unrelated cancellation remains authoritative") {
        nativeEquipment { h ->
            h.open()
            h.player.setItemOnCursor(ItemStack(Material.DIAMOND))
            h.click(54, ClickType.LEFT, InventoryAction.PLACE_ALL).isCancelled shouldBe false
            val guarded = InventoryClickEvent(h.player.openInventory, InventoryType.SlotType.CONTAINER,
                54, ClickType.LEFT, InventoryAction.PLACE_ALL).also { it.isCancelled = true }
            h.menu.onClick(guarded)
            guarded.isCancelled shouldBe true
            h.calls shouldBe emptyList()
            h.player.itemOnCursor.type shouldBe Material.DIAMOND
        }
    }

    test("loot view retains native belongings and shows death details without deposit hints") {
        nativeEquipment { h ->
            h.items[4] = ItemStack(Material.IRON_PICKAXE)
            h.items[6] = ItemStack(Material.COBBLESTONE, 28)
            h.open(canDeposit = false, details = listOf(Component.text("Убийца: Зомби")))
            h.export("corpse-loot")
            h.player.inventory.setItem(9, ItemStack(Material.DIAMOND))
            h.click(54).isCancelled shouldBe true
            h.player.setItemOnCursor(ItemStack(Material.DIAMOND))
            h.click(18).isCancelled shouldBe true
            h.calls shouldBe emptyList()
            h.player.setItemOnCursor(null)
            h.click(18)
            h.calls shouldBe listOf(Triple(6, null, ClickType.LEFT))
        }
    }

    test("unsupported cursor clicks placeholders and failed snapshots never transfer") {
        nativeEquipment { h ->
            h.items[6] = ItemStack(Material.BREAD, 8)
            h.open()
            h.player.setItemOnCursor(ItemStack(Material.DIAMOND))
            h.click(18, ClickType.SHIFT_LEFT).isCancelled shouldBe true
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

    fun open(canDeposit: Boolean = true, details: List<Component> = emptyList(), stockDetails: () -> List<Component> = { emptyList() }) = menu.open(player, "Василий", {
        if (failSnapshot) throw CivsRejection("inventory-recovery")
        items
    }, { _, npc, slot, click -> calls += Triple(npc, slot, click); future }, { backs++ }, canDeposit = canDeposit,
        titleKey = if (canDeposit) "equipment.title" else "corpse.inventory-title",
        backKey = if (canDeposit) "equipment.back" else "corpse.back", details = details, stockDetails = stockDetails)

    fun click(raw: Int, click: ClickType = ClickType.LEFT, action: InventoryAction = InventoryAction.PICKUP_ALL): InventoryClickEvent = InventoryClickEvent(
        player.openInventory, InventoryType.SlotType.CONTAINER, raw, click, action,
    ).also(menu::onClick)

    fun complete() {
        future.complete(Unit)
        paper.performTicks(2)
        future = CompletableFuture()
    }

    fun name(slot: Int): String = PlainTextComponentSerializer.plainText().serialize(
        requireNotNull(player.openInventory.topInventory.getItem(slot)!!.itemMeta.displayName()))

    fun export(id: String, page: Int = 0) {
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
            "page" to page, "logicalSize" to items.size,
            "logicalToView" to ((0..5) + ((6 + page * 27) until minOf(33 + page * 27, items.size)))
                .associateWith { civsEquipmentViewSlot(it, page) })
        Files.writeString(directory.resolve("$id.json"), GsonBuilder().setPrettyPrinting().create().toJson(payload))
    }
}
