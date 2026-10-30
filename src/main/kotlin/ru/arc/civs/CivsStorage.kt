package ru.arc.civs

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.TileState
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Barrel as BarrelData
import org.bukkit.entity.Player
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.entity.ItemDespawnEvent
import org.bukkit.event.entity.EntityTeleportEvent
import org.bukkit.event.entity.EntityPortalEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerItemDamageEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.inventory.Inventory
import org.bukkit.plugin.Plugin
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.playerstate.NativePaperPlayerDataPersistence
import ru.arc.paper.playerstate.PaperPlayerDataPersistence
import ru.arc.paper.chunk.PaperChunkKey
import ru.arc.paper.chunk.PaperChunkTicketLease
import ru.arc.civs.CivStorageKey as Key
import ru.arc.civs.CivStorageSource as Source
import ru.arc.civs.CivsStorageItems.isEmpty
import ru.arc.civs.CivsStorageItems.itemDescription
import ru.arc.civs.CivsStorageItems.matches
import ru.arc.civs.CivsStorageItems.matchesNative
import ru.arc.civs.CivsStorageItems.stack
import ru.arc.civs.CivsStorageItems.validateAmounts
import ru.arc.civs.storageHolderPositions as holderPositions
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/** Bounded main-thread storage. No IO, chunk loads, item drops or native mutation during planning. */
class CivsStorage(private val plugin: Plugin,
    private val playerDataPersistence: PaperPlayerDataPersistence = NativePaperPlayerDataPersistence,
    tileEntities: (Chunk) -> Array<BlockState> = { it.tileEntities }) : Listener, AutoCloseable {
    private data class Claim(val city: Settlement, val positions: Map<CivPosition, String?>, val playerId: UUID?,
        val worldPositions: Set<CivPosition> = emptySet(), val groundItems: Set<UUID> = emptySet(),
        val viewersAllowed: Boolean = false)
    private val claims = mutableMapOf<UUID, Claim>()
    private val cityClaims = mutableMapOf<UUID, UUID>()
    private val locked = mutableMapOf<Key, UUID>()
    private val lockedPlayers = mutableMapOf<UUID, UUID>()
    private val lockedWorld = mutableMapOf<Key, UUID>()
    private val lockedGround = mutableMapOf<UUID, UUID>()
    private val retiringByCity = mutableMapOf<UUID, Set<Key>>()
    private val retiringControls = hashSetOf<Key>()
    internal var residentFoods: CivsResidentFoods = CivsResidentFoods.BREAD_ONLY
    internal var foodCanModify: (Settlement, CivPosition) -> Boolean = { _, _ -> false }
    internal var forestryLeafCurrentCity: (UUID) -> Settlement? = { null }
    internal var relocationCurrentCity: (UUID) -> Settlement? = { null }
    internal var collectedChunkLease: (PaperChunkKey) -> PaperChunkTicketLease = { error("Collection chunk leases are not wired") }
    internal var validateUpkeepNative: (CivInventoryTransaction) -> CivInventoryApplyResult? = {
        CivInventoryApplyResult.Conflict(mapOf("reason" to "upkeep-authority-unavailable"))
    }
    internal var validateAutonomousVisualNative: (CivInventoryTransaction) -> CivInventoryApplyResult? = {
        CivInventoryApplyResult.Conflict(mapOf("reason" to "autonomous-work-authority-unavailable"))
    }
    internal var validateMineNative: (CivInventoryTransaction) -> CivInventoryApplyResult? = {
        CivInventoryApplyResult.Conflict(mapOf("reason" to "mine-authority-unavailable"))
    }
    private val nativePersistenceDelegate = lazy { CivsNativePersistence(plugin) { collectedChunkLease(it) } }
    private val nativePersistence by nativePersistenceDelegate
    internal var persistCollectedNative: (CivInventoryTransaction, Boolean) -> CompletableFuture<Unit> = { tx, before ->
        nativePersistence.persist(tx, before)
    }
    internal var persistControlRetirementNative: (Settlement, CivControlPlacement) -> CompletableFuture<Unit> = { city, receipt ->
        nativePersistence.persistRetiredControl(city, receipt)
    }
    private val pendingPersistence = mutableSetOf<CompletableFuture<Unit>>()
    private val persistenceTasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    private val containers = CivsStorageContainers(plugin, tileEntities,
        { cityId -> cityClaims[cityId]?.let(claims::get)?.positions }, ::checkReady,
        { city, position -> foodCanModify(city, position) })
    private val planner = CivsStoragePlanner(containers, ::checkReady, ::checkClaim,
        { cityId -> claims.getValue(cityClaims.getValue(cityId)).playerId }, { residentFoods })
    private val residents = CivsResidentStorage(plugin, containers, ::checkReady, ::checkClaim,
        { receiptBootstrap }, ::discardConsumed, { residentFoods }, { city, position -> foodCanModify(city, position) })
    private var consumedItems = CivConsumedItemIndex.EMPTY
    private var consumedWarning = false
    private var receiptBootstrap = false
    private var closed = false

    init { plugin.server.pluginManager.registerEvents(this, plugin) }

    /** Reservations and cached capture/index state, without resolving a single native inventory. */
    internal fun debug(cityId: UUID?): Map<String, Any?> {
        checkMain()
        fun point(p: CivPosition) = "${p.x},${p.y},${p.z}"
        fun <T> sample(values: Collection<T>, describe: (T) -> Any?): Map<String, Any?> = mapOf(
            "total" to values.size, "truncated" to (values.size > 8), "entries" to values.take(8).map(describe))
        val selected = claims.entries.filter { cityId == null || it.value.city.id == cityId }
        val claimIds = selected.mapTo(hashSetOf()) { it.key }
        val containerLocks = locked.entries.filter { cityId == null || it.value in claimIds }
        val worldCells = lockedWorld.entries.filter { cityId == null || it.value in claimIds }
        val players = lockedPlayers.entries.filter { cityId == null || it.value in claimIds }
        return sample(selected) { (id, claim) -> mapOf("receipt" to id.toString(), "city" to claim.city.id.toString(),
            "revision" to claim.city.revision, "world" to claim.city.worldId.toString(),
            "player" to claim.playerId?.toString(), "sourceMembershipContainers" to claim.positions.size,
            "worldPositions" to claim.worldPositions.size,
            "lockedContainers" to containerLocks.count { it.value == id }, "lockedWorldCells" to worldCells.count { it.value == id })
        } + mapOf("source" to "owner-cache", "closed" to closed, "receiptBootstrap" to receiptBootstrap,
            "consumedGroundItems" to consumedItems.size, "consumedGroundLimit" to CivConsumedItemIndex.MAX_ITEMS,
            "lockedContainers" to sample(containerLocks) { (key, receipt) -> mapOf("world" to key.worldId.toString(),
                "position" to point(key.position), "receipt" to receipt.toString()) },
            "lockedWorldCells" to sample(worldCells) { (key, receipt) -> mapOf("world" to key.worldId.toString(),
                "position" to point(key.position), "receipt" to receipt.toString()) },
            "lockedPlayers" to sample(players) { mapOf("player" to it.key.toString(), "receipt" to it.value.toString()) },
        ) + planner.debug(cityId) + containers.debug(cityId)
    }

    /** Receipt owners are unknown until local journal IO completes; keep before/after states unambiguous. */
    fun beginReceiptBootstrap() {
        checkMain(); requireOpen()
        if (!receiptBootstrap) CivsDiagnostics.event("inventory_bootstrap", "state" to "waiting")
        receiptBootstrap = true
    }

    /** Install every exact lock before releasing unrelated players, independently of network city loading. */
    fun completeReceiptBootstrap(transactions: Collection<CivInventoryTransaction>) =
        completeReceiptBootstrap(transactions, CivConsumedItemIndex.EMPTY)

    internal fun completeReceiptBootstrap(transactions: Collection<CivInventoryTransaction>, consumed: CivConsumedItemIndex) {
        checkMain(); requireOpen()
        transactions.forEach(::lock)
        installConsumed(consumed)
        receiptBootstrap = false
        CivsDiagnostics.event("inventory_bootstrap", "state" to "ready", "receipts" to transactions.size,
            "players" to transactions.count { it.playerId() != null })
    }

    internal fun installConsumed(consumed: CivConsumedItemIndex) {
        checkMain(); requireOpen()
        // Store snapshots only grow; an older async callback may not replace a newer committed index.
        if (consumed.size < consumedItems.size) return
        consumedItems = consumed
        if (!consumedWarning && (consumed.size >= CivConsumedItemIndex.MAX_ITEMS * 4 / 5 ||
            consumed.largestBucket >= CivConsumedItemIndex.MAX_BUCKET_ITEMS * 4 / 5)) {
            consumedWarning = true
            plugin.logger.warning("ArcCivs consumed ground item history or one UUID bucket is at least 80% full (total ${consumed.size}/${CivConsumedItemIndex.MAX_ITEMS}, largest bucket ${consumed.largestBucket}/${CivConsumedItemIndex.MAX_BUCKET_ITEMS}); new pickup will fail closed at capacity. History must not be evicted.")
        }
    }

    /** A known consumed UUID is a stale native copy, regardless of its current chunk or world. */
    private fun discardConsumed(item: Item): Boolean {
        if (receiptBootstrap || item.uniqueId !in consumedItems) return false
        item.remove()
        CivsDiagnostics.event("resident_stale_ground_removed", "entity" to item.uniqueId, "world" to item.world.uid)
        return true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onGroundAdded(event: EntityAddToWorldEvent) {
        (event.entity as? Item)?.let(::discardConsumed)
    }

    fun replaceCities(cities: Collection<Settlement>) {
        checkReady()
        retiringByCity.clear(); retiringControls.clear(); cities.forEach(::rememberRetiringControls)
        planner.clear(); residents.replaceCities(); containers.replaceCities(cities)
    }
    fun updateCity(city: Settlement) {
        checkReady()
        rememberRetiringControls(city)
        containers.updateCity(city); planner.updateCity(city); residents.updateCity(city)
    }
    fun removeCity(cityId: UUID) {
        checkReady()
        retiringByCity.remove(cityId)?.let(retiringControls::removeAll)
        planner.removeCity(cityId); residents.removeCity(cityId); containers.removeCity(cityId)
    }
    fun refreshBuilding(city: Settlement, building: CivBuilding? = null) {
        checkReady(); containers.refreshBuilding(city, building)
    }

    private fun rememberRetiringControls(city: Settlement) {
        retiringByCity.remove(city.id)?.let(retiringControls::removeAll)
        val points = city.pendingControls.orEmpty().filter { it.retire != null }
            .flatMap { listOf(requireNotNull(it.retire).position, it.position) }.mapTo(hashSetOf()) { Key(city.worldId, it) }
        if (points.isNotEmpty()) { retiringByCity[city.id] = points; retiringControls += points }
    }
    internal fun startSupplyCapture(city: Settlement): CivsSupplyCapture {
        checkReady(); return containers.startSupplyCapture(city)
    }
    internal fun supplyCaptureCurrent(city: Settlement, result: CivSupplyCaptureResult, buildingIds: Set<UUID>? = null,
        requireComplete: Boolean = false): Boolean {
        checkReady(); return containers.supplyCaptureCurrent(city, result, buildingIds, requireComplete = requireComplete)
    }
    internal fun supplyRevision(cityId: UUID): Long { checkReady(); return containers.supplyRevision(cityId) }
    internal fun rememberAutonomousCapacity(city: Settlement, result: CivSupplyCaptureResult) {
        checkReady(); containers.rememberAutonomousCapacity(city, result)
    }
    internal fun autonomousCapacityCurrent(city: Settlement, buildingId: UUID? = null): Boolean {
        checkReady(); return containers.autonomousCapacityCurrent(city, buildingId)
    }
    internal fun invalidateSupply(cityId: UUID, buildingIds: Set<UUID>? = null) {
        checkReady(); containers.invalidateSupply(cityId, buildingIds)
    }

    /** Report native viewers within the settlement's current source membership. */
    fun hasViewers(city: Settlement): Boolean {
        checkMain(); requireOpen()
        val positions = containers.positions(city)
        return plugin.server.onlinePlayers.any { player ->
            holderPositions(player.openInventory.topInventory.holder).any { it.worldId == city.worldId && it.position in positions }
        }
    }

    /** Acquire before preparing a receipt; keep it through journal, city commit and journal deletion. */
    fun lock(city: Settlement, transactionId: UUID, player: Player? = null) {
        checkMain()
        requireOpen()
        if (player != null) reject(!player.isOnline || !city.canManage(player.uniqueId), "not-authorized")
        lock(city, transactionId, containers.indexedPositions(city), player?.uniqueId)
    }

    /** Reserve membership for synchronous planning; seal keeps only the receipt's native containers locked. */
    fun reserve(city: Settlement, transactionId: UUID, player: Player? = null) {
        checkMain(); requireOpen()
        if (player != null) reject(!player.isOnline || !(if (city.autonomy != null) player.hasPermission("arccivs.admin")
            else city.canManage(player.uniqueId)), "not-authorized")
        lock(city, transactionId, containers.indexedPositions(city), player?.uniqueId, closeViewers = false)
    }

    fun seal(transaction: CivInventoryTransaction, keepViewersOpen: Boolean = false) {
        checkMain(); requireOpen(); transaction.validated()
        val claim = claims[transaction.id] ?: throw CivsRejection("storage-busy")
        val positions = transaction.changes.associate { it.container to it.containerType } +
            transaction.collected?.containers.orEmpty().associate { it.container to it.containerType } +
            transaction.containerRelocation?.containers.orEmpty().associate { it.position to it.type }
        reject(transaction.containerRelocation == null && positions.keys.any { Key(transaction.after.worldId, it) in retiringControls }, "storage-busy")
        val worldPositions = transaction.worldChanges.orEmpty().mapTo(mutableSetOf(), CivPlanBlock::position)
        val groundItems = transaction.collected?.ground.orEmpty().mapTo(mutableSetOf(), CivGroundItemChange::entityId)
        require(claim.city.id == transaction.cityId && claim.city.revision == transaction.beforeRevision &&
            claim.playerId == transaction.playerId() && positions.all { (position, type) ->
                position in claim.positions && (claim.positions[position] == null || claim.positions[position] == type)
            }) { "Prepared inventory receipt does not match its reservation" }
        transaction.residentTransfer?.let { transfer ->
            require(transfer.before == CivsEquipment.slots(claim.city.workers.firstOrNull { it.id == transfer.workerId }
                ?: claim.city.corpses.orEmpty().first { it.worker.id == transfer.workerId }.worker))
        }
        transaction.resurrection?.let { resurrection ->
            require(claim.city.corpses.orEmpty().single { it.worker.id == resurrection.corpse.worker.id } == resurrection.corpse)
            require(transaction.after == CivsRules.resurrect(claim.city, resurrection.playerId,
                resurrection.corpse.worker.id, resurrection.corpse.diedAt, resurrection.acceptedAt,
                administrator = claim.city.autonomy != null))
        }
        transaction.collected?.let { collected ->
            val worker = claim.city.workers.single { it.id == collected.workerId }
            val afterWorker = transaction.after.workers.single { it.id == worker.id }
            require(collected.before == CivsEquipment.slots(worker))
            require(afterWorker.copy(items = worker.items, inventory = worker.inventory, hasCollectedItems = worker.hasCollectedItems) == worker)
            require(transaction.after.copy(stock = collected.food?.stockBefore ?: transaction.after.stock,
                workers = transaction.after.workers.map { if (it.id == worker.id) worker else it },
                revision = claim.city.revision) == claim.city)
            if (collected.food != null) reject(collected.containers.any { !foodCanModify(claim.city, it.container) }, "lands-denied")
        }
        transaction.cargoDropoff?.let { receipt ->
            require(claim.city.workers.single { it.id == receipt.workerBefore.id } == receipt.workerBefore)
            val cargo = requireNotNull(receipt.workerBefore.cargo)
            require(transaction.after == CivsLogistics.unload(claim.city, receipt.workerBefore.id, cargo.id, receipt.acceptedAt))
            when (val state = classifyCargoTransfer(transaction, expectedBefore = true)) {
                CivInventoryApplyResult.Applied -> Unit
                is CivInventoryApplyResult.Retry -> throw CivsRejection("storage-unavailable", state.values)
                is CivInventoryApplyResult.Conflict -> throw CivsRejection("inventory-conflict", state.values)
                null -> error("Unclassified cargo delivery")
            }
        }
        transaction.cargoPickup?.let { receipt ->
            require(receipt.before(transaction.after) == claim.city)
            when (val state = cargoPickupBeforeState(transaction)) {
                CivInventoryApplyResult.Applied -> Unit
                is CivInventoryApplyResult.Retry -> throw CivsRejection("storage-unavailable", state.values)
                is CivInventoryApplyResult.Conflict -> throw CivsRejection("inventory-conflict", state.values)
                null -> error("Unclassified cargo pickup")
            }
        }
        transaction.forestry?.let { require(it.before(transaction.after) == claim.city) }
        transaction.upkeep?.let { require(it.before(transaction.after) == claim.city) }
        transaction.mine?.let { receipt ->
            val key = receipt.buildingBefore.id.toString()
            require(receipt.buildingBefore == claim.city.building(receipt.buildingBefore.id) &&
                receipt.planBefore == claim.city.minePlans?.get(key) && receipt.legacyBefore == claim.city.mineProgress?.get(key))
            if (receipt.workerBefore != null) require(receipt.before(transaction.after) == claim.city)
        }
        transaction.autoSupplyBuildingId?.let {
            require(transaction.after == claim.city.copy(revision = transaction.after.revision)) {
                "Auto supply may only change native slots and the city revision"
            }
        }
        transaction.containerRelocation?.let {
            require(transaction.after == claim.city.copy(revision = transaction.after.revision))
            require(it.building == claim.city.building(it.building.id))
            when (val state = classifyRelocation(transaction, expectedBefore = true)) {
                CivInventoryApplyResult.Applied -> Unit
                is CivInventoryApplyResult.Retry -> throw CivsRejection("storage-unavailable", state.values)
                is CivInventoryApplyResult.Conflict -> throw CivsRejection("inventory-conflict", state.values)
                null -> error("Unclassified container relocation")
            }
        }
        claim.positions.keys.filterNot { it in positions }.forEach {
            locked.remove(Key(claim.city.worldId, it), transaction.id)
        }
        reject(worldPositions.any { lockedWorld[Key(claim.city.worldId, it)]?.let { id -> id != transaction.id } == true }, "storage-busy")
        reject(groundItems.any { lockedGround[it]?.let { id -> id != transaction.id } == true }, "storage-busy")
        val viewersAllowed = transaction.containerRelocation == null && (keepViewersOpen || claim.playerId == null)
        claims[transaction.id] = claim.copy(worldPositions = worldPositions, groundItems = groundItems,
            viewersAllowed = viewersAllowed)
        worldPositions.forEach { lockedWorld[Key(claim.city.worldId, it)] = transaction.id }
        groundItems.forEach { lockedGround[it] = transaction.id }
        // Retain full frozen source membership for stock reads; native locks cover only receipt coordinates.
        if (!viewersAllowed) closeViewers(transaction.id, claim.playerId)
    }

    /** Exact receipt coordinates remain locked even if their chunks or current index entries are absent. */
    fun lock(transaction: CivInventoryTransaction) {
        checkMain(); requireOpen()
        transaction.validated()
        val positions = transaction.changes.associate { it.container to it.containerType } +
            transaction.collected?.containers.orEmpty().associate { it.container to it.containerType } +
            transaction.containerRelocation?.containers.orEmpty().associate { it.position to it.type }
        lock(transaction.after, transaction.id, positions, transaction.playerId(),
            transaction.worldChanges.orEmpty().mapTo(mutableSetOf(), CivPlanBlock::position),
            transaction.collected?.ground.orEmpty().mapTo(mutableSetOf(), CivGroundItemChange::entityId),
            closeViewers = transaction.containerRelocation != null || transaction.playerId() != null)
    }

    private fun lock(city: Settlement, transactionId: UUID, positions: Map<CivPosition, String?>, playerId: UUID?,
        worldPositions: Set<CivPosition> = emptySet(), groundItems: Set<UUID> = emptySet(), closeViewers: Boolean = true) {
        val existing = claims[transactionId]
        if (existing != null) {
            require(existing.city.id == city.id && existing.city.owner == city.owner && existing.city.worldId == city.worldId && existing.playerId == playerId &&
                existing.worldPositions == worldPositions &&
                existing.groundItems == groundItems &&
                positions.all { (position, type) -> position in existing.positions &&
                    (type == null || existing.positions[position] == null || existing.positions[position] == type) } &&
                (existing.city.revision == city.revision ||
                existing.city.revision < Long.MAX_VALUE && existing.city.revision + 1 == city.revision ||
                city.revision < Long.MAX_VALUE && city.revision + 1 == existing.city.revision)) { "Storage lock identity changed" }
            return
        }
        reject(city.id in cityClaims || playerId != null && playerId in lockedPlayers || positions.keys.any { Key(city.worldId, it) in locked } ||
            worldPositions.any { Key(city.worldId, it) in lockedWorld } || groundItems.any { it in lockedGround }, "storage-busy")
        claims[transactionId] = Claim(city, positions, playerId, worldPositions, groundItems, viewersAllowed = !closeViewers)
        cityClaims[city.id] = transactionId
        if (playerId != null) lockedPlayers[playerId] = transactionId
        positions.keys.forEach { locked[Key(city.worldId, it)] = transactionId }
        worldPositions.forEach { lockedWorld[Key(city.worldId, it)] = transactionId }
        groundItems.forEach { lockedGround[it] = transactionId }
        try {
            // Closing current viewers scales with players, not every container in a large settlement.
            if (closeViewers) closeViewers(transactionId, playerId)
        } catch (error: Exception) {
            unlock(transactionId)
            throw error
        }
    }

    private fun closeViewers(transactionId: UUID, playerId: UUID?) {
        plugin.server.onlinePlayers.forEach { player ->
            if (player.uniqueId == playerId || holderPositions(player.openInventory.topInventory.holder).any {
                    locked[Key(it.worldId, it.position)] == transactionId
                }) player.closeInventory()
        }
    }

    fun unlock(transactionId: UUID) {
        checkMain()
        val claim = claims.remove(transactionId) ?: return
        cityClaims.remove(claim.city.id, transactionId)
        claim.playerId?.let { lockedPlayers.remove(it, transactionId) }
        claim.positions.keys.forEach { locked.remove(Key(claim.city.worldId, it), transactionId) }
        claim.worldPositions.forEach { lockedWorld.remove(Key(claim.city.worldId, it), transactionId) }
        claim.groundItems.forEach { lockedGround.remove(it, transactionId) }
    }

    /** Main-thread claim identity; a source fence keeps the same city revision reserved. */
    internal fun hasClaim(city: Settlement): Boolean {
        checkMain(); requireOpen()
        return cityClaims[city.id]?.let(claims::get)?.city == city
    }

    internal fun cargoPickupBeforeState(transaction: CivInventoryTransaction): CivInventoryApplyResult? {
        checkMain(); requireOpen()
        val receipt = transaction.cargoPickup ?: error("Missing cargo pickup proof")
        val claim = claims[transaction.id]
        if (cityClaims[transaction.cityId] != transaction.id || claim?.city != receipt.before(transaction.after))
            return CivInventoryApplyResult.Conflict(mapOf("reason" to "cargo-claim-changed"))
        return classifyCargoTransfer(transaction, expectedBefore = true)
    }

    /** Exact accepted world cells; independent of the native-container index and unloaded chunks. */
    fun isWorldLocked(worldId: UUID, position: CivPosition): Boolean = Key(worldId, position) in lockedWorld

    internal fun prepareGroundPickup(city: Settlement, workerId: UUID, entityIds: List<UUID>, feet: org.bukkit.Location): CivCollectedPlan =
        residents.prepareGroundPickup(city, workerId, entityIds, feet)
    internal fun prepareForestryLeafLoot(city: Settlement, workerId: UUID, entityIds: List<UUID>): CivCollectedPlan =
        residents.prepareForestryLeafLoot(city, workerId, entityIds)
    internal fun homeDestination(city: Settlement, worker: CivWorker, reserve: Map<String, Long>): CivPosition? =
        residents.homeDestination(city, worker, reserve)
    internal fun residentFoodSource(city: Settlement, worker: CivWorker, feet: org.bukkit.Location,
        tavernService: Boolean = false): CivResidentFoodSource? = residents.residentFoodSource(city, worker, feet, tavernService)
    internal fun prepareFoodMaterialization(city: Settlement, workerId: UUID, feet: org.bukkit.Location): CivCollectedPlan =
        residents.prepareFoodMaterialization(city, workerId, feet)
    internal fun prepareFoodPickup(city: Settlement, workerId: UUID, target: CivResidentFoodSource, feet: org.bukkit.Location): CivCollectedPlan =
        residents.prepareFoodPickup(city, workerId, target, feet)
    internal fun prepareHomeDeposit(city: Settlement, workerId: UUID, destination: CivPosition,
        feet: org.bukkit.Location, reserve: Map<String, Long>): CivCollectedPlan = residents.prepareHomeDeposit(city, workerId, destination, feet, reserve)

    internal fun prepareUpgradeRelocation(city: Settlement, target: CivBuilding,
        future: Map<CivPosition, String>): CivContainerRelocation? {
        checkClaim(city)
        val building = city.building(target.id) ?: throw CivsRejection("building-not-found")
        require(building.phase == BuildingPhase.ACTIVE && !building.type.isInfrastructure &&
            target.type == building.type && target.level == building.level + 1 &&
            target.rotation == building.rotation && target.entrance == building.entrance)
        val positions = containers.buildingPositions(city, building.id)
        reject(positions.size > CivContainerRelocation.MAX_CONTAINERS, "storage-transaction-too-large")
        reject(positions.keys.map { (it.x shr 4) to (it.z shr 4) }.distinct().size > 8, "storage-transaction-too-large")
        val world = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("storage-unavailable")
        fun capture(point: CivPosition, type: String?): CivRelocatedContainer {
            reject(!foodCanModify(city, point), "lands-denied")
            val holder = containers.resolve(city, point, type)
            val contents = holder.inventory.storageContents.map(CivResidentItem::of)
            require(contents.size == STORAGE_SLOTS)
            return CivRelocatedContainer(point, holder.type, world.getBlockAt(point.x, point.y, point.z).blockData.asString,
                contents, contents)
        }
        val holders = positions.map { (point, type) -> capture(point, type) }.toMutableList()
        fun plan() = civsPlanContainerRelocation(building, target, future, holders)?.also { it.validated(city) }
        val full = try { return plan() } catch (failure: CivsRejection) {
            if (failure.reason != "storage-full") throw failure
            failure
        }
        val retained = civsRelocationRetained(building, target, future, holders)
        val outputMaximums = holders.filter { it.position !in retained }.flatMap { it.before.filterNotNull() }
            .distinctBy { it.material to it.data }.associate { (it.material to it.data) to it.stack().maxStackSize }
        val candidates = containers.positions(city).filter { (point, _) ->
            point !in positions && point !in future && !civsRelocationLocal(target, point) &&
                civsRelocationExternalReceiver(city, building, point)
        }
        val captured = mutableMapOf<CivPosition, CivRelocatedContainer?>()
        fun receiver(point: CivPosition): CivRelocatedContainer? {
            if (point in captured) return captured[point]
            if (point !in candidates || captured.size >= CivContainerRelocation.MAX_CONTAINERS) return null
            val value = if (!world.isChunkLoaded(point.x shr 4, point.z shr 4) || !foodCanModify(city, point)) null
            else try { capture(point, candidates[point]) } catch (failure: CivsRejection) {
                if (failure.reason !in setOf("storage-unavailable", "storage-container-invalid", "not-authorized")) throw failure
                null
            }
            captured[point] = value
            return value
        }
        // ponytail: at most 32 additional indexed reads; larger searches need sliced discovery, not chunk loads.
        for (point in candidates.keys.sortedWith(compareBy<CivPosition>(
                { it.point().distanceSquared(building.entrance.point()) }, { it.x }, { it.y }, { it.z }))) {
            if (holders.size == CivContainerRelocation.MAX_CONTAINERS) break
            if (holders.any { it.position == point }) continue
            val holder = receiver(point) ?: continue
            val partner = relocationChestPartner(point, holder.blockData)
            val group = if (partner == null) listOf(holder) else listOf(holder, receiver(partner) ?: continue)
            if (holders.size + group.size > CivContainerRelocation.MAX_CONTAINERS ||
                (holders + group).map { (it.position.x shr 4) to (it.position.z shr 4) }.distinct().size > 8) continue
            val unchanged = civsRelocationRetained(building, target, future, holders + group)
            if (group.any { it.position !in unchanged } || group.none { destination -> destination.before.any { slot ->
                    slot == null || slot.amount < (outputMaximums[slot.material to slot.data] ?: 0)
                } }) continue
            holders += group
            try { return plan() } catch (failure: CivsRejection) {
                if (failure.reason != "storage-full") throw failure
            }
        }
        throw full
    }

    /** Evacuate only the old control into existing holders; an uncreated replacement never receives items. */
    internal fun prepareControlRelocation(city: Settlement, building: CivBuilding, from: CivPosition, to: CivPosition): CivContainerRelocation? {
        checkClaim(city)
        require(city.building(building.id) == building && city.autonomy != null && city.inventory?.mode == CivStorageMode.PHYSICAL &&
            building.phase == BuildingPhase.ACTIVE && building.type in CivsWorkstations.TYPES && from != to &&
            building.bounds.contains(from.x, from.y, from.z) && building.bounds.contains(to.x, to.y, to.z) &&
            (from == building.control || city.pendingControls.orEmpty().any { it.buildingId == building.id && it.retire?.position == from && it.position == to }))
        val target = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("world-unavailable")
        reject(!target.isChunkLoaded(to.x shr 4, to.z shr 4), "chunk-unloaded")
        val replacement = target.getBlockAt(to.x, to.y, to.z)
        reject(replacement.type != Material.BARREL && !replacement.type.isAir, "control-obstructed")
        fun capture(point: CivPosition): CivRelocatedContainer {
            reject(!target.isChunkLoaded(point.x shr 4, point.z shr 4), "chunk-unloaded")
            reject(!foodCanModify(city, point), "lands-denied")
            val native = target.getBlockAt(point.x, point.y, point.z)
            reject(native.type.name !in CIVS_STORAGE_TYPES || point == from && native.type != Material.BARREL, "storage-container-invalid")
            val source = containers.resolve(city, point, native.type.name)
            val items = source.inventory.storageContents.map(CivResidentItem::of)
            require(items.size == STORAGE_SLOTS)
            return CivRelocatedContainer(point, source.type, native.blockData.asString, items, items)
        }
        val previous = capture(from)
        if (previous.before.all { it == null }) return null
        val receivers = (if (replacement.type == Material.BARREL) listOf(to) else emptyList()) +
            containers.buildingPositions(city, building.id).keys.filter { it != from && it != to && building.bounds.contains(it.x, it.y, it.z) }
        reject(receivers.size + 1 > CivContainerRelocation.MAX_CONTAINERS, "storage-transaction-too-large")
        reject(receivers.isEmpty(), "storage-full")
        val holders = listOf(previous) + receivers.map(::capture)
        return civsPlanContainerRelocation(building, building.copy(control = to),
            holders.associate { it.position to if (it.position == from) "minecraft:air" else it.blockData }, holders)?.also { it.validated(city) }
    }

    fun prepareResurrection(city: Settlement, player: Player, corpse: CivCorpse, at: Long,
        location: org.bukkit.Location): CivResurrectionDebit {
        checkClaim(city)
        reject(!player.isOnline || !(if (city.autonomy != null) player.hasPermission("arccivs.admin")
            else city.canManage(player.uniqueId)), "not-authorized")
        reject(location.world?.uid != city.worldId || !civsCorpseStandingSafe(location), "corpse-unavailable")
        val slot = player.inventory.heldItemSlot
        val item = player.inventory.getItem(slot)
        reject(!CivsResurrectionScroll.matches(item), "resurrection-scroll-required")
        val before = requireNotNull(CivResidentItem.of(item))
        return CivResurrectionDebit(player.uniqueId, corpse,
            CivNativePlayerSlot(slot, before, before.takeIf { it.amount > 1 }?.copy(amount = before.amount - 1)),
            at, CivPosition(location.blockX, location.blockY, location.blockZ), location.y)
    }

    /** Auto corpses have exact belongings but deliberately have no physical-stock record. */
    fun prepareCorpseTransfer(city: Settlement, workerId: UUID, player: Player, npcSlot: Int,
        click: org.bukkit.event.inventory.ClickType): CivCorpseTransferPlan {
        checkClaim(city)
        reject(!player.isOnline || !(if (city.autonomy != null) player.hasPermission("arccivs.admin")
            else city.canManage(player.uniqueId)), "not-authorized")
        reject(!isEmpty(player.itemOnCursor), "resident-cursor-busy")
        val worker = city.corpses.orEmpty().firstOrNull { it.worker.id == workerId }?.worker
            ?: throw CivsRejection("worker-not-found")
        val (updated, changes) = CivsEquipment.transfer(worker, player, npcSlot, null, click)
        return CivCorpseTransferPlan(updated, changes.takeIf { it.isNotEmpty() }?.let {
            CivResidentTransfer(player.uniqueId, workerId, CivsEquipment.slots(worker), it)
        })
    }

    fun prepareResidentTransfer(city: Settlement, workerId: UUID, player: Player, npcSlot: Int?, playerSlot: Int?, click: org.bukkit.event.inventory.ClickType, requestedTool: Material? = null, requestedSupply: Pair<Material, Long>? = null, requestedSupplies: Map<String, Long>? = null): CivResidentTransferPlan {
        checkClaim(city)
        reject(!city.canManage(player.uniqueId) || !player.isOnline, "not-authorized")
        val resident = city.workers.firstOrNull { it.id == workerId }
        val worker = resident ?: city.corpses.orEmpty().firstOrNull { it.worker.id == workerId }?.worker
            ?: throw CivsRejection("worker-not-found")
        reject(resident == null && (npcSlot == null || playerSlot != null || requestedTool != null ||
            requestedSupply != null || requestedSupplies != null), "not-authorized")
        val sourceSlot = if (playerSlot == CIVS_RESIDENT_CURSOR_SLOT) {
            require(npcSlot != null && click in setOf(org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.ClickType.RIGHT))
            val cursor = CivResidentItem.of(player.itemOnCursor) ?: throw CivsRejection("resident-item-missing")
            // This reversible move keeps ownership with the player if planning/journalling subsequently fails.
            val free = (0..35).firstOrNull { isEmpty(player.inventory.getItem(it)) } ?: throw CivsRejection("storage-full")
            player.inventory.setItem(free, cursor.stack())
            player.setItemOnCursor(null)
            free
        } else {
            reject(!isEmpty(player.itemOnCursor), "resident-cursor-busy")
            playerSlot
        }
        val (updated, changes) = if (requestedSupplies != null) {
            validateAmounts(requestedSupplies)
            CivsEquipment.giveRequestedSupplies(worker, player, requestedSupplies)
        } else if (requestedSupply != null) CivsEquipment.giveRequestedSupply(worker, player, requestedSupply.first, requestedSupply.second)
            else if (requestedTool != null) CivsEquipment.giveRequestedTool(worker, player, requestedTool)
            else CivsEquipment.transfer(worker, player, npcSlot, sourceSlot, click)
        val transfer = changes.takeIf { it.isNotEmpty() }?.let { CivResidentTransfer(player.uniqueId, workerId, CivsEquipment.slots(worker), it) }
        return CivResidentTransferPlan(CivStoragePlan(requireNotNull(city.inventory), emptyList(), residentTransfer = transfer), updated)
    }

    fun available(city: Settlement): Map<String, Long> = planner.available(city)
    fun buildingAvailable(city: Settlement, buildingId: UUID): Map<String, Long> = planner.buildingAvailable(city, buildingId)
    fun constructionAvailable(city: Settlement, buildingId: UUID): Map<String, Long> = planner.constructionAvailable(city, buildingId)
    fun prepareDebit(city: Settlement, required: Map<String, Long>): CivStoragePlan = planner.prepareDebit(city, required)
    fun prepareDebitBackground(city: Settlement, required: Map<String, Long>, buildingId: UUID? = null): CivStoragePlan? =
        planner.prepareDebitBackground(city, required, buildingId)
    fun prepareConstructionDebitBackground(city: Settlement, required: Map<String, Long>, buildingId: UUID): CivStoragePlan? =
        planner.prepareConstructionDebitBackground(city, required, buildingId)
    fun prepareCargoPickup(city: Settlement, cargo: CivCargo): CivStoragePlan = planner.prepareCargoPickup(city, cargo)
    fun cargoRoom(city: Settlement, buildingId: UUID, material: String): Long = planner.cargoRoom(city, buildingId, material)
    fun prepareCargoDropoff(city: Settlement, cargo: CivCargo, destinationBuildingId: UUID): CivStoragePlan =
        planner.prepareCargoDropoff(city, cargo, destinationBuildingId)
    /** Admission observes only permitted local city holders; it never loads a candidate chunk. */
    internal fun redirectCargoRoom(city: Settlement, buildingId: UUID, material: String): Long {
        if (city.inventory?.mode != CivStorageMode.PHYSICAL) return 0
        return planner.cargoRoom(city, buildingId, material) { foodCanModify(city, it) }
    }

    internal fun requireRedistributionCapacity(city: Settlement, cargo: CivCargo, reservations: Collection<CivCourierPlan>) =
        planner.requireRedistributionCapacity(city, cargo, reservations) { foodCanModify(city, it) }

    internal fun prepareRedistributionPickup(city: Settlement, workerId: UUID, cargo: CivCargo,
        reservations: Collection<CivCourierPlan>, requireArrival: () -> Unit): CivStoragePlan {
        checkClaim(city)
        reject(!CivsLogistics.redistributionAllowed(city, cargo) ||
            city.workers.firstOrNull { it.id == workerId }?.cargo != null, "stale-delivery")
        requireArrival()
        requireRedistributionCapacity(city, cargo, reservations)
        val prepared = planner.prepareCargoPickup(city, cargo)
        reject(prepared.changes.none { it.before != null && it.after == null }, "stale-delivery")
        val nativeWorld = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("storage-unavailable")
        val states = prepared.changes.map(CivInventorySlotChange::container).distinct().map { point ->
            reject(!foodCanModify(city, point), "lands-denied")
            CivCargoContainerState(point, nativeWorld.getBlockAt(point.x, point.y, point.z).blockData.asString)
        }
        requireArrival()
        return prepared.copy(cargoPickup = CivCargoPickupReceipt(city.workers.single { it.id == workerId }, cargo,
            requireNotNull(city.building(cargo.sourceBuildingId)), requireNotNull(city.building(cargo.targetBuildingId)), states).detached())
    }

    internal fun prepareCarriedCargoDropoff(city: Settlement, workerId: UUID, cargo: CivCargo,
        destinationId: UUID, at: Long, requireArrival: () -> Unit): CivStoragePlan {
        checkClaim(city)
        reject(city.inventory?.mode != CivStorageMode.PHYSICAL || !cargo.inventoryBacked || cargo.stockResource != null ||
            city.workers.firstOrNull { it.id == workerId }?.cargo != cargo ||
            CivsLogistics.destination(city, cargo)?.id != destinationId, "stale-delivery")
        requireArrival()
        val prepared = planner.prepareCargoDropoff(city, cargo, destinationId) { foodCanModify(city, it) }
        val nativeWorld = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("storage-unavailable")
        val states = prepared.changes.map(CivInventorySlotChange::container).distinct().map { point ->
            CivCargoContainerState(point, nativeWorld.getBlockAt(point.x, point.y, point.z).blockData.asString)
        }
        requireArrival()
        val receipt = CivCargoDropoffReceipt(city.workers.single { it.id == workerId },
            requireNotNull(city.building(destinationId)), at, states).detached()
        return prepared.copy(cargoDropoff = receipt)
    }

    fun prepareResidentPickup(city: Settlement, workerId: UUID, buildingId: UUID, required: Map<String, Long>): CivResidentTransferPlan =
        planner.prepareResidentPickup(city, workerId, buildingId, required)
    fun prepareResidentReturn(city: Settlement, workerId: UUID, buildingId: UUID,
        craftInputs: Map<String, Long> = emptyMap()): CivResidentTransferPlan = planner.prepareResidentReturn(city, workerId, buildingId, craftInputs)
    fun prepareBuilderPickup(city: Settlement, workerId: UUID,
        nextMaterials: Map<String, Long> = city.construction?.payment?.awaiting.orEmpty()): CivResidentTransferPlan =
        planner.prepareBuilderPickup(city, workerId, nextMaterials)
    fun prepareProduction(city: Settlement, building: CivBuilding, worker: CivWorker? = null, craftJob: CivCraftJob? = null): CivStoragePlan =
        planner.prepareProduction(city, building, worker, craftJob)
    fun prepareWork(city: Settlement, buildingId: UUID, inputs: Map<String, Long>, outputs: Map<String, Long>,
        worldChanges: List<CivPlanBlock>, work: CivWorldWork? = null): CivStoragePlan =
        planner.prepareWork(city, buildingId, inputs, outputs, worldChanges, work)
    fun prepareCredit(city: Settlement, material: String, count: Long, buildingId: UUID? = null): CivStoragePlan =
        planner.prepareCredit(city, material, count, buildingId)
    fun prepareConstructionSupply(city: Settlement, buildingId: UUID, required: Map<String, Long>, player: Player? = null): CivStorageSupplyPlan =
        planner.prepareConstructionSupply(city, buildingId, required, player)
    internal fun prepareAutoSupply(city: Settlement, demand: CivAutoSupplyDemand, canModify: (CivPosition) -> Boolean): CivStoragePlan? =
        planner.prepareAutoSupply(city, demand, canModify)
    fun prepareMaterialization(city: Settlement, targetMode: CivStorageMode = CivStorageMode.PHYSICAL): CivStoragePlan =
        planner.prepareMaterialization(city, targetMode)
    fun depositFromControl(city: Settlement, building: CivBuilding): CivStoragePlan = planner.depositFromControl(city, building)
    fun withdrawToControl(city: Settlement, building: CivBuilding, material: String, maximum: Int = 64): CivStoragePlan =
        planner.withdrawToControl(city, building, material, maximum)

    fun openControl(player: Player, city: Settlement, building: CivBuilding) {
        checkMain()
        requireOpen()
        reject(!(city.canManage(player.uniqueId) || city.autonomy != null) || player.world.uid != city.worldId, "not-authorized")
        val source = containers.control(city, building)
        reject(playerLocked(player.uniqueId) || viewLocked(Key(city.worldId, source.position)), "storage-busy")
        player.openInventory(source.inventory)
    }

    /** Read-only proof for retiring an already committed road receipt; no before or mixed endpoint is accepted. */
    internal fun upkeepNativeApplied(transaction: CivInventoryTransaction): Boolean {
        checkMain(); requireOpen()
        if (transaction.upkeep == null) return false
        transaction.validated()
        val claim = claims[transaction.id] ?: return false
        if (claim.city.id != transaction.cityId || claim.city.owner != transaction.after.owner ||
            claim.city.worldId != transaction.after.worldId || claim.city.revision !in transaction.beforeRevision..transaction.after.revision)
            return false
        val sources = linkedMapOf<CivPosition, Source>()
        for (change in transaction.changes) {
            if (locked[Key(transaction.after.worldId, change.container)] != transaction.id) return false
            try {
                sources.getOrPut(change.container) { containers.resolve(transaction.after, change.container, change.containerType) }
            } catch (_: CivsRejection) { return false }
        }
        val world = plugin.server.getWorld(transaction.after.worldId) ?: return false
        val cells = linkedMapOf<CivPosition, Pair<Block, BlockData>>()
        for (cell in transaction.worldChanges.orEmpty()) {
            if (lockedWorld[Key(transaction.after.worldId, cell.position)] != transaction.id ||
                cell.position.y !in world.minHeight until world.maxHeight ||
                !world.isChunkLoaded(cell.position.x shr 4, cell.position.z shr 4)) return false
            val block = world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
            if (block.state is TileState) return false
            val after = try { Bukkit.createBlockData(cell.after) } catch (_: IllegalArgumentException) { return false }
            cells[cell.position] = block to after
        }
        return classify(transaction, sources) == CivInventoryApplyResult.Applied &&
            classifyWorld(transaction, cells) == CivInventoryApplyResult.Applied
    }

    /** All slots are classified first; drift prevents every write, including otherwise valid cells. */
    fun reconcile(transaction: CivInventoryTransaction): CivInventoryApplyResult {
        checkMain()
        requireOpen()
        transaction.validated()
        val claim = claims[transaction.id] ?: throw CivsRejection("storage-busy")
        require(claim.city.id == transaction.cityId && claim.city.owner == transaction.after.owner &&
            claim.city.worldId == transaction.after.worldId && claim.city.revision in transaction.beforeRevision..transaction.after.revision)
        val playerId = transaction.playerId()
        val playerChanges = transaction.residentTransfer?.changes ?: transaction.resurrection?.let { listOf(it.change) } ?: transaction.playerDebit?.changes.orEmpty().map {
            CivNativePlayerSlot(it.slot, CivResidentItem(it.before.material, it.before.amount), it.after?.let { item -> CivResidentItem(item.material, item.amount) })
        }
        playerChanges.forEach { change ->
            if (runCatching { change.before?.stack(); change.after?.stack() }.isFailure)
                return CivInventoryApplyResult.Conflict(mapOf("reason" to "invalid-player-item", "slot" to change.slot.toString()))
        }
        transaction.resurrection?.let {
            if (!CivsResurrectionScroll.matches(it.change.before?.stack()))
                return CivInventoryApplyResult.Conflict(mapOf("reason" to "invalid-resurrection-scroll"))
        }
        val player = if (playerId == null) null else {
            if (claim.playerId != playerId || lockedPlayers[playerId] != transaction.id)
                return CivInventoryApplyResult.Conflict(mapOf("reason" to "unlocked-player", "player" to playerId.toString()))
            plugin.server.getPlayer(playerId)?.takeIf { it.isOnline }
                ?: return CivInventoryApplyResult.Retry(mapOf("reason" to "player-offline", "player" to playerId.toString()))
        }
        val resolved = linkedMapOf<CivPosition, Source>()
        for (change in transaction.changes) {
            if (locked[Key(transaction.after.worldId, change.container)] != transaction.id)
                return CivInventoryApplyResult.Conflict(details(transaction.after, change, "unlocked-container"))
            if (listOfNotNull(change.before, change.after).any { item ->
                    val prototype = try { stack(item.material, 1) } catch (_: CivsRejection) { null }
                    prototype == null || item.amount > prototype.maxStackSize
                }) return CivInventoryApplyResult.Conflict(details(transaction.after, change, "invalid-item"))
            try {
                val source = resolved.getOrPut(change.container) { containers.resolve(transaction.after, change.container, change.containerType) }
                if (source.type != change.containerType) return CivInventoryApplyResult.Conflict(details(transaction.after, change, "container-type-changed"))
            } catch (failure: CivsRejection) {
                return if (failure.reason == "storage-unavailable") CivInventoryApplyResult.Retry(failure.values)
                else CivInventoryApplyResult.Conflict(failure.values)
            }
        }
        val worldCells = linkedMapOf<CivPosition, Pair<Block, BlockData>>()
        for (cell in transaction.worldChanges.orEmpty()) {
            if (lockedWorld[Key(transaction.after.worldId, cell.position)] != transaction.id)
                return CivInventoryApplyResult.Conflict(worldDetails(transaction, cell, "unlocked-world-cell"))
            val world = plugin.server.getWorld(transaction.after.worldId)
                ?: return CivInventoryApplyResult.Retry(worldDetails(transaction, cell, "world-unloaded"))
            if (cell.position.y !in world.minHeight until world.maxHeight)
                return CivInventoryApplyResult.Conflict(worldDetails(transaction, cell, "outside-world"))
            if (!world.isChunkLoaded(cell.position.x shr 4, cell.position.z shr 4))
                return CivInventoryApplyResult.Retry(worldDetails(transaction, cell, "chunk-unloaded"))
            val block = world.getBlockAt(cell.position.x, cell.position.y, cell.position.z)
            if (block.state is TileState) return CivInventoryApplyResult.Conflict(worldDetails(transaction, cell, "world-block-entity"))
            val after = try { Bukkit.createBlockData(cell.after) } catch (_: IllegalArgumentException) {
                return CivInventoryApplyResult.Conflict(worldDetails(transaction, cell, "invalid-world-state"))
            }
            worldCells[cell.position] = block to after
        }
        val worldState = classifyWorld(transaction, worldCells)
        if (worldState is CivInventoryApplyResult.Conflict) return worldState
        val collectedState = classifyCollected(transaction)
        if (collectedState is CivInventoryApplyResult.Conflict || collectedState is CivInventoryApplyResult.Retry) return collectedState
        val relocationState = classifyRelocation(transaction)
        if (relocationState is CivInventoryApplyResult.Conflict || relocationState is CivInventoryApplyResult.Retry) return relocationState
        val playerState = classifyPlayer(playerId, playerChanges, player)
        if (playerState is CivInventoryApplyResult.Conflict || playerState is CivInventoryApplyResult.Retry) return playerState
        val containerState = classify(transaction, resolved)
        if (containerState is CivInventoryApplyResult.Conflict) return containerState
        when (val cargoState = classifyCargoTransfer(transaction)) {
            is CivInventoryApplyResult.Retry -> return cargoState
            is CivInventoryApplyResult.Conflict -> return cargoState
            else -> Unit
        }
        if (transaction.upkeep != null) when (val authority = validateUpkeepNative(transaction)) {
            is CivInventoryApplyResult.Retry -> return authority
            is CivInventoryApplyResult.Conflict -> return authority
            else -> Unit
        }
        if (transaction.autonomousVisual != null) when (val authority = validateAutonomousVisualNative(transaction)) {
            is CivInventoryApplyResult.Retry -> return authority
            is CivInventoryApplyResult.Conflict -> return authority
            else -> Unit
        }
        if (transaction.mine != null) when (val authority = validateMineNative(transaction)) {
            is CivInventoryApplyResult.Retry -> return authority
            is CivInventoryApplyResult.Conflict -> return authority
            else -> Unit
        }
        val nativeAuthorityBuilding = transaction.autoSupplyBuildingId ?: transaction.upkeep
            ?.takeIf { transaction.after.inventory?.mode == CivStorageMode.PHYSICAL }?.work?.buildingId
        if (nativeAuthorityBuilding != null) for (change in transaction.changes) {
            val native = resolved.getValue(change.container).inventory.getItem(change.slot)
            if (!matches(native, change.after) && !foodCanModify(transaction.after, change.container))
                return CivInventoryApplyResult.Conflict(details(transaction.after, change, "lands-denied") +
                    mapOf("building" to nativeAuthorityBuilding.toString()))
        }
        if (player == null && containerState == CivInventoryApplyResult.Applied && worldState == CivInventoryApplyResult.Applied &&
            collectedState == CivInventoryApplyResult.Applied && relocationState == CivInventoryApplyResult.Applied) return containerState
        var playerPersisted = player == null
        try {
            if (player != null) {
                playerChanges.forEach { change ->
                    if (!matchesNative(player.inventory.getItem(change.slot), change.after))
                        player.inventory.setItem(change.slot, change.after?.stack())
                }
                val verified = classifyPlayer(playerId, playerChanges, player)
                if (verified != CivInventoryApplyResult.Applied) return verified
                    ?: CivInventoryApplyResult.Retry(mapOf("reason" to "player-write-incomplete"))
                // Persist the debit before crediting any city holder or retiring the durable receipt.
                playerDataPersistence.persist(player)
                playerPersisted = true
                val persisted = classifyPlayer(playerId, playerChanges, player)
                if (persisted != CivInventoryApplyResult.Applied) return persisted
                    ?: CivInventoryApplyResult.Retry(mapOf("reason" to "player-write-incomplete"))
            }
            transaction.changes.forEach { change ->
                if ((transaction.cargoDropoff != null || transaction.cargoPickup != null)) when (val state = cargoTransferSlotState(transaction, change)) {
                    is CivInventoryApplyResult.Retry -> return state
                    is CivInventoryApplyResult.Conflict -> return state
                    else -> Unit
                }
                val inventory = if (transaction.cargoDropoff == null && transaction.cargoPickup == null) resolved.getValue(change.container).inventory
                    else containers.resolve(transaction.after, change.container, change.containerType).inventory
                if (!matches(inventory.getItem(change.slot), change.after)) {
                    inventory.setItem(change.slot, change.after?.let { stack(it.material, it.amount) })
                }
            }
            transaction.worldChanges.orEmpty().forEach { cell ->
                val (block, after) = worldCells.getValue(cell.position)
                if (!worldAfterMatches(transaction, block.blockData, after, cell)) block.setBlockData(after, false)
            }
            transaction.collected?.let { collected ->
                val world = requireNotNull(plugin.server.getWorld(transaction.after.worldId))
                collected.ground.forEach { change ->
                    (world.getEntity(change.entityId) as? Item)?.let { item ->
                        if (change.after == null) item.remove()
                        else if (!matchesNative(item.itemStack, change.after)) item.itemStack = change.after.stack()
                    }
                }
                collected.containers.forEach { change ->
                    val inventory = containers.resolve(transaction.after, change.container, change.containerType).inventory
                    if (!matchesNative(inventory.getItem(change.slot), change.after)) inventory.setItem(change.slot, change.after?.stack())
                }
            }
            transaction.containerRelocation?.changes()?.forEach { change ->
                val inventory = containers.resolve(transaction.after, change.container, change.containerType).inventory
                if (!matchesNative(inventory.getItem(change.slot), change.after)) inventory.setItem(change.slot, change.after?.stack())
            }
        } catch (failure: Exception) {
            CivsDiagnostics.record(plugin.logger, Level.WARNING, transaction.cityId, "ArcCivs could not apply storage transaction ${transaction.id} for ${transaction.cityId}", failure)
            if (!playerPersisted) return CivInventoryApplyResult.Retry(mapOf("reason" to "player-save-incomplete"))
            return completedNativeState(transaction, resolved, worldCells)
        }
        return completedNativeState(transaction, resolved, worldCells)
    }

    fun apply(transaction: CivInventoryTransaction): CivInventoryApplyResult = reconcile(transaction)

    /** Collected transfers fence both states; typed cargo persists its native credit before city debit. */
    internal fun persistCollected(transaction: CivInventoryTransaction, beforeJournal: Boolean = false): CompletableFuture<Unit> {
        checkMain(); requireOpen()
        if (transaction.collected == null && transaction.containerRelocation == null && transaction.cargoDropoff == null && transaction.cargoPickup == null)
            return CompletableFuture.completedFuture(Unit)
        fun checkState() {
            val state = if (transaction.containerRelocation != null) classifyRelocation(transaction, beforeJournal)
                else if (transaction.cargoDropoff != null || transaction.cargoPickup != null) classifyCargoTransfer(transaction, beforeJournal)
                else classifyCollected(transaction, beforeJournal)
            when (state) {
                CivInventoryApplyResult.Applied -> Unit
                is CivInventoryApplyResult.Retry -> throw CivsRejection("inventory-recovery", state.values)
                is CivInventoryApplyResult.Conflict -> throw CivsRejection("inventory-conflict", state.values)
                null -> throw CivsRejection("inventory-recovery")
            }
        }
        return nativeBarrier({ persistCollectedNative(transaction, beforeJournal) }, ::checkState)
    }

    internal fun persistControlRetirement(city: Settlement, receipt: CivControlPlacement): CompletableFuture<Unit> = nativeBarrier({
        persistControlRetirementNative(city, receipt)
    }) {
        val current = relocationCurrentCity(city.id) ?: throw CivsRejection("no-city")
        reject(receipt !in current.pendingControls.orEmpty() || current.building(receipt.buildingId)?.control != receipt.position, "control-location-changed")
        val point = requireNotNull(receipt.retire).position
        val world = plugin.server.getWorld(current.worldId) ?: throw CivsRejection("world-unavailable")
        reject(!world.isChunkLoaded(point.x shr 4, point.z shr 4), "chunk-unloaded")
        reject(!world.getBlockAt(point.x, point.y, point.z).type.isAir, "world-changed")
        reject(!world.isChunkLoaded(receipt.position.x shr 4, receipt.position.z shr 4) ||
            world.getBlockAt(receipt.position.x, receipt.position.y, receipt.position.z).type != Material.BARREL, "control-obstructed")
    }

    private fun nativeBarrier(persist: () -> CompletableFuture<Unit>, checkState: () -> Unit): CompletableFuture<Unit> {
        checkMain(); requireOpen(); checkState()
        val epoch = persistenceTasks.token()
        val result = CompletableFuture<Unit>()
        pendingPersistence += result
        val native = try { persist() } catch (failure: Throwable) {
            pendingPersistence -= result
            throw failure
        }
        native.whenComplete { _, failure ->
            val complete = complete@{
                if (!pendingPersistence.remove(result)) return@complete
                val problem = failure ?: runCatching { requireOpen(); checkState() }.exceptionOrNull()
                if (problem == null) result.complete(Unit) else result.completeExceptionally(problem)
                Unit
            }
            if (Bukkit.isPrimaryThread()) complete()
            else persistenceTasks.callSync(epoch, complete).whenComplete { _, dispatchFailure ->
                if (dispatchFailure != null) result.completeExceptionally(dispatchFailure)
            }
        }
        return result
    }

    internal fun cancelCollectedPersistence() {
        checkMain()
        if (nativePersistenceDelegate.isInitialized()) nativePersistence.cancelPending()
        pendingPersistence.toList().forEach {
            pendingPersistence.remove(it)
            it.completeExceptionally(CivsRejection("not-ready"))
        }
        if (closed) persistenceTasks.close() else persistenceTasks.restart()
    }

    internal fun collectedBeforeState(transaction: CivInventoryTransaction): CivInventoryApplyResult? {
        checkMain(); requireOpen()
        return classifyCollected(transaction, true)
    }

    internal fun relocationBeforeState(transaction: CivInventoryTransaction): CivInventoryApplyResult? = classifyRelocation(transaction, true)

    private fun classifyRelocation(transaction: CivInventoryTransaction, expectedBefore: Boolean = false): CivInventoryApplyResult? {
        val relocation = transaction.containerRelocation ?: return CivInventoryApplyResult.Applied
        val current = relocationCurrentCity(transaction.cityId)
            ?: return CivInventoryApplyResult.Retry(mapOf("reason" to "relocation-city-unavailable"))
        if (current.copy(revision = transaction.after.revision) != transaction.after ||
            current.building(relocation.building.id) != relocation.building)
            return CivInventoryApplyResult.Conflict(mapOf("reason" to "relocation-building-changed"))
        val world = plugin.server.getWorld(current.worldId)
            ?: return CivInventoryApplyResult.Retry(mapOf("reason" to "world-unloaded"))
        var pending = false
        for (holder in relocation.containers) {
            if ((holder.before + holder.after).filterNotNull().any { runCatching { it.stack() }.isFailure })
                return CivInventoryApplyResult.Conflict(mapOf("reason" to "invalid-relocation-item"))
            if (locked[Key(world.uid, holder.position)] != transaction.id)
                return CivInventoryApplyResult.Conflict(mapOf("reason" to "unlocked-relocation-container"))
            val source = try { containers.resolve(current, holder.position, holder.type) } catch (failure: CivsRejection) {
                return if (failure.reason == "storage-unavailable") CivInventoryApplyResult.Retry(failure.values)
                    else CivInventoryApplyResult.Conflict(failure.values)
            }
            if (!civsSameRelocationContainerState(
                world.getBlockAt(holder.position.x, holder.position.y, holder.position.z).blockData.asString, holder.blockData))
                return CivInventoryApplyResult.Conflict(mapOf("reason" to "relocation-container-state-changed"))
            for (slot in 0 until STORAGE_SLOTS) {
                val native = source.inventory.getItem(slot)
                val before = runCatching { matchesNative(native, holder.before[slot]) }.getOrDefault(false)
                val after = runCatching { matchesNative(native, holder.after[slot]) }.getOrDefault(false)
                if (expectedBefore && !before || !expectedBefore && !before && !after)
                    return CivInventoryApplyResult.Conflict(mapOf("reason" to "relocation-slot-drift", "slot" to slot.toString()))
                if ((expectedBefore || !after) && !foodCanModify(current, holder.position))
                    return CivInventoryApplyResult.Retry(mapOf("reason" to "relocation-lands-denied"))
                if (!expectedBefore && !after) pending = true
            }
        }
        return if (pending) null else CivInventoryApplyResult.Applied
    }

    private fun classifyCollected(transaction: CivInventoryTransaction, expectedBefore: Boolean = false): CivInventoryApplyResult? {
        val collected = transaction.collected ?: return CivInventoryApplyResult.Applied
        val world = plugin.server.getWorld(transaction.after.worldId)
            ?: return CivInventoryApplyResult.Retry(mapOf("reason" to "world-unloaded"))
        var pending = false
        val leafProof = collected.forestryLeafLoot
        fun leafAuthority(point: CivPosition): CivInventoryApplyResult? {
            if (leafProof == null) return null
            val current = forestryLeafCurrentCity(transaction.cityId)
                ?: return CivInventoryApplyResult.Retry(mapOf("reason" to "forestry-leaf-city-unavailable"))
            if (!CivsForestryLeafLoot.current(leafProof, current))
                return CivInventoryApplyResult.Conflict(mapOf("reason" to "forestry-leaf-owner-changed"))
            if (!foodCanModify(current, point))
                return CivInventoryApplyResult.Retry(mapOf("reason" to "forestry-leaf-lands-denied"))
            return null
        }
        for (change in collected.ground) {
            if (lockedGround[change.entityId] != transaction.id)
                return CivInventoryApplyResult.Conflict(mapOf("reason" to "unlocked-ground-item"))
            if (!citizenChunkReady(world, change.position.x shr 4, change.position.z shr 4))
                return CivInventoryApplyResult.Retry(mapOf("reason" to "ground-chunk-unloaded"))
            val entity = world.getEntity(change.entityId)
            val item = entity as? Item
            if (entity != null && (item == null || (item.location.blockX shr 4) != (change.position.x shr 4) ||
                (item.location.blockZ shr 4) != (change.position.z shr 4)))
                return CivInventoryApplyResult.Conflict(mapOf("reason" to "ground-item-moved"))
            val before = runCatching { matchesNative(item?.itemStack, change.before) }.getOrDefault(false)
            val after = runCatching { matchesNative(item?.itemStack, change.after) }.getOrDefault(false)
            if (leafProof != null && (expectedBefore || !after)) {
                leafAuthority(change.position)?.let { return it }
                val leaf = leafProof.sources.single { it.entityId == change.entityId }
                leafAuthority(leaf.leaf)?.let { return it }
                val actual = item?.let(CivsForestryLeafLoot::proof)
                if (item == null || actual != leafProof.copy(sources = listOf(leaf)) || item.owner != null || item.thrower != null ||
                    !civsGroundItemAvailable(item, collected.workerId))
                    return CivInventoryApplyResult.Conflict(mapOf("reason" to "forestry-leaf-source-changed"))
                val current = requireNotNull(forestryLeafCurrentCity(transaction.cityId))
                if (!current.contains(item.location.blockX, item.location.blockZ))
                    return CivInventoryApplyResult.Conflict(mapOf("reason" to "forestry-leaf-item-moved"))
                leafAuthority(CivPosition(item.location.blockX, item.location.blockY, item.location.blockZ))?.let { return it }
            }
            if (expectedBefore) {
                if (!before) return CivInventoryApplyResult.Conflict(mapOf("reason" to "ground-before-drift"))
            } else if (!after) {
                if (!before || item == null || !civsGroundItemAvailable(item, collected.workerId))
                    return CivInventoryApplyResult.Conflict(mapOf("reason" to "ground-item-drift"))
                pending = true
            }
        }
        for (change in collected.containers) {
            if (locked[Key(world.uid, change.container)] != transaction.id)
                return CivInventoryApplyResult.Conflict(mapOf("reason" to "unlocked-home-container"))
            if (!citizenChunkReady(world, change.container.x shr 4, change.container.z shr 4))
                return CivInventoryApplyResult.Retry(mapOf("reason" to "home-chunk-unloaded"))
            val source = try { containers.resolve(transaction.after, change.container, change.containerType) } catch (failure: CivsRejection) {
                return if (failure.reason == "storage-unavailable") CivInventoryApplyResult.Retry(failure.values)
                    else CivInventoryApplyResult.Conflict(failure.values)
            }
            val native = source.inventory.getItem(change.slot)
            val before = runCatching { matchesNative(native, change.before) }.getOrDefault(false)
            val after = runCatching { matchesNative(native, change.after) }.getOrDefault(false)
            if (leafProof != null && (expectedBefore || !after)) {
                leafAuthority(change.container)?.let { return it }
                val current = requireNotNull(forestryLeafCurrentCity(transaction.cityId))
                if (change.container !in containers.buildingPositions(current, leafProof.buildingId))
                    return CivInventoryApplyResult.Conflict(mapOf("reason" to "forestry-leaf-container-changed"))
            }
            // A recorded after-state must finish accounting. Only a still-needed native write
            // requires today's claim authority; denied recovery keeps the exact receipt locked.
            if (collected.food != null && (expectedBefore || !after) && !foodCanModify(transaction.after, change.container))
                return CivInventoryApplyResult.Retry(mapOf("reason" to "food-lands-denied"))
            if (expectedBefore) {
                if (!before) return CivInventoryApplyResult.Conflict(mapOf("reason" to "home-before-drift"))
            } else if (!after) {
                if (!before) return CivInventoryApplyResult.Conflict(mapOf("reason" to "home-slot-drift"))
                pending = true
            }
        }
        return if (pending) null else CivInventoryApplyResult.Applied
    }

    fun waitingForPlayer(transaction: CivInventoryTransaction): Boolean {
        checkMain(); requireOpen()
        return transaction.playerId()?.let { plugin.server.getPlayer(it)?.isOnline != true } == true
    }

    /** null means the exact accepted scroll is untouched; Applied means its native slot already matches the debit. */
    internal fun resurrectionDebitState(transaction: CivInventoryTransaction): CivInventoryApplyResult? {
        checkMain(); requireOpen()
        val debit = requireNotNull(transaction.resurrection)
        return classifyPlayer(debit.playerId, listOf(debit.change), plugin.server.getPlayer(debit.playerId))
    }

    private fun classifyPlayer(playerId: UUID?, changes: List<CivNativePlayerSlot>, player: Player?): CivInventoryApplyResult? {
        if (playerId == null) return CivInventoryApplyResult.Applied
        if (player == null || !player.isOnline) return CivInventoryApplyResult.Retry(mapOf("reason" to "player-offline"))
        var pending = false
        changes.forEach { change ->
            val current = player.inventory.getItem(change.slot)
            if (!matchesNative(current, change.after)) {
                if (!matchesNative(current, change.before)) return CivInventoryApplyResult.Conflict(mapOf(
                    "reason" to "player-slot-drift", "player" to playerId.toString(), "slot" to change.slot.toString(),
                    "actual" to itemDescription(current), "before" to change.before.toString(), "after" to change.after.toString()))
                pending = true
            }
        }
        return if (pending) null else CivInventoryApplyResult.Applied
    }

    private fun classifyWorld(transaction: CivInventoryTransaction, cells: Map<CivPosition, Pair<Block, BlockData>>): CivInventoryApplyResult? {
        var pending = false
        transaction.worldChanges.orEmpty().forEach { cell ->
            val (block, after) = cells.getValue(cell.position)
            val current = block.blockData
            if (!worldAfterMatches(transaction, current, after, cell)) {
                if (current.asString != cell.before) return CivInventoryApplyResult.Conflict(worldDetails(transaction, cell, "world-cell-drift") + ("actual" to current.asString))
                pending = true
            }
        }
        return if (pending) null else CivInventoryApplyResult.Applied
    }

    private fun worldAfterMatches(transaction: CivInventoryTransaction, actual: BlockData, expected: BlockData,
        cell: CivPlanBlock): Boolean = actual.asString == expected.asString ||
        transaction.upkeep != null && CivsWorld.sameConstructionState(actual, expected) ||
        transaction.autonomousVisual != null && CivsAutonomousWorksites.nativeAfterMatches(cell, actual.asString)

    private fun completedNativeState(transaction: CivInventoryTransaction, sources: Map<CivPosition, Source>, cells: Map<CivPosition, Pair<Block, BlockData>>): CivInventoryApplyResult {
        val containers = classify(transaction, sources)
        if (containers is CivInventoryApplyResult.Conflict) return containers
        val world = classifyWorld(transaction, cells)
        if (world is CivInventoryApplyResult.Conflict) return world
        val collected = classifyCollected(transaction)
        if (collected is CivInventoryApplyResult.Conflict || collected is CivInventoryApplyResult.Retry) return collected
        val relocation = classifyRelocation(transaction)
        if (relocation is CivInventoryApplyResult.Conflict || relocation is CivInventoryApplyResult.Retry) return relocation
        return if (containers == CivInventoryApplyResult.Applied && world == CivInventoryApplyResult.Applied &&
            collected == CivInventoryApplyResult.Applied && relocation == CivInventoryApplyResult.Applied) CivInventoryApplyResult.Applied
            else CivInventoryApplyResult.Retry(mapOf("reason" to "write-incomplete"))
    }

    private fun worldDetails(transaction: CivInventoryTransaction, cell: CivPlanBlock, reason: String): Map<String, String> = mapOf(
        "reason" to reason, "world" to transaction.after.worldId.toString(), "x" to cell.position.x.toString(),
        "y" to cell.position.y.toString(), "z" to cell.position.z.toString())

    private fun cargoTransferSlotState(transaction: CivInventoryTransaction, change: CivInventorySlotChange,
        expectedBefore: Boolean = false): CivInventoryApplyResult? {
        val states = transaction.cargoDropoff?.containers ?: transaction.cargoPickup?.containers
            ?: return CivInventoryApplyResult.Applied
        val source = try { containers.resolve(transaction.after, change.container, change.containerType) } catch (failure: CivsRejection) {
            return if (failure.reason == "storage-unavailable") CivInventoryApplyResult.Retry(failure.values)
                else CivInventoryApplyResult.Conflict(failure.values)
        }
        val current = source.inventory.getItem(change.slot)
        if (!expectedBefore && matches(current, change.after)) return CivInventoryApplyResult.Applied
        if (!matches(current, change.before))
            return CivInventoryApplyResult.Conflict(details(transaction.after, change, "cargo-slot-drift"))
        val nativeWorld = plugin.server.getWorld(transaction.after.worldId)
            ?: return CivInventoryApplyResult.Retry(mapOf("reason" to "world-unloaded"))
        val expected = states.firstOrNull { it.position == change.container }?.blockData
        if (expected == null || !cargoContainerStateMatches(
                nativeWorld.getBlockAt(change.container.x, change.container.y, change.container.z).blockData, expected))
            return CivInventoryApplyResult.Conflict(details(transaction.after, change, "cargo-container-state-changed"))
        if (!source.acceptsInsert || !foodCanModify(transaction.after, change.container))
            return CivInventoryApplyResult.Retry(details(transaction.after, change, "cargo-lands-denied"))
        return if (expectedBefore) CivInventoryApplyResult.Applied else null
    }

    private fun cargoContainerStateMatches(current: BlockData, expected: String): Boolean {
        val captured = try { Bukkit.createBlockData(expected) } catch (_: IllegalArgumentException) { return false }
        if (current.material != captured.material) return false
        val actual = current.clone()
        // Viewer open/close changes no holder or item authority; every other native property stays exact.
        if (actual is BarrelData && captured is BarrelData) actual.isOpen = captured.isOpen
        return actual.asString == captured.asString
    }

    private fun classifyCargoTransfer(transaction: CivInventoryTransaction, expectedBefore: Boolean = false): CivInventoryApplyResult? {
        if (transaction.cargoDropoff == null && transaction.cargoPickup == null) return CivInventoryApplyResult.Applied
        var pending = false
        for (change in transaction.changes) when (val state = cargoTransferSlotState(transaction, change, expectedBefore)) {
            is CivInventoryApplyResult.Retry -> return state
            is CivInventoryApplyResult.Conflict -> return state
            CivInventoryApplyResult.Applied -> Unit
            null -> pending = true
        }
        return if (pending) null else CivInventoryApplyResult.Applied
    }

    private fun classify(transaction: CivInventoryTransaction, sources: Map<CivPosition, Source>): CivInventoryApplyResult? {
        var pending = false
        transaction.changes.forEach { change ->
            val current = sources.getValue(change.container).inventory.getItem(change.slot)
            if (!matches(current, change.after)) {
                if (!matches(current, change.before)) return CivInventoryApplyResult.Conflict(
                    details(transaction.after, change, "slot-drift") + mapOf("actual" to itemDescription(current),
                        "before" to change.before.toString(), "after" to change.after.toString()))
                pending = true
            }
        }
        return if (pending) null else CivInventoryApplyResult.Applied
    }

    private fun checkClaim(city: Settlement) {
        checkMain()
        requireOpen()
        val claim = cityClaims[city.id]?.let(claims::get) ?: throw CivsRejection("storage-busy")
        require(claim.city == city) { "Storage preparation does not match its locked city revision" }
    }

    private fun details(city: Settlement, change: CivInventorySlotChange, reason: String): Map<String, String> = mapOf(
        "reason" to reason, "world" to city.worldId.toString(),
        "world_name" to (plugin.server.getWorld(city.worldId)?.name ?: city.worldId.toString()), "x" to change.container.x.toString(),
        "y" to change.container.y.toString(), "z" to change.container.z.toString(), "slot" to change.slot.toString(),
        "block" to change.containerType,
    )

    private fun playerLocked(playerId: UUID): Boolean = receiptBootstrap || playerId in lockedPlayers
    private fun locked(inventory: Inventory): Boolean = receiptBootstrap || exactLocked(inventory)
    private fun viewLocked(position: Key): Boolean = receiptBootstrap ||
        position in retiringControls || locked[position]?.let { claims[it]?.viewersAllowed != true } == true
    private fun viewLocked(inventory: Inventory): Boolean {
        if (receiptBootstrap) return true
        if (locked.isEmpty() && lockedPlayers.isEmpty() && retiringControls.isEmpty()) return false
        val holder = inventory.getHolder(false)
        return (holder as? Player)?.uniqueId?.let { it in lockedPlayers } == true ||
            holderPositions(holder).any(::viewLocked)
    }
    private fun exactLocked(inventory: Inventory): Boolean {
        if (locked.isEmpty() && lockedPlayers.isEmpty() && retiringControls.isEmpty()) return false
        val holder = inventory.getHolder(false)
        return (holder as? Player)?.uniqueId?.let { it in lockedPlayers } == true ||
            holderPositions(holder).any { it in locked || it in retiringControls }
    }
    private fun locked(block: Block): Boolean = receiptBootstrap && block.type.name in CIVS_STORAGE_TYPES ||
        Key(block.world.uid, CivPosition(block.x, block.y, block.z)).let { it in locked || it in retiringControls } ||
        isWorldLocked(block.world.uid, CivPosition(block.x, block.y, block.z))

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onOpen(event: InventoryOpenEvent) { if (playerLocked(event.player.uniqueId) || viewLocked(event.inventory)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onClick(event: InventoryClickEvent) { if (playerLocked(event.whoClicked.uniqueId) || locked(event.view.topInventory)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDrag(event: InventoryDragEvent) { if (playerLocked(event.whoClicked.uniqueId) || locked(event.view.topInventory)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onMove(event: InventoryMoveItemEvent) { if (locked(event.source) || locked(event.destination)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPickup(event: InventoryPickupItemEvent) {
        if (locked(event.inventory) || groundLocked(event.item.uniqueId)) event.isCancelled = true
        discardConsumed(event.item)
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBreak(event: BlockBreakEvent) { if (playerLocked(event.player.uniqueId) || locked(event.block)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPlace(event: BlockPlaceEvent) { if (playerLocked(event.player.uniqueId) || locked(event.blockPlaced)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPlayerPickup(event: EntityPickupItemEvent) {
        if (groundLocked(event.item.uniqueId) || event.entity is Player && playerLocked(event.entity.uniqueId)) event.isCancelled = true
        discardConsumed(event.item)
    }
    private fun groundLocked(id: UUID): Boolean = receiptBootstrap || id in lockedGround || id in consumedItems
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onGroundMerge(event: ItemMergeEvent) {
        if (groundLocked(event.entity.uniqueId) || groundLocked(event.target.uniqueId)) event.isCancelled = true
        discardConsumed(event.entity); discardConsumed(event.target)
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onGroundDespawn(event: ItemDespawnEvent) { if (groundLocked(event.entity.uniqueId)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onGroundTeleport(event: EntityTeleportEvent) { if (event.entity is Item && groundLocked(event.entity.uniqueId)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onGroundPortal(event: EntityPortalEvent) { if (event.entity is Item && groundLocked(event.entity.uniqueId)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPlayerDrop(event: PlayerDropItemEvent) { if (playerLocked(event.player.uniqueId)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onSwap(event: PlayerSwapHandItemsEvent) { if (playerLocked(event.player.uniqueId)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) { if (playerLocked(event.player.uniqueId)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onEntityInteract(event: PlayerInteractEntityEvent) { if (playerLocked(event.player.uniqueId)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onEntityInteractAt(event: PlayerInteractAtEntityEvent) { if (playerLocked(event.player.uniqueId)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onArmorStand(event: PlayerArmorStandManipulateEvent) { if (playerLocked(event.player.uniqueId)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onConsume(event: PlayerItemConsumeEvent) { if (playerLocked(event.player.uniqueId)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onItemDamage(event: PlayerItemDamageEvent) { if (playerLocked(event.player.uniqueId)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onCommand(event: PlayerCommandPreprocessEvent) { if (playerLocked(event.player.uniqueId)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPlayerDamage(event: EntityDamageEvent) {
        if (event.entity is Player && playerLocked(event.entity.uniqueId) || event.entity is Item && groundLocked(event.entity.uniqueId)) event.isCancelled = true
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBlockExplosion(event: BlockExplodeEvent) { event.blockList().removeIf(::locked) }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onEntityExplosion(event: EntityExplodeEvent) { event.blockList().removeIf(::locked) }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun indexPlace(event: BlockPlaceEvent) { containers.placed(event) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun indexBreak(event: BlockBreakEvent) { containers.removed(event.block) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun indexBlockExplosion(event: BlockExplodeEvent) { event.blockList().forEach(containers::removed) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun indexEntityExplosion(event: EntityExplodeEvent) { event.blockList().forEach(containers::removed) }
    @EventHandler(priority = EventPriority.MONITOR)
    fun indexChunkLoad(event: ChunkLoadEvent) { containers.chunkLoaded(event.chunk) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun indexChunkUnload(event: ChunkUnloadEvent) { containers.chunkUnloaded(event.chunk) }
    @EventHandler(priority = EventPriority.MONITOR)
    fun indexWorldLoad(event: WorldLoadEvent) { containers.worldLoaded(event.world) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun indexWorldUnload(event: WorldUnloadEvent) { containers.worldUnloaded(event.world) }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun supplyClick(event: InventoryClickEvent) { containers.supplyChanged(event.view.topInventory) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun supplyDrag(event: InventoryDragEvent) { containers.supplyChanged(event.view.topInventory) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun supplyMove(event: InventoryMoveItemEvent) { containers.supplyChanged(event.source); containers.supplyChanged(event.destination) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun supplyPickup(event: InventoryPickupItemEvent) { containers.supplyChanged(event.inventory) }

    override fun close() {
        checkMain()
        if (closed) return
        closed = true
        cancelCollectedPersistence()
        if (nativePersistenceDelegate.isInitialized()) nativePersistence.close()
        HandlerList.unregisterAll(this)
        claims.keys.toList().forEach(::unlock)
        containers.clear(); planner.clear(); residents.close()
    }
    private fun checkReady() { checkMain(); requireOpen() }
    private fun requireOpen() = check(!closed) { "CivsStorage is closed" }
    private fun checkMain() = check(Bukkit.isPrimaryThread()) { "CivsStorage requires Paper's main thread" }
    private fun reject(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
}
