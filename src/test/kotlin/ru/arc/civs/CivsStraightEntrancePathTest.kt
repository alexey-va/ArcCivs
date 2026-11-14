package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CivsStraightEntrancePathTest : FunSpec({
    test("a side-fed paid approach keeps three lanes, two soil cheeks and no paved side spur in four rotations") {
        val base = roadAdmissionCity()
        for ((turn, facing) in listOf("north", "east", "south", "west").withIndex()) {
            // Depth points from the door into the street. The last two paid steps approach sideways.
            fun point(side: Int, y: Int, depth: Int): CivPosition = when (turn) {
                0 -> CivPosition(12 + side, y, 10 + depth)
                1 -> CivPosition(12 - depth, y, 10 + side)
                2 -> CivPosition(12 - side, y, 10 - depth)
                else -> CivPosition(12 + depth, y, 10 - side)
            }
            val entrance = point(0, 68, 0)
            val a = point(-3, 66, -1)
            val b = point(3, 72, -6)
            val source = base.buildings.last().copy(type = BuildingType.HOUSE, rotation = turn * 90,
                schematicId = "civs/v2/nordic/house/test/level1", transit = null, entrance = entrance,
                anchor = entrance.copy(y = 67), bounds = CivBox(
                    CivPosition(minOf(a.x, b.x), a.y, minOf(a.z, b.z)),
                    CivPosition(maxOf(a.x, b.x), b.y, maxOf(a.z, b.z))))
            val city = base.copy(style = "nordic", buildings = base.buildings.dropLast(1) + source)
            val route = (7 downTo 0).map { point(2, 67, it) } + listOf(point(1, 68, 0), entrance)
            val plan = CivPlacementPlan(source.id, city.id, city.worldId, source, emptyList(),
                terrain = CivTerraformPlan(CivBox(CivPosition(0, 66, 0), CivPosition(30, 70, 30)),
                    emptyList(), route, source.anchor.y, 0, 0))
            val before = Gson().toJson(city to plan)
            val targets = CivsRoadMaintenance.prepare(city, listOf(plan)).targets.associate { it.floor to it.after }
            val stair = "minecraft:spruce_stairs[facing=$facing,half=bottom,shape=straight,waterlogged=false]"
            val expected = buildMap {
                for (depth in 0..3) for (side in -1..1)
                    put(point(side, if (depth < 2) 67 else 66, depth), if (depth == 1) stair else "minecraft:dirt_path")
                for (depth in 0..3) {
                    put(point(2, if (depth < 2) 67 else 66, depth), CivsEntrancePath.GRASS)
                    put(point(3, 66, depth), CivsEntrancePath.GRASS)
                    if (depth < 2) put(point(-2, 67, depth), CivsEntrancePath.GRASS)
                }
            }
            val columns = expected.keys.map { it.x to it.z }.toSet()
            withClue("rotation ${source.rotation}, including the former paid side spur") {
                targets.filterKeys { (it.x to it.z) in columns } shouldBe expected
            }
            Gson().toJson(city to plan) shouldBe before
        }
    }
})
