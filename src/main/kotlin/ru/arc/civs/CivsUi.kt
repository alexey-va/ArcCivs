package ru.arc.civs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.DialogDecoration
import ru.arc.civs.CivsDialogPresentation.Action
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogNavigation
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogTextInput
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Native screen composition. The host revalidates every domain mutation. */
class CivsUi internal constructor(
    internal val plugin: Plugin,
    internal val host: CivsUiHost,
    internal val tasks: LifecycleTaskScope,
    internal val runtime: PaperDialogRuntime,
    internal val text: CivsText,
) : AutoCloseable, Listener {
    constructor(plugin: Plugin, host: CivsUiHost, tasks: LifecycleTaskScope, text: CivsText) :
        this(plugin, host, tasks, PaperDialogRuntime(plugin), text)

    internal class Visit(val screenId: String, val epoch: LifecycleTaskScope.Token, val cityId: UUID?) {
        val id: UUID = UUID.randomUUID()
        var consumed = false
        var cancelPending: (() -> Unit)? = null
    }
    internal val visits = mutableMapOf<UUID, Visit>()
    internal val roots = mutableMapOf<UUID, String>()
    internal var closed = false
    internal val equipment = CivsEquipmentMenu(plugin, tasks, text)
    internal val presentation = CivsDialogPresentation(plugin.config.getString("presentation.font", "").orEmpty().trim())

    init { plugin.server.pluginManager.registerEvents(this, plugin) }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        visits.remove(event.player.uniqueId)
        roots.remove(event.player.uniqueId)
    }

    fun open(player: Player) {
        if (closed || !player.isOnline) {
            CivsDiagnostics.event("ui_open_ignored", "player" to player.uniqueId, "closed" to closed, "online" to player.isOnline)
            return
        }
        CivsDiagnostics.event("ui_open", *CivsDiagnostics.actor(player))
        equipment.dismiss(player)
        host.cancelSelection(player, "menu-reopened")
        runtime.beginFlow(player)
        if (!host.ready()) {
            roots[player.uniqueId] = "civs.loading"
            showEntry(player)
            return
        }
        roots[player.uniqueId] = "civs.lookup"
        perform(player, "civs.lookup", "common.title", { host.refreshCity(player) }, success = {
            runtime.beginFlow(player)
            openEntry(player)
        }, failure = { error ->
            runtime.beginFlow(player)
            roots[player.uniqueId] = "civs.network-error"
            present(player, "civs.network-error", "common.title", listOf(
                body(tr(player, "network.lookup-failed").color(DANGER)), body(error)),
                emptyList(), reopen = { open(player) })
        }, workingKey = "network.loading")
    }

    internal fun openEntry(player: Player) {
        roots[player.uniqueId] = when {
            !host.ready() -> "civs.loading"
            host.preview(player) != null -> "civs.preview"
            host.city(player) != null -> "civs.overview"
            host.remoteCity(player) != null -> "civs.remote"
            else -> "civs.welcome"
        }
        showEntry(player)
    }

    fun showingDialog(player: Player): Boolean = player.uniqueId in visits

    /** Administrative village flow is independent of the player's personal settlement. */
    fun openAutonomous(player: Player, cityId: UUID? = null) {
        if (closed || !player.isOnline) return
        equipment.dismiss(player)
        host.cancelSelection(player, "menu-reopened")
        runtime.beginFlow(player)
        roots[player.uniqueId] = if (cityId == null) "civs.autonomous" else "civs.autonomous-city"
        if (!isAdmin(player)) {
            autonomyDenied(player)
        } else if (cityId != null) {
            showAutonomousCity(player, cityId)
        } else if (host.preview(player)?.autonomous == true) {
            roots[player.uniqueId] = "civs.preview"
            showPreview(player)
        } else showAutonomous(player)
    }

    /** Forms and details yield to the world instead of reopening a placement modal. */
    fun resumePlacement(player: Player) {
        visits.remove(player.uniqueId)
        roots.remove(player.uniqueId)
        runtime.close(player)
        if (host.selection(player) != null) player.sendActionBar(text.info(player, "selection.hint"))
        else if (host.preview(player) != null) player.sendMessage(text.info(player, "panel.opened"))
    }

    fun openWorker(player: Player, cityId: UUID, workerId: UUID) {
        if (closed || !player.isOnline) return
        equipment.dismiss(player)
        host.cancelSelection(player, "menu-reopened")
        runtime.beginFlow(player)
        roots[player.uniqueId] = "civs.worker"
        val city = host.settlements().firstOrNull { it.id == cityId }
        if (city == null || !(if (city.autonomy != null) isAdmin(player) else city.canManage(player.uniqueId))) {
            present(player, "civs.worker", "workers.detail-title", listOf(body(text.reason(player,
                if (city?.autonomy != null) "not-authorized" else "not-member"))), emptyList(), cityId = cityId)
        } else if (city.autonomy != null) {
            showWorker(player, workerId, cityId = city.id)
        } else {
            val worker = city.workers.firstOrNull { it.id == workerId }
            val building = worker?.assignment?.let(city::building)
            if (building != null && CivsRules.isBuilder(city, building) && city.construction != null) {
                roots[player.uniqueId] = "civs.builder-materials"
                loadBuilderMaterials(player, building.id, workerId)
            } else showWorker(player, workerId)
        }
    }

    fun openCorpse(player: Player, cityId: UUID, corpseId: UUID) {
        if (closed || !player.isOnline) return
        equipment.dismiss(player)
        host.cancelSelection(player, "menu-reopened")
        runtime.beginFlow(player)
        val city = host.settlements().firstOrNull { it.id == cityId &&
            (if (it.autonomy != null) isAdmin(player) else it.canManage(player.uniqueId)) }
        val corpse = city?.corpses.orEmpty().firstOrNull { it.worker.id == corpseId }
        if (corpse == null) {
            roots[player.uniqueId] = "civs.corpse"
            notice(player, "civs.corpse", "corpse.items", text.reason(player, if (city == null) "not-member" else "worker-not-found")) { open(player) }
            return
        }
        val killerKey = "corpse.killer.${corpse.killerType?.lowercase(Locale.ROOT)}"
        val causeKey = "corpse.cause.${corpse.cause.lowercase(Locale.ROOT)}"
        val death = if (corpse.killerName != null || corpse.killerType != null) tr(player, "corpse.killed-by", "name" to
            (corpse.killerName?.let(text::literal) ?: tr(player, killerKey.takeIf { it in text.keys("ru") } ?: "corpse.killer.unknown")))
        else tr(player, "corpse.died-from", "cause" to tr(player, causeKey.takeIf { it in text.keys("ru") } ?: "corpse.cause.unknown"))
        visits.remove(player.uniqueId)
        roots.remove(player.uniqueId)
        runtime.close(player)
        fun checkDeath() {
            if (host.settlements().firstOrNull { it.id == cityId }?.corpses.orEmpty().none {
                    it.worker.id == corpseId && it.diedAt == corpse.diedAt }) throw CivsRejection("worker-not-found")
        }
        equipment.open(player, corpse.worker.name, items = { checkDeath(); host.corpseItems(player, cityId, corpseId) },
            transfer = { actor, npcSlot, playerSlot, click ->
                checkDeath()
                if (npcSlot != null && playerSlot == null) host.transferCorpseItem(actor, cityId, corpseId, npcSlot, click)
                else CompletableFuture.failedFuture(CivsRejection("not-authorized"))
            }, back = { if (city?.autonomy != null) openAutonomous(player, cityId) else open(player) }, canDeposit = false,
            titleKey = if (corpse.worker.sex == CivSex.FEMALE) "corpse.inventory-title-female" else "corpse.inventory-title",
            backKey = "corpse.back",
            details = listOf(death))
    }

    /** The control block and the town catalog open the same building screen. */
    fun openBuilding(player: Player, cityId: UUID, buildingId: UUID) {
        if (closed || !player.isOnline) return
        equipment.dismiss(player)
        host.cancelSelection(player, "menu-reopened")
        runtime.beginFlow(player)
        roots[player.uniqueId] = "civs.building"
        val city = host.settlements().firstOrNull { it.id == cityId }
        if (city == null || !city.canManage(player.uniqueId)) {
            notice(player, "civs.building", "buildings.detail-title", text.reason(player, "not-member")) { showEntry(player) }
        } else {
            val building = city.building(buildingId)
            if (building != null && CivsRules.isBuilder(city, building) && city.construction != null) {
                roots[player.uniqueId] = "civs.builder-materials"
                loadBuilderMaterials(player, buildingId)
            } else showBuilding(player, buildingId)
        }
    }

    /** Public Auto controls disclose the building and real barrel; management stays in its own flow. */
    fun openBuildingInspection(player: Player, cityId: UUID, buildingId: UUID) {
        if (closed || !player.isOnline) return
        equipment.dismiss(player)
        host.cancelSelection(player, "menu-reopened")
        runtime.beginFlow(player)
        roots[player.uniqueId] = "civs.building-inspection"
        showBuildingInspection(player, cityId, buildingId)
    }

    internal fun city(player: Player): Settlement? {
        val city = host.city(player)
        if (city == null || !city.canManage(player.uniqueId)) {
            showEntry(player)
            return null
        }
        return city
    }

    internal fun beginSelection(player: Player, action: () -> CompletableFuture<Unit>, failure: (Component) -> Unit) {
        val visit = visits[player.uniqueId] ?: return
        visit.cancelPending = { host.cancelSelection(player, "dialog-closed") }
        val future = try { action() } catch (error: Exception) { CompletableFuture.failedFuture(error) }
        future.whenCompleteSync(tasks, visit.epoch) { _, error ->
            if (!current(player, visit)) return@whenCompleteSync
            visit.cancelPending = null
            if (error == null) resumePlacement(player) else failure(text.failure(player, error))
        }
    }

    internal fun <T : Any> perform(player: Player, id: String, title: String, action: () -> CompletableFuture<T>,
        success: (T) -> Unit, failure: (Component) -> Unit, cancelPending: (() -> Unit)? = null,
        workingKey: String? = "common.working") {
        // Transfers retain the consumed visit and its table until fresh state can replace it once.
        val visit = if (workingKey == null) visits[player.uniqueId]?.takeIf { it.screenId == id && current(player, it) }
            else present(player, id, title, listOf(body(player, workingKey)), emptyList(),
                cityId = visits[player.uniqueId]?.takeIf { it.screenId == id }?.cityId)
        if (visit == null) return
        visit.cancelPending = cancelPending
        val future = try { action() } catch (error: Exception) { CompletableFuture.failedFuture(error) }
        future.whenCompleteSync(tasks, visit.epoch) { result, error ->
            if (!current(player, visit)) {
                CivsDiagnostics.event("ui_completion_ignored", "player" to player.uniqueId, "screen" to id, "visit" to visit.id, "city" to visit.cityId)
                return@whenCompleteSync
            }
            visit.cancelPending = null
            if (error == null) success(requireNotNull(result)) else failure(text.failure(player, error))
        }
    }

    internal fun notice(player: Player, id: String, title: String, content: Component,
        destinationKey: String = "common.settlements", destination: () -> Unit) =
        present(player, id, title, listOf(body(content)), listOf(button(player, "return", destinationKey) { destination() }))

    internal fun present(player: Player, id: String, title: String, bodies: List<PaperDialogBody>, buttons: List<PaperDialogButton>,
        inputs: List<PaperDialogTextInput> = emptyList(), reopen: (() -> Unit)? = null, cityId: UUID? = null): Visit? {
        if (closed || !player.isOnline) return null
        val visit = Visit(id, tasks.token(), cityId)
        visits[player.uniqueId] = visit
        CivsDiagnostics.event("ui_screen", *CivsDiagnostics.actor(player), "screen" to id, "visit" to visit.id, "city" to visit.cityId,
            "actions" to buttons.joinToString { it.id.toString() }, "inputs" to inputs.size)
        val hasParent = roots[player.uniqueId] != id
        val closeOnEscape = !hasParent && player.getMetadata("arc-menu-escape").any { it.asString() == "close" }
        val guarded = buttons.map { button ->
            val control = when (button.id.value) {
                "previous", "materials_previous" -> PaperDialogNavigation.previous(button, button.label)
                "next", "materials_next" -> PaperDialogNavigation.next(button, button.label)
                else -> button
            }
            control.copy(closeDialogBeforeAction = false, onClick = { context ->
                val accepted = current(player, visit) && !visit.consumed
                CivsDiagnostics.event("ui_click", *CivsDiagnostics.actor(player), "screen" to id, "visit" to visit.id, "city" to visit.cityId,
                    "action" to button.id.toString(), "outcome" to if (accepted) "accepted" else "ignored",
                    "reason" to if (accepted) null else if (visit.consumed) "already-consumed" else "stale-visit",
                    "city_name" to context.text(NAME), "member_name" to context.text(MEMBER))
                if (accepted) {
                    visit.consumed = true
                    if (button.closeDialogBeforeAction) runtime.close(player)
                    button.onClick.handle(context)
                }
            })
        }
        val screen = PaperDialogScreen(id = id, title = tint(tr(player, title), screenColor(id)), body = bodies,
            inputs = inputs, buttons = guarded,
            columns = if (guarded.size == 1) 1 else 2,
            exitButton = if (hasParent) presentation.button(
                button(player, "history", "common.back").copy(width = 200), Action.BACK,
            ) else button(player, "close", "common.close").copy(width = 200, tooltip = tr(player, "common.close")),
        )
        runtime.open(player, PaperDialogNavigation.bottom(
            screen,
            guarded.firstOrNull { it.id.value == "previous" },
            guarded.firstOrNull { it.id.value == "next" },
        ), reopen, {
            CivsDiagnostics.event("ui_dismiss", "player" to player.uniqueId, "screen" to id, "visit" to visit.id, "city" to visit.cityId,
                "current" to (visits[player.uniqueId] === visit), "pending" to (visit.cancelPending != null))
            if (visits[player.uniqueId] === visit) {
                visits.remove(player.uniqueId)
                visit.cancelPending?.invoke()
            }
        }, closeOnEscape)
        return visit
    }

    internal fun current(player: Player, visit: Visit): Boolean =
        !closed && player.isOnline && tasks.isCurrent(visit.epoch) && visits[player.uniqueId] === visit

    internal fun button(player: Player, id: String, key: String, color: TextColor = WHITE,
        close: Boolean = false, appearance: Action? = null,
        action: ru.arc.paper.menu.PaperDialogClickHandler = ru.arc.paper.menu.PaperDialogClickHandler {}): PaperDialogButton =
        presentation.button(PaperDialogButton(PaperDialogActionId.of(id), tint(tr(player, key), color), width = 230,
            closeDialogBeforeAction = close, onClick = action), appearance)

    internal fun valueButton(player: Player, id: String, key: String, value: Component, color: TextColor = WHITE,
        tooltip: Component = Component.empty(), action: () -> Unit): PaperDialogButton =
        PaperDialogButton(PaperDialogActionId.of(id), tint(tr(player, key, "value" to value), color), tooltip,
            width = 230, onClick = { action() })

    internal fun requestTool(request: CivsBuildingRequest?): String? =
        request?.material?.name?.substringAfterLast('_')?.lowercase(Locale.ROOT)?.takeIf { it in setOf("hoe", "axe", "pickaxe") }

    internal fun requestButton(player: Player, request: CivsBuildingRequest, unavailableKey: String, helpKey: String,
        give: () -> Unit, refresh: () -> Unit): PaperDialogButton {
        val tool = requestTool(request)
        val creative = player.gameMode == GameMode.CREATIVE
        val creativeTool = tool != null && creative
        val busy = host.cityBusy(player)
        val canGive = request.canGive && !busy
        val color = if (canGive) COMMIT else DANGER
        val tooltip = tr(player, when {
            busy -> "reason.city-busy"
            !request.canGive -> when {
                creativeTool -> "workers.tool-request-creative-unavailable"
                tool != null -> "workers.tool-request-unavailable"
                creative -> "$unavailableKey-creative"
                else -> unavailableKey
            }
            creativeTool -> "workers.give-tool-creative-help"
            tool != null -> "workers.give-tool-help"
            creative -> "$helpKey-creative"
            else -> helpKey
        })
        val action = { if (canGive) give() else refresh() }
        return if (tool != null) button(player, "give_request", "buildings.give-tool-$tool", color) { action() }.copy(tooltip = tooltip)
            else valueButton(player, "give_request", "buildings.give", materialName(player, request.material.name), color, tooltip, action)
    }

    internal fun tint(value: Component, color: TextColor): Component =
        value.color(color).children(value.children().map { tint(it, color) })

    internal fun MutableList<PaperDialogButton>.pages(player: Player, page: Int, count: Int, pageSize: Int = PAGE_SIZE, show: (Int) -> Unit) {
        if (page > 0) add(button(player, "previous", "common.previous", NAVIGATION) { show(page - 1) })
        if (page < lastPage(count, pageSize)) add(button(player, "next", "common.next", NAVIGATION) { show(page + 1) })
    }

    internal fun lastPage(count: Int, pageSize: Int = PAGE_SIZE): Int = ((count - 1).coerceAtLeast(0)) / pageSize
    internal fun isAdmin(player: Player): Boolean = player.hasPermission("arccivs.admin")
    internal fun tr(player: Player, key: String, vararg values: Pair<String, Any?>): Component = text.render(player, key, *values)
    internal fun body(player: Player, key: String): PaperDialogBody = presentation.paragraph(tr(player, key))
    internal fun body(component: Component): PaperDialogBody = PaperDialogBody(component, 320)
    internal fun detail(player: Player, label: String, value: Component): Component =
        tr(player, "common.detail", "label" to tr(player, label), "value" to value)
    internal fun card(lines: List<Component>, width: Int = 320): PaperDialogBody =
        DialogTables.framedBody(Component.join(Component.newline(), lines), DialogTables.Frame.COMMON, width)
    internal fun facts(player: Player, rows: List<Pair<Component, Component>>): PaperDialogBody =
        presentation.paragraph(Component.join(Component.newline(), rows.map { (label, value) ->
            tr(player, "common.detail", "label" to label, "value" to value)
        }))
    internal fun table(rows: List<Pair<Component, Component>>, columns: DialogTables.Columns = DialogTables.Columns.VALUE_WIDE,
        frame: DialogTables.Frame = DialogTables.Frame.UNCOMMON): PaperDialogBody = DialogTables.body(
        rows.map { (label, value) -> label.color(CivsText.BODY) to value.colorIfAbsent(WHITE) },
        null, frame, 320, columns,
    )
    internal fun busyBody(player: Player): PaperDialogBody? = if (host.cityBusy(player)) body(tint(tr(player, "reason.city-busy"), STOCK)) else null
    internal fun materialName(player: Player, material: String): Component = text.material(player, material)
    internal fun warehouseAmount(player: Player, own: Long, reserve: Long): Component = if (reserve > 0)
        tr(player, "warehouse.amount-with-reserve", "own" to own, "reserve" to reserve) else text.literal(own)
    internal fun buildingName(player: Player, type: BuildingType, level: Int): Component =
        tint(tr(player, "buildings.name", "name" to text.building(player, type), "level" to level), buildingColor(type))
    internal fun coordinates(player: Player, pos: CivPosition): Component = tr(player, "common.coordinates", "x" to pos.x, "y" to pos.y, "z" to pos.z)
    internal fun playerName(city: Settlement, id: UUID): Component = tint(text.literal(host.memberName(city, id)), PEOPLE)

    internal fun screenColor(id: String): TextColor = when (id) {
        "civs.workers", "civs.worker", "civs.members", "civs.member", "civs.member-add", "civs.building-workers" -> PEOPLE
        "civs.stock", "civs.warehouse", "civs.materials", "civs.building-materials", "civs.builder-materials", "civs.logistics",
        "civs.containers", "civs.container", "civs.industry", "civs.industry-building" -> STOCK
        "civs.management", "civs.upgrades", "civs.upgrade", "civs.worker-skills", "civs.production", "civs.carpentry-recipes", "civs.storage-mode", "civs.testing-settings", "civs.research", "civs.research-detail" -> PERSONAL
        "civs.guide", "civs.remote" -> NAVIGATION
        "civs.delete-city", "civs.delete-building" -> DANGER
        else -> LAND
    }

    internal fun statusColor(status: String): TextColor = when (status.substringAfterLast('.')) {
        "placement-failed", "delivery-failed", "unreachable", "route-unreachable", "lands-denied",
        "world-changed", "protected-terrain", "building-overlap", "outside-city", "protected-area",
        "storage-unavailable", "injury-pending", "closed", "deleting", "alarm-shelter" -> DANGER
        "active", "working", "working-field", "watering", "fertilizing", "planting-tree", "felling-tree", "digging-mine", "building", "placing",
        "delivering", "loading-cargo", "collecting-supplies", "guarding", "eating" -> COMMIT
        "to-work", "moving-to-work", "moving-to-storage", "moving-to-tavern", "moving-to-town-hall", "to-store", "moving-to-pickup", "carrying-cargo", "to-meal", "patrolling" -> NAVIGATION
        "sleeping", "sheltering", "to-campfire", "campfire", "no-viewers", "learning" -> PERSONAL
        "playing", "wandering", "unassigned", "no-assignment", "no-construction" -> PEOPLE
        else -> STOCK
    }

    internal fun buildingColor(type: BuildingType): TextColor = TextColor.color(when (type) {
        BuildingType.TOWN_HALL -> 0xE5BA73
        BuildingType.HOUSE -> 0xF3A2C9
        BuildingType.BUILDER_HUT -> 0xFFB277
        BuildingType.FARM -> 0xB7D878
        BuildingType.FORESTER -> 0x86C79A
        BuildingType.QUARRY -> 0xC6DDFA
        BuildingType.WAREHOUSE -> 0x86DCF1
        BuildingType.SAWMILL -> 0xD7B486
        BuildingType.BLACKSMITH -> 0xEF9D93
        BuildingType.STONEMASON -> 0xA8BDD8
        BuildingType.LIBRARY -> 0xC4ABFF
        BuildingType.TAVERN -> 0xE9B58E
        BuildingType.BAKERY -> 0xE5CE8F
        BuildingType.GUARD_TOWER -> 0x95B4CC
        BuildingType.WALL, BuildingType.GATE -> 0xD6E4F2
        BuildingType.ROAD -> 0xBDAE98
        BuildingType.PLAN_BOARD -> 0xD9BE85
        BuildingType.UNIVERSITY -> 0xD6B7F2
        BuildingType.SMELTERY -> 0xF5A46B
        BuildingType.CRUSHER -> 0xA8BDD8
        BuildingType.DECORATION -> 0xA7D6C5
    })

    override fun close() {
        if (closed) return
        closed = true
        visits.clear()
        roots.clear()
        HandlerList.unregisterAll(this)
        equipment.close()
        runtime.close()
    }

    internal companion object {
        const val PAGE_SIZE = 8
        const val BUILDINGS_PAGE_SIZE = 16
        const val BUILDING_OVERVIEW_PAGE_SIZE = 8
        const val SKILLS_PAGE_SIZE = 6
        const val LOGISTICS_MATERIAL_PAGE_SIZE = 6
        const val CARPENTRY_PAGE_SIZE = 3
        const val MATERIALS_PAGE_SIZE = 16
        val NAME = PaperDialogInputId.of("city_name")
        val DELETE_NAME = PaperDialogInputId.of("delete_city_name")
        val MEMBER = PaperDialogInputId.of("member_name")
        val WHITE = TextColor.color(0xFFFFFF)
        val LAND = TextColor.color(0x9BD48D)
        val COMMIT = TextColor.color(0x9BD48D)
        val PERSONAL = TextColor.color(0xC4A7E7)
        val PEOPLE = TextColor.color(0xF3A2C9)
        val STOCK = TextColor.color(0xF4D87A)
        val NAVIGATION = TextColor.color(0x92BED8)
        val DANGER = TextColor.color(0xFF6B61)
    }
}
