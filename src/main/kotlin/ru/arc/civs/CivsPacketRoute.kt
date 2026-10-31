package ru.arc.civs

import org.bukkit.Location
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.util.BoundingBox
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Owns one route and its main-thread movement. The same detached A* serves async jobs and
 * deterministic direct callers; every accepted move checks fresh full-body native geometry.
 */
internal class CitizenPacketRoute(
    private val world: World,
    from: Location,
    private val target: Location,
    private val allowed: (CivPosition) -> Boolean,
    private val stepAllowed: (CitizenPoint, CitizenPoint) -> Boolean,
    private val travelCost: (org.bukkit.block.Block) -> Double,
    private val avoiding: List<BoundingBox> = emptyList(),
    private val settings: CivsNavigationSettings = CivsNavigationSettings(),
    private val corridor: CitizenSearchCorridor? = null,
) {
    private class Waypoint(val start: CitizenPoint, val point: CitizenPoint, val corner: Boolean = true,
                           val action: CitizenRouteAction = CitizenRouteAction.WALK,
                           val support: CitizenPoint = point,
                           val profileTerrain: Map<CivPosition, CitizenCollisionSnapshot>? = null,
                           val straightCorridor: Boolean = false) {
        private val length = sqrt(start.distanceSquared(point))
        private var highWater = 0.0
        var profileChecked = false
        var corridorChecked = false

        private fun projection(at: CitizenPoint): Double = if (length < .000001) 0.0 else
            (((at.x - start.x) * (point.x - start.x) + (at.y - start.y) * (point.y - start.y) +
                (at.z - start.z) * (point.z - start.z)) / length).coerceIn(0.0, length)

        fun observe(at: CitizenPoint) { highWater = max(highWater, projection(at)) }
        fun behindHighWater(at: CitizenPoint): Boolean = projection(at) + .000001 < highWater

        fun walked(from: CitizenPoint, to: CitizenPoint): Double {
            // A changed observed position can raise the watermark, but only this accepted
            // walk earns progress. Revisiting a covered part of the segment earns none.
            observe(from)
            val projected = projection(to)
            val earned = (projected - highWater).coerceAtLeast(0.0)
            highWater = max(highWater, projected)
            return min(earned, sqrt(from.distanceSquared(to)))
        }
    }
    internal val worldId get() = world.uid
    private val origin = CitizenPoint(from.x, from.y, from.z)
    private val minimumY = world.minHeight
    private val maximumY = world.maxHeight
    private val path = ArrayDeque<Waypoint>()
    private var visited = 0
    private var asyncCancellation: (() -> Unit)? = null
    private var directSearch: CitizenNavigationSearch? = null
    private var directCapture: CitizenNavigationCapture? = null
    private val directTerrain = hashMapOf<CivPosition, CitizenTerrainBlock>()
    private var fallingSpeed = 0.0
    var preparationPhase = "queued"
        private set
    var capturedBlocks = 0
        private set
    var nativeShapeReads = 0
        private set
    var searchNanos = 0L
        private set
    var searching = true
        private set
    var failure: String? = null
        private set
    var finished = false
        private set
    var continuation = false
        private set
    var continuationReason: String? = null
        private set
    internal var blockedPositions: List<CivPosition> = emptyList()
        private set
    var waitingForCollision = false
        private set
    /** Contact fallback changes only captured actor bodies; native terrain and domain predicates remain mandatory. */
    internal var bodyContactAllowed = false
    var turning = false
        private set
    var forwardProgress = 0.0
        private set
    val navigating: Boolean get() = searching || failure == null && !finished
    val destination: Location? get() = path.firstOrNull()?.point?.let { Location(world, it.x, it.y, it.z) }
    val nodeCount: Int get() = visited
    val endpoint: CitizenPoint? get() = path.lastOrNull()?.point
    val remainingNodes: Int get() = path.size
    /** The request received a hint; ordinary wider-search fallback may still be needed. */
    internal val hasCourierCorridorHint: Boolean get() = corridor != null

    /** A raised foot may use only the exact native stair profile that admitted its current point. */
    internal fun stairProfileSupports(at: CitizenPoint): List<Triple<CivPosition, CitizenCollisionSnapshot, Double>> {
        if (searching || finished || failure != null) return emptyList()
        val entry = path.firstOrNull() ?: return emptyList()
        val profile = entry.profileTerrain?.takeIf { cells -> cells.values.any { it.bottomStair } } ?: return emptyList()
        val dx = entry.point.x - entry.start.x
        val dy = entry.point.y - entry.start.y
        val dz = entry.point.z - entry.start.z
        val length = dx * dx + dy * dy + dz * dz
        val t = if (length < .000000000001) 0.0 else
            ((at.x - entry.start.x) * dx + (at.y - entry.start.y) * dy + (at.z - entry.start.z) * dz) / length
        if (t !in -.000001..1.000001 || at.distanceSquared(CitizenPoint(entry.start.x + dx * t,
                entry.start.y + dy * t, entry.start.z + dz * t)) > .000000000001) return emptyList()
        val columns = linkedMapOf<Pair<Int, Int>, Triple<CivPosition, CitizenCollisionSnapshot, Double>>()
        for ((position, state) in profile) {
            if (position.x !in floor(at.x - .299).toInt()..floor(at.x + .299).toInt() ||
                position.z !in floor(at.z - .299).toInt()..floor(at.z + .299).toInt()) continue
            val top = state.boxes.filter { box ->
                box.maxY <= at.y + .001 && at.y - box.maxY <= .562501 &&
                    box.minX < at.x + .299 && box.maxX > at.x - .299 &&
                    box.minZ < at.z + .299 && box.maxZ > at.z - .299
            }.maxOfOrNull { it.maxY } ?: continue
            val column = position.x to position.z
            if (top > (columns[column]?.third ?: Double.NEGATIVE_INFINITY)) columns[column] = Triple(position, state, top)
        }
        return columns.values.toList()
    }

    fun cancel() {
        searching = false
        finished = true
        preparationPhase = "cancelled"
        asyncCancellation?.invoke()
        asyncCancellation = null
        releaseSearch()
        path.clear()
    }

    fun replan(from: Location, bodies: List<BoundingBox>): CitizenPacketRoute =
        CitizenPacketRoute(world, from, target, allowed, stepAllowed, travelCost, bodies.map(BoundingBox::clone), settings)

    private fun permits(from: CitizenPoint, to: CitizenPoint): Boolean = stepAllowed(from, to) &&
        (bodyContactAllowed || citizenSlideBody(org.bukkit.util.Vector(from.x, from.y, from.z), org.bukkit.util.Vector(to.x, to.y, to.z),
            .299, 1.798, .299, avoiding) == null)

    /** Direct deterministic driver; production requests use the owner/executor and the identical solver. */
    fun expand() {
        if (!searching || asyncCancellation != null) return
        val search = directSearch ?: newSearch().also { directSearch = it }
        try {
            val capture = directCapture
            if (capture != null && !capture.step()) return
            val result = search.advance(capture?.result.orEmpty())
            recordSearch(result)
            if (result.probes.isNotEmpty()) {
                preparationPhase = "capturing"
                directCapture = CitizenNavigationCapture(this, result.probes, directTerrain)
            } else acceptSearch(result)
        } catch (_: CitizenNavigationCaptureLimit) {
            acceptSearch(search.finishAtBudget("capture-budget"))
        }
    }

    internal fun newSearch() = CitizenNavigationSearch(origin, CitizenPoint(target.x, target.y, target.z),
        minimumY, maximumY, avoiding.map { CitizenTerrainBox(it.minX, it.minY, it.minZ, it.maxX, it.maxY, it.maxZ) }, settings, corridor)

    internal fun attachAsync(cancel: () -> Unit) {
        check(directSearch == null) { "A direct route cannot be submitted after search started" }
        asyncCancellation = cancel
        preparationPhase = "queued"
    }

    internal fun phase(value: String) { if (searching) preparationPhase = value }
    internal fun recordSearch(result: CitizenSearchResult) {
        visited = result.nodes
        searchNanos += result.searchNanos
    }
    internal fun planningFailed(reason: String) { if (searching) fail(reason) }
    internal fun captureLimits() = minimumY to maximumY
    internal fun captureBlockLimit() = settings.maximumCapturedBlocks
    internal fun captureProbeLimit() = settings.searchNodesPerBatch
    internal fun prefetchConnectedFrontier() = origin.distanceSquared(CitizenPoint(target.x, target.y, target.z)) >
        settings.closeRouteDistance * settings.closeRouteDistance
    internal fun preparationTickLimit(): Long =
        if (origin.distanceSquared(CitizenPoint(target.x, target.y, target.z)) <=
            settings.closeRouteDistance * settings.closeRouteDistance) settings.closePreparationTicks.toLong()
        else settings.preparationTicks.toLong()

    /** One bounded native capture. The resulting graph contains no World/Block or mutable shapes. */
    internal fun captureBlock(point: CivPosition, shapes: CivsNavigationTerrain? = null): CitizenTerrainBlock {
        if (point.y !in minimumY until maximumY || !citizenChunkReady(world, point.x shr 4, point.z shr 4))
            return CitizenTerrainBlock.UNAVAILABLE
        val block = world.getBlockAt(point.x, point.y, point.z)
        val capture = {
            nativeShapeReads++
            CitizenTerrainShape(block.isLiquid, citizenRouteOpenable(block.blockData) != null,
                Tag.CLIMBABLE.isTagged(block.type), citizenCampfireLit(block), block.collisionShape.boundingBoxes.map {
                    CitizenTerrainBox(it.minX, it.minY, it.minZ, it.maxX, it.maxY, it.maxZ)
                })
        }
        val shape = shapes?.read(world.uid, point, org.bukkit.Bukkit.getCurrentTick().toLong(), capture) ?: capture()
        capturedBlocks++
        // Empty air is not a surface, except the support coordinate below a ladder.
        // Read the current types so cached geometry never caches caller-specific costs.
        val surface = !block.type.isAir || shape.climbable ||
            point.y + 1 < maximumY &&
            Tag.CLIMBABLE.isTagged(world.getBlockAt(point.x, point.y + 1, point.z).type)
        return CitizenTerrainBlock(true, shape.liquid, shape.door, shape.climbable,
            shape.hot, allowed(point), if (surface) travelCost(block) else 1.0, shape.boxes)
    }

    internal fun capturePermission(from: CitizenPoint, to: CitizenPoint): Boolean = stepAllowed(from, to)

    internal fun acceptSearch(result: CitizenSearchResult) {
        if (!searching) return
        blockedPositions = result.blockedPositions
        result.failure?.let { fail(it); return }
        val steps = result.path?.toMutableList() ?: return
        // A nudge must not introduce a needless centre-and-reverse segment. The shortcut is
        // checked against current native geometry on this owner thread before accepting it.
        if (steps.size > 1 && steps.first().point.let {
                floor(it.x) == floor(origin.x) && floor(it.z) == floor(origin.z) && it.y == origin.y
            }) {
            val next = steps[1].point
            if (next.y == origin.y && permits(origin, next) && citizenPacketStepClear(world, origin, next, true)) steps.removeAt(0)
        }
        // Detached paths omit their root. An offset pose can need its own cell centre
        // before crossing an open panel, or before opening rotates a closed panel.
        val centre = CitizenPoint(floor(origin.x) + .5, origin.y, floor(origin.z) + .5)
        val first = steps.firstOrNull()?.takeIf { it.action == CitizenRouteAction.WALK && it.point.y == origin.y }
        if (first != null && origin.distanceSquared(centre) > .000001) {
            val cell = CivPosition(floor(first.point.x).toInt(), floor(first.point.y).toInt(), floor(first.point.z).toInt())
            val opening = cell.y in minimumY until maximumY && citizenChunkReady(world, cell.x shr 4, cell.z shr 4) &&
                citizenRouteOpenable(world.getBlockAt(cell.x, cell.y, cell.z).blockData)?.isOpen == false
            if ((opening || !citizenPacketStepClear(world, origin, first.point, false)) &&
                allowed(CivPosition(floor(centre.x).toInt(), floor(centre.y).toInt(), floor(centre.z).toInt())) &&
                citizenPacketSupported(world, centre) && permits(origin, centre) && permits(centre, first.point) &&
                citizenPacketStepClear(world, origin, centre, false) &&
                citizenPacketStepClear(world, centre, first.point, opening))
                steps.add(0, CitizenPlannedStep(centre, CitizenRouteAction.WALK))
        }
        var previous = origin
        for (step in steps) {
            path.addLast(Waypoint(previous, step.point, action = step.action))
            previous = step.point
        }
        searching = false
        continuation = result.continuation
        continuationReason = result.continuationReason
        preparationPhase = if (continuation) "ready-segment" else "ready"
        asyncCancellation = null
        releaseSearch()
    }

    /**
     * Native reads here intentionally bypass the planning cache. A new wall or unloaded chunk
     * stops the route before the actor moves; the domain owner decides retry or safe recovery.
     */
    fun move(from: Location, speed: Double,
             movementAllowed: (Location, Location) -> Boolean = { _, _ -> true },
             openDoor: (Location) -> Boolean): Location? {
        waitingForCollision = false
        turning = false
        if (searching || failure != null || finished) return null
        val observed = CitizenPoint(from.x, from.y, from.z)
        path.firstOrNull()?.observe(observed)
        straightenCorridor(observed)
        roundCorner(observed)
        // A large handoff turns in place first. Translating backwards while the body
        // catches up makes the native humanoid renderer reverse its own body heading.
        val stride = .215 * speed.coerceIn(.1, 5.0)
        // Route gravity has its own budget; a full-block drop must not consume the
        // horizontal walk. Bound each fall to one route level and the player sweep.
        val fallStride = ((fallingSpeed + .08) * .98).coerceAtMost(1.0)
        fun fallInPlace(): Location? {
            val entry = path.firstOrNull() ?: return null
            if (fallingSpeed == 0.0 || !gravityStep(entry, from.y)) return null
            val candidate = fall(from, from.clone().also { it.y = max(entry.point.y, from.y - fallStride) }) ?: return null
            val end = CitizenPoint(candidate.x, candidate.y, candidate.z)
            return candidate.takeIf { it.y < from.y - .000001 && permits(observed, end) &&
                citizenPacketSegmentClear(world, observed, end, false, observed, null) && movementAllowed(from, it) }
        }
        val heading = heading(from, stride)
        val pitch = from.pitch + (-from.pitch).coerceIn(-CITIZEN_TURN_PER_TICK, CITIZEN_TURN_PER_TICK)
        if (heading != null && abs(citizenAngleChange(from.yaw, heading)) > 65f) {
            turning = true
            val point = fallInPlace() ?: from.clone()
            fallingSpeed = if (abs(from.y - point.y - fallStride) < .000001) fallStride else 0.0
            return point.also { it.yaw = citizenTurn(from.yaw, heading, CITIZEN_TURN_PER_TICK); it.pitch = pitch }
        }
        var point = from.clone()
        var remaining = stride
        var moved = false
        var earned = 0.0
        fun finishSegment() {
            if (continuation && forwardProgress + earned <= .000001) fail("no-progress")
            else finished = true
        }
        var usedGravity = false
        var previousDirection: org.bukkit.util.Vector? = null
        var steppedStair = false
        var checkedProfile: Map<CivPosition, CitizenCollisionSnapshot>? = null
        // A normal step can cross a cell centre. Spend its remainder in this same tick,
        // never save it for a later tick or accelerate after an obstruction clears.
        for (segment in 0 until 8) {
            if (segment > 0) straightenCorridor(CitizenPoint(point.x, point.y, point.z))
            var entry = path.firstOrNull()
            if (entry == null) { finishSegment(); break }
            if (!entry.profileChecked && entry.profileTerrain == null) {
                entry.profileChecked = true
                prepareStepProfile(entry, point)
                entry = requireNotNull(path.firstOrNull())
            }
            val profile = entry.profileTerrain
            if (profile != null && profile !== checkedProfile) {
                if (profile.any { (position, captured) ->
                        !citizenChunkReady(world, position.x shr 4, position.z shr 4) ||
                            citizenCollisionSnapshot(world, position.x, position.y, position.z) != captured
                    }) { fail("path-changed"); break }
                checkedProfile = profile
            }
            val next = entry.point
            val to = Location(world, next.x, next.y, next.z)
            val support = entry.support
            if (!citizenChunkReady(world, to.blockX shr 4, to.blockZ shr 4) ||
                !allowed(CivPosition(to.blockX, floor(to.y + .001).toInt(), to.blockZ)) ||
                !citizenPacketSupported(world, support) ||
                !travelCost(world.getBlockAt(floor(support.x).toInt(), citizenSupportY(support.y), floor(support.z).toInt())).isFinite()) {
                fail("path-changed"); break
            }
            if (!openDoor(to)) { fail("passage-blocked"); break }
            // Lift before ascending and centre ladder entry. Ordinary downhill travel
            // follows the horizontal route while gravity settles onto native support.
            val descending = gravityStep(entry, point.y)
            val waypoint = to.clone()
            // A native profile already contains the clear ramps and mandatory tread contacts.
            if (profile == null) {
                if ((entry.action == CitizenRouteAction.CLIMB || entry.action == CitizenRouteAction.LADDER_ENTER) &&
                    (to.x - point.x) * (to.x - point.x) + (to.z - point.z) * (to.z - point.z) > .000001) {
                    waypoint.y = point.y
                } else if (to.y > point.y + .0001) {
                    waypoint.x = point.x
                    waypoint.z = point.z
                } else if (descending || to.y < point.y - .0001 &&
                    (to.x - point.x) * (to.x - point.x) + (to.z - point.z) * (to.z - point.z) > .000001) waypoint.y = point.y
            }
            val delta = waypoint.toVector().subtract(point.toVector())
            val stair = profile?.values?.any { it.bottomStair } == true
            val horizontal = sqrt(delta.x * delta.x + delta.z * delta.z)
            // A native stair spends the same X/Z stride as flat walking; Y is its supported lift.
            val distance = if (stair && horizontal > .000001) horizontal else delta.length()
            if (distance < .0001) {
                if (point.distanceSquared(to) < .000001) { path.removeFirst(); continue }
                if (!descending) break
            }
            val direction = if (distance < .0001) org.bukkit.util.Vector() else delta.clone().multiply(1.0 / distance)
            val straightStair = (stair || steppedStair) && previousDirection?.let { previous ->
                previous.x * direction.x + previous.z * direction.z > .000001 &&
                    abs(previous.x * direction.z - previous.z * direction.x) < .000001
            } == true
            if (previousDirection?.let { previous ->
                    (abs(previous.y) > .0001 || abs(direction.y) > .0001) &&
                        previous.clone().crossProduct(direction).lengthSquared() > .000001
                } == true && !straightStair) break
            val advance = min(distance, remaining)
            var candidate = if (advance == distance) waypoint else point.clone().add(direction.clone().multiply(advance))
            if (descending) {
                val lowered = fall(from, candidate.clone().also { it.y = max(to.y, from.y - fallStride) })
                if (lowered == null) { fail("movement-blocked"); break }
                candidate = lowered
                usedGravity = true
            }
            if (point.distanceSquared(candidate) < .000000000001) break
            val a = CitizenPoint(point.x, point.y, point.z)
            val b = CitizenPoint(candidate.x, candidate.y, candidate.z)
            if (!permits(a, b) || !(if (descending || profile != null) citizenPacketSegmentClear(world, a, b, false, a, null)
                    else citizenPacketStepClear(world, a, b, false))) {
                fail("movement-blocked"); break
            }
            val origin = CitizenPoint(from.x, from.y, from.z)
            // Native players lift, then sweep horizontally across a stair riser. Preserve that
            // body/ceiling check at a straight stair join; other corners retain the packet chord.
            if ((stair || steppedStair) && abs(b.y - origin.y) > .562501) break
            if (moved && (!permits(origin, b) || !(if (straightStair)
                    citizenPacketStepClear(world, origin, b, false)
                else citizenPacketSegmentClear(world, origin, b, false, origin, null)))) break
            if (!movementAllowed(from, candidate)) {
                waitingForCollision = true
                break
            }
            earned += entry.walked(a, b)
            point = candidate
            moved = true
            steppedStair = steppedStair || stair
            remaining -= advance
            previousDirection = direction
            // A supported lower node still constrains the route, but its centre is
            // not a stop in mid-air. Keep the last landing and every climb boundary.
            val nextEntry = path.elementAtOrNull(1)
            if (point.distanceSquared(to) < .000001 || profile == null && descending &&
                abs(point.x - to.x) < .000001 && abs(point.z - to.z) < .000001 &&
                nextEntry != null && nextEntry.action in listOf(CitizenRouteAction.WALK, CitizenRouteAction.STEP) &&
                nextEntry.point.y <= to.y + .0001) path.removeFirst()
            if (path.isEmpty()) { finishSegment(); break }
            if (remaining < .000001 || advance < .000001) break
        }
        if (!moved) {
            point = fallInPlace() ?: return null
            earned += path.firstOrNull()?.walked(observed, CitizenPoint(point.x, point.y, point.z)) ?: 0.0
            usedGravity = true
        }
        fallingSpeed = if (usedGravity && abs(from.y - point.y - fallStride) < .000001) fallStride else 0.0
        forwardProgress += min(earned, from.distance(point))
        val dx = point.x - from.x
        val dz = point.z - from.z
        // Exact geometric endpoints have no facing. A pure vertical leg must keep
        // its incoming angle instead of inheriting Location's default zero yaw.
        point.yaw = from.yaw
        if (heading != null || dx * dx + dz * dz > .00001) {
            val wanted = heading ?: Math.toDegrees(atan2(-dx, dz)).toFloat()
            point.yaw = citizenTurn(from.yaw, wanted, CITIZEN_TURN_PER_TICK)
        }
        point.pitch = pitch
        return point
    }

    private fun gravityStep(entry: Waypoint, y: Double): Boolean = entry.profileTerrain == null && entry.point.y < y - .0001 &&
        entry.action in listOf(CitizenRouteAction.WALK, CitizenRouteAction.STEP) &&
        (floor(entry.start.x) != floor(entry.point.x) || floor(entry.start.z) != floor(entry.point.z))

    /** Refine only this admitted edge; the A* cells, route policy and capture budgets stay unchanged. */
    private fun prepareStepProfile(entry: Waypoint, at: Location) {
        val from = CitizenPoint(at.x, at.y, at.z)
        val to = entry.point
        val horizontal = (to.x - from.x) * (to.x - from.x) + (to.z - from.z) * (to.z - from.z)
        if (entry.action != CitizenRouteAction.STEP || horizontal !in .0025..4.0 ||
            abs(to.y - from.y) <= .13 || !citizenStepHeightAllowed(from.y, to.y)) return
        val low = min(from.y, to.y)
        val high = max(from.y, to.y)
        val terrain = linkedMapOf<CivPosition, CitizenCollisionSnapshot>()
        val floors = mutableListOf<BoundingBox>()
        // One adjacent-cell edge: at most 4 x 4 x 4 native cells, never a world scan.
        for (x in floor(min(from.x, to.x) - .3).toInt()..floor(max(from.x, to.x) + .3).toInt())
            for (z in floor(min(from.z, to.z) - .3).toInt()..floor(max(from.z, to.z) + .3).toInt()) {
                if (!citizenChunkReady(world, x shr 4, z shr 4)) return
                for (y in floor(low).toInt() - 1..floor(high).toInt()) {
                    if (y !in minimumY until maximumY) return
                    val state = citizenCollisionSnapshot(world, x, y, z)
                    if (state.liquid || state.door) continue
                    val support = state.boxes.filter { it.maxY in low - .001..high + .001 }
                    if (support.isEmpty()) continue
                    terrain[CivPosition(x, y, z)] = state
                    floors += support
                }
            }
        val points = citizenStepProfile(from, to, floors) ?: return
        var previous = from
        for (point in points) {
            if (!allowed(CivPosition(floor(point.x).toInt(), floor(point.y + .001).toInt(), floor(point.z).toInt())) ||
                !permits(previous, point) || !citizenPacketSegmentClear(world, previous, point, false, previous, null)) return
            previous = point
        }
        previous = from
        val refined = points.map { point ->
            Waypoint(previous, point, corner = false, action = CitizenRouteAction.STEP,
                support = entry.support, profileTerrain = terrain).also { previous = point }
        }
        path.removeFirst()
        for (waypoint in refined.asReversed()) path.addFirst(waypoint)
    }

    /** Clip gravity against the swept feet, then validate the actual packet chord above. */
    private fun fall(from: Location, candidate: Location): Location? {
        val floors = mutableListOf<BoundingBox>()
        for (x in floor(min(from.x, candidate.x) - .299).toInt()..floor(max(from.x, candidate.x) + .299).toInt())
            for (z in floor(min(from.z, candidate.z) - .299).toInt()..floor(max(from.z, candidate.z) + .299).toInt()) {
                if (!citizenChunkReady(world, x shr 4, z shr 4)) return null
                for (y in floor(candidate.y).toInt() - 1..floor(from.y).toInt()) {
                    if (y !in minimumY until maximumY) return null
                    floors += citizenCollisionSnapshot(world, x, y, z).boxes.filter {
                        it.maxY <= from.y + .000001 && it.maxY >= candidate.y
                    }
                }
            }
        // Gravity may change only Y: a general body slide can also clip X/Z at a
        // taller neighbouring floor. Keeping its Y but restoring the intended X/Z
        // would move inside that floor. Stay above every support crossed by this
        // packet's horizontal footprint; the caller still validates its full body.
        val horizontalFrom = org.bukkit.util.Vector(from.x, .5, from.z)
        val horizontalTo = org.bukkit.util.Vector(candidate.x, .5, candidate.z)
        val direction = horizontalTo.clone().subtract(horizontalFrom)
        val distance = direction.length()
        for (floor in floors) {
            val footprint = BoundingBox(floor.minX - .299 + .0000001, 0.0, floor.minZ - .299 + .0000001,
                floor.maxX + .299 - .0000001, 1.0, floor.maxZ + .299 - .0000001)
            if (footprint.contains(horizontalFrom) || footprint.contains(horizontalTo) ||
                distance > .0000001 && footprint.rayTrace(horizontalFrom, direction, distance) != null)
                candidate.y = max(candidate.y, floor.maxY)
        }
        return candidate
    }

    /**
     * Remove grid zigzags only inside a built, flat corridor. Every sampled body
     * footprint keeps the original cheap surface; an adjacent lawn never licenses a shortcut.
     */
    private fun straightenCorridor(from: CitizenPoint) {
        val first = path.firstOrNull() ?: return
        if (first.corridorChecked || first.behindHighWater(from)) return
        first.corridorChecked = true
        val candidates = path.take(6).takeWhile { it.action == CitizenRouteAction.WALK && it.profileTerrain == null &&
            abs(it.point.y - from.y) <= .0001 && from.distanceSquared(it.point) <= 25.0 }
        if (candidates.size < 2) return
        var previousPoint = from
        val gridLength = candidates.sumOf { step -> sqrt(previousPoint.distanceSquared(step.point)).also { previousPoint = step.point } }
        // Collinear nodes need no shortcut; keep the next corner available to ordinary rounding.
        if (gridLength - sqrt(from.distanceSquared(candidates.last().point)) < .05) return
        fun built(point: CitizenPoint): Boolean {
            for (x in floor(point.x - .299).toInt()..floor(point.x + .299).toInt())
                for (z in floor(point.z - .299).toInt()..floor(point.z + .299).toInt()) {
                    if (!citizenChunkReady(world, x shr 4, z shr 4) ||
                        !allowed(CivPosition(x, floor(point.y + .001).toInt(), z)) ||
                        travelCost(world.getBlockAt(x, citizenSupportY(point.y), z)).let { !it.isFinite() || it > 1.000001 }) return false
                }
            return citizenPacketSupported(world, point)
        }
        if (!built(from)) return
        val target = candidates.last().point
        val length = sqrt(from.distanceSquared(target))
        if (length < .2) return
        // The eventual packets follow this whole chord. A policy boundary touched at
        // a sample join must not disappear when the ray is split into shorter pieces.
        if (!permits(from, target) || !citizenPacketStepClear(world, from, target, false)) return
        // One candidate and at most twenty-five short chords per admitted segment.
        val count = kotlin.math.ceil(length / .2).toInt()
        var previous = from
        for (sample in 1..count) {
            val fraction = sample.toDouble() / count
            val point = CitizenPoint(from.x + (target.x - from.x) * fraction, from.y,
                from.z + (target.z - from.z) * fraction)
            if (!built(point) || !permits(previous, point) ||
                !citizenPacketSegmentClear(world, previous, point, false, previous, null)) return
            previous = point
        }
        repeat(candidates.size) { path.removeFirst() }
        path.addFirst(Waypoint(from, target, corner = false, straightCorridor = true).also { it.corridorChecked = true })
    }

    /**
     * Round one flat corner inside its existing cell. The quadratic stays within .15
     * blocks of the authored legs; each short chord retains live support, protection,
     * body clearance and surface cost. Stairs, ladders and tight corners keep their nodes.
     */
    private fun roundCorner(from: CitizenPoint) {
        val first = path.firstOrNull()?.takeIf { it.corner } ?: return
        if (first.behindHighWater(from)) return
        val corner = first.point
        val next = path.elementAtOrNull(1)?.point ?: return
        if (abs(from.y - corner.y) > .0001 || abs(next.y - corner.y) > .0001) return
        val incoming = org.bukkit.util.Vector(corner.x - from.x, 0.0, corner.z - from.z)
        val outgoing = org.bukkit.util.Vector(next.x - corner.x, 0.0, next.z - corner.z)
        val distance = incoming.length()
        val ahead = outgoing.length()
        if (distance !in .15.. .45 || ahead < .6) return
        incoming.multiply(1.0 / distance)
        outgoing.multiply(1.0 / ahead)
        val angle = incoming.dot(outgoing)
        if (angle > .999 || angle < -.01) return
        val radius = min(.4, ahead * .45)
        val end = CitizenPoint(corner.x + outgoing.x * radius, corner.y, corner.z + outgoing.z * radius)
        val rounded = (1..6).map { index ->
            val t = index / 6.0
            val a = (1 - t) * (1 - t)
            val b = 2 * (1 - t) * t
            val c = t * t
            CitizenPoint(a * from.x + b * corner.x + c * end.x, corner.y,
                a * from.z + b * corner.z + c * end.z)
        }
        val cost = travelCost(world.getBlockAt(floor(corner.x).toInt(), citizenSupportY(corner.y), floor(corner.z).toInt()))
        var previous = from
        for (point in rounded) {
            for (x in floor(point.x - .299).toInt()..floor(point.x + .299).toInt())
                for (z in floor(point.z - .299).toInt()..floor(point.z + .299).toInt())
                    if (!allowed(CivPosition(x, floor(point.y + .001).toInt(), z))) return
            if (!citizenPacketSupported(world, point) || !permits(previous, point) ||
                !citizenPacketStepClear(world, previous, point, false) ||
                travelCost(world.getBlockAt(floor(point.x).toInt(), citizenSupportY(point.y), floor(point.z).toInt())) > cost) return
            previous = point
        }
        previous = from
        val waypoints = rounded.map { point -> Waypoint(previous, point, false).also { previous = point } }
        path.removeFirst()
        for (waypoint in waypoints.asReversed()) path.addFirst(waypoint)
    }

    /** Anticipate a corner along the validated polyline; never move along this look-ahead chord. */
    private fun heading(from: Location, stride: Double): Float? {
        path.firstOrNull()?.takeIf { it.straightCorridor }?.point?.let { point ->
            val dx = point.x - from.x
            val dz = point.z - from.z
            if (dx * dx + dz * dz > .00001) return Math.toDegrees(atan2(-dx, dz)).toFloat()
        }
        var previous = CitizenPoint(from.x, from.y, from.z)
        // Follow the overall admitted corridor rather than alternately facing every
        // cardinal grid edge. Level changes still terminate the look-ahead below.
        var remaining = (stride * 12).coerceIn(1.5, 3.0)
        var aim = previous
        for (waypoint in path) {
            val next = waypoint.point
            val changesLevel = abs(next.y - from.y) > .13
            // Settle or climb in the current column without turning toward its centre.
            // A step into another column must be faced before lifting off the support.
            if (changesLevel && floor(next.x).toInt() == from.blockX && floor(next.z).toInt() == from.blockZ) break
            val dx = next.x - previous.x
            val dz = next.z - previous.z
            val distance = sqrt(dx * dx + dz * dz)
            if (distance > .0001) {
                val part = min(1.0, remaining / distance)
                aim = CitizenPoint(previous.x + dx * part, from.y, previous.z + dz * part)
                remaining -= distance * part
                if (remaining <= .0001) break
            }
            if (changesLevel) break
            previous = next
        }
        val dx = aim.x - from.x
        val dz = aim.z - from.z
        return if (dx * dx + dz * dz > .00001) Math.toDegrees(atan2(-dx, dz)).toFloat() else null
    }

    private fun fail(reason: String) {
        failure = reason
        searching = false
        preparationPhase = "failed"
        asyncCancellation?.invoke()
        asyncCancellation = null
        path.clear()
        releaseSearch()
    }

    private fun releaseSearch() {
        directSearch?.cancel()
        directSearch = null
        directCapture = null
        directTerrain.clear()
    }

}
