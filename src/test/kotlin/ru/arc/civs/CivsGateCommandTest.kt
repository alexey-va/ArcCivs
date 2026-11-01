package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.command.Command
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.CompletableFuture

class CivsGateCommandTest : FunSpec({
    test("gate maintenance requires console authority exact ids and an explicit preview or apply") {
        val calls = mutableListOf<Triple<UUID, UUID, String?>>()
        val router = CivsCommand(mockk(), mockk(), mockk(relaxed = true), mockk(relaxed = true), refitGate = { city, gate, proof ->
            calls += Triple(city, gate, proof)
            CompletableFuture<Map<String, Any?>>()
        })
        val console = mockk<ConsoleCommandSender>(relaxed = true) { every { hasPermission(any<String>()) } returns true }
        val player = mockk<Player>(relaxed = true) { every { hasPermission(any<String>()) } returns true }
        val city = UUID(181, 20); val gate = UUID(181, 21); val command = mockk<Command>()
        val preview = arrayOf("refit-gate", "preview", city.toString(), gate.toString())
        router.onCommand(player, command, "civs", preview)
        router.onCommand(console, command, "civs", preview + "extra")
        router.onCommand(console, command, "civs", arrayOf("refit-gate", "apply", city.toString(), gate.toString()))
        router.onCommand(console, command, "civs", arrayOf("refit-gate", "preview", "bad", gate.toString()))
        calls shouldBe emptyList()
        every { console.hasPermission("arccivs.admin") } returns false
        router.onCommand(console, command, "civs", preview)
        calls shouldBe emptyList()
        every { console.hasPermission("arccivs.admin") } returns true
        router.onCommand(console, command, "civs", preview)
        val proof = "a".repeat(64)
        router.onCommand(console, command, "civs", arrayOf("refit-gate", "apply", city.toString(), gate.toString(), proof))
        calls shouldBe listOf(Triple(city, gate, null), Triple(city, gate, proof))
        CivsCommand.completions(true, true, arrayOf("refit-g"), console = true) shouldBe listOf("refit-gate")
        CivsCommand.completions(true, true, arrayOf("refit-g"), console = false) shouldBe emptyList()
        CivsCommand.completions(true, true, arrayOf("refit-gate", ""), console = true) shouldBe listOf("preview", "apply")
    }
})
