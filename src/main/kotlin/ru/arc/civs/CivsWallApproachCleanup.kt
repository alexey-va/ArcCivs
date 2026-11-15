package ru.arc.civs

import java.util.UUID

/** An exact flat surface written by a completed wall's retained construction capture. */
data class CivWallApproachCleanup(val captureId: UUID, val route: List<CivPosition>, val authored: CivPlanBlock,
    val owners: List<CivRoadJunctionRoute>, val layoutKey: String) {
    internal fun detached() = copy(route = route.toList(), owners = owners.map {
        it.copy(source = it.source.copy(transit = it.source.transit?.toList()), route = it.route.toList())
    })

    internal fun keptColumns(city: Settlement, source: CivBuilding): Set<Long> {
        val access = CivAutonomousAccess(routes = owners.associate { it.source.id to it.route })
        // Completed wall routes remain useful geometric witnesses for future work. Their temporary
        // surface is not a public street; real roads and unfinished work retain their full claims.
        val publicCity = city.copy(buildings = city.buildings.filterNot { CivsWallApproachCleanup.completed(city, it) })
        return access.roadColumns(publicCity) + access.standing(city, source.id).keys +
            CivsAutonomousGeometry.approach(source).map { civsTerrainKey(it.x, it.z) } +
            city.buildings.flatMap { it.transit.orEmpty() }.map { civsTerrainKey(it.x, it.z) }
    }

    internal fun validated(city: Settlement, upkeep: CivRoadUpkeep) {
        require(city.construction == null && city.constructionQueue.isNullOrEmpty())
        require(CivsWallApproachCleanup.completed(city, upkeep.source) && captureId == CivsWarehouseRefit.retainedPlanId(upkeep.source))
        require(layoutKey == civsPlanBoardLayoutKey(city) && route == upkeep.route && route.lastOrNull() == upkeep.source.entrance)
        require(route.size in 1..CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH &&
            CivsAutonomousTerraform.roadProfileContains(route, authored.position))
        require(authored.position == upkeep.floor && authored.before != authored.after &&
            authored.after in CivsWallApproachCleanup.FINISHES && upkeep.after == CivsEntrancePath.GRASS)
        require(upkeep.routeOverride == null && upkeep.linkedRouteOverrides == null && upkeep.previousRouteOverrides == null &&
            upkeep.authoredAfter == null && upkeep.junction == null && upkeep.hall == null && upkeep.neighbours.isEmpty())
        require(owners.size <= CivsRules.MAX_SITES && owners.map { it.source.id }.distinct().size == owners.size)
        owners.forEach { owner ->
            require(city.building(owner.source.id) == owner.source && !CivsWallApproachCleanup.completed(city, owner.source))
            require(owner.route.size in 1..CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH && owner.route.last() == owner.source.entrance)
            city.roadRouteOverrides?.get(owner.source.id.toString())?.let { require(it.route == owner.route) }
        }
        require(CivsWallApproachCleanup.claimsComplete(city, owners.mapTo(hashSetOf()) { it.source.id }))
        require(civsTerrainKey(upkeep.floor.x, upkeep.floor.z) !in keptColumns(city, upkeep.source))
    }
}

internal data class CivWallApproachCapture(val captureId: UUID, val route: List<CivPosition>, val pavement: List<CivPlanBlock>)

internal object CivsWallApproachCleanup {
    val FINISHES = setOf("minecraft:coarse_dirt", "minecraft:dirt_path")

    fun completed(city: Settlement, building: CivBuilding): Boolean = building.type == BuildingType.WALL &&
        building.phase == BuildingPhase.ACTIVE && city.construction?.buildingId != building.id &&
        building.id !in city.constructionQueue.orEmpty() && city.pendingControls.orEmpty().none { it.buildingId == building.id }

    internal fun claimsComplete(city: Settlement, retained: Set<UUID>): Boolean {
        val planned = CivsSettlementPlans.current(city)?.routes.orEmpty()
        return city.buildings.none { it.phase == BuildingPhase.ACTIVE && !completed(city, it) &&
            it.type != BuildingType.TOWN_HALL && it.type != BuildingType.ROAD && it.id !in retained &&
            it.id.toString() !in planned }
    }

    fun captures(city: Settlement, plans: Map<UUID, CivPlacementPlan>, routes: Map<UUID, List<CivPosition>>): Map<UUID, CivWallApproachCapture> =
        city.buildings.filter { completed(city, it) }.mapNotNull { source ->
            val plan = plans[source.id] ?: return@mapNotNull null
            val route = routes[source.id] ?: return@mapNotNull null
            if (plan.id != CivsWarehouseRefit.retainedPlanId(source) || plan.adminPlacement ||
                plan.cityId != city.id || plan.worldId != city.worldId || plan.building.copy(phase = BuildingPhase.ACTIVE) != source ||
                plan.terrain?.route != route) return@mapNotNull null
            val pavement = plan.terrain.blocks.filter { it.before != it.after && it.after in FINISHES &&
                CivsAutonomousTerraform.roadProfileContains(route, it.position) }
            if (pavement.isEmpty()) null else source.id to CivWallApproachCapture(plan.id, route.toList(), pavement.toList())
        }.toMap()

    fun targets(city: Settlement, access: CivAutonomousAccess): List<CivRoadUpkeep> {
        if (access.wallPavement.isEmpty() || city.construction != null || !city.constructionQueue.isNullOrEmpty() ||
            !claimsComplete(city, access.routes.keys)) return emptyList()
        val owners = access.routes.mapNotNull { (id, route) ->
            city.building(id)?.takeUnless { completed(city, it) }?.let { CivRoadJunctionRoute(it, route) }
        }
        val key = civsPlanBoardLayoutKey(city)
        val protected = civsProtectedBuildings(city).associateWith { CivsAutonomousGeometry.futureBounds(city, it) }
        val columns = hashSetOf<Long>()
        return access.wallPavement.flatMap { (id, capture) ->
            val source = city.building(id)?.takeIf { completed(city, it) } ?: return@flatMap emptyList()
            val base = CivWallApproachCleanup(capture.captureId, capture.route, capture.pavement.first(), owners, key)
            val kept = base.keptColumns(city, source)
            capture.pavement.mapNotNull { authored ->
                val floor = authored.position
                val column = civsTerrainKey(floor.x, floor.z)
                if (column in kept || column in columns || !city.contains(floor.x, floor.z) ||
                    protected.any { (building, bounds) -> CivsAutonomousGeometry.horizontalContains(bounds, floor) ||
                        CivsWorksiteLayouts.isField(building, floor.x, floor.z) ||
                        building.control?.let { it.x == floor.x && it.z == floor.z } == true } ||
                    access.reservedFortificationPorts.any { CivsAutonomousGeometry.horizontalContains(it, floor) }) return@mapNotNull null
                columns += column
                CivRoadUpkeep(city.id, city.worldId, city.owner, source.id, source, floor, CivsEntrancePath.GRASS,
                    capture.route, emptyList(), wallCleanup = base.copy(authored = authored))
            }
        }
    }
}
