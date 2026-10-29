package ru.arc.civs

import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Stairs
import kotlin.math.abs
import kotlin.math.floor

/** Collision classification only; replacing or supporting a road cell also requires its bounded palette and receipt. */
internal fun civsRetainedRouteStep(data: BlockData): Boolean =
    data is Stairs && data.half == Bisected.Half.BOTTOM && !data.isWaterlogged

/** Walk the actual tread contacts with a resident-width body, not a one-block jumping envelope. */
internal fun roadHalfStepWalk(world: World, route: List<CivPosition>,
    collision: ((Int, Int, Int) -> CitizenCollisionSnapshot)? = null): List<CitizenPoint>? {
    require(route.size in 2..CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH)
    val cache = hashMapOf<CivPosition, CitizenCollisionSnapshot>()
    fun state(x: Int, y: Int, z: Int): CitizenCollisionSnapshot = cache.getOrPut(CivPosition(x, y, z)) {
        collision?.invoke(x, y, z) ?: world.getBlockAt(x, y, z).let { block ->
            val boxes = block.collisionShape.boundingBoxes.map { it.clone() }
            CitizenCollisionSnapshot(block.isLiquid, citizenRouteOpenable(block.blockData) != null, boxes,
                boxes.map { it.clone().shift(x.toDouble(), y.toDouble(), z.toDouble()) })
        }
    }
    val walk = mutableListOf<CitizenPoint>()
    for ((a, b) in route.zipWithNext()) {
        require(abs(a.x.toLong() - b.x) + abs(a.z.toLong() - b.z) == 1L)
        for (index in 0..4) {
            if (walk.isNotEmpty() && index == 0) continue
            val t = index / 4.0
            val x = a.x + .5 + (b.x - a.x) * t
            val z = a.z + .5 + (b.z - a.z) * t
            val expected = a.y + (b.y - a.y) * t
            var top: Double? = null
            for (bx in floor(x - .299).toInt()..floor(x + .299).toInt())
                for (bz in floor(z - .299).toInt()..floor(z + .299).toInt()) {
                    if (!citizenChunkReady(world, bx shr 4, bz shr 4)) return null
                    for (by in floor(expected).toInt() - 2..floor(expected).toInt()) {
                        if (by !in world.minHeight until world.maxHeight) return null
                        val cell = state(bx, by, bz)
                        if (cell.liquid || cell.door) return null
                        for (box in cell.boxes) if (box.maxX > x - .299 && box.minX < x + .299 &&
                            box.maxZ > z - .299 && box.minZ < z + .299)
                            top = maxOf(top ?: box.maxY, box.maxY)
                    }
                }
            val point = CitizenPoint(x, top ?: return null, z)
            if (!citizenPacketBodyClear(world, point, false, collision = ::state)) return null
            walk.lastOrNull()?.let { before ->
                val rise = abs(point.y - before.y)
                val lower = minOf(point.y, before.y)
                val pathLip = rise <= 9.0 / 16.0 + .0001 && abs(lower - floor(lower) - 15.0 / 16.0) < .0001
                if (rise > .5001 && !pathLip || !citizenPacketStepClear(world, before, point, false, ::state) ||
                    !citizenPacketStepClear(world, point, before, false, ::state)) return null
            }
            walk += point
        }
    }
    return walk
}

/** Exact collision union for the deliberately small paving palette, including native corner shapes. */
internal fun roadUpkeepBoxes(data: BlockData): List<CitizenTerrainBox> {
    if (data.material == Material.COBBLESTONE || CivsRoadMaintenance.landing(data.asString))
        return listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
    if (data.material == Material.DIRT_PATH) return listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, 15.0 / 16.0, 1.0))
    require(CivsRoadMaintenance.surface(data.asString) && data is Stairs &&
        data.half == Bisected.Half.BOTTOM && !data.isWaterlogged)
    val (dx, dz) = when (data.facing) {
        BlockFace.NORTH -> 0 to -1
        BlockFace.SOUTH -> 0 to 1
        BlockFace.EAST -> 1 to 0
        BlockFace.WEST -> -1 to 0
        else -> throw IllegalArgumentException("Road stairs require a horizontal facing")
    }
    fun half(x: Int, z: Int) = CitizenTerrainBox(if (x == 1) .5 else 0.0, .5,
        if (z == 1) .5 else 0.0, if (x == -1) .5 else 1.0, 1.0, if (z == -1) .5 else 1.0)
    val front = half(dx, dz)
    val left = data.shape == Stairs.Shape.INNER_LEFT || data.shape == Stairs.Shape.OUTER_LEFT
    val side = if (left) half(dz, -dx) else half(-dz, dx)
    val base = CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, .5, 1.0)
    return when (data.shape) {
        Stairs.Shape.STRAIGHT -> listOf(base, front)
        Stairs.Shape.INNER_LEFT, Stairs.Shape.INNER_RIGHT -> listOf(base, front, side)
        Stairs.Shape.OUTER_LEFT, Stairs.Shape.OUTER_RIGHT -> listOf(base, CitizenTerrainBox(
            maxOf(front.minX, side.minX), .5, maxOf(front.minZ, side.minZ),
            minOf(front.maxX, side.maxX), 1.0, minOf(front.maxZ, side.maxZ)))
    }
}

internal fun civsBoxVolume(box: CivBox): Long {
    val lengths = listOf(box.max.x.toLong() - box.min.x + 1, box.max.y.toLong() - box.min.y + 1, box.max.z.toLong() - box.min.z + 1)
    if (lengths.any { it !in 1..CivsStore.MAX_PLAN_BLOCKS.toLong() }) return 0
    return lengths.fold(1L) { volume, length -> (volume * length).coerceAtMost(CivsStore.MAX_PLAN_BLOCKS.toLong() + 1) }
}

internal fun civsContainsBox(outer: CivBox, inner: CivBox): Boolean =
    outer.contains(inner.min.x, inner.min.y, inner.min.z) && outer.contains(inner.max.x, inner.max.y, inner.max.z)

/** Radius separates city plans; event protection below uses building boxes only. */
internal fun civsIntersectsCity(box: CivBox, city: Settlement): Boolean =
    box.min.x.toLong() <= city.center.x.toLong() + city.radius && box.max.x.toLong() >= city.center.x.toLong() - city.radius &&
        box.min.z.toLong() <= city.center.z.toLong() + city.radius && box.max.z.toLong() >= city.center.z.toLong() - city.radius

internal fun civsProtectedBuildings(city: Settlement): Sequence<CivBuilding> = sequence {
    yieldAll(city.buildings)
    city.construction?.plannedBuilding?.let { yield(it) }
}
