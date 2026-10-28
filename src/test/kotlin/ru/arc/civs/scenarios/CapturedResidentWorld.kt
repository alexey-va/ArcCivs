package ru.arc.civs.scenarios

import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import org.bukkit.util.VoxelShape
import org.mockbukkit.mockbukkit.block.BlockMock
import org.mockbukkit.mockbukkit.world.Coordinate
import ru.arc.civs.CivPosition
import ru.arc.civs.ResidentRuntimeWorld

/** Native states/colliders for the existing real Citizens runtime fixture.
 * Synthetic origin cells exist only during that fixture's initial bootstrap.
 * Activate [strict] before the scenario clock starts; every unknown read then fails. */
internal class CapturedResidentWorld(val capture: RecordedNativeMap,
    private val nativeStateFacts: Map<String, List<BoundingBox>> = emptyMap(),
    private val declaredContinuation: Map<CivPosition, String> = emptyMap()) : ResidentRuntimeWorld() {
    private val nativeCells = hashMapOf<Coordinate, BlockMock>()
    private val nativeData: Map<String, BlockData>
    val rejectedNativeReads = linkedSetOf<String>()
    var lastRejectedNativeRead: Map<String, Any?>? = null
        private set
    var strict = false

    private fun missingNative(message: String): Nothing {
        if (rejectedNativeReads.size < 8) rejectedNativeReads += message
        lastRejectedNativeRead = mapOf("reason" to message, "callers" to Throwable().stackTrace
            .filter { it.className.startsWith("ru.arc.civs.") && !it.className.contains("CapturedResidentWorld") }.take(5).map { it.toString() })
        error(message)
    }

    init {
        capture.loadedChunks.forEach { (x, z) -> loadChunk(x, z) }
        val palette = (capture.states.values + declaredContinuation.values).toSet().associateWith { state -> runCatching { Bukkit.createBlockData(state) } }
        val unsupported = palette.filterValues { it.isFailure }
        check(unsupported.isEmpty()) { "Unsupported captured native state parsing: " + unsupported.entries.joinToString("; ") {
            (state, failure) -> "$state: ${failure.exceptionOrNull()?.message}"
        } }
        nativeData = palette.mapValues { it.value.getOrThrow() }
        for ((state, boxes) in nativeStateFacts) nativeData[state]?.let { data ->
            val known = capture.collision(data)
            check(known == null || known.toSet() == boxes.toSet()) { "Supplemental native collider conflicts with the covered state $state" }
        }
    }

    override fun getBlockAt(x: Int, y: Int, z: Int): BlockMock = getBlockAt(Coordinate(x, y, z))

    override fun getBlockAt(point: Coordinate): BlockMock {
        val p = CivPosition(point.x, point.y, point.z)
        val recorded = capture.states[p] ?: declaredContinuation[p]
        if (recorded == null) {
            if (strict) missingNative("Missing native capture cell x=${point.x} y=${point.y} z=${point.z}")
            return super.getBlockAt(point)
        }
        return nativeCells.getOrPut(point) {
            object : BlockMock(Material.AIR, Location(this, point.x.toDouble(), point.y.toDouble(), point.z.toDouble())) {
                override fun isPassable(): Boolean = collisionShape.boundingBoxes.isEmpty()
                override fun getCollisionShape(): VoxelShape {
                    val boxes = capture.collision(blockData) ?: nativeStateFacts[blockData.asString]
                        ?: missingNative("Missing native collision facts for ${blockData.asString} at $p")
                    return object : VoxelShape {
                        override fun getBoundingBoxes(): Collection<BoundingBox> = boxes
                        override fun overlaps(other: BoundingBox): Boolean = boxes.any { it.overlaps(other) }
                    }
                }
                override fun getBoundingBox(): BoundingBox {
                    val boxes = collisionShape.boundingBoxes
                    if (boxes.isEmpty()) return BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y.toDouble(), z + 1.0)
                    return BoundingBox(x + boxes.minOf { it.minX }, y + boxes.minOf { it.minY }, z + boxes.minOf { it.minZ },
                        x + boxes.maxOf { it.maxX }, y + boxes.maxOf { it.maxY }, z + boxes.maxOf { it.maxZ })
                }
                // The same bounded samples as CivsWorksiteWorldFixture; Auto stock is separate from native drops.
                override fun getDrops(tool: ItemStack?): Collection<ItemStack> = when {
                    type.name.endsWith("_LOG") || type == Material.STONE -> listOf(ItemStack(if (type == Material.STONE) Material.COBBLESTONE else type))
                    type.name.endsWith("_LEAVES") -> emptyList()
                    else -> error("Unspecified captured-world drop sample for $type at $p")
                }
            }.also { it.blockData = nativeData.getValue(recorded).clone() }
        }
    }

    override fun getHighestBlockYAt(x: Int, z: Int): Int = getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING)
    override fun getHighestBlockYAt(x: Int, z: Int, heightMap: HeightMap): Int {
        if (!strict && capture.states.keys.none { it.x == x && it.z == z }) return super.getHighestBlockYAt(x, z, heightMap)
        return capture.column(x, z).firstOrNull { p ->
            val block = getBlockAt(p.x, p.y, p.z)
            when (heightMap) {
                HeightMap.WORLD_SURFACE -> !block.type.isAir
                HeightMap.MOTION_BLOCKING -> block.type.isSolid || block.isLiquid
                HeightMap.MOTION_BLOCKING_NO_LEAVES -> (block.type.isSolid || block.isLiquid) && !block.type.name.endsWith("_LEAVES")
                else -> error("Unspecified captured-world height map $heightMap")
            }
        }?.y ?: error("No native $heightMap surface captured at x=$x z=$z")
    }

    override fun rayTraceBlocks(start: Location, direction: Vector, maxDistance: Double,
        fluidCollisionMode: FluidCollisionMode, ignorePassableBlocks: Boolean): RayTraceResult? {
        val end = start.clone().add(direction.clone().normalize().multiply(maxDistance))
        var nearest: RayTraceResult? = null
        for (x in minOf(start.blockX, end.blockX)..maxOf(start.blockX, end.blockX))
            for (y in minOf(start.blockY, end.blockY)..maxOf(start.blockY, end.blockY))
                for (z in minOf(start.blockZ, end.blockZ)..maxOf(start.blockZ, end.blockZ)) {
                    val block = getBlockAt(x, y, z)
                    if (block.type.isAir || ignorePassableBlocks && block.isPassable && !block.isLiquid) continue
                    val boxes = if (block.isLiquid && fluidCollisionMode != FluidCollisionMode.NEVER)
                        listOf(BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0))
                    else block.collisionShape.boundingBoxes.map { it.clone().shift(x.toDouble(), y.toDouble(), z.toDouble()) }
                    for (box in boxes) {
                        val hit = box.rayTrace(start.toVector(), direction, maxDistance) ?: continue
                        if (nearest == null || hit.hitPosition.distanceSquared(start.toVector()) < nearest.hitPosition.distanceSquared(start.toVector()))
                            nearest = RayTraceResult(hit.hitPosition, block, hit.hitBlockFace)
                    }
                }
        return nearest
    }
}
