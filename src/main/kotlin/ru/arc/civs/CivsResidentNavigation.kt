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
        const val SEARCH_OPERATIONS_PER_TICK = 96
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
) {
    private data class Cell(val x: Int, val y: Double, val z: Int, val climbing: Boolean = false) {
        fun point() = CitizenPoint(x + .5, y, z + .5)
    }
    private data class Entry(val cell: Cell, val cost: Double, val score: Double, val order: Int)
    private val start = Cell(from.blockX, from.y, from.blockZ)
    private val open = PriorityQueue<Entry>(compareBy<Entry> { it.score }.thenBy { it.order })
    private val costs = hashMapOf(start to 0.0)
    private val parents = hashMapOf<Cell, Cell>()
    private val cells = hashMapOf<Triple<Int, Int, Int>, List<Cell>>()
    private val weights = hashMapOf<Cell, Double>()
    private val path = ArrayDeque<Cell>()
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
    val navigating: Boolean get() = searching || failure == null && !finished
    val destination: Location? get() = path.firstOrNull()?.let { Location(world, it.x + .5, it.y, it.z + .5) }
    val nodeCount: Int get() = visited
    val endpoint: CitizenPoint? get() = path.lastOrNull()?.point()

    init { open += Entry(start, 0.0, heuristic(start), serial++) }

    fun cancel() { searching = false; finished = true; releaseSearch(); path.clear() }

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
            if (abs(next.y - current.cell.y) <= 1.01 && stepAllowed(current.cell.point(), next.point()) &&
                citizenPacketStepClear(world, current.cell.point(), next.point(), allowDoors = true)) offer(current, next)
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
            path.addAll(reversed)
            searching = false
            releaseSearch()
            return
        }
        expanding = current
        edges = sequence {
            for (dx in -1..1) for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                val x = cell.x + dx
                val z = cell.z + dz
                yieldAll(cells.getOrPut(Triple(x, floor(cell.y).toInt(), z)) { standingCells(x, cell.y, z) })
            }
            // A quarry's ladder connects adjacent floors without inventing a solid support block.
            val at = world.getBlockAt(cell.x, floor(cell.y).toInt(), cell.z)
            if (Tag.CLIMBABLE.isTagged(at.type)) for (dy in listOf(-1, 1)) {
                val next = Cell(cell.x, floor(cell.y) + dy, cell.z, true)
                if (allowed(CivPosition(next.x, floor(next.y).toInt(), next.z)) &&
                    Tag.CLIMBABLE.isTagged(world.getBlockAt(next.x, floor(next.y).toInt(), next.z).type) &&
                    citizenPacketBodyClear(world, next.point(), true)) yield(next)
            }
        }.iterator()
    }

    private fun offer(previous: Entry, next: Cell) {
        val p = next.point()
        if (abs(p.x - start.point().x) > 96 || abs(p.z - start.point().z) > 96 ||
            abs(p.y - start.y) > 96) return
        val distance = sqrt(previous.cell.point().distanceSquared(p))
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
                if (allowed(CivPosition(x, by, z)) && citizenPacketBodyClear(world, point, true))
                    result += Cell(x, by.toDouble(), z, true)
            }
            for (box in block.collisionShape.boundingBoxes) {
                if (box.maxY <= 0 || box.minX > .5 || box.maxX < .5 || box.minZ > .5 || box.maxZ < .5) continue
                val height = by + box.maxY
                if (abs(height - y) > 1.01) continue
                val point = CitizenPoint(x + .5, height, z + .5)
                if (allowed(CivPosition(x, floor(height + .001).toInt(), z)) &&
                    citizenPacketBodyClear(world, point, true)) result += Cell(x, height, z)
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
        if (searching || failure != null || finished) return null
        val next = path.firstOrNull() ?: run { finished = true; return null }
        val to = Location(world, next.x + .5, next.y, next.z + .5)
        if (!citizenChunkReady(world, to.blockX shr 4, to.blockZ shr 4) ||
            !allowed(CivPosition(to.blockX, floor(to.y + .001).toInt(), to.blockZ)) ||
            !citizenPacketSupported(world, next.point()) ||
            !travelCost(world.getBlockAt(next.x, citizenSupportY(next.y), next.z)).isFinite() || !openDoor(to)) {
            fail("path-changed"); return null
        }
        // Rise before entering an upper block's side. Walk beyond a lower step's edge before
        // descending, so the body never interpolates diagonally through its previous support.
        val waypoint = to.clone()
        if (to.y > from.y + .0001) {
            waypoint.x = from.x
            waypoint.z = from.z
        } else if (to.y < from.y - .0001 &&
            (to.x - from.x) * (to.x - from.x) + (to.z - from.z) * (to.z - from.z) > .000001) {
            waypoint.y = from.y
        }
        val delta = waypoint.toVector().subtract(from.toVector())
        val distance = delta.length()
        val advance = min(distance, .215 * speed.coerceIn(.1, 5.0))
        val point = if (distance < .0001) waypoint else from.clone().add(delta.multiply(advance / distance))
        val a = CitizenPoint(from.x, from.y, from.z)
        val b = CitizenPoint(point.x, point.y, point.z)
        if (!stepAllowed(a, b) || !citizenPacketStepClear(world, a, b, false)) {
            fail("movement-blocked"); return null
        }
        // A player can temporarily occupy the next step. Preserve the waypoint; the
        // domain's existing timeout and safe recovery policy handle prolonged blocking.
        if (!movementAllowed(from, point)) {
            waitingForCollision = true
            return null
        }
        if (delta.x * delta.x + delta.z * delta.z > .00001) {
            val wanted = Math.toDegrees(atan2(-delta.x, delta.z)).toFloat()
            point.yaw = citizenTurn(from.yaw, wanted, 24f)
        }
        point.pitch = 0f
        if (point.distanceSquared(to) < .000001) {
            path.removeFirst()
            if (path.isEmpty()) finished = true
        }
        return point
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
        weights.clear()
        parents.clear()
        costs.clear()
        edges = null
        expanding = null
    }
}

internal fun citizenTurn(from: Float, to: Float, limit: Float): Float {
    val change = ((to - from + 540f) % 360f) - 180f
    return from + change.coerceIn(-limit, limit)
}

/** Collision shapes are local to their block; check both body width and headroom. */
internal fun citizenPacketBodyClear(world: World, point: CitizenPoint, allowDoors: Boolean,
                                    allowLiquids: Boolean = false, allowBelowWorld: Boolean = false): Boolean {
    val body = BoundingBox(point.x - .299, point.y + .001, point.z - .299, point.x + .299, point.y + 1.799, point.z + .299)
    for (x in floor(body.minX).toInt()..floor(body.maxX).toInt()) for (z in floor(body.minZ).toInt()..floor(body.maxZ).toInt()) {
        if (!citizenChunkReady(world, x shr 4, z shr 4)) return false
        for (y in floor(body.minY).toInt()..floor(body.maxY).toInt()) {
            if (y < world.minHeight) {
                if (allowBelowWorld) continue
                return false
            }
            if (y >= world.maxHeight) return false
            val block = world.getBlockAt(x, y, z)
            if (block.isLiquid && !allowLiquids) return false
            if (allowDoors && block.blockData is Door) continue
            for (shape in block.collisionShape.boundingBoxes) if (body.overlaps(shape.clone().shift(x.toDouble(), y.toDouble(), z.toDouble()))) return false
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

internal fun citizenPacketStepClear(world: World, from: CitizenPoint, to: CitizenPoint, allowDoors: Boolean): Boolean {
    if (abs(to.y - from.y) > 1.01) return false
    if (from.y != to.y) {
        val high = max(from.y, to.y)
        val aboveFrom = CitizenPoint(from.x, high, from.z)
        val aboveTo = CitizenPoint(to.x, high, to.z)
        return citizenPacketSegmentClear(world, from, aboveFrom, allowDoors) &&
            citizenPacketSegmentClear(world, aboveFrom, aboveTo, allowDoors) &&
            citizenPacketSegmentClear(world, aboveTo, to, allowDoors)
    }
    return citizenPacketSegmentClear(world, from, to, allowDoors)
}

private fun citizenPacketSegmentClear(world: World, from: CitizenPoint, to: CitizenPoint, allowDoors: Boolean): Boolean {
    val distance = sqrt(from.distanceSquared(to))
    val samples = kotlin.math.ceil(distance / .15).toInt().coerceIn(1, 16)
    for (index in 1..samples) {
        val t = index.toDouble() / samples
        val point = CitizenPoint(from.x + (to.x - from.x) * t,
            from.y + (to.y - from.y) * t,
            from.z + (to.z - from.z) * t)
        if (!citizenPacketBodyClear(world, point, allowDoors)) return false
    }
    return true
}
