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
    /** Missing in legacy JSON: those items still need one durable inventory materialization. */
    val inventoryBacked: Boolean = false,
    /** Null retains the existing local-container endpoint and shipment identity. */
    val sourceWorkerId: UUID? = null,
    /** Closed autonomous stock uses the same shipment identity without materializing an item. */
    val stockResource: Resource? = null,
    /** A native carried shipment may return to another city depot without changing its original identity. */
    val deliveryBuildingId: UUID? = null,
    /** Only new native Auto evacuation trips use this proof; historical shipments default to false. */
    val redistribution: Boolean = false,
)

data class CivCourierPlan(val cargo: CivCargo, val destinationBuildingId: UUID,
    val destinationEntrance: CivPosition, val carrying: Boolean)

/** Needed includes goods in transit; only (needed - inTransit) may be assigned again. */
data class CivSupplyRequest(val buildingId: UUID, val material: String, val needed: Long,
    val inTransit: Long, val construction: Boolean = false)

/** Demand is derived from local stores; only accepted, physically picked-up cargo needs persistence. */
object CivsLogistics {
    const val MAX_CARGO = 64L
    const val MAX_TRANSFER_DISTANCE = 256
    internal val QUARRY_SUPPLIES = mapOf("LADDER" to 16L, "TORCH" to 8L, "COBBLESTONE" to 32L)
    internal fun quarrySupplies(building: CivBuilding): Map<String, Long> = QUARRY_SUPPLIES +
        CivsMinePlan.forBuilding(building)?.structuralMaterials.orEmpty().associateWith {
            if (it.endsWith("_FENCE")) 16L else 32L
        }

    private val MATERIAL = Regex("[A-Z][A-Z0-9_]{0,63}")

    fun validCargo(city: Settlement, cargo: CivCargo?): Boolean = cargo == null || runCatching {
        require((city.inventory != null && cargo.stockResource == null ||
            city.autonomy != null && city.inventory == null && cargo.stockResource != null && !cargo.inventoryBacked &&
                cargo.material == cargo.stockResource.name && cargo.sourceWorkerId != null) &&
            listOf<Any?>(cargo.id, cargo.warehouseId, cargo.sourceBuildingId, cargo.targetBuildingId,
            cargo.sourceEntrance, cargo.material).all { it != null })
        require(cargo.amount in 1..MAX_CARGO && MATERIAL.matches(cargo.material) && cargo.material !in setOf("AIR", "CAVE_AIR", "VOID_AIR"))
        require(cargo.sourceBuildingId != cargo.targetBuildingId &&
            (cargo.sourceBuildingId == cargo.warehouseId || cargo.targetBuildingId == cargo.warehouseId))
        require(cargo.sourceWorkerId == null || cargo.sourceBuildingId != cargo.warehouseId && cargo.targetBuildingId == cargo.warehouseId)
        require(cargo.deliveryBuildingId == null || city.inventory?.mode == CivStorageMode.PHYSICAL &&
            cargo.inventoryBacked && cargo.stockResource == null)
        require(!cargo.redistribution || city.autonomy != null && city.inventory?.mode == CivStorageMode.PHYSICAL &&
            cargo.sourceWorkerId == null && cargo.stockResource == null)
        require(city.contains(cargo.sourceEntrance.x, cargo.sourceEntrance.z) && cargo.sourceEntrance.y in -4_096..4_096)
    }.isSuccess

    fun requests(city: Settlement, stock: (UUID) -> Map<String, Long>): List<CivSupplyRequest> {
        if (city.inventory == null || city.pendingDeletion != null) return emptyList()
        val carried = city.workers.mapNotNull(CivWorker::cargo)
        val destinations = carried.associate { it.id to destination(city, it)?.id }
        fun incoming(id: UUID, material: String) = carried.filter { destinations[it.id] == id && it.material == material }.sumOf(CivCargo::amount)
        val requests = mutableListOf<CivSupplyRequest>()
        fun request(buildingId: UUID, required: Map<String, Long>, construction: Boolean) {
            val local = stock(buildingId)
            for ((material, desired) in required.toSortedMap()) {
                val needed = (desired - (local[material] ?: 0L)).coerceAtLeast(0)
                val inTransit = incoming(buildingId, material)
                if (needed > 0 || inTransit > 0) requests += CivSupplyRequest(buildingId, material, needed, inTransit, construction)
            }
        }
        city.construction?.takeIf { it.payment != null }?.let { order ->
            val stores = CivsRules.constructionStores(city, order.buildingId)
            val target = stores.firstOrNull { building -> city.workers.any { it.assignment == building.id } } ?: stores.firstOrNull()
            if (target != null) {
                val available = mutableMapOf<String, Long>()
                stores.forEach { building -> stock(building.id).forEach { (material, count) -> available.merge(material, count, Math::addExact) } }
                CivsRules.constructionWorkers(city, order.buildingId).forEach { worker ->
                    CivsEquipment.slots(worker).filterNotNull().filter { it.data == null }.forEach {
                        available.merge(it.material, it.amount.toLong(), Math::addExact)
                    }
                }
                for ((material, desired) in CivsMaterials.unpaid(order.materials.orEmpty()).toSortedMap()) {
                    val needed = (desired - (available[material] ?: 0L)).coerceAtLeast(0)
                    val inTransit = stores.sumOf { incoming(it.id, material) }
                    if (needed > 0 || inTransit > 0) requests += CivSupplyRequest(target.id, material, needed, inTransit, true)
                }
            }
        }
        city.buildings.filter { it.phase == BuildingPhase.ACTIVE && it.control != null && !CivsRules.isBuilder(city, it) }.forEach { building ->
            val required = desiredStock(city, building)
            if (required.isNotEmpty()) request(building.id, required, false)
        }
        return requests
    }

    internal fun desiredStock(city: Settlement, building: CivBuilding): Map<String, Long> {
        if (building.type == BuildingType.TAVERN) return if (building.phase == BuildingPhase.ACTIVE && building.control != null)
            mapOf("BREAD" to CivsResidentFood.tavernStock(city)) else emptyMap()
        if (building.type == BuildingType.UNIVERSITY) return CivsProduction.inputs(city, building)
        // Automatic carpentry reserves only an accepted batch, in the supply allocator.
        if (building.type == BuildingType.SAWMILL) return emptyMap()
        val workers = city.workers.count { it.assignment == building.id && it.cargo == null }
        if (workers == 0) return emptyMap()
        return (CivsProduction.inputs(city, building) + when (building.type) {
            BuildingType.FARM -> mapOf((when (CivsProduction.material(building)) { "CARROT" -> "CARROT"; "POTATO" -> "POTATO"; else -> "WHEAT_SEEDS" }) to 16L, "BONE_MEAL" to 16L, "IRON_HOE" to 1L)
            BuildingType.FORESTER -> mapOf(requireNotNull(CivsProduction.material(building)).removeSuffix("_LOG") + "_SAPLING" to 4L, "IRON_AXE" to 1L)
            BuildingType.QUARRY -> quarrySupplies(building) + ("IRON_PICKAXE" to 1L)
            else -> emptyMap()
        }).filterValues { it > 0 }.mapValues { (_, amount) ->
            Math.multiplyExact(amount, workers.toLong())
        }
    }

    fun plan(city: Settlement, worker: CivWorker, stock: (UUID) -> Map<String, Long>): CivCourierPlan? =
        plan(city, worker, stock, emptyMap()) { _, _ -> MAX_CARGO }

    fun plan(city: Settlement, worker: CivWorker, stock: (UUID) -> Map<String, Long>,
        workerSources: Map<UUID, CivPosition>, storageRoom: (UUID, String) -> Long): CivCourierPlan? {
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
            val target = if (request.construction) city.construction?.let {
                constructionDestination(city, it.buildingId, requireNotNull(warehouse.control))
            } else city.building(request.buildingId)?.takeIf { it.control != null }
            if (target == null) continue
            if (target.id == warehouse.id || !withinRoute(city, requireNotNull(warehouse.control), requireNotNull(target.control))) continue
            val amount = minOf(MAX_CARGO, (request.needed - request.inTransit).coerceAtLeast(0), available[request.material] ?: 0,
                CivsEquipment.supplyRoom(worker, org.bukkit.Material.valueOf(request.material)).toLong())
            if (amount > 0) {
                val candidate = job(city, worker, warehouse, warehouse, target, request.material, amount)
                if (request.construction) return candidate
                candidates += candidate
            }
        }
        for (source in city.buildings) {
            if (source.phase != BuildingPhase.ACTIVE || source.control == null || CivsRules.isBuilder(city, source)) continue
            if (!withinRoute(city, source.control, requireNotNull(warehouse.control))) continue
            val material = CivsProduction.material(source) ?: continue
            val reserve = desiredStock(city, source)[material] ?: 0L
            val amount = minOf(MAX_CARGO, ((stock(source.id)[material] ?: 0L) - reserve).coerceAtLeast(0),
                CivsEquipment.supplyRoom(worker, org.bukkit.Material.valueOf(material)).toLong())
            if (amount > 0) candidates += job(city, worker, warehouse, source, warehouse, material, amount)
        }
        // The durable completed-trip counter rotates ordinary supply and collection together.
        return if (candidates.isEmpty()) null
            else candidates[Math.floorMod(worker.completedCycles, candidates.size.toLong()).toInt()]
    }

    internal fun redistributionAllowed(city: Settlement, cargo: CivCargo): Boolean {
        if (!cargo.redistribution || cargo.inventoryBacked || cargo.deliveryBuildingId != null ||
            city.autonomy?.paused != false || city.inventory?.mode != CivStorageMode.PHYSICAL ||
            !CivsAutoStorageMigration.ready(city) || city.pendingDeletion != null || !validCargo(city, cargo)) return false
        val source = city.building(cargo.sourceBuildingId) ?: return false
        val target = city.building(cargo.targetBuildingId) ?: return false
        return source.type == BuildingType.WAREHOUSE && source.phase == BuildingPhase.ACTIVE && source.control != null &&
            target.type in setOf(BuildingType.WAREHOUSE, BuildingType.TOWN_HALL) &&
            target.phase == BuildingPhase.ACTIVE && target.control != null &&
            city.construction?.buildingId !in setOf(source.id, target.id) &&
            withinRoute(city, source.control, target.control)
    }

    /** Equipped and custom items stay personal; real work inputs and sixteen bread stay available. */
    internal fun workerExcess(city: Settlement, worker: CivWorker, craftInputs: Map<String, Long> = emptyMap(),
        foods: CivsResidentFoods = CivsResidentFoods.BREAD_ONLY): Map<String, Long> {
        if (city.autonomy != null && city.inventory == null) return if (worker.cargo == null && !CivsResidents.isChild(worker))
            Resource.entries.associate { it.name to (worker.pendingStock?.get(it) ?: 0L) }.filterValues { it > 0 }
        else emptyMap()
        val building = worker.assignment?.let(city::building)?.takeIf { it.phase == BuildingPhase.ACTIVE && it.control != null }
            ?: return emptyMap()
        if (worker.cargo != null || CivsResidents.isChild(worker) || CivsRules.isBuilder(city, building) ||
            building.type !in setOf(BuildingType.FARM, BuildingType.FORESTER, BuildingType.QUARRY, BuildingType.SAWMILL,
                BuildingType.BLACKSMITH, BuildingType.STONEMASON, BuildingType.LIBRARY, BuildingType.TOWN_HALL)) return emptyMap()
        val reserve = workerReserve(city, worker, craftInputs, foods)
        return CivsEquipment.slots(worker).drop(CivsEquipment.BAG).filterNotNull().filter { item ->
            val type = org.bukkit.Material.valueOf(item.material)
            item.data == null && type.maxDurability.toInt() == 0 && CivsEquipment.armorSlot(item.material) == null
        }.groupingBy(CivResidentItem::material).fold(0L) { count, item -> count + item.amount }
            .mapValues { (material, count) -> (count - (reserve[material] ?: 0L)).coerceAtLeast(0) }
            .filterValues { it > 0 }.toSortedMap()
    }

    /** The same personal food and profession supplies stay with a worker on a home visit. */
    internal fun workerReserve(city: Settlement, worker: CivWorker, craftInputs: Map<String, Long> = emptyMap(),
        foods: CivsResidentFoods = CivsResidentFoods.BREAD_ONLY): Map<String, Long> {
        val reserve = workerWorkReserve(city, worker, craftInputs).toMutableMap()
        foods.personalReserve(worker).forEach { (material, count) ->
            reserve[material] = if (material == "BREAD") maxOf(reserve[material] ?: 0, count)
                else Math.addExact(reserve[material] ?: 0, count)
        }
        return reserve
    }

    internal fun workerWorkReserve(city: Settlement, worker: CivWorker, craftInputs: Map<String, Long> = emptyMap()): Map<String, Long> {
        val building = worker.assignment?.let(city::building)
        if (building == null) return emptyMap()
        val inputs = if (CivsRules.isBuilder(city, building)) CivsMaterials.unpaid(city.construction?.materials.orEmpty())
            else if (building.type == BuildingType.SAWMILL) craftInputs else CivsProduction.inputs(city, building)
        return inputs + when (building.type) {
            BuildingType.FARM -> mapOf((when (CivsProduction.material(building)) {
                "CARROT" -> "CARROT"; "POTATO" -> "POTATO"; else -> "WHEAT_SEEDS"
            }) to (CivsWorksiteLayouts.field(building).size.toLong().takeIf { it > 0 } ?: 16L), "BONE_MEAL" to 16L)
            BuildingType.QUARRY -> quarrySupplies(building)
            BuildingType.FORESTER -> mapOf(requireNotNull(CivsProduction.material(building)).removeSuffix("_LOG") + "_SAPLING" to 4L)
            else -> emptyMap()
        }
    }

    private fun job(city: Settlement, worker: CivWorker, warehouse: CivBuilding, source: CivBuilding,
        target: CivBuilding, material: String, amount: Long): CivCourierPlan {
        val id = shipmentId(city.id, worker.id, worker.completedCycles, source.id, target.id, material)
        return CivCourierPlan(CivCargo(id, warehouse.id, source.id, target.id, source.entrance, material, amount),
            target.id, target.entrance, false)
    }

    internal fun shipmentId(city: UUID, worker: UUID, cycle: Long, source: UUID, target: UUID, material: String,
        sourceWorkerId: UUID? = null): UUID = UUID.nameUUIDFromBytes(
        ("$city/$worker/$cycle/$source/$target/$material" + (sourceWorkerId?.let { "/$it" } ?: "")).toByteArray(StandardCharsets.UTF_8))

    internal fun constructionDestination(city: Settlement, buildingId: UUID, source: CivPosition): CivBuilding? {
        val stores = CivsRules.constructionStores(city, buildingId).filter { withinRoute(city, source, requireNotNull(it.control)) }
        return stores.firstOrNull { building -> city.workers.any { it.assignment == building.id } } ?: stores.firstOrNull()
    }

    /** A retained source uses its real barrel; a removed source retains its durable departure point. */
    fun destination(city: Settlement, cargo: CivCargo): CivBuilding? {
        cargo.deliveryBuildingId?.let { id -> city.building(id)?.takeIf {
            nativeCargoDepot(it) && withinRoute(city, cargo.sourceEntrance, requireNotNull(it.control))
        }?.let { return it } }
        val source = if (cargo.sourceWorkerId != null) cargo.sourceEntrance else city.building(cargo.sourceBuildingId)?.control ?: cargo.sourceEntrance
        val target = city.building(cargo.targetBuildingId)
        if (target != null && city.construction?.buildingId == target.id && target.type != BuildingType.BUILDER_HUT &&
            target.phase in setOf(BuildingPhase.SITE, BuildingPhase.CONSTRUCTING)) {
            // Old construction shipments retain their receipt identity, but never refill the site barrel.
            return constructionDestination(city, target.id, source)
                ?: city.building(cargo.sourceBuildingId)?.takeIf { it.type == BuildingType.WAREHOUSE && it.control != null &&
                    withinRoute(city, source, requireNotNull(it.control)) }
                ?: city.buildings.filter { it.type == BuildingType.WAREHOUSE && it.phase == BuildingPhase.ACTIVE && it.control != null &&
                    withinRoute(city, source, requireNotNull(it.control)) }.minByOrNull { distanceSquared(source, requireNotNull(it.control)) }
        }
        return city.building(cargo.targetBuildingId)?.takeIf { it.control != null }
            ?: city.buildings.filter { it.type == BuildingType.WAREHOUSE && it.phase == BuildingPhase.ACTIVE && it.control != null &&
                withinRoute(city, source, requireNotNull(it.control)) }
                .minByOrNull { distanceSquared(source, requireNotNull(it.control)) }
            ?: city.buildings.firstOrNull { it.type == BuildingType.TOWN_HALL && it.control != null &&
                withinRoute(city, source, requireNotNull(it.control)) }
    }

    private fun nativeCargoDepot(building: CivBuilding): Boolean = building.phase == BuildingPhase.ACTIVE &&
        building.type in setOf(BuildingType.WAREHOUSE, BuildingType.TOWN_HALL) && building.control != null

    /** Selection is read-only. The owner supplies current, permitted local room; a partial batch is never rerouted. */
    internal fun redirectDestination(city: Settlement, cargo: CivCargo, failedDestinationId: UUID, from: CivPosition,
        room: (UUID, String) -> Long): CivBuilding? {
        if (city.inventory?.mode != CivStorageMode.PHYSICAL || !cargo.inventoryBacked ||
            cargo.stockResource != null || !validCargo(city, cargo)) return null
        return city.buildings.asSequence().filter { it.id != failedDestinationId && nativeCargoDepot(it) &&
            withinRoute(city, cargo.sourceEntrance, requireNotNull(it.control)) &&
            withinRoute(city, from, requireNotNull(it.control)) }
            .sortedWith(compareBy<CivBuilding> { if (it.type == BuildingType.WAREHOUSE) 0 else 1 }
                .thenBy { distanceSquared(from, requireNotNull(it.control)) }.thenBy { it.id.toString() })
            .firstOrNull { room(it.id, cargo.material) >= cargo.amount }
    }

    /** Only the delivery address changes; the accepted item batch, bag and worker token remain intact. */
    internal fun redirect(city: Settlement, workerId: UUID, expected: CivCargo, destinationId: UUID): Settlement {
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        reject(city.pendingDeletion != null || city.inventory?.mode != CivStorageMode.PHYSICAL ||
            worker.cargo != expected || !expected.inventoryBacked || expected.stockResource != null, "stale-delivery")
        val destination = city.building(destinationId) ?: throw CivsRejection("building-not-found")
        reject(!nativeCargoDepot(destination) || !withinRoute(city, expected.sourceEntrance, requireNotNull(destination.control)), "stale-delivery")
        val changed = expected.copy(deliveryBuildingId = destinationId)
        if (changed == expected) return city
        return CivsRules.validate(city.copy(workers = city.workers.map {
            if (it.id == workerId) it.copy(cargo = changed) else it
        }, revision = Math.addExact(city.revision, 1)))
    }

    /** Automatic cities may place producers on opposite sides; normal supply retains its existing reach. */
    fun maximumTransferDistance(city: Settlement): Int = if (city.autonomy == null) CivsRules.MAX_WORK_ROUTE_DISTANCE
        else (city.radius.toLong() * 2).coerceIn(CivsRules.MAX_WORK_ROUTE_DISTANCE.toLong(), MAX_TRANSFER_DISTANCE.toLong()).toInt()

    private fun distanceSquared(from: CivPosition, to: CivPosition): Double =
        (from.x.toDouble() - to.x).let { it * it } + (from.y.toDouble() - to.y).let { it * it } +
            (from.z.toDouble() - to.z).let { it * it }

    internal fun withinRoute(city: Settlement, from: CivPosition, to: CivPosition): Boolean =
        distanceSquared(from, to) <= maximumTransferDistance(city).toDouble() * maximumTransferDistance(city)

    fun pickup(city: Settlement, workerId: UUID, cargo: CivCargo,
        craftInputs: Map<UUID, Map<String, Long>> = emptyMap(),
        foods: CivsResidentFoods = CivsResidentFoods.BREAD_ONLY): Settlement {
        if (cargo.stockResource != null) return CivsAutonomousLogistics.pickup(city, workerId, cargo)
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        if (worker.cargo == cargo.copy(inventoryBacked = true)) return city
        reject(cargo.sourceWorkerId != null, "stale-delivery")
        val source = city.building(cargo.sourceBuildingId) ?: throw CivsRejection("stale-delivery")
        val warehouse = city.building(cargo.warehouseId) ?: throw CivsRejection("stale-delivery")
        reject(city.pendingDeletion != null || worker.cargo != null || worker.assignment != cargo.warehouseId || warehouse.type != BuildingType.WAREHOUSE ||
            warehouse.phase != BuildingPhase.ACTIVE || cargo.sourceWorkerId == null && source.entrance != cargo.sourceEntrance ||
            cargo.id != shipmentId(city.id, workerId, worker.completedCycles, cargo.sourceBuildingId, cargo.targetBuildingId, cargo.material, cargo.sourceWorkerId) ||
            !validCargo(city, cargo), "stale-delivery")
        val supplied = CivsEquipment.collectSupplies(worker, mapOf(cargo.material to cargo.amount))
            .copy(cargo = cargo.copy(inventoryBacked = true))
        return CivsRules.validate(city.copy(workers = city.workers.map { if (it.id == workerId) supplied else it },
            revision = Math.addExact(city.revision, 1)))
    }

    /** The service durably commits this revision before exposing old shipments to gameplay. */
    fun materializeCargo(city: Settlement): Settlement {
        val workers = city.workers.map { worker ->
            val cargo = worker.cargo?.takeUnless { it.inventoryBacked || it.stockResource != null } ?: return@map worker
            CivsEquipment.materializeLegacyCargo(worker, cargo)
        }
        return if (workers == city.workers) city else CivsRules.validate(city.copy(workers = workers,
            revision = Math.addExact(city.revision, 1)))
    }

    fun unload(city: Settlement, workerId: UUID, cargoId: UUID, at: Long): Settlement {
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        reject(worker.cargo?.id != cargoId, "stale-delivery")
        val cargo = requireNotNull(worker.cargo)
        if (cargo.stockResource != null) return CivsAutonomousLogistics.unload(city, worker, cargo, at)
        reject(!cargo.inventoryBacked, "materials-migration-pending")
        val emptied = CivsEquipment.consume(worker.copy(cargo = null), cargo.material, cargo.amount.toInt())
        val updated = CivsResidents.trained(emptied.copy(completedCycles = Math.addExact(worker.completedCycles, 1),
            nextDeliveryAt = Math.addExact(at, CivsRules.BUILDER_SESSION_INTERVAL)), BuildingType.WAREHOUSE, city)
        return CivsRules.validate(city.copy(workers = city.workers.map { if (it.id == workerId) updated else it },
            revision = Math.addExact(city.revision, 1)))
    }

    private fun reject(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
}
