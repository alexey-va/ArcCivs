package ru.arc.civs

import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.civs.CivStorageSnapshot as Snapshot
import ru.arc.civs.CivStorageSource as Source
import ru.arc.civs.CivsStorageItems.debit
import ru.arc.civs.CivsStorageItems.insert
import ru.arc.civs.CivsStorageItems.isEmpty
import ru.arc.civs.CivsStorageItems.plain
import ru.arc.civs.CivsStorageItems.plan
import ru.arc.civs.CivsStorageItems.putAmount
import ru.arc.civs.CivsStorageItems.snapshot
import ru.arc.civs.CivsStorageItems.stack
import ru.arc.civs.CivsStorageItems.validateAmounts
import java.util.UUID

private const val MATERIALIZATION_CONTAINERS = 128
private const val BACKGROUND_DEBIT_CONTAINERS = 128

/** Plans ordinary stock transfers against one frozen claim; all native writes belong to reconciliation. */
internal class CivsStoragePlanner(
    private val containers: CivsStorageContainers,
    private val checkReady: () -> Unit,
    private val checkClaim: (Settlement) -> Unit,
    private val claimedPlayer: (UUID) -> UUID?,
    private val residentFoods: () -> CivsResidentFoods,
) {
    private data class ResidentReturnCursor(val buildingId: UUID, val next: Int)
    private class DebitSearch(val worldId: UUID, val owner: UUID, val required: Map<String, Long>, val buildings: Set<UUID>?) {
        var positions: Map<CivPosition, String?> = emptyMap()
        var ordered: List<CivPosition> = emptyList()
        var cursor = 0
        var candidates: Set<CivPosition> = emptySet()
    }
    private val residentReturnCursors = mutableMapOf<UUID, MutableMap<UUID, ResidentReturnCursor>>()
    private val materializationCursors = mutableMapOf<UUID, Int>()
    private val debitSearches = mutableMapOf<UUID, DebitSearch>()

    fun clear() { materializationCursors.clear(); residentReturnCursors.clear(); debitSearches.clear() }
    fun removeCity(cityId: UUID) {
        materializationCursors.remove(cityId)
        residentReturnCursors.remove(cityId)
        debitSearches.remove(cityId)
    }
    fun updateCity(city: Settlement) {
        residentReturnCursors[city.id]?.let { cursors ->
            val assignments = city.workers.associate { it.id to it.assignment }
            cursors.entries.removeIf { (id, cursor) -> assignments[id] != cursor.buildingId }
            if (cursors.isEmpty()) residentReturnCursors.remove(city.id)
        }
    }

    fun debug(cityId: UUID?): Map<String, Any?> {
        fun <T> sample(values: Collection<T>, describe: (T) -> Any?): Map<String, Any?> = mapOf(
            "total" to values.size, "truncated" to (values.size > 8), "entries" to values.take(8).map(describe))
        return mapOf("debitSearches" to sample(debitSearches.entries.filter { cityId == null || it.key == cityId }) { (id, search) ->
            mapOf("city" to id.toString(), "world" to search.worldId.toString(), "cursor" to search.cursor,
                "positions" to search.positions.size, "ordered" to search.ordered.size,
                "candidates" to search.candidates.size, "buildings" to search.buildings?.size,
                "required" to sample(search.required.entries) { mapOf("material" to it.key, "amount" to it.value) }) },
            "materializationCursors" to sample(materializationCursors.entries.filter { cityId == null || it.key == cityId }) {
                mapOf("city" to it.key.toString(), "cursor" to it.value) })
    }

    fun available(city: Settlement): Map<String, Long> {
        checkReady()
        val inventory = requireNotNull(city.inventory).validated()
        val totals = mutableMapOf<String, Long>()
        if (inventory.mode == CivStorageMode.PHYSICAL) containers.sources(city).forEach { source ->
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
        checkReady()
        val inventory = requireNotNull(city.inventory).validated()
        if (city.building(buildingId) == null) throw CivsRejection("building-not-found")
        if (inventory.mode == CivStorageMode.VIRTUAL) return inventory.buildingStock(buildingId).toSortedMap()
        val totals = mutableMapOf<String, Long>()
        containers.localSources(city, buildingId).forEach { source -> source.inventory.storageContents.forEach { item ->
            plain(item)?.let { totals.merge(it.material, it.amount.toLong(), Math::addExact) }
        } }
        return totals.toSortedMap()
    }

    fun constructionAvailable(city: Settlement, buildingId: UUID): Map<String, Long> {
        checkReady()
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
        val positions = if (buildings == null) containers.positions(city) else
            buildings.flatMap { containers.buildingPositions(city, it).entries }.associate { it.key to it.value }
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
            try { containers.resolve(city, position, positions[position]) } catch (failure: CivsRejection) {
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
            debit((physicalSources ?: containers.sources(city)).map { snapshot(it).also(snapshots::add) }, missing, candidate)
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
        val physical = if (city.inventory?.mode == CivStorageMode.PHYSICAL) containers.localSources(city, buildingId) else null
        return prepareDebit(city, required, physical, null, buildingId)
    }

    fun prepareCargoPickup(city: Settlement, cargo: CivCargo): CivStoragePlan {
        require(cargo.amount in 1L..64L)
        reject(cargo.sourceWorkerId != null, "stale-delivery")
        return prepareBuildingDebit(city, cargo.sourceBuildingId, mapOf(cargo.material to cargo.amount))
    }

    /** Free native cargo space excludes named or otherwise custom stacks. */
    fun cargoRoom(city: Settlement, buildingId: UUID, material: String): Long {
        checkReady()
        if (city.inventory?.mode == CivStorageMode.VIRTUAL) return warehouseSpace(city, buildingId).coerceAtMost(CivsLogistics.MAX_CARGO)
        val maximum = requireNotNull(stack(material, 1)).maxStackSize
        var room = 0L
        for (source in containers.localSources(city, buildingId).filter { it.acceptsInsert }) for (current in source.inventory.storageContents) {
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

    /** Return only fitting ordinary bag surplus to this worker's own workplace in one receipt. */
    fun prepareResidentReturn(city: Settlement, workerId: UUID, buildingId: UUID,
        craftInputs: Map<String, Long> = emptyMap()): CivResidentTransferPlan {
        checkClaim(city)
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        reject(city.inventory == null || worker.assignment != buildingId, "invalid-assignment")
        val offered = CivsLogistics.workerExcess(city, worker, craftInputs, residentFoods())
        val inventory = requireNotNull(city.inventory)
        if (offered.isEmpty()) return CivResidentTransferPlan(CivStoragePlan(inventory, emptyList()), worker)
        val snapshots = if (inventory.mode == CivStorageMode.PHYSICAL) {
            // Select from cached metadata before resolving any native inventory, then rotate past full windows.
            val positions = containers.buildingPositions(city, buildingId).entries.toList()
            if (positions.isEmpty()) emptyList() else {
                val cursors = residentReturnCursors.getOrPut(city.id, ::mutableMapOf)
                val start = Math.floorMod(cursors[worker.id]?.takeIf { it.buildingId == buildingId }?.next ?: 0, positions.size)
                val count = minOf(BACKGROUND_DEBIT_CONTAINERS, positions.size)
                val window = (0 until count).asSequence().map { offset ->
                    positions[(start + offset) % positions.size].let { it.key to it.value }
                }
                cursors[worker.id] = ResidentReturnCursor(buildingId, (start + count) % positions.size)
                containers.resolveSources(city, window).map(::snapshot).toList()
            }
        } else emptyList()
        var virtual = inventory
        val headroom = productionOutputHeadroom(city.building(buildingId)?.type)
        val moved = offered.mapValues { (material, count) ->
            if (inventory.mode == CivStorageMode.PHYSICAL) count - insert(snapshots, material, count, reservedEmptySlots = headroom)
            else minOf(count, warehouseSpace(city.copy(inventory = virtual), buildingId)).also { amount ->
                if (amount > 0) virtual = creditVirtual(city.copy(inventory = virtual), material, amount, buildingId).inventoryAfter
            }
        }.filterValues { it > 0 }.toMutableMap()
        val items = CivsEquipment.slots(worker).toMutableList()
        for (slot in CivsEquipment.BAG until items.size) {
            val item = items[slot]?.takeIf { it.data == null } ?: continue
            val count = minOf(item.amount.toLong(), moved[item.material] ?: 0).toInt()
            if (count == 0) continue
            items[slot] = item.copy(amount = item.amount - count).takeIf { it.amount > 0 }
            moved[item.material] = moved.getValue(item.material) - count
        }
        check(moved.values.all { it == 0L })
        return CivResidentTransferPlan(if (inventory.mode == CivStorageMode.PHYSICAL) plan(inventory, snapshots)
            else CivStoragePlan(virtual, emptyList()), CivsEquipment.replace(worker, items))
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
        val mine = work?.takeIf { it.miningSections != null || CivsMineFallback.matches(it) }?.let {
            require(it.buildingId == buildingId && it.changes == worldChanges && !CivsAutonomy.legacyStock(city))
            CivMineReceipt(building, city.minePlans?.get(buildingId.toString()),
                city.mineProgress?.get(buildingId.toString()), it,
                workerBefore = if (CivsMineFallback.matches(it)) city.workers.single { worker -> worker.id == it.workerId } else null).detached()
        }
        val inventory = requireNotNull(city.inventory)
        if (inventory.mode == CivStorageMode.VIRTUAL) {
            var after = prepareBuildingDebit(city, buildingId, inputs).inventoryAfter
            outputs.forEach { (material, amount) -> after = creditVirtual(city.copy(inventory = after), material, amount, buildingId).inventoryAfter }
            return CivStoragePlan(after, emptyList(), worldChanges = worldChanges.toList(), mine = mine)
        }
        val sources = containers.localSources(city, buildingId).iterator()
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
        val headroom = productionOutputHeadroom(building.type)
        outputs.forEach { (material, amount) ->
            reject(insert(snapshots(), material, amount, reservedEmptySlots = headroom) != 0L, "storage-full")
        }
        return plan(inventory, captured).copy(worldChanges = worldChanges.toList(), mine = mine)
    }

    private fun prepareBuildingCredit(city: Settlement, buildingId: UUID, material: String, count: Long): CivStoragePlan {
        checkClaim(city)
        validateAmounts(mapOf(material to count))
        if (city.building(buildingId) == null) throw CivsRejection("building-not-found")
        if (city.inventory?.mode == CivStorageMode.VIRTUAL) return creditVirtual(city, material, count, buildingId)
        val prepared = mutableListOf<Snapshot>()
        reject(insert(containers.localSources(city, buildingId).map { snapshot(it).also(prepared::add) }, material, count) != 0L, "storage-full")
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
        val snapshots = containers.sources(city, buildingId).map { snapshot(it).also(prepared::add) }
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
        require(claimedPlayer(city.id) == player?.uniqueId) { "Player supply does not match its inventory lock" }
        if (player != null) reject(!player.isOnline || !city.canManage(player.uniqueId), "not-authorized")
        if (required.isEmpty()) return CivStorageSupplyPlan(CivStoragePlan(inventory, emptyList()), CivsSupplyResult(0, 0, false))
        val snapshots = containers.localSources(city, buildingId).map(::snapshot).toList()
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

    /** The same grant planner, restricted to current Auto demands and rechecked native modification authority. */
    internal fun prepareAutoSupply(city: Settlement, demand: CivAutoSupplyDemand,
        canModify: (CivPosition) -> Boolean): CivStoragePlan? {
        reject(city.autonomy?.paused != false || !CivsAutoStorageMigration.ready(city), "not-authorized")
        // ponytail: use the existing row-search ceiling; oversized admin-authored storage waits for a sliced planner.
        if (containers.buildingPositions(city, demand.buildingId).size > BACKGROUND_DEBIT_CONTAINERS)
            throw CivsRejection("storage-unavailable", mapOf("reason" to "auto-supply-container-bound"))
        val prepared = prepareConstructionSupply(city, demand.buildingId, demand.required)
        prepared.plan.changes.forEach { reject(!canModify(it.container), "lands-denied") }
        return prepared.plan.takeIf { it.changes.isNotEmpty() }?.copy(autoSupplyBuildingId = demand.buildingId)
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
        val sharedDestinations = containers.buildingPositions(city, depot.id)
        val buildingDestinations = warehouses.keys.associateWith { id ->
            if (city.autonomy != null) containers.buildingPositions(city, UUID.fromString(id)).keys
            else containers.buildingPositions(city, UUID.fromString(id), controlOnly = true).keys + sharedDestinations.keys
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
                        snapshot(containers.resolve(city, position, type))
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
        val positions = containers.positions(city).filterKeys { it in eligible }
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
        val snapshot = snapshot(containers.control(city, building))
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
        val snapshots = listOf(snapshot(containers.control(city, building)))
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

    private fun reject(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
}
