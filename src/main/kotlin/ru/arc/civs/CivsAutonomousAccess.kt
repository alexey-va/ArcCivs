package ru.arc.civs

import java.util.UUID
import kotlin.math.abs

/** Retained completed approaches; this is a detached view of existing journals, not a second registry. */
internal data class CivAutonomousAccess(val routes: Map<UUID, List<CivPosition>> = emptyMap(),
    val infrastructure: List<CivSettlementPiece> = emptyList(),
    val acceptedFortifications: Set<UUID> = emptySet(),
    val reservedFortificationPorts: List<CivBox> = emptyList(),
    val paidHallLeadOut: List<CivPosition> = emptyList(),
    val wallApproachWrites: Map<UUID, Set<Long>> = emptyMap(),
    val perimeterCaptures: Map<UUID, CivPerimeterCapture> = emptyMap(),
    private val surveyedHallApron: Map<Long, CivPosition> = emptyMap()) {
    /** Only the source's paid terminal and reserved approach may be regraded together. */
    fun entrancePath(city: Settlement, source: CivBuilding, route: List<CivPosition>): CivsEntrancePath? {
        val path = CivsEntrancePath.from(source, route) ?: return null
        val cells = path.floors
        if (cells.any { !city.contains(it.x, it.z) } ||
            infrastructure.any { piece -> piece.transit.any { path.contains(it.x, it.z) } } ||
            reservedFortificationPorts.any { box -> cells.any { CivsAutonomousGeometry.horizontalContains(box, it) } }) return null
        for (building in civsProtectedBuildings(city)) {
            val bounds = CivsAutonomousGeometry.futureBounds(city, building)
            if (cells.any { CivsAutonomousGeometry.horizontalContains(bounds, it) || CivsWorksiteLayouts.isField(building, it.x, it.z) })
                return null
            if (building.id == source.id) continue
            if (CivsAutonomousGeometry.approach(building).any { path.contains(it.x, it.z) }) return null
            val other = routes[building.id] ?: CivsSettlementPlans.current(city)?.routes?.get(building.id.toString()) ?: continue
            if (other.any { feet -> (-1..1).any { dx -> (-1..1).any { dz -> path.contains(feet.x + dx, feet.z + dz) } } })
                return null
        }
        return path
    }

    fun forecourtClear(city: Settlement, source: CivBuilding, floor: CivPosition): Boolean {
        if (!city.contains(floor.x, floor.z) || reservedFortificationPorts.any { CivsAutonomousGeometry.horizontalContains(it, floor) } ||
            infrastructure.any { piece -> piece.transit.any { it.x == floor.x && it.z == floor.z } }) return false
        return civsProtectedBuildings(city).none { building ->
            val other = building.id != source.id
            CivsWorksiteLayouts.isField(building, floor.x, floor.z) ||
                building.control?.let { it.x == floor.x && it.z == floor.z } == true ||
                building != source && CivsAutonomousGeometry.horizontalContains(CivsAutonomousGeometry.futureBounds(city, building), floor) ||
                other && (routes[building.id] ?: CivsSettlementPlans.current(city)?.routes?.get(building.id.toString())).orEmpty()
                    .any { abs(it.x - floor.x) <= 1 && abs(it.z - floor.z) <= 1 }
        }
    }

    /** A paid route and the fresh surface can correct the Hall's nominal apron grade. */
    fun withSurveyedHallApron(city: Settlement, surface: CivTerrainSurface): CivAutonomousAccess {
        val hall = city.buildings.singleOrNull { it.type == BuildingType.TOWN_HALL && it.phase == BuildingPhase.ACTIVE }
            ?: return this
        val fixed = city.buildings.asSequence().filter { it.phase == BuildingPhase.ACTIVE }
            .flatMap { sequenceOf(it.entrance) + it.transit.orEmpty().asSequence() }
            .mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }
        val apron = CivsAutonomousGeometry.approach(hall).associateBy { civsTerrainKey(it.x, it.z) } - fixed
        val paid = routes.filterKeys { city.building(it)?.phase == BuildingPhase.ACTIVE }
        val candidates = paid.values.asSequence().flatten().filter { civsTerrainKey(it.x, it.z) in apron }
            .groupBy { civsTerrainKey(it.x, it.z) }
        val grades = buildMap {
            for ((key, points) in candidates) {
                val nominal = apron.getValue(key)
                val observed = surface.columns[key] ?: continue
                val point = points.distinct().singleOrNull() ?: continue
                if (abs(point.y.toLong() - nominal.y) == 1L &&
                    observed.loaded && (observed.natural || observed.dirtPath) && observed.waterTopY == null &&
                    observed.groundY == point.y - 1 && observed.topY == observed.groundY) put(key, point)
            }
        }
        return copy(surveyedHallApron = grades)
    }

    /** Preserve the full public surface when a civic parcel must borrow an existing street. */
    fun roadColumns(city: Settlement): Set<Long> = buildSet {
        routes.values.forEach { addAll(CivsAutonomousTerraform.roadProfile(it).keys) }
        // A missing older journal cannot license writing over the approach still in the
        // adopted plan. This reserves columns only; it supplies no grade or collision proof.
        val plan = CivsSettlementPlans.current(city)
        city.buildings.filter { it.phase == BuildingPhase.ACTIVE && it.id !in routes }.forEach { building ->
            plan?.routes?.get(building.id.toString())?.let { addAll(CivsAutonomousTerraform.roadProfile(it).keys) }
        }
        infrastructure.filter { it.type == BuildingType.ROAD || it.type == BuildingType.GATE }.forEach { piece ->
            piece.transit.forEach { add(civsTerrainKey(it.x, it.z)) }
        }
    }

    /** Earlier roads win when an older release recorded incompatible heights at the same column. */
    fun standing(city: Settlement, exceptBuildingId: UUID): Map<Long, CivPosition> = buildMap {
        require(city.buildings.size <= CivsRules.MAX_SITES)
        city.buildings.filter { it.id != exceptBuildingId }.forEach { building ->
            // A pinned SITE prepares only its entrance column; its wider terrace does not exist yet.
            val approach = if (building.phase == BuildingPhase.ACTIVE) CivsAutonomousGeometry.approach(building)
                else listOf(building.entrance)
            // This is a reservation grade; preserved native capture still has to prove its actual support and clearance.
            val route = routes[building.id]?.takeIf { building.phase == BuildingPhase.ACTIVE && !building.type.isInfrastructure }
            val path = route?.let { entrancePath(city, building, it) }
            val paidApproach = if (route == null) approach else approach.map { nominal ->
                (path?.floor(nominal.x, nominal.z) ?: CivsAutonomousTerraform.approachFloor(route, nominal))
                    ?.let { it.copy(y = it.y + 1) } ?: nominal }
            val graded = if (building.type == BuildingType.TOWN_HALL && building.phase == BuildingPhase.ACTIVE) {
                val leadOut = paidHallLeadOut.takeIf { it.firstOrNull() == building.entrance }.orEmpty()
                paidApproach.map { nominal -> surveyedHallApron[civsTerrainKey(nominal.x, nominal.z)] ?:
                    leadOut.firstOrNull { it.x == nominal.x && it.z == nominal.z } ?: nominal }
            } else paidApproach
            (graded + (if (path != null) path.travel(requireNotNull(route)) else routes[building.id].orEmpty()) +
                building.transit.orEmpty().takeIf { building.phase == BuildingPhase.ACTIVE }.orEmpty()).forEach { feet ->
                putIfAbsent(civsTerrainKey(feet.x, feet.z), feet)
            }
        }
    }

    companion object {
        fun from(city: Settlement, plans: List<CivPlacementPlan>): CivAutonomousAccess {
            require(city.buildings.size <= CivsRules.MAX_SITES && plans.size <= CivsRules.MAX_SITES)
            val retained = plans.associateBy { it.building.id }
            val adopted = CivsSettlementPlans.current(city)
            val fortifications = adopted?.plots.orEmpty().mapNotNull { plot ->
                val metadata = plot.fortification ?: return@mapNotNull null
                val actual = city.building(plot.building.id) ?: return@mapNotNull null
                val plan = retained[actual.id] ?: return@mapNotNull null
                actual.id.takeIf { actual.phase == BuildingPhase.ACTIVE && plan.id == actual.id && plan.cityId == city.id && plan.worldId == city.worldId &&
                    plan.capturedSha256 == metadata.sourceSha256 &&
                    CivsSettlementPlans.sameFortificationGeometry(actual, plot.building) &&
                    CivsSettlementPlans.sameFortificationGeometry(plan.building, plot.building) }
            }.toSet()
            val routes = buildMap {
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
            }
            val wallApproachWrites = if (adopted?.deferredPerimeter == null) emptyMap() else buildMap {
                for (wall in city.buildings.filter { it.type == BuildingType.WALL && it.id in routes }) {
                    val plan = retained.getValue(wall.id)
                    val terrain = requireNotNull(plan.terrain)
                    // A profile refit retains the route but its new capture omits the original earthworks.
                    if (plan.id != wall.id || plan.id != CivsGateRefit.retainedPlanId(wall) ||
                        plan.building.copy(phase = BuildingPhase.ACTIVE) != wall || plan.capturedSha256 == null ||
                        terrain.route != routes[wall.id]) continue
                    put(wall.id, terrain.blocks.asSequence().filter { it.before != it.after }
                        .mapTo(hashSetOf()) { civsTerrainKey(it.position.x, it.position.z) })
                }
            }
            val perimeterCaptures = adopted?.perimeterEntrances.orEmpty().mapNotNull { (id, stance) ->
                val building = city.building(UUID.fromString(id)) ?: return@mapNotNull null
                val plan = retained[building.id] ?: return@mapNotNull null
                val sha = plan.capturedSha256 ?: return@mapNotNull null
                val capture = CivPerimeterCapture(plan.id, sha)
                if (building.phase != BuildingPhase.ACTIVE || building.entrance != stance ||
                    building.type !in setOf(BuildingType.WALL, BuildingType.GATE) ||
                    CivsInfrastructureRebuild.version(building) != 5 || CivsInfrastructureRebuild.stage(building) == 0 ||
                    plan.id != CivsGateRefit.retainedPlanId(building) || plan.cityId != city.id || plan.worldId != city.worldId ||
                    plan.adminPlacement || plan.building.copy(phase = BuildingPhase.ACTIVE) != building || !capture.valid())
                    return@mapNotNull null
                building.id to capture
            }.toMap()
            return CivAutonomousAccess(routes, CivsSettlementLayout.accepted(city, plans), fortifications,
                adopted?.plots.orEmpty().flatMap { plot ->
                    plot.fortification?.let { CivsSettlementPlans.portReservations(it.proof) }.orEmpty()
                }, paidHallLeadOut(city, routes), wallApproachWrites, perimeterCaptures)
        }

        /** Only an exact, corroborated paid centreline can replace the Hall's synthetic flat apron. */
        private fun paidHallLeadOut(city: Settlement, routes: Map<UUID, List<CivPosition>>): List<CivPosition> {
            val hall = city.buildings.singleOrNull { it.type == BuildingType.TOWN_HALL && it.phase == BuildingPhase.ACTIVE }
                ?: return emptyList()
            val hallFeet = hall.entrance
            val alongZ = hall.rotation in listOf(0, 180)
            val centre = CivsAutonomousGeometry.approach(hall).filter {
                if (alongZ) it.x == hallFeet.x else it.z == hallFeet.z
            }
            val prefixes = routes.filterKeys { id -> id != hall.id && city.building(id)?.type?.isInfrastructure == false }
                .values.filter { route -> route.firstOrNull() == hallFeet && route.size >= centre.size }
                .map { it.take(centre.size) }.filter { path ->
                    path.map { it.copy(y = hallFeet.y) } == centre && path.all { abs(it.y.toLong() - hallFeet.y) <= 1L }
                }
            if (prefixes.size < 2) return emptyList()
            val selected = prefixes.first()
            val grades = selected.associate { civsTerrainKey(it.x, it.z) to it.y }
            if (routes.values.any { path -> path.any { p -> grades[civsTerrainKey(p.x, p.z)]?.let { it != p.y } == true } })
                return emptyList()
            return selected
        }
    }
}
