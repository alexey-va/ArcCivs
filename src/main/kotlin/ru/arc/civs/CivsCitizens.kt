package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Door
import org.bukkit.block.data.type.Gate
import org.bukkit.entity.Player
import org.bukkit.entity.Monster
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
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.observability.StructuredDebugLine
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.npc.PacketNpcProfile
import ru.arc.paper.npc.PacketNpcPose
import ru.arc.paper.npc.PacketNpcInteraction
import ru.arc.paper.npc.PacketPlayerNpc
import ru.arc.paper.npc.PaperPacketNpcs
import java.util.Random
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Bounded resident simulation; Core owns only the client-side player representation. */
class CivsCitizens(
    private val plugin: Plugin,
    private val cityProvider: () -> Collection<Settlement>,
    private val delivery: (UUID, UUID, UUID, Long, Long, UUID?, Long) -> CompletableFuture<Settlement>,
    private val interact: (Player, UUID, UUID) -> Unit,
    private val maxActive: Int = 48,
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
    private val constructionWorkplace: (UUID, UUID) -> CivPosition? = { _, _ -> null },
    private val collectionAvailable: (UUID) -> Boolean = { false },
    private val collectionHome: (Settlement, CivWorker) -> CivCollectedHome? = { _, _ -> null },
    private val pickupGround: (UUID, UUID, List<UUID>) -> CompletableFuture<Settlement> = { _, _, _ ->
        CompletableFuture.failedFuture(CivsRejection("storage-unavailable")) },
    private val depositCollected: (UUID, UUID, CivPosition?) -> CompletableFuture<Settlement> = { _, _, _ ->
        CompletableFuture.failedFuture(CivsRejection("storage-unavailable")) },
    private val immortalResidents: (UUID) -> Boolean = { false },
) : Listener, AutoCloseable {
    private val packets: PaperPacketNpcs
    private val ownsNavigationTasks = navigationTasks == null
    private val residentTasks = navigationTasks ?: LifecycleTaskScope(BukkitTaskScheduler(plugin))
    private val navigation = CivsResidentNavigation(residentTasks, navigationWork, navigationExecutor)
    private val paths = CivsPaths()
    private val threats: CivsResidentThreats
    private val skins: List<CitizenSkin>
    private var actors = linkedMapOf<UUID, Actor>()
    private var statuses = mutableMapOf<UUID, String>()
    private var spawnRetries = mutableMapOf<UUID, Pair<CitizenPlan, CitizenRetry>>()
    private val admissions = mutableMapOf<UUID, CitizenAdmission>()
    private val admissionOrigins = mutableMapOf<UUID, Pair<UUID, CivPosition>>()
    @Volatile private var completions = ConcurrentLinkedQueue<Completion>()
    private val closed = AtomicBoolean()
    private var retirement: CompletableFuture<Void>? = null
    private var wanderBudget = 0
    private var campBudget = 0
    private var collectionBudget = 0
    private var recoveryBudget = 0
    private val campfires = CivsCampfires()
    private val voices = CivsCitizenVoices(plugin.config)
    private val pendingHarm = mutableSetOf<UUID>()
    private val harmCompletions = ConcurrentLinkedQueue<HarmCompletion>()
    private val supplyCompletions = ConcurrentLinkedQueue<SupplyCompletion>()
    private val collectedCompletions = ConcurrentLinkedQueue<CollectedCompletion>()
    private var harmEpoch = 0L
    private var navigationTick = 0L
    private var navigationAt = 0L
    private var navigationCadenceMillis = 0L
    private var courierObservedAt = 0L
    internal var residentStepAllowed: (PacketPlayerNpc, Location, Location) -> Boolean = { _, _, _ -> true }

    internal fun collisionActors(): Collection<PacketPlayerNpc> {
        checkMainThread()
        return actors.values.map { it.npc }
    }

    /** Natural tree growth happens later than tending; protect the bodies present at that instant. */
    internal fun growthBlocked(worldId: UUID, point: CivPosition): Boolean {
        checkMainThread()
        val block = BoundingBox(point.x.toDouble(), point.y.toDouble(), point.z.toDouble(),
            point.x + 1.0, point.y + 1.0, point.z + 1.0)
        // The resident runtime already caps this collection at 48; no native entity/world scan.
        return actors.values.any { actor -> actor.worldId == worldId && actor.npc.isValid &&
            (actor.sleepPose?.takeIf { actor.sleeping }?.bounds
                ?: citizenStandingBounds(actor.npc.location, actor.npc.scale)).overlaps(block) }
    }

    /** Logical grounded feet stay stable while the sleeping mesh has a pose-only lift. */
    internal fun residentFeet(workerId: UUID): Location? {
        checkMainThread()
        val actor = actors[workerId]?.takeIf { it.npc.isValid } ?: return null
        return actor.sleepOrigin?.clone() ?: actor.npc.location.clone()
    }

    internal fun residentCollectionFeet(workerId: UUID, allowNight: Boolean = false): Location? {
        checkMainThread()
        val actor = actors[workerId]?.takeIf { it.npc.isValid } ?: return null
        if (actor.sleeping || !allowNight && actor.sheltering || actor.attacker != null || workerId in pendingHarm ||
            !allowNight && actor.npc.world.time in 12_000L..23_000L) return null
        return actor.npc.location.clone()
    }

    /** A small physical push changes only the observed position, never a work or cargo receipt. */
    internal fun nudgeResident(npc: PacketPlayerNpc, impulse: Vector): Boolean {
        checkMainThread()
        if (closed.get() || !npc.isValid || npc.pose == PacketNpcPose.SLEEPING) return false
        val actor = actors.values.firstOrNull { it.npc === npc } ?: return false
        val city = cityProvider().firstOrNull { it.id == actor.cityId && it.pendingDeletion == null } ?: return false
        val from = npc.location
        val to = from.clone().add(impulse)
        if (npc.world.uid != city.worldId || !citizenResidentStepClear(npc, from, to, shelterOccupied(actor)) ||
            !citizenResidentNudgeClear(npc, from, to)) return false
        if (!residentRouteStepAllowed(actor, city, from, to)) return false
        npc.teleport(to)
        actor.footsteps.reset()
        return true
    }

    private fun residentRouteStepAllowed(actor: Actor, city: Settlement, from: Location, to: Location): Boolean {
        val target = actor.plan.workplace ?: actor.plan.storage
        val allowed = actor.route?.allowedBuildings ?: citizenRouteBuildings(city, actor.plan, target,
            CivPosition(from.blockX, from.blockY, from.blockZ))
        val halfWidth = .3 * actor.npc.scale
        for (x in kotlin.math.floor(to.x - halfWidth).toInt()..kotlin.math.floor(to.x + halfWidth).toInt())
            for (z in kotlin.math.floor(to.z - halfWidth).toInt()..kotlin.math.floor(to.z + halfWidth).toInt())
                if (!citizenRoutePointAllowed(city, allowed, CivPosition(x, kotlin.math.floor(to.y).toInt(), z), routeMine(actor, city))) return false
        return citizenControlStepClear(city, from.point(), to.point())
    }

    private fun residentMoveAllowed(actor: Actor, from: Location, to: Location): Boolean =
        citizenResidentStepClear(actor.npc, from, to, shelterOccupied(actor)) && residentStepAllowed(actor.npc, from, to)

    private fun routeMoveAllowed(actor: Actor, from: Location, to: Location): Boolean {
        if (citizenResidentStepClear(actor.npc, from, to, shelterOccupied(actor)))
            return residentStepAllowed(actor.npc, from, to).also { allowed ->
                if (!allowed) actor.route?.nativeCollisionTicks = 40
            }
        // Preserve the aggregate fast path; identify an owner only for an actually denied sweep.
        actor.collisionResident = actors.values.firstOrNull { other -> other !== actor &&
            other.worldId == actor.worldId && other.npc.isValid &&
            !citizenResidentStepClear(actor.npc, from, to, residentOccupied(other)) }?.workerId
        return false
    }

    private fun canYield(actor: Actor): Boolean = actor.npc.isValid && !actor.sleeping &&
        actor.route?.let { it.completion != null || it.motion.finished || it.motion.failure != null } != true &&
        actor.attacker == null && actor.supplyPickup == null && actor.collectedPending == null &&
        !actor.courierHeld && actor.workerId !in pendingHarm &&
        (if (actor.sheltering) actor.route != null else actor.cycle.phase in setOf(CitizenPhase.IDLE, CitizenPhase.PAUSED,
            CitizenPhase.TO_STORAGE, CitizenPhase.TO_WORK, CitizenPhase.RETURNING))

    private fun yieldPriority(actor: Actor): Int = when {
        actor.plan.idleReason != null || actor.cycle.phase in setOf(CitizenPhase.IDLE, CitizenPhase.PAUSED) -> 0
        actor.cycle.phase == CitizenPhase.RETURNING || actor.returningToWarehouse || actor.plan.courier?.carrying == true -> 2
        else -> 1
    }

    /** Admission is read-only: a player must never be pushed by a resident's yielding probe. */
    private fun yieldPlayerClear(actor: Actor, from: Location, to: Location): Boolean {
        val sweep = citizenStandingBounds(from, actor.npc.scale).union(citizenStandingBounds(to, actor.npc.scale))
        val players = actor.npc.world.getNearbyPlayers(Location(actor.npc.world, sweep.centerX, sweep.centerY, sweep.centerZ),
            sweep.widthX / 2, sweep.height / 2, sweep.widthZ / 2)
            .filter { it.isOnline && it.isValid && !it.isDead && it.gameMode != GameMode.SPECTATOR &&
                !it.isInsideVehicle && !it.isSleeping && it.isCollidable }.map { it.boundingBox }
        return citizenResidentStepClear(actor.npc, from, to, players)
    }

    /** One resident yields; two approaching bodies need not overlap before either can step aside. */
    private fun beginResidentYield(actor: Actor): Boolean {
        val other = actor.collisionResident?.let(actors::get) ?: return false
        if (actor.residentYield != null || other.residentYield != null) {
            actor.route?.progressTick = navigationTick
            return true
        }
        if (actor.yieldProgress < 2.0) return false
        val candidates = listOf(actor, other).filter { canYield(it) && it.yieldProgress >= 2.0 }.sortedWith(
            compareBy<Actor> { yieldPriority(it) }.thenByDescending { it.workerId })
        val yielding = candidates.firstOrNull() ?: return false
        val passing = if (yielding === actor) other else actor
        if (navigationTick < yielding.yieldRetryAfter) return false
        yielding.yieldRetryAfter = navigationTick + 100
        val city = cityProvider().firstOrNull { it.id == yielding.cityId && it.pendingDeletion == null } ?: return false
        val from = yielding.npc.location
        val passFrom = passing.npc.location
        val passTo = passing.route?.motion?.destination ?: from
        val forward = passTo.toVector().subtract(passFrom.toVector()).setY(0.0)
        if (forward.lengthSquared() < .000001) return false
        forward.normalize()
        val side = if (abs(forward.x) >= abs(forward.z)) Vector(0.0, 0.0, 1.0) else Vector(1.0, 0.0, 0.0)
        // Three bounded corridors, then the existing two A* attempts and normal pause/retry.
        // 1.25 clears both 0.6-wide bodies at a diagonal approach, not just their current feet.
        for (direction in listOf(side, side.clone().multiply(-1.0), forward)) {
            val to = from.clone().add(direction.clone().multiply(1.25))
            if (!citizenResidentStepClear(yielding.npc, from, to, shelterOccupied(yielding)) ||
                !yieldPlayerClear(yielding, from, to) ||
                !citizenResidentStepClear(passing.npc, passFrom, passTo, listOf(citizenStandingBounds(to, yielding.npc.scale)))) continue
            // A clear endpoint cannot authorize stepping over an unsupported cell in between.
            if (!(1..25).all { index ->
                    val start = from.clone().add(direction.clone().multiply((index - 1) * .05))
                    val end = from.clone().add(direction.clone().multiply(index * .05))
                    residentRouteStepAllowed(yielding, city, start, end) && citizenResidentNudgeClear(yielding.npc, start, end)
                }) continue
            actor.yieldProgress = 0.0
            yielding.yieldProgress = 0.0
            // Both routes deliberately wait while the corridor opens; this earns no walk credit.
            yielding.route?.progressTick = navigationTick
            passing.route?.progressTick = navigationTick
            yielding.residentYield = ResidentYield(passing.workerId, from.point(), to.point(), navigationTick + 80)
            CivsDiagnostics.event("citizen_resident_yield", "city" to yielding.cityId, "worker" to yielding.workerId,
                "passing" to passing.workerId, "action" to "start", "actual" to from.point().coordinates(),
                "target" to to.point().coordinates(), "phase" to yielding.cycle.phase)
            return true
        }
        return false
    }

    /** Shares the four-actor separation budget; every accepted packet step is at most 0.05 block. */
    private fun advanceResidentYield(actor: Actor): Boolean {
        val yielding = actor.residentYield ?: return false
        actor.route?.progressTick = navigationTick
        fun finish(reason: String) {
            actor.residentYield = null
            actor.yieldRetryAfter = navigationTick + 80
            CivsDiagnostics.event("citizen_resident_yield", "city" to actor.cityId, "worker" to actor.workerId,
                "passing" to yielding.passing, "action" to reason, "steps" to yielding.steps,
                "actual" to actor.npc.location.point().coordinates(),
                "displacement" to sqrt(yielding.from.distanceSquared(actor.npc.location.point())))
        }
        if (!canYield(actor) || navigationTick >= yielding.until || actors[yielding.passing]?.npc?.isValid != true) {
            finish("cancelled")
            return true
        }
        val from = actor.npc.location
        val delta = Vector(yielding.target.x - from.x, yielding.target.y - from.y, yielding.target.z - from.z)
        if (delta.lengthSquared() < .000001) {
            if (yielding.holdUntil == 0L) yielding.holdUntil = navigationTick + 20
            if (navigationTick >= yielding.holdUntil) finish("complete")
            return true
        }
        val step = delta.normalize().multiply(minOf(.05, sqrt(from.point().distanceSquared(yielding.target))))
        if (!yieldPlayerClear(actor, from, from.clone().add(step)) || !nudgeResident(actor.npc, step)) finish("blocked")
        else yielding.steps++
        // A side step is physical displacement only; it never advances the work-route watermark.
        return true
    }

    /** Injury persistence still freezes work, but cannot repeatedly cancel a physical exit from a new solid. */
    private fun escapeTerrain(actor: Actor, now: Long): Boolean {
        val city = cityProvider().firstOrNull { it.id == actor.cityId && it.pendingDeletion == null } ?: return false
        if (actor.npc.world.uid != city.worldId) return false
        val from = actor.npc.location
        val escape = citizenResidentEscapeStep(actor.npc) { to ->
            residentRouteStepAllowed(actor, city, from, to) && residentMoveAllowed(actor, from, to)
        }
        if (escape == null) {
            actor.terrainObstructedAt?.let { started ->
                val half = .3 * actor.npc.scale - .001
                if (!citizenSleepClear(actor.npc.world, BoundingBox(from.x - half, from.y + .001, from.z - half,
                        from.x + half, from.y + actor.npc.height - .001, from.z + half))) return true
                CivsDiagnostics.record(plugin.logger, Level.INFO, actor.cityId, CivsDiagnostics.line("citizen_terrain_escape", "city" to actor.cityId,
                    "worker" to actor.workerId, "outcome" to "cleared", "actual" to from.point().coordinates(),
                    "elapsed_ms" to (now - started).coerceAtLeast(0)))
                actor.terrainObstructedAt = null
            }
            return false
        }
        if (actor.terrainObstructedAt == null) {
            actor.terrainObstructedAt = now
            CivsDiagnostics.record(plugin.logger, Level.WARNING, actor.cityId, CivsDiagnostics.line("citizen_terrain_escape", "city" to actor.cityId,
                "worker" to actor.workerId, "outcome" to "blocked", "reason" to "native-body-overlap",
                "actual" to from.point().coordinates(), "block" to escape.material, "obstruction" to escape.obstruction,
                "exit_available" to (escape.step != null), "injury_pending" to (actor.workerId in pendingHarm)))
            stopCamp(actor, "native-body-overlap")
            cancelRoute(actor)
        }
        actor.cycle.deferWork(now)
        statuses[actor.workerId] = "route-unreachable"
        escape.step?.let { actor.npc.teleport(it) }
        actor.footsteps.reset()
        return true
    }

    /** Existing overlaps separate by ordinary bounded steps, without teleport recovery or work credit. */
    private fun separateResident(actor: Actor): Boolean? {
        // ponytail: pair checks stay bounded by 48 owned actors; index only if that cap grows.
        val npc = actor.npc
        val at = npc.location
        val body = citizenStandingBounds(at, npc.scale)
        val other = actors.values.firstOrNull { it !== actor && it.worldId == actor.worldId && it.npc.isValid &&
            !it.sleeping && citizenStandingBounds(it.npc.location, it.npc.scale).overlaps(body) } ?: return null
        val away = at.toVector().subtract(other.npc.location.toVector()).setY(0.0)
        if (away.lengthSquared() < .000001) away.x = if (actor.workerId < other.workerId) -1.0 else 1.0
        away.normalize().multiply(.05)
        val moved = if (nudgeResident(npc, away)) true else {
            val tangent = Vector(-away.z, 0.0, away.x)
            nudgeResident(npc, tangent) || nudgeResident(npc, tangent.multiply(-1.0))
        }
        citizenRotateAfterAdmission(actors, actor.workerId)
        return moved
    }

    private fun idleDestinationFree(actor: Actor, to: Location): Boolean {
        val body = citizenStandingBounds(to, actor.npc.scale).expand(.15, 0.0, .15)
        if (shelterOccupied(actor).any(body::overlaps)) return false
        return actors.values.none { other -> other !== actor && other.worldId == actor.worldId && other.npc.isValid &&
            other.route?.target?.let { body.overlaps(citizenStandingBounds(it, other.npc.scale)) } == true }
    }

    init {
        require(maxActive in 1..48 && viewerRange in 1..96)
        checkMainThread()
        skins = loadCitizenSkins()
        packets = packetOwner ?: PaperPacketNpcs(plugin, "civs-residents")
        threats = CivsResidentThreats(plugin, attacked = ::defend, mobProtected = immortalResidents, injury = ::damage)
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
            "pathfinding=async-a-star path_jobs_max=${CivsResidentNavigation.MAXIMUM_JOBS} " +
            "path_capture_budget=" + if (navigationWork != null) "shared-native-work" else
                "${CivsResidentNavigation.SEARCH_OPERATIONS_PER_TICK}steps/${CivsResidentNavigation.SEARCH_BUDGET_NANOS / 1_000}us")
    }

    fun pulse(nowMillis: Long) {
        checkMainThread()
        if (closed.get()) return
        courierObservedAt = nowMillis
        wanderBudget = 8 // Aggregate native target checks across all idle actors, not eight per NPC.
        campBudget = 2 // At most two six-place campfire admission searches for the entire owner.
        collectionBudget = 2 // Two 32-item discoveries or four-holder home checks across the whole owner.
        recoveryBudget = 2 // At most two bounded safe-position searches after real route failures per pulse.
        drainCompletions(nowMillis)
        drainSupplies(nowMillis)
        drainCollected(nowMillis)
        drainHarm()
        val cities = cityProvider().associateBy { it.id }
        paths.retain(cities)
        val workers = cities.values.flatMap { city -> city.workers.map { it.id to (city to it) } }.toMap()
        voices.retain(workers.keys)
        statuses.keys.retainAll(workers.keys)
        spawnRetries.keys.retainAll(workers.keys)
        admissions.keys.retainAll(workers.keys - actors.keys)
        admissionOrigins.keys.retainAll(workers.keys - actors.keys)
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
            val plan = currentPlan(actor, city, worker, nowMillis)
            val reason = when {
                actor.cityId != city.id || actor.worldId != city.worldId -> "unavailable"
                world == null -> "world-unloaded"
                !actor.npc.isValid -> "unavailable"
                actor.npc.world.uid != city.worldId -> "unavailable"
                !activeAt(city.id, city.worldId, actor.npc.location.point(), viewers) -> "sleeping"
                plan == null -> "storage-unavailable"
                !citizenChunkReady(world, actor.npc.location.blockX shr 4, actor.npc.location.blockZ shr 4) ||
                    !plan.chunksReady(world) || actor.route?.chunks?.ready(world) == false -> "chunk-unloaded"
                else -> null
            }
            if (reason != null) {
                remove(actor, reason)
                continue
            }
            if (!reconcilePlan(actor, requireNotNull(plan))) continue
            try {
                syncResident(actor.npc, worker, requireNotNull(plan))
                update(actor, city, requireNotNull(world), nowMillis)
            } catch (error: Exception) {
                pause(actor, "route-unreachable", nowMillis)
                logFailure(actor, "navigation", error)
            }
        }

        var budget = citizenSpawnBudget(0, maxActive)
        workers.values.filter { it.second.id !in actors && it.second.id in pendingHarm }.forEach { (city, worker) ->
            recordAdmission(city, worker, null, null, "injury-pending", nowMillis)
        }
        val candidates = workers.values.filter { it.second.id !in actors && it.second.id !in pendingHarm }
            .map { (city, worker) -> Triple(city, worker, plan(city, worker)) }
            .sortedByDescending { (city, worker, plan) -> plan != null &&
                citizenHasViewer(city.worldId, admissionOrigin(city, worker.id, plan).point(), viewers, viewerRange) }
        for ((city, worker, plan) in candidates) {
            val workerId = worker.id
            val origin = plan?.let { admissionOrigin(city, workerId, it) }
            val visible = origin != null && citizenHasViewer(city.worldId, origin.point(), viewers, viewerRange)
            val world = plugin.server.getWorld(city.worldId)
            val reason = when {
                plan == null -> "storage-unavailable"
                world == null -> "world-unloaded"
                !activeAt(city.id, city.worldId, requireNotNull(origin).point(), viewers) -> "sleeping"
                !plan.chunksReady(world) -> "chunk-unloaded"
                else -> null
            }
            if (reason != null) {
                statuses[workerId] = reason
                if (reason == "sleeping") spawnRetries.remove(workerId)
                recordAdmission(city, worker, plan, world, reason, nowMillis)
                continue
            }
            if (budget == 0) {
                statuses[workerId] = "waiting"
                recordAdmission(city, worker, plan, world, "spawn-budget", nowMillis)
                continue
            }
            val retry = spawnRetries[workerId]?.takeIf { it.first == plan }?.second
                ?: CitizenRetry(exhaustedCooldownMillis = 60_000)
            if (!retry.ready(nowMillis)) {
                recordAdmission(city, worker, plan, world, "spawn-retry-cooldown", nowMillis, retry)
                continue
            }
            if (actors.size >= maxActive && visible) actors.values.firstOrNull { actor ->
                actor.workerId !in pendingHarm && actor.supplyPickup == null &&
                    actor.cycle.phase !in setOf(CitizenPhase.PICKING_UP, CitizenPhase.DELIVERING) &&
                    !citizenHasViewer(actor.worldId, actor.npc.location.point(), viewers, viewerRange)
            }?.let { remove(it, "capacity") }
            if (actors.size >= maxActive) {
                statuses[workerId] = "capacity"
                recordAdmission(city, worker, plan, world, "capacity", nowMillis, retry)
                continue
            }
            budget-- // Failed spawns consume the same per-pulse budget as successful spawns.
            var npc: PacketPlayerNpc? = null
            try {
                requireNotNull(plan)
                val search = spawnPoint(city, requireNotNull(world), requireNotNull(origin),
                    city.workers.indexOf(worker), viewers, nowMillis)
                val point = search.point?.location(world)
                if (point == null) {
                    retry.fail(nowMillis)
                    spawnRetries[workerId] = plan to retry
                    statuses[workerId] = "route-unreachable"
                    recordAdmission(city, worker, plan, world, "no-safe-spawn", nowMillis, retry, search)
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
                setEquipment(npc, plan)
                val actor = Actor(city.id, workerId, city.worldId, npc, plan)
                actors[workerId] = actor
                syncResident(npc, worker, plan)
                spawnRetries.remove(workerId)
                admissions.remove(workerId)
                admissionOrigins.remove(workerId)
                update(actor, city, world, nowMillis)
                if (actors[workerId] === actor) CivsDiagnostics.event("citizen_spawn", "city" to city.id,
                    "worker" to workerId, "type" to "PACKET_PLAYER",
                    "skin" to skin.name, "tool" to (CivsEquipment.slots(worker)[4]?.material ?: citizenTool(plan)?.name))
            } catch (error: Exception) {
                actors.remove(workerId)?.cycle?.close()
                runCatching { npc?.remove() }.onFailure { CivsDiagnostics.record(plugin.logger, Level.WARNING, city.id,
                    "ArcCivs failed to remove rejected NPC city=${city.id} worker=$workerId", it) }
                retry.fail(nowMillis)
                spawnRetries[workerId] = requireNotNull(plan) to retry
                statuses[workerId] = "unavailable"
                recordAdmission(city, worker, plan, world, "spawn-failed", nowMillis, retry)
                if (retry.attempts == 1 || retry.exhausted) CivsDiagnostics.record(plugin.logger, Level.WARNING, city.id,
                    "ArcCivs NPC spawn failed city=${city.id} worker=$workerId attempt=${retry.attempts}", error)
            }
        }
        campfires.scanOne(Bukkit.getCurrentTick()) { plugin.server.getWorld(it) }
        threats.pulse(actors.values.filter { it.workerId !in pendingHarm && it.npc.isValid }
            .map { CivsThreatTarget(it.cityId, it.workerId, it.npc) }, nowMillis)
    }

    /** Hand over admitted field work and short idle legs on the existing bounded worksite pulse. */
    internal fun pulseFieldWork(nowMillis: Long) {
        checkMainThread()
        if (closed.get()) return
        drainCompletions(nowMillis)
        val cities = cityProvider().associateBy { it.id }
        val viewers = viewers()
        // At most eight existing actors, rotated after admission; no spawn, scan or threat pulse.
        val candidates = actors.values.filter { it.plan.type == BuildingType.FARM && it.workerId !in pendingHarm &&
            it.npc.isValid && !it.sleeping && !it.sheltering && it.attacker == null && it.foodSource == null &&
            it.supplyPickup == null && it.social.visit == null }.take(8)
        for (actor in candidates) {
            citizenRotateAfterAdmission(actors, actor.workerId)
            val city = cities[actor.cityId]?.takeIf { it.pendingDeletion == null } ?: continue
            val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: continue
            val world = actor.npc.world
            if (world.uid != city.worldId || world.time in 12_000L..23_000L ||
                !activeAt(city.id, city.worldId, actor.npc.location.point(), viewers)) continue
            val plan = currentPlan(actor, city, worker, nowMillis) ?: continue
            if (plan.worldWork == null && plan.idleReason != "searching-work" || !plan.chunksReady(world) ||
                !citizenChunkReady(world, actor.npc.location.blockX shr 4, actor.npc.location.blockZ shr 4) ||
                actor.route?.chunks?.ready(world) == false) continue
            if (!reconcilePlan(actor, plan)) continue
            try {
                syncResident(actor.npc, worker, plan)
                update(actor, city, world, nowMillis)
            } catch (error: Exception) {
                pause(actor, "route-unreachable", nowMillis)
                logFailure(actor, "field-handoff", error)
            }
        }
        // Keep leisure handoffs responsive without another timer or another native admission budget.
        // Field workers retain all eight slots; at most four idle actors share the normal wander budget.
        val idle = actors.values.filter { it.plan.idleReason == "unassigned" && it.workerId !in pendingHarm &&
            it.npc.isValid && !it.sleeping && !it.sheltering && it.attacker == null && it.foodSource == null &&
            it.supplyPickup == null && it.collectedPending == null && !it.courierHeld && it.social.visit == null }.take(4)
        for (actor in idle) {
            citizenRotateAfterAdmission(actors, actor.workerId)
            val city = cities[actor.cityId]?.takeIf { it.pendingDeletion == null } ?: continue
            val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: continue
            val world = actor.npc.world
            if (world.uid != city.worldId || world.time in 12_000L..23_000L ||
                !activeAt(city.id, city.worldId, actor.npc.location.point(), viewers)) continue
            val plan = currentPlan(actor, city, worker, nowMillis)?.takeIf { it.idleReason == "unassigned" } ?: continue
            if (!plan.chunksReady(world) || !citizenChunkReady(world, actor.npc.location.blockX shr 4, actor.npc.location.blockZ shr 4) ||
                actor.route?.chunks?.ready(world) == false || !reconcilePlan(actor, plan)) continue
            try {
                syncResident(actor.npc, worker, plan)
                update(actor, city, world, nowMillis)
            } catch (error: Exception) {
                pause(actor, "route-unreachable", nowMillis)
                logFailure(actor, "idle-handoff", error)
            }
        }
    }

    /** New work cannot replace an accepted meeting; a changed producer token releases it first. */
    private fun currentPlan(actor: Actor, city: Settlement, worker: CivWorker, now: Long): CitizenPlan? {
        if (actor.courierHeld && (worker.assignment != actor.plan.token.assignmentId ||
                worker.completedCycles != actor.plan.token.completedCycles)) {
            actors.values.filter { it.courierPickupStartedAt != null && it.plan.courier?.cargo?.sourceWorkerId == worker.id }
                .forEach { pause(it, "route-unreachable", now) }
        }
        return if (actor.supplyPickup != null || actor.collectedPending != null || actor.courierHeld) actor.plan
            else citizenCurrentPlan(actor.plan, actor.cycle.phase) { plan(city, worker) }
    }

    private fun reconcilePlan(actor: Actor, nextPlan: CitizenPlan): Boolean {
        val mineEpisode = actor.mineRouteEpisode.key
        val mineActive = nextPlan.type == BuildingType.QUARRY && nextPlan.courier == null &&
            !actor.sheltering && !actor.returningToWarehouse && actor.attacker == null && actor.foodSource == null &&
            actor.social.visit == null
        val discoveringMineWork = mineActive && nextPlan.idleReason == "searching-work" &&
            mineEpisode?.buildingId == nextPlan.token.assignmentId && mineEpisode?.cycle == nextPlan.token.completedCycles
        actor.mineRouteEpisode.retain(if (discoveringMineWork) mineEpisode else if (mineActive)
            nextPlan.worldWork?.let { CivsMineRouteEpisode.key(it) } else null)
        if (actor.plan != nextPlan && (!citizenPlansContinue(actor.plan, nextPlan) ||
                actor.plan.courier?.carrying != nextPlan.courier?.carrying)) {
            if (citizenStorageNeedsReset(actor.plan.storage, nextPlan.storage, actor.npc.location.point())) {
                remove(actor, "waiting")
                return false
            }
            releaseCourierSource(actor, courierObservedAt, nextPlan.courier?.carrying == true)
            cancelRoute(actor)
            stopCamp(actor, "plan-changed")
            actor.cycle.close()
            if (actor.plan.storage != nextPlan.storage || actor.plan.workplace != nextPlan.workplace) actor.targets.clear()
            actor.plan = nextPlan
            actor.cycle = nextPlan.cycle()
            actor.returningToWarehouse = false
            actor.wander = CitizenWander(actor.workerId)
            actor.equipmentItems = null
            setEquipment(actor.npc, nextPlan)
        } else actor.plan = nextPlan
        return true
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
                actor?.combatStatus?.let { return "workers.status.$it" }
                if (actor?.sheltering == true) return "workers.status.${if (actor.sleeping) "sleeping" else "sheltering"}"
                if (actor?.collectedPending != null) return "workers.status.loading-cargo"
                if (actor?.collectionHome != null) return "workers.status.moving-to-storage"
                if (actor?.collectionSeek != null) return "workers.status.walking"
                if (actor?.courierHeld == true) return "workers.status.waiting-courier"
                if (actor?.returningToWarehouse == true) return "workers.status.moving-to-storage"
                actor?.social?.visit?.let {
                    return "workers.status.${if (it.partnerId != null) {
                        if (it.conversationAt == null) "meeting" else "talking"
                    } else if (it.arrivedAt == null) "to-campfire" else "campfire"}"
                }
                val plan = if (actor == null) plan(city, worker)
                    else citizenCurrentPlan(actor.plan, actor.cycle.phase) { plan(city, worker) }
                if (actor != null) {
                    if (actor.cycle.phase == CitizenPhase.PAUSED) return "workers.status.${actor.cycle.status()}"
                    if (navigationFailed(actor))
                        return "workers.status.route-unreachable"
                    if (plan != null) workActivityStatus(actor, plan)?.let { return "workers.status.$it" }
                    if (plan?.idleReason == "unassigned" && actor.route?.let { route ->
                            route.idleAnchor != null && route.motion.navigating && !route.motion.searching &&
                                !route.motion.waitingForCollision && !route.motion.turning
                        } == true) return "workers.status.walking"
                }
                plan?.idleReason?.takeUnless { it == "placing" }?.let { return "workers.status.$it" }
                if (actors[workerId]?.plan?.token?.let { it != plan?.token } == true) return "workers.status.waiting"
                if (actor != null && plan != null) citizenWorkStatus(plan, actor.cycle.phase)?.let { return "workers.status.$it" }
                if (actor != null && plan?.idleReason == "placing") {
                    val placing = actor.cycle.phase == CitizenPhase.WORKING &&
                        plan.token.constructionId?.let { constructionBuilder(actor, city, it) } != null
                    return "workers.status.${if (placing) "placing" else actor.cycle.status()}"
                }
            }
        }
        return "workers.status.${if (closed.get()) "closed" else statuses[workerId] ?: "sleeping"}"
    }

    fun activeCount(): Int {
        checkMainThread()
        return actors.size
    }

    /** Observe owned state without planning, reloading chunks or changing a resident's phase. */
    internal fun debug(cityId: UUID?, workerId: UUID?): Map<String, Any?> {
        checkMainThread()
        val selected = actors.values.filter { (cityId == null || it.cityId == cityId) && (workerId == null || it.workerId == workerId) }
        val absent = admissions.values.filter { (cityId == null || it.cityId == cityId) && (workerId == null || it.workerId == workerId) }
        val now = navigationClock()
        return linkedMapOf("closed" to closed.get(), "maximum_active" to maxActive, "viewer_range" to viewerRange,
            "navigation_tick" to navigationTick,
            "cadence_ms" to navigationCadenceMillis, "planner_global" to navigation.debug(),
            "admission" to mapOf("source" to "last-resident-pulse", "total" to absent.size,
                "truncated" to (absent.size > 8), "entries" to absent.take(8).map { admission -> mapOf(
                    "city" to admission.cityId, "worker" to admission.workerId, "reason" to admission.reason,
                    "observed_at_ms" to admission.at, "origin" to admission.plan?.spawnOrigin,
                    "origin_chunk" to admission.chunk, "required_load_level" to Chunk.LoadLevel.ENTITY_TICKING,
                    "retry_attempts" to admission.retry?.attempts, "retry_exhausted" to admission.retry?.exhausted,
                    "next_retry_at_ms" to admission.retry?.nextAttemptAt,
                    "spawn" to admission.spawn?.let { search -> mapOf("observed_at_ms" to search.at,
                        "candidate_limit" to CITIZEN_SPAWN_CANDIDATE_LIMIT, "inspected" to search.inspected,
                        "rejections" to search.rejections, "sample_limit" to CITIZEN_SPAWN_SAMPLE_LIMIT,
                        "truncated" to (search.inspected > search.samples.size), "samples" to search.samples) }) }),
            "total" to selected.size, "truncated" to (selected.size > 8), "entries" to selected.take(8).map { actor ->
                val valid = actor.npc.isValid
                val position = if (valid) actor.npc.location else null
                val route = actor.route
                linkedMapOf("city" to actor.cityId, "worker" to actor.workerId, "valid" to valid,
                    "status" to statuses[actor.workerId], "phase" to actor.cycle.phase,
                    "pause_reason" to actor.cycle.reason, "failed_attempts" to actor.cycle.failedAttempts,
                    "mine_route_episode" to actor.mineRouteEpisode.snapshot(now)?.let { mapOf(
                        "building" to it.key.buildingId, "plan_version" to it.key.planVersion, "cycle" to it.key.cycle,
                        "mining_step" to it.key.miningStep, "failures" to it.failures, "elapsed_ms" to it.elapsedMillis,
                        "walked_distance" to it.walkedDistance, "suppressed" to it.suppressed) },
                    "injury_pending" to (actor.workerId in pendingHarm),
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
                        "failure" to it.motion.failure, "completion" to it.completion?.cancelReason,
                        "path_progress" to it.motion.forwardProgress, "episode_progress" to actor.navigationEpisodeProgress,
                        "remaining_nodes" to it.motion.remainingNodes, "nodes" to it.motion.nodeCount,
                        "captured_blocks" to it.motion.capturedBlocks, "replans" to it.replanAttempts,
                        "collision_wait" to it.motion.waitingForCollision, "blocking_resident" to actor.collisionResident,
                        "yielding_to" to actor.residentYield?.passing, "destination" to it.motion.destination?.point(),
                        "preparation_ms" to it.timing.preparationElapsed(now), "travel_ms" to it.timing.elapsed(now),
                        "timeout_ms" to it.timeoutMillis, "storage_reason" to it.storageReason) },
                    "shelter" to mapOf("seeking" to actor.sheltering, "sleeping" to actor.sleeping,
                        "target" to actor.shelter, "search_cursor" to actor.shelterSearchIndex, "sleep_origin" to actor.sleepOrigin?.point()),
                    "courier" to mapOf("held" to actor.courierHeld, "pickup_started_at" to actor.courierPickupStartedAt,
                        "returning_to_warehouse" to actor.returningToWarehouse, "shipment" to actor.plan.courier?.cargo?.id,
                        "source_worker" to actor.plan.courier?.cargo?.sourceWorkerId, "carrying" to actor.plan.courier?.carrying),
                    "combat" to mapOf("attacker" to actor.attacker?.uniqueId, "until" to actor.combatUntil,
                        "status" to actor.combatStatus, "target" to actor.combatDestination),
                    "food" to mapOf("source" to actor.foodSource, "pickup_pending" to (actor.supplyPickup != null),
                        "retry_at" to actor.supplyRetryAt),
                    "social" to mapOf("visit" to (actor.social.visit != null), "arrived_at" to actor.social.visit?.arrivedAt,
                        "partner" to actor.social.visit?.partnerId, "seat" to actor.social.visit?.seat,
                        "conversation_at" to actor.social.visit?.conversationAt))
            })
    }

    /** Observed work admission for the city's active-minute hunger clock. */
    internal fun workingResidentIds(cityId: UUID): Set<UUID> {
        checkMainThread()
        return actors.values.filter { actor ->
            actor.cityId == cityId && actor.npc.isValid && actor.workerId !in pendingHarm && !actor.sheltering && !actor.courierHeld &&
                actor.plan.idleReason in listOf(null, "placing") &&
                actor.cycle.phase !in setOf(CitizenPhase.IDLE, CitizenPhase.PAUSED, CitizenPhase.CLOSED)
        }.mapTo(hashSetOf()) { it.workerId }
    }

    /** Reassignment may retire leisure, never an accepted work, transfer or injury episode. */
    internal fun canReassign(cityId: UUID, workerId: UUID): Boolean {
        checkMainThread()
        if (closed.get() || workerId in pendingHarm) return false
        val actor = actors[workerId] ?: return true
        return actor.cityId == cityId && actor.cycle.phase == CitizenPhase.IDLE &&
            actor.plan.worldWork == null && actor.plan.courier == null && actor.plan.autonomousWork == null &&
            actor.plan.token.constructionId == null && actor.plan.token.craftJobId == null &&
            actor.supplyPickup == null && actor.foodSource == null &&
            !actor.courierHeld && actor.courierPickupStartedAt == null && !actor.returningToWarehouse &&
            actor.collectedPending == null && actor.collectionSeek == null && actor.collectionHome == null &&
            actor.collectionCycle == null && actor.terrainObstructedAt == null
    }

    private fun admissionOrigin(city: Settlement, workerId: UUID, plan: CitizenPlan): CivPosition =
        admissionOrigins[workerId]?.takeIf { it.first == city.id }?.second ?: plan.spawnOrigin

    /** A committed resurrection may prefer its body site; normal admission still proves safe loaded feet. */
    internal fun requestAdmissionAt(cityId: UUID, workerId: UUID, location: Location) {
        checkMainThread()
        if (closed.get() || workerId in actors) return
        val city = cityProvider().firstOrNull { it.id == cityId && it.worldId == location.world?.uid &&
            it.pendingDeletion == null && it.workers.any { worker -> worker.id == workerId } } ?: return
        if (!location.x.isFinite() || !location.y.isFinite() || !location.z.isFinite() ||
            !city.contains(location.blockX, location.blockZ)) return
        admissionOrigins[workerId] = cityId to CivPosition(location.blockX, location.blockY, location.blockZ)
        spawnRetries.remove(workerId)
    }

    /** The UI owns management/reach checks. Moving an existing actor never completes work or a receipt. */
    internal fun summon(city: Settlement, buildingId: UUID): Boolean {
        checkMainThread()
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
        val nextPlan = plan(current, worker) ?: return false
        val npc = actor.npc
        val world = npc.world
        val viewers = viewers()
        val safe: (CivPosition) -> Boolean = { point ->
            citizenStandable(world, point) && citizenPacketBodyClear(world, point.point(), false) &&
                citizenIdleTerrain(world, point, current.buildings) && citizenControlClear(current, point) &&
                citizenRouteCellAllowed(current, setOf(building.id), point.x, point.z) &&
                activeAt(current.id, current.worldId, point.point(), viewers) &&
                point.location(world).let { destination -> residentMoveAllowed(actor, destination, destination) &&
                    actors.values.none { other -> other !== actor && other.npc.isValid && other.worldId == current.worldId &&
                        other.npc.location.distanceSquared(destination) < .8 * .8 } }
        }
        val point = if (building.control != null) citizenStorageTarget(current, building, world, safe)
            else citizenRecoveryTarget(current, building.entrance, safe)
        val destination = point?.location(world) ?: return false
        val previous = npc.location
        destination.yaw = previous.yaw
        stopCamp(actor, "summoned")
        cancelRoute(actor, destination)
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

    /** Rechecked by the item receipt owner after waiting for another city commit. */
    internal fun requireStorageArrival(cityId: UUID, workerId: UUID, buildingId: UUID) {
        checkMainThread()
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
        checkMainThread()
        val actor = actors[workerId]?.takeIf { it.cityId == cityId && it.npc.isValid }
            ?: throw CivsRejection("route-unreachable")
        val city = cityProvider().firstOrNull { it.id == cityId } ?: throw CivsRejection("no-city")
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        val current = citizenPlan(city, worker) ?: throw CivsRejection("stale-delivery")
        val observed = actor.plan
        val expected = observed.token.copy(industryRecipe = token.industryRecipe)
        val currentSchedule = current.token.copy(industryRecipe = token.industryRecipe)
        if (city.autonomy?.paused != false || city.inventory != null || city.pendingDeletion != null ||
            currentSchedule != token || expected != token || observed.autonomousWork == null ||
            observed.autonomousWork != worker.assignment?.let(city::building) || observed.workplace != current.workplace ||
            observed.storage != current.storage || current.idleReason !in listOf(null, "waiting-reassignment", "waiting-orders", "waiting-inputs"))
            throw CivsRejection("stale-delivery")
        if (closed.get() || actor.sheltering || actor.workerId in pendingHarm ||
            actor.cycle.phase != CitizenPhase.DELIVERING || actor.npc.world.uid != city.worldId ||
            !arrivedAt(actor, city, actor.npc.world, current.storage)) throw CivsRejection("route-unreachable")
        return observed
    }

    internal fun acceptedIndustryWork(city: Settlement, worker: CivWorker): CivIndustryWork? {
        checkMainThread()
        val actor = actors[worker.id]?.takeIf { it.cityId == city.id } ?: return null
        val token = actor.plan.token
        return actor.plan.industryWork?.takeIf { token.assignmentId == worker.assignment && token.completedCycles == worker.completedCycles &&
            token.nextDeliveryAt == worker.nextDeliveryAt && it.building == worker.assignment?.let(city::building) }
    }

    /** Research/new native recipes must match the very session observed by this actor before any receipt. */
    internal fun requireSelectedWorkDelivery(cityId: UUID, workerId: UUID, token: CitizenToken) {
        checkMainThread()
        val city = cityProvider().firstOrNull { it.id == cityId } ?: throw CivsRejection("no-city")
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        val actor = actors[workerId]?.takeIf { it.cityId == cityId && it.npc.isValid } ?: throw CivsRejection("route-unreachable")
        val current = citizenPlan(city, worker) ?: throw CivsRejection("stale-delivery")
        if (current.token != token || actor.plan.token != token || current.idleReason != null ||
            actor.plan.workplace != current.workplace || actor.plan.storage != current.storage ||
            actor.sheltering || workerId in pendingHarm || closed.get()) throw CivsRejection("stale-delivery")
        requireStorageArrival(cityId, workerId, requireNotNull(token.assignmentId))
    }

    /** Only observed, safe producers can offer a fixed meeting point; no item is reserved here. */
    internal fun courierSources(cityId: UUID, courierId: UUID): Map<UUID, CivPosition> {
        checkMainThread()
        val city = cityProvider().firstOrNull { it.id == cityId && it.pendingDeletion == null } ?: return emptyMap()
        return city.workers.mapNotNull { worker ->
            val source = actors[worker.id]?.takeIf { it.workerId != courierId && sourceReady(it, city, worker) } ?: return@mapNotNull null
            if (source.courierRetry.exhausted && courierObservedAt >= source.courierRetryAfter) source.courierRetry = CitizenRetry()
            if (!source.courierRetry.ready(courierObservedAt) || actors.values.any { other ->
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
        checkMainThread()
        val actor = actors[workerId]?.takeIf { it.cityId == cityId && it.npc.isValid } ?: return null
        val feet = actor.npc.location
        return CivPosition(feet.blockX, kotlin.math.floor(feet.y + .125).toInt(), feet.blockZ)
    }

    internal fun courierUnavailable(cityId: UUID, workerId: UUID): Boolean {
        checkMainThread()
        val actor = actors[workerId]?.takeIf { it.cityId == cityId } ?: return false
        return !actor.npc.isValid || actor.sleeping || actor.sheltering || actor.attacker != null ||
            workerId in pendingHarm || actor.foodSource != null || actor.supplyPickup != null
    }

    /** One city-wide observation; the dispatcher reserves an offered producer for one courier. */
    internal fun courierSupplySources(cityId: UUID): Map<UUID, CivPosition> {
        checkMainThread()
        val city = cityProvider().firstOrNull { it.id == cityId && it.pendingDeletion == null } ?: return emptyMap()
        return city.workers.mapNotNull { worker ->
            val actor = actors[worker.id]?.takeIf { sourceReady(it, city, worker) } ?: return@mapNotNull null
            if (actor.courierRetry.exhausted && courierObservedAt >= actor.courierRetryAfter) actor.courierRetry = CitizenRetry()
            if (!actor.courierRetry.ready(courierObservedAt)) return@mapNotNull null
            val feet = actor.npc.location
            worker.id to CivPosition(feet.blockX, kotlin.math.floor(feet.y + .125).toInt(), feet.blockZ)
        }.toMap()
    }

    /** Rechecked after a city wait: a barrel arrival can never authorize a remote bag debit. */
    internal fun requireCargoPickupArrival(cityId: UUID, workerId: UUID, cargo: CivCargo) {
        checkMainThread()
        val city = cityProvider().firstOrNull { it.id == cityId } ?: throw CivsRejection("no-city")
        val courier = actors[workerId]?.takeIf { it.cityId == cityId && it.npc.isValid } ?: throw CivsRejection("route-unreachable")
        val sourceId = cargo.sourceWorkerId ?: throw CivsRejection("stale-delivery")
        val source = actors[sourceId] ?: throw CivsRejection("route-unreachable")
        val worker = city.workers.firstOrNull { it.id == sourceId } ?: throw CivsRejection("stale-delivery")
        if (closed.get() || courier.workerId in pendingHarm || courier.cycle.phase != CitizenPhase.PICKING_UP ||
            courier.plan.courier?.cargo?.id != cargo.id || courier.sheltering || courier.attacker != null ||
            worker.assignment != cargo.sourceBuildingId || !sourceReady(source, city, worker) ||
            !citizenNearEndpoint(source.npc.location.point(), cargo.sourceEntrance) || !courierMeetsSource(courier, source, cargo))
            throw CivsRejection("route-unreachable")
    }

    private fun sourceReady(actor: Actor, city: Settlement, worker: CivWorker): Boolean {
        if (actor.cityId != city.id || !actor.npc.isValid || actor.npc.world.uid != city.worldId || actor.sleeping || actor.sheltering ||
            actor.attacker != null || actor.workerId in pendingHarm || actor.supplyPickup != null || actor.foodSource != null ||
            actor.route != null || actor.social.visit != null || actor.npc.world.time in 12_000L..23_000L ||
            actor.cycle.phase in setOf(CitizenPhase.READY, CitizenPhase.DELIVERING, CitizenPhase.PICKING_UP, CitizenPhase.CLOSED)) return false
        val excess = CivsLogistics.workerExcess(city, worker)
        if (excess.isEmpty()) return false
        // Finish useful work while a stack accumulates; idle or blocked producers can hand over a partial stack.
        if (!actor.courierHeld && actor.plan.worldWork != null && actor.cycle.phase != CitizenPhase.PAUSED &&
            excess.values.none { it >= CivsLogistics.MAX_CARGO } &&
            hasCapacity(city, actor.plan)) return false
        val feet = actor.npc.location
        val point = CivPosition(feet.blockX, kotlin.math.floor(feet.y + .125).toInt(), feet.blockZ)
        return citizenChunkReady(actor.npc.world, point.x shr 4, point.z shr 4) &&
            abs(feet.y - point.y) <= .125 && citizenStandable(actor.npc.world, point) &&
            (0..1).none { actor.npc.world.getBlockAt(point.x, point.y + it, point.z).type == Material.LADDER }
    }

    private fun courierMeetsSource(courier: Actor, source: Actor, cargo: CivCargo): Boolean =
        courier.npc.world.uid == source.npc.world.uid && courier.npc.location.distanceSquared(source.npc.location) <= 2.5 * 2.5 &&
            citizenCanReachBlock(courier.npc, cargo.sourceEntrance.copy(y = cargo.sourceEntrance.y + 1))

    private fun releaseCourierSource(courier: Actor, now: Long, success: Boolean = false) {
        if (courier.courierPickupStartedAt == null) return
        courier.courierPickupStartedAt = null
        val source = courier.plan.courier?.cargo?.sourceWorkerId?.let(actors::get) ?: return
        source.courierHeld = false
        source.cycle.deferWork(now)
        if (success) source.courierRetry = CitizenRetry()
        else {
            source.courierRetry.fail(now)
            if (source.courierRetry.exhausted) source.courierRetryAfter = now + 60_000L
        }
    }

    /** The producer waits on its real floor; a failed or expired rendezvous releases it immediately. */
    private fun holdCourierSource(courier: Actor, city: Settlement, now: Long): Boolean {
        val plan = courier.plan.courier?.takeIf { !it.carrying && it.cargo.sourceWorkerId != null } ?: return true
        val source = actors[plan.cargo.sourceWorkerId]
        val worker = city.workers.firstOrNull { it.id == plan.cargo.sourceWorkerId }
        if (courier.cycle.phase == CitizenPhase.PAUSED) { releaseCourierSource(courier, now); return false }
        val fresh = courier.courierPickupStartedAt == null
        val started = courier.courierPickupStartedAt ?: now.also { courier.courierPickupStartedAt = it }
        if (courier.sheltering || courier.attacker != null ||
            courier.npc.world.time in 12_000L..23_000L || now - started >= 90_000L || source == null || worker == null ||
            city.workers.firstOrNull { it.id == courier.workerId }?.assignment != plan.cargo.warehouseId ||
            worker.assignment != plan.cargo.sourceBuildingId || !sourceReady(source, city, worker) ||
            !citizenNearEndpoint(source.npc.location.point(), plan.cargo.sourceEntrance)) {
            releaseCourierSource(courier, now)
            if (courier.cycle.phase != CitizenPhase.PAUSED) pause(courier, "route-unreachable", now)
            return false
        }
        cancelRoute(source)
        source.cycle.deferWork(now)
        source.courierHeld = true
        statuses[source.workerId] = "waiting-courier"
        if (fresh) CivsDiagnostics.event("cargo_rendezvous_started", "city" to city.id, "worker" to courier.workerId,
            "source_worker" to source.workerId, "shipment" to plan.cargo.id, "material" to plan.cargo.material,
            "amount" to plan.cargo.amount, "point" to plan.cargo.sourceEntrance)
        return true
    }

    /** The receipt owner rechecks the same real block after an asynchronous city wait. */
    internal fun requireWorldWorkArrival(cityId: UUID, workerId: UUID, work: CivWorldWork) {
        checkMainThread()
        val actor = actors[workerId]?.takeIf { it.cityId == cityId && it.npc.isValid }
            ?: throw CivsRejection("route-unreachable")
        val city = cityProvider().firstOrNull { it.id == cityId } ?: throw CivsRejection("no-city")
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        if (closed.get() || actor.sheltering || actor.workerId in pendingHarm ||
            actor.cycle.phase != CitizenPhase.DELIVERING || actor.plan.worldWork != work ||
            work.workerId != workerId || worker.assignment != work.buildingId ||
            worker.completedCycles != work.cycle || actor.npc.world.uid != city.worldId ||
            !canWorkFrom(actor, work)) throw CivsRejection("route-unreachable")
    }

    private fun plan(city: Settlement, worker: CivWorker): CitizenPlan? {
        var base = citizenPlan(city, worker, if (CivsResidents.isChild(worker)) null else courierPlan(city, worker)) ?: return null
        if (city.autonomy?.paused == true) return base.copy(idleReason = "autonomy-paused")
        if (base.type == BuildingType.BUILDER_HUT && base.idleReason == "no-construction" && CivsRoadMaintenance.eligible(city)) {
            workPlan(city, worker)?.takeIf { it.upkeep != null }?.let { work ->
                base = base.copy(workplace = work.standing, workMillis = work.durationMillis, idleReason = null,
                    completeAtWorkplace = true, worldWork = work)
            }
        }
        if (city.autonomy != null && base.type == BuildingType.BUILDER_HUT) {
            base.token.constructionId?.let { orderId ->
                constructionWorkplace(city.id, orderId)?.let { base = base.copy(workplace = it) }
            }
        }
        if (city.autonomy == null && base.type == BuildingType.SAWMILL && base.idleReason == null) {
            val job = craftPlan(city, worker)
            base = if (job == null) base.copy(idleReason = "waiting-orders")
                else base.copy(token = base.token.copy(craftJobId = job.id))
        }
        val admitted = if (base.idleReason == null && !productionReady(city, worker)) base.copy(idleReason = "waiting-inputs") else base
        val planned = if (city.autonomy != null && admitted.type != BuildingType.FORESTER || admitted.courier != null ||
            admitted.type !in CivsWorksites.TYPES || admitted.idleReason != null) admitted else {
            val work = workPlan(city, worker)
            if (work == null) base.copy(idleReason = workIdleReason(city, worker)) else base.copy(
                workplace = work.standing, workMillis = work.durationMillis, completeAtWorkplace = true, worldWork = work)
        }
        return planned.copy(workMillis = CivsResidents.workDuration(worker, planned.workMillis),
            suppliesNeeded = !workInputsReady(city, worker))
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
            if (!actor.npc.isValid) return@mapNotNull null
            val city = cityProvider().firstOrNull { it.id == actor.cityId } ?: return@mapNotNull null
            val resident = city.workers.firstOrNull { it.id == actor.workerId } ?: return@mapNotNull null
            val entity = actor.npc
            val at = entity.location
            val type = if (resident.cargo != null) BuildingType.WAREHOUSE else
                resident.assignment?.let(city::building)?.let { CivsRules.effectiveWorkType(city, it) }
            CivsNpcHologramSnapshot(resident.id, at.world!!.uid, at.x, at.y, at.z, resident.name,
                "workers.status.${statuses[resident.id] ?: "waiting"}", CivsResidents.health(resident), CivsResidents.maxHealth(resident),
                actor = entity, profession = if (CivsResidents.isChild(resident)) "child" else type?.let(CivsRules::profession) ?: "unassigned",
                courierCart = citizenCourierCart(actor.plan, actor.cycle.phase, actor.sheltering),
                cargo = resident.cargo?.let { mapOf(it.material to it.amount.toInt()) }.orEmpty(),
                yaw = entity.bodyYaw, nativeHeight = entity.height, hunger = CivsResidents.hunger(resident),
                child = CivsResidents.isChild(resident))
        }
    }

    /** Only a successful canonical reload resolves an injury with an unknown commit outcome. */
    fun reconcileResidents() {
        checkMainThread()
        harmEpoch++
        for (id in pendingHarm) actors[id]?.let { it.cycle = it.plan.cycle() }
        pendingHarm.clear()
        harmCompletions.clear()
    }

    /** One shared timer advances bounded searches and resident movement; no server entity is ticked. */
    fun activateNavigating() {
        checkMainThread()
        if (closed.get() || actors.isEmpty()) return
        navigationTick++
        val now = navigationClock()
        navigationCadenceMillis = if (navigationAt == 0L) 0 else (now - navigationAt).coerceAtLeast(0)
        navigationAt = now
        paths.pulse()
        navigation.pulse()
        threats.tick()
        val viewers = viewers()
        var separationBudget = 4 // Shared by native-overlap exits and at most twelve resident separation probes.
        for (actor in actors.values.toList().sortedBy { if (it.residentYield == null) 1 else 0 }) {
            val npc = actor.npc
            var separationAdmitted = false
            if (separationBudget > 0 && npc.isValid && !actor.sleeping && actor.supplyPickup == null &&
                actor.collectedPending == null &&
                actor.cycle.phase !in setOf(CitizenPhase.PICKING_UP, CitizenPhase.DELIVERING) &&
                activeAt(actor.cityId, actor.worldId, npc.location.point(), viewers)) {
                separationBudget--
                separationAdmitted = true
                citizenRotateAfterAdmission(actors, actor.workerId)
                if (escapeTerrain(actor, now)) continue
                if (advanceResidentYield(actor)) continue
                val separated = if (actor.workerId !in pendingHarm) separateResident(actor) else null
                if (separated == true) {
                    actor.route?.progressTick = navigationTick
                    continue
                }
            }
            if (actor.residentYield != null) {
                // A skipped separation-budget frame is still owned by the bounded yield.
                actor.route?.progressTick = navigationTick
                continue
            }
            val route = actor.route
            if (npc.isValid) {
                val previous = actor.observedYaw
                if (!actor.sleeping && !actor.observedSleeping && previous != null &&
                    abs(citizenAngleChange(previous, npc.bodyYaw)) > 17f)
                    navigationDiagnostic(actor, "abrupt-turn", route?.target ?: npc.location, now)
                val at = npc.location.point()
                if (actor.turnOrigin?.distanceSquared(at)?.let { it > 1.0 } != false || navigationTick - actor.turnSinceTick > 40) {
                    actor.turnOrigin = at
                    actor.turnSinceTick = navigationTick
                    actor.turnAmount = 0f
                } else if (route != null && previous != null && !actor.sleeping) {
                    actor.turnAmount += abs(citizenAngleChange(previous, npc.bodyYaw))
                    if (actor.turnAmount >= 270f) navigationDiagnostic(actor, "turn-loop", route.target, now)
                }
                actor.observedYaw = npc.bodyYaw
                actor.observedSleeping = actor.sleeping
            }
            if (route == null && !actor.sleeping && actor.workerId !in pendingHarm && npc.isValid) {
                actor.lookYaw?.let { citizenFace(npc, it, actor.lookPitch, if (actor.attacker != null) 16f else CITIZEN_TURN_PER_TICK) }
            }
            if (route == null || actor.collectedPending != null || actor.workerId in pendingHarm || !npc.isValid ||
                !activeAt(actor.cityId, actor.worldId, npc.location.point(), viewers) ||
                !route.chunks.ready(npc.world)) {
                actor.footsteps.reset()
                continue
            }
            val city = cityProvider().firstOrNull { it.id == actor.cityId } ?: continue
            if (route.trip?.validFor(city) == false) {
                // Replan from the observed position after a building change; this is not a stuck-route recovery.
                cancelRoute(actor)
                continue
            }
            if (route.motion.searching) {
                if (now >= route.preparationReportAfter) {
                    route.preparationReportAfter = now + 10_000
                    CivsDiagnostics.event("citizen_route_preparing", "city" to actor.cityId,
                        "worker" to actor.workerId, "cycle" to actor.plan.token.completedCycles,
                        "phase" to actor.cycle.phase, "state" to route.motion.preparationPhase,
                        "elapsed_ms" to route.timing.preparationElapsed(now),
                        "path_nodes" to route.motion.nodeCount, "captured_blocks" to route.motion.capturedBlocks,
                        "actual" to npc.location.point().coordinates(), "target" to route.target.point().coordinates())
                }
                continue
            }
            // A player's small push can permit one forward frame without clearing the obstruction.
            // Count only ready navigation frames; capture/search cannot expire this protection.
            if (route.nativeCollisionTicks > 0) route.nativeCollisionTicks--
            route.previousMotion?.let { previous ->
                if (route.motion.failure != null && route.replanReason in setOf("player-blocked", "resident-blocked")) {
                    // A person can seal a narrow corridor. Keep the original route ready
                    // to resume when they leave; a failed detour never licenses teleporting.
                    route.motion.cancel()
                    route.motion = previous
                } else previous.cancel()
                route.previousMotion = null
                route.motionReady(navigationTick, now)
                actor.navigationEpisodeProgress = 0.0
                route.path = null
            }
            val motion = route.motion
            // Stop on the first valid arrival tick, including at any usable side of a barrel.
            // Waiting for the coarse work pulse used to keep walking around the barrel after arrival.
            val travelTarget = actor.combatDestination ?: actor.foodSource?.let(city::building)?.control ?:
                if (actor.returningToWarehouse) actor.plan.storage else when (actor.cycle.phase) {
                CitizenPhase.TO_STORAGE, CitizenPhase.TO_WORK, CitizenPhase.RETURNING -> actor.plan.travelTarget(actor.cycle.phase)
                else -> null
            }
            if (travelTarget != null && !actor.sheltering && actor.collectionSeek == null && actor.collectionHome == null &&
                (actor.returningToWarehouse || actor.plan.idleReason in listOf(null, "placing")) &&
                arrivedAt(actor, city, npc.world, travelTarget)) {
                motion.cancel()
                route.trip?.let { paths.complete(city, it) }
                route.completion = CitizenNavigationCompletion(null)
                continue
            }
            if (!route.planningReported) {
                route.planningReported = true
                // Shared A* admission can wait many frames. Only frames with a ready
                // route are movement opportunities; its first turn is not a stall.
                route.motionReady(navigationTick, now)
                actor.navigationEpisodeProgress = 0.0
                val elapsed = (now - route.startedAt).coerceAtLeast(0)
                if (elapsed >= 1_000 || route.storageReason != null) CivsDiagnostics.event("citizen_route_ready", "city" to actor.cityId,
                    "worker" to actor.workerId, "route_started_at" to route.startedAt,
                    "reason" to route.storageReason, "source" to route.storageBuilding,
                    "phase" to actor.cycle.phase, "state" to "path-ready",
                    "elapsed_ms" to elapsed, "path_nodes" to motion.nodeCount, "failure" to motion.failure,
                    "intermediate" to route.intermediate)
            }
            if (route.path == null) motion.endpoint?.let { route.path = CitizenPathSnapshot(motion.nodeCount, it, false) }
            if (motion.failure != null) {
                if (replanNavigation(actor, route, requireNotNull(motion.failure), now)) continue
                navigationDiagnostic(actor, requireNotNull(motion.failure), route.target, now, terminal = true)
                route.completion = CitizenNavigationCompletion(motion.failure)
                continue
            }
            if (motion.finished) {
                if (route.intermediate) {
                    cancelRoute(actor)
                    startRoute(actor, npc.world, route.authored, now, route.idleAnchor)
                    continue
                }
                route.trip?.let { paths.complete(city, it) }
                route.completion = CitizenNavigationCompletion(null)
                continue
            }
            val before = npc.location
            val working = actor.attacker == null && actor.collectionSeek == null && actor.collectionHome == null &&
                (actor.returningToWarehouse || citizenWorkingTravel(actor.plan, actor.cycle.phase, actor.sheltering))
            val speed = if (actor.attacker != null) 1.1 else if (working)
                .8 * surfaceSpeed(npc.world.getBlockAt(before.blockX, citizenSupportY(before.y), before.blockZ)) else .55
            actor.collisionResident = null
            val next = motion.move(before, speed, { from, to -> routeMoveAllowed(actor, from, to) }) { destination ->
                citizenOpenRouteDoors(city, route.allowedBuildings, before, destination, doorBlocked)
            }
            if (next != null) {
                npc.teleport(next)
                npc.bodyYaw = next.yaw
                npc.headYaw = next.yaw
                npc.pitch = next.pitch
                val forward = (motion.forwardProgress - route.observedProgress).coerceAtLeast(0.0)
                route.observedProgress = motion.forwardProgress
                route.progressSinceObservation += forward
                route.progressSinceReplan += forward
                actor.yieldProgress = minOf(2.0, actor.yieldProgress + forward)
                if (route.progressSinceObservation >= .25) {
                    route.progressSinceObservation = 0.0
                    route.progressTick = navigationTick
                }
                if (route.progressSinceReplan >= 2.0) {
                    route.replanAttempts = 0
                    route.progressSinceReplan = 0.0
                }
                if (mineRouteWork(actor, route) != null) actor.mineRouteEpisode.walked(forward, now)?.let {
                    mineRouteRecovered(actor, it, "walked", now)
                }
                actor.navigationEpisode?.let {
                    actor.navigationEpisodeProgress += forward
                    if (actor.navigationEpisodeProgress >= .5) {
                        CivsDiagnostics.event("citizen_route_progress", "city" to actor.cityId, "worker" to actor.workerId,
                            "action" to "resumed", "reason" to actor.navigationEpisode, "phase" to actor.cycle.phase,
                            "target" to route.target.point().coordinates(), "actual" to next.point().coordinates(),
                            "tick" to Bukkit.getCurrentTick(), "navigation_tick" to navigationTick,
                            "forward_distance" to actor.navigationEpisodeProgress,
                            "episode_ticks" to navigationTick - actor.navigationEpisodeTick,
                            "suppressed" to actor.navigationSuppressed)
                        actor.navigationEpisode = null
                        actor.navigationEpisodeProgress = 0.0
                        actor.navigationSuppressed = 0
                    }
                }
                if (working) {
                    fun grounded(at: Location) =
                        !Tag.CLIMBABLE.isTagged(npc.world.getBlockAt(at.blockX, at.blockY, at.blockZ).type) &&
                            citizenPacketSupported(npc.world, at.point())
                    val beforeGrounded = grounded(before)
                    val nextGrounded = grounded(next)
                    actor.footsteps.prime(before, beforeGrounded)
                    actor.footsteps.observe(next, nextGrounded)?.let { (from, to) ->
                        runCatching { footstep(actor.cityId, actor.workerId, npc, from, to) }
                            .onFailure { logFailure(actor, "footstep", it) }
                    }
                    if (beforeGrounded) route.trip?.observe(before.point())
                    if (nextGrounded) route.trip?.observe(next.point())
                } else actor.footsteps.reset()
            }
            if (motion.failure != null) {
                if (!replanNavigation(actor, route, requireNotNull(motion.failure), now)) {
                    navigationDiagnostic(actor, requireNotNull(motion.failure), route.target, now, terminal = true)
                    route.completion = CitizenNavigationCompletion(motion.failure)
                }
            }
            else if (motion.finished) {
                if (route.intermediate) {
                    cancelRoute(actor)
                    startRoute(actor, npc.world, route.authored, now, route.idleAnchor)
                    continue
                }
                route.trip?.let { paths.complete(city, it) }
                route.completion = CitizenNavigationCompletion(null)
            }
            else if (navigationTick - route.progressTick >= 40) {
                // Two optional walks must not wait forever for each other in a narrow passage.
                // Give up only this leisure leg; work routes keep their existing bounded detour/wait.
                if (motion.waitingForCollision && route.idleAnchor != null && actor.social.visit == null) {
                    cancelRoute(actor)
                    actor.wander.finish(now, false)
                    continue
                }
                val residentBlocked = motion.waitingForCollision && actor.collisionResident != null
                if (residentBlocked && (!separationAdmitted || beginResidentYield(actor))) continue
                val playerBlocked = motion.waitingForCollision || route.nativeCollisionTicks > 0
                val reason = if (residentBlocked) "resident-blocked" else if (playerBlocked) "player-blocked"
                    else if (motion.turning) "turn-loop" else "no-progress"
                if (!replanNavigation(actor, route, reason, now)) {
                    val terminal = !playerBlocked || residentBlocked
                    navigationDiagnostic(actor, reason, route.target, now, terminal = terminal)
                    if (terminal) route.completion = CitizenNavigationCompletion(reason)
                }
            }
        }
        threats.gravity(actors.values.filter { it.sleeping || it.route?.motion?.navigating == true }.mapTo(hashSetOf()) { it.workerId })
    }

    private fun update(actor: Actor, city: Settlement, world: World, now: Long) {
        val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: return
        if (actor.terrainObstructedAt != null) {
            actor.cycle.deferWork(now)
            statuses[actor.workerId] = "route-unreachable"
            return
        }
        if (actor.sleeping && !sleepStillSafe(actor)) {
            // Changed support/headroom must be handled before work, hunger or another route.
            // A clear but unsupported origin wakes in place and falls on the normal gravity tick.
            if (wake(actor)) { actor.shelter = null; actor.shelterPose = null }
            statuses[actor.workerId] = "sheltering"
            return
        }
        if (actor.supplyPickup != null) { statuses[actor.workerId] = "loading-cargo"; return }
        if (combat(actor, city, worker, world, now)) {
            actor.mineRouteEpisode.retain(null)
            releaseCourierSource(actor, now)
            return
        }
        val cycle = actor.cycle
        if (actor.collectedPending != null) { statuses[actor.workerId] = "loading-cargo"; return }
        if (supplyFood(actor, city, worker, world, now)) {
            actor.mineRouteEpisode.retain(null)
            releaseCourierSource(actor, now)
            return
        }
        if (world.time in 12_000L..23_000L && cycle.phase !in setOf(CitizenPhase.DELIVERING, CitizenPhase.PICKING_UP)) {
            if (actor.sheltering && actor.residentYield != null) { cycle.deferWork(now); return }
            if (collectItems(actor, city, worker, world, now, night = true)) return
            actor.mineRouteEpisode.retain(null)
            releaseCourierSource(actor, now)
            stopCamp(actor, "night")
            shelter(actor, city, worker, world, now)
            return
        }
        if (actor.residentYield != null) { cycle.deferWork(now); return }
        if (!holdCourierSource(actor, city, now)) return
        if (actor.courierHeld) {
            val waitingCourier = actors.values.any { it.courierPickupStartedAt != null && it.plan.courier?.let { plan ->
                !plan.carrying && plan.cargo.sourceWorkerId == actor.workerId
            } == true }
            actor.cycle.deferWork(now)
            if (waitingCourier) { statuses[actor.workerId] = "waiting-courier"; return }
            actor.courierHeld = false
        }
        if (actor.sheltering) {
            cancelRoute(actor)
            actor.sheltering = false
            actor.shelter = null
            actor.shelterPose = null
            actor.cycle.close()
            actor.cycle = actor.plan.cycle()
            return
        }
        if (CivsResidents.isChild(worker)) {
            stopCamp(actor, "child-playing")
            wander(actor, city, world, now)
            statuses[actor.workerId] = "playing"
            if (actor.route == null && now >= actor.nextPlayAt) {
                actor.npc.swingMainHand()
                val delay = 6_000L + Math.floorMod(actor.workerId.hashCode(), 4_001)
                actor.nextPlayAt = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
            }
            return
        }
        if (collectItems(actor, city, worker, world, now)) return
        if (returnToWarehouse(actor, city, world, now)) return
        if (citizenCampEligible(actor.plan, cycle.phase, world.time)) {
            if (gather(actor, city, worker, world, now)) return
        } else stopCamp(actor, "work-priority")
        actor.plan.idleReason?.let {
            if (it != "placing") {
                if (it == "searching-work") {
                    // A committed block invalidates the worksite cache before its bounded
                    // next-job search finishes. Returning to the surface in this gap made
                    // miners climb up after every block, then reverse for the next job.
                    cancelRoute(actor)
                    statuses[actor.workerId] = workActivityStatus(actor, actor.plan) ?: it
                    return
                }
                if (actor.plan.type == BuildingType.BUILDER_HUT && it == "waiting-materials") {
                    travelForSupplies(actor, city, world, actor.plan.storage, now)
                    return
                }
                if (it !in setOf("waiting-materials", "construction-paused")) wander(actor, city, world, now)
                statuses[actor.workerId] = it
                animateWork(actor, city, world, now)
                speak(actor, CitizenVoiceEvent.IDLE)
                return
            }
        }
        if (cycle.reason == "storage-full" && actor.plan.courier == null) {
            if (!hasCapacity(city, actor.plan)) {
                wander(actor, city, world, now)
                statuses[actor.workerId] = "storage-full"
                return
            }
            // Leisure owns its route while production waits. Resume the saved work only
            // after retiring that route, and walk back before submitting a completed receipt.
            cancelRoute(actor)
            cycle.resumeAfterWander()
        }
        cycle.retry(now)
        if (cycle.phase == CitizenPhase.IDLE) {
            if (!hasCapacity(city, actor.plan)) cycle.pause("storage-full", now, false)
            else cycle.start()
        }
        when (cycle.phase) {
            CitizenPhase.TO_STORAGE, CitizenPhase.TO_WORK, CitizenPhase.RETURNING -> {
                val target = actor.plan.travelTarget(cycle.phase)
                if (arrivedAt(actor, city, world, target)) {
                    arriveForWork(actor, city, worker, now)
                    statuses[actor.workerId] = citizenWorkStatus(actor.plan, actor.cycle.phase) ?: actor.cycle.status()
                    // Arrival starts the observed work interval on this pulse. Its first
                    // gesture and crop-care prop belong to that same interval.
                    if (actor.cycle.phase == CitizenPhase.WORKING) animateWork(actor, city, world, now)
                    return
                }
                if (actor.route == null) startRoute(actor, world, target, now)
                val route = actor.route
                if (route != null) {
                    if (!route.chunks.ready(world)) {
                        remove(actor, "chunk-unloaded")
                        return
                    } else {
                        if (route.completion == null && route.path == null) observePath(route)
                        val outcome = citizenNavigationOutcome(route.completion,
                            arrivedAt(actor, city, world, target),
                            route.timing.elapsed(now), route.timeoutMillis, route.motion.navigating, route.motion.searching)
                        when (outcome) {
                            CitizenNavigationOutcome.PENDING -> Unit
                            CitizenNavigationOutcome.ARRIVED -> {
                                route.trip?.let { paths.complete(city, it) }
                                arriveForWork(actor, city, worker, now)
                            }
                            else -> {
                                pauseNavigation(actor, "route-unreachable", outcome.name.lowercase(), route.target, now)
                                if (actor.cycle !== cycle) return
                            }
                        }
                    }
                }
            }
            CitizenPhase.WORKING -> {
                val target = requireNotNull(actor.plan.workplace)
                if (!workInputsReady(city, worker)) {
                    cancelRoute(actor)
                    actor.cycle = actor.plan.copy(suppliesNeeded = true).cycle()
                    actor.cycle.start()
                } else if (!arrivedAt(actor, city, world, target))
                    pauseNavigation(actor, "route-unreachable", "left-workplace", resolvedTarget(actor, target).location(world), now)
                else if (actor.plan.type == BuildingType.GUARD_TOWER) cycle.deferWork(now)
                else if (actor.plan.idleReason != "placing") {
                    cycle.work(now)
                    if (cycle.phase == CitizenPhase.READY) submitReadyDelivery(actor, city, world, now)
                }
            }
            CitizenPhase.PICKUP_READY -> {
                val target = requireNotNull(actor.plan.workplace)
                if (!arrivedAt(actor, city, world, target))
                    pauseNavigation(actor, "route-unreachable", "left-pickup-point", resolvedTarget(actor, target).location(world), now)
                else cycle.beginPickup()?.let { ticket -> submitPickup(actor, ticket) }
            }
            CitizenPhase.READY -> submitReadyDelivery(actor, city, world, now)
            else -> Unit
        }
        if (actors[actor.workerId] === actor) {
            statuses[actor.workerId] = if (navigationFailed(actor))
                "route-unreachable" else workActivityStatus(actor, actor.plan) ?: if (actor.plan.idleReason == "placing" && cycle.phase == CitizenPhase.WORKING &&
                actor.plan.token.constructionId?.let { constructionBuilder(actor, city, it) } != null) "placing"
                else citizenWorkStatus(actor.plan, cycle.phase) ?: cycle.status()
            animateWork(actor, city, world, now)
        }
    }

    private fun defend(workerId: UUID, monster: Monster) {
        val victim = actors[workerId] ?: return
        if (immortalResidents(victim.cityId)) return
        val now = System.currentTimeMillis()
        for (actor in actors.values) {
            if (actor.cityId != victim.cityId || !actor.npc.isValid || actor.npc.world != monster.world ||
                actor.npc.location.distanceSquared(victim.npc.location) > 10.0 * 10.0) continue
            if (actor.attacker?.uniqueId != monster.uniqueId) {
                if (actor.attacker?.let { it.isValid && !it.isDead } == true) continue
                cancelRoute(actor)
                stopCamp(actor, "monster")
                actor.sheltering = false
                actor.shelter = null
                actor.shelterPose = null
                actor.cycle = actor.plan.cycle()
                actor.attacker = monster
                actor.combatDestination = null
                actor.combatRouteAfter = 0
            }
            actor.combatUntil = now + 10_000
        }
    }

    private fun combat(actor: Actor, city: Settlement, worker: CivWorker, world: World, now: Long): Boolean {
        val monster = actor.attacker ?: return false
        if (immortalResidents(city.id) || !monster.isValid || monster.isDead || monster.world != world || now > actor.combatUntil ||
            monster.location.distanceSquared(actor.npc.location) > 24.0 * 24.0) {
            cancelRoute(actor)
            actor.attacker = null
            actor.combatUntil = 0
            actor.combatDestination = null
            actor.combatStatus = null
            actor.combatRouteAfter = 0
            actor.combatAttackAfter = 0
            actor.cycle = actor.plan.cycle()
            return false
        }
        val retreat = citizenRetreats(worker)
        actor.combatStatus = if (retreat) "retreating" else "defending"
        statuses[actor.workerId] = requireNotNull(actor.combatStatus)
        val at = actor.npc.location
        val foe = monster.location
        if (!retreat && at.distanceSquared(foe) <= 2.5 * 2.5 &&
            threats.visible(at.clone().add(0.0, actor.npc.eyeHeight, 0.0), monster.eyeLocation)) {
            cancelRoute(actor)
            actor.combatDestination = null
            citizenLookDirection(at.clone().add(0.0, actor.npc.eyeHeight, 0.0), monster.eyeLocation)
                .let { (yaw, pitch) -> look(actor.npc, yaw, pitch) }
            if (now >= actor.combatAttackAfter) {
                actor.combatAttackAfter = now + 1_000
                actor.npc.swingMainHand()
                monster.damage(citizenAttackDamage(CivsEquipment.slots(worker)[4]?.stack()))
            }
            return true
        }
        if (now < actor.combatRouteAfter || wanderBudget == 0) return true
        if (actor.route?.let { it.motion.navigating && !it.timedOut(now) } == true && actor.combatDestination?.let {
                retreat || it.point().distanceSquared(foe.point()) <= 3.0 * 3.0
            } == true) return true
        wanderBudget--
        actor.combatRouteAfter = now + 1_000
        cancelRoute(actor)
        val direction = at.toVector().subtract(foe.toVector()).setY(0.0)
        if (direction.lengthSquared() < .01) direction.x = 1.0
        direction.normalize()
        val points = if (retreat) listOf(6.0, 4.0, 8.0).flatMap { distance ->
            listOf(0.0, .8, -.8).map { turn -> at.clone().add(direction.clone().rotateAroundY(turn).multiply(distance)) }
        } else listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).map { (x, z) -> foe.clone().add(x.toDouble(), 0.0, z.toDouble()) }
        val destination = points.asSequence().flatMap { point -> sequenceOf(0, 1, -1).map { dy ->
            CivPosition(point.blockX, point.blockY + dy, point.blockZ)
        } }.firstOrNull { point -> citizenStandable(world, point) && citizenControlClear(city, point) &&
            citizenPacketBodyClear(world, point.point(), false) && citizenPacketCampfireClear(world, point.point()) &&
            citizenRouteCellAllowed(city, citizenRouteBuildings(city, actor.plan, point,
                CivPosition(at.blockX, at.blockY, at.blockZ)), point.x, point.z) }
        if (destination != null) {
            actor.combatDestination = destination
            startRoute(actor, world, destination, now)
        }
        return true
    }

    private fun arriveForWork(actor: Actor, city: Settlement, worker: CivWorker, now: Long) {
        actor.route?.takeIf { it.motion.forwardProgress > 0.0 }?.let { route ->
            val actual = actor.npc.location.point()
            CivsDiagnostics.event("citizen_route_arrived", "city" to city.id, "worker" to actor.workerId,
                "mode" to "walked", "phase" to actor.cycle.phase, "cycle" to worker.completedCycles,
                "from" to route.from.coordinates(), "actual" to actual.coordinates(), "target" to route.target.point().coordinates(),
                "path_progress" to route.motion.forwardProgress, "displacement" to sqrt(route.from.distanceSquared(actual)),
                "distance" to sqrt(actual.distanceSquared(route.target.point())),
                "elapsed_ms" to now - route.startedAt)
        }
        cancelRoute(actor)
        if (actor.cycle.phase == CitizenPhase.TO_STORAGE && city.inventory != null && actor.plan.courier == null &&
            actor.plan.type !in CivsWorksites.TYPES &&
            (actor.plan.type == BuildingType.BUILDER_HUT || !workInputsReady(city, worker))) {
            val source = storageForTarget(actor, city, actor.plan.storage) ?: return
            beginSupplies(actor, source.id, now, resumeWork = true)
        } else if (actor.cycle.phase != CitizenPhase.TO_WORK || workInputsReady(city, worker)) actor.cycle.arrive(now)
        else {
            actor.cycle = actor.plan.copy(suppliesNeeded = true).cycle()
            actor.cycle.start()
        }
    }

    private fun workActivityStatus(actor: Actor, plan: CitizenPlan): String? = citizenWorkActivityStatus(plan, actor.cycle.phase,
        actor.npc.location.point(), actor.workHandoff, navigationTick)

    private fun navigationFailed(actor: Actor): Boolean = actor.route?.let {
        it.motion.failure != null || it.completion?.cancelReason != null ||
            it.motion.navigating && !it.motion.searching && navigationTick - it.progressTick >= 40
    } == true

    /** Food is collected at the hall without replacing a cargo/work identity. */
    private fun supplyFood(actor: Actor, city: Settlement, worker: CivWorker, world: World, now: Long): Boolean {
        if (actor.cycle.phase in setOf(CitizenPhase.PICKING_UP, CitizenPhase.DELIVERING) || now < actor.supplyRetryAt) return false
        val source = residentSupplySource(city, worker)
        if (source == null) {
            if (actor.foodSource != null) { cancelRoute(actor); actor.foodSource = null }
            return false
        }
        val control = city.building(source)?.control ?: return false
        if (actor.foodSource != source) { cancelRoute(actor); stopCamp(actor, "food"); actor.foodSource = source }
        travelForSupplies(actor, city, world, control, now, resumeWork = false)
        return true
    }

    private fun travelForSupplies(actor: Actor, city: Settlement, world: World, target: CivPosition, now: Long,
                                  resumeWork: Boolean = false) {
        statuses[actor.workerId] = "moving-to-storage"
        if (now < actor.supplyRetryAt) { statuses[actor.workerId] = "waiting-materials"; return }
        if (arrivedAt(actor, city, world, target)) {
            cancelRoute(actor)
            citizenControlBuilding(city, target)?.let { beginSupplies(actor, it.id, now, resumeWork) }
            return
        }
        if (actor.route == null) startRoute(actor, world, target, now)
        actor.route?.let { route ->
            if (route.completion != null || route.timedOut(now)) {
                // A supply detour may retry normally, but never teleport into work from a food trip.
                cancelRoute(actor)
                actor.supplyRetryAt = now + 1_000
            }
        }
    }

    private fun beginSupplies(actor: Actor, buildingId: UUID, now: Long, resumeWork: Boolean) {
        if (actor.supplyPickup != null || now < actor.supplyRetryAt) return
        val pickup = SupplyPickup(UUID.randomUUID(), buildingId, resumeWork)
        actor.supplyPickup = pickup
        val future = try {
            val job = actor.plan.token.craftJobId.takeIf { buildingId == actor.plan.token.assignmentId }
            if (job == null) prepareSupplies(actor.cityId, actor.workerId, buildingId)
            else craftSupplies(actor.cityId, actor.workerId, buildingId, job)
        }
            catch (error: Exception) { CompletableFuture.failedFuture(error) }
        if (!future.isDone && actor.plan.type == BuildingType.BUILDER_HUT) CivsDiagnostics.event("citizen_supplies_pending", "city" to actor.cityId,
            "worker" to actor.workerId, "source" to buildingId, "phase" to actor.cycle.phase,
            "order" to actor.plan.token.constructionId, "state" to "operation-pending")
        future.whenComplete { city, error ->
            if (!closed.get()) supplyCompletions.add(SupplyCompletion(actor.workerId, actor.instanceId, pickup, city, error))
        }
    }

    private fun drainSupplies(now: Long) {
        while (true) {
            val completion = supplyCompletions.poll() ?: return
            val actor = actors[completion.workerId]?.takeIf {
                it.instanceId == completion.actorId && it.supplyPickup == completion.pickup
            } ?: continue
            actor.supplyPickup = null
            val error = (completion.error as? CompletionException)?.cause ?: completion.error
            if (error != null) {
                actor.supplyRetryAt = now + 1_000
                val reason = CivsDiagnostics.rejection(error)?.reason ?: "unexpected-failure"
                val failure = completion.pickup.buildingId to reason
                if (actor.plan.type == BuildingType.BUILDER_HUT && actor.supplyFailure != failure) CivsDiagnostics.event("citizen_supplies_blocked",
                    "city" to actor.cityId, "worker" to actor.workerId, "source" to completion.pickup.buildingId,
                    "order" to actor.plan.token.constructionId, "phase" to actor.cycle.phase, "reason" to reason)
                actor.supplyFailure = failure
                if (error !is CivsRejection) logFailure(actor, "supplies", error)
            } else {
                actor.supplyFailure = null
                actor.foodSource = null
                val city = completion.city ?: continue
                val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: continue
                if (completion.pickup.resumeWork && workInputsReady(city, worker)) actor.cycle.arrive(now)
                actor.equipmentItems = null
            }
        }
    }

    /** Seeking is leisure; a useful work action, cargo, combat and night shelter retain their priorities. */
    private fun collectItems(actor: Actor, city: Settlement, worker: CivWorker, world: World, now: Long,
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
            if (actor.collectionHome != null) { cancelRoute(actor); actor.collectionHome = null }
        } else if (actor.collectionCycle == null) actor.collectionCycle = worker.completedCycles
        val idle = citizenCollectionIdle(actor.plan, actor.cycle.phase)
        val shouldGoHome = worker.hasCollectedItems == true &&
            (night || idle || worker.completedCycles > requireNotNull(actor.collectionCycle))
        if (night || !idle && !shouldGoHome) clearCollectedRoute(actor)
        if (now < actor.collectionRetryAt || !collectionAvailable(city.id) || collectionBudget == 0) {
            // An admitted leisure route retains its identity while another resident uses the native budget.
            if (actor.collectionSeek != null || actor.collectionHome != null) {
                statuses[worker.id] = if (actor.collectionHome != null) "moving-to-storage" else "walking"
                return true
            }
            return false
        }
        collectionBudget--
        citizenRotateAfterAdmission(actors, actor.workerId)
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
                stopCamp(actor, "home-deposit")
                cancelRoute(actor)
                actor.collectionHome = target
                beginCollected(actor, now) { depositCollected(city.id, worker.id, target) }
                return true
            }
            if (night) return false
            val building = city.building(home.homeId) ?: return false
            val standing = citizenCollectedHomeTarget(city, building, world, target)
            if (standing == null) { actor.collectionRetryAt = now + 5_000; return false }
            stopCamp(actor, "home-return")
            if (actor.collectionHome != target || actor.route?.authored != standing) cancelRoute(actor)
            actor.collectionSeek = null
            actor.collectionHome = target
            if (actor.route == null) startRoute(actor, world, standing, now)
            val route = actor.route
            if (route == null || route.completion != null || route.timedOut(now)) {
                cancelRoute(actor)
                actor.collectionHome = null
                actor.collectionRetryAt = now + 5_000
                return false
            }
            statuses[worker.id] = "moving-to-storage"
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
            if (actor.collectionSeek != null) cancelRoute(actor)
            stopCamp(actor, "ground-pickup")
            actor.collectionSeek = null
            actor.collectionCycle = worker.completedCycles
            beginCollected(actor, now) { pickupGround(city.id, worker.id, reachable.map { it.uniqueId }) }
            return true
        }
        if (!idle) return false
        val item = nearby.firstOrNull { civsCanCollect(origin, it, worker.id, 8.0) }
        if (item == null) {
            if (actor.collectionSeek != null) cancelRoute(actor)
            actor.collectionSeek = null
            actor.collectionRetryAt = now + 2_000
            return false
        }
        val standing = citizenCollectedItemTarget(city, world, item.location)
        if (standing == null) { actor.collectionRetryAt = now + 5_000; return false }
        stopCamp(actor, "ground-item")
        if (actor.collectionSeek != item.uniqueId || actor.route?.authored != standing) cancelRoute(actor)
        actor.collectionSeek = item.uniqueId
        if (actor.route == null) startRoute(actor, world, standing, now)
        val route = actor.route
        if (route == null || route.completion != null || route.timedOut(now)) {
            cancelRoute(actor)
            actor.collectionSeek = null
            actor.collectionRetryAt = now + 5_000
            return false
        }
        statuses[worker.id] = "walking"
        return true
    }

    private fun clearCollectedRoute(actor: Actor) {
        if (actor.collectionSeek != null || actor.collectionHome != null) cancelRoute(actor)
        actor.collectionSeek = null
        actor.collectionHome = null
    }

    private fun beginCollected(actor: Actor, now: Long, operation: () -> CompletableFuture<Settlement>) {
        val ticket = UUID.randomUUID()
        actor.collectedPending = ticket
        actor.collectionRetryAt = now + 5_000
        statuses[actor.workerId] = "loading-cargo"
        val future = try { operation() } catch (failure: Exception) { CompletableFuture.failedFuture(failure) }
        future.whenComplete { city, failure ->
            if (!closed.get()) collectedCompletions.add(CollectedCompletion(actor.workerId, actor.instanceId, ticket, city, failure))
        }
    }

    private fun drainCollected(now: Long) {
        while (true) {
            val completion = collectedCompletions.poll() ?: return
            val actor = actors[completion.workerId]?.takeIf {
                it.instanceId == completion.actorId && it.collectedPending == completion.ticket
            } ?: continue
            actor.collectedPending = null
            actor.collectionRetryAt = now + 5_000
            if (actor.collectionHome != null) cancelRoute(actor)
            actor.collectionSeek = null
            actor.collectionHome = null
            actor.equipmentItems = null
            val error = (completion.error as? CompletionException)?.cause ?: completion.error
            if (error != null && error !is CivsRejection) logFailure(actor, "collected-items", error)
        }
    }

    private fun submitReadyDelivery(actor: Actor, city: Settlement, world: World, now: Long) {
        val target = actor.plan.travelTarget(CitizenPhase.RETURNING)
        if (!arrivedAt(actor, city, world, target)) {
            pauseNavigation(actor, "route-unreachable", "left-delivery-point", resolvedTarget(actor, target).location(world), now)
        } else if (!hasCapacity(city, actor.plan)) actor.cycle.pause("storage-full", now, true)
        else actor.cycle.beginDelivery(now)?.let { ticket -> submitDelivery(actor, ticket, now) }
    }

    private fun animateWork(actor: Actor, city: Settlement, world: World, now: Long) {
        if (actor.plan.type == BuildingType.BUILDER_HUT && actor.plan.worldWork?.upkeep == null) {
            val order = city.construction ?: return
            val target = constructionFocus(city.id, order.id) ?: return
            constructionBuilder(actor, city, order.id)?.let { lookAtBlock(it, target) }
            return
        }
        val working = citizenWorking(actor.plan, actor.cycle.phase)
        if (!working || (actor.route?.motion?.navigating == true)) { fieldCareHand(actor, null); return }
        val workplace = actor.plan.workplace ?: return
        if (!arrivedAt(actor, city, world, workplace)) { fieldCareHand(actor, null); return }
        val player = actor.npc
        val eye = player.location.clone().add(0.0, player.eyeHeight, 0.0)
        val workFocus = actor.plan.worldWork?.let(::citizenWorkFocus)
        val focus = if (workFocus != null) citizenReachableBlockPoint(world, eye, workFocus, citizenWorkReach(actor.plan.type)) ?: return
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
        focus?.let { citizenLookDirection(eye, it).let { (yaw, pitch) -> look(player, yaw, pitch) } }
        player.swingMainHand()
        speak(actor, CitizenVoiceEvent.WORK)
    }

    private fun fieldCareHand(actor: Actor, material: Material?) {
        if (actor.careItem == material) return
        actor.careItem = material
        if (!actor.npc.isValid) return
        val hand = material?.let(::ItemStack) ?: actor.equipmentItems?.getOrNull(4)?.stack()
            ?: ItemStack(citizenTool(actor.plan) ?: Material.AIR)
        actor.npc.setEquipment(EquipmentSlot.HAND, hand)
    }

    /** One small contact cue after the durable receipt; no looping particles or world mutation. */
    private fun fieldCareCompleted(actor: Actor) {
        val material = citizenFieldCareItem(actor.plan) ?: return
        val work = actor.plan.worldWork ?: return
        val focus = citizenWorkFocus(work) ?: return
        val npc = actor.npc
        if (!npc.isValid || npc.world.uid != actor.worldId || actor.sheltering || actor.sleeping || actor.workerId in pendingHarm ||
            (actor.route?.motion?.navigating == true) || !canWorkFrom(actor, work)) return
        val watering = material == Material.WATER_BUCKET
        val at = Location(npc.world, focus.x + .5, focus.y + if (watering) .18 else .35, focus.z + .5)
        npc.world.spawnParticle(if (watering) Particle.SPLASH else Particle.HAPPY_VILLAGER,
            at, if (watering) 8 else 6, .22, if (watering) .08 else .18, .22, if (watering) .015 else 0.0)
        npc.world.playSound(at, if (watering) Sound.ITEM_BUCKET_EMPTY else Sound.ITEM_BONE_MEAL_USE,
            SoundCategory.BLOCKS, .35f, 1.1f)
    }

    /** The native paste waits here while its observed builder is absent, travelling, hurt or sheltering. */
    internal fun constructionReady(cityId: UUID, orderId: UUID): Boolean {
        checkMainThread()
        if (closed.get()) return false
        val city = cityProvider().firstOrNull { it.id == cityId } ?: return false
        return actors.values.any { constructionBuilder(it, city, orderId) != null }
    }

    internal fun requireConstructionArrival(cityId: UUID, workerId: UUID, orderId: UUID) {
        checkMainThread()
        val city = cityProvider().firstOrNull { it.id == cityId } ?: throw CivsRejection("no-city")
        val actor = actors[workerId] ?: throw CivsRejection("route-unreachable")
        if (actor.cycle.phase != CitizenPhase.DELIVERING ||
            constructionBuilder(actor, city, orderId, requirePlacement = false) == null) throw CivsRejection("route-unreachable")
    }

    /** Cosmetic equipment is never inserted into the resident's durable inventory. */
    internal fun constructionPlaced(cityId: UUID, orderId: UUID, position: CivPosition, material: Material) {
        checkMainThread()
        if (closed.get()) return
        val city = cityProvider().firstOrNull { it.id == cityId } ?: return
        paths.invalidateBlock(city.worldId, position)
        if (material.isAir) return
        for (actor in actors.values.sortedBy { it.workerId }) {
            val player = constructionBuilder(actor, city, orderId) ?: continue
            actor.npc.setEquipment(EquipmentSlot.HAND, ItemStack(citizenPlacementItem(material) ?: Material.AIR))
            lookAtBlock(player, position)
            player.swingMainHand()
            speak(actor, CitizenVoiceEvent.WORK)
            break
        }
    }

    private fun constructionBuilder(actor: Actor, city: Settlement, orderId: UUID, requirePlacement: Boolean = true): PacketPlayerNpc? {
        val order = city.construction ?: return null
        val resident = city.workers.firstOrNull { it.id == actor.workerId } ?: return null
        val assignment = resident.assignment?.let(city::building) ?: return null
        if (city.autonomy != null) {
            val currentWorkplace = constructionWorkplace(city.id, orderId) ?: citizenPlan(city, resident)?.workplace
            // A saved terrain batch may advance before the next resident pulse updates its actor.
            if (actor.plan.workplace != currentWorkplace) return null
        }
        if (actor.cityId != city.id || order.id != orderId || resident.assignment != actor.plan.token.assignmentId ||
            !CivsRules.isBuilder(city, assignment) || !CivsRules.builderCanWork(city, assignment) ||
            requirePlacement && order.phase !in setOf(ConstructionPhase.READY_TO_PLACE, ConstructionPhase.PLACING) ||
            !citizenConstructionWorking(actor.plan, actor.cycle.phase, orderId) ||
            actor.sheltering || actor.workerId in pendingHarm || !actor.npc.isValid || (actor.route?.motion?.navigating == true)) return null
        val player = actor.npc
        if (player.world.uid != city.worldId || player.world.time in 12_000L..23_000L ||
            !citizenChunkReady(player.world, player.location.blockX shr 4, player.location.blockZ shr 4) ||
            !activeAt(city.id, city.worldId, player.location.point(), viewers())) return null
        return player.takeIf { actor.plan.workplace?.let { arrivedAt(actor, city, player.world, it) } == true }
    }

    private fun lookAtBlock(player: PacketPlayerNpc, point: CivPosition) {
        val focus = Location(player.world, point.x + 0.5, point.y + 0.5, point.z + 0.5)
        citizenLookDirection(player.location.clone().add(0.0, player.eyeHeight, 0.0), focus).let { (yaw, pitch) -> look(player, yaw, pitch) }
    }

    private fun speak(actor: Actor, event: CitizenVoiceEvent) {
        if (actor.sheltering || actor.sleeping || actor.workerId in pendingHarm ||
            !actor.npc.isValid || (actor.route?.motion?.navigating == true)) return
        val player = actor.npc
        val resident = cityProvider().firstOrNull { it.id == actor.cityId }?.workers?.firstOrNull { it.id == actor.workerId }
        runCatching { voices.say(actor.cityId, actor.workerId, player, event, Bukkit.getCurrentTick(), plugin.server.onlinePlayers,
            resident?.let(CivsResidents::sex)) }
            .onFailure { logFailure(actor, "voice", it) }
    }

    private fun stopCamp(actor: Actor, reason: String = "interrupted") {
        val visit = actor.social.visit ?: return
        actor.social.finish(Bukkit.getCurrentTick())
        CivsDiagnostics.event(if (visit.partnerId == null) "citizen_campfire" else "citizen_meeting",
            "city" to actor.cityId, "worker" to actor.workerId,
            "action" to "end", "reason" to reason, "fire" to visit.fire?.position, "partner" to visit.partnerId, "seat" to visit.seat,
            "actual" to actor.npc.takeIf { it.isValid }?.location?.point()?.coordinates())
        cancelRoute(actor)
        if (visit.partnerId != null) {
            actor.wander.finish(courierObservedAt, true)
            actors[visit.partnerId]?.takeIf { it.social.visit?.partnerId == actor.workerId }
                ?.let { stopCamp(it, "partner-$reason") }
        }
    }

    private fun gather(actor: Actor, city: Settlement, worker: CivWorker, world: World, now: Long): Boolean {
        val tick = Bukkit.getCurrentTick()
        val routine = actor.social
        var visit = routine.visit
        if (visit == null) {
            val evening = actor.plan.idleReason == null
            if (actor.route != null || campBudget == 0 || !routine.available(tick, world.fullTime / 24_000, evening)) return false
            campBudget--
            citizenRotateAfterAdmission(actors, actor.workerId)
            val origin = citizenIdleBuilding(city, worker) ?: return false
            val anchor = citizenIdleAnchor(city, origin)
            val allowed = city.buildings.filter { it.id == origin.id || it.type == BuildingType.TOWN_HALL }.mapTo(hashSetOf()) { it.id }
            val place = campfires.nearby(world, city, anchor, tick).asSequence().take(4).filter { fire ->
                citizenRouteCellAllowed(city, allowed, fire.position.x, fire.position.z) &&
                    citizenCampfireLit(world, fire.position) &&
                    actors.values.count { it.social.visit?.fire == fire } < 6
            }.mapNotNull { fire -> citizenCampSeats(fire.position, actor.workerId).firstOrNull { point ->
                citizenRouteCellAllowed(city, allowed, point.x, point.z) && citizenControlClear(city, point) &&
                    actors.values.none { other -> other !== actor && other.worldId == city.worldId &&
                        (other.social.visit?.seat?.let { it.x == point.x && it.z == point.z } == true ||
                            other.npc.isValid && other.npc.location.point().distanceSquared(point.point()) < 1.4 * 1.4) } &&
                    citizenIdleTerrain(world, point, city.buildings) && citizenStandable(world, point) &&
                    citizenCampfireVisible(world, point, fire.position)
            }?.let { fire to it } }.firstOrNull()
            if (place == null) {
                if (startMeeting(actor, city, worker, world, now, tick))
                    return continueMeeting(actor, city, world, tick)
                routine.discoveryMiss(tick)
                return false
            }
            val (fire, seat) = place
            routine.start(fire, seat, tick, world.fullTime / 24_000, evening)
            visit = requireNotNull(routine.visit)
            CivsDiagnostics.event("citizen_campfire", "city" to actor.cityId, "worker" to actor.workerId,
                "action" to "start", "fire" to fire.position, "seat" to seat, "evening" to evening)
            if (!startRoute(actor, world, seat, now, idleAnchor = fire.position)) {
                stopCamp(actor, "route-unavailable")
                return false
            }
        }
        val activeVisit = requireNotNull(visit)
        if (activeVisit.partnerId != null) return continueMeeting(actor, city, world, tick)
        val fire = requireNotNull(activeVisit.fire)
        if (routine.expired(tick) || !citizenCampfireLit(world, fire.position) ||
            !citizenIdleTerrain(world, activeVisit.seat, city.buildings)) {
            stopCamp(actor, if (routine.expired(tick)) {
                if (activeVisit.arrivedAt == null) "travel-timeout" else "rest-finished"
            } else "fire-or-seat-unavailable")
            return false
        }
        if (!citizenCampfireVisible(world, activeVisit.seat, fire.position)) {
            stopCamp(actor, "fire-occluded")
            return false
        }
        if (arrived(actor.npc.location, activeVisit.seat.location(world))) {
            if (!citizenCampfireVisible(world, actor.npc.location.clone().add(0.0, actor.npc.eyeHeight, 0.0), fire.position)) {
                stopCamp(actor, "fire-occluded-at-arrival")
                return false
            }
            if (activeVisit.arrivedAt == null) {
                cancelRoute(actor)
                activeVisit.arrivedAt = tick
                CivsDiagnostics.event("citizen_campfire", "city" to actor.cityId, "worker" to actor.workerId,
                    "action" to "arrived", "fire" to fire.position, "seat" to activeVisit.seat)
            }
            val company = actors.values.firstOrNull { other -> other !== actor &&
                other.social.visit?.let { it.fire == activeVisit.fire && it.arrivedAt != null } == true &&
                other.npc.isValid }
            if (!activeVisit.greeted && company != null) {
                activeVisit.greeted = true
                speak(actor, CitizenVoiceEvent.GREET)
            } else speak(actor, CitizenVoiceEvent.IDLE)
            val player = actor.npc
            val focus = company?.npc?.location?.add(0.0, 1.4, 0.0)
                ?: Location(world, fire.position.x + .5, fire.position.y + .4, fire.position.z + .5)
            citizenLookDirection(player.location.clone().add(0.0, player.eyeHeight, 0.0), focus).let { (yaw, pitch) -> look(player, yaw, pitch) }
            statuses[actor.workerId] = "campfire"
            return true
        }
        val route = actor.route
        if (activeVisit.arrivedAt != null || route == null || route.completion != null ||
            !route.chunks.ready(world) || route.timedOut(now)) {
            stopCamp(actor, if (activeVisit.arrivedAt != null) "left-seat" else "route-ended")
            return false
        }
        statuses[actor.workerId] = "to-campfire"
        return true
    }

    /** Reuse the social admission budget and lifecycle for a short pair encounter without a fireplace. */
    private fun startMeeting(actor: Actor, city: Settlement, worker: CivWorker, world: World, now: Long, tick: Int): Boolean {
        fun free(other: Actor): Boolean = other.cityId == city.id && other.worldId == city.worldId &&
            other.npc.isValid && other.npc.world == world && !other.sleeping &&
            !other.sheltering && other.attacker == null && other.foodSource == null && other.supplyPickup == null &&
            other.collectedPending == null && other.collectionSeek == null && other.collectionHome == null &&
            !other.courierHeld && !other.returningToWarehouse && other.workerId !in pendingHarm && other.route == null &&
            other.plan.idleReason in setOf("unassigned", "no-construction", "waiting-growth", "waiting-tool", "construction-paused") &&
            citizenCampEligible(other.plan, other.cycle.phase, world.time) &&
            city.workers.firstOrNull { it.id == other.workerId }?.let { !CivsResidents.isChild(it) && it.cargo == null } == true &&
            other.social.meetingAvailable(tick)
        if (!free(actor)) return false
        val partner = actors.values.asSequence().filter { it !== actor && free(it) &&
            it.npc.location.distanceSquared(actor.npc.location) <= 6.0 * 6.0 }
            .minByOrNull { it.npc.location.distanceSquared(actor.npc.location) } ?: return false
        val otherWorker = city.workers.first { it.id == partner.workerId }
        val home = citizenIdleBuilding(city, worker) ?: return false
        val otherHome = citizenIdleBuilding(city, otherWorker) ?: return false
        val from = actor.npc.location
        val otherFrom = partner.npc.location
        val middle = CivPosition(kotlin.math.floor((from.x + otherFrom.x) / 2).toInt(),
            from.blockY, kotlin.math.floor((from.z + otherFrom.z) / 2).toInt())
        fun safe(other: Actor, origin: CivBuilding, seat: CivPosition): Boolean =
            citizenWithinIdleArea(origin.entrance, seat.point()) &&
                citizenRouteCellAllowed(city, setOf(origin.id) + city.buildings.filter { it.type == BuildingType.TOWN_HALL }.map { it.id }, seat.x, seat.z) &&
                citizenControlClear(city, seat) && idleDestinationFree(other, seat.location(world)) &&
                citizenStandable(world, seat) && citizenIdleTerrain(world, seat, city.buildings)
        // Six pairs / twelve standing checks at most; no block-volume or population scan beyond 48 owned actors.
        for ((dx, dz) in listOf(1 to 0, 0 to 1)) for (dy in listOf(0, 1, -1)) {
            var first = CivPosition(middle.x - dx, middle.y + dy, middle.z - dz)
            var second = CivPosition(middle.x + dx, middle.y + dy, middle.z + dz)
            if (from.point().distanceSquared(first.point()) + otherFrom.point().distanceSquared(second.point()) >
                from.point().distanceSquared(second.point()) + otherFrom.point().distanceSquared(first.point())) {
                val swap = first; first = second; second = swap
            }
            if (!safe(actor, home, first) || !safe(partner, otherHome, second) ||
                !threats.visible(first.location(world).add(0.0, actor.npc.eyeHeight, 0.0),
                    second.location(world).add(0.0, partner.npc.eyeHeight, 0.0))) continue
            actor.social.startMeeting(partner.workerId, first, tick)
            partner.social.startMeeting(actor.workerId, second, tick)
            if (!startRoute(actor, world, first, now, idleAnchor = home.entrance) ||
                !startRoute(partner, world, second, now, idleAnchor = otherHome.entrance)) {
                stopCamp(actor, "route-unavailable")
                return false
            }
            CivsDiagnostics.event("citizen_meeting", "city" to city.id, "worker" to actor.workerId,
                "partner" to partner.workerId, "action" to "start", "seat" to first, "partner_seat" to second)
            return true
        }
        return false
    }

    private fun continueMeeting(actor: Actor, city: Settlement, world: World, tick: Int): Boolean {
        val visit = actor.social.visit ?: return false
        val partner = actors[visit.partnerId]?.takeIf { it.cityId == city.id && it.npc.isValid &&
            it.social.visit?.partnerId == actor.workerId }
        if (partner == null || actor.social.expired(tick) ||
            !citizenStandable(world, visit.seat) || !citizenIdleTerrain(world, visit.seat, city.buildings)) {
            stopCamp(actor, if (partner == null) "partner-unavailable" else "visit-ended")
            return false
        }
        val otherVisit = requireNotNull(partner.social.visit)
        if (!arrived(actor.npc.location, visit.seat.location(world))) {
            val route = actor.route
            if (visit.arrivedAt != null || route == null || route.completion != null ||
                !route.chunks.ready(world) || route.motion.waitingForCollision && navigationTick - route.progressTick >= 40) {
                stopCamp(actor, "approach-blocked")
                return false
            }
            statuses[actor.workerId] = "meeting"
            return true
        }
        if (visit.arrivedAt == null) { cancelRoute(actor); visit.arrivedAt = tick }
        val eye = actor.npc.location.add(0.0, actor.npc.eyeHeight, 0.0)
        val otherEye = partner.npc.location.add(0.0, partner.npc.eyeHeight, 0.0)
        val (yaw, pitch) = citizenLookDirection(eye, otherEye)
        look(actor.npc, yaw, pitch)
        if (otherVisit.arrivedAt == null) { statuses[actor.workerId] = "meeting"; return true }
        if (eye.distanceSquared(otherEye) > 4.0 * 4.0 || !threats.visible(eye, otherEye)) {
            stopCamp(actor, "partner-occluded")
            return false
        }
        if (visit.conversationAt == null) {
            visit.conversationAt = tick
            otherVisit.conversationAt = tick
        }
        val beat = (tick - requireNotNull(visit.conversationAt)) / 50
        val turn = if (actor.workerId < partner.workerId) 0 else 1
        if (beat % 2 == turn && visit.gestureBeat != beat && abs(citizenAngleChange(actor.npc.bodyYaw, yaw)) <= 20f) {
            actor.npc.swingMainHand()
            speak(actor, if (visit.greeted) CitizenVoiceEvent.IDLE else CitizenVoiceEvent.GREET)
            visit.greeted = true
            visit.gestureBeat = beat
        }
        statuses[actor.workerId] = "talking"
        return true
    }

    private fun wander(actor: Actor, city: Settlement, world: World, now: Long) {
        val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: return
        val home = citizenIdleBuilding(city, worker) ?: return
        val anchor = citizenIdleAnchor(city, home)
        val route = actor.route
        if (route != null) {
            val outcome = if (!route.chunks.ready(world)) CitizenNavigationOutcome.STOPPED
                else citizenNavigationOutcome(route.completion,
                    arrived(actor.npc.location, route.target),
                    route.timing.elapsed(now), route.timeoutMillis, route.motion.navigating, route.motion.searching)
            if (outcome == CitizenNavigationOutcome.PENDING) return
            if (outcome != CitizenNavigationOutcome.ARRIVED &&
                !citizenSafeIdlePosition(world, anchor, actor.npc.location.point(), city.buildings) &&
                recoverNavigation(actor, route.target, now, idleAnchor = anchor)) {
                actor.wander.finish(now, true)
                return
            }
            cancelRoute(actor)
            actor.wander.finish(now, outcome == CitizenNavigationOutcome.ARRIVED)
            CivsDiagnostics.event("citizen_wander", "city" to city.id, "worker" to actor.workerId,
                "outcome" to outcome.name.lowercase(), "target" to route.target.point().coordinates(),
                "reason" to route.motion.failure, "actual" to actor.npc.location.point().coordinates(),
                "from" to route.from.coordinates(), "path_progress" to route.motion.forwardProgress,
                "path_nodes" to route.motion.nodeCount, "elapsed_ms" to now - route.startedAt)
            return
        }
        val returning = !citizenWithinIdleArea(anchor, actor.npc.location.point())
        if (!returning && !actor.wander.ready(now) || wanderBudget == 0) return
        // Acceptance can precede the courier's next pulse. Keep the offered feet fixed
        // until its existing hold takes over, or the dispatcher releases the plan.
        if (city.workers.any { courierPlan(city, it)?.let { plan ->
                !plan.carrying && plan.cargo.sourceWorkerId == actor.workerId
            } == true }) return
        wanderBudget--
        // Keep short invalid-target retries fair within the shared eight-check budget.
        citizenRotateAfterAdmission(actors, actor.workerId)
        val target = if (returning) anchor
            else citizenWanderTarget(city, anchor, actor.workerId, actor.wander.step, setOf(home.id),
                actor.npc.location.point(), actor.npc.bodyYaw)
        // startRoute owns the single loaded-chunk, support and headroom check.
        if (target != null && actor.npc.location.point().distanceSquared(target.point()) >= 1.0 &&
            startRoute(actor, world, target, now, idleAnchor = anchor)) {
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

    /** An empty courier stays at its own depot until a real shipment or night shelter takes priority. */
    private fun returnToWarehouse(actor: Actor, city: Settlement, world: World, now: Long): Boolean {
        val warehouse = actor.plan.token.assignmentId?.let(city::building)
        if (actor.plan.courier != null || actor.plan.idleReason != "waiting-orders" ||
            warehouse?.type != BuildingType.WAREHOUSE || warehouse.phase != BuildingPhase.ACTIVE) {
            actor.returningToWarehouse = false
            return false
        }
        stopCamp(actor, "warehouse-return")
        val target = actor.plan.storage
        if (arrivedAt(actor, city, world, target)) {
            actor.route?.trip?.let { paths.complete(city, it) }
            cancelRoute(actor)
            actor.returningToWarehouse = false
            statuses[actor.workerId] = "waiting-orders"
            speak(actor, CitizenVoiceEvent.IDLE)
            return true
        }
        actor.returningToWarehouse = true
        statuses[actor.workerId] = "moving-to-storage"
        if (actor.route == null) startRoute(actor, world, target, now)
        actor.route?.let { route ->
            if (route.completion?.cancelReason == "resident-blocked") cancelRoute(actor)
            else if (route.completion != null || route.timedOut(now)) {
                if (!retryPreparation(actor, route, now)) recoverNavigation(actor, route.target, now)
            }
        }
        return true
    }

    private fun shelter(actor: Actor, city: Settlement, worker: CivWorker, world: World, now: Long) {
        if (!actor.sheltering) {
            cancelRoute(actor)
            actor.returningToWarehouse = false
            actor.sheltering = true
        }
        if (!actor.sleeping && actor.shelter?.let { point ->
                !citizenStandable(world, point) || actor.shelterPose?.let { !citizenSleepClear(world, it.bounds) } != false ||
                    actor.shelterPose?.let { pose -> shelterOccupied(actor).any(pose.bounds::overlaps) } == true ||
                    !arrived(actor.npc.location, point.location(world)) &&
                    !CitizenChunks.between(actor.npc.location.blockX, actor.npc.location.blockZ, point.x, point.z).ready(world)
            } == true) {
            cancelRoute(actor)
            actor.shelter = null
            actor.shelterPose = null
        }
        if (actor.shelter == null && wanderBudget > 0) {
            wanderBudget--
            citizenRotateAfterAdmission(actors, actor.workerId)
            reserveShelter(actor, city, worker, world)
        }
        statuses[actor.workerId] = if (actor.sleeping) "sleeping" else "sheltering"
        val target = actor.shelter ?: return
        if (actor.sleeping) {
            if (sleepStillSafe(actor)) return
            if (!wake(actor)) return
            statuses[actor.workerId] = "sheltering"
        }
        if (arrived(actor.npc.location, target.location(world))) {
            // Arrival has a tolerance; validate the observed feet, not the target's centre.
            val origin = actor.npc.location
            val pose = shelterPose(actor, city, worker, origin, shelterOccupied(actor))
            if (pose != null) {
                cancelRoute(actor)
                actor.sleepOrigin = origin
                actor.sleepPose = pose
                actor.shelterPose = pose
                actor.npc.sleepingBed = null
                actor.npc.teleport(pose.position)
                actor.npc.headYaw = pose.position.yaw
                actor.npc.pose = PacketNpcPose.SLEEPING
                actor.sleeping = true
                statuses[actor.workerId] = "sleeping"
                CivsDiagnostics.event("citizen_sleep", "city" to actor.cityId, "worker" to actor.workerId,
                    "action" to "sleep_requested", "target" to target)
                return
            }
            // Finish the short walk if only the arrival-tolerance position overlaps.
            // If the actual endpoint became occupied, release it for bounded admission
            // on the next pulse instead of retaining a finished route forever.
            if (actor.route?.motion?.finished == true) {
                cancelRoute(actor)
                actor.shelter = null
                actor.shelterPose = null
                return
            }
        }
        val route = actor.route
        if (route == null) { startRoute(actor, world, target, now); return }
        if (route.completion?.cancelReason == "resident-blocked") {
            cancelRoute(actor)
            actor.shelter = null
            actor.shelterPose = null
            return
        }
        if (route.completion != null || route.timedOut(now)) {
            // Keep the failed attempt until the shared recovery budget can admit it.
            // Cancelling it here used to restart the same impossible route every pulse.
            if (!retryPreparation(actor, route, now)) recoverNavigation(actor, route.target, now)
        }
    }

    /** Capacity/capture failures and denied passages retry quietly; they never justify teleporting. */
    private fun retryPreparation(actor: Actor, route: Route, now: Long): Boolean {
        val reason = route.motion.failure?.takeIf { it in PREPARATION_FAILURES } ?: return false
        val retryAt = route.preparationRetryAt ?: (now + 5_000).also { route.preparationRetryAt = it }
        if (now >= retryAt) {
            CivsDiagnostics.event("citizen_route_preparing", "city" to actor.cityId,
                "worker" to actor.workerId, "cycle" to actor.plan.token.completedCycles,
                "phase" to actor.cycle.phase, "state" to "retry", "reason" to reason,
                "actual" to actor.npc.location.point().coordinates(), "target" to route.target.point().coordinates())
            cancelRoute(actor)
        }
        return true
    }

    private fun residentOccupied(actor: Actor): List<BoundingBox> = buildList {
        add(actor.sleepPose?.takeIf { actor.sleeping }?.bounds ?: citizenStandingBounds(actor.npc.location, actor.npc.scale))
        if (actor.sheltering) {
            actor.shelterPose?.let { add(it.bounds) }
            (actor.sleepOrigin ?: actor.shelter?.location(actor.npc.world))?.let { add(citizenStandingBounds(it, actor.npc.scale)) }
        }
    }

    private fun shelterOccupied(actor: Actor): List<BoundingBox> = buildList {
        // The existing runtime cap is 48 actors; reservations do not scan the world.
        for (other in actors.values) {
            if (other === actor || other.worldId != actor.worldId || !other.npc.isValid) continue
            addAll(residentOccupied(other))
        }
    }

    private fun shelterPose(actor: Actor, city: Settlement, worker: CivWorker, feet: Location,
                            occupied: List<BoundingBox>): CitizenSleepPose? {
        val home = citizenHomeBuilding(city, worker) ?: return null
        if (occupied.any(citizenStandingBounds(feet, actor.npc.scale)::overlaps)) return null
        return citizenSleepPose(actor.npc.world, feet, actor.npc.scale) { body ->
            body.minX >= home.bounds.min.x && body.maxX <= home.bounds.max.x + 1.0 &&
                body.minY >= home.bounds.min.y && body.maxY <= home.bounds.max.y + 1.0 &&
                body.minZ >= home.bounds.min.z && body.maxZ <= home.bounds.max.z + 1.0 && occupied.none(body::overlaps)
        }
    }

    private fun reserveShelter(actor: Actor, city: Settlement, worker: CivWorker, world: World,
                               safe: (CivPosition) -> Boolean = { true }): CivPosition? {
        val candidates = citizenShelterCandidates(city, worker)
        if (candidates.isEmpty()) return null
        val occupied = shelterOccupied(actor)
        // Preserve a clear existing place in the home before choosing another one.
        // This also keeps a resident already at home from walking just to lie down.
        val at = actor.npc.location
        val current = CivPosition(at.blockX, at.blockY, at.blockZ)
        if (citizenControlClear(city, current) && safe(current)) {
            val pose = shelterPose(actor, city, worker, at, occupied)
            if (pose != null) {
                actor.shelter = current
                actor.shelterPose = pose
                return current
            }
        }
        // Several nearby feet share one search envelope. Retain no native readiness
        // across pulses, and include the existing-place probe in the 80-check budget.
        val ready = hashMapOf<CitizenChunks, Boolean>()
        repeat(minOf(79, candidates.size)) {
            val index = Math.floorMod(actor.shelterSearchIndex++, candidates.size)
            val point = candidates[index]
            val chunks = CitizenChunks.between(at.blockX, at.blockZ, point.x, point.z)
            if (citizenControlClear(city, point) && ready.getOrPut(chunks) { chunks.ready(world) } &&
                citizenStandable(world, point) && safe(point)) {
                val pose = shelterPose(actor, city, worker, point.location(world), occupied)
                if (pose != null) {
                    actor.shelter = point
                    actor.shelterPose = pose
                    return point
                }
            }
        }
        return null
    }

    private fun startRoute(actor: Actor, world: World, target: CivPosition, now: Long, idleAnchor: CivPosition? = null): Boolean {
        if (!wake(actor)) return false
        val from = actor.npc.location
        val city = cityProvider().firstOrNull { it.id == actor.cityId } ?: return false
        // A valid hand reach does not depend on the availability of the preferred standing
        // cell. Likewise, any visible side of the real storage barrel is a valid arrival.
        if (idleAnchor == null && !actor.sheltering && !actor.returningToWarehouse &&
            actor.plan.idleReason in listOf(null, "placing") && arrivedAt(actor, city, world, target)) {
            return true
        }
        val storage = storageForTarget(actor, city, target)
        if (storage != null) {
            val control = requireNotNull(storage.control)
            val cached = actor.targets[target]?.takeIf { idleAnchor == null && citizenAtStorage(world, it.point(), control) && citizenStandable(world, it) }
            val standing = cached ?: citizenStorageTarget(city, storage, world)
                ?: storage.takeIf { !citizenStorageReady(world, control) && it.phase != BuildingPhase.ACTIVE && city.construction?.buildingId == it.id }
                    ?.entrance?.takeIf { citizenStandable(world, it) }
            if (standing == null) {
                actor.targets.remove(target)
                pause(actor, "storage-unavailable", now)
                return false
            }
            actor.targets[target] = standing
        }
        val resolved = if (idleAnchor != null && storage == null) target else resolvedTarget(actor, target)
        val waypoint = citizenMineWaypoint(city, world, from, resolved)
        val to = (waypoint ?: resolved).location(world)
        if (idleAnchor != null && !idleDestinationFree(actor, to)) return false
        val distance = from.distance(to)
        if (!citizenRouteWithinReach(from.point(), to.point())) {
            if (idleAnchor == null && actor.plan.idleReason in listOf(null, "placing")) pauseNavigation(actor, "route-unreachable", "outside-route-range", to, now)
            return false
        }
        val chunks = CitizenChunks.between(from.blockX, from.blockZ, resolved.x, resolved.z)
        if (!chunks.ready(world)) {
            if (idleAnchor == null && actor.plan.idleReason in listOf(null, "placing")) remove(actor, "chunk-unloaded")
            return false
        }
        if (!citizenControlClear(city, resolved) || !citizenStandable(world, resolved) ||
            idleAnchor != null && !citizenIdleTerrain(world, resolved, city.buildings)) {
            if (idleAnchor == null && actor.plan.idleReason in listOf(null, "placing")) pauseNavigation(actor, "route-unreachable", "target-not-standable", to, now)
            return false
        }
        val allowed = citizenRouteBuildings(city, actor.plan, target, CivPosition(from.blockX, from.blockY, from.blockZ)) +
            listOfNotNull(idleAnchor?.let { citizenControlBuilding(city, it)?.id })
        val idleTransit = idleAnchor != null && !citizenWithinIdleArea(idleAnchor, from.point())
        val trip = if (actor.collectionSeek == null && actor.collectionHome == null &&
            (actor.returningToWarehouse || citizenWorkingTravel(actor.plan, actor.cycle.phase, actor.sheltering))) paths.begin(city) else null
        val mine = routeMine(actor, city).takeIf { idleAnchor == null }
        // The outdoor approach to the upper ladder is ordinary city travel. Once inside,
        // a work leg must use the mine's passages, including when a natural cave is open nearby.
        val workMine = mine?.takeIf { actor.cycle.phase == CitizenPhase.TO_WORK && actor.plan.worldWork?.miningStep != null }
        val minePassage = workMine?.let { building ->
            val work = actor.plan.worldWork
            citizenMineRoutePassage(building, work?.miningAccessStep ?: work?.miningStep ?: 0,
                city.minePlans?.get(building.id.toString()))
        }
        val motion = navigation.request(world, from, to, { point ->
            citizenRoutePointAllowed(city, allowed, point, mine) && citizenControlClear(city, point) &&
                (idleAnchor == null || idleTransit || citizenIdleRouteAllowed(idleAnchor, from.point(), point) &&
                    citizenIdleTerrain(world, point, city.buildings))
        }, { first, second -> citizenControlStepClear(city, first, second) &&
            (minePassage == null || minePassage(second)) },
            { ground -> trip?.cost(ground, surfaceSpeed) ?: 1.0 })
        val timeout = (10_000 + distance * 1_500).toLong().coerceAtMost(90_000)
        actor.route = Route(from.point(), to, chunks, now, timeout,
            allowed, motion, trip, target, waypoint != null, idleAnchor)
        actor.route?.progressTick = navigationTick
        val storageReason = when {
            actor.plan.type != BuildingType.BUILDER_HUT || idleAnchor != null || actor.sheltering || actor.attacker != null -> null
            actor.foodSource != null -> "food"
            actor.cycle.phase == CitizenPhase.RETURNING -> "unload"
            actor.plan.idleReason == "waiting-materials" -> "next-required-type"
            actor.cycle.phase == CitizenPhase.TO_STORAGE -> "work-materials"
            else -> null
        }
        if (storage != null && storageReason != null) {
            actor.route?.storageReason = storageReason
            actor.route?.storageBuilding = storage.id
            val worker = city.workers.firstOrNull { it.id == actor.workerId }
            CivsDiagnostics.event("citizen_storage_trip", "city" to actor.cityId, "worker" to actor.workerId,
                "order" to actor.plan.token.constructionId, "batch" to city.construction?.workDone,
                "construction_phase" to city.construction?.phase, "credit" to city.construction?.workCredit,
                "source" to storage.id, "target" to target, "from" to from.point(),
                "phase" to actor.cycle.phase, "reason" to storageReason, "supplies_needed" to actor.plan.suppliesNeeded,
                "carried" to worker?.let { CivsEquipment.available(it, emptyMap()) },
                "route_started_at" to now, "state" to "planning", "path_nodes" to motion.nodeCount)
        }
        look(actor.npc, from.yaw, 0f)
        return true
    }

    private fun observePath(route: Route) {
        route.motion.endpoint?.let { route.path = CitizenPathSnapshot(route.motion.nodeCount, it, false) }
    }

    private fun pauseNavigation(actor: Actor, status: String, detail: String, target: Location, now: Long) {
        navigationDiagnostic(actor, detail, target, now, terminal = true)
        if (actor.route?.completion?.cancelReason == "resident-blocked") { pause(actor, status, now); return }
        if (actor.route?.let { it.nativeCollisionTicks > 0 || it.motion.waitingForCollision ||
                it.previousMotion?.waitingForCollision == true } == true) {
            // Native players may legitimately keep a passage closed. Owned residents use bounded retries.
            if (actor.collisionResident != null && actor.residentYield == null &&
                actor.collisionResident?.let(actors::get)?.residentYield == null &&
                (actor.route?.replanAttempts ?: 0) >= 2) pause(actor, status, now)
            return
        }
        if (retryWorkPosition(actor, detail)) return
        if (actor.plan.courier?.let { !it.carrying && it.cargo.sourceWorkerId != null } == true) {
            pause(actor, status, now)
            return
        }
        if (!recoverNavigation(actor, target, now)) pause(actor, status, now)
    }

    /** A failed final approach rejects this stance, leaving the same physical action to its planner. */
    private fun retryWorkPosition(actor: Actor, detail: String): Boolean {
        val route = actor.route ?: return false
        val work = actor.plan.worldWork ?: return false
        if (!(actor.plan.type == BuildingType.QUARRY && work.miningStep != null || work.upkeep != null) ||
            actor.cycle.phase != CitizenPhase.TO_WORK || actor.plan.courier != null ||
            actor.sheltering || actor.returningToWarehouse || actor.attacker != null || actor.foodSource != null ||
            route.intermediate || route.idleAnchor != null || route.authored != work.standing || route.motion.searching) return false
        val cause = route.motion.failure ?: route.completion?.cancelReason ?: detail
        if (cause !in setOf("no-path", "search-budget", "capture-budget", "movement-blocked", "path-changed",
                "no-progress", "turn-loop", "timed_out", "stopped", "completed_outside_arrival")) return false
        val city = cityProvider().firstOrNull { it.id == actor.cityId && it.pendingDeletion == null } ?: return false
        val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: return false
        if (!workPositionFailed(city, worker, work)) return false
        CivsDiagnostics.event("citizen_work_position_rejected", "city" to city.id, "worker" to worker.id,
            "building" to work.buildingId, "cycle" to work.cycle, "mining_step" to work.miningStep,
            "focus" to citizenWorkFocus(work), "standing" to work.standing, "reason" to cause,
            "actual" to actor.npc.location.point().coordinates(), "path_progress" to route.motion.forwardProgress)
        cancelRoute(actor)
        actor.workHandoff = null
        actor.plan = actor.plan.copy(workplace = null, worldWork = null, idleReason = "searching-work")
        actor.cycle.close()
        actor.cycle = actor.plan.cycle()
        statuses[actor.workerId] = "searching-work"
        return true
    }

    private fun mineRouteWork(actor: Actor, route: Route): CivWorldWork? = actor.plan.worldWork?.takeIf { work ->
        actor.plan.type == BuildingType.QUARRY && work.miningStep != null && actor.cycle.phase == CitizenPhase.TO_WORK &&
            actor.plan.courier == null && !actor.sheltering && !actor.returningToWarehouse && actor.attacker == null &&
            actor.foodSource == null && actor.social.visit == null && route.idleAnchor == null && route.authored == work.standing
    }

    private fun mineRouteFailure(actor: Actor, route: Route, detail: String, target: Location, now: Long) {
        if (route.mineFailureReported || route.motion.searching || route.motion.waitingForCollision || route.nativeCollisionTicks > 0) return
        val work = mineRouteWork(actor, route) ?: return
        route.mineFailureReported = true
        val report = actor.mineRouteEpisode.failed(requireNotNull(CivsMineRouteEpisode.key(work)), now) ?: return
        CivsDiagnostics.record(plugin.logger, Level.WARNING, actor.cityId, MINE_ROUTE_DIAGNOSTIC.line(
            "city" to actor.cityId, "worker" to actor.workerId, "building" to report.key.buildingId,
            "plan_version" to report.key.planVersion, "cycle" to report.key.cycle, "mining_step" to report.key.miningStep,
            "state" to if (report.failures >= CivsMineRouteEpisode.STALL_FAILURES) "stalled" else "blocked",
            "episode_failures" to report.failures, "episode_elapsed_ms" to report.elapsedMillis,
            "reason" to (route.motion.failure ?: route.completion?.cancelReason ?: detail),
            "phase" to actor.cycle.phase, "actual" to actor.npc.location.point().coordinates(),
            "target" to target.point().coordinates(), "focus" to citizenWorkFocus(work), "standing" to work.standing,
            "path_nodes" to route.motion.nodeCount, "captured_blocks" to route.motion.capturedBlocks,
            "path_progress" to route.motion.forwardProgress, "episode_walked_distance" to report.walkedDistance,
            "search_nanos" to route.motion.searchNanos, "search_state" to route.motion.preparationPhase,
            "route_elapsed_ms" to (now - route.startedAt).coerceAtLeast(0), "intermediate" to route.intermediate,
            "standing_exclusion" to "pending", "suppressed" to report.suppressed))
    }

    private fun mineRouteRecovered(actor: Actor, report: CivsMineRouteEpisode.Snapshot, reason: String, now: Long) {
        CivsDiagnostics.record(plugin.logger, Level.INFO, actor.cityId, MINE_ROUTE_RECOVERED.line(
            "city" to actor.cityId, "worker" to actor.workerId, "building" to report.key.buildingId,
            "plan_version" to report.key.planVersion, "cycle" to report.key.cycle, "mining_step" to report.key.miningStep,
            "reason" to reason, "episode_failures" to report.failures, "episode_elapsed_ms" to report.elapsedMillis,
            "walked_distance" to report.walkedDistance, "actual" to actor.npc.location.point().coordinates(),
            "suppressed" to report.suppressed, "at" to now))
    }

    private fun navigationDiagnostic(actor: Actor, detail: String, target: Location, now: Long, terminal: Boolean = false): Boolean {
        val route = actor.route
        val actual = actor.npc.location.point()
        if (terminal && route != null) mineRouteFailure(actor, route, detail, target, now)
        if (actor.navigationEpisode == null) {
            actor.navigationEpisode = detail
            actor.navigationEpisodeProgress = 0.0
            actor.navigationEpisodeTick = navigationTick
        }
        if (navigationTick < actor.navigationReportAfter) { actor.navigationSuppressed++; return false }
        actor.navigationReportAfter = navigationTick + 200
        CivsDiagnostics.record(plugin.logger, Level.WARNING, actor.cityId, NAVIGATION_DIAGNOSTIC.line(
            "city" to actor.cityId, "worker" to actor.workerId, "cycle" to actor.plan.token.completedCycles,
            "attempt" to actor.cycle.failedAttempts + 1, "phase" to actor.cycle.phase, "reason" to if (route?.motion?.waitingForCollision == true)
                if (actor.collisionResident != null) "resident-blocked" else "player-blocked" else detail,
            "blocking_resident" to actor.collisionResident,
            "from" to route?.from?.coordinates(), "target" to target.point().coordinates(), "actual" to actual.coordinates(),
            "distance" to sqrt(actual.distanceSquared(target.point())), "elapsed_ms" to route?.let { now - it.startedAt },
            "path_nodes" to route?.motion?.nodeCount, "path_failure" to route?.motion?.failure,
            "searching" to route?.motion?.searching, "collision_wait" to route?.motion?.waitingForCollision,
            "search_state" to route?.motion?.preparationPhase,
            "preparation_ms" to route?.timing?.preparationElapsed(now), "travel_ms" to route?.timing?.elapsed(now),
            "captured_blocks" to route?.motion?.capturedBlocks, "search_nanos" to route?.motion?.searchNanos,
            "tick" to Bukkit.getCurrentTick(), "navigation_tick" to navigationTick, "cadence_ms" to navigationCadenceMillis,
            "body_yaw" to actor.npc.bodyYaw, "head_yaw" to actor.npc.headYaw,
            "previous_body_yaw" to actor.observedYaw,
            "frame_turn_degrees" to actor.observedYaw?.let { abs(citizenAngleChange(it, actor.npc.bodyYaw)) },
            "turn_degrees" to actor.turnAmount,
            "waypoint" to route?.motion?.destination?.point()?.coordinates(), "remaining_nodes" to route?.motion?.remainingNodes,
            "path_progress" to route?.motion?.forwardProgress, "episode_progress" to actor.navigationEpisodeProgress,
            "no_progress_ticks" to route?.let { navigationTick - it.progressTick }, "replans" to route?.replanAttempts,
            "suppressed" to actor.navigationSuppressed,
            "work_focus" to actor.plan.worldWork?.focus, "work_standing" to actor.plan.worldWork?.standing,
            "mining_step" to actor.plan.worldWork?.miningStep,
            "strategy" to "packet-async-a-star"))
        actor.navigationSuppressed = 0
        return true
    }

    /** Retry changed geometry from observed feet; a bounded person snapshot allows a real detour. */
    private fun replanNavigation(actor: Actor, route: Route, reason: String, now: Long): Boolean {
        if (route.previousMotion != null) return true
        if (reason !in setOf("movement-blocked", "path-changed", "no-progress", "turn-loop", "player-blocked", "resident-blocked") ||
            route.replanAttempts >= 2 || actor.workerId in pendingHarm) return false
        val npc = actor.npc
        val from = npc.location
        val bodies = if (reason in setOf("player-blocked", "resident-blocked")) npc.world.getNearbyPlayers(from, 2.5, 2.5, 2.5)
            .asSequence().filter { it.isOnline && it.isValid && !it.isDead && it.gameMode != GameMode.SPECTATOR &&
                !it.isInsideVehicle && !it.isSleeping && it.isCollidable }.take(8).map { it.boundingBox.clone() }.toList()
                .plus(shelterOccupied(actor).filter { it.overlaps(citizenStandingBounds(from, npc.scale).expand(2.5)) })
            else emptyList()
        val report = navigationDiagnostic(actor, reason, route.target, now)
        route.replanAttempts++
        route.replanReason = reason
        route.timing.prepare(now)
        route.preparationReportAfter = now + 2_000
        route.previousMotion = route.motion
        route.motion = navigation.replan(route.motion, from, bodies)
        route.completion = null
        route.path = null
        route.progressTick = navigationTick
        if (report) CivsDiagnostics.event("citizen_route_progress", "city" to actor.cityId, "worker" to actor.workerId,
            "action" to "replan", "reason" to reason, "phase" to actor.cycle.phase, "attempt" to route.replanAttempts,
            "target" to route.target.point().coordinates(), "actual" to from.point().coordinates(),
            "tick" to Bukkit.getCurrentTick(), "navigation_tick" to navigationTick, "obstacles" to bodies.size)
        return true
    }

    private fun resolvedTarget(actor: Actor, target: CivPosition): CivPosition = actor.targets[target] ?: target

    private fun routeMine(actor: Actor, city: Settlement): CivBuilding? = actor.plan.takeIf {
        it.type == BuildingType.QUARRY && it.worldWork != null && it.courier == null && !actor.sheltering &&
            actor.attacker == null && actor.foodSource == null && actor.social.visit == null &&
            actor.collectionSeek == null && actor.collectionHome == null
    }?.token?.assignmentId?.let(city::building)?.takeIf { it.type == BuildingType.QUARRY }

    private fun canWorkFrom(actor: Actor, work: CivWorldWork): Boolean {
        // Reach during a step or fall is transient: keep walking until the feet have support.
        if (!citizenPacketSupported(actor.npc.world, actor.npc.location.point())) return false
        if (actor.plan.type == BuildingType.QUARRY && work.miningStep != null) {
            val city = cityProvider().firstOrNull { it.id == actor.cityId } ?: return false
            val mine = city.building(work.buildingId) ?: return false
            val feet = actor.npc.location
            val standing = CivPosition(feet.blockX, feet.y.roundToInt(), feet.blockZ)
            val geometry = CivsMinePlan.forBuilding(mine, work.miningPlanVersion ?: CivsMinePlan.VERSION) ?: return false
            if (!geometry.builtStanding(CivsMinePlan.localPoint(mine, standing), work.miningAccessStep ?: work.miningStep) ||
                !citizenMineWorkRouteAllowed(mine, feet.point(), work.miningAccessStep ?: work.miningStep,
                    city.minePlans?.get(mine.id.toString()))) return false
        }
        return citizenWorkFocus(work)?.let { citizenCanReachBlock(actor.npc, it, citizenWorkReach(actor.plan.type)) } == true
    }

    private fun storageForTarget(actor: Actor, city: Settlement, target: CivPosition): CivBuilding? {
        val work = actor.plan.worldWork
        return if (actor.plan.autonomousWork != null && (target == actor.plan.workplace || target == actor.plan.storage)) null
        else if (target == actor.plan.workplace && actor.plan.courier?.cargo?.sourceWorkerId != null) null
        else if (target == actor.plan.workplace && work != null && citizenWorkFocus(work) == null)
            city.building(work.buildingId)?.takeIf { it.control != null }
        else citizenControlBuilding(city, target)
    }

    private fun arrivedAt(actor: Actor, city: Settlement, world: World, target: CivPosition): Boolean {
        if (actor.plan.autonomousWork != null && (target == actor.plan.workplace || target == actor.plan.storage))
            return citizenStandable(world, target) && citizenControlClear(city, target) &&
                citizenPacketBodyClear(world, actor.npc.location.point(), false) &&
                arrived(actor.npc.location, target.location(world))
        actor.plan.courier?.takeIf { !it.carrying && it.cargo.sourceWorkerId != null && target == actor.plan.workplace }?.let { plan ->
            val source = actors[plan.cargo.sourceWorkerId] ?: return false
            return citizenNearEndpoint(source.npc.location.point(), plan.cargo.sourceEntrance) && courierMeetsSource(actor, source, plan.cargo)
        }
        val work = actor.plan.worldWork
        if (!actor.sheltering && target == actor.plan.workplace && work != null && citizenWorkFocus(work) != null)
            return canWorkFrom(actor, work)
        val storage = storageForTarget(actor, city, target)
        val control = storage?.control ?: target
        if (storage != null && citizenAtStorage(world, actor.npc.location.point(), control) &&
            citizenCanReachBlock(actor.npc, control)) return true
        if (!arrived(actor.npc.location, resolvedTarget(actor, target).location(world))) return false
        if (storage == null) return true
        return !citizenStorageReady(world, control) && storage.phase != BuildingPhase.ACTIVE && city.construction?.buildingId == storage.id &&
            resolvedTarget(actor, target) == storage.entrance
    }

    /** A proven failed route may recover to a freshly validated loaded endpoint, never into a wall. */
    private fun recoverNavigation(actor: Actor, target: Location, now: Long, idleAnchor: CivPosition? = null): Boolean {
        val failed = actor.route ?: return false
        if (actor.plan.worldWork?.upkeep != null) return false
        // Planning latency, denied passages and player obstruction never authorize a recovery teleport.
        if (failed.motion.searching || failed.motion.waitingForCollision || failed.nativeCollisionTicks > 0 ||
            now < actor.nextRecoveryAt || actor.attacker != null) return false
        if (failed.motion.failure in PREPARATION_FAILURES) return false
        if (failed.motion.failure == null && failed.completion == null && !failed.timedOut(now)) return false
        if (recoveryBudget == 0 || idleAnchor == null && !actor.sheltering && !actor.returningToWarehouse &&
            actor.plan.idleReason !in listOf(null, "placing") ||
            actor.cycle.phase in setOf(CitizenPhase.PICKING_UP, CitizenPhase.DELIVERING)) return false
        val city = cityProvider().firstOrNull { it.id == actor.cityId && it.pendingDeletion == null } ?: return false
        val entity = actor.npc
        if (!entity.isValid || entity.world.uid != city.worldId || target.world?.uid != city.worldId) return false
        val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: return false
        val home = if (actor.sheltering) citizenHomeBuilding(city, worker) ?: return false else null
        val authored = when {
            actor.sheltering -> actor.shelter ?: return false
            actor.returningToWarehouse -> actor.plan.storage
            idleAnchor != null -> idleAnchor
            actor.cycle.phase == CitizenPhase.RETURNING -> actor.plan.travelTarget(CitizenPhase.RETURNING)
            actor.cycle.phase in setOf(CitizenPhase.TO_WORK, CitizenPhase.WORKING) ||
                actor.cycle.phase == CitizenPhase.READY && actor.plan.completeAtWorkplace -> actor.plan.workplace ?: return false
            else -> actor.plan.storage
        }
        recoveryBudget--
        citizenRotateAfterAdmission(actors, actor.workerId)
        val viewers = viewers()
        val safe: (CivPosition) -> Boolean = { candidate ->
            citizenStandable(entity.world, candidate) && citizenPacketBodyClear(entity.world, candidate.point(), false) &&
                citizenControlClear(city, candidate) && citizenRoutePointAllowed(city, failed.allowedBuildings, candidate,
                    routeMine(actor, city).takeIf { idleAnchor == null }) &&
                (home == null || home.bounds.contains(candidate.x, candidate.y, candidate.z)) &&
                (idleAnchor == null || citizenWithinIdleArea(idleAnchor, candidate.point()) && citizenIdleTerrain(entity.world, candidate, city.buildings)) &&
                activeAt(city.id, city.worldId, candidate.point(), viewers) &&
                candidate.location(entity.world).let { residentMoveAllowed(actor, it, it) }
        }
        val storage = storageForTarget(actor, city, authored)
        val point = (when {
            actor.sheltering -> authored.takeIf(safe)?.takeIf { point ->
                shelterPose(actor, city, worker, point.location(entity.world), shelterOccupied(actor))
                    ?.also { actor.shelterPose = it } != null
            } ?: reserveShelter(actor, city, worker, entity.world, safe)
            storage != null -> citizenStorageTarget(city, storage, entity.world, safe)
            else -> citizenRecoveryTarget(city, authored, safe)
        }) ?: return false
        val destination = point.location(entity.world)
        if (!residentMoveAllowed(actor, destination, destination)) return false
        val previous = entity.location
        destination.yaw = previous.yaw
        cancelRoute(actor, destination)
        if (actor.sheltering) actor.shelter = point
        else if (idleAnchor == null) actor.targets[authored] = point
        entity.teleport(destination)
        actor.observedYaw = destination.yaw
        actor.navigationEpisodeProgress = 0.0
        actor.mineRouteEpisode.teleported()
        actor.nextRecoveryAt = now + 60_000
        actor.footsteps.reset()
        if (idleAnchor == null && !actor.sheltering && !actor.returningToWarehouse) {
            if (!arrivedAt(actor, city, entity.world, authored)) return false
            arriveForWork(actor, city, worker, now)
        }
        CivsDiagnostics.event("citizen_route_recovered", "city" to city.id, "worker" to actor.workerId,
            "mode" to "safe-teleport", "cause" to (failed.motion.failure ?: failed.completion?.cancelReason ?: "timeout"),
            "distance" to previous.distance(destination), "phase" to actor.cycle.phase, "requested" to authored, "destination" to point)
        return true
    }

    private fun submitDelivery(actor: Actor, ticket: UUID, now: Long) {
        val token = actor.plan.token
        val actorId = actor.instanceId
        val workerId = actor.workerId
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
                if (completion.pickup) {
                    releaseCourierSource(actor, now)
                    actor.cycle.failPickup((error as? CivsRejection)?.reason, now)
                }
                else actor.cycle.failDelivery((error as? CivsRejection)?.reason, now)
                if (error !is CivsRejection) logFailure(actor, "delivery", error)
            } else {
                val worker = completion.city?.workers?.find { it.id == actor.workerId }
                if (completion.pickup) {
                    val cargo = worker?.cargo
                    if (cargo == null || cargo.id != actor.plan.courier?.cargo?.id ||
                        worker.completedCycles != completion.token.completedCycles) actor.cycle.failPickup(null, now)
                    else {
                        releaseCourierSource(actor, now, success = true)
                        actor.plan = actor.plan.copy(courier = requireNotNull(actor.plan.courier).copy(cargo = cargo, carrying = true))
                        actor.cycle.pickedUp()
                        actor.reportedFailure = false
                    }
                } else if (worker == null || worker.completedCycles != completion.token.completedCycles + 1 ||
                    actor.plan.courier != null && worker.cargo != null) {
                    actor.cycle.failDelivery(null, now)
                } else {
                    // The provider publishes durable state. Reconciliation starts a new fenced cycle, never this callback.
                    if (actor.plan.worldWork?.let { it.miningStep != null && it.changes.isNotEmpty() } == true)
                        actor.mineRouteEpisode.recovered(now)?.let { mineRouteRecovered(actor, it, "work-completed", now) }
                    actor.cycle.close()
                    actor.workHandoff = actor.plan.worldWork?.takeUnless {
                        it.status in setOf("waiting-tool", "waiting-materials", "collecting-supplies")
                    }?.let { work -> CitizenWorkHandoff(work.buildingId, worker.completedCycles,
                        actor.npc.location.point(), navigationTick, work.status) }
                    actor.reportedFailure = false
                    runCatching { fieldCareCompleted(actor) }.onFailure { logFailure(actor, "field-care-feedback", it) }
                }
            }
            if (!completion.pickup) fieldCareHand(actor, null)
        }
    }

    private fun pause(actor: Actor, reason: String, now: Long) {
        actor.plan.courier?.takeIf { !it.carrying && reason in setOf("route-unreachable", "delivery-failed") }?.let {
            courierFailed(actor.cityId, actor.workerId, it.cargo)
        }
        releaseCourierSource(actor, now)
        stopCamp(actor, reason)
        cancelRoute(actor)
        actor.cycle.pause(reason, now, false)
    }

    private fun cancelRoute(actor: Actor, wakeDestination: Location? = null) {
        actor.residentYield = null
        actor.collisionResident = null
        fieldCareHand(actor, null)
        actor.route?.motion?.cancel()
        actor.route?.previousMotion?.cancel()
        actor.route = null
        actor.lookYaw = null
        actor.footsteps.reset()
        wake(actor, wakeDestination)
    }

    private fun sleepStillSafe(actor: Actor): Boolean {
        val origin = actor.sleepOrigin ?: return false
        return citizenPacketSupported(actor.npc.world, origin.point()) &&
            citizenPacketBodyClear(actor.npc.world, origin.point(), false) &&
            actor.sleepPose?.let { citizenSleepClear(actor.npc.world, it.bounds) } == true
    }

    private fun wake(actor: Actor, checkedDestination: Location? = null): Boolean {
        val wasSleeping = actor.sleeping
        val npc = actor.npc
        val origin = actor.sleepOrigin
        val destination = if (wasSleeping && npc.isValid) {
            val occupied = shelterOccupied(actor)
            fun clear(at: Location) = at.world == npc.world && citizenPacketBodyClear(npc.world, at.point(), false) &&
                citizenPacketCampfireClear(npc.world, at.point()) &&
                occupied.none(citizenStandingBounds(at, npc.scale)::overlaps)
            checkedDestination?.takeIf(::clear) ?: origin?.takeIf(::clear) ?: run {
                if (recoveryBudget == 0 || origin == null) return false
                recoveryBudget--
                val city = cityProvider().firstOrNull { it.id == actor.cityId } ?: return false
                val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: return false
                val home = citizenHomeBuilding(city, worker) ?: return false
                val lying = actor.sleepPose?.bounds ?: return false
                citizenShelterTarget(city, worker) { point ->
                    val at = point.location(npc.world)
                    citizenStandable(npc.world, point) && clear(at) && citizenControlClear(city, point) &&
                        citizenRouteCellAllowed(city, setOf(home.id), point.x, point.z) &&
                        // Slide the low pose only through its clear swept envelope before standing.
                        // This cannot jump through a wall into another room or across a control.
                        citizenSleepClear(npc.world, lying.clone().union(lying.clone().shift(at.toVector().subtract(origin.toVector())))) &&
                        residentMoveAllowed(actor, at, at)
                }?.location(npc.world) ?: return false
            }
        } else null
        actor.sleeping = false
        actor.sleepOrigin = null
        actor.sleepPose = null
        if (wasSleeping) { actor.shelter = null; actor.shelterPose = null }
        if (!npc.isValid) return true
        npc.sleepingBed = null
        if (destination != null) npc.teleport(destination)
        npc.pose = PacketNpcPose.STANDING
        if (wasSleeping) CivsDiagnostics.event("citizen_sleep", "city" to actor.cityId, "worker" to actor.workerId, "action" to "wake")
        return true
    }

    private fun look(npc: PacketPlayerNpc, yaw: Float, pitch: Float) {
        val actor = actors.values.firstOrNull { it.npc === npc } ?: return
        actor.lookYaw = yaw
        actor.lookPitch = pitch
    }

    private fun logFailure(actor: Actor, phase: String, error: Throwable) {
        if (actor.reportedFailure) return
        actor.reportedFailure = true
        CivsDiagnostics.record(plugin.logger, Level.WARNING, actor.cityId,
            "ArcCivs NPC failure city=${actor.cityId} worker=${actor.workerId} phase=$phase cycle=${actor.plan.token.completedCycles}", error)
    }

    private fun setEquipment(npc: PacketPlayerNpc, plan: CitizenPlan) {
        npc.setEquipment(EquipmentSlot.HAND, ItemStack(citizenTool(plan) ?: Material.AIR))
    }

    private fun syncResident(npc: PacketPlayerNpc, worker: CivWorker, plan: CitizenPlan) {
        val actor = actors[worker.id]
        npc.scale = citizenScale(worker)
        val items = CivsEquipment.slots(worker).let { slots ->
            if (CivsResidents.isChild(worker)) slots.mapIndexed { index, item -> if (index in 4..5) null else item } else slots
        }
        if (actor?.equipmentItems != items || actor?.equipmentType != plan.type) {
            val slots = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)
            slots.forEachIndexed { index, slot ->
                npc.setEquipment(slot, if (index == 4 && actor?.careItem != null) ItemStack(requireNotNull(actor.careItem))
                    else items[index]?.stack() ?: ItemStack(if (index == 4) citizenTool(plan) ?: Material.AIR else Material.AIR))
            }
            actor?.equipmentItems = items
            actor?.equipmentType = plan.type
        }
        threats.equipment(worker.id, items.map { it?.stack() })
    }

    /** Every accepted hit freezes work until the canonical city write decides injury or permanent death. */
    internal fun damage(workerId: UUID, amount: Double) {
        val actor = actors[workerId] ?: return
        if (closed.get() || actor.workerId in pendingHarm) return
        val at = System.currentTimeMillis()
        if (at < actor.damageAfter) return
        val city = cityProvider().firstOrNull { it.id == actor.cityId } ?: return
        val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: return
        val damage = CivsResidents.damage(worker, actor.plan.type, amount)
        if (!damage.isFinite() || damage <= 0) return
        actor.damageAfter = at + 500
        pendingHarm += actor.workerId
        runCatching { stopCamp(actor, "injury"); cancelRoute(actor) }.onFailure { logFailure(actor, "injury-stop", it) }
        actor.cycle.close()
        actor.reportHarmCompletion = at >= actor.harmLogAfter
        if (actor.reportHarmCompletion) {
            actor.harmLogAfter = at + 5_000
            CivsDiagnostics.event("citizen_injury", "city" to actor.cityId, "worker" to actor.workerId,
                "state" to "pending", "damage" to damage)
        }
        val completionQueue = harmCompletions
        val epoch = harmEpoch
        val future = try { hurt(actor.cityId, actor.workerId, damage) }
            catch (error: Exception) { CompletableFuture.failedFuture(error) }
        future.whenComplete { saved, error ->
            val completion = HarmCompletion(actor.workerId, saved, error, epoch)
            if (!closed.get()) {
                completionQueue.add(completion)
                if (closed.get()) completionQueue.remove(completion)
            }
        }
    }

    private fun drainHarm() {
        while (true) {
            val result = harmCompletions.poll() ?: return
            if (result.epoch != harmEpoch) continue
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
                    else if (!actor.npc.isValid) remove(actor, "unavailable")
                    else {
                        actor.cycle = actor.plan.cycle()
                        syncResident(actor.npc, worker, actor.plan)
                        actor.npc.playHurtAnimation()
                        if (actor.reportHarmCompletion) CivsDiagnostics.event("citizen_injury", "city" to actor.cityId,
                            "worker" to actor.workerId, "state" to "committed", "health" to CivsResidents.health(worker))
                    }
                }.onFailure {
                    logFailure(actor, "injury-publication", it)
                    remove(actor, "unavailable") // The next actor comes from the committed health, never from a replayed hit.
                }
            }
        }
    }

    private fun recordAdmission(city: Settlement, worker: CivWorker, plan: CitizenPlan?, world: World?,
                                reason: String, at: Long, retry: CitizenRetry? = null, spawn: CitizenSpawnSearch? = null) {
        val previous = admissions[worker.id]?.takeIf { it.plan == plan }
        val origin = plan?.spawnOrigin
        val level = if (world == null || origin == null) null else citizenChunkLoadLevel(world, origin.x shr 4, origin.z shr 4)
        admissions[worker.id] = CitizenAdmission(city.id, worker.id, plan, reason, at,
            origin?.let { mapOf("world" to city.worldId, "x" to (it.x shr 4), "z" to (it.z shr 4),
                "loaded" to (level != null), "load_level" to level) },
            retry ?: spawnRetries[worker.id]?.takeIf { it.first == plan }?.second, spawn ?: previous?.spawn)
    }

    private fun spawnPoint(city: Settlement, world: World, storage: CivPosition, ordinal: Int,
                           viewers: List<CitizenViewer>, at: Long): CitizenSpawnSearch {
        val samples = mutableListOf<CitizenSpawnSample>()
        val rejections = linkedMapOf<String, Int>()
        var inspected = 0
        // Two admissions per pulse give at most 234 candidate checks across all cities.
        for (point in citizenSpawnCandidates(storage, ordinal)) {
            inspected++
            val location = point.location(world)
            val reason = when {
                !city.contains(point.x, point.z) -> "outside-city"
                !citizenControlClear(city, point) -> "control-obstructed"
                !citizenStandable(world, point) -> "not-standable"
                !activeAt(city.id, world.uid, point.point(), viewers) -> "sleeping"
                else -> {
                    val body = citizenStandingBounds(location, 1.0)
                    if (actors.values.any { actor ->
                        actor.npc.isValid && actor.worldId == world.uid &&
                            (citizenStandingBounds(actor.npc.location, actor.npc.scale).overlaps(body) ||
                                actor.route?.target?.let { citizenStandingBounds(it, actor.npc.scale).overlaps(body) } == true ||
                                actor.sleepPose?.takeIf { actor.sleeping }?.bounds?.overlaps(body) == true ||
                                actor.shelterPose?.takeIf { actor.sheltering }?.bounds?.overlaps(body) == true)
                    }) "occupied" else null
                }
            }
            if (samples.size < CITIZEN_SPAWN_SAMPLE_LIMIT || reason == null) {
                val level = citizenChunkLoadLevel(world, point.x shr 4, point.z shr 4)
                val blocks = if (level != null && point.y > world.minHeight && point.y + 1 < world.maxHeight)
                    listOf(-1, 0, 1).map { world.getBlockAt(point.x, point.y + it, point.z).type.name } else null
                val sample = CitizenSpawnSample(point, reason ?: "accepted", level, blocks?.get(0), blocks?.get(1), blocks?.get(2))
                if (samples.size == CITIZEN_SPAWN_SAMPLE_LIMIT) samples[samples.lastIndex] = sample else samples += sample
            }
            if (reason == null) return CitizenSpawnSearch(point, at, inspected, rejections, samples)
            rejections[reason] = (rejections[reason] ?: 0) + 1
        }
        return CitizenSpawnSearch(null, at, inspected, rejections, samples)
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
        actors.values.toList().filter { !it.npc.isValid ||
            !activeAt(it.cityId, it.worldId, it.npc.location.point(), viewers) }
            .forEach { remove(it, "sleeping") }
    }

    private fun activeAt(cityId: UUID, worldId: UUID, point: CitizenPoint, viewers: List<CitizenViewer>): Boolean =
        backgroundActive(cityId) || citizenHasViewer(worldId, point, viewers, viewerRange)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) = pruneViewers(event.player.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) = pruneViewers()

    private fun invalidateCampfire(block: org.bukkit.block.Block) {
        if (block.type == Material.CAMPFIRE || block.type == Material.SOUL_CAMPFIRE)
            campfires.invalidate(block.world.uid, block.x shr 4, block.z shr 4)
    }

    private fun invalidatePath(block: org.bukkit.block.Block) =
        paths.invalidateBlock(block.world.uid, CivPosition(block.x, block.y, block.z))

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
    fun onCampfireInteract(event: PlayerInteractEvent) { event.clickedBlock?.let(::invalidateCampfire) }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onWorldUnload(event: WorldUnloadEvent) {
        campfires.removeWorld(event.world.uid)
        paths.removeWorld(event.world.uid)
        actors.values.toList().filter { it.worldId == event.world.uid }.forEach { remove(it, "world-unloaded") }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChunkUnload(event: ChunkUnloadEvent) {
        campfires.invalidate(event.world.uid, event.chunk.x, event.chunk.z)
        paths.invalidateChunk(event.world.uid, event.chunk.x, event.chunk.z)
        actors.values.toList().filter { actor ->
            actor.worldId == event.world.uid && (actor.route?.chunks?.contains(event.chunk.x, event.chunk.z) == true ||
                actor.plan.storage.inChunk(event.chunk.x, event.chunk.z) ||
                actor.plan.workplace?.inChunk(event.chunk.x, event.chunk.z) == true ||
                actor.npc.isValid && actor.npc.location.let { it.blockX shr 4 == event.chunk.x && it.blockZ shr 4 == event.chunk.z })
        }.forEach { remove(it, "chunk-unloaded") }
    }

    private fun remove(actor: Actor, reason: String) {
        releaseCourierSource(actor, courierObservedAt)
        actors.remove(actor.workerId)
        actor.cycle.close()
        runCatching { stopCamp(actor, reason); cancelRoute(actor) }.onFailure { logFailure(actor, "cancel", it) }
        runCatching { threats.remove(actor.workerId) }.onFailure { logFailure(actor, "threat-retirement", it) }
        runCatching { actor.npc.remove() }.onFailure { logFailure(actor, "destroy", it) }
        statuses[actor.workerId] = reason
        CivsDiagnostics.event("citizen_retired", "city" to actor.cityId, "worker" to actor.workerId, "reason" to reason)
    }

    /** Terminal cleanup includes the actor's admitted client IDs, even if an observer cancels its copy. */
    fun closeAsync(): CompletableFuture<Void> {
        checkMainThread()
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
        for (actor in actors.values) { actor.cycle.close(); actor.route?.motion?.cancel(); actor.route?.previousMotion?.cancel() }
        actors.clear()
        completions.clear()
        harmCompletions.clear()
        supplyCompletions.clear()
        collectedCompletions.clear()
        pendingHarm.clear()
        spawnRetries.clear()
        admissions.clear()
        admissionOrigins.clear()
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

    private fun checkMainThread() = check(Bukkit.isPrimaryThread()) { "ArcCivs resident access must be on the server thread" }

    private class Actor(val cityId: UUID, val workerId: UUID, val worldId: UUID, val npc: PacketPlayerNpc, var plan: CitizenPlan) {
        val instanceId: UUID = UUID.randomUUID()
        var cycle = plan.cycle()
        val targets = mutableMapOf<CivPosition, CivPosition>()
        var wander = CitizenWander(workerId)
        val animation = CitizenWorkAnimation()
        val footsteps = CitizenFootsteps()
        var route: Route? = null
        var collisionResident: UUID? = null
        var residentYield: ResidentYield? = null
        var yieldRetryAfter = 0L
        // Physical yielding grants no progress; only subsequent accepted route travel replenishes this allowance.
        var yieldProgress = 2.0
        var lookYaw: Float? = null
        var lookPitch = 0f
        var observedYaw: Float? = null
        var observedSleeping = false
        var navigationEpisode: String? = null
        var navigationEpisodeProgress = 0.0
        var navigationEpisodeTick = 0L
        var navigationReportAfter = 0L
        var navigationSuppressed = 0
        val mineRouteEpisode = CivsMineRouteEpisode()
        var turnOrigin: CitizenPoint? = null
        var turnSinceTick = 0L
        var turnAmount = 0f
        var workHandoff: CitizenWorkHandoff? = null
        var reportedFailure = false
        var damageAfter = 0L
        var harmLogAfter = 0L
        var reportHarmCompletion = false
        var terrainObstructedAt: Long? = null
        var equipmentItems: List<CivResidentItem?>? = null
        var equipmentType: BuildingType? = null
        var careItem: Material? = null
        var sheltering = false
        var returningToWarehouse = false
        val social = CitizenCampRoutine(workerId)
        var sleeping = false
        var sleepOrigin: Location? = null
        var sleepPose: CitizenSleepPose? = null
        var shelter: CivPosition? = null
        var shelterPose: CitizenSleepPose? = null
        var shelterSearchIndex = 0
        var nextPlayAt = 0L
        var supplyPickup: SupplyPickup? = null
        var supplyFailure: Pair<UUID, String>? = null
        var supplyRetryAt = 0L
        var collectedPending: UUID? = null
        var collectionCycle: Long? = null
        var collectionRetryAt = 0L
        var collectionSeek: UUID? = null
        var collectionHome: CivPosition? = null
        var foodSource: UUID? = null
        var nextRecoveryAt = 0L
        var attacker: Monster? = null
        var combatUntil = 0L
        var combatDestination: CivPosition? = null
        var combatStatus: String? = null
        var combatRouteAfter = 0L
        var combatAttackAfter = 0L
        var courierPickupStartedAt: Long? = null
        var courierHeld = false
        var courierRetry = CitizenRetry()
        var courierRetryAfter = 0L
    }
    private class ResidentYield(val passing: UUID, val from: CitizenPoint, val target: CitizenPoint, val until: Long) {
        var holdUntil = 0L
        var steps = 0
    }

    private class Route(val from: CitizenPoint, val target: Location, val chunks: CitizenChunks, val startedAt: Long,
        val timeoutMillis: Long, val allowedBuildings: Set<UUID>, var motion: CitizenPacketRoute, val trip: CivsPaths.Trip?,
        val authored: CivPosition, val intermediate: Boolean = false, val idleAnchor: CivPosition? = null) {
        var completion: CitizenNavigationCompletion? = null
        var planningReported = false
        var mineFailureReported = false
        var storageReason: String? = null
        var storageBuilding: UUID? = null
        var path: CitizenPathSnapshot? = null
        var previousMotion: CitizenPacketRoute? = null
        var replanReason: String? = null
        var replanAttempts = 0
        var nativeCollisionTicks = 0
        var observedProgress = 0.0
        var progressSinceObservation = 0.0
        var progressSinceReplan = 0.0
        var progressTick = 0L
        val timing = CitizenRouteTiming(startedAt, timeoutMillis)
        var preparationReportAfter = startedAt + 2_000
        var preparationRetryAt: Long? = null

        fun motionReady(tick: Long, now: Long) {
            timing.ready(now)
            observedProgress = motion.forwardProgress
            progressSinceObservation = 0.0
            progressSinceReplan = 0.0
            progressTick = tick
        }

        fun timedOut(now: Long): Boolean = timing.timedOut(now, motion.searching)
    }
    private data class Completion(val workerId: UUID, val actorId: UUID, val ticket: UUID, val token: CitizenToken,
        val city: Settlement?, val error: Throwable?, val pickup: Boolean = false)
    private data class HarmCompletion(val workerId: UUID, val city: Settlement?, val error: Throwable?, val epoch: Long)
    private data class SupplyPickup(val ticket: UUID, val buildingId: UUID, val resumeWork: Boolean)
    private data class SupplyCompletion(val workerId: UUID, val actorId: UUID, val pickup: SupplyPickup,
        val city: Settlement?, val error: Throwable?)
    private data class CollectedCompletion(val workerId: UUID, val actorId: UUID, val ticket: UUID,
        val city: Settlement?, val error: Throwable?)

    private companion object {
        val NAVIGATION_DIAGNOSTIC = StructuredDebugLine("ARCCIVS_NAVIGATION_FAILED", maxFields = 48)
        val MINE_ROUTE_DIAGNOSTIC = StructuredDebugLine("ARCCIVS_MINE_ROUTE_BLOCKED")
        val MINE_ROUTE_RECOVERED = StructuredDebugLine("ARCCIVS_MINE_ROUTE_RECOVERED")
        val PREPARATION_FAILURES = setOf("search-budget", "capture-budget", "planner-busy", "planner-error",
            "chunk-unloaded", "cancelled", "broken-path", "passage-blocked")
    }
}

/** Consecutive native positions only; a route, spawn or teleport never fabricates footsteps. */
internal class CitizenFootsteps {
    private var previous: Location? = null

    fun reset() { previous = null }

    /** Seed only from the real pre-move position, including the first boundary crossed after a reset. */
    fun prime(position: Location, onGround: Boolean) {
        if (previous == null) observe(position, onGround)
    }

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

/** Owning-thread route clock: preparation/replanning never spends the allowance for walking. */
internal class CitizenRouteTiming(private val startedAt: Long, private val timeoutMillis: Long) {
    private var preparingSince = startedAt
    private var walkingSince: Long? = null
    private var walkedMillis = 0L
    private var observedElapsed = 0L

    fun ready(now: Long) {
        if (walkingSince == null) walkingSince = now
    }

    fun prepare(now: Long) {
        walkedMillis = elapsed(now)
        walkingSince = null
        preparingSince = now
    }

    fun elapsed(now: Long): Long {
        val current = walkedMillis + (walkingSince?.let { (now - it).coerceAtLeast(0) } ?: 0L)
        observedElapsed = maxOf(observedElapsed, current)
        return observedElapsed
    }
    fun preparationElapsed(now: Long): Long = if (walkingSince == null) (now - preparingSince).coerceAtLeast(0) else 0L
    fun timedOut(now: Long, searching: Boolean): Boolean = !searching && walkingSince != null && elapsed(now) > timeoutMillis
}

internal enum class CitizenNavigationOutcome { PENDING, ARRIVED, CANCELLED, COMPLETED_OUTSIDE_ARRIVAL, TIMED_OUT, STOPPED }
internal fun citizenNavigationOutcome(completion: CitizenNavigationCompletion?, arrived: Boolean,
    elapsedMillis: Long, timeoutMillis: Long, navigating: Boolean, searching: Boolean = false): CitizenNavigationOutcome = when {
    completion?.cancelReason != null -> CitizenNavigationOutcome.CANCELLED
    arrived -> CitizenNavigationOutcome.ARRIVED
    completion != null -> CitizenNavigationOutcome.COMPLETED_OUTSIDE_ARRIVAL
    searching -> CitizenNavigationOutcome.PENDING
    elapsedMillis > timeoutMillis -> CitizenNavigationOutcome.TIMED_OUT
    !navigating -> CitizenNavigationOutcome.STOPPED
    else -> CitizenNavigationOutcome.PENDING
}

internal data class CitizenPathSnapshot(val nodes: Int, val end: CitizenPoint, val truncated: Boolean)

internal fun citizenHasViewer(worldId: UUID, point: CitizenPoint, viewers: List<CitizenViewer>, range: Int): Boolean =
    viewers.any { it.worldId == worldId && it.point.distanceSquared(point) <= range.toDouble() * range }
internal fun citizenSpawnBudget(active: Int, maximum: Int): Int = (maximum.coerceIn(0, 48) - active).coerceIn(0, 2)

internal const val CITIZEN_SPAWN_CANDIDATE_LIMIT = 13 * 9
private const val CITIZEN_SPAWN_SAMPLE_LIMIT = 16

/** Preserve the nearest floor first, then inspect only four blocks above and below it. */
internal fun citizenSpawnCandidates(origin: CivPosition, ordinal: Int): Sequence<CivPosition> = sequence {
    val offsets = listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, -1 to 1,
        1 to -1, -1 to -1, 2 to 0, -2 to 0, 0 to 2, 0 to -2)
    for (dy in listOf(0, 1, -1, 2, -2, 3, -3, 4, -4)) for (index in offsets.indices) {
        val (dx, dz) = offsets[Math.floorMod(index + ordinal, offsets.size)]
        val x = origin.x.toLong() + dx
        val y = origin.y.toLong() + dy
        val z = origin.z.toLong() + dz
        if (x in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() && y in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
            z in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) yield(CivPosition(x.toInt(), y.toInt(), z.toInt()))
    }
}

/** Failed native target checks consume an admission too, so they must not monopolize the next pulse. */
internal fun <T> citizenRotateAfterAdmission(actors: MutableMap<UUID, T>, workerId: UUID) {
    val actor = actors.remove(workerId) ?: return
    actors[workerId] = actor
}

// Entrances retain the domain bound; a real actor can stop up to 0.65 blocks from an entrance.
internal const val CITIZEN_ROUTE_ARRIVAL_TOLERANCE = 1.0
/** Packet routes use the actual standing cell centre at both intermediate and final nodes. */
internal fun citizenNearEndpoint(from: CitizenPoint, target: CivPosition): Boolean =
    abs(from.y - target.y) <= 0.5 && from.distanceSquared(target.point()) <= 0.65 * 0.65

internal fun citizenRouteWithinReach(from: CitizenPoint, to: CitizenPoint): Boolean {
    val maximum = CivsRules.MAX_WORK_ROUTE_DISTANCE + CITIZEN_ROUTE_ARRIVAL_TOLERANCE
    return from.distanceSquared(to) <= maximum * maximum
}
internal fun citizenStorageNeedsReset(previous: CivPosition, next: CivPosition, actualPosition: CitizenPoint): Boolean =
    previous != next && !citizenRouteWithinReach(actualPosition, next.point())

internal data class CitizenToken(val assignmentId: UUID?, val completedCycles: Long, val nextDeliveryAt: Long, val constructionId: UUID?,
    val shipmentId: UUID? = null, val craftJobId: UUID? = null,
    val researchProjectId: UUID? = null, val industryRecipe: String? = null)
private data class CitizenAdmission(val cityId: UUID, val workerId: UUID, val plan: CitizenPlan?, val reason: String,
    val at: Long, val chunk: Map<String, Any?>?, val retry: CitizenRetry?, val spawn: CitizenSpawnSearch?)
private data class CitizenSpawnSample(val point: CivPosition, val reason: String, val loadLevel: Chunk.LoadLevel?,
    val support: String?, val feet: String?, val head: String?)
private data class CitizenSpawnSearch(val point: CivPosition?, val at: Long, val inspected: Int,
    val rejections: Map<String, Int>, val samples: List<CitizenSpawnSample>)
internal data class CitizenPlan(val token: CitizenToken, val storage: CivPosition, val workplace: CivPosition?,
    val type: BuildingType?, val workMillis: Long, val idleReason: String?, val completeAtWorkplace: Boolean = false,
    val courier: CivCourierPlan? = null, val worldWork: CivWorldWork? = null, val suppliesNeeded: Boolean = false,
    val autonomousWork: CivBuilding? = null, val industryWork: CivIndustryWork? = null) {
    val spawnOrigin: CivPosition get() = if (courier?.cargo?.sourceWorkerId != null) storage else courier?.cargo?.sourceEntrance ?: storage
    fun travelTarget(phase: CitizenPhase): CivPosition =
        if (phase == CitizenPhase.TO_WORK || phase == CitizenPhase.RETURNING && completeAtWorkplace) requireNotNull(workplace) else storage
    fun cycle() = CitizenCycle(token, workMillis, completeAtWorkplace, courier != null, courier?.carrying == true,
        worldWork != null || type == BuildingType.GUARD_TOWER ||
            type == BuildingType.BUILDER_HUT && idleReason in listOf(null, "placing") && !suppliesNeeded)
}

/** Pickup publication changes carried quantity, never the identity of the journey already observed. */
internal fun citizenPlansContinue(previous: CitizenPlan, next: CitizenPlan): Boolean {
    val first = previous.courier ?: return false
    val second = next.courier ?: return false
    return previous.token == next.token && previous.storage == next.storage && previous.workplace == next.workplace &&
        first.destinationBuildingId == second.destinationBuildingId && first.cargo.id == second.cargo.id &&
        first.cargo.warehouseId == second.cargo.warehouseId && first.cargo.sourceBuildingId == second.cargo.sourceBuildingId &&
        first.cargo.targetBuildingId == second.cargo.targetBuildingId && first.cargo.material == second.cargo.material &&
        first.cargo.sourceWorkerId == second.cargo.sourceWorkerId
}

internal fun citizenCurrentPlan(previous: CitizenPlan, phase: CitizenPhase, current: () -> CitizenPlan?): CitizenPlan? {
    if (previous.courier != null && (phase in setOf(CitizenPhase.PICKING_UP, CitizenPhase.DELIVERING) ||
            previous.courier.cargo.sourceWorkerId != null && !previous.courier.carrying &&
                phase !in setOf(CitizenPhase.IDLE, CitizenPhase.PAUSED, CitizenPhase.CLOSED)) ||
        previous.worldWork != null && (phase == CitizenPhase.DELIVERING ||
            phase == CitizenPhase.READY && !previous.worldWork.cropCare)) return previous
    val next = current()
    if (next != null && phase !in setOf(CitizenPhase.IDLE, CitizenPhase.PAUSED, CitizenPhase.CLOSED) &&
        next.workplace == previous.workplace && next.storage == previous.storage) {
        if (previous.autonomousWork != null && next.token.copy(industryRecipe = previous.token.industryRecipe) == previous.token &&
            next.idleReason in listOf(null, "waiting-reassignment", "waiting-orders", "waiting-inputs")) return previous
    }
    return next
}

internal data class CitizenWorkHandoff(val buildingId: UUID, val cycle: Long, val at: CitizenPoint, val tick: Long,
    val activity: String)

/** Only a committed world action admits a short discovery handoff in the same local activity. */
internal fun citizenWorkActivityStatus(plan: CitizenPlan, phase: CitizenPhase, actual: CitizenPoint,
    handoff: CitizenWorkHandoff?, tick: Long): String? {
    if (phase == CitizenPhase.PAUSED) return null
    val continues = handoff != null && handoff.buildingId == plan.token.assignmentId &&
        handoff.cycle == plan.token.completedCycles && tick - handoff.tick in 0L..20L &&
        actual.distanceSquared(handoff.at) <= 4.0 * 4.0
    val work = plan.worldWork
    if (plan.idleReason == null && phase == CitizenPhase.TO_WORK && work != null &&
        actual.distanceSquared(work.standing.point()) <= 4.0 * 4.0 &&
        (plan.type == BuildingType.FARM && work.status == "working-field" || continues && handoff?.activity == work.status))
        return work.status
    // Keep only the brief committed-action -> next-target handoff in the same activity.
    // A search lasting beyond one second is still visible; this never covers a failure.
    return if (plan.idleReason == "searching-work" && continues) handoff?.activity else null
}

internal fun citizenSummonEligible(city: Settlement, worker: CivWorker, buildingId: UUID): Boolean {
    if (CivsResidents.isChild(worker)) return false
    val assignment = worker.assignment?.let(city::building) ?: return false
    return assignment.id == buildingId || city.construction?.buildingId == buildingId &&
        CivsRules.isBuilder(city, assignment) && CivsRules.builderCanWork(city, assignment)
}

internal fun citizenPlan(city: Settlement, worker: CivWorker, courier: CivCourierPlan? = null): CitizenPlan? {
    if (CivsResidents.isChild(worker)) {
        val home = citizenHomeBuilding(city, worker) ?: return null
        return CitizenPlan(CitizenToken(null, 0, 0, null), citizenIdleAnchor(city, home), null, null, 0, "playing")
    }
    if (courier != null) {
        val destination = city.building(courier.destinationBuildingId)?.control ?: return null
        val source = courier.cargo.sourceEntrance.takeIf { courier.cargo.sourceWorkerId != null }
            ?: city.building(courier.cargo.sourceBuildingId)?.control
            ?: courier.cargo.sourceEntrance.takeIf { courier.carrying } ?: return null
        return CitizenPlan(CitizenToken(courier.cargo.warehouseId, worker.completedCycles, 0, null, courier.cargo.id),
            destination, source, BuildingType.WAREHOUSE, 1_000, null, courier = courier)
    }
    if (worker.cargo != null) return null
    val assigned = worker.assignment?.let(city::building)
    val autonomous = city.autonomy != null && city.inventory == null
    val workType = assigned?.let { CivsRules.effectiveWorkType(city, it) }
    val research = city.research?.project.takeIf { workType == BuildingType.UNIVERSITY && CivsResearch.university(city)?.id == assigned?.id }
    val industry = assigned?.takeIf { autonomous }?.let { CivsIndustry.autonomousWork(city, it) }
    val builder = workType == BuildingType.BUILDER_HUT
    val order = city.construction.takeIf { builder }
    val workBuilding = order?.buildingId?.let(city::building) ?: order?.plannedBuilding
    val workplace = if (builder) workBuilding?.control ?: order?.plannedBuilding?.entrance ?: workBuilding?.entrance
        else if (autonomous || workType == BuildingType.GUARD_TOWER) assigned?.entrance else assigned?.control
    val depot = city.buildings.filter(CivsRules::isDepot)
        .minByOrNull { (if (autonomous) it.entrance else it.control ?: it.entrance).point()
            .distanceSquared((workplace ?: city.center).point()) }
    val storage = when {
        autonomous -> depot?.entrance ?: return null
        // Physical builders take supplies from their assigned hut or their own bag.
        // The construction target and a nearby town hall are never their depot.
        city.inventory != null && assigned != null -> assigned.control ?: assigned.entrance
        workType in setOf(BuildingType.TOWN_HALL, BuildingType.WAREHOUSE) && assigned != null -> assigned.control ?: assigned.entrance
        else -> depot?.control ?: depot?.entrance ?: return null
    }
    val reason = when {
        autonomous && city.autonomy?.paused == true -> "autonomy-paused"
        worker.assignment == null -> "unassigned"
        assigned == null -> "building-unavailable"
        !builder && city.construction?.buildingId == assigned.id -> "building-unavailable"
        autonomous && CivsAutonomy.waitingReassignment(city, worker) -> "waiting-reassignment"
        workType == BuildingType.UNIVERSITY && research == null -> "no-research"
        workType == BuildingType.UNIVERSITY && research?.paused == true -> "research-paused"
        workType == BuildingType.UNIVERSITY && research?.funded != true -> "research-unfunded"
        autonomous && workType in CivsIndustry.processorTypes && industry == null -> "waiting-orders"
        workType == BuildingType.WAREHOUSE && assigned.phase == BuildingPhase.ACTIVE -> "waiting-orders"
        builder && order == null -> "no-construction"
        builder && !CivsRules.builderCanWork(city, assigned) -> "building-unavailable"
        builder && order?.phase == ConstructionPhase.WAITING_MATERIALS -> "waiting-materials"
        builder && order?.phase == ConstructionPhase.PAUSED -> "construction-paused"
        builder && order?.phase in setOf(ConstructionPhase.READY_TO_PLACE, ConstructionPhase.PLACING) -> "placing"
        builder && !autonomous && city.inventory == null && depot?.control == null && depot?.phase == BuildingPhase.ACTIVE -> "storage-unavailable"
        workType == BuildingType.GUARD_TOWER && assigned.phase == BuildingPhase.ACTIVE -> null
        !builder && !CivsRules.productionAvailable(city, assigned) -> "building-unavailable"
        workplace == null -> "building-unavailable"
        else -> null
    }
    val recipeKey = industry?.recipe?.id ?: assigned?.takeIf { !autonomous &&
        (it.type in setOf(BuildingType.SMELTERY, BuildingType.CRUSHER) ||
            it.type == BuildingType.BLACKSMITH && CivsProduction.material(it) in setOf("BUCKET", "SHEARS")) }?.let(CivsProduction::material)
    return CitizenPlan(CitizenToken(worker.assignment, worker.completedCycles, worker.nextDeliveryAt, order?.id,
        researchProjectId = research?.id, industryRecipe = recipeKey),
        storage, workplace, workType,
        if (workType == BuildingType.GUARD_TOWER) Long.MAX_VALUE
        else if (builder && (order?.payment != null || city.autonomy != null)) CivsRules.BUILDER_SESSION_INTERVAL
        else workType?.let(CivsRules::deliveryInterval) ?: 0,
        reason, builder || city.inventory != null,
        autonomousWork = assigned?.takeIf { autonomous && reason == null && it.type != BuildingType.FORESTER &&
            (CivsRules.resource(it.type) != null || industry != null || it.type == BuildingType.UNIVERSITY) }, industryWork = industry)
}

internal fun citizenControlBuilding(city: Settlement, point: CivPosition): CivBuilding? =
    civsProtectedBuildings(city).firstOrNull { it.control == point }

/** A control is furniture, including for the worker whose route enters that building. */
internal fun citizenControlClear(city: Settlement, feet: CivPosition): Boolean =
    civsProtectedBuildings(city).none { building -> building.control?.let {
        feet.x == it.x && feet.z == it.z && feet.y.toLong() in it.y.toLong()..(it.y.toLong() + 1)
    } == true }

/** Include the native player's width so a diagonal corner cannot turn a barrel into a shortcut. */
internal fun citizenControlStepClear(city: Settlement, from: CitizenPoint, to: CitizenPoint): Boolean {
    val origin = Vector(from.x, from.y, from.z)
    val destination = Vector(to.x, to.y, to.z)
    val delta = destination.clone().subtract(origin)
    val distance = delta.length()
    return civsProtectedBuildings(city).none { building ->
        val p = building.control ?: return@none false
        val obstacle = BoundingBox(p.x - 0.3, p.y - 1.8, p.z - 0.3, p.x + 1.3, p.y + 1.01, p.z + 1.3)
        obstacle.contains(destination) || !obstacle.contains(origin) && distance > 0 &&
            obstacle.rayTrace(origin, delta.clone().normalize(), distance) != null
    }
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
            citizenControlClear(city, point) && safe(point)) return point
    }
    return null
}

internal fun citizenCollectionIdle(plan: CitizenPlan, phase: CitizenPhase): Boolean = plan.courier == null &&
    phase !in setOf(CitizenPhase.WORKING, CitizenPhase.READY, CitizenPhase.DELIVERING, CitizenPhase.PICKING_UP) &&
    plan.idleReason in setOf("unassigned", "waiting-orders", "waiting-growth", "no-construction", "building-unavailable")

/** A route ends beside the real container; the final native ray remains authoritative at deposit time. */
internal fun citizenCollectedHomeTarget(city: Settlement, home: CivBuilding, world: World, target: CivPosition): CivPosition? {
    if (!citizenChunkReady(world, target.x shr 4, target.z shr 4)) return null
    val levels = listOf(home.entrance.y, target.y, target.y - 1, target.y + 1, target.y - 2, target.y - 3).distinct()
    for (y in levels) for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
        val point = target.copy(x = target.x + dx, y = y, z = target.z + dz)
        if (city.contains(point.x, point.z) && citizenControlClear(city, point) && citizenStandable(world, point) &&
            city.buildings.none { it.id != home.id && it.bounds.contains(point.x, point.y, point.z) } &&
            citizenCanReachBlock(world, point.location(world).add(0.0, 1.5, 0.0), target, 3.0)) return point
    }
    return null
}

internal fun citizenCollectedItemTarget(city: Settlement, world: World, item: Location): CivPosition? {
    for (dy in listOf(0, 1, -1)) for ((dx, dz) in listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
        val point = CivPosition(item.blockX + dx, item.blockY + dy, item.blockZ + dz)
        if (city.contains(point.x, point.z) && citizenControlClear(city, point) && citizenStandable(world, point)) return point
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

/** Batched actions keep one actual reachable focus (for example the trunk's lowest cut). */
internal fun citizenWorkFocus(work: CivWorldWork): CivPosition? {
    // A prepaid tree keeps its reachable root until the final batch, while earlier batches clear its crown.
    work.forestry?.let { progress ->
        val job = progress.job
        if (work.focus == job.root && job.root in job.roots && progress.next > job.next &&
            job.cells.takeLast(job.roots.size).any { job.position(it) == job.root }) return job.root
    }
    return (work.focus ?: work.changes.minByOrNull { it.position.point().distanceSquared(work.standing.point()) }?.position)
        ?.takeIf { focus -> work.changes.any { it.position == focus } || work.cropCare && work.focus == focus &&
            work.changes.all { it.position == focus.copy(y = focus.y - 1) } }
}

/** A short eye-to-block ray is the authority for work and storage access; no chunk is loaded. */
internal fun citizenCanReachBlock(npc: PacketPlayerNpc, target: CivPosition, reach: Double = CITIZEN_BLOCK_REACH): Boolean =
    npc.isValid && citizenCanReachBlock(npc.world, npc.location.clone().add(0.0, npc.eyeHeight, 0.0), target, reach)

internal const val CITIZEN_BLOCK_REACH = 3.0
internal const val CITIZEN_MINE_REACH = 4.5
internal fun citizenWorkReach(type: BuildingType?): Double = if (type == BuildingType.QUARRY) CITIZEN_MINE_REACH else CITIZEN_BLOCK_REACH

/** Planning uses the same ray contract as the observed actor and its durable receipt. */
internal fun citizenCanReachBlock(world: World, eye: Location, target: CivPosition, reach: Double = CITIZEN_BLOCK_REACH): Boolean =
    citizenReachableBlockPoint(world, eye, target, reach) != null

/** A block's centre can be hidden while one of its faces remains visible, notably below a ladder. */
internal fun citizenBlockAimPoints(eye: Location, target: CivPosition): List<Location> {
    if (!eye.x.isFinite() || !eye.y.isFinite() || !eye.z.isFinite()) return emptyList()
    val x0 = target.x + .001; val x1 = target.x + .999
    val y0 = target.y + .001; val y1 = target.y + .999
    val z0 = target.z + .001; val z1 = target.z + .999
    val x = eye.x.coerceIn(x0, x1)
    val y = eye.y.coerceIn(y0, y1)
    val z = eye.z.coerceIn(z0, z1)
    return listOf(Location(eye.world, target.x + .5, target.y + .5, target.z + .5),
        Location(eye.world, x, y0, z), Location(eye.world, x, y1, z),
        Location(eye.world, x0, y, z), Location(eye.world, x1, y, z),
        Location(eye.world, x, y, z0), Location(eye.world, x, y, z1)).distinct()
}

internal fun citizenReachableBlockPoint(world: World, eye: Location, target: CivPosition, reach: Double = CITIZEN_BLOCK_REACH): Location? {
    if (eye.world != world || !eye.x.isFinite() || !eye.y.isFinite() || !eye.z.isFinite() ||
        target.y !in world.minHeight until world.maxHeight) return null
    val points = citizenBlockAimPoints(eye, target).filter { eye.distanceSquared(it) <= reach * reach }
    if (points.isEmpty() || !citizenBlockTraceReady(world, eye, target)) return null
    return points.firstOrNull { citizenTraceBlockPoint(world, eye, target, it) }
}

/** A single bounded sample; Worksites keeps its own cursor and shared eight-ray pulse budget. */
internal fun citizenCanReachBlockPoint(world: World, eye: Location, target: CivPosition, aim: Location,
    reach: Double = CITIZEN_BLOCK_REACH): Boolean {
    if (aim.world != world || !aim.x.isFinite() || !aim.y.isFinite() || !aim.z.isFinite() ||
        aim.x < target.x || aim.x >= target.x + 1.0 || aim.y < target.y || aim.y >= target.y + 1.0 ||
        aim.z < target.z || aim.z >= target.z + 1.0 || eye.world != world ||
        eye.distanceSquared(aim) > reach * reach) return false
    return citizenBlockTraceReady(world, eye, target) && citizenTraceBlockPoint(world, eye, target, aim)
}

private fun citizenBlockTraceReady(world: World, eye: Location, target: CivPosition): Boolean {
    if (eye.world != world || !eye.x.isFinite() || !eye.y.isFinite() || !eye.z.isFinite() ||
        target.y !in world.minHeight until world.maxHeight) return false
    for (x in (minOf(eye.blockX, target.x) shr 4)..(maxOf(eye.blockX, target.x) shr 4))
        for (z in (minOf(eye.blockZ, target.z) shr 4)..(maxOf(eye.blockZ, target.z) shr 4))
            if (!world.isChunkLoaded(x, z)) return false
    return true
}

private fun citizenTraceBlockPoint(world: World, eye: Location, target: CivPosition, aim: Location): Boolean {
    val delta = aim.toVector().subtract(eye.toVector())
    val distance = delta.length()
    if (distance < .001) return true
    val hit = world.rayTraceBlocks(eye, delta.multiply(1.0 / distance), distance,
        FluidCollisionMode.ALWAYS, true) ?: return true
    val block = hit.hitBlock ?: return false
    return block.x == target.x && block.y == target.y && block.z == target.z
}

private fun citizenStorageReady(world: World, control: CivPosition): Boolean =
    world.isChunkLoaded(control.x shr 4, control.z shr 4) &&
        world.getBlockAt(control.x, control.y, control.z).type == org.bukkit.Material.BARREL

internal fun citizenLookDirection(eye: Location, focus: Location): Pair<Float, Float> = eye.clone().let {
    it.direction = focus.toVector().subtract(eye.toVector())
    it.yaw to it.pitch
}

internal fun citizenWorkingTravel(plan: CitizenPlan, phase: CitizenPhase, sheltering: Boolean): Boolean =
    plan.idleReason in listOf(null, "placing") && !sheltering &&
        phase in setOf(CitizenPhase.TO_STORAGE, CitizenPhase.TO_WORK, CitizenPhase.RETURNING)

/** A cart belongs to an observed shipment, never to the resident's profession or idle wandering. */
internal fun citizenCourierCart(plan: CitizenPlan, phase: CitizenPhase, sheltering: Boolean): Boolean =
    plan.courier != null && plan.idleReason == null && !sheltering && phase in setOf(
        CitizenPhase.TO_WORK, CitizenPhase.WORKING, CitizenPhase.PICKUP_READY, CitizenPhase.PICKING_UP,
        CitizenPhase.RETURNING, CitizenPhase.READY, CitizenPhase.DELIVERING)

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

/** A gate can overlap feet one block above its base; it has no upper half like a door. */
internal fun citizenOpenRouteDoors(city: Settlement, allowed: Set<UUID>, at: Location, destination: Location,
    blocked: (UUID, CivPosition) -> Boolean): Boolean {
    check(Bukkit.isPrimaryThread()) { "Resident passages require the server thread" }
    val world = destination.world ?: return false
    if (world.uid != city.worldId || at.world?.uid != world.uid ||
        !world.isChunkLoaded(destination.blockX shr 4, destination.blockZ shr 4)) return false
    val body = BoundingBox(destination.x - .299, destination.y + .001, destination.z - .299,
        destination.x + .299, destination.y + 1.799, destination.z + .299)
    return (-1..1).all { dy ->
        val point = CivPosition(destination.blockX, destination.blockY + dy, destination.blockZ)
        if (dy == -1) {
            if (point.y !in world.minHeight until world.maxHeight) return@all true
            val block = world.getBlockAt(point.x, point.y, point.z)
            if (block.blockData !is Gate || block.collisionShape.boundingBoxes.none {
                    body.overlaps(it.clone().shift(point.x.toDouble(), point.y.toDouble(), point.z.toDouble()))
                }) return@all true
        }
        citizenOpenRouteDoor(city, allowed, at, point, blocked)
    }
}

/** Open only a nearby path door or gate; foreign footprints and rejected native events stay intact. */
internal fun citizenOpenRouteDoor(city: Settlement, allowed: Set<UUID>, at: Location, point: CivPosition,
    blocked: (UUID, CivPosition) -> Boolean): Boolean {
    check(Bukkit.isPrimaryThread()) { "Resident passages require the server thread" }
    val world = at.world ?: return false
    if (world.uid != city.worldId || point.y !in world.minHeight until world.maxHeight ||
        !world.isChunkLoaded(point.x shr 4, point.z shr 4)) return false
    if (citizenRouteOpenable(world.getBlockAt(point.x, point.y, point.z).blockData) == null) return true
    fun passage() = if (!world.isChunkLoaded(point.x shr 4, point.z shr 4)) null else when (world.getBlockAt(point.x, point.y, point.z).blockData) {
        is Door -> citizenDoorPair(world, point)?.toList()
        is Gate -> listOf(world.getBlockAt(point.x, point.y, point.z))
        else -> null
    }
    val blocks = passage() ?: return false
    val materials = blocks.map { it.type }
    val lower = blocks.first()
    if (at.distanceSquared(Location(world, lower.x + 0.5, lower.y.toDouble(), lower.z + 0.5)) > 2.5 * 2.5 ||
        blocks.any { block ->
            val p = CivPosition(block.x, block.y, block.z)
            !citizenDoorAllowed(city, allowed, p) || blocked(world.uid, p)
        }) return false
    if (blocks.all { citizenRouteOpenable(it.blockData)?.isOpen == true }) return true
    val event = CivsResidentOpenDoorEvent(city.id, lower)
    Bukkit.getPluginManager().callEvent(event)
    if (event.isCancelled) return false
    // A native event listener may replace a block; never open its replacement or restore captured data.
    val current = passage() ?: return false
    if (current.map { it.type } != materials || current.any { blocked(world.uid, CivPosition(it.x, it.y, it.z)) }) return false
    for (block in current) {
        val state = citizenRouteOpenable(block.blockData) ?: return false
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
    if (city.autonomy != null) plan.industryWork?.let { return CivsIndustry.room(city, it) }
    if (city.autonomy != null && plan.type == BuildingType.FORESTER) {
        val work = plan.worldWork ?: return true
        return try { CivsForestry.autoStock(city, work); true } catch (_: CivsRejection) { false }
    }
    if (city.inventory != null && plan.type in CivsWorksites.TYPES && plan.worldWork?.outputs?.isNotEmpty() == true) {
        val worker = city.workers.firstOrNull { it.id == plan.worldWork.workerId } ?: return false
        return try {
            CivsEquipment.collectSupplies(CivsEquipment.consumeInputs(worker, plan.worldWork.inputs), plan.worldWork.outputs)
            true
        } catch (failure: CivsRejection) {
            if (failure.reason == "resident-inventory-full") false else throw failure
        }
    }
    // Item storage checks actual container space at its durable delivery boundary.
    if (city.inventory != null) return true
    val building = plan.token.assignmentId?.let(city::building) ?: return true
    val resource = CivsRules.resource(building.type) ?: return true
    return CivsRules.capacity(city) - city.stock[resource] >= CivsRules.deliveryAmount(building)
}

/** Transient visual movement has no production token, arrival grant or offline catch-up. */
internal class CitizenWander(workerId: UUID) {
    private val random = Random(workerId.mostSignificantBits xor java.lang.Long.rotateLeft(workerId.leastSignificantBits, 23))
    var step: Int = 0
        private set
    private var nextAt: Long? = null
    fun ready(now: Long): Boolean {
        if (nextAt == null) schedule(now, 100L + random.nextInt(301))
        return now >= requireNotNull(nextAt)
    }
    fun reject(now: Long) {
        step = if (step == Int.MAX_VALUE) 0 else step + 1
        schedule(now, 100L + random.nextInt(251))
    }
    fun finish(now: Long, arrived: Boolean) {
        val next = (step.toLong() / CITIZEN_WANDER_HEIGHTS.size + 1) * CITIZEN_WANDER_HEIGHTS.size
        step = if (next > Int.MAX_VALUE) 0 else next.toInt()
        // The existing five-tick handoff supplies a natural pause after a successful walk.
        schedule(now, if (arrived) 0L else 2_500L + random.nextInt(1_501))
    }
    private fun schedule(now: Long, delay: Long) {
        nextAt = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
    }
}

private val CITIZEN_WANDER_OFFSETS = (-7..7).flatMap { x ->
    (-7..7).filter { z -> x * x + z * z in 4..49 }.map { z -> x to z }
}
private val CITIZEN_WANDER_HEIGHTS = listOf(0, 1, -1, 2, -2)
private const val CITIZEN_IDLE_RADIUS = 8.0

internal fun citizenIdleBuilding(city: Settlement, worker: CivWorker): CivBuilding? =
    worker.assignment?.let(city::building) ?: citizenHomeBuilding(city, worker)

/** An interior barrel can be farther from the door than the entire leisure radius. */
internal fun citizenIdleAnchor(@Suppress("UNUSED_PARAMETER") city: Settlement, building: CivBuilding): CivPosition =
    building.entrance

private fun citizenWithinIdleArea(anchor: CivPosition, at: CitizenPoint): Boolean =
    (at.x - (anchor.x + .5)) * (at.x - (anchor.x + .5)) +
        (at.z - (anchor.z + .5)) * (at.z - (anchor.z + .5)) <= CITIZEN_IDLE_RADIUS * CITIZEN_IDLE_RADIUS &&
        at.y in anchor.y - 2.0..anchor.y + 2.0

/** An unreachable optional destination does not displace a safely standing resident. */
internal fun citizenSafeIdlePosition(world: World, anchor: CivPosition, at: CitizenPoint,
    buildings: Collection<CivBuilding> = emptyList()): Boolean {
    if (!citizenWithinIdleArea(anchor, at) || !citizenPacketSupported(world, at) ||
        !citizenPacketBodyClear(world, at, false)) return false
    val feet = Location(world, at.x, at.y, at.z)
    return citizenIdleTerrain(world, CivPosition(feet.blockX, feet.blockY, feet.blockZ), buildings) &&
        citizenSleepClear(world, citizenStandingBounds(feet, 1.0))
}

/** A displaced resident may walk back, but a new idle path cannot extend its original distance or height. */
internal fun citizenIdleRouteAllowed(anchor: CivPosition, from: CitizenPoint, point: CivPosition): Boolean {
    fun horizontal(x: Double, z: Double) = (x - (anchor.x + 0.5)) * (x - (anchor.x + 0.5)) +
        (z - (anchor.z + 0.5)) * (z - (anchor.z + 0.5))
    val radiusSquared = maxOf(CITIZEN_IDLE_RADIUS * CITIZEN_IDLE_RADIUS, horizontal(from.x, from.z))
    return horizontal(point.x + 0.5, point.z + 0.5) <= radiusSquared &&
        point.y.toDouble() in minOf(anchor.y - 2.0, from.y)..maxOf(anchor.y + 2.0, from.y)
}

/** Authored log floors are walkable; outside active buildings, idle routes avoid trees and climbs. */
internal fun citizenIdleTerrain(world: World, point: CivPosition, buildings: Collection<CivBuilding> = emptyList()): Boolean {
    if (point.y <= world.minHeight || point.y + 1 >= world.maxHeight ||
        !world.isChunkLoaded(point.x shr 4, point.z shr 4)) return false
    val feet = world.getBlockAt(point.x, point.y, point.z).type
    val support = world.getBlockAt(point.x, point.y - 1, point.z).type
    val head = world.getBlockAt(point.x, point.y + 1, point.z).type
    return feet !in CITIZEN_LEISURE_HAZARDS && support !in CITIZEN_LEISURE_HAZARDS && head !in CITIZEN_LEISURE_HAZARDS &&
        !Tag.CLIMBABLE.isTagged(feet) && !Tag.CLIMBABLE.isTagged(support) &&
        !Tag.LEAVES.isTagged(support) && (!Tag.LOGS.isTagged(support) ||
            buildings.any { it.phase == BuildingPhase.ACTIVE && it.bounds.contains(point.x, point.y - 1, point.z) })
}

private val CITIZEN_LEISURE_HAZARDS = setOf(Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.FIRE,
    Material.SOUL_FIRE, Material.LAVA, Material.MAGMA_BLOCK, Material.CACTUS, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW)

/** One candidate per actor admission. Native support/headroom checks belong to the bounded pulse. */
internal fun citizenWanderTarget(city: Settlement, storage: CivPosition, workerId: UUID, step: Int,
    allowedBuildings: Set<UUID> = emptySet(), from: CitizenPoint? = null, yaw: Float = 0f): CivPosition? {
    val seed = workerId.mostSignificantBits xor java.lang.Long.rotateLeft(workerId.leastSignificantBits, 23)
    val offset = Math.floorDiv(step, CITIZEN_WANDER_HEIGHTS.size)
    // Each horizontal attempt gets an independent reproducible choice, not the next vertex of a shared ring.
    val random = Random(seed xor (offset.toLong() * -7046029254386353131L))
    val dy = CITIZEN_WANDER_HEIGHTS[Math.floorMod(step, CITIZEN_WANDER_HEIGHTS.size)]
    // Pure geometry only: prefer a useful leg ahead over a tiny shuffle or another about-face.
    // Native support/headroom still consumes just one existing admission check.
    return List(if (from == null) 1 else 6) {
        val (dx, dz) = CITIZEN_WANDER_OFFSETS[random.nextInt(CITIZEN_WANDER_OFFSETS.size)]
        val x = storage.x.toLong() + dx
        val y = storage.y.toLong() + dy
        val z = storage.z.toLong() + dz
        if (x !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || y !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
            z !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) null
        else CivPosition(x.toInt(), y.toInt(), z.toInt()).takeIf { point ->
            citizenRouteCellAllowed(city, allowedBuildings, point.x, point.z) && citizenControlClear(city, point)
        }
    }.filterNotNull().maxByOrNull { target ->
        if (from == null) 0.0 else {
            val dx = target.x + .5 - from.x
            val dz = target.z + .5 - from.z
            val distance = kotlin.math.hypot(dx, dz)
            val angle = Math.toRadians(yaw.toDouble())
            if (distance < 3.0) -10.0 + distance
            else (-dx * kotlin.math.sin(angle) + dz * kotlin.math.cos(angle)) / distance + distance * .05
        }
    }
}

internal fun citizenStandable(world: World, point: CivPosition): Boolean {
    if (!citizenChunkReady(world, point.x shr 4, point.z shr 4) ||
        point.y <= world.minHeight || point.y + 1 >= world.maxHeight ||
        !citizenPacketCampfireClear(world, point.point())) return false
    val feet = world.getBlockAt(point.x, point.y, point.z)
    val head = world.getBlockAt(point.x, point.y + 1, point.z)
    val floor = world.getBlockAt(point.x, point.y - 1, point.z)
    val support = floor.boundingBox
    if (feet.type == org.bukkit.Material.LADDER || head.type == org.bukkit.Material.LADDER)
        return citizenPacketBodyClear(world, point.point(), false) && citizenPacketSupported(world, point.point())
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
    fun deferWork(now: Long) { if (phase == CitizenPhase.WORKING) observedAt = now }
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
    fun resumeAfterWander() {
        if (phase != CitizenPhase.PAUSED) return
        resume()
        if (phase == CitizenPhase.READY) phase = CitizenPhase.RETURNING
    }
    fun close() { phase = CitizenPhase.CLOSED; ticket = null; deliveryRetryAt = null }
    fun status(): String = when (phase) {
        CitizenPhase.RETURNING -> when {
            pickupAtWorkplace -> "carrying-cargo"
            completeAtWorkplace -> "moving-to-work"
            else -> "moving-to-storage"
        }
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

internal class CitizenRetry(private val exhaustedCooldownMillis: Long? = null) {
    var attempts = 0
        private set
    private var retryAt = 0L
    val exhausted: Boolean get() = attempts >= 3
    val nextAttemptAt: Long? get() = retryAt.takeIf { !exhausted || exhaustedCooldownMillis != null }
    fun ready(now: Long): Boolean = nextAttemptAt?.let { now >= it } == true
    fun fail(now: Long) {
        attempts++
        retryAt = now + if (exhausted && exhaustedCooldownMillis != null) exhaustedCooldownMillis
            else if (attempts == 1) 5_000 else 15_000
    }
}

/** Server-thread only. The false overload and load-level getter never load or retain a chunk on Paper. */
internal fun citizenChunkReady(world: World, x: Int, z: Int): Boolean =
    citizenChunkLoadLevel(world, x, z) == Chunk.LoadLevel.ENTITY_TICKING

internal fun citizenChunkLoadLevel(world: World, x: Int, z: Int): Chunk.LoadLevel? =
    if (world.isChunkLoaded(x, z)) world.getChunkAt(x, z, false).loadLevel else null

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


/** Horizontal footprints stay off transit routes, except the departure and actual destination buildings. */
internal fun citizenRouteCellAllowed(city: Settlement, allowed: Set<UUID>, x: Int, z: Int): Boolean =
    city.contains(x, z) && civsProtectedBuildings(city).none { it.id !in allowed && !civsPublicTransit(it, x, z) &&
        (x in it.bounds.min.x..it.bounds.max.x && z in it.bounds.min.z..it.bounds.max.z || CivsWorksiteLayouts.isField(it, x, z)) }

/** Only an observed mine leg may pass below another building, with headroom below its foundation. */
internal fun citizenRoutePointAllowed(city: Settlement, allowed: Set<UUID>, point: CivPosition, mine: CivBuilding? = null): Boolean {
    val bottom = mine?.takeIf { it.type == BuildingType.QUARRY }?.let { building ->
        CivsMinePlan.forBuilding(building)?.bottom?.let { CivsWorksiteLayouts.point(building, it) }
    }
    return city.contains(point.x, point.z) && civsProtectedBuildings(city).none { building ->
        building.id !in allowed && !civsPublicTransit(building, point.x, point.z) &&
            (point.x in building.bounds.min.x..building.bounds.max.x && point.z in building.bounds.min.z..building.bounds.max.z ||
                CivsWorksiteLayouts.isField(building, point.x, point.z)) &&
            (bottom == null || point.y >= bottom.y || !CivsMinePlan.belowFoundation(building, point.y, headroom = 1))
    }
}

/** Capture immutable completed prefixes once, before the async search inspects edges. */
private fun citizenMineRoutePassage(mine: CivBuilding, accessStep: Long,
                                  state: CivMinePlanState?): (CitizenPoint) -> Boolean {
    val current = CivsMinePlan.forBuilding(mine) ?: return { false }
    val bottom = CivsWorksiteLayouts.point(mine, current.bottom)
    val prefixes = buildList {
        add(current to accessStep)
        val v4 = if (state?.version == 4) state.nextStep else state?.v4Step
        if (v4 != null && v4 > 0) CivsMinePlan.forBuilding(mine, 4)?.let { add(it to v4) }
        val v3 = if (state?.version == 3) state.nextStep else state?.v3Step
        if (v3 != null && v3 > 0) CivsMinePlan.forBuilding(mine, 3)?.let { add(it to v3) }
        val v2 = if (state?.version == 2) state.nextStep else state?.v2Step
        if (v2 != null && v2 > 0) CivsMinePlan.forBuilding(mine, 2)?.let { add(it to v2) }
    }
    val legacy = state?.legacyStep?.takeIf { it > 0 }?.let(current::legacyCompleted)
    fun legacyOpen(p: CivPosition) = legacy?.containsKey(p) == true && legacy[p].let {
        it == null || it.startsWith("minecraft:ladder[") || it == "minecraft:torch"
    }
    return { point ->
        val local = CivsMinePlan.localPoint(mine, CivPosition(kotlin.math.floor(point.x).toInt(),
            point.y.roundToInt(), kotlin.math.floor(point.z).toInt()))
        point.y >= bottom.y - .125 || prefixes.any { (plan, prefix) -> plan.builtStanding(local, prefix) } ||
            legacyOpen(local) && legacyOpen(local.copy(y = local.y + 1))
    }
}

internal fun citizenMineWorkRouteAllowed(mine: CivBuilding, point: CitizenPoint, accessStep: Long,
                                        state: CivMinePlanState? = null): Boolean =
    citizenMineRoutePassage(mine, accessStep, state)(point)

internal fun citizenRouteBuildings(city: Settlement, plan: CitizenPlan, target: CivPosition, from: CivPosition): Set<UUID> =
    civsProtectedBuildings(city).filter { building ->
        building.bounds.contains(from.x, from.y, from.z) || building.bounds.contains(target.x, target.y, target.z) ||
            building.control == target ||
            (target == plan.workplace || target == plan.storage) && building.id == plan.token.assignmentId
    }.mapTo(hashSetOf()) { it.id }.also { allowed ->
        plan.courier?.cargo?.takeIf { it.sourceWorkerId != null }?.let { allowed += it.sourceBuildingId }
    }

/** Authored shafts split a deep search into the actual entrance, climb and gallery legs. */
internal fun citizenMineWaypoint(city: Settlement, world: World, from: Location, target: CivPosition): CivPosition? {
    for (mine in city.buildings.filter { it.type == BuildingType.QUARRY }) {
        val geometry = CivsMinePlan.forBuilding(mine) ?: continue
        val bottom = CivsWorksiteLayouts.point(mine, geometry.bottom)
        val state = city.minePlans?.get(mine.id.toString())
        val access = state?.takeIf { it.version == CivsMinePlan.VERSION }?.nextStep ?: 0
        fun local(x: Int, y: Int, z: Int) = CivsMinePlan.localPoint(mine, CivPosition(x, y, z))
        fun inMine(x: Int, y: Int, z: Int): Boolean = y < bottom.y &&
            (geometry.containsWorkStanding(local(x, y, z)) ||
                citizenMineWorkRouteAllowed(mine, CitizenPoint(x + .5, y.toDouble(), z + .5), access, state))
        if (abs(from.y - target.y) <= 1.01 ||
            !inMine(from.blockX, from.blockY, from.blockZ) && !inMine(target.x, target.y, target.z)) continue
        val onShaft = from.blockX == bottom.x && from.blockZ == bottom.z
        // A migrated resident must be able to leave its retained V3 ladder before
        // ordinary V4 receipts remodel that old access. Never route into future V3 cells.
        val oldPrefix = if (state?.version == 3) state.nextStep else state?.v3Step ?: 0
        val old = CivsMinePlan.forBuilding(mine, 3)?.takeIf { oldPrefix > 0 &&
            it.builtStanding(local(from.blockX, from.y.roundToInt(), from.blockZ), oldPrefix) &&
            !geometry.builtStanding(local(from.blockX, from.y.roundToInt(), from.blockZ), access) }
        val waypoint = if (old != null && !onShaft) {
            val oldLower = CivsWorksiteLayouts.point(mine, old.extensionLadder)
            val transfer = bottom.copy(y = mine.anchor.y + old.transferFeetY)
            when {
                from.y < transfer.y - .5 && (from.blockX != oldLower.x || from.blockZ != oldLower.z) ->
                    oldLower.copy(y = from.blockY)
                from.y < transfer.y - .5 -> oldLower
                else -> transfer
            }
        } else when {
            from.y > bottom.y + .5 -> bottom
            !onShaft -> bottom.copy(y = from.blockY)
            target.y >= bottom.y -> bottom
            else -> bottom.copy(y = target.y)
        }
        if (waypoint == target || citizenNearEndpoint(from.point(), waypoint)) continue
        if (citizenStandable(world, waypoint) && citizenPacketBodyClear(world, waypoint.point(), false)) return waypoint
    }
    return null
}

internal data class CitizenSleepPose(val position: Location, val bounds: org.bukkit.util.BoundingBox)

private fun citizenStandingBounds(feet: Location, scale: Double): BoundingBox = BoundingBox(
    feet.x - .3 * scale, feet.y, feet.z - .3 * scale,
    feet.x + .3 * scale, feet.y + 1.8 * scale, feet.z + .3 * scale)

/** Native 1.21.11 sleeping mesh: head toward -X at yaw zero, .9375 model scale.
 * Include outer skin/armour layers and lift the pose above the actual floor. No fake bed metadata. */
internal fun citizenSleepPose(world: World, feet: Location, scale: Double,
                             available: (BoundingBox) -> Boolean = { true }): CitizenSleepPose? {
    if (!citizenPacketSupported(world, feet.point()) || !citizenPacketBodyClear(world, feet.point(), false)) return null
    val origin = feet.clone().add(0.0, .32 * scale + .01, 0.0)
    for (yaw in listOf(0f, 90f, 180f, 270f)) {
        val radians = Math.toRadians(yaw.toDouble())
        val cos = kotlin.math.cos(radians)
        val sin = kotlin.math.sin(radians)
        // HumanoidModel keeps its idle arm bob in SLEEPING: the armoured hand reaches
        // .591 blocks sideways at the .1-radian maximum, beyond its static .527 width.
        val corners = listOf(-1.96, .07).flatMap { x -> listOf(-.62, .62).map { z ->
            (origin.x + (x * cos + z * sin) * scale) to (origin.z + (-x * sin + z * cos) * scale)
        } }
        val box = org.bukkit.util.BoundingBox(corners.minOf { it.first }, origin.y - .31 * scale,
            corners.minOf { it.second }, corners.maxOf { it.first }, origin.y + .31 * scale, corners.maxOf { it.second })
        if (available(box) && citizenSleepClear(world, box)) return CitizenSleepPose(origin.clone().also { it.yaw = yaw; it.pitch = 0f }, box)
    }
    return null
}

internal fun citizenSleepClear(world: World, body: org.bukkit.util.BoundingBox): Boolean {
    for (x in kotlin.math.floor(body.minX).toInt()..kotlin.math.floor(body.maxX).toInt())
        for (z in kotlin.math.floor(body.minZ).toInt()..kotlin.math.floor(body.maxZ).toInt()) {
            if (!citizenChunkReady(world, x shr 4, z shr 4)) return false
            for (y in kotlin.math.floor(body.minY).toInt()..kotlin.math.floor(body.maxY).toInt()) {
                if (y !in world.minHeight until world.maxHeight) return false
                val block = world.getBlockAt(x, y, z)
                if (block.isLiquid || block.type in CITIZEN_LEISURE_HAZARDS ||
                    block.collisionShape.boundingBoxes.any { it.clone().shift(x.toDouble(), y.toDouble(), z.toDouble()).overlaps(body) }) return false
            }
        }
    return true
}

internal fun citizenShelterTarget(city: Settlement, worker: CivWorker, safe: (CivPosition) -> Boolean): CivPosition? =
    citizenShelterCandidates(city, worker).take(80).firstOrNull(safe)

/** Cheap bounded coordinates; the actor admits at most 80 native probes per shelter search pulse. */
private fun citizenShelterCandidates(city: Settlement, worker: CivWorker): List<CivPosition> {
    val home = citizenHomeBuilding(city, worker) ?: return emptyList()
    val origin = home.control ?: home.entrance
    return buildSet {
        // Keep the established nearby cardinal preference before admitting additional
        // floor positions; the latter make room for several non-overlapping bodies.
        for (dy in listOf(0, 1, -1, 2, -2)) for (r in 1..4)
            for ((dx, dz) in listOf(r to 0, 0 to r, -r to 0, 0 to -r)) {
                val point = CivPosition(origin.x + dx, origin.y + dy, origin.z + dz)
                if (home.bounds.contains(point.x, point.y, point.z)) add(point)
            }
        for (dy in listOf(0, 1, -1, 2, -2)) for (r in 1..4) for (dx in -r..r) for (dz in -r..r) {
            if (maxOf(abs(dx), abs(dz)) != r) continue
            val point = CivPosition(origin.x + dx, origin.y + dy, origin.z + dz)
            if (home.bounds.contains(point.x, point.y, point.z)) add(point)
        }
    }.toList()
}

private fun citizenHomeBuilding(city: Settlement, worker: CivWorker): CivBuilding? {
    worker.homeId?.let { id -> city.building(id)?.takeIf { home ->
        home.type == BuildingType.TOWN_HALL || home.type.isResidence && home.phase == BuildingPhase.ACTIVE
    }?.let { return it } }
    val homes = city.buildings.filter { it.type.isResidence && it.phase == BuildingPhase.ACTIVE }
        .flatMap { home -> List(CivsPopulation.houseBeds(home)) { home } }
    return homes.getOrNull(city.workers.indexOfFirst { it.id == worker.id })
        ?: city.buildings.firstOrNull { it.type == BuildingType.TOWN_HALL }
}
