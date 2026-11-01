package ru.arc.civs

import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot

/** One off-thread graph shared by indoor work and control placement; never retained by a runtime cache. */
internal class CivsAuthoredInterior(val building: CivBuilding, val snapshot: ArcSchematicSnapshot,
    private val excludedSupport: Set<String>, private val retainedGround: Boolean = false) {
    val cells = snapshot.blocks()
    val entrance = CivsBlueprintGeometry.entrance(snapshot).position
    private val supportStates = cells.map { it.blockData }.distinct().associateWith { data ->
        val name = data.substringBefore('[').removePrefix("minecraft:")
        name !in excludedSupport && name !in UNSAFE_SUPPORT &&
            listOf("shulker_box", "_bed", "_fence", "_fence_gate", "_wall", "_pane", "_leaves", "_door", "_trapdoor",
                "_torch", "_sign", "_sapling", "_carpet", "_rail", "_button", "_pressure_plate").none(name::endsWith)
    }
    private val standing = cells.asSequence().filter { !it.isAir && (!it.isBarrier || retainedGround) }
        .map { it.position.let { p -> p.copy(y = p.y + 1) } }.filter { p ->
            val floor = p.copy(y = p.y - 1)
            inside(floor) && (supportStates[snapshot.blockAt(floor.x, floor.y, floor.z).blockData] == true ||
                retainedGround && floor.y == snapshot.origin.y && name(floor) == "barrier") &&
                clear(p) && clear(p.copy(y = p.y + 1))
        }.toHashSet()
    private val seeds = DIRECTIONS.flatMap { (dx, dz) -> listOf(0, 1, -1).map { dy ->
        entrance.copy(x = entrance.x + dx, y = entrance.y + dy, z = entrance.z + dz)
    } }.filter { it in standing }
    val reachable: Set<ArcSchematicPoint> = flood(null)
    private val routeParents by lazy { hashMapOf<ArcSchematicPoint, ArcSchematicPoint?>().also { flood(null, it) } }

    fun name(p: ArcSchematicPoint): String = snapshot.blockAt(p.x, p.y, p.z).blockData.substringBefore('[').removePrefix("minecraft:")
    fun world(p: ArcSchematicPoint): CivPosition = CivsWorksiteLayouts.point(building,
        CivPosition(p.x - snapshot.origin.x, p.y - snapshot.origin.y, p.z - snapshot.origin.z))

    /** Real machines need a reachable supported stance and headroom, including in an open forge. */
    fun operatingFloor(p: ArcSchematicPoint): Boolean = p in reachable && p.x > snapshot.minimum.x && p.x < snapshot.maximum.x &&
        p.z > snapshot.minimum.z && p.z < snapshot.maximum.z && world(p).let { building.bounds.contains(it.x, it.y + 1, it.z) }

    fun interior(p: ArcSchematicPoint): Boolean = operatingFloor(p) && sheltered(p)

    fun sheltered(p: ArcSchematicPoint): Boolean =
        (p.y + 2..minOf(p.y + 12, snapshot.maximum.y)).any { y -> name(p.copy(y = y)).let {
            it !in AIR && it !in excludedSupport && it !in setOf("structure_void", "barrier", "water", "lava") &&
                !it.endsWith("_leaves") && !it.endsWith("_bed")
        } }

    fun neighbors(p: ArcSchematicPoint): List<ArcSchematicPoint> = DIRECTIONS.flatMap { (dx, dz) -> listOf(0, 1, -1).map { dy ->
        p.copy(x = p.x + dx, y = p.y + dy, z = p.z + dz)
    } }

    /** A new barrel may consume a spare floor tile, never sever an authored entrance or work route. */
    fun connectedWithout(block: ArcSchematicPoint): Boolean = flood(block).size == reachable.count { !blocked(it, block) }

    /** Bounded detached proposal. Retained native terrain still needs a loaded-world collision proof. */
    fun routeTo(target: ArcSchematicPoint, avoid: ArcSchematicPoint? = null): List<CivPosition>? {
        val parents = if (avoid == null) routeParents else hashMapOf()
        val visited = if (avoid == null) reachable else flood(avoid, parents)
        if (target !in visited || avoid != null && visited.size != reachable.count { !blocked(it, avoid) }) return null
        val route = mutableListOf<ArcSchematicPoint>()
        var point: ArcSchematicPoint? = target
        while (point != null) {
            if (route.size >= 63) return null
            route += point
            point = parents[point]
        }
        return listOf(world(entrance)) + route.asReversed().map(::world)
    }
    fun wallScore(p: ArcSchematicPoint): Int = DIRECTIONS.count { (dx, dz) ->
        val side = p.copy(x = p.x + dx, z = p.z + dz)
        inside(side) && !clear(side) && name(side) !in setOf("water", "lava", "structure_void", "barrier")
    }

    private fun inside(p: ArcSchematicPoint) = p.x in snapshot.minimum.x..snapshot.maximum.x &&
        p.y in snapshot.minimum.y..snapshot.maximum.y && p.z in snapshot.minimum.z..snapshot.maximum.z
    private fun clear(p: ArcSchematicPoint): Boolean = inside(p) && name(p).let {
        it in AIR || it.endsWith("_door") || it.endsWith("_fence_gate") || it.endsWith("_carpet") ||
            retainedGround && p.y > snapshot.origin.y && it == "barrier"
    }
    private fun blocked(p: ArcSchematicPoint, block: ArcSchematicPoint?) = block != null && p.x == block.x && p.z == block.z &&
        p.y.toLong() in block.y.toLong() - 1..block.y.toLong()
    private fun flood(block: ArcSchematicPoint?, parents: MutableMap<ArcSchematicPoint, ArcSchematicPoint?>? = null): Set<ArcSchematicPoint> {
        val visited = hashSetOf<ArcSchematicPoint>()
        val frontier = ArrayDeque<ArcSchematicPoint>()
        seeds.filterNot { blocked(it, block) }.forEach { if (visited.add(it)) { frontier.addLast(it); parents?.put(it, null) } }
        while (frontier.isNotEmpty()) {
            val current = frontier.removeFirst()
            for (next in neighbors(current)) if (next in standing && !blocked(next, block) && visited.add(next)) {
                frontier.addLast(next)
                parents?.put(next, current)
            }
        }
        return visited
    }

    private companion object {
        val DIRECTIONS = listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)
        val AIR = setOf("air", "cave_air", "void_air")
        val UNSAFE_SUPPORT = AIR + setOf("structure_void", "barrier", "water", "lava", "fire", "soul_fire",
            "campfire", "soul_campfire", "magma_block", "cactus", "torch", "ladder", "chain", "lantern", "soul_lantern",
            "rail", "lever", "tripwire", "tripwire_hook", "redstone_wire", "iron_bars", "snow", "vine")
    }
}
