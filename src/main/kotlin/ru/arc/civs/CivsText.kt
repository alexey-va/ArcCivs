package ru.arc.civs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
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
    defaultLocale: () -> String,
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
        render(key, forcedLocale()?.takeIf(String::isNotBlank)
            ?: if (player != null && useClientLocale()) player.locale().toLanguageTag() else null, *values)

    fun render(key: String, locale: String? = null, vararg values: Pair<String, Any?>): Component {
        require(key in messages.getValue("ru")) { "Unknown ArcCivs locale key: $key" }
        return renderer.render(key, locale, values.associate { (name, value) ->
            name to if (value is Component) value else renderer.literal(value)
        }).colorIfAbsent(BODY).decoration(TextDecoration.ITALIC, false)
    }

    fun literal(value: Any?): Component = renderer.literal(value).color(BODY).decoration(TextDecoration.ITALIC, false)

    fun building(player: Player, type: BuildingType): Component = render(player, "building.${type.name.lowercase(Locale.ROOT)}")

    fun resource(player: Player, resource: Resource): Component = render(player, "resource.${resource.name.lowercase(Locale.ROOT)}")

    fun style(player: Player, style: String): Component =
        if ("style.$style" in messages.getValue("ru")) render(player, "style.$style") else literal(style)

    fun status(player: Player, status: String): Component = render(player,
        (if (status.startsWith("workers.status.")) status else "workers.status.$status")
            .takeIf { it in messages.getValue("ru") } ?: "workers.status.unavailable")

    fun reason(player: Player, reason: String?): Component = render(player,
        "reason.$reason".takeIf { it in messages.getValue("ru") } ?: "common.error")

    fun failure(player: Player, failure: Throwable): Component {
        val causes = generateSequence(failure) { it.cause }.take(8).toList()
        val reasonKey = causes.filterIsInstance<CivsRejection>().firstOrNull()?.reason
            ?: causes.filterIsInstance<ArcSchematicException>().firstOrNull()
                ?.takeIf { it.reason == ArcSchematicFailure.PROTECTED_AREA }?.let { "protected-area" }
        return reason(player, reasonKey)
    }

    internal fun keys(locale: String): Set<String> = messages.getValue(locale).keys

    companion object {
        val BODY: TextColor = TextColor.color(0xE8DFD2)

        fun load(plugin: Plugin): CivsText {
            val bundled = requireNotNull(plugin.getResource("locales.yml")) { "ArcCivs locales.yml is missing" }
                .bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
            val file = File(plugin.dataFolder, "locales.yml")
            val overrides = if (file.isFile) parse(file.readText(Charsets.UTF_8)) else emptyMap()
            val merged = bundled.mapValues { (locale, values) -> values + overrides[locale].orEmpty() }
            return CivsText(merged,
                { plugin.config.getString("locale", "ru") ?: "ru" },
                { plugin.config.getBoolean("use-client-locale", false) },
                // Missing keys in existing installations also adopt the server language.
                { plugin.config.getString("force-language", "ru") },
            )
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
