package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import kotlin.math.abs

/** Campfire, conversation and idle-walk admission and interruption. */
internal class CivsCitizenLeisure(
    private val runtime: CitizenRuntime,
    private val collisionsOwner: () -> CivsCitizenCollisions,
    private val routesOwner: () -> CivsCitizenRoutes,
    private val logisticsOwner: () -> CivsCitizenLogistics,
    private val lifeOwner: () -> CivsCitizenLife,
    private val threats: CivsResidentThreats,
    private val campfires: CivsCampfires,
    private val voices: CivsCitizenVoices,
) {
    private val collisions get() = collisionsOwner()
    private val routes get() = routesOwner()
    private val logistics get() = logisticsOwner()
    private val life get() = lifeOwner()

    fun speak(actor: CitizenActor, event: CitizenVoiceEvent) {
        if (actor.sheltering || actor.sleeping || actor.workerId in runtime.pendingHarm ||
            !actor.npc.isValid || (actor.route?.motion?.navigating == true)) return
        val player = actor.npc
        val resident = runtime.cityProvider().firstOrNull { it.id == actor.cityId }?.workers?.firstOrNull { it.id == actor.workerId }
        runCatching { voices.say(actor.cityId, actor.workerId, player, event, Bukkit.getCurrentTick(), runtime.plugin.server.onlinePlayers,
            resident?.let(CivsResidents::sex)) }
            .onFailure { runtime.logFailure(actor, "voice", it) }
    }

    fun stopCamp(actor: CitizenActor, reason: String = "interrupted") {
        val visit = actor.social.visit ?: return
        actor.social.finish(Bukkit.getCurrentTick())
        CivsDiagnostics.event(if (visit.partnerId == null) "citizen_campfire" else "citizen_meeting",
            "city" to actor.cityId, "worker" to actor.workerId,
            "action" to "end", "reason" to reason, "fire" to visit.fire?.position, "partner" to visit.partnerId, "seat" to visit.seat,
            "actual" to actor.npc.takeIf { it.isValid }?.location?.point()?.coordinates())
        routes.cancelRoute(actor)
        if (visit.partnerId != null) {
            actor.wander.finish(runtime.residentObservedAt, true)
            runtime.actors[visit.partnerId]?.takeIf { it.social.visit?.partnerId == actor.workerId }
                ?.let { stopCamp(it, "partner-$reason") }
        }
    }

    fun gather(actor: CitizenActor, city: Settlement, worker: CivWorker, world: World, now: Long): Boolean {
        val tick = Bukkit.getCurrentTick()
        val routine = actor.social
        var visit = routine.visit
        if (visit == null) {
            val evening = actor.plan.idleReason == null
            if (actor.route != null || runtime.campBudget == 0 || !routine.available(tick, world.fullTime / 24_000, evening)) return false
            runtime.campBudget--
            citizenRotateAfterAdmission(runtime.actors, actor.workerId)
            val origin = citizenIdleBuilding(city, worker) ?: return false
            val anchor = citizenIdleAnchor(city, origin)
            val allowed = city.buildings.filter { it.id == origin.id || it.type == BuildingType.TOWN_HALL }.mapTo(hashSetOf()) { it.id }
            val place = campfires.nearby(world, city, anchor, tick).asSequence().take(4).filter { fire ->
                citizenRouteCellAllowed(city, allowed, fire.position.x, fire.position.z) &&
                    citizenCampfireLit(world, fire.position) &&
                    runtime.actors.values.count { it.social.visit?.fire == fire } < 6
            }.mapNotNull { fire -> citizenCampSeats(fire.position, actor.workerId).firstOrNull { point ->
                citizenRouteCellAllowed(city, allowed, point.x, point.z) && citizenControlClear(city, point) &&
                    runtime.actors.values.none { other -> other !== actor && other.worldId == city.worldId &&
                        (other.social.visit?.seat?.let { it.x == point.x && it.z == point.z } == true ||
                            other.npc.isValid && other.npc.location.point().distanceSquared(point.point()) < 1.4 * 1.4) } &&
                    citizenIdleTerrain(world, point, city.buildings) && citizenStandable(world, point) &&
                    citizenCampfireVisible(world, point, fire.position)
            }?.let { fire to it } }.firstOrNull()
            if (place == null) {
                if (startMeeting(actor, city, worker, world, now, tick))
                    return continueMeeting(actor, city, world, tick)
                routine.discoveryMiss(tick)
                return false
            }
            val (fire, seat) = place
            routine.start(fire, seat, tick, world.fullTime / 24_000, evening)
            visit = requireNotNull(routine.visit)
            CivsDiagnostics.event("citizen_campfire", "city" to actor.cityId, "worker" to actor.workerId,
                "action" to "start", "fire" to fire.position, "seat" to seat, "evening" to evening)
            if (!routes.startRoute(actor, world, seat, now, idleAnchor = fire.position)) {
                stopCamp(actor, "route-unavailable")
                return false
            }
        }
        val activeVisit = requireNotNull(visit)
        if (activeVisit.partnerId != null) return continueMeeting(actor, city, world, tick)
        val fire = requireNotNull(activeVisit.fire)
        if (routine.expired(tick) || !citizenCampfireLit(world, fire.position) ||
            !citizenIdleTerrain(world, activeVisit.seat, city.buildings)) {
            stopCamp(actor, if (routine.expired(tick)) {
                if (activeVisit.arrivedAt == null) "travel-timeout" else "rest-finished"
            } else "fire-or-seat-unavailable")
            return false
        }
        if (!citizenCampfireVisible(world, activeVisit.seat, fire.position)) {
            stopCamp(actor, "fire-occluded")
            return false
        }
        if (citizenArrived(actor.npc.location, activeVisit.seat.location(world))) {
            if (!citizenCampfireVisible(world, actor.npc.location.clone().add(0.0, actor.npc.eyeHeight, 0.0), fire.position)) {
                stopCamp(actor, "fire-occluded-at-arrival")
                return false
            }
            if (activeVisit.arrivedAt == null) {
                routes.cancelRoute(actor)
                activeVisit.arrivedAt = tick
                CivsDiagnostics.event("citizen_campfire", "city" to actor.cityId, "worker" to actor.workerId,
                    "action" to "arrived", "fire" to fire.position, "seat" to activeVisit.seat)
            }
            val company = runtime.actors.values.firstOrNull { other -> other !== actor &&
                other.social.visit?.let { it.fire == activeVisit.fire && it.arrivedAt != null } == true &&
                other.npc.isValid }
            if (!activeVisit.greeted && company != null) {
                activeVisit.greeted = true
                speak(actor, CitizenVoiceEvent.GREET)
            } else speak(actor, CitizenVoiceEvent.IDLE)
            val player = actor.npc
            val focus = company?.npc?.location?.add(0.0, 1.4, 0.0)
                ?: Location(world, fire.position.x + .5, fire.position.y + .4, fire.position.z + .5)
            citizenLookDirection(player.location.clone().add(0.0, player.eyeHeight, 0.0), focus).let { (yaw, pitch) -> routes.look(player, yaw, pitch) }
            life.creditRest(actor, city, worker, tick)
            runtime.statuses[actor.workerId] = "campfire"
            return true
        }
        val route = actor.route
        if (activeVisit.arrivedAt != null || route == null || route.completion != null ||
            !route.chunks.ready(world) || route.timedOut(now)) {
            stopCamp(actor, if (activeVisit.arrivedAt != null) "left-seat" else "route-ended")
            return false
        }
        runtime.statuses[actor.workerId] = "to-campfire"
        return true
    }

    /** Reuse the social admission budget and lifecycle for a short pair encounter without a fireplace. */
    private fun startMeeting(actor: CitizenActor, city: Settlement, worker: CivWorker, world: World, now: Long, tick: Int): Boolean {
        fun free(other: CitizenActor): Boolean = other.cityId == city.id && other.worldId == city.worldId &&
            other.npc.isValid && other.npc.world == world && !other.sleeping &&
            !other.sheltering && other.attacker == null && other.foodSource == null && other.supplyPickup == null &&
            !logistics.collectedPending(other) && other.collectionSeek == null && other.collectionHome == null &&
            !other.returningToWarehouse && other.workerId !in runtime.pendingHarm && other.route == null &&
            other.plan.idleReason in setOf("unassigned", "no-construction", "waiting-growth", "waiting-tool", "construction-paused") &&
            citizenCampEligible(other.plan, other.cycle.phase, world.time) &&
            city.workers.firstOrNull { it.id == other.workerId }?.let { !CivsResidents.isChild(it) && it.cargo == null } == true &&
            other.social.meetingAvailable(tick)
        if (!free(actor)) return false
        val partner = runtime.actors.values.asSequence().filter { it !== actor && free(it) &&
            it.npc.location.distanceSquared(actor.npc.location) <= 6.0 * 6.0 }
            .minByOrNull { it.npc.location.distanceSquared(actor.npc.location) } ?: return false
        val otherWorker = city.workers.first { it.id == partner.workerId }
        val home = citizenIdleBuilding(city, worker) ?: return false
        val otherHome = citizenIdleBuilding(city, otherWorker) ?: return false
        val from = actor.npc.location
        val otherFrom = partner.npc.location
        val middle = CivPosition(kotlin.math.floor((from.x + otherFrom.x) / 2).toInt(),
            from.blockY, kotlin.math.floor((from.z + otherFrom.z) / 2).toInt())
        fun safe(other: CitizenActor, origin: CivBuilding, seat: CivPosition): Boolean =
            citizenWithinIdleArea(origin.entrance, seat.point()) &&
                citizenRouteCellAllowed(city, setOf(origin.id) + city.buildings.filter { it.type == BuildingType.TOWN_HALL }.map { it.id }, seat.x, seat.z) &&
                citizenControlClear(city, seat) && collisions.idleDestinationFree(other, seat.location(world)) &&
                citizenStandable(world, seat) && citizenIdleTerrain(world, seat, city.buildings)
        // Six pairs / twelve standing checks at most; no block-volume or population scan beyond 48 owned runtime.actors.
        for ((dx, dz) in listOf(1 to 0, 0 to 1)) for (dy in listOf(0, 1, -1)) {
            var first = CivPosition(middle.x - dx, middle.y + dy, middle.z - dz)
            var second = CivPosition(middle.x + dx, middle.y + dy, middle.z + dz)
            if (from.point().distanceSquared(first.point()) + otherFrom.point().distanceSquared(second.point()) >
                from.point().distanceSquared(second.point()) + otherFrom.point().distanceSquared(first.point())) {
                val swap = first; first = second; second = swap
            }
            if (!safe(actor, home, first) || !safe(partner, otherHome, second) ||
                !threats.visible(first.location(world).add(0.0, actor.npc.eyeHeight, 0.0),
                    second.location(world).add(0.0, partner.npc.eyeHeight, 0.0))) continue
            actor.social.startMeeting(partner.workerId, first, tick)
            partner.social.startMeeting(actor.workerId, second, tick)
            if (!routes.startRoute(actor, world, first, now, idleAnchor = home.entrance) ||
                !routes.startRoute(partner, world, second, now, idleAnchor = otherHome.entrance)) {
                stopCamp(actor, "route-unavailable")
                return false
            }
            CivsDiagnostics.event("citizen_meeting", "city" to city.id, "worker" to actor.workerId,
                "partner" to partner.workerId, "action" to "start", "seat" to first, "partner_seat" to second)
            return true
        }
        return false
    }

    private fun continueMeeting(actor: CitizenActor, city: Settlement, world: World, tick: Int): Boolean {
        val visit = actor.social.visit ?: return false
        val partner = runtime.actors[visit.partnerId]?.takeIf { it.cityId == city.id && it.npc.isValid &&
            it.social.visit?.partnerId == actor.workerId }
        if (partner == null || actor.social.expired(tick) ||
            !citizenStandable(world, visit.seat) || !citizenIdleTerrain(world, visit.seat, city.buildings)) {
            stopCamp(actor, if (partner == null) "partner-unavailable" else "visit-ended")
            return false
        }
        val otherVisit = requireNotNull(partner.social.visit)
        if (!citizenArrived(actor.npc.location, visit.seat.location(world))) {
            val route = actor.route
            if (visit.arrivedAt != null || route == null || route.completion != null ||
                !route.chunks.ready(world) || route.motion.waitingForCollision && runtime.navigationTick - route.progressTick >= 40) {
                stopCamp(actor, "approach-blocked")
                return false
            }
            runtime.statuses[actor.workerId] = "meeting"
            return true
        }
        if (visit.arrivedAt == null) { routes.cancelRoute(actor); visit.arrivedAt = tick }
        val eye = actor.npc.location.add(0.0, actor.npc.eyeHeight, 0.0)
        val otherEye = partner.npc.location.add(0.0, partner.npc.eyeHeight, 0.0)
        val (yaw, pitch) = citizenLookDirection(eye, otherEye)
        routes.look(actor.npc, yaw, pitch)
        if (otherVisit.arrivedAt == null) { runtime.statuses[actor.workerId] = "meeting"; return true }
        if (eye.distanceSquared(otherEye) > 4.0 * 4.0 || !threats.visible(eye, otherEye)) {
            stopCamp(actor, "partner-occluded")
            return false
        }
        if (visit.conversationAt == null) {
            visit.conversationAt = tick
            otherVisit.conversationAt = tick
        }
        val beat = (tick - requireNotNull(visit.conversationAt)) / 50
        val turn = if (actor.workerId < partner.workerId) 0 else 1
        if (beat % 2 == turn && visit.gestureBeat != beat && abs(citizenAngleChange(actor.npc.bodyYaw, yaw)) <= 20f) {
            actor.npc.swingMainHand()
            speak(actor, if (visit.greeted) CitizenVoiceEvent.IDLE else CitizenVoiceEvent.GREET)
            visit.greeted = true
            visit.gestureBeat = beat
        }
        city.workers.firstOrNull { it.id == actor.workerId }?.let { life.creditRest(actor, city, it, tick) }
        runtime.statuses[actor.workerId] = "talking"
        return true
    }

    fun wander(actor: CitizenActor, city: Settlement, world: World, now: Long, outsideHome: Boolean = false) {
        val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: return
        val home = citizenIdleBuilding(city, worker) ?: return
        val anchor = citizenIdleAnchor(city, home)
        val outside: (CivPosition) -> Boolean = { point ->
            point.x !in home.bounds.min.x..home.bounds.max.x || point.z !in home.bounds.min.z..home.bounds.max.z
        }
        val route = actor.route
        if (route != null) {
            val outcome = if (!route.chunks.ready(world)) CitizenNavigationOutcome.STOPPED
                else citizenNavigationOutcome(route.completion,
                    citizenArrived(actor.npc.location, route.target),
                    route.timing.elapsed(now), route.timeoutMillis, route.motion.navigating, route.motion.searching)
            if (outcome == CitizenNavigationOutcome.PENDING) return
            if (outcome != CitizenNavigationOutcome.ARRIVED &&
                !citizenSafeIdlePosition(world, anchor, actor.npc.location.point(), city.buildings) &&
                routes.recoverNavigation(actor, route.target, now, idleAnchor = anchor)) {
                actor.wander.finish(now, true, resumeImmediately = worker.assignment == null)
                return
            }
            routes.cancelRoute(actor)
            actor.wander.finish(now, outcome == CitizenNavigationOutcome.ARRIVED,
                resumeImmediately = worker.assignment == null)
            CivsDiagnostics.event("citizen_wander", "city" to city.id, "worker" to actor.workerId,
                "outcome" to outcome.name.lowercase(), "target" to route.target.point().coordinates(),
                "reason" to route.motion.failure, "actual" to actor.npc.location.point().coordinates(),
                "from" to route.from.coordinates(), "path_progress" to route.motion.forwardProgress,
                "path_nodes" to route.motion.nodeCount, "elapsed_ms" to now - route.startedAt)
            return
        }
        val returning = !citizenWithinIdleArea(anchor, actor.npc.location.point()) || outsideHome &&
            !outside(CivPosition(actor.npc.location.blockX, actor.npc.location.blockY, actor.npc.location.blockZ))
        if (!returning && !actor.wander.ready(now) || runtime.wanderBudget == 0) return
        runtime.wanderBudget--
        // Keep short invalid-target retries fair within the shared eight-check budget.
        citizenRotateAfterAdmission(runtime.actors, actor.workerId)
        val returnTarget = anchor.takeIf { returning && (!outsideHome || outside(it) &&
            citizenStandable(world, it) && citizenIdleTerrain(world, it, city.buildings)) }
        val target = if (returnTarget != null) returnTarget
            else citizenWanderTarget(city, anchor, actor.workerId, actor.wander.step, if (outsideHome) emptySet() else setOf(home.id),
                actor.npc.location.point(), actor.npc.bodyYaw)?.takeIf { !outsideHome || outside(it) }
        // startRoute owns the single loaded-chunk, support and headroom check.
        if (target != null && actor.npc.location.point().distanceSquared(target.point()) >= 1.0 &&
            routes.startRoute(actor, world, target, now, idleAnchor = anchor)) {
            CivsDiagnostics.event("citizen_wander", "city" to city.id,
                "worker" to actor.workerId, "outcome" to "started", "target" to target)
        } else {
            actor.wander.reject(now)
            if (actor.wander.step % CITIZEN_WANDER_HEIGHTS.size == 0) {
                CivsDiagnostics.event("citizen_wander", "city" to city.id, "worker" to actor.workerId,
                    "outcome" to "target-unavailable", "target" to target, "next_step" to actor.wander.step)
            }
        }
    }
}
