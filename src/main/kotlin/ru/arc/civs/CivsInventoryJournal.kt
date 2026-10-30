package ru.arc.civs

import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.paper.chunk.PaperChunkKey
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/** Main-thread receipt state machine: source fence, durable intent, native reconcile, publication and retirement. */
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
) {
    private val cities get() = settlements()
    private val closed get() = isClosed()
    private val collectedWorldBusy = mutableMapOf<UUID, UUID>()
    private val collectedNextAt = mutableMapOf<UUID, Long>()
    private val inventoryRecovery = linkedMapOf<UUID, CivInventoryTransaction>()
    private val blockedRecovery = mutableSetOf<UUID>()
    val pendingCount: Int get() = inventoryRecovery.size
    val blockedCount: Int get() = blockedRecovery.size
    fun pending(cityId: UUID): Boolean = cityId in inventoryRecovery
    fun pendingCount(cityId: UUID): Int = inventoryRecovery.values.count { it.cityId == cityId }
    fun pendingCityIds(): Set<UUID> = inventoryRecovery.keys.toSet()

    /** Validated durable receipts own bounded native reads even while their city is quarantined. */
    fun pendingNativeChunks(): List<Set<PaperChunkKey>> = inventoryRecovery.values.filter { it.cityId in cities }.map { tx ->
        val positions = tx.changes.map(CivInventorySlotChange::container) + tx.worldChanges.orEmpty().map(CivPlanBlock::position) +
            tx.collected?.containers.orEmpty().map(CivNativeContainerChange::container) +
            tx.collected?.ground.orEmpty().map(CivGroundItemChange::position) +
            tx.collected?.forestryLeafLoot?.sources.orEmpty().map(CivForestryLeafSource::leaf) +
            tx.containerRelocation?.containers.orEmpty().map(CivRelocatedContainer::position)
        val keys = positions.mapTo(linkedSetOf()) { PaperChunkKey(tx.after.worldId, it.x shr 4, it.z shr 4) }
        tx.upkeep?.work?.let { work ->
            val upkeep = requireNotNull(work.upkeep)
            val repair = upkeep.routeOverride ?: tx.after.roadRouteOverrides?.get(upkeep.source.id.toString())
            val cut = repair?.let { saved -> saved.runs().map { run -> saved.route.subList(run.first - 1, run.last + 2) }
                .filter { section -> section.any {
                    kotlin.math.abs(it.x.toLong() - upkeep.floor.x) <= 1 && kotlin.math.abs(it.z.toLong() - upkeep.floor.z) <= 1
                } }.flatten() }.orEmpty()
            val floors = listOf(upkeep.floor, work.standing) + upkeep.neighbours + cut
            for (floor in floors) for (x in ((floor.x - 2) shr 4)..((floor.x + 2) shr 4))
                for (z in ((floor.z - 2) shr 4)..((floor.z + 2) shr 4)) keys += PaperChunkKey(tx.after.worldId, x, z)
        }
        keys
    }

    fun collectionAvailable(worldId: UUID): Boolean = worldId !in collectedWorldBusy &&
        now() >= (collectedNextAt[worldId] ?: 0L) &&
        inventoryRecovery.values.none { it.collected != null && it.after.worldId == worldId }

    fun reserveCollection(worldId: UUID, receiptId: UUID) {
        collectedWorldBusy[worldId] = receiptId
        collectedNextAt[worldId] = now() + 5_000
    }

    fun releaseCollection(worldId: UUID, receiptId: UUID) { collectedWorldBusy.remove(worldId, receiptId) }

    fun submitCollected(city: Settlement, tx: CivInventoryTransaction): CompletableFuture<Settlement> = submitNative(city, tx)

    /** An evacuation owns the city and its exact source before asynchronous native persistence starts. */
    fun submitCargoPickup(city: Settlement, tx: CivInventoryTransaction,
        freshAdmission: () -> Unit = {}): CompletableFuture<Settlement> {
        require(tx.cargoPickup != null && tx.cityId == city.id)
        rejectIf(city.id in busy || city.id in unhealthy || cities[city.id] != city, "city-busy")
        busy += city.id
        return submitNative(city, tx, freshAdmission)
    }

    fun submitRelocation(city: Settlement, tx: CivInventoryTransaction,
        freshAdmission: () -> Unit = {}): CompletableFuture<Settlement> {
        require(tx.containerRelocation != null)
        return submitNative(city, tx, freshAdmission)
    }

    private fun submitNative(city: Settlement, tx: CivInventoryTransaction,
        freshAdmission: () -> Unit = {}): CompletableFuture<Settlement> {
        val id = tx.id
        val cityId = city.id
        val result = tracked<Settlement>()
        fun abort(failure: Throwable) {
            storage.unlock(id)
            collectedWorldBusy.remove(city.worldId, id)
            busy -= cityId
            result.completeExceptionally(failure)
            updateHealth()
        }
        val sourceFence = runCatching { storage.persistCollected(tx, beforeJournal = true) }
            .getOrElse { abort(it); return result }
        sourceFence.whenCompleteSync(tasks) { _, failure ->
            if (failure != null) return@whenCompleteSync abort(failure)
            val changed = runCatching {
                rejectIf(closed || result.isCancelled, "not-ready")
                rejectIf(cities[cityId] != city, "stale-delivery")
                requireFreshNative(tx)
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
        if (tx.containerRelocation != null) {
            val current = cities[tx.cityId] ?: throw CivsRejection("no-city")
            rejectIf(current != tx.after.copy(revision = tx.beforeRevision), "stale-delivery")
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
        require(tx.collected != null || tx.containerRelocation != null || tx.cargoPickup != null)
        requireCollectedCity(tx)
        if (tx.collected != null) validateNativeCollected(tx)
        else when (val state = if (tx.cargoPickup != null) storage.cargoPickupBeforeState(tx) else storage.relocationBeforeState(tx)) {
            CivInventoryApplyResult.Applied -> Unit
            is CivInventoryApplyResult.Retry -> throw CivsRejection("storage-unavailable", state.values)
            is CivInventoryApplyResult.Conflict -> throw CivsRejection("inventory-conflict", state.values)
            null -> error("Unclassified container relocation")
        }
    }

    fun submit(tx: CivInventoryTransaction): CompletableFuture<Settlement> {
        val cityId = tx.cityId
        val id = tx.id
        busy += cityId
        val result = tracked<Settlement>()
        store.saveInventoryTransaction(tx).whenCompleteSync(tasks) { _, failure ->
            if (failure != null) {
                val rejection = CivsDiagnostics.rejection(failure)
                if (rejection?.reason in setOf("storage-transaction-too-large", "ground-history-full", "ground-item-consumed")) {
                    // These admission failures precede the journal write; no native mutation has started.
                    storage.unlock(id)
                    if (tx.collected != null) collectedWorldBusy.remove(tx.after.worldId, tx.id)
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

    /** One immutable receipt covers native slots and the exact next city revision. */
    private fun applyInventory(tx: CivInventoryTransaction, result: CompletableFuture<Settlement>?) {
        if (closed) { result?.completeExceptionally(CivsRejection("not-ready")); return }
        fun fail(error: Throwable, retry: Boolean = false) {
            busy -= tx.cityId
            unhealthy += tx.cityId
            if (!retry) { blockedRecovery += tx.cityId; log("reconcile inventory ${tx.id}", error, tx.cityId) }
            result?.completeExceptionally(error)
            updateHealth()
        }
        runCatching {
            if (tx.collected != null || tx.containerRelocation != null || tx.cargoPickup != null) {
                if (result != null) {
                    if (tx.collected?.food != null) {
                        val state = storage.collectedBeforeState(tx)
                        if (state is CivInventoryApplyResult.Retry)
                            return fail(CivsRejection("inventory-recovery", state.values), retry = true)
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
            when (val applied = storage.reconcile(tx)) {
                is CivInventoryApplyResult.Retry -> {
                    if ((tx.cargoDropoff != null || tx.cargoPickup != null)) collectedNextAt[tx.after.worldId] = now() + 5_000
                    return fail(CivsRejection("inventory-recovery", applied.values), retry = true)
                }
                is CivInventoryApplyResult.Conflict -> return fail(CivsRejection("inventory-conflict", applied.values))
                CivInventoryApplyResult.Applied -> Unit
            }
            CivsDiagnostics.event("inventory_applied", "city" to tx.cityId, "transaction" to tx.id,
                "revision" to tx.after.revision, "slots" to tx.changes.size)
            fun commit() {
                val stale = runCatching { rejectIf(closed, "not-ready"); requireCollectedCity(tx) }.exceptionOrNull()
                if (stale != null) return fail(stale)
                storage.invalidateSupply(tx.cityId)
                store.save(tx.after).whenCompleteSync(tasks) { saved, failure ->
                    if (failure != null) return@whenCompleteSync fail(CivsRejection("inventory-recovery").also { it.initCause(failure) })
                    publish(requireNotNull(saved)).whenCompleteSync(tasks) { _, publishFailure ->
                        if (publishFailure != null) return@whenCompleteSync fail(publishFailure)
                        requestResurrectionAdmission(tx)
                        finishInventory(tx, result)
                    }
                }
            }
            if (tx.collected == null && tx.containerRelocation == null && tx.cargoDropoff == null && tx.cargoPickup == null) commit()
            else persistCollectedReceipt(tx).whenCompleteSync(tasks) { _, persistenceFailure ->
                if (persistenceFailure != null) fail(CivsRejection("inventory-recovery").also { it.initCause(persistenceFailure) },
                    retry = retryCollectedPersistence(tx, persistenceFailure))
                else commit()
            }
        }.onFailure { fail(it) }
    }

    /** Native after-state first, then a permanent UUID fence, then the caller may publish its one city credit. */
    private fun persistCollectedReceipt(tx: CivInventoryTransaction): CompletableFuture<Unit> {
        val result = tracked<Unit>()
        storage.persistCollected(tx).whenCompleteSync(tasks) { _, failure ->
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

    /** Retry the same locked receipt at most once per five seconds; exact mismatches remain blocked. */
    private fun retryCollectedPersistence(tx: CivInventoryTransaction, failure: Throwable): Boolean {
        if (tx.collected == null && tx.containerRelocation == null && tx.cargoDropoff == null && tx.cargoPickup == null ||
            generateSequence(failure) { it.cause }.take(8).none { it is CivsNativePersistenceRetry }) return false
        collectedNextAt[tx.after.worldId] = now() + 5_000
        return true
    }

    /** Untouched native endpoints can cancel safely; uncertain journal deletion retains the recovery lock. */
    private fun abortUntouchedInventory(tx: CivInventoryTransaction, result: CompletableFuture<Settlement>?, reason: Throwable) {
        store.deleteInventoryTransaction(tx.id).whenCompleteSync(tasks) { _, failure ->
            busy -= tx.cityId
            if (failure == null) {
                inventoryRecovery.remove(tx.cityId)
                unhealthy -= tx.cityId
                blockedRecovery -= tx.cityId
                storage.unlock(tx.id)
                if (tx.collected != null) collectedWorldBusy.remove(tx.after.worldId, tx.id)
                result?.completeExceptionally(reason)
            } else {
                unhealthy += tx.cityId
                blockedRecovery += tx.cityId
                log("cancel untouched inventory journal ${tx.id}", failure, tx.cityId)
                result?.completeExceptionally(CivsRejection("inventory-recovery"))
            }
            updateHealth()
        }
    }

    private fun finishInventory(tx: CivInventoryTransaction, result: CompletableFuture<Settlement>?) {
        store.deleteInventoryTransaction(tx.id).whenCompleteSync(tasks) { _, failure ->
            busy -= tx.cityId
            if (failure != null) {
                unhealthy += tx.cityId
                blockedRecovery += tx.cityId
                log("retire inventory journal ${tx.id}", failure, tx.cityId)
                result?.completeExceptionally(CivsRejection("inventory-recovery"))
            } else {
                inventoryRecovery.remove(tx.cityId)
                unhealthy -= tx.cityId
                blockedRecovery -= tx.cityId
                storage.unlock(tx.id)
                if (tx.collected != null) collectedWorldBusy.remove(tx.after.worldId, tx.id)
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
    }

    fun recover() {
        val tx = inventoryRecovery.values.firstOrNull { it.cityId !in busy && it.cityId !in blockedRecovery &&
            (it.collected == null && it.containerRelocation == null && it.cargoDropoff == null && it.cargoPickup == null ||
                now() >= (collectedNextAt[it.after.worldId] ?: 0L)) &&
            !storage.waitingForPlayer(it) } ?: return
        val city = cities[tx.cityId] ?: run { blockedRecovery += tx.cityId; return }
        busy += city.id
        runCatching {
            storage.lock(tx)
            if (city.revision == tx.beforeRevision) applyInventory(tx, null)
            else if (city.revision >= tx.after.revision && (city.revision != tx.after.revision || city == tx.after)) {
                if (city.revision == tx.after.revision || tx.residentTransfer != null || tx.resurrection != null || tx.collected != null ||
                    tx.containerRelocation != null || tx.cargoDropoff != null || tx.cargoPickup != null) when (val applied = storage.reconcile(tx)) {
                    is CivInventoryApplyResult.Retry -> {
                        if ((tx.cargoDropoff != null || tx.cargoPickup != null)) collectedNextAt[tx.after.worldId] = now() + 5_000
                        busy -= city.id; unhealthy += city.id; return@runCatching
                    }
                    is CivInventoryApplyResult.Conflict -> throw CivsRejection("inventory-conflict", applied.values)
                    CivInventoryApplyResult.Applied -> Unit
                }
                if (tx.collected == null && tx.containerRelocation == null && tx.cargoDropoff == null && tx.cargoPickup == null) {
                    requestResurrectionAdmission(tx)
                    finishInventory(tx, null)
                } else persistCollectedReceipt(tx).whenCompleteSync(tasks) { _, failure ->
                    val problem = failure ?: runCatching {
                        rejectIf(closed || cities[city.id] != city, "stale-delivery")
                    }.exceptionOrNull()
                    if (problem != null) {
                        busy -= city.id; unhealthy += city.id
                        if (!retryCollectedPersistence(tx, problem)) {
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
                        collectedWorldBusy.clear()
                        blockedRecovery.clear()
                        inventoryRecovery.putAll(retained.associateBy { it.cityId })
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
