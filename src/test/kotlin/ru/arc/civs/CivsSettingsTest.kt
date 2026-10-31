package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.configuration.file.YamlConfiguration

class CivsSettingsTest : FunSpec({
    test("older configuration receives larger default search batches and aggregate native limits") {
        val config = YamlConfiguration().apply { set("schematics-root", "schematics") }
        val settings = CivsSettings.read(config)
        settings.navigation shouldBe CivsNavigationSettings()
        settings.navigation.searchNodesPerBatch shouldBe 256
        settings.navigation.nativeOperationsPerTick shouldBe 10000
        settings.worksites shouldBe CivsWorksiteSettings()
        settings.maximumNpcs shouldBe 64
        settings.radius shouldBe 128
    }

    test("a configured seven-town population fits while old actor limits and the default remain valid") {
        val config = YamlConfiguration().apply { set("schematics-root", "schematics") }
        for (limit in listOf(1, 48, 64, 196, 256)) {
            config.set("maximum-active-npcs", limit)
            CivsSettings.read(config).maximumNpcs shouldBe limit
        }
        config.set("maximum-active-npcs", 257)
        shouldThrow<IllegalArgumentException> { CivsSettings.read(config) }
    }

    test("bundled search and native limits match backward-compatible defaults") {
        val config = requireNotNull(javaClass.getResourceAsStream("/config.yml")).use {
            YamlConfiguration.loadConfiguration(it.reader(Charsets.UTF_8))
        }
        CivsSettings.read(config).navigation shouldBe CivsNavigationSettings()
        CivsSettings.read(config).worksites shouldBe CivsWorksiteSettings()
    }

    test("all configured search and discovery limits are read") {
        val config = YamlConfiguration().apply {
            set("schematics-root", "schematics")
            set("navigation.nodes-per-batch", 1024)
            set("navigation.maximum-nodes", 8192)
            set("navigation.maximum-captured-blocks", 65536)
            set("navigation.maximum-jobs", 32)
            set("navigation.close-route-distance", 24.0)
            set("navigation.initial-horizontal-margin", 8)
            set("navigation.initial-vertical-margin", 3)
            set("navigation.close-preparation-ticks", 60)
            set("navigation.preparation-ticks", 240)
            set("native-work.operations-per-tick", 20000)
            set("native-work.budget-millis", 3.0)
            set("navigation.terrain-cache-blocks", 65536)
            set("navigation.terrain-refresh-ticks", 1200)
            set("worksites.standing-checks-per-pulse", 128)
            set("worksites.ray-checks-per-pulse", 24)
            set("worksites.mine-checks-per-pulse", 96)
            set("worksites.forest-work-units-per-pulse", 512)
            set("worksites.maximum-forest-columns-per-pulse", 32)
            set("worksites.budget-millis", 3.0)
        }
        val settings = CivsSettings.read(config)
        settings.navigation shouldBe CivsNavigationSettings(1024, 8192, 65536, 32, 24.0, 8, 3, 60, 240, 20000, 3.0, 65536, 1200)
        settings.worksites shouldBe CivsWorksiteSettings(128, 24, 96, 512, 32, 3.0)
    }

    for ((path, value) in listOf(
        "navigation.nodes-per-batch" to 0,
        "navigation.maximum-nodes" to "4096",
        "navigation.maximum-captured-blocks" to -1,
        "navigation.maximum-jobs" to 257,
        "navigation.close-route-distance" to Double.NaN,
        "navigation.initial-horizontal-margin" to 0,
        "navigation.initial-vertical-margin" to 97,
        "navigation.close-preparation-ticks" to 0,
        "navigation.preparation-ticks" to 1201,
        "navigation.terrain-cache-blocks" to 255,
        "navigation.terrain-refresh-ticks" to 0,
        "native-work.operations-per-tick" to 100001,
        "native-work.budget-millis" to Double.POSITIVE_INFINITY,
        "worksites.standing-checks-per-pulse" to 0,
        "worksites.ray-checks-per-pulse" to -8,
        "worksites.mine-checks-per-pulse" to 0,
        "worksites.forest-work-units-per-pulse" to 10001,
        "worksites.forest-work-units-per-pulse" to 127,
        "worksites.maximum-forest-columns-per-pulse" to 0,
        "worksites.budget-millis" to 0.0,
    )) {
        test("invalid configured $path=$value fails before native work starts") {
            val config = YamlConfiguration().apply {
                set("schematics-root", "schematics")
                set(path, value)
            }
            shouldThrow<IllegalArgumentException> { CivsSettings.read(config) }
        }
    }
})
