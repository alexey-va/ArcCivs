package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsAutonomousAccessRepairTest : FunSpec({
    test("later road fill over an older entrance yields only its exact inverse and no unrelated excavation") {
        val (city, plan) = accessRepairFixture()
        val feet = city.buildings.first().entrance
        val repaired = civsAutonomousAccessRepairs(city, listOf(plan))
        repaired shouldBe listOf(CivAutonomousAccessRepair(city.buildings.first().id, plan.building.id, feet,
            CivPlanBlock(feet, "minecraft:coarse_dirt", "minecraft:air")))
        civsAutonomousAccessRepairs(city.copy(autonomy = null), listOf(plan)) shouldBe emptyList()
    }
    test("foreign or moved journals and an earlier write cannot authorize historical repair") {
        val (city, plan) = accessRepairFixture()
        listOf(plan.copy(worldId = UUID.randomUUID()), plan.copy(cityId = UUID.randomUUID()),
            plan.copy(building = plan.building.copy(anchor = plan.building.anchor.copy(y = 70)))).forEach {
            civsAutonomousAccessRepairs(city, listOf(it)) shouldBe emptyList()
        }
        civsAutonomousAccessRepairs(city.copy(buildings = city.buildings.reversed()), listOf(plan)) shouldBe emptyList()
    }
    test("conflicting later writes at the same cell are not guessed or replayed") {
        val (original, plan) = accessRepairFixture()
        val other = plan.building.copy(id = UUID.randomUUID(), entrance = CivPosition(30, 65, 0),
            anchor = CivPosition(30, 64, 0))
        val city = original.copy(buildings = original.buildings + other)
        val second = plan.copy(id = other.id, building = other, terrain = plan.terrain!!.copy(
            route = listOf(other.entrance), blocks = listOf(plan.terrain.blocks.first().copy(after = "minecraft:stone"))))
        civsAutonomousAccessRepairs(city, listOf(plan, second)) shouldBe emptyList()
    }
})

private fun accessRepairFixture(): Pair<Settlement, CivPlacementPlan> {
    val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(0, 64, 0), 0, "fixture",
        CivBox(CivPosition(-4, 64, -8), CivPosition(4, 70, -1)), CivPosition(0, 65, 0), BuildingPhase.ACTIVE)
    val quarry = hall.copy(id = UUID.randomUUID(), type = BuildingType.QUARRY, anchor = CivPosition(20, 64, 0),
        bounds = CivBox(CivPosition(16, 64, -8), CivPosition(24, 70, -1)), entrance = CivPosition(20, 65, 0))
    val city = Settlement(UUID.randomUUID(), UUID.randomUUID(), "Repair", UUID.randomUUID(), CivPosition(0, 64, 0),
        96, "fixture", Stock(), listOf(hall, quarry), emptyList(), autonomy = CivAutonomy())
    val terrain = CivTerraformPlan(CivBox(CivPosition(-4, 63, -8), CivPosition(24, 70, 1)), listOf(
        CivPlanBlock(hall.entrance, "minecraft:air", "minecraft:coarse_dirt"),
        CivPlanBlock(CivPosition(5, 66, 0), "minecraft:air", "minecraft:stone"),
        CivPlanBlock(hall.entrance.copy(y = 64), "minecraft:dirt", "minecraft:coarse_dirt")),
        listOf(quarry.entrance), 64, 0, 3)
    return city to CivPlacementPlan(quarry.id, city.id, city.worldId, quarry, emptyList(), terrain = terrain)
}
