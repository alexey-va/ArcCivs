package ru.arc.civs

import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.logging.Level

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
) {
    private val cities get() = settlements()
    private val closed get() = isClosed()
    // Terrain can change without a city revision; retry a settled layout after five minutes.
    private val autonomyLayoutChecked = mutableMapOf<UUID, Pair<List<CivBuilding>, Long>>()
    private val autonomyRequests = mutableMapOf<UUID, UUID>()
    private val autonomyProgress = mutableMapOf<UUID, CivsAutonomyView>()
    private val autonomyRetryAt = mutableMapOf<UUID, Long>()
    private val autonomyPlanRetryAt = mutableMapOf<UUID, Long>()
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
    private var autonomyCursor = 0
    private var nextAutonomyAt = 0L

    fun progress(cityId: UUID): CivsAutonomyView? = autonomyProgress[cityId]
    fun debug(cityId: UUID): Map<String, Any?> = mapOf(
        "request" to autonomyRequests[cityId], "retry_at" to autonomyRetryAt[cityId],
        "placement" to autonomousPlacement.debug(cityId))

    fun paused(cityId: UUID) {
        developmentFinished(cityId)
        autonomyRequests.remove(cityId)
        autonomyRetryAt.remove(cityId)
        autonomyProgress.remove(cityId)
        unpaidRecoveryCursors.remove(cityId)
    }

    fun reset() {
        (autonomyRequests.keys + autonomyProgress.keys + autonomyRetryAt.keys).toSet().forEach(developmentFinished)
        autonomyRequests.clear()
        autonomyLayoutChecked.clear()
        autonomyProgress.clear()
        autonomyRetryAt.clear()
        autonomyPlanRetryAt.clear()
        unpaidRecoveryCursors.clear()
        autonomousAccessChecked.clear()
        autonomousAccessRetryAt.clear()
    }

    fun cancel() {
        (autonomyRequests.keys + autonomyProgress.keys + autonomyRetryAt.keys).toSet().forEach(developmentFinished)
        autonomyRequests.clear()
        autonomyLayoutChecked.clear()
        unpaidRecoveryCursors.clear()
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
        if (now() < nextAutonomyAt) return
        nextAutonomyAt = now() + 5_000
        unpaidRecoveryCursors.entries.removeIf { (cityId, cursor) ->
            val latest = cities[cityId]
            val plan = latest?.let(CivsSettlementPlans::current)
            latest == null || plan == null || unpaidPlot(latest, plan, cursor.type)?.building?.id != cursor.buildingId || !cursor.matches(latest)
        }
        val candidates = cities.values.filter { it.autonomy?.paused == false && CivsAutoStorageMigration.ready(it) && it.pendingDeletion == null &&
            accessReady(it) &&
            it.id !in busy && it.id !in unhealthy && !isPlacing(it.id) && it.id !in autonomyRequests &&
            it.pendingControls.orEmpty().isEmpty() && it.construction == null &&
            it.constructionQueue.orEmpty().isEmpty() && now() >= (autonomyRetryAt[it.id] ?: 0) && settlementActive(it) }
        if (candidates.isEmpty()) return
        val city = candidates[Math.floorMod(autonomyCursor++, candidates.size)]
        val capacity = if (storageCapacityPressure(city)) CivsAutonomy.capacityGoal(city, blueprints::maximumLevel) else null
        val ordinary = capacity ?: CivsAutonomy.nextGoal(city, blueprints::maximumLevel)
        val adopted = CivsSettlementPlans.current(city)
        val checked = autonomyLayoutChecked[city.id]
        val layoutCheckDue = checked == null || !civsSameBuildingLayout(checked.first, city.buildings) || now() >= checked.second
        var infrastructure = capacity == null && CivsAutonomy.infrastructureDue(city, ordinary) && layoutCheckDue
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
        val extendPlan = adopted != null && existing == null && !infrastructure &&
            CivsSettlementPlans.next(city, goal.type) == null && CivsSettlementPlans.needsExtension(city)
        if (extendPlan && now() < (autonomyPlanRetryAt[city.id] ?: 0L)) return
        if ((adopted == null || extendPlan) && existing == null && now() >= (autonomyPlanRetryAt[city.id] ?: 0L)) {
            val request = UUID.randomUUID()
            val key = civsPlanBoardLayoutKey(city)
            autonomyRequests[city.id] = request
            autonomyProgress[city.id] = CivsAutonomyView("planning", goal.type, goal.targetLevel)
            runCatching { autonomousPlacement.plan(civsPlanBoardCapture(city)) }
                .getOrElse { CompletableFuture.failedFuture(it) }.whenCompleteSync(tasks) { plan, failure ->
                if (autonomyRequests[city.id] != request) return@whenCompleteSync
                if (failure != null) {
                    autonomyRequests.remove(city.id)
                    autonomyPlanRetryAt[city.id] = now() + if (CivsDiagnostics.rejection(failure)?.reason in
                        setOf("world-busy", "chunks-unloaded", "stale-development")) 30_000 else 300_000
                    // Optional new reservations must not keep an existing upgrade behind another ROAD check.
                    if (adopted == null && infrastructure && ordinary?.buildingId?.let(city::building)?.phase == BuildingPhase.ACTIVE)
                        autonomyLayoutChecked[city.id] = city.buildings.toList() to autonomyPlanRetryAt.getValue(city.id)
                    autonomyFailed(city.id, goal, failure)
                } else mutateWhenAvailable(city.id) { current ->
                    rejectIf(autonomyRequests[city.id] != request || civsPlanBoardLayoutKey(current) != key ||
                        current.construction != null || current.constructionQueue.orEmpty().isNotEmpty(),
                        "stale-development")
                    CivsRules.validate(current.copy(revision = current.revision + 1,
                        autonomy = requireNotNull(current.autonomy).copy(layoutHalfWidth = requireNotNull(plan).halfWidth,
                            plan = CivsSettlementPlans.detached(plan))))
                }.whenCompleteSync(tasks) { _, saveFailure ->
                    autonomyRequests.remove(city.id, request)
                    if (saveFailure != null) autonomyFailed(city.id, goal, saveFailure)
                    else {
                        autonomyProgress.remove(city.id)
                        autonomyRetryAt.remove(city.id)
                        autonomyPlanRetryAt.remove(city.id)
                        CivsDiagnostics.event("autonomous_plan_adopted", "city" to city.id,
                            "plots" to requireNotNull(plan).plots.size, "roads" to plan.routes.size, "revision" to plan.revision)
                    }
                }
            }
            return
        }
        if (!infrastructure && ordinary == null) return
        if (!goal.ready) return
        val request = UUID.randomUUID()
        autonomyRequests[city.id] = request
        autonomyProgress[city.id] = CivsAutonomyView("searching", goal.type, goal.targetLevel)
        CivsDiagnostics.event("autonomous_goal_selected", "city" to city.id, "request" to request,
            "type" to goal.type, "level" to goal.targetLevel, "stock" to city.stock, "cost" to goal.cost)
        val reservedPlots = if (!infrastructure && existing == null && adopted != null &&
            unpaidPlot(city, adopted, goal.type) != null) adopted.plots.filter {
                it.building.type == goal.type && it.building.level == 1 && city.building(it.building.id) == null
            } else emptyList()
        val reservedLayoutKey = if (reservedPlots.isEmpty()) null else civsPlanBoardLayoutKey(city)
        var nextReserved = 1
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
                CivsAutonomy.infrastructureDue(city, null)) {
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
                    if (ordinary == null) developmentFinished(city.id)
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
                rejectIf(infrastructure && !civsSameBuildingLayout(current.buildings, city.buildings), "stale-development")
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
            !CivsVillageSiteScoring.completeProfile(plan) || type !in CivsAutonomy.profile || type == BuildingType.TOWN_HALL ||
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
                unpaidRecoveryCursors[city.id] !== cursor || !cursor.matches(latest) ||
                unpaidPlot(latest, oldPlan, goal.type)?.building?.id != oldPlot.building.id, "stale-development")
            return latest
        }
        fun failed(failure: Throwable) {
            if (autonomyRequests[city.id] != request) return
            val latest = cities[city.id]
            if (latest == null || !cursor.matches(latest) || unpaidPlot(latest, oldPlan, goal.type)?.building?.id != oldPlot.building.id)
                unpaidRecoveryCursors.remove(city.id)
            if (autonomyRequests.remove(city.id, request)) autonomyFailed(city.id, goal, failure)
        }
        val snapshot = runCatching { civsPlanBoardCapture(current()) }.getOrElse { failed(it); return }
        runCatching { autonomousPlacement.search(snapshot, goal.type, 1, oldPlot.building.id,
            captureVolume = true, initialPlan = oldPlan, skipPrepared = cursor.skipPrepared) }
            .getOrElse { CompletableFuture.failedFuture(it) }.whenCompleteSync(tasks) { design, searchFailure ->
            if (autonomyRequests[city.id] != request) return@whenCompleteSync
            if (searchFailure != null) {
                val rejection = CivsDiagnostics.rejection(searchFailure)
                if (rejection?.reason in setOf("autonomy-no-site", "autonomy-search-budget"))
                    unpaidRecoveryCursors.remove(city.id, cursor)
                failed(searchFailure)
                return@whenCompleteSync
            }
            val latest = runCatching { current() }.getOrElse { failed(it); return@whenCompleteSync }
            val replacement = requireNotNull(design)
            runCatching { autonomousPlacement.plan(civsPlanBoardCapture(latest), replacement) }
                .getOrElse { CompletableFuture.failedFuture(it) }.whenCompleteSync(tasks) planning@ { plan, planFailure ->
                if (autonomyRequests[city.id] != request) return@planning
                if (planFailure != null) {
                    val rejection = CivsDiagnostics.rejection(planFailure)
                    if (rejection?.reason in setOf("autonomy-layout-obstructed", "autonomy-no-site") ||
                        rejection?.reason == "autonomy-search-budget" && rejection.values["stage"] == "dry-perimeter") {
                        runCatching { current() }.getOrElse { failed(it); return@planning }
                        unpaidRecoveryCursors[city.id] = cursor.copy(skipPrepared = cursor.skipPrepared + 1)
                    }
                    failed(planFailure)
                    return@planning
                }
                val accepted = requireNotNull(plan)
                runCatching { mutateWhenAvailable(city.id) { fresh ->
                    current()
                    rejectIf(unpaidPlot(fresh, oldPlan, goal.type)?.building?.id != oldPlot.building.id || civsPlanBoardLayoutKey(fresh) != key ||
                        accepted.revision != oldPlan.revision + 1 || accepted.halfWidth != oldPlan.halfWidth ||
                        !CivsVillageSiteScoring.completeProfile(accepted) ||
                        accepted.plots.count { it.building.type != BuildingType.DECORATION } != CivsAutonomy.completeProfile.size ||
                        accepted.plots.singleOrNull { it.building.id == oldPlot.building.id }?.building !=
                            replacement.blueprint.building.copy(phase = BuildingPhase.SITE) ||
                        accepted.routes[oldPlot.building.id.toString()] != replacement.terrain?.route, "stale-development")
                    CivsRules.validate(fresh.copy(revision = fresh.revision + 1,
                        autonomy = requireNotNull(fresh.autonomy).copy(plan = CivsSettlementPlans.detached(accepted))))
                } }.getOrElse { failed(it); return@planning }.whenCompleteSync(tasks) { _, saveFailure ->
                    if (saveFailure != null) { failed(saveFailure); return@whenCompleteSync }
                    if (!autonomyRequests.remove(city.id, request)) return@whenCompleteSync
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

    private val decorationCursor = linkedMapOf<UUID, Int>()

    /** Optional small civic scenery follows the complete essential town and its paid infrastructure. */
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

    private fun autonomyFailed(cityId: UUID, goal: CivAutonomyGoal, failure: Throwable) {
        val rejection = CivsDiagnostics.rejection(failure)
        val reason = rejection?.reason ?: "placement-failed"
        // An upkeep admission retry keeps its one-job yield; other failed searches release ordinary maintenance.
        if (reason != "city-busy") developmentFinished(cityId)
        autonomyRetryAt[cityId] = now() + if (reason in setOf("world-busy", "city-busy", "stale-development")) 5_000 else 30_000
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

    private fun rejectIf(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
    private fun now() = clock()
    private fun log(context: String, error: Throwable, cityId: UUID) =
        CivsDiagnostics.record(Level.WARNING, cityId, "ArcCivs could not $context", plugin.logger, error)
}
