package ru.arc.civs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsInsetGateFrontageTest : FunSpec({
    // Curated miner levels 1..3 share this exact envelope and entrance; no private voxels are needed.
    val quarry = settlementPlanTemplates().getValue(BuildingType.QUARRY).first.copy(
        schematicId = "civs/v2/nordic/fixture-miner/level1",
        bounds = CivBox(CivPosition(-6, -3, -23), CivPosition(14, 24, -1)))
    val maximum = quarry.copy(level = 3)

    test("an adopted inset gate retains feasible exterior quarry frontage beyond the unchanged core") {
        val (city, access) = insetGateFrontageCity()
        val plan = requireNotNull(city.autonomy?.plan)
        CivsSettlementPlans.current(city) shouldBe plan
        val frame = CivsSettlementLayout.frame(city)
        val gate = frame.gates.single()
        gate.center shouldBe CivPosition(2, 0, 5)
        frame.core.max.x shouldBe 48

        // Feasibility witness: only the frontage origin moves to the core edge. The actual walls,
        // routes, occupied envelopes, reach limit and candidate cap still use the production guards.
        val edgeFrame = frame.copy(gates = listOf(gate.copy(center = gate.center.copy(x = frame.core.max.x))))
        val witness = CivsSettlementLayout.candidates(city, quarry, maximum, access, edgeFrame)
            .single { it.building.entrance == CivPosition(56, 1, -23) }.building
        CivsSettlementGrowth.outside(city, witness.bounds) shouldBe true

        val candidates = CivsSettlementLayout.candidates(city, quarry, maximum, access, frame)
        candidates.any { it.building == witness } shouldBe true
        city.autonomy?.plan shouldBe plan
    }

    for ((rotation, dx, dz) in listOf(Triple(0, 1, 0), Triple(90, 0, 1), Triple(180, -1, 0), Triple(270, 0, -1))) {
        test("rotation $rotation preserves boundary frontage and gates already outside the core") {
            val base = settlementPlanCity().copy(autonomy = CivAutonomy(layoutHalfWidth = 48))
            val hall = CivsSettlementPlans.at(settlementPlanTemplates().getValue(BuildingType.TOWN_HALL).first,
                base.center, rotation, base.buildings.single().id).copy(phase = BuildingPhase.ACTIVE)
            val city = base.copy(buildings = listOf(hall))
            val frame = CivsSettlementLayout.frame(city)
            val gate = frame.gates.single { it.side == 1 }
            fun frontage(shift: Int) = CivsSettlementLayout.candidates(city, quarry, maximum,
                frame = frame.copy(gates = listOf(gate.copy(center = gate.center.copy(
                    x = gate.center.x + dx * shift, z = gate.center.z + dz * shift)))))
            fun normalDistances(candidates: List<CivsAutonomousCandidate>) = candidates.map {
                (it.building.entrance.x - city.center.x) * dx + (it.building.entrance.z - city.center.z) * dz
            }.toSet()

            val boundary = frontage(0)
            normalDistances(boundary) shouldBe setOf(56, 62, 68)
            frontage(-17) shouldBe boundary
            normalDistances(frontage(2)) shouldBe setOf(58, 64, 70)
        }
    }
})

/** LAB Auto-1 metadata captured 2026-11-05 09:47 UTC, translated to its centre with synthetic identities.
 * This fixture proves detached frontage feasibility only, not native terrain, routing or placement. */
private fun insetGateFrontageCity(): Pair<Settlement, CivAutonomousAccess> {
    val hall = CivBuilding(UUID(216, 1), BuildingType.TOWN_HALL, 1, CivPosition(-1, -1, 1), 0,
        "civs/v2/nordic/fixture-hall/level1", CivBox(CivPosition(-18, -3, -28), CivPosition(11, 25, 0)),
        CivPosition(-1, 0, 1), BuildingPhase.SITE, CivPosition(0, 0, 0))
    val hut = CivBuilding(UUID(216, 2), BuildingType.BUILDER_HUT, 1, CivPosition(-18, -1, 13), 180,
        "civs/v2/nordic/fixture-builder/level1", CivBox(CivPosition(-29, -3, 14), CivPosition(-10, 21, 28)),
        CivPosition(-18, 0, 13), BuildingPhase.ACTIVE, CivPosition(-14, 0, 19))
    val forester = CivBuilding(UUID(216, 3), BuildingType.FORESTER, 1, CivPosition(62, -3, 9), 90,
        "civs/v2/nordic/fixture-lumberjack/level1", CivBox(CivPosition(63, -4, 0), CivPosition(83, 16, 15)),
        CivPosition(62, -2, 9), BuildingPhase.ACTIVE, CivPosition(78, -2, 8))
    val buildings = listOf(hall, hut, forester)
    val prefix = listOf(CivPosition(-1, 0, 1), CivPosition(-1, -1, 2)) +
        (3..5).map { CivPosition(-1, -2, it) }
    val hutRoute = prefix + (6..13).map { CivPosition(-1, -2, it) } +
        (2..3).map { CivPosition(-it, -2, 13) } + (4..16).map { CivPosition(-it, -1, 13) } +
        (17..18).map { CivPosition(-it, 0, 13) }
    val foresterRoute = prefix + listOf(
        (0..4) to -2, (5..28) to -3, (29..32) to -2, (33..36) to -1,
        (37..42) to 0, (43..53) to 1, (54..59) to 0, (60..61) to -1,
    ).flatMap { (xs, y) -> xs.map { CivPosition(it, y, 5) } } + (5..9).map { CivPosition(62, -2, it) }
    val routes = mapOf(hut.id to hutRoute, forester.id to foresterRoute)
    val walls = listOf(
        intArrayOf(-22, -30, 14, -30), intArrayOf(-22, -29, -22, 10), intArrayOf(14, -29, 14, 2),
        intArrayOf(2, 2, 13, 2), intArrayOf(2, 8, 2, 14), intArrayOf(-30, 10, -23, 10),
        intArrayOf(-30, 11, -30, 13), intArrayOf(-31, 13, -31, 18), intArrayOf(-6, 14, 1, 14),
        intArrayOf(-6, 15, -6, 30), intArrayOf(-30, 18, -30, 30), intArrayOf(-29, 30, -7, 30),
    ).map { (x0, z0, x1, z1) -> CivBox(CivPosition(x0, 0, z0), CivPosition(x1, 0, z1)) }
    val plots = buildings.map { building ->
        val forecast = building.copy(phase = BuildingPhase.SITE,
            control = if (building.id == forester.id) CivPosition(63, -2, 10) else building.control)
        CivSettlementPlot(forecast, building.bounds)
    }
    val plan = CivSettlementPlan(CivsSettlementPlans.VERSION, 1, 48, plots,
        routes.mapKeys { it.key.toString() }, walls,
        listOf(CivBox(CivPosition(0, 0, 3), CivPosition(4, 0, 7))), listOf(1))
    val city = settlementPlanCity().copy(center = CivPosition(0, 0, 0), style = "nordic", buildings = buildings,
        autonomy = CivAutonomy(layoutHalfWidth = 48, plan = plan))
    return city to CivAutonomousAccess(routes)
}
