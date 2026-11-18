package ru.arc.civs

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Detached search preference shared by resident trips, courier returns and proposed building access. */
internal class CitizenSearchCorridor(val columns: Set<Long>, val minimumY: Double, val maximumY: Double) {
    fun contains(point: CitizenPoint): Boolean = point.y in minimumY..maximumY &&
        civsTerrainKey(floor(point.x).toInt(), floor(point.z).toInt()) in columns

    private val snapshotChunks by lazy {
        buildSet {
            for (column in columns) {
                val x = (column shr 32).toInt()
                val z = column.toInt()
                for (cx in ((x - 1) shr 4)..((x + 1) shr 4))
                    for (cz in ((z - 1) shr 4)..((z + 1) shr 4)) add(CitizenSnapshotKey(cx, cz))
            }
        }
    }
    internal fun snapshotSection(section: CitizenSnapshotSectionKey): Boolean =
        CitizenSnapshotKey(section.x, section.z) in snapshotChunks &&
            section.y in ((floor(minimumY).toInt() - 3) shr 4)..((floor(maximumY).toInt() + 3) shr 4)

    private fun connects(from: CitizenPoint, to: CitizenPoint): Boolean {
        val start = civsTerrainKey(floor(from.x).toInt(), floor(from.z).toInt())
        val goal = civsTerrainKey(floor(to.x).toInt(), floor(to.z).toInt())
        val remaining = columns.toMutableSet()
        val pending = ArrayDeque<Long>()
        pending += start
        remaining.remove(start)
        while (pending.isNotEmpty()) {
            val column = pending.removeFirst()
            if (column == goal) return true
            val x = (column shr 32).toInt()
            val z = column.toInt()
            for (dx in -1..1) for (dz in -1..1) {
                val next = civsTerrainKey(x + dx, z + dz)
                if (remaining.remove(next)) pending += next
            }
        }
        return false
    }

    companion object {
        const val ANCHOR_DISTANCE = 12
        private const val WAREHOUSE_ANCHOR_DISTANCE = 64

        fun fromWalked(walked: Set<CivPosition>, from: CitizenPoint, to: CitizenPoint,
                       warehouseAccess: Boolean = false): CitizenSearchCorridor? {
            if (walked.size !in 2..CivsPaths.MAX_TRIP_CELLS) return null
            val start = walked.minBy { it.roadDistanceSquared(from) }
            val goal = walked.minBy { it.roadDistanceSquared(to) }
            val access = if (warehouseAccess) WAREHOUSE_ANCHOR_DISTANCE else ANCHOR_DISTANCE
            return fromSupports(walked, from, to, start, goal, access)?.takeIf { it.connects(from, to) }
        }

        /** A connected graph path already has both anchors. Unordered walked cells also require the flood check above. */
        fun fromSupports(supports: Set<CivPosition>, from: CitizenPoint, to: CitizenPoint,
                         start: CivPosition, goal: CivPosition, goalAccess: Int = ANCHOR_DISTANCE): CitizenSearchCorridor? {
            if (supports.size !in 2..CivsPaths.MAX_TRIP_CELLS ||
                start.roadDistanceSquared(from) > ANCHOR_DISTANCE * ANCHOR_DISTANCE ||
                goal.roadDistanceSquared(to) > goalAccess * goalAccess) return null
            val low = minOf(from.y, to.y, supports.minOf { it.y + 1.0 })
            val high = maxOf(from.y, to.y, supports.maxOf { it.y + 1.0 })
            if (high - low > 16) return null
            val columns = hashSetOf<Long>()
            supports.forEach { columns += civsTerrainKey(it.x, it.z) }
            fun connect(endpoint: CitizenPoint, anchor: CivPosition) {
                val dx = anchor.x + .5 - endpoint.x
                val dz = anchor.z + .5 - endpoint.z
                val steps = ceil(max(abs(dx), abs(dz))).toInt().coerceAtLeast(1)
                for (step in 0..steps) {
                    val fraction = step.toDouble() / steps
                    val x = floor(endpoint.x + dx * fraction).toInt()
                    val z = floor(endpoint.z + dz * fraction).toInt()
                    for (ax in -2..2) for (az in -2..2) columns += civsTerrainKey(x + ax, z + az)
                }
            }
            connect(from, start)
            connect(to, goal)
            return CitizenSearchCorridor(columns, low - 2, high + 2)
        }
    }
}

/** Horizontal coordinates are measured at the centre of the road's support block, Y at its top. */
internal fun CivPosition.roadDistanceSquared(endpoint: CitizenPoint): Double {
    val dx = x + .5 - endpoint.x
    val dy = y + 1.0 - endpoint.y
    val dz = z + .5 - endpoint.z
    return dx * dx + dy * dy + dz * dz
}
