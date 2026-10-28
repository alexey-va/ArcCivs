package ru.arc.civs

import java.util.UUID

internal data class CivAutoSupplyDemand(val buildingId: UUID, val required: Map<String, Long>)

/** Auto performs the same bounded local top-up as an administrator. Every ordinary town stays player supplied. */
internal class CivsAutoSupplies {
    private val cursors = mutableMapOf<UUID, Int>()

    fun next(city: Settlement, constructionNeed: Map<String, Long>?, foods: CivsResidentFoods,
        observation: CivSupplySnapshot? = null, jobs: Map<UUID, CivCraftJob> = emptyMap(),
        selectedInputs: Map<UUID, Map<String, Long>> = emptyMap()): CivAutoSupplyDemand? {
        val demands = demands(city, constructionNeed, foods, observation, jobs, selectedInputs)
        if (demands.isEmpty()) return null
        val cursor = Math.floorMod(cursors[city.id] ?: 0, demands.size)
        cursors[city.id] = (cursor + 1) % demands.size
        return demands[cursor]
    }

    fun retain(ids: Set<UUID>) { cursors.keys.retainAll(ids) }

    companion object {
        private const val MATERIALS_PER_RECEIPT = 8
        private const val BUFFER_PER_MATERIAL = 64L

        private fun carried(worker: CivWorker, material: String): Long {
            val tool = material.substringAfterLast('_')
            if (tool in setOf("AXE", "PICKAXE", "HOE", "SHOVEL") &&
                CivsEquipment.slots(worker).getOrNull(4)?.material?.substringAfterLast('_') == tool) return 1L
            return CivsEquipment.plainCount(worker, material).toLong()
        }

        /** Metadata only; the service also validates every native capture generation before admission. */
        fun observed(city: Settlement, snapshot: CivSupplySnapshot?): Boolean {
            val observation = snapshot?.inputKey as? CivSupplyObservation ?: return false
            return snapshot.cityId == city.id && !snapshot.autonomousStock && observation.inputs == civSupplyInputs(city) &&
                observation.capture.stores.values.all { it.unavailable == 0 } &&
                city.buildings.filter { it.phase == BuildingPhase.ACTIVE && it.control != null }.all {
                    it.id in snapshot.stores && it.id in observation.capture.stores
                }
        }

        fun demands(city: Settlement, constructionNeed: Map<String, Long>?, foods: CivsResidentFoods,
            observation: CivSupplySnapshot? = null, jobs: Map<UUID, CivCraftJob> = emptyMap(),
            selectedInputs: Map<UUID, Map<String, Long>> = emptyMap()): List<CivAutoSupplyDemand> {
            if (city.autonomy?.paused != false || !CivsAutoStorageMigration.ready(city) || city.pendingDeletion != null ||
                !observed(city, observation))
                return emptyList()
            val snapshot = requireNotNull(observation)
            val crafts = jobs.filter { (id, job) -> city.workers.firstOrNull { it.id == id }
                ?.let { CivsCarpentry.valid(city, it, job) } == true }
            val craftReserves = linkedMapOf<UUID, MutableMap<String, Long>>()
            val desired = linkedMapOf<UUID, MutableMap<String, Long>>()
            fun require(buildingId: UUID, materials: Map<String, Long>) {
                val target = desired.getOrPut(buildingId, ::linkedMapOf)
                materials.filterValues { it > 0 }.forEach { (material, count) -> target.merge(material, count, Math::max) }
            }
            city.construction?.takeIf { it.payment != null && it.payment.reservedBatch == null }?.let { order ->
                val stores = CivsRules.constructionStores(city, order.buildingId)
                val target = stores.firstOrNull { building -> city.workers.any { it.assignment == building.id } } ?: stores.firstOrNull()
                val needed = order.payment?.awaiting ?: constructionNeed
                if (target != null && needed != null) {
                    val builders = CivsRules.constructionWorkers(city, order.buildingId)
                    require(target.id, needed.mapValues { (material, count) ->
                        (count - builders.sumOf { worker -> carried(worker, material) }).coerceAtLeast(0)
                    })
                }
            }
            city.buildings.filter { it.phase == BuildingPhase.ACTIVE && it.control != null && !CivsRules.isBuilder(city, it) }
                .forEach { building ->
                    val workers = city.workers.filter { it.assignment == building.id && it.cargo == null && !CivsResidents.isChild(it) }
                    if (building.type == BuildingType.UNIVERSITY) require(building.id, CivsResearch.inputs(city, building))
                    else if (workers.isNotEmpty() && building.type in CivsCarpentry.workshops) {
                        val required = linkedMapOf<String, Long>()
                        workers.forEach { worker ->
                            val inputs = crafts[worker.id]?.inputs ?: if (building.type == BuildingType.SAWMILL) emptyMap()
                                else CivsProduction.inputs(city, building)
                            inputs.forEach { (material, count) ->
                                val missing = (count - carried(worker, material)).coerceAtLeast(0)
                                if (missing > 0) required.merge(material, missing, Math::addExact)
                            }
                        }
                        craftReserves[building.id] = required
                        require(building.id, required)
                    } else if (workers.isNotEmpty()) {
                        require(building.id, CivsLogistics.desiredStock(city, building).mapValues { (material, count) ->
                            (count - workers.sumOf { carried(it, material) }).coerceAtLeast(0)
                        })
                    }
                }
            val selected = linkedMapOf<UUID, MutableMap<String, Long>>()
            selectedInputs.forEach { (id, inputs) ->
                val worker = city.workers.firstOrNull { it.id == id && it.cargo == null && !CivsResidents.isChild(it) }
                    ?: return@forEach
                val building = worker.assignment?.let(city::building)?.takeIf { it.phase == BuildingPhase.ACTIVE && it.control != null }
                    ?: return@forEach
                inputs.forEach { (material, count) ->
                    val missing = (count - carried(worker, material)).coerceAtLeast(0)
                    if (missing > 0) selected.getOrPut(building.id, ::linkedMapOf).merge(material, missing, Math::addExact)
                }
            }
            selected.forEach { (id, inputs) -> require(id, inputs) }
            city.workers.filter { CivsResidents.hunger(it) <= 14 && foods.carried(city, it) == null }.forEach { worker ->
                val home = worker.homeId?.let(city::building)?.takeIf { it.control != null }
                    ?: city.buildings.firstOrNull { it.type == BuildingType.TOWN_HALL && it.control != null }
                if (home != null) require(home.id, mapOf("BREAD" to CivsResidentFood.BATCH.toLong()))
            }
            val incoming = linkedMapOf<UUID, MutableMap<String, Long>>()
            city.workers.mapNotNull { it.cargo }.filter { it.stockResource == null && it.inventoryBacked }
                .distinctBy(CivCargo::id).forEach { cargo ->
                    CivsLogistics.destination(city, cargo)?.id?.let { id ->
                        incoming.getOrPut(id, ::linkedMapOf).merge(cargo.material, cargo.amount, Math::addExact)
                    }
                }
            val returning = linkedMapOf<UUID, MutableMap<String, Long>>()
            city.workers.forEach { worker -> worker.assignment?.let { id ->
                CivsLogistics.workerExcess(city, worker, crafts[worker.id]?.inputs.orEmpty(), foods).forEach { (material, count) ->
                    returning.getOrPut(id, ::linkedMapOf).merge(material, count, Math::addExact)
                }
            } }
            val active = city.buildings.filter { it.phase == BuildingPhase.ACTIVE }.mapTo(hashSetOf(), CivBuilding::id)
            val exporters = snapshot.stores.values.filter { it.id in active &&
                (it.type == BuildingType.WAREHOUSE || it.outputMaterial != null) }
            val maximumDistance = snapshot.maximumTransferDistance.toDouble() * snapshot.maximumTransferDistance
            fun reachable(from: CivPosition, to: CivPosition): Boolean =
                (from.x.toDouble() - to.x).let { it * it } + (from.y.toDouble() - to.y).let { it * it } +
                    (from.z.toDouble() - to.z).let { it * it } <= maximumDistance
            val relays = city.workers.filterNot(CivsResidents::isChild).mapNotNull { worker ->
                worker.assignment?.let(snapshot.stores::get)?.takeIf { it.type == BuildingType.WAREHOUSE && it.id in active }
            }.distinctBy(CivSupplyStore::id)
            fun elsewhere(targetId: UUID, to: CivPosition, material: String): Long =
                exporters.sumOf { source ->
                    if (source.id == targetId || relays.none { reachable(source.control, it.control) && reachable(it.control, to) }) 0L
                    else {
                        val reserve = craftReserves[source.id]?.get(material) ?: source.desiredStock[material] ?: 0L
                        ((source.stock[material] ?: 0L) + (incoming[source.id]?.get(material) ?: 0L) - reserve).coerceAtLeast(0) +
                            (returning[source.id]?.get(material) ?: 0L)
                    }
                }
            return desired.flatMap { (buildingId, materials) ->
                val target = city.building(buildingId)?.control ?: return@flatMap emptyList()
                val local = snapshot.stores[buildingId]?.stock.orEmpty()
                materials.toSortedMap().mapNotNull { (material, count) ->
                    (count - (incoming[buildingId]?.get(material) ?: 0L) - elsewhere(buildingId, target, material)).coerceIn(0, BUFFER_PER_MATERIAL)
                        .takeIf { it > (local[material] ?: 0L) }?.let { material to it }
                }.chunked(MATERIALS_PER_RECEIPT).map { CivAutoSupplyDemand(buildingId, it.toMap()) }
            }
        }
    }
}
