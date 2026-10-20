package ru.arc.civs

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.paper.npc.PacketNpcPose
import ru.arc.paper.npc.PacketPlayerNpc
import kotlin.math.abs
import kotlin.math.floor

/**
 * Physical resident bodies belong to this domain, not to the packet renderer.
 * Paper owns the player's correction; there is no teleport/velocity timer or native NPC.
 */
internal class CivsResidentCollisions(
    plugin: Plugin,
    private val actors: () -> Collection<PacketPlayerNpc>,
) : Listener, AutoCloseable {
    private var closed = false

    init { plugin.server.pluginManager.registerEvents(this, plugin) }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (closed || event is PlayerTeleportEvent || !event.hasChangedPosition()) return
        val player = event.player
        if (!participates(player)) return
        val from = event.from
        val to = event.to ?: return
        if (from.world != to.world || player.world != from.world) return
        val nativeBody = player.boundingBox
        val halfX = nativeBody.widthX / 2
        val halfZ = nativeBody.widthZ / 2
        val height = nativeBody.height
        // ponytail: the resident runtime owns the hard 48-actor cap. No world/entity scan.
        val bodies = actors().mapNotNull { npc ->
            if (!npc.isValid || npc.world != from.world || npc.pose == PacketNpcPose.SLEEPING) null
            else residentBody(npc, npc.location)
        }
        val resolved = citizenSlideBody(from.toVector(), to.toVector(), halfX, height, halfZ, bodies) ?: return
        var at = resolved.position
        if (at.distanceSquared(resolved.contact) > CONTACT_EPSILON * CONTACT_EPSILON) {
            // Redirecting a move must not slide the player into/through a native wall.
            // The union conservatively covers all remaining, axis-clipped slide segments.
            val slide = bodyAt(resolved.contact, halfX, height, halfZ).union(bodyAt(at, halfX, height, halfZ))
            val world = requireNotNull(from.world)
            val loaded = slide.widthX <= 16 && slide.widthZ <= 16 &&
                ((floor(slide.minX).toInt() shr 4)..(floor(slide.maxX).toInt() shr 4)).all { x ->
                    ((floor(slide.minZ).toInt() shr 4)..(floor(slide.maxZ).toInt() shr 4)).all { z ->
                        world.isChunkLoaded(x, z)
                    }
                }
            if (!loaded || player.wouldCollideUsing(slide)) at = resolved.contact
        }
        if (at.distanceSquared(to.toVector()) <= CONTACT_EPSILON * CONTACT_EPSILON) return
        event.to = to.clone().apply { x = at.x; y = at.y; z = at.z }
    }

    /** Called before navigation consumes a waypoint; false means wait for the player. */
    fun canResidentMove(npc: PacketPlayerNpc, from: Location, to: Location): Boolean {
        if (closed || !npc.isValid || from.world != to.world || npc.world != from.world) return false
        if (npc.pose == PacketNpcPose.SLEEPING) return true
        // Normal navigation advances at most 1.075 blocks. Recovery probes its destination
        // with from == to instead of sweeping a teleport across unrelated chunks.
        if (abs(to.x - from.x) > 2 || abs(to.y - from.y) > 2 || abs(to.z - from.z) > 2) return false
        val first = residentBody(npc, from)
        val last = residentBody(npc, to)
        val sweep = first.clone().union(last)
        val world = requireNotNull(from.world)
        val center = Location(world, sweep.centerX, sweep.centerY, sweep.centerZ)
        val players = world.getNearbyPlayers(center, sweep.widthX / 2, sweep.height / 2, sweep.widthZ / 2)
            .asSequence().filter(::participates).map(Player::getBoundingBox).toList()
        if (from.x == to.x && from.y == to.y && from.z == to.z) return players.none(last::overlaps)
        return citizenSlideBody(from.toVector(), to.toVector(), first.widthX / 2,
            first.height, first.widthZ / 2, players) == null
    }

    override fun close() {
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
    }

    private fun participates(player: Player): Boolean = player.isOnline && player.isValid && !player.isDead &&
        player.gameMode != GameMode.SPECTATOR && !player.isInsideVehicle && !player.isSleeping

    private fun residentBody(npc: PacketPlayerNpc, at: Location) =
        bodyAt(at.toVector(), .3 * npc.scale, npc.height, .3 * npc.scale)
}

internal data class CitizenCollisionMove(val position: Vector, val contact: Vector)

/**
 * Swept AABB with up to three plane clips: a whole move cannot tunnel through a body.
 * A pre-existing overlap only blocks movement deeper than its nearest exit plane.
 */
internal fun citizenSlideBody(
    from: Vector, to: Vector, halfX: Double, height: Double, halfZ: Double,
    bodies: List<BoundingBox>,
): CitizenCollisionMove? {
    if (bodies.isEmpty()) return null
    val expanded = bodies.map {
        BoundingBox(it.minX - halfX, it.minY - height, it.minZ - halfZ,
            it.maxX + halfX, it.maxY, it.maxZ + halfZ)
    }
    val at = from.clone()
    var remaining = to.clone().subtract(from)
    var contact: Vector? = null
    repeat(3) {
        val distance = remaining.length()
        if (distance <= CONTACT_EPSILON) return contact?.let { CitizenCollisionMove(at, it) }
        var closest = distance
        var normal: Vector? = null
        for (box in expanded) {
            // A ray on a box's boundary can hit an adjacent face without the
            // bodies ever overlapping (walking beside, or above, a resident).
            if (abs(remaining.x) <= CONTACT_EPSILON && (at.x <= box.minX || at.x >= box.maxX) ||
                abs(remaining.y) <= CONTACT_EPSILON && (at.y <= box.minY || at.y >= box.maxY) ||
                abs(remaining.z) <= CONTACT_EPSILON && (at.z <= box.minZ || at.z >= box.maxZ)) continue
            val overlap = overlapExit(box, at, remaining)
            if (overlap != null) {
                if (remaining.dot(overlap) < -CONTACT_EPSILON) { closest = 0.0; normal = overlap }
                continue
            }
            val hit = box.rayTrace(at, remaining, distance) ?: continue
            val face = hit.hitBlockFace?.direction ?: continue
            if (remaining.dot(face) >= -CONTACT_EPSILON) continue
            val along = hit.hitPosition.distance(at)
            if (along <= closest) { closest = along; normal = face }
        }
        val plane = normal ?: run {
            at.add(remaining)
            return contact?.let { CitizenCollisionMove(at, it) }
        }
        val advance = (closest - CONTACT_EPSILON).coerceAtLeast(0.0)
        at.add(remaining.clone().multiply(advance / distance))
        if (contact == null) contact = at.clone()
        remaining.multiply(1 - advance / distance)
        remaining.subtract(plane.clone().multiply(remaining.dot(plane)))
    }
    return contact?.let { CitizenCollisionMove(at, it) }
}

private fun bodyAt(at: Vector, halfX: Double, height: Double, halfZ: Double) =
    BoundingBox(at.x - halfX, at.y, at.z - halfZ, at.x + halfX, at.y + height, at.z + halfZ)

private fun overlapExit(box: BoundingBox, at: Vector, movement: Vector): Vector? {
    if (at.x <= box.minX || at.x >= box.maxX || at.y <= box.minY ||
        at.y >= box.maxY || at.z <= box.minZ || at.z >= box.maxZ) return null
    val distances = doubleArrayOf(at.x - box.minX, box.maxX - at.x,
        at.y - box.minY, box.maxY - at.y, at.z - box.minZ, box.maxZ - at.z)
    val closest = distances.min()
    val faces = arrayOf(Vector(-1, 0, 0), Vector(1, 0, 0), Vector(0, -1, 0),
        Vector(0, 1, 0), Vector(0, 0, -1), Vector(0, 0, 1))
    return faces.indices.filter { distances[it] <= closest + CONTACT_EPSILON }
        .maxBy { movement.dot(faces[it]) }.let(faces::get)
}

private const val CONTACT_EPSILON = 1.0e-7
