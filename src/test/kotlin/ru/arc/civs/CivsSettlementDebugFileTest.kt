package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

class CivsSettlementDebugFileTest : FunSpec({
    test("the selected complete city is detached on Main and the raw file round trips as Settlement") {
        settlementFileFixture { root, city ->
            val skills = linkedMapOf("FORESTER" to 12L)
            val materials = linkedMapOf("OAK_LOG" to 17L)
            val workers = mutableListOf(CivWorker(UUID(0, 4), "Selected resident", null, skills = skills))
            val cells = mutableListOf(15 or (15 shl 5))
            val forest = CivForestryJob(UUID(0, 5), city.center, "OAK_SAPLING", listOf(city.center),
                listOf("minecraft:oak_log[axis=y]"), cells)
            val selected = city.copy(workers = workers, inventory = CivInventory(CivStorageMode.VIRTUAL, materials = materials),
                mineProgress = mapOf("retained" to 7L), forestryJobs = mapOf("retained" to forest),
                memberNames = mapOf(city.owner.toString() to "Selected owner"), revision = 42)
            val expected = Gson().fromJson(Gson().toJson(selected), Settlement::class.java)
            val executor = SettlementFileExecutor()
            var captures = 0
            val result = CivsSettlementDebugFile(root, executor).save(city.id) {
                Bukkit.isPrimaryThread() shouldBe true
                captures++
                selected
            }
            captures shouldBe 1
            result.isDone shouldBe false
            Files.exists(root.resolve("debug")) shouldBe false
            skills["FORESTER"] = 999L
            materials.clear()
            workers.clear()
            cells.clear()
            executor.runNext()
            val path = result.join()
            path shouldBe Path.of("debug", "settlement", "latest.json")
            Gson().fromJson(Files.readString(root.resolve(path)), Settlement::class.java) shouldBe expected
            Files.list(root.resolve("debug/settlement")).use { it.count() } shouldBe 1L
        }
    }

    test("wrong selection and capture failure never queue IO or postpone a valid capture") {
        settlementFileFixture { root, city ->
            val executor = SettlementFileExecutor()
            val files = CivsSettlementDebugFile(root, executor)
            settlementFileFailure(files.save(city.id) { city.copy(id = UUID(0, 88)) }) shouldBe "settlement-file-city-mismatch"
            settlementFileFailure(files.save(city.id) { throw CivsRejection("city-not-found") }) shouldBe "city-not-found"
            executor.pending.size shouldBe 0
            Files.exists(root.resolve("debug")) shouldBe false
            val valid = files.save(city.id) { city }
            executor.runNext()
            Files.isRegularFile(root.resolve(valid.join())) shouldBe true
        }
    }

    test("one pending write and a five second cooldown bound admission before capturing another city") {
        settlementFileFixture { root, city ->
            val executor = SettlementFileExecutor()
            var now = 0L
            var captures = 0
            val files = CivsSettlementDebugFile(root, executor) { now }
            val capture = { captures++; city }
            val first = files.save(city.id, capture)
            settlementFileFailure(files.save(city.id, capture)) shouldBe "settlement-file-busy"
            captures shouldBe 1
            executor.runNext()
            settlementFileFailure(files.save(city.id, capture)) shouldBe "settlement-file-throttled"
            now = 4_999_999_999
            settlementFileFailure(files.save(city.id, capture)) shouldBe "settlement-file-throttled"
            now = 5_000_000_000
            val second = files.save(city.id) { city.copy(revision = 43) }
            executor.runNext()
            second.join() shouldBe first.join()
            Gson().fromJson(Files.readString(root.resolve(second.join())), Settlement::class.java).revision shouldBe 43L
        }
    }

    test("oversized input stops the owner snapshot before queuing IO and oversized UTF8 preserves the prior file") {
        settlementFileFixture { root, city ->
            val executor = SettlementFileExecutor()
            var now = 0L
            val files = CivsSettlementDebugFile(root, executor) { now }
            settlementFileFailure(files.save(city.id) { city.copy(name = "x".repeat(CivsStore.MAX_CITY_BYTES.toInt())) }) shouldBe
                "settlement-file-too-large"
            executor.pending.size shouldBe 0
            val first = files.save(city.id) { city }
            executor.runNext()
            val target = root.resolve(first.join())
            val previous = Files.readString(target)
            now = 5_000_000_000
            val unicode = files.save(city.id) { city.copy(name = "я".repeat(CivsStore.MAX_CITY_BYTES.toInt() / 2)) }
            executor.pending.size shouldBe 1
            executor.runNext()
            settlementFileFailure(unicode) shouldBe "settlement-file-too-large"
            Files.readString(target) shouldBe previous
        }
    }

    test("cancelling the caller future keeps admission closed until its accepted write finishes") {
        settlementFileFixture { root, city ->
            val executor = SettlementFileExecutor()
            var now = 0L
            val files = CivsSettlementDebugFile(root, executor) { now }
            files.save(city.id) { city }.cancel(false) shouldBe true
            now = 5_000_000_000
            settlementFileFailure(files.save(city.id) { city }) shouldBe "settlement-file-busy"
            executor.runNext()
            val next = files.save(city.id) { city.copy(revision = 43) }
            executor.runNext()
            Gson().fromJson(Files.readString(root.resolve(next.join())), Settlement::class.java).revision shouldBe 43L
        }
    }

    test("linked output parents and linked latest files cannot write outside the plugin root") {
        settlementFileFixture { root, city ->
            val outside = Files.createTempDirectory("arccivs-settlement-outside-")
            try {
                Files.createSymbolicLink(root.resolve("debug"), outside)
                val executor = SettlementFileExecutor()
                val directory = CivsSettlementDebugFile(root, executor).save(city.id) { city }
                executor.runNext()
                settlementFileFailure(directory) shouldBe "settlement-file-unsafe-path"
                Files.list(outside).use { it.count() } shouldBe 0L
                Files.delete(root.resolve("debug"))
                Files.createDirectories(root.resolve("debug/settlement"))
                val secret = outside.resolve("untouched.json")
                Files.writeString(secret, "private existing file")
                Files.createSymbolicLink(root.resolve("debug/settlement/latest.json"), secret)
                val linked = CivsSettlementDebugFile(root, executor).save(city.id) { city }
                executor.runNext()
                shouldThrow<CompletionException> { linked.join() }
                Files.readString(secret) shouldBe "private existing file"
            } finally { outside.toFile().deleteRecursively() }
        }
    }

    test("off-thread capture inline executors and rejected executors cannot perform unsafe work") {
        settlementFileFixture { root, city ->
            var captures = 0
            val thrown = AtomicReference<Throwable>()
            val files = CivsSettlementDebugFile(root, SettlementFileExecutor())
            Thread({ runCatching { files.save(city.id) { captures++; city } }.exceptionOrNull()?.let(thrown::set) }).apply { start(); join() }
            thrown.get().javaClass shouldBe IllegalStateException::class.java
            captures shouldBe 0
            val inline = CivsSettlementDebugFile(root, Executor(Runnable::run)).save(city.id) { city }
            shouldThrow<CompletionException> { inline.join() }.cause?.javaClass shouldBe IllegalStateException::class.java
            val rejected = CivsSettlementDebugFile(root, Executor { throw RejectedExecutionException("full") }).save(city.id) { city }
            shouldThrow<CompletionException> { rejected.join() }.cause?.javaClass shouldBe RejectedExecutionException::class.java
            Files.exists(root.resolve("debug")) shouldBe false
        }
    }

    test("the existing IO lifecycle drains an accepted export after admission closes") {
        settlementFileFixture { root, city ->
            val executor = BoundedAsyncExecutor("settlement-export-test", 1, 4)
            try {
                val saved = CivsSettlementDebugFile(root, executor).save(city.id) { city }
                executor.closeAsync().join()
                Gson().fromJson(Files.readString(root.resolve(saved.join())), Settlement::class.java) shouldBe city
            } finally { executor.close() }
        }
    }
})

private fun settlementFileFailure(result: CompletableFuture<*>): String? =
    CivsDiagnostics.rejection(shouldThrow<CompletionException> { result.join() })?.reason

private class SettlementFileExecutor : Executor {
    val pending = mutableListOf<Runnable>()
    override fun execute(command: Runnable) { pending += command }
    fun runNext() { Thread(pending.removeAt(0), "settlement-file-test").apply { start(); join() } }
}

private fun settlementFileFixture(run: (Path, Settlement) -> Unit) {
    val root = Files.createTempDirectory("arccivs-settlement-file-test-")
    try {
        MockBukkitTestRuntime.open().use {
            run(root, Settlement(UUID(0, 1), UUID(0, 2), "Selected town", UUID(0, 3), CivPosition(0, 64, 0),
                96, "nordic", Stock(wood = 64), emptyList(), emptyList()))
        }
    } finally { root.toFile().deleteRecursively() }
}
