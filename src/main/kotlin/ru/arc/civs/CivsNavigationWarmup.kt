package ru.arc.civs

import org.bukkit.Bukkit
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/** Sparse geometry only: the loader's envelope is an eligibility bound, never the volume to decode. */
internal data class CitizenSnapshotWarmPlan(val sections: List<CitizenSnapshotSectionKey>, val truncated: Boolean) {
    companion object {
        fun of(city: Settlement, radius: Int, maximum: Int): CitizenSnapshotWarmPlan {
            val result = linkedSetOf<CitizenSnapshotSectionKey>()
            var truncated = false
            fun box(low: CivPosition, high: CivPosition, margin: Int = 1) {
                for (x in ((low.x - margin) shr 4)..((high.x + margin) shr 4))
                    for (z in ((low.z - margin) shr 4)..((high.z + margin) shr 4))
                        for (y in ((low.y - 3) shr 4)..((high.y + 3) shr 4)) {
                            val key = CitizenSnapshotSectionKey(x, y, z)
                            if (result.size >= maximum && key !in result) { truncated = true; return }
                            result += key
                        }
            }
            val area = CivsChunkArea.developed(city) { points, volume ->
                if (volume) {
                    val values = points.toList()
                    if (values.isNotEmpty()) box(CivPosition(values.minOf { it.x }, values.minOf { it.y }, values.minOf { it.z }),
                        CivPosition(values.maxOf { it.x }, values.maxOf { it.y }, values.maxOf { it.z }))
                } else for (point in points) box(point, point)
            }
            val developed = result.toList()
            result.clear()
            box(city.center.copy(x = maxOf(city.center.x - radius, area.minX * 16), y = city.center.y - 2,
                z = maxOf(city.center.z - radius, area.minZ * 16)),
                city.center.copy(x = minOf(city.center.x + radius, area.maxX * 16 + 15), y = city.center.y + 2,
                    z = minOf(city.center.z + radius, area.maxZ * 16 + 15)), 0)
            for (key in developed) {
                if (result.size >= maximum && key !in result) { truncated = true; continue }
                result += key
            }
            return CitizenSnapshotWarmPlan(result.toList(), truncated)
        }
    }
}

/** One speculative item shares the existing native budget and navigation worker. No native loads or new scheduler. */
internal class CivsNavigationWarmup(private val settings: CivsNavigationSettings,
    private val snapshots: CivsNavigationSnapshots, private val tasks: LifecycleTaskScope,
    private val work: BudgetedTaskQueue, private val executor: Executor,
) {
    private class City(var snapshot: Settlement, val plan: CitizenSnapshotWarmPlan) {
        val pending = ArrayDeque(plan.sections.groupBy { CitizenSnapshotKey(it.x, it.z) }.values.flatMap { it.chunked(4) })
        var active = false
        var processed = 0; var unavailable = 0; var capacity = 0; var failed = 0
        var remainingStates = 0; var dynamicStates = 0
    }
    private val epoch = tasks.token()
    private val cities = linkedMapOf<UUID, City>()
    private var cursor = 0
    private var pending: CompletableFuture<*>? = null
    private var closed = false
    private var busy = false

    fun prepare(settlements: Collection<Settlement>, active: (UUID) -> Boolean) {
        if (closed || !tasks.isCurrent(epoch)) return
        val retained = settlements.filter { it.pendingDeletion == null }
        val ids = retained.mapTo(hashSetOf()) { it.id }
        cities.keys.retainAll(ids)
        snapshots.retainCities(ids)
        for (city in retained) {
            val old = cities[city.id]
            val ready = settings.snapshotWarmRadius > 0 && active(city.id)
            if (old == null && !ready) continue
            val current = if (old == null || !sameFootprint(old.snapshot, city)) {
                City(city, CitizenSnapshotWarmPlan.of(city, settings.snapshotWarmRadius,
                    settings.snapshotSectionsPerCity)).also { cities[city.id] = it }
            } else old
            current.snapshot = city
            current.active = ready
        }
        snapshots.warmCities(cities.filterValues { it.active }.keys)
        pulse()
    }

    private fun pulse() {
        if (busy) return
        pending = null
        val candidates = cities.values.filter { it.active && it.pending.isNotEmpty() }
        if (candidates.isEmpty()) return
        val city = candidates[Math.floorMod(cursor++, candidates.size)]
        val batch = city.pending.removeFirst()
        val world = Bukkit.getWorld(city.snapshot.worldId)
        if (world == null) { city.unavailable += batch.size; return }
        val admitted = batch.filter { key ->
            if (key.y * 16 >= world.maxHeight || key.y * 16 + 15 < world.minHeight) false
            else if (!snapshots.canWarm(city.snapshot.id, world.uid, key)) { city.capacity++; false } else true
        }
        if (admitted.isEmpty()) return
        val chunkKey = CitizenSnapshotKey(admitted.first().x, admitted.first().z)
        var input: CitizenSnapshotChunk? = null
        busy = true
        val native = work.submit({ current(city) }) {
            input = snapshots.capture(world, chunkKey, Bukkit.getCurrentTick().toLong(),
                admitted.mapTo(linkedSetOf()) { it.y }, city.snapshot.id, warm = true)
            true
        }
        pending = native
        native.whenCompleteSync(tasks) nativeDone@{ _, failure ->
            if (!current(city)) { input?.releaseSource(); finish(); return@nativeDone }
            if (failure != null) {
                if (cause(failure) is CitizenSnapshotPending) city.pending.addLast(admitted) else city.failed += admitted.size
                finish(); return@nativeDone
            }
            val chunk = input
            if (chunk == null) { city.unavailable += admitted.size; finish(); return@nativeDone }
            val worker = try { CompletableFuture.supplyAsync({
                try { chunk.prepare(); chunk.payloads() } finally { chunk.releaseSource() }
            }, executor) } catch (failure: RuntimeException) {
                chunk.releaseSource(); city.failed += admitted.size; finish(); return@nativeDone
            }
            pending = worker
            worker.whenCompleteSync(tasks) workerDone@{ values, decodeFailure ->
                if (!current(city)) { finish(); return@workerDone }
                if (decodeFailure != null) { city.failed += admitted.size; finish(); return@workerDone }
                val decoded = requireNotNull(values)
                val stamps = decoded.associate { it.key to it.revision }
                if (!snapshots.currentSections(world.uid, stamps)) {
                    city.pending.addLast(admitted); finish(); return@workerDone
                }
                snapshots.publish(world.uid, decoded, city.snapshot.id, warm = true)
                val retained = decoded.filter { snapshots.owns(world.uid, it.key, city.snapshot.id) }
                city.capacity += decoded.size - retained.size
                val states = buildMap { retained.forEach { putAll(it.samples) } }
                val known = snapshots.knownStates(retained)
                val missing = states.entries.filter { it.key !in known }
                city.remainingStates = missing.size
                if (missing.isEmpty()) { city.processed += retained.size; finish(); return@workerDone }
                var next = 0
                val resolving = work.submit({ current(city) && snapshots.currentSections(world.uid, stamps) }) {
                    val sample = missing[next++]
                    if (!citizenChunkReady(world, sample.value.x shr 4, sample.value.z shr 4)) throw CitizenSnapshotUnavailable()
                    if (snapshots.state(world, sample.key, sample.value).dynamic) city.dynamicStates++
                    city.remainingStates = missing.size - next
                    next == missing.size
                }
                pending = resolving
                resolving.whenCompleteSync(tasks) statesDone@{ _, stateFailure ->
                    city.remainingStates = 0
                    if (!current(city)) { finish(); return@statesDone }
                    if (stateFailure == null) city.processed += retained.size else city.failed += retained.size
                    finish()
                }
            }
        }
    }

    // A stage can be done while its Main continuation is still queued. Only that terminal continuation frees the slot.
    private fun finish() { busy = false; pending = null }

    private fun current(city: City): Boolean = !closed && tasks.isCurrent(epoch) &&
        city.active && cities[city.snapshot.id] === city

    private fun cause(failure: Throwable): Throwable = if (failure.cause == null) failure else cause(requireNotNull(failure.cause))

    /** Worker/cargo cursor changes do not rebuild the footprint or restart completed warming. */
    private fun sameFootprint(a: Settlement, b: Settlement): Boolean = a.worldId == b.worldId && a.center == b.center &&
        a.buildings == b.buildings && a.sameConstructionFootprints(b) &&
        a.inventory?.containers == b.inventory?.containers && a.pendingControls == b.pendingControls &&
        a.roadRouteOverrides == b.roadRouteOverrides && a.forestryJobs?.keys == b.forestryJobs?.keys &&
        a.forestryJobs.orEmpty().all { (id, old) -> b.forestryJobs?.get(id)?.let {
            old.root == it.root && old.roots == it.roots && old.cells == it.cells
        } == true } && a.minePlans?.keys == b.minePlans?.keys && a.minePlans.orEmpty().all { (id, old) ->
            b.minePlans?.get(id)?.let { next -> old.version == next.version &&
                old.levels.orEmpty().map { it.building } == next.levels.orEmpty().map { it.building } &&
                (old.v2Step != null) == (next.v2Step != null) && (old.v3Step != null) == (next.v3Step != null) &&
                (old.v4Step != null) == (next.v4Step != null) && (old.v5Step != null || old.v5Proof != null) ==
                (next.v5Step != null || next.v5Proof != null) && (old.v6Proof != null) == (next.v6Proof != null)
            } == true
        }

    fun debug(cityId: UUID?): Map<String, Any?> {
        val selected = cities.filterKeys { cityId == null || it == cityId }
        return mapOf("scope" to "loaded developed sections and static palette; dynamic point shapes and caller costs remain on demand",
            "native_loads" to false, "maximum_inflight" to 1, "pending" to busy,
            "center_radius_blocks" to settings.snapshotWarmRadius,
            "cities_total" to selected.size, "truncated" to (selected.size > 8),
            "entries" to selected.entries.take(8).map { (id, city) -> mapOf("city" to id, "active" to city.active,
                "target_sections" to city.plan.sections.size, "target_truncated" to city.plan.truncated,
                "processed_sections" to city.processed, "remaining_batches" to city.pending.size,
                "remaining_states" to city.remainingStates, "dynamic_states_on_demand" to city.dynamicStates,
                "unavailable_sections" to city.unavailable, "failed_sections" to city.failed,
                "capacity_limited" to (city.capacity > 0 || city.plan.truncated), "capacity_skipped_sections" to city.capacity) })
    }

    fun close() { closed = true; cities.clear(); pending = null; busy = false }
}
