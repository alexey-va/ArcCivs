package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.util.BoundingBox
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.UUID
import kotlin.math.floor

internal data class CitizenRouteIdentity(val world: UUID, val policy: CitizenRouteReuseKey,
    val from: CitizenSearchNode, val to: CitizenPoint, val settings: CivsNavigationSettings,
    val pickupContainer: CivPosition? = null) {
    private val stableHash = 31 * (31 * (31 * (31 * (31 * world.hashCode() + policy.hashCode()) +
        from.hashCode()) + to.hashCode()) + settings.hashCode()) + pickupContainer.hashCode()
    override fun hashCode(): Int = stableHash
}

/**
 * Main-thread capture/result owner, like MineColonies' job/result/controller split. Core owns
 * native scheduling and bounded worker admission. Each route has one pure slice OR one native
 * cursor, never a worker waiting for Paper. Closing fences every result before draining workers.
 */
internal class CivsResidentNavigation(
    private val tasks: LifecycleTaskScope,
    nativeWork: BudgetedTaskQueue? = null,
    executor: Executor? = null,
    private val settings: CivsNavigationSettings = CivsNavigationSettings(),
    private val nanoTime: () -> Long = System::nanoTime,
    snapshotCache: CivsNavigationSnapshots? = null,
    snapshotContextFree: ((org.bukkit.block.data.BlockData) -> Boolean)? = null,
) {
    private class Job(val route: CitizenPacketRoute, var search: CitizenNavigationSearch, val startedNanos: Long) {
        var progressTick = Bukkit.getCurrentTick().toLong()
        var nativeProgress = 0L
        val terrain = hashMapOf<CivPosition, CitizenTerrainBlock>()
        var searching: CompletableFuture<CitizenSearchResult>? = null
        var capturing: CompletableFuture<Unit>? = null
        var pickupAnswer: Boolean? = null
        var capture: CitizenNavigationCapture? = null
        var snapshotTerrain = CitizenSnapshotTerrain()
        var snapshotCapture: CitizenSnapshotCapture? = null
        var snapshotWorker: CompletableFuture<CitizenSnapshotRequest>? = null
        var snapshotResult: List<CitizenSearchCapture>? = null
        var snapshotPending: CitizenSnapshotRequest? = null
        var seededInitial = false
        var publishedPayloads = emptyList<CitizenSnapshotSection>()
        var snapshotNative: CompletableFuture<Unit>? = null
        val snapshotRevisions = hashMapOf<CitizenSnapshotSectionKey, Long>()
        var reuse: ReuseAttempt? = null
        var debugTrace: DebugTrace? = null
        var publishedNodes = 0L
        var publishedCells = 0L
        var lastNodes = 0
        var lastCells = 0
    }
    private class DebugTrace(val context: CitizenNavigationDebugContext) {
        val rounds = mutableListOf<CitizenNavigationDebugRound>()
        var inputs = emptyList<CitizenSearchCapture>()
        var probes = 0
        var budgetCall: String? = null
        var cursor: CitizenNavigationDebugCursor? = null
    }
    private class DebugRequest(val arm: CitizenNavigationDebugArm, val expiresNanos: Long,
        val current: () -> Boolean, val save: (CitizenNavigationDebugFrame) -> CompletableFuture<Map<String, Any?>>) {
        var job: Job? = null
        var writing: CompletableFuture<Map<String, Any?>>? = null
    }
    private var debugRequest: DebugRequest? = null
    private val debugGeneration = UUID.randomUUID()

    private data class RouteSection(val world: UUID, val x: Int, val y: Int, val z: Int)
    private class CachedRoute(val steps: List<CitizenPlannedStep>, val ground: Set<CivPosition>,
        val sections: Set<RouteSection>, val cityId: UUID?) {
        val suffixLength = DoubleArray(steps.size).also { lengths ->
            for (index in steps.lastIndex - 1 downTo 0)
                lengths[index] = lengths[index + 1] + kotlin.math.sqrt(steps[index].point.distanceSquared(steps[index + 1].point))
        }
    }
    private class ReuseAttempt(val source: CitizenRouteIdentity, val request: CitizenRouteIdentity,
        val cached: CachedRoute, val steps: List<CitizenPlannedStep>, val splice: Boolean, origin: CitizenPoint) {
        var next = 0
        var previous = origin
        var valid = true
        var endpointAvailable = true
        var cost = 0.0
    }
    private data class Suffix(val source: CitizenRouteIdentity, val cached: CachedRoute, val index: Int)
    private enum class SharedRouteCounter {
        ELIGIBLE, EXCLUDED_NO_REUSE_POLICY, EXCLUDED_EXPLICIT_CORRIDOR, EXCLUDED_BODY_DETOUR,
        EXACT_CANDIDATES, SUFFIX_CANDIDATES, EXACT_HITS, SUFFIX_HITS, REJECTED, REJECTED_CONNECTORS, LEARNED,
        MISS_NO_NEARBY_ENTRIES, MISS_NO_MATCHING_ENDPOINT, MISS_NO_COMPATIBLE_ENTRY, MISS_NO_JOIN_POINT,
        SUFFIX_CANDIDATE_LIMIT_REACHED,
        REMOVED_CAPACITY, REMOVED_REPLACEMENT, REMOVED_NATIVE_CHANGE, REMOVED_REPLAN,
        REMOVED_REPLAY_OR_ROUTE_FAILURE, REMOVED_UNLOAD;

        val field = name.lowercase()
    }
    private class CityRouteCounters {
        val values = LongArray(SharedRouteCounter.entries.size)
        var lastRoster = 0L
    }
    private val owner = Thread.currentThread()
    private val epoch = tasks.token()
    private val ownsWork = nativeWork == null
    private val work = nativeWork ?: BudgetedTaskQueue(tasks, settings.nativeOperationsPerTick,
        (settings.nativeBudgetMillis * 1_000_000.0).toLong(), System::nanoTime, settings.maximumJobs)
    private val ownedExecutor = if (executor == null) BoundedAsyncExecutor("ArcCivs-navigation", 1, settings.maximumJobs) else null
    private val executor = executor ?: requireNotNull(ownedExecutor)
    private val jobs = linkedMapOf<CitizenPacketRoute, Job>()
    private val terrain = CivsNavigationTerrain(settings.terrainCacheBlocks, settings.terrainRefreshTicks)
    private val snapshots = snapshotCache ?: CivsNavigationSnapshots(settings.terrainCacheBlocks, settings.terrainRefreshTicks, tasks, snapshotContextFree,
        maximumSectionsPerCity = settings.snapshotSectionsPerCity, maximumSections = settings.snapshotSectionsMaximum)
    private val warmup = CivsNavigationWarmup(settings, snapshots, tasks, work, this.executor)
    private val sharedRoutes = LinkedHashMap<CitizenRouteIdentity, CachedRoute>(64, .75f, true)
    private val routeSections = hashMapOf<RouteSection, MutableSet<CitizenRouteIdentity>>()
    private val sharedRouteTotals = LongArray(SharedRouteCounter.entries.size)
    private val unknownCityRoutes = LongArray(SharedRouteCounter.entries.size)
    private val sharedRouteCities = linkedMapOf<UUID, CityRouteCounters>()
    private var sharedRouteRoster = 0L
    private var replayChecks = 0L
    private var rejectedSnapshotSections = 0L
    private var joinCandidates = 0L
    private var joinSteps = 0L
    private var lastReplayCost = 0.0
    private var closed = false
    private var staleSnapshotRestarts = 0L
    private var snapshotMainRequests = 0L
    private var readyPreparations = 0L
    private var failedPreparations = 0L
    private var cancelledPreparations = 0L
    private var preparationNanos = 0L
    private var maximumPreparationNanos = 0L
    private val preparationBoundsMillis = longArrayOf(100, 250, 500, 1_000, 2_000, 5_000, 10_000, Long.MAX_VALUE)
    private val preparationBuckets = LongArray(preparationBoundsMillis.size)
    private val preparationWork = Array(3) { LongArray(4) }
    private var terminal = CompletableFuture.completedFuture(Unit)

    /** A single opt-in recorder. Existing requests are never attached halfway through their history. */
    internal fun armDebug(identity: CitizenNavigationDebugIdentity, current: () -> Boolean,
        save: (CitizenNavigationDebugFrame) -> CompletableFuture<Map<String, Any?>>): CitizenNavigationDebugArm {
        checkOwner()
        pollDebug()
        if (closed || !tasks.isCurrent(epoch)) throw CivsRejection("not-ready")
        if (debugRequest != null) throw CivsRejection("navigation-file-busy")
        if (!current()) throw CivsRejection("stale-navigation-intent")
        val arm = CitizenNavigationDebugArm(UUID.randomUUID(), identity, System.currentTimeMillis() + 120_000,
            CompletableFuture())
        debugRequest = DebugRequest(arm, nanoTime() + 120_000_000_000L, current, save)
        return arm
    }

    internal fun debugSelected(city: UUID, worker: UUID): Boolean = debugRequest?.arm?.identity?.let {
        it.city == city && it.worker == worker
    } == true

    internal fun attachDebug(route: CitizenPacketRoute, identity: CitizenNavigationDebugIdentity,
        supported: Boolean, context: () -> CitizenNavigationDebugContext) {
        checkOwner()
        pollDebug()
        val request = debugRequest?.takeIf { it.job == null && it.writing == null && it.arm.identity == identity } ?: return
        val job = jobs[route]
        if (!supported || job == null || job.reuse != null || route.snapshotPolicy?.supportCost == null ||
            route.hasCourierCorridorHint || settings.maximumCapturedBlocks > CivsNavigationDebugFile.MAX_CELLS ||
            settings.maximumNodes > 4096) {
            failDebug("unsupported-navigation-request")
            return
        }
        try {
            job.debugTrace = DebugTrace(context())
            request.job = job
        } catch (_: RuntimeException) { failDebug("navigation-context-failed") }
    }

    private fun failDebug(reason: String) {
        val request = debugRequest ?: return
        if (request.writing != null) return
        request.job?.debugTrace = null
        request.arm.completion.completeExceptionally(CivsRejection(reason))
        debugRequest = null
    }

    private fun pollDebug() {
        val request = debugRequest ?: return
        if (request.writing != null) {
            if (request.writing?.isDone == true) debugRequest = null
            return
        }
        when {
            request.arm.completion.isCancelled -> failDebug("navigation-capture-cancelled")
            nanoTime() >= request.expiresNanos -> failDebug("navigation-capture-expired")
            !runCatching(request.current).getOrDefault(false) -> failDebug("stale-navigation-intent")
        }
    }

    private fun recordDebug(job: Job, result: CitizenSearchResult) {
        val trace = job.debugTrace ?: return
        if (trace.budgetCall != null) return
        trace.probes += trace.inputs.size + result.probes.size
        if (trace.rounds.size >= CivsNavigationDebugFile.MAX_ROUNDS || trace.probes > CivsNavigationDebugFile.MAX_PROBES) {
            failDebug("navigation-transcript-too-large")
            return
        }
        trace.rounds += CitizenNavigationDebugRound(trace.inputs, result.probes, result.nodes)
        trace.inputs = emptyList()
    }

    private fun finishDebug(job: Job, result: CitizenSearchResult) {
        val request = debugRequest?.takeIf { it.job === job && it.writing == null } ?: return
        val trace = job.debugTrace ?: return
        pollDebug()
        if (debugRequest !== request) return
        if (!CivsNavigationDebugFile.supportsFailure(result, trace.budgetCall)) {
            job.debugTrace = null
            debugRequest = null
            request.arm.completion.complete(request.arm.receipt() + mapOf("state" to "not-captured",
                "outcome" to when { result.continuation -> "partial"; result.path != null -> "ready"; else -> "failed" },
                "failure" to result.failure, "nodes" to result.nodes, "reported_blocks" to job.route.capturedBlocks))
            return
        }
        val (minimumY, maximumY) = job.route.captureLimits()
        val frame = CitizenNavigationDebugFrame(request.arm.request, debugGeneration, trace.context, settings, minimumY, maximumY,
            trace.rounds.toList(), result, trace.budgetCall, job.route.capturedBlocks, job.snapshotTerrain,
            trace.cursor, System.currentTimeMillis())
        job.debugTrace = null
        request.job = null
        val writing = try { request.save(frame) } catch (failure: Throwable) { CompletableFuture.failedFuture(failure) }
        request.writing = writing
        writing.whenComplete { receipt, failure ->
            if (failure == null) request.arm.completion.complete(receipt)
            else request.arm.completion.completeExceptionally(failure)
        }
    }

    internal fun debug(cityId: UUID? = null): Map<String, Any?> {
        checkOwner()
        val suffixHits = sharedRouteTotal(SharedRouteCounter.SUFFIX_HITS)
        val owned = jobs.values.filter { cityId == null || it.route.snapshotPolicy?.cityId == cityId }
        val jobPayloads = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<CitizenSnapshotSection, Boolean>())
        owned.forEach { jobPayloads.addAll(it.publishedPayloads) }
        val cachedPayloads = snapshots.retainedPayloads().toSet()
        return mapOf("closed" to closed, "jobs" to jobs.size, "maximum_jobs" to settings.maximumJobs,
            "native_pending" to jobs.values.count { it.capturing?.isDone == false && it.snapshotCapture == null },
            "worker_pending" to jobs.values.count { it.searching?.isDone == false || it.snapshotWorker?.isDone == false },
            "bulk_jobs" to jobs.values.count { it.route.snapshotPolicy != null },
            "captured_blocks" to jobs.values.sumOf { it.route.capturedBlocks }, "terrain_cache" to terrain.debug(),
            "snapshot_cache" to snapshots.debug(cityId), "snapshot_warmup" to warmup.debug(cityId), "snapshot_main_requests" to snapshotMainRequests,
            "active_job_section_payload_bytes" to jobPayloads.sumOf { it.indexPayloadBytes },
            "active_job_section_payload_mib" to jobPayloads.sumOf { it.indexPayloadBytes } / 1048576.0,
            "active_job_exclusive_section_payload_bytes" to jobPayloads.filter { it !in cachedPayloads }.sumOf { it.indexPayloadBytes },
            "active_job_payload_scope" to "immutable-last-worker-publication; includes local unconsumed seed sections",
            "stale_snapshot_restarts" to staleSnapshotRestarts,
            "used_snapshot_sections" to jobs.values.sumOf { it.snapshotRevisions.size },
            "section_snapshot_rejects" to rejectedSnapshotSections,
            "shared_routes" to mapOf("entries" to sharedRoutes.size, "maximum_entries" to MAXIMUM_SHARED_ROUTES,
                "maximum_steps" to CivsPaths.MAX_TRIP_CELLS, "learned" to sharedRouteTotal(SharedRouteCounter.LEARNED),
                "hits" to (sharedRouteTotal(SharedRouteCounter.EXACT_HITS) + suffixHits),
                "misses" to (sharedRouteTotal(SharedRouteCounter.MISS_NO_NEARBY_ENTRIES) +
                    sharedRouteTotal(SharedRouteCounter.MISS_NO_MATCHING_ENDPOINT) +
                    sharedRouteTotal(SharedRouteCounter.MISS_NO_COMPATIBLE_ENTRY) +
                    sharedRouteTotal(SharedRouteCounter.MISS_NO_JOIN_POINT)),
                "rejected" to sharedRouteTotal(SharedRouteCounter.REJECTED),
                "invalidated" to (sharedRouteTotal(SharedRouteCounter.REMOVED_NATIVE_CHANGE) +
                    sharedRouteTotal(SharedRouteCounter.REMOVED_REPLAN)), "native_step_checks" to replayChecks,
                "last_validated_cost" to lastReplayCost),
            "shared_route_suffixes" to mapOf("hits" to suffixHits, "candidates" to joinCandidates,
                "steps_examined" to joinSteps,
                "rejected_connectors" to sharedRouteTotal(SharedRouteCounter.REJECTED_CONNECTORS),
                "maximum_candidates" to MAXIMUM_JOIN_CANDIDATES, "maximum_join_distance" to MAXIMUM_JOIN_DISTANCE),
            "preparation_lifetime" to mapOf("source" to "all-admitted-planner-jobs", "ready" to readyPreparations,
                "failed" to failedPreparations, "cancelled" to cancelledPreparations,
                "published_work" to listOf("ready", "failed", "cancelled").mapIndexed { index, outcome ->
                    val work = preparationWork[index]
                    outcome to mapOf("nodes" to work[0], "captured_cells" to work[1],
                        "search_nanos" to work[2], "native_shape_reads" to work[3])
                }.toMap(),
                "work_scope" to "Main-published work across restarts; unfinished or discarded worker tails excluded",
                "mean_ms" to if (readyPreparations == 0L) 0.0 else preparationNanos / readyPreparations / 1_000_000.0,
                "max_ms" to maximumPreparationNanos / 1_000_000.0,
                "buckets_ms" to preparationBuckets.indices.associate { index ->
                    (if (index == preparationBuckets.lastIndex) ">10000" else "<=${preparationBoundsMillis[index]}") to preparationBuckets[index]
                }))
    }

    private fun sharedRouteTotal(counter: SharedRouteCounter): Long = sharedRouteTotals[counter.ordinal]

    private fun countSharedRoute(counter: SharedRouteCounter, cityId: UUID?) {
        val index = counter.ordinal
        sharedRouteTotals[index]++
        val counters = if (cityId == null) unknownCityRoutes else sharedRouteCities[cityId]?.values
        if (counters != null) counters[index]++
    }

    internal fun debugSharedRoutes(cityId: UUID? = null): Map<String, Any> {
        checkOwner()
        fun counts(values: LongArray) = SharedRouteCounter.entries.associate { it.field to values[it.ordinal] }
        val selected = if (cityId == null) sharedRouteCities else sharedRouteCities[cityId]?.let { mapOf(cityId to it) }.orEmpty()
        val occupancy = sharedRoutes.values.groupingBy { it.cityId }.eachCount()
        return mapOf("entries" to sharedRoutes.size, "maximum_entries" to MAXIMUM_SHARED_ROUTES,
            "request_scope" to "planner-admitted-requests",
            "city_scope" to "counters reset when the city leaves the navigation roster",
            "totals" to counts(sharedRouteTotals),
            "unknown_city" to mapOf("entries" to (occupancy[null] ?: 0), "counters" to counts(unknownCityRoutes)),
            "cities_total" to selected.size, "cities_truncated" to (selected.size > 8),
            "cities" to selected.entries.take(8).map { (id, counters) ->
                mapOf("city" to id, "entries" to (occupancy[id] ?: 0), "counters" to counts(counters.values))
            })
    }

    internal fun prepareNavigation(cities: Collection<Settlement>, active: (UUID) -> Boolean) {
        checkOwner()
        if (closed) return
        if (sharedRouteCities.isNotEmpty()) {
            val roster = ++sharedRouteRoster
            for (city in cities) if (city.pendingDeletion == null) sharedRouteCities[city.id]?.lastRoster = roster
            sharedRouteCities.values.removeIf { it.lastRoster != roster }
        }
        warmup.prepare(cities, active)
    }

    fun request(world: World, from: Location, to: Location, allowed: (CivPosition) -> Boolean,
                stepAllowed: (CitizenPoint, CitizenPoint) -> Boolean,
                travelCost: (Block) -> Double): CitizenPacketRoute =
        admit(CitizenPacketRoute(world, from, to, allowed, stepAllowed, travelCost, settings = settings))

    fun request(world: World, from: Location, to: Location, allowed: (CivPosition) -> Boolean,
                stepAllowed: (CitizenPoint, CitizenPoint) -> Boolean, travelCost: (Block) -> Double,
                corridor: CitizenSearchCorridor?): CitizenPacketRoute =
        admit(CitizenPacketRoute(world, from, to, allowed, stepAllowed, travelCost, settings = settings, corridor = corridor))

    fun request(world: World, from: Location, to: Location, allowed: (CivPosition) -> Boolean,
                stepAllowed: (CitizenPoint, CitizenPoint) -> Boolean, travelCost: (Block) -> Double,
                corridor: CitizenSearchCorridor?, snapshotPolicy: CitizenSnapshotPolicy, pickup: CitizenContainerRouteGoal? = null): CitizenPacketRoute =
        admit(CitizenPacketRoute(world, from, to, allowed, stepAllowed, travelCost, settings = settings,
            corridor = corridor, snapshotPolicy = snapshotPolicy, pickup = pickup))

    fun replan(route: CitizenPacketRoute, from: Location, bodies: List<BoundingBox> = emptyList(),
        refreshSnapshots: Boolean = bodies.isEmpty(), destination: Location? = null): CitizenPacketRoute {
        checkOwner()
        from.world?.let { world ->
            val points = listOf(CivPosition(from.blockX, from.blockY, from.blockZ)) +
                listOfNotNull(route.destination?.let { CivPosition(it.blockX, it.blockY, it.blockZ) },
                    destination?.let { CivPosition(it.blockX, it.blockY, it.blockZ) })
            points.forEach { terrain.changed(world.uid, it) }
            // Resident bodies belong to this search, not to the shared native chunk generation.
            if (refreshSnapshots) points.forEach {
                snapshots.changed(world.uid, it)
                invalidateShared(world.uid, it, SharedRouteCounter.REMOVED_REPLAN)
            }
        }
        // Replacing an unfinished search releases its admission slot. A ready previous path
        // remains with the caller so a blocked-person detour can fall back to waiting on it.
        if (route.searching) route.cancel()
        return admit(route.replan(from, bodies, destination), bodyDetour = bodies.isNotEmpty())
    }

    private fun admit(route: CitizenPacketRoute, bodyDetour: Boolean = false): CitizenPacketRoute {
        checkOwner()
        retireCancelled()
        if (closed || !tasks.isCurrent(epoch)) { route.planningFailed("cancelled"); return route }
        if (jobs.size >= settings.maximumJobs) { route.planningFailed("planner-busy"); return route }
        val job = Job(route, route.newWorkerSearch(), nanoTime())
        jobs[route] = job
        route.attachAsync { job.search.cancel() }
        val key = route.reuseIdentity()
        val cityId = route.snapshotPolicy?.cityId
        if (cityId != null) sharedRouteCities.getOrPut(cityId, ::CityRouteCounters)
        // Production corridors also omit the reuse key. Count the explicit request constraint first.
        countSharedRoute(when {
            key != null -> SharedRouteCounter.ELIGIBLE
            route.hasCourierCorridorHint -> SharedRouteCounter.EXCLUDED_EXPLICIT_CORRIDOR
            bodyDetour -> SharedRouteCounter.EXCLUDED_BODY_DETOUR
            else -> SharedRouteCounter.EXCLUDED_NO_REUSE_POLICY
        }, cityId)
        if (key != null) {
            route.attachReuseObserver({ steps, walked -> rememberRoute(key, steps, walked, cityId) },
                { removeShared(key, SharedRouteCounter.REMOVED_REPLAY_OR_ROUTE_FAILURE) })
            val cached = sharedRoutes[key]
            val suffix = if (cached == null) findSuffix(key, route.reuseOrigin, cityId) else null
            if (cached != null || suffix != null) {
                val candidate = if (suffix == null) SharedRouteCounter.EXACT_CANDIDATES else SharedRouteCounter.SUFFIX_CANDIDATES
                countSharedRoute(candidate, cityId)
                val source = suffix?.source ?: key
                val entry = cached ?: requireNotNull(suffix).cached
                val steps = if (suffix == null) entry.steps else
                    listOf(CitizenPlannedStep(entry.steps[suffix.index].point, CitizenRouteAction.WALK)) +
                        entry.steps.drop(suffix.index + 1)
                val attempt = ReuseAttempt(source, key, entry, steps, suffix != null, route.reuseOrigin)
                job.reuse = attempt
                route.phase("shared-route-validation")
                job.capturing = work.submit({ !closed && tasks.isCurrent(epoch) && route.searching }) {
                    job.progressTick = Bukkit.getCurrentTick().toLong()
                    if (sharedRoutes[source] !== entry) {
                        attempt.valid = false
                        true
                    } else if (attempt.next == attempt.steps.size) {
                        attempt.endpointAvailable = route.confirmPickup(attempt.steps.last().point)
                        true
                    } else {
                        val step = attempt.steps[attempt.next]
                        replayChecks++
                        val cost = route.validatedCachedStepCost(attempt.previous, step)
                        if (!cost.isFinite()) {
                            attempt.valid = false
                            if (attempt.splice && attempt.next == 0)
                                countSharedRoute(SharedRouteCounter.REJECTED_CONNECTORS, cityId)
                            refreshFailedReplay(route, attempt.previous, step.point)
                            true
                        } else {
                            attempt.cost += cost
                            attempt.previous = step.point
                            ++attempt.next == attempt.steps.size && key.pickupContainer == null
                        }
                    }
                }
                return route
            }
        }
        dispatch(job, emptyList())
        return route
    }

    /** Compare the failed bounded segment to cached native data; permission-only denial dirties no chunks. */
    private fun refreshFailedReplay(route: CitizenPacketRoute, from: CitizenPoint, to: CitizenPoint) {
        val (minimumY, maximumY) = route.captureLimits()
        val points = buildList {
            for (x in floor(minOf(from.x, to.x) - .299).toInt()..floor(maxOf(from.x, to.x) + .299).toInt())
                for (z in floor(minOf(from.z, to.z) - .299).toInt()..floor(maxOf(from.z, to.z) + .299).toInt())
                    for (y in maxOf(minimumY, citizenSupportY(minOf(from.y, to.y)))..
                        minOf(maximumY - 1, floor(maxOf(from.y, to.y) + 1.799).toInt())) add(CivPosition(x, y, z))
        }.take(64)
        terrain.refreshObstacles(route.worldId, points)
        snapshots.refreshObstacles(route.worldId, points)
    }

    /** One nearby join from the existing section index; no second spatial search or new voxel grid. */
    private fun findSuffix(request: CitizenRouteIdentity, from: CitizenPoint, cityId: UUID?): Suffix? {
        val candidates = linkedSetOf<CitizenRouteIdentity>()
        var nearby = false
        var matchingEndpoint = false
        val x = floor(from.x).toInt()
        val z = floor(from.z).toInt()
        val y = citizenSupportY(from.y) shr 4
        sections@ for (cx in ((x - MAXIMUM_JOIN_DISTANCE) shr 4)..((x + MAXIMUM_JOIN_DISTANCE) shr 4))
            for (cz in ((z - MAXIMUM_JOIN_DISTANCE) shr 4)..((z + MAXIMUM_JOIN_DISTANCE) shr 4)) {
                for (key in routeSections[RouteSection(request.world, cx, y, cz)].orEmpty()) {
                    nearby = true
                    if (key.world != request.world || key.to != request.to || key.pickupContainer != request.pickupContainer) continue
                    matchingEndpoint = true
                    if (key.settings == request.settings &&
                        key.policy.geometry == request.policy.geometry &&
                        key.policy.allowedBuildings == request.policy.allowedBuildings) {
                        candidates += key
                        if (candidates.size == MAXIMUM_JOIN_CANDIDATES) break@sections
                    }
                }
            }
        if (candidates.size == MAXIMUM_JOIN_CANDIDATES)
            countSharedRoute(SharedRouteCounter.SUFFIX_CANDIDATE_LIMIT_REACHED, cityId)
        var best: Suffix? = null
        var score = Double.POSITIVE_INFINITY
        for (key in candidates) {
            val entry = sharedRoutes[key] ?: continue
            joinCandidates++
            for ((index, step) in entry.steps.withIndex()) {
                joinSteps++
                if (step.point.y != from.y) continue
                val squared = from.distanceSquared(step.point)
                if (squared > MAXIMUM_JOIN_DISTANCE.toDouble() * MAXIMUM_JOIN_DISTANCE) continue
                val estimated = kotlin.math.sqrt(squared) + entry.suffixLength[index]
                val previous = best
                if (previous == null || estimated < score ||
                    estimated == score && entry.steps.size - index < previous.cached.steps.size - previous.index) {
                    best = Suffix(key, entry, index)
                    score = estimated
                }
            }
        }
        if (best == null) countSharedRoute(when {
            !nearby -> SharedRouteCounter.MISS_NO_NEARBY_ENTRIES
            !matchingEndpoint -> SharedRouteCounter.MISS_NO_MATCHING_ENDPOINT
            candidates.isEmpty() -> SharedRouteCounter.MISS_NO_COMPATIBLE_ENTRY
            else -> SharedRouteCounter.MISS_NO_JOIN_POINT
        }, cityId)
        return best
    }

    /** Native accepted arrival follows continuous observed movement; replay still validates the whole geometry. */
    private fun rememberRoute(key: CitizenRouteIdentity, steps: List<CitizenPlannedStep>, walked: Set<CivPosition>, cityId: UUID?) {
        checkOwner()
        if (closed || !tasks.isCurrent(epoch) || steps.size !in 1..CivsPaths.MAX_TRIP_CELLS || walked.size < 2) return
        val ground = walked.toMutableSet()
        steps.forEach { ground += CivPosition(floor(it.point.x).toInt(), citizenSupportY(it.point.y), floor(it.point.z).toInt()) }
        if (ground.size > CivsPaths.MAX_TRIP_CELLS) return
        val sections = hashSetOf<RouteSection>()
        for (point in ground) for (x in (point.x - 1 shr 4)..(point.x + 1 shr 4))
            for (z in (point.z - 1 shr 4)..(point.z + 1 shr 4))
                for (y in (point.y - 2 shr 4)..(point.y + 4 shr 4)) sections += RouteSection(key.world, x, y, z)
        removeShared(key, SharedRouteCounter.REMOVED_REPLACEMENT)
        sharedRoutes[key] = CachedRoute(steps.toList(), ground.toSet(), sections, cityId)
        sections.forEach { routeSections.getOrPut(it) { hashSetOf() }.add(key) }
        countSharedRoute(SharedRouteCounter.LEARNED, cityId)
        while (sharedRoutes.size > MAXIMUM_SHARED_ROUTES)
            removeShared(sharedRoutes.keys.first(), SharedRouteCounter.REMOVED_CAPACITY)
    }

    private fun removeShared(key: CitizenRouteIdentity, reason: SharedRouteCounter) {
        val entry = sharedRoutes.remove(key) ?: return
        countSharedRoute(reason, entry.cityId)
        for (section in entry.sections) routeSections[section]?.let { keys ->
            keys.remove(key)
            if (keys.isEmpty()) routeSections.remove(section)
        }
    }

    private fun invalidateShared(world: UUID, point: CivPosition, reason: SharedRouteCounter) {
        val section = RouteSection(world, point.x shr 4, point.y shr 4, point.z shr 4)
        for (key in routeSections[section]?.toList().orEmpty()) {
            val entry = sharedRoutes[key] ?: continue
            if (entry.ground.any { ground -> kotlin.math.abs(point.x.toLong() - ground.x) <= 1 &&
                kotlin.math.abs(point.z.toLong() - ground.z) <= 1 && point.y.toLong() in ground.y.toLong() - 2..ground.y.toLong() + 4 }) {
                removeShared(key, reason)
            }
        }
    }

    /** Never waits. Native continuations and this pump spend the same authoritative tick budget. */
    fun pulse() {
        checkOwner()
        if (closed) return
        if (!tasks.isCurrent(epoch)) { clear(); return }
        pollDebug()
        retireCancelled()
        poll()
        work.drainAvailable { Bukkit.getCurrentTick().toLong() }
        poll()
    }

    private fun poll() {
        for (job in jobs.values.toList()) {
            if (!job.route.searching) continue
            val tick = Bukkit.getCurrentTick().toLong()
            if (job.route.snapshotPolicy != null && !snapshots.currentSections(job.route.worldId, job.snapshotRevisions)) {
                rejectedSnapshotSections++
                restartSnapshot(job)
                continue
            }
            job.snapshotPending?.let { pending ->
                if (!snapshots.pending(job.route.worldId, pending.chunks)) {
                    job.snapshotPending = null
                    captureSnapshotMain(job, requireNotNull(job.snapshotCapture), pending)
                }
            }
            val nativeProgress = job.capture?.progress ?: 0L
            if (nativeProgress > job.nativeProgress || job.searching?.isDone == true || job.capturing?.isDone == true) {
                job.progressTick = tick
                job.nativeProgress = nativeProgress
            }
            if (tick - job.progressTick >= job.route.preparationTickLimit()) {
                job.route.planningFailed("preparation-budget")
                continue
            }
            try {
                val reuse = job.reuse
                if (reuse != null) {
                    val validation = requireNotNull(job.capturing)
                    if (validation.isDone) {
                        job.capturing = null
                        job.reuse = null
                        validation.join()
                        val cityId = job.route.snapshotPolicy?.cityId
                        if (reuse.valid && reuse.endpointAvailable && sharedRoutes[reuse.source] === reuse.cached) {
                            val hit = if (reuse.splice) SharedRouteCounter.SUFFIX_HITS else SharedRouteCounter.EXACT_HITS
                            countSharedRoute(hit, cityId)
                            lastReplayCost = reuse.cost
                            val requestKey = reuse.request
                            val sourceKey = reuse.source
                            job.route.attachReuseObserver(
                                { steps, walked -> rememberRoute(requestKey, steps, walked, cityId) },
                                {
                                    removeShared(requestKey, SharedRouteCounter.REMOVED_REPLAY_OR_ROUTE_FAILURE)
                                    removeShared(sourceKey, SharedRouteCounter.REMOVED_REPLAY_OR_ROUTE_FAILURE)
                                })
                            job.route.acceptSearch(CitizenSearchResult(emptyList(), 0, path = reuse.steps))
                        } else {
                            // A bad C-to-join connector says nothing about the already validated A-to-B path.
                            if (reuse.endpointAvailable && (!reuse.splice || reuse.next > 0))
                                removeShared(reuse.source, SharedRouteCounter.REMOVED_REPLAY_OR_ROUTE_FAILURE)
                            job.route.pickupEndpoint = null
                            countSharedRoute(SharedRouteCounter.REJECTED, cityId)
                            dispatch(job, emptyList())
                        }
                    }
                    continue
                }
                val searching = job.searching
                if (searching != null && searching.isDone) {
                    job.progressTick = tick
                    job.searching = null
                    val result = searching.join() // isDone above; no blocking gameplay-thread wait.
                    job.route.recordSearch(result)
                    job.publishedNodes += (result.nodes - job.lastNodes).coerceAtLeast(0)
                    job.lastNodes = result.nodes
                    recordDebug(job, result)
                    if (result.pickupCandidate != null) {
                        val search = job.search
                        job.capturing = work.submit({ !closed && tasks.isCurrent(epoch) && job.route.searching && job.search === search }) {
                            job.pickupAnswer = job.route.confirmPickup(result.pickupCandidate)
                            true
                        }
                    } else if (result.probes.isEmpty()) {
                        finishDebug(job, result)
                        job.route.acceptSearch(result)
                    } else {
                        job.route.phase("capturing")
                        job.nativeProgress = 0
                        if (job.route.snapshotPolicy != null) {
                            val cursor = job.route.snapshotCapture(result.probes, job.snapshotTerrain)
                            job.snapshotCapture = cursor
                            job.capturing = CompletableFuture()
                            val seeded = job.route.seedSnapshots(snapshots, result.probes, !job.seededInitial)
                            job.seededInitial = true
                            captureSnapshot(job, cursor, seeded)
                        } else {
                            val cursor = CitizenNavigationCapture(job.route, result.probes, job.terrain, terrain)
                            job.capture = cursor
                            job.capturing = work.submit({ !closed && tasks.isCurrent(epoch) && job.route.searching }, cursor::step)
                        }
                    }
                }
                val capturing = job.capturing
                if (job.route.searching && capturing != null && capturing.isDone) {
                    job.capturing = null
                    capturing.join() // Completion/failure only, never an incomplete future.
                    val answer = job.pickupAnswer
                    val captures = if (answer != null) emptyList() else job.snapshotResult ?: requireNotNull(job.capture).result
                    job.capture = null
                    job.snapshotCapture = null
                    job.snapshotResult = null
                    job.pickupAnswer = null
                    dispatch(job, captures, answer)
                }
            } catch (failure: RuntimeException) {
                val reason = reason(failure)
                if (reason == "capture-budget") {
                    job.debugTrace?.let { trace ->
                        trace.budgetCall = reason
                        trace.cursor = job.snapshotCapture?.debugCursor()
                        if (trace.cursor == null) failDebug("unsupported-navigation-cursor")
                    }
                    job.capture = null
                    job.snapshotCapture = null
                    job.snapshotResult = null
                    job.capturing = null
                    job.nativeProgress = 0
                    job.progressTick = tick
                    job.route.phase("searching")
                    val search = job.search
                    job.searching = try { CompletableFuture.supplyAsync({ search.finishAtBudget(reason) }, executor) }
                    catch (_: RejectedExecutionException) {
                        CompletableFuture.failedFuture(RejectedExecutionException("Navigation worker is full"))
                    }
                } else job.route.planningFailed(reason)
            }
        }
        retireCancelled()
    }

    private fun dispatch(job: Job, captures: List<CitizenSearchCapture>, pickupAccepted: Boolean? = null) {
        job.debugTrace?.inputs = captures
        job.route.phase("searching")
        val search = job.search
        // The runnable captures these two detached values, not Job/route or the owner callbacks.
        job.searching = try { CompletableFuture.supplyAsync({ search.advance(captures, pickupAccepted) }, executor) }
        catch (_: RejectedExecutionException) { CompletableFuture.failedFuture(RejectedExecutionException("Navigation worker is full")) }
    }

    private fun captureSnapshot(job: Job, cursor: CitizenSnapshotCapture, input: CitizenSnapshotInput = CitizenSnapshotInput()) {
        job.route.phase("snapshot-processing")
        val search = job.search
        val future = try { CompletableFuture.supplyAsync({ cursor.advance(input, search::captured) }, executor) }
        catch (failure: RejectedExecutionException) {
            input.chunks.values.forEach(CitizenSnapshotChunk::releaseSource)
            CompletableFuture.failedFuture(failure)
        }
        job.snapshotWorker = future
        future.whenCompleteSync(tasks) { request, failure ->
            if (closed || !tasks.isCurrent(epoch) || !job.route.searching || job.snapshotCapture !== cursor) return@whenCompleteSync
            job.snapshotWorker = null
            job.progressTick = Bukkit.getCurrentTick().toLong()
            if (failure != null) { job.capturing?.completeExceptionally(failure); return@whenCompleteSync }
            val prepared = requireNotNull(request)
            snapshots.publish(job.route.worldId, prepared.decoded.values, job.route.snapshotPolicy?.cityId)
            job.publishedPayloads = prepared.payloads
            job.snapshotRevisions.putAll(prepared.sections)
            snapshots.touched(job.route.worldId, prepared.sections, job.route.snapshotPolicy?.cityId)
            if (!snapshots.currentSections(job.route.worldId, job.snapshotRevisions)) {
                rejectedSnapshotSections++
                restartSnapshot(job)
                return@whenCompleteSync
            }
            job.route.recordSnapshotCapture(prepared.capturedBlocks)
            job.publishedCells += (prepared.capturedBlocks - job.lastCells).coerceAtLeast(0)
            job.lastCells = prepared.capturedBlocks
            val captures = prepared.captures
            if (captures != null) {
                job.snapshotResult = captures
                job.capturing?.complete(Unit)
            } else captureSnapshotMain(job, cursor, prepared)
        }
    }

    private fun captureSnapshotMain(job: Job, cursor: CitizenSnapshotCapture, request: CitizenSnapshotRequest) {
        snapshotMainRequests++
        job.route.phase("snapshot-capturing")
        var input: CitizenSnapshotInput? = null
        job.snapshotNative = work.submit({ !closed && tasks.isCurrent(epoch) && job.route.searching && job.snapshotCapture === cursor }) {
            val before = snapshots.shapeReads
            input = job.route.captureSnapshots(request, snapshots)
            job.route.recordSnapshotNativeReads((snapshots.shapeReads - before).toInt())
            true
        }.also { native -> native.whenCompleteSync(tasks) { _, failure ->
            job.snapshotNative = null
            if (closed || !tasks.isCurrent(epoch) || !job.route.searching || job.snapshotCapture !== cursor) return@whenCompleteSync
            if (failure == null) captureSnapshot(job, cursor, requireNotNull(input))
            else {
                var cause: Throwable = failure
                while (cause is CompletionException && cause.cause != null) cause = requireNotNull(cause.cause)
                if (cause is CitizenSnapshotPending) {
                    job.snapshotPending = request
                    job.route.phase("snapshot-refresh")
                } else job.capturing?.completeExceptionally(cause)
            }
        } }
    }

    /** Never let an old worker graph reach movement after an observed native block change. */
    private fun restartSnapshot(job: Job) {
        if (debugRequest?.job === job) failDebug("navigation-snapshot-restarted")
        job.search.cancel()
        job.snapshotCapture?.cancel()
        job.snapshotNative?.cancel(false)
        job.searching?.cancel(false)
        job.capturing?.cancel(false)
        job.snapshotCapture = null
        job.snapshotWorker = null
        job.snapshotResult = null
        job.snapshotPending = null
        job.capturing = null
        job.pickupAnswer = null
        job.snapshotTerrain = CitizenSnapshotTerrain()
        job.seededInitial = false
        job.publishedPayloads = emptyList()
        job.snapshotRevisions.clear()
        job.search = job.route.newWorkerSearch()
        job.lastNodes = 0
        job.lastCells = 0
        job.progressTick = Bukkit.getCurrentTick().toLong()
        staleSnapshotRestarts++
        dispatch(job, emptyList())
    }

    private fun retireCancelled() {
        val iterator = jobs.entries.iterator()
        while (iterator.hasNext()) {
            val job = iterator.next().value
            if (job.route.searching) continue
            if (debugRequest?.job === job) failDebug("navigation-request-retired")
            // Include fast routes absent from the deliberately quiet slow-route log.
            val outcome = when {
                job.route.preparationPhase == "cancelled" -> { cancelledPreparations++; 2 }
                job.route.failure != null -> { failedPreparations++; 1 }
                else -> {
                    val elapsed = (nanoTime() - job.startedNanos).coerceAtLeast(0)
                    readyPreparations++
                    preparationNanos += elapsed
                    maximumPreparationNanos = maxOf(maximumPreparationNanos, elapsed)
                    preparationBuckets[preparationBoundsMillis.indexOfFirst { elapsed / 1_000_000.0 <= it }]++
                    0
                }
            }
            val work = preparationWork[outcome]
            work[0] += job.publishedNodes
            work[1] += if (job.route.snapshotPolicy == null) job.route.capturedBlocks.toLong() else job.publishedCells
            work[2] += job.route.searchNanos
            work[3] += job.route.nativeShapeReads
            job.search.cancel()
            job.snapshotCapture?.cancel()
                job.snapshotNative?.cancel(false)
            job.capturing?.cancel(false)
            job.searching?.cancel(false)
            job.capture = null
            // A route or permission failure says nothing about other routes' captured shapes.
            // Native change events, local blocked-step refresh and the bounded TTL own invalidation.
            if (job.route.failure == "no-path" || job.route.continuationReason == "no-path") {
                terrain.refreshObstacles(job.route.worldId, job.route.blockedPositions)
                snapshots.refreshObstacles(job.route.worldId, job.route.blockedPositions)
            }
            job.terrain.clear()
            iterator.remove()
        }
    }

    fun clear() {
        checkOwner()
        if (closed) return
        closed = true
        val debugDrain = debugRequest?.writing?.handle { _, _ -> Unit } ?: CompletableFuture.completedFuture(Unit)
        failDebug("navigation-capture-closed")
        jobs.keys.toList().forEach(CitizenPacketRoute::cancel)
        retireCancelled()
        terrain.clear()
        warmup.close()
        snapshots.clear()
        sharedRoutes.clear()
        routeSections.clear()
        sharedRouteCities.clear()
        unknownCityRoutes.fill(0L)
        if (ownsWork) work.close()
        val searchDrain = ownedExecutor?.closeAsync() ?: CompletableFuture.completedFuture(Unit)
        terminal = CompletableFuture.allOf(searchDrain, debugDrain).thenApply { Unit }
    }

    fun drained(): CompletableFuture<Unit> = terminal.copy()

    fun changed(world: UUID, point: CivPosition) {
        checkOwner()
        invalidateShared(world, point, SharedRouteCounter.REMOVED_NATIVE_CHANGE)
        terrain.changed(world, point)
        snapshots.changed(world, point)
    }
    fun removeChunk(world: UUID, x: Int, z: Int) {
        checkOwner()
        sharedRoutes.entries.filter { it.key.world == world && it.value.sections.any { s -> s.x == x && s.z == z } }
            .map { it.key }.forEach { removeShared(it, SharedRouteCounter.REMOVED_UNLOAD) }
        terrain.removeChunk(world, x, z); snapshots.removeChunk(world, x, z)
    }
    fun removeWorld(world: UUID) {
        checkOwner()
        sharedRoutes.keys.filter { it.world == world }.forEach { removeShared(it, SharedRouteCounter.REMOVED_UNLOAD) }
        terrain.removeWorld(world); snapshots.removeWorld(world)
    }

    private fun checkOwner() = check(Thread.currentThread() === owner) { "Resident navigation requires its owning thread" }

    private fun reason(failure: RuntimeException): String {
        var cause: Throwable = failure
        while (cause is CompletionException && cause.cause != null) cause = requireNotNull(cause.cause)
        return when (cause) {
            is CitizenNavigationCaptureLimit -> "capture-budget"
            is CitizenSnapshotUnavailable -> "chunk-unloaded"
            is CancellationException -> "cancelled"
            is RejectedExecutionException -> "planner-busy"
            else -> "planner-error"
        }
    }

    companion object {
        const val MAXIMUM_SHARED_ROUTES = 64
        private const val MAXIMUM_JOIN_CANDIDATES = 8
        private const val MAXIMUM_JOIN_DISTANCE = 3
        const val SEARCH_OPERATIONS_PER_TICK = 10_000
        const val SEARCH_BUDGET_NANOS = 4_000_000L
        const val MAXIMUM_JOBS = 48
    }
}

internal class CitizenNavigationCaptureLimit : RuntimeException()
internal class CitizenSnapshotUnavailable : RuntimeException()
internal class CitizenSnapshotPending : RuntimeException()

/**
 * One native block or policy callback per step. Hot shapes may be shared; permissions/costs
 * belong to this search. Movement always checks fresh native geometry.
 */
internal class CitizenNavigationCapture(
    private val route: CitizenPacketRoute,
    probes: List<CitizenSearchProbe>,
    private val cached: MutableMap<CivPosition, CitizenTerrainBlock>,
    private val shapes: CivsNavigationTerrain? = null,
) {
    private val probes = probes.toMutableList()
    private val requestedCount = probes.size
    private val seen = probes.mapTo(hashSetOf()) { it.node }
    // Nearby routes need just one neighbour layer. Long trips fill the configured connected
    // frontier so the worker can traverse several grid steps without returning for each one.
    private val prefetch = probes.none { it.incoming || it.node.initial }
    private val maximumProbes = route.captureProbeLimit()
    private val bounds = route.captureLimits()
    private val maximumBlocks = route.captureBlockLimit()
    private val completed = mutableListOf<CitizenSearchCapture>()
    val result: List<CitizenSearchCapture> get() = completed.toList()
    private var probeIndex = 0
    private var positions = emptyList<CivPosition>()
    private var positionIndex = 0
    private var grappleRows: IntRange? = null
    private var deferredGrapple = false
    private var blocks = emptyMap<CivPosition, CitizenTerrainBlock>()
    private var candidates: List<CitizenSearchEdge>? = null
    private var candidateIndex = 0
    private val admitted = mutableListOf<CitizenSearchEdge>()
    var progress = 0L
        private set

    fun step(): Boolean {
        progress++
        if (probeIndex >= probes.size) return true
        val probe = probes[probeIndex]
        if (positions.isEmpty()) {
            val n = probe.node
            val y = floor(n.y).toInt()
            // Full candidate body, tall shapes below its feet and +/-17/16 ascent/descent. Include adjacent columns
            // for an off-centre initial body, not just the centre-to-centre planned edge.
            positions = buildList {
                for (x in n.x - 1..n.x + 1) for (z in n.z - 1..n.z + 1) for (by in y - 3..y + 3)
                    add(CivPosition(x, by, z))
            }
        }
        while (positionIndex < positions.size) {
            val point = positions[positionIndex++]
            if (point in cached) continue
            if (cached.size >= maximumBlocks) {
                // A speculative neighbour must not discard already captured required work.
                // If the solver still needs more terrain, its next required probe reports the cap.
                if (probeIndex >= requestedCount) { probeIndex = probes.size; return true }
                throw CitizenNavigationCaptureLimit()
            }
            cached[point] = route.captureBlock(point, shapes)
            return false
        }
        if (candidates == null) {
            blocks = positions.associateWith { cached.getValue(it) }
            if (probe.specialTraversal) {
                val rows = citizenGrappleRows(probe, blocks) { point ->
                    blocks[point]?.cost?.isFinite() == true && route.snapshotPolicy?.traversalAllowed?.invoke(point) != false
                }
                if (rows != null && rows != grappleRows && (probeIndex < requestedCount || rows.last - rows.first < 10)) {
                    val n = probe.node
                    val original = floor(n.y).toInt()
                    val previous = grappleRows ?: (original - 3..original + 3)
                    grappleRows = rows
                    positions = positions + buildList {
                        for (x in n.x - 1..n.x + 1) for (z in n.z - 1..n.z + 1) for (y in rows)
                            if (y !in previous) add(CivPosition(x, y, z))
                    }
                    return false
                }
                deferredGrapple = rows != null && rows != grappleRows && probeIndex >= requestedCount
            }
            candidates = citizenSearchCandidates(probe, blocks, bounds.first, bounds.second)
        }
        val edges = requireNotNull(candidates)
        if (candidateIndex < edges.size) {
            val edge = edges[candidateIndex++]
            admitted += edge.copy(permitted = citizenSearchEdgePermitted(probe, edge.node.point(), edge.action, route::capturePermission))
            return false
        }
        completed += CitizenSearchCapture(probe, blocks, admitted.toList(), deferredGrapple)
        if (prefetch && !probe.refiningGrapple && (probeIndex < requestedCount || route.prefetchConnectedFrontier())) for (edge in admitted) {
            if (probes.size >= maximumProbes) break
            // Clip only speculative centres; complete edges remain reusable when the worker widens its search.
            if (edge.permitted && probe.corridor?.contains(edge.node.point()) != false &&
                probe.bounds?.contains(edge.node.point()) != false && seen.add(edge.node))
                probes += CitizenSearchProbe(edge.node, edge.node.point(), corridor = probe.corridor, bounds = probe.bounds,
                    specialTraversal = probe.specialTraversal)
        }
        admitted.clear()
        probeIndex++
        positions = emptyList()
        positionIndex = 0
        grappleRows = null
        deferredGrapple = false
        blocks = emptyMap()
        candidates = null
        candidateIndex = 0
        return probeIndex >= probes.size
    }

    companion object { const val MAXIMUM_BLOCKS = 32_768 }
}
