package ru.arc.civs

import java.util.UUID

internal fun citizenSummonEligible(city: Settlement, worker: CivWorker, buildingId: UUID): Boolean {
    if (CivsResidents.isChild(worker)) return false
    val assignment = worker.assignment?.let(city::building) ?: return false
    return assignment.id == buildingId || city.construction?.buildingId == buildingId &&
        CivsRules.isBuilder(city, assignment) && CivsRules.builderCanWork(city, assignment)
}

internal fun citizenPlan(city: Settlement, worker: CivWorker, courier: CivCourierPlan? = null): CitizenPlan? {
    if (CivsResidents.isChild(worker)) {
        val home = citizenHomeBuilding(city, worker) ?: return null
        return CitizenPlan(CitizenToken(null, 0, 0, null), citizenIdleAnchor(city, home), null, null, 0, "playing")
    }
    if (courier != null) {
        val destination = city.building(courier.destinationBuildingId)?.control ?: return null
        val source = courier.cargo.sourceEntrance.takeIf { courier.cargo.sourceWorkerId != null }
            ?: city.building(courier.cargo.sourceBuildingId)?.control
            ?: courier.cargo.sourceEntrance.takeIf { courier.carrying } ?: return null
        return CitizenPlan(CitizenToken(courier.cargo.warehouseId, worker.completedCycles, 0, null, courier.cargo.id),
            destination, source, BuildingType.WAREHOUSE, 0, null, courier = courier)
    }
    if (worker.cargo != null) return null
    val assigned = worker.assignment?.let(city::building)
    val autonomous = city.autonomy != null && city.inventory == null
    val collecting = autonomous && assigned?.type?.let(CivsRules::resource) != null && CivsAutonomousLogistics.staffed(city)
    val workType = assigned?.let { CivsRules.effectiveWorkType(city, it) }
    val research = city.research?.project.takeIf { workType == BuildingType.UNIVERSITY && CivsResearch.university(city)?.id == assigned?.id }
    val industry = assigned?.takeIf { autonomous }?.let { CivsIndustry.autonomousWork(city, it) }
    val builder = workType == BuildingType.BUILDER_HUT
    val order = city.construction.takeIf { builder }
    val workBuilding = order?.buildingId?.let(city::building) ?: order?.plannedBuilding
    val workplace = if (builder) workBuilding?.control ?: order?.plannedBuilding?.entrance ?: workBuilding?.entrance
        else if (workType == BuildingType.TAVERN) assigned?.control ?: assigned?.entrance
        else if (autonomous || workType == BuildingType.GUARD_TOWER) assigned?.entrance else assigned?.control
    val depot = city.buildings.filter(CivsRules::isDepot)
        .minByOrNull { (if (autonomous) it.entrance else it.control ?: it.entrance).point()
            .distanceSquared((workplace ?: city.center).point()) }
    if (autonomous && assigned != null && !CivsAutonomousLogistics.staffed(city)) {
        worker.pendingStock?.takeUnless { it == Stock() || !CivsAutonomousLogistics.pendingRoom(city, it) }?.let { pending ->
            val destination = CivsAutonomousStorage.pendingDestination(city, pending, workplace ?: city.center) ?: return null
            return CitizenPlan(CitizenToken(assigned.id, worker.completedCycles, worker.nextDeliveryAt, null),
                destination.entrance, assigned.entrance, workType, 0,
                if (city.autonomy?.paused == true) "autonomy-paused" else null, pendingStockDelivery = pending)
        }
    }
    val storage = when {
        collecting -> requireNotNull(assigned).entrance
        autonomous -> depot?.entrance ?: return null
        // Physical builders take supplies from their assigned hut or their own bag.
        // The construction target and a nearby town hall are never their depot.
        city.inventory != null && assigned != null -> assigned.control ?: assigned.entrance
        workType in setOf(BuildingType.TOWN_HALL, BuildingType.WAREHOUSE) && assigned != null -> assigned.control ?: assigned.entrance
        else -> depot?.control ?: depot?.entrance ?: return null
    }
    val reason = when {
        autonomous && city.autonomy?.paused == true -> "autonomy-paused"
        worker.assignment == null -> "unassigned"
        assigned == null -> "building-unavailable"
        !builder && !CivsRules.workplaceAvailable(city, assigned) -> "building-unavailable"
        autonomous && CivsAutonomy.waitingReassignment(city, worker) -> "waiting-reassignment"
        workType == BuildingType.UNIVERSITY && research == null -> "no-research"
        workType == BuildingType.UNIVERSITY && research?.paused == true -> "research-paused"
        workType == BuildingType.UNIVERSITY && research?.funded != true -> "research-unfunded"
        autonomous && workType in CivsIndustry.processorTypes && industry == null -> "waiting-orders"
        workType == BuildingType.WAREHOUSE && assigned.phase == BuildingPhase.ACTIVE -> "waiting-orders"
        builder && order == null -> "no-construction"
        builder && !CivsRules.builderCanWork(city, assigned) -> "building-unavailable"
        builder && order?.phase == ConstructionPhase.WAITING_MATERIALS -> "waiting-materials"
        builder && order?.phase == ConstructionPhase.PAUSED -> "construction-paused"
        builder && order?.phase in setOf(ConstructionPhase.READY_TO_PLACE, ConstructionPhase.PLACING) -> "placing"
        builder && !autonomous && city.inventory == null && depot?.control == null && depot?.phase == BuildingPhase.ACTIVE -> "storage-unavailable"
        (workType == BuildingType.GUARD_TOWER || workType == BuildingType.TAVERN) && assigned.phase == BuildingPhase.ACTIVE -> null
        !builder && !CivsRules.productionAvailable(city, assigned) -> "building-unavailable"
        workplace == null -> "building-unavailable"
        else -> null
    }
    val recipeKey = industry?.recipe?.id ?: assigned?.takeIf { !autonomous &&
        (it.type in setOf(BuildingType.SMELTERY, BuildingType.CRUSHER) ||
            it.type == BuildingType.BLACKSMITH && CivsProduction.material(it) in setOf("BUCKET", "SHEARS")) }?.let(CivsProduction::material)
    return CitizenPlan(CitizenToken(worker.assignment, worker.completedCycles, worker.nextDeliveryAt, order?.id,
        researchProjectId = research?.id, industryRecipe = recipeKey),
        storage, workplace, workType,
        if (workType == BuildingType.GUARD_TOWER || workType == BuildingType.TAVERN) Long.MAX_VALUE
        else if (builder && (order?.payment != null || city.autonomy != null)) CivsRules.BUILDER_SESSION_INTERVAL
        else assigned?.let { CivsRules.deliveryInterval(city, it) } ?: 0,
        reason, builder || city.inventory != null || collecting,
        autonomousWork = assigned?.takeIf { autonomous && reason == null && it.type != BuildingType.FORESTER &&
            (CivsRules.resource(it.type) != null || industry != null || it.type == BuildingType.UNIVERSITY) }, industryWork = industry)
}

internal fun citizenHasCapacity(city: Settlement, plan: CitizenPlan): Boolean {
    plan.pendingStockDelivery?.let { return CivsAutonomousLogistics.pendingRoom(city, it) }
    if (plan.worldWork?.autonomousVisual == true) return true
    if (CivsAutonomy.legacyStock(city)) plan.industryWork?.let { return CivsIndustry.room(city, it) }
    if (CivsAutonomy.legacyStock(city) && plan.type == BuildingType.FORESTER) {
        val work = plan.worldWork ?: return true
        return try { CivsForestry.autoStock(city, work); true } catch (_: CivsRejection) { false }
    }
    if (city.inventory != null && plan.type in CivsWorksites.TYPES && plan.worldWork?.outputs?.isNotEmpty() == true) {
        val worker = city.workers.firstOrNull { it.id == plan.worldWork.workerId } ?: return false
        return try {
            CivsEquipment.collectSupplies(CivsEquipment.consumeInputs(worker, plan.worldWork.inputs), plan.worldWork.outputs)
            true
        } catch (failure: CivsRejection) {
            if (failure.reason == "resident-inventory-full") false else throw failure
        }
    }
    // Item storage checks actual container space at its durable delivery boundary.
    if (city.inventory != null) return true
    val building = plan.token.assignmentId?.let(city::building) ?: return true
    val resource = CivsRules.resource(building.type) ?: return true
    return (if (city.autonomy != null) CivsAutonomousLogistics.room(city, resource,
        city.workers.firstOrNull { it.assignment == building.id }?.id)
        else CivsRules.capacity(city) - city.stock[resource]) >= CivsRules.deliveryAmount(building)
}

internal fun citizenHomeBuilding(city: Settlement, worker: CivWorker): CivBuilding? {
    worker.homeId?.let { id -> city.building(id)?.takeIf { home ->
        home.type == BuildingType.TOWN_HALL || home.type.isResidence && home.phase == BuildingPhase.ACTIVE
    }?.let { return it } }
    val homes = city.buildings.filter { it.type.isResidence && it.phase == BuildingPhase.ACTIVE }
        .flatMap { home -> List(CivsPopulation.houseBeds(home)) { home } }
    return homes.getOrNull(city.workers.indexOfFirst { it.id == worker.id })
        ?: city.buildings.firstOrNull { it.type == BuildingType.TOWN_HALL }
}
