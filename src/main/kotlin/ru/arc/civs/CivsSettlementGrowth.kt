package ru.arc.civs

import kotlin.math.abs
import java.util.ArrayDeque

/** Ordinary accepted buildings and one next goal determine growth; the maximum town is only a forecast. */
internal object CivsSettlementGrowth {
    private const val FIRST_HALF_WIDTH = 32
    private const val QUARTER_WIDTH = 8

    fun initialHalfWidth(city: Settlement): Int {
        val hall = city.buildings.first { it.type == BuildingType.TOWN_HALL }
        val box = CivsAutonomousGeometry.futureBounds(city, hall)
        val extent = maxOf(abs(box.min.x.toLong() - city.center.x), abs(box.max.x.toLong() - city.center.x),
            abs(box.min.z.toLong() - city.center.z), abs(box.max.z.toLong() - city.center.z)) + 8
        val fit = ((extent + QUARTER_WIDTH - 1) / QUARTER_WIDTH * QUARTER_WIDTH).toInt()
        return maxOf(FIRST_HALF_WIDTH, fit).coerceAtMost(minOf(CivsSettlementLayout.MAXIMUM_HALF_WIDTH, city.radius - 24))
            .coerceAtLeast(24)
    }

    fun profile(city: Settlement): List<BuildingType> = buildList {
        addAll(city.buildings.filter { !it.type.isInfrastructure && it.type != BuildingType.DECORATION }.map { it.type })
        CivsAutonomy.nextGoal(city)?.takeIf { it.buildingId == null && !it.type.isInfrastructure &&
            it.type != BuildingType.DECORATION }?.let { add(it.type) }
    }

    fun covers(city: Settlement, plan: CivSettlementPlan): Boolean {
        val actual = plan.plots.groupingBy { it.building.type }.eachCount()
        return profile(city).groupingBy { it }.eachCount().all { (type, count) -> (actual[type] ?: 0) >= count }
    }

    fun halfWidths(city: Settlement): List<Int> {
        val current = CivsSettlementLayout.frame(city).halfWidth
        val maximum = minOf(CivsSettlementLayout.MAXIMUM_HALF_WIDTH, city.radius - 24).coerceAtLeast(current)
        return ((current..maximum step QUARTER_WIDTH).toList() + maximum).distinct()
    }

    fun outside(city: Settlement, envelope: CivBox): Boolean {
        val frame = CivsSettlementLayout.frame(city)
        if (CivsAutonomousGeometry.overlaps(envelope, frame.core, 3) ||
            (frame.wallObstacles + frame.gates.map { it.passage }).any { CivsAutonomousGeometry.overlaps(envelope, it, 3) }) return false
        val interior = interior(frame)
        return interior.isNotEmpty() && interior.none { key ->
            (key shr 32).toInt() in envelope.min.x..envelope.max.x && key.toInt() in envelope.min.z..envelope.max.z
        }
    }

    /** Gates close the planning boundary even while their native doors are open for travel. */
    fun interior(frame: CivSettlementFrame): Set<Long> {
        val edges = frame.wallObstacles + frame.gates.map { it.passage }
        val boxes = edges + frame.core
        val minX = boxes.minOf { it.min.x } - 1
        val maxX = boxes.maxOf { it.max.x } + 1
        val minZ = boxes.minOf { it.min.z } - 1
        val maxZ = boxes.maxOf { it.max.z } + 1
        // Existing separator retries add at most eight columns; two more cover its outer witness border.
        val maximumSpan = (CivsSettlementLayout.MAXIMUM_HALF_WIDTH + 10) * 2 + 1
        if (maxX.toLong() - minX + 1 !in 1L..maximumSpan.toLong() ||
            maxZ.toLong() - minZ + 1 !in 1L..maximumSpan.toLong()) return emptySet()
        val closed = edges.flatMapTo(hashSetOf()) { box -> (box.min.x..box.max.x).flatMap { x ->
            (box.min.z..box.max.z).map { z -> civsTerrainKey(x, z) }
        } }
        val exterior = hashSetOf<Long>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        fun add(x: Int, z: Int) {
            val key = civsTerrainKey(x, z)
            if (x in minX..maxX && z in minZ..maxZ && key !in closed && exterior.add(key)) queue.add(x to z)
        }
        for (x in minX..maxX) { add(x, minZ); add(x, maxZ) }
        for (z in minZ..maxZ) { add(minX, z); add(maxX, z) }
        while (queue.isNotEmpty()) {
            val (x, z) = queue.removeFirst()
            add(x - 1, z); add(x + 1, z); add(x, z - 1); add(x, z + 1)
        }
        return buildSet {
            for (x in minX..maxX) for (z in minZ..maxZ) {
                val key = civsTerrainKey(x, z)
                if (key !in closed && key !in exterior) add(key)
            }
        }
    }
}
