package ru.arc.civs

/** Authored wooden noticeboard. The single marker uses the ordinary registered control barrel. */
internal object CivsPlanBoardBlueprint {
    const val SCHEMATIC_ID = "civs/builtin/plan_board/v1"
    val origin = CivPosition(3, 0, 3)
    val bounds = CivBox(CivPosition(0, 0, 0), CivPosition(6, 5, 2))

    fun blocks(): Map<CivPosition, String> = buildMap {
        fun box(x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int, state: String) {
            for (y in y0..y1) for (z in z0..z1) for (x in x0..x1) put(CivPosition(x, y, z), "minecraft:$state")
        }
        // Journal the air around the face/roof too: a skipped barrier would preserve an old wall in front of the map.
        // Ground gaps stay untouched; ordinary native protection checks still own every clearing operation.
        box(0, 1, 0, 6, 5, 2, "air")
        for (x in listOf(0, 6)) {
            box(x, 0, 0, x, 0, 2, "stone_bricks")
            box(x, 1, 1, x, 4, 1, "stripped_spruce_log[axis=y]")
        }
        box(1, 0, 1, 5, 0, 1, "stripped_spruce_log[axis=x]")
        box(1, 4, 1, 5, 4, 1, "stripped_spruce_log[axis=x]")
        box(1, 1, 1, 5, 3, 1, "smooth_sandstone")
        box(0, 4, 0, 6, 4, 0, "dark_oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]")
        box(0, 4, 2, 6, 4, 2, "dark_oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]")
        box(0, 5, 1, 6, 5, 1, "dark_oak_slab[type=bottom,waterlogged=false]")
        put(CivPosition(6, 1, 2), "minecraft:structure_void")
    }

    fun schematic(dataVersion: Int): ByteArray = civsGeneratedSchematic(bounds, origin, blocks(), dataVersion)
}
