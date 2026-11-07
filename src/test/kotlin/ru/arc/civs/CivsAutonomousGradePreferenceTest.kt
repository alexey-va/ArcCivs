package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsAutonomousGradePreferenceTest : FunSpec({
    test("the recovered Auto5 warehouse height is refused for new work but retained for an accepted site") {
        val city = gradeCity(68)
        val warehouse = gradeWarehouse(1, CivPosition(39, 72, 9), 24, 34, rotation = 180)
        val candidate = CivsAutonomousCandidate(warehouse, streetFrontage = true)
        // Auto5 pre-build profile for the relocated 24x34 warehouse plot.
        val surface = gradeSurface(warehouse to listOf(
            66 to 26, 67 to 37, 68 to 99, 69 to 71, 70 to 60, 71 to 97, 72 to 164, 73 to 215, 74 to 45, 75 to 2,
        ))
        val options = CivsAutonomousTerraform.options(city, listOf(candidate), surface, tryNearbyCut = true)
        options.map { it.building.anchor.y } shouldBe listOf(69, 68)
        options.first().building.anchor.copy(y = warehouse.anchor.y) shouldBe warehouse.anchor
        CivsAutonomousTerraform.options(city, listOf(candidate), surface, fixed = true) shouldBe emptyList()

        val accepted = city.copy(buildings = city.buildings + warehouse)
        CivsAutonomousTerraform.options(accepted, listOf(candidate), surface, fixed = true).single().building shouldBe warehouse
    }

    test("a flatter Auto3 parcel can outrank steeper frontage within the offered sites") {
        val city = gradeCity(71)
        val frontage = gradeWarehouse(1, CivPosition(43, 69, 0), 21, 22)
        val flatter = gradeWarehouse(2, CivPosition(43, 71, -31), 21, 22)
        val surface = gradeSurface(
            frontage to listOf(68 to 208, 69 to 106, 70 to 50, 71 to 42, 72 to 27, 73 to 24, 74 to 5),
            flatter to listOf(70 to 13, 71 to 385, 72 to 61, 73 to 3),
        )
        val options = CivsAutonomousTerraform.options(city,
            listOf(CivsAutonomousCandidate(frontage, streetFrontage = true), CivsAutonomousCandidate(flatter)), surface)

        options.first().building shouldBe flatter
        options.any { it.building == frontage } shouldBe true
    }

    test("one low column allows a shallow cut without filling the notch four blocks deep") {
        val city = gradeCity(68)
        val plot = gradeWarehouse(1, CivPosition(20, 72, -16), 11, 11)
        val surface = gradeSurface(plot to listOf(68 to 1, 72 to 120))

        val options = CivsAutonomousTerraform.options(city, listOf(CivsAutonomousCandidate(plot)), surface)
        options.first().building.anchor.y shouldBe 71
        options.none { it.building.anchor.y == 72 } shouldBe true
    }

    test("a wide raised pad can exceed the average fill limit within the column depth limit") {
        val city = gradeCity(68)
        val plot = gradeWarehouse(1, CivPosition(20, 70, -16), 11, 11)
        val surface = gradeSurface(plot to listOf(67 to 60, 70 to 61))

        val options = CivsAutonomousTerraform.options(city, listOf(CivsAutonomousCandidate(plot)), surface)
        options.first().building.anchor.y shouldBe 69
        options.none { it.building.anchor.y == 70 } shouldBe true
    }

    test("a natural high pad still needs an entrance apron with a modest raise") {
        val city = gradeCity(68)
        val plot = gradeWarehouse(1, CivPosition(0, 74, 12), 11, 11, rotation = 180)
        val surface = CivTerrainSurface(-64, 320, buildMap {
            for (x in -8..8) for (z in -9..26) {
                val ground = if (x in -5..5 && z in 13..23) 74 else 68
                put(civsTerrainKey(x, z), CivTerrainSurfaceColumn(ground, true))
            }
        })
        val route = (0..12).map { z -> CivPosition(0, 69 + (z - 3).coerceIn(0, 6), z) }
        val failure = shouldThrow<CivsRejection> {
            CivsAutonomousTerraform.draft(city, CivTerraformOption(plot, 0), surface, reservedRoute = route)
        }

        failure.reason shouldBe "autonomy-slope"
        failure.values["stage"] shouldBe "approach-fill"
    }
})

private fun gradeCity(y: Int): Settlement {
    val hall = CivBuilding(UUID(232, 10), BuildingType.TOWN_HALL, 1, CivPosition(0, y, 0), 0, "fixture/hall",
        CivBox(CivPosition(-4, y - 1, -8), CivPosition(4, y + 6, -1)), CivPosition(0, y + 1, 0), BuildingPhase.ACTIVE)
    return Settlement(UUID(232, 11), UUID(232, 12), "Grade", UUID(232, 13), CivPosition(0, y + 1, 0),
        96, "nordic", Stock(), listOf(hall), emptyList(), autonomy = CivAutonomy())
}

private fun gradeWarehouse(id: Long, anchor: CivPosition, width: Int, depth: Int, rotation: Int = 0): CivBuilding {
    val minX = anchor.x - (width - 1) / 2
    val minZ = if (rotation == 180) anchor.z + 1 else anchor.z - depth
    return CivBuilding(UUID(232, id), BuildingType.WAREHOUSE, 1, anchor, rotation, "fixture/warehouse",
        CivBox(CivPosition(minX, anchor.y - 2, minZ), CivPosition(minX + width - 1, anchor.y + 21, minZ + depth - 1)),
        anchor.copy(y = anchor.y + 1), BuildingPhase.SITE)
}

private fun gradeSurface(vararg plots: Pair<CivBuilding, List<Pair<Int, Int>>>): CivTerrainSurface =
    CivTerrainSurface(-64, 320, buildMap {
        for ((building, histogram) in plots) {
            val heights = histogram.flatMap { (height, count) -> List(count) { height } }.iterator()
            val box = building.bounds
            for (x in box.min.x..box.max.x) for (z in box.min.z..box.max.z) {
                put(civsTerrainKey(x, z), CivTerrainSurfaceColumn(heights.next(), true))
            }
            check(!heights.hasNext())
        }
    })
