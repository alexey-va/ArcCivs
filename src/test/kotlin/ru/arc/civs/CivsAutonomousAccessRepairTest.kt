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
    test("later birch leaf cuts below the hall apron cannot be replayed as supports") {
        val entrance = CivPosition(12963, 79, 9342)
        val (city, plan) = accessRepairFixture(entrance, BuildingType.BUILDER_HUT)
        val support = entrance.copy(y = 78)
        val leafCuts = listOf(
            CivPlanBlock(support.copy(z = 9343), "minecraft:birch_leaves[distance=1,persistent=false,waterlogged=false]", "minecraft:air"),
            CivPlanBlock(support, "minecraft:birch_leaves[distance=2,persistent=false,waterlogged=false]", "minecraft:air"))
        val eligible = listOf(
            CivPlanBlock(support.copy(x = 12962), "minecraft:stone", "minecraft:air"),
            CivPlanBlock(support.copy(x = 12964), "minecraft:grass_block[snowy=false]", "minecraft:air"),
            CivPlanBlock(entrance, "minecraft:air", "minecraft:coarse_dirt"))
        fun repairs(cells: List<CivPlanBlock>): List<CivAutonomousAccessRepair> =
            civsAutonomousAccessRepairs(city, listOf(plan.copy(terrain = requireNotNull(plan.terrain).copy(blocks = cells))))

        repairs(leafCuts + eligible) shouldBe eligible.map { cell ->
            CivAutonomousAccessRepair(city.buildings.first().id, plan.building.id, cell.position.copy(y = entrance.y),
                cell.copy(before = cell.after, after = cell.before))
        }
        repairs(leafCuts) shouldBe emptyList()
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

private fun accessRepairFixture(entrance: CivPosition = CivPosition(0, 65, 0),
    laterType: BuildingType = BuildingType.QUARRY): Pair<Settlement, CivPlacementPlan> {
    fun at(x: Int, y: Int, z: Int) = CivPosition(entrance.x + x, entrance.y + y, entrance.z + z)
    val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, at(0, -1, 0), 0, "fixture",
        CivBox(at(-4, -1, -8), at(4, 5, -1)), entrance, BuildingPhase.ACTIVE)
    val later = hall.copy(id = UUID.randomUUID(), type = laterType, anchor = at(20, -1, 0),
        bounds = CivBox(at(16, -1, -8), at(24, 5, -1)), entrance = at(20, 0, 0))
    val city = Settlement(UUID.randomUUID(), UUID.randomUUID(), "Repair", UUID.randomUUID(), hall.anchor,
        96, "fixture", Stock(), listOf(hall, later), emptyList(), autonomy = CivAutonomy())
    val terrain = CivTerraformPlan(CivBox(at(-4, -2, -8), at(24, 5, 1)), listOf(
        CivPlanBlock(hall.entrance, "minecraft:air", "minecraft:coarse_dirt"),
        CivPlanBlock(at(5, 1, 0), "minecraft:air", "minecraft:stone"),
        CivPlanBlock(at(0, -1, 0), "minecraft:dirt", "minecraft:coarse_dirt")),
        listOf(later.entrance), hall.anchor.y, 0, 3)
    return city to CivPlacementPlan(later.id, city.id, city.worldId, later, emptyList(), terrain = terrain)
}
