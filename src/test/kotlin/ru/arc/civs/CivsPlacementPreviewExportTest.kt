package ru.arc.civs

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.actionpanel.*
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.math.atan2

/** Exports actual consumer layouts and Core geometry; the sampled world contains explicit fixtures. */
class CivsPlacementPreviewExportTest : FunSpec({
    test("exported controls remain reachable and movement keeps the terrain-pick corridor clear") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("CivsPlacementPreviewExport")
            val player = paper.addPlayer("Mayor").apply { setLocale(Locale.forLanguageTag("ru")); isOp = true }
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val host = mockk<CivsUiHost>(relaxed = true)
            val ui = mockk<CivsUi>(relaxed = true)
            val displays = mockk<PaperPacketDisplays>(relaxed = true)
            val panels = mockk<PaperActionPanels<CivsPlacementContext, CivsPlacementAction>>(relaxed = true)
            val text = CivsText.fromYaml(civsLocaleSource())
            val controls = CivsPlacementControls(plugin, host, ui, tasks, text, displays, panels)
            try {
                val base = CivBuilding(UUID(0, 1), BuildingType.TOWN_HALL, 1, CivPosition(0, 63, 0), 0,
                    "fixture", CivBox(CivPosition(-6, 63, -12), CivPosition(5, 72, -1)), CivPosition(0, 64, 0), BuildingPhase.CONSTRUCTING)
                fun scene(name: String, building: CivBuilding, eye: ActionPanelPoint, moving: Boolean, obstacle: Boolean): Map<String, Any> {
                    val preview = CivsPreviewView(building.type, 1, "nordic", "Oak Town", building.anchor,
                        building.rotation, 12, 10, 12, Stock(), 400, UUID(0, 2), player.world.uid, building.entrance,
                        building.bounds, true, false)
                    val status = if (moving) text.render(player, "panel.moving") else text.render(player, "panel.status",
                        "building" to text.building(player, building.type), "rotation" to building.rotation)
                    val layout = controls.layout(player, CivsPlacementControls.actions(preview, moving), status, moving)
                    val rects = layout.buttons.map { it.rect } + layout.labels.filter { it.visible }.map { it.rect }
                    val target = ActionPanelPoint((building.bounds.min.x.toDouble() + building.bounds.max.x + 1) / 2,
                        building.entrance.y.toDouble(), (building.bounds.min.z.toDouble() + building.bounds.max.z + 1) / 2)
                    val settings = CivsPlacementControls.followingSettings(moving)
                    val yaw = Math.toDegrees(atan2(target.x - eye.x, eye.z - target.z)).toFloat()
                    fun anchor(distance: Double, shift: ActionPanelShift) =
                        ActionPanelGeometry.followingAnchor(eye, yaw, rects, distance,
                            settings.sideOffset + shift.side, settings.heightOffset + shift.up)
                    // Fixed world obstacles keep before/after evidence comparable when layout offsets change.
                    val obstacles = if (obstacle) listOf(if (moving) ActionPanelBounds(8.0, 65.0, 8.0, 9.0, 66.0, 9.0)
                        else ActionPanelBounds(1.0, 65.0, 3.0, 2.0, 66.0, 4.0)) else emptyList()
                    fun safe(distance: Double, shift: ActionPanelShift, clearance: Double): Boolean {
                        val placed = anchor(distance, shift)
                        if (!ActionPanelGeometry.allReachable(eye, placed, rects, 4.0)) return false
                        val volume = ActionPanelGeometry.volume(placed, rects, ActionPanelGeometry.SURFACE_CLEARANCE + clearance)
                        if (obstacles.any { ActionPanelGeometry.overlaps(volume, it) }) return false
                        return rects.flatMap { ActionPanelGeometry.corners(placed, it) }.all { corner ->
                            val vector = Vector(corner.x - eye.x, corner.y - eye.y, corner.z - eye.z)
                            val length = vector.length()
                            obstacles.none { box -> BoundingBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)
                                .rayTrace(Vector(eye.x, eye.y, eye.z), vector.clone().normalize(), length - 0.025) != null }
                        }
                    }
                    val shift = ActionPanelGeometry.adaptiveShift(settings.obstacleMaxShift, null) { candidate, clearance ->
                        safe(settings.distance, candidate, clearance)
                    }
                    val chosenShift = shift ?: ActionPanelShift(0.0, 0.0)
                    val distance = if (shift != null) settings.distance else requireNotNull(ActionPanelGeometry.firstSafeDistance(settings.distance, 0.35) {
                        safe(it, chosenShift, 0.0)
                    })
                    val placed = anchor(distance, chosenShift)
                    safe(distance, chosenShift, 0.0) shouldBe true
                    fun label(key: String, rect: ActionPanelRect, component: net.kyori.adventure.text.Component, style: ActionPanelTextStyle) =
                        mapOf("key" to key, "center" to ActionPanelGeometry.localToWorld(placed, rect.center), "width" to rect.width,
                            "height" to rect.height, "yaw" to placed.yaw, "text" to plainText(component),
                            "color" to (component.color()?.asHexString() ?: "#e8dfd2"), "scale" to style.scale, "inset" to style.baselineInset)
                    val labels = layout.buttons.map { label(it.action.name, it.rect, it.text, it.style) } +
                        layout.labels.filter { it.visible }.map { label(it.key, it.rect, it.text, it.style) }
                    // In move mode the player looks at the sampled terrain destination while the panel
                    // retains its target-facing orientation, exactly as a head turn does at runtime.
                    val cameraTarget = if (moving) ActionPanelPoint(7.5, 64.0, 5.5) else target
                    val candidate = if (moving) civsAnchorCues(CivPosition(7, 63, 5), Material.LIGHT_BLUE_CONCRETE) else emptyList()
                    val corridor = if (moving) {
                        val lowerEdge = layout.buttons.minOf { ActionPanelGeometry.localToWorld(placed, it.rect.center).y - it.rect.height / 2 }
                        val planes = layout.buttons.map { ActionPanelPlane(it.action,
                            ActionPanelGeometry.localToWorld(placed, it.rect.center), placed.yaw,
                            it.rect.width, it.rect.height + 0.5, true) }
                        val margins = listOf(candidate.minOf { it.x }, cameraTarget.x, candidate.maxOf { it.x + it.sx }).flatMap { x ->
                            listOf(candidate.minOf { it.z }, cameraTarget.z, candidate.maxOf { it.z + it.sz }).map { z ->
                                val direction = ActionPanelPoint(x - eye.x, candidate.maxOf { it.y + it.sy } - eye.y, z - eye.z)
                                // Quarter-block padding tests a deliberate margin, not merely a missed center ray.
                                ActionPanelGeometry.nearestTarget(eye, direction, planes, 4.0, null) shouldBe null
                                val hit = requireNotNull(ActionPanelGeometry.nearestHit(eye, direction,
                                    listOf(ActionPanelPlane(Unit, placed.point, placed.yaw, 8.0, 8.0, true)), 4.0, null))
                                lowerEdge - hit.point.y
                            }
                        }
                        (margins.min() >= 0.25) shouldBe true
                        mapOf("minimumVerticalGap" to margins.min(), "sampledRays" to margins.size, "requiredGap" to 0.25)
                    } else emptyMap<String, Any>()
                    return mapOf("id" to name, "eye" to eye, "target" to target, "cameraTarget" to cameraTarget, "anchor" to placed,
                        "distance" to distance, "shift" to chosenShift, "labels" to labels, "obstacles" to obstacles,
                        "terrainCorridor" to corridor, "cues" to (civsPreviewCues(building) + candidate),
                        "bounds" to building.bounds, "groundY" to 63)
                }
                val scenes = listOf(scene("open", base, ActionPanelPoint(0.5, 65.62, 5.5), false, false),
                    scene("obstructed", base, ActionPanelPoint(0.5, 65.62, 5.5), false, true),
                    scene("moved-rotated", base.copy(anchor = CivPosition(3, 63, 2), entrance = CivPosition(3, 64, 2), rotation = 90,
                        bounds = CivBox(CivPosition(4, 63, -4), CivPosition(15, 72, 7))), ActionPanelPoint(6.5, 65.62, 11.5), true, false),
                    scene("moved-obstructed", base.copy(anchor = CivPosition(3, 63, 2), entrance = CivPosition(3, 64, 2), rotation = 90,
                        bounds = CivBox(CivPosition(4, 63, -4), CivPosition(15, 72, 7))), ActionPanelPoint(6.5, 65.62, 11.5), true, true))
                val out = System.getenv("CIVS_PLACEMENT_PREVIEW_OUT")?.let(Path::of)
                if (out != null) {
                    Files.createDirectories(out)
                    val sources = listOf("src/main/kotlin/ru/arc/civs/CivsPlacementControls.kt", "src/main/kotlin/ru/arc/civs/CivsPreviewRenderer.kt",
                        "src/main/resources/locales.yml", "../arc-core/arc-core-paper/src/main/kotlin/ru/arc/paper/actionpanel/ActionPanelGeometry.kt",
                        "../arc-core/arc-core-paper/src/main/kotlin/ru/arc/paper/actionpanel/ActionPanelSpec.kt").associateWith { name ->
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(name))).joinToString("") { "%02x".format(it) }
                    }
                    Files.writeString(out.resolve("scene.json"), GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                        "evidence" to "Compiled consumer layout, source cue geometry and Core placement helpers. Explicit obstacle and bounds fixtures; no schematic model or native client.",
                        "sourceSha256" to sources, "scenes" to scenes)))
                }
            } finally { controls.close(); tasks.close() }
        }
    }
})
