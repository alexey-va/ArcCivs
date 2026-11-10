package ru.arc.civs

import org.bukkit.Material
import org.bukkit.entity.Item
import org.bukkit.plugin.Plugin
import ru.arc.civs.CivStorageSource as Source
import ru.arc.civs.CivsStorageItems.isEmpty
import ru.arc.civs.CivsStorageItems.plain
import java.util.UUID

/** Bounded resident collection, home delivery and food discovery under the current storage claim. */
internal class CivsResidentStorage(
    private val plugin: Plugin,
    private val containers: CivsStorageContainers,
    private val checkReady: () -> Unit,
    private val checkClaim: (Settlement) -> Unit,
    private val receiptBootstrap: () -> Boolean,
    private val discardConsumed: (Item) -> Boolean,
    private val residentFoods: () -> CivsResidentFoods,
    private val foodCanModify: (Settlement, CivPosition) -> Boolean,
) {
    private val homeCursors = mutableMapOf<UUID, Int>()
    private val foodCursors = mutableMapOf<UUID, MutableMap<UUID, Int>>()

    fun replaceCities() { foodCursors.clear() }
    fun updateCity(city: Settlement) {
        foodCursors[city.id]?.keys?.retainAll(city.workers.mapTo(hashSetOf(), CivWorker::id))
    }
    fun removeCity(cityId: UUID) { foodCursors.remove(cityId) }
    fun close() { homeCursors.clear(); foodCursors.clear() }

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
            reject(receiptBootstrap() || discardConsumed(item), "ground-item-consumed")
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

    /** Actual marked decay Items enter only this forester's indexed native holders. No resident bag or stock changes. */
    internal fun prepareForestryLeafLoot(city: Settlement, workerId: UUID, entityIds: List<UUID>): CivCollectedPlan {
        checkClaim(city)
        require(entityIds.size in 1..8 && entityIds.distinct().size == entityIds.size)
        val worker = city.workers.single { it.id == workerId }
        val building = city.building(worker.assignment ?: throw CivsRejection("invalid-assignment"))
            ?: throw CivsRejection("building-not-found")
        reject(city.inventory?.mode != CivStorageMode.PHYSICAL || building.type != BuildingType.FORESTER ||
            building.phase != BuildingPhase.ACTIVE || CivsResidents.isChild(worker), "invalid-assignment")
        val world = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("storage-unavailable")
        val positions = containers.buildingPositions(city, building.id).entries.toList()
        val cursor = (homeCursors[workerId] ?: 0).takeIf { it in positions.indices } ?: 0
        val ordered = positions.drop(cursor) + positions.take(cursor)
        if (positions.isNotEmpty()) homeCursors[workerId] = (cursor + 4) % positions.size
        val snapshots = containers.resolveSources(city, ordered.asSequence().take(4)
            .filter { foodCanModify(city, it.key) }.map { it.key to it.value }).filter { it.acceptsInsert }.map { source ->
            source to source.inventory.storageContents.map(CivResidentItem::of).toMutableList()
        }.toList()
        val originals = snapshots.associate { (source, slots) -> source.position to slots.toList() }
        val ground = mutableListOf<CivGroundItemChange>()
        val sources = mutableListOf<CivForestryLeafSource>()
        for (id in entityIds) {
            val item = world.getEntity(id) as? Item ?: throw CivsRejection("stale-delivery")
            reject(receiptBootstrap() || discardConsumed(item), "ground-item-consumed")
            val proof = CivsForestryLeafLoot.proof(item) ?: throw CivsRejection("forestry-leaf-source-missing")
            val source = proof.sources.single()
            reject(proof.buildingId != building.id || !CivsForestryLeafLoot.current(proof, city) ||
                item.owner != null || item.thrower != null || !civsGroundItemAvailable(item, worker.id) ||
                !city.contains(item.location.blockX, item.location.blockZ), "forestry-leaf-source-changed")
            val position = CivPosition(item.location.blockX, item.location.blockY, item.location.blockZ)
            reject(!foodCanModify(city, source.leaf) || !foodCanModify(city, position), "lands-denied")
            val before = requireNotNull(CivResidentItem.of(item.itemStack))
            reject(before.data != null || !CivsForestryLeafLoot.leafLoot(before.material), "forestry-leaf-item-changed")
            val maximum = item.itemStack.maxStackSize
            val room = snapshots.sumOf { (_, slots) -> slots.sumOf { slot ->
                if (slot == null) maximum else if (slot.material == before.material && slot.data == null) maximum - slot.amount else 0
            } }
            if (room < before.amount) continue
            var remaining = before.amount
            for ((_, slots) in snapshots) for (empty in listOf(false, true)) for (slot in slots.indices) {
                val previous = slots[slot]
                if (remaining == 0 || (previous == null) != empty || previous != null &&
                    (previous.material != before.material || previous.data != null)) continue
                val moved = minOf(remaining, maximum - (previous?.amount ?: 0))
                if (moved <= 0) continue
                slots[slot] = before.copy(amount = (previous?.amount ?: 0) + moved)
                remaining -= moved
            }
            check(remaining == 0)
            ground += CivGroundItemChange(id, position, before, null)
            sources += source
        }
        if (ground.isEmpty()) return CivCollectedPlan(worker, null)
        val containers = snapshots.flatMap { (source, slots) -> slots.indices.mapNotNull { slot ->
            val before = originals.getValue(source.position)[slot]
            slots[slot]?.takeIf { it != before }?.let { CivNativeContainerChange(source.position, source.type, slot, before, it) }
        } }
        return CivCollectedPlan(worker, CivCollectedTransfer(workerId, CivsEquipment.slots(worker), ground = ground,
            containers = containers, forestryLeafLoot = CivForestryLeafLootProof(city.id, city.owner, city.worldId, building.id, sources)))
    }

    /** Cached membership limits discovery to four real holders in this resident's own home. */
    private fun homePositions(city: Settlement, worker: CivWorker, destination: CivPosition? = null): Sequence<Pair<CivPosition, String?>> {
        val home = civsCollectionHome(city, worker) ?: return emptySequence()
        val positions = containers.buildingPositions(city, home.id)
        val ordered = linkedMapOf<CivPosition, String?>()
        destination?.takeIf { it in positions }?.let { ordered[it] = positions[it] }
        val entries = positions.entries.toList()
        val cursor = (homeCursors[worker.id] ?: 0).takeIf { it in entries.indices } ?: 0
        for (entry in entries.drop(cursor) + entries.take(cursor)) ordered[entry.key] = entry.value
        if (destination == null && entries.isNotEmpty()) homeCursors[worker.id] = (cursor + 4) % entries.size
        return ordered.asSequence().take(4).map { it.key to it.value }
    }

    private fun homeSources(city: Settlement, worker: CivWorker, destination: CivPosition): Sequence<Source> =
        containers.resolveSources(city, homePositions(city, worker, destination)).filter { it.acceptsInsert }

    internal fun homeDestination(city: Settlement, worker: CivWorker, reserve: Map<String, Long>): CivPosition? {
        checkReady()
        if (worker.hasCollectedItems != true || worker.cargo != null || CivsResidents.isChild(worker)) return null
        val surplus = CivsEquipment.homeSurplus(worker, reserve)
        if (surplus.isEmpty()) return null
        val home = civsCollectionHome(city, worker) ?: return null
        val world = plugin.server.getWorld(city.worldId) ?: return null
        val items = CivsEquipment.slots(worker)
        for ((point, type) in homePositions(city, worker)) {
            val snapshot = containers.planningContainer(city, home.id, point)?.takeIf { it.acceptsInsert } ?: continue
            if (!citizenChunkReady(world, point.x shr 4, point.z shr 4)) continue
            val room = snapshot.slots.any { it.material == null } || surplus.any { (index, _) ->
                val item = requireNotNull(items[index])
                snapshot.slots.any { it.material == item.material && it.plain && item.data == null && it.amount < it.stackSize }
            }
            // The detached supply view does not carry custom NBT. Only a possible custom merge needs exact similarity.
            val custom = !room && surplus.any { (index, _) ->
                val item = requireNotNull(items[index])
                item.data != null && snapshot.slots.any { it.material == item.material && !it.plain && it.amount < it.stackSize }
            }
            val matchingCustom = custom && containers.resolveSources(city, sequenceOf(point to type)).any { source ->
                source.acceptsInsert && source.inventory.storageContents.any { native ->
                    native != null && !isEmpty(native) && native.amount < native.maxStackSize && surplus.any { (index, _) ->
                        val item = requireNotNull(items[index])
                        item.data != null && native.type.name == item.material && native.isSimilar(item.stack())
                    }
                }
            }
            if ((room || matchingCustom) && citizenCollectedHomeTarget(city, home, world, point) != null) return point
        }
        return null
    }

    private data class FoodPosition(val building: CivBuilding, val point: CivPosition, val type: String?,
        val venueRank: Int, val distanceSquared: Double)

    /** Up to sixteen indexed holders, including the nearest holder of the preferred venue. */
    private fun foodPositions(city: Settlement, worker: CivWorker, from: org.bukkit.Location,
        reserve: Boolean = false): Sequence<FoodPosition> {
        val buildings = city.buildings.filter { CivsResidentFood.eligible(city, worker, it) &&
            (!reserve || CivsResidentFood.reserveBuilding(city, it)) }
        if (buildings.isEmpty()) return emptySequence()
        val membership = if (reserve) buildings.associate { it.id to containers.buildingPositions(city, it.id) }
            else containers.planningPositions(city, buildings)
        val positions = mutableListOf<FoodPosition>()
        val seen = hashSetOf<CivPosition>()
        for (building in buildings) {
            val venueRank = when {
                building.type == BuildingType.TAVERN -> 0
                !reserve || building.type == BuildingType.WAREHOUSE -> 1
                else -> 2
            }
            for ((point, type) in membership[building.id].orEmpty()) {
                if (!seen.add(point)) continue
                val dx = point.x + .5 - from.x
                val dy = point.y - from.y
                val dz = point.z + .5 - from.z
                positions += FoodPosition(building, point, type, venueRank, dx * dx + dy * dy + dz * dz)
            }
        }
        if (positions.isEmpty()) return emptySequence()
        positions.sortWith { left, right ->
            val byVenue = left.venueRank.compareTo(right.venueRank)
            if (byVenue == 0) left.distanceSquared.compareTo(right.distanceSquared) else byVenue
        }
        val rest = positions.size - 1
        val cursors = foodCursors.getOrPut(city.id, ::mutableMapOf)
        val cursor = if (rest == 0) 0 else Math.floorMod(cursors[worker.id] ?: 0, rest)
        val count = minOf(15, rest)
        if (rest > 0) cursors[worker.id] = (cursor + count) % rest
        // Keep the preferred venue fast while empty large venues cannot hide all emergency holders.
        return sequence {
            yield(positions.first())
            repeat(count) { offset -> yield(positions[1 + (cursor + offset) % rest]) }
        }
    }

    internal fun residentFoodSource(city: Settlement, worker: CivWorker, feet: org.bukkit.Location,
        tavernService: Boolean = false): CivResidentFoodSource? {
        checkReady()
        if (city.inventory?.mode == CivStorageMode.VIRTUAL || feet.world?.uid != city.worldId ||
            CivsResidents.hunger(worker) > 14 || !tavernService && residentFoods().carried(city, worker) != null) return null
        val world = plugin.server.getWorld(city.worldId) ?: return null
        for ((building, point) in foodPositions(city, worker, feet)) {
            if (tavernService && (building.type != BuildingType.TAVERN || building.phase != BuildingPhase.ACTIVE)) continue
            val stock = containers.planningContainer(city, building.id, point) ?: continue
            val material = stock.slots.asSequence().filter { it.plain }.mapNotNull { it.material }.firstOrNull {
                residentFoods().mayPickUp(city, worker, it) && CivsEquipment.supplyRoom(worker, Material.valueOf(it)) > 0
            } ?: continue
            if (!foodCanModify(city, point)) continue
            val target = citizenCollectedHomeTarget(city, building, world, point) ?: continue
            return CivResidentFoodSource(building.id, point, target, material)
        }
        return null
    }

    /** An existing Auto FOOD unit becomes one ordinary bread only in this exact native receipt. */
    internal fun prepareFoodMaterialization(city: Settlement, workerId: UUID, feet: org.bukkit.Location): CivCollectedPlan {
        checkClaim(city)
        val worker = city.workers.single { it.id == workerId }
        reject(city.autonomy?.paused != false || city.inventory != null || city.stock.food <= 0 ||
            feet.world?.uid != city.worldId, "resident-food-missing")
        for ((building, point, type) in foodPositions(city, worker, feet, reserve = true)) {
            if (!foodCanModify(city, point)) continue
            val source = containers.resolveSources(city, sequenceOf(point to type)).firstOrNull()?.takeIf { it.acceptsInsert }
                ?: continue
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
        reject((target.servingFor == null) != (target.servingTicket == null), "stale-meal")
        val worker = city.workers.single { it.id == workerId }
        val building = city.building(target.buildingId) ?: throw CivsRejection("building-not-found")
        reject(!CivsResidentFood.eligible(city, worker, building) ||
            target.container !in containers.buildingPositions(city, building.id) || !civsCanReachContainer(feet, target.container), "worker-too-far")
        reject(!foodCanModify(city, target.container), "lands-denied")
        val serving = target.servingFor?.let { guestId ->
            city.workers.firstOrNull { it.id == guestId }?.let { CivsTavernMeals.keeper(city, worker, it, building) } == true
        } ?: false
        reject(target.servingFor != null && !serving || !residentFoods().mayPickUp(city, worker, target.material) ||
            !serving && (residentFoods().carried(city, worker) != null || CivsResidents.hunger(worker) > 14) || worker.cargo != null,
            "resident-food-missing")
        val source = containers.resolve(city, target.container, containers.buildingPositions(city, building.id)[target.container])
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
            containers = changes, food = CivResidentFoodReceipt(building.id, city.stock, material = target.material,
                servingFor = target.servingFor, servingTicket = target.servingTicket)))
    }

    internal fun prepareHomeDeposit(city: Settlement, workerId: UUID, destination: CivPosition,
        feet: org.bukkit.Location, reserve: Map<String, Long>): CivCollectedPlan {
        checkClaim(city)
        val worker = city.workers.singleOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        reject(worker.hasCollectedItems != true || worker.cargo != null || CivsResidents.isChild(worker), "stale-delivery")
        val home = civsCollectionHome(city, worker) ?: throw CivsRejection("building-not-found")
        reject(destination !in containers.buildingPositions(city, home.id) || !civsCanReachContainer(feet, destination), "worker-too-far")
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

    private fun reject(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
}
