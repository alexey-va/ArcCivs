package ru.arc.civs

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.npc.PacketNpcPose
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val GRAPPLE_VIEW_RANGE = 24
private const val GRAPPLE_RELEASE = 1.0 / 6

internal class CitizenGrappleVisual(val approach: CitizenPoint, val anchor: CitizenPoint, var stroke: Int,
    var released: Boolean) {
    var hook: PacketBlockDisplay? = null
    var rope: PacketBlockDisplay? = null
    var lastFeet: CitizenPoint? = null
    var lastYaw = Float.NaN
    var lastCast = Double.NaN
    var lastScale = Double.NaN
}

/** The route owns the animation clock. Its fixed exterior contact survives each short pull. */
internal class CivsCitizenTraversalVisuals(private val runtime: CitizenRuntime) {
    var displays: PaperPacketDisplays? = null
    // TripWireHookBlock defaults to the north-facing, unpowered, unattached model.
    private val hookBlock by lazy(LazyThreadSafetyMode.NONE) { Material.TRIPWIRE_HOOK.createBlockData() }
    private val ropeBlock by lazy(LazyThreadSafetyMode.NONE) { Material.STRIPPED_OAK_LOG.createBlockData() }

    fun crawl(actor: CitizenActor, low: Boolean) {
        val npc = actor.npc
        if (low && npc.pose != PacketNpcPose.SWIMMING) npc.pose = PacketNpcPose.SWIMMING
        else if (!low && npc.pose == PacketNpcPose.SWIMMING) npc.pose = PacketNpcPose.STANDING
    }

    fun grapple(actor: CitizenActor, approach: CitizenPoint, anchor: CitizenPoint, cast: Double, stroke: Int,
        viewers: List<CitizenViewer>) {
        val owner = displays
        val npc = actor.npc
        if (owner == null || viewers.isEmpty() || !npc.isValid) { clear(actor); return }
        val feet = npc.location.point()
        val range = minOf(GRAPPLE_VIEW_RANGE, npc.viewRange.toInt())
        if (!citizenHasViewer(actor.worldId, feet, viewers, range)) { clear(actor); return }
        val released = cast >= GRAPPLE_RELEASE
        if (actor.grappleVisual?.let { it.approach != approach || it.anchor != anchor || it.lastCast > cast } == true)
            clear(actor)
        try {
            val scene = actor.grappleVisual ?: CitizenGrappleVisual(approach, anchor, stroke, released).also {
                actor.grappleVisual = it
                npc.setEquipment(EquipmentSlot.HAND, ItemStack(Material.AIR))
                npc.setEquipment(EquipmentSlot.OFF_HAND, ItemStack(if (released) Material.LEAD else Material.TRIPWIRE_HOOK))
            }
            if (!released) return
            if (!scene.released) {
                npc.setEquipment(EquipmentSlot.OFF_HAND, ItemStack(Material.LEAD))
                npc.swingOffHand()
                scene.released = true
            }
            if (stroke != scene.stroke) {
                if (stroke % 2 == 1) npc.swingMainHand() else npc.swingOffHand()
                scene.stroke = stroke
            }
            if (scene.lastFeet == feet && scene.lastYaw == npc.bodyYaw && scene.lastCast == cast && scene.lastScale == npc.scale) return
            val pose = civsGrapplePose(approach, anchor, feet, npc.bodyYaw, npc.scale, cast)
            if (scene.hook == null) {
                val base = Location(npc.world, approach.x, approach.y, approach.z)
                scene.hook = owner.spawnBlock(base, hookBlock)
                scene.rope = owner.spawnBlock(base, ropeBlock)
                for (part in listOfNotNull(scene.hook, scene.rope)) {
                    part.viewRange = range / 64f
                    part.displayWidth = 4f
                    part.displayHeight = (anchor.y - approach.y + 3.0).toFloat()
                    part.shadowStrength = 0f
                    part.interpolationDuration = 2
                }
            }
            if (scene.lastCast != cast) scene.hook?.transformation = pose.hook
            scene.rope?.transformation = pose.rope
            scene.lastFeet = feet
            scene.lastYaw = npc.bodyYaw
            scene.lastCast = cast
            scene.lastScale = npc.scale
        } catch (failure: Exception) {
            clear(actor)
            runtime.logFailure(actor, "grapple-visual", failure)
        }
    }

    fun clear(actor: CitizenActor) {
        val scene = actor.grappleVisual ?: return
        actor.grappleVisual = null
        scene.hook?.remove()
        scene.rope?.remove()
        if (actor.npc.isValid) actor.restoreHands()
    }
}

internal data class CitizenGrapplePose(val hook: Transformation, val rope: Transformation)

/** Transforms are local to the lower route node, in world axes. The wooden plate sits flush on the exterior face. */
internal fun civsGrapplePose(approach: CitizenPoint, anchor: CitizenPoint, feet: CitizenPoint, yaw: Float,
    scale: Double, cast: Double): CitizenGrapplePose {
    val t = ((cast - GRAPPLE_RELEASE) / (1.0 - GRAPPLE_RELEASE)).coerceIn(0.0, 1.0).toFloat()
    val ledge = Vector3f((anchor.x - approach.x).toFloat(), (anchor.y - approach.y).toFloat(), (anchor.z - approach.z).toFloat())
    val facing = Quaternionf().rotationY(atan2(ledge.x, ledge.z))
    val body = Math.toRadians(yaw.toDouble())
    val reach = .25 * scale
    val left = (.34 - .22 * t) * scale
    val hand = Vector3f((feet.x - approach.x - sin(body) * reach + cos(body) * left).toFloat(),
        (feet.y - approach.y + (1.02 + .12 * t) * scale).toFloat(),
        (feet.z - approach.z + cos(body) * reach + sin(body) * left).toFloat())
    // North-facing vanilla tripwire_hook has its back at z=1 and iron eye near the middle of the cell.
    val hookScale = .8f
    val plate = Vector3f(.5f, .36f, 1f).mul(hookScale)
    val eye = Vector3f(.5f, .494f, .604f).mul(hookScale)
    val contact = facing.transform(Vector3f(eye).sub(plate)).add(ledge)
    val flying = Vector3f(hand).lerp(contact, t).add(0f, .9f * t * (1f - t), 0f)
    val hook = Transformation(Vector3f(flying).sub(facing.transform(Vector3f(eye))), facing,
        Vector3f(hookScale), Quaternionf())

    val line = Vector3f(flying).sub(hand)
    val length = line.length()
    val ropeRotation = if (length > .0001f) Quaternionf().rotationTo(Vector3f(0f, 1f, 0f), line.div(length)) else Quaternionf()
    val width = .054f
    val corner = ropeRotation.transform(Vector3f(-width / 2, 0f, -width / 2)).add(hand)
    return CitizenGrapplePose(hook, Transformation(corner, ropeRotation, Vector3f(width, length, width), Quaternionf()))
}
