package ru.arc.civs

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.menu.DialogTextLayout
import ru.arc.paper.scene.PacketDisplayScene
import ru.arc.paper.scene.SceneAudience
import ru.arc.paper.scene.SceneDisplayStyle
import ru.arc.paper.scene.SceneFrame
import ru.arc.paper.scene.ScenePart
import ru.arc.paper.scene.ScenePose
import ru.arc.paper.scene.SceneTextStyle
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.logging.Level
import kotlin.math.min

/** Public, ephemeral plan visuals. Registered building records own placement and persistence. */
class CivsPlanBoardRenderer(
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val nativeWork: BudgetedTaskQueue?,
    private val executor: Executor,
    private val snapshot: (UUID) -> CompletableFuture<CivPlanBoardSnapshot>,
) : AutoCloseable {
    private class City(var key: String, var boards: List<CivBuilding>, val worldId: UUID) {
        var pending = false
        var ready: Pair<CivPlanBoardSnapshot, CivPlanBoardLayout>? = null
        var retryAfter = 0L
        var failureSignature: String? = null
        var failedAttempts = 0L
    }
    private data class Drawn(val scene: PacketDisplayScene<String>, var key: String)
    private val cities = mutableMapOf<UUID, City>()
    private val scenes = mutableMapOf<UUID, Drawn>()
    private val pending = mutableSetOf<CompletableFuture<*>>()
    private val materials = mutableMapOf<String, BlockData>()
    private var displays: PaperPacketDisplays? = null
    private var text: CivsText? = null
    private var closed = false
    private var terminal: CompletableFuture<Void>? = null
    private val audience = SceneAudience.Public()
    private val style = SceneDisplayStyle(billboard = Display.Billboard.FIXED,
        brightness = Display.Brightness(15, 15), viewRange = .75f)
    private val textStyle = SceneTextStyle(background = Color.fromARGB(0), shadowed = false,
        seeThrough = false, alignment = TextDisplay.TextAlignment.CENTER, lineWidth = 4096)

    /** Main-thread settlement pulse; no file IO, terrain capture, or chunk loading. */
    fun pulse(settlements: Collection<Settlement>) {
        check(Bukkit.isPrimaryThread())
        if (closed) return
        val wanted = settlements.filter { city -> city.pendingDeletion == null &&
            city.buildings.any { it.type == BuildingType.PLAN_BOARD && it.phase == BuildingPhase.ACTIVE } }
        val ids = wanted.mapTo(hashSetOf()) { it.id }
        cities.keys.filter { it !in ids }.forEach { cities.remove(it) }
        val boardIds = wanted.flatMap { it.buildings }.filter { it.type == BuildingType.PLAN_BOARD && it.phase == BuildingPhase.ACTIVE }
            .mapTo(hashSetOf()) { it.id }
        scenes.keys.filter { it !in boardIds }.forEach { scenes.remove(it)?.scene?.close() }
        for (city in wanted) {
            val boards = city.buildings.filter { it.type == BuildingType.PLAN_BOARD && it.phase == BuildingPhase.ACTIVE }
            val key = civsPlanBoardLayoutKey(city)
            val state = cities.getOrPut(city.id) { City(key, boards, city.worldId) }
            state.boards = boards
            if (state.key != key) {
                state.key = key; state.retryAfter = 0
            }
            val observed = boards.filter { observed(city.worldId, it) }
            if (observed.isEmpty()) continue
            state.ready?.let { (data, layout) ->
                observed.forEach { draw(it, data, layout, data.layoutKey != key) }
            }
            if (state.ready?.first?.layoutKey != key && !state.pending && System.currentTimeMillis() >= state.retryAfter)
                request(city.id, state)
        }
    }

    private fun observed(worldId: UUID, board: CivBuilding): Boolean {
        val world = plugin.server.getWorld(worldId) ?: return false
        if (!world.isChunkLoaded(board.anchor.x shr 4, board.anchor.z shr 4)) return false
        return plugin.server.onlinePlayers.any { player ->
            if (player.world.uid != worldId) false else player.location.let { location ->
                val dx = location.x - board.anchor.x; val dy = location.y - board.anchor.y; val dz = location.z - board.anchor.z
                dx * dx + dy * dy + dz * dz <= 48.0 * 48.0
            }
        }
    }

    private fun request(cityId: UUID, state: City) {
        state.pending = true
        val key = state.key
        val future = try {
            snapshot(cityId).thenApplyAsync({ data ->
                require(data.cityId == cityId && data.layoutKey == key) { "Settlement plan changed while loading" }
                data to CivsPlanBoardGeometry.layout(data)
            }, executor)
        } catch (failure: Exception) {
            state.pending = false
            failed(cityId, state, failure)
            return
        }
        pending += future
        future.whenCompleteSync(tasks) { ready, failure ->
            pending -= future
            if (closed || cities[cityId] !== state) return@whenCompleteSync
            state.pending = false
            if (state.key != key) return@whenCompleteSync
            if (failure != null) {
                failed(cityId, state, failure)
            } else {
                val data = requireNotNull(ready)
                if (state.failureSignature != null) CivsDiagnostics.event("plan_board_refresh_recovered",
                    "city" to cityId, "layout_key" to key, "failed_attempts" to state.failedAttempts)
                state.failureSignature = null; state.failedAttempts = 0
                state.ready = data
                state.boards.filter { observed(state.worldId, it) }.forEach { draw(it, data.first, data.second) }
            }
        }
    }

    private fun failed(cityId: UUID, state: City, failure: Throwable) {
        val reason = CivsDiagnostics.rejection(failure)?.reason
        if (reason in setOf("plan-board-busy", "plan-board-stale", "not-ready", "no-city")) {
            // Expected bounded admission/lifecycle deferrals retry on the next ordinary pulse.
            state.retryAfter = System.currentTimeMillis() + 1_000
            return
        }
        state.retryAfter = System.currentTimeMillis() + 30_000
        state.failedAttempts++
        val signature = "${state.key}:${CivsDiagnostics.cause(failure)?.javaClass?.name}:$reason"
        if (state.failureSignature == signature) return
        state.failureSignature = signature
        CivsDiagnostics.record(Level.WARNING, cityId, CivsDiagnostics.line("plan_board_refresh_failed",
            "city" to cityId, "layout_key" to state.key, "reason" to reason,
            "failed_attempts" to state.failedAttempts, "retry_ms" to 30_000), plugin.logger, failure)
    }

    private fun draw(board: CivBuilding, snapshot: CivPlanBoardSnapshot, layout: CivPlanBoardLayout, updating: Boolean = false) {
        val world = plugin.server.getWorld(snapshot.worldId) ?: return
        val drawKey = "${snapshot.layoutKey}:${board.anchor}:${board.rotation}:$updating"
        if (scenes[board.id]?.let { it.key == drawKey && it.scene.isValid() } == true) return
        val owner = displays ?: (nativeWork?.let { PaperPacketDisplays(plugin, "civs-plan-board", it) }
            ?: PaperPacketDisplays(plugin, "civs-plan-board")).also { displays = it }
        val catalog = text ?: CivsText.load(plugin).also { text = it }
        val scene = scenes.getOrPut(board.id) { Drawn(PacketDisplayScene(owner), "") }
        scene.scene.apply(frame(world, board, snapshot, layout, catalog, updating))
        scene.key = drawKey
    }

    internal fun frame(world: World, board: CivBuilding, snapshot: CivPlanBoardSnapshot,
        layout: CivPlanBoardLayout, catalog: CivsText, updating: Boolean = false): SceneFrame<String> {
        val parts = mutableListOf<ScenePart<String>>()
        val large = CivsPlanBoardBlueprint.large(board)
        val legacy = board.schematicId == CivsPlanBoardBlueprint.LEGACY_SCHEMATIC_ID
        val sx = if (large) 1.0 else 5.0 / 9.0
        val sy = if (large) 1.0 else .75
        fun pose(x: Double, y: Double, z: Double, width: Double, height: Double, depth: Double,
            surface: Boolean = true, glyph: Boolean = false): ScenePose {
            val px = (if (surface && !large) 1 + (x + 1) * sx else x) +
                if (surface) CivsPlanBoardBlueprint.faceOffset(board.schematicId) else 0
            val py = if (surface && !large) 1 + (y - 2) * sy + if (legacy) 0 else 1 else y
            val p = localToWorld(board, px, py, z)
            val gx = if (!surface) 1.0 else if (glyph) min(sx, sy) else sx
            val gy = if (!surface) 1.0 else if (glyph) min(sx, sy) else sy
            return ScenePose(Location(world, p[0], p[1], p[2]), Transformation(Vector3f(),
                Quaternionf().rotateY(-Math.toRadians(board.rotation.toDouble()).toFloat()),
                Vector3f((width * gx).toFloat(), (height * gy).toFloat(), depth.toFloat()), Quaternionf()))
        }
        for (patch in layout.patches) parts += ScenePart.Block(patch.key,
            pose(patch.x, patch.y, patch.z, patch.width, patch.height, .006), style,
            materials.getOrPut(patch.material) { Material.valueOf(patch.material).createBlockData() })
        for (raster in layout.rasters) {
            // Rows are separate components: no oversized single UTF-8 string, even for a sparse path.
            val content = Component.text().color(TextColor.color(raster.color)).font(Key.key("minecraft:default"))
            raster.rows.forEachIndexed { index, row ->
                if (index != 0) content.append(Component.newline())
                content.append(Component.text(row))
            }
            parts += ScenePart.Text(raster.key, pose(raster.x + raster.width / 2, raster.y, raster.z,
                raster.width / (raster.columns * 36.0 * .025), raster.height / (raster.rows.size * 9.0 * .025), 1.0),
                style, content.build(), textStyle.copy(lineWidth = raster.columns * 36 + 8,
                    shadowed = true, alignment = TextDisplay.TextAlignment.LEFT))
        }
        fun label(key: String, content: Component, x: Double, y: Double, z: Double, scale: Double, color: Int,
            width: Double, lines: Int = 1, heightLimit: Double = 2.1, left: Boolean = false, surface: Boolean = true) {
            val plain = PlainTextComponentSerializer.plainText().serialize(content)
            val pixels = plain.lineSequence().maxOfOrNull {
                DialogTextLayout.naturalWidth(Component.text(it)) ?: it.codePointCount(0, it.length) * 9
            } ?: 1
            val fitted = min(min(scale, width / (pixels.coerceAtLeast(1) * .025)),
                if (lines > 1) heightLimit / (lines * 9 * .025) else scale)
            val height = lines * 9 * .025 * fitted
            fun tint(value: Component): Component = value.color(TextColor.color(color)).children(value.children().map(::tint))
            val textX = if (left) x + pixels * .025 * fitted / 2 else x
            parts += ScenePart.Text(key, pose(textX, y - height / 2, z, fitted, fitted, 1.0, surface, glyph = true), style,
                tint(content).font(Key.key("minecraft:default")).decoration(TextDecoration.ITALIC, false),
                textStyle.copy(alignment = if (left) TextDisplay.TextAlignment.LEFT else TextDisplay.TextAlignment.CENTER))
        }
        label("title", catalog.render(null, if (updating) "plan-board.updating-title" else "plan-board.title", "name" to snapshot.name),
            3.5, 6.25, 3.018, 1.15, 0xFFF4D3, 10.4)
        label("north", catalog.render(null, if (layout.northLeft) "plan-board.north-left" else "plan-board.north-up"),
            -.58, 5.93, 2.055, .50, 0x44352B, .45)
        parts += ScenePart.Block("legend-panel", pose(4.89, 2.12, 2.045, 2.97, 3.76, .006), style,
            materials.getOrPut("GRAY_CONCRETE") { Material.GRAY_CONCRETE.createBlockData() })
        label("legend-built", catalog.render(null, "plan-board.built"), 5.07, 5.64, 2.065, .65, 0x8DDFE6, 2.60, left = true)
        label("legend-planned", catalog.render(null, "plan-board.planned"), 5.07, 5.39, 2.065, .65, 0xFFD071, 2.60, left = true)
        label("legend-future", catalog.render(null, "plan-board.future"), 5.07, 5.14, 2.065, .65, 0xD7B4FF, 2.60, left = true)
        label("legend-infrastructure", catalog.render(null, "plan-board.infrastructure"), 5.07, 2.42, 2.065,
            .46, 0xE6CBB1, 2.60, left = true)
        val rows = (layout.entries.size + 1) / 2
        for (column in 0..1) {
            val entries = layout.entries.drop(column * rows).take(rows)
            val listing = Component.text()
            if (layout.showNumbers) entries.forEachIndexed { index, entry ->
                if (index != 0) listing.append(Component.newline())
                listing.append(Component.text("${entry.number}. ")).append(catalog.render(null,
                    if (entry.type in CivsAutonomy.profile) "plan-board.key.${entry.type.name.lowercase()}"
                    else "building.${entry.type.name.lowercase()}"))
            } else if (column == 0) listing.append(catalog.render(null, "plan-board.compact-key"))
            label("building-key-$column", listing.build(), 5.07 + column * 1.40, 3.98, 2.065, .66,
                0xFFF4DF, 1.22, if (layout.showNumbers) entries.size.coerceAtLeast(1) else 3, 2.0, left = true)
        }
        if (layout.showNumbers) layout.entries.forEach { entry ->
            label("number-${entry.id}", Component.text(entry.number), entry.x, entry.y, 2.055, .80,
                if (entry.future) 0x613681 else if (snapshot.buildings.any { it.id == entry.id }) 0xFFFFFF else 0x745117,
                min(entry.width * .85, min(entry.height * .85 * entry.number.toString().length, .44)))
        }
        val sideControl = board.schematicId in setOf(CivsPlanBoardBlueprint.LEGACY_SCHEMATIC_ID, CivsPlanBoardBlueprint.REFIT_V1)
        val controlX = if (sideControl) 6.5 else 3.5
        val controlY = if (large || !legacy) 1.61 else 2.32
        val controlZ = if (legacy) 2.065 else 3.025
        parts += ScenePart.Block("control-label-panel", pose(controlX - .46, controlY - .12, controlZ - .008,
            .92, .24, .006, surface = false), style, materials.getValue("GRAY_CONCRETE"))
        label("control-label", catalog.render(null, "plan-board.control"), controlX, controlY, controlZ,
            .37, 0xFFF4DF, .84, surface = false)
        check(parts.size == layout.displayParts)
        return SceneFrame(parts, audience)
    }

    override fun close() { closeAsync() }

    fun closeAsync(): CompletableFuture<Void> {
        terminal?.let { return it }
        closed = true
        cities.clear()
        scenes.values.forEach { it.scene.close() }; scenes.clear()
        val close = displays?.closeAsync() ?: CompletableFuture.completedFuture(null)
        return CompletableFuture.allOf(close, *pending.map { it.handle { _, _ -> null } }.toTypedArray())
            .also { terminal = it }
    }

    companion object {
        internal fun localToWorld(board: CivBuilding, x: Double, y: Double, z: Double): List<Double> {
            // Schematics rotate integer block cells; a block still extends +1 on each world axis.
            // Rotate continuous display geometry around cell centres, not the block-grid corner.
            val dx = x - CivsPlanBoardBlueprint.origin.x - .5; val dz = z - CivsPlanBoardBlueprint.origin.z - .5
            val (rx, rz) = when (Math.floorMod(board.rotation / 90, 4)) {
                0 -> dx to dz
                1 -> -dz to dx
                2 -> -dx to -dz
                else -> dz to -dx
            }
            return listOf(board.anchor.x + rx + .5, board.anchor.y + y, board.anchor.z + rz + .5)
        }
    }
}
