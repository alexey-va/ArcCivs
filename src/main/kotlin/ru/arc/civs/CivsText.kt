package ru.arc.civs

import com.google.gson.JsonParser
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.text.LocaleCatalog
import ru.arc.text.LocaleRequirements
import ru.arc.text.LocalizedMiniMessage
import ru.arc.paper.api.ArcSchematicException
import ru.arc.paper.api.ArcSchematicFailure
import java.io.File
import java.util.Locale

/** One localized renderer; names and host values are always literal components. */
class CivsText private constructor(
    private val messages: Map<String, Map<String, String>>,
    private val defaultLocale: () -> String,
    private val useClientLocale: () -> Boolean,
    private val forcedLocale: () -> String?,
) {
    private val renderer = LocalizedMiniMessage(
        messages.mapValues { (_, values) -> object : LocaleCatalog {
            override fun scalar(path: String): String? = values[path]
            override fun lines(path: String): List<String>? = null
        } },
        defaultLocale = defaultLocale,
    )

    init {
        require(messages.keys == setOf("ru", "en")) { "ArcCivs requires ru and en locale catalogs" }
        require(messages.getValue("ru").keys == messages.getValue("en").keys) { "ArcCivs locale keys must match" }
        renderer.validate(LocaleRequirements(scalarPaths = messages.getValue("ru").keys))
    }

    fun render(player: Player?, key: String, vararg values: Pair<String, Any?>): Component =
        render(key, language(player), *values)

    private fun language(player: Player?): String {
        val requested = forcedLocale()?.takeIf(String::isNotBlank)
            ?: if (player != null && useClientLocale()) player.locale().toLanguageTag() else defaultLocale()
        val language = Locale.forLanguageTag(requested.replace('_', '-')).language
        return language.takeIf { it in messages }
            ?: Locale.forLanguageTag(defaultLocale().replace('_', '-')).language
    }

    fun render(key: String, locale: String? = null, vararg values: Pair<String, Any?>): Component {
        require(key in messages.getValue("ru")) { "Unknown ArcCivs locale key: $key" }
        return renderer.render(key, locale, values.associate { (name, value) ->
            name to if (value is Component) value else renderer.literal(value)
        }).colorIfAbsent(BODY).decoration(TextDecoration.ITALIC, false)
    }

    fun literal(value: Any?): Component = renderer.literal(value).color(BODY).decoration(TextDecoration.ITALIC, false)

    /** Pack glyphs arc:resident_{health,food}_{full,half,empty}; shared by dialogs and nameplates. */
    fun residentVitals(player: Player?, health: Double, maximum: Double, hunger: Int): Component {
        fun number(value: Double) = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
        fun state(value: Double, max: Double, full: String, half: String, empty: String) = when {
            value <= 0 -> empty
            value >= max -> full
            else -> half
        }
        fun icon(value: String, label: String) = Component.text(value, TextColor.color(0xFFFFFF))
            .font(Key.key("minecraft:default")).decoration(TextDecoration.BOLD, false).hoverEvent(render(player, label))
        val currentHealth = health.coerceIn(0.0, maximum)
        val currentHunger = hunger.coerceIn(0, 20)
        return render(player, "hologram.health",
            "heart" to icon(state(currentHealth, maximum, "\uE600", "\uE601", "\uE602"), "workers.health-label"),
            "food" to icon(state(currentHunger.toDouble(), 20.0, "\uE603", "\uE604", "\uE605"), "workers.hunger-label"),
            "health" to literal(number(currentHealth)).color(if (currentHealth <= maximum / 2) ERROR else SUCCESS),
            "max" to number(maximum),
            "hunger" to literal(currentHunger).color(if (currentHunger <= 6) ERROR else WARNING))
    }

    fun success(player: Player?, key: String, vararg values: Pair<String, Any?>): Component =
        feedback(render(player, key, *values), SUCCESS)

    fun info(player: Player?, key: String, vararg values: Pair<String, Any?>): Component =
        feedback(render(player, key, *values), INFO)

    fun warning(player: Player?, key: String, vararg values: Pair<String, Any?>): Component =
        feedback(render(player, key, *values), WARNING)

    fun error(player: Player?, key: String, vararg values: Pair<String, Any?>): Component =
        feedback(render(player, key, *values), ERROR)

    private fun feedback(component: Component, color: TextColor): Component =
        component.color(color).children(component.children().map { feedback(it, color) })

    fun building(player: Player, type: BuildingType): Component = render(player, "building.${type.name.lowercase(Locale.ROOT)}")

    /** Current work status when supplied; otherwise the saved building state. */
    fun buildingHologram(player: Player, city: Settlement, building: CivBuilding, status: String? = null): Component {
        val order = city.construction?.takeIf { it.buildingId == building.id }
        val workers = city.workers.count { it.assignment == building.id }
        val state = when {
            order?.phase == ConstructionPhase.WAITING_MATERIALS -> "waiting"
            order?.phase == ConstructionPhase.PAUSED -> "paused"
            order != null || building.phase == BuildingPhase.CONSTRUCTING -> "building"
            building.phase == BuildingPhase.SITE -> "site"
            CivsRules.slots(city, building) > 0 -> if (workers > 0) "workers" else "vacant"
            else -> "ready"
        }
        val currentStatus = status?.takeIf { it in messages.getValue("ru") }?.let {
            render(player, it, "position" to (city.constructionQueue.orEmpty().indexOf(building.id) + 1))
        }
        val label = render(player, "hologram.label",
            "name" to this.building(player, building.type).color(TextColor.color(0x9BD48D)),
            "level" to render(player, "hologram.level", "level" to building.level),
            "state" to (currentStatus ?: render(player, "hologram.$state", "count" to workers)).color(TextColor.color(
                if (state in setOf("waiting", "paused", "vacant")) 0xE4BB75 else 0x92BED8)))
        val placed = order?.blocksPlaced
        val total = order?.blocksRequired
        return if (placed != null && total != null && total > 0 && placed in 0..total) label.append(Component.newline())
            .append(render(player, "hologram.blocks", "placed" to placed, "total" to total).color(INFO)) else label
    }

    fun resource(player: Player, resource: Resource): Component = render(player, "resource.${resource.name.lowercase(Locale.ROOT)}")

    fun style(player: Player, style: String): Component =
        if ("style.$style" in messages.getValue("ru")) render(player, "style.$style") else literal(style)

    fun status(player: Player?, status: String, inputs: List<String> = emptyList(), locale: String? = null): Component {
        val key = if (status.startsWith("workers.status.")) status else "workers.status.$status"
        val selectedLocale = locale ?: language(player)
        if (key == "workers.status.waiting-inputs" && inputs.isNotEmpty()) return render(
            "workers.status.waiting-input-materials", selectedLocale,
            "materials" to Component.join(literal(", "), inputs.distinct().map { material(it, selectedLocale) }))
        return render(key.takeIf { it in messages.getValue("ru") } ?: "workers.status.unavailable", selectedLocale)
    }

    /** Resolve names before measured layout, using the same language policy as the surrounding menu. */
    fun material(player: Player?, material: String): Component = material(material, language(player))

    private fun material(material: String, locale: String): Component = Material.matchMaterial(material)?.let {
        materialLabels[locale]?.get(it.translationKey())?.let(::literal)
    } ?: render("materials.unknown", locale)

    fun reason(player: Player, reason: String?, values: Map<String, String> = emptyMap()): Component = render(player,
        "reason.$reason".takeIf { it in messages.getValue("ru") } ?: "common.error", *values.map { (key, value) ->
            key to if (key == "material") material(player, value) else value
        }.toTypedArray())

    fun failure(player: Player, failure: Throwable): Component {
        val causes = generateSequence(failure) { it.cause }.take(8).toList()
        val rejection = causes.filterIsInstance<CivsRejection>().firstOrNull()
        val reasonKey = rejection?.reason
            ?: causes.filterIsInstance<ArcSchematicException>().firstOrNull()
                ?.takeIf { it.reason == ArcSchematicFailure.PROTECTED_AREA }?.let { "protected-area" }
        return feedback(reason(player, reasonKey, rejection?.values.orEmpty()), ERROR)
    }

    internal fun keys(locale: String): Set<String> = messages.getValue(locale).keys

    companion object {
        val BODY: TextColor = TextColor.color(0xE8DFD2)
        val SUCCESS: TextColor = TextColor.color(0x9BD48D)
        val INFO: TextColor = TextColor.color(0x92BED8)
        val WARNING: TextColor = TextColor.color(0xE4BB75)
        val ERROR: TextColor = TextColor.color(0xFF6B61)
        private val materialLabels = listOf("ru", "en").associateWith { locale ->
            requireNotNull(CivsText::class.java.getResourceAsStream("/materials/$locale.json")) {
                "Bundled Minecraft material labels are missing: $locale"
            }.bufferedReader(Charsets.UTF_8).use { reader ->
                JsonParser.parseReader(reader).asJsonObject.entrySet().associate { (key, value) -> key to value.asString }
            }
        }

        fun load(plugin: Plugin, config: org.bukkit.configuration.file.FileConfiguration = plugin.config): CivsText {
            val bundled = requireNotNull(plugin.getResource("locales.yml")) { "ArcCivs locales.yml is missing" }
                .bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
            val file = File(plugin.dataFolder, "locales.yml")
            val overrides = if (file.isFile) parse(file.readText(Charsets.UTF_8)) else emptyMap()
            val merged = bundled.mapValues { (locale, values) -> values + overrides[locale].orEmpty() }
            val locale = config.getString("locale", "ru") ?: "ru"
            val useClient = config.getBoolean("use-client-locale", false)
            val forced = config.getString("force-language", "ru")
            return CivsText(merged, { locale }, { useClient }, { forced })
        }

        internal fun fromYaml(source: String, defaultLocale: String = "ru"): CivsText =
            CivsText(parse(source), { defaultLocale }, { true }, { null })

        private fun parse(source: String): Map<String, Map<String, String>> {
            val yaml = YamlConfiguration().apply { loadFromString(source) }
            return listOf("ru", "en").mapNotNull { locale ->
                yaml.getConfigurationSection(locale)?.let { section ->
                    locale to section.getValues(true).mapNotNull { (key, value) ->
                        (value as? String)?.let { key to it }
                    }.toMap()
                }
            }.toMap()
        }
    }
}
