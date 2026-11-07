package ru.arc.civs

import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.logging.Level

/** A geometric refusal remains useful until its inputs change; unknown external edits get a bounded retry. */
internal class CivsAutonomousSearchFailures(private val lifetimeMillis: Long = 300_000) {
    private data class Key(val city: UUID, val type: BuildingType, val level: Int, val building: UUID?, val candidate: Int?)
    private data class Refusal(val owner: UUID, val world: UUID, val center: CivPosition, val radius: Int,
        val layout: String, val excluded: List<CivBox>, val approaches: List<CivPosition>, val until: Long) {
        fun matches(city: Settlement, key: String): Boolean = owner == city.owner && world == city.worldId && layout == key
        fun affected(point: CivPosition): Boolean {
            if (kotlin.math.abs(point.x.toLong() - center.x) > radius.toLong() + 1 ||
                kotlin.math.abs(point.z.toLong() - center.z) > radius.toLong() + 1) return false
            // Both route search and infrastructure reject these building columns regardless of native height.
            // Keep their border and every entrance/control context live; never infer a safe Y cutoff.
            if (approaches.any { kotlin.math.abs(it.x.toLong() - point.x) <= 1 &&
                    kotlin.math.abs(it.z.toLong() - point.z) <= 1 }) return true
            return excluded.none { point.x.toLong() > it.min.x.toLong() + 1 && point.x.toLong() < it.max.x.toLong() - 1 &&
                point.z.toLong() > it.min.z.toLong() + 1 && point.z.toLong() < it.max.z.toLong() - 1 }
        }
    }
    private val refused = linkedMapOf<Key, Refusal>()
    private val suppressed = mutableMapOf<UUID, Long>()

    fun remember(city: Settlement, goal: CivAutonomyGoal, reason: String, now: Long, candidate: Int? = null): Boolean {
        if (reason !in setOf("autonomy-no-site", "autonomy-no-path", "autonomy-search-budget",
                "autonomy-layout-obstructed", "autonomy-layout-complete")) return false
        val layout = civsPlanBoardLayoutKey(city)
        prune(city, layout, now)
        val key = Key(city.id, goal.type, goal.targetLevel, goal.buildingId, candidate)
        refused.remove(key)
        refused[key] = Refusal(city.owner, city.worldId, city.center, city.radius, layout,
            city.buildings.filter { !it.type.isInfrastructure && it.id != goal.buildingId }
                .map { CivsAutonomousGeometry.futureBounds(city, it) },
            city.buildings.flatMap { CivsAutonomousGeometry.approach(it) + listOfNotNull(it.control) },
            now + lifetimeMillis)
        // A city has a finite goal/profile and at most 64 recovery choices; expired/old evidence is disposable.
        val keys = refused.keys.filter { it.city == city.id }
        keys.take((keys.size - 128).coerceAtLeast(0)).forEach(refused::remove)
        return true
    }

    fun blocked(city: Settlement, goal: CivAutonomyGoal, now: Long, candidate: Int? = null): Boolean {
        if (refused.keys.none { it.city == city.id }) return false
        prune(city, civsPlanBoardLayoutKey(city), now)
        if (Key(city.id, goal.type, goal.targetLevel, goal.buildingId, candidate) !in refused) return false
        suppressed[city.id] = (suppressed[city.id] ?: 0L) + 1
        return true
    }

    private fun prune(city: Settlement, layout: String, now: Long) {
        refused.entries.removeIf { (key, old) -> now >= old.until || key.city == city.id && !old.matches(city, layout) }
    }

    fun changed(world: UUID, point: CivPosition): Set<UUID> {
        val affected = mutableSetOf<UUID>()
        refused.entries.removeIf { (key, old) ->
            (old.world == world && old.affected(point)).also { if (it) affected += key.city }
        }
        return affected
    }

    fun debug(city: UUID): Map<String, Any?> {
        val entries = refused.filterKeys { it.city == city }.values
        return mapOf("cached" to entries.isNotEmpty(), "cached_goals" to entries.size,
            "retry_at" to entries.minOfOrNull { it.until }, "suppressed_searches" to (suppressed[city] ?: 0L))
    }
    fun retain(cities: Set<UUID>) { refused.keys.removeIf { it.city !in cities }; suppressed.keys.retainAll(cities) }
    fun remove(city: UUID) { refused.keys.removeIf { it.city == city } }
    fun clear() { refused.clear(); suppressed.clear() }
}

/** Main-thread autonomous planning and retry lifecycle; the city authority commits every accepted design. */
internal class CivsAutonomousDevelopment(
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val world: CivsWorld,
    private val blueprints: CivsBlueprints,
    private val autonomousPlacement: CivsAutonomousPlacement,
    private val executor: Executor,
    private val settlements: () -> Map<UUID, Settlement>,
    private val busy: MutableSet<UUID>,
    private val unhealthy: Set<UUID>,
    private val isPlacing: (UUID) -> Boolean,
    private val ready: () -> Boolean,
    private val isClosed: () -> Boolean,
    private val loadAutonomousPlans: (Settlement) -> CompletableFuture<List<CivPlacementPlan>>,
    private val settlementActive: (Settlement) -> Boolean,
    private val afterCityAvailable: (UUID, () -> CompletableFuture<Unit>) -> CompletableFuture<Unit>,
    private val mutate: (UUID, (Settlement) -> Settlement) -> CompletableFuture<Settlement>,
    private val mutateWhenAvailable: (UUID, (Settlement) -> Settlement) -> CompletableFuture<Settlement>,
    private val commitAutonomousDesign: (Settlement, CivAutonomousDesign) -> CompletableFuture<Unit>,
    private val pulseConstruction: () -> Unit,
    private val clock: () -> Long,
    private val developmentFinished: (UUID) -> Unit = {},
    private val storageCapacityPressure: (Settlement) -> Boolean = { false },
    private val developmentAdmission: (Settlement) -> Boolean = { true },
    private val acceleratedCity: () -> UUID? = { null },
) {
    private val cities get() = settlements()
    private val closed get() = isClosed()
    // Terrain can change without a city revision; retry a settled layout after five minutes.
    private val autonomyLayoutChecked = mutableMapOf<UUID, Pair<List<CivBuilding>, Long>>()
    private val autonomyRequests = mutableMapOf<UUID, UUID>()
    private val autonomyProgress = mutableMapOf<UUID, CivsAutonomyView>()
    private val autonomyRetryAt = mutableMapOf<UUID, Long>()
    private val autonomyPlanRetryAt = mutableMapOf<UUID, Long>()
    private data class DeferredInitialPlan(val owner: UUID, val layoutKey: String)
    private val deferredInitialPlans = mutableMapOf<UUID, DeferredInitialPlan>()
    private val searchFailures = CivsAutonomousSearchFailures()
    // One fresh infrastructure attempt may follow failed growth even after terrain invalidates its geometric refusal.
    private data class FailedGrowthYield(val owner: UUID, val world: UUID, val layout: String,
        val goal: CivAutonomyGoal, val until: Long) {
        fun matches(city: Settlement, now: Long): Boolean = now < until && city.owner == owner && city.worldId == world &&
            civsPlanBoardLayoutKey(city) == layout
        fun matches(ordinary: CivAutonomyGoal?): Boolean = ordinary?.let {
            it.type == goal.type && it.targetLevel == goal.targetLevel && it.buildingId == goal.buildingId
        } == true
    }
    private val failedGrowthYields = mutableMapOf<UUID, FailedGrowthYield>()
    private data class UnpaidRecoveryCursor(val owner: UUID, val worldId: UUID, val layoutKey: String,
        val type: BuildingType, val buildingId: UUID,
        val skipPrepared: Int = 0) {
        // The layout key includes the original full plan and every actual building, but excludes residents/stock.
        fun matches(city: Settlement): Boolean = city.owner == owner && city.worldId == worldId &&
            civsPlanBoardLayoutKey(city) == layoutKey
    }
    private val unpaidRecoveryCursors = mutableMapOf<UUID, UnpaidRecoveryCursor>()
    private val autonomyWarnAt = mutableMapOf<UUID, Long>()
    private val autonomousAccessChecked = mutableMapOf<UUID, List<CivBuilding>>()
    private val autonomousAccessRetryAt = mutableMapOf<UUID, Long>()
    private val infrastructureRebuildRetryAt = mutableMapOf<UUID, Long>()
    private val farmExpansionRetryAt = mutableMapOf<UUID, Long>()
    private var autonomyCursor = 0
    private var nextAutonomyAt = 0L

    fun progress(cityId: UUID): CivsAutonomyView? = autonomyProgress[cityId]
    fun debug(cityId: UUID): Map<String, Any?> = mapOf(
        "request" to autonomyRequests[cityId], "retry_at" to autonomyRetryAt[cityId],
        "geometric_refusal" to searchFailures.debug(cityId),
        "placement" to autonomousPlacement.debug(cityId))

    fun paused(cityId: UUID) {
        searchFailures.remove(cityId)
        failedGrowthYields.remove(cityId)
        developmentFinished(cityId)
        autonomyRequests.remove(cityId)
        autonomyRetryAt.remove(cityId)
        autonomyProgress.remove(cityId)
        unpaidRecoveryCursors.remove(cityId)
        deferredInitialPlans.remove(cityId)
    }

    fun reset() {
        searchFailures.clear()
        failedGrowthYields.clear()
        (autonomyRequests.keys + autonomyProgress.keys + autonomyRetryAt.keys).toSet().forEach(developmentFinished)
        autonomyRequests.clear()
        autonomyLayoutChecked.clear()
        autonomyProgress.clear()
        autonomyRetryAt.clear()
        autonomyPlanRetryAt.clear()
        deferredInitialPlans.clear()
        unpaidRecoveryCursors.clear()
        autonomousAccessChecked.clear()
        autonomousAccessRetryAt.clear()
        infrastructureRebuildRetryAt.clear()
        farmExpansionRetryAt.clear()
    }

    fun cancel() {
        searchFailures.clear()
        failedGrowthYields.clear()
        (autonomyRequests.keys + autonomyProgress.keys + autonomyRetryAt.keys).toSet().forEach(developmentFinished)
        autonomyRequests.clear()
        autonomyLayoutChecked.clear()
        unpaidRecoveryCursors.clear()
        deferredInitialPlans.clear()
    }

    fun accessReady(city: Settlement): Boolean = city.autonomy == null ||
        civsSameBuildingLayout(autonomousAccessChecked[city.id], city.buildings)

    /** A bounded inverse of older terrain journals runs before growth, even when resource stock is empty. */
    fun pulseAccessRepair() {
        val city = cities.values.firstOrNull { it.autonomy?.paused == false && it.pendingDeletion == null &&
            !accessReady(it) && it.id !in busy && it.id !in unhealthy && !isPlacing(it.id) &&
            it.id !in autonomyRequests && now() >= (autonomousAccessRetryAt[it.id] ?: 0L) &&
            (settlementActive(it) || it.construction != null) } ?: return
        busy += city.id
        val prepared = runCatching { loadAutonomousPlans(city).thenApplyAsync({
            civsAutonomousAccessRepairs(city, it)
        }, executor) }.getOrElse { CompletableFuture.failedFuture(it) }
        prepared.whenCompleteSync(tasks) { repairs, failure ->
            fun completed(counts: Map<String, Int>?, error: Throwable?) {
                busy -= city.id
                if (closed) return
                if (error == null && (counts?.get("denied") ?: 0) == 0 && civsSameBuildingLayout(cities[city.id]?.buildings, city.buildings)) {
                    autonomousAccessChecked[city.id] = city.buildings.toList()
                    autonomousAccessRetryAt.remove(city.id)
                    if ((counts?.get("changed") ?: 0) > 0 || (counts?.get("conflicts") ?: 0) > 0) CivsDiagnostics.record(Level.WARNING, city.id, CivsDiagnostics.line(
                        "autonomous_access_repaired", "city" to city.id, "candidates" to repairs.orEmpty().size,
                        "result" to counts, "source" to "retained-terrain-inverse", "work_credit" to false), plugin.logger)
                    pulseConstruction()
                } else {
                    autonomousAccessRetryAt[city.id] = now() + 30_000L
                    CivsDiagnostics.record(Level.WARNING, city.id, CivsDiagnostics.line("autonomous_access_repair_blocked", "city" to city.id,
                        "reason" to (CivsDiagnostics.rejection(error)?.reason ?: when {
                            (counts?.get("denied") ?: 0) > 0 -> "autonomy-access-denied"
                            error == null -> "stale-building"
                            else -> "storage-unavailable"
                        }), "result" to counts), plugin.logger)
                    if (error != null && CivsDiagnostics.rejection(error) == null) log("repair autonomous access ${city.id}", error, city.id)
                }
            }
            if (failure != null) { completed(null, failure); return@whenCompleteSync }
            if (!ready() || !civsSameBuildingLayout(cities[city.id]?.buildings, city.buildings) || cities[city.id]?.autonomy?.paused != false) {
                completed(null, CivsRejection("stale-building")); return@whenCompleteSync
            }
            if (repairs.isNullOrEmpty()) { completed(emptyMap(), null); return@whenCompleteSync }
            runCatching { world.repairAutonomousAccess(city, repairs) }
                .onSuccess { it.whenCompleteSync(tasks) { counts, error -> completed(counts, error) } }
                .onFailure { completed(null, it) }
        }
    }

    /** One planning request per city, fair admission across cities; native capture uses the shared world budget. */
    fun pulse() {
        val at = now()
        val regular = at >= nextAutonomyAt
        val extra = if (regular) null else {
            val cityId = acceleratedCity() ?: return
            cities[cityId] ?: return
        }
        if (regular) {
            nextAutonomyAt = at + 5_000
            searchFailures.retain(cities.keys)
            failedGrowthYields.entries.removeIf { (cityId, evidence) ->
                val latest = cities[cityId]
                latest == null || latest.pendingDeletion != null || latest.autonomy?.paused != false ||
                    latest.construction != null || latest.constructionQueue.orEmpty().isNotEmpty() ||
                    latest.pendingControls.orEmpty().isNotEmpty() || !evidence.matches(latest, now())
            }
            deferredInitialPlans.entries.removeIf { (cityId, evidence) ->
                val latest = cities[cityId]
                latest == null || latest.pendingDeletion != null || latest.autonomy?.paused != false ||
                    latest.autonomy.plan != null || latest.owner != evidence.owner ||
                    now() >= (autonomyPlanRetryAt[cityId] ?: 0L) || civsPlanBoardLayoutKey(latest) != evidence.layoutKey
            }
            unpaidRecoveryCursors.entries.removeIf { (cityId, cursor) ->
                val latest = cities[cityId]
                val plan = latest?.let(CivsSettlementPlans::current)
                latest == null || plan == null || unpaidPlot(latest, plan, cursor.type)?.building?.id != cursor.buildingId || !cursor.matches(latest)
            }
        }
        val candidates = (extra?.let { listOf(it) } ?: cities.values).filter {
            it.autonomy?.paused == false && CivsAutoStorageMigration.ready(it) && it.pendingDeletion == null && accessReady(it) &&
                it.id !in busy && it.id !in unhealthy && !isPlacing(it.id) && it.id !in autonomyRequests &&
                it.pendingControls.orEmpty().isEmpty() && it.construction == null &&
                it.constructionQueue.orEmpty().isEmpty() && at >= (autonomyRetryAt[it.id] ?: 0) && settlementActive(it)
        }
        if (candidates.isEmpty()) return
        val city = if (extra == null) candidates[Math.floorMod(autonomyCursor++, candidates.size)] else candidates.single()
        if (pulseInfrastructureRebuild(city)) return
        if (pulseFarmExpansion(city)) return
        val capacity = if (storageCapacityPressure(city)) CivsAutonomy.capacityGoal(city, blueprints::maximumLevel) else null
        val ordinary = capacity ?: CivsAutonomy.nextGoal(city, blueprints::maximumLevel)
        val growthYield = failedGrowthYields[city.id]?.takeIf { capacity == null && it.matches(ordinary) }
        if (growthYield == null) failedGrowthYields.remove(city.id)
        val adopted = CivsSettlementPlans.current(city)
        val checked = autonomyLayoutChecked[city.id]
        val layoutCheckDue = checked == null || !civsSameBuildingLayout(checked.first, city.buildings) || now() >= checked.second
        // A refused extension may yield to unfinished work in the unchanged accepted layout.
        var infrastructure = capacity == null && layoutCheckDue && (CivsAutonomy.infrastructureDue(city, ordinary) ||
            adopted != null && growthYield != null && CivsAutonomy.infrastructureDue(city, null))
        if (!infrastructure && ordinary == null && adopted != null) { developmentFinished(city.id); return }
        var goal = if (infrastructure || ordinary == null) CivsAutonomy.goal(city, BuildingType.ROAD, 1, null) else requireNotNull(ordinary)
        val existing = if (infrastructure) null else goal.buildingId?.let(city::building)
        if (existing?.phase == BuildingPhase.SITE) {
            if (!goal.ready) { developmentFinished(city.id); return }
            runCatching { mutate(city.id) { CivsRules.enqueue(it, it.owner, existing.id) } }
                .onSuccess { future -> future.whenCompleteSync(tasks) { _, failure ->
                    if (failure != null) autonomyFailed(city.id, goal, failure)
                    else autonomyProgress[city.id] = CivsAutonomyView("queued", goal.type, goal.targetLevel)
                } }.onFailure { autonomyFailed(city.id, goal, it) }
            return
        }
        // Empty-stock recovery without a visible board retains its old no-growth admission boundary.
        if (!goal.ready && city.buildings.none { it.type == BuildingType.PLAN_BOARD && it.phase == BuildingPhase.ACTIVE }) {
            developmentFinished(city.id)
            return
        }
        // Legacy planless towns keep their ordinary one-goal search. New founding records its hall-sized quarter.
        val compactFounding = city.autonomy?.layoutHalfWidth == CivsSettlementGrowth.initialHalfWidth(city)
        val extendPlan = ordinary != null && existing == null && !infrastructure && (adopted != null || compactFounding) &&
            (adopted == null || CivsSettlementPlans.next(city, goal.type) == null && CivsSettlementPlans.needsExtension(city))
        if (extendPlan && now() < (autonomyPlanRetryAt[city.id] ?: 0L) && city.id !in deferredInitialPlans) return
        if (extendPlan && now() >= (autonomyPlanRetryAt[city.id] ?: 0L)) {
            if (!developmentAdmission(city)) {
                autonomyFailed(city.id, goal, CivsRejection("city-busy"))
                return
            }
            val request = UUID.randomUUID()
            val key = civsPlanBoardLayoutKey(city)
            deferredInitialPlans.remove(city.id)
            autonomyRequests[city.id] = request
            autonomyProgress[city.id] = CivsAutonomyView("planning", goal.type, goal.targetLevel)
            runCatching { autonomousPlacement.plan(civsPlanBoardCapture(city)) }
                .getOrElse { CompletableFuture.failedFuture(it) }.whenCompleteSync(tasks) { plan, failure ->
                if (autonomyRequests[city.id] != request) return@whenCompleteSync
                if (failure != null) {
                    autonomyRequests.remove(city.id)
                    val rejection = CivsDiagnostics.rejection(failure)
                    autonomyPlanRetryAt[city.id] = now() + if (rejection?.reason in
                        setOf("world-busy", "chunks-unloaded", "stale-development")) 30_000 else 300_000
                    val latest = cities[city.id]
                    if (adopted != null && rejection != null && !closed && ready() && latest != null &&
                        latest.owner == city.owner && latest.worldId == city.worldId && latest.pendingDeletion == null &&
                        latest.autonomy?.paused == false && civsPlanBoardLayoutKey(latest) == key &&
                        searchFailures.remember(city, goal, rejection.reason, now()))
                        failedGrowthYields[city.id] = FailedGrowthYield(city.owner, city.worldId, key, goal,
                            requireNotNull(autonomyPlanRetryAt[city.id]))
                    if (compactFounding && adopted == null && city.autonomy.plan == null &&
                        rejection?.reason == "autonomy-layout-obstructed" && rejection.values["stage"] == "infrastructure" &&
                        !closed && ready() && latest != null && latest.owner == city.owner && latest.autonomy?.paused == false &&
                        latest.pendingDeletion == null && latest.autonomy.plan == null && civsPlanBoardLayoutKey(latest) == key) {
                        // Only rejected unadopted infrastructure is deferred. Each ordinary site still proves its native work.
                        deferredInitialPlans[city.id] = DeferredInitialPlan(city.owner, key)
                        developmentFinished(city.id)
                        autonomyProgress.remove(city.id)
                        CivsDiagnostics.event("autonomous_initial_plan_deferred", "city" to city.id,
                            "reason" to rejection.reason, "details" to rejection.values,
                            "retry_at" to autonomyPlanRetryAt[city.id])
                    } else autonomyFailed(city.id, goal, failure)
                } else runCatching { mutateWhenAvailable(city.id) { current ->
                    rejectIf(autonomyRequests[city.id] != request || civsPlanBoardLayoutKey(current) != key ||
                        current.construction != null || current.constructionQueue.orEmpty().isNotEmpty(),
                        "stale-development")
                    CivsRules.validate(current.copy(revision = current.revision + 1,
                        autonomy = requireNotNull(current.autonomy).copy(layoutHalfWidth = requireNotNull(plan).halfWidth,
                            plan = CivsSettlementPlans.detached(plan))))
                } }.getOrElse { CompletableFuture.failedFuture(it) }.whenCompleteSync(tasks) { _, saveFailure ->
                    autonomyRequests.remove(city.id, request)
                    if (saveFailure != null) autonomyFailed(city.id, goal, saveFailure)
                    else {
                        developmentFinished(city.id)
                        autonomyProgress.remove(city.id)
                        autonomyRetryAt.remove(city.id)
                        autonomyPlanRetryAt.remove(city.id)
                        failedGrowthYields.remove(city.id)
                        CivsDiagnostics.event("autonomous_plan_adopted", "city" to city.id,
                            "plots" to requireNotNull(plan).plots.size, "roads" to plan.routes.size, "revision" to plan.revision)
                        plan.deferredPerimeter?.let { deferred ->
                            CivsDiagnostics.event("autonomous_perimeter_deferred", "city" to city.id, "stage" to deferred.stage,
                                "plot" to deferred.plotId, "plot_anchor" to deferred.plotAnchor, "crossing" to deferred.crossing)
                        }
                    }
                }
            }
            return
        }
        if (!infrastructure && ordinary == null) { developmentFinished(city.id); return }
        if (!goal.ready) { developmentFinished(city.id); return }
        val recovery = unpaidRecoveryCursors[city.id]?.takeIf { !infrastructure && it.type == goal.type }
        if (searchFailures.blocked(city, goal, now(), recovery?.skipPrepared)) return
        if (recovery != null && now() < (autonomyPlanRetryAt[city.id] ?: 0L)) return
        if (!developmentAdmission(city)) {
            autonomyFailed(city.id, goal, CivsRejection("city-busy"))
            return
        }
        if (infrastructure && growthYield != null) failedGrowthYields.remove(city.id, growthYield)
        val request = UUID.randomUUID()
        autonomyRequests[city.id] = request
        autonomyProgress[city.id] = CivsAutonomyView("searching", goal.type, goal.targetLevel)
        CivsDiagnostics.event("autonomous_goal_selected", "city" to city.id, "request" to request,
            "type" to goal.type, "level" to goal.targetLevel, "stock" to city.stock, "cost" to goal.cost)
        val reservedPlots = if (!infrastructure && existing == null && adopted != null &&
            unpaidPlot(city, adopted, goal.type) != null) adopted.plots.filter {
                it.building.type == goal.type && it.building.level == 1 && city.building(it.building.id) == null &&
                    (goal.type != BuildingType.QUARRY || CivsSettlementGrowth.outside(city, it.envelope))
            } else emptyList()
        val reservedLayoutKey = if (reservedPlots.isEmpty()) null else civsPlanBoardLayoutKey(city)
        var nextReserved = 1
        // A declined forecast already proved its reserved slot unusable. Continue the next candidate,
        // rather than repeating that slot or putting the new candidate behind the old refusal's TTL.
        if (recovery != null && adopted != null && now() >= (autonomyPlanRetryAt[city.id] ?: 0L)) {
            recoverUnpaidPlot(city, adopted, request, goal)
            return
        }
        val prepared = runCatching {
            if (infrastructure) autonomousPlacement.infrastructure(city)
            else if (existing == null && adopted == null)
                autonomousPlacement.search(city, goal.type, goal.targetLevel, UUID.randomUUID())
            else if (existing == null) autonomousPlacement.reserved(city,
                CivsSettlementPlans.next(city, goal.type) ?: throw CivsRejection("autonomy-plan-missing-site"))
            else if (CivsPlanBoardBlueprint.needsRefit(existing))
                blueprints.preparePlanBoardRefit(existing, city.worldId).thenApply { CivAutonomousDesign(it, null) }
            else blueprints.prepareUpgrade(existing, goal.targetLevel, city.worldId).thenApply { CivAutonomousDesign(it, null) }
        }.getOrElse { CompletableFuture.failedFuture(it) }
        fun completed(blueprint: CivAutonomousDesign?, failure: Throwable?) {
            if (autonomyRequests[city.id] != request) return
            val current = if (reservedLayoutKey == null) city else cities[city.id]?.takeIf { latest ->
                !closed && ready() && latest.owner == city.owner && latest.worldId == city.worldId &&
                    city.id !in unhealthy && !isPlacing(city.id) && settlementActive(latest) &&
                    civsPlanBoardLayoutKey(latest) == reservedLayoutKey &&
                    unpaidPlot(latest, requireNotNull(adopted), goal.type) != null
            }
            if (current == null) {
                autonomyRequests.remove(city.id, request)
                autonomyFailed(city.id, goal, CivsRejection("stale-development"))
                return
            }
            val rejection = failure?.let(CivsDiagnostics::rejection)
            // An unusable unpaid slot must not hide another already adopted slot of the same type.
            if (rejection?.reason == "autonomy-no-site" && nextReserved < reservedPlots.size) {
                val alternative = reservedPlots[nextReserved++]
                runCatching { autonomousPlacement.reserved(current, alternative) }
                    .getOrElse { CompletableFuture.failedFuture(it) }
                    .whenCompleteSync(tasks) { design, error -> completed(design, error) }
                return
            }
            if (rejection != null) searchFailures.remember(current, goal, rejection.reason, now())
            if (!infrastructure && existing == null && goal.targetLevel == 1 && adopted != null &&
                rejection?.reason == "autonomy-no-site" && unpaidPlot(city, adopted, goal.type) != null &&
                now() >= (autonomyPlanRetryAt[city.id] ?: 0L)) {
                recoverUnpaidPlot(current, adopted, request, goal)
                return
            }
            // A blocked first-level industry must not indefinitely hide unfinished paid infrastructure.
            // Keep the same request, terrain guards and settled-layout backoff; no accepted plot moves.
            if (failure != null && !infrastructure && existing == null && adopted == null && ordinary?.targetLevel == 1 &&
                CivsDiagnostics.rejection(failure)?.reason == "autonomy-no-site" && layoutCheckDue &&
                CivsAutonomy.infrastructureDue(city, null) &&
                !searchFailures.blocked(city, CivsAutonomy.goal(city, BuildingType.ROAD, 1, null), now())) {
                infrastructure = true
                goal = CivsAutonomy.goal(city, BuildingType.ROAD, 1, null)
                autonomyProgress[city.id] = CivsAutonomyView("searching", goal.type, goal.targetLevel)
                runCatching { autonomousPlacement.infrastructure(city) }.getOrElse { CompletableFuture.failedFuture(it) }
                    .whenCompleteSync(tasks) { design, error -> completed(design, error) }
                return
            }
            if (failure != null) {
                autonomyRequests.remove(city.id)
                if (infrastructure) autonomyLayoutChecked[city.id] = city.buildings.toList() to (now() + 300_000)
                if (CivsDiagnostics.rejection(failure)?.reason == "autonomy-layout-complete") {
                    autonomyProgress.remove(city.id)
                    developmentFinished(city.id)
                    CivsDiagnostics.event("autonomous_layout_complete", "city" to city.id,
                        "pieces" to city.buildings.count { it.type.isInfrastructure })
                    if (ordinary == null) pulseAutonomousDecoration(city)
                } else autonomyFailed(city.id, goal, failure)
                return
            }
            if (infrastructure) goal = CivsAutonomy.goal(city, requireNotNull(blueprint).blueprint.building.type, 1, null)
            runCatching { afterCityAvailable(city.id) {
                val current = cities[city.id] ?: throw CivsRejection("no-city")
                val latest = if (infrastructure) CivsAutonomy.goal(current, goal.type, 1, null)
                    else (if (storageCapacityPressure(current)) CivsAutonomy.capacityGoal(current, blueprints::maximumLevel) else null)
                        ?: CivsAutonomy.nextGoal(current, blueprints::maximumLevel)
                rejectIf(infrastructure && (current.owner != city.owner ||
                    civsPlanBoardLayoutKey(current) != civsPlanBoardLayoutKey(city)), "stale-development")
                rejectIf(infrastructure && latest?.ready != true, "not-enough-resources")
                rejectIf(autonomyRequests[city.id] != request || current.autonomy?.paused != false ||
                    current.construction != null || current.constructionQueue.orEmpty().isNotEmpty() ||
                    current.pendingControls.orEmpty().isNotEmpty() || latest?.let {
                        it.type == goal.type && it.targetLevel == goal.targetLevel && it.buildingId == goal.buildingId && it.ready
                    } != true || !settlementActive(current), "stale-development")
                commitAutonomousDesign(current, requireNotNull(blueprint))
            } }.onSuccess { committed -> committed.whenCompleteSync(tasks) { _, commitFailure ->
                if (autonomyRequests[city.id] == request) {
                    autonomyRequests.remove(city.id)
                    if (commitFailure != null) autonomyFailed(city.id, goal, commitFailure)
                    else {
                        autonomyProgress[city.id] = CivsAutonomyView("queued", goal.type, goal.targetLevel)
                        autonomyRetryAt.remove(city.id)
                        autonomyWarnAt.remove(city.id)
                    }
                }
            } }.onFailure {
                autonomyRequests.remove(city.id)
                autonomyFailed(city.id, goal, it)
            }
        }
        prepared.whenCompleteSync(tasks) { blueprint, failure -> completed(blueprint, failure) }
    }

    private fun unpaidPlot(city: Settlement, plan: CivSettlementPlan, type: BuildingType): CivSettlementPlot? {
        if (city.autonomy?.paused != false || !CivsAutoStorageMigration.ready(city) || city.pendingDeletion != null ||
            city.construction != null || city.constructionQueue.orEmpty().isNotEmpty() ||
            city.pendingControls.orEmpty().isNotEmpty() || CivsSettlementPlans.current(city) != plan ||
            !CivsSettlementGrowth.covers(city, plan) || type !in CivsAutonomy.profile || type == BuildingType.TOWN_HALL ||
            city.buildings.singleOrNull { it.type == BuildingType.TOWN_HALL } == null)
            return null
        val next = CivsAutonomy.nextGoal(city, blueprints::maximumLevel) ?: return null
        if (next.type != type || next.targetLevel != 1 || next.buildingId != null) return null
        return CivsSettlementPlans.next(city, type)?.takeIf { it.building.level == 1 }
    }

    /** Only unpaid intent changes; native choices and the full forecast finish before one ordinary city save. */
    private fun recoverUnpaidPlot(city: Settlement, oldPlan: CivSettlementPlan, request: UUID,
        goal: CivAutonomyGoal) {
        val oldPlot = unpaidPlot(city, oldPlan, goal.type) ?: return
        val key = civsPlanBoardLayoutKey(city)
        val cursor = unpaidRecoveryCursors[city.id]?.takeIf { it.matches(city) && it.type == goal.type && it.buildingId == oldPlot.building.id }
            ?: UnpaidRecoveryCursor(city.owner, city.worldId, key, goal.type, oldPlot.building.id).also { unpaidRecoveryCursors[city.id] = it }
        autonomyPlanRetryAt[city.id] = now() + 300_000L
        autonomyProgress[city.id] = CivsAutonomyView("planning", goal.type, goal.targetLevel)
        fun current(): Settlement {
            val latest = cities[city.id] ?: throw CivsRejection("stale-development")
            rejectIf(closed || !ready() || autonomyRequests[city.id] != request || city.id in unhealthy || isPlacing(city.id) ||
                !cursor.matches(latest) ||
                unpaidPlot(latest, oldPlan, goal.type)?.building?.id != oldPlot.building.id, "stale-development")
            return latest
        }
        fun failed(failure: Throwable, memoOwned: Boolean = unpaidRecoveryCursors[city.id] === cursor,
            candidate: Int? = cursor.skipPrepared, acceleratedRetry: Boolean = false) {
            if (autonomyRequests[city.id] != request) return
            if (memoOwned) CivsDiagnostics.rejection(failure)?.let {
                searchFailures.remember(city, goal, it.reason, now(), candidate)
            }
            val latest = cities[city.id]
            if (latest == null || !cursor.matches(latest) || unpaidPlot(latest, oldPlan, goal.type)?.building?.id != oldPlot.building.id)
                unpaidRecoveryCursors.remove(city.id, cursor)
            if (autonomyRequests.remove(city.id, request)) autonomyFailed(city.id, goal, failure, acceleratedRetry)
        }
        val snapshot = runCatching { civsPlanBoardCapture(current()) }.getOrElse { failed(it); return }
        runCatching { autonomousPlacement.search(snapshot, goal.type, 1, oldPlot.building.id,
            captureVolume = true, initialPlan = oldPlan, skipPrepared = cursor.skipPrepared) }
            .getOrElse { CompletableFuture.failedFuture(it) }.whenCompleteSync(tasks) { design, searchFailure ->
            if (autonomyRequests[city.id] != request) return@whenCompleteSync
            if (searchFailure != null) {
                val rejection = CivsDiagnostics.rejection(searchFailure)
                val exhausted = rejection?.reason in setOf("autonomy-no-site", "autonomy-search-budget") &&
                    unpaidRecoveryCursors[city.id] === cursor && unpaidRecoveryCursors.remove(city.id, cursor)
                failed(searchFailure, exhausted || unpaidRecoveryCursors[city.id] === cursor,
                    if (exhausted) null else cursor.skipPrepared)
                return@whenCompleteSync
            }
            val latest = runCatching { current() }.getOrElse { failed(it); return@whenCompleteSync }
            val replacement = requireNotNull(design)
            runCatching { autonomousPlacement.plan(civsPlanBoardCapture(latest), replacement) }
                .getOrElse { CompletableFuture.failedFuture(it) }.whenCompleteSync(tasks) planning@ { plan, planFailure ->
                if (autonomyRequests[city.id] != request) return@planning
                if (planFailure != null) {
                    val rejection = CivsDiagnostics.rejection(planFailure)
                    var memoOwned = unpaidRecoveryCursors[city.id] === cursor
                    var acceleratedRetry = false
                    if (rejection?.reason in setOf("autonomy-layout-obstructed", "autonomy-no-site") ||
                        rejection?.reason == "autonomy-search-budget" && rejection.values["stage"] == "dry-perimeter") {
                        runCatching { current() }.getOrElse { failed(it); return@planning }
                        memoOwned = unpaidRecoveryCursors[city.id] === cursor && unpaidRecoveryCursors.replace(city.id, cursor,
                            cursor.copy(skipPrepared = cursor.skipPrepared + 1))
                        acceleratedRetry = memoOwned && acceleratedCity() == city.id && rejection != null &&
                            rejection.reason == "autonomy-layout-obstructed" && CivsRetainedGrowthPerimeter.geometric(rejection) &&
                            rejection.values.keys.none { it in setOf("lands-denied", "chunks-unloaded", "chunk-unloaded", "world-unloaded") }
                        if (memoOwned) autonomyPlanRetryAt[city.id] = now() + if (acceleratedRetry) 5_000L else 30_000L
                    }
                    failed(planFailure, memoOwned, acceleratedRetry = acceleratedRetry)
                    return@planning
                }
                val accepted = requireNotNull(plan)
                runCatching { mutateWhenAvailable(city.id) { fresh ->
                    current()
                    rejectIf(unpaidPlot(fresh, oldPlan, goal.type)?.building?.id != oldPlot.building.id || civsPlanBoardLayoutKey(fresh) != key ||
                        accepted.revision != oldPlan.revision + 1 || accepted.halfWidth != oldPlan.halfWidth ||
                        !CivsSettlementGrowth.covers(fresh, accepted) ||
                        accepted.plots.count { it.building.type != BuildingType.DECORATION } !=
                            oldPlan.plots.count { it.building.type != BuildingType.DECORATION } ||
                        accepted.plots.singleOrNull { it.building.id == oldPlot.building.id }?.building !=
                            replacement.blueprint.building.copy(phase = BuildingPhase.SITE) ||
                        accepted.routes[oldPlot.building.id.toString()] != replacement.terrain?.route, "stale-development")
                    CivsRules.validate(fresh.copy(revision = fresh.revision + 1,
                        autonomy = requireNotNull(fresh.autonomy).copy(plan = CivsSettlementPlans.detached(accepted))))
                } }.getOrElse { failed(it); return@planning }.whenCompleteSync(tasks) { _, saveFailure ->
                    if (saveFailure != null) { failed(saveFailure); return@whenCompleteSync }
                    if (!autonomyRequests.remove(city.id, request)) return@whenCompleteSync
                    developmentFinished(city.id)
                    unpaidRecoveryCursors.remove(city.id)
                    autonomyProgress.remove(city.id)
                    autonomyRetryAt.remove(city.id)
                    CivsDiagnostics.event("autonomous_unpaid_plan_recovered", "city" to city.id,
                        "old_plan_revision" to oldPlan.revision, "new_plan_revision" to accepted.revision,
                        "type" to goal.type, "old_building" to oldPlot.building.id, "new_building" to replacement.blueprint.building.id,
                        "old_anchor" to oldPlot.building.anchor, "new_anchor" to replacement.blueprint.building.anchor)
                }
            }
        }
    }

    /** One optional retained-field intent uses the existing five-second city selection and commit owner. */
    private fun pulseFarmExpansion(city: Settlement): Boolean {
        farmExpansionRetryAt.keys.retainAll(cities.values.flatMap { it.buildings }.mapTo(hashSetOf()) { it.id })
        val building = city.buildings.firstOrNull { it.phase == BuildingPhase.ACTIVE && it.farmFieldVersion == null &&
            CivsFarmFields.originalMetadata(it) && city.inventory?.mode == CivStorageMode.PHYSICAL &&
            city.workers.any { worker -> worker.assignment == it.id && !CivsResidents.isChild(worker) } &&
            now() >= (farmExpansionRetryAt[it.id] ?: 0L) } ?: return false
        if (!developmentAdmission(city)) return false
        val request = UUID.randomUUID()
        val layout = civsPlanBoardLayoutKey(city)
        autonomyRequests[city.id] = request
        runCatching { loadAutonomousPlans(city) }.getOrElse { CompletableFuture.failedFuture(it) }
            .whenCompleteSync(tasks) prepared@ { plans, failure ->
                if (autonomyRequests[city.id] != request) return@prepared
                fun finished(error: Throwable?) {
                    autonomyRequests.remove(city.id, request)
                    developmentFinished(city.id)
                    if (closed) return
                    if (error == null) {
                        farmExpansionRetryAt.remove(building.id)
                        CivsDiagnostics.event("autonomous_farm_expansion_admitted", "city" to city.id,
                            "building" to building.id, "version" to CivsFarmFields.VERSION, "soil" to 120, "water" to 15)
                    } else {
                        // A refused optional field never applies the ordinary-development city backoff.
                        farmExpansionRetryAt[building.id] = now() + 300_000L
                        val refusal = CivsDiagnostics.rejection(error)
                        CivsDiagnostics.event("autonomous_farm_expansion_refused", "city" to city.id,
                            "building" to building.id, "reason" to refusal?.reason, "details" to refusal?.values)
                    }
                }
                if (failure != null) { finished(failure); return@prepared }
                runCatching { mutateWhenAvailable(city.id) { current ->
                    rejectIf(!ready() || closed || autonomyRequests[city.id] != request ||
                        current.owner != city.owner || current.worldId != city.worldId || current.autonomy?.paused != false ||
                        current.pendingDeletion != null || current.pendingControls.orEmpty().isNotEmpty() ||
                        current.construction != null || current.constructionQueue.orEmpty().isNotEmpty() ||
                        current.id in unhealthy || isPlacing(current.id) || !settlementActive(current) ||
                        civsPlanBoardLayoutKey(current) != layout || current.building(building.id) != building, "stale-development")
                    val retained = plans.orEmpty().singleOrNull { it.building.id == building.id }
                    rejectIf(retained == null || retained.cityId != current.id || retained.worldId != current.worldId ||
                        retained.building.type != building.type || retained.building.level != building.level ||
                        retained.building.schematicId != building.schematicId || retained.building.anchor != building.anchor ||
                        retained.building.rotation != building.rotation || retained.building.bounds != building.bounds ||
                        retained.building.entrance != building.entrance, "stale-development")
                    val native = plugin.server.getWorld(current.worldId) ?: throw CivsRejection("world-unavailable")
                    CivsFarmExpansion.validate(current, building, CivAutonomousAccess.from(current, plans.orEmpty()), native,
                        cities.values) { block, material -> world.canModify(current.owner, block, material) }
                    CivsRules.expandFarmField(current, building.id)
                } }.getOrElse { CompletableFuture.failedFuture(it) }
                    .whenCompleteSync(tasks) { _, error -> finished(error) }
            }
        return true
    }

    private val decorationCursor = linkedMapOf<UUID, Int>()

    /** Optional small civic scenery follows the complete essential town and its paid infrastructure. */
    /** Replace one old fortification through the ordinary durable queue; failed pieces never starve growth. */
    private fun pulseInfrastructureRebuild(city: Settlement): Boolean {
        infrastructureRebuildRetryAt.keys.retainAll(cities.values.flatMap { it.buildings }.mapTo(hashSetOf()) { it.id })
        val available = city.buildings.filter { now() >= (infrastructureRebuildRetryAt[it.id] ?: 0L) }.mapTo(hashSetOf()) { it.id }
        var building = city.buildings.filter { CivsInfrastructureRebuild.needsRefit(city, it) && it.id in available }
            .minWithOrNull(compareBy<CivBuilding> { if (it.type == BuildingType.GATE) 0 else 1 }
                .thenBy { it.bounds.min.x }.thenBy { it.bounds.min.z }) ?: return false
        if (!developmentAdmission(city)) return false
        val request = UUID.randomUUID()
        val layout = civsPlanBoardLayoutKey(city)
        autonomyRequests[city.id] = request
        autonomyProgress[city.id] = CivsAutonomyView("planning", building.type, building.level)
        val preparing = runCatching { loadAutonomousPlans(city).thenCompose { plans ->
            val work = CivsInfrastructureRebuild.next(city, plans, available)
                ?: throw CivsRejection("gate-refit-unavailable")
            building = work.building
            val seam = work.infill
            if (seam != null) autonomousPlacement.wallInfill(city, seam.gateId, seam.wallId, emptyList())
            else {
                val source = plans.singleOrNull { it.building.id == building.id }
                    ?: throw CivsRejection("gate-refit-source-missing")
                autonomousPlacement.rebuildInfrastructure(city, source, plans, work.survivor)
            }
        } }.getOrElse { CompletableFuture.failedFuture(it) }
        preparing.whenCompleteSync(tasks) prepared@ { design, failure ->
            if (autonomyRequests[city.id] != request) return@prepared
            fun finished(error: Throwable?) {
                autonomyRequests.remove(city.id, request)
                developmentFinished(city.id)
                if (closed) return
                if (error != null) {
                    infrastructureRebuildRetryAt[building.id] = now() + 300_000L
                    autonomyFailed(city.id, CivsAutonomy.goal(city, building.type, building.level, building.id), error)
                } else {
                    infrastructureRebuildRetryAt.remove(building.id)
                    autonomyProgress[city.id] = CivsAutonomyView("queued", building.type, building.level)
                    CivsDiagnostics.event("autonomous_fortification_rebuild_queued", "city" to city.id,
                        "building" to requireNotNull(design).blueprint.building.id, "type" to requireNotNull(design).blueprint.building.type,
                        "stage" to CivsInfrastructureRebuild.stage(city))
                    pulseConstruction()
                }
            }
            if (failure != null) { finished(failure); return@prepared }
            runCatching { afterCityAvailable(city.id) {
                val current = cities[city.id] ?: throw CivsRejection("no-city")
                rejectIf(!ready() || autonomyRequests[city.id] != request || current.autonomy?.paused != false ||
                    civsPlanBoardLayoutKey(current) != layout || current.construction != null ||
                    current.constructionQueue.orEmpty().isNotEmpty() || !settlementActive(current), "stale-development")
                commitAutonomousDesign(current, requireNotNull(design))
            } }.onSuccess { it.whenCompleteSync(tasks) { _, error -> finished(error) } }.onFailure(::finished)
        }
        return true
    }

    private fun pulseAutonomousDecoration(city: Settlement) {
        decorationCursor.keys.retainAll(cities.keys)
        if (city.autonomy?.paused != false || city.construction != null || city.constructionQueue.orEmpty().isNotEmpty() ||
            city.id in autonomyRequests || city.id in busy || city.id in unhealthy ||
            city.buildings.count { it.type == BuildingType.DECORATION } >= CivsRules.decorationLimit(city) ||
            city.buildings.count { it.type == BuildingType.DECORATION &&
                !it.schematicId.startsWith("civs/fortifications/v1/") } >= CivsRules.MAX_AUTONOMOUS_SCENERY ||
            CivsAutonomy.nextGoal(city, blueprints::maximumLevel) != null) return
        val choices = blueprints.smallDecorations(city.style).filter { choice -> city.buildings.none { it.schematicId == choice.id } }
        if (choices.isEmpty()) return
        val next = decorationCursor[city.id] ?: Math.floorMod(city.id.hashCode(), choices.size)
        val choice = choices[Math.floorMod(next, choices.size)]
        decorationCursor[city.id] = (next + 1) % 32
        val request = UUID.randomUUID()
        autonomyRequests[city.id] = request
        autonomyProgress[city.id] = CivsAutonomyView("searching", BuildingType.DECORATION, 1)
        autonomousPlacement.decoration(city, choice.id, UUID.randomUUID()).whenCompleteSync(tasks) { design, failure ->
            if (autonomyRequests[city.id] != request) return@whenCompleteSync
            if (failure != null) {
                autonomyRequests.remove(city.id)
                developmentFinished(city.id)
                autonomyRetryAt[city.id] = now() + 60_000L
                autonomyProgress[city.id] = CivsAutonomyView("blocked", BuildingType.DECORATION, 1,
                    CivsDiagnostics.rejection(failure)?.reason ?: "placement-failed")
                return@whenCompleteSync
            }
            runCatching {
                val price = CivsDecorationCost.cost(requireNotNull(design).blueprint.materials)
                afterCityAvailable(city.id) {
                    val current = cities[city.id] ?: throw CivsRejection("no-city")
                    rejectIf(autonomyRequests[city.id] != request || !civsSameBuildingLayout(current.buildings, city.buildings) ||
                        current.autonomy?.paused != false || current.construction != null ||
                        current.constructionQueue.orEmpty().isNotEmpty() || !settlementActive(current), "stale-development")
                    rejectIf(CivsAutonomy.legacyStock(current) && !current.stock.covers(price), "not-enough-resources")
                    commitAutonomousDesign(current, design)
                }
            }.getOrElse { CompletableFuture.failedFuture<Unit>(it) }.whenCompleteSync(tasks) { _, commitFailure ->
                if (autonomyRequests.remove(city.id, request)) {
                    if (commitFailure == null) autonomyProgress[city.id] = CivsAutonomyView("queued", BuildingType.DECORATION, 1)
                    else {
                        val roadAdmission = CivsDiagnostics.rejection(commitFailure)?.reason == "city-busy"
                        if (!roadAdmission) developmentFinished(city.id)
                        autonomyRetryAt[city.id] = now() + if (roadAdmission) 5_000L else 60_000L
                        autonomyProgress[city.id] = CivsAutonomyView("blocked", BuildingType.DECORATION, 1,
                            CivsDiagnostics.rejection(commitFailure)?.reason ?: "placement-failed")
                    }
                }
            }
        }
    }

    private fun autonomyFailed(cityId: UUID, goal: CivAutonomyGoal, failure: Throwable, acceleratedRetry: Boolean = false) {
        val rejection = CivsDiagnostics.rejection(failure)
        val reason = rejection?.reason ?: "placement-failed"
        // Geometric evidence is recorded by the search callback against its captured input and candidate.
        // An upkeep admission retry keeps its one-job yield; other failed searches release ordinary maintenance.
        if (reason != "city-busy") developmentFinished(cityId)
        autonomyRetryAt[cityId] = now() + if (acceleratedRetry ||
            reason in setOf("world-busy", "city-busy", "stale-development")) 5_000 else 30_000
        val next = CivsAutonomyView("blocked", goal.type, goal.targetLevel, reason)
        if (autonomyProgress.put(cityId, next) != next) CivsDiagnostics.event("autonomous_goal_blocked",
            "city" to cityId, "type" to goal.type, "level" to goal.targetLevel, "reason" to reason,
            "details" to rejection?.values)
        if (reason !in setOf("world-busy", "city-busy", "stale-development", "chunks-unloaded", "chunk-unloaded", "not-enough-resources")) {
            val warnAt = autonomyWarnAt.getOrPut(cityId) { now() + 60_000 }
            if (now() >= warnAt) {
                autonomyWarnAt[cityId] = now() + 60_000
                CivsDiagnostics.record(Level.WARNING, cityId, CivsDiagnostics.line("autonomous_development_stalled", "city" to cityId,
                    "type" to goal.type, "level" to goal.targetLevel, "reason" to reason,
                    "details" to rejection?.values,
                    "placement" to autonomousPlacement.debug(cityId)), plugin.logger)
            }
        }
        if (CivsDiagnostics.rejection(failure) == null) log("develop autonomous settlement $cityId", failure, cityId)
    }

    fun terrainChanged(world: UUID, point: CivPosition) {
        searchFailures.changed(world, point).forEach { cityId ->
            autonomyPlanRetryAt.remove(cityId)
            autonomyLayoutChecked.remove(cityId)
            // Layout/plot identity owns continuation; every next candidate still repeats native proof.
        }
    }

    private fun rejectIf(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
    private fun now() = clock()
    private fun log(context: String, error: Throwable, cityId: UUID) =
        CivsDiagnostics.record(Level.WARNING, cityId, "ArcCivs could not $context", plugin.logger, error)
}
