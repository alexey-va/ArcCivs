package ru.arc.civs

import org.bukkit.Chunk
import org.bukkit.Server
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import java.util.UUID

/** Main-thread, loaded-chunk index. Inventories and their contents are never retained here. */
internal class CivsContainerIndex(
    private val server: Server,
    private val tileEntities: (Chunk) -> Array<BlockState>,
) {
    private data class ChunkKey(val world: UUID, val x: Int, val z: Int)
    private data class Geometry(val world: UUID, val bounds: List<CivBox>, val controls: List<CivPosition>, val registered: List<CivPosition>) {
        fun intersects(chunk: ChunkKey): Boolean = world == chunk.world && (bounds.any {
            chunk.x in (it.min.x shr 4)..(it.max.x shr 4) && chunk.z in (it.min.z shr 4)..(it.max.z shr 4)
        } || (controls + registered).any { (it.x shr 4) == chunk.x && (it.z shr 4) == chunk.z })
        fun contains(position: CivPosition): Boolean = bounds.any { it.contains(position.x, position.y, position.z) }
    }
    private class Entry(val geometry: Geometry) {
        val chunks = linkedSetOf<ChunkKey>()
        var positions: Map<CivPosition, String?> = emptyMap()
    }
    private val entries = linkedMapOf<UUID, Entry>()
    private val chunks = mutableMapOf<ChunkKey, MutableMap<CivPosition, String>>()
    private val subscribers = mutableMapOf<ChunkKey, MutableSet<UUID>>()

    fun update(city: Settlement) {
        val geometry = Geometry(city.worldId, city.buildings.map(CivBuilding::bounds),
            city.buildings.mapNotNull(CivBuilding::control), city.inventory?.containers.orEmpty())
        if (entries[city.id]?.geometry == geometry) return
        val cause = if (city.id in entries) "geometry" else "register"
        detach(city.id)
        val entry = Entry(geometry)
        entries[city.id] = entry
        server.getWorld(city.worldId)?.loadedChunks?.forEach { chunk ->
            val key = key(chunk)
            if (geometry.intersects(key)) {
                entry.chunks += key
                subscribers.getOrPut(key, ::linkedSetOf) += city.id
                if (key !in chunks) chunks[key] = read(chunk)
            }
        }
        rebuild(entry)
        diagnostic(city.id, entry, cause, entry.chunks.size)
    }

    fun positions(city: Settlement): Map<CivPosition, String?> {
        update(city)
        return entries.getValue(city.id).positions
    }

    /** Contents events start from chunk membership, never a scan across every city. */
    fun citiesAt(worldId: UUID, position: CivPosition): Set<UUID> =
        citiesInChunk(worldId, position.x shr 4, position.z shr 4).filterTo(linkedSetOf()) { id ->
            entries[id]?.let { it.geometry.contains(position) || position in it.positions } == true
        }

    fun citiesInChunk(worldId: UUID, x: Int, z: Int): Set<UUID> =
        subscribers[ChunkKey(worldId, x, z)].orEmpty().toSet()

    fun replace(cities: Collection<Settlement>) {
        // Explicit reload is also the resync boundary for external native edits that do not emit block events.
        clear()
        cities.forEach(::update)
    }

    fun remove(cityId: UUID) {
        entries[cityId]?.let { diagnostic(cityId, it, "remove", it.chunks.size) }
        detach(cityId)
    }

    private fun detach(cityId: UUID) {
        val entry = entries.remove(cityId) ?: return
        entry.chunks.forEach { key ->
            subscribers[key]?.let { ids ->
                ids -= cityId
                if (ids.isEmpty()) {
                    subscribers.remove(key)
                    chunks.remove(key)
                }
            }
        }
    }

    /** Native pastes bypass block events. Refresh only loaded chunks touching the changed geometry. */
    fun refresh(city: Settlement, building: CivBuilding?) {
        update(city)
        val entry = entries.getValue(city.id)
        val world = server.getWorld(city.worldId) ?: return
        val bounds = building?.bounds
        var scanned = 0
        entry.chunks.toList().forEach { key ->
            if (bounds != null && (key.x !in (bounds.min.x shr 4)..(bounds.max.x shr 4) ||
                    key.z !in (bounds.min.z shr 4)..(bounds.max.z shr 4))) return@forEach
            if (!world.isChunkLoaded(key.x, key.z)) unload(key) else {
                chunks[key] = read(world.getChunkAt(key.x, key.z))
                scanned++
                rebuild(key)
            }
        }
        diagnostic(city.id, entry, "paste", scanned, building?.id)
    }

    fun load(chunk: Chunk) {
        val key = key(chunk)
        val ids = entries.filterValues { it.geometry.intersects(key) }.keys
        if (ids.isEmpty()) return
        chunks[key] = read(chunk)
        subscribers[key] = ids.toMutableSet()
        ids.forEach { id -> entries.getValue(id).let { it.chunks += key; rebuild(it); diagnostic(id, it, "chunk_load", 1) } }
    }

    fun unload(chunk: Chunk) = unload(key(chunk))

    fun unloadWorld(world: UUID) = chunks.keys.filter { it.world == world }.forEach(::unload)

    fun changed(block: Block) {
        val key = ChunkKey(block.world.uid, block.x shr 4, block.z shr 4)
        val indexed = chunks[key] ?: return
        val position = CivPosition(block.x, block.y, block.z)
        val type = block.type.name.takeIf { it in CIVS_STORAGE_TYPES }
        if (indexed[position] == type) return
        if (type == null) indexed.remove(position) else indexed[position] = type
        rebuild(key)
    }

    /** Break/explosion events run before the native block disappears. */
    fun removed(block: Block) {
        val key = ChunkKey(block.world.uid, block.x shr 4, block.z shr 4)
        if (chunks[key]?.remove(CivPosition(block.x, block.y, block.z)) != null) rebuild(key)
    }

    fun clear() { entries.clear(); chunks.clear(); subscribers.clear() }

    private fun unload(key: ChunkKey) {
        chunks.remove(key)
        subscribers.remove(key)?.forEach { id -> entries[id]?.let { it.chunks -= key; rebuild(it); diagnostic(id, it, "chunk_unload", 1) } }
    }

    private fun read(chunk: Chunk): MutableMap<CivPosition, String> = tileEntities(chunk).asSequence()
        .filter { it.type.name in CIVS_STORAGE_TYPES }
        .associateTo(linkedMapOf()) { CivPosition(it.x, it.y, it.z) to it.type.name }

    private fun rebuild(key: ChunkKey) { subscribers[key]?.forEach { entries[it]?.let(::rebuild) } }

    private fun rebuild(entry: Entry) {
        val positions = linkedMapOf<CivPosition, String?>()
        entry.geometry.controls.forEach { positions[it] = "BARREL" }
        entry.chunks.asSequence().flatMap { chunks[it].orEmpty().asSequence() }
            .filter { entry.geometry.contains(it.key) }.sortedWith(compareBy({ it.key.x }, { it.key.y }, { it.key.z }))
            .forEach { positions.putIfAbsent(it.key, it.value) }
        entry.geometry.registered.forEach { if (it !in positions) positions[it] = null }
        entry.positions = positions.toMap()
    }

    private fun key(chunk: Chunk) = ChunkKey(chunk.world.uid, chunk.x, chunk.z)

    private fun diagnostic(cityId: UUID, entry: Entry, cause: String, chunkCount: Int, buildingId: UUID? = null) =
        CivsDiagnostics.event("storage_index", "city" to cityId, "building" to buildingId,
            "world" to entry.geometry.world, "cause" to cause, "chunks" to chunkCount,
            "containers" to entry.positions.size)
}
