package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.command.Command
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player

class CivsCommandTest : FunSpec({
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

    test("suggestions stay within exact syntax and never request Bukkit player fallback") {
        CivsCommand.completions(false, true, arrayOf("")) shouldBe emptyList()
        CivsCommand.completions(true, false, arrayOf("")) shouldBe listOf("preview")
        CivsCommand.completions(true, true, arrayOf("r")) shouldBe listOf("reload")
        CivsCommand.completions(true, true, arrayOf("status", "")) shouldBe emptyList()
        CivsCommand.completions(true, true, arrayOf("unknown")) shouldBe emptyList()
    }
})
