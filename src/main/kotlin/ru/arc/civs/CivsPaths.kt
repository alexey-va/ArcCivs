package ru.arc.civs

import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor

/** Remember walked corridors; Trails alone owns their wear, material and walking speed. Main thread only. */
internal class CivsPaths {
    internal data class Geometry(val world: UUID, val center: CivPosition, val radius: Int,
                                 val buildings: List<CivBuilding>, val planned: CivBuilding?) {
        private val protectedBuildings = buildings + listOfNotNull(planned)
        fun matches(city: Settlement): Boolean = world == city.worldId && center == city.center && radius == city.radius &&
            buildings == city.buildings && planned == city.construction?.plannedBuilding

        fun outside(point: CivPosition): Boolean =
            abs(point.x.toLong() - center.x) <= radius && abs(point.z.toLong() - center.z) <= radius &&
                protectedBuildings.none { point.x in it.bounds.min.x..it.bounds.max.x &&
                    point.z in it.bounds.min.z..it.bounds.max.z || CivsWorksiteLayouts.isField(it, point.x, point.z) }
    }

    internal class Network(val geometry: Geometry, val cells: LinkedHashSet<CivPosition> = linkedSetOf())

    class Trip internal constructor(internal val cityId: UUID, internal val network: Network) {
        // Costs cannot change halfway through one A* search as other residents finish their trips.
        private val preferred = network.cells.toHashSet()
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

        fun cost(ground: Block, recognizedSurface: (Block) -> Double): Double {
            val point = CivPosition(ground.x, ground.y, ground.z)
            if (!network.geometry.outside(point)) return 1.0
            // A remembered line never licenses climbing an outdoor tree.
            if (Tag.LOGS.isTagged(ground.type) || Tag.LEAVES.isTagged(ground.type)) return Double.POSITIVE_INFINITY
            return when {
                point in preferred -> 1.0
                ground.type == Material.DIRT_PATH -> 1.15
                // The provider may recognize untracked materials when its configuration permits it.
                recognizedSurface(ground) > 1.0 -> 1.25
                else -> ORDINARY_COST
            }
        }
    }

    private class Learning(val trip: Trip, val points: Iterator<CivPosition>) {
        var existing: Set<CivPosition>? = null
    }
    private val networks = LinkedHashMap<UUID, Network>(MAX_CITIES, .75f, true)
    private val learning = ArrayDeque<Learning>()

    fun begin(city: Settlement): Trip {
        val geometry = geometry(city)
        val network = networks[city.id]?.takeIf { it.geometry == geometry }
            ?: Network(geometry).also { networks[city.id] = it }
        while (networks.size > MAX_CITIES) networks.remove(networks.keys.first())
        return Trip(city.id, network)
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
        const val ORDINARY_COST = 1.6
        const val MAX_CITIES = 32
        const val MAX_CELLS_PER_CITY = 512
        const val MAX_TRIP_CELLS = 256
        const val MAX_PENDING_TRIPS = 48
        const val CELLS_PER_TICK = 16
    }
}

/** Feet on a path/slab are inside that block's Y coordinate, unlike feet on a full block. */
internal fun citizenSupportY(feetY: Double): Int = floor(feetY - .001).toInt()
