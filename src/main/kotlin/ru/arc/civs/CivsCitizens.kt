package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.npc.PacketNpcProfile
import ru.arc.paper.npc.PacketNpcInteraction
import ru.arc.paper.npc.PacketPlayerNpc
import ru.arc.paper.npc.PaperPacketNpcs
import ru.arc.paper.api.ArcSceneBounds
import ru.arc.paper.api.ArcSceneEntityInfo
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.logging.Level

/** Bounded resident simulation; Core owns only the client-side player representation. */
class CivsCitizens(
    private val plugin: Plugin,
    private val cityProvider: () -> Collection<Settlement>,
    private val delivery: (UUID, UUID, UUID, Long, Long, UUID?, Long) -> CompletableFuture<Settlement>,
    private val interact: (Player, UUID, UUID) -> Unit,
    private val maxActive: Int = CivsSettings.MAXIMUM_NPCS,
    private val viewerRange: Int = 96,
    private val hurt: (UUID, UUID, Double) -> CompletableFuture<Settlement> = { _, _, _ ->
        CompletableFuture.failedFuture(CivsRejection("storage-unavailable")) },
    private val footstep: (UUID, UUID, PacketPlayerNpc, Location, Location) -> Unit = { _, _, _, _, _ -> },
    private val courierPlan: (Settlement, CivWorker) -> CivCourierPlan? = { _, _ -> null },
    private val pickupCargo: (UUID, UUID, CivCargo) -> CompletableFuture<Settlement> = { _, _, _ ->
        CompletableFuture.failedFuture(CivsRejection("storage-unavailable")) },
    private val surfaceSpeed: (org.bukkit.block.Block) -> Double = { 1.0 },
    private val workPlan: (Settlement, CivWorker) -> CivWorldWork? = { _, _ -> null },
    private val workIdleReason: (Settlement, CivWorker) -> String = { _, _ -> "waiting-growth" },
    private val dropoffCargo: (UUID, UUID, CivCargo, UUID) -> CompletableFuture<Settlement> = { _, _, _, _ ->
        CompletableFuture.failedFuture(CivsRejection("storage-unavailable")) },
    private val doorBlocked: (UUID, CivPosition) -> Boolean = { _, _ -> false },
    private val constructionFocus: (UUID, UUID) -> CivPosition? = { _, _ -> null },
    packetOwner: PaperPacketNpcs? = null,
    private val productionReady: (Settlement, CivWorker) -> Boolean = { _, _ -> true },
    private val workInputsReady: (Settlement, CivWorker) -> Boolean = { _, _ -> true },
    private val residentSupplySource: (Settlement, CivWorker) -> UUID? = { _, _ -> null },
    private val residentFoodSource: (Settlement, CivWorker) -> CivResidentFoodSource? = { _, _ -> null },
    private val tavernFoodSource: (Settlement, CivWorker) -> CivResidentFoodSource? = { _, _ -> null },
    private val residentFoods: CivsResidentFoods = CivsResidentFoods.BREAD_ONLY,
    private val pickupResidentFood: (UUID, UUID, CivResidentFoodSource) -> CompletableFuture<Settlement> = { _, _, _ ->
        CompletableFuture.failedFuture(CivsRejection("resident-food-missing")) },
    private val prepareSupplies: (UUID, UUID, UUID) -> CompletableFuture<Settlement> = { _, _, _ ->
        CompletableFuture.failedFuture(CivsRejection("storage-unavailable")) },
    private val backgroundActive: (UUID) -> Boolean = { false },
    navigationTasks: LifecycleTaskScope? = null,
    navigationWork: BudgetedTaskQueue? = null,
    navigationExecutor: Executor? = null,
    private val navigationClock: () -> Long = System::currentTimeMillis,
    private val courierFailed: (UUID, UUID, CivCargo) -> Unit = { _, _, _ -> },
    private val workPositionFailed: (Settlement, CivWorker, CivWorldWork) -> Boolean = { _, _, _ -> false },
    private val craftPlan: (Settlement, CivWorker) -> CivCraftJob? = { _, _ -> null },
    private val craftDelivery: (UUID, UUID, UUID, Long, Long, UUID, Long) -> CompletableFuture<Settlement> = { _, _, _, _, _, _, _ ->
        CompletableFuture.failedFuture(CivsRejection("stale-delivery")) },
    private val craftSupplies: (UUID, UUID, UUID, UUID) -> CompletableFuture<Settlement> = { _, _, _, _ ->
        CompletableFuture.failedFuture(CivsRejection("stale-delivery")) },
    private val constructionSite: (UUID, UUID) -> CivConstructionSite? = { _, _ -> null },
    private val collectionAvailable: (UUID) -> Boolean = { false },
    private val collectionHome: (Settlement, CivWorker) -> CivCollectedHome? = { _, _ -> null },
    private val pickupGround: (UUID, UUID, List<UUID>) -> CompletableFuture<Settlement> = { _, _, _ ->
        CompletableFuture.failedFuture(CivsRejection("storage-unavailable")) },
    private val depositCollected: (UUID, UUID, CivPosition?) -> CompletableFuture<Settlement> = { _, _, _ ->
        CompletableFuture.failedFuture(CivsRejection("storage-unavailable")) },
    private val immortalResidents: (UUID) -> Boolean = { false },
    private val navigationSettings: CivsNavigationSettings = CivsNavigationSettings(),
    private val recoveryClock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val workStatusInputs: (Settlement, CivWorker) -> List<String> = { _, _ -> emptyList() },
    private val workstation: ((Settlement, CivWorker) -> CivWorkstation?)? = null,
) : Listener, AutoCloseable {
    private val runtime = CitizenRuntime(plugin, cityProvider)
    internal var residentLifeEnabled: Boolean
        get() = runtime.residentLifeEnabled
        set(value) { runtime.residentLifeEnabled = value }
    internal var returnResidentOutputs: (UUID, UUID, UUID) -> CompletableFuture<Settlement> = { _, _, _ ->
        CompletableFuture.failedFuture(CivsRejection("storage-unavailable")) }
    internal var finishResidentMeal: (UUID, CivResidentMeal) -> CompletableFuture<Settlement> = { _, _ ->
        CompletableFuture.failedFuture(CivsRejection("not-ready")) }
    internal var deliverTavernMeal: (UUID, CivTavernDelivery) -> CompletableFuture<Settlement> = { _, _ ->
        CompletableFuture.failedFuture(CivsRejection("not-ready")) }
    internal var finishResidentRest: (UUID, UUID, Long) -> CompletableFuture<Settlement> = { _, _, _ ->
        CompletableFuture.failedFuture(CivsRejection("not-ready")) }
    internal var finishResidentLesson: (UUID, CivResidentLessonProof) -> CompletableFuture<Settlement> = { _, _ ->
        CompletableFuture.failedFuture(CivsRejection("not-ready")) }
    internal var worksiteTerrainChanged: (UUID, CivPosition) -> Unit = { _, _ -> }
    internal var worksiteWorldUnloaded: (UUID) -> Unit = {}
    internal var worksiteChunkUnloaded: (UUID, Int, Int) -> Unit = { _, _, _ -> }
    private val packets: PaperPacketNpcs
    private val ownsNavigationTasks = navigationTasks == null
    private val residentTasks = navigationTasks ?: LifecycleTaskScope(BukkitTaskScheduler(plugin))
    private val navigation = CivsResidentNavigation(residentTasks, navigationWork, navigationExecutor, settings = navigationSettings)
    private val paths = CivsPaths()
    private val threats: CivsResidentThreats
    private val life: CivsCitizenLife
    private val collisions: CivsCitizenCollisions
    private val routes: CivsCitizenRoutes
    private val motion: CivsCitizenMotion
    private val shelter: CivsCitizenShelters
    private val couriers: CivsCitizenCouriers
    private val leisure: CivsCitizenLeisure
    private val workRuntime: CivsCitizenWork
    private val logistics: CivsCitizenLogistics
    private val combat: CivsCitizenCombat
    private val observations: CivsCitizenObservations
    private val plans: CivsCitizenPlanning
    private val equipment: CivsCitizenEquipment
    private val audience: CivsCitizenAudience
    private val skins: List<CitizenSkin>
    private val actors = runtime.actors
    private val statuses = runtime.statuses
    private val admission = CivsCitizenAdmissions({ city, world, point, viewers -> audience.activeAt(city, world, point, viewers) }) { worldId, body ->
        actors.values.any { actor ->
            actor.npc.isValid && actor.worldId == worldId &&
                (citizenStandingBounds(actor.npc.location, actor.npc.scale).overlaps(body) ||
                    actor.route?.target?.let { citizenStandingBounds(it, actor.npc.scale).overlaps(body) } == true ||
                    actor.sleepPose?.takeIf { actor.sleeping }?.bounds?.overlaps(body) == true ||
                    actor.shelterPose?.takeIf { actor.sheltering }?.bounds?.overlaps(body) == true)
        }
    }
    private val closed = runtime.closed
    private var retirement: CompletableFuture<Void>? = null
    private val campfires = CivsCampfires()
    private val voices = CivsCitizenVoices(plugin.config)
    private val pendingHarm = runtime.pendingHarm
    internal var residentStepAllowed: (PacketPlayerNpc, Location, Location) -> Boolean = { _, _, _ -> true }

    internal fun collisionActors(): Collection<PacketPlayerNpc> {
        checkCitizenMainThread()
        return actors.values.map { it.npc }
    }

    /** Natural tree growth happens later than tending; protect the bodies present at that instant. */
    internal fun growthBlocked(worldId: UUID, point: CivPosition): Boolean {
        checkCitizenMainThread()
        val block = BoundingBox(point.x.toDouble(), point.y.toDouble(), point.z.toDouble(),
            point.x + 1.0, point.y + 1.0, point.z + 1.0)
        // The resident runtime already caps this collection; no native entity/world scan.
        return actors.values.any { actor -> actor.worldId == worldId && actor.npc.isValid &&
            (actor.mealVisit?.seat?.takeIf { actor.npc.isSeated }?.bounds ?: actor.sleepPose?.takeIf { actor.sleeping }?.bounds
                ?: citizenStandingBounds(actor.npc.location, actor.npc.scale)).overlaps(block) }
    }

    /** Logical grounded feet stay stable while the sleeping mesh has a pose-only lift. */
    internal fun residentFeet(workerId: UUID): Location? {
        checkCitizenMainThread()
        val actor = actors[workerId]?.takeIf { it.npc.isValid } ?: return null
        return actor.mealVisit?.seatedFrom?.clone() ?: actor.sleepOrigin?.clone() ?: actor.npc.location.clone()
    }

    internal fun residentCollectionFeet(workerId: UUID, allowNight: Boolean = false): Location? {
        checkCitizenMainThread()
        val actor = actors[workerId]?.takeIf { it.npc.isValid } ?: return null
        if (actor.npc.isSeated || actor.sleeping || !allowNight && actor.sheltering || actor.attacker != null || workerId in pendingHarm ||
            !allowNight && actor.npc.world.time in 12_000L..23_000L) return null
        return actor.npc.location.clone()
    }
    init {
        require(maxActive in 1..CivsSettings.MAXIMUM_NPCS && viewerRange in 1..96)
        checkCitizenMainThread()
        skins = loadCitizenSkins()
        packets = packetOwner ?: PaperPacketNpcs(plugin, "civs-residents")
        packets.enableSceneObservation()
        threats = CivsResidentThreats(plugin, attacked = { worker, monster -> combat.defend(worker, monster) }, mobProtected = immortalResidents,
            discovered = { worker, monster -> if (residentLifeEnabled) combat.defend(worker, monster) }, injury = { worker, amount -> combat.damage(worker, amount) })
        collisions = CivsCitizenCollisions(runtime,
            routesOwner = { routes },
            leisureOwner = { leisure },
            workOwner = { workRuntime },
            logisticsOwner = { logistics },
            audienceOwner = { audience },
            lifeOwner = { life },
            recoveryClock = recoveryClock,
            residentStepAllowed = { npc, from, to -> residentStepAllowed(npc, from, to) })
        routes = CivsCitizenRoutes(runtime,
            collisionsOwner = { collisions },
            shelterOwner = { shelter },
            couriersOwner = { couriers },
            leisureOwner = { leisure },
            workOwner = { workRuntime },
            logisticsOwner = { logistics },
            lifeOwner = { life },
            navigation = navigation,
            paths = paths,
            surfaceSpeed = surfaceSpeed,
            workPositionFailed = workPositionFailed,
            recoveryClock = recoveryClock,
            courierFailed = courierFailed,
            remove = ::remove)
        motion = CivsCitizenMotion(runtime,
            collisionsOwner = { collisions },
            routesOwner = { routes },
            workOwner = { workRuntime },
            logisticsOwner = { logistics },
            audienceOwner = { audience },
            navigation = navigation,
            paths = paths,
            threats = threats,
            admission = admission,
            footstep = footstep,
            surfaceSpeed = surfaceSpeed,
            doorBlocked = doorBlocked,
            navigationClock = navigationClock)
        shelter = CivsCitizenShelters(runtime,
            collisionsOwner = { collisions },
            routesOwner = { routes },
            lifeOwner = { life })
        couriers = CivsCitizenCouriers(runtime,
            routesOwner = { routes },
            leisureOwner = { leisure },
            workOwner = { workRuntime },
            logisticsOwner = { logistics },
            paths = paths,
            residentFoods = residentFoods,
            craftPlan = craftPlan,
            navigationSettings = navigationSettings)
        leisure = CivsCitizenLeisure(runtime,
            collisionsOwner = { collisions },
            routesOwner = { routes },
            logisticsOwner = { logistics },
            lifeOwner = { life },
            threats = threats,
            campfires = campfires,
            voices = voices)
        workRuntime = CivsCitizenWork(runtime,
            routesOwner = { routes },
            shelterOwner = { shelter },
            couriersOwner = { couriers },
            leisureOwner = { leisure },
            logisticsOwner = { logistics },
            combatOwner = { combat },
            audienceOwner = { audience },
            lifeOwner = { life },
            navigation = navigation,
            paths = paths,
            constructionFocus = constructionFocus,
            workInputsReady = workInputsReady,
            constructionSite = constructionSite,
            worksiteTerrainChanged = { world, point -> worksiteTerrainChanged(world, point) },
            remove = ::remove)
        logistics = CivsCitizenLogistics(runtime,
            routesOwner = { routes },
            couriersOwner = { couriers },
            leisureOwner = { leisure },
            workOwner = { workRuntime },
            delivery = delivery,
            pickupCargo = pickupCargo,
            dropoffCargo = dropoffCargo,
            workInputsReady = workInputsReady,
            residentSupplySource = residentSupplySource,
            residentFoodSource = residentFoodSource,
            residentFoods = residentFoods,
            pickupResidentFood = pickupResidentFood,
            prepareSupplies = prepareSupplies,
            craftDelivery = craftDelivery,
            craftSupplies = craftSupplies,
            collectionAvailable = collectionAvailable,
            collectionHome = collectionHome,
            pickupGround = pickupGround,
            depositCollected = depositCollected,
            returnResidentOutputs = { city, worker, building -> returnResidentOutputs(city, worker, building) })
        combat = CivsCitizenCombat(runtime,
            collisionsOwner = { collisions },
            routesOwner = { routes },
            shelterOwner = { shelter },
            couriersOwner = { couriers },
            leisureOwner = { leisure },
            workOwner = { workRuntime },
            equipmentOwner = { equipment },
            lifeOwner = { life },
            threats = threats,
            hurt = hurt,
            immortalResidents = immortalResidents,
            remove = ::remove)
        observations = CivsCitizenObservations(runtime,
            workOwner = { workRuntime },
            logisticsOwner = { logistics },
            audienceOwner = { audience },
            lifeOwner = { life },
            navigation = navigation,
            admission = admission,
            maxActive = maxActive,
            viewerRange = viewerRange,
            navigationClock = navigationClock,
            workStatusInputs = workStatusInputs)
        plans = CivsCitizenPlanning(runtime,
            routesOwner = { routes },
            couriersOwner = { couriers },
            leisureOwner = { leisure },
            workOwner = { workRuntime },
            logisticsOwner = { logistics },
            equipmentOwner = { equipment },
            lifeOwner = { life },
            courierPlan = courierPlan,
            workPlan = workPlan,
            workIdleReason = workIdleReason,
            productionReady = productionReady,
            workInputsReady = workInputsReady,
            craftPlan = craftPlan,
            workstation = workstation,
            remove = ::remove)
        equipment = CivsCitizenEquipment(runtime,
            threats = threats)
        audience = CivsCitizenAudience(runtime,
            viewerRange = viewerRange,
            backgroundActive = backgroundActive,
            remove = ::remove)
        life = CivsCitizenLife(runtime, residentFoods, threats,
            residentStepAllowed = { npc, from, to -> residentStepAllowed(npc, from, to) },
            tavernFoodSource = tavernFoodSource, pickupResidentFood = pickupResidentFood,
            finishResidentMeal = { city, meal -> finishResidentMeal(city, meal) },
            deliverTavernMeal = { city, proof -> deliverTavernMeal(city, proof) },
            finishResidentLesson = { city, proof -> finishResidentLesson(city, proof) },
            finishResidentRest = { city, worker, minute -> finishResidentRest(city, worker, minute) },
            collisionsOwner = { collisions }, routesOwner = { routes }, workOwner = { workRuntime },
            leisureOwner = { leisure }, logisticsOwner = { logistics }, equipmentOwner = { equipment })
        packets.onInteract = { player, npc, action ->
            val actor = actors.values.firstOrNull { it.npc === npc }
            if (!closed.get() && actor != null) {
                if (action == PacketNpcInteraction.ATTACK) threats.playerAttack(player, actor.workerId, npc)
                else {
                    val resident = cityProvider().firstOrNull { it.id == actor.cityId }?.workers?.firstOrNull { it.id == actor.workerId }
                    voices.greet(actor.cityId, actor.workerId, npc, player, resident?.let(CivsResidents::sex))
                    interact(player, actor.cityId, actor.workerId)
                }
            }
        }
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.logger.info("ArcCivs resident backend=packets maximum=$maxActive viewer_range=$viewerRange " +
            "pathfinding=async-a-star path_jobs_max=${navigationSettings.maximumJobs} " +
            "path_nodes_per_batch=${navigationSettings.searchNodesPerBatch} " +
            "path_capture_budget=${navigationSettings.nativeOperationsPerTick}steps/${navigationSettings.nativeBudgetMillis}ms " +
            "path_capture_owner=" + if (navigationWork != null) "shared-native-work" else "resident-navigation")
    }

    fun pulse(nowMillis: Long) {
        checkCitizenMainThread()
        if (closed.get()) return
        runtime.residentObservedAt = nowMillis
        runtime.wanderBudget = 8 // Aggregate native target checks across all idle actors, not eight per NPC.
        runtime.campBudget = 2 // At most two six-place campfire admission searches for the entire owner.
        runtime.collectionBudget = 2 // Two 32-item discoveries or four-holder home checks across the whole owner.
        runtime.recoveryBudget = 2 // At most two bounded safe-position searches after real route failures per pulse.
        runtime.constructionBudget = 2 // Each construction inspects at most sixteen exterior columns, once while valid.
        logistics.drainCompletions(nowMillis)
        logistics.drainSupplies(nowMillis)
        life.drainResidentLife(nowMillis)
        logistics.drainCollected(nowMillis)
        combat.drainHarm()
        val cities = cityProvider().associateBy { it.id }
        combat.retain(cities.keys, nowMillis)
        workRuntime.retainConstruction(cities.values)
        paths.retain(cities)
        val workers = cities.values.flatMap { city -> city.workers.map { it.id to (city to it) } }.toMap()
        voices.retain(workers.keys)
        statuses.keys.retainAll(workers.keys)
        admission.retain(workers.keys, actors.keys)
        val viewers = audience.viewers()

        for (actor in actors.values.toList()) {
            val current = workers[actor.workerId]
            if (current == null) {
                remove(actor, "unavailable")
                continue
            }
            val (city, worker) = current
            if (worker.id in pendingHarm) {
                statuses[worker.id] = "injury-pending"
                continue
            }
            val world = plugin.server.getWorld(city.worldId)
            // Native inventory slots can change before the canonical cargo revision is published.
            // Keep this exact transfer stationary until its receipt resolves instead of choosing a new request.
            val plan = plans.currentPlan(actor, city, worker)
            val reason = when {
                actor.cityId != city.id || actor.worldId != city.worldId -> "unavailable"
                world == null -> "world-unloaded"
                !actor.npc.isValid -> "unavailable"
                actor.npc.world.uid != city.worldId -> "unavailable"
                !audience.activeAt(city.id, city.worldId, actor.npc.location.point(), viewers) -> "sleeping"
                plan == null -> "storage-unavailable"
                !citizenChunkReady(world, actor.npc.location.blockX shr 4, actor.npc.location.blockZ shr 4) ||
                    !plan.chunksReady(world) || actor.route?.chunks?.ready(world) == false -> "chunk-unloaded"
                else -> null
            }
            if (reason != null) {
                remove(actor, reason)
                continue
            }
            if (!plans.reconcilePlan(actor, requireNotNull(plan), city)) continue
            try {
                equipment.syncResident(actor.npc, worker, requireNotNull(plan))
                workRuntime.update(actor, city, requireNotNull(world), nowMillis)
            } catch (error: Exception) {
                routes.pause(actor, "route-unreachable", nowMillis)
                runtime.logFailure(actor, "navigation", error)
            }
        }

        var budget = citizenSpawnBudget(0, maxActive)
        workers.values.filter { it.second.id !in actors && it.second.id in pendingHarm }.forEach { (city, worker) ->
            admission.record(city, worker, null, null, "injury-pending", nowMillis)
        }
        val candidates = workers.values.filter { it.second.id !in actors && it.second.id !in pendingHarm }
            .map { (city, worker) -> Triple(city, worker, plans.plan(city, worker)) }
            .sortedByDescending { (city, worker, plan) -> plan != null &&
                citizenHasViewer(city.worldId, admission.origin(city, worker.id, plan).point(), viewers, viewerRange) }
        for ((city, worker, plan) in candidates) {
            val workerId = worker.id
            val origin = plan?.let { admission.origin(city, workerId, it) }
            val visible = origin != null && citizenHasViewer(city.worldId, origin.point(), viewers, viewerRange)
            val world = plugin.server.getWorld(city.worldId)
            val reason = when {
                plan == null -> "storage-unavailable"
                world == null -> "world-unloaded"
                !audience.activeAt(city.id, city.worldId, requireNotNull(origin).point(), viewers) -> "sleeping"
                !plan.chunksReady(world) -> "chunk-unloaded"
                else -> null
            }
            if (reason != null) {
                statuses[workerId] = reason
                if (reason == "sleeping") admission.sleeping(workerId)
                admission.record(city, worker, plan, world, reason, nowMillis)
                continue
            }
            if (budget == 0) {
                statuses[workerId] = "waiting"
                admission.record(city, worker, plan, world, "spawn-budget", nowMillis)
                continue
            }
            val retry = admission.retry(workerId, plan)
            if (!retry.ready(nowMillis)) {
                admission.record(city, worker, plan, world, "spawn-retry-cooldown", nowMillis, retry)
                continue
            }
            if (actors.size >= maxActive && visible) actors.values.firstOrNull { actor ->
                actor.workerId !in pendingHarm && actor.supplyPickup == null &&
                    actor.cycle.phase !in setOf(CitizenPhase.PICKING_UP, CitizenPhase.DELIVERING) &&
                    !citizenHasViewer(actor.worldId, actor.npc.location.point(), viewers, viewerRange)
            }?.let { remove(it, "capacity") }
            if (actors.size >= maxActive) {
                statuses[workerId] = "capacity"
                admission.record(city, worker, plan, world, "capacity", nowMillis, retry)
                continue
            }
            budget-- // Failed spawns consume the same per-pulse budget as successful spawns.
            var npc: PacketPlayerNpc? = null
            try {
                requireNotNull(plan)
                val search = admission.spawnPoint(city, requireNotNull(world), requireNotNull(origin),
                    city.workers.indexOf(worker), viewers, nowMillis)
                val point = search.point?.location(world)
                if (point == null) {
                    admission.failed(workerId, plan, retry, nowMillis)
                    statuses[workerId] = "route-unreachable"
                    admission.record(city, worker, plan, world, "no-safe-spawn", nowMillis, retry, search)
                    if (retry.attempts == 1 || retry.attempts == 3) CivsDiagnostics.record(plugin.logger, Level.WARNING, city.id, CivsDiagnostics.line(
                        "citizen_spawn_blocked", "city" to city.id, "worker" to workerId, "origin" to origin,
                        "attempt" to retry.attempts, "retry_at" to retry.nextAttemptAt,
                        "candidates" to search.inspected, "rejections" to search.rejections))
                    continue
                }
                val skin = citizenSkin(skins, worker)
                npc = packets.spawnPlayer(point, PacketNpcProfile(workerId, "civ_" + workerId.toString().replace("-", "").take(12),
                    skin.texture, skin.signature))
                npc.viewRange = viewerRange.toDouble()
                equipment.setEquipment(npc, plan)
                val actor = CitizenActor(city.id, workerId, city.worldId, npc, plan)
                actors[workerId] = actor
                equipment.syncResident(npc, worker, plan)
                admission.accepted(workerId)
                workRuntime.update(actor, city, world, nowMillis)
                if (actors[workerId] === actor) CivsDiagnostics.event("citizen_spawn", "city" to city.id,
                    "worker" to workerId, "type" to "PACKET_PLAYER",
                    "skin" to skin.name, "tool" to (CivsEquipment.slots(worker)[4]?.material ?: citizenTool(plan)?.name))
            } catch (error: Exception) {
                actors.remove(workerId)?.let { actor ->
                    life.cancel(actor)
                    logistics.cancel(actor)
                    actor.cycle.close()
                }
                runCatching { npc?.remove() }.onFailure { CivsDiagnostics.record(plugin.logger, Level.WARNING, city.id,
                    "ArcCivs failed to remove rejected NPC city=${city.id} worker=$workerId", it) }
                admission.failed(workerId, plan, retry, nowMillis)
                statuses[workerId] = "unavailable"
                admission.record(city, worker, plan, world, "spawn-failed", nowMillis, retry)
                if (retry.attempts == 1 || retry.exhausted) CivsDiagnostics.record(plugin.logger, Level.WARNING, city.id,
                    "ArcCivs NPC spawn failed city=${city.id} worker=$workerId attempt=${retry.attempts}", error)
            }
        }
        campfires.scanOne(Bukkit.getCurrentTick()) { plugin.server.getWorld(it) }
        threats.pulse(actors.values.filter { it.workerId !in pendingHarm && it.npc.isValid }
            .map { CivsThreatTarget(it.cityId, it.workerId, it.npc) }, nowMillis)
    }

    /** Hand over admitted work, courier transfers and short idle legs on the existing bounded pulse. */
    internal fun pulseFieldWork(nowMillis: Long) {
        checkCitizenMainThread()
        if (closed.get()) return
        // Both owner cadences can submit observed resident-life and work receipts.
        runtime.residentObservedAt = nowMillis
        logistics.drainCompletions(nowMillis)
        val cities = cityProvider().associateBy { it.id }
        val viewers = audience.viewers()
        // At most eight existing actors, rotated after admission; no spawn, scan or threat pulse.
        val candidates = actors.values.filter { (it.plan.type in setOf(BuildingType.FARM, BuildingType.BUILDER_HUT) ||
            it.plan.type == BuildingType.WAREHOUSE && it.plan.courier != null) && it.workerId !in pendingHarm &&
            it.npc.isValid && !it.sleeping && !it.sheltering && it.attacker == null && it.foodSource == null &&
            it.supplyPickup == null && it.social.visit == null }.take(8)
        for (actor in candidates) {
            citizenRotateAfterAdmission(actors, actor.workerId)
            val city = cities[actor.cityId]?.takeIf { it.pendingDeletion == null } ?: continue
            val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: continue
            val world = actor.npc.world
            if (world.uid != city.worldId || world.time in 12_000L..23_000L ||
                !audience.activeAt(city.id, city.worldId, actor.npc.location.point(), viewers)) continue
            val plan = plans.currentPlan(actor, city, worker) ?: continue
            if (plan.worldWork == null && plan.token.constructionId == null && plan.idleReason != "searching-work" && plan.courier == null || !plan.chunksReady(world) ||
                !citizenChunkReady(world, actor.npc.location.blockX shr 4, actor.npc.location.blockZ shr 4) ||
                actor.route?.chunks?.ready(world) == false) continue
            if (!plans.reconcilePlan(actor, plan, city)) continue
            try {
                equipment.syncResident(actor.npc, worker, plan)
                workRuntime.update(actor, city, world, nowMillis)
            } catch (error: Exception) {
                routes.pause(actor, "route-unreachable", nowMillis)
                runtime.logFailure(actor, "field-handoff", error)
            }
        }
        // Keep leisure handoffs responsive without another timer or another native admission budget.
        // Work handoffs retain all eight slots; at most four idle actors share the normal wander budget.
        val idle = actors.values.filter { it.plan.idleReason == "unassigned" && it.workerId !in pendingHarm &&
            it.npc.isValid && !it.sleeping && !it.sheltering && it.attacker == null && it.foodSource == null &&
            it.supplyPickup == null && !logistics.collectedPending(it) && it.social.visit == null }.take(4)
        for (actor in idle) {
            citizenRotateAfterAdmission(actors, actor.workerId)
            val city = cities[actor.cityId]?.takeIf { it.pendingDeletion == null } ?: continue
            val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: continue
            val world = actor.npc.world
            if (world.uid != city.worldId || world.time in 12_000L..23_000L ||
                !audience.activeAt(city.id, city.worldId, actor.npc.location.point(), viewers)) continue
            val plan = plans.currentPlan(actor, city, worker)?.takeIf { it.idleReason == "unassigned" } ?: continue
            if (!plan.chunksReady(world) || !citizenChunkReady(world, actor.npc.location.blockX shr 4, actor.npc.location.blockZ shr 4) ||
                actor.route?.chunks?.ready(world) == false || !plans.reconcilePlan(actor, plan, city)) continue
            try {
                equipment.syncResident(actor.npc, worker, plan)
                workRuntime.update(actor, city, world, nowMillis)
            } catch (error: Exception) {
                routes.pause(actor, "route-unreachable", nowMillis)
                runtime.logFailure(actor, "idle-handoff", error)
            }
        }
    }

    /** A committed resurrection may prefer its body site; normal admission still proves safe loaded feet. */
    internal fun requestAdmissionAt(cityId: UUID, workerId: UUID, location: Location) {
        checkCitizenMainThread()
        if (closed.get() || workerId in actors) return
        val city = cityProvider().firstOrNull { it.id == cityId && it.worldId == location.world?.uid &&
            it.pendingDeletion == null && it.workers.any { worker -> worker.id == workerId } } ?: return
        if (!location.x.isFinite() || !location.y.isFinite() || !location.z.isFinite() ||
            !city.contains(location.blockX, location.blockZ)) return
        admission.requestAt(cityId, workerId, CivPosition(location.blockX, location.blockY, location.blockZ))
    }

    /** The UI owns management/reach checks. Moving an existing actor never completes work or a receipt. */
    internal fun summon(city: Settlement, buildingId: UUID): Boolean {
        checkCitizenMainThread()
        if (closed.get()) return false
        val current = cityProvider().firstOrNull { it.id == city.id && it.pendingDeletion == null } ?: return false
        val building = current.building(buildingId) ?: return false
        val actor = current.workers.asSequence().filter { citizenSummonEligible(current, it, buildingId) }
            .mapNotNull { actors[it.id] }.firstOrNull {
                it.npc.isValid && it.cityId == current.id && it.worldId == current.worldId && it.npc.world.uid == current.worldId &&
                    it.workerId !in pendingHarm &&
                    it.cycle.phase !in setOf(CitizenPhase.PICKING_UP, CitizenPhase.DELIVERING)
            } ?: return false
        val worker = current.workers.first { it.id == actor.workerId }
        val nextPlan = plans.plan(current, worker) ?: return false
        val npc = actor.npc
        val world = npc.world
        val viewers = audience.viewers()
        val safe: (CivPosition) -> Boolean = { point ->
            citizenStandable(world, point) && citizenPacketBodyClear(world, point.point(), false) &&
                citizenIdleTerrain(world, point, current.buildings) && citizenControlClear(current, point) &&
                citizenRouteCellAllowed(current, setOf(building.id), point.x, point.z) &&
                audience.activeAt(current.id, current.worldId, point.point(), viewers) &&
                point.location(world).let { destination -> collisions.residentMoveAllowed(actor, destination, destination) &&
                    actors.values.none { other -> other !== actor && other.npc.isValid && other.worldId == current.worldId &&
                        other.npc.location.distanceSquared(destination) < .8 * .8 } }
        }
        val point = if (building.control != null) citizenStorageTarget(current, building, world, safe)
            else citizenRecoveryTarget(current, building.entrance, safe)
        val destination = point?.location(world) ?: return false
        val previous = npc.location
        destination.yaw = previous.yaw
        leisure.stopCamp(actor, "summoned")
        routes.cancelRoute(actor, destination)
        actor.cycle.close()
        actor.plan = nextPlan
        actor.cycle = nextPlan.cycle()
        actor.targets.clear()
        actor.sheltering = false
        actor.shelter = null
        actor.shelterPose = null
        actor.wander = CitizenWander(actor.workerId)
        actor.footsteps.reset()
        npc.teleport(destination)
        statuses[actor.workerId] = "waiting"
        CivsDiagnostics.event("resident_summoned", "city" to current.id, "worker" to worker.id,
            "building" to buildingId, "distance" to previous.distance(destination), "destination" to point)
        return true
    }

    /** Capacity observations can wake only this exact accepted shipment; delivery still proves actual arrival. */
    internal fun retryCargoDelivery(cityId: UUID, workerId: UUID, shipmentId: UUID): Boolean {
        checkCitizenMainThread()
        val actor = actors[workerId]?.takeIf { it.cityId == cityId && it.npc.isValid } ?: return true
        if (actor.plan.courier?.let { it.carrying && it.cargo.id == shipmentId } != true) return true
        // A completed future may still await the actor drain. Keep its capacity wake until failDelivery has installed the pause.
        if (actor.cycle.phase == CitizenPhase.DELIVERING) return false
        if (actor.cycle.phase == CitizenPhase.PAUSED && actor.cycle.reason == "storage-full") actor.cycle.resume()
        return true
    }

    /** Rechecked by the item receipt owner after waiting for another city commit. */
    internal fun requireStorageArrival(cityId: UUID, workerId: UUID, buildingId: UUID) {
        checkCitizenMainThread()
        val actor = actors[workerId]?.takeIf { it.cityId == cityId && it.npc.isValid }
            ?: throw CivsRejection("storage-unavailable")
        val city = cityProvider().firstOrNull { it.id == cityId } ?: throw CivsRejection("no-city")
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        val control = building.control ?: throw CivsRejection("control-missing")
        val entity = actor.npc
        if (entity.world.uid != city.worldId || actor.workerId in pendingHarm ||
            actor.supplyPickup?.buildingId != buildingId &&
                actor.cycle.phase !in setOf(CitizenPhase.PICKING_UP, CitizenPhase.DELIVERING) ||
            !citizenAtStorage(entity.world, entity.location.point(), control) ||
            !citizenCanReachBlock(entity, control)) throw CivsRejection("route-unreachable")
    }

    /** Abstract production uses the prepared entrances; item receipts still require their real barrel. */
    internal fun requireAutonomousDelivery(cityId: UUID, workerId: UUID, token: CitizenToken): CitizenPlan {
        checkCitizenMainThread()
        val actor = actors[workerId]?.takeIf { it.cityId == cityId && it.npc.isValid }
            ?: throw CivsRejection("route-unreachable")
        val city = cityProvider().firstOrNull { it.id == cityId } ?: throw CivsRejection("no-city")
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        val current = citizenPlan(city, worker) ?: throw CivsRejection("stale-delivery")
        val observed = actor.plan
        val collecting = CivsAutonomousLogistics.staffed(city) && observed.type?.let(CivsRules::resource) != null
        val expected = observed.token.copy(industryRecipe = token.industryRecipe)
        val currentSchedule = current.token.copy(industryRecipe = token.industryRecipe)
        if (city.autonomy?.paused != false || city.inventory != null || city.pendingDeletion != null ||
            currentSchedule != token || expected != token || observed.autonomousWork == null ||
            observed.autonomousWork != worker.assignment?.let(city::building) || !collecting && observed.workplace != current.workplace ||
            observed.storage != current.storage || current.idleReason !in listOf(null, "waiting-reassignment", "waiting-orders", "waiting-inputs"))
            throw CivsRejection("stale-delivery")
        if (closed.get() || actor.sheltering || actor.workerId in pendingHarm ||
            actor.cycle.phase != CitizenPhase.DELIVERING || actor.npc.world.uid != city.worldId ||
            !routes.arrivedAt(actor, city, actor.npc.world, if (collecting) requireNotNull(observed.workplace) else current.storage))
            throw CivsRejection("route-unreachable")
        return observed
    }

    internal fun autonomousVisualWork(cityId: UUID, workerId: UUID): CivWorldWork? {
        checkCitizenMainThread()
        return actors[workerId]?.takeIf { it.cityId == cityId }?.plan?.worldWork?.takeIf { it.autonomousVisual }
    }

    internal fun pendingStockDelivery(cityId: UUID, workerId: UUID): Stock? {
        checkCitizenMainThread()
        return actors[workerId]?.takeIf { it.cityId == cityId }?.plan?.pendingStockDelivery
    }

    /** Saved goods need an actual depot arrival, but never another observed production session. */
    internal fun requirePendingStockDelivery(cityId: UUID, workerId: UUID, token: CitizenToken): CivPendingStockDelivery {
        checkCitizenMainThread()
        val actor = actors[workerId]?.takeIf { it.cityId == cityId && it.npc.isValid }
            ?: throw CivsRejection("route-unreachable")
        val city = cityProvider().firstOrNull { it.id == cityId } ?: throw CivsRejection("no-city")
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        val pending = actor.plan.pendingStockDelivery ?: throw CivsRejection("stale-delivery")
        val destination = city.buildings.firstOrNull { CivsRules.isDepot(it) && it.entrance == actor.plan.storage }
            ?: throw CivsRejection("stale-delivery")
        if (city.autonomy?.paused != false || city.inventory != null || city.pendingDeletion != null ||
            actor.plan.token != token || worker.assignment != token.assignmentId || worker.completedCycles != token.completedCycles ||
            worker.nextDeliveryAt != token.nextDeliveryAt || worker.pendingStock != pending || pending == Stock()) throw CivsRejection("stale-delivery")
        if (closed.get() || actor.sheltering || workerId in pendingHarm || actor.cycle.phase != CitizenPhase.DELIVERING ||
            actor.npc.world.uid != city.worldId || !routes.arrivedAt(actor, city, actor.npc.world, actor.plan.storage))
            throw CivsRejection("route-unreachable")
        return CivPendingStockDelivery(pending, destination.id)
    }

    internal fun requireAutonomousObservedSession(cityId: UUID, workerId: UUID) {
        checkCitizenMainThread()
        val actor = actors[workerId]?.takeIf { it.cityId == cityId } ?: throw CivsRejection("route-unreachable")
        val city = cityProvider().firstOrNull { it.id == cityId } ?: throw CivsRejection("no-city")
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        val session = actor.autonomousVisualSession ?: throw CivsRejection("work-not-observed")
        if (session.token != actor.plan.token || session.building != worker.assignment?.let(city::building) ||
            session.token.completedCycles != worker.completedCycles || session.token.nextDeliveryAt != worker.nextDeliveryAt ||
            !session.ready) throw CivsRejection("work-not-observed")
    }

    internal fun acceptedIndustryWork(city: Settlement, worker: CivWorker): CivIndustryWork? {
        checkCitizenMainThread()
        val actor = actors[worker.id]?.takeIf { it.cityId == city.id } ?: return null
        val token = actor.plan.token
        return actor.plan.industryWork?.takeIf { token.assignmentId == worker.assignment && token.completedCycles == worker.completedCycles &&
            token.nextDeliveryAt == worker.nextDeliveryAt && it.building == worker.assignment?.let(city::building) }
    }

    /** Research/new native recipes must match the very session observed by this actor before any receipt. */
    internal fun requireSelectedWorkDelivery(cityId: UUID, workerId: UUID, token: CitizenToken, craftJob: CivCraftJob? = null) {
        checkCitizenMainThread()
        val city = cityProvider().firstOrNull { it.id == cityId } ?: throw CivsRejection("no-city")
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        val actor = actors[workerId]?.takeIf { it.cityId == cityId && it.npc.isValid } ?: throw CivsRejection("route-unreachable")
        val base = citizenPlan(city, worker) ?: throw CivsRejection("stale-delivery")
        if (craftJob != null && !CivsCarpentry.valid(city, worker, craftJob)) throw CivsRejection("stale-delivery")
        val current = if (craftJob == null) base else base.copy(token = base.token.copy(craftJobId = craftJob.id))
        val station = actor.plan.workstation
        val indoorWork = station != null && station.standing == actor.plan.workplace && !actor.plan.completeAtWorkplace &&
            current.type in CivsWorkstations.TYPES && actor.plan.type == current.type &&
            worker.assignment?.let(city::building)?.let { CivsWorkstations.valid(city, it, actor.npc.world, station) } == true
        if (current.token != token || actor.plan.token != token || current.idleReason != null ||
            !indoorWork && actor.plan.workplace != current.workplace || actor.plan.storage != current.storage ||
            actor.sheltering || workerId in pendingHarm || closed.get()) throw CivsRejection("stale-delivery")
        requireStorageArrival(cityId, workerId, requireNotNull(token.assignmentId))
    }

    /** The receipt owner rechecks the same real block after an asynchronous city wait. */
    internal fun requireWorldWorkArrival(cityId: UUID, workerId: UUID, work: CivWorldWork) {
        checkCitizenMainThread()
        val actor = actors[workerId]?.takeIf { it.cityId == cityId && it.npc.isValid }
            ?: throw CivsRejection("route-unreachable")
        val city = cityProvider().firstOrNull { it.id == cityId } ?: throw CivsRejection("no-city")
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        if (closed.get() || actor.sheltering || actor.workerId in pendingHarm ||
            actor.cycle.phase != CitizenPhase.DELIVERING || actor.plan.worldWork != work ||
            work.workerId != workerId || worker.assignment != work.buildingId ||
            worker.completedCycles != work.cycle || actor.npc.world.uid != city.worldId ||
            !routes.canWorkFrom(actor, work)) throw CivsRejection("route-unreachable")
    }

    internal fun validateTavernPickup(cityId: UUID, keeperId: UUID, guestId: UUID, buildingId: UUID, material: String,
        expectedTicket: UUID? = null) = life.validateTavernPickup(cityId, keeperId, guestId, buildingId, material, expectedTicket)

    internal fun validateTavernDelivery(cityId: UUID, proof: CivTavernDelivery) = life.validateTavernDelivery(cityId, proof)

    internal fun validateResidentMeal(cityId: UUID, plan: CivResidentMeal) = life.validateResidentMeal(cityId, plan)

    internal fun validateResidentLesson(cityId: UUID, proof: CivResidentLessonProof) = life.validateResidentLesson(cityId, proof)

    internal fun validateResidentRest(cityId: UUID, workerId: UUID) = life.validateResidentRest(cityId, workerId)

    internal fun terrainChanged(worldId: UUID, positions: Collection<CivPosition>) {
        checkCitizenMainThread()
        if (closed.get()) return
        positions.forEach { navigation.changed(worldId, it); worksiteTerrainChanged(worldId, it) }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) = audience.pruneViewers(event.player.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) = audience.pruneViewers()

    private fun invalidateCampfire(block: org.bukkit.block.Block) {
        if (block.type == Material.CAMPFIRE || block.type == Material.SOUL_CAMPFIRE)
            campfires.invalidate(block.world.uid, block.x shr 4, block.z shr 4)
    }

    private fun invalidatePath(block: org.bukkit.block.Block) {
        paths.invalidateBlock(block.world.uid, CivPosition(block.x, block.y, block.z))
        invalidateTerrain(block)
    }

    private fun invalidateTerrain(block: org.bukkit.block.Block) {
        navigation.changed(block.world.uid, CivPosition(block.x, block.y, block.z))
        worksiteTerrainChanged(block.world.uid, CivPosition(block.x, block.y, block.z))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCampfirePlace(event: BlockPlaceEvent) {
        invalidateCampfire(event.blockPlaced)
        // Trails' unchanged BlockPlaceEvent is a protection probe, not a new obstacle.
        if (event.blockPlaced.blockData != event.blockReplacedState.blockData) invalidatePath(event.blockPlaced)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCampfireBreak(event: BlockBreakEvent) {
        invalidateCampfire(event.block)
        invalidatePath(event.block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCampfireIgnite(event: BlockIgniteEvent) = invalidateCampfire(event.block)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCampfireInteract(event: PlayerInteractEvent) {
        event.clickedBlock?.let { invalidateCampfire(it); invalidateTerrain(it) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTerrainGrowth(event: org.bukkit.event.block.BlockGrowEvent) = invalidateTerrain(event.block)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTerrainForm(event: org.bukkit.event.block.BlockFormEvent) = invalidateTerrain(event.block)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTerrainFade(event: org.bukkit.event.block.BlockFadeEvent) = invalidateTerrain(event.block)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTerrainFlow(event: org.bukkit.event.block.BlockFromToEvent) = invalidateTerrain(event.toBlock)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTerrainFluid(event: org.bukkit.event.block.FluidLevelChangeEvent) = invalidateTerrain(event.block)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTerrainEntity(event: org.bukkit.event.entity.EntityChangeBlockEvent) = invalidateTerrain(event.block)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTerrainTree(event: org.bukkit.event.world.StructureGrowEvent) {
        event.blocks.forEach { invalidateTerrain(it.block) }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTerrainPiston(event: org.bukkit.event.block.BlockPistonExtendEvent) {
        event.blocks.forEach { invalidateTerrain(it); invalidateTerrain(it.getRelative(event.direction)) }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTerrainPistonRetract(event: org.bukkit.event.block.BlockPistonRetractEvent) {
        event.blocks.forEach { invalidateTerrain(it); invalidateTerrain(it.getRelative(event.direction.oppositeFace)) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onWorldUnload(event: WorldUnloadEvent) {
        campfires.removeWorld(event.world.uid)
        paths.removeWorld(event.world.uid)
        navigation.removeWorld(event.world.uid)
        worksiteWorldUnloaded(event.world.uid)
        actors.values.toList().filter { it.worldId == event.world.uid }.forEach { remove(it, "world-unloaded") }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChunkUnload(event: ChunkUnloadEvent) {
        campfires.invalidate(event.world.uid, event.chunk.x, event.chunk.z)
        paths.invalidateChunk(event.world.uid, event.chunk.x, event.chunk.z)
        navigation.removeChunk(event.world.uid, event.chunk.x, event.chunk.z)
        worksiteChunkUnloaded(event.world.uid, event.chunk.x, event.chunk.z)
        actors.values.toList().filter { actor ->
            actor.worldId == event.world.uid && (actor.route?.chunks?.contains(event.chunk.x, event.chunk.z) == true ||
                actor.plan.storage.inChunk(event.chunk.x, event.chunk.z) ||
                actor.plan.workplace?.inChunk(event.chunk.x, event.chunk.z) == true ||
                actor.npc.isValid && actor.npc.location.let { it.blockX shr 4 == event.chunk.x && it.blockZ shr 4 == event.chunk.z })
        }.forEach { remove(it, "chunk-unloaded") }
    }

    private fun remove(actor: CitizenActor, reason: String) {
        couriers.releaseCourierSource(actor, runtime.residentObservedAt)
        runCatching { life.stopMeal(actor) }.onFailure { runtime.logFailure(actor, "stop-meal", it) }
        actors.remove(actor.workerId)
        life.cancel(actor)
        logistics.cancel(actor)
        actor.cycle.close()
        runCatching { leisure.stopCamp(actor, reason); routes.cancelRoute(actor) }.onFailure { runtime.logFailure(actor, "cancel", it) }
        runCatching { threats.remove(actor.workerId) }.onFailure { runtime.logFailure(actor, "threat-retirement", it) }
        runCatching { actor.npc.remove() }.onFailure { runtime.logFailure(actor, "destroy", it) }
        statuses[actor.workerId] = reason
        CivsDiagnostics.event("citizen_retired", "city" to actor.cityId, "worker" to actor.workerId, "reason" to reason)
    }

    /** Terminal cleanup includes the actor's admitted client IDs, even if an observer cancels its copy. */
    fun closeAsync(): CompletableFuture<Void> {
        checkCitizenMainThread()
        retirement?.let { return it.copy() }
        val result = CompletableFuture<Void>()
        retirement = result
        closed.set(true)
        campfires.clear()
        voices.clear()
        navigation.clear()
        if (ownsNavigationTasks) residentTasks.close()
        paths.clear()
        val failure = runCatching { threats.close() }.exceptionOrNull()
        HandlerList.unregisterAll(this)
        packets.onInteract = null
        for (actor in actors.values) {
            runCatching { life.stopMeal(actor) }.onFailure { runtime.logFailure(actor, "stop-meal", it) }
            actor.cycle.close(); actor.route?.motion?.cancel(); actor.route?.previousMotion?.cancel()
        }
        actors.clear()
        combat.clear()
        life.close()
        logistics.close()
        pendingHarm.clear()
        admission.clear()
        workRuntime.clear()
        statuses.clear()
        val packetRetirement = try { packets.closeAsync() }
            catch (error: Throwable) { CompletableFuture.failedFuture<Void>(error) }
        CompletableFuture.allOf(packetRetirement, navigation.drained()).whenComplete { _, error ->
            val terminal = failure ?: error
            if (failure != null && error != null && failure !== error) failure.addSuppressed(error)
            if (terminal == null) result.complete(null) else result.completeExceptionally(terminal)
        }
        return result.copy()
    }

    override fun close() { closeAsync() }

    internal fun nudgeResident(npc: PacketPlayerNpc, impulse: Vector): Boolean = collisions.nudgeResident(npc, impulse)

    fun activateNavigating() = motion.activateNavigating()

    internal fun courierSources(cityId: UUID, courierId: UUID): Map<UUID, CivPosition> = couriers.courierSources(cityId, courierId)

    internal fun courierPosition(cityId: UUID, workerId: UUID): CivPosition? = couriers.courierPosition(cityId, workerId)

    internal fun courierUnavailable(cityId: UUID, workerId: UUID): Boolean = couriers.courierUnavailable(cityId, workerId)

    internal fun courierSupplySources(cityId: UUID, retainedSources: Set<UUID> = emptySet()): Map<UUID, CivPosition> = couriers.courierSupplySources(cityId, retainedSources)

    internal fun courierSourceCurrent(cityId: UUID, cargo: CivCargo): Boolean = couriers.courierSourceCurrent(cityId, cargo)

    internal fun requireCargoPickupArrival(cityId: UUID, workerId: UUID, cargo: CivCargo) = couriers.requireCargoPickupArrival(cityId, workerId, cargo)

    internal fun constructionReady(cityId: UUID, orderId: UUID): Boolean = workRuntime.constructionReady(cityId, orderId)

    internal fun requireConstructionArrival(cityId: UUID, workerId: UUID, orderId: UUID) = workRuntime.requireConstructionArrival(cityId, workerId, orderId)

    internal fun constructionPlaced(cityId: UUID, orderId: UUID, position: CivPosition, material: Material) = workRuntime.constructionPlaced(cityId, orderId, position, material)

    internal fun damage(workerId: UUID, amount: Double) = combat.damage(workerId, amount)

    fun reconcileResidents() = combat.reconcileResidents()

    fun status(workerId: UUID): String = observations.status(workerId)

    fun statusInputs(workerId: UUID): List<String> = observations.statusInputs(workerId)

    fun activeCount(): Int = observations.activeCount()

    internal fun debug(cityId: UUID?, workerId: UUID?): Map<String, Any?> = observations.debug(cityId, workerId)

    internal fun awakeResidentIds(cityId: UUID): Set<UUID> = observations.awakeResidentIds(cityId)

    internal fun workingResidentIds(cityId: UUID): Set<UUID> = observations.workingResidentIds(cityId)

    internal fun canReassign(cityId: UUID, workerId: UUID): Boolean = observations.canReassign(cityId, workerId)

    fun workplaceSnapshots(): List<CivsWorkplaceSnapshot> = observations.workplaceSnapshots()

    fun hologramSnapshots(): List<CivsNpcHologramSnapshot> = observations.hologramSnapshots()

    fun sceneEntityInformation(text: CivsText, bounds: ArcSceneBounds, entityIds: Set<String>, limit: Int): List<ArcSceneEntityInfo> = observations.sceneEntityInformation(text, bounds, entityIds, limit)

}

private fun CivPosition.inChunk(x: Int, z: Int) = this.x shr 4 == x && this.z shr 4 == z
