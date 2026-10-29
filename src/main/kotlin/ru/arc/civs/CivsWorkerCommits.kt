package ru.arc.civs

import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Observed work completion: current actor proofs and one canonical durable city write lane. */
internal class CivsWorkerCommits(
    private val authority: CivsService,
    private val tasks: LifecycleTaskScope,
    private val storage: CivsStorage,
    private val worksites: CivsWorksites,
    private val citizens: CivsCitizens,
    private val world: CivsWorld,
    private val physics: CivsPhysics,
    private val inventoryJournal: CivsInventoryJournal,
    private val roadMaintenance: CivsRoadMaintenance,
    private val construction: CivsConstructionRuntime,
    private val blueprints: CivsBlueprints,
    private val settlements: () -> Map<UUID, Settlement>,
) {
    private val cities get() = settlements()
    fun deliver(cityId: UUID, workerId: UUID, assignment: UUID, cycle: Long, nextAt: Long, constructionId: UUID?, at: Long): CompletableFuture<Settlement> = authority.action("deliver", null, "city" to cityId, "worker" to workerId, "cycle" to cycle) {
        authority.afterCityAvailable(cityId) {
            val city = cities[cityId] ?: throw CivsRejection("no-city")
            val building = city.building(assignment) ?: throw CivsRejection("building-not-found")
            if (citizens.pendingStockDelivery(cityId, workerId) != null) {
                val token = CitizenToken(assignment, cycle, nextAt, constructionId)
                val delivered = citizens.requirePendingStockDelivery(cityId, workerId, token)
                val pending = delivered.stock
                return@afterCityAvailable authority.mutate(cityId) { current ->
                    rejectIf(citizens.requirePendingStockDelivery(cityId, workerId, token) != delivered, "stale-delivery")
                    authority.requireAutonomousStorage(current)
                    CivsAutonomousLogistics.unloadPending(current, workerId, assignment, cycle, nextAt, pending, at,
                        delivered.destinationBuildingId)
                }.thenApply { saved ->
                    CivsDiagnostics.event("autonomous_pending_stock_delivered", "city" to cityId, "worker" to workerId,
                        "pending_before" to pending, "pending_after" to saved.workers.first { it.id == workerId }.pendingStock,
                        "stock_before" to city.stock, "stock_after" to saved.stock, "revision" to saved.revision)
                    saved
                }
            }
            if (CivsRules.isBuilder(city, building)) {
                if (constructionId == null) {
                    val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
                    rejectIf(worker.assignment != assignment || worker.completedCycles != cycle || worker.nextDeliveryAt != nextAt,
                        "stale-delivery")
                    val work = roadMaintenance.plan(city, worker)?.takeIf { it.upkeep != null }
                        ?: throw CivsRejection("stale-worksite")
                    citizens.requireWorldWorkArrival(cityId, workerId, work)
                    autonomousUpkeepMutation(city, work, at)
                } else {
                    rejectIf(city.construction?.id != constructionId, "stale-delivery")
                    citizens.requireConstructionArrival(cityId, workerId, constructionId)
                    authority.mutate(cityId) { CivsRules.deliver(it, it.owner, workerId, assignment, cycle, nextAt, at) }
                        .thenApply { saved ->
                            tasks.runLater(1) { construction.pulse() }
                            saved
                        }
                }
            } else if (building.type == BuildingType.UNIVERSITY) {
                rejectIf(constructionId != null, "stale-delivery")
                val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
                val plan = citizenPlan(city, worker) ?: throw CivsRejection("stale-delivery")
                rejectIf(plan.token.completedCycles != cycle || plan.token.nextDeliveryAt != nextAt, "stale-delivery")
                val projectId = plan.token.researchProjectId ?: throw CivsRejection("stale-research")
                if (CivsAutonomy.legacyStock(city)) citizens.requireAutonomousDelivery(cityId, workerId, plan.token)
                else citizens.requireSelectedWorkDelivery(cityId, workerId, plan.token)
                authority.mutate(cityId) { CivsResearch.complete(it, workerId, cycle, nextAt, projectId, at) }.thenApply { saved ->
                    CivsDiagnostics.event("research_session_committed", "city" to cityId, "worker" to workerId, "cycle" to cycle,
                        "project" to projectId, "research" to city.research?.project?.research,
                        "work_done" to (saved.research?.project?.workDone ?: city.research?.project?.workRequired),
                        "completed" to (saved.research?.project == null), "revision" to saved.revision)
                    saved
                }
            } else if (CivsAutonomy.legacyStock(city) && building.type != BuildingType.FORESTER) {
                rejectIf(constructionId != null || city.autonomy?.paused == true, "stale-delivery")
                val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
                rejectIf(worker.completedCycles != cycle || worker.nextDeliveryAt != nextAt, "stale-delivery")
                citizens.autonomousVisualWork(cityId, workerId)?.let { work ->
                    rejectIf(!CivsAutonomousWorksites.supports(city, building), "stale-worksite")
                    val pending = try {
                        autonomousVisualMutation(city, work)
                    } catch (failure: Exception) { worksites.invalidate(workerId); throw failure }
                    return@afterCityAvailable pending.whenCompleteSync(tasks) { saved, failure ->
                        worksites.invalidate(workerId, completedWork = work.takeIf { failure == null && saved != null })
                    }.toCompletableFuture()
                }
                if (CivsAutonomousWorksites.supports(city, building)) citizens.requireAutonomousObservedSession(cityId, workerId)
                val plan = citizens.requireAutonomousDelivery(cityId, workerId, CitizenToken(assignment, cycle, nextAt, null))
                plan.industryWork?.let { work ->
                    return@afterCityAvailable authority.mutate(cityId) { CivsIndustry.complete(it, workerId, cycle, nextAt, work, at) }.thenApply { saved ->
                        CivsDiagnostics.event("autonomous_industry_committed", "city" to cityId, "worker" to workerId,
                            "cycle" to cycle, "recipe" to work.recipe.id, "inputs_stock" to work.recipe.stock,
                            "inputs_products" to work.recipe.products, "product" to work.recipe.product,
                            "amount" to work.recipe.amount, "products" to saved.autonomy?.products, "revision" to saved.revision)
                        saved
                    }
                }
                authority.mutate(cityId) {
                    authority.requireAutonomousStorage(it, assignment)
                    CivsRules.deliver(it, it.owner, workerId, assignment, cycle, nextAt, at)
                }.thenApply { saved ->
                    CivsDiagnostics.event("autonomous_resource_delivered", "city" to cityId, "worker" to workerId,
                        "cycle" to cycle, "resource" to CivsRules.resource(building.type),
                        "amount" to CivsRules.deliveryAmount(building), "stock" to saved.stock,
                        "pending_stock" to saved.workers.first { it.id == workerId }.pendingStock, "revision" to saved.revision)
                    saved
                }
            } else if (building.type in CivsWorksites.TYPES) {
                val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
                val work = worksites.plan(city, worker) ?: throw CivsRejection("stale-worksite")
                rejectIf(work.cycle != cycle || work.buildingId != assignment, "stale-delivery")
                if (citizenWorkFocus(work) == null) citizens.requireStorageArrival(cityId, workerId, assignment)
                else citizens.requireWorldWorkArrival(cityId, workerId, work)
                try { worksites.validate(city, work) } catch (failure: Exception) { worksites.invalidate(workerId); throw failure }
                var completedWork: Settlement? = null
                (if (CivsAutonomy.legacyStock(city)) autonomousForestryMutation(city, work, at) else authority.inventoryMutation(cityId, { current ->
                    worksites.validate(current, work)
                    if (citizenWorkFocus(work) == null) citizens.requireStorageArrival(cityId, workerId, assignment)
                    else citizens.requireWorldWorkArrival(cityId, workerId, work)
                    val currentWorker = current.workers.first { it.id == workerId }
                    completedWork = try { CivsRules.finishWorldWork(current, work, at) } catch (failure: CivsRejection) {
                        if (failure.reason == "resident-inventory-full") throw CivsRejection("storage-full") else throw failure
                    }
                    storage.prepareWork(current, assignment, work.storageInputs(currentWorker), emptyMap(), work.changes, work)
                }, keepViewersOpen = true) { _, inventory ->
                    requireNotNull(completedWork).copy(inventory = inventory)
                }).whenCompleteSync(tasks) { saved, failure ->
                    if (failure == null && saved != null) {
                        citizens.terrainChanged(city.worldId, work.changes.map { it.position })
                        val completed = saved.workers.first { it.id == workerId }
                        CivsDiagnostics.event("world_work_committed", "city" to cityId, "worker" to workerId,
                            "building" to assignment, "cycle" to cycle, "revision" to saved.revision,
                            "status" to work.status, "duration_ms" to work.durationMillis,
                            "point" to work.focus, "blocks" to work.changes.size, "inputs" to work.inputs,
                            "carried_before" to work.inputs.mapValues { (material, _) -> CivsEquipment.plainCount(worker, material) },
                            "carried_after" to work.inputs.mapValues { (material, _) -> CivsEquipment.plainCount(completed, material) },
                            "storage_inputs" to if (!CivsAutonomy.legacyStock(city)) work.storageInputs(worker) else emptyMap<String, Long>(),
                            "autonomous_wood_delta" to if (CivsAutonomy.legacyStock(city)) saved.stock.wood - city.stock.wood else null,
                            "seed_deferred" to work.forestry?.job?.seedDeferred,
                            "initial_seed_kit" to (CivsAutonomy.legacyStock(city) && work.forestry == null && !CivsForestry.care(work) && city.stock.wood == 0L),
                            "outputs" to work.outputs,
                            "mining_step" to work.miningStep)
                    }
                    worksites.invalidate(workerId, completedWork = work.takeIf { failure == null && saved != null })
                }.toCompletableFuture()
            } else {
                rejectIf(constructionId != null, "stale-delivery")
                // Automatic carpentry must carry its immutable accepted batch through the actor callback.
                rejectIf(building.type == BuildingType.SAWMILL || authority.craftPlan(city, city.workers.first { it.id == workerId }) != null, "stale-delivery")
                if (building.type in setOf(BuildingType.SMELTERY, BuildingType.CRUSHER) ||
                    building.type == BuildingType.BLACKSMITH && CivsProduction.material(building) in setOf("BUCKET", "SHEARS")) {
                    val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
                    val plan = citizenPlan(city, worker) ?: throw CivsRejection("stale-delivery")
                    rejectIf(plan.token.completedCycles != cycle || plan.token.nextDeliveryAt != nextAt, "stale-delivery")
                    citizens.requireSelectedWorkDelivery(cityId, workerId, plan.token)
                }
                citizens.requireStorageArrival(cityId, workerId, assignment)
                if (city.inventory == null) return@afterCityAvailable authority.mutate(cityId) { CivsRules.deliver(it, it.owner, workerId, assignment, cycle, nextAt, at) }
                // Validate the observed trip before reserving space; repeated callbacks cannot mint again.
                CivsRules.deliver(city, city.owner, workerId, assignment, cycle, nextAt, at, outputHandled = true)
                authority.inventoryMutation(cityId, { storage.prepareProduction(it, it.building(assignment)
                    ?: throw CivsRejection("building-not-found"), it.workers.first { worker -> worker.id == workerId }) }, keepViewersOpen = true) { before, inventory ->
                    CivsRules.deliver(before, before.owner, workerId, assignment, cycle, nextAt, at, outputHandled = true).copy(inventory = inventory)
                }
            }
        }
    }

    /** Recovery of pre-migration Auto forestry retains its original closed-stock receipt. */
    private fun autonomousForestryMutation(city: Settlement, work: CivWorldWork, at: Long): CompletableFuture<Settlement> {
        authority.requireAutonomousStorage(city, work.buildingId)
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val tx = try {
            worksites.validate(city, work)
            val worker = city.workers.single { it.id == work.workerId }
            val after = CivsRules.finishWorldWork(city, work, at)
            val receipt = CivForestryReceipt(worker, city.stock, city.forestryJobs?.get(work.buildingId.toString()),
                city.forestryJobs == null, work, at, city.forestrySeeded)
            CivInventoryTransaction(id, city.id, city.revision, after, emptyList(), worldChanges = work.changes,
                forestry = receipt).also { storage.seal(it, keepViewersOpen = true) }
        } catch (failure: Exception) { storage.unlock(id); throw failure }
        return inventoryJournal.submit(tx)
    }

    /** Scenery has no item or economic debit. A stale cursor rediscovers the actual blocks after restart. */
    private fun autonomousVisualMutation(city: Settlement, work: CivWorldWork): CompletableFuture<Settlement> =
        authority.mutate(city.id) { current ->
            citizens.requireWorldWorkArrival(current.id, work.workerId, work)
            val next = CivsRules.finishAutonomousVisual(current, work)
            worksites.applyAutonomousVisual(current, work)
            citizens.terrainChanged(current.worldId, work.changes.map { it.position })
            next
        }.thenApply { saved ->
            physics.enqueue(saved.worldId, work.changes)
            CivsDiagnostics.event("autonomous_world_work_committed", "city" to saved.id,
                "worker" to work.workerId, "cycle" to work.cycle, "revision" to saved.revision,
                "status" to work.status, "blocks" to work.changes.size, "mining_step" to work.miningStep,
                "native_outputs" to 0, "economic_credit" to false)
            saved
        }

    private fun autonomousUpkeepMutation(city: Settlement, work: CivWorldWork, at: Long): CompletableFuture<Settlement> {
        rejectIf(!CivsRoadMaintenance.eligible(city), "stale-worksite")
        rejectIf(CivsAutonomy.legacyStock(city) && city.stock.stone <= (CivsAutonomy.nextGoal(city, blueprints::maximumLevel)?.cost?.stone ?: 0L), "materials-missing")
        val worker = city.workers.firstOrNull { it.id == work.workerId } ?: throw CivsRejection("worker-not-found")
        rejectIf(worker.completedCycles != work.cycle || worker.assignment != work.buildingId, "stale-delivery")
        rejectIf(at < worker.nextDeliveryAt, "delivery-too-early")
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val tx = try {
            citizens.requireWorldWorkArrival(city.id, work.workerId, work)
            rejectIf(!world.roadUpkeepPending(city, work), "stale-worksite")
            world.validateRoadUpkeep(city, work)
            val prepared = if (CivsAutonomy.legacyStock(city)) null else storage.prepareWork(city, work.buildingId,
                work.storageInputs(worker), emptyMap(), work.changes)
            val after = CivsRoadMaintenance.complete(city, work, at, nativePaid = prepared != null)
            CivInventoryTransaction(id, city.id, city.revision, after, prepared?.changes.orEmpty(), worldChanges = work.changes,
                upkeep = CivRoadUpkeepReceipt(worker, city.stock, work, at)).also { storage.seal(it, keepViewersOpen = true) }
        } catch (failure: Exception) { storage.unlock(id); throw failure }
        return inventoryJournal.submit(tx)
    }

    /** Fresh or unfinished effects retain native authority; exact applied recovery only retires its journal. */
    fun validateUpkeepNative(tx: CivInventoryTransaction): CivInventoryApplyResult? {
        val receipt = tx.upkeep ?: return null
        val current = cities[tx.cityId] ?: return CivInventoryApplyResult.Conflict(mapOf("reason" to "city-unavailable"))
        if (current == tx.after && storage.upkeepNativeApplied(tx)) return null
        return try {
            world.validateRoadUpkeep(if (current == tx.after) receipt.before(tx.after) else current, receipt.work)
            null
        } catch (failure: CivsRejection) {
            val details = failure.values + ("reason" to failure.reason)
            if (failure.reason in setOf("world-unavailable", "world-unloaded", "chunk-unloaded", "chunks-unloaded"))
                CivInventoryApplyResult.Retry(details) else CivInventoryApplyResult.Conflict(details)
        }
    }

    /** Ordinary mine receipts retain their captured progress, but always recheck current native authority. */
    fun validateMineNative(tx: CivInventoryTransaction): CivInventoryApplyResult? {
        val receipt = tx.mine ?: return null
        // The city has already persisted this exact item/tool transition; recovery only retires its journal.
        if (receipt.workerBefore != null && cities[tx.cityId] == tx.after) return null
        return try {
            if (receipt.workerBefore != null) worksites.validateMineFallbackReceiptNative(receipt.before(tx.after), receipt.work)
            else worksites.validateMineReceiptNative(receipt.before(tx.after), receipt.work)
            null
        } catch (failure: CivsRejection) {
            val details = failure.values + ("reason" to failure.reason)
            if (failure.reason in setOf("world-unavailable", "world-unloaded", "chunk-unloaded", "chunks-unloaded"))
                CivInventoryApplyResult.Retry(details) else CivInventoryApplyResult.Conflict(details)
        }
    }

    private fun rejectIf(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
}
