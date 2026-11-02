package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.ChunkSnapshot
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Lightable
import org.bukkit.util.BoundingBox
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.ScheduledTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Explicitly detached caller policy. Neither function may retain World, Block or mutable actor state. */
internal data class CitizenSnapshotPolicy(
    val allowed: (CivPosition, (CivPosition) -> Material?) -> Boolean,
    val stepAllowed: (CitizenPoint, CitizenPoint) -> Boolean,
)

internal data class CitizenSnapshotKey(val x: Int, val z: Int)
internal data class CitizenSnapshotState(val shape: CitizenTerrainShape, val dynamic: Boolean)

/** Paper owns the immutable chunk copy. Palette discovery runs only on the worker. */
internal class CitizenSnapshotChunk(val snapshot: ChunkSnapshot, val minimumY: Int, val maximumY: Int, val revision: Long = 0) {
    private val sections = ConcurrentHashMap<Int, Map<BlockData, CivPosition>>()
    fun states(minY: Int, maxY: Int): Map<BlockData, CivPosition> = buildMap {
        for (section in (maxOf(minY, minimumY) shr 4)..(minOf(maxY, maximumY - 1) shr 4)) {
            putAll(sections.computeIfAbsent(section) {
                buildMap {
                    for (y in maxOf(minimumY, section * 16) until minOf(maximumY, section * 16 + 16))
                        for (z in 0..15) for (x in 0..15) {
                            val data = snapshot.getBlockData(x, y, z)
                            putIfAbsent(data, CivPosition(snapshot.x * 16 + x, y, snapshot.z * 16 + z))
                        }
                }
            })
        }
    }
    fun data(point: CivPosition): BlockData = snapshot.getBlockData(point.x and 15, point.y, point.z and 15)
}

/** Main-thread acquisition/cache only; workers receive snapshots and immutable palette boxes. */
internal class CivsNavigationSnapshots(
    maximumBlocks: Int,
    private val refreshTicks: Int,
    private val tasks: LifecycleTaskScope,
    contextFree: ((BlockData) -> Boolean)? = null,
    private val shape: (BlockData, World, CivPosition) -> Collection<BoundingBox> = { data, world, point ->
        val block = world.getBlockAt(point.x, point.y, point.z)
        (if (block.blockData == data) block.collisionShape else
            data.getCollisionShape(Location(world, point.x.toDouble(), point.y.toDouble(), point.z.toDouble()))).boundingBoxes
    },
) {
    private data class Key(val world: UUID, val chunk: CitizenSnapshotKey)
    private data class Cached(val tick: Long, val chunk: CitizenSnapshotChunk)
    private val owner = Thread.currentThread()
    private val contextFree = contextFree ?: CitizenNativeShapeCache::contextFree
    private val maximumChunks = (maximumBlocks / 4096).coerceIn(1, 256)
    private val chunks = LinkedHashMap<Key, Cached>(32, .75f, true)
    private val palette = hashMapOf<BlockData, CitizenSnapshotState>()
    // Revisions retain only chunks requested by navigation, until their native unload.
    private val revisions = hashMapOf<Key, Long>()
    private val revisionTicks = hashMapOf<Key, Long>()
    private val dirtyRequested = hashSetOf<Key>()
    private val dirty = linkedSetOf<Key>()
    private var refresh: ScheduledTask? = null
    private var refreshBatches = 0L
    private var revisionSerial = 0L
    private var captures = 0L
    private var paletteReads = 0L
    private var dynamicReads = 0L
    private var supportCostReads = 0L

    fun capture(world: World, key: CitizenSnapshotKey, tick: Long): CitizenSnapshotChunk? {
        checkOwner()
        if (!citizenChunkReady(world, key.x, key.z)) return null
        val id = Key(world.uid, key)
        if (id in dirty) { dirtyRequested += id; throw CitizenSnapshotPending() }
        chunks[id]?.takeIf { tick - it.tick < refreshTicks }?.let { return it.chunk }
        val snapshot = world.getChunkAt(key.x, key.z).getChunkSnapshot(false, false, false, false)
        // LRU eviction is not a native terrain change: concurrent routes keep the same generation.
        val revision = revisions[id]?.takeIf { tick - (revisionTicks[id] ?: Long.MIN_VALUE / 2) < refreshTicks }
            ?: (++revisionSerial).also { revisions[id] = it; revisionTicks[id] = tick }
        val captured = CitizenSnapshotChunk(snapshot, world.minHeight, world.maxHeight, revision)
        chunks[id] = Cached(tick, captured)
        captures++
        while (chunks.size > maximumChunks) chunks.remove(chunks.keys.first())
        return captured
    }

    fun state(world: World, data: BlockData, sample: CivPosition): CitizenSnapshotState {
        checkOwner()
        return palette.getOrPut(data) {
            val constant = contextFree(data)
            val boxes = if (constant) {
                paletteReads++
                boxes(data, world, sample)
            } else emptyList()
            val liquid = CitizenNativeShapeCache.liquid(data) ?: world.getBlockAt(sample.x, sample.y, sample.z).let { block ->
                if (block.blockData != data) throw CitizenSnapshotUnavailable()
                block.isLiquid
            }
            CitizenSnapshotState(CitizenTerrainShape(liquid,
                citizenRouteOpenable(data) != null, Tag.CLIMBABLE.isTagged(data.material),
                data.material in setOf(Material.CAMPFIRE, Material.SOUL_CAMPFIRE) && (data as? Lightable)?.isLit == true,
                boxes), dynamic = !constant)
        }
    }

    fun dynamic(world: World, data: BlockData, point: CivPosition): List<CitizenTerrainBox> {
        checkOwner()
        check(citizenChunkReady(world, point.x shr 4, point.z shr 4)) { "Snapshot chunk unloaded" }
        dynamicReads++
        return boxes(data, world, point)
    }

    private fun boxes(data: BlockData, world: World, point: CivPosition): List<CitizenTerrainBox> =
        shape(data, world, point).map { CitizenTerrainBox(it.minX, it.minY, it.minZ, it.maxX, it.maxY, it.maxZ) }

    val shapeReads: Long get() { checkOwner(); return paletteReads + dynamicReads }
    fun supportCosts(count: Int) { checkOwner(); supportCostReads += count }

    /** Failed physical frontiers also discover plugin edits that emit no native block event. */
    fun refreshObstacles(worldId: UUID, points: List<CivPosition>) {
        checkOwner()
        require(points.size <= 64)
        val world = Bukkit.getWorld(worldId) ?: return
        for (point in points) {
            val key = CitizenSnapshotKey(point.x shr 4, point.z shr 4)
            val captured = chunks[Key(worldId, key)]?.chunk ?: continue
            if (point.y !in world.minHeight until world.maxHeight || !citizenChunkReady(world, key.x, key.z)) continue
            // A denied route alone must not restart neighbours using unchanged chunks.
            if (captured.data(point) != world.getBlockAt(point.x, point.y, point.z).blockData)
                changed(worldId, point)
        }
    }

    /** Invalidate immediately, but copy the post-event native state once after the whole batch settles. */
    fun changed(world: UUID, point: CivPosition) {
        checkOwner()
        val x = point.x shr 4
        val z = point.z shr 4
        // Contextual collision shapes can read neighbouring blocks across either chunk edge.
        val xs = if (point.x and 15 == 0) x - 1..x else if (point.x and 15 == 15) x..x + 1 else x..x
        val zs = if (point.z and 15 == 0) z - 1..z else if (point.z and 15 == 15) z..z + 1 else z..z
        for (cx in xs) for (cz in zs) markDirty(Key(world, CitizenSnapshotKey(cx, cz)))
        if (dirty.isNotEmpty() && refresh == null)
            refresh = tasks.runLater(2) { refresh = null; refreshDirty() }
    }

    private fun markDirty(key: Key) {
        if (key !in revisions) return
        if (dirty.add(key)) { revisions[key] = ++revisionSerial; revisionTicks.remove(key) }
    }

    private fun refreshDirty() {
        checkOwner()
        val batch = dirty.toList()
        dirty.clear()
        refreshBatches++
        for (key in batch) {
            val needed = chunks.remove(key) != null || key in dirtyRequested
            dirtyRequested.remove(key)
            if (!needed) continue
            val world = Bukkit.getWorld(key.world) ?: continue
            if (citizenChunkReady(world, key.chunk.x, key.chunk.z)) capture(world, key.chunk, Bukkit.getCurrentTick().toLong())
        }
    }

    fun current(world: UUID, captured: Map<CitizenSnapshotKey, Long>): Boolean {
        checkOwner()
        return captured.all { (chunk, revision) -> Key(world, chunk).let { it !in dirty && revisions[it] == revision } }
    }
    fun pending(world: UUID, requested: Collection<CitizenSnapshotKey>): Boolean {
        checkOwner()
        return requested.any { Key(world, it) in dirty }
    }
    fun removeChunk(world: UUID, x: Int, z: Int) {
        checkOwner()
        // Native unload invalidates retained worker data even if the LRU evicted the chunk copy.
        for (dx in -1..1) for (dz in -1..1) {
            val key = Key(world, CitizenSnapshotKey(x + dx, z + dz))
            chunks.remove(key)
            if (key in revisions) revisions[key] = ++revisionSerial
            revisionTicks.remove(key)
            dirty.remove(key)
            dirtyRequested.remove(key)
        }
        revisions.remove(Key(world, CitizenSnapshotKey(x, z)))
    }
    fun removeWorld(world: UUID) {
        checkOwner()
        chunks.keys.removeIf { it.world == world }
        revisions.keys.removeIf { it.world == world }
        revisionTicks.keys.removeIf { it.world == world }
        dirtyRequested.removeIf { it.world == world }
        dirty.removeIf { it.world == world }
    }
    fun clear() {
        checkOwner()
        refresh?.cancel(); refresh = null
        chunks.clear(); palette.clear(); revisions.clear(); revisionTicks.clear(); dirty.clear(); dirtyRequested.clear()
    }
    fun debug(): Map<String, Any> {
        checkOwner()
        return mapOf("chunks" to chunks.size, "maximum_chunks" to maximumChunks, "chunk_captures" to captures,
            "palette_states" to palette.size, "palette_shape_reads" to paletteReads,
            "dynamic_shape_reads" to dynamicReads, "support_cost_reads" to supportCostReads,
            "dirty_chunks" to dirty.size, "refresh_scheduled" to (refresh != null), "refresh_batches" to refreshBatches)
    }
    private fun checkOwner() = check(Thread.currentThread() === owner) { "Chunk snapshots require their owning thread" }
}

/** Pinned Paper/Purpur 1.21.11: a non-dynamic Block uses BlockStateBase's exact cached collision shape.
 * Public Craft/NMS methods only. Unknown implementations fall through to exact per-position Main capture. */
private object CitizenNativeShapeCache {
    private data class Access(val state: java.lang.reflect.Method, val block: java.lang.reflect.Method,
        val dynamic: java.lang.reflect.Method, val liquid: java.lang.reflect.Method)
    private val access by lazy {
        runCatching {
            check(org.bukkit.Bukkit.getMinecraftVersion() == "1.21.11")
            val craft = Class.forName("org.bukkit.craftbukkit.block.data.CraftBlockData")
            val state = craft.getMethod("getState")
            val block = state.returnType.getMethod("getBlock")
            Access(state, block, block.returnType.getMethod("hasDynamicShape"), state.returnType.getMethod("liquid"))
        }.getOrNull()
    }
    fun contextFree(data: BlockData): Boolean = access?.let { methods ->
        runCatching { methods.dynamic.invoke(methods.block.invoke(methods.state.invoke(data))) == false }.getOrDefault(false)
    } ?: false
    fun liquid(data: BlockData): Boolean? = access?.let { methods ->
        runCatching { methods.liquid.invoke(methods.state.invoke(data)) as Boolean }.getOrNull()
    }
}
