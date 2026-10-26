package ru.arc.civs

import ru.arc.core.LifecycleTaskScope
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/** Detached city records and validated retained routes; no native world or storage authority. */
data class CivPlanBoardSnapshot(
    val cityId: UUID,
    val name: String,
    val layoutKey: String,
    val worldId: UUID,
    val center: CivPosition,
    val radius: Int,
    val buildings: List<CivBuilding>,
    val plannedBuildings: List<CivBuilding>,
    val routes: Map<UUID, List<CivPosition>>,
    val forecast: CivPlanBoardForecast? = null,
)

/** Candidate reservations only: no terrain approval, construction order, deadline or material grant. */
data class CivPlanBoardForecast(val buildings: List<CivBuilding>, val streets: List<CivBox>,
    val perimeter: List<CivBox>, val unavailable: Boolean = false)

/** Geometry identity deliberately excludes stock, residents and construction work cursors. */
internal fun civsPlanBoardLayoutKey(city: Settlement): String {
    require(city.buildings.size <= CivsRules.MAX_SITES)
    val digest = MessageDigest.getInstance("SHA-256")
    fun field(value: Any?) {
        val bytes = value?.toString()?.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes?.size ?: -1).array())
        if (bytes != null) digest.update(bytes)
    }
    fun building(value: CivBuilding) {
        field(value.id); field(value.type); field(value.level); field(value.anchor); field(value.rotation)
        field(value.schematicId); field(value.bounds); field(value.entrance); field(value.phase)
        field(value.control); field(value.production); field(value.transit.orEmpty().size)
        value.transit.orEmpty().forEach(::field)
    }
    field(city.id); field(city.name); field(city.worldId); field(city.center); field(city.radius)
    field(city.style); field(city.autonomy != null); field(city.autonomy?.layoutHalfWidth)
    city.research?.completed.orEmpty().sortedBy { it.ordinal }.forEach(::field)
    field(city.buildings.size)
    city.buildings.forEach(::building)
    field(city.construction?.plannedBuilding != null)
    city.construction?.plannedBuilding?.let(::building)
    field(city.constructionQueue.orEmpty().size)
    city.constructionQueue.orEmpty().forEach(::field)
    field(city.roadRouteOverrides.orEmpty().size)
    city.roadRouteOverrides.orEmpty().toSortedMap().forEach { (id, route) ->
        field(id); field(route.originalRoute.size); route.originalRoute.forEach(::field)
        field(route.route.size); route.route.forEach(::field)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun CivBuilding.boardCopy(): CivBuilding = copy(transit = transit?.toList())

internal fun queuedUpgradeId(buildingId: UUID, targetLevel: Int): UUID =
    UUID.nameUUIDFromBytes("arccivs-upgrade/$buildingId/$targetLevel".toByteArray(Charsets.UTF_8))

internal fun queuedDesignId(building: CivBuilding): UUID = if (building.phase == BuildingPhase.SITE) building.id
    else queuedUpgradeId(building.id, building.level + 1)

/** The existing queue's captured-design identity, shared by activation and the detached board view. */
internal fun requireQueuedDesign(plan: CivPlacementPlan, city: Settlement, building: CivBuilding) {
    require(plan.id == queuedDesignId(building) && plan.cityId == city.id && plan.worldId == city.worldId)
    require(if (building.phase == BuildingPhase.SITE)
        plan.building.copy(phase = BuildingPhase.SITE, production = building.production) == building
    else building.phase == BuildingPhase.ACTIVE && plan.building.id == building.id && plan.building.type == building.type &&
        plan.building.phase == BuildingPhase.CONSTRUCTING && plan.building.level == building.level + 1 &&
        plan.building.entrance == building.entrance && plan.building.rotation == building.rotation &&
        civsContainsBox(plan.building.bounds, building.bounds)) { "Queued design no longer matches building ${building.id}" }
}

/** Called on Main before any asynchronous plan read, including caller-owned list detachment. */
internal fun civsPlanBoardCapture(city: Settlement): Settlement = city.copy(
    buildings = city.buildings.map { it.boardCopy() },
    construction = city.construction?.let { it.copy(plannedBuilding = it.plannedBuilding?.boardCopy()) },
    constructionQueue = city.constructionQueue?.toList(),
    research = city.research?.copy(completed = city.research.completed.toSet(), project = null),
    roadRouteOverrides = city.roadRouteOverrides?.mapValues { (_, value) ->
        value.copy(originalRoute = value.originalRoute.toList(), route = value.route.toList())
    },
    workers = emptyList(),
)

internal fun civsPlanBoardSnapshot(city: Settlement, saved: List<CivPlacementPlan>): CivPlanBoardSnapshot {
    val planned = linkedMapOf<UUID, CivBuilding>()
    city.buildings.filter { it.phase != BuildingPhase.ACTIVE }.forEach { planned[it.id] = it.boardCopy() }
    val plans = saved.associateBy(CivPlacementPlan::id)
    city.constructionQueue.orEmpty().mapNotNull(city::building).filter { it.phase == BuildingPhase.ACTIVE }.forEach { building ->
        val plan = plans[queuedDesignId(building)] ?: throw CivsRejection("plan-board-queued-plan-unavailable")
        if (runCatching { requireQueuedDesign(plan, city, building) }.isFailure)
            throw CivsRejection("plan-board-queued-plan-mismatch")
        planned[building.id] = plan.building.boardCopy()
    }
    city.construction?.plannedBuilding?.let { planned[it.id] = it.boardCopy() }
    return CivPlanBoardSnapshot(city.id, city.name, civsPlanBoardLayoutKey(city), city.worldId, city.center, city.radius,
        city.buildings.filter { it.phase == BuildingPhase.ACTIVE }.map { it.boardCopy() }, planned.values.toList(),
        CivAutonomousAccess.from(city, saved.filter { it.id == it.building.id }).routes.mapValues { (_, route) -> route.toList() })
}

/** Replays the existing goal order and placement candidates on detached copies, never the live planner. */
internal fun civsPlanBoardForecast(city: Settlement, snapshot: CivPlanBoardSnapshot, executor: Executor,
    template: (Settlement, BuildingType, Int, UUID) -> CompletableFuture<CivBuilding>): CompletableFuture<CivPlanBoardForecast?> {
    val autonomy = city.autonomy ?: return CompletableFuture.completedFuture(null)
    val accepted = snapshot.plannedBuildings.associateBy { it.id }
    var projected = city.copy(autonomy = autonomy.copy(paused = false), construction = null, constructionQueue = emptyList(),
        buildings = (city.buildings.map { accepted[it.id] ?: it } + accepted.values.filter { city.building(it.id) == null })
            .map { it.copy(phase = BuildingPhase.ACTIVE) })
    val frame = CivsSettlementLayout.frame(projected)
    val streets = frame.streets.map { street ->
        val dx = if (street.from.x == street.to.x) 1 else 0
        val dz = if (street.from.z == street.to.z) 1 else 0
        CivBox(CivPosition(minOf(street.from.x, street.to.x) - dx, city.center.y, minOf(street.from.z, street.to.z) - dz),
            CivPosition(maxOf(street.from.x, street.to.x) + dx, city.center.y, maxOf(street.from.z, street.to.z) + dz))
    }
    val buildings = mutableListOf<CivBuilding>()
    val access = CivAutonomousAccess(snapshot.routes)
    fun result(unavailable: Boolean = false) = CivPlanBoardForecast(buildings.toList(), streets, frame.wallObstacles.toList(), unavailable)
    fun next(): CompletableFuture<CivPlanBoardForecast?> {
        val goal = CivsAutonomy.nextGoal(projected)
        // ponytail: eight new-site reservations, stopping at the first upgrade or unavailable site.
        // Later revisions use the same planner again; this is not a second long-term simulation.
        if (buildings.size == 8 || CivsRules.buildingCount(projected) >= CivsRules.MAX_BUILDINGS ||
            goal == null || goal.buildingId != null || goal.targetLevel != 1)
            return CompletableFuture.completedFuture(result())
        val id = UUID.nameUUIDFromBytes("arccivs-board-forecast/${city.id}/${buildings.size}/${goal.type}".toByteArray(Charsets.UTF_8))
        val current = template(projected, goal.type, 1, id)
        val future = if (goal.type.maxLevel == 1) current else template(projected, goal.type, goal.type.maxLevel, id)
        return current.thenCombineAsync(future, { a, b ->
            CivsSettlementLayout.candidates(projected, a, b, access, frame).firstOrNull()
                ?: CivsSettlementLayout.infill(projected, a, b, access, frame).firstOrNull()
        }, executor).thenCompose { candidate ->
            if (candidate == null) CompletableFuture.completedFuture(result(unavailable = true))
            else {
                val reserved = candidate.building.copy(phase = BuildingPhase.ACTIVE)
                buildings += reserved
                projected = projected.copy(buildings = projected.buildings + reserved)
                next()
            }
        }
    }
    return runCatching { next() }.getOrElse { CompletableFuture.failedFuture(it) }
        .exceptionally { result(unavailable = true) }
}

/** Main captures and publishes; the existing plan loader and executor own all file/pure layout work. */
internal class CivsPlanBoardSnapshots(
    private val tasks: LifecycleTaskScope,
    private val executor: Executor,
    private val currentCity: (UUID) -> Settlement?,
    private val loadPlans: (Settlement) -> CompletableFuture<List<CivPlacementPlan>>,
    private val template: ((Settlement, BuildingType, Int, UUID) -> CompletableFuture<CivBuilding>)? = null,
) : AutoCloseable {
    private class Request(val cityId: UUID, val key: String) {
        val result = CompletableFuture<CivPlanBoardSnapshot>()
        var completion: CompletableFuture<*> = CompletableFuture.completedFuture(Unit)
    }
    private val monitor = Any()
    private val cache = mutableMapOf<UUID, CivPlanBoardSnapshot>()
    private val pending = mutableMapOf<UUID, Request>()
    private val accepted = mutableSetOf<Request>()
    private var closed = false

    /** Must be called on Main, as must currentCity. Failed or stale requests are safe to retry. */
    fun snapshot(cityId: UUID): CompletableFuture<CivPlanBoardSnapshot> {
        val city = currentCity(cityId)?.takeIf { it.pendingDeletion == null }
            ?: return CompletableFuture.failedFuture(CivsRejection("no-city"))
        val captured = civsPlanBoardCapture(city)
        val key = civsPlanBoardLayoutKey(captured)
        val request = synchronized(monitor) {
            if (closed) return CompletableFuture.failedFuture(CivsRejection("not-ready"))
            cache[cityId]?.takeIf { it.layoutKey == key }?.let { return CompletableFuture.completedFuture(it) }
            pending[cityId]?.takeIf { it.key == key }?.let { return it.result }
            if (accepted.size >= MAX_PENDING) return CompletableFuture.failedFuture(CivsRejection("plan-board-busy"))
            pending.remove(cityId)?.result?.completeExceptionally(CivsRejection("plan-board-stale"))
            Request(cityId, key).also { pending[cityId] = it; accepted += it }
        }
        val token = runCatching { tasks.token() }.getOrElse { failure ->
            finish(request, null, failure)
            return request.result
        }
        request.completion = runCatching { loadPlans(captured) }.getOrElse { CompletableFuture.failedFuture(it) }
            .thenApplyAsync({ saved -> civsPlanBoardSnapshot(captured, saved) }, executor)
            .thenCompose { snapshot ->
                template?.let { loader -> civsPlanBoardForecast(captured, snapshot, executor, loader)
                    .thenApply { snapshot.copy(forecast = it) } } ?: CompletableFuture.completedFuture(snapshot)
            }
            .thenCompose { snapshot ->
                tasks.callSync(token) {
                    synchronized(monitor) {
                        val latest = currentCity(cityId)
                        if (closed || pending[cityId] !== request || latest == null || latest.pendingDeletion != null ||
                            civsPlanBoardLayoutKey(latest) != key) throw CivsRejection("plan-board-stale")
                        cache[cityId] = snapshot
                    }
                    snapshot
                }
            }
        request.completion.whenComplete { value, failure ->
            finish(request, value as? CivPlanBoardSnapshot, failure)
        }
        return request.result
    }

    /** Main reload/deletion fences even a replacement city with identical geometry and UUID. */
    fun retain(cityIds: Set<UUID>) = synchronized(monitor) {
        cache.keys.retainAll(cityIds)
        pending.values.filter { it.cityId !in cityIds }.forEach {
            pending.remove(it.cityId, it)
            it.result.completeExceptionally(CivsRejection("plan-board-stale"))
        }
    }

    private fun finish(request: Request, snapshot: CivPlanBoardSnapshot?, failure: Throwable?) {
        synchronized(monitor) { pending.remove(request.cityId, request); accepted -= request }
        if (failure == null) request.result.complete(requireNotNull(snapshot))
        else request.result.completeExceptionally(failure)
    }

    override fun close() {
        synchronized(monitor) {
            if (closed) return
            closed = true
            cache.clear()
            pending.values.forEach { it.result.completeExceptionally(CivsRejection("not-ready")) }
            pending.clear()
        }
    }

    /** Includes plan reads and scheduled publication, even after caller futures were failed on close. */
    fun drained(): CompletableFuture<Void> = synchronized(monitor) {
        CompletableFuture.allOf(*accepted.map { it.completion.handle { _, _ -> Unit } }.toTypedArray())
    }

    companion object { private const val MAX_PENDING = 2 }
}
