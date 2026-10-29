package ru.arc.civs

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import java.util.Random
import java.util.UUID

/** Transient visual movement has no production token, arrival grant or offline catch-up. */
internal class CitizenWander(workerId: UUID) {
    private val random = Random(workerId.mostSignificantBits xor java.lang.Long.rotateLeft(workerId.leastSignificantBits, 23))
    var step: Int = 0
        private set
    private var nextAt: Long? = null
    fun ready(now: Long): Boolean {
        if (nextAt == null) schedule(now, 100L + random.nextInt(301))
        return now >= requireNotNull(nextAt)
    }
    fun reject(now: Long) {
        step = if (step == Int.MAX_VALUE) 0 else step + 1
        schedule(now, 100L + random.nextInt(251))
    }
    fun finish(now: Long, arrived: Boolean, resumeImmediately: Boolean = false) {
        val next = (step.toLong() / CITIZEN_WANDER_HEIGHTS.size + 1) * CITIZEN_WANDER_HEIGHTS.size
        step = if (next > Int.MAX_VALUE) 0 else next.toInt()
        // Unassigned residents can continue at the next handoff. Assigned producers retain
        // their offer window while asynchronous supply allocation validates a stationary meeting.
        schedule(now, if (!arrived) 2_500L + random.nextInt(1_501) else if (resumeImmediately) 0L
            else 2_000L + random.nextInt(4_501))
    }
    private fun schedule(now: Long, delay: Long) {
        nextAt = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
    }
}

private val CITIZEN_WANDER_OFFSETS = (-7..7).flatMap { x ->
    (-7..7).filter { z -> x * x + z * z in 4..49 }.map { z -> x to z }
}
internal val CITIZEN_WANDER_HEIGHTS = listOf(0, 1, -1, 2, -2)
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
    return feet !in CITIZEN_LEISURE_HAZARDS && support !in CITIZEN_LEISURE_HAZARDS && head !in CITIZEN_LEISURE_HAZARDS &&
        !Tag.CLIMBABLE.isTagged(feet) && !Tag.CLIMBABLE.isTagged(support) &&
        !Tag.LEAVES.isTagged(support) && (!Tag.LOGS.isTagged(support) ||
            buildings.any { it.phase == BuildingPhase.ACTIVE && it.bounds.contains(point.x, point.y - 1, point.z) })
}

internal val CITIZEN_LEISURE_HAZARDS = setOf(Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.FIRE,
    Material.SOUL_FIRE, Material.LAVA, Material.MAGMA_BLOCK, Material.CACTUS, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW)

/** One candidate per actor admission. Native support/headroom checks belong to the bounded pulse. */
internal fun citizenWanderTarget(city: Settlement, storage: CivPosition, workerId: UUID, step: Int,
    allowedBuildings: Set<UUID> = emptySet(), from: CitizenPoint? = null, yaw: Float = 0f): CivPosition? {
    val seed = workerId.mostSignificantBits xor java.lang.Long.rotateLeft(workerId.leastSignificantBits, 23)
    val offset = Math.floorDiv(step, CITIZEN_WANDER_HEIGHTS.size)
    // Each horizontal attempt gets an independent reproducible choice, not the next vertex of a shared ring.
    val random = Random(seed xor (offset.toLong() * -7046029254386353131L))
    val dy = CITIZEN_WANDER_HEIGHTS[Math.floorMod(step, CITIZEN_WANDER_HEIGHTS.size)]
    // Pure geometry only: prefer a useful leg ahead over a tiny shuffle or another about-face.
    // Native support/headroom still consumes just one existing admission check.
    return List(if (from == null) 1 else 6) {
        val (dx, dz) = CITIZEN_WANDER_OFFSETS[random.nextInt(CITIZEN_WANDER_OFFSETS.size)]
        val x = storage.x.toLong() + dx
        val y = storage.y.toLong() + dy
        val z = storage.z.toLong() + dz
        if (x !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || y !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
            z !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) null
        else CivPosition(x.toInt(), y.toInt(), z.toInt()).takeIf { point ->
            citizenRouteCellAllowed(city, allowedBuildings, point.x, point.z) && citizenControlClear(city, point)
        }
    }.filterNotNull().maxByOrNull { target ->
        if (from == null) 0.0 else {
            val dx = target.x + .5 - from.x
            val dz = target.z + .5 - from.z
            val distance = kotlin.math.hypot(dx, dz)
            val angle = Math.toRadians(yaw.toDouble())
            if (distance < 3.0) -10.0 + distance
            else (-dx * kotlin.math.sin(angle) + dz * kotlin.math.cos(angle)) / distance + distance * .05
        }
    }
}
