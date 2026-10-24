package ru.arc.civs

import java.util.UUID

/** Only active minutes advance recruitment; pausing or restarting never catches up offline time. */
data class CivAutonomy(val paused: Boolean = false, val recruitmentMinutes: Int = 0,
    val layoutHalfWidth: Int? = null)

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
    val profile = listOf(BuildingType.BUILDER_HUT, BuildingType.FORESTER, BuildingType.QUARRY,
        BuildingType.FARM, BuildingType.WAREHOUSE, BuildingType.TOWN_HALL, BuildingType.HOUSE,
        BuildingType.TAVERN, BuildingType.GUARD_TOWER)
    val allowedTypes = profile.toSet() + setOf(BuildingType.WALL, BuildingType.GATE, BuildingType.ROAD)
    private val upgrades = listOf(BuildingType.FORESTER, BuildingType.QUARRY, BuildingType.FARM,
        BuildingType.WAREHOUSE, BuildingType.HOUSE, BuildingType.BUILDER_HUT, BuildingType.TAVERN, BuildingType.GUARD_TOWER)

    fun cost(type: BuildingType, targetLevel: Int): Stock {
        if (type !in allowedTypes || targetLevel !in 1..type.maxLevel) throw CivsRejection("invalid-building")
        if (type == BuildingType.TOWN_HALL && targetLevel == 1) return Stock(32, 24, 4)
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
            if (building.phase == BuildingPhase.SITE) 1 else building.level + 1, building.id)
        val hut = city.buildings.firstOrNull { it.type == BuildingType.BUILDER_HUT }
        if (hut == null) return goal(city, BuildingType.BUILDER_HUT, 1, null)
        if (hut.phase == BuildingPhase.SITE) return goal(hut)
        val hallReady = profile.takeWhile { it != BuildingType.TOWN_HALL }.all { type ->
            city.buildings.any { it.type == type && it.phase == BuildingPhase.ACTIVE }
        }
        city.constructionQueue.orEmpty().mapNotNull(city::building).firstOrNull {
            it.type != BuildingType.TOWN_HALL || it.phase != BuildingPhase.SITE || hallReady
        }?.let { return goal(it) }
        city.buildings.filter { it.phase == BuildingPhase.SITE && it.type != BuildingType.TOWN_HALL }
            .minByOrNull { profile.indexOf(it.type) }?.let { return goal(it) }
        for (type in profile) {
            val existing = city.buildings.firstOrNull { it.type == type }
            if (existing == null) return goal(city, type, 1, null)
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
        return null
    }

    internal fun goal(city: Settlement, type: BuildingType, level: Int, id: UUID?): CivAutonomyGoal {
        val price = cost(type, level)
        val missing = Stock((price.wood - city.stock.wood).coerceAtLeast(0),
            (price.stone - city.stock.stone).coerceAtLeast(0), (price.food - city.stock.food).coerceAtLeast(0))
        return CivAutonomyGoal(type, level, id, price, missing)
    }

    internal fun infrastructureDue(city: Settlement, ordinary: CivAutonomyGoal?): Boolean =
        city.buildings.count { it.type.isInfrastructure } < CivsRules.MAX_INFRASTRUCTURE &&
            (ordinary == null || ordinary.targetLevel > 1) && profile.all { type ->
                city.buildings.any { it.type == type && it.phase == BuildingPhase.ACTIVE }
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
