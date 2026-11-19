package ru.arc.civs

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import java.util.AbstractMap.SimpleImmutableEntry
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor

private val unavailableSnapshotBlock = CitizenTerrainBlock.UNAVAILABLE.copy(cost = 1.0)
/** This cell belongs to one search job; global section/palette values never retain candidate nodes. */
internal class CitizenSnapshotCell(point: CivPosition, block: CitizenTerrainBlock) :
    SimpleImmutableEntry<CivPosition, CitizenTerrainBlock>(point, block) {
    private var standingNode: CitizenSearchNode? = null

    // Keep the first exact standing height only; a different height still gets its own node.
    fun standingNode(y: Double): CitizenSearchNode {
        val old = standingNode
        if (old != null && old.y.toBits() == y.toBits()) return old
        return CitizenSearchNode(key.x, y, key.z).also { if (old == null) standingNode = it }
    }
}

/** Replaces this route's cell map; exact X/Z and Y keys do not truncate world coordinates. */
internal class CitizenSnapshotCells : AbstractMap<CivPosition, CitizenTerrainBlock>() {
    private val columns = Long2ObjectOpenHashMap<Int2ObjectOpenHashMap<CitizenSnapshotCell>>()
    override var size: Int = 0
        private set

    fun cell(x: Int, y: Int, z: Int): CitizenSnapshotCell? =
        columns[civsTerrainKey(x, z)]?.get(y)

    override fun get(key: CivPosition): CitizenTerrainBlock? = cell(key.x, key.y, key.z)?.value

    fun add(point: CivPosition, block: CitizenTerrainBlock): CitizenSnapshotCell {
        val key = civsTerrainKey(point.x, point.z)
        val column = columns[key] ?: Int2ObjectOpenHashMap<CitizenSnapshotCell>(8).also { columns.put(key, it) }
        check(!column.containsKey(point.y)) { "Captured navigation cells are immutable" }
        return CitizenSnapshotCell(point, block).also { column.put(point.y, it); size++ }
    }

    override val entries: Set<Map.Entry<CivPosition, CitizenTerrainBlock>> = object : AbstractSet<Map.Entry<CivPosition, CitizenTerrainBlock>>() {
        override val size: Int get() = this@CitizenSnapshotCells.size
        override fun iterator(): Iterator<Map.Entry<CivPosition, CitizenTerrainBlock>> =
            columns.values.asSequence().flatMap { it.values.asSequence() }.iterator()
    }
}

/** One route's worker-owned immutable inputs and derived cells; never exposed as mutable Main state. */
internal class CitizenSnapshotTerrain {
    val chunks = hashMapOf<CitizenSnapshotKey, CitizenSnapshotChunk>()
    val unavailable = hashSetOf<CitizenSnapshotKey>()
    val states = hashMapOf<BlockData, CitizenSnapshotState>()
    // Resolved states accumulate for this route. Refreshed sections supply a new immutable palette.
    private val resolvedPalettes = Collections.newSetFromMap(IdentityHashMap<Map<BlockData, CivPosition>, Boolean>())
    val dynamics = hashMapOf<CivPosition, List<CitizenTerrainBox>>()
    val costs = hashMapOf<CivPosition, Double>()
    val blocks = CitizenSnapshotCells()
    val sections = hashMapOf<CitizenSnapshotSectionKey, Long>()
    private val seeded = hashMapOf<CitizenSnapshotSectionKey, CitizenSnapshotSection>()
    private var publishedPayloads = emptyList<CitizenSnapshotSection>()
    private var payloadsChanged = true

    fun merge(key: CitizenSnapshotKey, chunk: CitizenSnapshotChunk) {
        chunks[key]?.merge(chunk) ?: run { chunks[key] = chunk }
        payloadsChanged = true
    }
    fun unavailable(keys: Set<CitizenSnapshotKey>) {
        // A later partial-section request can observe a downgrade without a native unload event.
        // Previously consumed geometry cannot be mixed with that unavailable reply.
        if (keys.any { it in chunks }) throw CitizenSnapshotUnavailable()
        unavailable += keys
        seeded.keys.removeIf { CitizenSnapshotKey(it.x, it.z) in keys }
        if (keys.isNotEmpty()) payloadsChanged = true
    }
    fun seed(values: List<CitizenSnapshotSection>) {
        for (value in values) if (value.key !in sections) seeded[value.key] = value
        if (values.isNotEmpty()) payloadsChanged = true
    }
    fun activate(section: CitizenSnapshotSectionKey, minimumY: Int, maximumY: Int) {
        if (CitizenSnapshotKey(section.x, section.z) in unavailable) return
        val value = seeded.remove(section) ?: return
        val key = CitizenSnapshotKey(section.x, section.z)
        merge(key, CitizenSnapshotChunk.detached(key, minimumY, maximumY, listOf(value)))
    }
    fun payloads(changed: Boolean = false): List<CitizenSnapshotSection> {
        if (payloadsChanged || changed) {
            publishedPayloads = chunks.values.flatMap { it.payloads() } + seeded.values
            payloadsChanged = false
        }
        return publishedPayloads
    }

    fun collectMissingStates(palette: Map<BlockData, CivPosition>, missing: MutableMap<BlockData, CivPosition>?):
        MutableMap<BlockData, CivPosition>? {
        if (palette in resolvedPalettes) return missing
        var requested = missing
        var complete = true
        for (data in palette.keys) {
            if (data in states) continue
            complete = false
            if (requested?.size == 256) break
            if (requested == null) requested = linkedMapOf()
            requested.putIfAbsent(data, palette.getValue(data))
        }
        if (complete) resolvedPalettes.add(palette)
        return requested
    }
}

internal data class CitizenSnapshotInput(
    val chunks: Map<CitizenSnapshotKey, CitizenSnapshotChunk> = emptyMap(),
    val unavailable: Set<CitizenSnapshotKey> = emptySet(),
    val states: Map<BlockData, CitizenSnapshotState> = emptyMap(),
    val dynamics: Map<CivPosition, List<CitizenTerrainBox>> = emptyMap(),
    val costs: Map<CivPosition, Double> = emptyMap(),
    val seeded: List<CitizenSnapshotSection> = emptyList(),
)

internal data class CitizenSnapshotRequest(
    val chunks: Set<CitizenSnapshotKey> = emptySet(),
    val states: Map<BlockData, CivPosition> = emptyMap(),
    val dynamics: Map<CivPosition, BlockData> = emptyMap(),
    val costs: Set<CivPosition> = emptySet(),
    val captures: List<CitizenSearchCapture>? = null,
    val capturedBlocks: Int = 0,
    val sections: Map<CitizenSnapshotSectionKey, Long> = emptyMap(),
    val requestedSections: Set<CitizenSnapshotSectionKey> = emptySet(),
    val decoded: Map<CitizenSnapshotSectionKey, CitizenSnapshotSection> = emptyMap(),
    val payloads: List<CitizenSnapshotSection> = emptyList(),
)

/** The existing dense neighbourhood needs no temporary key; ordinary maps keep their exact lookup. */
internal operator fun Map<CivPosition, CitizenTerrainBlock>.get(x: Int, y: Int, z: Int): CitizenTerrainBlock? =
    if (this is CitizenSnapshotBlocks) get(x, y, z) else get(CivPosition(x, y, z))

/** Candidate flags stay exact; only ordinary standing candidates can share this job's cell slot. */
internal fun Map<CivPosition, CitizenTerrainBlock>.searchCandidateNode(x: Int, y: Double, z: Int,
    climbing: Boolean): CitizenSearchNode =
    if (!climbing && this is CitizenSnapshotBlocks) standingNode(x, y, z) else CitizenSearchNode(x, y, z, climbing)

/** Fixed x/z/y order; overlapping probes share immutable cells from this route only. */
private class CitizenSnapshotBlocks(
    private val cells: Array<CitizenSnapshotCell>,
) : AbstractMap<CivPosition, CitizenTerrainBlock>() {
    private val origin = cells.firstOrNull()?.key
    private val rows = cells.size / 9
    override val size: Int get() = cells.size

    override fun get(key: CivPosition): CitizenTerrainBlock? = get(key.x, key.y, key.z)

    fun get(worldX: Int, worldY: Int, worldZ: Int): CitizenTerrainBlock? {
        val index = indexOf(worldX, worldY, worldZ)
        return if (index < 0) null else cells[index].value
    }

    fun standingNode(worldX: Int, worldY: Double, worldZ: Int): CitizenSearchNode {
        val index = indexOf(worldX, floor(worldY + .001).toInt(), worldZ)
        return if (index < 0) CitizenSearchNode(worldX, worldY, worldZ) else cells[index].standingNode(worldY)
    }

    fun key(worldX: Int, worldY: Int, worldZ: Int): CivPosition {
        val index = indexOf(worldX, worldY, worldZ)
        return if (index < 0) CivPosition(worldX, worldY, worldZ) else cells[index].key
    }

    private fun indexOf(worldX: Int, worldY: Int, worldZ: Int): Int {
        val first = origin ?: return -1
        val x = worldX.toLong() - first.x
        val y = worldY.toLong() - first.y
        val z = worldZ.toLong() - first.z
        if (x !in 0L..2L || y < 0 || y >= rows || z !in 0L..2L) return -1
        return (x.toInt() * 3 + z.toInt()) * rows + y.toInt()
    }

    override fun containsKey(key: CivPosition): Boolean = get(key) != null

    override val entries: Set<Map.Entry<CivPosition, CitizenTerrainBlock>> = object : AbstractSet<Map.Entry<CivPosition, CitizenTerrainBlock>>() {
        override val size: Int get() = cells.size
        override fun iterator(): Iterator<Map.Entry<CivPosition, CitizenTerrainBlock>> = object : Iterator<Map.Entry<CivPosition, CitizenTerrainBlock>> {
            private var next = 0
            override fun hasNext(): Boolean = next < cells.size
            override fun next(): Map.Entry<CivPosition, CitizenTerrainBlock> {
                if (!hasNext()) throw NoSuchElementException()
                val index = next++
                return cells[index]
            }
        }
    }

    /** Copy references only when needed; an older published capture never observes later cost knowledge. */
    fun withCosts(resolve: (CitizenSnapshotCell) -> CitizenSnapshotCell): CitizenSnapshotBlocks {
        var resolved: Array<CitizenSnapshotCell>? = null
        for (index in cells.indices) {
            val cell = resolve(cells[index])
            if (cell !== cells[index]) {
                if (resolved == null) resolved = cells.copyOf()
                resolved[index] = cell
            }
        }
        return resolved?.let(::CitizenSnapshotBlocks) ?: this
    }
}

/** Whole chunks are processed on the worker. Main resolves palette exceptions and caller-native costs. */
internal class CitizenSnapshotCapture @JvmOverloads constructor(
    probes: List<CitizenSearchProbe>,
    private val terrain: CitizenSnapshotTerrain,
    private val policy: CitizenSnapshotPolicy,
    private val minimumY: Int,
    private val maximumY: Int,
    private val maximumBlocks: Int,
    private val maximumProbes: Int,
    private val connectedPrefetch: Boolean,
    private val cancelled: AtomicBoolean = AtomicBoolean(),
) {
    private data class CapturedProbe(val probe: CitizenSearchProbe, val blocks: CitizenSnapshotBlocks,
        val edges: List<CitizenSearchEdge>, val published: CitizenSearchCapture? = null, val deferredGrapple: Boolean = false)
    private val probes = probes.toMutableList()
    private val requestedCount = probes.size
    private val prefetch = probes.none { it.incoming || it.node.initial }
    private val seen = probes.mapTo(hashSetOf()) { it.node }
    private val completed = mutableListOf<CapturedProbe>()
    private val requiredCosts = hashSetOf<CivPosition>()
    private val newSections = linkedMapOf<CitizenSnapshotSectionKey, Long>()
    private var probeStates = arrayOfNulls<CitizenSnapshotState>(3 * 3 * 7)
    private var probePositions = arrayOfNulls<CivPosition>(3 * 3 * 7)
    private var grappleRows: IntRange? = null
    private val materialAt: (CivPosition) -> Material? = ::material
    private var next = 0

    fun cancel() { cancelled.set(true) }

    fun following(probes: List<CitizenSearchProbe>) = CitizenSnapshotCapture(probes, terrain, policy, minimumY,
        maximumY, maximumBlocks, maximumProbes, connectedPrefetch, cancelled)

    /** Called only after this cursor's worker has terminated; no native state is read. */
    internal fun debugCursor() = CitizenNavigationDebugCursor(requestedCount, next, probes.toList())

    @JvmOverloads
    fun advance(input: CitizenSnapshotInput,
                captured: ((CitizenSearchProbe) -> CitizenSearchCapture?)? = null): CitizenSnapshotRequest {
        try {
            checkCancelled()
            for ((key, chunk) in input.chunks) {
                chunk.prepare()
                // A direct test fixture can supply an undecoded handoff; production inputs name their sections.
                if (chunk.retainsNativeSnapshot && chunk.payloads().isEmpty()) probes.getOrNull(next)?.let { probe ->
                    val y = floor(probe.node.y).toInt()
                    val rows = grappleRows ?: (y - 3..y + 3)
                    if (rows.first < maximumY && rows.last >= minimumY) chunk.states(rows.first, rows.last)
                }
                extracted.putAll(chunk.drainExtracted())
                terrain.merge(key, chunk)
            }
            return advancePrepared(input, captured)
        } catch (missing: CitizenSnapshotSectionMissing) {
            return request(chunks = setOf(CitizenSnapshotKey(missing.section.x, missing.section.z)),
                requestedSections = setOf(missing.section))
        } finally {
            // Futures and route inputs can outlive a worker slice, but never retain its whole native column copy.
            input.chunks.values.forEach(CitizenSnapshotChunk::releaseSource)
            terrain.chunks.values.forEach(CitizenSnapshotChunk::releaseSource)
        }
    }

    private val extracted = linkedMapOf<CitizenSnapshotSectionKey, CitizenSnapshotSection>()

    private fun advancePrepared(input: CitizenSnapshotInput,
        captured: ((CitizenSearchProbe) -> CitizenSearchCapture?)?): CitizenSnapshotRequest {
        terrain.seed(input.seeded)
        terrain.unavailable(input.unavailable)
        terrain.states.putAll(input.states)
        terrain.dynamics.putAll(input.dynamics)
        terrain.costs.putAll(input.costs)
        while (next < probes.size) {
            checkCancelled()
            val probe = probes[next]
            val previous = captured?.invoke(probe)
            val previousBlocks = previous?.blocks
            if (previous != null && previousBlocks is CitizenSnapshotBlocks) {
                // Keep this queue slot and its current bounds: skipping it would change connected prefetch.
                completed += CapturedProbe(probe, previousBlocks, previous.edges, previous)
                prefetch(probe, previous.edges)
                next++
                continue
            }
            val y = floor(probe.node.y).toInt()
            val xs = probe.node.x - 1..probe.node.x + 1
            val zs = probe.node.z - 1..probe.node.z + 1
            val ys = grappleRows ?: (y - 3..y + 3)
            val chunkKeys = if (xs.isEmpty() || zs.isEmpty() || ys.isEmpty()) emptyList() else buildList(4) {
                for (cx in (xs.first shr 4)..(xs.last shr 4))
                    for (cz in (zs.first shr 4)..(zs.last shr 4)) add(CitizenSnapshotKey(cx, cz))
            }
            var missing: MutableSet<CitizenSnapshotSectionKey>? = null
            var firstUse: MutableList<CitizenSnapshotSectionKey>? = null
            if (minimumY < maximumY && ys.first < maximumY && ys.last >= minimumY) {
                for (key in chunkKeys) {
                    if (key in terrain.unavailable) continue
                    for (sy in (maxOf(ys.first, minimumY) shr 4)..(minOf(ys.last, maximumY - 1) shr 4)) {
                        val section = CitizenSnapshotSectionKey(key.x, sy, key.z)
                        if (section in terrain.sections) continue
                        terrain.activate(section, minimumY, maximumY)
                        if (terrain.chunks[key]?.available(sy) != true) {
                            (missing ?: linkedSetOf<CitizenSnapshotSectionKey>().also { missing = it }).add(section)
                        } else (firstUse ?: arrayListOf<CitizenSnapshotSectionKey>().also { firstUse = it }).add(section)
                    }
                }
            }
            missing?.let { return request(
                chunks = it.mapTo(linkedSetOf()) { section -> CitizenSnapshotKey(section.x, section.z) }, requestedSections = it) }
            // Match the existing handoff: publish no new section stamps until this probe has every required section.
            firstUse?.forEach { section ->
                val revision = terrain.chunks.getValue(CitizenSnapshotKey(section.x, section.z)).stamp(section.y)
                terrain.sections[section] = revision
                newSections[section] = revision
            }
            var missingStates: MutableMap<BlockData, CivPosition>? = null
            for (key in chunkKeys) {
                terrain.chunks[key]?.states(ys.first, ys.last)?.let { missingStates = terrain.collectMissingStates(it, missingStates) }
            }
            missingStates?.let { return request(states = it) }
            val count = if (xs.isEmpty() || zs.isEmpty() || ys.isEmpty()) 0 else 9 * (ys.last - ys.first + 1)
            if (count > probeStates.size) {
                probeStates = arrayOfNulls(count)
                probePositions = arrayOfNulls(count)
            }
            val cells = arrayOfNulls<CitizenSnapshotCell>(count)
            var dynamic: MutableMap<CivPosition, BlockData>? = null
            var positionIndex = 0
            for (x in xs) for (z in zs) {
                var chunk: CitizenSnapshotChunk? = null
                var chunkResolved = false
                for (by in ys) {
                    val index = positionIndex++
                    val cached = terrain.blocks.cell(x, by, z)
                    if (cached != null) { cells[index] = cached; continue }
                    val point = CivPosition(x, by, z)
                    probePositions[index] = point
                    val cell = if (by in minimumY until maximumY) {
                        if (!chunkResolved) {
                            chunk = terrain.chunks[CitizenSnapshotKey(x shr 4, z shr 4)]
                            chunkResolved = true
                        }
                        chunk?.passability(point)
                    } else null
                    val state = cell?.state(terrain.states)
                    probeStates[index] = state
                    if (cell != null && state?.dynamic == true && point !in terrain.dynamics) {
                        if (dynamic == null) dynamic = linkedMapOf()
                        dynamic[point] = cell.data
                    }
                }
            }
            dynamic?.let { return request(dynamics = it) }
            for (index in cells.indices) {
                if (cells[index] != null) continue
                val point = requireNotNull(probePositions[index])
                if (terrain.blocks.size >= maximumBlocks) {
                    if (next < requestedCount) throw CitizenNavigationCaptureLimit()
                    next = probes.size
                    return finish()
                }
                val state = probeStates[index]
                val block = if (state == null) unavailableSnapshotBlock else {
                    state.block(policy.allowed(point, materialAt),
                        if (state.dynamic) terrain.dynamics.getValue(point) else state.shape.boxes)
                }
                cells[index] = terrain.blocks.add(point, block)
            }
            val blocks = CitizenSnapshotBlocks(cells.requireNoNulls())
            var deferredGrapple = false
            if (probe.specialTraversal) {
                val rows = citizenGrappleRows(probe, blocks) { point ->
                    policy.traversalAllowed(point) && policy.supportCost?.let { cost ->
                        blockData(point)?.let { cost(point, it).isFinite() }
                    } != false
                }
                if (rows != null && rows != grappleRows) {
                    if (next < requestedCount || rows.last - rows.first < 10) { grappleRows = rows; continue }
                    deferredGrapple = true
                }
            }
            grappleRows = null
            val edges = citizenSearchCandidates(probe, blocks, minimumY, maximumY, policy.stepAllowed)
            edges.forEach { edge -> requiredCosts += blocks.key(edge.node.x, citizenSupportY(edge.node.y), edge.node.z) }
            completed += CapturedProbe(probe, blocks, edges, deferredGrapple = deferredGrapple)
            prefetch(probe, edges)
            next++
        }
        return finish()
    }

    private fun prefetch(probe: CitizenSearchProbe, edges: List<CitizenSearchEdge>) {
        if (prefetch && !probe.refiningGrapple && (next < requestedCount || connectedPrefetch)) for (edge in edges) {
            if (probes.size >= maximumProbes) break
            if (!edge.permitted) continue
            val point = edge.node.point()
            if (probe.corridor?.contains(point) != false && probe.bounds?.contains(point) != false && seen.add(edge.node))
                probes += CitizenSearchProbe(edge.node, point, corridor = probe.corridor, bounds = probe.bounds,
                    specialTraversal = probe.specialTraversal)
        }
    }

    private fun finish(): CitizenSnapshotRequest {
        val missing = linkedSetOf<CivPosition>()
        val supportCost = policy.supportCost
        for (point in requiredCosts) {
            if (point in terrain.costs) continue
            val cost = supportCost?.let { resolve -> blockData(point, copyCached = true)?.let { resolve(point, it) } }
            if (cost == null) missing += point else terrain.costs[point] = cost
        }
        if (missing.isNotEmpty()) return request(costs = missing.take(512).toSet())
        val resolved = hashMapOf<CivPosition, CitizenSnapshotCell>()
        return request(captures = completed.map { capture ->
            capture.published?.let { return@map it.copy(probe = capture.probe) }
            val blocks = capture.blocks.withCosts { cell ->
                val point = cell.key
                val block = cell.value
                val cost = terrain.costs[point]
                if (cost == null || cost.toBits() == block.cost.toBits()) cell
                else resolved.getOrPut(point) { CitizenSnapshotCell(point, block.copy(cost = cost)) }
            }
            CitizenSearchCapture(capture.probe, blocks, capture.edges, capture.deferredGrapple)
        })
    }

    private fun material(point: CivPosition): Material? = blockData(point)?.material

    private fun blockData(point: CivPosition, copyCached: Boolean = false): BlockData? {
        if (point.y !in minimumY until maximumY) return null
        val chunk = terrain.chunks[CitizenSnapshotKey(point.x shr 4, point.z shr 4)] ?: return null
        val section = CitizenSnapshotSectionKey(point.x shr 4, point.y shr 4, point.z shr 4)
        terrain.activate(section, minimumY, maximumY)
        if (section !in terrain.sections) {
            val revision = chunk.stamp(section.y)
            terrain.sections[section] = revision
            newSections[section] = revision
        }
        return chunk.data(point, copyCached)
    }

    private fun request(chunks: Set<CitizenSnapshotKey> = emptySet(), states: Map<BlockData, CivPosition> = emptyMap(),
        dynamics: Map<CivPosition, BlockData> = emptyMap(), costs: Set<CivPosition> = emptySet(),
        captures: List<CitizenSearchCapture>? = null, requestedSections: Set<CitizenSnapshotSectionKey> = emptySet()): CitizenSnapshotRequest {
        val sections = newSections.toMap()
        newSections.clear()
        terrain.chunks.values.forEach { extracted.putAll(it.drainExtracted()) }
        val decoded = extracted.toMap()
        extracted.clear()
        return CitizenSnapshotRequest(chunks, states, dynamics, costs, captures, terrain.blocks.size, sections,
            requestedSections, decoded, terrain.payloads(decoded.isNotEmpty()))
    }

    private fun checkCancelled() { if (cancelled.get()) throw CancellationException("Navigation snapshot retired") }
}

/** Serial FIFO turns for one detached search. Main owns native reads, section publication and actor decisions. */
internal class CitizenSnapshotSearchBatch(var cursor: CitizenSnapshotCapture, private val search: CitizenNavigationSearch,
    private val combine: Boolean) {
    val completion = CompletableFuture<CitizenSnapshotRequest>()
    @Volatile var completedYields = 0L
        private set
    private var latest: CitizenSearchResult? = null
    private var searchNanos = 0L
    private var yieldToWorker = false
    private var usedSections: MutableMap<CitizenSnapshotSectionKey, Long>? = null
    val sections: Map<CitizenSnapshotSectionKey, Long> get() = usedSections.orEmpty()
    var searchReady = false
        private set
    var capturedBlocks: Int? = null
        private set
    val progress: CitizenSearchResult? get() = latest?.copy(searchNanos = searchNanos)

    fun submit(executor: Executor, first: CitizenSnapshotInput) {
        fun enqueue(input: CitizenSnapshotInput, previous: CitizenSnapshotRequest?) {
            val caller = Thread.currentThread()
            var returned = false
            try {
                executor.execute {
                    try {
                        // Some injected executors run a task inside execute(). Give that caller a bounded turn.
                        val queued = Thread.currentThread() !== caller || returned
                        if (previous != null) cursor = cursor.following(requireNotNull(latest).probes)
                        val request = advance(input)
                        if (yieldToWorker && queued) {
                            completedYields++
                            enqueue(CitizenSnapshotInput(), request)
                        } else completion.complete(request)
                    } catch (failure: Throwable) { completion.completeExceptionally(failure) }
                }
            } catch (failure: RejectedExecutionException) {
                // With a full queue the completed search can still return through its ordinary owner handoff.
                if (previous != null) completion.complete(previous)
                else {
                    input.chunks.values.forEach(CitizenSnapshotChunk::releaseSource)
                    completion.completeExceptionally(failure)
                }
            } finally { returned = true }
        }
        enqueue(first, null)
    }

    private fun advance(first: CitizenSnapshotInput): CitizenSnapshotRequest {
        yieldToWorker = false
        searchReady = false
        val started = System.nanoTime()
        var input = first
        var rounds = 0
        while (true) {
            val request = cursor.advance(input, search::captured)
            if (request.sections.isNotEmpty()) {
                val used = usedSections ?: hashMapOf<CitizenSnapshotSectionKey, Long>().also { usedSections = it }
                used.putAll(request.sections)
            }
            capturedBlocks = request.capturedBlocks
            val captures = request.captures
            if (!combine || captures == null) return request
            val result = search.advance(captures)
            latest = result
            searchNanos += result.searchNanos
            // A single capture/advance pair is indivisible. Between pairs, bound worker occupancy
            // and give other queued residents their turn, even on a completely warm graph.
            val needsOwner = result.probes.isEmpty() || result.earlyWalk != null || result.pickupCandidate != null ||
                request.decoded.isNotEmpty()
            if (needsOwner || ++rounds >= 16 || System.nanoTime() - started >= 4_000_000L) {
                yieldToWorker = !needsOwner
                searchReady = true
                return request
            }
            cursor = cursor.following(result.probes)
            input = CitizenSnapshotInput()
        }
    }
}
