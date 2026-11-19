package ru.arc.civs

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.Block
import java.util.Random
import java.util.UUID
import kotlin.math.floor
import kotlin.math.roundToInt

/** Transient visual movement has no production token, arrival grant or offline catch-up. */
internal class CitizenWander(workerId: UUID) {
    private val random = Random(workerId.mostSignificantBits xor java.lang.Long.rotateLeft(workerId.leastSignificantBits, 23))
    private val recentStarts = ArrayDeque<CitizenPoint>()
    private var nextAt: Long? = null
    fun ready(now: Long, returning: Boolean = false): Boolean {
        if (nextAt == null) schedule(now, if (returning) 0L else 100L + random.nextInt(301))
        return now >= requireNotNull(nextAt)
    }

    fun directions(yaw: Float, allowDiagonal: Boolean = false): List<Pair<Int, Int>> {
        val forward = (yaw / (if (allowDiagonal) 45f else 90f)).roundToInt()
        val turn = if (random.nextBoolean()) 1 else -1
        val heading = if (random.nextInt(if (allowDiagonal) 3 else 8) == 0) forward + turn else forward
        val diagonal = allowDiagonal && (heading and 1) != 0
        val cardinal = when {
            !allowDiagonal -> heading
            diagonal -> (yaw / 90f).roundToInt()
            else -> heading / 2
        }
        val fallback = listOf(cardinal, cardinal + turn, cardinal - turn, cardinal + 2)
            .map { CITIZEN_WANDER_DIRECTIONS[it and 3] }
        return if (diagonal) listOf(CITIZEN_WANDER_DIAGONALS[(heading and 7) / 2]) + fallback else fallback
    }

    fun legLimit(): Int = 4 + random.nextInt(3)
    fun targetVariation(): Int = random.nextInt(16)
    fun rememberStart(at: CitizenPoint) {
        if (recentStarts.size == 6) recentStarts.removeFirst()
        recentStarts += at
    }
    fun separation(at: CitizenPoint): Int =
        recentStarts.minOfOrNull { it.distanceSquared(at) }?.coerceAtMost(25.0)?.toInt() ?: 0

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
    val token: CitizenToken, val startedAt: Long, var legLimit: Int, val departureBuilding: CivBuilding?,
    val anchor: CivPosition, val streets: CivsPaths.RetainedRoads) {
    val remaining = ArrayDeque<CitizenPoint>()
    val allowedBuildings = setOfNotNull(departureBuilding?.id)
    var leavingBuilding = false
    val mode get() = if (leavingBuilding) "building-exit" else "local"
    var legs = 0
    var reachedAt: Long? = null
    var maxHandoffMillis = 0L

    fun onDeparturePlot(x: Int, z: Int): Boolean = departureBuilding?.bounds?.let {
        x in it.min.x..it.max.x && z in it.min.z..it.max.z
    } == true

    fun permits(point: CivPosition): Boolean =
        territory.allows(point, allowedBuildings, null) && territory.controls.clear(point)

    fun permitsStep(from: CitizenPoint, to: CitizenPoint, halfWidth: Double): Boolean {
        if (!territory.controls.stepClear(from, to)) return false
        val fromX = floor(from.x).toInt()
        val fromZ = floor(from.z).toInt()
        val startedOnPlot = departureBuilding?.bounds?.let { b ->
            from.x + halfWidth > b.min.x && from.x - halfWidth < b.max.x + 1.0 &&
                from.z + halfWidth > b.min.z && from.z - halfWidth < b.max.z + 1.0
        } == true
        if (!onDeparturePlot(fromX, fromZ) && onDeparturePlot(floor(to.x).toInt(), floor(to.z).toInt())) return false
        val outsidePost = outsideHome && (fromX !in home.bounds.min.x..home.bounds.max.x ||
            fromZ !in home.bounds.min.z..home.bounds.max.z)
        val y = floor(to.y + .001).toInt()
        for (x in floor(minOf(from.x, to.x) - halfWidth).toInt()..floor(maxOf(from.x, to.x) + halfWidth).toInt())
            for (z in floor(minOf(from.z, to.z) - halfWidth).toInt()..floor(maxOf(from.z, to.z) + halfWidth).toInt()) {
                if (!territory.allows(CivPosition(x, y, z), if (startedOnPlot) allowedBuildings else emptySet(), null) ||
                    outsidePost && x in home.bounds.min.x..home.bounds.max.x && z in home.bounds.min.z..home.bounds.max.z)
                    return false
            }
        return true
    }
}

/** Inspect only the actual support grade. A covered path on another floor gives no preference. */
private fun citizenStreetSupport(block: Block, roads: CivsPaths.RetainedRoads, territory: CivRouteTerritory): Boolean {
    val type = block.type
    if (type.isAir || type in CivsTerrainPolicy.AUTONOMOUS_SOIL && type != Material.COARSE_DIRT) return false
    val support = CivPosition(block.x, block.y, block.z)
    return (type == Material.DIRT_PATH || support in roads &&
        (type == Material.COARSE_DIRT || CivsRoadMaintenance.surface(block.blockData.asString))) &&
        territory.allows(support, emptySet(), null)
}

private data class WanderFloor(val feet: CitizenPoint, val plain: Boolean, val street: Boolean)
private class WanderNode(val floor: WanderFloor, val previous: WanderNode?, val depth: Int)
private const val WANDER_CELLS = 40
private const val WANDER_EDGES = 96

/** Small owner-thread flood. Only its chosen feet survive admission; movement samples native terrain again. */
internal fun citizenWanderPath(world: World, start: CitizenPoint, walk: CitizenWanderWalk, buildings: List<CivBuilding>, wander: CitizenWander,
    yaw: Float, halfWidth: Double, firstStepFree: (CitizenPoint) -> Boolean): List<CitizenPoint> {
    if (!citizenPacketSupported(world, start, false)) return emptyList()
    val directions = wander.directions(yaw, allowDiagonal = true)
    val cells = hashMapOf<CitizenPoint, WanderFloor?>()
    val shapes = hashMapOf<CivPosition, CitizenCollisionSnapshot>()
    fun floorAt(x: Int, z: Int, fromY: Double): WanderFloor? {
        val key = CitizenPoint(x + .5, fromY, z + .5)
        if (cells.containsKey(key)) return cells[key]
        val by = citizenSupportY(fromY)
        if (by !in world.minHeight until world.maxHeight - 2 ||
            !walk.territory.allows(CivPosition(x, by + 1, z), walk.allowedBuildings, null) ||
            !citizenChunkReady(world, x shr 4, z shr 4)) return null
        val base = world.getBlockAt(x, by, z)
        // Two air rows above one full-width, level native top prove the short flat sweep.
        // Furniture, steps, liquids, off-centre starts and partial footprints use the normal geometry.
        val baseType = base.type
        val top = if (!baseType.isAir && world.getBlockAt(x, by + 1, z).type.isAir &&
            world.getBlockAt(x, by + 2, z).type.isAir) {
            when (baseType) {
                Material.DIRT_PATH, Material.FARMLAND -> .9375
                Material.STONE, Material.COBBLESTONE, Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT -> 1.0
                else -> if (base.isLiquid || citizenRouteOpenable(base.blockData) != null) null else
                    base.collisionShape.boundingBoxes.singleOrNull()?.takeIf {
                        it.minX == 0.0 && it.maxX == 1.0 && it.minZ == 0.0 && it.maxZ == 1.0 && it.maxY in .875..1.0
                    }?.maxY
            }?.takeIf { kotlin.math.abs(by + it - fromY) <= .13 }
        } else null
        val feet = top?.let { key.copy(y = by + it) } ?: citizenWanderFeet(world, x, z, fromY)
        val point = feet?.let { CivPosition(x, floor(it.y + .001).toInt(), z) }
        val value = if (feet == null || point == null || point.y <= world.minHeight || !walk.territory.controls.clear(point)) null else {
            val suitable = if (top == null) citizenIdleTerrain(world, point, buildings)
                else citizenIdleMaterials(point, buildings,
                    if (point.y == by) baseType else Material.AIR, if (point.y == by) world.getBlockAt(x, by - 1, z).type else baseType,
                    Material.AIR, Tag.CLIMBABLE::isTagged, Tag.LEAVES::isTagged, Tag.LOGS::isTagged)
            if (!suitable) null else WanderFloor(feet, top != null,
                citizenStreetSupport(if (citizenSupportY(feet.y) == by) base else world.getBlockAt(x, citizenSupportY(feet.y), z),
                    walk.streets, walk.territory))
        }
        cells[key] = value
        return value
    }
    fun edge(from: WanderFloor, next: WanderFloor, diagonal: Boolean): Boolean {
        val a = from.feet
        val b = next.feet
        if (!walk.permitsStep(a, b, halfWidth)) return false
        if (diagonal) {
            if (a.y != b.y) return false
            val side1 = floorAt(floor(a.x).toInt(), floor(b.z).toInt(), a.y) ?: return false
            val side2 = floorAt(floor(b.x).toInt(), floor(a.z).toInt(), a.y) ?: return false
            if (side1.feet.y != a.y || side2.feet.y != a.y) return false
            if (from.plain && next.plain && side1.plain && side2.plain) return true
        } else if (from.plain && next.plain && citizenSupportY(a.y) == citizenSupportY(b.y)) return true
        return (a.y != b.y || citizenFlatSupportClear(a, b) { citizenPacketSupported(world, it, false) }) &&
            citizenPacketStepClear(world, a, b, false) { x, y, z ->
                shapes.getOrPut(CivPosition(x, y, z)) { citizenCollisionSnapshot(world, x, y, z) }
            }
    }

    val origin = floorAt(floor(start.x).toInt(), floor(start.z).toInt(), start.y)
    val root = WanderNode(origin?.takeIf { it.feet == start } ?: WanderFloor(start, false, false), null, 0)
    val reached = hashSetOf(start)
    val queue = ArrayDeque<WanderNode>()
    queue += root
    var best: WanderNode? = null
    var bestScore = Int.MIN_VALUE
    var followStreet = false
    var leavingDeadEnd = false
    val heading = directions.first()
    val exit = walk.departureBuilding?.entrance?.point()
    var attempted = 0
    while (queue.isNotEmpty() && reached.size < WANDER_CELLS && attempted < WANDER_EDGES) {
        val node = queue.removeFirst()
        if (node.depth == walk.legLimit) continue
        val at = node.floor.feet
        val next = ArrayList<WanderFloor>(directions.size)
        for ((dx, dz) in directions) {
            if (attempted == WANDER_EDGES || reached.size + next.size >= WANDER_CELLS) break
            attempted++
            val floor = floorAt(floor(at.x).toInt() + dx, floor(at.z).toInt() + dz, at.y) ?: continue
            if (floor.feet in reached || followStreet && !floor.street) continue
            if (edge(node.floor, floor, dx != 0 && dz != 0) && (node !== root || firstStepFree(floor.feet))) next += floor
        }
        if (node === root) {
            val paved = next.filter { it.street }
            leavingDeadEnd = root.floor.street && paved.size == 1 && next.size > 1 && paved.single().feet.let {
                (it.x - at.x) * heading.first + (it.z - at.z) * heading.second < 0
            }
            followStreet = paved.isNotEmpty() && !leavingDeadEnd
        }
        for (floor in next) {
            if (followStreet && !floor.street || !reached.add(floor.feet)) continue
            val candidate = WanderNode(floor, node, node.depth + 1)
            queue += candidate
            val feet = floor.feet
            val exited = walk.departureBuilding != null && !walk.onDeparturePlot(floor(feet.x).toInt(), floor(feet.z).toInt())
            val forward = ((feet.x - start.x) * heading.first + (feet.z - start.z) * heading.second).toInt()
            val towardsExit = exit?.let { (start.distanceSquared(it) - feet.distanceSquared(it)).coerceIn(-32.0, 32.0).toInt() } ?: 0
            // Prefer the outside and real paths, then spread short trips among new, forward cells.
            val score = (if (exited) 512 else 0) + (if (floor.street && !leavingDeadEnd) 256 else 0) + towardsExit +
                candidate.depth * 12 + forward * 5 + wander.separation(feet) * 3 + wander.targetVariation()
            if (score > bestScore) { best = candidate; bestScore = score }
        }
    }
    val path = ArrayDeque<CitizenPoint>()
    var node = best
    while (node != null && node.previous != null) {
        path.addFirst(node.floor.feet)
        node = node.previous
    }
    if (path.isNotEmpty()) wander.rememberStart(start)
    return path
}

private val CITIZEN_WANDER_DIRECTIONS = listOf(0 to 1, -1 to 0, 0 to -1, 1 to 0)
private val CITIZEN_WANDER_DIAGONALS = listOf(-1 to 1, -1 to -1, 1 to -1, 1 to 1)
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
