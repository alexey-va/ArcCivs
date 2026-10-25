package ru.arc.civs

import java.util.UUID

/** Personal stores are authoritative persisted items; native NPC equipment is only a view. */
object CivsResidents {
    val professions = listOf("BUILDER_HUT", "FORESTER", "QUARRY", "FARM", "TOWN_HALL", "SAWMILL", "BLACKSMITH", "STONEMASON", "LIBRARY", "WAREHOUSE", "BAKERY", "GUARD_TOWER", "UNIVERSITY", "SMELTERY", "CRUSHER")
    val catalog = listOf("BREAD", "IRON_SHOVEL", "IRON_AXE", "IRON_PICKAXE", "IRON_HOE",
        "LEATHER_HELMET", "LEATHER_CHESTPLATE", "LEATHER_LEGGINGS", "LEATHER_BOOTS",
        "IRON_HELMET", "IRON_CHESTPLATE", "IRON_LEGGINGS", "IRON_BOOTS")

    fun health(worker: CivWorker): Double = worker.health ?: 20.0
    fun hunger(worker: CivWorker): Int = worker.hunger ?: 20
    fun isChild(worker: CivWorker): Boolean = worker.childMinutes != null
    fun legacySex(workerId: UUID): CivSex = if (Math.floorMod(workerId.hashCode(), 2) == 0) CivSex.MALE else CivSex.FEMALE
    fun sex(worker: CivWorker): CivSex = worker.sex ?: CivsNames.knownSex(worker.name) ?: legacySex(worker.id)
    fun workDuration(worker: CivWorker, baseMillis: Long): Long {
        require(baseMillis >= 0)
        return if (hunger(worker) <= 6) {
            if (baseMillis > Long.MAX_VALUE / 2) Long.MAX_VALUE else baseMillis * 2
        } else baseMillis
    }

    /** The active city clock calls this once; only an existing plain bread in the bag can be eaten. */
    fun activeMinute(worker: CivWorker, working: Boolean): CivWorker {
        val hunger = (hunger(worker) - if (working && !isChild(worker)) 1 else 0).coerceAtLeast(0)
        val items = CivsEquipment.slots(worker)
        val bread = if (hunger <= 14 && CivsEquipment.usableCount(worker, "BREAD") > 0) (CivsEquipment.BAG until items.size).firstOrNull {
            items[it]?.let { item -> item.material == "BREAD" && item.data == null } == true
        } else null
        if (bread == null) return if (worker.hunger == hunger) worker else worker.copy(hunger = hunger)
        val consumed = items.toMutableList()
        val item = requireNotNull(consumed[bread])
        consumed[bread] = item.copy(amount = item.amount - 1).takeIf { it.amount > 0 }
        return CivsEquipment.replace(worker, consumed).copy(hunger = minOf(20, hunger + 6))
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
    fun trained(worker: CivWorker, type: BuildingType): CivWorker {
        require(!isChild(worker))
        val profession = profession(type)
        if (profession !in professions) return worker
        return worker.copy(skills = worker.skills.orEmpty() +
            (profession to (skillExperience(worker, profession) + 1).coerceAtMost(40)))
    }
    fun validated(worker: CivWorker) {
        require(health(worker).isFinite() && health(worker) > 0 && health(worker) <= maxHealth(worker))
        require(hunger(worker) in 0..20)
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
