package ru.arc.civs

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.Ageable
import org.bukkit.block.data.BlockData
import org.bukkit.block.structure.StructureRotation
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import org.mockbukkit.mockbukkit.block.BlockMock
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.block.data.BlockDataMock
import org.mockbukkit.mockbukkit.world.Coordinate
import org.mockbukkit.mockbukkit.world.WorldMock

/** Only the native collision/drop operations absent from the pinned MockBukkit are supplied here.
 * BlockData, mutations, loaded chunks and the world remain ordinary MockBukkit state.
 * This is a stone-plane fixture, not evidence of native growth or Citizens navigation. */
internal class CivsWorksiteWorldFixture(name: String) : WorldMock(Material.STONE, 63) {
    private val cells = hashMapOf<Coordinate, BlockMock>()
    init { setName(name) }

    override fun getBlockAt(x: Int, y: Int, z: Int): BlockMock = getBlockAt(Coordinate(x, y, z))

    override fun getBlockAt(point: Coordinate): BlockMock = cells.getOrPut(point) {
        object : BlockMock(if (point.y <= 63) Material.STONE else Material.AIR,
            Location(this, point.x.toDouble(), point.y.toDouble(), point.z.toDouble())) {
            override fun getBoundingBox(): BoundingBox {
                val height = when {
                    type == Material.FARMLAND -> 15.0 / 16.0
                    type == Material.LADDER -> return BoundingBox(x.toDouble(), y.toDouble(), z + 0.875,
                        x + 1.0, y + 1.0, z + 1.0)
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
                type == Material.COBBLESTONE || type.name.endsWith("_LOG") -> listOf(ItemStack(type))
                else -> error("Unspecified fixture drops for $type")
            }
        }
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
