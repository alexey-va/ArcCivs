package ru.arc.civs

import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The planning view joins accepted city records with independently refreshed observations.
 * Residents and roads are immutable publications. Store slots publish independently, and
 * world terrain is shared by section in CivsNavigationSnapshots across every city's routes.
 */
internal data class CivSettlementWorkFrame(
    val city: Settlement,
    val residents: CivResidentReadFrame,
    val stores: CivSettlementSupplyRead,
    val roads: CivsPaths.RetainedRoads,
    val territory: CivRouteTerritory,
)

internal class CivsSettlementFrames {
    private val territories = mutableMapOf<UUID, CivRouteTerritory>()

    fun territory(city: Settlement): CivRouteTerritory {
        val previous = territories[city.id]
        return previous?.takeIf { it.matches(city) } ?: CivRouteTerritory(city, previous).also { territories[city.id] = it }
    }

    fun remove(cityId: UUID) { territories.remove(cityId) }
    fun clear() { territories.clear() }
}

/** Detached plot and furniture geometry, shared until the accepted layout changes. */
internal class CivRouteTerritory(city: Settlement, previous: CivRouteTerritory?) {
    constructor(city: Settlement) : this(city, null)
    data class GateExit(val gate: UUID, val transit: CivPosition, val approach: CivPosition)
    private class Plot(val building: CivBuilding) {
        val bounds = building.bounds
        val field = CivsWorksiteLayouts.fieldBounds(building)
        val public = civsPublicTransit(building)

        fun covers(x: Int, z: Int): Boolean = bounds.coversColumn(x, z) ||
            field?.coversColumn(x, z) == true && CivsWorksiteLayouts.isField(building, x, z)

        fun blocks(point: CivPosition, allowed: Set<UUID>, mineBottom: Int?, wallCrossing: Boolean): Boolean =
            building.id !in allowed && covers(point.x, point.z) &&
                (!public || !civsPublicTransit(building, point.x, point.z)) &&
                !(wallCrossing && building.type == BuildingType.WALL && building.phase == BuildingPhase.ACTIVE &&
                    point.y.toLong() in bounds.min.y.toLong()..(bounds.max.y.toLong() + 2)) &&
                (mineBottom == null || point.y >= mineBottom || !CivsMinePlan.belowFoundation(building, point.y, headroom = 1))
    }

    private val world = city.worldId
    private val center = city.center
    private val radius = city.radius
    private val buildings = city.buildings
    private val planned = city.constructionFootprints()
    private val chunks = hashMapOf<Long, MutableList<Plot>>()
    private val large = mutableListOf<Plot>()
    val controls = CitizenRouteControls(city)
    private val gates = buildings.filter { it.type == BuildingType.GATE && civsPublicTransit(it) }
    private val ring = if (gates.isEmpty()) emptySet() else buildings.asSequence()
        .filter { it.phase == BuildingPhase.ACTIVE && it.type == BuildingType.WALL }.map { it.bounds }.toMutableSet()
        .also { walls -> if (walls.isNotEmpty()) gates.mapTo(walls) { it.bounds } }
    private val gateInterior: Lazy<GateInterior> = if (previous != null && previous.ring == ring) previous.gateInterior
        else retainedGateInterior(ring)

    init {
        for (building in civsProtectedBuildings(city)) {
            val plot = Plot(building)
            val field = plot.field ?: plot.bounds
            val minX = minOf(plot.bounds.min.x, field.min.x) shr 4
            val maxX = maxOf(plot.bounds.max.x, field.max.x) shr 4
            val minZ = minOf(plot.bounds.min.z, field.min.z) shr 4
            val maxZ = maxOf(plot.bounds.max.z, field.max.z) shr 4
            if ((maxX.toLong() - minX + 1) * (maxZ.toLong() - minZ + 1) > 64) {
                large += plot
            } else {
                for (x in minX..maxX) for (z in minZ..maxZ) chunks.getOrPut(columnKey(x, z), ::mutableListOf) += plot
            }
        }
    }

    fun matches(city: Settlement): Boolean = world == city.worldId && center == city.center && radius == city.radius &&
        buildings == city.buildings && city.matchesConstructionFootprints(planned)

    fun onPlot(point: CivPosition): Boolean =
        chunks[columnKey(point.x shr 4, point.z shr 4)]?.any { it.covers(point.x, point.z) } == true ||
            large.any { it.covers(point.x, point.z) }

    fun allows(point: CivPosition, allowed: Set<UUID>, mineBottom: Int?, wallCrossing: Boolean = false): Boolean =
        kotlin.math.abs(point.x.toLong() - center.x) <= radius && kotlin.math.abs(point.z.toLong() - center.z) <= radius &&
            chunks[columnKey(point.x shr 4, point.z shr 4)]?.none { it.blocks(point, allowed, mineBottom, wallCrossing) } != false &&
            large.none { it.blocks(point, allowed, mineBottom, wallCrossing) }

    /** A crossing ends one cell beyond authored public transit, on the same side as the actual destination. */
    fun gateExits(from: CitizenPoint, target: CivPosition, reach: Double): List<GateExit> {
        if (ring.isEmpty()) return emptyList()
        val interior = gateInterior.value
        val goalInside = interior.contains(target.x, target.z)
        if (interior.contains(floor(from.x).toInt(), floor(from.z).toInt()) == goalInside) return emptyList()
        val radius = if (reach == 0.0) 0 else ceil(reach + .5).toInt()
        for (dx in -radius..radius) for (dz in -radius..radius)
            if (interior.contains(target.x + dx, target.z + dz) != goalInside) return emptyList()

        return gates.sortedBy { gate -> sqrt(from.distanceSquared(gate.entrance.point())) +
            sqrt(target.point().distanceSquared(gate.entrance.point())) }.flatMap { gate ->
            val box = gate.bounds
            buildList {
                fun exit(transit: CivPosition, x: Int, z: Int) {
                    val approach = transit.copy(x = x, z = z)
                    if (interior.contains(x, z) == goalInside && allows(approach, emptySet(), null) && controls.clear(approach))
                        add(GateExit(gate.id, transit, approach))
                }
                for (p in gate.transit.orEmpty()) {
                    if (!allows(p, emptySet(), null) || !controls.clear(p)) continue
                    if (p.x == box.min.x) exit(p, p.x - 1, p.z)
                    if (p.x == box.max.x) exit(p, p.x + 1, p.z)
                    if (p.z == box.min.z) exit(p, p.x, p.z - 1)
                    if (p.z == box.max.z) exit(p, p.x, p.z + 1)
                }
            }.sortedBy { it.approach.point().distanceSquared(gate.entrance.point()) }
        }
    }

    private companion object {
        fun CivBox.coversColumn(x: Int, z: Int): Boolean = x in min.x..max.x && z in min.z..max.z
        fun columnKey(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xffff_ffffL)
    }
}

private fun retainedGateInterior(walls: Set<CivBox>) = lazy { GateInterior(walls) }

/** Only a wall or gate edge can change connectivity. A strip of any length is one cell. */
private class GateInterior(private val walls: Set<CivBox>) {
    private fun cuts(start: (CivBox) -> Int, end: (CivBox) -> Int): LongArray {
        val edges = sortedSetOf<Long>()
        for (wall in walls) {
            edges.add(start(wall).toLong())
            edges.add(end(wall).toLong() + 1)
        }
        edges.add(edges.first() - 1)
        edges.add(edges.last() + 1)
        return edges.toLongArray()
    }

    private val xs = cuts({ it.min.x }, { it.max.x })
    private val zs = cuts({ it.min.z }, { it.max.z })
    private val width = xs.lastIndex
    private val height = zs.lastIndex
    private val outside = BooleanArray(width * height)

    init {
        for (wall in walls) {
            val left = xs.binarySearch(wall.min.x.toLong())
            val right = xs.binarySearch(wall.max.x.toLong() + 1)
            for (z in zs.binarySearch(wall.min.z.toLong()) until zs.binarySearch(wall.max.z.toLong() + 1))
                outside.fill(true, z * width + left, z * width + right)
        }
        val queue = IntArray(outside.size)
        var head = 0
        var tail = 0
        fun visit(cell: Int) {
            if (!outside[cell]) {
                outside[cell] = true
                queue[tail++] = cell
            }
        }
        visit(0) // The one-cell border connects the entire exterior, including gaps between walls.
        while (head < tail) {
            val cell = queue[head++]
            val x = cell % width
            if (x > 0) visit(cell - 1)
            if (x + 1 < width) visit(cell + 1)
            if (cell >= width) visit(cell - width)
            if (cell + width < outside.size) visit(cell + width)
        }
    }

    fun contains(x: Int, z: Int): Boolean {
        fun interval(edges: LongArray, value: Int): Int {
            val index = edges.binarySearch(value.toLong())
            return if (index >= 0) index else -index - 2
        }
        val col = interval(xs, x)
        val row = interval(zs, z)
        return col in 0 until width && row in 0 until height && !outside[row * width + col]
    }
}
