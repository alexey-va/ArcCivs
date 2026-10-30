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
) {
    private val cities get() = settlements()
    private val closed get() = isClosed()
    // Terrain can change without a city revision; retry a settled layout after five minutes.
    private val autonomyLayoutChecked = mutableMapOf<UUID, Pair<List<CivBuilding>, Long>>()
    private val autonomyRequests = mutableMapOf<UUID, UUID>()
    private val autonomyProgress = mutableMapOf<UUID, CivsAutonomyView>()
    private val autonomyRetryAt = mutableMapOf<UUID, Long>()
    private val autonomyPlanRetryAt = mutableMapOf<UUID, Long>()
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
    }

    fun reset() {
        (autonomyRequests.keys + autonomyProgress.keys + autonomyRetryAt.keys).toSet().forEach(developmentFinished)
        autonomyRequests.clear()
        autonomyLayoutChecked.clear()
        autonomyProgress.clear()
        autonomyRetryAt.clear()
        autonomyPlanRetryAt.clear()
        autonomousAccessChecked.clear()
        autonomousAccessRetryAt.clear()
    }

    fun cancel() {
        (autonomyRequests.keys + autonomyProgress.keys + autonomyRetryAt.keys).toSet().forEach(developmentFinished)
        autonomyRequests.clear()
        autonomyLayoutChecked.clear()
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
        val candidates = cities.values.filter { it.autonomy?.paused == false && CivsAutoStorageMigration.ready(it) && it.pendingDeletion == null &&
            accessReady(it) &&
            it.id !in busy && it.id !in unhealthy && !isPlacing(it.id) && it.id !in autonomyRequests &&
            it.pendingControls.orEmpty().isEmpty() && it.construction == null &&
            it.constructionQueue.orEmpty().isEmpty() && now() >= (autonomyRetryAt[it.id] ?: 0) && settlementActive(it) }
        if (candidates.isEmpty()) return
        val city = candidates[Math.floorMod(autonomyCursor++, candidates.size)]
        val ordinary = CivsAutonomy.nextGoal(city, blueprints::maximumLevel)
        val adopted = CivsSettlementPlans.current(city)
        val checked = autonomyLayoutChecked[city.id]
        val layoutCheckDue = checked == null || !civsSameBuildingLayout(checked.first, city.buildings) || now() >= checked.second
        var infrastructure = CivsAutonomy.infrastructureDue(city, ordinary) && layoutCheckDue
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
        if (adopted == null && existing == null && now() >= (autonomyPlanRetryAt[city.id] ?: 0L)) {
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
                    autonomyFailed(city.id, goal, failure)
                } else mutateWhenAvailable(city.id) { current ->
                    rejectIf(autonomyRequests[city.id] != request || civsPlanBoardLayoutKey(current) != key,
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
                val latest = if (infrastructure) CivsAutonomy.goal(current, goal.type, 1, null) else CivsAutonomy.nextGoal(current, blueprints::maximumLevel)
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

    private val decorationCursor = linkedMapOf<UUID, Int>()

    /** Optional small civic scenery follows the complete essential town and its paid infrastructure. */
    private fun pulseAutonomousDecoration(city: Settlement) {
        decorationCursor.keys.retainAll(cities.keys)
        if (city.autonomy?.paused != false || city.construction != null || city.constructionQueue.orEmpty().isNotEmpty() ||
            city.id in autonomyRequests || city.id in busy || city.id in unhealthy ||
            city.buildings.count { it.type == BuildingType.DECORATION } >= CivsRules.decorationLimit(city) ||
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
