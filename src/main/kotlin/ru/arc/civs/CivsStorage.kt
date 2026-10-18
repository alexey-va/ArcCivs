package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Barrel
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.Chest
import org.bukkit.block.DoubleChest
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.UUID
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

data class CivStoragePlan(val inventoryAfter: CivInventory, val changes: List<CivInventorySlotChange>)

/** Exact slot receipt; the service persists this before applying any native item change. */
data class CivInventoryTransaction(
    val id: UUID, val cityId: UUID, val beforeRevision: Long,
    val after: Settlement, val changes: List<CivInventorySlotChange>,
) {
    fun validated(): CivInventoryTransaction = apply {
        require(id != null && cityId != null && after != null && changes != null)
        require(beforeRevision in 0 until Long.MAX_VALUE && after.id == cityId && after.revision == beforeRevision + 1)
        CivsRules.validate(after)
        requireNotNull(after.inventory)
        val allowed = storagePositions(after)
        require(changes.size <= MAX_STORAGE_CONTAINERS * STORAGE_SLOTS)
        require(changes.map { it.container to it.slot }.distinct().size == changes.size)
        changes.forEach { change ->
            require(change != null && change.container != null && change.containerType in STORAGE_TYPES && change.slot in 0 until STORAGE_SLOTS)
            require(change.container in allowed && (allowed[change.container] == null || allowed[change.container] == change.containerType))
            require(change.before != change.after)
            change.before?.validated()
            change.after?.validated()
        }
    }
}

sealed interface CivInventoryApplyResult {
    data object Applied : CivInventoryApplyResult
    data class Retry(val values: Map<String, String>) : CivInventoryApplyResult
    data class Conflict(val values: Map<String, String>) : CivInventoryApplyResult
}

private const val MAX_STORAGE_CONTAINERS = 32
private const val STORAGE_SLOTS = 27
private val STORAGE_TYPES = setOf("CHEST", "BARREL")

/** Null means an explicitly registered chest or barrel; building controls must remain barrels. */
private fun storagePositions(city: Settlement): Map<CivPosition, String?> {
    require(city.buildings.size <= 16)
    val inventory = requireNotNull(city.inventory) { "Settlement inventory has not been migrated" }.validated()
    val result = linkedMapOf<CivPosition, String?>()
    inventory.containers.forEach { result[it] = null }
    city.buildings.mapNotNull(CivBuilding::control).forEach { result[it] = "BARREL" }
    require(result.size <= MAX_STORAGE_CONTAINERS)
    require(result.keys.all { city.contains(it.x, it.z) && it.y in -4_096..4_096 &&
        kotlin.math.abs(it.x.toLong()) <= 30_000_000 && kotlin.math.abs(it.z.toLong()) <= 30_000_000 })
    return result
}

/** Bounded main-thread storage. No IO, chunk loads, item drops or native mutation during planning. */
class CivsStorage(private val plugin: Plugin) : Listener, AutoCloseable {
    private data class Key(val worldId: UUID, val position: CivPosition)
    private data class Claim(val city: Settlement, val positions: Map<CivPosition, String?>)
    private data class Source(val position: CivPosition, val type: String, val inventory: Inventory)
    private data class Snapshot(val source: Source, val before: List<ItemStack?>, val after: MutableList<ItemStack?>)
    private val claims = mutableMapOf<UUID, Claim>()
    private val cityClaims = mutableMapOf<UUID, UUID>()
    private val locked = mutableMapOf<Key, UUID>()
    private var closed = false

    init { plugin.server.pluginManager.registerEvents(this, plugin) }

    /** Acquire before preparing a receipt; keep it through journal, city commit and journal deletion. */
    fun lock(city: Settlement, transactionId: UUID) {
        checkMain()
        requireOpen()
        val positions = storagePositions(city)
        val existing = claims[transactionId]
        if (existing != null) {
            require(existing.city.id == city.id && existing.city.owner == city.owner && existing.city.worldId == city.worldId &&
                existing.positions == positions && (existing.city.revision == city.revision ||
                existing.city.revision < Long.MAX_VALUE && existing.city.revision + 1 == city.revision ||
                city.revision < Long.MAX_VALUE && city.revision + 1 == existing.city.revision)) { "Storage lock identity changed" }
            return
        }
        reject(city.id in cityClaims || positions.keys.any { Key(city.worldId, it) in locked }, "storage-busy")
        claims[transactionId] = Claim(city, positions)
        cityClaims[city.id] = transactionId
        positions.keys.forEach { locked[Key(city.worldId, it)] = transactionId }
        try {
            positions.forEach { (position, type) ->
                val world = plugin.server.getWorld(city.worldId) ?: return@forEach
                if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) return@forEach
                val source = try { resolve(city, position, type) } catch (_: CivsRejection) { return@forEach }
                source.inventory.viewers.toList().forEach { it.closeInventory() }
            }
        } catch (error: Exception) {
            unlock(transactionId)
            throw error
        }
    }

    fun unlock(transactionId: UUID) {
        checkMain()
        val claim = claims.remove(transactionId) ?: return
        cityClaims.remove(claim.city.id, transactionId)
        claim.positions.keys.forEach { locked.remove(Key(claim.city.worldId, it), transactionId) }
    }

    fun available(city: Settlement): Map<String, Long> {
        checkMain()
        requireOpen()
        val inventory = requireNotNull(city.inventory).validated()
        val totals = inventory.materials.toMutableMap()
        inventory.buildingMaterials.orEmpty().values.forEach { warehouse ->
            warehouse.forEach { (material, count) -> totals.merge(material, count, Math::addExact) }
        }
        if (inventory.mode == CivStorageMode.PHYSICAL) sources(city).forEach { source ->
            source.inventory.storageContents.forEach { stack -> plain(stack)?.let { totals.merge(it.material, it.amount.toLong(), Math::addExact) } }
        }
        return totals.toSortedMap()
    }

    fun prepareDebit(city: Settlement, required: Map<String, Long>): CivStoragePlan {
        checkClaim(city)
        validateAmounts(required)
        val inventory = requireNotNull(city.inventory)
        val virtual = inventory.materials.toMutableMap()
        val warehouses = warehouses(inventory)
        val snapshots = if (inventory.mode == CivStorageMode.PHYSICAL) snapshots(sources(city)) else emptyList()
        val missing = sortedMapOf<String, Long>()
        required.toSortedMap().forEach { (material, requested) ->
            var remaining = requested
            snapshots.forEach { snapshot ->
                snapshot.after.indices.forEach { slot ->
                    val item = plain(snapshot.after[slot])
                    if (remaining > 0 && item?.material == material) {
                        val taken = minOf(remaining, item.amount.toLong()).toInt()
                        remaining -= taken
                        snapshot.after[slot] = stack(material, item.amount - taken)
                    }
                }
            }
            val taken = minOf(remaining, virtual[material] ?: 0)
            putAmount(virtual, material, (virtual[material] ?: 0) - taken)
            remaining -= taken
            warehouses.toSortedMap().values.forEach { warehouse ->
                val amount = minOf(remaining, warehouse[material] ?: 0)
                putAmount(warehouse, material, (warehouse[material] ?: 0) - amount)
                remaining -= amount
            }
            if (remaining > 0) missing[material] = remaining
        }
        if (missing.isNotEmpty()) throw CivsRejection("materials-missing", mapOf(
            "material" to missing.firstKey(), "count" to missing.getValue(missing.firstKey()).toString(),
            "missing" to missing.entries.joinToString { "${it.key}:${it.value}" },
        ))
        return plan(inventory.copy(materials = virtual.toMap(), buildingMaterials = freeze(inventory, warehouses)), snapshots)
    }

    fun prepareCredit(city: Settlement, material: String, count: Long, buildingId: UUID? = null): CivStoragePlan {
        checkClaim(city)
        validateAmounts(mapOf(material to count))
        val inventory = requireNotNull(city.inventory)
        if (inventory.mode == CivStorageMode.VIRTUAL) {
            val id = buildingId ?: throw CivsRejection("storage-building-required")
            reject(count > warehouseSpace(city, id), "storage-full")
            val warehouses = warehouses(inventory)
            val warehouse = warehouses.getOrPut(id.toString(), ::linkedMapOf)
            putAmount(warehouse, material, Math.addExact(warehouse[material] ?: 0, count))
            return CivStoragePlan(inventory.copy(buildingMaterials = freeze(inventory, warehouses)).validated(), emptyList())
        }
        val snapshots = snapshots(sources(city))
        reject(insert(snapshots, material, count) != 0L, "storage-full")
        return plan(inventory, snapshots)
    }

    /** Deposit plain cargo into this building's warehouse, retaining custom items and overflow. */
    fun depositFromControl(city: Settlement, building: CivBuilding): CivStoragePlan {
        checkClaim(city)
        val inventory = requireNotNull(city.inventory)
        val snapshot = snapshots(listOf(control(city, building))).single()
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
        val snapshots = snapshots(listOf(control(city, building)))
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
        reject(!city.canManage(player.uniqueId) || player.world.uid != city.worldId, "not-authorized")
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
        classify(transaction, resolved)?.let { return it }
        try {
            transaction.changes.forEach { change ->
                val inventory = resolved.getValue(change.container).inventory
                if (!matches(inventory.getItem(change.slot), change.after)) {
                    inventory.setItem(change.slot, change.after?.let { stack(it.material, it.amount) })
                }
            }
        } catch (failure: Exception) {
            plugin.logger.log(Level.WARNING, "ArcCivs could not apply storage transaction ${transaction.id} for ${transaction.cityId}", failure)
            return classify(transaction, resolved) ?: CivInventoryApplyResult.Retry(mapOf("reason" to "write-incomplete"))
        }
        return classify(transaction, resolved) ?: CivInventoryApplyResult.Retry(mapOf("reason" to "write-incomplete"))
    }

    fun apply(transaction: CivInventoryTransaction): CivInventoryApplyResult = reconcile(transaction)

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

    private fun sources(city: Settlement): List<Source> = storagePositions(city).mapNotNull { (position, type) ->
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
        val allowed = storagePositions(city)
        reject(position !in allowed, "not-authorized")
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
        if (expectedType != null && block.type.name != expectedType || allowed[position] != null && block.type.name != allowed[position])
            throw CivsRejection("storage-container-invalid", located + ("block" to block.type.name))
        require(inventory.size == STORAGE_SLOTS)
        return Source(position, block.type.name, inventory)
    }

    private fun snapshots(sources: List<Source>): List<Snapshot> = sources.map { source ->
        val before = source.inventory.storageContents.map { it?.clone() }
        require(before.size == STORAGE_SLOTS)
        Snapshot(source, before, before.map { it?.clone() }.toMutableList())
    }

    private fun plan(inventory: CivInventory, snapshots: List<Snapshot>): CivStoragePlan = CivStoragePlan(inventory.validated(), snapshots.flatMap { snapshot ->
        snapshot.before.indices.mapNotNull { slot ->
            val before = plain(snapshot.before[slot])
            val after = plain(snapshot.after[slot])
            if (before == after) null else CivInventorySlotChange(snapshot.source.position, snapshot.source.type, slot, before, after)
        }
    })

    private fun insert(snapshots: List<Snapshot>, material: String, amount: Long): Long {
        var remaining = amount
        val limit = requireNotNull(stack(material, 1)).maxStackSize
        // Fill matching stacks first, then empty slots, retaining every custom or unrelated item.
        for (empty in listOf(false, true)) for (snapshot in snapshots) for (slot in snapshot.after.indices) {
            if (remaining == 0L) return 0
            val current = snapshot.after[slot]
            val item = plain(current)
            if (if (empty) !isEmpty(current) else item?.material != material) continue
            val count = if (empty) 0 else requireNotNull(item).amount
            val added = minOf(remaining, (limit - count).coerceAtLeast(0).toLong()).toInt()
            if (added > 0) snapshot.after[slot] = stack(material, count + added)
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

    private fun locked(inventory: Inventory): Boolean = holderPositions(inventory.holder).any { it in locked }
    private fun holderPositions(holder: InventoryHolder?): List<Key> = when (holder) {
        is DoubleChest -> holderPositions(holder.leftSide) + holderPositions(holder.rightSide)
        is BlockState -> listOf(Key(holder.world.uid, CivPosition(holder.x, holder.y, holder.z)))
        else -> emptyList()
    }
    private fun locked(block: Block): Boolean = Key(block.world.uid, CivPosition(block.x, block.y, block.z)) in locked

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onOpen(event: InventoryOpenEvent) { if (locked(event.inventory)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onClick(event: InventoryClickEvent) { if (locked(event.view.topInventory)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDrag(event: InventoryDragEvent) { if (locked(event.view.topInventory)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onMove(event: InventoryMoveItemEvent) { if (locked(event.source) || locked(event.destination)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onPickup(event: InventoryPickupItemEvent) { if (locked(event.inventory)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBreak(event: BlockBreakEvent) { if (locked(event.block)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBlockExplosion(event: BlockExplodeEvent) { event.blockList().removeIf(::locked) }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onEntityExplosion(event: EntityExplodeEvent) { event.blockList().removeIf(::locked) }

    override fun close() {
        checkMain()
        if (closed) return
        closed = true
        HandlerList.unregisterAll(this)
        claims.keys.toList().forEach(::unlock)
    }
    private fun requireOpen() = check(!closed) { "CivsStorage is closed" }
    private fun checkMain() = check(Bukkit.isPrimaryThread()) { "CivsStorage requires Paper's main thread" }
    private fun reject(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
}
