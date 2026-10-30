package ru.arc.civs

import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot

/** One off-thread graph shared by indoor work and control placement; never retained by a runtime cache. */
internal class CivsAuthoredInterior(val building: CivBuilding, val snapshot: ArcSchematicSnapshot, excludedSupport: Set<String>) {
    val cells = snapshot.blocks()
    val entrance = CivsBlueprintGeometry.entrance(snapshot).position
    private val standing = cells.asSequence().filter { !it.isAir && !it.isBarrier }
        .map { it.position.let { p -> p.copy(y = p.y + 1) } }.filter { p ->
            val floor = p.copy(y = p.y - 1)
            inside(floor) && name(floor).let { it !in excludedSupport && it !in UNSAFE_SUPPORT && !it.endsWith("shulker_box") } &&
                clear(p) && clear(p.copy(y = p.y + 1))
        }.toHashSet()
    private val seeds = DIRECTIONS.flatMap { (dx, dz) -> listOf(0, 1, -1).map { dy ->
        entrance.copy(x = entrance.x + dx, y = entrance.y + dy, z = entrance.z + dz)
    } }.filter { it in standing }
    val reachable: Set<ArcSchematicPoint> = flood(null)

    fun name(p: ArcSchematicPoint): String = snapshot.blockAt(p.x, p.y, p.z).blockData.substringBefore('[').removePrefix("minecraft:")
    fun world(p: ArcSchematicPoint): CivPosition = CivsWorksiteLayouts.point(building,
        CivPosition(p.x - snapshot.origin.x, p.y - snapshot.origin.y, p.z - snapshot.origin.z))

    fun interior(p: ArcSchematicPoint): Boolean = p in reachable && p.x > snapshot.minimum.x && p.x < snapshot.maximum.x &&
        p.z > snapshot.minimum.z && p.z < snapshot.maximum.z && world(p).let { building.bounds.contains(it.x, it.y + 1, it.z) } &&
        (p.y + 2..minOf(p.y + 12, snapshot.maximum.y)).any { y -> name(p.copy(y = y)).let {
            it !in AIR && it !in setOf("structure_void", "water", "lava") && !it.endsWith("_leaves")
        } }

    fun neighbors(p: ArcSchematicPoint): List<ArcSchematicPoint> = DIRECTIONS.flatMap { (dx, dz) -> listOf(0, 1, -1).map { dy ->
        p.copy(x = p.x + dx, y = p.y + dy, z = p.z + dz)
    } }

    /** A new barrel may consume a spare floor tile, never sever an authored entrance or work route. */
    fun connectedWithout(block: ArcSchematicPoint): Boolean = flood(block).size == reachable.count { !blocked(it, block) }
    fun wallScore(p: ArcSchematicPoint): Int = DIRECTIONS.count { (dx, dz) ->
        val side = p.copy(x = p.x + dx, z = p.z + dz)
        inside(side) && !clear(side) && name(side) !in setOf("water", "lava", "structure_void", "barrier")
    }

    private fun inside(p: ArcSchematicPoint) = p.x in snapshot.minimum.x..snapshot.maximum.x &&
        p.y in snapshot.minimum.y..snapshot.maximum.y && p.z in snapshot.minimum.z..snapshot.maximum.z
    private fun clear(p: ArcSchematicPoint): Boolean = inside(p) && name(p).let { it in AIR || it.endsWith("_door") || it.endsWith("_carpet") }
    private fun blocked(p: ArcSchematicPoint, block: ArcSchematicPoint?) = block != null && p.x == block.x && p.z == block.z &&
        p.y.toLong() in block.y.toLong() - 1..block.y.toLong()
    private fun flood(block: ArcSchematicPoint?): Set<ArcSchematicPoint> {
        val visited = hashSetOf<ArcSchematicPoint>()
        val frontier = ArrayDeque<ArcSchematicPoint>()
        seeds.filterNot { blocked(it, block) }.forEach { if (visited.add(it)) frontier.addLast(it) }
        while (frontier.isNotEmpty()) for (next in neighbors(frontier.removeFirst())) {
            if (next in standing && !blocked(next, block) && visited.add(next)) frontier.addLast(next)
        }
        return visited
    }

    private companion object {
        val DIRECTIONS = listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)
        val AIR = setOf("air", "cave_air", "void_air")
        val UNSAFE_SUPPORT = AIR + setOf("structure_void", "barrier", "water", "lava", "fire", "soul_fire",
            "campfire", "soul_campfire", "magma_block", "cactus")
    }
}
