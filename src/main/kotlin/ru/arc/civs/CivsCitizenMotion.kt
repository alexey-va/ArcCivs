package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Tag
import org.bukkit.block.Block
import ru.arc.paper.npc.PacketPlayerNpc
import java.util.UUID
import kotlin.math.abs

/** One owning-thread navigation frame advances all admitted resident routes. */
internal class CivsCitizenMotion(
    private val runtime: CitizenRuntime,
    private val collisionsOwner: () -> CivsCitizenCollisions,
    private val routesOwner: () -> CivsCitizenRoutes,
    private val workOwner: () -> CivsCitizenWork,
    private val logisticsOwner: () -> CivsCitizenLogistics,
    private val audienceOwner: () -> CivsCitizenAudience,
    private val navigation: CivsResidentNavigation,
    private val paths: CivsPaths,
    private val threats: CivsResidentThreats,
    private val admission: CivsCitizenAdmissions,
    private val footstep: (UUID, UUID, PacketPlayerNpc, Location, Location) -> Unit,
    private val surfaceSpeed: (org.bukkit.block.Block) -> Double,
    private val doorBlocked: (UUID, CivPosition) -> Boolean,
    private val navigationClock: () -> Long,
) {
    private val collisions get() = collisionsOwner()
    private val routes get() = routesOwner()
    private val workRuntime get() = workOwner()
    private val logistics get() = logisticsOwner()
    private val audience get() = audienceOwner()

    /** One shared timer advances bounded searches and resident movement; no server entity is ticked. */
    fun activateNavigating() {
        checkCitizenMainThread()
        if (runtime.closed.get() || runtime.actors.isEmpty()) return
        runtime.navigationTick++
        val now = navigationClock()
        runtime.navigationCadenceMillis = if (runtime.navigationAt == 0L) 0 else (now - runtime.navigationAt).coerceAtLeast(0)
        runtime.navigationAt = now
        paths.pulse()
        navigation.pulse()
        threats.tick()
        val viewers = audience.viewers()
        var separationBudget = 4 // Shared by native-overlap exits and at most twelve resident separation probes.
        for (actor in runtime.actors.values.toList().sortedBy { if (it.residentYield == null) 1 else 0 }) {
            val npc = actor.npc
            if (npc.isSeated) { actor.footsteps.reset(); continue }
            var separationAdmitted = false
            if (separationBudget > 0 && npc.isValid && !actor.sleeping && actor.supplyPickup == null &&
                !logistics.collectedPending(actor) &&
                actor.cycle.phase !in setOf(CitizenPhase.PICKING_UP, CitizenPhase.DELIVERING) &&
                audience.activeAt(actor.cityId, actor.worldId, npc.location.point(), viewers)) {
                separationBudget--
                separationAdmitted = true
                citizenRotateAfterAdmission(runtime.actors, actor.workerId)
                if (collisions.escapeTerrain(actor, now)) continue
                if (collisions.advanceResidentYield(actor)) continue
                val separated = if (actor.workerId !in runtime.pendingHarm) collisions.separateResident(actor) else null
                if (separated == true) {
                    actor.route?.progressTick = runtime.navigationTick
                    continue
                }
                // A ready neighbour collision can yield on the next admitted frame;
                // the existing cooldown, real-progress and corridor guards still own admission.
                if (actor.plan.idleReason == null && actor.social.visit == null &&
                    actor.route?.motion?.waitingForCollision == true && actor.collisionResident != null &&
                    runtime.actors[actor.collisionResident]?.social?.visit == null && collisions.beginResidentYield(actor)) continue
            }
            if (actor.residentYield != null) {
                // A skipped separation-budget frame is still owned by the bounded yield.
                actor.route?.progressTick = runtime.navigationTick
                continue
            }
            val route = actor.route
            if (npc.isValid) {
                val previous = actor.observedYaw
                if (!actor.sleeping && !actor.observedSleeping && previous != null &&
                    abs(citizenAngleChange(previous, npc.bodyYaw)) > 17f)
                    routes.navigationDiagnostic(actor, "abrupt-turn", route?.target ?: npc.location, now)
                val at = npc.location.point()
                if (actor.turnOrigin?.distanceSquared(at)?.let { it > 1.0 } != false || runtime.navigationTick - actor.turnSinceTick > 40) {
                    actor.turnOrigin = at
                    actor.turnSinceTick = runtime.navigationTick
                    actor.turnAmount = 0f
                } else if (route != null && previous != null && !actor.sleeping) {
                    actor.turnAmount += abs(citizenAngleChange(previous, npc.bodyYaw))
                    if (actor.turnAmount >= 270f) routes.navigationDiagnostic(actor, "turn-loop", route.target, now)
                }
                actor.observedYaw = npc.bodyYaw
                actor.observedSleeping = actor.sleeping
            }
            if (route == null && !actor.sleeping && actor.workerId !in runtime.pendingHarm && npc.isValid) {
                actor.lookYaw?.let { citizenFace(npc, it, actor.lookPitch, if (actor.attacker != null) 16f else CITIZEN_TURN_PER_TICK) }
            }
            if (route == null || logistics.collectedPending(actor) || actor.workerId in runtime.pendingHarm || !npc.isValid ||
                !audience.activeAt(actor.cityId, actor.worldId, npc.location.point(), viewers) ||
                !route.chunks.ready(npc.world)) {
                actor.footsteps.reset()
                continue
            }
            val city = runtime.cityProvider().firstOrNull { it.id == actor.cityId } ?: continue
            routes.observeArrivalWait(actor, route)
            if (route.trip?.validFor(city) == false) {
                // Replan from the observed position after a building change; this is not a stuck-route recovery.
                routes.cancelForContinuation(actor, city, route)
                continue
            }
            if (route.motion.searching) {
                if (routes.recoverNavigation(actor, route.target, now, timedOnly = true)) continue
                if (now >= route.preparationReportAfter) {
                    route.preparationReportAfter = now + 10_000
                    CivsDiagnostics.event("citizen_route_preparing", "city" to actor.cityId,
                        "worker" to actor.workerId, "cycle" to actor.plan.token.completedCycles,
                        "phase" to actor.cycle.phase, "state" to route.motion.preparationPhase,
                        "elapsed_ms" to route.timing.preparationElapsed(now),
                        "path_nodes" to route.motion.nodeCount, "captured_blocks" to route.motion.capturedBlocks, "native_shape_reads" to route.motion.nativeShapeReads,
                        "actual" to npc.location.point().coordinates(), "target" to route.target.point().coordinates())
                }
                continue
            }
            // A player's small push can permit one forward frame without clearing the obstruction.
            // Count only ready navigation frames; capture/search cannot expire this protection.
            if (route.nativeCollisionTicks > 0) route.nativeCollisionTicks--
            route.previousMotion?.let { previous ->
                if (route.motion.failure != null && route.replanReason in setOf("player-blocked", "resident-blocked")) {
                    // A person can seal a narrow corridor. Keep the original route ready
                    // to resume when they leave; a failed detour never licenses teleporting.
                    route.motion.cancel()
                    route.motion = previous
                } else previous.cancel()
                route.previousMotion = null
                route.motionReady(runtime.navigationTick, now)
                actor.navigationEpisodeProgress = 0.0
                route.path = null
            }
            val motion = route.motion
            // Stop on the first valid arrival tick, including at any usable side of a barrel.
            // Waiting for the coarse work pulse used to keep walking around the barrel after arrival.
            val travelTarget = actor.combatDestination ?: actor.foodSource?.let(city::building)?.control ?:
                actor.mineVisit?.takeIf { it.owns(route.authored) }?.goal ?:
                if (actor.returningToWarehouse) actor.plan.storage else when (actor.cycle.phase) {
                CitizenPhase.TO_STORAGE, CitizenPhase.TO_WORK, CitizenPhase.RETURNING -> actor.plan.travelTarget(actor.cycle.phase)
                else -> null
            }
            if (travelTarget != null && !actor.sheltering && actor.mealVisit == null && actor.tavernService == null && actor.collectionSeek == null && actor.collectionHome == null &&
                (actor.returningToWarehouse || actor.plan.idleReason in listOf(null, "placing")) &&
                routes.arrivedAt(actor, city, npc.world, travelTarget)) {
                motion.cancel()
                route.trip?.let { paths.complete(city, it) }
                route.completion = CitizenNavigationCompletion(null)
                actor.arrivalWait = null
                continue
            }
            if (!route.planningReported) {
                route.planningReported = true
                // Shared A* admission can wait many frames. Only frames with a ready
                // route are movement opportunities; its first turn is not a stall.
                route.motionReady(runtime.navigationTick, now)
                actor.navigationEpisodeProgress = 0.0
                val elapsed = (now - route.startedAt).coerceAtLeast(0)
                if (elapsed >= 1_000 || route.storageReason != null || city.inventory?.mode == CivStorageMode.PHYSICAL &&
                    actor.plan.type == BuildingType.WAREHOUSE && actor.plan.courier?.carrying == true &&
                    actor.cycle.phase == CitizenPhase.RETURNING) CivsDiagnostics.event("citizen_route_ready", "city" to actor.cityId,
                    "worker" to actor.workerId, "route_started_at" to route.startedAt,
                    "reason" to route.storageReason, "source" to route.storageBuilding,
                    "phase" to actor.cycle.phase, "state" to if (motion.continuation) "segment-ready" else "path-ready",
                    "elapsed_ms" to elapsed, "path_nodes" to motion.nodeCount, "failure" to motion.failure,
                    "intermediate" to route.intermediate, "corridor_hint" to motion.hasCourierCorridorHint)
            }
            if (route.path == null) motion.endpoint?.let { route.path = CitizenPathSnapshot(motion.nodeCount, it, false) }
            if ((motion.failure != null || route.completion != null || route.timedOut(now)) &&
                routes.recoverNavigation(actor, route.target, now, timedOnly = true)) continue
            if (motion.failure != null) {
                if (routes.replanNavigation(actor, route, requireNotNull(motion.failure), now)) continue
                routes.navigationDiagnostic(actor, requireNotNull(motion.failure), route.target, now, terminal = true)
                route.completion = CitizenNavigationCompletion(motion.failure)
                continue
            }
            if (motion.finished) {
                if (route.intermediate || motion.continuation) {
                    routes.cancelForContinuation(actor, city, route)
                    routes.startRoute(actor, npc.world, route.authored, now, route.idleAnchor)
                    continue
                }
                route.trip?.let { paths.complete(city, it) }
                route.completion = CitizenNavigationCompletion(null)
                continue
            }
            val before = npc.location
            val working = actor.attacker == null && actor.collectionSeek == null && actor.collectionHome == null &&
                workRuntime.workingTravel(actor)
            val speed = if (actor.attacker != null) 1.1 else if (working)
                .8 * surfaceSpeed(citizenMotionSurface(before, motion)) else .55
            actor.collisionResident = null
            val next = motion.move(before, speed, { from, to -> collisions.routeMoveAllowed(actor, from, to) }) { destination ->
                citizenOpenRouteDoors(city, route.allowedBuildings, before, destination, doorBlocked).also { opened ->
                    if (!opened) route.blockedWaypoint = destination.point()
                }
            }
            if (next != null) {
                npc.teleport(next)
                npc.bodyYaw = next.yaw
                npc.headYaw = next.yaw
                npc.pitch = next.pitch
                val forward = (motion.forwardProgress - route.observedProgress).coerceAtLeast(0.0)
                if (forward > .000001) actor.arrivalWait = null
                route.observedProgress = motion.forwardProgress
                route.progressSinceObservation += forward
                route.progressSinceReplan += forward
                actor.yieldProgress = minOf(2.0, actor.yieldProgress + forward)
                if (route.progressSinceObservation >= .25) {
                    route.progressSinceObservation = 0.0
                    route.progressTick = runtime.navigationTick
                }
                if (route.progressSinceReplan >= 2.0) {
                    route.replanAttempts = 0
                    route.progressSinceReplan = 0.0
                }
                if (routes.mineRouteWork(actor, route) != null) actor.mineRouteEpisode.walked(forward, now)?.let {
                    routes.mineRouteRecovered(actor, it, "walked", now)
                }
                actor.navigationEpisode?.let {
                    actor.navigationEpisodeProgress += forward
                    if (actor.navigationEpisodeProgress >= .5) {
                        CivsDiagnostics.event("citizen_route_progress", "city" to actor.cityId, "worker" to actor.workerId,
                            "action" to "resumed", "reason" to actor.navigationEpisode, "phase" to actor.cycle.phase,
                            "target" to route.target.point().coordinates(), "actual" to next.point().coordinates(),
                            "tick" to Bukkit.getCurrentTick(), "navigation_tick" to runtime.navigationTick,
                            "forward_distance" to actor.navigationEpisodeProgress,
                            "episode_ticks" to runtime.navigationTick - actor.navigationEpisodeTick,
                            "suppressed" to actor.navigationSuppressed)
                        actor.navigationEpisode = null
                        actor.navigationEpisodeProgress = 0.0
                        actor.navigationSuppressed = 0
                    }
                }
                if (working) {
                    fun grounded(at: Location) =
                        !Tag.CLIMBABLE.isTagged(npc.world.getBlockAt(at.blockX, at.blockY, at.blockZ).type) &&
                            citizenPacketSupported(npc.world, at.point())
                    val beforeGrounded = grounded(before)
                    val nextGrounded = grounded(next)
                    actor.footsteps.prime(before, beforeGrounded)
                    actor.footsteps.observe(next, nextGrounded)?.let { (from, to) ->
                        runCatching { footstep(actor.cityId, actor.workerId, npc, from, to) }
                            .onFailure { runtime.logFailure(actor, "footstep", it) }
                    }
                    if (beforeGrounded) route.trip?.observe(before.point())
                    if (nextGrounded) route.trip?.observe(next.point())
                } else actor.footsteps.reset()
                actor.courierInboundPrefix?.let { prefix ->
                    route.trip?.let { prefix.observe(before.point(), next.point(), it) }
                }
            }
            if (motion.failure != null) {
                if (!routes.replanNavigation(actor, route, requireNotNull(motion.failure), now)) {
                    routes.navigationDiagnostic(actor, requireNotNull(motion.failure), route.target, now, terminal = true)
                    route.completion = CitizenNavigationCompletion(motion.failure)
                }
            }
            else if (motion.finished) {
                if (route.intermediate || motion.continuation) {
                    routes.cancelForContinuation(actor, city, route)
                    routes.startRoute(actor, npc.world, route.authored, now, route.idleAnchor)
                    continue
                }
                route.trip?.let { paths.complete(city, it) }
                route.completion = CitizenNavigationCompletion(null)
            }
            else if (runtime.navigationTick - route.progressTick >= 40) {
                val residentBlocked = motion.waitingForCollision && actor.collisionResident != null
                // Let a neighbour clear the corridor before abandoning an optional walk.
                // Cancelling first made idle residents retry from the same mutually blocked positions.
                if (residentBlocked && (!separationAdmitted || collisions.beginResidentYield(actor))) continue
                // Two optional walks must not wait forever for each other in a narrow passage.
                // Give up only this leisure leg; work routes keep their existing bounded detour/wait.
                if (motion.waitingForCollision && route.idleAnchor != null && actor.social.visit == null) {
                    routes.cancelRoute(actor)
                    actor.wander.finish(now, false)
                    continue
                }
                val playerBlocked = motion.waitingForCollision || route.nativeCollisionTicks > 0
                val reason = if (residentBlocked) "resident-blocked" else if (playerBlocked) "player-blocked"
                    else if (motion.turning) "turn-loop" else "no-progress"
                if (!routes.replanNavigation(actor, route, reason, now)) {
                    val terminal = !playerBlocked || residentBlocked
                    routes.navigationDiagnostic(actor, reason, route.target, now, terminal = terminal)
                    if (terminal) route.completion = CitizenNavigationCompletion(reason)
                }
            }
        }
        threats.gravity(runtime.actors.values.filter { it.npc.isSeated || it.sleeping || it.route?.motion?.navigating == true }.mapTo(hashSetOf()) { it.workerId })
    }
}

/** At most four captured floor columns and one native match; a nearby stair never grants a multiplier. */
internal fun citizenMotionSurface(at: Location, motion: CitizenPacketRoute): Block {
    val world = requireNotNull(at.world)
    val y = citizenSupportY(at.y)
    val centre = world.getBlockAt(at.blockX, y, at.blockZ)
    if (!centre.type.isAir) return centre
    val (position, captured, _) = motion.stairProfileSupports(at.point()).maxByOrNull { it.third } ?: return centre
    if (!citizenChunkReady(world, position.x shr 4, position.z shr 4) ||
        citizenCollisionSnapshot(world, position.x, position.y, position.z) != captured) return centre
    return world.getBlockAt(position.x, position.y, position.z)
}
