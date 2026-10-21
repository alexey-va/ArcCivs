package ru.arc.civs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * A view of persisted native items. The menu owns no items and never mutates the cursor.
 * Every accepted click delegates one atomic transfer to the authoritative inventory service.
 */
class CivsEquipmentMenu(
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val text: CivsText,
    background: ItemStack = ItemStack(Material.GRAY_STAINED_GLASS_PANE),
) : AutoCloseable, Listener {
    private class Session(
        val player: Player,
        val epoch: LifecycleTaskScope.Token,
        val items: () -> List<ItemStack?>,
        val transfer: (Player, Int?, Int?, ClickType) -> CompletableFuture<Unit>,
        val back: () -> Unit,
        val canDeposit: Boolean,
        val details: List<Component>,
    ) : InventoryHolder {
        lateinit var view: Inventory
        var pending = false
        var page = 0
        var feedback: Component? = null
        var snapshot = emptyList<ItemStack?>()
        override fun getInventory(): Inventory = view
    }

    private val sessions = mutableMapOf<UUID, Session>()
    private val filler = background.clone().apply {
        amount = 1
        editMeta { it.displayName(Component.space()); it.isHideTooltip = true }
    }
    private var closed = false
    private val refresh = tasks.runTimer(20, 20) {
        sessions.values.toList().forEach { session ->
            if (current(session) && !session.pending) render(session)
        }
    }

    init { plugin.server.pluginManager.registerEvents(this, plugin) }

    fun open(
        player: Player,
        workerName: String,
        items: () -> List<ItemStack?>,
        transfer: (Player, Int?, Int?, ClickType) -> CompletableFuture<Unit>,
        back: () -> Unit,
        canDeposit: Boolean = true,
        titleKey: String = "equipment.title",
        backKey: String = "equipment.back",
        details: List<Component> = emptyList(),
    ) {
        if (closed || !player.isOnline) return
        val session = Session(player, tasks.token(), items, transfer, back, canDeposit, details)
        val title = text.render(player, titleKey, "name" to workerName)
            .color(TextColor.color(0x20252B))
        session.view = plugin.server.createInventory(session, CIVS_EQUIPMENT_SIZE, title)
        for (slot in 0 until CIVS_EQUIPMENT_SIZE) {
            if (civsEquipmentLogicalSlot(slot) == null) session.view.setItem(slot, filler.clone())
        }
        session.view.setItem(45, icon(Material.ARROW, player, backKey))
        // Opening replaces the previous view and can synchronously fire InventoryCloseEvent.
        sessions.remove(player.uniqueId)
        render(session)
        player.openInventory(session.view)
        sessions[player.uniqueId] = session
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onClick(event: InventoryClickEvent) {
        val session = owned(event.view.topInventory) ?: return
        val previouslyCancelled = event.isCancelled
        event.isCancelled = true
        if (event.whoClicked.uniqueId != session.player.uniqueId || !current(session) || session.pending) return
        if (event.click !in CIVS_EQUIPMENT_CLICKS) return
        if (event.rawSlot == 45) {
            sessions.remove(session.player.uniqueId)
            tasks.runLater(session.epoch, 1) {
                if (!closed && session.player.isOnline && session.player.openInventory.topInventory === session.view) {
                    session.player.closeInventory()
                    session.back()
                }
            }
            return
        }
        if (event.rawSlot == 47 || event.rawSlot == 51) {
            val page = session.page + if (event.rawSlot == 47) -1 else 1
            if (page in 0 until civsEquipmentPages(session.snapshot.size)) {
                session.page = page
                session.feedback = null
                render(session)
            }
            return
        }
        val npcSlot = civsEquipmentLogicalSlot(event.rawSlot, session.page)
        var playerSlot = if (event.clickedInventory === session.player.inventory && event.slot in 0..35) event.slot else null
        val cursor = !event.cursor.type.isAir && event.cursor.amount > 0
        val canDeposit = session.canDeposit && session.page == 0
        if (cursor) {
            if (canDeposit && npcSlot != null && event.click in setOf(ClickType.LEFT, ClickType.RIGHT)) {
                // The inventory service stages and journals the cursor item; the UI never consumes it.
                playerSlot = CIVS_RESIDENT_CURSOR_SLOT
            } else if (playerSlot != null && event.action in setOf(InventoryAction.PLACE_ALL, InventoryAction.PLACE_ONE,
                    InventoryAction.PLACE_SOME, InventoryAction.SWAP_WITH_CURSOR)) {
                // Returning an item to the player's own inventory needs no resident transaction.
                event.isCancelled = previouslyCancelled
                return
            } else {
                session.feedback = text.warning(session.player, when {
                    !session.canDeposit -> "corpse.take-only"
                    session.page > 0 -> "equipment.overflow-take-only"
                    else -> "equipment.cursor-busy"
                })
                status(session)
                return
            }
        }
        if (session.snapshot.size !in CIVS_EQUIPMENT_LOGICAL_SIZE..CivsEquipment.MAX_SLOTS) return
        if (npcSlot == null && playerSlot == null) return
        if (!canDeposit && playerSlot != null) {
            if (session.canDeposit) {
                session.feedback = text.warning(session.player, "equipment.overflow-take-only")
                status(session)
            }
            return
        }
        if (npcSlot != null && !cursor && session.snapshot.getOrNull(npcSlot) == null) return
        if (playerSlot != null && playerSlot != CIVS_RESIDENT_CURSOR_SLOT && session.player.inventory.getItem(playerSlot)?.type?.isAir != false) return
        val latest = readSnapshot(session)
        if (latest.getOrNull() != session.snapshot) {
            // Another viewer or a worker may have changed the bag since this page was drawn.
            session.feedback = text.warning(session.player, "equipment.updated")
            render(session, latest)
            return
        }
        session.pending = true
        session.feedback = null
        status(session)
        val future = try { session.transfer(session.player, npcSlot, playerSlot, event.click) }
            catch (failure: Exception) { CompletableFuture.failedFuture(failure) }
        future.whenCompleteSync(tasks, session.epoch) { _, failure ->
            if (!current(session)) return@whenCompleteSync
            session.pending = false
            session.feedback = failure?.let { text.failure(session.player, it) }
            render(session)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDrag(event: InventoryDragEvent) {
        if (owned(event.view.topInventory) != null) event.isCancelled = true
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val session = owned(event.view.topInventory) ?: return
        sessions.remove(event.player.uniqueId, session)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) { sessions.remove(event.player.uniqueId) }

    fun dismiss(player: Player) {
        val session = sessions.remove(player.uniqueId) ?: return
        if (player.openInventory.topInventory === session.view) player.closeInventory()
    }

    private fun owned(inventory: Inventory): Session? = (inventory.holder as? Session)
        ?.takeIf { it.view === inventory }

    private fun current(session: Session): Boolean = !closed && tasks.isCurrent(session.epoch) &&
        session.player.isOnline && sessions[session.player.uniqueId] === session &&
        session.player.openInventory.topInventory === session.view

    private fun readSnapshot(session: Session): Result<List<ItemStack?>> = runCatching {
        val values = session.items()
        require(values.size in CIVS_EQUIPMENT_LOGICAL_SIZE..CivsEquipment.MAX_SLOTS) {
            "Resident inventory must contain 33 to ${CivsEquipment.MAX_SLOTS} logical slots"
        }
        values.map { it?.takeUnless { item -> item.type.isAir || item.amount <= 0 }?.clone() }
    }

    private fun render(session: Session, snapshot: Result<List<ItemStack?>> = readSnapshot(session)) {
        val values = snapshot.getOrNull()
        if (values == null) {
            // Stale mirrors remain unclickable until a fresh authoritative snapshot is available.
            session.snapshot = emptyList()
            session.page = 0
            session.feedback = text.failure(session.player, requireNotNull(snapshot.exceptionOrNull()))
            for (logical in 0 until CIVS_EQUIPMENT_LOGICAL_SIZE) session.view.setItem(civsEquipmentViewSlot(logical), null)
        } else {
            session.snapshot = values
            session.page = session.page.coerceIn(0, civsEquipmentPages(values.size) - 1)
            for (slot in 0 until CIVS_EQUIPMENT_SIZE) {
                val logical = civsEquipmentLogicalSlot(slot, session.page) ?: continue
                val item = values.getOrNull(logical) ?: if (logical < 6) icon(Material.GRAY_DYE, session.player,
                    "equipment.slot-${CIVS_EQUIPMENT_GEAR[logical]}",
                    if (session.canDeposit && session.page == 0) "equipment.empty-gear" else "corpse.empty-slot") else null
                if (session.view.getItem(slot) != item) session.view.setItem(slot, item?.clone())
            }
        }
        navigation(session)
        status(session)
    }

    private fun navigation(session: Session) {
        val pages = civsEquipmentPages(session.snapshot.size)
        val bag = icon(Material.BUNDLE, session.player, "equipment.bag",
            if (session.page == 0) "equipment.bag-help" else "equipment.overflow-help")
        if (pages > 1) bag.editMeta { it.displayName(text.render(session.player, "equipment.bag-page",
            "page" to session.page + 1, "pages" to pages)) }
        if (session.view.getItem(13) != bag) session.view.setItem(13, bag)
        session.view.setItem(47, if (session.page > 0) icon(Material.ARROW, session.player, "common.previous") else filler.clone())
        session.view.setItem(51, if (session.page + 1 < pages) icon(Material.ARROW, session.player, "common.next") else filler.clone())
    }

    private fun status(session: Session) {
        val material = when { session.pending -> Material.CLOCK; session.feedback != null -> Material.PAPER; else -> Material.BOOK }
        val canDeposit = session.canDeposit && session.page == 0
        val item = icon(material, session.player, if (session.pending) "equipment.pending" else if (canDeposit) "equipment.help" else "corpse.items")
        item.editMeta { meta -> meta.lore(buildList {
            add(Component.empty())
            addAll(session.details)
            if (session.details.isNotEmpty()) add(Component.empty())
            if (canDeposit) add(text.render(session.player, "equipment.give-help"))
            else if (session.canDeposit) add(text.render(session.player, "equipment.overflow-give-help"))
            add(text.render(session.player, "equipment.take-help"))
            add(text.render(session.player, "equipment.amount-help"))
        }) }
        session.feedback?.let { feedback -> item.editMeta { it.displayName(feedback.decoration(TextDecoration.ITALIC, false)) } }
        if (session.view.getItem(49) != item) session.view.setItem(49, item)
    }

    private fun icon(material: Material, player: Player, name: String, vararg lore: String): ItemStack = ItemStack(material).apply {
        editMeta { meta ->
            meta.displayName(text.render(player, name))
            if (lore.isNotEmpty()) meta.lore(lore.map { text.render(player, it) })
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        refresh?.cancel()
        val open = sessions.values.toList()
        sessions.clear()
        open.forEach { if (it.player.openInventory.topInventory === it.view) it.player.closeInventory() }
        HandlerList.unregisterAll(this)
    }
}

internal const val CIVS_EQUIPMENT_SIZE = 54
internal const val CIVS_EQUIPMENT_LOGICAL_SIZE = 33
private const val CIVS_EQUIPMENT_BAG_PAGE_SIZE = 27
internal val CIVS_EQUIPMENT_GEAR = listOf("helmet", "chest", "legs", "boots", "main", "off")
internal val CIVS_EQUIPMENT_CLICKS = setOf(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT)
private val CIVS_EQUIPMENT_GEAR_SLOTS = listOf(1, 2, 3, 4, 6, 7)

internal fun civsEquipmentPages(logicalSize: Int): Int =
    ((logicalSize - 6 + CIVS_EQUIPMENT_BAG_PAGE_SIZE - 1) / CIVS_EQUIPMENT_BAG_PAGE_SIZE).coerceAtLeast(1)

internal fun civsEquipmentViewSlot(logical: Int): Int = civsEquipmentViewSlot(logical, 0)

internal fun civsEquipmentViewSlot(logical: Int, page: Int): Int {
    require(page >= 0)
    val start = 6 + page * CIVS_EQUIPMENT_BAG_PAGE_SIZE
    require(logical in 0..5 || logical in start until start + CIVS_EQUIPMENT_BAG_PAGE_SIZE)
    return if (logical < 6) CIVS_EQUIPMENT_GEAR_SLOTS[logical] else logical - start + 18
}

internal fun civsEquipmentLogicalSlot(view: Int): Int? = civsEquipmentLogicalSlot(view, 0)

internal fun civsEquipmentLogicalSlot(view: Int, page: Int): Int? = when {
    view in 18..44 -> view - 12 + page * CIVS_EQUIPMENT_BAG_PAGE_SIZE
    view in CIVS_EQUIPMENT_GEAR_SLOTS -> CIVS_EQUIPMENT_GEAR_SLOTS.indexOf(view)
    else -> null
}
