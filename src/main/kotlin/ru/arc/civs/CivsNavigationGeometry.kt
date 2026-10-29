package ru.arc.civs

import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Openable
import org.bukkit.block.data.type.Door
import org.bukkit.block.data.type.Gate
import org.bukkit.block.data.type.Stairs
import org.bukkit.util.BoundingBox
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal const val CITIZEN_TURN_PER_TICK = 8f
internal fun citizenAngleChange(from: Float, to: Float): Float = (((to - from) % 360f + 540f) % 360f) - 180f
internal fun citizenTurn(from: Float, to: Float, limit: Float): Float =
    from + citizenAngleChange(from, to).coerceIn(-limit, limit)

/** The tick owner applies an authored look once; action callbacks only change its target. */
internal fun citizenFace(npc: ru.arc.paper.npc.PacketPlayerNpc, yaw: Float, pitch: Float, limit: Float = CITIZEN_TURN_PER_TICK) {
    npc.bodyYaw = citizenTurn(npc.bodyYaw, yaw, limit)
    npc.headYaw = citizenTurn(npc.headYaw, yaw, limit)
    npc.pitch += (pitch - npc.pitch).coerceIn(-limit, limit)
}

/** Native top-face profile under the moving body, independent of material, orientation and stair shape. */
internal fun citizenStepProfile(from: CitizenPoint, to: CitizenPoint, floors: List<BoundingBox>): List<CitizenPoint>? {
    data class Span(val start: Double, val end: Double, val height: Double)
    val dx = to.x - from.x
    val dz = to.z - from.z
    val distance = sqrt(dx * dx + dz * dz)
    if (distance < .05 || distance > 2.0 || abs(to.y - from.y) <= .13) return null
    // One wire-position quantum keeps interpolated packets off the riser boundary.
    val half = .299 + 1.0 / 4096.0
    val spans = floors.mapNotNull { box ->
        var start = 0.0
        var end = 1.0
        fun axis(origin: Double, delta: Double, low: Double, high: Double): Boolean {
            if (abs(delta) < .0000001) return origin > low && origin < high
            val first = (low - origin) / delta
            val second = (high - origin) / delta
            start = max(start, min(first, second))
            end = min(end, max(first, second))
            return end - start > .0000001
        }
        if (axis(from.x, dx, box.minX - half, box.maxX + half) &&
            axis(from.z, dz, box.minZ - half, box.maxZ + half)) Span(start, end, box.maxY) else null
    }
    val boundaries = (listOf(0.0, 1.0) + spans.flatMap { listOf(it.start, it.end) }).distinct().sorted()
    if (boundaries.size > 64) return null
    val levels = mutableListOf<Span>()
    for ((start, end) in boundaries.zipWithNext()) {
        if (end - start <= .0000001) continue
        val middle = (start + end) / 2
        val height = spans.filter { middle > it.start && middle < it.end }.maxOfOrNull { it.height } ?: return null
        val last = levels.lastOrNull()
        if (last != null && abs(last.height - height) < .000001) levels[levels.lastIndex] = last.copy(end = end)
        else levels += Span(start, end, height)
    }
    if (levels.size < 3 || abs(levels.first().height - from.y) > .001 || abs(levels.last().height - to.y) > .001) return null
    val ascending = to.y > from.y
    // Ordinary treads rise by 1/2; entering one from a 15/16 path raises the first riser to 9/16.
    if (levels.zipWithNext().any { (a, b) ->
            val rise = (b.height - a.height) * if (ascending) 1 else -1
            rise <= .000001 || rise > .562501
        } || levels.any { (it.end - it.start) * distance < .025 }) return null
    fun point(t: Double, y: Double) = CitizenPoint(from.x + dx * t, y, from.z + dz * t)
    val points = mutableListOf<CitizenPoint>()
    fun add(point: CitizenPoint) {
        if ((points.lastOrNull() ?: from).distanceSquared(point) > .000000000001) points += point
    }
    for ((before, after) in levels.zipWithNext()) {
        val contact = before.end
        if (ascending) {
            val ramp = min(.2 / distance, (before.end - before.start) * .45)
            add(point(contact - ramp, before.height))
            add(point(contact, after.height))
        } else {
            val ramp = min(.2 / distance, (after.end - after.start) * .45)
            add(point(contact, before.height))
            add(point(contact + ramp, after.height))
        }
    }
    add(to)
    return points
}

internal data class CitizenCollisionSnapshot(val liquid: Boolean, val door: Boolean,
    val localBoxes: List<BoundingBox>, val boxes: List<BoundingBox>, val bottomStair: Boolean = false)

/** Ground passages only: opening a trapdoor could remove a resident's floor or ladder access. */
internal fun citizenRouteOpenable(data: BlockData): Openable? = when (data) {
    is Door -> data
    is Gate -> data
    else -> null
}

internal fun citizenCollisionSnapshot(world: World, x: Int, y: Int, z: Int): CitizenCollisionSnapshot {
    val block = world.getBlockAt(x, y, z)
    val data = block.blockData
    val local = block.collisionShape.boundingBoxes.map { it.clone() }
    return CitizenCollisionSnapshot(block.isLiquid, citizenRouteOpenable(data) != null, local,
        local.map { it.clone().shift(x.toDouble(), y.toDouble(), z.toDouble()) },
        data is Stairs && data.half == Bisected.Half.BOTTOM && !data.isWaterlogged)
}

/** Collision shapes are local to their block; check both body width and headroom. */
internal fun citizenPacketBodyClear(world: World, point: CitizenPoint, allowDoors: Boolean,
                                    allowLiquids: Boolean = false, allowBelowWorld: Boolean = false,
                                    collision: ((Int, Int, Int) -> CitizenCollisionSnapshot)? = null): Boolean {
    val body = BoundingBox(point.x - .299, point.y + .001, point.z - .299, point.x + .299, point.y + 1.799, point.z + .299)
    val feetRow = floor(body.minY).toInt()
    if (feetRow < world.minHeight && !allowBelowWorld) return false
    for (x in floor(body.minX).toInt()..floor(body.maxX).toInt()) for (z in floor(body.minZ).toInt()..floor(body.maxZ).toInt()) {
        if (!citizenChunkReady(world, x shr 4, z shr 4)) return false
        // Fence/gate shapes extend half a block above their own native cell.
        for (y in max(world.minHeight, feetRow - 1)..floor(body.maxY).toInt()) {
            if (y >= world.maxHeight) return false
            val state = collision?.invoke(x, y, z) ?: citizenCollisionSnapshot(world, x, y, z)
            if (state.liquid && !allowLiquids &&
                body.overlaps(BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0))) return false
            if (allowDoors && state.door) continue
            if (state.boxes.any(body::overlaps)) return false
        }
    }
    return true
}

internal fun citizenPacketSupported(world: World, point: CitizenPoint): Boolean {
    val x = floor(point.x).toInt()
    val z = floor(point.z).toInt()
    val y = floor(point.y + .001).toInt()
    if (!citizenChunkReady(world, x shr 4, z shr 4) || y <= world.minHeight || y >= world.maxHeight) return false
    if (Tag.CLIMBABLE.isTagged(world.getBlockAt(x, y, z).type)) return true
    val by = citizenSupportY(point.y)
    val block = world.getBlockAt(x, by, z)
    if (block.isLiquid || citizenRouteOpenable(block.blockData) != null) return false
    return block.collisionShape.boundingBoxes.any {
        abs(by + it.maxY - point.y) <= .13 && point.x - x in it.minX..it.maxX && point.z - z in it.minZ..it.maxZ
    }
}

/** Low campfire collision shapes are solid footholds but unsafe for the whole resident body. */
internal fun citizenPacketCampfireClear(world: World, point: CitizenPoint, from: CitizenPoint? = null,
                                       halfWidth: Double = .299, height: Double = 1.8): Boolean {
    fun bodyAt(at: CitizenPoint) = BoundingBox(at.x - halfWidth, at.y - .001, at.z - halfWidth,
        at.x + halfWidth, at.y + height - .001, at.z + halfWidth)
    val body = bodyAt(point)
    val occupied = from?.let(::bodyAt)
    for (x in floor(body.minX).toInt()..floor(body.maxX).toInt()) for (z in floor(body.minZ).toInt()..floor(body.maxZ).toInt()) {
        if (!citizenChunkReady(world, x shr 4, z shr 4)) return false
        for (y in floor(body.minY).toInt()..floor(body.maxY).toInt()) {
            if (y !in world.minHeight until world.maxHeight) return false
            if (citizenCampfireLit(world.getBlockAt(x, y, z)) &&
                // An already exposed resident may leave its occupied fire cell, never enter a new one.
                occupied?.overlaps(BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0)) != true) return false
        }
    }
    return true
}

/**
 * Path/farmland support is 1/16 below a full block. Its edge to the next full
 * level spans 17/16, including the centre of a bottom stair. Admit only that
 * extra height class; a few thousandths account for observed rounded feet Y.
 * Native support, full-body sweeps and permissions remain separate checks.
 */
internal fun citizenStepHeightAllowed(fromY: Double, toY: Double): Boolean {
    val lower = min(fromY, toY)
    val upper = max(fromY, toY)
    if (upper - lower <= 1.01) return true
    return upper - lower <= 17.0 / 16.0 + .005 &&
        abs(lower - (floor(lower) + 15.0 / 16.0)) <= .005 &&
        abs(upper - kotlin.math.round(upper)) <= .005
}

internal fun citizenPacketStepClear(world: World, from: CitizenPoint, to: CitizenPoint, allowDoors: Boolean,
                                   collision: ((Int, Int, Int) -> CitizenCollisionSnapshot)? = null): Boolean {
    if (!citizenStepHeightAllowed(from.y, to.y)) return false
    if (from.y != to.y) {
        val high = max(from.y, to.y)
        val aboveFrom = CitizenPoint(from.x, high, from.z)
        val aboveTo = CitizenPoint(to.x, high, to.z)
        return citizenPacketSegmentClear(world, from, aboveFrom, allowDoors, from, collision) &&
            citizenPacketSegmentClear(world, aboveFrom, aboveTo, allowDoors, from, collision) &&
            citizenPacketSegmentClear(world, aboveTo, to, allowDoors, from, collision)
    }
    return citizenPacketSegmentClear(world, from, to, allowDoors, from, collision)
}

internal fun citizenPacketSegmentClear(world: World, from: CitizenPoint, to: CitizenPoint, allowDoors: Boolean,
                                      occupied: CitizenPoint, collision: ((Int, Int, Int) -> CitizenCollisionSnapshot)?): Boolean {
    // Both ends of a short rounded corner can be clear while its middle clips a
    // thin door panel. Sweep the same body used below through native block boxes.
    val obstacles = mutableListOf<BoundingBox>()
    val feetRow = floor(min(from.y, to.y) + .001).toInt()
    if (feetRow < world.minHeight) return false
    for (x in floor(min(from.x, to.x) - .299).toInt()..floor(max(from.x, to.x) + .299).toInt())
        for (z in floor(min(from.z, to.z) - .299).toInt()..floor(max(from.z, to.z) + .299).toInt()) {
            if (!citizenChunkReady(world, x shr 4, z shr 4)) return false
            for (y in max(world.minHeight, feetRow - 1)..floor(max(from.y, to.y) + 1.799).toInt()) {
                if (y !in world.minHeight until world.maxHeight) return false
                val state = collision?.invoke(x, y, z) ?: citizenCollisionSnapshot(world, x, y, z)
                if (state.liquid) obstacles += BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0)
                else if (!allowDoors || !state.door) obstacles += state.boxes
            }
        }
    if (citizenSlideBody(org.bukkit.util.Vector(from.x, from.y + .001, from.z),
            org.bukkit.util.Vector(to.x, to.y + .001, to.z), .299, 1.798, .299, obstacles) != null) return false
    val distance = sqrt(from.distanceSquared(to))
    val samples = kotlin.math.ceil(distance / .15).toInt().coerceIn(1, 16)
    for (index in 1..samples) {
        val t = index.toDouble() / samples
        val point = CitizenPoint(from.x + (to.x - from.x) * t,
            from.y + (to.y - from.y) * t,
            from.z + (to.z - from.z) * t)
        if (!citizenPacketBodyClear(world, point, allowDoors, collision = collision) || !citizenPacketCampfireClear(world, point, occupied)) return false
    }
    return true
}
