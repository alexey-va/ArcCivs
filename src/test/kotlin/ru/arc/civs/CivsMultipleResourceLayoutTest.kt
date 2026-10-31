package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

class CivsMultipleResourceLayoutTest : FunSpec({
    test("runtime sized seven resource plots fit within reach when the first professions precede repeated large farms") {
        val city = settlementPlanCity().copy(radius = 128)
        // Runtime-selected Caledonia alfarmer is 41 columns wide; authored soil extends its envelope to 52.
        val envelopes = mapOf(
            BuildingType.FARM to CivBox(CivPosition(-20, -1, -29), CivPosition(31, 9, -1)),
            BuildingType.QUARRY to CivBox(CivPosition(-10, -6, -22), CivPosition(10, 17, -1)),
            BuildingType.FORESTER to CivBox(CivPosition(-5, -1, -16), CivPosition(9, 9, -1)))
        val templates = settlementPlanTemplates().mapValues { (type, pair) ->
            val bounds = envelopes[type] ?: return@mapValues pair
            pair.first.copy(bounds = bounds) to pair.second.copy(bounds = bounds)
        }
        val searches = mutableListOf<CivRouteSearchStats>()
        val surface = settlementPlanSurface(city)
        val plan = try {
            CivsSettlementPlans.prepare(city, templates, surface, CivAutonomousAccess(), inspectRoute = searches::add,
                inspect = layoutFixtureDiagnostics("multi-resource", city, templates, surface))
        } catch (failure: CivsRejection) { throw AssertionError("${failure.reason}: ${failure.values}", failure) }
        plan.plots.size shouldBe 34
        val resources = plan.plots.filter { it.building.type in CivsSettlementLayout.exteriorTypes }
        resources.size shouldBe 7
        resources.take(3).map { it.building.type }.toSet() shouldBe CivsSettlementLayout.exteriorTypes
        resources.any { minOf(abs(it.building.entrance.x), abs(it.building.entrance.z)) > 24 } shouldBe true
        val nativeDepots = city.buildings.filter(CivsRules::isDepot).mapTo(hashSetOf()) { it.entrance }
        val bootstrap = plan.plots.filter { it.building.type in CivsSettlementLayout.exteriorTypes + BuildingType.BUILDER_HUT }
            .distinctBy { it.building.type }
        bootstrap.all { CivsRules.workRouteWithinReach(city, it.building.entrance) &&
            plan.routes.getValue(it.building.id.toString()).first() in nativeDepots } shouldBe true
        val warehouses = plan.plots.filter { it.building.type == BuildingType.WAREHOUSE }
        warehouses.all { it.building.phase == BuildingPhase.SITE } shouldBe true
        val earlyDepots = nativeDepots + warehouses.first().building.entrance
        val early = plan.plots.filter { it.building.type !in CivsSettlementLayout.exteriorTypes &&
            it.building.type !in setOf(BuildingType.TOWN_HALL, BuildingType.BUILDER_HUT, BuildingType.HOUSE, BuildingType.WAREHOUSE) }
            .distinctBy { it.building.type } + plan.plots.filter { it.building.type == BuildingType.HOUSE }.take(3)
        early.all { plan.routes.getValue(it.building.id.toString()).first() in earlyDepots } shouldBe true
        val maximum = city.copy(buildings = plan.plots.map { plot -> plot.building.copy(bounds = plot.envelope,
            phase = if (plot.building.type == BuildingType.WAREHOUSE) BuildingPhase.ACTIVE else plot.building.phase) })
        resources.all { CivsRules.workRouteWithinReach(maximum, it.building.entrance) } shouldBe true
        searches.all { it.expanded <= CivsAutonomousTerraform.MAXIMUM_ROUTE_NODES } shouldBe true
        plan.plots.takeLast(16).all { it.building.type == BuildingType.HOUSE } shouldBe true
        city.buildings.size shouldBe 1
        city.stock shouldBe Stock()
        city.workers shouldBe emptyList()
    }
})
