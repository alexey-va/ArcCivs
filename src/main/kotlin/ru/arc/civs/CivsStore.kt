package ru.arc.civs

import com.google.gson.Gson
import ru.arc.persistence.AtomicFileStore
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

internal class CivsCheckpointFailure(val failures: Map<UUID, Throwable>) :
    IllegalStateException("Could not checkpoint settlements ${failures.keys}", failures.values.first()) {
    init { failures.values.drop(1).forEach(::addSuppressed) }
}

internal enum class CivsCheckpointReason { EXPLICIT_SAVE, PERIODIC, BEFORE_NATIVE, RELOAD, DRAIN }
internal data class CivsCheckpointStats(val dirtyCities: Int, val fullCityWrites: Map<CivsCheckpointReason, Long>)

/** Owned RAM state with bounded durable checkpoints. Explicit load rescans disk; IO stays on storage/SQL workers. */
class CivsStore(
    root: Path,
    private val executor: Executor,
    private val maximumCities: Int = 500,
    private val network: CivsNetworkStore? = null,
    private val checkpointClock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val root = root.toAbsolutePath().normalize()
    private val gson = Gson()
    private val lifecycleLock = Any()
    // ponytail: one bounded global lock serializes store paths; use keyed locks only if disk contention is measured.
    private val ioLock = Any()
    private var cityIndex: Map<UUID, CityIndex>? = null
    private var citySnapshots: MutableMap<UUID, Settlement>? = null
    private var deletedCityIndex: Map<UUID, CivsDeletedCity>? = null
    private val dirtyCities = linkedMapOf<UUID, Settlement>()
    private val checkpointDueAt = hashMapOf<UUID, Long>()
    private val fullCityWrites = java.util.EnumMap<CivsCheckpointReason, Long>(CivsCheckpointReason::class.java)
    @Volatile private var publishedCheckpointStats = CivsCheckpointStats(0, CivsCheckpointReason.entries.associateWith { 0L })
    private val durableRevisions = hashMapOf<UUID, Long>()
    private val fileRevisions = hashMapOf<UUID, Long>()
    private var inventoryHeads: MutableMap<UUID, InventoryCompletionHead>? = null
    private var pendingReceipts: MutableMap<UUID, CivInventoryTransaction>? = null
    private val activeReceipts = linkedMapOf<UUID, CivInventoryTransaction>()
    private val completedActiveReceipts = hashSetOf<UUID>()
    private var resolvedRoot: Path? = null
    private val completedReceipts = linkedMapOf<UUID, UUID>()
    private val directoryReady = hashSetOf<UUID>()
    // Recent order/retained/preview plans only, never whole villages. All access stays under ioLock.
    private val planCache = object : LinkedHashMap<UUID, CivPlacementPlan>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, CivPlacementPlan>): Boolean = size > 4
    }
    private val consumedItems = CivsConsumedItems()
    private data class PlanChanges(val sequence: Long = 0, val all: Long = 0, val byId: Map<UUID, Long> = emptyMap())
    private val planChanges = AtomicReference(PlanChanges())
    internal fun planGeneration(ids: List<UUID>): Long {
        val changes = planChanges.get()
        var generation = changes.all
        for (id in ids) generation = maxOf(generation, changes.byId[id] ?: 0)
        return generation
    }
    private var closed = false
    private var closeDrain: CompletableFuture<Unit>? = null

    init {
        require(maximumCities > 0) { "maximumCities must be positive" }
    }

    fun load(): CompletableFuture<List<Settlement>> = submitPlanChange {
        flushDirtyLocal(CivsCheckpointReason.RELOAD)
        planCache.clear()
        directoryReady.clear()
        inventoryHeads = null
        pendingReceipts = null
        val root = trustedRoot()
        if (network == null) CompletableFuture.completedFuture(reloadCityIndex(root).also { cities -> cities.forEach { verifyBuffers(root, it) } })
        else network.loadOwned(loadDeletedCities(root).values.toList(), { deleted -> fileContinuation {
            deleted.forEach { tombstone ->
                val old = deletedCityStore(root, tombstone.cityId).loadOrNull()?.deleted
                if (old != null) check(old.owner == tombstone.owner && old.revision == tombstone.revision &&
                    old.autonomous == tombstone.autonomous && (old.server == null || old.server == tombstone.server)) {
                    "Local and network city deletion histories disagree"
                }
                if (old == null || old.server == null) deletedCityStore(root, tombstone.cityId).write(DeletedCityDocument(SCHEMA_VERSION, tombstone))
            }
            if (deleted.isNotEmpty()) forceDirectories(root, "deleted-cities")
            reloadCityIndex(root)
        } }, { city -> fileContinuation { recoverDirectoryCity(city) } }).thenApply { cities ->
            synchronized(ioLock) {
                cities.forEach { verifyBuffers(root, it) }
                directoryReady.addAll(cities.map(Settlement::id))
            }
            cities
        }
    }.thenCompose { it }

    /** Only an accepted SQL transition may initialize or replace an older home-local snapshot. */
    private fun recoverDirectoryCity(city: Settlement): Settlement {
        CivsRules.validate(city)
        val root = trustedRoot()
        check(city.id !in requireNotNull(deletedCityIndex)) { "Pending directory city was already deleted" }
        val previous = currentCity(root, city.id)
        check(previous == null || previous.owner == city.owner && (previous.autonomy != null) == (city.autonomy != null) &&
            (previous.revision < city.revision || previous == city)) { "Pending directory state conflicts with acknowledged local city" }
        verifyBuffers(root, city)
        return writeCity(root, city)
    }

    fun lookup(playerId: UUID): CompletableFuture<CivsNetworkCity?> = synchronized(lifecycleLock) {
        if (closed) CompletableFuture.failedFuture(IllegalStateException("CivsStore is closed"))
        else network?.lookup(playerId) ?: CompletableFuture.completedFuture(null)
    }

    fun homeServer() = network?.server

    /** Accepted ordinary simulation state. Only its latest revision is checkpointed by flush(). */
    internal fun stage(city: Settlement): CompletableFuture<Settlement> {
        val snapshot = snapshotCity(city)
        return submit {
            val root = ramRoot()
            val before = requireNotNull(currentCity(root, snapshot.id)) { "Buffered city must already exist" }
            require(indexEntry(before).directoryMatches(snapshot)) { "Directory changes require a durable save" }
            require(before.inventory?.constructionBuffers == snapshot.inventory?.constructionBuffers &&
                before.inventory?.mode == snapshot.inventory?.mode) {
                "Construction storage changes require their durable inventory receipt"
            }
            require(before.pendingControls == snapshot.pendingControls && before.pendingDeletion == snapshot.pendingDeletion &&
                before.construction == snapshot.construction && before.deferredConstruction == snapshot.deferredConstruction &&
                before.buildings == snapshot.buildings) {
                "World mutation intents require a durable save"
            }
            require(loadInventoryTransactions(root).none { it.cityId == snapshot.id }) { "Pending inventory owns its city revision" }
            val accepted = saveLocal(snapshot, write = false)
            rememberCity(accepted)
            markDirty(accepted)
            accepted
        }
    }

    /** Lifecycle drain only. Periodic work admits one city through flushNext(), never this whole batch. */
    internal fun flush(): CompletableFuture<Unit> = submit { flushDirtyLocal(CivsCheckpointReason.DRAIN) }

    /** Last published counters only; Main never waits for the storage lane. */
    internal fun checkpointStats(): CivsCheckpointStats = publishedCheckpointStats

    private fun publishCheckpointStats() {
        publishedCheckpointStats = CivsCheckpointStats(dirtyCities.size,
            CivsCheckpointReason.entries.associateWith { fullCityWrites[it] ?: 0L })
    }

    /** One eligible dirty city per pulse; no catch-up loop or queued copies of intermediate revisions. */
    internal fun flushNext(): CompletableFuture<Unit> = submit {
        if (dirtyCities.isEmpty()) return@submit
        val now = checkpointClock()
        val id = dirtyCities.keys.asSequence().filter { requireNotNull(checkpointDueAt[it]) <= now }
            .minByOrNull { requireNotNull(checkpointDueAt[it]) } ?: return@submit
        var index = cityIndex
        var snapshots = citySnapshots
        try {
            val root = trustedRoot()
            ensureCityIndex(root)
            index = cityIndex
            snapshots = citySnapshots
            dirtyCities[id]?.let { writeCity(root, it, CivsCheckpointReason.PERIODIC) }
        } catch (failure: Exception) {
            // Only the already accepted RAM revision was written. Keep unrelated cities independent
            // of this failed path; explicit load still rescans uncertain disk outcomes.
            cityIndex = index
            citySnapshots = snapshots
            throw CivsCheckpointFailure(mapOf(id to failure))
        } finally {
            // A slow or failed write starts a fresh interval instead of repeating its overdue attempt.
            checkpointDueAt[id] = checkpointClock() + CHECKPOINT_INTERVAL_MILLIS
        }
    }

    private fun markDirty(city: Settlement) {
        val newlyDirty = dirtyCities.put(city.id, city) == null
        checkpointDueAt.getOrPut(city.id) {
            checkpointClock() + Math.floorMod(city.id.hashCode().toLong(), CHECKPOINT_INTERVAL_MILLIS)
        }
        if (newlyDirty) publishCheckpointStats()
    }

    private fun flushDirtyLocal(reason: CivsCheckpointReason) {
        val root = ramRoot()
        consumedItems.flush(root)
        if (dirtyCities.isEmpty()) {
            if (reason == CivsCheckpointReason.DRAIN) requireInventoryDrained()
            return
        }
        ensureCityIndex(root)
        val failures = linkedMapOf<UUID, Throwable>()
        dirtyCities.keys.toList().forEach { id -> dirtyCities[id]?.let { city ->
            val index = cityIndex
            val snapshots = citySnapshots
            try { writeCity(root, city, reason) } catch (failure: Exception) {
                failures[id] = failure
                cityIndex = index
                citySnapshots = snapshots
            }
        } }
        if (failures.isNotEmpty()) {
            cityIndex = null // Rescan uncertain outcomes after the other independent cities have drained.
            throw CivsCheckpointFailure(failures)
        }
        if (reason == CivsCheckpointReason.DRAIN) requireInventoryDrained()
    }

    private fun requireInventoryDrained() {
        val unfinished = activeReceipts.values.filter { it.id !in completedActiveReceipts }
        if (unfinished.isNotEmpty()) throw CivsCheckpointFailure(unfinished.associate { it.cityId to
            IllegalStateException("Inventory operation ${it.id} has not reached its accepted city state") })
    }

    private fun currentCity(root: Path, id: UUID): Settlement? {
        ensureCityIndex(root)
        return citySnapshots?.get(id)
    }

    private fun rememberCity(city: Settlement) {
        cityIndex = requireNotNull(cityIndex) + (city.id to indexEntry(city))
        requireNotNull(citySnapshots)[city.id] = city
    }

    fun save(city: Settlement): CompletableFuture<Settlement> {
        val proposed = snapshotCity(city)
        if (network == null) return submit { saveLocal(proposed) }
        return submit {
            val prepared = saveLocal(proposed, write = false)
            val root = trustedRoot()
            val previous = ensureCityIndex(root)[prepared.id]
            if (prepared.id in directoryReady && previous?.directoryMatches(prepared) == true) {
                val saved = if (previous.revision == prepared.revision &&
                    (durableRevisions[prepared.id] ?: -1L) >= prepared.revision) {
                    forceDirectories(root, "cities")
                    prepared
                } else writeCity(root, prepared)
                CompletableFuture.completedFuture(saved)
            } else {
                require(gson.toJson(CityDocument(SCHEMA_VERSION, prepared)).toByteArray(StandardCharsets.UTF_8).size <= MAX_CITY_DOCUMENT_BYTES) {
                    "City snapshot exceeds 256 KiB"
                }
                val before = currentCity(root, prepared.id)
                val previouslyReady = directoryReady.remove(prepared.id)
                network.save(prepared, before) { accepted -> fileContinuation { saveLocal(accepted) } }.thenApply { saved ->
                    synchronized(ioLock) { directoryReady += saved.id }
                    saved
                }.whenComplete { _, failure ->
                    if (previouslyReady && failure != null && CivsDiagnostics.rejection(failure)?.reason == "already-member")
                        synchronized(ioLock) { directoryReady += prepared.id }
                }
            }
        }.thenCompose { it }
    }

    private fun saveLocal(proposed: Settlement, write: Boolean = true): Settlement {
        CivsRules.validate(proposed)
        val trustedRoot = if (write) trustedRoot() else ramRoot()
        val index = ensureCityIndex(trustedRoot)
        val deleted = requireNotNull(deletedCityIndex)
        if (proposed.id in deleted) throw CivsRejection("city-deleted")
        val snapshot = if (proposed.autonomy == null && proposed.revision == 0L &&
            deleted.values.any { it.owner == proposed.owner && !it.autonomous })
            proposed.copy(stock = Stock(), inventory = proposed.inventory?.copy(materials = emptyMap())) else proposed
        val sameCity = index[snapshot.id]
        if (sameCity?.buffers != snapshot.inventory?.constructionBuffers ||
            !sameCity?.buffers.isNullOrEmpty() && sameCity?.mode != snapshot.inventory?.mode) {
            requireBufferTransition(trustedRoot, currentCity(trustedRoot, snapshot.id), snapshot)
        }
        require(sameCity == null || sameCity.owner == snapshot.owner) { "City id cannot be reassigned to another owner" }
        require(sameCity == null || sameCity.autonomous == (snapshot.autonomy != null)) { "City autonomy cannot change" }
        val otherParticipants = HashSet<UUID>()
        index.filterKeys { it != snapshot.id }.values.filterNot { it.autonomous }.forEach { existing ->
            otherParticipants.add(existing.owner)
            otherParticipants.addAll(existing.members)
        }
        if (CivsMembership.participants(snapshot).any(otherParticipants::contains)) throw CivsRejection("already-member")
        if (sameCity == null && index.size >= maximumCities) throw CivsRejection("settlement-limit")

        if (sameCity == null) {
            require(snapshot.revision == 0L) { "New settlement revision must start at zero" }
        } else if (snapshot.revision == sameCity.revision) {
            val accepted = requireNotNull(currentCity(trustedRoot, snapshot.id))
            require(accepted == proposed || accepted == snapshot) { "Same-revision city retry does not match saved state" }
            if (write && (durableRevisions[snapshot.id] ?: -1L) < snapshot.revision) return writeCity(trustedRoot, snapshot)
            if (write) forceDirectories(trustedRoot, "cities")
            return accepted
        } else {
            require(sameCity.revision < Long.MAX_VALUE && snapshot.revision == sameCity.revision + 1) {
                "Settlement revision is stale or skips a committed revision"
            }
        }

        return if (write) writeCity(trustedRoot, snapshot) else snapshot
    }

    private fun writeCity(root: Path, snapshot: Settlement, reason: CivsCheckpointReason = CivsCheckpointReason.EXPLICIT_SAVE): Settlement {
        val saved = try {
            consumedItems.flush(root)
            requireNotNull(cityStore(root, snapshot.id).write(CityDocument(SCHEMA_VERSION, snapshot)).city).also {
                // Persist the cities/settlements directory creation edges before retiring any item receipt.
                forceDirectories(root, "cities")
            }
        } catch (error: Exception) {
            cityIndex = null
            throw error
        }
        fullCityWrites[reason] = (fullCityWrites[reason] ?: 0L) + 1
        if (snapshot.id in checkpointDueAt) checkpointDueAt[snapshot.id] = checkpointClock() + CHECKPOINT_INTERVAL_MILLIS
        try {
            ensureCityIndex(root)
            rememberCity(saved)
            durableRevisions[saved.id] = saved.revision
            fileRevisions[saved.id] = saved.revision
            dirtyCities.remove(saved.id)
            retireCompletedReceipts(root, saved.id)
            return saved
        } finally { publishCheckpointStats() }
    }

    /** The canonical commit precedes local cleanup; a tombstone is durable before its old JSON disappears. */
    fun delete(city: Settlement): CompletableFuture<Unit> {
        val snapshot = snapshotCity(city)
        require(snapshot.inventory?.constructionBuffers.isNullOrEmpty()) { "Construction storage must be returned before deletion" }
        if (network == null) return submit {
            dirtyCities[snapshot.id]?.let { writeCity(trustedRoot(), it) }
            deleteLocal(snapshot, null)
        }
        return submit {
            val root = trustedRoot()
            dirtyCities[snapshot.id]?.let { writeCity(root, it) }
            val deleted = loadDeletedCities(root)[snapshot.id]
            if (deleted != null) deleted.copy(server = deleted.server ?: network.server.value).verify(snapshot, network.server.value)
            else {
                val saved = cityStore(root, snapshot.id).loadOrNull()?.city ?: throw CivsRejection("stale-city")
                if (saved.owner != snapshot.owner) throw CivsRejection("owner-required")
                if (saved != snapshot) throw CivsRejection("stale-city")
            }
            directoryReady -= snapshot.id
            network.delete(snapshot) { fileContinuation { deleteLocal(snapshot, network.server.value) } }
        }.thenCompose { it }
    }

    private fun deleteLocal(city: Settlement, home: String?) {
        CivsRules.validate(city)
        val trustedRoot = trustedRoot()
        val deleted = deletedCityIndex ?: loadDeletedCities(trustedRoot).also { deletedCityIndex = it }
        val existing = deleted[city.id]
        try {
            if (existing != null) existing.copy(server = existing.server ?: home).verify(city, home)
            else {
                val saved = cityStore(trustedRoot, city.id).loadOrNull()?.city
                if (saved == null) throw CivsRejection("stale-city")
                if (saved.owner != city.owner) throw CivsRejection("owner-required")
                if (saved != city) throw CivsRejection("stale-city")
                require(deleted.size < MAX_DELETED_CITIES) { "City deletion history exceeds its limit" }
                deletedCityStore(trustedRoot, city.id).write(DeletedCityDocument(SCHEMA_VERSION,
                    CivsDeletedCity(city.id, city.owner, home, city.revision, System.currentTimeMillis(), city.autonomy != null)))
                forceDirectories(trustedRoot, "deleted-cities")
            }
            forceDirectories(trustedRoot, "deleted-cities")
            cityStore(trustedRoot, city.id).deleteIfExists()
            inventoryHeadStore(trustedRoot, city.id).deleteIfExists()
            inventoryHeads?.remove(city.id)
            dirtyCities.remove(city.id)
            checkpointDueAt.remove(city.id)
            durableRevisions.remove(city.id)
            fileRevisions.remove(city.id)
            publishCheckpointStats()
        } finally {
            // A failed atomic write can have an unknown outcome: rescan its durable fence before the next action.
            cityIndex = null
            citySnapshots = null
            deletedCityIndex = null
        }
    }

    fun savePlan(plan: CivPlacementPlan): CompletableFuture<CivPlacementPlan> {
        val snapshot = plan.copy(building = snapshotBuilding(plan.building), blocks = plan.blocks.toList(), terrain = plan.terrain?.let {
            it.copy(blocks = it.blocks.toList(), route = it.route.toList(), waterFoundations = it.waterFoundations?.toList(),
                requiredGrass = it.requiredGrass?.toList(), paidGateFloors = it.paidGateFloors?.toList(),
                paidWaterFloors = it.paidWaterFloors?.toList())
        })
        return submitPlanChange(snapshot.id) {
            // A failed atomic write can have reached rename; never retain its previous cached value.
            planCache.remove(snapshot.id)
            validatePlan(snapshot)
            val trustedRoot = trustedRoot()
            cachePlan(requireNotNull(planStore(trustedRoot, snapshot.id).write(PlanDocument(SCHEMA_VERSION, snapshot)).plan))
        }
    }

    /** Reuse owned committed plans until save/delete or explicit load(); no disk polling on a hit. */
    fun loadPlan(id: UUID): CompletableFuture<CivPlacementPlan?> = submit { loadPlanLocal(id) }

    /** Finish the pure projection on the same storage task, before a completed future can reach Main. */
    internal fun loadRetainedPlanSource(id: UUID): CompletableFuture<CivRetainedPlanSource?> = submit {
        loadPlanLocal(id)?.let(CivRetainedPlanSource::from)
    }

    private fun loadPlanLocal(id: UUID): CivPlacementPlan? {
        planCache[id]?.let { return it }
        val trustedRoot = trustedRoot()
        return planStore(trustedRoot, id).loadOrNull()?.plan?.let(::cachePlan)
    }

    fun deletePlan(id: UUID): CompletableFuture<Boolean> = submitPlanChange(id) {
        planCache.remove(id)
        val trustedRoot = trustedRoot()
        val store = planStore(trustedRoot, id)
        val existing = store.loadOrNull() ?: return@submitPlanChange false
        requireNotNull(existing.plan)
        store.deleteIfExists()
    }

    /** Exact items are immutable home-local documents. A city or SQL snapshot only carries their balances. */
    internal fun saveConstructionStorage(value: CivConstructionStorageSnapshot): CompletableFuture<CivConstructionStoragePart> {
        val snapshot = value.copy(items = value.items.toList())
        return submit {
            val root = trustedRoot()
            snapshot.validate()
            verifyConstructionPlan(root, snapshot)
            val bytes = snapshot.encoded()
            val part = snapshot.initial(bytes)
            val file = constructionFile(root, part.id)
            val stored = file.loadOrNull()
            if (stored == null) file.write(bytes) else require(stored.contentEquals(bytes)) { "Construction storage id was reused" }
            check(readPart(root, snapshot.owner, part) == snapshot)
            forceDirectories(root, "construction-buffers")
            part
        }
    }

    internal fun loadConstructionStorage(owner: CivConstructionStorageOwner, part: CivConstructionStoragePart) =
        submit { readPart(trustedRoot(), owner, part) }

    private fun constructionFile(root: Path, id: UUID) = AtomicFileStore(
        root = root, relativePath = Path.of("construction-buffers", "$id.json"), maxBytes = MAX_INVENTORY_TRANSACTION_BYTES,
        encode = { it }, decode = { it }, validate = { require(it.isNotEmpty()) },
    )

    private fun readPart(root: Path, owner: CivConstructionStorageOwner, part: CivConstructionStoragePart): CivConstructionStorageSnapshot {
        val bytes = requireNotNull(constructionFile(root, part.id).loadOrNull()) { "Construction storage snapshot is missing" }
        require(CivsGateRefit.sha256(bytes) == part.sha256) { "Construction storage snapshot bytes changed" }
        val snapshot = decodeJson(bytes, CivConstructionStorageSnapshot::class.java)
        snapshot.validate()
        require(snapshot.id == part.id && snapshot.owner == owner && snapshot.cell == part.cell) {
            "Construction storage snapshot identity changed"
        }
        snapshot.contents(part)
        return snapshot
    }

    private fun verifyConstructionPlan(root: Path, snapshot: CivConstructionStorageSnapshot) {
        val plan = planCache[snapshot.planId] ?: requireNotNull(planStore(root, snapshot.planId).loadOrNull()?.plan)
            .also { cachePlan(it) }
        require(plan.cityId == snapshot.owner.cityId && plan.worldId == snapshot.owner.worldId &&
            civsSameBuildingGeometry(plan.building, snapshot.owner.target) && plan.capturedSha256 == snapshot.owner.schematicSha256 &&
            snapshot.cell in plan.blocks) { "Construction storage does not match the accepted placement" }
    }

    private fun verifyBuffers(root: Path, city: Settlement) {
        city.inventory?.constructionBuffers.orEmpty().values.forEach { group ->
            require(group.owner.matches(city))
            group.parts.forEach { readPart(root, group.owner, it) }
        }
    }

    private fun verifyBufferReceipt(root: Path, tx: CivInventoryTransaction, admitting: Boolean = false) {
        if (admitting) tx.containerRelocation?.unregister?.let { retired -> retired.planId?.let { id ->
            val plan = planCache[id] ?: requireNotNull(planStore(root, id).loadOrNull()?.plan).also { cachePlan(it) }
            require(plan.cityId == tx.cityId && plan.worldId == tx.after.worldId && plan.building == retired.target &&
                plan.capturedSha256 != null && retired.cells.all { it in plan.blocks }) { "Retired container does not match its accepted placement" }
        } }
        tx.containerRelocation?.let { relocation -> relocation.buffer?.let { move ->
            val proof = readPart(root, move.owner, move.before ?: requireNotNull(move.after))
            if (admitting && move.before == null) verifyConstructionPlan(root, proof)
            move.validateSnapshot(proof, relocation.containers)
        } }
        tx.bufferPickup?.let { pickup ->
            val group = pickup.beforeGroup
            val after = tx.after.inventory?.constructionBuffers?.get(pickup.source.toString())?.parts.orEmpty().associateBy { it.id }
            group.parts.filter { after[it.id] != it }.forEach { part ->
                val snapshot = readPart(root, group.owner, part)
                after[part.id]?.let(snapshot::contents)
            }
        }
    }

    internal fun verifyConstructionReceipt(tx: CivInventoryTransaction): CompletableFuture<Unit> =
        submit { verifyBufferReceipt(trustedRoot(), tx) }

    private fun requireBufferTransition(root: Path, old: Settlement?, next: Settlement) {
        next.inventory?.let { old?.inventory?.requireStorageMode(it.mode) }
        if (old?.inventory?.constructionBuffers == next.inventory?.constructionBuffers) return
        val tx = loadInventoryTransactions(root).singleOrNull { it.cityId == next.id && it.after == next }
            ?: error("Construction storage change requires its pending inventory receipt")
        val before = tx.containerRelocation?.takeIf { it.buffer != null }?.before(next)
            ?: tx.bufferPickup?.before(next) ?: error("Inventory receipt cannot change construction storage")
        require(before == old) { "Construction storage receipt has a different canonical before-city" }
        verifyBufferReceipt(root, tx)
    }

    /** Detached in-process item intent. New operations are checkpointed with their city, never per operation. */
    fun saveInventoryTransaction(transaction: CivInventoryTransaction): CompletableFuture<CivInventoryTransaction> {
        val snapshot = transaction.copy(after = snapshotCity(transaction.after), changes = transaction.changes.toList(),
            playerDebit = transaction.playerDebit?.let { it.copy(changes = it.changes.toList()) },
            residentTransfer = transaction.residentTransfer?.let { it.copy(before = it.before.toList(), changes = it.changes.toList()) },
            trade = transaction.trade?.let { it.copy(changes = it.changes.toList()) },
            worldChanges = transaction.worldChanges?.toList(),
            mine = transaction.mine?.detached(),
            mineOpening = transaction.mineOpening?.detached(),
            authoredVegetation = transaction.authoredVegetation?.detached(),
            upkeep = transaction.upkeep?.detached(),
            cargoDropoff = transaction.cargoDropoff?.detached(),
            cargoPickup = transaction.cargoPickup?.detached(),
            bufferPickup = transaction.bufferPickup?.let { pickup -> pickup.copy(workerBefore = snapshotWorker(pickup.workerBefore),
                inventoryBefore = requireNotNull(snapshotCity(pickup.before(transaction.after)).inventory),
                withdrawn = pickup.withdrawn.toMap(), containers = pickup.containers.toList()) },
            gateRefit = transaction.gateRefit?.detached(),
            warehouseRefit = transaction.warehouseRefit?.detached(),
            autonomousVisual = transaction.autonomousVisual?.let { it.copy(
                buildingBefore = snapshotBuilding(it.buildingBefore),
                mineProgressBefore = it.mineProgressBefore?.toMap(),
                minePlansBefore = it.minePlansBefore?.mapValues { (_, state) -> CivsMineProgress.detached(state) }) },
            collected = transaction.collected?.let { it.copy(before = it.before.toList(), ground = it.ground.toList(), containers = it.containers.toList(),
                forestryLeafLoot = it.forestryLeafLoot?.copy(sources = it.forestryLeafLoot.sources.toList())) },
            containerRelocation = transaction.containerRelocation?.let { it.copy(building = snapshotBuilding(it.building),
                unregister = it.unregister?.let { proof -> proof.copy(target = snapshotBuilding(proof.target),
                    registeredBefore = proof.registeredBefore.toList(), cells = proof.cells.toList()) },
                containers = it.containers.map { holder -> holder.copy(before = holder.before.toList(), after = holder.after.toList()) }) },
            resurrection = transaction.resurrection?.let { it.copy(corpse = it.corpse.copy(worker = snapshotWorker(it.corpse.worker))) })
        return submit {
            snapshot.validated()
            val trustedRoot = ramRoot()
            ensureCityIndex(trustedRoot)
            val pending = loadInventoryTransactions(trustedRoot)
            val existing = pending.firstOrNull { it.id == snapshot.id }
            require(snapshot.id !in completedActiveReceipts && snapshot.id !in completedReceipts &&
                loadInventoryHeads(trustedRoot).values.none { it.transactionId == snapshot.id }) {
                "Inventory transaction is already completed"
            }
            verifyBufferReceipt(trustedRoot, snapshot, admitting = existing == null)
            if (existing != null) {
                require(existing == snapshot) { "Inventory transaction retry differs from its accepted intent" }
                return@submit existing
            }
            val current = currentCity(trustedRoot, snapshot.cityId) ?: throw CivsRejection("no-city")
            if (current.revision != snapshot.beforeRevision || !indexEntry(current).directoryMatches(snapshot.after))
                throw CivsRejection("stale-city")
            require(pending.size < maximumCities && pending.none { it.cityId == snapshot.cityId }) {
                "City already has an inventory transaction or journal capacity is full"
            }
            require(snapshot.playerId() == null || pending.none { it.playerId() == snapshot.playerId() }) {
                "Player already has an inventory transaction"
            }
            val groundIds = snapshot.collected?.ground.orEmpty().mapTo(hashSetOf(), CivGroundItemChange::entityId)
            require(pending.none { it.collected?.ground.orEmpty().any { change -> change.entityId in groundIds } }) {
                "Ground item already has an inventory transaction"
            }
            consumedItems.admit(trustedRoot, snapshot, pending)
            activeReceipts[snapshot.id] = snapshot
            snapshot
        }
    }

    /** Native slots match their after-state. New city revisions remain in RAM; old disk receipts finish durably. */
    internal fun completeInventoryTransaction(transaction: CivInventoryTransaction): CompletableFuture<Settlement> = submit {
        val root = ramRoot()
        val current = requireNotNull(currentCity(root, transaction.cityId)) { "Inventory city disappeared" }
        activeReceipts[transaction.id]?.let { receipt ->
            require(receipt == transaction) { "Inventory completion differs from its accepted intent" }
            val accepted = if (current.revision == receipt.beforeRevision) saveLocal(receipt.after, write = false)
                else current.also {
                    require(it.revision >= receipt.after.revision && (it.revision != receipt.after.revision || it == receipt.after)) {
                        "Inventory completion has a different city revision"
                    }
                }
            require(indexEntry(current).directoryMatches(accepted)) { "Inventory completion cannot change the city directory" }
            if (receipt.id in completedActiveReceipts) return@submit accepted
            consumedItems.stage(root, receipt.after.worldId, receipt.collected?.ground.orEmpty().map(CivGroundItemChange::entityId))
            // This explicit administrator repair keeps its existing audit; ordinary inventory intents never write it.
            writeAuthoredVegetationAudit(root, receipt)
            rememberCity(accepted)
            markDirty(accepted)
            completedActiveReceipts += receipt.id
            return@submit accepted
        }
        val heads = loadInventoryHeads(root)
        val old = heads[transaction.cityId]
        if (old?.transactionId == transaction.id) {
            require(old.afterRevision == transaction.after.revision && current.revision >= old.afterRevision &&
                (current.revision != old.afterRevision || current == transaction.after)) { "Completed inventory retry differs" }
            forceDirectories(root, "inventory-completed")
            return@submit current
        }
        val receipt = loadInventoryTransactions(root).singleOrNull { it.id == transaction.id }
            ?: error("Inventory completion requires its durable pending receipt")
        require(receipt == transaction) { "Inventory completion differs from its durable receipt" }
        val accepted = if (current.revision == receipt.beforeRevision) saveLocal(receipt.after, write = false)
            else current.also {
                require(it.revision >= receipt.after.revision && (it.revision != receipt.after.revision || it == receipt.after)) {
                    "Inventory completion has a different city revision"
                }
            }
        require(indexEntry(current).directoryMatches(accepted)) { "Inventory completion cannot change the city directory" }
        require(old == null || old.afterRevision < receipt.after.revision) { "Inventory completion moved backwards" }
        writeAuthoredVegetationAudit(root, receipt)
        val head = InventoryCompletionHead(SCHEMA_VERSION, receipt.cityId, receipt.id, receipt.after.revision)
        try {
            inventoryHeadStore(root, receipt.cityId).write(head)
            forceDirectories(root, "inventory-completed")
        } catch (failure: Exception) {
            inventoryHeads = null
            pendingReceipts = null
            cityIndex = null
            throw failure
        }
        heads[receipt.cityId] = head
        pendingReceipts?.remove(receipt.id)
        completedReceipts[receipt.id] = receipt.cityId
        rememberCity(accepted)
        durableRevisions[accepted.id] = maxOf(durableRevisions[accepted.id] ?: -1L, receipt.after.revision)
        if ((fileRevisions[accepted.id] ?: -1L) < accepted.revision) markDirty(accepted)
        // This one-time compatibility path retires old acknowledged receipts into a full snapshot.
        writeCity(root, accepted)
    }

    fun loadInventoryTransactions(): CompletableFuture<List<CivInventoryTransaction>> = submit {
        val trustedRoot = trustedRoot()
        inventoryHeads = null
        pendingReceipts = null
        val transactions = loadInventoryTransactions(trustedRoot)
        ensureCityIndex(trustedRoot)
        loadInventoryHeads(trustedRoot).forEach { (id, head) ->
            completedCity(trustedRoot, requireNotNull(cityStore(trustedRoot, id).loadOrNull()?.city) {
                "Completed inventory city is missing"
            }, head)
            if ((fileRevisions[id] ?: -1L) < head.afterRevision)
                writeCity(trustedRoot, requireNotNull(currentCity(trustedRoot, id)))
            else retireCompletedReceipts(trustedRoot, id)
        }
        transactions.forEach { verifyBufferReceipt(trustedRoot, it) }
        // Recovery may resume a prior uncertain write immediately after this ACK, without saving it again.
        if (!pendingReceipts.isNullOrEmpty()) forceDirectories(trustedRoot)
        transactions
    }

    internal fun loadConsumedItems(): CompletableFuture<CivConsumedItemIndex> = submit {
        val root = trustedRoot()
        consumedItems.flush(root)
        consumedItems.load(root)
    }

    internal fun markConsumedItems(transaction: CivInventoryTransaction): CompletableFuture<CivConsumedItemIndex> = submit {
        val root = ramRoot()
        require(loadInventoryTransactions(root).singleOrNull { it.id == transaction.id } == transaction) {
            "Consumed item marker requires its exact accepted inventory intent"
        }
        val ids = transaction.collected?.ground.orEmpty().map(CivGroundItemChange::entityId)
        if (transaction.id in activeReceipts) consumedItems.stage(root, transaction.after.worldId, ids)
        else consumedItems.mark(root, transaction.after.worldId, ids)
    }

    fun deleteInventoryTransaction(id: UUID): CompletableFuture<Boolean> = submit {
        if (activeReceipts.remove(id) != null) {
            completedActiveReceipts.remove(id)
            return@submit true
        }
        if (pendingReceipts != null && id !in requireNotNull(pendingReceipts) && id !in completedReceipts)
            return@submit false
        val trustedRoot = trustedRoot()
        loadInventoryTransactions(trustedRoot)
        completedReceipts[id]?.let { cityId ->
            // Logical retirement releases native locks, but its payload remains the checkpoint until
            // a later completed receipt or a full city snapshot durably covers it.
            retireCompletedReceipts(trustedRoot, cityId)
            return@submit true
        }
        val store = inventoryTransactionStore(trustedRoot, id)
        val transaction = pendingReceipts?.get(id)
        transaction?.let { writeAuthoredVegetationAudit(trustedRoot, it) }
        val deleted = if (transaction == null) false else store.deleteIfExists()
        // A retry after an uncertain delete must acknowledge the directory even when the file is now absent.
        forceDirectories(trustedRoot)
        pendingReceipts?.remove(id)
        deleted
    }

    private fun writeAuthoredVegetationAudit(trustedRoot: Path, transaction: CivInventoryTransaction) {
        val id = transaction.id
        transaction.authoredVegetation?.let { receipt ->
            // Keep this explicit repair's exact AIR/source images after normal journal retirement, without a city export.
            val bytes = gson.toJson(mapOf("transaction" to id, "city" to transaction.cityId,
                "world" to transaction.after.worldId, "before_revision" to transaction.beforeRevision,
                "after_revision" to transaction.after.revision, "receipt" to receipt)).toByteArray(StandardCharsets.UTF_8)
            val audit = AtomicFileStore(trustedRoot, Path.of("authored-vegetation-repairs", "$id.json"),
                1024L * 1024L, { value: ByteArray -> value }, { it })
            val previous = audit.loadOrNull()
            require(previous == null || previous.contentEquals(bytes)) { "Authored vegetation audit differs from its receipt" }
            if (previous == null) audit.write(bytes)
        }
    }

    private fun retireCompletedReceipts(root: Path, cityId: UUID) {
        val head = loadInventoryHeads(root)[cityId] ?: return
        val covered = (fileRevisions[cityId] ?: -1L) >= head.afterRevision
        val retired = completedReceipts.filter { (id, city) -> city == cityId && (covered || id != head.transactionId) }.keys.toList()
        for (id in retired) {
            inventoryTransactionStore(root, id).deleteIfExists()
            forceDirectories(root)
            completedReceipts.remove(id)
        }
    }

    private fun forceDirectories(trustedRoot: Path, name: String = "inventory-transactions") {
        val directory = trustedRoot.resolve(name)
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "State directory is not a real directory" }
        // AtomicFileStore covers the file/rename; include both directory creation edges before native mutation.
        listOf(directory, trustedRoot, requireNotNull(trustedRoot.parent)).forEach {
            FileChannel.open(it, StandardOpenOption.READ).use { channel -> channel.force(true) }
        }
    }

    /** A pure path accessor for Core's later asynchronous schematic open. */
    fun schematicRoot(): Path = root.resolve("captured")

    /** Persists one immutable order capture; retries may repeat identical bytes, never replace them. */
    fun saveSchematic(id: UUID, bytes: ByteArray): CompletableFuture<Unit> {
        val snapshot = if (bytes.size in 1..MAX_SCHEMATIC_BYTES) bytes.copyOf() else null
        return submit {
            val captured = requireNotNull(snapshot) { "Captured schematic must be nonempty and at most 16 MiB" }
            val trustedRoot = trustedRoot()
            val store = schematicStore(trustedRoot, id)
            val existing = store.loadOrNull()
            if (existing == null) {
                store.write(captured)
            } else {
                require(existing.contentEquals(captured)) { "Order already has different captured schematic bytes" }
            }
            Unit
        }
    }

    fun loadSchematic(id: UUID): CompletableFuture<ByteArray?> = submit {
        schematicStore(trustedRoot(), id).loadOrNull()?.copyOf()
    }

    fun deleteSchematic(id: UUID): CompletableFuture<Boolean> = submit {
        val trustedRoot = trustedRoot()
        val store = schematicStore(trustedRoot, id)
        if (store.loadOrNull() == null) return@submit false
        store.deleteIfExists()
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            // The owning executor is drained after this store. No stopped Main callback is needed.
            closeDrain = enqueue { flushDirtyLocal(CivsCheckpointReason.DRAIN) }
        }
    }

    internal fun drained(): CompletableFuture<Unit> = synchronized(lifecycleLock) {
        closeDrain ?: CompletableFuture.completedFuture(Unit)
    }

    private fun reloadCityIndex(trustedRoot: Path): List<Settlement> {
        cityIndex = null
        deletedCityIndex = null
        val deleted = loadDeletedCities(trustedRoot)
        deletedCityIndex = deleted
        val cities = loadCities(trustedRoot, deleted.keys)
        val effective = cities.associateByTo(linkedMapOf(), Settlement::id)
        for ((id, buffered) in dirtyCities.toMap()) {
            val durable = effective[id]
            if (id in deleted || durable != null && durable.revision > buffered.revision) dirtyCities.remove(id)
            else {
                require(durable != null && indexEntry(durable).directoryMatches(buffered) &&
                    (durable.revision != buffered.revision || durable == buffered)) { "Buffered city conflicts with durable state" }
                effective[id] = buffered
            }
        }
        citySnapshots = effective
        cityIndex = effective.mapValues { indexEntry(it.value) }
        publishCheckpointStats()
        return effective.values.toList()
    }

    private fun ensureCityIndex(trustedRoot: Path): Map<UUID, CityIndex> =
        cityIndex ?: run {
            reloadCityIndex(trustedRoot)
            requireNotNull(cityIndex)
        }

    private fun indexEntry(city: Settlement) = CityIndex(city.owner, city.name, city.members.toSet(), city.revision, city.autonomy != null, city.inventory?.constructionBuffers, city.inventory?.mode)

    private fun loadCities(trustedRoot: Path, deleted: Set<UUID>): List<Settlement> {
        durableRevisions.clear()
        fileRevisions.clear()
        val directory = trustedRoot.resolve("cities")
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            require(loadInventoryHeads(trustedRoot).keys.all { it in deleted }) { "Completed inventory city is missing" }
            return emptyList()
        }
        require(!Files.isSymbolicLink(directory)) { "City directory must not be a symbolic link" }
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "City path is not a directory" }

        val files = ArrayList<Path>()
        Files.newDirectoryStream(directory).use { entries ->
            for (entry in entries) {
                if (!entry.fileName.toString().endsWith(".json")) continue
                if (idFromJsonFilename(entry) in deleted) continue
                require(files.size < maximumCities) { "City directory exceeds the configured limit" }
                files.add(entry)
            }
        }

        val ids = HashSet<UUID>(files.size)
        val participants = HashSet<UUID>()
        val heads = loadInventoryHeads(trustedRoot)
        val cities = files.sortedBy { it.fileName.toString() }.map { file ->
            val id = idFromJsonFilename(file)
            val record = requireNotNull(cityStore(trustedRoot, id).loadOrNull()) {
                "City record disappeared during load"
            }
            val checkpoint = requireNotNull(record.city)
            fileRevisions[id] = checkpoint.revision
            val head = heads[id]
            val city = if (head == null || checkpoint.revision >= head.afterRevision) checkpoint else {
                completedCity(trustedRoot, checkpoint, head).also { recovered ->
                    if ((dirtyCities[id]?.revision ?: -1L) < recovered.revision) markDirty(recovered)
                }
            }
            durableRevisions[id] = city.revision
            require(city.id == id) { "City id does not match its filename" }
            require(ids.add(city.id)) { "Duplicate city id" }
            require(CivsMembership.participants(city).all(participants::add)) {
                "Player belongs to multiple settlements"
            }
            city
        }
        require(heads.keys.all { it in ids || it in deleted }) { "Completed inventory city is missing" }
        return cities
    }

    private fun loadDeletedCities(trustedRoot: Path): Map<UUID, CivsDeletedCity> {
        val directory = trustedRoot.resolve("deleted-cities")
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return emptyMap()
        require(!Files.isSymbolicLink(directory) && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            "City deletion directory is invalid"
        }
        val deleted = linkedMapOf<UUID, CivsDeletedCity>()
        Files.newDirectoryStream(directory).use { files ->
            files.forEach { file ->
                if (file.fileName.toString().endsWith(".json")) {
                    require(deleted.size < MAX_DELETED_CITIES) { "City deletion history exceeds its limit" }
                    val id = idFromJsonFilename(file)
                    deleted[id] = requireNotNull(deletedCityStore(trustedRoot, id).loadOrNull()?.deleted) {
                        "City deletion record disappeared during load"
                    }
                }
            }
        }
        return deleted
    }

    private fun idFromJsonFilename(file: Path): UUID {
        val name = file.fileName.toString()
        require(name.endsWith(".json")) { "Unexpected city file" }
        val idText = name.removeSuffix(".json")
        val id = runCatching { UUID.fromString(idText) }.getOrElse {
            throw IllegalArgumentException("City filename is not a UUID", it)
        }
        require(id.toString() == idText) { "City filename must use canonical UUID form" }
        return id
    }

    private fun loadInventoryTransactions(trustedRoot: Path): List<CivInventoryTransaction> {
        pendingReceipts?.let { return it.values.toList() + activeReceipts.values }
        val heads = loadInventoryHeads(trustedRoot)
        val directory = trustedRoot.resolve("inventory-transactions")
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            pendingReceipts = linkedMapOf()
            return activeReceipts.values.toList()
        }
        require(!Files.isSymbolicLink(directory) && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            "Inventory transaction directory is invalid"
        }
        val ids = mutableListOf<UUID>()
        Files.newDirectoryStream(directory).use { files ->
            files.forEach { file ->
                if (file.fileName.toString().endsWith(".json")) {
                    require(ids.size.toLong() < maximumCities.toLong() * 2) { "Inventory transaction directory exceeds its limit" }
                    ids += idFromJsonFilename(file)
                }
            }
        }
        completedReceipts.clear()
        val completedIds = heads.values.associateBy(InventoryCompletionHead::transactionId)
        val records = ids.sortedBy(UUID::toString).mapNotNull { id ->
            completedIds[id]?.let { head ->
                completedReceipts[id] = head.cityId
                return@mapNotNull null
            }
            val receipt = requireNotNull(inventoryTransactionStore(trustedRoot, id).loadOrNull()?.transaction) {
                "Inventory transaction disappeared during load"
            }
            if ((heads[receipt.cityId]?.afterRevision ?: -1L) >= receipt.after.revision) {
                completedReceipts[id] = receipt.cityId
                null
            } else receipt
        }
        require(records.map { it.cityId }.distinct().size == records.size) { "City has multiple pending inventory transactions" }
        val players = records.mapNotNull(CivInventoryTransaction::playerId)
        require(players.distinct().size == players.size) { "Player has multiple pending inventory transactions" }
        val ground = records.flatMap { it.collected?.ground.orEmpty() }.map(CivGroundItemChange::entityId)
        require(ground.distinct().size == ground.size) { "Ground item has multiple pending inventory transactions" }
        require(records.none { it.id in activeReceipts || activeReceipts.values.any { active -> active.cityId == it.cityId } }) {
            "Disk inventory receipt conflicts with an active RAM intent"
        }
        pendingReceipts = records.associateByTo(linkedMapOf(), CivInventoryTransaction::id)
        return records + activeReceipts.values
    }

    private fun loadInventoryHeads(root: Path): MutableMap<UUID, InventoryCompletionHead> {
        inventoryHeads?.let { return it }
        val directory = root.resolve("inventory-completed")
        val heads = linkedMapOf<UUID, InventoryCompletionHead>()
        val deleted = deletedCityIndex ?: loadDeletedCities(root)
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(directory) && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                "Inventory completion directory is invalid"
            }
            Files.newDirectoryStream(directory).use { files -> files.forEach { file ->
                if (file.fileName.toString().endsWith(".json")) {
                    val id = idFromJsonFilename(file)
                    if (id !in deleted) {
                        require(heads.size < maximumCities) { "Inventory completion directory exceeds its limit" }
                        heads[id] = requireNotNull(inventoryHeadStore(root, id).loadOrNull())
                    }
                }
            } }
        }
        inventoryHeads = heads
        return heads
    }

    private fun completedCity(root: Path, checkpoint: Settlement, head: InventoryCompletionHead): Settlement {
        if (checkpoint.revision >= head.afterRevision) return checkpoint
        val receipt = inventoryTransactionStore(root, head.transactionId).loadOrNull()?.transaction
        if (receipt != null) require(receipt.cityId == checkpoint.id && receipt.after.revision == head.afterRevision) {
            "Completed inventory checkpoint has a different receipt identity"
        }
        requireNotNull(receipt) {
            "Completed inventory checkpoint is missing its receipt"
        }
        require(indexEntry(checkpoint).directoryMatches(receipt.after)) { "Completed inventory checkpoint conflicts with city" }
        completedReceipts[receipt.id] = receipt.cityId
        return receipt.after
    }

    private fun inventoryHeadStore(root: Path, id: UUID) = AtomicFileStore(
        root, Path.of("inventory-completed", "$id.json"), 1_024L,
        { value: InventoryCompletionHead -> gson.toJson(value).toByteArray(StandardCharsets.UTF_8) },
        { decodeJson(it, InventoryCompletionHead::class.java) },
        { head -> requireNotNull(head.transactionId)
            require(head.schemaVersion == SCHEMA_VERSION && head.cityId == id && head.afterRevision > 0) {
            "Invalid inventory completion checkpoint"
        } },
    )

    private fun cityStore(trustedRoot: Path, id: UUID) = AtomicFileStore(
        root = trustedRoot,
        relativePath = Path.of("cities", "$id.json"),
        maxBytes = MAX_CITY_DOCUMENT_BYTES,
        encode = { gson.toJson(it).toByteArray(StandardCharsets.UTF_8) },
        decode = { decodeJson(it, CityDocument::class.java) },
        validate = { record ->
            require(record.schemaVersion == SCHEMA_VERSION) { "Unsupported city schema version: ${record.schemaVersion}" }
            val city = requireNotNull(record.city) { "City record is missing its city" }
            require(city.id == id) { "City id does not match its filename" }
            CivsRules.validate(city)
        },
    )

    private fun deletedCityStore(trustedRoot: Path, id: UUID) = AtomicFileStore(
        root = trustedRoot,
        relativePath = Path.of("deleted-cities", "$id.json"),
        maxBytes = MAX_DELETED_CITY_BYTES,
        encode = { gson.toJson(it).toByteArray(StandardCharsets.UTF_8) },
        decode = { decodeJson(it, DeletedCityDocument::class.java) },
        validate = { record ->
            require(record.schemaVersion == SCHEMA_VERSION) { "Unsupported city deletion schema" }
            val deleted = requireNotNull(record.deleted) { "City deletion record is missing" }.validated()
            require(deleted.cityId == id) { "Deleted city id does not match its filename" }
        },
    )

    private fun planStore(trustedRoot: Path, id: UUID) = AtomicFileStore(
        root = trustedRoot,
        relativePath = Path.of("plans", "$id.json"),
        maxBytes = MAX_PLAN_BYTES,
        encode = { gson.toJson(it).toByteArray(StandardCharsets.UTF_8) },
        decode = { decodeJson(it, PlanDocument::class.java) },
        validate = { record ->
            require(record.schemaVersion == SCHEMA_VERSION) { "Unsupported plan schema version: ${record.schemaVersion}" }
            val plan = requireNotNull(record.plan) { "Plan record is missing its plan" }
            require(plan.id == id) { "Plan id does not match its filename" }
            validatePlan(plan)
        },
    )

    private fun schematicStore(trustedRoot: Path, id: UUID) = AtomicFileStore(
        root = trustedRoot,
        relativePath = Path.of("captured", "$id.schem"),
        maxBytes = MAX_SCHEMATIC_BYTES.toLong(),
        encode = { it },
        decode = { it },
        validate = { require(it.isNotEmpty()) { "Captured schematic must not be empty" } },
    )

    private fun inventoryTransactionStore(trustedRoot: Path, id: UUID) = AtomicFileStore(
        root = trustedRoot,
        relativePath = Path.of("inventory-transactions", "$id.json"),
        maxBytes = MAX_INVENTORY_TRANSACTION_BYTES,
        encode = { record: InventoryTransactionDocument -> gson.toJson(record).toByteArray(StandardCharsets.UTF_8) },
        decode = { decodeJson(it, InventoryTransactionDocument::class.java) },
        validate = { record ->
            require(record.schemaVersion == SCHEMA_VERSION) { "Unsupported inventory transaction schema" }
            val transaction = requireNotNull(record.transaction) { "Inventory transaction is missing" }
            require(transaction.id == id) { "Inventory transaction id does not match its filename" }
            transaction.validated()
        },
    )

    /** Freeze the validated readback once; sharing it must not let a caller alter later loads. */
    private fun cachePlan(plan: CivPlacementPlan): CivPlacementPlan = plan.copy(
        building = plan.building.copy(transit = plan.building.transit?.let { java.util.List.copyOf(it) }),
        blocks = java.util.List.copyOf(plan.blocks),
        terrain = plan.terrain?.let { it.copy(blocks = java.util.List.copyOf(it.blocks),
            route = java.util.List.copyOf(it.route), waterFoundations = it.waterFoundations?.let { java.util.List.copyOf(it) },
            requiredGrass = it.requiredGrass?.let { java.util.List.copyOf(it) },
            paidGateFloors = it.paidGateFloors?.let { java.util.List.copyOf(it) },
            paidWaterFloors = it.paidWaterFloors?.let { java.util.List.copyOf(it) }) },
    ).also { planCache[it.id] = it }

    private fun snapshotBuilding(building: CivBuilding) = building.copy(transit = building.transit?.toList())

    private fun snapshotConstruction(order: CivConstruction) = order.copy(
        plannedBuilding = order.plannedBuilding?.let(::snapshotBuilding), materials = order.materials?.toMap(),
        payment = order.payment?.let { it.copy(awaiting = it.awaiting?.toMap()) })

    private fun snapshotWorker(worker: CivWorker) = worker.copy(skills = worker.skills?.toMap(),
        inventory = worker.inventory?.toMap(), items = worker.items?.toList(), parentIds = worker.parentIds?.toSet(),
        life = worker.life?.let { it.copy(education = it.education?.toMap()) })

    private fun snapshotCity(city: Settlement): Settlement = city.copy(
        buildings = city.buildings.map(::snapshotBuilding), workers = city.workers.map(::snapshotWorker), members = city.members.toSet(),
        memberNames = city.memberNames?.toMap(), families = city.families?.toList(),
        corpses = city.corpses?.map { it.copy(worker = snapshotWorker(it.worker)) },
        inventory = city.inventory?.let { it.copy(materials = it.materials.toMap(), containers = it.containers.toList(),
            constructionBuffers = it.constructionBuffers?.mapValues { (_, group) -> group.detached() },
            buildingMaterials = it.buildingMaterials?.mapValues { entry -> entry.value.toMap() }) },
        construction = city.construction?.let(::snapshotConstruction),
        deferredConstruction = city.deferredConstruction?.map(::snapshotConstruction),
        pendingControls = city.pendingControls?.map { it.copy(approach = it.approach?.toList(), retire = it.retire?.copy()) },
        constructionQueue = city.constructionQueue?.toList(),
        mineProgress = city.mineProgress?.toMap(),
        minePlans = city.minePlans?.mapValues { (_, state) -> CivsMineProgress.detached(state) },
        research = CivsResearch.detached(city.research),
        roadRouteOverrides = city.roadRouteOverrides?.mapValues { (_, route) ->
            route.detached()
        },
        autonomy = city.autonomy?.let { it.copy(products = it.products?.toMap(), plan = CivsSettlementPlans.detached(it.plan)) },
    )

    private fun validatePlan(plan: CivPlacementPlan) {
        civsValidateWaterFoundations(plan)
        require(plan.blocks.size in 1..MAX_PLAN_BLOCKS) { "Placement plan block count is invalid" }
        val building = requireNotNull(plan.building) { "Placement plan is missing its building" }
        requireNotNull(plan.id)
        requireNotNull(plan.cityId)
        requireNotNull(plan.worldId)
        requireNotNull(building.id)
        requireNotNull(building.type)
        requireNotNull(building.phase)
        require(validSha256(plan.capturedSha256)) { "Placement capture SHA-256 is invalid" }
        require(building.level in 1..building.type.maxLevel) { "Placement building level is invalid" }
        require(building.rotation in ROTATIONS) { "Placement building rotation is invalid" }
        require(CivsWarehouseRefit.validReference(building) &&
            (building.warehouseCaptureId == null || building.warehouseCaptureId == plan.id)) { "Invalid warehouse capture reference" }
        require(validBoundedText(building.schematicId, MAX_SCHEMATIC_ID_CHARS)) { "Placement schematic id is invalid" }

        val bounds = requireNotNull(building.bounds) { "Placement building has no bounds" }
        val min = requireNotNull(bounds.min)
        val max = requireNotNull(bounds.max)
        require(min.x <= max.x && min.y <= max.y && min.z <= max.z) { "Placement bounds are inverted" }
        requireSafePosition(min)
        requireSafePosition(max)
        requireSafePosition(building.anchor)
        requireSafePosition(building.entrance)

        val paidGateCells = civsValidatePaidGateFloors(plan)
        val grass = plan.terrain?.requiredGrass.orEmpty().toSet()
        require(grass.size == plan.terrain?.requiredGrass.orEmpty().size)
        require(grass.isEmpty() || building.type == BuildingType.WALL && !plan.terrain?.route.isNullOrEmpty())
        val coordinates = HashSet<CivPosition>(plan.blocks.size)
        plan.blocks.forEach { block ->
            val position = requireNotNull(requireNotNull(block).position)
            requireSafePosition(position)
            require(bounds.contains(position.x, position.y, position.z)) { "Plan block lies outside building bounds" }
            require(coordinates.add(position)) { "Placement plan contains duplicate coordinates" }
            require(validBoundedText(block.before, MAX_BLOCK_STATE_CHARS)) { "Plan before-state is invalid" }
            require(validBoundedText(block.after, MAX_BLOCK_STATE_CHARS)) { "Plan after-state is invalid" }
            if (position in grass) require(block.before.substringBefore('[') == "minecraft:grass_block")
        }
        require(coordinates.containsAll(grass))
        plan.terrain?.let { terrain ->
            require(terrain.blocks.size <= MAX_PLAN_BLOCKS && terrain.blocks.size + plan.blocks.size <= MAX_PLAN_BLOCKS)
            require(terrain.route.size <= 4096)
            require(terrain.cutCount >= 0 && terrain.fillCount >= 0 &&
                terrain.cutCount.toLong() + terrain.fillCount <= terrain.blocks.size)
            requireSafePosition(terrain.bounds.min); requireSafePosition(terrain.bounds.max)
            require(terrain.bounds.min.x <= terrain.bounds.max.x && terrain.bounds.min.y <= terrain.bounds.max.y &&
                terrain.bounds.min.z <= terrain.bounds.max.z)
            require(paidGateCells.all { terrain.bounds.contains(it.x, it.y, it.z) })
            require(terrain.groundY in -4095..4095)
            val terrainCoordinates = hashSetOf<CivPosition>()
            terrain.blocks.forEach { cell ->
                requireSafePosition(cell.position)
                require(terrain.bounds.contains(cell.position.x, cell.position.y, cell.position.z))
                CivsRules.check(cell.position !in paidGateCells, "invalid-plan")
                // This only validates capture shape; native admission still requires the exact linked SITE/order.
                val hallMarkerCut = cell.position == building.control && building.type == BuildingType.TOWN_HALL && building.level == 1 &&
                    building.phase == BuildingPhase.CONSTRUCTING && plan.capturedSha256 != null && !plan.adminPlacement &&
                    terrain.route.isEmpty() && cell.after == "minecraft:air" && cell.position !in coordinates
                require((cell.position != building.control || hallMarkerCut) && terrainCoordinates.add(cell.position))
                require(validBoundedText(cell.before, MAX_BLOCK_STATE_CHARS) && validBoundedText(cell.after, MAX_BLOCK_STATE_CHARS))
                if (terrain.route.isEmpty()) {
                    val p = cell.position
                    val footprint = p.x in bounds.min.x..bounds.max.x && p.z in bounds.min.z..bounds.max.z
                    val entrance = p.x == building.entrance.x && p.z == building.entrance.z
                    require(footprint || entrance) { "Manual foundation lies outside its footprint" }
                    val ground = if (entrance && !footprint) building.entrance.y - 1 else building.anchor.y
                    require(if (cell.after == "minecraft:air") p.y > ground else cell.after == "minecraft:dirt" && p.y <= ground)
                }
            }
            terrain.route.forEach(::requireSafePosition)
            terrain.route.zipWithNext().forEach { (a, b) ->
                require(kotlin.math.abs(a.x.toLong() - b.x) + kotlin.math.abs(a.z.toLong() - b.z) == 1L &&
                    kotlin.math.abs(a.y.toLong() - b.y) <= 1) { "Terraform route is discontinuous" }
            }
        }
    }

    private fun requireSafePosition(position: CivPosition) {
        require(kotlin.math.abs(position.x.toLong()) <= MAX_HORIZONTAL_COORD &&
            kotlin.math.abs(position.z.toLong()) <= MAX_HORIZONTAL_COORD &&
            position.y.toLong() in -MAX_VERTICAL_COORD..MAX_VERTICAL_COORD) {
            "Placement coordinate is outside the supported world range"
        }
    }

    private fun validBoundedText(text: String, max: Int): Boolean =
        text.isNotBlank() && text == text.trim() && text.length <= max && text.none { it.isISOControl() }

    private fun validSha256(value: String?): Boolean = value == null ||
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun trustedRoot(): Path {
        Files.createDirectories(root)
        return root.toRealPath().also { resolvedRoot = it }
    }

    /** The startup-resolved path is enough for accepted RAM operations; actual writes revalidate the filesystem. */
    private fun ramRoot(): Path = resolvedRoot ?: trustedRoot()

    private fun <T> decodeJson(bytes: ByteArray, type: Class<T>): T {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val record = if (bytes.size >= MIN_STREAMED_JSON_BYTES) {
            InputStreamReader(ByteArrayInputStream(bytes), decoder).use { gson.fromJson(it, type) }
        } else {
            gson.fromJson(decoder.decode(ByteBuffer.wrap(bytes)).toString(), type)
        }
        return requireNotNull(record) { "JSON record is empty" }
    }

    private fun <T> submitPlanChange(id: UUID? = null, action: () -> T): CompletableFuture<T> {
        // Fence both queued readers and projections completed while an uncertain write was in flight.
        markPlanChanged(id)
        return submit {
            try { action() } finally { markPlanChanged(id) }
        }
    }

    private fun markPlanChanged(id: UUID?) {
        planChanges.updateAndGet { before ->
            val sequence = before.sequence + 1
            // Retain only bounded ID metadata. Reload or overflow conservatively invalidates every projection.
            if (id == null || id !in before.byId && before.byId.size >= 64) PlanChanges(sequence, sequence)
            else PlanChanges(sequence, before.all, before.byId + (id to sequence))
        }
    }

    private fun <T> submit(action: () -> T): CompletableFuture<T> = synchronized(lifecycleLock) {
        if (closed) return@synchronized CompletableFuture.failedFuture(IllegalStateException("CivsStore is closed"))
        enqueue(action)
    }

    // Already-admitted SQL chains finish even after the supplied storage executor has sealed admissions.
    // These continuations run only from a storage/SQL worker and never touch Bukkit state.
    private fun <T> fileContinuation(action: () -> T): CompletableFuture<T> = try {
        CompletableFuture.completedFuture(synchronized(ioLock) { action() })
    } catch (error: Exception) {
        CompletableFuture.failedFuture(error)
    }

    private fun <T> enqueue(action: () -> T): CompletableFuture<T> = try {
        CompletableFuture.supplyAsync({ synchronized(ioLock) { action() } }, executor)
    } catch (error: RuntimeException) {
        CompletableFuture.failedFuture(error)
    }

    private data class CityDocument(val schemaVersion: Int, val city: Settlement?)
    private data class DeletedCityDocument(val schemaVersion: Int, val deleted: CivsDeletedCity?)
    private data class PlanDocument(val schemaVersion: Int, val plan: CivPlacementPlan?)
    private data class InventoryTransactionDocument(val schemaVersion: Int, val transaction: CivInventoryTransaction?)
    private data class InventoryCompletionHead(val schemaVersion: Int, val cityId: UUID, val transactionId: UUID, val afterRevision: Long)
    private data class CityIndex(val owner: UUID, val name: String, val members: Set<UUID>, val revision: Long,
        val autonomous: Boolean, val buffers: Map<String, CivConstructionStorageBuffer>?, val mode: CivStorageMode?) {
        fun directoryMatches(city: Settlement) = owner == city.owner && name == city.name && members == city.members && autonomous == (city.autonomy != null)
    }

    internal companion object {
        const val CHECKPOINT_INTERVAL_MILLIS = 300_000L
        const val SCHEMA_VERSION = 1
        const val MAX_CITY_BYTES = 256L * 1024L
        // Schema 1's {"schemaVersion":1,"city":<payload>} adds exactly 27 ASCII bytes.
        const val MAX_CITY_DOCUMENT_BYTES = MAX_CITY_BYTES + 27
        const val MAX_PLAN_BYTES = 32L * 1024L * 1024L
        // The stream's fixed input buffer costs more than decoding the smallest documents directly.
        private const val MIN_STREAMED_JSON_BYTES = 4 * 1024
        const val MAX_SCHEMATIC_BYTES = 16 * 1024 * 1024
        const val MAX_INVENTORY_TRANSACTION_BYTES = 32L * 1024L * 1024L
        const val MAX_DELETED_CITY_BYTES = 1_024L
        const val MAX_DELETED_CITIES = 100_000
        internal const val MAX_PLAN_BLOCKS = 150_000
        const val MAX_SCHEMATIC_ID_CHARS = 512
        internal const val MAX_BLOCK_STATE_CHARS = 2_048
        internal const val MAX_HORIZONTAL_COORD = 30_000_000L
        internal const val MAX_VERTICAL_COORD = 4_096L
        val ROTATIONS = setOf(0, 90, 180, 270)
    }
}
