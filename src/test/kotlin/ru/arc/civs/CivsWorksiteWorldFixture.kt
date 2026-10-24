package ru.arc.civs

import org.bukkit.Location
import org.bukkit.FluidCollisionMode
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Ageable
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Gate
import org.bukkit.block.structure.StructureRotation
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Vector
import org.bukkit.util.VoxelShape
import org.mockbukkit.mockbukkit.block.BlockMock
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.block.data.BlockDataMock
import org.mockbukkit.mockbukkit.world.Coordinate
import org.mockbukkit.mockbukkit.world.WorldMock

private val MINE_TIMBER_FENCE_STATE = Regex(
    "minecraft:(oak|spruce|dark_oak)_fence\\[east=(true|false),north=(true|false)," +
        "south=(true|false),waterlogged=false,west=(true|false)]",
)

/** Only the native collision/drop operations absent from the pinned MockBukkit are supplied here.
 * BlockData, mutations, loaded chunks and the world remain ordinary MockBukkit state.
 * This is a stone-plane fixture, not evidence of native growth or resident navigation. */
internal class CivsWorksiteWorldFixture(name: String, private val ground: Material = Material.STONE) : WorldMock(ground, 63) {
    private val cells = hashMapOf<Coordinate, BlockMock>()
    var rayTraces = 0
        private set
    init { setName(name) }

    override fun getHighestBlockYAt(x: Int, z: Int): Int =
        (maxOf(63, cells.keys.filter { it.x == x && it.z == z }.maxOfOrNull { it.y } ?: 63) downTo minHeight)
            .first { !getBlockAt(x, it, z).type.isAir }

    override fun getBlockAt(x: Int, y: Int, z: Int): BlockMock = getBlockAt(Coordinate(x, y, z))

    override fun getBlockAt(point: Coordinate): BlockMock = cells.getOrPut(point) {
        object : BlockMock(if (point.y <= 63) ground else Material.AIR,
            Location(this, point.x.toDouble(), point.y.toDouble(), point.z.toDouble())) {
            // CraftBlock uses an empty collision shape, not Material.isSolid(). A ladder
            // is nonpassable even though a centered resident fits beside its thin shape.
            override fun isPassable(): Boolean = collisionShape.boundingBoxes.isEmpty()

            override fun getCollisionShape(): VoxelShape {
                val boxes = if (type.name.endsWith("_FENCE")) {
                    fenceCollisionBoxes(blockData.asString)
                } else if (blockData is Gate) {
                    // Purpur 1.21.11 native FenceGateBlock probe: closed height 1.5, open empty.
                    val gate = blockData as Gate
                    if (gate.isOpen) emptyList()
                    else if (gate.facing in setOf(BlockFace.NORTH, BlockFace.SOUTH))
                        listOf(BoundingBox(0.0, 0.0, .375, 1.0, 1.5, .625))
                    else listOf(BoundingBox(.375, 0.0, 0.0, .625, 1.5, 1.0))
                } else {
                    val bounds = boundingBox
                    if (bounds.height == 0.0) emptyList() else listOf(bounds.shift(-x.toDouble(), -y.toDouble(), -z.toDouble()))
                }
                return object : VoxelShape {
                    override fun getBoundingBoxes(): Collection<BoundingBox> = boxes
                    override fun overlaps(other: BoundingBox): Boolean = boxes.any { it.overlaps(other) }
                }
            }

            override fun getBoundingBox(): BoundingBox {
                if (type.name.endsWith("_FENCE")) {
                    val boxes = fenceCollisionBoxes(blockData.asString)
                    return BoundingBox(
                        x + boxes.minOf { it.minX }, y + boxes.minOf { it.minY }, z + boxes.minOf { it.minZ },
                        x + boxes.maxOf { it.maxX }, y + boxes.maxOf { it.maxY }, z + boxes.maxOf { it.maxZ },
                    )
                }
                val height = when {
                    type == Material.FARMLAND || type == Material.DIRT_PATH -> 15.0 / 16.0
                    // Paper 1.21.11 LadderBlock: full height/width and 3/16 thickness.
                    type == Material.LADDER -> return when (Regex("facing=([a-z]+)").find(blockData.asString)?.groupValues?.get(1)) {
                        "south" -> BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 3.0 / 16.0)
                        "west" -> BoundingBox(x + 13.0 / 16.0, y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0)
                        "east" -> BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 3.0 / 16.0, y + 1.0, z + 1.0)
                        else -> BoundingBox(x.toDouble(), y.toDouble(), z + 13.0 / 16.0, x + 1.0, y + 1.0, z + 1.0)
                    }
                    type.isSolid -> 1.0
                    else -> 0.0
                }
                return BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + height, z + 1.0)
            }

            override fun getDrops(tool: ItemStack?): Collection<ItemStack> = when {
                type == Material.WHEAT -> {
                    val age = blockData as Ageable
                    if (age.age == age.maximumAge) listOf(ItemStack(Material.WHEAT), ItemStack(Material.WHEAT_SEEDS, 2))
                    else listOf(ItemStack(Material.WHEAT_SEEDS))
                }
                type == Material.STONE -> listOf(ItemStack(Material.COBBLESTONE))
                // Deterministic native drop sample; production still owns gravel/flint RNG.
                type == Material.GRAVEL -> listOf(ItemStack(Material.GRAVEL))
                type in setOf(
                    Material.COBBLESTONE, Material.LADDER, Material.TORCH,
                    Material.OAK_PLANKS, Material.SPRUCE_PLANKS, Material.DARK_OAK_PLANKS,
                    Material.OAK_FENCE, Material.SPRUCE_FENCE, Material.DARK_OAK_FENCE,
                ) || type.name.endsWith("_LOG") -> listOf(ItemStack(type))
                else -> error("Unspecified fixture drops for $type")
            }
        }
    }

    /** Paper 1.21.11 FenceBlock collision: a 4×24×4 post plus the connected 4×24×8 arms. */
    private fun fenceCollisionBoxes(state: String): List<BoundingBox> {
        val fence = MINE_TIMBER_FENCE_STATE.matchEntire(state)
            ?: error("Unspecified fixture fence state: $state")
        val connections = mapOf(
            "north" to (fence.groupValues[3] == "true"),
            "east" to (fence.groupValues[2] == "true"),
            "south" to (fence.groupValues[4] == "true"),
            "west" to (fence.groupValues[5] == "true"),
        )
        val sixteenth = 1.0 / 16.0
        fun box(minX: Int, minY: Int, minZ: Int, maxX: Int, maxY: Int, maxZ: Int) = BoundingBox(
            minX * sixteenth, minY * sixteenth, minZ * sixteenth,
            maxX * sixteenth, maxY * sixteenth, maxZ * sixteenth,
        )
        return buildList {
            // Block.column(4, 0, 24): x/z 6..10, y 0..24.
            add(box(6, 0, 6, 10, 24, 10))
            // Block.boxZ(4, 0, 24, 0, 8), rotated to each connected cardinal side.
            if (connections.getValue("north")) add(box(6, 0, 0, 10, 24, 8))
            if (connections.getValue("east")) add(box(8, 0, 6, 16, 24, 10))
            if (connections.getValue("south")) add(box(6, 0, 8, 10, 24, 16))
            if (connections.getValue("west")) add(box(0, 0, 6, 8, 24, 10))
        }
    }

    /** Native box intersection supplies the ray API missing from pinned MockBukkit. */
    override fun rayTraceBlocks(start: Location, direction: Vector, maxDistance: Double,
        fluidCollisionMode: FluidCollisionMode, ignorePassableBlocks: Boolean): RayTraceResult? {
        rayTraces++
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

/** The pinned parser omits ladder properties and every BlockData.rotate operation.
 * Supply only ladder and V5 timber mine states; unknown states still use its strict parser. */
internal class CivsWorksiteServerFixture : ServerMock() {
    override fun createBlockData(data: String): BlockData {
        val timberState = TIMBER_LOG_STATE.matchEntire(data)
            ?: TIMBER_PLANKS_STATE.matchEntire(data)
            ?: MINE_TIMBER_FENCE_STATE.matchEntire(data)
        val material = when {
            data.startsWith("minecraft:ladder[") -> Material.LADDER
            data == "minecraft:cobblestone" -> Material.COBBLESTONE
            data == "minecraft:torch" -> Material.TORCH
            timberState != null -> {
                val timber = timberState.groupValues[1]
                val part = when {
                    TIMBER_LOG_STATE.matches(data) -> "LOG"
                    TIMBER_PLANKS_STATE.matches(data) -> "PLANKS"
                    else -> "FENCE"
                }
                Material.valueOf("${timber.uppercase()}_$part")
            }
            else -> return super.createBlockData(data)
        }
        return MineDataFixture(material, data)
    }

    private class MineDataFixture(material: Material, private var state: String) : BlockDataMock(material) {
        override fun getAsString(): String = state
        override fun getAsString(hideUnspecified: Boolean): String = state
        override fun matches(other: BlockData?): Boolean = other?.asString == state
        override fun clone(): BlockDataMock = MineDataFixture(material, state)
        override fun rotate(rotation: StructureRotation) {
            if (rotation == StructureRotation.NONE) return
            val faces = listOf("north", "east", "south", "west")
            val turns = when (rotation) { StructureRotation.CLOCKWISE_90 -> 1; StructureRotation.CLOCKWISE_180 -> 2; else -> 3 }
            when {
                material == Material.LADDER -> {
                    val facing = Regex("facing=([a-z]+)").find(state)!!.groupValues[1]
                    state = state.replace("facing=$facing", "facing=${faces[(faces.indexOf(facing) + turns) % 4]}")
                }
                material.name.endsWith("_FENCE") -> {
                    val fence = MINE_TIMBER_FENCE_STATE.matchEntire(state)
                        ?: error("Unspecified fixture fence state: $state")
                    val old = mapOf(
                        "east" to (fence.groupValues[2] == "true"),
                        "north" to (fence.groupValues[3] == "true"),
                        "south" to (fence.groupValues[4] == "true"),
                        "west" to (fence.groupValues[5] == "true"),
                    )
                    val rotated = faces.associateWith { false }.toMutableMap()
                    old.forEach { (face, connected) ->
                        if (connected) rotated[faces[(faces.indexOf(face) + turns) % 4]] = true
                    }
                    state = "minecraft:${fence.groupValues[1]}_fence[east=${rotated.getValue("east")}," +
                        "north=${rotated.getValue("north")},south=${rotated.getValue("south")}," +
                        "waterlogged=false,west=${rotated.getValue("west")}]"
                }
                // V5 logs are axis=y and planks have no directional properties.
            }
        }
    }

    private companion object {
        val TIMBER_LOG_STATE = Regex("minecraft:(oak|spruce|dark_oak)_log\\[axis=y]")
        val TIMBER_PLANKS_STATE = Regex("minecraft:(oak|spruce|dark_oak)_planks")
    }
}
