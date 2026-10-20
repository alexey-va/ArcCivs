package ru.arc.civs

import org.bukkit.Bukkit
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
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor

/**
 * Physical resident bodies belong to this domain, not to the packet renderer.
 * Native-style contact adds a small horizontal impulse; a resident is never a solid
 * barrier or a reason to rewrite/cancel the player's observed movement.
 */
internal class CivsResidentCollisions(
    plugin: Plugin,
    private val actors: () -> Collection<PacketPlayerNpc>,
    private val nudgeResident: (PacketPlayerNpc, Vector) -> Boolean,
) : Listener, AutoCloseable {
    private var closed = false
    private var contactTick = Int.MIN_VALUE
    private val pushedPlayers = hashSetOf<UUID>()
    private val pushedResidents = hashSetOf<UUID>()

    init { plugin.server.pluginManager.registerEvents(this, plugin) }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (closed || event is PlayerTeleportEvent || !event.hasChangedPosition()) return
        val player = event.player
        if (!participates(player)) return
        val from = event.from
        val to = event.to ?: return
        if (from.world != to.world || player.world != from.world) return
        val nativeBody = player.boundingBox
        val body = bodyAt(to.toVector(), nativeBody.widthX / 2, nativeBody.height, nativeBody.widthZ / 2)
        // ponytail: the resident runtime owns the hard 48-actor cap. No world/entity scan.
        for (npc in actors()) {
            if (!npc.isValid || npc.world != from.world || npc.pose == PacketNpcPose.SLEEPING ||
                !residentBody(npc, npc.location).overlaps(body)) continue
            pushApart(player, to.toVector(), npc, to.toVector().subtract(from.toVector()))
        }
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
            .filter(::participates)
        val bodies = players.map(Player::getBoundingBox)
        // Recovery admission is a read-only occupancy probe, never an impulse source.
        if (from.x == to.x && from.y == to.y && from.z == to.z) return bodies.none(last::overlaps)
        if (citizenSlideBody(from.toVector(), to.toVector(), first.widthX / 2,
                first.height, first.widthZ / 2, bodies) == null) return true
        for (player in players) if (first.overlaps(player.boundingBox) || last.overlaps(player.boundingBox))
            pushApart(player, player.location.toVector(), npc, from.toVector().subtract(to.toVector()))
        return false
    }

    private fun pushApart(player: Player, playerAt: Vector, npc: PacketPlayerNpc, movement: Vector) {
        val tick = Bukkit.getCurrentTick()
        if (contactTick != tick) {
            contactTick = tick
            pushedPlayers.clear()
            pushedResidents.clear()
        }
        val away = npc.location.toVector().subtract(playerAt).setY(0.0)
        if (away.lengthSquared() < .000001) away.copy(movement).setY(0.0)
        if (away.lengthSquared() < .000001) away.setX(1.0)
        // Entity.push uses a horizontal 0.05 impulse. Bound each body to one contact
        // per tick so repeated move packets or a crowd cannot stack stronger pushes.
        away.normalize().multiply(.05)
        if (pushedPlayers.add(player.uniqueId)) player.velocity = player.velocity.clone().subtract(away)
        if (!pushedResidents.add(npc.uniqueId)) return
        if (nudgeResident(npc, away.clone())) return
        // A wall behind the resident may leave either side free. If all three are
        // blocked the player still passes; no velocity or position correction grows.
        val tangent = Vector(-away.z, 0.0, away.x)
        if (!nudgeResident(npc, tangent)) nudgeResident(npc, tangent.clone().multiply(-1.0))
    }

    override fun close() {
        if (closed) return
        closed = true
        pushedPlayers.clear()
        pushedResidents.clear()
        HandlerList.unregisterAll(this)
    }

    private fun participates(player: Player): Boolean = player.isOnline && player.isValid && !player.isDead &&
        player.gameMode != GameMode.SPECTATOR && !player.isInsideVehicle && !player.isSleeping && player.isCollidable

    private fun residentBody(npc: PacketPlayerNpc, at: Location) =
        bodyAt(at.toVector(), .3 * npc.scale, npc.height, .3 * npc.scale)
}

/** A scaled body may move a single small horizontal step only through loaded, supported space. */
internal fun citizenResidentNudgeClear(npc: PacketPlayerNpc, from: Location, to: Location): Boolean {
    if (!npc.isValid || from.world != to.world || from.world != npc.world || from.y != to.y ||
        !from.x.isFinite() || !from.y.isFinite() || !from.z.isFinite() ||
        !to.x.isFinite() || !to.z.isFinite() || from.distanceSquared(to) > .050001 * .050001) return false
    val world = npc.world
    val half = .3 * npc.scale - .001
    val body = bodyAt(from.toVector().add(Vector(0.0, .001, 0.0)), half, npc.height - .002, half)
        .union(bodyAt(to.toVector().add(Vector(0.0, .001, 0.0)), half, npc.height - .002, half))
    for (x in floor(body.minX).toInt()..floor(body.maxX).toInt())
        for (z in floor(body.minZ).toInt()..floor(body.maxZ).toInt()) {
            if (!citizenChunkReady(world, x shr 4, z shr 4)) return false
            // Fences and walls can project above the block row below the resident's feet.
            for (y in (floor(body.minY).toInt() - 1)..floor(body.maxY).toInt()) {
                if (y !in world.minHeight until world.maxHeight) return false
                val block = world.getBlockAt(x, y, z)
                if (block.isLiquid || block.collisionShape.boundingBoxes.any {
                    body.overlaps(it.clone().shift(x.toDouble(), y.toDouble(), z.toDouble()))
                }) return false
            }
        }
    return citizenPacketSupported(world, CitizenPoint(to.x, to.y, to.z))
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
