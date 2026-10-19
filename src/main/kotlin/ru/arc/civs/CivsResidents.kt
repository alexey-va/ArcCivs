package ru.arc.civs

/** Personal stores are authoritative persisted items; native NPC equipment is only a view. */
object CivsResidents {
    val professions = listOf("BUILDER_HUT", "FORESTER", "QUARRY", "FARM", "TOWN_HALL", "SAWMILL", "BLACKSMITH", "STONEMASON", "LIBRARY", "WAREHOUSE")
    val catalog = listOf("BREAD", "IRON_SHOVEL", "IRON_AXE", "IRON_PICKAXE", "IRON_HOE",
        "LEATHER_HELMET", "LEATHER_CHESTPLATE", "LEATHER_LEGGINGS", "LEATHER_BOOTS",
        "IRON_HELMET", "IRON_CHESTPLATE", "IRON_LEGGINGS", "IRON_BOOTS")

    fun health(worker: CivWorker): Double = worker.health ?: 20.0
    fun skillExperience(worker: CivWorker, profession: String): Long = worker.skills.orEmpty()[profession] ?: 0
    fun skillLevel(worker: CivWorker, profession: String): Int =
        1 + (skillExperience(worker, profession) / 10).toInt().coerceIn(0, 4)
    fun maxHealth(worker: CivWorker): Double = 20.0 + 2 * professions.maxOf { skillLevel(worker, it) - 1 }
    fun profession(type: BuildingType): String = type.name
    fun tool(type: BuildingType): String? = when (type) {
        BuildingType.BUILDER_HUT -> "IRON_SHOVEL"
        BuildingType.FORESTER, BuildingType.SAWMILL -> "IRON_AXE"
        BuildingType.QUARRY, BuildingType.BLACKSMITH, BuildingType.STONEMASON -> "IRON_PICKAXE"
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
        val profession = profession(type)
        if (profession !in professions) return worker
        return worker.copy(skills = worker.skills.orEmpty() +
            (profession to (skillExperience(worker, profession) + 1).coerceAtMost(40)))
    }
    fun validated(worker: CivWorker) {
        require(health(worker).isFinite() && health(worker) > 0 && health(worker) <= maxHealth(worker))
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
