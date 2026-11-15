package ru.arc.civs

import org.bukkit.Material

private fun requireCivs(allowed: Boolean, reason: String) {
    if (!allowed) throw CivsRejection(reason)
}

/** The authored mouth is construction access, not a paid mining-progress receipt. */
internal object CivsMineOpening {
    // The installed catalog's tallest mine is fortress: sixteen layers in its nine central columns.
    const val MAX_CELLS = 144

    fun positions(building: CivBuilding): List<CivPosition> {
        requireCivs(building.type == BuildingType.QUARRY && building.phase == BuildingPhase.ACTIVE,
            "invalid-building")
        val plan = CivsMinePlan.forBuilding(building) ?: throw CivsRejection("mine-layout-missing")
        requireCivs(plan.version == 7, "mine-layout-missing")
        val bottom = CivsWorksiteLayouts.point(building, plan.center.copy(y = plan.transferFeetY)).y
        // The saved V7 ordinal ends at ladderBottom, below an authored cap in some families.
        // Join that existing opening to the entrance floor without changing any mining ordinal.
        val top = building.entrance.y - 1
        requireCivs(top >= building.anchor.y + plan.bottom.y && top - bottom in 2..15, "mine-layout-missing")
        return plan.openingColumns.sortedWith(compareBy({ it.first }, { it.second })).flatMap { (x, z) ->
            val column = CivsWorksiteLayouts.point(building, CivPosition(x, 0, z))
            requireCivs(building.bounds.contains(column.x, top, column.z), "mine-layout-missing")
            (bottom..top).map { y -> column.copy(y = y) }
        }.also { require(it.size in 27..MAX_CELLS && it.distinct().size == it.size) }
    }

    /** Never treat a ladder, rim, chest or another authored fitting as an excavation candidate. */
    fun removable(before: String, initial: Boolean): Boolean {
        val material = Material.matchMaterial(before.substringBefore('[')) ?: return false
        return CivsMineSafety.natural(material) || initial && material == Material.COBBLESTONE
    }
}

/** Exact before-images survive partial application/restart; no stock, worker or mine cursor changes. */
data class CivMineOpeningReceipt(val building: CivBuilding, val cells: List<CivPlanBlock>) {
    internal fun detached() = copy(building = building.copy(transit = building.transit?.toList()), cells = cells.toList())

    internal fun validated(after: Settlement) {
        require(after.pendingDeletion == null && after.building(building.id) == building &&
            after.construction?.buildingId != building.id && building.id !in after.constructionQueue.orEmpty())
        val targets = CivsMineOpening.positions(building).toSet()
        require(cells.size in 1..CivsMineOpening.MAX_CELLS && cells.map { it.position }.distinct().size == cells.size)
        require(cells.all { it.position in targets && it.after == "minecraft:air" &&
            CivsMineOpening.removable(it.before, initial = true) })
    }
}
