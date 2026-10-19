package ru.arc.civs

import com.destroystokyo.paper.entity.Pathfinder
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.ai.AbstractPathStrategy
import net.citizensnpcs.api.ai.NavigatorParameters
import net.citizensnpcs.api.ai.PathfinderType
import net.citizensnpcs.api.ai.TargetType
import net.citizensnpcs.api.ai.event.CancelReason
import net.citizensnpcs.api.event.NPCRightClickEvent
import net.citizensnpcs.api.npc.MemoryNPCDataStore
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.npc.NPCRegistry
import net.citizensnpcs.trait.VillagerProfession
import net.citizensnpcs.util.NMS
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.Plugin
import org.bukkit.util.Vector
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
) : Listener, AutoCloseable {
    private val registry: NPCRegistry
    private val citizensProvider: Plugin
    private val nativeCleanup: ArcNativeCleanupService
    private var actors = linkedMapOf<UUID, Actor>()
    private var statuses = mutableMapOf<UUID, String>()
    private var spawnRetries = mutableMapOf<UUID, Pair<CitizenPlan, CitizenRetry>>()
    @Volatile private var completions = ConcurrentLinkedQueue<Completion>()
    private val closed = AtomicBoolean()
    private var retirement: CompletableFuture<Void>? = null
    private var wanderBudget = 0
    private var recoveryBudget = 0

    init {
        require(maxActive in 1..48 && viewerRange in 1..96)
        checkMainThread()
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
            val world = plugin.server.getWorld(city.worldId)
            val plan = citizenPlan(city, worker)
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
            if (actor.plan != plan) {
                val nextPlan = requireNotNull(plan)
                if (citizenStorageNeedsReset(actor.plan.storage, nextPlan.storage, actor.npc.entity.location.point())) {
                    remove(actor, "waiting")
                    continue
                }
                cancelRoute(actor)
                actor.cycle.close()
                if (actor.plan.storage != nextPlan.storage || actor.plan.workplace != nextPlan.workplace) actor.targets.clear()
                actor.plan = nextPlan
                actor.cycle = CitizenCycle(nextPlan.token, nextPlan.workMillis, nextPlan.completeAtWorkplace)
                actor.wander = CitizenWander()
                setProfession(actor.npc, nextPlan.type)
            }
            try {
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
            val plan = citizenPlan(city, worker)
            val world = plugin.server.getWorld(city.worldId)
            val reason = when {
                plan == null -> "storage-unavailable"
                world == null -> "world-unloaded"
                !citizenHasViewer(city.worldId, plan.storage.point(), viewers, viewerRange) -> "sleeping"
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
                val point = spawnPoint(requireNotNull(world), requireNotNull(plan).storage, city.workers.indexOf(worker), viewers)
                if (point == null) {
                    retry.fail(nowMillis)
                    spawnRetries[workerId] = plan to retry
                    statuses[workerId] = "route-unreachable"
                    continue
                }
                npc = registry.createNPC(EntityType.VILLAGER, citizenName(worker.name, workerId))
                configure(npc, plan.type)
                check(npc.spawn(point) { entity ->
                    val villager = entity as Villager
                    villager.setAdult()
                    villager.ageLock = true
                    villager.setBreed(false)
                    villager.recipes = emptyList()
                    villager.canPickupItems = false
                    villager.isPersistent = false
                    villager.isInvulnerable = true
                    villager.isCollidable = false
                    villager.isSilent = true
                }) { "Citizens rejected spawn" }
                val actor = Actor(city.id, workerId, city.worldId, npc, plan)
                actors[workerId] = actor
                spawnRetries.remove(workerId)
                update(actor, city, world, nowMillis)
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
    }

    /** Stable locale keys; no Bukkit objects or user-controlled text enter the key. */
    fun status(workerId: UUID): String {
        checkMainThread()
        if (!closed.get()) {
            val city = cityProvider().firstOrNull { it.workers.any { worker -> worker.id == workerId } }
            val worker = city?.workers?.firstOrNull { it.id == workerId }
            if (city != null && worker != null) {
                val plan = citizenPlan(city, worker)
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

    /** Called by one Core timer before Paper chooses active/inactive entity ticks. */
    fun activateNavigating() {
        checkMainThread()
        if (closed.get() || actors.isEmpty()) return
        val viewers = viewers()
        for (actor in actors.values) {
            val route = actor.route ?: continue
            if (route.completion != null) continue
            activateCitizenRoute(actor.npc, registry, actor.worldId, viewers, viewerRange, route.chunks::ready)
        }
    }

    private fun update(actor: Actor, city: Settlement, world: World, now: Long) {
        val cycle = actor.cycle
        actor.plan.idleReason?.let {
            if (it !in setOf("placing", "waiting-materials", "construction-paused")) wander(actor, city, world, now)
            statuses[actor.workerId] = it
            return
        }
        if (cycle.reason == "storage-full") {
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
                            route.completion?.cancelReason == null && route.completion != null &&
                                arrived(actor.npc.entity.location, route.target),
                            now - route.startedAt, route.timeoutMillis, actor.npc.navigator.isNavigating)
                        when (outcome) {
                            CitizenNavigationOutcome.PENDING -> Unit
                            CitizenNavigationOutcome.ARRIVED -> {
                                actor.route = null
                                cycle.arrive(now)
                            }
                            else -> pauseNavigation(actor, "route-unreachable", outcome.name.lowercase(), route.target, now)
                        }
                    }
                }
            }
            CitizenPhase.WORKING -> {
                val target = resolvedTarget(actor, requireNotNull(actor.plan.workplace)).location(world)
                if (!arrived(actor.npc.entity.location, target))
                    pauseNavigation(actor, "route-unreachable", "left-workplace", target, now)
                else cycle.work(now)
            }
            CitizenPhase.READY -> {
                val target = resolvedTarget(actor, if (actor.plan.completeAtWorkplace) requireNotNull(actor.plan.workplace) else actor.plan.storage).location(world)
                if (!arrived(actor.npc.entity.location, target)) {
                    pauseNavigation(actor, "route-unreachable", "left-delivery-point", target, now)
                } else if (!hasCapacity(city, actor.plan)) {
                    cycle.pause("storage-full", now, true)
                } else cycle.beginDelivery(now)?.let { ticket -> submitDelivery(actor, ticket, now) }
            }
            else -> Unit
        }
        if (actors[actor.workerId] === actor) statuses[actor.workerId] = cycle.status()
    }

    private fun wander(actor: Actor, city: Settlement, world: World, now: Long) {
        val route = actor.route
        if (route != null) {
            val outcome = if (!route.chunks.ready(world)) CitizenNavigationOutcome.STOPPED
                else citizenNavigationOutcome(route.completion,
                    route.completion?.cancelReason == null && route.completion != null && arrived(actor.npc.entity.location, route.target),
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
        val target = citizenWanderTarget(city, actor.plan.storage, actor.workerId, actor.wander.step)
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

    private fun startRoute(actor: Actor, world: World, target: CivPosition, now: Long): Boolean {
        val from = actor.npc.entity.location
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
        if (actor.plan.idleReason == null && arrived(from, to)) {
            actor.cycle.arrive(now)
            return true
        }
        // Citizens itself retains the already-loaded destination while navigating. ArcCivs never loads a chunk.
        val navigator = actor.npc.navigator
        navigator.defaultParameters.range((distance + 8).coerceAtLeast(16.0).toFloat())
        val route = Route(from.point(), to, chunks, now, (10_000 + distance * 1_500).toLong().coerceAtMost(90_000))
        actor.route = route
        navigator.setTarget { parameters ->
            CitizenExactNavigation((actor.npc.entity as Villager).pathfinder, to, parameters)
        }
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
        val entity = actor.npc.entity as Villager
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

    /** A failed path may relocate to a nearby safe endpoint; it never grants work or delivery itself. */
    private fun recoverNavigation(actor: Actor, target: Location, now: Long): Boolean {
        if (recoveryBudget == 0 || actor.plan.idleReason != null) return false
        val city = cityProvider().firstOrNull { it.id == actor.cityId && it.pendingDeletion == null } ?: return false
        val entity = actor.npc.entity
        if (!entity.isValid || entity.world.uid != city.worldId || target.world?.uid != city.worldId) return false
        val authored = if (actor.cycle.phase in setOf(CitizenPhase.TO_WORK, CitizenPhase.WORKING) ||
            actor.cycle.phase == CitizenPhase.READY && actor.plan.completeAtWorkplace) actor.plan.workplace ?: return false else actor.plan.storage
        recoveryBudget--
        citizenRotateAfterAdmission(actors, actor.workerId)
        val viewers = viewers()
        val point = citizenRecoveryTarget(city, authored) { candidate ->
            citizenStandable(entity.world, candidate) &&
                citizenHasViewer(city.worldId, candidate.point(), viewers, viewerRange)
        } ?: return false
        val destination = point.location(entity.world)
        cancelRoute(actor)
        if (!arrived(entity.location, destination) && !entity.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)) return false
        if (!arrived(entity.location, destination)) return false
        actor.targets[authored] = point
        actor.cycle.arrive(now) // Same phase-specific transition as a verified native arrival; WORKING/READY retain their phase.
        CivsDiagnostics.event("citizen_route_recovered", "city" to city.id, "worker" to actor.workerId,
            "phase" to actor.cycle.phase, "requested" to authored, "destination" to point)
        return true
    }

    private fun submitDelivery(actor: Actor, ticket: UUID, now: Long) {
        val token = actor.plan.token
        val actorId = actor.instanceId
        val workerId = actor.workerId
        val future = try {
            delivery(actor.cityId, actor.workerId, requireNotNull(token.assignmentId), token.completedCycles,
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

    private fun drainCompletions(now: Long) {
        while (true) {
            val completion = completions.poll() ?: return
            val actor = actors[completion.workerId] ?: continue
            if (actor.instanceId != completion.actorId || actor.plan.token != completion.token ||
                !actor.cycle.acceptsCompletion(completion.ticket)) continue
            val error = (completion.error as? CompletionException)?.cause ?: completion.error
            if (error != null) {
                actor.cycle.failDelivery((error as? CivsRejection)?.reason, now)
                if (error !is CivsRejection) logFailure(actor, "delivery", error)
            } else {
                val worker = completion.city?.workers?.find { it.id == actor.workerId }
                if (worker == null || worker.completedCycles != completion.token.completedCycles + 1) {
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
        actor.npc.navigator.cancelNavigation()
    }

    private fun logFailure(actor: Actor, phase: String, error: Throwable) {
        if (actor.reportedFailure) return
        actor.reportedFailure = true
        plugin.logger.log(Level.WARNING,
            "ArcCivs NPC failure city=${actor.cityId} worker=${actor.workerId} phase=$phase cycle=${actor.plan.token.completedCycles}", error)
    }

    private fun configure(npc: NPC, type: BuildingType?) {
        npc.isProtected = true
        npc.setUseMinecraftAI(false)
        npc.setAlwaysUseNameHologram(false)
        npc.data().set(NPC.Metadata.KEEP_CHUNK_LOADED, false)
        npc.data().set(NPC.Metadata.SHOULD_SAVE, false)
        npc.data().set(NPC.Metadata.VILLAGER_BLOCK_TRADES, true)
        npc.data().set(NPC.Metadata.PICKUP_ITEMS, false)
        npc.data().set(NPC.Metadata.DROPS_ITEMS, false)
        npc.data().set(NPC.Metadata.COLLIDABLE, false)
        npc.data().set(NPC.Metadata.FLUID_PUSHABLE, false)
        npc.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, true)
        npc.navigator.defaultParameters.pathfinderType(PathfinderType.MINECRAFT).stuckAction(null)
            .destinationTeleportMargin(-1.0).distanceMargin(0.35).pathDistanceMargin(0.35)
            .straightLineTargetingDistance(0f).stationaryTicks(100).range(96f).speedModifier(1.0f)
        setProfession(npc, type)
    }

    private fun setProfession(npc: NPC, type: BuildingType?) {
        npc.getOrAddTrait(VillagerProfession::class.java).profession = when (type) {
            BuildingType.TOWN_HALL, BuildingType.BUILDER_HUT -> Villager.Profession.TOOLSMITH
            BuildingType.FORESTER -> Villager.Profession.FLETCHER
            BuildingType.QUARRY -> Villager.Profession.MASON
            BuildingType.FARM -> Villager.Profession.FARMER
            else -> Villager.Profession.NONE
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
        if (distance > 0.65) return false
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
        var cycle = CitizenCycle(plan.token, plan.workMillis, plan.completeAtWorkplace)
        val targets = mutableMapOf<CivPosition, CivPosition>()
        var wander = CitizenWander()
        var route: Route? = null
        var reportedFailure = false
    }
    private class Route(val from: CitizenPoint, val target: Location, val chunks: CitizenChunks, val startedAt: Long, val timeoutMillis: Long) {
        var completion: CitizenNavigationCompletion? = null
        var callbackPosition: CitizenPoint? = null
        var callbackNavigating: Boolean? = null
        var path: CitizenPathSnapshot? = null
        var strategy: String? = null
    }
    private data class Completion(val workerId: UUID, val actorId: UUID, val ticket: UUID, val token: CitizenToken,
        val city: Settlement?, val error: Throwable?)

    private companion object {
        val NAVIGATION_DIAGNOSTIC = StructuredDebugLine("ARCCIVS_NAVIGATION_FAILED")
    }
}

/**
 * Keep native movement under Citizens lifecycle, but use Paper's exact-block path request.
 * Citizens' Minecraft location strategy uses a one-block reach range, which can finish
 * 1.45 blocks before an entrance. Paper findPath uses reach range zero on Paper 1.21.11.
 */
internal class CitizenExactNavigation(
    private val pathfinder: Pathfinder,
    target: Location,
    private val parameters: NavigatorParameters,
) : AbstractPathStrategy(TargetType.LOCATION) {
    private val target = target.clone()
    private var started = false
    private var stopped = false
    private var path: Pathfinder.PathResult? = null

    override fun getTargetAsLocation(): Location = target.clone()
    override fun getCurrentDestination(): Location = target.clone()
    override fun getPath(): Iterable<Vector>? = path?.points?.map { it.toVector() }

    override fun update(): Boolean {
        if (stopped) return true
        if (!started) {
            // Citizens applies the route-local native range before this first update.
            started = true
            val candidate = pathfinder.findPath(target)
            path = candidate
            val end = candidate?.finalPoint
            if (candidate == null || !candidate.canReachFinalPoint() || end == null ||
                end.world != target.world || end.blockX != target.blockX ||
                end.blockY != target.blockY || end.blockZ != target.blockZ ||
                !pathfinder.moveTo(candidate, parameters.speedModifier().toDouble())) {
                setCancelReason(CancelReason.STUCK)
                return true
            }
        }
        return !pathfinder.hasPath()
    }

    override fun stop() {
        if (stopped) return
        stopped = true
        path = null
        pathfinder.stopPathfinding()
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
    completion != null -> if (arrived) CitizenNavigationOutcome.ARRIVED else CitizenNavigationOutcome.COMPLETED_OUTSIDE_ARRIVAL
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

/** NPC.update also runs during inactive villager ticks, too late to activate that tick's movement. */
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
internal fun citizenRouteWithinReach(from: CitizenPoint, to: CitizenPoint): Boolean {
    val maximum = CivsRules.MAX_WORK_ROUTE_DISTANCE + CITIZEN_ROUTE_ARRIVAL_TOLERANCE
    return from.distanceSquared(to) <= maximum * maximum
}
internal fun citizenStorageNeedsReset(previous: CivPosition, next: CivPosition, actualPosition: CitizenPoint): Boolean =
    previous != next && !citizenRouteWithinReach(actualPosition, next.point())

internal data class CitizenToken(val assignmentId: UUID?, val completedCycles: Long, val nextDeliveryAt: Long, val constructionId: UUID?)
internal data class CitizenPlan(val token: CitizenToken, val storage: CivPosition, val workplace: CivPosition?,
    val type: BuildingType?, val workMillis: Long, val idleReason: String?, val completeAtWorkplace: Boolean = false)

internal fun citizenPlan(city: Settlement, worker: CivWorker): CitizenPlan? {
    val assigned = worker.assignment?.let(city::building)
    val builder = assigned?.let(CivsRules::isBuilder) == true
    val order = city.construction.takeIf { builder }
    val workplace = if (builder) order?.plannedBuilding?.entrance ?: order?.buildingId?.let(city::building)?.entrance else assigned?.entrance
    val storage = city.buildings.filter(CivsRules::isDepot)
        .minByOrNull { it.entrance.point().distanceSquared((workplace ?: city.center).point()) }?.entrance ?: return null
    val reason = when {
        worker.assignment == null -> "unassigned"
        assigned == null -> "building-unavailable"
        builder && order == null -> "no-construction"
        builder && !CivsRules.builderCanWork(city, assigned) -> "building-unavailable"
        builder && order?.phase == ConstructionPhase.WAITING_MATERIALS -> "waiting-materials"
        builder && order?.phase == ConstructionPhase.PAUSED -> "construction-paused"
        builder && order?.phase in setOf(ConstructionPhase.READY_TO_PLACE, ConstructionPhase.PLACING) -> "placing"
        !builder && !CivsRules.productionAvailable(city, assigned) -> "building-unavailable"
        workplace == null -> "building-unavailable"
        else -> null
    }
    return CitizenPlan(CitizenToken(worker.assignment, worker.completedCycles, worker.nextDeliveryAt, order?.id),
        storage, workplace, assigned?.type,
        if (builder && order?.payment != null) CivsRules.BUILDER_SESSION_INTERVAL else assigned?.type?.let(CivsRules::deliveryInterval) ?: 0,
        reason, builder && order?.payment != null)
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
        schedule(now, if (arrived) 5_000L else 15_000L)
    }
    private fun schedule(now: Long, delay: Long) {
        nextAt = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
    }
}

private val CITIZEN_WANDER_OFFSETS = listOf(4 to 0, 3 to 3, 0 to 4, -3 to 3, -4 to 0, -3 to -3, 0 to -4, 3 to -3)
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
    return CivPosition(x.toInt(), y.toInt(), z.toInt()).takeIf { city.contains(it.x, it.z) }
}

internal fun citizenStandable(world: World, point: CivPosition): Boolean {
    if (!citizenChunkReady(world, point.x shr 4, point.z shr 4) ||
        point.y <= world.minHeight || point.y + 1 >= world.maxHeight) return false
    val feet = world.getBlockAt(point.x, point.y, point.z)
    val head = world.getBlockAt(point.x, point.y + 1, point.z)
    val floor = world.getBlockAt(point.x, point.y - 1, point.z)
    val support = floor.boundingBox
    val clearFeet = feet.isPassable || feet.type.name.endsWith("_CARPET") && feet.boundingBox.maxY <= point.y + 0.125
    return clearFeet && !feet.isLiquid && head.isPassable && !head.isLiquid &&
        !floor.isLiquid && support.maxY >= point.y - 0.01 &&
        support.minX <= point.x + 0.2 && support.maxX >= point.x + 0.8 &&
        support.minZ <= point.z + 0.2 && support.maxZ >= point.z + 0.8
}

internal enum class CitizenPhase { IDLE, TO_STORAGE, TO_WORK, WORKING, RETURNING, READY, DELIVERING, PAUSED, CLOSED }

private const val CITIZEN_DELIVERY_RETRY_DELAY_MILLIS = 5_000L

/** Only observed work counts. Producers return cargo to storage; builders finish the session at their worksite. */
internal class CitizenCycle(val token: CitizenToken, private val workMillis: Long, private val completeAtWorkplace: Boolean = false) {
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
    val failedAttempts: Int get() = retries.attempts

    fun start() { if (phase == CitizenPhase.IDLE) phase = CitizenPhase.TO_STORAGE }
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
        if (workedMillis >= workMillis) phase = if (completeAtWorkplace) CitizenPhase.READY else CitizenPhase.RETURNING
    }
    fun beginDelivery(now: Long): UUID? {
        if (phase != CitizenPhase.READY || now < token.nextDeliveryAt) return null
        phase = CitizenPhase.DELIVERING
        return UUID.randomUUID().also { ticket = it }
    }
    fun acceptsCompletion(candidate: UUID): Boolean = phase == CitizenPhase.DELIVERING && ticket == candidate
    fun failDelivery(reason: String?, now: Long) {
        if (phase != CitizenPhase.DELIVERING) return
        if (reason != "city-busy") {
            pause(if (reason == "storage-full") "storage-full" else "delivery-failed", now, true)
            return
        }
        // Another worker's durable commit is still pending; the completed trip remains valid.
        this.reason = "waiting"
        resumePhase = CitizenPhase.READY
        ticket = null
        deliveryRetryAt = now + CITIZEN_DELIVERY_RETRY_DELAY_MILLIS
        phase = CitizenPhase.PAUSED
    }
    fun pause(reason: String, now: Long, retainWork: Boolean) {
        if (phase == CitizenPhase.CLOSED) return
        this.reason = reason
        resumePhase = if (retainWork) CitizenPhase.READY else CitizenPhase.TO_STORAGE
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
        CitizenPhase.TO_STORAGE, CitizenPhase.RETURNING -> "moving-to-storage"
        CitizenPhase.TO_WORK -> "moving-to-work"
        CitizenPhase.WORKING -> "working"
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
