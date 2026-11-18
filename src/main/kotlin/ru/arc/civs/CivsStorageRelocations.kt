package ru.arc.civs

import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Holds city and native storage reservations until relocation enters the inventory journal. */
internal class CivsStorageRelocations(
    private val tasks: LifecycleTaskScope,
    private val store: CivsStore,
    private val storage: CivsStorage,
    private val inventoryJournal: CivsInventoryJournal,
    private val records: CivsCityRecords,
    private val checkMain: () -> Unit,
    private val ready: () -> Boolean,
    private val reserveCity: (UUID) -> Unit,
    private val releaseCity: (UUID) -> Unit,
    private val track: (CompletableFuture<*>) -> Unit,
) {
    /** Preflight owns the city until the changed cells are ready. Journal one exact native half at a time. */
    fun evacuateConstruction(initial: Settlement, plan: CivPlacementPlan, cells: List<CivPlanBlock>,
        admit: (Settlement) -> Unit): CompletableFuture<Settlement> {
        checkMain()
        val building = initial.building(plan.building.id) ?: return CompletableFuture.completedFuture(initial)
        if (cells.isEmpty() || initial.inventory == null || civsAutonomousHallRetarget(initial, building, plan.building))
            return CompletableFuture.completedFuture(initial)
        val owner = CivConstructionStorageOwner.forPlan(initial, building, plan)
        val result = CompletableFuture<Settlement>().also(track)
        fun batch(city: Settlement, start: Int) {
            val id = UUID.randomUUID()
            runCatching {
                rejectIf(!ready() || records.cities[city.id] != city || city.id in records.busy || city.id in records.unhealthy, "city-busy")
                admit(city)
                storage.reserve(city, id)
                reserveCity(city.id)
            }.onFailure { result.completeExceptionally(it); return }
            fun finish(failure: Throwable?) {
                storage.unlock(id)
                releaseCity(city.id)
                if (failure == null) result.complete(city) else result.completeExceptionally(failure)
            }
            fun scan(offset: Int, recheckEarlier: Boolean) {
                val end = minOf(cells.size, offset + CivContainerRelocation.MAX_CONTAINERS)
                val page = cells.subList(offset, end)
                val prepared = runCatching {
                    rejectIf(!ready() || records.cities[city.id] != city || city.id !in records.busy, "stale-city")
                    admit(city)
                    storage.prepareConstructionStorage(city, plan.building, page)
                }.getOrElse { finish(it); return }
                if (prepared == null) {
                    // Leave the city reserved while a broad empty footprint is examined over multiple ticks.
                    if (end < cells.size) tasks.runLater(1) { scan(end, recheckEarlier) }
                    else if (recheckEarlier) tasks.runLater(1) { scan(0, false) }
                    else finish(null)
                    return
                }
                val holder = prepared.holder
                val resume = offset + page.indexOf(prepared.cell) + 1
                val save = if (holder.before.all { it == null }) CompletableFuture.completedFuture(null)
                    else store.saveConstructionStorage(CivConstructionStorageSnapshot(id, owner, prepared.cell, holder.before, plan.id))
                save.whenCompleteSync(tasks) { part, failure ->
                    if (failure != null) return@whenCompleteSync finish(failure)
                    val tx = runCatching {
                        rejectIf(!ready() || records.cities[city.id] != city, "stale-city")
                        admit(city)
                        val retirement = prepared.retirement?.let { if (part == null) it.copy(planId = plan.id) else it }
                        val move = CivContainerRelocation(building, listOf(holder), retirement,
                            part?.let { CivConstructionStorageMove(owner, null, it, prepared.cell) })
                        CivInventoryTransaction(id, city.id, city.revision, move.after(city),
                            emptyList(), containerRelocation = move).also { storage.seal(it) }
                    }.getOrElse { finish(it); return@whenCompleteSync }
                    inventoryJournal.submitRelocation(city, tx) { admit(city) }.whenCompleteSync(tasks) { saved, error ->
                        if (error != null) result.completeExceptionally(error) else batch(requireNotNull(saved), resume)
                    }
                }
            }
            scan(start, recheckEarlier = start > 0)
        }
        batch(initial, 0)
        return result
    }

    /** Return one part to freshly admitted native holders. Insufficient room leaves its exact remainder durable. */
    fun returnConstructionStorage(city: Settlement, group: CivConstructionStorageBuffer,
        part: CivConstructionStoragePart): CompletableFuture<Settlement> {
        checkMain()
        rejectIf(!ready() || records.cities[city.id] != city || city.id in records.busy || city.id in records.unhealthy, "city-busy")
        rejectIf(group.owner.held(city), "construction-busy")
        val id = UUID.randomUUID()
        storage.reserveConstructionReturn(city, id, group, part)
        reserveCity(city.id)
        val result = CompletableFuture<Settlement>().also(track)
        fun abort(error: Throwable?) {
            storage.unlock(id); releaseCity(city.id)
            if (error == null) result.complete(city) else result.completeExceptionally(error)
        }
        store.loadConstructionStorage(group.owner, part).whenCompleteSync(tasks) { snapshot, failure ->
            if (failure != null) return@whenCompleteSync abort(failure)
            val proof = requireNotNull(snapshot)
            val candidates = runCatching { storage.constructionReturnCandidates(city, proof) }.getOrElse {
                abort(it); return@whenCompleteSync
            }
            fun scan(offset: Int) {
                val end = minOf(candidates.size, offset + CivContainerRelocation.MAX_CONTAINERS)
                val move = runCatching {
                    rejectIf(!ready() || records.cities[city.id] != city || group.owner.held(city), "stale-city")
                    storage.prepareConstructionReturn(city, proof, part, candidates.subList(offset, end))
                }.getOrElse { abort(it); return }
                if (move == null) {
                    if (end < candidates.size) tasks.runLater(1) { scan(end) } else abort(null)
                    return
                }
                val tx = runCatching {
                    CivInventoryTransaction(id, city.id, city.revision, move.after(city), emptyList(),
                        containerRelocation = move).also { storage.seal(it) }
                }.getOrElse { abort(it); return }
                inventoryJournal.submitRelocation(city, tx) {}.whenCompleteSync(tasks) { saved, error ->
                    if (error == null) result.complete(requireNotNull(saved)) else result.completeExceptionally(error)
                }
            }
            scan(0)
        }
        return result
    }

    /** Same-level control moves use the same exact slot receipt as upgrade evacuation. */
    fun relocateControlStorage(city: Settlement, building: CivBuilding, from: CivPosition,
        to: CivPosition): CompletableFuture<Settlement> {
        checkMain()
        rejectIf(!ready(), "not-ready")
        rejectIf(records.cities[city.id] != city || city.id in records.busy || city.id in records.unhealthy, "city-busy")
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        reserveCity(city.id)
        val tx = try {
            val move = storage.prepareControlRelocation(city, building, from, to)
            if (move == null) {
                storage.unlock(id)
                releaseCity(city.id)
                return CompletableFuture.completedFuture(city)
            }
            CivInventoryTransaction(id, city.id, city.revision, city.copy(revision = city.revision + 1),
                emptyList(), containerRelocation = move).also(storage::seal)
        } catch (failure: Throwable) {
            storage.unlock(id)
            releaseCity(city.id)
            return CompletableFuture.failedFuture(failure)
        }
        val result = inventoryJournal.submitRelocation(city, tx) {
            rejectIf(!ready() || records.cities[city.id] != city || city.id !in records.busy, "stale-city")
        }
        track(result)
        return result
    }

    private fun rejectIf(condition: Boolean, reason: String) {
        if (condition) throw CivsRejection(reason)
    }
}
