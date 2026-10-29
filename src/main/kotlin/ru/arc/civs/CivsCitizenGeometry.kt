package ru.arc.civs

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.util.BoundingBox
import kotlin.math.abs

internal fun citizenStandable(world: World, point: CivPosition): Boolean {
    if (!citizenChunkReady(world, point.x shr 4, point.z shr 4) ||
        point.y <= world.minHeight || point.y + 1 >= world.maxHeight ||
        !citizenPacketCampfireClear(world, point.point())) return false
    val feet = world.getBlockAt(point.x, point.y, point.z)
    val head = world.getBlockAt(point.x, point.y + 1, point.z)
    val floor = world.getBlockAt(point.x, point.y - 1, point.z)
    val support = floor.boundingBox
    if (feet.type == org.bukkit.Material.LADDER || head.type == org.bukkit.Material.LADDER)
        return citizenPacketBodyClear(world, point.point(), false) && citizenPacketSupported(world, point.point())
    val clearFeet = feet.isPassable || feet.type.name.endsWith("_CARPET") && feet.boundingBox.maxY <= point.y + 0.125
    return clearFeet && !feet.isLiquid && head.isPassable && !head.isLiquid &&
        !floor.isLiquid && support.maxY >= point.y - 0.125 &&
        support.minX <= point.x + 0.2 && support.maxX >= point.x + 0.8 &&
        support.minZ <= point.z + 0.2 && support.maxZ >= point.z + 0.8
}

internal data class CitizenSleepPose(val position: Location, val bounds: org.bukkit.util.BoundingBox)

internal fun citizenStandingBounds(feet: Location, scale: Double): BoundingBox = BoundingBox(
    feet.x - .3 * scale, feet.y, feet.z - .3 * scale,
    feet.x + .3 * scale, feet.y + 1.8 * scale, feet.z + .3 * scale)

/** Native 1.21.11 sleeping mesh: head toward -X at yaw zero, .9375 model scale.
 * Include outer skin/armour layers and lift the pose above the actual floor. No fake bed metadata. */
internal fun citizenSleepPose(world: World, feet: Location, scale: Double,
                             available: (BoundingBox) -> Boolean = { true }): CitizenSleepPose? {
    if (!citizenPacketSupported(world, feet.point()) || !citizenPacketBodyClear(world, feet.point(), false)) return null
    val origin = feet.clone().add(0.0, .32 * scale + .01, 0.0)
    for (yaw in listOf(0f, 90f, 180f, 270f)) {
        val radians = Math.toRadians(yaw.toDouble())
        val cos = kotlin.math.cos(radians)
        val sin = kotlin.math.sin(radians)
        // HumanoidModel keeps its idle arm bob in SLEEPING: the armoured hand reaches
        // .591 blocks sideways at the .1-radian maximum, beyond its static .527 width.
        val corners = listOf(-1.96, .07).flatMap { x -> listOf(-.62, .62).map { z ->
            (origin.x + (x * cos + z * sin) * scale) to (origin.z + (-x * sin + z * cos) * scale)
        } }
        val box = org.bukkit.util.BoundingBox(corners.minOf { it.first }, origin.y - .31 * scale,
            corners.minOf { it.second }, corners.maxOf { it.first }, origin.y + .31 * scale, corners.maxOf { it.second })
        if (available(box) && citizenSleepClear(world, box)) return CitizenSleepPose(origin.clone().also { it.yaw = yaw; it.pitch = 0f }, box)
    }
    return null
}

internal fun citizenSleepClear(world: World, body: org.bukkit.util.BoundingBox): Boolean {
    for (x in kotlin.math.floor(body.minX).toInt()..kotlin.math.floor(body.maxX).toInt())
        for (z in kotlin.math.floor(body.minZ).toInt()..kotlin.math.floor(body.maxZ).toInt()) {
            if (!citizenChunkReady(world, x shr 4, z shr 4)) return false
            for (y in kotlin.math.floor(body.minY).toInt()..kotlin.math.floor(body.maxY).toInt()) {
                if (y !in world.minHeight until world.maxHeight) return false
                val block = world.getBlockAt(x, y, z)
                if (block.isLiquid || block.type in CITIZEN_LEISURE_HAZARDS ||
                    block.collisionShape.boundingBoxes.any { it.clone().shift(x.toDouble(), y.toDouble(), z.toDouble()).overlaps(body) }) return false
            }
        }
    return true
}

internal fun citizenShelterTarget(city: Settlement, worker: CivWorker, safe: (CivPosition) -> Boolean): CivPosition? =
    citizenShelterCandidates(city, worker).take(80).firstOrNull(safe)

/** Cheap bounded coordinates; the actor admits at most 80 native probes per shelter search pulse. */
internal fun citizenShelterCandidates(city: Settlement, worker: CivWorker): List<CivPosition> {
    val home = citizenHomeBuilding(city, worker) ?: return emptyList()
    val origin = home.control ?: home.entrance
    return buildSet {
        // Keep the established nearby cardinal preference before admitting additional
        // floor positions; the latter make room for several non-overlapping bodies.
        for (dy in listOf(0, 1, -1, 2, -2)) for (r in 1..4)
            for ((dx, dz) in listOf(r to 0, 0 to r, -r to 0, 0 to -r)) {
                val point = CivPosition(origin.x + dx, origin.y + dy, origin.z + dz)
                if (home.bounds.contains(point.x, point.y, point.z)) add(point)
            }
        for (dy in listOf(0, 1, -1, 2, -2)) for (r in 1..4) for (dx in -r..r) for (dz in -r..r) {
            if (maxOf(abs(dx), abs(dz)) != r) continue
            val point = CivPosition(origin.x + dx, origin.y + dy, origin.z + dz)
            if (home.bounds.contains(point.x, point.y, point.z)) add(point)
        }
    }.toList()
}
