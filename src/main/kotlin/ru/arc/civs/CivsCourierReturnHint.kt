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
        private const val WAREHOUSE_ANCHOR_DISTANCE = 32
        fun fromWalked(walked: Set<CivPosition>, from: CitizenPoint, to: CitizenPoint,
                       warehouseAccess: Boolean = false): CitizenSearchCorridor? {
            if (walked.size !in 2..CivsPaths.MAX_TRIP_CELLS) return null
            val columns = hashSetOf<Long>()
            fun add(x: Int, z: Int, radius: Int) {
                for (dx in -radius..radius) for (dz in -radius..radius)
                    columns += civsTerrainKey(x + dx, z + dz)
            }
            walked.forEach { add(it.x, it.z, 1) }
            for ((index, endpoint) in listOf(from, to).withIndex()) {
                val nearest = walked.minByOrNull { it.copy(y = it.y + 1).point().distanceSquared(endpoint) } ?: return null
                val target = nearest.copy(y = nearest.y + 1).point()
                val access = if (warehouseAccess && index == 1) WAREHOUSE_ANCHOR_DISTANCE else ANCHOR_DISTANCE
                if (target.distanceSquared(endpoint) > access * access) return null
                val dx = target.x - endpoint.x
                val dz = target.z - endpoint.z
                val steps = ceil(max(abs(dx), abs(dz))).toInt().coerceAtLeast(1)
                for (step in 0..steps) {
                    val fraction = step.toDouble() / steps
                    add(floor(endpoint.x + dx * fraction).toInt(), floor(endpoint.z + dz * fraction).toInt(), 2)
                }
            }
            // Outdoor observation omits building interiors. Only the two bounded
            // access tubes may bridge those omissions; the warehouse side may
            // also connect a recent nearby delivery. All cells still need fresh A*.
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
    private val warehouseAccess: Boolean,
) {
    fun corridor(city: Settlement, cargo: CivCargo, from: CitizenPoint, to: CitizenPoint): CitizenSearchCorridor? {
        if (city.id != cityId || city.worldId != worldId || cargo.id != shipmentId || !geometry.matches(city) ||
            city.roadRouteOverrides.orEmpty() != overrides || from.distanceSquared(arrived) > 4.0 ||
            (if (warehouseAccess) to != start else to.distanceSquared(start) > 16.0)) return null
        return CitizenSearchCorridor.fromWalked(walked, from, to, warehouseAccess)
    }

    companion object {
        fun capture(city: Settlement, cargo: CivCargo, trip: CivsPaths.Trip,
                    start: CitizenPoint, arrived: CitizenPoint,
                    walked: Set<CivPosition> = trip.walked, warehouseAccess: Boolean = false): CitizenCourierReturnHint? {
            if (warehouseAccess) {
                if (city.inventory?.mode != CivStorageMode.PHYSICAL || cargo.sourceWorkerId != null) return null
                val control = city.building(cargo.warehouseId)?.takeIf { it.type == BuildingType.WAREHOUSE }?.control ?: return null
                val dx = start.x - control.x - .5; val dz = start.z - control.z - .5
                if (cargo.targetBuildingId != cargo.warehouseId || abs(start.y - control.y) > 1.5 || dx * dx + dz * dz > 1.75 * 1.75 ||
                    floor(start.x).toInt() == control.x && floor(start.z).toInt() == control.z)
                    return null
            }
            if (!trip.validFor(city) || walked.size !in 2..CivsPaths.MAX_TRIP_CELLS ||
                CitizenSearchCorridor.fromWalked(walked, arrived, start, warehouseAccess) == null) return null
            return CitizenCourierReturnHint(city.id, city.worldId, cargo.id, trip.network.geometry,
                city.roadRouteOverrides.orEmpty(), start, arrived, walked.toSet(), warehouseAccess)
        }
    }
}

/** One physical source leg's observed prefix, never old navigation or road-learning work. */
internal class CitizenCourierInboundPrefix private constructor(
    private val cityId: UUID, private val worldId: UUID, private val center: CivPosition, private val radius: Int,
    private val cargo: CivCargo, private val destinationId: UUID, private val endpoints: List<CivBuilding>,
    val origin: CitizenPoint, private var actual: CitizenPoint,
) {
    // Null is a tombstone: a later continuation cannot revive overflow or a rejected identity.
    private var walked: Set<CivPosition>? = emptySet()
    private var segmentObserved = false

    private fun matches(city: Settlement, current: CivCargo, destination: UUID): Boolean =
        city.id == cityId && city.worldId == worldId && city.center == center && city.radius == radius &&
            city.inventory?.mode == CivStorageMode.PHYSICAL && cargo == current.copy(inventoryBacked = false) &&
            destinationId == destination && endpoints.all { shape(city.building(it.id)) == it }

    fun start(city: Settlement, cargo: CivCargo, destination: UUID, from: CitizenPoint) {
        if (!matches(city, cargo, destination) || actual.distanceSquared(from) > .25) walked = null
        segmentObserved = false
    }

    fun observe(from: CitizenPoint, to: CitizenPoint, trip: CivsPaths.Trip) {
        // Trip only clears a previously nonempty observation set on its 256-cell overflow.
        if (actual.distanceSquared(from) > .25 || segmentObserved && trip.walked.isEmpty()) walked = null
        segmentObserved = segmentObserved || trip.walked.isNotEmpty()
        actual = to
    }

    fun append(city: Settlement, cargo: CivCargo, destination: UUID, trip: CivsPaths.Trip,
               at: CitizenPoint) {
        val previous = walked
        if (previous == null || !matches(city, cargo, destination) || actual.distanceSquared(at) > .25 ||
            trip.cityId != city.id || trip.network.geometry.world != worldId || trip.network.geometry.center != center ||
            trip.network.geometry.radius != radius || segmentObserved && trip.walked.isEmpty()) { walked = null; return }
        val combined = previous + trip.walked
        val geometry = CivsPaths.Geometry(city.worldId, city.center, city.radius, city.buildings, city.construction?.plannedBuilding)
        walked = combined.takeIf { it.size <= CivsPaths.MAX_TRIP_CELLS && it.all(geometry::outside) }
    }

    fun finish(city: Settlement, cargo: CivCargo, destination: UUID, trip: CivsPaths.Trip,
               at: CitizenPoint, returnTarget: CitizenPoint? = null): CitizenCourierReturnHint? {
        if (!trip.validFor(city)) return null
        append(city, cargo, destination, trip, at)
        return walked?.let { CitizenCourierReturnHint.capture(city, cargo, trip, returnTarget ?: origin, at, it,
            warehouseAccess = returnTarget != null) }
    }

    companion object {
        private fun shape(building: CivBuilding?): CivBuilding? = building?.copy(phase = BuildingPhase.ACTIVE, production = null)
        fun begin(city: Settlement, cargo: CivCargo, destination: UUID, origin: CitizenPoint): CitizenCourierInboundPrefix? {
            if (city.inventory?.mode != CivStorageMode.PHYSICAL || cargo.sourceWorkerId != null ||
                destination != cargo.warehouseId || city.building(cargo.warehouseId)?.type != BuildingType.WAREHOUSE) return null
            val ids = setOf(cargo.warehouseId, cargo.sourceBuildingId, destination)
            val endpoints = ids.map { shape(city.building(it)) ?: return null }
            if (endpoints.any { it.control == null }) return null
            return CitizenCourierInboundPrefix(city.id, city.worldId, city.center, city.radius,
                cargo.copy(inventoryBacked = false), destination, endpoints, origin, origin)
        }
    }
}
