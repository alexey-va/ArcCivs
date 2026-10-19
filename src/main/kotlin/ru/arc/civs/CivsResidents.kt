package ru.arc.civs

/** Small personal stores contain only explicitly supplied plain items, never native NPC copies. */
object CivsResidents {
    val professions = listOf("BUILDER_HUT", "FORESTER", "QUARRY", "FARM")
    val catalog = listOf("BREAD", "IRON_SHOVEL", "IRON_AXE", "IRON_PICKAXE", "IRON_HOE",
        "LEATHER_HELMET", "LEATHER_CHESTPLATE", "LEATHER_LEGGINGS", "LEATHER_BOOTS",
        "IRON_HELMET", "IRON_CHESTPLATE", "IRON_LEGGINGS", "IRON_BOOTS")

    fun health(worker: CivWorker): Double = worker.health ?: 20.0
    fun skillExperience(worker: CivWorker, profession: String): Long = worker.skills.orEmpty()[profession] ?: 0
    fun skillLevel(worker: CivWorker, profession: String): Int =
        1 + (skillExperience(worker, profession) / 10).toInt().coerceIn(0, 4)
    fun maxHealth(worker: CivWorker): Double = 20.0 + 2 * professions.maxOf { skillLevel(worker, it) - 1 }
    fun profession(type: BuildingType): String = if (type == BuildingType.TOWN_HALL) "BUILDER_HUT" else type.name
    fun tool(type: BuildingType): String? = when (type) {
        BuildingType.TOWN_HALL, BuildingType.BUILDER_HUT -> "IRON_SHOVEL"
        BuildingType.FORESTER -> "IRON_AXE"
        BuildingType.QUARRY -> "IRON_PICKAXE"
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
        (worker.inventory.orEmpty()[material] ?: 0) < (if (material == "BREAD") 16 else 1) &&
        worker.inventory.orEmpty().keys.none { it != material && slot(it) == slot(material) }
    fun damage(worker: CivWorker, type: BuildingType?, nativeDamage: Double): Double =
        nativeDamage * if (type?.let(::tool)?.let { worker.inventory.orEmpty()[it] == 1L } == true) 0.9 else 1.0
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
    }
}
