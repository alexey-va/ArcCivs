package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsSharedRoadTrunkTest : FunSpec({
    for (protectedFlank in listOf(false, true)) {
        test("a quarry shares the graded trunk until a safe branch is available, protected flank=$protectedFlank") {
            // The paid forester route drops one block. The quarry can stay high on
            // the parallel row, or reuse the lower trunk and climb once at the branch.
            val hall = CivBuilding(UUID(233, 1), BuildingType.TOWN_HALL, 1, CivPosition(-4, 66, 0), 270, "fixture/hall",
                CivBox(CivPosition(-8, 65, -2), CivPosition(-5, 73, 2)), CivPosition(-4, 67, 0), BuildingPhase.ACTIVE)
            val forester = CivBuilding(UUID(233, 2), BuildingType.FORESTER, 1, CivPosition(8, 65, 0), 90, "fixture/forester",
                CivBox(CivPosition(9, 64, -1), CivPosition(11, 72, 1)), CivPosition(8, 66, 0), BuildingPhase.ACTIVE)
            val quarry = CivBuilding(UUID(233, 3), BuildingType.QUARRY, 1, CivPosition(3, 66, -7), 0, "fixture/quarry",
                CivBox(CivPosition(1, 65, -12), CivPosition(5, 73, -8)), CivPosition(3, 67, -7), BuildingPhase.SITE)
            val city = Settlement(UUID(233, 4), UUID(233, 5), "Shared road", UUID(233, 6), hall.entrance,
                32, "fixture", Stock(), listOf(hall, forester), emptyList(), autonomy = CivAutonomy())
            val trunk = (-4..8).map { x -> CivPosition(x, if (x <= 0) 67 else 66, 0) }
            val access = CivAutonomousAccess(routes = mapOf(forester.id to trunk))
            val paidFloors = trunk.associateBy({ civsTerrainKey(it.x, it.z) }, { it.y - 1 })
            val surface = CivTerrainSurface(-64, 320, buildMap {
                for (x in -8..11) for (z in -13..2) {
                    val floor = if (z == 0 && x > 0) 65 else 66
                    val natural = z == 0 || x in 0..3 && z in -2..-1 ||
                        x in 2..4 && z in -7..-2 || x in 1..5 && z in -12..-8
                    put(civsTerrainKey(x, z), CivTerrainSurfaceColumn(floor, natural))
                }
            })
            // Model a protected portal flank without allowing the draft to reshape the gate.
            val flank = CivBox(CivPosition(1, 64, -1), CivPosition(3, 72, -1))
            val obstacles = listOfNotNull(flank.takeIf { protectedFlank })

            val draft = CivsAutonomousTerraform.draft(city, CivTerraformOption(quarry, 0), surface, access,
                additionalObstacles = obstacles, preferredRoads = paidFloors.keys)

            draft.route.first() shouldBe hall.entrance
            draft.route.last() shouldBe quarry.entrance
            val shared = draft.route.takeWhile { paidFloors[civsTerrainKey(it.x, it.z)] == it.y - 1 }
            shared shouldBe trunk.take(if (protectedFlank) 5 else 8)
            if (protectedFlank) {
                draft.route.none { CivsAutonomousGeometry.horizontalContains(flank, it) } shouldBe true
                draft.columns.none { it.x in 1..3 && it.z == -1 } shouldBe true
            } else {
                draft.route[shared.size] shouldBe CivPosition(3, 67, -1)
            }
            draft.columns.filter { civsTerrainKey(it.x, it.z) in paidFloors }.all {
                it.preserve && it.groundY == paidFloors.getValue(civsTerrainKey(it.x, it.z))
            } shouldBe true
        }
    }
})
