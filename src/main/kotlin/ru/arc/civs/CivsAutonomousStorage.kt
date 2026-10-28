package ru.arc.civs

import java.util.UUID

/** Real container slots; closed Auto goods reserve ordinary 64-item stacks in these slots. */
data class CivAutonomousStore(val emptySlots: Int, val slots: Int, val containers: Int, val unavailable: Int = 0) {
    internal fun valid() = emptySlots in 0..slots && slots >= 0 && slots <= 27 * 8192 &&
        containers in 0..8192 && unavailable in 0..containers && slots <= containers * 27
}

internal object CivsAutonomousStorage {
    fun observed(city: Settlement): Boolean = city.autonomy?.storage != null

    fun stores(capture: CivSupplyCaptureResult): Map<String, CivAutonomousStore> = capture.stores.mapKeys { it.key.toString() }
        .mapValues { (_, store) -> CivAutonomousStore(store.capacity.emptySlots, store.slots, store.containers, store.unavailable) }

    fun store(city: Settlement, building: UUID): CivAutonomousStore? = city.autonomy?.storage?.get(building.toString())

    fun depots(city: Settlement): List<CivBuilding> {
        val warehouses = city.buildings.filter { it.phase == BuildingPhase.ACTIVE && it.type == BuildingType.WAREHOUSE }
        return warehouses.ifEmpty { city.buildings.filter { it.phase == BuildingPhase.ACTIVE && it.type == BuildingType.TOWN_HALL } }
            .sortedBy { it.id.toString() }
    }

    fun citySlots(city: Settlement): Long = depots(city).sumOf { store(city, it.id)?.emptySlots?.toLong() ?: 0L }

    fun pendingDestination(city: Settlement, pending: Stock, from: CivPosition): CivBuilding? {
        val rooms = if (observed(city)) rooms(city) else null
        return city.buildings.filter(CivsRules::isDepot).filter { building -> rooms == null ||
            Resource.entries.any { pending[it] > 0 && (rooms[building.id]?.fits(it.name) ?: 0L) > 0L }
        }.minByOrNull { it.entrance.point().distanceSquared(from.point()) }
    }

    fun room(slots: Long, held: Stock, resource: Resource): Long {
        val otherSlots = Resource.entries.filter { it != resource }.sumOf { stacks(held[it]) }
        return ((slots - otherSlots).coerceAtLeast(0) * 64L - held[resource]).coerceAtLeast(0)
    }

    fun room(city: Settlement, worker: CivWorker, resource: Resource): Long? {
        if (!observed(city)) return null
        val building = worker.assignment ?: return 0L
        val slots = store(city, building)?.emptySlots?.toLong() ?: 0L
        val held = (city.workers + city.corpses.orEmpty().map(CivCorpse::worker)).filter { it.assignment == building }
            .fold(Stock()) { total, resident -> add(total, resident.pendingStock ?: Stock()) }
        return room(slots, held, resource)
    }

    /**
     * Durable arrivals choose their slots before global stock. Live cargo is left for the dispatcher's
     * existing reservation pass; corpse cargo remains occupied here because it has no live courier.
     */
    fun capacities(city: Settlement): Map<UUID, CivSupplyCapacity> {
        val depots = depots(city)
        val slots = depots.associate { it.id to (store(city, it.id)?.emptySlots ?: 0).toLong() }
        val held = depots.associate { it.id to Stock() }.toMutableMap()
        val live = city.workers.mapNotNull { it.cargo?.takeIf { cargo -> cargo.stockResource != null } }
        val cargo = live + city.corpses.orEmpty().mapNotNull { it.worker.cargo?.takeIf { cargo -> cargo.stockResource != null } }
        fun blocked() = depots.associate { it.id to CivSupplyCapacity(0, emptyMap()) }
        for (shipment in cargo.sortedBy { it.id.toString() }) {
            val destination = CivsLogistics.destination(city, shipment)?.id ?: return blocked()
            val before = held[destination] ?: return blocked()
            val resource = requireNotNull(shipment.stockResource)
            if (room(slots.getValue(destination), before, resource) < shipment.amount) return blocked()
            held[destination] = before.with(resource, Math.addExact(before[resource], shipment.amount))
        }
        for (resource in Resource.entries) {
            var remaining = city.stock[resource]
            for (building in depots) {
                val id = building.id
                val before = held.getValue(id)
                val moved = minOf(remaining, room(slots.getValue(id), before, resource))
                held[id] = before.with(resource, Math.addExact(before[resource], moved))
                remaining -= moved
            }
            // Shrinking capacity retains every earned unit and admits no new stock until all holdings fit.
            if (remaining > 0) return blocked()
        }
        for (shipment in live) {
            val id = requireNotNull(CivsLogistics.destination(city, shipment)).id
            val resource = requireNotNull(shipment.stockResource)
            val before = held.getValue(id)
            held[id] = before.with(resource, before[resource] - shipment.amount)
        }
        return depots.associate { building ->
            val stock = held.getValue(building.id)
            building.id to CivSupplyCapacity((slots.getValue(building.id) - Resource.entries.sumOf { stacks(stock[it]) }).toInt(),
                Resource.entries.filter { stock[it] % 64L > 0 }.associate { it.name to 64L - stock[it] % 64L })
        }
    }

    fun destinationRoom(city: Settlement, building: UUID, resource: Resource, unloading: UUID? = null): Long =
        rooms(city, unloading)[building]?.fits(resource.name) ?: 0L

    fun cityRoom(city: Settlement, resource: Resource): Long = rooms(city).values.sumOf { it.fits(resource.name) }

    /** One observed destination's slots are shared across every resource in this self-delivery. */
    fun pendingTransfer(city: Settlement, pending: Stock, destination: UUID): Stock {
        val room = rooms(city)[destination] ?: return Stock()
        return Resource.entries.fold(Stock()) { accepted, resource ->
            val amount = minOf(pending[resource], room.fits(resource.name))
            room.take(resource.name, amount)
            accepted.with(resource, amount)
        }
    }

    private fun rooms(city: Settlement, unloading: UUID? = null): Map<UUID, CivSupplyRoom> {
        val rooms = capacities(city).mapValues { CivSupplyRoom(it.value, Resource.entries.associate { it.name to 64 }) }
        city.workers.mapNotNull { it.cargo?.takeIf { cargo -> cargo.stockResource != null && cargo.id != unloading } }
            .forEach { cargo ->
                val room = CivsLogistics.destination(city, cargo)?.id?.let(rooms::get) ?: return@forEach
                if (room.fits(cargo.material) < cargo.amount) room.block() else room.take(cargo.material, cargo.amount)
            }
        return rooms
    }

    private fun stacks(amount: Long) = amount / 64L + if (amount % 64L == 0L) 0L else 1L
    private fun add(first: Stock, second: Stock) = Stock(Math.addExact(first.wood, second.wood),
        Math.addExact(first.stone, second.stone), Math.addExact(first.food, second.food))
}
