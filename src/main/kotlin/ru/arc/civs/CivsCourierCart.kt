package ru.arc.civs

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Barrel
import org.bukkit.entity.Display
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.npc.PacketPlayerNpc
import java.util.UUID

/** One compact parcel replaces the forward cart; it follows the same packet carrier as the nameplate. */
internal class CivsCourierCart(private val displays: PaperPacketDisplays) : AutoCloseable {
    private data class Parcel(val body: PacketBlockDisplay, var carrier: PacketPlayerNpc,
        var yaw: Float = Float.NaN, var height: Double = Double.NaN)
    private val parcels = linkedMapOf<UUID, Parcel>()

    fun sync(npc: CivsNpcHologramSnapshot) {
        val carrier = npc.actor
        if (!npc.courierCart || npc.cargo.values.none { it > 0 } || carrier == null || !carrier.isValid) {
            remove(npc.workerId)
            return
        }
        val parcel = parcels[npc.workerId] ?: run {
            val block = (Material.BARREL.createBlockData() as Barrel).apply { facing = BlockFace.UP }
            val handle = displays.spawnBlock(Location(carrier.world, npc.x, npc.y, npc.z), block)
            handle.billboard = Display.Billboard.FIXED
            handle.viewRange = 1f
            handle.displayWidth = 3f
            handle.displayHeight = 3f
            handle.interpolationDelay = -1
            // A rotated block corner must not interpolate separately from its rotation and sweep outside the carrier.
            handle.interpolationDuration = 0
            handle.teleportDuration = 0
            handle.shadowRadius = 0f
            handle.shadowStrength = 0f
            Parcel(handle, carrier).also { parcels[npc.workerId] = it }
        }
        if (parcel.carrier !== carrier) {
            parcel.carrier = carrier
            parcel.yaw = Float.NaN
        }
        applyPose(parcel, npc.yaw, npc.nativeHeight)
    }

    /** Client mounting owns position; only changing body heading/stance changes the local pose. */
    fun updatePoses() {
        for ((id, parcel) in parcels.toMap()) {
            if (!parcel.carrier.isValid) { remove(id); continue }
            applyPose(parcel, parcel.carrier.bodyYaw, parcel.carrier.height)
        }
    }

    private fun applyPose(parcel: Parcel, yaw: Float, height: Double) {
        if (!yaw.isFinite() || !height.isFinite() || height <= 0 || parcel.yaw == yaw && parcel.height == height) return
        parcel.body.transformation = civsCourierParcelTransform(yaw, height)
        parcel.body.attachTo(parcel.carrier)
        parcel.yaw = yaw
        parcel.height = height
    }

    fun remove(id: UUID) { parcels.remove(id)?.body?.remove() }
    override fun close() { parcels.keys.toList().forEach(::remove) }
}

/** 32×42×14 cm against the back. Every horizontal corner stays within radius 0.289, inside the 0.6-wide actor. */
internal fun civsCourierParcelTransform(yaw: Float, height: Double): Transformation {
    val rotation = Quaternionf().rotationY(-Math.toRadians(yaw.toDouble()).toFloat())
    val corner = rotation.transform(Vector3f(-.16f, (height * .6 - .21 - height).toFloat(), -.24f))
    return Transformation(corner, rotation, Vector3f(.32f, .42f, .14f), Quaternionf())
}
