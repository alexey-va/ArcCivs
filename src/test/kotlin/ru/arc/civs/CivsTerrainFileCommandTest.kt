package ru.arc.civs

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsTerrainFileCommandTest : FunSpec({
    test("terrain file command preserves console permissions exact syntax and readiness before capture admission") {
        var captures = 0
        var saves = 0
        var state = "active"
        val router = CivsCommand(mockk(), mockk(), mockk(), mockk(), hotloadStatus = { mapOf("state" to state) },
            debugTerrainAt = { _, _, _ -> captures++; emptyMap() }, saveTerrainCapture = { saves++; CompletableFuture() })
        val valid = arrayOf("debug-terrain-file-at", UUID.randomUUID().toString(), UUID.randomUUID().toString(), "8", "64", "8")
        for ((sender, denied) in listOf(mockk<Player>(relaxed = true) to null, mockk<CommandSender>(relaxed = true) to null,
            mockk<ConsoleCommandSender>(relaxed = true) to "arccivs.use", mockk<ConsoleCommandSender>(relaxed = true) to "arccivs.admin")) {
            val messages = mutableListOf<String>()
            every { sender.hasPermission(any<String>()) } returns true
            if (denied != null) every { sender.hasPermission(denied) } returns false
            every { sender.sendMessage(capture(messages)) } returns Unit
            router.onCommand(sender, mockk(), "civs", valid)
            terrainFileReceipt(messages.single())["error"].asString shouldBe "not-authorized"
        }
        val messages = mutableListOf<String>()
        val console = terrainFileConsole(messages)
        for ((args, error) in listOf(valid.dropLast(1).toTypedArray() to "invalid-terrain-arguments",
            (valid + "extra") to "invalid-terrain-arguments",
            valid.copyOf().apply { this[1] = "bad" } to "invalid-city-id",
            valid.copyOf().apply { this[2] = "bad" } to "invalid-worker-id",
            valid.copyOf().apply { this[3] = "2147483648" } to "invalid-terrain-position")) {
            router.onCommand(console, mockk(), "civs", args)
            terrainFileReceipt(messages.last())["error"].asString shouldBe error
        }
        state = "preparing"
        router.onCommand(console, mockk(), "civs", valid)
        terrainFileReceipt(messages.last())["error"].asString shouldBe "not-ready"
        captures shouldBe 0
        saves shouldBe 0
        CivsCommand.completions(true, false, arrayOf("debug-terrain-file"), true) shouldBe emptyList()
        CivsCommand.completions(true, true, arrayOf("debug-terrain-file"), false) shouldBe emptyList()
        CivsCommand.completions(true, true, arrayOf("debug-terrain-file"), true) shouldBe listOf("debug-terrain-file-at")
    }

    test("asynchronous terrain file completion reports only its path through the owner scheduler") {
        val scheduler = TestTaskScheduler()
        val tasks = LifecycleTaskScope(scheduler)
        val messages = mutableListOf<String>()
        val console = terrainFileConsole(messages)
        val city = UUID.randomUUID(); val worker = UUID.randomUUID()
        val calls = mutableListOf<Triple<UUID, UUID, CivPosition>>()
        val completion = CompletableFuture<Path>()
        val router = CivsCommand(mockk(), mockk(), mockk(), tasks, debugTerrainAt = { id, resident, point ->
            calls += Triple(id, resident, point)
            mapOf("voxels" to listOf("PRIVATE_CAPTURE_CONTENT"), "other" to "SECRET")
        }, saveTerrainCapture = { capture -> capture(); completion })
        router.onCommand(console, mockk(), "civs", arrayOf("debug-terrain-file-at", city.toString(), worker.toString(), "8", "55", "8"))
        calls shouldBe listOf(Triple(city, worker, CivPosition(8, 55, 8)))
        messages shouldBe emptyList()
        Thread({ completion.complete(Path.of("debug", "terrain", "latest.json")) }).apply { start(); join() }
        messages shouldBe emptyList()
        scheduler.executeImmediate()
        val receipt = terrainFileReceipt(messages.single())
        receipt.keySet() shouldBe setOf("schema", "state", "path")
        receipt["state"].asString shouldBe "saved"
        receipt["path"].asString shouldBe "debug/terrain/latest.json"
        messages.single().contains("PRIVATE_CAPTURE_CONTENT") shouldBe false
        messages.single().contains("SECRET") shouldBe false
        tasks.close()
    }

    test("capture refusals and asynchronous IO failures return one bounded error without private exception messages") {
        for (failure in listOf(CivsRejection("chunks-unloaded"), java.io.IOException("PRIVATE_IO_PATH"))) {
            val scheduler = TestTaskScheduler()
            val tasks = LifecycleTaskScope(scheduler)
            val messages = mutableListOf<String>()
            val completion = CompletableFuture<Path>()
            val router = CivsCommand(mockk(), mockk(), mockk(), tasks, saveTerrainCapture = { completion })
            router.onCommand(terrainFileConsole(messages), mockk(), "civs", arrayOf("debug-terrain-file-at",
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), "8", "64", "8"))
            Thread({ completion.completeExceptionally(failure) }).apply { start(); join() }
            scheduler.executeImmediate()
            val receipt = terrainFileReceipt(messages.single())
            receipt.keySet() shouldBe setOf("schema", "state", "error")
            receipt["state"].asString shouldBe "failed"
            receipt["error"].asString shouldBe if (failure is CivsRejection) "chunks-unloaded" else "terrain-file-write-failed"
            messages.single().contains("PRIVATE_IO_PATH") shouldBe false
            tasks.close()
        }
    }

    test("a completed file cannot send a stale callback after the command lifecycle closes") {
        val scheduler = TestTaskScheduler()
        val tasks = LifecycleTaskScope(scheduler)
        val messages = mutableListOf<String>()
        val completion = CompletableFuture<Path>()
        val router = CivsCommand(mockk(), mockk(), mockk(), tasks, saveTerrainCapture = { completion })
        router.onCommand(terrainFileConsole(messages), mockk(), "civs", arrayOf("debug-terrain-file-at",
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), "8", "64", "8"))
        tasks.close()
        Thread({ completion.complete(Path.of("debug/terrain/latest.json")) }).apply { start(); join() }
        scheduler.executeImmediate()
        messages shouldBe emptyList()
    }
})

private fun terrainFileConsole(messages: MutableList<String>): ConsoleCommandSender = mockk {
    every { hasPermission(any<String>()) } returns true
    every { sendMessage(capture(messages)) } returns Unit
}

private fun terrainFileReceipt(line: String) = JsonParser.parseString(line.removePrefix("ARCCIVS_TERRAIN_FILE ")).asJsonObject
