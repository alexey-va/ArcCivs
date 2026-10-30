package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.key.Key
import io.mockk.every
import io.mockk.mockk
import org.bukkit.entity.Player
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import ru.arc.paper.api.ArcSchematicException
import ru.arc.paper.api.ArcSchematicFailure
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletionException
import java.io.ByteArrayInputStream

class CivsTextTest : FunSpec({
    lateinit var paper: ru.arc.paper.testing.MockBukkitTestRuntime
    beforeEach { paper = ru.arc.paper.testing.MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

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
            plainText(text.material(player, "COBBLESTONE")) shouldBe "Булыжник"
            plainText(text.material(player, "SPRUCE_FENCE")) shouldBe "Еловый забор"
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

    test("waiting inputs name the recipe materials through the existing Minecraft locale catalog") {
        val text = CivsText.fromYaml(civsLocaleSource())
        val ru = mockk<Player> { every { locale() } returns Locale.forLanguageTag("ru-RU") }
        val en = mockk<Player> { every { locale() } returns Locale.ENGLISH }
        plainText(text.status(ru, "workers.status.waiting-inputs", listOf("COBBLESTONE"))) shouldBe "Ждёт: Булыжник"
        plainText(text.status(ru, "waiting-inputs", listOf("SAND", "CHARCOAL", "SAND"))) shouldBe "Ждёт: Песок, Древесный уголь"
        plainText(text.status(en, "waiting-inputs", listOf("RAW_IRON", "CHARCOAL"))) shouldBe "Waiting for: Raw Iron, Charcoal"
        plainText(text.status(null, "waiting-inputs", listOf("GRAVEL"))) shouldBe "Ждёт: Гравий"
        plainText(text.status(ru, "waiting-inputs")) shouldBe "Ждёт сырьё"
        plainText(text.status(ru, "moving-to-tavern", listOf("BREAD"))) shouldBe "Идёт в трактир"
        plainText(text.status(ru, "moving-to-town-hall")) shouldBe "Идёт в ратушу"
    }

    test("missing translation rejects a catalog instead of publishing untranslated controls") {
        shouldThrow<IllegalArgumentException> {
            CivsText.fromYaml("ru:\n  title: 'Город'\nen:\n  different: 'Town'\n")
        }
    }

    test("resident vital glyphs follow full partial and empty values against the actual maximum") {
        val text = CivsText.fromYaml(civsLocaleSource())
        fun components(value: Component): List<Component> = listOf(value) + value.children().flatMap(::components)
        data class Case(val health: Double, val maximum: Double, val hunger: Int, val expected: String)
        val cases = listOf(
            Case(20.0, 20.0, 20, "\uE600 20/20   \uE603 20/20"),
            Case(4.0, 20.0, 4, "\uE601 4/20   \uE604 4/20"),
            Case(4.0, 28.0, 19, "\uE601 4/28   \uE604 19/20"),
            Case(20.0, 28.0, 20, "\uE601 20/28   \uE603 20/20"),
            Case(28.0, 28.0, 20, "\uE600 28/28   \uE603 20/20"),
            Case(19.99, 20.0, 1, "\uE601 19.99/20   \uE604 1/20"),
            Case(0.0, 28.0, 0, "\uE602 0/28   \uE605 0/20"),
            Case(-1.0, 20.0, -1, "\uE602 0/20   \uE605 0/20"),
            Case(30.0, 28.0, 21, "\uE600 28/28   \uE603 20/20"),
        )
        for (locale in listOf("ru", "en")) {
            val player = mockk<Player> { every { locale() } returns Locale.forLanguageTag(locale) }
            for (case in cases) {
                val value = text.residentVitals(player, case.health, case.maximum, case.hunger)
                plainText(value) shouldBe case.expected
                val icons = components(value).filterIsInstance<TextComponent>()
                    .filter { it.content().length == 1 && it.content()[0] in '\uE600'..'\uE605' }
                icons.size shouldBe 2
                icons.forEach {
                    it.color() shouldBe TextColor.color(0xFFFFFF)
                    it.font() shouldBe Key.key("minecraft:default")
                    it.decoration(TextDecoration.BOLD) shouldBe TextDecoration.State.FALSE
                }
                icons.map { plainText(it.hoverEvent()!!.value() as Component) } shouldBe
                    if (locale == "ru") listOf("Здоровье", "Сытость") else listOf("Health", "Hunger")
            }
        }
        val legacy = CivWorker(UUID(0, 1), "Resident", null)
        plainText(text.residentVitals(null, CivsResidents.health(legacy), CivsResidents.maxHealth(legacy),
            CivsResidents.hunger(legacy))) shouldBe "\uE600 20/20   \uE603 20/20"
    }

    test("a wrapped shared-protection rejection gives a localized action without leaking diagnostics") {
        val text = CivsText.fromYaml(civsLocaleSource())
        val player = mockk<Player> { every { locale() } returns Locale.forLanguageTag("ru-RU") }
        val failure = CompletionException(ArcSchematicException(ArcSchematicFailure.PROTECTED_AREA, "private/owner/detail"))
        plainText(text.failure(player, failure)) shouldBe "Эта область защищена. Выберите другое место."
    }

    test("wrapped world rejection preserves configured names as literal text") {
        val text = CivsText.fromYaml(civsLocaleSource())
        val player = mockk<Player> { every { locale() } returns Locale.forLanguageTag("ru-RU") }
        val worlds = "vanilla, <click:run_command:'/op a'>custom</click>"
        val failure = CompletionException(CivsRejection("wrong-world", mapOf(
            "world" to "<red>outside</red>", "worlds" to worlds,
        )))
        plainText(text.failure(player, failure)) shouldBe
            "В мире <red>outside</red> нельзя размещать городские постройки.\n" +
            "Разрешённые миры: $worlds.\nПерейдите в один из них и повторите выбор места."
    }

    test("material names resolve before table layout and failures retain literal quantities") {
        val text = CivsText.fromYaml(civsLocaleSource())
        val player = mockk<Player> { every { locale() } returns Locale.forLanguageTag("ru-RU") }
        val failure = text.failure(player, CivsRejection("materials-missing", mapOf("material" to "OAK_LOG", "count" to "7")))
        fun translations(component: Component): List<String> =
            listOfNotNull((component as? TranslatableComponent)?.key()) + component.children().flatMap(::translations)
        translations(failure) shouldBe emptyList()
        plainText(failure) shouldContain "Дубовое бревно × 7"
        plainText(failure) shouldContain "× 7"
        plainText(text.material(player, "COBBLESTONE_STAIRS")) shouldBe "Булыжные ступеньки"
        every { player.locale() } returns Locale.ENGLISH
        plainText(text.material(player, "COBBLESTONE_STAIRS")) shouldBe "Cobblestone Stairs"
        every { player.locale() } returns Locale.JAPANESE
        plainText(text.material(player, "COBBLESTONE_STAIRS")) shouldBe "Булыжные ступеньки"
        plainText(text.reason(player, "unsupported-material", mapOf("material" to "PRIVATE_INTERNAL_ID"))) shouldBe
            "Этот материал нельзя использовать: неизвестный материал."
    }

    test("new development and inventory states have localized messages in both catalogs") {
        val text = CivsText.fromYaml(civsLocaleSource())
        for (locale in listOf("ru", "en")) {
            val player = mockk<Player> { every { locale() } returns Locale.forLanguageTag(locale) }
            listOf("inventory-recovery", "inventory-conflict", "materials-migration-pending", "container-not-empty",
                "control-missing", "control-obstructed", "look-at-container", "container-limit", "registered-container",
                "control-marker-missing", "control-marker-ambiguous", "control-relocation-required", "control-relocation-site", "control-location-changed",
                "invalid-production", "builder-required", "storage-busy", "storage-container-invalid", "storage-empty",
                "invalid-storage-mode", "storage-transaction-too-large",
                "delete-name-mismatch", "delete-city-changed", "stale-city", "city-deleted").forEach { reason ->
                (plainText(text.reason(player, reason)) != plainText(text.render(player, "common.error"))) shouldBe true
            }
            plainText(text.render(player, "building-phase.site")).isNotBlank() shouldBe true
            plainText(text.render(player, "construction-phase.waiting_materials")).isNotBlank() shouldBe true
            plainText(text.render(player, "guide.open")) shouldBe if (locale == "ru") "Гайд ›" else "Guide ›"
            plainText(text.render(player, "delete.stock-help")) shouldContain if (locale == "ru") "будут потеряны" else "will be lost"
            plainText(text.building(player, BuildingType.BUILDER_HUT)).isNotBlank() shouldBe true
            plainText(text.render(player, "selection.hint")) shouldContain if (locale == "ru") "ПКМ по блоку" else "Right-click a block"
            plainText(text.reason(player, "control-missing")) shouldContain if (locale == "ru") "координаты блока управления" else "coordinates in the building menu"
            plainText(text.reason(player, "control-relocation-required")) shouldContain if (locale == "ru") "Перенести блок управления" else "Relocate control block"
            plainText(text.reason(player, "control-position-missing", mapOf("x" to "-15", "y" to "68", "z" to "42"))) shouldContain "-15, 68, 42"
        }
    }

    test("feedback uses semantic colors without recoloring ordinary menu text or parsing names") {
        val text = CivsText.fromYaml(civsLocaleSource())
        val player = mockk<Player> { every { locale() } returns Locale.forLanguageTag("ru") }
        val name = "<red>Town</red>"
        val success = text.success(player, "delete.success", "name" to name)
        fun colors(value: Component): List<net.kyori.adventure.text.format.TextColor?> =
            listOf(value.color()) + value.children().flatMap(::colors)
        colors(success).all { it == CivsText.SUCCESS } shouldBe true
        plainText(success) shouldContain name
        colors(text.info(player, "panel.opened")).all { it == CivsText.INFO } shouldBe true
        colors(text.warning(player, "panel.cancelled")).all { it == CivsText.WARNING } shouldBe true
        colors(text.failure(player, CivsRejection("materials-missing", mapOf("material" to "OAK_LOG", "count" to "7"))))
            .all { it == CivsText.ERROR } shouldBe true
        text.render(player, "delete.success", "name" to name).color() shouldBe CivsText.BODY
        text.render(player, "city.title").color() shouldBe CivsText.BODY
        for (locale in listOf("ru", "en")) {
            every { player.locale() } returns Locale.forLanguageTag(locale)
            for (key in listOf("founding.name-help", "preview.move-hint", "selection.hint", "panel.move-opened", "panel.pick-hint", "buildings.site-help")) {
                val line = plainText(text.render(player, key)).lowercase()
                line.contains("origin") shouldBe false
                line.contains("existing barrel") shouldBe false
                line.contains("установленную бочку") shouldBe false
            }
        }
    }

    test("construction progress has a real line break and translated phase") {
        val text = CivsText.fromYaml(civsLocaleSource())
        val value = text.render("city.construction", "en", "building" to "Farm", "done" to 2,
            "total" to 3, "state" to text.render("construction-phase.paused", "en"))
        plainText(value) shouldBe "Farm · 2/3\nPaused"
    }

    test("control labels use three localized lines and distinguish saved work assignments from construction") {
        val text = CivsText.fromYaml(civsLocaleSource())
        val player = mockk<Player> { every { locale() } returns Locale.forLanguageTag("ru-RU") }
        val building = CivBuilding(UUID(0, 1), BuildingType.FARM, 2, CivPosition(0, 64, 0), 0, "fixture/farm",
            CivBox(CivPosition(0, 64, 0), CivPosition(4, 70, 4)), CivPosition(2, 64, -1), BuildingPhase.ACTIVE)
        val city = Settlement(UUID(0, 2), UUID(0, 3), "City", UUID(0, 4), CivPosition(0, 64, 0), 96,
            "fixture", Stock(), listOf(building), emptyList())
        plainText(text.buildingHologram(player, city, building)) shouldBe "Ферма\nУр. 2\nНет работников"
        val assigned = city.copy(workers = listOf(CivWorker(UUID(0, 5), "Worker", building.id)))
        plainText(text.buildingHologram(player, assigned, building)) shouldBe "Ферма\nУр. 2\nРаботников: 1"
        plainText(text.buildingHologram(player, city, building.copy(phase = BuildingPhase.SITE))) shouldBe "Ферма\nУр. 2\nНе начато"
        val order = CivConstruction(UUID(0, 6), building.id, 3, 100, 40, ConstructionPhase.CONSTRUCTING, Stock())
        plainText(text.buildingHologram(player, city.copy(construction = order), building)) shouldBe "Ферма\nУр. 2\nСтройка"
        plainText(text.buildingHologram(player, city.copy(construction = order.copy(phase = ConstructionPhase.PAUSED)), building)) shouldBe
            "Ферма\nУр. 2\nОстановлено"
        every { player.locale() } returns Locale.ENGLISH
        plainText(text.buildingHologram(player, assigned, building)) shouldBe "Farm\nLevel 2\nWorkers: 1"
        plainText(text.buildingHologram(player, city.copy(construction = order.copy(phase = ConstructionPhase.WAITING_MATERIALS)), building)) shouldBe
            "Farm\nLevel 2\nWaiting for materials"
    }
})

internal fun civsLocaleSource(): String = requireNotNull(CivsTextTest::class.java.getResourceAsStream("/locales.yml"))
    .bufferedReader(Charsets.UTF_8).use { it.readText() }

internal fun plainText(component: net.kyori.adventure.text.Component): String = PlainTextComponentSerializer.plainText().serialize(component)
