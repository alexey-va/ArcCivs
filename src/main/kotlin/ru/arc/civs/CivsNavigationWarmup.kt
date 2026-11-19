package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.HeightMap
import org.bukkit.World
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/** Authored floors first; the retained envelope contributes only its height-map surface, never full-height volume. */
internal data class CitizenSnapshotWarmPlan(val sections: List<CitizenSnapshotSectionKey>, val truncated: Boolean,
    val surface: List<CitizenSnapshotKey> = emptyList()) {
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
            box(city.center.copy(x = maxOf(city.center.x - radius, area.minX * 16), y = city.center.y - 2,
                z = maxOf(city.center.z - radius, area.minZ * 16)),
                city.center.copy(x = minOf(city.center.x + radius, area.maxX * 16 + 15), y = city.center.y + 2,
                    z = minOf(city.center.z + radius, area.maxZ * 16 + 15)), 0)
            val surface = ArrayList<CitizenSnapshotKey>()
            surface@ for (x in area.minX..area.maxX) for (z in area.minZ..area.maxZ) {
                if (surface.size == maximum) { truncated = true; break@surface }
                surface += CitizenSnapshotKey(x, z)
            }
            return CitizenSnapshotWarmPlan(result.toList(), truncated, surface)
        }
    }
}

/** One speculative item shares the existing native budget and navigation worker. No native loads or new scheduler. */
internal class CivsNavigationWarmup(private val settings: CivsNavigationSettings,
    private val snapshots: CivsNavigationSnapshots, private val tasks: LifecycleTaskScope,
    private val work: BudgetedTaskQueue, private val executor: Executor,
) {
    private data class Batch(val key: CitizenSnapshotKey, val sections: List<CitizenSnapshotSectionKey>, val surface: Boolean = false)
    private data class Surface(val sections: Set<CitizenSnapshotSectionKey>,
        val stamps: Map<CitizenSnapshotSectionKey, Long> = emptyMap(), val tick: Long = 0)
    private class City(var snapshot: Settlement, val plan: CitizenSnapshotWarmPlan) {
        val sections = plan.sections.toMutableSet()
        val surfaces = hashMapOf<CitizenSnapshotKey, Surface>()
        val pending = ArrayDeque(plan.sections.groupBy { CitizenSnapshotKey(it.x, it.z) }.flatMap { (key, values) ->
            values.chunked(4).map { Batch(key, it) }
        } + plan.surface.map { Batch(it, emptyList(), surface = true) })
        val deferred = ArrayDeque<Batch>()
        var active = false
        var truncated = plan.truncated
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
            val current = if (old == null || !sameFootprint(old.snapshot, city) || ready && !old.active) {
                City(city, CitizenSnapshotWarmPlan.of(city, settings.snapshotWarmRadius,
                    settings.snapshotSectionsPerCity)).also { next ->
                    if (old?.snapshot?.worldId == city.worldId) for (key in next.plan.surface) {
                        val surface = old.surfaces[key] ?: continue
                        val additional = surface.sections.count { it !in next.sections }
                        if (next.sections.size + additional > settings.snapshotSectionsPerCity || !validSurface(old, key, surface)) continue
                        next.surfaces[key] = surface
                        next.sections += surface.sections
                        next.processed += additional
                    }
                    next.pending.removeAll { it.surface && it.key in next.surfaces }
                    cities[city.id] = next
                }
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
        if (world == null) { city.unavailable += batch.sections.size; city.deferred += batch; return }
        if (batch.surface && city.sections.size == settings.snapshotSectionsPerCity) {
            city.truncated = true; return
        }
        val surface = linkedSetOf<CitizenSnapshotSectionKey>()
        var row = 0
        var admitted = emptyList<CitizenSnapshotSectionKey>()
        var retry = batch
        var input: CitizenSnapshotChunk? = null
        var unavailable = false
        busy = true
        val native = work.submit({ current(city) }) {
            if (!citizenChunkReady(world, batch.key.x, batch.key.z)) {
                unavailable = true; true
            } else if (batch.surface && row < 16) {
                // One loaded chunk row per shared-budget quantum; no Main work can unload it between these reads.
                for (x in 0..15) {
                    val y = world.getHighestBlockYAt(batch.key.x * 16 + x,
                        batch.key.z * 16 + row, HeightMap.MOTION_BLOCKING_NO_LEAVES)
                    for (sy in (maxOf(world.minHeight, y - 3) shr 4)..(minOf(world.maxHeight - 1, y + 3) shr 4))
                        surface += CitizenSnapshotSectionKey(batch.key.x, sy, batch.key.z)
                }
                row++
                false
            } else {
                if (batch.surface) city.surfaces[batch.key] = Surface(surface.toSet())
                val wanted = if (!batch.surface) batch.sections else surface.filter { key ->
                    if (key in city.sections) false
                    else if (city.sections.size == settings.snapshotSectionsPerCity) { city.truncated = true; false }
                    else { city.sections += key; true }
                }
                val available = wanted.filter { key ->
                    if (key.y * 16 >= world.maxHeight || key.y * 16 + 15 < world.minHeight) false
                    else if (!snapshots.canWarm(city.snapshot.id, world.uid, key)) { city.capacity++; false } else true
                }
                admitted = available.take(4)
                available.drop(4).chunked(4).asReversed().forEach { city.pending.addFirst(Batch(batch.key, it)) }
                retry = Batch(batch.key, admitted)
                if (admitted.isNotEmpty()) input = snapshots.capture(world, batch.key, Bukkit.getCurrentTick().toLong(),
                    admitted.mapTo(linkedSetOf()) { it.y }, city.snapshot.id, warm = true)
                unavailable = admitted.isNotEmpty() && input == null
                true
            }
        }
        pending = native
        native.whenCompleteSync(tasks) nativeDone@{ _, failure ->
            if (!current(city)) { input?.releaseSource(); defer(city, retry); finish(); return@nativeDone }
            if (failure != null) {
                if (cause(failure) is CitizenSnapshotPending) city.pending.addLast(retry)
                else city.failed += retry.sections.size
                finish(); return@nativeDone
            }
            val chunk = input
            if (chunk == null) {
                if (unavailable) { city.unavailable += retry.sections.size; defer(city, retry) }
                else rememberSurface(city, world, batch.key)
                finish(); return@nativeDone
            }
            val worker = try { CompletableFuture.supplyAsync({
                try { chunk.prepare(); chunk.payloads() } finally { chunk.releaseSource() }
            }, executor) } catch (failure: RuntimeException) {
                chunk.releaseSource(); city.failed += admitted.size; finish(); return@nativeDone
            }
            pending = worker
            worker.whenCompleteSync(tasks) workerDone@{ values, decodeFailure ->
                if (!current(city)) { defer(city, retry); finish(); return@workerDone }
                if (decodeFailure != null) { city.failed += admitted.size; finish(); return@workerDone }
                val decoded = requireNotNull(values)
                val stamps = decoded.associate { it.key to it.revision }
                if (!snapshots.currentSections(world.uid, stamps)) {
                    city.pending.addLast(retry); finish(); return@workerDone
                }
                snapshots.publish(world.uid, decoded, city.snapshot.id, warm = true)
                val retained = decoded.filter { snapshots.owns(world.uid, it.key, city.snapshot.id) }
                city.capacity += decoded.size - retained.size
                val states = buildMap { retained.forEach { putAll(it.samples) } }
                val known = snapshots.knownStates(retained)
                val missing = states.entries.filter { it.key !in known }
                city.remainingStates = missing.size
                if (missing.isEmpty()) {
                    city.processed += retained.size; rememberSurface(city, world, batch.key); finish(); return@workerDone
                }
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
                    if (!current(city)) { defer(city, retry); finish(); return@statesDone }
                    if (stateFailure == null) { city.processed += retained.size; rememberSurface(city, world, batch.key) }
                    else if (cause(stateFailure) is CitizenSnapshotUnavailable) {
                        city.unavailable += retained.size; defer(city, retry)
                    } else if (!snapshots.currentSections(world.uid, stamps)) city.pending.addLast(retry)
                    else city.failed += retained.size
                    finish()
                }
            }
        }
    }

    // A stage can be done while its Main continuation is still queued. Only that terminal continuation frees the slot.
    private fun finish() { busy = false; pending = null }

    /** Readiness loss is retried on reactivation, not polled forever by the speculative warmer. */
    private fun defer(city: City, batch: Batch) { if (retained(city)) city.deferred.addLast(batch) }

    private fun validSurface(city: City, key: CitizenSnapshotKey, surface: Surface): Boolean {
        val revision = surface.stamps.values.maxOrNull() ?: return false
        return surface.stamps.size == surface.sections.size &&
            Bukkit.getCurrentTick().toLong() - surface.tick < settings.terrainRefreshTicks &&
            snapshots.current(city.snapshot.worldId, mapOf(key to revision)) &&
            snapshots.currentSections(city.snapshot.worldId, surface.stamps) &&
            surface.sections.all { snapshots.owns(city.snapshot.worldId, it, city.snapshot.id) }
    }

    /** Preserve only keys and source generations across city revisions, never detached payloads or native chunks. */
    private fun rememberSurface(city: City, world: World, key: CitizenSnapshotKey) {
        val surface = city.surfaces[key]?.takeIf { it.stamps.isEmpty() } ?: return
        val values = snapshots.cached(world, surface.sections, Bukkit.getCurrentTick().toLong(),
            city.snapshot.id, warm = true, touch = false)[key]?.payloads().orEmpty()
        if (values.size != surface.sections.size) return
        val known = snapshots.knownStates(values)
        if (values.any { section -> section.samples.keys.any { it !in known } }) return
        city.surfaces[key] = surface.copy(stamps = values.associate { it.key to it.revision }, tick = values.minOf { it.geometryTick })
    }

    private fun retained(city: City): Boolean = !closed && tasks.isCurrent(epoch) && cities[city.snapshot.id] === city
    private fun current(city: City): Boolean = retained(city) && city.active

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
                "target_sections" to city.sections.size, "target_truncated" to city.truncated,
                "processed_sections" to city.processed, "remaining_batches" to city.pending.size + city.deferred.size,
                "deferred_batches" to city.deferred.size,
                "surface_chunks" to city.plan.surface.size,
                "remaining_states" to city.remainingStates, "dynamic_states_on_demand" to city.dynamicStates,
                "unavailable_sections" to city.unavailable, "failed_sections" to city.failed,
                "capacity_limited" to (city.capacity > 0 || city.truncated), "capacity_skipped_sections" to city.capacity) })
    }

    fun close() { closed = true; cities.clear(); pending = null; busy = false }
}
