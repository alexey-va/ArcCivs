package ru.arc.civs

import kotlin.math.abs

/** The paid route proves the grade. Move its last turn into the street before paving the doorway. */
internal class CivsEntrancePath private constructor(
    private val door: CivPosition, private val front: CivPosition, private val side: Int,
) {
    private fun point(lane: Int, depth: Int, feetY: Int) = CivPosition(
        door.x + lane * front.z + depth * front.x, feetY, door.z - lane * front.x + depth * front.z)

    private fun depth(x: Int, z: Int) = (x - door.x) * front.x + (z - door.z) * front.z
    private fun lane(x: Int, z: Int) = (x - door.x) * front.z - (z - door.z) * front.x

    fun contains(x: Int, z: Int) = floor(x, z) != null

    val floors: List<CivPosition> get() = (0..3).flatMap { d ->
        (-3..3).mapNotNull { s -> point(s, d, door.y).let { floor(it.x, it.z) } }
    }

    val oldTurn: CivPosition? get() = if (side == 0) null else point(side, 0, door.y - 2)
    val sideFed: Boolean get() = side != 0

    fun floor(x: Int, z: Int): CivPosition? {
        val d = depth(x, z)
        val s = lane(x, z)
        if (d !in 0..3) return null
        val flank = abs(s) == 2 && d <= 1
        val retired = side != 0 && (s == side || s == side + side / 2)
        if (abs(s) > 1 && !flank && !retired) return null
        return CivPosition(x, door.y - if (d < 2 && abs(s) <= 2) 1 else 2, z)
    }

    fun bank(p: CivPosition): Boolean = abs(lane(p.x, p.z)) > 1 && floor(p.x, p.z) == p
    fun stair(p: CivPosition): Boolean = abs(lane(p.x, p.z)) <= 1 && floor(p.x, p.z) == p && depth(p.x, p.z) == 1

    fun surface(p: CivPosition, style: String): String? {
        if (floor(p.x, p.z) != p) return null
        if (bank(p)) return GRASS
        if (!stair(p)) return "minecraft:dirt_path"
        val facing = when { front.x == 1 -> "west"; front.x == -1 -> "east"; front.z == 1 -> "north"; else -> "south" }
        return "minecraft:${CivsSettlementLayout.wood(style)}_stairs[facing=$facing,half=bottom,shape=straight,waterlogged=false]"
    }

    /** Only these single exposed foundation blocks can contain an obsolete street surface. */
    fun bedding(p: CivPosition, before: String, style: String): Boolean {
        if (p.y != door.y - 1 || (!bank(p) && !stair(p))) return false
        val s = lane(p.x, p.z)
        return bank(p) && before.substringBefore('[') == "minecraft:grass_block" ||
            side != 0 && s * side > 0 && before == "minecraft:dirt_path" ||
            p.copy(y = p.y - 1) == oldTurn && before == "minecraft:${CivsSettlementLayout.wood(style)}_planks"
    }

    fun nearApproach(p: CivPosition): Boolean = depth(p.x, p.z) in 0..6 && abs(lane(p.x, p.z)) <= 3

    fun travel(route: List<CivPosition>): List<CivPosition> {
        if (side == 0) return route
        return buildList(route.size) {
            addAll(route.dropLast(6))
            add(point(side / 2, 4, door.y - 1))
            for (d in 4 downTo 2) add(point(0, d, door.y - 1))
            add(point(0, 1, door.y))
            add(door)
        }
    }

    companion object {
        const val GRASS = "minecraft:grass_block[snowy=false]"

        fun from(source: CivBuilding, route: List<CivPosition>): CivsEntrancePath? {
            val door = source.entrance
            if (source.type.isInfrastructure || route.size < 3 || route.last() != door) return null
            val front = CivsAutonomousGeometry.frontage(source)
            val last = route[route.lastIndex - 1]
            val side = (last.x - door.x) * front.z - (last.z - door.z) * front.x
            val path = CivsEntrancePath(door, front, 2 * side)
            if (side == 0) return path.takeIf {
                last == path.point(0, 1, door.y) && route[route.lastIndex - 2] == path.point(0, 2, door.y - 1)
            }
            if (abs(side) != 1 || route.size < 7 || last != path.point(side, 0, door.y)) return null
            if ((0..4).any { d -> route[route.lastIndex - 2 - d] != path.point(side * 2, d, door.y - 1) }) return null
            val projected = path.travel(route)
            return path.takeIf { projected.map { p -> civsTerrainKey(p.x, p.z) }.toSet().size == projected.size }
        }
    }
}
