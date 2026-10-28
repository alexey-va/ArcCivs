package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Tag

/** Explicit operator capture only: a fixed neighbourhood, with no chunk admission, NBT or inventories. */
internal object CivsTerrainDebug {
    private data class PaletteEntry(val blockData: String, val boxes: List<CitizenTerrainBox>,
        val climbable: Boolean, val hot: Boolean, val liquid: Boolean, val door: Boolean)

    fun requestedBounds(city: Settlement, origin: CivPosition, minimumY: Int, maximumY: Int): CivBox {
        val low = listOf(origin.x.toLong() - 4, origin.y.toLong() - 5, origin.z.toLong() - 4)
        val high = listOf(origin.x.toLong() + 4, origin.y.toLong() + 5, origin.z.toLong() + 4)
        if ((low + high).any { it !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() })
            throw CivsRejection("capture-bounds-overflow")
        if (low[1] < minimumY || high[1] >= maximumY) throw CivsRejection("capture-bounds-outside-world")
        val min = CivPosition(low[0].toInt(), low[1].toInt(), low[2].toInt())
        val max = CivPosition(high[0].toInt(), high[1].toInt(), high[2].toInt())
        if (listOf(min.x, max.x).any { x -> listOf(min.z, max.z).any { z -> !city.contains(x, z) } })
            throw CivsRejection("capture-outside-city")
        return CivBox(min, max)
    }

    fun capture(city: Settlement, worker: CivWorker, actual: Location?, work: CivWorldWork?, now: Long,
        origin: CivPosition? = null): Map<String, Any?> {
        check(Bukkit.isPrimaryThread())
        val started = System.nanoTime()
        val header = linkedMapOf<String, Any?>("source" to "fresh-native-command", "city" to city.id,
            "worker" to worker.id, "building" to worker.assignment, "captured_at_ms" to now,
            "actual" to actual?.let { listOf(it.x, it.y, it.z) })
        if (origin != null) {
            header["focus_origin"] = "operator-request"
            header["capture_center"] = origin
            if (city.workers.none { it.id == worker.id }) return header + ("error" to "worker-not-found")
        }
        val world = actual?.world ?: Bukkit.getWorld(city.worldId) ?: return header + ("error" to "world-unloaded")
        if (world.uid != city.worldId) return header + ("error" to "resident-world-mismatch")
        if (actual != null && (!actual.x.isFinite() || !actual.y.isFinite() || !actual.z.isFinite()))
            return header + ("error" to "invalid-resident-position")
        val planned = if (actual == null && origin == null) citizenPlan(city, worker)?.spawnOrigin else null
        val home = worker.homeId?.let(city::building)?.let { it.control ?: it.entrance }
        val depot = city.buildings.firstOrNull(CivsRules::isDepot)?.let { it.control ?: it.entrance }
        val center = origin ?: actual?.let { CivPosition(it.blockX, it.blockY, it.blockZ) } ?: planned ?: home ?: depot ?: city.center
        header["focus_origin"] = when {
            origin != null -> "operator-request"
            actual != null -> "resident-feet"
            planned != null -> "planned-spawn"
            home != null -> "home"
            depot != null -> "depot"
            else -> "settlement-center"
        }
        header["capture_center"] = center
        if (origin == null && center.y !in world.minHeight until world.maxHeight)
            return header + ("error" to if (actual == null) "capture-origin-outside-world" else "resident-outside-world")
        val bounds = if (origin == null) CivBox(
            CivPosition(center.x - 4, (center.y - 5).coerceAtLeast(world.minHeight), center.z - 4),
            CivPosition(center.x + 4, (center.y + 5).coerceAtMost(world.maxHeight - 1), center.z + 4))
        else try { requestedBounds(city, origin, world.minHeight, world.maxHeight) }
            catch (failure: CivsRejection) { return header + ("error" to failure.reason) }
        val min = bounds.min
        val max = bounds.max
        val palette = linkedMapOf<PaletteEntry, Int>()
        val voxels = ArrayList<List<Any?>>(891)
        val chunks = linkedMapOf<Pair<Int, Int>, Chunk.LoadLevel?>()
        if (origin != null) {
            for (x in (min.x shr 4)..(max.x shr 4)) for (z in (min.z shr 4)..(max.z shr 4))
                chunks[x to z] = citizenChunkLoadLevel(world, x, z)
            if (chunks.values.any { it != Chunk.LoadLevel.ENTITY_TICKING }) return header + mapOf(
                "error" to "chunks-unloaded", "world" to world.uid,
                "required_load_level" to Chunk.LoadLevel.ENTITY_TICKING,
                "bounds" to mapOf("min" to min, "max" to max),
                "chunks" to chunks.map { (key, level) -> mapOf("x" to key.first, "z" to key.second,
                    "loaded" to (level != null), "load_level" to level) })
        }
        var unavailable = 0
        for (x in min.x..max.x) for (z in min.z..max.z) {
            val key = (x shr 4) to (z shr 4)
            if (!chunks.containsKey(key)) chunks[key] = citizenChunkLoadLevel(world, key.first, key.second)
            val loaded = chunks[key] != null
            for (y in min.y..max.y) {
                if (!loaded) {
                    unavailable++
                    voxels += listOf(x - min.x, y - min.y, z - min.z, null, false)
                    continue
                }
                val block = world.getBlockAt(x, y, z)
                val data = block.blockData
                val entry = PaletteEntry(data.asString, block.collisionShape.boundingBoxes.map {
                    CitizenTerrainBox(it.minX, it.minY, it.minZ, it.maxX, it.maxY, it.maxZ)
                }, Tag.CLIMBABLE.isTagged(block.type), citizenCampfireLit(block), block.isLiquid, citizenRouteOpenable(data) != null)
                val index = palette.getOrPut(entry) { palette.size }
                voxels += listOf(x - min.x, y - min.y, z - min.z, index, true)
            }
        }
        val progress = worker.assignment?.let { city.minePlans?.get(it.toString()) }
        return header + linkedMapOf("world" to world.uid,
            "required_load_level" to Chunk.LoadLevel.ENTITY_TICKING,
            "chunks" to chunks.map { (key, level) -> mapOf("x" to key.first, "z" to key.second,
                "loaded" to (level != null), "load_level" to level) },
            "focus" to work?.let(::citizenWorkFocus), "standing" to work?.standing,
            "geometry_version" to (work?.miningPlanVersion ?: progress?.version ?:
                worker.assignment?.let(city::building)?.takeIf { it.type == BuildingType.QUARRY }?.let { CivsMinePlan.VERSION }),
            "mining_step" to (work?.miningStep ?: progress?.nextStep),
            "bounds" to mapOf("min" to min, "max" to max),
            "size" to listOf(max.x - min.x + 1, max.y - min.y + 1, max.z - min.z + 1),
            "voxel_coordinates" to "offset-from-bounds-min", "voxel_fields" to listOf("x", "y", "z", "palette", "available"),
            "collision_coordinates" to "block-local", "collision_fields" to listOf("min_x", "min_y", "min_z", "max_x", "max_y", "max_z"),
            "palette" to palette.keys.map { entry -> mapOf("block_data" to entry.blockData,
                "collision" to entry.boxes.map { listOf(it.minX, it.minY, it.minZ, it.maxX, it.maxY, it.maxZ) },
                "climbable" to entry.climbable, "hot" to entry.hot, "liquid" to entry.liquid, "door" to entry.door) },
            "voxels" to voxels, "voxel_count" to voxels.size, "unavailable_count" to unavailable,
            "capture_elapsed_nanos" to (System.nanoTime() - started).coerceAtLeast(0))
    }
}
