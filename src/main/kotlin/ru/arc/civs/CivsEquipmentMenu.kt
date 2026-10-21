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
 * A view of persisted native items. The menu owns no items and never puts an item on the cursor.
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
    ) : InventoryHolder {
        lateinit var view: Inventory
        var pending = false
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
    ) {
        if (closed || !player.isOnline) return
        val session = Session(player, tasks.token(), items, transfer, back)
        val title = text.render(player, "equipment.title", "name" to workerName)
            .color(TextColor.color(0x20252B))
        session.view = plugin.server.createInventory(session, CIVS_EQUIPMENT_SIZE, title)
        for (slot in 0 until CIVS_EQUIPMENT_SIZE) {
            if (civsEquipmentLogicalSlot(slot) == null) session.view.setItem(slot, filler.clone())
        }
        session.view.setItem(13, icon(Material.BUNDLE, player, "equipment.bag", "equipment.bag-help"))
        session.view.setItem(45, icon(Material.ARROW, player, "equipment.back"))
        // Opening replaces the previous view and can synchronously fire InventoryCloseEvent.
        sessions.remove(player.uniqueId)
        render(session)
        player.openInventory(session.view)
        sessions[player.uniqueId] = session
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onClick(event: InventoryClickEvent) {
        val session = owned(event.view.topInventory) ?: return
        event.isCancelled = true
        if (event.whoClicked.uniqueId != session.player.uniqueId || !current(session) || session.pending) return
        if (event.click !in CIVS_EQUIPMENT_CLICKS) return
        // Existing cursor contents stay with the player; this journal has no cursor custody.
        if (!event.cursor.type.isAir) {
            session.feedback = text.warning(session.player, "equipment.cursor-busy")
            status(session)
            return
        }
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
        if (session.snapshot.size != CIVS_EQUIPMENT_LOGICAL_SIZE) return
        val npcSlot = civsEquipmentLogicalSlot(event.rawSlot)
        val playerSlot = if (event.clickedInventory === session.player.inventory && event.slot in 0..35) event.slot else null
        if (npcSlot == null && playerSlot == null) return
        if (npcSlot != null && session.snapshot.getOrNull(npcSlot) == null) return
        if (playerSlot != null && session.player.inventory.getItem(playerSlot)?.type?.isAir != false) return
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

    private fun render(session: Session) {
        val snapshot = runCatching {
            val values = session.items()
            require(values.size == CIVS_EQUIPMENT_LOGICAL_SIZE) { "Resident inventory must contain 33 logical slots" }
            values.map { it?.takeUnless { item -> item.type.isAir || item.amount <= 0 }?.clone() }
        }
        val values = snapshot.getOrNull()
        if (values == null) {
            // Stale mirrors remain unclickable until a fresh authoritative snapshot is available.
            session.snapshot = emptyList()
            session.feedback = text.failure(session.player, requireNotNull(snapshot.exceptionOrNull()))
            for (logical in 0 until CIVS_EQUIPMENT_LOGICAL_SIZE) session.view.setItem(civsEquipmentViewSlot(logical), null)
        } else {
            session.snapshot = values
            for (logical in values.indices) {
                val slot = civsEquipmentViewSlot(logical)
                val item = values[logical] ?: if (logical < 6) icon(Material.GRAY_DYE, session.player,
                    "equipment.slot-${CIVS_EQUIPMENT_GEAR[logical]}", "equipment.empty-gear") else null
                if (session.view.getItem(slot) != item) session.view.setItem(slot, item?.clone())
            }
        }
        status(session)
    }

    private fun status(session: Session) {
        val material = when { session.pending -> Material.CLOCK; session.feedback != null -> Material.PAPER; else -> Material.BOOK }
        val item = icon(material, session.player, if (session.pending) "equipment.pending" else "equipment.help",
            "equipment.give-help", "equipment.take-help", "equipment.amount-help")
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
internal val CIVS_EQUIPMENT_GEAR = listOf("helmet", "chest", "legs", "boots", "main", "off")
internal val CIVS_EQUIPMENT_CLICKS = setOf(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT)
private val CIVS_EQUIPMENT_GEAR_SLOTS = listOf(1, 2, 3, 4, 6, 7)

internal fun civsEquipmentViewSlot(logical: Int): Int {
    require(logical in 0 until CIVS_EQUIPMENT_LOGICAL_SIZE)
    return if (logical < 6) CIVS_EQUIPMENT_GEAR_SLOTS[logical] else logical + 12
}

internal fun civsEquipmentLogicalSlot(view: Int): Int? = when {
    view in 18..44 -> view - 12
    view in CIVS_EQUIPMENT_GEAR_SLOTS -> CIVS_EQUIPMENT_GEAR_SLOTS.indexOf(view)
    else -> null
}
