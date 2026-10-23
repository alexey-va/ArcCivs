package ru.arc.civs

import org.bukkit.Location
import org.bukkit.Tag
import org.bukkit.block.data.type.Door

/** Explicit operator capture only: a fixed neighbourhood, with no chunk admission, NBT or inventories. */
internal object CivsTerrainDebug {
    private data class PaletteEntry(val blockData: String, val boxes: List<CitizenTerrainBox>,
        val climbable: Boolean, val hot: Boolean, val liquid: Boolean, val door: Boolean)

    fun capture(city: Settlement, worker: CivWorker, actual: Location?, work: CivWorldWork?, now: Long): Map<String, Any?> {
        val started = System.nanoTime()
        val header = linkedMapOf<String, Any?>("source" to "fresh-native-command", "city" to city.id,
            "worker" to worker.id, "building" to worker.assignment, "captured_at_ms" to now)
        val world = actual?.world ?: return header + ("error" to "resident-unavailable")
        if (world.uid != city.worldId) return header + ("error" to "resident-world-mismatch")
        if (!actual.x.isFinite() || !actual.y.isFinite() || !actual.z.isFinite())
            return header + ("error" to "invalid-resident-position")
        val center = CivPosition(actual.blockX, actual.blockY, actual.blockZ)
        if (center.y !in world.minHeight until world.maxHeight)
            return header + ("error" to "resident-outside-world")
        val min = CivPosition(center.x - 4, (center.y - 5).coerceAtLeast(world.minHeight), center.z - 4)
        val max = CivPosition(center.x + 4, (center.y + 5).coerceAtMost(world.maxHeight - 1), center.z + 4)
        val palette = linkedMapOf<PaletteEntry, Int>()
        val voxels = ArrayList<List<Any?>>(891)
        var unavailable = 0
        for (x in min.x..max.x) for (z in min.z..max.z) {
            val loaded = world.isChunkLoaded(x shr 4, z shr 4)
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
                }, Tag.CLIMBABLE.isTagged(block.type), citizenCampfireLit(block), block.isLiquid, data is Door)
                val index = palette.getOrPut(entry) { palette.size }
                voxels += listOf(x - min.x, y - min.y, z - min.z, index, true)
            }
        }
        val progress = worker.assignment?.let { city.minePlans?.get(it.toString()) }
        return header + linkedMapOf("world" to world.uid, "actual" to listOf(actual.x, actual.y, actual.z),
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
