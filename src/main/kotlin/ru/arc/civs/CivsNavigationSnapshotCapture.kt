package ru.arc.civs

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import java.util.AbstractMap.SimpleImmutableEntry
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor

private val unavailableSnapshotBlock = CitizenTerrainBlock.UNAVAILABLE.copy(cost = 1.0)
private typealias SnapshotCell = SimpleImmutableEntry<CivPosition, CitizenTerrainBlock>

/** Replaces this route's cell map; exact X/Z and Y keys do not truncate world coordinates. */
internal class CitizenSnapshotCells : AbstractMap<CivPosition, CitizenTerrainBlock>() {
    private val columns = Long2ObjectOpenHashMap<Int2ObjectOpenHashMap<SnapshotCell>>()
    override var size: Int = 0
        private set

    fun cell(x: Int, y: Int, z: Int): SimpleImmutableEntry<CivPosition, CitizenTerrainBlock>? =
        columns[civsTerrainKey(x, z)]?.get(y)

    override fun get(key: CivPosition): CitizenTerrainBlock? = cell(key.x, key.y, key.z)?.value

    fun add(point: CivPosition, block: CitizenTerrainBlock): SimpleImmutableEntry<CivPosition, CitizenTerrainBlock> {
        val key = civsTerrainKey(point.x, point.z)
        val column = columns[key] ?: Int2ObjectOpenHashMap<SnapshotCell>(8).also { columns.put(key, it) }
        check(!column.containsKey(point.y)) { "Captured navigation cells are immutable" }
        return SnapshotCell(point, block).also { column.put(point.y, it); size++ }
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

    fun collectMissingStates(palette: Map<BlockData, CivPosition>, missing: MutableMap<BlockData, CivPosition>) {
        if (palette in resolvedPalettes) return
        var complete = true
        for (data in palette.keys) {
            if (data in states) continue
            complete = false
            if (missing.size == 256) break
            missing.putIfAbsent(data, palette.getValue(data))
        }
        if (complete) resolvedPalettes.add(palette)
    }
}

internal data class CitizenSnapshotInput(
    val chunks: Map<CitizenSnapshotKey, CitizenSnapshotChunk> = emptyMap(),
    val unavailable: Set<CitizenSnapshotKey> = emptySet(),
    val states: Map<BlockData, CitizenSnapshotState> = emptyMap(),
    val dynamics: Map<CivPosition, List<CitizenTerrainBox>> = emptyMap(),
    val costs: Map<CivPosition, Double> = emptyMap(),
)

internal data class CitizenSnapshotRequest(
    val chunks: Set<CitizenSnapshotKey> = emptySet(),
    val states: Map<BlockData, CivPosition> = emptyMap(),
    val dynamics: Map<CivPosition, BlockData> = emptyMap(),
    val costs: Set<CivPosition> = emptySet(),
    val captures: List<CitizenSearchCapture>? = null,
    val capturedBlocks: Int = 0,
    val sections: Map<CitizenSnapshotSectionKey, Long> = emptyMap(),
)

/** The existing dense neighbourhood needs no temporary key; ordinary maps keep their exact lookup. */
internal operator fun Map<CivPosition, CitizenTerrainBlock>.get(x: Int, y: Int, z: Int): CitizenTerrainBlock? =
    if (this is CitizenSnapshotBlocks) get(x, y, z) else get(CivPosition(x, y, z))

/** Fixed x/z/y order; overlapping probes share immutable cells from this route only. */
private class CitizenSnapshotBlocks(
    private val cells: Array<SnapshotCell>,
) : AbstractMap<CivPosition, CitizenTerrainBlock>() {
    private val origin = cells.firstOrNull()?.key
    override val size: Int get() = cells.size

    override fun get(key: CivPosition): CitizenTerrainBlock? = get(key.x, key.y, key.z)

    fun get(worldX: Int, worldY: Int, worldZ: Int): CitizenTerrainBlock? {
        val index = indexOf(worldX, worldY, worldZ)
        return if (index < 0) null else cells[index].value
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
        if (x !in 0L..2L || y !in 0L..6L || z !in 0L..2L) return -1
        return (x.toInt() * 3 + z.toInt()) * 7 + y.toInt()
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
    fun withCosts(resolve: (SnapshotCell) -> SnapshotCell): CitizenSnapshotBlocks {
        var resolved: Array<SnapshotCell>? = null
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
internal class CitizenSnapshotCapture(
    probes: List<CitizenSearchProbe>,
    private val terrain: CitizenSnapshotTerrain,
    private val policy: CitizenSnapshotPolicy,
    private val minimumY: Int,
    private val maximumY: Int,
    private val maximumBlocks: Int,
    private val maximumProbes: Int,
    private val connectedPrefetch: Boolean,
) {
    private data class CapturedProbe(val probe: CitizenSearchProbe, val blocks: CitizenSnapshotBlocks,
        val edges: List<CitizenSearchEdge>)
    private val probes = probes.toMutableList()
    private val requestedCount = probes.size
    private val prefetch = probes.none { it.incoming || it.node.initial }
    private val seen = probes.mapTo(hashSetOf()) { it.node }
    private val completed = mutableListOf<CapturedProbe>()
    private val requiredCosts = hashSetOf<CivPosition>()
    private val newSections = linkedMapOf<CitizenSnapshotSectionKey, Long>()
    private val probeStates = arrayOfNulls<CitizenSnapshotState>(3 * 3 * 7)
    private val probePositions = arrayOfNulls<CivPosition>(3 * 3 * 7)
    private val cancelled = AtomicBoolean()
    private var next = 0

    fun cancel() { cancelled.set(true) }

    /** Called only after this cursor's worker has terminated; no native state is read. */
    internal fun debugCursor() = CitizenNavigationDebugCursor(requestedCount, next, probes.toList())

    fun advance(input: CitizenSnapshotInput): CitizenSnapshotRequest {
        checkCancelled()
        terrain.chunks.putAll(input.chunks)
        terrain.unavailable += input.unavailable
        terrain.states.putAll(input.states)
        terrain.dynamics.putAll(input.dynamics)
        terrain.costs.putAll(input.costs)
        while (next < probes.size) {
            checkCancelled()
            val probe = probes[next]
            val y = floor(probe.node.y).toInt()
            val xs = probe.node.x - 1..probe.node.x + 1
            val zs = probe.node.z - 1..probe.node.z + 1
            val ys = y - 3..y + 3
            val chunkKeys = if (xs.isEmpty() || zs.isEmpty() || ys.isEmpty()) emptyList() else buildList(4) {
                for (cx in (xs.first shr 4)..(xs.last shr 4))
                    for (cz in (zs.first shr 4)..(zs.last shr 4)) add(CitizenSnapshotKey(cx, cz))
            }
            val missingChunks = if (minimumY < maximumY && y - 3 < maximumY && y + 3 >= minimumY)
                chunkKeys.filterTo(linkedSetOf()) { it !in terrain.chunks && it !in terrain.unavailable }
                else emptySet()
            if (missingChunks.isNotEmpty()) return request(chunks = missingChunks)
            for (key in chunkKeys) {
                val chunk = terrain.chunks[key] ?: continue
                for (sy in (maxOf(y - 3, minimumY) shr 4)..(minOf(y + 3, maximumY - 1) shr 4)) {
                    val section = CitizenSnapshotSectionKey(key.x, sy, key.z)
                    if (section !in terrain.sections) {
                        terrain.sections[section] = chunk.revision
                        newSections[section] = chunk.revision
                    }
                }
            }
            val missingStates = linkedMapOf<BlockData, CivPosition>()
            for (key in chunkKeys) {
                terrain.chunks[key]?.states(ys.first, ys.last)?.let { terrain.collectMissingStates(it, missingStates) }
            }
            if (missingStates.isNotEmpty()) return request(states = missingStates)
            val cells = arrayOfNulls<SnapshotCell>(if (xs.isEmpty() || zs.isEmpty() || ys.isEmpty()) 0 else probeStates.size)
            val dynamic = linkedMapOf<CivPosition, BlockData>()
            var positionIndex = 0
            for (x in xs) for (z in zs) for (by in ys) {
                val index = positionIndex++
                val cached = terrain.blocks.cell(x, by, z)
                if (cached != null) { cells[index] = cached; continue }
                val point = CivPosition(x, by, z)
                probePositions[index] = point
                val chunk = if (point.y in minimumY until maximumY)
                    terrain.chunks[CitizenSnapshotKey(point.x shr 4, point.z shr 4)] else null
                val cell = chunk?.passability(point)
                val state = cell?.state(terrain.states)
                probeStates[index] = state
                if (cell != null && state?.dynamic == true && point !in terrain.dynamics) dynamic[point] = cell.data
            }
            if (dynamic.isNotEmpty()) return request(dynamics = dynamic)
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
                    val shape = state.shape
                    CitizenTerrainBlock(true, shape.liquid, shape.door, shape.climbable, shape.hot,
                        policy.allowed(point, ::material), 1.0,
                        if (state.dynamic) terrain.dynamics.getValue(point) else shape.boxes)
                }
                cells[index] = terrain.blocks.add(point, block)
            }
            val blocks = CitizenSnapshotBlocks(cells.requireNoNulls())
            val edges = citizenSearchCandidates(probe, blocks, minimumY, maximumY).map { edge ->
                val point = edge.node.point()
                val permitted = if (probe.incoming) policy.stepAllowed(point, probe.from)
                    else policy.stepAllowed(probe.from, point)
                if (permitted == edge.permitted) edge else edge.copy(permitted = permitted)
            }
            edges.forEach { edge -> requiredCosts += blocks.key(edge.node.x, citizenSupportY(edge.node.y), edge.node.z) }
            completed += CapturedProbe(probe, blocks, edges)
            if (prefetch && (next < requestedCount || connectedPrefetch)) for (edge in edges) {
                if (probes.size >= maximumProbes) break
                if (!edge.permitted) continue
                val point = edge.node.point()
                if (probe.corridor?.contains(point) != false && probe.bounds?.contains(point) != false && seen.add(edge.node))
                    probes += CitizenSearchProbe(edge.node, point, corridor = probe.corridor, bounds = probe.bounds)
            }
            next++
        }
        return finish()
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
        val resolved = hashMapOf<CivPosition, SnapshotCell>()
        return request(captures = completed.map { capture ->
            val blocks = capture.blocks.withCosts { cell ->
                val point = cell.key
                val block = cell.value
                val cost = terrain.costs[point]
                if (cost == null || cost.toBits() == block.cost.toBits()) cell
                else resolved.getOrPut(point) { SnapshotCell(point, block.copy(cost = cost)) }
            }
            CitizenSearchCapture(capture.probe, blocks, capture.edges)
        })
    }

    private fun material(point: CivPosition): Material? = blockData(point)?.material

    private fun blockData(point: CivPosition, copyCached: Boolean = false): BlockData? =
        if (point.y !in minimumY until maximumY) null
        else terrain.chunks[CitizenSnapshotKey(point.x shr 4, point.z shr 4)]?.data(point, copyCached)

    private fun request(chunks: Set<CitizenSnapshotKey> = emptySet(), states: Map<BlockData, CivPosition> = emptyMap(),
        dynamics: Map<CivPosition, BlockData> = emptyMap(), costs: Set<CivPosition> = emptySet(),
        captures: List<CitizenSearchCapture>? = null): CitizenSnapshotRequest {
        val sections = newSections.toMap()
        newSections.clear()
        return CitizenSnapshotRequest(chunks, states, dynamics, costs, captures, terrain.blocks.size, sections)
    }

    private fun checkCancelled() { if (cancelled.get()) throw CancellationException("Navigation snapshot retired") }
}
