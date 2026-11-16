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
    val cityId: UUID? = null,
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
internal data class CitizenSnapshotState(val shape: CitizenTerrainShape, val dynamic: Boolean) {
    private val permitted = if (dynamic) null else CitizenTerrainBlock(true, shape.liquid, shape.door,
        shape.climbable, shape.hot, true, 1.0, shape.boxes)
    private val denied = permitted?.copy(allowed = false)

    /** Permissions are evaluated per cell; only the resulting immutable unit-cost value is shared. */
    fun block(allowed: Boolean, boxes: List<CitizenTerrainBox>): CitizenTerrainBlock =
        (if (allowed) permitted else denied) ?: CitizenTerrainBlock(true, shape.liquid, shape.door,
            shape.climbable, shape.hot, allowed, 1.0, boxes)
}

/** Immutable 16³ payload. Canonical BlockData never escapes to a mutating caller without cloning. */
internal class CitizenSnapshotSection(val key: CitizenSnapshotSectionKey, val revision: Long, val geometryTick: Long,
    private val cells: ShortArray, private val palette: Array<CitizenSnapshotChunk.Passability>,
    val samples: Map<BlockData, CivPosition>,
) {
    val indexPayloadBytes: Long get() = cells.size.toLong() * Short.SIZE_BYTES
    val paletteSize: Int get() = palette.size
    fun at(point: CivPosition): CitizenSnapshotChunk.Passability =
        palette[cells[((point.y and 15) * 16 + (point.z and 15)) * 16 + (point.x and 15)].toInt()]

    companion object {
        fun decode(snapshot: ChunkSnapshot, minimumY: Int, maximumY: Int, y: Int, revision: Long,
            tick: Long): CitizenSnapshotSection {
            val ids = hashMapOf<BlockData, Int>()
            val palette = arrayListOf<CitizenSnapshotChunk.Passability>()
            val cells = ShortArray(16 * 16 * 16)
            val samples = buildMap {
                for (by in maxOf(minimumY, y * 16) until minOf(maximumY, y * 16 + 16)) {
                    for (z in 0..15) for (x in 0..15) {
                        val data = snapshot.getBlockData(x, by, z)
                        val id = ids.getOrPut(data) {
                            put(data, CivPosition(snapshot.x * 16 + x, by, snapshot.z * 16 + z))
                            palette += CitizenSnapshotChunk.Passability(data)
                            palette.lastIndex
                        }
                        cells[((by and 15) * 16 + z) * 16 + x] = id.toShort()
                    }
                }
            }
            return CitizenSnapshotSection(CitizenSnapshotSectionKey(snapshot.x, y, snapshot.z), revision, tick,
                cells, palette.toTypedArray(), samples)
        }
    }
}

/** A worker handoff, never a persistent cache entry. The native whole-column copy is released after extraction. */
internal class CitizenSnapshotChunk private constructor(val key: CitizenSnapshotKey, val minimumY: Int, val maximumY: Int,
    val revision: Long, private var source: ChunkSnapshot?, private val geometryTick: Long,
    decoded: Map<Int, CitizenSnapshotSection>, private val requested: Set<Int>, private val sectionDecodes: AtomicLong?,
) {
    constructor(snapshot: ChunkSnapshot, minimumY: Int, maximumY: Int, revision: Long = 0,
        previous: CitizenSnapshotChunk? = null, changedSections: Set<Int> = emptySet(), sectionDecodes: AtomicLong? = null,
        geometryTick: Long = 0, requested: Set<Int> = emptySet(),
    ) : this(CitizenSnapshotKey(snapshot.x, snapshot.z), minimumY, maximumY, revision, snapshot, geometryTick,
        previous?.sections?.filterKeys { it !in changedSections }.orEmpty(), requested, sectionDecodes)

    /** All routes using a section share palette state; contextual boxes remain exact per point. */
    class Passability(val data: BlockData) {
        @Volatile private var resolved: CitizenSnapshotState? = null
        fun knownState(): CitizenSnapshotState? = resolved
        fun state(states: Map<BlockData, CitizenSnapshotState>): CitizenSnapshotState =
            resolved ?: states.getValue(data).also { resolved = it }
    }

    private val sections = ConcurrentHashMap(decoded)
    private val extracted = linkedMapOf<CitizenSnapshotSectionKey, CitizenSnapshotSection>()
    private val palettes = hashMapOf<Pair<Int, Int>, Map<BlockData, CivPosition>>()
    val retainedSections: Int get() = sections.size
    val retainsNativeSnapshot: Boolean get() = source != null
    // Test fixtures can inspect their still-owned input before handing it to a capture worker.
    val snapshot: ChunkSnapshot get() = requireNotNull(source) { "Native snapshot was released after extraction" }

    fun prepare() { requested.forEach(::section) }
    fun releaseSource() { source = null }
    fun payloads(): List<CitizenSnapshotSection> = sections.values.toList()
    fun drainExtracted(): Map<CitizenSnapshotSectionKey, CitizenSnapshotSection> = extracted.toMap().also { extracted.clear() }
    fun available(y: Int): Boolean = sections.containsKey(y) || source != null
    fun stamp(y: Int): Long = section(y).revision
    fun merge(other: CitizenSnapshotChunk): CitizenSnapshotChunk {
        sections.putAll(other.sections)
        palettes.clear()
        return this
    }

    fun states(minY: Int, maxY: Int): Map<BlockData, CivPosition> {
        val first = maxOf(minY, minimumY) shr 4
        val last = minOf(maxY, maximumY - 1) shr 4
        if (first == last) return section(first).samples
        return palettes.getOrPut(first to last) { buildMap { for (y in first..last) putAll(section(y).samples) } }
    }
    fun passability(point: CivPosition): Passability = section(point.y shr 4).at(point)
    fun data(point: CivPosition, copyCached: Boolean = false): BlockData =
        passability(point).data.let { if (copyCached) it.clone() else it }

    private fun section(y: Int): CitizenSnapshotSection = sections[y] ?: run {
        val snapshot = source ?: throw CitizenSnapshotSectionMissing(CitizenSnapshotSectionKey(key.x, y, key.z))
        CitizenSnapshotSection.decode(snapshot, minimumY, maximumY, y, revision, geometryTick).also {
            sections[y] = it
            extracted[it.key] = it
            sectionDecodes?.incrementAndGet()
        }
    }

    companion object {
        fun detached(key: CitizenSnapshotKey, minimumY: Int, maximumY: Int,
            sections: Collection<CitizenSnapshotSection>): CitizenSnapshotChunk =
            CitizenSnapshotChunk(key, minimumY, maximumY, sections.maxOfOrNull { it.revision } ?: 0,
                null, 0, sections.associateBy { it.key.y }, emptySet(), null)
    }
}

internal class CitizenSnapshotSectionMissing(val section: CitizenSnapshotSectionKey) : RuntimeException(null, null, false, false)

/** One owner for compact physical sections, city memberships, global palette and native source generations. */
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
    private val maximumSectionsPerCity: Int = 1024,
    private val maximumSections: Int = 8192,
) {
    private data class Key(val world: UUID, val chunk: CitizenSnapshotKey)
    private data class SectionKey(val world: UUID, val section: CitizenSnapshotSectionKey) {
        val chunk: Key get() = Key(world, CitizenSnapshotKey(section.x, section.z))
    }
    private class Cached(val value: CitizenSnapshotSection) { val owners = linkedSetOf<UUID?>() }
    private class City {
        val sections = LinkedHashMap<SectionKey, Unit>(32, .75f, true)
        var hits = 0L; var cold = 0L; var evictions = 0L; var refreshes = 0L
        var warmHits = 0L; var warmRejected = 0L
    }
    private val owner = Thread.currentThread()
    private val contextFree = contextFree ?: CitizenNativeShapeCache::contextFree
    private val fallbackMaximum = (maximumBlocks / 4096).coerceIn(1, 256)
    private val sections = LinkedHashMap<SectionKey, Cached>()
    private val cities = linkedMapOf<UUID?, City>()
    private var warmCities = emptySet<UUID>()
    private val palette = hashMapOf<BlockData, CitizenSnapshotState>()
    private val revisions = hashMapOf<Key, Long>()
    private val revisionTicks = hashMapOf<Key, Long>()
    private val minimumRevisions = hashMapOf<Key, Long>()
    private val sectionChanges = hashMapOf<Key, MutableMap<Int, Long>>()
    private val dirty = linkedSetOf<Key>()
    private val deferredChunks = hashSetOf<Key>()
    private var refresh: ScheduledTask? = null
    private var refreshBatches = 0L
    private var revisionSerial = 0L
    private var cacheHits = 0L
    private var coldCaptures = 0L
    private var knownChunkSectionMissCaptures = 0L
    private var physicalSectionEvictions = 0L
    private var dirtyCaptures = 0L
    private var ttlCaptures = 0L
    private var deferredRecaptures = 0L
    private val sectionDecodes = AtomicLong()
    private var paletteReads = 0L
    private var dynamicReads = 0L
    private var supportCostReads = 0L

    init { require(maximumSectionsPerCity > 0 && maximumSections > 0) }

    fun retainCities(ids: Set<UUID>) {
        checkOwner()
        for (city in cities.keys.filterNotNull().filter { it !in ids }) {
            val scope = cities.remove(city) ?: continue
            for (id in scope.sections.keys) sections[id]?.let { cached ->
                cached.owners -= city
                if (cached.owners.isEmpty()) sections.remove(id)
            }
        }
    }

    fun warmCities(ids: Set<UUID>) { checkOwner(); warmCities = ids.toSet() }
    private fun limit(city: UUID?, warm: Boolean = false): Int =
        (if (city == null) fallbackMaximum else maximumSectionsPerCity).coerceAtMost(
            if (warm) (maximumSections / warmCities.size.coerceAtLeast(1)).coerceAtLeast(1) else maximumSections)

    /** Non-evicting speculative admission. Hot route requests can replace their own LRU data. */
    fun canWarm(city: UUID, world: UUID, key: CitizenSnapshotSectionKey): Boolean {
        checkOwner()
        val id = SectionKey(world, key)
        return id in cities[city]?.sections.orEmpty() ||
            ((cities[city]?.sections?.size ?: 0) < limit(city, true) && (id in sections || sections.size < maximumSections))
    }

    /** Cache-only owner lookup. Never captures native data or decodes a section. */
    fun cached(world: World, wanted: Collection<CitizenSnapshotSectionKey>, tick: Long, city: UUID? = null,
        warm: Boolean = false, touch: Boolean = true): Map<CitizenSnapshotKey, CitizenSnapshotChunk> {
        checkOwner()
        val found = linkedMapOf<CitizenSnapshotKey, MutableList<CitizenSnapshotSection>>()
        for (section in wanted) {
            val key = Key(world.uid, CitizenSnapshotKey(section.x, section.z))
            if (key in dirty || !citizenChunkReady(world, section.x, section.z)) continue
            val id = SectionKey(world.uid, section)
            val cached = sections[id] ?: continue
            if (tick - cached.value.geometryTick >= refreshTicks || !currentSection(key, section.y, cached.value.revision)) continue
            if (touch && !retain(id, cached, city, warm)) continue
            val scope = cities.getValue(city)
            if (warm) scope.warmHits++ else scope.hits++
            found.getOrPut(key.chunk) { arrayListOf() } += cached.value
        }
        if (found.isNotEmpty()) cacheHits++
        return found.mapValues { (key, values) -> CitizenSnapshotChunk.detached(key, world.minHeight, world.maxHeight, values) }
    }

    fun seed(world: World, city: UUID?, tick: Long,
        accepts: (CitizenSnapshotSectionKey) -> Boolean): Map<CitizenSnapshotKey, CitizenSnapshotChunk> {
        checkOwner()
        val wanted = cities[city]?.sections?.keys.orEmpty().asSequence()
            .filter { it.world == world.uid && accepts(it.section) }.map { it.section }.toList()
        return cached(world, wanted, tick, city, touch = false)
    }

    /** One native column copy per call. Only explicitly requested sections leave the worker handoff. */
    fun capture(world: World, key: CitizenSnapshotKey, tick: Long, wanted: Set<Int> = emptySet(),
        city: UUID? = null, warm: Boolean = false): CitizenSnapshotChunk? {
        checkOwner()
        if (!citizenChunkReady(world, key.x, key.z)) return null
        val id = Key(world.uid, key)
        if (id in dirty) throw CitizenSnapshotPending()
        val requested = wanted.map { CitizenSnapshotSectionKey(key.x, it, key.z) }
        val existing = cached(world, requested, tick, city, warm)[key]
        if (wanted.isNotEmpty() && existing?.payloads()?.size == wanted.size) return existing
        val oldTick = revisionTicks[id]
        val expired = oldTick != null && tick - oldTick >= refreshTicks || requested.any {
            sections[SectionKey(world.uid, it)]?.value?.let { value -> tick - value.geometryTick >= refreshTicks } == true
        }
        val previous = revisions[id]
        val revision = previous?.takeIf { !expired } ?: (++revisionSerial).also { revisions[id] = it }
        if (expired) {
            minimumRevisions[id] = revision
            sections.keys.filter { it.chunk == id }.toList().forEach { remove(it, eviction = false) }
        } else minimumRevisions.putIfAbsent(id, revision)
        if (expired) revisionTicks[id] = tick else revisionTicks.putIfAbsent(id, tick)
        val raw = world.getChunkAt(key.x, key.z).getChunkSnapshot(false, false, false, false)
        deferredChunks.remove(id)
        val scope = cities.getOrPut(city) { City() }
        when {
            expired -> { ttlCaptures++; scope.refreshes++ }
            previous != null && oldTick == null -> { dirtyCaptures++; scope.refreshes++ }
            previous != null -> knownChunkSectionMissCaptures++
            else -> { coldCaptures++; scope.cold++ }
        }
        val preserved = existing?.takeUnless { expired }
        return CitizenSnapshotChunk(raw, world.minHeight, world.maxHeight, revision, previous = preserved,
            sectionDecodes = sectionDecodes, geometryTick = tick, requested = wanted)
    }

    /** Old worker replies cannot republish a retired source generation. Values contain no raw ChunkSnapshot. */
    fun publish(world: UUID, values: Collection<CitizenSnapshotSection>, city: UUID? = null, warm: Boolean = false) {
        checkOwner()
        for (value in values) {
            val id = SectionKey(world, value.key)
            if (!currentSection(id.chunk, value.key.y, value.revision)) continue
            val old = sections[id]
            if (old != null && old.value.revision > value.revision) continue
            if (old?.value === value) { retain(id, old, city, warm); continue }
            if (warm && city != null && !canWarm(city, world, value.key)) {
                cities.getOrPut(city) { City() }.warmRejected++; continue
            }
            val cached = Cached(value)
            old?.owners?.let(cached.owners::addAll)
            sections[id] = cached
            retain(id, cached, city, warm)
            while (sections.size > maximumSections) remove(sections.keys.first(), eviction = true)
        }
    }

    private fun retain(id: SectionKey, cached: Cached, city: UUID?, warm: Boolean): Boolean {
        val scope = cities.getOrPut(city) { City() }
        if (id !in scope.sections && warm && scope.sections.size >= limit(city, true)) {
            scope.warmRejected++; return false
        }
        sections.remove(id); sections[id] = cached
        scope.sections[id] = Unit
        cached.owners += city
        while (scope.sections.size > limit(city)) {
            val first = scope.sections.keys.first()
            scope.sections.remove(first); scope.evictions++
            sections[first]?.let { old ->
                old.owners -= city
                if (old.owners.isEmpty()) { sections.remove(first); physicalSectionEvictions++ }
            }
        }
        return true
    }
    fun touched(world: UUID, values: Map<CitizenSnapshotSectionKey, Long>, city: UUID?) {
        checkOwner()
        for ((key, revision) in values) {
            val id = SectionKey(world, key)
            val cached = sections[id] ?: continue
            if (cached.value.revision == revision) retain(id, cached, city, warm = false)
        }
    }
    private fun remove(id: SectionKey, eviction: Boolean) {
        val removed = sections.remove(id) ?: return
        if (eviction) physicalSectionEvictions++
        removed.owners.forEach { owner -> cities[owner]?.let {
            it.sections.remove(id); if (eviction) it.evictions++
        } }
    }
    fun owns(world: UUID, section: CitizenSnapshotSectionKey, city: UUID?): Boolean {
        checkOwner(); return city in sections[SectionKey(world, section)]?.owners.orEmpty()
    }
    fun retainedPayloads(): List<CitizenSnapshotSection> { checkOwner(); return sections.values.map { it.value } }
    fun knownStates(values: Collection<CitizenSnapshotSection>): Map<BlockData, CitizenSnapshotState> {
        checkOwner()
        return buildMap { values.forEach { section -> section.samples.keys.forEach { data -> palette[data]?.let { put(data, it) } } } }
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

    fun refreshObstacles(worldId: UUID, points: List<CivPosition>) {
        checkOwner()
        require(points.size <= 64)
        val world = Bukkit.getWorld(worldId) ?: return
        for (point in points) {
            val key = CitizenSnapshotSectionKey(point.x shr 4, point.y shr 4, point.z shr 4)
            val captured = sections[SectionKey(worldId, key)]?.value ?: continue
            if (point.y !in world.minHeight until world.maxHeight || !citizenChunkReady(world, key.x, key.z)) continue
            if (captured.at(point).data != world.getBlockAt(point.x, point.y, point.z).blockData) changed(worldId, point)
        }
    }
    fun changed(world: UUID, point: CivPosition) {
        checkOwner()
        val x = point.x shr 4; val z = point.z shr 4
        val xs = if (point.x and 15 == 0) x - 1..x else if (point.x and 15 == 15) x..x + 1 else x..x
        val zs = if (point.z and 15 == 0) z - 1..z else if (point.z and 15 == 15) z..z + 1 else z..z
        for (cx in xs) for (cz in zs) {
            val key = Key(world, CitizenSnapshotKey(cx, cz))
            if (key !in revisions) continue
            if (dirty.add(key)) { revisions[key] = ++revisionSerial; revisionTicks.remove(key) }
            val changed = sectionChanges.getOrPut(key) { hashMapOf() }
            for (y in ((point.y - 2) shr 4)..((point.y + 2) shr 4)) changed[y] = revisions.getValue(key)
        }
        if (dirty.isNotEmpty() && refresh == null) refresh = tasks.runLater(2) {
            refresh = null; refreshBatches++
            deferredRecaptures += dirty.count { key -> sections.keys.any { it.chunk == key } && deferredChunks.add(key) }
            dirty.clear()
        }
    }
    private fun currentSection(key: Key, y: Int, revision: Long): Boolean {
        val minimum = minimumRevisions[key] ?: return false
        return key in revisions && revision >= minimum && revision >= (sectionChanges[key]?.get(y) ?: minimum)
    }
    fun current(world: UUID, captured: Map<CitizenSnapshotKey, Long>): Boolean {
        checkOwner()
        return captured.all { (chunk, revision) -> Key(world, chunk).let { it !in dirty && revisions[it] == revision } }
    }
    fun currentSections(world: UUID, captured: Map<CitizenSnapshotSectionKey, Long>): Boolean {
        checkOwner()
        return captured.all { (section, revision) -> currentSection(Key(world, CitizenSnapshotKey(section.x, section.z)), section.y, revision) }
    }
    fun pending(world: UUID, requested: Collection<CitizenSnapshotKey>): Boolean {
        checkOwner(); return requested.any { Key(world, it) in dirty }
    }
    fun removeChunk(world: UUID, x: Int, z: Int) {
        checkOwner()
        var tracked = false
        for (dx in -1..1) for (dz in -1..1) {
            val key = Key(world, CitizenSnapshotKey(x + dx, z + dz))
            if (key !in revisions) continue
            tracked = true
            val revision = ++revisionSerial; revisions[key] = revision; minimumRevisions[key] = revision
            revisionTicks.remove(key); sectionChanges.remove(key); dirty.remove(key); deferredChunks.remove(key)
        }
        // A captured generation can still be in flight without any published section.
        if (!tracked) return
        sections.keys.filter { it.world == world && it.section.x - x in -1..1 && it.section.z - z in -1..1 }
            .forEach { remove(it, eviction = false) }
        val key = Key(world, CitizenSnapshotKey(x, z))
        revisions.remove(key)
        minimumRevisions.remove(key)
    }
    fun removeWorld(world: UUID) {
        checkOwner()
        sections.keys.filter { it.world == world }.toList().forEach { remove(it, eviction = false) }
        revisions.keys.removeIf { it.world == world }; revisionTicks.keys.removeIf { it.world == world }
        minimumRevisions.keys.removeIf { it.world == world }; sectionChanges.keys.removeIf { it.world == world }
        dirty.removeIf { it.world == world }; deferredChunks.removeIf { it.world == world }
    }
    fun clear() {
        checkOwner(); refresh?.cancel(); refresh = null
        sections.clear(); cities.clear(); palette.clear(); revisions.clear(); revisionTicks.clear(); dirty.clear(); deferredChunks.clear()
        minimumRevisions.clear(); sectionChanges.clear(); warmCities = emptySet()
    }
    fun debug(cityId: UUID? = null): Map<String, Any?> {
        checkOwner()
        val selected = cities.filterKeys { cityId == null || it == cityId }
        return mapOf("chunks" to sections.keys.map { it.chunk }.toSet().size, "sections" to sections.size,
            "maximum_sections" to maximumSections, "maximum_sections_per_city" to maximumSectionsPerCity,
            "index_payload_bytes" to sections.values.sumOf { it.value.indexPayloadBytes },
            "index_payload_mib" to sections.values.sumOf { it.value.indexPayloadBytes } / 1048576.0,
            "maximum_index_payload_bytes" to maximumSections.toLong() * 8192,
            "memory_measurement" to "exact-primitive-index-payload; excludes objects, palettes, route cells and JVM overhead",
            "estimated_overhead_bytes" to null, "retained_heap_bytes" to null,
            "native_snapshots_retained_by_cache" to 0,
            "chunk_cache_hits" to cacheHits, "chunk_captures" to (coldCaptures + knownChunkSectionMissCaptures + dirtyCaptures + ttlCaptures),
            "chunk_captures_cold" to coldCaptures,
            "chunk_captures_known_chunk_section_miss" to knownChunkSectionMissCaptures,
            "chunk_captures_lru" to knownChunkSectionMissCaptures,
            "chunk_captures_lru_deprecated" to "alias:chunk_captures_known_chunk_section_miss; includes first requested height",
            "physical_section_evictions" to physicalSectionEvictions,
            "city_evictions_scope" to "logical section memberships",
            "chunk_captures_dirty" to dirtyCaptures, "chunk_captures_ttl" to ttlCaptures,
            "detached_section_decodes" to sectionDecodes.get(), "deferred_native_recaptures" to deferredRecaptures,
            "palette_states" to palette.size, "palette_shape_reads" to paletteReads,
            "dynamic_shape_reads" to dynamicReads, "support_cost_reads" to supportCostReads,
            "tracked_changed_sections" to sectionChanges.values.sumOf { it.size }, "dirty_chunks" to dirty.size,
            "refresh_scheduled" to (refresh != null), "refresh_batches" to refreshBatches,
            "cities_total" to selected.size, "cities_truncated" to (selected.size > 8),
            "cities" to selected.entries.take(8).map { (id, city) ->
                val values = city.sections.keys.mapNotNull { sections[it] }
                mapOf("city" to id, "chunks" to city.sections.keys.map { it.chunk }.toSet().size,
                    "sections" to values.size, "maximum_sections" to limit(id), "warm_sections_limit" to limit(id, true),
                    "index_payload_bytes" to values.sumOf { it.value.indexPayloadBytes },
                    "index_payload_mib" to values.sumOf { it.value.indexPayloadBytes } / 1048576.0,
                    "shared_sections" to values.count { it.owners.size > 1 }, "hits" to city.hits, "cold" to city.cold,
                    "evictions" to city.evictions, "refreshes" to city.refreshes, "warm_hits" to city.warmHits,
                    "warm_capacity_rejections" to city.warmRejected)
            })
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
