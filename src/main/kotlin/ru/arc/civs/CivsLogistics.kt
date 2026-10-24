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
)

data class CivCourierPlan(val cargo: CivCargo, val destinationBuildingId: UUID,
    val destinationEntrance: CivPosition, val carrying: Boolean)

/** Needed includes goods in transit; only (needed - inTransit) may be assigned again. */
data class CivSupplyRequest(val buildingId: UUID, val material: String, val needed: Long,
    val inTransit: Long, val construction: Boolean = false)

/** Demand is derived from local stores; only accepted, physically picked-up cargo needs persistence. */
object CivsLogistics {
    const val MAX_CARGO = 64L
    internal val QUARRY_SUPPLIES = mapOf("LADDER" to 16L, "TORCH" to 8L, "COBBLESTONE" to 32L)
    internal fun quarrySupplies(building: CivBuilding): Map<String, Long> = QUARRY_SUPPLIES +
        CivsMinePlan.forBuilding(building)?.structuralMaterials.orEmpty().associateWith {
            if (it.endsWith("_FENCE")) 16L else 32L
        }

    private val MATERIAL = Regex("[A-Z][A-Z0-9_]{0,63}")

    fun validCargo(city: Settlement, cargo: CivCargo?): Boolean = cargo == null || runCatching {
        require(city.inventory != null && listOf<Any?>(cargo.id, cargo.warehouseId, cargo.sourceBuildingId, cargo.targetBuildingId,
            cargo.sourceEntrance, cargo.material).all { it != null })
        require(cargo.amount in 1..MAX_CARGO && MATERIAL.matches(cargo.material) && cargo.material !in setOf("AIR", "CAVE_AIR", "VOID_AIR"))
        require(cargo.sourceBuildingId != cargo.targetBuildingId &&
            (cargo.sourceBuildingId == cargo.warehouseId || cargo.targetBuildingId == cargo.warehouseId))
        require(cargo.sourceWorkerId == null || cargo.sourceBuildingId != cargo.warehouseId && cargo.targetBuildingId == cargo.warehouseId)
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
        // Automatic carpentry reserves only an accepted batch, in the supply allocator.
        if (building.type == BuildingType.SAWMILL) return emptyMap()
        val workers = city.workers.count { it.assignment == building.id && it.cargo == null }
        if (workers == 0) return emptyMap()
        return (CivsProduction.inputs(building) + when (building.type) {
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
            if (target.id == warehouse.id || !withinRoute(requireNotNull(warehouse.control), requireNotNull(target.control))) continue
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
            if (!withinRoute(source.control, requireNotNull(warehouse.control))) continue
            val material = CivsProduction.material(source) ?: continue
            val reserve = desiredStock(city, source)[material] ?: 0L
            val amount = minOf(MAX_CARGO, ((stock(source.id)[material] ?: 0L) - reserve).coerceAtLeast(0),
                CivsEquipment.supplyRoom(worker, org.bukkit.Material.valueOf(material)).toLong())
            if (amount > 0) candidates += job(city, worker, warehouse, source, warehouse, material, amount)
        }
        for (producer in city.workers) {
            val meeting = workerSources[producer.id] ?: continue
            val source = producer.assignment?.let(city::building) ?: continue
            if (!withinRoute(meeting, requireNotNull(warehouse.control))) continue
            for ((material, surplus) in workerExcess(city, producer)) {
                val amount = minOf(MAX_CARGO, surplus, CivsEquipment.supplyRoom(worker, org.bukkit.Material.valueOf(material)).toLong(),
                    storageRoom(warehouse.id, material))
                if (amount <= 0) continue
                val id = shipmentId(city.id, worker.id, worker.completedCycles, source.id, warehouse.id, material, producer.id)
                candidates += CivCourierPlan(CivCargo(id, warehouse.id, source.id, warehouse.id, meeting, material, amount,
                    sourceWorkerId = producer.id), warehouse.id, warehouse.entrance, false)
            }
        }
        // The durable completed-trip counter rotates ordinary supply and collection together.
        return if (candidates.isEmpty()) null
            else candidates[Math.floorMod(worker.completedCycles, candidates.size.toLong()).toInt()]
    }

    /** Equipped and custom items stay personal; real work inputs and sixteen bread stay available. */
    internal fun workerExcess(city: Settlement, worker: CivWorker, craftInputs: Map<String, Long> = emptyMap()): Map<String, Long> {
        val building = worker.assignment?.let(city::building)?.takeIf { it.phase == BuildingPhase.ACTIVE && it.control != null }
            ?: return emptyMap()
        if (worker.cargo != null || CivsResidents.isChild(worker) || CivsRules.isBuilder(city, building) ||
            building.type !in setOf(BuildingType.FARM, BuildingType.FORESTER, BuildingType.QUARRY, BuildingType.SAWMILL,
                BuildingType.BLACKSMITH, BuildingType.STONEMASON, BuildingType.LIBRARY, BuildingType.TOWN_HALL)) return emptyMap()
        val reserve = workerReserve(city, worker, craftInputs)
        return CivsEquipment.slots(worker).drop(CivsEquipment.BAG).filterNotNull().filter { item ->
            val type = org.bukkit.Material.valueOf(item.material)
            item.data == null && type.maxDurability.toInt() == 0 && CivsEquipment.armorSlot(item.material) == null
        }.groupingBy(CivResidentItem::material).fold(0L) { count, item -> count + item.amount }
            .mapValues { (material, count) -> (count - (reserve[material] ?: 0L)).coerceAtLeast(0) }
            .filterValues { it > 0 }.toSortedMap()
    }

    /** The same personal food and profession supplies stay with a worker on a home visit. */
    internal fun workerReserve(city: Settlement, worker: CivWorker, craftInputs: Map<String, Long> = emptyMap()): Map<String, Long> {
        val building = worker.assignment?.let(city::building)
        if (building == null) return mapOf("BREAD" to 16L)
        val inputs = if (CivsRules.isBuilder(city, building)) CivsMaterials.unpaid(city.construction?.materials.orEmpty())
            else if (building.type == BuildingType.SAWMILL) craftInputs else CivsProduction.inputs(building)
        return inputs + when (building.type) {
            BuildingType.FARM -> mapOf((when (CivsProduction.material(building)) {
                "CARROT" -> "CARROT"; "POTATO" -> "POTATO"; else -> "WHEAT_SEEDS"
            }) to (CivsWorksiteLayouts.field(building).size.toLong().takeIf { it > 0 } ?: 16L), "BONE_MEAL" to 16L)
            BuildingType.QUARRY -> quarrySupplies(building)
            BuildingType.FORESTER -> mapOf(requireNotNull(CivsProduction.material(building)).removeSuffix("_LOG") + "_SAPLING" to 4L)
            else -> emptyMap()
        } + ("BREAD" to 16L)
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
        val stores = CivsRules.constructionStores(city, buildingId).filter { withinRoute(source, requireNotNull(it.control)) }
        return stores.firstOrNull { building -> city.workers.any { it.assignment == building.id } } ?: stores.firstOrNull()
    }

    /** A retained source uses its real barrel; a removed source retains its durable departure point. */
    fun destination(city: Settlement, cargo: CivCargo): CivBuilding? {
        val source = if (cargo.sourceWorkerId != null) cargo.sourceEntrance else city.building(cargo.sourceBuildingId)?.control ?: cargo.sourceEntrance
        val target = city.building(cargo.targetBuildingId)
        if (target != null && city.construction?.buildingId == target.id && target.type != BuildingType.BUILDER_HUT &&
            target.phase in setOf(BuildingPhase.SITE, BuildingPhase.CONSTRUCTING)) {
            // Old construction shipments retain their receipt identity, but never refill the site barrel.
            return constructionDestination(city, target.id, source)
                ?: city.building(cargo.sourceBuildingId)?.takeIf { it.type == BuildingType.WAREHOUSE && it.control != null &&
                    withinRoute(source, requireNotNull(it.control)) }
                ?: city.buildings.filter { it.type == BuildingType.WAREHOUSE && it.phase == BuildingPhase.ACTIVE && it.control != null &&
                    withinRoute(source, requireNotNull(it.control)) }.minByOrNull { distanceSquared(source, requireNotNull(it.control)) }
        }
        return city.building(cargo.targetBuildingId)?.takeIf { it.control != null }
            ?: city.buildings.filter { it.type == BuildingType.WAREHOUSE && it.phase == BuildingPhase.ACTIVE && it.control != null &&
                withinRoute(source, requireNotNull(it.control)) }
                .minByOrNull { distanceSquared(source, requireNotNull(it.control)) }
            ?: city.buildings.firstOrNull { it.type == BuildingType.TOWN_HALL && it.control != null &&
                withinRoute(source, requireNotNull(it.control)) }
    }

    private fun distanceSquared(from: CivPosition, to: CivPosition): Double =
        (from.x.toDouble() - to.x).let { it * it } + (from.y.toDouble() - to.y).let { it * it } +
            (from.z.toDouble() - to.z).let { it * it }

    private fun withinRoute(from: CivPosition, to: CivPosition): Boolean =
        distanceSquared(from, to) <= CivsRules.MAX_WORK_ROUTE_DISTANCE.toDouble() * CivsRules.MAX_WORK_ROUTE_DISTANCE

    fun pickup(city: Settlement, workerId: UUID, cargo: CivCargo,
        craftInputs: Map<UUID, Map<String, Long>> = emptyMap()): Settlement {
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        if (worker.cargo == cargo.copy(inventoryBacked = true)) return city
        val source = city.building(cargo.sourceBuildingId) ?: throw CivsRejection("stale-delivery")
        val warehouse = city.building(cargo.warehouseId) ?: throw CivsRejection("stale-delivery")
        reject(city.pendingDeletion != null || worker.cargo != null || worker.assignment != cargo.warehouseId || warehouse.type != BuildingType.WAREHOUSE ||
            warehouse.phase != BuildingPhase.ACTIVE || cargo.sourceWorkerId == null && source.entrance != cargo.sourceEntrance ||
            cargo.id != shipmentId(city.id, workerId, worker.completedCycles, cargo.sourceBuildingId, cargo.targetBuildingId, cargo.material, cargo.sourceWorkerId) ||
            !validCargo(city, cargo), "stale-delivery")
        val producer = cargo.sourceWorkerId?.let { id ->
            city.workers.firstOrNull { it.id == id }?.takeIf { it.id != workerId && it.assignment == source.id &&
                withinRoute(cargo.sourceEntrance, requireNotNull(warehouse.control)) &&
                (workerExcess(city, it, craftInputs[it.id].orEmpty())[cargo.material] ?: 0L) >= cargo.amount } ?: throw CivsRejection("stale-delivery")
        }
        val supplied = CivsEquipment.collectSupplies(worker, mapOf(cargo.material to cargo.amount))
            .copy(cargo = cargo.copy(inventoryBacked = true))
        val emptied = producer?.let {
            val items = CivsEquipment.slots(it).toMutableList()
            var remaining = cargo.amount.toInt()
            for (index in CivsEquipment.BAG until items.size) {
                val item = items[index]?.takeIf { item -> item.data == null && item.material == cargo.material } ?: continue
                val moved = minOf(remaining, item.amount)
                items[index] = item.copy(amount = item.amount - moved).takeIf { item -> item.amount > 0 }
                remaining -= moved
                if (remaining == 0) break
            }
            check(remaining == 0)
            CivsEquipment.replace(it, items)
        }
        return CivsRules.validate(city.copy(workers = city.workers.map { when (it.id) {
            workerId -> supplied; producer?.id -> requireNotNull(emptied); else -> it
        } },
            revision = Math.addExact(city.revision, 1)))
    }

    /** The service durably commits this revision before exposing old shipments to gameplay. */
    fun materializeCargo(city: Settlement): Settlement {
        val workers = city.workers.map { worker ->
            val cargo = worker.cargo?.takeUnless { it.inventoryBacked } ?: return@map worker
            CivsEquipment.materializeLegacyCargo(worker, cargo)
        }
        return if (workers == city.workers) city else CivsRules.validate(city.copy(workers = workers,
            revision = Math.addExact(city.revision, 1)))
    }

    fun unload(city: Settlement, workerId: UUID, cargoId: UUID, at: Long): Settlement {
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        reject(worker.cargo?.id != cargoId, "stale-delivery")
        val cargo = requireNotNull(worker.cargo)
        reject(!cargo.inventoryBacked, "materials-migration-pending")
        val emptied = CivsEquipment.consume(worker.copy(cargo = null), cargo.material, cargo.amount.toInt())
        val updated = CivsResidents.trained(emptied.copy(completedCycles = Math.addExact(worker.completedCycles, 1),
            nextDeliveryAt = Math.addExact(at, CivsRules.BUILDER_SESSION_INTERVAL)), BuildingType.WAREHOUSE)
        return CivsRules.validate(city.copy(workers = city.workers.map { if (it.id == workerId) updated else it },
            revision = Math.addExact(city.revision, 1)))
    }

    private fun reject(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
}
