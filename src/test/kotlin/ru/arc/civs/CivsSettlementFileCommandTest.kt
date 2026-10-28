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

class CivsSettlementFileCommandTest : FunSpec({
    test("only an authorized console with exact syntax and an active owner can request a selected city") {
        var saves = 0
        var state = "active"
        var ready = true
        val host = mockk<CivsUiHost> { every { ready() } answers { ready } }
        val command = CivsCommand(host, mockk(), mockk(), mockk(), hotloadStatus = { mapOf("state" to state) },
            saveSettlementCapture = { _, _ -> saves++; CompletableFuture() })
        val valid = arrayOf("debug-settlement-file", UUID(0, 1).toString())
        for ((sender, denied) in listOf(mockk<Player>(relaxed = true) to null, mockk<CommandSender>(relaxed = true) to null,
            mockk<ConsoleCommandSender>(relaxed = true) to "arccivs.use", mockk<ConsoleCommandSender>(relaxed = true) to "arccivs.admin")) {
            val messages = mutableListOf<String>()
            every { sender.hasPermission(any<String>()) } returns true
            if (denied != null) every { sender.hasPermission(denied) } returns false
            every { sender.sendMessage(capture(messages)) } returns Unit
            command.onCommand(sender, mockk(), "civs", valid)
            settlementFileReceipt(messages.single())["error"].asString shouldBe "not-authorized"
        }
        val messages = mutableListOf<String>()
        val console = settlementFileConsole(messages)
        for ((args, error) in listOf(valid.dropLast(1).toTypedArray() to "invalid-settlement-arguments",
            (valid + "extra") to "invalid-settlement-arguments", arrayOf(valid[0], "bad") to "invalid-city-id")) {
            command.onCommand(console, mockk(), "civs", args)
            settlementFileReceipt(messages.last())["error"].asString shouldBe error
        }
        state = "preparing"
        command.onCommand(console, mockk(), "civs", valid)
        settlementFileReceipt(messages.last())["error"].asString shouldBe "not-ready"
        state = "active"; ready = false
        command.onCommand(console, mockk(), "civs", valid)
        settlementFileReceipt(messages.last())["error"].asString shouldBe "not-ready"
        saves shouldBe 0
        CivsCommand.completions(true, false, arrayOf("debug-settlement"), true) shouldBe emptyList()
        CivsCommand.completions(true, true, arrayOf("debug-settlement"), false) shouldBe emptyList()
        CivsCommand.completions(true, true, arrayOf("debug-settlement"), true) shouldBe listOf("debug-settlement-file")
    }

    test("the callback selects one current loaded city and sends only its relative file receipt on Main") {
        val scheduler = TestTaskScheduler()
        val tasks = LifecycleTaskScope(scheduler)
        val messages = mutableListOf<String>()
        val selected = commandFileCity(UUID(0, 1), "SELECTED_PRIVATE_CITY")
        val other = commandFileCity(UUID(0, 2), "OTHER_PRIVATE_CITY")
        val host = mockk<CivsUiHost> {
            every { ready() } returns true
            every { settlements() } returns listOf(other, selected)
        }
        val completion = CompletableFuture<Path>()
        val command = CivsCommand(host, mockk(), mockk(), tasks, saveSettlementCapture = { cityId, capture ->
            cityId shouldBe selected.id
            capture() shouldBe selected
            completion
        })
        command.onCommand(settlementFileConsole(messages), mockk(), "civs", arrayOf("debug-settlement-file", selected.id.toString()))
        messages shouldBe emptyList()
        Thread({ completion.complete(Path.of("debug/settlement/latest.json")) }).apply { start(); join() }
        messages shouldBe emptyList()
        scheduler.executeImmediate()
        val receipt = settlementFileReceipt(messages.single())
        receipt.keySet() shouldBe setOf("schema", "state", "path")
        receipt["state"].asString shouldBe "saved"
        receipt["path"].asString shouldBe "debug/settlement/latest.json"
        messages.single().contains("PRIVATE_CITY") shouldBe false
        tasks.close()
    }

    test("missing cities and asynchronous failures do not expose city contents or exception messages") {
        val scheduler = TestTaskScheduler()
        val tasks = LifecycleTaskScope(scheduler)
        val messages = mutableListOf<String>()
        val host = mockk<CivsUiHost> {
            every { ready() } returns true
            every { settlements() } returns emptyList()
        }
        val missing = CivsCommand(host, mockk(), mockk(), tasks, saveSettlementCapture = { _, capture ->
            capture(); CompletableFuture.completedFuture(Path.of("unused"))
        })
        missing.onCommand(settlementFileConsole(messages), mockk(), "civs", arrayOf("debug-settlement-file", UUID(0, 1).toString()))
        scheduler.executeImmediate()
        settlementFileReceipt(messages.single())["error"].asString shouldBe "city-not-found"
        val completion = CompletableFuture<Path>()
        val failed = CivsCommand(host, mockk(), mockk(), tasks, saveSettlementCapture = { _, _ -> completion })
        failed.onCommand(settlementFileConsole(messages), mockk(), "civs", arrayOf("debug-settlement-file", UUID(0, 1).toString()))
        Thread({ completion.completeExceptionally(java.io.IOException("PRIVATE_IO_PATH")) }).apply { start(); join() }
        scheduler.executeImmediate()
        settlementFileReceipt(messages.last())["error"].asString shouldBe "settlement-file-write-failed"
        messages.last().contains("PRIVATE_IO_PATH") shouldBe false
        tasks.close()
    }

    test("lifecycle closure suppresses a stale completion without affecting terrain diagnostics") {
        val scheduler = TestTaskScheduler()
        val tasks = LifecycleTaskScope(scheduler)
        val messages = mutableListOf<String>()
        val host = mockk<CivsUiHost> { every { ready() } returns true }
        val completion = CompletableFuture<Path>()
        var terrainCalls = 0
        val command = CivsCommand(host, mockk(), mockk(), tasks, saveSettlementCapture = { _, _ -> completion },
            debugTerrainAt = { _, _, _ -> terrainCalls++; mapOf("selected-terrain" to true) })
        val console = settlementFileConsole(messages)
        command.onCommand(console, mockk(), "civs", arrayOf("debug-terrain-at", UUID(0, 1).toString(), UUID(0, 2).toString(), "8", "64", "8"))
        terrainCalls shouldBe 1
        messages.single().startsWith("ARCCIVS_TERRAIN_CAPTURE ") shouldBe true
        messages.clear()
        command.onCommand(console, mockk(), "civs", arrayOf("debug-settlement-file", UUID(0, 1).toString()))
        tasks.close()
        Thread({ completion.complete(Path.of("debug/settlement/latest.json")) }).apply { start(); join() }
        scheduler.executeImmediate()
        messages shouldBe emptyList()
    }
})

private fun commandFileCity(id: UUID, name: String) = Settlement(id, UUID(0, 3), name, UUID(0, 4), CivPosition(0, 64, 0),
    96, "nordic", Stock(), emptyList(), emptyList())

private fun settlementFileConsole(messages: MutableList<String>): ConsoleCommandSender = mockk {
    every { hasPermission(any<String>()) } returns true
    every { sendMessage(capture(messages)) } returns Unit
}

private fun settlementFileReceipt(line: String) = JsonParser.parseString(line.removePrefix("ARCCIVS_SETTLEMENT_FILE ")).asJsonObject
