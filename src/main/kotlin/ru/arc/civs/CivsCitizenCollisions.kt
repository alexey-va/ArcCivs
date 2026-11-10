package ru.arc.civs

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.paper.npc.PacketNpcPose
import ru.arc.paper.npc.PacketPlayerNpc
import java.util.UUID
import java.util.logging.Level
import kotlin.math.abs
import kotlin.math.sqrt

/** Bounded resident separation, yielding and terrain-overlap recovery. */
internal class CivsCitizenCollisions(
    private val runtime: CitizenRuntime,
    private val routesOwner: () -> CivsCitizenRoutes,
    private val leisureOwner: () -> CivsCitizenLeisure,
    private val workOwner: () -> CivsCitizenWork,
    private val logisticsOwner: () -> CivsCitizenLogistics,
    private val audienceOwner: () -> CivsCitizenAudience,
    private val lifeOwner: () -> CivsCitizenLife,
    private val recoveryClock: () -> Long,
    private val residentStepAllowed: (PacketPlayerNpc, Location, Location) -> Boolean,
) {
    private val routes get() = routesOwner()
    private val leisure get() = leisureOwner()
    private val workRuntime get() = workOwner()
    private val logistics get() = logisticsOwner()
    private val audience get() = audienceOwner()
    private val life get() = lifeOwner()

    /** A small physical push changes only the observed position, never a work or cargo receipt. */
    internal fun nudgeResident(npc: PacketPlayerNpc, impulse: Vector): Boolean {
        checkCitizenMainThread()
        if (runtime.closed.get() || !npc.isValid || npc.isSeated || npc.pose == PacketNpcPose.SLEEPING) return false
        val actor = runtime.actors.values.firstOrNull { it.npc === npc } ?: return false
        val city = runtime.cityProvider().firstOrNull { it.id == actor.cityId && it.pendingDeletion == null } ?: return false
        val from = npc.location
        val to = from.clone().add(impulse)
        if (npc.world.uid != city.worldId || !citizenResidentStepClear(npc, from, to, shelterOccupied(actor)) ||
            !citizenResidentNudgeClear(npc, from, to)) return false
        if (!residentRouteStepAllowed(actor, city, from, to)) return false
        npc.teleport(to)
        actor.route?.trip?.let { actor.courierInboundPrefix?.observe(from.point(), npc.location.point(), it, recordGround = true) }
        actor.footsteps.reset()
        return true
    }

    private fun residentRouteStepAllowed(actor: CitizenActor, city: Settlement, from: Location, to: Location): Boolean {
        val target = actor.plan.workplace ?: actor.plan.storage
        val allowed = actor.route?.allowedBuildings ?: citizenRouteBuildings(city, actor.plan, target,
            CivPosition(from.blockX, from.blockY, from.blockZ))
        val halfWidth = .3 * actor.npc.scale
        for (x in kotlin.math.floor(to.x - halfWidth).toInt()..kotlin.math.floor(to.x + halfWidth).toInt())
            for (z in kotlin.math.floor(to.z - halfWidth).toInt()..kotlin.math.floor(to.z + halfWidth).toInt())
                if (!citizenRoutePointAllowed(city, allowed, CivPosition(x, kotlin.math.floor(to.y).toInt(), z), routes.routeMine(actor, city))) return false
        return citizenControlStepClear(city, from.point(), to.point()) &&
            actor.mineVisit?.passage?.allows?.invoke(to.point()) != false
    }

    fun residentMoveAllowed(actor: CitizenActor, from: Location, to: Location): Boolean =
        citizenResidentStepClear(actor.npc, from, to, shelterOccupied(actor)) && residentStepAllowed(actor.npc, from, to)

    fun routeMoveAllowed(actor: CitizenActor, from: Location, to: Location): Boolean {
        val occupied = shelterOccupied(actor)
        val clear = citizenResidentStepClear(actor.npc, from, to, occupied)
        val end = actor.route?.motion?.endpoint
        if (end != null && to.point() == end &&
            (!citizenResidentStepClear(actor.npc, to, to, occupied) || !residentStepAllowed(actor.npc, to, to))) {
            actor.collisionResident = blockingResident(actor, to, to)
            markBodyContact(actor)
            return false
        }
        if (passingBodies(actor)) {
            val route = requireNotNull(actor.route)
            val residentClear = citizenResidentStepClear(actor.npc, to, to, occupied)
            val playerClear = yieldPlayerClear(actor, to, to)
            if (end != null && (!clear || !residentClear || !playerClear)) {
                // Transit may cross a different body, but this segment must never park in its own blocker.
                val terminal = citizenStandingBounds(Location(actor.npc.world, end.x, end.y, end.z), actor.npc.scale)
                val terminalBodies = occupied.filter(terminal::overlaps)
                if (!citizenResidentStepClear(actor.npc, from, to, terminalBodies) ||
                    !playerClear && !yieldPlayerClear(actor, from, to, terminal)) {
                    actor.collisionResident = blockingResident(actor, from, to, terminal)
                    markBodyContact(actor)
                    return false
                }
            }
            // Escaping an overlap is a permitted sweep, but is still contact until the endpoint body is clear.
            if (!residentClear || !playerClear)
                markBodyContact(actor, blocked = false)
            else if (runtime.navigationTick - route.bodyContactTick >= 4) route.bodyPassUntil = 0
            return residentStepAllowed(actor.npc, from, to)
        }
        if (clear)
            return residentStepAllowed(actor.npc, from, to).also { allowed ->
                if (!allowed) {
                    actor.route?.nativeCollisionTicks = 40
                    if (!yieldPlayerClear(actor, from, to)) markBodyContact(actor)
                }
            }
        // Preserve the aggregate fast path; identify an owner only for an actually denied sweep.
        actor.collisionResident = blockingResident(actor, from, to)
        markBodyContact(actor)
        return false
    }

    /** A finished path still distinguishes another resident from a player occupying its endpoint. */
    fun markArrivalContact(actor: CitizenActor) {
        val feet = actor.npc.location
        actor.collisionResident = blockingResident(actor, feet, feet)
        markBodyContact(actor)
    }

    private fun blockingResident(actor: CitizenActor, from: Location, to: Location, terminal: BoundingBox? = null): UUID? =
        runtime.actors.values.firstOrNull { other -> other !== actor &&
            other.worldId == actor.worldId && other.npc.isValid &&
            !citizenResidentStepClear(actor.npc, from, to, residentOccupied(other).let {
                if (terminal == null) it else it.filter(terminal::overlaps)
            }) }?.workerId

    /** The resident that denied the last move still owns this exact planned endpoint. */
    fun endpointResident(actor: CitizenActor, route: CitizenRoute): CitizenActor? {
        if (!route.motion.waitingForCollision) return null
        val end = route.motion.endpoint ?: return null
        val other = actor.collisionResident?.let(runtime.actors::get) ?: return null
        if (!other.npc.isValid || other.worldId != actor.worldId) return null
        val body = citizenStandingBounds(Location(actor.npc.world, end.x, end.y, end.z), actor.npc.scale)
        return other.takeIf { residentOccupied(it).any(body::overlaps) }
    }

    private fun markBodyContact(actor: CitizenActor, blocked: Boolean = true) {
        actor.bodyContact = CitizenBodyContact(actor.plan.token, actor.npc.location.point())
        actor.route?.let { route ->
            if (blocked && route.bodyBlockedSince == null) route.bodyBlockedSince = runtime.navigationTick
            route.bodyContactTick = runtime.navigationTick
        }
    }

    /** Retrying, turning in place or being pushed never clears contact. Require verified travel away. */
    fun observeWalkAfterContact(actor: CitizenActor, from: CitizenPoint, to: CitizenPoint) {
        val contact = actor.bodyContact ?: return
        contact.walked += kotlin.math.hypot(to.x - from.x, to.z - from.z)
        if (contact.walked >= 2.0 && kotlin.math.hypot(to.x - contact.at.x, to.z - contact.at.z) >= 2.0)
            actor.bodyContact = null
    }

    fun passingBodies(actor: CitizenActor): Boolean = actor.route?.let {
        it.bodyPassUntil > runtime.navigationTick && !routes.navigationHeld(actor) &&
            !actor.sleeping && actor.npc.isValid && !actor.npc.isSeated
    } == true

    private fun travelling(actor: CitizenActor): Boolean = actor.route?.let {
        it.completion == null && it.motion.navigating
    } == true && (actor.sheltering || actor.cycle.phase in setOf(CitizenPhase.IDLE, CitizenPhase.PAUSED,
        CitizenPhase.TO_STORAGE, CitizenPhase.TO_WORK, CitizenPhase.RETURNING))

    private fun canYield(actor: CitizenActor): Boolean = travelling(actor) && actor.npc.isValid && !actor.npc.isSeated && !actor.sleeping &&
        actor.route?.let { it.completion != null || it.motion.finished || it.motion.failure != null } != true &&
        actor.attacker == null && actor.supplyPickup == null && !logistics.collectedPending(actor) &&
        actor.workerId !in runtime.pendingHarm &&
        (if (actor.sheltering) actor.route != null else actor.cycle.phase in setOf(CitizenPhase.IDLE, CitizenPhase.PAUSED,
            CitizenPhase.TO_STORAGE, CitizenPhase.TO_WORK, CitizenPhase.RETURNING))

    private fun yieldPriority(actor: CitizenActor): Int = when {
        actor.plan.idleReason != null || actor.cycle.phase in setOf(CitizenPhase.IDLE, CitizenPhase.PAUSED) -> 0
        actor.cycle.phase == CitizenPhase.RETURNING || actor.returningToWarehouse || actor.plan.courier?.carrying == true -> 2
        else -> 1
    }

    /** Admission is read-only: a player must never be pushed by a resident's yielding probe. */
    private fun yieldPlayerClear(actor: CitizenActor, from: Location, to: Location, terminal: BoundingBox? = null): Boolean {
        val sweep = citizenStandingBounds(from, actor.npc.scale).union(citizenStandingBounds(to, actor.npc.scale))
        val players = actor.npc.world.getNearbyPlayers(Location(actor.npc.world, sweep.centerX, sweep.centerY, sweep.centerZ),
            sweep.widthX / 2, sweep.height / 2, sweep.widthZ / 2)
            .filter { it.isOnline && it.isValid && !it.isDead && it.gameMode != GameMode.SPECTATOR &&
                !it.isInsideVehicle && !it.isSleeping && it.isCollidable }
            .mapNotNull { player -> player.boundingBox.takeIf { terminal == null || terminal.overlaps(it) } }
        return citizenResidentStepClear(actor.npc, from, to, players)
    }

    /** One resident yields; two approaching bodies need not overlap before either can step aside. */
    fun beginResidentYield(actor: CitizenActor): Boolean {
        val other = actor.collisionResident?.let(runtime.actors::get) ?: return false
        if (actor.residentYield != null || other.residentYield != null) {
            actor.route?.progressTick = runtime.navigationTick
            return true
        }
        if (actor.yieldProgress < 2.0) return false
        val candidates = listOf(actor, other).filter { canYield(it) && it.yieldProgress >= 2.0 }.sortedWith(
            compareBy<CitizenActor> { yieldPriority(it) }.thenByDescending { it.workerId })
        val yielding = candidates.firstOrNull() ?: return false
        val passing = if (yielding === actor) other else actor
        if (runtime.navigationTick < yielding.yieldRetryAfter) return false
        yielding.yieldRetryAfter = runtime.navigationTick + 100
        val city = runtime.cityProvider().firstOrNull { it.id == yielding.cityId && it.pendingDeletion == null } ?: return false
        val from = yielding.npc.location
        val passFrom = passing.npc.location
        val passTo = passing.route?.motion?.destination ?: from
        val forward = passTo.toVector().subtract(passFrom.toVector()).setY(0.0)
        if (forward.lengthSquared() < .000001) return false
        forward.normalize()
        val side = if (abs(forward.x) >= abs(forward.z)) Vector(0.0, 0.0, 1.0) else Vector(1.0, 0.0, 0.0)
        // Three bounded corridors, then the existing two A* attempts and normal pause/retry.
        // 1.25 clears both 0.6-wide bodies at a diagonal approach, not just their current feet.
        for (direction in listOf(side, side.clone().multiply(-1.0), forward)) {
            val to = from.clone().add(direction.clone().multiply(1.25))
            if (!citizenResidentStepClear(yielding.npc, from, to, shelterOccupied(yielding)) ||
                !yieldPlayerClear(yielding, from, to) ||
                !citizenResidentStepClear(passing.npc, passFrom, passTo, listOf(citizenStandingBounds(to, yielding.npc.scale)))) continue
            // A clear endpoint cannot authorize stepping over an unsupported cell in between.
            if (!(1..25).all { index ->
                    val start = from.clone().add(direction.clone().multiply((index - 1) * .05))
                    val end = from.clone().add(direction.clone().multiply(index * .05))
                    residentRouteStepAllowed(yielding, city, start, end) && citizenResidentNudgeClear(yielding.npc, start, end)
                }) continue
            actor.yieldProgress = 0.0
            yielding.yieldProgress = 0.0
            // Both routes deliberately wait while the corridor opens; this earns no walk credit.
            yielding.route?.progressTick = runtime.navigationTick
            passing.route?.progressTick = runtime.navigationTick
            yielding.residentYield = CitizenResidentYield(passing.workerId, from.point(), to.point(), runtime.navigationTick + 80)
            CivsDiagnostics.event("citizen_resident_yield", "city" to yielding.cityId, "worker" to yielding.workerId,
                "passing" to passing.workerId, "action" to "start", "actual" to from.point().coordinates(),
                "target" to to.point().coordinates(), "phase" to yielding.cycle.phase)
            return true
        }
        return false
    }

    /** Shares the four-actor separation budget; every accepted packet step is at most 0.05 block. */
    fun advanceResidentYield(actor: CitizenActor): Boolean {
        val yielding = actor.residentYield ?: return false
        actor.route?.progressTick = runtime.navigationTick
        fun finish(reason: String) {
            actor.residentYield = null
            actor.yieldRetryAfter = runtime.navigationTick + 80
            CivsDiagnostics.event("citizen_resident_yield", "city" to actor.cityId, "worker" to actor.workerId,
                "passing" to yielding.passing, "action" to reason, "steps" to yielding.steps,
                "actual" to actor.npc.location.point().coordinates(),
                "displacement" to sqrt(yielding.from.distanceSquared(actor.npc.location.point())))
        }
        if (!canYield(actor) || runtime.navigationTick >= yielding.until || runtime.actors[yielding.passing]?.npc?.isValid != true) {
            finish("cancelled")
            return true
        }
        val from = actor.npc.location
        val delta = Vector(yielding.target.x - from.x, yielding.target.y - from.y, yielding.target.z - from.z)
        if (delta.lengthSquared() < .000001) {
            if (yielding.holdUntil == 0L) yielding.holdUntil = runtime.navigationTick + 20
            if (runtime.navigationTick >= yielding.holdUntil) finish("complete")
            return true
        }
        val step = delta.normalize().multiply(minOf(.05, sqrt(from.point().distanceSquared(yielding.target))))
        if (!yieldPlayerClear(actor, from, from.clone().add(step)) || !nudgeResident(actor.npc, step)) finish("blocked")
        else yielding.steps++
        // A side step is physical displacement only; it never advances the work-route watermark.
        return true
    }

    /** Injury persistence still freezes work, but cannot repeatedly cancel a physical exit from a new solid. */
    fun escapeTerrain(actor: CitizenActor, now: Long): Boolean {
        val city = runtime.cityProvider().firstOrNull { it.id == actor.cityId && it.pendingDeletion == null } ?: return false
        if (actor.npc.world.uid != city.worldId) return false
        val from = actor.npc.location
        val escape = citizenResidentEscapeStep(actor.npc) { to ->
            residentRouteStepAllowed(actor, city, from, to) && residentMoveAllowed(actor, from, to)
        }
        if (escape == null) {
            actor.terrainObstructedAt?.let { started ->
                val half = .3 * actor.npc.scale - .001
                if (!citizenSleepClear(actor.npc.world, BoundingBox(from.x - half, from.y + .001, from.z - half,
                        from.x + half, from.y + actor.npc.height - .001, from.z + half))) return true
                CivsDiagnostics.record(runtime.plugin.logger, Level.INFO, actor.cityId, CivsDiagnostics.line("citizen_terrain_escape", "city" to actor.cityId,
                    "worker" to actor.workerId, "outcome" to "cleared", "actual" to from.point().coordinates(),
                    "elapsed_ms" to (now - started).coerceAtLeast(0)))
                actor.terrainObstructedAt = null
            }
            return false
        }
        if (actor.terrainObstructedAt == null) {
            actor.terrainObstructedAt = now
            CivsDiagnostics.record(runtime.plugin.logger, Level.WARNING, actor.cityId, CivsDiagnostics.line("citizen_terrain_escape", "city" to actor.cityId,
                "worker" to actor.workerId, "outcome" to "blocked", "reason" to "native-body-overlap",
                "actual" to from.point().coordinates(), "block" to escape.material, "obstruction" to escape.obstruction,
                "exit_available" to (escape.step != null), "injury_pending" to (actor.workerId in runtime.pendingHarm)))
            leisure.stopCamp(actor, "native-body-overlap")
            routes.cancelRoute(actor)
        }
        actor.cycle.deferWork(now)
        runtime.statuses[actor.workerId] = "route-unreachable"
        if (escape.step != null) actor.npc.teleport(escape.step)
        else recoverTerrainOverlap(actor, city, now)
        actor.footsteps.reset()
        return true
    }

    /** An enclosed body may relocate, but its injury, cargo and observed-work receipts stay untouched. */
    private fun recoverTerrainOverlap(actor: CitizenActor, city: Settlement, now: Long): Boolean {
        if (actor.mineVisit != null) return false
        val clock = recoveryClock()
        if (runtime.recoveryBudget == 0 || actor.nextRecoveryAt?.let { clock < it } == true ||
            life.residentLifePending(actor) || actor.supplyPickup != null || logistics.collectedPending(actor) ||
            actor.cycle.phase in setOf(CitizenPhase.PICKING_UP, CitizenPhase.DELIVERING)) return false
        runtime.recoveryBudget--
        actor.nextRecoveryAt = clock + 5_000L
        val from = actor.npc.location
        val origin = CivPosition(from.blockX, from.blockY, from.blockZ)
        val target = actor.plan.workplace ?: actor.plan.storage
        val allowed = citizenRouteBuildings(city, actor.plan, target, origin)
        val safe: (CivPosition) -> Boolean = { recoveryPointSafe(actor, city, it, allowed) }
        // One proven work stance, 27 local positions, then four existing authored anchors.
        val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: return false
        val point = actor.plan.workplace?.takeIf(safe) ?: citizenRecoveryTarget(city, origin, safe)
            ?: listOfNotNull(worker.homeId?.let(city::building)?.entrance,
                worker.assignment?.let(city::building)?.entrance,
                city.buildings.firstOrNull { it.type == BuildingType.TOWN_HALL }?.entrance, city.center)
                .distinct().firstOrNull(safe) ?: return false
        val destination = point.location(actor.npc.world).also { it.yaw = from.yaw; it.pitch = from.pitch }
        if (!safe(point)) return false
        routes.cancelRoute(actor, destination)
        if (!actor.npc.isValid) return false
        actor.npc.teleport(destination)
        workRuntime.rememberWorkDetour(actor)
        actor.terrainObstructedAt = null
        actor.arrivalWait = null
        actor.nextRecoveryAt = clock + 60_000L
        actor.mineRouteEpisode.teleported()
        CivsDiagnostics.event("citizen_terrain_escape", "city" to city.id, "worker" to actor.workerId,
            "outcome" to "teleported", "reason" to "no-safe-step", "from" to from.point().coordinates(),
            "actual" to destination.point().coordinates(), "destination" to point,
            "injury_pending" to (actor.workerId in runtime.pendingHarm), "at" to now)
        return true
    }

    fun recoveryPointSafe(actor: CitizenActor, city: Settlement, point: CivPosition, allowed: Set<UUID>,
                                  mine: CivBuilding? = null, feet: Location? = null): Boolean {
        val npc = actor.npc
        if (!npc.isValid || city.pendingDeletion != null || npc.world.uid != city.worldId || actor.worldId != city.worldId) return false
        val destination = feet ?: point.location(npc.world)
        val body = citizenStandingBounds(destination, npc.scale)
        for (x in kotlin.math.floor(body.minX).toInt()..kotlin.math.floor(body.maxX).toInt())
            for (z in kotlin.math.floor(body.minZ).toInt()..kotlin.math.floor(body.maxZ).toInt())
                if (!citizenRoutePointAllowed(city, allowed, CivPosition(x, point.y, z), mine)) return false
        return citizenStandable(npc.world, point) && citizenControlClear(city, point) &&
            citizenPacketBodyClear(npc.world, destination.point(), false) && citizenSleepClear(npc.world, body) &&
            audience.activeAt(city.id, city.worldId, destination.point(), audience.viewers()) && residentMoveAllowed(actor, destination, destination)
    }

    /** Existing overlaps separate by ordinary bounded steps, without teleport recovery or work credit. */
    fun separateResident(actor: CitizenActor): Boolean? {
        val awaitingArrival = actor.route?.let {
            it.completion == null && it.motion.finished && it.motion.failure == null && it.bodyBlockedSince != null
        } == true
        val foodTravel = actor.foodSource != null && actor.route?.completion == null
        if ((!travelling(actor) && !awaitingArrival && !foodTravel) || routes.navigationHeld(actor)) return null
        // Pair checks use the configured actor cap (at most CivsSettings.MAXIMUM_NPCS); admission is shared by four actors per frame.
        val npc = actor.npc
        val at = npc.location
        val body = citizenStandingBounds(at, npc.scale)
        var other: CitizenActor? = null
        val away = Vector()
        for (candidate in runtime.actors.values) {
            if (candidate === actor || candidate.worldId != actor.worldId || !candidate.npc.isValid || candidate.sleeping) continue
            val feet = candidate.npc.location
            if (!citizenStandingBounds(feet, candidate.npc.scale).overlaps(body)) continue
            val dx = at.x - feet.x
            val dz = at.z - feet.z
            val sameCentre = dx * dx + dz * dz < .000001
            if (other == null || sameCentre) {
                other = candidate
                away.setX(dx).setZ(dz)
            }
            if (sameCentre) break
        }
        other ?: return null
        val coincident = away.lengthSquared() < .000001
        val passing = passingBodies(actor)
        if (!coincident && !awaitingArrival && (passing || passingBodies(other))) return null
        if (coincident) away.x = if (actor.workerId < other.workerId) -1.0 else 1.0
        away.normalize().multiply(.05)
        fun nudge(step: Vector): Boolean =
            (!passing || yieldPlayerClear(actor, at, at.clone().add(step))) && nudgeResident(npc, step)
        val moved = if (nudge(away)) true else {
            val tangent = Vector(-away.z, 0.0, away.x)
            nudge(tangent) || nudge(tangent.multiply(-1.0)) || coincident && nudge(away.multiply(-1.0))
        }
        citizenRotateAfterAdmission(runtime.actors, actor.workerId)
        return moved
    }

    /** An exhausted delivery leg cannot walk its remaining overlap clear. Recheck the same barrel from another side. */
    fun retryOccupiedDeliveryArrival(actor: CitizenActor, route: CitizenRoute, now: Long): Boolean {
        val since = route.bodyBlockedSince ?: return false
        val courier = actor.plan.courier ?: return false
        if (!courier.carrying || !courier.cargo.inventoryBacked || actor.cycle.phase != CitizenPhase.RETURNING ||
            route.authored != actor.plan.storage || route.completion != null || route.intermediate || route.idleAnchor != null ||
            route.motion.failure != null || route.motion.continuation ||
            runtime.navigationTick - maxOf(since, route.progressTick) < 80L || now < actor.courierTargetAfter ||
            actor.sheltering || actor.attacker != null || actor.combatDestination != null || actor.mineVisit != null ||
            actor.foodSource != null || actor.mealVisit != null || actor.tavernService != null || actor.workDetour != null ||
            actor.collectionSeek != null || actor.collectionHome != null || actor.returningToWarehouse) return false
        val from = actor.npc.location
        val blocker = (if (route.motion.finished) blockingResident(actor, from, from)?.let(runtime.actors::get)
            else endpointResident(actor, route))?.takeIf { it.cityId == actor.cityId } ?: return false
        // Separate from the contact tick, which is refreshed every frame at an occupied endpoint.
        actor.courierTargetAfter = now + 5_000L
        if (!yieldPlayerClear(actor, from, from)) return false
        return routes.retryDeliveryStanding(actor, route, courier, blocker.workerId, now)
    }

    /** A saved-stock return still earns its own depot arrival after walking to a clear side of the entrance. */
    fun stepPendingDepotArrival(actor: CitizenActor, route: CitizenRoute): Boolean {
        val pending = actor.plan.pendingStockDelivery ?: return false
        if (actor.cycle.phase != CitizenPhase.RETURNING || route.authored != actor.plan.storage ||
            route.completion != null || route.intermediate || route.motion.continuation || route.motion.failure != null) return false
        val npc = actor.npc
        val world = npc.world
        val from = npc.location
        val entrance = route.authored.location(world)
        if (from.distanceSquared(entrance) > 4.0 || abs(from.y - entrance.y) > .5) return false
        val city = runtime.cityProvider().firstOrNull { it.id == actor.cityId && it.worldId == world.uid &&
            it.pendingDeletion == null && it.autonomy?.paused == false && it.inventory == null } ?: return false
        if (city.workers.firstOrNull { it.id == actor.workerId }?.pendingStock != pending ||
            city.buildings.none { CivsRules.isDepot(it) && it.entrance == route.authored } ||
            routes.arrivedAt(actor, city, world, route.authored)) return false

        val occupied = shelterOccupied(actor)
        for ((dx, dz) in listOf(-.625 to 0.0, .625 to 0.0, 0.0 to -.625, 0.0 to .625)) {
            val stance = Location(world, entrance.x + dx, from.y, entrance.z + dz)
            if (!citizenArrived(stance, entrance) || !residentRouteStepAllowed(actor, city, from, stance) ||
                !citizenResidentStepClear(npc, stance, stance, occupied) || !residentStepAllowed(npc, stance, stance) ||
                !citizenResidentStepClear(npc, from, stance, occupied) || !yieldPlayerClear(actor, from, stance) ||
                !citizenFlatSupportClear(from.point(), stance.point()) { citizenPacketSupported(world, it) } ||
                !citizenPacketStepClear(world, from.point(), stance.point(), false)) continue
            val step = stance.toVector().subtract(from.toVector())
            val length = step.length()
            if (length > .000001 && nudgeResident(npc, step.multiply(minOf(1.0, .05 / length)))) return true
        }
        return false
    }

    /** A finished food route can have no normal body-separation step even though its terrain is passable. */
    fun recoverFoodOverlap(actor: CitizenActor, route: CitizenRoute, now: Long): Boolean {
        val since = route.bodyBlockedSince ?: return false
        val food = actor.foodPickupSource ?: return false
        if (actor.foodSource != food.buildingId || route.authored != food.standing || route.completion != null ||
            !route.motion.finished || route.motion.failure != null || route.timedOut(now) ||
            runtime.navigationTick - maxOf(since, route.progressTick) < 80L ||
            actor.sheltering || actor.attacker != null || actor.mineVisit != null || actor.plan.courier?.carrying == true ||
            actor.workerId in runtime.pendingHarm || life.residentLifePending(actor) || actor.supplyPickup != null ||
            logistics.collectedPending(actor)) return false
        val clock = recoveryClock()
        if (runtime.recoveryBudget == 0 || actor.nextRecoveryAt?.let { clock < it } == true) return false
        val npc = actor.npc
        val from = npc.location
        val blocker = blockingResident(actor, from, from)?.let(runtime.actors::get)?.takeIf { it.cityId == actor.cityId }
            ?: return false
        if (!yieldPlayerClear(actor, from, from)) return false
        val city = runtime.cityProvider().firstOrNull { it.id == actor.cityId && it.pendingDeletion == null } ?: return false
        if (city.inventory?.mode != CivStorageMode.PHYSICAL || city.building(food.buildingId) == null) return false
        runtime.recoveryBudget--
        actor.nextRecoveryAt = clock + 5_000
        citizenRotateAfterAdmission(runtime.actors, actor.workerId)
        val origin = CivPosition(from.blockX, from.blockY, from.blockZ)
        val safe: (CivPosition) -> Boolean = { point ->
            val to = point.location(npc.world)
            recoveryPointSafe(actor, city, point, route.allowedBuildings) && yieldPlayerClear(actor, from, to) &&
                citizenControlStepClear(city, from.point(), to.point()) &&
                citizenPacketStepClear(npc.world, from.point(), to.point(), false)
        }
        val point = citizenRecoveryTarget(city, origin) {
            safe(it) && !civsCanReachContainer(it.location(npc.world), food.container)
        } ?: citizenRecoveryTarget(city, origin, safe) ?: return false
        val to = point.location(npc.world).also { it.yaw = from.yaw; it.pitch = from.pitch }
        routes.cancelRoute(actor, to)
        if (!npc.isValid || !safe(point)) return false
        npc.teleport(to)
        actor.arrivalWait = null
        actor.navigationEpisodeProgress = 0.0
        actor.nextRecoveryAt = clock + 60_000
        actor.footsteps.reset()
        // Food, delivery and work still wait for the next ordinary reach and receipt.
        CivsDiagnostics.event("citizen_route_recovered", "city" to city.id, "worker" to actor.workerId,
            "mode" to "food-overlap-staging", "blocker" to blocker.workerId, "requested" to route.authored,
            "from" to from.point().coordinates(), "actual" to npc.location.point().coordinates(), "destination" to point)
        return true
    }

    fun idleDestinationFree(actor: CitizenActor, to: Location): Boolean {
        val body = citizenStandingBounds(to, actor.npc.scale).expand(.15, 0.0, .15)
        if (shelterOccupied(actor).any(body::overlaps)) return false
        return runtime.actors.values.none { other -> other !== actor && other.worldId == actor.worldId && other.npc.isValid &&
            (other.route?.target?.let { body.overlaps(citizenStandingBounds(it, other.npc.scale)) } == true ||
                other.mealVisit?.target?.location(other.npc.world)?.let {
                    body.overlaps(citizenStandingBounds(it, other.npc.scale))
                } == true) }
    }

    private fun residentOccupied(actor: CitizenActor): List<BoundingBox> = buildList {
        val meal = actor.mealVisit
        add(meal?.seat?.takeIf { actor.npc.isSeated }?.bounds ?: actor.sleepPose?.takeIf { actor.sleeping }?.bounds
            ?: citizenStandingBounds(actor.npc.location, actor.npc.scale))
        meal?.seat?.let { seat ->
            add(seat.bounds)
            add(diningStandingBounds(meal.seatedFrom ?: seat.approach.location(actor.npc.world), actor.npc.scale))
        }
        if (actor.sheltering) {
            actor.shelterPose?.let { add(it.bounds) }
            (actor.sleepOrigin ?: actor.shelter?.location(actor.npc.world))?.let { add(citizenStandingBounds(it, actor.npc.scale)) }
        }
    }

    fun shelterOccupied(actor: CitizenActor): List<BoundingBox> = buildList {
        // Only the configured, capped runtime actors and their reservations are read; no world/entity discovery.
        for (other in runtime.actors.values) {
            if (other === actor || other.worldId != actor.worldId || !other.npc.isValid) continue
            addAll(residentOccupied(other))
        }
    }
}

internal class CitizenResidentYield(val passing: UUID, val from: CitizenPoint, val target: CitizenPoint, val until: Long) {
    var holdUntil = 0L
    var steps = 0
}
