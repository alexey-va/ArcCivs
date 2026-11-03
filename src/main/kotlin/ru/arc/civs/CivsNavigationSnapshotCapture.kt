package ru.arc.civs

import org.bukkit.Material
import org.bukkit.block.data.BlockData
import java.util.AbstractMap.SimpleImmutableEntry
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor

/** One route's worker-owned immutable inputs and derived cells; never exposed as mutable Main state. */
internal class CitizenSnapshotTerrain {
    val chunks = hashMapOf<CitizenSnapshotKey, CitizenSnapshotChunk>()
    val unavailable = hashSetOf<CitizenSnapshotKey>()
    val states = hashMapOf<BlockData, CitizenSnapshotState>()
    val dynamics = hashMapOf<CivPosition, List<CitizenTerrainBox>>()
    val costs = hashMapOf<CivPosition, Double>()
    val blocks = hashMapOf<CivPosition, CitizenTerrainBlock>()
    val sections = hashMapOf<CitizenSnapshotSectionKey, Long>()
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

/** Fixed x/z/y order, with owned immutable keys and cell references instead of sixty-three map nodes. */
private class CitizenSnapshotBlocks(
    private val positions: List<CivPosition>,
    private val cells: Array<CitizenTerrainBlock>,
) : AbstractMap<CivPosition, CitizenTerrainBlock>() {
    private val origin = positions.firstOrNull()
    override val size: Int get() = cells.size

    override fun get(key: CivPosition): CitizenTerrainBlock? {
        val first = origin ?: return null
        val x = key.x.toLong() - first.x
        val y = key.y.toLong() - first.y
        val z = key.z.toLong() - first.z
        if (x !in 0L..2L || y !in 0L..6L || z !in 0L..2L) return null
        return cells[(x.toInt() * 3 + z.toInt()) * 7 + y.toInt()]
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
                return SimpleImmutableEntry(positions[index], cells[index])
            }
        }
    }

    /** Copy references only when needed; an older published capture never observes later cost knowledge. */
    fun withCosts(resolve: (CivPosition, CitizenTerrainBlock) -> CitizenTerrainBlock): CitizenSnapshotBlocks {
        var resolved: Array<CitizenTerrainBlock>? = null
        for (index in cells.indices) {
            val block = resolve(positions[index], cells[index])
            if (block !== cells[index]) {
                if (resolved == null) resolved = cells.copyOf()
                resolved[index] = block
            }
        }
        return resolved?.let { CitizenSnapshotBlocks(positions, it) } ?: this
    }
}

/** Whole chunks are processed on the worker. Main resolves only palette exceptions and support costs. */
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
    private val cancelled = AtomicBoolean()
    private var next = 0

    fun cancel() { cancelled.set(true) }

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
            val positions = buildList {
                for (x in probe.node.x - 1..probe.node.x + 1) for (z in probe.node.z - 1..probe.node.z + 1)
                    for (by in y - 3..y + 3) add(CivPosition(x, by, z))
            }
            val chunkKeys = if (positions.isEmpty()) emptyList() else buildList(4) {
                for (cx in ((probe.node.x - 1) shr 4)..((probe.node.x + 1) shr 4))
                    for (cz in ((probe.node.z - 1) shr 4)..((probe.node.z + 1) shr 4)) add(CitizenSnapshotKey(cx, cz))
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
            // Palette discovery scans a detached whole chunk once, shared by every route using that snapshot.
            val missingStates = linkedMapOf<BlockData, CivPosition>()
            for (key in chunkKeys) {
                terrain.chunks[key]?.states(y - 3, y + 3)?.forEach { (data, sample) ->
                    if (data !in terrain.states && missingStates.size < 256) missingStates.putIfAbsent(data, sample)
                }
            }
            if (missingStates.isNotEmpty()) return request(states = missingStates)
            val cells = Array<CitizenTerrainBlock?>(positions.size) { terrain.blocks[positions[it]] }
            val dynamic = linkedMapOf<CivPosition, BlockData>()
            for (index in positions.indices) {
                val point = positions[index]
                if (cells[index] != null || point.y !in minimumY until maximumY) continue
                val chunk = terrain.chunks[CitizenSnapshotKey(point.x shr 4, point.z shr 4)] ?: continue
                val data = chunk.data(point)
                if (terrain.states.getValue(data).dynamic && point !in terrain.dynamics) dynamic[point] = data
            }
            if (dynamic.isNotEmpty()) return request(dynamics = dynamic)
            for (index in positions.indices) {
                if (cells[index] != null) continue
                val point = positions[index]
                if (terrain.blocks.size >= maximumBlocks) {
                    if (next < requestedCount) throw CitizenNavigationCaptureLimit()
                    next = probes.size
                    return finish()
                }
                val chunk = terrain.chunks[CitizenSnapshotKey(point.x shr 4, point.z shr 4)]
                val block = if (point.y !in minimumY until maximumY || chunk == null) CitizenTerrainBlock.UNAVAILABLE else {
                    val state = terrain.states.getValue(chunk.data(point))
                    val shape = state.shape
                    CitizenTerrainBlock(true, shape.liquid, shape.door, shape.climbable, shape.hot,
                        policy.allowed(point, ::material), 1.0,
                        if (state.dynamic) terrain.dynamics.getValue(point) else shape.boxes)
                }
                terrain.blocks[point] = block
                cells[index] = block
            }
            val blocks = CitizenSnapshotBlocks(positions, cells.requireNoNulls())
            val edges = citizenSearchCandidates(probe, blocks, minimumY, maximumY).map { edge ->
                edge.copy(permitted = if (probe.incoming) policy.stepAllowed(edge.node.point(), probe.from)
                    else policy.stepAllowed(probe.from, edge.node.point()))
            }
            edges.forEach { edge -> requiredCosts += CivPosition(edge.node.x, citizenSupportY(edge.node.y), edge.node.z) }
            completed += CapturedProbe(probe, blocks, edges)
            if (prefetch && (next < requestedCount || connectedPrefetch)) for (edge in edges) {
                if (probes.size >= maximumProbes) break
                if (edge.permitted && probe.corridor?.contains(edge.node.point()) != false &&
                    probe.bounds?.contains(edge.node.point()) != false && seen.add(edge.node))
                    probes += CitizenSearchProbe(edge.node, edge.node.point(), corridor = probe.corridor, bounds = probe.bounds)
            }
            next++
        }
        return finish()
    }

    private fun finish(): CitizenSnapshotRequest {
        val missing = requiredCosts.filterTo(linkedSetOf()) { it !in terrain.costs }
        if (missing.isNotEmpty()) return request(costs = missing.take(512).toSet())
        val resolved = hashMapOf<CivPosition, CitizenTerrainBlock>()
        return request(captures = completed.map { capture ->
            val blocks = capture.blocks.withCosts { point, block ->
                resolved.getOrPut(point) {
                    val cost = terrain.costs[point] ?: 1.0
                    if (cost.toBits() == block.cost.toBits()) block else block.copy(cost = cost)
                }
            }
            CitizenSearchCapture(capture.probe, blocks, capture.edges)
        })
    }

    private fun material(point: CivPosition): Material? = if (point.y !in minimumY until maximumY) null
        else terrain.chunks[CitizenSnapshotKey(point.x shr 4, point.z shr 4)]?.data(point)?.material

    private fun request(chunks: Set<CitizenSnapshotKey> = emptySet(), states: Map<BlockData, CivPosition> = emptyMap(),
        dynamics: Map<CivPosition, BlockData> = emptyMap(), costs: Set<CivPosition> = emptySet(),
        captures: List<CitizenSearchCapture>? = null): CitizenSnapshotRequest {
        val sections = newSections.toMap()
        newSections.clear()
        return CitizenSnapshotRequest(chunks, states, dynamics, costs, captures, terrain.blocks.size, sections)
    }

    private fun checkCancelled() { if (cancelled.get()) throw CancellationException("Navigation snapshot retired") }
}
