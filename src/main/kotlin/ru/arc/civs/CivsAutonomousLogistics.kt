package ru.arc.civs

import java.util.UUID

/** Closed stock conservation: production earns once, pickup moves it, arrival makes it spendable. */
internal object CivsAutonomousLogistics {
    fun staffed(city: Settlement): Boolean = city.autonomy != null && city.workers.any { worker ->
        !CivsResidents.isChild(worker) && worker.assignment?.let(city::building)?.let {
            it.type == BuildingType.WAREHOUSE && it.phase == BuildingPhase.ACTIVE && it.control != null
        } == true
    }

    fun held(city: Settlement, resource: Resource): Long = city.stock[resource] +
        (city.workers + city.corpses.orEmpty().map(CivCorpse::worker)).sumOf { worker ->
        (worker.pendingStock?.get(resource) ?: 0L) +
            (worker.cargo?.takeIf { it.stockResource == resource }?.amount ?: 0L)
        }

    fun room(city: Settlement, resource: Resource): Long = (CivsRules.capacity(city) - held(city, resource)).coerceAtLeast(0)

    fun pendingRoom(city: Settlement, pending: Stock): Boolean = Resource.entries.any {
        pending[it] > 0 && city.stock[it] < CivsRules.capacity(city)
    }

    /** An observed return transfers already-earned goods, independently of room for another production batch. */
    fun unloadPending(city: Settlement, workerId: UUID, assignment: UUID, cycle: Long, nextAt: Long,
        pending: Stock, at: Long): Settlement {
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        if (city.autonomy?.paused != false || city.inventory != null || city.pendingDeletion != null || at < 0 ||
            CivsResidents.isChild(worker) || worker.cargo != null || worker.assignment != assignment ||
            worker.completedCycles != cycle || worker.nextDeliveryAt != nextAt || worker.pendingStock != pending ||
            pending == Stock()) throw CivsRejection("stale-delivery")
        if (!pendingRoom(city, pending)) throw CivsRejection("storage-full")
        var stock = city.stock
        var remaining = pending
        for (resource in Resource.entries) {
            val amount = minOf(pending[resource], (CivsRules.capacity(city) - stock[resource]).coerceAtLeast(0))
            stock = stock.with(resource, Math.addExact(stock[resource], amount))
            remaining = remaining.with(resource, pending[resource] - amount)
        }
        // Fencing changes only the schedule identity; this transfer earns no production cycle or experience.
        val updated = worker.copy(pendingStock = remaining.takeUnless { it == Stock() },
            nextDeliveryAt = maxOf(at, Math.addExact(worker.nextDeliveryAt, 1)))
        return CivsRules.validate(city.copy(stock = stock, workers = city.workers.map { if (it.id == workerId) updated else it },
            revision = Math.addExact(city.revision, 1)))
    }

    /** Before a warehouse is staffed the existing observed self-delivery also retires old queued goods. */
    fun produce(city: Settlement, worker: CivWorker, resource: Resource, amount: Long): Pair<Stock, Stock?> {
        if (amount < 0 || room(city, resource) < amount) throw CivsRejection("storage-full")
        val pending = worker.pendingStock ?: Stock()
        if (staffed(city)) return city.stock to pending.with(resource, Math.addExact(pending[resource], amount)).takeUnless { it == Stock() }
        var stock = city.stock
        for (kind in Resource.entries) {
            val count = Math.addExact(stock[kind], Math.addExact(pending[kind], if (kind == resource) amount else 0L))
            if (count > CivsRules.capacity(city)) throw CivsRejection("storage-full")
            stock = stock.with(kind, count)
        }
        return stock to null
    }

    fun pickup(city: Settlement, workerId: UUID, cargo: CivCargo): Settlement {
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        if (worker.cargo == cargo) return city
        val warehouse = city.building(cargo.warehouseId)
        val source = city.workers.firstOrNull { it.id == cargo.sourceWorkerId }
        val resource = cargo.stockResource ?: throw CivsRejection("stale-delivery")
        if (city.autonomy?.paused != false || city.pendingDeletion != null || !CivsLogistics.validCargo(city, cargo) ||
            worker.cargo != null || CivsResidents.isChild(worker) || worker.assignment != cargo.warehouseId ||
            warehouse?.type != BuildingType.WAREHOUSE || warehouse.phase != BuildingPhase.ACTIVE || warehouse.control == null ||
            cargo.targetBuildingId != warehouse.id || source == null || source.id == workerId ||
            source.assignment != cargo.sourceBuildingId || CivsResidents.isChild(source) || source.cargo != null ||
            (source.pendingStock?.get(resource) ?: 0L) < cargo.amount ||
            cargo.id != CivsLogistics.shipmentId(city.id, workerId, worker.completedCycles, cargo.sourceBuildingId,
                cargo.targetBuildingId, cargo.material, cargo.sourceWorkerId)) throw CivsRejection("stale-delivery")
        val remaining = requireNotNull(source.pendingStock).let { it.with(resource, it[resource] - cargo.amount) }
            .takeUnless { it == Stock() }
        return CivsRules.validate(city.copy(workers = city.workers.map { when (it.id) {
            workerId -> it.copy(cargo = cargo)
            source.id -> it.copy(pendingStock = remaining)
            else -> it
        } }, revision = Math.addExact(city.revision, 1)))
    }

    fun unload(city: Settlement, worker: CivWorker, cargo: CivCargo, at: Long): Settlement {
        val resource = requireNotNull(cargo.stockResource)
        if (!CivsLogistics.validCargo(city, cargo) || city.pendingDeletion != null ||
            CivsLogistics.destination(city, cargo) == null) throw CivsRejection("stale-delivery")
        if (CivsRules.capacity(city) - city.stock[resource] < cargo.amount) throw CivsRejection("storage-full")
        val updated = CivsResidents.trained(worker.copy(cargo = null,
            completedCycles = Math.addExact(worker.completedCycles, 1),
            nextDeliveryAt = Math.addExact(at, CivsRules.BUILDER_SESSION_INTERVAL)), BuildingType.WAREHOUSE)
        return CivsRules.validate(city.copy(stock = city.stock.with(resource, Math.addExact(city.stock[resource], cargo.amount)),
            workers = city.workers.map { if (it.id == worker.id) updated else it }, revision = Math.addExact(city.revision, 1)))
    }

    fun snapshot(city: Settlement, retained: Map<UUID, CivCourierPlan>, positions: (UUID) -> CivPosition?,
        sources: Map<UUID, CivPosition>, unavailable: (UUID) -> Boolean): CivSupplySnapshot {
        val materials = Resource.entries.associate { it.name to CivsLogistics.MAX_CARGO }
        val capacity = Resource.entries.associate { resource -> resource.name to
            (CivsRules.capacity(city) - city.stock[resource]).coerceAtLeast(0) }
        val stores = city.buildings.filter { it.control != null }.associate { building ->
            building.id to CivSupplyStore(building.id, building.entrance, requireNotNull(building.control),
                emptyMap(), emptyMap(), null, CivSupplyCapacity(0, capacity), building.type)
        }
        val couriers = city.workers.mapNotNull { worker ->
            val building = worker.assignment?.let(city::building) ?: return@mapNotNull null
            if (worker.cargo != null || CivsResidents.isChild(worker) || unavailable(worker.id) ||
                building.type != BuildingType.WAREHOUSE || building.phase != BuildingPhase.ACTIVE || building.control == null) return@mapNotNull null
            CivSupplyCourier(worker.id, building.id, worker.completedCycles, positions(worker.id) ?: building.entrance, materials)
        }
        val workers = city.workers.mapNotNull { worker ->
            val source = sources[worker.id] ?: return@mapNotNull null
            val building = worker.assignment?.let(city::building) ?: return@mapNotNull null
            val excess = CivsLogistics.workerExcess(city, worker)
            if (excess.isEmpty()) null else CivSupplyWorkerSource(worker.id, building.id, source, excess)
        }
        val existing = retained.filter { (id, plan) ->
            val worker = city.workers.firstOrNull { it.id == id }
            val warehouse = city.building(plan.cargo.warehouseId)
            worker != null && worker.cargo == null && !unavailable(id) && worker.assignment == warehouse?.id &&
                warehouse?.type == BuildingType.WAREHOUSE && warehouse.phase == BuildingPhase.ACTIVE &&
                plan.cargo.stockResource != null &&
                plan.cargo.id == CivsLogistics.shipmentId(city.id, id, worker.completedCycles, plan.cargo.sourceBuildingId,
                    plan.cargo.targetBuildingId, plan.cargo.material, plan.cargo.sourceWorkerId)
        }.toMutableMap()
        city.workers.forEach { worker -> worker.cargo?.let { cargo ->
            CivsLogistics.destination(city, cargo)?.let { target ->
                existing[worker.id] = CivCourierPlan(cargo, target.id, target.entrance, true)
            }
        } }
        return CivSupplySnapshot(city.id, CivAutonomousSupplyObservation(civSupplyInputs(city)), stores, emptyList(),
            couriers, workers, existing, materials.mapValues { it.value.toInt() }, autonomousStock = true)
    }
}

internal data class CivAutonomousSupplyObservation(val inputs: Any)
