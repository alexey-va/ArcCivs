package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsSettlementPerimeterLimitsTest : FunSpec({
    test("the terrain separator accepts the shared maximum core and rejects either oversized dimension") {
        val base = settlementPlanCity()
        val farm = CivBuilding(UUID(138, 10), BuildingType.FORESTER, 1, CivPosition(75, 64, 0), 0, "fixture/forest",
            CivBox(CivPosition(75, 64, -7), CivPosition(81, 71, -1)), CivPosition(75, 65, 0), BuildingPhase.ACTIVE)
        val city = base.copy(buildings = base.buildings + farm)
        val frame = CivsSettlementLayout.frame(city)
        frame.halfWidth shouldBe 72
        val routes = mapOf(farm.id to (0..75).map { CivPosition(it, 65, 0) })
        val surface = settlementPlanSurface(city)
        val adapted = CivsSettlementPerimeter.adapt(city, frame, surface, routes, emptyList())
        adapted.wallObstacles.isNotEmpty() shouldBe true
        adapted.gates.isNotEmpty() shouldBe true
        (adapted.wallObstacles.size + adapted.gates.size <= CivsRules.MAX_INFRASTRUCTURE) shouldBe true
        for (tooLarge in listOf(frame.core.copy(max = frame.core.max.copy(x = frame.core.max.x + 1)),
            frame.core.copy(max = frame.core.max.copy(z = frame.core.max.z + 1)))) {
            shouldThrow<IllegalArgumentException> {
                CivsSettlementPerimeter.adapt(city, frame.copy(core = tooLarge), surface, routes, emptyList())
            }
        }
        val house = farm.copy(id = UUID(138, 11), type = BuildingType.HOUSE, anchor = CivPosition(30, 64, 5), rotation = 180,
            bounds = CivBox(CivPosition(27, 64, 6), CivPosition(33, 71, 12)), entrance = CivPosition(30, 65, 5))
        val edge = frame.core.max.x
        val detour = (0..edge).map { CivPosition(it, 65, 0) } + (1..5).map { CivPosition(edge, 65, it) } +
            (edge - 1 downTo 30).map { CivPosition(it, 65, 5) }
        val rejected = shouldThrow<CivsRejection> {
            CivsSettlementPerimeter.adapt(city.copy(buildings = city.buildings + house), frame, surface,
                routes + (house.id to detour), emptyList())
        }
        rejected.reason shouldBe "autonomy-layout-obstructed"
        rejected.values["inner_boundary_seeds"] shouldBe "6"
        rejected.values["first_steep_seed"] shouldBe "none"
        rejected.values["inner_route_boundary"]!!.startsWith("HOUSE:") shouldBe true
    }
})
