package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.arc.paper.npc.PacketPlayerNpc
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/** Observed meals, tavern service, lessons and rest share one request and posture lifecycle. */
internal class CivsCitizenLife(
    private val runtime: CitizenRuntime,
    private val residentFoods: CivsResidentFoods,
    private val threats: CivsResidentThreats,
    private val tavernFoodSource: (Settlement, CivWorker) -> CivResidentFoodSource?,
    private val residentStepAllowed: (PacketPlayerNpc, Location, Location) -> Boolean,
    private val pickupResidentFood: (UUID, UUID, CivResidentFoodSource) -> CompletableFuture<Settlement>,
    private val finishResidentMeal: (UUID, CivResidentMeal) -> CompletableFuture<Settlement>,
    private val deliverTavernMeal: (UUID, CivTavernDelivery) -> CompletableFuture<Settlement>,
    private val finishResidentLesson: (UUID, CivResidentLessonProof) -> CompletableFuture<Settlement>,
    private val finishResidentRest: (UUID, UUID, Long) -> CompletableFuture<Settlement>,
    private val collisionsOwner: () -> CivsCitizenCollisions,
    private val routesOwner: () -> CivsCitizenRoutes,
    private val workOwner: () -> CivsCitizenWork,
    private val leisureOwner: () -> CivsCitizenLeisure,
    private val logisticsOwner: () -> CivsCitizenLogistics,
    private val equipmentOwner: () -> CivsCitizenEquipment,
) {
    private val collisions get() = collisionsOwner()
    private val routes get() = routesOwner()
    private val workRuntime get() = workOwner()
    private val leisure get() = leisureOwner()
    private val logistics get() = logisticsOwner()
    private val equipment get() = equipmentOwner()
    private val lifeRequests = CivsCitizenRequests<Boolean>(runtime.closed::get)

    fun cancel(actor: CitizenActor) = lifeRequests.cancel(actor.workerId, actor.instanceId)
    fun close() = lifeRequests.close()

    fun stopMeal(actor: CitizenActor) {
        stopTavernService(actor)
        val visit = actor.mealVisit
        if (visit != null) {
            runtime.actors.values.firstOrNull { it.tavernService?.ticket == visit.ticket }?.let(::stopTavernService)
            actor.npc.isUsingMainHand = false
            routes.cancelRoute(actor)
            actor.mealVisit = null
            actor.equipmentItems = null
            actor.npc.setEquipment(EquipmentSlot.HAND, visit.handBefore)
            runtime.cityProvider().firstOrNull { it.id == actor.cityId }?.workers?.firstOrNull { it.id == actor.workerId }
                ?.let { equipment.syncResident(actor.npc, it, actor.plan) }
        }
    }

    private fun retryMeal(actor: CitizenActor, now: Long, reason: String): Boolean {
        stopMeal(actor)
        actor.lifeRetryAt = now + 5_000
        actor.mealOutcome = reason to now
        return false
    }

    private fun tavernKeeperReady(actor: CitizenActor, city: Settlement, guest: CivWorker, building: CivBuilding,
        continuingService: Boolean = false): Boolean = tavernKeeperUnavailable(actor, city, guest, building, continuingService) == null

    fun tavernKeeperUnavailable(actor: CitizenActor, city: Settlement, guest: CivWorker, building: CivBuilding,
        continuingService: Boolean = false): String? {
        val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: return "worker-missing"
        // An owned navigation jump may be between supports; inventory witnesses still require grounded feet.
        val travelling = continuingService && actor.tavernService?.guestId == guest.id && actor.route?.let {
            it.completion == null && (it.motion.navigating || it.motion.searching)
        } == true
        return when {
            actor.cityId != city.id || !CivsTavernMeals.keeper(city, worker, guest, building) -> "domain-ineligible"
            lifeActorUnavailable(actor, requireSupport = !travelling) != null -> "life-unavailable"
            actor.mealVisit != null -> "personal-meal"
            actor.foodSource != null -> "food-source"
            actor.supplyPickup != null -> "pickup-pending"
            residentLifePending(actor) -> "life-pending"
            logistics.collectedPending(actor) -> "collection-pending"
            actor.terrainObstructedAt != null -> "terrain-obstructed"
            actor.cycle.phase in setOf(CitizenPhase.PICKING_UP, CitizenPhase.DELIVERING, CitizenPhase.CLOSED) -> "cycle-unavailable"
            else -> null
        }
    }

    fun tavernServingPoints(seat: CitizenDiningSeat): List<CivPosition> =
        listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1).map { (dx, dz) ->
            CivPosition(seat.block.x + dx, seat.block.y, seat.block.z + dz)
        }

    fun tavernServingPointUnavailable(keeper: CitizenActor, visit: CitizenMealVisit, point: CivPosition): String? = when {
        !visit.building.bounds.contains(point.x, point.y, point.z) -> "outside-building"
        !citizenStandable(keeper.npc.world, point) -> "not-standable"
        !citizenPacketBodyClear(keeper.npc.world, point.point(), false) -> "body-blocked"
        !collisions.idleDestinationFree(keeper, point.location(keeper.npc.world)) -> "resident-reserved"
        else -> null
    }

    private fun tavernFutureFailure(future: CompletableFuture<Settlement>): String = future.handle { _, error ->
        val cause = (error as? CompletionException)?.cause ?: error
        if (future.isCancelled) "cancelled" else (cause as? CivsRejection)?.reason ?: cause?.javaClass?.simpleName ?: "failed"
    }.join()

    private fun stopTavernService(keeper: CitizenActor) {
        val service = keeper.tavernService ?: return
        keeper.tavernService = null
        runtime.actors[service.guestId]?.mealVisit?.takeIf { it.ticket == service.ticket }?.keeperId = null
        routes.cancelRoute(keeper)
        keeper.equipmentItems = null
        runtime.cityProvider().firstOrNull { it.id == keeper.cityId }?.workers?.firstOrNull { it.id == keeper.workerId }
            ?.let { equipment.syncResident(keeper.npc, it, keeper.plan) }
    }

    /** Real tavern food moves through the keeper's bag, then one atomic city save serves the guest. */
    fun serveTavernGuest(keeper: CitizenActor, city: Settlement, worker: CivWorker, world: World, now: Long): Boolean {
        if (!runtime.residentLifeEnabled || keeper.plan.type != BuildingType.TAVERN) { stopTavernService(keeper); return false }
        if (keeper.tavernService == null) {
            val guest = runtime.actors.values.firstOrNull { guest ->
                val visit = guest.mealVisit
                val resident = city.workers.firstOrNull { it.id == guest.workerId }
                visit?.foodSource != null && visit.seatedFrom != null && visit.servedAt == null &&
                    visit.keeperId == null && now >= visit.serviceRetryAt && resident != null &&
                    tavernKeeperReady(keeper, city, resident, visit.building) && mealSeatStillSafe(guest)
            } ?: return false
            val visit = requireNotNull(guest.mealVisit)
            workRuntime.rememberWorkDetour(keeper)
            routes.cancelRoute(keeper)
            leisure.stopCamp(keeper, "tavern-service")
            visit.keeperId = worker.id
            keeper.tavernService = CitizenTavernService(visit.ticket, guest.workerId, requireNotNull(visit.foodSource), now)
        }
        val service = requireNotNull(keeper.tavernService)
        val guest = runtime.actors[service.guestId]
        val visit = guest?.mealVisit?.takeIf { it.ticket == service.ticket }
        val resident = city.workers.firstOrNull { it.id == service.guestId }
        if (visit == null || resident == null || !tavernKeeperReady(keeper, city, resident, visit.building, continuingService = true) ||
            !mealSeatStillSafe(requireNotNull(guest)) || now - service.startedAt > 45_000) {
            keeper.tavernServiceFailure = CitizenTavernServiceFailure(when {
                visit == null || resident == null -> "guest-missing"
                !tavernKeeperReady(keeper, city, resident, visit.building, continuingService = true) ->
                    "keeper-${tavernKeeperUnavailable(keeper, city, resident, visit.building, continuingService = true)}"
                !mealSeatStillSafe(requireNotNull(guest)) -> "seat-${mealSeatUnavailable(guest)}"
                else -> "service-timeout"
            }, now, service.guestId, service.target)
            stopTavernService(keeper)
            return false
        }
        fun failed(reason: String): Boolean {
            keeper.tavernServiceFailure = CitizenTavernServiceFailure(reason, now, service.guestId, service.target)
            visit.serviceRetryAt = now + 5_000
            stopTavernService(keeper)
            return false
        }
        keeper.cycle.deferWork(now)
        runtime.statuses[worker.id] = "serving-guests"
        service.delivery?.let { future ->
            if (!future.isDone) {
                // A city queue may wait while both runtime.actors remain here. Renew only
                // a still-continuous native arrival/body/visibility witness.
                try { validateTavernDelivery(city.id, requireNotNull(service.proof)) }
                catch (_: CivsRejection) { return failed("delivery-witness-lost") }
                service.observedAt = now
                return true
            }
            if (future.isCompletedExceptionally || future.isCancelled) return failed("delivery-${tavernFutureFailure(future)}")
            val saved = future.join()
            val fed = saved.workers.firstOrNull { it.id == guest.workerId } ?: return failed("delivered-guest-missing")
            visit.plan = CivsResidentLife.mealPlan(saved, fed, visit.building.id, residentFoods) ?: return failed("delivered-meal-unavailable")
            visit.servedAt = now
            visit.arrivedAt = now
            CivsDiagnostics.event("tavern_meal_served", "city" to city.id, "keeper" to worker.id,
                "guest" to guest.workerId, "ticket" to service.ticket, "material" to service.proof?.material,
                "amount" to service.proof?.amount, "seat" to visit.seat?.block)
            stopTavernService(keeper)
            return false
        }
        service.pickup?.let { future ->
            if (!future.isDone) return true
            if (future.isCompletedExceptionally || future.isCancelled) return failed("pickup-${tavernFutureFailure(future)}")
            service.pickup = null
            service.loaded = true
            routes.cancelRoute(keeper)
        }
        if (!service.loaded) {
            if (!civsCanReachContainer(keeper.npc.location, service.source.container)) {
                if (keeper.route?.let { it.completion != null || it.timedOut(now) } == true ||
                    keeper.route == null && !routes.startRoute(keeper, world, service.source.standing, now)) return failed("pickup-route-unavailable")
                return true
            }
            routes.cancelRoute(keeper)
            if (residentFoods.available(city, worker, visit.plan.material) == 0) {
                service.pickup = try { pickupResidentFood(city.id, worker.id,
                    service.source.copy(servingFor = guest.workerId, servingTicket = service.ticket)) }
                    catch (error: Exception) { CompletableFuture.failedFuture(error) }
                return true
            }
            service.loaded = true
        }
        val servingWorker = city.workers.firstOrNull { it.id == worker.id } ?: return failed("keeper-missing")
        val amount = minOf(visit.plan.amount, residentFoods.available(city, servingWorker, visit.plan.material))
        if (amount == 0) return failed("keeper-food-missing")
        val seat = requireNotNull(visit.seat)
        val target = service.target ?: tavernServingPoints(seat)
            .filter { tavernServingPointUnavailable(keeper, visit, it) == null }
            .minByOrNull { keeper.npc.location.distanceSquared(it.location(world)) }
            ?.also { service.target = it } ?: return failed("no-serving-position")
        keeper.npc.setEquipment(EquipmentSlot.HAND, ItemStack(Material.valueOf(visit.plan.material)))
        if (!citizenArrived(keeper.npc.location, target.location(world))) {
            if (keeper.route?.let { it.completion != null || it.timedOut(now) } == true ||
                keeper.route == null && !routes.startRoute(keeper, world, target, now)) return failed("serving-route-unavailable")
            return true
        }
        routes.cancelRoute(keeper)
        keeper.npc.setEquipment(EquipmentSlot.HAND, ItemStack(Material.valueOf(visit.plan.material)))
        if (!threats.visible(keeper.npc.location.add(0.0, keeper.npc.eyeHeight, 0.0), seat.feet.clone().add(0.0, 1.4, 0.0))) return failed("guest-not-visible")
        if (service.arrivedAt == null || now - service.observedAt > 2_000) service.arrivedAt = now
        service.observedAt = now
        if (now - requireNotNull(service.arrivedAt) >= 1_000) {
            keeper.npc.swingMainHand()
            val proof = CivTavernDelivery(service.ticket, visit.building.id, worker.id, guest.workerId,
                CivsEquipment.slots(servingWorker), CivsEquipment.slots(resident), resident.life?.meals ?: 0L,
                visit.plan.material, amount)
            service.proof = proof
            service.delivery = try { deliverTavernMeal(city.id, proof) }
                catch (error: Exception) { CompletableFuture.failedFuture(error) }
        }
        return true
    }

    internal fun validateTavernPickup(cityId: UUID, keeperId: UUID, guestId: UUID, buildingId: UUID, material: String,
        expectedTicket: UUID? = null) {
        check(Bukkit.isPrimaryThread()) { "ArcCivs resident access must be on the server thread" }
        val keeper = runtime.actors[keeperId] ?: throw CivsRejection("stale-meal")
        val service = keeper.tavernService ?: throw CivsRejection("stale-meal")
        val guest = runtime.actors[guestId] ?: throw CivsRejection("stale-meal")
        val visit = guest.mealVisit ?: throw CivsRejection("stale-meal")
        if (keeper.cityId != cityId || guest.cityId != cityId || service.guestId != guestId ||
            expectedTicket != null && service.ticket != expectedTicket ||
            service.ticket != visit.ticket || service.source.buildingId != buildingId || service.source.material != material ||
            !lifeActorReady(keeper) || !mealSeatStillSafe(guest) || runtime.residentObservedAt - visit.observedAt > 2_000 ||
            !civsCanReachContainer(keeper.npc.location, service.source.container)) throw CivsRejection("stale-meal")
    }

    internal fun validateTavernDelivery(cityId: UUID, proof: CivTavernDelivery) {
        check(Bukkit.isPrimaryThread()) { "ArcCivs resident access must be on the server thread" }
        val keeper = runtime.actors[proof.keeperId] ?: throw CivsRejection("stale-meal")
        val service = keeper.tavernService ?: throw CivsRejection("stale-meal")
        val guest = runtime.actors[proof.guestId] ?: throw CivsRejection("stale-meal")
        val visit = guest.mealVisit ?: throw CivsRejection("stale-meal")
        if (keeper.cityId != cityId || guest.cityId != cityId || service.proof != proof || service.ticket != visit.ticket ||
            visit.servedAt != null || !lifeActorReady(keeper) || !mealSeatStillSafe(guest) ||
            runtime.residentObservedAt - visit.observedAt > 2_000 || runtime.residentObservedAt - service.observedAt > 2_000 ||
            service.arrivedAt?.let { runtime.residentObservedAt - it >= 1_000 } != true ||
            service.target?.let { citizenArrived(keeper.npc.location, it.location(keeper.npc.world)) } != true ||
            !threats.visible(keeper.npc.location.add(0.0, keeper.npc.eyeHeight, 0.0),
                requireNotNull(visit.seat).feet.clone().add(0.0, 1.4, 0.0))) throw CivsRejection("stale-meal")
    }

    private fun diningSeat(actor: CitizenActor, building: CivBuilding, world: World): CitizenDiningSeat? {
        if (runtime.actors.values.count { other -> other !== actor && other.cityId == actor.cityId && other.npc.isValid &&
                other.mealVisit?.plan?.buildingId == building.id } >= 6) return null
        val candidates = citizenDiningCandidates(building)
        if (candidates.isEmpty()) return null
        val occupied = collisions.shelterOccupied(actor)
        val reserved = runtime.actors.values.mapNotNull { other -> other.takeIf { it !== actor && it.worldId == actor.worldId }
            ?.mealVisit?.seat?.block }.toSet()
        // Existing camp admission bounds this to two residents, twelve candidate reads each.
        repeat(minOf(12, candidates.size)) {
            val point = candidates[Math.floorMod(actor.diningSearchIndex++, candidates.size)]
            if (point !in reserved) citizenDiningSeat(world, building, point, actor.npc.scale) { body ->
                occupied.none(body::overlaps)
            }?.let { return it }
        }
        return null
    }

    fun mealSeatStillSafe(actor: CitizenActor): Boolean = mealSeatUnavailable(actor) == null

    fun mealSeatUnavailable(actor: CitizenActor): String? {
        val visit = actor.mealVisit ?: return "meal-missing"
        val seat = visit.seat ?: return "seat-missing"
        val from = visit.seatedFrom ?: return "not-seated"
        return when {
            !actor.npc.isValid -> "actor-invalid"
            !actor.npc.isSeated -> "not-seated"
            actor.npc.scale != seat.scale -> "scale-changed"
            !(actor.npc.location.distanceSquared(seat.feet) < .0001) -> "position-changed"
            runtime.cityProvider().firstOrNull { it.id == actor.cityId }?.building(visit.plan.buildingId) != visit.building -> "building-changed"
            !citizenDiningSeatClear(actor.npc.world, seat) -> "seat-blocked"
            !citizenDiningStandingClear(actor.npc.world, from, actor.npc.scale) -> "approach-blocked"
            !citizenDiningTransitionClear(actor.npc.world, seat, from, actor.npc.scale) -> "transition-blocked"
            !residentStepAllowed(actor.npc, seat.feet, seat.feet) -> "step-denied"
            else -> null
        }
    }

    /** Return only to the checked adjacent approach or stand on this chair; never search another room. */
    fun standFromMeal(actor: CitizenActor): Boolean {
        val visit = actor.mealVisit ?: return true
        val origin = visit.seatedFrom ?: return true
        visit.seatedFrom = null
        visit.arrivedAt = null
        visit.observedAt = 0
        val npc = actor.npc
        if (!npc.isValid || !npc.isSeated) return true // An authoritative move already ended this posture.
        val seat = requireNotNull(visit.seat)
        val occupied = collisions.shelterOccupied(actor)
        fun safe(at: Location) = citizenDiningStandingClear(npc.world, at, npc.scale) &&
            occupied.none(diningStandingBounds(at, npc.scale)::overlaps) &&
            citizenDiningTransitionClear(npc.world, seat, at, npc.scale) && residentStepAllowed(npc, at, at)
        val onChair = Location(npc.world, seat.block.x + .5, seat.block.y + 1.0, seat.block.z + .5, npc.bodyYaw, 0f)
        val destination = origin.takeIf(::safe) ?: onChair.takeIf(::safe)
        npc.stand()
        if (destination == null) {
            // A newly enclosed chair has no valid standing body. Retire its visual actor
            // instead of intersecting the obstruction or jumping to a remote recovery point.
            npc.remove()
            return false
        }
        npc.teleport(destination)
        return true
    }

    /** Short observed eating session; neither walking nor waiting grants a meal. */
    fun eatMeal(actor: CitizenActor, city: Settlement, worker: CivWorker, world: World, now: Long): Boolean {
        if (!runtime.residentLifeEnabled || actor.cycle.phase in setOf(CitizenPhase.PICKING_UP, CitizenPhase.DELIVERING) ||
            worker.cargo != null || actor.plan.courier?.carrying == true) { stopMeal(actor); return false }
        if (residentLifePending(actor)) {
            actor.cycle.deferWork(now)
            runtime.statuses[worker.id] = if (actor.mealVisit != null) "eating" else if (actor.mentorVisit != null) "learning" else "campfire"
            return true
        }
        if (now < actor.lifeRetryAt) return false
        val fallback = city.buildings.firstOrNull { it.type == BuildingType.TOWN_HALL }?.id
        var visit = actor.mealVisit
        if (visit == null) {
            val at = actor.npc.location
            if (!citizenPacketSupported(world, at.point()) || !citizenPacketBodyClear(world, at.point(), false)) return false
            val source = if (now >= actor.mealVenueRetryAt && CivsResidents.hunger(worker) <= 14)
                tavernFoodSource(city, worker) else null
            val tavern = source?.buildingId?.let(city::building)?.takeIf { building ->
                runtime.actors.values.any { tavernKeeperReady(it, city, worker, building) }
            }
            val seat = tavern?.let { diningSeat(actor, it, world) }
            val definition = source?.material?.let(residentFoods::definition)
            val order = if (seat != null && tavern != null && source != null && definition != null)
                CivResidentMeal(city.revision, worker.id, tavern.id, worker.life?.meals ?: 0L,
                    if (source.material == "BREAD") CivResidentMealSource.CARRIED_BREAD else CivResidentMealSource.CARRIED_FOOD,
                    minOf(CivsResidentFood.BATCH, (15 - CivsResidents.hunger(worker) + definition.nutrition - 1) / definition.nutrition),
                    source.material, definition.nutrition, definition.consumeMillis) else null
            val plan = order ?: CivsResidentLife.mealPlan(city, worker, fallback, residentFoods) ?: return false
            val building = city.building(plan.buildingId) ?: return false
            val target = seat?.approach?.takeIf { order != null } ?: CivPosition(at.blockX, at.blockY, at.blockZ)
            workRuntime.rememberWorkDetour(actor)
            stopMeal(actor)
            routes.cancelRoute(actor)
            leisure.stopCamp(actor, "meal")
            val hand = CivsEquipment.slots(worker)[4]?.stack() ?: ItemStack(citizenTool(actor.plan) ?: Material.AIR)
            visit = CitizenMealVisit(plan, target, now, building, hand, seat = seat.takeIf { order != null },
                foodSource = source.takeIf { order != null })
            actor.mealVisit = visit
        } else if (visit.foodSource == null || visit.servedAt != null) {
            val current = CivsResidentLife.mealPlan(city, worker, visit.plan.buildingId, residentFoods)
            if (current == null || visit.plan.copy(cityRevision = current.cityRevision) != current) {
                stopMeal(actor)
                return false
            }
        }
        val session = requireNotNull(visit)
        actor.cycle.deferWork(now)
        if (now - session.startedAt > 60_000 || !citizenStandable(world, session.target) ||
            session.seat?.let { !citizenDiningSeatClear(world, it) } == true) {
            if (session.building.type == BuildingType.TAVERN) actor.mealVenueRetryAt = now + 30_000
            return retryMeal(actor, now, "session-unavailable")
        }
        if (session.seatedFrom == null && !citizenArrived(actor.npc.location, session.target.location(world))) {
            runtime.statuses[worker.id] = "moving-to-tavern"
            if (actor.route?.let { it.completion != null || it.timedOut(now) } == true ||
                actor.route == null && !routes.startRoute(actor, world, session.target, now)) {
                actor.mealVenueRetryAt = now + 30_000
                return retryMeal(actor, now, "tavern-unavailable")
            }
            return true
        }
        if (session.seat != null && session.seatedFrom == null) {
            val from = actor.npc.location
            if (!citizenDiningStandingClear(world, from, actor.npc.scale) ||
                !citizenDiningTransitionClear(world, session.seat, from, actor.npc.scale)) return retryMeal(actor, now, "seat-unavailable")
            routes.cancelRoute(actor)
            session.seatedFrom = from
            session.seatedAt = now
            actor.npc.sit(session.seat.feet)
            CivsDiagnostics.event("tavern_guest_seated", "city" to city.id, "guest" to worker.id,
                "ticket" to session.ticket, "seat" to session.seat.block, "yaw" to session.seat.feet.yaw)
        }
        if (session.foodSource != null && session.servedAt == null) {
            session.observedAt = now
            runtime.statuses[worker.id] = "waiting"
            actor.npc.setEquipment(EquipmentSlot.HAND, session.handBefore)
            actor.npc.isUsingMainHand = false
            if (session.seatedAt?.let { now - it > 45_000 } == true) {
                actor.mealVenueRetryAt = now + 30_000
                return retryMeal(actor, now, "service-unavailable")
            }
            return true
        }
        if (session.arrivedAt == null || now - session.observedAt > 2_000) {
            if (session.seat == null) routes.cancelRoute(actor)
            session.arrivedAt = now
        }
        session.observedAt = now
        runtime.statuses[worker.id] = "eating"
        actor.npc.setEquipment(EquipmentSlot.HAND, ItemStack(Material.valueOf(session.plan.material)))
        actor.npc.isUsingMainHand = true
        if (now >= session.gestureAt) {
            world.playSound(actor.npc.location, Sound.ENTITY_GENERIC_EAT, org.bukkit.SoundCategory.NEUTRAL, .35f, 1f)
            session.gestureAt = now + 400
        }
        if (now - requireNotNull(session.arrivedAt) >= session.plan.durationMillis) {
            submitResidentLife(actor, meal = true) { finishResidentMeal(city.id, session.plan) }
        }
        return true
    }

    internal fun validateResidentMeal(cityId: UUID, plan: CivResidentMeal) {
        check(Bukkit.isPrimaryThread()) { "ArcCivs resident access must be on the server thread" }
        val actor = runtime.actors[plan.workerId] ?: throw CivsRejection("stale-meal")
        val visit = actor.mealVisit ?: throw CivsRejection("stale-meal")
        val now = runtime.residentObservedAt
        if (actor.cityId != cityId || visit.plan != plan || !lifeActorReady(actor) ||
            runtime.cityProvider().firstOrNull { it.id == cityId }?.building(plan.buildingId) != visit.building ||
            visit.foodSource != null && visit.servedAt == null ||
            visit.arrivedAt?.let { now - it >= plan.durationMillis } != true || now - visit.observedAt > 2_000 ||
            (if (visit.seat != null) !mealSeatStillSafe(actor)
            else !citizenArrived(actor.npc.location, visit.target.location(actor.npc.world)))) throw CivsRejection("stale-meal")
    }

    fun lifeActorReady(actor: CitizenActor): Boolean = lifeActorUnavailable(actor) == null

    fun lifeActorUnavailable(actor: CitizenActor, requireSupport: Boolean = true): String? = when {
        runtime.closed.get() -> "closed"
        !actor.npc.isValid -> "actor-invalid"
        actor.workerId in runtime.pendingHarm -> "injury-pending"
        actor.sleeping -> "sleeping"
        actor.sheltering -> "sheltering"
        actor.attacker != null -> "combat"
        actor.npc.world.time in 12_000L..23_000L -> "night"
        actor.mealVisit?.seatedFrom != null -> mealSeatUnavailable(actor)
        requireSupport && !citizenPacketSupported(actor.npc.world, actor.npc.location.point()) -> "unsupported"
        !citizenPacketBodyClear(actor.npc.world, actor.npc.location.point(), false) -> "body-blocked"
        else -> null
    }

    fun residentLifePending(actor: CitizenActor): Boolean = lifeRequests.pending(actor.workerId, actor.instanceId)
    private fun submitResidentLife(actor: CitizenActor, meal: Boolean = false, action: () -> CompletableFuture<Settlement>) {
        if (residentLifePending(actor)) return
        lifeRequests.start(actor.workerId, actor.instanceId, meal) {
            if (meal) actor.mealOutcome = "pending" to runtime.residentObservedAt
            action()
        }
    }

    fun drainResidentLife(now: Long) {
        lifeRequests.tick({ runtime.actors[it]?.instanceId }) { completion ->
            val actor = runtime.actors.getValue(completion.workerId)
            stopMeal(actor)
            actor.mentorVisit = null
            val error = (completion.error as? CompletionException)?.cause ?: completion.error
            if (completion.payload) actor.mealOutcome =
                (if (error == null) "accepted" else (error as? CivsRejection)?.reason ?: "failed") to now
            if (error != null) {
                actor.lifeRetryAt = now + 5_000
                if (error !is CivsRejection) runtime.logFailure(actor, "resident-life", error)
            }
        }
    }

    /** A child follows one nearby experienced resident; no work or inventory is credited. */
    fun mentor(actor: CitizenActor, city: Settlement, child: CivWorker, world: World, now: Long): Boolean {
        if (!runtime.residentLifeEnabled || now < actor.lifeRetryAt || residentLifePending(actor) ||
            child.life?.education.orEmpty().values.sum() >= CivsResidentLife.MAX_EDUCATION_XP) return false
        val age = child.childMinutes ?: return false
        val interval = if (CivsResidentLife.needs(city, child).happiness >= 70) 3 else 5
        if (age == 0 || age % interval != 0 || child.life?.lastLessonAge == age) return false
        var session = actor.mentorVisit
        val teacher = session?.teacherId?.let(runtime.actors::get)?.takeIf(::lifeActorReady) ?: runtime.actors.values.firstOrNull { other ->
            if (other === actor || other.cityId != city.id || !lifeActorReady(other) ||
                other.npc.location.distanceSquared(actor.npc.location) > 24.0 * 24.0) return@firstOrNull false
            val worker = city.workers.firstOrNull { it.id == other.workerId } ?: return@firstOrNull false
            val building = worker.assignment?.let(city::building) ?: return@firstOrNull false
            val at = other.npc.location
            val peacefulPlace = other.social.visit?.arrivedAt != null || city.buildings.any { home ->
                home.phase == BuildingPhase.ACTIVE && home.type in setOf(BuildingType.HOUSE, BuildingType.TAVERN,
                    BuildingType.TOWN_HALL, BuildingType.LIBRARY, BuildingType.UNIVERSITY) &&
                    home.bounds.contains(at.blockX, at.blockY, at.blockZ)
            }
            !CivsResidents.isChild(worker) && peacefulPlace &&
                CivsResidents.skillExperience(worker, CivsRules.effectiveWorkType(city, building).name) >= 10
        } ?: run { actor.mentorVisit = null; return false }
        if (session?.teacherId != teacher.workerId || session.age != age) {
            if (runtime.campBudget == 0) return false
            runtime.campBudget--
            val at = teacher.npc.location
            val target = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).map { (x, z) ->
                CivPosition(at.blockX + x, at.blockY, at.blockZ + z)
            }.firstOrNull { citizenStandable(world, it) && collisions.idleDestinationFree(actor, it.location(world)) && citizenControlClear(city, it) }
                ?: return false
            routes.cancelRoute(actor)
            session = CitizenMentorVisit(teacher.workerId, age, target, now)
            actor.mentorVisit = session
        }
        val visit = requireNotNull(session)
        if (now - visit.startedAt > 30_000 || teacher.npc.location.distanceSquared(visit.target.location(world)) > 16) {
            actor.mentorVisit = null; routes.cancelRoute(actor); actor.lifeRetryAt = now + 5_000; return false
        }
        if (!citizenArrived(actor.npc.location, visit.target.location(world))) {
            visit.arrivedAt = null
            if (actor.route == null) routes.startRoute(actor, world, visit.target, now, idleAnchor = visit.target)
            runtime.statuses[child.id] = "learning"
            return true
        }
        if (visit.arrivedAt == null || now - visit.observedAt > 2_000) { routes.cancelRoute(actor); visit.arrivedAt = now }
        visit.observedAt = now
        runtime.statuses[child.id] = "learning"
        if (now - requireNotNull(visit.arrivedAt) >= 4_000) {
            val teacherWorker = city.workers.first { it.id == teacher.workerId }
            val profession = teacherWorker.assignment?.let(city::building)?.let { CivsRules.effectiveWorkType(city, it) } ?: return false
            val proof = CivResidentLessonProof(city.revision, child.id, teacher.workerId, profession, age,
                child.life?.awakeMinutes ?: 0, actor.npc.location.let { CivPosition(it.blockX, it.blockY, it.blockZ) },
                teacher.npc.location.let { CivPosition(it.blockX, it.blockY, it.blockZ) })
            submitResidentLife(actor) { finishResidentLesson(city.id, proof) }
        }
        return true
    }

    internal fun validateResidentLesson(cityId: UUID, proof: CivResidentLessonProof) {
        check(Bukkit.isPrimaryThread()) { "ArcCivs resident access must be on the server thread" }
        val child = runtime.actors[proof.childId] ?: throw CivsRejection("stale-lesson")
        val teacher = runtime.actors[proof.teacherId] ?: throw CivsRejection("stale-lesson")
        val visit = child.mentorVisit ?: throw CivsRejection("stale-lesson")
        fun at(actor: CitizenActor, point: CivPosition) = actor.npc.location.let { it.blockX == point.x && it.blockY == point.y && it.blockZ == point.z }
        if (child.cityId != cityId || teacher.cityId != cityId || visit.teacherId != teacher.workerId ||
            !lifeActorReady(child) || !lifeActorReady(teacher) || !at(child, proof.childPosition) || !at(teacher, proof.teacherPosition) ||
            visit.arrivedAt?.let { runtime.residentObservedAt - it >= 4_000 } != true || runtime.residentObservedAt - visit.observedAt > 2_000 ||
            !threats.visible(child.npc.location.add(0.0, child.npc.eyeHeight, 0.0), teacher.npc.location.add(0.0, teacher.npc.eyeHeight, 0.0)))
            throw CivsRejection("stale-lesson")
    }

    fun creditRest(actor: CitizenActor, city: Settlement, worker: CivWorker, tick: Int) {
        if (!runtime.residentLifeEnabled || residentLifePending(actor)) return
        val visit = actor.social.visit ?: return
        val began = visit.conversationAt ?: visit.arrivedAt ?: return
        if (tick - began < 100 || actor.restVisit == visit.startedAt || !lifeActorReady(actor)) return
        val minute = worker.life?.awakeMinutes ?: return
        actor.restVisit = visit.startedAt
        submitResidentLife(actor) { finishResidentRest(city.id, worker.id, minute) }
    }

    internal fun validateResidentRest(cityId: UUID, workerId: UUID) {
        check(Bukkit.isPrimaryThread()) { "ArcCivs resident access must be on the server thread" }
        val actor = runtime.actors[workerId] ?: throw CivsRejection("stale-rest")
        val visit = actor.social.visit ?: throw CivsRejection("stale-rest")
        val began = visit.conversationAt ?: visit.arrivedAt ?: throw CivsRejection("stale-rest")
        if (actor.cityId != cityId || !lifeActorReady(actor) || Bukkit.getCurrentTick() - began < 100 ||
            !citizenArrived(actor.npc.location, visit.seat.location(actor.npc.world))) throw CivsRejection("stale-rest")
    }

    fun servingTavernGuests(keeper: CitizenActor, city: Settlement): Boolean {
        if (keeper.plan.type != BuildingType.TAVERN) return false
        val assignment = keeper.plan.token.assignmentId ?: return false
        val tavern = city.building(assignment) ?: return false
        return runtime.actors.values.any { guest ->
            guest !== keeper && guest.cityId == city.id && guest.npc.isValid &&
                guest.mealVisit?.let { it.plan.buildingId == tavern.id && it.arrivedAt != null } == true &&
                guest.npc.location.let { at -> tavern.bounds.contains(at.blockX, at.blockY, at.blockZ) ||
                    citizenNearEndpoint(CitizenPoint(at.x, at.y, at.z), tavern.entrance) }
        }
    }

}

internal data class CitizenTavernServiceFailure(val reason: String, val at: Long, val guest: UUID, val target: CivPosition?)

internal data class CitizenMealVisit(var plan: CivResidentMeal, val target: CivPosition, val startedAt: Long, val building: CivBuilding,
    val handBefore: ItemStack,
    var arrivedAt: Long? = null, var observedAt: Long = 0, var gestureAt: Long = 0,
    val seat: CitizenDiningSeat? = null, var seatedFrom: Location? = null,
    val foodSource: CivResidentFoodSource? = null, val ticket: UUID = UUID.randomUUID(),
    var seatedAt: Long? = null, var keeperId: UUID? = null, var servedAt: Long? = null,
    var serviceRetryAt: Long = 0)
internal data class CitizenMentorVisit(val teacherId: UUID, val age: Int, val target: CivPosition, val startedAt: Long,
    var arrivedAt: Long? = null, var observedAt: Long = 0)
