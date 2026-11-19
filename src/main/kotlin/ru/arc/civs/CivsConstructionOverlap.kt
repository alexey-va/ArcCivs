package ru.arc.civs

/** Off-footprint earthworks can overlap even when both building boxes and entrances are separate. */
internal fun civsConstructionPlansCompatible(held: CivPlacementPlan, next: CivPlacementPlan,
    nextApproach: List<CivPlanBlock> = emptyList()): Boolean {
    if (held.worldId != next.worldId) return true
    // Stored route witnesses may extend beyond a terrain box after a paid approach is reused.
    fun area(plan: CivPlacementPlan): CivBox {
        val bounds = plan.building.bounds
        var minX = bounds.min.x
        var maxX = bounds.max.x
        var minZ = bounds.min.z
        var maxZ = bounds.max.z
        fun include(point: CivPosition) {
            minX = minOf(minX, point.x)
            maxX = maxOf(maxX, point.x)
            minZ = minOf(minZ, point.z)
            maxZ = maxOf(maxZ, point.z)
        }
        include(plan.building.entrance)
        plan.terrain?.let { terrain ->
            include(terrain.bounds.min)
            include(terrain.bounds.max)
            terrain.route.forEach(::include)
        }
        return CivBox(CivPosition(minX, 0, minZ), CivPosition(maxX, 0, maxZ))
    }
    val heldArea = area(held)
    val nextArea = area(next)
    if (!heldArea.overlaps(nextArea)) return true

    fun accepts(owner: CivPlacementPlan, ownerApproach: List<CivPlanBlock>, ownerArea: CivBox,
        writer: CivPlacementPlan, writerApproach: List<CivPlanBlock>, writerArea: CivBox): Boolean {
        fun nearWriter(cell: CivPlanBlock) = writerArea.contains(cell.position.x, 0, cell.position.z)
        val authored = owner.blocks.asSequence().filter(::nearWriter).associateBy { it.position }
        val terrain = owner.terrain
        val earthworks = terrain?.blocks.orEmpty().asSequence().filter(::nearWriter).associateBy { it.position }
        val standing = terrain?.route.orEmpty().toHashSet()
        standing += owner.building.entrance
        terrain?.paidGateFloors.orEmpty().forEach { standing += it.copy(y = it.y + 1) }
        val beds = terrain?.waterFoundations.orEmpty().mapTo(hashSetOf()) { CivPosition(it.x, it.bottomY, it.z) }
        fun compatible(cell: CivPlanBlock): Boolean {
            val p = cell.position
            if (cell.before == cell.after || !ownerArea.contains(p.x, 0, p.z)) return true
            if (authored[p]?.let { it.after != cell.after } == true ||
                earthworks[p]?.let { it.after != cell.after } == true ||
                ownerApproach.any { it.position == p && it.after != cell.after }) return false
            val material = cell.after.substringBefore('[')
            if (p in standing || p.copy(y = p.y - 1) in standing) return material in air
            if (p in beds) return material in stableGround || civsPaidWaterFoundationBlock(cell.after)
            if (p.copy(y = p.y + 1) in standing) return material in routeGround ||
                civsPaidWaterFoundationBlock(cell.after) || CivsRoadMaintenance.surface(cell.after)
            return true
        }
        return writer.terrain?.blocks.orEmpty().all(::compatible) && writer.blocks.all(::compatible) &&
            writerApproach.all(::compatible)
    }
    return accepts(held, emptyList(), heldArea, next, nextApproach, nextArea) &&
        accepts(next, nextApproach, nextArea, held, emptyList(), heldArea)
}

private val air = setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air")
private val routeGround = CivsTerrainPolicy.TERRAFORM_GROUND.mapTo(hashSetOf()) { "minecraft:${it.name.lowercase()}" }
private val stableGround = routeGround - CivsTerrainPolicy.FALLING_GROUND.map { "minecraft:${it.name.lowercase()}" }
