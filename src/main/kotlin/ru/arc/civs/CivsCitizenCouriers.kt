package ru.arc.civs

import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.World
import java.util.UUID
import kotlin.math.roundToInt

/** Observed producer meetings and courier shipment approaches. */
internal class CivsCitizenCouriers(
    private val runtime: CitizenRuntime,
    private val routesOwner: () -> CivsCitizenRoutes,
    private val leisureOwner: () -> CivsCitizenLeisure,
    private val workOwner: () -> CivsCitizenWork,
    private val logisticsOwner: () -> CivsCitizenLogistics,
    private val paths: CivsPaths,
    private val residentFoods: CivsResidentFoods,
    private val craftPlan: (Settlement, CivWorker) -> CivCraftJob?,
    private val navigationSettings: CivsNavigationSettings,
) {
    private val routes get() = routesOwner()
    private val leisure get() = leisureOwner()
    private val workRuntime get() = workOwner()
    private val logistics get() = logisticsOwner()

    /** The position estimates a trip; the producer remains free to walk and work. */
    internal fun courierSources(cityId: UUID, courierId: UUID): Map<UUID, CivPosition> {
        checkCitizenMainThread()
        val city = runtime.cityProvider().firstOrNull { it.id == cityId && it.pendingDeletion == null } ?: return emptyMap()
        if (city.inventory != null) return emptyMap()
        return city.workers.mapNotNull { worker ->
            val source = runtime.actors[worker.id]?.takeIf { it.workerId != courierId && sourceReady(it, city, worker) &&
                sourceOffersCargo(it, city, worker) } ?: return@mapNotNull null
            if (source.courierRetry.exhausted && runtime.residentObservedAt >= source.courierRetryAfter) source.courierRetry = CitizenRetry()
            if (!source.courierRetry.ready(runtime.residentObservedAt) || runtime.actors.values.any { other ->
                    other.workerId != courierId && other.cityId == cityId && other.plan.courier?.let {
                        !it.carrying && it.cargo.sourceWorkerId == worker.id
                    } == true && other.cycle.phase !in setOf(CitizenPhase.PAUSED, CitizenPhase.CLOSED)
                }) return@mapNotNull null
            val feet = source.npc.location
            worker.id to CivPosition(feet.blockX, kotlin.math.floor(feet.y + .125).toInt(), feet.blockZ)
        }.toMap()
    }

    /** A missing actor can receive work before spawning; observed feet improve the trip estimate. */
    internal fun courierPosition(cityId: UUID, workerId: UUID): CivPosition? {
        checkCitizenMainThread()
        val actor = runtime.actors[workerId]?.takeIf { it.cityId == cityId && it.npc.isValid } ?: return null
        val feet = actor.npc.location
        return CivPosition(feet.blockX, kotlin.math.floor(feet.y + .125).toInt(), feet.blockZ)
    }

    internal fun courierUnavailable(cityId: UUID, workerId: UUID): Boolean {
        checkCitizenMainThread()
        val actor = runtime.actors[workerId]?.takeIf { it.cityId == cityId } ?: return false
        return !actor.npc.isValid || actor.sleeping || actor.sheltering || actor.attacker != null ||
            workerId in runtime.pendingHarm || actor.foodSource != null || actor.supplyPickup != null
    }

    /** One city-wide observation; the dispatcher reserves an offered producer for one courier. */
    internal fun courierSupplySources(cityId: UUID, retainedSources: Set<UUID> = emptySet()): Map<UUID, CivPosition> {
        checkCitizenMainThread()
        val city = runtime.cityProvider().firstOrNull { it.id == cityId && it.pendingDeletion == null } ?: return emptyMap()
        if (city.inventory != null) return emptyMap()
        return city.workers.mapNotNull { worker ->
            val actor = runtime.actors[worker.id]?.takeIf { sourceReady(it, city, worker) &&
                (worker.id in retainedSources || sourceOffersCargo(it, city, worker)) }
                ?: return@mapNotNull null
            if (actor.courierRetry.exhausted && runtime.residentObservedAt >= actor.courierRetryAfter) actor.courierRetry = CitizenRetry()
            if (!actor.courierRetry.ready(runtime.residentObservedAt)) return@mapNotNull null
            val feet = actor.npc.location
            worker.id to CivPosition(feet.blockX, kotlin.math.floor(feet.y + .125).toInt(), feet.blockZ)
        }.toMap()
    }

    /** Acceptance retains the observed cargo, independently of the producer's current position or next job. */
    internal fun courierSourceCurrent(cityId: UUID, cargo: CivCargo): Boolean {
        checkCitizenMainThread()
        val city = runtime.cityProvider().firstOrNull { it.id == cityId && it.pendingDeletion == null } ?: return false
        if (city.inventory != null) return false
        val worker = city.workers.firstOrNull { it.id == cargo.sourceWorkerId } ?: return false
        val source = runtime.actors[worker.id] ?: return false
        return worker.assignment == cargo.sourceBuildingId && sourceReady(source, city, worker) &&
            (CivsLogistics.workerExcess(city, worker, foods = residentFoods)[cargo.material] ?: 0L) >= cargo.amount
    }

    /** Rechecked after a city wait: a barrel arrival can never authorize a remote bag debit. */
    internal fun requireCargoPickupArrival(cityId: UUID, workerId: UUID, cargo: CivCargo) {
        checkCitizenMainThread()
        val city = runtime.cityProvider().firstOrNull { it.id == cityId } ?: throw CivsRejection("no-city")
        if (city.inventory != null) throw CivsRejection("stale-delivery")
        val courier = runtime.actors[workerId]?.takeIf { it.cityId == cityId && it.npc.isValid } ?: throw CivsRejection("route-unreachable")
        val sourceId = cargo.sourceWorkerId ?: throw CivsRejection("stale-delivery")
        val source = runtime.actors[sourceId] ?: throw CivsRejection("route-unreachable")
        val worker = city.workers.firstOrNull { it.id == sourceId } ?: throw CivsRejection("stale-delivery")
        val offered = courier.plan.courier?.takeIf { !it.carrying }?.cargo
        if (runtime.closed.get() || courier.workerId in runtime.pendingHarm || courier.cycle.phase != CitizenPhase.PICKING_UP ||
            offered == null || cargo.amount !in 1..offered.amount || offered.copy(amount = cargo.amount) != cargo || courier.sheltering ||
            courier.attacker != null || courier.foodSource != null || courier.supplyPickup != null ||
            city.workers.firstOrNull { it.id == workerId }?.assignment != cargo.warehouseId ||
            worker.assignment != cargo.sourceBuildingId || !courierSourceCurrent(cityId, cargo) ||
            !courierMeetsSource(courier, source))
            throw CivsRejection("route-unreachable")
    }

    private fun sourceReady(actor: CitizenActor, city: Settlement, worker: CivWorker): Boolean {
        if (actor.cityId != city.id || worker.id != actor.workerId || worker.cargo != null || CivsResidents.isChild(worker) ||
            actor.plan.pendingStockDelivery != null ||
            !actor.npc.isValid || actor.npc.world.uid != city.worldId || actor.sleeping || actor.sheltering ||
            actor.attacker != null || actor.workerId in runtime.pendingHarm || actor.supplyPickup != null || actor.foodSource != null ||
            logistics.collectedPending(actor) || actor.social.visit != null || actor.npc.world.time in 12_000L..23_000L ||
            actor.cycle.phase in setOf(CitizenPhase.READY, CitizenPhase.DELIVERING, CitizenPhase.PICKING_UP, CitizenPhase.CLOSED)) return false
        val feet = actor.npc.location
        // Actual collision/support follows fractional farm paths and stair steps; it is not an integer floor test.
        return citizenChunkReady(actor.npc.world, feet.blockX shr 4, feet.blockZ shr 4) &&
            citizenPacketBodyClear(actor.npc.world, feet.point(), false) &&
            citizenPacketCampfireClear(actor.npc.world, feet.point()) &&
            (citizenPacketSupported(actor.npc.world, feet.point()) || actor.route?.motion?.let { !it.searching && it.navigating } == true)
    }

    fun sourceOffersCargo(actor: CitizenActor, city: Settlement, worker: CivWorker): Boolean {
        val excess = CivsLogistics.workerExcess(city, worker, craftPlan(city, worker)?.inputs.orEmpty(), residentFoods)
        if (excess.isEmpty()) return false
        if (actor.outputFullPause?.let { it.buildingId == worker.assignment } == true) return true
        if (CivsAutonomy.legacyStock(city)) return true
        val searchingMine = actor.plan.type == BuildingType.QUARRY && actor.plan.idleReason == "searching-work" &&
            actor.plan.token.assignmentId == worker.assignment && worker.assignment?.let(city::building)?.type == BuildingType.QUARRY
        // Bounded quarry discovery is active work, not a reason to carry a single block home.
        // Finish useful work while a stack accumulates; idle or blocked producers return a partial stack.
        return actor.plan.worldWork == null && !searchingMine || actor.cycle.phase == CitizenPhase.PAUSED ||
            excess.values.any { it >= CivsLogistics.MAX_CARGO } || !citizenHasCapacity(city, actor.plan)
    }

    fun courierMeetsSource(courier: CitizenActor, source: CitizenActor): Boolean {
        if (courier.npc.world.uid != source.npc.world.uid ||
            courier.npc.location.distanceSquared(source.npc.location) > 2.5 * 2.5 ||
            !citizenPacketBodyClear(courier.npc.world, courier.npc.location.point(), false)) return false
        return courierInteractionClear(courier.npc.location.clone().add(0.0, courier.npc.eyeHeight, 0.0), source)
    }

    private fun courierInteractionClear(eye: Location, source: CitizenActor): Boolean {
        val aim = source.npc.location.clone().add(0.0, source.npc.height * .5, 0.0)
        if (!citizenBlockTraceReady(source.npc.world, eye, CivPosition(aim.blockX, aim.blockY, aim.blockZ))) return false
        val delta = aim.toVector().subtract(eye.toVector())
        val distance = delta.length()
        return distance < .001 || source.npc.world.rayTraceBlocks(eye, delta.multiply(1.0 / distance), distance,
            FluidCollisionMode.ALWAYS, true) == null
    }

    fun releaseCourierSource(courier: CitizenActor, now: Long, success: Boolean = false) {
        if (courier.courierPickupStartedAt == null) return
        courier.courierPickupStartedAt = null
        courier.courierSourceObserved = null
        courier.courierTargetAfter = 0L
        courier.plan.workplace?.let(courier.targets::remove)
        val source = courier.plan.courier?.cargo?.sourceWorkerId?.let(runtime.actors::get) ?: return
        if (success) source.courierRetry = CitizenRetry()
        else {
            source.courierRetry.fail(now)
            if (source.courierRetry.exhausted) source.courierRetryAfter = now + 60_000L
        }
    }

    /** Retarget at most once a second; source work and its route are never owned by the courier. */
    fun trackCourierSource(courier: CitizenActor, city: Settlement, now: Long): Boolean {
        val plan = courier.plan.courier?.takeIf { !it.carrying && it.cargo.sourceWorkerId != null } ?: return true
        // A durable callback rechecks the actual encounter after its city wait. Keep its ticket until completion.
        if (courier.cycle.phase == CitizenPhase.PICKING_UP) return true
        val source = runtime.actors[plan.cargo.sourceWorkerId]
        val worker = city.workers.firstOrNull { it.id == plan.cargo.sourceWorkerId }
        if (courier.cycle.phase == CitizenPhase.PAUSED) {
            releaseCourierSource(courier, now)
            courier.cycle.retry(now)
            if (courier.cycle.phase == CitizenPhase.PAUSED) return false
        }
        val fresh = courier.courierPickupStartedAt == null
        val started = courier.courierPickupStartedAt ?: now.also { courier.courierPickupStartedAt = it }
        if (courier.sheltering || courier.attacker != null ||
            courier.npc.world.time in 12_000L..23_000L || now - started >= 90_000L || source == null || worker == null ||
            city.workers.firstOrNull { it.id == courier.workerId }?.assignment != plan.cargo.warehouseId ||
            worker.assignment != plan.cargo.sourceBuildingId || !source.npc.isValid || source.workerId in runtime.pendingHarm ||
            source.npc.world.uid != city.worldId || source.sleeping || source.sheltering || source.attacker != null ||
            (CivsLogistics.workerExcess(city, worker, foods = residentFoods)[plan.cargo.material] ?: 0L) < plan.cargo.amount) {
            releaseCourierSource(courier, now)
            if (courier.cycle.phase != CitizenPhase.PAUSED) routes.pause(courier, "route-unreachable", now)
            return false
        }
        if (fresh) CivsDiagnostics.event("cargo_interception_started", "city" to city.id, "worker" to courier.workerId,
            "source_worker" to source.workerId, "shipment" to plan.cargo.id, "material" to plan.cargo.material,
            "amount" to plan.cargo.amount, "point" to plan.cargo.sourceEntrance, "actual" to source.npc.location.point())
        if (!sourceReady(source, city, worker)) {
            routes.cancelRoute(courier)
            courier.cycle.deferWork(now)
            runtime.statuses[courier.workerId] = "waiting"
            return false
        }
        if (courierMeetsSource(courier, source)) return true
        if (courier.cycle.phase in setOf(CitizenPhase.WORKING, CitizenPhase.PICKUP_READY)) {
            routes.cancelRoute(courier)
            courier.cycle = courier.plan.cycle().also(CitizenCycle::start)
        }
        if (now < courier.courierTargetAfter) return courier.plan.workplace?.let(courier.targets::containsKey) == true
        courier.courierTargetAfter = now + 1_000L
        val at = source.npc.location.point()
        val approach = courier.route
        if (approach?.motion?.navigating == true) {
            val nearby = navigationSettings.closeRouteDistance * navigationSettings.closeRouteDistance
            // Keep a distant approach while the source stays in its destination area.
            // Repeated local motion must not cancel capture before the courier can walk.
            if (courier.courierSourceObserved?.distanceSquared(at)?.let { it <= 1.0 } == true ||
                courier.npc.location.point().distanceSquared(approach.target.point()) > nearby &&
                at.distanceSquared(approach.target.point()) <= nearby) return true
        }
        val target = courierInterceptTarget(courier, source, city) ?: run {
            routes.cancelRoute(courier)
            courier.cycle.deferWork(now)
            runtime.statuses[courier.workerId] = "waiting"
            return false
        }
        val authored = requireNotNull(courier.plan.workplace)
        if (courier.targets[authored] != target) {
            routes.cancelRoute(courier)
            courier.targets[authored] = target
        }
        courier.courierSourceObserved = at
        return true
    }

    private fun courierInterceptTarget(courier: CitizenActor, source: CitizenActor, city: Settlement): CivPosition? {
        val from = source.npc.location
        val next = source.route?.motion?.takeIf { !it.searching && it.navigating }?.destination
        val dx = (next?.x ?: from.x) - from.x
        val dz = (next?.z ?: from.z) - from.z
        val length = kotlin.math.hypot(dx, dz)
        val lead = if (length > .001) minOf(1.5, length) / length else 0.0
        val ahead = CivPosition(kotlin.math.floor(from.x + dx * lead).toInt(), from.y.roundToInt(),
            kotlin.math.floor(from.z + dz * lead).toInt())
        val actual = CivPosition(from.blockX, from.y.roundToInt(), from.blockZ)
        val allowed = citizenRouteBuildings(city, courier.plan, requireNotNull(courier.plan.workplace),
            CivPosition(courier.npc.location.blockX, courier.npc.location.blockY, courier.npc.location.blockZ))
        // Thirty short loaded support probes at most; the existing bounded navigator proves the full route.
        return listOf(ahead, actual).distinct().asSequence().flatMap { anchor ->
            listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1).asSequence().flatMap { (x, z) ->
                listOf(0, -1, 1).asSequence().map { y -> anchor.copy(x = anchor.x + x, y = anchor.y + y, z = anchor.z + z) }
            }
        }.firstOrNull { point -> point.point().distanceSquared(from.point()) <= 2.5 * 2.5 &&
            citizenRouteCellAllowed(city, allowed, point.x, point.z) && citizenControlClear(city, point) &&
            CitizenChunks.between(courier.npc.location.blockX, courier.npc.location.blockZ, point.x, point.z).ready(source.npc.world) &&
            citizenStandable(source.npc.world, point) &&
            courierInteractionClear(point.location(source.npc.world).add(0.0, courier.npc.eyeHeight, 0.0), source) }
    }

    /** An empty courier stays at its own depot until a real shipment or night shelter takes priority. */
    fun returnToWarehouse(actor: CitizenActor, city: Settlement, world: World, now: Long): Boolean {
        val warehouse = actor.plan.token.assignmentId?.let(city::building)
        if (actor.plan.courier != null || actor.plan.idleReason != "waiting-orders" ||
            warehouse?.type != BuildingType.WAREHOUSE || warehouse.phase != BuildingPhase.ACTIVE) {
            actor.returningToWarehouse = false
            return false
        }
        leisure.stopCamp(actor, "warehouse-return")
        val target = actor.plan.storage
        if (routes.arrivedAt(actor, city, world, target)) {
            actor.route?.trip?.let { paths.complete(city, it) }
            routes.cancelRoute(actor)
            actor.returningToWarehouse = false
            runtime.statuses[actor.workerId] = "waiting-orders"
            leisure.speak(actor, CitizenVoiceEvent.IDLE)
            return true
        }
        actor.returningToWarehouse = true
        runtime.statuses[actor.workerId] = "moving-to-storage"
        if (actor.route == null) routes.startRoute(actor, world, target, now)
        actor.route?.let { route ->
            if (route.completion?.cancelReason == "resident-blocked") routes.cancelRoute(actor)
            else if (route.completion != null || route.timedOut(now)) {
                if (!routes.retryPreparation(actor, route, now)) routes.recoverNavigation(actor, route.target, now)
            }
        }
        return true
    }
}
