package ru.arc.civs

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import ru.arc.civs.CivsTerrainPolicy.checkAutonomousTerrain
import ru.arc.civs.CivsTerrainPolicy.naturalTerraform
import ru.arc.civs.CivsTerrainPolicy.fullTerraformSupport
import ru.arc.civs.CivsTerrainPolicy.passableTerraform
import ru.arc.civs.CivsTerrainPolicy.MAXIMUM_TREE_READS

/** Captures settlement surface columns, including any explicitly requested water-depth probes. */
internal class AutonomousHeightField(access: CivsWorld, private val citySnapshot: Settlement, envelope: CivBuilding,
    private val previous: CivBuilding?, private val waterColumns: Set<Long>,
    private val prospective: Boolean = false, private val requested: List<CivPosition>? = null,
    private val localFootprint: Boolean = false, private val markWaterTop: Boolean = false) :
    CivsWorldScan<CivTerrainSurface>(access, citySnapshot.id, citySnapshot.owner, citySnapshot.worldId, envelope) {
    private val shore = requested != null && !prospective
    private val radius = citySnapshot.radius.coerceIn(0, 127)
    private val width = if (localFootprint) building.bounds.max.x - building.bounds.min.x + 1 else radius * 2 + 1
    private val depth = if (localFootprint) building.bounds.max.z - building.bounds.min.z + 1 else width
    private val area = requested?.size ?: (width * depth)
    // Packed adjacent X/Z keys collide under Long.hashCode; retain raster order with mixed long hashing.
    private val columns = Long2ObjectLinkedOpenHashMap<CivTerrainSurfaceColumn>()
    private var topY: Int? = null
    private var waterTopY: Int? = null
    private var probeY = 0
    private var probes = 0

    init { requireCivs(area <= CivsAutonomousTerraform.MAXIMUM_SURFACE_COLUMNS, "autonomy-search-budget") }
    override fun validateIdentity(city: Settlement?) {
        if (prospective) requireCivs(city == null, "stale-building")
        else requireUnchangedBuilding(city, previous)
    }
    override fun step() {
        if (index == area) return finish(CivTerrainSurface(world.minHeight, world.maxHeight, columns))
        val sample = requested?.get(index)
        val x = sample?.x ?: if (localFootprint) building.bounds.min.x + index % width
            else Math.addExact(citySnapshot.center.x, index % width - radius)
        val z = sample?.z ?: if (localFootprint) building.bounds.min.z + index / width
            else Math.addExact(citySnapshot.center.z, index / width - radius)
        val key = civsTerrainKey(x, z)
        fun complete(ground: Int, natural: Boolean, loaded: Boolean = true, water: CivTerrainSurfaceColumn? = null,
            dirtPath: Boolean = false, cleanGrass: Boolean = false) {
            columns[key] = water?.copy(topY = topY ?: ground) ?:
                CivTerrainSurfaceColumn(ground, natural, loaded, topY ?: ground, dirtPath = dirtPath, cleanGrass = cleanGrass)
            index++; topY = null; waterTopY = null; probes = 0
        }
        if (!world.isChunkLoaded(x shr 4, z shr 4) ||
            !world.worldBorder.isInside(Location(world, x + .5, building.anchor.y.toDouble(), z + .5))) {
            complete(world.minHeight, false, false)
            return
        }
        if (topY == null) {
            probeY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING)
            topY = probeY
            if (shore && probeY != sample?.y) { complete(probeY, false); return }
        }
        if (probeY !in world.minHeight until world.maxHeight) {
            complete(waterTopY ?: probeY, false)
            return
        }
        val block = read(CivPosition(x, probeY, z))
        probes++
        if ((prospective || shore) && !ownerCanModify(block)) {
            complete(probeY, false)
            return
        }
        val waterTop = waterTopY
        if (waterTop != null) {
            if (block.type == Material.WATER && waterTop.toLong() - probeY < CivsWorld.MAXIMUM_SURFACE_WATER_DEPTH) {
                probeY--
                return
            }
            val water = civsWaterSurfaceColumn(waterTop, probeY, block.type,
                naturalTerraform(block) && (!shore || fullTerraformSupport(block)))
            complete(waterTop, false, water = water ?: observedWater(waterTop))
            return
        }
        if (block.type == Material.WATER && (key in waterColumns || markWaterTop)) {
            if (key in waterColumns) { waterTopY = probeY; probeY-- }
            else complete(probeY, false, water = observedWater(probeY))
            return
        }
        if (shore) { complete(probeY, false); return }
        if (block.type == Material.DIRT_PATH) {
            complete(probeY, false, dirtPath = true)
            return
        }
        val canopy = Tag.LOGS.isTagged(block.type) || block.blockData is org.bukkit.block.data.type.Leaves
        if (!canopy && block.type.isSolid && naturalTerraform(block)) {
            complete(probeY, true, cleanGrass = block.type == Material.GRASS_BLOCK && topY == probeY)
            return
        }
        val clearable = naturalTerraform(block)
        if (!clearable || probes >= MAXIMUM_TREE_READS) complete(probeY, false) else probeY--
    }

    private fun observedWater(y: Int) = if (markWaterTop && y == topY)
        CivTerrainSurfaceColumn(y, natural = false, waterTopY = y) else null
}

/** Finds the lowest supporting column on a footprint whose heights differ by at most one. */
internal class AutonomousSurface(access: CivsWorld, city: Settlement, envelope: CivBuilding) :
    CivsWorldScan<Int>(access, city.id, city.owner, city.worldId, envelope) {
    private val width = building.bounds.max.x - building.bounds.min.x + 1
    private val area = width * (building.bounds.max.z - building.bounds.min.z + 1)
    private var low = Int.MAX_VALUE
    private var high = Int.MIN_VALUE
    override fun validateIdentity(city: Settlement?) = requireUnchangedBuilding(city, null)
    override fun step() {
        if (index == area) return finish(low)
        val x = building.bounds.min.x + index % width
        val z = building.bounds.min.z + index / width
        index++
        requireCivs(world.isChunkLoaded(x shr 4, z shr 4), "chunks-unloaded")
        // Heightmap and one block read are a fixed native unit, never a downward unbounded scan.
        val y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES)
        val surface = read(CivPosition(x, y, z))
        if (!surface.type.isSolid || !naturalTerraform(surface)) rejectAt("autonomy-terrain", surface)
        low = minOf(low, y); high = maxOf(high, y)
        requireCivs(high - low <= 1, "autonomy-slope")
    }
}

/** Checks support below the footprint, the target volume and the entrance approach. */
internal class AutonomousTerrain(access: CivsWorld, city: Settlement, envelope: CivBuilding) :
    CivsWorldScan<Unit>(access, city.id, city.owner, city.worldId, envelope) {
    private val width = building.bounds.max.x - building.bounds.min.x + 1
    private val depth = building.bounds.max.z - building.bounds.min.z + 1
    private val area = width * depth
    private val volume = civsBoxVolume(building.bounds).toInt()
    private val approach = CivsAutonomousGeometry.approach(building)
    override fun validateIdentity(city: Settlement?) = requireUnchangedBuilding(city, null)
    override fun step() {
        val cursor = index++
        if (cursor < area) {
            val block = read(CivPosition(building.bounds.min.x + cursor % width, building.bounds.min.y - 1,
                building.bounds.min.z + cursor / width))
            if (!block.type.isSolid || block.isLiquid || block.collisionShape.boundingBoxes.none { it.maxY >= 1.0 })
                rejectAt("autonomy-terrain", block)
            return
        }
        val cell = cursor - area
        if (cell < volume) {
            val block = read(CivPosition(building.bounds.min.x + cell % width,
                building.bounds.min.y + cell / area, building.bounds.min.z + cell / width % depth))
            checkAutonomousTerrain(block, building.anchor.y)
            if (!ownerCanModify(block)) rejectAt("lands-denied", block)
            return
        }
        val entry = cell - volume
        if (entry >= approach.size * 3) return finish(Unit)
        val p = approach[entry / 3]
        val offset = entry % 3 - 1
        val block = read(p.copy(y = p.y + offset))
        if (offset == -1) {
            if (!block.type.isSolid || block.isLiquid || block.collisionShape.boundingBoxes.none { it.maxY >= 1.0 })
                rejectAt("autonomy-terrain", block)
        } else if (!passableTerraform(block)) rejectAt("autonomy-terrain", block)
    }
}
