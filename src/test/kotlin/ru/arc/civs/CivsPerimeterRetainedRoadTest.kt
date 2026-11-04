package ru.arc.civs

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs

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

        val admitted = try { CivsSettlementPerimeter.adapt(city, frame, surface, routes, listOf(route)) }
        catch (failure: CivsRejection) { throw AssertionError("${failure.reason}: ${failure.values}", failure) }
        admitted.gates.size shouldBe 1
        admitted.gates.single().passage.let { it.max.z - it.min.z + 1 >= 3 } shouldBe true
        route.none { p -> admitted.wallObstacles.any { CivsAutonomousGeometry.horizontalContains(it, p) } } shouldBe true
        val infrastructure = CivsSettlementLayout.infrastructure(city, admitted, surface, routes.values.toList(),
            retainedRoutes = listOf(route))
        withClue("Gates=${admitted.gates}; rejected=${infrastructure.rejected}: ") {
            infrastructure.perimeterClosed shouldBe true
        }
        infrastructure.rejected shouldBe emptyMap()
        val road = route.flatMap { p -> (-1..1).map { p.copy(z = p.z + it) } }
        road.none { p -> admitted.wallObstacles.any { CivsAutonomousGeometry.horizontalContains(it, p) } } shouldBe true
        val gate = infrastructure.pieces.single { it.type == BuildingType.GATE }
        road.filter { CivsAutonomousGeometry.horizontalContains(gate.bounds, it) }.all { it in gate.transit } shouldBe true
        val structural = infrastructure.pieces.filter { it.type in setOf(BuildingType.WALL, BuildingType.GATE) }
        val body = road.flatMapTo(hashSetOf()) { listOf(it, it.copy(y = it.y + 1)) }
        structural.flatMap { it.blocks }.none { it.position in body && it.after != "minecraft:air" } shouldBe true

        // Test physical closure independently of rectangle coverage and the local junction proof.
        // Seal the intended core only: a bad cap or flank must still reveal an escape here.
        val blocked = structural.flatMap { it.blocks }.filter { it.position.y in 65..66 && it.after != "minecraft:air" }
            .mapTo(hashSetOf()) { it.position.x to it.position.z }
        val alongX = CivsInfrastructureRebuild.axis(CivsInfrastructure.building(gate))
        val middle = if (alongX) (gate.bounds.min.z + gate.bounds.max.z) / 2 else (gate.bounds.min.x + gate.bounds.max.x) / 2
        gate.transit.filter { if (alongX) it.z == middle else it.x == middle }.forEach { blocked += it.x to it.z }
        val start = city.buildings.first().entrance.let { it.x to it.z }
        val seen = hashSetOf(start)
        val queue = ArrayDeque<Pair<Int, Int>>().apply { add(start) }
        var escape: Pair<Int, Int>? = null
        while (queue.isNotEmpty() && escape == null) {
            val (x, z) = queue.removeFirst()
            if (abs(x - city.center.x) >= city.radius || abs(z - city.center.z) >= city.radius) { escape = x to z; break }
            for ((dx, dz) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                val next = x + dx to z + dz
                if (next !in blocked && seen.add(next)) queue += next
            }
        }
        withClue("Escape around a sealed paid-road gate: ") { escape shouldBe null }
        layoutFixtureDiagnostics("retained-road-collar", city, emptyMap(), surface)?.invoke(
            CivSettlementPlan(CivsSettlementPlans.VERSION, 0, admitted.halfWidth,
                city.buildings.map { CivSettlementPlot(it, it.bounds) }, routes.mapKeys { it.key.toString() },
                admitted.wallObstacles, admitted.gates.map { it.passage }, admitted.gates.map { it.side }), infrastructure)
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
