package ru.arc.civs

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsInfrastructureApproachTest : FunSpec({
    fun roadApproach(unaccepted: Boolean = false, endpointFloor: Int = 66,
        blocked: Boolean = false): Pair<CivSettlementInfrastructure, List<CivPosition>> {
        val hall = CivBuilding(UUID(163, 1), BuildingType.TOWN_HALL, 1, CivPosition(-20, 64, 0), 0, "fixture/hall",
            CivBox(CivPosition(-24, 64, -8), CivPosition(-16, 76, -1)), CivPosition(-20, 65, 0), BuildingPhase.ACTIVE)
        val blocker = CivBuilding(UUID(163, 2), BuildingType.HOUSE, 1, CivPosition(0, 64, 1), 0, "fixture/blocker",
            CivBox(CivPosition(0, 64, 1), CivPosition(0, 70, 1)), CivPosition(-1, 65, 1), BuildingPhase.ACTIVE)
        val city = Settlement(UUID(163, 3), UUID(163, 4), "Generated road approach", UUID(163, 5),
            CivPosition(0, 64, 0), 96, "fixture", Stock(), listOf(hall) + if (blocked) listOf(blocker) else emptyList(),
            emptyList(), autonomy = CivAutonomy())
        val surface = CivTerrainSurface(-64, 320, buildMap {
            for (x in -5..5) for (z in -5..5)
                put(civsTerrainKey(x, z), CivTerrainSurfaceColumn(if (unaccepted) 63 else 64, true))
            // This is a valid one-step native approach, but the house owns it; never request paving through it.
            if (blocked) put(civsTerrainKey(0, 1), CivTerrainSurfaceColumn(65, true))
        })
        // The vertical strip is partitioned first. Its last floor65 is the endpoint's only lawful floor66 approach.
        val verticalFeet = if (unaccepted) listOf(66, 66, 66, 66) else listOf(65, 65, 66, 66)
        val route = (-2..1).mapIndexed { i, z -> CivPosition(0, verticalFeet[i], z) } +
            listOf(CivPosition(1, endpointFloor + 1, 1), CivPosition(2, endpointFloor + 1, 1))
        val proposedRoutes = if (blocked) listOf(route.take(3), route.drop(4)) else listOf(route)
        val frame = CivsSettlementLayout.frame(city, 24).copy(wallObstacles = emptyList(), gates = emptyList())
        val json = GsonBuilder().serializeNulls().create()
        val before = json.toJson(listOf(city, surface, frame, proposedRoutes))
        val result = CivsSettlementLayout.infrastructure(city, frame, surface, proposedRoutes)
        json.toJson(listOf(city, surface, frame, proposedRoutes)) shouldBe before
        return result to route
    }

    test("an earlier generated road supplies its actual one-step approach grade") {
        val (result, route) = roadApproach()
        result.rejected shouldBe emptyMap()
        result.pieces.map { it.type } shouldBe listOf(BuildingType.ROAD, BuildingType.ROAD)
        result.pieces.map { it.transit } shouldBe listOf(route.take(4), route.drop(4))
        val endpoint = result.pieces.last()
        endpoint.bounds shouldBe CivBox(CivPosition(1, 66, 1), CivPosition(2, 68, 1))
        endpoint.entrance shouldBe CivPosition(0, 66, 1)
    }

    test("a two-step generated-road join is still rejected by the unchanged slope guard") {
        val (result, _) = roadApproach(endpointFloor = 67)
        result.rejected shouldBe mapOf("infrastructure-slope" to 2)
        result.pieces shouldBe emptyList()
    }

    test("an unaccepted future route cannot lend its proposed approach height") {
        val (result, _) = roadApproach(unaccepted = true)
        result.rejected shouldBe mapOf("infrastructure-no-approach" to 2)
        result.pieces shouldBe emptyList()
    }

    test("an occupied approach stays blocked even when its proposed route has a one-step grade") {
        val (result, route) = roadApproach(blocked = true)
        result.rejected shouldBe mapOf("infrastructure-no-approach" to 1)
        result.pieces.map { it.type } shouldBe listOf(BuildingType.ROAD)
        result.pieces.single().transit shouldBe route.take(3)
    }

})
