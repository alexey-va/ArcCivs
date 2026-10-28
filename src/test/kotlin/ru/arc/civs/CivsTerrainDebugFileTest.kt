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
import org.bukkit.command.ConsoleCommandSender
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

class CivsTerrainDebugFileTest : FunSpec({
    test("native capture and deep detachment finish on Main before asynchronous serialization and file IO") {
        terrainFileFixture { root, world, city, worker ->
            val origin = CivPosition(8, 64, 8)
            world.getBlockAt(8, 64, 8).type = Material.STONE_BRICKS
            val capture = CivsTerrainDebug.capture(city, worker, Location(world, 8.5, 64.0, 8.5), null, 1_000, origin)
            val executor = TerrainFileExecutor()
            val files = CivsTerrainDebugFile(root, executor)
            var captures = 0

            val result = files.save {
                Bukkit.isPrimaryThread() shouldBe true
                captures++
                capture
            }

            captures shouldBe 1
            executor.pending.size shouldBe 1
            result.isDone shouldBe false
            Files.exists(root.resolve("debug")) shouldBe false
            @Suppress("UNCHECKED_CAST")
            val sourceVoxels = capture["voxels"] as MutableList<List<Any?>>
            sourceVoxels.clear()
            @Suppress("UNCHECKED_CAST")
            val sourcePalette = capture["palette"] as List<MutableMap<String, Any?>>
            sourcePalette.forEach { it["block_data"] = "MUTATED_PRIVATE_VALUE" }
            world.getBlockAt(8, 64, 8).type = Material.AIR
            executor.runNext()

            result.join() shouldBe Path.of("debug", "terrain", "latest.json")
            val document = JsonParser.parseString(Files.readString(root.resolve(result.join()))).asJsonObject
            document["voxel_count"].asInt shouldBe 891
            document["voxels"].asJsonArray.size() shouldBe 891
            val voxel = document["voxels"].asJsonArray.first { row -> row.asJsonArray.let {
                it[0].asInt == 4 && it[1].asInt == 5 && it[2].asInt == 4
            } }.asJsonArray
            document["palette"].asJsonArray[voxel[3].asInt].asJsonObject["block_data"].asString shouldBe "minecraft:stone_bricks"
            document["bounds"].asJsonObject["min"].asJsonObject["x"].asInt shouldBe 4
            document["schema"].asInt shouldBe 1
            document["state"].asString shouldBe "captured"
        }
    }

    test("the saved file contains only the selected capture and no resident inventories or other city data") {
        terrainFileFixture { root, world, city, worker ->
            val other = worker.copy(id = UUID.randomUUID(), name = "OTHER_PRIVATE_RESIDENT")
            val selected = worker.copy(name = "SELECTED_PRIVATE_NAME", items = listOf(CivResidentItem("DIAMOND", 64)))
            val source = city.copy(name = "PRIVATE_CITY_NAME", workers = listOf(selected, other), stock = Stock(wood = 12345))
            val executor = TerrainFileExecutor()
            val result = CivsTerrainDebugFile(root, executor).save {
                CivsTerrainDebug.capture(source, selected, null, null, 1_000, CivPosition(8, 64, 8))
            }
            executor.runNext()
            val encoded = Files.readString(root.resolve(result.join()))
            for (secret in listOf(other.id.toString(), city.owner.toString(), "OTHER_PRIVATE_RESIDENT", "SELECTED_PRIVATE_NAME",
                "PRIVATE_CITY_NAME", "DIAMOND", "12345", "inventory", "entities", "nbt")) encoded.contains(secret) shouldBe false
            val document = JsonParser.parseString(encoded).asJsonObject
            document["city"].asString shouldBe city.id.toString()
            document["worker"].asString shouldBe worker.id.toString()
            document["voxel_count"].asInt shouldBe 891
        }
    }

    test("native boundary and worker refusals never queue IO or create a file") {
        terrainFileFixture { root, world, city, worker ->
            val executor = TerrainFileExecutor()
            val files = CivsTerrainDebugFile(root, executor)
            for ((resident, point, reason) in listOf(
                Triple(worker, city.center.copy(x = city.center.x + city.radius), "capture-outside-city"),
                Triple(worker, CivPosition(8, world.minHeight, 8), "capture-bounds-outside-world"),
                Triple(worker.copy(id = UUID.randomUUID()), CivPosition(8, 64, 8), "worker-not-found"))) {
                terrainFileFailure(files.save { CivsTerrainDebug.capture(city, resident, null, null, 1_000, point) }) shouldBe reason
            }
            executor.pending.size shouldBe 0
            Files.exists(root.resolve("debug")) shouldBe false
        }
    }

    test("an unloaded required chunk refuses the file before any block reads or chunk loading") {
        terrainFileFixture { root, world, city, worker ->
            val observed = spyk(world)
            every { observed.isChunkLoaded(any<Int>(), any<Int>()) } answers {
                firstArg<Int>() == 0 && secondArg<Int>() == 0
            }
            val executor = TerrainFileExecutor()
            val result = CivsTerrainDebugFile(root, executor).save {
                CivsTerrainDebug.capture(city, worker, Location(observed, 8.5, 64.0, 8.5), null, 1_000, CivPosition(15, 64, 8))
            }
            terrainFileFailure(result) shouldBe "chunks-unloaded"
            executor.pending.size shouldBe 0
            Files.exists(root.resolve("debug")) shouldBe false
            verify(exactly = 0) { observed.getBlockAt(any<Int>(), any<Int>(), any<Int>()) }
            verify(exactly = 0) { observed.getChunkAt(any<Int>(), any<Int>(), true) }
            verify(exactly = 0) { observed.loadChunk(any<Int>(), any<Int>()) }
            verify(exactly = 0) { observed.loadChunk(any<Int>(), any<Int>(), any<Boolean>()) }
        }
    }

    test("the command reports capture map errors and never writes or replaces latest json") {
        terrainFileFixture { root, _, city, worker ->
            val executor = TerrainFileExecutor()
            val files = CivsTerrainDebugFile(root, executor)
            val scheduler = TestTaskScheduler()
            val tasks = LifecycleTaskScope(scheduler)
            val messages = mutableListOf<String>()
            val console = mockk<ConsoleCommandSender> {
                every { hasPermission(any<String>()) } returns true
                every { sendMessage(capture(messages)) } returns Unit
            }
            var reason = "chunks-unloaded"
            val command = CivsCommand(mockk(), mockk(), mockk(), tasks,
                debugTerrainAt = { _, _, _ -> mapOf("error" to reason) }, saveTerrainCapture = files::save)
            val args = arrayOf("debug-terrain-file-at", city.id.toString(), worker.id.toString(), "8", "64", "8")
            command.onCommand(console, mockk(), "civs", args)
            scheduler.executeImmediate()
            executor.pending.size shouldBe 0
            Files.exists(root.resolve("debug")) shouldBe false
            Files.createDirectories(root.resolve("debug/terrain"))
            val previous = root.resolve("debug/terrain/latest.json")
            Files.writeString(previous, "previous-safe-capture")
            reason = "capture-outside-city"
            command.onCommand(console, mockk(), "civs", args)
            scheduler.executeImmediate()
            executor.pending.size shouldBe 0
            Files.readString(previous) shouldBe "previous-safe-capture"
            messages.map { JsonParser.parseString(it.removePrefix("ARCCIVS_TERRAIN_FILE ")).asJsonObject.let { receipt ->
                receipt["state"].asString shouldBe "failed"
                receipt.has("path") shouldBe false
                receipt["error"].asString
            } } shouldBe listOf("chunks-unloaded", "capture-outside-city")
            tasks.close()
        }
    }

    test("one pending write and a monotonic five second cooldown bound capture admission and replace one file") {
        terrainFileFixture { root, world, city, worker ->
            val executor = TerrainFileExecutor()
            var now = 0L
            var captures = 0
            val files = CivsTerrainDebugFile(root, executor) { now }
            val capture = {
                captures++
                CivsTerrainDebug.capture(city, worker, null, null, captures.toLong(), CivPosition(8, 64, 8))
            }
            val first = files.save(capture)
            terrainFileFailure(files.save(capture)) shouldBe "terrain-file-busy"
            captures shouldBe 1
            executor.runNext()
            terrainFileFailure(files.save(capture)) shouldBe "terrain-file-throttled"
            now = 4_999_999_999
            terrainFileFailure(files.save(capture)) shouldBe "terrain-file-throttled"
            captures shouldBe 1
            now = 5_000_000_000
            val second = files.save(capture)
            executor.runNext()
            second.join() shouldBe first.join()
            captures shouldBe 2
            JsonParser.parseString(Files.readString(root.resolve(second.join()))).asJsonObject["captured_at_ms"].asLong shouldBe 2
            Files.list(root.resolve("debug/terrain")).use { it.map { file -> file.fileName.toString() }.toList() } shouldBe listOf("latest.json")
        }
    }

    test("an unsafe path reports an asynchronous failure and releases admission for a later retry") {
        terrainFileFixture { root, world, city, worker ->
            val executor = TerrainFileExecutor()
            var now = 0L
            val files = CivsTerrainDebugFile(root, executor) { now }
            val capture = { CivsTerrainDebug.capture(city, worker, null, null, 1_000, CivPosition(8, 64, 8)) }
            Files.writeString(root.resolve("debug"), "occupied by a file")
            val failed = files.save(capture)
            failed.isDone shouldBe false
            executor.runNext()
            terrainFileFailure(failed) shouldBe "terrain-file-unsafe-path"
            Files.delete(root.resolve("debug"))
            now = 5_000_000_000
            val retry = files.save(capture)
            executor.runNext()
            Files.isRegularFile(root.resolve(retry.join())) shouldBe true
        }
    }

    test("a linked debug directory is refused without creating anything outside the plugin root") {
        terrainFileFixture { root, world, city, worker ->
            val outside = Files.createTempDirectory("arccivs-terrain-outside-test-")
            try {
                Files.createSymbolicLink(root.resolve("debug"), outside)
                val executor = TerrainFileExecutor()
                val result = CivsTerrainDebugFile(root, executor).save {
                    CivsTerrainDebug.capture(city, worker, null, null, 1_000, CivPosition(8, 64, 8))
                }
                executor.runNext()
                terrainFileFailure(result) shouldBe "terrain-file-unsafe-path"
                Files.list(outside).use { it.count() } shouldBe 0
            } finally { Files.deleteIfExists(root.resolve("debug")); outside.toFile().deleteRecursively() }
        }
    }

    test("oversized JSON fails on the worker before creating a debug file") {
        terrainFileFixture { root, world, city, worker ->
            val executor = TerrainFileExecutor()
            val capture = CivsTerrainDebug.capture(city, worker, null, null, 1_000, CivPosition(8, 64, 8)).toMutableMap()
            capture["palette"] = listOf(mapOf("block_data" to "x".repeat(2 * 1024 * 1024)))
            val result = CivsTerrainDebugFile(root, executor).save { capture }
            executor.runNext()
            terrainFileFailure(result) shouldBe "terrain-file-too-large"
            Files.exists(root.resolve("debug")) shouldBe false
        }
    }

    test("unexpected data and live Bukkit values cannot cross the immutable capture boundary") {
        terrainFileFixture { root, world, city, worker ->
            val executor = TerrainFileExecutor()
            val capture = CivsTerrainDebug.capture(city, worker, null, null, 1_000, CivPosition(8, 64, 8))
            val files = CivsTerrainDebugFile(root, executor)
            for (invalid in listOf(capture + ("inventory" to listOf("secret")), capture + ("standing" to world),
                capture + ("voxel_count" to 892), capture + ("unavailable_count" to 1))) {
                terrainFileFailure(files.save { invalid }) shouldBe "invalid-terrain-capture"
            }
            executor.pending.size shouldBe 0
            Files.exists(root.resolve("debug")) shouldBe false
        }
    }

    test("a rejected executor and an executor incorrectly running on Main expose failures without IO") {
        terrainFileFixture { root, world, city, worker ->
            val capture = { CivsTerrainDebug.capture(city, worker, null, null, 1_000, CivPosition(8, 64, 8)) }
            val rejected = CivsTerrainDebugFile(root, Executor { throw RejectedExecutionException("full") }).save(capture)
            shouldThrow<CompletionException> { rejected.join() }.cause?.javaClass shouldBe RejectedExecutionException::class.java
            val inline = CivsTerrainDebugFile(root, Executor(Runnable::run)).save(capture)
            shouldThrow<CompletionException> { inline.join() }.cause?.javaClass shouldBe IllegalStateException::class.java
            Files.exists(root.resolve("debug")) shouldBe false
        }
    }

    test("off-thread callers cannot perform a native capture") {
        terrainFileFixture { root, _, _, _ ->
            var captures = 0
            val failure = AtomicReference<Throwable>()
            val files = CivsTerrainDebugFile(root, TerrainFileExecutor())
            Thread({ runCatching { files.save { captures++; emptyMap() } }.exceptionOrNull()?.let(failure::set) }).apply { start(); join() }
            failure.get().javaClass shouldBe IllegalStateException::class.java
            captures shouldBe 0
            Files.exists(root.resolve("debug")) shouldBe false
        }
    }
})

private fun terrainFileFailure(result: CompletableFuture<*>): String? =
    CivsDiagnostics.rejection(shouldThrow<CompletionException> { result.join() })?.reason

private class TerrainFileExecutor : Executor {
    val pending = mutableListOf<Runnable>()
    override fun execute(command: Runnable) { pending += command }
    fun runNext() { Thread(pending.removeAt(0), "terrain-file-test").apply { start(); join() } }
}

private fun terrainFileFixture(run: (Path, World, Settlement, CivWorker) -> Unit) {
    val root = Files.createTempDirectory("arccivs-terrain-file-test-")
    try {
        MockBukkitTestRuntime.open(CivsWorksiteServerFixture()).use { paper ->
            val world = CivsWorksiteWorldFixture("terrain-file").also(paper.server::addWorld)
            world.loadChunk(0, 0)
            val building = spatialBuilding("medievaloak", "miner", 1, CivPosition(8, 64, 8))
            val worker = spatialWorker(building, "IRON_PICKAXE")
            run(root, world, spatialCity(world.uid, listOf(building), worker), worker)
        }
    } finally { root.toFile().deleteRecursively() }
}
