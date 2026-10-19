package ru.arc.civs

import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.ai.PathfinderType
import net.citizensnpcs.api.astar.pathfinder.BlockSource
import net.citizensnpcs.api.astar.pathfinder.PathPoint
import net.citizensnpcs.api.astar.pathfinder.BlockExaminer
import net.citizensnpcs.api.astar.pathfinder.MinecraftBlockExaminer
import net.citizensnpcs.api.astar.pathfinder.DoorExaminer
import net.citizensnpcs.api.event.NPCRightClickEvent
import net.citizensnpcs.api.event.NPCOpenDoorEvent
import net.citizensnpcs.api.npc.MemoryNPCDataStore
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.npc.NPCRegistry
import net.citizensnpcs.api.trait.trait.Equipment
import net.citizensnpcs.trait.LookClose
import net.citizensnpcs.trait.SkinTrait
import net.citizensnpcs.util.NMS
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Door
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.entity.Monster
import org.bukkit.attribute.Attribute
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityRegainHealthEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import ru.arc.observability.StructuredDebugLine
import ru.arc.paper.api.ArcNativeCleanupService
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import kotlin.math.abs
import kotlin.math.sqrt

/** A bounded, transient Citizens scene. The owning Core runtime calls [pulse] every 20 ticks. */
class CivsCitizens(
    private val plugin: Plugin,
    private val cityProvider: () -> Collection<Settlement>,
    private val delivery: (UUID, UUID, UUID, Long, Long, UUID?, Long) -> CompletableFuture<Settlement>,
    private val interact: (Player, UUID, UUID) -> Unit,
    private val maxActive: Int = 48,
    private val viewerRange: Int = 96,
    private val hurt: (UUID, UUID, Double) -> CompletableFuture<Settlement> = { _, _, _ ->
        CompletableFuture.failedFuture(CivsRejection("storage-unavailable")) },
    private val footstep: (UUID, UUID, Entity, Location, Location) -> Unit = { _, _, _, _, _ -> },
    private val courierPlan: (Settlement, CivWorker) -> CivCourierPlan? = { _, _ -> null },
    private val pickupCargo: (UUID, UUID, CivCargo) -> CompletableFuture<Settlement> = { _, _, _ ->
        CompletableFuture.failedFuture(CivsRejection("storage-unavailable")) },
    private val surfaceSpeed: (org.bukkit.block.Block) -> Double = { 1.0 },
    private val workPlan: (Settlement, CivWorker) -> CivWorldWork? = { _, _ -> null },
    private val workIdleReason: (Settlement, CivWorker) -> String = { _, _ -> "waiting-growth" },
    private val dropoffCargo: (UUID, UUID, CivCargo, UUID) -> CompletableFuture<Settlement> = { _, _, _, _ ->
        CompletableFuture.failedFuture(CivsRejection("storage-unavailable")) },
    private val doorBlocked: (UUID, CivPosition) -> Boolean = { _, _ -> false },
) : Listener, AutoCloseable {
    private val registry: NPCRegistry
    private val citizensProvider: Plugin
    private val nativeCleanup: ArcNativeCleanupService
    private val skins: List<CitizenSkin>
    private var actors = linkedMapOf<UUID, Actor>()
    private var statuses = mutableMapOf<UUID, String>()
    private var spawnRetries = mutableMapOf<UUID, Pair<CitizenPlan, CitizenRetry>>()
    @Volatile private var completions = ConcurrentLinkedQueue<Completion>()
    private val closed = AtomicBoolean()
    private var retirement: CompletableFuture<Void>? = null
    private var wanderBudget = 0
    private var recoveryBudget = 0
    private val pendingHarm = mutableSetOf<UUID>()
    private val harmCompletions = ConcurrentLinkedQueue<HarmCompletion>()
    private var threatCursor = 0

    init {
        require(maxActive in 1..48 && viewerRange in 1..96)
        checkMainThread()
        skins = loadCitizenSkins()
        nativeCleanup = requireNotNull(plugin.server.servicesManager.load(ArcNativeCleanupService::class.java)) {
            "ArcCore native cleanup service is unavailable"
        }
        citizensProvider = CitizensAPI.getPlugin()
        registry = CitizensAPI.createAnonymousNPCRegistry(MemoryNPCDataStore())
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun pulse(nowMillis: Long) {
        checkMainThread()
        if (closed.get()) return
        wanderBudget = 8 // Aggregate native target checks across all idle actors, not eight per NPC.
        recoveryBudget = 2 // At most two bounded safe-position searches after real route failures per pulse.
        drainCompletions(nowMillis)
        drainHarm()
        val cities = cityProvider().associateBy { it.id }
        val workers = cities.values.flatMap { city -> city.workers.map { it.id to (city to it) } }.toMap()
        statuses.keys.retainAll(workers.keys)
        spawnRetries.keys.retainAll(workers.keys)
        val viewers = viewers()

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
            val plan = citizenCurrentPlan(actor.plan, actor.cycle.phase) { plan(city, worker) }
            val reason = when {
                actor.cityId != city.id || actor.worldId != city.worldId -> "unavailable"
                world == null -> "world-unloaded"
                !actor.npc.isSpawned || !actor.npc.entity.isValid -> "unavailable"
                actor.npc.entity.world.uid != city.worldId -> "unavailable"
                !citizenHasViewer(city.worldId, actor.npc.entity.location.point(), viewers, viewerRange) -> "sleeping"
                plan == null -> "storage-unavailable"
                !citizenChunkReady(world, actor.npc.entity.location.blockX shr 4, actor.npc.entity.location.blockZ shr 4) ||
                    !plan.chunksReady(world) || actor.route?.chunks?.ready(world) == false -> "chunk-unloaded"
                else -> null
            }
            if (reason != null) {
                remove(actor, reason)
                continue
            }
            if (actor.plan != plan && (!citizenPlansContinue(actor.plan, requireNotNull(plan)) ||
                    actor.plan.courier?.carrying != plan.courier?.carrying)) {
                val nextPlan = requireNotNull(plan)
                if (citizenStorageNeedsReset(actor.plan.storage, nextPlan.storage, actor.npc.entity.location.point())) {
                    remove(actor, "waiting")
                    continue
                }
                cancelRoute(actor)
                actor.cycle.close()
                if (actor.plan.storage != nextPlan.storage || actor.plan.workplace != nextPlan.workplace) actor.targets.clear()
                actor.plan = nextPlan
                actor.cycle = nextPlan.cycle()
                actor.wander = CitizenWander()
                setEquipment(actor.npc, nextPlan)
            } else actor.plan = requireNotNull(plan)
            try {
                syncResident(actor.npc, worker, requireNotNull(plan))
                update(actor, city, requireNotNull(world), nowMillis)
            } catch (error: Exception) {
                pause(actor, "route-unreachable", nowMillis)
                logFailure(actor, "navigation", error)
            }
        }

        var budget = citizenSpawnBudget(actors.size, maxActive)
        for ((workerId, pair) in workers) {
            if (workerId in actors) continue
            val (city, worker) = pair
            if (workerId in pendingHarm) continue
            val plan = plan(city, worker)
            val world = plugin.server.getWorld(city.worldId)
            val reason = when {
                plan == null -> "storage-unavailable"
                world == null -> "world-unloaded"
                !citizenHasViewer(city.worldId, plan.spawnOrigin.point(), viewers, viewerRange) -> "sleeping"
                !plan.chunksReady(world) -> "chunk-unloaded"
                actors.size >= maxActive -> "capacity"
                else -> null
            }
            if (reason != null) {
                statuses[workerId] = reason
                if (reason == "sleeping") spawnRetries.remove(workerId)
                continue
            }
            if (budget == 0) {
                statuses[workerId] = "waiting"
                continue
            }
            val retry = spawnRetries[workerId]?.takeIf { it.first == plan }?.second ?: CitizenRetry()
            if (!retry.ready(nowMillis)) continue
            budget-- // Failed spawns consume the same per-pulse budget as successful spawns.
            var npc: NPC? = null
            try {
                val point = spawnPoint(requireNotNull(world), requireNotNull(plan).spawnOrigin, city.workers.indexOf(worker), viewers)
                if (point == null) {
                    retry.fail(nowMillis)
                    spawnRetries[workerId] = plan to retry
                    statuses[workerId] = "route-unreachable"
                    continue
                }
                npc = registry.createNPC(EntityType.PLAYER, citizenName(worker.name, workerId))
                configure(npc, workerId, plan)
                check(npc.spawn(point) { entity ->
                    val player = entity as Player
                    player.canPickupItems = false
                    player.isPersistent = false
                    player.isInvulnerable = false
                    player.isCollidable = true
                    player.isSilent = true
                }) { "Citizens rejected spawn" }
                val actor = Actor(city.id, workerId, city.worldId, npc, plan)
                actors[workerId] = actor
                syncResident(npc, worker, plan)
                spawnRetries.remove(workerId)
                update(actor, city, world, nowMillis)
                if (actors[workerId] === actor) CivsDiagnostics.event("citizen_spawn", "city" to city.id,
                    "worker" to workerId, "type" to npc.entity.type,
                    "skin" to npc.getOrAddTrait(SkinTrait::class.java).skinName,
                    "tool" to npc.getOrAddTrait(Equipment::class.java).get(Equipment.EquipmentSlot.HAND)?.type)
            } catch (error: Exception) {
                actors.remove(workerId)?.cycle?.close()
                runCatching { npc?.destroy() }.onFailure { plugin.logger.log(Level.WARNING,
                    "ArcCivs failed to remove rejected NPC city=${city.id} worker=$workerId", it) }
                retry.fail(nowMillis)
                spawnRetries[workerId] = requireNotNull(plan) to retry
                statuses[workerId] = "unavailable"
                if (retry.attempts == 1 || retry.exhausted) plugin.logger.log(Level.WARNING,
                    "ArcCivs NPC spawn failed city=${city.id} worker=$workerId attempt=${retry.attempts}", error)
            }
        }
        attractThreats()
    }

    /** Stable locale keys; no Bukkit objects or user-controlled text enter the key. */
    fun status(workerId: UUID): String {
        checkMainThread()
        if (workerId in pendingHarm) return "workers.status.injury-pending"
        if (!closed.get()) {
            val city = cityProvider().firstOrNull { it.workers.any { worker -> worker.id == workerId } }
            val worker = city?.workers?.firstOrNull { it.id == workerId }
            if (city != null && worker != null) {
                val actor = actors[workerId]
                val plan = if (actor == null) plan(city, worker)
                    else citizenCurrentPlan(actor.plan, actor.cycle.phase) { plan(city, worker) }
                plan?.idleReason?.let { return "workers.status.$it" }
                if (actors[workerId]?.plan?.token?.let { it != plan?.token } == true) return "workers.status.waiting"
            }
        }
        return "workers.status.${if (closed.get()) "closed" else statuses[workerId] ?: "sleeping"}"
    }

    fun activeCount(): Int {
        checkMainThread()
        return actors.size
    }

    /** Rechecked by the item receipt owner after waiting for another city commit. */
    internal fun requireStorageArrival(cityId: UUID, workerId: UUID, buildingId: UUID) {
        checkMainThread()
        val actor = actors[workerId]?.takeIf { it.cityId == cityId && it.npc.isSpawned && it.npc.entity.isValid }
            ?: throw CivsRejection("storage-unavailable")
        val city = cityProvider().firstOrNull { it.id == cityId } ?: throw CivsRejection("no-city")
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        val control = building.control ?: throw CivsRejection("control-missing")
        val entity = actor.npc.entity
        if (entity.world.uid != city.worldId || actor.workerId in pendingHarm ||
            actor.cycle.phase !in setOf(CitizenPhase.PICKING_UP, CitizenPhase.DELIVERING) ||
            !citizenAtStorage(entity.world, entity.location.point(), control)) throw CivsRejection("route-unreachable")
    }

    private fun plan(city: Settlement, worker: CivWorker): CitizenPlan? {
        val base = citizenPlan(city, worker, courierPlan(city, worker)) ?: return null
        if (base.courier != null || base.type !in CivsWorksites.TYPES || base.idleReason != null) return base
        val work = workPlan(city, worker)
        return if (work == null) base.copy(idleReason = workIdleReason(city, worker)) else base.copy(
            workplace = work.standing, workMillis = work.durationMillis, completeAtWorkplace = true, worldWork = work)
    }

    fun workplaceSnapshots(): List<CivsWorkplaceSnapshot> {
        checkMainThread()
        return cityProvider().flatMap { city -> city.buildings.mapNotNull { building ->
            building.control?.let { CivsWorkplaceSnapshot(building.id, city.worldId, it,
                CivsRules.effectiveWorkType(city, building), building.rotation) }
        } }
    }

    fun hologramSnapshots(): List<CivsNpcHologramSnapshot> {
        checkMainThread()
        return actors.values.mapNotNull { actor ->
            if (!actor.npc.isSpawned || !actor.npc.entity.isValid) return@mapNotNull null
            val city = cityProvider().firstOrNull { it.id == actor.cityId } ?: return@mapNotNull null
            val resident = city.workers.firstOrNull { it.id == actor.workerId } ?: return@mapNotNull null
            val entity = actor.npc.entity as? Player ?: return@mapNotNull null
            val at = entity.location
            val type = if (resident.cargo != null) BuildingType.WAREHOUSE else
                resident.assignment?.let(city::building)?.let { CivsRules.effectiveWorkType(city, it) }
            CivsNpcHologramSnapshot(resident.id, at.world!!.uid, at.x, at.y, at.z, resident.name,
                "workers.status.${statuses[resident.id] ?: "waiting"}", CivsResidents.health(resident), CivsResidents.maxHealth(resident),
                entity = entity, profession = type?.let(CivsRules::profession) ?: "unassigned",
                courierCart = type == BuildingType.WAREHOUSE && !actor.sheltering,
                cargo = resident.cargo?.let { mapOf(it.material to it.amount.toInt()) }.orEmpty(),
                yaw = entity.bodyYaw, nativeHeight = entity.height)
        }
    }

    /** Only a successful canonical reload resolves an injury with an unknown commit outcome. */
    fun reconcileResidents() {
        checkMainThread()
        pendingHarm.clear()
        harmCompletions.clear()
    }

    /** Called by one Core timer before Paper chooses active/inactive entity ticks. */
    fun activateNavigating() {
        checkMainThread()
        if (closed.get() || actors.isEmpty()) return
        val viewers = viewers()
        for (actor in actors.values) {
            val route = actor.route
            val activated = route != null && route.completion == null && actor.workerId !in pendingHarm &&
                activateCitizenRoute(actor.npc, registry, actor.worldId, viewers, viewerRange, route.chunks::ready)
            if (!activated) {
                actor.footsteps.reset()
                continue
            }
            val entity = actor.npc.entity
            val at = entity.location
            val city = cityProvider().firstOrNull { it.id == actor.cityId }
            if (city == null || !openUpcomingDoor(actor, city, at)) {
                pause(actor, "route-unreachable", System.currentTimeMillis())
                continue
            }
            if (!citizenWorkingTravel(actor.plan, actor.cycle.phase, actor.sheltering)) {
                actor.npc.navigator.localParameters.speedModifier(0.55f)
                actor.footsteps.reset()
                continue
            }
            val ground = at.world!!.getBlockAt(at.blockX, at.blockY - 1, at.blockZ)
            actor.npc.navigator.localParameters.speedModifier(surfaceSpeed(ground).toFloat())
            actor.footsteps.observe(entity.location, entity.isOnGround)?.let { (from, to) ->
                runCatching { footstep(actor.cityId, actor.workerId, entity, from, to) }
                    .onFailure { logFailure(actor, "footstep", it) }
            }
        }
    }

    private fun update(actor: Actor, city: Settlement, world: World, now: Long) {
        val cycle = actor.cycle
        val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: return
        if (world.time in 12_000L..23_000L && cycle.phase !in setOf(CitizenPhase.DELIVERING, CitizenPhase.PICKING_UP)) {
            shelter(actor, city, worker, world, now)
            return
        }
        if (actor.sheltering) {
            cancelRoute(actor)
            actor.sheltering = false
            actor.shelter = null
            actor.cycle.close()
            actor.cycle = actor.plan.cycle()
            return
        }
        actor.plan.idleReason?.let {
            if (it !in setOf("placing", "waiting-materials", "construction-paused")) wander(actor, city, world, now)
            statuses[actor.workerId] = it
            animateWork(actor, city, world, now)
            return
        }
        if (cycle.reason == "storage-full" && actor.plan.courier == null) {
            if (hasCapacity(city, actor.plan)) cycle.resume()
            else {
                statuses[actor.workerId] = "storage-full"
                return
            }
        }
        cycle.retry(now)
        if (cycle.phase == CitizenPhase.IDLE) {
            if (!hasCapacity(city, actor.plan)) cycle.pause("storage-full", now, false)
            else cycle.start()
        }
        when (cycle.phase) {
            CitizenPhase.TO_STORAGE, CitizenPhase.TO_WORK, CitizenPhase.RETURNING -> {
                val target = if (cycle.phase == CitizenPhase.TO_WORK) requireNotNull(actor.plan.workplace) else actor.plan.storage
                if (actor.route == null) startRoute(actor, world, target, now)
                val route = actor.route
                if (route != null) {
                    if (!route.chunks.ready(world)) {
                        remove(actor, "chunk-unloaded")
                        return
                    } else {
                        if (route.completion == null && route.path == null) observePath(actor, route)
                        val outcome = citizenNavigationOutcome(route.completion,
                            arrivedAt(actor, city, world, target),
                            now - route.startedAt, route.timeoutMillis, actor.npc.navigator.isNavigating)
                        when (outcome) {
                            CitizenNavigationOutcome.PENDING -> Unit
                            CitizenNavigationOutcome.ARRIVED -> {
                                cancelRoute(actor)
                                cycle.arrive(now)
                            }
                            else -> pauseNavigation(actor, "route-unreachable", outcome.name.lowercase(), route.target, now)
                        }
                    }
                }
            }
            CitizenPhase.WORKING -> {
                val target = requireNotNull(actor.plan.workplace)
                if (!arrivedAt(actor, city, world, target))
                    pauseNavigation(actor, "route-unreachable", "left-workplace", resolvedTarget(actor, target).location(world), now)
                else cycle.work(now)
            }
            CitizenPhase.PICKUP_READY -> {
                val target = requireNotNull(actor.plan.workplace)
                if (!arrivedAt(actor, city, world, target))
                    pauseNavigation(actor, "route-unreachable", "left-pickup-point", resolvedTarget(actor, target).location(world), now)
                else cycle.beginPickup()?.let { ticket -> submitPickup(actor, ticket) }
            }
            CitizenPhase.READY -> {
                val target = if (actor.plan.completeAtWorkplace) requireNotNull(actor.plan.workplace) else actor.plan.storage
                if (!arrivedAt(actor, city, world, target)) {
                    pauseNavigation(actor, "route-unreachable", "left-delivery-point", resolvedTarget(actor, target).location(world), now)
                } else if (!hasCapacity(city, actor.plan)) {
                    cycle.pause("storage-full", now, true)
                } else cycle.beginDelivery(now)?.let { ticket -> submitDelivery(actor, ticket, now) }
            }
            else -> Unit
        }
        if (actors[actor.workerId] === actor) {
            statuses[actor.workerId] = if (cycle.phase == CitizenPhase.WORKING) actor.plan.worldWork?.status ?: cycle.status() else cycle.status()
            animateWork(actor, city, world, now)
        }
    }

    private fun animateWork(actor: Actor, city: Settlement, world: World, now: Long) {
        val working = citizenWorking(actor.plan, actor.cycle.phase, city.construction?.phase)
        if (!working || actor.npc.navigator.isNavigating) return
        val workplace = actor.plan.workplace ?: return
        if (!arrivedAt(actor, city, world, workplace)) return
        if (!actor.animation.swing(now)) return
        val player = actor.npc.entity as Player
        val focus = if (city.buildings.any { it.control == workplace })
            Location(world, workplace.x + 0.5, workplace.y + 0.7, workplace.z + 0.5)
        else citizenWorkBuilding(city, actor.plan)?.bounds?.let { bounds ->
            Location(world,
                (bounds.min.x.toDouble() + bounds.max.x) / 2.0 + 0.5,
                player.eyeLocation.y,
                (bounds.min.z.toDouble() + bounds.max.z) / 2.0 + 0.5)
        }
        focus?.let { citizenLookDirection(player.eyeLocation, it).let { (yaw, pitch) -> NMS.look(player, yaw, pitch) } }
        player.swingMainHand()
    }

    private fun wander(actor: Actor, city: Settlement, world: World, now: Long) {
        val route = actor.route
        if (route != null) {
            val outcome = if (!route.chunks.ready(world)) CitizenNavigationOutcome.STOPPED
                else citizenNavigationOutcome(route.completion,
                    arrived(actor.npc.entity.location, route.target),
                    now - route.startedAt, route.timeoutMillis, actor.npc.navigator.isNavigating)
            if (outcome == CitizenNavigationOutcome.PENDING) return
            cancelRoute(actor)
            actor.wander.finish(now, outcome == CitizenNavigationOutcome.ARRIVED)
            CivsDiagnostics.event("citizen_wander", "city" to city.id, "worker" to actor.workerId,
                "outcome" to outcome.name.lowercase(), "target" to route.target.point().coordinates())
            return
        }
        if (!actor.wander.ready(now) || wanderBudget == 0) return
        wanderBudget--
        // Keep short invalid-target retries fair within the shared eight-check budget.
        citizenRotateAfterAdmission(actors, actor.workerId)
        val at = actor.npc.entity.location
        val target = citizenWanderTarget(city, CivPosition(at.blockX, at.blockY, at.blockZ), actor.workerId, actor.wander.step)
        // startRoute owns the single loaded-chunk, support and headroom check.
        if (target != null && startRoute(actor, world, target, now)) {
            CivsDiagnostics.event("citizen_wander", "city" to city.id,
                "worker" to actor.workerId, "outcome" to "started", "target" to target)
        } else {
            actor.wander.reject(now)
            if (actor.wander.step % CITIZEN_WANDER_HEIGHTS.size == 0) {
                CivsDiagnostics.event("citizen_wander", "city" to city.id, "worker" to actor.workerId,
                    "outcome" to "target-unavailable", "target" to target, "next_step" to actor.wander.step)
            }
        }
    }

    private fun shelter(actor: Actor, city: Settlement, worker: CivWorker, world: World, now: Long) {
        if (!actor.sheltering) {
            cancelRoute(actor)
            actor.sheltering = true
        }
        if (actor.shelter == null && wanderBudget > 0) {
            wanderBudget--
            actor.shelter = citizenShelterTarget(city, worker) { citizenStandable(world, it) }
        }
        statuses[actor.workerId] = "sheltering"
        val target = actor.shelter ?: return
        if (arrived(actor.npc.entity.location, target.location(world))) { cancelRoute(actor); return }
        val route = actor.route
        if (route == null) { startRoute(actor, world, target, now); return }
        if (route.completion != null || now - route.startedAt > route.timeoutMillis) {
            cancelRoute(actor)
            actor.shelter = citizenShelterTarget(city, worker) { citizenStandable(world, it) }
        }
    }

    private fun startRoute(actor: Actor, world: World, target: CivPosition, now: Long): Boolean {
        val from = actor.npc.entity.location
        val city = cityProvider().firstOrNull { it.id == actor.cityId } ?: return false
        val storage = city.buildings.firstOrNull { it.control == target }
        if (storage != null) {
            val cached = actor.targets[target]?.takeIf { citizenAtStorage(world, it.point(), target) && citizenStandable(world, it) }
            val standing = cached ?: citizenStorageTarget(city, storage, world)
                ?: storage.takeIf { !citizenStorageReady(world, target) && it.phase != BuildingPhase.ACTIVE && city.construction?.buildingId == it.id }
                    ?.entrance?.takeIf { citizenStandable(world, it) }
            if (standing == null) {
                actor.targets.remove(target)
                pause(actor, "storage-unavailable", now)
                return false
            }
            actor.targets[target] = standing
        }
        val resolved = resolvedTarget(actor, target)
        val to = resolved.location(world)
        val distance = from.distance(to)
        if (!citizenRouteWithinReach(from.point(), to.point())) {
            if (actor.plan.idleReason == null) pauseNavigation(actor, "route-unreachable", "outside-route-range", to, now)
            return false
        }
        val chunks = CitizenChunks.between(from.blockX, from.blockZ, resolved.x, resolved.z)
        if (!chunks.ready(world)) {
            if (actor.plan.idleReason == null) remove(actor, "chunk-unloaded")
            return false
        }
        if (!citizenStandable(world, resolved)) {
            if (actor.plan.idleReason == null) pauseNavigation(actor, "route-unreachable", "target-not-standable", to, now)
            return false
        }
        if (actor.plan.idleReason == null && arrivedAt(actor, city, world, target)) {
            actor.cycle.arrive(now)
            return true
        }
        // Citizens itself retains the already-loaded destination while navigating. ArcCivs never loads a chunk.
        val navigator = actor.npc.navigator
        val allowed = citizenRouteBuildings(city, actor.plan, target, CivPosition(from.blockX, from.blockY, from.blockZ))
        navigator.defaultParameters.range((distance + 8).coerceAtLeast(16.0).toFloat())
            .speedModifier(if (citizenWorkingTravel(actor.plan, actor.cycle.phase, actor.sheltering)) 1f else 0.55f)
            .pathfinderType(PathfinderType.CITIZENS).clearExaminers().examiner(object : MinecraftBlockExaminer(actor.npc) {
                override fun canStandAt(source: BlockSource, point: PathPoint): BlockExaminer.StandableState {
                    val p = point.vector
                    if (!citizenRouteCellAllowed(city, allowed, p.blockX, p.blockZ) ||
                        !world.isChunkLoaded(p.blockX shr 4, p.blockZ shr 4)) return BlockExaminer.StandableState.NOT_STANDABLE
                    return super.canStandAt(source, point)
                }
                override fun getCost(source: BlockSource, point: PathPoint): Float {
                    val p = point.vector
                    val speed = if (world.isChunkLoaded(p.blockX shr 4, p.blockZ shr 4))
                        surfaceSpeed(world.getBlockAt(p.blockX, p.blockY - 1, p.blockZ)) else 1.0
                    return super.getCost(source, point) + (4.0 / speed.coerceIn(0.2, 5.0)).toFloat()
                }
            }).examiner(DoorExaminer())
        val route = Route(from.point(), to, chunks, now, (10_000 + distance * 1_500).toLong().coerceAtMost(90_000), allowed)
        actor.route = route
        // Citizens owns native player movement. Its early completion still passes through
        // our exact arrived check and bounded recovery before any work can begin.
        NMS.look(actor.npc.entity, from.yaw, 0f)
        navigator.setTarget(to)
        route.strategy = navigator.pathStrategy?.javaClass?.simpleName
        navigator.localParameters.addSingleUseCallback { reason ->
            if (actor.route === route) {
                observePath(actor, route)
                route.callbackPosition = actor.npc.entity.location.point()
                route.callbackNavigating = navigator.isNavigating
                route.completion = CitizenNavigationCompletion(reason?.name)
            }
        }
        return true
    }

    /** The native strategy exposes its current node; its full path also contains already passed nodes. */
    private fun openUpcomingDoor(actor: Actor, city: Settlement, at: Location): Boolean {
        val route = actor.route ?: return true
        val next = actor.npc.navigator.pathStrategy?.currentDestination ?: return true
        if (next.world != at.world || next.distanceSquared(at) > 2.5 * 2.5) return true
        for (dy in 0..1) {
            val point = CivPosition(next.blockX, next.blockY + dy, next.blockZ)
            if (!citizenOpenRouteDoor(actor.npc, city, route.allowedBuildings, at, point, doorBlocked)) return false
        }
        return true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onOpenDoor(event: NPCOpenDoorEvent) {
        if (event.npc.owningRegistry !== registry) return
        val actor = actors.values.firstOrNull { it.npc === event.npc }
        val city = actor?.let { current -> cityProvider().firstOrNull { it.id == current.cityId } }
        val route = actor?.route
        val pair = citizenDoorPair(event.door.world, CivPosition(event.door.x, event.door.y, event.door.z))
        if (city == null || route == null || pair == null || listOf(pair.first, pair.second).any { block ->
                val point = CivPosition(block.x, block.y, block.z)
                !citizenDoorAllowed(city, route.allowedBuildings, point) || doorBlocked(block.world.uid, point)
            }) event.isCancelled = true
    }

    private fun observePath(actor: Actor, route: Route) {
        // Native navigation can clear its path before invoking the completion callback.
        // Retain only a bounded endpoint snapshot, never the path or world blocks.
        runCatching {
            actor.npc.navigator.pathStrategy?.path?.asSequence()?.map { CitizenPoint(it.x, it.y, it.z) }
                ?.asIterable()?.let(::citizenPathSnapshot)
        }.getOrNull()?.let { route.path = it }
    }

    private fun pauseNavigation(actor: Actor, status: String, detail: String, target: Location, now: Long) {
        val route = actor.route
        if (route != null && route.completion == null) observePath(actor, route)
        val entity = actor.npc.entity as Player
        val actual = entity.location.point()
        val velocity = entity.velocity
        val navigator = actor.npc.navigator
        val parameters = navigator.localParameters
        // The cycle permits three attempts, so failures produce at most three lines per actor/cycle.
        plugin.logger.warning(NAVIGATION_DIAGNOSTIC.line(
            "city" to actor.cityId, "worker" to actor.workerId, "cycle" to actor.plan.token.completedCycles,
            "attempt" to actor.cycle.failedAttempts + 1, "phase" to actor.cycle.phase, "reason" to detail,
            "callback" to (route?.completion?.let { it.cancelReason ?: "null" } ?: "not-received"),
            "from" to route?.from?.coordinates(), "target" to target.point().coordinates(), "actual" to actual.coordinates(),
            "distance" to sqrt(actual.distanceSquared(target.point())), "elapsed_ms" to route?.let { now - it.startedAt },
            "timeout_ms" to route?.timeoutMillis, "navigating" to navigator.isNavigating,
            "callback_navigating" to route?.callbackNavigating, "callback_actual" to route?.callbackPosition?.coordinates(),
            "velocity" to CitizenPoint(velocity.x, velocity.y, velocity.z).coordinates(),
            "has_ai" to entity.hasAI(), "collidable" to entity.isCollidable,
            "path_nodes" to route?.path?.nodes, "path_end" to route?.path?.end?.coordinates(),
            "path_truncated" to route?.path?.truncated, "range" to parameters.range(),
            "distance_margin" to parameters.distanceMargin(), "path_distance_margin" to parameters.pathDistanceMargin(),
            "strategy" to route?.strategy,
        ))
        if (!recoverNavigation(actor, target, now)) pause(actor, status, now)
    }

    private fun resolvedTarget(actor: Actor, target: CivPosition): CivPosition = actor.targets[target] ?: target

    private fun arrivedAt(actor: Actor, city: Settlement, world: World, target: CivPosition): Boolean {
        if (!arrived(actor.npc.entity.location, resolvedTarget(actor, target).location(world))) return false
        val storage = city.buildings.firstOrNull { it.control == target } ?: return true
        return citizenAtStorage(world, actor.npc.entity.location.point(), target) ||
            !citizenStorageReady(world, target) && storage.phase != BuildingPhase.ACTIVE && city.construction?.buildingId == storage.id &&
            resolvedTarget(actor, target) == storage.entrance
    }

    /** Retry a supported nearby endpoint on foot; arrival remains the navigator's responsibility. */
    private fun recoverNavigation(actor: Actor, target: Location, now: Long): Boolean {
        // A shipment must traverse the route. Recovery teleportation cannot transport items.
        if (recoveryBudget == 0 || actor.plan.idleReason != null || actor.plan.courier != null) return false
        val city = cityProvider().firstOrNull { it.id == actor.cityId && it.pendingDeletion == null } ?: return false
        val entity = actor.npc.entity
        if (!entity.isValid || entity.world.uid != city.worldId || target.world?.uid != city.worldId) return false
        val authored = if (actor.cycle.phase in setOf(CitizenPhase.TO_WORK, CitizenPhase.WORKING) ||
            actor.cycle.phase == CitizenPhase.READY && actor.plan.completeAtWorkplace) actor.plan.workplace ?: return false else actor.plan.storage
        recoveryBudget--
        citizenRotateAfterAdmission(actors, actor.workerId)
        val viewers = viewers()
        val safe: (CivPosition) -> Boolean = { candidate ->
            citizenStandable(entity.world, candidate) &&
                citizenHasViewer(city.worldId, candidate.point(), viewers, viewerRange)
        }
        val storage = city.buildings.firstOrNull { it.control == authored }
        val point = (if (storage != null) citizenStorageTarget(city, storage, entity.world, safe)
            else citizenRecoveryTarget(city, authored, safe)) ?: return false
        val destination = point.location(entity.world)
        cancelRoute(actor)
        if (actor.targets[authored] == point && !arrived(entity.location, destination)) return false
        actor.targets[authored] = point
        if (!startRoute(actor, entity.world, authored, now)) return false
        CivsDiagnostics.event("citizen_route_recovered", "city" to city.id, "worker" to actor.workerId,
            "phase" to actor.cycle.phase, "requested" to authored, "destination" to point)
        return true
    }

    private fun submitDelivery(actor: Actor, ticket: UUID, now: Long) {
        val token = actor.plan.token
        val actorId = actor.instanceId
        val workerId = actor.workerId
        val future = try {
            actor.plan.courier?.let { dropoffCargo(actor.cityId, actor.workerId, it.cargo, it.destinationBuildingId) }
                ?: delivery(actor.cityId, actor.workerId, requireNotNull(token.assignmentId), token.completedCycles,
                    token.nextDeliveryAt, token.constructionId, now)
        } catch (error: Exception) {
            CompletableFuture.failedFuture(error)
        }
        future.whenComplete { city, error ->
            // Only immutable domain data crosses this boundary; Bukkit and actor state stay on pulse().
            val completion = Completion(workerId, actorId, ticket, token, city, error)
            if (!closed.get()) {
                completions.add(completion)
                if (closed.get()) completions.remove(completion)
            }
        }
    }

    private fun submitPickup(actor: Actor, ticket: UUID) {
        val token = actor.plan.token
        val actorId = actor.instanceId
        val workerId = actor.workerId
        val future = try { pickupCargo(actor.cityId, workerId, requireNotNull(actor.plan.courier).cargo) }
            catch (error: Exception) { CompletableFuture.failedFuture(error) }
        future.whenComplete { city, error ->
            val completion = Completion(workerId, actorId, ticket, token, city, error, pickup = true)
            if (!closed.get()) {
                completions.add(completion)
                if (closed.get()) completions.remove(completion)
            }
        }
    }

    private fun drainCompletions(now: Long) {
        while (true) {
            val completion = completions.poll() ?: return
            val actor = actors[completion.workerId] ?: continue
            if (actor.instanceId != completion.actorId || actor.plan.token != completion.token ||
                !(if (completion.pickup) actor.cycle.acceptsPickup(completion.ticket)
                    else actor.cycle.acceptsCompletion(completion.ticket))) continue
            val error = (completion.error as? CompletionException)?.cause ?: completion.error
            if (error != null) {
                if (completion.pickup) actor.cycle.failPickup((error as? CivsRejection)?.reason, now)
                else actor.cycle.failDelivery((error as? CivsRejection)?.reason, now)
                if (error !is CivsRejection) logFailure(actor, "delivery", error)
            } else {
                val worker = completion.city?.workers?.find { it.id == actor.workerId }
                if (completion.pickup) {
                    val cargo = worker?.cargo
                    if (cargo == null || cargo.id != actor.plan.courier?.cargo?.id ||
                        worker.completedCycles != completion.token.completedCycles) actor.cycle.failPickup(null, now)
                    else {
                        actor.plan = actor.plan.copy(courier = requireNotNull(actor.plan.courier).copy(cargo = cargo, carrying = true))
                        actor.cycle.pickedUp()
                        actor.reportedFailure = false
                    }
                } else if (worker == null || worker.completedCycles != completion.token.completedCycles + 1 ||
                    actor.plan.courier != null && worker.cargo != null) {
                    actor.cycle.failDelivery(null, now)
                } else {
                    // The provider publishes durable state. Reconciliation starts a new fenced cycle, never this callback.
                    actor.cycle.close()
                    actor.reportedFailure = false
                }
            }
        }
    }

    private fun pause(actor: Actor, reason: String, now: Long) {
        cancelRoute(actor)
        actor.cycle.pause(reason, now, false)
    }

    private fun cancelRoute(actor: Actor) {
        actor.route = null
        actor.footsteps.reset()
        actor.npc.navigator.cancelNavigation()
    }

    private fun logFailure(actor: Actor, phase: String, error: Throwable) {
        if (actor.reportedFailure) return
        actor.reportedFailure = true
        plugin.logger.log(Level.WARNING,
            "ArcCivs NPC failure city=${actor.cityId} worker=${actor.workerId} phase=$phase cycle=${actor.plan.token.completedCycles}", error)
    }

    private fun configure(npc: NPC, workerId: UUID, plan: CitizenPlan) {
        npc.isProtected = false
        npc.setUseMinecraftAI(false)
        npc.setAlwaysUseNameHologram(false)
        npc.data().set(NPC.Metadata.KEEP_CHUNK_LOADED, false)
        npc.data().set(NPC.Metadata.SHOULD_SAVE, false)
        npc.data().set(NPC.Metadata.REMOVE_FROM_PLAYERLIST, true)
        npc.data().set(NPC.Metadata.REMOVE_FROM_TABLIST, true)
        npc.data().set(NPC.Metadata.PICKUP_ITEMS, false)
        npc.data().set(NPC.Metadata.DROPS_ITEMS, false)
        npc.data().set(NPC.Metadata.RESPAWN_DELAY, -1)
        npc.data().set(NPC.Metadata.COLLIDABLE, true)
        npc.data().set(NPC.Metadata.FLUID_PUSHABLE, false)
        npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, false)
        npc.navigator.defaultParameters.pathfinderType(PathfinderType.MINECRAFT).stuckAction(null)
            .destinationTeleportMargin(-1.0).distanceMargin(0.35).pathDistanceMargin(0.35)
            .straightLineTargetingDistance(0f).stationaryTicks(100).range(96f).speedModifier(1.0f).lookAtFunction(null)
        npc.getOrAddTrait(LookClose::class.java).apply { lookClose(false); setRandomLook(false) }
        val skin = citizenSkin(skins, workerId)
        npc.getOrAddTrait(SkinTrait::class.java).apply {
            setFetchDefaultSkin(false)
            setShouldUpdateSkins(false)
            setSkinPersistent(skin.name, skin.signature, skin.texture)
        }
        setEquipment(npc, plan)
    }

    private fun setEquipment(npc: NPC, plan: CitizenPlan) {
        // An unsupplied work tool is a visual prop; it never enters a personal or town inventory.
        val equipment = npc.getOrAddTrait(Equipment::class.java)
        val material = citizenTool(plan)
        if (equipment.get(Equipment.EquipmentSlot.HAND)?.type != material)
            equipment.set(Equipment.EquipmentSlot.HAND, material?.let(::ItemStack))
    }

    private fun syncResident(npc: NPC, worker: CivWorker, plan: CitizenPlan) {
        val player = npc.entity as Player
        player.getAttribute(Attribute.MAX_HEALTH)?.let { if (it.baseValue != CivsResidents.maxHealth(worker)) it.baseValue = CivsResidents.maxHealth(worker) }
        if (player.health != CivsResidents.health(worker)) player.health = CivsResidents.health(worker)
        val actor = actors[worker.id]
        val items = CivsEquipment.slots(worker)
        if (actor?.equipmentItems != items || actor?.equipmentType != plan.type) {
            val equipment = npc.getOrAddTrait(Equipment::class.java)
            val nativeSlots = listOf(Equipment.EquipmentSlot.HELMET, Equipment.EquipmentSlot.CHESTPLATE,
                Equipment.EquipmentSlot.LEGGINGS, Equipment.EquipmentSlot.BOOTS, Equipment.EquipmentSlot.HAND, Equipment.EquipmentSlot.OFF_HAND)
            nativeSlots.forEachIndexed { index, slot ->
                equipment.set(slot, items[index]?.stack() ?: if (index == 4) citizenTool(plan)?.let(::ItemStack) else null)
            }
            actor?.equipmentItems = items
            actor?.equipmentType = plan.type
        }
    }

    /** Player NPCs are absent from the native player list; explicitly offer them to idle nearby monsters. */
    private fun attractThreats() {
        val available = actors.values.toList()
        if (available.isEmpty()) return
        repeat(minOf(2, available.size)) {
            val actor = available[Math.floorMod(threatCursor++, available.size)]
            if (!actor.npc.isSpawned || actor.workerId in pendingHarm) return@repeat
            val player = actor.npc.entity as Player
            runCatching {
                // Native spatial lookup is indivisible; only two queries and 16 LOS/target checks per query per pulse.
                for (entity in player.world.getNearbyEntities(player.location, 8.0, 4.0, 8.0) { it is Monster }.take(16)) {
                    val monster = entity as Monster
                    if (monster.isValid && monster.type !in NEUTRAL_MONSTERS && monster.target == null && monster.hasLineOfSight(player))
                        monster.target = player
                }
            }.onFailure { logFailure(actor, "threat-target", it) }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val actor = actors.values.firstOrNull { it.npc.isSpawned && it.npc.entity.uniqueId == event.entity.uniqueId } ?: return
        // Native death must never race a durable city write or create an automatic Citizens respawn.
        event.isCancelled = true
        if (closed.get() || actor.workerId in pendingHarm) return
        val at = System.currentTimeMillis()
        if (at < actor.damageAfter) return
        val city = cityProvider().firstOrNull { it.id == actor.cityId } ?: return
        val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: return
        val damage = CivsResidents.damage(worker, actor.plan.type, event.finalDamage)
        if (!damage.isFinite() || damage <= 0) return
        actor.damageAfter = at + 500
        pendingHarm += actor.workerId
        runCatching { cancelRoute(actor) }.onFailure { logFailure(actor, "injury-stop", it) }
        actor.cycle.close()
        val completionQueue = harmCompletions
        val future = try { hurt(actor.cityId, actor.workerId, damage) }
            catch (error: Exception) { CompletableFuture.failedFuture(error) }
        future.whenComplete { saved, error ->
            if (!closed.get()) completionQueue.add(HarmCompletion(actor.workerId, saved, error))
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onRegainHealth(event: EntityRegainHealthEvent) {
        if (actors.values.any { it.npc.isSpawned && it.npc.entity.uniqueId == event.entity.uniqueId }) event.isCancelled = true
    }

    private fun drainHarm() {
        while (true) {
            val result = harmCompletions.poll() ?: return
            val actor = actors[result.workerId]
            if (result.error != null) {
                // Keep this identity frozen until canonical recovery; never replay an unknown commit.
                if (actor != null) logFailure(actor, "injury", result.error)
                continue
            }
            pendingHarm -= result.workerId
            if (actor != null) {
                runCatching {
                    val worker = result.city?.workers?.firstOrNull { it.id == result.workerId }
                    if (worker == null) remove(actor, "dead")
                    else if (!actor.npc.isSpawned || !actor.npc.entity.isValid) remove(actor, "unavailable")
                    else {
                        actor.cycle = actor.plan.cycle()
                        syncResident(actor.npc, worker, actor.plan)
                        (actor.npc.entity as Player).playHurtAnimation(0f)
                    }
                }.onFailure {
                    logFailure(actor, "injury-publication", it)
                    remove(actor, "unavailable") // The next actor comes from the committed health, never from a replayed hit.
                }
            }
        }
    }

    private fun spawnPoint(world: World, storage: CivPosition, ordinal: Int, viewers: List<CitizenViewer>): Location? {
        val offsets = listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, -1 to 1,
            1 to -1, -1 to -1, 2 to 0, -2 to 0, 0 to 2, 0 to -2)
        return offsets.indices.asSequence().map { offsets[(it + ordinal) % offsets.size] }
            .map { (x, z) -> CivPosition(storage.x + x, storage.y, storage.z + z) }
            .filter { citizenStandable(world, it) }
            .map { it.location(world) }
            .filter { citizenHasViewer(world.uid, it.point(), viewers, viewerRange) }
            .firstOrNull { location -> actors.values.none { actor ->
                actor.npc.isSpawned && actor.worldId == world.uid && actor.npc.entity.location.distanceSquared(location) < 0.8
            } }
    }

    private fun arrived(from: Location, to: Location): Boolean {
        if (from.world != to.world || abs(from.y - to.y) > 0.5) return false
        val offset = to.toVector().subtract(from.toVector())
        val distance = offset.length()
        if (!citizenNearEndpoint(from.point(), CivPosition(to.blockX, to.blockY, to.blockZ))) return false
        if (distance < 0.01) return true
        return from.world!!.rayTraceBlocks(from.clone().add(0.0, 0.8, 0.0), offset.normalize(), distance,
            FluidCollisionMode.ALWAYS, true) == null
    }

    private fun viewers(excluding: UUID? = null): List<CitizenViewer> = plugin.server.onlinePlayers.asSequence()
        .filter { it.isOnline && it.uniqueId != excluding && !it.hasMetadata("NPC") }
        .map { CitizenViewer(it.world.uid, it.location.point()) }.toList()

    private fun pruneViewers(excluding: UUID? = null) {
        val viewers = viewers(excluding)
        actors.values.toList().filter { !it.npc.isSpawned ||
            !citizenHasViewer(it.worldId, it.npc.entity.location.point(), viewers, viewerRange) }
            .forEach { remove(it, "sleeping") }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onClick(event: NPCRightClickEvent) {
        if (event.npc.owningRegistry !== registry) return
        event.isCancelled = true
        event.isDelayedCancellation = true
        if (closed.get()) return
        val actor = actors.values.find { it.npc === event.npc } ?: return
        if (event.clicker.isOnline && event.clicker.world.uid == actor.worldId)
            interact(event.clicker, actor.cityId, actor.workerId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) = pruneViewers(event.player.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) = pruneViewers()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        actors.values.firstOrNull { it.npc.isSpawned && it.npc.entity.uniqueId == event.player.uniqueId }
            ?.footsteps?.reset()
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onWorldUnload(event: WorldUnloadEvent) {
        actors.values.toList().filter { it.worldId == event.world.uid }.forEach { remove(it, "world-unloaded") }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChunkUnload(event: ChunkUnloadEvent) {
        actors.values.toList().filter { actor ->
            actor.worldId == event.world.uid && (actor.route?.chunks?.contains(event.chunk.x, event.chunk.z) == true ||
                actor.plan.storage.inChunk(event.chunk.x, event.chunk.z) ||
                actor.plan.workplace?.inChunk(event.chunk.x, event.chunk.z) == true ||
                actor.npc.isSpawned && actor.npc.entity.location.let { it.blockX shr 4 == event.chunk.x && it.blockZ shr 4 == event.chunk.z })
        }.forEach { remove(it, "chunk-unloaded") }
    }

    private fun remove(actor: Actor, reason: String) {
        actors.remove(actor.workerId)
        actor.cycle.close()
        runCatching { cancelRoute(actor) }.onFailure { logFailure(actor, "cancel", it) }
        runCatching { actor.npc.destroy() }.onFailure { logFailure(actor, "destroy", it) }
        statuses[actor.workerId] = reason
    }

    /** Completes after the exact old NPC registry is empty; cancelling an observer cannot cancel cleanup. */
    fun closeAsync(): CompletableFuture<Void> {
        checkMainThread()
        retirement?.let { return it.copy() }
        val result = CompletableFuture<Void>()
        retirement = result
        closed.set(true)
        HandlerList.unregisterAll(this)
        val oldActors = actors
        actors = linkedMapOf()
        completions = ConcurrentLinkedQueue()
        spawnRetries = mutableMapOf()
        statuses = mutableMapOf()
        var failure: Throwable? = null
        fun attempt(operation: () -> Unit) {
            try { operation() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        fun requireProvider() = check(citizensProvider.isEnabled) {
            "Citizens is unavailable during ArcCivs actor retirement"
        }
        val attempted = IdentityHashMap<NPC, Boolean>()
        val steps = sequence {
            for (actor in oldActors.values) {
                actor.cycle.close()
                requireProvider()
                attempted[actor.npc] = true
                attempt { cancelRoute(actor) }
                attempt { actor.npc.destroy() }
                yield(Unit)
            }
            // Citizens 2.0.42 exposes a live fastutil iterator. Capture one reference per step
            // before destroying these leftovers, including rejected partial spawns.
            requireProvider()
            val orphans = ArrayList<NPC>()
            val iterator = registry.iterator()
            while (iterator.hasNext()) {
                val npc = iterator.next()
                if (!attempted.containsKey(npc)) orphans.add(npc)
                yield(Unit)
            }
            for (npc in orphans) {
                requireProvider()
                attempt { npc.navigator.cancelNavigation() }
                attempt { npc.destroy() }
                yield(Unit)
            }
            requireProvider()
            attempt {
                check(!registry.iterator().hasNext()) { "ArcCivs Citizens registry still contains retired NPCs" }
                // Empty by construction: do not hide a bulk native tail in deregisterAll().
                registry.deregisterAll()
            }
            yield(Unit)
        }.iterator()
        try {
            nativeCleanup.submit("ArcCivs:citizens") {
                checkMainThread()
                if (steps.hasNext()) { steps.next(); false }
                else { failure?.let { throw it }; true }
            }.whenComplete { _, error ->
                if (error == null) result.complete(null) else result.completeExceptionally(error)
            }
        } catch (error: Throwable) { result.completeExceptionally(error) }
        return result.copy()
    }

    override fun close() { closeAsync() }

    private fun checkMainThread() = check(Bukkit.isPrimaryThread()) { "ArcCivs Citizens access must be on the server thread" }

    private class Actor(val cityId: UUID, val workerId: UUID, val worldId: UUID, val npc: NPC, var plan: CitizenPlan) {
        val instanceId: UUID = UUID.randomUUID()
        var cycle = plan.cycle()
        val targets = mutableMapOf<CivPosition, CivPosition>()
        var wander = CitizenWander()
        val animation = CitizenWorkAnimation()
        val footsteps = CitizenFootsteps()
        var route: Route? = null
        var reportedFailure = false
        var damageAfter = 0L
        var equipmentItems: List<CivResidentItem?>? = null
        var equipmentType: BuildingType? = null
        var sheltering = false
        var shelter: CivPosition? = null
    }
    private class Route(val from: CitizenPoint, val target: Location, val chunks: CitizenChunks, val startedAt: Long,
        val timeoutMillis: Long, val allowedBuildings: Set<UUID>) {
        var completion: CitizenNavigationCompletion? = null
        var callbackPosition: CitizenPoint? = null
        var callbackNavigating: Boolean? = null
        var path: CitizenPathSnapshot? = null
        var strategy: String? = null
    }
    private data class Completion(val workerId: UUID, val actorId: UUID, val ticket: UUID, val token: CitizenToken,
        val city: Settlement?, val error: Throwable?, val pickup: Boolean = false)
    private data class HarmCompletion(val workerId: UUID, val city: Settlement?, val error: Throwable?)

    private companion object {
        val NAVIGATION_DIAGNOSTIC = StructuredDebugLine("ARCCIVS_NAVIGATION_FAILED")
        val NEUTRAL_MONSTERS = setOf(EntityType.ENDERMAN, EntityType.PIGLIN, EntityType.ZOMBIFIED_PIGLIN, EntityType.SPIDER)
    }
}

/** Consecutive native positions only; a route, spawn or teleport never fabricates footsteps. */
internal class CitizenFootsteps {
    private var previous: Location? = null

    fun reset() { previous = null }

    fun observe(position: Location, onGround: Boolean): Pair<Location, Location>? {
        if (!onGround || position.world == null || !position.x.isFinite() || !position.y.isFinite() || !position.z.isFinite()) {
            reset()
            return null
        }
        val from = previous
        previous = position.clone()
        if (from == null || from.world?.uid != position.world?.uid) return null
        val dx = position.x - from.x
        val dy = position.y - from.y
        val dz = position.z - from.z
        val horizontal = dx * dx + dz * dz
        if (horizontal <= 0.000001 || horizontal + dy * dy > 4.0 || abs(dy) > 1.25) return null
        return from to position.clone()
    }
}

internal data class CitizenPoint(val x: Double, val y: Double, val z: Double) {
    fun distanceSquared(other: CitizenPoint): Double = (x - other.x) * (x - other.x) +
        (y - other.y) * (y - other.y) + (z - other.z) * (z - other.z)
}
internal data class CitizenViewer(val worldId: UUID, val point: CitizenPoint)

/** Null completion means no callback; a received null reason means native completion, not verified arrival. */
internal data class CitizenNavigationCompletion(val cancelReason: String?)
internal enum class CitizenNavigationOutcome { PENDING, ARRIVED, CANCELLED, COMPLETED_OUTSIDE_ARRIVAL, TIMED_OUT, STOPPED }
internal fun citizenNavigationOutcome(completion: CitizenNavigationCompletion?, arrived: Boolean,
    elapsedMillis: Long, timeoutMillis: Long, navigating: Boolean): CitizenNavigationOutcome = when {
    completion?.cancelReason != null -> CitizenNavigationOutcome.CANCELLED
    arrived -> CitizenNavigationOutcome.ARRIVED
    completion != null -> CitizenNavigationOutcome.COMPLETED_OUTSIDE_ARRIVAL
    elapsedMillis > timeoutMillis -> CitizenNavigationOutcome.TIMED_OUT
    !navigating -> CitizenNavigationOutcome.STOPPED
    else -> CitizenNavigationOutcome.PENDING
}

internal data class CitizenPathSnapshot(val nodes: Int, val end: CitizenPoint, val truncated: Boolean)
internal fun citizenPathSnapshot(path: Iterable<CitizenPoint>): CitizenPathSnapshot? {
    val iterator = path.iterator()
    var nodes = 0
    var end: CitizenPoint? = null
    while (nodes < 256 && iterator.hasNext()) {
        end = iterator.next()
        nodes++
    }
    return end?.let { CitizenPathSnapshot(nodes, it, iterator.hasNext()) }
}

internal fun citizenHasViewer(worldId: UUID, point: CitizenPoint, viewers: List<CitizenViewer>, range: Int): Boolean =
    viewers.any { it.worldId == worldId && it.point.distanceSquared(point) <= range.toDouble() * range }
internal fun citizenSpawnBudget(active: Int, maximum: Int): Int = (maximum.coerceIn(0, 48) - active).coerceIn(0, 2)

/** Failed native target checks consume an admission too, so they must not monopolize the next pulse. */
internal fun <T> citizenRotateAfterAdmission(actors: MutableMap<UUID, T>, workerId: UUID) {
    val actor = actors.remove(workerId) ?: return
    actors[workerId] = actor
}

/** Keep native route movement active before Paper chooses this tick's entity activation. */
internal fun activateCitizenRoute(npc: NPC, registry: NPCRegistry, worldId: UUID, viewers: List<CitizenViewer>,
    viewerRange: Int, chunksReady: (World) -> Boolean, activate: (Entity) -> Unit = NMS::activate): Boolean {
    if (npc.owningRegistry !== registry || !npc.isSpawned || !npc.navigator.isNavigating) return false
    val entity = npc.entity
    if (!entity.isValid || entity.world.uid != worldId ||
        !citizenHasViewer(worldId, entity.location.point(), viewers, viewerRange) ||
        !citizenChunkReady(entity.world, entity.location.blockX shr 4, entity.location.blockZ shr 4) ||
        !chunksReady(entity.world)) return false
    activate(entity)
    return true
}

// Entrances retain the domain bound; a real actor can stop up to 0.65 blocks from an entrance.
internal const val CITIZEN_ROUTE_ARRIVAL_TOLERANCE = 1.0
/** Citizens 2.0.42 ends an A* path at the integer goal; its preceding nodes use block centres. */
internal fun citizenNearEndpoint(from: CitizenPoint, target: CivPosition): Boolean =
    abs(from.y - target.y) <= 0.5 && (from.distanceSquared(target.point()) <= 0.65 * 0.65 ||
        from.distanceSquared(CitizenPoint(target.x.toDouble(), target.y.toDouble(), target.z.toDouble())) <= 0.4 * 0.4)

internal fun citizenRouteWithinReach(from: CitizenPoint, to: CitizenPoint): Boolean {
    val maximum = CivsRules.MAX_WORK_ROUTE_DISTANCE + CITIZEN_ROUTE_ARRIVAL_TOLERANCE
    return from.distanceSquared(to) <= maximum * maximum
}
internal fun citizenStorageNeedsReset(previous: CivPosition, next: CivPosition, actualPosition: CitizenPoint): Boolean =
    previous != next && !citizenRouteWithinReach(actualPosition, next.point())

internal data class CitizenToken(val assignmentId: UUID?, val completedCycles: Long, val nextDeliveryAt: Long, val constructionId: UUID?,
    val shipmentId: UUID? = null)
internal data class CitizenPlan(val token: CitizenToken, val storage: CivPosition, val workplace: CivPosition?,
    val type: BuildingType?, val workMillis: Long, val idleReason: String?, val completeAtWorkplace: Boolean = false,
    val courier: CivCourierPlan? = null, val worldWork: CivWorldWork? = null) {
    val spawnOrigin: CivPosition get() = courier?.cargo?.sourceEntrance ?: storage
    fun cycle() = CitizenCycle(token, workMillis, completeAtWorkplace, courier != null, courier?.carrying == true, worldWork != null)
}

/** Pickup publication changes carried quantity, never the identity of the journey already observed. */
internal fun citizenPlansContinue(previous: CitizenPlan, next: CitizenPlan): Boolean {
    val first = previous.courier ?: return false
    val second = next.courier ?: return false
    return previous.token == next.token && previous.storage == next.storage && previous.workplace == next.workplace &&
        first.destinationBuildingId == second.destinationBuildingId && first.cargo.id == second.cargo.id &&
        first.cargo.warehouseId == second.cargo.warehouseId && first.cargo.sourceBuildingId == second.cargo.sourceBuildingId &&
        first.cargo.targetBuildingId == second.cargo.targetBuildingId && first.cargo.material == second.cargo.material
}

internal fun citizenCurrentPlan(previous: CitizenPlan, phase: CitizenPhase, current: () -> CitizenPlan?): CitizenPlan? =
    if (previous.courier != null && phase in setOf(CitizenPhase.PICKING_UP, CitizenPhase.DELIVERING)) previous else current()

internal fun citizenPlan(city: Settlement, worker: CivWorker, courier: CivCourierPlan? = null): CitizenPlan? {
    if (courier != null) {
        val destination = city.building(courier.destinationBuildingId)?.control ?: return null
        val source = city.building(courier.cargo.sourceBuildingId)?.control
            ?: courier.cargo.sourceEntrance.takeIf { courier.carrying } ?: return null
        return CitizenPlan(CitizenToken(courier.cargo.warehouseId, worker.completedCycles, 0, null, courier.cargo.id),
            destination, source, BuildingType.WAREHOUSE, 1_000, null, courier = courier)
    }
    if (worker.cargo != null) return null
    val assigned = worker.assignment?.let(city::building)
    val workType = assigned?.let { CivsRules.effectiveWorkType(city, it) }
    val builder = workType == BuildingType.BUILDER_HUT
    val order = city.construction.takeIf { builder }
    val workBuilding = order?.buildingId?.let(city::building) ?: order?.plannedBuilding
    val workplace = if (builder) workBuilding?.control ?: order?.plannedBuilding?.entrance ?: workBuilding?.entrance
        else assigned?.control
    val depot = city.buildings.filter(CivsRules::isDepot)
        .minByOrNull { (it.control ?: it.entrance).point().distanceSquared((workplace ?: city.center).point()) } ?: return null
    val storage = if ((city.inventory != null || workType == BuildingType.TOWN_HALL) && !builder && assigned != null)
        assigned.control ?: assigned.entrance else depot.control ?: depot.entrance
    val reason = when {
        worker.assignment == null -> "unassigned"
        assigned == null -> "building-unavailable"
        workType == BuildingType.WAREHOUSE && assigned.phase == BuildingPhase.ACTIVE -> "waiting-orders"
        builder && order == null -> "no-construction"
        builder && !CivsRules.builderCanWork(city, assigned) -> "building-unavailable"
        builder && order?.phase == ConstructionPhase.WAITING_MATERIALS -> "waiting-materials"
        builder && order?.phase == ConstructionPhase.PAUSED -> "construction-paused"
        builder && order?.payment == null && order?.phase in setOf(ConstructionPhase.READY_TO_PLACE, ConstructionPhase.PLACING) -> "placing"
        builder && depot.control == null && depot.phase == BuildingPhase.ACTIVE -> "storage-unavailable"
        !builder && !CivsRules.productionAvailable(city, assigned) -> "building-unavailable"
        workplace == null -> "building-unavailable"
        else -> null
    }
    return CitizenPlan(CitizenToken(worker.assignment, worker.completedCycles, worker.nextDeliveryAt, order?.id),
        storage, workplace, workType,
        if (builder && order?.payment != null) CivsRules.BUILDER_SESSION_INTERVAL else workType?.let(CivsRules::deliveryInterval) ?: 0,
        reason, builder && order?.payment != null || city.inventory != null && !builder)
}

/** One real barrel, twelve supported side cells; never its top or another building's interior. */
internal fun citizenStorageTarget(city: Settlement, own: CivBuilding, world: World,
    safe: (CivPosition) -> Boolean = { citizenStandable(world, it) }): CivPosition? {
    val control = own.control ?: return null
    if (world.uid != city.worldId || !citizenStorageReady(world, control)) return null
    for (dy in listOf(0, 1, -1)) for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
        val coordinates = listOf(control.x.toLong() + dx, control.y.toLong() + dy, control.z.toLong() + dz)
        if (coordinates.any { it !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }) continue
        val point = CivPosition(coordinates[0].toInt(), coordinates[1].toInt(), coordinates[2].toInt())
        if (city.contains(point.x, point.z) &&
            city.buildings.none { it.id != own.id && it.bounds.contains(point.x, point.y, point.z) } &&
            city.construction?.plannedBuilding?.takeIf { it.id != own.id }?.bounds?.contains(point.x, point.y, point.z) != true &&
            safe(point)) return point
    }
    return null
}

internal fun citizenAtStorage(world: World, from: CitizenPoint, control: CivPosition): Boolean {
    if (!citizenStorageReady(world, control)) return false
    val dx = from.x - (control.x + 0.5)
    val dz = from.z - (control.z + 0.5)
    return abs(from.y - control.y) <= 1.5 && dx * dx + dz * dz <= 1.75 * 1.75 &&
        (kotlin.math.floor(from.x).toInt() != control.x || kotlin.math.floor(from.z).toInt() != control.z)
}

private fun citizenStorageReady(world: World, control: CivPosition): Boolean =
    world.isChunkLoaded(control.x shr 4, control.z shr 4) &&
        world.getBlockAt(control.x, control.y, control.z).type == org.bukkit.Material.BARREL

internal fun citizenLookDirection(eye: Location, focus: Location): Pair<Float, Float> = eye.clone().let {
    it.direction = focus.toVector().subtract(eye.toVector())
    it.yaw to it.pitch
}

internal fun citizenWorkingTravel(plan: CitizenPlan, phase: CitizenPhase, sheltering: Boolean): Boolean =
    plan.idleReason == null && !sheltering && phase in setOf(CitizenPhase.TO_STORAGE, CitizenPhase.TO_WORK, CitizenPhase.RETURNING)

internal fun citizenDoorPair(world: World, point: CivPosition): Pair<org.bukkit.block.Block, org.bukkit.block.Block>? {
    if (point.y !in world.minHeight until world.maxHeight || !world.isChunkLoaded(point.x shr 4, point.z shr 4)) return null
    val data = world.getBlockAt(point.x, point.y, point.z).blockData as? Door ?: return null
    val y = point.y - if (data.half == Bisected.Half.TOP) 1 else 0
    if (y < world.minHeight || y + 1 >= world.maxHeight) return null
    val lower = world.getBlockAt(point.x, y, point.z)
    val upper = world.getBlockAt(point.x, y + 1, point.z)
    val bottom = lower.blockData as? Door ?: return null
    val top = upper.blockData as? Door ?: return null
    if (lower.type != upper.type || bottom.half != Bisected.Half.BOTTOM || top.half != Bisected.Half.TOP ||
        bottom.facing != top.facing || bottom.hinge != top.hinge) return null
    return lower to upper
}

internal fun citizenDoorAllowed(city: Settlement, allowed: Set<UUID>, point: CivPosition): Boolean =
    city.pendingDeletion == null && citizenRouteCellAllowed(city, allowed, point.x, point.z)

/** Open only a nearby path door; normal blocks, foreign footprints and rejected native events stay intact. */
internal fun citizenOpenRouteDoor(npc: NPC, city: Settlement, allowed: Set<UUID>, at: Location, point: CivPosition,
    blocked: (UUID, CivPosition) -> Boolean): Boolean {
    val world = at.world ?: return false
    if (world.uid != city.worldId || point.y !in world.minHeight until world.maxHeight ||
        !world.isChunkLoaded(point.x shr 4, point.z shr 4)) return false
    if (world.getBlockAt(point.x, point.y, point.z).blockData !is Door) return true
    val pair = citizenDoorPair(world, point) ?: return false
    val lower = pair.first
    if (at.distanceSquared(Location(world, lower.x + 0.5, lower.y.toDouble(), lower.z + 0.5)) > 2.5 * 2.5 ||
        listOf(pair.first, pair.second).any { block ->
            val p = CivPosition(block.x, block.y, block.z)
            !citizenDoorAllowed(city, allowed, p) || blocked(world.uid, p)
        }) return false
    if (listOf(pair.first, pair.second).all { (it.blockData as Door).isOpen }) return true
    val event = NPCOpenDoorEvent(npc, lower)
    Bukkit.getPluginManager().callEvent(event)
    if (event.isCancelled) return false
    // A native event listener may replace a block; never put captured door data over its replacement.
    val current = citizenDoorPair(world, point) ?: return false
    if (listOf(current.first, current.second).any { blocked(world.uid, CivPosition(it.x, it.y, it.z)) }) return false
    for (block in listOf(current.first, current.second)) {
        val state = block.blockData as Door
        if (!state.isOpen) block.setBlockData(state.also { it.isOpen = true }, false)
    }
    return true
}

/** Exact entrance first, then at most 26 neighboring loaded, supported feet positions; never inside a building. */
internal fun citizenRecoveryTarget(city: Settlement, target: CivPosition, safe: (CivPosition) -> Boolean): CivPosition? {
    val offsets = listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, -1 to 1, 1 to -1, -1 to -1)
    for (dy in listOf(0, 1, -1)) for ((dx, dz) in offsets) {
        val x = target.x.toLong() + dx
        val y = target.y.toLong() + dy
        val z = target.z.toLong() + dz
        if (x !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || y !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
            z !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) continue
        val point = CivPosition(x.toInt(), y.toInt(), z.toInt())
        if (city.contains(point.x, point.z) && city.buildings.none { it.bounds.contains(point.x, point.y, point.z) } &&
            city.construction?.plannedBuilding?.bounds?.contains(point.x, point.y, point.z) != true && safe(point)) return point
    }
    return null
}

private fun hasCapacity(city: Settlement, plan: CitizenPlan): Boolean {
    // Item storage checks actual container space at its durable delivery boundary.
    if (city.inventory != null) return true
    val building = plan.token.assignmentId?.let(city::building) ?: return true
    val resource = CivsRules.resource(building.type) ?: return true
    return CivsRules.capacity(city) - city.stock[resource] >= CivsRules.deliveryAmount(building)
}

/** Transient visual movement has no production token, arrival grant or offline catch-up. */
internal class CitizenWander {
    var step: Int = 0
        private set
    private var nextAt = 0L
    fun ready(now: Long): Boolean = now >= nextAt
    fun reject(now: Long) {
        step = (step + 1) % (CITIZEN_WANDER_OFFSETS.size * CITIZEN_WANDER_HEIGHTS.size)
        schedule(now, 1_000L)
    }
    fun finish(now: Long, arrived: Boolean) {
        step = ((step / CITIZEN_WANDER_HEIGHTS.size + 1) % CITIZEN_WANDER_OFFSETS.size) * CITIZEN_WANDER_HEIGHTS.size
        schedule(now, if (arrived) 2_000L else 8_000L)
    }
    private fun schedule(now: Long, delay: Long) {
        nextAt = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
    }
}

private val CITIZEN_WANDER_OFFSETS = listOf(6 to 0, 8 to 8, 0 to 14, -12 to 8, -16 to 0, -8 to -12, 0 to -10, 10 to -6)
private val CITIZEN_WANDER_HEIGHTS = listOf(0, 1, -1, 2, -2)

/** One candidate per actor admission. Native support/headroom checks belong to the bounded pulse. */
internal fun citizenWanderTarget(city: Settlement, storage: CivPosition, workerId: UUID, step: Int): CivPosition? {
    val seed = workerId.mostSignificantBits xor workerId.leastSignificantBits
    val offset = Math.floorDiv(step, CITIZEN_WANDER_HEIGHTS.size)
    val (dx, dz) = CITIZEN_WANDER_OFFSETS[Math.floorMod(Math.floorMod(seed, 8L).toInt() + offset, CITIZEN_WANDER_OFFSETS.size)]
    val dy = CITIZEN_WANDER_HEIGHTS[Math.floorMod(step, CITIZEN_WANDER_HEIGHTS.size)]
    val x = storage.x.toLong() + dx
    val y = storage.y.toLong() + dy
    val z = storage.z.toLong() + dz
    if (x !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || y !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
        z !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
    return CivPosition(x.toInt(), y.toInt(), z.toInt()).takeIf { citizenRouteCellAllowed(city, emptySet(), it.x, it.z) }
}

internal fun citizenStandable(world: World, point: CivPosition): Boolean {
    if (!citizenChunkReady(world, point.x shr 4, point.z shr 4) ||
        point.y <= world.minHeight || point.y + 1 >= world.maxHeight) return false
    val feet = world.getBlockAt(point.x, point.y, point.z)
    val head = world.getBlockAt(point.x, point.y + 1, point.z)
    val floor = world.getBlockAt(point.x, point.y - 1, point.z)
    val support = floor.boundingBox
    if (feet.type == org.bukkit.Material.LADDER && head.isPassable && !head.isLiquid) return true
    val clearFeet = feet.isPassable || feet.type.name.endsWith("_CARPET") && feet.boundingBox.maxY <= point.y + 0.125
    return clearFeet && !feet.isLiquid && head.isPassable && !head.isLiquid &&
        !floor.isLiquid && support.maxY >= point.y - 0.125 &&
        support.minX <= point.x + 0.2 && support.maxX >= point.x + 0.8 &&
        support.minZ <= point.z + 0.2 && support.maxZ >= point.z + 0.8
}

internal enum class CitizenPhase { IDLE, TO_STORAGE, TO_WORK, WORKING, PICKUP_READY, PICKING_UP, RETURNING, READY, DELIVERING, PAUSED, CLOSED }

private const val CITIZEN_DELIVERY_RETRY_DELAY_MILLIS = 5_000L

/** Only observed work counts. Producers return cargo to storage; builders finish the session at their worksite. */
internal class CitizenCycle(val token: CitizenToken, private val workMillis: Long, private val completeAtWorkplace: Boolean = false,
    private val pickupAtWorkplace: Boolean = false, carrying: Boolean = false, private val directToWork: Boolean = false) {
    var phase = CitizenPhase.IDLE
        private set
    var reason: String? = null
        private set
    private var workedMillis = 0L
    private var observedAt = 0L
    private var ticket: UUID? = null
    private var resumePhase = CitizenPhase.TO_STORAGE
    private var deliveryRetryAt: Long? = null
    private val retries = CitizenRetry()
    private var hasCargo = carrying
    val failedAttempts: Int get() = retries.attempts

    private fun travelStart(): CitizenPhase = when {
        pickupAtWorkplace && hasCargo -> CitizenPhase.RETURNING
        pickupAtWorkplace || directToWork -> CitizenPhase.TO_WORK
        else -> CitizenPhase.TO_STORAGE
    }
    fun start() { if (phase == CitizenPhase.IDLE) phase = travelStart() }
    fun arrive(now: Long) {
        phase = when (phase) {
            CitizenPhase.TO_STORAGE -> CitizenPhase.TO_WORK
            CitizenPhase.TO_WORK -> CitizenPhase.WORKING.also { workedMillis = 0; observedAt = now }
            CitizenPhase.WORKING -> CitizenPhase.WORKING.also { observedAt = now } // Recovery cannot count the known interval away from work.
            CitizenPhase.RETURNING -> CitizenPhase.READY
            else -> phase
        }
    }
    fun work(now: Long) {
        if (phase != CitizenPhase.WORKING) return
        val delta = now - observedAt
        observedAt = now
        if (delta in 0..2_500) workedMillis += delta
        if (workedMillis >= workMillis) phase = when {
            pickupAtWorkplace -> CitizenPhase.PICKUP_READY
            completeAtWorkplace -> CitizenPhase.READY
            else -> CitizenPhase.RETURNING
        }
    }
    fun beginPickup(): UUID? {
        if (phase != CitizenPhase.PICKUP_READY) return null
        phase = CitizenPhase.PICKING_UP
        return UUID.randomUUID().also { ticket = it }
    }
    fun acceptsPickup(candidate: UUID): Boolean = phase == CitizenPhase.PICKING_UP && ticket == candidate
    fun pickedUp() {
        if (phase != CitizenPhase.PICKING_UP) return
        hasCargo = true
        ticket = null
        phase = CitizenPhase.RETURNING
    }
    fun failPickup(reason: String?, now: Long) {
        if (phase != CitizenPhase.PICKING_UP) return
        retryTransfer(reason, now, CitizenPhase.PICKUP_READY)
    }
    fun beginDelivery(now: Long): UUID? {
        if (phase != CitizenPhase.READY || !pickupAtWorkplace && now < token.nextDeliveryAt) return null
        phase = CitizenPhase.DELIVERING
        return UUID.randomUUID().also { ticket = it }
    }
    fun acceptsCompletion(candidate: UUID): Boolean = phase == CitizenPhase.DELIVERING && ticket == candidate
    fun failDelivery(reason: String?, now: Long) {
        if (phase != CitizenPhase.DELIVERING) return
        retryTransfer(reason, now, CitizenPhase.READY)
    }
    private fun retryTransfer(reason: String?, now: Long, readyPhase: CitizenPhase) {
        val retryStatus = when (reason) {
            "city-busy", "construction-credit-full" -> "waiting"
            "storage-busy" -> "storage-busy"
            "materials-missing" -> "waiting-inputs"
            "storage-full" -> if (pickupAtWorkplace) "storage-full" else null
            "control-missing", "storage-unavailable", "building-not-found" -> if (pickupAtWorkplace) "storage-unavailable" else null
            else -> null
        }
        if (retryStatus == null) {
            pause(if (reason == "storage-full") "storage-full" else "delivery-failed", now, true)
            resumePhase = readyPhase
            return
        }
        // A completed observed trip remains valid while supplies or another durable commit are pending.
        this.reason = retryStatus
        resumePhase = readyPhase
        ticket = null
        deliveryRetryAt = now + CITIZEN_DELIVERY_RETRY_DELAY_MILLIS
        phase = CitizenPhase.PAUSED
    }
    fun pause(reason: String, now: Long, retainWork: Boolean) {
        if (phase == CitizenPhase.CLOSED) return
        this.reason = reason
        resumePhase = if (retainWork) CitizenPhase.READY else travelStart()
        ticket = null
        deliveryRetryAt = null
        retries.fail(now)
        if (reason == "route-unreachable" && retries.exhausted) deliveryRetryAt = now + 15_000L
        phase = CitizenPhase.PAUSED
    }
    fun retry(now: Long) {
        if (phase == CitizenPhase.PAUSED && (deliveryRetryAt?.let { now >= it } ?: retries.ready(now))) resume()
    }
    fun resume() {
        if (phase != CitizenPhase.PAUSED) return
        phase = resumePhase
        reason = null
        deliveryRetryAt = null
    }
    fun close() { phase = CitizenPhase.CLOSED; ticket = null; deliveryRetryAt = null }
    fun status(): String = when (phase) {
        CitizenPhase.RETURNING -> if (pickupAtWorkplace) "carrying-cargo" else "moving-to-storage"
        CitizenPhase.TO_STORAGE -> "moving-to-storage"
        CitizenPhase.TO_WORK -> if (pickupAtWorkplace) "moving-to-pickup" else "moving-to-work"
        CitizenPhase.WORKING -> "working"
        CitizenPhase.PICKUP_READY -> "waiting"
        CitizenPhase.PICKING_UP -> "loading-cargo"
        CitizenPhase.READY -> "waiting"
        CitizenPhase.DELIVERING -> "delivering"
        CitizenPhase.PAUSED -> reason ?: "unavailable"
        CitizenPhase.CLOSED -> "waiting"
        CitizenPhase.IDLE -> "waiting"
    }
}

internal class CitizenRetry {
    var attempts = 0
        private set
    private var retryAt = 0L
    val exhausted: Boolean get() = attempts >= 3
    fun ready(now: Long): Boolean = !exhausted && now >= retryAt
    fun fail(now: Long) {
        attempts++
        retryAt = now + if (attempts == 1) 5_000 else 15_000
    }
}

/** Server-thread only. The false overload and load-level getter never load or retain a chunk on Paper. */
internal fun citizenChunkReady(world: World, x: Int, z: Int): Boolean =
    world.isChunkLoaded(x, z) && world.getChunkAt(x, z, false).loadLevel == Chunk.LoadLevel.ENTITY_TICKING

private fun CitizenPlan.chunksReady(world: World): Boolean = workplace?.let {
    CitizenChunks.between(storage.x, storage.z, it.x, it.z).ready(world)
} ?: citizenChunkReady(world, storage.x shr 4, storage.z shr 4)

internal data class CitizenChunks(val minX: Int, val maxX: Int, val minZ: Int, val maxZ: Int) {
    fun contains(x: Int, z: Int) = x in minX..maxX && z in minZ..maxZ
    // The one-chunk search margin only needs loaded terrain; movement needs entity ticking.
    fun ready(world: World): Boolean = (minX..maxX).all { x -> (minZ..maxZ).all { z ->
        if (x == minX || x == maxX || z == minZ || z == maxZ) world.isChunkLoaded(x, z)
        else citizenChunkReady(world, x, z)
    } }
    companion object {
        fun between(x1: Int, z1: Int, x2: Int, z2: Int) = CitizenChunks(
            (minOf(x1, x2) shr 4) - 1, (maxOf(x1, x2) shr 4) + 1,
            (minOf(z1, z2) shr 4) - 1, (maxOf(z1, z2) shr 4) + 1)
    }
}

private fun CivPosition.location(world: World) = Location(world, x + 0.5, y.toDouble(), z + 0.5)
private fun CivPosition.point() = CitizenPoint(x + 0.5, y.toDouble(), z + 0.5)
private fun Location.point() = CitizenPoint(x, y, z)
private fun CitizenPoint.coordinates() = "$x,$y,$z"
private fun CivPosition.inChunk(x: Int, z: Int) = this.x shr 4 == x && this.z shr 4 == z
private fun citizenName(name: String, id: UUID): String = name.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
    .trim().take(24).ifEmpty { id.toString().take(8) }

/** Horizontal footprints stay off transit routes, except the departure and actual destination buildings. */
internal fun citizenRouteCellAllowed(city: Settlement, allowed: Set<UUID>, x: Int, z: Int): Boolean =
    city.contains(x, z) && civsProtectedBuildings(city).none { it.id !in allowed &&
        (x in it.bounds.min.x..it.bounds.max.x && z in it.bounds.min.z..it.bounds.max.z || CivsWorksiteLayouts.isField(it, x, z)) }

internal fun citizenRouteBuildings(city: Settlement, plan: CitizenPlan, target: CivPosition, from: CivPosition): Set<UUID> =
    city.buildings.filter { building ->
        building.bounds.contains(from.x, from.y, from.z) || building.bounds.contains(target.x, target.y, target.z) ||
            building.control == target ||
            (target == plan.workplace || target == plan.storage) && building.id == plan.token.assignmentId
    }.mapTo(hashSetOf()) { it.id }

internal fun citizenShelterTarget(city: Settlement, worker: CivWorker, safe: (CivPosition) -> Boolean): CivPosition? {
    val homes = city.buildings.filter { it.type == BuildingType.HOUSE && it.phase == BuildingPhase.ACTIVE }
        .flatMap { home -> List(home.level * 2) { home } }
    val resident = city.workers.indexOfFirst { it.id == worker.id }
    val home = homes.getOrNull(resident)
        ?: city.buildings.firstOrNull { it.type == BuildingType.TOWN_HALL } ?: return null
    val place = if (resident < homes.size) resident - homes.indexOfFirst { it.id == home.id } else resident - homes.size
    val origin = home.control ?: home.entrance
    val candidates = sequence {
        for (dy in listOf(0, 1, -1, 2, -2)) for (r in 1..4) for ((dx, dz) in listOf(r to 0, 0 to r, -r to 0, 0 to -r))
            yield(CivPosition(origin.x + dx, origin.y + dy, origin.z + dz))
    }
    val safePlaces = candidates.filter { home.bounds.contains(it.x, it.y, it.z) && safe(it) }.take(maxOf(place + 1, 1)).toList()
    return safePlaces.getOrNull(place) ?: safePlaces.lastOrNull()
}
