package ru.arc.civs

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.util.UUID

class CivsCommandTest : FunSpec({
    test("console settings route has a closed grammar and never accepts player authority") {
        val city = UUID.randomUUID()
        val requests = mutableListOf<Triple<UUID, CivSettlementSetting, Boolean>>()
        val router = CivsCommand(mockk(), mockk(), mockk(relaxed = true), mockk(relaxed = true), configureSetting = { id, setting, enabled ->
            requests += Triple(id, setting, enabled)
            java.util.concurrent.CompletableFuture<Unit>()
        })
        val console = mockk<ConsoleCommandSender>(relaxed = true) {
            every { hasPermission("arccivs.use") } returns true
            every { hasPermission("arccivs.admin") } returns true
        }
        val command = mockk<Command>()
        for (args in listOf(arrayOf("settings", city.toString(), "immortal-residents", "yes"),
            arrayOf("settings", "bad-uuid", "immortal-residents", "on"),
            arrayOf("settings", city.toString(), "other", "on"),
            arrayOf("settings", city.toString(), "immortal-residents", "on", "extra")))
            router.onCommand(console, command, "civs", args)
        requests shouldBe emptyList()
        val player = mockk<Player>(relaxed = true) {
            every { hasPermission(any<String>()) } returns true
        }
        val valid = arrayOf("settings", city.toString(), "immortal-residents", "on")
        router.onCommand(player, command, "civs", valid)
        requests shouldBe emptyList()
        every { console.hasPermission("arccivs.admin") } returns false
        router.onCommand(console, command, "civs", valid)
        requests shouldBe emptyList()
        every { console.hasPermission("arccivs.admin") } returns true
        router.onCommand(console, command, "civs", valid)
        requests shouldBe listOf(Triple(city, CivSettlementSetting.IMMORTAL_RESIDENTS, true))
    }
    test("the bundled city alias opens the same settlement menu as civs") {
        val definition = requireNotNull(CivsCommandTest::class.java.getResourceAsStream("/plugin.yml"))
            .bufferedReader(Charsets.UTF_8).use(YamlConfiguration::loadConfiguration)
        definition.getStringList("commands.civs.aliases") shouldBe listOf("city")
        val player = mockk<Player> { every { hasPermission("arccivs.use") } returns true }
        val ui = mockk<CivsUi>(relaxed = true)
        val router = CivsCommand(mockk(), ui, mockk(), mockk())
        val command = mockk<Command>()
        for (label in listOf("city", "civs")) router.onCommand(player, command, label, emptyArray()) shouldBe true
        verify(exactly = 2) { ui.open(player) }
    }

    test("command rejection and usage guidance have semantic feedback colors") {
        val messages = mutableListOf<net.kyori.adventure.text.Component>()
        val player = mockk<Player>(relaxed = true) {
            every { locale() } returns java.util.Locale.ENGLISH
            every { hasPermission("arccivs.use") } returns false
            every { sendMessage(capture(messages)) } returns Unit
        }
        val router = CivsCommand(mockk(), mockk(), CivsText.fromYaml(civsLocaleSource()), mockk())
        router.onCommand(player, mockk(), "city", emptyArray())
        messages.single().color() shouldBe CivsText.ERROR
        every { player.hasPermission("arccivs.use") } returns true
        router.onCommand(player, mockk(), "city", arrayOf("unknown"))
        messages.last().color() shouldBe CivsText.INFO
    }

    test("console and administrator debug routes preserve the exact system city and worker filters") {
        val city = UUID.randomUUID()
        val worker = UUID.randomUUID()
        val senders = listOf<CommandSender>(mockk<ConsoleCommandSender>(relaxed = true), mockk<Player>(relaxed = true))
        for (sender in senders) {
            val messages = mutableListOf<String>()
            every { sender.hasPermission("arccivs.use") } returns true
            every { sender.hasPermission("arccivs.admin") } returns true
            every { sender.sendMessage(capture(messages)) } returns Unit
            val requests = mutableListOf<Triple<String, UUID?, UUID?>>()
            val router = CivsCommand(mockk(), mockk(), mockk(), mockk(), debugState = { system, cityId, workerId ->
                requests += Triple(system, cityId, workerId)
                mapOf("pending" to 3, "phase" to "working")
            })
            router.onCommand(sender, mockk(), "civs", arrayOf("debug", "citizens", city.toString(), worker.toString())) shouldBe true
            requests shouldBe listOf(Triple("citizens", city, worker))
            messages.single().startsWith("ARCCIVS_DEBUG ") shouldBe true
            val payload = JsonParser.parseString(messages.single().removePrefix("ARCCIVS_DEBUG ")).asJsonObject
            payload["schema"].asInt shouldBe 1
            payload["system"].asString shouldBe "citizens"
            payload["city"].asString shouldBe city.toString()
            payload["worker"].asString shouldBe worker.toString()
            payload.getAsJsonObject("state")["pending"].asInt shouldBe 3
            payload.getAsJsonObject("state")["phase"].asString shouldBe "working"
        }
    }

    test("all debug emits one parseable console line for each of the nine supported systems") {
        val systems = listOf("overview", "citizens", "worksites", "supply", "storage", "chunks", "construction", "resources", "residents")
        val city = UUID.randomUUID()
        val worker = UUID.randomUUID()
        val messages = mutableListOf<String>()
        val sender = mockk<ConsoleCommandSender> {
            every { hasPermission("arccivs.use") } returns true
            every { hasPermission("arccivs.admin") } returns true
            every { sendMessage(capture(messages)) } returns Unit
        }
        val requests = mutableListOf<Triple<String, UUID?, UUID?>>()
        val router = CivsCommand(mockk(), mockk(), mockk(), mockk(), debugState = { system, cityId, workerId ->
            requests += Triple(system, cityId, workerId)
            mapOf("source" to system, "note" to "one line\nwith \"quotes\"", "items" to listOf(1, 2))
        })
        router.onCommand(sender, mockk(), "civs", arrayOf("debug", "all", city.toString(), worker.toString())) shouldBe true
        CivsDebug.systems shouldBe systems
        requests shouldBe systems.map { Triple(it, city, worker) }
        messages.size shouldBe 9
        messages.map { line ->
            line.startsWith("ARCCIVS_DEBUG ") shouldBe true
            line.contains('\n') shouldBe false
            val payload = JsonParser.parseString(line.removePrefix("ARCCIVS_DEBUG ")).asJsonObject
            payload["schema"].asInt shouldBe 1
            (payload["generated_at_ms"].asLong > 0) shouldBe true
            payload["city"].asString shouldBe city.toString()
            payload["worker"].asString shouldBe worker.toString()
            val system = payload["system"].asString
            val state = payload.getAsJsonObject("state")
            state["source"].asString shouldBe system
            state["note"].asString shouldBe "one line\nwith \"quotes\""
            state.getAsJsonArray("items").map { it.asInt } shouldBe listOf(1, 2)
            system
        } shouldBe systems
    }

    test("debug without filters requests the overview from the console") {
        val messages = mutableListOf<String>()
        val sender = mockk<ConsoleCommandSender> {
            every { hasPermission("arccivs.use") } returns true
            every { hasPermission("arccivs.admin") } returns true
            every { sendMessage(capture(messages)) } returns Unit
        }
        val requests = mutableListOf<Triple<String, UUID?, UUID?>>()
        val router = CivsCommand(mockk(), mockk(), mockk(), mockk(), debugState = { system, city, worker ->
            requests += Triple(system, city, worker)
            mapOf("ready" to true)
        })
        router.onCommand(sender, mockk(), "civs", arrayOf("debug")) shouldBe true
        requests shouldBe listOf(Triple("overview", null, null))
        messages.single().startsWith("ARCCIVS_DEBUG ") shouldBe true
        val payload = JsonParser.parseString(messages.single().removePrefix("ARCCIVS_DEBUG ")).asJsonObject
        payload["system"].asString shouldBe "overview"
        payload.getAsJsonObject("state")["ready"].asBoolean shouldBe true
    }

    test("debug never invokes its provider when use or administrator permission is denied") {
        for ((use, admin) in listOf(false to true, true to false)) {
            val messages = mutableListOf<net.kyori.adventure.text.Component>()
            val player = mockk<Player>(relaxed = true) {
                every { locale() } returns java.util.Locale.ENGLISH
                every { hasPermission("arccivs.use") } returns use
                every { hasPermission("arccivs.admin") } returns admin
                every { sendMessage(capture(messages)) } returns Unit
            }
            var requests = 0
            val router = CivsCommand(mockk(), mockk(), CivsText.fromYaml(civsLocaleSource()), mockk(),
                debugState = { _, _, _ -> requests++; emptyMap() })
            router.onCommand(player, mockk(), "civs", arrayOf("debug", "all")) shouldBe true
            requests shouldBe 0
            messages.single().color() shouldBe CivsText.ERROR
        }
    }

    test("suggestions stay within exact syntax and never request Bukkit player fallback") {
        CivsCommand.completions(false, true, arrayOf("")) shouldBe emptyList()
        CivsCommand.completions(true, false, arrayOf("")) shouldBe listOf("preview")
        CivsCommand.completions(true, true, arrayOf("r")) shouldBe listOf("reload")
        CivsCommand.completions(true, true, arrayOf("status", "")) shouldBe emptyList()
        CivsCommand.completions(true, true, arrayOf("unknown")) shouldBe emptyList()
        CivsCommand.completions(true, false, arrayOf("debug")) shouldBe emptyList()
        CivsCommand.completions(true, true, arrayOf("debug", "Wo")) shouldBe listOf("worksites")
        CivsCommand.completions(true, true, arrayOf("debug", "all", "")) shouldBe emptyList()
        val systems = listOf("all", "overview", "citizens", "worksites", "supply", "storage", "chunks", "construction", "resources", "residents")
        val router = CivsCommand(mockk(), mockk(), mockk(), mockk())
        for ((use, admin) in listOf(false to true, true to false, true to true)) {
            val sender = mockk<CommandSender> {
                every { hasPermission("arccivs.use") } returns use
                every { hasPermission("arccivs.admin") } returns admin
            }
            router.onTabComplete(sender, mockk(), "civs", arrayOf("debug", "")) shouldBe
                if (use && admin) systems else emptyList()
        }
    }
})
