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
    test("hotload requires a console administrator and exact prepare or status syntax") {
        val messages = mutableListOf<String>()
        var prepares = 0
        var statuses = 0
        val router = CivsCommand(mockk(), mockk(), mockk(), mockk(),
            prepareHotload = { prepares++; mapOf("state" to "preparing") },
            hotloadStatus = { statuses++; mapOf("state" to "active") })
        val command = mockk<Command>()
        for ((sender, denied) in listOf(
            mockk<Player>() to null,
            mockk<CommandSender>() to null,
            mockk<ConsoleCommandSender>() to "arccivs.use",
            mockk<ConsoleCommandSender>() to "arccivs.admin",
        )) {
            every { sender.sendMessage(capture(messages)) } returns Unit
            every { sender.hasPermission("arccivs.use") } returns true
            every { sender.hasPermission("arccivs.admin") } returns true
            if (denied != null) every { sender.hasPermission(denied) } returns false
            router.onCommand(sender, command, "civs", arrayOf("hotload", "prepare")) shouldBe true
            JsonParser.parseString(messages.last().removePrefix("ARCCIVS_HOTLOAD ")).asJsonObject["error"].asString shouldBe "not-authorized"
        }
        val console = mockk<ConsoleCommandSender> {
            every { hasPermission(any<String>()) } returns true
            every { sendMessage(capture(messages)) } returns Unit
        }
        for (args in listOf(arrayOf("hotload"), arrayOf("hotload", "other"), arrayOf("hotload", "prepare", "extra"))) {
            router.onCommand(console, command, "civs", args)
            JsonParser.parseString(messages.last().removePrefix("ARCCIVS_HOTLOAD ")).asJsonObject["error"].asString shouldBe "invalid-hotload-arguments"
        }
        prepares shouldBe 0
        statuses shouldBe 0
    }

    test("hotload receipts remain available after prepare closes the runtime task scope") {
        val messages = mutableListOf<String>()
        val console = mockk<ConsoleCommandSender> {
            every { hasPermission(any<String>()) } returns true
            every { sendMessage(capture(messages)) } returns Unit
        }
        val tasks = ru.arc.core.LifecycleTaskScope(ru.arc.core.TestTaskScheduler())
        var state = "active"
        var prepares = 0
        val router = CivsCommand(mockk(), mockk(), mockk(), tasks,
            prepareHotload = { prepares++; tasks.close(); state = "preparing"; mapOf("state" to state, "version" to "0.9.3") },
            hotloadStatus = { mapOf("state" to state, "version" to "0.9.3") })
        router.onCommand(console, mockk(), "civs", arrayOf("hotload", "prepare")) shouldBe true
        state = "ready"
        router.onCommand(console, mockk(), "civs", arrayOf("hotload", "status")) shouldBe true
        prepares shouldBe 1
        messages.map { JsonParser.parseString(it.removePrefix("ARCCIVS_HOTLOAD ")).asJsonObject["state"].asString } shouldBe
            listOf("preparing", "ready")
        JsonParser.parseString(messages.last().removePrefix("ARCCIVS_HOTLOAD ")).asJsonObject["version"].asString shouldBe "0.9.3"
        router.onTabComplete(console, mockk(), "civs", arrayOf("")) shouldBe listOf("hotload")
        router.onTabComplete(console, mockk(), "civs", arrayOf("hotload", "")) shouldBe listOf("prepare", "status")
    }

    test("prepared runtime rejects gameplay commands before accessing the stopped service") {
        val player = mockk<Player>(relaxed = true) { every { hasPermission(any<String>()) } returns true }
        val text = mockk<CivsText>(relaxed = true)
        val router = CivsCommand(mockk(), mockk(), text, mockk(), hotloadStatus = { mapOf("state" to "preparing") })
        for (args in listOf(emptyArray(), arrayOf("preview"), arrayOf("reload"), arrayOf("debug", "all")))
            router.onCommand(player, mockk(), "civs", args) shouldBe true
        verify(exactly = 4) { text.error(player, "reason.not-ready") }
        CivsCommand.completions(true, true, arrayOf("h"), console = false) shouldBe emptyList()
        CivsCommand.completions(true, true, arrayOf("h"), console = true) shouldBe listOf("hotload")
        CivsCommand.completions(true, false, arrayOf("hotload", ""), console = true) shouldBe emptyList()
    }

    test("construction retry accepts only console administrators and an exact city UUID") {
        val calls = mutableListOf<UUID>()
        val router = CivsCommand(mockk(), mockk(), mockk(relaxed = true), mockk(relaxed = true), retryConstruction = { city ->
            calls += city; java.util.concurrent.CompletableFuture<Unit>()
        })
        val console = mockk<ConsoleCommandSender>(relaxed = true) { every { hasPermission(any<String>()) } returns true }
        val player = mockk<Player>(relaxed = true) { every { hasPermission(any<String>()) } returns true }
        val city = UUID.randomUUID(); val command = mockk<Command>()
        val args = arrayOf("retry-construction", city.toString())
        router.onCommand(player, command, "civs", args)
        router.onCommand(console, command, "civs", args + "extra")
        router.onCommand(console, command, "civs", arrayOf("retry-construction", "bad"))
        every { console.hasPermission("arccivs.admin") } returns false
        router.onCommand(console, command, "civs", args)
        calls shouldBe emptyList()
        every { console.hasPermission("arccivs.admin") } returns true
        router.onCommand(console, command, "civs", args)
        calls shouldBe listOf(city)
    }

    test("resurrection scroll issuance is admin self-only and never drops items from a full inventory") {
        ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation {
            ru.arc.paper.testing.MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.createSimplePlugin("ScrollCommand")
                val player = paper.addPlayer("Issuer").apply { addAttachment(plugin, "arccivs.use", true) }
                val other = paper.addPlayer("Other")
                val text = CivsText.fromYaml(civsLocaleSource())
                val router = CivsCommand(mockk(relaxed = true), mockk(relaxed = true), text, mockk(relaxed = true))
                val command = mockk<Command>()
                val args = arrayOf("give-resurrection-scroll")
                router.onCommand(player, command, "civs", args)
                player.inventory.isEmpty shouldBe true
                player.addAttachment(plugin, "arccivs.admin", true)
                router.onCommand(player, command, "civs", args + other.name)
                player.inventory.isEmpty shouldBe true
                other.inventory.isEmpty shouldBe true
                router.onCommand(player, command, "civs", args)
                player.inventory.storageContents.filterNotNull().single().let {
                    it.amount shouldBe 1
                    CivsResurrectionScroll.matches(it) shouldBe true
                }
                for (slot in 0..35) player.inventory.setItem(slot, org.bukkit.inventory.ItemStack(org.bukkit.Material.STONE, 64))
                router.onCommand(player, command, "civs", args)
                player.inventory.storageContents.all { it?.type == org.bukkit.Material.STONE && it.amount == 64 } shouldBe true
                other.inventory.isEmpty shouldBe true
                CivsCommand.completions(true, false, arrayOf("give")) shouldBe emptyList()
                CivsCommand.completions(true, true, arrayOf("give")) shouldBe listOf("give-resurrection-scroll")
            }
        }
    }

    test("autonomous placement is admin-only and preserves the whole village name") {
        val player = mockk<Player>(relaxed = true) { every { hasPermission(any<String>()) } returns true }
        val host = mockk<CivsUiHost>(relaxed = true)
        val ui = mockk<CivsUi>(relaxed = true)
        every { host.beginAutonomousSelection(any(), any(), any()) } returns java.util.concurrent.CompletableFuture<Unit>()
        val router = CivsCommand(host, ui, mockk(relaxed = true), mockk(relaxed = true))
        val command = mockk<Command>()
        router.onCommand(player, command, "civs", arrayOf("autonomous"))
        verify(exactly = 1) { ui.openAutonomous(player, null) }
        router.onCommand(player, command, "civs", arrayOf("autonomous", "nordic", "Free", "Village"))
        verify(exactly = 1) { host.beginAutonomousSelection(player, "Free Village", "nordic") }
        every { player.hasPermission("arccivs.admin") } returns false
        router.onCommand(player, command, "civs", arrayOf("autonomous"))
        router.onCommand(player, command, "civs", arrayOf("autonomous", "nordic", "Other"))
        verify(exactly = 1) { ui.openAutonomous(player, null) }
        verify(exactly = 1) { host.beginAutonomousSelection(any(), any(), any()) }
        CivsCommand.completions(true, false, arrayOf("aut")) shouldBe emptyList()
        CivsCommand.completions(true, true, arrayOf("aut")) shouldBe listOf("autonomous")
        CivsCommand.completions(true, true, arrayOf("autonomous", "n")) shouldBe listOf("nordic")
    }

    test("warehouse maintenance accepts only console administrators and exact city building UUIDs") {
        val calls = mutableListOf<Pair<UUID, UUID>>()
        val router = CivsCommand(mockk(), mockk(), mockk(relaxed = true), mockk(relaxed = true), repairStorageRacks = { city, building ->
            calls += city to building; java.util.concurrent.CompletableFuture<Map<String, Int>>()
        })
        val console = mockk<ConsoleCommandSender>(relaxed = true) { every { hasPermission(any<String>()) } returns true }
        val player = mockk<Player>(relaxed = true) { every { hasPermission(any<String>()) } returns true }
        val city = UUID.randomUUID(); val building = UUID.randomUUID(); val command = mockk<Command>()
        val args = arrayOf("repair-warehouse", city.toString(), building.toString())
        router.onCommand(player, command, "civs", args)
        router.onCommand(console, command, "civs", args + "extra")
        router.onCommand(console, command, "civs", arrayOf("repair-warehouse", "bad", building.toString()))
        every { console.hasPermission("arccivs.admin") } returns false
        router.onCommand(console, command, "civs", args)
        calls shouldBe emptyList()
        every { console.hasPermission("arccivs.admin") } returns true
        router.onCommand(console, command, "civs", args)
        calls shouldBe listOf(city to building)
        router.onCommand(console, command, "civs", arrayOf("repair-storage-racks", city.toString(), building.toString()))
        calls shouldBe listOf(city to building, city to building)
    }

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

    test("all debug emits one parseable console line for each of the twelve supported systems") {
        val systems = listOf("overview", "autonomy", "citizens", "worksites", "supply", "storage", "chunks", "construction", "resources", "residents", "research", "industry")
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
        messages.size shouldBe 12
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

    test("layout export is asynchronous and permission failures stay bounded and structured") {
        val city = UUID.randomUUID()
        for ((use, admin) in listOf(false to true, true to false, true to true)) {
            val messages = mutableListOf<String>()
            val player = mockk<Player>(relaxed = true) {
                every { hasPermission("arccivs.use") } returns use
                every { hasPermission("arccivs.admin") } returns admin
                every { sendMessage(capture(messages)) } returns Unit
            }
            val scheduler = ru.arc.core.TestTaskScheduler()
            val tasks = ru.arc.core.LifecycleTaskScope(scheduler)
            val completion = java.util.concurrent.CompletableFuture<Map<String, Any?>>()
            val requests = mutableListOf<Pair<UUID, Boolean>>()
            val router = CivsCommand(mockk(), mockk(), mockk(), tasks, debugLayout = { id, terrain ->
                requests += id to terrain
                completion
            })
            router.onCommand(player, mockk(), "civs", arrayOf("debug", "layout", city.toString(), "terrain")) shouldBe true
            if (use && admin) {
                requests shouldBe listOf(city to true)
                messages shouldBe emptyList()
                completion.complete(mapOf("city" to city, "path" to "debug/layout/$city.json", "bytes" to 120))
                scheduler.executeImmediate()
                val receipt = JsonParser.parseString(messages.single().removePrefix("ARCCIVS_DEBUG ")).asJsonObject
                receipt["status"].asString shouldBe "completed"
                receipt["path"].asString shouldBe "debug/layout/$city.json"
                receipt["bytes"].asInt shouldBe 120
            } else {
                requests shouldBe emptyList()
                val denied = JsonParser.parseString(messages.single().removePrefix("ARCCIVS_DEBUG ")).asJsonObject
                denied["system"].asString shouldBe "layout"
                denied["status"].asString shouldBe "failed"
                denied["error"].asString shouldBe "not-authorized"
            }
            tasks.close()
        }
    }

    test("layout export rejects malformed arguments before invoking its provider and reports export failures") {
        val city = UUID.randomUUID()
        val messages = mutableListOf<String>()
        val sender = mockk<ConsoleCommandSender> {
            every { hasPermission(any<String>()) } returns true
            every { sendMessage(capture(messages)) } returns Unit
        }
        val scheduler = ru.arc.core.TestTaskScheduler()
        val tasks = ru.arc.core.LifecycleTaskScope(scheduler)
        var requests = 0
        val router = CivsCommand(mockk(), mockk(), mockk(), tasks, debugLayout = { _, _ ->
            requests++
            java.util.concurrent.CompletableFuture.failedFuture(CivsRejection("city-not-found"))
        })
        for ((args, error) in listOf(
            arrayOf("debug", "layout") to "invalid-layout-arguments",
            arrayOf("debug", "layout", "1-1-1-1-1") to "invalid-city-id",
            arrayOf("debug", "layout", city.toString(), "other") to "invalid-layout-arguments",
            arrayOf("debug", "layout", city.toString(), "terrain", "extra") to "invalid-layout-arguments",
        )) {
            router.onCommand(sender, mockk(), "civs", args)
            scheduler.executeImmediate()
            JsonParser.parseString(messages.last().removePrefix("ARCCIVS_DEBUG ")).asJsonObject["error"].asString shouldBe error
        }
        requests shouldBe 0
        router.onCommand(sender, mockk(), "civs", arrayOf("debug", "layout", city.toString()))
        scheduler.executeImmediate()
        requests shouldBe 1
        JsonParser.parseString(messages.last().removePrefix("ARCCIVS_DEBUG ")).asJsonObject["error"].asString shouldBe "city-not-found"
        tasks.close()
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
        CivsCommand.completions(true, true, arrayOf("debug", "lay")) shouldBe listOf("layout")
        CivsCommand.completions(true, true, arrayOf("debug", "layout", UUID.randomUUID().toString(), "t")) shouldBe listOf("terrain")
        CivsCommand.completions(true, false, arrayOf("debug", "layout", UUID.randomUUID().toString(), "t")) shouldBe emptyList()
        val systems = listOf("all", "overview", "autonomy", "citizens", "worksites", "supply", "storage", "chunks", "construction", "resources", "residents", "research", "industry", "layout")
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
