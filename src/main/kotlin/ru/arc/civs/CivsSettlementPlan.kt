package ru.arc.civs

import java.util.UUID
import java.util.Collections
import kotlin.math.abs

/** Adopted spatial intent. Native before-images and payment remain owned by each construction order. */
data class CivSettlementPlan(val version: Int, val revision: Long, val halfWidth: Int,
    val plots: List<CivSettlementPlot>, val routes: Map<String, List<CivPosition>>,
    val walls: List<CivBox>, val gates: List<CivBox>, val gateSides: List<Int>? = null,
    val deferredPerimeter: CivDeferredPerimeter? = null)

/** A usable ordinary parcel was planned, but its new perimeter was not proven. */
data class CivDeferredPerimeter(val stage: String, val plotId: UUID, val plotAnchor: CivPosition,
    val crossing: CivPosition? = null)

data class CivSettlementFortification(val sourceSha256: String, val recipeSha256: String,
    val proof: CivFortificationProof)

data class CivSettlementPlot(val building: CivBuilding, val envelope: CivBox,
    val fortification: CivSettlementFortification? = null)

internal object CivsSettlementPlans {
    const val VERSION = 1
    private val deferredPerimeterStages = setOf("no-dry-perimeter", "gate-junction", "infrastructure")
    private val geometricGateOutcomes = setOf("route-target-blocked", "route-disconnected",
        "route-exhausted", "route-length-or-repeat")

    internal fun initialRoadPerimeterMayWait(city: Settlement, failure: CivsRejection): Boolean {
        val values = failure.values
        if (values["fully_loaded"] != "true" || city.buildings.any { it.type.isInfrastructure } ||
            !CivsRetainedGrowthPerimeter.geometric(failure)) return false
        return when (values["stage"]) {
            "retained-road-wall" -> failure.reason == "autonomy-layout-obstructed" &&
                values["first"] != null && values["retained_floor"]?.toIntOrNull() != null
            "gate-junction" -> failure.reason == "autonomy-layout-obstructed" &&
                values["retained_crossing"] == "true" && values["first"] != null &&
                (values["candidate_retained-route"]?.toIntOrNull() ?: 0) > 0
            "gate-connection" -> {
                val outcomes = values.keys.filter { it.startsWith("route-") }
                failure.reason == "autonomy-no-path" && outcomes.isNotEmpty() && outcomes.all { it in geometricGateOutcomes }
            }
            else -> false
        }
    }

    private fun fullyLoadedSurvey(city: Settlement, surface: CivTerrainSurface): Boolean {
        val radius = city.radius.coerceIn(0, 127)
        val diameter = radius * 2 + 1
        return city.radius == radius && surface.columns.size == diameter * diameter && surface.columns.values.all { it.loaded }
    }

    fun detached(plan: CivSettlementPlan?): CivSettlementPlan? = runCatching { plan?.copy(
        plots = plan.plots.map { it.copy(building = it.building.copy(transit = it.building.transit?.toList()),
            fortification = it.fortification?.let(::detachedFortification)) },
        routes = plan.routes.mapValues { it.value.toList() }, walls = plan.walls.toList(), gates = plan.gates.toList(), gateSides = plan.gateSides?.toList()) }.getOrNull()

    /** Bad or obsolete unpaid intent never quarantines an otherwise usable settlement. */
    fun current(city: Settlement): CivSettlementPlan? = city.autonomy?.plan?.takeIf {
        runCatching { validate(city, it) }.isSuccess
    }

    fun next(city: Settlement, type: BuildingType): CivSettlementPlot? {
        val missing = current(city)?.plots.orEmpty().filter { it.building.type == type && city.building(it.building.id) == null &&
            (type != BuildingType.QUARRY || CivsSettlementGrowth.outside(city, it.envelope)) }
        return if (type == BuildingType.DECORATION) missing.firstOrNull { it.fortification != null } ?: missing.firstOrNull()
            else missing.firstOrNull()
    }

    internal fun sameFortificationGeometry(a: CivBuilding, b: CivBuilding): Boolean = a.copy(phase = b.phase) == b

    internal fun portReservations(proof: CivFortificationProof): List<CivBox> = proof.wallEndPorts.map { port ->
        val base = port.ground[1]
        val across = CivPosition(-port.outward.z, 0, port.outward.x)
        val points = listOf(1, 3).flatMap { distance -> listOf(-2, 2).map { side ->
            CivPosition(base.x + port.outward.x * distance + across.x * side, base.y,
                base.z + port.outward.z * distance + across.z * side)
        } }
        CivBox(CivPosition(points.minOf { it.x }, base.y, points.minOf { it.z }),
            CivPosition(points.maxOf { it.x }, base.y + 9, points.maxOf { it.z }))
    }

    internal fun matchesFortification(plot: CivSettlementPlot, template: CivFortificationTemplate): Boolean = runCatching {
        validateFortification(plot)
        val metadata = plot.fortification ?: return@runCatching false
        val expected = at(template.building, plot.building.anchor, plot.building.rotation, plot.building.id)
        metadata.sourceSha256 == template.sourceSha256 && metadata.recipeSha256 == template.recipeSha256 &&
            sameFortificationGeometry(plot.building, expected) && plot.envelope == expected.bounds &&
            metadata.proof == template.proofAt(plot.building.anchor, plot.building.rotation)
    }.getOrDefault(false)

    private fun detachedFortification(value: CivSettlementFortification): CivSettlementFortification {
        fun points(values: List<CivPosition>) = Collections.unmodifiableList(values.toList())
        val proof = value.proof
        return value.copy(proof = proof.copy(groundBarrier = Collections.unmodifiableSet(proof.groundBarrier.toSet()),
            wallWalkFloors = Collections.unmodifiableSet(proof.wallWalkFloors.toSet()),
            wallEndPorts = Collections.unmodifiableList(proof.wallEndPorts.map { it.copy(ground = points(it.ground),
                floor = points(it.floor), feet = points(it.feet), head = points(it.head)) }),
            interiorWitnesses = points(proof.interiorWitnesses)))
    }

    private fun validateFortification(plot: CivSettlementPlot) {
        val metadata = plot.fortification ?: return
        val building = plot.building
        require(building.type == BuildingType.DECORATION && building.level == 1 && building.control == null &&
            building.production == null && building.transit == null && plot.envelope == building.bounds)
        require(listOf(metadata.sourceSha256, metadata.recipeSha256).all { it.matches(Regex("[0-9a-f]{64}")) } &&
            building.schematicId == "civs/fortifications/v1/${CivsFortifications.STYLE}/${metadata.recipeSha256}")
        val proof = metadata.proof
        require(proof.groundBarrier.size in 1..1024 && proof.wallEndPorts.size == 2 && proof.interiorWitnesses.size in 1..16)
        require(proof.wallWalkFloors.size in 6..1024 && proof.wallWalkFloors.all {
            it.y == building.anchor.y + 7 && building.bounds.contains(it.x, it.y, it.z)
        })
        require(proof.groundBarrier.all { it.y == building.anchor.y && building.bounds.contains(it.x, it.y, it.z) })
        require(proof.interiorWitnesses.all { it.y == building.anchor.y + 1 && building.bounds.contains(it.x, it.y, it.z) })
        for (port in proof.wallEndPorts) {
            require(port.outward.y == 0 && abs(port.outward.x) + abs(port.outward.z) == 1)
            require(listOf(port.ground, port.floor, port.feet, port.head).all { it.size == 3 && it.distinct().size == 3 })
            require(port.ground.all { it in proof.groundBarrier })
            for (index in 0..2) {
                val base = port.ground[index]
                require(port.floor[index] == base.copy(y = base.y + 7) && port.feet[index] == base.copy(y = base.y + 8) &&
                    port.head[index] == base.copy(y = base.y + 9))
                require(port.floor[index] in proof.wallWalkFloors)
                require(!building.bounds.contains(base.x + port.outward.x, base.y, base.z + port.outward.z))
            }
            require(port.ground.zipWithNext().all { (a, b) -> abs(a.x - b.x) + abs(a.z - b.z) == 1 &&
                (b.x - a.x) * port.outward.x + (b.z - a.z) * port.outward.z == 0 })
        }
    }

    /** A compatible older plan remains usable while its missing unpaid plots wait for safe admission. */
    fun needsExtension(city: Settlement): Boolean {
        if (city.construction != null || city.constructionQueue.orEmpty().isNotEmpty()) return false
        val plan = current(city) ?: return false
        val types = (plan.plots.map { it.building } + city.buildings).distinctBy { it.id }.groupingBy { it.type }.eachCount()
        val goal = CivsAutonomy.nextGoal(city)
        return goal?.takeIf { it.buildingId == null }?.let { next(city, it.type) == null } == true ||
            CivsAutonomy.completeProfile.groupingBy { it }.eachCount().any { (type, count) -> (types[type] ?: 0) < count }
    }

    fun validate(city: Settlement, plan: CivSettlementPlan) {
        require(plan.version == VERSION && plan.revision >= 0 && plan.halfWidth in 24..CivsSettlementLayout.MAXIMUM_HALF_WIDTH)
        require(plan.plots.size <= CivsRules.MAX_BUILDINGS + CivsRules.decorationLimit(city) &&
            plan.plots.count { it.building.type != BuildingType.DECORATION } <= CivsRules.MAX_BUILDINGS &&
            plan.plots.map { it.building.id }.distinct().size == plan.plots.size)
        if (city.autonomy != null) require(plan.plots.count { it.fortification != null } <= CivsRules.MAX_AUTONOMOUS_FORTIFICATIONS &&
            plan.plots.count { it.building.type == BuildingType.DECORATION && it.fortification == null } <= CivsRules.MAX_AUTONOMOUS_SCENERY)
        require(plan.walls.size <= CivsRules.MAX_INFRASTRUCTURE && plan.gates.size in 1..8)
        require(plan.gateSides?.let { it.size == plan.gates.size && it.all { side -> side in 0..3 } } ?: (plan.gates.size == 3))
        require(plan.routes.size <= CivsRules.MAX_BUILDINGS + CivsRules.decorationLimit(city) + plan.gates.size)
        fun box(b: CivBox) = b.min.x <= b.max.x && b.min.y <= b.max.y && b.min.z <= b.max.z &&
            city.contains(b.min.x, b.min.z) && city.contains(b.max.x, b.max.z)
        require((plan.walls + plan.gates).all(::box))
        require(plan.plots.all { p -> !p.building.type.isInfrastructure && p.building.type in CivsAutonomy.allowedTypes &&
            p.building.level in 1..p.building.type.maxLevel && p.building.rotation in listOf(0, 90, 180, 270) &&
            box(p.building.bounds) && box(p.envelope) && civsContainsBox(p.envelope, p.building.bounds) })
        plan.plots.forEach(::validateFortification)
        plan.deferredPerimeter?.let { deferred ->
            require(deferred.stage in deferredPerimeterStages && plan.plots.any {
                it.building.id == deferred.plotId && it.building.anchor == deferred.plotAnchor && it.fortification == null
            })
            require(deferred.crossing?.let { city.contains(it.x, it.z) } != false)
        }
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
        fortification: CivFortificationTemplate? = null,
        profile: List<BuildingType> = CivsAutonomy.completeProfile,
        halfWidth: Int? = null,
        inspectRoute: (CivRouteSearchStats) -> Unit = {},
        inspect: ((CivSettlementPlan, CivSettlementInfrastructure) -> Unit)? = null): CivSettlementPlan {
        val newFortifications = fortification != null && city.style == CivsFortifications.STYLE && city.autonomy?.plan == null &&
            city.autonomy != null && city.construction == null && city.constructionQueue.orEmpty().isEmpty() &&
            city.buildings.none { it.type.isInfrastructure } && retained.infrastructure.isEmpty() &&
            CivsSettlementLayout.frame(city).halfWidth > 56 && profile == CivsAutonomy.completeProfile
        val maximum = if (newFortifications) minOf(CivsRules.MAX_AUTONOMOUS_FORTIFICATIONS,
            CivsRules.decorationLimit(city) - city.buildings.count { it.type == BuildingType.DECORATION }) else 0
        for (count in maximum downTo 0) {
            try { return prepareOnce(city, templates, surface, retained, fortification, count, profile, halfWidth, inspectRoute, inspect) }
            catch (failure: CivsRejection) { if (count == 0) throw failure }
        }
        throw CivsRejection("autonomy-no-site")
    }

    /** Add a quarter only after the next ordinary plot no longer fits the current one. Paid work stays pinned. */
    fun prepareGrowth(city: Settlement, templates: Map<BuildingType, Pair<CivBuilding, CivBuilding>>,
        surface: CivTerrainSurface, retained: CivAutonomousAccess, profile: List<BuildingType>,
        fortification: CivFortificationTemplate? = null): CivSettlementPlan {
        var last: CivsRejection? = null
        val compactFounding = city.autonomy?.let {
            it.plan == null && it.layoutHalfWidth == CivsSettlementGrowth.initialHalfWidth(city)
        } == true
        var deferredInfrastructure: CivsRejection? = null
        var deferredRoadPerimeter: CivsRejection? = null
        val prior = current(city)
        val actual = city.buildings.filter { !it.type.isInfrastructure && it.type != BuildingType.DECORATION }.map { it.type }
        val paidInfrastructure = city.buildings.filter { it.type.isInfrastructure }.mapTo(hashSetOf()) { it.id }
        val civicTarget = profile.lastOrNull()?.takeIf {
            profile.dropLast(1) == actual && !it.isInfrastructure && it !in CivsSettlementLayout.exteriorTypes && it != BuildingType.DECORATION &&
                city.buildings.all { building -> building.phase == BuildingPhase.ACTIVE } &&
                paidInfrastructure == retained.infrastructure.mapTo(hashSetOf()) { piece -> piece.id } &&
                (paidInfrastructure.isEmpty() || prior?.deferredPerimeter != null) &&
                prior?.plots?.all { plot -> plot.fortification == null } == true
        }
        var ordinaryWithoutNewPerimeter: CivSettlementPlan? = null
        var alternatives = 0
        for (width in CivsSettlementGrowth.halfWidths(city)) {
            var routeBudget = false
            val inspectRoute: (CivRouteSearchStats) -> Unit = { if (it.outcome == "route-budget") routeBudget = true }
            var forecast: CivSettlementPlan? = null
            val inspect: ((CivSettlementPlan, CivSettlementInfrastructure) -> Unit)? =
                if (civicTarget == null || ordinaryWithoutNewPerimeter != null) null else { plan, _ -> forecast = plan }
            try { return prepare(city, templates, surface, retained, fortification, profile, width, inspectRoute, inspect) }
            catch (failure: CivsRejection) {
                if (failure.reason !in setOf("autonomy-no-site", "autonomy-layout-obstructed", "autonomy-no-path")) throw failure
                if (compactFounding && deferredInfrastructure == null && failure.reason == "autonomy-layout-obstructed" &&
                    failure.values["stage"] == "infrastructure") deferredInfrastructure = failure
                if (compactFounding && deferredRoadPerimeter == null && initialRoadPerimeterMayWait(city, failure))
                    deferredRoadPerimeter = failure
                last = failure
                if (ordinaryWithoutNewPerimeter == null && !routeBudget && civicTarget != null && forecast != null &&
                    failure.reason == "autonomy-layout-obstructed" && failure.values["stage"] in deferredPerimeterStages &&
                    CivsRetainedGrowthPerimeter.geometric(failure) &&
                    failure.values.keys.none { it in setOf("chunks-unloaded", "world-unloaded", "lands-denied") }) {
                    ordinaryWithoutNewPerimeter = ordinaryGrowth(city, requireNotNull(forecast), retained, civicTarget, failure)
                }
                if (alternatives < 3 && !routeBudget && CivsRetainedGrowthPerimeter.geometric(failure)) {
                    val adopted = current(city)
                    val envelopes = city.buildings.filterNot { it.type.isInfrastructure }.associate { building ->
                        val actual = actualPlot(city, building, templates)
                        val old = adopted?.plots?.firstOrNull { it.building.id == building.id }
                        building.id to if (old == null) actual.envelope else union(actual.envelope, old.envelope)
                    }
                    val nominal = CivsSettlementLayout.frame(city, width)
                    for (candidate in CivsRetainedGrowthPerimeter.candidates(city, nominal, surface, retained, envelopes)) {
                        if (alternatives == 3) break
                        alternatives++
                        routeBudget = false
                        try { return prepareOnce(city, templates, surface, retained, fortification, 0, profile,
                            width, inspectRoute, null, candidate) }
                        catch (alternative: CivsRejection) {
                            if (routeBudget || !CivsRetainedGrowthPerimeter.geometric(alternative)) throw alternative
                            last = alternative
                        }
                    }
                }
            }
        }
        return ordinaryWithoutNewPerimeter ?: throw requireNotNull(deferredInfrastructure ?: deferredRoadPerimeter ?: last)
    }

    private fun ordinaryGrowth(city: Settlement, forecast: CivSettlementPlan, retained: CivAutonomousAccess,
        target: BuildingType, failure: CivsRejection): CivSettlementPlan? {
        if (retained.infrastructure.isNotEmpty()) {
            val prior = current(city) ?: return null
            if (prior.deferredPerimeter == null || forecast.halfWidth != prior.halfWidth || forecast.walls != prior.walls ||
                forecast.gates != prior.gates || forecast.gateSides != prior.gateSides) return null
        }
        val next = forecast.plots.filter { city.building(it.building.id) == null }.singleOrNull()
            ?.takeIf { it.building.type == target && it.building.level == 1 && it.fortification == null } ?: return null
        if (!city.buildings.filterNot { it.type.isInfrastructure }.all { actual ->
                civsSameBuildingGeometry(forecast.plots.singleOrNull { it.building.id == actual.id }?.building,
                    actual.copy(phase = BuildingPhase.SITE))
            } || retained.routes.any { (id, route) ->
                city.building(id)?.type?.isInfrastructure == false && forecast.routes[id.toString()] != route
            }) return null
        val approach = forecast.routes[next.building.id.toString()] ?: return null
        if (approach.lastOrNull() != next.building.entrance ||
            city.buildings.none { CivsRules.isDepot(it) && it.entrance == approach.firstOrNull() } ||
            approach.any { p -> forecast.walls.any { CivsAutonomousGeometry.horizontalContains(it, p) } }) return null
        val crossing = failure.values["first"]?.split(',')?.takeIf { it.size == 2 }?.mapNotNull(String::toIntOrNull)
            ?.takeIf { it.size == 2 }?.let { CivPosition(it[0], city.center.y, it[1]) }
        val pending = forecast.copy(deferredPerimeter = CivDeferredPerimeter(requireNotNull(failure.values["stage"]),
            next.building.id, next.building.anchor, crossing))
        return runCatching { validate(city, pending); pending }.getOrNull()
    }

    private fun prepareOnce(city: Settlement, templates: Map<BuildingType, Pair<CivBuilding, CivBuilding>>,
        surface: CivTerrainSurface, retained: CivAutonomousAccess, fortification: CivFortificationTemplate?, count: Int,
        profile: List<BuildingType>, halfWidth: Int?,
        inspectRoute: (CivRouteSearchStats) -> Unit,
        inspect: ((CivSettlementPlan, CivSettlementInfrastructure) -> Unit)?,
        candidateFrame: CivSettlementFrame? = null): CivSettlementPlan {
        val frame = (candidateFrame ?: CivsSettlementLayout.frame(city, halfWidth ?: city.autonomy?.layoutHalfWidth))
            .let { if (count > 0) it.copy(fortified = true) else it }
        val adopted = current(city)
        val expanding = frame.halfWidth > CivsSettlementLayout.frame(city).halfWidth
        val accepted = city.construction?.plannedBuilding
        val actual = city.buildings.map { if (accepted != null && it.id == accepted.id) accepted else it }
        val actualPlots = actual.filterNot { it.type.isInfrastructure }.map { actualPlot(city, it, templates) }
        val plots = adopted?.plots.orEmpty().filter { old -> city.building(old.building.id) != null || !expanding &&
            (old.building.type != BuildingType.QUARRY || !CivsAutonomousGeometry.overlaps(old.envelope, frame.core, 3)) }
            .map { old -> actualPlots.firstOrNull { it.building.id == old.building.id }
            ?.let { it.copy(envelope = union(old.envelope, it.envelope), fortification = old.fortification) } ?: old }.toMutableList()
        plots += actualPlots.filter { actualPlot -> plots.none { it.building.id == actualPlot.building.id } }
        var projected = city.copy(construction = null, constructionQueue = emptyList(),
            buildings = actual.map { b -> b.copy(phase = if (plots.any { it.building.id == b.id && it.fortification != null })
                b.phase else BuildingPhase.ACTIVE,
                bounds = plots.firstOrNull { it.building.id == b.id }?.envelope ?: b.bounds) } +
                plots.filter { plot -> actual.none { it.id == plot.building.id } }.map { it.building.copy(bounds = it.envelope,
                    phase = if (it.building.type == BuildingType.WAREHOUSE) BuildingPhase.ACTIVE else it.building.phase) })
        val keptPlots = plots.mapTo(hashSetOf()) { it.building.id.toString() }
        val routes = adopted?.routes.orEmpty().filter { (id, _) -> id in keptPlots || !expanding &&
            adopted?.plots.orEmpty().none { it.building.id.toString() == id } }.mapKeys { UUID.fromString(it.key) }.toMutableMap()
        routes += retained.routes.filterKeys { city.building(it)?.type?.isInfrastructure == false }
        val preferredRoads = routes.values.flatten().mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }
        fun planningAccess() = retained.copy(routes = if (candidateFrame == null) routes else routes + retained.routes,
            reservedFortificationPorts = plots.flatMap { it.fortification?.let { metadata -> portReservations(metadata.proof) }.orEmpty() })
        if (count > 0) {
            val template = requireNotNull(fortification)
            var reserved = 0
            for (quarter in 0..3) {
                if (reserved == count) break
                val id = UUID.nameUUIDFromBytes("arccivs-fortification/${city.id}/${template.recipeSha256}/$quarter".toByteArray(Charsets.UTF_8))
                val rotation = Math.floorMod(frame.rotation / 90 + quarter, 4) * 90
                val candidates = listOf(0, 4, 8).map { inset ->
                    val local = CivPosition(frame.halfWidth - inset - template.building.bounds.max.x, city.center.y,
                        -frame.halfWidth + inset - template.building.bounds.min.z)
                    val offset = when (rotation / 90) { 0 -> local; 1 -> CivPosition(-local.z, local.y, local.x)
                        2 -> CivPosition(-local.x, local.y, -local.z); else -> CivPosition(local.z, local.y, -local.x) }
                    at(template.building, CivPosition(city.center.x + offset.x, offset.y, city.center.z + offset.z), rotation, id)
                }.filter { b -> city.contains(b.bounds.min.x, b.bounds.min.z) && city.contains(b.bounds.max.x, b.bounds.max.z) &&
                    plots.none { CivsAutonomousGeometry.overlaps(b.bounds, it.envelope, 2) } }
                    .map { CivsAutonomousCandidate(it) }
                val draft = CivsAutonomousTerraform.options(projected, candidates, surface).asSequence().mapNotNull { option ->
                    try { CivsAutonomousTerraform.draft(projected, option, surface,
                        planningAccess(), preferredRoads = preferredRoads, inspectRoute = inspectRoute) }
                    catch (_: CivsRejection) { null }
                }.firstOrNull() ?: continue
                val building = draft.building.copy(phase = BuildingPhase.SITE)
                val plot = CivSettlementPlot(building, building.bounds, detachedFortification(CivSettlementFortification(
                    template.sourceSha256, template.recipeSha256, template.proofAt(building.anchor, building.rotation))))
                if (!matchesFortification(plot, template)) throw CivsRejection("invalid-plan")
                plots += plot
                routes[plot.building.id] = draft.route
                draft.route.mapTo(preferredRoads) { civsTerrainKey(it.x, it.z) }
                projected = projected.copy(buildings = projected.buildings + plot.building)
                reserved++
            }
            if (reserved < count) throw CivsRejection("autonomy-no-site", mapOf("stage" to "fortification-reservation"))
        }
        val remaining = profile.toMutableList()
        plots.forEach { remaining.remove(it.building.type) }
        if (plots.count { it.building.type != BuildingType.DECORATION } + remaining.size > CivsRules.MAX_BUILDINGS)
            throw CivsRejection("building-limit")
        // A first worksite of each profession and civic services precede repetitive outskirts and housing.
        // Construction order stays in CivsAutonomy; these are only unpaid spatial reservations.
        val occurrences = plots.groupingBy { it.building.type }.eachCount().toMutableMap()
        val spatialOrder = remaining.map { type ->
            val occurrence = occurrences[type] ?: 0
            occurrences[type] = occurrence + 1
            type to occurrence
        }.sortedWith(compareBy<Pair<BuildingType, Int>> { (type, occurrence) ->
            when { type == BuildingType.HOUSE -> 2; type in CivsSettlementLayout.exteriorTypes && occurrence > 0 -> 1; else -> 0 }
        }.thenByDescending { (type, _) ->
            val box = templates.getValue(type).second.bounds
            (box.max.x.toLong() - box.min.x + 1) * (box.max.z.toLong() - box.min.z + 1)
        })
        for ((type, _) in spatialOrder) {
            val rejected = sortedMapOf<String, Int>()
            val stages = linkedMapOf<String, String>()
            val samples = mutableListOf<String>()
            val draftSamples = mutableListOf<String>()
            val occurrence = plots.count { it.building.type == type }
            val id = UUID.nameUUIDFromBytes("arccivs-plan/${city.id}/$type/$occurrence".toByteArray(Charsets.UTF_8))
            val pair = templates.getValue(type)
            val current = pair.first.copy(id = id)
            val future = pair.second.copy(id = id)
            val access = planningAccess()
            val bootstrap = occurrence == 0 && type in CivsSettlementLayout.exteriorTypes + BuildingType.BUILDER_HUT
            val firstStage = occurrence == 0 || type == BuildingType.HOUSE && occurrence < 3
            val firstWarehouse = plots.firstOrNull { it.building.type == BuildingType.WAREHOUSE }?.building?.id
            // Spatial order reserves large depots early. Native goal order still owns when each one can supply a route.
            val navigable = projected.copy(buildings = projected.buildings.map { building ->
                val actual = city.building(building.id)?.let(CivsRules::isDepot) == true
                val forecast = !bootstrap && (!firstStage || building.id == firstWarehouse)
                if (building.type == BuildingType.WAREHOUSE && !actual && !forecast) building.copy(phase = BuildingPhase.SITE) else building
            })
            val reachable: (CivTerraformOption) -> Boolean = { !bootstrap || CivsRules.workRouteWithinReach(city, it.building.entrance) }
            val standing = access.standing(navigable, id)
            var chosen: CivTerraformDraft? = null
            var firstAttempted: CivBuilding? = null
            for (infill in listOf(false, true)) {
                val stage = if (infill) "infill" else "frontage"
                // Keep the same finite 64-option budget, after rejecting unusable native footprints.
                // Otherwise the closest wet parcels can hide every dry plot farther from the square.
                val eligible: (CivBuilding) -> Boolean = { candidate ->
                    CivsAutonomousTerraform.options(navigable, listOf(CivsAutonomousCandidate(candidate)), surface, standing = standing,
                        rejected = { reason, at ->
                            rejected.merge("${stage}_filter_$reason", 1, Int::plus)
                            if (samples.size < 4) samples += "$stage:$reason:${at.x},${at.y},${at.z}"
                        }).any(reachable)
                }
                val candidates = if (infill) CivsSettlementLayout.infill(projected, current, future, access, frame, eligible)
                    else CivsSettlementLayout.candidates(projected, current, future, access, frame, eligible)
                stages["${stage}_candidates"] = candidates.size.toString()
                val options = CivsAutonomousTerraform.options(navigable, candidates, surface, standing = standing, rejected = { reason, at ->
                    rejected.merge(reason, 1, Int::plus)
                    rejected.merge("${stage}_footprint_$reason", 1, Int::plus)
                    if (samples.size < 4) samples += "$stage:$reason:${at.x},${at.y},${at.z}"
                }).filter(reachable).take(64)
                stages["${stage}_options"] = options.size.toString()
                for (option in options) {
                    if (firstAttempted == null) firstAttempted = option.building
                    val draft = try { CivsAutonomousTerraform.draft(navigable, option, surface, access,
                        if (adopted == null && retained.infrastructure.isEmpty()) emptyList() else frame.wallObstacles,
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
            val draft = chosen ?: run {
                inspect?.invoke(CivSettlementPlan(VERSION, (city.autonomy?.plan?.revision ?: -1) + 1, frame.halfWidth,
                    plots + listOfNotNull(firstAttempted?.let { CivSettlementPlot(it.copy(phase = BuildingPhase.SITE), it.bounds) }),
                    routes.mapKeys { it.key.toString() }, frame.wallObstacles, frame.gates.map { it.passage }, frame.gates.map { it.side }),
                    CivSettlementInfrastructure(emptyList(), rejected + ("unplaced-target" to if (firstAttempted == null) 0 else 1), false))
                throw CivsRejection("autonomy-no-site", mapOf("type" to type.name) +
                    rejected.mapValues { it.value.toString() } + stages + mapOf("samples" to samples.joinToString(";"),
                        "draft_samples" to draftSamples.joinToString(";")))
            }
            val building = at(current, draft.building.anchor, draft.building.rotation, id).copy(phase = BuildingPhase.SITE)
            plots += CivSettlementPlot(building, draft.building.bounds)
            routes[id] = draft.route
            draft.route.mapTo(preferredRoads) { civsTerrainKey(it.x, it.z) }
            projected = projected.copy(buildings = projected.buildings + draft.building.copy(
                phase = if (type == BuildingType.WAREHOUSE) BuildingPhase.ACTIVE else BuildingPhase.SITE))
        }
        val fortificationProofs = plots.mapNotNull { plot -> plot.fortification?.let { plot.building.id to it.proof } }.toMap()
        val exteriorRoutes = routes.keys.any { projected.building(it)?.type in CivsSettlementLayout.exteriorTypes }
        val adoptedFrame = if (adopted != null && !expanding) frame else if (retained.infrastructure.isEmpty() && exteriorRoutes)
            try { CivsSettlementPerimeter.adapt(projected, frame, surface, routes, retained.routes.values, fortificationProofs) }
            catch (failure: CivsRejection) {
                if (inspect != null) runCatching {
                    inspect(CivSettlementPlan(VERSION, (city.autonomy?.plan?.revision ?: -1) + 1, frame.halfWidth,
                        plots.toList(), routes.mapKeys { it.key.toString() }, frame.wallObstacles,
                        frame.gates.map { it.passage }, frame.gates.map { it.side }),
                        CivSettlementInfrastructure(emptyList(), mapOf("perimeter-failed" to 1), false))
                }.onFailure(failure::addSuppressed)
                if (failure.reason == "autonomy-layout-obstructed" && (failure.values["stage"] == "retained-road-wall" ||
                        failure.values["stage"] == "gate-junction" && failure.values["retained_crossing"] == "true"))
                    throw CivsRejection(failure.reason, failure.values +
                        mapOf("fully_loaded" to fullyLoadedSurvey(city, surface).toString(),
                            "half_width" to frame.halfWidth.toString())).also { it.initCause(failure) }
                throw failure
            }
            else CivsPerimeterCrossings.adapt(frame, planningAccess().routes.values, retained.infrastructure)
        if (candidateFrame != null) CivsRetainedGrowthPerimeter.validate(city, adoptedFrame, surface, retained,
            plots.map { it.envelope })
        if (retained.infrastructure.isEmpty()) {
            if (adoptedFrame.fortified) for ((index, gate) in adoptedFrame.gates.withIndex()) {
                for (other in adoptedFrame.gates.drop(index + 1)) {
                    if (abs(gate.center.x - other.center.x) + abs(gate.center.z - other.center.z) <= 16)
                        throw CivsRejection("autonomy-layout-obstructed", mapOf("stage" to "gate-spacing",
                            "first" to "${gate.center.x},${gate.center.z}",
                            "second" to "${other.center.x},${other.center.z}"))
                }
            }
            val obstacles = adoptedFrame.wallObstacles + CivsSettlementLayout.gateConnectionObstacles(adoptedFrame)
            val streetGates = adoptedFrame.gates.filter { gate -> retained.routes.values.none { route ->
                route.any { CivsAutonomousGeometry.horizontalContains(gate.passage, it) } } }
            val streets = streetGates.map { gate -> CivRouteStreet(gate.center,
                Math.floorMod(gate.side + adoptedFrame.rotation / 90, 2) == 1) }
            val reconnectObstacles = obstacles + streetGates.map { it.passage }
            // Full depot-to-plot routes include their shared prefixes, so this also removes every
            // provisional dependent before selecting shared streets, including legal edge lanes.
            val reconnect = routes.filter { (id, route) -> city.building(id) == null && id !in retained.routes &&
                (adopted == null || id.toString() !in adopted.routes) &&
                route.any { p -> reconnectObstacles.any { CivsAutonomousGeometry.horizontalContains(it, p) } } }
            reconnect.keys.forEach(routes::remove)
            for ((id, previous) in reconnect) {
                val target = requireNotNull(projected.building(id))
                // Keep the original source's admission: an early worksite cannot acquire a new
                // dependency on a forecast warehouse merely because its approach was redirected.
                val navigable = projected.copy(buildings = projected.buildings.map { building ->
                    if (building.type == BuildingType.WAREHOUSE && city.building(building.id)?.let(CivsRules::isDepot) != true &&
                        building.entrance != previous.first()) building.copy(phase = BuildingPhase.SITE) else building
                })
                val endpoints = navigable.buildings.filter { CivsRules.isDepot(it) || it.type == BuildingType.TOWN_HALL }
                    .map { it.entrance } + target.entrance
                val centreObstacles = streetGates.flatMap { gate ->
                    // Keep four approach steps straight beyond each portal edge, including
                    // deeper fortified walls. Only new road proposals use this restriction.
                    val acrossX = Math.floorMod(gate.side + adoptedFrame.rotation / 90, 2) == 0
                    val box = if (acrossX) gate.passage.copy(min = gate.passage.min.copy(z = gate.passage.min.z - 4),
                        max = gate.passage.max.copy(z = gate.passage.max.z + 4))
                    else gate.passage.copy(min = gate.passage.min.copy(x = gate.passage.min.x - 4),
                        max = gate.passage.max.copy(x = gate.passage.max.x + 4))
                    val apron = CivBox(box.min.copy(x = box.min.x - 1, z = box.min.z - 1),
                        box.max.copy(x = box.max.x + 1, z = box.max.z + 1))
                    // An actual source or destination beside this short channel may still
                    // enter from its own forecourt, rather than being trapped behind a mask.
                    if (endpoints.any { CivsAutonomousGeometry.horizontalContains(apron, it) }) emptyList()
                    else if (acrossX)
                        listOf(box.copy(max = box.max.copy(x = gate.center.x - 1)), box.copy(min = box.min.copy(x = gate.center.x + 1)))
                    else listOf(box.copy(max = box.max.copy(z = gate.center.z - 1)), box.copy(min = box.min.copy(z = gate.center.z + 1)))
                }
                // Keep the street on the gate axis; place a short transverse branch beside the
                // worksite instead of letting an equal-length search turn along the gate flank.
                val frontage = if (target.type !in CivsSettlementLayout.exteriorTypes) null else
                    streetGates.mapNotNull { gate ->
                        val acrossX = Math.floorMod(gate.side + adoptedFrame.rotation / 90, 2) == 0
                        val entry = target.entrance
                        val normal = if (acrossX) entry.z - gate.center.z else entry.x - gate.center.x
                        val sourceNormal = if (acrossX) previous.first().z - gate.center.z else previous.first().x - gate.center.x
                        val shift = if (acrossX) abs(entry.x - gate.center.x) else abs(entry.z - gate.center.z)
                        if (shift !in 1..6 || abs(normal) < 6 || normal.toLong() * sourceNormal >= 0)
                            null else (if (acrossX) entry.copy(x = gate.center.x) else entry.copy(z = gate.center.z)) to abs(normal)
                    }.minByOrNull { it.second }?.first
                val routeOutcomes = linkedMapOf<String, Int>()
                var lastConnection: CivRouteSearchStats? = null
                routes[id] = try {
                    val access = planningAccess()
                    val connected = CivsAutonomousTerraform.connection(navigable, target, surface,
                        access, obstacles + centreObstacles, { stats ->
                            inspectRoute(stats)
                            lastConnection = stats
                            routeOutcomes.merge(stats.outcome, 1, Int::plus)
                        }, frontage, streets)
                    val paid = retained.copy(reservedFortificationPorts = access.reservedFortificationPorts)
                    val otherPlots = plots.filter { it.building.id != id }.map { it.envelope }
                    val reuse = CivsAutonomousTerraform.paidApproach(city, target, connected, adoptedFrame,
                        surface, paid, otherPlots, routes.values)
                    if (reuse == null) connected else try {
                        // A real SITE hall owns its entrance, not the projected ACTIVE hall's flat apron.
                        // Validate the paid grades against the actual town and physical obstacles.
                        CivsAutonomousTerraform.draft(city, CivTerraformOption(target, 0), surface, paid,
                            obstacles + otherPlots, reservedRoute = reuse.route).route
                    } catch (failure: CivsRejection) {
                        if (failure.reason !in setOf("autonomy-no-path", "autonomy-terrain", "autonomy-slope",
                                "building-overlap", "outside-world", "autonomy-search-budget")) throw failure
                        connected
                    }
                }
                catch (failure: CivsRejection) {
                    if (inspect != null) runCatching {
                        inspect(CivSettlementPlan(VERSION, 0, adoptedFrame.halfWidth, plots.toList(),
                            (routes + reconnect).mapKeys { it.key.toString() }, adoptedFrame.wallObstacles,
                            adoptedFrame.gates.map { it.passage }, adoptedFrame.gates.map { it.side }),
                            CivSettlementInfrastructure(emptyList(), mapOf("gate-connection-failed" to 1), false))
                    }.onFailure(failure::addSuppressed)
                    if (failure.reason != "autonomy-no-path") throw failure
                    fun coordinates(p: CivPosition) = "${p.x},${p.y},${p.z}"
                    throw CivsRejection(failure.reason, failure.values +
                        mapOf("stage" to "gate-connection", "fully_loaded" to fullyLoadedSurvey(city, surface).toString(),
                            "half_width" to adoptedFrame.halfWidth.toString()) +
                        routeOutcomes.mapValues { it.value.toString() } +
                        mapOf("type" to target.type.name, "plot" to id.toString(),
                            "entrance" to coordinates(target.entrance), "from" to coordinates(lastConnection?.from ?: previous.first()),
                            "to" to coordinates(lastConnection?.to ?: target.entrance)))
                        .also { it.initCause(failure) }
                }
            }
        }
        // A legacy accepted frame keeps its gate approaches. New gates already lie on real exterior routes.
        val connectionFrame = if (candidateFrame == null) frame else adoptedFrame
        // An incomplete outline may have paid wall pieces but no safe gate approach yet.
        val retainingDeferred = adopted?.deferredPerimeter != null && !expanding && candidateFrame == null
        for (gate in if (retained.infrastructure.isEmpty() || retainingDeferred) emptyList() else connectionFrame.gates) {
            val id = UUID.nameUUIDFromBytes("arccivs-plan/${city.id}/gate/${gate.side}/${gate.center.x}/${gate.center.z}".toByteArray(Charsets.UTF_8))
            val existing = retained.infrastructure.filter { it.type == BuildingType.GATE }.flatMap { it.transit }
                .firstOrNull { it.x == gate.center.x && it.z == gate.center.z }
            val paid = if (candidateFrame == null) null else retained.routes.values.asSequence().flatten()
                .firstOrNull { it.x == gate.center.x && it.z == gate.center.z && surface.at(it.x, it.z)?.let { native ->
                    native.loaded && native.topY == native.groundY && native.groundY == it.y - 1
                } == true }
            val point = existing ?: paid ?: surface.at(gate.center.x, gate.center.z)?.takeIf { it.loaded && it.natural }
                ?.let { gate.center.copy(y = it.groundY + 1) } ?: throw CivsRejection("autonomy-terrain")
            val target = CivBuilding(id, BuildingType.GATE, 1, point, 0, "civs/generated/gate",
                CivBox(point, point), point, BuildingPhase.CONSTRUCTING, transit = listOf(point))
            routes[id] = CivsAutonomousTerraform.connection(projected, target, surface,
                planningAccess(), connectionFrame.wallObstacles + CivsSettlementLayout.gateConnectionObstacles(connectionFrame), inspectRoute)
        }
        val roads = routes.values.toList()
        val plan = CivSettlementPlan(VERSION, (city.autonomy?.plan?.revision ?: -1) + 1, frame.halfWidth,
            plots.toList(), routes.mapKeys { it.key.toString() }.mapValues { it.value.toList() },
            adoptedFrame.wallObstacles.toList(), adoptedFrame.gates.map { it.passage }, adoptedFrame.gates.map { it.side })
        val forecast = projected.copy(autonomy = projected.autonomy?.copy(plan = plan, layoutHalfWidth = plan.halfWidth))
        val protectedRoads = if (candidateFrame == null) roads else roads + retained.routes.values
        val infrastructure = CivsSettlementLayout.infrastructure(forecast, adoptedFrame, surface, protectedRoads,
            retained.infrastructure, roads, retained.routes.values, acceptedFortifications = retained.acceptedFortifications,
            forecastFortifications = fortificationProofs.keys)
        inspect?.invoke(plan, infrastructure)
        if (!infrastructure.perimeterClosed || infrastructure.rejected.isNotEmpty() || infrastructure.pieces.size > CivsRules.MAX_INFRASTRUCTURE)
            throw CivsRejection("autonomy-layout-obstructed", infrastructure.rejected.mapValues { it.value.toString() } +
                mapOf("stage" to "infrastructure", "closed" to "${infrastructure.perimeterClosed}",
                    "pieces" to "${infrastructure.pieces.size}", "wall_strips" to "${adoptedFrame.wallObstacles.size}") +
                infrastructure.pieces.groupingBy { it.type.name }.eachCount().mapValues { it.value.toString() })
        if (candidateFrame != null) CivsRetainedGrowthPerimeter.validateResult(adoptedFrame, retained, infrastructure, protectedRoads)
        return plan.also { validate(city, it) }
    }

    private fun actualPlot(city: Settlement, building: CivBuilding,
        templates: Map<BuildingType, Pair<CivBuilding, CivBuilding>>): CivSettlementPlot {
        val future = templates[building.type]?.second?.let { at(it, building.anchor, building.rotation, building.id) }
        val bounds = if (building.type == BuildingType.PLAN_BOARD) CivsPlanBoardBlueprint.futureBounds(building)
            else union(CivsAutonomousGeometry.futureBounds(city, building), future?.bounds ?: building.bounds)
        return CivSettlementPlot(building.copy(phase = BuildingPhase.SITE), bounds)
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
