package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Personal survey marks near a city's edge; Core owns client tracking and ordered transport. */
class CivsBoundaryRenderer internal constructor(
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val displays: PaperPacketDisplays,
    private val nativeWork: BudgetedTaskQueue? = null,
) : AutoCloseable, Listener {
    constructor(plugin: Plugin, tasks: LifecycleTaskScope, nativeWork: BudgetedTaskQueue? = null) :
        this(plugin, tasks, nativeWork?.let { PaperPacketDisplays(plugin, "civs-boundary", it) }
            ?: PaperPacketDisplays(plugin, "civs-boundary"), nativeWork)

    private data class Rendered(val handle: PacketDisplay, var geometry: CivsPreviewCuboid?)
    private class Scene(val player: Player, val cityId: UUID, val worldId: UUID, val epoch: LifecycleTaskScope.Token) {
        val handles = linkedMapOf<CivsBoundaryKey, Rendered>()
        var desired = emptyMap<CivsBoundaryKey, CivsPreviewCuboid>()
        var pending = false
    }

    private val work = nativeWork ?: BudgetedTaskQueue(tasks)
    private val scenes = mutableMapOf<UUID, Scene>()
    private val material = Material.CYAN_CONCRETE.createBlockData()
    private var closed = false
    private var terminal: CompletableFuture<Void>? = null

    init { plugin.server.pluginManager.registerEvents(this, plugin) }

    /** Called by the settlement pulse, with its current local membership snapshot. No world/block scans. */
    fun pulse(player: Player, city: Settlement?) {
        check(Bukkit.isPrimaryThread()) { "Civs boundary updates require the server thread" }
        if (closed) return
        if (city == null || !city.canManage(player.uniqueId) || !player.isOnline || player.world.uid != city.worldId) {
            clear(player.uniqueId)
            return
        }
        val location = player.location
        val desired = civsBoundaryGeometry(city, location.x, location.y, location.z).associate { it.key to it.cuboid }
        if (desired.isEmpty()) {
            clear(player.uniqueId)
            return
        }
        val previous = scenes[player.uniqueId]
        if (previous != null && (previous.cityId != city.id || previous.worldId != city.worldId)) clear(player.uniqueId)
        val scene = scenes.getOrPut(player.uniqueId) { Scene(player, city.id, city.worldId, tasks.token()) }
        if (scene.desired == desired) return
        scene.desired = desired
        if (scene.pending) return // An admitted step reads only the latest desired geometry.
        scene.pending = true
        work.submit({ current(scene) }) { reconcileOne(scene) }.whenComplete { _, failure ->
            scene.pending = false
            if (failure != null && !closed && scenes[player.uniqueId] === scene) {
                clear(player.uniqueId)
                if (failure !is CancellationException) plugin.logger.log(Level.WARNING,
                    "ArcCivs boundary rendering failed city=${scene.cityId} player=${player.uniqueId}", failure)
            }
        }
    }

    /** A fixed-size lookup followed by at most one native handle operation per shared budget step. */
    private fun reconcileOne(scene: Scene): Boolean {
        val removed = scene.handles.keys.firstOrNull { it !in scene.desired }
        if (removed != null) {
            scene.handles.remove(removed)!!.handle.remove()
            return false
        }
        val changed = scene.desired.entries.firstOrNull { scene.handles[it.key]?.geometry != it.value } ?: return true
        val cue = changed.value
        val location = Location(scene.player.world, cue.x, cue.y, cue.z)
        val rendered = scene.handles[changed.key] ?: Rendered(displays.spawnBlock(location, material), null).also {
            scene.handles[changed.key] = it // Keep ownership even when subsequent configuration fails.
            it.handle.isVisibleByDefault = false
            it.handle.showTo(scene.player)
            it.handle.viewRange = 1f
            it.handle.shadowRadius = 0f
            it.handle.shadowStrength = 0f
            it.handle.brightness = Display.Brightness(15, 15)
            it.handle.interpolationDuration = 0
            it.handle.teleportDuration = 0
        }
        if (rendered.geometry != null) rendered.handle.teleport(location)
        rendered.handle.transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(cue.sx, cue.sy, cue.sz), Quaternionf())
        rendered.handle.displayWidth = maxOf(cue.sx, cue.sz)
        rendered.handle.displayHeight = cue.sy
        rendered.geometry = cue
        return false
    }

    private fun current(scene: Scene): Boolean = !closed && scenes[scene.player.uniqueId] === scene &&
        tasks.isCurrent(scene.epoch) && scene.player.isOnline && scene.player.world.uid == scene.worldId

    fun clear(playerId: UUID) {
        val scene = scenes.remove(playerId) ?: return
        val retired = scene.handles.values.map { it.handle }.iterator()
        scene.handles.clear()
        scene.desired = emptyMap()
        work.submit({ !closed }) {
            if (retired.hasNext()) retired.next().remove()
            !retired.hasNext()
        }.whenComplete { _, failure ->
            if (failure != null && !closed && failure !is CancellationException) plugin.logger.log(Level.WARNING,
                "ArcCivs boundary cleanup remains owned by its display runtime player=$playerId", failure)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) = clear(event.player.uniqueId)

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) = clear(event.player.uniqueId)

    override fun close() {
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        scenes.clear()
        if (nativeWork == null) work.close()
        terminal = displays.closeAsync()
    }

    fun drained(): CompletableFuture<Void> = terminal?.copy() ?: CompletableFuture.completedFuture(null)
}

internal data class CivsBoundaryBounds(val minX: Long, val maxX: Long, val minZ: Long, val maxZ: Long)

/** The outer faces of inclusive city blocks, including negative coordinates and old large radii. */
internal fun civsBoundaryBounds(city: Settlement): CivsBoundaryBounds = CivsBoundaryBounds(
    city.center.x.toLong() - city.radius, city.center.x.toLong() + city.radius + 1,
    city.center.z.toLong() - city.radius, city.center.z.toLong() + city.radius + 1,
)

internal enum class CivsBoundarySide { NORTH, EAST, SOUTH, WEST }
internal data class CivsBoundaryKey(val side: CivsBoundarySide, val segment: Long, val post: Boolean)
internal data class CivsBoundaryPart(val key: CivsBoundaryKey, val cuboid: CivsPreviewCuboid)

/** At most seven eight-block segments per side; cost is independent of city area or radius. */
internal fun civsBoundaryGeometry(city: Settlement, viewerX: Double, viewerY: Double, viewerZ: Double): List<CivsBoundaryPart> {
    require(viewerX.isFinite() && viewerY.isFinite() && viewerZ.isFinite())
    val bounds = civsBoundaryBounds(city)
    val thickness = 0.08f
    val half = thickness / 2.0
    val y = floor(viewerY) + 0.12
    return buildList {
        val posts = mutableSetOf<Pair<Double, Double>>()
        fun edge(side: CivsBoundarySide, fixed: Long, start: Long, end: Long, horizontal: Boolean) {
            val across = if (horizontal) viewerZ else viewerX
            if (abs(across - fixed) > 24.0) return
            val along = if (horizontal) viewerX else viewerZ
            val count = ceil((end - start) / 8.0).toLong()
            val first = maxOf(0L, floor((along - 24.0 - start) / 8.0).toLong())
            val last = minOf(count - 1, floor((along + 24.0 - start) / 8.0).toLong())
            if (first > last) return
            fun post(index: Long, position: Double) {
                val x = if (horizontal) position else fixed.toDouble()
                val z = if (horizontal) fixed.toDouble() else position
                if (!posts.add(x to z)) return
                add(CivsBoundaryPart(CivsBoundaryKey(side, index, true), CivsPreviewCuboid(
                    x - half, y, z - half,
                    thickness, 2.3f, thickness, Material.CYAN_CONCRETE)))
            }
            for (index in first..last) {
                val from = start + index * 8.0
                val to = minOf(end.toDouble(), from + 8.0)
                add(CivsBoundaryPart(CivsBoundaryKey(side, index, false), CivsPreviewCuboid(
                    if (horizontal) from + half else fixed - half, y,
                    if (horizontal) fixed - half else from + half,
                    if (horizontal) (to - from - thickness).toFloat() else thickness, thickness,
                    if (horizontal) thickness else (to - from - thickness).toFloat(), Material.CYAN_CONCRETE)))
                post(index, from)
                if (index == last) post(index + 1, to)
            }
        }
        edge(CivsBoundarySide.NORTH, bounds.minZ, bounds.minX, bounds.maxX, true)
        edge(CivsBoundarySide.EAST, bounds.maxX, bounds.minZ, bounds.maxZ, false)
        edge(CivsBoundarySide.SOUTH, bounds.maxZ, bounds.minX, bounds.maxX, true)
        edge(CivsBoundarySide.WEST, bounds.minX, bounds.minZ, bounds.maxZ, false)
    }
}
