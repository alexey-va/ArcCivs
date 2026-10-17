package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.paper.api.ArcSchematicOperation
import ru.arc.paper.api.ArcSchematicSnapshot
import ru.arc.paper.api.ArcBlockProtectionService
import ru.arc.paper.api.ArcSchematicException
import ru.arc.paper.api.ArcSchematicFailure
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.logging.Level

/** Main-thread authority over small immutable city records; all IO goes through the Core executor. */
class CivsService(
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val settings: CivsSettings,
    private val store: CivsStore,
    private val blueprints: CivsBlueprints,
    private val executor: Executor,
    worldAdapter: CivsWorld? = null,
    previewRenderer: CivsPreviewRenderer? = null,
    citizenRuntime: CivsCitizens? = null,
) : CivsUiHost, Listener, AutoCloseable {
    private data class Preview(
        val cityId: UUID?, val cityName: String, val style: String, val blueprint: CivBlueprint,
        val upgrading: Boolean, val expires: Long,
    )
    private val cities = linkedMapOf<UUID, Settlement>()
    private val busy = mutableSetOf<UUID>()
    private val unhealthy = mutableSetOf<UUID>()
    private val pendingFounders = mutableMapOf<UUID, Pair<UUID, CivPosition>>()
    private val previews = mutableMapOf<UUID, Preview>()
    private val requests = mutableMapOf<UUID, UUID>()
    private val previewLoads = mutableSetOf<UUID>()
    private val completions = mutableSetOf<CompletableFuture<*>>()
    private val placing = mutableSetOf<UUID>()
    private val operations = mutableMapOf<UUID, ArcSchematicOperation>()
    private var initialized = false
    private var closed = false
    private var loading = false
    private val world = worldAdapter ?: CivsWorld(plugin, tasks) { cities.values.toList() }
    private val renderer = previewRenderer ?: CivsPreviewRenderer(plugin, tasks)
    private val protection = CivsProtection(cities = { cities.values.toList() }, available = { ready() }, protectedWorlds = settings.worlds)
    private val sharedProtection = object : ArcBlockProtectionService {
        override fun isProtected(worldId: UUID, x: Int, y: Int, z: Int): Boolean =
            protection.isProtected(worldId, x, y, z)

        override fun permitsSchematicMutation(owner: String, worldId: UUID, x: Int, y: Int, z: Int): Boolean {
            if (owner == "arccivs-construction" && ready() && placing.any { id ->
                cities[id]?.takeIf { it.worldId == worldId }?.let { city ->
                    // Connection repair also visits the immediate neighbors of the committed building.
                    civsProtectedBuildings(city).any { building ->
                        val box = building.bounds
                        x.toLong() in (box.min.x.toLong() - 1)..(box.max.x.toLong() + 1) &&
                            y.toLong() in (box.min.y.toLong() - 1)..(box.max.y.toLong() + 1) &&
                            z.toLong() in (box.min.z.toLong() - 1)..(box.max.z.toLong() + 1)
                    }
                } == true
            }) return true
            return !isProtected(worldId, x, y, z)
        }
    }
    lateinit var ui: CivsUi
    private val citizens = citizenRuntime ?: CivsCitizens(plugin, { if (ready()) cities.values.filter { it.id !in unhealthy } else emptyList() },
        ::deliver, { player, cityId, workerId ->
            if (city(player)?.id == cityId) ui.openWorker(player, cityId, workerId)
        }, settings.maximumNpcs, settings.viewerRange)
    @Volatile private var health = RuntimeHealthContribution(state = RuntimeHealthState.STARTING)

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.pluginManager.registerEvents(protection, plugin)
        plugin.server.servicesManager.register(ArcBlockProtectionService::class.java, sharedProtection, plugin, ServicePriority.Normal)
        tasks.runTimer(20, 20) { pulse() }
    }

    fun start(): CompletableFuture<Unit> = loadCities()
    fun health(): RuntimeHealthContribution = health
    override fun ready(): Boolean = initialized && !closed && !loading
    override fun styles(): List<String> = CivsBlueprints.STYLES
    override fun settlements(): List<Settlement> = cities.values.toList()
    override fun city(player: Player): Settlement? = cities.values.firstOrNull { it.canManage(player.uniqueId) }
    override fun workerStatus(workerId: UUID): String = citizens.status(workerId)
    override fun activeNpcCount(): Int = citizens.activeCount()

    override fun preview(player: Player): CivsPreviewView? = previews[player.uniqueId]?.takeIf { it.expires > now() }?.let {
        val b = it.blueprint.building
        CivsPreviewView(b.type, b.level, it.style, it.cityName, b.anchor, b.rotation,
            b.bounds.max.x - b.bounds.min.x + 1, b.bounds.max.y - b.bounds.min.y + 1,
            b.bounds.max.z - b.bounds.min.z + 1, CivsRules.cost(b.type, b.level), it.blueprint.snapshot.nonBarrierCount)
    }

    override fun prepareFounding(player: Player, name: String, style: String): CompletableFuture<Unit> = action {
        requireReady(player)
        rejectIf(city(player) != null || player.uniqueId in pendingFounders, "already-member")
        rejectIf(name.trim().isBlank() || name.trim().length > 48 || name.any { it.isISOControl() }, "invalid-name")
        val entrance = target(player)
        checkSettlementSpace(player.world.uid, entrance)
        prepare(player, null, name.trim(), style, BuildingType.TOWN_HALL, 1, UUID.randomUUID(), entrance, false)
    }

    override fun prepareBuilding(player: Player, type: BuildingType): CompletableFuture<Unit> = action {
        val city = managed(player)
        rejectIf(type == BuildingType.TOWN_HALL, "invalid-building")
        rejectIf(city.construction != null, "construction-busy")
        val entrance = target(player)
        rejectIf(player.world.uid != city.worldId || !city.contains(entrance.x, entrance.z), "outside-city")
        prepare(player, city.id, city.name, city.style, type, 1, UUID.randomUUID(), entrance, false)
    }

    override fun prepareUpgrade(player: Player, buildingId: UUID): CompletableFuture<Unit> = action {
        val city = managed(player)
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        rejectIf(city.construction != null, "construction-busy")
        rejectIf(building.level >= 3, "invalid-level")
        rejectIf(building.type != BuildingType.TOWN_HALL && building.level >= city.level(), "town-hall-level-required")
        near(player, city.worldId, building.entrance)
        prepare(player, city.id, city.name, city.style, building.type, building.level + 1, building.id,
            building.entrance, true, building.rotation)
    }

    override fun rotatePreview(player: Player): CompletableFuture<Unit> = action {
        val old = currentPreview(player)
        rejectIf(old.upgrading, "upgrade-position-fixed")
        val b = old.blueprint.building
        prepare(player, old.cityId, old.cityName, old.style, b.type, b.level, b.id, b.entrance, false, (b.rotation + 90) % 360)
    }

    override fun movePreview(player: Player): CompletableFuture<Unit> = action {
        val old = currentPreview(player)
        rejectIf(old.upgrading, "upgrade-position-fixed")
        val b = old.blueprint.building
        val entrance = target(player)
        if (old.cityId == null) checkSettlementSpace(player.world.uid, entrance)
        prepare(player, old.cityId, old.cityName, old.style, b.type, b.level, b.id, entrance, false, b.rotation)
    }

    private fun prepare(
        player: Player, cityId: UUID?, name: String, style: String, type: BuildingType, level: Int,
        buildingId: UUID, entrance: CivPosition, upgrading: Boolean, rotation: Int? = null,
    ): CompletableFuture<Unit> {
        requireReady(player)
        rejectIf(style !in styles(), "invalid-style")
        rejectIf(player.uniqueId in previewLoads, "city-busy")
        rejectIf((previews.keys + previewLoads).size >= settings.maximumPreviews && player.uniqueId !in previews, "too-many-previews")
        val worldId = player.world.uid
        if (cityId != null) rejectIf(cities[cityId]?.let { !it.canManage(player.uniqueId) || it.worldId != worldId } != false, "not-authorized")
        val request = UUID.randomUUID()
        requests[player.uniqueId] = request
        previewLoads += player.uniqueId
        previews.remove(player.uniqueId)
        renderer.clear(player.uniqueId)
        val result = tracked<Unit>()
        blueprints.prepare(style, type, level, buildingId, worldId, entrance, facing(player), rotation)
            .whenCompleteSync(tasks) { blueprint, failure ->
                previewLoads -= player.uniqueId
                if (requests[player.uniqueId] != request || !player.isOnline || player.world.uid != worldId) {
                    result.completeExceptionally(CivsRejection("preview-expired"))
                } else if (failure != null) {
                    log("prepare preview", failure)
                    result.completeExceptionally(failure)
                } else runCatching {
                    val prepared = requireNotNull(blueprint)
                    val city = cityId?.let { cities[it] ?: throw CivsRejection("no-city") }
                    if (city != null) rejectIf(!city.canManage(player.uniqueId) ||
                        !city.contains(prepared.building.bounds.min.x, prepared.building.bounds.min.z) ||
                        !city.contains(prepared.building.bounds.max.x, prepared.building.bounds.max.z), "outside-city")
                    previews[player.uniqueId] = Preview(cityId, name, style, prepared, upgrading, now() + settings.previewSeconds * 1_000L)
                    renderer.show(player, prepared.building, prepared.surface)
                    result.complete(Unit)
                }.onFailure { result.completeExceptionally(it) }
            }
        return result
    }

    override fun cancelPreview(player: Player) { cancelPreview(player.uniqueId) }
    private fun cancelPreview(playerId: UUID) {
        requests.remove(playerId)
        previews.remove(playerId)
        renderer.clear(playerId)
    }

    override fun confirmPreview(player: Player): CompletableFuture<Unit> = action {
        val preview = currentPreview(player)
        val blueprint = preview.blueprint
        near(player, blueprint.worldId, blueprint.building.entrance)
        val cityId = preview.cityId ?: UUID.randomUUID()
        val previousCity = preview.cityId?.let { managed(player).also { current -> rejectIf(current.id != it, "not-authorized") } }
        rejectIf(cityId in busy || cityId in unhealthy, "city-busy")
        if (previousCity == null) {
            rejectIf(city(player) != null || player.uniqueId in pendingFounders, "already-member")
            checkSettlementSpace(blueprint.worldId, blueprint.building.entrance)
            pendingFounders[player.uniqueId] = blueprint.worldId to blueprint.building.entrance
        }
        busy += cityId
        val result = tracked<Unit>()
        val oldBuilding = previousCity?.building(blueprint.building.id)
        world.capture(cityId, previousCity?.owner ?: player.uniqueId, blueprint.worldId, blueprint.building, blueprint.cells, oldBuilding)
            .whenCompleteSync(tasks) { captured, captureFailure ->
                if (captureFailure != null) return@whenCompleteSync finishReservation(cityId, player.uniqueId, result, captureFailure)
                runCatching {
                    rejectIf(!player.isOnline || requests[player.uniqueId] == null || previews[player.uniqueId] !== preview, "preview-expired")
                    near(player, blueprint.worldId, blueprint.building.entrance)
                    val plan = requireNotNull(captured).copy(capturedSha256 = blueprint.snapshot.fileSha256)
                    val next = if (previousCity == null) {
                        checkSettlementSpace(blueprint.worldId, blueprint.building.entrance, player.uniqueId)
                        rejectIf(city(player) != null, "already-member")
                        CivsRules.found(cityId, player.uniqueId, preview.cityName, blueprint.worldId,
                            blueprint.building.entrance, settings.radius, preview.style, blueprint.building,
                            (1..4).map { CivWorker(UUID.randomUUID(), workerName(it), null) }, plan.id, now())
                    } else {
                        val current = cities.getValue(cityId)
                        CivsRules.reserveBuild(current, player.uniqueId, blueprint.building,
                            CivConstruction(plan.id, blueprint.building.id, blueprint.building.level,
                                CivsRules.workRequired(blueprint.building.type, blueprint.building.level), 0,
                                ConstructionPhase.CONSTRUCTING, CivsRules.cost(blueprint.building.type, blueprint.building.level),
                                plannedBuilding = blueprint.building))
                    }
                    // Durable captured bytes and before-images precede the city commit and every world write.
                    store.saveSchematic(plan.id, blueprint.sourceBytes).thenCompose { store.savePlan(plan) }
                        .thenCompose { store.save(next) }.whenCompleteSync(tasks) { saved, saveFailure ->
                            if (saveFailure != null) {
                                // An atomic replace may already have committed: never retry from stale cached stock.
                                unhealthy += cityId
                                initialized = false
                                log("commit construction $cityId", saveFailure)
                                finishReservation(cityId, player.uniqueId, result, CivsRejection("storage-unavailable"))
                            } else {
                                publish(requireNotNull(saved))
                                cancelPreview(player.uniqueId)
                                finishReservation(cityId, player.uniqueId, result, null)
                                pulseConstruction()
                            }
                        }
                }.onFailure { finishReservation(cityId, player.uniqueId, result, it) }
            }
        result
    }

    private fun finishReservation(cityId: UUID, playerId: UUID, future: CompletableFuture<Unit>, failure: Throwable?) {
        busy -= cityId
        pendingFounders.remove(playerId)
        if (failure == null) future.complete(Unit) else future.completeExceptionally(failure)
        updateHealth()
    }

    override fun recruit(player: Player): CompletableFuture<Unit> = action {
        val city = managed(player)
        mutate(city.id) { CivsRules.recruit(it, player.uniqueId, UUID.randomUUID(), workerName(it.workers.size + 1)) }.thenApply { Unit }
    }
    override fun assign(player: Player, workerId: UUID, buildingId: UUID?): CompletableFuture<Unit> = action {
        mutate(managed(player).id) { CivsRules.assign(it, player.uniqueId, workerId, buildingId, now()) }.thenApply { Unit }
    }
    override fun addMember(player: Player, name: String): CompletableFuture<Unit> = action {
        val city = managed(player)
        val member = plugin.server.getPlayerExact(name.trim()) ?: throw CivsRejection("player-offline")
        rejectIf(cities.values.any { it.id != city.id && it.canManage(member.uniqueId) } || member.uniqueId in pendingFounders, "already-member")
        mutate(city.id) { CivsRules.addMember(it, player.uniqueId, member.uniqueId) }.thenApply { Unit }
    }
    override fun removeMember(player: Player, memberId: UUID): CompletableFuture<Unit> = action {
        mutate(managed(player).id) { CivsRules.removeMember(it, player.uniqueId, memberId) }.thenApply { Unit }
    }
    override fun retryConstruction(player: Player, cityId: UUID): CompletableFuture<Unit> = action {
        requireReady(player)
        val city = cities[cityId] ?: throw CivsRejection("no-city")
        rejectIf(!city.canManage(player.uniqueId) && !player.hasPermission("arccivs.admin"), "not-authorized")
        val order = city.construction ?: throw CivsRejection("no-construction")
        mutate(cityId) { CivsRules.retryConstruction(it, it.owner, order.id) }.thenApply { Unit }
    }
    override fun reload(player: Player): CompletableFuture<Unit> = action {
        rejectIf(!player.hasPermission("arccivs.admin"), "not-authorized")
        rejectIf(busy.isNotEmpty() || placing.isNotEmpty() || previewLoads.isNotEmpty(), "city-busy")
        loadCities()
    }

    private fun deliver(cityId: UUID, workerId: UUID, assignment: UUID, cycle: Long, nextAt: Long, constructionId: UUID?, at: Long): CompletableFuture<Settlement> = action {
        rejectIf(!ready(), "not-ready")
        mutate(cityId) { city ->
            if (city.building(assignment)?.type == BuildingType.TOWN_HALL) rejectIf(city.construction?.id != constructionId, "stale-delivery")
            CivsRules.deliver(city, city.owner, workerId, assignment, cycle, nextAt, at)
        }
    }

    private fun mutate(cityId: UUID, transform: (Settlement) -> Settlement): CompletableFuture<Settlement> {
        checkMain()
        rejectIf(cityId in unhealthy, "storage-unavailable")
        rejectIf(cityId in busy, "city-busy")
        val city = cities[cityId] ?: throw CivsRejection("no-city")
        val next = transform(city)
        if (next == city) return CompletableFuture.completedFuture(city)
        busy += cityId
        val result = tracked<Settlement>()
        store.save(next).whenCompleteSync(tasks) { saved, failure ->
            busy -= cityId
            if (failure == null) {
                publish(requireNotNull(saved))
                result.complete(saved)
            } else {
                unhealthy += cityId
                log("save settlement $cityId", failure)
                result.completeExceptionally(CivsRejection("storage-unavailable"))
            }
            updateHealth()
        }
        return result
    }

    private fun loadCities(): CompletableFuture<Unit> {
        checkMain()
        rejectIf(loading || closed, "not-ready")
        loading = true
        previews.keys.toList().forEach(::cancelPreview)
        citizens.pulse(now()) // Provider is empty while loading: despawn previous runtime actors.
        val result = tracked<Unit>()
        store.load().whenCompleteSync(tasks) { loaded, failure ->
            loading = false
            if (failure != null) {
                initialized = false
                log("load settlements", failure)
                result.completeExceptionally(failure)
            } else runCatching {
                val records = requireNotNull(loaded)
                val members = records.flatMap { it.members + it.owner }
                require(members.distinct().size == members.size) { "A player belongs to more than one settlement" }
                cities.clear()
                records.forEach { cities[it.id] = it }
                protection.refresh()
                unhealthy.clear()
                initialized = true
                result.complete(Unit)
                pulseConstruction()
            }.onFailure { initialized = false; result.completeExceptionally(it); log("validate settlements", it) }
            updateHealth()
        }
        return result
    }

    private fun publish(city: Settlement) {
        val previous = cities.put(city.id, city)
        if (previous?.buildings != city.buildings || previous?.construction?.plannedBuilding != city.construction?.plannedBuilding) {
            protection.refresh()
        }
    }

    private fun pulse() {
        if (closed) return
        previews.filterValues { it.expires <= now() }.keys.toList().forEach(::cancelPreview)
        citizens.pulse(now())
        pulseConstruction()
        updateHealth()
    }

    private fun pulseConstruction() {
        if (!ready()) return
        for (city in cities.values.toList()) {
            if (placing.size >= 2) break
            val order = city.construction ?: continue
            if (city.id in busy || city.id in unhealthy || city.id in placing) continue
            if (order.phase !in setOf(ConstructionPhase.READY_TO_PLACE, ConstructionPhase.PLACING)) continue
            // A reloaded city sleeps until its destination chunks are already available.
            val b = order.plannedBuilding ?: continue
            val w = plugin.server.getWorld(city.worldId) ?: continue
            if (!w.isChunkLoaded(b.entrance.x shr 4, b.entrance.z shr 4)) continue
            startPlacement(city, order)
        }
    }

    private fun startPlacement(city: Settlement, order: CivConstruction) {
        placing += city.id
        store.loadPlan(order.id).thenCompose { plan ->
            requireNotNull(plan) { "Missing captured construction plan ${order.id}" }
            require(plan.cityId == city.id && plan.worldId == city.worldId && plan.building == order.plannedBuilding)
            blueprints.recover(plan).thenApply { snapshot -> plan to snapshot }
        }.whenCompleteSync(tasks) { recovered, failure ->
            if (failure != null) return@whenCompleteSync placementFailed(city.id, order.id, failure)
            val (plan, snapshot) = requireNotNull(recovered)
            world.revalidate(plan, city.owner).whenCompleteSync(tasks) { _, validationFailure ->
                if (validationFailure != null) return@whenCompleteSync placementFailed(city.id, order.id, validationFailure)
                runCatching {
                    mutate(city.id) { CivsRules.beginPlacement(it, it.owner, order.id) }
                        .whenCompleteSync(tasks) { _, beginFailure ->
                            if (beginFailure != null) return@whenCompleteSync placementFailed(city.id, order.id, beginFailure)
                            pasteCaptured(city.id, order.id, plan, snapshot)
                        }
                }.onFailure { placementFailed(city.id, order.id, it) }
            }
        }
    }

    private fun pasteCaptured(cityId: UUID, orderId: UUID, plan: CivPlacementPlan, snapshot: ArcSchematicSnapshot) {
        runCatching {
            val operation = blueprints.paste(snapshot, plan.worldId, plan.building.anchor)
            operations[cityId] = operation
            operation.completion.whenCompleteSync(tasks) { mutation, failure ->
                operations.remove(cityId)
                if (failure != null || mutation?.cancelled != false) {
                    placementFailed(cityId, orderId, failure ?: CivsRejection("placement-cancelled"))
                } else runCatching {
                    mutate(cityId) { CivsRules.completeConstruction(it, it.owner, orderId) }
                        .whenCompleteSync(tasks) { _, commitFailure ->
                            if (commitFailure != null) placementFailed(cityId, orderId, commitFailure)
                            else repairCommitted(cityId, plan, snapshot)
                        }
                }.onFailure { placementFailed(cityId, orderId, it) }
            }
        }.onFailure { placementFailed(cityId, orderId, it) }
    }

    private fun repairCommitted(cityId: UUID, plan: CivPlacementPlan, snapshot: ArcSchematicSnapshot) {
        // Shape repair is cosmetic and happens only after the durable build commit.
        CompletableFuture.supplyAsync({ CivsBlueprints.worldCells(snapshot, plan.building.anchor) }, executor)
            .whenCompleteSync(tasks) { cells, failure ->
                if (failure != null) { placing -= cityId; log("prepare shape repair $cityId", failure); return@whenCompleteSync }
                runCatching {
                    val repair = blueprints.repair(plan.worldId, requireNotNull(cells))
                    operations[cityId] = repair
                    repair.completion.whenCompleteSync(tasks) { _, repairFailure ->
                        operations.remove(cityId)
                        placing -= cityId
                        if (repairFailure != null) log("repair shapes $cityId", repairFailure)
                        else store.deletePlan(plan.id).thenCompose { store.deleteSchematic(plan.id) }
                            .whenComplete { _, cleanupFailure -> if (cleanupFailure != null) log("clean captured plan ${plan.id}", cleanupFailure) }
                        updateHealth()
                    }
                }.onFailure { placing -= cityId; log("repair shapes $cityId", it) }
            }
    }

    private fun placementFailed(cityId: UUID, orderId: UUID, failure: Throwable) {
        operations.remove(cityId)
        placing -= cityId
        log("place construction $orderId", failure)
        if (cityId in unhealthy || cities[cityId]?.construction?.id != orderId) return
        val causes = generateSequence(failure) { it.cause }.toList()
        val reason = causes.filterIsInstance<CivsRejection>().firstOrNull()?.reason
            ?: if (causes.filterIsInstance<ArcSchematicException>().any { it.reason == ArcSchematicFailure.PROTECTED_AREA }) "protected-area" else "placement-failed"
        runCatching { mutate(cityId) { CivsRules.pauseConstruction(it, it.owner, orderId, reason) } }
            .onFailure { log("pause construction $orderId", it) }
    }

    private fun currentPreview(player: Player): Preview {
        requireReady(player)
        return previews[player.uniqueId]?.takeIf { it.expires > now() } ?: throw CivsRejection("preview-expired")
    }
    private fun managed(player: Player): Settlement {
        requireReady(player)
        val city = city(player) ?: throw CivsRejection("no-city")
        rejectIf(city.id in unhealthy, "storage-unavailable")
        return city
    }
    private fun requireReady(player: Player) {
        checkMain()
        rejectIf(!ready(), "not-ready")
        rejectIf(!player.hasPermission("arccivs.use"), "not-authorized")
    }
    private fun target(player: Player): CivPosition {
        rejectIf(player.world.name !in settings.worlds, "wrong-world")
        val hit = player.rayTraceBlocks(12.0, FluidCollisionMode.NEVER)?.hitBlock ?: throw CivsRejection("look-at-ground")
        rejectIf(!hit.type.isSolid || hit.isLiquid, "look-at-ground")
        return CivPosition(hit.x, hit.y + 1, hit.z)
    }
    private fun near(player: Player, worldId: UUID, point: CivPosition) {
        rejectIf(player.world.uid != worldId || player.location.toVector().distanceSquared(org.bukkit.util.Vector(point.x.toDouble(), point.y.toDouble(), point.z.toDouble())) > 96.0 * 96.0, "too-far")
    }
    private fun facing(player: Player): Int = Math.floorMod(kotlin.math.floor((player.location.yaw + 45.0) / 90.0).toInt(), 4)
    private fun checkSettlementSpace(worldId: UUID, center: CivPosition, ignoreFounder: UUID? = null) {
        rejectIf(cities.size + pendingFounders.keys.count { it != ignoreFounder } >= settings.maximumCities, "settlement-limit")
        rejectIf(cities.values.any { it.worldId == worldId && areasOverlap(center, settings.radius, it.center, it.radius) } ||
            pendingFounders.any { (owner, place) -> owner != ignoreFounder && place.first == worldId && areasOverlap(center, settings.radius, place.second, settings.radius) }, "city-overlap")
    }
    private fun updateHealth() {
        health = RuntimeHealthContribution(
            state = when { closed || !initialized && !loading -> RuntimeHealthState.DOWN; loading -> RuntimeHealthState.STARTING; unhealthy.isNotEmpty() -> RuntimeHealthState.DEGRADED; else -> RuntimeHealthState.UP },
            recoveryBacklog = cities.values.count { it.construction != null } + unhealthy.count { it !in cities },
            activeLeases = previews.size + placing.size,
            schemas = mapOf("city" to 1, "plan" to 1),
            dependencies = mapOf("storage" to (initialized && unhealthy.isEmpty())),
        )
    }
    private fun <T> tracked(): CompletableFuture<T> = CompletableFuture<T>().also { future ->
        completions += future
        future.whenComplete { _, _ -> if (Bukkit.isPrimaryThread()) completions -= future else tasks.runSync { completions -= future } }
    }
    private fun <T> action(block: () -> CompletableFuture<T>): CompletableFuture<T> = try { checkMain(); block() } catch (error: Exception) { CompletableFuture.failedFuture(error) }
    private fun rejectIf(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
    private fun now() = System.currentTimeMillis()
    private fun log(context: String, error: Throwable) = plugin.logger.log(Level.WARNING, "ArcCivs could not $context", error)
    private fun checkMain() = check(Bukkit.isPrimaryThread()) { "ArcCivs authority must run on Paper's main thread" }

    @EventHandler fun quit(event: PlayerQuitEvent) { cancelPreview(event.player.uniqueId) }
    @EventHandler fun worldChanged(event: PlayerChangedWorldEvent) { cancelPreview(event.player.uniqueId) }

    override fun close() {
        if (closed) return
        closed = true
        initialized = false
        HandlerList.unregisterAll(this)
        operations.values.forEach { it.cancel() }
        operations.clear()
        citizens.close()
        world.close()
        HandlerList.unregisterAll(protection)
        plugin.server.servicesManager.unregister(ArcBlockProtectionService::class.java, sharedProtection)
        renderer.close()
        previews.clear()
        requests.clear()
        previewLoads.clear()
        completions.toList().forEach { it.completeExceptionally(CivsRejection("not-ready")) }
        completions.clear()
        updateHealth()
    }

    companion object {
        internal fun areasOverlap(a: CivPosition, ar: Int, b: CivPosition, br: Int): Boolean =
            kotlin.math.abs(a.x.toLong() - b.x) <= ar.toLong() + br && kotlin.math.abs(a.z.toLong() - b.z) <= ar.toLong() + br
        private fun workerName(number: Int): String = listOf("Алекс", "Мира", "Рен", "Тора", "Лис", "Ива", "Ник", "Элла", "Дан", "Лея", "Кай", "Ада")[(number - 1) % 12]
    }
}
