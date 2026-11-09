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
                                 val buildings: List<CivBuilding>, val planned: CivBuilding?) {
        private val protectedBuildings = buildings + listOfNotNull(planned)
        fun matches(city: Settlement): Boolean = world == city.worldId && center == city.center && radius == city.radius &&
            buildings == city.buildings && planned == city.construction?.plannedBuilding

        fun outside(point: CivPosition): Boolean = contains(point) && !onPlot(point)

        fun contains(point: CivPosition): Boolean =
            abs(point.x.toLong() - center.x) <= radius && abs(point.z.toLong() - center.z) <= radius

        fun onPlot(point: CivPosition): Boolean =
            protectedBuildings.any { point.x in it.bounds.min.x..it.bounds.max.x &&
                point.z in it.bounds.min.z..it.bounds.max.z || CivsWorksiteLayouts.isField(it, point.x, point.z) }
    }

    internal class Network(val geometry: Geometry, val cells: LinkedHashSet<CivPosition> = linkedSetOf())

    /** Built from the existing detached receipts, without native terrain reads. */
    internal class RetainedRoads private constructor(private val supports: Set<CivPosition>) {
        private val signature = supports.hashCode()
        operator fun contains(point: CivPosition): Boolean = point in supports
        override fun hashCode(): Int = signature
        override fun equals(other: Any?): Boolean = this === other || other is RetainedRoads &&
            signature == other.signature && supports == other.supports

        companion object {
            val EMPTY = RetainedRoads(emptySet())

            fun from(city: Settlement, access: CivAutonomousAccess): RetainedRoads {
                val supports = buildSet {
                    for (building in city.buildings) {
                        if (building.phase != BuildingPhase.ACTIVE || building.type.isInfrastructure) continue
                        for (feet in access.routes[building.id].orEmpty()) add(feet.copy(y = feet.y - 1))
                    }
                }
                return if (supports.isEmpty()) EMPTY else RetainedRoads(supports)
            }
        }
    }

    class Trip internal constructor(internal val cityId: UUID, internal val network: Network,
        internal val preferCourierRoads: Boolean, internal val retainedRoads: RetainedRoads) {
        // Costs cannot change halfway through one A* search as other residents finish their trips.
        internal val weights = Weights(network.geometry, network.cells.toHashSet(), preferCourierRoads, retainedRoads)
        internal val reuseSignature: Set<CivPosition> get() = weights.preferred
        internal val walked = linkedSetOf<CivPosition>()
        internal var complete = false
        private var overflow = false

        fun validFor(city: Settlement): Boolean = network.geometry.matches(city)

        fun observe(position: CitizenPoint) {
            if (complete || overflow) return
            val ground = CivPosition(floor(position.x).toInt(), citizenSupportY(position.y), floor(position.z).toInt())
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
        val preferred: Set<CivPosition>,
        private val preferCourierRoads: Boolean,
        private val retainedRoads: RetainedRoads,
    ) {
        fun native(ground: Block, recognizedSurface: (Block) -> Double): Double =
            cost(CivPosition(ground.x, ground.y, ground.z), { ground.type }, { ground.blockData },
                { Tag.LOGS.isTagged(it) || Tag.LEAVES.isTagged(it) }, { recognizedSurface(ground) > 1.0 })

        fun detached(logs: Set<Material>, leaves: Set<Material>): (CivPosition, BlockData) -> Double =
            { point, data -> cost(point, { data.material }, { data }, { it in logs || it in leaves }, { false }) }

        private inline fun cost(point: CivPosition, type: () -> Material, data: () -> BlockData,
            canopy: (Material) -> Boolean, recognizedSurface: () -> Boolean): Double {
            if (!geometry.contains(point)) return 1.0
            if (geometry.onPlot(point)) {
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
                point in preferred -> 1.5
                else -> ORDINARY_COST
            }
        }
    }

    private class Learning(val trip: Trip, val points: Iterator<CivPosition>) {
        var existing: Set<CivPosition>? = null
    }
    private val networks = LinkedHashMap<UUID, Network>(MAX_CITIES, .75f, true)
    private val learning = ArrayDeque<Learning>()

    fun begin(city: Settlement, preferCourierRoads: Boolean = false): Trip {
        val geometry = geometry(city)
        val network = networks[city.id]?.takeIf { it.geometry == geometry }
            ?: Network(geometry).also { networks[city.id] = it }
        while (networks.size > MAX_CITIES) networks.remove(networks.keys.first())
        return Trip(city.id, network, preferCourierRoads, if (preferCourierRoads) courierRoads(city) else RetainedRoads.EMPTY)
    }

    fun complete(city: Settlement, trip: Trip) {
        if (trip.complete) return
        trip.complete = true
        if (trip.walked.size < 2 || city.pendingDeletion != null ||
            networks[city.id] !== trip.network || !trip.validFor(city) ||
            learning.size >= MAX_PENDING_TRIPS) return
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
            val existing = batch.existing ?: network.cells.toHashSet().also { batch.existing = it }
            val point = batch.points.next()
            remaining--
            if (point !in network.cells && !nearCorridor(existing, point) && network.cells.size < MAX_CELLS_PER_CITY)
                network.cells += point
        }
    }

    fun retain(current: Map<UUID, Settlement>) {
        networks.entries.removeIf { (id, network) ->
            current[id]?.takeIf { it.pendingDeletion == null }?.let(network.geometry::matches) != true
        }
    }

    fun removeWorld(world: UUID) { networks.entries.removeIf { it.value.geometry.world == world } }

    fun invalidateChunk(world: UUID, x: Int, z: Int) {
        networks.entries.removeIf { (_, network) -> network.geometry.world == world &&
            network.cells.any { it.x shr 4 == x && it.z shr 4 == z } }
        learning.removeIf { batch -> batch.trip.network.geometry.world == world &&
            batch.trip.walked.any { it.x shr 4 == x && it.z shr 4 == z } }
    }

    fun invalidateBlock(world: UUID, changed: CivPosition) {
        networks.values.filter { it.geometry.world == world }.forEach { network ->
            for (dx in -1..1) for (dz in -1..1) for (dy in 0..2)
                network.cells.remove(CivPosition(changed.x + dx, changed.y - dy, changed.z + dz))
        }
    }

    fun clear() { networks.clear(); learning.clear() }

    internal fun remembered(cityId: UUID): Set<CivPosition> = networks[cityId]?.cells?.toSet().orEmpty()

    private fun geometry(city: Settlement) =
        Geometry(city.worldId, city.center, city.radius, city.buildings, city.construction?.plannedBuilding)

    private fun nearCorridor(existing: Set<CivPosition>, point: CivPosition): Boolean {
        for (dx in -2..2) for (dz in -2..2) for (dy in -1..1)
            if (CivPosition(point.x + dx, point.y + dy, point.z + dz) in existing) return true
        return false
    }

    companion object {
        const val ORDINARY_COST = 2.25
        const val MAX_CITIES = 32
        const val MAX_CELLS_PER_CITY = 512
        const val MAX_TRIP_CELLS = 256
        const val MAX_PENDING_TRIPS = 48
        const val CELLS_PER_TICK = 16
    }
}

/** Feet on a path/slab are inside that block's Y coordinate, unlike feet on a full block. */
internal fun citizenSupportY(feetY: Double): Int = floor(feetY - .001).toInt()
