package ru.arc.civs

import org.bukkit.World
import org.bukkit.entity.Monster
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue

/** Settlement alarms, resident defence and fenced injury completions. */
internal class CivsCitizenCombat(
    private val runtime: CitizenRuntime,
    private val collisionsOwner: () -> CivsCitizenCollisions,
    private val routesOwner: () -> CivsCitizenRoutes,
    private val shelterOwner: () -> CivsCitizenShelters,
    private val couriersOwner: () -> CivsCitizenCouriers,
    private val leisureOwner: () -> CivsCitizenLeisure,
    private val workOwner: () -> CivsCitizenWork,
    private val equipmentOwner: () -> CivsCitizenEquipment,
    private val lifeOwner: () -> CivsCitizenLife,
    private val threats: CivsResidentThreats,
    private val hurt: (UUID, UUID, Double) -> CompletableFuture<Settlement>,
    private val immortalResidents: (UUID) -> Boolean,
    private val remove: (CitizenActor, String) -> Unit,
) {
    private val collisions get() = collisionsOwner()
    private val routes get() = routesOwner()
    private val shelter get() = shelterOwner()
    private val couriers get() = couriersOwner()
    private val leisure get() = leisureOwner()
    private val workRuntime get() = workOwner()
    private val equipment get() = equipmentOwner()
    private val life get() = lifeOwner()
    private val alarms = mutableMapOf<UUID, ResidentAlarm>()
    private val harmCompletions = ConcurrentLinkedQueue<HarmCompletion>()
    private var harmEpoch = 0L
    private data class HarmCompletion(val workerId: UUID, val city: Settlement?, val error: Throwable?, val epoch: Long)

    fun retain(cities: Set<UUID>, now: Long) {
        alarms.entries.removeIf { (cityId, alarm) -> cityId !in cities || now > alarm.until ||
            !alarm.monster.isValid || alarm.monster.isDead }
    }
    fun clear() { alarms.clear(); harmCompletions.clear() }

    /** Only a successful canonical reload resolves an injury with an unknown commit outcome. */
    fun reconcileResidents() {
        checkCitizenMainThread()
        harmEpoch++
        for (id in runtime.pendingHarm) runtime.actors[id]?.let { it.cycle = it.plan.cycle() }
        runtime.pendingHarm.clear()
        harmCompletions.clear()
    }

    fun defend(workerId: UUID, monster: Monster) {
        val victim = runtime.actors[workerId] ?: return
        if (immortalResidents(victim.cityId)) return
        val now = runtime.residentObservedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        if (runtime.residentLifeEnabled) alarms[victim.cityId] = ResidentAlarm(monster, now + 10_000)
        for (actor in runtime.actors.values) {
            if (actor.cityId != victim.cityId || !actor.npc.isValid || actor.npc.world != monster.world ||
                (if (runtime.residentLifeEnabled) actor.plan.type != BuildingType.GUARD_TOWER
                    else actor.npc.location.distanceSquared(victim.npc.location) > 10.0 * 10.0)) continue
            if (actor.attacker?.uniqueId != monster.uniqueId) {
                if (actor.attacker?.let { it.isValid && !it.isDead } == true) continue
                routes.cancelRoute(actor)
                leisure.stopCamp(actor, "monster")
                actor.sheltering = false
                actor.shelter = null
                actor.shelterPose = null
                actor.cycle = actor.plan.cycle()
                actor.attacker = monster
                actor.combatDestination = null
                actor.combatRouteAfter = 0
            }
            actor.combatUntil = now + 10_000
        }
    }

    fun alarmShelter(actor: CitizenActor, city: Settlement, worker: CivWorker, world: World, now: Long): Boolean {
        val alarm = alarms[city.id]?.takeIf { !immortalResidents(city.id) && now <= it.until && it.monster.isValid && !it.monster.isDead }
        if (alarm == null || actor.plan.type == BuildingType.GUARD_TOWER) {
            if (actor.alarmSheltering) {
                if (!shelter.wake(actor)) return true
                routes.cancelRoute(actor)
                actor.alarmSheltering = false
                actor.sheltering = false
                actor.shelter = null
                actor.shelterPose = null
            }
            return false
        }
        if (actor.cycle.phase in setOf(CitizenPhase.PICKING_UP, CitizenPhase.DELIVERING)) return false
        life.stopMeal(actor)
        actor.mentorVisit = null
        workRuntime.rememberWorkDetour(actor)
        actor.alarmSheltering = true
        actor.attacker = null
        actor.cycle.deferWork(now)
        couriers.releaseCourierSource(actor, now)
        leisure.stopCamp(actor, "alarm")
        shelter.shelter(actor, city, worker, world, now)
        runtime.statuses[worker.id] = "alarm-shelter"
        return true
    }

    /** A few existing road/gate posts, not a second world search or a production cycle. */
    fun patrol(actor: CitizenActor, city: Settlement, worker: CivWorker, world: World, now: Long): Boolean {
        if (actor.plan.type != BuildingType.GUARD_TOWER || actor.plan.idleReason != null || actor.attacker != null) return false
        actor.cycle.deferWork(now)
        val home = worker.assignment?.let(city::building) ?: return false
        val radius = CivsResearch.patrolRadius(city).toDouble()
        val posts = city.buildings.filter { it.phase == BuildingPhase.ACTIVE && it.type in setOf(BuildingType.ROAD, BuildingType.GATE) }
            .flatMap { building -> building.transit.orEmpty().let { cells ->
                if (cells.isEmpty()) listOf(building.entrance) else listOf(cells.first(), cells[cells.size / 2], cells.last())
            } }
            .filter { it.point().distanceSquared(home.entrance.point()) <= radius * radius }
            .distinct().sortedWith(compareBy<CivPosition> { it.x }.thenBy { it.z }).let { points ->
                if (points.size <= 4) points else listOf(0, points.size / 3, points.size * 2 / 3, points.lastIndex).map(points::get)
            }
        if (posts.isEmpty()) return false
        val target = actor.patrolTarget
        if (target != null) {
            if (citizenArrived(actor.npc.location, target.location(world))) {
                routes.cancelRoute(actor)
                actor.patrolTarget = null
                actor.patrolAfter = now + 3_000
                actor.patrolIndex++
            } else if (actor.route?.let { it.completion != null || it.timedOut(now) } != false) {
                routes.cancelRoute(actor); actor.patrolTarget = null; actor.patrolAfter = now + 5_000; actor.patrolIndex++
            }
        } else if (now >= actor.patrolAfter && runtime.wanderBudget > 0) {
            runtime.wanderBudget--
            val next = posts[Math.floorMod(actor.patrolIndex + worker.id.hashCode(), posts.size)]
            if (citizenStandable(world, next) && citizenControlClear(city, next) && collisions.idleDestinationFree(actor, next.location(world)) &&
                routes.startRoute(actor, world, next, now, idleAnchor = next)) actor.patrolTarget = next
            else { actor.patrolAfter = now + 5_000; actor.patrolIndex++ }
        }
        runtime.statuses[worker.id] = if (actor.patrolTarget == null) "guarding" else "patrolling"
        return true
    }

    fun combat(actor: CitizenActor, city: Settlement, worker: CivWorker, world: World, now: Long): Boolean {
        val monster = actor.attacker ?: return false
        if (immortalResidents(city.id) || !monster.isValid || monster.isDead || monster.world != world || now > actor.combatUntil ||
            monster.location.distanceSquared(actor.npc.location) > 24.0 * 24.0) {
            routes.cancelRoute(actor)
            actor.attacker = null
            actor.combatUntil = 0
            actor.combatDestination = null
            actor.combatStatus = null
            actor.combatRouteAfter = 0
            actor.combatAttackAfter = 0
            actor.cycle = actor.plan.cycle()
            return false
        }
        val retreat = citizenRetreats(worker)
        actor.combatStatus = if (retreat) "retreating" else "defending"
        runtime.statuses[actor.workerId] = requireNotNull(actor.combatStatus)
        val at = actor.npc.location
        val foe = monster.location
        if (!retreat && at.distanceSquared(foe) <= 2.5 * 2.5 &&
            threats.visible(at.clone().add(0.0, actor.npc.eyeHeight, 0.0), monster.eyeLocation)) {
            routes.cancelRoute(actor)
            actor.combatDestination = null
            citizenLookDirection(at.clone().add(0.0, actor.npc.eyeHeight, 0.0), monster.eyeLocation)
                .let { (yaw, pitch) -> routes.look(actor.npc, yaw, pitch) }
            if (now >= actor.combatAttackAfter) {
                actor.combatAttackAfter = now + 1_000
                actor.npc.swingMainHand()
                monster.damage(citizenAttackDamage(CivsEquipment.slots(worker)[4]?.stack()))
            }
            return true
        }
        if (now < actor.combatRouteAfter || runtime.wanderBudget == 0) return true
        if (actor.route?.let { it.motion.navigating && !it.timedOut(now) } == true && actor.combatDestination?.let {
                retreat || it.point().distanceSquared(foe.point()) <= 3.0 * 3.0
            } == true) return true
        runtime.wanderBudget--
        actor.combatRouteAfter = now + 1_000
        routes.cancelRoute(actor)
        val direction = at.toVector().subtract(foe.toVector()).setY(0.0)
        if (direction.lengthSquared() < .01) direction.x = 1.0
        direction.normalize()
        val points = if (retreat) listOf(6.0, 4.0, 8.0).flatMap { distance ->
            listOf(0.0, .8, -.8).map { turn -> at.clone().add(direction.clone().rotateAroundY(turn).multiply(distance)) }
        } else listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).map { (x, z) -> foe.clone().add(x.toDouble(), 0.0, z.toDouble()) }
        val destination = points.asSequence().flatMap { point -> sequenceOf(0, 1, -1).map { dy ->
            CivPosition(point.blockX, point.blockY + dy, point.blockZ)
        } }.firstOrNull { point -> citizenStandable(world, point) && citizenControlClear(city, point) &&
            citizenPacketBodyClear(world, point.point(), false) && citizenPacketCampfireClear(world, point.point()) &&
            citizenRouteCellAllowed(city, citizenRouteBuildings(city, actor.plan, point,
                CivPosition(at.blockX, at.blockY, at.blockZ)), point.x, point.z) }
        if (destination != null) {
            actor.combatDestination = destination
            routes.startRoute(actor, world, destination, now)
        }
        return true
    }

    /** Every accepted hit freezes work until the canonical city write decides injury or permanent death. */
    internal fun damage(workerId: UUID, amount: Double) {
        val actor = runtime.actors[workerId] ?: return
        if (runtime.closed.get() || actor.workerId in runtime.pendingHarm) return
        val at = System.currentTimeMillis()
        if (at < actor.damageAfter) return
        val city = runtime.cityProvider().firstOrNull { it.id == actor.cityId } ?: return
        val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: return
        val damage = CivsResidents.damage(worker, actor.plan.type, amount)
        if (!damage.isFinite() || damage <= 0) return
        actor.damageAfter = at + 500
        runtime.pendingHarm += actor.workerId
        runCatching { life.stopMeal(actor); leisure.stopCamp(actor, "injury"); routes.cancelRoute(actor) }.onFailure { runtime.logFailure(actor, "injury-stop", it) }
        actor.cycle.close()
        actor.reportHarmCompletion = at >= actor.harmLogAfter
        if (actor.reportHarmCompletion) {
            actor.harmLogAfter = at + 5_000
            CivsDiagnostics.event("citizen_injury", "city" to actor.cityId, "worker" to actor.workerId,
                "state" to "pending", "damage" to damage)
        }
        val completionQueue = harmCompletions
        val epoch = harmEpoch
        val future = try { hurt(actor.cityId, actor.workerId, damage) }
            catch (error: Exception) { CompletableFuture.failedFuture(error) }
        future.whenComplete { saved, error ->
            val completion = HarmCompletion(actor.workerId, saved, error, epoch)
            if (!runtime.closed.get()) {
                completionQueue.add(completion)
                if (runtime.closed.get()) completionQueue.remove(completion)
            }
        }
    }

    fun drainHarm() {
        while (true) {
            val result = harmCompletions.poll() ?: return
            if (result.epoch != harmEpoch) continue
            val actor = runtime.actors[result.workerId]
            if (result.error != null) {
                // Keep this identity frozen until canonical recovery; never replay an unknown commit.
                if (actor != null) runtime.logFailure(actor, "injury", result.error)
                continue
            }
            runtime.pendingHarm -= result.workerId
            if (actor != null) {
                runCatching {
                    val worker = result.city?.workers?.firstOrNull { it.id == result.workerId }
                    if (worker == null) remove(actor, "dead")
                    else if (!actor.npc.isValid) remove(actor, "unavailable")
                    else {
                        actor.cycle = actor.plan.cycle()
                        equipment.syncResident(actor.npc, worker, actor.plan)
                        actor.npc.playHurtAnimation()
                        if (actor.reportHarmCompletion) CivsDiagnostics.event("citizen_injury", "city" to actor.cityId,
                            "worker" to actor.workerId, "state" to "committed", "health" to CivsResidents.health(worker))
                    }
                }.onFailure {
                    runtime.logFailure(actor, "injury-publication", it)
                    remove(actor, "unavailable") // The next actor comes from the committed health, never from a replayed hit.
                }
            }
        }
    }
}

internal data class ResidentAlarm(val monster: Monster, val until: Long)
