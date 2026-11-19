package ru.arc.civs

import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.paper.chunk.PaperChunkKey
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

private val STORAGE_BLOCK_IDS = CIVS_STORAGE_TYPES.mapTo(hashSetOf()) { it.lowercase() }

private fun CivInventoryTransaction.hasNativeItemFence(): Boolean = collected != null || containerRelocation != null ||
    cargoDropoff != null || cargoPickup != null || trade != null || mixedBufferPickup()

/** Main-thread inventory state machine. New intents live in RAM; only pre-existing disk receipts use crash fences. */
internal class CivsInventoryJournal(
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val store: CivsStore,
    private val storage: CivsStorage,
    private val settlements: () -> Map<UUID, Settlement>,
    private val busy: MutableSet<UUID>,
    private val unhealthy: MutableSet<UUID>,
    private val isClosed: () -> Boolean,
    private val publish: (Settlement) -> CompletableFuture<Unit>,
    private val mutate: (UUID, (Settlement) -> Settlement) -> CompletableFuture<Settlement>,
    private val track: (CompletableFuture<*>) -> Unit,
    private val validateNativeCollected: (CivInventoryTransaction) -> Unit,
    private val resurrectionRejection: (CivInventoryTransaction, Settlement, Boolean) -> Throwable?,
    private val requestResurrectionAdmission: (CivInventoryTransaction) -> Unit,
    private val afterRetired: (CivInventoryTransaction, Boolean) -> Settlement,
    private val updateHealth: () -> Unit,
    private val clock: () -> Long,
    private val verifyGateCaptures: (CivInventoryTransaction) -> CompletableFuture<Unit> = { CompletableFuture.completedFuture(Unit) },
    private val verifyHallCaptures: (CivInventoryTransaction) -> CompletableFuture<Unit> = { CompletableFuture.failedFuture(CivsRejection("stale-worksite")) },
    private val verifyWarehouseCaptures: (CivInventoryTransaction) -> CompletableFuture<Unit> = { CompletableFuture.completedFuture(Unit) },
    private val requireBufferedPickupArrival: (UUID, UUID, UUID) -> Unit = { _, _, _ -> throw CivsRejection("route-unreachable") },
    private val verifyAuthoredVegetationSource: (CivInventoryTransaction) -> CompletableFuture<Unit> = {
        CompletableFuture.failedFuture(CivsRejection("vegetation-source-unavailable")) },
) {
    private val cities get() = settlements()
    private val closed get() = isClosed()
    private val inventoryRecovery = linkedMapOf<UUID, CivInventoryTransaction>()
    private val inventoryRetryAt = mutableMapOf<UUID, Long>()
    private val blockedRecovery = mutableSetOf<UUID>()
    private val activeRamReceipts = mutableSetOf<UUID>()
    private val localReceipts = mutableSetOf<UUID>()
    val pendingCount: Int get() = inventoryRecovery.size
    val blockedCount: Int get() = blockedRecovery.size
    fun pending(cityId: UUID): Boolean = cityId in inventoryRecovery
    fun pendingCount(cityId: UUID): Int = if (pending(cityId)) 1 else 0
    fun pendingCityIds(): Set<UUID> = inventoryRecovery.keys.toSet()

    /** Validated durable receipts own bounded native reads even while their city is quarantined. */
    fun pendingNativeChunks(): List<Set<PaperChunkKey>> = inventoryRecovery.values.filter { it.cityId in cities }.map { tx ->
        val positions = tx.changes.map(CivInventorySlotChange::container) + tx.worldChanges.orEmpty().map(CivPlanBlock::position) +
            tx.collected?.containers.orEmpty().map(CivNativeContainerChange::container) +
            tx.collected?.ground.orEmpty().map(CivGroundItemChange::position) +
            tx.collected?.forestryLeafLoot?.sources.orEmpty().map(CivForestryLeafSource::leaf) +
            tx.containerRelocation?.containers.orEmpty().map(CivRelocatedContainer::position) +
            tx.warehouseRefit?.routes.orEmpty().flatten() +
            tx.mineOpening?.let { CivsMineOpening.positions(it.building) }.orEmpty()
        val keys = positions.mapTo(linkedSetOf()) { PaperChunkKey(tx.after.worldId, it.x shr 4, it.z shr 4) }
        tx.upkeep?.work?.let { work ->
            val upkeep = requireNotNull(work.upkeep)
            val repair = upkeep.routeOverride ?: tx.after.roadRouteOverrides?.get(upkeep.source.id.toString())
            val cut = repair?.let { saved -> saved.runs().map { run -> saved.route.subList(run.first - 1, run.last + 2) }
                .filter { section -> section.any {
                    kotlin.math.abs(it.x.toLong() - upkeep.floor.x) <= 1 && kotlin.math.abs(it.z.toLong() - upkeep.floor.z) <= 1
                } }.flatten() }.orEmpty()
            val walks = upkeep.hall?.let { proof ->
                CivsTownHallApproach.from(tx.after, upkeep.source, proof)?.crossingChecks(upkeep.floor, work.changes)?.map { it.before + it.after }
            }.orEmpty()
            val sharedWalk = CivsGateSharedApproaches.repair(tx.after, upkeep)?.route.orEmpty()
            val floors = listOf(upkeep.floor, work.standing) + upkeep.neighbours + cut + walks.flatten() + sharedWalk
            for (floor in floors) for (x in ((floor.x - 2) shr 4)..((floor.x + 2) shr 4))
                for (z in ((floor.z - 2) shr 4)..((floor.z + 2) shr 4)) keys += PaperChunkKey(tx.after.worldId, x, z)
        }
        keys
    }

    /** An evacuation owns the city and its exact source before asynchronous native persistence starts. */
    fun submitCargoPickup(city: Settlement, tx: CivInventoryTransaction, local: Boolean = false,
        freshAdmission: () -> Unit = {}): CompletableFuture<Settlement> {
        require((tx.cargoPickup != null || tx.mixedBufferPickup()) && tx.cityId == city.id)
        rejectIf(city.id in busy || city.id in unhealthy || cities[city.id] != city, "city-busy")
        busy += city.id
        return submitNative(city, tx, local, freshAdmission)
    }

    fun submitTrade(city: Settlement, tx: CivInventoryTransaction,
        freshAdmission: () -> Unit = {}): CompletableFuture<Settlement> {
        require(tx.trade != null && tx.cityId == city.id)
        rejectIf(city.id in busy || city.id in unhealthy || cities[city.id] != city, "city-busy")
        busy += city.id
        return submitNative(city, tx, freshAdmission = freshAdmission)
    }

    fun submitRelocation(city: Settlement, tx: CivInventoryTransaction,
        freshAdmission: () -> Unit = {}): CompletableFuture<Settlement> {
        require(tx.containerRelocation != null)
        return submitNative(city, tx, freshAdmission = freshAdmission)
    }

    /** Submit a sealed native receipt while its caller holds the city reservation. */
    fun submitNative(city: Settlement, tx: CivInventoryTransaction, local: Boolean = false,
        freshAdmission: () -> Unit = {}): CompletableFuture<Settlement> {
        val id = tx.id
        val cityId = city.id
        inventoryRecovery[cityId]?.let { pending ->
            if (pending.id != id) storage.unlock(id)
            return CompletableFuture.failedFuture(CivsRejection("city-busy"))
        }
        if (local && storage.localInventory(city, tx, null)) {
            try {
                rejectIf(closed, "not-ready")
                rejectIf(cities[cityId] !== city, "stale-delivery")
                freshAdmission()
            } catch (failure: Exception) {
                storage.unlock(id)
                busy -= cityId
                updateHealth()
                return CompletableFuture.failedFuture(failure)
            }
            return submitLocal(city, tx, cityReserved = true)
        }
        val result = tracked<Settlement>()
        fun abort(failure: Throwable) {
            storage.unlock(id)
            busy -= cityId
            result.completeExceptionally(failure)
            updateHealth()
        }
        val sourceFence = runCatching { storage.persistCollected(tx, beforeJournal = true, durable = false) }
            .getOrElse { abort(it); return result }
        sourceFence.whenCompleteSync(tasks) { _, failure ->
            if (failure != null) return@whenCompleteSync abort(failure)
            val changed = runCatching {
                rejectIf(closed || result.isCancelled, "not-ready")
                rejectIf(cities[cityId] != city, "stale-delivery")
                requireFreshNative(tx)
                tx.bufferPickup?.let { requireBufferedPickupArrival(cityId, it.workerBefore.id, it.source) }
                // Ephemeral player intent expires before journal admission; accepted recovery keeps its native proof.
                freshAdmission()
            }.exceptionOrNull()
            if (changed != null) return@whenCompleteSync abort(changed)
            submit(tx).whenComplete { saved, journalFailure ->
                if (journalFailure == null) result.complete(saved) else result.completeExceptionally(journalFailure)
            }
        }
        return result
    }

    /** No changed city or resident may inherit a prepared native transfer after an asynchronous wait. */
    private fun requireCollectedCity(tx: CivInventoryTransaction) {
        tx.bufferPickup?.let { receipt ->
            val current = cities[tx.cityId] ?: throw CivsRejection("no-city")
            rejectIf(current != receipt.before(tx.after) && current != tx.after, "stale-delivery")
        }
        if (tx.trade != null) {
            val current = cities[tx.cityId] ?: throw CivsRejection("no-city")
            rejectIf(current != tx.after.copy(revision = tx.beforeRevision), "stale-delivery")
        }
        if (tx.containerRelocation != null) {
            val current = cities[tx.cityId] ?: throw CivsRejection("no-city")
            rejectIf(current != tx.containerRelocation.before(tx.after) &&
                !(tx.containerRelocation.buffer != null && current == tx.after), "stale-delivery")
        }
        tx.cargoDropoff?.let { receipt ->
            val current = cities[tx.cityId] ?: throw CivsRejection("no-city")
            val before = tx.after.copy(workers = tx.after.workers.map {
                if (it.id == receipt.workerBefore.id) receipt.workerBefore else it
            }, revision = tx.beforeRevision)
            rejectIf(current != before, "stale-delivery")
        }
        tx.cargoPickup?.let { receipt ->
            val current = cities[tx.cityId] ?: throw CivsRejection("no-city")
            rejectIf(current != receipt.before(tx.after), "stale-delivery")
        }
        val transfer = tx.collected ?: return
        val current = cities[tx.cityId] ?: throw CivsRejection("no-city")
        val worker = current.workers.singleOrNull { it.id == transfer.workerId } ?: throw CivsRejection("stale-delivery")
        val afterWorker = tx.after.workers.single { it.id == transfer.workerId }
        rejectIf(current.revision != tx.beforeRevision || current.pendingDeletion != null ||
            transfer.food?.let { current.stock != it.stockBefore } == true ||
            CivsEquipment.slots(worker) != transfer.before ||
            worker.copy(items = afterWorker.items, inventory = afterWorker.inventory,
                hasCollectedItems = afterWorker.hasCollectedItems) != afterWorker ||
            current.copy(stock = if (transfer.food != null) tx.after.stock else current.stock,
                workers = current.workers.map { if (it.id == worker.id) afterWorker else it },
                revision = tx.after.revision) != tx.after, "stale-delivery")
    }

    private fun requireFreshNative(tx: CivInventoryTransaction) {
        require(tx.collected != null || tx.containerRelocation != null || tx.cargoPickup != null || tx.trade != null || tx.mixedBufferPickup())
        requireCollectedCity(tx)
        if (tx.collected != null) validateNativeCollected(tx)
        else when (val state = when {
            tx.trade != null -> storage.tradeNativeState(tx, expectedBefore = true)
            tx.mixedBufferPickup() -> storage.bufferedPickupState(tx, true)
            tx.cargoPickup != null -> storage.cargoPickupBeforeState(tx)
            else -> storage.relocationBeforeState(tx)
        }) {
            CivInventoryApplyResult.Applied -> Unit
            is CivInventoryApplyResult.Retry -> throw CivsRejection("storage-unavailable", state.values)
            is CivInventoryApplyResult.Conflict -> throw CivsRejection("inventory-conflict", state.values)
            null -> error("Unclassified container relocation")
        }
    }

    fun submit(tx: CivInventoryTransaction, before: Settlement? = null, fieldWork: CivWorldWork? = null): CompletableFuture<Settlement> {
        if (before != null && storage.localInventory(before, tx, fieldWork)) return submitLocal(before, tx)
        val cityId = tx.cityId
        val id = tx.id
        busy += cityId
        val result = tracked<Settlement>()
        activeRamReceipts += id
        store.saveInventoryTransaction(tx).whenCompleteSync(tasks) { _, failure ->
            if (failure != null) {
                val rejection = CivsDiagnostics.rejection(failure)
                if (rejection?.reason in setOf("storage-transaction-too-large", "ground-history-full", "ground-item-consumed", "stale-city", "no-city")) {
                    // These admission failures precede the journal write; no native mutation has started.
                    storage.unlock(id)
                    activeRamReceipts -= id
                    busy -= cityId
                    result.completeExceptionally(rejection)
                    updateHealth()
                    return@whenCompleteSync
                }
                inventoryRecovery[cityId] = tx
                unhealthy += cityId
                blockedRecovery += cityId
                busy -= cityId
                log("journal inventory ${tx.id}", failure, tx.cityId)
                result.completeExceptionally(CivsRejection("inventory-recovery"))
            } else {
                inventoryRecovery[cityId] = tx
                applyInventory(tx, result)
            }
        }
        return result
    }

    private fun submitLocal(before: Settlement, tx: CivInventoryTransaction, cityReserved: Boolean = false): CompletableFuture<Settlement> {
        val id = tx.cityId
        val pending = inventoryRecovery[id]
        if (pending != null || !cityReserved && id in busy) {
            if (pending?.id != tx.id) storage.unlock(tx.id)
            return CompletableFuture.failedFuture(CivsRejection("city-busy"))
        }
        try {
            rejectIf(closed, "not-ready")
            rejectIf(id in unhealthy, "city-busy")
            rejectIf(cities[id] !== before, "stale-city")
            store.beginLocalInventory(before, tx)
        } catch (failure: Exception) {
            storage.unlock(tx.id)
            if (cityReserved) busy -= id
            updateHealth()
            return CompletableFuture.failedFuture(failure)
        }
        val result = tracked<Settlement>()
        busy += id
        inventoryRecovery[id] = tx
        activeRamReceipts += tx.id
        localReceipts += tx.id
        applyInventory(tx, result)
        return result
    }

    /** One immutable receipt covers native slots and the exact next city revision. */
    private fun applyInventory(tx: CivInventoryTransaction, result: CompletableFuture<Settlement>?, gateVerified: Boolean = false) {
        if (closed) { result?.completeExceptionally(CivsRejection("not-ready")); return }
        val local = tx.id in localReceipts
        val freshLocal = local && result != null
        var nativeStarted = !freshLocal
        fun fail(error: Throwable, retry: Boolean = false) {
            if (!nativeStarted) return abortUntouchedInventory(tx, result, error)
            busy -= tx.cityId
            unhealthy += tx.cityId
            if (retry) deferNativeRecovery(tx)
            else { blockedRecovery += tx.cityId; log("reconcile inventory ${tx.id}", error, tx.cityId) }
            result?.completeExceptionally(error)
            updateHealth()
        }
        if ((tx.containerRelocation?.buffer != null || tx.bufferPickup != null) && !gateVerified) {
            store.verifyConstructionReceipt(tx).whenCompleteSync(tasks) { _, error ->
                if (error != null) fail(error) else applyInventory(tx, result, gateVerified = true)
            }
            return
        }
        if (tx.containerRelocation?.buffer != null || tx.bufferPickup != null) {
            val before = tx.containerRelocation?.before(tx.after) ?: requireNotNull(tx.bufferPickup).before(tx.after)
            if (cities[tx.cityId] != before && cities[tx.cityId] != tx.after) return fail(CivsRejection("stale-city"))
        }
        if (tx.upkeep?.work?.upkeep?.let { it.hall != null || it.preparedCentre || it.forkPartner != null ||
                CivsGateSharedApproaches.repair(tx.after, it) != null } == true && !gateVerified) {
            runCatching { verifyHallCaptures(tx) }.getOrElse { CompletableFuture.failedFuture(it) }
                .whenCompleteSync(tasks) { _, failure ->
                    if (failure != null) fail(failure) else applyInventory(tx, result, gateVerified = true)
                }
            return
        }
        if (tx.warehouseRefit != null && !gateVerified) {
            runCatching { verifyWarehouseCaptures(tx) }.getOrElse { CompletableFuture.failedFuture(it) }
                .whenCompleteSync(tasks) { _, failure ->
                    if (failure != null) fail(failure) else applyInventory(tx, result, gateVerified = true)
                }
            return
        }
        if (tx.gateRefit != null && !gateVerified) {
            runCatching { verifyGateCaptures(tx) }.getOrElse { CompletableFuture.failedFuture(it) }
                .whenCompleteSync(tasks) { _, failure ->
                    if (failure != null) fail(failure) else applyInventory(tx, result, gateVerified = true)
                }
            return
        }
        if (tx.authoredVegetation != null && !gateVerified) {
            runCatching { verifyAuthoredVegetationSource(tx) }.getOrElse { CompletableFuture.failedFuture(it) }
                .whenCompleteSync(tasks) { _, failure ->
                    if (failure != null) fail(failure) else applyInventory(tx, result, gateVerified = true)
                }
            return
        }
        runCatching {
            if (tx.collected != null || tx.containerRelocation != null || tx.cargoPickup != null || tx.trade != null || tx.mixedBufferPickup()) {
                if (result != null) {
                    if (tx.collected?.food != null) {
                        val state = storage.collectedBeforeState(tx)
                        if (state is CivInventoryApplyResult.Retry)
                            return fail(CivsRejection(if (freshLocal) "storage-unavailable" else "inventory-recovery", state.values), retry = true)
                    }
                    // This invocation has not written any native endpoint yet; fresh absence is never our own debit.
                    val failure = runCatching { requireFreshNative(tx) }.exceptionOrNull()
                    if (failure != null) return abortUntouchedInventory(tx, result, failure)
                }
            }
            tx.resurrection?.let {
                when (val state = storage.resurrectionDebitState(tx)) {
                    is CivInventoryApplyResult.Retry -> return fail(CivsRejection("inventory-recovery", state.values), retry = true)
                    is CivInventoryApplyResult.Conflict -> return fail(CivsRejection("inventory-conflict", state.values))
                    CivInventoryApplyResult.Applied -> Unit // The accepted debit must finish even after later expiry.
                    null -> {
                        val current = cities[tx.cityId] ?: throw CivsRejection("no-city")
                        if (current.revision == tx.beforeRevision) {
                            val reason = resurrectionRejection(tx, current, result != null)
                            if (reason != null) return abortUntouchedInventory(tx, result, reason)
                        }
                    }
                }
            }
            val applied = if (freshLocal) storage.applyFreshLocal(tx) { nativeStarted = true }
                else storage.reconcile(tx, persistPlayer = tx.id !in activeRamReceipts)
            when (applied) {
                is CivInventoryApplyResult.Retry -> return fail(CivsRejection(
                    if (nativeStarted) "inventory-recovery" else "storage-unavailable", applied.values), retry = true)
                is CivInventoryApplyResult.Conflict -> return fail(CivsRejection("inventory-conflict", applied.values))
                CivInventoryApplyResult.Applied -> Unit
            }
            nativeStarted = true
            CivsDiagnostics.event("inventory_applied", "city" to tx.cityId, "transaction" to tx.id,
                "revision" to tx.after.revision, "slots" to tx.changes.size)
            fun commit() {
                val stale = runCatching {
                    rejectIf(closed, "not-ready"); requireCollectedCity(tx)
                    if (tx.containerRelocation?.let { it.unregister != null || it.buffer != null } == true) when (val native = storage.relocationAfterState(tx)) {
                        CivInventoryApplyResult.Applied -> Unit
                        is CivInventoryApplyResult.Retry -> return fail(CivsRejection("inventory-recovery", native.values), retry = true)
                        is CivInventoryApplyResult.Conflict -> throw CivsRejection("inventory-conflict", native.values)
                        null -> throw CivsRejection("inventory-conflict")
                    }
                    tx.warehouseRefit?.let { receipt ->
                        val current = cities[tx.cityId]
                        rejectIf(current != receipt.before(tx.after) && current != tx.after, "stale-city")
                    }
                    tx.gateRefit?.let { receipt ->
                        val current = cities[tx.cityId]
                        rejectIf(current != receipt.before(tx.after) && current != tx.after, "stale-city")
                    }
                }.exceptionOrNull()
                if (stale != null) return fail(stale)
                val changedHolders = buildSet {
                    tx.changes.forEach { add(it.container) }
                    tx.collected?.containers.orEmpty().forEach { add(it.container) }
                    tx.containerRelocation?.containers.orEmpty().forEach { add(it.position) }
                    tx.worldChanges.orEmpty().forEach { cell ->
                        val before = cell.before.substringBefore('[').removePrefix("minecraft:")
                        val after = cell.after.substringBefore('[').removePrefix("minecraft:")
                        if (before in STORAGE_BLOCK_IDS || after in STORAGE_BLOCK_IDS) add(cell.position)
                    }
                }
                if (changedHolders.isNotEmpty()) storage.invalidateSupplyPositions(tx.after.worldId, changedHolders)
                if (local) {
                    val accepted = store.completeLocalInventory(tx)
                    val publication = publish(accepted)
                    fun published(failure: Throwable?) {
                        if (failure != null) fail(failure) else finishInventory(tx, result)
                    }
                    if (publication.isDone) publication.whenComplete { _, failure -> published(failure) }
                    else publication.whenCompleteSync(tasks) { _, failure -> published(failure) }
                    return
                }
                val completion = store.completeInventoryTransaction(tx)
                // Both submissions happen in this Main turn. DRAIN is queued after the accepted city and UUID state,
                // even if shutdown prevents the later publication callback from running.
                val accepted = if (tx.id in activeRamReceipts && !tx.collected?.ground.isNullOrEmpty())
                    completion.thenCombine(store.markConsumedItems(tx)) { city, consumed -> city to consumed }
                else completion.thenApply { city -> city to null }
                accepted.whenCompleteSync(tasks) { state, failure ->
                    if (failure != null) return@whenCompleteSync fail(CivsRejection("inventory-recovery").also { it.initCause(failure) })
                    val (saved, consumed) = requireNotNull(state)
                    consumed?.let(storage::installConsumed)
                    publish(saved).whenCompleteSync(tasks) { _, publishFailure ->
                        if (publishFailure != null) return@whenCompleteSync fail(publishFailure)
                        requestResurrectionAdmission(tx)
                        finishInventory(tx, result)
                    }
                }
            }
            if (tx.id in activeRamReceipts) {
                // RAM mode performs the after-state check synchronously and never schedules native persistence.
                storage.persistCollected(tx, durable = false)
                commit()
            } else if (!tx.hasNativeItemFence()) commit()
            else persistCollectedReceipt(tx).whenCompleteSync(tasks) { _, persistenceFailure ->
                if (persistenceFailure != null) fail(CivsRejection("inventory-recovery").also { it.initCause(persistenceFailure) },
                    retry = isNativePersistenceRetry(persistenceFailure))
                else commit()
            }
        }.onFailure { fail(it) }
    }

    /** Validate native after-state and reserve its consumed UUIDs before publishing the city credit. */
    private fun persistCollectedReceipt(tx: CivInventoryTransaction): CompletableFuture<Unit> {
        val result = tracked<Unit>()
        runCatching { storage.persistCollected(tx, durable = tx.id !in activeRamReceipts) }.getOrElse { CompletableFuture.failedFuture(it) }.whenCompleteSync(tasks) { _, failure ->
            if (failure != null) result.completeExceptionally(failure)
            else if (closed) result.completeExceptionally(CivsRejection("not-ready"))
            else if (tx.collected?.ground.isNullOrEmpty()) result.complete(Unit)
            else store.markConsumedItems(tx).whenCompleteSync(tasks) { consumed, markerFailure ->
                if (markerFailure != null) result.completeExceptionally(markerFailure)
                else if (closed) result.completeExceptionally(CivsRejection("not-ready"))
                else {
                    storage.installConsumed(requireNotNull(consumed))
                    result.complete(Unit)
                }
            }
        }
        return result
    }

    private fun isNativePersistenceRetry(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }.take(8).any { it is CivsNativePersistenceRetry }

    private fun deferNativeRecovery(tx: CivInventoryTransaction) {
        if (tx.hasNativeItemFence() || tx.id in localReceipts) inventoryRetryAt[tx.id] = now() + 5_000
    }

    /** Untouched native endpoints can cancel safely; uncertain journal deletion retains the recovery lock. */
    private fun abortUntouchedInventory(tx: CivInventoryTransaction, result: CompletableFuture<Settlement>?, reason: Throwable) {
        fun retired(failure: Throwable?) {
            busy -= tx.cityId
            if (failure == null) {
                inventoryRecovery.remove(tx.cityId)
                activeRamReceipts -= tx.id
                localReceipts -= tx.id
                unhealthy -= tx.cityId
                blockedRecovery -= tx.cityId
                storage.unlock(tx.id)
                result?.completeExceptionally(reason)
            } else {
                unhealthy += tx.cityId
                blockedRecovery += tx.cityId
                log("cancel untouched inventory journal ${tx.id}", failure, tx.cityId)
                result?.completeExceptionally(CivsRejection("inventory-recovery"))
            }
            updateHealth()
        }
        if (tx.id in localReceipts) retired(runCatching { store.cancelLocalInventory(tx) }.exceptionOrNull())
        else store.deleteInventoryTransaction(tx.id).whenCompleteSync(tasks) { _, failure -> retired(failure) }
    }

    private fun finishInventory(tx: CivInventoryTransaction, result: CompletableFuture<Settlement>?) {
        fun retired(failure: Throwable?) {
            busy -= tx.cityId
            if (failure != null) {
                unhealthy += tx.cityId
                blockedRecovery += tx.cityId
                log("retire inventory journal ${tx.id}", failure, tx.cityId)
                result?.completeExceptionally(CivsRejection("inventory-recovery"))
            } else {
                inventoryRecovery.remove(tx.cityId)
                activeRamReceipts -= tx.id
                localReceipts -= tx.id
                unhealthy -= tx.cityId
                blockedRecovery -= tx.cityId
                storage.unlock(tx.id)
                val current = afterRetired(tx, result == null)
                if (current.workers.any { it.cargo?.let { cargo -> !cargo.inventoryBacked && cargo.stockResource == null } == true }) {
                    // Retire the immutable old receipt first; migration owns a new durable revision.
                    val migration = runCatching { mutate(current.id) { CivsLogistics.materializeCargo(it) } }
                        .getOrElse { CompletableFuture.failedFuture(it) }
                    if (!migration.isDone) unhealthy += current.id
                    migration.whenCompleteSync(tasks) { saved, error ->
                        if (error != null) {
                            unhealthy += current.id
                            log("materialize recovered cargo ${current.id}", error, current.id)
                            result?.completeExceptionally(error)
                        } else {
                            unhealthy -= current.id
                            result?.complete(requireNotNull(saved))
                        }
                        updateHealth()
                    }
                } else result?.complete(current)
            }
            updateHealth()
        }
        if (tx.id in localReceipts) retired(null)
        else store.deleteInventoryTransaction(tx.id).whenCompleteSync(tasks) { _, failure -> retired(failure) }
    }

    fun recover() {
        val time = now()
        val tx = inventoryRecovery.values.firstOrNull { it.cityId !in busy && it.cityId !in blockedRecovery &&
            (inventoryRetryAt[it.id]?.let { retryAt -> time >= retryAt } ?: true) &&
            !storage.waitingForPlayer(it) } ?: return
        inventoryRetryAt.remove(tx.id)
        val city = cities[tx.cityId] ?: run { blockedRecovery += tx.cityId; return }
        // Move this attempt to the end so stuck receipts cannot keep later ones from running.
        inventoryRecovery.remove(tx.cityId)
        inventoryRecovery[tx.cityId] = tx
        busy += city.id
        runCatching {
            storage.lock(tx)
            if (city.revision == tx.beforeRevision) applyInventory(tx, null)
            else if (city.revision >= tx.after.revision && (city.revision != tx.after.revision || city == tx.after)) {
                if (tx.gateRefit != null || tx.warehouseRefit != null || tx.authoredVegetation != null ||
                    tx.upkeep?.work?.upkeep?.let { it.hall != null || it.preparedCentre || it.forkPartner != null ||
                        CivsGateSharedApproaches.repair(tx.after, it) != null } == true ||
                    tx.containerRelocation?.buffer != null || tx.bufferPickup != null) {
                    rejectIf(city != tx.after, "stale-city")
                    applyInventory(tx, null)
                    return@runCatching
                }
                if (city.revision == tx.after.revision || tx.trade != null || tx.residentTransfer != null || tx.resurrection != null || tx.collected != null ||
                    tx.containerRelocation != null || tx.cargoDropoff != null || tx.cargoPickup != null) when (val applied = storage.reconcile(tx, persistPlayer = tx.id !in activeRamReceipts)) {
                    is CivInventoryApplyResult.Retry -> {
                        deferNativeRecovery(tx)
                        busy -= city.id; unhealthy += city.id; return@runCatching
                    }
                    is CivInventoryApplyResult.Conflict -> throw CivsRejection("inventory-conflict", applied.values)
                    CivInventoryApplyResult.Applied -> Unit
                }
                if (tx.collected == null && tx.containerRelocation == null && tx.cargoDropoff == null && tx.cargoPickup == null && tx.trade == null) {
                    requestResurrectionAdmission(tx)
                    finishInventory(tx, null)
                } else persistCollectedReceipt(tx).whenCompleteSync(tasks) { _, failure ->
                    val problem = failure ?: runCatching {
                        rejectIf(closed || cities[city.id] != city, "stale-delivery")
                    }.exceptionOrNull()
                    if (problem != null) {
                        busy -= city.id; unhealthy += city.id
                        if (isNativePersistenceRetry(problem)) deferNativeRecovery(tx)
                        else {
                            blockedRecovery += city.id
                            log("recover inventory ${tx.id}", problem, tx.cityId)
                        }
                        updateHealth()
                    } else {
                        requestResurrectionAdmission(tx)
                        finishInventory(tx, null)
                    }
                }
            }
            else throw CivsRejection("inventory-conflict")
        }.onFailure {
            busy -= city.id; blockedRecovery += city.id; unhealthy += city.id
            log("recover inventory ${tx.id}", it, tx.cityId)
        }
    }

    fun bootstrap(): CompletableFuture<Unit> {
        storage.beginReceiptBootstrap()
        val result = tracked<Unit>()
        fun failed(failure: Throwable) {
            // Unknown local debits must never be mistaken for an empty journal, even if SQL is unavailable.
            plugin.logger.log(Level.SEVERE, "ArcCivs inventory journal bootstrap failed closed; player item changes remain blocked. Repair the local receipt error before restarting the server.", failure)
            CivsDiagnostics.event("inventory_bootstrap", "state" to "failed", "cause" to failure.javaClass.simpleName)
            result.completeExceptionally(failure)
        }
        store.loadInventoryTransactions().thenCombine(store.loadConsumedItems()) { transactions, consumed -> transactions to consumed }
            .whenCompleteSync(tasks) { loaded, failure ->
                if (closed) { result.completeExceptionally(CivsRejection("not-ready")); return@whenCompleteSync }
                runCatching {
                    if (failure != null) throw failure
                    val (receipts, consumed) = requireNotNull(loaded)
                    // Only the typed, validated no-value scenery format is retired. Native blocks and the
                    // saved city remain authoritative; neither old after-state nor its cursor is replayed.
                    val cosmetic = receipts.filter { it.autonomousVisual != null }.onEach { it.validated() }
                    val retained = receipts.filter { it.autonomousVisual == null }
                    require(retained.map { it.cityId }.distinct().size == retained.size) { "Multiple inventory receipts for one city" }
                    fun completeBootstrap() {
                        inventoryRecovery.values.forEach { storage.unlock(it.id) }
                        inventoryRecovery.clear()
                        blockedRecovery.clear()
                        inventoryRecovery.putAll(retained.associateBy { it.cityId })
                        val retainedIds = retained.mapTo(hashSetOf()) { it.id }
                        inventoryRetryAt.keys.retainAll(retainedIds)
                        activeRamReceipts.retainAll(retainedIds)
                        localReceipts.clear()
                        retained.filter { it.localInventoryEndpoints() && store.hasLocalInventory(it) }.forEach {
                            localReceipts += it.id
                            activeRamReceipts += it.id
                        }
                        storage.completeReceiptBootstrap(retained, consumed)
                        cosmetic.forEach { tx ->
                            CivsDiagnostics.event("autonomous_visual_journal_retired", "city" to tx.cityId,
                                "transaction" to tx.id, "native_replay" to false, "economic_credit" to false)
                        }
                        result.complete(Unit)
                    }
                    if (cosmetic.isEmpty()) completeBootstrap()
                    else {
                        val retirements = cosmetic.map { store.deleteInventoryTransaction(it.id) }
                        CompletableFuture.allOf(*retirements.toTypedArray()).whenCompleteSync(tasks) retirement@{ _, retirementFailure ->
                            if (closed) { result.completeExceptionally(CivsRejection("not-ready")); return@retirement }
                            runCatching {
                                if (retirementFailure != null) throw retirementFailure
                                completeBootstrap()
                            }.onFailure(::failed)
                        }
                    }
                }.onFailure(::failed)
        }
        return result
    }

    private fun <T> tracked(): CompletableFuture<T> = CompletableFuture<T>().also(track)
    private fun rejectIf(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
    private fun now() = clock()
    private fun log(context: String, error: Throwable, cityId: UUID) =
        CivsDiagnostics.record(Level.WARNING, cityId, "ArcCivs could not $context", plugin.logger, error)
}
