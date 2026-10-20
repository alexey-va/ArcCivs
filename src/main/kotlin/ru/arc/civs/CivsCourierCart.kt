package ru.arc.civs

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Barrel
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.npc.PacketPlayerNpc
import java.util.UUID

/** A close-fitting backpack and at most two samples of the actual delivery; no inventory mutations. */
internal class CivsCourierCart(private val displays: PaperPacketDisplays) : AutoCloseable {
    private data class Sample(val handle: PacketItemDisplay, var material: Material)
    private data class Parcel(val body: PacketBlockDisplay, var carrier: PacketPlayerNpc,
        val samples: MutableList<Sample> = mutableListOf(), var yaw: Float = Float.NaN, var height: Double = Double.NaN)
    private val parcels = linkedMapOf<UUID, Parcel>()

    fun sync(npc: CivsNpcHologramSnapshot) {
        val carrier = npc.actor
        if (!npc.courierCart || npc.cargo.values.none { it > 0 } || carrier == null || !carrier.isValid) {
            remove(npc.workerId)
            return
        }
        val parcel = parcels[npc.workerId] ?: run {
            val block = (Material.BARREL.createBlockData() as Barrel).apply { facing = BlockFace.UP; isOpen = true }
            val handle = displays.spawnBlock(Location(carrier.world, npc.x, npc.y, npc.z), block)
            configure(handle)
            Parcel(handle, carrier).also { parcels[npc.workerId] = it }
        }
        if (parcel.carrier !== carrier) {
            parcel.carrier = carrier
            parcel.yaw = Float.NaN
        }
        val materials = civsCourierCargoMaterials(npc.cargo)
        while (parcel.samples.size > materials.size) {
            parcel.samples.removeLast().handle.remove()
            parcel.yaw = Float.NaN
        }
        materials.forEachIndexed { index, material ->
            val sample = parcel.samples.getOrNull(index)
            if (sample == null) {
                val handle = displays.spawnItem(Location(carrier.world, npc.x, npc.y, npc.z), ItemStack(material))
                parcel.samples += Sample(handle, material)
                configure(handle)
                handle.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
                parcel.yaw = Float.NaN
            } else if (sample.material != material) {
                sample.handle.itemStack = ItemStack(material)
                sample.material = material
                parcel.yaw = Float.NaN
            }
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
        parcel.samples.forEachIndexed { index, sample ->
            sample.handle.transformation = civsCourierCargoTransform(yaw, height, index, parcel.samples.size, sample.material.isBlock)
            sample.handle.attachTo(parcel.carrier)
        }
        parcel.yaw = yaw
        parcel.height = height
    }

    private fun configure(handle: PacketDisplay) {
        handle.billboard = Display.Billboard.FIXED
        handle.viewRange = 1f
        handle.displayWidth = 3f
        handle.displayHeight = 3f
        handle.interpolationDelay = -1
        // A corner and its heading must not interpolate separately and sweep outside the carrier.
        handle.interpolationDuration = 0
        handle.teleportDuration = 0
        handle.shadowRadius = 0f
        handle.shadowStrength = 0f
    }

    fun remove(id: UUID) { parcels.remove(id)?.let { parcel ->
        parcel.samples.forEach { it.handle.remove() }
        parcel.body.remove()
    } }
    override fun close() { parcels.keys.toList().forEach(::remove) }
}

/** 40×60×11 cm against the back; horizontal corner radius stays below 0.298 during a full turn. */
internal fun civsCourierParcelTransform(yaw: Float, height: Double): Transformation {
    val rotation = Quaternionf().rotationY(-Math.toRadians(yaw.toDouble()).toFloat())
    val corner = rotation.transform(Vector3f(-.20f, (height * .6 - .38 - height).toFloat(), -.22f))
    return Transformation(corner, rotation, Vector3f(.40f, .60f, .11f), Quaternionf())
}

internal fun civsCourierCargoMaterials(cargo: Map<String, Int>): List<Material> = cargo.entries.asSequence()
    .filter { it.value > 0 }.sortedBy { it.key }.mapNotNull { Material.matchMaterial(it.key) }
    .filter { it.isItem && !it.isAir }.distinct().take(2).toList()

/** NONE preserves the centered native model. Blocks rest on the rim; flat supplies tuck into the mouth. */
internal fun civsCourierCargoTransform(yaw: Float, height: Double, index: Int, count: Int, block: Boolean): Transformation {
    val scale = if (block) { if (count == 1) .18f else .13f } else { if (count == 1) .26f else .17f }
    val rotation = Quaternionf().rotationY(-Math.toRadians(yaw.toDouble()).toFloat())
    val center = rotation.transform(Vector3f(if (count == 1) 0f else if (index == 0) -.10f else .10f,
        (height * .6 + .22 + scale * (if (block) .5 else .25) - height).toFloat(), -.17f))
    return Transformation(center, rotation, Vector3f(scale), Quaternionf())
}
