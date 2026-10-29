package ru.arc.civs

import java.util.UUID

/** Pure transitions. The caller persists the returned revision before publishing it or placing blocks. */
object CivsRules {
    const val MAX_BUILDINGS = 16
    const val MAX_DECORATIONS = 8
    const val MAX_AUTONOMOUS_DECORATIONS = 2
    const val MAX_INFRASTRUCTURE = 96
    const val MAX_SITES = MAX_BUILDINGS + MAX_INFRASTRUCTURE + MAX_DECORATIONS
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
    internal val ESSENTIAL_BUILDINGS = setOf(BuildingType.WAREHOUSE, BuildingType.FARM, BuildingType.HOUSE, BuildingType.FORESTER, BuildingType.QUARRY)

    fun cost(type: BuildingType, targetLevel: Int): Stock {
        check(targetLevel in 1..type.maxLevel, "invalid-level")
        if (type == BuildingType.TOWN_HALL) return when (targetLevel) {
            1 -> Stock()
            2 -> Stock(128, 96, 24)
            else -> Stock(384, 288, 72)
        }
        val base = when (type) {
            BuildingType.BUILDER_HUT, BuildingType.SAWMILL, BuildingType.BLACKSMITH,
            BuildingType.STONEMASON, BuildingType.LIBRARY, BuildingType.BAKERY,
            BuildingType.UNIVERSITY, BuildingType.SMELTERY, BuildingType.CRUSHER,
            BuildingType.DECORATION -> Stock() // Native construction reserves exact materials.
            BuildingType.WAREHOUSE -> Stock(32, 16)
            BuildingType.HOUSE -> Stock(24, 12)
            BuildingType.FORESTER -> Stock(20, 12)
            BuildingType.QUARRY -> Stock(20, 20)
            BuildingType.FARM -> Stock(24, 8)
            BuildingType.TAVERN -> Stock(32, 16)
            BuildingType.GUARD_TOWER -> Stock(24, 24)
            BuildingType.WALL -> Stock(8, 16)
            BuildingType.GATE -> Stock(12, 16)
            BuildingType.ROAD -> Stock(0, 8)
            BuildingType.PLAN_BOARD -> Stock(12, 8)
            BuildingType.TOWN_HALL -> error("handled above")
        }
        val multiplier = targetLevel.toLong() * targetLevel
        return Stock(base.wood * multiplier, base.stone * multiplier, base.food * multiplier)
    }

    fun capacity(city: Settlement): Long = if (CivsAutonomousStorage.observed(city))
        CivsAutonomousStorage.citySlots(city) * 64L else 128L + city.buildings.sumOf {
        if (it.phase == BuildingPhase.ACTIVE && it.type == BuildingType.WAREHOUSE) {
            when (it.level) { 1 -> 256L; 2 -> 512L; 3 -> 1024L; else -> 0L }
        } else 0L
    }

    fun beds(city: Settlement): Int = CivsPopulation.IMMIGRANT_POPULATION + city.buildings.sumOf {
        if (it.phase == BuildingPhase.ACTIVE && it.type.isResidence) CivsPopulation.houseBeds(it) else 0
    }

    fun populationLimit(city: Settlement): Int = minOf(MAX_POPULATION, maxOf(CivsPopulation.IMMIGRANT_POPULATION, city.level() * 4))

    fun slots(building: CivBuilding): Int = if (building.type in setOf(BuildingType.UNIVERSITY, BuildingType.SMELTERY, BuildingType.CRUSHER)) {
        if (building.phase == BuildingPhase.ACTIVE) 1 else 0
    } else if (building.type == BuildingType.BUILDER_HUT) {
        if (building.phase == BuildingPhase.ACTIVE) building.level else 1
    }
    else if (building.phase != BuildingPhase.ACTIVE) 0 else when (building.type) {
        BuildingType.TOWN_HALL, BuildingType.WAREHOUSE, BuildingType.FORESTER, BuildingType.QUARRY, BuildingType.FARM,
        BuildingType.SAWMILL, BuildingType.BLACKSMITH, BuildingType.STONEMASON, BuildingType.LIBRARY,
        BuildingType.BAKERY -> building.level
        BuildingType.GUARD_TOWER, BuildingType.TAVERN -> 1
        else -> 0
    }

    /** Old full cities have no spare plot for the newly introduced builder hut. New sites reserve that plot. */
    fun legacyHallBuilder(city: Settlement): Boolean = city.inventory != null && buildingCount(city) == MAX_BUILDINGS &&
        city.buildings.none { it.type == BuildingType.BUILDER_HUT } &&
        city.buildings.any { it.type == BuildingType.TOWN_HALL && it.phase == BuildingPhase.ACTIVE }

    fun slots(city: Settlement, building: CivBuilding): Int =
        if (building.type == BuildingType.UNIVERSITY) {
            if (CivsResearch.university(city)?.id == building.id) slots(building) else 0
        } else if (CivsAutonomy.legacyStock(city)) {
            if (building.type == BuildingType.BLACKSMITH && building.phase == BuildingPhase.ACTIVE) 1
            else if (building.type in setOf(BuildingType.BUILDER_HUT, BuildingType.WAREHOUSE, BuildingType.GUARD_TOWER, BuildingType.TAVERN, BuildingType.SMELTERY,
                    BuildingType.CRUSHER) || resource(building.type) != null) slots(building) else 0
        }
        else if (building.type == BuildingType.WAREHOUSE && city.inventory == null) 0
        else if (building.type == BuildingType.TOWN_HALL && isBuilder(city, building)) {
            if (building.phase == BuildingPhase.ACTIVE) 1 else 0
        } else slots(building)

    /** Old paid orders and full cities keep their hall builder until a normal hut can serve them. */
    fun effectiveWorkType(city: Settlement, building: CivBuilding): BuildingType =
        if (city.autonomy == null && building.type == BuildingType.TOWN_HALL && (city.inventory == null || legacyHallBuilder(city)))
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

    internal fun workplaceAvailable(city: Settlement, building: CivBuilding): Boolean =
        building.phase == BuildingPhase.ACTIVE && (city.construction?.takeIf { it.buildingId == building.id }
            ?.let { it.targetLevel > building.level } ?: true)

    fun productionAvailable(city: Settlement, building: CivBuilding): Boolean =
        workplaceAvailable(city, building) && !isBuilder(city, building) &&
            (if (building.type == BuildingType.UNIVERSITY) CivsResearch.university(city)?.id == building.id &&
                city.research?.project?.let { it.funded && !it.paused } == true
            else CivsProduction.options(building.type).isNotEmpty() || city.autonomy != null && building.type == BuildingType.BLACKSMITH)

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
        BuildingType.BAKERY -> "baker"
        BuildingType.TAVERN -> "innkeeper"
        BuildingType.GUARD_TOWER -> "guard"
        BuildingType.UNIVERSITY -> "researcher"
        BuildingType.SMELTERY -> "smelter"
        BuildingType.CRUSHER -> "crusher"
        else -> "unassigned"
    }

    fun deliveryAmount(building: CivBuilding): Long = when (resource(building.type)) {
        Resource.WOOD -> 4L * building.level
        Resource.STONE, Resource.FOOD -> 3L * building.level
        null -> 0L
    }

    fun profession(city: Settlement, building: CivBuilding): String = profession(effectiveWorkType(city, building))

    fun staffedTavern(city: Settlement, building: CivBuilding): Boolean =
        building.type == BuildingType.TAVERN && workplaceAvailable(city, building) && city.workers.any {
                it.assignment == building.id && !CivsResidents.isChild(it)
            }

    fun deliveryInterval(type: BuildingType): Long = if (type == BuildingType.BUILDER_HUT) BUILDER_INTERVAL else PRODUCTION_INTERVAL

    fun deliveryInterval(city: Settlement, building: CivBuilding): Long =
        if (city.autonomy != null && isBuilder(city, building)) BUILDER_SESSION_INTERVAL
        else deliveryInterval(effectiveWorkType(city, building))

    fun workRequired(type: BuildingType, targetLevel: Int): Int {
        check(targetLevel in 1..type.maxLevel, "invalid-level")
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

    fun immigrationDue(city: Settlement, now: Long): Boolean = city.pendingDeletion == null && city.autonomy?.paused != true &&
        (city.autonomy == null || (city.immigration?.remaining ?: 0) > 0) &&
        city.workers.size < minOf(CivsPopulation.IMMIGRANT_POPULATION, populationLimit(city), beds(city)) &&
        now >= immigrationArrival(city)

    private fun immigrationArrival(city: Settlement): Long = city.immigration?.nextArrivalAt ?:
        if (city.createdAt > Long.MAX_VALUE - FIRST_IMMIGRATION_DELAY) Long.MAX_VALUE else city.createdAt + FIRST_IMMIGRATION_DELAY

    fun immigrate(city: Settlement, actor: UUID, workerId: UUID, name: String, now: Long): Settlement {
        authorized(city, actor)
        check(now >= 0, "invalid-time")
        check(city.autonomy?.paused != true, "autonomy-paused")
        if (city.autonomy != null && (city.immigration?.remaining ?: 0) == 0) return city
        if (city.workers.size >= CivsPopulation.IMMIGRANT_POPULATION) return city
        check(now >= immigrationArrival(city), "immigration-not-ready")
        check(city.workers.none { it.id == workerId }, "duplicate-worker")
        check(validText(name.trim(), 32), "invalid-name")
        check(city.workers.size < populationLimit(city), "population-full")
        check(city.workers.size < beds(city), "no-beds")
        val home = city.buildings.first { it.type == BuildingType.TOWN_HALL }
        return changed(city, city.copy(workers = city.workers + CivWorker(workerId, name.trim(), null,
            sex = CivsPopulation.nextImmigrantSex(city), homeId = home.id, hunger = 20),
            immigration = CivImmigration(if (city.autonomy == null) CivsPopulation.IMMIGRANT_POPULATION - city.workers.size - 1
                else requireNotNull(city.immigration).remaining - 1, add(now, IMMIGRATION_INTERVAL))))
    }

    fun placeSite(city: Settlement, actor: UUID, building: CivBuilding): Settlement {
        authorized(city, actor)
        val site = building.copy(phase = BuildingPhase.SITE)
        city.building(site.id)?.let { check(it == site, "invalid-building"); return city }
        check(site.type != BuildingType.TOWN_HALL && site.level == 1, "invalid-level")
        CivsResearch.required(site.type, site.level)?.let { check(CivsResearch.has(city, it), "research-required") }
        requireBuildingSlot(city, site.type, ESSENTIAL_BUILDINGS + BuildingType.BUILDER_HUT)
        check(!CivsSnapshotValidation.intersectsApproach(city, site), "building-overlap")
        check(CivsSnapshotValidation.validBuilding(city, site) && city.buildings.none { it.bounds.overlaps(site.bounds) } &&
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
        check(building.phase == BuildingPhase.SITE || building.phase == BuildingPhase.ACTIVE &&
            (building.level < building.type.maxLevel || CivsPlanBoardBlueprint.needsRefit(building)), "invalid-building")
        check(city.construction?.buildingId != buildingId, "construction-busy")
        check(building.phase == BuildingPhase.SITE || building.type == BuildingType.TOWN_HALL ||
            building.level < city.level() || CivsPlanBoardBlueprint.needsRefit(building), "town-hall-level-required")
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
            roadRouteOverrides = city.roadRouteOverrides?.minus(buildingId.toString())?.takeIf { it.isNotEmpty() },
            forestryJobs = city.forestryJobs?.minus(buildingId.toString())?.takeIf { it.isNotEmpty() },
            forestrySeeded = city.forestrySeeded?.minus(buildingId.toString())?.takeIf { it.isNotEmpty() },
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

    fun setProduction(city: Settlement, actor: UUID, buildingId: UUID, material: String, now: Long): Settlement {
        authorized(city, actor)
        val building = city.building(buildingId) ?: reject("building-not-found")
        check(material in CivsProduction.options(city, building), "invalid-production")
        check(city.construction?.buildingId != buildingId, "construction-busy")
        if (building.production == material) return city
        return changed(city, city.copy(buildings = city.buildings.map { if (it.id == buildingId) it.copy(production = material) else it },
            workers = city.workers.map { worker -> if (worker.assignment == buildingId) worker.copy(
                nextDeliveryAt = maxOf(add(worker.nextDeliveryAt, 1), add(now, deliveryInterval(city, building)))) else worker }))
    }

    fun assign(city: Settlement, actor: UUID, workerId: UUID, buildingId: UUID?, now: Long): Settlement {
        authorized(city, actor)
        check(now >= 0, "invalid-time")
        val worker = city.workers.find { it.id == workerId } ?: reject("worker-not-found")
        check(buildingId == null || !CivsResidents.isChild(worker), "child-cannot-work")
        if (worker.assignment == buildingId) return city
        val building = buildingId?.let { city.building(it) ?: reject("building-not-found") }
        if (building != null) {
            check(building.type != BuildingType.WAREHOUSE || worker.pendingStock == null, "resident-stock-pending")
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

    /** The native scroll debit belongs to the same durable receipt as this exact death transition. */
    fun resurrect(city: Settlement, actor: UUID, workerId: UUID, diedAt: Long, now: Long,
        administrator: Boolean = false): Settlement {
        check(city.autonomy == null || administrator, "not-authorized")
        authorized(city, if (city.autonomy != null && administrator) city.owner else actor)
        check(now >= 0, "invalid-time")
        val corpse = city.corpses.orEmpty().firstOrNull { it.worker.id == workerId && it.diedAt == diedAt }
            ?: reject("worker-not-found")
        check(now >= diedAt && now < corpse.expiresAt, "corpse-expired")
        check(city.workers.none { it.id == workerId }, "duplicate-worker")
        check(city.workers.size < populationLimit(city), "population-full")
        check(city.workers.size < beds(city), "no-beds")
        return changed(city, city.copy(workers = city.workers + resurrectedWorker(city, corpse),
            corpses = city.corpses.orEmpty().filterNot { it.worker.id == workerId && it.diedAt == diedAt }))
    }

    internal fun resurrectedWorker(city: Settlement, corpse: CivCorpse): CivWorker = corpse.worker.copy(
        assignment = null, cargo = corpse.worker.cargo?.takeIf { it.stockResource != null }, nextDeliveryAt = 0,
        health = CivsResidents.maxHealth(corpse.worker),
        homeId = corpse.worker.homeId?.takeIf { id -> city.buildings.any {
            it.id == id && (it.type == BuildingType.TOWN_HALL || it.type.isResidence)
        } })

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

    fun needsAutoAssignment(city: Settlement, canReassign: (UUID) -> Boolean = { true }): Boolean =
        CivsAutonomy.criticalAssignment(city, canReassign) != null ||
            city.workers.any { it.assignment == null && !CivsResidents.isChild(it) } &&
            city.buildings.any { automaticVacancies(city, it) > 0 }

    private fun automaticVacancies(city: Settlement, building: CivBuilding): Int {
        val capacity = if (building.type == BuildingType.WAREHOUSE) minOf(1, slots(city, building)) else slots(city, building)
        return (capacity - city.workers.count { it.assignment == building.id }).coerceAtLeast(0)
    }

    /** Personal jobs remain assigned; autonomous towns may recover one idle critical worker. */
    fun autoAssign(city: Settlement, actor: UUID, now: Long, canReassign: (UUID) -> Boolean = { true }): Settlement {
        authorized(city, actor)
        check(now >= 0, "invalid-time")
        CivsAutonomy.criticalAssignment(city, canReassign)?.let { (worker, building) ->
            return assign(city, actor, worker, building, now)
        }
        if (!needsAutoAssignment(city, canReassign)) return city
        val vacancies = if (city.autonomy != null) {
            val workplaces = city.buildings.sortedBy { when (it.type) {
                BuildingType.BUILDER_HUT -> 0
                BuildingType.FORESTER -> 1
                BuildingType.QUARRY -> 2
                BuildingType.FARM -> 3
                BuildingType.WAREHOUSE -> 4
                else -> 5
            } }
            fun vacant(building: CivBuilding, range: IntRange): List<CivBuilding> {
                val assigned = city.workers.count { it.assignment == building.id }
                return range.filter { it > assigned && it <= slots(city, building) }.map { building }
            }
            // Staff producers and their courier before giving any profession another resident.
            workplaces.flatMap { vacant(it, 1..1) } +
                workplaces.filter { resource(it.type) != null }.flatMap { vacant(it, 2..3) } +
                workplaces.filter { it.type == BuildingType.BUILDER_HUT }.flatMap { vacant(it, 2..3) }
        } else city.buildings.sortedBy { when {
            isBuilder(city, it) -> 0
            it.type == BuildingType.WAREHOUSE -> 1
            it.type == BuildingType.TOWN_HALL -> 3
            else -> 2
        } }.flatMap { building -> List(automaticVacancies(city, building)) { building } }
        val available = vacancies.toMutableList()
        val workers = city.workers.map { worker ->
            val index = available.indexOfFirst { worker.pendingStock == null || it.type != BuildingType.WAREHOUSE }
            if (CivsResidents.isChild(worker) || worker.assignment != null || index < 0) worker else {
                val building = available.removeAt(index)
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

    fun reserveBuild(city: Settlement, actor: UUID, building: CivBuilding, order: CivConstruction): Settlement =
        CivsConstructionRules.reserveBuild(city, actor, building, order)
    internal fun workRouteWithinReach(city: Settlement, entrance: CivPosition): Boolean =
        CivsConstructionRules.workRouteWithinReach(city, entrance)
    fun beginPlacement(city: Settlement, actor: UUID, orderId: UUID): Settlement = CivsConstructionRules.beginPlacement(city, actor, orderId)
    fun payBatch(city: Settlement, actor: UUID, orderId: UUID, batch: Int, materials: Map<String, Long>): Settlement =
        CivsConstructionRules.payBatch(city, actor, orderId, batch, materials)
    fun awaitMaterials(city: Settlement, actor: UUID, orderId: UUID, batch: Int, materials: Map<String, Long>): Settlement =
        CivsConstructionRules.awaitMaterials(city, actor, orderId, batch, materials)
    fun pauseConstruction(city: Settlement, actor: UUID, orderId: UUID, failure: String): Settlement = CivsConstructionRules.pauseConstruction(city, actor, orderId, failure)
    fun retryConstruction(city: Settlement, actor: UUID, orderId: UUID): Settlement = CivsConstructionRules.retryConstruction(city, actor, orderId)
    fun completeConstruction(city: Settlement, actor: UUID, orderId: UUID): Settlement = CivsConstructionRules.completeConstruction(city, actor, orderId)
    fun completeBatch(city: Settlement, actor: UUID, orderId: UUID): Settlement = CivsConstructionRules.completeBatch(city, actor, orderId)

    /** Called only for an observed completed worker trip; elapsed time alone never earns resources. */
    fun deliver(
        city: Settlement, actor: UUID, workerId: UUID, assignmentId: UUID,
        expectedCompletedCycles: Long, expectedNextDeliveryAt: Long, now: Long, outputHandled: Boolean = false,
        craftJob: CivCraftJob? = null, equipmentAfter: List<CivResidentItem?>? = null,
    ): Settlement {
        authorized(city, actor)
        check(city.autonomy?.paused != true, "autonomy-paused")
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
        var pendingStock = worker.pendingStock
        var construction = city.construction
        if (isBuilder(city, building)) {
            val current = construction ?: reject("no-construction")
            check(builderCanWork(city, building), "invalid-assignment")
            if (current.payment != null || city.autonomy != null && current.batches != null) {
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
            check(!CivsAutonomy.legacyStock(city) || building.type != BuildingType.FORESTER, "stale-worksite")
            if (city.inventory == null) {
                val resource = resource(building.type) ?: reject("invalid-assignment")
                val amount = deliveryAmount(building)
                check(!outputHandled, "invalid-delivery")
                if (city.autonomy != null) {
                    val produced = CivsAutonomousLogistics.produce(city, worker, resource, amount)
                    stock = produced.first
                    pendingStock = produced.second
                } else {
                    check(capacity(city) - stock[resource] >= amount, "storage-full")
                    stock = stock.with(resource, add(stock[resource], amount))
                }
            } else {
                check(outputHandled, "materials-required")
                check(CivsEquipment.inputsReady(worker, productionInputs, emptyMap()), "materials-missing")
            }
        }
        val interval = CivsResidents.workDuration(worker,
            if (isBuilder(city, building) && (construction?.payment != null || city.autonomy != null && construction?.batches != null))
                BUILDER_SESSION_INTERVAL else deliveryInterval(city, building))
        val equipped = equipmentAfter?.let { CivsEquipment.replace(worker, it) } ?: worker
        val supplied = if (!isBuilder(city, building) && city.inventory != null && outputHandled)
            CivsEquipment.consumeInputs(equipped, productionInputs) else equipped
        val updated = CivsResidents.trained(supplied.copy(pendingStock = pendingStock, completedCycles = add(worker.completedCycles, 1),
            nextDeliveryAt = add(now, interval)), effectiveWorkType(city, building), city)
        return changed(city, city.copy(stock = stock, construction = construction,
            workers = city.workers.map { if (it.id == workerId) updated else it }))
    }

    internal fun finishWorldWork(city: Settlement, work: CivWorldWork, now: Long): Settlement {
        val worker = city.workers.firstOrNull { it.id == work.workerId } ?: reject("worker-not-found")
        check(!CivsResidents.isChild(worker), "child-cannot-work")
        val building = city.building(work.buildingId) ?: reject("building-not-found")
        check(worker.assignment == building.id && worker.completedCycles == work.cycle && worker.cargo == null, "stale-delivery")
        check(productionAvailable(city, building) && building.type in CivsWorksites.TYPES, "invalid-assignment")
        val forestry = building.type == BuildingType.FORESTER && (work.forestry != null || work.changes.isNotEmpty())
        val autonomousForestry = CivsAutonomy.legacyStock(city) && forestry
        check(!CivsAutonomy.legacyStock(city) || autonomousForestry && city.autonomy?.paused == false, "invalid-assignment")
        if (forestry) try { CivsForestry.validateWork(city, work) } catch (_: IllegalArgumentException) { reject("stale-worksite") }
        val equipped = work.equipmentAfter?.let { CivsEquipment.replace(worker, it) } ?: worker
        val supplied = if (autonomousForestry || work.equipmentAfter != null && work.changes.isEmpty()) equipped else CivsEquipment.consumeInputs(equipped, work.inputs)
        val produced = if (work.outputs.isNotEmpty())
            CivsEquipment.collectSupplies(supplied, work.outputs) else supplied
        val progressed = produced.copy(completedCycles = add(worker.completedCycles, 1), nextDeliveryAt = add(now, 1_000))
        val forestFinished = work.forestry?.let { it.next == it.job.cells.size } == true
        val updated = if (work.changes.isEmpty() || work.cropCare || work.forestry != null && !forestFinished) progressed else CivsResidents.trained(progressed, building.type, city)
        val forestryJobs = if (work.forestry != null) work.forestry.let { progress ->
            city.forestryJobs.orEmpty().toMutableMap().also { jobs ->
                val next = CivsForestry.after(progress)
                if (next == null) jobs.remove(building.id.toString()) else jobs[building.id.toString()] = next
            }.takeIf { it.isNotEmpty() }
        } else city.forestryJobs
        var stock = if (autonomousForestry) CivsForestry.autoStock(city, work) else city.stock
        var completed = updated
        if (autonomousForestry) {
            val delta = stock.wood - city.stock.wood
            if (delta >= 0) {
                val producedStock = CivsAutonomousLogistics.produce(city, updated, Resource.WOOD, delta)
                stock = producedStock.first
                completed = updated.copy(pendingStock = producedStock.second)
            }
        }
        val forestrySeeded = if (autonomousForestry && !CivsForestry.care(work) &&
            (work.forestry == null || forestFinished || !work.forestry.job.seedDeferred))
            city.forestrySeeded.orEmpty() + building.id.toString() else city.forestrySeeded
        val (progress, plans) = miningProgressAfter(city, building, work)
        return changed(city, city.copy(workers = city.workers.map { if (it.id == worker.id) completed else it },
            mineProgress = progress, minePlans = plans, stock = stock, forestryJobs = forestryJobs, forestrySeeded = forestrySeeded))
    }

    internal fun finishAutonomousVisual(city: Settlement, work: CivWorldWork): Settlement {
        CivsAutonomousWorksites.validate(city, work)
        val building = city.building(work.buildingId) ?: reject("building-not-found")
        val (progress, plans) = miningProgressAfter(city, building, work)
        return validate(city.copy(mineProgress = progress, minePlans = plans, revision = add(city.revision, 1)))
    }

    private fun miningProgressAfter(city: Settlement, building: CivBuilding, work: CivWorldWork):
        Pair<Map<String, Long>?, Map<String, CivMinePlanState>?> {
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
            city.minePlans.orEmpty() + (key to CivsMineProgress.after(building, previous, city.mineProgress?.get(key), work))
        } ?: city.minePlans
        return progress to plans
    }

    /** Fail closed at the storage boundary, including missing/null fields from reflection-based JSON decoders. */
    fun validate(city: Settlement): Settlement = CivsSnapshotValidation.validate(city)

    fun buildingCount(city: Settlement): Int = city.buildings.count { !it.type.isInfrastructure && it.type != BuildingType.DECORATION }
    fun decorationLimit(city: Settlement): Int = if (city.autonomy == null) MAX_DECORATIONS else MAX_AUTONOMOUS_DECORATIONS

    internal fun requireBuildingSlot(city: Settlement, type: BuildingType, essential: Set<BuildingType>) {
        if (type == BuildingType.DECORATION) {
            check(city.buildings.count { it.type == BuildingType.DECORATION } < decorationLimit(city), "building-limit")
        } else if (type.isInfrastructure) {
            check(city.buildings.count { it.type.isInfrastructure } < MAX_INFRASTRUCTURE, "building-limit")
        } else {
            val count = buildingCount(city)
            check(count < MAX_BUILDINGS, "building-limit")
            val missing = essential.count { it != type && city.buildings.none { building -> building.type == it } }
            check(MAX_BUILDINGS - count - 1 >= missing, "resources.bootstrap-slots")
        }
    }

    internal fun isFounding(city: Settlement): Boolean = city.buildings.size == 1 && city.buildings.single().let {
        it.type == BuildingType.TOWN_HALL && it.level == 1 && it.phase == BuildingPhase.CONSTRUCTING && city.construction?.buildingId == it.id
    }

    internal fun validText(text: String, max: Int): Boolean = text.isNotBlank() && text == text.trim() && text.length <= max && text.none { it.isISOControl() }
    internal fun present(vararg values: Any?): Boolean = values.all { it != null }

    internal fun order(city: Settlement, id: UUID): CivConstruction {
        val order = city.construction ?: reject("no-construction")
        check(order.id == id, "stale-construction")
        return order
    }

    internal fun authorized(city: Settlement, actor: UUID) {
        check(city.canManage(actor), "not-authorized")
        check(city.pendingDeletion == null, "deletion-pending")
        validate(city)
    }

    private fun ownerOnly(city: Settlement, actor: UUID) {
        check(city.owner == actor, "owner-required")
        check(city.pendingDeletion == null, "deletion-pending")
        validate(city)
    }

    internal fun changed(before: Settlement, after: Settlement): Settlement =
        if (before == after) before else validate(after.copy(revision = add(before.revision, 1)))

    private fun add(left: Long, right: Long): Long {
        check(left >= 0 && right >= 0 && left <= Long.MAX_VALUE - right, "arithmetic-overflow")
        return left + right
    }

    internal fun check(condition: Boolean, reason: String) { if (!condition) reject(reason) }
    internal fun reject(reason: String): Nothing = throw CivsRejection(reason)
}
