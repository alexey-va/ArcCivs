package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.ChunkSnapshot
import org.bukkit.Material
import org.bukkit.block.Biome
import org.bukkit.block.data.BlockData

/** Frozen data only. Every World/Block read happens before construction on the fixture's owner thread. */
internal class CivsNavigationChunkSnapshotFixture(
    private val chunkX: Int, private val chunkZ: Int, private val minimumY: Int, private val maximumY: Int,
    private val sourceWorld: String, private val capturedAt: Long, private val ground: BlockData,
    private val cells: Map<CivPosition, BlockData>,
) : ChunkSnapshot {
    private val air = Bukkit.createBlockData(Material.AIR)
    override fun getX() = chunkX
    override fun getZ() = chunkZ
    override fun getWorldName() = sourceWorld
    override fun getCaptureFullTime() = capturedAt
    override fun getBlockData(x: Int, y: Int, z: Int): BlockData {
        require(x in 0..15 && z in 0..15 && y in minimumY until maximumY)
        return (cells[CivPosition(x, y, z)] ?: if (y <= 63) ground else air).clone()
    }
    override fun getBlockType(x: Int, y: Int, z: Int) = getBlockData(x, y, z).material
    override fun getHighestBlockYAt(x: Int, z: Int): Int =
        (maximumY - 1 downTo minimumY).firstOrNull { !getBlockType(x, it, z).isAir } ?: minimumY
    override fun isSectionEmpty(sy: Int): Boolean = (minimumY + sy * 16 until minOf(maximumY, minimumY + (sy + 1) * 16))
        .all { y -> (0..15).all { x -> (0..15).all { z -> getBlockType(x, y, z).isAir } } }
    override fun contains(block: BlockData) = block == ground || block == air || cells.values.any { it == block }
    override fun contains(biome: Biome): Boolean = error("Biome was not captured")
    override fun getData(x: Int, y: Int, z: Int): Int = error("Legacy data is not used")
    override fun getBlockSkyLight(x: Int, y: Int, z: Int): Int = error("Light was not captured")
    override fun getBlockEmittedLight(x: Int, y: Int, z: Int): Int = error("Light was not captured")
    override fun getBiome(x: Int, z: Int): Biome = error("Biome was not captured")
    override fun getBiome(x: Int, y: Int, z: Int): Biome = error("Biome was not captured")
    override fun getRawBiomeTemperature(x: Int, z: Int): Double = error("Temperature was not captured")
    override fun getRawBiomeTemperature(x: Int, y: Int, z: Int): Double = error("Temperature was not captured")
}
