package ru.arc.civs

import org.bukkit.Location
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.data.type.Door
import org.bukkit.util.BoundingBox
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Owns one route and its main-thread movement. The same detached A* serves async jobs and
 * deterministic direct callers; every accepted move checks fresh full-body native geometry.
 */
internal class CitizenPacketRoute(
    private val world: World,
    from: Location,
    private val target: Location,
    private val allowed: (CivPosition) -> Boolean,
    private val stepAllowed: (CitizenPoint, CitizenPoint) -> Boolean,
    private val travelCost: (org.bukkit.block.Block) -> Double,
    private val avoiding: List<BoundingBox> = emptyList(),
) {
    private class Waypoint(val start: CitizenPoint, val point: CitizenPoint, val corner: Boolean = true,
                           val action: CitizenRouteAction = CitizenRouteAction.WALK) {
        private val length = sqrt(start.distanceSquared(point))
        private var highWater = 0.0

        private fun projection(at: CitizenPoint): Double = if (length < .000001) 0.0 else
            (((at.x - start.x) * (point.x - start.x) + (at.y - start.y) * (point.y - start.y) +
                (at.z - start.z) * (point.z - start.z)) / length).coerceIn(0.0, length)

        fun observe(at: CitizenPoint) { highWater = max(highWater, projection(at)) }
        fun behindHighWater(at: CitizenPoint): Boolean = projection(at) + .000001 < highWater

        fun walked(from: CitizenPoint, to: CitizenPoint): Double {
            // A changed observed position can raise the watermark, but only this accepted
            // walk earns progress. Revisiting a covered part of the segment earns none.
            observe(from)
            val projected = projection(to)
            val earned = (projected - highWater).coerceAtLeast(0.0)
            highWater = max(highWater, projected)
            return min(earned, sqrt(from.distanceSquared(to)))
        }
    }
    private val origin = CitizenPoint(from.x, from.y, from.z)
    private val minimumY = world.minHeight
    private val maximumY = world.maxHeight
    private val path = ArrayDeque<Waypoint>()
    private var visited = 0
    private var asyncCancellation: (() -> Unit)? = null
    private var directSearch: CitizenNavigationSearch? = null
    private var directCapture: CitizenNavigationCapture? = null
    private val directTerrain = hashMapOf<CivPosition, CitizenTerrainBlock>()
    private var fallingSpeed = 0.0
    var preparationPhase = "queued"
        private set
    var capturedBlocks = 0
        private set
    var searchNanos = 0L
        private set
    var searching = true
        private set
    var failure: String? = null
        private set
    var finished = false
        private set
    var waitingForCollision = false
        private set
    var turning = false
        private set
    var forwardProgress = 0.0
        private set
    val navigating: Boolean get() = searching || failure == null && !finished
    val destination: Location? get() = path.firstOrNull()?.point?.let { Location(world, it.x, it.y, it.z) }
    val nodeCount: Int get() = visited
    val endpoint: CitizenPoint? get() = path.lastOrNull()?.point
    val remainingNodes: Int get() = path.size

    fun cancel() {
        searching = false
        finished = true
        preparationPhase = "cancelled"
        asyncCancellation?.invoke()
        asyncCancellation = null
        releaseSearch()
        path.clear()
    }

    fun replan(from: Location, bodies: List<BoundingBox>): CitizenPacketRoute =
        CitizenPacketRoute(world, from, target, allowed, stepAllowed, travelCost, bodies.map(BoundingBox::clone))

    private fun permits(from: CitizenPoint, to: CitizenPoint): Boolean = stepAllowed(from, to) &&
        citizenSlideBody(org.bukkit.util.Vector(from.x, from.y, from.z), org.bukkit.util.Vector(to.x, to.y, to.z),
            .299, 1.798, .299, avoiding) == null

    /** Direct deterministic driver; production requests use the owner/executor and the identical solver. */
    fun expand() {
        if (!searching || asyncCancellation != null) return
        val search = directSearch ?: newSearch().also { directSearch = it }
        try {
            val capture = directCapture
            if (capture != null && !capture.step()) return
            val result = search.advance(capture?.result.orEmpty())
            recordSearch(result)
            if (result.probes.isNotEmpty()) {
                preparationPhase = "capturing"
                directCapture = CitizenNavigationCapture(this, result.probes, directTerrain)
            } else acceptSearch(result)
        } catch (_: CitizenNavigationCaptureLimit) {
            fail("capture-budget")
        }
    }

    internal fun newSearch() = CitizenNavigationSearch(origin, CitizenPoint(target.x, target.y, target.z),
        minimumY, maximumY, avoiding.map { CitizenTerrainBox(it.minX, it.minY, it.minZ, it.maxX, it.maxY, it.maxZ) })

    internal fun attachAsync(cancel: () -> Unit) {
        check(directSearch == null) { "A direct route cannot be submitted after search started" }
        asyncCancellation = cancel
        preparationPhase = "queued"
    }

    internal fun phase(value: String) { if (searching) preparationPhase = value }
    internal fun recordSearch(result: CitizenSearchResult) {
        visited = result.nodes
        searchNanos += result.searchNanos
    }
    internal fun planningFailed(reason: String) { if (searching) fail(reason) }
    internal fun captureLimits() = minimumY to maximumY

    /** One bounded native capture. The resulting graph contains no World/Block or mutable shapes. */
    internal fun captureBlock(point: CivPosition): CitizenTerrainBlock {
        if (point.y !in minimumY until maximumY || !citizenChunkReady(world, point.x shr 4, point.z shr 4))
            return CitizenTerrainBlock.UNAVAILABLE
        val block = world.getBlockAt(point.x, point.y, point.z)
        val boxes = block.collisionShape.boundingBoxes.map {
            CitizenTerrainBox(it.minX, it.minY, it.minZ, it.maxX, it.maxY, it.maxZ)
        }
        capturedBlocks++
        return CitizenTerrainBlock(true, block.isLiquid, block.blockData is Door,
            Tag.CLIMBABLE.isTagged(block.type), citizenCampfireLit(block), allowed(point), travelCost(block), boxes)
    }

    internal fun capturePermission(from: CitizenPoint, to: CitizenPoint): Boolean = stepAllowed(from, to)

    internal fun acceptSearch(result: CitizenSearchResult) {
        if (!searching) return
        result.failure?.let { fail(it); return }
        val steps = result.path?.toMutableList() ?: return
        // A nudge must not introduce a needless centre-and-reverse segment. The shortcut is
        // checked against current native geometry on this owner thread before accepting it.
        if (steps.size > 1 && steps.first().point.let {
                floor(it.x) == floor(origin.x) && floor(it.z) == floor(origin.z) && it.y == origin.y
            }) {
            val next = steps[1].point
            if (next.y == origin.y && permits(origin, next) && citizenPacketStepClear(world, origin, next, true)) steps.removeAt(0)
        }
        var previous = origin
        for (step in steps) {
            path.addLast(Waypoint(previous, step.point, action = step.action))
            previous = step.point
        }
        searching = false
        preparationPhase = "ready"
        asyncCancellation = null
        releaseSearch()
    }

    /**
     * Native reads here intentionally bypass the planning cache. A new wall or unloaded chunk
     * stops the route before the actor moves; the domain owner decides retry or safe recovery.
     */
    fun move(from: Location, speed: Double,
             movementAllowed: (Location, Location) -> Boolean = { _, _ -> true },
             openDoor: (Location) -> Boolean): Location? {
        waitingForCollision = false
        turning = false
        if (searching || failure != null || finished) return null
        val observed = CitizenPoint(from.x, from.y, from.z)
        path.firstOrNull()?.observe(observed)
        roundCorner(observed)
        // A large handoff turns in place first. Translating backwards while the body
        // catches up makes the native humanoid renderer reverse its own body heading.
        val stride = .215 * speed.coerceIn(.1, 5.0)
        // Route gravity has its own budget; a full-block drop must not consume the
        // horizontal walk. Bound each fall to one route level and the player sweep.
        val fallStride = ((fallingSpeed + .08) * .98).coerceAtMost(1.0)
        fun fallInPlace(): Location? {
            val entry = path.firstOrNull() ?: return null
            if (fallingSpeed == 0.0 || !gravityStep(entry, from.y)) return null
            val candidate = fall(from, from.clone().also { it.y = max(entry.point.y, from.y - fallStride) }) ?: return null
            val end = CitizenPoint(candidate.x, candidate.y, candidate.z)
            return candidate.takeIf { it.y < from.y - .000001 && permits(observed, end) &&
                citizenPacketSegmentClear(world, observed, end, false, observed, null) && movementAllowed(from, it) }
        }
        val heading = heading(from, stride)
        val pitch = from.pitch + (-from.pitch).coerceIn(-CITIZEN_TURN_PER_TICK, CITIZEN_TURN_PER_TICK)
        if (heading != null && abs(citizenAngleChange(from.yaw, heading)) > 65f) {
            turning = true
            val point = fallInPlace() ?: from.clone()
            fallingSpeed = if (abs(from.y - point.y - fallStride) < .000001) fallStride else 0.0
            return point.also { it.yaw = citizenTurn(from.yaw, heading, CITIZEN_TURN_PER_TICK); it.pitch = pitch }
        }
        var point = from.clone()
        var remaining = stride
        var moved = false
        var earned = 0.0
        var usedGravity = false
        var previousDirection: org.bukkit.util.Vector? = null
        // A normal step can cross a cell centre. Spend its remainder in this same tick,
        // never save it for a later tick or accelerate after an obstruction clears.
        for (segment in 0 until 8) {
            val entry = path.firstOrNull()
            if (entry == null) { finished = true; break }
            val next = entry.point
            val to = Location(world, next.x, next.y, next.z)
            if (!citizenChunkReady(world, to.blockX shr 4, to.blockZ shr 4) ||
                !allowed(CivPosition(to.blockX, floor(to.y + .001).toInt(), to.blockZ)) ||
                !citizenPacketSupported(world, next) ||
                !travelCost(world.getBlockAt(to.blockX, citizenSupportY(next.y), to.blockZ)).isFinite() || !openDoor(to)) {
                fail("path-changed"); break
            }
            // Lift before ascending and centre ladder entry. Ordinary downhill travel
            // follows the horizontal route while gravity settles onto native support.
            val descending = gravityStep(entry, point.y)
            val waypoint = to.clone()
            if ((entry.action == CitizenRouteAction.CLIMB || entry.action == CitizenRouteAction.LADDER_ENTER) &&
                (to.x - point.x) * (to.x - point.x) + (to.z - point.z) * (to.z - point.z) > .000001) {
                waypoint.y = point.y
            } else if (to.y > point.y + .0001) {
                waypoint.x = point.x
                waypoint.z = point.z
            } else if (descending || to.y < point.y - .0001 &&
                (to.x - point.x) * (to.x - point.x) + (to.z - point.z) * (to.z - point.z) > .000001) waypoint.y = point.y
            val delta = waypoint.toVector().subtract(point.toVector())
            val distance = delta.length()
            if (distance < .0001) {
                if (point.distanceSquared(to) < .000001) { path.removeFirst(); continue }
                if (!descending) break
            }
            val direction = if (distance < .0001) org.bukkit.util.Vector() else delta.clone().multiply(1.0 / distance)
            if (previousDirection?.let { previous ->
                    (abs(previous.y) > .0001 || abs(direction.y) > .0001) &&
                        previous.clone().crossProduct(direction).lengthSquared() > .000001
                } == true) break
            val advance = min(distance, remaining)
            var candidate = if (advance == distance) waypoint else point.clone().add(direction.clone().multiply(advance))
            if (descending) {
                val lowered = fall(from, candidate.clone().also { it.y = max(to.y, from.y - fallStride) })
                if (lowered == null) { fail("movement-blocked"); break }
                candidate = lowered
                usedGravity = true
            }
            if (point.distanceSquared(candidate) < .000000000001) break
            val a = CitizenPoint(point.x, point.y, point.z)
            val b = CitizenPoint(candidate.x, candidate.y, candidate.z)
            if (!permits(a, b) || !(if (descending) citizenPacketSegmentClear(world, a, b, false, a, null)
                    else citizenPacketStepClear(world, a, b, false))) {
                fail("movement-blocked"); break
            }
            // A client interpolates one straight segment between packets. Adjacent path legs
            // may each be clear while their shortcut clips a corner; stop at that corner then.
            val origin = CitizenPoint(from.x, from.y, from.z)
            if (moved && (!permits(origin, b) || !citizenPacketSegmentClear(world, origin, b, false, origin, null))) break
            if (!movementAllowed(from, candidate)) {
                waitingForCollision = true
                break
            }
            earned += entry.walked(a, b)
            point = candidate
            moved = true
            remaining -= advance
            previousDirection = direction
            // A supported lower node still constrains the route, but its centre is
            // not a stop in mid-air. Keep the last landing and every climb boundary.
            val nextEntry = path.elementAtOrNull(1)
            if (point.distanceSquared(to) < .000001 || descending &&
                abs(point.x - to.x) < .000001 && abs(point.z - to.z) < .000001 &&
                nextEntry != null && nextEntry.action in listOf(CitizenRouteAction.WALK, CitizenRouteAction.STEP) &&
                nextEntry.point.y <= to.y + .0001) path.removeFirst()
            if (path.isEmpty()) { finished = true; break }
            if (remaining < .000001 || advance < .000001) break
        }
        if (!moved) {
            point = fallInPlace() ?: return null
            earned += path.firstOrNull()?.walked(observed, CitizenPoint(point.x, point.y, point.z)) ?: 0.0
            usedGravity = true
        }
        fallingSpeed = if (usedGravity && abs(from.y - point.y - fallStride) < .000001) fallStride else 0.0
        forwardProgress += min(earned, from.distance(point))
        val dx = point.x - from.x
        val dz = point.z - from.z
        // Exact geometric endpoints have no facing. A pure vertical leg must keep
        // its incoming angle instead of inheriting Location's default zero yaw.
        point.yaw = from.yaw
        if (heading != null || dx * dx + dz * dz > .00001) {
            val wanted = heading ?: Math.toDegrees(atan2(-dx, dz)).toFloat()
            point.yaw = citizenTurn(from.yaw, wanted, CITIZEN_TURN_PER_TICK)
        }
        point.pitch = pitch
        return point
    }

    private fun gravityStep(entry: Waypoint, y: Double): Boolean = entry.point.y < y - .0001 &&
        entry.action in listOf(CitizenRouteAction.WALK, CitizenRouteAction.STEP) &&
        (floor(entry.start.x) != floor(entry.point.x) || floor(entry.start.z) != floor(entry.point.z))

    /** Clip gravity against the swept feet, then validate the actual packet chord above. */
    private fun fall(from: Location, candidate: Location): Location? {
        val floors = mutableListOf<BoundingBox>()
        for (x in floor(min(from.x, candidate.x) - .299).toInt()..floor(max(from.x, candidate.x) + .299).toInt())
            for (z in floor(min(from.z, candidate.z) - .299).toInt()..floor(max(from.z, candidate.z) + .299).toInt()) {
                if (!citizenChunkReady(world, x shr 4, z shr 4)) return null
                for (y in floor(candidate.y).toInt() - 1..floor(from.y).toInt()) {
                    if (y !in minimumY until maximumY) return null
                    floors += citizenCollisionSnapshot(world, x, y, z).boxes.filter {
                        it.maxY <= from.y + .000001 && it.maxY >= candidate.y
                    }
                }
            }
        // Gravity may change only Y: a general body slide can also clip X/Z at a
        // taller neighbouring floor. Keeping its Y but restoring the intended X/Z
        // would move inside that floor. Stay above every support crossed by this
        // packet's horizontal footprint; the caller still validates its full body.
        val horizontalFrom = org.bukkit.util.Vector(from.x, .5, from.z)
        val horizontalTo = org.bukkit.util.Vector(candidate.x, .5, candidate.z)
        val direction = horizontalTo.clone().subtract(horizontalFrom)
        val distance = direction.length()
        for (floor in floors) {
            val footprint = BoundingBox(floor.minX - .299 + .0000001, 0.0, floor.minZ - .299 + .0000001,
                floor.maxX + .299 - .0000001, 1.0, floor.maxZ + .299 - .0000001)
            if (footprint.contains(horizontalFrom) || footprint.contains(horizontalTo) ||
                distance > .0000001 && footprint.rayTrace(horizontalFrom, direction, distance) != null)
                candidate.y = max(candidate.y, floor.maxY)
        }
        return candidate
    }

    /**
     * Round one flat corner inside its existing cell. The quadratic stays within .15
     * blocks of the authored legs; each short chord retains live support, protection,
     * body clearance and surface cost. Stairs, ladders and tight corners keep their nodes.
     */
    private fun roundCorner(from: CitizenPoint) {
        val first = path.firstOrNull()?.takeIf { it.corner } ?: return
        if (first.behindHighWater(from)) return
        val corner = first.point
        val next = path.elementAtOrNull(1)?.point ?: return
        if (abs(from.y - corner.y) > .0001 || abs(next.y - corner.y) > .0001) return
        val incoming = org.bukkit.util.Vector(corner.x - from.x, 0.0, corner.z - from.z)
        val outgoing = org.bukkit.util.Vector(next.x - corner.x, 0.0, next.z - corner.z)
        val distance = incoming.length()
        val ahead = outgoing.length()
        if (distance !in .15.. .45 || ahead < .6) return
        incoming.multiply(1.0 / distance)
        outgoing.multiply(1.0 / ahead)
        val angle = incoming.dot(outgoing)
        if (angle > .999 || angle < -.01) return
        val radius = min(.4, ahead * .45)
        val end = CitizenPoint(corner.x + outgoing.x * radius, corner.y, corner.z + outgoing.z * radius)
        val rounded = (1..6).map { index ->
            val t = index / 6.0
            val a = (1 - t) * (1 - t)
            val b = 2 * (1 - t) * t
            val c = t * t
            CitizenPoint(a * from.x + b * corner.x + c * end.x, corner.y,
                a * from.z + b * corner.z + c * end.z)
        }
        val cost = travelCost(world.getBlockAt(floor(corner.x).toInt(), citizenSupportY(corner.y), floor(corner.z).toInt()))
        var previous = from
        for (point in rounded) {
            for (x in floor(point.x - .299).toInt()..floor(point.x + .299).toInt())
                for (z in floor(point.z - .299).toInt()..floor(point.z + .299).toInt())
                    if (!allowed(CivPosition(x, floor(point.y + .001).toInt(), z))) return
            if (!citizenPacketSupported(world, point) || !permits(previous, point) ||
                !citizenPacketStepClear(world, previous, point, false) ||
                travelCost(world.getBlockAt(floor(point.x).toInt(), citizenSupportY(point.y), floor(point.z).toInt())) > cost) return
            previous = point
        }
        previous = from
        val waypoints = rounded.map { point -> Waypoint(previous, point, false).also { previous = point } }
        path.removeFirst()
        for (waypoint in waypoints.asReversed()) path.addFirst(waypoint)
    }

    /** Anticipate a corner along the validated polyline; never move along this look-ahead chord. */
    private fun heading(from: Location, stride: Double): Float? {
        var previous = CitizenPoint(from.x, from.y, from.z)
        // A right-angle turn takes twelve frames at 8 degrees per frame. Begin its
        // first half before the corner, including when Trails increases the stride.
        var remaining = (stride * 6).coerceIn(.65, 2.0)
        var aim = previous
        for (waypoint in path) {
            val next = waypoint.point
            val changesLevel = abs(next.y - from.y) > .13
            // Settle or climb in the current column without turning toward its centre.
            // A step into another column must be faced before lifting off the support.
            if (changesLevel && floor(next.x).toInt() == from.blockX && floor(next.z).toInt() == from.blockZ) break
            val dx = next.x - previous.x
            val dz = next.z - previous.z
            val distance = sqrt(dx * dx + dz * dz)
            if (distance > .0001) {
                val part = min(1.0, remaining / distance)
                aim = CitizenPoint(previous.x + dx * part, from.y, previous.z + dz * part)
                remaining -= distance * part
                if (remaining <= .0001) break
            }
            if (changesLevel) break
            previous = next
        }
        val dx = aim.x - from.x
        val dz = aim.z - from.z
        return if (dx * dx + dz * dz > .00001) Math.toDegrees(atan2(-dx, dz)).toFloat() else null
    }

    private fun fail(reason: String) {
        failure = reason
        searching = false
        preparationPhase = "failed"
        asyncCancellation?.invoke()
        asyncCancellation = null
        path.clear()
        releaseSearch()
    }

    private fun releaseSearch() {
        directSearch?.cancel()
        directSearch = null
        directCapture = null
        directTerrain.clear()
    }

}

internal const val CITIZEN_TURN_PER_TICK = 8f
internal fun citizenAngleChange(from: Float, to: Float): Float = (((to - from) % 360f + 540f) % 360f) - 180f
internal fun citizenTurn(from: Float, to: Float, limit: Float): Float =
    from + citizenAngleChange(from, to).coerceIn(-limit, limit)

/** The tick owner applies an authored look once; action callbacks only change its target. */
internal fun citizenFace(npc: ru.arc.paper.npc.PacketPlayerNpc, yaw: Float, pitch: Float, limit: Float = CITIZEN_TURN_PER_TICK) {
    npc.bodyYaw = citizenTurn(npc.bodyYaw, yaw, limit)
    npc.headYaw = citizenTurn(npc.headYaw, yaw, limit)
    npc.pitch += (pitch - npc.pitch).coerceIn(-limit, limit)
}

internal data class CitizenCollisionSnapshot(val liquid: Boolean, val door: Boolean,
    val localBoxes: List<BoundingBox>, val boxes: List<BoundingBox>)

private fun citizenCollisionSnapshot(world: World, x: Int, y: Int, z: Int): CitizenCollisionSnapshot {
    val block = world.getBlockAt(x, y, z)
    val local = block.collisionShape.boundingBoxes.map { it.clone() }
    return CitizenCollisionSnapshot(block.isLiquid, block.blockData is Door, local,
        local.map { it.clone().shift(x.toDouble(), y.toDouble(), z.toDouble()) })
}

/** Collision shapes are local to their block; check both body width and headroom. */
internal fun citizenPacketBodyClear(world: World, point: CitizenPoint, allowDoors: Boolean,
                                    allowLiquids: Boolean = false, allowBelowWorld: Boolean = false,
                                    collision: ((Int, Int, Int) -> CitizenCollisionSnapshot)? = null): Boolean {
    val body = BoundingBox(point.x - .299, point.y + .001, point.z - .299, point.x + .299, point.y + 1.799, point.z + .299)
    for (x in floor(body.minX).toInt()..floor(body.maxX).toInt()) for (z in floor(body.minZ).toInt()..floor(body.maxZ).toInt()) {
        if (!citizenChunkReady(world, x shr 4, z shr 4)) return false
        for (y in floor(body.minY).toInt()..floor(body.maxY).toInt()) {
            if (y < world.minHeight) {
                if (allowBelowWorld) continue
                return false
            }
            if (y >= world.maxHeight) return false
            val state = collision?.invoke(x, y, z) ?: citizenCollisionSnapshot(world, x, y, z)
            if (state.liquid && !allowLiquids) return false
            if (allowDoors && state.door) continue
            if (state.boxes.any(body::overlaps)) return false
        }
    }
    return true
}

internal fun citizenPacketSupported(world: World, point: CitizenPoint): Boolean {
    val x = floor(point.x).toInt()
    val z = floor(point.z).toInt()
    val y = floor(point.y + .001).toInt()
    if (!citizenChunkReady(world, x shr 4, z shr 4) || y <= world.minHeight || y >= world.maxHeight) return false
    if (Tag.CLIMBABLE.isTagged(world.getBlockAt(x, y, z).type)) return true
    val by = citizenSupportY(point.y)
    val block = world.getBlockAt(x, by, z)
    if (block.isLiquid) return false
    return block.collisionShape.boundingBoxes.any {
        abs(by + it.maxY - point.y) <= .13 && point.x - x in it.minX..it.maxX && point.z - z in it.minZ..it.maxZ
    }
}

/** Low campfire collision shapes are solid footholds but unsafe for the whole resident body. */
internal fun citizenPacketCampfireClear(world: World, point: CitizenPoint, from: CitizenPoint? = null,
                                       halfWidth: Double = .299, height: Double = 1.8): Boolean {
    fun bodyAt(at: CitizenPoint) = BoundingBox(at.x - halfWidth, at.y - .001, at.z - halfWidth,
        at.x + halfWidth, at.y + height - .001, at.z + halfWidth)
    val body = bodyAt(point)
    val occupied = from?.let(::bodyAt)
    for (x in floor(body.minX).toInt()..floor(body.maxX).toInt()) for (z in floor(body.minZ).toInt()..floor(body.maxZ).toInt()) {
        if (!citizenChunkReady(world, x shr 4, z shr 4)) return false
        for (y in floor(body.minY).toInt()..floor(body.maxY).toInt()) {
            if (y !in world.minHeight until world.maxHeight) return false
            if (citizenCampfireLit(world.getBlockAt(x, y, z)) &&
                // An already exposed resident may leave its occupied fire cell, never enter a new one.
                occupied?.overlaps(BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0)) != true) return false
        }
    }
    return true
}

/**
 * Path/farmland support is 1/16 below a full block. Its edge to the next full
 * level spans 17/16, including the centre of a bottom stair. Admit only that
 * extra height class; a few thousandths account for observed rounded feet Y.
 * Native support, full-body sweeps and permissions remain separate checks.
 */
internal fun citizenStepHeightAllowed(fromY: Double, toY: Double): Boolean {
    val lower = min(fromY, toY)
    val upper = max(fromY, toY)
    if (upper - lower <= 1.01) return true
    return upper - lower <= 17.0 / 16.0 + .005 &&
        abs(lower - (floor(lower) + 15.0 / 16.0)) <= .005 &&
        abs(upper - kotlin.math.round(upper)) <= .005
}

internal fun citizenPacketStepClear(world: World, from: CitizenPoint, to: CitizenPoint, allowDoors: Boolean,
                                   collision: ((Int, Int, Int) -> CitizenCollisionSnapshot)? = null): Boolean {
    if (!citizenStepHeightAllowed(from.y, to.y)) return false
    if (from.y != to.y) {
        val high = max(from.y, to.y)
        val aboveFrom = CitizenPoint(from.x, high, from.z)
        val aboveTo = CitizenPoint(to.x, high, to.z)
        return citizenPacketSegmentClear(world, from, aboveFrom, allowDoors, from, collision) &&
            citizenPacketSegmentClear(world, aboveFrom, aboveTo, allowDoors, from, collision) &&
            citizenPacketSegmentClear(world, aboveTo, to, allowDoors, from, collision)
    }
    return citizenPacketSegmentClear(world, from, to, allowDoors, from, collision)
}

private fun citizenPacketSegmentClear(world: World, from: CitizenPoint, to: CitizenPoint, allowDoors: Boolean,
                                      occupied: CitizenPoint, collision: ((Int, Int, Int) -> CitizenCollisionSnapshot)?): Boolean {
    // Both ends of a short rounded corner can be clear while its middle clips a
    // thin door panel. Sweep the same body used below through native block boxes.
    val obstacles = mutableListOf<BoundingBox>()
    for (x in floor(min(from.x, to.x) - .299).toInt()..floor(max(from.x, to.x) + .299).toInt())
        for (z in floor(min(from.z, to.z) - .299).toInt()..floor(max(from.z, to.z) + .299).toInt()) {
            if (!citizenChunkReady(world, x shr 4, z shr 4)) return false
            for (y in floor(min(from.y, to.y) + .001).toInt()..floor(max(from.y, to.y) + 1.799).toInt()) {
                if (y !in world.minHeight until world.maxHeight) return false
                val state = collision?.invoke(x, y, z) ?: citizenCollisionSnapshot(world, x, y, z)
                if (state.liquid) obstacles += BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0)
                else if (!allowDoors || !state.door) obstacles += state.boxes
            }
        }
    if (citizenSlideBody(org.bukkit.util.Vector(from.x, from.y + .001, from.z),
            org.bukkit.util.Vector(to.x, to.y + .001, to.z), .299, 1.798, .299, obstacles) != null) return false
    val distance = sqrt(from.distanceSquared(to))
    val samples = kotlin.math.ceil(distance / .15).toInt().coerceIn(1, 16)
    for (index in 1..samples) {
        val t = index.toDouble() / samples
        val point = CitizenPoint(from.x + (to.x - from.x) * t,
            from.y + (to.y - from.y) * t,
            from.z + (to.z - from.z) * t)
        if (!citizenPacketBodyClear(world, point, allowDoors, collision = collision) || !citizenPacketCampfireClear(world, point, occupied)) return false
    }
    return true
}
