package ru.arc.civs

import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Detached values only. Bukkit's Vector/BoundingBox below are local arithmetic, never native state. */
internal data class CitizenTerrainBox(
    val minX: Double, val minY: Double, val minZ: Double,
    val maxX: Double, val maxY: Double, val maxZ: Double,
) {
    fun at(point: CivPosition) = BoundingBox(minX + point.x, minY + point.y, minZ + point.z,
        maxX + point.x, maxY + point.y, maxZ + point.z)
}

internal data class CitizenTerrainBlock(
    val loaded: Boolean, val liquid: Boolean, val door: Boolean, val climbable: Boolean,
    val hot: Boolean, val allowed: Boolean, val cost: Double, val boxes: List<CitizenTerrainBox>,
) {
    companion object {
        val UNAVAILABLE = CitizenTerrainBlock(false, false, false, false, false, false,
            Double.POSITIVE_INFINITY, emptyList())
    }
}

internal enum class CitizenRouteAction { WALK, STEP, LADDER_ENTER, CLIMB, LADDER_EXIT }
internal data class CitizenSearchNode(val x: Int, val y: Double, val z: Int,
    val climbing: Boolean = false, val initial: Boolean = false) {
    fun point() = CitizenPoint(x + .5, y, z + .5)
}
internal data class CitizenSearchProbe(val node: CitizenSearchNode, val from: CitizenPoint)
internal data class CitizenSearchEdge(val node: CitizenSearchNode, val action: CitizenRouteAction, val permitted: Boolean)
internal data class CitizenPlannedStep(val point: CitizenPoint, val action: CitizenRouteAction)
internal data class CitizenSearchCapture(val probe: CitizenSearchProbe,
    val blocks: Map<CivPosition, CitizenTerrainBlock>, val edges: List<CitizenSearchEdge>)
internal data class CitizenSearchResult(val probes: List<CitizenSearchProbe>, val nodes: Int,
    val path: List<CitizenPlannedStep>? = null, val failure: String? = null, val searchNanos: Long = 0)

/**
 * One worker owns this A* state. A slice asks for at most 32 small neighbourhoods; native capture
 * and arbitrary route policies are supplied later by the owner. Local trips retain unweighted
 * cost ordering; longer trips use a MineColonies-style weighted heuristic to favour reaching
 * a useful route within a finite node budget over cost optimality.
 * Pending edges are settled before accepting a goal, including every already-requested branch.
 */
internal class CitizenNavigationSearch(
    private val origin: CitizenPoint,
    private val target: CitizenPoint,
    private val minimumY: Int,
    private val maximumY: Int,
    private val avoiding: List<CitizenTerrainBox>,
) {
    private data class Entry(val node: CitizenSearchNode, val cost: Double, val score: Double, val order: Int)
    private data class Parent(val node: CitizenSearchNode, val action: CitizenRouteAction)
    private val start = CitizenSearchNode(floor(origin.x).toInt(), origin.y, floor(origin.z).toInt(), initial = true)
    // Local trips must still choose a modest road detour over learned grass. Keep weight 1
    // through 32 blocks, then ramp to 2 by 64: distant ordinary-cost terrain otherwise exhausts
    // the 4096-node allowance. The weight stays fixed for the job, so queued scores stay valid.
    private val heuristicWeight = 1.0 + (MAXIMUM_HEURISTIC_WEIGHT - 1.0) *
        ((sqrt(origin.distanceSquared(target)) - LOCAL_ROUTE_DISTANCE) /
            (WEIGHTED_ROUTE_DISTANCE - LOCAL_ROUTE_DISTANCE)).coerceIn(0.0, 1.0)
    private val open = PriorityQueue<Entry>(compareBy<Entry> { it.score }.thenBy { it.order })
    private val costs = hashMapOf(start to 0.0)
    private val parents = hashMapOf<CitizenSearchNode, Parent>()
    private var pending = emptyMap<CitizenSearchNode, Entry>()
    private var goal: Entry? = null
    private var serial = 0
    private var visited = 0
    private var unknownTerrain = false
    private val cancelled = AtomicBoolean()

    init { open += Entry(start, 0.0, heuristic(start), serial++) }

    fun cancel() { cancelled.set(true) }

    fun advance(captures: List<CitizenSearchCapture>): CitizenSearchResult {
        val began = System.nanoTime()
        fun result(probes: List<CitizenSearchProbe> = emptyList(), path: List<CitizenPlannedStep>? = null,
                   failure: String? = null) = CitizenSearchResult(probes, visited, path, failure, System.nanoTime() - began)
        if (cancelled.get()) return result(failure = "cancelled")
        for (capture in captures) {
            val previous = pending[capture.probe.node] ?: continue
            if (costs[previous.node] != previous.cost) continue
            if (capture.blocks.any { (point, block) -> point.y in minimumY until maximumY && !block.loaded })
                unknownTerrain = true
            val terrain = CitizenSearchGeometry(capture.blocks, minimumY, maximumY, avoiding)
            val from = capture.probe.from
            if (!terrain.loaded(previous.node.x, floor(previous.node.y).toInt(), previous.node.z))
                return result(failure = "chunk-unloaded")
            for (edge in capture.edges) {
                if (cancelled.get()) return result(failure = "cancelled")
                val next = edge.node
                if (!edge.permitted || !terrain.bodyClear(next.point()) ||
                    !terrain.stepClear(from, next.point(), edge.action)) continue
                val point = next.point()
                if (abs(point.x - start.point().x) > 96 || abs(point.z - start.point().z) > 96 ||
                    abs(point.y - start.y) > 96) continue
                val weight = capture.blocks[CivPosition(next.x, citizenSupportY(next.y), next.z)]?.cost
                    ?: Double.POSITIVE_INFINITY
                if (!weight.isFinite()) continue
                val cost = previous.cost + sqrt(from.distanceSquared(point)) * weight.coerceIn(1.0, CivsPaths.ORDINARY_COST)
                if (cost >= (costs[next] ?: Double.POSITIVE_INFINITY)) continue
                costs[next] = cost
                parents[next] = Parent(previous.node, edge.action)
                open += Entry(next, cost, cost + heuristic(next), serial++)
            }
        }
        pending = emptyMap()
        val requested = linkedMapOf<CitizenSearchNode, Entry>()
        while (requested.size < NODES_PER_SLICE) {
            if (cancelled.get()) return result(failure = "cancelled")
            var entry = open.poll()
            while (entry != null && costs[entry.node] != entry.cost) entry = open.poll()
            if (entry == null) break
            val found = goal
            if (found != null && entry.score >= found.cost) {
                open += entry
                break
            }
            if (++visited > MAXIMUM_NODES) return result(failure = "search-budget")
            val node = entry.node
            if (node.x == floor(target.x).toInt() && node.z == floor(target.z).toInt() && abs(node.y - target.y) <= .5) {
                if (goal?.let { entry.cost < it.cost } != false) goal = entry
            } else requested[node] = entry
        }
        pending = requested
        if (requested.isNotEmpty()) return result(requested.keys.map { CitizenSearchProbe(it, if (it == start) origin else it.point()) })
        val found = goal ?: return result(failure = if (unknownTerrain) "chunk-unloaded" else "no-path")
        val reverse = ArrayDeque<CitizenPlannedStep>()
        var node = found.node
        while (node != start) {
            if (cancelled.get()) return result(failure = "cancelled")
            val parent = parents[node] ?: return result(failure = "broken-path")
            reverse.addFirst(CitizenPlannedStep(node.point(), parent.action))
            node = parent.node
        }
        if (reverse.isEmpty()) reverse += CitizenPlannedStep(
            CitizenPoint(floor(target.x) + .5, target.y, floor(target.z) + .5), CitizenRouteAction.WALK)
        return result(path = reverse.toList())
    }

    private fun heuristic(node: CitizenSearchNode): Double {
        val dx = node.x + .5 - target.x
        val dz = node.z + .5 - target.z
        val dy = max(0.0, abs(node.y - target.y) - .5)
        return heuristicWeight * sqrt(dx * dx + dy * dy + dz * dz)
    }

    companion object {
        const val NODES_PER_SLICE = 32
        const val MAXIMUM_NODES = 4096
        private const val LOCAL_ROUTE_DISTANCE = 32.0
        private const val WEIGHTED_ROUTE_DISTANCE = 64.0
        private const val MAXIMUM_HEURISTIC_WEIGHT = 2.0
    }
}

/** Candidate enumeration is cheap pure arithmetic; the owner then captures each policy decision. */
internal fun citizenSearchCandidates(probe: CitizenSearchProbe, blocks: Map<CivPosition, CitizenTerrainBlock>,
                                     minimumY: Int, maximumY: Int): List<CitizenSearchEdge> {
    val node = probe.node
    val result = linkedSetOf<CitizenSearchNode>()
    val base = floor(node.y).toInt()
    fun standing(x: Int, z: Int) {
        for (by in base - 2..base + 1) {
            if (by < minimumY || by + 2 >= maximumY) continue
            val block = blocks[CivPosition(x, by, z)] ?: continue
            if (!block.loaded || block.liquid) continue
            if (block.climbable && block.allowed && citizenStepHeightAllowed(node.y, by.toDouble()))
                result += CitizenSearchNode(x, by.toDouble(), z, climbing = true)
            for (box in block.boxes) {
                if (box.maxY <= 0 || box.minX > .5 || box.maxX < .5 || box.minZ > .5 || box.maxZ < .5) continue
                val height = by + box.maxY
                if (citizenStepHeightAllowed(node.y, height) &&
                    blocks[CivPosition(x, floor(height + .001).toInt(), z)]?.allowed == true)
                    result += CitizenSearchNode(x, height, z)
            }
        }
    }
    if (node.initial) standing(node.x, node.z)
    for (dx in -1..1) for (dz in -1..1) if (dx != 0 || dz != 0) standing(node.x + dx, node.z + dz)
    val onLadder = blocks[CivPosition(node.x, base, node.z)]?.climbable == true
    if (onLadder) for (dy in -1..1) {
        val next = blocks[CivPosition(node.x, base + dy, node.z)] ?: continue
        if (next.loaded && next.climbable && next.allowed && citizenStepHeightAllowed(node.y, (base + dy).toDouble()))
            result += CitizenSearchNode(node.x, (base + dy).toDouble(), node.z, climbing = true)
    }
    return result.map { next ->
        val action = when {
            next.climbing && onLadder && next.x == node.x && next.z == node.z -> CitizenRouteAction.CLIMB
            next.climbing -> CitizenRouteAction.LADDER_ENTER
            node.climbing || onLadder -> CitizenRouteAction.LADDER_EXIT
            abs(next.y - node.y) > .0001 -> CitizenRouteAction.STEP
            else -> CitizenRouteAction.WALK
        }
        CitizenSearchEdge(next, action, true)
    }
}

private class CitizenSearchGeometry(
    private val blocks: Map<CivPosition, CitizenTerrainBlock>,
    private val minimumY: Int,
    private val maximumY: Int,
    avoiding: List<CitizenTerrainBox>,
) {
    private val bodies = avoiding.map { it.at(CivPosition(0, 0, 0)) }
    fun loaded(x: Int, y: Int, z: Int) = blocks[CivPosition(x, y, z)]?.loaded == true

    fun bodyClear(point: CitizenPoint): Boolean {
        val body = body(point)
        for (x in floor(body.minX).toInt()..floor(body.maxX).toInt())
            for (z in floor(body.minZ).toInt()..floor(body.maxZ).toInt())
                for (y in floor(body.minY).toInt()..floor(body.maxY).toInt()) {
                    val p = CivPosition(x, y, z)
                    val block = blocks[p] ?: return false
                    if (y !in minimumY until maximumY || !block.loaded || block.liquid) return false
                    if (!block.door && block.boxes.any { body.overlaps(it.at(p)) }) return false
                }
        return true
    }

    fun stepClear(from: CitizenPoint, to: CitizenPoint, action: CitizenRouteAction): Boolean {
        if (!citizenStepHeightAllowed(from.y, to.y) ||
            citizenSlideBody(Vector(from.x, from.y, from.z), Vector(to.x, to.y, to.z), .299, 1.798, .299, bodies) != null) return false
        // Enter/settle onto the ladder centre before changing height, including fractional starts.
        if (action == CitizenRouteAction.CLIMB || action == CitizenRouteAction.LADDER_ENTER) {
            val centre = CitizenPoint(to.x, from.y, to.z)
            return segmentClear(from, centre, from) && segmentClear(centre, to, from)
        }
        if (from.y != to.y) {
            val high = max(from.y, to.y)
            val aboveFrom = CitizenPoint(from.x, high, from.z)
            val aboveTo = CitizenPoint(to.x, high, to.z)
            return segmentClear(from, aboveFrom, from) && segmentClear(aboveFrom, aboveTo, from) && segmentClear(aboveTo, to, from)
        }
        return segmentClear(from, to, from)
    }

    private fun segmentClear(from: CitizenPoint, to: CitizenPoint, occupied: CitizenPoint): Boolean {
        val obstacles = mutableListOf<BoundingBox>()
        for (x in floor(min(from.x, to.x) - .299).toInt()..floor(max(from.x, to.x) + .299).toInt())
            for (z in floor(min(from.z, to.z) - .299).toInt()..floor(max(from.z, to.z) + .299).toInt())
                for (y in floor(min(from.y, to.y) + .001).toInt()..floor(max(from.y, to.y) + 1.799).toInt()) {
                    val p = CivPosition(x, y, z)
                    val block = blocks[p] ?: return false
                    if (y !in minimumY until maximumY || !block.loaded) return false
                    if (block.liquid) obstacles += BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0)
                    else if (!block.door) block.boxes.forEach { obstacles += it.at(p) }
                }
        if (citizenSlideBody(Vector(from.x, from.y + .001, from.z), Vector(to.x, to.y + .001, to.z),
                .299, 1.798, .299, obstacles) != null) return false
        val samples = ceil(sqrt(from.distanceSquared(to)) / .15).toInt().coerceIn(1, 16)
        val occupiedBody = body(occupied, -.001)
        for (index in 1..samples) {
            val t = index.toDouble() / samples
            val point = CitizenPoint(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t,
                from.z + (to.z - from.z) * t)
            if (!bodyClear(point)) return false
            val body = body(point, -.001)
            for (x in floor(body.minX).toInt()..floor(body.maxX).toInt())
                for (z in floor(body.minZ).toInt()..floor(body.maxZ).toInt())
                    for (y in floor(body.minY).toInt()..floor(body.maxY).toInt()) {
                        val block = blocks[CivPosition(x, y, z)] ?: return false
                        if (!block.loaded) return false
                        if (block.hot && !occupiedBody.overlaps(BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0))) return false
                    }
        }
        return true
    }

    private fun body(point: CitizenPoint, bottomOffset: Double = .001) = BoundingBox(
        point.x - .299, point.y + bottomOffset, point.z - .299, point.x + .299, point.y + 1.799, point.z + .299)
}
