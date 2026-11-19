package ru.arc.civs

import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Detached values only. Bukkit's Vector/BoundingBox below are local arithmetic, never native state. */
internal data class CitizenTerrainBox(
    val minX: Double, val minY: Double, val minZ: Double,
    val maxX: Double, val maxY: Double, val maxZ: Double,
) {
    fun at(point: CivPosition) = at(point.x, point.y, point.z)
    fun at(x: Int, y: Int, z: Int) = BoundingBox(minX + x, minY + y, minZ + z,
        maxX + x, maxY + y, maxZ + z)
}

/** Planning boxes omit the leaf of a closed passage. Open leaves retain their native collision. */
internal data class CitizenTerrainBlock(
    val loaded: Boolean, val liquid: Boolean, val door: Boolean, val climbable: Boolean,
    val hot: Boolean, val allowed: Boolean, val cost: Double, val boxes: List<CitizenTerrainBox>,
) {
    companion object {
        val UNAVAILABLE = CitizenTerrainBlock(false, false, false, false, false, false,
            Double.POSITIVE_INFINITY, emptyList())
    }
}

internal enum class CitizenRouteAction {
    WALK, STEP, LADDER_ENTER, CLIMB, LADDER_EXIT, GRAPPLE, CRAWL;

    val isGroundStep: Boolean get() = this == WALK || this == STEP
    val isSpecialTraversal: Boolean get() = this == CRAWL || this == GRAPPLE
    val alignsToLadder: Boolean get() = this == LADDER_ENTER || this == CLIMB
}

internal const val CITIZEN_CRAWL_HEIGHT = .6
internal const val CITIZEN_CRAWL_STRIDE = .065
internal const val CITIZEN_GRAPPLE_STRIDE = .035
internal const val CITIZEN_GRAPPLE_CAST_TICKS = 12
internal const val CITIZEN_GRAPPLE_MAX_RISE = 10
// Native working travel spends .215 × .8 blocks per tick before the surface multiplier.
private const val CITIZEN_WORK_WALK_STRIDE = .215 * .8

/** A broad solid face reaches the exact near edge of the cap and supports the centre of the landing. */
internal fun citizenHookFace(minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double,
                             dx: Int, dz: Int): Boolean =
    minY <= .001 && maxY >= .5 && minX <= .5 && maxX >= .5 && minZ <= .5 && maxZ >= .5 &&
        when {
            dx != 0 && dz == 0 -> minZ <= .3 && maxZ >= .7 && (if (dx > 0) minX <= .001 else maxX >= .999)
            dz != 0 && dx == 0 -> minX <= .3 && maxX >= .7 && (if (dz > 0) minZ <= .001 else maxZ >= .999)
            else -> false
        }

internal fun citizenGrappleAnchor(low: CitizenPoint, high: CitizenPoint) = CitizenPoint(
    (low.x + high.x) / 2, high.y - .12, (low.z + high.z) / 2)
internal data class CitizenSearchNode(val x: Int, val y: Double, val z: Int,
    val climbing: Boolean = false, val initial: Boolean = false) {
    @Transient private var position: CitizenPoint? = null
    fun point() = position ?: CitizenPoint(x + .5, y, z + .5).also { position = it }
}
internal class CitizenSearchBounds private constructor(private val minimum: CitizenPoint, private val maximum: CitizenPoint) {
    fun containsColumn(point: CitizenPoint): Boolean = point.x in minimum.x..maximum.x && point.z in minimum.z..maximum.z
    fun contains(point: CitizenPoint): Boolean = containsColumn(point) && point.y in minimum.y..maximum.y

    companion object {
        fun between(from: CitizenPoint, to: CitizenPoint, horizontalMargin: Int, verticalMargin: Int) = CitizenSearchBounds(
            CitizenPoint(min(from.x, to.x) - horizontalMargin, min(from.y, to.y) - verticalMargin, min(from.z, to.z) - horizontalMargin),
            CitizenPoint(max(from.x, to.x) + horizontalMargin, max(from.y, to.y) + verticalMargin, max(from.z, to.z) + horizontalMargin))
    }
}
internal data class CitizenSearchProbe(val node: CitizenSearchNode, val from: CitizenPoint, val incoming: Boolean = false,
    val corridor: CitizenSearchCorridor? = null, val bounds: CitizenSearchBounds? = null,
    val specialTraversal: Boolean = false, val refiningGrapple: Boolean = false)
internal data class CitizenSearchEdge(val node: CitizenSearchNode, val action: CitizenRouteAction, val permitted: Boolean)
internal data class CitizenPlannedStep(val point: CitizenPoint, val action: CitizenRouteAction)
internal data class CitizenSearchCapture(val probe: CitizenSearchProbe,
    val blocks: Map<CivPosition, CitizenTerrainBlock>, val edges: List<CitizenSearchEdge>, val deferredGrapple: Boolean = false)
internal data class CitizenSearchResult(val probes: List<CitizenSearchProbe>, val nodes: Int,
    val path: List<CitizenPlannedStep>? = null, val failure: String? = null, val searchNanos: Long = 0,
    val continuation: Boolean = false, val blockedPositions: List<CivPosition> = emptyList(),
    val continuationReason: String? = null, val roadRouting: CitizenRoadRouting? = null,
    val pickupCandidate: CitizenPoint? = null, val searchStop: String? = null,
    val firstUnavailableBlock: CivPosition? = null)

/**
 * One worker owns this A* state. A slice asks for a configured batch of small neighbourhoods; native capture
 * and arbitrary route policies are supplied later by the owner. First search the start/goal
 * corridor, retaining its boundary for a wider detour when needed. Longer trips use a weighted heuristic to favour reaching
 * a useful route within a finite node budget over cost optimality.
 * Pending edges are settled before accepting a goal, including every already-requested branch.
 */
internal class CitizenNavigationSearch(
    private val origin: CitizenPoint,
    private val target: CitizenPoint,
    private val minimumY: Int,
    private val maximumY: Int,
    private val avoiding: List<CitizenTerrainBox>,
    private val settings: CivsNavigationSettings = CivsNavigationSettings(),
    private val preferredCorridor: CitizenSearchCorridor? = null,
    roadCorridor: CitizenRoadLookup? = null,
    private val pickupContainer: CivPosition? = null,
    private val specialTraversal: Boolean = false,
    private val traversalAllowed: (CivPosition) -> Boolean = { true },
) {
    private data class Entry(val node: CitizenSearchNode, val cost: Double, val score: Double, val order: Int,
                             val special: Boolean = false)
    private data class Parent(val node: CitizenSearchNode, val action: CitizenRouteAction)
    private val initialBounds = CitizenSearchBounds.between(origin, target, settings.initialHorizontalMargin, settings.initialVerticalMargin)
    private val start = CitizenSearchNode(floor(origin.x).toInt(), origin.y, floor(origin.z).toInt(), initial = true)
    // Local trips must still choose a modest road detour over learned grass. Keep weight 1
    // through 32 blocks, then ramp to 2 by 64: distant ordinary-cost terrain otherwise exhausts
    // the node allowance. The weight stays fixed for the job, so queued scores stay valid.
    private val heuristicWeight = 1.0 + (MAXIMUM_HEURISTIC_WEIGHT - 1.0) *
        ((sqrt(origin.distanceSquared(target)) - LOCAL_ROUTE_DISTANCE) /
            (WEIGHTED_ROUTE_DISTANCE - LOCAL_ROUTE_DISTANCE)).coerceIn(0.0, 1.0)
    private val gateRequests = origin.distanceSquared(target) > settings.closeRouteDistance * settings.closeRouteDistance
    private val open = PriorityQueue<Entry>(compareBy<Entry> { it.score }.thenBy { it.order })
    private val deferred = mutableListOf<Entry>()
    private val costs = hashMapOf(start to 0.0)
    private val parents = hashMapOf<CitizenSearchNode, Parent>()
    private val proneOnly = hashSetOf<CitizenSearchNode>()
    private var closestDistanceSquared = origin.distanceSquared(target)
    private val neighbourhoods = hashMapOf<CitizenSearchNode, CitizenSearchCapture>()
    private val geometry = CitizenSearchGeometry(minimumY, maximumY, avoiding)
    private var pending = emptyMap<CitizenSearchNode, Entry>()
    private var goal: Entry? = null
    private var checkingPickup: Entry? = null
    private var originInspected = !specialTraversal
    private val checkedPickupFeet = pickupContainer?.let { hashSetOf<CitizenPoint>() }
    private var serial = 0
    private var visited = 0
    private var firstUnavailableBlock: CivPosition? = null
    private val unknownTerrain get() = firstUnavailableBlock != null
    private var widerSearch = false
    private var roadLookup = roadCorridor.takeIf { preferredCorridor == null }
    private var corridor = preferredCorridor
    private var roadRouting: CitizenRoadRouting? = null
    // Reserve room for an ordinary detour under smaller configured limits too (3 x 3 x 7 cells per probe).
    private val roadNodeLimit = minOf(256, settings.maximumNodes / 2, settings.maximumCapturedBlocks / (2 * 63))
    private var clipped = false
    private var isolatedGoalDistance: Double? = null
    private var inspectEndpoint = pickupContainer == null && origin.distanceSquared(target) <= settings.closeRouteDistance * settings.closeRouteDistance &&
        (abs(start.x - floor(target.x).toInt()) > 1 || abs(start.z - floor(target.z).toInt()) > 1) &&
        target.x == floor(target.x) + .5 && target.z == floor(target.z) + .5 && target.y == floor(target.y)
    private val cancelled = AtomicBoolean()
    private val blockedPositions = linkedSetOf<CivPosition>()

    init { open += Entry(start, 0.0, heuristic(start), serial++) }

    fun cancel() { cancelled.set(true) }

    /** Read only between search slices on the same worker. Initial and incoming probes are directional. */
    fun captured(probe: CitizenSearchProbe): CitizenSearchCapture? =
        if (probe.incoming || probe.node.initial) null
        else neighbourhoods[probe.node]?.takeIf { !it.deferredGrapple && it.probe.from == probe.from }

    @JvmOverloads
    fun advance(captures: List<CitizenSearchCapture>, pickupAccepted: Boolean? = null): CitizenSearchResult {
        val began = System.nanoTime()
        fun result(probes: List<CitizenSearchProbe> = emptyList(), path: List<CitizenPlannedStep>? = null,
                   failure: String? = null, continuation: Boolean = false,
                   continuationReason: String? = null, pickupCandidate: CitizenPoint? = null) = CitizenSearchResult(probes, visited, path, failure,
            System.nanoTime() - began, continuation,
            blockedPositions = if (failure == "no-path" || continuationReason == "no-path") blockedPositions.toList() else emptyList(),
            continuationReason = continuationReason, roadRouting = roadRouting, pickupCandidate = pickupCandidate,
            searchStop = failure ?: continuationReason, firstUnavailableBlock = firstUnavailableBlock)
        if (cancelled.get()) return result(failure = "cancelled")
        checkingPickup?.let { entry ->
            checkingPickup = null
            if (checkNotNull(pickupAccepted)) {
                if (!openCheaperWalks(entry)) {
                    val path = pathTo(entry.node) ?: return result(failure = "broken-path")
                    return result(path = path)
                }
                // The native owner has reserved these feet. A budget cannot discard this proven handoff.
                goal = entry
            } else open += entry
        }
        roadLookup?.let { lookup ->
            roadLookup = null
            corridor = lookup(origin, target)
            if (corridor != null) roadRouting = CitizenRoadRouting.ROAD
        }
        for (capture in captures) {
            if (capture.probe.incoming) {
                isolatedEndpointDistance(capture)?.let { distance ->
                    rememberObstacles(capture.blocks, target.y, target.y)
                    isolatedGoalDistance = distance
                }
                continue
            }
            if (!capture.deferredGrapple || neighbourhoods[capture.probe.node]?.deferredGrapple != false)
                neighbourhoods[capture.probe.node] = capture
        }
        for (previous in pending.values) {
            if (costs[previous.node] != previous.cost) continue
            val capture = neighbourhoods[previous.node] ?: continue
            if (previous.node.initial && !originInspected) {
                originInspected = true
                val terrain = geometry.also { it.blocks = capture.blocks }
                if (!terrain.bodyClear(origin)) proneOnly += start
                else if (terrain.supported(origin, true) && (pickupContainer?.let {
                        pickupDistanceSquared(origin, it) <= CITIZEN_BLOCK_REACH * CITIZEN_BLOCK_REACH
                    } ?: (atGoal(start) && terrain.bodyClear(target) && terrain.stepClear(origin, target, CitizenRouteAction.WALK)))) {
                    open += previous
                    continue
                }
            }
            expand(previous, capture)?.let { return result(failure = it) }
        }
        pending = emptyMap()
        if (reachedIsolatedFrontier()) return partial("no-path", MINIMUM_PARTIAL_GAIN, "isolated-endpoint")
        val requested = linkedMapOf<CitizenSearchNode, Entry>()
        var firstRequestedScore = Double.POSITIVE_INFINITY
        while (requested.size < settings.searchNodesPerBatch) {
            if (cancelled.get()) return result(failure = "cancelled")
            if (goal == null && roadRouting == CitizenRoadRouting.ROAD && visited >= roadNodeLimit) {
                if (requested.isNotEmpty()) break
                beginOrdinarySearch()
            }
            var entry = open.poll()
            while (entry != null && costs[entry.node] != entry.cost) entry = open.poll()
            if (goal?.let(::openCheaperWalks) == true) {
                if (entry != null) open += entry
                continue
            }
            if (entry == null) {
                if (requested.isEmpty() && goal == null && roadRouting == CitizenRoadRouting.ROAD) {
                    beginOrdinarySearch()
                    continue
                }
                if (requested.isEmpty() && goal == null && !widerSearch && deferred.isNotEmpty()) {
                    widerSearch = true
                    open.addAll(deferred)
                    deferred.clear()
                    continue
                }
                break
            }
            // Settle the initial long corridor first; widened detours need full batches to avoid repeated prefetch.
            if (gateRequests && !widerSearch && requested.isNotEmpty() && entry.score > firstRequestedScore) {
                open += entry
                break
            }
            val found = goal
            if (found != null && entry.score >= found.cost) {
                open += entry
                break
            }
            val node = entry.node
            if (pickupContainer != null && node !in proneOnly && (!node.initial || originInspected)) {
                val feet = if (node.initial) origin else node.point()
                if (pickupDistanceSquared(feet, pickupContainer) <= CITIZEN_BLOCK_REACH * CITIZEN_BLOCK_REACH &&
                    feet !in requireNotNull(checkedPickupFeet)) {
                    // Settle the outstanding neighbourhoods before pausing this frontier for one live reach check.
                    if (requested.isNotEmpty()) { open += entry; break }
                    checkedPickupFeet += feet
                    checkingPickup = entry
                    return result(pickupCandidate = feet)
                }
            }
            if (++visited > settings.maximumNodes) return finishAtBudget("search-budget", "node-budget")
            if (pickupContainer == null && (!node.initial || originInspected) && atGoal(node)) {
                if (goal?.let { entry.cost < it.cost } != false) goal = entry
            } else {
                val known = neighbourhoods[node]
                if (known == null || known.deferredGrapple) {
                    if (requested.isEmpty()) firstRequestedScore = entry.score
                    requested[node] = entry
                } else expand(entry, known)?.let { return result(failure = it) }
            }
            if (reachedIsolatedFrontier()) return partial("no-path", MINIMUM_PARTIAL_GAIN, "isolated-endpoint")
            // Only the initial hinted return may leave on useful native progress before the hard budget.
            // Its caller walks this segment, then starts an ordinary search to the unchanged depot.
            if (preferredCorridor != null && goal == null && !unknownTerrain && visited % HINTED_PROGRESS_INTERVAL == 0) {
                val endpoint = progressEndpoint(HINTED_PROGRESS_GAIN)
                if (endpoint != null && !atGoal(endpoint)) {
                    val path = pathTo(endpoint)
                        ?: return result(failure = if (cancelled.get()) "cancelled" else "broken-path")
                    return result(path = path, continuation = true, continuationReason = "hinted-progress")
                }
            }
        }
        pending = requested
        if (requested.isNotEmpty()) return result(buildList {
            addAll(requested.keys.map { CitizenSearchProbe(it, if (it == start) origin else it.point(),
                corridor = corridor.takeUnless { widerSearch },
                bounds = initialBounds.takeIf { corridor == null && !widerSearch }, specialTraversal = specialTraversal,
                refiningGrapple = neighbourhoods[it]?.deferredGrapple == true) })
            if (inspectEndpoint) {
                inspectEndpoint = false
                add(CitizenSearchProbe(CitizenSearchNode(floor(target.x).toInt(), target.y, floor(target.z).toInt()), target,
                    incoming = true, specialTraversal = specialTraversal))
            }
        })
        val found = goal ?: return when {
            unknownTerrain -> partial("chunk-unloaded", MINIMUM_PARTIAL_GAIN, "frontier-exhausted")
            clipped && isolatedGoalDistance == null -> finishAtBudget("search-budget", "search-boundary")
            else -> partial("no-path", MINIMUM_PARTIAL_GAIN, "frontier-exhausted")
        }
        val path = pathTo(found.node) ?: return result(failure = if (cancelled.get()) "cancelled" else "broken-path")
        return result(path = path)
    }

    /** Keep useful, already validated progress when a finite search fills its memory allowance.
     * The caller must walk this segment and continue to the original goal; it is not an arrival. */
    @JvmOverloads
    fun finishAtBudget(reason: String, searchStop: String = reason): CitizenSearchResult {
        if (specialTraversal && !cancelled.get()) {
            // A complete checked crossing remains usable when a cheaper detour consumes the remaining budget.
            val reached = goal?.node ?: if (pickupContainer == null)
                parents.keys.asSequence().filter(::atGoal).minByOrNull { costs.getValue(it) } else null
            reached?.let { pathTo(it) }?.let { return CitizenSearchResult(emptyList(), visited, path = it, roadRouting = roadRouting,
                searchStop = searchStop, firstUnavailableBlock = firstUnavailableBlock) }
        }
        return partial(if (isolatedGoalDistance != null) "no-path" else reason,
            if (sqrt(origin.distanceSquared(target)) <= settings.closeRouteDistance) MINIMUM_PARTIAL_GAIN else 2.0, searchStop)
    }

    /** Isolation proves failure, not that the first discovered step is the closest reachable point.
     * Stop early only after native progress reaches the endpoint probe's geometric lower bound. */
    private fun reachedIsolatedFrontier(): Boolean {
        val bound = isolatedGoalDistance ?: return false
        return sqrt(origin.distanceSquared(target)) - sqrt(closestDistanceSquared) >= MINIMUM_PARTIAL_GAIN &&
            closestDistanceSquared <= bound * bound + .0000001
    }

    /** An unreachable goal may still have a useful native prefix; its endpoint is never an arrival. */
    private fun partial(reason: String, minimumGain: Double, searchStop: String): CitizenSearchResult {
        fun failed(cause: String) = CitizenSearchResult(emptyList(), visited, failure = cause,
            blockedPositions = if (cause == "no-path") blockedPositions.toList() else emptyList(), roadRouting = roadRouting,
            searchStop = searchStop, firstUnavailableBlock = firstUnavailableBlock)
        if (cancelled.get()) return failed("cancelled")
        // A failed person detour must keep its caller's previous ready route available for waiting.
        val cause = if (unknownTerrain) "chunk-unloaded" else reason
        if (avoiding.isNotEmpty() && (reason == "no-path" || unknownTerrain)) return failed(cause)
        // The complete component exposed no free pickup position. Walking its rejected endpoint
        // would send a second resident to a counter already reserved by the first.
        if (pickupContainer != null && cause == "no-path") return failed(cause)
        // An unavailable neighbour does not invalidate connected edges already checked
        // against loaded cells. Walk only that prefix, then replan at its real frontier.
        val closest = progressEndpoint(minimumGain) ?: return failed(cause)
        val path = pathTo(closest) ?: return failed(if (cancelled.get()) "cancelled" else "broken-path")
        return CitizenSearchResult(emptyList(), visited, path = path, continuation = true,
            blockedPositions = if (cause == "no-path") blockedPositions.toList() else emptyList(), continuationReason = cause,
            roadRouting = roadRouting, searchStop = searchStop, firstUnavailableBlock = firstUnavailableBlock)
    }

    private fun progressEndpoint(minimumGain: Double): CitizenSearchNode? {
        val reach = if (pickupContainer == null) 0.0 else CITIZEN_BLOCK_REACH
        val distance = max(0.0, sqrt(pickupContainer?.let { pickupDistanceSquared(origin, it) } ?: origin.distanceSquared(target)) - reach)
        if (distance < minimumGain) return null
        return costs.keys.asSequence().filter { it != start && it in parents && it !in proneOnly }
            // Neither a hinted nor a budget-limited segment may end at feet the live owner already rejected.
            .filter { distance - max(0.0, sqrt(distanceToTargetSquared(it)) - reach) >= minimumGain &&
                checkedPickupFeet?.contains(it.point()) != true }
            .minWithOrNull(compareBy<CitizenSearchNode> { distanceToTargetSquared(it) }.thenBy { costs.getValue(it) })
    }

    private fun distanceToTargetSquared(node: CitizenSearchNode): Double {
        pickupContainer?.let { return pickupDistanceSquared(node.point(), it) }
        val dx = node.x + .5 - target.x
        val dy = node.y - target.y
        val dz = node.z + .5 - target.z
        return dx * dx + dy * dy + dz * dz
    }

    private fun atGoal(node: CitizenSearchNode): Boolean = node !in proneOnly && node.x == floor(target.x).toInt() &&
        node.z == floor(target.z).toInt() && abs(node.y - target.y) <= .5

    private fun pathTo(endpoint: CitizenSearchNode): List<CitizenPlannedStep>? {
        val reverse = ArrayDeque<CitizenPlannedStep>()
        var node = endpoint
        while (node != start) {
            if (cancelled.get()) return null
            val parent = parents[node] ?: return null
            reverse.addFirst(CitizenPlannedStep(node.point(), parent.action))
            node = parent.node
        }
        if (reverse.isEmpty()) reverse += CitizenPlannedStep(
            if (pickupContainer != null) origin else CitizenPoint(floor(target.x) + .5, target.y, floor(target.z) + .5),
            CitizenRouteAction.WALK)
        return reverse.toList()
    }

    /** Reuse exact captured geometry and directed policy decisions without another native handoff. */
    private fun expand(previous: Entry, capture: CitizenSearchCapture): String? {
        if (firstUnavailableBlock == null) {
            firstUnavailableBlock = capture.blocks.entries.firstOrNull { (point, block) ->
                point.y in minimumY until maximumY && !block.loaded
            }?.key
        }
        val terrain = geometry.also { it.blocks = capture.blocks }
        val from = capture.probe.from
        if (!terrain.loaded(previous.node.x, floor(previous.node.y).toInt(), previous.node.z))
            return if (roadRouting == CitizenRoadRouting.ROAD) null else "chunk-unloaded"
        val startPoint = start.point()
        val prone = specialTraversal && (previous.node in proneOnly || previous.node.initial && !terrain.bodyClear(from))
        for (edge in capture.edges) {
            if (cancelled.get()) return "cancelled"
            val next = edge.node
            if (!edge.permitted || next == previous.node) continue
            val point = next.point()
            val standing = terrain.bodyClear(point)
            val action = when {
                edge.action == CitizenRouteAction.GRAPPLE -> edge.action.takeIf { specialTraversal && !prone && standing &&
                    terrain.grappleClear(from, point, traversalAllowed) }
                !prone && standing && terrain.stepClear(from, point, edge.action) -> edge.action
                specialTraversal && edge.action == CitizenRouteAction.WALK &&
                    terrain.crawlClear(from, point, traversalAllowed) -> CitizenRouteAction.CRAWL
                else -> null
            }
            if (action == null) {
                rememberObstacles(capture.blocks, from.y, next.y)
                continue
            }
            if (abs(point.x - startPoint.x) > 96 || abs(point.z - startPoint.z) > 96 ||
                abs(point.y - start.y) > 96) { clipped = true; continue }
            val weight = capture.blocks[next.x, citizenSupportY(next.y), next.z]?.cost
                ?: Double.POSITIVE_INFINITY
            if (!weight.isFinite() || action.isSpecialTraversal &&
                capture.blocks[previous.node.x, citizenSupportY(from.y), previous.node.z]?.cost?.isFinite() != true) continue
            val distance = when (action) {
                CitizenRouteAction.GRAPPLE -> CITIZEN_WORK_WALK_STRIDE * (CITIZEN_GRAPPLE_CAST_TICKS +
                    (abs(point.y - from.y) + 1) / CITIZEN_GRAPPLE_STRIDE)
                CitizenRouteAction.CRAWL -> sqrt(from.distanceSquared(point)) * (CITIZEN_WORK_WALK_STRIDE / CITIZEN_CRAWL_STRIDE)
                else -> sqrt(from.distanceSquared(point))
            }
            val cost = previous.cost + distance * weight.coerceIn(1.0, CivsPaths.ORDINARY_COST)
            if (cost >= (costs[next] ?: Double.POSITIVE_INFINITY)) continue
            costs[next] = cost
            parents[next] = Parent(previous.node, action)
            if (!standing) proneOnly += next
            if (standing && next != start) closestDistanceSquared = minOf(closestDistanceSquared, point.distanceSquared(target))
            val special = previous.special || action.isSpecialTraversal
            val entry = Entry(next, cost, cost + heuristic(next), serial++, special)
            // A checked wall top belongs to the same horizontal corridor even above its normal walking envelope.
            if (widerSearch || inCorridor(point) || special && corridor == null && initialBounds.containsColumn(point)) open += entry
            else deferred += entry
        }
        return null
    }

    /** A failed route refreshes only a small physical frontier, never its whole explored zone.
     * This also discovers edits made by other plugins without a block-change event. */
    private fun rememberObstacles(blocks: Map<CivPosition, CitizenTerrainBlock>, fromY: Double, toY: Double) {
        for ((point, block) in blocks) {
            if (blockedPositions.size >= 64) return
            if (!block.loaded || point.y >= max(fromY, toY) + 1.799) continue
            if (block.liquid && point.y + 1.0 > min(fromY, toY) + .001 ||
                block.boxes.any { point.y + it.maxY > min(fromY, toY) + .001 }) blockedPositions += point
        }
    }

    private fun inCorridor(point: CitizenPoint): Boolean =
        corridor?.contains(point) ?: initialBounds.contains(point)

    private fun openCheaperWalks(found: Entry): Boolean {
        if (!found.special || widerSearch || deferred.none {
                !it.special && it.score < found.cost && costs[it.node] == it.cost }) return false
        widerSearch = true
        if (roadRouting == CitizenRoadRouting.ROAD) roadRouting = CitizenRoadRouting.FALLBACK
        open.addAll(deferred)
        deferred.clear()
        return true
    }

    /** Keep native captures and every boundary edge; a stale road gets the usual bounded search, not another hint. */
    private fun beginOrdinarySearch() {
        corridor = null
        roadRouting = CitizenRoadRouting.FALLBACK
        val frontier = ArrayList<Entry>(open.size + deferred.size)
        frontier.addAll(open)
        frontier.addAll(deferred)
        open.clear()
        deferred.clear()
        for (entry in frontier) {
            if (costs[entry.node] != entry.cost) continue
            if (initialBounds.contains(entry.node.point())) open += entry else deferred += entry
        }
    }

    /** Prove isolation only for a centred, integer-height goal on a full block. Other shapes
     * and ladders keep the ordinary search; unknown cells never prove a closed entrance. */
    private fun isolatedEndpointDistance(capture: CitizenSearchCapture): Double? {
        val blocks = capture.blocks
        if (blocks.values.any { !it.loaded || it.climbable }) return null
        val feet = CivPosition(floor(target.x).toInt(), target.y.toInt(), floor(target.z).toInt())
        val ground = blocks[feet.copy(y = feet.y - 1)] ?: return null
        if (ground.liquid || ground.door || ground.boxes.none { it.minX <= 0 && it.maxX >= 1 &&
                it.minZ <= 0 && it.maxZ >= 1 && it.maxY == 1.0 }) return null
        for (point in listOf(feet, feet.copy(y = feet.y + 1))) {
            val block = blocks[point] ?: return null
            if (block.liquid || block.door || block.boxes.isNotEmpty()) return null
        }
        val terrain = geometry.also { it.blocks = blocks }
        val isolated = blocks.getValue(feet).allowed.not() || !terrain.bodyClear(target) || capture.edges.none { edge ->
            if (!edge.permitted) false else {
                val point = edge.node.point()
                if (edge.action == CitizenRouteAction.GRAPPLE) specialTraversal && terrain.grappleClear(point, target, traversalAllowed)
                else terrain.bodyClear(point) && terrain.stepClear(point, target, edge.action) ||
                    specialTraversal && edge.action == CitizenRouteAction.WALK && terrain.crawlClear(point, target, traversalAllowed)
            }
        }
        if (!isolated) return null
        // Every centre outside the captured 3x3 columns is at least two blocks away.
        // Inside them inspect every supported height in that ball, including surfaces too
        // high for an incoming step. Ignoring connectivity makes this a lower bound only.
        var distance = 2.0
        for ((point, groundBlock) in blocks) {
            if (groundBlock.liquid || groundBlock.door) continue
            for (box in groundBlock.boxes) {
                if (box.maxY <= 0 || box.minX > .5 || box.maxX < .5 || box.minZ > .5 || box.maxZ < .5) continue
                val node = CitizenSearchNode(point.x, point.y + box.maxY, point.z)
                val candidate = node.point()
                if (atGoal(node) || candidate.distanceSquared(target) >= distance * distance ||
                    blocks[node.x, floor(node.y + .001).toInt(), node.z]?.allowed != true ||
                    !terrain.bodyClear(candidate)) continue
                distance = sqrt(candidate.distanceSquared(target))
            }
        }
        return distance
    }

    private fun heuristic(node: CitizenSearchNode): Double {
        pickupContainer?.let {
            return heuristicWeight * max(0.0, sqrt(pickupDistanceSquared(node.point(), it)) - CITIZEN_BLOCK_REACH)
        }
        val dx = node.x + .5 - target.x
        val dz = node.z + .5 - target.z
        val dy = max(0.0, abs(node.y - target.y) - .5)
        return heuristicWeight * sqrt(dx * dx + dy * dy + dz * dz)
    }

    // Lower bound for the two live pickup rules: feet to block centre, or eye to a visible face.
    private fun pickupDistanceSquared(feet: CitizenPoint, container: CivPosition): Double {
        val dx = abs(feet.x - (container.x + .5))
        val dy = feet.y - (container.y + .5)
        val dz = abs(feet.z - (container.z + .5))
        val xFace = max(0.0, dx - .5)
        val yFace = max(0.0, abs(dy + 1.5) - .5)
        val zFace = max(0.0, dz - .5)
        return minOf(dx * dx + dy * dy + dz * dz, xFace * xFace + yFace * yFace + zFace * zFace)
    }

    companion object {
        const val MAXIMUM_NODES = 4096
        private const val MINIMUM_PARTIAL_GAIN = .5
        private const val HINTED_PROGRESS_INTERVAL = 512
        private const val HINTED_PROGRESS_GAIN = 8.0
        private const val LOCAL_ROUTE_DISTANCE = 32.0
        private const val WEIGHTED_ROUTE_DISTANCE = 64.0
        private const val MAXIMUM_HEURISTIC_WEIGHT = 2.0
    }
}

/** Enumerate each candidate once, capturing its optional directed policy in the same order. */
internal fun citizenSearchCandidates(probe: CitizenSearchProbe, blocks: Map<CivPosition, CitizenTerrainBlock>,
    minimumY: Int, maximumY: Int, stepAllowed: ((CitizenPoint, CitizenPoint) -> Boolean)? = null): List<CitizenSearchEdge> {
    val node = probe.node
    val result = ArrayList<CitizenSearchEdge>(16)
    val base = floor(node.y).toInt()
    val onLadder = blocks[node.x, base, node.z]?.climbable == true
    fun add(x: Int, y: Double, z: Int, climbing: Boolean, first: Int) {
        // A column has only its few supported heights. Keep first occurrence/order without hash nodes.
        for (index in first until result.size) {
            val present = result[index].node
            if (present.x == x && present.z == z && present.y.toBits() == y.toBits() && present.climbing == climbing) return
        }
        val action = when {
            climbing && onLadder && x == node.x && z == node.z -> CitizenRouteAction.CLIMB
            climbing -> CitizenRouteAction.LADDER_ENTER
            node.climbing || onLadder -> CitizenRouteAction.LADDER_EXIT
            abs(y - node.y) > .0001 -> CitizenRouteAction.STEP
            else -> CitizenRouteAction.WALK
        }
        val candidate = blocks.searchCandidateNode(x, y, z, climbing)
        val permitted = stepAllowed?.let { allowed ->
            val point = candidate.point()
            if (probe.incoming) allowed(point, probe.from) else allowed(probe.from, point)
        } ?: true
        result += CitizenSearchEdge(candidate, action, permitted)
    }
    fun standing(x: Int, z: Int) {
        val first = result.size
        for (by in base - 2..base + 1) {
            if (by < minimumY || by + 2 >= maximumY) continue
            val block = blocks[x, by, z] ?: continue
            // Opening a passage must never remove the foothold selected by the planner.
            if (!block.loaded || block.liquid || block.door) continue
            if (block.climbable && block.allowed && citizenStepHeightAllowed(node.y, by.toDouble()))
                add(x, by.toDouble(), z, true, first)
            for (box in block.boxes) {
                if (box.maxY <= 0 || box.minX > .5 || box.maxX < .5 || box.minZ > .5 || box.maxZ < .5) continue
                val height = by + box.maxY
                if (!citizenStepHeightAllowed(node.y, height)) continue
                val feet = blocks[x, floor(height + .001).toInt(), z] ?: continue
                if (!feet.loaded || !feet.allowed) continue
                // This box already supports the centre. A protruding shape can put the feet on another row.
                val supportY = citizenSupportY(height)
                if (!feet.climbable && supportY != by) {
                    val support = blocks[x, supportY, z] ?: continue
                    if (!support.loaded || support.liquid || support.door || support.boxes.none {
                            abs(supportY + it.maxY - height) <= .13 &&
                                .5 in it.minX..it.maxX && .5 in it.minZ..it.maxZ
                        }) continue
                }
                add(x, height, z, false, first)
            }
        }
    }
    if (node.initial) standing(node.x, node.z)
    for (dx in -1..1) for (dz in -1..1) if (dx != 0 || dz != 0) standing(node.x + dx, node.z + dz)
    if (onLadder) for (dy in -1..1) {
        val next = blocks[node.x, base + dy, node.z] ?: continue
        if (next.loaded && next.climbable && next.allowed && citizenStepHeightAllowed(node.y, (base + dy).toDouble()))
            add(node.x, (base + dy).toDouble(), node.z, true, 0)
    }
    if (probe.specialTraversal && !onLadder && probe.from == node.point() && blocks.grappleStandingRoom(node)) {
        for ((dx, dz) in grappleDirections) {
            val x = node.x + dx
            val z = node.z + dz
            val up = blocks.mayGrappleUp(node, dx, dz)
            val down = blocks.mayGrappleDown(node, dx, dz)
            if (!up && !down) continue
            val lowest = if (down) base - CITIZEN_GRAPPLE_MAX_RISE - 1 else base
            val highest = if (up) base + CITIZEN_GRAPPLE_MAX_RISE else base - 1
            for (by in maxOf(minimumY, lowest)..minOf(maximumY - 2, highest)) {
                val floor = blocks[x, by, z] ?: continue
                if (!floor.loaded || floor.liquid || floor.door || floor.hot) continue
                for (box in floor.boxes) {
                    if (.5 !in box.minX..box.maxX || .5 !in box.minZ..box.maxZ || box.maxY <= 0) continue
                    val height = by + box.maxY
                    val rise = height - node.y
                    if (citizenStepHeightAllowed(height, node.y) || abs(rise) > CITIZEN_GRAPPLE_MAX_RISE ||
                        rise > 0 && !up || rise < 0 && !down) continue
                    val feet = blocks[x, kotlin.math.floor(height + .001).toInt(), z] ?: continue
                    if (!feet.loaded || !feet.allowed || feet.liquid || feet.door || feet.boxes.any {
                            it.minY <= .001 && .5 in it.minX..it.maxX && .5 in it.minZ..it.maxZ && kotlin.math.floor(height + .001) + it.maxY > height + .001
                        }) continue
                    val candidate = blocks.searchCandidateNode(x, height, z, false)
                    val other = candidate.point()
                    val low = if (rise > 0) probe.from else other
                    val high = if (rise > 0) other else probe.from
                    if (!blocks.grappleFace(low, high)) continue
                    val action = CitizenRouteAction.GRAPPLE
                    if (result.none { it.node == candidate && it.action == action })
                        result += CitizenSearchEdge(candidate, action, stepAllowed == null ||
                            citizenSearchEdgePermitted(probe, other, action, stepAllowed))
                }
            }
        }
    }
    return result
}

private val grappleDirections = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

private fun CitizenTerrainBox.hookFace(dx: Int, dz: Int) =
    citizenHookFace(minX, minY, minZ, maxX, maxY, maxZ, dx, dz)

private fun CitizenTerrainBlock?.wallFace(dx: Int, dz: Int): Boolean =
    this != null && loaded && !door && !liquid && !hot && boxes.any { it.hookFace(dx, dz) }

/** Prefetch also proposes supports buried inside floors. A hook can only start from standing feet. */
private fun Map<CivPosition, CitizenTerrainBlock>.grappleStandingRoom(node: CitizenSearchNode): Boolean {
    val y = floor(node.y + .001).toInt()
    if (this[node.x, y, node.z]?.allowed != true) return false
    for (row in y - 1..floor(node.y + 1.799).toInt()) {
        val b = this[node.x, row, node.z] ?: return false
        if (!b.loaded || b.liquid && row + 1 > node.y + .001 ||
            b.boxes.any { it.maxX > .201 && it.minX < .799 && it.maxZ > .201 && it.minZ < .799 &&
                row + it.maxY > node.y + .001 && row + it.minY < node.y + 1.799 }) return false
    }
    return true
}

private fun Map<CivPosition, CitizenTerrainBlock>.mayGrappleUp(node: CitizenSearchNode, dx: Int, dz: Int): Boolean {
    val y = floor(node.y + .001).toInt()
    return this[node.x + dx, y + 1, node.z + dz].wallFace(dx, dz) &&
        this[node.x + dx, y, node.z + dz].wallFace(dx, dz)
}

private fun Map<CivPosition, CitizenTerrainBlock>.mayGrappleDown(node: CitizenSearchNode, dx: Int, dz: Int): Boolean {
    val y = citizenSupportY(node.y)
    val shaft = this[node.x + dx, y, node.z + dz] ?: return false
    if (!shaft.loaded || !shaft.allowed || shaft.liquid || shaft.door ||
        shaft.boxes.any { .5 in it.minX..it.maxX && .5 in it.minZ..it.maxZ }) return false
    val below = this[node.x + dx, y - 1, node.z + dz] ?: return false
    return below.loaded && !below.liquid && !below.door && below.boxes.none {
        .5 in it.minX..it.maxX && .5 in it.minZ..it.maxZ && citizenStepHeightAllowed(node.y, y - 1 + it.maxY)
    } && this[node.x, y, node.z].wallFace(-dx, -dz)
}

/** Ordinary neighbourhoods stay 3×3×7. Only a real adjacent face/drop needs the taller local capture. */
internal fun citizenGrappleRows(probe: CitizenSearchProbe, blocks: Map<CivPosition, CitizenTerrainBlock>,
                                allowed: (CivPosition) -> Boolean): IntRange? {
    if (!probe.specialTraversal || probe.from != probe.node.point() || probe.node.climbing ||
        !blocks.grappleStandingRoom(probe.node)) return null
    val node = probe.node
    val y = floor(node.y).toInt()
    var first = y - 3
    var last = y + 3
    for ((dx, dz) in grappleDirections) {
        val x = node.x + dx
        val z = node.z + dz
        if (blocks.mayGrappleUp(node, dx, dz) && blocks[x, y + 2, z].wallFace(dx, dz) &&
            allowed(CivPosition(x, y + 1, z))) {
            val above = if (blocks[x, y + 3, z].wallFace(dx, dz)) {
                if (blocks[x, y + 5, z].wallFace(dx, dz)) CITIZEN_GRAPPLE_MAX_RISE + 2 else 6
            } else 5
            last = maxOf(last, y + above)
        }
        if (blocks.mayGrappleDown(node, dx, dz) && allowed(CivPosition(node.x, citizenSupportY(node.y), node.z)) &&
            blocks[x, y - 3, z]?.let { it.loaded && !it.liquid && !it.door &&
                it.boxes.none { box -> .5 in box.minX..box.maxX && .5 in box.minZ..box.maxZ } } == true)
            first = minOf(first, y - if (blocks[x, y - 5, z]?.let { it.loaded && !it.liquid &&
                it.boxes.none { box -> .5 in box.minX..box.maxX && .5 in box.minZ..box.maxZ } } == true &&
                blocks[x, y - 4, z]?.boxes?.none { box -> .5 in box.minX..box.maxX && .5 in box.minZ..box.maxZ } == true)
                CITIZEN_GRAPPLE_MAX_RISE + 2 else 5)
    }
    return if (first == y - 3 && last == y + 3) null else first..last
}

private fun Map<CivPosition, CitizenTerrainBlock>.grappleFace(low: CitizenPoint, high: CitizenPoint): Boolean {
    val x = floor(high.x).toInt()
    val z = floor(high.z).toInt()
    val dx = (high.x - low.x).toInt()
    val dz = (high.z - low.z).toInt()
    val cap = citizenSupportY(high.y)
    for (y in floor(low.y + .001).toInt()..cap) {
        val wall = this[x, y, z] ?: return false
        if (!wall.loaded || wall.liquid || wall.door || wall.hot || wall.boxes.none {
                it.hookFace(dx, dz) && if (y == cap) abs(y + it.maxY - high.y) < .001 else it.maxY >= .999
            }) return false
    }
    return true
}

/** Check the actual bent path in its travel direction, including reverse endpoint inspection. */
internal fun citizenSearchEdgePermitted(probe: CitizenSearchProbe, other: CitizenPoint, action: CitizenRouteAction,
                                       allowed: (CitizenPoint, CitizenPoint) -> Boolean): Boolean {
    val from = if (probe.incoming) other else probe.from
    val to = if (probe.incoming) probe.from else other
    if (action != CitizenRouteAction.GRAPPLE) return allowed(from, to)
    val crest = if (from.y < to.y) from.copy(y = to.y) else to.copy(y = from.y)
    return allowed(from, crest) && allowed(crest, to)
}

private class CitizenSearchGeometry(
    private val minimumY: Int,
    private val maximumY: Int,
    avoiding: List<CitizenTerrainBox>,
) {
    var blocks: Map<CivPosition, CitizenTerrainBlock> = emptyMap()
    private val bodies = avoiding.map { it.at(0, 0, 0) }
    // One worker owns this scratch for the job. A synchronous sweep only sees the active prefix.
    private val segmentObstacles = ArrayList<BoundingBox>()
    private val bodyScratch = BoundingBox()
    private val obstacleScratch = BoundingBox()
    private val occupiedScratch = BoundingBox()
    fun loaded(x: Int, y: Int, z: Int) = blocks[x, y, z]?.loaded == true

    fun bodyClear(point: CitizenPoint): Boolean = bodyClear(point.x, point.y, point.z)

    private fun bodyClear(px: Double, py: Double, pz: Double, height: Double = 1.8): Boolean {
        val body = bodyScratch.resize(px - .299, py + .001, pz - .299,
            px + .299, py + height - .001, pz + .299)
        val feetRow = floor(body.minY).toInt()
        if (feetRow < minimumY) return false
        for (x in floor(body.minX).toInt()..floor(body.maxX).toInt())
            for (z in floor(body.minZ).toInt()..floor(body.maxZ).toInt())
                for (y in max(minimumY, feetRow - 1)..floor(body.maxY).toInt()) {
                    val block = blocks[x, y, z] ?: return false
                    if (y >= maximumY || !block.loaded) return false
                    if (block.liquid && body.overlaps(obstacleScratch.resize(x.toDouble(), y.toDouble(), z.toDouble(),
                            x + 1.0, y + 1.0, z + 1.0))) return false
                    if (block.boxes.any { body.overlaps(obstacleScratch.resize(it.minX + x, it.minY + y, it.minZ + z,
                            it.maxX + x, it.maxY + y, it.maxZ + z)) }) return false
                }
        return true
    }

    fun stepClear(from: CitizenPoint, to: CitizenPoint, action: CitizenRouteAction): Boolean {
        if (!citizenStepHeightAllowed(from.y, to.y)) return false
        if (bodies.isNotEmpty() &&
            citizenSlideBody(Vector(from.x, from.y, from.z), Vector(to.x, to.y, to.z), .299, 1.798, .299, bodies) != null) return false
        // An endpoint can stand on a stair's high half while its flat approach crosses the low half.
        // Reject that edge in the graph instead of repeatedly handing it to stricter packet movement.
        if (action == CitizenRouteAction.WALK && from.y == to.y &&
            !citizenFlatSupportClear(from, to) { citizenWalkingSupported(it, ::supported) }) return false
        // Enter/settle onto the ladder centre before changing height, including fractional starts.
        if (action.alignsToLadder) {
            val centre = CitizenPoint(to.x, from.y, to.z)
            return segmentClear(from, centre, from) && segmentClear(centre, to, from)
        }
        if (from.y != to.y) {
            val high = max(from.y, to.y)
            val aboveFrom = CitizenPoint(from.x, high, from.z)
            val aboveTo = CitizenPoint(to.x, high, to.z)
            return segmentClear(from, aboveFrom, from) && segmentClear(aboveFrom, aboveTo, from) && segmentClear(aboveTo, to, from)
        }
        return segmentClear(from, to, from)
    }

    fun crawlClear(from: CitizenPoint, to: CitizenPoint, allowed: (CivPosition) -> Boolean): Boolean {
        if (from.y != to.y) return false
        val dx = abs(floor(from.x).toInt() - floor(to.x).toInt())
        val dz = abs(floor(from.z).toInt() - floor(to.z).toInt())
        if (dx + dz > 1 || !supported(from, false) || !supported(to, false) ||
            !citizenFlatSupportClear(from, to) { citizenWalkingSupported(it, ::supported) }) return false
        return specialSegmentClear(from, to, CITIZEN_CRAWL_HEIGHT, allowed)
    }

    fun grappleClear(from: CitizenPoint, to: CitizenPoint, allowed: (CivPosition) -> Boolean): Boolean {
        val low = if (from.y < to.y) from else to
        val high = if (from.y < to.y) to else from
        if (abs(low.x - high.x) + abs(low.z - high.z) != 1.0 ||
            citizenStepHeightAllowed(low.y, high.y) || high.y - low.y > CITIZEN_GRAPPLE_MAX_RISE ||
            !supported(low, false) || !supported(high, false) || !bodyClear(low) || !bodyClear(high) ||
            !blocks.grappleFace(low, high)) return false
        for (y in floor(low.y + .001).toInt()..citizenSupportY(high.y))
            if (!allowed(CivPosition(floor(high.x).toInt(), y, floor(high.z).toInt()))) return false
        val crest = low.copy(y = high.y)
        return specialSegmentClear(from, crest, 1.8, allowed) && specialSegmentClear(crest, to, 1.8, allowed)
    }

    private fun specialSegmentClear(from: CitizenPoint, to: CitizenPoint, height: Double,
                                    allowed: (CivPosition) -> Boolean): Boolean {
        val minFeet = floor(min(from.y, to.y) + .001).toInt()
        val maxFeet = floor(max(from.y, to.y) + .001).toInt()
        for (x in floor(min(from.x, to.x) - .299).toInt()..floor(max(from.x, to.x) + .299).toInt())
            for (z in floor(min(from.z, to.z) - .299).toInt()..floor(max(from.z, to.z) + .299).toInt())
                for (y in minFeet - 1..floor(max(from.y, to.y) + 1.799).toInt()) {
                    val block = blocks[x, y, z] ?: return false
                    // Unknown headroom is never classified as a low opening. Check territory for the
                    // occupied posture, its support and the entire shaft, including the mantle corner.
                    if (!block.loaded || y in minFeet..maxFeet && !block.allowed) return false
                    if (y <= floor(max(from.y, to.y) + height - .001).toInt() &&
                        (block.door || !allowed(CivPosition(x, y, z)))) return false
                }
        if (bodies.isNotEmpty() && citizenSlideBody(Vector(from.x, from.y, from.z), Vector(to.x, to.y, to.z),
                .299, height - .002, .299, bodies) != null) return false
        return bodyClear(from.x, from.y, from.z, height) && segmentClear(from, to, from, height)
    }

    fun supported(point: CitizenPoint, allowClimbable: Boolean): Boolean {
        val x = floor(point.x).toInt()
        val z = floor(point.z).toInt()
        val y = floor(point.y + .001).toInt()
        if (y <= minimumY || y >= maximumY) return false
        val feet = blocks[x, y, z]?.takeIf { it.loaded } ?: return false
        if (allowClimbable && feet.climbable) return true
        val by = citizenSupportY(point.y)
        val block = blocks[x, by, z]?.takeIf { it.loaded && !it.liquid && !it.door } ?: return false
        return block.boxes.any {
            abs(by + it.maxY - point.y) <= .13 && point.x - x in it.minX..it.maxX && point.z - z in it.minZ..it.maxZ
        }
    }

    private fun segmentClear(from: CitizenPoint, to: CitizenPoint, occupied: CitizenPoint, height: Double = 1.8): Boolean {
        var obstacleCount = 0
        val feetRow = floor(min(from.y, to.y) + .001).toInt()
        if (feetRow < minimumY) return false
        for (x in floor(min(from.x, to.x) - .299).toInt()..floor(max(from.x, to.x) + .299).toInt())
            for (z in floor(min(from.z, to.z) - .299).toInt()..floor(max(from.z, to.z) + .299).toInt())
                for (y in max(minimumY, feetRow - 1)..floor(max(from.y, to.y) + height - .001).toInt()) {
                    val block = blocks[x, y, z] ?: return false
                    if (y !in minimumY until maximumY || !block.loaded) return false
                    if (block.liquid) {
                        setSegmentObstacle(obstacleCount++, x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0)
                    } else for (index in block.boxes.indices) {
                        val box = block.boxes[index]
                        setSegmentObstacle(obstacleCount++, box.minX + x, box.minY + y, box.minZ + z,
                            box.maxX + x, box.maxY + y, box.maxZ + z)
                    }
                }
        // Match the solver's contact epsilon and arithmetic order before allocating its vectors and list view.
        val sweep = occupiedScratch.resize(minOf(from.x, to.x) - .299,
            minOf(from.y + .001, to.y + .001), minOf(from.z, to.z) - .299,
            maxOf(from.x, to.x) + .299, maxOf(from.y + .001, to.y + .001) + (height - .002),
            maxOf(from.z, to.z) + .299).expand(1.0e-7)
        var intersects = false
        for (index in 0 until obstacleCount) if (sweep.overlaps(segmentObstacles[index])) {
            intersects = true
            break
        }
        if (intersects && citizenSlideBody(Vector(from.x, from.y + .001, from.z), Vector(to.x, to.y + .001, to.z),
                .299, height - .002, .299, segmentObstacles.subList(0, obstacleCount)) != null) return false
        val samples = ceil(sqrt(from.distanceSquared(to)) / .15).toInt().coerceIn(1, 16)
        val occupiedBody = occupiedScratch.resize(occupied.x - .299, occupied.y - .001, occupied.z - .299,
            occupied.x + .299, occupied.y + height - .001, occupied.z + .299)
        for (index in 1..samples) {
            val t = index.toDouble() / samples
            val px = from.x + (to.x - from.x) * t
            val py = from.y + (to.y - from.y) * t
            val pz = from.z + (to.z - from.z) * t
            if (!bodyClear(px, py, pz, height)) return false
            for (x in floor(px - .299).toInt()..floor(px + .299).toInt())
                for (z in floor(pz - .299).toInt()..floor(pz + .299).toInt())
                    for (y in floor(py - .001).toInt()..floor(py + height - .001).toInt()) {
                        val block = blocks[x, y, z] ?: return false
                        if (!block.loaded) return false
                        if (block.hot && !occupiedBody.overlaps(obstacleScratch.resize(x.toDouble(), y.toDouble(), z.toDouble(),
                                x + 1.0, y + 1.0, z + 1.0))) return false
                    }
        }
        return true
    }

    private fun setSegmentObstacle(index: Int, minX: Double, minY: Double, minZ: Double,
                                   maxX: Double, maxY: Double, maxZ: Double) {
        if (index < segmentObstacles.size) segmentObstacles[index].resize(minX, minY, minZ, maxX, maxY, maxZ)
        else segmentObstacles.add(BoundingBox(minX, minY, minZ, maxX, maxY, maxZ))
    }
}
