package ru.arc.civs

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
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
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Private territory marks, site fences and control labels; Core owns tracking and ordered transport. */
class CivsBoundaryRenderer internal constructor(
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val displays: PaperPacketDisplays,
    private val nativeWork: BudgetedTaskQueue? = null,
    private val text: CivsText? = null,
) : AutoCloseable, Listener {
    constructor(plugin: Plugin, tasks: LifecycleTaskScope, nativeWork: BudgetedTaskQueue? = null, text: CivsText = CivsText.load(plugin)) :
        this(plugin, tasks, nativeWork?.let { PaperPacketDisplays(plugin, "civs-boundary", it) }
            ?: PaperPacketDisplays(plugin, "civs-boundary"), nativeWork, text)

    private data class Rendered(val handle: PacketBlockDisplay, var geometry: CivsPreviewCuboid?)
    private data class Label(val handle: PacketTextDisplay, var value: CivsControlHologram?)
    private class Scene(val player: Player, val cityId: UUID, val worldId: UUID, val epoch: LifecycleTaskScope.Token) {
        val handles = linkedMapOf<CivsBoundaryKey, Rendered>()
        val labels = linkedMapOf<UUID, Label>()
        var desired = emptyMap<CivsBoundaryKey, CivsPreviewCuboid>()
        var desiredLabels = emptyMap<UUID, CivsControlHologram>()
        var pending = false
    }

    private val work = nativeWork ?: BudgetedTaskQueue(tasks)
    private val scenes = mutableMapOf<UUID, Scene>()
    private val materials = listOf(Material.CYAN_CONCRETE, Material.OAK_PLANKS, Material.SMOOTH_SANDSTONE,
        Material.RED_CONCRETE).associateWith { it.createBlockData() }
    private var closed = false
    private var terminal: CompletableFuture<Void>? = null

    init { plugin.server.pluginManager.registerEvents(this, plugin) }

    /** Called by the settlement pulse, with its current local membership snapshot. No world/block scans. */
    fun pulse(player: Player, city: Settlement?, placing: Boolean = false, planned: CivBox? = null,
              plannedBuildingId: UUID? = null, buildingStatuses: Map<UUID, String> = emptyMap()) {
        check(Bukkit.isPrimaryThread()) { "Civs boundary updates require the server thread" }
        if (closed) return
        if (city == null || !city.canManage(player.uniqueId) || !player.isOnline || player.world.uid != city.worldId) {
            clear(player.uniqueId)
            return
        }
        val location = player.location
        val desired = (civsBoundaryGeometry(city, location.x, location.y, location.z) +
            civsConstructionFenceGeometry(city, location.x, location.y, location.z) +
            if (placing) civsBuildingBoundaryGeometry(city, location.x, location.y, location.z, planned, plannedBuildingId)
            else emptyList()).associate { it.key to it.cuboid }
        val desiredLabels = text?.let { catalog ->
            civsControlHolograms(city, location.x, location.y, location.z) {
                catalog.buildingHologram(player, city, it, buildingStatuses[it.id])
            }
                .associateBy { it.buildingId }
        }.orEmpty()
        if (desired.isEmpty() && desiredLabels.isEmpty()) {
            clear(player.uniqueId)
            return
        }
        val previous = scenes[player.uniqueId]
        if (previous != null && (previous.cityId != city.id || previous.worldId != city.worldId)) clear(player.uniqueId)
        val scene = scenes.getOrPut(player.uniqueId) { Scene(player, city.id, city.worldId, tasks.token()) }
        if (scene.desired == desired && scene.desiredLabels == desiredLabels) return
        scene.desired = desired
        scene.desiredLabels = desiredLabels
        if (scene.pending) return // An admitted step reads only the latest desired geometry.
        scene.pending = true
        work.submit({ current(scene) }) { reconcileOne(scene) }.whenComplete { _, failure ->
            scene.pending = false
            if (failure != null && !closed && scenes[player.uniqueId] === scene) {
                clear(player.uniqueId)
                if (failure !is CancellationException) CivsDiagnostics.record(plugin.logger, Level.WARNING, scene.cityId,
                    "ArcCivs boundary rendering failed city=${scene.cityId} player=${player.uniqueId}", failure)
            }
        }
    }

    /** A fixed-size lookup followed by at most one native handle operation per shared budget step. */
    private fun reconcileOne(scene: Scene): Boolean {
        val removedLabel = scene.labels.keys.firstOrNull { it !in scene.desiredLabels }
        if (removedLabel != null) {
            scene.labels.remove(removedLabel)!!.handle.remove()
            return false
        }
        val removed = scene.handles.keys.firstOrNull { it !in scene.desired }
        if (removed != null) {
            scene.handles.remove(removed)!!.handle.remove()
            return false
        }
        val label = scene.desiredLabels.entries.firstOrNull { scene.labels[it.key]?.value != it.value }
        if (label != null) {
            val cue = label.value
            val location = Location(scene.player.world, cue.x, cue.y, cue.z)
            val rendered = scene.labels[label.key] ?: Label(displays.spawnText(location, cue.text), null).also {
                scene.labels[label.key] = it
                it.handle.isVisibleByDefault = false
                it.handle.billboard = Display.Billboard.CENTER
                it.handle.alignment = TextDisplay.TextAlignment.CENTER
                it.handle.lineWidth = CIVS_CONTROL_TEXT_WIDTH
                it.handle.backgroundColor = Color.fromARGB(150, 15, 23, 30)
                it.handle.isShadowed = true
                it.handle.isSeeThrough = false
                it.handle.textOpacity = 0xff.toByte()
                it.handle.viewRange = 1f
                it.handle.shadowRadius = 0f
                it.handle.shadowStrength = 0f
                it.handle.brightness = Display.Brightness(15, 15)
                it.handle.interpolationDuration = 0
                it.handle.teleportDuration = 0
                it.handle.displayWidth = 4f
                it.handle.displayHeight = 1f
                it.handle.transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(CIVS_CONTROL_TEXT_SCALE), Quaternionf())
                it.handle.showTo(scene.player)
            }
            rendered.value?.let { previous ->
                if (previous.x != cue.x || previous.y != cue.y || previous.z != cue.z) rendered.handle.teleport(location)
                if (previous.text != cue.text) rendered.handle.text(cue.text)
            }
            rendered.value = cue
            return false
        }
        val changed = scene.desired.entries.firstOrNull { scene.handles[it.key]?.geometry != it.value } ?: return true
        val cue = changed.value
        val location = Location(scene.player.world, cue.x, cue.y, cue.z)
        val rendered = scene.handles[changed.key] ?: Rendered(displays.spawnBlock(location, materials.getValue(cue.material)), null).also {
            scene.handles[changed.key] = it // Keep ownership even when subsequent configuration fails.
            it.handle.isVisibleByDefault = false
            it.handle.showTo(scene.player)
            it.handle.viewRange = 1f
            it.handle.shadowRadius = 0f
            it.handle.shadowStrength = 0f
            if (cue.material != Material.OAK_PLANKS) it.handle.brightness = Display.Brightness(15, 15)
            it.handle.interpolationDuration = 0
            it.handle.teleportDuration = 0
        }
        rendered.geometry?.let { previous ->
            if (previous.material != cue.material) rendered.handle.blockData = materials.getValue(cue.material)
            if (previous.x != cue.x || previous.y != cue.y || previous.z != cue.z) rendered.handle.teleport(location)
        }
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
        val retired = (scene.handles.values.map { it.handle } + scene.labels.values.map { it.handle }).iterator()
        scene.handles.clear()
        scene.labels.clear()
        scene.desired = emptyMap()
        scene.desiredLabels = emptyMap()
        work.submit({ !closed }) {
            if (retired.hasNext()) retired.next().remove()
            !retired.hasNext()
        }.whenComplete { _, failure ->
            if (failure != null && !closed && failure !is CancellationException) CivsDiagnostics.record(plugin.logger, Level.WARNING, scene.cityId,
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

internal const val CIVS_CONTROL_TEXT_SCALE = 0.60f
internal const val CIVS_CONTROL_TEXT_WIDTH = 180
internal const val CIVS_CONTROL_TEXT_OFFSET = 1.40
internal data class CivsControlHologram(val buildingId: UUID, val x: Double, val y: Double, val z: Double, val text: Component)

/** Only saved controls qualify: no guessed anchor, planned upgrade marker, world scan or block lookup. */
internal fun civsControlHolograms(city: Settlement, viewerX: Double, viewerY: Double, viewerZ: Double,
                                label: (CivBuilding) -> Component): List<CivsControlHologram> {
    require(viewerX.isFinite() && viewerY.isFinite() && viewerZ.isFinite())
    return city.buildings.mapNotNull { building -> building.control?.let { control ->
        val dx = control.x + 0.5 - viewerX
        val dy = control.y + 0.5 - viewerY
        val dz = control.z + 0.5 - viewerZ
        (building to (dx * dx + dy * dy + dz * dz)).takeIf { it.second <= 64.0 * 64.0 }
    } }.sortedWith(compareBy<Pair<CivBuilding, Double>> { it.second }.thenBy { it.first.id }).take(4).map { (building, _) ->
        val control = requireNotNull(building.control)
        CivsControlHologram(building.id, control.x + 0.5, control.y + CIVS_CONTROL_TEXT_OFFSET, control.z + 0.5, label(building))
    }
}

internal data class CivsBoundaryBounds(val minX: Long, val maxX: Long, val minZ: Long, val maxZ: Long)

/** The outer faces of inclusive city blocks, including negative coordinates and old large radii. */
internal fun civsBoundaryBounds(city: Settlement): CivsBoundaryBounds = CivsBoundaryBounds(
    city.center.x.toLong() - city.radius, city.center.x.toLong() + city.radius + 1,
    city.center.z.toLong() - city.radius, city.center.z.toLong() + city.radius + 1,
)

internal enum class CivsBoundarySide { NORTH, EAST, SOUTH, WEST }
internal data class CivsBoundaryKey(
    val side: CivsBoundarySide,
    val segment: Long,
    val post: Boolean,
    val buildingId: UUID? = null,
    val layer: Int = 0,
)
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

internal const val CIVS_BUILDING_BOUNDARY_PART_LIMIT = 128

/**
 * Nearby saved footprints become visible as soon as placement starts, before a candidate exists.
 * Inclusive box overlap uses the same predicate as placement validation. An upgrade never conflicts
 * with its own building. Conflicts take priority within the fixed display budget; no world reads.
 */
internal fun civsBuildingBoundaryGeometry(city: Settlement, viewerX: Double, viewerY: Double, viewerZ: Double,
                                         planned: CivBox? = null, plannedBuildingId: UUID? = null): List<CivsBoundaryPart> {
    require(viewerX.isFinite() && viewerY.isFinite() && viewerZ.isFinite())
    val buildings = city.buildings.associateByTo(linkedMapOf()) { it.id }
    city.construction?.plannedBuilding?.let { buildings[it.id] = it }
    fun conflict(building: CivBuilding) = building.id != plannedBuildingId && planned?.overlaps(building.bounds) == true
    fun distance(building: CivBuilding): Double {
        val box = building.bounds
        val dx = maxOf(box.min.x - viewerX, 0.0, viewerX - (box.max.x.toLong() + 1))
        val dz = maxOf(box.min.z - viewerZ, 0.0, viewerZ - (box.max.z.toLong() + 1))
        return dx * dx + dz * dz
    }
    return buildList {
        for (building in buildings.values.sortedWith(compareByDescending<CivBuilding>(::conflict)
            .thenBy(::distance).thenBy { it.id })) {
            if (size >= CIVS_BUILDING_BOUNDARY_PART_LIMIT) break
            if (distance(building) > 32.0 * 32.0 || abs(viewerY - building.entrance.y) > 16.0) continue
            val box = building.bounds
            val minX = box.min.x.toLong()
            val maxX = box.max.x.toLong() + 1
            val minZ = box.min.z.toLong()
            val maxZ = box.max.z.toLong() + 1
            val y = building.entrance.y + 0.10
            val material = if (conflict(building)) Material.RED_CONCRETE else Material.SMOOTH_SANDSTONE
            val thickness = 0.08f
            val half = thickness / 2.0
            val corners = mutableSetOf<Pair<Long, Long>>()
            fun addPart(key: CivsBoundaryKey, x: Double, z: Double, sx: Float, sy: Float, sz: Float) {
                if (size < CIVS_BUILDING_BOUNDARY_PART_LIMIT && sx > 0 && sz > 0)
                    add(CivsBoundaryPart(key, CivsPreviewCuboid(x, y, z, sx, sy, sz, material)))
            }
            fun edge(side: CivsBoundarySide, fixed: Long, start: Long, end: Long, horizontal: Boolean) {
                val across = if (horizontal) viewerZ else viewerX
                if (abs(across - fixed) > 32.0) return
                val along = if (horizontal) viewerX else viewerZ
                val count = ceil((end - start) / 8.0).toLong()
                val first = maxOf(0L, floor((along - 32.0 - start) / 8.0).toLong())
                val last = minOf(count - 1, floor((along + 32.0 - start) / 8.0).toLong())
                if (first > last) return
                for (index in first..last) {
                    val from = start + index * 8.0
                    val to = minOf(end.toDouble(), from + 8.0)
                    val insetStart = if (index == 0L) half else 0.0
                    val insetEnd = if (index == count - 1) half else 0.0
                    addPart(CivsBoundaryKey(side, index, false, building.id, 10),
                        if (horizontal) from + insetStart else fixed - half,
                        if (horizontal) fixed - half else from + insetStart,
                        if (horizontal) (to - from - insetStart - insetEnd).toFloat() else thickness,
                        thickness, if (horizontal) thickness else (to - from - insetStart - insetEnd).toFloat())
                }
                // Only real corners rise above the base; no repeating fence around completed houses.
                for ((index, position) in listOf(0L to start, count to end)) {
                    if (abs(along - position) > 32.0) continue
                    val x = if (horizontal) position else fixed
                    val z = if (horizontal) fixed else position
                    if (corners.add(x to z)) addPart(CivsBoundaryKey(side, index, true, building.id, 11),
                        x - half, z - half, thickness, 0.80f, thickness)
                }
            }
            edge(CivsBoundarySide.NORTH, minZ, minX, maxX, true)
            edge(CivsBoundarySide.EAST, maxX, minZ, maxZ, false)
            edge(CivsBoundarySide.SOUTH, maxZ, minX, maxX, true)
            edge(CivsBoundarySide.WEST, minX, minZ, maxZ, false)
        }
    }
}

internal const val CIVS_FENCE_PART_LIMIT = 192

/**
 * A visual-only two-rail fence, grounded at the authored entrance rather than a buried foundation.
 * The existing city pulse samples at most 16 building boxes and short nearby edge windows. No chunks
 * or blocks are read. Stable keys retain parts while a builder waits; completed sites disappear.
 */
internal fun civsConstructionFenceGeometry(city: Settlement, viewerX: Double, viewerY: Double, viewerZ: Double): List<CivsBoundaryPart> {
    require(viewerX.isFinite() && viewerY.isFinite() && viewerZ.isFinite())
    val planned = city.construction?.plannedBuilding
    val sites = city.buildings.filter { it.phase != BuildingPhase.ACTIVE || it.id == city.construction?.buildingId }
        .associateByTo(linkedMapOf()) { it.id }
    if (planned != null) sites[planned.id] = planned
    fun distance(building: CivBuilding): Double {
        val box = building.bounds
        val dx = maxOf(box.min.x - viewerX, 0.0, viewerX - (box.max.x.toLong() + 1))
        val dz = maxOf(box.min.z - viewerZ, 0.0, viewerZ - (box.max.z.toLong() + 1))
        return dx * dx + dz * dz
    }
    return buildList {
        for (building in sites.values.sortedWith(compareBy<CivBuilding>(::distance).thenBy { it.id })) {
            if (size >= CIVS_FENCE_PART_LIMIT) break
            if (distance(building) > 32.0 * 32.0 || abs(viewerY - building.entrance.y) > 16) continue
            val bounds = building.bounds
            val minX = bounds.min.x.toDouble()
            val maxX = bounds.max.x.toLong() + 1.0
            val minZ = bounds.min.z.toDouble()
            val maxZ = bounds.max.z.toLong() + 1.0
            val y = building.entrance.y.toDouble()
            val entranceX = building.entrance.x + 0.5
            val entranceZ = building.entrance.z + 0.5
            data class Edge(val side: CivsBoundarySide, val fixed: Double, val start: Double, val end: Double, val horizontal: Boolean)
            val edges = listOf(
                Edge(CivsBoundarySide.NORTH, minZ, minX, maxX, true),
                Edge(CivsBoundarySide.EAST, maxX, minZ, maxZ, false),
                Edge(CivsBoundarySide.SOUTH, maxZ, minX, maxX, true),
                Edge(CivsBoundarySide.WEST, minX, minZ, maxZ, false),
            )
            val gate = edges.minBy { abs((if (it.horizontal) entranceZ else entranceX) - it.fixed) }.side
            val posts = mutableSetOf<Pair<Double, Double>>()
            fun part(key: CivsBoundaryKey, x: Double, bottom: Double, z: Double, sx: Float, sy: Float, sz: Float) {
                if (size < CIVS_FENCE_PART_LIMIT && sx > 0 && sz > 0) {
                    add(CivsBoundaryPart(key, CivsPreviewCuboid(x, bottom, z, sx, sy, sz, Material.OAK_PLANKS)))
                }
            }
            for (edge in edges.sortedBy { abs((if (it.horizontal) viewerZ else viewerX) - it.fixed) }) {
                val across = if (edge.horizontal) viewerZ else viewerX
                if (abs(across - edge.fixed) > 32.0) continue
                val along = if (edge.horizontal) viewerX else viewerZ
                val count = ceil((edge.end - edge.start) / 4.0).toLong()
                val first = maxOf(0L, floor((along - 32.0 - edge.start) / 4.0).toLong())
                val last = minOf(count - 1, floor((along + 32.0 - edge.start) / 4.0).toLong())
                if (first > last) continue
                val gateCenter = (if (edge.horizontal) entranceX else entranceZ).coerceIn(edge.start, edge.end)
                val gateFrom = maxOf(edge.start, gateCenter - 1.0)
                val gateTo = minOf(edge.end, gateCenter + 1.0)
                fun post(index: Long, position: Double) {
                    if (edge.side == gate && position > gateFrom && position < gateTo) return
                    val x = if (edge.horizontal) position else edge.fixed
                    val z = if (edge.horizontal) edge.fixed else position
                    if (!posts.add(x to z)) return
                    part(CivsBoundaryKey(edge.side, index, true, building.id), x - 0.125, y, z - 0.125, 0.25f, 1.25f, 0.25f)
                }
                for (index in first..last) {
                    if (size >= CIVS_FENCE_PART_LIMIT) break
                    val from = edge.start + index * 4.0
                    val to = minOf(edge.end, from + 4.0)
                    val spans = if (edge.side == gate && from < gateTo && to > gateFrom)
                        listOf(from to minOf(to, gateFrom), maxOf(from, gateTo) to to) else listOf(from to to)
                    spans.forEachIndexed { piece, (start, end) ->
                        if (end - start <= 0.25) return@forEachIndexed
                        for (rail in 0..1) part(CivsBoundaryKey(edge.side, index, false, building.id, rail * 2 + piece),
                            if (edge.horizontal) start + 0.125 else edge.fixed - 0.0625, y + 0.375 + rail * 0.5625,
                            if (edge.horizontal) edge.fixed - 0.0625 else start + 0.125,
                            if (edge.horizontal) (end - start - 0.25).toFloat() else 0.125f, 0.1875f,
                            if (edge.horizontal) 0.125f else (end - start - 0.25).toFloat())
                    }
                    post(index, from)
                    if (index == last) post(index + 1, to)
                }
                if (edge.side == gate) {
                    if (abs(along - gateFrom) <= 32.0) post(count + 1, gateFrom)
                    if (abs(along - gateTo) <= 32.0) post(count + 2, gateTo)
                }
            }
        }
    }
}
