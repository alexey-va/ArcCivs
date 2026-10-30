package ru.arc.civs

import java.util.UUID

/** Personal stores are authoritative persisted items; native NPC equipment is only a view. */
object CivsResidents {
    val professions = listOf("BUILDER_HUT", "FORESTER", "QUARRY", "FARM", "TOWN_HALL", "SAWMILL", "BLACKSMITH", "STONEMASON", "LIBRARY", "WAREHOUSE", "BAKERY", "TAVERN", "GUARD_TOWER", "UNIVERSITY", "SMELTERY", "CRUSHER")
    val catalog = listOf("BREAD", "IRON_SHOVEL", "IRON_AXE", "IRON_PICKAXE", "IRON_HOE",
        "LEATHER_HELMET", "LEATHER_CHESTPLATE", "LEATHER_LEGGINGS", "LEATHER_BOOTS",
        "IRON_HELMET", "IRON_CHESTPLATE", "IRON_LEGGINGS", "IRON_BOOTS")

    fun health(worker: CivWorker): Double = worker.health ?: 20.0
    fun hunger(worker: CivWorker): Int = worker.hunger ?: 20
    internal fun canRegenerate(worker: CivWorker): Boolean = canRegenerate(worker, null)
    internal fun canRegenerate(worker: CivWorker, city: Settlement?): Boolean =
        hunger(worker) >= (city?.let(CivsResearch::regenerationHunger) ?: 18) && health(worker) > 0 && health(worker) < maxHealth(worker)
    internal fun regenerate(worker: CivWorker): CivWorker = regenerate(worker, null)
    internal fun regenerate(worker: CivWorker, city: Settlement?): CivWorker = if (!canRegenerate(worker, city)) worker
        else worker.copy(health = minOf(maxHealth(worker), health(worker) + 1.0))
    fun isChild(worker: CivWorker): Boolean = worker.childMinutes != null
    fun legacySex(workerId: UUID): CivSex = if (Math.floorMod(workerId.hashCode(), 2) == 0) CivSex.MALE else CivSex.FEMALE
    fun sex(worker: CivWorker): CivSex = worker.sex ?: CivsNames.knownSex(worker.name) ?: legacySex(worker.id)
    fun workDuration(worker: CivWorker, baseMillis: Long): Long {
        require(baseMillis >= 0)
        return if (hunger(worker) <= 6) {
            if (baseMillis > Long.MAX_VALUE / 2) Long.MAX_VALUE else baseMillis * 2
        } else baseMillis
    }

    /** One observed awake minute, including idle time. Meals require a separate actual-arrival callback. */
    fun activeMinute(worker: CivWorker, awake: Boolean): CivWorker {
        if (!awake) return worker
        val life = CivsResidentLife.minute(worker)
        val drain = if (!isChild(worker) || !life.childHungerRemainder) 1 else 0
        return worker.copy(hunger = (hunger(worker) - drain).coerceAtLeast(0), life = life)
    }
    fun skillExperience(worker: CivWorker, profession: String): Long = worker.skills.orEmpty()[profession] ?: 0
    fun skillLevel(worker: CivWorker, profession: String): Int =
        1 + (skillExperience(worker, profession) / 10).toInt().coerceIn(0, 4)
    fun maxHealth(worker: CivWorker): Double = 20.0 + 2 * professions.maxOf { skillLevel(worker, it) - 1 }
    fun profession(type: BuildingType): String = type.name
    fun tool(type: BuildingType): String? = when (type) {
        BuildingType.BUILDER_HUT -> "IRON_SHOVEL"
        BuildingType.FORESTER, BuildingType.SAWMILL -> "IRON_AXE"
        BuildingType.QUARRY, BuildingType.BLACKSMITH, BuildingType.STONEMASON, BuildingType.SMELTERY, BuildingType.CRUSHER -> "IRON_PICKAXE"
        BuildingType.FARM -> "IRON_HOE"
        else -> null
    }
    fun slot(material: String): String = when {
        material.endsWith("_HELMET") -> "HELMET"
        material.endsWith("_CHESTPLATE") -> "CHESTPLATE"
        material.endsWith("_LEGGINGS") -> "LEGGINGS"
        material.endsWith("_BOOTS") -> "BOOTS"
        else -> material
    }
    fun canCarry(worker: CivWorker, material: String): Boolean = material in catalog &&
        CivsEquipment.plainCount(worker, material) < (if (material == "BREAD") 16 else 1) &&
        CivsEquipment.armorSlot(material)?.let { CivsEquipment.slots(worker)[it] == null } != false &&
        runCatching { CivsEquipment.supply(worker, material, false) }.isSuccess
    fun damage(worker: CivWorker, type: BuildingType?, nativeDamage: Double): Double =
        nativeDamage * if (type?.let(::tool)?.let { tool -> CivsEquipment.slots(worker).take(6).any { it?.material == tool } } == true) 0.9 else 1.0
    fun trained(worker: CivWorker, type: BuildingType, city: Settlement? = null): CivWorker {
        require(!isChild(worker))
        val profession = profession(type)
        if (profession !in professions) return worker
        val experience = skillExperience(worker, profession)
        // Null life is the exact old training witness used by already accepted receipts.
        val observedCity = city?.takeIf { worker.life != null }
        val cap = observedCity?.let { CivsResidentLife.learningCap(it, worker) } ?: 40L
        if (experience >= cap) return worker // A poorer home never removes historical experience.
        val gain = if (observedCity != null && CivsResidentLife.aptitude(worker) == type) 2L else 1L
        return worker.copy(skills = worker.skills.orEmpty() + (profession to minOf(cap, experience + gain)))
    }
    fun validated(worker: CivWorker) {
        require(health(worker).isFinite() && health(worker) > 0 && health(worker) <= maxHealth(worker))
        require(hunger(worker) in 0..20)
        CivsResidentLife.validated(worker)
        worker.childMinutes?.let { require(it in 0 until CivsPopulation.ADULT_MINUTES) }
        worker.parentIds?.let { require(it.size == 2 && it.all { parent -> parent != null && parent != worker.id }) }
        if (isChild(worker)) {
            require(worker.parentIds != null && worker.assignment == null && worker.cargo == null &&
                worker.completedCycles == 0L && worker.nextDeliveryAt == 0L && worker.skills.isNullOrEmpty())
        }
        require(worker.skills.orEmpty().all { (key, value) -> key in professions && value in 0..40 })
        val items = worker.inventory.orEmpty()
        require(items.all { (key, value) -> key in catalog && value in 1..if (key == "BREAD") 16L else 1L })
        require(items.keys.map(::slot).distinct().size == items.size)
        worker.items?.let {
            require(worker.inventory.isNullOrEmpty())
            CivsEquipment.validate(it)
        }
    }
}
