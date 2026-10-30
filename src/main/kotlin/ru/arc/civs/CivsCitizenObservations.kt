package ru.arc.civs

import org.bukkit.Bukkit
import ru.arc.paper.api.ArcSceneBounds
import ru.arc.paper.api.ArcSceneEntityInfo
import java.util.UUID

/** Read-only resident status, scene observation and bounded diagnostics. */
internal class CivsCitizenObservations(
    private val runtime: CitizenRuntime,
    private val workOwner: () -> CivsCitizenWork,
    private val logisticsOwner: () -> CivsCitizenLogistics,
    private val plansOwner: () -> CivsCitizenPlanning,
    private val audienceOwner: () -> CivsCitizenAudience,
    private val lifeOwner: () -> CivsCitizenLife,
    private val navigation: CivsResidentNavigation,
    private val admission: CivsCitizenAdmissions,
    private val maxActive: Int,
    private val viewerRange: Int,
    private val navigationClock: () -> Long,
) {
    private val workRuntime get() = workOwner()
    private val logistics get() = logisticsOwner()
    private val plans get() = plansOwner()
    private val audience get() = audienceOwner()
    private val life get() = lifeOwner()

    /** Stable locale keys; no Bukkit objects or user-controlled text enter the key. */
    fun status(workerId: UUID): String {
        checkCitizenMainThread()
        if (workerId in runtime.pendingHarm) return "workers.status.injury-pending"
        if (!runtime.closed.get()) {
            val city = runtime.cityProvider().firstOrNull { it.workers.any { worker -> worker.id == workerId } }
            val worker = city?.workers?.firstOrNull { it.id == workerId }
            if (city != null && worker != null) {
                val actor = runtime.actors[workerId]
                actor?.combatStatus?.let { return "workers.status.$it" }
                if (actor?.alarmSheltering == true) return "workers.status.alarm-shelter"
                if (actor?.sheltering == true) return "workers.status.${if (actor.sleeping) "sleeping" else "sheltering"}"
                if (actor != null && logistics.collectedPending(actor)) return "workers.status.loading-cargo"
                if (actor?.tavernService != null) return "workers.status.serving-guests"
                actor?.mealVisit?.let { return "workers.status.${if (it.seatedFrom != null && it.servedAt == null) "waiting"
                    else if (it.arrivedAt == null) "moving-to-tavern" else "eating"}" }
                if (actor?.collectionHome != null) return "workers.status.moving-to-storage"
                if (actor?.collectionSeek != null) return "workers.status.walking"
                actor?.mineVisit?.let { visit ->
                    val status = runtime.statuses[workerId]?.takeIf { it in setOf("mine-descending", "mine-returning", "route-unreachable", "searching-work") }
                        ?: if (visit.returning) "mine-returning" else if (visit.target == null) "searching-work" else "mine-descending"
                    return "workers.status.$status"
                }
                if (actor?.returningToWarehouse == true) return "workers.status.moving-to-storage"
                if (actor?.supplyFailure?.second == "storage-full" && actor.supplyRetryAt > runtime.residentObservedAt)
                    return "workers.status.storage-full"
                if (actor?.outputReturn != null) return "workers.status.${when {
                    actor.supplyPickup != null -> "loading-cargo"
                    actor.supplyFailure == (actor.outputReturn to "storage-full") && actor.supplyRetryAt > runtime.residentObservedAt -> "storage-full"
                    else -> "returning-output"
                }}"
                actor?.social?.visit?.let {
                    return "workers.status.${if (it.partnerId != null) {
                        if (it.conversationAt == null) "meeting" else "talking"
                    } else if (it.arrivedAt == null) "to-campfire" else "campfire"}"
                }
                val plan = if (actor == null) plans.plan(city, worker)
                    else citizenCurrentPlan(actor.plan, actor.cycle.phase) { plans.plan(city, worker) }
                if (actor != null) {
                    if (actor.cycle.phase == CitizenPhase.PAUSED) return "workers.status.${actor.cycle.status()}"
                    if (workRuntime.navigationFailed(actor))
                        return "workers.status.route-unreachable"
                    if (plan != null) workRuntime.workActivityStatus(actor, plan)?.let { return "workers.status.$it" }
                    if (plan?.idleReason == "unassigned" && actor.route?.let { route ->
                            route.idleAnchor != null && route.motion.navigating && !route.motion.searching &&
                                !route.motion.waitingForCollision && !route.motion.turning
                        } == true) return "workers.status.walking"
                }
                plan?.idleReason?.takeUnless { it == "placing" }?.let { return "workers.status.$it" }
                if (runtime.actors[workerId]?.plan?.token?.let { it != plan?.token } == true) return "workers.status.waiting"
                if (actor != null && plan != null) citizenWorkStatus(plan, actor.cycle.phase, life.servingTavernGuests(actor, city))?.let { return "workers.status.$it" }
                if (actor != null && plan?.idleReason == "placing") {
                    val placing = actor.cycle.phase == CitizenPhase.WORKING &&
                        plan.token.constructionId?.let { workRuntime.constructionBuilder(actor, city, it) } != null
                    return "workers.status.${if (placing) "placing" else actor.cycle.status()}"
                }
            }
        }
        return "workers.status.${if (runtime.closed.get()) "closed" else runtime.statuses[workerId] ?: "sleeping"}"
    }

    fun activeCount(): Int {
        checkCitizenMainThread()
        return runtime.actors.size
    }

    /** Observe owned state without planning, reloading chunks or changing a resident's phase. */
    internal fun debug(cityId: UUID?, workerId: UUID?): Map<String, Any?> {
        checkCitizenMainThread()
        val selected = runtime.actors.values.filter { (cityId == null || it.cityId == cityId) && (workerId == null || it.workerId == workerId) }
        val now = navigationClock()
        return linkedMapOf("closed" to runtime.closed.get(), "maximum_active" to maxActive, "viewer_range" to viewerRange,
            "navigation_tick" to runtime.navigationTick,
            "cadence_ms" to runtime.navigationCadenceMillis, "planner_global" to navigation.debug(),
            "admission" to admission.debug(cityId, workerId),
            "total" to selected.size, "truncated" to (selected.size > 8), "entries" to selected.take(8).map { actor ->
                val valid = actor.npc.isValid
                val position = if (valid) actor.npc.location else null
                val route = actor.route
                val city = runtime.cityProvider().firstOrNull { it.id == actor.cityId }
                val meal = actor.mealVisit
                val guest = city?.workers?.firstOrNull { it.id == actor.workerId }
                linkedMapOf("city" to actor.cityId, "worker" to actor.workerId, "valid" to valid,
                    "status" to runtime.statuses[actor.workerId], "phase" to actor.cycle.phase,
                    "pause_reason" to actor.cycle.reason, "failed_attempts" to actor.cycle.failedAttempts,
                    "mine_route_episode" to actor.mineRouteEpisode.snapshot(now)?.let { mapOf(
                        "building" to it.key.buildingId, "plan_version" to it.key.planVersion, "cycle" to it.key.cycle,
                        "mining_step" to it.key.miningStep, "failures" to it.failures, "elapsed_ms" to it.elapsedMillis,
                        "walked_distance" to it.walkedDistance, "suppressed" to it.suppressed) },
                    "injury_pending" to (actor.workerId in runtime.pendingHarm),
                    "terrain_obstructed_at" to actor.terrainObstructedAt,
                    "actual" to position?.point(), "body_yaw" to if (valid) actor.npc.bodyYaw else null,
                    "head_yaw" to if (valid) actor.npc.headYaw else null,
                    "plan" to mapOf("assignment" to actor.plan.token.assignmentId, "cycle" to actor.plan.token.completedCycles,
                        "craft_job" to actor.plan.token.craftJobId,
                        "type" to actor.plan.type, "idle_reason" to actor.plan.idleReason, "storage" to actor.plan.storage,
                        "workplace" to actor.plan.workplace, "focus" to actor.plan.worldWork?.let(::citizenWorkFocus),
                        "standing" to actor.plan.worldWork?.standing, "mining_step" to actor.plan.worldWork?.miningStep),
                    "route" to route?.let { mapOf("from" to it.from, "target" to it.target.point(), "authored" to it.authored,
                        "started_at" to it.startedAt, "intermediate" to it.intermediate,
                        "state" to it.motion.preparationPhase, "searching" to it.motion.searching, "navigating" to it.motion.navigating,
                        "continuation" to it.motion.continuation,
                        "failure" to it.motion.failure, "completion" to it.completion?.cancelReason,
                        "path_progress" to it.motion.forwardProgress, "episode_progress" to actor.navigationEpisodeProgress,
                        "remaining_nodes" to it.motion.remainingNodes, "nodes" to it.motion.nodeCount,
                        "captured_blocks" to it.motion.capturedBlocks, "native_shape_reads" to it.motion.nativeShapeReads,
                        "corridor_hint" to it.motion.hasCourierCorridorHint, "replans" to it.replanAttempts,
                        "collision_wait" to it.motion.waitingForCollision, "blocking_resident" to actor.collisionResident,
                        "yielding_to" to actor.residentYield?.passing, "destination" to it.motion.destination?.point(),
                        "preparation_ms" to it.timing.preparationElapsed(now), "travel_ms" to it.timing.elapsed(now),
                        "timeout_ms" to it.timeoutMillis, "storage_reason" to it.storageReason) },
                    "shelter" to mapOf("seeking" to actor.sheltering, "sleeping" to actor.sleeping,
                        "target" to actor.shelter, "search_cursor" to actor.shelterSearchIndex, "sleep_origin" to actor.sleepOrigin?.point()),
                    "courier" to mapOf("pickup_started_at" to actor.courierPickupStartedAt,
                        "source_observed" to actor.courierSourceObserved, "retarget_after" to actor.courierTargetAfter,
                        "returning_to_warehouse" to actor.returningToWarehouse, "shipment" to actor.plan.courier?.cargo?.id,
                        "source_worker" to actor.plan.courier?.cargo?.sourceWorkerId, "carrying" to actor.plan.courier?.carrying),
                    "combat" to mapOf("attacker" to actor.attacker?.uniqueId, "until" to actor.combatUntil,
                        "status" to actor.combatStatus, "target" to actor.combatDestination),
                    "food" to mapOf("source" to actor.foodSource, "pickup_pending" to (actor.supplyPickup != null),
                        "retry_at" to actor.supplyRetryAt),
                    "meal" to (mapOf("building" to actor.mealVisit?.plan?.buildingId, "source" to actor.mealVisit?.plan?.source,
                        "target" to actor.mealVisit?.target, "started_at" to actor.mealVisit?.startedAt,
                        "arrived_at" to actor.mealVisit?.arrivedAt, "observed_at" to actor.mealVisit?.observedAt,
                        "seat" to actor.mealVisit?.seat?.block, "seated_at" to actor.mealVisit?.seatedAt,
                        "furniture_id" to actor.mealVisit?.seat?.furnitureId, "table" to actor.mealVisit?.seat?.table,
                        "keeper" to actor.mealVisit?.keeperId, "served_at" to actor.mealVisit?.servedAt,
                        "ticket" to actor.mealVisit?.ticket,
                        "service_guest" to actor.tavernService?.guestId,
                        "service_ticket" to actor.tavernService?.ticket,
                        "service_loaded" to actor.tavernService?.loaded,
                        "service_target" to actor.tavernService?.target,
                        "service_retry_at" to meal?.serviceRetryAt,
                        "service_failure" to actor.tavernServiceFailure?.let { mapOf("reason" to it.reason,
                            "at" to it.at, "guest" to it.guest, "target" to it.target) },
                        "pending" to (life.residentLifePending(actor) && actor.mealOutcome?.first == "pending"), "retry_at" to actor.lifeRetryAt,
                        "last_outcome" to actor.mealOutcome?.first, "last_outcome_at" to actor.mealOutcome?.second) +
                        if (workerId == null) emptyMap() else mapOf(
                        "seat_failure" to meal?.seat?.let { life.mealSeatUnavailable(actor) },
                        "service_keepers_truncated" to (runtime.actors.values.count {
                            it.cityId == actor.cityId && it.plan.type == BuildingType.TAVERN } > 4),
                        "service_keepers" to if (meal?.foodSource == null || city == null || guest == null) emptyList<Any>()
                            else runtime.actors.values.filter { it.cityId == actor.cityId && it.plan.type == BuildingType.TAVERN }
                                .take(4).map { keeper -> mapOf("worker" to keeper.workerId,
                                    "unavailable" to life.tavernKeeperUnavailable(keeper, city, guest, meal.building,
                                        continuingService = keeper.tavernService?.guestId == guest.id),
                                    "life_failure" to life.lifeActorUnavailable(keeper),
                                    "service_guest" to keeper.tavernService?.guestId,
                                    "last_failure" to keeper.tavernServiceFailure?.let { mapOf("reason" to it.reason,
                                        "at" to it.at, "guest" to it.guest, "target" to it.target) },
                                    "positions" to life.tavernServingPoints(requireNotNull(meal.seat)).map { point ->
                                        mapOf("point" to point, "unavailable" to life.tavernServingPointUnavailable(keeper, meal, point))
                                    }) })),
                    "social" to mapOf("visit" to (actor.social.visit != null), "arrived_at" to actor.social.visit?.arrivedAt,
                        "partner" to actor.social.visit?.partnerId, "seat" to actor.social.visit?.seat,
                        "conversation_at" to actor.social.visit?.conversationAt))
            })
    }

    /** Sleeping and absent residents do not accrue hunger; awake idle residents still do. */
    internal fun awakeResidentIds(cityId: UUID): Set<UUID> {
        checkCitizenMainThread()
        val present = audience.viewers()
        return runtime.actors.values.filter { actor -> actor.cityId == cityId && actor.npc.isValid &&
            actor.workerId !in runtime.pendingHarm && !actor.sleeping && !actor.sheltering &&
            (actor.npc.world.time !in 12_000L..23_000L || actor.plan.type == BuildingType.GUARD_TOWER) &&
            citizenChunkReady(actor.npc.world, actor.npc.location.blockX shr 4, actor.npc.location.blockZ shr 4) &&
            audience.activeAt(cityId, actor.worldId, actor.npc.location.point(), present)
        }.mapTo(hashSetOf()) { it.workerId }
    }

    /** Observed work admission retained for diagnostics, independent of the hunger clock. */
    internal fun workingResidentIds(cityId: UUID): Set<UUID> {
        checkCitizenMainThread()
        return runtime.actors.values.filter { actor ->
            actor.cityId == cityId && actor.npc.isValid && actor.workerId !in runtime.pendingHarm && !actor.sheltering &&
                actor.plan.idleReason in listOf(null, "placing") &&
                actor.cycle.phase !in setOf(CitizenPhase.IDLE, CitizenPhase.PAUSED, CitizenPhase.CLOSED)
        }.mapTo(hashSetOf()) { it.workerId }
    }

    /** Reassignment may retire leisure, never an accepted work, transfer or injury episode. */
    internal fun canReassign(cityId: UUID, workerId: UUID): Boolean {
        checkCitizenMainThread()
        if (runtime.closed.get() || workerId in runtime.pendingHarm) return false
        val actor = runtime.actors[workerId] ?: return true
        val idleTavernkeeper = actor.plan.type == BuildingType.TAVERN && actor.cycle.phase == CitizenPhase.WORKING &&
            actor.npc.isValid && !actor.npc.isSeated && !actor.sleeping && !actor.sheltering && !actor.alarmSheltering &&
            actor.attacker == null && actor.residentYield == null && actor.social.visit == null &&
            actor.route == null && actor.workDetour == null
        return actor.cityId == cityId && (actor.cycle.phase == CitizenPhase.IDLE || idleTavernkeeper) &&
            !life.residentLifePending(actor) && actor.mealVisit == null && actor.mentorVisit == null &&
            actor.plan.worldWork == null && actor.plan.courier == null && actor.plan.autonomousWork == null &&
            actor.plan.token.constructionId == null && actor.plan.token.craftJobId == null &&
            actor.supplyPickup == null && actor.foodSource == null &&
            actor.courierPickupStartedAt == null && !actor.returningToWarehouse &&
            !logistics.collectedPending(actor) && actor.collectionSeek == null && actor.collectionHome == null &&
            actor.collectionCycle == null && actor.terrainObstructedAt == null
    }

    fun workplaceSnapshots(): List<CivsWorkplaceSnapshot> {
        checkCitizenMainThread()
        return runtime.cityProvider().flatMap { city -> city.buildings.mapNotNull { building ->
            building.control?.let { CivsWorkplaceSnapshot(building.id, city.worldId, it,
                CivsRules.effectiveWorkType(city, building), building.rotation) }
        } }
    }

    fun hologramSnapshots(): List<CivsNpcHologramSnapshot> {
        checkCitizenMainThread()
        return runtime.actors.values.mapNotNull { actor ->
            if (!actor.npc.isValid) return@mapNotNull null
            val city = runtime.cityProvider().firstOrNull { it.id == actor.cityId } ?: return@mapNotNull null
            val resident = city.workers.firstOrNull { it.id == actor.workerId } ?: return@mapNotNull null
            hologramSnapshot(actor, city, resident)
        }
    }

    /** Explicit public opt-in: at most 48 live carriers, requested IDs only; no planning or world reads. */
    fun sceneEntityInformation(text: CivsText, bounds: ArcSceneBounds, entityIds: Set<String>, limit: Int): List<ArcSceneEntityInfo> {
        checkCitizenMainThread()
        require(limit in 1..64 && entityIds.size <= 64)
        if (runtime.closed.get() || entityIds.isEmpty()) return emptyList()
        val cities = runtime.cityProvider().associateBy { it.id }
        val result = ArrayList<ArcSceneEntityInfo>(minOf(limit, runtime.actors.size))
        for (actor in runtime.actors.values) {
            val npc = actor.npc
            if (!npc.isValid || !npc.isVisibleByDefault || "packet-npc:${npc.uniqueId}" !in entityIds) continue
            val city = cities[actor.cityId] ?: continue
            if (city.pendingDeletion != null || city.worldId != bounds.worldId) continue
            val resident = city.workers.firstOrNull { it.id == actor.workerId } ?: continue
            val at = npc.location
            if (!bounds.contains(npc.world.uid, at.x, at.y, at.z)) continue
            civsSceneEntityInfo(text, bounds, entityIds, hologramSnapshot(actor, city, resident), city)?.let(result::add)
            if (result.size == limit) break
        }
        return result
    }

    private fun hologramSnapshot(actor: CitizenActor, city: Settlement, resident: CivWorker): CivsNpcHologramSnapshot {
        val entity = actor.npc
        val at = entity.location
        val type = if (resident.cargo != null) BuildingType.WAREHOUSE else
            resident.assignment?.let(city::building)?.let { CivsRules.effectiveWorkType(city, it) }
        return CivsNpcHologramSnapshot(resident.id, at.world!!.uid, at.x, at.y, at.z, resident.name,
            "workers.status.${runtime.statuses[resident.id] ?: "waiting"}", CivsResidents.health(resident), CivsResidents.maxHealth(resident),
            actor = entity, profession = if (CivsResidents.isChild(resident)) "child" else type?.let(CivsRules::profession) ?: "unassigned",
            courierCart = citizenCourierCart(actor.plan, actor.cycle.phase, actor.sheltering),
            cargo = resident.cargo?.let { mapOf(it.material to it.amount.toInt()) }.orEmpty(),
            yaw = entity.bodyYaw, nativeHeight = entity.height, hunger = CivsResidents.hunger(resident),
            child = CivsResidents.isChild(resident))
    }
}
