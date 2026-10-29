package ru.arc.civs

import org.bukkit.Material
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal class CivsCargoRequests(
    private val authority: CivsService,
    private val records: CivsCityRecords,
    private val residentSuppliesOwner: () -> CivsResidentSupplies,
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val residentFoods: CivsResidentFoods,
    private val world: CivsWorld,
    private val storage: CivsStorage,
    private val citizens: CivsCitizens,
    private val supply: CivsSupplyDispatcher,
    private val construction: CivsConstructionRuntime,
    private val inventoryJournal: CivsInventoryJournal,
) {
    private val residentSupplies get() = residentSuppliesOwner()
    private val supplyObservations = mutableMapOf<UUID, Pair<Long, Long>>()
    private val capacityWrites = mutableSetOf<UUID>()

    fun clearObservations() = supplyObservations.clear()
    fun forgetObservation(cityId: UUID) { supplyObservations.remove(cityId) }

    internal fun courierPlan(city: Settlement, worker: CivWorker): CivCourierPlan? {
        worker.cargo?.let { cargo ->
            val target = CivsLogistics.destination(city, cargo) ?: return null
            return CivCourierPlan(cargo, target.id, target.entrance, true)
        }
        return supply.plan(city.id, worker.id)?.takeIf { plan ->
            worker.assignment == plan.cargo.warehouseId &&
                CivsLogistics.shipmentId(city.id, worker.id, worker.completedCycles, plan.cargo.sourceBuildingId,
                    plan.cargo.targetBuildingId, plan.cargo.material, plan.cargo.sourceWorkerId) == plan.cargo.id
        }
    }

    fun pulseSupply() {
        val relevant = records.cities.values.filter { city -> city.pendingDeletion == null &&
            (city.inventory != null || city.autonomy?.paused == false) &&
            city.id !in records.unhealthy && (if (city.autonomy?.paused == false && city.inventory?.mode == CivStorageMode.PHYSICAL)
                authority.settlementActive(city)
            else if (CivsAutonomy.legacyStock(city)) authority.settlementActive(city) && city.workers.any { worker ->
                worker.cargo != null || worker.pendingStock != null || worker.assignment?.let(city::building)?.let {
                    it.type == BuildingType.WAREHOUSE || CivsRules.resource(it.type) != null
                } == true
            } else city.workers.any { worker ->
                worker.cargo != null || worker.assignment?.let(city::building)?.type.let {
                    it == BuildingType.WAREHOUSE || it in CivsCarpentry.workshops
                }
            }) }
        val ids = relevant.mapTo(hashSetOf(), Settlement::id)
        // An unknown receipt outcome keeps its accepted batch reserved until the existing
        // recovery owner publishes or definitively rejects that receipt.
        ids += supply.cityIds().filter { inventoryJournal.pending(it) }
        (supply.cityIds() - ids).forEach { supply.remove(it); supplyObservations.remove(it) }
        for (city in relevant) {
            val revision = if (CivsAutonomy.legacyStock(city)) city.revision else storage.supplyRevision(city.id)
            val previous = supplyObservations[city.id]
            if (previous == null || revision != previous.first || authority.now() - previous.second >= 5_000L) {
                supply.request(city.id)
                supplyObservations[city.id] = revision to authority.now()
            }
        }
    }

    fun captureSupply(cityId: UUID, retained: Map<UUID, CivCourierPlan>): () -> CivSupplySnapshot? {
        val city = records.cities[cityId]?.takeIf { authority.ready() && cityId !in records.unhealthy && it.pendingDeletion == null &&
            (it.inventory != null || it.autonomy?.paused == false) }
            ?: throw CivsRejection("storage-unavailable")
        if (cityId in records.busy) throw CivsRejection("city-busy")
        if (CivsAutonomy.legacyStock(city)) {
            val capture = storage.startSupplyCapture(city)
            return capture@{
                if (!authority.ready() || cityId in records.unhealthy) throw CivsRejection("storage-unavailable")
                if (cityId in capacityWrites) return@capture null
                if (cityId in records.busy) throw CivsRejection("city-busy")
                if (!capture.step()) return@capture null
                val observed = requireNotNull(capture.result)
                val current = records.cities[cityId] ?: throw CivsRejection("storage-unavailable")
                if (!storage.supplyCaptureCurrent(current, observed)) throw CivsRejection("storage-observation-stale")
                val stores = CivsAutonomousStorage.stores(observed)
                if (current.autonomy?.storage != stores) {
                    capacityWrites += cityId
                    runCatching { authority.mutate(cityId) { live ->
                        if (!storage.supplyCaptureCurrent(live, observed)) throw CivsRejection("storage-observation-stale")
                        val auto = live.autonomy ?: throw CivsRejection("storage-unavailable")
                        CivsRules.validate(live.copy(autonomy = auto.copy(storage = stores), revision = Math.addExact(live.revision, 1)))
                    } }.getOrElse { CompletableFuture.failedFuture(it) }.whenCompleteSync(tasks) { saved, failure ->
                        capacityWrites -= cityId
                        if (failure == null && saved != null) storage.rememberAutonomousCapacity(saved, observed)
                        if (failure == null) CivsDiagnostics.event("autonomous_storage_observed", "city" to cityId,
                            "stores" to stores.size, "empty_slots" to stores.values.sumOf { it.emptySlots },
                            "containers" to stores.values.sumOf { it.containers }, "revision" to saved?.revision)
                        else if (CivsDiagnostics.rejection(failure)?.reason !in setOf("city-busy", "storage-observation-stale"))
                            authority.log("observe autonomous storage $cityId", failure, cityId)
                    }
                    return@capture null
                }
                storage.rememberAutonomousCapacity(current, observed)
                CivsAutonomousLogistics.snapshot(current, retained, { citizens.courierPosition(cityId, it) },
                    citizens.courierSupplySources(cityId, retained.values.mapNotNullTo(hashSetOf()) { it.cargo.sourceWorkerId }),
                    observed, { citizens.courierUnavailable(cityId, it) })
            }
        }
        val capture = CivsSupplyPlanning(city, storage.startSupplyCapture(city), retained,
            { citizens.courierPosition(cityId, it) }, { citizens.courierUnavailable(cityId, it) }, supply.craftJobs(cityId))
        return {
            if (!authority.ready() || cityId in records.unhealthy) throw CivsRejection("storage-unavailable")
            if (cityId in records.busy) throw CivsRejection("city-busy")
            capture.step()?.let { snapshot -> snapshot.copy(requests = snapshot.requests +
                residentSupplies.upkeepSupplyRequests(city) { id -> snapshot.stores[id]?.stock.orEmpty() }) }
        }
    }

    fun validateSupply(snapshot: CivSupplySnapshot, plans: Map<UUID, CivCourierPlan>): Boolean {
        val city = records.cities[snapshot.cityId]?.takeIf { authority.ready() && it.id !in records.busy && it.id !in records.unhealthy } ?: return false
        if (snapshot.autonomousStock) {
            val observation = snapshot.inputKey as? CivAutonomousSupplyObservation ?: return false
            return city.autonomy?.paused == false && observation.inputs == civSupplyInputs(city) &&
                (observation.capture == null || storage.supplyCaptureCurrent(city, observation.capture)) &&
                plans.all { (workerId, plan) -> plan.carrying ||
                    !citizens.courierUnavailable(city.id, workerId) && citizens.courierSourceCurrent(city.id, plan.cargo) }
        }
        val observation = snapshot.inputKey as? CivSupplyObservation ?: return false
        if (observation.inputs != civSupplyInputs(city)) return false
        if (plans.any { (workerId, plan) -> !plan.carrying && citizens.courierUnavailable(city.id, workerId) }) return false
        if (plans.values.any { !it.carrying && it.cargo.sourceWorkerId != null }) return false
        val buildings = if (snapshot.craftWorkers.isNotEmpty()) snapshot.stores.keys else plans.values.flatMap { plan ->
            if (plan.carrying || plan.cargo.sourceWorkerId != null) listOf(plan.destinationBuildingId)
            else listOf(plan.cargo.sourceBuildingId, plan.destinationBuildingId)
        }.toSet()
        return storage.supplyCaptureCurrent(city, observation.capture, buildings)
    }

    internal fun pickupCargo(cityId: UUID, workerId: UUID, requested: CivCargo): CompletableFuture<Settlement> =
        authority.action("cargo_pickup", null, "city" to cityId, "worker" to workerId, "shipment" to requested.id) {
            authority.afterCityAvailable(cityId) {
                val city = records.cities[cityId] ?: throw CivsRejection("no-city")
                val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
                worker.cargo?.let { cargo ->
                    rejectIf(cargo.id != requested.id, "stale-delivery")
                    return@afterCityAvailable CompletableFuture.completedFuture(city)
                }
                val accepted = courierPlan(city, worker)?.takeUnless { it.carrying }?.cargo ?: throw CivsRejection("stale-delivery")
                rejectIf(accepted.id != requested.id || accepted.sourceEntrance != requested.sourceEntrance, "stale-delivery")
                accepted.stockResource?.let { resource ->
                    val source = city.workers.firstOrNull { it.id == accepted.sourceWorkerId } ?: throw CivsRejection("stale-delivery")
                    val amount = minOf(accepted.amount, requested.amount, source.pendingStock?.get(resource) ?: 0L)
                    rejectIf(amount <= 0L, "stale-delivery")
                    val cargo = accepted.copy(amount = amount)
                    citizens.requireCargoPickupArrival(cityId, workerId, cargo)
                    return@afterCityAvailable authority.mutate(cityId) { current ->
                        citizens.requireCargoPickupArrival(cityId, workerId, cargo)
                        CivsLogistics.pickup(current, workerId, cargo, foods = residentFoods)
                    }.thenApply { saved ->
                        supply.release(cityId, workerId)
                        CivsDiagnostics.event("cargo_pickup_committed", "city" to cityId, "worker" to workerId,
                            "shipment" to cargo.id, "stock_resource" to resource, "amount" to amount,
                            "source_worker" to source.id, "source_before" to source.pendingStock?.get(resource),
                            "source_after" to (saved.workers.first { it.id == source.id }.pendingStock?.get(resource) ?: 0L),
                            "carried_after" to saved.workers.first { it.id == workerId }.cargo?.amount, "revision" to saved.revision)
                        saved
                    }
                }
                rejectIf(accepted.sourceWorkerId != null, "stale-delivery")
                val available = run {
                    val count = storage.buildingAvailable(city, accepted.sourceBuildingId)[accepted.material] ?: 0L
                    val reserve = if (accepted.sourceBuildingId == accepted.warehouseId) 0L else
                        (city.building(accepted.sourceBuildingId)?.let { CivsLogistics.desiredStock(city, it)[accepted.material] } ?: 0L) +
                            (residentSupplies.craftRequired(city, accepted.sourceBuildingId)[accepted.material] ?: 0L)
                    (count - reserve).coerceAtLeast(0)
                }
                val needed = if (accepted.sourceBuildingId != accepted.warehouseId) CivsLogistics.MAX_CARGO else {
                    val local = residentSupplies.localStocks(city)
                    (CivsLogistics.requests(city, local) + residentSupplies.workSupplyRequests(city, local)).firstOrNull { request ->
                        request.material == accepted.material && (request.buildingId == accepted.targetBuildingId ||
                            request.construction && city.construction?.let { order ->
                                CivsRules.constructionStores(city, order.buildingId).any { it.id == accepted.targetBuildingId }
                            } == true)
                    }?.let { (it.needed - it.inTransit).coerceAtLeast(0) } ?: 0L
                }
                val amount = minOf(accepted.amount, requested.amount, available, needed,
                    CivsEquipment.supplyRoom(worker, Material.valueOf(accepted.material)).toLong(),
                    storage.cargoRoom(city, accepted.targetBuildingId, accepted.material))
                rejectIf(amount <= 0L, "stale-delivery")
                val cargo = accepted.copy(amount = amount)
                rejectIf(!CivsLogistics.validCargo(city, cargo), "stale-delivery")
                citizens.requireStorageArrival(cityId, workerId, cargo.sourceBuildingId)
                val carriedBefore = CivsEquipment.plainCount(worker, cargo.material)
                authority.inventoryMutation(cityId, { storage.prepareCargoPickup(it, cargo) }, keepViewersOpen = true) { before, inventory ->
                    CivsLogistics.pickup(before, workerId, cargo, supply.craftJobs(cityId).mapValues { it.value.inputs }, residentFoods).copy(inventory = inventory)
                }.thenApply { saved ->
                    CivsDiagnostics.event("cargo_pickup_committed", "city" to cityId, "worker" to workerId,
                        "shipment" to cargo.id, "material" to cargo.material, "amount" to cargo.amount,
                        "source" to cargo.sourceBuildingId, "source_worker" to cargo.sourceWorkerId,
                        "carried_before" to carriedBefore,
                        "carried_after" to CivsEquipment.plainCount(saved.workers.first { it.id == workerId }, cargo.material),
                        "revision" to saved.revision)
                    supply.release(cityId, workerId)
                    saved
                }
            }
        }.whenComplete { _, failure ->
            val current = if (!records.closed) supply.plan(cityId, workerId)?.cargo else null
            if (!records.closed && failure != null && cityId !in records.unhealthy && current?.id == requested.id &&
                current.sourceEntrance == requested.sourceEntrance &&
                CivsDiagnostics.rejection(failure)?.reason in
                setOf("stale-delivery", "materials-missing", "storage-full", "route-unreachable", "building-not-found", "control-missing"))
                supply.release(cityId, workerId, failed = true)
        }

    internal fun dropoffCargo(cityId: UUID, workerId: UUID, requested: CivCargo, destinationId: UUID): CompletableFuture<Settlement> =
        authority.action("cargo_dropoff", null, "city" to cityId, "worker" to workerId, "shipment" to requested.id) {
            authority.afterCityAvailable(cityId) {
                val city = records.cities[cityId] ?: throw CivsRejection("no-city")
                val cargo = city.workers.firstOrNull { it.id == workerId }?.cargo ?: throw CivsRejection("stale-delivery")
                rejectIf(cargo.id != requested.id || CivsLogistics.destination(city, cargo)?.id != destinationId, "stale-delivery")
                citizens.requireStorageArrival(cityId, workerId, destinationId)
                cargo.stockResource?.let { resource ->
                    return@afterCityAvailable authority.mutate(cityId) { current ->
                        citizens.requireStorageArrival(cityId, workerId, destinationId)
                        rejectIf(CivsLogistics.destination(current, cargo)?.id != destinationId, "stale-delivery")
                        residentSupplies.requireAutonomousDepotStorage(current)
                        CivsLogistics.unload(current, workerId, cargo.id, authority.now())
                    }.thenApply { saved ->
                        supply.release(cityId, workerId)
                        construction.materialsChanged()
                        CivsDiagnostics.event("cargo_dropoff_committed", "city" to cityId, "worker" to workerId,
                            "shipment" to cargo.id, "stock_resource" to resource, "amount" to cargo.amount,
                            "source_worker" to cargo.sourceWorkerId, "destination" to destinationId,
                            "stock_before" to city.stock[resource], "stock_after" to saved.stock[resource], "revision" to saved.revision)
                        saved
                    }
                }
                val carriedBefore = CivsEquipment.plainCount(city.workers.first { it.id == workerId }, cargo.material)
                authority.inventoryMutation(cityId, { storage.prepareCargoDropoff(it, cargo, destinationId) }, keepViewersOpen = true) { before, inventory ->
                    CivsLogistics.unload(before, workerId, cargo.id, authority.now()).copy(inventory = inventory)
                }.whenComplete { saved, failure ->
                    if (failure == null) {
                        supply.release(cityId, workerId)
                        construction.materialsChanged()
                        CivsDiagnostics.event("cargo_dropoff_committed", "city" to cityId, "worker" to workerId,
                            "shipment" to cargo.id, "material" to cargo.material, "amount" to cargo.amount,
                            "source_worker" to cargo.sourceWorkerId, "carried_before" to carriedBefore,
                            "carried_after" to CivsEquipment.plainCount(saved.workers.first { it.id == workerId }, cargo.material),
                            "destination" to destinationId, "revision" to saved.revision)
                    }
                }
            }
        }

    internal fun collectionAvailable(cityId: UUID): Boolean {
        val city = records.cities[cityId] ?: return false
        return authority.ready() && city.pendingDeletion == null && city.id !in records.busy && city.id !in records.unhealthy &&
            inventoryJournal.collectionAvailable(city.worldId)
    }

    private fun collectionReserve(city: Settlement, worker: CivWorker): Map<String, Long> =
        CivsLogistics.workerReserve(city, worker, residentSupplies.craftPlan(city, worker)?.inputs.orEmpty(), residentFoods)

    internal fun collectedHome(city: Settlement, worker: CivWorker): CivCollectedHome? {
        if (!collectionAvailable(city.id) || worker.hasCollectedItems != true) return null
        val home = civsCollectionHome(city, worker) ?: return null
        val reserve = collectionReserve(city, worker)
        val surplus = CivsEquipment.homeSurplus(worker, reserve).isNotEmpty()
        return CivCollectedHome(home.id, if (surplus) storage.homeDestination(city, worker, reserve) else null, surplus)
    }

    internal fun pickupGround(cityId: UUID, workerId: UUID, entities: List<UUID>): CompletableFuture<Settlement> =
        authority.action("resident_ground_pickup", null, "city" to cityId, "worker" to workerId, "entities" to entities.size) {
            collectedMutation(cityId, workerId) { city, feet -> storage.prepareGroundPickup(city, workerId, entities, feet) }
        }

    fun collectForestryLeafLoot(cityId: UUID, workerId: UUID, entities: List<UUID>): CompletableFuture<Settlement> =
        authority.action("forestry_leaf_loot", null, "city" to cityId, "worker" to workerId, "entities" to entities.size) {
            collectedReceipt(cityId, workerId) { city -> storage.prepareForestryLeafLoot(city, workerId, entities) }
        }

    internal fun depositCollected(cityId: UUID, workerId: UUID, destination: CivPosition?): CompletableFuture<Settlement> =
        authority.action("resident_home_deposit", null, "city" to cityId, "worker" to workerId) {
            val city = records.cities[cityId] ?: throw CivsRejection("no-city")
            rejectIf(!collectionAvailable(cityId), "city-busy")
            val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
            val reserve = collectionReserve(city, worker)
            if (CivsEquipment.homeSurplus(worker, reserve).isEmpty()) {
                if (worker.hasCollectedItems != true) CompletableFuture.completedFuture(city)
                else authority.mutate(cityId) { current -> CivsRules.validate(current.copy(workers = current.workers.map {
                    if (it.id == workerId) it.copy(hasCollectedItems = false) else it
                }, revision = current.revision + 1)) }
            } else collectedMutation(cityId, workerId, allowNight = true) { current, feet -> storage.prepareHomeDeposit(current, workerId,
                destination ?: throw CivsRejection("storage-unavailable"), feet, collectionReserve(current,
                    current.workers.first { it.id == workerId })) }
        }

    fun collectedMutation(cityId: UUID, workerId: UUID, allowNight: Boolean = false,
        materializingFood: Boolean = false,
        prepare: (Settlement, org.bukkit.Location) -> CivCollectedPlan): CompletableFuture<Settlement> {
        authority.checkMain()
        rejectIf(!collectionAvailable(cityId), "city-busy") // Throttle before reserving a city or any native source.
        val city = records.cities.getValue(cityId)
        val feet = if (materializingFood) citizens.residentFeet(workerId) else citizens.residentCollectionFeet(workerId, allowNight)
        feet ?: throw CivsRejection("worker-too-far")
        rejectIf(feet.world?.uid != city.worldId, "wrong-world")
        return collectedReceipt(cityId, workerId) { current -> prepare(current, feet) }
    }

    private fun collectedReceipt(cityId: UUID, workerId: UUID,
        prepare: (Settlement) -> CivCollectedPlan): CompletableFuture<Settlement> {
        authority.checkMain()
        rejectIf(!collectionAvailable(cityId), "city-busy")
        val city = records.cities.getValue(cityId)
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val tx = try {
            val prepared = prepare(city)
            val transfer = prepared.transfer ?: run { storage.unlock(id); return CompletableFuture.completedFuture(city) }
            val after = CivsRules.validate(city.copy(stock = prepared.stock ?: city.stock,
                workers = city.workers.map { if (it.id == workerId) prepared.worker else it },
                revision = city.revision + 1))
            CivInventoryTransaction(id, cityId, city.revision, after, emptyList(), collected = transfer).also {
                storage.seal(it)
                inventoryJournal.reserveCollection(city.worldId, id)
                authority.reserveCity(cityId) // The immutable after-state must remain current during the first asynchronous disk fence.
            }
        } catch (failure: Exception) {
            storage.unlock(id)
            inventoryJournal.releaseCollection(city.worldId, id)
            authority.releaseCity(cityId)
            throw failure
        }
        return inventoryJournal.submitCollected(city, tx)
    }

    fun validateNativeCollected(tx: CivInventoryTransaction) {
        val collected = requireNotNull(tx.collected)
        rejectIf(storage.collectedBeforeState(tx) != CivInventoryApplyResult.Applied, "stale-delivery")
        if (collected.forestryLeafLoot != null) return // Exact PDC source, workplace and Lands checks live in the shared native fence.
        if (collected.food?.materialized?.let { it > 0 } == true) return
        collected.food?.servingFor?.let { guest ->
            citizens.validateTavernPickup(tx.cityId, collected.workerId, guest,
                requireNotNull(collected.food).buildingId, requireNotNull(collected.food).material,
                requireNotNull(collected.food).servingTicket ?: throw CivsRejection("stale-meal"))
        }
        val feet = citizens.residentCollectionFeet(collected.workerId, allowNight = collected.homeId != null)
            ?: throw CivsRejection("worker-too-far")
        val world = plugin.server.getWorld(tx.after.worldId) ?: throw CivsRejection("storage-unavailable")
        rejectIf(feet.world?.uid != world.uid, "wrong-world")
        collected.ground.forEach { change ->
            val item = world.getEntity(change.entityId) as? org.bukkit.entity.Item ?: throw CivsRejection("stale-delivery")
            rejectIf(!civsCanCollect(feet, item, collected.workerId), "worker-too-far")
        }
        collected.containers.forEach { rejectIf(!civsCanReachContainer(feet, it.container), "worker-too-far") }
    }

    private fun rejectIf(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
}
