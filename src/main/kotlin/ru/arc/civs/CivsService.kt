package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Material
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
import ru.arc.paper.network.BackendTransfer
import ru.arc.paper.network.BackendTransferResult
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
    nativeWork: ru.arc.core.BudgetedTaskQueue? = null,
    private val backendTransfer: BackendTransfer? = null,
    onCitizensCreated: (CivsCitizens) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    boundaryRenderer: CivsBoundaryRenderer? = null,
    storageAdapter: CivsStorage? = null,
) : CivsUiHost, Listener, AutoCloseable {
    private data class Preview(
        val id: UUID, val cityId: UUID?, val cityName: String, val style: String, val blueprint: CivBlueprint,
        val upgrading: Boolean, val expires: Long, val movementOrigin: CivPosition,
    )
    private data class Selection(
        val id: UUID, val worldId: UUID, val cityId: UUID?, val name: String, val style: String,
        val type: BuildingType, val expires: Long,
    )
    private class PlacementAccess(val plan: CivPlacementPlan, val materials: Map<CivPosition, Material>, val containersChanged: Boolean) {
        var repairing = false
    }
    private data class RecoveredPlacement(val plan: CivPlacementPlan, val snapshot: ArcSchematicSnapshot,
        val access: PlacementAccess, val batch: CivBuildBatch?, val validationPlan: CivPlacementPlan)
    private var cities = linkedMapOf<UUID, Settlement>()
    private val remoteCities = mutableMapOf<UUID, CivsNetworkCity>()
    private val lookups = mutableMapOf<UUID, UUID>()
    private val busy = mutableSetOf<UUID>()
    private val unhealthy = mutableSetOf<UUID>()
    private val pendingFounders = mutableMapOf<UUID, Pair<UUID, CivPosition>>()
    private val previews = mutableMapOf<UUID, Preview>()
    private val selections = mutableMapOf<UUID, Selection>()
    private val requests = mutableMapOf<UUID, UUID>()
    private val previewLoads = mutableSetOf<UUID>()
    private val completions = mutableSetOf<CompletableFuture<*>>()
    private val placing = mutableSetOf<UUID>()
    private val placementAccess = mutableMapOf<UUID, PlacementAccess>()
    private val operations = mutableMapOf<UUID, ArcSchematicOperation>()
    private var initialized = false
    private var closed = false
    private var citizenDrain = CompletableFuture.completedFuture<Void>(null)
    private var loading = false
    private var loadFailure: String? = null
    private var geometryGeneration = 0L
    private val world = worldAdapter ?: CivsWorld(plugin, tasks) { cities.values.toList() }
    private val boundary = boundaryRenderer ?: CivsBoundaryRenderer(plugin, tasks, nativeWork)
    private val storage = (storageAdapter ?: CivsStorage(plugin)).also { it.beginReceiptBootstrap() }
    private val inventoryRecovery = linkedMapOf<UUID, CivInventoryTransaction>()
    private val blockedRecovery = mutableSetOf<UUID>()
    private val renderer = previewRenderer ?: CivsPreviewRenderer(plugin, tasks, nativeWork)
    private val protection = CivsProtection(cities = { cities.values.toList() }, available = { ready() }, protectedWorlds = settings.worlds)
    private val sharedProtection = object : ArcBlockProtectionService {
        override fun isProtected(worldId: UUID, x: Int, y: Int, z: Int): Boolean =
            protection.isProtected(worldId, x, y, z)

        override fun permitsSchematicMutation(owner: String, worldId: UUID, x: Int, y: Int, z: Int): Boolean {
            if (!Bukkit.isPrimaryThread()) return false
            if (owner != "arccivs-construction") return !isProtected(worldId, x, y, z)
            if (!ready()) return false
            val position = CivPosition(x, y, z)
            val access = placementAccess.values.firstOrNull { access ->
                val box = access.plan.building.bounds
                access.plan.worldId == worldId && if (access.repairing) {
                    x.toLong() in (box.min.x.toLong() - 1)..(box.max.x.toLong() + 1) &&
                        y.toLong() in (box.min.y.toLong() - 1)..(box.max.y.toLong() + 1) &&
                        z.toLong() in (box.min.z.toLong() - 1)..(box.max.z.toLong() + 1)
                } else box.contains(x, y, z) && position in access.materials
            } ?: return false
            val city = cities[access.plan.cityId] ?: return false
            if (city.id !in placing || city.id in unhealthy || city.worldId != worldId) return false
            val targetWorld = plugin.server.getWorld(worldId) ?: return false
            if (y !in targetWorld.minHeight until targetWorld.maxHeight || !targetWorld.isChunkLoaded(x shr 4, z shr 4)) return false
            val block = targetWorld.getBlockAt(x, y, z)
            // Preflight can precede this write by many ticks; Lands authority must still be current.
            return world.canModify(city.owner, block, if (access.repairing) block.type else access.materials.getValue(position))
        }
    }
    lateinit var ui: CivsUi
    private val citizens = (citizenRuntime ?: CivsCitizens(plugin, { if (ready()) cities.values.filter { it.id !in unhealthy } else emptyList() },
        ::deliver, { player, cityId, workerId ->
            if (city(player)?.id == cityId) ui.openWorker(player, cityId, workerId)
        }, settings.maximumNpcs, settings.viewerRange)).also(onCitizensCreated)
    @Volatile private var health = RuntimeHealthContribution(state = RuntimeHealthState.STARTING)

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.pluginManager.registerEvents(protection, plugin)
        plugin.server.servicesManager.register(ArcBlockProtectionService::class.java, sharedProtection, plugin, ServicePriority.Normal)
        tasks.runTimer(1, 1) { if (ready()) citizens.activateNavigating() }
        tasks.runTimer(20, 20) { pulse() }
    }

    fun start(): CompletableFuture<Unit> = loadCities()
    fun health(): RuntimeHealthContribution = health
    override fun ready(): Boolean = initialized && !closed && !loading
    override fun unavailableReason(): String? = loadFailure ?: if (!initialized && unhealthy.isNotEmpty()) "storage-unavailable" else null
    override fun styles(): List<String> = CivsBlueprints.STYLES
    override fun settlements(): List<Settlement> = cities.values.toList()
    override fun city(player: Player): Settlement? = cities.values.firstOrNull { it.canManage(player.uniqueId) }
    override fun remoteCity(player: Player): CivsNetworkCity? = remoteCities[player.uniqueId]
    override fun cityBusy(player: Player): Boolean = city(player)?.id?.let { it in busy || it in unhealthy } == true
    override fun foundingAllowed(): Boolean = settings.worlds.isNotEmpty()
    override fun refreshCity(player: Player): CompletableFuture<Unit> = action("lookup", player) {
        requireReady(player)
        val request = UUID.randomUUID()
        lookups[player.uniqueId] = request
        remoteCities.remove(player.uniqueId)
        val result = tracked<Unit>()
        store.lookup(player.uniqueId).whenCompleteSync(tasks) { record, failure ->
            if (lookups[player.uniqueId] != request || !player.isOnline) {
                result.completeExceptionally(CivsRejection("lookup-expired"))
            } else {
                lookups.remove(player.uniqueId)
                when {
                    failure != null -> {
                        log("read network membership ${player.uniqueId}", failure)
                        result.completeExceptionally(CivsRejection("network-unavailable"))
                    }
                    record != null && record.server != store.homeServer() -> {
                        remoteCities[player.uniqueId] = record
                        result.complete(Unit)
                    }
                    record != null && city(player)?.id != record.city.id -> {
                        initialized = false
                        loadFailure = "storage-unavailable"
                        updateHealth()
                        result.completeExceptionally(CivsRejection("storage-unavailable"))
                    }
                    else -> result.complete(Unit)
                }
            }
        }
        result
    }
    override fun transferHome(player: Player): CompletableFuture<Unit> = action("transfer", player) {
        requireReady(player)
        val destination = remoteCity(player)?.server ?: throw CivsRejection("no-city")
        val outcome = backendTransfer?.connect(player, destination) ?: BackendTransferResult.TRANSFER_CLOSED
        CivsDiagnostics.event("transfer", *CivsDiagnostics.actor(player), "destination" to destination.value,
            "outcome" to if (outcome == BackendTransferResult.SENT) "requested" else outcome.name.lowercase())
        rejectIf(outcome != BackendTransferResult.SENT, "transfer-failed")
        CompletableFuture.completedFuture(Unit)
    }
    override fun workerStatus(workerId: UUID): String = citizens.status(workerId)
    override fun activeNpcCount(): Int = citizens.activeCount()

    override fun preview(player: Player): CivsPreviewView? = previews[player.uniqueId]?.takeIf { it.expires > now() }?.let {
        val b = it.blueprint.building
        CivsPreviewView(b.type, b.level, it.style, it.cityName, b.anchor, b.rotation,
            b.bounds.max.x - b.bounds.min.x + 1, b.bounds.max.y - b.bounds.min.y + 1,
            b.bounds.max.z - b.bounds.min.z + 1, CivsRules.cost(b.type, b.level), it.blueprint.snapshot.nonBarrierCount,
            it.id, it.blueprint.worldId, b.entrance, b.bounds, !it.upgrading, player.uniqueId in previewLoads, it.blueprint.materials,
            b.control ?: b.anchor)
    }

    override fun selection(player: Player): CivsSelectionView? {
        val selection = selections[player.uniqueId] ?: return null
        if (selection.expires <= now()) {
            cancelSelection(player.uniqueId, "expired")
            return null
        }
        return CivsSelectionView(selection.id, selection.worldId, selection.type, selection.expires,
            player.uniqueId in previewLoads)
    }

    override fun beginFoundingSelection(player: Player, name: String, style: String): CompletableFuture<Unit> = action("begin_founding_selection", player, "city_name" to name, "style" to style) {
        requireReady(player)
        requireFounder(player)
        rejectIf(name.trim().isBlank() || name.trim().length > 48 || name.any { it.isISOControl() }, "invalid-name")
        beginSelection(player, null, name.trim(), style, BuildingType.TOWN_HALL)
    }

    override fun beginBuildingSelection(player: Player, type: BuildingType): CompletableFuture<Unit> = action("begin_building_selection", player, "type" to type) {
        val city = managed(player)
        rejectIf(type == BuildingType.TOWN_HALL, "invalid-building")
        rejectIf(city.construction != null, "construction-busy")
        rejectIf(player.world.uid != city.worldId, "outside-city")
        beginSelection(player, city.id, city.name, city.style, type)
    }

    private fun beginSelection(player: Player, cityId: UUID?, name: String, style: String, type: BuildingType): CompletableFuture<Unit> {
        requirePlacementWorld(player)
        rejectIf(style !in styles(), "invalid-style")
        requirePreviewSlot(player)
        if (player.uniqueId in previews || player.uniqueId in selections || player.uniqueId in requests)
            cancelPreview(player.uniqueId, "replaced")
        val selection = Selection(UUID.randomUUID(), player.world.uid, cityId, name, style, type,
            now() + settings.previewSeconds * 1_000L)
        selections[player.uniqueId] = selection
        CivsDiagnostics.event("selection_begin", *CivsDiagnostics.actor(player), "selection" to selection.id,
            "city" to cityId, "city_name" to name, "style" to style, "type" to type, "expires_at" to selection.expires)
        return CompletableFuture.completedFuture(Unit)
    }

    override fun selectGround(player: Player, selectionId: UUID, target: CivsGroundTarget): CompletableFuture<Unit> =
        action("select_ground", player, "selection" to selectionId, "target_world" to target.worldId, "block" to target.block) {
            requireReady(player)
            val selection = selections[player.uniqueId]?.takeIf { it.id == selectionId && it.expires > now() }
                ?: throw CivsRejection("selection-expired")
            rejectIf(selection.worldId != player.world.uid, "selection-expired")
            rejectIf(player.uniqueId in previewLoads, "city-busy")
            val point = clickedPlacement(player, target)
            if (selection.cityId == null) {
                requireFounder(player)
                checkSettlementSpace(player.world.uid, point)
            } else {
                val city = managed(player)
                rejectIf(city.id != selection.cityId, "not-authorized")
                rejectIf(city.construction != null, "construction-busy")
                rejectIf(!city.contains(point.x, point.z), "outside-city")
            }
            CivsDiagnostics.event("selection_selected", *CivsDiagnostics.actor(player), "selection" to selection.id,
                "block" to target.block, "placement" to point, "type" to selection.type)
            prepare(player, selection.cityId, selection.name, selection.style, selection.type, 1, UUID.randomUUID(),
                point, false, selectionId = selection.id)
        }.whenComplete { _, failure ->
            if (failure != null) CivsDiagnostics.event("selection_rejected", "player" to player.uniqueId,
                "selection" to selectionId, "block" to target.block, "reason" to CivsDiagnostics.rejection(failure)?.reason,
                "cause" to CivsDiagnostics.cause(failure)?.javaClass?.simpleName)
        }

    override fun cancelSelection(player: Player, reason: String) = cancelSelection(player.uniqueId, reason)

    private fun cancelSelection(playerId: UUID, reason: String) {
        val selection = selections.remove(playerId) ?: return
        requests.remove(playerId)
        CivsDiagnostics.event(if (reason == "expired") "selection_expired" else "selection_cancel",
            "player" to playerId, "selection" to selection.id, "reason" to reason,
            "world" to selection.worldId, "type" to selection.type)
    }

    private fun requireFounder(player: Player) {
        rejectIf(city(player) != null || remoteCity(player) != null || player.uniqueId in pendingFounders, "already-member")
    }

    override fun prepareUpgrade(player: Player, buildingId: UUID): CompletableFuture<Unit> = action("prepare_upgrade", player, "building" to buildingId) {
        val city = managed(player)
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        rejectIf(city.construction != null, "construction-busy")
        rejectIf(building.level >= 3, "invalid-level")
        rejectIf(building.type != BuildingType.TOWN_HALL && building.level >= city.level(), "town-hall-level-required")
        near(player, city.worldId, building.entrance)
        prepare(player, city.id, city.name, city.style, building.type, building.level + 1, building.id,
            building.anchor, true, building.rotation, fixedEntrance = building.entrance)
    }

    override fun rotatePreview(player: Player, degrees: Int): CompletableFuture<Unit> = action("rotate_preview", player, "degrees" to degrees) {
        val old = currentPreview(player)
        rejectIf(old.upgrading, "upgrade-position-fixed")
        require(degrees == -90 || degrees == 90)
        val b = old.blueprint.building
        prepare(player, old.cityId, old.cityName, old.style, b.type, b.level, b.id, b.anchor, false,
            Math.floorMod(b.rotation + degrees, 360), old.movementOrigin)
    }

    override fun movePreview(player: Player): CompletableFuture<Unit> = action("move_preview", player) {
        val old = currentPreview(player)
        rejectIf(old.upgrading, "upgrade-position-fixed")
        movePreviewTo(player, old, target(player))
    }

    override fun movePreview(player: Player, target: CivsGroundTarget): CompletableFuture<Unit> =
        action("move_preview", player, "target_world" to target.worldId, "block" to target.block) {
            val old = currentPreview(player)
            rejectIf(old.upgrading, "upgrade-position-fixed")
            movePreviewTo(player, old, clickedPlacement(player, target))
        }

    private fun movePreviewTo(player: Player, old: Preview, placement: CivPosition): CompletableFuture<Unit> {
        val b = old.blueprint.building
        if (old.cityId == null) checkSettlementSpace(player.world.uid, placement)
        return prepare(player, old.cityId, old.cityName, old.style, b.type, b.level, b.id, placement, false, b.rotation)
    }

    override fun nudgePreview(player: Player, direction: CivsPreviewMove): CompletableFuture<Unit> = action("nudge_preview", player, "direction" to direction) {
        val old = currentPreview(player)
        rejectIf(old.upgrading, "upgrade-position-fixed")
        val b = old.blueprint.building
        val anchor = civsMovedEntrance(b.anchor, direction, facing(player))
        rejectIf(listOf(anchor.x.toLong() - old.movementOrigin.x, anchor.y.toLong() - old.movementOrigin.y,
            anchor.z.toLong() - old.movementOrigin.z).any { kotlin.math.abs(it) > 64 }, "placement-offset-limit")
        near(player, old.blueprint.worldId, anchor)
        rejectIf(anchor.y <= player.world.minHeight || anchor.y + 1 >= player.world.maxHeight, "outside-world")
        if (old.cityId == null) checkSettlementSpace(player.world.uid, anchor)
        prepare(player, old.cityId, old.cityName, old.style, b.type, b.level, b.id, anchor, false, b.rotation, old.movementOrigin)
    }

    private fun prepare(
        player: Player, cityId: UUID?, name: String, style: String, type: BuildingType, level: Int,
        buildingId: UUID, placement: CivPosition, upgrading: Boolean, rotation: Int? = null, movementOrigin: CivPosition? = null,
        selectionId: UUID? = null, fixedEntrance: CivPosition? = null,
    ): CompletableFuture<Unit> {
        requireReady(player)
        rejectIf(style !in styles(), "invalid-style")
        requirePreviewSlot(player)
        val worldId = player.world.uid
        if (cityId != null) rejectIf(cities[cityId]?.let { !it.canManage(player.uniqueId) || it.worldId != worldId } != false, "not-authorized")
        val request = UUID.randomUUID()
        requests[player.uniqueId] = request
        CivsDiagnostics.event("preview_load", *CivsDiagnostics.actor(player), "request" to request,
            "city" to cityId, "city_name" to name, "style" to style, "type" to type,
            "placement" to placement, "fixed_entrance" to fixedEntrance, "rotation" to rotation)
        previewLoads += player.uniqueId
        // Keep the last usable position visible until the replacement is ready.
        val result = tracked<Unit>()
        blueprints.prepare(style, type, level, buildingId, worldId, placement, facing(player), rotation, fixedEntrance)
            .whenCompleteSync(tasks) { blueprint, failure ->
                previewLoads -= player.uniqueId
                if (requests[player.uniqueId] != request || !player.isOnline || player.world.uid != worldId ||
                    selectionId != null && selections[player.uniqueId]?.let { it.id == selectionId && it.expires > now() } != true) {
                    result.completeExceptionally(CivsRejection("preview-expired"))
                } else if (failure != null) {
                    log("prepare preview", failure)
                    result.completeExceptionally(failure)
                } else runCatching {
                    val city = cityId?.let { cities[it] ?: throw CivsRejection("no-city") }
                    val source = requireNotNull(blueprint)
                    val prepared = source.copy(building = source.building.copy(production = city?.building(buildingId)?.production))
                    if (city != null) rejectIf(!city.canManage(player.uniqueId) ||
                        !city.contains(prepared.building.bounds.min.x, prepared.building.bounds.min.z) ||
                        !city.contains(prepared.building.bounds.max.x, prepared.building.bounds.max.z), "outside-city")
                    renderer.show(player, prepared.building, prepared.surface)
                    previews[player.uniqueId] = Preview(request, cityId, name, style, prepared, upgrading,
                        now() + settings.previewSeconds * 1_000L, movementOrigin ?: prepared.building.anchor)
                    if (selectionId != null) selections.remove(player.uniqueId)
                    CivsDiagnostics.event("preview_ready", "player" to player.uniqueId, "request" to request,
                        "selection" to selectionId, "world" to worldId, "bounds" to prepared.building.bounds,
                        "control" to prepared.building.control, "entrance" to prepared.building.entrance,
                        "anchor" to prepared.building.anchor, "blocks" to prepared.snapshot.nonBarrierCount)
                    result.complete(Unit)
                }.onFailure { result.completeExceptionally(it) }
            }
        return result
    }

    override fun cancelPreview(player: Player) { cancelPreview(player.uniqueId) }
    private fun cancelPreview(playerId: UUID, reason: String = "cancelled") {
        cancelSelection(playerId, reason)
        val request = requests.remove(playerId)
        if (request != null) CivsDiagnostics.event("preview_cancel", "player" to playerId, "request" to request)
        previews.remove(playerId)
        renderer.clear(playerId)
    }

    override fun confirmPreview(player: Player): CompletableFuture<Unit> = action("confirm_preview", player) {
        val preview = currentPreview(player)
        val blueprint = preview.blueprint
        val cityCenter = blueprint.building.control ?: blueprint.building.entrance
        near(player, blueprint.worldId, blueprint.building.entrance)
        val cityId = preview.cityId ?: UUID.randomUUID()
        val previousCity = preview.cityId?.let { managed(player).also { current -> rejectIf(current.id != it, "not-authorized") } }
        if (preview.upgrading && previousCity?.building(blueprint.building.id)?.control != blueprint.building.control) {
            return@action relocateControl(player, blueprint.building.id, requireNotNull(blueprint.building.control))
                .thenCompose { confirmPreview(player) }
        }
        rejectIf(cityId in busy || cityId in unhealthy, "city-busy")
        if (previousCity == null) {
            requireFounder(player)
            checkSettlementSpace(blueprint.worldId, cityCenter)
            pendingFounders[player.uniqueId] = blueprint.worldId to cityCenter
        }
        busy += cityId
        val result = tracked<Unit>()
        val capture = if (preview.upgrading) world.capture(cityId, previousCity?.owner ?: player.uniqueId, blueprint.worldId,
            blueprint.building, blueprint.cells, previousCity?.building(blueprint.building.id))
        else world.captureSite(cityId, previousCity?.owner ?: player.uniqueId, blueprint.worldId, blueprint.building,
            blueprint.cells, previousCity?.building(blueprint.building.id))
        capture.whenCompleteSync(tasks) { captured, captureFailure ->
            if (captureFailure != null) return@whenCompleteSync finishReservation(cityId, player.uniqueId, result, captureFailure)
            runCatching {
                rejectIf(!player.isOnline || previews[player.uniqueId] !== preview, "preview-expired")
                near(player, blueprint.worldId, blueprint.building.entrance)
                val capturedPlan = requireNotNull(captured).copy(capturedSha256 = blueprint.snapshot.fileSha256)
                if (preview.upgrading) {
                    startOrder(player, cities.getValue(cityId), capturedPlan, blueprint.sourceBytes).whenCompleteSync(tasks) { _, failure ->
                        if (failure == null) cancelPreview(player.uniqueId)
                        finishReservation(cityId, player.uniqueId, result, failure)
                    }
                } else {
                    val control = prepareControl(cityId, blueprint.worldId, previousCity?.owner ?: player.uniqueId, blueprint.building)
                    // The immutable design belongs to the SITE until the player orders construction.
                    val plan = capturedPlan.copy(id = blueprint.building.id)
                    val placed = if (previousCity == null) {
                        checkSettlementSpace(blueprint.worldId, cityCenter, player.uniqueId)
                        requireFounderAfterCapture(player)
                        CivsRules.found(cityId, player.uniqueId, preview.cityName, blueprint.worldId,
                            cityCenter, settings.radius, preview.style, blueprint.building, now())
                    } else CivsRules.placeSite(cities.getValue(cityId), player.uniqueId, blueprint.building)
                    val next = CivsRules.validate(placed.copy(pendingControls = placed.pendingControls.orEmpty() + control))
                    store.saveSchematic(plan.id, blueprint.sourceBytes).thenCompose { store.savePlan(plan) }
                        .thenCompose { store.save(next) }.whenCompleteSync(tasks) { saved, saveFailure ->
                            if (saveFailure != null) {
                                val rejected = CivsDiagnostics.rejection(saveFailure)
                                if (rejected != null) finishReservation(cityId, player.uniqueId, result, rejected)
                                else {
                                    // Unknown commit outcomes keep the site capture for canonical recovery.
                                    unhealthy += cityId; initialized = false
                                    log("commit building site $cityId", saveFailure)
                                    finishReservation(cityId, player.uniqueId, result, CivsRejection("storage-unavailable"))
                                }
                            } else publish(requireNotNull(saved)).whenCompleteSync(tasks) { _, publishFailure ->
                                if (publishFailure == null) {
                                    cancelPreview(player.uniqueId)
                                    CivsDiagnostics.event("site_committed", "city" to cityId, "building" to plan.building.id,
                                        "control" to saved.building(plan.building.id)?.control)
                                    busy -= cityId
                                    runCatching { installControl(cityId, plan.building.id) }.onSuccess { installed ->
                                        installed.whenCompleteSync(tasks) { _, controlFailure ->
                                            finishReservation(cityId, player.uniqueId, result, controlFailure)
                                        }
                                    }.onFailure { finishReservation(cityId, player.uniqueId, result, it) }
                                } else {
                                    unhealthy += cityId
                                    finishReservation(cityId, player.uniqueId, result, publishFailure)
                                }
                            }
                        }
                }
            }.onFailure { finishReservation(cityId, player.uniqueId, result, it) }
        }
        result
    }

    private fun requireFounderAfterCapture(player: Player) {
        rejectIf(city(player) != null || remoteCity(player) != null, "already-member")
    }

    override fun buildingMaterials(player: Player, buildingId: UUID): CompletableFuture<Map<String, Long>> = action("building_materials", player, "building" to buildingId) {
        val city = managed(player)
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        if (city.construction?.buildingId == buildingId) CompletableFuture.completedFuture(city.construction.materials.orEmpty())
        else if (building.phase != BuildingPhase.SITE) CompletableFuture.completedFuture(emptyMap())
        else store.loadPlan(buildingId).thenApplyAsync({ plan ->
            requireNotNull(plan) { "Missing site design $buildingId" }
            require(plan.cityId == city.id && plan.building.id == buildingId)
            CivsMaterials.required(plan)
        }, executor)
    }

    override fun supplyConstruction(player: Player, buildingId: UUID): CompletableFuture<CivsSupplyResult> =
        action("construction_supply", player, "building" to buildingId) { supplyConstruction(player, buildingId, false) }

    override fun grantConstructionMaterials(player: Player, buildingId: UUID): CompletableFuture<CivsSupplyResult> =
        action("construction_grant", player, "building" to buildingId) { supplyConstruction(player, buildingId, true) }

    private fun supplyConstruction(player: Player, buildingId: UUID, grant: Boolean): CompletableFuture<CivsSupplyResult> {
        rejectIf(grant && !player.hasPermission("arccivs.admin"), "not-authorized")
        val city = managed(player)
        rejectIf(city.id in busy, "city-busy")
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        near(player, city.worldId, building.entrance)
        rejectIf(city.inventory?.mode != CivStorageMode.PHYSICAL, "invalid-storage-mode")
        val order = city.construction?.takeIf { it.buildingId == buildingId }
        rejectIf(order == null && building.phase != BuildingPhase.SITE, "invalid-building")
        // Legacy orders paid their complete bill when reserved; their estimate must never mint another payment.
        if (order != null && order.payment == null) return CompletableFuture.completedFuture(CivsSupplyResult(0, 0, false))
        val required = if (order != null) CompletableFuture.completedFuture(order.materials.orEmpty())
        else store.loadPlan(buildingId).thenApplyAsync({ stored ->
            val plan = requireNotNull(stored) { "Missing site design $buildingId" }
            require(plan.cityId == city.id && plan.worldId == city.worldId &&
                plan.building.copy(phase = BuildingPhase.SITE, production = building.production) == building)
            CivsMaterials.required(plan)
        }, executor)
        busy += city.id
        val result = tracked<CivsSupplyResult>()
        required.whenCompleteSync(tasks) { materials, failure ->
            busy -= city.id
            if (failure != null) { result.completeExceptionally(failure); return@whenCompleteSync }
            runCatching {
                rejectIf(!player.isOnline || grant && !player.hasPermission("arccivs.admin"), "not-authorized")
                val current = managed(player)
                rejectIf(current.id != city.id || current.building(buildingId) != building || current.construction != city.construction, "invalid-building")
                near(player, current.worldId, building.entrance)
                var supplied: CivStorageSupplyPlan? = null
                inventoryMutation(current.id, { lockedCity ->
                    storage.prepareConstructionSupply(lockedCity, buildingId, requireNotNull(materials), if (grant) null else player)
                        .also { supplied = it }.plan
                }, sourcePlayer = if (grant) null else player) { before, inventory ->
                    if (requireNotNull(supplied).plan.changes.isEmpty()) before
                    else before.copy(inventory = inventory, revision = Math.addExact(before.revision, 1))
                }.whenCompleteSync(tasks) { _, applyFailure ->
                    if (applyFailure != null) result.completeExceptionally(applyFailure)
                    else {
                        val outcome = requireNotNull(supplied).result
                        CivsDiagnostics.event("construction_supplied", "city" to city.id, "building" to buildingId,
                            "player" to player.uniqueId, "grant" to grant, "moved" to outcome.moved,
                            "remaining" to outcome.remaining, "storage_full" to outcome.storageFull)
                        result.complete(outcome)
                    }
                }
            }.onFailure(result::completeExceptionally)
        }
        return result
    }

    override fun startBuilding(player: Player, buildingId: UUID): CompletableFuture<Unit> = action("construction_order", player, "building" to buildingId) {
        val city = managed(player)
        rejectIf(city.id in busy || city.construction != null, "construction-busy")
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        rejectIf(building.phase != BuildingPhase.SITE, "invalid-building")
        near(player, city.worldId, building.entrance)
        requireControlBarrel(city.worldId, building)
        requireAssignedBuilder(city, building.id)
        busy += city.id
        val result = tracked<Unit>()
        store.loadPlan(buildingId).thenCompose { plan ->
            requireNotNull(plan) { "Missing site design $buildingId" }
            require(plan.cityId == city.id && plan.worldId == city.worldId && plan.building.copy(phase = BuildingPhase.SITE, production = building.production) == building)
            blueprints.recover(plan).thenCombine(store.loadSchematic(buildingId)) { snapshot, bytes ->
                Triple(plan, snapshot, requireNotNull(bytes))
            }
        }.whenCompleteSync(tasks) { loaded, failure ->
            if (failure != null) return@whenCompleteSync finishReservation(city.id, player.uniqueId, result, failure)
            val (sitePlan, snapshot, bytes) = requireNotNull(loaded)
            CompletableFuture.supplyAsync({ CivsBlueprints.worldCells(snapshot, building.anchor) }, executor).whenCompleteSync(tasks) { cells, cellsFailure ->
                if (cellsFailure != null) return@whenCompleteSync finishReservation(city.id, player.uniqueId, result, cellsFailure)
                // SITE owners can remodel freely: reserve against fresh before-images, not the old preview.
                world.capture(city.id, city.owner, city.worldId, building.copy(phase = BuildingPhase.CONSTRUCTING), requireNotNull(cells), building)
                    .whenCompleteSync(tasks) { captured, captureFailure ->
                        if (captureFailure != null) return@whenCompleteSync finishReservation(city.id, player.uniqueId, result, captureFailure)
                        runCatching {
                            rejectIf(!player.isOnline, "not-authorized")
                            near(player, city.worldId, building.entrance)
                            requireControlBarrel(city.worldId, building)
                            startOrder(player, cities.getValue(city.id), requireNotNull(captured).copy(capturedSha256 = sitePlan.capturedSha256), bytes)
                                .whenCompleteSync(tasks) { _, orderFailure -> finishReservation(city.id, player.uniqueId, result, orderFailure) }
                        }.onFailure { finishReservation(city.id, player.uniqueId, result, it) }
                    }
            }
        }
        result
    }

    /** Starting an order reserves the immutable design; each worked row pays independently later. */
    private fun startOrder(player: Player, city: Settlement, plan: CivPlacementPlan, bytes: ByteArray): CompletableFuture<Settlement> {
        rejectIf(city.construction != null, "construction-busy")
        rejectIf(city.inventory == null, "materials-migration-pending")
        rejectIf(!city.canManage(player.uniqueId), "not-authorized")
        val building = plan.building
        requireAssignedBuilder(city, building.id)
        if (building.type == BuildingType.TOWN_HALL && building.level > city.level()) {
            rejectIf(cities.values.any { it.id != city.id && it.worldId == city.worldId &&
                areasOverlap(city.center, city.radius + 32, it.center, it.radius) }, "city-overlap")
        }
        rejectIf(city.pendingControls.orEmpty().any { it.buildingId == building.id }, "city-busy")
        val approach = prepareControl(city.id, city.worldId, city.owner, building).takeIf { !it.approach.isNullOrEmpty() }
        val result = tracked<Settlement>()
        CompletableFuture.supplyAsync({
            val materials = CivsMaterials.required(plan)
            val batches = CivConstructionBatches(civsMaterialBatches(plan).size, 16)
            CivConstruction(plan.id, building.id, building.level, batches.total, 0,
                ConstructionPhase.CONSTRUCTING, Stock(), plannedBuilding = building, batches = batches, materials = materials,
                payment = CivConstructionPayment())
        }, executor).thenCompose { order ->
            store.saveSchematic(plan.id, bytes).thenCompose { store.savePlan(plan) }.thenApply { order }
        }.whenCompleteSync(tasks) { preparedOrder, failure ->
            if (failure != null) { busy -= city.id; result.completeExceptionally(failure) }
            else runCatching {
                val order = requireNotNull(preparedOrder)
                val materials = requireNotNull(order.materials)
                val batches = requireNotNull(order.batches)
                busy -= city.id
                mutate(city.id) { before ->
                    val reserved = CivsRules.reserveBuild(before, player.uniqueId, building, order)
                    if (approach == null) reserved else CivsRules.validate(reserved.copy(
                        pendingControls = reserved.pendingControls.orEmpty() + approach))
                }
                    .whenCompleteSync(tasks) { saved, reserveFailure ->
                    if (reserveFailure != null) result.completeExceptionally(reserveFailure)
                    else {
                        CivsDiagnostics.event("construction_reserved", "city" to city.id, "order" to order.id,
                            "building" to building.id, "batches" to batches.total, "materials" to materials, "payment" to "per_batch")
                        if (approach == null) result.complete(saved)
                        else runCatching { installControl(city.id, building.id) }.onSuccess { installed ->
                            installed.whenCompleteSync(tasks) { applied, installFailure ->
                                if (installFailure == null) result.complete(applied) else result.completeExceptionally(installFailure)
                            }
                        }.onFailure(result::completeExceptionally)
                    }
                }
            }.onFailure { result.completeExceptionally(it) }
        }
        return result
    }

    private fun requireAssignedBuilder(city: Settlement, buildingId: UUID) {
        rejectIf(city.workers.none { worker -> worker.assignment?.let(city::building)?.let { assigned ->
            assigned.type == BuildingType.BUILDER_HUT && (assigned.phase == BuildingPhase.ACTIVE || assigned.id == buildingId) ||
                assigned.type == BuildingType.TOWN_HALL && CivsRules.legacyHallBuilder(city)
        } == true }, "builder-required")
    }

    private fun finishReservation(cityId: UUID, playerId: UUID, future: CompletableFuture<Unit>, failure: Throwable?) {
        busy -= cityId
        pendingFounders.remove(playerId)
        if (failure == null) future.complete(Unit) else future.completeExceptionally(failure)
        updateHealth()
    }

    override fun recruit(player: Player): CompletableFuture<Unit> = action("recruit", player) {
        val city = managed(player)
        if (city.inventory == null) mutate(city.id) { CivsRules.recruit(it, player.uniqueId, UUID.randomUUID(), workerName(it.workers.size + 1)) }.thenApply { Unit }
        else inventoryMutation(city.id, { storage.prepareDebit(it, mapOf("BREAD" to CivsRules.RECRUIT_FOOD)) }) { before, inventory ->
            CivsRules.recruitFunded(before, player.uniqueId, UUID.randomUUID(), workerName(before.workers.size + 1)).copy(inventory = inventory)
        }.thenApply { Unit }
    }
    override fun assign(player: Player, workerId: UUID, buildingId: UUID?): CompletableFuture<Unit> = action("assign", player, "worker" to workerId, "building" to buildingId) {
        mutate(managed(player).id) { CivsRules.assign(it, player.uniqueId, workerId, buildingId, now()) }.thenApply { Unit }
    }
    override fun addMember(player: Player, name: String): CompletableFuture<Unit> = action("add_member", player, "member_name" to name) {
        val city = managed(player)
        val member = plugin.server.getPlayerExact(name.trim()) ?: throw CivsRejection("player-offline")
        rejectIf(cities.values.any { it.id != city.id && it.canManage(member.uniqueId) } || member.uniqueId in pendingFounders, "already-member")
        mutate(city.id) { CivsRules.addMember(it, player.uniqueId, member.uniqueId) }.thenApply { Unit }
    }
    override fun removeMember(player: Player, memberId: UUID): CompletableFuture<Unit> = action("remove_member", player, "member" to memberId) {
        mutate(managed(player).id) { CivsRules.removeMember(it, player.uniqueId, memberId) }.thenApply { Unit }
    }
    override fun retryConstruction(player: Player, cityId: UUID): CompletableFuture<Unit> = action("retry_construction", player, "city" to cityId) {
        requireReady(player)
        val city = cities[cityId] ?: throw CivsRejection("no-city")
        rejectIf(!city.canManage(player.uniqueId) && !player.hasPermission("arccivs.admin"), "not-authorized")
        val order = city.construction ?: throw CivsRejection("no-construction")
        mutate(cityId) { CivsRules.retryConstruction(it, it.owner, order.id) }.thenApply { Unit }
    }
    override fun reload(player: Player): CompletableFuture<Unit> = action("reload", player) {
        rejectIf(!player.hasPermission("arccivs.admin"), "not-authorized")
        rejectIf(busy.isNotEmpty() || placing.isNotEmpty() || previewLoads.isNotEmpty(), "city-busy")
        loadCities()
    }

    private fun deliver(cityId: UUID, workerId: UUID, assignment: UUID, cycle: Long, nextAt: Long, constructionId: UUID?, at: Long): CompletableFuture<Settlement> = action("deliver", null, "city" to cityId, "worker" to workerId, "cycle" to cycle) {
        rejectIf(!ready(), "not-ready")
        val city = cities[cityId] ?: throw CivsRejection("no-city")
        val building = city.building(assignment) ?: throw CivsRejection("building-not-found")
        if (CivsRules.isBuilder(building)) {
            rejectIf(city.construction?.id != constructionId, "stale-delivery")
            mutate(cityId) { CivsRules.deliver(it, it.owner, workerId, assignment, cycle, nextAt, at) }
        } else if (city.inventory == null) mutate(cityId) { CivsRules.deliver(it, it.owner, workerId, assignment, cycle, nextAt, at) }
        else {
            // Validate the observed trip before reserving space; repeated callbacks cannot mint again.
            CivsRules.deliver(city, city.owner, workerId, assignment, cycle, nextAt, at, outputHandled = true)
            inventoryMutation(cityId, { storage.prepareCredit(it, CivsProduction.material(building)
                ?: throw CivsRejection("invalid-production"), CivsProduction.amount(building), building.id) }) { before, inventory ->
                CivsRules.deliver(before, before.owner, workerId, assignment, cycle, nextAt, at, outputHandled = true).copy(inventory = inventory)
            }
        }
    }

    private fun inventoryMutation(
        cityId: UUID, prepare: (Settlement) -> CivStoragePlan?,
        sourcePlayer: Player? = null,
        transition: (Settlement, CivInventory) -> Settlement,
    ): CompletableFuture<Settlement> {
        checkMain()
        rejectIf(!ready(), "not-ready")
        rejectIf(cityId in busy || cityId in unhealthy, "city-busy")
        val city = cities[cityId] ?: throw CivsRejection("no-city")
        val id = UUID.randomUUID()
        storage.lock(city, id, sourcePlayer)
        val tx = try {
            val prepared = prepare(city) ?: run {
                storage.unlock(id)
                return CompletableFuture.completedFuture(city)
            }
            val after = CivsRules.validate(transition(city, prepared.inventoryAfter))
            if (after == city && prepared.changes.isEmpty()) {
                storage.unlock(id)
                return CompletableFuture.completedFuture(city)
            }
            require(after.revision == city.revision + 1)
            CivInventoryTransaction(id, city.id, city.revision, after, prepared.changes, prepared.playerDebit)
        } catch (failure: Exception) { storage.unlock(id); throw failure }
        busy += cityId
        val result = tracked<Settlement>()
        store.saveInventoryTransaction(tx).whenCompleteSync(tasks) { _, failure ->
            if (failure != null) {
                val rejection = CivsDiagnostics.rejection(failure)
                if (rejection?.reason == "storage-transaction-too-large") {
                    // The store rejects oversized bytes before writing a journal; no native mutation has started.
                    storage.unlock(id)
                    busy -= cityId
                    result.completeExceptionally(rejection)
                    updateHealth()
                    return@whenCompleteSync
                }
                inventoryRecovery[cityId] = tx
                unhealthy += cityId
                blockedRecovery += cityId
                busy -= cityId
                log("journal inventory ${tx.id}", failure)
                result.completeExceptionally(CivsRejection("inventory-recovery"))
            } else {
                inventoryRecovery[cityId] = tx
                applyInventory(tx, result)
            }
        }
        return result
    }

    /** One immutable receipt covers native slots and the exact next city revision. */
    private fun applyInventory(tx: CivInventoryTransaction, result: CompletableFuture<Settlement>?) {
        if (closed) { result?.completeExceptionally(CivsRejection("not-ready")); return }
        fun fail(error: Throwable, retry: Boolean = false) {
            busy -= tx.cityId
            unhealthy += tx.cityId
            if (!retry) { blockedRecovery += tx.cityId; log("reconcile inventory ${tx.id}", error) }
            result?.completeExceptionally(error)
            updateHealth()
        }
        runCatching {
            when (val applied = storage.reconcile(tx)) {
                is CivInventoryApplyResult.Retry -> return fail(CivsRejection("inventory-recovery", applied.values), retry = true)
                is CivInventoryApplyResult.Conflict -> return fail(CivsRejection("inventory-conflict", applied.values))
                CivInventoryApplyResult.Applied -> Unit
            }
            CivsDiagnostics.event("inventory_applied", "city" to tx.cityId, "transaction" to tx.id,
                "revision" to tx.after.revision, "slots" to tx.changes.size)
            store.save(tx.after).whenCompleteSync(tasks) { saved, failure ->
                if (failure != null) return@whenCompleteSync fail(CivsRejection("inventory-recovery").also { it.initCause(failure) })
                publish(requireNotNull(saved)).whenCompleteSync(tasks) { _, publishFailure ->
                    if (publishFailure != null) return@whenCompleteSync fail(publishFailure)
                    finishInventory(tx, result)
                }
            }
        }.onFailure { fail(it) }
    }

    private fun finishInventory(tx: CivInventoryTransaction, result: CompletableFuture<Settlement>?) {
        store.deleteInventoryTransaction(tx.id).whenCompleteSync(tasks) { _, failure ->
            busy -= tx.cityId
            if (failure != null) {
                unhealthy += tx.cityId
                blockedRecovery += tx.cityId
                log("retire inventory journal ${tx.id}", failure)
                result?.completeExceptionally(CivsRejection("inventory-recovery"))
            } else {
                inventoryRecovery.remove(tx.cityId)
                unhealthy -= tx.cityId
                blockedRecovery -= tx.cityId
                storage.unlock(tx.id)
                CivsDiagnostics.event("inventory_committed", "city" to tx.cityId, "transaction" to tx.id, "revision" to tx.after.revision)
                result?.complete(cities.getValue(tx.cityId))
            }
            updateHealth()
        }
    }

    private fun recoverInventories() {
        val tx = inventoryRecovery.values.firstOrNull { it.cityId !in busy && it.cityId !in blockedRecovery &&
            (cities[it.cityId]?.revision != it.beforeRevision || !storage.waitingForPlayer(it)) } ?: return
        val city = cities[tx.cityId] ?: run { blockedRecovery += tx.cityId; return }
        busy += city.id
        runCatching {
            storage.lock(tx)
            if (city.revision == tx.beforeRevision) applyInventory(tx, null)
            else if (city.revision >= tx.after.revision && (city.revision != tx.after.revision || city == tx.after)) finishInventory(tx, null)
            else throw CivsRejection("inventory-conflict")
        }.onFailure {
            busy -= city.id; blockedRecovery += city.id; unhealthy += city.id
            log("recover inventory ${tx.id}", it)
        }
    }

    override fun availableMaterials(player: Player): Map<String, Long> = storage.available(managed(player))

    override fun setStorageMode(player: Player, mode: CivStorageMode): CompletableFuture<Unit> = action("storage_mode", player, "mode" to mode) {
        val city = managed(player)
        if (mode == CivStorageMode.PHYSICAL) materialize(city.id).thenApply { Unit }
        else mutate(city.id) { current ->
            val inventory = current.inventory ?: throw CivsRejection("materials-migration-pending")
            if (inventory.mode == mode) current else CivsRules.validate(current.copy(inventory = inventory.copy(mode = mode), revision = current.revision + 1))
        }.thenApply { Unit }
    }

    override fun materializeStorage(player: Player): CompletableFuture<Unit> = action("storage_materialize", player) {
        val city = managed(player)
        rejectIf(city.inventory?.mode != CivStorageMode.PHYSICAL, "invalid-storage-mode")
        materialize(city.id).thenApply { Unit }
    }

    private fun materialize(cityId: UUID): CompletableFuture<Settlement> {
        val before = cities[cityId] ?: throw CivsRejection("no-city")
        return inventoryMutation(cityId, { storage.prepareMaterialization(it) }) { current, inventory ->
            if (inventory == current.inventory) current else current.copy(inventory = inventory, revision = current.revision + 1)
        }.whenComplete { saved, failure ->
            if (failure == null && saved != before) CivsDiagnostics.event("storage_materialized", "city" to cityId,
                "revision" to saved.revision, "moved_items" to pendingMaterials(before) - pendingMaterials(saved),
                "pending_items" to pendingMaterials(saved))
        }
    }

    private fun pendingMaterials(city: Settlement): Long = city.inventory?.let { inventory ->
        inventory.materials.values.sum() + inventory.buildingMaterials.orEmpty().values.sumOf { it.values.sum() }
    } ?: 0L

    private fun inventoryBuilding(player: Player, city: Settlement, buildingId: UUID): CivBuilding {
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        val control = building.control ?: throw CivsRejection("control-missing")
        rejectIf(player.world.uid != city.worldId || player.location.toVector().distanceSquared(
            org.bukkit.util.Vector(control.x + .5, control.y + .5, control.z + .5)) > 64.0, "too-far")
        return building
    }

    override fun openBuildingInventory(player: Player, buildingId: UUID): CompletableFuture<Unit> = action("inventory_open", player, "building" to buildingId) {
        val city = managed(player)
        rejectIf(city.id in busy, "city-busy")
        inventoryBuilding(player, city, buildingId)
        val prepared = if (city.inventory?.mode == CivStorageMode.PHYSICAL && pendingMaterials(city) > 0)
            materialize(city.id) else CompletableFuture.completedFuture(city)
        prepared.thenApply {
            val current = managed(player)
            rejectIf(current.id != city.id, "no-city")
            storage.openControl(player, current, inventoryBuilding(player, current, buildingId))
        }
    }

    override fun buildingControl(player: Player, buildingId: UUID): CompletableFuture<CivPosition> = action("control_locate", player, "building" to buildingId) {
        val city = managed(player)
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        val result = tracked<CivPosition>()
        blueprints.prepare(city.style, building.type, building.level, building.id, city.worldId,
            building.anchor, building.rotation / 90, building.rotation, building.entrance)
            .whenCompleteSync(tasks) { blueprint, failure ->
                if (failure != null) result.completeExceptionally(failure)
                else runCatching {
                    rejectIf(managed(player).let { it.id != city.id || it.building(buildingId) != building }, "control-location-changed")
                    val prepared = requireNotNull(blueprint).building
                    rejectIf(prepared.anchor != building.anchor || prepared.bounds != building.bounds ||
                        prepared.entrance != building.entrance || prepared.rotation != building.rotation, "control-location-changed")
                    CivsDiagnostics.event("control_located", "city" to city.id, "building" to buildingId,
                        "previous" to building.control, "control" to prepared.control, "anchor" to prepared.anchor,
                        "entrance" to prepared.entrance, "rotation" to prepared.rotation)
                    requireNotNull(prepared.control)
                }.onSuccess(result::complete).onFailure(result::completeExceptionally)
            }
        result
    }

    /** Install the authored marker once; the previous native container and its contents stay untouched. */
    override fun relocateControl(player: Player, buildingId: UUID, expectedTarget: CivPosition): CompletableFuture<Unit> =
        action("control_relocate", player, "building" to buildingId, "expected_control" to expectedTarget) {
            val before = managed(player)
            val building = before.building(buildingId) ?: throw CivsRejection("building-not-found")
            rejectIf(before.id in busy || before.id in placing || before.id in inventoryRecovery, "city-busy")
            rejectIf(before.construction != null, "construction-busy")
            rejectIf(building.phase != BuildingPhase.ACTIVE, "control-relocation-site")
            val result = tracked<Unit>()
            buildingControl(player, buildingId).whenCompleteSync(tasks) { target, failure ->
                if (failure != null) result.completeExceptionally(failure)
                else runCatching {
                    val point = requireNotNull(target)
                    rejectIf(point != expectedTarget, "control-location-changed")
                    val current = managed(player)
                    rejectIf(current.id != before.id || current.building(buildingId) != building, "control-location-changed")
                    rejectIf(current.construction != null || current.id in placing, "construction-busy")
                    rejectIf(current.id in inventoryRecovery, "inventory-recovery")
                    near(player, current.worldId, point)
                    if (point == building.control) requireControlBarrel(current.worldId, building)
                    val placement = prepareControl(current.id, current.worldId, current.owner, building.copy(control = point))
                    val nativeWorld = requireNotNull(plugin.server.getWorld(current.worldId))
                    rejectIf(!world.canModify(current.owner, nativeWorld.getBlockAt(point.x, point.y, point.z), Material.BARREL), "lands-denied")
                    rejectIf(civsProtectedBuildings(current).any { it.id != building.id &&
                        (it.control == point || it.bounds.contains(point.x, point.y, point.z)) }, "building-overlap")
                    val old = building.control
                    val retained = old?.takeIf { it != point }?.let {
                        rejectIf(!nativeWorld.isChunkLoaded(it.x shr 4, it.z shr 4), "chunk-unloaded")
                        val block = nativeWorld.getBlockAt(it.x, it.y, it.z)
                        rejectIf(!world.canModify(current.owner, block), "lands-denied")
                        it.takeIf { block.type == Material.BARREL }
                    }
                    mutate(current.id) { city ->
                        rejectIf(city.building(buildingId) != building || city.construction != null, "control-location-changed")
                        if (point == old) city else {
                            val inventory = city.inventory ?: throw CivsRejection("materials-migration-pending")
                            val containers = (inventory.containers - point).let { if (retained == null || retained in it) it else it + retained }
                            rejectIf(containers.size > CivInventory.MAX_CONTAINERS, "container-limit")
                            CivsRules.validate(city.copy(buildings = city.buildings.map {
                                if (it.id == buildingId) it.copy(control = point) else it
                            }, inventory = inventory.copy(containers = containers), revision = city.revision + 1,
                                pendingControls = city.pendingControls.orEmpty().filterNot { it.buildingId == buildingId } + placement))
                        }
                    }.whenCompleteSync(tasks) { _, commitFailure ->
                        if (commitFailure != null) result.completeExceptionally(commitFailure)
                        else {
                            controlFailures.remove(buildingId)
                            CivsDiagnostics.event("control_relocated", "city" to current.id, "building" to buildingId,
                                "previous" to old, "control" to point, "retained_container" to retained)
                            runCatching { installControl(current.id, buildingId) }.onSuccess { installed ->
                                installed.whenCompleteSync(tasks) { _, installFailure ->
                                    if (installFailure == null) result.complete(Unit) else result.completeExceptionally(installFailure)
                                }
                            }.onFailure(result::completeExceptionally)
                        }
                    }
                }.onFailure(result::completeExceptionally)
            }
            result
        }

    override fun depositMaterials(player: Player, buildingId: UUID): CompletableFuture<Unit> = action("inventory_deposit", player, "building" to buildingId) {
        val city = managed(player)
        val building = inventoryBuilding(player, city, buildingId)
        inventoryMutation(city.id, { storage.depositFromControl(it, building) }) { before, inventory ->
            before.copy(inventory = inventory, revision = before.revision + 1)
        }.thenApply { Unit }
    }

    override fun withdrawMaterial(player: Player, buildingId: UUID, material: String): CompletableFuture<Unit> = action("inventory_withdraw", player, "building" to buildingId, "material" to material) {
        val city = managed(player)
        val building = inventoryBuilding(player, city, buildingId)
        inventoryMutation(city.id, { storage.withdrawToControl(it, building, material) }) { before, inventory ->
            before.copy(inventory = inventory, revision = before.revision + 1)
        }.thenApply { Unit }
    }

    override fun registerContainer(player: Player): CompletableFuture<Unit> = action("container_register", player) {
        val city = managed(player)
        val block = player.rayTraceBlocks(6.0, FluidCollisionMode.NEVER)?.hitBlock ?: throw CivsRejection("look-at-container")
        val point = CivPosition(block.x, block.y, block.z)
        rejectIf(block.world.uid != city.worldId || !city.contains(point.x, point.z), "outside-city")
        rejectIf(block.type !in setOf(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL), "look-at-container")
        rejectIf(!world.canModify(city.owner, block), "lands-denied")
        rejectIf(civsProtectedBuildings(city).any { it.phase == BuildingPhase.CONSTRUCTING && it.bounds.contains(point.x, point.y, point.z) } ||
            city.construction?.plannedBuilding?.bounds?.contains(point.x, point.y, point.z) == true, "construction-busy")
        mutate(city.id) { before ->
            val inventory = before.inventory ?: throw CivsRejection("materials-migration-pending")
            if (point in inventory.containers || before.buildings.any { it.control == point }) before
            else {
                rejectIf(inventory.containers.size >= CivInventory.MAX_CONTAINERS, "container-limit")
                CivsRules.validate(before.copy(inventory = inventory.copy(containers = inventory.containers + point), revision = before.revision + 1))
            }
        }.thenApply { Unit }
    }

    override fun unregisterContainer(player: Player, position: CivPosition): CompletableFuture<Unit> = action("container_unregister", player, "position" to position) {
        mutate(managed(player).id) { city ->
            val inventory = city.inventory ?: throw CivsRejection("materials-migration-pending")
            if (position !in inventory.containers) city else CivsRules.validate(city.copy(
                inventory = inventory.copy(containers = inventory.containers - position), revision = city.revision + 1))
        }.thenApply { Unit }
    }

    override fun setProduction(player: Player, buildingId: UUID, material: String): CompletableFuture<Unit> = action("production_select", player, "building" to buildingId, "material" to material) {
        mutate(managed(player).id) { city ->
            val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
            rejectIf(material !in CivsProduction.options(building.type), "invalid-production")
            rejectIf(city.construction?.buildingId == buildingId, "construction-busy")
            if (building.production == material) city else CivsRules.validate(city.copy(buildings = city.buildings.map {
                if (it.id == buildingId) it.copy(production = material) else it
            }, revision = city.revision + 1))
        }.thenApply { Unit }
    }

    override fun deleteCity(player: Player, expectedCityId: UUID, confirmationName: String): CompletableFuture<Unit> =
        action("delete_city", player, "city" to expectedCityId) {
            val city = managed(player)
            CivsRules.requireDeletion(city, player.uniqueId, expectedCityId, confirmationName)
            rejectIf(city.id in busy || city.id in placing || city.id in inventoryRecovery, "city-busy")
            busy += city.id
            val result = tracked<Unit>()
            store.delete(city).whenCompleteSync(tasks) { _, failure ->
                if (failure != null) {
                    busy -= city.id
                    val rejection = CivsDiagnostics.rejection(failure)
                    if (rejection != null) result.completeExceptionally(rejection)
                    else {
                        unhealthy += city.id
                        log("delete settlement ${city.id}", failure)
                        result.completeExceptionally(CivsRejection("storage-unavailable"))
                    }
                    updateHealth()
                } else publish(city.id, null).whenCompleteSync(tasks) { _, publishFailure ->
                    busy -= city.id
                    if (publishFailure != null) {
                        unhealthy += city.id
                        log("publish settlement deletion ${city.id}", publishFailure)
                        result.completeExceptionally(CivsRejection("storage-unavailable"))
                    } else {
                        // Invalidate old drafts and membership callbacks before a new city can be founded.
                        (city.members + city.owner).forEach { member ->
                            lookups.remove(member)
                            remoteCities.remove(member)
                            cancelPreview(member, "city-deleted")
                            boundary.clear(member)
                        }
                        remoteCities.entries.removeIf { it.value.city.id == city.id }
                        city.buildings.forEach { controlFailures.remove(it.id) }
                        citizens.pulse(now())
                        CivsDiagnostics.event("city_deleted", "city" to city.id, "owner" to city.owner,
                            "revision" to city.revision, "buildings_preserved" to city.buildings.size,
                            "discarded_shared_items" to city.inventory?.materials.orEmpty().values.sum(),
                            "discarded_building_items" to city.inventory?.buildingMaterials.orEmpty().values.sumOf { it.values.sum() },
                            "discarded_legacy_stock" to city.stock,
                            "construction_cancelled" to city.construction?.id)
                        result.complete(Unit)
                    }
                    updateHealth()
                }
            }
            result
        }

    private fun mutate(cityId: UUID, transform: (Settlement) -> Settlement): CompletableFuture<Settlement> {
        checkMain()
        rejectIf(closed, "not-ready")
        rejectIf(cityId in unhealthy, "storage-unavailable")
        rejectIf(cityId in busy, "city-busy")
        val city = cities[cityId] ?: throw CivsRejection("no-city")
        val next = transform(city)
        if (next == city) return CompletableFuture.completedFuture(city)
        busy += cityId
        val result = tracked<Settlement>()
        store.save(next).whenCompleteSync(tasks) { saved, failure ->
            if (failure == null) {
                publish(requireNotNull(saved)).whenCompleteSync(tasks) { _, publishFailure ->
                    busy -= cityId
                    if (publishFailure == null) result.complete(saved)
                    else { unhealthy += cityId; result.completeExceptionally(publishFailure) }
                    updateHealth()
                }
            } else {
                busy -= cityId
                val rejected = CivsDiagnostics.rejection(failure)
                if (rejected != null) result.completeExceptionally(rejected)
                else {
                    unhealthy += cityId
                    log("save settlement $cityId", failure)
                    result.completeExceptionally(CivsRejection("storage-unavailable"))
                }
            }
            updateHealth()
        }
        return result
    }

    private fun loadCities(): CompletableFuture<Unit> {
        checkMain()
        rejectIf(loading || closed, "not-ready")
        loading = true
        loadFailure = null
        (previews.keys + selections.keys + requests.keys).toList().forEach { cancelPreview(it, "reload") }
        citizens.pulse(now()) // Provider is empty while loading: despawn previous runtime actors.
        val result = tracked<Unit>()
        loadInventoryReceipts().thenCompose { store.load() }.thenApplyAsync({ records ->
            val members = records.flatMap { it.members + it.owner }
            require(members.distinct().size == members.size) { "A player belongs to more than one settlement" }
            records.associateByTo(linkedMapOf(), Settlement::id) to CivsProtection.prepare(records)
        }, executor).whenCompleteSync(tasks) { loaded, failure ->
            if (closed) { result.completeExceptionally(CivsRejection("not-ready")); return@whenCompleteSync }
            loading = false
            if (failure != null) {
                initialized = false
                loadFailure = "storage-unavailable"
                log("load settlements", failure)
                result.completeExceptionally(failure)
            } else runCatching {
                val (records, prepared) = requireNotNull(loaded)
                storage.replaceCities(records.values)
                cities = records
                protection.publish(prepared)
                geometryGeneration++
                unhealthy.clear()
                unhealthy.addAll(inventoryRecovery.keys)
                initialized = true
                result.complete(Unit)
                pulseConstruction()
            }.onFailure { initialized = false; result.completeExceptionally(it); log("validate settlements", it) }
            updateHealth()
        }
        return result
    }

    private fun loadInventoryReceipts(): CompletableFuture<Unit> {
        storage.beginReceiptBootstrap()
        val result = tracked<Unit>()
        store.loadInventoryTransactions().whenCompleteSync(tasks) { transactions, failure ->
            if (closed) { result.completeExceptionally(CivsRejection("not-ready")); return@whenCompleteSync }
            runCatching {
                if (failure != null) throw failure
                val receipts = requireNotNull(transactions)
                require(receipts.map { it.cityId }.distinct().size == receipts.size) { "Multiple inventory receipts for one city" }
                inventoryRecovery.values.forEach { storage.unlock(it.id) }
                inventoryRecovery.clear()
                blockedRecovery.clear()
                inventoryRecovery.putAll(receipts.associateBy { it.cityId })
                storage.completeReceiptBootstrap(receipts)
                result.complete(Unit)
            }.onFailure {
                // Unknown local debits must never be mistaken for an empty journal, even if SQL is unavailable.
                plugin.logger.log(Level.SEVERE, "ArcCivs inventory journal bootstrap failed closed; player item changes remain blocked. Repair the local receipt error before restarting the server.", it)
                CivsDiagnostics.event("inventory_bootstrap", "state" to "failed", "cause" to it.javaClass.simpleName)
                result.completeExceptionally(it)
            }
        }
        return result
    }

    /** Publish geometry together with its prepared protection index; concurrent cities retry a stale candidate. */
    private fun publish(city: Settlement): CompletableFuture<Unit> = publish(city.id, city)

    private fun publish(cityId: UUID, city: Settlement?): CompletableFuture<Unit> {
        if (closed) return CompletableFuture.failedFuture(CivsRejection("not-ready"))
        val previous = cities[cityId]
        if (city != null && previous?.buildings == city.buildings && previous?.construction?.plannedBuilding == city.construction?.plannedBuilding && previous.members == city.members &&
            previous.inventory?.containers == city.inventory?.containers && previous.pendingControls == city.pendingControls) {
            cities[city.id] = city
            return CompletableFuture.completedFuture(Unit)
        }
        val expected = geometryGeneration
        val snapshot = cities.values.filter { it.id != cityId } + listOfNotNull(city)
        val result = tracked<Unit>()
        CompletableFuture.supplyAsync({ CivsProtection.prepare(snapshot) }, executor).whenCompleteSync(tasks) { prepared, failure ->
            if (closed) result.completeExceptionally(CivsRejection("not-ready"))
            else if (failure != null) result.completeExceptionally(failure)
            else if (geometryGeneration != expected) publish(cityId, city).whenComplete { _, retryFailure ->
                if (retryFailure == null) result.complete(Unit) else result.completeExceptionally(retryFailure)
            } else runCatching {
                if (city == null) storage.removeCity(cityId) else storage.updateCity(city)
                if (city == null) cities.remove(cityId) else cities[cityId] = city
                protection.publish(requireNotNull(prepared))
                geometryGeneration++
            }.onSuccess { result.complete(Unit) }.onFailure(result::completeExceptionally)
        }
        return result
    }

    private fun pulse() {
        if (closed) return
        selections.filterValues { it.expires <= now() }.keys.toList().forEach { cancelSelection(it, "expired") }
        previews.filterValues { it.expires <= now() }.keys.toList().forEach { cancelPreview(it, "expired") }
        citizens.pulse(now())
        plugin.server.onlinePlayers.forEach { player -> boundary.pulse(player, if (ready()) city(player) else null) }
        if (ready()) {
            recoverInventories()
            pulseImmigration()
            pulseAutoAssignments()
            pulseControls()
            pulseStorage()
        }
        pulseConstruction()
        updateHealth()
    }

    private val controlFailures = mutableSetOf<UUID>()
    private var controlCursor = 0
    private var nextStorageTransferAt = 0L
    private var storageCursor = 0

    private fun pulseStorage() {
        if (now() < nextStorageTransferAt) return
        val candidates = cities.values.filter { city ->
            city.id !in busy && city.id !in unhealthy && city.inventory?.mode == CivStorageMode.PHYSICAL && pendingMaterials(city) > 0 &&
                !storage.hasViewers(city) &&
                plugin.server.onlinePlayers.any { it.world.uid == city.worldId && city.contains(it.location.blockX, it.location.blockZ) }
        }
        if (candidates.isEmpty()) return
        val city = candidates[Math.floorMod(storageCursor++, candidates.size)]
        nextStorageTransferAt = now() + 5_000L
        runCatching { materialize(city.id) }.onFailure { log("materialize stored items ${city.id}", it) }
    }

    /** Missing controls after their one-time grant require repair, never a repeating free grant. */
    private fun requireControlBarrel(worldId: UUID, building: CivBuilding) {
        val point = building.control ?: throw CivsRejection("control-missing")
        val target = plugin.server.getWorld(worldId) ?: throw CivsRejection("world-unavailable")
        rejectIf(point.y !in target.minHeight until target.maxHeight, "outside-world")
        rejectIf(!target.isChunkLoaded(point.x shr 4, point.z shr 4), "chunk-unloaded")
        if (target.getBlockAt(point.x, point.y, point.z).type != Material.BARREL) throw CivsRejection("control-position-missing",
            mapOf("x" to point.x.toString(), "y" to point.y.toString(), "z" to point.z.toString()))
    }

    private fun prepareControl(cityId: UUID, worldId: UUID, owner: UUID, building: CivBuilding): CivControlPlacement {
        val point = building.control ?: throw CivsRejection("control-missing")
        val target = plugin.server.getWorld(worldId) ?: throw CivsRejection("world-unavailable")
        rejectIf(point.y !in target.minHeight until target.maxHeight, "outside-world")
        rejectIf(!target.isChunkLoaded(point.x shr 4, point.z shr 4), "chunk-unloaded")
        val block = target.getBlockAt(point.x, point.y, point.z)
        rejectIf(block.type != Material.BARREL && (block.type == Material.BEDROCK || block.state is org.bukkit.block.TileState), "control-obstructed")
        rejectIf(!world.canModify(owner, block, Material.BARREL), "lands-denied")
        val approach = (-1..1).mapNotNull { dy ->
            val position = building.entrance.copy(y = Math.addExact(building.entrance.y, dy))
            // Old non-curated layouts may use an approach inside their paste; never rewrite captured design cells.
            if (position == point || building.bounds.contains(position.x, position.y, position.z)) return@mapNotNull null
            rejectIf(position.y !in target.minHeight until target.maxHeight, "outside-world")
            rejectIf(!target.isChunkLoaded(position.x shr 4, position.z shr 4), "chunk-unloaded")
            val cell = target.getBlockAt(position.x, position.y, position.z)
            if (if (dy == -1) cell.type.isSolid else cell.isPassable && !cell.isLiquid) return@mapNotNull null
            val after = if (dy == -1) Material.DIRT else Material.AIR
            checkApproachCell(cityId, owner, building, cell, after)
            CivPlanBlock(position, cell.blockData.asString, after.createBlockData().asString)
        }.takeIf { it.isNotEmpty() }
        if (approach != null) CivsDiagnostics.event("approach_prepared", "city" to cityId, "building" to building.id,
            "world" to worldId, "count" to approach.size, "positions" to approach.map { it.position })
        return CivControlPlacement(building.id, point, block.blockData.asString, approach)
    }

    private fun checkApproachCell(cityId: UUID, owner: UUID, building: CivBuilding, block: org.bukkit.block.Block, after: Material) {
        val point = CivPosition(block.x, block.y, block.z)
        val values = mapOf("block" to block.type.name, "x" to block.x.toString(), "y" to block.y.toString(),
            "z" to block.z.toString(), "world" to block.world.uid.toString(), "world_name" to block.world.name)
        fun reject(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason, values) }
        reject(!block.world.worldBorder.isInside(org.bukkit.Location(block.world, point.x + .5, point.y + .5, point.z + .5)), "outside-border")
        val city = cities[cityId]
        reject(city != null && (city.owner != owner || city.worldId != block.world.uid), "not-authorized")
        reject(city != null && !city.contains(point.x, point.z), "outside-city")
        reject(cities.values.any { it.id != cityId && it.worldId == block.world.uid && it.contains(point.x, point.z) }, "city-overlap")
        reject(cities.values.filter { it.worldId == block.world.uid }.any { other ->
            civsProtectedBuildings(other).any { (other.id != cityId || it.id != building.id) &&
                (it.bounds.contains(point.x, point.y, point.z) || it.control == point) } ||
                other.pendingControls.orEmpty().any { (other.id != cityId || it.buildingId != building.id) &&
                    it.approach.orEmpty().any { cell -> cell.position == point } }
        }, "building-overlap")
        reject(block.type == Material.BEDROCK || block.state is org.bukkit.block.TileState, "protected-terrain")
        reject(cities.values.any { it.worldId == block.world.uid && point in it.inventory?.containers.orEmpty() }, "registered-container")
        reject(!world.canModify(owner, block, after), "lands-denied")
    }

    private fun installControl(cityId: UUID, buildingId: UUID): CompletableFuture<Settlement> {
        rejectIf(!ready(), "not-ready")
        val city = cities[cityId] ?: throw CivsRejection("no-city")
        val receipt = city.pendingControls.orEmpty().firstOrNull { it.buildingId == buildingId }
            ?: return CompletableFuture.completedFuture(city)
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        rejectIf(cityId in busy || cityId in unhealthy, "city-busy")
        val point = receipt.position
        rejectIf(building.control != point, "control-location-changed")
        val target = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("world-unavailable")
        rejectIf(point.y !in target.minHeight until target.maxHeight, "outside-world")
        rejectIf(!target.isChunkLoaded(point.x shr 4, point.z shr 4), "chunk-unloaded")
        val block = target.getBlockAt(point.x, point.y, point.z)
        rejectIf(block.type != Material.BARREL && block.blockData.asString != receipt.before, "control-obstructed")
        rejectIf(block.type != Material.BARREL && (block.type == Material.BEDROCK || block.state is org.bukkit.block.TileState), "control-obstructed")
        rejectIf(!world.canModify(city.owner, block, Material.BARREL), "lands-denied")
        // Validate every before/after image before the first native write, including the barrel grant.
        val approach = receipt.approach.orEmpty().map { cell ->
            val position = cell.position
            rejectIf(position.y !in target.minHeight until target.maxHeight, "outside-world")
            rejectIf(!target.isChunkLoaded(position.x shr 4, position.z shr 4), "chunk-unloaded")
            val native = target.getBlockAt(position.x, position.y, position.z)
            val current = native.blockData.asString
            if (current != cell.before && current != cell.after) throw CivsRejection("world-changed", mapOf(
                "block" to native.type.name, "x" to position.x.toString(), "y" to position.y.toString(),
                "z" to position.z.toString(), "world" to city.worldId.toString(), "world_name" to target.name))
            val after = Bukkit.createBlockData(cell.after)
            checkApproachCell(city.id, city.owner, building, native, after.material)
            native to after
        }
        val reused = block.type == Material.BARREL
        if (!reused) block.setType(Material.BARREL, false)
        approach.forEach { (native, after) -> if (native.blockData.asString != after.asString) native.setBlockData(after, false) }
        if (approach.isNotEmpty()) CivsDiagnostics.event("approach_applied", "city" to city.id, "building" to building.id,
            "world" to city.worldId, "count" to approach.size, "positions" to receipt.approach!!.map { it.position })
        storage.refreshBuilding(city, building)
        CivsDiagnostics.event("control_installed", "city" to city.id, "building" to building.id, "world" to city.worldId,
            "block" to point, "reused" to reused)
        return mutate(cityId) { current ->
            rejectIf(receipt !in current.pendingControls.orEmpty(), "control-location-changed")
            CivsRules.validate(current.copy(pendingControls = current.pendingControls.orEmpty() - receipt, revision = current.revision + 1))
        }.whenComplete { _, failure ->
            if (failure == null) controlFailures -= buildingId
        }
    }

    private fun pulseControls() {
        val nearby = plugin.server.onlinePlayers.mapNotNull(::city).distinctBy { it.id }.filter { city ->
            plugin.server.onlinePlayers.any { it.world.uid == city.worldId && city.contains(it.location.blockX, it.location.blockZ) }
        }
        // Existing paid orders finish with their original receipt before conversion to actual item storage.
        nearby.firstOrNull { it.inventory == null && it.construction == null && it.id !in busy && it.id !in unhealthy }?.let { old ->
            runCatching { mutate(old.id) { city ->
                val buildings = city.buildings.map { it.copy(control = it.control ?: it.anchor) }
                val controls = buildings.filter { city.building(it.id)?.control == null }.map {
                    prepareControl(city.id, city.worldId, city.owner, it)
                }
                CivsRules.validate(city.copy(stock = Stock(), inventory = CivInventory.starting(city.stock),
                    buildings = buildings, pendingControls = city.pendingControls.orEmpty() + controls, revision = city.revision + 1))
            } }.onFailure { log("migrate city materials ${old.id}", it) }
        }
        val controls = nearby.filter { it.id !in busy && it.id !in unhealthy }.flatMap { city -> city.pendingControls.orEmpty().map { city to it } }
        if (controls.isEmpty()) return
        repeat(minOf(16, controls.size)) {
            val (city, pending) = controls[Math.floorMod(controlCursor++, controls.size)]
            if (city.id in busy) return@repeat
            runCatching { installControl(city.id, pending.buildingId) }.onSuccess { future ->
                future.whenComplete { _, failure ->
                    if (failure != null && controlFailures.add(pending.buildingId)) log("install control ${pending.buildingId}", failure)
                }
            }.onFailure {
                if (controlFailures.add(pending.buildingId)) log("install control ${pending.buildingId}", it)
            }
        }
    }

    private fun pulseImmigration() {
        cities.values.firstOrNull { city ->
            city.id !in busy && city.id !in unhealthy && city.immigration?.let { it.remaining > 0 && it.nextArrivalAt <= now() } == true &&
                plugin.server.onlinePlayers.any { player -> player.world.uid == city.worldId &&
                    player.location.toVector().distanceSquared(org.bukkit.util.Vector(city.center.x.toDouble(), city.center.y.toDouble(), city.center.z.toDouble())) <= settings.viewerRange * settings.viewerRange }
        }?.let { city ->
            runCatching { mutate(city.id) { CivsRules.immigrate(it, it.owner, UUID.randomUUID(), workerName(it.workers.size + 1), now()) } }
                .onSuccess { future -> future.whenComplete { saved, failure ->
                    if (failure == null) CivsDiagnostics.event("resident_arrived", "city" to city.id, "population" to saved.workers.size,
                        "remaining" to saved.immigration?.remaining)
                } }.onFailure { log("admit resident ${city.id}", it) }
        }
    }

    private fun pulseAutoAssignments() {
        val city = cities.values.firstOrNull { city ->
            city.id !in busy && city.id !in unhealthy && CivsRules.needsAutoAssignment(city) &&
                plugin.server.onlinePlayers.any { player -> player.world.uid == city.worldId && city.contains(player.location.blockX, player.location.blockZ) }
        } ?: return
        runCatching { mutate(city.id) { CivsRules.autoAssign(it, it.owner, now()) } }.onSuccess { future ->
            future.whenCompleteSync(tasks) { saved, failure ->
                if (failure != null) log("auto assign residents ${city.id}", failure)
                else CivsDiagnostics.event("workers_auto_assigned", "city" to city.id,
                    "assigned" to requireNotNull(saved).workers.count { worker -> city.workers.firstOrNull { it.id == worker.id }?.assignment != worker.assignment })
            }
        }.onFailure { log("auto assign residents ${city.id}", it) }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = false)
    fun controlClicked(event: org.bukkit.event.player.PlayerInteractEvent) {
        if (event.action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return
        val player = event.player
        if (!ready() || selection(player) != null || preview(player) != null) return
        val block = event.clickedBlock ?: return
        val point = CivPosition(block.x, block.y, block.z)
        val city = city(player)?.takeIf { it.worldId == block.world.uid } ?: return
        val building = city.buildings.firstOrNull { it.control == point } ?: return
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY)
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY)
        if (!player.hasPermission("arccivs.use") || !::ui.isInitialized) return
        tasks.runSync { if (player.isOnline && ready()) ui.openBuilding(player, city.id, building.id) }
    }

    private var nextMaterialCheckAt = 0L
    private var materialCursor = 0

    private fun pulseConstruction() {
        if (!ready()) return
        if (now() >= nextMaterialCheckAt) {
            nextMaterialCheckAt = now() + 5_000L
            val waiting = cities.values.filter { city ->
                city.construction?.phase == ConstructionPhase.WAITING_MATERIALS && city.id !in busy && city.id !in unhealthy &&
                    city.id !in placing && city.pendingControls.orEmpty().none { it.buildingId == city.construction.buildingId } &&
                    plugin.server.getWorld(city.worldId)?.let { target ->
                        city.construction.plannedBuilding?.entrance?.let { target.isChunkLoaded(it.x shr 4, it.z shr 4) }
                    } == true
            }
            if (waiting.isNotEmpty()) {
                val city = waiting[Math.floorMod(materialCursor++, waiting.size)]
                val order = requireNotNull(city.construction)
                if (!storage.hasViewers(city)) runCatching { payBatch(city, order, requireNotNull(order.payment?.awaiting)) }
                    .onSuccess { future -> future.whenCompleteSync(tasks) { _, failure ->
                        if (failure != null && CivsDiagnostics.rejection(failure)?.reason != "materials-missing")
                            placementFailed(city.id, order.id, failure)
                    } }.onFailure {
                        if (CivsDiagnostics.rejection(it)?.reason != "materials-missing") placementFailed(city.id, order.id, it)
                    }
            }
        }
        for (city in cities.values.toList()) {
            if (placing.size >= 2) break
            val order = city.construction ?: continue
            if (city.id in busy || city.id in unhealthy || city.id in placing) continue
            if (city.pendingControls.orEmpty().any { it.buildingId == order.buildingId }) continue
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
            blueprints.recover(plan).thenApplyAsync({ snapshot ->
                require(plan.blocks.size in 1..CivsStore.MAX_PLAN_BLOCKS) { "Invalid construction plan size" }
                val palette = mutableMapOf<String, Material>()
                val materials = plan.blocks.associate { cell ->
                    val name = cell.after.substringBefore('[')
                    cell.position to palette.getOrPut(name) {
                        Material.matchMaterial(name)?.takeIf { it.isBlock } ?: throw CivsRejection("invalid-plan")
                    }
                }
                val batch = order.batches?.let { config ->
                    val batches = civsConstructionBatches(plan, order)
                    require(batches.size == config.total && order.workDone in batches.indices)
                    batches[order.workDone]
                }
                val cells = if (batch == null) plan.blocks else plan.blocks.filter {
                    it.position.x in batch.minimum.x..batch.maximum.x && it.position.y == batch.minimum.y &&
                        it.position.z in batch.minimum.z..batch.maximum.z
                }
                val containerTypes = setOf("minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel")
                val containersChanged = cells.any { it.before.substringBefore('[') in containerTypes || it.after.substringBefore('[') in containerTypes }
                RecoveredPlacement(plan, snapshot, PlacementAccess(plan, materials.filterKeys { p ->
                    batch == null || p.x in batch.minimum.x..batch.maximum.x && p.y == batch.minimum.y && p.z in batch.minimum.z..batch.maximum.z
                }, containersChanged), batch, plan.copy(blocks = cells))
            }, executor)
        }.whenCompleteSync(tasks) { recovered, failure ->
            if (closed) return@whenCompleteSync
            if (failure != null) return@whenCompleteSync placementFailed(city.id, order.id, failure)
            val (plan, snapshot, access, batch, batchPlan) = requireNotNull(recovered)
            val validation = if (batchPlan.blocks.isEmpty()) CompletableFuture.completedFuture(Unit) else world.revalidate(batchPlan, city.owner)
            validation.whenCompleteSync(tasks) { _, validationFailure ->
                if (closed) return@whenCompleteSync
                if (validationFailure != null) return@whenCompleteSync placementFailed(city.id, order.id, validationFailure)
                runCatching {
                    val required = if (order.payment != null && order.payment.reservedBatch == null)
                        CivsMaterials.required(batchPlan) else null
                    val begin = if (required != null) payBatch(city, order, required)
                        else mutate(city.id) { CivsRules.beginPlacement(it, it.owner, order.id) }
                    begin
                        .whenCompleteSync(tasks) { paid, beginFailure ->
                            if (closed) return@whenCompleteSync
                            if (beginFailure != null) return@whenCompleteSync placementFailed(city.id, order.id, beginFailure)
                            if (required != null && paid?.construction?.payment?.reservedBatch != order.workDone) {
                                releasePlacement(city.id)
                                runCatching {
                                    mutate(city.id) { CivsRules.awaitMaterials(it, it.owner, order.id, order.workDone, required) }
                                        .whenComplete { _, waitFailure ->
                                            if (waitFailure != null) log("wait for construction materials ${order.id}", waitFailure)
                                            else CivsDiagnostics.event("construction_waiting_materials", "city" to city.id,
                                                "order" to order.id, "batch" to order.workDone, "materials" to required)
                                        }
                                }.onFailure { placementFailed(city.id, order.id, it) }
                                return@whenCompleteSync
                            }
                            blueprints.pasteParts(snapshot, plan.building, batch).whenCompleteSync(tasks) { parts, cropFailure ->
                                if (cropFailure != null) placementFailed(city.id, order.id, cropFailure)
                                else {
                                    CivsDiagnostics.event("construction_parts_prepared", "city" to city.id, "order" to order.id,
                                        "batch" to order.workDone, "parts" to requireNotNull(parts).size,
                                        "control" to plan.building.control, "captured_sha256" to plan.capturedSha256)
                                    pasteCaptured(city.id, order, plan, parts, snapshot, access)
                                }
                            }
                        }
                }.onFailure { placementFailed(city.id, order.id, it) }
            }
        }
    }

    private fun payBatch(city: Settlement, order: CivConstruction, required: Map<String, Long>): CompletableFuture<Settlement> =
        inventoryMutation(city.id, { storage.prepareDebitBackground(it, required) }) { before, inventory ->
            CivsRules.payBatch(before, before.owner, order.id, order.workDone, required).copy(inventory = inventory)
        }.whenComplete { saved, failure ->
            if (failure == null && saved?.construction?.payment?.reservedBatch == order.workDone)
                CivsDiagnostics.event("construction_batch_paid", "city" to city.id, "order" to order.id,
                "batch" to order.workDone, "materials" to required, "revision" to saved.revision)
        }

    private fun pasteCaptured(cityId: UUID, order: CivConstruction, plan: CivPlacementPlan, parts: List<ArcSchematicSnapshot>,
        fullSnapshot: ArcSchematicSnapshot, access: PlacementAccess, partIndex: Int = 0) {
        val orderId = order.id
        runCatching {
            rejectIf(!ready() || cityId !in placing || cityId in unhealthy, "not-ready")
            require(parts.isNotEmpty() && partIndex in parts.indices) { "Empty construction tile" }
            placementAccess[cityId] = access
            CivsDiagnostics.event("construction_part_started", "city" to cityId, "order" to orderId,
                "batch" to order.workDone, "part" to partIndex + 1, "parts" to parts.size)
            val operation = blueprints.paste(parts[partIndex], plan.worldId, plan.building.anchor)
            operations[cityId] = operation
            operation.completion.whenCompleteSync(tasks) { mutation, failure ->
                if (placementAccess[cityId] !== access) return@whenCompleteSync
                operations.remove(cityId)
                CivsDiagnostics.event("construction_part_finished", "city" to cityId, "order" to orderId,
                    "batch" to order.workDone, "part" to partIndex + 1, "changed" to mutation?.changed,
                    "skipped" to mutation?.skipped, "cancelled" to mutation?.cancelled,
                    "reason" to CivsDiagnostics.rejection(failure)?.reason)
                if (failure != null || mutation?.cancelled != false) {
                    placementFailed(cityId, orderId, failure ?: CivsRejection("placement-cancelled"))
                } else if (partIndex + 1 < parts.size) {
                    pasteCaptured(cityId, order, plan, parts, fullSnapshot, access, partIndex + 1)
                } else runCatching {
                    mutate(cityId) { current ->
                        rejectIf(current.construction?.id != orderId || current.construction.workDone != order.workDone, "stale-construction")
                        if (order.batches == null) CivsRules.completeConstruction(current, current.owner, orderId)
                        else CivsRules.completeBatch(current, current.owner, orderId)
                    }.whenCompleteSync(tasks) { saved, commitFailure ->
                        if (commitFailure != null) placementFailed(cityId, orderId, commitFailure)
                        else {
                            CivsDiagnostics.event("construction_batch_committed", "city" to cityId, "order" to orderId,
                                "completed" to (order.workDone + 1), "total" to order.workRequired)
                            if (access.containersChanged) saved?.let { storage.refreshBuilding(it, plan.building) }
                            if (saved?.construction == null) repairCommitted(cityId, plan, fullSnapshot)
                            else releasePlacement(cityId)
                        }
                    }
                }.onFailure { placementFailed(cityId, orderId, it) }
            }
        }.onFailure { placementFailed(cityId, orderId, it) }
    }

    private fun repairCommitted(cityId: UUID, plan: CivPlacementPlan, snapshot: ArcSchematicSnapshot) {
        // Shape repair is cosmetic and happens only after the durable build commit.
        val access = placementAccess[cityId] ?: return
        access.repairing = true
        runCatching {
            CompletableFuture.supplyAsync({ CivsBlueprints.worldCells(snapshot, plan.building.anchor).filterNot {
                it.blockData.substringBefore('[') == "minecraft:structure_void" &&
                    CivPosition(it.position.x, it.position.y, it.position.z) == plan.building.control
            } }, executor)
                .whenCompleteSync(tasks) { cells, failure ->
                    if (placementAccess[cityId] !== access) return@whenCompleteSync
                    if (failure != null) { releasePlacement(cityId); log("prepare shape repair $cityId", failure); return@whenCompleteSync }
                    if (!ready()) { releasePlacement(cityId); return@whenCompleteSync }
                    runCatching {
                        val repair = blueprints.repair(plan.worldId, requireNotNull(cells))
                        operations[cityId] = repair
                        repair.completion.whenCompleteSync(tasks) { _, repairFailure ->
                            if (placementAccess[cityId] !== access) return@whenCompleteSync
                            releasePlacement(cityId)
                            if (repairFailure != null) log("repair shapes $cityId", repairFailure)
                            else store.deletePlan(plan.id).thenCompose { store.deleteSchematic(plan.id) }
                                .whenComplete { _, cleanupFailure -> if (cleanupFailure != null) log("clean captured plan ${plan.id}", cleanupFailure) }
                            updateHealth()
                        }
                    }.onFailure { releasePlacement(cityId); log("repair shapes $cityId", it) }
                }
        }.onFailure { releasePlacement(cityId); log("prepare shape repair $cityId", it) }
    }

    private fun releasePlacement(cityId: UUID) {
        operations.remove(cityId)
        placementAccess.remove(cityId)
        placing -= cityId
    }

    private fun placementFailed(cityId: UUID, orderId: UUID, failure: Throwable) {
        releasePlacement(cityId)
        log("place construction $orderId", failure)
        if (closed || cityId in unhealthy || cities[cityId]?.construction?.id != orderId) return
        val causes = generateSequence(failure) { it.cause }.toList()
        val reason = causes.filterIsInstance<CivsRejection>().firstOrNull()?.reason
            ?: if (causes.filterIsInstance<ArcSchematicException>().any { it.reason == ArcSchematicFailure.PROTECTED_AREA }) "protected-area" else "placement-failed"
        runCatching { mutate(cityId) { CivsRules.pauseConstruction(it, it.owner, orderId, reason) } }
            .onFailure { log("pause construction $orderId", it) }
    }

    private fun currentPreview(player: Player): Preview {
        requireReady(player)
        rejectIf(player.uniqueId in previewLoads, "city-busy")
        return previews[player.uniqueId]?.takeIf { it.expires > now() && it.blueprint.worldId == player.world.uid }
            ?: throw CivsRejection("preview-expired")
    }
    private fun managed(player: Player): Settlement {
        requireReady(player)
        val city = city(player) ?: throw remoteCity(player)?.let {
            CivsRejection("remote-city", mapOf("server" to it.server.value))
        } ?: CivsRejection("no-city")
        rejectIf(city.id in unhealthy, "storage-unavailable")
        return city
    }
    private fun requireReady(player: Player) {
        checkMain()
        rejectIf(!ready(), "not-ready")
        rejectIf(!player.hasPermission("arccivs.use"), "not-authorized")
    }
    private fun requirePreviewSlot(player: Player) {
        rejectIf(player.uniqueId in previewLoads, "city-busy")
        val occupied = previews.keys + selections.keys + previewLoads
        rejectIf(occupied.size >= settings.maximumPreviews && player.uniqueId !in occupied, "too-many-previews")
    }
    private fun requirePlacementWorld(player: Player) {
        rejectIf(settings.worlds.isEmpty(), "founding-disabled")
        if (player.world.name !in settings.worlds) throw CivsRejection("wrong-world", mapOf(
            "world" to player.world.name, "worlds" to settings.worlds.sorted().joinToString(),
        ))
    }
    /** Only the legacy command uses the current aim; world interactions pass their actual clicked block. */
    private fun target(player: Player): CivPosition {
        requirePlacementWorld(player)
        val hit = player.rayTraceBlocks(12.0, FluidCollisionMode.NEVER)?.hitBlock ?: throw CivsRejection("look-at-ground")
        return clickedPlacement(player, CivsGroundTarget(hit.world.uid, CivPosition(hit.x, hit.y, hit.z)))
    }
    private fun clickedPlacement(player: Player, target: CivsGroundTarget): CivPosition {
        requirePlacementWorld(player)
        rejectIf(target.worldId != player.world.uid, "selection-expired")
        val point = target.block
        val world = player.world
        rejectIf(point.y < world.minHeight || point.y.toLong() + 2 >= world.maxHeight, "outside-world")
        rejectIf(!world.isChunkLoaded(point.x shr 4, point.z shr 4), "chunk-unloaded")
        val eye = player.eyeLocation
        rejectIf(eye.toVector().distanceSquared(org.bukkit.util.Vector(point.x + 0.5, point.y + 0.5, point.z + 0.5)) > 12.0 * 12.0, "too-far")
        val block = world.getBlockAt(point.x, point.y, point.z)
        return civsGroundSupport(block) ?: throw CivsRejection("look-at-ground")
    }
    private fun near(player: Player, worldId: UUID, point: CivPosition) {
        rejectIf(player.world.uid != worldId || player.location.toVector().distanceSquared(org.bukkit.util.Vector(point.x.toDouble(), point.y.toDouble(), point.z.toDouble())) > 96.0 * 96.0, "too-far")
    }
    private fun facing(player: Player): Int = Math.floorMod(kotlin.math.floor((player.location.yaw + 45.0) / 90.0).toInt(), 4)
    private fun checkSettlementSpace(worldId: UUID, center: CivPosition, ignoreFounder: UUID? = null) {
        rejectIf(cities.size + pendingFounders.keys.count { it != ignoreFounder } >= settings.maximumCities, "settlement-limit")
        rejectIf(cities.values.any { it.worldId == worldId && areasOverlap(center, settings.radius + CivsRules.MAX_TERRITORY_GROWTH, it.center, it.radius + (3 - it.level()) * 32) } ||
            pendingFounders.any { (owner, place) -> owner != ignoreFounder && place.first == worldId && areasOverlap(center, settings.radius + CivsRules.MAX_TERRITORY_GROWTH, place.second, settings.radius + CivsRules.MAX_TERRITORY_GROWTH) }, "city-overlap")
    }
    private fun updateHealth() {
        health = RuntimeHealthContribution(
            state = when { closed || !initialized && !loading -> RuntimeHealthState.DOWN; loading -> RuntimeHealthState.STARTING; unhealthy.isNotEmpty() -> RuntimeHealthState.DEGRADED; else -> RuntimeHealthState.UP },
            recoveryBacklog = cities.values.count { it.construction != null } + unhealthy.count { it !in cities },
            activeLeases = previews.size + selections.size + placing.size,
            schemas = mapOf("city" to 1, "plan" to 1),
            dependencies = mapOf("storage" to (initialized && unhealthy.isEmpty())),
        )
    }
    private fun <T> tracked(): CompletableFuture<T> = CompletableFuture<T>().also { future ->
        completions += future
        future.whenComplete { _, _ -> if (Bukkit.isPrimaryThread()) completions -= future else tasks.runSync { completions -= future } }
    }
    private fun <T> action(name: String, player: Player? = null, vararg fields: Pair<String, Any?>,
        block: () -> CompletableFuture<T>): CompletableFuture<T> {
        checkMain()
        val id = UUID.randomUUID()
        val started = System.nanoTime()
        val actor = player?.let(CivsDiagnostics::actor) ?: emptyArray()
        CivsDiagnostics.event("operation_start", "operation" to name, "request" to id, *actor, *fields)
        val result = try { block() } catch (error: Exception) { CompletableFuture.failedFuture(error) }
        return result.whenComplete { _, failure ->
            val rejection = CivsDiagnostics.rejection(failure)
            if (failure != null && rejection == null) plugin.logger.log(Level.WARNING,
                "ArcCivs operation $name failed (request=$id)", failure)
            CivsDiagnostics.event("operation_end", "operation" to name, "request" to id, *actor, *fields,
                "outcome" to if (failure == null) "success" else if (rejection != null) "rejected" else "failed",
                "reason" to rejection?.reason, "detail" to rejection?.values,
                "cause" to CivsDiagnostics.cause(failure)?.javaClass?.simpleName,
                "duration_ms" to (System.nanoTime() - started) / 1_000_000)
        }
    }
    private fun rejectIf(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
    private fun now() = clock()
    private fun log(context: String, error: Throwable) = plugin.logger.log(Level.WARNING, "ArcCivs could not $context", error)
    private fun checkMain() = check(Bukkit.isPrimaryThread()) { "ArcCivs authority must run on Paper's main thread" }

    @EventHandler fun quit(event: PlayerQuitEvent) {
        cancelPreview(event.player.uniqueId, "logout")
        boundary.clear(event.player.uniqueId)
        remoteCities.remove(event.player.uniqueId)
        lookups.remove(event.player.uniqueId)
    }
    @EventHandler fun worldChanged(event: PlayerChangedWorldEvent) { cancelPreview(event.player.uniqueId, "world-changed"); boundary.clear(event.player.uniqueId) }

    override fun close() {
        if (closed) return
        closed = true
        initialized = false
        HandlerList.unregisterAll(this)
        placementAccess.clear()
        placing.clear()
        operations.values.toList().forEach { it.cancel() }
        operations.clear()
        citizenDrain = runCatching { citizens.closeAsync() }
            .getOrElse { CompletableFuture.failedFuture(it) }
        world.close()
        HandlerList.unregisterAll(protection)
        plugin.server.servicesManager.unregister(ArcBlockProtectionService::class.java, sharedProtection)
        renderer.close()
        boundary.close()
        storage.close()
        previews.clear()
        selections.keys.toList().forEach { cancelSelection(it, "shutdown") }
        remoteCities.clear()
        lookups.clear()
        requests.clear()
        previewLoads.clear()
        completions.toList().forEach { it.completeExceptionally(CivsRejection("not-ready")) }
        completions.clear()
        updateHealth()
    }

    fun drained(): CompletableFuture<Void> = CompletableFuture.allOf(renderer.drained(), boundary.drained(), citizenDrain)

    companion object {
        internal fun areasOverlap(a: CivPosition, ar: Int, b: CivPosition, br: Int): Boolean =
            kotlin.math.abs(a.x.toLong() - b.x) <= ar.toLong() + br && kotlin.math.abs(a.z.toLong() - b.z) <= ar.toLong() + br
        private fun workerName(number: Int): String = listOf("Алекс", "Мира", "Рен", "Тора", "Лис", "Ива", "Ник", "Элла", "Дан", "Лея", "Кай", "Ада")[(number - 1) % 12]
    }
}
