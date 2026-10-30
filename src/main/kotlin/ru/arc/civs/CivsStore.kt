package ru.arc.civs

import com.google.gson.Gson
import ru.arc.persistence.AtomicFileStore
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

/** Durable, bounded file state. Explicit load rescans external edits; filesystem work runs on the supplied executor. */
class CivsStore(
    root: Path,
    private val executor: Executor,
    private val maximumCities: Int = 500,
    private val network: CivsNetworkStore? = null,
) : AutoCloseable {
    private val root = root.toAbsolutePath().normalize()
    private val gson = Gson()
    private val lifecycleLock = Any()
    // ponytail: one bounded global lock serializes store paths; use keyed locks only if disk contention is measured.
    private val ioLock = Any()
    private var cityIndex: Map<UUID, CityIndex>? = null
    private var deletedCityIndex: Map<UUID, CivsDeletedCity>? = null
    private val consumedItems = CivsConsumedItems()
    private var closed = false

    init {
        require(maximumCities > 0) { "maximumCities must be positive" }
    }

    fun load(): CompletableFuture<List<Settlement>> = submit {
        reloadCityIndex(trustedRoot()) to requireNotNull(deletedCityIndex).values.toList()
    }.thenCompose { (legacy, deleted) -> network?.loadOwned(legacy, deleted) ?: CompletableFuture.completedFuture(legacy) }

    fun lookup(playerId: UUID): CompletableFuture<CivsNetworkCity?> = synchronized(lifecycleLock) {
        if (closed) CompletableFuture.failedFuture(IllegalStateException("CivsStore is closed"))
        else network?.lookup(playerId) ?: CompletableFuture.completedFuture(null)
    }

    fun homeServer() = network?.server

    fun save(city: Settlement): CompletableFuture<Settlement> {
        val proposed = snapshotCity(city)
        if (network != null) return synchronized(lifecycleLock) {
            if (closed) CompletableFuture.failedFuture(IllegalStateException("CivsStore is closed")) else network.save(proposed)
        }
        return submit {
            CivsRules.validate(proposed)
            val trustedRoot = trustedRoot()
            val index = ensureCityIndex(trustedRoot)
            val deleted = requireNotNull(deletedCityIndex)
            if (proposed.id in deleted) throw CivsRejection("city-deleted")
            val snapshot = if (proposed.autonomy == null && proposed.revision == 0L &&
                deleted.values.any { it.owner == proposed.owner && !it.autonomous })
                proposed.copy(stock = Stock(), inventory = proposed.inventory?.copy(materials = emptyMap())) else proposed
            val sameCity = index[snapshot.id]
            require(sameCity == null || sameCity.owner == snapshot.owner) { "City id cannot be reassigned to another owner" }
            require(sameCity == null || sameCity.autonomous == (snapshot.autonomy != null)) { "City autonomy cannot change" }
            val otherParticipants = HashSet<UUID>()
            index.filterKeys { it != snapshot.id }.values.filterNot { it.autonomous }.forEach { existing ->
                otherParticipants.add(existing.owner)
                otherParticipants.addAll(existing.members)
            }
            require(CivsMembership.participants(snapshot).none(otherParticipants::contains)) {
                "Player already belongs to another settlement"
            }
            require(sameCity != null || index.size < maximumCities) { "Settlement capacity is full" }

            if (sameCity == null) {
                require(snapshot.revision == 0L) { "New settlement revision must start at zero" }
            } else if (snapshot.revision == sameCity.revision) {
                val persisted = try {
                    requireNotNull(cityStore(trustedRoot, snapshot.id).loadOrNull()) {
                        "Saved city disappeared during retry"
                    }
                } catch (error: Exception) {
                    cityIndex = null
                    throw error
                }
                if (persisted.city == proposed) return@submit proposed
                require(persisted.city == snapshot) { "Same-revision city retry does not match saved state" }
                return@submit snapshot
            } else {
                require(sameCity.revision < Long.MAX_VALUE && snapshot.revision == sameCity.revision + 1) {
                    "Settlement revision is stale or skips a committed revision"
                }
            }

            val saved = try {
                requireNotNull(cityStore(trustedRoot, snapshot.id).write(CityDocument(SCHEMA_VERSION, snapshot)).city)
            } catch (error: Exception) {
                cityIndex = null
                throw error
            }
            cityIndex = index + (saved.id to indexEntry(saved))
            saved
        }
    }

    /** The canonical commit precedes local cleanup; a tombstone is durable before its old JSON disappears. */
    fun delete(city: Settlement): CompletableFuture<Unit> {
        val snapshot = snapshotCity(city)
        if (network != null) return synchronized(lifecycleLock) {
            if (closed) CompletableFuture.failedFuture(IllegalStateException("CivsStore is closed"))
            else network.delete(snapshot).thenCompose { enqueue { deleteLocal(snapshot, network.server.value) } }
        }
        return submit { deleteLocal(snapshot, null) }
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
                if (home == null) {
                    if (saved == null) throw CivsRejection("stale-city")
                    if (saved.owner != city.owner) throw CivsRejection("owner-required")
                    if (saved != city) throw CivsRejection("stale-city")
                } else if (saved != null && saved.owner != city.owner) throw CivsRejection("owner-required")
                require(deleted.size < MAX_DELETED_CITIES) { "City deletion history exceeds its limit" }
                deletedCityStore(trustedRoot, city.id).write(DeletedCityDocument(SCHEMA_VERSION,
                    CivsDeletedCity(city.id, city.owner, home, city.revision, System.currentTimeMillis(), city.autonomy != null)))
            }
            cityStore(trustedRoot, city.id).deleteIfExists()
        } finally {
            // A failed atomic write can have an unknown outcome: rescan its durable fence before the next action.
            cityIndex = null
            deletedCityIndex = null
        }
    }

    fun savePlan(plan: CivPlacementPlan): CompletableFuture<CivPlacementPlan> {
        val snapshot = plan.copy(building = snapshotBuilding(plan.building), blocks = plan.blocks.toList(), terrain = plan.terrain?.let {
            it.copy(blocks = it.blocks.toList(), route = it.route.toList(), waterFoundations = it.waterFoundations?.toList())
        })
        return submit {
            validatePlan(snapshot)
            val trustedRoot = trustedRoot()
            requireNotNull(planStore(trustedRoot, snapshot.id).write(PlanDocument(SCHEMA_VERSION, snapshot)).plan)
        }
    }

    fun loadPlan(id: UUID): CompletableFuture<CivPlacementPlan?> = submit {
        val trustedRoot = trustedRoot()
        planStore(trustedRoot, id).loadOrNull()?.plan
    }

    fun deletePlan(id: UUID): CompletableFuture<Boolean> = submit {
        val trustedRoot = trustedRoot()
        val store = planStore(trustedRoot, id)
        val existing = store.loadOrNull() ?: return@submit false
        requireNotNull(existing.plan)
        store.deleteIfExists()
    }

    /** Immutable write-ahead item receipt; the same UUID can only retry identical bytes/state. */
    fun saveInventoryTransaction(transaction: CivInventoryTransaction): CompletableFuture<CivInventoryTransaction> {
        val snapshot = transaction.copy(after = snapshotCity(transaction.after), changes = transaction.changes.toList(),
            playerDebit = transaction.playerDebit?.let { it.copy(changes = it.changes.toList()) },
            residentTransfer = transaction.residentTransfer?.let { it.copy(before = it.before.toList(), changes = it.changes.toList()) },
            worldChanges = transaction.worldChanges?.toList(),
            mine = transaction.mine?.detached(),
            upkeep = transaction.upkeep?.detached(),
            cargoDropoff = transaction.cargoDropoff?.detached(),
            cargoPickup = transaction.cargoPickup?.detached(),
            autonomousVisual = transaction.autonomousVisual?.let { it.copy(
                buildingBefore = snapshotBuilding(it.buildingBefore),
                mineProgressBefore = it.mineProgressBefore?.toMap(),
                minePlansBefore = it.minePlansBefore?.mapValues { (_, state) -> CivsMineProgress.detached(state) }) },
            collected = transaction.collected?.let { it.copy(before = it.before.toList(), ground = it.ground.toList(), containers = it.containers.toList(),
                forestryLeafLoot = it.forestryLeafLoot?.copy(sources = it.forestryLeafLoot.sources.toList())) },
            containerRelocation = transaction.containerRelocation?.let { it.copy(building = snapshotBuilding(it.building),
                containers = it.containers.map { holder -> holder.copy(before = holder.before.toList(), after = holder.after.toList()) }) },
            resurrection = transaction.resurrection?.let { it.copy(corpse = it.corpse.copy(worker = snapshotWorker(it.corpse.worker))) })
        return submit {
            snapshot.validated()
            val trustedRoot = trustedRoot()
            val store = inventoryTransactionStore(trustedRoot, snapshot.id)
            val existing = store.loadOrNull()?.transaction
            if (existing != null) {
                require(existing == snapshot) { "Inventory transaction retry differs from its journal" }
                // A previous write may have reached rename but failed its parent-directory acknowledgement.
                forceInventoryDirectories(trustedRoot)
                return@submit existing
            }
            val pending = loadInventoryTransactions(trustedRoot)
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
            val document = InventoryTransactionDocument(SCHEMA_VERSION, snapshot)
            if (gson.toJson(CityDocument(SCHEMA_VERSION, snapshot.after)).toByteArray(StandardCharsets.UTF_8).size > MAX_CITY_BYTES ||
                gson.toJson(document).toByteArray(StandardCharsets.UTF_8).size > MAX_INVENTORY_TRANSACTION_BYTES)
                throw CivsRejection("storage-transaction-too-large")
            val written = requireNotNull(store.write(document).transaction)
            // AtomicFileStore forces inventory-transactions itself. Also persist its newly-created entry in
            // settlements before native debit; otherwise a crash could lose the entire acknowledged journal.
            forceInventoryDirectories(trustedRoot)
            written
        }
    }

    fun loadInventoryTransactions(): CompletableFuture<List<CivInventoryTransaction>> = submit {
        val trustedRoot = trustedRoot()
        val transactions = loadInventoryTransactions(trustedRoot)
        // Recovery may resume a prior uncertain write immediately after this ACK, without saving it again.
        if (transactions.isNotEmpty()) forceInventoryDirectories(trustedRoot)
        transactions
    }

    internal fun loadConsumedItems(): CompletableFuture<CivConsumedItemIndex> = submit { consumedItems.load(trustedRoot()) }

    internal fun markConsumedItems(transaction: CivInventoryTransaction): CompletableFuture<CivConsumedItemIndex> = submit {
        val root = trustedRoot()
        require(inventoryTransactionStore(root, transaction.id).loadOrNull()?.transaction == transaction) {
            "Consumed item marker requires its exact durable inventory receipt"
        }
        consumedItems.mark(root, transaction.after.worldId, transaction.collected?.ground.orEmpty().map(CivGroundItemChange::entityId))
    }

    fun deleteInventoryTransaction(id: UUID): CompletableFuture<Boolean> = submit {
        val trustedRoot = trustedRoot()
        val store = inventoryTransactionStore(trustedRoot, id)
        val deleted = if (store.loadOrNull() == null) false else store.deleteIfExists()
        // A retry after an uncertain delete must acknowledge the directory even when the file is now absent.
        forceInventoryDirectories(trustedRoot)
        deleted
    }

    private fun forceInventoryDirectories(trustedRoot: Path) {
        val directory = trustedRoot.resolve("inventory-transactions")
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
        require(Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "Inventory journal directory is not a real directory" }
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
        synchronized(lifecycleLock) { closed = true }
    }

    private fun reloadCityIndex(trustedRoot: Path): List<Settlement> {
        cityIndex = null
        deletedCityIndex = null
        val deleted = loadDeletedCities(trustedRoot)
        deletedCityIndex = deleted
        val cities = loadCities(trustedRoot, deleted.keys)
        cityIndex = cities.associate { it.id to indexEntry(it) }
        return cities
    }

    private fun ensureCityIndex(trustedRoot: Path): Map<UUID, CityIndex> =
        cityIndex ?: run {
            reloadCityIndex(trustedRoot)
            requireNotNull(cityIndex)
        }

    private fun indexEntry(city: Settlement) = CityIndex(city.owner, city.members.toSet(), city.revision, city.autonomy != null)

    private fun loadCities(trustedRoot: Path, deleted: Set<UUID>): List<Settlement> {
        val directory = trustedRoot.resolve("cities")
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return emptyList()
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
        return files.sortedBy { it.fileName.toString() }.map { file ->
            val id = idFromJsonFilename(file)
            val record = requireNotNull(cityStore(trustedRoot, id).loadOrNull()) {
                "City record disappeared during load"
            }
            val city = requireNotNull(record.city)
            require(city.id == id) { "City id does not match its filename" }
            require(ids.add(city.id)) { "Duplicate city id" }
            require(CivsMembership.participants(city).all(participants::add)) {
                "Player belongs to multiple settlements"
            }
            city
        }
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
        val directory = trustedRoot.resolve("inventory-transactions")
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        require(!Files.isSymbolicLink(directory) && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            "Inventory transaction directory is invalid"
        }
        val ids = mutableListOf<UUID>()
        Files.newDirectoryStream(directory).use { files ->
            files.forEach { file ->
                if (file.fileName.toString().endsWith(".json")) {
                    require(ids.size < maximumCities) { "Inventory transaction directory exceeds its limit" }
                    ids += idFromJsonFilename(file)
                }
            }
        }
        val records = ids.sortedBy(UUID::toString).map { id ->
            requireNotNull(inventoryTransactionStore(trustedRoot, id).loadOrNull()?.transaction) {
                "Inventory transaction disappeared during load"
            }
        }
        require(records.map { it.cityId }.distinct().size == records.size) { "City has multiple pending inventory transactions" }
        val players = records.mapNotNull(CivInventoryTransaction::playerId)
        require(players.distinct().size == players.size) { "Player has multiple pending inventory transactions" }
        val ground = records.flatMap { it.collected?.ground.orEmpty() }.map(CivGroundItemChange::entityId)
        require(ground.distinct().size == ground.size) { "Ground item has multiple pending inventory transactions" }
        return records
    }

    private fun cityStore(trustedRoot: Path, id: UUID) = AtomicFileStore(
        root = trustedRoot,
        relativePath = Path.of("cities", "$id.json"),
        maxBytes = MAX_CITY_BYTES,
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
        encode = { gson.toJson(it).toByteArray(StandardCharsets.UTF_8) },
        decode = { decodeJson(it, InventoryTransactionDocument::class.java) },
        validate = { record ->
            require(record.schemaVersion == SCHEMA_VERSION) { "Unsupported inventory transaction schema" }
            val transaction = requireNotNull(record.transaction) { "Inventory transaction is missing" }
            require(transaction.id == id) { "Inventory transaction id does not match its filename" }
            transaction.validated()
        },
    )

    private fun snapshotBuilding(building: CivBuilding) = building.copy(transit = building.transit?.toList())

    private fun snapshotWorker(worker: CivWorker) = worker.copy(skills = worker.skills?.toMap(),
        inventory = worker.inventory?.toMap(), items = worker.items?.toList(), parentIds = worker.parentIds?.toSet(),
        life = worker.life?.let { it.copy(education = it.education?.toMap()) })

    private fun snapshotCity(city: Settlement): Settlement = city.copy(
        buildings = city.buildings.map(::snapshotBuilding), workers = city.workers.map(::snapshotWorker), members = city.members.toSet(),
        memberNames = city.memberNames?.toMap(), families = city.families?.toList(),
        corpses = city.corpses?.map { it.copy(worker = snapshotWorker(it.worker)) },
        inventory = city.inventory?.let { it.copy(materials = it.materials.toMap(), containers = it.containers.toList(),
            buildingMaterials = it.buildingMaterials?.mapValues { entry -> entry.value.toMap() }) },
        construction = city.construction?.let { it.copy(plannedBuilding = it.plannedBuilding?.let(::snapshotBuilding), materials = it.materials?.toMap(),
            payment = it.payment?.let { payment -> payment.copy(awaiting = payment.awaiting?.toMap()) }) },
        pendingControls = city.pendingControls?.map { it.copy(approach = it.approach?.toList()) },
        constructionQueue = city.constructionQueue?.toList(),
        mineProgress = city.mineProgress?.toMap(),
        minePlans = city.minePlans?.mapValues { (_, state) -> CivsMineProgress.detached(state) },
        research = CivsResearch.detached(city.research),
        roadRouteOverrides = city.roadRouteOverrides?.mapValues { (_, route) ->
            route.copy(originalRoute = route.originalRoute.toList(), route = route.route.toList())
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
        require(validBoundedText(building.schematicId, MAX_SCHEMATIC_ID_CHARS)) { "Placement schematic id is invalid" }

        val bounds = requireNotNull(building.bounds) { "Placement building has no bounds" }
        val min = requireNotNull(bounds.min)
        val max = requireNotNull(bounds.max)
        require(min.x <= max.x && min.y <= max.y && min.z <= max.z) { "Placement bounds are inverted" }
        requireSafePosition(min)
        requireSafePosition(max)
        requireSafePosition(building.anchor)
        requireSafePosition(building.entrance)

        val coordinates = HashSet<CivPosition>(plan.blocks.size)
        plan.blocks.forEach { block ->
            val position = requireNotNull(requireNotNull(block).position)
            requireSafePosition(position)
            require(bounds.contains(position.x, position.y, position.z)) { "Plan block lies outside building bounds" }
            require(coordinates.add(position)) { "Placement plan contains duplicate coordinates" }
            require(validBoundedText(block.before, MAX_BLOCK_STATE_CHARS)) { "Plan before-state is invalid" }
            require(validBoundedText(block.after, MAX_BLOCK_STATE_CHARS)) { "Plan after-state is invalid" }
        }
        plan.terrain?.let { terrain ->
            require(terrain.blocks.size <= MAX_PLAN_BLOCKS && terrain.blocks.size + plan.blocks.size <= MAX_PLAN_BLOCKS)
            require(terrain.route.size <= 4096)
            require(terrain.cutCount >= 0 && terrain.fillCount >= 0 &&
                terrain.cutCount.toLong() + terrain.fillCount <= terrain.blocks.size)
            requireSafePosition(terrain.bounds.min); requireSafePosition(terrain.bounds.max)
            require(terrain.bounds.min.x <= terrain.bounds.max.x && terrain.bounds.min.y <= terrain.bounds.max.y &&
                terrain.bounds.min.z <= terrain.bounds.max.z)
            require(terrain.groundY in -4095..4095)
            val terrainCoordinates = hashSetOf<CivPosition>()
            terrain.blocks.forEach { cell ->
                requireSafePosition(cell.position)
                require(terrain.bounds.contains(cell.position.x, cell.position.y, cell.position.z))
                require(cell.position != building.control && terrainCoordinates.add(cell.position))
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
        return root.toRealPath()
    }

    private fun <T> decodeJson(bytes: ByteArray, type: Class<T>): T {
        val text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        return requireNotNull(gson.fromJson(text, type)) { "JSON record is empty" }
    }

    private fun <T> submit(action: () -> T): CompletableFuture<T> = synchronized(lifecycleLock) {
        if (closed) return@synchronized CompletableFuture.failedFuture(IllegalStateException("CivsStore is closed"))
        enqueue(action)
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
    private data class CityIndex(val owner: UUID, val members: Set<UUID>, val revision: Long, val autonomous: Boolean)

    internal companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_CITY_BYTES = 256L * 1024L
        const val MAX_PLAN_BYTES = 32L * 1024L * 1024L
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
