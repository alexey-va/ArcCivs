package ru.arc.civs

import org.bukkit.Material

/** Bounded per-column cover observation; a roof or tree is never a terrain-depth measurement. */
internal class CivMineCover(val world: java.util.UUID, val building: java.util.UUID, val x: Int, val z: Int,
    val top: Int, val minimum: Int) {
    var next = top
        private set
    var ground: Int? = null
        private set
    var finished = false
        private set

    fun step(read: (Int) -> Material) {
        if (finished) return
        val material = read(next)
        if (CivsMineSafety.natural(material)) { ground = next; finished = true; return }
        next--
        if (next < minimum || top - next >= CivsMineSafety.MAXIMUM_COVER_READS) finished = true
    }
}

internal object CivsMineSafety {
    const val MAXIMUM_COVER_READS = 64
    const val COMPACT_RADIUS = 8
    const val COMPACT_COVER = 6
    const val FULL_COVER = 16

    /** Explicit hazard removal, never an item, equipment or completed excavation receipt. */
    fun clearingLava(work: CivWorldWork): Boolean = work.miningPlanVersion == 7 && work.status == "securing-mine" &&
        !work.miningGenerator && work.inputs.isEmpty() && work.outputs.isEmpty() && work.equipmentAfter == null &&
        work.miningSections != null && work.miningAccessSections != null && work.miningOrdinal != null &&
        work.changes.singleOrNull()?.let { it.before.substringBefore('[') == "minecraft:lava" && it.after == "minecraft:air" } == true

    fun natural(material: Material): Boolean = material in GROUND || material.name.endsWith("_ORE")

    /** Existing ladder/backing access stays narrow; broad landings and arms need real terrain cover. */
    fun requiredCover(plan: CivsMinePlan, ordinal: Int, local: CivPosition): Int {
        if (plan.section(ordinal) == "spine" &&
            kotlin.math.abs(local.x - plan.bottom.x) <= 1 && kotlin.math.abs(local.z - plan.bottom.z) <= 1) return 0
        return if (maxOf(kotlin.math.abs(local.x - plan.center.x), kotlin.math.abs(local.z - plan.center.z)) <= COMPACT_RADIUS)
            COMPACT_COVER else FULL_COVER
    }

    fun ceiling(plan: CivsMinePlan, ordinal: Int, local: CivPosition): Int {
        val floor = plan.steps[ordinal].floor?.let { index ->
            if (index == 0) plan.entranceLanding else plan.floors.firstOrNull { it.index == index }
        }
        return maxOf(local.y, floor?.let { it.feetY + it.height - 1 } ?: local.y)
    }

    private val GROUND = setOf(Material.STONE, Material.DEEPSLATE, Material.GRANITE, Material.DIORITE,
        Material.ANDESITE, Material.TUFF, Material.CALCITE, Material.DIRT, Material.GRASS_BLOCK,
        Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.PODZOL, Material.MYCELIUM, Material.CLAY,
        Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.TERRACOTTA, Material.MUD,
        Material.SANDSTONE, Material.RED_SANDSTONE)
}
