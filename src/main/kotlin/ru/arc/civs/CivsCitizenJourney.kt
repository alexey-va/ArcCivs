package ru.arc.civs

import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.World
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class CitizenPhase { IDLE, TO_STORAGE, TO_WORK, WORKING, PICKUP_READY, PICKING_UP, RETURNING, READY, DELIVERING, PAUSED, CLOSED }

private const val CITIZEN_DELIVERY_RETRY_DELAY_MILLIS = 5_000L

/** Only observed work counts. Producers return cargo to storage; builders finish the session at their worksite. */
internal class CitizenCycle(val token: CitizenToken, private val workMillis: Long, private val completeAtWorkplace: Boolean = false,
    private val pickupAtWorkplace: Boolean = false, carrying: Boolean = false, private val directToWork: Boolean = false,
    private val visualSubaction: Boolean = false, private val pendingStockDelivery: Boolean = false) {
    var phase = CitizenPhase.IDLE
        private set
    var reason: String? = null
        private set
    private var workedMillis = 0L
    private var observedAt = 0L
    private var ticket: UUID? = null
    private var resumePhase = CitizenPhase.TO_STORAGE
    private var deliveryRetryAt: Long? = null
    private val retries = CitizenRetry()
    private var hasCargo = carrying
    val failedAttempts: Int get() = retries.attempts
    val observedWorkMillis: Long get() = workedMillis

    fun returnWithObservedWork() {
        check(phase == CitizenPhase.IDLE)
        phase = CitizenPhase.RETURNING
    }

    private fun travelStart(): CitizenPhase = when {
        pendingStockDelivery -> CitizenPhase.RETURNING
        pickupAtWorkplace && hasCargo -> CitizenPhase.RETURNING
        pickupAtWorkplace || directToWork -> CitizenPhase.TO_WORK
        else -> CitizenPhase.TO_STORAGE
    }
    fun start() { if (phase == CitizenPhase.IDLE) phase = travelStart() }
    fun arrive(now: Long) {
        phase = when (phase) {
            CitizenPhase.TO_STORAGE -> CitizenPhase.TO_WORK
            CitizenPhase.TO_WORK -> CitizenPhase.WORKING.also { workedMillis = 0; observedAt = now }
            CitizenPhase.WORKING -> CitizenPhase.WORKING.also { observedAt = now } // Recovery cannot count the known interval away from work.
            CitizenPhase.RETURNING -> CitizenPhase.READY
            else -> phase
        }
    }
    fun work(now: Long) {
        if (phase != CitizenPhase.WORKING) return
        val delta = now - observedAt
        observedAt = now
        if (delta in 0..2_500) workedMillis += delta
        if (workedMillis >= workMillis) phase = when {
            pickupAtWorkplace -> CitizenPhase.PICKUP_READY
            completeAtWorkplace -> CitizenPhase.READY
            else -> CitizenPhase.RETURNING
        }
    }
    fun deferWork(now: Long) { if (phase == CitizenPhase.WORKING) observedAt = now }
    fun beginPickup(): UUID? {
        if (phase != CitizenPhase.PICKUP_READY) return null
        phase = CitizenPhase.PICKING_UP
        return UUID.randomUUID().also { ticket = it }
    }
    fun acceptsPickup(candidate: UUID): Boolean = phase == CitizenPhase.PICKING_UP && ticket == candidate
    fun pickedUp() {
        if (phase != CitizenPhase.PICKING_UP) return
        hasCargo = true
        ticket = null
        phase = CitizenPhase.RETURNING
    }
    fun failPickup(reason: String?, now: Long) {
        if (phase != CitizenPhase.PICKING_UP) return
        retryTransfer(reason, now, CitizenPhase.PICKUP_READY)
    }
    fun beginDelivery(now: Long): UUID? {
        if (phase != CitizenPhase.READY || !pickupAtWorkplace && !visualSubaction && !pendingStockDelivery && now < token.nextDeliveryAt) return null
        phase = CitizenPhase.DELIVERING
        return UUID.randomUUID().also { ticket = it }
    }
    fun acceptsCompletion(candidate: UUID): Boolean = phase == CitizenPhase.DELIVERING && ticket == candidate
    fun failDelivery(reason: String?, now: Long) {
        if (phase != CitizenPhase.DELIVERING) return
        retryTransfer(reason, now, CitizenPhase.READY)
    }
    private fun retryTransfer(reason: String?, now: Long, readyPhase: CitizenPhase) {
        val retryStatus = when (reason) {
            "city-busy", "construction-credit-full" -> "waiting"
            "storage-busy" -> "storage-busy"
            "materials-missing" -> "waiting-inputs"
            "storage-full" -> if (pickupAtWorkplace) "storage-full" else null
            "control-missing", "storage-unavailable", "building-not-found" -> if (pickupAtWorkplace) "storage-unavailable" else null
            else -> null
        }
        if (retryStatus == null) {
            pause(if (reason == "storage-full") "storage-full" else "delivery-failed", now, true)
            resumePhase = readyPhase
            return
        }
        // A completed observed trip remains valid while supplies or another durable commit are pending.
        this.reason = retryStatus
        resumePhase = readyPhase
        ticket = null
        deliveryRetryAt = now + CITIZEN_DELIVERY_RETRY_DELAY_MILLIS
        phase = CitizenPhase.PAUSED
    }
    fun pause(reason: String, now: Long, retainWork: Boolean) {
        if (phase == CitizenPhase.CLOSED) return
        this.reason = reason
        resumePhase = if (retainWork) CitizenPhase.READY else travelStart()
        ticket = null
        deliveryRetryAt = null
        retries.fail(now)
        if (reason == "route-unreachable" && retries.exhausted) deliveryRetryAt = now + 15_000L
        phase = CitizenPhase.PAUSED
    }
    fun retry(now: Long) {
        if (phase == CitizenPhase.PAUSED && (deliveryRetryAt?.let { now >= it } ?: retries.ready(now))) resume()
    }
    fun resume() {
        if (phase != CitizenPhase.PAUSED) return
        phase = resumePhase
        reason = null
        deliveryRetryAt = null
    }
    fun resumeAfterWander() {
        if (phase != CitizenPhase.PAUSED) return
        resume()
        if (phase == CitizenPhase.READY) phase = CitizenPhase.RETURNING
    }
    fun close() { phase = CitizenPhase.CLOSED; ticket = null; deliveryRetryAt = null }
    fun status(): String = when (phase) {
        CitizenPhase.RETURNING -> when {
            pickupAtWorkplace -> "carrying-cargo"
            completeAtWorkplace -> "moving-to-work"
            else -> "moving-to-storage"
        }
        CitizenPhase.TO_STORAGE -> "moving-to-storage"
        CitizenPhase.TO_WORK -> if (pickupAtWorkplace) "moving-to-pickup" else "moving-to-work"
        CitizenPhase.WORKING -> "working"
        CitizenPhase.PICKUP_READY -> "waiting"
        CitizenPhase.PICKING_UP -> "loading-cargo"
        CitizenPhase.READY -> "waiting"
        CitizenPhase.DELIVERING -> "delivering"
        CitizenPhase.PAUSED -> reason ?: "unavailable"
        CitizenPhase.CLOSED -> "waiting"
        CitizenPhase.IDLE -> "waiting"
    }
}

internal class CitizenRetry(private val exhaustedCooldownMillis: Long? = null) {
    var attempts = 0
        private set
    private var retryAt = 0L
    val exhausted: Boolean get() = attempts >= 3
    val nextAttemptAt: Long? get() = retryAt.takeIf { !exhausted || exhaustedCooldownMillis != null }
    fun ready(now: Long): Boolean = nextAttemptAt?.let { now >= it } == true
    fun fail(now: Long) {
        attempts++
        retryAt = now + if (exhausted && exhaustedCooldownMillis != null) exhaustedCooldownMillis
            else if (attempts == 1) 5_000 else 15_000
    }
}

/** Server-thread only. The false overload and load-level getter never load or retain a chunk on Paper. */
internal fun citizenChunkReady(world: World, x: Int, z: Int): Boolean =
    citizenChunkLoadLevel(world, x, z) == Chunk.LoadLevel.ENTITY_TICKING

internal fun citizenChunkLoadLevel(world: World, x: Int, z: Int): Chunk.LoadLevel? =
    if (world.isChunkLoaded(x, z)) world.getChunkAt(x, z, false).loadLevel else null

internal data class CitizenChunks(val minX: Int, val maxX: Int, val minZ: Int, val maxZ: Int) {
    fun contains(x: Int, z: Int) = x in minX..maxX && z in minZ..maxZ
    // The one-chunk search margin only needs loaded terrain; movement needs entity ticking.
    fun ready(world: World): Boolean = (minX..maxX).all { x -> (minZ..maxZ).all { z ->
        if (x == minX || x == maxX || z == minZ || z == maxZ) world.isChunkLoaded(x, z)
        else citizenChunkReady(world, x, z)
    } }
    companion object {
        fun between(x1: Int, z1: Int, x2: Int, z2: Int) = CitizenChunks(
            (minOf(x1, x2) shr 4) - 1, (maxOf(x1, x2) shr 4) + 1,
            (minOf(z1, z2) shr 4) - 1, (maxOf(z1, z2) shr 4) + 1)
    }
}

internal fun CivPosition.location(world: World) = Location(world, x + 0.5, y.toDouble(), z + 0.5)
internal fun CivPosition.point() = CitizenPoint(x + 0.5, y.toDouble(), z + 0.5)
internal fun Location.point() = CitizenPoint(x, y, z)


/** Horizontal footprints stay off transit routes, except the departure and actual destination buildings. */
internal fun citizenRouteCellAllowed(city: Settlement, allowed: Set<UUID>, x: Int, z: Int): Boolean =
    city.contains(x, z) && civsProtectedBuildings(city).none { it.id !in allowed && !civsPublicTransit(it, x, z) &&
        (x in it.bounds.min.x..it.bounds.max.x && z in it.bounds.min.z..it.bounds.max.z || CivsWorksiteLayouts.isField(it, x, z)) }

/** Only an observed mine leg may pass below another building, with headroom below its foundation. */
internal fun citizenRoutePointAllowed(city: Settlement, allowed: Set<UUID>, point: CivPosition, mine: CivBuilding? = null): Boolean {
    val bottom = mine?.takeIf { it.type == BuildingType.QUARRY }?.let { building ->
        CivsMinePlan.forBuilding(building)?.bottom?.let { CivsWorksiteLayouts.point(building, it) }
    }
    return city.contains(point.x, point.z) && civsProtectedBuildings(city).none { building ->
        building.id !in allowed && !civsPublicTransit(building, point.x, point.z) &&
            (point.x in building.bounds.min.x..building.bounds.max.x && point.z in building.bounds.min.z..building.bounds.max.z ||
                CivsWorksiteLayouts.isField(building, point.x, point.z)) &&
            (bottom == null || point.y >= bottom.y || !CivsMinePlan.belowFoundation(building, point.y, headroom = 1))
    }
}

internal class CitizenMinePassage(val completed: (CitizenPoint) -> Boolean, val allows: (CitizenPoint) -> Boolean)

/** Capture immutable completed prefixes once, before the async search inspects edges. */
internal fun citizenMineRoutePassage(mine: CivBuilding, accessStep: Long,
                                  state: CivMinePlanState?, accessSections: Map<String, Long>? = null): CitizenMinePassage {
    val current = CivsMinePlan.forBuilding(mine) ?: return CitizenMinePassage({ false }, { false })
    val bottom = CivsWorksiteLayouts.point(mine, current.bottom)
    val sections = (accessSections ?: state?.takeIf { it.version == current.version }?.sectionSteps).orEmpty().toMap()
    val v5 = CivsMineProgress.v5State(state)
    val prefixes = buildList {
        add(current to accessStep)
        val v4 = if (state?.version == 4) state.nextStep else state?.v4Step
        if (v4 != null && v4 > 0) CivsMinePlan.forBuilding(mine, 4)?.let { add(it to v4) }
        val v3 = if (state?.version == 3) state.nextStep else state?.v3Step
        if (v3 != null && v3 > 0) CivsMinePlan.forBuilding(mine, 3)?.let { add(it to v3) }
        val v2 = if (state?.version == 2) state.nextStep else state?.v2Step
        if (v2 != null && v2 > 0) CivsMinePlan.forBuilding(mine, 2)?.let { add(it to v2) }
        if (v5 != null && current.version != 5) CivsMinePlan.forBuilding(mine, 5)?.let { add(it to v5.nextStep) }
    }
    val legacy = state?.legacyStep?.takeIf { it > 0 }?.let(current::legacyCompleted)
    fun legacyOpen(p: CivPosition) = legacy?.containsKey(p) == true && legacy[p].let {
        it == null || it.startsWith("minecraft:ladder[") || it == "minecraft:torch"
    }
    val completed: (CitizenPoint) -> Boolean = { point ->
        val local = CivsMinePlan.localPoint(mine, CivPosition(kotlin.math.floor(point.x).toInt(),
            point.y.roundToInt(), kotlin.math.floor(point.z).toInt()))
        prefixes.any { (plan, prefix) -> plan.builtStanding(local, prefix,
            if (plan.version == current.version) sections else if (plan.version == 5) v5?.sectionSteps.orEmpty() else emptyMap()) } ||
            legacyOpen(local) && legacyOpen(local.copy(y = local.y + 1)) ||
            CivsMineProgress.priorStanding(mine, state, CivPosition(kotlin.math.floor(point.x).toInt(),
                point.y.roundToInt(), kotlin.math.floor(point.z).toInt()))
    }
    return CitizenMinePassage(completed) { point -> point.y >= bottom.y - .125 || completed(point) }
}

internal fun citizenMineWorkRouteAllowed(mine: CivBuilding, point: CitizenPoint, accessStep: Long,
                                        state: CivMinePlanState? = null, accessSections: Map<String, Long>? = null): Boolean =
    citizenMineRoutePassage(mine, accessStep, state, accessSections).allows(point)

/** Outside approaches enter the real building before a work leg uses its underground passages. */
internal fun citizenMineEntranceWaypoint(mine: CivBuilding, from: CitizenPoint, target: CivPosition,
                                       passage: CitizenMinePassage): CivPosition? {
    fun inside(point: CitizenPoint): Boolean {
        val standing = CivPosition(kotlin.math.floor(point.x).toInt(), point.y.roundToInt(),
            kotlin.math.floor(point.z).toInt())
        // Future authored cells are still outside; completed legacy access counts at every height.
        return mine.bounds.contains(standing.x, standing.y, standing.z) || passage.completed(point)
    }
    return mine.entrance.takeIf {
        !citizenNearEndpoint(from, it) && !inside(from) && inside(target.point())
    }
}

internal fun citizenRouteBuildings(city: Settlement, plan: CitizenPlan, target: CivPosition, from: CivPosition): Set<UUID> =
    civsProtectedBuildings(city).filter { building ->
        val mineDeparture = if (building.type == BuildingType.QUARRY && building.id == plan.token.assignmentId) {
            val saved = city.minePlans?.get(building.id.toString())
            val access = saved?.takeIf { it.version == CivsMinePlan.VERSION }?.nextStep ?: 0
            citizenMineRoutePassage(building, access, saved).completed(from.point())
        } else false
        building.bounds.contains(from.x, from.y, from.z) || mineDeparture || building.bounds.contains(target.x, target.y, target.z) ||
            building.control == target ||
            (target == plan.workplace || target == plan.storage) && building.id == plan.token.assignmentId
    }.mapTo(hashSetOf()) { it.id }.also { allowed ->
        plan.courier?.cargo?.takeIf { it.sourceWorkerId != null }?.let { allowed += it.sourceBuildingId }
    }

/** Authored shafts split underground travel and the return outside into actual entrance, climb and gallery legs. */
internal fun citizenMineWaypoint(city: Settlement, world: World, from: Location, target: CivPosition): CivPosition? {
    for (mine in city.buildings.filter { it.type == BuildingType.QUARRY }) {
        val geometry = CivsMinePlan.forBuilding(mine) ?: continue
        val bottom = CivsWorksiteLayouts.point(mine, geometry.bottom)
        val state = city.minePlans?.get(mine.id.toString())
        val access = state?.takeIf { it.version == CivsMinePlan.VERSION }?.nextStep ?: 0
        fun local(x: Int, y: Int, z: Int) = CivsMinePlan.localPoint(mine, CivPosition(x, y, z))
        fun inMine(x: Int, y: Int, z: Int): Boolean = y < bottom.y &&
            (geometry.containsWorkStanding(local(x, y, z)) ||
                citizenMineWorkRouteAllowed(mine, CitizenPoint(x + .5, y.toDouble(), z + .5), access, state))
        val fromBelow = inMine(from.blockX, from.blockY, from.blockZ)
        val targetBelow = inMine(target.x, target.y, target.z)
        val leaving = (fromBelow || mine.bounds.contains(from.blockX, from.blockY, from.blockZ)) &&
            !targetBelow && !mine.bounds.contains(target.x, target.y, target.z)
        // Equal-height outdoor storage can still require a climb out of the mine.
        // After reaching the shaft bottom, finish the authored stairs at the real entrance.
        if (!leaving && (abs(from.y - target.y) <= 1.01 || !fromBelow && !targetBelow)) continue
        val onShaft = from.blockX == bottom.x && from.blockZ == bottom.z
        // A migrated resident must be able to leave its retained V3 ladder before
        // ordinary V4 receipts remodel that old access. Never route into future V3 cells.
        val oldPrefix = if (state?.version == 3) state.nextStep else state?.v3Step ?: 0
        val old = CivsMinePlan.forBuilding(mine, 3)?.takeIf { oldPrefix > 0 &&
            it.builtStanding(local(from.blockX, from.y.roundToInt(), from.blockZ), oldPrefix) &&
            !geometry.builtStanding(local(from.blockX, from.y.roundToInt(), from.blockZ), access) }
        val waypoint = if (old != null && !onShaft) {
            val oldLower = CivsWorksiteLayouts.point(mine, old.extensionLadder)
            val transfer = bottom.copy(y = mine.anchor.y + old.transferFeetY)
            when {
                from.y < transfer.y - .5 && (from.blockX != oldLower.x || from.blockZ != oldLower.z) ->
                    oldLower.copy(y = from.blockY)
                from.y < transfer.y - .5 -> oldLower
                else -> transfer
            }
        } else when {
            leaving && from.y >= bottom.y - .5 -> mine.entrance
            from.y > bottom.y + .5 -> bottom
            !onShaft -> bottom.copy(y = from.blockY)
            leaving -> bottom
            target.y >= bottom.y -> bottom
            else -> bottom.copy(y = target.y)
        }
        if (waypoint == target || citizenNearEndpoint(from.point(), waypoint)) continue
        if (citizenStandable(world, waypoint) && citizenPacketBodyClear(world, waypoint.point(), false)) return waypoint
    }
    return null
}
