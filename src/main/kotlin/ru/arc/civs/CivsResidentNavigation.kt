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
    val from: CitizenSearchNode, val to: CitizenPoint, val settings: CivsNavigationSettings) {
    private val stableHash = 31 * (31 * (31 * (31 * world.hashCode() + policy.hashCode()) +
        from.hashCode()) + to.hashCode()) + settings.hashCode()
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
        var capture: CitizenNavigationCapture? = null
        var snapshotTerrain = CitizenSnapshotTerrain()
        var snapshotCapture: CitizenSnapshotCapture? = null
        var snapshotWorker: CompletableFuture<CitizenSnapshotRequest>? = null
        var snapshotResult: List<CitizenSearchCapture>? = null
        var snapshotPending: CitizenSnapshotRequest? = null
        val snapshotRevisions = hashMapOf<CitizenSnapshotSectionKey, Long>()
        var reuse: ReuseAttempt? = null
    }
    private data class RouteSection(val world: UUID, val x: Int, val y: Int, val z: Int)
    private class CachedRoute(val steps: List<CitizenPlannedStep>, val ground: Set<CivPosition>,
        val sections: Set<RouteSection>) {
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
        var cost = 0.0
    }
    private data class Suffix(val source: CitizenRouteIdentity, val cached: CachedRoute, val index: Int)
    private val owner = Thread.currentThread()
    private val epoch = tasks.token()
    private val ownsWork = nativeWork == null
    private val work = nativeWork ?: BudgetedTaskQueue(tasks, settings.nativeOperationsPerTick,
        (settings.nativeBudgetMillis * 1_000_000.0).toLong(), System::nanoTime, settings.maximumJobs)
    private val ownedExecutor = if (executor == null) BoundedAsyncExecutor("ArcCivs-navigation", 1, settings.maximumJobs) else null
    private val executor = executor ?: requireNotNull(ownedExecutor)
    private val jobs = linkedMapOf<CitizenPacketRoute, Job>()
    private val terrain = CivsNavigationTerrain(settings.terrainCacheBlocks, settings.terrainRefreshTicks)
    private val snapshots = snapshotCache ?: CivsNavigationSnapshots(settings.terrainCacheBlocks, settings.terrainRefreshTicks, tasks, snapshotContextFree)
    private val sharedRoutes = LinkedHashMap<CitizenRouteIdentity, CachedRoute>(64, .75f, true)
    private val routeSections = hashMapOf<RouteSection, MutableSet<CitizenRouteIdentity>>()
    private var reusedRoutes = 0L
    private var reuseMisses = 0L
    private var learnedRoutes = 0L
    private var rejectedReuse = 0L
    private var invalidatedRoutes = 0L
    private var replayChecks = 0L
    private var rejectedSnapshotSections = 0L
    private var suffixHits = 0L
    private var joinCandidates = 0L
    private var joinSteps = 0L
    private var rejectedConnectors = 0L
    private var lastReplayCost = 0.0
    private var closed = false
    private var staleSnapshotRestarts = 0L
    private var readyPreparations = 0L
    private var failedPreparations = 0L
    private var cancelledPreparations = 0L
    private var preparationNanos = 0L
    private var maximumPreparationNanos = 0L
    private val preparationBoundsMillis = longArrayOf(100, 250, 500, 1_000, 2_000, 5_000, 10_000, Long.MAX_VALUE)
    private val preparationBuckets = LongArray(preparationBoundsMillis.size)
    private var terminal = CompletableFuture.completedFuture(Unit)

    internal fun debug(): Map<String, Any?> {
        checkOwner()
        return mapOf("closed" to closed, "jobs" to jobs.size, "maximum_jobs" to settings.maximumJobs,
            "native_pending" to jobs.values.count { it.capturing?.isDone == false && it.snapshotCapture == null },
            "worker_pending" to jobs.values.count { it.searching?.isDone == false || it.snapshotWorker?.isDone == false },
            "bulk_jobs" to jobs.values.count { it.route.snapshotPolicy != null },
            "captured_blocks" to jobs.values.sumOf { it.route.capturedBlocks }, "terrain_cache" to terrain.debug(),
            "snapshot_cache" to snapshots.debug(), "stale_snapshot_restarts" to staleSnapshotRestarts,
            "used_snapshot_sections" to jobs.values.sumOf { it.snapshotRevisions.size },
            "section_snapshot_rejects" to rejectedSnapshotSections,
            "shared_routes" to mapOf("entries" to sharedRoutes.size, "maximum_entries" to MAXIMUM_SHARED_ROUTES,
                "maximum_steps" to CivsPaths.MAX_TRIP_CELLS, "learned" to learnedRoutes,
                "hits" to reusedRoutes, "misses" to reuseMisses, "rejected" to rejectedReuse,
                "invalidated" to invalidatedRoutes, "native_step_checks" to replayChecks,
                "last_validated_cost" to lastReplayCost),
            "shared_route_suffixes" to mapOf("hits" to suffixHits, "candidates" to joinCandidates,
                "steps_examined" to joinSteps, "rejected_connectors" to rejectedConnectors,
                "maximum_candidates" to MAXIMUM_JOIN_CANDIDATES, "maximum_join_distance" to MAXIMUM_JOIN_DISTANCE),
            "preparation_lifetime" to mapOf("source" to "all-admitted-planner-jobs", "ready" to readyPreparations,
                "failed" to failedPreparations, "cancelled" to cancelledPreparations,
                "mean_ms" to if (readyPreparations == 0L) 0.0 else preparationNanos / readyPreparations / 1_000_000.0,
                "max_ms" to maximumPreparationNanos / 1_000_000.0,
                "buckets_ms" to preparationBuckets.indices.associate { index ->
                    (if (index == preparationBuckets.lastIndex) ">10000" else "<=${preparationBoundsMillis[index]}") to preparationBuckets[index]
                }))
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
                corridor: CitizenSearchCorridor?, snapshotPolicy: CitizenSnapshotPolicy): CitizenPacketRoute =
        admit(CitizenPacketRoute(world, from, to, allowed, stepAllowed, travelCost, settings = settings,
            corridor = corridor, snapshotPolicy = snapshotPolicy))

    fun replan(route: CitizenPacketRoute, from: Location, bodies: List<BoundingBox> = emptyList(),
        refreshSnapshots: Boolean = bodies.isEmpty(), destination: Location? = null): CitizenPacketRoute {
        checkOwner()
        from.world?.let { world ->
            val points = listOf(CivPosition(from.blockX, from.blockY, from.blockZ)) +
                listOfNotNull(route.destination?.let { CivPosition(it.blockX, it.blockY, it.blockZ) },
                    destination?.let { CivPosition(it.blockX, it.blockY, it.blockZ) })
            points.forEach { terrain.changed(world.uid, it) }
            // Resident bodies belong to this search, not to the shared native chunk generation.
            if (refreshSnapshots) points.forEach { snapshots.changed(world.uid, it); invalidateShared(world.uid, it) }
        }
        // Replacing an unfinished search releases its admission slot. A ready previous path
        // remains with the caller so a blocked-person detour can fall back to waiting on it.
        if (route.searching) route.cancel()
        return admit(route.replan(from, bodies, destination))
    }

    private fun admit(route: CitizenPacketRoute): CitizenPacketRoute {
        checkOwner()
        retireCancelled()
        if (closed || !tasks.isCurrent(epoch)) { route.planningFailed("cancelled"); return route }
        if (jobs.size >= settings.maximumJobs) { route.planningFailed("planner-busy"); return route }
        val job = Job(route, route.newSearch(), nanoTime())
        jobs[route] = job
        route.attachAsync { job.search.cancel() }
        val key = route.reuseIdentity()
        if (key != null) {
            route.attachReuseObserver({ steps, walked -> rememberRoute(key, steps, walked) }, { removeShared(key) })
            val cached = sharedRoutes[key]
            val suffix = if (cached == null) findSuffix(key, route.reuseOrigin) else null
            if (cached != null || suffix != null) {
                val source = suffix?.source ?: key
                val entry = cached ?: requireNotNull(suffix).cached
                val steps = if (suffix == null) entry.steps else
                    listOf(CitizenPlannedStep(entry.steps[suffix.index].point, CitizenRouteAction.WALK)) +
                        entry.steps.drop(suffix.index + 1)
                val attempt = ReuseAttempt(source, key, entry, steps, suffix != null, route.reuseOrigin)
                job.reuse = attempt
                route.phase("shared-route-validation")
                job.capturing = work.submit({ !closed && tasks.isCurrent(epoch) && route.searching }) {
                    val step = attempt.steps[attempt.next]
                    replayChecks++
                    job.progressTick = Bukkit.getCurrentTick().toLong()
                    if (sharedRoutes[source] !== entry) {
                        attempt.valid = false
                        true
                    } else {
                        val cost = route.validatedCachedStepCost(attempt.previous, step)
                        if (!cost.isFinite()) {
                            attempt.valid = false
                            if (attempt.splice && attempt.next == 0) rejectedConnectors++
                            refreshFailedReplay(route, attempt.previous, step.point)
                            true
                        } else {
                            attempt.cost += cost
                            attempt.previous = step.point
                            ++attempt.next == attempt.steps.size
                        }
                    }
                }
                return route
            }
            reuseMisses++
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
    private fun findSuffix(request: CitizenRouteIdentity, from: CitizenPoint): Suffix? {
        val candidates = linkedSetOf<CitizenRouteIdentity>()
        val x = floor(from.x).toInt()
        val z = floor(from.z).toInt()
        val y = citizenSupportY(from.y) shr 4
        sections@ for (cx in ((x - MAXIMUM_JOIN_DISTANCE) shr 4)..((x + MAXIMUM_JOIN_DISTANCE) shr 4))
            for (cz in ((z - MAXIMUM_JOIN_DISTANCE) shr 4)..((z + MAXIMUM_JOIN_DISTANCE) shr 4)) {
                for (key in routeSections[RouteSection(request.world, cx, y, cz)].orEmpty()) {
                    if (key.world == request.world && key.to == request.to && key.settings == request.settings &&
                        key.policy.geometry == request.policy.geometry &&
                        key.policy.allowedBuildings == request.policy.allowedBuildings) {
                        candidates += key
                        if (candidates.size == MAXIMUM_JOIN_CANDIDATES) break@sections
                    }
                }
            }
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
        return best
    }

    /** Native accepted arrival follows continuous observed movement; replay still validates the whole geometry. */
    private fun rememberRoute(key: CitizenRouteIdentity, steps: List<CitizenPlannedStep>, walked: Set<CivPosition>) {
        checkOwner()
        if (closed || !tasks.isCurrent(epoch) || steps.size !in 1..CivsPaths.MAX_TRIP_CELLS || walked.size < 2) return
        val ground = walked.toMutableSet()
        steps.forEach { ground += CivPosition(floor(it.point.x).toInt(), citizenSupportY(it.point.y), floor(it.point.z).toInt()) }
        if (ground.size > CivsPaths.MAX_TRIP_CELLS) return
        val sections = hashSetOf<RouteSection>()
        for (point in ground) for (x in (point.x - 1 shr 4)..(point.x + 1 shr 4))
            for (z in (point.z - 1 shr 4)..(point.z + 1 shr 4))
                for (y in (point.y - 2 shr 4)..(point.y + 4 shr 4)) sections += RouteSection(key.world, x, y, z)
        removeShared(key)
        sharedRoutes[key] = CachedRoute(steps.toList(), ground.toSet(), sections)
        sections.forEach { routeSections.getOrPut(it) { hashSetOf() }.add(key) }
        learnedRoutes++
        while (sharedRoutes.size > MAXIMUM_SHARED_ROUTES) removeShared(sharedRoutes.keys.first())
    }

    private fun removeShared(key: CitizenRouteIdentity) {
        val entry = sharedRoutes.remove(key) ?: return
        for (section in entry.sections) routeSections[section]?.let { keys ->
            keys.remove(key)
            if (keys.isEmpty()) routeSections.remove(section)
        }
    }

    private fun invalidateShared(world: UUID, point: CivPosition) {
        val section = RouteSection(world, point.x shr 4, point.y shr 4, point.z shr 4)
        for (key in routeSections[section]?.toList().orEmpty()) {
            val entry = sharedRoutes[key] ?: continue
            if (entry.ground.any { ground -> kotlin.math.abs(point.x.toLong() - ground.x) <= 1 &&
                kotlin.math.abs(point.z.toLong() - ground.z) <= 1 && point.y.toLong() in ground.y.toLong() - 2..ground.y.toLong() + 4 }) {
                removeShared(key)
                invalidatedRoutes++
            }
        }
    }

    /** Never waits. Native continuations and this pump spend the same authoritative tick budget. */
    fun pulse() {
        checkOwner()
        if (closed) return
        if (!tasks.isCurrent(epoch)) { clear(); return }
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
                        if (reuse.valid && sharedRoutes[reuse.source] === reuse.cached) {
                            reusedRoutes++
                            lastReplayCost = reuse.cost
                            if (reuse.splice) suffixHits++
                            val requestKey = reuse.request
                            val sourceKey = reuse.source
                            job.route.attachReuseObserver(
                                { steps, walked -> rememberRoute(requestKey, steps, walked) },
                                { removeShared(requestKey); removeShared(sourceKey) })
                            job.route.acceptSearch(CitizenSearchResult(emptyList(), 0, path = reuse.steps))
                        } else {
                            // A bad C-to-join connector says nothing about the already validated A-to-B path.
                            if (!reuse.splice || reuse.next > 0) removeShared(reuse.source)
                            rejectedReuse++
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
                    if (result.probes.isEmpty()) {
                        job.route.acceptSearch(result)
                    } else {
                        job.route.phase("capturing")
                        job.nativeProgress = 0
                        if (job.route.snapshotPolicy != null) {
                            val cursor = job.route.snapshotCapture(result.probes, job.snapshotTerrain)
                            job.snapshotCapture = cursor
                            job.capturing = CompletableFuture()
                            captureSnapshot(job, cursor)
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
                    val captures = job.snapshotResult ?: requireNotNull(job.capture).result
                    job.capture = null
                    job.snapshotCapture = null
                    job.snapshotResult = null
                    dispatch(job, captures)
                }
            } catch (failure: RuntimeException) {
                val reason = reason(failure)
                if (reason == "capture-budget") {
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

    private fun dispatch(job: Job, captures: List<CitizenSearchCapture>) {
        job.route.phase("searching")
        val search = job.search
        // The runnable captures these two detached values, not Job/route or the owner callbacks.
        job.searching = try { CompletableFuture.supplyAsync({ search.advance(captures) }, executor) }
        catch (_: RejectedExecutionException) { CompletableFuture.failedFuture(RejectedExecutionException("Navigation worker is full")) }
    }

    private fun captureSnapshot(job: Job, cursor: CitizenSnapshotCapture, input: CitizenSnapshotInput = CitizenSnapshotInput()) {
        job.route.phase("snapshot-processing")
        val future = try { CompletableFuture.supplyAsync({ cursor.advance(input) }, executor) }
        catch (failure: RejectedExecutionException) { CompletableFuture.failedFuture(failure) }
        job.snapshotWorker = future
        future.whenCompleteSync(tasks) { request, failure ->
            if (closed || !tasks.isCurrent(epoch) || !job.route.searching || job.snapshotCapture !== cursor) return@whenCompleteSync
            job.snapshotWorker = null
            job.progressTick = Bukkit.getCurrentTick().toLong()
            if (failure != null) { job.capturing?.completeExceptionally(failure); return@whenCompleteSync }
            val prepared = requireNotNull(request)
            job.snapshotRevisions.putAll(prepared.sections)
            if (!snapshots.currentSections(job.route.worldId, job.snapshotRevisions)) {
                rejectedSnapshotSections++
                restartSnapshot(job)
                return@whenCompleteSync
            }
            job.route.recordSnapshotCapture(prepared.capturedBlocks)
            val captures = prepared.captures
            if (captures != null) {
                job.snapshotResult = captures
                job.capturing?.complete(Unit)
            } else captureSnapshotMain(job, cursor, prepared)
        }
    }

    private fun captureSnapshotMain(job: Job, cursor: CitizenSnapshotCapture, request: CitizenSnapshotRequest) {
        runCatching {
            job.route.phase("snapshot-capturing")
            val before = snapshots.shapeReads
            val captured = job.route.captureSnapshots(request, snapshots)
            job.route.recordSnapshotNativeReads((snapshots.shapeReads - before).toInt())
            captureSnapshot(job, cursor, captured)
        }.onFailure { failure ->
            if (failure is CitizenSnapshotPending) {
                job.snapshotPending = request
                job.route.phase("snapshot-refresh")
            } else job.capturing?.completeExceptionally(failure)
        }
    }

    /** Never let an old worker graph reach movement after an observed native block change. */
    private fun restartSnapshot(job: Job) {
        job.search.cancel()
        job.snapshotCapture?.cancel()
        job.snapshotWorker?.cancel(false)
        job.searching?.cancel(false)
        job.capturing?.cancel(false)
        job.snapshotCapture = null
        job.snapshotWorker = null
        job.snapshotResult = null
        job.snapshotPending = null
        job.capturing = null
        job.snapshotTerrain = CitizenSnapshotTerrain()
        job.snapshotRevisions.clear()
        job.search = job.route.newSearch()
        job.progressTick = Bukkit.getCurrentTick().toLong()
        staleSnapshotRestarts++
        dispatch(job, emptyList())
    }

    private fun retireCancelled() {
        val iterator = jobs.entries.iterator()
        while (iterator.hasNext()) {
            val job = iterator.next().value
            if (job.route.searching) continue
            // Include fast routes absent from the deliberately quiet slow-route log.
            when {
                job.route.preparationPhase == "cancelled" -> cancelledPreparations++
                job.route.failure != null -> failedPreparations++
                else -> {
                    val elapsed = (nanoTime() - job.startedNanos).coerceAtLeast(0)
                    readyPreparations++
                    preparationNanos += elapsed
                    maximumPreparationNanos = maxOf(maximumPreparationNanos, elapsed)
                    preparationBuckets[preparationBoundsMillis.indexOfFirst { elapsed / 1_000_000.0 <= it }]++
                }
            }
            job.search.cancel()
            job.snapshotCapture?.cancel()
            job.snapshotWorker?.cancel(false)
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
        jobs.keys.toList().forEach(CitizenPacketRoute::cancel)
        retireCancelled()
        terrain.clear()
        snapshots.clear()
        sharedRoutes.clear()
        routeSections.clear()
        if (ownsWork) work.close()
        terminal = ownedExecutor?.closeAsync() ?: CompletableFuture.completedFuture(Unit)
    }

    fun drained(): CompletableFuture<Unit> = terminal.copy()

    fun changed(world: UUID, point: CivPosition) {
        checkOwner(); invalidateShared(world, point); terrain.changed(world, point); snapshots.changed(world, point)
    }
    fun removeChunk(world: UUID, x: Int, z: Int) {
        checkOwner()
        sharedRoutes.entries.filter { it.key.world == world && it.value.sections.any { s -> s.x == x && s.z == z } }
            .map { it.key }.forEach(::removeShared)
        terrain.removeChunk(world, x, z); snapshots.removeChunk(world, x, z)
    }
    fun removeWorld(world: UUID) {
        checkOwner(); sharedRoutes.keys.filter { it.world == world }.forEach(::removeShared)
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
            candidates = citizenSearchCandidates(probe, blocks, bounds.first, bounds.second)
        }
        val edges = requireNotNull(candidates)
        if (candidateIndex < edges.size) {
            val edge = edges[candidateIndex++]
            admitted += edge.copy(permitted = if (probe.incoming) route.capturePermission(edge.node.point(), probe.from)
                else route.capturePermission(probe.from, edge.node.point()))
            return false
        }
        completed += CitizenSearchCapture(probe, blocks, admitted.toList())
        if (prefetch && (probeIndex < requestedCount || route.prefetchConnectedFrontier())) for (edge in admitted) {
            if (probes.size >= maximumProbes) break
            // Clip only speculative centres; complete edges remain reusable when the worker widens its search.
            if (edge.permitted && probe.corridor?.contains(edge.node.point()) != false &&
                probe.bounds?.contains(edge.node.point()) != false && seen.add(edge.node))
                probes += CitizenSearchProbe(edge.node, edge.node.point(), corridor = probe.corridor, bounds = probe.bounds)
        }
        admitted.clear()
        probeIndex++
        positions = emptyList()
        positionIndex = 0
        blocks = emptyMap()
        candidates = null
        candidateIndex = 0
        return probeIndex >= probes.size
    }

    companion object { const val MAXIMUM_BLOCKS = 32_768 }
}
