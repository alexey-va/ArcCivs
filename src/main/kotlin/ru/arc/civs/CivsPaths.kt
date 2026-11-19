package ru.arc.civs

import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor

/** Remember walked corridors; Trails alone owns their wear, material and walking speed. Main thread only. */
internal class CivsPaths(private val courierRoads: (Settlement) -> RetainedRoads = { RetainedRoads.EMPTY }) {
    internal data class Geometry(val world: UUID, val center: CivPosition, val radius: Int,
                                 val buildings: List<CivBuilding>, val planned: Map<UUID, CivBuilding>) {
        constructor(city: Settlement) : this(city.worldId, city.center, city.radius, city.buildings, city.constructionFootprints())

        val protectedBuildings = buildings + planned.values
        fun matches(city: Settlement): Boolean = world == city.worldId && center == city.center && radius == city.radius &&
            buildings == city.buildings && city.matchesConstructionFootprints(planned)

        fun outside(point: CivPosition): Boolean = contains(point) && !onPlot(point)

        fun contains(point: CivPosition): Boolean =
            abs(point.x.toLong() - center.x) <= radius && abs(point.z.toLong() - center.z) <= radius

        fun overlapsChunk(x: Int, z: Int): Boolean {
            val minX = x.toLong() * 16
            val minZ = z.toLong() * 16
            return minX <= center.x.toLong() + radius && minX + 15 >= center.x.toLong() - radius &&
                minZ <= center.z.toLong() + radius && minZ + 15 >= center.z.toLong() - radius
        }

        fun onPlot(point: CivPosition): Boolean =
            protectedBuildings.any { point.x in it.bounds.min.x..it.bounds.max.x &&
                point.z in it.bounds.min.z..it.bounds.max.z || CivsWorksiteLayouts.isField(it, point.x, point.z) }
    }

    internal class Network(val geometry: Geometry) {
        private val learned = linkedSetOf<CivPosition>()
        val cells: Set<CivPosition> get() = learned
        private var snapshot: CivsRoadGraph.Supports? = null

        fun remember(point: CivPosition) { if (learned.add(point)) snapshot = null }
        fun forget(point: CivPosition) { if (learned.remove(point)) snapshot = null }

        // Jobs share one detached projection until this city's learned support actually changes.
        fun learnedRoads(): CivsRoadGraph.Supports = snapshot ?: CivsRoadGraph.Supports(
            buildSet(learned.size) { addAll(learned) }).also { snapshot = it }

        var completedTrips = 0L
        var tripsAlongRoad = 0L
        var longestRoadRun = 0
    }

    /** Paid approach floors and public transit grades, detached from the existing receipts. */
    internal class RetainedRoads private constructor(private val supports: Set<CivPosition>,
        private val guardApproaches: Map<UUID, List<CivPosition>> = emptyMap()) {
        // Navigation cost identity depends on the support grades, independently of patrol order.
        private val signature = supports.hashCode()
        internal val index = CivsRoadGraph.Supports(supports)
        operator fun contains(point: CivPosition): Boolean = point in supports
        fun guardApproach(towerId: UUID): List<CivPosition> = guardApproaches[towerId].orEmpty()
        internal fun debugSupports(): Set<CivPosition> = supports
        override fun hashCode(): Int = signature
        override fun equals(other: Any?): Boolean = this === other || other is RetainedRoads &&
            signature == other.signature && supports == other.supports

        companion object {
            val EMPTY = RetainedRoads(emptySet())

            fun from(city: Settlement, access: CivAutonomousAccess): RetainedRoads {
                val publicWays = access.infrastructure.filter { it.type == BuildingType.ROAD || it.type == BuildingType.GATE }
                    .associateBy { it.id }
                val guardApproaches = mutableMapOf<UUID, List<CivPosition>>()
                val hall = access.hallApproach(city)
                val supports = buildSet {
                    for (building in city.buildings) {
                        if (building.phase != BuildingPhase.ACTIVE) continue
                        val route = if (building.type.isInfrastructure) publicWays[building.id]?.transit
                            else access.routes[building.id]?.let { paid ->
                                val terminal = access.entrancePath(city, building, paid)?.travel(paid) ?: paid
                                hall?.takeIf { h -> h.witness.branches.any { it.owner == building.id } }?.travel(terminal) ?: terminal
                            }
                        for (feet in route.orEmpty()) add(feet.copy(y = feet.y - 1))
                        if (building.type == BuildingType.GATE &&
                            CivsGateSharedApproaches.proof(city.roadRouteOverrides?.get(building.id.toString())) != null)
                            for (feet in access.routes[building.id].orEmpty()) add(feet.copy(y = feet.y - 1))
                        if (building.type == BuildingType.GUARD_TOWER && route != null)
                            guardApproaches[building.id] = java.util.List.copyOf(route)
                    }
                }
                if (supports.isEmpty()) return EMPTY
                return RetainedRoads(supports, guardApproaches)
            }
        }
    }

    class Trip internal constructor(internal val cityId: UUID, internal val network: Network,
        internal val preferCourierRoads: Boolean, internal val retainedRoads: RetainedRoads,
        territory: CivRouteTerritory?) {
        // Costs cannot change halfway through one A* search as other residents finish their trips.
        internal val weights = Weights(network.geometry, network.learnedRoads(), preferCourierRoads, retainedRoads, territory)
        internal val reuseSignature: Set<CivPosition> get() = weights.preferred
        internal val walked = linkedSetOf<CivPosition>()
        internal var complete = false
        private var overflow = false
        private var lastGround: CivPosition? = null
        private var roadRun = 0
        internal var longestRoadRun = 0
            private set

        fun validFor(city: Settlement): Boolean = network.geometry.matches(city)

        fun observe(position: CitizenPoint) {
            if (complete || overflow) return
            val ground = CivPosition(floor(position.x).toInt(), citizenSupportY(position.y), floor(position.z).toInt())
            val previous = lastGround
            if (ground == previous) return
            lastGround = ground
            roadRun = when {
                ground !in retainedRoads -> 0
                previous == null || abs(previous.x.toLong() - ground.x) > 1 ||
                    abs(previous.z.toLong() - ground.z) > 1 || abs(previous.y.toLong() - ground.y) > 1 -> 1
                else -> roadRun + 1
            }
            longestRoadRun = maxOf(longestRoadRun, roadRun)
            if (!network.geometry.outside(ground)) return
            if (walked.size == MAX_TRIP_CELLS && ground !in walked) {
                overflow = true
                walked.clear()
            } else walked += ground
        }

        fun cost(ground: Block, recognizedSurface: (Block) -> Double): Double =
            weights.native(ground, recognizedSurface)
    }

    /** The detached cost callback retains only these admitted weights and a copy of the material tags. */
    internal class Weights(
        private val geometry: Geometry,
        learned: CivsRoadGraph.Supports,
        private val preferCourierRoads: Boolean,
        private val retainedRoads: RetainedRoads,
        private val territory: CivRouteTerritory? = null,
    ) {
        val preferred = learned.cells
        val roads = CivsRoadGraph(retainedRoads.index, learned)

        internal fun debugPolicy(allowedBuildings: Set<UUID>, logs: Set<Material>, leaves: Set<Material>) = CitizenNavigationDebugPolicy(
            geometry.center, geometry.radius, geometry.protectedBuildings
                .map(CitizenNavigationDebugBuilding::of), allowedBuildings.toSet(), preferred.toSet(),
            retainedRoads.debugSupports().toSet(), preferCourierRoads, (logs + leaves).mapTo(sortedSetOf(), Material::name))

        fun native(ground: Block, recognizedSurface: (Block) -> Double): Double =
            cost(CivPosition(ground.x, ground.y, ground.z), { ground.type }, { ground.blockData },
                { Tag.LOGS.isTagged(it) || Tag.LEAVES.isTagged(it) }, { recognizedSurface(ground) > 1.0 })

        fun detached(logs: Set<Material>, leaves: Set<Material>): (CivPosition, BlockData) -> Double =
            { point, data -> cost(point, { data.material }, { data }, { it in logs || it in leaves }, { false }) }

        private inline fun cost(point: CivPosition, type: () -> Material, data: () -> BlockData,
            canopy: (Material) -> Boolean, recognizedSurface: () -> Boolean): Double {
            if (!geometry.contains(point)) return 1.0
            if (territory?.onPlot(point) ?: geometry.onPlot(point)) {
                if (!preferCourierRoads) return 1.0
                val material = type()
                return if (material in CivsTerrainPolicy.AUTONOMOUS_SOIL &&
                    (material != Material.COARSE_DIRT || point !in retainedRoads)) ORDINARY_COST else 1.0
            }
            // A remembered line never licenses climbing an outdoor tree.
            val material = type()
            if (canopy(material)) return Double.POSITIVE_INFINITY
            return when {
                material == Material.DIRT_PATH -> 1.0
                point in retainedRoads && (material == Material.COARSE_DIRT ||
                    CivsRoadMaintenance.surface(data().asString)) -> 1.0
                // The provider may recognize untracked materials when its configuration permits it.
                recognizedSurface() -> 1.0
                point in preferred -> LEARNED_COST
                else -> ORDINARY_COST
            }
        }
    }

    private class Learning(val trip: Trip, val points: Iterator<CivPosition>) {
        var existing: Set<CivPosition>? = null
    }
    private val networks = LinkedHashMap<UUID, Network>(MAX_CITIES, .75f, true)
    private val learning = ArrayDeque<Learning>()

    fun begin(city: Settlement, preferCourierRoads: Boolean = false, roads: RetainedRoads = courierRoads(city),
        territory: CivRouteTerritory? = null): Trip {
        val network = networks[city.id]?.takeIf { it.geometry.matches(city) }
            ?: Network(Geometry(city)).also { networks[city.id] = it }
        while (networks.size > MAX_CITIES) networks.remove(networks.keys.first())
        return Trip(city.id, network, preferCourierRoads, roads, territory)
    }

    fun complete(city: Settlement, trip: Trip) {
        if (trip.complete) return
        trip.complete = true
        if (city.pendingDeletion != null || networks[city.id] !== trip.network || !trip.validFor(city)) return
        val network = trip.network
        network.completedTrips++
        if (trip.longestRoadRun >= 3) network.tripsAlongRoad++
        network.longestRoadRun = maxOf(network.longestRoadRun, trip.longestRoadRun)
        if (trip.walked.size < 2 || learning.size >= MAX_PENDING_TRIPS) return
        learning += Learning(trip, trip.walked.iterator())
    }

    /** Pure, shared admission budget. Do not spread a parallel trip into a second road beside the first. */
    fun pulse() {
        var remaining = CELLS_PER_TICK
        while (remaining > 0 && learning.isNotEmpty()) {
            val batch = learning.first()
            val network = batch.trip.network
            if (networks[batch.trip.cityId] !== network || !batch.points.hasNext()) {
                learning.removeFirst()
                continue
            }
            // Snapshot when this completed trip reaches the queue head, after earlier trips.
            // Comparing against its own newly added cells would leave a dotted corridor.
            val existing = batch.existing ?: network.learnedRoads().cells.also { batch.existing = it }
            val point = batch.points.next()
            remaining--
            if (point !in network.cells && !nearCorridor(existing, point) && network.cells.size < MAX_CELLS_PER_CITY)
                network.remember(point)
        }
    }

    fun retain(current: Map<UUID, Settlement>) {
        networks.entries.removeIf { (id, network) ->
            current[id]?.takeIf { it.pendingDeletion == null }?.let(network.geometry::matches) != true
        }
    }

    fun removeWorld(world: UUID) { networks.entries.removeIf { it.value.geometry.world == world } }

    fun invalidateChunk(world: UUID, x: Int, z: Int) {
        // All learned cells passed Geometry.outside when observed. An unload outside
        // the settlement cannot invalidate a remembered route or pending learning.
        networks.entries.removeIf { (_, network) -> network.geometry.world == world &&
            network.geometry.overlapsChunk(x, z) &&
            network.cells.any { it.x shr 4 == x && it.z shr 4 == z } }
        learning.removeIf { batch ->
            val geometry = batch.trip.network.geometry
            geometry.world == world && geometry.overlapsChunk(x, z) &&
                batch.trip.walked.any { it.x shr 4 == x && it.z shr 4 == z }
        }
    }

    fun invalidateBlock(world: UUID, changed: CivPosition) {
        networks.values.filter { it.geometry.world == world }.forEach { network ->
            for (dx in -1..1) for (dz in -1..1) for (dy in 0..2)
                network.forget(CivPosition(changed.x + dx, changed.y - dy, changed.z + dz))
        }
    }

    fun clear() { networks.clear(); learning.clear() }

    fun roadTravel(cityId: UUID?): Map<String, Number>? = cityId?.let(networks::get)?.let { network ->
        mapOf("completed_work_trips" to network.completedTrips, "trips_along_retained_road" to network.tripsAlongRoad,
            "longest_retained_road_run" to network.longestRoadRun)
    }

    internal fun remembered(cityId: UUID): Set<CivPosition> = networks[cityId]?.cells?.toSet().orEmpty()


    private fun nearCorridor(existing: Set<CivPosition>, point: CivPosition): Boolean {
        for (dx in -2..2) for (dz in -2..2) for (dy in -1..1)
            if (CivPosition(point.x + dx, point.y + dy, point.z + dz) in existing) return true
        return false
    }

    companion object {
        const val ORDINARY_COST = 2.25
        const val LEARNED_COST = 1.5
        const val MAX_CITIES = 32
        const val MAX_CELLS_PER_CITY = 512
        const val MAX_TRIP_CELLS = 256
        const val MAX_PENDING_TRIPS = 48
        const val CELLS_PER_TICK = 16
    }
}

/** Feet on a path/slab are inside that block's Y coordinate, unlike feet on a full block. */
internal fun citizenSupportY(feetY: Double): Int = floor(feetY - .001).toInt()
