package ru.arc.civs

import java.util.UUID

/** Read-only access evidence. Ordinary building blocks and arbitrary terrain before-images are not retained. */
internal data class CivRetainedPlanSource(
    val id: UUID,
    val cityId: UUID,
    val worldId: UUID,
    val building: CivBuilding,
    val capturedSha256: String?,
    val adminPlacement: Boolean,
    val route: List<CivPosition>?,
    val changedColumns: Set<Long>,
    val pavement: List<CivPlanBlock>,
    val infrastructureBlocks: List<CivSettlementBlock>,
    val waterFoundations: List<CivWaterFoundation>,
    val paidGateFloors: List<CivPosition>,
    val paidWaterFloors: List<CivPosition>,
) {
    companion object {
        fun from(plan: CivPlacementPlan): CivRetainedPlanSource {
            val terrain = plan.terrain
            val route = terrain?.route
            val infrastructure = plan.building.type.isInfrastructure
            return CivRetainedPlanSource(plan.id, plan.cityId, plan.worldId,
                plan.building.copy(transit = plan.building.transit?.let { java.util.List.copyOf(it) }),
                plan.capturedSha256, plan.adminPlacement, route?.let { java.util.List.copyOf(it) },
                if (plan.building.type == BuildingType.WALL) java.util.Set.copyOf(terrain?.blocks.orEmpty()
                    .asSequence().filter { it.before != it.after }.map { civsTerrainKey(it.position.x, it.position.z) }.toSet())
                else emptySet(),
                if (route == null) emptyList() else java.util.List.copyOf(terrain?.blocks.orEmpty().filter {
                    it.before != it.after && it.after in CivsWallApproachCleanup.FINISHES &&
                        CivsAutonomousTerraform.roadProfileContains(route, it.position)
                }),
                if (infrastructure) java.util.List.copyOf(plan.blocks.map { CivSettlementBlock(it.position, it.after) }) else emptyList(),
                if (infrastructure) java.util.List.copyOf(terrain?.waterFoundations.orEmpty()) else emptyList(),
                if (infrastructure) java.util.List.copyOf(terrain?.paidGateFloors.orEmpty()) else emptyList(),
                if (infrastructure) java.util.List.copyOf(terrain?.paidWaterFloors.orEmpty()) else emptyList())
        }
    }
}
