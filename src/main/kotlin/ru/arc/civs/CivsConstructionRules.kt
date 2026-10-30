package ru.arc.civs

import java.util.UUID

/** Pure construction admission, exact payment and durable batch transitions. */
internal object CivsConstructionRules {
    fun reserveBuild(city: Settlement, actor: UUID, building: CivBuilding, order: CivConstruction): Settlement {
        CivsRules.authorized(city, actor)
        CivsRules.check(city.pendingControls.orEmpty().none { it.buildingId == building.id && it.retire != null }, "city-busy")
        val plan = building.copy(phase = BuildingPhase.CONSTRUCTING)
        CivsRules.check(order.buildingId == plan.id && order.targetLevel == plan.level &&
            (city.inventory == null || order.batches != null) &&
            CivsSnapshotValidation.validConstructionPrice(city, order, plan) &&
            order.workDone == 0 && order.phase == (if ((order.automaticPreparationBatches ?: 0) > 0)
                ConstructionPhase.READY_TO_PLACE else ConstructionPhase.CONSTRUCTING) && order.failure == null &&
            (order.payment == null || order.payment == CivConstructionPayment()) &&
            (order.plannedBuilding == null || order.plannedBuilding == plan), "invalid-construction")
        val reserved = order.copy(plannedBuilding = plan)
        city.construction?.let { current ->
            CivsRules.check(current.id == order.id, "construction-busy")
            val replay = current.copy(workDone = 0, phase = reserved.phase, failure = null,
                workCredit = if (city.autonomy != null) reserved.workCredit else current.workCredit,
                blocksPlaced = if (city.autonomy != null) reserved.blocksPlaced else current.blocksPlaced)
            CivsRules.check(replay == reserved, "invalid-construction")
            return city
        }
        val existing = city.building(plan.id)
        if (existing?.phase != BuildingPhase.SITE && plan.id !in city.constructionQueue.orEmpty())
            CivsResearch.required(plan.type, plan.level)?.let { CivsRules.check(CivsResearch.has(city, it), "research-required") }
        if (city.constructionQueue.orEmpty().isNotEmpty())
            CivsRules.check(city.constructionQueue!!.first() == plan.id, "construction-busy")
        CivsRules.check(plan.type == BuildingType.TOWN_HALL || plan.level <= city.level(), "town-hall-level-required")
        if (existing == null) {
            CivsRules.check(plan.type != BuildingType.TOWN_HALL && plan.level == 1, "invalid-level")
            CivsRules.requireBuildingSlot(city, plan.type, CivsRules.ESSENTIAL_BUILDINGS)
        } else if (existing.phase == BuildingPhase.SITE) {
            CivsRules.check(order.batches != null && existing.copy(phase = BuildingPhase.CONSTRUCTING) == plan, "invalid-building")
        } else {
            CivsRules.check(existing.phase == BuildingPhase.ACTIVE && existing.type == plan.type &&
                existing.entrance == plan.entrance && existing.rotation == plan.rotation, "invalid-building")
            CivsRules.check(plan.level == existing.level + 1 && plan.level <= plan.type.maxLevel ||
                CivsPlanBoardBlueprint.refit(existing, plan), "invalid-level")
        }
        CivsRules.check(!CivsSnapshotValidation.intersectsApproach(city, plan), "building-overlap")
        CivsRules.check(CivsSnapshotValidation.validBuilding(city, plan) && city.buildings.none { it.id != plan.id && it.bounds.overlaps(plan.bounds) }, "invalid-building")
        CivsRules.check(workRouteWithinReach(city, plan.entrance), "work-route-too-long")
        CivsRules.check(city.stock.covers(reserved.cost), "not-enough-resources")
        val remaining = city.stock - reserved.cost
        // Both basic producers remain affordable until their own construction has been reserved.
        val bootstrap = listOf(BuildingType.FORESTER, BuildingType.QUARRY)
            .filter { type -> plan.type != type && city.buildings.none { it.type == type } }.map { CivsRules.cost(it, 1) }
        if (order.batches == null) CivsRules.check(Resource.entries.all { resource -> remaining[resource] >= bootstrap.sumOf { it[resource] } }, "resources.bootstrap-reserve")
        return CivsRules.changed(city, city.copy(stock = remaining,
            buildings = if (existing == null) city.buildings + plan else if (existing.phase == BuildingPhase.SITE)
                city.buildings.map { if (it.id == plan.id) plan else it } else city.buildings,
            construction = reserved, constructionQueue = city.constructionQueue?.filterNot { it == plan.id }))
    }

    /** Same nearest available depot and three-dimensional entrance distance as citizenPlan. */
    internal fun workRouteWithinReach(city: Settlement, entrance: CivPosition): Boolean {
        val nearestSquared = city.buildings.filter(CivsRules::isDepot).minOfOrNull {
            val dx = it.entrance.x.toDouble() - entrance.x
            val dy = it.entrance.y.toDouble() - entrance.y
            val dz = it.entrance.z.toDouble() - entrance.z
            dx * dx + dy * dy + dz * dz
        }
        return nearestSquared != null && nearestSquared <= CivsRules.MAX_WORK_ROUTE_DISTANCE.toDouble() * CivsRules.MAX_WORK_ROUTE_DISTANCE
    }

    fun beginPlacement(city: Settlement, actor: UUID, orderId: UUID): Settlement {
        CivsRules.authorized(city, actor)
        val order = CivsRules.order(city, orderId)
        if (order.phase == ConstructionPhase.PLACING) return city
        CivsRules.check(order.phase == ConstructionPhase.READY_TO_PLACE, "construction-not-ready")
        CivsRules.check(order.payment == null || order.payment.reservedBatch == order.workDone, "materials-required")
        return CivsRules.changed(city, city.copy(construction = order.copy(phase = ConstructionPhase.PLACING)))
    }

    /** Called in the same durable inventory receipt as this batch's exact item debit. */
    fun payBatch(city: Settlement, actor: UUID, orderId: UUID, batch: Int, materials: Map<String, Long>): Settlement {
        CivsRules.authorized(city, actor)
        val order = CivsRules.order(city, orderId)
        val payment = order.payment ?: CivsRules.reject("invalid-construction")
        CivsRules.check(batch == order.workDone && payment.reservedBatch == null &&
            order.phase in setOf(ConstructionPhase.READY_TO_PLACE, ConstructionPhase.WAITING_MATERIALS), "stale-construction")
        CivsRules.check(CivsSnapshotValidation.validMaterials(materials) && materials.all { (key, value) -> order.materials.orEmpty().getOrDefault(key, 0) >= value }, "invalid-construction")
        CivsRules.check(payment.awaiting == null || CivsMaterials.unpaid(payment.awaiting) == materials, "invalid-construction")
        CivsRules.check(CivsRules.constructionStorageInputs(city, order.buildingId, materials).isEmpty(), "materials-missing")
        val remaining = CivsMaterials.unpaid(order.materials.orEmpty()).mapValues { (key, value) -> value - materials.getOrDefault(key, 0) }.filterValues { it > 0 }
        var storageInputs = materials
        val builders = CivsRules.constructionWorkers(city, order.buildingId).map(CivWorker::id).toSet()
        val workers = city.workers.map { worker ->
            if (worker.id !in builders) worker else CivsEquipment.consumeInputs(worker, storageInputs).also {
                storageInputs = CivsEquipment.storageInputs(worker, storageInputs)
            }
        }
        return CivsRules.changed(city, city.copy(construction = order.copy(phase = ConstructionPhase.PLACING,
            materials = remaining, payment = CivConstructionPayment(batch)), workers = workers))
    }

    fun awaitMaterials(city: Settlement, actor: UUID, orderId: UUID, batch: Int, materials: Map<String, Long>): Settlement {
        CivsRules.authorized(city, actor)
        val order = CivsRules.order(city, orderId)
        CivsRules.check(order.payment?.reservedBatch == null && order.payment != null && order.workDone == batch &&
            order.phase in setOf(ConstructionPhase.READY_TO_PLACE, ConstructionPhase.WAITING_MATERIALS) &&
            materials.isNotEmpty() && CivsSnapshotValidation.validMaterials(materials) &&
            materials.all { (key, value) -> order.materials.orEmpty().getOrDefault(key, 0) >= value }, "invalid-construction")
        if (order.phase == ConstructionPhase.WAITING_MATERIALS && order.payment?.awaiting == materials) return city
        return CivsRules.changed(city, city.copy(construction = order.copy(phase = ConstructionPhase.WAITING_MATERIALS,
            payment = CivConstructionPayment(awaiting = materials))))
    }

    fun pauseConstruction(city: Settlement, actor: UUID, orderId: UUID, failure: String): Settlement {
        CivsRules.authorized(city, actor)
        CivsRules.check(CivsRules.validText(failure, 512), "invalid-failure")
        val order = CivsRules.order(city, orderId)
        return CivsRules.changed(city, city.copy(construction = order.copy(phase = ConstructionPhase.PAUSED, failure = failure)))
    }

    fun retryConstruction(city: Settlement, actor: UUID, orderId: UUID): Settlement {
        CivsRules.authorized(city, actor)
        val order = CivsRules.order(city, orderId)
        if (order.phase != ConstructionPhase.PAUSED) return city
        val phase = if (order.workDone < (order.automaticPreparationBatches ?: 0) || order.workDone == order.workRequired ||
            order.payment?.reservedBatch == order.workDone || (order.workCredit ?: 0) > 0)
            ConstructionPhase.READY_TO_PLACE else ConstructionPhase.CONSTRUCTING
        return CivsRules.changed(city, city.copy(construction = order.copy(phase = phase, failure = null,
            payment = order.payment?.copy(awaiting = null))))
    }

    fun completeConstruction(city: Settlement, actor: UUID, orderId: UUID): Settlement {
        CivsRules.authorized(city, actor)
        if (city.construction == null) return city
        val order = CivsRules.order(city, orderId)
        CivsRules.check(order.phase == ConstructionPhase.PLACING, "construction-not-placing")
        CivsRules.check(order.batches == null, "invalid-construction")
        return finishConstruction(city, order)
    }

    fun completeBatch(city: Settlement, actor: UUID, orderId: UUID): Settlement {
        CivsRules.authorized(city, actor)
        if (city.construction == null) return city
        val order = CivsRules.order(city, orderId)
        CivsRules.check(order.batches != null && order.phase == ConstructionPhase.PLACING, "construction-not-placing")
        val completed = order.workDone + 1
        CivsRules.check(completed != order.workRequired || order.payment == null || order.materials.orEmpty().isEmpty(), "invalid-construction")
        return if (completed == order.workRequired) finishConstruction(city, order)
        else CivsRules.changed(city, city.copy(construction = order.copy(workDone = completed,
            workCredit = order.workCredit?.let { (it - 1).coerceAtLeast(0) },
            phase = if (completed < (order.automaticPreparationBatches ?: 0) || (order.workCredit ?: 0) > 1)
                ConstructionPhase.READY_TO_PLACE else ConstructionPhase.CONSTRUCTING,
            payment = order.payment?.let { CivConstructionPayment() })))
    }

    private fun finishConstruction(city: Settlement, order: CivConstruction): Settlement {
        val finished = order.plannedBuilding!!.copy(phase = BuildingPhase.ACTIVE)
        val previous = city.building(finished.id)!!
        val mineUpgrade = finished.type == BuildingType.QUARRY && finished.level > previous.level
        val mineState = if (mineUpgrade) CivsMineProgress.upgraded(previous, finished,
            city.minePlans?.get(finished.id.toString()), city.mineProgress?.get(finished.id.toString())) else null
        val growth = if (finished.type == BuildingType.TOWN_HALL && finished.level > previous.level) CivsRules.MAX_TERRITORY_GROWTH / 2 else 0
        CivsRules.check(city.radius <= Int.MAX_VALUE - growth, "arithmetic-overflow")
        return CivsRules.changed(city, city.copy(radius = city.radius + growth,
            buildings = city.buildings.map { if (it.id == finished.id) finished else it }, construction = null,
            mineProgress = if (mineUpgrade)
                city.mineProgress.orEmpty() - finished.id.toString() else city.mineProgress,
            minePlans = if (mineUpgrade) (city.minePlans.orEmpty() - finished.id.toString()).let {
                if (mineState == null) it else it + (finished.id.toString() to mineState)
            } else city.minePlans))
    }

}
