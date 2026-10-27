package ru.arc.civs

import org.bukkit.HeightMap
import org.bukkit.World
import java.util.UUID
import kotlin.math.abs

/** Detached construction geometry; a moving earthwork cursor never owns the builder's feet. */
data class CivConstructionSite(val orderId: UUID, val building: CivBuilding, val changedColumns: Set<Long>,
    val exteriorColumns: List<CivPosition> = citizenConstructionColumns(building, changedColumns))

internal fun civsConstructionSite(plan: CivPlacementPlan): CivConstructionSite = CivConstructionSite(
    plan.id, plan.building, plan.terrain?.blocks.orEmpty().asSequence().filter { it.before != it.after }
        .map { civsTerrainKey(it.position.x, it.position.z) }.toSet())

internal fun citizenConstructionStanding(city: Settlement, world: World, site: CivConstructionSite, point: CivPosition): Boolean =
    world.uid == city.worldId && civsTerrainKey(point.x, point.z) !in site.changedColumns &&
        !CivsAutonomousGeometry.horizontalContains(site.building.bounds, point) &&
        citizenRouteCellAllowed(city, emptySet(), point.x, point.z) && citizenControlClear(city, point) &&
        citizenIdleTerrain(world, point) && citizenStandable(world, point) &&
        citizenPacketBodyClear(world, CitizenPoint(point.x + .5, point.y.toDouble(), point.z + .5), false)

/** Detached geometry only: skip exact future cut/fill columns before the bounded native stance probe. */
internal fun citizenConstructionColumns(building: CivBuilding, changed: Set<Long>): List<CivPosition> {
    val box = building.bounds
    val entrance = building.entrance
    val sides = listOf(
        entrance.copy(x = box.min.x - 1, z = entrance.z.coerceIn(box.min.z, box.max.z)) to (-1 to 0),
        entrance.copy(x = box.max.x + 1, z = entrance.z.coerceIn(box.min.z, box.max.z)) to (1 to 0),
        entrance.copy(x = entrance.x.coerceIn(box.min.x, box.max.x), z = box.min.z - 1) to (0 to -1),
        entrance.copy(x = entrance.x.coerceIn(box.min.x, box.max.x), z = box.max.z + 1) to (0 to 1),
    )
    return sides.flatMap { (start, direction) ->
        // A finite changed-column set cannot block more than its own size along one non-repeating ray.
        (0 until changed.size + 4).asSequence().map { distance ->
            start.copy(x = start.x + direction.first * distance, z = start.z + direction.second * distance)
        }.filter { civsTerrainKey(it.x, it.z) !in changed }.take(4).toList()
    }.sortedBy { abs(it.x.toLong() - entrance.x) + abs(it.z.toLong() - entrance.z) }
}

/** Sixteen precomputed exterior columns; never load chunks or stand on future cut/fill. */
internal fun citizenConstructionTarget(city: Settlement, world: World, site: CivConstructionSite): CivPosition? {
    val entrance = site.building.entrance
    for (column in site.exteriorColumns) {
        if (civsTerrainKey(column.x, column.z) in site.changedColumns ||
            !citizenRouteCellAllowed(city, emptySet(), column.x, column.z) ||
            !citizenChunkReady(world, column.x shr 4, column.z shr 4)) continue
        val feet = column.copy(y = world.getHighestBlockYAt(column.x, column.z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1)
        if (abs(feet.y.toLong() - entrance.y) <= CivsAutonomousTerraform.MAXIMUM_CHANGE &&
            citizenConstructionStanding(city, world, site, feet)) return feet
    }
    return null
}
