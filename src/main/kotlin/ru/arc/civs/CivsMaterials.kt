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
data class CivConstructionBatches(val total: Int, val size: Int = 64, val atomicPairs: Boolean? = null)

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

/** New orders pay short, homogeneous rows instead of requiring a mixed 256-cell tile. */
internal fun civsMaterialBatches(plan: CivPlacementPlan, size: Int = 16, atomicPairs: Boolean = false): List<CivBuildBatch> {
    require(size in 1..16)
    val changed = plan.blocks.filter { it.before != it.after }.sortedWith(compareBy({ it.position.y }, { it.position.z }, { it.position.x }))
    val result = mutableListOf<CivBuildBatch>()
    var cost: Map<String, Long>? = null
    val cells = if (atomicPairs) plan.blocks.associateBy { it.position } else emptyMap()
    val consumed = hashSetOf<CivPosition>()
    for (cell in changed) {
        if (!consumed.add(cell.position)) continue
        val companion = if (atomicPairs) civsPairedPosition(cell.position, cell.after)?.let(cells::get)
            ?.takeIf { it.after.substringBefore('[') == cell.after.substringBefore('[') &&
                civsPairedPosition(it.position, it.after) == cell.position } else null
        if (companion != null) {
            consumed += companion.position
            val p = cell.position
            val q = companion.position
            result += CivBuildBatch(CivPosition(minOf(p.x, q.x), minOf(p.y, q.y), minOf(p.z, q.z)),
                CivPosition(maxOf(p.x, q.x), maxOf(p.y, q.y), maxOf(p.z, q.z)))
            cost = null
            continue
        }
        val required = CivsMaterials.batchKey(cell.after)
        val previous = result.lastOrNull()
        if (previous != null && required == cost && cell.position.y == previous.maximum.y &&
            cell.position.z == previous.maximum.z && cell.position.x == previous.maximum.x + 1 &&
            cell.position.x - previous.minimum.x < size) {
            result[result.lastIndex] = previous.copy(maximum = cell.position)
        } else result += CivBuildBatch(cell.position, cell.position)
        cost = required
    }
    return result.ifEmpty { listOf(CivBuildBatch(plan.blocks.first().position, plan.blocks.first().position)) }
}

internal fun civsConstructionBatches(plan: CivPlacementPlan, order: CivConstruction): List<CivBuildBatch> =
    if (order.payment != null || order.batches?.atomicPairs == true)
        civsMaterialBatches(plan, requireNotNull(order.batches).size, order.batches.atomicPairs == true)
    else civsBuildBatches(plan, requireNotNull(order.batches).size)

/** Only native two-cell blocks; trapdoors and ordinary bisected-looking states remain single blocks. */
internal fun civsPairedPosition(p: CivPosition, state: String): CivPosition? {
    val material = state.substringBefore('[')
    val properties = state.substringAfter('[', "").substringBefore(']').split(',').mapNotNull {
        val parts = it.split('=', limit = 2)
        if (parts.size == 2) parts[0] to parts[1] else null
    }.toMap()
    if (material.endsWith("_bed")) {
        val direction = when (properties["part"]) { "foot" -> 1; "head" -> -1; else -> return null }
        return when (properties["facing"]) {
            "north" -> p.copy(z = p.z - direction)
            "south" -> p.copy(z = p.z + direction)
            "east" -> p.copy(x = p.x + direction)
            "west" -> p.copy(x = p.x - direction)
            else -> null
        }
    }
    if (!material.endsWith("_door") && material.removePrefix("minecraft:") !in setOf(
            "sunflower", "lilac", "rose_bush", "peony", "tall_grass", "large_fern", "pitcher_plant", "small_dripleaf", "tall_seagrass")) return null
    return when (properties["half"]) {
        "lower", "bottom" -> p.copy(y = p.y + 1)
        "upper", "top" -> p.copy(y = p.y - 1)
        else -> null
    }
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

    /** Already persisted row indices keep their original grouping when water becomes free. */
    fun batchKey(blockData: String): Map<String, Long> = requirements(blockData, freeWater = false)

    fun unpaid(materials: Map<String, Long>): Map<String, Long> = materials.filterKeys { it != "WATER_BUCKET" }

    fun requirements(blockData: String): Map<String, Long> = requirements(blockData, freeWater = true)

    private fun requirements(blockData: String, freeWater: Boolean): Map<String, Long> {
        val id = blockData.substringBefore('[').removePrefix("minecraft:").uppercase(java.util.Locale.ROOT)
        val states = blockData.substringAfter('[', "").removeSuffix("]").split(',').mapNotNull {
            val pair = it.split('=', limit = 2)
            if (pair.size == 2) pair[0] to pair[1] else null
        }.toMap()
        if (id in setOf("AIR", "CAVE_AIR", "VOID_AIR", "BARRIER", "STRUCTURE_VOID")) return emptyMap()
        if (freeWater && id == "WATER") return emptyMap()
        if ((id.endsWith("_DOOR") || id in TALL_PLANTS) && states["half"] == "upper") return emptyMap()
        if (id.endsWith("_BED") && states["part"] == "head") return emptyMap()
        if (freeWater && id == "WATER_CAULDRON") return mapOf("CAULDRON" to 1L)
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
        if (!freeWater && states["waterlogged"] == "true") costs.merge("WATER_BUCKET", 1L, Math::addExact)
        return costs
    }

    private fun item(name: String): String = Material.matchMaterial(name)?.takeIf { it.isItem && !it.isAir }?.name
        ?: throw CivsRejection("unsupported-material", mapOf("material" to name))

    private val TALL_PLANTS = setOf("TALL_GRASS", "LARGE_FERN", "SUNFLOWER", "LILAC", "ROSE_BUSH", "PEONY", "TALL_SEAGRASS", "PITCHER_PLANT")
    private val ITEM_ALIASES = mapOf(
        "FIRE" to "FIRE_CHARGE", "SOUL_FIRE" to "FIRE_CHARGE",
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
        BuildingType.TOWN_HALL -> listOf("BOOK")
        BuildingType.FORESTER -> listOf("OAK_LOG", "SPRUCE_LOG", "BIRCH_LOG", "JUNGLE_LOG", "ACACIA_LOG", "DARK_OAK_LOG")
        BuildingType.QUARRY -> listOf("COBBLESTONE", "STONE", "COBBLED_DEEPSLATE")
        BuildingType.FARM -> listOf("WHEAT", "CARROT", "POTATO")
        BuildingType.SAWMILL -> listOf("OAK_PLANKS", "SPRUCE_PLANKS", "BIRCH_PLANKS", "JUNGLE_PLANKS", "ACACIA_PLANKS", "DARK_OAK_PLANKS")
        BuildingType.BLACKSMITH -> listOf("IRON_SHOVEL", "IRON_AXE", "IRON_PICKAXE", "IRON_HOE")
        BuildingType.STONEMASON -> listOf("STONE_BRICKS")
        BuildingType.LIBRARY -> listOf("WRITABLE_BOOK")
        else -> emptyList()
    }

    fun amount(building: CivBuilding): Long = when (material(building)) {
        "BOOK", "WRITABLE_BOOK", "IRON_SHOVEL", "IRON_AXE", "IRON_PICKAXE", "IRON_HOE" -> 1L
        "OAK_PLANKS", "SPRUCE_PLANKS", "BIRCH_PLANKS", "JUNGLE_PLANKS", "ACACIA_PLANKS", "DARK_OAK_PLANKS", "STONE_BRICKS" -> 4L
        else -> CivsRules.deliveryAmount(building)
    }

    fun inputs(building: CivBuilding): Map<String, Long> = when (val material = material(building)) {
        "BOOK" -> mapOf("PAPER" to 3L, "LEATHER" to 1L)
        "WRITABLE_BOOK" -> mapOf("BOOK" to 1L, "FEATHER" to 1L, "INK_SAC" to 1L)
        "OAK_PLANKS", "SPRUCE_PLANKS", "BIRCH_PLANKS", "JUNGLE_PLANKS", "ACACIA_PLANKS", "DARK_OAK_PLANKS" ->
            mapOf(material.removeSuffix("_PLANKS") + "_LOG" to 1L)
        "STONE_BRICKS" -> mapOf("STONE" to 4L)
        "IRON_SHOVEL" -> mapOf("IRON_INGOT" to 1L, "STICK" to 2L)
        "IRON_AXE", "IRON_PICKAXE" -> mapOf("IRON_INGOT" to 3L, "STICK" to 2L)
        "IRON_HOE" -> mapOf("IRON_INGOT" to 2L, "STICK" to 2L)
        else -> emptyMap()
    }

    fun material(building: CivBuilding): String? = options(building.type).let { choices ->
        building.production?.takeIf { it in choices } ?: choices.firstOrNull()
    }
}
