package ru.arc.civs

import java.util.UUID

/** Observed active time only. Missing life state preserves old residents without invented history. */
data class CivResidentLife(
    val awakeMinutes: Long = 0,
    val lastMealMinute: Long? = null,
    val lastRestMinute: Long? = null,
    val meals: Long = 0,
    val childHungerRemainder: Boolean = false,
    val lastLessonAge: Int? = null,
    val education: Map<String, Long>? = null,
)

enum class CivResidentMealSource { CARRIED_BREAD, ABSTRACT_FOOD, CARRIED_FOOD }

/** Building is the city context; the actor proves an uninterrupted meal where it actually stands. */
data class CivResidentMeal(val cityRevision: Long, val workerId: UUID, val buildingId: UUID,
    val mealsBefore: Long, val source: CivResidentMealSource, val amount: Int = 1,
    val material: String = "BREAD", val nutrition: Int = 6, val consumeMillis: Long = 1_600L) {
    val durationMillis: Long get() = maxOf(4_000L, amount * consumeMillis)
}

/** Detached native observations; the actor must establish a fresh, supported co-location before committing. */
data class CivResidentLessonProof(val cityRevision: Long, val childId: UUID, val teacherId: UUID,
    val profession: BuildingType, val childAge: Int, val childAwakeMinute: Long,
    val childPosition: CivPosition, val teacherPosition: CivPosition)

enum class CivResidentNeed {
    FED, NEEDS_MEAL, HUNGRY, HOMELESS, HOUSED, REST_NEEDED, RESTED, JOBLESS, OCCUPIED, FAMILY, GUARDED, UNGUARDED,
}

data class CivResidentNeeds(val happiness: Int, val causes: List<CivResidentNeed>)

/** Pure life transitions. Native arrival/session proof and durable publication belong to the service/actor. */
object CivsResidentLife {
    const val REST_AFTER_MINUTES = 30L
    const val MAX_EDUCATION_XP = 10L
    private const val MAX_COUNTER = 1_000_000_000L

    fun aptitude(worker: CivWorker): BuildingType = BuildingType.valueOf(
        CivsResidents.professions[Math.floorMod(worker.id.hashCode(), CivsResidents.professions.size)])

    fun learningCap(city: Settlement, worker: CivWorker): Long = worker.homeId?.let(city::building)?.takeIf {
        it.type == BuildingType.HOUSE && it.phase == BuildingPhase.ACTIVE
    }?.let { (it.level + 1) * 10L } ?: 10L

    internal fun minute(worker: CivWorker): CivResidentLife {
        val before = worker.life ?: CivResidentLife()
        if (before.awakeMinutes == MAX_COUNTER) throw CivsRejection("arithmetic-overflow")
        return before.copy(awakeMinutes = before.awakeMinutes + 1,
            childHungerRemainder = CivsResidents.isChild(worker) && !before.childHungerRemainder)
    }

    fun mealPlan(city: Settlement, worker: CivWorker, preferredBuildingId: UUID? = null,
        foods: CivsResidentFoods = CivsResidentFoods.BREAD_ONLY): CivResidentMeal? {
        if (city.pendingDeletion != null || city.autonomy?.paused == true || CivsResidents.hunger(worker) > 14 ||
            city.workers.none { it.id == worker.id }) return null
        val material = foods.carried(city, worker) ?: return null
        val food = requireNotNull(foods.definition(material))
        val amount = minOf((15 - CivsResidents.hunger(worker) + food.nutrition - 1) / food.nutrition,
            foods.available(city, worker, material))
        val building = if (preferredBuildingId != null) city.building(preferredBuildingId)?.takeIf {
            it.type == BuildingType.TOWN_HALL || it.type == BuildingType.TAVERN && it.phase == BuildingPhase.ACTIVE && it.control != null
        } ?: return null
        else CivsResidentFood.tavern(city)
            ?: city.buildings.firstOrNull { it.type == BuildingType.TOWN_HALL } ?: return null
        return CivResidentMeal(city.revision, worker.id, building.id, worker.life?.meals ?: 0,
            if (material == "BREAD") CivResidentMealSource.CARRIED_BREAD else CivResidentMealSource.CARRIED_FOOD,
            amount, material, food.nutrition, food.consumeMillis)
    }

    /** One existing food unit after actual arrival, never work, stock production or profession XP. */
    fun finishMeal(city: Settlement, plan: CivResidentMeal, foods: CivsResidentFoods = CivsResidentFoods.BREAD_ONLY): Settlement {
        val worker = city.workers.firstOrNull { it.id == plan.workerId } ?: throw CivsRejection("worker-not-found")
        if (plan.cityRevision > city.revision || plan.copy(cityRevision = city.revision) != mealPlan(city, worker, plan.buildingId, foods))
            throw CivsRejection("stale-meal")
        val life = worker.life ?: CivResidentLife()
        if (life.meals > MAX_COUNTER - plan.amount) throw CivsRejection("arithmetic-overflow")
        var supplied = worker
        repeat(plan.amount) {
            val items = CivsEquipment.slots(supplied).toMutableList()
            val slot = plainBagFood(supplied, plan.material) ?: throw CivsRejection("resident-food-missing")
            val bread = requireNotNull(items[slot])
            items[slot] = bread.copy(amount = bread.amount - 1).takeIf { it.amount > 0 }
            supplied = CivsEquipment.replace(supplied, items)
        }
        val fed = supplied.copy(hunger = minOf(20, CivsResidents.hunger(worker) + plan.nutrition * plan.amount),
            life = life.copy(lastMealMinute = life.awakeMinutes, meals = life.meals + plan.amount))
        return changed(city, city.copy(
            workers = city.workers.map { if (it.id == fed.id) fed else it }))
    }

    fun finishRest(city: Settlement, workerId: UUID, expectedAwakeMinute: Long): Settlement {
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        val life = worker.life ?: throw CivsRejection("stale-rest")
        if (city.pendingDeletion != null || city.autonomy?.paused == true || expectedAwakeMinute != life.awakeMinutes ||
            life.lastRestMinute == expectedAwakeMinute) throw CivsRejection("stale-rest")
        return changed(city, city.copy(workers = city.workers.map {
            if (it.id == workerId) it.copy(life = life.copy(lastRestMinute = life.awakeMinutes)) else it
        }))
    }

    /** At most one education point per observed child minute, ten total, without child work or items. */
    fun finishLesson(city: Settlement, proof: CivResidentLessonProof): Settlement {
        val child = city.workers.firstOrNull { it.id == proof.childId } ?: throw CivsRejection("worker-not-found")
        val teacher = city.workers.firstOrNull { it.id == proof.teacherId } ?: throw CivsRejection("worker-not-found")
        val life = child.life ?: throw CivsRejection("stale-lesson")
        val teacherType = teacher.assignment?.let(city::building)?.let { CivsRules.effectiveWorkType(city, it) }
        fun inside(point: CivPosition) = city.contains(point.x, point.z) && point.y in -4_096..4_096
        val dx = proof.childPosition.x.toLong() - proof.teacherPosition.x
        val dy = proof.childPosition.y.toLong() - proof.teacherPosition.y
        val dz = proof.childPosition.z.toLong() - proof.teacherPosition.z
        if (city.pendingDeletion != null || city.autonomy?.paused == true || proof.cityRevision > city.revision ||
            !CivsResidents.isChild(child) || CivsResidents.isChild(teacher) || child.id == teacher.id ||
            proof.childAge != child.childMinutes || proof.childAwakeMinute != life.awakeMinutes || life.awakeMinutes == 0L ||
            life.lastLessonAge == proof.childAge || teacherType != proof.profession ||
            proof.profession.name !in CivsResidents.professions || CivsResidents.skillExperience(teacher, proof.profession.name) < 10L ||
            !inside(proof.childPosition) || !inside(proof.teacherPosition) || dx * dx + dy * dy + dz * dz > 16L)
            throw CivsRejection("stale-lesson")
        val education = life.education.orEmpty()
        if (education.values.sum() >= MAX_EDUCATION_XP) throw CivsRejection("education-complete")
        val next = life.copy(lastLessonAge = proof.childAge,
            education = education + (proof.profession.name to ((education[proof.profession.name] ?: 0) + 1)))
        return changed(city, city.copy(workers = city.workers.map { if (it.id == child.id) it.copy(life = next) else it }))
    }

    internal fun adult(city: Settlement, worker: CivWorker): CivWorker {
        val life = worker.life ?: return worker.copy(childMinutes = null)
        val cap = learningCap(city, worker)
        val skills = life.education.orEmpty().mapValues { (_, xp) -> minOf(cap, xp) }.takeIf { it.isNotEmpty() }
        return worker.copy(childMinutes = null, skills = skills,
            life = life.copy(education = null, lastLessonAge = null, childHungerRemainder = false))
    }

    fun needs(city: Settlement, worker: CivWorker): CivResidentNeeds {
        var happiness = 60
        val causes = mutableListOf<CivResidentNeed>()
        fun cause(value: CivResidentNeed, change: Int) { causes += value; happiness += change }
        when (CivsResidents.hunger(worker)) {
            in 0..6 -> cause(CivResidentNeed.HUNGRY, -25)
            in 7..14 -> cause(CivResidentNeed.NEEDS_MEAL, -10)
            else -> cause(CivResidentNeed.FED, 10)
        }
        val home = worker.homeId?.let(city::building)?.takeIf { it.phase == BuildingPhase.ACTIVE &&
            (it.type == BuildingType.TOWN_HALL || it.type.isResidence) }
        cause(if (home == null) CivResidentNeed.HOMELESS else CivResidentNeed.HOUSED, if (home == null) -20 else 10)
        worker.life?.let { life ->
            if (life.awakeMinutes - (life.lastRestMinute ?: 0) >= REST_AFTER_MINUTES) cause(CivResidentNeed.REST_NEEDED, -15)
            else if (life.lastRestMinute != null) cause(CivResidentNeed.RESTED, 10)
        }
        if (!CivsResidents.isChild(worker)) {
            val employed = worker.assignment?.let(city::building) != null
            cause(if (employed) CivResidentNeed.OCCUPIED else CivResidentNeed.JOBLESS, if (employed) 5 else -10)
        }
        if (city.workers.any { other -> other.id != worker.id && other.homeId != null && other.homeId == worker.homeId &&
                (other.id in worker.parentIds.orEmpty() || worker.id in other.parentIds.orEmpty() || city.families.orEmpty().any {
                    setOf(it.firstParentId, it.secondParentId) == setOf(worker.id, other.id)
                }) }) cause(CivResidentNeed.FAMILY, 5)
        val guarded = city.buildings.any { it.type == BuildingType.GUARD_TOWER && it.phase == BuildingPhase.ACTIVE &&
            city.workers.any { guard -> guard.assignment == it.id && !CivsResidents.isChild(guard) } }
        cause(if (guarded) CivResidentNeed.GUARDED else CivResidentNeed.UNGUARDED, if (guarded) 5 else -5)
        return CivResidentNeeds(happiness.coerceIn(0, 100), causes.toList())
    }

    internal fun validated(worker: CivWorker) {
        val life = worker.life ?: return
        require(life.awakeMinutes in 0..MAX_COUNTER && life.meals in 0..MAX_COUNTER)
        require(life.lastMealMinute?.let { it in 0..life.awakeMinutes } != false &&
            life.lastRestMinute?.let { it in 0..life.awakeMinutes } != false)
        require(life.lastLessonAge?.let { CivsResidents.isChild(worker) && it in 0..requireNotNull(worker.childMinutes) } != false)
        val education = life.education.orEmpty()
        require(education.size <= MAX_EDUCATION_XP && education.all { (key, xp) -> key in CivsResidents.professions && xp in 1..MAX_EDUCATION_XP } &&
            education.values.sum() <= MAX_EDUCATION_XP)
        require(CivsResidents.isChild(worker) || education.isEmpty() && !life.childHungerRemainder)
    }

    private fun plainBagFood(worker: CivWorker, material: String): Int? = CivsEquipment.slots(worker).let { items ->
        (CivsEquipment.BAG until items.size).firstOrNull { items[it]?.let { it.material == material && it.data == null } == true }
    }

    private fun changed(before: Settlement, after: Settlement): Settlement {
        if (before == after) return before
        if (before.revision == Long.MAX_VALUE) throw CivsRejection("arithmetic-overflow")
        return CivsRules.validate(after.copy(revision = before.revision + 1))
    }
}
