package ru.arc.civs

import java.util.UUID

/** One missing two-column seam between an existing posted gate and its three-wide wall. */
internal data class CivWallInfillTarget(val gateId: UUID, val wallId: UUID, val footprint: CivBox)

internal object CivsWallInfill {
    fun target(city: Settlement, gateId: UUID, wallId: UUID): CivWallInfillTarget {
        val gate = city.building(gateId) ?: throw CivsRejection("building-not-found")
        val wall = city.building(wallId) ?: throw CivsRejection("building-not-found")
        check(city.style == "caledonia" && city.autonomy != null && city.pendingDeletion == null &&
            gate.phase == BuildingPhase.ACTIVE && wall.phase == BuildingPhase.ACTIVE &&
            CivsGateRefit.validTransit(gate) && wall.type == BuildingType.WALL && wall.level == 1 &&
            wall.schematicId == "civs/infrastructure/v1/wall/${wall.id}" &&
            !CivsSettlementLayout.frame(city).fortified, "wall-infill-unsupported")
        val acrossX = CivsGateRefit.profileAxis(gate.bounds, gate.transit.orEmpty())
            ?: throw CivsRejection("wall-infill-unsupported")
        val g = gate.bounds; val w = wall.bounds
        // The long wall direction follows the gate's width; its thickness occupies the inner three gate rows.
        val crossMin = if (acrossX) w.min.z else w.min.x
        val crossMax = if (acrossX) w.max.z else w.max.x
        check(crossMin == (if (acrossX) g.min.z else g.min.x) + 1 &&
            crossMax == (if (acrossX) g.max.z else g.max.x) - 1, "wall-infill-unsupported")
        val low = if (acrossX) g.min.x else g.min.z
        val high = if (acrossX) g.max.x else g.max.z
        val wallLow = if (acrossX) w.min.x else w.min.z
        val wallHigh = if (acrossX) w.max.x else w.max.z
        check(wallHigh.toLong() - wallLow >= 2, "wall-infill-unsupported")
        val start = when {
            wallLow.toLong() - high == 3L -> high + 1
            low.toLong() - wallHigh == 3L -> wallHigh + 1
            else -> throw CivsRejection("wall-infill-unsupported")
        }
        val box = if (acrossX) CivBox(CivPosition(start, city.center.y, crossMin),
            CivPosition(start + 1, city.center.y, crossMax)) else
            CivBox(CivPosition(crossMin, city.center.y, start), CivPosition(crossMax, city.center.y, start + 1))
        check(city.contains(box.min.x, box.min.z) && city.contains(box.max.x, box.max.z), "outside-city")
        return CivWallInfillTarget(gateId, wallId, box)
    }

    fun prepare(city: Settlement, target: CivWallInfillTarget, surface: CivTerrainSurface,
        access: CivAutonomousAccess, acceptedWork: List<CivPlacementPlan>): Pair<CivSettlementPiece, CivTerrainSurface> {
        check(this.target(city, target.gateId, target.wallId) == target, "stale-building")
        val buildable = buildableSurface(city, target, surface, access, acceptedWork)
        val footprint = target.footprint
        val routes = access.routes.values.toList() + acceptedWork.mapNotNull { it.terrain?.route } +
            CivsSettlementPlans.current(city)?.routes.orEmpty().values
        val wall = requireNotNull(city.building(target.wallId))
        // Keep the original wall axis even though a 3x2 seam is wider than it is long.
        val strip = CivsAutonomousGeometry.union(wall.bounds, footprint)
        val frame = CivsSettlementLayout.frame(city).copy(wallObstacles = listOf(strip), gates = emptyList())
        val generated = CivsSettlementLayout.infrastructure(city, frame, buildable, routes,
            accepted = access.infrastructure, pavingRoutes = emptyList(), retainedRoutes = routes, wallProfileStage = 0)
        val piece = generated.pieces.filter { city.building(it.id) == null }.singleOrNull()
            ?: throw CivsRejection("wall-infill-unavailable", generated.rejected.mapValues { it.value.toString() })
        check(piece.type == BuildingType.WALL && sameColumns(piece.bounds, footprint) &&
            piece.blocks.all { CivsAutonomousGeometry.horizontalContains(footprint, it.position) }, "wall-infill-unsupported")
        val result = piece.copy(id = identity(city, target, piece.bounds))
        CivsRules.placeSite(city, city.owner, CivsInfrastructure.building(result))
        return result to buildable
    }

    /** A queued SITE re-proves the same small seam after restart; no additional persistent registry is needed. */
    fun acceptedSurface(city: Settlement, building: CivBuilding, surface: CivTerrainSurface,
        access: CivAutonomousAccess): CivTerrainSurface {
        if (building.type != BuildingType.WALL || city.building(building.id)?.phase != BuildingPhase.SITE ||
            building.schematicId != "civs/infrastructure/v1/wall/${building.id}") return surface
        val targets = city.buildings.filter { it.type == BuildingType.GATE && it.phase == BuildingPhase.ACTIVE }.flatMap { gate ->
            city.buildings.filter { it.type == BuildingType.WALL && it.phase == BuildingPhase.ACTIVE }.mapNotNull { wall ->
                runCatching { target(city, gate.id, wall.id) }.getOrNull()?.takeIf {
                    sameColumns(it.footprint, building.bounds) && identity(city, it, building.bounds) == building.id
                }
            }
        }
        if (targets.isEmpty()) return surface
        check(targets.size == 1, "wall-infill-unsupported")
        return buildableSurface(city, targets.single(), surface, access, emptyList(), building.id)
    }

    private fun sameColumns(a: CivBox, b: CivBox) = a.min.x == b.min.x && a.max.x == b.max.x &&
        a.min.z == b.min.z && a.max.z == b.max.z

    private fun identity(city: Settlement, target: CivWallInfillTarget, bounds: CivBox) = UUID.nameUUIDFromBytes(
        "${city.id}:wall-infill:1:${target.gateId}:${target.wallId}:$bounds".toByteArray(Charsets.UTF_8))

    private fun buildableSurface(city: Settlement, target: CivWallInfillTarget, surface: CivTerrainSurface,
        access: CivAutonomousAccess, acceptedWork: List<CivPlacementPlan>, except: UUID = UUID(0, 0)): CivTerrainSurface {
        val footprint = target.footprint
        fun inside(p: CivPosition) = CivsAutonomousGeometry.horizontalContains(footprint, p)
        val owned = access.infrastructure.associateBy { it.id }
        check(city.buildings.filter { it.phase == BuildingPhase.ACTIVE && it.type.isInfrastructure }.all {
            owned[it.id]?.let { piece -> piece.bounds == it.bounds && piece.type == it.type } == true
        }, "wall-infill-source-missing")
        check(city.buildings.filter { it.phase == BuildingPhase.ACTIVE && !it.type.isInfrastructure &&
            it.type !in setOf(BuildingType.TOWN_HALL, BuildingType.PLAN_BOARD, BuildingType.DECORATION) }
            .all { it.id in access.routes }, "wall-infill-source-missing")
        val future = CivsSettlementPlans.current(city)?.plots.orEmpty().filter { city.building(it.building.id) == null }
        check((civsProtectedBuildings(city).filter { it.id != except }.map { CivsAutonomousGeometry.futureBounds(city, it) }.toList() +
            future.map { it.envelope } + acceptedWork.map { it.building.bounds }).none {
                CivsAutonomousGeometry.overlaps(footprint, it)
            }, "building-overlap")
        val routes = access.routes.values.toList() + acceptedWork.mapNotNull { it.terrain?.route } +
            CivsSettlementPlans.current(city)?.routes.orEmpty().values
        val protected = access.standing(city, except).values + routes.flatten() +
            acceptedWork.flatMap { it.blocks.map { cell -> cell.position } + it.terrain?.blocks.orEmpty().map { cell -> cell.position } } +
            city.pendingControls.orEmpty().flatMap { listOfNotNull(it.position, it.retire?.position) + it.approach.orEmpty().map { cell -> cell.position } } +
            city.inventory?.containers.orEmpty()
        check(protected.none(::inside), "wall-infill-route-conflict")
        // Height-field path classification protects ordinary searches. Only this proved unowned
        // six-column seam can consume an existing path block through normal paid construction.
        val columns = surface.columns.toMutableMap()
        for (x in footprint.min.x..footprint.max.x) for (z in footprint.min.z..footprint.max.z) {
            val key = civsTerrainKey(x, z)
            val c = columns[key] ?: throw CivsRejection("chunks-unloaded")
            check(c.loaded, "chunks-unloaded")
            check(c.topY == c.groundY && (c.natural || c.dirtPath) && c.waterTopY == null, "autonomy-terrain")
            if (c.dirtPath) columns[key] = c.copy(natural = true)
        }
        return surface.copy(columns = columns)
    }

    fun proof(city: Settlement, target: CivWallInfillTarget, plan: CivPlacementPlan): String =
        CivsGateRefit.sha256(buildString {
            append(city.id); append(city.worldId); append(city.building(target.gateId)); append(city.building(target.wallId))
            append(plan.building); append(plan.capturedSha256)
            plan.blocks.forEach { append(it.position); append(it.before); append(it.after) }
            plan.terrain?.let { append(it.route); it.blocks.forEach { cell -> append(cell.position); append(cell.before); append(cell.after) } }
        }.toByteArray(Charsets.UTF_8))

    private fun check(value: Boolean, reason: String) { if (!value) throw CivsRejection(reason) }
}
