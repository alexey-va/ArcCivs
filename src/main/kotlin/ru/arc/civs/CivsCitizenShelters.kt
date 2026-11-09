package ru.arc.civs

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.util.BoundingBox
import ru.arc.paper.npc.PacketNpcPose

/** Route replacement is not progress; only a quarter-block change of the resident's actual feet renews this window. */
internal class CitizenNightHomeProgress {
    private var home: CivBuilding? = null
    private var feet: CitizenPoint? = null
    private var progressedAt = 0L
    private var observedAt = 0L
    private var rotatedAt: Long? = null
    private var retryAt = 0L

    fun stalled(currentHome: CivBuilding, actual: CitizenPoint, now: Long): Boolean {
        if (!sameEpisode(currentHome, actual, now)) {
            home = currentHome
            feet = actual
            progressedAt = now
            rotatedAt = null
            retryAt = now
        }
        observedAt = now
        return now - (rotatedAt ?: progressedAt) >= STALL_MILLIS
    }

    fun rotated(now: Long) { rotatedAt = now }

    fun recoveryDue(currentHome: CivBuilding, actual: CitizenPoint, now: Long): Boolean =
        sameEpisode(currentHome, actual, now) && rotatedAt != null &&
            now - progressedAt >= 2 * STALL_MILLIS && now >= retryAt

    fun attemptedRecovery(now: Long) { retryAt = now + STALL_MILLIS }

    private fun sameEpisode(currentHome: CivBuilding, actual: CitizenPoint, now: Long): Boolean =
        home == currentHome && now >= observedAt && feet?.distanceSquared(actual)?.let { it < .25 * .25 } == true

    fun clear() {
        home = null
        feet = null
        progressedAt = 0
        observedAt = 0
        rotatedAt = null
        retryAt = 0
    }

    companion object { const val STALL_MILLIS = 30_000L }
}

/** Night shelter reservation and native sleeping posture lifecycle. */
internal class CivsCitizenShelters(
    private val runtime: CitizenRuntime,
    private val collisionsOwner: () -> CivsCitizenCollisions,
    private val routesOwner: () -> CivsCitizenRoutes,
    private val lifeOwner: () -> CivsCitizenLife,
) {
    private val collisions get() = collisionsOwner()
    private val routes get() = routesOwner()
    private val life get() = lifeOwner()

    fun shelter(actor: CitizenActor, city: Settlement, worker: CivWorker, world: World, now: Long) {
        if (!actor.sheltering) {
            routes.cancelRoute(actor)
            actor.returningToWarehouse = false
            actor.sheltering = true
        }
        if (actor.sleeping) {
            actor.nightHomeProgress.clear()
            runtime.statuses[actor.workerId] = "sleeping"
            if (sleepStillSafe(actor)) return
            if (!wake(actor)) return
        }
        val home = citizenHomeBuilding(city, worker)
        if (city.autonomy != null && world.time in 12_000L..23_000L && !actor.alarmSheltering && actor.attacker == null &&
            home?.phase != BuildingPhase.ACTIVE) {
            // A future home floor need not be reachable. Admit only actual grounded feet until this resident's selected home is built.
            routes.cancelRoute(actor)
            actor.shelter = null
            actor.shelterPose = null
            actor.nightHomeProgress.clear()
            runtime.statuses[actor.workerId] = "sheltering"
            if (runtime.wanderBudget <= 0) return
            runtime.wanderBudget--
            citizenRotateAfterAdmission(runtime.actors, actor.workerId)
            val origin = actor.npc.location
            val current = CivPosition(origin.blockX, origin.blockY, origin.blockZ)
            if (!citizenControlClear(city, current) || !citizenIdleTerrain(world, current, city.buildings)) return
            val occupied = collisions.shelterOccupied(actor)
            if (occupied.any(citizenStandingBounds(origin, actor.npc.scale)::overlaps)) return
            val pose = citizenSleepPose(world, origin, actor.npc.scale) { body ->
                body.minX >= city.center.x.toDouble() - city.radius && body.maxX <= city.center.x.toDouble() + city.radius + 1.0 &&
                    body.minZ >= city.center.z.toDouble() - city.radius && body.maxZ <= city.center.z.toDouble() + city.radius + 1.0 &&
                    occupied.none(body::overlaps)
            } ?: return
            actor.shelter = current
            sleep(actor, origin, pose)
            return
        }
        val nightHome = home?.takeIf { routes.canResetNightShelter(actor) && city.id == actor.cityId &&
            city.worldId == actor.worldId && world.uid == actor.worldId }
        if (nightHome == null) actor.nightHomeProgress.clear()
        if (!actor.sleeping && actor.shelter?.let { point ->
                !citizenStandable(world, point) || actor.shelterPose?.let { !citizenSleepClear(world, it.bounds) } != false ||
                    actor.shelterPose?.let { pose -> collisions.shelterOccupied(actor).any(pose.bounds::overlaps) } == true ||
                    !citizenArrived(actor.npc.location, point.location(world)) &&
                    !CitizenChunks.between(actor.npc.location.blockX, actor.npc.location.blockZ, point.x, point.z).ready(world)
            } == true) {
            routes.cancelRoute(actor)
            actor.shelter = null
            actor.shelterPose = null
            actor.nightHomeProgress.clear()
        }
        if (actor.shelter == null && runtime.wanderBudget > 0) {
            runtime.wanderBudget--
            citizenRotateAfterAdmission(runtime.actors, actor.workerId)
            reserveShelter(actor, city, worker, world)
        }
        runtime.statuses[actor.workerId] = if (actor.sleeping) "sleeping" else "sheltering"
        val target = actor.shelter ?: return
        if (citizenArrived(actor.npc.location, target.location(world))) {
            // Arrival has a tolerance; validate the observed feet, not the target's centre.
            val origin = actor.npc.location
            val pose = shelterPose(actor, city, worker, origin, collisions.shelterOccupied(actor))
            if (pose != null) {
                sleep(actor, origin, pose)
                return
            }
            // Finish the short walk if only the arrival-tolerance position overlaps.
            // If the actual endpoint became occupied, release it for bounded admission
            // on the next pulse instead of retaining a finished route forever.
            if (actor.route?.motion?.finished == true) {
                routes.cancelRoute(actor)
                actor.shelter = null
                actor.shelterPose = null
                actor.nightHomeProgress.clear()
                return
            }
        }
        val route = actor.route
        if (nightHome != null) {
            val stalled = actor.nightHomeProgress.stalled(nightHome, actor.npc.location.point(), now)
            if (route != null) {
                if (route.motion.failure == "capture-budget" &&
                    routes.recoverNavigation(actor, route.target, now, nightHome = nightHome)) return
                if (stalled && routes.resetNightShelter(actor, now)) return
            }
        }
        if (route == null) { routes.startRoute(actor, world, target, now); return }
        if (route.completion?.cancelReason == "resident-blocked") {
            routes.cancelRoute(actor)
            actor.shelter = null
            actor.shelterPose = null
            actor.nightHomeProgress.clear()
            return
        }
        if (route.completion != null || route.timedOut(now)) {
            // Keep the failed attempt until the shared recovery budget can admit it.
            // Cancelling it here used to restart the same impossible route every pulse.
            if (!routes.retryPreparation(actor, route, now)) routes.recoverNavigation(actor, route.target, now)
        }
    }

    private fun sleep(actor: CitizenActor, origin: Location, pose: CitizenSleepPose) {
        routes.cancelRoute(actor)
        actor.sleepOrigin = origin
        actor.sleepPose = pose
        actor.shelterPose = pose
        actor.npc.sleepingBed = null
        actor.npc.teleport(pose.position)
        actor.npc.headYaw = pose.position.yaw
        actor.npc.pose = PacketNpcPose.SLEEPING
        actor.sleeping = true
        actor.nightHomeProgress.clear()
        runtime.statuses[actor.workerId] = "sleeping"
        CivsDiagnostics.event("citizen_sleep", "city" to actor.cityId, "worker" to actor.workerId,
            "action" to "sleep_requested", "target" to actor.shelter)
    }

    fun shelterPose(actor: CitizenActor, city: Settlement, worker: CivWorker, feet: Location,
                            occupied: List<BoundingBox>): CitizenSleepPose? {
        val home = citizenHomeBuilding(city, worker) ?: return null
        if (occupied.any(citizenStandingBounds(feet, actor.npc.scale)::overlaps)) return null
        return citizenSleepPose(actor.npc.world, feet, actor.npc.scale) { body ->
            body.minX >= home.bounds.min.x && body.maxX <= home.bounds.max.x + 1.0 &&
                body.minY >= home.bounds.min.y && body.maxY <= home.bounds.max.y + 1.0 &&
                body.minZ >= home.bounds.min.z && body.maxZ <= home.bounds.max.z + 1.0 && occupied.none(body::overlaps)
        }
    }

    fun reserveShelter(actor: CitizenActor, city: Settlement, worker: CivWorker, world: World,
                               safe: (CivPosition) -> Boolean = { true }): CivPosition? {
        val candidates = citizenShelterCandidates(city, worker)
        if (candidates.isEmpty()) return null
        val occupied = collisions.shelterOccupied(actor)
        // Preserve a clear existing place in the home before choosing another one.
        // This also keeps a resident already at home from walking just to lie down.
        val at = actor.npc.location
        val current = CivPosition(at.blockX, at.blockY, at.blockZ)
        if (citizenControlClear(city, current) && safe(current)) {
            val pose = shelterPose(actor, city, worker, at, occupied)
            if (pose != null) {
                actor.shelter = current
                actor.shelterPose = pose
                return current
            }
        }
        // Several nearby feet share one search envelope. Retain no native readiness
        // across pulses, and include the existing-place probe in the 80-check budget.
        val ready = hashMapOf<CitizenChunks, Boolean>()
        repeat(minOf(79, candidates.size)) {
            val index = Math.floorMod(actor.shelterSearchIndex++, candidates.size)
            val point = candidates[index]
            val chunks = CitizenChunks.between(at.blockX, at.blockZ, point.x, point.z)
            if (citizenControlClear(city, point) && ready.getOrPut(chunks) { chunks.ready(world) } &&
                citizenStandable(world, point) && safe(point)) {
                val pose = shelterPose(actor, city, worker, point.location(world), occupied)
                if (pose != null) {
                    actor.shelter = point
                    actor.shelterPose = pose
                    return point
                }
            }
        }
        return null
    }

    fun sleepStillSafe(actor: CitizenActor): Boolean {
        val origin = actor.sleepOrigin ?: return false
        return citizenPacketSupported(actor.npc.world, origin.point(), allowClimbable = false) &&
            citizenPacketBodyClear(actor.npc.world, origin.point(), false) &&
            actor.sleepPose?.let { citizenSleepClear(actor.npc.world, it.bounds) } == true
    }

    fun wake(actor: CitizenActor, checkedDestination: Location? = null): Boolean {
        if (!life.standFromMeal(actor)) return false
        val wasSleeping = actor.sleeping
        val npc = actor.npc
        val origin = actor.sleepOrigin
        val destination = if (wasSleeping && npc.isValid) {
            val occupied = collisions.shelterOccupied(actor)
            fun clear(at: Location) = at.world == npc.world && citizenPacketBodyClear(npc.world, at.point(), false) &&
                citizenPacketCampfireClear(npc.world, at.point()) &&
                occupied.none(citizenStandingBounds(at, npc.scale)::overlaps)
            checkedDestination?.takeIf(::clear) ?: origin?.takeIf(::clear) ?: run {
                if (runtime.recoveryBudget == 0 || origin == null) return false
                runtime.recoveryBudget--
                val city = runtime.cityProvider().firstOrNull { it.id == actor.cityId } ?: return false
                val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: return false
                val home = citizenHomeBuilding(city, worker) ?: return false
                val lying = actor.sleepPose?.bounds ?: return false
                citizenShelterTarget(city, worker) { point ->
                    val at = point.location(npc.world)
                    citizenStandable(npc.world, point) && clear(at) && citizenControlClear(city, point) &&
                        citizenRouteCellAllowed(city, setOf(home.id), point.x, point.z) &&
                        // Slide the low pose only through its clear swept envelope before standing.
                        // This cannot jump through a wall into another room or across a control.
                        citizenSleepClear(npc.world, lying.clone().union(lying.clone().shift(at.toVector().subtract(origin.toVector())))) &&
                        collisions.residentMoveAllowed(actor, at, at)
                }?.location(npc.world) ?: return false
            }
        } else null
        actor.sleeping = false
        actor.sleepOrigin = null
        actor.sleepPose = null
        if (wasSleeping) { actor.shelter = null; actor.shelterPose = null }
        if (!npc.isValid) return true
        npc.sleepingBed = null
        if (destination != null) npc.teleport(destination)
        npc.pose = PacketNpcPose.STANDING
        if (wasSleeping) CivsDiagnostics.event("citizen_sleep", "city" to actor.cityId, "worker" to actor.workerId, "action" to "wake")
        return true
    }
}
