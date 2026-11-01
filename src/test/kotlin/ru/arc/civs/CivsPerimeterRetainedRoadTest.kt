package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsPerimeterRetainedRoadTest : FunSpec({
    test("a retained three-wide dirt road admits its gate while unrelated or obstructed shoulders remain protected") {
        val base = settlementPlanCity().copy(radius = 128)
        val forester = CivBuilding(UUID(175, 1), BuildingType.FORESTER, 1, CivPosition(75, 64, 0), 0, "fixture/forest",
            CivBox(CivPosition(75, 64, -7), CivPosition(81, 71, -1)), CivPosition(75, 65, 0), BuildingPhase.ACTIVE)
        val city = base.copy(buildings = base.buildings + forester)
        val frame = CivsSettlementLayout.frame(base, 74)
        val route = (0..75).map { CivPosition(it, 65, 0) }
        val routes = mapOf(forester.id to route)
        val surface = settlementPlanSurface(city).let { flat -> flat.copy(columns = flat.columns.toMutableMap().apply {
            // The road runs to the captured edge, so extra separator canvas cannot hide a rejected shoulder.
            for (x in 0..city.radius) for (z in -1..1)
                put(civsTerrainKey(x, z), CivTerrainSurfaceColumn(64, natural = false, dirtPath = true))
        }) }
        val before = Gson().toJson(listOf(city, frame, surface, routes))

        val admitted = CivsSettlementPerimeter.adapt(city, frame, surface, routes, listOf(route))
        admitted.gates.size shouldBe 1
        admitted.gates.single().passage.let { it.max.z - it.min.z + 1 >= 3 } shouldBe true
        route.none { p -> admitted.wallObstacles.any { CivsAutonomousGeometry.horizontalContains(it, p) } } shouldBe true
        val infrastructure = CivsSettlementLayout.infrastructure(city, admitted, surface, routes.values.toList(),
            retainedRoutes = listOf(route))
        infrastructure.perimeterClosed shouldBe true
        infrastructure.rejected shouldBe emptyMap()
        Gson().toJson(listOf(city, frame, surface, routes)) shouldBe before

        fun refuses(terrain: CivTerrainSurface, retained: Collection<List<CivPosition>>) {
            val failure = shouldThrow<CivsRejection> {
                CivsSettlementPerimeter.adapt(city, frame, terrain, routes, retained)
            }
            failure.reason shouldBe "autonomy-layout-obstructed"
            failure.values["stage"] shouldBe "no-dry-perimeter"
        }
        refuses(surface, emptyList()) // A dirt path alone grants no retained-route context.
        for (shoulder in listOf(
            CivTerrainSurfaceColumn(64, natural = false, dirtPath = false),
            CivTerrainSurfaceColumn(64, natural = false, loaded = false, dirtPath = true),
            CivTerrainSurfaceColumn(64, natural = false, topY = 65, dirtPath = true),
            CivTerrainSurfaceColumn(66, natural = false, dirtPath = true),
        )) refuses(surface.copy(columns = surface.columns.toMutableMap().apply {
            for (x in 0..city.radius) for (z in listOf(-1, 1)) put(civsTerrainKey(x, z), shoulder)
        }), listOf(route))
    }
})
