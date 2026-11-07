package ru.arc.civs

/** Frozen retained-field profile; curator metadata still owns every unversioned farm. */
internal object CivsFarmFields {
    const val VERSION = 1
    private const val SOURCE = "civs/v2/nordic/agriculture-horticulture-farmer/c0ad265fc22f522f22f9/level"
    private val originalWater = listOf(CivPosition(15, 0, -14))
    private val originalSoil = (11..19).flatMap { x -> (-18..-10).map { z -> CivPosition(x, 0, z) } }
        .filter { it !in originalWater }
    private val water = (-21..-7).map { z -> CivPosition(15, 0, z) }
    private val soil = (11..19).filter { it != 15 }.flatMap { x -> (-21..-7).map { z -> CivPosition(x, 0, z) } }
    val columns: Set<Pair<Int, Int>> = (soil + water).mapTo(hashSetOf()) { it.x to it.z }

    fun supported(building: CivBuilding): Boolean = building.type == BuildingType.FARM && building.level in 1..3 &&
        building.schematicId == "$SOURCE${building.level}"

    fun originalMetadata(building: CivBuilding): Boolean = supported(building) &&
        CivsWorksiteLayouts.points(building, "farm", "soilCells").toSet() == originalSoil.map { CivsWorksiteLayouts.point(building, it) }.toSet() &&
        CivsWorksiteLayouts.points(building, "farm", "irrigationCells") == originalWater.map { CivsWorksiteLayouts.point(building, it) }

    fun soil(building: CivBuilding): List<CivPosition> = soil.map { CivsWorksiteLayouts.point(building, it) }
    fun water(building: CivBuilding): List<CivPosition> = water.map { CivsWorksiteLayouts.point(building, it) }
    fun draining(building: CivBuilding): Set<CivPosition> = originalSoil.filter { it in water }
        .mapTo(hashSetOf()) { CivsWorksiteLayouts.point(building, it) }
    fun added(building: CivBuilding): List<CivPosition> = (soil + water).filter { it !in originalSoil && it !in originalWater }
        .map { CivsWorksiteLayouts.point(building, it) }
    fun corners(building: CivBuilding): List<CivPosition> = listOf(11 to -21, 11 to -7, 19 to -21, 19 to -7)
        .map { (x, z) -> CivsWorksiteLayouts.point(building, CivPosition(x, 0, z)) }

    fun validReference(city: Settlement, building: CivBuilding): Boolean = building.farmFieldVersion == null ||
        building.farmFieldVersion == VERSION && supported(building) && building.phase != BuildingPhase.SITE &&
            city.autonomy != null && city.inventory?.mode == CivStorageMode.PHYSICAL &&
            corners(building).all { city.contains(it.x, it.z) }
}
