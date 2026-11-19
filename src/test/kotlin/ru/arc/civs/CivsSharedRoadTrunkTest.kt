package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsSharedRoadTrunkTest : FunSpec({
    test("a paid fork joins only the level median before the upper street climbs") {
        val shared = (-2..0).map { CivPosition(it, 70, 0) }
        val main = shared + (1..5).map { CivPosition(it, 70, 0) } +
            (1..12).map { CivPosition(5, if (it < 8) 70 else 71, it) }
        val branch = shared + (1..4).map { CivPosition(0, 70, it) } + (4..12).map { CivPosition(1, 70, it) }
        val landing = requireNotNull(CivsRoadFork.landing(main, branch))
        landing.floors shouldBe ((2..6).map { CivPosition(3, 69, it) } +
            (2..3).map { CivPosition(2, 69, it) }).toSet()
        landing.floors.none { p -> (main + branch).any { it.x == p.x && it.z == p.z } } shouldBe true
        CivsRoadFork.landing(main.map { it.copy(y = 70) }, branch) shouldBe null
        CivsRoadFork.landing(main, branch.drop(2)) shouldBe null
        CivsRoadFork.replaceable("minecraft:spruce_planks") shouldBe false
        CivsRoadFork.replaceable("minecraft:dirt_path") shouldBe false
    }

    test("three-wide farm and forester streets share the approach across a one-block grass median") {
        val hall = CivBuilding(UUID(234, 1), BuildingType.TOWN_HALL, 1, CivPosition(0, 69, 0), 270, "fixture/hall",
            CivBox(CivPosition(-5, 68, -2), CivPosition(-1, 75, 2)), CivPosition(0, 70, 0), BuildingPhase.ACTIVE)
        val forest = CivBuilding(UUID(234, 2), BuildingType.FORESTER, 1, CivPosition(4, 69, -16), 0, "fixture/forester",
            CivBox(CivPosition(3, 68, -20), CivPosition(5, 75, -17)), CivPosition(4, 70, -16), BuildingPhase.ACTIVE)
        val farm = CivBuilding(UUID(234, 3), BuildingType.FARM, 1, CivPosition(0, 69, -12), 0, "fixture/farm",
            CivBox(CivPosition(-1, 68, -16), CivPosition(1, 75, -13)), CivPosition(0, 70, -12), BuildingPhase.SITE)
        val city = Settlement(UUID(234, 4), UUID(234, 5), "Forest lane", UUID(234, 6), hall.entrance,
            32, "fixture", Stock(), listOf(hall, forest), emptyList(), autonomy = CivAutonomy())
        val trunk = (0..4).map { CivPosition(it, 70, 0) } + (1..16).map { CivPosition(4, 70, -it) }
        val access = CivAutonomousAccess(routes = mapOf(forest.id to trunk))
        for (bridgeOpen in listOf(true, false)) {
            val surface = CivTerrainSurface(-64, 320, buildMap {
                for (x in -2..6) for (z in -20..2) {
                    val open = x in -1..1 || x in 3..5 || z in -1..1 || bridgeOpen && z in -9..-7
                    put(civsTerrainKey(x, z), CivTerrainSurfaceColumn(69, open))
                }
            })
            val route = CivsAutonomousTerraform.draft(city, CivTerraformOption(farm, 0), surface, access,
                preferredRoads = trunk.mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }).route
            route.first() shouldBe hall.entrance
            route.last() shouldBe farm.entrance
            route.map { it.y }.distinct() shouldBe listOf(70)
            if (bridgeOpen) {
                route.takeWhile { it in trunk }.last().z shouldBe -9
            } else {
                route.count { it.x == 4 } shouldBe 0
            }
        }
    }

    for (protectedFlank in listOf(false, true)) for (trunkDrops in listOf(false, true)) {
        test("a quarry shares the level trunk until a safe flat branch, protected flank=$protectedFlank trunk drops=$trunkDrops") {
            // A lower paid trunk would add a descent and climb. Reuse its level prefix;
            // when the whole trunk is level, keep sharing it to the nearest branch.
            val foresterFeet = if (trunkDrops) 66 else 67
            val hall = CivBuilding(UUID(233, 1), BuildingType.TOWN_HALL, 1, CivPosition(-4, 66, 0), 270, "fixture/hall",
                CivBox(CivPosition(-8, 65, -2), CivPosition(-5, 73, 2)), CivPosition(-4, 67, 0), BuildingPhase.ACTIVE)
            val forester = CivBuilding(UUID(233, 2), BuildingType.FORESTER, 1, CivPosition(8, foresterFeet - 1, 0), 90, "fixture/forester",
                CivBox(CivPosition(9, foresterFeet - 2, -1), CivPosition(11, foresterFeet + 6, 1)),
                CivPosition(8, foresterFeet, 0), BuildingPhase.ACTIVE)
            val quarry = CivBuilding(UUID(233, 3), BuildingType.QUARRY, 1, CivPosition(3, 66, -7), 0, "fixture/quarry",
                CivBox(CivPosition(1, 65, -12), CivPosition(5, 73, -8)), CivPosition(3, 67, -7), BuildingPhase.SITE)
            val city = Settlement(UUID(233, 4), UUID(233, 5), "Shared road", UUID(233, 6), hall.entrance,
                32, "fixture", Stock(), listOf(hall, forester), emptyList(), autonomy = CivAutonomy())
            val trunk = (-4..8).map { x -> CivPosition(x, if (x <= 0) 67 else foresterFeet, 0) }
            val access = CivAutonomousAccess(routes = mapOf(forester.id to trunk))
            val paidFloors = trunk.associateBy({ civsTerrainKey(it.x, it.z) }, { it.y - 1 })
            val surface = CivTerrainSurface(-64, 320, buildMap {
                for (x in -8..11) for (z in -13..2) {
                    val floor = if (z == 0 && x > 0) foresterFeet - 1 else 66
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
            shared shouldBe trunk.take(if (protectedFlank || trunkDrops) 5 else 8)
            draft.route.map { it.y }.distinct() shouldBe listOf(67)
            draft.route.size shouldBe 15 // Seven X steps and seven Z steps; no extra detour.
            draft.route[shared.size] shouldBe CivPosition(if (protectedFlank || trunkDrops) 0 else 3, 67, -1)
            if (protectedFlank) {
                draft.route.none { CivsAutonomousGeometry.horizontalContains(flank, it) } shouldBe true
                draft.columns.none { it.x in 1..3 && it.z == -1 } shouldBe true
            }
            draft.columns.filter { civsTerrainKey(it.x, it.z) in paidFloors }.all {
                it.preserve && it.groundY == paidFloors.getValue(civsTerrainKey(it.x, it.z))
            } shouldBe true
        }
    }
})
