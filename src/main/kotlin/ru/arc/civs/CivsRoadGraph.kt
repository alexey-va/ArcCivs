package ru.arc.civs

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

internal typealias CitizenRoadLookup = (CitizenPoint, CitizenPoint) -> CitizenSearchCorridor?
internal enum class CitizenRoadRouting { ROAD, FALLBACK }

/** Connect admitted pavement and previously walked support grades. Only native navigation may turn a hint into steps. */
internal class CivsRoadGraph(private val paid: Supports, private val learned: Supports) {
    /** Both sets are immutable for the projection's lifetime. Index once, on the first worker lookup. */
    internal class Supports(val cells: Set<CivPosition>) {
        private val columns by lazy {
            cells.groupBy { civsTerrainKey(it.x, it.z) }.mapValues { (_, grades) -> grades.sortedBy(CivPosition::y) }
        }

        inline fun inColumn(x: Int, z: Int, low: Int, high: Int, visit: (CivPosition) -> Unit) {
            if (cells.isEmpty()) return
            val grades = columns[civsTerrainKey(x, z)] ?: return
            var index = if (grades.size == 1) 0 else grades.binarySearchBy(low, selector = CivPosition::y)
            if (index < 0) index = -index - 1
            while (index < grades.size) {
                val support = grades[index++]
                if (support.y > high) break
                if (support.y >= low) visit(support)
            }
        }
    }

    private data class Step(val support: CivPosition, val cost: Double, val score: Double, val order: Int)

    fun corridor(from: CitizenPoint, to: CitizenPoint): CitizenSearchCorridor? {
        val distanceSquared = from.distanceSquared(to)
        if (distanceSquared < 4.0 || paid.cells.isEmpty() && learned.cells.isEmpty()) return null
        val start = anchor(from) ?: return null
        val goal = anchor(to) ?: return null
        if (start == goal) return null
        val maximumCost = sqrt(distanceSquared) * CivsPaths.ORDINARY_COST + ANCHOR_DISTANCE * 2
        val open = PriorityQueue(BY_SCORE)
        val costs = hashMapOf(start to 0.0)
        val parents = hashMapOf<CivPosition, CivPosition>()
        var order = 0
        var visited = 0
        open += Step(start, 0.0, distance(start, goal), order++)
        while (open.isNotEmpty() && visited < MAXIMUM_NODES) {
            val current = open.remove()
            if (costs[current.support] != current.cost) continue
            visited++
            if (current.support == goal) {
                val path = linkedSetOf<CivPosition>()
                var point = goal
                while (true) {
                    path += point
                    if (path.size > CivsPaths.MAX_TRIP_CELLS) return null
                    point = parents[point] ?: break
                }
                return CitizenSearchCorridor.fromSupports(path, from, to, start, goal)
            }
            fun visit(next: CivPosition, weight: Double) {
                val cost = current.cost + distance(current.support, next) * weight
                if (cost >= (costs[next] ?: Double.POSITIVE_INFINITY)) return
                val score = cost + distance(next, goal)
                if (score > maximumCost) return
                costs[next] = cost
                parents[next] = current.support
                open += Step(next, cost, score, order++)
            }
            val p = current.support
            for (dx in -1..1) for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                paid.inColumn(p.x + dx, p.z + dz, p.y - 1, p.y + 1) { visit(it, 1.0) }
                learned.inColumn(p.x + dx, p.z + dz, p.y - 1, p.y + 1) {
                    if (it !in paid.cells) visit(it, CivsPaths.LEARNED_COST)
                }
            }
        }
        return null
    }

    private fun anchor(endpoint: CitizenPoint): CivPosition? {
        val x = floor(endpoint.x).toInt()
        val z = floor(endpoint.z).toInt()
        val low = ceil(endpoint.y - 1 - ANCHOR_DISTANCE).toInt()
        val high = floor(endpoint.y - 1 + ANCHOR_DISTANCE).toInt()
        var best: CivPosition? = null
        var distance = (ANCHOR_DISTANCE * ANCHOR_DISTANCE).toDouble()
        fun consider(point: CivPosition) {
            val squared = point.roadDistanceSquared(endpoint)
            if (squared < distance || squared == distance && best == null) {
                best = point
                distance = squared
            }
        }
        for (radius in 0..ANCHOR_DISTANCE) {
            if (radius > 0 && (radius - .5) * (radius - .5) > distance) break
            for (dx in -radius..radius) for (dz in -radius..radius) {
                if (abs(dx) != radius && abs(dz) != radius) continue
                paid.inColumn(x + dx, z + dz, low, high, ::consider)
                learned.inColumn(x + dx, z + dz, low, high, ::consider)
            }
        }
        return best
    }

    private fun distance(a: CivPosition, b: CivPosition): Double {
        val dx = a.x.toDouble() - b.x
        val dy = a.y.toDouble() - b.y
        val dz = a.z.toDouble() - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private companion object {
        const val MAXIMUM_NODES = 1024
        const val ANCHOR_DISTANCE = CitizenSearchCorridor.ANCHOR_DISTANCE
        val BY_SCORE = compareBy<Step> { it.score }.thenBy { it.order }
    }
}
