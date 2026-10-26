package ru.arc.civs

import java.util.UUID
import kotlin.math.abs

/** Adopted spatial intent. Native before-images and payment remain owned by each construction order. */
data class CivSettlementPlan(val version: Int, val revision: Long, val halfWidth: Int,
    val plots: List<CivSettlementPlot>, val routes: Map<String, List<CivPosition>>,
    val walls: List<CivBox>, val gates: List<CivBox>, val gateSides: List<Int>? = null)

data class CivSettlementPlot(val building: CivBuilding, val envelope: CivBox)

internal object CivsSettlementPlans {
    const val VERSION = 1

    fun detached(plan: CivSettlementPlan?): CivSettlementPlan? = runCatching { plan?.copy(
        plots = plan.plots.map { it.copy(building = it.building.copy(transit = it.building.transit?.toList())) },
        routes = plan.routes.mapValues { it.value.toList() }, walls = plan.walls.toList(), gates = plan.gates.toList(), gateSides = plan.gateSides?.toList()) }.getOrNull()

    /** Bad or obsolete unpaid intent never quarantines an otherwise usable settlement. */
    fun current(city: Settlement): CivSettlementPlan? = city.autonomy?.plan?.takeIf {
        runCatching { validate(city, it) }.isSuccess
    }

    fun next(city: Settlement, type: BuildingType): CivSettlementPlot? = current(city)?.plots
        ?.firstOrNull { it.building.type == type && city.building(it.building.id) == null }

    fun validate(city: Settlement, plan: CivSettlementPlan) {
        require(plan.version == VERSION && plan.revision >= 0 && plan.halfWidth in 24..56)
        require(plan.plots.size <= CivsRules.MAX_BUILDINGS && plan.plots.map { it.building.id }.distinct().size == plan.plots.size)
        require(plan.walls.size <= CivsRules.MAX_INFRASTRUCTURE && plan.gates.size in 1..8)
        require(plan.gateSides?.let { it.size == plan.gates.size && it.all { side -> side in 0..3 } } ?: (plan.gates.size == 3))
        require(plan.routes.size <= CivsRules.MAX_BUILDINGS + plan.gates.size)
        fun box(b: CivBox) = b.min.x <= b.max.x && b.min.y <= b.max.y && b.min.z <= b.max.z &&
            city.contains(b.min.x, b.min.z) && city.contains(b.max.x, b.max.z)
        require((plan.walls + plan.gates).all(::box))
        require(plan.plots.all { p -> !p.building.type.isInfrastructure && p.building.type in CivsAutonomy.allowedTypes &&
            p.building.level in 1..p.building.type.maxLevel && p.building.rotation in listOf(0, 90, 180, 270) &&
            box(p.building.bounds) && box(p.envelope) && civsContainsBox(p.envelope, p.building.bounds) })
        require(plan.plots.withIndex().all { (i, a) -> plan.plots.drop(i + 1).none {
            CivsAutonomousGeometry.overlaps(a.envelope, it.envelope)
        } })
        require(plan.routes.all { (id, route) ->
            runCatching { UUID.fromString(id) }.isSuccess && route.isNotEmpty() &&
                route.size <= CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH &&
                route.all { city.contains(it.x, it.z) && it.y in -4095..4095 } &&
                route.map { civsTerrainKey(it.x, it.z) }.distinct().size == route.size &&
                route.zipWithNext().all { (a, b) -> abs(a.x.toLong() - b.x) + abs(a.z.toLong() - b.z) == 1L &&
                    abs(a.y.toLong() - b.y) <= 1L }
        })
    }

    /** Same finite frontage, grading and route solver used by single-order placement. No world writes. */
    fun prepare(city: Settlement, templates: Map<BuildingType, Pair<CivBuilding, CivBuilding>>,
        surface: CivTerrainSurface, retained: CivAutonomousAccess,
        inspectRoute: (CivRouteSearchStats) -> Unit = {},
        inspect: ((CivSettlementPlan, CivSettlementInfrastructure) -> Unit)? = null): CivSettlementPlan {
        val frame = CivsSettlementLayout.frame(city)
        val accepted = city.construction?.plannedBuilding
        val actual = city.buildings.map { if (accepted != null && it.id == accepted.id) accepted else it }
        val plots = actual.filterNot { it.type.isInfrastructure }.map { building ->
            val future = templates[building.type]?.second?.let { at(it, building.anchor, building.rotation, building.id) }
            val bounds = if (building.type == BuildingType.PLAN_BOARD) CivsPlanBoardBlueprint.futureBounds(building)
                else union(CivsAutonomousGeometry.futureBounds(city, building), future?.bounds ?: building.bounds)
            CivSettlementPlot(building.copy(phase = BuildingPhase.SITE), bounds)
        }.toMutableList()
        var projected = city.copy(construction = null, constructionQueue = emptyList(),
            buildings = actual.map { b -> b.copy(phase = BuildingPhase.ACTIVE,
                bounds = plots.firstOrNull { it.building.id == b.id }?.envelope ?: b.bounds) })
        val routes = retained.routes.filterKeys { city.building(it)?.type?.isInfrastructure == false }.toMutableMap()
        val preferredRoads = routes.values.flatten().mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }
        val remaining = CivsAutonomy.completeProfile.toMutableList()
        actual.filterNot { it.type.isInfrastructure }.forEach { remaining.remove(it.type) }
        if (plots.size + remaining.size > CivsRules.MAX_BUILDINGS) throw CivsRejection("building-limit")
        // Reserve scarce large parcels before small houses; construction order stays in CivsAutonomy.
        val spatialOrder = remaining.sortedByDescending { type ->
            val box = templates.getValue(type).second.bounds
            (box.max.x.toLong() - box.min.x + 1) * (box.max.z.toLong() - box.min.z + 1)
        }
        for (type in spatialOrder) {
            val rejected = sortedMapOf<String, Int>()
            val stages = linkedMapOf<String, String>()
            val samples = mutableListOf<String>()
            val draftSamples = mutableListOf<String>()
            val occurrence = plots.count { it.building.type == type }
            val id = UUID.nameUUIDFromBytes("arccivs-plan/${city.id}/$type/$occurrence".toByteArray(Charsets.UTF_8))
            val pair = templates.getValue(type)
            val current = pair.first.copy(id = id)
            val future = pair.second.copy(id = id)
            val access = CivAutonomousAccess(routes, retained.infrastructure)
            var chosen: CivTerraformDraft? = null
            for (infill in listOf(false, true)) {
                val stage = if (infill) "infill" else "frontage"
                // Keep the same finite 64-option budget, after rejecting unusable native footprints.
                // Otherwise the closest wet parcels can hide every dry plot farther from the square.
                val eligible: (CivBuilding) -> Boolean = { candidate ->
                    CivsAutonomousTerraform.options(projected, listOf(CivsAutonomousCandidate(candidate)), surface,
                        rejected = { reason, at ->
                            rejected.merge("${stage}_filter_$reason", 1, Int::plus)
                            if (samples.size < 4) samples += "$stage:$reason:${at.x},${at.y},${at.z}"
                        }).isNotEmpty()
                }
                val candidates = if (infill) CivsSettlementLayout.infill(projected, current, future, access, frame, eligible)
                    else CivsSettlementLayout.candidates(projected, current, future, access, frame, eligible)
                stages["${stage}_candidates"] = candidates.size.toString()
                val options = CivsAutonomousTerraform.options(projected, candidates, surface, rejected = { reason, at ->
                    rejected.merge(reason, 1, Int::plus)
                    rejected.merge("${stage}_footprint_$reason", 1, Int::plus)
                    if (samples.size < 4) samples += "$stage:$reason:${at.x},${at.y},${at.z}"
                }).take(64)
                stages["${stage}_options"] = options.size.toString()
                for (option in options) {
                    val draft = try { CivsAutonomousTerraform.draft(projected, option, surface, access,
                        if (retained.infrastructure.isEmpty()) emptyList() else frame.wallObstacles,
                        preferredRoads = preferredRoads, inspectRoute = inspectRoute) }
                    catch (failure: CivsRejection) {
                        rejected.merge(failure.reason, 1, Int::plus)
                        rejected.merge("${stage}_draft_${failure.reason}", 1, Int::plus)
                        failure.values.forEach { (key, value) -> if (key.startsWith("route-"))
                            value.toIntOrNull()?.let { rejected.merge(key, it, Int::plus) } }
                        if (draftSamples.size < 12) draftSamples += "${option.building.entrance}:${failure.values}"
                        null
                    }
                    if (draft != null) { chosen = draft; break }
                }
                if (chosen != null) break
            }
            val draft = chosen ?: throw CivsRejection("autonomy-no-site", mapOf("type" to type.name) +
                rejected.mapValues { it.value.toString() } + stages + mapOf("samples" to samples.joinToString(";"),
                    "draft_samples" to draftSamples.joinToString(";")))
            val building = at(current, draft.building.anchor, draft.building.rotation, id).copy(phase = BuildingPhase.SITE)
            plots += CivSettlementPlot(building, draft.building.bounds)
            routes[id] = draft.route
            projected = projected.copy(buildings = projected.buildings + draft.building.copy(phase = BuildingPhase.SITE))
        }
        val adoptedFrame = if (retained.infrastructure.isEmpty())
            CivsSettlementPerimeter.adapt(projected, frame, surface, routes, retained.routes.values) else frame
        // A legacy accepted frame keeps its gate approaches. New gates already lie on real exterior routes.
        for (gate in if (retained.infrastructure.isEmpty()) emptyList() else adoptedFrame.gates) {
            val id = UUID.nameUUIDFromBytes("arccivs-plan/${city.id}/gate/${gate.side}/${gate.center.x}/${gate.center.z}".toByteArray(Charsets.UTF_8))
            val existing = retained.infrastructure.filter { it.type == BuildingType.GATE }.flatMap { it.transit }
                .firstOrNull { it.x == gate.center.x && it.z == gate.center.z }
            val point = existing ?: surface.at(gate.center.x, gate.center.z)?.takeIf { it.loaded && it.natural }
                ?.let { gate.center.copy(y = it.groundY + 1) } ?: throw CivsRejection("autonomy-terrain")
            val target = CivBuilding(id, BuildingType.GATE, 1, point, 0, "civs/generated/gate",
                CivBox(point, point), point, BuildingPhase.CONSTRUCTING, transit = listOf(point))
            routes[id] = CivsAutonomousTerraform.connection(projected, target, surface,
                CivAutonomousAccess(routes, retained.infrastructure), frame.wallObstacles)
        }
        val roads = routes.values.toList()
        val infrastructure = CivsSettlementLayout.infrastructure(projected, adoptedFrame, surface, roads, retained.infrastructure, roads, retained.routes.values)
        val plan = CivSettlementPlan(VERSION, (city.autonomy?.plan?.revision ?: -1) + 1, frame.halfWidth,
            plots.toList(), routes.mapKeys { it.key.toString() }.mapValues { it.value.toList() },
            adoptedFrame.wallObstacles.toList(), adoptedFrame.gates.map { it.passage }, adoptedFrame.gates.map { it.side })
        inspect?.invoke(plan, infrastructure)
        if (!infrastructure.perimeterClosed || infrastructure.rejected.isNotEmpty() || infrastructure.pieces.size > CivsRules.MAX_INFRASTRUCTURE)
            throw CivsRejection("autonomy-layout-obstructed", infrastructure.rejected.mapValues { it.value.toString() } +
                mapOf("stage" to "infrastructure", "closed" to "${infrastructure.perimeterClosed}",
                    "pieces" to "${infrastructure.pieces.size}", "wall_strips" to "${adoptedFrame.wallObstacles.size}") +
                infrastructure.pieces.groupingBy { it.type.name }.eachCount().mapValues { it.value.toString() })
        return plan.also { validate(city, it) }
    }

    internal fun at(template: CivBuilding, anchor: CivPosition, rotation: Int, id: UUID = template.id): CivBuilding {
        fun point(p: CivPosition): CivPosition {
            val q = when (rotation / 90) {
                0 -> p; 1 -> CivPosition(-p.z, p.y, p.x); 2 -> CivPosition(-p.x, p.y, -p.z)
                else -> CivPosition(p.z, p.y, -p.x)
            }
            return CivPosition(anchor.x + q.x, anchor.y + q.y, anchor.z + q.z)
        }
        val corners = listOf(template.bounds.min, template.bounds.max,
            template.bounds.min.copy(z = template.bounds.max.z), template.bounds.max.copy(z = template.bounds.min.z)).map(::point)
        return template.copy(id = id, anchor = anchor, rotation = rotation,
            bounds = CivBox(CivPosition(corners.minOf { it.x }, corners.minOf { it.y }, corners.minOf { it.z }),
                CivPosition(corners.maxOf { it.x }, corners.maxOf { it.y }, corners.maxOf { it.z })),
            entrance = point(template.entrance), control = template.control?.let(::point))
    }

    private fun union(a: CivBox, b: CivBox) = CivBox(
        CivPosition(minOf(a.min.x, b.min.x), minOf(a.min.y, b.min.y), minOf(a.min.z, b.min.z)),
        CivPosition(maxOf(a.max.x, b.max.x), maxOf(a.max.y, b.max.y), maxOf(a.max.z, b.max.z)))
}
