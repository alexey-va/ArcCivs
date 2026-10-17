package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import io.mockk.every
import io.mockk.mockk
import org.bukkit.entity.Player
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import ru.arc.paper.api.ArcSchematicException
import ru.arc.paper.api.ArcSchematicFailure
import java.util.Locale
import java.util.concurrent.CompletionException
import java.io.ByteArrayInputStream

class CivsTextTest : FunSpec({
    test("bundled locales have parity and unknown client locales fall back to Russian") {
        val text = CivsText.fromYaml(civsLocaleSource())
        text.keys("ru") shouldBe text.keys("en")
        plainText(text.render("common.title", "en-US")) shouldBe "Settlements"
        plainText(text.render("common.title", "ru_RU")) shouldBe "Города"
        plainText(text.render("common.title", "ja-JP")) shouldBe "Города"
    }

    test("server force language overrides English clients and legacy client-language settings") {
        val config = YamlConfiguration().apply {
            set("locale", "ru")
            set("use-client-locale", true)
        }
        val folder = java.nio.file.Files.createTempDirectory("civs-text").toFile()
        try {
            val plugin = mockk<Plugin> {
                every { getResource("locales.yml") } answers { ByteArrayInputStream(civsLocaleSource().toByteArray()) }
                every { dataFolder } returns folder
                every { getConfig() } returns config
            }
            val player = mockk<Player> { every { locale() } returns Locale.ENGLISH }
            val text = CivsText.load(plugin)
            plainText(text.render(player, "common.title")) shouldBe "Города"
            plainText(text.render("common.title", "en")) shouldBe "Settlements"
            config.set("force-language", "ru")
            plainText(text.render(player, "common.title")) shouldBe "Города"
        } finally {
            folder.deleteRecursively()
        }
    }

    test("city names and external values cannot inject MiniMessage formatting") {
        val text = CivsText.fromYaml(civsLocaleSource())
        val name = "<red>Town</red><click:run_command:'/op a'>x</click>"
        plainText(text.render("city.intro", "en", "name" to name)) shouldBe name
        plainText(text.render("workers.assign-help", "en", "name" to name)) shouldContain name
    }

    test("missing translation rejects a catalog instead of publishing untranslated controls") {
        shouldThrow<IllegalArgumentException> {
            CivsText.fromYaml("ru:\n  title: 'Город'\nen:\n  different: 'Town'\n")
        }
    }

    test("a wrapped shared-protection rejection gives a localized action without leaking diagnostics") {
        val text = CivsText.fromYaml(civsLocaleSource())
        val player = mockk<Player> { every { locale() } returns Locale.forLanguageTag("ru-RU") }
        val failure = CompletionException(ArcSchematicException(ArcSchematicFailure.PROTECTED_AREA, "private/owner/detail"))
        plainText(text.failure(player, failure)) shouldBe "Эта область защищена. Выберите другое место."
    }

    test("construction progress has a real line break and translated phase") {
        val text = CivsText.fromYaml(civsLocaleSource())
        val value = text.render("city.construction", "en", "building" to "Farm", "done" to 2,
            "total" to 3, "state" to text.render("construction-phase.paused", "en"))
        plainText(value) shouldBe "Farm · 2/3\nPaused"
    }
})

internal fun civsLocaleSource(): String = requireNotNull(CivsTextTest::class.java.getResourceAsStream("/locales.yml"))
    .bufferedReader(Charsets.UTF_8).use { it.readText() }

internal fun plainText(component: net.kyori.adventure.text.Component): String = PlainTextComponentSerializer.plainText().serialize(component)
