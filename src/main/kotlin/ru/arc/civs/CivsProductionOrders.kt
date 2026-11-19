package ru.arc.civs

import java.util.UUID

/** A finite permission to start work. Items, paid trees and transfers remain with their normal receipt owners. */
internal data class CivProductionOrder(val buildingId: UUID, val configured: String, val material: String,
    val fromCycle: Long, val untilCycle: Long, val unitsPerCycle: Long, val anyTimber: Boolean = false) {
    fun matches(worker: CivWorker): Boolean = worker.assignment == buildingId && worker.cargo == null &&
        worker.completedCycles in fromCycle until untilCycle
    fun matches(building: CivBuilding): Boolean = building.id == buildingId && CivsProduction.material(building) == configured
    fun allows(material: String): Boolean = this.material == material || anyTimber && CivsCarpentry.timberFamily(material)
}

/** Only immutable settlement values and the unique native count cross to the supply worker. */
internal data class CivProductionSurvey(val city: Settlement, val plainStock: Map<String, Long>?,
    val capturedAt: Long?, val unavailable: String?, val knownStores: Set<UUID>? = null)
internal data class CivProductionBoard(val orders: Map<UUID, CivProductionOrder> = emptyMap(),
    val timber: CivsForestrySurplus.Decision? = null, val capturedAt: Long? = null, val reviewedAt: Long? = null,
    val failure: String? = null, val partial: Boolean = false)

internal object CivsProductionOrders {
    const val REVIEW_MILLIS = 30_000L
    private const val MAX_CYCLES = 4L

    fun manages(building: CivBuilding): Boolean = when (building.type) {
        BuildingType.FORESTER, BuildingType.QUARRY, BuildingType.FARM, BuildingType.TOWN_HALL,
        BuildingType.BLACKSMITH, BuildingType.STONEMASON, BuildingType.LIBRARY, BuildingType.BAKERY,
        BuildingType.SMELTERY, BuildingType.CRUSHER -> true
        else -> false
    }

    fun applies(city: Settlement, building: CivBuilding): Boolean = city.inventory != null && manages(building) &&
        !CivsRules.isBuilder(city, building)

    fun survey(city: Settlement, snapshot: CivSupplySnapshot, now: Long): CivProductionSurvey {
        val capture = (snapshot.inputKey as? CivSupplyObservation)?.capture
        val at = capture?.startedAtMillis
        val physical = city.inventory?.mode == CivStorageMode.PHYSICAL
        val failure = when {
            city.inventory == null || city.pendingDeletion != null || city.autonomy?.paused == true -> "city-unavailable"
            capture == null || snapshot.cityId != city.id || capture.cityId != city.id -> "observation-missing"
            !physical -> null // The durable virtual stores are complete without native chunk coverage.
            at == null || now - at !in 0..CivsForestrySurplus.MAXIMUM_AGE_MILLIS -> "capture-age-invalid"
            capture.plainStock == null -> "unique-stock-missing"
            else -> null
        }
        val partial = failure == null && physical && capture != null && (!capture.complete ||
            capture.stores.values.any { it.unavailable != 0 } ||
            city.buildings.any { it.phase == BuildingPhase.ACTIVE && it.control != null && it.id !in snapshot.stores })
        val known = if (partial) requireNotNull(capture).stores.filterValues { it.unavailable == 0 && it.containers > 0 }.keys else null
        return CivProductionSurvey(city, capture?.plainStock?.takeIf { physical }, at,
            if (known?.isEmpty() == true) "capture-incomplete" else failure, known)
    }

    fun allocate(survey: CivProductionSurvey, snapshot: CivSupplySnapshot, plannedCrafts: Map<UUID, CivCraftJob>,
        previous: CivProductionBoard, now: Long): CivProductionBoard {
        val city = survey.city
        if (survey.unavailable != null) return previous.copy(failure = survey.unavailable, reviewedAt = now,
            capturedAt = survey.capturedAt)
        val holdings = survey.plainStock.orEmpty().toMutableMap()
        fun add(stock: MutableMap<String, Long>, material: String, count: Long) {
            if (count > 0L) stock.merge(material, count, Math::addExact)
        }
        val inventory = requireNotNull(city.inventory)
        // These reserves are distinct from native holders until materialized; virtual stores are themselves durable.
        inventory.materials.forEach { (material, count) -> add(holdings, material, count) }
        inventory.buildingMaterials.orEmpty().values.forEach { store ->
            store.forEach { (material, count) -> add(holdings, material, count) }
        }
        inventory.constructionBuffers.orEmpty().values.forEach { buffer ->
            inventory.bufferStock(buffer.owner.original.id).forEach { (material, count) -> add(holdings, material, count) }
        }
        city.workers.forEach { worker ->
            CivsEquipment.slots(worker).filterNotNull().forEach { item ->
                if (item.data == null) add(holdings, item.material, item.amount.toLong())
            }
            // Pre-bag picked-up cargo owns its goods separately. New carried cargo is already in the bag.
            worker.cargo?.takeIf { !it.inventoryBacked && it.stockResource == null }?.let { add(holdings, it.material, it.amount) }
        }
        val required = linkedMapOf<String, Long>()
        val known = survey.knownStores
        fun observed(id: UUID) = known == null || id in known
        val order = city.construction
        val bill = (order?.materials ?: order?.payment?.awaiting)?.takeIf {
            known == null || order != null && CivsRules.constructionStores(city, order.buildingId).any { observed(it.id) }
        }
        bill?.let(CivsMaterials::unpaid)?.forEach { (material, count) -> add(required, material, count) }
        // Supply may reject a new logistics result after this approximate stock review. Its still-valid
        // accepted workshop batch also reserves that worker and its actual inputs until the next review.
        val jobs = snapshot.craftJobs.filter { (id, job) ->
            city.workers.firstOrNull { it.id == id }?.let { CivsCarpentry.valid(city, it, job) } == true
        } + plannedCrafts
        val selectedInputs = jobs.values.groupBy(CivCraftJob::buildingId)
        val requests = snapshot.requests.filterNot(CivSupplyRequest::construction).groupBy(CivSupplyRequest::buildingId)
        for (store in snapshot.stores.values) {
            if (!observed(store.id)) continue
            // Builder huts may each protect the same bill or protect all local stock until it is known.
            val local = if (store.type == BuildingType.BUILDER_HUT) mutableMapOf() else store.desiredStock.toMutableMap()
            requests[store.id].orEmpty().forEach { request ->
                if (request.needed > 0) local.merge(request.material,
                    Math.addExact(request.needed, store.stock[request.material] ?: 0L), Math::max)
            }
            val accepted = linkedMapOf<String, Long>()
            selectedInputs[store.id].orEmpty().forEach { job ->
                job.inputs.forEach { (material, count) -> add(accepted, material, count) }
            }
            accepted.forEach { (material, count) -> local.merge(material, count, Math::max) }
            local.forEach { (material, count) -> add(required, material, count) }
        }
        // The existing planner's warehouse buffers are stock targets; upstream production can supply them.
        snapshot.stockTargets.filterKeys(::observed).values.forEach { targets ->
            targets.forEach { (material, count) -> add(required, material, count) }
        }
        // The receipt proves this exact deficit independently of old stock or an unrelated missing holder.
        val replacement = city.settings?.garrisonReplacement.orEmpty().takeIf { CivsVillageProvisions.enabled(city) }.orEmpty()
        val buildings = city.buildings.associateBy(CivBuilding::id)
        val workers = city.workers.filter { worker ->
            val building = buildings[worker.assignment]
            worker.cargo == null && !CivsResidents.isChild(worker) && building != null &&
                applies(city, building) && CivsRules.productionAvailable(city, building) &&
                CivsProduction.material(building) in CivsProduction.options(city, building)
        }.associateBy(CivWorker::id)
        val allocated = linkedMapOf<UUID, CivProductionOrder>()
        val promised = mutableMapOf<String, Long>()
        // A previously accepted tree can span many worker cycles and must finish even after restart.
        city.forestryJobs.orEmpty().values.forEach { tree ->
            val logs = tree.states.map { CivsForestry.material(it) == tree.timber }
            val remainingLogs = tree.cells.subList(tree.next, tree.cells.size).count { logs[it ushr 16] }
            add(promised, tree.timber, remainingLogs.toLong())
        }
        val craftOutputs = mutableMapOf<String, Long>()
        jobs.values.forEach { job ->
            add(promised, job.material, job.amount)
            add(craftOutputs, job.material, job.amount)
        }
        // A forester wants saplings to replant. That prerequisite cannot itself authorize another tree.
        val timber = CivsForestrySurplus.decide(holdings, required.filterKeys { !it.endsWith("_SAPLING") },
            previous.timber?.paused == true, complete = known == null, craftOutputs = craftOutputs)
        // With a missing holder, one small local reserve suffices alongside genuine orders from seen consumers.
        val familyTarget = if (known == null) CivsForestrySurplus.STOP_AT else MAX_CYCLES
        var familyNeed = if (timber.paused) 0L else familyTarget - timber.familyStock
        familyNeed -= promised.filterKeys(CivsCarpentry::timberFamily).values.sum()
        fun reserve(worker: CivWorker, batch: CivProductionOrder) {
            val units = Math.multiplyExact(batch.untilCycle - worker.completedCycles, batch.unitsPerCycle)
            allocated[worker.id] = batch
            // A family lot may return another local species. Only exact commitments cover an exact bill.
            if (!batch.anyTimber) add(promised, batch.material, units)
            if (CivsCarpentry.timberFamily(batch.material)) familyNeed -= units
        }
        previous.orders.forEach { (id, batch) ->
            val worker = workers[id] ?: return@forEach
            val building = buildings.getValue(requireNotNull(worker.assignment))
            if (id !in jobs && batch.matches(worker) && batch.matches(building)) {
                // A new family shortage also admits local timber while the original species remains outstanding.
                val local = building.type == BuildingType.FORESTER && !batch.anyTimber && batch.material !in replacement &&
                    familyNeed > 0 && observed(building.id)
                reserve(worker, if (local) batch.copy(anyTimber = true) else batch)
            }
        }
        val rawReserve = if (known == null) 64L else MAX_CYCLES
        val rock = CivsProduction.options(BuildingType.QUARRY)
        val rockTarget = maxOf(rawReserve, rock.sumOf { required[it] ?: 0L })
        val rockHeld = rock.sumOf { holdings[it] ?: 0L }
        // A stable assignment keeps its unspent cycles; new lots share the remaining city deficit.
        for (worker in workers.values.sortedBy { it.completedCycles }) {
            if (worker.id in allocated || worker.id in jobs) continue
            val building = buildings.getValue(requireNotNull(worker.assignment))
            if (!observed(building.id)) continue
            val configured = requireNotNull(CivsProduction.material(building))
            val forest = building.type == BuildingType.FORESTER
            val amount = if (forest) CivsRules.deliveryAmount(building) else if (building.type.isWorldWork) 1L
                else CivsProduction.amount(building)
            val material: String
            val need: Long
            val anyTimber: Boolean
            if (forest) {
                if (building.id.toString() in city.forestryJobs.orEmpty()) continue
                val spent = replacement.keys.firstOrNull { CivsCarpentry.timberFamily(it) &&
                    replacement.getValue(it) > (promised[it] ?: 0L) }
                anyTimber = familyNeed > 0 && spent == null
                if (anyTimber) {
                    material = configured
                    need = familyNeed
                } else {
                    val species = CivsProduction.options(BuildingType.FORESTER).filter {
                        (timber.neededUnits[it] ?: 0L) > (promised[it] ?: 0L) ||
                            CivsCarpentry.timberSpecies(it) in timber.uncertainSpecies && (promised[it] ?: 0L) == 0L
                    }
                    material = spent ?: configured.takeIf { it in species } ?: species.firstOrNull() ?: continue
                    need = (timber.neededUnits[material] ?: 1L) - (promised[material] ?: 0L)
                }
            } else if (building.type == BuildingType.QUARRY) {
                // Native excavation has incidental drops. Its generator ordinarily yields cobblestone regardless of
                // the displayed selection. A backlog for exact stone can still use the smelter's real paid recipe.
                material = "COBBLESTONE"
                anyTimber = false
                val family = rockTarget - rockHeld - rock.sumOf { promised[it] ?: 0L }
                val usableCobble = (required[material] ?: 0L) - (holdings[material] ?: 0L) - (promised[material] ?: 0L)
                need = maxOf(family, usableCobble)
            } else {
                material = configured
                anyTimber = false
                val reserve = if (building.type.isWorldWork) rawReserve else amount
                need = maxOf(required[material] ?: 0L, reserve) - (holdings[material] ?: 0L) - (promised[material] ?: 0L)
            }
            val missing = maxOf(need, (replacement[material] ?: 0L) - (promised[material] ?: 0L))
            if (missing <= 0) continue
            val cycles = minOf(if (forest) 1L else MAX_CYCLES, (missing - 1) / amount + 1,
                Long.MAX_VALUE - worker.completedCycles)
            if (cycles == 0L) continue
            reserve(worker, CivProductionOrder(building.id, configured, material, worker.completedCycles,
                worker.completedCycles + cycles, amount, anyTimber))
        }
        return CivProductionBoard(allocated, timber, survey.capturedAt, now, partial = known != null)
    }
}
