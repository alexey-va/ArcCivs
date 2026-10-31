package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsSettlementPerimeterLimitsTest : FunSpec({
    test("fortified plots reserve enough edge room for a complete five-wide perimeter") {
        val base = settlementPlanCity().copy(radius = 128)
        val frame = CivsSettlementLayout.frame(base, 72).copy(fortified = true)
        val template = CivBuilding(UUID(143, 1), BuildingType.HOUSE, 1, CivPosition(0, 0, 0), 0, "fixture/edge-house",
            CivBox(CivPosition(-9, 0, -16), CivPosition(8, 18, -1)), CivPosition(0, 1, 0), BuildingPhase.ACTIVE)
        // The native replay's rejected house had exactly this four-column gap to the core edge.
        val edgeBounds = CivBox(CivPosition(51, 64, -20), CivPosition(68, 82, -5))
        val legacy = CivsSettlementLayout.infill(base, template, template, frame = frame.copy(fortified = false),
            eligible = { it.bounds == edgeBounds }).single().building
        CivsSettlementLayout.infill(base, template, template, frame = frame,
            eligible = { it.bounds == edgeBounds }) shouldBe emptyList()

        val forester = CivBuilding(UUID(143, 2), BuildingType.FORESTER, 1, CivPosition(75, 64, 0), 0, "fixture/forest",
            CivBox(CivPosition(75, 64, -7), CivPosition(81, 71, -1)), CivPosition(75, 65, 0), BuildingPhase.ACTIVE)
        val city = base.copy(buildings = base.buildings + forester + legacy)
        val routes = mapOf(forester.id to (0..75).map { CivPosition(it, 65, 0) })
        val surface = settlementPlanSurface(city)
        val blocked = shouldThrow<CivsRejection> {
            CivsSettlementPerimeter.adapt(city, frame, surface, routes, emptyList())
        }
        blocked.reason shouldBe "autonomy-layout-obstructed"
        blocked.values["stage"] shouldBe "no-dry-perimeter"
        blocked.values["inner_boundary_seeds"] shouldBe "0"
        blocked.values["first_steep_seed"] shouldBe "none"

        // One more free column admits the existing separator without moving its boundary or narrowing the wall.
        val inset = legacy.copy(anchor = legacy.anchor.copy(x = legacy.anchor.x - 1),
            entrance = legacy.entrance.copy(x = legacy.entrance.x - 1),
            bounds = CivBox(legacy.bounds.min.copy(x = legacy.bounds.min.x - 1), legacy.bounds.max.copy(x = legacy.bounds.max.x - 1)))
        val admitted = CivsSettlementPerimeter.adapt(city.copy(buildings = base.buildings + forester + inset),
            frame, surface, routes, emptyList())
        admitted.core shouldBe frame.core
        admitted.wallObstacles.isNotEmpty() shouldBe true
        admitted.gates.isNotEmpty() shouldBe true
        admitted.wallObstacles.none { CivsAutonomousGeometry.overlaps(it, inset.bounds) } shouldBe true
        (admitted.wallObstacles.size + admitted.gates.size <= CivsRules.MAX_INFRASTRUCTURE) shouldBe true
    }

    test("the terrain separator accepts the shared maximum core and rejects either oversized dimension") {
        val base = settlementPlanCity().copy(radius = 128)
        val farm = CivBuilding(UUID(138, 10), BuildingType.FORESTER, 1, CivPosition(75, 64, 0), 0, "fixture/forest",
            CivBox(CivPosition(75, 64, -7), CivPosition(81, 71, -1)), CivPosition(75, 65, 0), BuildingPhase.ACTIVE)
        val city = base.copy(buildings = base.buildings + farm)
        // Full planning selects its new-town frame before reserving exterior plots.
        val frame = CivsSettlementLayout.frame(base)
        frame.halfWidth shouldBe 74
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
