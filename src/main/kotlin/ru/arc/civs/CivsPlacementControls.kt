package ru.arc.civs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.paper.actionpanel.*
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal enum class CivsPlacementAction {
    CONFIRM, MOVE, ROTATE_LEFT, ROTATE_RIGHT, DETAILS, CANCEL,
    LEFT, RIGHT, UP, DOWN, TOWARD, AWAY, DONE;

    val key: String get() = "panel." + name.lowercase().replace('_', '-')
}

internal data class CivsPlacementContext(val previewId: UUID, val moving: Boolean, val pending: UUID?)

/** Civs owns placement state and authority; Core owns the actual world panel and its inputs. */
internal class CivsPlacementControls(
    private val plugin: Plugin,
    private val host: CivsUiHost,
    private val ui: CivsUi,
    private val tasks: LifecycleTaskScope,
    private val text: CivsText,
    private val displays: PaperPacketDisplays = PaperPacketDisplays(plugin, "civs-placement-controls"),
    panelRuntime: PaperActionPanels<CivsPlacementContext, CivsPlacementAction>? = null,
) : ActionPanelController<CivsPlacementContext, CivsPlacementAction>(), Listener, AutoCloseable {
    private class Session(var previewId: UUID, val worldId: UUID) {
        var moving = false
        var pending: UUID? = null
        var confirming = false
        var target: CivPosition? = null
        val marker = mutableListOf<PacketBlockDisplay>()
    }

    private val sessions = mutableMapOf<UUID, Session>()
    private val panels = panelRuntime ?: PaperActionPanels(plugin, displays, this)
    private var closed = false

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        tasks.runTimer(1, 1) {
            if (!closed) {
                panels.tick()
                sessions.toMap().forEach { (id, session) ->
                    val player = plugin.server.getPlayer(id)
                    if (player == null || !player.isOnline || player.world.uid != session.worldId ||
                        eligible(player) == null && session.pending == null) clear(id)
                    else refreshTarget(player, session)
                }
            }
        }
    }

    private fun eligible(player: Player): CivsPreviewView? {
        if (closed || !player.isOnline || !host.ready() || !player.hasPermission("arccivs.use")) return null
        val preview = host.preview(player) ?: return null
        if (preview.worldId != player.world.uid) return null
        val location = player.location
        val p = preview.entrance
        if (location.toVector().distanceSquared(org.bukkit.util.Vector(p.x.toDouble(), p.y.toDouble(), p.z.toDouble())) > 96.0 * 96.0) return null
        return preview
    }

    override fun frame(player: Player): ActionPanelFrame<CivsPlacementContext, CivsPlacementAction>? {
        val preview = eligible(player) ?: return null
        val session = sessions.getOrPut(player.uniqueId) { Session(preview.id, preview.worldId) }
        if (ui.showingDialog(player)) return null
        if (session.previewId != preview.id && session.pending == null) session.previewId = preview.id
        val busy = preview.busy || session.pending != null
        val status = when {
            session.confirming -> text.render(player, "panel.saving")
            busy -> text.render(player, "panel.busy")
            session.moving -> text.render(player, "panel.moving")
            else -> text.render(player, "panel.status", "building" to text.building(player, preview.type), "rotation" to preview.rotation)
        }
        val actions = if (busy) emptyList() else actions(preview, session.moving)
        val box = preview.bounds
        return ActionPanelFrame(
            CivsPlacementContext(preview.id, session.moving, session.pending),
            ActionPanelPlacement.FollowTarget(preview.worldId,
                ActionPanelPoint((box.min.x.toDouble() + box.max.x + 1) / 2,
                    preview.entrance.y.toDouble(), (box.min.z.toDouble() + box.max.z + 1) / 2),
                followingSettings(session.moving)),
            layout(player, actions, status, session.moving), !busy,
            ActionPanelOptions(ActionPanelInputProfile.ENTITY_BOTH, 4.0, false), null,
        )
    }

    internal fun layout(player: Player, actions: List<CivsPlacementAction>, status: Component,
        moving: Boolean): ActionPanelLayout<CivsPlacementAction> {
        val buttons = actions.map { action ->
            ActionPanelAction(action, text.render(player, action.key).color(when (action) {
                CivsPlacementAction.CONFIRM, CivsPlacementAction.DONE -> TextColor.color(0x9BD48D)
                CivsPlacementAction.CANCEL -> TextColor.color(0xFF6B61)
                CivsPlacementAction.ROTATE_LEFT, CivsPlacementAction.ROTATE_RIGHT -> TextColor.color(0xC4A7E7)
                CivsPlacementAction.DETAILS -> TextColor.color(0x92BED8)
                else -> TextColor.color(0xFFFFFF)
            }))
        }
        if (!moving) return ActionPanelLayouts.twoColumn(buttons, status)
        // Eight controls need lateral clearance near terrain, without pushing the group above view.
        val grid = ActionPanelGridSettings(0.26, 1.45, 1.4f, 0.23f, 0.75f)
        val layout = ActionPanelLayouts.twoColumn(buttons, status, Component.empty(), 0, grid)
        val width = grid.columnSpacing + grid.buttonWidth
        return ActionPanelLayout(layout.buttons, layout.labels.map { label ->
            ActionPanelLabel(label.key, ActionPanelRect(label.rect.center, minOf(label.rect.width, width), label.rect.height),
                label.text, label.style, label.visible)
        })
    }

    override fun activate(player: Player, context: CivsPlacementContext, action: CivsPlacementAction) {
        val fresh = frame(player) ?: return
        if (fresh.context != context || !fresh.inputEnabled || fresh.layout.buttons.none { it.action == action && it.enabled }) return
        val session = sessions[player.uniqueId] ?: return
        when (action) {
            CivsPlacementAction.MOVE -> beginMove(player, session)
            CivsPlacementAction.DONE -> { session.moving = false; clearTarget(session) }
            CivsPlacementAction.DETAILS -> { clearTarget(session); ui.open(player) }
            CivsPlacementAction.CANCEL -> {
                host.cancelPreview(player)
                clear(player.uniqueId)
                player.sendMessage(text.render(player, "panel.cancelled"))
            }
            CivsPlacementAction.CONFIRM -> perform(player, session, confirming = true) { host.confirmPreview(player) }
            CivsPlacementAction.ROTATE_LEFT -> perform(player, session) { host.rotatePreview(player, -90) }
            CivsPlacementAction.ROTATE_RIGHT -> perform(player, session) { host.rotatePreview(player, 90) }
            else -> perform(player, session) { host.nudgePreview(player, CivsPreviewMove.valueOf(action.name)) }
        }
    }

    private fun beginMove(player: Player, session: Session) {
        session.moving = true
        // A new mode starts at its authored distance instead of inheriting the old distance hysteresis.
        panels.clear(player.uniqueId)
        player.sendMessage(text.render(player, "panel.move-opened"))
    }

    private fun perform(player: Player, session: Session, confirming: Boolean = false, finishMove: Boolean = false,
        action: () -> CompletableFuture<Unit>) {
        if (session.pending != null) return
        val request = UUID.randomUUID()
        session.pending = request
        session.confirming = confirming
        clearTarget(session)
        val future = try { action() } catch (failure: Exception) { CompletableFuture.failedFuture(failure) }
        future.whenCompleteSync(tasks) { _, failure ->
            if (closed || !player.isOnline || sessions[player.uniqueId] !== session || session.pending != request) return@whenCompleteSync
            session.pending = null
            session.confirming = false
            if (failure != null) player.sendMessage(text.failure(player, failure))
            else if (confirming) player.sendMessage(text.render(player, "panel.confirmed"))
            else if (finishMove) session.moving = false
            val preview = eligible(player)
            if (preview == null) clear(player.uniqueId) else session.previewId = preview.id
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun interact(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || event.action !in setOf(Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK,
                Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK)) return
        val player = event.player
        if (panels.intercept(player)) { event.isCancelled = true; return }
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val preview = eligible(player) ?: return
        if (!preview.movable || preview.busy || ui.showingDialog(player)) return
        val session = sessions.getOrPut(player.uniqueId) { Session(preview.id, preview.worldId) }
        if (session.pending != null) return
        if (session.moving) {
            event.isCancelled = true
            perform(player, session, finishMove = true) { host.movePreview(player) }
        } else if (aimsAtAnchor(player, preview.anchor)) {
            event.isCancelled = true
            beginMove(player, session)
        }
    }

    private fun aimsAtAnchor(player: Player, point: CivPosition): Boolean {
        val eye = player.eyeLocation
        val hit = BoundingBox(point.x - 0.04, point.y - 0.04, point.z - 0.04,
            point.x + 1.04, point.y + 1.04, point.z + 1.04).rayTrace(eye.toVector(), eye.direction, 12.0) ?: return false
        val ground = player.world.rayTraceBlocks(eye, eye.direction, 12.0, FluidCollisionMode.NEVER, true)
        return ground == null || hit.hitPosition.distanceSquared(eye.toVector()) <= ground.hitPosition.distanceSquared(eye.toVector()) + 0.01
    }

    private fun refreshTarget(player: Player, session: Session) {
        if (!session.moving || session.pending != null || ui.showingDialog(player)) { clearTarget(session); return }
        val block = player.rayTraceBlocks(12.0, FluidCollisionMode.NEVER)?.hitBlock
        val target = block?.takeIf { it.type.isSolid && !it.isLiquid }?.let { CivPosition(it.x, it.y, it.z) }
        if (target == null) { clearTarget(session); return }
        if (target == session.target) return
        val cues = civsAnchorCues(target, Material.LIGHT_BLUE_CONCRETE)
        if (session.marker.isEmpty()) cues.forEach { cue ->
            val handle = displays.spawnBlock(Location(player.world, cue.x, cue.y, cue.z), cue.material.createBlockData())
            session.marker += handle
            handle.isVisibleByDefault = false
            handle.showTo(player)
            handle.brightness = Display.Brightness(15, 15)
            handle.shadowRadius = 0f
            handle.shadowStrength = 0f
            handle.displayWidth = maxOf(cue.sx, cue.sz)
            handle.displayHeight = cue.sy
            handle.interpolationDuration = 0
            handle.teleportDuration = 0
            handle.transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(cue.sx, cue.sy, cue.sz), Quaternionf())
        } else cues.zip(session.marker).forEach { (cue, handle) ->
            handle.teleport(Location(player.world, cue.x, cue.y, cue.z))
        }
        session.target = target
        player.sendActionBar(text.render(player, "panel.pick-hint"))
    }

    private fun clearTarget(session: Session) {
        session.marker.forEach { it.remove() }
        session.marker.clear()
        session.target = null
    }

    private fun clear(id: UUID) {
        sessions.remove(id)?.let(::clearTarget)
        panels.clear(id)
    }

    @EventHandler fun quit(event: PlayerQuitEvent) = clear(event.player.uniqueId)
    @EventHandler fun worldChanged(event: PlayerChangedWorldEvent) = clear(event.player.uniqueId)

    override fun close() {
        if (closed) return
        closed = true
        sessions.keys.toList().forEach(::clear)
        panels.close()
        displays.close()
        HandlerList.unregisterAll(this)
    }

    companion object {
        /** Keep the terrain-pick corridor below the move controls; other Civs modes use Core defaults. */
        internal fun followingSettings(moving: Boolean): ActionPanelFollowSettings = if (moving)
            ActionPanelFollowSettings(3.0, -0.5, 0.2, 1.25, 0.03) else ActionPanelFollowSettings()

        internal fun actions(preview: CivsPreviewView, moving: Boolean): List<CivsPlacementAction> = when {
            moving && preview.movable -> listOf(CivsPlacementAction.LEFT, CivsPlacementAction.RIGHT,
                CivsPlacementAction.UP, CivsPlacementAction.DOWN, CivsPlacementAction.TOWARD, CivsPlacementAction.AWAY,
                CivsPlacementAction.DONE, CivsPlacementAction.CANCEL)
            preview.movable -> listOf(CivsPlacementAction.CONFIRM, CivsPlacementAction.MOVE,
                CivsPlacementAction.ROTATE_LEFT, CivsPlacementAction.ROTATE_RIGHT, CivsPlacementAction.DETAILS, CivsPlacementAction.CANCEL)
            else -> listOf(CivsPlacementAction.CONFIRM, CivsPlacementAction.DETAILS, CivsPlacementAction.CANCEL)
        }
    }
}
