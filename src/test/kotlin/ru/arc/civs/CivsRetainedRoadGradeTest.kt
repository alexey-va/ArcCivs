package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsRetainedRoadGradeTest : FunSpec({
    test("a dry house apron can match one retained road grade omitted by median plus or minus four") {
        val fixture = retainedRoadGradeFixture()
        val before = Gson().toJson(fixture)
        val choices = listOf(CivsAutonomousCandidate(fixture.house))
        val ordinary = CivsAutonomousTerraform.options(fixture.city, choices, fixture.surface)
        ordinary.map { it.building.anchor.y }.toSet() shouldBe setOf(66, 62, 70)
        ordinary.forEach { option ->
            shouldThrow<CivsRejection> {
                CivsAutonomousTerraform.draft(fixture.city, option, fixture.surface, fixture.access)
            }.reason shouldBe "autonomy-no-path"
        }
        val aligned = CivsAutonomousGeometry.atHeight(fixture.house, 69)
        val safe = CivsAutonomousTerraform.draft(fixture.city, CivTerraformOption(aligned, 0), fixture.surface, fixture.access)
        safe.building.entrance.y shouldBe 70
        safe.route.first() shouldBe fixture.city.buildings.first().entrance
        safe.route.last() shouldBe aligned.entrance
        safe.columns.all { column -> kotlin.math.abs(column.groundY - 66) <= CivsAutonomousTerraform.MAXIMUM_CHANGE } shouldBe true
        val standing = fixture.access.standing(fixture.city, fixture.house.id)
        safe.columns.filter { civsTerrainKey(it.x, it.z) in standing }.all { column ->
            column.groundY == standing.getValue(civsTerrainKey(column.x, column.z)).y - 1
        } shouldBe true
        val offered = CivsAutonomousTerraform.options(fixture.city, choices, fixture.surface, standing = standing)
        offered.map { it.building.anchor.y }.toSet() shouldBe setOf(66, 62, 70, 69)
        offered.count { it.building.anchor.y == 69 } shouldBe 1
        val selected = offered.single { it.building.anchor.y == 69 }
        CivsAutonomousTerraform.draft(fixture.city, selected, fixture.surface, fixture.access) shouldBe safe.copy(building = selected.building)
        Gson().toJson(fixture) shouldBe before
    }

    test("conflicting retained apron heights add no invented compromise grade") {
        val fixture = retainedRoadGradeFixture()
        val standing = fixture.access.standing(fixture.city, fixture.house.id).toMutableMap().apply {
            put(civsTerrainKey(0, 2), CivPosition(0, 71, 2))
        }
        val before = Gson().toJson(standing)
        val offered = CivsAutonomousTerraform.options(fixture.city, listOf(CivsAutonomousCandidate(fixture.house)),
            fixture.surface, standing = standing)
        offered.map { it.building.anchor.y }.toSet() shouldBe setOf(66, 62, 70)
        Gson().toJson(standing) shouldBe before
    }

    test("fixed reservations keep their original height and existing grade choices are not duplicated") {
        val fixture = retainedRoadGradeFixture()
        val choices = listOf(CivsAutonomousCandidate(fixture.house))
        val standing = fixture.access.standing(fixture.city, fixture.house.id)
        val fixed = CivsAutonomousTerraform.options(fixture.city, choices, fixture.surface, fixed = true, standing = standing)
        fixed.map { it.building.anchor.y } shouldBe listOf(fixture.house.anchor.y)
        fixed.single().building shouldBe fixture.house
        val matching = standing.mapValues { (_, point) -> point.copy(y = 67) }
        val offered = CivsAutonomousTerraform.options(fixture.city, choices, fixture.surface, standing = matching)
        offered.map { it.building.anchor.y }.toSet() shouldBe setOf(66, 62, 70)
        offered.size shouldBe 3
    }

    test("a retained road cannot admit protected unloaded unreachable or overcut house terrain") {
        val fixture = retainedRoadGradeFixture()
        val choices = listOf(CivsAutonomousCandidate(fixture.house))
        val standing = fixture.access.standing(fixture.city, fixture.house.id)
        val key = civsTerrainKey(0, -3)
        for (column in listOf(CivTerrainSurfaceColumn(66, false), CivTerrainSurfaceColumn(66, true, loaded = false))) {
            val surface = fixture.surface.copy(columns = fixture.surface.columns + (key to column))
            CivsAutonomousTerraform.options(fixture.city, choices, surface, standing = standing) shouldBe emptyList()
        }
        val highRoad = standing.mapValues { (_, point) -> point.copy(y = 100) }
        CivsAutonomousTerraform.options(fixture.city, choices, fixture.surface, standing = highRoad)
            .map { it.building.anchor.y }.toSet() shouldBe setOf(66, 62, 70)
        val far = fixture.house.copy(anchor = fixture.house.anchor.copy(x = 100),
            bounds = CivBox(fixture.house.bounds.min.copy(x = 97), fixture.house.bounds.max.copy(x = 103)),
            entrance = fixture.house.entrance.copy(x = 100), control = fixture.house.control!!.copy(x = 101))
        val surface = fixture.surface.copy(columns = buildMap {
            putAll(fixture.surface.columns)
            for (x in 97..103) for (z in -8..3) put(civsTerrainKey(x, z), CivTerrainSurfaceColumn(66, true))
        })
        val farRoad = CivsAutonomousGeometry.approach(far).associate { civsTerrainKey(it.x, it.z) to it.copy(y = 70) }
        CivsAutonomousTerraform.options(fixture.city, listOf(CivsAutonomousCandidate(far)), surface,
            standing = farRoad) shouldBe emptyList()
    }
})

private data class RetainedRoadGradeFixture(val city: Settlement, val house: CivBuilding,
    val surface: CivTerrainSurface, val access: CivAutonomousAccess)

private fun retainedRoadGradeFixture(): RetainedRoadGradeFixture {
    val hall = CivBuilding(UUID(159, 1), BuildingType.TOWN_HALL, 3, CivPosition(-10, 69, 2), 0, "fixture/hall",
        CivBox(CivPosition(-14, 68, -6), CivPosition(-6, 77, 1)), CivPosition(-10, 70, 2), BuildingPhase.ACTIVE,
        control = CivPosition(-9, 70, 1))
    val farm = CivBuilding(UUID(159, 2), BuildingType.FARM, 1, CivPosition(30, 69, 2), 0, "fixture/farm",
        CivBox(CivPosition(27, 68, -6), CivPosition(33, 77, 1)), CivPosition(30, 70, 2), BuildingPhase.SITE,
        control = CivPosition(31, 70, 1))
    val house = CivBuilding(UUID(159, 3), BuildingType.HOUSE, 1, CivPosition(0, 66, 0), 0, "fixture/house",
        CivBox(CivPosition(-3, 65, -8), CivPosition(3, 74, -1)), CivPosition(0, 67, 0), BuildingPhase.CONSTRUCTING,
        control = CivPosition(1, 67, -1))
    val city = CivsRules.validate(Settlement(UUID(159, 4), UUID(159, 5), "Retained grade", UUID(159, 6),
        CivPosition(0, 69, 0), 96, "nordic", Stock(), listOf(hall, farm), emptyList(),
        inventory = CivInventory(CivStorageMode.PHYSICAL), autonomy = CivAutonomy()))
    val route = (-10..30).map { CivPosition(it, 70, 2) }
    val access = CivAutonomousAccess(mapOf(farm.id to route))
    val surface = CivTerrainSurface(-64, 320, buildMap {
        for (x in -64..64) for (z in -64..64) put(civsTerrainKey(x, z), CivTerrainSurfaceColumn(66, true))
    })
    return RetainedRoadGradeFixture(city, house, surface, access)
}
