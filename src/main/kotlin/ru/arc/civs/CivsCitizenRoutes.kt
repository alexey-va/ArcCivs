package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.util.BoundingBox
import ru.arc.observability.StructuredDebugLine
import ru.arc.paper.npc.PacketPlayerNpc
import java.util.UUID
import java.util.logging.Level
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Route admission, progress recovery and cancellation share one typed actor route. */
internal class CivsCitizenRoutes(
    private val runtime: CitizenRuntime,
    private val collisionsOwner: () -> CivsCitizenCollisions,
    private val shelterOwner: () -> CivsCitizenShelters,
    private val couriersOwner: () -> CivsCitizenCouriers,
    private val leisureOwner: () -> CivsCitizenLeisure,
    private val workOwner: () -> CivsCitizenWork,
    private val logisticsOwner: () -> CivsCitizenLogistics,
    private val lifeOwner: () -> CivsCitizenLife,
    private val navigation: CivsResidentNavigation,
    private val paths: CivsPaths,
    private val surfaceSpeed: (org.bukkit.block.Block) -> Double,
    private val workPositionFailed: (Settlement, CivWorker, CivWorldWork) -> Boolean,
    private val recoveryClock: () -> Long,
    private val courierFailed: (UUID, UUID, CivCargo) -> Unit,
    private val remove: (CitizenActor, String) -> Unit,
    private val labCourierRecoveryAllowed: (Settlement, CivWorker, CivCourierPlan, List<CivPosition>) -> Boolean = { _, _, _, _ -> false },
    private val foodRecoveryAllowed: (Settlement, CivPosition) -> Boolean = { _, _ -> false },
    private val nightShelterRecoveryAllowed: (Settlement, CivPosition) -> Boolean = { _, _ -> false },
    private val workFrame: (UUID) -> CivSettlementWorkFrame? = { id ->
        runtime.cityProvider().firstOrNull { it.id == id }?.let {
            CivSettlementWorkFrame(it, CivResidentReadFrame(), CivSettlementSupplyRead.EMPTY, CivsPaths.RetainedRoads.EMPTY,
                CivRouteTerritory(it))
        }
    },
) {
    private val collisions get() = collisionsOwner()
    private val shelter get() = shelterOwner()
    private val couriers get() = couriersOwner()
    private val leisure get() = leisureOwner()
    private val workRuntime get() = workOwner()
    private val logistics get() = logisticsOwner()
    private val life get() = lifeOwner()

    /** Ordinary preparation failures retry quietly; food recovery checks its own physical intent and deadline. */
    fun retryPreparation(actor: CitizenActor, route: CitizenRoute, now: Long): Boolean {
        val reason = route.motion.failure?.takeIf { it in PREPARATION_FAILURES } ?: return false
        val retryAt = route.preparationRetryAt ?: (now + 5_000).also { route.preparationRetryAt = it }
        if (now >= retryAt) {
            CivsDiagnostics.event("citizen_route_preparing", "city" to actor.cityId,
                "worker" to actor.workerId, "cycle" to actor.plan.token.completedCycles,
                "phase" to actor.cycle.phase, "state" to "retry", "reason" to reason,
                "actual" to actor.npc.location.point().coordinates(), "target" to route.target.point().coordinates())
            cancelRoute(actor)
        }
        return true
    }

    fun startRoute(actor: CitizenActor, world: World, target: CivPosition, now: Long, idleAnchor: CivPosition? = null,
                   storageStandingOverride: CivPosition? = null): Boolean {
        actor.pendingChunkTarget = null
        if (!shelter.wake(actor)) return false
        val from = actor.npc.location
        val frame = workFrame(actor.cityId) ?: return false
        val city = frame.city
        // The endpoint must be visible to the chunk owner before arrival/standing reads or
        // route admission. Missing terrain is a loading wait, never an invented arrival.
        if (citizenRouteWithinReach(from.point(), target.point(), CivsLogistics.maximumTransferDistance(city)) &&
            !CitizenChunks.between(from.blockX, from.blockZ, target.x, target.z).ready(world)) {
            actor.pendingChunkTarget = target
            return true // Accepted preparation, not physical arrival; callers keep their existing intent.
        }
        if (workRuntime.inboundMineAccess(actor, city, target, idleAnchor) != null &&
            city.workers.firstOrNull { it.id == actor.workerId }?.let { workRuntime.waitingForMineAccess(actor, city, it) } == true) {
            actor.cycle.deferWork(now)
            runtime.statuses[actor.workerId] = "mine-waiting"
            return false
        }
        val waitingMine = waitingMine(actor, city, idleAnchor)
        if (waitingMine != null && !outside(waitingMine, target.x, target.z)) return false
        val visit = actor.mineVisit?.takeIf { idleAnchor == null && it.owns(target) && !actor.sheltering &&
            actor.attacker == null && actor.foodSource == null && actor.mealVisit == null }
        // A valid hand reach does not depend on the availability of the preferred standing
        // cell. Likewise, any visible side of the real storage barrel is a valid arrival.
        if (idleAnchor == null && !actor.sheltering && !actor.returningToWarehouse &&
            actor.plan.idleReason in listOf(null, "placing") && arrivedAt(actor, city, world, target)) {
            return true
        }
        // The return must physically reach its original standing before the reserve session resumes.
        val storage = if (visit != null) null else storageForTarget(actor, city, target)
        if (storage != null) {
            val standing = storageStandingOverride ?: storageStanding(actor, city, world, target, storage, idleAnchor == null)
            if (standing == null) {
                actor.targets.remove(target)
                pause(actor, "storage-unavailable", now)
                return false
            }
            if (storageStandingOverride == null) actor.targets[target] = standing
        }
        val resolved = when {
            visit != null || idleAnchor != null && storage == null -> target
            storage != null && storageStandingOverride != null -> storageStandingOverride
            else -> resolvedTarget(actor, target)
        }
        val mine = routeMine(actor, city).takeIf { idleAnchor == null }
        val workMine = mine?.takeIf { visit != null || actor.cycle.phase == CitizenPhase.TO_WORK && actor.plan.worldWork?.miningStep != null }
        val minePassage = visit?.passage ?: workMine?.let { building ->
            val work = actor.plan.worldWork
            citizenMineRoutePassage(building, work?.miningAccessStep ?: work?.miningStep ?: 0,
                city.minePlans?.get(building.id.toString()), work?.miningAccessSections)
        }
        val entrance = workMine?.let { citizenMineEntranceWaypoint(it, from.point(), resolved, requireNotNull(minePassage)) }
        val waypoint = entrance ?: citizenMineWaypoint(city, world, from, resolved)
        val to = (waypoint ?: resolved).location(world)
        if (idleAnchor != null && !collisions.idleDestinationFree(actor, to)) return false
        val distance = from.distance(to)
        if (!citizenRouteWithinReach(from.point(), to.point(), CivsLogistics.maximumTransferDistance(city))) {
            if (idleAnchor == null && actor.plan.idleReason in listOf(null, "placing")) pauseNavigation(actor, "route-unreachable", "outside-route-range", to, now)
            return false
        }
        val chunks = CitizenChunks.between(from.blockX, from.blockZ, resolved.x, resolved.z)
        if (!chunks.ready(world)) {
            actor.pendingChunkTarget = resolved
            return true
        }
        if (!citizenControlClear(city, resolved) || !citizenStandable(world, resolved) ||
            idleAnchor != null && !citizenIdleTerrain(world, resolved, city.buildings)) {
            if (idleAnchor == null && actor.plan.idleReason in listOf(null, "placing"))
                pauseNavigation(actor, "route-unreachable", "target-not-standable", to, now,
                    target.takeIf { waypoint == null })
            return false
        }
        val outsideWaitingMine = waitingMine != null && outside(waitingMine, from.blockX, from.blockZ)
        val allowed = (citizenRouteBuildings(city, actor.plan, target, CivPosition(from.blockX, from.blockY, from.blockZ)) +
            listOfNotNull(idleAnchor?.let { citizenControlBuilding(city, it)?.id }, visit?.key?.building?.id)).let {
                if (outsideWaitingMine) it - requireNotNull(waitingMine).id else it
            }
        val idleTransit = idleAnchor != null && !citizenWithinIdleArea(idleAnchor, from.point())
        val mealTarget = actor.mealVisit?.target?.takeIf { it == target }
        val preferCourierRoads = idleAnchor == null && waypoint == null && actor.plan.type == BuildingType.WAREHOUSE &&
            city.inventory?.mode == CivStorageMode.PHYSICAL && actor.attacker == null && actor.combatDestination == null &&
            actor.foodSource == null && actor.mealVisit == null && actor.plan.courier?.let { courier ->
                when (actor.cycle.phase) {
                    CitizenPhase.TO_WORK -> !courier.carrying && target == actor.plan.workplace
                    CitizenPhase.RETURNING -> courier.carrying && target == actor.plan.storage
                    else -> false
                }
            } == true
        val trip = if (actor.collectionSeek == null && actor.collectionHome == null &&
            workRuntime.workingTravel(actor)) paths.begin(city, preferCourierRoads, frame.roads, frame.territory) else null
        val inbound = actor.plan.courier?.takeIf { actor.plan.type == BuildingType.WAREHOUSE && !it.carrying &&
            actor.cycle.phase == CitizenPhase.TO_WORK && it.cargo.sourceWorkerId == null && idleAnchor == null &&
            !actor.sheltering && !actor.returningToWarehouse && actor.foodSource == null && actor.mealVisit == null }
        if (inbound == null || trip == null) actor.courierInboundPrefix = null
        else {
            actor.courierInboundPrefix?.start(city, inbound.cargo, inbound.destinationBuildingId, from.point())
            if (actor.courierInboundPrefix == null)
                actor.courierInboundPrefix = CitizenCourierInboundPrefix.begin(city, inbound.cargo, inbound.destinationBuildingId, from.point())
        }
        // Even leisure and interrupted work prefer the existing streets; only working trips teach new corridors.
        val routeCosts = trip ?: paths.begin(city, roads = frame.roads, territory = frame.territory)
        // Only the outside leg to the real entrance is ordinary city travel. Internal
        // shaft/gallery waypoints must retain the same completed-prefix restrictions.
        val routeMine = mine.takeIf { entrance == null }
        val hint = actor.courierReturnHint
        actor.courierReturnHint = null
        val corridor = actor.plan.courier?.takeIf { idleAnchor == null && it.carrying &&
            actor.cycle.phase == CitizenPhase.RETURNING && target == actor.plan.storage && waypoint == null }
            ?.let { hint?.corridor(city, it.cargo, from.point(), to.point()) }
        val origin = from.point()
        val minimumY = world.minHeight
        val maximumY = world.maxHeight
        val climbable = org.bukkit.Tag.CLIMBABLE.values.toSet()
        val leaves = org.bukkit.Tag.LEAVES.values.toSet()
        val logs = org.bukkit.Tag.LOGS.values.toSet()
        val supportCost = (surfaceSpeed as? CivsFootTraffic)?.detachedSupportCost(routeCosts.weights, logs, leaves)
        fun outsideSnapshot(building: CivBuilding, x: Int, z: Int) =
            x !in building.bounds.min.x..building.bounds.max.x || z !in building.bounds.min.z..building.bounds.max.z
        val territory = frame.territory
        val controls = territory.controls
        val mineBottom = citizenRouteMineBottom(routeMine)
        val policy = CitizenSnapshotPolicy({ point, material ->
            territory.allows(point, allowed, mineBottom) && controls.clear(point) &&
                (!outsideWaitingMine || outsideSnapshot(requireNotNull(waitingMine), point.x, point.z)) &&
                (idleAnchor == null || idleTransit || citizenIdleRouteAllowed(idleAnchor, origin, point, mealTarget) &&
                    citizenIdleTerrain(point, minimumY, maximumY, city.buildings, material, climbable, leaves, logs))
        }, { first, second -> controls.stepClear(first, second) &&
            (waitingMine == null || !outsideSnapshot(waitingMine, kotlin.math.floor(first.x).toInt(), kotlin.math.floor(first.z).toInt()) ||
                outsideSnapshot(waitingMine, kotlin.math.floor(second.x).toInt(), kotlin.math.floor(second.z).toInt())) &&
            (entrance != null || minePassage == null || minePassage.allows(second)) },
            reuseKey = if (trip != null && idleAnchor == null && mine == null && waitingMine == null && corridor == null &&
                !actor.sheltering && actor.attacker == null && actor.combatDestination == null &&
                actor.foodSource == null && actor.mealVisit == null && actor.workDetour == null &&
                actor.collectionSeek == null && actor.collectionHome == null && actor.tavernService == null && actor.social.visit == null)
                CitizenRouteReuseKey.of(routeCosts, allowed) else null, supportCost = supportCost)
        val motion = navigation.request(world, from, to, { point -> policy.allowed(point) { p ->
            if (p.y in minimumY until maximumY && world.isChunkLoaded(p.x shr 4, p.z shr 4)) world.getBlockAt(p.x, p.y, p.z).type
            else null
        } }, policy.stepAllowed, { ground -> routeCosts.cost(ground, surfaceSpeed) }, corridor, policy)
        val timeout = (10_000 + distance * 1_500).toLong().coerceAtMost(90_000)
        actor.route = CitizenRoute(from.point(), to, chunks, now, timeout,
            allowed, motion, trip, target, waypoint != null, idleAnchor)
        workRuntime.bindMineAccess(actor, city, requireNotNull(actor.route))
        actor.route?.progressTick = runtime.navigationTick
        val storageReason = when {
            actor.plan.type != BuildingType.BUILDER_HUT || idleAnchor != null || actor.sheltering || actor.attacker != null -> null
            actor.foodSource != null -> "food"
            actor.cycle.phase == CitizenPhase.RETURNING -> "unload"
            actor.plan.idleReason == "waiting-materials" -> "next-required-type"
            actor.cycle.phase == CitizenPhase.TO_STORAGE -> "work-materials"
            else -> null
        }
        if (storage != null && storageReason != null) {
            actor.route?.storageReason = storageReason
            actor.route?.storageBuilding = storage.id
            val worker = city.workers.firstOrNull { it.id == actor.workerId }
            CivsDiagnostics.event("citizen_storage_trip", "city" to actor.cityId, "worker" to actor.workerId,
                "order" to actor.plan.token.constructionId, "batch" to city.construction?.workDone,
                "construction_phase" to city.construction?.phase, "credit" to city.construction?.workCredit,
                "source" to storage.id, "target" to target, "from" to from.point(),
                "phase" to actor.cycle.phase, "reason" to storageReason, "supplies_needed" to actor.plan.suppliesNeeded,
                "carried" to worker?.let { CivsEquipment.available(it, emptyMap()) },
                "route_started_at" to now, "state" to "planning", "path_nodes" to motion.nodeCount)
        }
        look(actor.npc, from.yaw, 0f)
        return true
    }

    private fun storageStanding(actor: CitizenActor, city: Settlement, world: World, target: CivPosition,
                                storage: CivBuilding, allowCached: Boolean): CivPosition? {
        val control = requireNotNull(storage.control)
        val safe: (CivPosition) -> Boolean = { storagePointClear(actor, city, world, it) }
        val cached = actor.targets[target]?.takeIf { allowCached && citizenAtStorage(world, it.point(), control) && safe(it) }
        return cached ?: citizenStorageTarget(city, storage, world, safe)
            ?: storage.takeIf { !citizenStorageReady(world, control) && it.phase != BuildingPhase.ACTIVE && city.construction?.buildingId == it.id }
                ?.entrance?.takeIf { citizenStandable(world, it) }
    }

    fun retryDeliveryStanding(actor: CitizenActor, route: CitizenRoute, courier: CivCourierPlan, blocker: UUID, now: Long): Boolean {
        val world = actor.npc.world
        val city = runtime.cityProvider().firstOrNull { it.id == actor.cityId && it.worldId == world.uid &&
            it.pendingDeletion == null && it.inventory?.mode == CivStorageMode.PHYSICAL } ?: return false
        if (city.workers.firstOrNull { it.id == actor.workerId }?.cargo != courier.cargo) return false
        val control = route.authored
        val storage = CivsLogistics.destination(city, courier.cargo)?.takeIf {
            it.id == courier.destinationBuildingId && it.control == control
        } ?: return false
        val oldStanding = route.target.point()
        val standing = citizenStorageTarget(city, storage, world) { point ->
            point.point() != oldStanding && citizenRoutePointAllowed(city, route.allowedBuildings, point) &&
                storagePointClear(actor, city, world, point) &&
                citizenCanReachBlock(world, point.location(world).add(0.0, actor.npc.eyeHeight, 0.0), control)
        } ?: return false
        cancelRoute(actor)
        // A failed path to this side must not replace the usual cached storage approach.
        if (!startRoute(actor, world, control, now, storageStandingOverride = standing)) return false
        CivsDiagnostics.event("citizen_route_progress", "city" to city.id, "worker" to actor.workerId,
            "action" to "retarget", "reason" to "occupied-storage-arrival", "phase" to actor.cycle.phase,
            "shipment" to courier.cargo.id, "blocking_resident" to blocker, "storage" to storage.id,
            "from" to oldStanding.coordinates(), "target" to standing, "actual" to actor.npc.location.point().coordinates())
        return true
    }

    private fun storagePointClear(actor: CitizenActor, city: Settlement, world: World, point: CivPosition): Boolean =
        citizenStandable(world, point) && citizenControlClear(city, point) &&
            citizenPacketBodyClear(world, point.point(), false) && point.location(world).let { destination ->
                collisions.residentMoveAllowed(actor, destination, destination)
            }

    /** Keep a clear food approach stable; other residents' bodies and arrival goals retain their existing reservations. */
    fun foodStanding(actor: CitizenActor, city: Settlement, source: CivResidentFoodSource): CivPosition? {
        val building = city.building(source.buildingId) ?: return null
        val world = actor.npc.world
        val safe: (CivPosition) -> Boolean = { point ->
            storagePointClear(actor, city, world, point) && collisions.idleDestinationFree(actor, point.location(world))
        }
        val blocked = actor.foodRouteFailure?.takeIf { !it.alternateTried && it.matches(actor, source, recoveryClock()) }
        // Keep a walking alternative once admitted. A different resident's reservation must not restart it each pulse.
        if (blocked != null && actor.route == null) {
            citizenCollectedHomeTarget(city, building, world, source.container, source.standing) {
                it != blocked.source.standing && safe(it)
            }?.let { alternate ->
                blocked.alternateTried = true
                return alternate
            }
        }
        return citizenCollectedHomeTarget(city, building, world, source.container, source.standing, safe)
    }

    /** Work retries may replace the route's timer, so a blocked food holder keeps its own short-lived witness. */
    fun recoverFoodRoute(actor: CitizenActor, city: Settlement, worker: CivWorker, route: CitizenRoute,
                         source: CivResidentFoodSource, now: Long, currentSource: () -> CivResidentFoodSource?): Boolean {
        val cause = route.completion?.cancelReason ?: return false
        // The solver reports this reason only after a required probe exhausts the capture limit.
        val exhaustedCapture = cause == "capture-budget" && !route.motion.searching && !route.motion.continuation &&
            route.replanAttempts == 0 && route.from.distanceSquared(actor.npc.location.point()) <= .25
        if (route.motion.failure != cause || cause !in FOOD_TERRAIN_FAILURES && !exhaustedCapture) return false
        if (route.authored != source.standing ||
            route.intermediate || route.idleAnchor != null || route.motion.waitingForCollision || route.nativeCollisionTicks > 0 ||
            actor.collisionResident != null || route.replanReason == "player-blocked" || route.replanReason == "resident-blocked" ||
            actor.foodSource != source.buildingId || actor.foodPickupSource != source || source.servingFor != null ||
            source.servingTicket != null || worker.cargo != null || actor.plan.courier?.carrying == true ||
            actor.sheltering || actor.sleeping || actor.alarmSheltering || actor.attacker != null ||
            actor.combatDestination != null || actor.mineVisit != null || actor.returningToWarehouse ||
            actor.outputReturn != null || actor.mealVisit != null || actor.tavernService != null ||
            actor.collectionSeek != null || actor.collectionHome != null || navigationHeld(actor) ||
            actor.bodyContact?.token == actor.plan.token || city.inventory?.mode == CivStorageMode.VIRTUAL) {
            actor.foodRouteFailure = null
            return false
        }
        val clock = recoveryClock()
        // Count a timely walking retry even when its native capture spends longer than the retry window.
        val attemptAt = if (exhaustedCapture) {
            clock - (now - route.startedAt).coerceIn(0L, FOOD_CAPTURE_RETRY_PREPARATION_LIMIT)
        } else clock
        val previous = actor.foodRouteFailure?.takeIf { attemptAt >= it.lastFailureAt && it.matches(actor, source, attemptAt) }
        if (previous == null) {
            actor.foodRouteFailure = CitizenFoodRouteFailure(actor.plan.token, source, actor.npc.location.point(), clock)
            return false
        }
        previous.lastFailureAt = clock
        if (clock - previous.firstFailureAt < 60_000L || runtime.recoveryBudget == 0 ||
            actor.nextRecoveryAt?.let { clock < it } == true) return false
        // Bound denied probes too. The ordinary walking retry and its work cooldown continue.
        if (currentSource()?.copy(standing = source.standing) != source) {
            actor.foodRouteFailure = null
            return false
        }
        val recovered = recoverNavigation(actor, route.target, now, food = source)
        actor.nextRecoveryAt = clock + if (recovered) 60_000L else 5_000L
        if (recovered) actor.foodRouteFailure = null
        return recovered
    }

    /** Called only after the owner's ordinary arrival proof, before cancelling its route. */
    fun captureCourierReturnHint(actor: CitizenActor, city: Settlement) {
        actor.courierReturnHint = null
        val courier = actor.plan.courier?.takeIf { actor.plan.type == BuildingType.WAREHOUSE && !it.carrying &&
            it.cargo.sourceWorkerId == null && actor.cycle.phase == CitizenPhase.TO_WORK } ?: return
        if (city.inventory?.mode != CivStorageMode.PHYSICAL || actor.npc.world.uid != city.worldId ||
            actor.sheltering || actor.returningToWarehouse || actor.foodSource != null || actor.mealVisit != null) return
        val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: return
        if (worker.assignment != courier.cargo.warehouseId ||
            city.building(courier.cargo.warehouseId)?.type != BuildingType.WAREHOUSE) return
        val route = actor.route?.takeIf { !it.intermediate && it.authored == actor.plan.workplace } ?: return
        val destination = city.building(courier.destinationBuildingId)?.control ?: return
        val trip = route.trip ?: return
        val prefix = actor.courierInboundPrefix
        if (prefix != null) {
            val depot = city.building(courier.destinationBuildingId)?.takeIf { it.id == courier.cargo.warehouseId &&
                it.type == BuildingType.WAREHOUSE && it.control == actor.plan.storage } ?: return
            val standing = storageStanding(actor, city, actor.npc.world, actor.plan.storage, depot, true) ?: return
            actor.targets[actor.plan.storage] = standing
            actor.courierReturnHint = prefix.finish(city, courier.cargo, courier.destinationBuildingId,
                trip, actor.npc.location.point(), standing.point())
        } else {
            if (route.motion.forwardProgress <= 0.0 || !citizenAtStorage(actor.npc.world, route.from, destination)) return
            actor.courierReturnHint = CitizenCourierReturnHint.capture(city, courier.cargo, trip, route.from, actor.npc.location.point())
        }
    }

    /** Fresh route planning still replaces every motion; only the observed same-shipment prefix survives. */
    fun cancelForContinuation(actor: CitizenActor, city: Settlement, route: CitizenRoute) {
        if (route.motion.continuation && route.motion.finished && route.motion.failure == null)
            CivsDiagnostics.event("citizen_route_partial", "city" to actor.cityId, "worker" to actor.workerId,
                "cycle" to actor.plan.token.completedCycles, "phase" to actor.cycle.phase,
                "reason" to route.motion.continuationReason, "from" to route.from.coordinates(),
                "actual" to actor.npc.location.point().coordinates(), "endpoint" to route.path?.end?.coordinates(),
                "authored_goal" to route.authored, "target" to route.target.point().coordinates(),
                "path_progress" to route.motion.forwardProgress, "path_nodes" to route.motion.nodeCount,
                "captured_blocks" to route.motion.capturedBlocks, "native_shape_reads" to route.motion.nativeShapeReads)
        val prefix = actor.courierInboundPrefix
        val courier = actor.plan.courier
        if (prefix != null && courier != null && actor.cycle.phase == CitizenPhase.TO_WORK && !courier.carrying)
            route.trip?.let { prefix.append(city, courier.cargo, courier.destinationBuildingId, it,
                actor.npc.location.point()) }
        cancelRoute(actor)
        actor.courierInboundPrefix = prefix.takeIf { courier != null && actor.cycle.phase == CitizenPhase.TO_WORK && !courier.carrying }
    }

    fun observePath(route: CitizenRoute) {
        route.motion.endpoint?.let { route.path = CitizenPathSnapshot(route.motion.nodeCount, it, false) }
    }

    fun pauseNavigation(actor: CitizenActor, status: String, detail: String, target: Location, now: Long,
                                preflightStanding: CivPosition? = null) {
        if (actor.mineVisit != null) {
            actor.mineVisit?.failure = detail
            cancelRoute(actor)
            actor.cycle.deferWork(now)
            runtime.statuses[actor.workerId] = "route-unreachable"
            return
        }
        if (actor.workDetour != null) {
            cancelRoute(actor)
            actor.cycle.deferWork(now)
            actor.lifeRetryAt = now + 5_000
            runtime.statuses[actor.workerId] = status
            return
        }
        navigationDiagnostic(actor, detail, target, now, terminal = true)
        if (actor.route?.completion?.cancelReason == "resident-blocked") { pause(actor, status, now); return }
        if (actor.route?.let { it.nativeCollisionTicks > 0 || it.motion.waitingForCollision ||
                it.previousMotion?.waitingForCollision == true } == true) {
            actor.arrivalWait = null
            // Native players may legitimately keep a passage runtime.closed. Owned residents use bounded retries.
            if (actor.collisionResident != null && actor.residentYield == null &&
                actor.collisionResident?.let(runtime.actors::get)?.residentYield == null &&
                (actor.route?.replanAttempts ?: 0) >= 2) pause(actor, status, now)
            return
        }
        if (retryWorkPosition(actor, detail, preflightStanding)) return
        if (actor.plan.courier?.let { !it.carrying && it.cargo.sourceWorkerId != null } == true) {
            pause(actor, status, now)
            return
        }
        val failed = actor.route
        if (failed?.motion?.failure == "chunk-unloaded" && retryLabCourierRoute(actor, failed, now)) return
        if (!recoverNavigation(actor, target, now)) pause(actor, status, now)
    }

    private fun retryLabCourierRoute(actor: CitizenActor, failed: CitizenRoute, now: Long): Boolean {
        val city = runtime.cityProvider().firstOrNull { it.id == actor.cityId && it.pendingDeletion == null } ?: return false
        val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: return false
        if (labCourierStorage(actor, city, worker, failed) == null) return false
        val timedOut = observeArrivalWait(actor, failed)
        val wait = actor.arrivalWait ?: return false
        val first = wait.firstLabChunkFailureAt ?: failed.startedAt.also { wait.firstLabChunkFailureAt = it }
        if (!timedOut || first == failed.startedAt) return retryPreparation(actor, failed, now)
        if (recoverNavigation(actor, failed.target, now)) return true
        CivsDiagnostics.event("citizen_route_recovery_deferred", "city" to city.id, "worker" to worker.id,
            "shipment" to actor.plan.courier?.cargo?.id, "phase" to actor.cycle.phase, "target" to failed.authored,
            "cause" to "chunk-unloaded", "reason" to "courier-landing-not-admitted")
        actor.arrivalWait = null
        return false
    }

    /** A LAB exception belongs only to a current physical trip and its exact fixed barrel. */
    private fun labCourierStorage(actor: CitizenActor, city: Settlement, worker: CivWorker, route: CitizenRoute): CivBuilding? {
        val trip = actor.plan.courier ?: return null
        val cargo = trip.cargo
        if (city.inventory?.mode != CivStorageMode.PHYSICAL || cargo.sourceWorkerId != null || cargo.stockResource != null ||
            worker.assignment != cargo.warehouseId ||
            worker.completedCycles != actor.plan.token.completedCycles || actor.plan.token.shipmentId != cargo.id ||
            route.idleAnchor != null || route.intermediate || actor.returningToWarehouse || actor.sheltering ||
            actor.attacker != null || actor.combatDestination != null || actor.foodSource != null || actor.mealVisit != null ||
            navigationHeld(actor)) return null
        val phase = actor.cycle.phase
        val storage = when (phase) {
            CitizenPhase.TO_WORK -> {
                // The current reservation is still empty-handed. Only ordinary pickup can back its cargo with items.
                val warehouse = city.building(cargo.warehouseId)
                if (trip.carrying || worker.cargo != null || !CivsLogistics.validCargo(city, cargo) ||
                    warehouse?.type != BuildingType.WAREHOUSE || warehouse.phase != BuildingPhase.ACTIVE ||
                    warehouse.control == null) return null
                val source = city.building(cargo.sourceBuildingId)?.takeIf {
                    it.phase == BuildingPhase.ACTIVE && it.entrance == cargo.sourceEntrance
                } ?: return null
                val destination = CivsLogistics.destination(city, cargo)?.takeIf {
                    it.id == trip.destinationBuildingId && it.entrance == trip.destinationEntrance
                }?.control ?: return null
                source.takeIf { CivsLogistics.withinRoute(city, route.authored, destination) }
            }
            CitizenPhase.RETURNING -> CivsLogistics.destination(city, cargo)?.takeIf {
                trip.carrying && cargo.inventoryBacked && worker.cargo == cargo && it.id == trip.destinationBuildingId
            }
            else -> null
        }
        return storage?.takeIf { it.control == route.authored && actor.plan.travelTarget(phase) == route.authored &&
            labCourierRecoveryAllowed(city, worker, trip, emptyList()) }
    }

    /** A failed final approach rejects this stance, leaving the same physical action to its planner. */
    private fun retryWorkPosition(actor: CitizenActor, detail: String, preflightStanding: CivPosition? = null): Boolean {
        val route = actor.route
        val work = actor.plan.worldWork ?: return false
        if (!(actor.plan.type == BuildingType.QUARRY && work.miningStep != null ||
                actor.plan.type in setOf(BuildingType.FORESTER, BuildingType.FARM) || work.upkeep != null) ||
            actor.cycle.phase != CitizenPhase.TO_WORK || actor.plan.courier != null ||
            actor.sheltering || actor.returningToWarehouse || actor.attacker != null || actor.foodSource != null ||
            route?.let { it.intermediate || it.idleAnchor != null || it.authored != work.standing || it.motion.searching } == true) return false
        if (route == null && (detail != "target-not-standable" || preflightStanding != work.standing)) return false
        val cause = route?.motion?.failure ?: route?.completion?.cancelReason ?: detail
        if (cause !in setOf("no-path", "search-budget", "capture-budget", "preparation-budget", "movement-blocked", "path-changed",
                "no-progress", "turn-loop", "timed_out", "stopped", "completed_outside_arrival", "target-not-standable")) return false
        val city = runtime.cityProvider().firstOrNull { it.id == actor.cityId && it.pendingDeletion == null } ?: return false
        val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: return false
        if (!workPositionFailed(city, worker, work)) return false
        CivsDiagnostics.event("citizen_work_position_rejected", "city" to city.id, "worker" to worker.id,
            "building" to work.buildingId, "cycle" to work.cycle, "mining_step" to work.miningStep,
            "focus" to citizenWorkFocus(work), "standing" to work.standing, "reason" to cause,
            "actual" to actor.npc.location.point().coordinates(), "path_progress" to (route?.motion?.forwardProgress ?: 0.0))
        cancelRoute(actor)
        actor.workHandoff = null
        actor.plan = actor.plan.copy(workplace = null, worldWork = null, idleReason = "searching-work")
        actor.cycle.close()
        actor.cycle = actor.plan.cycle()
        runtime.statuses[actor.workerId] = "searching-work"
        return true
    }

    fun mineRouteWork(actor: CitizenActor, route: CitizenRoute): CivWorldWork? = actor.plan.worldWork?.takeIf { work ->
        actor.plan.type == BuildingType.QUARRY && work.miningStep != null && actor.cycle.phase == CitizenPhase.TO_WORK &&
            actor.plan.courier == null && !actor.sheltering && !actor.returningToWarehouse && actor.attacker == null &&
            actor.foodSource == null && actor.social.visit == null && route.idleAnchor == null && route.authored == work.standing
    }

    private fun mineRouteFailure(actor: CitizenActor, route: CitizenRoute, detail: String, target: Location, now: Long) {
        if (route.mineFailureReported || route.motion.searching || route.motion.waitingForCollision || route.nativeCollisionTicks > 0) return
        val work = mineRouteWork(actor, route) ?: return
        route.mineFailureReported = true
        val report = actor.mineRouteEpisode.failed(requireNotNull(CivsMineRouteEpisode.key(work)), now) ?: return
        CivsDiagnostics.record(runtime.plugin.logger, Level.WARNING, actor.cityId, MINE_ROUTE_DIAGNOSTIC.line(
            "city" to actor.cityId, "worker" to actor.workerId, "building" to report.key.buildingId,
            "plan_version" to report.key.planVersion, "cycle" to report.key.cycle, "mining_step" to report.key.miningStep,
            "state" to if (report.failures >= CivsMineRouteEpisode.STALL_FAILURES) "stalled" else "blocked",
            "episode_failures" to report.failures, "episode_elapsed_ms" to report.elapsedMillis,
            "reason" to (route.motion.failure ?: route.completion?.cancelReason ?: detail),
            "phase" to actor.cycle.phase, "actual" to actor.npc.location.point().coordinates(),
            "target" to target.point().coordinates(), "focus" to citizenWorkFocus(work), "standing" to work.standing,
            "path_nodes" to route.motion.nodeCount, "captured_blocks" to route.motion.capturedBlocks, "native_shape_reads" to route.motion.nativeShapeReads,
            "path_progress" to route.motion.forwardProgress, "episode_walked_distance" to report.walkedDistance,
            "search_nanos" to route.motion.searchNanos, "search_state" to route.motion.preparationPhase,
            "route_elapsed_ms" to (now - route.startedAt).coerceAtLeast(0), "intermediate" to route.intermediate,
            "standing_exclusion" to "pending", "suppressed" to report.suppressed))
    }

    fun mineRouteRecovered(actor: CitizenActor, report: CivsMineRouteEpisode.Snapshot, reason: String, now: Long) {
        CivsDiagnostics.record(runtime.plugin.logger, Level.INFO, actor.cityId, MINE_ROUTE_RECOVERED.line(
            "city" to actor.cityId, "worker" to actor.workerId, "building" to report.key.buildingId,
            "plan_version" to report.key.planVersion, "cycle" to report.key.cycle, "mining_step" to report.key.miningStep,
            "reason" to reason, "episode_failures" to report.failures, "episode_elapsed_ms" to report.elapsedMillis,
            "walked_distance" to report.walkedDistance, "actual" to actor.npc.location.point().coordinates(),
            "suppressed" to report.suppressed, "at" to now))
    }

    fun navigationDiagnostic(actor: CitizenActor, detail: String, target: Location, now: Long, terminal: Boolean = false): Boolean {
        val route = actor.route
        val actual = actor.npc.location.point()
        if (terminal && route != null) mineRouteFailure(actor, route, detail, target, now)
        if (actor.navigationEpisode == null) {
            actor.navigationEpisode = detail
            actor.navigationEpisodeProgress = 0.0
            actor.navigationEpisodeTick = runtime.navigationTick
        }
        if (runtime.navigationTick < actor.navigationReportAfter) { actor.navigationSuppressed++; return false }
        actor.navigationReportAfter = runtime.navigationTick + 200
        CivsDiagnostics.record(runtime.plugin.logger, Level.WARNING, actor.cityId, NAVIGATION_DIAGNOSTIC.line(
            "city" to actor.cityId, "worker" to actor.workerId, "cycle" to actor.plan.token.completedCycles,
            "attempt" to actor.cycle.failedAttempts + 1, "phase" to actor.cycle.phase, "reason" to if (route?.motion?.waitingForCollision == true)
                if (actor.collisionResident != null) "resident-blocked" else "player-blocked" else detail,
            "blocking_resident" to actor.collisionResident,
            "from" to route?.from?.coordinates(), "target" to target.point().coordinates(), "actual" to actual.coordinates(),
            "distance" to sqrt(actual.distanceSquared(target.point())), "elapsed_ms" to route?.let { now - it.startedAt },
            "path_nodes" to route?.motion?.nodeCount, "path_failure" to route?.motion?.failure,
            "partial_reason" to route?.motion?.continuationReason, "partial_endpoint" to route?.path?.end?.coordinates(),
            "authored_goal" to route?.authored,
            "blocked_waypoint" to route?.blockedWaypoint?.coordinates(),
            "searching" to route?.motion?.searching, "collision_wait" to route?.motion?.waitingForCollision,
            "search_state" to route?.motion?.preparationPhase,
            "preparation_ms" to route?.timing?.preparationElapsed(now), "travel_ms" to route?.timing?.elapsed(now),
            "captured_blocks" to route?.motion?.capturedBlocks, "native_shape_reads" to route?.motion?.nativeShapeReads, "search_nanos" to route?.motion?.searchNanos,
            "tick" to Bukkit.getCurrentTick(), "navigation_tick" to runtime.navigationTick, "cadence_ms" to runtime.navigationCadenceMillis,
            "body_yaw" to actor.npc.bodyYaw, "head_yaw" to actor.npc.headYaw,
            "previous_body_yaw" to actor.observedYaw,
            "frame_turn_degrees" to actor.observedYaw?.let { abs(citizenAngleChange(it, actor.npc.bodyYaw)) },
            "turn_degrees" to actor.turnAmount,
            "waypoint" to route?.motion?.destination?.point()?.coordinates(), "remaining_nodes" to route?.motion?.remainingNodes,
            "path_progress" to route?.motion?.forwardProgress, "episode_progress" to actor.navigationEpisodeProgress,
            "no_progress_ticks" to route?.let { runtime.navigationTick - it.progressTick }, "replans" to route?.replanAttempts,
            "suppressed" to actor.navigationSuppressed,
            "work_focus" to actor.plan.worldWork?.focus, "work_standing" to actor.plan.worldWork?.standing,
            "mining_step" to actor.plan.worldWork?.miningStep,
            "strategy" to "packet-async-a-star"))
        actor.navigationSuppressed = 0
        return true
    }

    /** Retry changed geometry from observed feet; a bounded person snapshot allows a real detour. */
    fun replanNavigation(actor: CitizenActor, route: CitizenRoute, reason: String, now: Long): Boolean {
        if (route.previousMotion != null) return true
        if (reason !in setOf("movement-blocked", "path-changed", "no-progress", "turn-loop", "player-blocked", "resident-blocked") ||
            route.replanAttempts >= 2 || actor.workerId in runtime.pendingHarm) return false
        val npc = actor.npc
        val from = npc.location
        val bodies = if (reason in setOf("player-blocked", "resident-blocked")) npc.world.getNearbyPlayers(from, 2.5, 2.5, 2.5)
            .asSequence().filter { it.isOnline && it.isValid && !it.isDead && it.gameMode != GameMode.SPECTATOR &&
                !it.isInsideVehicle && !it.isSleeping && it.isCollidable }.take(8).map { it.boundingBox.clone() }.toList()
                .plus(collisions.shelterOccupied(actor).filter { it.overlaps(citizenStandingBounds(from, npc.scale).expand(2.5)) })
            else emptyList()
        val report = navigationDiagnostic(actor, reason, route.target, now)
        route.replanAttempts++
        route.replanReason = reason
        route.timing.prepare(now)
        route.preparationReportAfter = now + 2_000
        route.previousMotion = route.motion
        route.motion = navigation.replan(route.motion, from, bodies)
        route.completion = null
        route.path = null
        route.progressTick = runtime.navigationTick
        if (report) CivsDiagnostics.event("citizen_route_progress", "city" to actor.cityId, "worker" to actor.workerId,
            "action" to "replan", "reason" to reason, "phase" to actor.cycle.phase, "attempt" to route.replanAttempts,
            "target" to route.target.point().coordinates(), "actual" to from.point().coordinates(),
            "tick" to Bukkit.getCurrentTick(), "navigation_tick" to runtime.navigationTick, "obstacles" to bodies.size)
        return true
    }

    fun resolvedTarget(actor: CitizenActor, target: CivPosition): CivPosition = actor.targets[target] ?: target

    /** Resident contact stays local; players retain their bounded detour and strict arrival checks. */
    fun handleBodyContact(actor: CitizenActor, route: CitizenRoute, now: Long): Boolean {
        val since = route.bodyBlockedSince ?: return false
        if (navigationHeld(actor)) return true
        if (collisions.passingBodies(actor)) return false
        val residentContact = actor.collisionResident != null
        if (residentContact) {
            // A real terrain failure still belongs to the ordinary route recovery below.
            if (route.motion.failure != null) return false
            // Arrival cannot be earned by passing through the body at the final waypoint.
            // Existing supported separation steps may clear it, without another terrain search.
            if (route.motion.finished) return false // Recheck the real endpoint each frame after an occupant leaves.
            if (runtime.navigationTick - since < 4) {
                route.progressTick = runtime.navigationTick
                return true
            }
        }
        val failed = route.bodyDetourFailed || route.bodyDetourStarted && route.previousMotion != null && route.motion.failure != null
        if (residentContact || failed || runtime.navigationTick - since >= 30 || route.replanAttempts >= 2 && !route.bodyDetourStarted) {
            route.previousMotion?.let { original ->
                route.motion.cancel()
                route.motion = original
                route.previousMotion = null
            }
            route.bodyPassUntil = runtime.navigationTick + 60
            route.bodyContactTick = runtime.navigationTick
            route.bodyBlockedSince = null
            route.bodyDetourStarted = false
            route.bodyDetourFailed = false
            route.nativeCollisionTicks = 0
            actor.collisionResident = null
            route.completion = null
            if (route.motion.failure != null || route.motion.finished) {
                route.motion = navigation.replan(route.motion, actor.npc.location, emptyList(),
                    refreshSnapshots = route.motion.failure != null)
                route.timing.prepare(now)
            } else route.motionReady(runtime.navigationTick, now)
            route.motion.bodyContactAllowed = true
            CivsDiagnostics.event("citizen_body_contact", "city" to actor.cityId, "worker" to actor.workerId,
                "action" to "pass", "cause" to if (residentContact) "resident-contact" else if (failed) "detour-unavailable" else "contact-timeout",
                "phase" to actor.cycle.phase, "actual" to actor.npc.location.point().coordinates(),
                "target" to route.authored)
            return route.motion.searching
        }
        if (route.bodyDetourStarted) return route.motion.searching
        route.bodyDetourStarted = true
        return replanNavigation(actor, route, if (actor.collisionResident == null) "player-blocked" else "resident-blocked", now)
    }

    // Returning after cosmetic work has no worldWork, but still leaves the assigned mine.
    fun routeMine(actor: CitizenActor, city: Settlement): CivBuilding? = actor.mineVisit?.takeIf {
        !actor.sheltering && actor.attacker == null && actor.foodSource == null && actor.mealVisit == null
    }?.key?.building?.id?.let(city::building) ?: actor.plan.takeIf {
        it.type == BuildingType.QUARRY && it.courier == null && !actor.sheltering &&
            actor.attacker == null && actor.foodSource == null && actor.social.visit == null &&
            actor.collectionSeek == null && actor.collectionHome == null
    }?.token?.assignmentId?.let(city::building)?.takeIf { it.type == BuildingType.QUARRY }

    fun canWorkFrom(actor: CitizenActor, work: CivWorldWork): Boolean {
        if (!arrivalBodyClear(actor)) return false
        // Reach during a step or fall is transient: keep walking until the feet have support.
        if (!citizenPacketSupported(actor.npc.world, actor.npc.location.point())) return false
        if (actor.plan.type == BuildingType.QUARRY && work.miningStep != null) {
            val city = runtime.cityProvider().firstOrNull { it.id == actor.cityId } ?: return false
            val mine = city.building(work.buildingId) ?: return false
            val feet = actor.npc.location
            val standing = CivPosition(feet.blockX, feet.y.roundToInt(), feet.blockZ)
            val geometry = CivsMinePlan.forBuilding(mine, work.miningPlanVersion ?: CivsMinePlan.VERSION) ?: return false
            if (!geometry.builtStanding(CivsMinePlan.localPoint(mine, standing), work.miningAccessStep ?: work.miningStep,
                    work.miningAccessSections.orEmpty()) ||
                !citizenMineWorkRouteAllowed(mine, feet.point(), work.miningAccessStep ?: work.miningStep,
                    city.minePlans?.get(mine.id.toString()), work.miningAccessSections)) return false
        }
        val eye = actor.npc.location.clone().add(0.0, actor.npc.eyeHeight, 0.0)
        return citizenWorkFocus(work)?.let { citizenReachableBlockPoint(actor.npc.world, eye, it,
            citizenWorkReach(actor.plan.type, work), work) != null } == true
    }

    fun storageForTarget(actor: CitizenActor, city: Settlement, target: CivPosition): CivBuilding? {
        val work = actor.plan.worldWork
        return if ((actor.plan.autonomousWork != null || actor.plan.pendingStockDelivery != null) &&
            (target == actor.plan.workplace || target == actor.plan.storage)) null
        else if (target == actor.plan.workplace && actor.plan.courier?.cargo?.sourceWorkerId != null) null
        else if (target == actor.plan.workplace && work != null && citizenWorkFocus(work) == null)
            city.building(work.buildingId)?.takeIf { it.control != null }
        else citizenControlBuilding(city, target)
    }

    fun arrivedAt(actor: CitizenActor, city: Settlement, world: World, target: CivPosition): Boolean {
        if (!arrivalBodyClear(actor)) return false
        actor.foodPickupSource?.takeIf { actor.combatDestination == null && actor.foodSource == it.buildingId &&
            target == it.standing }?.let { return civsCanReachContainer(actor.npc.location, it.container) }
        actor.mineVisit?.takeIf { it.owns(target) }?.let {
            return citizenControlClear(city, target) && citizenStandable(world, target) &&
                citizenPacketBodyClear(world, actor.npc.location.point(), false) &&
                citizenAtSupportedEndpoint(world, actor.npc.location.point(), target)
        }
        if (!actor.sheltering && actor.plan.token.constructionId != null && target == actor.plan.workplace)
            return citizenStandable(world, target) && citizenControlClear(city, target) &&
                citizenPacketBodyClear(world, actor.npc.location.point(), false) &&
                citizenAtSupportedEndpoint(world, actor.npc.location.point(), target)
        if ((actor.plan.autonomousWork != null || actor.plan.pendingStockDelivery != null) &&
            (target == actor.plan.workplace || target == actor.plan.storage))
            return citizenStandable(world, target) && citizenControlClear(city, target) &&
                citizenPacketBodyClear(world, actor.npc.location.point(), false) &&
                citizenArrived(actor.npc.location, target.location(world))
        actor.plan.courier?.takeIf { !it.carrying && it.cargo.sourceWorkerId != null && target == actor.plan.workplace }?.let { plan ->
            val source = runtime.actors[plan.cargo.sourceWorkerId] ?: return false
            return couriers.courierMeetsSource(actor, source)
        }
        val work = actor.plan.worldWork
        if (!actor.sheltering && target == actor.plan.workplace && work != null && citizenWorkFocus(work) != null)
            return canWorkFrom(actor, work)
        val storage = storageForTarget(actor, city, target)
        val control = storage?.control ?: target
        if (storage != null && citizenAtStorage(world, actor.npc.location.point(), control) &&
            citizenCanReachBlock(actor.npc, control)) return true
        if (!citizenArrived(actor.npc.location, resolvedTarget(actor, target).location(world))) return false
        if (storage == null) return true
        return !citizenStorageReady(world, control) && storage.phase != BuildingPhase.ACTIVE && city.construction?.buildingId == storage.id &&
            resolvedTarget(actor, target) == storage.entrance
    }

    /** A fresh arrival must have a clear body even when contact occurred before its first movement sweep. */
    fun arrivalBodyClear(actor: CitizenActor): Boolean {
        // arriveForWork retires its route before accepting work/transfer. Later player occupancy cannot revoke that receipt.
        val supplyArrival = actor.supplyPickup == null && (actor.foodSource != null || actor.outputReturn != null)
        if (actor.route == null && !supplyArrival && actor.cycle.phase !in
            setOf(CitizenPhase.TO_STORAGE, CitizenPhase.TO_WORK, CitizenPhase.RETURNING)) return true
        val feet = actor.npc.location
        return collisions.residentMoveAllowed(actor, feet, feet)
    }

    /** Only accepted physical travel resets this deadline; capture/search retries may reread the same terrain. */
    fun observeArrivalWait(actor: CitizenActor, route: CitizenRoute): Boolean {
        val clock = recoveryClock()
        if (route.motion.waitingForCollision || route.nativeCollisionTicks > 0 || actor.collisionResident != null ||
            route.completion?.cancelReason in setOf("resident-blocked", "player-blocked")) {
            actor.arrivalWait = null
            return false
        }
        val previous = actor.arrivalWait
        val wait = previous?.takeIf { it.token == actor.plan.token && it.target == route.authored &&
            it.cargoId == actor.plan.courier?.cargo?.id && it.carrying == actor.plan.courier?.carrying }
            ?: CitizenArrivalWait(actor.plan.token, route.authored, actor.plan.courier?.cargo?.id, actor.plan.courier?.carrying, clock)
                .also { actor.arrivalWait = it }
        return clock - wait.progressAt >= 60_000L
    }

    /** A failed or expired arrival may recover to a freshly validated loaded endpoint, never into a wall. */
    fun recoverNavigation(actor: CitizenActor, target: Location, now: Long, idleAnchor: CivPosition? = null,
                                  timedOnly: Boolean = false, food: CivResidentFoodSource? = null,
                                  nightHome: CivBuilding? = null): Boolean {
        val failed = actor.route ?: return false
        if (actor.mineVisit != null) return false
        val nightRecovery = nightHome != null
        if (nightHome != null && (food != null || !nightCaptureFailed(actor, failed, nightHome, now))) return false
        if (!nightRecovery && failed.motion.failure == "capture-budget" && canResetNightShelter(actor)) return false
        val foodRoute = actor.foodPickupSource?.takeIf { actor.foodSource == it.buildingId && failed.authored == it.standing }
        // Only the physical food owner can revalidate the exact holder for relocation.
        if (food != foodRoute) return false
        val recoveryAnchor = failed.idleAnchor ?: idleAnchor
        val timedOut = food != null || nightRecovery || observeArrivalWait(actor, failed)
        val chunkFailure = failed.motion.failure == "chunk-unloaded"
        val labFailure = actor.arrivalWait?.firstLabChunkFailureAt
        // A retained failed trip still gets a genuine walking search on each retry.
        if (chunkFailure && (!timedOut || labFailure == null || labFailure == failed.startedAt)) return false
        if (!chunkFailure && labFailure != null) return false
        // Keep body contact authoritative across retries until the resident actually walks clear.
        if (!actor.sheltering && actor.bodyContact?.token == actor.plan.token) return false
        if (timedOnly && !timedOut) return false
        if (actor.plan.worldWork?.upkeep != null && !timedOut) return false
        if (actor.plan.token.constructionId != null && recoveryAnchor == null && !actor.sheltering &&
            !actor.returningToWarehouse && actor.foodSource == null &&
            actor.cycle.phase in setOf(CitizenPhase.TO_WORK, CitizenPhase.WORKING, CitizenPhase.READY) && !timedOut) return false
        // A stalled planner or denied passage may expire; player and resident obstruction remain authoritative.
        if (failed.motion.searching && !timedOut || failed.motion.waitingForCollision || failed.nativeCollisionTicks > 0 ||
            actor.nextRecoveryAt?.let { recoveryClock() < it } == true || actor.attacker != null ||
            actor.workerId in runtime.pendingHarm || life.residentLifePending(actor) || actor.supplyPickup != null || logistics.collectedPending(actor)) return false
        if (failed.motion.failure in PREPARATION_FAILURES && (!timedOut || failed.motion.failure == "cancelled")) return false
        if (failed.motion.failure == null && failed.completion == null && !failed.timedOut(now) && !timedOut) return false
        if (runtime.recoveryBudget == 0 || recoveryAnchor == null && !actor.sheltering && !actor.returningToWarehouse &&
            actor.plan.idleReason !in listOf(null, "placing") && !timedOut ||
            actor.cycle.phase in setOf(CitizenPhase.PICKING_UP, CitizenPhase.DELIVERING)) return false
        val city = runtime.cityProvider().firstOrNull { it.id == actor.cityId && it.pendingDeletion == null } ?: return false
        val entity = actor.npc
        if (!entity.isValid || entity.world.uid != city.worldId || target.world?.uid != city.worldId) return false
        val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: return false
        val foodBuilding = food?.let {
            val building = city.building(it.buildingId) ?: return false
            if (!CivsResidentFood.eligible(city, worker, building) ||
                !foodRecoveryPointAllowed(city, entity.world, it.container)) return false
            val material = entity.world.getBlockAt(it.container.x, it.container.y, it.container.z).type
            if (material.name !in CIVS_STORAGE_TYPES || building.control == it.container && material != org.bukkit.Material.BARREL)
                return false
            building
        }
        val labStorage = if (chunkFailure) labCourierStorage(actor, city, worker, failed) ?: return false else null
        val recoveryTrip = labStorage?.let { requireNotNull(actor.plan.courier) }
        val home = if (actor.sheltering) citizenHomeBuilding(city, worker) ?: return false else null
        if (nightHome != null && (home != nightHome || nightHome.phase != BuildingPhase.ACTIVE ||
                actor.worldId != city.worldId || worker.cargo != null || !failed.chunks.ready(entity.world))) return false
        val waitingMine = waitingMine(actor, city, recoveryAnchor)
        val authored = when {
            timedOut -> failed.authored // A work cycle may currently be visiting food, supplies or leisure.
            actor.sheltering -> actor.shelter ?: return false
            actor.returningToWarehouse -> actor.plan.storage
            recoveryAnchor != null -> recoveryAnchor
            actor.cycle.phase == CitizenPhase.RETURNING -> actor.plan.travelTarget(CitizenPhase.RETURNING)
            actor.cycle.phase in setOf(CitizenPhase.TO_WORK, CitizenPhase.WORKING) ||
                actor.cycle.phase == CitizenPhase.READY && actor.plan.completeAtWorkplace -> actor.plan.workplace ?: return false
            else -> actor.plan.storage
        }
        runtime.recoveryBudget--
        citizenRotateAfterAdmission(runtime.actors, actor.workerId)
        val previous = entity.location
        if (nightRecovery) {
            actor.nightHomeProgress.attemptedRecovery(now)
            val standing = citizenStandingBounds(previous, entity.scale)
            if (!citizenRouteWithinReach(previous.point(), authored.point(), CivsLogistics.maximumTransferDistance(city)) ||
                !citizenPacketSupported(entity.world, previous.point()) || !citizenSleepClear(entity.world, standing) ||
                !nightRecoveryVolumeAllowed(city, previous, standing)) return false
        }
        if (!collisions.residentMoveAllowed(actor, previous, previous)) return false
        if (food != null && !foodRecoveryPointAllowed(city, entity.world,
                CivPosition(previous.blockX, previous.blockY, previous.blockZ))) return false
        if (recoveryTrip != null && (!city.contains(previous.blockX, previous.blockZ) ||
                !citizenRouteWithinReach(previous.point(), authored.point(), CivsLogistics.maximumTransferDistance(city)) ||
                !citizenChunkReady(entity.world, previous.blockX shr 4, previous.blockZ shr 4) ||
                !labCourierRecoveryAllowed(city, worker, recoveryTrip, listOf(authored)))) return false
        val safe: (CivPosition) -> Boolean = { candidate ->
            collisions.recoveryPointSafe(actor, city, candidate, failed.allowedBuildings, routeMine(actor, city).takeIf { recoveryAnchor == null }) &&
                (waitingMine == null || outside(waitingMine, candidate.x, candidate.z)) &&
                (home == null || home.bounds.contains(candidate.x, candidate.y, candidate.z)) &&
                (recoveryAnchor == null || citizenWithinIdleArea(recoveryAnchor, candidate.point()) && citizenIdleTerrain(entity.world, candidate, city.buildings)) &&
                (recoveryTrip == null || citizenAtStorage(entity.world, candidate.point(), authored) &&
                    citizenCanReachBlock(entity.world, candidate.location(entity.world).add(0.0, entity.eyeHeight, 0.0), authored) &&
                    labCourierRecoveryAllowed(city, worker, recoveryTrip, listOf(candidate, candidate.copy(y = candidate.y - 1))))
        }
        val storage = labStorage ?: if (waitingMine == null) storageForTarget(actor, city, authored) else null
        val point = (when {
            actor.sheltering -> {
                val reserved = authored.takeIf(safe)?.takeIf { point ->
                    val feet = point.location(entity.world)
                    shelter.shelterPose(actor, city, worker, feet, collisions.shelterOccupied(actor))?.takeIf { pose ->
                        !nightRecovery || nightRecoveryVolumeAllowed(city, feet, citizenStandingBounds(feet, entity.scale), pose.bounds)
                    }?.also { actor.shelterPose = it } != null
                }
                reserved ?: if (nightRecovery) null else shelter.reserveShelter(actor, city, worker, entity.world, safe)
            }
            food != null && foodBuilding != null -> citizenCollectedHomeTarget(city, foodBuilding, entity.world,
                food.container, food.standing) { candidate ->
                safe(candidate) && foodRecoveryPointAllowed(city, entity.world, candidate) &&
                    foodRecoveryPointAllowed(city, entity.world, candidate.copy(y = candidate.y - 1)) &&
                    civsCanReachContainer(candidate.location(entity.world), food.container)
            }
            storage != null -> citizenStorageTarget(city, storage, entity.world, safe)
            else -> citizenRecoveryTarget(city, authored, safe)
        }) ?: return false
        val destination = point.location(entity.world)
        if (!collisions.residentMoveAllowed(actor, destination, destination)) return false
        destination.yaw = previous.yaw
        cancelRoute(actor, destination)
        if (actor.sheltering) actor.shelter = point
        else if (recoveryAnchor == null && food == null) actor.targets[authored] = point
        entity.teleport(destination)
        actor.observedYaw = destination.yaw
        actor.navigationEpisodeProgress = 0.0
        actor.arrivalWait = null
        actor.mineRouteEpisode.teleported()
        actor.nextRecoveryAt = recoveryClock() + 60_000L
        actor.footsteps.reset()
        if (!timedOut && recoveryAnchor == null && !actor.sheltering && !actor.returningToWarehouse) {
            if (!arrivedAt(actor, city, entity.world, authored)) return false
            workRuntime.arriveForWork(actor, city, worker, now)
        }
        val mode = when {
            nightRecovery -> "night-home-safe-teleport"
            food != null -> "food-safe-teleport"
            else -> "safe-teleport"
        }
        CivsDiagnostics.event("citizen_route_recovered", "city" to city.id, "worker" to actor.workerId,
            "mode" to mode,
            "cause" to (failed.motion.failure ?: failed.completion?.cancelReason ?: "timeout"),
            "distance" to previous.distance(destination), "phase" to actor.cycle.phase, "requested" to authored, "destination" to point,
            "shipment" to recoveryTrip?.cargo?.id, "container" to food?.container)
        return true
    }

    private fun foodRecoveryPointAllowed(city: Settlement, world: World, point: CivPosition): Boolean =
        recoveryPointAllowed(city, world, point, foodRecoveryAllowed)

    private fun nightRecoveryVolumeAllowed(city: Settlement, feet: Location, vararg bodies: BoundingBox): Boolean {
        val points = buildSet {
            add(CivPosition(feet.blockX, citizenSupportY(feet.y), feet.blockZ))
            for (body in bodies)
                for (x in floor(body.minX).toInt()..floor(body.maxX).toInt())
                    for (y in floor(body.minY).toInt()..floor(body.maxY).toInt())
                        for (z in floor(body.minZ).toInt()..floor(body.maxZ).toInt()) add(CivPosition(x, y, z))
        }
        val world = feet.world ?: return false
        return points.all { recoveryPointAllowed(city, world, it, nightShelterRecoveryAllowed) }
    }

    private fun recoveryPointAllowed(city: Settlement, world: World, point: CivPosition,
                                     allowed: (Settlement, CivPosition) -> Boolean): Boolean =
        point.y in world.minHeight until world.maxHeight && city.contains(point.x, point.z) &&
            citizenChunkReady(world, point.x shr 4, point.z shr 4) && world.worldBorder.isInside(point.location(world)) &&
            allowed(city, point)

    fun pause(actor: CitizenActor, reason: String, now: Long) {
        if (actor.arrivalWait?.firstLabChunkFailureAt != null) actor.arrivalWait = null
        actor.plan.courier?.takeIf { !it.carrying && reason in setOf("route-unreachable", "delivery-failed") }?.let {
            courierFailed(actor.cityId, actor.workerId, it.cargo)
        }
        couriers.releaseCourierSource(actor, now)
        life.stopMeal(actor)
        leisure.stopCamp(actor, reason)
        cancelRoute(actor)
        actor.cycle.pause(reason, now, false)
    }

    /** Accepted native receipts own the actor's position; a night retry cannot interrupt or age through that hold. */
    fun navigationHeld(actor: CitizenActor): Boolean = actor.pendingChunkTarget != null || actor.workerId in runtime.pendingHarm ||
        actor.supplyPickup != null || life.residentLifePending(actor) || logistics.collectedPending(actor) ||
        actor.cycle.phase in setOf(CitizenPhase.PICKING_UP, CitizenPhase.DELIVERING)

    fun canResetNightShelter(actor: CitizenActor): Boolean = actor.sheltering && !actor.sleeping &&
        !actor.alarmSheltering && actor.attacker == null && actor.npc.world.time in 12_000L..23_000L &&
        actor.foodSource == null && actor.foodPickupSource == null && actor.mealVisit == null &&
        actor.tavernService == null && !actor.npc.isSeated && !navigationHeld(actor)

    private fun nightCaptureFailed(actor: CitizenActor, route: CitizenRoute, home: CivBuilding, now: Long): Boolean {
        val feet = actor.npc.location.point()
        return home.phase == BuildingPhase.ACTIVE && canResetNightShelter(actor) &&
            actor.nightHomeProgress.recoveryDue(home, feet, now) && actor.shelterPose != null &&
            route.authored == actor.shelter && route.target.point() == route.authored.point() &&
            route.motion.failure == "capture-budget" && !route.motion.searching && !route.motion.continuation &&
            route.motion.forwardProgress == 0.0 && route.from.distanceSquared(feet) < .25 * .25 &&
            !route.intermediate && route.idleAnchor == null && route.replanAttempts == 0 && route.previousMotion == null &&
            route.bodyBlockedSince == null && actor.residentYield == null && actor.collisionResident == null &&
            actor.bodyContact?.token != actor.plan.token && actor.plan.courier?.carrying != true
    }

    fun resetNightShelter(actor: CitizenActor, now: Long): Boolean {
        if (!canResetNightShelter(actor) || runtime.recoveryBudget <= 0) return false
        val target = actor.shelter ?: return false
        val failed = actor.route
        runtime.recoveryBudget--
        citizenRotateAfterAdmission(runtime.actors, actor.workerId)
        cancelRoute(actor)
        actor.targets.remove(target)
        actor.shelter = null
        actor.shelterPose = null
        actor.arrivalWait = null
        actor.cycle.deferWork(now)
        actor.nightHomeProgress.rotated(now)
        CivsDiagnostics.event("citizen_night_route_reset", "city" to actor.cityId, "worker" to actor.workerId,
            "target" to target, "actual" to actor.npc.location.point().coordinates(),
            "stationary_ms" to CitizenNightHomeProgress.STALL_MILLIS,
            "cause" to (failed?.motion?.failure ?: failed?.completion?.cancelReason ?: "no-progress"),
            "phase" to actor.cycle.phase)
        return true
    }

    fun cancelRoute(actor: CitizenActor, wakeDestination: Location? = null) {
        actor.pendingChunkTarget = null
        actor.courierInboundPrefix = null
        actor.npc.isUsingMainHand = false
        life.standFromMeal(actor)
        actor.residentYield = null
        actor.collisionResident = null
        workRuntime.fieldCareHand(actor, null)
        actor.route?.motion?.cancel()
        actor.route?.previousMotion?.cancel()
        actor.route = null
        actor.lookYaw = null
        actor.footsteps.reset()
        shelter.wake(actor, wakeDestination)
    }

    fun look(npc: PacketPlayerNpc, yaw: Float, pitch: Float) {
        val actor = runtime.actors.values.firstOrNull { it.npc === npc } ?: return
        actor.lookYaw = yaw
        actor.lookPitch = pitch
    }

    private fun waitingMine(actor: CitizenActor, city: Settlement, idleAnchor: CivPosition?): CivBuilding? {
        if (idleAnchor == null || actor.sheltering || actor.foodSource != null || actor.mealVisit != null ||
            actor.social.visit != null) return null
        val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: return null
        return worker.assignment?.let(city::building)?.takeIf { workRuntime.waitingForMineAccess(actor, city, worker) }
    }

    private fun outside(building: CivBuilding, x: Int, z: Int): Boolean =
        x !in building.bounds.min.x..building.bounds.max.x || z !in building.bounds.min.z..building.bounds.max.z

    private companion object {
        val NAVIGATION_DIAGNOSTIC = StructuredDebugLine("ARCCIVS_NAVIGATION_FAILED", maxFields = 48)
        val MINE_ROUTE_DIAGNOSTIC = StructuredDebugLine("ARCCIVS_MINE_ROUTE_BLOCKED")
        val MINE_ROUTE_RECOVERED = StructuredDebugLine("ARCCIVS_MINE_ROUTE_RECOVERED")
        val FOOD_TERRAIN_FAILURES = setOf("movement-blocked", "no-path")
        const val FOOD_CAPTURE_RETRY_PREPARATION_LIMIT = 120_000L
        val PREPARATION_FAILURES = setOf("search-budget", "capture-budget", "preparation-budget", "planner-busy", "planner-error",
            "chunk-unloaded", "cancelled", "broken-path", "passage-blocked")
    }}
