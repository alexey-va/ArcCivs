package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsRetainedPlanSourceTest : FunSpec({
    test("compact source keeps exact route pavement evidence without ordinary construction blocks") {
        val route = (0..10).map { CivPosition(it, 64, 0) }.toMutableList()
        val building = CivBuilding(UUID(0, 1), BuildingType.FORESTER, 1, CivPosition(10, 64, 2), 0,
            "fixture/forester", CivBox(CivPosition(8, 64, 1), CivPosition(12, 70, 4)), route.last(), BuildingPhase.ACTIVE)
        val kept = CivPlanBlock(CivPosition(4, 63, 0), "minecraft:grass_block[snowy=false]", "minecraft:coarse_dirt")
        val outside = kept.copy(position = CivPosition(30, 63, 0))
        val unchanged = kept.copy(position = CivPosition(5, 63, 0), before = kept.after)
        val irrelevant = kept.copy(position = CivPosition(6, 63, 0), after = "minecraft:dirt")
        val blocks = MutableList(10_000) { CivPlanBlock(CivPosition(10, it, 2), "minecraft:air", "minecraft:stone") }
        val terrain = CivTerraformPlan(building.bounds, listOf(kept, outside, unchanged, irrelevant), route, 63, 0, 3)
        val plan = CivPlacementPlan(building.id, UUID(0, 2), UUID(0, 3), building, blocks, "a".repeat(64), terrain)
        val source = CivRetainedPlanSource.from(plan)
        source.route shouldBe route
        source.pavement shouldBe listOf(kept)
        source.infrastructureBlocks shouldBe emptyList()
        source.changedColumns shouldBe emptySet()
        route.clear(); blocks.clear()
        source.route!!.size shouldBe 11
        shouldThrow<UnsupportedOperationException> { (source.route as MutableList<CivPosition>).clear() }
        shouldThrow<UnsupportedOperationException> { (source.pavement as MutableList<CivPlanBlock>).clear() }
    }

    test("infrastructure projection preserves exact after cells foundations and changed wall columns") {
        val route = (0..6).map { CivPosition(it, 64, 0) }
        val building = CivBuilding(UUID(0, 11), BuildingType.WALL, 1, CivPosition(6, 64, 2), 0,
            "fixture/wall", CivBox(CivPosition(5, 64, 1), CivPosition(7, 70, 4)), route.last(), BuildingPhase.ACTIVE)
        val changes = listOf(
            CivPlanBlock(CivPosition(2, 63, 0), "minecraft:grass_block[snowy=false]", "minecraft:dirt_path"),
            CivPlanBlock(CivPosition(2, 64, 0), "minecraft:stone", "minecraft:air"),
            CivPlanBlock(CivPosition(3, 63, 0), "minecraft:dirt", "minecraft:dirt"))
        val foundation = CivWaterFoundation(6, 2, 63, 62, 60)
        val gateFloor = CivPosition(6, 63, 3)
        val waterFloor = CivPosition(6, 63, 4)
        val blocks = listOf(CivPlanBlock(building.anchor, "minecraft:air", "minecraft:stone_bricks"))
        val terrain = CivTerraformPlan(building.bounds, changes, route, 63, 1, 1,
            listOf(foundation), paidGateFloors = listOf(gateFloor), paidWaterFloors = listOf(waterFloor))
        val city = Settlement(UUID(0, 12), UUID(0, 13), "Projection", UUID(0, 14), CivPosition(0, 64, 0),
            96, "fixture", Stock(), listOf(building), emptyList())
        val plan = CivPlacementPlan(building.id, city.id, city.worldId, building, blocks, "b".repeat(64), terrain)
        val source = CivRetainedPlanSource.from(plan)
        source.changedColumns shouldBe setOf(civsTerrainKey(2, 0))
        source.pavement shouldBe listOf(changes[0])
        val expected = CivSettlementPiece(building.id, building.type, building.bounds, building.entrance,
            listOf(CivSettlementBlock(building.anchor, "minecraft:stone_bricks")), waterFoundations = listOf(foundation),
            paidGateFloors = listOf(gateFloor), paidWaterFloors = listOf(waterFloor))
        CivsSettlementLayout.acceptedSources(city, listOf(source)) shouldBe listOf(expected)
        CivsSettlementLayout.accepted(city, listOf(plan)) shouldBe listOf(expected)
        CivAutonomousAccess.fromSources(city, listOf(source)) shouldBe CivAutonomousAccess.from(city, listOf(plan))
        CivAutonomousAccess.fromSources(city, listOf(source)).wallPavement.getValue(building.id).pavement shouldBe listOf(changes[0])
        CivsSettlementLayout.acceptedSources(city.copy(worldId = UUID(0, 99)), listOf(source)) shouldBe emptyList()
        CivAutonomousAccess.fromSources(city.copy(worldId = UUID(0, 99)), listOf(source)).routes shouldBe emptyMap()
    }
})
