package ru.arc.civs

import org.bukkit.Material
import java.util.UUID

/** Only supply inputs participate in optimistic validation, never the city's general revision. */
internal fun civSupplyInputs(city: Settlement): Any = listOf(
    city.worldId, city.center, city.radius, city.buildings, city.construction, city.pendingDeletion,
    city.inventory?.mode, city.inventory?.containers,
    city.inventory?.takeIf { it.mode == CivStorageMode.VIRTUAL },
    city.workers.map { listOf(it.id, it.assignment, it.completedCycles, it.inventory, it.items, it.cargo, it.childMinutes != null) },
)

/** Owner metadata is stripped before dispatch; no native inventory or callback enters the worker. */
internal data class CivSupplyObservation(val inputs: Any, val capture: CivSupplyCaptureResult)

internal class CivsSupplyPlanning(
    private val city: Settlement,
    private val capture: CivsSupplyCapture,
    private val retained: Map<UUID, CivCourierPlan>,
    private val position: (UUID) -> CivPosition?,
    private val sources: () -> Map<UUID, CivPosition>,
    private val unavailable: (UUID) -> Boolean,
    private val crafts: Map<UUID, CivCraftJob> = emptyMap(),
) {
    private var stores: Map<UUID, CivSupplyStore>? = null
    private var requests = emptyList<CivSupplyRequest>()
    private var meetings = emptyMap<UUID, CivPosition>()
    private val workerSources = mutableListOf<CivSupplyWorkerSource>()
    private val couriers = mutableListOf<CivSupplyCourier>()
    private val craftWorkers = mutableListOf<CivCraftWorker>()
    private var materials = emptyMap<String, Material>()
    private var workerIndex = 0

    fun step(): CivSupplySnapshot? {
        if (stores == null) {
            if (!capture.step()) return null
            stores = requireNotNull(capture.result).stores.mapNotNull { (id, observed) ->
                val building = city.building(id)?.takeIf { it.control != null } ?: return@mapNotNull null
                id to CivSupplyStore(id, building.entrance, requireNotNull(building.control), observed.stock,
                    CivsLogistics.desiredStock(city, building),
                    CivsProduction.material(building).takeIf { building.phase == BuildingPhase.ACTIVE && !CivsRules.isBuilder(city, building) },
                    observed.capacity, building.type)
            }.toMap()
            requests = CivsLogistics.requests(city) { stores?.get(it)?.stock.orEmpty() }
            meetings = sources()
            // The universe also includes arbitrary plain excess such as gravel and dirt.
            val names = requests.map(CivSupplyRequest::material).toMutableSet()
            stores.orEmpty().values.forEach { names += it.stock.keys }
            city.workers.forEach { names += CivsEquipment.materials(it).keys }
            if (city.buildings.any { it.type == BuildingType.SAWMILL }) names += CivsCarpentry.materials
            materials = names.mapNotNull { name -> Material.getMaterial(name)?.takeIf { it.isItem && !it.isAir }?.let { name to it } }.toMap()
            return null
        }
        if (workerIndex < city.workers.size) {
            val worker = city.workers[workerIndex++]
            val building = worker.assignment?.let(city::building)
            if (worker.cargo == null && !CivsResidents.isChild(worker) && building?.type == BuildingType.SAWMILL &&
                CivsRules.productionAvailable(city, building) && building.control != null) {
                craftWorkers += CivCraftWorker(worker.id, building.id, worker.completedCycles, building.level,
                    CivsEquipment.available(worker, emptyMap()).toMap(), CivsEquipment.slots(worker)[4]?.material?.endsWith("_AXE") == true)
            }
            if (worker.cargo == null && !unavailable(worker.id) && !CivsResidents.isChild(worker) && building?.type == BuildingType.WAREHOUSE &&
                building.phase == BuildingPhase.ACTIVE && building.control != null) {
                couriers += CivSupplyCourier(worker.id, building.id, worker.completedCycles,
                    position(worker.id) ?: building.entrance,
                    materials.mapValues { (_, type) -> CivsEquipment.supplyRoom(worker, type).toLong() })
            }
            meetings[worker.id]?.let { point ->
                if (building != null) workerSources += CivSupplyWorkerSource(worker.id, building.id, point,
                    CivsLogistics.workerExcess(city, worker, crafts[worker.id]?.inputs.orEmpty()))
            }
            return null
        }
        val existing = retained.mapNotNull { (workerId, plan) ->
            val worker = city.workers.firstOrNull { it.id == workerId } ?: return@mapNotNull null
            if (worker.cargo == null && unavailable(workerId)) return@mapNotNull null
            val warehouse = city.building(plan.cargo.warehouseId)
            if (warehouse == null || worker.assignment != warehouse.id || warehouse.type != BuildingType.WAREHOUSE ||
                warehouse.phase != BuildingPhase.ACTIVE || warehouse.control == null ||
                CivsLogistics.shipmentId(city.id, worker.id, worker.completedCycles, plan.cargo.sourceBuildingId,
                    plan.cargo.targetBuildingId, plan.cargo.material, plan.cargo.sourceWorkerId) != plan.cargo.id)
                return@mapNotNull null
            val source = city.building(plan.cargo.sourceBuildingId) ?: return@mapNotNull null
            val target = CivsLogistics.destination(city, plan.cargo) ?: return@mapNotNull null
            if (target.id != plan.destinationBuildingId || target.entrance != plan.destinationEntrance ||
                target.control == null || source.control == null ||
                plan.cargo.sourceWorkerId == null && source.entrance != plan.cargo.sourceEntrance ||
                plan.cargo.sourceWorkerId?.let { id -> city.workers.none { it.id == id && it.assignment == source.id } } == true)
                return@mapNotNull null
            workerId to plan
        }.toMap().toMutableMap()
        city.workers.forEach { worker -> worker.cargo?.let { cargo ->
            CivsLogistics.destination(city, cargo)?.let { target ->
                existing[worker.id] = CivCourierPlan(cargo, target.id, target.entrance, true)
            }
        } }
        val constructionTargets = city.construction?.let { order ->
            couriers.mapNotNull { courier ->
                val source = stores?.get(courier.warehouseId) ?: return@mapNotNull null
                CivsLogistics.constructionDestination(city, order.buildingId, source.control)?.let { courier.warehouseId to it.id }
            }.toMap()
        }.orEmpty()
        return CivSupplySnapshot(city.id, CivSupplyObservation(civSupplyInputs(city), requireNotNull(capture.result)), requireNotNull(stores),
            requests, couriers.toList(), workerSources.toList(), existing.toMap(), materials.mapValues { it.value.maxStackSize },
            constructionTargets, craftWorkers.toList(), crafts.toMap(), constructionId = city.construction?.id)
    }
}
