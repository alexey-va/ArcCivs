package ru.arc.civs

import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.ai.PathfinderType
import net.citizensnpcs.api.event.NPCRightClickEvent
import net.citizensnpcs.api.npc.MemoryNPCDataStore
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.npc.NPCRegistry
import net.citizensnpcs.trait.VillagerProfession
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import kotlin.math.abs

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
    private val actors = linkedMapOf<UUID, Actor>()
    private val statuses = mutableMapOf<UUID, String>()
    private val spawnRetries = mutableMapOf<UUID, Pair<CitizenPlan, CitizenRetry>>()
    private val completions = ConcurrentLinkedQueue<Completion>()
    private val closed = AtomicBoolean()

    init {
        require(maxActive in 1..48 && viewerRange in 1..96)
        checkMainThread()
        registry = CitizensAPI.createAnonymousNPCRegistry(MemoryNPCDataStore())
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    fun pulse(nowMillis: Long) {
        checkMainThread()
        if (closed.get()) return
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
                !loaded(world, plan.storage) || plan.workplace?.let { !loaded(world, it) } == true -> "chunk-unloaded"
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
                actor.plan = nextPlan
                actor.cycle = CitizenCycle(nextPlan.token, nextPlan.workMillis)
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
                !loaded(world, plan.storage) || plan.workplace?.let { !loaded(world, it) } == true -> "chunk-unloaded"
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
        return "workers.status.${if (closed.get()) "closed" else statuses[workerId] ?: "sleeping"}"
    }

    fun activeCount(): Int {
        checkMainThread()
        return actors.size
    }

    private fun update(actor: Actor, city: Settlement, world: World, now: Long) {
        val cycle = actor.cycle
        actor.plan.idleReason?.let {
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
                    when {
                        !route.chunks.loaded(world) -> pause(actor, "chunk-unloaded", now)
                        route.success == false -> pause(actor, "route-unreachable", now)
                        route.success == true -> {
                            actor.route = null
                            if (arrived(actor.npc.entity.location, route.target)) cycle.arrive(now)
                            else pause(actor, "route-unreachable", now)
                        }
                        now - route.startedAt > route.timeoutMillis || !actor.npc.navigator.isNavigating ->
                            pause(actor, "route-unreachable", now)
                    }
                }
            }
            CitizenPhase.WORKING -> {
                if (!arrived(actor.npc.entity.location, requireNotNull(actor.plan.workplace).location(world)))
                    pause(actor, "route-unreachable", now)
                else cycle.work(now)
            }
            CitizenPhase.READY -> {
                if (!arrived(actor.npc.entity.location, actor.plan.storage.location(world))) {
                    pause(actor, "route-unreachable", now)
                } else if (!hasCapacity(city, actor.plan)) {
                    cycle.pause("storage-full", now, true)
                } else cycle.beginDelivery(now)?.let { ticket -> submitDelivery(actor, ticket, now) }
            }
            else -> Unit
        }
        statuses[actor.workerId] = cycle.status()
    }

    private fun startRoute(actor: Actor, world: World, target: CivPosition, now: Long) {
        val from = actor.npc.entity.location
        val to = target.location(world)
        val distance = from.distance(to)
        if (!citizenRouteWithinReach(from.point(), to.point())) {
            pause(actor, "route-unreachable", now)
            return
        }
        val chunks = CitizenChunks.between(from.blockX, from.blockZ, target.x, target.z)
        val chunksLoaded = chunks.loaded(world)
        if (!chunksLoaded || !standable(world, target)) {
            pause(actor, if (!chunksLoaded) "chunk-unloaded" else "route-unreachable", now)
            return
        }
        // Citizens itself retains the already-loaded destination while navigating. ArcCivs never loads a chunk.
        val navigator = actor.npc.navigator
        navigator.defaultParameters.range((distance + 8).coerceAtLeast(16.0).toFloat())
        val route = Route(to, chunks, now, (10_000 + distance * 1_500).toLong().coerceAtMost(90_000))
        actor.route = route
        navigator.setTarget(to)
        navigator.localParameters.addSingleUseCallback { reason ->
            if (actor.route === route) route.success = reason == null
        }
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
                val reason = if (error is CivsRejection && error.reason == "storage-full") "storage-full" else "delivery-failed"
                actor.cycle.pause(reason, now, true)
                if (error !is CivsRejection) logFailure(actor, "delivery", error)
            } else {
                val worker = completion.city?.workers?.find { it.id == actor.workerId }
                if (worker == null || worker.completedCycles != completion.token.completedCycles + 1) {
                    actor.cycle.pause("delivery-failed", now, true)
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
            BuildingType.TOWN_HALL -> Villager.Profession.TOOLSMITH
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
            .filter { standable(world, it) }
            .map { it.location(world) }
            .filter { citizenHasViewer(world.uid, it.point(), viewers, viewerRange) }
            .firstOrNull { location -> actors.values.none { actor ->
                actor.npc.isSpawned && actor.worldId == world.uid && actor.npc.entity.location.distanceSquared(location) < 0.8
            } }
    }

    private fun standable(world: World, point: CivPosition): Boolean {
        if (!loaded(world, point) || point.y <= world.minHeight || point.y + 1 >= world.maxHeight) return false
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

    private fun arrived(from: Location, to: Location): Boolean {
        if (from.world != to.world || abs(from.y - to.y) > 0.5) return false
        val offset = to.toVector().subtract(from.toVector())
        val distance = offset.length()
        if (distance > 0.65) return false
        if (distance < 0.01) return true
        return from.world!!.rayTraceBlocks(from.clone().add(0.0, 0.8, 0.0), offset.normalize(), distance,
            FluidCollisionMode.ALWAYS, true) == null
    }

    private fun loaded(world: World, point: CivPosition): Boolean = world.isChunkLoaded(point.x shr 4, point.z shr 4)

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

    override fun close() {
        checkMainThread()
        if (!closed.compareAndSet(false, true)) return
        HandlerList.unregisterAll(this)
        actors.values.toList().forEach { remove(it, "closed") }
        runCatching { registry.deregisterAll() }.onFailure {
            plugin.logger.log(Level.WARNING, "ArcCivs Citizens registry cleanup failed", it)
        }
        completions.clear()
        spawnRetries.clear()
        statuses.clear()
    }

    private fun checkMainThread() = check(Bukkit.isPrimaryThread()) { "ArcCivs Citizens access must be on the server thread" }

    private class Actor(val cityId: UUID, val workerId: UUID, val worldId: UUID, val npc: NPC, var plan: CitizenPlan) {
        val instanceId: UUID = UUID.randomUUID()
        var cycle = CitizenCycle(plan.token, plan.workMillis)
        var route: Route? = null
        var reportedFailure = false
    }
    private class Route(val target: Location, val chunks: CitizenChunks, val startedAt: Long, val timeoutMillis: Long) {
        var success: Boolean? = null
    }
    private data class Completion(val workerId: UUID, val actorId: UUID, val ticket: UUID, val token: CitizenToken,
        val city: Settlement?, val error: Throwable?)
}

internal data class CitizenPoint(val x: Double, val y: Double, val z: Double) {
    fun distanceSquared(other: CitizenPoint): Double = (x - other.x) * (x - other.x) +
        (y - other.y) * (y - other.y) + (z - other.z) * (z - other.z)
}
internal data class CitizenViewer(val worldId: UUID, val point: CitizenPoint)
internal fun citizenHasViewer(worldId: UUID, point: CitizenPoint, viewers: List<CitizenViewer>, range: Int): Boolean =
    viewers.any { it.worldId == worldId && it.point.distanceSquared(point) <= range.toDouble() * range }
internal fun citizenSpawnBudget(active: Int, maximum: Int): Int = (maximum.coerceIn(0, 48) - active).coerceIn(0, 2)

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
    val type: BuildingType?, val workMillis: Long, val idleReason: String?)

internal fun citizenPlan(city: Settlement, worker: CivWorker): CitizenPlan? {
    val assigned = worker.assignment?.let(city::building)
    val builder = assigned?.type == BuildingType.TOWN_HALL
    val order = city.construction.takeIf { builder }
    val workplace = if (builder) order?.plannedBuilding?.entrance ?: order?.buildingId?.let(city::building)?.entrance else assigned?.entrance
    val storage = city.buildings.filter { it.phase == BuildingPhase.ACTIVE &&
        (it.type == BuildingType.TOWN_HALL || it.type == BuildingType.WAREHOUSE) }
        .minByOrNull { it.entrance.point().distanceSquared((workplace ?: city.center).point()) }?.entrance ?: return null
    val reason = when {
        worker.assignment == null -> "unassigned"
        assigned == null || assigned.phase != BuildingPhase.ACTIVE || CivsRules.slots(assigned) == 0 -> "building-unavailable"
        builder && order == null -> "no-construction"
        builder && order?.phase != ConstructionPhase.CONSTRUCTING -> "construction-paused"
        workplace == null -> "building-unavailable"
        else -> null
    }
    return CitizenPlan(CitizenToken(worker.assignment, worker.completedCycles, worker.nextDeliveryAt, order?.id),
        storage, workplace, assigned?.type, assigned?.type?.let(CivsRules::deliveryInterval) ?: 0, reason)
}

private fun hasCapacity(city: Settlement, plan: CitizenPlan): Boolean {
    val building = plan.token.assignmentId?.let(city::building) ?: return true
    val resource = CivsRules.resource(building.type) ?: return true
    return CivsRules.capacity(city) - city.stock[resource] >= CivsRules.deliveryAmount(building)
}

internal enum class CitizenPhase { IDLE, TO_STORAGE, TO_WORK, WORKING, RETURNING, READY, DELIVERING, PAUSED, CLOSED }

/** Only observed work time counts. Every cycle must visit storage, workplace and storage in that order. */
internal class CitizenCycle(val token: CitizenToken, private val workMillis: Long) {
    var phase = CitizenPhase.IDLE
        private set
    var reason: String? = null
        private set
    private var workedMillis = 0L
    private var observedAt = 0L
    private var ticket: UUID? = null
    private var resumePhase = CitizenPhase.TO_STORAGE
    private val retries = CitizenRetry()

    fun start() { if (phase == CitizenPhase.IDLE) phase = CitizenPhase.TO_STORAGE }
    fun arrive(now: Long) {
        phase = when (phase) {
            CitizenPhase.TO_STORAGE -> CitizenPhase.TO_WORK
            CitizenPhase.TO_WORK -> CitizenPhase.WORKING.also { workedMillis = 0; observedAt = now }
            CitizenPhase.RETURNING -> CitizenPhase.READY
            else -> phase
        }
    }
    fun work(now: Long) {
        if (phase != CitizenPhase.WORKING) return
        val delta = now - observedAt
        observedAt = now
        if (delta in 0..2_500) workedMillis += delta
        if (workedMillis >= workMillis) phase = CitizenPhase.RETURNING
    }
    fun beginDelivery(now: Long): UUID? {
        if (phase != CitizenPhase.READY || now < token.nextDeliveryAt) return null
        phase = CitizenPhase.DELIVERING
        return UUID.randomUUID().also { ticket = it }
    }
    fun acceptsCompletion(candidate: UUID): Boolean = phase == CitizenPhase.DELIVERING && ticket == candidate
    fun pause(reason: String, now: Long, retainWork: Boolean) {
        if (phase == CitizenPhase.CLOSED) return
        this.reason = reason
        resumePhase = if (retainWork) CitizenPhase.READY else CitizenPhase.TO_STORAGE
        ticket = null
        retries.fail(now)
        phase = CitizenPhase.PAUSED
    }
    fun retry(now: Long) { if (phase == CitizenPhase.PAUSED && retries.ready(now)) resume() }
    fun resume() {
        if (phase != CitizenPhase.PAUSED) return
        phase = resumePhase
        reason = null
    }
    fun close() { phase = CitizenPhase.CLOSED; ticket = null }
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

private data class CitizenChunks(val minX: Int, val maxX: Int, val minZ: Int, val maxZ: Int) {
    fun contains(x: Int, z: Int) = x in minX..maxX && z in minZ..maxZ
    fun loaded(world: World): Boolean = (minX..maxX).all { x -> (minZ..maxZ).all { z -> world.isChunkLoaded(x, z) } }
    companion object {
        fun between(x1: Int, z1: Int, x2: Int, z2: Int) = CitizenChunks(
            (minOf(x1, x2) shr 4) - 1, (maxOf(x1, x2) shr 4) + 1,
            (minOf(z1, z2) shr 4) - 1, (maxOf(z1, z2) shr 4) + 1)
    }
}

private fun CivPosition.location(world: World) = Location(world, x + 0.5, y.toDouble(), z + 0.5)
private fun CivPosition.point() = CitizenPoint(x + 0.5, y.toDouble(), z + 0.5)
private fun Location.point() = CitizenPoint(x, y, z)
private fun CivPosition.inChunk(x: Int, z: Int) = this.x shr 4 == x && this.z shr 4 == z
private fun citizenName(name: String, id: UUID): String = name.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
    .trim().take(24).ifEmpty { id.toString().take(8) }
