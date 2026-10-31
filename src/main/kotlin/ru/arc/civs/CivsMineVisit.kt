package ru.arc.civs

import org.bukkit.World
import java.util.UUID

internal data class CivMineVisitCause(val status: String, val reason: String, val point: CivPosition?, val material: String?)
internal data class CivMineVisitKey(val cityId: UUID, val workerId: UUID, val building: CivBuilding,
    val proof: CivMinePlanState, val cause: CivMineVisitCause)

/** A native walk, never a work action, receipt, mining cursor or delivery. */
internal class CitizenMineVisit(val key: CivMineVisitKey, val storageStanding: CivPosition,
    val candidates: List<CivPosition>, var passage: CitizenMinePassage, val startedAt: Long) {
    var target: CivPosition? = null
    var cursor = 0
    var returning = false
    var routeBuilding: CivBuilding? = key.building
    var routeProof: CivMinePlanState? = key.proof
    var planRetired = false
    var failure: String? = null
    var retryAt = 0L
    val goal: CivPosition? get() = if (returning) storageStanding else target
    fun owns(point: CivPosition?): Boolean = point != null && point == goal
}

/** Owner lifetime outlasts actor removal/recreation; at most one attempt per unchanged proof/cause. */
internal class CivsMineVisits(private val runtime: CitizenRuntime, private val maximumAttempts: Int = 4_096,
    private val routesOwner: () -> CivsCitizenRoutes) {
    private val routes get() = routesOwner()
    private val attempted = mutableMapOf<Pair<UUID, UUID>, CivMineVisitKey>()
    private var targetChecks = 8
    internal val retainedAttempts: Int get() = attempted.size

    fun retain(cities: Collection<Settlement>) {
        val current = cities.flatMap { city -> city.workers.map { city.id to it.id } }.toSet()
        attempted.keys.retainAll(current)
        targetChecks = 8 // Shared across every optional visit admitted on this work pulse.
    }
    fun clear() = attempted.clear()

    fun waitingForAccess(actor: CitizenActor, city: Settlement, worker: CivWorker, world: World): Boolean =
        pending(actor, city, worker, world)?.let { occupied(actor, it, world) } == true

    private fun occupied(actor: CitizenActor, key: CivMineVisitKey, world: World): Boolean = runtime.actors.values.any { other ->
        other !== actor && other.cityId == key.cityId && other.worldId == world.uid && other.npc.isValid &&
            other.mineVisit?.key?.let { it.cityId == key.cityId && it.building.id == key.building.id } == true
    }

    private fun pending(actor: CitizenActor, city: Settlement, worker: CivWorker, world: World): CivMineVisitKey? {
        if (actor.mineVisit != null || actor.cycle.phase != CitizenPhase.IDLE || actor.cycle.observedWorkMillis != 0L ||
            actor.plan.idleReason != null || actor.plan.courier != null || !citizenHasCapacity(city, actor.plan)) return null
        val work = actor.plan.worldWork?.takeIf { CivsMineFallback.matches(it) &&
            it.workerId == worker.id && it.cycle == worker.completedCycles } ?: return null
        val mine = city.building(work.buildingId)?.takeIf { CivsMineFallback.eligible(city, it, worker) } ?: return null
        val control = mine.control ?: return null
        if (!citizenStorageReady(world, control)) return null
        val cause = CivsMineFallback.cause(work) ?: return null
        val key = CivMineVisitKey(city.id, worker.id, mine, proof(city, mine), cause)
        val identity = city.id to worker.id
        return key.takeUnless { attempted[identity] == it || identity !in attempted && attempted.size >= maximumAttempts }
    }

    fun reconcile(actor: CitizenActor, city: Settlement) {
        val visit = actor.mineVisit ?: return
        val mine = city.building(visit.key.building.id)
        val proof = mine?.let { proof(city, it) }
        val work = actor.plan.worldWork
        val cause = work?.let(CivsMineFallback::cause)
        val changedProof = mine != visit.routeBuilding || proof != visit.routeProof
        val changedPlan = !visit.planRetired && (actor.plan.token.assignmentId != mine?.id ||
            work?.let(CivsMineFallback::matches) != true || cause != visit.key.cause)
        if (changedProof || changedPlan) {
            // Changed proof retires even an in-flight return search; only current proof may admit the next one.
            visit.routeBuilding = mine
            visit.routeProof = proof
            visit.planRetired = visit.planRetired || changedPlan
            visit.passage = mine?.let { passage(it, requireNotNull(proof)) } ?: CitizenMinePassage({ false }, { false })
            routes.cancelRoute(actor)
            returnToStorage(actor, visit, "proof-or-plan-changed", runtime.residentObservedAt)
        }
    }

    fun update(actor: CitizenActor, city: Settlement, worker: CivWorker, world: World, now: Long): Boolean {
        if (actor.mineVisit == null) {
            val key = pending(actor, city, worker, world) ?: return false
            if (occupied(actor, key, world)) {
                actor.cycle.deferWork(now)
                runtime.statuses[worker.id] = "mine-waiting"
                return true
            }
            val work = requireNotNull(actor.plan.worldWork)
            val mine = key.building
            // A waiting miner returns from leisure before its first physical visit, without starting work.
            if (actor.route?.idleAnchor != null) routes.cancelRoute(actor)
            if (!routes.arrivedAt(actor, city, world, work.standing)) {
                actor.cycle.deferWork(now)
                actor.route?.takeIf { it.motion.failure != null || it.completion != null || it.timedOut(now) }?.let { route ->
                    val retryAt = route.preparationRetryAt ?: (now + 5_000).also { route.preparationRetryAt = it }
                    if (now >= retryAt) routes.cancelRoute(actor)
                }
                if (actor.route == null) routes.startRoute(actor, world, work.standing, now)
                runtime.statuses[worker.id] = if (actor.route?.motion?.failure != null) "route-unreachable" else "moving-to-storage"
                return true
            }
            attempted[city.id to worker.id] = key
            val allowed = passage(mine, key.proof)
            val candidates = candidates(mine, key.proof).filter {
                it.y <= work.standing.y - 2 && it.y <= actor.npc.location.y - 2 && allowed.completed(it.point())
            }
            val visit = CitizenMineVisit(key, work.standing, candidates, allowed, now)
            actor.mineVisit = visit
            routes.cancelRoute(actor)
            report(actor, visit, "admitted", key.cause.reason, now)
        }
        val visit = requireNotNull(actor.mineVisit)
        actor.cycle.deferWork(now)
        if (actor.workDetour != null && !visit.returning) returnToStorage(actor, visit, "life-interrupted", now)
        if (visit.target == null && !visit.returning) {
            // Bound native probes independently of the number of completed levels/branches.
            while (targetChecks > 0) {
                val candidate = visit.candidates.getOrNull(visit.cursor++) ?: break
                targetChecks--
                if (safe(city, world, visit, candidate)) {
                    visit.target = candidate
                    break
                }
            }
            if (visit.target == null) {
                if (visit.cursor >= visit.candidates.size) {
                    report(actor, visit, "unavailable", "no-native-safe-paid-standing", now)
                    actor.mineVisit = null
                }
                runtime.statuses[worker.id] = "searching-work"
                return true
            }
            report(actor, visit, "descending", visit.key.cause.reason, now)
        }
        val goal = requireNotNull(visit.goal)
        if (!visit.returning && !safe(city, world, visit, goal))
            returnToStorage(actor, visit, "native-standing-changed", now)
        val control = visit.key.building.control
        if (visit.returning && control != null && citizenStorageReady(world, control) &&
            citizenAtStorage(world, actor.npc.location.point(), control) && citizenCanReachBlock(actor.npc, control) &&
            citizenAtSupportedEndpoint(world, actor.npc.location.point(), visit.storageStanding) &&
            citizenPacketBodyClear(world, actor.npc.location.point(), false)) {
            report(actor, visit, "returned", visit.failure ?: visit.key.cause.reason, now)
            routes.cancelRoute(actor)
            actor.mineVisit = null
            return true // The economic cycle can only start on a later pulse, at the real barrel.
        }
        if (!visit.returning && routes.arrivedAt(actor, city, world, goal)) {
            report(actor, visit, "reached", visit.key.cause.reason, now)
            returnToStorage(actor, visit, "visit-complete", now)
            return true
        }
        val route = actor.route
        val failure = visit.failure?.takeUnless { visit.returning } ?: route?.let {
            it.motion.failure ?: it.completion?.let { completion -> completion.cancelReason ?: "completed-outside-visit-arrival" }
                ?: "visit-timeout".takeIf { _ -> it.timedOut(now) }
        }
        if (failure != null) {
            if (!visit.returning) returnToStorage(actor, visit, failure, now)
            else {
                report(actor, visit, "return-blocked", failure, now)
                routes.cancelRoute(actor)
                visit.retryAt = now + 5_000
            }
        }
        val destination = requireNotNull(visit.goal)
        if (actor.route == null && now >= visit.retryAt) {
            visit.failure = null
            if (!routes.startRoute(actor, world, destination, now)) {
                val reason = visit.failure ?: "visit-route-unavailable"
                if (!visit.returning) returnToStorage(actor, visit, reason, now)
                else {
                    report(actor, visit, "return-blocked", reason, now)
                    visit.retryAt = now + 5_000
                }
            }
        }
        runtime.statuses[worker.id] = when {
            visit.retryAt > now -> "route-unreachable"
            visit.returning -> "mine-returning"
            else -> "mine-descending"
        }
        return true
    }

    private fun returnToStorage(actor: CitizenActor, visit: CitizenMineVisit, reason: String, now: Long) {
        if (visit.returning) return
        report(actor, visit, "returning", reason, now)
        routes.cancelRoute(actor)
        visit.returning = true
        runtime.statuses[actor.workerId] = "mine-returning"
        visit.failure = reason
        visit.retryAt = 0
    }

    private fun report(actor: CitizenActor, visit: CitizenMineVisit, state: String, reason: String, now: Long) =
        CivsDiagnostics.event("citizen_mine_visit", "city" to actor.cityId, "worker" to actor.workerId,
            "building" to visit.key.building.id, "state" to state, "reason" to reason,
            "unavailable_status" to visit.key.cause.status, "unavailable_reason" to visit.key.cause.reason,
            "unavailable_point" to visit.key.cause.point, "unavailable_material" to visit.key.cause.material,
            "actual" to actor.npc.location.point().coordinates(), "visit_target" to visit.target,
            "return_target" to visit.storageStanding, "plan_version" to visit.key.proof.version,
            "mining_step" to visit.key.proof.nextStep, "elapsed_ms" to (now - visit.startedAt).coerceAtLeast(0))

    companion object {
        private fun proof(city: Settlement, mine: CivBuilding): CivMinePlanState = city.minePlans?.get(mine.id.toString())
            ?.let(CivsMineProgress::detached) ?: CivMinePlanState(CivsMinePlan.VERSION, 0,
                legacyStep = city.mineProgress?.get(mine.id.toString()))

        private fun passage(mine: CivBuilding, proof: CivMinePlanState): CitizenMinePassage = citizenMineRoutePassage(
            mine, proof.takeIf { it.version == CivsMinePlan.VERSION }?.nextStep ?: 0, proof)

        internal fun safe(city: Settlement, world: World, visit: CitizenMineVisit, point: CivPosition): Boolean =
            visit.passage.completed(point.point()) && citizenRoutePointAllowed(city, setOf(visit.key.building.id), point, visit.key.building) &&
                citizenControlClear(city, point) && citizenStandable(world, point) &&
                citizenPacketBodyClear(world, point.point(), false) && citizenPacketSupported(world, point.point())

        /** Authored ladder columns only. Native caves and future unpaid cells are never candidates. */
        internal fun candidates(mine: CivBuilding, proof: CivMinePlanState): List<CivPosition> = buildSet {
            for (building in listOf(mine) + proof.levels.orEmpty().map { it.building }) {
                for (version in 2..CivsMinePlan.VERSION) {
                    val geometry = CivsMinePlan.forBuilding(building, version) ?: continue
                    for (column in setOf(geometry.bottom, geometry.extensionLadder))
                        for (y in geometry.lowest..geometry.bottom.y)
                            add(CivsWorksiteLayouts.point(building, column.copy(y = y)))
                }
            }
        }.sortedWith(compareBy<CivPosition> { it.y }.thenBy { it.x }.thenBy { it.z })
    }
}
