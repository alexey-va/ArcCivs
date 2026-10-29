package ru.arc.civs

import java.util.UUID

/** A real container and a supported adjacent place; contents are rechecked by the receipt owner. */
data class CivResidentFoodSource(val buildingId: UUID, val container: CivPosition, val standing: CivPosition,
    val material: String = "BREAD", val servingFor: UUID? = null)

/** FOOD is converted once at 1:1. Pickup transfers existing bread; neither transition feeds a resident. */
data class CivResidentFoodReceipt(val buildingId: UUID, val stockBefore: Stock, val materialized: Int = 0,
    val material: String = "BREAD", val servingFor: UUID? = null) {
    fun validated(city: Settlement, transfer: CivCollectedTransfer) {
        val worker = city.workers.single { it.id == transfer.workerId }
        val building = city.building(buildingId) ?: error("Missing food building")
        require(CivsResidentFood.eligible(city, worker, building))
        servingFor?.let { guestId ->
            val guest = city.workers.single { it.id == guestId }
            require(materialized == 0 && CivsTavernMeals.keeper(city, worker, guest, building))
        }
        require(transfer.ground.isEmpty() && transfer.homeId == null && transfer.containers.isNotEmpty())
        require(materialized in 0..CivsResidentFood.BATCH)
        require(material.matches(Regex("[A-Z][A-Z0-9_]{0,63}")))
        val after = CivsEquipment.slots(worker)
        var carried = 0
        repeat(maxOf(transfer.before.size, after.size)) { slot ->
            val before = transfer.before.getOrNull(slot)
            val next = after.getOrNull(slot)
            if (before != next) {
                require(slot >= CivsEquipment.BAG && (before == null || before.material == material && before.data == null) &&
                    next?.material == material && next.data == null && next.amount > (before?.amount ?: 0))
                carried += next.amount - (before?.amount ?: 0)
            }
        }
        var native = 0
        transfer.containers.forEach { change ->
            require(change.containerType in CIVS_STORAGE_TYPES && change.slot in 0..26 && change.before != change.after)
            require(city.contains(change.container.x, change.container.z) &&
                (change.container == building.control || building.bounds.contains(change.container.x, change.container.y, change.container.z)))
            require(city.buildings.none { it.id != building.id && it.control == change.container })
            require(change.container != building.control || change.containerType == "BARREL")
            listOfNotNull(change.before, change.after).forEach {
                it.validated()
                require(it.material == material && it.data == null)
            }
            val delta = (change.after?.amount ?: 0) - (change.before?.amount ?: 0)
            require(if (materialized > 0) delta > 0 else delta < 0)
            native += delta
        }
        if (materialized > 0) {
            require(city.autonomy != null && city.inventory == null && carried == 0 && native == materialized && material == "BREAD")
            require(CivsResidentFood.reserveBuilding(city, building))
            require(stockBefore.food >= materialized && city.stock == stockBefore.copy(food = stockBefore.food - materialized))
        } else require(city.stock == stockBefore && carried in 1..CivsResidentFood.BATCH && native == -carried)
    }
}

internal object CivsResidentFood {
    // Retain the existing four-bread supply batch; no new hunger, reward or production rate.
    const val BATCH = 4

    fun tavern(city: Settlement): CivBuilding? = city.buildings.firstOrNull {
        it.type == BuildingType.TAVERN && it.phase == BuildingPhase.ACTIVE && it.control != null
    }

    fun tavernStock(city: Settlement): Long = (city.workers.size.toLong() * BATCH).coerceIn(BATCH.toLong(), 64L)

    fun reserveBuilding(city: Settlement, building: CivBuilding): Boolean = building.phase == BuildingPhase.ACTIVE &&
        building.type in setOf(BuildingType.TOWN_HALL, BuildingType.WAREHOUSE, BuildingType.TAVERN)

    fun eligible(city: Settlement, worker: CivWorker, building: CivBuilding): Boolean =
        city.pendingDeletion == null && city.autonomy?.paused != true && city.workers.any { it.id == worker.id } &&
            building.phase == BuildingPhase.ACTIVE && (building.type in setOf(BuildingType.TOWN_HALL,
                BuildingType.TAVERN, BuildingType.WAREHOUSE) || building.id == worker.homeId || building.id == worker.assignment)
}
