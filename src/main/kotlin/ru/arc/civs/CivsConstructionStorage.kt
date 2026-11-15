package ru.arc.civs

import com.google.gson.Gson
import org.bukkit.block.BlockState
import org.bukkit.block.Chest
import org.bukkit.block.Container
import java.util.Base64
import java.util.UUID

/** The accepted design and its original authority stay pinned while any native goods are in custody. */
data class CivConstructionStorageOwner(val cityId: UUID, val worldId: UUID, val original: CivBuilding,
    val target: CivBuilding, val schematicSha256: String) {
    fun matches(city: Settlement): Boolean = city.id == cityId && city.worldId == worldId &&
        city.building(original.id)?.let { building ->
            listOf(original, target).any { it.id == building.id && it.type == building.type &&
                it.level == building.level && it.schematicId == building.schematicId &&
                it.anchor == building.anchor && it.rotation == building.rotation && it.bounds == building.bounds }
        } == true

    fun held(city: Settlement): Boolean = !returningForDeletion(city) && (city.construction?.let { it.buildingId == target.id &&
        it.targetLevel == target.level && civsSameBuildingGeometry(it.plannedBuilding, target) } == true ||
        city.building(original.id)?.let { it.level == original.level && it.schematicId == original.schematicId &&
            it.id in city.constructionQueue.orEmpty() } == true)

    fun returningForDeletion(city: Settlement) = city.pendingDeletion?.let { it.buildingId == null || it.buildingId == original.id } == true

    fun validate() {
        require(SHA.matches(schematicSha256) && original.id == target.id &&
            target.phase == BuildingPhase.CONSTRUCTING && original.rotation == target.rotation &&
            original.entrance == target.entrance && civsContainsBox(target.bounds, original.bounds))
        require(original.type == target.type && (original.phase == BuildingPhase.ACTIVE && target.level == original.level + 1 ||
            original.phase == BuildingPhase.SITE && target.level == original.level) ||
            CivsPlanBoardBlueprint.refit(original, target) || CivsInfrastructureRebuild.refit(original, target))
    }
    /** An external footprint or a SITE could only have admitted this nonempty holder by explicit registration. */
    fun cancelledRegisteredSource(city: Settlement, cell: CivPlanBlock): CivPosition? {
        val p = cell.position
        val inventory = city.inventory ?: return null
        if (inventory.constructionBuffers?.get(original.id.toString())?.parts.orEmpty().none { it.cell == cell } ||
            !civsSameBuildingGeometry(city.building(original.id), original) || held(city) || !city.contains(p.x, p.z) ||
            original.phase == BuildingPhase.ACTIVE && !original.type.isInfrastructure && civsRelocationLocal(original, p) ||
            p !in inventory.containers && inventory.containers.size >= CivInventory.MAX_CONTAINERS ||
            !civsConstructionStorageFootprint(city, original, target, p) ||
            city.construction?.plannedBuilding?.let { civsRelocationLocal(it, p) } == true ||
            city.pendingControls.orEmpty().any { it.position == p || it.retire?.position == p || it.approach.orEmpty().any { c -> c.position == p } })
            return null
        return p
    }

    companion object {
        internal val SHA = Regex("[0-9a-f]{64}")

        internal fun forPlan(city: Settlement, original: CivBuilding, plan: CivPlacementPlan): CivConstructionStorageOwner {
            val sha = requireNotNull(plan.capturedSha256)
            val held = city.inventory?.constructionBuffers?.get(original.id.toString())?.owner
                ?: return CivConstructionStorageOwner(city.id, city.worldId, original, plan.building, sha)
            require(held.cityId == city.id && held.worldId == city.worldId && held.schematicSha256 == sha &&
                civsSameBuildingGeometry(held.original, original) && civsSameBuildingGeometry(held.target, plan.building))
            return held
        }
    }
}

/** One exact 27-slot block inventory. A double chest has two independent native owners. */
data class CivConstructionStorageSnapshot(val id: UUID, val owner: CivConstructionStorageOwner,
    val cell: CivPlanBlock, val items: List<CivResidentItem?>, val planId: UUID) {
    fun validate() {
        owner.validate()
        requireNotNull(planId)
        require(items.size == STORAGE_SLOTS && items.any { it != null })
        require(civsRetainContainerState(cell.before) && cell.after.length in 1..CivsStore.MAX_BLOCK_STATE_CHARS &&
            cell.after.substringBefore('[') !in setOf("minecraft:structure_void", "minecraft:barrier") &&
            !civsSameRelocationContainerState(cell.before, cell.after))
        require(owner.target.bounds.contains(cell.position.x, cell.position.y, cell.position.z) &&
            cell.position != owner.original.control && cell.position != owner.target.control)
        items.filterNotNull().forEach { it.validatedConstruction() }
    }
    fun encoded(): ByteArray = Gson().toJson(this).toByteArray(Charsets.UTF_8).also {
        require(it.size <= CivsStore.MAX_INVENTORY_TRANSACTION_BYTES)
    }
    fun initial(bytes: ByteArray = encoded()): CivConstructionStoragePart = balance(CivsGateRefit.sha256(bytes), items)

    fun balance(sha: String, remaining: List<CivResidentItem?>): CivConstructionStoragePart {
        val plain = sortedMapOf<String, Long>()
        val custom = mutableListOf<Byte>()
        items.forEachIndexed { i, original ->
            val item = remaining[i]
            if (original?.data != null) custom += (item?.amount ?: 0).toByte()
            else if (item != null) plain.merge(item.material, item.amount.toLong(), Math::addExact)
        }
        return CivConstructionStoragePart(id, sha, cell, plain, Base64.getEncoder().encodeToString(custom.toByteArray()))
    }

    /** Plain stacks are fungible within their original holder. Custom rows retain their exact identity and slot. */
    fun contents(part: CivConstructionStoragePart): List<CivResidentItem?> {
        require(id == part.id)
        val plain = part.plain.toMutableMap()
        val custom = Base64.getDecoder().decode(part.custom)
        var next = 0
        val rows = items.map { item ->
            if (item == null) return@map null
            val count = if (item.data != null) (custom[next++].toInt() and 255) else {
                val left = plain[item.material] ?: 0
                val take = minOf(left, item.amount.toLong())
                plain[item.material] = left - take
                take.toInt()
            }
            require(count in 0..item.amount)
            item.copy(amount = count).takeIf { count > 0 }
        }
        require(next == custom.size && plain.values.all { it == 0L } && balance(part.sha256, rows) == part)
        return rows
    }
}

data class CivConstructionStoragePart(val id: UUID, val sha256: String, val cell: CivPlanBlock, val plain: Map<String, Long>, val custom: String) {
    fun empty(): Boolean = plain.isEmpty() && Base64.getDecoder().decode(custom).all { it.toInt() == 0 }
    fun validate() {
        require(CivConstructionStorageOwner.SHA.matches(sha256) && plain.size <= STORAGE_SLOTS &&
            plain.all { it.key.matches(Regex("[A-Z][A-Z0-9_]{0,63}")) && it.value in 1..(STORAGE_SLOTS * 99L) } &&
            custom.length <= 36 && Base64.getDecoder().decode(custom).size <= STORAGE_SLOTS && !empty())
    }
}

data class CivConstructionStorageBuffer(val owner: CivConstructionStorageOwner, val parts: List<CivConstructionStoragePart>)

internal fun CivInventory.updatePart(owner: CivConstructionStorageOwner, before: CivConstructionStoragePart?,
    after: CivConstructionStoragePart?): CivInventory {
    val id = owner.original.id.toString()
    val buffers = constructionBuffers.orEmpty().toMutableMap()
    val existing = buffers[id]
    require(existing == null || existing.owner == owner)
    val key = (before ?: requireNotNull(after)).id
    require(existing?.parts.orEmpty().singleOrNull { it.id == key } == before)
    require(after == null || after.id == key && (before == null || before.sha256 == after.sha256))
    val parts = (existing?.parts.orEmpty().filterNot { it.id == key } + listOfNotNull(after)).sortedBy { it.id }
    if (parts.isEmpty()) buffers.remove(id) else buffers[id] = CivConstructionStorageBuffer(owner, parts)
    return copy(constructionBuffers = buffers.takeIf { it.isNotEmpty() })
}

internal fun CivInventory.bufferStock(buildingId: UUID): Map<String, Long> {
    val totals = mutableMapOf<String, Long>()
    constructionBuffers?.get(buildingId.toString())?.parts.orEmpty().forEach { part ->
        part.plain.forEach { (material, count) -> totals.merge(material, count, Math::addExact) }
    }
    return totals
}

internal fun CivInventory.takeBuffered(buildingId: UUID, required: Map<String, Long>): Pair<CivInventory, Map<String, Long>> {
    val group = constructionBuffers?.get(buildingId.toString()) ?: return this to required
    var result = this
    val need = required.toMutableMap()
    for (part in group.parts) {
        val plain = part.plain.toMutableMap()
        for ((material, count) in part.plain) {
            val taken = minOf(count, need[material] ?: 0L)
            if (taken == 0L) continue
            if (count == taken) plain.remove(material) else plain[material] = count - taken
            if (need[material] == taken) need.remove(material) else need[material] = need.getValue(material) - taken
        }
        if (plain != part.plain) {
            val next = part.copy(plain = plain)
            result = result.updatePart(group.owner, part, next.takeUnless { it.empty() })
        }
    }
    return result to need
}

data class CivConstructionStorageMove(val owner: CivConstructionStorageOwner, val before: CivConstructionStoragePart?,
    val after: CivConstructionStoragePart?, val cell: CivPlanBlock) {
    fun validateSnapshot(snapshot: CivConstructionStorageSnapshot, holders: List<CivRelocatedContainer>) {
        require(snapshot.owner == owner && snapshot.cell == cell)
        val initial = snapshot.initial()
        val totals = mutableMapOf<Pair<String, String?>, Long>()
        fun count(item: CivResidentItem?, sign: Int) {
            if (item != null) totals.merge(item.material to item.data, sign * item.amount.toLong(), Math::addExact)
        }
        if (before == null) {
            require(after == initial && holders.size == 1)
            val holder = holders.single()
            require(holder.position == cell.position && holder.blockData == cell.before && holder.before == snapshot.items &&
                holder.after.all { it == null })
        } else {
            require(initial.sha256 == before.sha256 && before.id == snapshot.id)
            val oldRows = snapshot.contents(before)
            val newRows = after?.let { require(it.sha256 == before.sha256); snapshot.contents(it) } ?: List(STORAGE_SLOTS) { null }
            require(oldRows != newRows && (after == null || after.plain.all { it.value <= before.plain.getOrDefault(it.key, 0) } &&
                Base64.getDecoder().decode(after.custom).zip(Base64.getDecoder().decode(before.custom)).all {
                    (it.first.toInt() and 255) <= (it.second.toInt() and 255) }))
            oldRows.forEach { count(it, -1) }; newRows.forEach { count(it, 1) }
            holders.forEach { h -> h.before.forEach { count(it, -1) }; h.after.forEach { count(it, 1) } }
            require(totals.values.all { it == 0L }) { "Construction storage must conserve every native component" }
        }
    }
}

internal fun civsConstructionStorageOwns(city: Settlement, original: CivBuilding?, target: CivBuilding, p: CivPosition,
    acceptedEmptyHolder: Boolean = false): Boolean {
    if (!civsConstructionStorageFootprint(city, original, target, p)) return false
    return acceptedEmptyHolder && original != null || p in city.inventory?.containers.orEmpty() ||
        original?.phase == BuildingPhase.ACTIVE && !original.type.isInfrastructure && original.bounds.contains(p.x, p.y, p.z)
}

private fun civsConstructionStorageFootprint(city: Settlement, original: CivBuilding?, target: CivBuilding, p: CivPosition) =
    target.bounds.contains(p.x, p.y, p.z) && p != target.control && p != original?.control &&
        city.buildings.none { it.id != target.id && (it.control == p || it.bounds.contains(p.x, p.y, p.z)) }

/** A source holder is never consumed merely because it lies under a proposed new footprint. */
internal fun civsStorageChangingCell(cell: CivPlanBlock): Boolean = civsRetainContainerState(cell.before) &&
    cell.after.substringBefore('[') !in setOf("minecraft:barrier", "minecraft:structure_void") &&
    !civsSameRelocationContainerState(cell.before, cell.after)

internal data class CivConstructionStorageBatch(val holder: CivRelocatedContainer, val cell: CivPlanBlock,
    val retirement: CivUpgradeContainerRetirement?)

internal fun civsConstructionReturnAllowed(city: Settlement, building: CivBuilding, p: CivPosition): Boolean {
    if (city.pendingControls.orEmpty().any { it.retire != null && (p == it.position || p == it.retire.position) }) return false
    if (city.pendingDeletion == null && city.construction?.let { order -> listOfNotNull(city.building(order.buildingId), order.plannedBuilding).any {
            civsRelocationLocal(it, p)
        } } == true) return false
    if (city.pendingDeletion?.let { deletion -> city.buildings.any { it.control == p && (deletion.buildingId == null || deletion.buildingId == it.id) } } == true) return false
    if (civsRelocationLocal(building, p)) return p == building.control ||
        building.phase == BuildingPhase.ACTIVE && !building.type.isInfrastructure || p in city.inventory?.containers.orEmpty()
    return civsRelocationExternalReceiver(city, building, p) && city.buildings.any { CivsLogistics.nativeCargoDepot(it) && civsRelocationLocal(it, p) } &&
        city.buildings.none { it.id in city.constructionQueue.orEmpty() && civsRelocationLocal(it, p) }
}

internal fun CivConstructionStorageBuffer.detached() = copy(parts = parts.map { it.copy(plain = it.plain.toMap()) })

internal fun CivInventory.sourceStock(buildingId: UUID, available: Map<String, Long>): Map<String, Long> {
    if (constructionBuffers?.containsKey(buildingId.toString()) != true) return available
    return available.toMutableMap().also { sum -> bufferStock(buildingId).forEach { (m, q) -> sum.merge(m, q, Math::addExact) } }
}

/** A proven ordinary trip: only a named resident bag may receive a plain debit from this building's buffer. */
data class CivConstructionStoragePickup(val source: UUID, val workerBefore: CivWorker, val inventoryBefore: CivInventory,
    val withdrawn: Map<String, Long>, val cargo: CivCargo?, val containers: List<CivCargoContainerState> = emptyList()) {
    val beforeGroup: CivConstructionStorageBuffer get() = inventoryBefore.constructionBuffers!!.getValue(source.toString())
    fun before(after: Settlement): Settlement = after.copy(revision = after.revision - 1, inventory = inventoryBefore,
        workers = after.workers.map { if (it.id == workerBefore.id) workerBefore else it })

    fun validate(after: Settlement, changes: List<CivInventorySlotChange>) {
        val positions = containers.map { it.position }
        require(positions == positions.distinct() && positions.toSet() == changes.map { it.container }.toSet() &&
            positions.map { (it.x shr 4) to (it.z shr 4) }.distinct().size <= 8 && containers.all { civsRetainContainerState(it.blockData) })
        val city = before(after)
        CivsRules.validate(city)
        require(withdrawn.isNotEmpty() && withdrawn.values.all { it > 0 } && beforeGroup.owner.matches(city))
        val (debit, missing) = inventoryBefore.takeBuffered(source, withdrawn)
        val inventory = requireNotNull(after.inventory)
        require(missing.isEmpty() && debit.constructionBuffers == inventory.constructionBuffers &&
            debit.copy(buildingMaterials = inventory.buildingMaterials) == inventory)
        val building = requireNotNull(city.building(source))
        val bag = after.workers.single { it.id == workerBefore.id }
        if (cargo != null) {
            require(!cargo.redistribution && cargo.sourceWorkerId == null && cargo.stockResource == null &&
                cargo.sourceBuildingId == source && workerBefore.cargo == null &&
                CivsLogistics.pickup(city, workerBefore.id, cargo).copy(inventory = inventory) == after)
        } else {
            require(workerBefore.cargo == null && (workerBefore.assignment == source ||
                (building.type == BuildingType.TAVERN || building.type == BuildingType.TOWN_HALL) && withdrawn.keys == setOf("BREAD")) &&
                workerBefore.copy(items = bag.items, inventory = bag.inventory) == bag)
        }
        val totals = mutableMapOf<Pair<String, String?>, Long>()
        fun add(m: String, data: String?, amount: Long) { totals.merge(m to data, amount, Math::addExact) }
        CivsEquipment.slots(workerBefore).filterNotNull().forEach { add(it.material, it.data, -it.amount.toLong()) }
        CivsEquipment.slots(bag).filterNotNull().forEach { add(it.material, it.data, it.amount.toLong()) }
        withdrawn.forEach { (m, count) -> add(m, null, -count) }
        if (inventory.mode == CivStorageMode.PHYSICAL) require(inventory.buildingMaterials == debit.buildingMaterials)
        else {
            require(changes.isEmpty() && inventory.buildingMaterials.orEmpty().filterKeys { it != source.toString() } ==
                debit.buildingMaterials.orEmpty().filterKeys { it != source.toString() })
            debit.buildingStock(source).forEach { (m, count) -> add(m, null, -count) }
            inventory.buildingStock(source).forEach { (m, count) -> add(m, null, count) }
        }
        changes.forEach {
            require(civsRelocationLocal(building, it.container) && city.buildings.none { other ->
                other.id != source && other.control == it.container
            })
            it.before?.let { item -> add(item.material, null, -item.amount.toLong()) }
            it.after?.let { item -> add(item.material, null, item.amount.toLong()) }
        }
        require(totals.values.all { it == 0L }) { "Native stock, construction buffer and carried items must balance" }
    }
}


/** A neighbouring chest half keeps its independent inventory during a construction replacement. */
internal fun civsContainerHasItems(state: BlockState?): Boolean = when (state) {
    is Chest -> !state.blockInventory.isEmpty
    is Container -> !state.inventory.isEmpty
    else -> false
}
