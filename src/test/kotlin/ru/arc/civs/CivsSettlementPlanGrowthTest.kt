package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CivsSettlementPlanGrowthTest : FunSpec({
    val legacy by lazy { legacyGrowthCity() }

    test("a roomy sixteen plot plan appends eighteen plots without moving old unpaid intent or its perimeter") {
        val before = legacy
        val old = requireNotNull(CivsSettlementPlans.current(before))
        old.plots.size shouldBe 16
        old.halfWidth shouldBe 56
        old.routes.values.flatten().none { point -> old.walls.any { CivsAutonomousGeometry.horizontalContains(it, point) } } shouldBe true
        CivsSettlementLayout.frame(before.copy(autonomy = before.autonomy!!.copy(layoutHalfWidth = null))).halfWidth shouldBe 56
        CivsSettlementPlans.needsExtension(before) shouldBe true
        val templates = settlementPlanTemplates()
        val surface = settlementPlanSurface(before)
        val next = try {
            CivsSettlementPlans.prepare(before, templates, surface, CivAutonomousAccess(),
                inspect = layoutFixtureDiagnostics("growth", before, templates, surface))
        } catch (failure: CivsRejection) { throw AssertionError("${failure.reason}: ${failure.values}", failure) }
        next.plots.size shouldBe 34
        next.plots.count { it.building.type == BuildingType.HOUSE } shouldBe 16
        next.plots.take(old.plots.size) shouldBe old.plots
        next.walls shouldBe old.walls
        next.gates shouldBe old.gates
        next.gateSides shouldBe old.gateSides
        next.halfWidth shouldBe 56
        old.routes.forEach { (id, route) -> next.routes[id] shouldBe route }
        val adopted = before.copy(autonomy = before.autonomy!!.copy(plan = next))
        CivsSettlementPlans.needsExtension(adopted) shouldBe false
        CivsSettlementLayout.frame(adopted).halfWidth shouldBe 56
        CivsRules.validate(Gson().fromJson(Gson().toJson(adopted), Settlement::class.java)) shouldBe adopted
        before.autonomy!!.plan shouldBe old
        before.buildings.size shouldBe 1
        before.stock shouldBe Stock()
        before.workers shouldBe emptyList()
    }

    test("a bounded greedy refusal of a compact retained subset keeps the old town and its accepted work usable") {
        // These parcels came from a feasible full plan. Refusal is this finite replan's limit, not proof of no physical space.
        val before = legacyGrowthCity(roomy = false)
        val old = requireNotNull(CivsSettlementPlans.current(before))
        val serialized = Gson().toJson(before)
        shouldThrow<CivsRejection> {
            CivsSettlementPlans.prepare(before, settlementPlanTemplates(), settlementPlanSurface(before), CivAutonomousAccess())
        }.reason shouldBe "autonomy-no-site"
        Gson().toJson(before) shouldBe serialized
        CivsSettlementPlans.current(before) shouldBe old
        before.revision shouldBe 0L
        before.constructionQueue shouldBe null
        CivsRules.validate(before) shouldBe before

        val plot = requireNotNull(CivsSettlementPlans.next(before, BuildingType.HOUSE))
        val admitted = CivsRules.placeSite(before, before.owner, plot.building)
        val queued = CivsRules.enqueue(admitted, admitted.owner, plot.building.id)
        queued.constructionQueue shouldBe listOf(plot.building.id)
        queued.autonomy!!.plan shouldBe old
        CivsSettlementPlans.needsExtension(queued) shouldBe false
        CivsRules.validate(queued) shouldBe queued

        val templates = settlementPlanTemplates()
        val established = before.copy(buildings = old.plots.map {
            CivsSettlementPlans.at(templates.getValue(it.building.type).second, it.building.anchor,
                it.building.rotation, it.building.id).copy(phase = BuildingPhase.ACTIVE)
        }, research = CivResearchState(CivResearchId.entries.toSet()))
        val forester = established.buildings.first { it.type == BuildingType.FORESTER }.copy(level = 2)
        val upgradeable = established.copy(buildings = established.buildings.map { if (it.id == forester.id) forester else it })
        CivsRules.validate(upgradeable) shouldBe upgradeable
        CivsAutonomy.nextGoal(upgradeable)?.let { Triple(it.type, it.targetLevel, it.buildingId) } shouldBe
            Triple(BuildingType.FORESTER, 3, forester.id)
        upgradeable.autonomy!!.plan shouldBe old
    }

    test("accepted orders and FIFO entries defer extension and a failed search keeps all old intent") {
        val before = legacy
        val plot = requireNotNull(CivsSettlementPlans.next(before, BuildingType.HOUSE))
        val queued = before.copy(buildings = before.buildings + plot.building,
            constructionQueue = listOf(plot.building.id))
        CivsSettlementPlans.needsExtension(queued) shouldBe false
        val order = CivConstruction(plot.building.id, plot.building.id, 1, 1, 0,
            ConstructionPhase.CONSTRUCTING, Stock(), plannedBuilding = plot.building)
        CivsSettlementPlans.needsExtension(before.copy(construction = order)) shouldBe false
        val blocked = settlementPlanSurface(before).let { it.copy(columns = it.columns.mapValues { (_, column) ->
            column.copy(natural = false)
        }) }
        val snapshot = Gson().toJson(before)
        shouldThrow<CivsRejection> {
            CivsSettlementPlans.prepare(before, settlementPlanTemplates(), blocked, CivAutonomousAccess())
        }.reason shouldBe "autonomy-no-site"
        Gson().toJson(before) shouldBe snapshot
        CivsSettlementPlans.current(before) shouldBe before.autonomy!!.plan
    }
})

/** A saved V1 profile: the old three houses and all other ordinary plots keep their original identities. */
internal fun legacyGrowthCity(roomy: Boolean = true): Settlement {
    val city = settlementPlanCity().copy(autonomy = CivAutonomy(layoutHalfWidth = 56))
    val plan = CivsSettlementPlans.prepare(city, settlementPlanTemplates(), settlementPlanSurface(city), CivAutonomousAccess())
    val needed = (CivsAutonomy.profile + List(2) { BuildingType.HOUSE }).toMutableList()
    val retained = plan.plots.filter { needed.remove(it.building.type) }
    val removed = plan.plots.filterNot { it in retained }.mapTo(hashSetOf()) { it.building.id.toString() }
    val routes = plan.routes.filterKeys { it !in removed }
    val frame = if (roomy) {
        val initial = CivsSettlementLayout.frame(city)
        // The old ring is authored before adoption. Derive all its openings from the roads it actually retains.
        val closed = initial.copy(gates = emptyList(), wallObstacles = initial.wallObstacles + initial.gates.map { gate ->
            if (gate.side % 2 == 0) gate.passage.copy(min = gate.passage.min.copy(z = gate.center.z - 1),
                max = gate.passage.max.copy(z = gate.center.z + 1)) else
                gate.passage.copy(min = gate.passage.min.copy(x = gate.center.x - 1), max = gate.passage.max.copy(x = gate.center.x + 1))
        })
        CivsPerimeterCrossings.adapt(closed, routes.values, emptyList()).let { aligned -> aligned.copy(gates = aligned.gates.map { gate ->
            gate.copy(side = initial.gates.minBy { kotlin.math.abs(it.center.x - gate.center.x) +
                kotlin.math.abs(it.center.z - gate.center.z) }.side)
        }) }
    } else null
    return city.copy(autonomy = city.autonomy!!.copy(plan = plan.copy(plots = retained, routes = routes,
        walls = frame?.wallObstacles ?: plan.walls, gates = frame?.gates?.map { it.passage } ?: plan.gates,
        gateSides = frame?.gates?.map { it.side } ?: plan.gateSides)))
}
