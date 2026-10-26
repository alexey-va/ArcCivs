package ru.arc.civs

import org.bukkit.configuration.file.FileConfiguration
import java.nio.file.Path

data class CivsSettings(
    val schematicRoot: Path,
    val worlds: Set<String>,
    val radius: Int,
    val maximumCities: Int,
    val maximumVolume: Int,
    val maximumPreviews: Int,
    val previewSeconds: Int,
    val maximumNpcs: Int,
    val viewerRange: Int,
    val maximumLoadedChunks: Int = 2048,
    val navigation: CivsNavigationSettings = CivsNavigationSettings(),
    val worksites: CivsWorksiteSettings = CivsWorksiteSettings(),
) {
    companion object {
        fun chunkLoadingMaximum(config: FileConfiguration, warn: (String) -> Unit = {}): Int {
            val value = config.get("chunk-loading.maximum-chunks") ?: return 2048
            if (value is Int && value in 1..8192) return value
            warn("chunk-loading.maximum-chunks must be an integer in 1..8192; using 2048")
            return 2048
        }

        fun read(config: FileConfiguration): CivsSettings = CivsSettings(
            Path.of(requireNotNull(config.getString("schematics-root"))).toAbsolutePath().normalize(),
            config.getStringList("allowed-worlds").toSet(),
            config.getInt("settlement-radius", 96).also { require(it in 64..128) },
            config.getInt("maximum-settlements", 500).also { require(it in 1..500) },
            config.getInt("maximum-building-volume", 50_000).also { require(it in 1..75_000) },
            config.getInt("maximum-previews", 4).also { require(it in 1..8) },
            config.getInt("preview-seconds", 120).also { require(it in 30..300) },
            config.getInt("maximum-active-npcs", 48).also { require(it in 1..48) },
            config.getInt("npc-viewer-range", 96).also { require(it in 24..96) },
            chunkLoadingMaximum(config),
            CivsNavigationSettings.read(config),
            CivsWorksiteSettings.read(config),
        )
    }
}

data class CivsNavigationSettings(
    val searchNodesPerBatch: Int = 256,
    val maximumNodes: Int = 4096,
    val maximumCapturedBlocks: Int = 32768,
    val maximumJobs: Int = 48,
    val closeRouteDistance: Double = 16.0,
    val initialHorizontalMargin: Int = 4,
    val initialVerticalMargin: Int = 2,
    val closePreparationTicks: Int = 80,
    val preparationTicks: Int = 320,
    val nativeOperationsPerTick: Int = 10000,
    val nativeBudgetMillis: Double = 4.0,
) {
    init {
        require(searchNodesPerBatch in 1..65536)
        require(maximumNodes in 64..65536)
        require(maximumCapturedBlocks in 256..262144)
        require(maximumJobs in 1..256)
        require(closeRouteDistance in 1.0..64.0)
        require(initialHorizontalMargin in 1..96 && initialVerticalMargin in 1..96)
        require(closePreparationTicks in 1..1200 && preparationTicks in 1..1200)
        require(nativeOperationsPerTick in 1..100000 && nativeBudgetMillis in 0.1..20.0)
    }

    companion object {
        fun read(config: FileConfiguration): CivsNavigationSettings = CivsNavigationSettings(
            config.limit("navigation.nodes-per-batch", 256, 1..65536),
            config.limit("navigation.maximum-nodes", 4096, 64..65536),
            config.limit("navigation.maximum-captured-blocks", 32768, 256..262144),
            config.limit("navigation.maximum-jobs", 48, 1..256),
            config.decimal("navigation.close-route-distance", 16.0, 1.0..64.0),
            config.limit("navigation.initial-horizontal-margin", 4, 1..96),
            config.limit("navigation.initial-vertical-margin", 2, 1..96),
            config.limit("navigation.close-preparation-ticks", 80, 1..1200),
            config.limit("navigation.preparation-ticks", 320, 1..1200),
            config.limit("native-work.operations-per-tick", 10000, 1..100000),
            config.decimal("native-work.budget-millis", 4.0, 0.1..20.0),
        )
    }
}

data class CivsWorksiteSettings(
    val standingChecksPerPulse: Int = 32,
    val rayChecksPerPulse: Int = 8,
    val mineChecksPerPulse: Int = 24,
    val forestWorkUnitsPerPulse: Int = 192,
    val maximumForestColumnsPerPulse: Int = 16,
    val nativeBudgetMillis: Double = 2.0,
) {
    init {
        require(standingChecksPerPulse in 1..10000 && rayChecksPerPulse in 1..10000)
        require(mineChecksPerPulse in 1..10000 && forestWorkUnitsPerPulse in 128..10000)
        require(maximumForestColumnsPerPulse in 1..10000 && nativeBudgetMillis in 0.1..20.0)
    }

    companion object {
        fun read(config: FileConfiguration): CivsWorksiteSettings = CivsWorksiteSettings(
            config.limit("worksites.standing-checks-per-pulse", 32, 1..10000),
            config.limit("worksites.ray-checks-per-pulse", 8, 1..10000),
            config.limit("worksites.mine-checks-per-pulse", 24, 1..10000),
            config.limit("worksites.forest-work-units-per-pulse", 192, 128..10000),
            config.limit("worksites.maximum-forest-columns-per-pulse", 16, 1..10000),
            config.decimal("worksites.budget-millis", 2.0, 0.1..20.0),
        )
    }
}

private fun FileConfiguration.limit(path: String, default: Int, range: IntRange): Int {
    val value = get(path) ?: return default
    require(value is Int && value in range) { "$path must be an integer in $range" }
    return value
}

private fun FileConfiguration.decimal(path: String, default: Double, range: ClosedFloatingPointRange<Double>): Double {
    val value = get(path) ?: return default
    require(value is Number && value.toDouble() in range) { "$path must be a number in $range" }
    return value.toDouble()
}
