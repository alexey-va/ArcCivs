package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsSurveyedHallApronTest : FunSpec({
    test("a farm approach preserves the surveyed lower builder route on a lateral Hall apron cell") {
        val fixture = SurveyedHallApronFixture()
        val old = shouldThrow<CivsRejection> { fixture.draft(fixture.access) }
        old.reason shouldBe "autonomy-no-path"
        old.values shouldBe mapOf("stage" to "retained-grade", "x" to "1", "z" to "1", "floor" to "63", "retained" to "64")

        val corrected = fixture.access.withSurveyedHallApron(fixture.city, fixture.surface)
        corrected.standing(fixture.city, fixture.farm.id).getValue(fixture.apronKey) shouldBe fixture.paidApron
        fixture.access.standing(fixture.city, fixture.farm.id).getValue(fixture.apronKey) shouldBe fixture.nominalApron
        corrected.routes shouldBe mapOf(fixture.builder.id to fixture.builderRoute)
        CivsSettlementPlans.current(fixture.city)?.routes shouldBe mapOf(fixture.builder.id.toString() to fixture.builderRoute)

        val draft = fixture.draft(corrected)
        draft.route shouldBe fixture.farmRoute
        draft.route.first() shouldBe fixture.hall.entrance
        val apron = draft.columns.single { it.x == fixture.paidApron.x && it.z == fixture.paidApron.z }
        apron.preserve shouldBe true
        apron.groundY shouldBe 63
        apron.floorY shouldBe 63
    }

    val paved = CivTerrainSurfaceColumn(63, natural = false, dirtPath = true)
    for ((name, column) in listOf(
        "missing" to null,
        "unloaded" to paved.copy(loaded = false),
        "nonmatching" to paved.copy(groundY = 64, topY = 64),
        "covered" to paved.copy(topY = 64),
        "protected" to paved.copy(dirtPath = false),
        "water" to paved.copy(waterTopY = 63, waterBottomY = 62),
    )) test("a $name survey column retains the original Hall apron standing grade") {
        val fixture = SurveyedHallApronFixture()
        val columns = fixture.surface.columns - fixture.apronKey
        val surface = fixture.surface.copy(columns = if (column == null) columns else columns + (fixture.apronKey to column))
        val corrected = fixture.access.withSurveyedHallApron(fixture.city, surface)
        corrected.standing(fixture.city, fixture.farm.id).getValue(fixture.apronKey) shouldBe fixture.nominalApron
    }

    test("conflicting paid routes retain the Hall apron grade even when one route matches the survey") {
        val fixture = SurveyedHallApronFixture()
        val house = CivBuilding(UUID(891, 8), BuildingType.HOUSE, 1, CivPosition(1, 64, 8), 180, "fixture/house",
            CivBox(CivPosition(0, 64, 9), CivPosition(2, 70, 12)), CivPosition(1, 65, 8), BuildingPhase.ACTIVE)
        val otherRoute = fixture.builderRoute.take(2) + (1..8).map { z -> CivPosition(1, 65, z) }
        val city = fixture.city.copy(buildings = fixture.city.buildings + house)
        val access = CivAutonomousAccess.from(city, listOf(fixture.builderReceipt, fixture.receipt(house, otherRoute)))
        access.routes shouldBe mapOf(fixture.builder.id to fixture.builderRoute, house.id to otherRoute)
        access.withSurveyedHallApron(city, fixture.surface).standing(city, fixture.farm.id).getValue(fixture.apronKey) shouldBe fixture.nominalApron
    }

    test("surveyed route evidence cannot move the Hall entrance or shift a lateral apron two blocks") {
        val fixture = SurveyedHallApronFixture()
        val warehouse = CivBuilding(UUID(891, 9), BuildingType.WAREHOUSE, 1, CivPosition(0, 63, 8), 180, "fixture/warehouse",
            CivBox(CivPosition(-2, 63, 9), CivPosition(2, 70, 12)), CivPosition(0, 64, 8), BuildingPhase.ACTIVE)
        val city = fixture.city.copy(buildings = fixture.city.buildings + warehouse)
        val route = (8 downTo 0).map { z -> CivPosition(0, 64, z) } +
            listOf(CivPosition(1, 64, 0), fixture.paidApron.copy(y = 63)) + fixture.builderRoute.drop(3)
        val entranceKey = civsTerrainKey(fixture.hall.entrance.x, fixture.hall.entrance.z)
        val surface = fixture.surface.copy(columns = fixture.surface.columns +
            (entranceKey to paved) + (fixture.apronKey to paved.copy(groundY = 62, topY = 62)))
        val access = CivAutonomousAccess.from(city, listOf(fixture.receipt(fixture.builder, route)))
        access.routes shouldBe mapOf(fixture.builder.id to route)
        val standing = access.withSurveyedHallApron(city, surface).standing(city, fixture.farm.id)
        standing.getValue(entranceKey) shouldBe fixture.hall.entrance
        standing.getValue(fixture.apronKey) shouldBe fixture.nominalApron
    }
})

private class SurveyedHallApronFixture {
    val hall = CivBuilding(UUID(891, 1), BuildingType.TOWN_HALL, 1, CivPosition(0, 64, 0), 0, "fixture/hall",
        CivBox(CivPosition(-2, 64, -5), CivPosition(2, 70, -1)), CivPosition(0, 65, 0), BuildingPhase.ACTIVE)
    val builder = CivBuilding(UUID(891, 2), BuildingType.BUILDER_HUT, 1, CivPosition(10, 63, 1), 90, "fixture/builder",
        CivBox(CivPosition(11, 63, -1), CivPosition(13, 70, 3)), CivPosition(10, 64, 1), BuildingPhase.ACTIVE)
    val farm = CivBuilding(UUID(891, 3), BuildingType.FARM, 1, CivPosition(4, 63, 10), 180, "fixture/farm",
        CivBox(CivPosition(3, 63, 11), CivPosition(5, 70, 14)), CivPosition(4, 64, 10), BuildingPhase.SITE)
    val paidApron = CivPosition(1, 64, 1)
    val nominalApron = paidApron.copy(y = hall.entrance.y)
    val apronKey = civsTerrainKey(paidApron.x, paidApron.z)
    val builderRoute = listOf(hall.entrance, CivPosition(0, 65, 1)) + (1..10).map { x -> CivPosition(x, 64, 1) }
    val farmRoute = builderRoute.take(6) + (2..10).map { z -> CivPosition(4, 64, z) }
    private val base = Settlement(UUID(891, 4), UUID(891, 5), "Surveyed Hall apron", UUID(891, 6), hall.anchor,
        32, "fixture", Stock(), listOf(hall, builder), emptyList(), autonomy = CivAutonomy())
    private val frame = CivsSettlementLayout.frame(base)
    private val plan = CivSettlementPlan(CivsSettlementPlans.VERSION, 1, frame.halfWidth,
        base.buildings.map { CivSettlementPlot(it.copy(phase = BuildingPhase.SITE), it.bounds) },
        mapOf(builder.id.toString() to builderRoute), frame.wallObstacles, frame.gates.map { it.passage }, frame.gates.map { it.side })
    val city = base.copy(autonomy = CivAutonomy(plan = plan, layoutHalfWidth = frame.halfWidth))
    val builderReceipt = receipt(builder, builderRoute)
    val access = CivAutonomousAccess.from(city, listOf(builderReceipt))
    val surface = CivTerrainSurface(-64, 320, buildMap {
        for (x in -6..16) for (z in -6..16) put(civsTerrainKey(x, z), CivTerrainSurfaceColumn(64, natural = true))
        farmRoute.forEach { point -> put(civsTerrainKey(point.x, point.z), CivTerrainSurfaceColumn(point.y - 1, natural = true)) }
        builderRoute.drop(2).forEach { point ->
            put(civsTerrainKey(point.x, point.z), CivTerrainSurfaceColumn(point.y - 1, natural = false, dirtPath = true))
        }
    })

    fun receipt(building: CivBuilding, route: List<CivPosition>): CivPlacementPlan {
        val paving = route.drop(1).map { feet -> CivPlanBlock(feet.copy(y = feet.y - 1), "minecraft:grass_block[snowy=false]", "minecraft:dirt_path") }
        val terrain = CivTerraformPlan(CivBox(CivPosition(-6, 60, -6), CivPosition(16, 70, 16)), paving, route, 64, 0, 0)
        return CivPlacementPlan(building.id, base.id, base.worldId, building.copy(phase = BuildingPhase.CONSTRUCTING),
            listOf(CivPlanBlock(building.bounds.min, "minecraft:air", "minecraft:spruce_planks")), terrain = terrain)
    }

    fun draft(access: CivAutonomousAccess): CivTerraformDraft = CivsAutonomousTerraform.draft(city, CivTerraformOption(farm, 0),
        surface, access, reservedRoute = farmRoute)
}
