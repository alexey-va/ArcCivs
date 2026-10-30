package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.World
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentLinkedQueue

/** Supplies, collected items and durable work-transfer completions. */
internal class CivsCitizenLogistics(
    private val runtime: CitizenRuntime,
    private val routesOwner: () -> CivsCitizenRoutes,
    private val couriersOwner: () -> CivsCitizenCouriers,
    private val leisureOwner: () -> CivsCitizenLeisure,
    private val workOwner: () -> CivsCitizenWork,
    private val delivery: (UUID, UUID, UUID, Long, Long, UUID?, Long) -> CompletableFuture<Settlement>,
    private val pickupCargo: (UUID, UUID, CivCargo) -> CompletableFuture<Settlement>,
    private val dropoffCargo: (UUID, UUID, CivCargo, UUID) -> CompletableFuture<Settlement>,
    private val workInputsReady: (Settlement, CivWorker) -> Boolean,
    private val residentSupplySource: (Settlement, CivWorker) -> UUID?,
    private val residentFoodSource: (Settlement, CivWorker) -> CivResidentFoodSource?,
    private val residentFoods: CivsResidentFoods,
    private val pickupResidentFood: (UUID, UUID, CivResidentFoodSource) -> CompletableFuture<Settlement>,
    private val prepareSupplies: (UUID, UUID, UUID) -> CompletableFuture<Settlement>,
    private val craftDelivery: (UUID, UUID, UUID, Long, Long, UUID, Long) -> CompletableFuture<Settlement>,
    private val craftSupplies: (UUID, UUID, UUID, UUID) -> CompletableFuture<Settlement>,
    private val collectionAvailable: (UUID) -> Boolean,
    private val collectionHome: (Settlement, CivWorker) -> CivCollectedHome?,
    private val pickupGround: (UUID, UUID, List<UUID>) -> CompletableFuture<Settlement>,
    private val depositCollected: (UUID, UUID, CivPosition?) -> CompletableFuture<Settlement>,
    private val returnResidentOutputs: (UUID, UUID, UUID) -> CompletableFuture<Settlement>,
) {
    private val routes get() = routesOwner()
    private val couriers get() = couriersOwner()
    private val leisure get() = leisureOwner()
    private val workRuntime get() = workOwner()
    @Volatile private var completions = ConcurrentLinkedQueue<Completion>()
    private val supplyCompletions = ConcurrentLinkedQueue<SupplyCompletion>()
    private val collectedRequests = CivsCitizenRequests<Unit>(runtime.closed::get)
    private data class Completion(val workerId: UUID, val actorId: UUID, val ticket: UUID, val token: CitizenToken,
        val city: Settlement?, val error: Throwable?, val pickup: Boolean = false, val visualWork: CivWorldWork? = null)
    private data class SupplyCompletion(val workerId: UUID, val actorId: UUID, val pickup: CitizenSupplyPickup,
        val city: Settlement?, val error: Throwable?)

    fun cancel(actor: CitizenActor) = collectedRequests.cancel(actor.workerId, actor.instanceId)
    fun close() { completions.clear(); supplyCompletions.clear(); collectedRequests.close() }

    fun collectedPending(actor: CitizenActor): Boolean = collectedRequests.pending(actor.workerId, actor.instanceId)

    fun supplyFood(actor: CitizenActor, city: Settlement, worker: CivWorker, world: World, now: Long): Boolean {
        if (actor.cycle.phase in setOf(CitizenPhase.PICKING_UP, CitizenPhase.DELIVERING) || now < actor.supplyRetryAt) return false
        if (world.time !in 12_000L..23_000L && worker.cargo == null && CivsResidents.hunger(worker) <= 14 &&
            residentFoods.carried(city, worker) == null) {
            val physical = actor.foodPickupSource ?: residentFoodSource(city, worker)
            if (physical != null) {
                if (actor.foodPickupSource != physical) {
                    workRuntime.rememberWorkDetour(actor)
                    routes.cancelRoute(actor)
                    leisure.stopCamp(actor, "food")
                    actor.foodPickupSource = physical
                    actor.foodSource = physical.buildingId
                }
                actor.cycle.deferWork(now)
                if (civsCanReachContainer(actor.npc.location, physical.container)) {
                    routes.cancelRoute(actor)
                    beginSupplies(actor, physical.buildingId, now, false)
                } else {
                    runtime.statuses[worker.id] = "moving-to-storage"
                    if (actor.route?.let { it.completion != null || it.timedOut(now) } == true) {
                        routes.cancelRoute(actor)
                        actor.foodPickupSource = null
                        actor.foodSource = null
                        actor.supplyRetryAt = now + 5_000
                    } else if (actor.route == null && !routes.startRoute(actor, world, physical.standing, now)) {
                        actor.foodPickupSource = null
                        actor.foodSource = null
                        actor.supplyRetryAt = now + 5_000
                    }
                }
                return true
            }
        } else actor.foodPickupSource = null
        val source = residentSupplySource(city, worker)
        if (source == null) {
            if (actor.foodSource != null) { routes.cancelRoute(actor); actor.foodSource = null }
            return false
        }
        val control = city.building(source)?.control ?: return false
        if (actor.foodSource != source) {
            workRuntime.rememberWorkDetour(actor)
            routes.cancelRoute(actor)
            leisure.stopCamp(actor, "food")
            actor.foodSource = source
        }
        travelForSupplies(actor, city, world, control, now, resumeWork = false)
        return true
    }

    fun travelForSupplies(actor: CitizenActor, city: Settlement, world: World, target: CivPosition, now: Long,
                                  resumeWork: Boolean = false) {
        runtime.statuses[actor.workerId] = if (actor.outputReturn != null) "returning-output" else "moving-to-storage"
        if (now < actor.supplyRetryAt) {
            runtime.statuses[actor.workerId] = if (actor.outputReturn != null &&
                actor.supplyFailure == (actor.outputReturn to "storage-full")) "storage-full" else "waiting-materials"
            return
        }
        if (routes.arrivedAt(actor, city, world, target)) {
            routes.cancelRoute(actor)
            citizenControlBuilding(city, target)?.let { beginSupplies(actor, it.id, now, resumeWork) }
            return
        }
        if (actor.route == null) routes.startRoute(actor, world, target, now)
        actor.route?.let { route ->
            if (route.completion != null || route.timedOut(now)) {
                // A supply detour may retry normally, but never teleport into work from a food trip.
                routes.cancelRoute(actor)
                actor.supplyRetryAt = now + 1_000
            }
        }
    }

    fun beginSupplies(actor: CitizenActor, buildingId: UUID, now: Long, resumeWork: Boolean) {
        if (actor.supplyPickup != null || now < actor.supplyRetryAt) return
        val pickup = CitizenSupplyPickup(UUID.randomUUID(), buildingId, resumeWork, actor.outputReturn == buildingId)
        actor.supplyPickup = pickup
        val future = try {
            val job = actor.plan.token.craftJobId.takeIf { buildingId == actor.plan.token.assignmentId }
            if (pickup.returnOutputs) returnResidentOutputs(actor.cityId, actor.workerId, buildingId)
            else if (actor.foodPickupSource != null) pickupResidentFood(actor.cityId, actor.workerId, requireNotNull(actor.foodPickupSource))
            else if (job == null) prepareSupplies(actor.cityId, actor.workerId, buildingId)
            else craftSupplies(actor.cityId, actor.workerId, buildingId, job)
        }
            catch (error: Exception) { CompletableFuture.failedFuture(error) }
        if (!future.isDone && actor.plan.type == BuildingType.BUILDER_HUT) CivsDiagnostics.event("citizen_supplies_pending", "city" to actor.cityId,
            "worker" to actor.workerId, "source" to buildingId, "phase" to actor.cycle.phase,
            "order" to actor.plan.token.constructionId, "state" to "operation-pending")
        future.whenComplete { city, error ->
            if (!runtime.closed.get()) supplyCompletions.add(SupplyCompletion(actor.workerId, actor.instanceId, pickup, city, error))
        }
    }

    fun drainSupplies(now: Long) {
        while (true) {
            val completion = supplyCompletions.poll() ?: return
            val actor = runtime.actors[completion.workerId]?.takeIf {
                it.instanceId == completion.actorId && it.supplyPickup == completion.pickup
            } ?: continue
            actor.supplyPickup = null
            actor.foodPickupSource = null
            val error = (completion.error as? CompletionException)?.cause ?: completion.error
            if (error != null) {
                actor.foodSource = null
                val reason = CivsDiagnostics.rejection(error)?.reason ?: "unexpected-failure"
                actor.supplyRetryAt = now + if (completion.pickup.returnOutputs && reason == "storage-full") 5_000 else 1_000
                if (completion.pickup.returnOutputs && error is CivsRejection && reason == "storage-full" &&
                    actor.outputReturn == completion.pickup.buildingId) {
                    // No transfer was admitted; allow fresh useful work to replace an idle detour plan.
                    actor.outputReturn = null
                    routes.cancelRoute(actor)
                }
                val failure = completion.pickup.buildingId to reason
                if (actor.plan.type == BuildingType.BUILDER_HUT && actor.supplyFailure != failure) CivsDiagnostics.event("citizen_supplies_blocked",
                    "city" to actor.cityId, "worker" to actor.workerId, "source" to completion.pickup.buildingId,
                    "order" to actor.plan.token.constructionId, "phase" to actor.cycle.phase, "reason" to reason)
                actor.supplyFailure = failure
                if (error !is CivsRejection) runtime.logFailure(actor, "supplies", error)
            } else {
                actor.supplyFailure = null
                actor.foodSource = null
                if (completion.pickup.returnOutputs) actor.outputReturn = null
                val city = completion.city ?: continue
                val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: continue
                if (completion.pickup.resumeWork && workInputsReady(city, worker)) actor.cycle.arrive(now)
                actor.equipmentItems = null
            }
        }
    }

    /** Seeking is leisure; a useful work action, cargo, combat and night shelter retain their priorities. */
    fun collectItems(actor: CitizenActor, city: Settlement, worker: CivWorker, world: World, now: Long,
        night: Boolean = false): Boolean {
        if (CivsResidents.isChild(worker) || worker.cargo != null || actor.plan.courier != null ||
            actor.sleeping || actor.foodSource != null || actor.supplyPickup != null ||
            actor.cycle.phase in setOf(CitizenPhase.WORKING, CitizenPhase.PICKUP_READY, CitizenPhase.PICKING_UP,
                CitizenPhase.RETURNING, CitizenPhase.READY, CitizenPhase.DELIVERING)) {
            clearCollectedRoute(actor)
            return false
        }
        if (worker.hasCollectedItems != true) {
            actor.collectionCycle = null
            if (actor.collectionHome != null) { routes.cancelRoute(actor); actor.collectionHome = null }
        } else if (actor.collectionCycle == null) actor.collectionCycle = worker.completedCycles
        val idle = citizenCollectionIdle(actor.plan, actor.cycle.phase)
        val shouldGoHome = worker.hasCollectedItems == true &&
            (night || idle || worker.completedCycles > requireNotNull(actor.collectionCycle))
        if (night || !idle && !shouldGoHome) clearCollectedRoute(actor)
        if (now < actor.collectionRetryAt || !collectionAvailable(city.id) || runtime.collectionBudget == 0) {
            // An admitted leisure route retains its identity while another resident uses the native budget.
            if (actor.collectionSeek != null || actor.collectionHome != null) {
                runtime.statuses[worker.id] = if (actor.collectionHome != null) "moving-to-storage" else "walking"
                return true
            }
            return false
        }
        runtime.collectionBudget--
        citizenRotateAfterAdmission(runtime.actors, actor.workerId)
        if (shouldGoHome) {
            val home = collectionHome(city, worker)
            if (home == null) { actor.collectionRetryAt = now + 5_000; return false }
            if (!home.surplus) {
                beginCollected(actor, now) { depositCollected(city.id, worker.id, null) }
                return true
            }
            val target = home.container
            if (target == null) { actor.collectionRetryAt = now + 5_000; return false }
            if (civsCanReachContainer(actor.npc.location, target)) {
                leisure.stopCamp(actor, "home-deposit")
                routes.cancelRoute(actor)
                actor.collectionHome = target
                beginCollected(actor, now) { depositCollected(city.id, worker.id, target) }
                return true
            }
            if (night) return false
            val building = city.building(home.homeId) ?: return false
            val standing = citizenCollectedHomeTarget(city, building, world, target)
            if (standing == null) { actor.collectionRetryAt = now + 5_000; return false }
            leisure.stopCamp(actor, "home-return")
            if (actor.collectionHome != target || actor.route?.authored != standing) routes.cancelRoute(actor)
            actor.collectionSeek = null
            actor.collectionHome = target
            if (actor.route == null) routes.startRoute(actor, world, standing, now)
            val route = actor.route
            if (route == null || route.completion != null || route.timedOut(now)) {
                routes.cancelRoute(actor)
                actor.collectionHome = null
                actor.collectionRetryAt = now + 5_000
                return false
            }
            runtime.statuses[worker.id] = "moving-to-storage"
            return true
        }
        if (night) return false
        val origin = actor.npc.location
        val radius = if (idle) 8.0 else 2.0
        val nearby = world.getNearbyEntities(origin, radius, 2.0, radius) { it is org.bukkit.entity.Item }.asSequence()
            .filterIsInstance<org.bukkit.entity.Item>().take(32)
            .filter { city.contains(it.location.blockX, it.location.blockZ) && civsGroundItemAvailable(it, worker.id) }
            .filter { item -> CivResidentItem.of(item.itemStack)?.let { CivsEquipment.collectGround(worker, it).second > 0 } == true }
            .sortedWith(compareBy<org.bukkit.entity.Item> { origin.distanceSquared(it.location) }.thenBy { it.uniqueId })
            .toList()
        val reachable = nearby.filter { civsCanCollect(origin, it, worker.id) }.take(8)
        if (reachable.isNotEmpty()) {
            if (actor.collectionSeek != null) routes.cancelRoute(actor)
            leisure.stopCamp(actor, "ground-pickup")
            actor.collectionSeek = null
            actor.collectionCycle = worker.completedCycles
            beginCollected(actor, now) { pickupGround(city.id, worker.id, reachable.map { it.uniqueId }) }
            return true
        }
        if (!idle) return false
        val item = nearby.firstOrNull { civsCanCollect(origin, it, worker.id, 8.0) }
        if (item == null) {
            if (actor.collectionSeek != null) routes.cancelRoute(actor)
            actor.collectionSeek = null
            actor.collectionRetryAt = now + 2_000
            return false
        }
        val standing = citizenCollectedItemTarget(city, world, item.location)
        if (standing == null) { actor.collectionRetryAt = now + 5_000; return false }
        leisure.stopCamp(actor, "ground-item")
        if (actor.collectionSeek != item.uniqueId || actor.route?.authored != standing) routes.cancelRoute(actor)
        actor.collectionSeek = item.uniqueId
        if (actor.route == null) routes.startRoute(actor, world, standing, now)
        val route = actor.route
        if (route == null || route.completion != null || route.timedOut(now)) {
            routes.cancelRoute(actor)
            actor.collectionSeek = null
            actor.collectionRetryAt = now + 5_000
            return false
        }
        runtime.statuses[worker.id] = "walking"
        return true
    }

    private fun clearCollectedRoute(actor: CitizenActor) {
        if (actor.collectionSeek != null || actor.collectionHome != null) routes.cancelRoute(actor)
        actor.collectionSeek = null
        actor.collectionHome = null
    }

    private fun beginCollected(actor: CitizenActor, now: Long, operation: () -> CompletableFuture<Settlement>) {
        collectedRequests.start(actor.workerId, actor.instanceId, Unit) {
            actor.collectionRetryAt = now + 5_000
            runtime.statuses[actor.workerId] = "loading-cargo"
            operation()
        }
    }

    fun drainCollected(now: Long) {
        collectedRequests.tick({ runtime.actors[it]?.instanceId }) { completion ->
            val actor = runtime.actors.getValue(completion.workerId)
            actor.collectionRetryAt = now + 5_000
            if (actor.collectionHome != null) routes.cancelRoute(actor)
            actor.collectionSeek = null
            actor.collectionHome = null
            actor.equipmentItems = null
            val error = (completion.error as? CompletionException)?.cause ?: completion.error
            if (error != null && error !is CivsRejection) runtime.logFailure(actor, "collected-items", error)
        }
    }

    fun submitReadyDelivery(actor: CitizenActor, city: Settlement, world: World, now: Long) {
        val target = actor.plan.travelTarget(CitizenPhase.RETURNING)
        if (!routes.arrivedAt(actor, city, world, target)) {
            routes.pauseNavigation(actor, "route-unreachable", "left-delivery-point", routes.resolvedTarget(actor, target).location(world), now)
        } else if (!citizenHasCapacity(city, actor.plan)) actor.cycle.pause("storage-full", now, true)
        else actor.cycle.beginDelivery(now)?.let { ticket -> submitDelivery(actor, ticket, now) }
    }

    private fun submitDelivery(actor: CitizenActor, ticket: UUID, now: Long) {
        val token = actor.plan.token
        val actorId = actor.instanceId
        val workerId = actor.workerId
        val visual = actor.plan.worldWork?.takeIf { it.autonomousVisual }
        val future = try {
            actor.plan.courier?.let { dropoffCargo(actor.cityId, actor.workerId, it.cargo, it.destinationBuildingId) }
                ?: token.craftJobId?.let { craftDelivery(actor.cityId, actor.workerId, requireNotNull(token.assignmentId),
                    token.completedCycles, token.nextDeliveryAt, it, now) }
                ?: delivery(actor.cityId, actor.workerId, requireNotNull(token.assignmentId), token.completedCycles,
                    token.nextDeliveryAt, token.constructionId, now)
        } catch (error: Exception) {
            CompletableFuture.failedFuture(error)
        }
        future.whenComplete { city, error ->
            // Only immutable domain data crosses this boundary; Bukkit and actor state stay on pulse().
            val completion = Completion(workerId, actorId, ticket, token, city, error, visualWork = visual)
            if (!runtime.closed.get()) {
                completions.add(completion)
                if (runtime.closed.get()) completions.remove(completion)
            }
        }
    }

    fun submitPickup(actor: CitizenActor, ticket: UUID) {
        val token = actor.plan.token
        val actorId = actor.instanceId
        val workerId = actor.workerId
        val future = try { pickupCargo(actor.cityId, workerId, requireNotNull(actor.plan.courier).cargo) }
            catch (error: Exception) { CompletableFuture.failedFuture(error) }
        future.whenComplete { city, error ->
            val completion = Completion(workerId, actorId, ticket, token, city, error, pickup = true)
            if (!runtime.closed.get()) {
                completions.add(completion)
                if (runtime.closed.get()) completions.remove(completion)
            }
        }
    }

    fun drainCompletions(now: Long) {
        while (true) {
            val completion = completions.poll() ?: return
            val actor = runtime.actors[completion.workerId] ?: continue
            if (actor.instanceId != completion.actorId || actor.plan.token != completion.token ||
                !(if (completion.pickup) actor.cycle.acceptsPickup(completion.ticket)
                    else actor.cycle.acceptsCompletion(completion.ticket))) continue
            val error = (completion.error as? CompletionException)?.cause ?: completion.error
            if (error != null) {
                if (completion.pickup) {
                    couriers.releaseCourierSource(actor, now)
                    actor.cycle.failPickup((error as? CivsRejection)?.reason, now)
                }
                else {
                    val rejection = error as? CivsRejection
                    val delay = rejection?.values?.get("retry_millis")?.toLongOrNull()
                        ?.takeIf { rejection?.reason == "storage-full" && actor.plan.courier?.carrying == true }
                        ?: 5_000L
                    actor.cycle.failDelivery(rejection?.reason, now, delay)
                }
                if (error !is CivsRejection) runtime.logFailure(actor, "delivery", error)
            } else {
                val worker = completion.city?.workers?.find { it.id == actor.workerId }
                if (completion.pickup) {
                    val cargo = worker?.cargo
                    if (cargo == null || cargo.id != actor.plan.courier?.cargo?.id ||
                        worker.completedCycles != completion.token.completedCycles) actor.cycle.failPickup(null, now)
                    else {
                        couriers.releaseCourierSource(actor, now, success = true)
                        actor.plan = actor.plan.copy(courier = requireNotNull(actor.plan.courier).copy(cargo = cargo, carrying = true))
                        actor.cycle.pickedUp()
                        actor.reportedFailure = false
                    }
                } else if (completion.visualWork != null) {
                    val work = completion.visualWork
                    if (worker == null || actor.plan.worldWork != work || !work.autonomousVisual ||
                        worker.assignment != completion.token.assignmentId || worker.completedCycles != completion.token.completedCycles ||
                        worker.nextDeliveryAt != completion.token.nextDeliveryAt) actor.cycle.failDelivery(null, now)
                    else {
                        actor.cycle.close()
                        actor.workHandoff = CitizenWorkHandoff(work.buildingId, worker.completedCycles,
                            actor.npc.location.point(), runtime.navigationTick, work.status)
                        actor.reportedFailure = false
                        runCatching { workRuntime.fieldCareCompleted(actor) }.onFailure { runtime.logFailure(actor, "field-care-feedback", it) }
                    }
                } else if (worker == null || worker.completedCycles != completion.token.completedCycles + 1 ||
                    actor.plan.courier != null && worker.cargo != null) {
                    actor.cycle.failDelivery(null, now)
                } else {
                    // The provider publishes durable state. Reconciliation starts a new fenced cycle, never this callback.
                    if (actor.plan.worldWork?.let { it.miningStep != null && it.changes.isNotEmpty() } == true)
                        actor.mineRouteEpisode.recovered(now)?.let { routes.mineRouteRecovered(actor, it, "work-completed", now) }
                    actor.cycle.close()
                    actor.workHandoff = actor.plan.worldWork?.takeUnless {
                        it.status in setOf("waiting-tool", "waiting-materials", "collecting-supplies")
                    }?.let { work -> CitizenWorkHandoff(work.buildingId, worker.completedCycles,
                        actor.npc.location.point(), runtime.navigationTick, work.status) }
                    actor.reportedFailure = false
                    runCatching { workRuntime.fieldCareCompleted(actor) }.onFailure { runtime.logFailure(actor, "field-care-feedback", it) }
                }
            }
            if (!completion.pickup) workRuntime.fieldCareHand(actor, null)
        }
    }
}

internal data class CitizenSupplyPickup(val ticket: UUID, val buildingId: UUID, val resumeWork: Boolean, val returnOutputs: Boolean = false)
