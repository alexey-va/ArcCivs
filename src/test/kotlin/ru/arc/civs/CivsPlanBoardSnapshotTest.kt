package ru.arc.civs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class CivsPlanBoardSnapshotTest : FunSpec({
    test("the board reads and detaches the same adopted plan used by construction") {
        val base = settlementPlanCity()
        val plan = CivsSettlementPlans.prepare(base, settlementPlanTemplates(), settlementPlanSurface(base), CivAutonomousAccess())
        val city = base.copy(autonomy = base.autonomy!!.copy(plan = plan))
        val captured = civsPlanBoardCapture(city)
        val snapshot = civsPlanBoardSnapshot(captured, emptyList())
        snapshot.plan shouldBe plan
        snapshot.plan!!.plots.size shouldBe 16
        snapshot.plannedBuildings shouldBe emptyList()
        city.buildings shouldBe base.buildings
        (civsPlanBoardLayoutKey(base) != civsPlanBoardLayoutKey(city)) shouldBe true
        (civsPlanBoardLayoutKey(city) != civsPlanBoardLayoutKey(city.copy(autonomy = city.autonomy!!.copy(
            plan = plan.copy(revision = plan.revision + 1))))) shouldBe true
    }

    test("active bodies survive upgrade overlays while registered sites and current construction deduplicate") {
        val base = boardCity()
        val existing = base.buildings.last()
        val site = existing.copy(id = UUID.randomUUID(), type = BuildingType.PLAN_BOARD, phase = BuildingPhase.SITE)
        val upgrade = existing.copy(level = 2, phase = BuildingPhase.CONSTRUCTING,
            bounds = existing.bounds.copy(max = existing.bounds.max.copy(y = 75)))
        val city = base.copy(buildings = base.buildings + site, construction = boardOrder(upgrade), constructionQueue = listOf(site.id))
        val snapshot = civsPlanBoardSnapshot(civsPlanBoardCapture(city), emptyList())
        snapshot.buildings shouldBe base.buildings
        snapshot.plannedBuildings shouldBe listOf(site, upgrade)
        snapshot.buildings.count { it.id == existing.id } shouldBe 1
        snapshot.plannedBuildings.count { it.id == existing.id } shouldBe 1
        snapshot.routes shouldBe emptyMap()
        val constructing = existing.copy(phase = BuildingPhase.CONSTRUCTING)
        val fresh = base.copy(buildings = listOf(base.buildings.first(), constructing), construction = boardOrder(constructing))
        civsPlanBoardSnapshot(fresh, emptyList()).let {
            it.buildings shouldBe listOf(base.buildings.first())
            it.plannedBuildings shouldBe listOf(constructing)
        }
    }

    test("only validated retained routes are detached and queued upgrades use exact accepted captures") {
        val base = boardCity()
        val existing = base.buildings.last()
        val route = (0..8).map { CivPosition(0, 65, it) }.toMutableList()
        val retained = boardPlan(base, existing, route)
        val upgrade = existing.copy(level = 2, phase = BuildingPhase.CONSTRUCTING,
            bounds = existing.bounds.copy(max = existing.bounds.max.copy(y = 75)))
        val queued = boardPlan(base, upgrade, listOf(CivPosition(1, 65, 8), existing.entrance))
            .copy(id = queuedUpgradeId(existing.id, 2))
        val city = base.copy(constructionQueue = listOf(existing.id))
        val snapshot = civsPlanBoardSnapshot(city, listOf(retained, queued))
        snapshot.plannedBuildings shouldBe listOf(upgrade)
        snapshot.buildings shouldBe base.buildings
        snapshot.routes shouldBe mapOf(existing.id to route.toList())
        route.clear()
        snapshot.routes.getValue(existing.id).size shouldBe 9
        listOf(queued.copy(cityId = UUID.randomUUID()), queued.copy(worldId = UUID.randomUUID()),
            queued.copy(id = UUID.randomUUID()), queued.copy(building = upgrade.copy(level = 3)),
            queued.copy(building = upgrade.copy(entrance = upgrade.entrance.copy(x = 1)))).forEach { invalid ->
            shouldThrow<CivsRejection> { civsPlanBoardSnapshot(city, listOf(retained, invalid)) }
        }
        shouldThrow<CivsRejection> { civsPlanBoardSnapshot(city, listOf(retained)) }.reason shouldBe "plan-board-queued-plan-unavailable"
        val broken = boardPlan(base, existing, listOf(CivPosition(0, 65, 0), existing.entrance))
        listOf(broken, retained.copy(cityId = UUID.randomUUID()), retained.copy(worldId = UUID.randomUUID())).forEach { invalid ->
            civsPlanBoardSnapshot(base, listOf(invalid)).routes shouldBe emptyMap()
        }
        civsPlanBoardSnapshot(base.copy(buildings = base.buildings.map { it.copy(phase = BuildingPhase.SITE) }),
            listOf(retained)).routes shouldBe emptyMap()
    }

    test("geometry key ignores stock resident and work churn but captures queue and layout changes") {
        val city = boardCity()
        val key = civsPlanBoardLayoutKey(city)
        val order = boardOrder(city.buildings.last().copy(phase = BuildingPhase.CONSTRUCTING))
        val constructing = city.copy(construction = order)
        civsPlanBoardLayoutKey(city.copy(revision = 100, stock = Stock(9, 10, 11),
            workers = listOf(CivWorker(UUID.randomUUID(), "Resident", null, completedCycles = 70)))) shouldBe key
        civsPlanBoardLayoutKey(constructing.copy(construction = order.copy(workDone = 1))) shouldBe civsPlanBoardLayoutKey(constructing)
        listOf(city.copy(name = "Renamed"), city.copy(radius = 97), city.copy(center = city.center.copy(x = 1)),
            city.copy(worldId = UUID.randomUUID()), city.copy(constructionQueue = listOf(city.buildings.last().id)),
            city.copy(buildings = city.buildings.map { it.copy(rotation = 90) }), constructing).forEach {
            (civsPlanBoardLayoutKey(it) == key) shouldBe false
        }
        val oldRoute = mutableListOf(CivPosition(0, 65, 0), CivPosition(0, 66, 1))
        val newRoute = oldRoute.map { it.copy(y = 65) }.toMutableList()
        val override = CivRoadRouteOverride(oldRoute, newRoute)
        val ids = city.buildings.map { it.id.toString() }
        val overrides = linkedMapOf(ids[0] to override, ids[1] to override)
        val repaired = city.copy(roadRouteOverrides = overrides)
        (civsPlanBoardLayoutKey(repaired) == key) shouldBe false
        civsPlanBoardLayoutKey(repaired.copy(roadRouteOverrides = overrides.entries.reversed().associate { it.toPair() })) shouldBe
            civsPlanBoardLayoutKey(repaired)
        val detachedRepair = civsPlanBoardCapture(repaired)
        val repairKey = civsPlanBoardLayoutKey(detachedRepair)
        newRoute.clear(); oldRoute.clear(); overrides.clear()
        civsPlanBoardLayoutKey(detachedRepair) shouldBe repairKey
        val transit = mutableListOf(CivPosition(3, 65, 3))
        val buildings = city.buildings.toMutableList().also { rows ->
            rows += rows.last().copy(id = UUID.randomUUID(), type = BuildingType.ROAD, transit = transit)
        }
        val captured = civsPlanBoardCapture(city.copy(buildings = buildings))
        val capturedKey = civsPlanBoardLayoutKey(captured)
        transit.clear(); buildings.clear()
        captured.buildings.last().transit shouldBe listOf(CivPosition(3, 65, 3))
        civsPlanBoardLayoutKey(captured) shouldBe capturedKey
    }

    test("provider shares pending reads and cached snapshots across worker revisions then refreshes geometry") {
        boardProviderScenario { h ->
            val id = h.city!!.id
            val first = h.snapshots.snapshot(id)
            (h.snapshots.snapshot(id) === first) shouldBe true
            h.reads.size shouldBe 1
            h.city = h.city!!.copy(revision = 77, stock = Stock(1, 2, 3))
            h.reads.single().complete(emptyList())
            first.isDone shouldBe false
            h.finish()
            val snapshot = first.join()
            h.snapshots.snapshot(id).join() shouldBe snapshot
            h.reads.size shouldBe 1
            h.city = h.city!!.copy(name = "New layout label")
            val next = h.snapshots.snapshot(id)
            h.reads.size shouldBe 2
            next.isDone shouldBe false
            h.reads.last().complete(emptyList())
            h.finish()
            next.join().name shouldBe "New layout label"
            (next.join().layoutKey == snapshot.layoutKey) shouldBe false
        }
    }

    test("stale geometry and deleted city completions cannot replace a newer snapshot") {
        boardProviderScenario { h ->
            val original = h.city!!
            val old = h.snapshots.snapshot(original.id)
            h.city = original.copy(name = "Replacement")
            val replacement = h.snapshots.snapshot(original.id)
            boardSnapshotFailure(old) shouldBe "plan-board-stale"
            h.reads[0].complete(emptyList())
            h.reads[1].complete(emptyList())
            h.finish()
            replacement.join().name shouldBe "Replacement"
            h.snapshots.snapshot(original.id).join() shouldBe replacement.join()
            h.city = h.city!!.copy(radius = 95)
            val deleted = h.snapshots.snapshot(original.id)
            h.city = null
            h.snapshots.retain(emptySet())
            boardSnapshotFailure(deleted) shouldBe "plan-board-stale"
            h.city = original // Same UUID and original geometry after reload cannot revive an old request.
            val reloaded = h.snapshots.snapshot(original.id)
            h.reads[2].complete(emptyList())
            h.reads[3].complete(emptyList())
            h.finish()
            reloaded.join().name shouldBe original.name
            h.snapshots.snapshot(original.id).join() shouldBe reloaded.join()
        }
    }

    test("close drains admitted work and lifecycle cancellation or executor rejection permits safe failure") {
        boardProviderScenario { h ->
            val pending = h.snapshots.snapshot(h.city!!.id)
            h.snapshots.close()
            boardSnapshotFailure(pending) shouldBe "not-ready"
            val drain = h.snapshots.drained()
            drain.isDone shouldBe false
            h.reads.single().complete(emptyList())
            h.finish()
            drain.join()
            boardSnapshotFailure(h.snapshots.snapshot(h.city!!.id)) shouldBe "not-ready"
        }
        boardProviderScenario { h ->
            val cancelled = h.snapshots.snapshot(h.city!!.id)
            h.tasks.restart()
            h.reads.last().complete(emptyList())
            h.finish()
            cancelled.isCompletedExceptionally shouldBe true
            val retry = h.snapshots.snapshot(h.city!!.id)
            h.reads.last().complete(emptyList())
            h.finish()
            retry.join().cityId shouldBe h.city!!.id
            h.city = h.city!!.copy(radius = 95)
            h.rejectWork = true
            val rejected = h.snapshots.snapshot(h.city!!.id)
            h.reads.last().complete(emptyList())
            rejected.isCompletedExceptionally shouldBe true
            h.rejectWork = false
            val recovered = h.snapshots.snapshot(h.city!!.id)
            h.reads.last().complete(emptyList())
            h.finish()
            recovered.join().radius shouldBe 95
        }
    }

    test("outstanding snapshot work has a global bound and retries after admitted reads drain") {
        boardProviderScenario { h ->
            val original = h.city!!
            h.snapshots.snapshot(original.id)
            h.city = original.copy(radius = 95)
            h.snapshots.snapshot(original.id)
            h.city = original.copy(radius = 94)
            boardSnapshotFailure(h.snapshots.snapshot(original.id)) shouldBe "plan-board-busy"
            h.reads.size shouldBe 2
            h.reads.forEach { it.complete(emptyList()) }
            h.finish()
            val admitted = h.snapshots.snapshot(original.id)
            h.reads.last().complete(emptyList())
            h.finish()
            admitted.join().radius shouldBe 94
        }
    }
})

private class BoardProviderHarness {
    var city: Settlement? = boardCity()
    val scheduler = TestTaskScheduler()
    val tasks = LifecycleTaskScope(scheduler)
    val work = ArrayDeque<Runnable>()
    var rejectWork = false
    val reads = mutableListOf<CompletableFuture<List<CivPlacementPlan>>>()
    val snapshots = CivsPlanBoardSnapshots(tasks, Executor {
        if (rejectWork) throw RejectedExecutionException("fixture")
        work.addLast(it)
    }, { id -> city?.takeIf { it.id == id } }, {
        CompletableFuture<List<CivPlacementPlan>>().also(reads::add)
    })
    fun finish() {
        var rounds = 0
        while (work.isNotEmpty() || scheduler.pendingCount() > 0) {
            check(rounds++ < 20) { "Board snapshot fixture did not drain" }
            while (work.isNotEmpty()) work.removeFirst().run()
            scheduler.executeAll()
        }
    }
}

private fun boardProviderScenario(test: (BoardProviderHarness) -> Unit) {
    val harness = BoardProviderHarness()
    try { test(harness) } finally { harness.snapshots.close(); harness.tasks.close() }
}

private fun boardSnapshotFailure(future: CompletableFuture<*>): String? =
    CivsDiagnostics.rejection(shouldThrow<CompletionException> { future.join() })?.reason

private fun boardCity(): Settlement {
    val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(0, 64, 0), 0, "fixture",
        CivBox(CivPosition(-4, 64, -8), CivPosition(4, 70, -1)), CivPosition(0, 65, 0), BuildingPhase.ACTIVE)
    val forester = hall.copy(id = UUID.randomUUID(), type = BuildingType.FORESTER, anchor = CivPosition(0, 64, 8),
        bounds = CivBox(CivPosition(-4, 64, 9), CivPosition(4, 70, 16)), entrance = CivPosition(0, 65, 8), rotation = 180)
    return Settlement(UUID.randomUUID(), UUID.randomUUID(), "Plan", UUID.randomUUID(), CivPosition(0, 64, 0),
        96, "fixture", Stock(), listOf(hall, forester), emptyList())
}

private fun boardOrder(building: CivBuilding) = CivConstruction(UUID.randomUUID(), building.id, building.level,
    2, 0, ConstructionPhase.CONSTRUCTING, Stock(), plannedBuilding = building)

private fun boardPlan(city: Settlement, building: CivBuilding, route: List<CivPosition>) =
    CivPlacementPlan(building.id, city.id, city.worldId, building, emptyList(), terrain = CivTerraformPlan(
        CivBox(CivPosition(-10, 63, -10), CivPosition(10, 68, 20)), emptyList(), route, 64, 0, 0))
