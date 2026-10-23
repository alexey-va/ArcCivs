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
    const val BUILDER_SESSION_INTERVAL = 2_000L
    const val BUILDER_SESSION_ROWS = 64
    const val MAX_BUILDER_WORK_CREDIT = MAX_POPULATION * BUILDER_SESSION_ROWS
    const val FIRST_IMMIGRATION_DELAY = 30_000L
    const val IMMIGRATION_INTERVAL = 60_000L
    const val MAX_TERRITORY_GROWTH = 64
    val STARTING_STOCK = Stock(96, 96, 24)

    /** The service journals deletion before retiring registered control barrels. */
    fun requireDeletion(city: Settlement, actor: UUID, expectedCityId: UUID, confirmationName: String) {
        check(city.owner == actor, "owner-required")
        validate(city)
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
            BuildingType.BUILDER_HUT, BuildingType.SAWMILL, BuildingType.BLACKSMITH,
            BuildingType.STONEMASON, BuildingType.LIBRARY -> Stock() // New construction reserves exact materials, never an abstract price.
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

    fun beds(city: Settlement): Int = CivsPopulation.IMMIGRANT_POPULATION + city.buildings.sumOf {
        if (it.phase == BuildingPhase.ACTIVE && it.type == BuildingType.HOUSE) CivsPopulation.houseBeds(it) else 0
    }

    fun populationLimit(city: Settlement): Int = minOf(MAX_POPULATION, maxOf(CivsPopulation.IMMIGRANT_POPULATION, city.level() * 4))

    fun slots(building: CivBuilding): Int = if (building.type == BuildingType.BUILDER_HUT) {
        if (building.phase == BuildingPhase.ACTIVE) building.level else 1
    }
    else if (building.phase != BuildingPhase.ACTIVE) 0 else when (building.type) {
        BuildingType.TOWN_HALL, BuildingType.WAREHOUSE, BuildingType.FORESTER, BuildingType.QUARRY, BuildingType.FARM,
        BuildingType.SAWMILL, BuildingType.BLACKSMITH, BuildingType.STONEMASON, BuildingType.LIBRARY -> building.level
        else -> 0
    }

    /** Old full cities have no spare plot for the newly introduced builder hut. New sites reserve that plot. */
    fun legacyHallBuilder(city: Settlement): Boolean = city.inventory != null && city.buildings.size == MAX_BUILDINGS &&
        city.buildings.none { it.type == BuildingType.BUILDER_HUT } &&
        city.buildings.any { it.type == BuildingType.TOWN_HALL && it.phase == BuildingPhase.ACTIVE }

    fun slots(city: Settlement, building: CivBuilding): Int =
        if (building.type == BuildingType.WAREHOUSE && city.inventory == null) 0
        else if (building.type == BuildingType.TOWN_HALL && isBuilder(city, building)) {
            if (building.phase == BuildingPhase.ACTIVE) 1 else 0
        } else slots(building)

    /** Old paid orders and full cities keep their hall builder until a normal hut can serve them. */
    fun effectiveWorkType(city: Settlement, building: CivBuilding): BuildingType =
        if (building.type == BuildingType.TOWN_HALL && (city.inventory == null || legacyHallBuilder(city)))
            BuildingType.BUILDER_HUT else building.type

    fun isBuilder(city: Settlement, building: CivBuilding): Boolean = effectiveWorkType(city, building) == BuildingType.BUILDER_HUT

    fun isDepot(building: CivBuilding): Boolean = building.type == BuildingType.TOWN_HALL ||
        building.type == BuildingType.WAREHOUSE && building.phase == BuildingPhase.ACTIVE

    fun builderCanWork(city: Settlement, building: CivBuilding): Boolean = when (building.type) {
        BuildingType.TOWN_HALL -> isBuilder(city, building) && building.phase == BuildingPhase.ACTIVE &&
            (city.construction?.batches == null || legacyHallBuilder(city))
        BuildingType.BUILDER_HUT -> building.phase == BuildingPhase.ACTIVE || city.construction?.buildingId == building.id
        else -> false
    }

    /** A site hut bootstraps itself; town halls never provide construction storage. */
    fun constructionStores(city: Settlement, buildingId: UUID): List<CivBuilding> = city.buildings.filter {
        it.type == BuildingType.BUILDER_HUT && it.control != null &&
            (it.phase == BuildingPhase.ACTIVE || it.id == buildingId)
    }

    fun constructionWorkers(city: Settlement, buildingId: UUID): List<CivWorker> = city.workers.filter { worker ->
        !CivsResidents.isChild(worker) && worker.cargo == null && worker.assignment?.let(city::building)?.let { assigned ->
            assigned.type == BuildingType.BUILDER_HUT && (assigned.phase == BuildingPhase.ACTIVE || assigned.id == buildingId) ||
                assigned.type == BuildingType.TOWN_HALL && legacyHallBuilder(city)
        } == true
    }

    fun constructionStorageInputs(city: Settlement, buildingId: UUID, materials: Map<String, Long>): Map<String, Long> =
        constructionWorkers(city, buildingId).fold(materials) { remaining, worker -> CivsEquipment.storageInputs(worker, remaining) }

    fun productionAvailable(city: Settlement, building: CivBuilding): Boolean =
        building.phase == BuildingPhase.ACTIVE && !isBuilder(city, building) &&
            CivsProduction.options(building.type).isNotEmpty() && city.construction?.buildingId != building.id

    fun resource(type: BuildingType): Resource? = when (type) {
        BuildingType.FORESTER -> Resource.WOOD
        BuildingType.QUARRY -> Resource.STONE
        BuildingType.FARM -> Resource.FOOD
        else -> null
    }

    fun profession(type: BuildingType): String = when (type) {
        BuildingType.TOWN_HALL -> "scribe"
        BuildingType.BUILDER_HUT -> "builder"
        BuildingType.FORESTER -> "forester"
        BuildingType.QUARRY -> "miner"
        BuildingType.FARM -> "farmer"
        BuildingType.SAWMILL -> "carpenter"
        BuildingType.BLACKSMITH -> "blacksmith"
        BuildingType.STONEMASON -> "stonemason"
        BuildingType.LIBRARY -> "librarian"
        BuildingType.WAREHOUSE -> "courier"
        else -> "unassigned"
    }

    fun deliveryAmount(building: CivBuilding): Long = when (resource(building.type)) {
        Resource.WOOD -> 4L * building.level
        Resource.STONE, Resource.FOOD -> 3L * building.level
        null -> 0L
    }

    fun profession(city: Settlement, building: CivBuilding): String = profession(effectiveWorkType(city, building))

    fun deliveryInterval(type: BuildingType): Long = if (type == BuildingType.BUILDER_HUT) BUILDER_INTERVAL else PRODUCTION_INTERVAL

    fun deliveryInterval(city: Settlement, building: CivBuilding): Long = deliveryInterval(effectiveWorkType(city, building))

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
            immigration = CivImmigration(CivsPopulation.IMMIGRANT_POPULATION, add(now, FIRST_IMMIGRATION_DELAY))))
    }

    fun immigrationDue(city: Settlement, now: Long): Boolean = city.pendingDeletion == null &&
        city.workers.size < minOf(CivsPopulation.IMMIGRANT_POPULATION, populationLimit(city), beds(city)) &&
        now >= immigrationArrival(city)

    private fun immigrationArrival(city: Settlement): Long = city.immigration?.nextArrivalAt ?:
        if (city.createdAt > Long.MAX_VALUE - FIRST_IMMIGRATION_DELAY) Long.MAX_VALUE else city.createdAt + FIRST_IMMIGRATION_DELAY

    fun immigrate(city: Settlement, actor: UUID, workerId: UUID, name: String, now: Long): Settlement {
        authorized(city, actor)
        check(now >= 0, "invalid-time")
        if (city.workers.size >= CivsPopulation.IMMIGRANT_POPULATION) return city
        check(now >= immigrationArrival(city), "immigration-not-ready")
        check(city.workers.none { it.id == workerId }, "duplicate-worker")
        check(validText(name.trim(), 32), "invalid-name")
        check(city.workers.size < populationLimit(city), "population-full")
        check(city.workers.size < beds(city), "no-beds")
        val home = city.buildings.first { it.type == BuildingType.TOWN_HALL }
        return changed(city, city.copy(workers = city.workers + CivWorker(workerId, name.trim(), null,
            sex = CivsPopulation.nextImmigrantSex(city), homeId = home.id, hunger = 20),
            immigration = CivImmigration(CivsPopulation.IMMIGRANT_POPULATION - city.workers.size - 1, add(now, IMMIGRATION_INTERVAL))))
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
        val queue = if (city.construction != null || city.constructionQueue.orEmpty().isNotEmpty())
            city.constructionQueue.orEmpty() + site.id else city.constructionQueue
        return changed(city, city.copy(buildings = city.buildings + site, constructionQueue = queue))
    }

    fun startSite(city: Settlement, actor: UUID, building: CivBuilding, order: CivConstruction): Settlement {
        authorized(city, actor)
        val existing = city.building(building.id) ?: reject("building-not-found")
        check(existing.phase == BuildingPhase.SITE || city.construction?.id == order.id, "invalid-building")
        return reserveBuild(city, actor, building, order)
    }

    fun enqueue(city: Settlement, actor: UUID, buildingId: UUID): Settlement {
        authorized(city, actor)
        val building = city.building(buildingId) ?: reject("invalid-building")
        check(building.phase == BuildingPhase.SITE || building.phase == BuildingPhase.ACTIVE && building.level < 3, "invalid-building")
        check(city.construction?.buildingId != buildingId, "construction-busy")
        check(building.phase == BuildingPhase.SITE || building.type == BuildingType.TOWN_HALL || building.level < city.level(), "town-hall-level-required")
        if (buildingId in city.constructionQueue.orEmpty()) return city
        return changed(city, city.copy(constructionQueue = city.constructionQueue.orEmpty() + buildingId))
    }

    fun requestDeletion(city: Settlement, actor: UUID, buildingId: UUID?): Settlement {
        check(city.canManage(actor), "not-authorized")
        validate(city)
        if (buildingId == null) check(city.owner == actor, "owner-required")
        val intent = CivDeletion(buildingId)
        city.pendingDeletion?.let { check(it == intent, "deletion-pending"); return city }
        if (buildingId != null) withoutBuilding(city, buildingId) // Capacity and item limits before native cleanup.
        return changed(city, city.copy(pendingDeletion = intent))
    }

    fun finishBuildingDeletion(city: Settlement, actor: UUID): Settlement {
        check(city.canManage(actor), "not-authorized")
        validate(city)
        val buildingId = city.pendingDeletion?.buildingId ?: reject("invalid-building")
        return changed(city, withoutBuilding(city, buildingId).copy(pendingDeletion = null))
    }

    private fun withoutBuilding(city: Settlement, buildingId: UUID): Settlement {
        val building = city.building(buildingId) ?: reject("building-not-found")
        check(building.type != BuildingType.TOWN_HALL, "town-hall-delete")
        val inventory = city.inventory?.let { previous ->
            val materials = previous.materials.toMutableMap()
            previous.buildingStock(buildingId).forEach { (key, amount) -> materials[key] = add(materials.getOrDefault(key, 0), amount) }
            check(materials.size <= CivInventory.MAX_MATERIALS && materials.values.all { it <= CivInventory.MAX_AMOUNT }, "storage-full")
            previous.copy(materials = materials, buildingMaterials = previous.buildingMaterials?.minus(buildingId.toString()),
                containers = previous.containers.filterNot { it == building.control })
        }
        val next = city.copy(buildings = city.buildings.filterNot { it.id == buildingId },
            mineProgress = city.mineProgress?.minus(buildingId.toString()),
            minePlans = city.minePlans?.minus(buildingId.toString()),
            workers = city.workers.map { it.copy(assignment = it.assignment?.takeUnless { id -> id == buildingId },
                homeId = it.homeId?.takeUnless { id -> id == buildingId }) },
            families = city.families?.filterNot { it.homeId == buildingId },
            construction = city.construction?.takeUnless { it.buildingId == buildingId },
            constructionQueue = city.constructionQueue?.filterNot { it == buildingId },
            pendingControls = city.pendingControls?.filterNot { it.buildingId == buildingId }, inventory = inventory)
        return next
    }

    fun recruit(city: Settlement, actor: UUID, workerId: UUID, name: String): Settlement {
        authorized(city, actor)
        reject("population-natural")
    }

    /** Retained to reject stale callers before any new recruitment receipt can be committed. */
    fun recruitFunded(city: Settlement, actor: UUID, workerId: UUID, name: String): Settlement {
        authorized(city, actor)
        reject("population-natural")
    }

    fun assign(city: Settlement, actor: UUID, workerId: UUID, buildingId: UUID?, now: Long): Settlement {
        authorized(city, actor)
        check(now >= 0, "invalid-time")
        val worker = city.workers.find { it.id == workerId } ?: reject("worker-not-found")
        check(buildingId == null || !CivsResidents.isChild(worker), "child-cannot-work")
        if (worker.assignment == buildingId) return city
        val building = buildingId?.let { city.building(it) ?: reject("building-not-found") }
        if (building != null) {
            check(slots(city, building) > 0, "invalid-assignment")
            check(city.workers.count { it.assignment == buildingId } < slots(city, building), "workplace-full")
        }
        // A monotonic schedule also invalidates an old trip after unassign/reassign at the same timestamp.
        val due = maxOf(add(worker.nextDeliveryAt, 1), add(now,
            CivsResidents.workDuration(worker, building?.let { deliveryInterval(city, it) } ?: 0L)))
        return changed(city, city.copy(workers = city.workers.map {
            if (it.id == workerId) it.copy(assignment = buildingId, nextDeliveryAt = due) else it
        }))
    }

    fun hurtResident(city: Settlement, actor: UUID, workerId: UUID, damage: Double, death: CivDeathContext? = null,
        immortal: Boolean = false): Settlement {
        authorized(city, actor)
        check(damage.isFinite() && damage > 0, "invalid-damage")
        val worker = city.workers.find { it.id == workerId } ?: reject("worker-not-found")
        val health = (CivsResidents.health(worker) - damage).let { if (immortal) it.coerceAtLeast(1.0) else it }
        return changed(city, city.copy(workers = if (health <= 0) city.workers.filterNot { it.id == workerId }
            else city.workers.map { if (it.id == workerId) it.copy(health = health) else it },
            families = if (health <= 0) city.families?.filterNot {
                it.firstParentId == workerId || it.secondParentId == workerId
            } else city.families,
            corpses = if (health <= 0 && death != null) city.corpses.orEmpty() + CivsCorpses.create(worker, death)
                else city.corpses))
    }

    /** The caller includes the exact town-storage debit/credit in this same revision's receipt. */
    fun supplyResident(city: Settlement, actor: UUID, workerId: UUID, material: String, returning: Boolean = false): Settlement {
        authorized(city, actor)
        check(material in CivsResidents.catalog, "invalid-resident-item")
        val worker = city.workers.find { it.id == workerId } ?: reject("worker-not-found")
        if (!returning) {
            check(CivsEquipment.plainCount(worker, material) < if (material == "BREAD") 16 else 1, "resident-inventory-full")
            val armor = CivsEquipment.armorSlot(material)
            check(armor == null || CivsEquipment.slots(worker)[armor] == null, "resident-slot-occupied")
            check(CivsResidents.canCarry(worker, material), "resident-inventory-full")
        }
        val updated = CivsEquipment.supply(worker, material, returning)
        return changed(city, city.copy(workers = city.workers.map { if (it.id == workerId) updated else it }))
    }

    fun feedResident(city: Settlement, actor: UUID, workerId: UUID): Settlement {
        authorized(city, actor)
        val worker = city.workers.find { it.id == workerId } ?: reject("worker-not-found")
        check(CivsResidents.health(worker) < CivsResidents.maxHealth(worker) || CivsResidents.hunger(worker) < 20, "resident-healthy")
        val count = CivsEquipment.plainCount(worker, "BREAD")
        check(count > 0, "resident-food-missing")
        val updated = CivsEquipment.consume(worker, "BREAD", 1)
            .copy(health = minOf(CivsResidents.maxHealth(worker), CivsResidents.health(worker) + 6),
                hunger = minOf(20, CivsResidents.hunger(worker) + 6))
        return changed(city, city.copy(workers = city.workers.map { if (it.id == workerId) updated else it }))
    }

    fun needsAutoAssignment(city: Settlement): Boolean = city.workers.any { it.assignment == null && !CivsResidents.isChild(it) } &&
        city.buildings.any { automaticVacancies(city, it) > 0 }

    private fun automaticVacancies(city: Settlement, building: CivBuilding): Int {
        val capacity = if (building.type == BuildingType.WAREHOUSE) minOf(1, slots(city, building)) else slots(city, building)
        return (capacity - city.workers.count { it.assignment == building.id }).coerceAtLeast(0)
    }

    /** Fill vacancies without moving a resident who already has a job. Builder sites bootstrap themselves. */
    fun autoAssign(city: Settlement, actor: UUID, now: Long): Settlement {
        authorized(city, actor)
        check(now >= 0, "invalid-time")
        if (!needsAutoAssignment(city)) return city
        val vacancies = city.buildings.sortedBy { when {
            isBuilder(city, it) -> 0
            it.type == BuildingType.WAREHOUSE -> 1
            it.type == BuildingType.TOWN_HALL -> 3
            else -> 2
        } }.flatMap { building ->
            List(automaticVacancies(city, building)) { building }
        }.iterator()
        val workers = city.workers.map { worker ->
            if (CivsResidents.isChild(worker) || worker.assignment != null || !vacancies.hasNext()) worker else {
                val building = vacancies.next()
                worker.copy(assignment = building.id,
                    nextDeliveryAt = maxOf(add(worker.nextDeliveryAt, 1),
                        add(now, CivsResidents.workDuration(worker, deliveryInterval(city, building)))))
            }
        }
        return changed(city, city.copy(workers = workers))
    }

    fun addMember(city: Settlement, actor: UUID, member: UUID): Settlement {
        ownerOnly(city, actor)
        if (member == city.owner || member in city.members) return city
        return changed(city, city.copy(members = city.members + member))
    }

    fun removeMember(city: Settlement, actor: UUID, member: UUID): Settlement {
        ownerOnly(city, actor)
        check(member != city.owner, "owner-cannot-be-removed")
        return changed(city, city.copy(members = city.members - member,
            memberNames = city.memberNames?.minus(member.toString())?.takeIf { it.isNotEmpty() }))
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
        if (city.constructionQueue.orEmpty().isNotEmpty())
            check(city.constructionQueue!!.first() == plan.id, "construction-busy")
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
            construction = reserved, constructionQueue = city.constructionQueue?.filterNot { it == plan.id }))
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
        check(payment.awaiting == null || CivsMaterials.unpaid(payment.awaiting) == materials, "invalid-construction")
        check(constructionStorageInputs(city, order.buildingId, materials).isEmpty(), "materials-missing")
        val remaining = CivsMaterials.unpaid(order.materials.orEmpty()).mapValues { (key, value) -> value - materials.getOrDefault(key, 0) }.filterValues { it > 0 }
        var storageInputs = materials
        val builders = constructionWorkers(city, order.buildingId).map(CivWorker::id).toSet()
        val workers = city.workers.map { worker ->
            if (worker.id !in builders) worker else CivsEquipment.consumeInputs(worker, storageInputs).also {
                storageInputs = CivsEquipment.storageInputs(worker, storageInputs)
            }
        }
        return changed(city, city.copy(construction = order.copy(phase = ConstructionPhase.PLACING,
            materials = remaining, payment = CivConstructionPayment(batch)), workers = workers))
    }

    fun awaitMaterials(city: Settlement, actor: UUID, orderId: UUID, batch: Int, materials: Map<String, Long>): Settlement {
        authorized(city, actor)
        val order = order(city, orderId)
        check(order.payment?.reservedBatch == null && order.payment != null && order.workDone == batch &&
            order.phase in setOf(ConstructionPhase.READY_TO_PLACE, ConstructionPhase.WAITING_MATERIALS) &&
            materials.isNotEmpty() && validMaterials(materials) &&
            materials.all { (key, value) -> order.materials.orEmpty().getOrDefault(key, 0) >= value }, "invalid-construction")
        if (order.phase == ConstructionPhase.WAITING_MATERIALS && order.payment?.awaiting == materials) return city
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
        val phase = if (order.workDone == order.workRequired || order.payment?.reservedBatch == order.workDone || (order.workCredit ?: 0) > 0)
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
        else changed(city, city.copy(construction = order.copy(workDone = completed,
            workCredit = order.workCredit?.let { (it - 1).coerceAtLeast(0) },
            phase = if ((order.workCredit ?: 0) > 1) ConstructionPhase.READY_TO_PLACE else ConstructionPhase.CONSTRUCTING,
            payment = order.payment?.let { CivConstructionPayment() })))
    }

    private fun finishConstruction(city: Settlement, order: CivConstruction): Settlement {
        val finished = order.plannedBuilding!!.copy(phase = BuildingPhase.ACTIVE)
        val previous = city.building(finished.id)!!
        val growth = if (finished.type == BuildingType.TOWN_HALL && finished.level > previous.level) MAX_TERRITORY_GROWTH / 2 else 0
        check(city.radius <= Int.MAX_VALUE - growth, "arithmetic-overflow")
        return changed(city, city.copy(radius = city.radius + growth,
            buildings = city.buildings.map { if (it.id == finished.id) finished else it }, construction = null,
            mineProgress = if (finished.type == BuildingType.QUARRY && finished.level > previous.level)
                city.mineProgress.orEmpty() - finished.id.toString() else city.mineProgress,
            minePlans = if (finished.type == BuildingType.QUARRY && finished.level > previous.level)
                city.minePlans.orEmpty() - finished.id.toString() else city.minePlans))
    }

    /** Called only for an observed completed worker trip; elapsed time alone never earns resources. */
    fun deliver(
        city: Settlement, actor: UUID, workerId: UUID, assignmentId: UUID,
        expectedCompletedCycles: Long, expectedNextDeliveryAt: Long, now: Long, outputHandled: Boolean = false,
        craftJob: CivCraftJob? = null, equipmentAfter: List<CivResidentItem?>? = null,
    ): Settlement {
        authorized(city, actor)
        check(now >= 0, "invalid-time")
        val worker = city.workers.find { it.id == workerId } ?: reject("worker-not-found")
        check(!CivsResidents.isChild(worker), "child-cannot-work")
        check(worker.cargo == null, "stale-delivery")
        check(worker.assignment != null, "unassigned")
        check(worker.assignment == assignmentId && worker.completedCycles == expectedCompletedCycles &&
            worker.nextDeliveryAt == expectedNextDeliveryAt, "stale-delivery")
        check(now >= worker.nextDeliveryAt, "delivery-too-early")
        val building = city.building(assignmentId) ?: reject("building-not-found")
        if (craftJob != null) check(CivsCarpentry.valid(city, worker, craftJob), "stale-delivery")
        check(equipmentAfter == null || craftJob?.let(CivsCarpentry::recipe)?.stripping == true, "invalid-delivery")
        check(craftJob?.let(CivsCarpentry::recipe)?.stripping != true || equipmentAfter != null, "invalid-delivery")
        val productionInputs = craftJob?.inputs ?: CivsProduction.inputs(building)
        var stock = city.stock
        var construction = city.construction
        if (isBuilder(city, building)) {
            val current = construction ?: reject("no-construction")
            check(builderCanWork(city, building), "invalid-assignment")
            if (current.payment != null) {
                check(current.phase in setOf(ConstructionPhase.CONSTRUCTING, ConstructionPhase.READY_TO_PLACE,
                    ConstructionPhase.PLACING), "construction-not-working")
                val credit = current.workCredit ?: 0
                val room = minOf(MAX_BUILDER_WORK_CREDIT, current.workRequired - current.workDone) - credit
                check(room > 0, "construction-credit-full")
                construction = current.copy(workCredit = credit + minOf(BUILDER_SESSION_ROWS, room),
                    phase = if (current.phase == ConstructionPhase.CONSTRUCTING) ConstructionPhase.READY_TO_PLACE else current.phase)
            } else {
                check(current.phase == ConstructionPhase.CONSTRUCTING, "construction-not-working")
                val done = current.workDone + if (current.batches == null) 1 else 0
                construction = current.copy(workDone = done,
                    phase = if (current.batches != null || done == current.workRequired) ConstructionPhase.READY_TO_PLACE else ConstructionPhase.CONSTRUCTING)
            }
        } else {
            check(productionAvailable(city, building) && slots(city, building) > 0, "invalid-assignment")
            if (city.inventory == null) {
                val resource = resource(building.type) ?: reject("invalid-assignment")
                val amount = deliveryAmount(building)
                check(!outputHandled, "invalid-delivery")
                check(capacity(city) - stock[resource] >= amount, "storage-full")
                stock = stock.with(resource, add(stock[resource], amount))
            } else {
                check(outputHandled, "materials-required")
                check(CivsEquipment.inputsReady(worker, productionInputs, emptyMap()), "materials-missing")
            }
        }
        val interval = CivsResidents.workDuration(worker,
            if (isBuilder(city, building) && construction?.payment != null) BUILDER_SESSION_INTERVAL else deliveryInterval(city, building))
        val equipped = equipmentAfter?.let { CivsEquipment.replace(worker, it) } ?: worker
        val supplied = if (!isBuilder(city, building) && city.inventory != null && outputHandled)
            CivsEquipment.consumeInputs(equipped, productionInputs) else equipped
        val updated = CivsResidents.trained(supplied.copy(completedCycles = add(worker.completedCycles, 1),
            nextDeliveryAt = add(now, interval)), effectiveWorkType(city, building))
        return changed(city, city.copy(stock = stock, construction = construction,
            workers = city.workers.map { if (it.id == workerId) updated else it }))
    }

    internal fun finishWorldWork(city: Settlement, work: CivWorldWork, now: Long): Settlement {
        val worker = city.workers.firstOrNull { it.id == work.workerId } ?: reject("worker-not-found")
        check(!CivsResidents.isChild(worker), "child-cannot-work")
        val building = city.building(work.buildingId) ?: reject("building-not-found")
        check(worker.assignment == building.id && worker.completedCycles == work.cycle && worker.cargo == null, "stale-delivery")
        check(productionAvailable(city, building) && building.type in CivsWorksites.TYPES, "invalid-assignment")
        val equipped = work.equipmentAfter?.let { CivsEquipment.replace(worker, it) } ?: worker
        val supplied = if (work.equipmentAfter != null && work.changes.isEmpty()) equipped else CivsEquipment.consumeInputs(equipped, work.inputs)
        val produced = if (work.outputs.isNotEmpty())
            CivsEquipment.collectSupplies(supplied, work.outputs) else supplied
        val progressed = produced.copy(completedCycles = add(worker.completedCycles, 1), nextDeliveryAt = add(now, 1_000))
        val updated = if (work.changes.isEmpty() || work.cropCare) progressed else CivsResidents.trained(progressed, building.type)
        check(work.miningPlanVersion == null || work.miningPlanVersion in 2..CivsMinePlan.VERSION && work.miningStep != null,
            "stale-worksite")
        val progress = work.miningStep?.takeIf { work.miningPlanVersion == null }?.let { next ->
            city.mineProgress.orEmpty() + (building.id.toString() to next)
        } ?: city.mineProgress
        val plans = work.miningStep?.takeIf { work.miningPlanVersion != null }?.let { next ->
            val key = building.id.toString()
            val previous = city.minePlans?.get(key)
            val version = requireNotNull(work.miningPlanVersion)
            val plan = CivsMinePlan.forBuilding(building, version) ?: reject("stale-worksite")
            check(building.type == BuildingType.QUARRY && work.changes.isNotEmpty() &&
                (previous == null || version >= previous.version) &&
                next in (previous?.takeIf { it.version == version }?.nextStep ?: 0)..plan.steps.size.toLong(), "stale-worksite")
            city.minePlans.orEmpty() + (key to CivMinePlanState(version, next,
                previous?.legacyStep ?: city.mineProgress?.get(key),
                previous?.takeIf { it.version == 2 && version >= 3 }?.nextStep ?: previous?.v2Step,
                previous?.takeIf { it.version == 3 && version >= 4 }?.nextStep ?: previous?.v3Step))
        } ?: city.minePlans
        return changed(city, city.copy(workers = city.workers.map { if (it.id == worker.id) updated else it },
            mineProgress = progress, minePlans = plans))
    }

    /** Fail closed at the storage boundary, including missing/null fields from reflection-based JSON decoders. */
    fun validate(city: Settlement): Settlement {
        try {
            snapshot(present(city.id, city.owner, city.worldId, city.center, city.stock, city.buildings, city.workers, city.members))
            snapshot(validText(city.name, 48) && validText(city.style, 128) && city.radius > 0 && city.revision >= 0 && city.createdAt >= 0)
            snapshot(city.owner !in city.members && city.members.all { present(it) })
            city.memberNames?.let { names ->
                val participants = (city.members + city.owner).mapTo(hashSetOf(), UUID::toString)
                snapshot(names.keys.all { it in participants } && names.values.all {
                    it.isNotBlank() && it.length <= 32 && it.none(Char::isISOControl)
                })
            }
            snapshot(city.buildings.size in 1..MAX_BUILDINGS && city.buildings.map { it.id }.distinct().size == city.buildings.size)
            snapshot(city.buildings.count { it.type == BuildingType.TOWN_HALL } == 1)
            snapshot(city.buildings.all { validBuilding(city, it) })
            snapshot(city.buildings.all { it.type == BuildingType.TOWN_HALL || it.level <= city.level() })
            snapshot(city.buildings.withIndex().all { (index, building) -> city.buildings.drop(index + 1).none { it.bounds.overlaps(building.bounds) } })
            val order = city.construction
            city.constructionQueue?.let { queue ->
                snapshot(queue.size <= MAX_BUILDINGS && queue.distinct().size == queue.size &&
                    queue.all { id -> present(id) && city.building(id)?.let {
                        it.phase == BuildingPhase.SITE || it.phase == BuildingPhase.ACTIVE && it.level < 3
                    } == true && id != order?.buildingId })
            }
            city.pendingDeletion?.buildingId?.let { id -> snapshot(city.building(id)?.type?.let { it != BuildingType.TOWN_HALL } == true) }
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
                snapshot((order.blocksRequired == null) == (order.blocksPlaced == null))
                order.blocksRequired?.let { total -> snapshot(total in 0..CivsStore.MAX_PLAN_BLOCKS && order.blocksPlaced!! in 0..total) }
                order.workCredit?.let { credit -> snapshot(order.payment != null && credit in 0..MAX_BUILDER_WORK_CREDIT &&
                    credit <= order.workRequired - order.workDone && (order.phase != ConstructionPhase.CONSTRUCTING || credit == 0)) }
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
            snapshot(city.workers.size in 0..MAX_POPULATION &&
                city.workers.map { it.id }.distinct().size == city.workers.size)
            CivsCorpses.validate(city)
            city.mineProgress?.let { progress -> snapshot(progress.size <= MAX_BUILDINGS && progress.all { (id, step) ->
                java.util.UUID.fromString(id).toString() == id && step in 0..100_000 }) }
            city.minePlans?.let { plans -> snapshot(plans.size <= MAX_BUILDINGS && plans.all { (id, plan) ->
                val buildingId = java.util.UUID.fromString(id)
                val building = city.building(buildingId)
                val size = building?.takeIf { it.type == BuildingType.QUARRY }?.let { CivsMinePlan.forBuilding(it, plan.version) }?.steps?.size
                val v2Size = building?.takeIf { it.type == BuildingType.QUARRY }?.let { CivsMinePlan.forBuilding(it, 2) }?.steps?.size
                val v3Size = building?.takeIf { it.type == BuildingType.QUARRY }?.let { CivsMinePlan.forBuilding(it, 3) }?.steps?.size
                buildingId.toString() == id && size != null && present(plan) &&
                    plan.version in 2..CivsMinePlan.VERSION && plan.nextStep in 0..size.toLong() &&
                    (plan.v2Step == null || plan.version >= 3 && v2Size != null && plan.v2Step in 0..v2Size.toLong()) &&
                    (plan.v3Step == null || plan.version >= 4 && v3Size != null && plan.v3Step in 0..v3Size.toLong()) &&
                    plan.legacyStep == city.mineProgress?.get(id)
            }) }
            val shipments = city.workers.mapNotNull { it.cargo?.id }
            snapshot(shipments.distinct().size == shipments.size)
            city.immigration?.let { snapshot(it.remaining in 0..CivsPopulation.IMMIGRANT_POPULATION && it.nextArrivalAt >= city.createdAt) }
            snapshot(city.workers.all { worker ->
                CivsResidents.validated(worker)
                present(worker.id) && validText(worker.name, 32) && worker.completedCycles >= 0 && worker.nextDeliveryAt >= 0 &&
                    CivsLogistics.validCargo(city, worker.cargo) &&
                    (worker.cargo?.takeIf { it.inventoryBacked }?.let {
                        CivsEquipment.plainCount(worker, it.material) >= it.amount
                    } != false) &&
                    (worker.assignment == null || worker.nextDeliveryAt > 0 && city.building(worker.assignment)?.let {
                        slots(city, it) > 0 || (it.type == BuildingType.TOWN_HALL && isFounding(city))
                    } == true)
            })
            CivsPopulation.validated(city)
            snapshot(city.buildings.all { building ->
                city.workers.count { it.assignment == building.id } <=
                    if (building.type == BuildingType.TOWN_HALL && isFounding(city)) 1 else slots(city, building)
            })
            // Retiring housing/storage does not evict residents or erase stock. New intake still checks current capacity.
            snapshot(city.workers.size <= populationLimit(city))
            snapshot(Resource.entries.all { city.stock[it] in 0..(128L + (MAX_BUILDINGS - 1) * 1024L) })
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
            // Old farms may still store BREAD; their effective selection is now wheat.
            // Preserve the stored value and previously accepted receipts on reload.
            (building.production?.let { it in CivsProduction.options(building.type) ||
                building.type == BuildingType.FARM && it == "BREAD" } != false)

    private fun intersectsApproach(city: Settlement, building: CivBuilding): Boolean = city.pendingControls.orEmpty().any {
        it.buildingId != building.id && it.approach.orEmpty().any { cell ->
            building.bounds.contains(cell.position.x, cell.position.y, cell.position.z) || building.control == cell.position
        }
    }

    private fun validConstructionPrice(order: CivConstruction, building: CivBuilding): Boolean {
        if (building.type in setOf(BuildingType.SAWMILL, BuildingType.BLACKSMITH,
                BuildingType.STONEMASON, BuildingType.LIBRARY) && order.payment == null) return false
        val batches = order.batches
        if (batches == null) return order.payment == null && building.type in setOf(BuildingType.TOWN_HALL,
            BuildingType.WAREHOUSE, BuildingType.HOUSE, BuildingType.FORESTER, BuildingType.QUARRY, BuildingType.FARM) &&
            order.materials == null && order.cost == cost(building.type, building.level) &&
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
        check(city.pendingDeletion == null, "deletion-pending")
        validate(city)
    }

    private fun ownerOnly(city: Settlement, actor: UUID) {
        check(city.owner == actor, "owner-required")
        check(city.pendingDeletion == null, "deletion-pending")
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
