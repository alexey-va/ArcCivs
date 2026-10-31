package ru.arc.civs

import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsOperatingPlotPreferenceTest : FunSpec({
    test("a larger new frame samples eleven bounded depot aligned offsets while a legacy frame keeps its smaller span") {
        val base = settlementPlanCity().copy(radius = 128)
        val warehouse = CivsSettlementPlans.at(settlementPlanTemplates().getValue(BuildingType.WAREHOUSE).first,
            CivPosition(-40, 64, 0), 0, UUID(141, 250)).copy(phase = BuildingPhase.ACTIVE)
        val city = base.copy(buildings = base.buildings + warehouse)
        val pair = settlementPlanTemplates().getValue(BuildingType.FORESTER)
        val current = pair.first.copy(bounds = CivBox(CivPosition(-5, -1, -16), CivPosition(9, 9, -1)))
        val future = current.copy(level = 3)
        val choices = CivsSettlementLayout.candidates(city, current, future)
        (choices.size in 1..64) shouldBe true
        choices.any { it.building.entrance.x == -80 && kotlin.math.abs(it.building.entrance.z) == 60 } shouldBe true
        choices.all { CivsRules.workRouteWithinReach(city, it.building.entrance) } shouldBe true
        val legacy = city.copy(autonomy = CivAutonomy(layoutHalfWidth = 56))
        CivsSettlementLayout.candidates(legacy, current, future).none {
            it.building.entrance.x == -64 && kotlin.math.abs(it.building.entrance.z) > 36
        } shouldBe true
        warehouse.phase shouldBe BuildingPhase.ACTIVE
        city.buildings shouldBe base.buildings + warehouse
    }

    test("a saved wall also protects the doorway apron when the house envelope has the required gap") {
        val base = settlementPlanCity().copy(autonomy = CivAutonomy(layoutHalfWidth = 56))
        val initial = CivsSettlementLayout.frame(base)
        val wall = CivBox(CivPosition(-52, 64, -28), CivPosition(52, 64, -28))
        val old = CivSettlementPlan(CivsSettlementPlans.VERSION, 7, 56,
            listOf(CivSettlementPlot(base.buildings.single(), base.buildings.single().bounds)), emptyMap(),
            initial.wallObstacles + wall, initial.gates.map { it.passage }, initial.gates.map { it.side })
        val city = base.copy(autonomy = base.autonomy!!.copy(plan = old))
        val pair = settlementPlanTemplates().getValue(BuildingType.HOUSE)
        val rejected = CivsSettlementPlans.at(pair.second, CivPosition(0, 64, -30), 0)
        CivsAutonomousGeometry.overlaps(rejected.bounds, wall, 2) shouldBe false
        CivsAutonomousGeometry.approach(rejected).any { CivsAutonomousGeometry.horizontalContains(wall, it) } shouldBe true
        val choices = CivsSettlementLayout.infill(city, pair.first, pair.second, eligible = {
            it.entrance.x == 0 && (it.entrance.z == -30 && it.rotation == 0 ||
                it.entrance.z == -24 && it.rotation == 180)
        })
        choices.map { it.building.entrance to it.building.rotation } shouldBe listOf(CivPosition(0, 65, -24) to 180)
        CivsSettlementPlans.current(city) shouldBe old
        city.autonomy!!.plan!!.walls shouldBe initial.wallObstacles + wall
        city.autonomy!!.plan!!.revision shouldBe 7L
        city.buildings shouldBe base.buildings
    }

    test("a small forester leaves the remaining broad dry frontage available for a future farm") {
        val base = settlementPlanCity().copy(radius = 128)
        val templates = settlementPlanTemplates()
        val farm = templates.getValue(BuildingType.FARM).second.copy(
            bounds = CivBox(CivPosition(-20, -1, -29), CivPosition(31, 9, -1)))
        val quarry = templates.getValue(BuildingType.QUARRY).second.copy(
            bounds = CivBox(CivPosition(-10, -6, -22), CivPosition(10, 17, -1)))
        val firstFarm = CivsSettlementPlans.at(farm, CivPosition(-80, 64, 0), 270, UUID(140, 100)).copy(phase = BuildingPhase.SITE)
        val firstQuarry = CivsSettlementPlans.at(quarry, CivPosition(0, 64, 80), 180, UUID(140, 101)).copy(phase = BuildingPhase.SITE)
        val city = base.copy(buildings = base.buildings + firstFarm + firstQuarry)
        val forester = templates.getValue(BuildingType.FORESTER).second.copy(
            bounds = CivBox(CivPosition(-5, -1, -16), CivPosition(9, 9, -1)))
        // A detached shoreline excludes the eastern plots; it does not change the candidate budget.
        val dry: (CivBuilding) -> Boolean = { it.bounds.max.x <= 40 }
        val choices = CivsSettlementLayout.candidates(city, forester, forester, eligible = dry)
        choices.isNotEmpty() shouldBe true
        (choices.size <= 64) shouldBe true
        val graded = CivsAutonomousTerraform.options(city, choices, settlementPlanSurface(city))
        graded.isNotEmpty() shouldBe true
        val chosen = graded.first().building.copy(phase = BuildingPhase.SITE)
        chosen.entrance shouldBe CivPosition(-80, 65, 36)
        val next = city.copy(buildings = city.buildings + chosen)
        val farmChoices = CivsSettlementLayout.candidates(next, farm, farm, eligible = dry)
        farmChoices.any { it.building.entrance.x == 0 && it.building.entrance.z == -80 } shouldBe true
        CivsRules.workRouteWithinReach(city, chosen.entrance) shouldBe true
        city.buildings shouldBe base.buildings + firstFarm + firstQuarry
    }

    test("a graded small quarry reserves a bounded flank while a broad dry farm parcel is still missing") {
        val base = settlementPlanCity().copy(radius = 128)
        val templates = settlementPlanTemplates()
        val farm = templates.getValue(BuildingType.FARM).second.copy(
            bounds = CivBox(CivPosition(-20, -1, -29), CivPosition(31, 9, -1)))
        val firstFarm = CivsSettlementPlans.at(farm, CivPosition(-80, 64, 0), 270, UUID(141, 201)).copy(phase = BuildingPhase.SITE)
        val city = base.copy(buildings = base.buildings + firstFarm)
        val quarry = templates.getValue(BuildingType.QUARRY).second.copy(
            bounds = CivBox(CivPosition(-10, -6, -22), CivPosition(10, 17, -1)))
        val dry: (CivBuilding) -> Boolean = { it.bounds.max.x <= 40 }
        val choices = CivsSettlementLayout.candidates(city, quarry, quarry, eligible = dry)
        (choices.size in 1..64) shouldBe true
        val options = CivsAutonomousTerraform.options(city, choices, settlementPlanSurface(city))
        options.isNotEmpty() shouldBe true
        val chosen = options.first().building.copy(phase = BuildingPhase.SITE)
        minOf(kotlin.math.abs(chosen.entrance.x), kotlin.math.abs(chosen.entrance.z)) shouldBe 36
        CivsRules.workRouteWithinReach(city, chosen.entrance) shouldBe true
        val next = city.copy(buildings = city.buildings + chosen)
        CivsSettlementLayout.candidates(next, farm, farm, eligible = dry).any {
            it.building.entrance.x == 0 && it.building.entrance.z == -80
        } shouldBe true
        city.buildings shouldBe base.buildings + firstFarm
        city.stock shouldBe base.stock
    }

    test("adaptive gates without the usual forester side still offer bounded exterior plots") {
        val city = settlementPlanCity().copy(radius = 128)
        val templates = settlementPlanTemplates().getValue(BuildingType.FORESTER)
        val frame = CivsSettlementLayout.frame(city).let { it.copy(gates = it.gates.filter { gate -> gate.side == 1 }) }
        val choices = CivsSettlementLayout.candidates(city, templates.first, templates.second, frame = frame)
        choices.isNotEmpty() shouldBe true
        (choices.size <= 64) shouldBe true
        city.buildings.size shouldBe 1
    }

    test("a large exterior quarry rejects the old small unpinned territory and retains its explicit legacy frame") {
        val city = settlementPlanCity()
        val current = settlementPlanTemplates().getValue(BuildingType.QUARRY).first.copy(
            bounds = CivBox(CivPosition(-10, -6, -22), CivPosition(10, 17, -1)))
        val future = current.copy(level = 3)
        CivsSettlementLayout.candidates(city, current, future) shouldBe emptyList()
        CivsSettlementLayout.candidates(city.copy(autonomy = CivAutonomy(layoutHalfWidth = 56)), current, future)
            .isNotEmpty() shouldBe true
        CivsSettlementLayout.candidates(city.copy(radius = 128), current, future).isNotEmpty() shouldBe true
        city.radius shouldBe 96
        city.buildings.size shouldBe 1
    }

    test("a second forester prefers a separate authored search zone without changing the first building") {
        val id = "fixture/forest-spacing"
        CivsWorksiteLayouts.installCatalog(mapOf(id to JsonParser.parseString(
            """{"forester":{"searchBounds":{"min":[-20,-4,-20],"max":[20,24,20]}}}""").asJsonObject))
        try {
            val templates = settlementPlanTemplates().getValue(BuildingType.FORESTER)
            val current = templates.first.copy(schematicId = id)
            val future = templates.second.copy(schematicId = id)
            val first = CivsSettlementPlans.at(future, CivPosition(-92, 64, 8), 270, UUID(139, 600))
                .copy(phase = BuildingPhase.ACTIVE)
            val base = settlementPlanCity()
            val city = base.copy(radius = 128, buildings = base.buildings + first,
                autonomy = CivAutonomy(layoutHalfWidth = 72))
            val choices = CivsSettlementLayout.candidates(city, current, future)
            choices.isNotEmpty() shouldBe true
            (choices.size <= 64) shouldBe true
            fun zone(building: CivBuilding): CivBox {
                val corners = listOf(CivPosition(-20, -4, -20), CivPosition(20, 24, 20),
                    CivPosition(-20, -4, 20), CivPosition(20, 24, -20)).map { CivsWorksiteLayouts.point(building, it) }
                return CivBox(CivPosition(corners.minOf { it.x }, corners.minOf { it.y }, corners.minOf { it.z }),
                    CivPosition(corners.maxOf { it.x }, corners.maxOf { it.y }, corners.maxOf { it.z }))
            }
            CivsAutonomousGeometry.overlaps(zone(first), zone(choices.first().building), 2) shouldBe false
            city.building(first.id) shouldBe first
            city.buildings.size shouldBe 2
        } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
    }
})
