package ru.arc.civs

import java.util.UUID

/** Pure transitions. The caller persists the returned revision before publishing it or placing blocks. */
object CivsRules {
    const val MAX_BUILDINGS = 16
    const val MAX_POPULATION = 12
    const val MAX_WORK_ROUTE_DISTANCE = 88
    const val RECRUIT_FOOD = 12L
    const val PRODUCTION_INTERVAL = 60_000L
    const val BUILDER_INTERVAL = 10_000L
    const val FIRST_IMMIGRATION_DELAY = 30_000L
    const val IMMIGRATION_INTERVAL = 60_000L
    const val MAX_TERRITORY_GROWTH = 64
    val STARTING_STOCK = Stock(96, 96, 24)

    /** Confirmed deletion discards virtual records; native blocks and container contents stay in the world. */
    fun requireDeletion(city: Settlement, actor: UUID, expectedCityId: UUID, confirmationName: String) {
        ownerOnly(city, actor)
        check(city.id == expectedCityId, "delete-city-changed")
        check(confirmationName.trim() == city.name, "delete-name-mismatch")
    }
    private val ESSENTIAL_BUILDINGS = setOf(BuildingType.WAREHOUSE, BuildingType.FARM, BuildingType.HOUSE, BuildingType.FORESTER, BuildingType.QUARRY)

    fun cost(type: BuildingType, targetLevel: Int): Stock {
        check(targetLevel in 1..3, "invalid-level")
        if (type == BuildingType.TOWN_HALL) return when (targetLevel) {
            1 -> Stock()
            2 -> Stock(128, 96, 24)
            else -> Stock(384, 288, 72)
        }
        val base = when (type) {
            BuildingType.BUILDER_HUT -> Stock() // New construction reserves exact materials, never an abstract price.
            BuildingType.WAREHOUSE -> Stock(32, 16)
            BuildingType.HOUSE -> Stock(24, 12)
            BuildingType.FORESTER -> Stock(20, 12)
            BuildingType.QUARRY -> Stock(20, 20)
            BuildingType.FARM -> Stock(24, 8)
            BuildingType.TOWN_HALL -> error("handled above")
        }
        val multiplier = targetLevel.toLong() * targetLevel
        return Stock(base.wood * multiplier, base.stone * multiplier, base.food * multiplier)
    }

    fun capacity(city: Settlement): Long = 128L + city.buildings.sumOf {
        if (it.phase == BuildingPhase.ACTIVE && it.type == BuildingType.WAREHOUSE) {
            when (it.level) { 1 -> 256L; 2 -> 512L; 3 -> 1024L; else -> 0L }
        } else 0L
    }

    fun beds(city: Settlement): Int = 4 + city.buildings.sumOf {
        if (it.phase == BuildingPhase.ACTIVE && it.type == BuildingType.HOUSE) it.level * 2 else 0
    }

    fun populationLimit(city: Settlement): Int = city.level() * 4

    fun slots(building: CivBuilding): Int = if (building.type == BuildingType.BUILDER_HUT) 1
    else if (building.phase != BuildingPhase.ACTIVE) 0 else when (building.type) {
        BuildingType.FORESTER, BuildingType.QUARRY, BuildingType.FARM -> building.level
        else -> 0
    }

    /** Old full cities have no spare plot for the newly introduced builder hut. New sites reserve that plot. */
    fun legacyHallBuilder(city: Settlement): Boolean = city.inventory != null && city.buildings.size == MAX_BUILDINGS &&
        city.buildings.none { it.type == BuildingType.BUILDER_HUT } &&
        city.buildings.any { it.type == BuildingType.TOWN_HALL && it.phase == BuildingPhase.ACTIVE }

    fun slots(city: Settlement, building: CivBuilding): Int =
        if (building.type == BuildingType.TOWN_HALL && legacyHallBuilder(city)) 1 else slots(building)

    fun isBuilder(building: CivBuilding): Boolean = building.type == BuildingType.BUILDER_HUT || building.type == BuildingType.TOWN_HALL

    fun isDepot(building: CivBuilding): Boolean = building.type == BuildingType.TOWN_HALL ||
        building.type == BuildingType.WAREHOUSE && building.phase == BuildingPhase.ACTIVE

    fun builderCanWork(city: Settlement, building: CivBuilding): Boolean = when (building.type) {
        BuildingType.TOWN_HALL -> building.phase == BuildingPhase.ACTIVE && (city.construction?.batches == null || legacyHallBuilder(city))
        BuildingType.BUILDER_HUT -> building.phase == BuildingPhase.ACTIVE || city.construction?.buildingId == building.id
        else -> false
    }

    fun productionAvailable(city: Settlement, building: CivBuilding): Boolean =
        building.phase == BuildingPhase.ACTIVE && resource(building.type) != null && city.construction?.buildingId != building.id

    fun resource(type: BuildingType): Resource? = when (type) {
        BuildingType.FORESTER -> Resource.WOOD
        BuildingType.QUARRY -> Resource.STONE
        BuildingType.FARM -> Resource.FOOD
        else -> null
    }

    fun profession(type: BuildingType): String = when (type) {
        BuildingType.TOWN_HALL, BuildingType.BUILDER_HUT -> "builder"
        BuildingType.FORESTER -> "forester"
        BuildingType.QUARRY -> "miner"
        BuildingType.FARM -> "farmer"
        else -> "unassigned"
    }

    fun deliveryAmount(building: CivBuilding): Long = when (resource(building.type)) {
        Resource.WOOD -> 4L * building.level
        Resource.STONE, Resource.FOOD -> 3L * building.level
        null -> 0L
    }

    fun deliveryInterval(type: BuildingType): Long = if (type == BuildingType.TOWN_HALL || type == BuildingType.BUILDER_HUT) BUILDER_INTERVAL else PRODUCTION_INTERVAL

    fun workRequired(type: BuildingType, targetLevel: Int): Int {
        check(targetLevel in 1..3, "invalid-level")
        return if (type == BuildingType.TOWN_HALL && targetLevel == 1) 0 else 3 * targetLevel
    }

    fun found(
        id: UUID, owner: UUID, name: String, worldId: UUID, center: CivPosition,
        radius: Int, style: String, townHall: CivBuilding, now: Long,
    ): Settlement {
        check(now >= 0, "invalid-time")
        check(townHall.type == BuildingType.TOWN_HALL && townHall.level == 1, "invalid-building")
        val hall = townHall.copy(phase = BuildingPhase.SITE)
        return validate(Settlement(id, owner, name.trim(), worldId, center, radius, style,
            Stock(), listOf(hall), emptyList(), createdAt = now, inventory = CivInventory.starting(STARTING_STOCK),
            immigration = CivImmigration(4, add(now, FIRST_IMMIGRATION_DELAY))))
    }

    fun immigrate(city: Settlement, actor: UUID, workerId: UUID, name: String, now: Long): Settlement {
        authorized(city, actor)
        check(now >= 0, "invalid-time")
        val immigration = city.immigration ?: return city
        if (immigration.remaining == 0) return city
        check(now >= immigration.nextArrivalAt, "immigration-not-ready")
        check(city.workers.none { it.id == workerId }, "duplicate-worker")
        check(validText(name.trim(), 32), "invalid-name")
        check(city.workers.size < populationLimit(city), "population-full")
        check(city.workers.size < beds(city), "no-beds")
        return changed(city, city.copy(workers = city.workers + CivWorker(workerId, name.trim(), null),
            immigration = CivImmigration(immigration.remaining - 1, add(now, IMMIGRATION_INTERVAL))))
    }

    fun placeSite(city: Settlement, actor: UUID, building: CivBuilding): Settlement {
        authorized(city, actor)
        val site = building.copy(phase = BuildingPhase.SITE)
        city.building(site.id)?.let { check(it == site, "invalid-building"); return city }
        check(site.type != BuildingType.TOWN_HALL && site.level == 1, "invalid-level")
        check(city.buildings.size < MAX_BUILDINGS, "building-limit")
        val essential = ESSENTIAL_BUILDINGS + BuildingType.BUILDER_HUT
        val missing = essential.count { type -> type != site.type && city.buildings.none { it.type == type } }
        check(MAX_BUILDINGS - city.buildings.size - 1 >= missing, "resources.bootstrap-slots")
        check(!intersectsApproach(city, site), "building-overlap")
        check(validBuilding(city, site) && city.buildings.none { it.bounds.overlaps(site.bounds) } &&
            city.construction?.plannedBuilding?.bounds?.overlaps(site.bounds) != true, "invalid-building")
        check(workRouteWithinReach(city, site.entrance), "work-route-too-long")
        return changed(city, city.copy(buildings = city.buildings + site))
    }

    fun startSite(city: Settlement, actor: UUID, building: CivBuilding, order: CivConstruction): Settlement {
        authorized(city, actor)
        val existing = city.building(building.id) ?: reject("building-not-found")
        check(existing.phase == BuildingPhase.SITE || city.construction?.id == order.id, "invalid-building")
        return reserveBuild(city, actor, building, order)
    }

    fun recruit(city: Settlement, actor: UUID, workerId: UUID, name: String): Settlement =
        addRecruit(city, actor, workerId, name, funded = false)

    /** The service has durably reserved physical/virtual BREAD before invoking this transition. */
    fun recruitFunded(city: Settlement, actor: UUID, workerId: UUID, name: String): Settlement =
        addRecruit(city, actor, workerId, name, funded = true)

    private fun addRecruit(city: Settlement, actor: UUID, workerId: UUID, name: String, funded: Boolean): Settlement {
        authorized(city, actor)
        check(city.buildings.single { it.type == BuildingType.TOWN_HALL }.phase == BuildingPhase.ACTIVE, "town-hall-not-ready")
        check(city.workers.none { it.id == workerId }, "duplicate-worker")
        check(validText(name.trim(), 32), "invalid-name")
        check(city.workers.size < populationLimit(city), "population-full")
        check(city.workers.size < beds(city), "no-beds")
        check(funded == (city.inventory != null), "materials-required")
        if (!funded) check(city.stock.food >= RECRUIT_FOOD, "not-enough-resources")
        return changed(city, city.copy(stock = if (funded) city.stock else city.stock.copy(food = city.stock.food - RECRUIT_FOOD),
            workers = city.workers + CivWorker(workerId, name.trim(), null)))
    }

    fun assign(city: Settlement, actor: UUID, workerId: UUID, buildingId: UUID?, now: Long): Settlement {
        authorized(city, actor)
        check(now >= 0, "invalid-time")
        val worker = city.workers.find { it.id == workerId } ?: reject("worker-not-found")
        if (worker.assignment == buildingId) return city
        val building = buildingId?.let { city.building(it) ?: reject("building-not-found") }
        if (building != null) {
            check(slots(city, building) > 0, "invalid-assignment")
            check(city.workers.count { it.assignment == buildingId } < slots(city, building), "workplace-full")
        }
        // A monotonic schedule also invalidates an old trip after unassign/reassign at the same timestamp.
        val due = maxOf(add(worker.nextDeliveryAt, 1), add(now, building?.let { deliveryInterval(it.type) } ?: 0L))
        return changed(city, city.copy(workers = city.workers.map {
            if (it.id == workerId) it.copy(assignment = buildingId, nextDeliveryAt = due) else it
        }))
    }

    fun addMember(city: Settlement, actor: UUID, member: UUID): Settlement {
        ownerOnly(city, actor)
        if (member == city.owner || member in city.members) return city
        return changed(city, city.copy(members = city.members + member))
    }

    fun removeMember(city: Settlement, actor: UUID, member: UUID): Settlement {
        ownerOnly(city, actor)
        check(member != city.owner, "owner-cannot-be-removed")
        return changed(city, city.copy(members = city.members - member))
    }

    fun reserveBuild(city: Settlement, actor: UUID, building: CivBuilding, order: CivConstruction): Settlement {
        authorized(city, actor)
        val plan = building.copy(phase = BuildingPhase.CONSTRUCTING)
        check(order.buildingId == plan.id && order.targetLevel == plan.level &&
            (city.inventory == null || order.batches != null) &&
            validConstructionPrice(order, plan) &&
            order.workDone == 0 && order.phase == ConstructionPhase.CONSTRUCTING && order.failure == null &&
            (order.payment == null || order.payment == CivConstructionPayment()) &&
            (order.plannedBuilding == null || order.plannedBuilding == plan), "invalid-construction")
        val reserved = order.copy(plannedBuilding = plan)
        city.construction?.let { current ->
            check(current.id == order.id, "construction-busy")
            check(current.copy(workDone = 0, phase = ConstructionPhase.CONSTRUCTING, failure = null) == reserved, "invalid-construction")
            return city
        }
        val existing = city.building(plan.id)
        check(plan.type == BuildingType.TOWN_HALL || plan.level <= city.level(), "town-hall-level-required")
        if (existing == null) {
            check(plan.type != BuildingType.TOWN_HALL && plan.level == 1, "invalid-level")
            check(city.buildings.size < MAX_BUILDINGS, "building-limit")
            val missing = ESSENTIAL_BUILDINGS.count { type -> type != plan.type && city.buildings.none { it.type == type } }
            check(MAX_BUILDINGS - city.buildings.size - 1 >= missing, "resources.bootstrap-slots")
        } else if (existing.phase == BuildingPhase.SITE) {
            check(order.batches != null && existing.copy(phase = BuildingPhase.CONSTRUCTING) == plan, "invalid-building")
        } else {
            check(existing.phase == BuildingPhase.ACTIVE && existing.type == plan.type &&
                existing.entrance == plan.entrance && existing.rotation == plan.rotation, "invalid-building")
            check(plan.level == existing.level + 1 && plan.level <= 3, "invalid-level")
        }
        check(!intersectsApproach(city, plan), "building-overlap")
        check(validBuilding(city, plan) && city.buildings.none { it.id != plan.id && it.bounds.overlaps(plan.bounds) }, "invalid-building")
        check(workRouteWithinReach(city, plan.entrance), "work-route-too-long")
        check(city.stock.covers(reserved.cost), "not-enough-resources")
        val remaining = city.stock - reserved.cost
        // Both basic producers remain affordable until their own construction has been reserved.
        val bootstrap = listOf(BuildingType.FORESTER, BuildingType.QUARRY)
            .filter { type -> plan.type != type && city.buildings.none { it.type == type } }.map { cost(it, 1) }
        if (order.batches == null) check(Resource.entries.all { resource -> remaining[resource] >= bootstrap.sumOf { it[resource] } }, "resources.bootstrap-reserve")
        return changed(city, city.copy(stock = remaining,
            buildings = if (existing == null) city.buildings + plan else if (existing.phase == BuildingPhase.SITE)
                city.buildings.map { if (it.id == plan.id) plan else it } else city.buildings,
            construction = reserved))
    }

    /** Same nearest available depot and three-dimensional entrance distance as citizenPlan. */
    internal fun workRouteWithinReach(city: Settlement, entrance: CivPosition): Boolean {
        val nearestSquared = city.buildings.filter(::isDepot).minOfOrNull {
            val dx = it.entrance.x.toDouble() - entrance.x
            val dy = it.entrance.y.toDouble() - entrance.y
            val dz = it.entrance.z.toDouble() - entrance.z
            dx * dx + dy * dy + dz * dz
        }
        return nearestSquared != null && nearestSquared <= MAX_WORK_ROUTE_DISTANCE.toDouble() * MAX_WORK_ROUTE_DISTANCE
    }

    fun beginPlacement(city: Settlement, actor: UUID, orderId: UUID): Settlement {
        authorized(city, actor)
        val order = order(city, orderId)
        if (order.phase == ConstructionPhase.PLACING) return city
        check(order.phase == ConstructionPhase.READY_TO_PLACE, "construction-not-ready")
        check(order.payment == null || order.payment.reservedBatch == order.workDone, "materials-required")
        return changed(city, city.copy(construction = order.copy(phase = ConstructionPhase.PLACING)))
    }

    /** Called in the same durable inventory receipt as this batch's exact item debit. */
    fun payBatch(city: Settlement, actor: UUID, orderId: UUID, batch: Int, materials: Map<String, Long>): Settlement {
        authorized(city, actor)
        val order = order(city, orderId)
        val payment = order.payment ?: reject("invalid-construction")
        check(batch == order.workDone && payment.reservedBatch == null &&
            order.phase in setOf(ConstructionPhase.READY_TO_PLACE, ConstructionPhase.WAITING_MATERIALS), "stale-construction")
        check(validMaterials(materials) && materials.all { (key, value) -> order.materials.orEmpty().getOrDefault(key, 0) >= value }, "invalid-construction")
        check(payment.awaiting == null || payment.awaiting == materials, "invalid-construction")
        val remaining = order.materials.orEmpty().mapValues { (key, value) -> value - materials.getOrDefault(key, 0) }.filterValues { it > 0 }
        return changed(city, city.copy(construction = order.copy(phase = ConstructionPhase.PLACING,
            materials = remaining, payment = CivConstructionPayment(batch))))
    }

    fun awaitMaterials(city: Settlement, actor: UUID, orderId: UUID, batch: Int, materials: Map<String, Long>): Settlement {
        authorized(city, actor)
        val order = order(city, orderId)
        check(order.payment?.reservedBatch == null && order.payment != null && order.workDone == batch &&
            order.phase == ConstructionPhase.READY_TO_PLACE && materials.isNotEmpty() && validMaterials(materials) &&
            materials.all { (key, value) -> order.materials.orEmpty().getOrDefault(key, 0) >= value }, "invalid-construction")
        return changed(city, city.copy(construction = order.copy(phase = ConstructionPhase.WAITING_MATERIALS,
            payment = CivConstructionPayment(awaiting = materials))))
    }

    fun pauseConstruction(city: Settlement, actor: UUID, orderId: UUID, failure: String): Settlement {
        authorized(city, actor)
        check(validText(failure, 512), "invalid-failure")
        val order = order(city, orderId)
        return changed(city, city.copy(construction = order.copy(phase = ConstructionPhase.PAUSED, failure = failure)))
    }

    fun retryConstruction(city: Settlement, actor: UUID, orderId: UUID): Settlement {
        authorized(city, actor)
        val order = order(city, orderId)
        if (order.phase != ConstructionPhase.PAUSED) return city
        val phase = if (order.workDone == order.workRequired || order.payment?.reservedBatch == order.workDone)
            ConstructionPhase.READY_TO_PLACE else ConstructionPhase.CONSTRUCTING
        return changed(city, city.copy(construction = order.copy(phase = phase, failure = null,
            payment = order.payment?.copy(awaiting = null))))
    }

    fun completeConstruction(city: Settlement, actor: UUID, orderId: UUID): Settlement {
        authorized(city, actor)
        if (city.construction == null) return city
        val order = order(city, orderId)
        check(order.phase == ConstructionPhase.PLACING, "construction-not-placing")
        check(order.batches == null, "invalid-construction")
        return finishConstruction(city, order)
    }

    fun completeBatch(city: Settlement, actor: UUID, orderId: UUID): Settlement {
        authorized(city, actor)
        if (city.construction == null) return city
        val order = order(city, orderId)
        check(order.batches != null && order.phase == ConstructionPhase.PLACING, "construction-not-placing")
        val completed = order.workDone + 1
        check(completed != order.workRequired || order.payment == null || order.materials.orEmpty().isEmpty(), "invalid-construction")
        return if (completed == order.workRequired) finishConstruction(city, order)
        else changed(city, city.copy(construction = order.copy(workDone = completed, phase = ConstructionPhase.CONSTRUCTING,
            payment = order.payment?.let { CivConstructionPayment() })))
    }

    private fun finishConstruction(city: Settlement, order: CivConstruction): Settlement {
        val finished = order.plannedBuilding!!.copy(phase = BuildingPhase.ACTIVE)
        val previous = city.building(finished.id)!!
        val growth = if (finished.type == BuildingType.TOWN_HALL && finished.level > previous.level) MAX_TERRITORY_GROWTH / 2 else 0
        check(city.radius <= Int.MAX_VALUE - growth, "arithmetic-overflow")
        return changed(city, city.copy(radius = city.radius + growth,
            buildings = city.buildings.map { if (it.id == finished.id) finished else it }, construction = null))
    }

    /** Called only for an observed completed worker trip; elapsed time alone never earns resources. */
    fun deliver(
        city: Settlement, actor: UUID, workerId: UUID, assignmentId: UUID,
        expectedCompletedCycles: Long, expectedNextDeliveryAt: Long, now: Long, outputHandled: Boolean = false,
    ): Settlement {
        authorized(city, actor)
        check(now >= 0, "invalid-time")
        val worker = city.workers.find { it.id == workerId } ?: reject("worker-not-found")
        check(worker.assignment != null, "unassigned")
        check(worker.assignment == assignmentId && worker.completedCycles == expectedCompletedCycles &&
            worker.nextDeliveryAt == expectedNextDeliveryAt, "stale-delivery")
        check(now >= worker.nextDeliveryAt, "delivery-too-early")
        val building = city.building(assignmentId) ?: reject("building-not-found")
        var stock = city.stock
        var construction = city.construction
        if (isBuilder(building)) {
            val current = construction ?: reject("no-construction")
            check(builderCanWork(city, building), "invalid-assignment")
            check(current.phase == ConstructionPhase.CONSTRUCTING, "construction-not-working")
            val done = current.workDone + if (current.batches == null) 1 else 0
            construction = current.copy(workDone = done,
                phase = if (current.batches != null || done == current.workRequired) ConstructionPhase.READY_TO_PLACE else ConstructionPhase.CONSTRUCTING)
        } else {
            check(productionAvailable(city, building) && slots(building) > 0, "invalid-assignment")
            val resource = resource(building.type) ?: reject("invalid-assignment")
            val amount = deliveryAmount(building)
            if (city.inventory == null) {
                check(!outputHandled, "invalid-delivery")
                check(capacity(city) - stock[resource] >= amount, "storage-full")
                stock = stock.with(resource, add(stock[resource], amount))
            } else check(outputHandled, "materials-required")
        }
        val updated = worker.copy(completedCycles = add(worker.completedCycles, 1), nextDeliveryAt = add(now, deliveryInterval(building.type)))
        return changed(city, city.copy(stock = stock, construction = construction,
            workers = city.workers.map { if (it.id == workerId) updated else it }))
    }

    /** Fail closed at the storage boundary, including missing/null fields from reflection-based JSON decoders. */
    fun validate(city: Settlement): Settlement {
        try {
            snapshot(present(city.id, city.owner, city.worldId, city.center, city.stock, city.buildings, city.workers, city.members))
            snapshot(validText(city.name, 48) && validText(city.style, 128) && city.radius > 0 && city.revision >= 0 && city.createdAt >= 0)
            snapshot(city.owner !in city.members && city.members.all { present(it) })
            snapshot(city.buildings.size in 1..MAX_BUILDINGS && city.buildings.map { it.id }.distinct().size == city.buildings.size)
            snapshot(city.buildings.count { it.type == BuildingType.TOWN_HALL } == 1)
            snapshot(city.buildings.all { validBuilding(city, it) })
            snapshot(city.buildings.all { it.type == BuildingType.TOWN_HALL || it.level <= city.level() })
            snapshot(city.buildings.withIndex().all { (index, building) -> city.buildings.drop(index + 1).none { it.bounds.overlaps(building.bounds) } })
            val order = city.construction
            if (order != null) {
                val plan = order.plannedBuilding
                snapshot(plan != null && validBuilding(city, plan))
                val target = plan!!
                val current = city.building(order.buildingId)
                snapshot(present(order.id) && current != null && order.buildingId == target.id && order.targetLevel == target.level)
                snapshot(target.phase == BuildingPhase.CONSTRUCTING && city.buildings.none { it.id != target.id && it.bounds.overlaps(target.bounds) })
                val founding = isFounding(city)
                snapshot(if (current!!.phase == BuildingPhase.CONSTRUCTING) current == target && target.level == 1 else
                    current.phase == BuildingPhase.ACTIVE && current.type == target.type && current.level + 1 == target.level &&
                        current.entrance == target.entrance && current.rotation == target.rotation)
                snapshot(target.type == BuildingType.TOWN_HALL || target.level <= city.level())
                snapshot(founding || target.type != BuildingType.TOWN_HALL || target.level > 1 || order.batches != null)
                snapshot(validConstructionPrice(order, target))
                snapshot(if (order.batches == null) order.workDone in 0..order.workRequired else order.workDone in 0 until order.workRequired)
                snapshot(when (order.phase) {
                    ConstructionPhase.CONSTRUCTING -> order.workDone < order.workRequired && order.failure == null
                    ConstructionPhase.READY_TO_PLACE, ConstructionPhase.PLACING ->
                        (order.batches != null || order.workDone == order.workRequired) && order.failure == null
                    ConstructionPhase.WAITING_MATERIALS -> order.payment?.awaiting?.isNotEmpty() == true && order.failure == null
                    ConstructionPhase.PAUSED -> order.failure != null && validText(order.failure, 512)
                })
                order.payment?.let { payment ->
                    snapshot(order.batches != null && order.batches.size <= 16)
                    snapshot(payment.reservedBatch == null || payment.reservedBatch == order.workDone &&
                        order.phase in setOf(ConstructionPhase.READY_TO_PLACE, ConstructionPhase.PLACING, ConstructionPhase.PAUSED))
                    snapshot(order.phase != ConstructionPhase.PLACING || payment.reservedBatch == order.workDone)
                    snapshot(payment.awaiting == null || order.phase in setOf(ConstructionPhase.WAITING_MATERIALS, ConstructionPhase.PAUSED) &&
                        payment.reservedBatch == null && payment.awaiting.isNotEmpty() && validMaterials(payment.awaiting) &&
                        payment.awaiting.all { (key, value) -> order.materials.orEmpty().getOrDefault(key, 0) >= value })
                }
            }
            snapshot(city.buildings.none { it.phase == BuildingPhase.CONSTRUCTING && it.id != order?.buildingId })
            snapshot(city.workers.size in 0..MAX_POPULATION && (city.workers.size >= 4 || city.immigration != null) &&
                city.workers.map { it.id }.distinct().size == city.workers.size)
            city.immigration?.let { snapshot(it.remaining in 0..4 && it.nextArrivalAt >= city.createdAt && city.workers.size + it.remaining >= 4) }
            snapshot(city.workers.all { worker ->
                present(worker.id) && validText(worker.name, 32) && worker.completedCycles >= 0 && worker.nextDeliveryAt >= 0 &&
                    (worker.assignment == null || worker.nextDeliveryAt > 0 && city.building(worker.assignment)?.let {
                        slots(it) > 0 || (it.type == BuildingType.TOWN_HALL && (it.phase == BuildingPhase.ACTIVE || isFounding(city)))
                    } == true)
            })
            snapshot(city.buildings.all { building ->
                city.workers.count { it.assignment == building.id } <=
                    if (building.type == BuildingType.TOWN_HALL && (building.phase == BuildingPhase.ACTIVE || isFounding(city))) 1 else slots(building)
            })
            snapshot(city.workers.size <= populationLimit(city) && city.workers.size <= beds(city))
            snapshot(Resource.entries.all { city.stock[it] in 0..capacity(city) })
            city.inventory?.let { inventory ->
                inventory.validated()
                snapshot(city.stock == Stock())
                snapshot(inventory.containers.all { city.contains(it.x, it.z) && it.y in -4_096..4_096 })
                inventory.buildingMaterials?.forEach { (id, items) ->
                    val building = city.building(UUID.fromString(id))
                    snapshot(building != null && items.values.sum() <= CivInventory.buildingCapacity(building))
                }
            }
            city.pendingControls?.let { pending ->
                snapshot(pending.size <= MAX_BUILDINGS && pending.map { it.buildingId }.distinct().size == pending.size)
                snapshot((city.buildings + listOfNotNull(city.construction?.plannedBuilding)).none { intersectsApproach(city, it) })
                val approachPoints = pending.flatMap { it.approach.orEmpty() }.map { it.position }
                snapshot(approachPoints.distinct().size == approachPoints.size)
                snapshot(pending.all { present(it.buildingId, it.position) && city.building(it.buildingId)?.control == it.position &&
                    it.before.isNotEmpty() && it.before.length <= CivsStore.MAX_BLOCK_STATE_CHARS &&
                    it.before.startsWith("minecraft:") && it.before.none(Char::isISOControl) })
                pending.forEach { receipt ->
                    val building = requireNotNull(city.building(receipt.buildingId))
                    val approach = receipt.approach.orEmpty()
                    snapshot(approach.size <= 3 && approach.map { it.position }.distinct().size == approach.size)
                    snapshot(approach.all { cell ->
                        val point = cell.position
                        present(point, cell.before, cell.after) && point.x == building.entrance.x && point.z == building.entrance.z &&
                            point.y.toLong() in (building.entrance.y.toLong() - 1)..(building.entrance.y.toLong() + 1) &&
                            point.y in -4_096..4_096 && point != receipt.position && !building.bounds.contains(point.x, point.y, point.z) &&
                            validText(cell.before, CivsStore.MAX_BLOCK_STATE_CHARS) && cell.before.startsWith("minecraft:") &&
                            cell.before != cell.after && cell.after == if (point.y < building.entrance.y) "minecraft:dirt" else "minecraft:air"
                    })
                }
            }
            return city
        } catch (error: CivsRejection) {
            if (error.reason == "invalid-state") throw error
            reject("invalid-state")
        } catch (_: RuntimeException) {
            reject("invalid-state")
        }
    }

    private fun validBuilding(city: Settlement, building: CivBuilding): Boolean =
        present(building.id, building.type, building.phase, building.anchor, building.bounds, building.entrance) && building.level in 1..3 &&
            building.rotation in listOf(0, 90, 180, 270) && validText(building.schematicId, 512) &&
            building.bounds.min.x <= building.bounds.max.x && building.bounds.min.y <= building.bounds.max.y && building.bounds.min.z <= building.bounds.max.z &&
            city.contains(building.bounds.min.x, building.bounds.min.z) && city.contains(building.bounds.max.x, building.bounds.max.z) &&
            city.contains(building.anchor.x, building.anchor.z) && city.contains(building.entrance.x, building.entrance.z) &&
            (building.phase != BuildingPhase.SITE || building.level == 1) &&
            (building.control?.let { city.contains(it.x, it.z) } != false) &&
            (building.production?.let { it in CivsProduction.options(building.type) } != false)

    private fun intersectsApproach(city: Settlement, building: CivBuilding): Boolean = city.pendingControls.orEmpty().any {
        it.buildingId != building.id && it.approach.orEmpty().any { cell ->
            building.bounds.contains(cell.position.x, cell.position.y, cell.position.z) || building.control == cell.position
        }
    }

    private fun validConstructionPrice(order: CivConstruction, building: CivBuilding): Boolean {
        val batches = order.batches
        if (batches == null) return order.payment == null && building.type != BuildingType.BUILDER_HUT && order.materials == null && order.cost == cost(building.type, building.level) &&
            order.workRequired == workRequired(building.type, building.level)
        val materials = order.materials ?: return false
        return batches.total in 1..75_000 && batches.size in 1..256 && order.workRequired == batches.total &&
            order.cost == Stock() && validMaterials(materials)
    }

    private fun validMaterials(materials: Map<String, Long>): Boolean =
        runCatching { CivInventory(CivStorageMode.VIRTUAL, materials).validated() }.isSuccess

    private fun isFounding(city: Settlement): Boolean = city.buildings.size == 1 && city.buildings.single().let {
        it.type == BuildingType.TOWN_HALL && it.level == 1 && it.phase == BuildingPhase.CONSTRUCTING && city.construction?.buildingId == it.id
    }

    private fun validText(text: String, max: Int): Boolean = text.isNotBlank() && text == text.trim() && text.length <= max && text.none { it.isISOControl() }
    private fun present(vararg values: Any?): Boolean = values.all { it != null }

    private fun order(city: Settlement, id: UUID): CivConstruction {
        val order = city.construction ?: reject("no-construction")
        check(order.id == id, "stale-construction")
        return order
    }

    private fun authorized(city: Settlement, actor: UUID) {
        check(city.canManage(actor), "not-authorized")
        validate(city)
    }

    private fun ownerOnly(city: Settlement, actor: UUID) {
        check(city.owner == actor, "owner-required")
        validate(city)
    }

    private fun changed(before: Settlement, after: Settlement): Settlement =
        if (before == after) before else validate(after.copy(revision = add(before.revision, 1)))

    private fun add(left: Long, right: Long): Long {
        check(left >= 0 && right >= 0 && left <= Long.MAX_VALUE - right, "arithmetic-overflow")
        return left + right
    }

    private fun snapshot(condition: Boolean) = check(condition, "invalid-state")
    private fun check(condition: Boolean, reason: String) { if (!condition) reject(reason) }
    private fun reject(reason: String): Nothing = throw CivsRejection(reason)
}
