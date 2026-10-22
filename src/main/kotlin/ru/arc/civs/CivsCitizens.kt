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
import ru.arc.paper.npc.PacketNpcProfile
import ru.arc.paper.npc.PacketNpcPose
import ru.arc.paper.npc.PacketNpcInteraction
import ru.arc.paper.npc.PacketPlayerNpc
import ru.arc.paper.npc.PaperPacketNpcs
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import kotlin.math.abs
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
) : Listener, AutoCloseable {
    private val packets: PaperPacketNpcs
    private val navigation = CivsResidentNavigation()
    private val paths = CivsPaths()
    private val threats: CivsResidentThreats
    private val skins: List<CitizenSkin>
    private var actors = linkedMapOf<UUID, Actor>()
    private var statuses = mutableMapOf<UUID, String>()
    private var spawnRetries = mutableMapOf<UUID, Pair<CitizenPlan, CitizenRetry>>()
    @Volatile private var completions = ConcurrentLinkedQueue<Completion>()
    private val closed = AtomicBoolean()
    private var retirement: CompletableFuture<Void>? = null
    private var wanderBudget = 0
    private var campBudget = 0
    private var recoveryBudget = 0
    private val campfires = CivsCampfires()
    private val voices = CivsCitizenVoices(plugin.config)
    private val pendingHarm = mutableSetOf<UUID>()
    private val harmCompletions = ConcurrentLinkedQueue<HarmCompletion>()
    private val supplyCompletions = ConcurrentLinkedQueue<SupplyCompletion>()
    private var harmEpoch = 0L
    private var navigationTick = 0L
    private var navigationAt = 0L
    private var navigationCadenceMillis = 0L
    internal var residentStepAllowed: (PacketPlayerNpc, Location, Location) -> Boolean = { _, _, _ -> true }

    internal fun collisionActors(): Collection<PacketPlayerNpc> {
        checkMainThread()
        return actors.values.map { it.npc }
    }

    /** Logical grounded feet stay stable while the sleeping mesh has a pose-only lift. */
    internal fun residentFeet(workerId: UUID): Location? {
        checkMainThread()
        val actor = actors[workerId]?.takeIf { it.npc.isValid } ?: return null
        return actor.sleepOrigin?.clone() ?: actor.npc.location.clone()
    }

    /** A small physical push changes only the observed position, never a work or cargo receipt. */
    internal fun nudgeResident(npc: PacketPlayerNpc, impulse: Vector): Boolean {
        checkMainThread()
        if (closed.get() || !npc.isValid || npc.pose == PacketNpcPose.SLEEPING) return false
        val actor = actors.values.firstOrNull { it.npc === npc } ?: return false
        val city = cityProvider().firstOrNull { it.id == actor.cityId && it.pendingDeletion == null } ?: return false
        val from = npc.location
        val to = from.clone().add(impulse)
        if (npc.world.uid != city.worldId || !citizenResidentNudgeClear(npc, from, to)) return false
        val target = actor.plan.workplace ?: actor.plan.storage
        val allowed = actor.route?.allowedBuildings ?: citizenRouteBuildings(city, actor.plan, target,
            CivPosition(from.blockX, from.blockY, from.blockZ))
        val halfWidth = .3 * npc.scale
        for (x in kotlin.math.floor(to.x - halfWidth).toInt()..kotlin.math.floor(to.x + halfWidth).toInt())
            for (z in kotlin.math.floor(to.z - halfWidth).toInt()..kotlin.math.floor(to.z + halfWidth).toInt())
                if (!citizenRouteCellAllowed(city, allowed, x, z)) return false
        if (!citizenControlStepClear(city, from.point(), to.point())) return false
        npc.teleport(to)
        actor.footsteps.reset()
        return true
    }

    init {
        require(maxActive in 1..48 && viewerRange in 1..96)
        checkMainThread()
        skins = loadCitizenSkins()
        packets = packetOwner ?: PaperPacketNpcs(plugin, "civs-residents")
        threats = CivsResidentThreats(plugin, attacked = ::defend, injury = ::damage)
        packets.onInteract = { player, npc, action ->
            val actor = actors.values.firstOrNull { it.npc === npc }
            if (!closed.get() && actor != null) {
                if (action == PacketNpcInteraction.ATTACK) threats.playerAttack(player, actor.workerId, npc)
                else {
                    val resident = cityProvider().firstOrNull { it.id == actor.cityId }?.workers?.firstOrNull { it.id == actor.workerId }
                    voices.greet(actor.workerId, npc, player, resident?.let(CivsResidents::sex))
                    interact(player, actor.cityId, actor.workerId)
                }
            }
        }
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.logger.info("ArcCivs resident backend=packets maximum=$maxActive viewer_range=$viewerRange " +
            "path_steps_per_tick=${CivsResidentNavigation.SEARCH_OPERATIONS_PER_TICK} " +
            "path_budget_micros=${CivsResidentNavigation.SEARCH_BUDGET_NANOS / 1_000}")
    }

    fun pulse(nowMillis: Long) {
        checkMainThread()
        if (closed.get()) return
        wanderBudget = 8 // Aggregate native target checks across all idle actors, not eight per NPC.
        campBudget = 2 // At most two six-place campfire admission searches for the entire owner.
        recoveryBudget = 2 // At most two bounded safe-position searches after real route failures per pulse.
        drainCompletions(nowMillis)
        drainSupplies(nowMillis)
        drainHarm()
        val cities = cityProvider().associateBy { it.id }
        paths.retain(cities)
        val workers = cities.values.flatMap { city -> city.workers.map { it.id to (city to it) } }.toMap()
        voices.retain(workers.keys)
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
            val plan = if (actor.supplyPickup != null) actor.plan
                else citizenCurrentPlan(actor.plan, actor.cycle.phase) { plan(city, worker) }
            val reason = when {
                actor.cityId != city.id || actor.worldId != city.worldId -> "unavailable"
                world == null -> "world-unloaded"
                !actor.npc.isValid -> "unavailable"
                actor.npc.world.uid != city.worldId -> "unavailable"
                !citizenHasViewer(city.worldId, actor.npc.location.point(), viewers, viewerRange) -> "sleeping"
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
            var npc: PacketPlayerNpc? = null
            try {
                val point = spawnPoint(requireNotNull(world), requireNotNull(plan).spawnOrigin, city.workers.indexOf(worker), viewers)
                if (point == null) {
                    retry.fail(nowMillis)
                    spawnRetries[workerId] = plan to retry
                    statuses[workerId] = "route-unreachable"
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
                update(actor, city, world, nowMillis)
                if (actors[workerId] === actor) CivsDiagnostics.event("citizen_spawn", "city" to city.id,
                    "worker" to workerId, "type" to "PACKET_PLAYER",
                    "skin" to skin.name, "tool" to (CivsEquipment.slots(worker)[4]?.material ?: citizenTool(plan)?.name))
            } catch (error: Exception) {
                actors.remove(workerId)?.cycle?.close()
                runCatching { npc?.remove() }.onFailure { plugin.logger.log(Level.WARNING,
                    "ArcCivs failed to remove rejected NPC city=${city.id} worker=$workerId", it) }
                retry.fail(nowMillis)
                spawnRetries[workerId] = requireNotNull(plan) to retry
                statuses[workerId] = "unavailable"
                if (retry.attempts == 1 || retry.exhausted) plugin.logger.log(Level.WARNING,
                    "ArcCivs NPC spawn failed city=${city.id} worker=$workerId attempt=${retry.attempts}", error)
            }
        }
        campfires.scanOne(Bukkit.getCurrentTick()) { plugin.server.getWorld(it) }
        threats.pulse(actors.values.filter { it.workerId !in pendingHarm && it.npc.isValid }
            .map { CivsThreatTarget(it.cityId, it.workerId, it.npc) }, nowMillis)
    }

    /** After each bounded worksite pulse, hand an admitted field action to its existing actor. */
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
                !citizenHasViewer(city.worldId, actor.npc.location.point(), viewers, viewerRange)) continue
            val plan = citizenCurrentPlan(actor.plan, actor.cycle.phase) { plan(city, worker) } ?: continue
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
    }

    private fun reconcilePlan(actor: Actor, nextPlan: CitizenPlan): Boolean {
        if (actor.plan != nextPlan && (!citizenPlansContinue(actor.plan, nextPlan) ||
                actor.plan.courier?.carrying != nextPlan.courier?.carrying)) {
            if (citizenStorageNeedsReset(actor.plan.storage, nextPlan.storage, actor.npc.location.point())) {
                remove(actor, "waiting")
                return false
            }
            cancelRoute(actor)
            stopCamp(actor, "plan-changed")
            actor.cycle.close()
            if (actor.plan.storage != nextPlan.storage || actor.plan.workplace != nextPlan.workplace) actor.targets.clear()
            actor.plan = nextPlan
            actor.cycle = nextPlan.cycle()
            actor.returningToWarehouse = false
            actor.wander = CitizenWander()
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
                if (actor?.returningToWarehouse == true) return "workers.status.moving-to-storage"
                actor?.social?.visit?.let {
                    return "workers.status.${if (it.arrivedAt == null) "to-campfire" else "campfire"}"
                }
                val plan = if (actor == null) plan(city, worker)
                    else citizenCurrentPlan(actor.plan, actor.cycle.phase) { plan(city, worker) }
                if (actor != null) {
                    if (actor.cycle.phase == CitizenPhase.PAUSED) return "workers.status.${actor.cycle.status()}"
                    if (navigationFailed(actor))
                        return "workers.status.route-unreachable"
                    if (plan != null) workActivityStatus(actor, plan)?.let { return "workers.status.$it" }
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

    /** Observed work admission for the city's active-minute hunger clock. */
    internal fun workingResidentIds(cityId: UUID): Set<UUID> {
        checkMainThread()
        return actors.values.filter { actor ->
            actor.cityId == cityId && actor.npc.isValid && actor.workerId !in pendingHarm && !actor.sheltering &&
                actor.plan.idleReason in listOf(null, "placing") &&
                actor.cycle.phase !in setOf(CitizenPhase.IDLE, CitizenPhase.PAUSED, CitizenPhase.CLOSED)
        }.mapTo(hashSetOf()) { it.workerId }
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
                citizenHasViewer(current.worldId, point.point(), viewers, viewerRange) &&
                point.location(world).let { destination -> residentStepAllowed(npc, destination, destination) &&
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
        actor.wander = CitizenWander()
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
        val base = citizenPlan(city, worker, if (CivsResidents.isChild(worker)) null else courierPlan(city, worker)) ?: return null
        val admitted = if (base.idleReason == null && !productionReady(city, worker)) base.copy(idleReason = "waiting-inputs") else base
        val planned = if (admitted.courier != null || admitted.type !in CivsWorksites.TYPES || admitted.idleReason != null) admitted else {
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
        val now = System.currentTimeMillis()
        navigationCadenceMillis = if (navigationAt == 0L) 0 else (now - navigationAt).coerceAtLeast(0)
        navigationAt = now
        paths.pulse()
        navigation.pulse()
        threats.tick()
        val viewers = viewers()
        for (actor in actors.values.toList()) {
            val route = actor.route
            val npc = actor.npc
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
            if (route == null || actor.workerId in pendingHarm || !npc.isValid ||
                !citizenHasViewer(actor.worldId, npc.location.point(), viewers, viewerRange) ||
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
            if (route.motion.searching) continue
            route.previousMotion?.let { previous ->
                if (route.motion.failure != null && route.replanReason == "player-blocked") {
                    // A person can seal a narrow corridor. Keep the original route ready
                    // to resume when they leave; a failed detour never licenses teleporting.
                    route.motion.cancel()
                    route.motion = previous
                } else previous.cancel()
                route.previousMotion = null
                route.motionReady(navigationTick)
                actor.navigationEpisodeProgress = 0.0
                route.path = null
            }
            val motion = route.motion
            // Stop on the first valid arrival tick, including at any usable side of a barrel.
            // Waiting for the coarse work pulse used to keep walking around the barrel after arrival.
            val travelTarget = actor.combatDestination ?: actor.foodSource?.let(city::building)?.control ?:
                if (actor.returningToWarehouse) actor.plan.storage else when (actor.cycle.phase) {
                CitizenPhase.TO_WORK -> actor.plan.workplace
                CitizenPhase.TO_STORAGE, CitizenPhase.RETURNING -> actor.plan.storage
                else -> null
            }
            if (travelTarget != null && !actor.sheltering &&
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
                route.motionReady(navigationTick)
                actor.navigationEpisodeProgress = 0.0
                val elapsed = System.currentTimeMillis() - route.startedAt
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
                navigationDiagnostic(actor, requireNotNull(motion.failure), route.target, now)
                route.completion = CitizenNavigationCompletion(motion.failure)
                continue
            }
            if (motion.finished) {
                if (route.intermediate) {
                    cancelRoute(actor)
                    startRoute(actor, npc.world, route.authored, System.currentTimeMillis(), route.idleAnchor)
                    continue
                }
                route.trip?.let { paths.complete(city, it) }
                route.completion = CitizenNavigationCompletion(null)
                continue
            }
            val before = npc.location
            val working = actor.attacker == null && (actor.returningToWarehouse || citizenWorkingTravel(actor.plan, actor.cycle.phase, actor.sheltering))
            val speed = if (actor.attacker != null) 1.1 else if (working)
                .8 * surfaceSpeed(npc.world.getBlockAt(before.blockX, citizenSupportY(before.y), before.blockZ)) else .55
            val next = motion.move(before, speed, { from, to -> residentStepAllowed(npc, from, to) }) { destination ->
                (0..1).all { dy -> citizenOpenRouteDoor(city, route.allowedBuildings, before,
                    CivPosition(destination.blockX, destination.blockY + dy, destination.blockZ), doorBlocked) }
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
                if (route.progressSinceObservation >= .25) {
                    route.progressSinceObservation = 0.0
                    route.progressTick = navigationTick
                }
                if (route.progressSinceReplan >= 2.0) {
                    route.replanAttempts = 0
                    route.progressSinceReplan = 0.0
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
                if (!replanNavigation(actor, route, requireNotNull(motion.failure), now))
                    route.completion = CitizenNavigationCompletion(motion.failure)
            }
            else if (motion.finished) {
                if (route.intermediate) {
                    cancelRoute(actor)
                    startRoute(actor, npc.world, route.authored, System.currentTimeMillis(), route.idleAnchor)
                    continue
                }
                route.trip?.let { paths.complete(city, it) }
                route.completion = CitizenNavigationCompletion(null)
            }
            else if (navigationTick - route.progressTick >= 40) {
                val reason = if (motion.waitingForCollision) "player-blocked" else if (motion.turning) "turn-loop" else "no-progress"
                if (!replanNavigation(actor, route, reason, now)) {
                    navigationDiagnostic(actor, reason, route.target, now)
                    if (!motion.waitingForCollision) route.completion = CitizenNavigationCompletion(reason)
                }
            }
        }
        threats.gravity(actors.values.filter { it.sleeping || it.route?.motion?.navigating == true }.mapTo(hashSetOf()) { it.workerId })
    }

    private fun update(actor: Actor, city: Settlement, world: World, now: Long) {
        val cycle = actor.cycle
        val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: return
        if (actor.sleeping && !sleepStillSafe(actor)) {
            // Changed support/headroom must be handled before work, hunger or another route.
            // A clear but unsupported origin wakes in place and falls on the normal gravity tick.
            if (wake(actor)) actor.shelter = null
            statuses[actor.workerId] = "sheltering"
            return
        }
        if (actor.supplyPickup != null) { statuses[actor.workerId] = "loading-cargo"; return }
        if (combat(actor, city, worker, world, now)) return
        if (supplyFood(actor, city, worker, world, now)) return
        if (world.time in 12_000L..23_000L && cycle.phase !in setOf(CitizenPhase.DELIVERING, CitizenPhase.PICKING_UP)) {
            stopCamp(actor, "night")
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
                if (arrivedAt(actor, city, world, target)) {
                    cancelRoute(actor)
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
                            now - route.startedAt, route.timeoutMillis, (actor.route?.motion?.navigating == true))
                        when (outcome) {
                            CitizenNavigationOutcome.PENDING -> Unit
                            CitizenNavigationOutcome.ARRIVED -> {
                                route.trip?.let { paths.complete(city, it) }
                                cancelRoute(actor)
                                arriveForWork(actor, city, worker, now)
                            }
                            else -> pauseNavigation(actor, "route-unreachable", outcome.name.lowercase(), route.target, now)
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
        if (!monster.isValid || monster.isDead || monster.world != world || now > actor.combatUntil ||
            monster.location.distanceSquared(actor.npc.location) > 24.0 * 24.0) {
            cancelRoute(actor)
            actor.attacker = null
            actor.combatDestination = null
            actor.combatStatus = null
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
        if (actor.route?.let { it.motion.navigating && now - it.startedAt < it.timeoutMillis } == true && actor.combatDestination?.let {
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
            if (route.completion != null || now - route.startedAt > route.timeoutMillis) {
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
        val future = try { prepareSupplies(actor.cityId, actor.workerId, buildingId) }
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

    private fun submitReadyDelivery(actor: Actor, city: Settlement, world: World, now: Long) {
        val target = if (actor.plan.completeAtWorkplace) requireNotNull(actor.plan.workplace) else actor.plan.storage
        if (!arrivedAt(actor, city, world, target)) {
            pauseNavigation(actor, "route-unreachable", "left-delivery-point", resolvedTarget(actor, target).location(world), now)
        } else if (!hasCapacity(city, actor.plan)) actor.cycle.pause("storage-full", now, true)
        else actor.cycle.beginDelivery(now)?.let { ticket -> submitDelivery(actor, ticket, now) }
    }

    private fun animateWork(actor: Actor, city: Settlement, world: World, now: Long) {
        if (actor.plan.type == BuildingType.BUILDER_HUT) {
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
        if (closed.get() || material.isAir) return
        val city = cityProvider().firstOrNull { it.id == cityId } ?: return
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
        if (actor.cityId != city.id || order.id != orderId || resident.assignment != actor.plan.token.assignmentId ||
            !CivsRules.isBuilder(city, assignment) || !CivsRules.builderCanWork(city, assignment) ||
            requirePlacement && order.phase !in setOf(ConstructionPhase.READY_TO_PLACE, ConstructionPhase.PLACING) ||
            !citizenConstructionWorking(actor.plan, actor.cycle.phase, orderId) ||
            actor.sheltering || actor.workerId in pendingHarm || !actor.npc.isValid || (actor.route?.motion?.navigating == true)) return null
        val player = actor.npc
        if (player.world.uid != city.worldId || player.world.time in 12_000L..23_000L ||
            !citizenChunkReady(player.world, player.location.blockX shr 4, player.location.blockZ shr 4) ||
            !citizenHasViewer(city.worldId, player.location.point(), viewers(), viewerRange)) return null
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
        runCatching { voices.say(actor.workerId, player, event, Bukkit.getCurrentTick(), plugin.server.onlinePlayers,
            resident?.let(CivsResidents::sex)) }
            .onFailure { logFailure(actor, "voice", it) }
    }

    private fun stopCamp(actor: Actor, reason: String = "interrupted") {
        val visit = actor.social.visit ?: return
        actor.social.finish(Bukkit.getCurrentTick())
        CivsDiagnostics.event("citizen_campfire", "city" to actor.cityId, "worker" to actor.workerId,
            "action" to "end", "reason" to reason, "fire" to visit.fire.position, "seat" to visit.seat)
        cancelRoute(actor)
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
            val anchor = origin.control ?: origin.entrance
            val allowed = city.buildings.filter { it.id == origin.id || it.type == BuildingType.TOWN_HALL }.mapTo(hashSetOf()) { it.id }
            val fire = campfires.nearby(world, city, anchor, tick).take(4).firstOrNull { fire ->
                citizenRouteCellAllowed(city, allowed, fire.position.x, fire.position.z) &&
                    citizenCampfireLit(world, fire.position) &&
                    actors.values.count { it.social.visit?.fire == fire } < 6
            }
            val seat = fire?.let { target -> citizenCampSeats(target.position, actor.workerId).firstOrNull { point ->
                citizenRouteCellAllowed(city, allowed, point.x, point.z) && citizenControlClear(city, point) &&
                    actors.values.none { other -> other !== actor && other.worldId == city.worldId &&
                        (other.social.visit?.seat?.let { it.x == point.x && it.z == point.z } == true ||
                            other.npc.isValid && other.npc.location.point().distanceSquared(point.point()) < 1.4 * 1.4) } &&
                    citizenIdleTerrain(world, point, city.buildings) && citizenStandable(world, point)
            } }
            if (fire == null || seat == null) { routine.discoveryMiss(tick); return false }
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
        if (routine.expired(tick) || !citizenCampfireLit(world, activeVisit.fire.position) ||
            !citizenIdleTerrain(world, activeVisit.seat, city.buildings)) {
            stopCamp(actor, if (routine.expired(tick)) {
                if (activeVisit.arrivedAt == null) "travel-timeout" else "rest-finished"
            } else "fire-or-seat-unavailable")
            return false
        }
        if (arrived(actor.npc.location, activeVisit.seat.location(world))) {
            if (activeVisit.arrivedAt == null) {
                cancelRoute(actor)
                activeVisit.arrivedAt = tick
                CivsDiagnostics.event("citizen_campfire", "city" to actor.cityId, "worker" to actor.workerId,
                    "action" to "arrived", "fire" to activeVisit.fire.position, "seat" to activeVisit.seat)
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
                ?: Location(world, activeVisit.fire.position.x + .5, activeVisit.fire.position.y + .4, activeVisit.fire.position.z + .5)
            citizenLookDirection(player.location.clone().add(0.0, player.eyeHeight, 0.0), focus).let { (yaw, pitch) -> look(player, yaw, pitch) }
            statuses[actor.workerId] = "campfire"
            return true
        }
        val route = actor.route
        if (activeVisit.arrivedAt != null || route == null || route.completion != null ||
            !route.chunks.ready(world) || now - route.startedAt > route.timeoutMillis) {
            stopCamp(actor, if (activeVisit.arrivedAt != null) "left-seat" else "route-ended")
            return false
        }
        statuses[actor.workerId] = "to-campfire"
        return true
    }

    private fun wander(actor: Actor, city: Settlement, world: World, now: Long) {
        val worker = city.workers.firstOrNull { it.id == actor.workerId } ?: return
        val home = citizenIdleBuilding(city, worker) ?: return
        val anchor = home.control ?: home.entrance
        val route = actor.route
        if (route != null) {
            val outcome = if (!route.chunks.ready(world)) CitizenNavigationOutcome.STOPPED
                else citizenNavigationOutcome(route.completion,
                    arrived(actor.npc.location, route.target),
                    now - route.startedAt, route.timeoutMillis, (actor.route?.motion?.navigating == true))
            if (outcome == CitizenNavigationOutcome.PENDING) return
            if (outcome != CitizenNavigationOutcome.ARRIVED && recoverNavigation(actor, route.target, now, idleAnchor = anchor)) {
                actor.wander.finish(now, true)
                return
            }
            cancelRoute(actor)
            actor.wander.finish(now, outcome == CitizenNavigationOutcome.ARRIVED)
            CivsDiagnostics.event("citizen_wander", "city" to city.id, "worker" to actor.workerId,
                "outcome" to outcome.name.lowercase(), "target" to route.target.point().coordinates(),
                "reason" to route.motion.failure, "actual" to actor.npc.location.point().coordinates(),
                "path_nodes" to route.motion.nodeCount, "elapsed_ms" to now - route.startedAt)
            return
        }
        if (!actor.wander.ready(now) || wanderBudget == 0) return
        wanderBudget--
        // Keep short invalid-target retries fair within the shared eight-check budget.
        citizenRotateAfterAdmission(actors, actor.workerId)
        val target = if (!citizenWithinIdleArea(anchor, actor.npc.location.point())) anchor
            else citizenWanderTarget(city, anchor, actor.workerId, actor.wander.step, setOf(home.id))
        // startRoute owns the single loaded-chunk, support and headroom check.
        if (target != null && startRoute(actor, world, target, now, idleAnchor = anchor)) {
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
            if (route.completion != null || now - route.startedAt > route.timeoutMillis)
                recoverNavigation(actor, route.target, now)
        }
        return true
    }

    private fun shelter(actor: Actor, city: Settlement, worker: CivWorker, world: World, now: Long) {
        if (!actor.sheltering) {
            cancelRoute(actor)
            actor.returningToWarehouse = false
            actor.sheltering = true
        }
        if (actor.shelter == null && wanderBudget > 0) {
            wanderBudget--
            actor.shelter = citizenShelterTarget(city, worker) {
                citizenStandable(world, it) && citizenSleepPose(world, it.location(world), citizenScale(worker)) != null
            } ?: citizenShelterTarget(city, worker) { citizenStandable(world, it) }
        }
        statuses[actor.workerId] = if (actor.sleeping) "sleeping" else "sheltering"
        val target = actor.shelter ?: return
        if (actor.sleeping) {
            if (sleepStillSafe(actor)) return
            if (!wake(actor)) return
            statuses[actor.workerId] = "sheltering"
        }
        if (arrived(actor.npc.location, target.location(world))) {
            cancelRoute(actor)
            // Arrival has a tolerance; validate the observed feet, not the target's centre.
            val origin = actor.npc.location
            val pose = citizenSleepPose(world, origin, citizenScale(worker)) ?: return
            actor.sleepOrigin = origin
            actor.sleepPose = pose
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
        val route = actor.route
        if (route == null) { startRoute(actor, world, target, now); return }
        if (route.completion != null || now - route.startedAt > route.timeoutMillis) {
            // Keep the failed attempt until the shared recovery budget can admit it.
            // Cancelling it here used to restart the same impossible route every pulse.
            recoverNavigation(actor, route.target, now)
        }
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
        val trip = if (actor.returningToWarehouse || citizenWorkingTravel(actor.plan, actor.cycle.phase, actor.sheltering)) paths.begin(city) else null
        val motion = navigation.request(world, from, to, { point ->
            citizenRouteCellAllowed(city, allowed, point.x, point.z) && citizenControlClear(city, point) &&
                (idleAnchor == null || idleTransit || citizenIdleRouteAllowed(idleAnchor, from.point(), point) &&
                    citizenIdleTerrain(world, point, city.buildings))
        }, { first, second -> citizenControlStepClear(city, first, second) },
            { ground -> trip?.cost(ground, surfaceSpeed) ?: 1.0 })
        val timeout = if (actor.sheltering) 10_000L else (10_000 + distance * 1_500).toLong().coerceAtMost(90_000)
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
        navigationDiagnostic(actor, detail, target, now)
        if (actor.route?.let { it.motion.waitingForCollision || it.previousMotion?.waitingForCollision == true } == true) return
        if (!recoverNavigation(actor, target, now)) pause(actor, status, now)
    }

    private fun navigationDiagnostic(actor: Actor, detail: String, target: Location, now: Long): Boolean {
        val route = actor.route
        val actual = actor.npc.location.point()
        if (actor.navigationEpisode == null) {
            actor.navigationEpisode = detail
            actor.navigationEpisodeProgress = 0.0
            actor.navigationEpisodeTick = navigationTick
        }
        if (navigationTick < actor.navigationReportAfter) { actor.navigationSuppressed++; return false }
        actor.navigationReportAfter = navigationTick + 200
        plugin.logger.warning(NAVIGATION_DIAGNOSTIC.line(
            "city" to actor.cityId, "worker" to actor.workerId, "cycle" to actor.plan.token.completedCycles,
            "attempt" to actor.cycle.failedAttempts + 1, "phase" to actor.cycle.phase, "reason" to detail,
            "from" to route?.from?.coordinates(), "target" to target.point().coordinates(), "actual" to actual.coordinates(),
            "distance" to sqrt(actual.distanceSquared(target.point())), "elapsed_ms" to route?.let { now - it.startedAt },
            "path_nodes" to route?.motion?.nodeCount, "path_failure" to route?.motion?.failure,
            "searching" to route?.motion?.searching, "collision_wait" to route?.motion?.waitingForCollision,
            "tick" to Bukkit.getCurrentTick(), "navigation_tick" to navigationTick, "cadence_ms" to navigationCadenceMillis,
            "body_yaw" to actor.npc.bodyYaw, "head_yaw" to actor.npc.headYaw,
            "turn_degrees" to actor.turnAmount,
            "waypoint" to route?.motion?.destination?.point()?.coordinates(), "remaining_nodes" to route?.motion?.remainingNodes,
            "path_progress" to route?.motion?.forwardProgress, "episode_progress" to actor.navigationEpisodeProgress,
            "no_progress_ticks" to route?.let { navigationTick - it.progressTick }, "replans" to route?.replanAttempts,
            "suppressed" to actor.navigationSuppressed,
            "work_focus" to actor.plan.worldWork?.focus, "work_standing" to actor.plan.worldWork?.standing,
            "mining_step" to actor.plan.worldWork?.miningStep,
            "strategy" to "packet-grid"))
        actor.navigationSuppressed = 0
        return true
    }

    /** Retry changed geometry from observed feet; a bounded person snapshot allows a real detour. */
    private fun replanNavigation(actor: Actor, route: Route, reason: String, now: Long): Boolean {
        if (route.previousMotion != null) return true
        if (reason !in setOf("movement-blocked", "path-changed", "no-progress", "turn-loop", "player-blocked") ||
            route.replanAttempts >= 2 || actor.workerId in pendingHarm) return false
        val npc = actor.npc
        val from = npc.location
        val bodies = if (reason == "player-blocked") npc.world.getNearbyPlayers(from, 2.5, 2.5, 2.5)
            .asSequence().filter { it.isOnline && it.isValid && !it.isDead && it.gameMode != GameMode.SPECTATOR &&
                !it.isInsideVehicle && !it.isSleeping && it.isCollidable }.take(8).map { it.boundingBox.clone() }.toList()
            else emptyList()
        val report = navigationDiagnostic(actor, reason, route.target, now)
        route.replanAttempts++
        route.replanReason = reason
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

    private fun canWorkFrom(actor: Actor, work: CivWorldWork): Boolean =
        citizenWorkFocus(work)?.let { citizenCanReachBlock(actor.npc, it, citizenWorkReach(actor.plan.type)) } == true

    private fun storageForTarget(actor: Actor, city: Settlement, target: CivPosition): CivBuilding? {
        val work = actor.plan.worldWork
        return if (target == actor.plan.workplace && work != null && citizenWorkFocus(work) == null)
            city.building(work.buildingId)?.takeIf { it.control != null }
        else citizenControlBuilding(city, target)
    }

    private fun arrivedAt(actor: Actor, city: Settlement, world: World, target: CivPosition): Boolean {
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
        // Planning latency and a temporary player obstruction are not proof of an impossible route.
        if (failed.motion.searching || failed.motion.waitingForCollision || now < actor.nextRecoveryAt || actor.attacker != null) return false
        if (failed.motion.failure == null && failed.completion == null && now - failed.startedAt <= failed.timeoutMillis) return false
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
            actor.cycle.phase in setOf(CitizenPhase.TO_WORK, CitizenPhase.WORKING) ||
                actor.cycle.phase == CitizenPhase.READY && actor.plan.completeAtWorkplace -> actor.plan.workplace ?: return false
            else -> actor.plan.storage
        }
        recoveryBudget--
        citizenRotateAfterAdmission(actors, actor.workerId)
        val viewers = viewers()
        val safe: (CivPosition) -> Boolean = { candidate ->
            citizenStandable(entity.world, candidate) && citizenPacketBodyClear(entity.world, candidate.point(), false) &&
                citizenControlClear(city, candidate) && citizenRouteCellAllowed(city, failed.allowedBuildings, candidate.x, candidate.z) &&
                (home == null || home.bounds.contains(candidate.x, candidate.y, candidate.z)) &&
                (idleAnchor == null || citizenWithinIdleArea(idleAnchor, candidate.point()) && citizenIdleTerrain(entity.world, candidate, city.buildings)) &&
                citizenHasViewer(city.worldId, candidate.point(), viewers, viewerRange)
        }
        val storage = storageForTarget(actor, city, authored)
        val point = (when {
            actor.sheltering -> authored.takeIf(safe) ?: citizenShelterTarget(city, worker, safe)
            storage != null -> citizenStorageTarget(city, storage, entity.world, safe)
            else -> citizenRecoveryTarget(city, authored, safe)
        }) ?: return false
        val destination = point.location(entity.world)
        if (!residentStepAllowed(entity, destination, destination)) return false
        val previous = entity.location
        destination.yaw = previous.yaw
        cancelRoute(actor, destination)
        if (actor.sheltering) actor.shelter = point
        else if (idleAnchor == null) actor.targets[authored] = point
        entity.teleport(destination)
        actor.observedYaw = destination.yaw
        actor.navigationEpisodeProgress = 0.0
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
                    actor.workHandoff = actor.plan.worldWork?.takeUnless {
                        it.status in setOf("waiting-tool", "waiting-materials")
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
        stopCamp(actor, reason)
        cancelRoute(actor)
        actor.cycle.pause(reason, now, false)
    }

    private fun cancelRoute(actor: Actor, wakeDestination: Location? = null) {
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
            fun clear(at: Location) = at.world == npc.world && citizenPacketBodyClear(npc.world, at.point(), false) &&
                citizenPacketCampfireClear(npc.world, at.point())
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
                        residentStepAllowed(npc, at, at)
                }?.location(npc.world) ?: return false
            }
        } else null
        actor.sleeping = false
        actor.sleepOrigin = null
        actor.sleepPose = null
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
        plugin.logger.log(Level.WARNING,
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

    private fun spawnPoint(world: World, storage: CivPosition, ordinal: Int, viewers: List<CitizenViewer>): Location? {
        val offsets = listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, -1 to 1,
            1 to -1, -1 to -1, 2 to 0, -2 to 0, 0 to 2, 0 to -2)
        return offsets.indices.asSequence().map { offsets[(it + ordinal) % offsets.size] }
            .map { (x, z) -> CivPosition(storage.x + x, storage.y, storage.z + z) }
            .filter { citizenStandable(world, it) }
            .map { it.location(world) }
            .filter { citizenHasViewer(world.uid, it.point(), viewers, viewerRange) }
            .firstOrNull { location -> actors.values.none { actor ->
                actor.npc.isValid && actor.worldId == world.uid && actor.npc.location.distanceSquared(location) < 0.8
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
        actors.values.toList().filter { !it.npc.isValid ||
            !citizenHasViewer(it.worldId, it.npc.location.point(), viewers, viewerRange) }
            .forEach { remove(it, "sleeping") }
    }

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
        paths.clear()
        val failure = runCatching { threats.close() }.exceptionOrNull()
        HandlerList.unregisterAll(this)
        packets.onInteract = null
        for (actor in actors.values) { actor.cycle.close(); actor.route?.motion?.cancel(); actor.route?.previousMotion?.cancel() }
        actors.clear()
        completions.clear()
        harmCompletions.clear()
        supplyCompletions.clear()
        pendingHarm.clear()
        spawnRetries.clear()
        statuses.clear()
        try {
            packets.closeAsync().whenComplete { _, error ->
                val terminal = failure ?: error
                if (failure != null && error != null && failure !== error) failure.addSuppressed(error)
                if (terminal == null) result.complete(null) else result.completeExceptionally(terminal)
            }
        } catch (error: Throwable) {
            if (failure != null && failure !== error) error.addSuppressed(failure)
            result.completeExceptionally(error)
        }
        return result.copy()
    }

    override fun close() { closeAsync() }

    private fun checkMainThread() = check(Bukkit.isPrimaryThread()) { "ArcCivs resident access must be on the server thread" }

    private class Actor(val cityId: UUID, val workerId: UUID, val worldId: UUID, val npc: PacketPlayerNpc, var plan: CitizenPlan) {
        val instanceId: UUID = UUID.randomUUID()
        var cycle = plan.cycle()
        val targets = mutableMapOf<CivPosition, CivPosition>()
        var wander = CitizenWander()
        val animation = CitizenWorkAnimation()
        val footsteps = CitizenFootsteps()
        var route: Route? = null
        var lookYaw: Float? = null
        var lookPitch = 0f
        var observedYaw: Float? = null
        var observedSleeping = false
        var navigationEpisode: String? = null
        var navigationEpisodeProgress = 0.0
        var navigationEpisodeTick = 0L
        var navigationReportAfter = 0L
        var navigationSuppressed = 0
        var turnOrigin: CitizenPoint? = null
        var turnSinceTick = 0L
        var turnAmount = 0f
        var workHandoff: CitizenWorkHandoff? = null
        var reportedFailure = false
        var damageAfter = 0L
        var harmLogAfter = 0L
        var reportHarmCompletion = false
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
        var nextPlayAt = 0L
        var supplyPickup: SupplyPickup? = null
        var supplyFailure: Pair<UUID, String>? = null
        var supplyRetryAt = 0L
        var foodSource: UUID? = null
        var nextRecoveryAt = 0L
        var attacker: Monster? = null
        var combatUntil = 0L
        var combatDestination: CivPosition? = null
        var combatStatus: String? = null
        var combatRouteAfter = 0L
        var combatAttackAfter = 0L
    }
    private class Route(val from: CitizenPoint, val target: Location, val chunks: CitizenChunks, val startedAt: Long,
        val timeoutMillis: Long, val allowedBuildings: Set<UUID>, var motion: CitizenPacketRoute, val trip: CivsPaths.Trip?,
        val authored: CivPosition, val intermediate: Boolean = false, val idleAnchor: CivPosition? = null) {
        var completion: CitizenNavigationCompletion? = null
        var planningReported = false
        var storageReason: String? = null
        var storageBuilding: UUID? = null
        var path: CitizenPathSnapshot? = null
        var previousMotion: CitizenPacketRoute? = null
        var replanReason: String? = null
        var replanAttempts = 0
        var observedProgress = 0.0
        var progressSinceObservation = 0.0
        var progressSinceReplan = 0.0
        var progressTick = 0L

        fun motionReady(tick: Long) {
            observedProgress = motion.forwardProgress
            progressSinceObservation = 0.0
            progressSinceReplan = 0.0
            progressTick = tick
        }
    }
    private data class Completion(val workerId: UUID, val actorId: UUID, val ticket: UUID, val token: CitizenToken,
        val city: Settlement?, val error: Throwable?, val pickup: Boolean = false)
    private data class HarmCompletion(val workerId: UUID, val city: Settlement?, val error: Throwable?, val epoch: Long)
    private data class SupplyPickup(val ticket: UUID, val buildingId: UUID, val resumeWork: Boolean)
    private data class SupplyCompletion(val workerId: UUID, val actorId: UUID, val pickup: SupplyPickup,
        val city: Settlement?, val error: Throwable?)

    private companion object {
        val NAVIGATION_DIAGNOSTIC = StructuredDebugLine("ARCCIVS_NAVIGATION_FAILED")
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

internal fun citizenHasViewer(worldId: UUID, point: CitizenPoint, viewers: List<CitizenViewer>, range: Int): Boolean =
    viewers.any { it.worldId == worldId && it.point.distanceSquared(point) <= range.toDouble() * range }
internal fun citizenSpawnBudget(active: Int, maximum: Int): Int = (maximum.coerceIn(0, 48) - active).coerceIn(0, 2)

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
    val shipmentId: UUID? = null)
internal data class CitizenPlan(val token: CitizenToken, val storage: CivPosition, val workplace: CivPosition?,
    val type: BuildingType?, val workMillis: Long, val idleReason: String?, val completeAtWorkplace: Boolean = false,
    val courier: CivCourierPlan? = null, val worldWork: CivWorldWork? = null, val suppliesNeeded: Boolean = false) {
    val spawnOrigin: CivPosition get() = courier?.cargo?.sourceEntrance ?: storage
    fun cycle() = CitizenCycle(token, workMillis, completeAtWorkplace, courier != null, courier?.carrying == true,
        worldWork != null || type == BuildingType.BUILDER_HUT && idleReason in listOf(null, "placing") && !suppliesNeeded)
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
    if (previous.courier != null && phase in setOf(CitizenPhase.PICKING_UP, CitizenPhase.DELIVERING) ||
        previous.worldWork != null && (phase == CitizenPhase.DELIVERING ||
            phase == CitizenPhase.READY && !previous.worldWork.cropCare)) previous else current()

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
        return CitizenPlan(CitizenToken(null, 0, 0, null), home.control ?: home.entrance, null, null, 0, "playing")
    }
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
        .minByOrNull { (it.control ?: it.entrance).point().distanceSquared((workplace ?: city.center).point()) }
    val storage = when {
        // Physical builders take supplies from their assigned hut or their own bag.
        // The construction target and a nearby town hall are never their depot.
        city.inventory != null && assigned != null -> assigned.control ?: assigned.entrance
        workType in setOf(BuildingType.TOWN_HALL, BuildingType.WAREHOUSE) && assigned != null -> assigned.control ?: assigned.entrance
        else -> depot?.control ?: depot?.entrance ?: return null
    }
    val reason = when {
        worker.assignment == null -> "unassigned"
        assigned == null -> "building-unavailable"
        workType == BuildingType.WAREHOUSE && assigned.phase == BuildingPhase.ACTIVE -> "waiting-orders"
        builder && order == null -> "no-construction"
        builder && !CivsRules.builderCanWork(city, assigned) -> "building-unavailable"
        builder && order?.phase == ConstructionPhase.WAITING_MATERIALS -> "waiting-materials"
        builder && order?.phase == ConstructionPhase.PAUSED -> "construction-paused"
        builder && order?.phase in setOf(ConstructionPhase.READY_TO_PLACE, ConstructionPhase.PLACING) -> "placing"
        builder && city.inventory == null && depot?.control == null && depot?.phase == BuildingPhase.ACTIVE -> "storage-unavailable"
        !builder && !CivsRules.productionAvailable(city, assigned) -> "building-unavailable"
        workplace == null -> "building-unavailable"
        else -> null
    }
    return CitizenPlan(CitizenToken(worker.assignment, worker.completedCycles, worker.nextDeliveryAt, order?.id),
        storage, workplace, workType,
        if (builder && order?.payment != null) CivsRules.BUILDER_SESSION_INTERVAL else workType?.let(CivsRules::deliveryInterval) ?: 0,
        reason, builder || city.inventory != null)
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

internal fun citizenAtStorage(world: World, from: CitizenPoint, control: CivPosition): Boolean {
    if (!citizenStorageReady(world, control)) return false
    val dx = from.x - (control.x + 0.5)
    val dz = from.z - (control.z + 0.5)
    return abs(from.y - control.y) <= 1.5 && dx * dx + dz * dz <= 1.75 * 1.75 &&
        (kotlin.math.floor(from.x).toInt() != control.x || kotlin.math.floor(from.z).toInt() != control.z)
}

/** Batched actions keep one actual reachable focus (for example the trunk's lowest cut). */
internal fun citizenWorkFocus(work: CivWorldWork): CivPosition? =
    (work.focus ?: work.changes.minByOrNull { it.position.point().distanceSquared(work.standing.point()) }?.position)
        ?.takeIf { focus -> work.changes.any { it.position == focus } || work.cropCare && work.focus == focus &&
            work.changes.all { it.position == focus.copy(y = focus.y - 1) } }

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

/** Open only a nearby path door; normal blocks, foreign footprints and rejected native events stay intact. */
internal fun citizenOpenRouteDoor(city: Settlement, allowed: Set<UUID>, at: Location, point: CivPosition,
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
    val event = CivsResidentOpenDoorEvent(city.id, lower)
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

private val CITIZEN_WANDER_OFFSETS = listOf(3 to 0, 4 to 4, 0 to 6, -5 to 4, -6 to 0, -4 to -5, 0 to -6, 5 to -3)
private val CITIZEN_WANDER_HEIGHTS = listOf(0, 1, -1, 2, -2)
private const val CITIZEN_IDLE_RADIUS = 8.0

internal fun citizenIdleBuilding(city: Settlement, worker: CivWorker): CivBuilding? =
    worker.assignment?.let(city::building) ?: citizenHomeBuilding(city, worker)

private fun citizenWithinIdleArea(anchor: CivPosition, at: CitizenPoint): Boolean =
    (at.x - (anchor.x + .5)) * (at.x - (anchor.x + .5)) +
        (at.z - (anchor.z + .5)) * (at.z - (anchor.z + .5)) <= CITIZEN_IDLE_RADIUS * CITIZEN_IDLE_RADIUS &&
        at.y in anchor.y - 2.0..anchor.y + 2.0

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
    allowedBuildings: Set<UUID> = emptySet()): CivPosition? {
    val seed = workerId.mostSignificantBits xor workerId.leastSignificantBits
    val offset = Math.floorDiv(step, CITIZEN_WANDER_HEIGHTS.size)
    val (dx, dz) = CITIZEN_WANDER_OFFSETS[Math.floorMod(Math.floorMod(seed, 8L).toInt() + offset, CITIZEN_WANDER_OFFSETS.size)]
    val dy = CITIZEN_WANDER_HEIGHTS[Math.floorMod(step, CITIZEN_WANDER_HEIGHTS.size)]
    val x = storage.x.toLong() + dx
    val y = storage.y.toLong() + dy
    val z = storage.z.toLong() + dz
    if (x !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() || y !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
        z !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return null
    return CivPosition(x.toInt(), y.toInt(), z.toInt()).takeIf {
        citizenRouteCellAllowed(city, allowedBuildings, it.x, it.z) && citizenControlClear(city, it)
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


/** Horizontal footprints stay off transit routes, except the departure and actual destination buildings. */
internal fun citizenRouteCellAllowed(city: Settlement, allowed: Set<UUID>, x: Int, z: Int): Boolean =
    city.contains(x, z) && civsProtectedBuildings(city).none { it.id !in allowed &&
        (x in it.bounds.min.x..it.bounds.max.x && z in it.bounds.min.z..it.bounds.max.z || CivsWorksiteLayouts.isField(it, x, z)) }

internal fun citizenRouteBuildings(city: Settlement, plan: CitizenPlan, target: CivPosition, from: CivPosition): Set<UUID> =
    civsProtectedBuildings(city).filter { building ->
        building.bounds.contains(from.x, from.y, from.z) || building.bounds.contains(target.x, target.y, target.z) ||
            building.control == target ||
            (target == plan.workplace || target == plan.storage) && building.id == plan.token.assignmentId
    }.mapTo(hashSetOf()) { it.id }

/** Authored shafts split a deep search into the actual entrance, climb and gallery legs. */
internal fun citizenMineWaypoint(city: Settlement, world: World, from: Location, target: CivPosition): CivPosition? {
    for (mine in city.buildings.filter { it.type == BuildingType.QUARRY }) {
        val layout = CivsWorksiteLayouts.site(mine)?.getAsJsonObject("mine") ?: continue
        val bottom = CivsWorksiteLayouts.point(mine, CivsWorksiteLayouts.vector(layout["ladderBottom"]))
        val floors = layout.getAsJsonArray("floors").flatMap { floor ->
            floor.asJsonObject.getAsJsonArray("floorCells").map { CivsWorksiteLayouts.point(mine, CivsWorksiteLayouts.vector(it)) }
        }
        fun inMine(x: Int, y: Double, z: Int): Boolean = y < bottom.y - .5 &&
            (x == bottom.x && z == bottom.z || floors.any { abs(x - it.x) <= 1 && abs(z - it.z) <= 1 && y in (it.y.toDouble()..it.y + 4.0) })
        if (abs(from.y - target.y) <= 1.01 ||
            !inMine(from.blockX, from.y, from.blockZ) && !inMine(target.x, target.y.toDouble(), target.z)) continue
        val onShaft = from.blockX == bottom.x && from.blockZ == bottom.z
        val waypoint = when {
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

/** Native 1.21.11 sleeping mesh: head toward -X at yaw zero, .9375 model scale.
 * Include outer skin/armour layers and lift the pose above the actual floor. No fake bed metadata. */
internal fun citizenSleepPose(world: World, feet: Location, scale: Double): CitizenSleepPose? {
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
        if (citizenSleepClear(world, box)) return CitizenSleepPose(origin.clone().also { it.yaw = yaw; it.pitch = 0f }, box)
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

internal fun citizenShelterTarget(city: Settlement, worker: CivWorker, safe: (CivPosition) -> Boolean): CivPosition? {
    val home = citizenHomeBuilding(city, worker) ?: return null
    val place = city.workers.filter { citizenHomeBuilding(city, it)?.id == home.id }.indexOfFirst { it.id == worker.id }.coerceAtLeast(0)
    val origin = home.control ?: home.entrance
    val candidates = sequence {
        for (dy in listOf(0, 1, -1, 2, -2)) for (r in 1..4) for ((dx, dz) in listOf(r to 0, 0 to r, -r to 0, 0 to -r))
            yield(CivPosition(origin.x + dx, origin.y + dy, origin.z + dz))
    }
    val safePlaces = candidates.filter { home.bounds.contains(it.x, it.y, it.z) && safe(it) }.take(maxOf(place + 1, 1)).toList()
    return safePlaces.getOrNull(place) ?: safePlaces.lastOrNull()
}

private fun citizenHomeBuilding(city: Settlement, worker: CivWorker): CivBuilding? {
    worker.homeId?.let { id -> city.building(id)?.takeIf { home ->
        home.type == BuildingType.TOWN_HALL || home.type == BuildingType.HOUSE && home.phase == BuildingPhase.ACTIVE
    }?.let { return it } }
    val homes = city.buildings.filter { it.type == BuildingType.HOUSE && it.phase == BuildingPhase.ACTIVE }
        .flatMap { home -> List(CivsPopulation.houseBeds(home)) { home } }
    return homes.getOrNull(city.workers.indexOfFirst { it.id == worker.id })
        ?: city.buildings.firstOrNull { it.type == BuildingType.TOWN_HALL }
}
