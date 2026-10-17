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
    val STARTING_STOCK = Stock(96, 96, 24)
    private val ESSENTIAL_BUILDINGS = setOf(BuildingType.WAREHOUSE, BuildingType.FARM, BuildingType.HOUSE, BuildingType.FORESTER, BuildingType.QUARRY)

    fun cost(type: BuildingType, targetLevel: Int): Stock {
        check(targetLevel in 1..3, "invalid-level")
        if (type == BuildingType.TOWN_HALL) return when (targetLevel) {
            1 -> Stock()
            2 -> Stock(128, 96, 24)
            else -> Stock(384, 288, 72)
        }
        val base = when (type) {
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

    fun slots(building: CivBuilding): Int = if (building.phase != BuildingPhase.ACTIVE) 0 else when (building.type) {
        BuildingType.TOWN_HALL -> 1
        BuildingType.FORESTER, BuildingType.QUARRY, BuildingType.FARM -> building.level
        BuildingType.WAREHOUSE, BuildingType.HOUSE -> 0
    }

    fun resource(type: BuildingType): Resource? = when (type) {
        BuildingType.FORESTER -> Resource.WOOD
        BuildingType.QUARRY -> Resource.STONE
        BuildingType.FARM -> Resource.FOOD
        else -> null
    }

    fun profession(type: BuildingType): String = when (type) {
        BuildingType.TOWN_HALL -> "builder"
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

    fun deliveryInterval(type: BuildingType): Long = if (type == BuildingType.TOWN_HALL) BUILDER_INTERVAL else PRODUCTION_INTERVAL

    fun workRequired(type: BuildingType, targetLevel: Int): Int {
        check(targetLevel in 1..3, "invalid-level")
        return if (type == BuildingType.TOWN_HALL && targetLevel == 1) 0 else 3 * targetLevel
    }

    fun found(
        id: UUID, owner: UUID, name: String, worldId: UUID, center: CivPosition,
        radius: Int, style: String, townHall: CivBuilding, pioneers: List<CivWorker>, orderId: UUID, now: Long,
    ): Settlement {
        check(now >= 0, "invalid-time")
        check(townHall.type == BuildingType.TOWN_HALL && townHall.level == 1, "invalid-building")
        check(pioneers.size == 4 && pioneers.all { it.assignment == null && it.completedCycles == 0L && it.nextDeliveryAt == 0L }, "invalid-workers")
        val hall = townHall.copy(phase = BuildingPhase.CONSTRUCTING)
        val workers = pioneers.mapIndexed { index, worker ->
            worker.copy(name = worker.name.trim(), assignment = if (index == 0) hall.id else null,
                nextDeliveryAt = if (index == 0) add(now, BUILDER_INTERVAL) else 0)
        }
        return validate(Settlement(id, owner, name.trim(), worldId, center, radius, style,
            STARTING_STOCK, listOf(hall), workers,
            construction = CivConstruction(orderId, hall.id, 1, 0, 0, ConstructionPhase.READY_TO_PLACE, Stock(), plannedBuilding = hall),
            createdAt = now))
    }

    fun recruit(city: Settlement, actor: UUID, workerId: UUID, name: String): Settlement {
        authorized(city, actor)
        check(city.buildings.single { it.type == BuildingType.TOWN_HALL }.phase == BuildingPhase.ACTIVE, "town-hall-not-ready")
        check(city.workers.none { it.id == workerId }, "duplicate-worker")
        check(validText(name.trim(), 32), "invalid-name")
        check(city.workers.size < populationLimit(city), "population-full")
        check(city.workers.size < beds(city), "no-beds")
        check(city.stock.food >= RECRUIT_FOOD, "not-enough-resources")
        return changed(city, city.copy(stock = city.stock.copy(food = city.stock.food - RECRUIT_FOOD),
            workers = city.workers + CivWorker(workerId, name.trim(), null)))
    }

    fun assign(city: Settlement, actor: UUID, workerId: UUID, buildingId: UUID?, now: Long): Settlement {
        authorized(city, actor)
        check(now >= 0, "invalid-time")
        val worker = city.workers.find { it.id == workerId } ?: reject("worker-not-found")
        if (worker.assignment == buildingId) return city
        val building = buildingId?.let { city.building(it) ?: reject("building-not-found") }
        if (building != null) {
            check(slots(building) > 0, "invalid-assignment")
            check(city.workers.count { it.assignment == buildingId } < slots(building), "workplace-full")
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
            order.cost == cost(plan.type, plan.level) && order.workRequired == workRequired(plan.type, plan.level) &&
            order.workDone == 0 && order.phase == ConstructionPhase.CONSTRUCTING && order.failure == null &&
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
        } else {
            check(existing.phase == BuildingPhase.ACTIVE && existing.type == plan.type &&
                existing.entrance == plan.entrance && existing.rotation == plan.rotation, "invalid-building")
            check(plan.level == existing.level + 1 && plan.level <= 3, "invalid-level")
        }
        check(validBuilding(city, plan) && city.buildings.none { it.id != plan.id && it.bounds.overlaps(plan.bounds) }, "invalid-building")
        check(workRouteWithinReach(city, plan.entrance), "work-route-too-long")
        check(city.stock.covers(reserved.cost), "not-enough-resources")
        val remaining = city.stock - reserved.cost
        // Both basic producers remain affordable until their own construction has been reserved.
        val bootstrap = listOf(BuildingType.FORESTER, BuildingType.QUARRY)
            .filter { type -> plan.type != type && city.buildings.none { it.type == type } }.map { cost(it, 1) }
        check(Resource.entries.all { resource -> remaining[resource] >= bootstrap.sumOf { it[resource] } }, "resources.bootstrap-reserve")
        return changed(city, city.copy(stock = remaining,
            buildings = if (existing == null) city.buildings + plan else city.buildings,
            construction = reserved))
    }

    /** Same nearest active depot and three-dimensional entrance distance as citizenPlan. */
    internal fun workRouteWithinReach(city: Settlement, entrance: CivPosition): Boolean {
        val nearestSquared = city.buildings.filter { it.phase == BuildingPhase.ACTIVE &&
            (it.type == BuildingType.TOWN_HALL || it.type == BuildingType.WAREHOUSE) }.minOfOrNull {
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
        return changed(city, city.copy(construction = order.copy(phase = ConstructionPhase.PLACING)))
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
        val phase = if (order.workDone == order.workRequired) ConstructionPhase.READY_TO_PLACE else ConstructionPhase.CONSTRUCTING
        return changed(city, city.copy(construction = order.copy(phase = phase, failure = null)))
    }

    fun completeConstruction(city: Settlement, actor: UUID, orderId: UUID): Settlement {
        authorized(city, actor)
        if (city.construction == null) return city
        val order = order(city, orderId)
        check(order.phase == ConstructionPhase.PLACING, "construction-not-placing")
        val finished = order.plannedBuilding!!.copy(phase = BuildingPhase.ACTIVE)
        return changed(city, city.copy(buildings = city.buildings.map { if (it.id == finished.id) finished else it }, construction = null))
    }

    /** Called only for an observed completed worker trip; elapsed time alone never earns resources. */
    fun deliver(
        city: Settlement, actor: UUID, workerId: UUID, assignmentId: UUID,
        expectedCompletedCycles: Long, expectedNextDeliveryAt: Long, now: Long,
    ): Settlement {
        authorized(city, actor)
        check(now >= 0, "invalid-time")
        val worker = city.workers.find { it.id == workerId } ?: reject("worker-not-found")
        check(worker.assignment != null, "unassigned")
        check(worker.assignment == assignmentId && worker.completedCycles == expectedCompletedCycles &&
            worker.nextDeliveryAt == expectedNextDeliveryAt, "stale-delivery")
        check(now >= worker.nextDeliveryAt, "delivery-too-early")
        val building = city.building(assignmentId) ?: reject("building-not-found")
        check(building.phase == BuildingPhase.ACTIVE && slots(building) > 0, "invalid-assignment")
        var stock = city.stock
        var construction = city.construction
        if (building.type == BuildingType.TOWN_HALL) {
            val current = construction ?: reject("no-construction")
            check(current.phase == ConstructionPhase.CONSTRUCTING, "construction-not-working")
            val done = current.workDone + 1
            construction = current.copy(workDone = done,
                phase = if (done == current.workRequired) ConstructionPhase.READY_TO_PLACE else ConstructionPhase.CONSTRUCTING)
        } else {
            val resource = resource(building.type) ?: reject("invalid-assignment")
            val amount = deliveryAmount(building)
            check(capacity(city) - stock[resource] >= amount, "storage-full")
            stock = stock.with(resource, add(stock[resource], amount))
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
                    current.type == target.type && current.level + 1 == target.level && current.entrance == target.entrance && current.rotation == target.rotation)
                snapshot(target.type == BuildingType.TOWN_HALL || target.level <= city.level())
                snapshot(founding || target.type != BuildingType.TOWN_HALL || target.level > 1)
                snapshot(order.cost == cost(target.type, target.level) && order.workRequired == workRequired(target.type, target.level))
                snapshot(order.workDone in 0..order.workRequired)
                snapshot(when (order.phase) {
                    ConstructionPhase.CONSTRUCTING -> order.workDone < order.workRequired && order.failure == null
                    ConstructionPhase.READY_TO_PLACE, ConstructionPhase.PLACING -> order.workDone == order.workRequired && order.failure == null
                    ConstructionPhase.PAUSED -> order.failure != null && validText(order.failure, 512)
                })
            }
            snapshot(city.buildings.none { it.phase == BuildingPhase.CONSTRUCTING && it.id != order?.buildingId })
            snapshot(city.workers.size in 4..MAX_POPULATION && city.workers.map { it.id }.distinct().size == city.workers.size)
            snapshot(city.workers.all { worker ->
                present(worker.id) && validText(worker.name, 32) && worker.completedCycles >= 0 && worker.nextDeliveryAt >= 0 &&
                    (worker.assignment == null || worker.nextDeliveryAt > 0 && city.building(worker.assignment)?.let {
                        slots(it) > 0 || (isFounding(city) && it.type == BuildingType.TOWN_HALL)
                    } == true)
            })
            snapshot(city.buildings.all { building ->
                city.workers.count { it.assignment == building.id } <=
                    if (isFounding(city) && building.type == BuildingType.TOWN_HALL) 1 else slots(building)
            })
            snapshot(city.workers.size <= populationLimit(city) && city.workers.size <= beds(city))
            snapshot(Resource.entries.all { city.stock[it] in 0..capacity(city) })
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
            city.contains(building.anchor.x, building.anchor.z) && city.contains(building.entrance.x, building.entrance.z)

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
