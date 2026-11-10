package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor

class CivsShorePerimeterTest : FunSpec({
    test("a local shore can complete an adopted perimeter with dry gates and ordinary paid walls") {
        val (city, access) = shorePerimeterCity()
        val flat = settlementPlanSurface(city)
        val dry = recoverShore(city, access, flat) { error("Dry contour needs no shore survey") }
        dry.perimeterEntrances.isNullOrEmpty() shouldBe false

        val opaque = shoreCorridor(flat)
        var surveyed = emptyMap<Long, CivTerrainSurfaceColumn>()
        val recovered = recoverShore(city, access, opaque) { columns ->
            columns.isNotEmpty() shouldBe true
            columns.all { it.x == 0 && it.y == 64 && it.z in -16..-9 } shouldBe true
            surveyed = columns.associate { civsTerrainKey(it.x, it.z) to
                CivTerrainSurfaceColumn(64, natural = false, waterTopY = 64, waterBottomY = 62) }
            opaque.copy(columns = surveyed)
        }
        recovered.deferredPerimeter shouldBe null
        recovered.routes shouldBe city.autonomy!!.plan!!.routes
        val adopted = city.copy(autonomy = city.autonomy.copy(plan = recovered))
        val frame = CivsSettlementLayout.frame(adopted)
        val surface = opaque.copy(columns = opaque.columns + surveyed)
        val layout = CivsSettlementLayout.infrastructure(adopted, frame, surface, access.routes.values.toList(),
            pavingRoutes = emptyList(), retainedRoutes = access.routes.values, retainedAccess = access, protectPaidGateFloors = true)
        layout.rejected shouldBe emptyMap()
        layout.perimeterClosed shouldBe true
        CivsRetainedGrowthPerimeter.closed(frame, layout.pieces) shouldBe true
        recovered.perimeterEntrances!!.keys shouldBe layout.pieces.mapTo(hashSetOf()) { it.id.toString() }
        val gates = layout.pieces.filter { it.type == BuildingType.GATE }
        gates.isNotEmpty() shouldBe true
        gates.all { it.waterFoundations.isEmpty() && it.paidGateFloors.isNotEmpty() } shouldBe true
        val paidColumns = access.roadColumns(adopted)
        val wet = layout.pieces.filter { it.waterFoundations.isNotEmpty() }
        wet.isNotEmpty() shouldBe true
        for (piece in wet) {
            piece.type shouldBe BuildingType.WALL
            val building = CivsInfrastructure.building(piece).copy(entrance =
                recovered.perimeterEntrances.getValue(piece.id.toString()))
            val draft = CivsAutonomousTerraform.draft(adopted, CivTerraformOption(building, 0), surface, access,
                frame.wallObstacles, piece.floorGrades(), waterFoundations = piece.waterFoundations,
                protectedRoadColumns = paidColumns)
            draft.waterFoundations shouldBe piece.waterFoundations
            draft.route.last() shouldBe building.entrance
            draft.columns.none { !it.preserve && civsTerrainKey(it.x, it.z) in paidColumns } shouldBe true
        }

        // Rebuilding a previously admitted footprint after drift also refuses a water gate.
        val gate = frame.gates.first()
        for (point in listOf(gate.center, gate.apron(frame.rotation).min)) {
            val drifted = surface.copy(columns = surface.columns + (civsTerrainKey(point.x, point.z) to
                CivTerrainSurfaceColumn(64, natural = false, waterTopY = 64, waterBottomY = 62)))
            val refused = CivsSettlementLayout.infrastructure(adopted, frame, drifted, access.routes.values.toList(),
                pavingRoutes = emptyList(), retainedRoutes = access.routes.values, retainedAccess = access, protectPaidGateFloors = true)
            refused.rejected["gate-wet"] shouldBe 1
            refused.perimeterClosed shouldBe false
        }
    }

    test("unproved or drifted beds leave the original refusal after one attempt") {
        val (city, access) = shorePerimeterCity()
        val opaque = shoreCorridor(settlementPlanSurface(city))
        val failure = shouldThrow<CompletionException> {
            recoverShore(city, access, opaque) { columns ->
                opaque.copy(columns = columns.associate { p -> civsTerrainKey(p.x, p.z) to
                    CivTerrainSurfaceColumn(63, natural = false, waterTopY = 63, waterBottomY = 61) })
            }
        }.cause as CivsRejection
        failure.reason shouldBe "autonomy-layout-obstructed"
        failure.values["stage"] shouldBe "no-dry-perimeter"
        failure.values["shore_proved"] shouldBe "0"
    }
})

private fun shoreCorridor(flat: CivTerrainSurface) = flat.copy(columns = flat.columns +
    (-38..-9).map { 0 to it }.plus(40 to 36).associate { (x, z) ->
        civsTerrainKey(x, z) to CivTerrainSurfaceColumn(64, natural = false, waterTopY = 64)
    })

private fun recoverShore(city: Settlement, access: CivAutonomousAccess, initial: CivTerrainSurface,
    sample: (List<CivPosition>) -> CivTerrainSurface): CivSettlementPlan {
    val world = mockk<CivsWorld>()
    val hall = city.buildings.single { it.type == BuildingType.TOWN_HALL }
    var requests = 0
    every { world.autonomousHeightField(city, hall, hall, markWaterTop = true) } returns
        CompletableFuture.completedFuture(initial)
    every { world.autonomousShoreField(city, hall, any()) } answers {
        (++requests <= 1) shouldBe true
        CompletableFuture.completedFuture(sample(thirdArg()))
    }
    return CivsAutonomousPlacement(mockk(), world, Executor(Runnable::run),
        loadAccess = { CompletableFuture.completedFuture(access) }).use { placement ->
        try { placement.plan(city, recoverPerimeter = true).join() }
        finally { verify(exactly = 1) { world.autonomousHeightField(city, hall, hall, markWaterTop = true) } }
    }
}

private fun shorePerimeterCity(): Pair<Settlement, CivAutonomousAccess> {
    val base = settlementPlanCity().copy(radius = 48)
    val civic = CivBuilding(UUID(164, 1), BuildingType.SMELTERY, 1, CivPosition(12, 64, -4), 0,
        "fixture/smeltery", CivBox(CivPosition(10, 64, -4), CivPosition(14, 68, -2)),
        CivPosition(12, 65, -1), BuildingPhase.ACTIVE)
    val quarry = CivBuilding(UUID(164, 2), BuildingType.QUARRY, 1, CivPosition(36, 64, -6), 0,
        "fixture/quarry", CivBox(CivPosition(34, 64, -6), CivPosition(38, 68, -2)),
        CivPosition(36, 65, 0), BuildingPhase.ACTIVE)
    val city = base.copy(buildings = base.buildings + civic + quarry)
    val frame = CivsSettlementLayout.frame(city, 24)
    val quarryRoute = (0..36).map { CivPosition(it, 65, 0) }
    val routes = mapOf(civic.id to quarryRoute.take(13) + civic.entrance, quarry.id to quarryRoute)
    val plan = CivSettlementPlan(CivsSettlementPlans.VERSION, 5, 24,
        city.buildings.map { CivSettlementPlot(it, it.bounds) }, routes.mapKeys { it.key.toString() },
        frame.wallObstacles, frame.gates.map { it.passage }, frame.gates.map { it.side },
        CivDeferredPerimeter(CivsSettlementPlans.CIVIC_PERIMETER, civic.id, civic.anchor))
    return city.copy(autonomy = city.autonomy!!.copy(plan = plan, layoutHalfWidth = 24)) to CivAutonomousAccess(routes)
}
