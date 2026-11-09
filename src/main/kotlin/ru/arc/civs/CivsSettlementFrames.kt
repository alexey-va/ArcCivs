package ru.arc.civs

import java.util.UUID

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

    fun territory(city: Settlement): CivRouteTerritory = territories[city.id]?.takeIf { it.matches(city) }
        ?: CivRouteTerritory(city).also { territories[city.id] = it }

    fun remove(cityId: UUID) { territories.remove(cityId) }
    fun clear() { territories.clear() }
}

/** Detached plot and furniture geometry, shared until the accepted layout changes. */
internal class CivRouteTerritory(city: Settlement) {
    private class Plot(val building: CivBuilding) {
        val bounds = building.bounds
        val field = CivsWorksiteLayouts.fieldBounds(building)
        val public = civsPublicTransit(building)

        fun covers(x: Int, z: Int): Boolean = bounds.coversColumn(x, z) ||
            field?.coversColumn(x, z) == true && CivsWorksiteLayouts.isField(building, x, z)

        fun blocks(point: CivPosition, allowed: Set<UUID>, mineBottom: Int?): Boolean =
            building.id !in allowed && covers(point.x, point.z) &&
                (!public || !civsPublicTransit(building, point.x, point.z)) &&
                (mineBottom == null || point.y >= mineBottom || !CivsMinePlan.belowFoundation(building, point.y, headroom = 1))
    }

    private val world = city.worldId
    private val center = city.center
    private val radius = city.radius
    private val buildings = city.buildings
    private val planned = city.construction?.plannedBuilding
    private val chunks = hashMapOf<Long, MutableList<Plot>>()
    private val large = mutableListOf<Plot>()
    val controls = CitizenRouteControls(city)

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
        buildings == city.buildings && planned == city.construction?.plannedBuilding

    fun onPlot(point: CivPosition): Boolean =
        chunks[columnKey(point.x shr 4, point.z shr 4)]?.any { it.covers(point.x, point.z) } == true ||
            large.any { it.covers(point.x, point.z) }

    fun allows(point: CivPosition, allowed: Set<UUID>, mineBottom: Int?): Boolean =
        kotlin.math.abs(point.x.toLong() - center.x) <= radius && kotlin.math.abs(point.z.toLong() - center.z) <= radius &&
            chunks[columnKey(point.x shr 4, point.z shr 4)]?.none { it.blocks(point, allowed, mineBottom) } != false &&
            large.none { it.blocks(point, allowed, mineBottom) }

    private companion object {
        fun CivBox.coversColumn(x: Int, z: Int): Boolean = x in min.x..max.x && z in min.z..max.z
        fun columnKey(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xffff_ffffL)
    }
}
