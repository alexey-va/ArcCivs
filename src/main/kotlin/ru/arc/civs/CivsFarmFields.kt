package ru.arc.civs

/** Frozen retained-field geometry for the exact authored fields that support a wider two-bed layout. */
internal object CivsFarmFields {
    const val VERSION = 1

    private class Profile(family: String, revisions: Set<String>, val dx: Int, val dz: Int) {
        private fun point(x: Int, z: Int) = CivPosition(x + dx, 0, z + dz)
        val originalWater = listOf(point(15, -14))
        val originalSoil = (11..19).flatMap { x -> (-18..-10).map { z -> point(x, z) } } - originalWater.toSet()
        val water = (-21..-7).map { z -> point(15, z) }
        val soil = (11..19).filter { it != 15 }.flatMap { x -> (-21..-7).map { z -> point(x, z) } }
        val columns = (soil + water).mapTo(hashSetOf()) { it.x to it.z }
        val draining = originalSoil.filter { it in water }
        val added = (soil + water).filter { it !in originalSoil && it !in originalWater }
        val corners = listOf(point(11, -21), point(11, -7), point(19, -21), point(19, -7))
        val rim = (10..20).flatMap { x -> listOf(point(x, -22), point(x, -6)) } +
            (-21..-7).flatMap { z -> listOf(point(10, z), point(20, z)) }

        val schematicIds = revisions.flatMap { revision ->
            (1..3).map { level -> "$family/$revision/level$level" }
        }
    }

    private val profiles = listOf(
        // The Nordic captures share field and entrance geometry. Only generated exterior gravel changed.
        Profile("civs/v2/nordic/agriculture-horticulture-farmer",
            setOf("c0ad265fc22f522f22f9", "660de08515ed8f6e5b27"), dx = 0, dz = 0),
        Profile("civs/v2/medievaloak/agriculture-horticulture-altfarmer",
            setOf("b2847c45d9139927e715"), dx = 1, dz = 3),
    ).flatMap { profile -> profile.schematicIds.map { it to profile } }.toMap()

    private fun profile(building: CivBuilding): Profile? =
        if (building.type == BuildingType.FARM && building.schematicId.lastOrNull()?.digitToIntOrNull() == building.level)
            profiles[building.schematicId] else null

    private fun world(building: CivBuilding, local: List<CivPosition>): List<CivPosition> =
        local.map { CivsWorksiteLayouts.point(building, it) }

    fun supported(building: CivBuilding): Boolean = profile(building) != null

    fun originalMetadata(building: CivBuilding): Boolean {
        val profile = profile(building) ?: return false
        return CivsWorksiteLayouts.points(building, "farm", "soilCells").toSet() == world(building, profile.originalSoil).toSet() &&
            CivsWorksiteLayouts.points(building, "farm", "irrigationCells") == world(building, profile.originalWater)
    }

    /** An ordinary upgrade keeps its original immutable site journal and its field profile. */
    fun matchesRetainedProfile(retained: CivBuilding, active: CivBuilding): Boolean =
        retained.level <= active.level && profile(active)?.let { it === profile(retained) } == true &&
            originalMetadata(retained)

    fun columns(building: CivBuilding): Set<Pair<Int, Int>> = requireNotNull(profile(building)).columns
    fun soil(building: CivBuilding): List<CivPosition> = world(building, requireNotNull(profile(building)).soil)
    fun water(building: CivBuilding): List<CivPosition> = world(building, requireNotNull(profile(building)).water)
    fun draining(building: CivBuilding): Set<CivPosition> = world(building, requireNotNull(profile(building)).draining).toSet()
    fun added(building: CivBuilding): List<CivPosition> = world(building, requireNotNull(profile(building)).added)
    fun corners(building: CivBuilding): List<CivPosition> = world(building, requireNotNull(profile(building)).corners)
    fun rim(building: CivBuilding): List<CivPosition> = world(building, requireNotNull(profile(building)).rim)

    fun validReference(city: Settlement, building: CivBuilding): Boolean = building.farmFieldVersion == null ||
        building.farmFieldVersion == VERSION && supported(building) && building.phase != BuildingPhase.SITE &&
            city.autonomy != null && city.inventory?.mode == CivStorageMode.PHYSICAL &&
            corners(building).all { city.contains(it.x, it.z) }
}
