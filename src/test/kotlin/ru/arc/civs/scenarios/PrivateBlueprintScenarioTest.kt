package ru.arc.civs.scenarios

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.civs.*
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicIds
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Private source payload is opt-in, never a required CI download or a public fixture. */
class PrivateBlueprintScenarioTest : FunSpec({
    val privateRoot = System.getenv("CIVS_SCENARIO_SCHEMATICS_ROOT")
    test("an actual pinned Caledonia forester preserves its complete authored building and adjacent roads")
        .config(enabled = privateRoot != null) {
            val source = privateBlueprint(requireNotNull(privateRoot),
                "civs/minecolonies/caledonia/fundamentals/lumberjack1",
                "c2b461694ab83a7e0409171a82039745f51d881e601ef0b6310a1e27d9f925d5")
            var authored = emptyMap<CivPosition, String>()
            SettlementScenario.run("actual-caledonia-forester", CivPosition(0, 64, 0), setup = { world ->
                support("hall", BuildingType.TOWN_HALL, CivPosition(50, 63, 5))
                val hut = support("hut", BuildingType.BUILDER_HUT, CivPosition(40, 63, 10))
                val frame = blueprint("mill", "lumberjack", CivPosition(0, 63, 0))
                val alignment = CivsBlueprintGeometry.alignment(source, frame.anchor, 2, 0)
                val mill = frame.copy(control = alignment.control, entrance = alignment.entrance)
                buildings["mill"] = mill
                resident("forester", mill)
                resident("builder", hut)
                road("road", CivPosition(0, 63, 4))
                for (x in -4..4) for (z in -4..4) world.loadChunk(x, z)
                authored = CivsBlueprints.worldCells(source, mill.anchor).filterNot { it.isBarrier }.associate { cell ->
                    val point = CivPosition(cell.position.x, cell.position.y, cell.position.z)
                    val state = if (cell.blockData == "minecraft:structure_void") "minecraft:barrel[facing=north,open=false]" else cell.blockData
                    world.getBlockAt(point.x, point.y, point.z).blockData = Bukkit.createBlockData(state)
                    point to state
                }
                for (y in 64..66) world.getBlockAt(-15, y, -20).type = Material.OAK_LOG
                world.getBlockAt(-15, 67, -20).blockData = Bukkit.createBlockData(
                    "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]")
                world.getBlockAt(1, 63, 4).type = Material.DIRT_PATH
            }) {
                authored.size shouldBe 1690
                unchanged(authored)
                val path = snapshot(listOf(CivPosition(1, 63, 4)))
                val work = nextWork("forester")
                work.status shouldBe "felling-tree"
                work.focus shouldBe CivPosition(-15, 64, -20)
                complete(work)
                unchanged(authored)
                unchanged(path)
                city.stock.food shouldBe 1
                println("private-blueprint id=civs/minecolonies/caledonia/fundamentals/lumberjack1 " +
                    "sha256=${source.fileSha256} cells=${source.volume} replayed=${authored.size}; MockBukkit source replay, not native Paper acceptance")
            }
        }
})

/** Uses the existing Sponge decoder, then the actual ArcCivs alignment/world-cell helpers. */
internal fun privateBlueprint(configuredRoot: String, id: String, sha256: String): ArcSchematicSnapshot {
    require(configuredRoot.isNotBlank()) { "CIVS_SCENARIO_SCHEMATICS_ROOT was explicitly set but is empty" }
    require(ArcSchematicIds.isValid(id))
    val root = Path.of(configuredRoot).toRealPath()
    val atelier = Path.of(System.getenv("CIVS_SCENARIO_ATELIER_ROOT") ?: "../ruscrafting-ops/scripts/location-atelier").toRealPath()
    check(Files.isRegularFile(atelier.resolve("sponge.py"))) { "Missing existing atelier Sponge reader at $atelier" }
    val reader = requireNotNull(PrivateBlueprintScenarioTest::class.java.getResource("/scenarios/read-private-blueprint.py")).readText()
    val process = ProcessBuilder("python3", "-B", "-c", reader, root.toString(), atelier.toString(), id, sha256)
        .redirectErrorStream(true).start()
    val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
    val encoded = try {
        check(process.waitFor(30, TimeUnit.SECONDS)) { "Private blueprint reader did not finish within 30 seconds" }
        val text = output.get(1, TimeUnit.SECONDS)
        check(process.exitValue() == 0) { "Private blueprint reader failed for $id:\n$text" }
        JsonParser.parseString(text).asJsonObject
    } finally { process.destroyForcibly() }
    val size = encoded.getAsJsonArray("size").map { it.asInt }
    val offset = encoded.getAsJsonArray("worldEditOffset").map { it.asInt }
    val cells = encoded.getAsJsonArray("cells").mapIndexed { index, value ->
        val state = value.asString
        ArcSchematicBlock(ArcSchematicPoint(offset[0] + index % size[0], offset[1] + index / (size[0] * size[2]),
            offset[2] + index / size[0] % size[2]), state, state in setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air"),
            state == "minecraft:barrier")
    }
    return mockk {
        every { minimum } returns ArcSchematicPoint(offset[0], offset[1], offset[2])
        every { maximum } returns ArcSchematicPoint(offset[0] + size[0] - 1, offset[1] + size[1] - 1, offset[2] + size[2] - 1)
        every { origin } returns ArcSchematicPoint(0, 0, 0)
        every { blocks() } returns cells
        every { fileSha256 } returns sha256
        every { volume } returns cells.size.toLong()
    }
}
