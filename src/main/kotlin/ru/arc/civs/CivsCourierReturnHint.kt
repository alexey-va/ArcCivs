package ru.arc.civs

import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Detached search preference, never a path or permission to move. */
internal class CitizenSearchCorridor private constructor(
    private val columns: Set<Long>, private val minimumY: Double, private val maximumY: Double,
) {
    fun contains(point: CitizenPoint): Boolean = point.y in minimumY..maximumY &&
        civsTerrainKey(floor(point.x).toInt(), floor(point.z).toInt()) in columns

    companion object {
        private const val ANCHOR_DISTANCE = 12
        fun fromWalked(walked: Set<CivPosition>, from: CitizenPoint, to: CitizenPoint): CitizenSearchCorridor? {
            if (walked.size !in 2..CivsPaths.MAX_TRIP_CELLS) return null
            val columns = hashSetOf<Long>()
            fun add(x: Int, z: Int, radius: Int) {
                for (dx in -radius..radius) for (dz in -radius..radius)
                    columns += civsTerrainKey(x + dx, z + dz)
            }
            walked.forEach { add(it.x, it.z, 1) }
            for (endpoint in listOf(from, to)) {
                val nearest = walked.minByOrNull { it.copy(y = it.y + 1).point().distanceSquared(endpoint) } ?: return null
                val target = nearest.copy(y = nearest.y + 1).point()
                if (target.distanceSquared(endpoint) > ANCHOR_DISTANCE * ANCHOR_DISTANCE) return null
                val dx = target.x - endpoint.x
                val dz = target.z - endpoint.z
                val steps = ceil(max(abs(dx), abs(dz))).toInt().coerceAtLeast(1)
                for (step in 0..steps) {
                    val fraction = step.toDouble() / steps
                    add(floor(endpoint.x + dx * fraction).toInt(), floor(endpoint.z + dz * fraction).toInt(), 2)
                }
            }
            // Outdoor observation omits building interiors. Only the two bounded
            // access tubes may bridge those omissions; unrelated gaps skip the hint.
            val start = civsTerrainKey(floor(from.x).toInt(), floor(from.z).toInt())
            val goal = civsTerrainKey(floor(to.x).toInt(), floor(to.z).toInt())
            val remaining = columns.toMutableSet()
            val pending = ArrayDeque<Pair<Int, Int>>()
            pending += floor(from.x).toInt() to floor(from.z).toInt()
            remaining.remove(start)
            var connected = false
            while (pending.isNotEmpty()) {
                val (x, z) = pending.removeFirst()
                if (civsTerrainKey(x, z) == goal) { connected = true; break }
                for (dx in -1..1) for (dz in -1..1)
                    if (remaining.remove(civsTerrainKey(x + dx, z + dz))) pending += x + dx to z + dz
            }
            if (!connected) return null
            val low = minOf(from.y, to.y, walked.minOf { it.y + 1.0 })
            val high = maxOf(from.y, to.y, walked.maxOf { it.y + 1.0 })
            if (high - low > 16) return null
            return CitizenSearchCorridor(columns, low - 2, high + 2)
        }
    }
}

/** One observed source leg, retired on use or any changed shipment/geometry. */
internal class CitizenCourierReturnHint private constructor(
    private val cityId: UUID, private val worldId: UUID, private val shipmentId: UUID,
    private val geometry: CivsPaths.Geometry, private val overrides: Map<String, CivRoadRouteOverride>,
    private val start: CitizenPoint, private val arrived: CitizenPoint, private val walked: Set<CivPosition>,
) {
    fun corridor(city: Settlement, cargo: CivCargo, from: CitizenPoint, to: CitizenPoint): CitizenSearchCorridor? {
        if (city.id != cityId || city.worldId != worldId || cargo.id != shipmentId || !geometry.matches(city) ||
            city.roadRouteOverrides.orEmpty() != overrides || from.distanceSquared(arrived) > 4.0 ||
            to.distanceSquared(start) > 16.0) return null
        return CitizenSearchCorridor.fromWalked(walked, from, to)
    }

    companion object {
        fun capture(city: Settlement, cargo: CivCargo, trip: CivsPaths.Trip,
                    start: CitizenPoint, arrived: CitizenPoint): CitizenCourierReturnHint? {
            if (!trip.validFor(city) || trip.walked.size !in 2..CivsPaths.MAX_TRIP_CELLS ||
                CitizenSearchCorridor.fromWalked(trip.walked, arrived, start) == null) return null
            return CitizenCourierReturnHint(city.id, city.worldId, cargo.id, trip.network.geometry,
                city.roadRouteOverrides.orEmpty(), start, arrived, trip.walked.toSet())
        }
    }
}
