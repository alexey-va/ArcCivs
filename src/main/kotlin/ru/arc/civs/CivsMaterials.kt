package ru.arc.civs

import org.bukkit.Material

enum class CivStorageMode { PHYSICAL, VIRTUAL }

/** Both storage modes hold the same vanilla items; changing mode never creates materials. */
data class CivInventory(
    val mode: CivStorageMode,
    val materials: Map<String, Long> = emptyMap(),
    val containers: List<CivPosition> = emptyList(),
    val buildingMaterials: Map<String, Map<String, Long>>? = null,
) {
    fun validated(): CivInventory = apply {
        require(mode != null && materials != null && containers != null)
        require(materials.size <= MAX_MATERIALS && materials.all { (key, value) ->
            MATERIAL_ID.matches(key) && value in 1..MAX_AMOUNT
        })
        buildingMaterials?.let { stores ->
            require(stores.size <= CivsRules.MAX_BUILDINGS)
            stores.forEach { (id, items) ->
                require(java.util.UUID.fromString(id).toString() == id)
                require(items.size <= MAX_MATERIALS && items.all { (key, value) -> MATERIAL_ID.matches(key) && value in 1..MAX_AMOUNT })
            }
        }
        require(containers.size <= MAX_CONTAINERS && containers.distinct().size == containers.size)
    }

    fun buildingStock(buildingId: java.util.UUID): Map<String, Long> = buildingMaterials?.get(buildingId.toString()).orEmpty()

    companion object {
        fun buildingCapacity(building: CivBuilding): Long = 27L * 64 * building.level

        const val MAX_CONTAINERS = 16
        const val MAX_MATERIALS = 512
        const val MAX_AMOUNT = 1_000_000L
        private val MATERIAL_ID = Regex("[A-Z][A-Z0-9_]{0,63}")

        fun starting(stock: Stock, mode: CivStorageMode = CivStorageMode.PHYSICAL): CivInventory = CivInventory(
            mode, mapOf("OAK_LOG" to stock.wood, "COBBLESTONE" to stock.stone, "BREAD" to stock.food).filterValues { it > 0 },
        )
    }
}

/** Fixed deterministic layer tiles; a completed NPC trip authorizes exactly one tile. */
data class CivConstructionBatches(val total: Int, val size: Int = 64)

internal data class CivBuildBatch(val minimum: CivPosition, val maximum: CivPosition)

internal fun civsBuildBatches(bounds: CivBox, size: Int = 256): List<CivBuildBatch> {
    require(size in 1..256)
    val width = minOf(16, size)
    val depth = maxOf(1, size / width)
    return buildList {
        for (y in bounds.min.y..bounds.max.y) {
            var z = bounds.min.z
            while (z <= bounds.max.z) {
                var x = bounds.min.x
                while (x <= bounds.max.x) {
                    add(CivBuildBatch(CivPosition(x, y, z), CivPosition(minOf(bounds.max.x, x + width - 1), y, minOf(bounds.max.z, z + depth - 1))))
                    x += width
                }
                z += depth
            }
        }
    }
}

/** Empty air and already-correct terrain do not consume invisible builder trips. */
internal fun civsBuildBatches(plan: CivPlacementPlan, size: Int = 256): List<CivBuildBatch> {
    val tiles = civsBuildBatches(plan.building.bounds, size)
    val minimum = plan.building.bounds.min
    val width = minOf(16, size)
    val depth = maxOf(1, size / width)
    fun key(p: CivPosition) = Triple((p.x - minimum.x) / width, p.y, (p.z - minimum.z) / depth)
    val changed = plan.blocks.asSequence().filter { it.before != it.after }.map { key(it.position) }.toHashSet()
    return tiles.filter { key(it.minimum) in changed }.ifEmpty { listOf(tiles.first()) }
}

/** Vanilla obtainability rules, shared by estimates and the final reservation. No creative/OP bypass. */
internal object CivsMaterials {
    fun required(plan: CivPlacementPlan): Map<String, Long> {
        val result = sortedMapOf<String, Long>()
        for (cell in plan.blocks) {
            if (cell.before == cell.after) continue
            requirements(cell.after).forEach { (material, count) -> result.merge(material, count, Math::addExact) }
        }
        return result
    }

    fun requirements(blockData: String): Map<String, Long> {
        val id = blockData.substringBefore('[').removePrefix("minecraft:").uppercase(java.util.Locale.ROOT)
        val states = blockData.substringAfter('[', "").removeSuffix("]").split(',').mapNotNull {
            val pair = it.split('=', limit = 2)
            if (pair.size == 2) pair[0] to pair[1] else null
        }.toMap()
        if (id in setOf("AIR", "CAVE_AIR", "VOID_AIR", "BARRIER", "STRUCTURE_VOID")) return emptyMap()
        if ((id.endsWith("_DOOR") || id in TALL_PLANTS) && states["half"] == "upper") return emptyMap()
        if (id.endsWith("_BED") && states["part"] == "head") return emptyMap()
        if (id in setOf("WATER_CAULDRON", "LAVA_CAULDRON", "POWDER_SNOW_CAULDRON"))
            return mapOf("CAULDRON" to 1L, id.removeSuffix("_CAULDRON") + "_BUCKET" to 1L)
        val mapped = when {
            id.startsWith("POTTED_") -> return mapOf("FLOWER_POT" to 1L, item(id.removePrefix("POTTED_")) to 1L)
            id.endsWith("_WALL_HANGING_SIGN") -> id.replace("_WALL_HANGING_SIGN", "_HANGING_SIGN")
            id.endsWith("_WALL_SIGN") -> id.replace("_WALL_SIGN", "_SIGN")
            id.endsWith("_WALL_TORCH") -> id.replace("_WALL_TORCH", "_TORCH")
            id == "WALL_TORCH" -> "TORCH"
            id.endsWith("_WALL_HEAD") -> id.replace("_WALL_HEAD", "_HEAD")
            id.endsWith("_WALL_SKULL") -> id.replace("_WALL_SKULL", "_SKULL")
            id.endsWith("_WALL_BANNER") -> id.replace("_WALL_BANNER", "_BANNER")
            else -> ITEM_ALIASES[id] ?: id
        }
        val count = when {
            id.endsWith("_SLAB") && states["type"] == "double" -> 2L
            id == "SNOW" -> states["layers"]?.toLongOrNull() ?: 1L
            id.endsWith("CANDLE") -> states["candles"]?.toLongOrNull() ?: 1L
            id == "SEA_PICKLE" -> states["pickles"]?.toLongOrNull() ?: 1L
            else -> 1L
        }
        val costs = linkedMapOf(item(mapped) to count)
        if (states["waterlogged"] == "true") costs.merge("WATER_BUCKET", 1L, Math::addExact)
        return costs
    }

    private fun item(name: String): String = Material.matchMaterial(name)?.takeIf { it.isItem && !it.isAir }?.name
        ?: throw CivsRejection("unsupported-material", mapOf("material" to name))

    private val TALL_PLANTS = setOf("TALL_GRASS", "LARGE_FERN", "SUNFLOWER", "LILAC", "ROSE_BUSH", "PEONY", "TALL_SEAGRASS", "PITCHER_PLANT")
    private val ITEM_ALIASES = mapOf(
        "WATER" to "WATER_BUCKET", "LAVA" to "LAVA_BUCKET", "FARMLAND" to "DIRT", "DIRT_PATH" to "DIRT",
        "WHEAT" to "WHEAT_SEEDS", "CARROTS" to "CARROT", "POTATOES" to "POTATO", "BEETROOTS" to "BEETROOT_SEEDS",
        "COCOA" to "COCOA_BEANS", "PUMPKIN_STEM" to "PUMPKIN_SEEDS", "ATTACHED_PUMPKIN_STEM" to "PUMPKIN_SEEDS",
        "MELON_STEM" to "MELON_SEEDS", "ATTACHED_MELON_STEM" to "MELON_SEEDS", "SWEET_BERRY_BUSH" to "SWEET_BERRIES",
        "REDSTONE_WIRE" to "REDSTONE", "TRIPWIRE" to "STRING", "BAMBOO_SAPLING" to "BAMBOO",
        "CAVE_VINES" to "GLOW_BERRIES", "CAVE_VINES_PLANT" to "GLOW_BERRIES", "KELP_PLANT" to "KELP",
        "TWISTING_VINES_PLANT" to "TWISTING_VINES", "WEEPING_VINES_PLANT" to "WEEPING_VINES",
    )
}

internal object CivsProduction {
    fun options(type: BuildingType): List<String> = when (type) {
        BuildingType.FORESTER -> listOf("OAK_LOG", "SPRUCE_LOG", "BIRCH_LOG", "JUNGLE_LOG", "ACACIA_LOG", "DARK_OAK_LOG")
        BuildingType.QUARRY -> listOf("COBBLESTONE", "STONE", "COBBLED_DEEPSLATE")
        BuildingType.FARM -> listOf("BREAD", "WHEAT", "CARROT", "POTATO")
        else -> emptyList()
    }

    fun amount(building: CivBuilding): Long = if (material(building) == "BREAD") building.level.toLong()
        else CivsRules.deliveryAmount(building)

    fun material(building: CivBuilding): String? = options(building.type).let { choices ->
        building.production?.takeIf { it in choices } ?: choices.firstOrNull()
    }
}
