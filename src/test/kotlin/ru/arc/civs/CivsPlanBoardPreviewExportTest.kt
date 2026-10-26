package ru.arc.civs

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.joml.Vector3f
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.scene.ScenePart
import ru.arc.paper.api.ArcSchematicPoint
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
                    "long-name" to auto.copy(name = "Очень длинное название большого поселения")).toMutableList()
                val scenarioPath = System.getenv("CIVS_PLAN_BOARD_SCENARIO")
                scenarioPath?.let { input ->
                    val gson = GsonBuilder().create()
                    val document = JsonParser.parseString(Files.readString(Path.of(input))).asJsonObject
                    val city = gson.fromJson(document["city"], Settlement::class.java)
                    val snapshot = gson.fromJson(document["snapshot"], CivPlanBoardSnapshot::class.java)
                    val templates = document.getAsJsonObject("templates")
                    val forecast = civsPlanBoardForecast(city, snapshot, Executor { it.run() }) { _, type, level, id ->
                        CompletableFuture.completedFuture(gson.fromJson(templates["$type-$level"], CivBuilding::class.java).copy(id = id))
                    }.join()!!
                    forecast.buildings.isNotEmpty() shouldBe true
                    val ready = snapshot.copy(worldId = player.world.uid, forecast = forecast)
                    cases += "auto2-legacy" to ready
                    cases += "auto2-new" to ready
                }
                val states = buildList {
                    for ((name, data) in cases) {
                        val rotations = if (name == "auto-upgrade") listOf(0, 90, 180, 270)
                            else if (name.startsWith("auto2-")) listOf(0, 270) else listOf(0)
                        for (rotation in rotations) {
                            val legacy = name == "auto2-legacy"
                            val lift = if (legacy) 0.0 else 1.0
                            val board = planBoardFixtureBuilding().copy(rotation = rotation,
                                schematicId = if (legacy) CivsPlanBoardBlueprint.LEGACY_SCHEMATIC_ID else CivsPlanBoardBlueprint.SCHEMATIC_ID)
                            val layout = CivsPlanBoardGeometry.layout(data)
                            val frame = renderer.frame(player.world, board, data, layout, text)
                            frame.parts.size shouldBe layout.displayParts
                            (frame.parts.size <= 256) shouldBe true
                            frame.parts.map { it.key }.distinct().size shouldBe frame.parts.size
                            val pieces = CivsPlanBoardBlueprint.blocks(legacy).filterValues { it != "minecraft:air" }.map { (point, state) ->
                                // Physical blocks use schematic integer cells, independently of display transforms.
                                val cell = CivsBlueprintGeometry.rotate(ArcSchematicPoint(point.x - CivsPlanBoardBlueprint.origin.x,
                                    point.y, point.z - CivsPlanBoardBlueprint.origin.z), rotation / 90)
                                mapOf<String, Any>("key" to "body-${point.x}-${point.y}-${point.z}",
                                    "x" to board.anchor.x + cell.x + .5, "y" to board.anchor.y + cell.y + .5, "z" to board.anchor.z + cell.z + .5,
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
                                "forecast" to data.forecast, "boardTemplate" to board.schematicId,
                                "roadCells" to layout.roadCells.size, "exactRoadRectangles" to layout.roadRectangles.size,
                                "cameras" to mapOf(
                                    "player" to listOf(3.5, 2.62, 7.0, 3.5, 2.62, 1.5, 65.0),
                                    "overview" to listOf(11.0, 8.0, 13.0, 3.5, 2.5, 1.5, 48.0),
                                    "front" to listOf(3.5, 3.0, 15.0, 3.5, 3.0, 1.5, 30.0),
                                    "side" to listOf(17.0, 3.0, 1.5, 3.5, 2.8, 1.5, 32.0),
                                    "top" to listOf(3.5, 17.0, 1.5, 3.5, 0.0, 1.5, 30.0)
                                ).mapValues { (key, c) -> mapOf(
                                    "eye" to CivsPlanBoardRenderer.localToWorld(board, c[0], c[1], c[2]),
                                    "target" to CivsPlanBoardRenderer.localToWorld(board, c[3], c[4] + if (key == "top") 0.0 else lift, c[5]),
                                    "up" to if (key != "top") listOf(0, 1, 0) else when (rotation) {
                                        90 -> listOf(1, 0, 0); 180 -> listOf(0, 0, 1); 270 -> listOf(-1, 0, 0); else -> listOf(0, 0, -1)
                                    }, "fov" to c[6]) },
                                "eye" to CivsPlanBoardRenderer.localToWorld(board, 3.5, 2.62, 7.0),
                                "target" to CivsPlanBoardRenderer.localToWorld(board, 3.5, 2.62 + lift, 1.5)))
                        }
                    }
                }
                System.getenv("CIVS_PLAN_BOARD_PREVIEW_OUT")?.let { output ->
                    val out = Path.of(output); Files.createDirectories(out)
                    val sourcePaths = listOf("CivsPlanBoardBlueprint.kt", "CivsPlanBoardGeometry.kt", "CivsPlanBoardRenderer.kt",
                        "CivsPlanBoardSnapshot.kt", "CivsInfrastructure.kt").map { "src/main/kotlin/ru/arc/civs/$it" } +
                        listOf("src/main/resources/locales.yml", "src/test/resources/plan-board-auto071.json") + listOfNotNull(scenarioPath)
                    val hashes = sourcePaths.associateWith { source -> MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(Path.of(source))).joinToString("") { "%02x".format(it) } }
                    Files.writeString(out.resolve("scene.json"), GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
                        "evidence" to "Compiled PacketDisplayScene frames; physical body uses independent integer schematic cells. Retained city snapshots, optional private Auto2 source scenario and labelled synthetic stress. Forecast sites have no terrain approval. No native client.",
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
