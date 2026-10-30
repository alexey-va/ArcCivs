package ru.arc.civs

import java.util.UUID

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
    val worldChanges: List<CivPlanBlock>? = null, val mine: CivMineReceipt? = null,
    val autoSupplyBuildingId: UUID? = null, val cargoDropoff: CivCargoDropoffReceipt? = null,
    val cargoPickup: CivCargoPickupReceipt? = null)
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
    /** Explicit native issuance; absent in older receipts and ordinary transfers/production. */
    val autoSupplyBuildingId: UUID? = null,
    /** Exact internal container move; absent in all older receipts. */
    val containerRelocation: CivContainerRelocation? = null,
    /** Native cargo conservation and destination proof; absent in every historical receipt. */
    val cargoDropoff: CivCargoDropoffReceipt? = null,
    /** Exact native depot evacuation debit; absent from historical and ordinary pickups. */
    val cargoPickup: CivCargoPickupReceipt? = null,
) {
    fun validated(): CivInventoryTransaction = apply {
        require(id != null && cityId != null && after != null && changes != null)
        require(beforeRevision in 0 until Long.MAX_VALUE && after.id == cityId && after.revision == beforeRevision + 1)
        CivsRules.validate(after)
        require(after.inventory != null || resurrection != null || collected != null || forestry != null || upkeep != null ||
            autonomousVisual != null || containerRelocation != null || after.autonomy != null && residentTransfer != null)
        require(listOfNotNull(playerDebit, residentTransfer, resurrection, collected, forestry, upkeep, autonomousVisual, mine,
            autoSupplyBuildingId, containerRelocation, cargoDropoff, cargoPickup).size <= 1)
        containerRelocation?.let {
            require(changes.isEmpty() && worldChanges.orEmpty().isEmpty())
            it.validated(after)
        }
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
            require(worldChanges.orEmpty() == it.work.changes)
            it.validated(after, changes)
        }
        autonomousVisual?.let {
            require(changes.isEmpty() && worldChanges.orEmpty() == it.work.changes)
            it.validated(after)
        }
        mine?.let {
            require(after.inventory != null && !CivsAutonomy.legacyStock(after) && worldChanges.orEmpty() == it.work.changes &&
                it.work.forestry == null && it.work.upkeep == null)
            if (it.workerBefore != null) require(changes.isEmpty() && worldChanges.orEmpty().isEmpty())
            it.validated(after)
        }
        cargoDropoff?.let {
            require(worldChanges.isNullOrEmpty())
            it.validated(after, changes)
        }
        cargoPickup?.let {
            require(worldChanges.isNullOrEmpty())
            it.validated(after, changes)
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
            require(change != null && change.container != null && change.containerType in CIVS_STORAGE_TYPES && change.slot in 0 until STORAGE_SLOTS)
            require(storagePositionAllowed(after, change.container, change.containerType))
            require(change.before != change.after)
            change.before?.validated()
            change.after?.validated()
        }
        autoSupplyBuildingId?.let { buildingId ->
            require(after.autonomy != null && after.inventory?.mode == CivStorageMode.PHYSICAL &&
                changes.isNotEmpty() && worldChanges.isNullOrEmpty())
            val building = requireNotNull(after.building(buildingId))
            val added = linkedMapOf<String, Long>()
            changes.forEach { change ->
                require(change.container == building.control || building.phase == BuildingPhase.ACTIVE &&
                    building.bounds.contains(change.container.x, change.container.y, change.container.z))
                require(after.buildings.none { it.id != buildingId && it.control == change.container })
                val item = requireNotNull(change.after)
                require(change.before == null || change.before.material == item.material && change.before.amount < item.amount)
                added.merge(item.material, (item.amount - (change.before?.amount ?: 0)).toLong(), Math::addExact)
            }
            require(added.size <= 8 && added.values.all { it in 1..64L })
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
    workerBefore = workerBefore?.let { it.copy(items = it.items?.toList(), inventory = it.inventory?.toMap()) },
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
internal const val STORAGE_SLOTS = 27
internal const val PLAYER_STORAGE_SLOTS = 36
internal val CIVS_STORAGE_TYPES = setOf("CHEST", "TRAPPED_CHEST", "BARREL")

/** Recovery authorizes durable coordinates against geometry, independently of loaded chunks or the index. */
internal fun storagePositionAllowed(city: Settlement, position: CivPosition, type: String): Boolean =
    type in CIVS_STORAGE_TYPES && city.contains(position.x, position.z) && position.y in -4_096..4_096 &&
        kotlin.math.abs(position.x.toLong()) <= 30_000_000 && kotlin.math.abs(position.z.toLong()) <= 30_000_000 &&
        (if (city.buildings.any { it.control == position }) type == "BARREL" else
            position in city.inventory?.containers.orEmpty() || city.buildings.any { it.bounds.contains(position.x, position.y, position.z) })
