package ru.arc.civs

import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.TileState
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Waterlogged
import org.bukkit.block.data.type.Door
import org.bukkit.block.data.type.Gate
import org.bukkit.block.data.type.Fence
import org.bukkit.block.data.type.Leaves
import org.bukkit.block.data.type.Stairs
import kotlin.math.abs


/** Natural terrain and equivalent block states used by every bounded world operation. */
internal object CivsTerrainPolicy {
    fun checkAutonomousTerrain(block: Block, ground: Int) {
        if (block.type.isAir) return
        if (block.isLiquid || block.state is TileState ||
            block.type !in AUTONOMOUS_PLANTS &&
            !(block.type in AUTONOMOUS_SOIL && block.y <= ground + 1 || block.type in TERRAFORM_GROUND && block.y <= ground))
            throw CivsRejection("autonomy-terrain", mapOf("block" to block.type.name,
                "x" to block.x.toString(), "y" to block.y.toString(), "z" to block.z.toString()))
    }

    fun naturalLeaves(data: BlockData): Boolean = (data as? Leaves)?.isPersistent == false

    fun naturalTerraform(block: Block, tree: Boolean = false): Boolean = block.type.isAir ||
        (block.type in TERRAFORM_GROUND || block.type in AUTONOMOUS_PLANTS || naturalLeaves(block.blockData) ||
            tree && Tag.LOGS.isTagged(block.type)) && !block.isLiquid &&
        (block.blockData as? Waterlogged)?.isWaterlogged != true && block.state !is TileState

    fun fullTerraformSupport(block: Block): Boolean = block.type.isSolid && !block.isLiquid &&
        (block.blockData as? Waterlogged)?.isWaterlogged != true && block.collisionShape.boundingBoxes.any {
            it.minX <= 0.0 && it.minZ <= 0.0 && it.maxX >= 1.0 && it.maxZ >= 1.0 &&
                it.maxY >= if (block.type in setOf(Material.DIRT_PATH, Material.FARMLAND)) 15.0 / 16.0 else 1.0
        }

    /** Retained paved steps are walkable; foundations still require a full supporting top face. */
    fun routeTerraformSupport(block: Block): Boolean = fullTerraformSupport(block) ||
        civsRetainedRouteStep(block.blockData)

    fun passableTerraform(block: Block): Boolean = block.type.isAir ||
        block.type in AUTONOMOUS_PLANTS && !block.isLiquid &&
        (block.blockData as? Waterlogged)?.isWaterlogged != true && block.collisionShape.boundingBoxes.isEmpty()

    fun requireTerrainRoute(route: List<CivPosition>, building: CivBuilding, allowManualFoundation: Boolean = false) {
        if (allowManualFoundation && route.isEmpty()) return
        requireCivs(route.size in 1..CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH && route.last() == building.entrance &&
            route.all { it.y in -4_095..4_095 } &&
            route.map { civsTerrainKey(it.x, it.z) }.distinct().size == route.size, "invalid-plan")
        requireCivs(route.zipWithNext().all { (a, b) ->
            kotlin.math.abs(a.x.toLong() - b.x) + kotlin.math.abs(a.z.toLong() - b.z) == 1L &&
                kotlin.math.abs(a.y.toLong() - b.y) <= 1
        }, "autonomy-no-path")
    }

    /** Natural grass, opening passages and neighbour connections may change an otherwise intact cell. */
    fun sameConstructionState(actual: BlockData, expected: BlockData): Boolean {
        if (actual.asString == expected.asString) return true
        if (expected.material == Material.DIRT && actual.material == Material.GRASS_BLOCK) return true
        if (actual.material != expected.material) return false
        if (sameNaturalLeafState(actual, expected)) return true
        if (actual is Door && expected is Door)
            return (actual.clone() as Door).apply { isOpen = expected.isOpen }.asString == expected.asString
        if (actual is Gate && expected is Gate)
            return (actual.clone() as Gate).apply { isOpen = expected.isOpen }.asString == expected.asString
        if (actual is org.bukkit.block.data.type.Stairs && expected is org.bukkit.block.data.type.Stairs)
            return (actual.clone() as org.bukkit.block.data.type.Stairs).apply { shape = expected.shape }.asString == expected.asString
        if (actual is Fence && expected is Fence)
            return (actual.clone() as Fence).apply {
                for (face in listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST))
                    setFace(face, expected.hasFace(face))
            }.asString == expected.asString
        return false
    }

    /** Only natural dry leaves may change their neighbour-distance metadata without changing ownership. */
    private fun sameNaturalLeafState(actual: BlockData, expected: BlockData): Boolean {
        val leaves = actual as? Leaves ?: return false
        val before = expected as? Leaves ?: return false
        if (before.material != leaves.material || before.isPersistent || leaves.isPersistent ||
            (before as? Waterlogged)?.isWaterlogged == true || (leaves as? Waterlogged)?.isWaterlogged == true) return false
        return (leaves.clone() as Leaves).apply { distance = before.distance }.asString == before.asString
    }

    const val MAXIMUM_TREE_READS = 24
    const val MAXIMUM_TREE_LEDGER_PROBE = 56
    val AUTONOMOUS_SOIL = setOf(Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT,
        Material.ROOTED_DIRT, Material.PODZOL, Material.MYCELIUM, Material.MOSS_BLOCK, Material.CLAY)
    val AUTONOMOUS_ROCK = setOf(Material.STONE, Material.DEEPSLATE, Material.GRANITE,
        Material.DIORITE, Material.ANDESITE, Material.TUFF)
    val TREE_GROUND = AUTONOMOUS_SOIL + AUTONOMOUS_ROCK
    val TERRAFORM_GROUND = TREE_GROUND + setOf(Material.SAND, Material.RED_SAND, Material.GRAVEL)
    val FALLING_GROUND = setOf(Material.SAND, Material.RED_SAND, Material.GRAVEL)
    val AUTONOMOUS_PLANTS = setOf(Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN,
        Material.LARGE_FERN, Material.DEAD_BUSH, Material.SNOW, Material.MOSS_CARPET,
        Material.LEAF_LITTER, Material.WILDFLOWERS, Material.PINK_PETALS, Material.BUSH, Material.FIREFLY_BUSH,
        Material.SHORT_DRY_GRASS, Material.TALL_DRY_GRASS, Material.CLOSED_EYEBLOSSOM, Material.OPEN_EYEBLOSSOM,
        Material.CACTUS_FLOWER, Material.PALE_MOSS_CARPET, Material.PALE_HANGING_MOSS, Material.DANDELION,
        Material.POPPY, Material.BLUE_ORCHID, Material.ALLIUM, Material.AZURE_BLUET, Material.RED_TULIP,
        Material.ORANGE_TULIP, Material.WHITE_TULIP, Material.PINK_TULIP, Material.OXEYE_DAISY,
        Material.CORNFLOWER, Material.LILY_OF_THE_VALLEY, Material.SUNFLOWER, Material.LILAC,
        Material.ROSE_BUSH, Material.PEONY)
}

private fun requireCivs(condition: Boolean, reason: String) { if (!condition) throw CivsRejection(reason) }
