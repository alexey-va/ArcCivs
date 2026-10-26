package ru.arc.civs

import java.util.UUID

/** Native shapes only: route permissions and travel costs are always evaluated for the caller. */
internal data class CitizenTerrainShape(val liquid: Boolean, val door: Boolean, val climbable: Boolean,
    val hot: Boolean, val boxes: List<CitizenTerrainBox>)

/** Sparse, bounded hot chunks. All access stays on the navigation owner's thread. */
internal class CivsNavigationTerrain(private val maximumBlocks: Int, private val refreshTicks: Int) {
    private data class Key(val world: UUID, val x: Int, val z: Int)
    private class Chunk(val capturedAt: Long, val blocks: MutableMap<CivPosition, CitizenTerrainShape> = hashMapOf())
    private val chunks = LinkedHashMap<Key, Chunk>(32, .75f, true)
    private var blocks = 0
    private var hits = 0L
    private var misses = 0L

    fun read(world: UUID, point: CivPosition, tick: Long, capture: () -> CitizenTerrainShape): CitizenTerrainShape {
        val key = Key(world, point.x shr 4, point.z shr 4)
        var chunk = chunks[key]
        if (chunk != null && tick - chunk.capturedAt >= refreshTicks) {
            blocks -= chunk.blocks.size
            chunks.remove(key)
            chunk = null
        }
        if (chunk == null) chunk = Chunk(tick).also { chunks[key] = it }
        chunk.blocks[point]?.let { hits++; return it }
        val shape = capture()
        misses++
        chunk.blocks[point] = shape
        blocks++
        while (blocks > maximumBlocks) {
            val oldest = chunks.entries.iterator()
            blocks -= oldest.next().value.blocks.size
            oldest.remove()
        }
        return shape
    }

    /** Neighbour-dependent fences, walls, stairs and doors share the local invalidation. */
    fun changed(world: UUID, point: CivPosition) {
        for (dx in -1..1) for (dz in -1..1) for (dy in -2..2) {
            val at = CivPosition(point.x + dx, point.y + dy, point.z + dz)
            if (chunks[Key(world, at.x shr 4, at.z shr 4)]?.blocks?.remove(at) != null) blocks--
        }
    }

    fun discard(world: UUID, positions: Collection<CivPosition>) {
        for (at in positions)
            if (chunks[Key(world, at.x shr 4, at.z shr 4)]?.blocks?.remove(at) != null) blocks--
    }

    fun removeChunk(world: UUID, x: Int, z: Int) {
        for (dx in -1..1) for (dz in -1..1)
            blocks -= chunks.remove(Key(world, x + dx, z + dz))?.blocks?.size ?: 0
    }

    fun removeWorld(world: UUID) {
        val iterator = chunks.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.world == world) { blocks -= entry.value.blocks.size; iterator.remove() }
        }
    }

    fun clear() { chunks.clear(); blocks = 0 }
    fun debug(): Map<String, Any> = mapOf("blocks" to blocks, "chunks" to chunks.size, "hits" to hits, "misses" to misses)
}
