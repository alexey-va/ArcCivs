package ru.arc.civs

import org.bukkit.World
import java.util.UUID

internal data class CivMineVisitCause(val status: String, val reason: String, val point: CivPosition?, val material: String?)
internal data class CivMineVisitKey(val cityId: UUID, val workerId: UUID, val building: CivBuilding,
    val proof: CivMinePlanState, val cause: CivMineVisitCause)
internal data class CitizenMineAccessRequest(val buildingId: UUID, val cycle: Long, val ordinal: Long)

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
    private class Attempts(val building: CivBuilding, val proof: CivMinePlanState) {
        val causes = hashSetOf<CivMineVisitCause>()
        fun matches(key: CivMineVisitKey) = building == key.building && proof == key.proof
    }
    private val attempted = mutableMapOf<Pair<UUID, UUID>, Attempts>()
    private var requestOrdinal = 0L
    private var targetChecks = 8
    internal val retainedAttempts: Int get() = attempted.size

    fun retain(cities: Collection<Settlement>) {
        val current = cities.flatMap { city -> city.workers.map { city.id to it.id } }.toSet()
        attempted.keys.retainAll(current)
        val byId = cities.associateBy { it.id }
        for (actor in runtime.actors.values) {
            val city = byId[actor.cityId]
            if (city == null) { actor.mineAccessRequest = null; clearTurn(actor); continue }
            retainRequest(actor, city)
            reconcileAccess(actor, city)
        }
        targetChecks = 8 // Shared across every optional visit admitted on this work pulse.
    }
    fun clear() {
        attempted.clear()
        runtime.actors.values.forEach { it.mineAccessRequest = null; clearTurn(it) }
    }

    fun waitingForAccess(actor: CitizenActor, city: Settlement, worker: CivWorker, world: World, register: Boolean = true): Boolean {
        val mine = physicalMine(actor, city, worker) ?: pending(actor, city, worker, world)?.building ?: return false
        if (actor.mineVisit != null || actor.mineAccess?.id == mine.id ||
            underground(actor, mine) && !atSurface(actor, mine, mine)) return false
        return waitingTurn(actor, city, worker, mine, world, register)
    }

    private fun requestValid(actor: CitizenActor, city: Settlement, request: CitizenMineAccessRequest): Boolean {
        val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: return false
        return actor.cityId == city.id && actor.worldId == city.worldId && actor.npc.isValid && actor.npc.world.uid == city.worldId &&
            city.pendingDeletion == null && city.autonomy?.paused != true &&
            worker.assignment == request.buildingId && worker.completedCycles == request.cycle &&
            actor.plan.token.assignmentId == request.buildingId && actor.plan.token.completedCycles == request.cycle &&
            actor.plan.type == BuildingType.QUARRY && actor.cycle.phase != CitizenPhase.CLOSED &&
            city.building(request.buildingId)?.let { CivsRules.workplaceAvailable(city, it) } == true &&
            !actor.sheltering && actor.attacker == null && actor.foodSource == null && actor.mealVisit == null &&
            actor.social.visit == null && actor.collectionSeek == null && actor.collectionHome == null &&
            actor.outputReturn == null && actor.supplyPickup == null && actor.plan.courier == null &&
            actor.workerId !in runtime.pendingHarm &&
            (physicalMine(actor, city, worker)?.id == request.buildingId ||
                pending(actor, city, worker, actor.npc.world)?.building?.id == request.buildingId)
    }

    private fun retainRequest(actor: CitizenActor, city: Settlement) {
        actor.mineAccessRequest?.takeUnless { requestValid(actor, city, it) }?.let { actor.mineAccessRequest = null }
        val worker = city.workers.firstOrNull { it.id == actor.workerId }
        val heldMine = actor.mineAccess?.id ?: actor.mineVisit?.key?.building?.id
        if (!actor.npc.isValid || actor.worldId != city.worldId || actor.npc.world.uid != city.worldId ||
            city.pendingDeletion != null || worker == null || heldMine != null &&
            (worker.assignment != heldMine || actor.plan.token.assignmentId != heldMine ||
                actor.plan.type != BuildingType.QUARRY || actor.cycle.phase == CitizenPhase.CLOSED)) {
            // Cancellation clears turn metadata, never the resident's physical underground presence.
            clearTurn(actor)
            return
        }
        actor.mineYieldPin?.let { pin ->
            if (worker.assignment != pin.assignmentId || worker.completedCycles != pin.completedCycles ||
                pin.assignmentId != actor.plan.token.assignmentId || pin.completedCycles != actor.plan.token.completedCycles)
                clearTurn(actor)
        }
    }

    private fun enqueue(actor: CitizenActor, worker: CivWorker, mine: CivBuilding) {
        if (actor.mineAccessRequest?.let { it.buildingId == mine.id && it.cycle == worker.completedCycles } != true)
            actor.mineAccessRequest = CitizenMineAccessRequest(mine.id, worker.completedCycles, requestOrdinal++)
    }

    private fun hasContenders(actor: CitizenActor, city: Settlement, mineId: UUID): Boolean =
        runtime.actors.values.any { other -> other !== actor && other.mineAccessRequest?.let {
            it.buildingId == mineId && requestValid(other, city, it)
        } == true }

    private fun waitingTurn(actor: CitizenActor, city: Settlement, worker: CivWorker, mine: CivBuilding, world: World,
        register: Boolean = true): Boolean {
        if (register) retainRequest(actor, city)
        val first = runtime.actors.values.filter { other -> other.mineAccessRequest?.let {
            it.buildingId == mine.id && requestValid(other, city, it)
        } == true }.minWithOrNull(compareBy<CitizenActor> { it.mineAccessRequest!!.ordinal }.thenBy { it.workerId })
        if (!occupied(actor, city, mine.id, world) && (first == null || first === actor)) return false
        if (!register) return true
        enqueue(actor, worker, mine)
        for (owner in runtime.actors.values) if (owner !== actor && owner.cityId == city.id && owner.worldId == world.uid &&
            owner.npc.isValid && owner.npc.world.uid == world.uid && owner.plan.token.assignmentId == mine.id &&
            owner.plan.type == BuildingType.QUARRY && owner.cycle.phase != CitizenPhase.CLOSED &&
            city.workers.any { it.id == owner.workerId && it.assignment == mine.id } &&
            (owner.mineAccess?.id == mine.id || owner.mineVisit?.key?.building?.id == mine.id))
            if (owner.mineContendedAt == null) owner.mineContendedAt = runtime.residentObservedAt
        return true
    }

    private fun turnExpired(actor: CitizenActor, city: Settlement, mineId: UUID, now: Long): Boolean {
        if (!hasContenders(actor, city, mineId)) {
            if (actor.mineYieldPin == null) actor.mineContendedAt = null
            return actor.mineYieldPin != null
        }
        val began = actor.mineContendedAt ?: now.also { actor.mineContendedAt = it }
        return now >= began && now - began >= CONTENDED_TURN_MILLIS
    }

    private fun clearTurn(actor: CitizenActor) {
        actor.mineYieldPin?.let { if (actor.workDetour == it) actor.workDetour = null }
        actor.mineYieldPin = null
        actor.mineContendedAt = null
    }

    /** A turn ends only between receipts. Physical presence remains reserved throughout the ordinary walk out. */
    fun yieldForAccess(actor: CitizenActor, city: Settlement, worker: CivWorker, world: World, now: Long): Boolean {
        val mine = actor.mineAccess ?: return false
        if (actor.mineVisit != null || worker.assignment != mine.id || actor.plan.token.assignmentId != mine.id ||
            actor.plan.token.completedCycles != worker.completedCycles ||
            actor.plan.type != BuildingType.QUARRY || !turnExpired(actor, city, mine.id, now)) return false
        if (actor.supplyPickup != null || actor.workerId in runtime.pendingHarm ||
            actor.cycle.phase !in setOf(CitizenPhase.IDLE, CitizenPhase.TO_STORAGE, CitizenPhase.TO_WORK, CitizenPhase.PAUSED) ||
            actor.cycle.observedWorkMillis != 0L || actor.plan.pendingStockDelivery != null ||
            actor.workDetour != null && actor.mineYieldPin == null) return false
        if (actor.mineYieldPin == null) {
            enqueue(actor, worker, mine)
            // Reuse the existing same-token detour pin; target/proof churn must not cancel this safe egress.
            actor.mineYieldPin = actor.plan.token
            actor.workDetour = actor.plan.token
            routes.cancelRoute(actor)
        }
        actor.cycle.deferWork(now)
        val target = (city.building(mine.id) ?: mine).let { it.control ?: it.entrance }
        if (actor.route?.let { it.motion.failure != null || it.completion != null || it.timedOut(now) } == true) {
            val retry = actor.route!!.preparationRetryAt ?: (now + 5_000).also { actor.route!!.preparationRetryAt = it }
            if (now >= retry) routes.cancelRoute(actor)
        }
        if (actor.route == null) routes.startRoute(actor, world, target, now)
        runtime.statuses[actor.workerId] = "moving-to-storage"
        return true
    }

    private fun occupied(actor: CitizenActor, city: Settlement, mineId: UUID, world: World): Boolean = runtime.actors.values.any { other ->
        other !== actor && other.cityId == city.id && other.worldId == world.uid && other.npc.isValid &&
            (other.mineVisit?.key?.let { it.cityId == city.id && it.building.id == mineId } == true || other.mineAccess?.id == mineId ||
                other.route?.let { inbound(other, city, it.authored, it.idleAnchor) }?.id == mineId)
    }

    private fun physicalMine(actor: CitizenActor, city: Settlement, worker: CivWorker): CivBuilding? {
        if (actor.cycle.phase in setOf(CitizenPhase.CLOSED, CitizenPhase.DELIVERING, CitizenPhase.PICKING_UP) ||
            actor.plan.type != BuildingType.QUARRY || actor.plan.idleReason != null || actor.plan.courier != null ||
            actor.sheltering || actor.returningToWarehouse || actor.attacker != null || actor.foodSource != null ||
            actor.mealVisit != null || actor.social.visit != null || actor.collectionSeek != null ||
            actor.collectionHome != null || actor.outputReturn != null || actor.supplyPickup != null) return null
        val work = actor.plan.worldWork?.takeIf { it.miningStep != null && it.workerId == worker.id &&
            it.cycle == worker.completedCycles && it.buildingId == worker.assignment &&
            it.buildingId == actor.plan.token.assignmentId } ?: return null
        return city.building(work.buildingId)?.takeIf { it.type == BuildingType.QUARRY &&
            work.standing.y < it.entrance.y - .5 }
    }

    fun inbound(actor: CitizenActor, city: Settlement, target: CivPosition, idleAnchor: CivPosition?): CivBuilding? {
        if (idleAnchor != null || target != actor.plan.worldWork?.standing) return null
        val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: return null
        return physicalMine(actor, city, worker)
    }

    fun bind(actor: CitizenActor, city: Settlement, route: CitizenRoute) {
        val mine = inbound(actor, city, route.authored, route.idleAnchor) ?: return
        reconcileAccess(actor, city)
        if (actor.mineAccess == null) {
            actor.mineAccess = mine
            actor.mineAccessRequest = null
            actor.mineContendedAt = null
        }
    }

    /** A cancelled route or completed action does not move an underground resident out of its shaft. */
    private fun reconcileAccess(actor: CitizenActor, city: Settlement) {
        if (!actor.npc.isValid || actor.worldId != city.worldId || actor.npc.world.uid != city.worldId || actor.mineVisit != null) return
        val saved = actor.mineAccess
        val inbound = actor.route?.let { inbound(actor, city, it.authored, it.idleAnchor) }
        if (saved != null) {
            if (inbound?.id != saved.id && atSurface(actor, saved, city.building(saved.id))) {
                actor.mineAccess = inbound
                clearTurn(actor)
            }
            return
        }
        if (inbound != null) { actor.mineAccess = inbound; return }
        // Recovery only: normal physical entry binds the admitted route before its first native move.
        val mine = city.workers.firstOrNull { it.id == actor.workerId }?.assignment?.let(city::building)
            ?.takeIf { it.type == BuildingType.QUARRY } ?: return
        if (underground(actor, mine) && !atSurface(actor, mine, mine)) actor.mineAccess = mine
    }

    private fun underground(actor: CitizenActor, mine: CivBuilding): Boolean = actor.npc.location.let { at ->
        at.y < mine.entrance.y - .5 && at.blockX in mine.bounds.min.x..mine.bounds.max.x && at.blockZ in mine.bounds.min.z..mine.bounds.max.z
    }

    private fun atSurface(actor: CitizenActor, saved: CivBuilding, current: CivBuilding?): Boolean {
        val world = actor.npc.world
        val at = actor.npc.location
        val point = at.point()
        val controls = listOfNotNull(current?.control, saved.control).distinct()
        val barrels = controls.filter { citizenAtStorage(world, point, it) }
        val outside = point.y >= (current ?: saved).entrance.y - .5 && listOfNotNull(saved, current).all {
            at.blockX !in it.bounds.min.x..it.bounds.max.x || at.blockZ !in it.bounds.min.z..it.bounds.max.z
        }
        if (barrels.isEmpty() && !outside || !citizenPacketSupported(world, point) || !citizenPacketBodyClear(world, point, false)) return false
        return outside || barrels.any { citizenCanReachBlock(actor.npc, it) }
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
        val previous = attempted[identity]
        val current = previous?.takeIf { it.matches(key) }
        // Diagnostic churn must not reopen old attempts; saturation keeps them closed until paid topology changes.
        return key.takeUnless { current != null && (it.cause in current.causes || current.causes.size >= MAXIMUM_CAUSES) ||
            previous == null && attempted.size >= maximumAttempts }
    }

    fun reconcile(actor: CitizenActor, city: Settlement) {
        retainRequest(actor, city)
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
            if (actor.mineAccess?.id != key.building.id && waitingTurn(actor, city, worker, key.building, world)) {
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
            val identity = city.id to worker.id
            val history = attempted[identity]?.takeIf { it.matches(key) }
                ?: Attempts(key.building, key.proof).also { attempted[identity] = it }
            history.causes += key.cause
            actor.mineAccessRequest = null
            actor.mineContendedAt = null
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
        if (!visit.returning && turnExpired(actor, city, visit.key.building.id, now))
            returnToStorage(actor, visit, "contended-turn-ended", now)
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
            clearTurn(actor)
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
        private const val CONTENDED_TURN_MILLIS = 30_000L
        private const val MAXIMUM_CAUSES = 32

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
