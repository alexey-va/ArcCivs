package ru.arc.civs

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PaperPacketDisplays

/** Observed sleep only; the logical feet remain on their native support. */
data class CivSleepPresentation(val groundX: Double, val groundY: Double, val groundZ: Double,
    val yaw: Float, val scale: Double, val centreX: Double, val topY: Double, val centreZ: Double) {
    internal fun valid(): Boolean = listOf(groundX, groundY, groundZ, scale, centreX, topY, centreZ).all(Double::isFinite) &&
        yaw.isFinite() && scale > 0.0
}

internal const val CIVS_SLEEP_PAD_HEIGHT = .05

/** Two fixed client-only cloth parts; handles survive ordinary label/status updates. */
internal class CivsSleepBedrollRenderer(private val displays: PaperPacketDisplays) {
    fun show(ground: Location, yaw: Float, scale: Double): AutoCloseable {
        val facing = Quaternionf().rotationY(Math.toRadians(yaw.toDouble()).toFloat())
        val handles = arrayListOf<PacketDisplay>()
        fun part(material: Material, offset: Vector3f, dimensions: Vector3f) {
            val handle = displays.spawnBlock(ground.clone().apply { this.yaw = 0f; pitch = 0f }, material.createBlockData())
            handles += handle
            handle.viewRange = .375f
            handle.displayWidth = 2.5f
            handle.displayHeight = 1f
            handle.shadowStrength = 0f
            handle.transformation = Transformation(facing.transform(offset.mul(scale.toFloat())), Quaternionf(facing),
                dimensions.mul(scale.toFloat()), Quaternionf())
        }
        try {
            // Both parts remain within the reserved animated/armoured lying footprint.
            part(Material.BROWN_WOOL, Vector3f(-1.95f, .002f, -.60f),
                Vector3f(2.01f, CIVS_SLEEP_PAD_HEIGHT.toFloat(), 1.20f))
            part(Material.WHITE_WOOL, Vector3f(-1.94f, .002f, -.48f), Vector3f(.18f, .065f, .96f))
        } catch (failure: Exception) {
            handles.forEach(PacketDisplay::remove)
            throw failure
        }
        var closed = false
        return AutoCloseable {
            if (!closed) { closed = true; handles.forEach(PacketDisplay::remove) }
        }
    }
}
