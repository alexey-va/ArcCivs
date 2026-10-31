package ru.arc.civs

import org.bukkit.Material
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.UUID
import java.util.concurrent.CompletableFuture

private const val UPKEEP_SUPPLY_BUFFER = 16L

internal class CivsResidentSupplies(
    private val authority: CivsService,
    private val records: CivsCityRecords,
    private val cargoRequestsOwner: () -> CivsCargoRequests,
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val residentFoods: CivsResidentFoods,
    private val world: CivsWorld,
    private val roadMaintenance: CivsRoadMaintenance,
    private val storage: CivsStorage,
    private val citizens: CivsCitizens,
    private val supply: CivsSupplyDispatcher,
    private val construction: CivsConstructionRuntime,
    private val inventoryJournal: CivsInventoryJournal,
) {
    private val cargoRequests get() = cargoRequestsOwner()
    private var testingSupplyCursor = 0
    private val supplyNoopDiagnostics = linkedMapOf<UUID, String>()
    private var nextResidentFoodAt = 0L
    private var residentFoodCursor = 0
    private val autoSupplies = CivsAutoSupplies()
    private val forestrySurplus = CivsForestrySurplus()
    private val surplusRefreshAt = mutableMapOf<UUID, Long>()
    private var nextAutoSupplyAt = 0L
    private var autoSupplyCursor = 0

    /** New forestry admission observes existing supply evidence; it never scans or moves inventory. */
    internal fun forestrySurplus(city: Settlement, timber: String): Boolean {
        val now = authority.now()
        val decision = forestrySurplus.observe(city, supply.observation(city.id), supply.craftJobs(city.id), now) {
            storage.supplyCaptureCurrent(city, it, requireComplete = true)
        }
        if (decision.familyStock == null && now >= surplusRefreshAt.getOrDefault(city.id, Long.MIN_VALUE)) {
            supply.request(city.id)
            surplusRefreshAt[city.id] = now + 5_000L
        }
        return decision.blocks(timber)
    }

    internal fun forestrySurplusDebug(cityId: UUID?): Map<String, Any?> = forestrySurplus.debug(cityId, authority.now())

    internal fun craftPlan(city: Settlement, worker: CivWorker): CivCraftJob? =
        supply.craftJobs(city.id)[worker.id]?.takeIf { CivsCarpentry.valid(city, worker, it) }

    /** Recipe context only; status and public scene reads never capture native inventories. */
    internal fun workStatusInputs(city: Settlement, worker: CivWorker): List<String> {
        val building = worker.assignment?.let(city::building) ?: return emptyList()
        val inputs = craftPlan(city, worker)?.inputs ?: if (building.type == BuildingType.SAWMILL) return emptyList()
            else CivsProduction.inputs(city, building)
        return CivsEquipment.storageInputs(worker, inputs).keys.ifEmpty { inputs.keys }.toList()
    }

    fun craftObservationCurrent(city: Settlement): Boolean {
        val snapshot = supply.craftObservation(city.id) ?: return false
        val observation = snapshot.inputKey as? CivSupplyObservation ?: return false
        return observation.inputs == civSupplyInputs(city) &&
            storage.supplyCaptureCurrent(city, observation.capture, snapshot.stores.keys)
    }

    private fun requireCraftJob(city: Settlement, worker: CivWorker, id: UUID?): CivCraftJob {
        val job = craftPlan(city, worker)?.takeIf { it.id == id } ?: throw CivsRejection("stale-delivery")
        rejectIf(!craftObservationCurrent(city), "stale-delivery")
        return job
    }

    internal fun craftRequired(city: Settlement, buildingId: UUID): Map<String, Long> {
        val required = linkedMapOf<String, Long>()
        city.workers.filter { it.assignment == buildingId }.forEach { worker ->
            craftPlan(city, worker)?.inputs?.let { CivsEquipment.storageInputs(worker, it) }?.forEach { (material, count) ->
                required.merge(material, count, Math::addExact)
            }
        }
        return required
    }

    fun workSupplyRequests(city: Settlement, stock: (UUID) -> Map<String, Long>): List<CivSupplyRequest> =
        city.buildings.filter { it.type in CivsCarpentry.workshops }.flatMap { building ->
            craftRequired(city, building.id).mapNotNull { (material, count) ->
                val needed = (count - (stock(building.id)[material] ?: 0L)).coerceAtLeast(0)
                val incoming = city.workers.mapNotNull(CivWorker::cargo).filter {
                    it.material == material && CivsLogistics.destination(city, it)?.id == building.id
                }.sumOf(CivCargo::amount)
                if (needed > 0 || incoming > 0) CivSupplyRequest(building.id, material, needed, incoming) else null
            }
        } + upkeepSupplyRequests(city, stock)

    fun deliverCraft(cityId: UUID, workerId: UUID, assignment: UUID, cycle: Long, nextAt: Long,
        jobId: UUID, at: Long): CompletableFuture<Settlement> = authority.action("craft", null,
        "city" to cityId, "worker" to workerId, "job" to jobId, "cycle" to cycle) {
        authority.afterCityAvailable(cityId) {
            var committedJob: CivCraftJob? = null
            var completed: Settlement? = null
            authority.inventoryMutation(cityId, { current ->
                val worker = current.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
                val job = craftPlan(current, worker)?.takeIf { it.id == jobId } ?: throw CivsRejection("stale-delivery")
                rejectIf(job.buildingId != assignment || job.cycle != cycle, "stale-delivery")
                val selected = citizenPlan(current, worker) ?: throw CivsRejection("stale-delivery")
                citizens.requireSelectedWorkDelivery(cityId, workerId, selected.token.copy(craftJobId = job.id), job)
                citizens.requireStorageArrival(cityId, workerId, assignment)
                val equipment = if (requireNotNull(CivsCarpentry.recipe(job)).stripping) CivsEquipment.strippedWithAxe(worker) else null
                completed = CivsRules.deliver(current, current.owner, workerId, assignment, cycle, nextAt, at,
                    outputHandled = true, craftJob = job, equipmentAfter = equipment)
                committedJob = job
                storage.prepareProduction(current, requireNotNull(current.building(assignment)), worker, job)
            }, keepViewersOpen = true) { _, inventory -> requireNotNull(completed).copy(inventory = inventory) }
                .whenCompleteSync(tasks) { saved, failure ->
                    if (failure == null && saved != null) {
                        supply.releaseCraft(cityId, workerId, jobId)
                        val job = requireNotNull(committedJob)
                        CivsDiagnostics.event("craft_committed", "city" to cityId, "worker" to workerId,
                            "job" to jobId, "recipe" to job.recipeId, "recipe_version" to job.version,
                            "cycle" to cycle, "inputs" to job.inputs, "material" to job.material, "amount" to job.amount,
                            "demand" to job.demandMaterial, "target" to job.targetBuildingId, "revision" to saved.revision)
                    }
                }.toCompletableFuture()
        }
    }

    internal fun upkeepSupplyRequests(city: Settlement, stock: (UUID) -> Map<String, Long>): List<CivSupplyRequest> =
        authority.upkeepRequirements(city).flatMap { (workerId, inputs) ->
            val worker = city.workers.first { it.id == workerId }
            val target = requireNotNull(worker.assignment)
            // The accepted column still costs one item; real courier stock covers several
            // subsequent columns instead of requiring a round trip after every cut.
            CivsEquipment.storageInputs(worker, inputs.mapValues { (_, count) -> maxOf(count, UPKEEP_SUPPLY_BUFFER) })
                .mapNotNull { (material, count) ->
                    val incoming = city.workers.mapNotNull(CivWorker::cargo).filter {
                        it.material == material && CivsLogistics.destination(city, it)?.id == target
                    }.sumOf(CivCargo::amount)
                    val need = (count - (stock(target)[material] ?: 0L)).coerceAtLeast(0)
                    if (need > 0 || incoming > 0) CivSupplyRequest(target, material, need, incoming) else null
                }
        }

    fun localStocks(city: Settlement): (UUID) -> Map<String, Long> {
        val cached = mutableMapOf<UUID, Map<String, Long>>()
        return { id -> cached.getOrPut(id) {
            try { storage.buildingAvailable(city, id) } catch (failure: CivsRejection) {
                if (failure.reason in setOf("storage-unavailable", "storage-container-invalid", "building-not-found")) emptyMap()
                else throw failure
            }
        } }
    }

    /** Local stock permits the trip to storage; work still requires the subsequent physical pickup. */
    internal fun productionReady(city: Settlement, worker: CivWorker): Boolean {
        val building = worker.assignment?.let(city::building) ?: return true
        if (CivsAutonomy.legacyStock(city)) return false
        if (building.type == BuildingType.UNIVERSITY) return CivsRules.productionAvailable(city, building)
        if (building.type in CivsIndustry.processorTypes && CivsProduction.material(building) !in CivsProduction.options(city, building)) return false
        if (CivsRules.isBuilder(city, building) || building.type in CivsWorksites.TYPES) return true
        val inputs = craftPlan(city, worker)?.inputs ?: if (building.type == BuildingType.SAWMILL) return false
            else CivsProduction.inputs(city, building)
        return CivsEquipment.inputsReady(worker, inputs, localStocks(city)(building.id))
    }

    internal fun requireAutonomousStorage(city: Settlement, buildingId: UUID? = null) {
        rejectIf(CivsAutonomy.legacyStock(city) && !storage.autonomousCapacityCurrent(city, buildingId), "storage-observation-stale")
    }

    fun requireAutonomousDepotStorage(city: Settlement) {
        CivsAutonomousStorage.depots(city).forEach { requireAutonomousStorage(city, it.id) }
    }

    internal fun workInputsReady(city: Settlement, worker: CivWorker): Boolean {
        val building = worker.assignment?.let(city::building) ?: return true
        if (CivsAutonomy.legacyStock(city)) return false
        if (building.type == BuildingType.UNIVERSITY) return CivsRules.productionAvailable(city, building)
        if (CivsRules.isBuilder(city, building)) {
            val order = city.construction ?: return roadMaintenance.requirements(city)[worker.id]?.let {
                CivsEquipment.inputsReady(worker, it, localStocks(city)(building.id))
            } ?: true
            if (order.payment == null || order.payment.reservedBatch != null) return true
            val required = order.payment.awaiting ?: construction.supplyNeed(city)
            // The first captured row may only remove blocks; its exact debit is prepared before placement.
            return required == null || CivsRules.constructionStorageInputs(city, order.buildingId, required).isEmpty()
        }
        if (building.type in CivsWorksites.TYPES) return true
        val inputs = craftPlan(city, worker)?.inputs ?: if (building.type == BuildingType.SAWMILL) return false
            else CivsProduction.inputs(city, building)
        return CivsEquipment.inputsReady(worker, inputs, emptyMap())
    }

    internal fun residentSupplySource(city: Settlement, worker: CivWorker): UUID? {
        // Physical food uses its exact reachable native container rather than a whole-building debit.
        if (city.inventory?.mode != CivStorageMode.VIRTUAL) return null
        if (city.inventory == null || CivsResidents.hunger(worker) > 14 || CivsEquipment.usableCount(worker, "BREAD") > 0 ||
            CivsEquipment.supplyRoom(worker, Material.BREAD) == 0) return null
        return city.buildings.sortedBy { if (it.type == BuildingType.TAVERN) 0 else 1 }.firstOrNull {
            (it.type == BuildingType.TOWN_HALL || it.type == BuildingType.TAVERN && it.phase == BuildingPhase.ACTIVE) && it.control != null &&
            (localStocks(city)(it.id)["BREAD"] ?: 0L) > 0L }?.id
    }

    fun citizensFeetForFood(workerId: UUID): org.bukkit.Location? = citizens.residentFeet(workerId)

    internal fun pickupResidentFood(cityId: UUID, workerId: UUID, source: CivResidentFoodSource): CompletableFuture<Settlement> =
        authority.action("resident_food_pickup", null, "city" to cityId, "worker" to workerId, "building" to source.buildingId) {
            authority.afterCityAvailable(cityId) {
                cargoRequests.collectedMutation(cityId, workerId) { city, feet ->
                    source.servingFor?.let { citizens.validateTavernPickup(cityId, workerId, it, source.buildingId,
                        source.material, source.servingTicket ?: throw CivsRejection("stale-meal")) }
                    storage.prepareFoodPickup(city, workerId, source, feet)
                }
            }
        }

    internal fun deliverTavernMeal(cityId: UUID, delivery: CivTavernDelivery): CompletableFuture<Settlement> =
        authority.afterCityAvailable(cityId) {
            citizens.validateTavernDelivery(cityId, delivery)
            authority.mutate(cityId) { current ->
                citizens.validateTavernDelivery(cityId, delivery)
                CivsTavernMeals.deliver(current, delivery, residentFoods)
            }
        }

    internal fun materializeResidentFood(cityId: UUID, workerId: UUID): CompletableFuture<Settlement> =
        authority.action("resident_food_materialize", null, "city" to cityId, "worker" to workerId) {
            cargoRequests.collectedMutation(cityId, workerId, materializingFood = true) { city, feet ->
                storage.prepareFoodMaterialization(city, workerId, feet)
            }
        }

    fun pulseResidentFood() {
        if (authority.now() < nextResidentFoodAt) return
        val candidates = records.cities.values.filter { it.autonomy?.paused == false && it.stock.food > 0 &&
            it.pendingDeletion == null && cargoRequests.collectionAvailable(it.id) && authority.settlementActive(it) && it.workers.any { worker ->
                citizens.residentFeet(worker.id) != null
            } }
        if (candidates.isEmpty()) return
        val city = candidates[Math.floorMod(residentFoodCursor++, candidates.size)]
        val worker = city.workers.firstOrNull { citizens.residentFeet(it.id) != null } ?: return
        nextResidentFoodAt = authority.now() + 5_000
        runCatching { materializeResidentFood(city.id, worker.id) }.onSuccess { future ->
            future.whenComplete { _, failure -> if (failure != null && CivsDiagnostics.rejection(failure)?.reason !in
                    setOf("city-busy", "resident-food-missing", "worker-too-far")) authority.log("materialize resident food ${city.id}", failure, city.id) }
        }.onFailure { if (CivsDiagnostics.rejection(it)?.reason !in setOf("city-busy", "resident-food-missing", "worker-too-far"))
            authority.log("materialize resident food ${city.id}", it, city.id) }
    }

    internal fun returnResidentOutputs(cityId: UUID, workerId: UUID, buildingId: UUID): CompletableFuture<Settlement> =
        authority.action("resident_outputs", null, "city" to cityId, "worker" to workerId, "building" to buildingId) {
            authority.afterCityAvailable(cityId) {
                var returned: CivWorker? = null
                authority.inventoryMutation(cityId, { city ->
                    citizens.requireStorageArrival(cityId, workerId, buildingId)
                    val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
                    val prepared = storage.prepareResidentReturn(city, workerId, buildingId, craftPlan(city, worker)?.inputs.orEmpty())
                    returned = prepared.worker
                    rejectIf(prepared.worker == worker, "storage-full")
                    prepared.plan
                }, keepViewersOpen = true) { before, inventory ->
                    before.copy(workers = before.workers.map { if (it.id == workerId) requireNotNull(returned) else it },
                        inventory = inventory, revision = Math.addExact(before.revision, 1))
                }
            }
        }

    internal fun prepareResidentSupplies(cityId: UUID, workerId: UUID, buildingId: UUID, craftJobId: UUID? = null): CompletableFuture<Settlement> =
        authority.action("resident_supplies", null, "city" to cityId, "worker" to workerId, "building" to buildingId) {
            authority.afterCityAvailable(cityId) {
                var supplied: CivWorker? = null
                var supplyContext = emptyArray<Pair<String, Any?>>()
                var carriedBefore = emptyMap<String, Long>()
                var required: Map<String, Long>? = null
                var reportSupply = false
                var supplyChanged = false
                authority.inventoryMutation(cityId, { city ->
                    val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
                    val source = city.building(buildingId) ?: throw CivsRejection("building-not-found")
                    citizens.requireStorageArrival(cityId, workerId, buildingId)
                    carriedBefore = CivsEquipment.available(worker, emptyMap())
                    val food = (source.type == BuildingType.TOWN_HALL || source.type == BuildingType.TAVERN && source.phase == BuildingPhase.ACTIVE) && CivsResidents.hunger(worker) <= 14 &&
                        CivsEquipment.usableCount(worker, "BREAD") == 0
                    val builder = !food && CivsRules.isBuilder(city, source)
                    reportSupply = worker.assignment?.let(city::building)?.let { CivsRules.isBuilder(city, it) } == true
                    val order = city.construction.takeIf { reportSupply }
                    supplyContext = arrayOf("city" to cityId, "worker" to workerId, "source" to buildingId,
                        "target" to source.control, "order" to order?.id, "batch" to order?.workDone,
                        "construction_phase" to order?.phase, "reserved_batch" to order?.payment?.reservedBatch,
                        "reason" to if (food) "food" else if (order?.payment?.awaiting != null) "next-required-type" else "work-materials")
                    val prepared = if (food) {
                        val count = minOf(4L, storage.buildingAvailable(city, buildingId)["BREAD"] ?: 0L,
                            CivsEquipment.supplyRoom(worker, Material.BREAD).toLong())
                        rejectIf(count == 0L, "resident-food-missing")
                        required = mapOf("BREAD" to count)
                        storage.prepareResidentPickup(city, workerId, buildingId, mapOf("BREAD" to count))
                    } else {
                        rejectIf(worker.assignment != buildingId || worker.cargo != null || CivsResidents.isChild(worker), "invalid-assignment")
                        if (builder) {
                            required = city.construction?.payment?.awaiting ?: construction.supplyNeed(city)
                            storage.prepareBuilderPickup(city, workerId, required.orEmpty())
                        } else {
                            rejectIf(!CivsRules.productionAvailable(city, source) || source.type in CivsWorksites.TYPES, "invalid-assignment")
                            required = if (craftJobId != null || source.type == BuildingType.SAWMILL) requireCraftJob(city, worker, craftJobId).inputs
                                else CivsProduction.inputs(city, source)
                            storage.prepareResidentPickup(city, workerId, buildingId,
                                CivsEquipment.storageInputs(worker, requireNotNull(required)))
                        }
                    }
                    supplied = prepared.worker
                    supplyChanged = prepared.worker != worker || prepared.plan.changes.isNotEmpty() || prepared.plan.inventoryAfter != city.inventory
                    if (supplyChanged) prepared.plan else null
                }, keepViewersOpen = true) { before, inventory ->
                    val worker = requireNotNull(supplied)
                    before.copy(workers = before.workers.map { if (it.id == workerId) worker else it },
                        inventory = inventory, revision = Math.addExact(before.revision, 1))
                }.thenApply { saved ->
                    construction.materialsChanged()
                    if (!reportSupply) return@thenApply saved
                    val worker = requireNotNull(supplied)
                    val carriedAfter = CivsEquipment.available(worker, emptyMap())
                    val picked = carriedAfter.mapValues { (material, count) -> count - carriedBefore.getOrDefault(material, 0) }.filterValues { it > 0 }
                    val returned = carriedBefore.mapValues { (material, count) -> count - carriedAfter.getOrDefault(material, 0) }.filterValues { it > 0 }
                    val fields = supplyContext + arrayOf("outcome" to if (supplyChanged) "committed" else "no-op",
                        "required_known" to (required != null), "required" to required,
                        "missing_before" to required?.mapValues { (material, count) -> (count - carriedBefore.getOrDefault(material, 0)).coerceAtLeast(0) }?.filterValues { it > 0 },
                        "missing_after" to required?.let { CivsEquipment.storageInputs(worker, it) },
                        "carried_before" to carriedBefore, "carried_after" to carriedAfter,
                        "picked" to picked, "returned" to returned, "amount" to picked.values.sum())
                    val noOp = !supplyChanged
                    val signature = if (noOp) CivsDiagnostics.line("resident_supplies_result", *fields) else null
                    if (!noOp || supplyNoopDiagnostics.put(workerId, requireNotNull(signature)) != signature)
                        CivsDiagnostics.event("resident_supplies_result", *fields, "revision" to saved.revision)
                    if (!noOp) supplyNoopDiagnostics.remove(workerId)
                    // Only coalesce unchanged no-ops; real committed transfers are always reported.
                    if (supplyNoopDiagnostics.size > 128) supplyNoopDiagnostics.remove(supplyNoopDiagnostics.keys.first())
                    saved
                }
            }
        }

    private fun testingConstructionNeed(city: Settlement): Map<String, Long>? {
        val order = city.construction ?: return null
        return order.payment?.awaiting ?: construction.supplyNeed(city)
    }

    /** One bounded journal per pulse. Recovery replays this saved receipt, never a second issuance. */
    internal fun prepareTestingConstructionSupplies(cityId: UUID): CompletableFuture<Settlement> {
        var grant: CivTestingConstructionGrant? = null
        return authority.inventoryMutation(cityId, { city ->
            if (city.settings?.autoConstructionMaterials != true || !authority.testingAllowed(city)) return@inventoryMutation null
            val required = testingConstructionNeed(city) ?: return@inventoryMutation null
            val order = city.construction ?: return@inventoryMutation null
            grant = civsTestingConstructionGrant(city, required, storage.constructionAvailable(city, order.buildingId))
            if (grant == null) null else CivStoragePlan(requireNotNull(city.inventory), emptyList())
        }, keepViewersOpen = true) { before, inventory ->
            before.copy(workers = requireNotNull(grant).workers, inventory = inventory, revision = Math.addExact(before.revision, 1))
        }.thenApply { saved ->
            grant?.issued?.forEach { (worker, materials) ->
                CivsDiagnostics.event("testing_construction_materials_granted", "city" to saved.id, "worker" to worker,
                    "order" to saved.construction?.id, "batch" to saved.construction?.workDone, "materials" to materials,
                    "revision" to saved.revision)
            }
            saved
        }
    }

    fun pulseTestingSupplies() {
        val candidates = records.cities.values.filter { city -> city.settings?.autoConstructionMaterials == true &&
            city.id !in records.busy && city.id !in records.unhealthy && city.pendingDeletion == null && city.inventory != null &&
            authority.settlementActive(city) && authority.testingAllowed(city) && testingConstructionNeed(city) != null }
        if (candidates.isEmpty()) return
        val city = candidates[Math.floorMod(testingSupplyCursor++, candidates.size)]
        runCatching { prepareTestingConstructionSupplies(city.id) }.onSuccess { future ->
            future.whenComplete { _, failure -> if (failure != null) authority.log("issue testing construction supplies ${city.id}", failure, city.id) }
        }.onFailure { authority.log("issue testing construction supplies ${city.id}", it, city.id) }
    }

    fun pulseAutoSupplies() {
        forestrySurplus.retain(records.cities.keys)
        surplusRefreshAt.keys.retainAll(records.cities.keys)
        if (authority.now() < nextAutoSupplyAt) return
        val candidates = records.cities.values.filter { it.autonomy?.paused == false && CivsAutoStorageMigration.ready(it) &&
            it.id !in records.busy && it.id !in records.unhealthy && it.pendingDeletion == null && !inventoryJournal.pending(it.id) && authority.settlementActive(it) }
        autoSupplies.retain(records.cities.values.filter { it.autonomy != null }.mapTo(hashSetOf(), Settlement::id))
        if (candidates.isEmpty()) return
        val city = candidates[Math.floorMod(autoSupplyCursor++, candidates.size)]
        nextAutoSupplyAt = authority.now() + 1_000L
        val observation = autoSupplyObservation(city) ?: return
        val need = runCatching { construction.supplyNeed(city) }.getOrNull()
        val demand = autoSupplies.next(city, need, residentFoods, observation, supply.craftJobs(city.id), authority.upkeepRequirements(city)) ?: return
        runCatching { authority.inventoryMutation(city.id, { current ->
            val fresh = autoSupplyObservation(current) ?: throw CivsRejection("storage-observation-stale")
            rejectIf(demand !in CivsAutoSupplies.demands(current, construction.supplyNeed(current), residentFoods,
                fresh, supply.craftJobs(current.id), authority.upkeepRequirements(current)), "stale-delivery")
            val nativeWorld = plugin.server.getWorld(current.worldId) ?: throw CivsRejection("world-unavailable")
            storage.prepareAutoSupply(current, demand) { point ->
                world.canModify(current.owner, nativeWorld.getBlockAt(point.x, point.y, point.z))
            }
        }, keepViewersOpen = true) { before, inventory ->
            before.copy(inventory = inventory, revision = Math.addExact(before.revision, 1))
        } }.onSuccess { accepted -> accepted.whenCompleteSync(tasks) { saved, failure ->
            if (failure == null && saved != city) {
                construction.materialsChanged()
                CivsDiagnostics.event("autonomous_supplies_granted", "city" to city.id, "building" to demand.buildingId,
                    "requested" to demand.required, "revision" to saved?.revision)
            } else if (failure != null && CivsDiagnostics.rejection(failure)?.reason !in setOf("city-busy", "stale-delivery",
                    "storage-unavailable", "storage-observation-stale", "chunks-unloaded", "chunk-unloaded", "lands-denied"))
                authority.log("supply autonomous workplace ${city.id}", failure, city.id)
        } }.onFailure { failure ->
            if (CivsDiagnostics.rejection(failure)?.reason !in setOf("city-busy", "stale-delivery", "storage-unavailable",
                    "storage-observation-stale", "chunks-unloaded", "chunk-unloaded", "lands-denied")) authority.log("supply autonomous workplace ${city.id}", failure, city.id)
        }
    }

    /** Reuse the accepted bounded capture; no whole-city native read belongs in an Auto top-up pulse. */
    private fun autoSupplyObservation(city: Settlement): CivSupplySnapshot? {
        val snapshot = supply.observation(city.id)
        val observed = snapshot?.inputKey as? CivSupplyObservation
        if (!CivsAutoSupplies.observed(city, snapshot) || observed == null || !storage.supplyCaptureCurrent(city, observed.capture)) {
            supply.request(city.id)
            return null
        }
        return snapshot
    }

    private fun rejectIf(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
}
