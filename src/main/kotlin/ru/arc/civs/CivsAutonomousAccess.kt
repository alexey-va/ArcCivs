package ru.arc.civs

import java.util.UUID
import kotlin.math.abs

/** Retained completed approaches; this is a detached view of existing journals, not a second registry. */
internal data class CivAutonomousAccess(val routes: Map<UUID, List<CivPosition>> = emptyMap(),
    val infrastructure: List<CivSettlementPiece> = emptyList(),
    val acceptedFortifications: Set<UUID> = emptySet(),
    val reservedFortificationPorts: List<CivBox> = emptyList()) {
    /** Earlier roads win when an older release recorded incompatible heights at the same column. */
    fun standing(city: Settlement, exceptBuildingId: UUID): Map<Long, CivPosition> = buildMap {
        require(city.buildings.size <= CivsRules.MAX_SITES)
        city.buildings.filter { it.id != exceptBuildingId }.forEach { building ->
            // A pinned SITE prepares only its entrance column; its wider terrace does not exist yet.
            val approach = if (building.phase == BuildingPhase.ACTIVE) CivsAutonomousGeometry.approach(building)
                else listOf(building.entrance)
            (approach + routes[building.id].orEmpty() +
                building.transit.orEmpty().takeIf { building.phase == BuildingPhase.ACTIVE }.orEmpty()).forEach { feet ->
                putIfAbsent(civsTerrainKey(feet.x, feet.z), feet)
            }
        }
    }

    companion object {
        fun from(city: Settlement, plans: List<CivPlacementPlan>): CivAutonomousAccess {
            require(city.buildings.size <= CivsRules.MAX_SITES && plans.size <= CivsRules.MAX_SITES)
            val retained = plans.associateBy { it.building.id }
            val fortifications = CivsSettlementPlans.current(city)?.plots.orEmpty().mapNotNull { plot ->
                val metadata = plot.fortification ?: return@mapNotNull null
                val actual = city.building(plot.building.id) ?: return@mapNotNull null
                val plan = retained[actual.id] ?: return@mapNotNull null
                actual.id.takeIf { actual.phase == BuildingPhase.ACTIVE && plan.id == actual.id && plan.cityId == city.id && plan.worldId == city.worldId &&
                    plan.capturedSha256 == metadata.sourceSha256 &&
                    CivsSettlementPlans.sameFortificationGeometry(actual, plot.building) &&
                    CivsSettlementPlans.sameFortificationGeometry(plan.building, plot.building) }
            }.toSet()
            return CivAutonomousAccess(buildMap {
                city.buildings.filter { it.phase == BuildingPhase.ACTIVE }.forEach { building ->
                    val plan = retained[building.id] ?: return@forEach
                    val route = plan.terrain?.route ?: return@forEach
                    if (plan.cityId != city.id || plan.worldId != city.worldId || plan.building.type != building.type ||
                        plan.building.anchor != building.anchor || plan.building.rotation != building.rotation ||
                        plan.building.entrance != building.entrance || route.isEmpty() ||
                        route.size > CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH || route.last() != building.entrance ||
                        route.any { !city.contains(it.x, it.z) } ||
                        route.map { civsTerrainKey(it.x, it.z) }.distinct().size != route.size ||
                        route.zipWithNext().any { (a, b) ->
                            abs(a.x.toLong() - b.x) + abs(a.z.toLong() - b.z) != 1L || abs(a.y.toLong() - b.y) > 1L
                        }) return@forEach
                    val override = city.roadRouteOverrides?.get(building.id.toString())
                    if (override != null && override.originalRoute == route) {
                        override.validated(city, building)
                        put(building.id, override.route.toList())
                    } else put(building.id, route.toList())
                }
            }, CivsSettlementLayout.accepted(city, plans), fortifications,
                CivsSettlementPlans.current(city)?.plots.orEmpty().flatMap { plot ->
                    plot.fortification?.let { CivsSettlementPlans.portReservations(it.proof) }.orEmpty()
                })
        }
    }
}
