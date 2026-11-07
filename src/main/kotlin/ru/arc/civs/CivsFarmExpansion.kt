package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.TileState

/** Read-only admission for two fixed flat beds. The resident journal remains the only writer. */
internal object CivsFarmExpansion {
    private val soils = setOf(Material.DIRT, Material.GRASS_BLOCK, Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.FARMLAND)
    private val naturalSoils = soils - Material.FARMLAND
    private val weeds = setOf(Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN)
    private val sides = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    fun validate(city: Settlement, building: CivBuilding, access: CivAutonomousAccess, world: World,
        settlements: Collection<Settlement>, canModify: (Block, Material?) -> Boolean) {
        check(Bukkit.isPrimaryThread())
        fun require(condition: Boolean, stage: String) {
            if (!condition) throw CivsRejection("farm-expansion-unavailable", mapOf("stage" to stage, "building" to building.id.toString()))
        }
        require(world.uid == city.worldId && city.autonomy?.paused == false && city.inventory?.mode == CivStorageMode.PHYSICAL &&
            city.pendingDeletion == null && city.pendingControls.orEmpty().isEmpty() && building.farmFieldVersion == null &&
            building.phase == BuildingPhase.ACTIVE && CivsFarmFields.originalMetadata(building), "original-profile")
        val field = (CivsFarmFields.soil(building) + CivsFarmFields.water(building)).toSet()
        val columns = field.mapTo(hashSetOf()) { it.x to it.z }
        val border = (10..20).flatMap { x -> listOf(CivPosition(x, 0, -22), CivPosition(x, 0, -6)) } +
            (-21..-7).flatMap { z -> listOf(CivPosition(10, 0, z), CivPosition(20, 0, z)) }
        val perimeter = border.map { CivsWorksiteLayouts.point(building, it) }
        val operating = (field + perimeter).mapTo(hashSetOf()) { it.x to it.z }
        require(operating.size <= 192 && operating.all { city.contains(it.first, it.second) }, "city-coverage")
        val others = settlements.filter { it.worldId == city.worldId }.flatMap { other ->
            other.buildings.filter { it.id != building.id }.map { CivsAutonomousGeometry.futureBounds(other, it) } +
                listOfNotNull(other.construction?.plannedBuilding?.takeIf { it.id != building.id }?.bounds) +
                CivsSettlementPlans.current(other)?.plots.orEmpty().filter { it.building.id != building.id }.map { it.envelope }
        }
        require(operating.none { (x, z) -> others.any { x in it.min.x..it.max.x && z in it.min.z..it.max.z } }, "other-building-or-reservation")
        val routes = access.routes.values.flatten() + access.standing(city, building.id).values +
            CivsSettlementPlans.current(city)?.routes.orEmpty().values.flatten()
        require(city.buildings.mapNotNull { it.control }.none { (it.x to it.z) in operating }, "current-control")
        require(routes.none { (it.x to it.z) in columns } &&
            access.reservedFortificationPorts.none { box -> field.any { CivsAutonomousGeometry.horizontalContains(box, it) } } &&
            access.infrastructure.none { piece -> field.any { CivsAutonomousGeometry.horizontalContains(piece.bounds, it) } }, "route-or-port")
        fun read(point: CivPosition): Block? {
            if (!city.contains(point.x, point.z) || point.y !in world.minHeight until world.maxHeight ||
                !world.isChunkLoaded(point.x shr 4, point.z shr 4)) return null
            val block = world.getBlockAt(point.x, point.y, point.z)
            return block.takeIf { world.worldBorder.isInside(it.location) && it.getState(false) !is TileState && canModify(it, null) }
        }
        fun clear(point: CivPosition): Boolean = read(point)?.let { (it.type.isAir || it.type in weeds) && !it.isLiquid } == true
        val added = CivsFarmFields.added(building)
        val water = CivsFarmFields.water(building).toSet()
        val crop = CivsFarmWorksite.cropAndSeed(building).first
        for (point in added) {
            val ground = read(point)
            require(ground != null && ground.type in naturalSoils && canModify(ground, if (point in water) Material.WATER else Material.FARMLAND) &&
                read(point.copy(y = point.y - 1))?.let(CivsTerrainPolicy::fullTerraformSupport) == true &&
                clear(point.copy(y = point.y + 1)) && clear(point.copy(y = point.y + 2)), "new-column")
            require(point in water || read(point.copy(y = point.y + 1))?.let { canModify(it, crop) } == true, "crop-permission")
        }
        for (point in CivsFarmFields.draining(building)) {
            val ground = read(point)
            val top = read(point.copy(y = point.y + 1))
            require(ground != null && ground.type == Material.FARMLAND && top != null && (top.type == crop || top.type.isAir) &&
                canModify(ground, Material.WATER) && canModify(top, Material.AIR) &&
                read(point.copy(y = point.y - 1))?.let(CivsTerrainPolicy::fullTerraformSupport) == true &&
                clear(point.copy(y = point.y + 2)), "retired-crop")
        }
        val originalWater = CivsWorksiteLayouts.points(building, "farm", "irrigationCells").singleOrNull()
        require(originalWater != null && read(originalWater)?.type == Material.WATER &&
            read(originalWater.copy(y = originalWater.y + 1))?.type?.isAir == true &&
            clear(originalWater.copy(y = originalWater.y + 2)), "original-water")
        require(water.all { waterSafe(building, it, ::read, requireClearTop = false) }, "water-banks")
        require(walkableRim(perimeter, ::read, ::clear), "perimeter-access")
    }

    private fun walkableRim(perimeter: List<CivPosition>, read: (CivPosition) -> Block?,
        clear: (CivPosition) -> Boolean): Boolean {
        val floors = perimeter.associate { base ->
            if (read(base)?.let(CivsTerrainPolicy::fullTerraformSupport) != true) return false
            val floor = (0..2).firstNotNullOfOrNull { rise ->
                val point = base.copy(y = base.y + rise)
                val supported = (1..rise).all { step ->
                    read(base.copy(y = base.y + step))?.let { it.type in naturalSoils &&
                        CivsTerrainPolicy.fullTerraformSupport(it) } == true
                }
                point.takeIf { supported && clear(it.copy(y = it.y + 1)) && clear(it.copy(y = it.y + 2)) }
            } ?: return false
            (floor.x to floor.z) to floor
        }
        // Every rim column needs a real approach from field grade. A steep
        // corner is fine if the other side reaches it in one-block steps.
        val fieldY = perimeter.first().y
        val start = floors.values.firstOrNull { it.y == fieldY } ?: return false
        val visited = hashSetOf(start)
        val open = ArrayDeque<CivPosition>().apply { addLast(start) }
        while (open.isNotEmpty()) {
            val point = open.removeFirst()
            for ((dx, dz) in sides) {
                val other = floors[(point.x + dx) to (point.z + dz)] ?: continue
                if (other in visited || kotlin.math.abs(point.y - other.y) > 1) continue
                if (point.y != other.y) {
                    val lower = if (point.y < other.y) point else other
                    if (!clear(lower.copy(y = lower.y + 3))) continue
                }
                visited += other
                open.addLast(other)
            }
        }
        return visited.size == floors.size
    }

    /** Rechecked by ordinary work validation; no water may flow through a missing bed or outside bank. */
    fun waterSafe(building: CivBuilding, point: CivPosition, read: (CivPosition) -> Block?, requireClearTop: Boolean = true): Boolean {
        val water = CivsFarmFields.water(building).toSet()
        if (point !in water || read(point.copy(y = point.y - 1))?.let(CivsTerrainPolicy::fullTerraformSupport) != true) return false
        if (requireClearTop && read(point.copy(y = point.y + 1))?.type?.isAir != true) return false
        return sides.all { (dx, dz) ->
            val neighbor = point.copy(x = point.x + dx, z = point.z + dz)
            val bank = read(neighbor) ?: return@all false
            if (neighbor in water) bank.type == Material.WATER || bank.type in soils
            else bank.type in soils && CivsTerrainPolicy.fullTerraformSupport(bank)
        }
    }
}
