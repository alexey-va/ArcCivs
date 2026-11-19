package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsConstructionOverlapTest : FunSpec({
    val city = UUID.randomUUID()
    val world = UUID.randomUUID()
    val slope = CivPosition(40, 64, 1)
    val feet = slope.copy(y = 65)
    val head = slope.copy(y = 66)
    fun change(at: CivPosition, after: String) = CivPlanBlock(at, "minecraft:air", after)
    fun plan(x: Int, earthworks: List<CivPlanBlock> = emptyList(), route: List<CivPosition> = emptyList(),
        entry: CivPosition = CivPosition(x, 65, 0)): CivPlacementPlan {
        val anchor = CivPosition(x, 64, 0)
        val building = CivBuilding(UUID.randomUUID(), BuildingType.HOUSE, 1, anchor, 0, "house",
            CivBox(anchor, anchor.copy(y = 68)), entry, BuildingPhase.CONSTRUCTING)
        val area = earthworks.map { it.position } + anchor
        val bounds = CivBox(CivPosition(area.minOf { it.x }, area.minOf { it.y }, area.minOf { it.z }),
            CivPosition(area.maxOf { it.x }, area.maxOf { it.y }, area.maxOf { it.z }))
        return CivPlacementPlan(UUID.randomUUID(), city, world, building, listOf(change(anchor, "minecraft:stone")),
            terrain = CivTerraformPlan(bounds, earthworks, route, 64, 0, earthworks.size))
    }

    test("separate houses can share the same road but cannot assign different finishes to the same external slope") {
        val road = change(slope, "minecraft:coarse_dirt")
        val first = plan(1, listOf(road))
        val shared = plan(60, listOf(road))
        val raised = plan(60, listOf(change(slope, "minecraft:dirt")))
        civsConstructionPlansCompatible(first, shared) shouldBe true
        civsConstructionPlansCompatible(first, raised) shouldBe false
        civsConstructionPlansCompatible(raised, first) shouldBe false
        civsConstructionPlansCompatible(first, plan(60, listOf(change(slope.copy(x = 50), "minecraft:dirt")))) shouldBe true
        val retained = plan(60, listOf(CivPlanBlock(slope, "minecraft:dirt", "minecraft:dirt")))
        civsConstructionPlansCompatible(first, retained) shouldBe false
    }

    test("paid walking routes preserve their support and both clear blocks even beyond the old earthworks box") {
        val first = plan(1, route = listOf(feet))
        val passage = plan(60, listOf(change(slope, "minecraft:cobblestone")))
        civsConstructionPlansCompatible(first, passage) shouldBe true
        for (point in listOf(feet, head)) {
            val wall = plan(60, listOf(change(point, "minecraft:stone")))
            civsConstructionPlansCompatible(first, wall) shouldBe false
            civsConstructionPlansCompatible(wall, first) shouldBe false
        }
        val hole = plan(60, listOf(CivPlanBlock(slope, "minecraft:dirt", "minecraft:air")))
        civsConstructionPlansCompatible(first, hole) shouldBe false
        val outside = plan(60, listOf(change(head.copy(z = head.z + 1), "minecraft:stone")))
        civsConstructionPlansCompatible(first, outside) shouldBe true
    }

    test("a native entrance receipt cannot clear a cell retained by a suspended project") {
        val held = plan(1, listOf(CivPlanBlock(head, "minecraft:stone", "minecraft:stone")))
        val manual = plan(60, entry = feet).copy(terrain = null)
        val clear = CivPlanBlock(head, "minecraft:stone", "minecraft:air")
        civsConstructionPlansCompatible(held, manual) shouldBe true
        civsConstructionPlansCompatible(held, manual, listOf(clear)) shouldBe false
    }
})
