package ru.arc.civs

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.util.BoundingBox
import kotlin.math.abs
import kotlin.math.floor

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

/** Storage retains its admitted cell while the native collision top owns the actual feet. */
internal fun citizenStorageStanding(world: World, point: CivPosition, feet: Location): Boolean {
    if (feet.world?.uid != world.uid || !citizenChunkReady(world, point.x shr 4, point.z shr 4) ||
        point.y <= world.minHeight || point.y + 1 >= world.maxHeight ||
        feet.y < point.y || feet.y >= point.y + 1.0) return false
    val at = feet.point()
    if (feet.y == point.y.toDouble()) {
        if (!citizenStandable(world, point)) return false
    } else {
        val x = floor(feet.x).toInt()
        val z = floor(feet.z).toInt()
        val y = citizenSupportY(feet.y)
        if (!citizenChunkReady(world, x shr 4, z shr 4)) return false
        val support = world.getBlockAt(x, y, z)
        if (support.isLiquid || citizenRouteOpenable(support.blockData) != null ||
            support.collisionShape.boundingBoxes.none { abs(y + it.maxY - feet.y) <= .001 &&
                feet.x - x in it.minX..it.maxX && feet.z - z in it.minZ..it.maxZ }) return false
        // Preserve the integer standing contract's .6-wide support footprint, including cell seams.
        for (dx in listOf(-.3, .3)) for (dz in listOf(-.3, .3))
            if (!citizenPacketSupported(world, CitizenPoint(feet.x + dx, feet.y, feet.z + dz), false)) return false
    }
    return citizenPacketSupported(world, at) && citizenPacketCampfireClear(world, at) &&
        citizenPacketBodyClear(world, at, false)
}

/** At most the admitted cell and its substrate; no material whitelist or extra route search. */
internal fun citizenStorageFeet(world: World, point: CivPosition): Location? {
    if (!citizenChunkReady(world, point.x shr 4, point.z shr 4) ||
        point.y <= world.minHeight || point.y + 1 >= world.maxHeight) return null
    val base = point.location(world)
    val floor = world.getBlockAt(point.x, point.y - 1, point.z)
    if (citizenStorageStanding(world, point, base) &&
        (abs(floor.boundingBox.maxY - base.y) <= .001 || !citizenPacketSupported(world, base.point(), false))) return base
    return listOf(point.y - 1, point.y).flatMap { y ->
        world.getBlockAt(point.x, y, point.z).collisionShape.boundingBoxes.map { y + it.maxY }
    }.filter { it > point.y + .001 && it < point.y + 1.0 }.distinct().sorted().asSequence()
        .map { base.clone().apply { y = it } }.firstOrNull { citizenStorageStanding(world, point, it) }
}

internal data class CitizenSleepPose(val position: Location, val bounds: org.bukkit.util.BoundingBox)

/** A sleeping floor keeps its authored plane; native collision geometry owns the exact feet. */
internal fun citizenSleepFeet(world: World, point: CivPosition): Location? {
    if (!citizenChunkReady(world, point.x shr 4, point.z shr 4) ||
        point.y <= world.minHeight || point.y + 1 >= world.maxHeight) return null
    return listOf(point.y - 1, point.y).flatMap { y ->
        world.getBlockAt(point.x, y, point.z).collisionShape.boundingBoxes.map { y + it.maxY }
    }.filter { abs(it - point.y) < 1.0 }.distinct().sortedBy { abs(it - point.y) }.asSequence()
        .map { point.location(world).apply { y = it } }
        .firstOrNull { citizenSleepStanding(world, point, it) }
}

internal fun citizenSleepStanding(world: World, floor: CivPosition, feet: Location): Boolean {
    if (feet.world?.uid != world.uid || feet.blockX != floor.x || feet.blockZ != floor.z ||
        !citizenChunkReady(world, floor.x shr 4, floor.z shr 4) || abs(feet.y - floor.y) >= 1.0 ||
        feet.y <= world.minHeight || feet.y + 1.8 >= world.maxHeight) return false
    val y = citizenSupportY(feet.y)
    val support = world.getBlockAt(floor.x, y, floor.z)
    if (support.isLiquid || support.type in CITIZEN_LEISURE_HAZARDS || citizenRouteOpenable(support.blockData) != null ||
        support.collisionShape.boundingBoxes.none { abs(y + it.maxY - feet.y) <= .001 &&
            feet.x - floor.x in it.minX..it.maxX && feet.z - floor.z in it.minZ..it.maxZ }) return false
    for (dx in listOf(-.3, .3)) for (dz in listOf(-.3, .3))
        if (!citizenPacketSupported(world, CitizenPoint(feet.x + dx, feet.y, feet.z + dz), false)) return false
    return citizenPacketSupported(world, feet.point(), false) && citizenPacketCampfireClear(world, feet.point()) &&
        citizenPacketBodyClear(world, feet.point(), false)
}

/** The whole lying envelope, including animated arm/armour margins, belongs to one strict room plane. */
internal fun citizenSleepRoom(floors: Set<CivPosition>, floorY: Int, feet: Location, body: BoundingBox): Boolean =
    abs(feet.y - floorY) < 1.0 &&
        (floor(body.minX).toInt()..floor(body.maxX - 1e-7).toInt()).all { x ->
            (floor(body.minZ).toInt()..floor(body.maxZ - 1e-7).toInt()).all { z -> CivPosition(x, floorY, z) in floors }
        }

internal fun citizenStandingBounds(feet: Location, scale: Double): BoundingBox = BoundingBox(
    feet.x - .3 * scale, feet.y, feet.z - .3 * scale,
    feet.x + .3 * scale, feet.y + 1.8 * scale, feet.z + .3 * scale)

/** Native 1.21.11 sleeping mesh: head toward -X at yaw zero, .9375 model scale.
 * Include outer skin/armour layers and lift the pose above the actual floor. No fake bed metadata. */
internal fun citizenSleepPose(world: World, feet: Location, scale: Double, floorLift: Double = 0.0,
                             available: (BoundingBox) -> Boolean = { true }): CitizenSleepPose? {
    if (!citizenPacketSupported(world, feet.point(), allowClimbable = false) ||
        !citizenPacketBodyClear(world, feet.point(), false)) return null
    val origin = feet.clone().add(0.0, (.32 + floorLift) * scale + .01, 0.0)
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
