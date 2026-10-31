package ru.arc.civs

import java.util.UUID

/** Pure active-time population changes. The service owns admission, cadence and the durable city commit. */
object CivsPopulation {
    const val IMMIGRANT_POPULATION = 5
    const val BIRTH_MINUTES = 20
    const val ADULT_MINUTES = 180
    const val NURSERY_PLACES = 4

    fun houseBeds(house: CivBuilding): Int = if (house.type == BuildingType.TAVERN) 4 else maxOf(3, house.level * 2)

    fun nextImmigrantSex(city: Settlement): CivSex {
        val adults = city.workers.filterNot(CivsResidents::isChild)
        return if (adults.count { CivsResidents.sex(it) == CivSex.MALE } <=
            adults.count { CivsResidents.sex(it) == CivSex.FEMALE }) CivSex.MALE else CivSex.FEMALE
    }

    fun related(first: CivWorker, second: CivWorker): Boolean =
        first.id == second.id || first.id in second.parentIds.orEmpty() || second.id in first.parentIds.orEmpty() ||
            first.parentIds.orEmpty().any { it in second.parentIds.orEmpty() }

    /** Exactly one active minute, with at most one birth. No timestamps or offline catch-up enter this API. */
    fun advance(
        city: Settlement,
        activeWorkers: Set<UUID>,
        nextName: (Settlement, UUID, CivSex) -> String,
        nextId: () -> UUID = UUID::randomUUID,
    ): Settlement {
        CivsRules.validate(city)
        if (city.pendingDeletion != null) return city
        val homes = city.buildings.filter { it.type == BuildingType.TOWN_HALL || it.type.isResidence }
            .associateBy { it.id }
        val houses = homes.values.filter { it.type == BuildingType.HOUSE && it.phase == BuildingPhase.ACTIVE }
        val hall = homes.values.first { it.type == BuildingType.TOWN_HALL }
        var adultPlaces = CivsRules.populationLimit(city) - city.workers.count { !CivsResidents.isChild(it) }
        val residents = city.workers.map { before ->
            val awake = before.id in activeWorkers
            val resident = CivsResidents.activeMinute(before, awake).let { observed ->
                if (!awake || !CivsResidents.isChild(observed)) observed
                else if (requireNotNull(observed.childMinutes) + 1 == ADULT_MINUTES && adultPlaces > 0) {
                    adultPlaces--
                    CivsResidentLife.adult(city, observed)
                } else observed.copy(childMinutes = minOf(ADULT_MINUTES - 1, requireNotNull(observed.childMinutes) + 1))
            }
            resident.copy(sex = CivsResidents.sex(resident), homeId = resident.homeId?.takeIf { it in homes },
                hunger = CivsResidents.hunger(resident))
        }.toMutableList()
        val families = city.families.orEmpty().toMutableList()
        val paired = families.flatMapTo(hashSetOf()) { listOf(it.firstParentId, it.secondParentId) }

        // A family keeps its home. Other adults, including grown children, may form a pair in
        // another house only when two adults plus at least one child fit there.
        for (house in houses.filter { house -> families.none { it.homeId == house.id } }) {
            val candidates = residents.filter { !CivsResidents.isChild(it) && it.id !in paired }
                .sortedBy { when (it.homeId) { house.id -> 0; null, hall.id -> 1; else -> 2 } }
            var pair: Pair<CivWorker, CivWorker>? = null
            for ((index, first) in candidates.withIndex()) {
                val second = candidates.drop(index + 1).firstOrNull { other ->
                    CivsResidents.sex(first) != CivsResidents.sex(other) && !related(first, other) &&
                        residents.count { it.homeId == house.id && it.id != first.id && it.id != other.id } + 2 < houseBeds(house)
                }
                if (second != null) { pair = first to second; break }
            }
            val parents = pair ?: continue
            val ids = setOf(parents.first.id, parents.second.id)
            residents.indices.filter { residents[it].id in ids }.forEach { index ->
                residents[index] = residents[index].copy(homeId = house.id)
            }
            paired += ids
            families += CivFamily(parents.first.id, parents.second.id, house.id, 0)
        }

        // Reserve a child's place without separating an existing young family.
        // One move per city minute is enough; the normal allocator keeps this bed free.
        val youngParents = residents.filter(CivsResidents::isChild).flatMapTo(hashSetOf()) { it.parentIds.orEmpty() }
        val communalHomes = homes.values.filter { it.phase == BuildingPhase.ACTIVE &&
            it.type in setOf(BuildingType.TAVERN, BuildingType.TOWN_HALL) }
            .sortedBy { if (it.type == BuildingType.TAVERN) 0 else 1 }
        for (family in families) {
            val home = houses.firstOrNull { it.id == family.homeId } ?: continue
            if (residents.count { it.homeId == home.id } < houseBeds(home)) continue
            val single = residents.indexOfFirst { it.homeId == home.id && !CivsResidents.isChild(it) &&
                it.id !in paired && it.id !in youngParents }
            if (single < 0) continue
            val destination = communalHomes.firstOrNull { communal -> residents.count { it.homeId == communal.id } <
                if (communal.type == BuildingType.TOWN_HALL) IMMIGRANT_POPULATION else houseBeds(communal) } ?: break
            residents[single] = residents[single].copy(homeId = destination.id)
            break
        }

        for (index in residents.indices) {
            val resident = residents[index]
            // Family members keep their home. Singles in communal housing move into
            // existing houses without waiting for an unrelated marriage to free them.
            val communal = resident.homeId?.let(homes::get)?.type in setOf(BuildingType.TOWN_HALL, BuildingType.TAVERN)
            if (resident.homeId != null && (!communal || CivsResidents.isChild(resident) || resident.id in paired)) continue
            fun hasRoom(house: CivBuilding): Boolean {
                val spare = houseBeds(house) - residents.count { it.homeId == house.id }
                return spare > if (families.any { it.homeId == house.id }) 1 else 0
            }
            val home = houses.firstOrNull(::hasRoom)
                ?: resident.homeId?.let(homes::get)
                ?: hall.takeIf { residents.count { it.homeId == hall.id } < IMMIGRANT_POPULATION }
                ?: homes.values.firstOrNull { it.type.isResidence && it.phase == BuildingPhase.ACTIVE && hasRoom(it) }
            if (home != null) residents[index] = resident.copy(homeId = home.id)
        }

        var born = false
        for (index in families.indices) {
            val family = families[index]
            val home = houses.firstOrNull { it.id == family.homeId } ?: continue
            val room = residents.size >= IMMIGRANT_POPULATION &&
                residents.size < minOf(CivsRules.residentLimit(city), CivsRules.beds(city)) &&
                residents.count(CivsResidents::isChild) < NURSERY_PLACES &&
                residents.count { it.homeId == home.id } < houseBeds(home)
            if (!room) continue
            val minutes = minOf(BIRTH_MINUTES, family.activeMinutes + 1)
            if (minutes < BIRTH_MINUTES || born) {
                families[index] = family.copy(activeMinutes = minutes)
                continue
            }
            val id = nextId()
            require(residents.none { it.id == id }) { "Duplicate resident identity" }
            val sex = CivsResidents.legacySex(id)
            val current = city.copy(workers = residents.toList(), families = families.toList())
            residents += CivWorker(id, nextName(current, id, sex).trim(), null, sex = sex, homeId = home.id,
                parentIds = setOf(family.firstParentId, family.secondParentId), childMinutes = 0, hunger = 20)
            families[index] = family.copy(activeMinutes = 0)
            born = true
        }
        val next = city.copy(workers = residents.toList(), families = families.takeIf { it.isNotEmpty() }?.toList())
        if (next == city) return city
        if (city.revision == Long.MAX_VALUE) throw CivsRejection("arithmetic-overflow")
        return CivsRules.validate(next.copy(revision = city.revision + 1))
    }

    /** Caller supplies fresh idle actor positions; accepted work and unobserved residents cannot die here. */
    internal fun retireElder(city: Settlement, observed: Map<UUID, CivDeathContext>): Settlement {
        val elder = city.workers.firstOrNull { worker ->
            !CivsResidents.isChild(worker) && worker.id in observed &&
                (worker.life?.adultMinutes ?: 0) >= CivsResidentLife.lifespanMinutes(worker)
        } ?: return city
        val proof = observed.getValue(elder.id)
        require(proof.cause == "OLD_AGE" && city.contains(proof.position.x, proof.position.z))
        return CivsRules.hurtResident(city, city.owner, elder.id, CivsResidents.maxHealth(elder), proof)
    }

    /** Dead parent IDs remain ancestry. Home/family references still point at current city records. */
    internal fun validated(city: Settlement) {
        val workers = city.workers.associateBy { it.id }
        for (worker in city.workers) {
            worker.homeId?.let { home ->
                require(city.building(home)?.type?.let { it == BuildingType.TOWN_HALL || it.isResidence } == true)
            }
            val livingParents = worker.parentIds.orEmpty().mapNotNull(workers::get)
            require(livingParents.none(CivsResidents::isChild))
            if (livingParents.size == 2) require(CivsResidents.sex(livingParents[0]) != CivsResidents.sex(livingParents[1]))
            val ancestors = ArrayDeque(worker.parentIds.orEmpty())
            val seen = hashSetOf<UUID>()
            while (ancestors.isNotEmpty()) {
                val id = ancestors.removeFirst()
                require(id != worker.id)
                if (seen.add(id)) ancestors.addAll(workers[id]?.parentIds.orEmpty())
            }
        }
        val families = city.families.orEmpty()
        require(families.size <= CivsRules.MAX_POPULATION / 2)
        require(families.map { it.homeId }.distinct().size == families.size)
        val parents = families.flatMap { listOf(it.firstParentId, it.secondParentId) }
        require(parents.distinct().size == parents.size)
        for (family in families) {
            val first = requireNotNull(workers[family.firstParentId])
            val second = requireNotNull(workers[family.secondParentId])
            require(!CivsResidents.isChild(first) && !CivsResidents.isChild(second) &&
                CivsResidents.sex(first) != CivsResidents.sex(second) && !related(first, second))
            require(city.building(family.homeId)?.type == BuildingType.HOUSE &&
                first.homeId == family.homeId && second.homeId == family.homeId &&
                family.activeMinutes in 0..BIRTH_MINUTES)
        }
    }
}
