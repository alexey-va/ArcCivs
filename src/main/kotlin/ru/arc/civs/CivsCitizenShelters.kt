package ru.arc.civs

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.util.BoundingBox
import ru.arc.paper.npc.PacketNpcPose
import java.util.concurrent.CompletableFuture

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
    loadFloors: (CivBuilding) -> CompletableFuture<CivSleepingFloorMask>,
) : AutoCloseable {
    private val collisions get() = collisionsOwner()
    private val routes get() = routesOwner()
    private val life get() = lifeOwner()
    private val floors = CivsSleepingFloors(loadFloors, runtime.plugin.logger)
    private val sleepOffsets = listOf(0.0 to 0.0, -.25 to 0.0, .25 to 0.0, 0.0 to -.25, 0.0 to .25)

    private fun completedHome(building: CivBuilding): Boolean = building.phase == BuildingPhase.ACTIVE &&
        (building.type.isResidence || building.type == BuildingType.TOWN_HALL)

    private fun eligibleHome(building: CivBuilding, preferred: CivBuilding?): Boolean = completedHome(building) &&
        (preferred?.phase != BuildingPhase.ACTIVE || preferred.id == building.id || building.type == BuildingType.TOWN_HALL)

    private fun reservedHome(actor: CitizenActor, city: Settlement, worker: CivWorker): CivBuilding? {
        val id = actor.shelterHome ?: return null
        val preferred = citizenHomeBuilding(city, worker)
        if (preferred != null && preferred.id == id && completedHome(preferred)) return preferred
        return city.building(id)?.takeIf { eligibleHome(it, preferred) }
    }

    private fun clearReservation(actor: CitizenActor) {
        actor.shelter = null
        actor.shelterPose = null
        actor.shelterHome = null
        actor.shelterFeet = null
        actor.shelterLastReason = null
        actor.nightHomeProgress.clear()
    }

    fun shelter(actor: CitizenActor, city: Settlement, worker: CivWorker, world: World, now: Long) {
        if (!actor.sheltering) {
            routes.cancelRoute(actor)
            actor.returningToWarehouse = false
            actor.sheltering = true
            clearReservation(actor)
        }
        if (actor.sleeping) {
            actor.nightHomeProgress.clear()
            runtime.statuses[actor.workerId] = "sleeping"
            if (sleepStillSafe(actor, city, worker)) return
            if (!wake(actor)) return
        }
        val home = citizenHomeBuilding(city, worker)
        if (city.autonomy != null && world.time in 12_000L..23_000L && !actor.alarmSheltering && actor.attacker == null &&
            home?.phase != BuildingPhase.ACTIVE && city.buildings.none(::completedHome)) {
            // Grounded bootstrap sleep exists only until any completed eligible home can provide indoor shelter.
            routes.cancelRoute(actor)
            clearReservation(actor)
            runtime.statuses[actor.workerId] = "sheltering"
            if (runtime.wanderBudget <= 0) return
            runtime.wanderBudget--
            citizenRotateAfterAdmission(runtime.actors, actor.workerId)
            val origin = actor.npc.location
            val current = CivPosition(origin.blockX, origin.blockY, origin.blockZ)
            if (!citizenControlClear(city, current) || !citizenIdleTerrain(world, current, city.buildings)) return
            val occupied = collisions.shelterOccupied(actor)
            if (occupied.any(citizenStandingBounds(origin, actor.npc.scale)::overlaps)) return
            val pose = citizenSleepPose(world, origin, actor.npc.scale, floorLift = CIVS_SLEEP_PAD_HEIGHT) { body ->
                body.minX >= city.center.x.toDouble() - city.radius && body.maxX <= city.center.x.toDouble() + city.radius + 1.0 &&
                    body.minZ >= city.center.z.toDouble() - city.radius && body.maxZ <= city.center.z.toDouble() + city.radius + 1.0 &&
                    occupied.none(body::overlaps)
            } ?: return
            actor.shelter = current
            actor.shelterFeet = origin.clone()
            sleep(actor, origin, pose)
            return
        }
        val trackNight = routes.canResetNightShelter(actor) && city.id == actor.cityId &&
            city.worldId == actor.worldId && world.uid == actor.worldId
        if (!trackNight) actor.nightHomeProgress.clear()
        if (!actor.sleeping && actor.shelter?.let { point ->
                routeFeet(actor, city, world, point) == null ||
                    actor.shelterPose?.let { !citizenSleepClear(world, it.bounds) } != false ||
                    actor.shelterPose?.let { pose -> collisions.shelterOccupied(actor).any(pose.bounds::overlaps) } == true ||
                    !citizenArrived(actor.npc.location, actor.shelterFeet ?: point.location(world)) &&
                    !CitizenChunks.between(actor.npc.location.blockX, actor.npc.location.blockZ, point.x, point.z).ready(world)
            } == true) {
            routes.cancelRoute(actor)
            clearReservation(actor)
        }
        if (actor.shelter == null && runtime.wanderBudget > 0) {
            runtime.wanderBudget--
            citizenRotateAfterAdmission(runtime.actors, actor.workerId)
            reserveShelter(actor, city, worker, world)
        }
        runtime.statuses[actor.workerId] = "sheltering"
        val target = actor.shelter ?: return
        val targetFeet = actor.shelterFeet ?: return
        if (citizenArrived(actor.npc.location, targetFeet)) {
            val origin = actor.npc.location
            val offset = targetFeet.x != target.x + .5 || targetFeet.z != target.z + .5
            val needsAlignment = offset && origin.distanceSquared(targetFeet) > .000001
            if (!needsAlignment && (!offset || citizenSleepStanding(world, target, origin))) {
                val pose = shelterPose(actor, city, worker, origin, collisions.shelterOccupied(actor))
                if (pose != null) { sleep(actor, origin, pose); return }
            }
            if (actor.route?.motion?.finished == true) {
                if (needsAlignment && routes.alignShelterFeet(actor, targetFeet, now)) return
                routes.cancelRoute(actor)
                clearReservation(actor)
                return
            }
        }
        val route = actor.route
        val nightHome = if (trackNight) actor.shelterHome?.let(city::building) else null
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
            clearReservation(actor)
            return
        }
        if (route.completion != null || route.timedOut(now)) {
            if (!routes.retryPreparation(actor, route, now)) {
                if (home?.phase == BuildingPhase.ACTIVE && actor.shelterHome == home.id)
                    routes.recoverNavigation(actor, route.target, now)
                else {
                    // A temporary home grants an ordinary walk, never a new recovery destination.
                    routes.cancelRoute(actor)
                    clearReservation(actor)
                }
            }
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
        actor.shelterLastReason = "sleeping"
        actor.nightHomeProgress.clear()
        runtime.statuses[actor.workerId] = "sleeping"
        CivsDiagnostics.event("citizen_sleep", "city" to actor.cityId, "worker" to actor.workerId,
            "action" to "sleep_requested", "target" to actor.shelter)
    }

    fun shelterPose(actor: CitizenActor, city: Settlement, worker: CivWorker, feet: Location,
                    occupied: List<BoundingBox>): CitizenSleepPose? {
        val home = reservedHome(actor, city, worker) ?: return null
        val mask = floors.get(city, home) ?: return null
        val floorY = actor.shelter?.y ?: return null
        return roomPose(actor, home, mask, floorY, feet, occupied)
    }

    private fun roomPose(actor: CitizenActor, home: CivBuilding, mask: CivSleepingFloorMask, floorY: Int,
                         feet: Location, occupied: List<BoundingBox>, onOccupied: (() -> Unit)? = null): CitizenSleepPose? {
        if (occupied.any(citizenStandingBounds(feet, actor.npc.scale)::overlaps)) {
            onOccupied?.invoke()
            return null
        }
        return citizenSleepPose(actor.npc.world, feet, actor.npc.scale, floorLift = CIVS_SLEEP_PAD_HEIGHT) { body ->
            if (!citizenSleepRoom(mask.cells, floorY, feet, body) || body.maxY > home.bounds.max.y + 1.0) return@citizenSleepPose false
            if (occupied.none(body::overlaps)) return@citizenSleepPose true
            onOccupied?.invoke()
            false
        }
    }

    /** Exact native endpoint plus current source identity and full room pose; no route or recovery authority is added. */
    fun routeFeet(actor: CitizenActor, city: Settlement, world: World, target: CivPosition): Location? {
        if (!actor.sheltering || actor.shelter != target || world.uid != actor.worldId || city.worldId != actor.worldId) return null
        val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: return null
        val home = reservedHome(actor, city, worker) ?: return null
        val mask = floors.get(city, home) ?: return null
        val feet = actor.shelterFeet ?: return null
        if (!citizenControlClear(city, target) || !citizenSleepStanding(world, target, feet) ||
            roomPose(actor, home, mask, target.y, feet, collisions.shelterOccupied(actor)) == null) return null
        return feet.clone()
    }

    fun reserveShelter(actor: CitizenActor, city: Settlement, worker: CivWorker, world: World,
                       safe: (CivPosition, Location) -> Boolean = { _, _ -> true }): CivPosition? {
        val occupied = collisions.shelterOccupied(actor)
        val at = actor.npc.location
        val ready = hashMapOf<CitizenChunks, Boolean>()
        var remaining = 80
        var reason = "mask-unavailable"
        val onOccupied = { reason = "occupied" }
        val preferred = citizenHomeBuilding(city, worker)
        val eligible = city.buildings.filter { eligibleHome(it, preferred) }
        val homes = if (preferred?.phase == BuildingPhase.ACTIVE) eligible.sortedBy { it.id != preferred.id }
        else eligible.sortedWith(compareBy<CivBuilding>(
            { kotlin.math.abs(it.entrance.x - at.blockX) + kotlin.math.abs(it.entrance.z - at.blockZ) }, { it.id }))
        actor.shelterHomeSearch.keys.retainAll(homes.mapTo(hashSetOf()) { it.id })
        for ((homeIndex, home) in homes.withIndex()) {
            val mask = floors.get(city, home) ?: continue
            if (reason == "mask-unavailable") reason = "no-fitting-pose"
            if (mask.points.isEmpty()) continue
            fun reserve(point: CivPosition, feet: Location): CivPosition? {
                if (!safe(point, feet)) return null
                val pose = roomPose(actor, home, mask, point.y, feet, occupied, onOccupied) ?: return null
                actor.shelter = point
                actor.shelterPose = pose
                actor.shelterHome = home.id
                actor.shelterFeet = feet.clone()
                actor.shelterLastReason = "approaching"
                return point
            }
            // Preserve a grounded current location only when its entire body belongs to the room.
            for (y in listOf(at.blockY, at.blockY + 1)) {
                val current = CivPosition(at.blockX, y, at.blockZ)
                if (current !in mask.cells || remaining <= 0) continue
                remaining--
                if (citizenControlClear(city, current) && citizenSleepStanding(world, current, at))
                    reserve(current, at)?.let { return it }
            }
            // Let the preferred room use its share on quarter poses before trying the communal hall.
            val stances = mask.points.size * sleepOffsets.size
            val limit = if (home == preferred && homes.size > 1) stances else mask.points.size
            val probes = minOf(limit, remaining, maxOf(1, remaining / (homes.size - homeIndex)))
            repeat(probes) {
                remaining--
                val index = actor.shelterHomeSearch.getOrDefault(home.id, 0)
                actor.shelterHomeSearch[home.id] = index + 1
                actor.shelterSearchIndex++
                // Finish the center sweep before trying each lateral offset. Each stance costs one probe.
                val stance = Math.floorMod(index, stances)
                val point = mask.points[stance % mask.points.size]
                val (dx, dz) = sleepOffsets[stance / mask.points.size]
                val chunks = CitizenChunks.between(at.blockX, at.blockZ, point.x, point.z)
                if (citizenControlClear(city, point) && ready.getOrPut(chunks) { chunks.ready(world) }) {
                    citizenSleepFeet(world, point, dx, dz)?.let { feet -> reserve(point, feet)?.let { return it } }
                }
            }
            if (remaining <= 0) break
        }
        actor.shelterLastReason = reason
        return null
    }

    fun sleepStillSafe(actor: CitizenActor, city: Settlement, worker: CivWorker): Boolean {
        val origin = actor.sleepOrigin ?: return false
        val pose = actor.sleepPose ?: return false
        if (actor.shelterHome == null) {
            if (citizenHomeBuilding(city, worker)?.phase == BuildingPhase.ACTIVE || city.buildings.any(::completedHome)) return false
        } else {
            val home = reservedHome(actor, city, worker) ?: return false
            val mask = floors.get(city, home) ?: return false
            if (!citizenSleepRoom(mask.cells, actor.shelter?.y ?: return false, origin, pose.bounds)) return false
        }
        return citizenPacketSupported(actor.npc.world, origin.point(), allowClimbable = false) &&
            citizenPacketBodyClear(actor.npc.world, origin.point(), false) && citizenSleepClear(actor.npc.world, pose.bounds)
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
                val city = runtime.cityProvider().firstOrNull { it.id == actor.cityId } ?: return false
                val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: return false
                val home = citizenHomeBuilding(city, worker) ?: return false
                // The recovery candidates belong to the assigned home. A guest wakes at the hall's actual feet.
                if (actor.shelterHome != null && actor.shelterHome != home.id) return false
                runtime.recoveryBudget--
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
        if (wasSleeping) clearReservation(actor)
        if (!npc.isValid) return true
        npc.sleepingBed = null
        if (destination != null) npc.teleport(destination)
        npc.pose = PacketNpcPose.STANDING
        if (wasSleeping) CivsDiagnostics.event("citizen_sleep", "city" to actor.cityId, "worker" to actor.workerId, "action" to "wake")
        return true
    }

    override fun close() = floors.close()
}
