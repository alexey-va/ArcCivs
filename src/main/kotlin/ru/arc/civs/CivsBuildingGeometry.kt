package ru.arc.civs

/** Production selection does not move a building, its entrances, fields or retained roads. */
internal fun civsSameBuildingGeometry(before: CivBuilding?, after: CivBuilding?): Boolean =
    before == after || before != null && after != null && before.copy(production = after.production) == after

/** Preserve a captured layout across production changes; every structural field still participates. */
internal fun civsSameBuildingLayout(before: List<CivBuilding>?, after: List<CivBuilding>?): Boolean =
    before === after || before != null && after != null && before.size == after.size &&
        before.indices.all { civsSameBuildingGeometry(before[it], after[it]) }
