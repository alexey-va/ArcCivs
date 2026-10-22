package ru.arc.civs

import org.bukkit.Location
import org.bukkit.FluidCollisionMode
import org.bukkit.Material
import org.bukkit.block.data.Ageable
import org.bukkit.block.data.BlockData
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
                val bounds = boundingBox
                val boxes = if (bounds.height == 0.0) emptyList() else listOf(bounds.shift(-x.toDouble(), -y.toDouble(), -z.toDouble()))
                return object : VoxelShape {
                    override fun getBoundingBoxes(): Collection<BoundingBox> = boxes
                    override fun overlaps(other: BoundingBox): Boolean = boxes.any { it.overlaps(other) }
                }
            }

            override fun getBoundingBox(): BoundingBox {
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
                type in setOf(Material.COBBLESTONE, Material.LADDER, Material.TORCH) || type.name.endsWith("_LOG") -> listOf(ItemStack(type))
                else -> error("Unspecified fixture drops for $type")
            }
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
 * Supply only the three mine placement states; unknown states still use its strict parser. */
internal class CivsWorksiteServerFixture : ServerMock() {
    override fun createBlockData(data: String): BlockData {
        val material = when {
            data.startsWith("minecraft:ladder[") -> Material.LADDER
            data == "minecraft:cobblestone" -> Material.COBBLESTONE
            data == "minecraft:torch" -> Material.TORCH
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
            if (material != Material.LADDER || rotation == StructureRotation.NONE) return
            val faces = listOf("north", "east", "south", "west")
            val facing = Regex("facing=([a-z]+)").find(state)!!.groupValues[1]
            val turns = when (rotation) { StructureRotation.CLOCKWISE_90 -> 1; StructureRotation.CLOCKWISE_180 -> 2; else -> 3 }
            state = state.replace("facing=$facing", "facing=${faces[(faces.indexOf(facing) + turns) % 4]}")
        }
    }
}
