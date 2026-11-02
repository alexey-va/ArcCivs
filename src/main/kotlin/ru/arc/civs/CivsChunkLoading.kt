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
    fun union(other: CivsChunkArea): CivsChunkArea {
        require(world == other.world)
        return CivsChunkArea(world, minOf(minX, other.minX), maxOf(maxX, other.maxX), minOf(minZ, other.minZ), maxOf(maxZ, other.maxZ))
    }
    fun keys(): Set<PaperChunkKey> = (minX..maxX).flatMapTo(linkedSetOf()) { x ->
        (minZ..maxZ).map { z -> PaperChunkKey(world, x, z) }
    }

    companion object {
        fun of(city: Settlement) = CivsChunkArea(city.worldId,
            ((city.center.x.toLong() - city.radius) shr 4).toInt() - 1,
            ((city.center.x.toLong() + city.radius) shr 4).toInt() + 1,
            ((city.center.z.toLong() - city.radius) shr 4).toInt() - 1,
            ((city.center.z.toLong() + city.radius) shr 4).toInt() + 1)

        fun around(world: UUID, points: Iterable<CivPosition>, margin: Int = 1): CivsChunkArea? {
            var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE
            var minZ = Int.MAX_VALUE; var maxZ = Int.MIN_VALUE
            for (point in points) {
                minX = minOf(minX, point.x shr 4); maxX = maxOf(maxX, point.x shr 4)
                minZ = minOf(minZ, point.z shr 4); maxZ = maxOf(maxZ, point.z shr 4)
            }
            return if (minX > maxX) null else CivsChunkArea(world, minX - margin, maxX + margin, minZ - margin, maxZ + margin)
        }

        /** Keep the current travel rectangle; unused city expansion land is a temporary survey demand. */
        fun developed(city: Settlement): CivsChunkArea {
            var area = requireNotNull(around(city.worldId, listOf(
                city.center.copy(x = city.center.x - 1, z = city.center.z - 1),
                city.center.copy(x = city.center.x + 1, z = city.center.z + 1))))
            fun points(values: Iterable<CivPosition>) { around(city.worldId, values)?.let { area = area.union(it) } }
            fun building(value: CivBuilding, state: CivMinePlanState?) {
                points(listOfNotNull(value.bounds.min, value.bounds.max, value.entrance, value.control))
                points(CivsWorksiteLayouts.field(value)); points(CivsWorksiteLayouts.irrigation(value))
                CivsWorksiteLayouts.site(value)?.getAsJsonObject("forester")?.getAsJsonObject("searchBounds")?.let { bounds ->
                    val low = CivsWorksiteLayouts.vector(bounds["min"])
                    val high = CivsWorksiteLayouts.vector(bounds["max"])
                    points(listOf(CivsWorksiteLayouts.point(value, low), CivsWorksiteLayouts.point(value, high)))
                }
                if (value.type == BuildingType.QUARRY) {
                    val versions = setOfNotNull(CivsMinePlan.VERSION, state?.version,
                        2.takeIf { state?.v2Step != null }, 3.takeIf { state?.v3Step != null },
                        4.takeIf { state?.v4Step != null }, 5.takeIf { state?.v5Step != null || state?.v5Proof != null },
                        6.takeIf { state?.v6Proof != null })
                    for (version in versions) CivsMinePlan.forBuilding(value, version)?.let { plan ->
                        points(plan.steps.asSequence().map { CivsWorksiteLayouts.point(value, it.point) }.asIterable())
                    }
                }
            }
            city.buildings.forEach { building(it, city.minePlans?.get(it.id.toString())) }
            city.construction?.plannedBuilding?.let { building(it, city.minePlans?.get(it.id.toString())) }
            city.minePlans.orEmpty().values.flatMap { it.levels.orEmpty() }.forEach { building(it.building, it.state()) }
            points(city.inventory?.containers.orEmpty())
            city.pendingControls.orEmpty().forEach { receipt ->
                points(listOfNotNull(receipt.position, receipt.retire?.position))
                points(receipt.approach.orEmpty().map { it.position })
            }
            city.forestryJobs.orEmpty().values.forEach { job ->
                points(listOf(job.root) + job.roots)
                points(job.cells.asSequence().map(job::position).asIterable())
            }
            city.roadRouteOverrides.orEmpty().values.forEach { points(it.route) }
            return area
        }
    }
}

/** One bounded consumer, sharing the existing loader and registry; close is safe after async cancellation. */
internal class CivsChunkDemand internal constructor(internal val owner: UUID, internal val worldId: UUID,
    internal val keys: Set<PaperChunkKey>, internal val generation: Long,
    private val readiness: (CivsChunkDemand) -> Boolean) : AutoCloseable {
    @Volatile internal var closed = false
        private set
    internal var failure: Throwable? = null
    fun ready(): Boolean = readiness(this)
    override fun close() { closed = true }
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
    private class City(val owner: UUID, val worldName: String, var snapshot: Settlement, var base: CivsChunkArea,
        var area: CivsChunkArea, var chunks: Set<PaperChunkKey>) {
        var ready = false
        var required = area
    }
    private data class Load(val key: PaperChunkKey, val generation: Long, val retained: Boolean, val failure: Throwable?)
    private var registry = registryFactory()
    private val cities = linkedMapOf<UUID, City>()
    private val wanted = linkedMapOf<PaperChunkKey, Int>()
    private var recoveryKeys = emptySet<PaperChunkKey>()
    private val temporary = linkedSetOf<CivsChunkDemand>()
    private val leases = linkedMapOf<PaperChunkKey, PaperChunkTicketLease>()
    private val ready = hashSetOf<PaperChunkKey>()
    private val observedLevels = mutableMapOf<PaperChunkKey, Pair<Long, Chunk.LoadLevel?>>()
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
    /** Shares the one plugin ticket registry; never creates or loads a missing source chunk. */
    internal fun retainLoaded(key: PaperChunkKey): PaperChunkTicketLease {
        check(org.bukkit.Bukkit.isPrimaryThread())
        check(!closed && !failed && key.worldId !in unloading &&
            plugin.server.getWorld(key.worldId)?.isChunkLoaded(key.x, key.z) == true) { "Persistence chunk unavailable" }
        return when (val acquired = registry.acquire(key)) {
            is PaperChunkTicketAcquireResult.Acquired -> acquired.lease
            is PaperChunkTicketAcquireResult.Failed -> throw acquired.failure
            else -> error("Persistence chunk lease unavailable: $acquired")
        }
    }

    /** Operation authority remains with the caller; this temporary read lease never activates a city. */
    internal fun request(owner: UUID, worldId: UUID, keys: Set<PaperChunkKey>): CivsChunkDemand {
        check(org.bukkit.Bukkit.isPrimaryThread())
        if (closed || failed) throw CivsRejection("chunk-loading-failed")
        if (worldId in unloading || plugin.server.getWorld(worldId) == null) throw CivsRejection("world-unavailable")
        require(keys.isNotEmpty() && keys.all { it.worldId == worldId })
        if (keys.size > maximumChunks || wanted.size + keys.count { it !in wanted } > maximumChunks)
            throw CivsRejection("chunk-capacity")
        return CivsChunkDemand(owner, worldId, keys.toSet(), generation, ::demandReady).also { demand ->
            temporary += demand
            demand.keys.forEach { key -> wanted[key] = (wanted[key] ?: 0) + 1 }
        }
    }

    private fun demandReady(demand: CivsChunkDemand): Boolean {
        check(org.bukkit.Bukkit.isPrimaryThread())
        if (closed || failed || demand.closed || demand.generation != generation) throw CivsRejection("chunk-loading-failed")
        if (demand.worldId in unloading || plugin.server.getWorld(demand.worldId) == null) throw CivsRejection("world-unavailable")
        demand.failure?.let { throw it }
        if (demand.keys.any { it in retryAt && it !in leases }) throw CivsRejection("chunk-unavailable")
        return demand.keys.all(ready::contains)
    }

    private fun stillRequested(key: PaperChunkKey): Boolean = key in recoveryKeys ||
        cities.values.any { key in it.chunks } || temporary.any { !it.closed && it.failure == null && key in it.keys }
    fun active(cityId: UUID): Boolean = !closed && !failed && cities[cityId]?.let {
        it.ready && permissions.allowed(it.owner, it.worldName)
    } == true

    /** Keep admitted actors stationary while their required footprint loads; this grants no activity. */
    internal fun hydrating(cityId: UUID): Boolean = !closed && !failed && cities[cityId]?.let {
        !it.ready && it.area == it.required && statuses[cityId] == "loading" &&
            permissions.allowed(it.owner, it.worldName)
    } == true

    /** Distinguishes ordinary readiness hydration from a known reason to retire temporary founding leases. */
    internal fun handoffReady(city: Settlement): Boolean {
        check(org.bukkit.Bukkit.isPrimaryThread())
        if (closed || failed) throw CivsRejection("chunk-loading-failed")
        if (city.worldId in unloading) throw CivsRejection("world-unavailable")
        val world = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("world-unavailable")
        permissions.handoffFailure(city.owner, world.name)?.let { throw CivsRejection(it) }
        if (statuses[city.id] == "capacity") throw CivsRejection("chunk-capacity")
        if (statuses[city.id] == "chunk-unavailable") throw CivsRejection("chunk-unavailable")
        val state = cities[city.id] ?: return false
        return state.owner == city.owner && sameFootprint(state.snapshot, city) &&
            state.worldName == world.name && active(city.id)
    }

    private fun loaded(key: PaperChunkKey, epoch: Long, chunk: Chunk?, error: Throwable?) {
        val retained = runCatching {
            check(org.bukkit.Bukkit.isPrimaryThread()) { "Settlement chunk completion must run on the primary thread" }
            if (closed || failed || epoch != generation || pending[key] != epoch || key !in wanted || !stillRequested(key) ||
                key.worldId in unloading) return@runCatching false
            if (error != null) throw error
            if (chunk == null || plugin.server.getWorld(key.worldId)?.isChunkLoaded(key.x, key.z) != true)
                return@runCatching false
            // Paper's load callback is the handoff: keep the chunk before native temporary ownership ends.
            acquire(key)
        }
        if (!closed) replies += Load(key, epoch, retained.getOrDefault(false), retained.exceptionOrNull())
    }

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
            "requiredLoadLevel" to Chunk.LoadLevel.ENTITY_TICKING,
            "generation" to generation, "maximumChunks" to maximumChunks,
            "permanentScope" to "developed-envelope", "temporaryDemands" to temporary.count { !it.closed },
            "wantedChunks" to keys.count(wanted::containsKey), "leasedChunks" to keys.count(leases::containsKey),
            "readyChunks" to keys.count(ready::contains), "pendingChunks" to keys.count(pending::containsKey),
            "queuedReplies" to replies.count { cityId == null || it.key in keys },
            "releaseFailures" to keys.count(releaseFailures::contains),
            "chunks" to sample(keys) { key -> mapOf("world" to key.worldId.toString(), "x" to key.x, "z" to key.z,
                "wantedBy" to wanted[key], "leased" to (key in leases), "readyLastPulse" to (key in ready),
                "loadLevelLastPulse" to observedLevels[key]?.second, "loadLevelObservedAtMillis" to observedLevels[key]?.first,
                "pendingGeneration" to pending[key], "retryInMillis" to retryAt[key]?.let { (it - now).coerceAtLeast(0) },
                "releaseFailed" to (key in releaseFailures)) },
            "permissions" to permissions.debug(owners))
    }

    fun pulse(settlements: Collection<Settlement>, recoveryChunks: Collection<Set<PaperChunkKey>> = emptyList(),
        extraPositions: Map<UUID, Collection<CivPosition>> = emptyMap()) {
        if (closed || failed) return
        val available = settlements.filter { it.pendingDeletion == null && it.worldId !in unloading && it.settings?.chunkLoading != false }
            .mapNotNull { city -> plugin.server.getWorld(city.worldId)?.let { city to it.name } }
        permissions.pulse(available.map { it.first.owner to it.second })
        val byId = available.associateBy { it.first.id }
        cities.toMap().forEach { (id, state) ->
            val current = byId[id]?.first
            if (current == null || current.owner != state.owner || current.worldId != state.area.world || byId[id]?.second != state.worldName ||
                !permissions.allowed(state.owner, state.worldName)) retire(id)
        }
        statuses.keys.retainAll(byId.keys)
        temporary.removeIf { it.closed }
        // Reconcile demand, not native leases: the common intersection keeps the same registry lease.
        val nextWanted = linkedMapOf<PaperChunkKey, Int>()
        fun retain(keys: Set<PaperChunkKey>) { keys.forEach { key -> nextWanted[key] = (nextWanted[key] ?: 0) + 1 } }
        cities.values.forEach { retain(it.chunks) }
        val nextRecovery = linkedSetOf<PaperChunkKey>()
        for (receipt in recoveryChunks) {
            if (receipt.any { it.worldId in unloading || plugin.server.getWorld(it.worldId) == null } ||
                nextWanted.size + receipt.count { it !in nextWanted } > maximumChunks) continue
            retain(receipt); nextRecovery += receipt
        }
        recoveryKeys = nextRecovery
        for (demand in temporary) {
            if (demand.failure != null) continue
            if (nextWanted.size + demand.keys.count { it !in nextWanted } > maximumChunks)
                demand.failure = CivsRejection("chunk-capacity")
            else retain(demand.keys)
        }
        wanted.clear(); wanted.putAll(nextWanted)
        ready.retainAll(wanted.keys)
        observedLevels.keys.retainAll(wanted.keys)
        for ((id, state) in cities) {
            val city = byId.getValue(id).first
            if (!sameFootprint(state.snapshot, city)) state.base = CivsChunkArea.developed(city)
            state.snapshot = city
            val area = CivsChunkArea.around(city.worldId, extraPositions[id].orEmpty())?.let(state.base::union) ?: state.base
            state.required = area
            if (area == state.area) continue
            val chunks = if (area.size <= maximumChunks) area.keys() else emptySet()
            val removed = state.chunks.count { it !in chunks && wanted[it] == 1 }
            if (area.size > maximumChunks || wanted.size - removed + chunks.count { it !in wanted } > maximumChunks) {
                state.ready = false; status(id, "capacity"); continue
            }
            for (key in state.chunks - chunks) {
                val count = wanted.getValue(key) - 1
                if (count == 0) { wanted.remove(key); ready -= key; observedLevels.remove(key) } else wanted[key] = count
            }
            for (key in chunks - state.chunks) wanted[key] = (wanted[key] ?: 0) + 1
            state.area = area; state.chunks = chunks; state.ready = false
            status(id, "loading")
        }
        val candidates = available.filter { (city, name) -> city.id !in cities && permissions.allowed(city.owner, name) }
        if (candidates.isNotEmpty()) {
            val (city, name) = candidates[Math.floorMod(admissionCursor++, candidates.size)]
            val base = CivsChunkArea.developed(city)
            val area = CivsChunkArea.around(city.worldId, extraPositions[city.id].orEmpty())?.let(base::union) ?: base
            if (area.size > maximumChunks) status(city.id, "capacity")
            else {
                val chunks = area.keys()
                if (wanted.size + chunks.count { it !in wanted } > maximumChunks) status(city.id, "capacity")
                else {
                    cities[city.id] = City(city.owner, name, city, base, area, chunks)
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
            acquisitions++
            if (!reply.retained || reply.failure != null) {
                retryAt[reply.key] = clock() + 30_000
                cities.filterValues { reply.key in it.chunks }.keys.forEach {
                    if (statuses[it] != "capacity") status(it, "chunk-unavailable")
                }
                continue
            }
        }
        retryAt.keys.retainAll(wanted.keys)
        // Two native completions can pin in the same tick. Drain that cohort before reserving more admissions.
        if (pending.isEmpty()) for (key in wanted.keys) {
            if (acquisitions >= 2 || nanoTime() >= deadline) break
            if (key in leases || key in pending || clock() < (retryAt[key] ?: 0)) continue
            val world = plugin.server.getWorld(key.worldId) ?: continue
            if (world.isChunkLoaded(key.x, key.z)) {
                acquisitions++
                acquire(key)
            } else if (pending.size < 2) {
                acquisitions++
                val epoch = generation
                pending[key] = epoch
                runCatching { load(world, key.x, key.z) }.getOrElse { CompletableFuture.failedFuture(it) }
                    .whenComplete { chunk, error -> loaded(key, epoch, chunk, error) }
            }
        }
        val retained = leases.keys.toList()
        repeat(minOf(32, retained.size)) {
            if (nanoTime() >= deadline) return@repeat
            val key = retained[Math.floorMod(readinessCursor++, retained.size)]
            val world = plugin.server.getWorld(key.worldId)
            val level = world?.let { citizenChunkLoadLevel(it, key.x, key.z) }
            observedLevels[key] = clock() to level
            if (key in wanted && level == Chunk.LoadLevel.ENTITY_TICKING) ready += key
            else ready -= key
        }
        cities.forEach { (id, state) ->
            if (state.area != state.required) return@forEach
            val complete = state.chunks.all(ready::contains)
            if (state.ready != complete) { state.ready = complete; status(id, if (complete) "active" else "loading") }
        }
    }

    private fun sameFootprint(first: Settlement, second: Settlement): Boolean = first.center == second.center &&
        first.buildings == second.buildings && first.construction?.plannedBuilding == second.construction?.plannedBuilding &&
        first.inventory?.containers == second.inventory?.containers && first.pendingControls == second.pendingControls &&
        sameMines(first.minePlans.orEmpty(), second.minePlans.orEmpty()) &&
        sameForestry(first.forestryJobs.orEmpty(), second.forestryJobs.orEmpty()) &&
        first.roadRouteOverrides == second.roadRouteOverrides

    /** Cursor/cargo progress changes no native footprint; only a new plan lineage or tree does. */
    private fun sameMines(first: Map<String, CivMinePlanState>, second: Map<String, CivMinePlanState>): Boolean =
        first === second || first.keys == second.keys && first.all { (id, a) ->
            val b = second.getValue(id)
            sameMineVersions(a, b) && a.levels.orEmpty().size == b.levels.orEmpty().size &&
                a.levels.orEmpty().zip(b.levels.orEmpty()).all { (old, current) ->
                    old.building == current.building && sameMineVersions(old.state(), current.state())
                }
        }

    private fun sameMineVersions(a: CivMinePlanState?, b: CivMinePlanState?): Boolean = a === b ||
        a?.version == b?.version && (a?.v2Step != null) == (b?.v2Step != null) &&
        (a?.v3Step != null) == (b?.v3Step != null) && (a?.v4Step != null) == (b?.v4Step != null) &&
        (a?.v5Step != null || a?.v5Proof != null) == (b?.v5Step != null || b?.v5Proof != null) &&
        (a?.v6Proof != null) == (b?.v6Proof != null)

    private fun sameForestry(first: Map<String, CivForestryJob>, second: Map<String, CivForestryJob>): Boolean =
        first === second || first.keys == second.keys && first.all { (id, a) ->
            val b = second.getValue(id)
            a.root == b.root && a.roots == b.roots && a.cells == b.cells
        }

    private fun acquire(key: PaperChunkKey): Boolean {
        if (key in leases) return true
        if (registry.activeChunkCount >= maximumChunks) {
            cities.filterValues { key in it.chunks }.keys.forEach { status(it, "capacity") }
            return false
        }
        return when (val result = registry.acquire(key)) {
            is PaperChunkTicketAcquireResult.Acquired -> { leases[key] = result.lease; true }
            else -> {
                retryAt[key] = clock() + 30_000
                cities.filterValues { key in it.chunks }.keys.forEach { status(it, "chunk-unavailable") }
                if (result is PaperChunkTicketAcquireResult.Failed)
                    plugin.logger.log(Level.WARNING, "ArcCivs could not retain settlement chunk $key", result.failure)
                false
            }
        }
    }

    private fun retire(id: UUID) {
        val city = cities.remove(id) ?: return
        city.chunks.forEach { key ->
            val count = wanted.getValue(key) - 1
            if (count == 0) { wanted.remove(key); ready -= key; observedLevels.remove(key) } else wanted[key] = count
        }
        status(id, "released")
    }

    private fun release(key: PaperChunkKey) {
        if (key in releaseFailures) return
        try { leases.remove(key)?.close(); ready -= key; observedLevels.remove(key) }
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
        cities.clear(); wanted.clear(); ready.clear(); observedLevels.clear(); retryAt.clear(); statuses.clear()
        recoveryKeys = emptySet()
        temporary.forEach(CivsChunkDemand::close); temporary.clear()
        failed = runCatching { registry.close() }.onFailure {
            plugin.logger.log(Level.SEVERE, "ArcCivs settlement chunk cleanup failed; background activity remains stopped", it)
        }.isFailure
        if (!failed) { leases.clear(); releaseFailures.clear(); registry = registryFactory() }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun worldUnloading(event: WorldUnloadEvent) {
        unloading += event.world.uid
        temporary.filter { it.worldId == event.world.uid }.forEach { it.failure = CivsRejection("world-unavailable") }
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
        cities.clear(); wanted.clear(); ready.clear(); observedLevels.clear(); replies.clear()
        recoveryKeys = emptySet()
        temporary.forEach(CivsChunkDemand::close); temporary.clear()
        registry.close()
        leases.clear()
    }
}
