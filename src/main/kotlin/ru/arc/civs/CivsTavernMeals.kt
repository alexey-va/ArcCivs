package ru.arc.civs

import java.util.UUID
import java.util.concurrent.CompletableFuture

/** The observed service session fences one atomic transfer between two real resident bags. */
data class CivTavernDelivery(val ticket: UUID, val buildingId: UUID, val keeperId: UUID, val guestId: UUID,
    val keeperBefore: List<CivResidentItem?>, val guestBefore: List<CivResidentItem?>,
    val mealsBefore: Long, val material: String, val amount: Int)

/** One keeper owns one storage-to-chair leg; accepted items always remain in a durable bag. */
internal class CitizenTavernService(val ticket: UUID, val guestId: UUID, val source: CivResidentFoodSource,
    val startedAt: Long) {
    var loaded = false
    var target: CivPosition? = null
    var arrivedAt: Long? = null
    var observedAt = 0L
    var pickup: CompletableFuture<Settlement>? = null
    var delivery: CompletableFuture<Settlement>? = null
    var proof: CivTavernDelivery? = null
}

internal object CivsTavernMeals {
    fun keeper(city: Settlement, keeper: CivWorker, guest: CivWorker, building: CivBuilding): Boolean =
        city.pendingDeletion == null && city.autonomy?.paused != true && building.type == BuildingType.TAVERN &&
            building.phase == BuildingPhase.ACTIVE && building.control != null && keeper.assignment == building.id &&
            keeper.id != guest.id && !CivsResidents.isChild(keeper) && keeper.cargo == null && guest.cargo == null &&
            CivsResidents.hunger(guest) <= 14

    /** No native inventory changes: both before-bags and the meal counter prevent a replay. */
    fun deliver(city: Settlement, delivery: CivTavernDelivery,
        foods: CivsResidentFoods = CivsResidentFoods.BREAD_ONLY): Settlement {
        val keeper = city.workers.firstOrNull { it.id == delivery.keeperId } ?: throw CivsRejection("stale-meal")
        val guest = city.workers.firstOrNull { it.id == delivery.guestId } ?: throw CivsRejection("stale-meal")
        val building = city.building(delivery.buildingId) ?: throw CivsRejection("stale-meal")
        if (!keeper(city, keeper, guest, building) || delivery.amount !in 1..CivsResidentFood.BATCH ||
            foods.definition(delivery.material) == null || !foods.mayPickUp(city, guest, delivery.material) ||
            CivsEquipment.slots(keeper) != delivery.keeperBefore || CivsEquipment.slots(guest) != delivery.guestBefore ||
            (guest.life?.meals ?: 0L) != delivery.mealsBefore ||
            foods.available(city, keeper, delivery.material) < delivery.amount)
            throw CivsRejection("stale-meal")
        var remaining = delivery.amount
        val items = CivsEquipment.slots(keeper).toMutableList()
        for (slot in CivsEquipment.BAG until items.size) {
            val item = items[slot]?.takeIf { it.material == delivery.material && it.data == null } ?: continue
            val moved = minOf(item.amount, remaining)
            items[slot] = item.copy(amount = item.amount - moved).takeIf { it.amount > 0 }
            remaining -= moved
            if (remaining == 0) break
        }
        if (remaining != 0) throw CivsRejection("resident-food-missing")
        val served = CivsEquipment.collectSupplies(guest, mapOf(delivery.material to delivery.amount.toLong()))
        val emptied = CivsEquipment.replace(keeper, items)
        return CivsRules.validate(city.copy(workers = city.workers.map {
            when (it.id) { keeper.id -> emptied; guest.id -> served; else -> it }
        }, revision = Math.addExact(city.revision, 1)))
    }
}
