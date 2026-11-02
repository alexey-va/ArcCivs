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
