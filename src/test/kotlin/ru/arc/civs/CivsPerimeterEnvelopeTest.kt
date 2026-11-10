package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CivsPerimeterEnvelopeTest : FunSpec({
    fun fixture(): Pair<Settlement, Map<UUID, List<CivPosition>>> {
        val base = settlementPlanCity().copy(radius = 128)
        val forester = CivBuilding(UUID(163, 20), BuildingType.FORESTER, 1, CivPosition(75, 64, 0), 0, "fixture/forest",
            CivBox(CivPosition(75, 64, -7), CivPosition(81, 71, -1)), CivPosition(75, 65, 0), BuildingPhase.ACTIVE)
        return base.copy(buildings = base.buildings + forester) to
            mapOf(forester.id to (0..75).map { CivPosition(it, 65, 0) })
    }

    fun corridor(surface: CivTerrainSurface): CivTerrainSurface = surface.copy(columns = surface.columns.toMutableMap().apply {
        // A protected chain starts next to the hall and runs beyond the old north edge74, ending before82.
        for (z in -76..-9) put(civsTerrainKey(0, z), CivTerrainSurfaceColumn(64, natural = false))
    })

    fun clipped(surface: CivTerrainSurface, box: CivBox): CivTerrainSurface = surface.copy(columns = surface.columns.filterKeys {
        (it shr 32).toInt() in box.min.x..box.max.x && it.toInt() in box.min.z..box.max.z
    })

    test("one bounded outer envelope closes around a protected chain beyond the ordinary core") {
        val (city, routes) = fixture()
        val frame = CivsSettlementLayout.frame(city, 74)
        val surface = corridor(settlementPlanSurface(city))
        val before = Gson().toJson(listOf(city, frame, surface, routes))
        // Exact same old canvas and cells cannot separate this seed-to-edge INFINITE chain.
        val old = shouldThrow<CivsRejection> {
            CivsSettlementPerimeter.adapt(city, frame, clipped(surface, frame.core), routes, emptyList())
        }
        old.reason shouldBe "autonomy-layout-obstructed"
        old.values["stage"] shouldBe "no-dry-perimeter"
        old.values["inner_boundary_seeds"] shouldBe "0"

        val adapted = CivsSettlementPerimeter.adapt(city, frame, surface, routes, emptyList())
        adapted.core shouldBe frame.core
        adapted.halfWidth shouldBe 74
        adapted.wallObstacles.any { it.min.z < frame.core.min.z } shouldBe true
        // These routes have no retained receipt. As in full planning, reconnect the provisional
        // approach after the separator chooses a sound gate junction around the protected chain.
        val connected = routes.keys.map { id -> CivsAutonomousTerraform.connection(city,
            requireNotNull(city.building(id)), surface, CivAutonomousAccess(),
            adapted.wallObstacles + CivsSettlementLayout.gateConnectionObstacles(adapted)) }
        val infrastructure = CivsSettlementLayout.infrastructure(city, adapted, surface, connected)
        withClue("Gates=${adapted.gates}; rejected=${infrastructure.rejected}; samples=${infrastructure.rejectionSamples}: ") {
            infrastructure.perimeterClosed shouldBe true
        }
        infrastructure.rejected shouldBe emptyMap()
        (infrastructure.pieces.size <= CivsRules.MAX_INFRASTRUCTURE) shouldBe true
        Gson().toJson(listOf(city, frame, surface, routes)) shouldBe before
    }

    test("a paid strip that crosses the old edge can be enclosed on the fully captured outer canvas") {
        val base = settlementPlanCity().copy(radius = 128)
        val house = CivBuilding(UUID(163, 31), BuildingType.HOUSE, 1, CivPosition(-60, 64, -3), 0, "fixture/house",
            CivBox(CivPosition(-62, 64, -5), CivPosition(-56, 70, -1)), CivPosition(-60, 65, 0), BuildingPhase.ACTIVE)
        val quarry = CivBuilding(UUID(163, 32), BuildingType.QUARRY, 1, CivPosition(72, 64, -4), 0, "fixture/quarry",
            CivBox(CivPosition(69, 64, -7), CivPosition(75, 71, -1)), CivPosition(72, 65, 0), BuildingPhase.ACTIVE)
        val city = base.copy(buildings = base.buildings + house + quarry)
        val frame = CivsSettlementLayout.frame(city, 64)
        val route = (0..72).map { CivPosition(it, 65, 0) }
        val routes = mapOf(house.id to (0 downTo -60).map { CivPosition(it, 65, 0) }, quarry.id to route)
        val strip = CivBox(CivPosition(-65, 64, -1), CivPosition(-63, 69, -1))
        val surface = settlementPlanSurface(city).let { field ->
            field.copy(columns = field.columns + (civsTerrainKey(-64, -1) to CivTerrainSurfaceColumn(68, true)))
        }

        val result = CivsSettlementPerimeter.adapt(city, frame, surface, routes, emptyList(), insidePaid = listOf(strip))
        result.core shouldBe frame.core
        result.gates.size shouldBe 1
        val interior = CivsSettlementGrowth.interior(result)
        (-65..-63).all { civsTerrainKey(it, -1) in interior } shouldBe true
        (quarry.bounds.min.x..quarry.bounds.max.x).all { x ->
            (quarry.bounds.min.z..quarry.bounds.max.z).none { z -> civsTerrainKey(x, z) in interior }
        } shouldBe true
        (result.wallObstacles + result.gates.map { it.passage }).none {
            CivsAutonomousGeometry.overlaps(it, strip) || CivsAutonomousGeometry.overlaps(it, quarry.bounds)
        } shouldBe true

        val missing = surface.copy(columns = surface.columns - civsTerrainKey(-65, -1))
        val far = CivBox(CivPosition(-73, 64, 0), CivPosition(-73, 69, 0))
        for ((capture, strips) in listOf(missing to listOf(strip), surface to listOf(strip, far))) {
            val refusal = shouldThrow<CivsRejection> {
                CivsSettlementPerimeter.adapt(city, frame, capture, routes, emptyList(), insidePaid = strips)
            }
            refusal.values["stage"] shouldBe "no-dry-perimeter"
            refusal.values["inner_boundary_seeds"] shouldBe "1"
            refusal.values["inner_route_boundary"] shouldBe "none"
        }

        val longRoute = (0 downTo -72).map { CivPosition(it, 65, 0) } +
            (-72..-60).map { CivPosition(it, 65, 1) } + house.entrance
        val clippedRoute = shouldThrow<CivsRejection> {
            CivsSettlementPerimeter.adapt(city, frame, surface, routes + (house.id to longRoute), emptyList(),
                insidePaid = listOf(strip))
        }
        clippedRoute.values["stage"] shouldBe "no-dry-perimeter"
        clippedRoute.values["inner_boundary_seeds"] shouldBe "3"
        clippedRoute.values["inner_route_boundary"]!!.startsWith("HOUSE:") shouldBe true

        val enclosedQuarry = quarry.copy(id = UUID(163, 33), anchor = CivPosition(-27, 64, 3),
            bounds = CivBox(CivPosition(-28, 64, 2), CivPosition(-26, 71, 4)), entrance = CivPosition(-27, 65, 2))
        val aroundQuarry = (0 downTo -25).map { CivPosition(it, 65, 0) } +
            (1..5).map { CivPosition(-25, 65, it) } + (-26 downTo -29).map { CivPosition(it, 65, 5) } +
            (4 downTo 1).map { CivPosition(-29, 65, it) } + (-28..-26).map { CivPosition(it, 65, 1) } +
            (-26 downTo -60).map { CivPosition(it, 65, 0) }
        val quarryRefusal = shouldThrow<CivsRejection> {
            CivsSettlementPerimeter.adapt(city.copy(buildings = city.buildings + enclosedQuarry), frame, surface,
                routes + (house.id to aroundQuarry), emptyList(), insidePaid = listOf(strip))
        }
        quarryRefusal.values["stage"] shouldBe "retained-boundary-quarry"
    }

    test("an already accepted flat separator keeps its old-canvas geometry") {
        val (city, routes) = fixture()
        val frame = CivsSettlementLayout.frame(city, 74)
        val surface = settlementPlanSurface(city)
        val legacy = CivsSettlementPerimeter.adapt(city, frame, clipped(surface, frame.core), routes, emptyList())
        val adapted = CivsSettlementPerimeter.adapt(city, frame, surface, routes, emptyList())
        adapted shouldBe legacy
        adapted.core shouldBe frame.core
        adapted.halfWidth shouldBe 74
        (adapted.wallObstacles + adapted.gates.map { it.passage }).all { edge ->
            edge.min.x >= frame.core.min.x && edge.max.x <= frame.core.max.x &&
                edge.min.z >= frame.core.min.z && edge.max.z <= frame.core.max.z
        } shouldBe true
    }

    test("a surface with no extra captured canvas retains the original refusal") {
        val (city, routes) = fixture()
        val frame = CivsSettlementLayout.frame(city, 74)
        val surface = clipped(corridor(settlementPlanSurface(city)), frame.core)
        val before = Gson().toJson(surface)
        val failure = shouldThrow<CivsRejection> {
            CivsSettlementPerimeter.adapt(city, frame, surface, routes, emptyList())
        }
        failure.reason shouldBe "autonomy-layout-obstructed"
        failure.values["stage"] shouldBe "no-dry-perimeter"
        failure.values["inner_boundary_seeds"] shouldBe "0"
        Gson().toJson(surface) shouldBe before
    }
})
