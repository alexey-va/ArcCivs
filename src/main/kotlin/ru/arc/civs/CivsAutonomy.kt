package ru.arc.civs

import java.util.UUID

/** Only active minutes advance recruitment; pausing or restarting never catches up offline time. */
data class CivAutonomy(val paused: Boolean = false, val recruitmentMinutes: Int = 0,
    val layoutHalfWidth: Int? = null,
    /** Closed abstract city products; these quantities never become native inventory items. */
    val products: Map<CivIndustryProduct, Long>? = null,
    /** Adopted geometry only; unpaid plots are not sites, stock, workers or construction receipts. */
    val plan: CivSettlementPlan? = null)

data class CivAutonomyGoal(
    val type: BuildingType,
    val targetLevel: Int,
    val buildingId: UUID?,
    val cost: Stock,
    val missing: Stock,
) {
    val ready: Boolean get() = missing == Stock()
}

/** Abstract village stock pays complete buildings once; it never becomes transferable vanilla items. */
object CivsAutonomy {
    const val RECRUITMENT_MINUTES = 2
    val baseProfile = listOf(BuildingType.BUILDER_HUT, BuildingType.FORESTER, BuildingType.QUARRY,
        BuildingType.FARM, BuildingType.WAREHOUSE, BuildingType.TOWN_HALL, BuildingType.HOUSE, BuildingType.PLAN_BOARD,
        BuildingType.TAVERN, BuildingType.GUARD_TOWER)
    private val industryProfile = listOf(BuildingType.UNIVERSITY, BuildingType.SMELTERY, BuildingType.CRUSHER, BuildingType.BLACKSMITH)
    val profile = baseProfile + industryProfile
    val completeProfile = baseProfile + List(2) { BuildingType.HOUSE } + industryProfile
    val allowedTypes = profile.toSet() + setOf(BuildingType.WALL, BuildingType.GATE, BuildingType.ROAD, BuildingType.PLAN_BOARD)
    private val upgrades = listOf(BuildingType.FORESTER, BuildingType.QUARRY, BuildingType.FARM,
        BuildingType.WAREHOUSE, BuildingType.HOUSE, BuildingType.BUILDER_HUT, BuildingType.TAVERN, BuildingType.GUARD_TOWER)

    fun cost(type: BuildingType, targetLevel: Int): Stock {
        if (type !in allowedTypes || targetLevel !in 1..type.maxLevel) throw CivsRejection("invalid-building")
        if (type == BuildingType.TOWN_HALL && targetLevel == 1) return Stock(32, 24, 4)
        val industry = when (type) {
            BuildingType.UNIVERSITY -> Stock(48, 32, 8)
            BuildingType.SMELTERY, BuildingType.BLACKSMITH -> Stock(32, 24)
            BuildingType.CRUSHER -> Stock(24, 24)
            else -> null
        }
        if (industry != null) {
            val multiplier = targetLevel.toLong() * targetLevel
            return Stock(industry.wood * multiplier, industry.stone * multiplier, industry.food * multiplier)
        }
        val regular = CivsRules.cost(type, targetLevel)
        return if (type == BuildingType.BUILDER_HUT) {
            val multiplier = targetLevel.toLong() * targetLevel
            Stock(16 * multiplier, 8 * multiplier)
        } else regular
    }

    fun found(
        id: UUID, owner: UUID, name: String, worldId: UUID, center: CivPosition,
        radius: Int, style: String, townHall: CivBuilding, now: Long,
    ): Settlement = CivsRules.validate(CivsRules.found(id, owner, name, worldId, center, radius, style, townHall, now)
        .copy(stock = CivsRules.STARTING_STOCK, inventory = null, autonomy = CivAutonomy()))

    /** Finish accepted sites before adding another; the hall waits for a real hut and sustainable producers. */
    fun nextGoal(city: Settlement): CivAutonomyGoal? {
        if (city.autonomy?.paused != false || city.pendingDeletion != null || city.construction != null) return null
        fun goal(building: CivBuilding) = goal(city, building.type,
            if (building.phase == BuildingPhase.SITE) 1 else if (CivsPlanBoardBlueprint.needsRefit(building)) building.level
                else building.level + 1, building.id)
        val hut = city.buildings.firstOrNull { it.type == BuildingType.BUILDER_HUT }
        if (hut == null) return goal(city, BuildingType.BUILDER_HUT, 1, null)
        if (hut.phase == BuildingPhase.SITE) return goal(hut)
        val hallReady = baseProfile.takeWhile { it != BuildingType.TOWN_HALL }.all { type ->
            city.buildings.any { it.type == type && it.phase == BuildingPhase.ACTIVE }
        }
        city.constructionQueue.orEmpty().mapNotNull(city::building).firstOrNull {
            it.type != BuildingType.TOWN_HALL || it.phase != BuildingPhase.SITE || hallReady
        }?.let { return goal(it) }
        city.buildings.filter { it.phase == BuildingPhase.SITE && it.type != BuildingType.TOWN_HALL }
            .minByOrNull { profile.indexOf(it.type) }?.let { return goal(it) }
        city.buildings.firstOrNull(CivsPlanBoardBlueprint::needsRefit)?.let {
            return goal(city, BuildingType.PLAN_BOARD, it.level, it.id)
        }
        for (type in baseProfile) {
            val existing = city.buildings.firstOrNull { it.type == type }
            if (existing == null) {
                // An established full village must not lose upgrades to a newly added amenity.
                if (type == BuildingType.PLAN_BOARD && CivsRules.buildingCount(city) >= CivsRules.MAX_BUILDINGS) continue
                return goal(city, type, 1, null)
            }
            if (existing.phase == BuildingPhase.SITE) return goal(existing)
        }
        if (city.buildings.count { it.type == BuildingType.HOUSE } < 3 && CivsRules.buildingCount(city) < CivsRules.MAX_BUILDINGS)
            return goal(city, BuildingType.HOUSE, 1, null)
        val hall = city.buildings.first { it.type == BuildingType.TOWN_HALL }
        if (hall.level < 2) return goal(city, BuildingType.TOWN_HALL, 2, hall.id)
        for (type in upgrades) {
            val building = city.buildings.firstOrNull { it.type == type && it.level < 2 } ?: continue
            return goal(city, type, 2, building.id)
        }
        for (type in industryProfile) {
            if (type == BuildingType.BLACKSMITH && !CivsResearch.has(city, CivResearchId.METALLURGY)) continue
            if (CivsResearch.required(type, 1)?.let { !CivsResearch.has(city, it) } == true) continue
            val building = city.buildings.firstOrNull { it.type == type }
            if (building == null) return goal(city, type, 1, null)
            if (building.phase == BuildingPhase.SITE) return goal(building)
        }
        if (CivsResearch.has(city, CivResearchId.MATERIALS)) {
            for (type in listOf(BuildingType.UNIVERSITY, BuildingType.SMELTERY, BuildingType.CRUSHER)) {
                val building = city.buildings.firstOrNull { it.type == type && it.level < 2 } ?: continue
                return goal(city, type, 2, building.id)
            }
        }
        if (CivsResearch.has(city, CivResearchId.ENGINEERING)) {
            if (hall.level < 3) return goal(city, BuildingType.TOWN_HALL, 3, hall.id)
            for (type in listOf(BuildingType.UNIVERSITY, BuildingType.SMELTERY, BuildingType.CRUSHER)) {
                val building = city.buildings.firstOrNull { it.type == type && it.level < 3 } ?: continue
                return goal(city, type, 3, building.id)
            }
        }
        return null
    }

    /** Read-only UI projection of the next goal after the current accepted construction finishes. */
    fun nextGoalAfterCurrent(city: Settlement): CivAutonomyGoal? {
        val autonomy = city.autonomy ?: return null
        if (city.pendingDeletion != null) return null
        val order = city.construction
        var projected = city.copy(autonomy = autonomy.copy(paused = false))
        if (order != null) {
            val building = (order.plannedBuilding ?: city.building(order.buildingId)) ?: return null
            val completed = building.copy(level = order.targetLevel, phase = BuildingPhase.ACTIVE)
            projected = projected.copy(construction = null,
                buildings = city.buildings.map { if (it.id == completed.id) completed else it }.let {
                    if (it.any { building -> building.id == completed.id }) it else it + completed
                },
                constructionQueue = city.constructionQueue?.filterNot { it == completed.id })
        }
        return nextGoal(projected)
    }

    internal fun goal(city: Settlement, type: BuildingType, level: Int, id: UUID?): CivAutonomyGoal {
        val price = if (id?.let(city::building)?.let(CivsPlanBoardBlueprint::needsRefit) == true &&
            type == BuildingType.PLAN_BOARD && level == 1) Stock() else cost(type, level)
        val missing = Stock((price.wood - city.stock.wood).coerceAtLeast(0),
            (price.stone - city.stock.stone).coerceAtLeast(0), (price.food - city.stock.food).coerceAtLeast(0))
        return CivAutonomyGoal(type, level, id, price, missing)
    }

    internal fun infrastructureDue(city: Settlement, ordinary: CivAutonomyGoal?): Boolean {
        val adopted = CivsSettlementPlans.current(city)
        return city.buildings.count { it.type.isInfrastructure } < CivsRules.MAX_INFRASTRUCTURE &&
            (ordinary == null || ordinary.targetLevel > 1 || adopted != null &&
                city.buildings.lastOrNull()?.type?.isInfrastructure != true) &&
            (if (adopted == null) baseProfile.filterNot { it == BuildingType.PLAN_BOARD }
                else baseProfile.take(6)).all { type ->
                city.buildings.any { it.type == type && it.phase == BuildingPhase.ACTIVE }
            }
    }

    /** Recover a missing critical profession after deaths, without interrupting accepted work. */
    internal fun criticalAssignment(city: Settlement, canReassign: (UUID) -> Boolean): Pair<UUID, UUID>? {
        if (city.autonomy?.paused != false || city.pendingDeletion != null) return null
        fun unstaffed(building: CivBuilding) = CivsRules.slots(city, building) > 0 &&
            city.workers.none { it.assignment == building.id }
        val builder = city.construction?.takeIf { CivsRules.constructionWorkers(city, it.buildingId).isEmpty() }?.let {
            city.buildings.firstOrNull { CivsRules.isBuilder(city, it) && CivsRules.builderCanWork(city, it) && unstaffed(it) }
        }
        val hungryFarm = city.buildings.firstOrNull { it.type == BuildingType.FARM && city.stock.food < CivsRules.RECRUIT_FOOD &&
            CivsRules.productionAvailable(city, it) && unstaffed(it) }
        // Existing food/builder recovery may borrow the last raw producer. Industry keeps one of every raw job.
        val urgent = builder ?: hungryFarm
        val industry = CivsIndustry.desiredBuilding(city)
        val missingProducer = industry?.let { listOf(BuildingType.FARM, BuildingType.FORESTER, BuildingType.QUARRY).firstNotNullOfOrNull { type ->
            city.buildings.firstOrNull { it.type == type && CivsRules.productionAvailable(city, it) && unstaffed(it) }
        } }
        val warehouse = city.buildings.firstOrNull { it.type == BuildingType.WAREHOUSE &&
            it.phase == BuildingPhase.ACTIVE && it.control != null && unstaffed(it) }?.takeIf {
            city.workers.none { worker -> worker.assignment == null && !CivsResidents.isChild(worker) && worker.cargo == null }
        }
        val target = urgent ?: warehouse ?: missingProducer ?: industry?.takeIf(::unstaffed)
        target ?: return null
        val worker = city.workers.sortedBy { it.assignment != null }.firstOrNull { worker ->
            !CivsResidents.isChild(worker) && worker.cargo == null && worker.pendingStock == null && worker.hasCollectedItems != true &&
                worker.assignment?.toString() !in city.forestryJobs.orEmpty() &&
                !(city.construction != null && worker.assignment?.let(city::building)?.let { CivsRules.isBuilder(city, it) } == true) &&
                dispensable(city, worker, preserveRaw = urgent == null) && canReassign(worker.id)
        } ?: return null
        return worker.id to target.id
    }

    private fun dispensable(city: Settlement, worker: CivWorker, preserveRaw: Boolean = true): Boolean {
        val assigned = worker.assignment?.let(city::building) ?: return true
        if ((assigned.type == BuildingType.FARM || preserveRaw && assigned.type in setOf(BuildingType.FORESTER, BuildingType.QUARRY)) &&
            city.workers.firstOrNull { it.assignment?.let(city::building)?.type == assigned.type }?.id == worker.id) return false
        if (CivsRules.isBuilder(city, assigned) && (city.construction != null || city.constructionQueue.orEmpty().isNotEmpty())) return false
        if (assigned.type == BuildingType.WAREHOUSE && city.workers.none { it.id != worker.id &&
                it.assignment?.let(city::building)?.type == BuildingType.WAREHOUSE }) return false
        return true
    }

    /** Finish an accepted trip first; the next cycle exposes the ordinary safe-idle reassignment seam. */
    internal fun waitingReassignment(city: Settlement, worker: CivWorker): Boolean {
        if (city.autonomy?.paused != false || city.pendingDeletion != null || !dispensable(city, worker) || worker.cargo != null || worker.pendingStock != null ||
            worker.hasCollectedItems == true || worker.assignment?.toString() in city.forestryJobs.orEmpty()) return false
        val target = CivsIndustry.desiredBuilding(city)?.takeIf { building -> city.workers.none { it.assignment == building.id } } ?: return false
        if (worker.assignment == target.id || city.workers.any { !CivsResidents.isChild(it) && it.assignment == null && it.cargo == null }) return false
        return worker.assignment?.let(city::building)?.type in setOf(BuildingType.FARM, BuildingType.FORESTER, BuildingType.QUARRY,
            BuildingType.BUILDER_HUT, BuildingType.GUARD_TOWER, BuildingType.UNIVERSITY, BuildingType.SMELTERY, BuildingType.CRUSHER, BuildingType.BLACKSMITH)
    }

    fun setPaused(city: Settlement, paused: Boolean): Settlement {
        CivsRules.validate(city)
        val autonomy = city.autonomy ?: throw CivsRejection("invalid-state")
        return changed(city, city.copy(autonomy = autonomy.copy(paused = paused)))
    }

    /** Called after population's one-minute hunger/age step, never from wall-clock elapsed time. */
    fun activeMinute(
        city: Settlement,
        nextName: (Settlement, UUID, CivSex) -> String = CivsNames::name,
        nextId: () -> UUID = UUID::randomUUID,
    ): Settlement {
        CivsRules.validate(city)
        val autonomy = city.autonomy ?: return city
        if (autonomy.paused || city.pendingDeletion != null) return city
        var stock = city.stock
        val residents = city.workers.map { worker ->
            if (CivsResidents.hunger(worker) <= 14 && stock.food > 0) {
                stock = stock.copy(food = stock.food - 1)
                worker.copy(hunger = minOf(20, CivsResidents.hunger(worker) + 6))
            } else worker
        }
        var next = city.copy(stock = stock, workers = residents)
        val initialAdmissionsPending = (city.immigration?.remaining ?: 0) > 0
        val room = residents.size < minOf(CivsRules.populationLimit(city), CivsRules.beds(city))
        var minutes = if (!initialAdmissionsPending && room) minOf(RECRUITMENT_MINUTES, autonomy.recruitmentMinutes + 1) else 0
        if (minutes == RECRUITMENT_MINUTES && stock.food >= CivsRules.RECRUIT_FOOD) {
            val home = city.buildings.filter { it.type == BuildingType.TOWN_HALL ||
                it.type.isResidence && it.phase == BuildingPhase.ACTIVE }.firstOrNull { building ->
                residents.count { it.homeId == building.id } < if (building.type == BuildingType.TOWN_HALL)
                    CivsPopulation.IMMIGRANT_POPULATION else CivsPopulation.houseBeds(building)
            }
            if (home != null) {
                val id = nextId()
                if (residents.any { it.id == id }) throw CivsRejection("duplicate-worker")
                val sex = CivsPopulation.nextImmigrantSex(next)
                next = next.copy(stock = stock.copy(food = stock.food - CivsRules.RECRUIT_FOOD),
                    workers = residents + CivWorker(id, nextName(next, id, sex).trim(), null,
                        sex = sex, homeId = home.id, hunger = 20))
                minutes = 0
            }
        }
        return changed(city, next.copy(autonomy = autonomy.copy(recruitmentMinutes = minutes)))
    }

    private fun changed(before: Settlement, after: Settlement): Settlement {
        if (before == after) return before
        if (before.revision == Long.MAX_VALUE) throw CivsRejection("arithmetic-overflow")
        return CivsRules.validate(after.copy(revision = before.revision + 1))
    }
}
