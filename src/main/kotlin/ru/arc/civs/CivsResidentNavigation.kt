package ru.arc.civs

import org.bukkit.Location
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.data.type.Door
import org.bukkit.util.BoundingBox
import java.util.ArrayDeque
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One fair search budget for the whole resident runtime. All world reads stay on the server thread. */
internal class CivsResidentNavigation {
    private val searching = ArrayDeque<CitizenPacketRoute>()

    fun request(world: World, from: Location, to: Location, allowed: (CivPosition) -> Boolean,
                stepAllowed: (CitizenPoint, CitizenPoint) -> Boolean,
                travelCost: (org.bukkit.block.Block) -> Double): CitizenPacketRoute =
        CitizenPacketRoute(world, from, to, allowed, stepAllowed, travelCost).also(searching::addLast)

    fun replan(route: CitizenPacketRoute, from: Location, bodies: List<BoundingBox> = emptyList()): CitizenPacketRoute =
        route.replan(from, bodies).also(searching::addLast)

    fun pulse() {
        val deadline = System.nanoTime() + SEARCH_BUDGET_NANOS
        var remaining = SEARCH_OPERATIONS_PER_TICK
        while (remaining-- > 0 && searching.isNotEmpty()) {
            val route = searching.removeFirst()
            if (route.searching) route.expand()
            if (route.searching) searching.addLast(route)
            if (System.nanoTime() >= deadline) break
        }
    }

    fun clear() { searching.forEach(CitizenPacketRoute::cancel); searching.clear() }

    companion object {
        const val SEARCH_OPERATIONS_PER_TICK = 384
        const val SEARCH_BUDGET_NANOS = 1_000_000L
    }
}

/**
 * Incremental, loaded-terrain A*. Native collision shapes are cached only while planning;
 * every movement is checked against fresh shapes, including the full 0.6 by 1.8 body.
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
    private data class Cell(val x: Int, val y: Double, val z: Int, val climbing: Boolean = false, val initial: Boolean = false) {
        fun point() = CitizenPoint(x + .5, y, z + .5)
    }
    private data class Entry(val cell: Cell, val cost: Double, val score: Double, val order: Int)
    private class Waypoint(val start: CitizenPoint, val point: CitizenPoint, val corner: Boolean = true) {
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
    private val start = Cell(from.blockX, from.y, from.blockZ, initial = true)
    private val origin = CitizenPoint(from.x, from.y, from.z)
    private val open = PriorityQueue<Entry>(compareBy<Entry> { it.score }.thenBy { it.order })
    private val costs = hashMapOf(start to 0.0)
    private val parents = hashMapOf<Cell, Cell>()
    private val cells = hashMapOf<Triple<Int, Double, Int>, List<Cell>>()
    private val collisions = hashMapOf<CivPosition, CitizenCollisionSnapshot>()
    private val weights = hashMapOf<Cell, Double>()
    private val path = ArrayDeque<Waypoint>()
    private var expanding: Entry? = null
    private var edges: Iterator<Cell>? = null
    private var serial = 0
    private var visited = 0
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

    init { open += Entry(start, 0.0, heuristic(start), serial++) }

    fun cancel() { searching = false; finished = true; releaseSearch(); path.clear() }

    fun replan(from: Location, bodies: List<BoundingBox>): CitizenPacketRoute =
        CitizenPacketRoute(world, from, target, allowed, stepAllowed, travelCost, bodies.map(BoundingBox::clone))

    private fun permits(from: CitizenPoint, to: CitizenPoint): Boolean = stepAllowed(from, to) &&
        citizenSlideBody(org.bukkit.util.Vector(from.x, from.y, from.z), org.bukkit.util.Vector(to.x, to.y, to.z),
            .299, 1.798, .299, avoiding) == null

    fun expand() {
        if (!searching) return
        if (expanding?.cell?.let { !citizenChunkReady(world, it.x shr 4, it.z shr 4) } == true) {
            fail("chunk-unloaded")
            return
        }
        val pending = edges
        if (pending != null && pending.hasNext()) {
            val current = requireNotNull(expanding)
            val next = pending.next()
            // A resident can start anywhere inside a path/slab cell after a push or an
            // earlier arrival. Validating from its centre can admit a blocked first corner.
            val from = if (current.cell == start) origin else current.cell.point()
            if (abs(next.y - current.cell.y) <= 1.01 && permits(from, next.point()) &&
                citizenPacketStepClear(world, from, next.point(), allowDoors = true, collision = ::collision)) offer(current, next)
            return
        }
        edges = null
        expanding = null
        var current = open.poll()
        while (current != null && costs[current.cell] != current.cost) current = open.poll()
        if (current == null) { fail("no-path"); return }
        if (++visited > 4096) { fail("search-budget"); return }
        val cell = current.cell
        if (!citizenChunkReady(world, cell.x shr 4, cell.z shr 4)) { fail("chunk-unloaded"); return }
        if (cell.x == target.blockX && cell.z == target.blockZ && abs(cell.y - target.y) <= .5) {
            val reversed = ArrayDeque<Cell>()
            var node = cell
            while (node != start) {
                reversed.addFirst(node)
                node = parents[node] ?: run { fail("broken-path"); return }
            }
            // A path within the starting cell still has to reach its real authored endpoint.
            if (reversed.isEmpty()) reversed += Cell(target.blockX, target.y, target.blockZ)
            // Replanning after a nudge must not walk back to this cell's centre and
            // immediately reverse again. Keep that node when it is needed to clear a corner.
            if (reversed.size > 1 && reversed.first().let { it.x == start.x && it.z == start.z && it.y == origin.y }) {
                val next = reversed.elementAt(1).point()
                if (next.y == origin.y && permits(origin, next) &&
                    citizenPacketStepClear(world, origin, next, true, collision = ::collision)) reversed.removeFirst()
            }
            var previous = origin
            for (cell in reversed) {
                val point = cell.point()
                path.addLast(Waypoint(previous, point))
                previous = point
            }
            searching = false
            releaseSearch()
            return
        }
        expanding = current
        edges = sequence {
            // A cancelled ascent/ladder exit can leave feet between supports or off centre.
            // First reach a real support in this column before looking for a neighbour.
            if (cell == start) yieldAll(standingCells(cell.x, cell.y, cell.z))
            for (dx in -1..1) for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                val x = cell.x + dx
                val z = cell.z + dz
                yieldAll(cells.getOrPut(Triple(x, cell.y, z)) { standingCells(x, cell.y, z) })
            }
            // A quarry's ladder connects adjacent floors without inventing a solid support block.
            val at = world.getBlockAt(cell.x, floor(cell.y).toInt(), cell.z)
            if (Tag.CLIMBABLE.isTagged(at.type)) for (dy in listOf(-1, 0, 1)) {
                val next = Cell(cell.x, floor(cell.y) + dy, cell.z, true)
                if (allowed(CivPosition(next.x, floor(next.y).toInt(), next.z)) &&
                    Tag.CLIMBABLE.isTagged(world.getBlockAt(next.x, floor(next.y).toInt(), next.z).type) &&
                    citizenPacketBodyClear(world, next.point(), true, collision = ::collision)) yield(next)
            }
        }.iterator()
    }

    private fun offer(previous: Entry, next: Cell) {
        val p = next.point()
        if (abs(p.x - start.point().x) > 96 || abs(p.z - start.point().z) > 96 ||
            abs(p.y - start.y) > 96) return
        val distance = sqrt((if (previous.cell == start) origin else previous.cell.point()).distanceSquared(p))
        val weight = weights.getOrPut(next) {
            travelCost(world.getBlockAt(next.x, citizenSupportY(next.y), next.z))
        }
        if (!weight.isFinite()) return
        // Preferences change planning cost, never velocity. The minimum cost remains one.
        val cost = previous.cost + distance * weight.coerceIn(1.0, CivsPaths.ORDINARY_COST)
        if (cost >= (costs[next] ?: Double.POSITIVE_INFINITY)) return
        costs[next] = cost
        parents[next] = previous.cell
        open += Entry(next, cost, cost + heuristic(next), serial++)
    }

    private fun heuristic(cell: Cell): Double {
        val dx = cell.x + .5 - target.x
        val dz = cell.z + .5 - target.z
        // A path or slab may finish within the admitted half-block vertical tolerance.
        val dy = max(0.0, abs(cell.y - target.y) - .5)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun collision(x: Int, y: Int, z: Int): CitizenCollisionSnapshot {
        val key = CivPosition(x, y, z)
        return collisions[key] ?: citizenCollisionSnapshot(world, x, y, z).also {
            // Each search is already bounded by 4096 expanded cells and a 96-block region.
            // Cap shape retention independently when one region contains many small shapes.
            if (collisions.size < 16_384) collisions[key] = it
        }
    }

    private fun standingCells(x: Int, y: Double, z: Int): List<Cell> {
        if (!citizenChunkReady(world, x shr 4, z shr 4)) return emptyList()
        val result = linkedSetOf<Cell>()
        val base = floor(y).toInt()
        for (by in base - 2..base + 1) {
            if (by < world.minHeight || by + 2 >= world.maxHeight) continue
            val block = world.getBlockAt(x, by, z)
            if (block.isLiquid) continue
            if (Tag.CLIMBABLE.isTagged(block.type)) {
                val point = CitizenPoint(x + .5, by.toDouble(), z + .5)
                if (allowed(CivPosition(x, by, z)) && citizenPacketBodyClear(world, point, true, collision = ::collision))
                    result += Cell(x, by.toDouble(), z, true)
            }
            for (box in collision(x, by, z).localBoxes) {
                if (box.maxY <= 0 || box.minX > .5 || box.maxX < .5 || box.minZ > .5 || box.maxZ < .5) continue
                val height = by + box.maxY
                if (abs(height - y) > 1.01) continue
                val point = CitizenPoint(x + .5, height, z + .5)
                if (allowed(CivPosition(x, floor(height + .001).toInt(), z)) &&
                    citizenPacketBodyClear(world, point, true, collision = ::collision)) result += Cell(x, height, z)
            }
        }
        return result.toList()
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
        val heading = heading(from, stride)
        val pitch = from.pitch + (-from.pitch).coerceIn(-CITIZEN_TURN_PER_TICK, CITIZEN_TURN_PER_TICK)
        if (heading != null && abs(citizenAngleChange(from.yaw, heading)) > 65f) {
            turning = true
            return from.clone().also { it.yaw = citizenTurn(from.yaw, heading, CITIZEN_TURN_PER_TICK); it.pitch = pitch }
        }
        var point = from.clone()
        var remaining = stride
        var moved = false
        var earned = 0.0
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
            // Keep vertical and horizontal legs in separate packets: interpolating diagonally
            // from below a step to its top would cut through the solid support's side.
            val waypoint = to.clone()
            if (to.y > point.y + .0001) {
                waypoint.x = point.x
                waypoint.z = point.z
            } else if (to.y < point.y - .0001 &&
                (to.x - point.x) * (to.x - point.x) + (to.z - point.z) * (to.z - point.z) > .000001) {
                waypoint.y = point.y
            }
            val delta = waypoint.toVector().subtract(point.toVector())
            val distance = delta.length()
            if (distance < .0001) {
                if (point.distanceSquared(to) < .000001) { path.removeFirst(); continue }
                break
            }
            val direction = delta.clone().multiply(1.0 / distance)
            if (previousDirection?.let { previous ->
                    (abs(previous.y) > .0001 || abs(direction.y) > .0001) &&
                        previous.clone().crossProduct(direction).lengthSquared() > .000001
                } == true) break
            val advance = min(distance, remaining)
            val candidate = if (advance == distance) waypoint else point.clone().add(direction.clone().multiply(advance))
            val a = CitizenPoint(point.x, point.y, point.z)
            val b = CitizenPoint(candidate.x, candidate.y, candidate.z)
            if (!permits(a, b) || !citizenPacketStepClear(world, a, b, false)) {
                fail("movement-blocked"); break
            }
            // A client interpolates one straight segment between packets. Adjacent path legs
            // may each be clear while their shortcut clips a corner; stop at that corner then.
            val origin = CitizenPoint(from.x, from.y, from.z)
            if (moved && (!permits(origin, b) || !citizenPacketStepClear(world, origin, b, false))) break
            if (!movementAllowed(from, candidate)) {
                waitingForCollision = true
                break
            }
            earned += entry.walked(a, b)
            point = candidate
            moved = true
            remaining -= advance
            previousDirection = direction
            if (point.distanceSquared(to) < .000001) path.removeFirst()
            if (path.isEmpty()) { finished = true; break }
            if (remaining < .000001) break
        }
        if (!moved) return null
        forwardProgress += min(earned, from.distance(point))
        val dx = point.x - from.x
        val dz = point.z - from.z
        if (heading != null || dx * dx + dz * dz > .00001) {
            val wanted = heading ?: Math.toDegrees(atan2(-dx, dz)).toFloat()
            point.yaw = citizenTurn(from.yaw, wanted, CITIZEN_TURN_PER_TICK)
        }
        point.pitch = pitch
        return point
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
        path.clear()
        releaseSearch()
    }

    private fun releaseSearch() {
        open.clear()
        cells.clear()
        collisions.clear()
        weights.clear()
        parents.clear()
        costs.clear()
        edges = null
        expanding = null
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

internal fun citizenPacketStepClear(world: World, from: CitizenPoint, to: CitizenPoint, allowDoors: Boolean,
                                   collision: ((Int, Int, Int) -> CitizenCollisionSnapshot)? = null): Boolean {
    if (abs(to.y - from.y) > 1.01) return false
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
