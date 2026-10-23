package ru.arc.civs

import java.util.UUID

/** An inverse of one retained native earthwork write, never a new construction or resource receipt. */
internal data class CivAutonomousAccessRepair(
    val protectedBuildingId: UUID,
    val offendingBuildingId: UUID,
    val standing: CivPosition,
    val cell: CivPlanBlock,
)

/** Earlier completed approaches win; ambiguous or excessive historical edits are left untouched. */
internal fun civsAutonomousAccessRepairs(city: Settlement, plans: List<CivPlacementPlan>): List<CivAutonomousAccessRepair> {
    if (city.autonomy == null || city.pendingDeletion != null) return emptyList()
    val access = CivAutonomousAccess.from(city, plans)
    val retained = plans.associateBy { it.building.id }
    val older = linkedMapOf<Long, Pair<UUID, CivPosition>>()
    val repairs = linkedMapOf<CivPosition, CivAutonomousAccessRepair>()
    val ambiguous = mutableSetOf<CivPosition>()
    fun air(data: String) = data in setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air")
    city.buildings.filter { it.phase == BuildingPhase.ACTIVE }.forEach { building ->
        // from() has checked the retained journal's identity, world, geometry and bounded route.
        if (building.id in access.routes && city.construction?.buildingId != building.id) {
            retained.getValue(building.id).terrain!!.blocks.forEach cells@ { original ->
                val (owner, feet) = older[civsTerrainKey(original.position.x, original.position.z)] ?: return@cells
                if (city.construction?.buildingId == owner || original.position in ambiguous) return@cells
                val y = original.position.y
                val restoresAccess = if (y == feet.y - 1) !air(original.before) && air(original.after)
                    else y in feet.y..feet.y + 1 && air(original.before) && !air(original.after)
                if (!restoresAccess) return@cells
                val inverse = CivAutonomousAccessRepair(owner, building.id, feet,
                    original.copy(before = original.after, after = original.before))
                val previous = repairs[original.position]
                if (previous != null && previous.cell != inverse.cell) {
                    repairs.remove(original.position)
                    ambiguous += original.position
                } else repairs.putIfAbsent(original.position, inverse)
            }
        }
        (CivsAutonomousGeometry.approach(building) + access.routes[building.id].orEmpty()).forEach { feet ->
            older.putIfAbsent(civsTerrainKey(feet.x, feet.z), building.id to feet)
        }
    }
    if (repairs.size > 512) throw CivsRejection("autonomy-access-repair-budget")
    return repairs.values.toList()
}
