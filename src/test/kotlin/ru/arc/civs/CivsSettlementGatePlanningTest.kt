package ru.arc.civs

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.ArrayDeque
import kotlin.math.abs

class CivsSettlementGatePlanningTest : FunSpec({
    for (complete in listOf(false, true))
    test("new ${if (complete) "full" else "compact"} forecast joins real three-wide portals without nearby duplicate exits") {
        val base = settlementPlanCity().copy(center = CivPosition(1, 65, -1), radius = if (complete) 128 else 96,
            autonomy = CivAutonomy(layoutHalfWidth = if (complete) 74 else 32))
        val profile = if (complete) CivsAutonomy.completeProfile else
            listOf(BuildingType.TOWN_HALL, BuildingType.BUILDER_HUT, BuildingType.FORESTER)
        val templates = settlementPlanTemplates()
        val surface = settlementPlanSurface(base.copy(radius = 127))
        val diagnostic = layoutFixtureDiagnostics("new-gates-${if (complete) "full" else "compact"}", base, templates, surface)
        val searches = mutableListOf<CivRouteSearchStats>()
        var generated: CivSettlementInfrastructure? = null
        val plan = try { CivsSettlementPlans.prepare(base, templates, surface,
            CivAutonomousAccess(), profile = profile, inspectRoute = searches::add, inspect = { candidate, infrastructure ->
                generated = infrastructure
                diagnostic?.invoke(candidate, infrastructure)
            }) }
        catch (failure: CivsRejection) {
            throw AssertionError("${failure.reason}: ${failure.values}; last search=${searches.lastOrNull()}", failure)
        }
        val infrastructure = requireNotNull(generated)
        infrastructure.rejected shouldBe emptyMap()
        infrastructure.perimeterClosed shouldBe true
        val gates = infrastructure.pieces.filter { it.type == BuildingType.GATE }
        gates.size shouldBe plan.gates.size
        if (!complete) gates.size shouldBe 1
        for ((index, gate) in gates.withIndex()) {
            gate.bounds.max.x - gate.bounds.min.x shouldBe 4
            gate.bounds.max.z - gate.bounds.min.z shouldBe 4
            gate.transit.size shouldBe 19
            CivsGateRefit.validTransit(CivsInfrastructure.building(gate)) shouldBe true
            plan.routes.values.flatten().filter { CivsAutonomousGeometry.horizontalContains(gate.bounds, it) }
                .all { it in gate.transit } shouldBe true
            val acrossX = CivsInfrastructureRebuild.axis(CivsInfrastructure.building(gate))
            val axis = if (acrossX) (gate.bounds.min.x + gate.bounds.max.x) / 2 else (gate.bounds.min.z + gate.bounds.max.z) / 2
            val normal = if (acrossX) (gate.bounds.min.z + gate.bounds.max.z) / 2 else (gate.bounds.min.x + gate.bounds.max.x) / 2
            for ((owner, route) in plan.routes) {
                val crossing = route.indices.filter { CivsAutonomousGeometry.horizontalContains(gate.bounds, route[it]) }
                if (crossing.isEmpty()) continue
                // A real entrance inside the approach channel has its own short forecourt;
                // through traffic without such an endpoint must use the full axial approach.
                val localEntrance = listOf(route.first(), route.last()).any { p ->
                    if (acrossX) abs(p.x - axis) <= 3 && abs(p.z - normal) <= 7
                    else abs(p.z - axis) <= 3 && abs(p.x - normal) <= 7
                }
                if (localEntrance) continue
                val approach = route.subList((crossing.first() - 4).coerceAtLeast(0),
                    (crossing.last() + 5).coerceAtMost(route.size))
                withClue("$owner must share a straight centre street through ${gate.bounds}: ") {
                    approach.all { if (acrossX) it.x == axis else it.z == axis } shouldBe true
                }
            }
            for (other in gates.drop(index + 1)) {
                val dx = (gate.bounds.min.x + gate.bounds.max.x - other.bounds.min.x - other.bounds.max.x) / 2
                val dz = (gate.bounds.min.z + gate.bounds.max.z - other.bounds.min.z - other.bounds.max.z) / 2
                (abs(dx) + abs(dz) > 16) shouldBe true
            }
        }
        if (!complete) {
            val worksite = plan.plots.single { it.building.type == BuildingType.FORESTER }.building
            val route = plan.routes.getValue(worksite.id.toString())
            val gate = gates.single()
            val alongX = CivsInfrastructureRebuild.axis(CivsInfrastructure.building(gate))
            val center = CivPosition((gate.bounds.min.x + gate.bounds.max.x) / 2, worksite.entrance.y,
                (gate.bounds.min.z + gate.bounds.max.z) / 2)
            val join = if (alongX) worksite.entrance.copy(x = center.x) else worksite.entrance.copy(z = center.z)
            val entryIndex = route.indexOf(center); val joinIndex = route.indexOf(join)
            withClue("The street must continue through the gate axis to the worksite frontage: ") {
                (entryIndex >= 0 && joinIndex > entryIndex) shouldBe true
                route.subList(entryIndex, joinIndex + 1).all { if (alongX) it.x == center.x else it.z == center.z } shouldBe true
                route.filter { CivsAutonomousGeometry.horizontalContains(gate.bounds, it) }
                    .all { if (alongX) it.x == center.x else it.z == center.z } shouldBe true
                route.last() shouldBe worksite.entrance
                route.size - 1 shouldBe 47
                searches.count { it.to == join } shouldBe 1
            }

            // Unsafe optional frontage falls back before the one ordinary search. Neither an
            // unloaded nor a protected stub cell may be appended to an otherwise valid path.
            val forecast = base.copy(buildings = plan.plots.map { it.building.copy(bounds = it.envelope,
                phase = BuildingPhase.ACTIVE) }, autonomy = base.autonomy!!.copy(plan = plan))
            val frame = CivsSettlementLayout.frame(forecast)
            val obstacles = plan.walls + CivsSettlementLayout.gateConnectionObstacles(frame)
            val blocked = join.copy(x = join.x + (worksite.entrance.x - join.x).coerceIn(-1, 1),
                z = join.z + (worksite.entrance.z - join.z).coerceIn(-1, 1))
            for (column in listOf(CivTerrainSurfaceColumn(64, natural = false),
                CivTerrainSurfaceColumn(64, natural = true, loaded = false))) {
                val terrain = surface.copy(columns = surface.columns + (civsTerrainKey(blocked.x, blocked.z) to column))
                val fallbackSearches = mutableListOf<CivRouteSearchStats>()
                val fallback = CivsAutonomousTerraform.connection(forecast, worksite, terrain, CivAutonomousAccess(),
                    obstacles, fallbackSearches::add, join)
                fallback.last() shouldBe worksite.entrance
                fallback.none { it.x == blocked.x && it.z == blocked.z } shouldBe true
                fallbackSearches.size shouldBe 1
                fallbackSearches.single().to shouldBe worksite.entrance
            }
        }
        // Close only each intended core in this detached flat-grid check. Any remaining escape
        // would be a physical flank/terrain seam, even if rectangle coverage claimed a perimeter.
        val states = infrastructure.pieces.flatMap { it.blocks }.associate { it.position to it.after }
        val blocked = states.filter { (p, state) -> p.y in 65..66 && state != "minecraft:air" }
            .keys.mapTo(hashSetOf()) { it.x to it.z }
        gates.forEach { gate ->
            val building = CivsInfrastructure.building(gate)
            val alongX = CivsInfrastructureRebuild.axis(building)
            val middle = if (alongX) (gate.bounds.min.z + gate.bounds.max.z) / 2 else (gate.bounds.min.x + gate.bounds.max.x) / 2
            gate.transit.filter { if (alongX) it.z == middle else it.x == middle }.forEach { blocked += it.x to it.z }
        }
        val start = base.buildings.single().entrance.let { it.x to it.z }
        val seen = hashSetOf(start)
        val queue = ArrayDeque<Pair<Int, Int>>().apply { add(start) }
        var escape: Pair<Int, Int>? = null
        while (queue.isNotEmpty() && escape == null) {
            val (x, z) = queue.removeFirst()
            if (abs(x - base.center.x) >= base.radius || abs(z - base.center.z) >= base.radius) { escape = x to z; break }
            for ((dx, dz) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                val next = x + dx to z + dz
                if (next !in blocked && seen.add(next)) queue.add(next)
            }
        }
        withClue("Unexpected escape around a sealed intended portal: ") { escape shouldBe null }
    }
})
