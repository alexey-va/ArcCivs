package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsAutonomousSearchFailuresTest : FunSpec({
    val city = Settlement(UUID(0, 1), UUID(0, 2), "Retry", UUID(0, 3), CivPosition(0, 64, 0),
        48, "fixture", Stock(), emptyList(), emptyList(), autonomy = CivAutonomy())
    val goal = CivAutonomyGoal(BuildingType.HOUSE, 1, null, Stock(), Stock())

    test("unchanged geometric refusal survives economic revisions but expires") {
        val failures = CivsAutonomousSearchFailures(300_000)
        failures.remember(city, goal, "autonomy-no-site", 10)
        failures.blocked(city.copy(revision = 500, stock = Stock(stone = 10)), goal, 30_010) shouldBe true
        failures.blocked(city, goal, 300_009) shouldBe true
        failures.blocked(city, goal, 300_010) shouldBe false
    }

    test("changed geometry owner world or goal wakes a refused search") {
        val variants = listOf(city.copy(center = CivPosition(1, 64, 0)), city.copy(radius = 49),
            city.copy(owner = UUID(0, 4)), city.copy(worldId = UUID(0, 5)), city.copy(style = "another"))
        for (changed in variants) {
            val failures = CivsAutonomousSearchFailures()
            failures.remember(city, goal, "autonomy-no-site", 0)
            failures.blocked(changed, goal, 1) shouldBe false
        }
        for (changed in listOf(goal.copy(targetLevel = 2), goal.copy(type = BuildingType.WAREHOUSE),
                goal.copy(buildingId = UUID(0, 6)))) {
            val failures = CivsAutonomousSearchFailures()
            failures.remember(city, goal, "autonomy-no-path", 0)
            failures.blocked(city, changed, 1) shouldBe false
        }
    }

    test("terrain edits wake only affected cities including the context neighbour") {
        val failures = CivsAutonomousSearchFailures()
        val other = city.copy(id = UUID(0, 7), center = CivPosition(1000, 64, 1000))
        failures.remember(city, goal, "autonomy-no-site", 0)
        failures.remember(other, goal, "autonomy-no-site", 0)
        failures.changed(UUID(0, 99), city.center) shouldBe emptySet()
        failures.changed(city.worldId, CivPosition(50, 64, 0)) shouldBe emptySet()
        failures.blocked(city, goal, 1) shouldBe true
        failures.changed(city.worldId, CivPosition(49, -20, 0)) shouldBe setOf(city.id)
        failures.blocked(city, goal, 1) shouldBe false
        failures.blocked(other, goal, 1) shouldBe true
    }

    test("transient admission resources and native availability never become geometric refusals") {
        for (reason in listOf("world-busy", "city-busy", "stale-development", "chunks-unloaded",
                "not-enough-resources", "storage-unavailable")) {
            val failures = CivsAutonomousSearchFailures()
            failures.remember(city, goal, reason, 0)
            failures.blocked(city, goal, 1) shouldBe false
        }
    }

    test("asking or refusing ROAD does not erase the failed FARM input") {
        val failures = CivsAutonomousSearchFailures()
        val farm = goal.copy(type = BuildingType.FARM)
        val road = goal.copy(type = BuildingType.ROAD)
        failures.remember(city, farm, "autonomy-no-site", 0)
        failures.blocked(city, road, 1) shouldBe false
        failures.blocked(city, farm, 2) shouldBe true
        failures.remember(city, road, "autonomy-layout-complete", 3)
        failures.blocked(city, farm, 30_000) shouldBe true
        failures.blocked(city, road, 30_000) shouldBe true
        failures.debug(city.id)["cached_goals"] shouldBe 2
        failures.blocked(city, farm.copy(targetLevel = 2), 30_001) shouldBe false
        failures.blocked(city, farm, 30_002) shouldBe true
    }

    test("budget and layout refusals protect the same input but not the next recovery candidate") {
        val failures = CivsAutonomousSearchFailures()
        for (reason in listOf("autonomy-search-budget", "autonomy-layout-obstructed")) {
            failures.remember(city, goal, reason, 0, candidate = 0)
            failures.blocked(city, goal, 30_001, candidate = 0) shouldBe true
            failures.blocked(city, goal, 30_001, candidate = 1) shouldBe false
            failures.blocked(city, goal, 30_001) shouldBe false
            failures.remember(city, goal, reason, 30_001, candidate = 1)
            failures.blocked(city, goal, 30_002, candidate = 0) shouldBe true
            failures.blocked(city, goal, 30_002, candidate = 1) shouldBe true
            failures.clear()
        }
    }

    test("many refusals remain bounded and a new layout invalidates every old goal") {
        val failures = CivsAutonomousSearchFailures()
        repeat(160) { failures.remember(city, goal, "autonomy-no-path", 0, candidate = it) }
        failures.debug(city.id)["cached_goals"] shouldBe 128
        failures.blocked(city, goal, 1, candidate = 0) shouldBe false
        failures.blocked(city, goal, 1, candidate = 159) shouldBe true
        failures.blocked(city.copy(radius = 49), goal, 2, candidate = 159) shouldBe false
        failures.debug(city.id)["cached_goals"] shouldBe 0
        failures.remember(city, goal, "autonomy-no-site", 10)
        failures.blocked(city, goal.copy(type = BuildingType.ROAD), 300_010) shouldBe false
        failures.debug(city.id)["cached_goals"] shouldBe 0
    }

    test("only statically excluded interiors ignore edits and entrance goal and boundary edits wake") {
        val building = CivBuilding(UUID(0, 10), BuildingType.QUARRY, 1, city.center, 0, "fixture",
            CivBox(CivPosition(-10, 63, -10), CivPosition(10, 75, 10)), CivPosition(0, 64, -10),
            BuildingPhase.ACTIVE, control = city.center)
        val occupied = city.copy(buildings = listOf(building))
        val failures = CivsAutonomousSearchFailures()
        val road = goal.copy(type = BuildingType.ROAD)
        failures.remember(occupied, goal, "autonomy-no-site", 0)
        failures.remember(occupied, road, "autonomy-no-path", 0)
        failures.changed(city.worldId, CivPosition(5, -40, 5)) shouldBe emptySet()
        failures.blocked(occupied, goal, 1) shouldBe true
        failures.blocked(occupied, road, 1) shouldBe true
        for (point in listOf(building.entrance, building.control!!, CivPosition(9, 64, 5), CivPosition(20, -40, 5))) {
            failures.remember(occupied, goal, "autonomy-no-site", 0)
            failures.changed(city.worldId, point) shouldBe setOf(city.id)
            failures.blocked(occupied, goal, 1) shouldBe false
        }
        val upgrade = goal.copy(type = BuildingType.QUARRY, targetLevel = 2, buildingId = building.id)
        failures.remember(occupied, upgrade, "autonomy-no-site", 0)
        failures.changed(city.worldId, CivPosition(5, -40, 5)) shouldBe setOf(city.id)
        failures.blocked(occupied, upgrade, 1) shouldBe false
    }

    test("authored crop columns inside the protected future field do not repeat unrelated planning") {
        val farm = CivBuilding(UUID(0, 11), BuildingType.FARM, 1, city.center, 0,
            "civs/minecolonies/nordic/agriculture/horticulture/farmer1",
            CivBox(CivPosition(-7, 64, -23), CivPosition(7, 83, -1)), CivPosition(0, 64, -1), BuildingPhase.ACTIVE)
        val occupied = city.copy(buildings = listOf(farm))
        val bounds = CivsAutonomousGeometry.futureBounds(occupied, farm)
        val soil = CivsWorksiteLayouts.field(farm).first { it.x > bounds.min.x + 1 && it.x < bounds.max.x - 1 &&
            it.z > bounds.min.z + 1 && it.z < bounds.max.z - 1 && !farm.bounds.contains(it.x, it.y, it.z) }
        val failures = CivsAutonomousSearchFailures()
        failures.remember(occupied, goal, "autonomy-no-site", 0)
        failures.changed(city.worldId, soil.copy(y = soil.y + 1)) shouldBe emptySet()
        failures.blocked(occupied, goal, 1) shouldBe true
        failures.changed(city.worldId, CivPosition(bounds.max.x + 1, soil.y, soil.z)) shouldBe setOf(city.id)
    }

    test("pause reload and city retirement discard remembered refusals") {
        val failures = CivsAutonomousSearchFailures()
        failures.remember(city, goal, "autonomy-no-site", 0)
        failures.remove(city.id)
        failures.blocked(city, goal, 1) shouldBe false
        failures.remember(city, goal, "autonomy-no-site", 0)
        failures.retain(emptySet())
        failures.blocked(city, goal, 1) shouldBe false
        failures.remember(city, goal, "autonomy-no-site", 0)
        failures.clear()
        failures.blocked(city, goal, 1) shouldBe false
    }
})
