package ru.arc.civs

/** Authored wooden noticeboard. The single marker uses the ordinary registered control barrel. */
internal object CivsPlanBoardBlueprint {
    const val LEGACY_SCHEMATIC_ID = "civs/builtin/plan_board/v1"
    const val SMALL_SCHEMATIC_ID = "civs/builtin/plan_board/v2"
    const val SCHEMATIC_ID = "civs/builtin/plan_board/v3"
    const val REFIT_V1 = "civs/builtin/plan_board/v3-from-v1"
    const val REFIT_V2 = "civs/builtin/plan_board/v3-from-v2"
    val versions = setOf(LEGACY_SCHEMATIC_ID, SMALL_SCHEMATIC_ID, SCHEMATIC_ID, REFIT_V1, REFIT_V2)
    val origin = CivPosition(3, 0, 3)
    val bounds = CivBox(CivPosition(-2, 0, 0), CivPosition(8, 7, 2))
    val smallBounds = CivBox(CivPosition(0, 0, 0), CivPosition(6, 6, 2))
    val legacyBounds = smallBounds.copy(max = smallBounds.max.copy(y = 5))
    val legacyRefitBounds = CivBox(CivPosition(0, 0, 0), CivPosition(11, 7, 2))
    fun bounds(id: String): CivBox = when (id) {
        LEGACY_SCHEMATIC_ID -> legacyBounds; SMALL_SCHEMATIC_ID -> smallBounds
        REFIT_V1 -> legacyRefitBounds; else -> bounds
    }
    fun faceOffset(id: String): Int = if (id == REFIT_V1) 3 else 0

    fun futureBounds(board: CivBuilding): CivBox {
        val id = when (board.schematicId) { LEGACY_SCHEMATIC_ID -> REFIT_V1; SMALL_SCHEMATIC_ID -> REFIT_V2; else -> return board.bounds }
        val local = bounds(id)
        fun point(p: CivPosition): CivPosition {
            val x = p.x - origin.x; val z = p.z - origin.z
            val (dx, dz) = when (board.rotation / 90) { 0 -> x to z; 1 -> -z to x; 2 -> -x to -z; else -> z to -x }
            return CivPosition(board.anchor.x + dx, board.anchor.y + p.y, board.anchor.z + dz)
        }
        val corners = listOf(local.min, local.max, local.min.copy(z = local.max.z), local.max.copy(z = local.min.z)).map(::point)
        return CivBox(CivPosition(corners.minOf { it.x }, corners.minOf { it.y }, corners.minOf { it.z }),
            CivPosition(corners.maxOf { it.x }, corners.maxOf { it.y }, corners.maxOf { it.z }))
    }

    fun large(board: CivBuilding): Boolean = board.schematicId in setOf(SCHEMATIC_ID, REFIT_V1, REFIT_V2)
    fun needsRefit(board: CivBuilding): Boolean = board.type == BuildingType.PLAN_BOARD &&
        board.phase == BuildingPhase.ACTIVE && board.schematicId in setOf(LEGACY_SCHEMATIC_ID, SMALL_SCHEMATIC_ID)

    fun refitId(board: CivBuilding): String = when (board.schematicId) {
        LEGACY_SCHEMATIC_ID -> REFIT_V1
        SMALL_SCHEMATIC_ID -> REFIT_V2
        else -> throw CivsRejection("invalid-building")
    }

    fun refit(previous: CivBuilding?, next: CivBuilding): Boolean = previous != null && needsRefit(previous) &&
        next.id == previous.id && next.type == previous.type && next.level == previous.level &&
        next.schematicId == refitId(previous) && next.anchor == previous.anchor && next.entrance == previous.entrance &&
        next.rotation == previous.rotation && next.control == previous.control && civsContainsBox(next.bounds, previous.bounds)

    fun blocks(legacy: Boolean = false): Map<CivPosition, String> = blocks(if (legacy) LEGACY_SCHEMATIC_ID else SCHEMATIC_ID)

    fun blocks(id: String): Map<CivPosition, String> {
        val body = body(id)
        if (id !in setOf(REFIT_V1, REFIT_V2)) return body
        val previous = smallBlocks(id == REFIT_V1)
        // Unchanged cells stay barriers. Removed authored feet are explicit air, never item drops.
        return (previous.keys + body.keys).associateWith { p ->
            val before = previous[p]
            val after = body[p] ?: "minecraft:air"
            if (after == "minecraft:structure_void") after
            else if (before == after || before == null && after == "minecraft:air" && p.y == 0) "minecraft:barrier"
            else after
        }
    }

    /** Completed physical shape, also consumed by the source-derived preview. */
    fun body(id: String): Map<CivPosition, String> {
        require(id in versions)
        if (id == LEGACY_SCHEMATIC_ID || id == SMALL_SCHEMATIC_ID) return smallBlocks(id == LEGACY_SCHEMATIC_ID)
        val offset = faceOffset(id)
        return buildMap {
            fun box(x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int, state: String) {
                for (y in y0..y1) for (z in z0..z1) for (x in x0..x1) put(CivPosition(x + offset, y, z), "minecraft:$state")
            }
            box(-2, 1, 0, 8, 7, 2, "air")
            for (x in listOf(-2, 8)) {
                box(x, 0, 0, x, 0, 2, "stone_bricks")
                box(x, 1, 1, x, 6, 1, "stripped_spruce_log[axis=y]")
            }
            for (y in listOf(1, 6)) box(-1, y, 1, 7, y, 1, "stripped_spruce_log[axis=x]")
            box(-1, 2, 1, 7, 5, 1, "smooth_sandstone")
            box(-2, 6, 0, 8, 6, 0, "dark_oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]")
            box(-2, 6, 2, 8, 6, 2, "dark_oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")
            box(-2, 7, 1, 8, 7, 1, "dark_oak_slab[type=bottom,waterlogged=false]")
            put(CivPosition(3 + offset, 1, 2), "minecraft:structure_void")
        }
    }

    private fun smallBlocks(legacy: Boolean): Map<CivPosition, String> = buildMap {
        val lift = if (legacy) 0 else 1
        fun box(x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int, state: String) {
            for (y in y0..y1) for (z in z0..z1) for (x in x0..x1) put(CivPosition(x, y, z), "minecraft:$state")
        }
        // Journal the air around the face/roof too: a skipped barrier would preserve an old wall in front of the map.
        // Ground gaps stay untouched; ordinary native protection checks still own every clearing operation.
        box(0, 1, 0, 6, 5 + lift, 2, "air")
        for (x in listOf(0, 6)) {
            box(x, 0, 0, x, 0, 2, "stone_bricks")
            box(x, 1, 1, x, 4 + lift, 1, "stripped_spruce_log[axis=y]")
        }
        box(1, lift, 1, 5, lift, 1, "stripped_spruce_log[axis=x]")
        box(1 + lift, 4 + lift, 1, 5 - lift, 4 + lift, 1, "stripped_spruce_log[axis=x]")
        box(1, 1 + lift, 1, 5, 3 + lift, 1, "smooth_sandstone")
        box(0, 4 + lift, 0, 6, 4 + lift, 0, "dark_oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]")
        box(0, 4 + lift, 2, 6, 4 + lift, 2, "dark_oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")
        box(0, 5 + lift, 1, 6, 5 + lift, 1, "dark_oak_slab[type=bottom,waterlogged=false]")
        put(if (legacy) CivPosition(6, 1, 2) else CivPosition(3, 1, 2), "minecraft:structure_void")
    }

    fun schematic(dataVersion: Int, legacy: Boolean = false): ByteArray =
        schematic(dataVersion, if (legacy) LEGACY_SCHEMATIC_ID else SCHEMATIC_ID)

    fun schematic(dataVersion: Int, id: String): ByteArray = civsGeneratedSchematic(bounds(id), origin, blocks(id), dataVersion)
}
