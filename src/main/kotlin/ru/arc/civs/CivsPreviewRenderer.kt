package ru.arc.civs

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData
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
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.ScheduledTask
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.ArrayDeque
import java.util.UUID
import java.util.logging.Level

/** Private, bounded previews. Core owns packet admission, chunks and ordered client cleanup. */
class CivsPreviewRenderer internal constructor(
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val displays: PaperPacketDisplays,
) : AutoCloseable, Listener {
    constructor(plugin: Plugin, tasks: LifecycleTaskScope) :
        this(plugin, tasks, PaperPacketDisplays(plugin, "civs-preview"))

    private class Scene(
        val player: Player,
        val worldId: UUID,
        val epoch: LifecycleTaskScope.Token,
        val blocks: List<ArcSchematicBlock>,
        val cues: List<CivsPreviewCuboid>,
    ) {
        val handles = mutableListOf<PacketDisplay>()
        val blockData = mutableMapOf<String, BlockData>()
        var cursor = 0
        val size: Int get() = cues.size + blocks.size
    }

    private val scenes = mutableMapOf<UUID, Scene>()
    private val pending = ArrayDeque<Scene>()
    private var batch: ScheduledTask? = null
    private var closed = false

    init { plugin.server.pluginManager.registerEvents(this, plugin) }

    fun show(player: Player, building: CivBuilding, blocks: List<ArcSchematicBlock>) {
        check(!closed) { "Civs preview renderer is closed" }
        if (blocks.size > MAX_SURFACE_BLOCKS) throw CivsRejection("preview-too-large")
        if (!player.isOnline) return
        clear(player.uniqueId)
        val scene = Scene(player, player.world.uid, tasks.token(), blocks.toList(), civsPreviewCues(building))
        scenes[player.uniqueId] = scene
        pending.addLast(scene)
        if (batch?.isCancelled != false) batch = tasks.runTimer(scene.epoch, 1, 1, ::spawnBatch)
    }

    /** One global per-renderer budget, shared fairly between all pending player previews. */
    private fun spawnBatch() {
        var remaining = SPAWN_BUDGET
        while (remaining > 0 && pending.isNotEmpty()) {
            val scene = pending.removeFirst()
            if (!current(scene)) {
                clear(scene.player.uniqueId)
                continue
            }
            try {
                val count = minOf(16, remaining, scene.size - scene.cursor)
                repeat(count) { spawn(scene, scene.cursor++) }
                remaining -= count
                if (scene.cursor < scene.size) pending.addLast(scene)
            } catch (failure: Exception) {
                clear(scene.player.uniqueId)
                plugin.logger.log(Level.WARNING, "ArcCivs preview rendering failed for ${scene.player.uniqueId}", failure)
            }
        }
        if (pending.isEmpty()) {
            batch?.cancel()
            batch = null
        }
    }

    private fun spawn(scene: Scene, index: Int) {
        val cue = scene.cues.getOrNull(index)
        val block = if (cue == null) scene.blocks[index - scene.cues.size] else null
        val data = if (cue != null) cue.material.createBlockData() else scene.blockData.getOrPut(block!!.blockData) {
            plugin.server.createBlockData(block.blockData)
        }
        val location = if (cue != null) Location(scene.player.world, cue.x, cue.y, cue.z)
        else block!!.position.let { Location(scene.player.world, it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }
        val display = displays.spawnBlock(location, data)
        // Register before further configuration so an exception cannot leak a handle.
        scene.handles += display
        display.isVisibleByDefault = false
        display.showTo(scene.player)
        display.viewRange = 2f
        display.shadowRadius = 0f
        display.shadowStrength = 0f
        display.interpolationDuration = 0
        display.teleportDuration = 0
        if (cue != null) {
            display.brightness = Display.Brightness(15, 15)
            display.transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(cue.sx, cue.sy, cue.sz), Quaternionf())
            display.displayWidth = maxOf(cue.sx, cue.sz)
            display.displayHeight = cue.sy
        } else {
            display.displayWidth = 1f
            display.displayHeight = 1f
        }
    }

    private fun current(scene: Scene): Boolean = !closed && scenes[scene.player.uniqueId] === scene &&
        tasks.isCurrent(scene.epoch) && scene.player.isOnline && scene.player.world.uid == scene.worldId

    fun clear(playerId: UUID) {
        val scene = scenes.remove(playerId) ?: return
        pending.remove(scene)
        scene.handles.forEach(PacketDisplay::remove)
        scene.handles.clear()
        scene.blockData.clear()
        if (pending.isEmpty()) {
            batch?.cancel()
            batch = null
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) = clear(event.player.uniqueId)

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) = clear(event.player.uniqueId)

    override fun close() {
        if (closed) return
        closed = true
        batch?.cancel()
        batch = null
        scenes.keys.toList().forEach(::clear)
        pending.clear()
        HandlerList.unregisterAll(this)
        displays.close()
    }

    private companion object {
        const val MAX_SURFACE_BLOCKS = 6_000
        const val SPAWN_BUDGET = 128
    }
}

internal data class CivsPreviewCuboid(
    val x: Double, val y: Double, val z: Double,
    val sx: Float, val sy: Float, val sz: Float,
    val material: Material,
)

/** Amber building bounds, an unmistakable green anchor block and a cyan walkable entrance. */
internal fun civsPreviewCues(building: CivBuilding): List<CivsPreviewCuboid> = buildList {
    val thickness = 0.035f
    val half = thickness / 2.0
    val box = building.bounds
    val x0 = box.min.x.toDouble() - half
    val y0 = box.min.y.toDouble() - half
    val z0 = box.min.z.toDouble() - half
    val x1 = box.max.x.toDouble() + 1.0 - half
    val y1 = box.max.y.toDouble() + 1.0 - half
    val z1 = box.max.z.toDouble() + 1.0 - half
    val sx = (x1 - x0 + thickness).toFloat()
    val sy = (y1 - y0 + thickness).toFloat()
    val sz = (z1 - z0 + thickness).toFloat()
    for (y in listOf(y0, y1)) for (z in listOf(z0, z1)) add(CivsPreviewCuboid(x0, y, z, sx, thickness, thickness, Material.ORANGE_CONCRETE))
    for (x in listOf(x0, x1)) for (z in listOf(z0, z1)) add(CivsPreviewCuboid(x, y0, z, thickness, sy, thickness, Material.ORANGE_CONCRETE))
    for (x in listOf(x0, x1)) for (y in listOf(y0, y1)) add(CivsPreviewCuboid(x, y, z0, thickness, thickness, sz, Material.ORANGE_CONCRETE))
    val entry = building.entrance
    // Keep the walkable entrance inside the support frame; coincident rings hide the green handle.
    val ex = entry.x + 0.16
    val ey = entry.y + 0.025
    val ez = entry.z + 0.16
    val edge = 0.045f
    for (z in listOf(ez, ez + 0.635)) add(CivsPreviewCuboid(ex, ey, z, 0.68f, 0.035f, edge, Material.CYAN_CONCRETE))
    for (x in listOf(ex, ex + 0.635)) add(CivsPreviewCuboid(x, ey, ez, edge, 0.035f, 0.68f, Material.CYAN_CONCRETE))
    addAll(civsAnchorCues(building.anchor, Material.LIME_CONCRETE))
}

/** Same twelve-edge, 0.08-block frame as Builder's movable origin marker. */
internal fun civsAnchorCues(point: CivPosition, material: Material): List<CivsPreviewCuboid> = buildList(12) {
    val thickness = 0.08f
    val half = thickness / 2.0
    val x = point.x.toDouble()
    val y = point.y.toDouble()
    val z = point.z.toDouble()
    for (yy in listOf(y, y + 1)) for (zz in listOf(z, z + 1))
        add(CivsPreviewCuboid(x, yy - half, zz - half, 1f, thickness, thickness, material))
    for (xx in listOf(x, x + 1)) for (zz in listOf(z, z + 1))
        add(CivsPreviewCuboid(xx - half, y, zz - half, thickness, 1f, thickness, material))
    for (xx in listOf(x, x + 1)) for (yy in listOf(y, y + 1))
        add(CivsPreviewCuboid(xx - half, yy - half, z, thickness, thickness, 1f, material))
}
