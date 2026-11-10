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
import java.util.concurrent.atomic.AtomicLong

/** Explicitly detached caller policy. Callbacks may not retain World, Block or mutable actor state. */
internal data class CitizenSnapshotPolicy(
    val allowed: (CivPosition, (CivPosition) -> Material?) -> Boolean,
    val stepAllowed: (CitizenPoint, CitizenPoint) -> Boolean,
    val reuseKey: CitizenRouteReuseKey? = null,
    val supportCost: ((CivPosition, BlockData) -> Double)? = null,
)

/** Explicit trip cost identity; arbitrary caller closures never become cache keys. */
internal data class CitizenRouteReuseKey private constructor(
    val geometry: CivsPaths.Geometry,
    val allowedBuildings: Set<UUID>,
    val preferred: Set<CivPosition>,
    val preferCourierRoads: Boolean,
    val retainedRoads: CivsPaths.RetainedRoads,
) {
    companion object {
        fun of(trip: CivsPaths.Trip, allowedBuildings: Set<UUID>) = CitizenRouteReuseKey(
            trip.network.geometry.copy(buildings = trip.network.geometry.buildings.toList()),
            allowedBuildings.toSet(), trip.reuseSignature.toSet(), trip.preferCourierRoads, trip.retainedRoads)
    }
}

internal data class CitizenSnapshotKey(val x: Int, val z: Int)
internal data class CitizenSnapshotSectionKey(val x: Int, val y: Int, val z: Int)
internal data class CitizenSnapshotState(val shape: CitizenTerrainShape, val dynamic: Boolean)

/** The chunk copy and compact section palettes are detached; only workers decode sections. */
internal class CitizenSnapshotChunk(val snapshot: ChunkSnapshot, val minimumY: Int, val maximumY: Int, val revision: Long = 0,
    previous: CitizenSnapshotChunk? = null, changedSections: Set<Int> = emptySet(), private val sectionDecodes: AtomicLong? = null,
) {
    /** All routes using a section share palette state; contextual boxes are still captured per point. */
    class Passability(val data: BlockData) {
        @Volatile private var resolved: CitizenSnapshotState? = null
        fun state(states: Map<BlockData, CitizenSnapshotState>): CitizenSnapshotState =
            resolved ?: states.getValue(data).also { resolved = it }
    }

    private class Section(private val cells: ShortArray, private val palette: Array<Passability>,
        val samples: Map<BlockData, CivPosition>) {
        fun at(point: CivPosition): Passability =
            palette[cells[((point.y and 15) * 16 + (point.z and 15)) * 16 + (point.x and 15)].toInt()]
    }

    private val sections: ConcurrentHashMap<Int, Section> = ConcurrentHashMap<Int, Section>().apply {
        previous?.sections?.forEach { (y, section) -> if (y !in changedSections) put(y, section) }
    }
    val retainedSections = sections.size
    private val palettes = ConcurrentHashMap<Int, ConcurrentHashMap<Int, Map<BlockData, CivPosition>>>()

    fun states(minY: Int, maxY: Int): Map<BlockData, CivPosition> {
        val first = maxOf(minY, minimumY) shr 4
        val last = minOf(maxY, maximumY - 1) shr 4
        if (first == last) return section(first).samples
        return palettes.computeIfAbsent(first) { ConcurrentHashMap() }.computeIfAbsent(last) {
            buildMap { for (y in first..last) putAll(section(y).samples) }
        }
    }

    fun passability(point: CivPosition): Passability = sections.getValue(point.y shr 4).at(point)

    // Read already decoded data when present. Owner-thread obstacle readback never decodes a section.
    fun data(point: CivPosition, copyCached: Boolean = false): BlockData {
        val cell = sections[point.y shr 4]?.at(point)
        return if (cell == null) snapshot.getBlockData(point.x and 15, point.y, point.z and 15)
        else if (copyCached) cell.data.clone() else cell.data
    }

    private fun section(y: Int): Section = sections.computeIfAbsent(y) {
        val ids = hashMapOf<BlockData, Int>()
        val palette = arrayListOf<Passability>()
        val cells = ShortArray(16 * 16 * 16)
        val originX = snapshot.x * 16
        val originZ = snapshot.z * 16
        val samples = buildMap {
            for (by in maxOf(minimumY, y * 16) until minOf(maximumY, y * 16 + 16)) {
                for (z in 0..15) for (x in 0..15) {
                    val data = snapshot.getBlockData(x, by, z)
                    val id = ids.getOrPut(data) {
                        put(data, CivPosition(originX + x, by, originZ + z))
                        palette += Passability(data)
                        palette.lastIndex
                    }
                    cells[((by and 15) * 16 + z) * 16 + x] = id.toShort()
                }
            }
        }
        Section(cells, palette.toTypedArray(), samples).also { sectionDecodes?.incrementAndGet() }
    }
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
    private data class Cached(val geometryTick: Long, val chunk: CitizenSnapshotChunk, val stale: Boolean = false)
    private val owner = Thread.currentThread()
    private val contextFree = contextFree ?: CitizenNativeShapeCache::contextFree
    private val maximumChunks = (maximumBlocks / 4096).coerceIn(1, 256)
    private val chunks = LinkedHashMap<Key, Cached>(32, .75f, true)
    private val palette = hashMapOf<BlockData, CitizenSnapshotState>()
    // Revisions retain only chunks requested by navigation, until their native unload.
    private val revisions = hashMapOf<Key, Long>()
    private val revisionTicks = hashMapOf<Key, Long>()
    private val minimumRevisions = hashMapOf<Key, Long>()
    private val sectionChanges = hashMapOf<Key, MutableMap<Int, Long>>()
    private val dirtyRequested = hashSetOf<Key>()
    private val dirty = linkedSetOf<Key>()
    private var refresh: ScheduledTask? = null
    private var refreshBatches = 0L
    private var revisionSerial = 0L
    private var cacheHits = 0L
    private var coldCaptures = 0L
    private var lruCaptures = 0L
    private var dirtyCaptures = 0L
    private var ttlCaptures = 0L
    private var deferredRecaptures = 0L
    private val sectionDecodes = AtomicLong()
    private var retainedSections = 0L
    private var paletteReads = 0L
    private var dynamicReads = 0L
    private var supportCostReads = 0L

    fun capture(world: World, key: CitizenSnapshotKey, tick: Long): CitizenSnapshotChunk? {
        checkOwner()
        if (!citizenChunkReady(world, key.x, key.z)) return null
        val id = Key(world.uid, key)
        if (id in dirty) { dirtyRequested += id; throw CitizenSnapshotPending() }
        val cached = chunks[id]
        cached?.takeIf { !it.stale && tick - it.geometryTick < refreshTicks }?.let {
            cacheHits++
            return it.chunk
        }
        val snapshot = world.getChunkAt(key.x, key.z).getChunkSnapshot(false, false, false, false)
        val previousTick = revisionTicks[id]
        val geometryTick = cached?.geometryTick
        val expired = (previousTick != null && tick - previousTick >= refreshTicks) ||
            (geometryTick != null && tick - geometryTick >= refreshTicks)
        // LRU eviction is not a native terrain change: concurrent routes keep the same generation.
        val revision = revisions[id]?.takeIf { !expired && previousTick != null && tick - previousTick < refreshTicks }
            ?: (++revisionSerial).also { revisions[id] = it; revisionTicks[id] = tick }
        if (expired) minimumRevisions[id] = revision else minimumRevisions.putIfAbsent(id, revision)
        val retained = cached?.takeIf { it.stale && !expired }?.chunk
        val invalidated = retained?.let { previous ->
            sectionChanges[id]?.filterValues { it > previous.revision }?.keys
        }.orEmpty()
        val captured = CitizenSnapshotChunk(snapshot, world.minHeight, world.maxHeight, revision, retained, invalidated, sectionDecodes)
        // Event refreshes cannot indefinitely postpone checking inherited sections against a fresh native snapshot.
        chunks[id] = Cached(geometryTick?.takeIf { captured.retainedSections > 0 } ?: tick, captured)
        // Dirtying an evicted chunk drops its capture tick but retains its changed sections.
        when {
            expired -> ttlCaptures++
            cached?.stale == true || previousTick == null && id in sectionChanges -> dirtyCaptures++
            previousTick != null -> lruCaptures++
            else -> coldCaptures++
        }
        retainedSections += captured.retainedSections
        while (chunks.size > maximumChunks) chunks.remove(chunks.keys.first())
        return captured
    }

    fun state(world: World, data: BlockData, sample: CivPosition): CitizenSnapshotState {
        checkOwner()
        return palette.getOrPut(data) {
            val passage = citizenRouteOpenable(data)
            val closedPassage = passage?.isOpen == false
            val constant = closedPassage || contextFree(data)
            val boxes = if (constant && !closedPassage) {
                paletteReads++
                boxes(data, world, sample)
            } else emptyList()
            val liquid = CitizenNativeShapeCache.liquid(data) ?: world.getBlockAt(sample.x, sample.y, sample.z).let { block ->
                if (block.blockData != data) throw CitizenSnapshotUnavailable()
                block.isLiquid
            }
            CitizenSnapshotState(CitizenTerrainShape(liquid,
                passage != null, Tag.CLIMBABLE.isTagged(data.material),
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
        for (cx in xs) for (cz in zs) {
            val key = Key(world, CitizenSnapshotKey(cx, cz))
            markDirty(key)
            val revision = revisions[key] ?: continue
            val sections = sectionChanges.getOrPut(key) { hashMapOf() }
            for (y in ((point.y - 2) shr 4)..((point.y + 2) shr 4)) sections[y] = revision
        }
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
            val cached = chunks[key]
            if (cached != null && !cached.stale) {
                chunks[key] = cached.copy(stale = true)
                if (key !in dirtyRequested) deferredRecaptures++
            }
            if (!dirtyRequested.remove(key)) continue
            val world = Bukkit.getWorld(key.world) ?: continue
            capture(world, key.chunk, Bukkit.getCurrentTick().toLong())
        }
    }

    fun current(world: UUID, captured: Map<CitizenSnapshotKey, Long>): Boolean {
        checkOwner()
        return captured.all { (chunk, revision) -> Key(world, chunk).let { it !in dirty && revisions[it] == revision } }
    }
    /** A whole snapshot can remain valid for an untouched section while another floor is edited. */
    fun currentSections(world: UUID, captured: Map<CitizenSnapshotSectionKey, Long>): Boolean {
        checkOwner()
        return captured.all { (section, revision) ->
            val key = Key(world, CitizenSnapshotKey(section.x, section.z))
            val minimum = minimumRevisions[key]
            minimum != null && key in revisions && revision >= minimum &&
                revision >= (sectionChanges[key]?.get(section.y) ?: minimum)
        }
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
            if (key in revisions) {
                val revision = ++revisionSerial
                revisions[key] = revision
                minimumRevisions[key] = revision
            }
            revisionTicks.remove(key)
            sectionChanges.remove(key)
            dirty.remove(key)
            dirtyRequested.remove(key)
        }
        revisions.remove(Key(world, CitizenSnapshotKey(x, z)))
        minimumRevisions.remove(Key(world, CitizenSnapshotKey(x, z)))
    }
    fun removeWorld(world: UUID) {
        checkOwner()
        chunks.keys.removeIf { it.world == world }
        revisions.keys.removeIf { it.world == world }
        revisionTicks.keys.removeIf { it.world == world }
        minimumRevisions.keys.removeIf { it.world == world }
        sectionChanges.keys.removeIf { it.world == world }
        dirtyRequested.removeIf { it.world == world }
        dirty.removeIf { it.world == world }
    }
    fun clear() {
        checkOwner()
        refresh?.cancel(); refresh = null
        chunks.clear(); palette.clear(); revisions.clear(); revisionTicks.clear(); dirty.clear(); dirtyRequested.clear()
        minimumRevisions.clear(); sectionChanges.clear()
    }
    fun debug(): Map<String, Any> {
        checkOwner()
        return mapOf("chunks" to chunks.size, "maximum_chunks" to maximumChunks, "chunk_cache_hits" to cacheHits,
            "chunk_captures" to (coldCaptures + lruCaptures + dirtyCaptures + ttlCaptures),
            "chunk_captures_cold" to coldCaptures, "chunk_captures_lru" to lruCaptures,
            "chunk_captures_dirty" to dirtyCaptures, "chunk_captures_ttl" to ttlCaptures,
            "detached_section_decodes" to sectionDecodes.get(), "retained_sections_on_refresh" to retainedSections,
            "deferred_native_recaptures" to deferredRecaptures,
            "palette_states" to palette.size, "palette_shape_reads" to paletteReads,
            "dynamic_shape_reads" to dynamicReads, "support_cost_reads" to supportCostReads,
            "tracked_changed_sections" to sectionChanges.values.sumOf { it.size },
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
