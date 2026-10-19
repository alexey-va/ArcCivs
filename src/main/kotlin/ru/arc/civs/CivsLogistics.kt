package ru.arc.civs

import java.nio.charset.StandardCharsets
import java.util.UUID

/** An accepted shipment is owned by its carrier until one exact unload receipt retires it. */
data class CivCargo(
    val id: UUID,
    val warehouseId: UUID,
    val sourceBuildingId: UUID,
    val targetBuildingId: UUID,
    val sourceEntrance: CivPosition,
    val material: String,
    val amount: Long,
)

data class CivCourierPlan(val cargo: CivCargo, val destinationBuildingId: UUID,
    val destinationEntrance: CivPosition, val carrying: Boolean)

/** Needed includes goods in transit; only (needed - inTransit) may be assigned again. */
data class CivSupplyRequest(val buildingId: UUID, val material: String, val needed: Long,
    val inTransit: Long, val construction: Boolean = false)

/** Demand is derived from local stores; only accepted, physically picked-up cargo needs persistence. */
object CivsLogistics {
    const val MAX_CARGO = 64L
    private val MATERIAL = Regex("[A-Z][A-Z0-9_]{0,63}")

    fun validCargo(city: Settlement, cargo: CivCargo?): Boolean = cargo == null || runCatching {
        require(city.inventory != null && listOf<Any?>(cargo.id, cargo.warehouseId, cargo.sourceBuildingId, cargo.targetBuildingId,
            cargo.sourceEntrance, cargo.material).all { it != null })
        require(cargo.amount in 1..MAX_CARGO && MATERIAL.matches(cargo.material) && cargo.material !in setOf("AIR", "CAVE_AIR", "VOID_AIR"))
        require(cargo.sourceBuildingId != cargo.targetBuildingId &&
            (cargo.sourceBuildingId == cargo.warehouseId || cargo.targetBuildingId == cargo.warehouseId))
        require(city.contains(cargo.sourceEntrance.x, cargo.sourceEntrance.z) && cargo.sourceEntrance.y in -4_096..4_096)
    }.isSuccess

    fun requests(city: Settlement, stock: (UUID) -> Map<String, Long>): List<CivSupplyRequest> {
        if (city.inventory == null || city.pendingDeletion != null) return emptyList()
        val carried = city.workers.mapNotNull(CivWorker::cargo)
        fun incoming(id: UUID, material: String) = carried.filter { it.targetBuildingId == id && it.material == material }.sumOf(CivCargo::amount)
        val requests = mutableListOf<CivSupplyRequest>()
        fun request(buildingId: UUID, required: Map<String, Long>, construction: Boolean) {
            val local = stock(buildingId)
            for ((material, desired) in required.toSortedMap()) {
                val needed = (desired - (local[material] ?: 0L)).coerceAtLeast(0)
                val inTransit = incoming(buildingId, material)
                if (needed > 0 || inTransit > 0) requests += CivSupplyRequest(buildingId, material, needed, inTransit, construction)
            }
        }
        city.construction?.let { order ->
            order.payment?.awaiting?.takeIf { city.building(order.buildingId)?.control != null }
                ?.let { request(order.buildingId, it, true) }
        }
        city.buildings.filter { it.phase == BuildingPhase.ACTIVE && it.control != null && !CivsRules.isBuilder(city, it) }.forEach { building ->
            val workers = city.workers.count { it.assignment == building.id && it.cargo == null }
            if (workers > 0) request(building.id, CivsProduction.inputs(building).mapValues { (_, amount) ->
                Math.multiplyExact(amount, workers.toLong())
            }, false)
        }
        return requests
    }

    fun plan(city: Settlement, worker: CivWorker, stock: (UUID) -> Map<String, Long>): CivCourierPlan? {
        if (city.inventory == null || city.pendingDeletion != null) return null
        worker.cargo?.let { cargo ->
            val destination = destination(city, cargo) ?: return null
            return CivCourierPlan(cargo, destination.id, destination.entrance, true)
        }
        val warehouse = worker.assignment?.let(city::building)?.takeIf {
            it.type == BuildingType.WAREHOUSE && it.phase == BuildingPhase.ACTIVE && it.control != null
        } ?: return null
        val available = stock(warehouse.id)
        val candidates = mutableListOf<CivCourierPlan>()
        for (request in requests(city, stock)) {
            val target = city.building(request.buildingId)?.takeIf { it.control != null } ?: continue
            if (target.id == warehouse.id || !withinRoute(warehouse.entrance, target.entrance)) continue
            val amount = minOf(MAX_CARGO, (request.needed - request.inTransit).coerceAtLeast(0), available[request.material] ?: 0)
            if (amount > 0) {
                val candidate = job(city, worker, warehouse, warehouse, target, request.material, amount)
                if (request.construction) return candidate
                candidates += candidate
            }
        }
        for (source in city.buildings) {
            if (source.phase != BuildingPhase.ACTIVE || source.control == null || CivsRules.isBuilder(city, source)) continue
            if (!withinRoute(source.entrance, warehouse.entrance)) continue
            val material = CivsProduction.material(source) ?: continue
            val amount = minOf(MAX_CARGO, stock(source.id)[material] ?: 0L)
            if (amount > 0) candidates += job(city, worker, warehouse, source, warehouse, material, amount)
        }
        // The durable completed-trip counter rotates ordinary supply and collection together.
        return if (candidates.isEmpty()) null
            else candidates[Math.floorMod(worker.completedCycles, candidates.size.toLong()).toInt()]
    }

    private fun job(city: Settlement, worker: CivWorker, warehouse: CivBuilding, source: CivBuilding,
        target: CivBuilding, material: String, amount: Long): CivCourierPlan {
        val id = shipmentId(city.id, worker.id, worker.completedCycles, source.id, target.id, material)
        return CivCourierPlan(CivCargo(id, warehouse.id, source.id, target.id, source.entrance, material, amount),
            target.id, target.entrance, false)
    }

    private fun shipmentId(city: UUID, worker: UUID, cycle: Long, source: UUID, target: UUID, material: String): UUID =
        UUID.nameUUIDFromBytes("$city/$worker/$cycle/$source/$target/$material".toByteArray(StandardCharsets.UTF_8))

    /** Deleted destinations return to a reachable warehouse/hall from the persisted source point. */
    fun destination(city: Settlement, cargo: CivCargo): CivBuilding? =
        city.building(cargo.targetBuildingId)?.takeIf { it.control != null }
            ?: city.buildings.filter { it.type == BuildingType.WAREHOUSE && it.phase == BuildingPhase.ACTIVE && it.control != null &&
                withinRoute(cargo.sourceEntrance, it.entrance) }
                .minByOrNull { distanceSquared(cargo.sourceEntrance, it.entrance) }
            ?: city.buildings.firstOrNull { it.type == BuildingType.TOWN_HALL && it.control != null &&
                withinRoute(cargo.sourceEntrance, it.entrance) }

    private fun distanceSquared(from: CivPosition, to: CivPosition): Double =
        (from.x.toDouble() - to.x).let { it * it } + (from.y.toDouble() - to.y).let { it * it } +
            (from.z.toDouble() - to.z).let { it * it }

    private fun withinRoute(from: CivPosition, to: CivPosition): Boolean =
        distanceSquared(from, to) <= CivsRules.MAX_WORK_ROUTE_DISTANCE.toDouble() * CivsRules.MAX_WORK_ROUTE_DISTANCE

    fun pickup(city: Settlement, workerId: UUID, cargo: CivCargo): Settlement {
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        if (worker.cargo == cargo) return city
        val source = city.building(cargo.sourceBuildingId) ?: throw CivsRejection("stale-delivery")
        val warehouse = city.building(cargo.warehouseId) ?: throw CivsRejection("stale-delivery")
        reject(city.pendingDeletion != null || worker.cargo != null || worker.assignment != cargo.warehouseId || warehouse.type != BuildingType.WAREHOUSE ||
            warehouse.phase != BuildingPhase.ACTIVE || source.entrance != cargo.sourceEntrance ||
            cargo.id != shipmentId(city.id, workerId, worker.completedCycles, cargo.sourceBuildingId, cargo.targetBuildingId, cargo.material) ||
            !validCargo(city, cargo), "stale-delivery")
        return CivsRules.validate(city.copy(workers = city.workers.map { if (it.id == workerId) it.copy(cargo = cargo) else it },
            revision = Math.addExact(city.revision, 1)))
    }

    fun unload(city: Settlement, workerId: UUID, cargoId: UUID, at: Long): Settlement {
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        reject(worker.cargo?.id != cargoId, "stale-delivery")
        val updated = CivsResidents.trained(worker.copy(cargo = null, completedCycles = Math.addExact(worker.completedCycles, 1),
            nextDeliveryAt = Math.addExact(at, CivsRules.BUILDER_SESSION_INTERVAL)), BuildingType.WAREHOUSE)
        return CivsRules.validate(city.copy(workers = city.workers.map { if (it.id == workerId) updated else it },
            revision = Math.addExact(city.revision, 1)))
    }

    private fun reject(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
}
