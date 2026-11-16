package ru.arc.civs

import kotlin.math.abs

/** Small changes to a retained route, using its real endpoints and the existing upkeep receipt. */
internal object CivsRoadTerraces {
    const val MAX_SPAN = 24
    const val MAX_DEPTH = 3
    const val MAX_BANK_FILL = 4

    fun gradeCost(route: List<CivPosition>): Int {
        var previous = 0
        var cost = 0
        for (at in 1 until route.size) {
            val rise = route[at].y - route[at - 1].y
            cost += abs(rise) * 40
            if (rise != 0 && rise != previous) cost += 48
            previous = rise
        }
        return cost
    }

    /** Each half-step has a straight approach and landing in both walking directions. */
    private fun landings(route: List<CivPosition>): Boolean = route.windowed(3).all { (a, b, c) ->
        val turn = a.x != c.x && a.z != c.z
        (!turn || b.y <= a.y && c.y >= b.y) && !(b.y > a.y && c.y < b.y)
    }

    fun valid(original: List<CivPosition>, route: List<CivPosition>, run: IntRange): Boolean =
        run.count() <= MAX_SPAN && run.first > 0 && run.last < route.lastIndex &&
            run.all { abs(route[it].y.toLong() - original[it].y) in 1L..MAX_DEPTH.toLong() } &&
            gradeCost(route) < gradeCost(original) &&
            landings(route.subList(maxOf(0, run.first - 1), minOf(route.size, run.last + 2)))

    /** Prefer broad level sections, then combine the necessary straight rises into one flight.
     * One pass changes a column by at most one block, so the ordinary paid column work can finish
     * before another pass deepens a cut or raises a hollow. No horizontal route is invented. */
    fun candidates(route: List<CivPosition>): List<List<CivPosition>> {
        if (route.size !in 4..CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH) return emptyList()
        if (gradeCost(route) == 0) return emptyList()
        // Score only the affected window. Retain one small descriptor per first changed cell;
        // full routes are allocated only for the final eight independent alternatives.
        data class Candidate(val from: Int, val to: Int, val flight: Int, val gain: Int,
            val earthwork: Int, val first: Int, val last: Int)
        val best = arrayOfNulls<Candidate>(route.size)
        fun consider(from: Int, to: Int, flight: Int) {
            val rise = (route[to].y - route[from].y).coerceIn(-1, 1)
            val height = abs(route[to].y - route[from].y)
            fun level(at: Int): Int = if (at <= from || at >= to) route[at].y else
                (route[from].y + rise * (at - flight).coerceIn(0, height)).coerceIn(route[at].y - 1, route[at].y + 1)
            var first = -1; var last = -1; var earthwork = 0
            for (at in from + 1 until to) {
                val delta = abs(level(at) - route[at].y)
                if (delta != 0) { if (first < 0) first = at; last = at; earthwork += delta }
            }
            if (first < 0) return
            for (at in maxOf(1, from)..minOf(route.lastIndex - 1, to)) {
                val a = level(at - 1); val b = level(at); val c = level(at + 1)
                val turn = route[at - 1].x != route[at + 1].x && route[at - 1].z != route[at + 1].z
                if (abs(b - a) > 1 || abs(c - b) > 1 || turn && (b > a || c < b) || b > a && c < b) return
            }
            var gain = 0
            for (at in from + 1..minOf(route.lastIndex, to + 1)) {
                val oldRise = route[at].y - route[at - 1].y
                val oldPrevious = if (at > 1) route[at - 1].y - route[at - 2].y else 0
                val newRise = level(at) - level(at - 1)
                val newPrevious = if (at > 1) level(at - 1) - level(at - 2) else 0
                gain += abs(oldRise) * 40 + (if (oldRise != 0 && oldRise != oldPrevious) 48 else 0) -
                    abs(newRise) * 40 - (if (newRise != 0 && newRise != newPrevious) 48 else 0)
            }
            if (gain <= 0) return
            val previous = best[first]
            if (previous == null || gain > previous.gain || gain == previous.gain && earthwork < previous.earthwork)
                best[first] = Candidate(from, to, flight, gain, earthwork, first, last)
        }
        for (from in 0 until route.lastIndex - 1) {
            val limit = minOf(route.lastIndex, from + MAX_SPAN + 1)
            for (to in from + 2..limit) if (route[to].y == route[from].y) consider(from, to, from)
            var straightEnd = limit
            for (at in from + 1..limit) if (route[at].x != route[from].x && route[at].z != route[from].z) {
                straightEnd = at - 1; break
            }
            if (straightEnd <= from + 2) continue
            val height = abs(route[straightEnd].y - route[from].y)
            if (height == 0 || height >= straightEnd - from) continue
            for (flight in from until straightEnd - height) consider(from, straightEnd, flight)
        }
        val chosen = mutableListOf<Candidate>()
        for (candidate in best.filterNotNull().sortedWith(compareByDescending<Candidate> { it.gain }
            .thenBy { it.earthwork }.thenBy { it.first })) {
            if (chosen.any { candidate.first <= it.last + 1 && candidate.last >= it.first - 1 }) continue
            chosen += candidate
            if (chosen.size == 8) break
        }
        return chosen.map { candidate ->
            val rise = (route[candidate.to].y - route[candidate.from].y).coerceIn(-1, 1)
            val height = abs(route[candidate.to].y - route[candidate.from].y)
            route.mapIndexed { at, point -> if (at in candidate.from + 1 until candidate.to)
                point.copy(y = (route[candidate.from].y + rise * (at - candidate.flight).coerceIn(0, height))
                    .coerceIn(point.y - 1, point.y + 1)) else point }
        }
    }

    /** One grass shoulder on either side of a straight flight and its upper landing. The flat
     * continuation ends at a turn, another grade, the route end or the bounded terrace span.
     * Native capture leaves matching/higher ground in place; shared-route claims are excluded by upkeep. */
    fun banks(route: List<CivPosition>, flightsTouching: Set<Int>? = null): Map<Long, CivPosition> {
        if (route.size < 2) return emptyMap()
        val road = CivsAutonomousTerraform.roadProfile(route)
        val banks = linkedMapOf<Long, CivPosition>()
        var at = 1
        while (at < route.size) {
            val first = route[at - 1]
            val next = route[at]
            val rise = next.y - first.y
            val dx = next.x - first.x; val dz = next.z - first.z
            if (abs(rise) != 1 || abs(dx) + abs(dz) != 1) { at++; continue }
            var end = at
            while (end + 1 < route.size && route[end + 1].y - route[end].y == rise &&
                route[end + 1].x - route[end].x == dx && route[end + 1].z - route[end].z == dz) end++
            if (flightsTouching != null && (at - 1..end).none { it in flightsTouching }) {
                at = end + 1
                continue
            }
            fun shoulder(high: CivPosition, y: Int) {
                for (side in listOf(-2, 2)) {
                    val x = high.x + dz * side; val z = high.z - dx * side
                    val key = civsTerrainKey(x, z)
                    if (key in road) continue
                    val point = CivPosition(x, y, z)
                    banks[key] = banks[key]?.takeIf { it.y >= y } ?: point
                }
            }
            val highFirst = if (rise > 0) (end downTo at).map { route[it] } else (at - 1 until end).map { route[it] }
            for ((index, high) in highFirst.withIndex()) shoulder(high, high.y - 1 + index % 2)
            val highIndex = if (rise > 0) end else at - 1
            val outward = if (rise > 0) 1 else -1
            val high = route[highIndex]
            for (step in 1..MAX_SPAN) {
                val index = highIndex + outward * step
                val next = route.getOrNull(index) ?: break
                if (next.y != high.y || next.x != high.x + dx * outward * step || next.z != high.z + dz * outward * step) break
                shoulder(next, high.y - 1)
            }
            at = end + 1
        }
        return banks
    }
}
