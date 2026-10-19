package ru.arc.civs

import dev.lone.itemsadder.api.CustomStack
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Savva's existing model, on the same native carrier and packet owner as the nameplate. */
internal class CivsCourierCart(
    private val displays: PaperPacketDisplays,
    private val resolveItem: () -> ItemStack? = { CustomStack.getInstance(CIVS_COURIER_CART_ITEM)?.itemStack?.clone() },
) : AutoCloseable {
    private data class Cart(val body: PacketItemDisplay, var carrier: Player,
        val cargo: MutableList<PacketDisplay> = mutableListOf(), var samples: List<Material> = emptyList(),
        var yaw: Float = Float.NaN, var height: Double = Double.NaN)
    private val carts = linkedMapOf<UUID, Cart>()
    private var model: ItemStack? = null

    fun sync(npc: CivsNpcHologramSnapshot) {
        val carrier = npc.entity
        if (!npc.courierCart || carrier == null || !carrier.isValid) { remove(npc.workerId); return }
        val cart = carts[npc.workerId] ?: run {
            if (model == null) model = runCatching(resolveItem).getOrNull()
            val item = model ?: return
            val handle = displays.spawnItem(at(npc), item)
            handle.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND
            configure(handle)
            Cart(handle, carrier).also { carts[npc.workerId] = it }
        }
        if (cart.carrier !== carrier) {
            cart.carrier = carrier
            cart.yaw = Float.NaN
        }
        val samples = civsCourierCargoSamples(npc.cargo)
        if (cart.samples != samples) {
            // A pickup/unload changes the cargo, not the cart's identity or pose.
            cart.cargo.forEach(PacketDisplay::remove)
            cart.cargo.clear()
            cart.samples = samples
            for (material in samples) {
                val piece: PacketDisplay = if (material.isBlock) displays.spawnBlock(at(npc), material.createBlockData())
                    else displays.spawnItem(at(npc), ItemStack(material)).apply {
                        itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
                    }
                configure(piece)
                cart.cargo += piece
            }
            cart.yaw = Float.NaN
        }
        applyPose(cart, npc.yaw, npc.nativeHeight)
    }

    /** Position follows native client motion. Only changing body heading/stance sends a local pose. */
    fun updatePoses() {
        for ((id, cart) in carts.toMap()) {
            if (!cart.carrier.isValid) { remove(id); continue }
            applyPose(cart, cart.carrier.bodyYaw, cart.carrier.height)
        }
    }

    private fun applyPose(cart: Cart, yaw: Float, height: Double) {
        if (!yaw.isFinite() || !height.isFinite() || height <= 0 || cart.yaw == yaw && cart.height == height) return
        cart.body.transformation = civsCourierCartTransform(yaw, height)
        cart.body.attachTo(cart.carrier)
        cart.cargo.forEachIndexed { index, handle ->
            handle.transformation = civsCourierCargoTransform(yaw, height, index, cart.cargo.size, handle is PacketBlockDisplay)
            handle.attachTo(cart.carrier)
        }
        cart.yaw = yaw
        cart.height = height
    }

    private fun configure(handle: PacketDisplay) {
        handle.billboard = Display.Billboard.FIXED
        handle.viewRange = 0.25f
        handle.displayWidth = 3f
        handle.displayHeight = 3f
        handle.interpolationDelay = -1
        handle.interpolationDuration = 1
        handle.teleportDuration = 0
        handle.shadowRadius = 0f
        handle.shadowStrength = 0f
    }

    fun remove(id: UUID) {
        val cart = carts.remove(id) ?: return
        cart.cargo.forEach(PacketDisplay::remove)
        cart.body.remove()
    }

    override fun close() { carts.keys.toList().forEach(::remove); model = null }
    private fun at(npc: CivsNpcHologramSnapshot) = Location(requireNotNull(npc.entity).world, npc.x, npc.y, npc.z)
}

internal const val CIVS_COURIER_CART_ITEM = "elitecreatures:medieval_market_decoration_v1_cart_2"

/** At most three representative pieces; material and density derive only from accepted current cargo. */
internal fun civsCourierCargoSamples(cargo: Map<String, Int>): List<Material> = buildList {
    for ((key, amount) in cargo.toSortedMap()) {
        val material = Material.matchMaterial(key)?.takeIf { it.isItem && !it.isAir } ?: continue
        if (amount <= 0) continue
        repeat(((amount.toLong() + 23) / 24).coerceAtMost(3 - size.toLong()).toInt()) { add(material) }
        if (size == 3) break
    }
}

/** GROUND ×3 has its lowest wheel at -0.5625; forward is Bukkit (-sin yaw, 0, cos yaw). */
internal fun civsCourierCartTransform(yaw: Float, height: Double): Transformation {
    val radians = Math.toRadians(yaw.toDouble())
    return Transformation(Vector3f(-sin(radians).toFloat(), (0.5625 - height).toFloat(), cos(radians).toFloat()),
        Quaternionf().rotationY((Math.PI - radians).toFloat()), Vector3f(3f), Quaternionf())
}

/** The measured cart bed slopes 22.5°; its top plane is y=0.534585666-z*tan(22.5°). */
internal fun civsCourierCargoTransform(yaw: Float, height: Double, index: Int, count: Int, block: Boolean): Transformation {
    val radians = Math.toRadians(yaw.toDouble())
    val z = if (count == 1) 0.15 else -0.20 + index * 0.35
    val bed = Vector3f(0f, (0.536585666 - z * tan(Math.PI / 8)).toFloat(), z.toFloat())
    val frame = Quaternionf().rotationY(-radians.toFloat())
    frame.transform(bed)
    bed.add(-sin(radians).toFloat(), -height.toFloat(), cos(radians).toFloat())
    val rotation = Quaternionf(frame).rotateX(if (block) (Math.PI / 8).toFloat() else (-3 * Math.PI / 8).toFloat())
    val scale = if (block) Vector3f(0.30f, 0.22f, 0.28f) else Vector3f(0.42f)
    if (block) bed.add(rotation.transform(Vector3f(-scale.x / 2, 0f, -scale.z / 2)))
    else bed.y += 0.018f
    return Transformation(bed, rotation, scale, Quaternionf())
}
