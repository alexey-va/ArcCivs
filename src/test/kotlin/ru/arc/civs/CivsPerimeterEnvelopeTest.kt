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
