package ru.arc.civs

import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsTerrainDebugTest : FunSpec({
    test("operator terrain bounds keep the entire fixed box inside the city and world without integer wraparound") {
        val city = Settlement(UUID.randomUUID(), UUID.randomUUID(), "Bounds", UUID.randomUUID(), CivPosition(0, 64, 0),
            8, "test", Stock(), emptyList(), emptyList())
        CivsTerrainDebug.requestedBounds(city, CivPosition(4, -59, 4), -64, 320) shouldBe
            CivBox(CivPosition(0, -64, 0), CivPosition(8, -54, 8))
        CivsTerrainDebug.requestedBounds(city, CivPosition(-4, 314, -4), -64, 320) shouldBe
            CivBox(CivPosition(-8, 309, -8), CivPosition(0, 319, 0))
        for (origin in listOf(CivPosition(5, 64, 0), CivPosition(-5, 64, 0), CivPosition(0, 64, 5), CivPosition(0, 64, -5)))
            shouldThrow<CivsRejection> { CivsTerrainDebug.requestedBounds(city, origin, -64, 320) }.reason shouldBe "capture-outside-city"
        for (y in listOf(-65, -64, -60, 315, 319, 320))
            shouldThrow<CivsRejection> { CivsTerrainDebug.requestedBounds(city, CivPosition(0, y, 0), -64, 320) }
                .reason shouldBe "capture-bounds-outside-world"
        for (origin in listOf(CivPosition(Int.MIN_VALUE, 64, 0), CivPosition(Int.MAX_VALUE, 64, 0),
            CivPosition(0, Int.MIN_VALUE, 0), CivPosition(0, Int.MAX_VALUE, 0),
            CivPosition(0, 64, Int.MIN_VALUE), CivPosition(0, 64, Int.MAX_VALUE)))
            shouldThrow<CivsRejection> { CivsTerrainDebug.requestedBounds(city, origin, -64, 320) }
                .reason shouldBe "capture-bounds-overflow"
    }

    test("an operator origin captures all 891 native states at Y55 without changing the actual resident or loading chunks") {
        terrainFixture { world, city, worker ->
            world.loadChunk(1, 0)
            val origin = CivPosition(24, 55, 8)
            world.getBlockAt(24, 55, 8).blockData = Bukkit.createBlockData("minecraft:ladder[facing=north,waterlogged=false]")
            world.getBlockAt(24, 54, 8).type = Material.COBBLESTONE
            world.getBlockAt(24, 56, 8).type = Material.AIR
            val actual = Location(world, 8.5, 64.25, 8.5)
            val loaded = world.loadedChunks.map { it.x to it.z }.toSet()
            val privateWorker = worker.copy(name = "PRIVATE_RESIDENT_NAME", items = listOf(CivResidentItem("DIAMOND", 64)))
            val selected = city.copy(workers = listOf(privateWorker))

            val snapshot = CivsTerrainDebug.capture(selected, privateWorker, actual, null, 42_000, origin)

            snapshot["error"] shouldBe null
            snapshot["focus_origin"] shouldBe "operator-request"
            snapshot["capture_center"] shouldBe origin
            snapshot["actual"] shouldBe listOf(8.5, 64.25, 8.5)
            snapshot["bounds"] shouldBe mapOf("min" to CivPosition(20, 50, 4), "max" to CivPosition(28, 60, 12))
            snapshot["voxel_count"] shouldBe 891
            snapshot["unavailable_count"] shouldBe 0
            snapshot["size"] shouldBe listOf(9, 11, 9)
            val ladder = terrainPaletteAt(snapshot, origin)
            ladder["block_data"] shouldBe "minecraft:ladder[facing=north,waterlogged=false]"
            ladder["collision"] shouldBe listOf(listOf(0.0, 0.0, 13.0 / 16.0, 1.0, 1.0, 1.0))
            terrainPaletteAt(snapshot, origin.copy(y = 54))["block_data"] shouldBe "minecraft:cobblestone"
            terrainPaletteAt(snapshot, origin.copy(y = 56))["collision"] shouldBe emptyList<Any>()
            val encoded = com.google.gson.Gson().toJson(snapshot)
            for (secret in listOf("PRIVATE_RESIDENT_NAME", "DIAMOND", "inventory", "entities", "nbt"))
                encoded.contains(secret) shouldBe false
            actual shouldBe Location(world, 8.5, 64.25, 8.5)
            world.loadedChunks.map { it.x to it.z }.toSet() shouldBe loaded
            world.getBlockAt(24, 55, 8).type shouldBe Material.LADDER
        }
    }

    test("an operator box fails before native reads if any required chunk is unloaded") {
        terrainFixture { world, city, worker ->
            val observed = spyk(world)
            every { observed.isChunkLoaded(any<Int>(), any<Int>()) } answers {
                firstArg<Int>() == 0 && secondArg<Int>() == 0
            }
            val loaded = world.loadedChunks.map { it.x to it.z }.toSet()
            val snapshot = CivsTerrainDebug.capture(city, worker, Location(observed, 8.5, 64.0, 8.5), null, 1_000,
                CivPosition(15, 55, 8))
            snapshot["error"] shouldBe "chunks-unloaded"
            snapshot["focus_origin"] shouldBe "operator-request"
            snapshot["voxels"] shouldBe null
            snapshot["palette"] shouldBe null
            verify(exactly = 0) { observed.getBlockAt(any<Int>(), any<Int>(), any<Int>()) }
            verify(exactly = 0) { observed.getChunkAt(any<Int>(), any<Int>(), true) }
            verify(exactly = 0) { observed.loadChunk(any<Int>(), any<Int>()) }
            verify(exactly = 0) { observed.loadChunk(any<Int>(), any<Int>(), any<Boolean>()) }
            world.loadedChunks.map { it.x to it.z }.toSet() shouldBe loaded
        }
    }

    test("operator captures reject a foreign worker and an out-of-city box before reading blocks") {
        terrainFixture { world, city, worker ->
            val observed = spyk(world)
            for ((selectedWorker, origin, error) in listOf(
                Triple(worker.copy(id = UUID.randomUUID()), CivPosition(8, 55, 8), "worker-not-found"),
                Triple(worker, city.center.copy(x = city.center.x + city.radius, y = 55), "capture-outside-city"))) {
                val snapshot = CivsTerrainDebug.capture(city, selectedWorker, Location(observed, 8.5, 64.0, 8.5), null, 1_000, origin)
                snapshot["error"] shouldBe error
                snapshot["voxels"] shouldBe null
            }
            verify(exactly = 0) { observed.getBlockAt(any<Int>(), any<Int>(), any<Int>()) }
        }
    }

    test("operator capture requires entity ticking rather than merely an existing chunk") {
        terrainFixture { world, city, worker ->
            val observed = spyk(world)
            every { observed.getChunkAt(0, 0, false) } returns mockk<org.mockbukkit.mockbukkit.world.ChunkMock> {
                every { loadLevel } returns org.bukkit.Chunk.LoadLevel.BORDER
            }
            val snapshot = CivsTerrainDebug.capture(city, worker, Location(observed, 8.5, 64.0, 8.5), null, 1_000,
                CivPosition(8, 55, 8))
            snapshot["error"] shouldBe "chunks-unloaded"
            snapshot["voxels"] shouldBe null
            verify(exactly = 0) { observed.getBlockAt(any<Int>(), any<Int>(), any<Int>()) }
            verify(exactly = 0) { observed.loadChunk(any<Int>(), any<Int>()) }
        }
    }

    test("explicit terrain requires exact valid filters and all includes autonomous state without native terrain capture") {
        val city = UUID.randomUUID().toString()
        val worker = UUID.randomUUID().toString()
        val cases = listOf(
            listOf("terrain") to "terrain-requires-city-and-worker",
            listOf("terrain", city) to "terrain-requires-city-and-worker",
            listOf("terrain", "invalid", worker) to "invalid-city-id",
            listOf("terrain", city, "invalid") to "invalid-worker-id",
            listOf("terrain", city, worker, "extra") to "too-many-arguments")
        var requests = 0
        for ((args, error) in cases) {
            val line = CivsDebug.lines(args) { _, _, _ -> requests++; emptyMap() }.single()
            JsonParser.parseString(line.removePrefix("ARCCIVS_DEBUG ")).asJsonObject["error"].asString shouldBe error
        }
        requests shouldBe 0
        val requested = mutableListOf<String>()
        CivsDebug.lines(listOf("all", city, worker)) { system, _, _ -> requested += system; emptyMap() }
        requested shouldBe CivsDebug.systems
        requested.size shouldBe 12
        ("autonomy" in requested) shouldBe true
        ("terrain" in requested) shouldBe false
        CivsDebug.lines(listOf("terrain", city, worker)) { system, cityId, workerId ->
            system shouldBe "terrain"
            cityId shouldBe UUID.fromString(city)
            workerId shouldBe UUID.fromString(worker)
            requests++
            emptyMap()
        }.size shouldBe 1
        requests shouldBe 1
    }

    test("native ladder cobblestone and air retain exact local collision geometry in the palette") {
        terrainFixture { world, city, worker ->
            val standing = CivPosition(8, 64, 8)
            val focus = standing.copy(y = 63)
            world.getBlockAt(8, 64, 8).blockData = Bukkit.createBlockData("minecraft:ladder[facing=north,waterlogged=false]")
            world.getBlockAt(8, 63, 8).blockData = Bukkit.createBlockData("minecraft:cobblestone")
            world.getBlockAt(8, 65, 8).type = Material.AIR
            val work = CivWorldWork(worker.id, worker.completedCycles, requireNotNull(worker.assignment), standing,
                listOf(CivPlanBlock(focus, "minecraft:cobblestone", "minecraft:air")),
                status = "digging-mine", miningStep = 83, focus = focus, miningPlanVersion = 4)
            val chunks = world.loadedChunks.map { it.x to it.z }.toSet()

            val snapshot = CivsTerrainDebug.capture(city, worker, Location(world, 8.5, 64.25, 8.5), work, 123_000)

            snapshot["source"] shouldBe "fresh-native-command"
            snapshot["captured_at_ms"] shouldBe 123_000L
            snapshot["actual"] shouldBe listOf(8.5, 64.25, 8.5)
            snapshot["focus"] shouldBe focus
            snapshot["standing"] shouldBe standing
            snapshot["geometry_version"] shouldBe 4
            snapshot["mining_step"] shouldBe 83L
            snapshot["voxel_count"] shouldBe 891
            snapshot["unavailable_count"] shouldBe 0
            snapshot["size"] shouldBe listOf(9, 11, 9)
            val ladder = terrainPaletteAt(snapshot, standing)
            ladder["block_data"] shouldBe "minecraft:ladder[facing=north,waterlogged=false]"
            ladder["climbable"] shouldBe true
            ladder["collision"] shouldBe listOf(listOf(0.0, 0.0, 13.0 / 16.0, 1.0, 1.0, 1.0))
            val cobblestone = terrainPaletteAt(snapshot, focus)
            cobblestone["block_data"] shouldBe "minecraft:cobblestone"
            cobblestone["collision"] shouldBe listOf(listOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
            val air = terrainPaletteAt(snapshot, standing.copy(y = 65))
            air["block_data"] shouldBe "minecraft:air"
            air["collision"] shouldBe emptyList<Any>()
            air["climbable"] shouldBe false
            air["hot"] shouldBe false
            air["liquid"] shouldBe false
            world.loadedChunks.map { it.x to it.z }.toSet() shouldBe chunks
            world.getBlockAt(8, 64, 8).type shouldBe Material.LADDER
            world.getBlockAt(8, 63, 8).type shouldBe Material.COBBLESTONE
            world.getBlockAt(8, 65, 8).type shouldBe Material.AIR
        }
    }

    test("an unloaded column is marked unavailable without reading blocks or requesting any chunk") {
        terrainFixture { world, city, worker ->
            val observed = spyk(world)
            every { observed.isChunkLoaded(any<Int>(), any<Int>()) } answers {
                firstArg<Int>() == 0 && secondArg<Int>() == 0
            }
            every { observed.getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
                val x = firstArg<Int>()
                val y = secondArg<Int>()
                val z = thirdArg<Int>()
                check((x shr 4) == 0 && (z shr 4) == 0) { "Unloaded terrain was read at $x,$y,$z" }
                world.getBlockAt(x, y, z)
            }

            val snapshot = CivsTerrainDebug.capture(city, worker, Location(observed, 15.5, 64.0, 8.5), null, 1_000)

            snapshot["voxel_count"] shouldBe 891
            snapshot["unavailable_count"] shouldBe 396
            val missing = terrainVoxelAt(snapshot, CivPosition(16, 64, 8))
            missing[3] shouldBe null
            missing[4] shouldBe false
            verify(exactly = 495) { observed.getBlockAt(any<Int>(), any<Int>(), any<Int>()) }
            verify(exactly = 0) { observed.getChunkAt(any<Int>(), any<Int>(), true) }
            verify(exactly = 0) { observed.loadChunk(any<Int>(), any<Int>()) }
            verify(exactly = 0) { observed.loadChunk(any<Int>(), any<Int>(), any<Boolean>()) }
        }
    }

    test("captures at either world-height boundary remain clamped below the 891 voxel limit") {
        terrainFixture { world, city, worker ->
            for (y in listOf(world.minHeight, world.maxHeight - 1)) {
                val snapshot = CivsTerrainDebug.capture(city, worker, Location(world, 8.5, y + .25, 8.5), null, 1_000)
                val bounds = snapshot["bounds"] as Map<*, *>
                val min = bounds["min"] as CivPosition
                val max = bounds["max"] as CivPosition
                (min.y >= world.minHeight) shouldBe true
                (max.y < world.maxHeight) shouldBe true
                snapshot["voxel_count"] shouldBe 486
                snapshot["size"] shouldBe listOf(9, 6, 9)
            }
        }
    }

    test("an absent bootstrap resident captures its depot without inventing an actual position") {
        terrainFixture { world, city, worker ->
            val point = CivPosition(8, 64, 8)
            val hall = city.buildings.first { it.type == BuildingType.TOWN_HALL }.copy(phase = BuildingPhase.SITE,
                control = point, entrance = point)
            val absent = worker.copy(assignment = null, cargo = null)
            val bootstrap = city.copy(buildings = listOf(hall), workers = listOf(absent), inventory = null,
                autonomy = CivAutonomy())
            val loaded = world.loadedChunks.map { it.x to it.z }.toSet()

            val snapshot = CivsTerrainDebug.capture(bootstrap, absent, null, null, 42_000)

            snapshot["error"] shouldBe null
            snapshot["actual"] shouldBe null
            snapshot["focus_origin"] shouldBe "planned-spawn"
            snapshot["capture_center"] shouldBe point
            snapshot["voxel_count"] shouldBe 891
            val chunk = (snapshot["chunks"] as List<*>).single() as Map<*, *>
            chunk["loaded"] shouldBe true
            chunk["load_level"] shouldBe org.bukkit.Chunk.LoadLevel.ENTITY_TICKING
            world.loadedChunks.map { it.x to it.z }.toSet() shouldBe loaded
        }
    }
})

private fun terrainFixture(run: (World, Settlement, CivWorker) -> Unit) {
    MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
        val world = CivsWorksiteWorldFixture("terrain-debug").also(paper.server::addWorld)
        world.loadChunk(0, 0)
        val mine = spatialBuilding("medievaloak", "miner", 1, CivPosition(8, 64, 8))
        val worker = spatialWorker(mine, "IRON_PICKAXE")
        run(world, spatialCity(world.uid, listOf(mine), worker), worker)
    }
}

private fun terrainVoxelAt(snapshot: Map<String, Any?>, point: CivPosition): List<*> {
    val min = (snapshot["bounds"] as Map<*, *>)["min"] as CivPosition
    return (snapshot["voxels"] as List<*>).map { it as List<*> }.first {
        it[0] == point.x - min.x && it[1] == point.y - min.y && it[2] == point.z - min.z
    }
}

private fun terrainPaletteAt(snapshot: Map<String, Any?>, point: CivPosition): Map<*, *> {
    val voxel = terrainVoxelAt(snapshot, point)
    voxel[4] shouldBe true
    return (snapshot["palette"] as List<*>)[voxel[3] as Int] as Map<*, *>
}
