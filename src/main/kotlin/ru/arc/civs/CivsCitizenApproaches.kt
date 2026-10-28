package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Door
import org.bukkit.block.data.type.Gate
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.paper.npc.PacketPlayerNpc
import java.util.UUID
import kotlin.math.abs

internal fun citizenControlBuilding(city: Settlement, point: CivPosition): CivBuilding? =
    civsProtectedBuildings(city).firstOrNull { it.control == point }

/** A control is furniture, including for the worker whose route enters that building. */
internal fun citizenControlClear(city: Settlement, feet: CivPosition): Boolean =
    civsProtectedBuildings(city).none { building -> building.control?.let {
        feet.x == it.x && feet.z == it.z && feet.y.toLong() in it.y.toLong()..(it.y.toLong() + 1)
    } == true }

/** Include the native player's width so a diagonal corner cannot turn a barrel into a shortcut. */
internal fun citizenControlStepClear(city: Settlement, from: CitizenPoint, to: CitizenPoint): Boolean {
    val origin = Vector(from.x, from.y, from.z)
    val destination = Vector(to.x, to.y, to.z)
    val delta = destination.clone().subtract(origin)
    val distance = delta.length()
    return civsProtectedBuildings(city).none { building ->
        val p = building.control ?: return@none false
        val obstacle = BoundingBox(p.x - 0.3, p.y - 1.8, p.z - 0.3, p.x + 1.3, p.y + 1.01, p.z + 1.3)
        obstacle.contains(destination) || !obstacle.contains(origin) && distance > 0 &&
            obstacle.rayTrace(origin, delta.clone().normalize(), distance) != null
    }
}

/** One real barrel, twelve supported side cells; never its top or another building's interior. */
internal fun citizenStorageTarget(city: Settlement, own: CivBuilding, world: World,
    safe: (CivPosition) -> Boolean = { citizenStandable(world, it) }): CivPosition? {
    val control = own.control ?: return null
    if (world.uid != city.worldId || !citizenStorageReady(world, control)) return null
    for (dy in listOf(0, 1, -1)) for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
        val coordinates = listOf(control.x.toLong() + dx, control.y.toLong() + dy, control.z.toLong() + dz)
        if (coordinates.any { it !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }) continue
        val point = CivPosition(coordinates[0].toInt(), coordinates[1].toInt(), coordinates[2].toInt())
        if (city.contains(point.x, point.z) &&
            city.buildings.none { it.id != own.id && it.bounds.contains(point.x, point.y, point.z) } &&
            city.construction?.plannedBuilding?.takeIf { it.id != own.id }?.bounds?.contains(point.x, point.y, point.z) != true &&
            citizenControlClear(city, point) && safe(point)) return point
    }
    return null
}

internal fun citizenCollectionIdle(plan: CitizenPlan, phase: CitizenPhase): Boolean = plan.courier == null &&
    phase !in setOf(CitizenPhase.WORKING, CitizenPhase.READY, CitizenPhase.DELIVERING, CitizenPhase.PICKING_UP) &&
    plan.idleReason in setOf("unassigned", "waiting-orders", "waiting-growth", "no-construction", "building-unavailable")

/** A route ends beside the real container; the final native ray remains authoritative at deposit time. */
internal fun citizenCollectedHomeTarget(city: Settlement, home: CivBuilding, world: World, target: CivPosition): CivPosition? {
    if (!citizenChunkReady(world, target.x shr 4, target.z shr 4)) return null
    val levels = listOf(home.entrance.y, target.y, target.y - 1, target.y + 1, target.y - 2, target.y - 3).distinct()
    for (y in levels) for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
        val point = target.copy(x = target.x + dx, y = y, z = target.z + dz)
        if (city.contains(point.x, point.z) && citizenControlClear(city, point) && citizenStandable(world, point) &&
            city.buildings.none { it.id != home.id && it.bounds.contains(point.x, point.y, point.z) } &&
            citizenCanReachBlock(world, point.location(world).add(0.0, 1.5, 0.0), target, 3.0)) return point
    }
    return null
}

internal fun citizenCollectedItemTarget(city: Settlement, world: World, item: Location): CivPosition? {
    for (dy in listOf(0, 1, -1)) for ((dx, dz) in listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
        val point = CivPosition(item.blockX + dx, item.blockY + dy, item.blockZ + dz)
        if (city.contains(point.x, point.z) && citizenControlClear(city, point) && citizenStandable(world, point)) return point
    }
    return null
}

internal fun citizenAtStorage(world: World, from: CitizenPoint, control: CivPosition): Boolean {
    if (!citizenStorageReady(world, control)) return false
    val dx = from.x - (control.x + 0.5)
    val dz = from.z - (control.z + 0.5)
    return abs(from.y - control.y) <= 1.5 && dx * dx + dz * dz <= 1.75 * 1.75 &&
        (kotlin.math.floor(from.x).toInt() != control.x || kotlin.math.floor(from.z).toInt() != control.z)
}

/** Batched actions keep one actual reachable focus (for example the trunk's lowest cut). */
internal fun citizenWorkFocus(work: CivWorldWork): CivPosition? {
    // A prepaid tree keeps its reachable root until the final batch, while earlier batches clear its crown.
    work.forestry?.let { progress ->
        val job = progress.job
        if (work.focus == job.root && job.root in job.roots && progress.next > job.next &&
            job.cells.takeLast(job.roots.size).any { job.position(it) == job.root }) return job.root
    }
    return (work.focus ?: work.changes.minByOrNull { it.position.point().distanceSquared(work.standing.point()) }?.position)
        ?.takeIf { focus -> work.changes.any { it.position == focus } || work.cropCare && work.focus == focus &&
            work.changes.all { it.position == focus.copy(y = focus.y - 1) } }
}

/** A short eye-to-block ray is the authority for work and storage access; no chunk is loaded. */
internal fun citizenCanReachBlock(npc: PacketPlayerNpc, target: CivPosition, reach: Double = CITIZEN_BLOCK_REACH): Boolean =
    npc.isValid && citizenCanReachBlock(npc.world, npc.location.clone().add(0.0, npc.eyeHeight, 0.0), target, reach)

internal const val CITIZEN_BLOCK_REACH = 3.0
internal const val CITIZEN_MINE_REACH = 4.5
internal const val CITIZEN_REMOTE_WORK_REACH = 12.0
internal fun citizenWorkReach(type: BuildingType?, work: CivWorldWork? = null): Double =
    if (work?.remoteWork == true && type in CivsWorksites.TYPES) CITIZEN_REMOTE_WORK_REACH
    else if (type == BuildingType.QUARRY) CITIZEN_MINE_REACH else CITIZEN_BLOCK_REACH

/** Planning uses the same ray contract as the observed actor and its durable receipt. */
internal fun citizenCanReachBlock(world: World, eye: Location, target: CivPosition, reach: Double = CITIZEN_BLOCK_REACH): Boolean =
    citizenReachableBlockPoint(world, eye, target, reach) != null

/** A block's centre can be hidden while one of its faces remains visible, notably below a ladder. */
internal fun citizenBlockAimPoints(eye: Location, target: CivPosition): List<Location> {
    if (!eye.x.isFinite() || !eye.y.isFinite() || !eye.z.isFinite()) return emptyList()
    val x0 = target.x + .001; val x1 = target.x + .999
    val y0 = target.y + .001; val y1 = target.y + .999
    val z0 = target.z + .001; val z1 = target.z + .999
    val x = eye.x.coerceIn(x0, x1)
    val y = eye.y.coerceIn(y0, y1)
    val z = eye.z.coerceIn(z0, z1)
    return listOf(Location(eye.world, target.x + .5, target.y + .5, target.z + .5),
        Location(eye.world, x, y0, z), Location(eye.world, x, y1, z),
        Location(eye.world, x0, y, z), Location(eye.world, x1, y, z),
        Location(eye.world, x, y, z0), Location(eye.world, x, y, z1)).distinct()
}

internal fun citizenReachableBlockPoint(world: World, eye: Location, target: CivPosition, reach: Double = CITIZEN_BLOCK_REACH,
    work: CivWorldWork? = null): Location? {
    if (eye.world != world || !eye.x.isFinite() || !eye.y.isFinite() || !eye.z.isFinite() ||
        target.y !in world.minHeight until world.maxHeight) return null
    val points = citizenBlockAimPoints(eye, target).filter { eye.distanceSquared(it) <= reach * reach }
    if (points.isEmpty() || !citizenBlockTraceReady(world, eye, target)) return null
    return points.firstOrNull { citizenTraceBlockPoint(world, eye, target, it, work) }
}

/** A single bounded sample; Worksites keeps its own cursor and shared eight-ray pulse budget. */
internal fun citizenCanReachBlockPoint(world: World, eye: Location, target: CivPosition, aim: Location,
    reach: Double = CITIZEN_BLOCK_REACH, work: CivWorldWork? = null): Boolean {
    if (aim.world != world || !aim.x.isFinite() || !aim.y.isFinite() || !aim.z.isFinite() ||
        aim.x < target.x || aim.x >= target.x + 1.0 || aim.y < target.y || aim.y >= target.y + 1.0 ||
        aim.z < target.z || aim.z >= target.z + 1.0 || eye.world != world ||
        eye.distanceSquared(aim) > reach * reach) return false
    return citizenBlockTraceReady(world, eye, target) && citizenTraceBlockPoint(world, eye, target, aim, work)
}

internal fun citizenBlockTraceReady(world: World, eye: Location, target: CivPosition): Boolean {
    if (eye.world != world || !eye.x.isFinite() || !eye.y.isFinite() || !eye.z.isFinite() ||
        target.y !in world.minHeight until world.maxHeight) return false
    for (x in (minOf(eye.blockX, target.x) shr 4)..(maxOf(eye.blockX, target.x) shr 4))
        for (z in (minOf(eye.blockZ, target.z) shr 4)..(maxOf(eye.blockZ, target.z) shr 4))
            if (!world.isChunkLoaded(x, z)) return false
    return true
}

private fun citizenTraceBlockPoint(world: World, eye: Location, target: CivPosition, aim: Location,
    work: CivWorldWork? = null): Boolean {
    val delta = aim.toVector().subtract(eye.toVector())
    val distance = delta.length()
    if (distance < .001) return true
    delta.multiply(1.0 / distance)
    val job = work?.takeIf { it.remoteWork && distance <= CITIZEN_REMOTE_WORK_REACH }?.forestry?.job
    var travelled = 0.0
    // A twelve-block ray crosses fewer than 32 voxels. Natural foliage may obscure the
    // remote animation, including a neighbouring crown; this never authorizes changing those leaves.
    repeat(if (job == null) 1 else 32) {
        val start = eye.clone().add(delta.clone().multiply(travelled))
        val hit = world.rayTraceBlocks(start, delta, distance - travelled, FluidCollisionMode.ALWAYS, true) ?: return true
        val block = hit.hitBlock ?: return false
        if (block.x == target.x && block.y == target.y && block.z == target.z) return true
        if (job == null) return false
        val leaves = block.blockData as? org.bukkit.block.data.type.Leaves
        if (leaves == null || leaves.isPersistent ||
            (leaves as? org.bukkit.block.data.Waterlogged)?.isWaterlogged == true) return false
        val exits = listOf(Triple(eye.x, delta.x, block.x), Triple(eye.y, delta.y, block.y), Triple(eye.z, delta.z, block.z))
            .mapNotNull { (origin, direction, cell) -> when {
                direction > .0000001 -> (cell + 1.0 - origin) / direction
                direction < -.0000001 -> (cell.toDouble() - origin) / direction
                else -> null
            } }.filter { it >= travelled }
        travelled = (exits.minOrNull() ?: return false) + .00001
        if (travelled >= distance) return true
    }
    return false
}

internal fun citizenStorageReady(world: World, control: CivPosition): Boolean =
    world.isChunkLoaded(control.x shr 4, control.z shr 4) &&
        world.getBlockAt(control.x, control.y, control.z).type == org.bukkit.Material.BARREL

internal fun citizenLookDirection(eye: Location, focus: Location): Pair<Float, Float> = eye.clone().let {
    it.direction = focus.toVector().subtract(eye.toVector())
    it.yaw to it.pitch
}

internal fun citizenWorkingTravel(plan: CitizenPlan, phase: CitizenPhase, sheltering: Boolean): Boolean =
    plan.idleReason in listOf(null, "placing") && !sheltering &&
        phase in setOf(CitizenPhase.TO_STORAGE, CitizenPhase.TO_WORK, CitizenPhase.RETURNING)

/** A cart belongs to an observed shipment, never to the resident's profession or idle wandering. */
internal fun citizenCourierCart(plan: CitizenPlan, phase: CitizenPhase, sheltering: Boolean): Boolean =
    plan.courier != null && plan.idleReason == null && !sheltering && phase in setOf(
        CitizenPhase.TO_WORK, CitizenPhase.WORKING, CitizenPhase.PICKUP_READY, CitizenPhase.PICKING_UP,
        CitizenPhase.RETURNING, CitizenPhase.READY, CitizenPhase.DELIVERING)

internal fun citizenDoorPair(world: World, point: CivPosition): Pair<org.bukkit.block.Block, org.bukkit.block.Block>? {
    if (point.y !in world.minHeight until world.maxHeight || !world.isChunkLoaded(point.x shr 4, point.z shr 4)) return null
    val data = world.getBlockAt(point.x, point.y, point.z).blockData as? Door ?: return null
    val y = point.y - if (data.half == Bisected.Half.TOP) 1 else 0
    if (y < world.minHeight || y + 1 >= world.maxHeight) return null
    val lower = world.getBlockAt(point.x, y, point.z)
    val upper = world.getBlockAt(point.x, y + 1, point.z)
    val bottom = lower.blockData as? Door ?: return null
    val top = upper.blockData as? Door ?: return null
    if (lower.type != upper.type || bottom.half != Bisected.Half.BOTTOM || top.half != Bisected.Half.TOP ||
        bottom.facing != top.facing || bottom.hinge != top.hinge) return null
    return lower to upper
}

internal fun citizenDoorAllowed(city: Settlement, allowed: Set<UUID>, point: CivPosition): Boolean =
    city.pendingDeletion == null && citizenRouteCellAllowed(city, allowed, point.x, point.z)

/** A gate can overlap feet one block above its base; it has no upper half like a door. */
internal fun citizenOpenRouteDoors(city: Settlement, allowed: Set<UUID>, at: Location, destination: Location,
    blocked: (UUID, CivPosition) -> Boolean): Boolean {
    check(Bukkit.isPrimaryThread()) { "Resident passages require the server thread" }
    val world = destination.world ?: return false
    if (world.uid != city.worldId || at.world?.uid != world.uid ||
        !world.isChunkLoaded(destination.blockX shr 4, destination.blockZ shr 4)) return false
    val body = BoundingBox(destination.x - .299, destination.y + .001, destination.z - .299,
        destination.x + .299, destination.y + 1.799, destination.z + .299)
    return (-1..1).all { dy ->
        val point = CivPosition(destination.blockX, destination.blockY + dy, destination.blockZ)
        if (dy == -1) {
            if (point.y !in world.minHeight until world.maxHeight) return@all true
            val block = world.getBlockAt(point.x, point.y, point.z)
            if (block.blockData !is Gate || block.collisionShape.boundingBoxes.none {
                    body.overlaps(it.clone().shift(point.x.toDouble(), point.y.toDouble(), point.z.toDouble()))
                }) return@all true
        }
        citizenOpenRouteDoor(city, allowed, at, point, blocked)
    }
}

/** Open only a nearby path door or gate; foreign footprints and rejected native events stay intact. */
internal fun citizenOpenRouteDoor(city: Settlement, allowed: Set<UUID>, at: Location, point: CivPosition,
    blocked: (UUID, CivPosition) -> Boolean): Boolean {
    check(Bukkit.isPrimaryThread()) { "Resident passages require the server thread" }
    val world = at.world ?: return false
    if (world.uid != city.worldId || point.y !in world.minHeight until world.maxHeight ||
        !world.isChunkLoaded(point.x shr 4, point.z shr 4)) return false
    if (citizenRouteOpenable(world.getBlockAt(point.x, point.y, point.z).blockData) == null) return true
    fun passage() = if (!world.isChunkLoaded(point.x shr 4, point.z shr 4)) null else when (world.getBlockAt(point.x, point.y, point.z).blockData) {
        is Door -> citizenDoorPair(world, point)?.toList()
        is Gate -> listOf(world.getBlockAt(point.x, point.y, point.z))
        else -> null
    }
    val blocks = passage() ?: return false
    val materials = blocks.map { it.type }
    val lower = blocks.first()
    if (blocks.any { block ->
            val p = CivPosition(block.x, block.y, block.z)
            !citizenDoorAllowed(city, allowed, p) || blocked(world.uid, p)
        }) return false
    if (blocks.all { citizenRouteOpenable(it.blockData)?.isOpen == true }) return true
    if (at.distanceSquared(Location(world, lower.x + 0.5, lower.y.toDouble(), lower.z + 0.5)) > 2.5 * 2.5) return false
    val event = CivsResidentOpenDoorEvent(city.id, lower)
    Bukkit.getPluginManager().callEvent(event)
    if (event.isCancelled) return false
    // A native event listener may replace a block; never open its replacement or restore captured data.
    val current = passage() ?: return false
    if (current.map { it.type } != materials || current.any { blocked(world.uid, CivPosition(it.x, it.y, it.z)) }) return false
    for (block in current) {
        val state = citizenRouteOpenable(block.blockData) ?: return false
        if (!state.isOpen) block.setBlockData(state.also { it.isOpen = true }, false)
    }
    return true
}

/** Exact entrance first, then at most 26 neighboring loaded, supported feet positions; never inside a building. */
internal fun citizenRecoveryTarget(city: Settlement, target: CivPosition, safe: (CivPosition) -> Boolean): CivPosition? {
    val offsets = listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, -1 to 1, 1 to -1, -1 to -1)
    for (dy in listOf(0, 1, -1)) for ((dx, dz) in offsets) {
        val x = target.x.toLong() + dx
        val y = target.y.toLong() + dy
        val z = target.z.toLong() + dz
        if (x !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || y !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
            z !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) continue
        val point = CivPosition(x.toInt(), y.toInt(), z.toInt())
        if (city.contains(point.x, point.z) && city.buildings.none { it.bounds.contains(point.x, point.y, point.z) } &&
            city.construction?.plannedBuilding?.bounds?.contains(point.x, point.y, point.z) != true && safe(point)) return point
    }
    return null
}
