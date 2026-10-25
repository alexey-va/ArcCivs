package ru.arc.civs

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.joml.Vector3f
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.scene.ScenePart
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/** Exports the actual renderer's text, transforms, materials and canonical physical body. */
class CivsPlanBoardPreviewExportTest : FunSpec({
    test("compiled map frames fit one public scene and export the real geometry and text") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("CivsPlanBoardPreviewExport")
            val player = paper.addPlayer("BoardReader")
            val tasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val renderer = CivsPlanBoardRenderer(plugin, tasks, null, Executor { it.run() }) {
                error("A render export must not read storage")
            }
            val text = CivsText.fromYaml(civsLocaleSource())
            try {
                // Old service fixtures and idle cities must not construct PacketEvents/native providers.
                renderer.pulse(emptyList())
                val auto = planBoardAutoFixture().copy(worldId = player.world.uid)
                val hall = auto.buildings.first { it.type == BuildingType.TOWN_HALL }
                val planned = hall.copy(level = hall.level + 1, phase = BuildingPhase.CONSTRUCTING,
                    bounds = hall.bounds.copy(max = hall.bounds.max.copy(x = hall.bounds.max.x + 2, z = hall.bounds.max.z + 2)))
                val cases = listOf("auto071" to auto,
                    "auto-upgrade" to auto.copy(plannedBuildings = listOf(planned)),
                    "dense28" to planBoardDenseFixture().copy(worldId = player.world.uid),
                    "winding-raster" to planBoardRasterFixture().copy(worldId = player.world.uid),
                    "portrait-raster" to planBoardPortraitRasterFixture().copy(worldId = player.world.uid),
                    "maximum-native-route" to planBoardLargestRouteFixture().copy(worldId = player.world.uid),
                    "long-name" to auto.copy(name = "Очень длинное название большого поселения"))
                val states = buildList {
                    for ((name, data) in cases) {
                        val rotations = if (name == "auto-upgrade") listOf(0, 90, 180, 270) else listOf(0)
                        for (rotation in rotations) {
                            val board = planBoardFixtureBuilding().copy(rotation = rotation)
                            val layout = CivsPlanBoardGeometry.layout(data)
                            val frame = renderer.frame(player.world, board, data, layout, text)
                            frame.parts.size shouldBe layout.displayParts
                            (frame.parts.size <= 256) shouldBe true
                            frame.parts.map { it.key }.distinct().size shouldBe frame.parts.size
                            val pieces = CivsPlanBoardBlueprint.blocks().filterValues { it != "minecraft:air" }.map { (point, state) ->
                                val center = CivsPlanBoardRenderer.localToWorld(board, point.x + .5, point.y + .5, point.z + .5)
                                mapOf<String, Any>("key" to "body-${point.x}-${point.y}-${point.z}", "x" to center[0], "y" to center[1], "z" to center[2],
                                    "width" to 1.0, "height" to 1.0, "depth" to 1.0, "rotationY" to -rotation,
                                    "material" to if (state == "minecraft:structure_void") Material.BARREL.createBlockData().asString else state)
                            }.toMutableList()
                            val labels = mutableListOf<Map<String, Any>>()
                            for (part in frame.parts) {
                                val pose = part.pose
                                val at = pose.location(); val transform = pose.transformation()
                                if (part is ScenePart.Block) {
                                    val center = Vector3f(transform.scale).mul(.5f).rotate(transform.leftRotation).add(transform.translation)
                                    pieces += mapOf("key" to part.key, "x" to at.x + center.x, "y" to at.y + center.y, "z" to at.z + center.z,
                                        "width" to transform.scale.x, "height" to transform.scale.y, "depth" to transform.scale.z,
                                        "rotationY" to -rotation, "material" to part.block().asString)
                                } else if (part is ScenePart.Text) {
                                    labels += mapOf("key" to part.key, "text" to PlainTextComponentSerializer.plainText().serialize(part.text),
                                        "component" to GsonComponentSerializer.gson().serialize(part.text),
                                        "color" to (part.text.color()?.asHexString() ?: "#FFFFFF"),
                                        "x" to at.x, "y" to at.y, "z" to at.z,
                                        "sx" to transform.scale.x, "sy" to transform.scale.y, "sz" to transform.scale.z,
                                        "rotationY" to -rotation, "shadow" to part.textStyle.shadowed,
                                        "lineWidth" to part.textStyle.lineWidth, "alignment" to part.textStyle.alignment.name)
                                }
                            }
                            add(mapOf("id" to "$name-r$rotation", "label" to data.name, "pieces" to pieces, "labels" to labels,
                                "displayParts" to frame.parts.size, "raster" to layout.rasters.isNotEmpty(),
                                "roadCells" to layout.roadCells.size, "exactRoadRectangles" to layout.roadRectangles.size,
                                "eye" to CivsPlanBoardRenderer.localToWorld(board, 3.5, 2.62, 7.0),
                                "target" to CivsPlanBoardRenderer.localToWorld(board, 3.5, 2.62, 1.5)))
                        }
                    }
                }
                System.getenv("CIVS_PLAN_BOARD_PREVIEW_OUT")?.let { output ->
                    val out = Path.of(output); Files.createDirectories(out)
                    val sourcePaths = listOf("CivsPlanBoardBlueprint.kt", "CivsPlanBoardGeometry.kt", "CivsPlanBoardRenderer.kt",
                        "CivsPlanBoardSnapshot.kt", "CivsInfrastructure.kt").map { "src/main/kotlin/ru/arc/civs/$it" } +
                        listOf("src/main/resources/locales.yml", "src/test/resources/plan-board-auto071.json")
                    val hashes = sourcePaths.associateWith { source -> MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(Path.of(source))).joinToString("") { "%02x".format(it) } }
                    Files.writeString(out.resolve("scene.json"), GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                        "evidence" to "Compiled PacketDisplayScene frames and canonical physical body; real Auto071 retained snapshot and labelled synthetic stress. No native client.",
                        "sourceSha256" to hashes, "states" to states)))
                    Files.write(out.resolve("plan-board.schem"), CivsPlanBoardBlueprint.schematic(4671))
                }
            } finally {
                renderer.closeAsync().isDone shouldBe true
                tasks.close()
            }
        }
    }
})
