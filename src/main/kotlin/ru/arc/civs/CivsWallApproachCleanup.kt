package ru.arc.civs

import java.util.UUID

/** An exact flat surface from a completed infrastructure construction approach. */
data class CivWallApproachCleanup(val captureId: UUID, val route: List<CivPosition>, val authored: CivPlanBlock,
    val owners: List<CivRoadJunctionRoute>, val layoutKey: String,
    val sharedRoute: CivRoadRouteOverride? = null) {
    internal fun detached() = copy(route = route.toList(), owners = owners.map {
        it.copy(source = it.source.copy(transit = it.source.transit?.toList()), route = it.route.toList())
    }, sharedRoute = sharedRoute?.detached())

    internal fun keptColumns(city: Settlement, source: CivBuilding): Set<Long> {
        val routes = owners.associate { it.source.id to it.route }
        sharedRoute?.let { repair ->
            val access = CivAutonomousAccess(routes = routes)
            return access.roadColumns(city) + access.standing(city, source.id).keys +
                CivsAutonomousGeometry.approach(source).map { civsTerrainKey(it.x, it.z) } +
                city.buildings.flatMap { it.transit.orEmpty() }.map { civsTerrainKey(it.x, it.z) } +
                requireNotNull(CivsGateSharedApproaches.proof(repair)).hallFloors.map { civsTerrainKey(it.x, it.z) }
        }
        val retired = CivsWallApproachCleanup.retired(city, routes)
        val access = CivAutonomousAccess(routes = routes.filterKeys { it !in retired })
        // Keep every permanent route and transit, including non-retirable ROAD approaches. Raw
        // captures remain available as witnesses; retirement never deletes them or their entries.
        val publicCity = city.copy(buildings = city.buildings.filterNot { it.id in retired })
        return access.roadColumns(publicCity) + access.standing(city, source.id).keys +
            CivsAutonomousGeometry.approach(source).map { civsTerrainKey(it.x, it.z) } +
            city.buildings.flatMap { it.transit.orEmpty() }.map { civsTerrainKey(it.x, it.z) }
    }

    internal fun validated(city: Settlement, upkeep: CivRoadUpkeep) {
        require(city.construction == null && city.constructionQueue.isNullOrEmpty())
        require(captureId == CivsWarehouseRefit.retainedPlanId(upkeep.source))
        val before = if (sharedRoute != null && upkeep.routeOverride != null) city.copy(
            roadRouteOverrides = city.roadRouteOverrides?.minus(upkeep.source.id.toString())?.takeIf { it.isNotEmpty() }) else city
        require(layoutKey == civsPlanBoardLayoutKey(before) && route == upkeep.route && route.lastOrNull() == upkeep.source.entrance)
        require(route.size in 1..CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH &&
            CivsAutonomousTerraform.roadProfileContains(route, authored.position))
        require(authored.position == upkeep.floor && authored.before != authored.after &&
            authored.after in CivsWallApproachCleanup.FINISHES && upkeep.after == CivsEntrancePath.GRASS)
        require((upkeep.routeOverride == null || sharedRoute != null && upkeep.routeOverride == sharedRoute) &&
            upkeep.linkedRouteOverrides == null && upkeep.previousRouteOverrides == null &&
            upkeep.authoredAfter == null && upkeep.junction == null && upkeep.hall == null && upkeep.neighbours.isEmpty())
        require(owners.size <= CivsRules.MAX_SITES && owners.map { it.source.id }.distinct().size == owners.size)
        owners.forEach { owner ->
            require(city.building(owner.source.id) == owner.source && !CivsWallApproachCleanup.completed(city, owner.source))
            require(owner.route.size in 1..CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH && owner.route.last() == owner.source.entrance)
            city.roadRouteOverrides?.get(owner.source.id.toString())?.let { require(it.route == owner.route) }
        }
        val routes = owners.associate { it.source.id to it.route }
        if (sharedRoute == null) {
            require(upkeep.source.id in CivsWallApproachCleanup.retired(city, routes))
            if (upkeep.source.type == BuildingType.ROAD) require(routes[upkeep.source.id] == route)
        } else {
            val proof = requireNotNull(CivsGateSharedApproaches.proof(sharedRoute))
            sharedRoute.validated(city, upkeep.source)
            require(CivsGateSharedApproaches.currentOwners(city, sharedRoute) && proof.source == upkeep.source &&
                captureId == proof.sourceCapture.id && route == sharedRoute.originalRoute &&
                routes[upkeep.source.id] == sharedRoute.route && routes[proof.donor.id] == proof.donorRoute &&
                (upkeep.routeOverride == sharedRoute || city.roadRouteOverrides?.get(upkeep.source.id.toString()) == sharedRoute))
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

    /** ROAD approaches cannot justify each other's removal. Every transit floor must already
     * belong to an ordinary building or GATE's exact paid profile, including its actual height. */
    internal fun retired(city: Settlement, routes: Map<UUID, List<CivPosition>>): Set<UUID> {
        val walls = city.buildings.filter { completed(city, it) }.mapTo(hashSetOf()) { it.id }
        val roads = city.buildings.filter { building -> building.type == BuildingType.ROAD &&
            building.phase == BuildingPhase.ACTIVE && building.id in routes &&
            building.schematicId == "civs/infrastructure/v1/road/${building.id}" &&
            city.construction?.buildingId != building.id && building.id !in city.constructionQueue.orEmpty() &&
            city.pendingControls.orEmpty().none { it.buildingId == building.id } &&
            !building.transit.isNullOrEmpty() }
        if (roads.isEmpty() || !claimsComplete(city, routes.keys)) return walls
        val permanent = routes.asSequence().filter { (id, _) -> city.building(id)?.let {
            it.phase == BuildingPhase.ACTIVE && (!it.type.isInfrastructure || it.type == BuildingType.GATE)
        } == true }.flatMap { (_, route) -> CivsAutonomousTerraform.roadProfile(route).values.asSequence() }.toHashSet()
        return roads.filter { building -> building.transit!!.all { it.copy(y = it.y - 1) in permanent } }.mapTo(walls) { it.id }
    }

    internal fun claimsComplete(city: Settlement, retained: Set<UUID>): Boolean {
        val planned = CivsSettlementPlans.current(city)?.routes.orEmpty()
        return claimsComplete(city, retained) { planned[it.toString()] }
    }

    internal fun claimsComplete(city: Settlement, retained: Set<UUID>, adoptedRoute: (UUID) -> List<CivPosition>?): Boolean =
        city.buildings.none { it.phase == BuildingPhase.ACTIVE && !completed(city, it) &&
            it.type != BuildingType.TOWN_HALL && it.id !in retained &&
            adoptedRoute(it.id)?.lastOrNull() != it.entrance }

    fun captures(city: Settlement, plans: Map<UUID, CivRetainedPlanSource>, routes: Map<UUID, List<CivPosition>>): Map<UUID, CivWallApproachCapture> {
        val retired = retired(city, routes)
        return city.buildings.filter { it.id in retired }.mapNotNull { source ->
            val plan = plans[source.id] ?: return@mapNotNull null
            val route = routes[source.id] ?: return@mapNotNull null
            if (plan.id != CivsWarehouseRefit.retainedPlanId(source) || plan.adminPlacement ||
                plan.cityId != city.id || plan.worldId != city.worldId || plan.building.copy(phase = BuildingPhase.ACTIVE) != source ||
                plan.route != route) return@mapNotNull null
            val pavement = plan.pavement
            if (pavement.isEmpty()) null else source.id to CivWallApproachCapture(plan.id, route.toList(), pavement.toList())
        }.toMap()
    }

    fun targets(city: Settlement, access: CivAutonomousAccess): List<CivRoadUpkeep> {
        if (access.wallPavement.isEmpty() || city.construction != null || !city.constructionQueue.isNullOrEmpty() ||
            !claimsComplete(city, access.routes.keys)) return emptyList()
        val owners = access.routes.mapNotNull { (id, route) ->
            city.building(id)?.takeUnless { completed(city, it) }?.let { CivRoadJunctionRoute(it, route) }
        }
        val key = civsPlanBoardLayoutKey(city)
        val retired = retired(city, access.routes)
        val protected = civsProtectedBuildings(city).associateWith { CivsAutonomousGeometry.futureBounds(city, it) }
        val columns = hashSetOf<Long>()
        return access.wallPavement.flatMap { (id, capture) ->
            val source = city.building(id)?.takeIf { it.id in retired } ?: return@flatMap emptyList()
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

    /** A partial shared approach never retires its gate, transit or entry. */
    fun sharedTargets(city: Settlement, access: CivAutonomousAccess, repair: CivRoadRouteOverride): List<CivRoadUpkeep> {
        val proof = CivsGateSharedApproaches.proof(repair) ?: return emptyList()
        if (!CivsGateSharedApproaches.current(city, repair, access) || !claimsComplete(city, access.routes.keys)) return emptyList()
        val capture = access.sharedRoadCaptures[proof.source.id] ?: return emptyList()
        val owners = access.routes.mapNotNull { (id, route) ->
            city.building(id)?.takeUnless { completed(city, it) }?.let {
                CivRoadJunctionRoute(it, if (id == proof.source.id) repair.route else route)
            }
        }
        val base = CivWallApproachCleanup(capture.capture.id, capture.route, capture.pavement.firstOrNull() ?: return emptyList(),
            owners, civsPlanBoardLayoutKey(city), repair)
        val kept = base.keptColumns(city, proof.source)
        val protected = civsProtectedBuildings(city).associateWith { CivsAutonomousGeometry.futureBounds(city, it) }
        val install = repair.takeIf { city.roadRouteOverrides?.containsKey(proof.source.id.toString()) != true }
        return capture.pavement.mapNotNull { authored ->
            val floor = authored.position
            if (civsTerrainKey(floor.x, floor.z) in kept || !city.contains(floor.x, floor.z) ||
                protected.any { (building, bounds) -> CivsAutonomousGeometry.horizontalContains(bounds, floor) ||
                    CivsWorksiteLayouts.isField(building, floor.x, floor.z) ||
                    building.control?.let { it.x == floor.x && it.z == floor.z } == true } ||
                access.reservedFortificationPorts.any { CivsAutonomousGeometry.horizontalContains(it, floor) }) return@mapNotNull null
            CivRoadUpkeep(city.id, city.worldId, city.owner, proof.source.id, proof.source, floor, CivsEntrancePath.GRASS,
                capture.route, emptyList(), routeOverride = install, wallCleanup = base.copy(authored = authored))
                .also { it.validated(city) }
        }
    }
}
