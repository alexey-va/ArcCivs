package ru.arc.civs

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import java.util.Random
import java.util.UUID
import kotlin.math.floor
import kotlin.math.roundToInt

/** Transient visual movement has no production token, arrival grant or offline catch-up. */
internal class CitizenWander(workerId: UUID) {
    private val random = Random(workerId.mostSignificantBits xor java.lang.Long.rotateLeft(workerId.leastSignificantBits, 23))
    private var nextAt: Long? = null
    fun ready(now: Long, returning: Boolean = false): Boolean {
        if (nextAt == null) schedule(now, if (returning) 0L else 100L + random.nextInt(301))
        return now >= requireNotNull(nextAt)
    }

    fun directions(yaw: Float): List<Pair<Int, Int>> {
        val forward = (yaw / 90f).roundToInt()
        val turn = if (random.nextBoolean()) 1 else -1
        val heading = if (random.nextInt(8) == 0) forward + turn else forward
        return listOf(heading, heading + turn, heading - turn, heading + 2)
            .map { CITIZEN_WANDER_DIRECTIONS[it and 3] }
    }

    fun legLimit(): Int = 8 + random.nextInt(5)

    fun finish(now: Long, arrived: Boolean, resumeImmediately: Boolean = false) {
        // A live bag offer retains its stationary window for asynchronous courier dispatch.
        schedule(now, if (!arrived) 2_500L + random.nextInt(1_501) else if (resumeImmediately) 0L
            else 2_000L + random.nextInt(4_501))
    }
    private fun schedule(now: Long, delay: Long) {
        nextAt = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
    }
}

internal class CitizenWanderWalk(val home: CivBuilding, val outsideHome: Boolean, val territory: CivRouteTerritory,
    val token: CitizenToken, val startedAt: Long, val legLimit: Int) {
    var legs = 0
    var reachedAt: Long? = null
    var maxHandoffMillis = 0L

    fun outside(x: Int, z: Int): Boolean =
        x !in home.bounds.min.x..home.bounds.max.x || z !in home.bounds.min.z..home.bounds.max.z

    fun distanceToExit(x: Int, z: Int): Long = if (outside(x, z)) -1 else
        minOf(x.toLong() - home.bounds.min.x, home.bounds.max.x.toLong() - x,
            z.toLong() - home.bounds.min.z, home.bounds.max.z.toLong() - z)
}

private val CITIZEN_WANDER_DIRECTIONS = listOf(0 to 1, -1 to 0, 0 to -1, 1 to 0)
private const val CITIZEN_IDLE_RADIUS = 8.0

internal fun citizenIdleBuilding(city: Settlement, worker: CivWorker): CivBuilding? =
    worker.assignment?.let(city::building) ?: citizenHomeBuilding(city, worker)

/** An interior barrel can be farther from the door than the entire leisure radius. */
internal fun citizenIdleAnchor(@Suppress("UNUSED_PARAMETER") city: Settlement, building: CivBuilding): CivPosition =
    building.entrance

internal fun citizenWithinIdleArea(anchor: CivPosition, at: CitizenPoint): Boolean =
    (at.x - (anchor.x + .5)) * (at.x - (anchor.x + .5)) +
        (at.z - (anchor.z + .5)) * (at.z - (anchor.z + .5)) <= CITIZEN_IDLE_RADIUS * CITIZEN_IDLE_RADIUS &&
        at.y in anchor.y - 2.0..anchor.y + 2.0

/** An unreachable optional destination does not displace a safely standing resident. */
internal fun citizenSafeIdlePosition(world: World, anchor: CivPosition, at: CitizenPoint,
    buildings: Collection<CivBuilding> = emptyList()): Boolean {
    if (!citizenWithinIdleArea(anchor, at) || !citizenPacketSupported(world, at) ||
        !citizenPacketBodyClear(world, at, false)) return false
    val feet = Location(world, at.x, at.y, at.z)
    return citizenIdleTerrain(world, CivPosition(feet.blockX, feet.blockY, feet.blockZ), buildings) &&
        citizenSleepClear(world, citizenStandingBounds(feet, 1.0))
}

/** Idle walks retain their envelope; an observed meal visit also includes its validated destination. */
internal fun citizenIdleRouteAllowed(anchor: CivPosition, from: CitizenPoint, point: CivPosition,
                                    mealTarget: CivPosition? = null): Boolean {
    fun horizontal(x: Double, z: Double) = (x - (anchor.x + 0.5)) * (x - (anchor.x + 0.5)) +
        (z - (anchor.z + 0.5)) * (z - (anchor.z + 0.5))
    val radiusSquared = maxOf(CITIZEN_IDLE_RADIUS * CITIZEN_IDLE_RADIUS, horizontal(from.x, from.z),
        mealTarget?.let { horizontal(it.x + .5, it.z + .5) } ?: 0.0)
    val minimumY = minOf(anchor.y - 2.0, from.y, mealTarget?.y?.toDouble() ?: from.y)
    val maximumY = maxOf(anchor.y + 2.0, from.y, mealTarget?.y?.toDouble() ?: from.y)
    return horizontal(point.x + 0.5, point.z + 0.5) <= radiusSquared &&
        point.y.toDouble() in minimumY..maximumY
}

/** Authored log floors are walkable; outside active buildings, idle routes avoid trees and climbs. */
internal fun citizenIdleTerrain(world: World, point: CivPosition, buildings: Collection<CivBuilding> = emptyList()): Boolean {
    if (point.y <= world.minHeight || point.y + 1 >= world.maxHeight ||
        !world.isChunkLoaded(point.x shr 4, point.z shr 4)) return false
    val feet = world.getBlockAt(point.x, point.y, point.z).type
    val support = world.getBlockAt(point.x, point.y - 1, point.z).type
    val head = world.getBlockAt(point.x, point.y + 1, point.z).type
    return citizenIdleMaterials(point, buildings, feet, support, head, Tag.CLIMBABLE::isTagged,
        Tag.LEAVES::isTagged, Tag.LOGS::isTagged)
}

/** The identical idle policy over detached chunk materials and tag sets captured on Main. */
internal fun citizenIdleTerrain(point: CivPosition, minimumY: Int, maximumY: Int, buildings: Collection<CivBuilding>,
    material: (CivPosition) -> Material?, climbable: Set<Material>, leaves: Set<Material>, logs: Set<Material>): Boolean {
    if (point.y <= minimumY || point.y + 1 >= maximumY) return false
    val feet = material(point) ?: return false
    val support = material(point.copy(y = point.y - 1)) ?: return false
    val head = material(point.copy(y = point.y + 1)) ?: return false
    return citizenIdleMaterials(point, buildings, feet, support, head, climbable::contains, leaves::contains, logs::contains)
}

private inline fun citizenIdleMaterials(point: CivPosition, buildings: Collection<CivBuilding>,
    feet: Material, support: Material, head: Material, climbable: (Material) -> Boolean,
    leaves: (Material) -> Boolean, logs: (Material) -> Boolean): Boolean =
    feet !in CITIZEN_LEISURE_HAZARDS && support !in CITIZEN_LEISURE_HAZARDS && head !in CITIZEN_LEISURE_HAZARDS &&
        !climbable(feet) && !climbable(support) &&
        !leaves(support) && (!logs(support) ||
            buildings.any { it.phase == BuildingPhase.ACTIVE && it.bounds.contains(point.x, point.y - 1, point.z) })

internal val CITIZEN_LEISURE_HAZARDS = setOf(Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.FIRE,
    Material.SOUL_FIRE, Material.LAVA, Material.MAGMA_BLOCK, Material.CACTUS, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW)

/** Inspect only adjacent native floor shapes within the ordinary walking step height. */
internal fun citizenWanderFeet(world: World, x: Int, z: Int, fromY: Double): CitizenPoint? {
    if (!citizenChunkReady(world, x shr 4, z shr 4)) return null
    val low = (floor(fromY - 1.068).toInt() - 1).coerceAtLeast(world.minHeight)
    val high = floor(fromY + 1.068).toInt().coerceAtMost(world.maxHeight - 1)
    for (y in high downTo low) {
        val block = world.getBlockAt(x, y, z)
        if (block.type.isAir) continue
        val top = block.collisionShape.boundingBoxes.maxOfOrNull {
            if (.5 in it.minX..it.maxX && .5 in it.minZ..it.maxZ) it.maxY else Double.NEGATIVE_INFINITY
        }?.takeIf(Double::isFinite) ?: continue
        val at = CitizenPoint(x + .5, y + top, z + .5)
        if (citizenStepHeightAllowed(fromY, at.y) && citizenPacketSupported(world, at, allowClimbable = false))
            return at
    }
    return null
}
