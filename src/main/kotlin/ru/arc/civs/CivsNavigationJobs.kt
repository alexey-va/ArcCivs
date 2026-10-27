package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.util.BoundingBox
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.UUID
import kotlin.math.floor

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
) {
    private class Job(val route: CitizenPacketRoute, val search: CitizenNavigationSearch) {
        var progressTick = Bukkit.getCurrentTick().toLong()
        var nativeProgress = 0L
        val terrain = hashMapOf<CivPosition, CitizenTerrainBlock>()
        var searching: CompletableFuture<CitizenSearchResult>? = null
        var capturing: CompletableFuture<Unit>? = null
        var capture: CitizenNavigationCapture? = null
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
    private var closed = false
    private var terminal = CompletableFuture.completedFuture(Unit)

    internal fun debug(): Map<String, Any?> {
        checkOwner()
        return mapOf("closed" to closed, "jobs" to jobs.size, "maximum_jobs" to settings.maximumJobs,
            "native_pending" to jobs.values.count { it.capturing?.isDone == false },
            "worker_pending" to jobs.values.count { it.searching?.isDone == false },
            "captured_blocks" to jobs.values.sumOf { it.terrain.size }, "terrain_cache" to terrain.debug())
    }

    fun request(world: World, from: Location, to: Location, allowed: (CivPosition) -> Boolean,
                stepAllowed: (CitizenPoint, CitizenPoint) -> Boolean,
                travelCost: (Block) -> Double): CitizenPacketRoute =
        admit(CitizenPacketRoute(world, from, to, allowed, stepAllowed, travelCost, settings = settings))

    fun replan(route: CitizenPacketRoute, from: Location, bodies: List<BoundingBox> = emptyList()): CitizenPacketRoute {
        checkOwner()
        from.world?.let { world ->
            terrain.changed(world.uid, CivPosition(from.blockX, from.blockY, from.blockZ))
            route.destination?.let { terrain.changed(world.uid, CivPosition(it.blockX, it.blockY, it.blockZ)) }
        }
        // Replacing an unfinished search releases its admission slot. A ready previous path
        // remains with the caller so a blocked-person detour can fall back to waiting on it.
        if (route.searching) route.cancel()
        return admit(route.replan(from, bodies))
    }

    private fun admit(route: CitizenPacketRoute): CitizenPacketRoute {
        checkOwner()
        retireCancelled()
        if (closed || !tasks.isCurrent(epoch)) { route.planningFailed("cancelled"); return route }
        if (jobs.size >= settings.maximumJobs) { route.planningFailed("planner-busy"); return route }
        val job = Job(route, route.newSearch())
        jobs[route] = job
        route.attachAsync(job.search::cancel)
        dispatch(job, emptyList())
        return route
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
                        val cursor = CitizenNavigationCapture(job.route, result.probes, job.terrain, terrain)
                        job.capture = cursor
                        job.nativeProgress = 0
                        job.capturing = work.submit({ !closed && tasks.isCurrent(epoch) && job.route.searching }, cursor::step)
                    }
                }
                val capturing = job.capturing
                if (job.route.searching && capturing != null && capturing.isDone) {
                    job.capturing = null
                    capturing.join() // Completion/failure only, never an incomplete future.
                    val capture = requireNotNull(job.capture)
                    job.capture = null
                    dispatch(job, capture.result)
                }
            } catch (failure: RuntimeException) {
                val reason = reason(failure)
                if (reason == "capture-budget") {
                    job.capture = null
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

    private fun retireCancelled() {
        val iterator = jobs.entries.iterator()
        while (iterator.hasNext()) {
            val job = iterator.next().value
            if (job.route.searching) continue
            job.search.cancel()
            job.capturing?.cancel(false)
            job.searching?.cancel(false)
            job.capture = null
            // A route or permission failure says nothing about other routes' captured shapes.
            // Native change events, local blocked-step refresh and the bounded TTL own invalidation.
            if (job.route.failure == "no-path") terrain.refreshObstacles(job.route.worldId, job.route.blockedPositions)
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
        if (ownsWork) work.close()
        terminal = ownedExecutor?.closeAsync() ?: CompletableFuture.completedFuture(Unit)
    }

    fun drained(): CompletableFuture<Unit> = terminal.copy()

    fun changed(world: UUID, point: CivPosition) { checkOwner(); terrain.changed(world, point) }
    fun removeChunk(world: UUID, x: Int, z: Int) { checkOwner(); terrain.removeChunk(world, x, z) }
    fun removeWorld(world: UUID) { checkOwner(); terrain.removeWorld(world) }

    private fun checkOwner() = check(Thread.currentThread() === owner) { "Resident navigation requires its owning thread" }

    private fun reason(failure: RuntimeException): String {
        var cause: Throwable = failure
        while (cause is CompletionException && cause.cause != null) cause = requireNotNull(cause.cause)
        return when (cause) {
            is CitizenNavigationCaptureLimit -> "capture-budget"
            is CancellationException -> "cancelled"
            is RejectedExecutionException -> "planner-busy"
            else -> "planner-error"
        }
    }

    companion object {
        const val SEARCH_OPERATIONS_PER_TICK = 10_000
        const val SEARCH_BUDGET_NANOS = 4_000_000L
        const val MAXIMUM_JOBS = 48
    }
}

internal class CitizenNavigationCaptureLimit : RuntimeException()

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
            if (edge.permitted && seen.add(edge.node)) probes += CitizenSearchProbe(edge.node, edge.node.point())
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
