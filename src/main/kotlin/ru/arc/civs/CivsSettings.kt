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
        )
    }
}
