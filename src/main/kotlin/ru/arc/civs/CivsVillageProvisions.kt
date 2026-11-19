package ru.arc.civs

/** Replenishment of the outpost takes place at one staffed tower, through the ordinary couriers. */
internal object CivsVillageProvisions {
    const val ISSUE_INTERVAL = 300_000L

    fun supported(city: Settlement) = city.autonomy != null && city.inventory?.mode == CivStorageMode.PHYSICAL

    fun enabled(city: Settlement) = city.settings?.garrisonSupplies == true && supported(city) &&
        city.autonomy?.paused == false && city.pendingDeletion == null

    fun tower(city: Settlement): CivBuilding? = if (!enabled(city)) null else city.buildings.firstOrNull { building ->
        building.type == BuildingType.GUARD_TOWER && building.phase == BuildingPhase.ACTIVE && building.control != null &&
            city.workers.any { it.assignment == building.id && !CivsResidents.isChild(it) }
    }

    val loads = mapOf("OAK_LOG" to 16L, "WHEAT" to 8L, "GRAVEL" to 8L, "GLASS" to 8L,
        "CHARCOAL" to 8L, "IRON_SHOVEL" to 3L, "BOOK" to 4L)

    fun ready(city: Settlement, stock: Map<String, Long>, now: Long): Map<String, Long> = loads.filter { (item, amount) ->
        val last = city.settings?.lastGarrisonIssue?.get(item)
        (stock[item] ?: 0L) >= amount && (last == null || now - last >= ISSUE_INTERVAL)
    }
}
