package ru.arc.civs

import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.Plugin
import ru.arc.paper.chunk.PaperChunkKey
import ru.arc.paper.chunk.PaperChunkTicketAcquireResult
import ru.arc.paper.chunk.PaperChunkTicketLease
import ru.arc.paper.chunk.PaperChunkTicketRegistry
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Level

internal data class CivsChunkArea(val world: UUID, val minX: Int, val maxX: Int, val minZ: Int, val maxZ: Int) {
    val size: Long get() = (maxX.toLong() - minX + 1) * (maxZ.toLong() - minZ + 1)
    fun keys(): Set<PaperChunkKey> = (minX..maxX).flatMapTo(linkedSetOf()) { x ->
        (minZ..maxZ).map { z -> PaperChunkKey(world, x, z) }
    }

    companion object {
        fun of(city: Settlement) = CivsChunkArea(city.worldId,
            ((city.center.x.toLong() - city.radius) shr 4).toInt() - 1,
            ((city.center.x.toLong() + city.radius) shr 4).toInt() + 1,
            ((city.center.z.toLong() - city.radius) shr 4).toInt() - 1,
            ((city.center.z.toLong() + city.radius) shr 4).toInt() + 1)
    }
}

/** One lifecycle owns admission, asynchronous existing-terrain loads and all native leases. */
internal class CivsChunkLoading(
    private val plugin: Plugin,
    private var maximumChunks: Int,
    private val permissions: CivsChunkPermissions = CivsChunkPermissions(plugin),
    private val registryFactory: () -> PaperChunkTicketRegistry = { PaperChunkTicketRegistry(plugin) },
    private val load: (World, Int, Int) -> CompletableFuture<Chunk?> = { world, x, z -> world.getChunkAtAsync(x, z, false) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
) : Listener, AutoCloseable {
    private class City(val owner: UUID, val worldName: String, val area: CivsChunkArea, val chunks: Set<PaperChunkKey>) {
        var ready = false
    }
    private data class Load(val key: PaperChunkKey, val generation: Long, val present: Boolean, val failure: Throwable?)
    private var registry = registryFactory()
    private val cities = linkedMapOf<UUID, City>()
    private val wanted = linkedMapOf<PaperChunkKey, Int>()
    private val leases = linkedMapOf<PaperChunkKey, PaperChunkTicketLease>()
    private val ready = hashSetOf<PaperChunkKey>()
    private val pending = mutableMapOf<PaperChunkKey, Long>()
    private val replies = ConcurrentLinkedQueue<Load>()
    private val retryAt = mutableMapOf<PaperChunkKey, Long>()
    private val releaseFailures = mutableSetOf<PaperChunkKey>()
    private val unloading = mutableSetOf<UUID>()
    private val statuses = mutableMapOf<UUID, String>()
    private var generation = 0L
    private var admissionCursor = 0
    private var readinessCursor = 0
    private var failed = false
    @Volatile private var closed = false

    init {
        require(maximumChunks in 1..8192)
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    val retainedChunks: Int get() = registry.activeChunkCount
    fun active(cityId: UUID): Boolean = !closed && !failed && cities[cityId]?.let {
        it.ready && permissions.allowed(it.owner, it.worldName)
    } == true

    /** Last observed lifecycle state; no readiness/world reads, provider checks or new tickets. */
    internal fun debug(cityId: UUID?, ownerIds: Set<UUID>? = null): Map<String, Any?> {
        check(org.bukkit.Bukkit.isPrimaryThread())
        val now = clock()
        fun <T> sample(values: Collection<T>, describe: (T) -> Any?): Map<String, Any?> = mapOf(
            "total" to values.size, "truncated" to (values.size > 8), "entries" to values.take(8).map(describe))
        val ids = (cities.keys + statuses.keys).filter { cityId == null || it == cityId }
        val keys = if (cityId == null) wanted.keys + leases.keys + ready + pending.keys + retryAt.keys + releaseFailures
            else ids.flatMapTo(linkedSetOf()) { cities[it]?.chunks.orEmpty() }
        val owners = ownerIds ?: if (cityId == null) null else ids.mapNotNullTo(hashSetOf()) { cities[it]?.owner }
        return sample(ids) { id ->
            val state = cities[id]
            mapOf("city" to id.toString(), "status" to statuses[id], "admitted" to (state != null),
                "readyLastPulse" to state?.ready,
                "activeLastPulse" to (!closed && !failed && state?.ready == true && statuses[id] == "active"),
                "owner" to state?.owner?.toString(), "world" to state?.area?.world?.toString(),
                "requiredChunks" to state?.chunks?.size, "readyChunks" to state?.chunks?.count(ready::contains),
                "leasedChunks" to state?.chunks?.count(leases::containsKey),
                "pendingChunks" to state?.chunks?.count(pending::containsKey))
        } + mapOf("source" to "owner-cache", "freshWorldRead" to false, "closed" to closed, "failed" to failed,
            "generation" to generation, "maximumChunks" to maximumChunks,
            "wantedChunks" to keys.count(wanted::containsKey), "leasedChunks" to keys.count(leases::containsKey),
            "readyChunks" to keys.count(ready::contains), "pendingChunks" to keys.count(pending::containsKey),
            "queuedReplies" to replies.count { cityId == null || it.key in keys },
            "releaseFailures" to keys.count(releaseFailures::contains),
            "chunks" to sample(keys) { key -> mapOf("world" to key.worldId.toString(), "x" to key.x, "z" to key.z,
                "wantedBy" to wanted[key], "leased" to (key in leases), "readyLastPulse" to (key in ready),
                "pendingGeneration" to pending[key], "retryInMillis" to retryAt[key]?.let { (it - now).coerceAtLeast(0) },
                "releaseFailed" to (key in releaseFailures)) },
            "permissions" to permissions.debug(owners))
    }

    fun pulse(settlements: Collection<Settlement>) {
        if (closed || failed) return
        val available = settlements.filter { it.pendingDeletion == null && it.worldId !in unloading && it.settings?.chunkLoading != false }
            .mapNotNull { city -> plugin.server.getWorld(city.worldId)?.let { city to it.name } }
        permissions.pulse(available.map { it.first.owner to it.second })
        val byId = available.associateBy { it.first.id }
        cities.toMap().forEach { (id, state) ->
            val current = byId[id]?.first
            if (current == null || current.owner != state.owner || byId[id]?.second != state.worldName || CivsChunkArea.of(current) != state.area ||
                !permissions.allowed(state.owner, state.worldName)) retire(id)
        }
        statuses.keys.retainAll(byId.keys)
        val candidates = available.filter { (city, name) -> city.id !in cities && permissions.allowed(city.owner, name) }
        if (candidates.isNotEmpty()) {
            val (city, name) = candidates[Math.floorMod(admissionCursor++, candidates.size)]
            val area = CivsChunkArea.of(city)
            if (area.size > maximumChunks) status(city.id, "capacity")
            else {
                val chunks = area.keys()
                if (wanted.size + chunks.count { it !in wanted } > maximumChunks) status(city.id, "capacity")
                else {
                    cities[city.id] = City(city.owner, name, area, chunks)
                    chunks.forEach { wanted[it] = (wanted[it] ?: 0) + 1 }
                    status(city.id, "loading")
                }
            }
        }
        val deadline = nanoTime() + 1_000_000L
        for (key in leases.keys.toList().filter { it !in wanted }.take(32)) {
            if (nanoTime() >= deadline) break
            release(key)
        }
        var acquisitions = 0
        while (true) {
            val reply = replies.peek() ?: break
            if (acquisitions >= 2 || nanoTime() >= deadline) break
            replies.poll()
            if (pending[reply.key] != reply.generation) continue
            pending.remove(reply.key)
            if (reply.generation != generation || reply.key !in wanted) continue
            if (!reply.present || reply.failure != null) {
                retryAt[reply.key] = clock() + 30_000
                cities.filterValues { reply.key in it.chunks }.keys.forEach { status(it, "chunk-unavailable") }
                continue
            }
            if (plugin.server.getWorld(reply.key.worldId)?.isChunkLoaded(reply.key.x, reply.key.z) == true) {
                acquisitions++
                acquire(reply.key)
            }
        }
        retryAt.keys.retainAll(wanted.keys)
        for (key in wanted.keys) {
            if (acquisitions >= 2 || nanoTime() >= deadline) break
            if (key in leases || key in pending || clock() < (retryAt[key] ?: 0)) continue
            val world = plugin.server.getWorld(key.worldId) ?: continue
            if (world.isChunkLoaded(key.x, key.z)) {
                acquisitions++
                acquire(key)
            } else if (pending.size < 4) {
                val epoch = generation
                pending[key] = epoch
                runCatching { load(world, key.x, key.z) }.getOrElse { CompletableFuture.failedFuture(it) }
                    .whenComplete { chunk, error ->
                        if (!closed) replies += Load(key, epoch, chunk != null, error)
                    }
            }
        }
        val retained = leases.keys.toList()
        repeat(minOf(32, retained.size)) {
            if (nanoTime() >= deadline) return@repeat
            val key = retained[Math.floorMod(readinessCursor++, retained.size)]
            val world = plugin.server.getWorld(key.worldId)
            if (key in wanted && world != null && citizenChunkReady(world, key.x, key.z)) ready += key
            else ready -= key
        }
        cities.forEach { (id, state) ->
            val complete = state.chunks.all(ready::contains)
            if (state.ready != complete) { state.ready = complete; status(id, if (complete) "active" else "loading") }
        }
    }

    private fun acquire(key: PaperChunkKey) {
        if (key in leases || registry.activeChunkCount >= maximumChunks) return
        when (val result = registry.acquire(key)) {
            is PaperChunkTicketAcquireResult.Acquired -> leases[key] = result.lease
            else -> {
                retryAt[key] = clock() + 30_000
                if (result is PaperChunkTicketAcquireResult.Failed)
                    plugin.logger.log(Level.WARNING, "ArcCivs could not retain settlement chunk $key", result.failure)
            }
        }
    }

    private fun retire(id: UUID) {
        val city = cities.remove(id) ?: return
        city.chunks.forEach { key ->
            val count = wanted.getValue(key) - 1
            if (count == 0) { wanted.remove(key); ready -= key } else wanted[key] = count
        }
        status(id, "released")
    }

    private fun release(key: PaperChunkKey) {
        if (key in releaseFailures) return
        try { leases.remove(key)?.close(); ready -= key }
        catch (failure: Exception) {
            releaseFailures += key
            plugin.logger.log(Level.SEVERE, "ArcCivs settlement chunk release failed for $key; /civs reload retries cleanup", failure)
        }
    }

    fun reset(limit: Int = maximumChunks) {
        require(limit in 1..8192)
        maximumChunks = limit
        generation++
        permissions.reset()
        cities.clear(); wanted.clear(); ready.clear(); retryAt.clear(); statuses.clear()
        failed = runCatching { registry.close() }.onFailure {
            plugin.logger.log(Level.SEVERE, "ArcCivs settlement chunk cleanup failed; background activity remains stopped", it)
        }.isFailure
        if (!failed) { leases.clear(); releaseFailures.clear(); registry = registryFactory() }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun worldUnloading(event: WorldUnloadEvent) {
        unloading += event.world.uid
        cities.filterValues { it.area.world == event.world.uid }.keys.toList().forEach(::retire)
        leases.keys.filter { it.worldId == event.world.uid }.toList().forEach(::release)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun worldLoaded(event: WorldLoadEvent) { unloading -= event.world.uid }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun chunkUnloading(event: ChunkUnloadEvent) {
        val key = PaperChunkKey(event.world.uid, event.chunk.x, event.chunk.z)
        ready -= key
        cities.values.filter { key in it.chunks }.forEach { it.ready = false }
        release(key)
    }

    private fun status(city: UUID, value: String) {
        if (statuses.put(city, value) != value) CivsDiagnostics.event("settlement_chunks", "city" to city,
            "state" to value, "chunks" to cities[city]?.chunks?.size, "retained" to retainedChunks, "maximum" to maximumChunks)
    }

    override fun close() {
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        permissions.close()
        cities.clear(); wanted.clear(); ready.clear(); replies.clear()
        registry.close()
        leases.clear()
    }
}
