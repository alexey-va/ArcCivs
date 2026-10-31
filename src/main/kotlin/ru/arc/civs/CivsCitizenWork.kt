package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.World
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.EquipmentSlot
import ru.arc.paper.npc.PacketPlayerNpc
import java.util.UUID
import kotlin.math.sqrt

/** Resident work priority, visual work and construction arrival lifecycle. */
internal class CivsCitizenWork(
    private val runtime: CitizenRuntime,
    private val routesOwner: () -> CivsCitizenRoutes,
    private val shelterOwner: () -> CivsCitizenShelters,
    private val couriersOwner: () -> CivsCitizenCouriers,
    private val leisureOwner: () -> CivsCitizenLeisure,
    private val logisticsOwner: () -> CivsCitizenLogistics,
    private val combatOwner: () -> CivsCitizenCombat,
    private val audienceOwner: () -> CivsCitizenAudience,
    private val lifeOwner: () -> CivsCitizenLife,
    private val navigation: CivsResidentNavigation,
    private val paths: CivsPaths,
    private val constructionFocus: (UUID, UUID) -> CivPosition?,
    private val workInputsReady: (Settlement, CivWorker) -> Boolean,
    private val constructionSite: (UUID, UUID) -> CivConstructionSite?,
    private val worksiteTerrainChanged: (UUID, CivPosition) -> Unit,
    private val remove: (CitizenActor, String) -> Unit,
) {
    private val routes get() = routesOwner()
    private val shelter get() = shelterOwner()
    private val couriers get() = couriersOwner()
    private val leisure get() = leisureOwner()
    private val logistics get() = logisticsOwner()
    private val combat get() = combatOwner()
    private val audience get() = audienceOwner()
    private val life get() = lifeOwner()
    private val constructionWorkplaces = mutableMapOf<UUID, CivPosition>()
    private val mineVisits = CivsMineVisits(runtime) { routes }

    fun reconcileMineVisit(actor: CitizenActor, city: Settlement) = mineVisits.reconcile(actor, city)

    fun waitingForMineAccess(actor: CitizenActor, city: Settlement, worker: CivWorker, register: Boolean = true): Boolean =
        mineVisits.waitingForAccess(actor, city, worker, actor.npc.world, register)

    fun inboundMineAccess(actor: CitizenActor, city: Settlement, target: CivPosition, idleAnchor: CivPosition?): CivBuilding? =
        mineVisits.inbound(actor, city, target, idleAnchor)

    fun bindMineAccess(actor: CitizenActor, city: Settlement, route: CitizenRoute) = mineVisits.bind(actor, city, route)

    fun retainConstruction(cities: Collection<Settlement>) {
        constructionWorkplaces.keys.retainAll(cities.mapNotNull { it.construction?.id }.toSet())
        mineVisits.retain(cities)
    }
    fun clear() { constructionWorkplaces.clear(); mineVisits.clear() }

    fun update(actor: CitizenActor, city: Settlement, world: World, now: Long) {
        val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: return
        if (!routes.canResetNightShelter(actor)) actor.nightHomeProgress.clear()
        actor.mealVisit?.let { visit ->
            if (visit.arrivedAt != null && (!life.lifeActorReady(actor) || visit.seat == null &&
                !citizenArrived(actor.npc.location, visit.target.location(world)))) life.stopMeal(actor)
        }
        if (world.time in 12_000L..23_000L) {
            life.stopMeal(actor)
            if (!actor.npc.isValid) return
        }
        if (actor.mealVisit?.seatedFrom != null && !life.mealSeatStillSafe(actor)) {
            life.stopMeal(actor)
            actor.lifeRetryAt = now + 5_000
            if (!actor.npc.isValid) return
        }
        if (actor.terrainObstructedAt != null) {
            life.stopMeal(actor)
            actor.cycle.deferWork(now)
            runtime.statuses[actor.workerId] = "route-unreachable"
            return
        }
        if (actor.sleeping && !shelter.sleepStillSafe(actor)) {
            // Changed support/headroom must be handled before work, hunger or another route.
            // A clear but unsupported origin wakes in place and falls on the normal gravity tick.
            if (shelter.wake(actor)) { actor.shelter = null; actor.shelterPose = null }
            runtime.statuses[actor.workerId] = if (actor.alarmSheltering) "alarm-shelter" else "sheltering"
            return
        }
        if (actor.supplyPickup != null) { runtime.statuses[actor.workerId] = "loading-cargo"; return }
        if (combat.alarmShelter(actor, city, worker, world, now)) return
        if (combat.combat(actor, city, worker, world, now)) {
            life.stopMeal(actor)
            actor.mentorVisit = null
            actor.mineRouteEpisode.retain(null)
            couriers.releaseCourierSource(actor, now)
            return
        }
        if (logistics.collectedPending(actor)) { runtime.statuses[actor.workerId] = "loading-cargo"; return }
        if (life.serveTavernGuest(actor, city, worker, world, now)) return
        if (world.time !in 12_000L..23_000L && life.eatMeal(actor, city, worker, world, now)) return
        if (logistics.supplyFood(actor, city, worker, world, now)) {
            actor.mineRouteEpisode.retain(null)
            couriers.releaseCourierSource(actor, now)
            return
        }
        if (world.time in 12_000L..23_000L && actor.plan.type != BuildingType.GUARD_TOWER &&
            actor.cycle.phase !in setOf(CitizenPhase.DELIVERING, CitizenPhase.PICKING_UP)) {
            rememberWorkDetour(actor)
            life.stopMeal(actor)
            actor.mentorVisit = null
            if (actor.sheltering && actor.residentYield != null) { actor.cycle.deferWork(now); return }
            if (logistics.collectItems(actor, city, worker, world, now, night = true)) return
            actor.mineRouteEpisode.retain(null)
            couriers.releaseCourierSource(actor, now)
            leisure.stopCamp(actor, "night")
            shelter.shelter(actor, city, worker, world, now)
            return
        }
        if (actor.residentYield != null) { actor.cycle.deferWork(now); return }
        if (!couriers.trackCourierSource(actor, city, now)) return
        val cycle = actor.cycle
        if (actor.sheltering) {
            routes.cancelRoute(actor)
            actor.sheltering = false
            actor.shelter = null
            actor.shelterPose = null
            if (actor.workDetour == null) {
                actor.cycle.close()
                actor.cycle = actor.plan.cycle()
            } else actor.cycle.deferWork(now)
            return
        }
        if (actor.mineVisit != null && mineVisits.update(actor, city, worker, world, now)) return
        if (mineVisits.yieldForAccess(actor, city, worker, world, now)) return
        if (returnProducerOutputs(actor, city, worker, world, now)) return
        if (mineVisits.waitingForAccess(actor, city, worker, world)) {
            actor.cycle.deferWork(now)
            if (actor.route?.idleAnchor == null) routes.cancelRoute(actor)
            leisure.wander(actor, city, world, now, outsideHome = true)
            runtime.statuses[actor.workerId] = "mine-waiting"
            return
        }
        actor.plan.workstation?.let { station ->
            if (cycle.phase != CitizenPhase.DELIVERING && worker.assignment?.let(city::building)?.let {
                    CivsWorkstations.valid(city, it, world, station)
                } != true) {
                routes.cancelRoute(actor)
                cycle.deferWork(now)
                runtime.statuses[actor.workerId] = "workstation-unavailable"
                return
            }
        }
        if (returnFromWorkDetour(actor, city, world, now)) return
        if (mineVisits.update(actor, city, worker, world, now)) return
        if (CivsResidents.isChild(worker)) {
            if (life.mentor(actor, city, worker, world, now)) return
            leisure.stopCamp(actor, "child-playing")
            leisure.wander(actor, city, world, now)
            runtime.statuses[actor.workerId] = "playing"
            if (actor.route == null && now >= actor.nextPlayAt) {
                actor.npc.swingMainHand()
                val delay = 6_000L + Math.floorMod(actor.workerId.hashCode(), 4_001)
                actor.nextPlayAt = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
            }
            return
        }
        if (logistics.collectItems(actor, city, worker, world, now)) return
        if (couriers.returnToWarehouse(actor, city, world, now)) return
        if (combat.patrol(actor, city, worker, world, now)) return
        if (citizenCampEligible(actor.plan, cycle.phase, world.time)) {
            if (leisure.gather(actor, city, worker, world, now)) return
        } else leisure.stopCamp(actor, "work-priority")
        actor.plan.idleReason?.let {
            if (it != "placing") {
                if (it == "searching-work") {
                    // A committed block invalidates the worksite cache before its bounded
                    // next-job search finishes. Returning to the surface in this gap made
                    // miners climb up after every block, then reverse for the next job.
                    routes.cancelRoute(actor)
                    runtime.statuses[actor.workerId] = workActivityStatus(actor, actor.plan) ?: it
                    return
                }
                if (actor.plan.type == BuildingType.BUILDER_HUT && it == "waiting-materials") {
                    logistics.travelForSupplies(actor, city, world, actor.plan.storage, now)
                    return
                }
                if (it !in setOf("waiting-materials", "construction-paused")) leisure.wander(actor, city, world, now)
                runtime.statuses[actor.workerId] = it
                animateWork(actor, city, world, now)
                leisure.speak(actor, CitizenVoiceEvent.IDLE)
                return
            }
        }
        if (cycle.reason == "storage-full" && actor.plan.courier == null) {
            if (!citizenHasCapacity(city, actor.plan)) {
                leisure.wander(actor, city, world, now)
                runtime.statuses[actor.workerId] = "storage-full"
                return
            }
            // Leisure owns its route while production waits. Resume the saved work only
            // after retiring that route, and walk back before submitting a completed receipt.
            routes.cancelRoute(actor)
            cycle.resumeAfterWander()
        }
        cycle.retry(now)
        if (cycle.phase == CitizenPhase.IDLE) {
            if (!citizenHasCapacity(city, actor.plan)) cycle.pause("storage-full", now, false)
            else cycle.start()
        }
        when (cycle.phase) {
            CitizenPhase.TO_STORAGE, CitizenPhase.TO_WORK, CitizenPhase.RETURNING -> {
                val target = actor.plan.travelTarget(cycle.phase)
                if (routes.arrivedAt(actor, city, world, target)) {
                    arriveForWork(actor, city, worker, now)
                    runtime.statuses[actor.workerId] = if (actor.plan.worldWork?.upkeep != null &&
                        actor.cycle.phase == CitizenPhase.TO_WORK) "waiting-materials"
                    else citizenWorkStatus(actor.plan, actor.cycle.phase, life.servingTavernGuests(actor, city)) ?: actor.cycle.status()
                    // Arrival starts the observed work interval on this pulse. Its first
                    // gesture and crop-care prop belong to that same interval.
                    if (actor.cycle.phase == CitizenPhase.WORKING) {
                        if (actor.plan.type == BuildingType.WAREHOUSE && actor.plan.courier != null) {
                            actor.cycle.work(now)
                            submitReadyPickup(actor)
                        } else animateWork(actor, city, world, now)
                    }
                    return
                }
                if (actor.route == null) routes.startRoute(actor, world, target, now)
                val route = actor.route
                if (route != null) {
                    if (!route.chunks.ready(world)) {
                        remove(actor, "chunk-unloaded")
                        return
                    } else {
                        if (route.completion == null && route.path == null) routes.observePath(route)
                        val outcome = citizenNavigationOutcome(route.completion,
                            routes.arrivedAt(actor, city, world, target),
                            route.timing.elapsed(now), route.timeoutMillis, route.motion.navigating, route.motion.searching)
                        when (outcome) {
                            CitizenNavigationOutcome.PENDING -> Unit
                            CitizenNavigationOutcome.ARRIVED -> {
                                route.trip?.let { paths.complete(city, it) }
                                arriveForWork(actor, city, worker, now)
                            }
                            else -> {
                                routes.pauseNavigation(actor, "route-unreachable", outcome.name.lowercase(), route.target, now)
                                if (actor.cycle !== cycle) return
                            }
                        }
                    }
                }
            }
            CitizenPhase.WORKING -> {
                val target = requireNotNull(actor.plan.workplace)
                if (!workInputsReady(city, worker)) {
                    routes.cancelRoute(actor)
                    actor.cycle = actor.plan.copy(suppliesNeeded = true).cycle()
                    actor.cycle.start()
                } else if (!routes.arrivedAt(actor, city, world, target))
                    routes.pauseNavigation(actor, "route-unreachable", "left-workplace", routes.resolvedTarget(actor, target).location(world), now)
                else if (actor.plan.type == BuildingType.GUARD_TOWER || actor.plan.type == BuildingType.TAVERN) cycle.deferWork(now)
                else {
                    val beforeWork = cycle.observedWorkMillis
                    cycle.work(now)
                    actor.autonomousVisualSession?.takeIf { it.token == actor.plan.token }?.observe(
                        cycle.observedWorkMillis - beforeWork)
                    // Native placement is already observed work. Keep its next session ready,
                    // but request more credit only when the current credited rows run out.
                    if (cycle.phase == CitizenPhase.READY && actor.plan.idleReason != "placing")
                        logistics.submitReadyDelivery(actor, city, world, now)
                    else if (cycle.phase == CitizenPhase.PICKUP_READY && actor.plan.type == BuildingType.WAREHOUSE && actor.plan.courier != null)
                        submitReadyPickup(actor)
                }
            }
            CitizenPhase.PICKUP_READY -> {
                val target = requireNotNull(actor.plan.workplace)
                if (!routes.arrivedAt(actor, city, world, target))
                    routes.pauseNavigation(actor, "route-unreachable", "left-pickup-point", routes.resolvedTarget(actor, target).location(world), now)
                else submitReadyPickup(actor)
            }
            CitizenPhase.READY -> if (actor.plan.idleReason != "placing") logistics.submitReadyDelivery(actor, city, world, now)
            else -> Unit
        }
        if (runtime.actors[actor.workerId] === actor) {
            runtime.statuses[actor.workerId] = if (navigationFailed(actor))
                "route-unreachable" else workActivityStatus(actor, actor.plan) ?: if (actor.plan.idleReason == "placing" && cycle.phase == CitizenPhase.WORKING &&
                actor.plan.token.constructionId?.let { constructionBuilder(actor, city, it) } != null) "placing"
                else citizenWorkStatus(actor.plan, cycle.phase, life.servingTavernGuests(actor, city)) ?: cycle.status()
            animateWork(actor, city, world, now)
        }
    }

    private fun submitReadyPickup(actor: CitizenActor) {
        actor.cycle.beginPickup()?.let { ticket -> logistics.submitPickup(actor, ticket) }
    }

    fun arriveForWork(actor: CitizenActor, city: Settlement, worker: CivWorker, now: Long) {
        actor.arrivalWait = null
        actor.route?.takeIf { it.motion.forwardProgress > 0.0 }?.let { route ->
            val actual = actor.npc.location.point()
            CivsDiagnostics.event("citizen_route_arrived", "city" to city.id, "worker" to actor.workerId,
                "mode" to "walked", "phase" to actor.cycle.phase, "cycle" to worker.completedCycles,
                "from" to route.from.coordinates(), "actual" to actual.coordinates(), "target" to route.target.point().coordinates(),
                "path_progress" to route.motion.forwardProgress, "displacement" to sqrt(route.from.distanceSquared(actual)),
                "distance" to sqrt(actual.distanceSquared(route.target.point())),
                "elapsed_ms" to now - route.startedAt)
        }
        routes.captureCourierReturnHint(actor, city)
        routes.cancelRoute(actor)
        if (actor.cycle.phase == CitizenPhase.TO_STORAGE && city.inventory != null && actor.plan.courier == null &&
            actor.plan.type !in CivsWorksites.TYPES &&
            (actor.plan.type == BuildingType.BUILDER_HUT || !workInputsReady(city, worker))) {
            val source = routes.storageForTarget(actor, city, actor.plan.storage) ?: return
            logistics.beginSupplies(actor, source.id, now, resumeWork = true)
        } else if (actor.cycle.phase != CitizenPhase.TO_WORK || workInputsReady(city, worker)) actor.cycle.arrive(now)
        else {
            actor.cycle = actor.plan.copy(suppliesNeeded = true).cycle()
            actor.cycle.start()
        }
    }

    fun workActivityStatus(actor: CitizenActor, plan: CitizenPlan): String? = citizenWorkActivityStatus(plan, actor.cycle.phase,
        actor.npc.location.point(), actor.workHandoff, runtime.navigationTick)

    fun workingTravel(actor: CitizenActor): Boolean = !actor.sheltering &&
        !(actor.outputFullPause != null && actor.route?.idleAnchor != null) &&
        (actor.returningToWarehouse || citizenWorkingTravel(actor.plan, actor.cycle.phase, false,
            purposefulDetour = actor.mineVisit != null || actor.outputReturn != null || actor.foodSource != null || actor.workDetour != null ||
                actor.mealVisit != null || actor.plan.type == BuildingType.BUILDER_HUT && actor.plan.idleReason == "waiting-materials"))

    fun navigationFailed(actor: CitizenActor): Boolean = actor.route?.let {
        it.motion.failure != null || it.completion?.cancelReason != null ||
            it.motion.navigating && !it.motion.searching && runtime.navigationTick - it.progressTick >= 40
    } == true

    /** Reconcile only observed bag contents and assignment; retry expiry does not end a full-store pause. */
    fun outputFullPause(actor: CitizenActor, city: Settlement, worker: CivWorker): CitizenOutputFullPause? {
        val pause = actor.outputFullPause ?: return null
        val building = worker.assignment?.let(city::building)
        if (city.inventory != null && worker.assignment == pause.buildingId && worker.cargo == null &&
            building?.phase == BuildingPhase.ACTIVE && building.control != null && actor.plan.courier == null &&
            couriers.sourceOffersCargo(actor, city, worker)) return pause
        actor.outputFullPause = null
        if (actor.route?.idleAnchor != null && actor.foodSource == null && actor.mealVisit == null &&
            actor.social.visit == null) routes.cancelRoute(actor)
        if (actor.supplyFailure == (pause.buildingId to "storage-full")) {
            actor.supplyFailure = null
            actor.supplyRetryAt = 0
        }
        return null
    }

    /** Ordinary output leaves its bag only after the producer reaches its own workplace. */
    private fun returnProducerOutputs(actor: CitizenActor, city: Settlement, worker: CivWorker, world: World, now: Long): Boolean {
        val building = worker.assignment?.let(city::building)?.takeIf { it.phase == BuildingPhase.ACTIVE && it.control != null }
        val fullOutput = outputFullPause(actor, city, worker)
        if (city.inventory == null || building == null || actor.plan.courier != null ||
            actor.cycle.phase in setOf(CitizenPhase.DELIVERING, CitizenPhase.PICKING_UP, CitizenPhase.CLOSED) ||
            actor.plan.worldWork?.status == "waiting-tool" && fullOutput == null) {
            if (actor.outputReturn != null) { actor.outputReturn = null; routes.cancelRoute(actor) }
            return false
        }
        // Only a definite full-barrel rejection releases outputReturn in drainSupplies.
        // An uncertain accepted transfer keeps its existing pin and never enters leisure.
        if (!couriers.sourceOffersCargo(actor, city, worker)) {
            if (actor.outputReturn != null) routes.cancelRoute(actor)
            actor.outputReturn = null
            return false
        }
        if (fullOutput != null && actor.outputReturn == null &&
            (now < fullOutput.retryAt || actor.route?.idleAnchor != null)) {
            actor.cycle.deferWork(now)
            // Finish an admitted local walk before retrying; a five-second timer must not cut it short.
            leisure.wander(actor, city, world, now)
            runtime.statuses[actor.workerId] = "storage-full"
            return true
        }
        if (actor.outputReturn != building.id) {
            rememberWorkDetour(actor)
            routes.cancelRoute(actor)
            leisure.stopCamp(actor, "work-priority")
            actor.outputReturn = building.id
        }
        actor.cycle.deferWork(now)
        logistics.travelForSupplies(actor, city, world, requireNotNull(building.control), now)
        return true
    }

    /** Keep the admitted cycle's observed work while a resident visits food or shelter. */
    fun rememberWorkDetour(actor: CitizenActor) {
        if (actor.cycle.phase in setOf(CitizenPhase.WORKING, CitizenPhase.PICKUP_READY, CitizenPhase.READY) ||
            actor.cycle.phase == CitizenPhase.PAUSED && actor.plan.workMillis > 0 &&
            actor.cycle.observedWorkMillis >= actor.plan.workMillis)
            actor.workDetour = actor.plan.token
    }

    private fun returnFromWorkDetour(actor: CitizenActor, city: Settlement, world: World, now: Long): Boolean {
        val accepted = actor.workDetour ?: return false
        if (accepted != actor.plan.token || actor.cycle.phase !in setOf(CitizenPhase.WORKING,
                CitizenPhase.PICKUP_READY, CitizenPhase.READY, CitizenPhase.PAUSED)) {
            actor.workDetour = null
            return false
        }
        val working = actor.cycle.phase in setOf(CitizenPhase.WORKING, CitizenPhase.PICKUP_READY)
        val target = actor.plan.travelTarget(if (working) CitizenPhase.TO_WORK else CitizenPhase.RETURNING)
        actor.cycle.deferWork(now)
        if (routes.arrivedAt(actor, city, world, target)) {
            routes.cancelRoute(actor)
            actor.workDetour = null
            return false
        }
        runtime.statuses[actor.workerId] = if (target == actor.plan.workplace) "moving-to-work" else "moving-to-storage"
        if (now < actor.lifeRetryAt) return true
        if (actor.route?.let { it.completion != null || it.timedOut(now) } == true) {
            routes.cancelRoute(actor)
            actor.lifeRetryAt = now + 5_000
            return true
        }
        if (actor.route == null && runtime.wanderBudget > 0) {
            runtime.wanderBudget--
            routes.startRoute(actor, world, target, now)
        }
        return true
    }

    private fun animateWork(actor: CitizenActor, city: Settlement, world: World, now: Long) {
        if (actor.plan.type == BuildingType.BUILDER_HUT && actor.plan.worldWork?.upkeep == null) {
            val order = city.construction ?: return
            val target = constructionFocus(city.id, order.id) ?: return
            constructionBuilder(actor, city, order.id)?.let { lookAtBlock(it, target) }
            return
        }
        val working = citizenWorking(actor.plan, actor.cycle.phase)
        if (!working || (actor.route?.motion?.navigating == true)) { fieldCareHand(actor, null); return }
        val workplace = actor.plan.workplace ?: return
        if (!routes.arrivedAt(actor, city, world, workplace)) { fieldCareHand(actor, null); return }
        val player = actor.npc
        val eye = player.location.clone().add(0.0, player.eyeHeight, 0.0)
        val workFocus = actor.plan.worldWork?.let(::citizenWorkFocus) ?: actor.plan.workstation?.focus
        val focus = if (workFocus != null) citizenReachableBlockPoint(world, eye, workFocus,
            citizenWorkReach(actor.plan.type, actor.plan.worldWork), actor.plan.worldWork) ?: return
        else if (city.buildings.any { it.control == workplace })
            Location(world, workplace.x + 0.5, workplace.y + 0.7, workplace.z + 0.5)
        else citizenWorkBuilding(city, actor.plan)?.bounds?.let { bounds ->
            Location(world,
                (bounds.min.x.toDouble() + bounds.max.x) / 2.0 + 0.5,
                player.location.clone().add(0.0, player.eyeHeight, 0.0).y,
                (bounds.min.z.toDouble() + bounds.max.z) / 2.0 + 0.5)
        }
        fieldCareHand(actor, citizenFieldCareItem(actor.plan))
        if (!actor.animation.swing(now)) return
        focus?.let { citizenLookDirection(eye, it).let { (yaw, pitch) -> routes.look(player, yaw, pitch) } }
        player.swingMainHand()
        leisure.speak(actor, CitizenVoiceEvent.WORK)
    }

    fun fieldCareHand(actor: CitizenActor, material: Material?) {
        if (actor.careItem == material) return
        actor.careItem = material
        if (!actor.npc.isValid) return
        val hand = material?.let(::ItemStack) ?: actor.equipmentItems?.getOrNull(4)?.stack()
            ?: ItemStack(citizenTool(actor.plan) ?: Material.AIR)
        actor.npc.setEquipment(EquipmentSlot.HAND, hand)
    }

    /** One small contact cue after the durable receipt; no looping particles or world mutation. */
    fun fieldCareCompleted(actor: CitizenActor) {
        val material = citizenFieldCareItem(actor.plan) ?: return
        val work = actor.plan.worldWork ?: return
        val focus = citizenWorkFocus(work) ?: return
        val npc = actor.npc
        if (!npc.isValid || npc.world.uid != actor.worldId || actor.sheltering || actor.sleeping || actor.workerId in runtime.pendingHarm ||
            (actor.route?.motion?.navigating == true) || !routes.canWorkFrom(actor, work)) return
        val watering = material == Material.WATER_BUCKET
        val at = Location(npc.world, focus.x + .5, focus.y + if (watering) .18 else .35, focus.z + .5)
        npc.world.spawnParticle(if (watering) Particle.SPLASH else Particle.HAPPY_VILLAGER,
            at, if (watering) 8 else 6, .22, if (watering) .08 else .18, .22, if (watering) .015 else 0.0)
        npc.world.playSound(at, if (watering) Sound.ITEM_BUCKET_EMPTY else Sound.ITEM_BONE_MEAL_USE,
            SoundCategory.BLOCKS, .35f, 1.1f)
    }

    /** The native paste waits here while its observed builder is absent, travelling, hurt or sheltering. */
    internal fun constructionReady(cityId: UUID, orderId: UUID): Boolean {
        checkCitizenMainThread()
        if (runtime.closed.get()) return false
        val city = runtime.cityProvider().firstOrNull { it.id == cityId } ?: return false
        return runtime.actors.values.any { constructionBuilder(it, city, orderId) != null }
    }

    internal fun requireConstructionArrival(cityId: UUID, workerId: UUID, orderId: UUID) {
        checkCitizenMainThread()
        val city = runtime.cityProvider().firstOrNull { it.id == cityId } ?: throw CivsRejection("no-city")
        val actor = runtime.actors[workerId] ?: throw CivsRejection("route-unreachable")
        if (actor.cycle.phase != CitizenPhase.DELIVERING ||
            constructionBuilder(actor, city, orderId, requirePlacement = false) == null) throw CivsRejection("route-unreachable")
    }

    /** Cosmetic equipment is never inserted into the resident's durable inventory. */
    internal fun constructionPlaced(cityId: UUID, orderId: UUID, position: CivPosition, material: Material) {
        checkCitizenMainThread()
        if (runtime.closed.get()) return
        val city = runtime.cityProvider().firstOrNull { it.id == cityId } ?: return
        paths.invalidateBlock(city.worldId, position)
        navigation.changed(city.worldId, position)
        worksiteTerrainChanged(city.worldId, position)
        if (material.isAir) return
        for (actor in runtime.actors.values.sortedBy { it.workerId }) {
            val player = constructionBuilder(actor, city, orderId) ?: continue
            actor.npc.setEquipment(EquipmentSlot.HAND, ItemStack(citizenPlacementItem(material) ?: Material.AIR))
            lookAtBlock(player, position)
            player.swingMainHand()
            leisure.speak(actor, CitizenVoiceEvent.WORK)
            break
        }
    }

    fun constructionBuilder(actor: CitizenActor, city: Settlement, orderId: UUID, requirePlacement: Boolean = true): PacketPlayerNpc? {
        val order = city.construction ?: return null
        val resident = city.workers.firstOrNull { it.id == actor.workerId } ?: return null
        val assignment = resident.assignment?.let(city::building) ?: return null
        if (actor.plan.workplace != constructionWorkplaces[orderId]) return null
        if (actor.cityId != city.id || order.id != orderId || resident.assignment != actor.plan.token.assignmentId ||
            !CivsRules.isBuilder(city, assignment) || !CivsRules.builderCanWork(city, assignment) ||
            requirePlacement && order.phase !in setOf(ConstructionPhase.READY_TO_PLACE, ConstructionPhase.PLACING) ||
            !citizenConstructionWorking(actor.plan, actor.cycle.phase, orderId) ||
            actor.sheltering || actor.workerId in runtime.pendingHarm || !actor.npc.isValid || (actor.route?.motion?.navigating == true)) return null
        val player = actor.npc
        if (player.world.uid != city.worldId || player.world.time in 12_000L..23_000L ||
            !citizenChunkReady(player.world, player.location.blockX shr 4, player.location.blockZ shr 4) ||
            !audience.activeAt(city.id, city.worldId, player.location.point(), audience.viewers())) return null
        return player.takeIf { actor.plan.workplace?.let { routes.arrivedAt(actor, city, player.world, it) } == true }
    }

    fun constructionWorkplace(city: Settlement, orderId: UUID): CivPosition? {
        val site = constructionSite(city.id, orderId)?.takeIf { it.orderId == orderId } ?: return null
        val world = Bukkit.getWorld(city.worldId) ?: return null
        constructionWorkplaces[orderId]?.let { point ->
            if (citizenConstructionStanding(city, world, site, point)) return point
            constructionWorkplaces.remove(orderId)
        }
        if (runtime.constructionBudget == 0) return null
        runtime.constructionBudget--
        return citizenConstructionTarget(city, world, site)?.also { constructionWorkplaces[orderId] = it }
    }

    private fun lookAtBlock(player: PacketPlayerNpc, point: CivPosition) {
        val focus = Location(player.world, point.x + 0.5, point.y + 0.5, point.z + 0.5)
        citizenLookDirection(player.location.clone().add(0.0, player.eyeHeight, 0.0), focus).let { (yaw, pitch) -> routes.look(player, yaw, pitch) }
    }
}
