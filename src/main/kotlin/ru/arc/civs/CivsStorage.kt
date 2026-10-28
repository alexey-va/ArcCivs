package ru.arc.civs

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.Chest
import org.bukkit.block.DoubleChest
import org.bukkit.block.TileState
import org.bukkit.block.data.BlockData
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
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.playerstate.NativePaperPlayerDataPersistence
import ru.arc.paper.playerstate.PaperPlayerDataPersistence
import ru.arc.paper.chunk.PaperChunkKey
import ru.arc.paper.chunk.PaperChunkTicketLease
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

data class CivPlainItem(val material: String, val amount: Int) {
    fun validated(): CivPlainItem = apply {
        require(material != null && MATERIAL.matches(material) && amount in 1..64) { "Invalid plain storage item" }
    }
    private companion object { val MATERIAL = Regex("[A-Z][A-Z0-9_]{0,63}") }
}

data class CivInventorySlotChange(
    val container: CivPosition, val containerType: String, val slot: Int,
    val before: CivPlainItem?, val after: CivPlainItem?,
)

data class CivPlayerSlotChange(val slot: Int, val before: CivPlainItem, val after: CivPlainItem?)
data class CivPlayerDebit(val playerId: UUID, val changes: List<CivPlayerSlotChange>)

data class CivNativePlayerSlot(val slot: Int, val before: CivResidentItem?, val after: CivResidentItem?)
data class CivResurrectionDebit(val playerId: UUID, val corpse: CivCorpse, val change: CivNativePlayerSlot,
    val acceptedAt: Long, val position: CivPosition, val feetY: Double) {
    fun validated(city: Settlement) {
        require(city.canManage(playerId) || city.autonomy != null)
        require(acceptedAt >= corpse.diedAt && acceptedAt < corpse.expiresAt)
        require(change.slot in 0..8 && change.before != null && change.before.material == "PAPER" && change.before.data != null)
        change.before.validated(); change.after?.validated()
        require(if (change.before.amount == 1) change.after == null
            else change.after == change.before.copy(amount = change.before.amount - 1))
        require(position.y in -4096..4096 && feetY.isFinite() && kotlin.math.floor(feetY).toInt() == position.y)
        CivsCorpses.validate(city.copy(workers = city.workers.filterNot { it.id == corpse.worker.id }, corpses = listOf(corpse)))
        require(city.corpses.orEmpty().none { it.worker.id == corpse.worker.id })
        require(city.workers.single { it.id == corpse.worker.id } == CivsRules.resurrectedWorker(city, corpse))
    }
}
data class CivResidentTransfer(val playerId: UUID, val workerId: UUID,
    val before: List<CivResidentItem?>, val changes: List<CivNativePlayerSlot>) {
    fun validated(city: Settlement) {
        require((if (city.autonomy != null) city.corpses.orEmpty().any { it.worker.id == workerId } else city.canManage(playerId)) &&
            changes.size in 1..36 && changes.map { it.slot }.distinct().size == changes.size)
        CivsEquipment.validate(before)
        val after = (city.workers.firstOrNull { it.id == workerId }
            ?: city.corpses.orEmpty().first { it.worker.id == workerId }.worker).items ?: error("Missing resident inventory")
        CivsEquipment.validate(after)
        val delta = mutableMapOf<Pair<String, String?>, Long>()
        fun count(item: CivResidentItem?, sign: Long) {
            item?.validated()?.let { delta.merge(it.material to it.data, sign * it.amount, Long::plus) }
        }
        before.forEach { count(it, -1) }; after.forEach { count(it, 1) }
        changes.forEach {
            require(it.slot in 0..35 && it.before != it.after)
            count(it.before, -1); count(it.after, 1)
        }
        require(delta.values.all { it == 0L }) { "Resident transfer must conserve exact items" }
    }
}

data class CivStoragePlan(val inventoryAfter: CivInventory, val changes: List<CivInventorySlotChange>,
    val playerDebit: CivPlayerDebit? = null, val residentTransfer: CivResidentTransfer? = null,
    val worldChanges: List<CivPlanBlock>? = null, val mine: CivMineReceipt? = null)
data class CivStorageSupplyPlan(val plan: CivStoragePlan, val result: CivsSupplyResult)
data class CivResidentTransferPlan(val plan: CivStoragePlan, val worker: CivWorker)
data class CivCorpseTransferPlan(val worker: CivWorker, val transfer: CivResidentTransfer?)

/** Exact slot receipt; the service persists this before applying any native item change. */
data class CivInventoryTransaction(
    val id: UUID, val cityId: UUID, val beforeRevision: Long,
    val after: Settlement, val changes: List<CivInventorySlotChange>,
    val playerDebit: CivPlayerDebit? = null,
    val residentTransfer: CivResidentTransfer? = null,
    val worldChanges: List<CivPlanBlock>? = null,
    val resurrection: CivResurrectionDebit? = null,
    val collected: CivCollectedTransfer? = null,
    val forestry: CivForestryReceipt? = null,
    val upkeep: CivRoadUpkeepReceipt? = null,
    val autonomousVisual: CivAutonomousVisualReceipt? = null,
    val mine: CivMineReceipt? = null,
) {
    fun validated(): CivInventoryTransaction = apply {
        require(id != null && cityId != null && after != null && changes != null)
        require(beforeRevision in 0 until Long.MAX_VALUE && after.id == cityId && after.revision == beforeRevision + 1)
        CivsRules.validate(after)
        require(after.inventory != null || resurrection != null || collected != null || forestry != null || upkeep != null ||
            autonomousVisual != null || after.autonomy != null && residentTransfer != null)
        require(listOfNotNull(playerDebit, residentTransfer, resurrection, collected, forestry, upkeep, autonomousVisual, mine).size <= 1)
        collected?.let {
            require(changes.isEmpty() && worldChanges.orEmpty().isEmpty())
            it.validated(after)
        }
        resurrection?.let {
            require(changes.isEmpty() && worldChanges.orEmpty().isEmpty())
            it.validated(after)
        }
        if (after.inventory == null) require(changes.isEmpty() && (forestry != null || upkeep != null || autonomousVisual != null || worldChanges.orEmpty().isEmpty()))
        forestry?.let {
            require(changes.isEmpty() && worldChanges.orEmpty() == it.work.changes)
            it.validated(after)
        }
        upkeep?.let {
            require(changes.isEmpty() && worldChanges.orEmpty() == it.work.changes)
            it.validated(after)
        }
        autonomousVisual?.let {
            require(changes.isEmpty() && worldChanges.orEmpty() == it.work.changes)
            it.validated(after)
        }
        mine?.let {
            require(after.inventory != null && after.autonomy == null && worldChanges.orEmpty() == it.work.changes &&
                it.work.forestry == null && it.work.upkeep == null)
            it.validated(after)
        }
        residentTransfer?.validated(after)
        require(worldChanges.orEmpty().size <= 128 && worldChanges.orEmpty().map { it.position }.distinct().size == worldChanges.orEmpty().size)
        worldChanges.orEmpty().forEach { cell ->
            require(after.contains(cell.position.x, cell.position.z) && cell.position.y in -4_096..4_096 &&
                cell.before.length in 1..2_048 && cell.after.length in 1..2_048 && cell.before != cell.after)
        }
        require(changes.size <= MAX_INVENTORY_SLOT_CHANGES)
        require(changes.map { it.container to it.slot }.distinct().size == changes.size)
        changes.forEach { change ->
            require(change != null && change.container != null && change.containerType in STORAGE_TYPES && change.slot in 0 until STORAGE_SLOTS)
            require(storagePositionAllowed(after, change.container, change.containerType))
            require(change.before != change.after)
            change.before?.validated()
            change.after?.validated()
        }
        playerDebit?.let { debit ->
            require(debit.playerId != null && after.canManage(debit.playerId) && debit.changes != null)
            require(after.inventory?.mode == CivStorageMode.PHYSICAL)
            require(debit.changes.size in 1..PLAYER_STORAGE_SLOTS && debit.changes.map { it.slot }.distinct().size == debit.changes.size)
            val removed = linkedMapOf<String, Long>()
            debit.changes.forEach { change ->
                require(change != null && change.slot in 0 until PLAYER_STORAGE_SLOTS && change.before != null)
                change.before.validated(); change.after?.validated()
                require(change.after == null || change.after.material == change.before.material && change.after.amount < change.before.amount)
                removed.merge(change.before.material, (change.before.amount - (change.after?.amount ?: 0)).toLong(), Math::addExact)
            }
            val added = linkedMapOf<String, Long>()
            changes.forEach { change ->
                val item = requireNotNull(change.after)
                require(change.before == null || change.before.material == item.material && change.before.amount < item.amount)
                added.merge(item.material, (item.amount - (change.before?.amount ?: 0)).toLong(), Math::addExact)
            }
            require(removed == added) { "Player transfer does not conserve materials" }
        }
    }
}

/** Detached before-state and action proof cross preparation and asynchronous journal boundaries. */
internal fun CivMineReceipt.detached(): CivMineReceipt = copy(
    buildingBefore = buildingBefore.copy(transit = buildingBefore.transit?.toList()),
    planBefore = planBefore?.let(CivsMineProgress::detached),
    work = work.copy(changes = work.changes.toList(), inputs = work.inputs.toMap(), outputs = work.outputs.toMap(),
        equipmentAfter = work.equipmentAfter?.toList(), miningSections = work.miningSections?.toMap(),
        miningAccessSections = work.miningAccessSections?.toMap(), miningDeferredSeals = work.miningDeferredSeals?.toSet()),
)

internal fun CivInventoryTransaction.playerId(): UUID? = playerDebit?.playerId ?: residentTransfer?.playerId ?: resurrection?.playerId

sealed interface CivInventoryApplyResult {
    data object Applied : CivInventoryApplyResult
    data class Retry(val values: Map<String, String>) : CivInventoryApplyResult
    data class Conflict(val values: Map<String, String>) : CivInventoryApplyResult
}

internal const val MAX_INVENTORY_SLOT_CHANGES = 65_536
private const val MATERIALIZATION_SLOT_CHANGES = 1_024
private const val MATERIALIZATION_CONTAINERS = 128
private const val BACKGROUND_DEBIT_CONTAINERS = 128
private const val STORAGE_SLOTS = 27
private const val PLAYER_STORAGE_SLOTS = 36
internal val CIVS_STORAGE_TYPES = setOf("CHEST", "TRAPPED_CHEST", "BARREL")
private val STORAGE_TYPES = CIVS_STORAGE_TYPES

/** Recovery authorizes durable coordinates against geometry, independently of loaded chunks or the index. */
private fun storagePositionAllowed(city: Settlement, position: CivPosition, type: String): Boolean =
    type in STORAGE_TYPES && city.contains(position.x, position.z) && position.y in -4_096..4_096 &&
        kotlin.math.abs(position.x.toLong()) <= 30_000_000 && kotlin.math.abs(position.z.toLong()) <= 30_000_000 &&
        (if (city.buildings.any { it.control == position }) type == "BARREL" else
            position in city.inventory?.containers.orEmpty() || city.buildings.any { it.bounds.contains(position.x, position.y, position.z) })

/** Bounded main-thread storage. No IO, chunk loads, item drops or native mutation during planning. */
class CivsStorage(private val plugin: Plugin,
    private val playerDataPersistence: PaperPlayerDataPersistence = NativePaperPlayerDataPersistence,
    tileEntities: (Chunk) -> Array<BlockState> = { it.tileEntities }) : Listener, AutoCloseable {
    private data class Key(val worldId: UUID, val position: CivPosition)
    private data class Claim(val city: Settlement, val positions: Map<CivPosition, String?>, val playerId: UUID?,
        val worldPositions: Set<CivPosition> = emptySet(), val groundItems: Set<UUID> = emptySet())
    private data class Source(val position: CivPosition, val type: String, val inventory: Inventory)
    private data class Snapshot(val source: Source, val before: List<ItemStack?>, val after: MutableList<ItemStack?>)
    private class DebitSearch(val worldId: UUID, val owner: UUID, val required: Map<String, Long>, val buildings: Set<UUID>?) {
        var positions: Map<CivPosition, String?> = emptyMap()
        var ordered: List<CivPosition> = emptyList()
        var cursor = 0
        var candidates: Set<CivPosition> = emptySet()
    }
    private val claims = mutableMapOf<UUID, Claim>()
    private val cityClaims = mutableMapOf<UUID, UUID>()
    private val locked = mutableMapOf<Key, UUID>()
    private val lockedPlayers = mutableMapOf<UUID, UUID>()
    private val lockedWorld = mutableMapOf<Key, UUID>()
    private val lockedGround = mutableMapOf<UUID, UUID>()
    private val homeCursors = mutableMapOf<UUID, Int>()
    internal var residentFoods: CivsResidentFoods = CivsResidentFoods.BREAD_ONLY
    internal var foodCanModify: (Settlement, CivPosition) -> Boolean = { _, _ -> false }
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
    private val pendingPersistence = mutableSetOf<CompletableFuture<Unit>>()
    private val persistenceTasks = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    private val index = CivsContainerIndex(plugin.server, tileEntities)
    private val supplyCaptures = CivsSupplyCaptures(index, { city ->
        val positions = index.positions(city)
        city.buildings.associate { it.id to selectBuildingPositions(city, it.id, positions) }
    }, ::captureSupplyContainer)
    private val materializationCursors = mutableMapOf<UUID, Int>()
    private val autonomousCaptures = mutableMapOf<UUID, CivSupplyCaptureResult>()
    private val debitSearches = mutableMapOf<UUID, DebitSearch>()
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
        val containers = locked.entries.filter { cityId == null || it.value in claimIds }
        val worldCells = lockedWorld.entries.filter { cityId == null || it.value in claimIds }
        val players = lockedPlayers.entries.filter { cityId == null || it.value in claimIds }
        return sample(selected) { (id, claim) -> mapOf("receipt" to id.toString(), "city" to claim.city.id.toString(),
            "revision" to claim.city.revision, "world" to claim.city.worldId.toString(),
            "player" to claim.playerId?.toString(), "sourceMembershipContainers" to claim.positions.size,
            "worldPositions" to claim.worldPositions.size,
            "lockedContainers" to containers.count { it.value == id }, "lockedWorldCells" to worldCells.count { it.value == id })
        } + mapOf("source" to "owner-cache", "closed" to closed, "receiptBootstrap" to receiptBootstrap,
            "consumedGroundItems" to consumedItems.size, "consumedGroundLimit" to CivConsumedItemIndex.MAX_ITEMS,
            "lockedContainers" to sample(containers) { (key, receipt) -> mapOf("world" to key.worldId.toString(),
                "position" to point(key.position), "receipt" to receipt.toString()) },
            "lockedWorldCells" to sample(worldCells) { (key, receipt) -> mapOf("world" to key.worldId.toString(),
                "position" to point(key.position), "receipt" to receipt.toString()) },
            "lockedPlayers" to sample(players) { mapOf("player" to it.key.toString(), "receipt" to it.value.toString()) },
            "debitSearches" to sample(debitSearches.entries.filter { cityId == null || it.key == cityId }) { (id, search) ->
                mapOf("city" to id.toString(), "world" to search.worldId.toString(), "cursor" to search.cursor,
                    "positions" to search.positions.size, "ordered" to search.ordered.size,
                    "candidates" to search.candidates.size, "buildings" to search.buildings?.size,
                    "required" to sample(search.required.entries) { mapOf("material" to it.key, "amount" to it.value) }) },
            "materializationCursors" to sample(materializationCursors.entries.filter { cityId == null || it.key == cityId }) {
                mapOf("city" to it.key.toString(), "cursor" to it.value) },
            "containerIndex" to index.debug(cityId), "supplyCaptures" to supplyCaptures.debug(cityId))
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
        checkMain(); requireOpen()
        materializationCursors.clear()
        debitSearches.clear()
        supplyCaptures.clear()
        autonomousCaptures.clear()
        index.replace(cities)
    }
    fun updateCity(city: Settlement) { checkMain(); requireOpen(); index.update(city); supplyCaptures.update(city) }
    fun removeCity(cityId: UUID) {
        checkMain(); requireOpen()
        materializationCursors.remove(cityId)
        debitSearches.remove(cityId)
        supplyCaptures.remove(cityId)
        autonomousCaptures.remove(cityId)
        index.remove(cityId)
    }
    fun refreshBuilding(city: Settlement, building: CivBuilding? = null) {
        checkMain(); requireOpen()
        supplyCaptures.invalidate(city.id, building?.let { setOf(it.id) })
        index.refresh(city, building)
    }

    internal fun startSupplyCapture(city: Settlement): CivsSupplyCapture {
        checkMain(); requireOpen()
        return supplyCaptures.start(city)
    }

    internal fun supplyCaptureCurrent(city: Settlement, result: CivSupplyCaptureResult, buildingIds: Set<UUID>? = null): Boolean {
        checkMain(); requireOpen()
        return supplyCaptures.current(city, result, buildingIds)
    }

    internal fun supplyRevision(cityId: UUID): Long { checkMain(); requireOpen(); return supplyCaptures.revision(cityId) }

    internal fun rememberAutonomousCapacity(city: Settlement, result: CivSupplyCaptureResult) {
        checkMain(); requireOpen()
        if (city.autonomy?.storage == CivsAutonomousStorage.stores(result) && supplyCaptureCurrent(city, result))
            autonomousCaptures[city.id] = result
        else autonomousCaptures.remove(city.id)
    }

    internal fun autonomousCapacityCurrent(city: Settlement, buildingId: UUID? = null): Boolean {
        checkMain(); requireOpen()
        if (!CivsAutonomousStorage.observed(city)) return true
        val result = autonomousCaptures[city.id] ?: return false
        return city.autonomy?.storage == CivsAutonomousStorage.stores(result) &&
            supplyCaptureCurrent(city, result, buildingId?.let(::setOf))
    }

    internal fun invalidateSupply(cityId: UUID, buildingIds: Set<UUID>? = null) {
        checkMain(); requireOpen(); supplyCaptures.invalidate(cityId, buildingIds)
    }

    /** Report native viewers within the settlement's current source membership. */
    fun hasViewers(city: Settlement): Boolean {
        checkMain(); requireOpen()
        val positions = cityClaims[city.id]?.let(claims::get)?.positions ?: index.positions(city)
        return plugin.server.onlinePlayers.any { player ->
            holderPositions(player.openInventory.topInventory.holder).any { it.worldId == city.worldId && it.position in positions }
        }
    }

    /** Acquire before preparing a receipt; keep it through journal, city commit and journal deletion. */
    fun lock(city: Settlement, transactionId: UUID, player: Player? = null) {
        checkMain()
        requireOpen()
        if (player != null) reject(!player.isOnline || !city.canManage(player.uniqueId), "not-authorized")
        lock(city, transactionId, index.positions(city), player?.uniqueId)
    }

    /** Reserve membership for synchronous planning; seal keeps only the receipt's native containers locked. */
    fun reserve(city: Settlement, transactionId: UUID, player: Player? = null) {
        checkMain(); requireOpen()
        if (player != null) reject(!player.isOnline || !(if (city.autonomy != null) player.hasPermission("arccivs.admin")
            else city.canManage(player.uniqueId)), "not-authorized")
        lock(city, transactionId, index.positions(city), player?.uniqueId, closeViewers = false)
    }

    fun seal(transaction: CivInventoryTransaction, keepViewersOpen: Boolean = false) {
        checkMain(); requireOpen(); transaction.validated()
        val claim = claims[transaction.id] ?: throw CivsRejection("storage-busy")
        val positions = transaction.changes.associate { it.container to it.containerType } +
            transaction.collected?.containers.orEmpty().associate { it.container to it.containerType }
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
        transaction.forestry?.let { require(it.before(transaction.after) == claim.city) }
        transaction.upkeep?.let { require(it.before(transaction.after) == claim.city) }
        transaction.mine?.let { receipt ->
            val key = receipt.buildingBefore.id.toString()
            require(receipt.buildingBefore == claim.city.building(receipt.buildingBefore.id) &&
                receipt.planBefore == claim.city.minePlans?.get(key) && receipt.legacyBefore == claim.city.mineProgress?.get(key))
        }
        claim.positions.keys.filterNot { it in positions }.forEach {
            locked.remove(Key(claim.city.worldId, it), transaction.id)
        }
        reject(worldPositions.any { lockedWorld[Key(claim.city.worldId, it)]?.let { id -> id != transaction.id } == true }, "storage-busy")
        reject(groundItems.any { lockedGround[it]?.let { id -> id != transaction.id } == true }, "storage-busy")
        claims[transaction.id] = claim.copy(worldPositions = worldPositions, groundItems = groundItems)
        worldPositions.forEach { lockedWorld[Key(claim.city.worldId, it)] = transaction.id }
        groundItems.forEach { lockedGround[it] = transaction.id }
        // Retain full frozen source membership for stock reads; native locks cover only receipt coordinates.
        if (!keepViewersOpen) closeViewers(transaction.id, claim.playerId)
    }

    /** Exact receipt coordinates remain locked even if their chunks or current index entries are absent. */
    fun lock(transaction: CivInventoryTransaction) {
        checkMain(); requireOpen()
        transaction.validated()
        val positions = transaction.changes.associate { it.container to it.containerType } +
            transaction.collected?.containers.orEmpty().associate { it.container to it.containerType }
        lock(transaction.after, transaction.id, positions, transaction.playerId(),
            transaction.worldChanges.orEmpty().mapTo(mutableSetOf(), CivPlanBlock::position),
            transaction.collected?.ground.orEmpty().mapTo(mutableSetOf(), CivGroundItemChange::entityId))
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
        claims[transactionId] = Claim(city, positions, playerId, worldPositions, groundItems)
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

    /** Exact accepted world cells; independent of the native-container index and unloaded chunks. */
    fun isWorldLocked(worldId: UUID, position: CivPosition): Boolean = Key(worldId, position) in lockedWorld

    internal fun prepareGroundPickup(city: Settlement, workerId: UUID, entityIds: List<UUID>, feet: org.bukkit.Location): CivCollectedPlan {
        checkClaim(city)
        require(entityIds.size in 1..8 && entityIds.distinct().size == entityIds.size)
        val worker = city.workers.singleOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        reject(worker.cargo != null || CivsResidents.isChild(worker), "stale-delivery")
        val world = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("storage-unavailable")
        var updated = worker
        val changes = mutableListOf<CivGroundItemChange>()
        for (id in entityIds) {
            val item = world.getEntity(id) as? Item ?: throw CivsRejection("stale-delivery")
            reject(receiptBootstrap || discardConsumed(item), "ground-item-consumed")
            reject(!city.contains(item.location.blockX, item.location.blockZ) || !civsCanCollect(feet, item, workerId), "stale-delivery")
            val before = requireNotNull(CivResidentItem.of(item.itemStack))
            val (next, moved) = CivsEquipment.collectGround(updated, before)
            // Leave the whole native Item unchanged until its complete stack fits. There is no moving remainder to journal.
            if (moved != before.amount) continue
            updated = next
            changes += CivGroundItemChange(id, CivPosition(item.location.blockX, item.location.blockY, item.location.blockZ),
                before, null)
        }
        return CivCollectedPlan(updated, changes.takeIf { it.isNotEmpty() }?.let {
            CivCollectedTransfer(workerId, CivsEquipment.slots(worker), ground = it)
        })
    }

    /** Cached membership limits discovery to four real holders in this resident's own home. */
    private fun homeSources(city: Settlement, worker: CivWorker, destination: CivPosition? = null): Sequence<Source> {
        val home = civsCollectionHome(city, worker) ?: return emptySequence()
        val positions = buildingPositions(city, home.id)
        val ordered = linkedMapOf<CivPosition, String?>()
        destination?.takeIf { it in positions }?.let { ordered[it] = positions[it] }
        val entries = positions.entries.toList()
        val cursor = (homeCursors[worker.id] ?: 0).takeIf { it in entries.indices } ?: 0
        for (entry in entries.drop(cursor) + entries.take(cursor)) ordered[entry.key] = entry.value
        if (destination == null && entries.isNotEmpty()) homeCursors[worker.id] = (cursor + 4) % entries.size
        return resolveSources(city, ordered.asSequence().take(4).map { it.key to it.value })
    }

    internal fun homeDestination(city: Settlement, worker: CivWorker, reserve: Map<String, Long>): CivPosition? {
        checkMain(); requireOpen()
        if (worker.hasCollectedItems != true || worker.cargo != null || CivsResidents.isChild(worker)) return null
        val surplus = CivsEquipment.homeSurplus(worker, reserve)
        if (surplus.isEmpty()) return null
        val home = civsCollectionHome(city, worker) ?: return null
        for (source in homeSources(city, worker)) {
            val world = plugin.server.getWorld(city.worldId) ?: return null
            if (!citizenChunkReady(world, source.position.x shr 4, source.position.z shr 4)) continue
            val slots = source.inventory.storageContents
            if (surplus.any { (index, _) ->
                val item = requireNotNull(CivsEquipment.slots(worker)[index]).stack()
                slots.any { native -> isEmpty(native) || native != null && native.isSimilar(item) && native.amount < native.maxStackSize }
            } && citizenCollectedHomeTarget(city, home, world, source.position) != null) return source.position
        }
        return null
    }

    /** At most sixteen nearest indexed holders; never load chunks or invent a stock entry. */
    private fun foodSources(city: Settlement, worker: CivWorker, from: org.bukkit.Location,
        reserve: Boolean = false): Sequence<Pair<CivBuilding, Source>> {
        val positions = city.buildings.filter { CivsResidentFood.eligible(city, worker, it) &&
            (!reserve || CivsResidentFood.reserveBuilding(city, it)) }.flatMap { building ->
            buildingPositions(city, building.id).map { (point, type) -> Triple(building, point, type) }
        }.distinctBy { it.second }.sortedWith(compareBy<Triple<CivBuilding, CivPosition, String?>> { (building, _, _) ->
            if (!reserve) 0 else when (building.type) { BuildingType.TAVERN -> 0; BuildingType.WAREHOUSE -> 1; else -> 2 }
        }.thenBy { (_, point, _) ->
            val dx = point.x + .5 - from.x; val dy = point.y - from.y; val dz = point.z + .5 - from.z
            dx * dx + dy * dy + dz * dz
        }).take(16)
        return positions.asSequence().mapNotNull { (building, point, type) ->
            if (!foodCanModify(city, point)) null
            else resolveSources(city, sequenceOf(point to type)).firstOrNull()?.let { building to it }
        }
    }

    internal fun residentFoodSource(city: Settlement, worker: CivWorker, feet: org.bukkit.Location): CivResidentFoodSource? {
        checkMain(); requireOpen()
        if (city.inventory?.mode == CivStorageMode.VIRTUAL || feet.world?.uid != city.worldId ||
            CivsResidents.hunger(worker) > 14 || residentFoods.carried(city, worker) != null) return null
        for ((building, source) in foodSources(city, worker, feet)) {
            val material = source.inventory.storageContents.asSequence().mapNotNull(::plain).firstOrNull {
                residentFoods.mayPickUp(city, worker, it.material) &&
                    CivsEquipment.supplyRoom(worker, Material.valueOf(it.material)) > 0
            }?.material ?: continue
            val world = plugin.server.getWorld(city.worldId) ?: return null
            val target = citizenCollectedHomeTarget(city, building, world, source.position) ?: continue
            return CivResidentFoodSource(building.id, source.position, target, material)
        }
        return null
    }

    /** An existing Auto FOOD unit becomes one ordinary bread only in this exact native receipt. */
    internal fun prepareFoodMaterialization(city: Settlement, workerId: UUID, feet: org.bukkit.Location): CivCollectedPlan {
        checkClaim(city)
        val worker = city.workers.single { it.id == workerId }
        reject(city.autonomy?.paused != false || city.inventory != null || city.stock.food <= 0 ||
            feet.world?.uid != city.worldId, "resident-food-missing")
        for ((building, source) in foodSources(city, worker, feet, reserve = true)) {
            val world = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("storage-unavailable")
            if (citizenCollectedHomeTarget(city, building, world, source.position) == null) continue
            val original = source.inventory.storageContents.map(CivResidentItem::of)
            val count = original.filterNotNull().filter { it.material == "BREAD" && it.data == null }.sumOf { it.amount }
            if (count >= CivsResidentFood.BATCH) return CivCollectedPlan(worker, null)
            val slot = original.indexOfFirst { it == null || it.material == "BREAD" && it.data == null && it.amount < 64 }
            if (slot < 0) continue
            val moved = minOf(CivsResidentFood.BATCH - count, city.stock.food.toInt().coerceAtMost(CivsResidentFood.BATCH))
            val before = original[slot]
            val after = CivResidentItem("BREAD", (before?.amount ?: 0) + moved)
            val change = CivNativeContainerChange(source.position, source.type, slot, before, after)
            return CivCollectedPlan(worker, CivCollectedTransfer(worker.id, CivsEquipment.slots(worker),
                containers = listOf(change), food = CivResidentFoodReceipt(building.id, city.stock, moved)),
                city.stock.copy(food = city.stock.food - moved))
        }
        return CivCollectedPlan(worker, null)
    }

    internal fun prepareFoodPickup(city: Settlement, workerId: UUID, target: CivResidentFoodSource,
        feet: org.bukkit.Location): CivCollectedPlan {
        checkClaim(city)
        val worker = city.workers.single { it.id == workerId }
        val building = city.building(target.buildingId) ?: throw CivsRejection("building-not-found")
        reject(!CivsResidentFood.eligible(city, worker, building) ||
            target.container !in buildingPositions(city, building.id) || !civsCanReachContainer(feet, target.container), "worker-too-far")
        reject(!foodCanModify(city, target.container), "lands-denied")
        reject(!residentFoods.mayPickUp(city, worker, target.material) || residentFoods.carried(city, worker) != null ||
            CivsResidents.hunger(worker) > 14 || worker.cargo != null,
            "resident-food-missing")
        val source = resolve(city, target.container, buildingPositions(city, building.id)[target.container])
        var remaining = minOf(CivsResidentFood.BATCH, CivsEquipment.supplyRoom(worker, Material.valueOf(target.material)))
        val changes = mutableListOf<CivNativeContainerChange>()
        for ((slot, stack) in source.inventory.storageContents.withIndex()) {
            val before = plain(stack)?.takeIf { it.material == target.material } ?: continue
            val moved = minOf(remaining, before.amount)
            if (moved <= 0) break
            changes += CivNativeContainerChange(source.position, source.type, slot, CivResidentItem(target.material, before.amount),
                (before.amount - moved).takeIf { it > 0 }?.let { CivResidentItem(target.material, it) })
            remaining -= moved
        }
        reject(changes.isEmpty(), "resident-food-missing")
        val count = changes.sumOf { (it.before?.amount ?: 0) - (it.after?.amount ?: 0) }
        val supplied = CivsEquipment.collectSupplies(worker, mapOf(target.material to count.toLong()))
        return CivCollectedPlan(supplied, CivCollectedTransfer(worker.id, CivsEquipment.slots(worker),
            containers = changes, food = CivResidentFoodReceipt(building.id, city.stock, material = target.material)))
    }

    internal fun prepareHomeDeposit(city: Settlement, workerId: UUID, destination: CivPosition,
        feet: org.bukkit.Location, reserve: Map<String, Long>): CivCollectedPlan {
        checkClaim(city)
        val worker = city.workers.singleOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        reject(worker.hasCollectedItems != true || worker.cargo != null || CivsResidents.isChild(worker), "stale-delivery")
        val home = civsCollectionHome(city, worker) ?: throw CivsRejection("building-not-found")
        reject(destination !in buildingPositions(city, home.id) || !civsCanReachContainer(feet, destination), "worker-too-far")
        val snapshots = homeSources(city, worker, destination).filter { civsCanReachContainer(feet, it.position) }
            .map { source -> source to source.inventory.storageContents.map(CivResidentItem::of).toMutableList() }.toList()
        val originals = snapshots.associate { (source, slots) -> source.position to slots.toList() }
        val items = CivsEquipment.slots(worker).toMutableList()
        for ((bagSlot, available) in CivsEquipment.homeSurplus(worker, reserve)) {
            val item = requireNotNull(items[bagSlot])
            val maximum = item.stack().maxStackSize
            var remaining = available
            for ((_, slots) in snapshots) for (empty in listOf(false, true)) for (slot in slots.indices) {
                val before = slots[slot]
                if (remaining == 0 || (before == null) != empty || before != null &&
                    (before.material != item.material || before.data != item.data)) continue
                val moved = minOf(remaining, maximum - (before?.amount ?: 0))
                if (moved <= 0) continue
                slots[slot] = item.copy(amount = (before?.amount ?: 0) + moved)
                remaining -= moved
            }
            items[bagSlot] = item.copy(amount = item.amount - (available - remaining)).takeIf { it.amount > 0 }
        }
        val updated = CivsEquipment.replace(worker, items).let {
            it.copy(hasCollectedItems = CivsEquipment.homeSurplus(it, reserve).isNotEmpty())
        }
        val changes = snapshots.flatMap { (source, slots) -> slots.indices.mapNotNull { slot ->
            val before = originals.getValue(source.position)[slot]
            if (before == slots[slot]) null else CivNativeContainerChange(source.position, source.type, slot, before, slots[slot])
        } }
        return CivCollectedPlan(updated, changes.takeIf { it.isNotEmpty() }?.let {
            CivCollectedTransfer(workerId, CivsEquipment.slots(worker), homeId = home.id, containers = it)
        })
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

    fun available(city: Settlement): Map<String, Long> {
        checkMain()
        requireOpen()
        val inventory = requireNotNull(city.inventory).validated()
        val totals = mutableMapOf<String, Long>()
        if (inventory.mode == CivStorageMode.PHYSICAL) sources(city).forEach { source ->
            source.inventory.storageContents.forEach { stack -> plain(stack)?.let { totals.merge(it.material, it.amount.toLong(), Math::addExact) } }
        } else {
            totals.putAll(inventory.materials)
            inventory.buildingMaterials.orEmpty().values.forEach { warehouse ->
                warehouse.forEach { (material, count) -> totals.merge(material, count, Math::addExact) }
            }
        }
        return totals.toSortedMap()
    }

    /** Only this building's buffer and native holders inside its own completed footprint. */
    fun buildingAvailable(city: Settlement, buildingId: UUID): Map<String, Long> {
        checkMain(); requireOpen()
        val inventory = requireNotNull(city.inventory).validated()
        if (city.building(buildingId) == null) throw CivsRejection("building-not-found")
        if (inventory.mode == CivStorageMode.VIRTUAL) return inventory.buildingStock(buildingId).toSortedMap()
        val totals = mutableMapOf<String, Long>()
        localSources(city, buildingId).forEach { source -> source.inventory.storageContents.forEach { item ->
            plain(item)?.let { totals.merge(it.material, it.amount.toLong(), Math::addExact) }
        } }
        return totals.toSortedMap()
    }

    fun constructionAvailable(city: Settlement, buildingId: UUID): Map<String, Long> {
        checkMain(); requireOpen()
        if (city.inventory == null) throw CivsRejection("materials-required")
        val totals = mutableMapOf<String, Long>()
        CivsRules.constructionStores(city, buildingId).forEach { building ->
            buildingAvailable(city, building.id).forEach { (material, count) -> totals.merge(material, count, Math::addExact) }
        }
        CivsRules.constructionWorkers(city, buildingId).forEach { worker ->
            CivsEquipment.slots(worker).filterNotNull().filter { it.data == null }.forEach {
                totals.merge(it.material, it.amount.toLong(), Math::addExact)
            }
        }
        return totals.toSortedMap()
    }

    fun prepareDebit(city: Settlement, required: Map<String, Long>): CivStoragePlan =
        prepareDebit(city, required, null, null)

    /**
     * Small construction rows search at most 128 containers, including rechecked candidates.
     * Null keeps the order waiting: no native changes or partial receipt. Only coordinates survive
     * between attempts; each accepted slot is freshly captured under the current transaction lock.
     * An explicit building reads its local buffer; the omitted target preserves the global search.
     */
    fun prepareDebitBackground(city: Settlement, required: Map<String, Long>, buildingId: UUID? = null): CivStoragePlan? {
        checkClaim(city)
        validateAmounts(required)
        if (buildingId != null) {
            debitSearches.remove(city.id)
            return try { prepareBuildingDebit(city, buildingId, required) } catch (failure: CivsRejection) {
                if (failure.reason == "materials-missing") null else throw failure
            }
        }
        if (city.inventory?.mode != CivStorageMode.PHYSICAL || required.isEmpty()) {
            debitSearches.remove(city.id)
            return try { prepareDebit(city, required) } catch (failure: CivsRejection) {
                if (failure.reason == "materials-missing") null else throw failure
            }
        }
        return prepareScopedDebitBackground(city, required, null)
    }

    fun prepareConstructionDebitBackground(city: Settlement, required: Map<String, Long>, buildingId: UUID): CivStoragePlan? {
        checkClaim(city)
        validateAmounts(required)
        val stores = CivsRules.constructionStores(city, buildingId).map(CivBuilding::id).toSet()
        if (city.inventory?.mode == CivStorageMode.VIRTUAL) return try {
            prepareDebit(city, required, null, null, buildingIds = stores)
        } catch (failure: CivsRejection) {
            if (failure.reason == "materials-missing") null else throw failure
        }
        if (city.inventory == null) throw CivsRejection("materials-required")
        return prepareScopedDebitBackground(city, required, stores)
    }

    private fun prepareScopedDebitBackground(city: Settlement, required: Map<String, Long>, buildings: Set<UUID>?): CivStoragePlan? {
        // A homogeneous row has at most 16 blocks; eight snow layers are the largest item count.
        require(required.values.sum() <= BACKGROUND_DEBIT_CONTAINERS) { "Background debit exceeds one construction row" }
        val search = debitSearches[city.id]?.takeIf {
            it.worldId == city.worldId && it.owner == city.owner && it.required == required && it.buildings == buildings
        } ?: DebitSearch(city.worldId, city.owner, required.toMap(), buildings).also { debitSearches[city.id] = it }
        val positions = if (buildings == null) claims.getValue(cityClaims.getValue(city.id)).positions else
            buildings.flatMap { buildingPositions(city, it).entries }.associate { it.key to it.value }
        if (search.positions != positions) {
            search.positions = positions
            search.ordered = positions.keys.toList()
            search.cursor = if (positions.isEmpty()) 0 else Math.floorMod(search.cursor, positions.size)
        }
        val candidates = linkedSetOf<CivPosition>()
        val selected = sequence {
            val visited = hashSetOf<CivPosition>()
            for (position in search.candidates) if (position in positions && visited.add(position)) yield(position)
            var examined = 0
            while (examined < search.ordered.size) {
                val position = search.ordered[search.cursor]
                search.cursor = (search.cursor + 1) % search.ordered.size
                examined++
                if (visited.add(position)) yield(position)
            }
        }.take(BACKGROUND_DEBIT_CONTAINERS).mapNotNull { position ->
            try { resolve(city, position, positions[position]) } catch (failure: CivsRejection) {
                if (failure.reason == "storage-unavailable") null else throw failure
            }
        }
        return try {
            prepareDebit(city, required, selected, candidates::add).also { debitSearches.remove(city.id) }
        } catch (failure: CivsRejection) {
            if (failure.reason != "materials-missing") throw failure
            search.candidates = candidates
            null
        }
    }

    private fun prepareDebit(city: Settlement, required: Map<String, Long>,
        physicalSources: Sequence<Source>?, candidate: ((CivPosition) -> Boolean)?, buildingId: UUID? = null,
        buildingIds: Set<UUID>? = buildingId?.let(::setOf),
    ): CivStoragePlan {
        checkClaim(city)
        validateAmounts(required)
        val inventory = requireNotNull(city.inventory)
        val virtual = inventory.materials.toMutableMap()
        val warehouses = warehouses(inventory)
        val snapshots = mutableListOf<Snapshot>()
        val missing = required.toSortedMap()
        if (inventory.mode == CivStorageMode.PHYSICAL) {
            debit((physicalSources ?: sources(city)).map { snapshot(it).also(snapshots::add) }, missing, candidate)
        } else {
            required.toSortedMap().forEach { (material, requested) ->
                var remaining = requested
                val taken = if (buildingIds == null) minOf(remaining, virtual[material] ?: 0) else 0L
                putAmount(virtual, material, (virtual[material] ?: 0) - taken)
                remaining -= taken
                val selected = if (buildingIds == null) warehouses.toSortedMap().values
                    else buildingIds.map { warehouses.getOrPut(it.toString(), ::linkedMapOf) }
                selected.forEach { warehouse ->
                    val amount = minOf(remaining, warehouse[material] ?: 0)
                    putAmount(warehouse, material, (warehouse[material] ?: 0) - amount)
                    remaining -= amount
                }
                putAmount(missing, material, remaining)
            }
        }
        if (missing.isNotEmpty()) throw CivsRejection("materials-missing", mapOf(
            "material" to missing.firstKey(), "count" to missing.getValue(missing.firstKey()).toString(),
            "missing" to missing.entries.joinToString { "${it.key}:${it.value}" },
        ))
        return plan(inventory.copy(materials = virtual.toMap(), buildingMaterials = freeze(inventory, warehouses)), snapshots)
    }

    private fun prepareBuildingDebit(city: Settlement, buildingId: UUID, required: Map<String, Long>): CivStoragePlan {
        checkClaim(city)
        if (city.building(buildingId) == null) throw CivsRejection("building-not-found")
        val physical = if (city.inventory?.mode == CivStorageMode.PHYSICAL) localSources(city, buildingId) else null
        return prepareDebit(city, required, physical, null, buildingId)
    }

    fun prepareCargoPickup(city: Settlement, cargo: CivCargo): CivStoragePlan {
        require(cargo.amount in 1L..64L)
        if (cargo.sourceWorkerId != null) {
            checkClaim(city)
            reject(cargoRoom(city, cargo.targetBuildingId, cargo.material) < cargo.amount, "storage-full")
            return CivStoragePlan(requireNotNull(city.inventory), emptyList())
        }
        return prepareBuildingDebit(city, cargo.sourceBuildingId, mapOf(cargo.material to cargo.amount))
    }

    /** A prospective worker handoff never starts without space in the destination's real holders. */
    fun cargoRoom(city: Settlement, buildingId: UUID, material: String): Long {
        checkMain(); requireOpen()
        if (city.inventory?.mode == CivStorageMode.VIRTUAL) return warehouseSpace(city, buildingId).coerceAtMost(CivsLogistics.MAX_CARGO)
        val maximum = requireNotNull(stack(material, 1)).maxStackSize
        var room = 0L
        for (source in localSources(city, buildingId)) for (current in source.inventory.storageContents) {
            room += if (isEmpty(current)) maximum else plain(current)?.takeIf { it.material == material }?.let { maximum - it.amount } ?: 0
            if (room >= CivsLogistics.MAX_CARGO) return CivsLogistics.MAX_CARGO
        }
        return room
    }

    fun prepareCargoDropoff(city: Settlement, cargo: CivCargo, destinationBuildingId: UUID): CivStoragePlan {
        require(cargo.amount in 1L..64L)
        return prepareBuildingCredit(city, destinationBuildingId, cargo.material, cargo.amount)
    }

    /** The returned resident and local storage plan belong in the same durable city receipt. */
    fun prepareResidentPickup(city: Settlement, workerId: UUID, buildingId: UUID, required: Map<String, Long>): CivResidentTransferPlan {
        checkClaim(city)
        validateAmounts(required)
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        val supplied = CivsEquipment.collectSupplies(worker, required)
        return CivResidentTransferPlan(prepareBuildingDebit(city, buildingId, required), supplied)
    }

    /** Fill a useful carried batch from the assigned hut; a site never remotely spends hut stock. */
    fun prepareBuilderPickup(city: Settlement, workerId: UUID,
        nextMaterials: Map<String, Long> = city.construction?.payment?.awaiting.orEmpty()): CivResidentTransferPlan {
        checkClaim(city)
        val order = city.construction?.takeIf { it.payment != null } ?: throw CivsRejection("no-construction")
        val worker = CivsRules.constructionWorkers(city, order.buildingId).firstOrNull { it.id == workerId }
            ?: throw CivsRejection("invalid-assignment")
        val source = worker.assignment?.let(city::building)?.takeIf { it in CivsRules.constructionStores(city, order.buildingId) }
            ?: throw CivsRejection("storage-building-required")
        val stock = buildingAvailable(city, source.id)
        var supplied = worker
        val moved = mutableMapOf<String, Long>()
        val returned = mutableMapOf<String, Long>()
        fun missing(required: Map<String, Long>) = required.mapValues { (material, count) ->
            minOf((count - CivsEquipment.usableCount(supplied, material)).coerceAtLeast(0),
                ((stock[material] ?: 0L) - (moved[material] ?: 0L)).coerceAtLeast(0))
        }.filterValues { it > 0 }
        fun collect(required: Map<String, Long>) {
            val (updated, picked) = CivsEquipment.grantSupplies(supplied, required)
            supplied = updated
            picked.forEach { (material, amount) -> moved.merge(material, amount, Math::addExact) }
        }
        val remaining = CivsMaterials.unpaid(order.materials.orEmpty())
        // Keep the next row's full recipe, returning only surplus ordinary construction supplies.
        // Nonblock ingredients come from this order's price; personal tools, food and custom items stay.
        collect(missing(nextMaterials))
        for (slot in (CivsEquipment.BAG until CivsEquipment.SLOTS).reversed()) {
            if (missing(nextMaterials).isEmpty()) break
            val items = CivsEquipment.slots(supplied).toMutableList()
            val item = items[slot]?.takeIf { it.data == null &&
                (Material.valueOf(it.material).isBlock || it.material in remaining) } ?: continue
            val amount = minOf(item.amount.toLong(),
                (CivsEquipment.usableCount(supplied, item.material) - (nextMaterials[item.material] ?: 0L)).coerceAtLeast(0)).toInt()
            if (amount == 0) continue
            items[slot] = item.copy(amount = item.amount - amount).takeIf { it.amount > 0 }
            supplied = CivsEquipment.replace(supplied, items)
            returned.merge(item.material, amount.toLong(), Math::addExact)
            collect(missing(nextMaterials))
        }
        // One stack of each needed material before bulk fill avoids a bag of only the first stone.
        collect(missing(remaining).mapValues { (material, count) -> minOf(count, Material.valueOf(material).maxStackSize.toLong()) })
        collect(missing(remaining))
        return CivResidentTransferPlan(prepareWork(city, source.id, moved, returned, emptyList()), supplied)
    }

    private fun debit(snapshots: Sequence<Snapshot>, missing: MutableMap<String, Long>, candidate: ((CivPosition) -> Boolean)? = null) {
        var changedSlots = 0
        val iterator = snapshots.iterator()
        while (missing.isNotEmpty() && iterator.hasNext()) {
            val snapshot = iterator.next()
            var contributed = false
            snapshot.after.indices.forEach { slot ->
                val item = plain(snapshot.after[slot])
                val remaining = item?.let { missing[it.material] } ?: 0L
                if (remaining > 0 && item != null) {
                    reject(changedSlots == MAX_INVENTORY_SLOT_CHANGES, "storage-transaction-too-large")
                    val taken = minOf(remaining, item.amount.toLong()).toInt()
                    putAmount(missing, item.material, remaining - taken)
                    snapshot.after[slot] = stack(item.material, item.amount - taken)
                    changedSlots++
                    contributed = true
                }
            }
            if (contributed) candidate?.invoke(snapshot.source.position)
        }
    }

    /** Consume local recipe inputs and insert local output in the same snapshots and durable receipt. */
    fun prepareProduction(city: Settlement, building: CivBuilding, worker: CivWorker? = null, craftJob: CivCraftJob? = null): CivStoragePlan {
        checkClaim(city)
        reject(city.building(building.id) != building, "not-authorized")
        if (craftJob != null) reject(worker == null || !CivsCarpentry.valid(city, worker, craftJob), "stale-delivery")
        val material = craftJob?.material ?: CivsProduction.material(building) ?: throw CivsRejection("invalid-production")
        val inputs = craftJob?.inputs ?: CivsProduction.inputs(building)
        if (worker != null) reject(!CivsEquipment.inputsReady(worker, inputs, emptyMap()), "materials-missing")
        return prepareWork(city, building.id, if (worker == null) inputs else emptyMap(),
            mapOf(material to (craftJob?.amount ?: CivsProduction.amount(building))), emptyList())
    }

    fun prepareWork(city: Settlement, buildingId: UUID, inputs: Map<String, Long>, outputs: Map<String, Long>,
        worldChanges: List<CivPlanBlock>, work: CivWorldWork? = null): CivStoragePlan {
        checkClaim(city)
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        validateAmounts(inputs)
        validateAmounts(outputs)
        val mine = work?.takeIf { it.miningSections != null }?.let {
            require(it.buildingId == buildingId && it.changes == worldChanges && city.autonomy == null)
            CivMineReceipt(building, city.minePlans?.get(buildingId.toString()),
                city.mineProgress?.get(buildingId.toString()), it).detached()
        }
        val inventory = requireNotNull(city.inventory)
        if (inventory.mode == CivStorageMode.VIRTUAL) {
            var after = prepareBuildingDebit(city, buildingId, inputs).inventoryAfter
            outputs.forEach { (material, amount) -> after = creditVirtual(city.copy(inventory = after), material, amount, buildingId).inventoryAfter }
            return CivStoragePlan(after, emptyList(), worldChanges = worldChanges.toList(), mine = mine)
        }
        val sources = localSources(city, buildingId).iterator()
        val captured = mutableListOf<Snapshot>()
        fun snapshots() = sequence {
            yieldAll(captured)
            while (sources.hasNext()) {
                val next = snapshot(sources.next())
                captured += next
                yield(next)
            }
        }
        val missing = inputs.toSortedMap()
        debit(snapshots(), missing)
        if (missing.isNotEmpty()) throw CivsRejection("materials-missing", mapOf(
            "material" to missing.firstKey(), "count" to missing.getValue(missing.firstKey()).toString()))
        outputs.forEach { (material, amount) -> reject(insert(snapshots(), material, amount) != 0L, "storage-full") }
        return plan(inventory, captured).copy(worldChanges = worldChanges.toList(), mine = mine)
    }

    private fun prepareBuildingCredit(city: Settlement, buildingId: UUID, material: String, count: Long): CivStoragePlan {
        checkClaim(city)
        validateAmounts(mapOf(material to count))
        if (city.building(buildingId) == null) throw CivsRejection("building-not-found")
        if (city.inventory?.mode == CivStorageMode.VIRTUAL) return creditVirtual(city, material, count, buildingId)
        val prepared = mutableListOf<Snapshot>()
        reject(insert(localSources(city, buildingId).map { snapshot(it).also(prepared::add) }, material, count) != 0L, "storage-full")
        return plan(requireNotNull(city.inventory), prepared)
    }

    fun prepareCredit(city: Settlement, material: String, count: Long, buildingId: UUID? = null): CivStoragePlan {
        checkClaim(city)
        validateAmounts(mapOf(material to count))
        val inventory = requireNotNull(city.inventory)
        if (inventory.mode == CivStorageMode.VIRTUAL) {
            return creditVirtual(city, material, count, buildingId ?: throw CivsRejection("storage-building-required"))
        }
        val prepared = mutableListOf<Snapshot>()
        val snapshots = sources(city, buildingId).map { snapshot(it).also(prepared::add) }
        reject(insert(snapshots, material, count) != 0L, "storage-full")
        return plan(inventory, prepared)
    }

    private fun creditVirtual(city: Settlement, material: String, count: Long, buildingId: UUID): CivStoragePlan {
        val inventory = requireNotNull(city.inventory)
        reject(count > warehouseSpace(city, buildingId), "storage-full")
        val warehouses = warehouses(inventory)
        val warehouse = warehouses.getOrPut(buildingId.toString(), ::linkedMapOf)
        putAmount(warehouse, material, Math.addExact(warehouse[material] ?: 0, count))
        return CivStoragePlan(inventory.copy(buildingMaterials = freeze(inventory, warehouses)).validated(), emptyList())
    }

    /** Explicit supply reads and fills the target's local buffer, moving plain cargo up to its shortage. */
    fun prepareConstructionSupply(city: Settlement, buildingId: UUID, required: Map<String, Long>, player: Player? = null): CivStorageSupplyPlan {
        checkClaim(city)
        validateAmounts(required)
        val inventory = requireNotNull(city.inventory)
        reject(inventory.mode != CivStorageMode.PHYSICAL, "invalid-storage-mode")
        val claim = claims.getValue(cityClaims.getValue(city.id))
        require(claim.playerId == player?.uniqueId) { "Player supply does not match its inventory lock" }
        if (player != null) reject(!player.isOnline || !city.canManage(player.uniqueId), "not-authorized")
        if (required.isEmpty()) return CivStorageSupplyPlan(CivStoragePlan(inventory, emptyList()), CivsSupplyResult(0, 0, false))
        val snapshots = localSources(city, buildingId).map(::snapshot).toList()
        val available = mutableMapOf<String, Long>()
        snapshots.forEach { source -> source.before.forEach { stack ->
            plain(stack)?.let { if (it.material in required) available.merge(it.material, it.amount.toLong(), Math::addExact) }
        } }
        val missing = required.mapValues { (material, count) -> (count - (available[material] ?: 0L)).coerceAtLeast(0) }
            .filterValues { it > 0 }.toSortedMap()
        if (missing.isEmpty()) return CivStorageSupplyPlan(CivStoragePlan(inventory, emptyList()), CivsSupplyResult(0, 0, false))
        val playerBefore = player?.inventory?.storageContents?.map { it?.clone() }
        require(playerBefore == null || playerBefore.size == PLAYER_STORAGE_SLOTS)
        val cargo = mutableMapOf<String, Long>()
        playerBefore?.forEach { stack -> plain(stack)?.let { cargo.merge(it.material, it.amount.toLong(), Math::addExact) } }
        val offered = missing.mapValues { (material, count) -> if (player == null) count else minOf(count, cargo[material] ?: 0) }
            .filterValues { it > 0 }
        reject(offered.isEmpty(), "construction-supplies-empty")
        var full = false
        val moved = offered.mapValues { (material, count) ->
            val remainder = insert(snapshots, material, count)
            if (remainder > 0) full = true
            count - remainder
        }.filterValues { it > 0 }
        val playerChanges = mutableListOf<CivPlayerSlotChange>()
        if (playerBefore != null) {
            val remaining = moved.toMutableMap()
            playerBefore.forEachIndexed { slot, stack ->
                val item = plain(stack) ?: return@forEachIndexed
                val amount = minOf(item.amount.toLong(), remaining[item.material] ?: 0L).toInt()
                if (amount == 0) return@forEachIndexed
                playerChanges += CivPlayerSlotChange(slot, item, if (amount == item.amount) null else item.copy(amount = item.amount - amount))
                putAmount(remaining, item.material, requireNotNull(remaining[item.material]) - amount)
            }
            require(remaining.isEmpty())
        }
        val prepared = plan(inventory, snapshots).copy(playerDebit = player?.takeIf { playerChanges.isNotEmpty() }?.let {
            CivPlayerDebit(it.uniqueId, playerChanges.toList())
        })
        val count = moved.values.sum()
        return CivStorageSupplyPlan(prepared, CivsSupplyResult(count, missing.values.sum() - count, full))
    }

    /** Move reserves into real slots in a bounded receipt; full/unloaded storage leaves every remainder durable. */
    fun prepareMaterialization(city: Settlement, targetMode: CivStorageMode = CivStorageMode.PHYSICAL): CivStoragePlan {
        checkClaim(city)
        reject(targetMode != CivStorageMode.PHYSICAL, "invalid-storage-mode")
        val inventory = requireNotNull(city.inventory)
        val virtual = inventory.materials.toMutableMap()
        val warehouses = warehouses(inventory)
        val depot = city.buildings.firstOrNull { it.type == BuildingType.WAREHOUSE && it.phase == BuildingPhase.ACTIVE }
            ?: city.buildings.first { it.type == BuildingType.TOWN_HALL }
        val sharedDestinations = buildingPositions(city, depot.id)
        val buildingDestinations = warehouses.keys.associateWith { id ->
            buildingPositions(city, UUID.fromString(id), controlOnly = true).keys + sharedDestinations.keys
        }
        val eligible = sharedDestinations.keys + buildingDestinations.values.flatten()
        val positions = materializationWindow(city, eligible)
        val prepared = linkedMapOf<CivPosition, Snapshot?>()
        val changed = hashSetOf<Pair<CivPosition, Int>>()
        fun move(amounts: MutableMap<String, Long>, buildingId: UUID?) {
            val preferred = buildingId?.let { city.building(it)?.control }
            val allowed = if (buildingId == null) sharedDestinations.keys else buildingDestinations.getValue(buildingId.toString())
            val selected = positions.filter { it.first in allowed }
            val ordered = if (preferred == null) selected else selected.filter { it.first == preferred } + selected.filter { it.first != preferred }
            for ((material, count) in amounts.toSortedMap()) {
                if (changed.size >= MATERIALIZATION_SLOT_CHANGES) break
                val snapshots = ordered.asSequence().mapNotNull { (position, type) ->
                    if (position !in prepared) prepared[position] = try {
                        snapshot(resolve(city, position, type))
                    } catch (failure: CivsRejection) {
                        if (failure.reason == "storage-unavailable") null else throw failure
                    }
                    prepared[position]
                }
                val remaining = insert(snapshots, material, count, changed)
                putAmount(amounts, material, remaining)
            }
        }
        move(virtual, null)
        warehouses.toSortedMap().forEach { (id, amounts) -> move(amounts, UUID.fromString(id)) }
        return plan(inventory.copy(mode = targetMode, materials = virtual.toMap(), buildingMaterials = freeze(inventory, warehouses)), prepared.values.filterNotNull())
    }

    /** Select before native reads; advance even when this whole window is full or unloaded. */
    private fun materializationWindow(city: Settlement, eligible: Set<CivPosition>): List<Pair<CivPosition, String?>> {
        val positions = claims.getValue(cityClaims.getValue(city.id)).positions.filterKeys { it in eligible }
        val controls = city.buildings.mapNotNull(CivBuilding::control).distinct().filter { it in positions }
        val others = positions.keys.filter { it !in controls }
        val count = minOf(MATERIALIZATION_CONTAINERS - controls.size, others.size)
        val start = if (others.isEmpty()) 0 else (materializationCursors[city.id] ?: 0) % others.size
        val selected = controls + (0 until count).map { others[(start + it) % others.size] }
        materializationCursors[city.id] = if (others.isEmpty()) 0 else (start + count) % others.size
        return selected.map { it to positions[it] }
    }

    /** Deposit plain cargo into this building's warehouse, retaining custom items and overflow. */
    fun depositFromControl(city: Settlement, building: CivBuilding): CivStoragePlan {
        checkClaim(city)
        val inventory = requireNotNull(city.inventory)
        reject(inventory.mode != CivStorageMode.VIRTUAL, "invalid-storage-mode")
        val snapshot = snapshot(control(city, building))
        val warehouses = warehouses(inventory)
        val warehouse = warehouses.getOrPut(building.id.toString(), ::linkedMapOf)
        var space = warehouseSpace(city, building.id)
        var moved = 0L
        snapshot.after.indices.forEach { slot ->
            val item = plain(snapshot.after[slot]) ?: return@forEach
            val amount = minOf(space, item.amount.toLong())
            if (amount == 0L) return@forEach
            putAmount(warehouse, item.material, Math.addExact(warehouse[item.material] ?: 0, amount))
            snapshot.after[slot] = stack(item.material, item.amount - amount.toInt())
            space -= amount
            moved += amount
        }
        reject(moved == 0L, if (space == 0L) "storage-full" else "storage-empty")
        return plan(inventory.copy(buildingMaterials = freeze(inventory, warehouses)).validated(), listOf(snapshot))
    }

    fun withdrawToControl(city: Settlement, building: CivBuilding, material: String, maximum: Int = 64): CivStoragePlan {
        checkClaim(city)
        require(maximum in 1..64)
        validateAmounts(mapOf(material to 1L))
        val inventory = requireNotNull(city.inventory)
        val warehouses = warehouses(inventory)
        val warehouse = warehouses.getOrPut(building.id.toString(), ::linkedMapOf)
        val available = Math.addExact(warehouse[material] ?: 0, inventory.materials[material] ?: 0)
        if (available == 0L) throw CivsRejection("materials-missing", mapOf("material" to material, "count" to "1"))
        val snapshots = listOf(snapshot(control(city, building)))
        val requested = minOf(available, maximum.toLong())
        val remaining = insert(snapshots, material, requested)
        val moved = requested - remaining
        reject(moved == 0L, "storage-full")
        val virtual = inventory.materials.toMutableMap()
        val fromWarehouse = minOf(moved, warehouse[material] ?: 0)
        putAmount(warehouse, material, (warehouse[material] ?: 0) - fromWarehouse)
        putAmount(virtual, material, (virtual[material] ?: 0) - (moved - fromWarehouse))
        return plan(inventory.copy(materials = virtual.toMap(), buildingMaterials = freeze(inventory, warehouses)), snapshots)
    }

    fun openControl(player: Player, city: Settlement, building: CivBuilding) {
        checkMain()
        requireOpen()
        reject(!(city.canManage(player.uniqueId) || city.autonomy != null) || player.world.uid != city.worldId, "not-authorized")
        reject(city.id in cityClaims, "storage-busy")
        player.openInventory(control(city, building).inventory)
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
                val source = resolved.getOrPut(change.container) { resolve(transaction.after, change.container, change.containerType) }
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
        val playerState = classifyPlayer(playerId, playerChanges, player)
        if (playerState is CivInventoryApplyResult.Conflict || playerState is CivInventoryApplyResult.Retry) return playerState
        val containerState = classify(transaction, resolved)
        if (containerState is CivInventoryApplyResult.Conflict) return containerState
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
        if (player == null && containerState == CivInventoryApplyResult.Applied && worldState == CivInventoryApplyResult.Applied &&
            collectedState == CivInventoryApplyResult.Applied) return containerState
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
                val inventory = resolved.getValue(change.container).inventory
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
                    val inventory = resolve(transaction.after, change.container, change.containerType).inventory
                    if (!matchesNative(inventory.getItem(change.slot), change.after)) inventory.setItem(change.slot, change.after?.stack())
                }
            }
        } catch (failure: Exception) {
            CivsDiagnostics.record(plugin.logger, Level.WARNING, transaction.cityId, "ArcCivs could not apply storage transaction ${transaction.id} for ${transaction.cityId}", failure)
            if (!playerPersisted) return CivInventoryApplyResult.Retry(mapOf("reason" to "player-save-incomplete"))
            return completedNativeState(transaction, resolved, worldCells)
        }
        return completedNativeState(transaction, resolved, worldCells)
    }

    fun apply(transaction: CivInventoryTransaction): CivInventoryApplyResult = reconcile(transaction)

    /** Each admitted batch has two fences: source before the journal, native after before city credit. */
    internal fun persistCollected(transaction: CivInventoryTransaction, beforeJournal: Boolean = false): CompletableFuture<Unit> {
        checkMain(); requireOpen()
        if (transaction.collected == null) return CompletableFuture.completedFuture(Unit)
        fun checkState() {
            when (val state = classifyCollected(transaction, beforeJournal)) {
                CivInventoryApplyResult.Applied -> Unit
                is CivInventoryApplyResult.Retry -> throw CivsRejection("inventory-recovery", state.values)
                is CivInventoryApplyResult.Conflict -> throw CivsRejection("inventory-conflict", state.values)
                null -> throw CivsRejection("inventory-recovery")
            }
        }
        checkState()
        val epoch = persistenceTasks.token()
        val result = CompletableFuture<Unit>()
        pendingPersistence += result
        val native = try { persistCollectedNative(transaction, beforeJournal) } catch (failure: Throwable) {
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

    private fun classifyCollected(transaction: CivInventoryTransaction, expectedBefore: Boolean = false): CivInventoryApplyResult? {
        val collected = transaction.collected ?: return CivInventoryApplyResult.Applied
        val world = plugin.server.getWorld(transaction.after.worldId)
            ?: return CivInventoryApplyResult.Retry(mapOf("reason" to "world-unloaded"))
        var pending = false
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
            val source = try { resolve(transaction.after, change.container, change.containerType) } catch (failure: CivsRejection) {
                return if (failure.reason == "storage-unavailable") CivInventoryApplyResult.Retry(failure.values)
                    else CivInventoryApplyResult.Conflict(failure.values)
            }
            val native = source.inventory.getItem(change.slot)
            val before = runCatching { matchesNative(native, change.before) }.getOrDefault(false)
            val after = runCatching { matchesNative(native, change.after) }.getOrDefault(false)
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

    private fun matchesNative(item: ItemStack?, expected: CivResidentItem?): Boolean =
        if (expected == null) isEmpty(item) else !isEmpty(item) && item!!.amount == expected.amount && item.isSimilar(expected.stack())

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
        return if (containers == CivInventoryApplyResult.Applied && world == CivInventoryApplyResult.Applied &&
            collected == CivInventoryApplyResult.Applied) CivInventoryApplyResult.Applied
            else CivInventoryApplyResult.Retry(mapOf("reason" to "write-incomplete"))
    }

    private fun worldDetails(transaction: CivInventoryTransaction, cell: CivPlanBlock, reason: String): Map<String, String> = mapOf(
        "reason" to reason, "world" to transaction.after.worldId.toString(), "x" to cell.position.x.toString(),
        "y" to cell.position.y.toString(), "z" to cell.position.z.toString())

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

    private fun sources(city: Settlement, preferredBuildingId: UUID? = null): Sequence<Source> {
        val positions = cityClaims[city.id]?.let(claims::get)?.positions ?: index.positions(city)
        val preferred = preferredBuildingId?.let { id ->
            (city.building(id) ?: throw CivsRejection("building-not-found")).control
        }
        // The claim freezes source membership across journal IO. New event-indexed containers join the next operation.
        val ordered = sequence {
            if (preferred != null && preferred in positions) yield(preferred to positions[preferred])
            positions.forEach { (position, type) -> if (position != preferred) yield(position to type) }
        }
        return resolveSources(city, ordered)
    }

    private fun buildingPositions(city: Settlement, buildingId: UUID, controlOnly: Boolean = false): Map<CivPosition, String?> {
        val positions = cityClaims[city.id]?.let(claims::get)?.positions ?: index.positions(city)
        return selectBuildingPositions(city, buildingId, positions, controlOnly)
    }

    private fun selectBuildingPositions(city: Settlement, buildingId: UUID, positions: Map<CivPosition, String?>,
        controlOnly: Boolean = false): Map<CivPosition, String?> {
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        return linkedMapOf<CivPosition, String?>().also { selected ->
            building.control?.takeIf { it in positions }?.let { selected[it] = positions[it] }
            if (!controlOnly && building.phase == BuildingPhase.ACTIVE) {
                val foreignControls = city.buildings.filter { it.id != buildingId }.mapNotNull(CivBuilding::control).toSet()
                positions.forEach { (position, type) ->
                    if (position !in foreignControls && building.bounds.contains(position.x, position.y, position.z))
                        selected.putIfAbsent(position, type)
                }
            }
        }
    }

    private fun localSources(city: Settlement, buildingId: UUID): Sequence<Source> =
        resolveSources(city, buildingPositions(city, buildingId).asSequence().map { it.key to it.value })

    private fun resolveSources(city: Settlement, positions: Sequence<Pair<CivPosition, String?>>): Sequence<Source> =
        positions.mapNotNull { (position, type) ->
            try { resolve(city, position, type) } catch (failure: CivsRejection) {
                if (failure.reason == "storage-unavailable") null else throw failure
            }
        }

    private fun control(city: Settlement, building: CivBuilding): Source {
        reject(city.building(building.id) != building, "not-authorized")
        val position = building.control ?: throw CivsRejection("storage-container-invalid")
        return resolve(city, position, "BARREL")
    }

    private fun resolve(city: Settlement, position: CivPosition, expectedType: String?): Source {
        val control = city.buildings.any { it.control == position }
        reject(!storagePositionAllowed(city, position, if (control) "BARREL" else expectedType ?: "CHEST"), "not-authorized")
        val values = mapOf("x" to position.x.toString(), "y" to position.y.toString(), "z" to position.z.toString(), "world" to city.worldId.toString())
        val world = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("storage-unavailable", values)
        val located = values + ("world_name" to world.name)
        if (position.y !in world.minHeight until world.maxHeight)
            throw CivsRejection("storage-container-invalid", located + ("reason" to "outside-world"))
        if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) throw CivsRejection("storage-unavailable", located)
        val block = world.getBlockAt(position.x, position.y, position.z)
        val state = block.state
        val inventory = when (state) {
            is Chest -> state.blockInventory // Exactly one 27-slot half, even beside a foreign chest.
            is Barrel -> state.inventory
            else -> throw CivsRejection("storage-container-invalid", located + ("block" to block.type.name))
        }
        if (block.type.name !in STORAGE_TYPES || expectedType != null && block.type.name != expectedType || control && block.type != Material.BARREL)
            throw CivsRejection("storage-container-invalid", located + ("block" to block.type.name))
        require(inventory.size == STORAGE_SLOTS)
        return Source(position, block.type.name, inventory)
    }

    private fun snapshot(source: Source): Snapshot {
        val before = source.inventory.storageContents.map { it?.clone() }
        require(before.size == STORAGE_SLOTS)
        return Snapshot(source, before, before.map { it?.clone() }.toMutableList())
    }

    private fun captureSupplyContainer(city: Settlement, position: CivPosition, expectedType: String?): CivSupplyCapturedContainer? {
        checkMain(); requireOpen()
        if (city.autonomy != null && !foodCanModify(city, position)) return null
        val source = try { resolve(city, position, expectedType) } catch (failure: CivsRejection) {
            if (failure.reason in setOf("storage-unavailable", "storage-container-invalid")) return null else throw failure
        }
        val contents = source.inventory.storageContents
        require(contents.size == STORAGE_SLOTS)
        return CivSupplyCapturedContainer(source.type, contents.map { item ->
            if (isEmpty(item)) CivSupplyCapturedSlot(null, 0, false, 0) else {
                val native = requireNotNull(item)
                CivSupplyCapturedSlot(native.type.name, native.amount, plain(native) != null, native.maxStackSize)
            }
        })
    }

    private fun plan(inventory: CivInventory, snapshots: List<Snapshot>): CivStoragePlan {
        val changes = mutableListOf<CivInventorySlotChange>()
        snapshots.forEach { snapshot ->
            snapshot.before.indices.forEach { slot ->
                val before = plain(snapshot.before[slot])
                val after = plain(snapshot.after[slot])
                if (before != after) {
                    reject(changes.size == MAX_INVENTORY_SLOT_CHANGES, "storage-transaction-too-large")
                    changes += CivInventorySlotChange(snapshot.source.position, snapshot.source.type, slot, before, after)
                }
            }
        }
        return CivStoragePlan(inventory.validated(), changes.toList())
    }

    private fun insert(snapshots: List<Snapshot>, material: String, amount: Long): Long = insert(snapshots.asSequence(), material, amount)

    private fun insert(snapshots: Sequence<Snapshot>, material: String, amount: Long,
        changed: MutableSet<Pair<CivPosition, Int>>? = null): Long {
        var remaining = amount
        var writes = 0
        val limit = requireNotNull(stack(material, 1)).maxStackSize
        // Finish each source before proceeding: the control remains primary even when another chest has a partial stack.
        for (snapshot in snapshots) for (empty in listOf(false, true)) for (slot in snapshot.after.indices) {
            if (remaining == 0L) return 0
            val current = snapshot.after[slot]
            val item = plain(current)
            if (if (empty) !isEmpty(current) else item?.material != material) continue
            val count = if (empty) 0 else requireNotNull(item).amount
            val added = minOf(remaining, (limit - count).coerceAtLeast(0).toLong()).toInt()
            if (added > 0) {
                if (changed != null) {
                    val key = snapshot.source.position to slot
                    if (key !in changed && changed.size >= MATERIALIZATION_SLOT_CHANGES) return remaining
                    changed += key
                } else reject(writes == MAX_INVENTORY_SLOT_CHANGES, "storage-transaction-too-large")
                snapshot.after[slot] = stack(material, count + added)
                writes++
            }
            remaining -= added
        }
        return remaining
    }

    private fun validateAmounts(amounts: Map<String, Long>) {
        require(amounts.size <= CivInventory.MAX_MATERIALS)
        amounts.forEach { (material, count) ->
            require(count in 1..CivInventory.MAX_AMOUNT)
            requireNotNull(stack(material, 1))
        }
    }

    private fun warehouseSpace(city: Settlement, buildingId: UUID): Long {
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        return (27L * 64L * building.level - requireNotNull(city.inventory).buildingMaterials.orEmpty()[buildingId.toString()]
            .orEmpty().values.sum()).coerceAtLeast(0)
    }

    private fun warehouses(inventory: CivInventory): MutableMap<String, MutableMap<String, Long>> =
        inventory.buildingMaterials.orEmpty().mapValuesTo(linkedMapOf()) { it.value.toMutableMap() }

    private fun freeze(inventory: CivInventory, warehouses: Map<String, Map<String, Long>>): Map<String, Map<String, Long>>? =
        warehouses.filterValues { it.isNotEmpty() }.mapValues { it.value.toMap() }.let {
            if (inventory.buildingMaterials == null && it.isEmpty()) null else it
        }

    private fun putAmount(amounts: MutableMap<String, Long>, material: String, amount: Long) {
        require(amount in 0..CivInventory.MAX_AMOUNT)
        if (amount == 0L) amounts.remove(material) else amounts[material] = amount
    }

    private fun plain(item: ItemStack?): CivPlainItem? {
        // Equality with the native default prototype covers metadata/components without relying on empty-meta representation.
        if (isEmpty(item) || !item!!.isSimilar(ItemStack(item.type))) return null
        if (item.amount !in 1..minOf(64, item.maxStackSize)) return null
        return CivPlainItem(item.type.name, item.amount)
    }

    private fun matches(item: ItemStack?, expected: CivPlainItem?): Boolean = if (expected == null) isEmpty(item) else plain(item) == expected
    private fun isEmpty(item: ItemStack?): Boolean = item == null || item.type.isAir || item.amount <= 0
    private fun stack(material: String, count: Int): ItemStack? {
        if (count == 0) return null
        val type = Material.getMaterial(material)?.takeIf { it.isItem && !it.isAir }
            ?: throw CivsRejection("unsupported-material", mapOf("material" to material))
        return ItemStack(type, count)
    }
    private fun itemDescription(item: ItemStack?): String = if (isEmpty(item)) "AIR" else "${item!!.type.name}:${item.amount}${if (plain(item) == null) ":custom" else ""}"
    private fun details(city: Settlement, change: CivInventorySlotChange, reason: String): Map<String, String> = mapOf(
        "reason" to reason, "world" to city.worldId.toString(),
        "world_name" to (plugin.server.getWorld(city.worldId)?.name ?: city.worldId.toString()), "x" to change.container.x.toString(),
        "y" to change.container.y.toString(), "z" to change.container.z.toString(), "slot" to change.slot.toString(),
        "block" to change.containerType,
    )

    private fun playerLocked(playerId: UUID): Boolean = receiptBootstrap || playerId in lockedPlayers
    private fun locked(inventory: Inventory): Boolean = receiptBootstrap || exactLocked(inventory)
    private fun exactLocked(inventory: Inventory): Boolean {
        if (locked.isEmpty() && lockedPlayers.isEmpty()) return false
        val holder = inventory.getHolder(false)
        return (holder as? Player)?.uniqueId?.let { it in lockedPlayers } == true ||
            holderPositions(holder).any { it in locked }
    }
    private fun holderPositions(holder: InventoryHolder?): List<Key> = when (holder) {
        is DoubleChest -> holderPositions(holder.getLeftSide(false)) + holderPositions(holder.getRightSide(false))
        is BlockState -> listOf(Key(holder.world.uid, CivPosition(holder.x, holder.y, holder.z)))
        else -> emptyList()
    }
    private fun locked(block: Block): Boolean = receiptBootstrap && block.type.name in STORAGE_TYPES ||
        Key(block.world.uid, CivPosition(block.x, block.y, block.z)) in locked ||
        isWorldLocked(block.world.uid, CivPosition(block.x, block.y, block.z))

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onOpen(event: InventoryOpenEvent) { if (playerLocked(event.player.uniqueId) || locked(event.inventory)) event.isCancelled = true }
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
    fun indexPlace(event: BlockPlaceEvent) {
        index.changed(event.blockPlaced)
        if (event.blockPlaced.type.name in STORAGE_TYPES || event.blockReplacedState.type.name in STORAGE_TYPES)
            supplyChanged(event.blockPlaced)
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun indexBreak(event: BlockBreakEvent) { if (event.block.type.name in STORAGE_TYPES) supplyChanged(event.block); index.removed(event.block) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun indexBlockExplosion(event: BlockExplodeEvent) { event.blockList().forEach { if (it.type.name in STORAGE_TYPES) supplyChanged(it); index.removed(it) } }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun indexEntityExplosion(event: EntityExplodeEvent) { event.blockList().forEach { if (it.type.name in STORAGE_TYPES) supplyChanged(it); index.removed(it) } }
    @EventHandler(priority = EventPriority.MONITOR)
    fun indexChunkLoad(event: ChunkLoadEvent) { index.load(event.chunk); supplyChunkChanged(event.chunk) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun indexChunkUnload(event: ChunkUnloadEvent) { supplyChunkChanged(event.chunk); index.unload(event.chunk) }
    @EventHandler(priority = EventPriority.MONITOR)
    fun indexWorldLoad(event: WorldLoadEvent) { event.world.loadedChunks.forEach { index.load(it); supplyChunkChanged(it) } }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun indexWorldUnload(event: WorldUnloadEvent) { event.world.loadedChunks.forEach(::supplyChunkChanged); index.unloadWorld(event.world.uid) }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun supplyClick(event: InventoryClickEvent) { supplyChanged(event.view.topInventory) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun supplyDrag(event: InventoryDragEvent) { supplyChanged(event.view.topInventory) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun supplyMove(event: InventoryMoveItemEvent) { supplyChanged(event.source); supplyChanged(event.destination) }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun supplyPickup(event: InventoryPickupItemEvent) { supplyChanged(event.inventory) }

    private fun supplyChanged(inventory: Inventory) {
        holderPositions(inventory.holder).forEach { supplyCaptures.changed(it.worldId, it.position) }
    }
    private fun supplyChanged(block: Block) = supplyCaptures.changed(block.world.uid, CivPosition(block.x, block.y, block.z))
    private fun supplyChunkChanged(chunk: Chunk) = supplyCaptures.chunkChanged(chunk.world.uid, chunk.x, chunk.z)

    override fun close() {
        checkMain()
        if (closed) return
        closed = true
        cancelCollectedPersistence()
        if (nativePersistenceDelegate.isInitialized()) nativePersistence.close()
        HandlerList.unregisterAll(this)
        claims.keys.toList().forEach(::unlock)
        index.clear()
        materializationCursors.clear()
        debitSearches.clear()
        homeCursors.clear()
        supplyCaptures.clear()
        autonomousCaptures.clear()
    }
    private fun requireOpen() = check(!closed) { "CivsStorage is closed" }
    private fun checkMain() = check(Bukkit.isPrimaryThread()) { "CivsStorage requires Paper's main thread" }
    private fun reject(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
}
