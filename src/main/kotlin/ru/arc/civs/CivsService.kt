package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
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
import ru.arc.paper.api.ArcBlockProtectionService
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
    private val offlinePlayerData: ru.arc.paper.player.PaperOfflinePlayerData? = null,
    supplyExecutor: Executor? = null,
    private val residentFoods: CivsResidentFoods = CivsResidentFoods.BREAD_ONLY,
) : CivsUiHost, Listener, AutoCloseable {
    private data class Preview(
        val id: UUID, val cityId: UUID?, val cityName: String, val style: String, val blueprint: CivBlueprint,
        val upgrading: Boolean, val expires: Long, val movementOrigin: CivPosition, val autonomous: Boolean = false,
    )
    private data class Selection(
        val id: UUID, val worldId: UUID, val cityId: UUID?, val name: String, val style: String,
        val type: BuildingType, val expires: Long, val autonomous: Boolean = false, val entryId: String? = null,
    )
    private var cities = linkedMapOf<UUID, Settlement>()
    private val remoteCities = mutableMapOf<UUID, CivsNetworkCity>()
    private val lookups = mutableMapOf<UUID, UUID>()
    private val busy = mutableSetOf<UUID>()
    private val cityAvailability = linkedMapOf<UUID, ArrayDeque<CompletableFuture<Unit>>>()
    private val pendingConfirmations = mutableMapOf<UUID, CompletableFuture<Unit>>()
    private val pendingBuildingOrders = mutableMapOf<Pair<UUID, UUID>, CompletableFuture<Unit>>()
    private val unhealthy = mutableSetOf<UUID>()
    private data class FoundingReservation(val cityId: UUID, val worldId: UUID, val center: CivPosition)
    private val pendingFounders = mutableMapOf<UUID, FoundingReservation>()
    private val previews = mutableMapOf<UUID, Preview>()
    private val selections = mutableMapOf<UUID, Selection>()
    private val requests = mutableMapOf<UUID, UUID>()
    private val previewLoads = mutableMapOf<UUID, Boolean>() // Whether the preparation has a visible placement preview.
    private val completions = mutableSetOf<CompletableFuture<*>>()
    private var testingSupplyCursor = 0
    private var researchCursor = 0
    private val supplyNoopDiagnostics = linkedMapOf<UUID, String>()
    private val lastInjury = mutableMapOf<UUID, CivDeathContext>()
    private val memberNamesPending = mutableSetOf<UUID>()
    private var recoveredMemberNames = emptyMap<UUID, String>()
    private var initialized = false
    private var closed = false
    private var citizenDrain = CompletableFuture.completedFuture<Void>(null)
    private var planBoardDrain = CompletableFuture.completedFuture<Void>(null)
    private var loading = false
    private var loadFailure: String? = null
    private var geometryGeneration = 0L
    private val world = worldAdapter ?: CivsWorld(plugin, tasks, navigationSettings = settings.navigation) { cities.values.toList() }
    private val roadMaintenance = CivsRoadMaintenance(world)
    private val layoutDebug = CivsLayoutDebug(plugin.dataFolder.toPath(), tasks, executor, { id ->
        store.loadPlan(id).whenComplete { _, failure ->
            if (failure != null) plugin.logger.log(Level.WARNING, "ArcCivs layout export could not read plan=$id", failure)
        }
    }, clock)
    private val autonomousPlacement = CivsAutonomousPlacement(blueprints, world, executor, ::loadAutonomousAccess,
        store::saveSchematic, store::deleteSchematic, plugin.server.unsafe.dataVersion)
    private val autonomousDevelopment = CivsAutonomousDevelopment(plugin, tasks, world, blueprints,
        autonomousPlacement, executor, { cities }, busy, unhealthy, { construction.isPlacing(it) }, ::ready, { closed },
        ::loadAutonomousPlans, ::settlementActive, { id, action -> afterCityAvailable(id, action) },
        { id, transform -> mutate(id, transform = transform) },
        { id, transform -> afterCityAvailable(id) { mutate(id, transform = transform) } },
        ::commitAutonomousDesign, { construction.pulse() }, clock)
    /** Detached columns from the existing saved terrain routes; never a second road journal. */
    private val forestryRoutes = mutableMapOf<UUID, Pair<UUID, Set<Pair<Int, Int>>>>()
    private val physics = CivsPhysics(plugin, tasks)
    private val controlCleanup = CivsControlCleanup(plugin) { owner, block -> world.canModify(owner, block) }
    private val boundary = boundaryRenderer ?: CivsBoundaryRenderer(plugin, tasks, nativeWork)
    private val storage = (storageAdapter ?: CivsStorage(plugin)).also {
        it.beginReceiptBootstrap()
        it.validateUpkeepNative = ::validateUpkeepNative
        it.validateMineNative = ::validateMineNative
    }
    private val renderer = previewRenderer ?: CivsPreviewRenderer(plugin, tasks, nativeWork)
    private val planBoardSnapshots = CivsPlanBoardSnapshots(tasks, executor, { id ->
        if (ready()) cities[id]?.takeUnless { id in unhealthy || it.pendingDeletion != null } else null
    }, ::loadPlanBoardPlans)
    private val planBoards = CivsPlanBoardRenderer(plugin, tasks, nativeWork, executor, planBoardSnapshots::snapshot)
    private val protection = CivsProtection(cities = { cities.values.toList() }, available = { ready() }, protectedWorlds = settings.worlds,
        worldLocked = storage::isWorldLocked, forestryPath = { worldId, x, z ->
            forestryRoutes.values.any { it.first == worldId && (x to z) in it.second }
        }, citizenCollision = { worldId, point -> citizens.growthBlocked(worldId, point) })
    private val sharedProtection = object : ArcBlockProtectionService {
        override fun isProtected(worldId: UUID, x: Int, y: Int, z: Int): Boolean =
            protection.isProtected(worldId, x, y, z)

        override fun permitsSchematicMutation(owner: String, worldId: UUID, x: Int, y: Int, z: Int): Boolean {
            if (!Bukkit.isPrimaryThread()) return false
            if (owner != "arccivs-construction") return !isProtected(worldId, x, y, z)
            return construction.permitsSchematicMutation(worldId, x, y, z)
        }
    }
    lateinit var ui: CivsUi
    internal var corpseLocation: (UUID, UUID, Long) -> org.bukkit.Location? = { _, _, _ -> null }
    private val footTraffic = CivsFootTraffic(plugin) { if (ready()) cities.values.filter { it.id !in unhealthy } else emptyList() }
    private val worksites = CivsWorksites(plugin, world, { city, id -> storage.buildingAvailable(city, id) },
        storage::isWorldLocked, { it in busy || it in unhealthy }, { forestryRoutes[it.id]?.second.orEmpty() }, settings = settings.worksites)
    private val chunkLoading = CivsChunkLoading(plugin, settings.maximumLoadedChunks,
        permissions = CivsChunkPermissions(plugin, clock), clock = clock).also { storage.collectedChunkLease = it::retainLoaded }
    private val testingPermissions = CivsChunkPermissions(plugin, clock, CIVS_TESTING_PERMISSION)
    private val citizens = (citizenRuntime ?: CivsCitizens(plugin, { if (ready()) cities.values.filter { it.id !in unhealthy && it.pendingDeletion == null } else emptyList() },
        ::deliver, { player, cityId, workerId ->
            if (cities[cityId]?.autonomy != null && player.hasPermission("arccivs.admin")) ui.openWorker(player, cityId, workerId)
            else if (city(player)?.id == cityId) ui.openWorker(player, cityId, workerId)
        }, settings.maximumNpcs, settings.viewerRange, hurt = ::hurtResident, footstep = footTraffic::record,
        surfaceSpeed = footTraffic::speed, workPlan = { city, worker -> roadMaintenance.plan(city, worker) ?: worksites.plan(city, worker) },
        workIdleReason = worksites::idleReason, courierPlan = ::courierPlan, pickupCargo = ::pickupCargo, dropoffCargo = ::dropoffCargo,
        doorBlocked = { worldId, point -> !ready() || storage.isWorldLocked(worldId, point) },
        productionReady = ::productionReady, workInputsReady = ::workInputsReady,
        backgroundActive = chunkLoading::active,
        navigationTasks = tasks, navigationWork = nativeWork, navigationSettings = settings.navigation,
        courierFailed = { cityId, workerId, cargo ->
            if (supply.plan(cityId, workerId)?.cargo == cargo) supply.release(cityId, workerId, failed = true)
        },
        workPositionFailed = { city, worker, work ->
            if (work.upkeep != null) { roadMaintenance.invalidate(worker.id); true }
            else worksites.rejectStanding(city, worker, work)
        },
        residentSupplySource = ::residentSupplySource, prepareSupplies = { c, w, b -> prepareResidentSupplies(c, w, b) },
        residentFoodSource = { city, worker -> citizensFeetForFood(worker.id)?.let { storage.residentFoodSource(city, worker, it) } },
        pickupResidentFood = ::pickupResidentFood,
        residentFoods = residentFoods,
        craftPlan = ::craftPlan, craftDelivery = ::deliverCraft,
        craftSupplies = { c, w, b, job -> prepareResidentSupplies(c, w, b, job) },
        constructionFocus = { cityId, orderId -> construction.focus(cityId, orderId) },
        constructionSite = { cityId, orderId -> construction.site(cityId, orderId) },
        collectionAvailable = ::collectionAvailable, collectionHome = ::collectedHome,
        pickupGround = ::pickupGround, depositCollected = ::depositCollected,
        immortalResidents = { id -> cities[id]?.let { it.settings?.immortalResidents == true && testingAllowed(it) } == true })).also(onCitizensCreated)
    @Volatile private var health = RuntimeHealthContribution(state = RuntimeHealthState.STARTING)
    private val supply = CivsSupplyDispatcher(tasks, nativeWork, ::captureSupply, ::validateSupply, supplyExecutor, clock)
    private val supplyObservations = mutableMapOf<UUID, Pair<Long, Long>>()

    private val construction: CivsConstructionRuntime = CivsConstructionRuntime(plugin, tasks, world,
        blueprints, store, executor, physics, storage, citizens, footTraffic, { cities }, busy, unhealthy,
        ::ready, { closed }, ::settlementActive, autonomousDevelopment::accessReady,
        { id, action -> afterCityAvailable(id, action) }, { id, transform -> mutate(id, transform = transform) },
        { city, building -> activateBuilding(city, building) }, ::payBatch, ::updateHealth, clock)

    private val inventoryJournal = CivsInventoryJournal(plugin, tasks, store, storage, { cities }, busy,
        unhealthy, { closed }, { city -> publish(city) }, { id, transform -> mutate(id, transform = transform) },
        ::track, ::validateNativeCollected, ::resurrectionRejection, ::requestResurrectionAdmission,
        ::inventoryRetired, ::updateHealth, clock)

    private val residentLife = CivsResidentLifeRuntime(plugin, tasks, citizens, chunkLoading, settings.viewerRange,
        { cities }, busy, unhealthy, ::settlementActive,
        { id, action -> afterCityAvailable(id, action) }, { id, action -> afterCityAvailable(id, action) },
        { id, transform -> mutate(id, transform = transform) }, clock, residentFoods)

    init {
        storage.residentFoods = residentFoods
        storage.foodCanModify = { city, position ->
            plugin.server.getWorld(city.worldId)?.takeIf { position.y in it.minHeight until it.maxHeight &&
                it.isChunkLoaded(position.x shr 4, position.z shr 4) }
                ?.getBlockAt(position.x, position.y, position.z)?.let { world.canModify(city.owner, it) } == true
        }
        worksites.mutationBlocked = citizens::growthBlocked
        worksites.residentFeet = citizens::residentFeet
        citizens.residentLifeEnabled = true
        citizens.finishResidentMeal = residentLife::finishMeal
        citizens.finishResidentRest = residentLife::finishRest
        citizens.finishResidentLesson = residentLife::finishLesson
        citizens.worksiteTerrainChanged = worksites::changed
        citizens.worksiteWorldUnloaded = worksites::forgetWorld
        citizens.worksiteChunkUnloaded = worksites::forgetChunk
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.pluginManager.registerEvents(protection, plugin)
        plugin.server.servicesManager.register(ArcBlockProtectionService::class.java, sharedProtection, plugin, ServicePriority.Normal)
        tasks.runTimer(1, 1) {
            chunkLoading.pulse(if (ready()) cities.values.filter { it.id !in unhealthy } else emptyList())
            testingPermissions.pulse(if (ready()) cities.values.filter { it.pendingDeletion == null }.mapNotNull { city ->
                plugin.server.getWorld(city.worldId)?.let { city.owner to it.name }
            } else emptyList())
            resumeCityActions()
            if (ready()) supply.pulse()
            if (ready()) citizens.activateNavigating()
        }
        tasks.runTimer(5, 5) {
            if (ready()) {
                pulseSupply()
                worksites.pulse(cities.values)
                citizens.pulseFieldWork(now())
            }
        }
        tasks.runTimer(20, 20) { pulse() }
    }

    fun start(): CompletableFuture<Unit> = loadCities()
    fun health(): RuntimeHealthContribution = health

    override fun canChangeSettlementSetting(player: Player, setting: CivSettlementSetting): Boolean =
        ready() && city(player)?.owner == player.uniqueId && player.hasPermission(CIVS_TESTING_PERMISSION) &&
            (setting != CivSettlementSetting.CHUNK_LOADING || player.hasPermission(CIVS_CHUNK_PERMISSION))

    override fun setSettlementSetting(player: Player, setting: CivSettlementSetting, enabled: Boolean): CompletableFuture<Unit> =
        cityAction("settlement_setting", player, "setting" to setting, "enabled" to enabled) { city ->
            mutate(city.id) { current ->
                rejectIf(!player.isOnline || current.owner != player.uniqueId || !canChangeSettlementSetting(player, setting), "not-authorized")
                changedSettlementSetting(current, setting, enabled)
            }.thenApply { saved ->
                CivsDiagnostics.event("settlement_setting_changed", "city" to saved.id, "owner" to saved.owner,
                    "player" to player.uniqueId, "setting" to setting, "enabled" to enabled, "revision" to saved.revision)
                Unit
            }
        }

    /** Console routing supplies administrator authority; the city's owner must still hold the feature permission. */
    internal fun configureSettlementSetting(cityId: UUID, setting: CivSettlementSetting, enabled: Boolean): CompletableFuture<Unit> =
        action("settlement_setting_console", null, "city" to cityId, "setting" to setting, "enabled" to enabled) {
            afterCityAvailable(cityId) {
                mutate(cityId) { city ->
                    rejectIf(enabled && !testingAllowed(city), "not-authorized")
                    changedSettlementSetting(city, setting, enabled)
                }.thenApply { saved ->
                    CivsDiagnostics.event("settlement_setting_changed", "city" to saved.id, "owner" to saved.owner,
                        "source" to "console", "setting" to setting, "enabled" to enabled, "revision" to saved.revision)
                    Unit
                }
            }
        }

    /** Explicit console maintenance only; never restores a chest again after a player removes it. */
    internal fun repairBuildingStorage(cityId: UUID, buildingId: UUID): CompletableFuture<Map<String, Int>> =
        action("building_storage_repair", null, "city" to cityId, "building" to buildingId) {
            afterCityAvailable(cityId) {
                val city = cities[cityId] ?: throw CivsRejection("no-city")
                val building = city.building(buildingId)?.takeIf { it.phase == BuildingPhase.ACTIVE }
                    ?: throw CivsRejection("invalid-building")
                val result = tracked<Map<String, Int>>()
                blueprints.buildingStorageRepair(building).whenCompleteSync(tasks) { cells, failure ->
                    if (failure != null) { result.completeExceptionally(failure); return@whenCompleteSync }
                    runCatching {
                        afterCityAvailable<Map<String, Int>>(cityId) {
                            rejectIf(cities[cityId]?.building(buildingId) != building, "stale-building")
                            busy += cityId
                            val repair = runCatching {
                                world.repairStorageRacks(requireNotNull(cities[cityId]), building, requireNotNull(cells))
                            }.onFailure { busy -= cityId }.getOrThrow()
                            repair.whenCompleteSync(tasks) { counts, repairFailure ->
                                busy -= cityId
                                val refreshFailure = runCatching {
                                    cities[cityId]?.let { storage.refreshBuilding(it, building) }
                                    supply.request(cityId)
                                }.exceptionOrNull()
                                if (repairFailure != null || refreshFailure != null)
                                    result.completeExceptionally(repairFailure ?: refreshFailure!!)
                                else {
                                    CivsDiagnostics.event("building_storage_repaired", "city" to cityId, "building" to buildingId,
                                        "counts" to counts)
                                    result.complete(counts)
                                }
                            }.toCompletableFuture()
                        }.exceptionally { result.completeExceptionally(it); null }
                    }.onFailure(result::completeExceptionally)
                }
                result
            }
        }

    private fun changedSettlementSetting(city: Settlement, setting: CivSettlementSetting, enabled: Boolean): Settlement {
        val before = city.settings ?: CivSettlementSettings()
        val after = before.with(setting, enabled)
        return if (before == after) city else CivsRules.validate(city.copy(settings = after, revision = Math.addExact(city.revision, 1)))
    }

    private fun testingAllowed(city: Settlement): Boolean = plugin.server.getWorld(city.worldId)?.let {
        testingPermissions.allowed(city.owner, it.name)
    } == true
    /** Main captures city identity; plans, serialization and atomic file IO remain on the bounded worker. */
    fun debugLayout(cityId: UUID, terrain: Boolean): CompletableFuture<Map<String, Any?>> {
        check(Bukkit.isPrimaryThread())
        if (closed || !ready()) return CompletableFuture.failedFuture(CivsRejection("not-ready"))
        val city = cities[cityId] ?: return CompletableFuture.failedFuture(CivsRejection("city-not-found"))
        val nativeWorld = plugin.server.getWorld(city.worldId)
        if (terrain && nativeWorld == null) return CompletableFuture.failedFuture(CivsRejection("terrain-world-not-loaded"))
        val hall = city.buildings.firstOrNull { it.type == BuildingType.TOWN_HALL }
            ?: return CompletableFuture.failedFuture(CivsRejection("layout-town-hall-missing"))
        val runtime = mapOf("captured_at_ms" to now(), "coverage" to "existing-bounded-owner-debug-samples-with-total-and-truncated",
            "citizens" to debugState("citizens", cityId, null), "worksites" to debugState("worksites", cityId, null),
            "supply" to debugState("supply", cityId, null))
        return layoutDebug.export(city, nativeWorld?.name, plugin.pluginMeta.version, runtime,
            if (terrain) ({ world.autonomousHeightField(city, hall, hall) }) else null) { cities[cityId] }
            .whenComplete { _, failure ->
                if (failure != null && CivsDiagnostics.rejection(failure) == null &&
                    CivsDiagnostics.cause(failure) !is java.util.concurrent.CancellationException)
                    CivsDiagnostics.record(Level.WARNING, cityId, "ArcCivs layout export failed city=$cityId terrain=$terrain", plugin.logger, failure)
            }
    }

    internal fun debugTerrainAt(cityId: UUID, workerId: UUID, origin: CivPosition): Map<String, Any?> {
        check(Bukkit.isPrimaryThread())
        val header = mapOf("source" to "fresh-native-command", "city" to cityId, "worker" to workerId,
            "focus_origin" to "operator-request", "capture_center" to origin)
        if (closed || !ready()) return header + ("error" to "not-ready")
        val city = cities[cityId] ?: return header + ("error" to "city-not-found")
        val worker = city.workers.firstOrNull { it.id == workerId } ?: return header + ("error" to "worker-not-found")
        return CivsTerrainDebug.capture(city, worker, citizens.residentFeet(workerId), worksites.plan(city, worker), now(), origin)
    }

    /** Cached snapshots by default; explicit terrain alone captures a bounded loaded native neighbourhood. */
    fun debugState(system: String, cityId: UUID?, workerId: UUID?): Map<String, Any?> {
        check(Bukkit.isPrimaryThread())
        val selected = if (cityId == null) cities.values.take(8) else listOfNotNull(cities[cityId])
        if (cityId != null && selected.isEmpty()) return mapOf("error" to "city-not-found")
        if (workerId != null && selected.none { city -> city.workers.any { it.id == workerId } })
            return mapOf("error" to "worker-not-found")
        val total = if (cityId == null) cities.size else selected.size
        val selectedWorkers = if (cityId == null) null else selected.flatMap { city ->
            city.workers.filter { workerId == null || it.id == workerId }
        }
        fun rows(transform: (Settlement) -> Map<String, Any?>): Map<String, Any?> = mapOf(
            "total" to total, "truncated" to (total > selected.size), "entries" to selected.map(transform))
        return when (system) {
            "research" -> rows { city -> mapOf("city" to city.id, "revision" to city.revision, "view" to CivsResearch.view(city)) }
            "industry" -> rows { city -> mapOf("city" to city.id, "revision" to city.revision, "view" to CivsIndustry.view(city)) }
            "autonomy" -> rows { city -> mapOf("city" to city.id, "enabled" to (city.autonomy != null),
                "state" to city.autonomy, "progress" to autonomyStatus(city.id), "stock" to city.stock) +
                autonomousDevelopment.debug(city.id) }
            "terrain" -> {
                if (cityId == null || workerId == null) return mapOf("error" to "terrain-requires-city-and-worker")
                val city = selected.single()
                val worker = city.workers.first { it.id == workerId }
                CivsTerrainDebug.capture(city, worker, citizens.residentFeet(workerId), worksites.plan(city, worker), now())
            }
            "citizens" -> citizens.debug(cityId, workerId)
            "worksites" -> worksites.debug(selectedWorkers?.mapTo(hashSetOf()) { it.id },
                selectedWorkers?.mapNotNullTo(hashSetOf()) { it.assignment })
            "supply" -> supply.debug(cityId, workerId)
            "storage" -> storage.debug(cityId)
            "chunks" -> chunkLoading.debug(cityId, if (cityId == null) null else selected.mapTo(hashSetOf()) { it.owner })
            "construction" -> rows(construction::debug)
            "resources" -> rows { city ->
                val workers = city.workers.filter { workerId == null || it.id == workerId }
                mapOf("city" to city.id, "revision" to city.revision, "mode" to city.inventory?.mode,
                    "stock" to city.stock, "total_workers" to workers.size, "truncated_workers" to (workers.size > 8),
                    "workers" to workers.take(8).map { worker -> mapOf("worker" to worker.id,
                        "assignment" to worker.assignment, "shipment" to worker.cargo?.id, "pending_stock" to worker.pendingStock,
                        "cargo" to worker.cargo?.let { mapOf("material" to it.material, "amount" to it.amount,
                            "source" to it.sourceBuildingId, "destination" to it.targetBuildingId, "source_worker" to it.sourceWorkerId,
                            "stock_resource" to it.stockResource) },
                        "items" to CivsEquipment.slots(worker).take(CivsEquipment.MAX_SLOTS).mapIndexedNotNull { slot, item ->
                            item?.let { mapOf("slot" to slot, "material" to it.material, "amount" to it.amount,
                                "custom_data" to (it.data != null)) }
                        }) })
            }
            "residents" -> rows { city ->
                val workers = city.workers.filter { workerId == null || it.id == workerId }
                mapOf("city" to city.id, "settings" to (city.settings ?: CivSettlementSettings()),
                    "testing_permissions" to testingPermissions.debug(setOf(city.owner)), "population_pending" to (residentLife.pending(city.id)),
                    "last_population_minute" to residentLife.nextMinuteAt(city.id), "immigration" to city.immigration,
                    "total_workers" to workers.size, "truncated_workers" to (workers.size > 8),
                    "workers" to workers.take(8).map { mapOf("worker" to it.id, "name" to it.name,
                        "assignment" to it.assignment, "cycles" to it.completedCycles, "health" to it.health,
                        "hunger" to it.hunger, "home" to it.homeId, "sex" to it.sex, "child_minutes" to it.childMinutes,
                        "skills" to it.skills, "injury" to lastInjury[it.id]) },
                    "families_total" to city.families.orEmpty().size, "families" to city.families.orEmpty().take(8),
                    "corpses_total" to city.corpses.orEmpty().size, "corpses" to city.corpses.orEmpty().take(8).map {
                        mapOf("worker" to it.worker.id, "position" to it.position, "died_at" to it.diedAt,
                            "expires_at" to it.expiresAt, "killer" to it.killerName, "killer_type" to it.killerType, "cause" to it.cause)
                    })
            }
            "overview" -> mapOf("ready" to ready(), "loading" to loading, "closed" to closed,
                "health" to mapOf("state" to health.state, "recovery_backlog" to health.recoveryBacklog,
                    "active_leases" to health.activeLeases, "dependencies" to health.dependencies),
                "load_failure" to loadFailure, "cities_total" to cities.size, "city_rows_truncated" to (total > selected.size),
                "active_npcs" to citizens.activeCount(), "pending_operations" to completions.size,
                "inventory_recovery_total" to inventoryJournal.pendingCount, "blocked_recovery_total" to inventoryJournal.blockedCount,
                "previews" to previews.size, "selections" to selections.size, "preview_loads" to previewLoads.size,
                "pending_founders" to pendingFounders.size, "pending_confirmations" to pendingConfirmations.size,
                "pending_building_orders" to pendingBuildingOrders.size, "geometry_generation" to geometryGeneration,
                "cities" to selected.map { city -> mapOf("city" to city.id, "name" to city.name, "owner" to city.owner,
                    "world" to city.worldId, "revision" to city.revision, "workers" to city.workers.size,
                    "buildings" to city.buildings.size, "busy" to (city.id in busy), "unhealthy" to (city.id in unhealthy),
                    "building_catalog_truncated" to (city.buildings.size > 32),
                    "building_catalog" to city.buildings.take(32).map { building -> mapOf(
                        "id" to building.id, "type" to building.type, "phase" to building.phase, "level" to building.level,
                        "assigned_adults" to city.workers.filter { it.assignment == building.id && !CivsResidents.isChild(it) }.map { it.id },
                        "serving_tavern" to CivsRules.staffedTavern(city, building)) },
                    "inventory_recovery" to inventoryJournal.pendingCount(city.id),
                    "deletion_pending" to (city.pendingDeletion != null),
                    "pending_controls" to city.pendingControls.orEmpty().take(8).map { mapOf("building" to it.buildingId, "position" to it.position) }) })
            else -> mapOf("error" to "unknown-system")
        }
    }
    override fun ready(): Boolean = initialized && !closed && !loading
    override fun unavailableReason(): String? = loadFailure ?: if (!initialized && unhealthy.isNotEmpty()) "storage-unavailable" else null
    override fun styles(): List<String> = blueprints.availableStyles(BuildingType.TOWN_HALL)
    override fun styles(type: BuildingType): List<String> = blueprints.availableStyles(type)
    override fun blueprintChoices(type: BuildingType, style: String): List<CivBlueprintChoice> = blueprints.choices(type, style)
    override fun maximumBuildingLevel(building: CivBuilding): Int = blueprints.maximumLevel(building)
    override fun settlements(): List<Settlement> = cities.values.toList()
    internal fun corpseSettlements(): List<Settlement> = if (ready()) cities.values.filter {
        it.id !in unhealthy && it.pendingDeletion == null
    } else emptyList()
    override fun city(player: Player): Settlement? = cities.values.firstOrNull { it.autonomy == null && it.canManage(player.uniqueId) }
    override fun remoteCity(player: Player): CivsNetworkCity? = remoteCities[player.uniqueId]
    override fun memberName(city: Settlement, memberId: UUID): String =
        plugin.server.getPlayer(memberId)?.name ?: city.memberNames?.get(memberId.toString())
        ?: recoveredMemberNames[memberId] ?: memberId.toString().take(8)
    /** UI admission is blocked only by recovery/deletion, never by an ordinary in-flight save. */
    override fun cityBusy(player: Player): Boolean = city(player)?.let { it.id in unhealthy || it.pendingDeletion != null } == true
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
    override fun workerStatus(workerId: UUID): String {
        val city = cities.values.firstOrNull { it.workers.any { worker -> worker.id == workerId } }
            ?: return "workers.status.unavailable"
        val worker = city.workers.first { it.id == workerId }
        if (city.pendingDeletion != null) return "workers.status.waiting"
        if (city.autonomy?.paused == true) return "workers.status.autonomy-paused"
        if (city.id in unhealthy && inventoryJournal.pending(city.id)) return "workers.status.inventory-recovery"
        if (city.id in unhealthy) return "workers.status.storage-unavailable"
        citizenPlan(city, worker)?.idleReason?.let {
            val reason = if (it == "construction-paused") constructionFailureStatus(city.construction?.failure) else it
            return "workers.status.$reason"
        }
        val status = citizens.status(workerId)
        return if (status in setOf("workers.status.unassigned", "workers.status.no-construction", "workers.status.building-unavailable",
                "workers.status.construction-paused", "workers.status.waiting-materials", "workers.status.placing")) "workers.status.waiting" else status
    }

    override fun buildingStatus(buildingId: UUID): String {
        val city = cities.values.firstOrNull { it.building(buildingId) != null } ?: return "building-status.paused"
        val building = requireNotNull(city.building(buildingId))
        val order = city.construction?.takeIf { it.buildingId == buildingId }
        val status = when {
            city.pendingDeletion != null -> "deleting"
            city.id in unhealthy && inventoryJournal.pending(city.id) -> "inventory-recovery"
            city.id in unhealthy -> "storage-unavailable"
            order?.phase == ConstructionPhase.WAITING_MATERIALS -> "waiting-materials"
            order?.phase == ConstructionPhase.PAUSED -> constructionFailureStatus(order.failure)
            order?.phase in setOf(ConstructionPhase.PLACING, ConstructionPhase.READY_TO_PLACE) -> "placing"
            buildingId in city.constructionQueue.orEmpty() -> construction.queueFailure(city.id, buildingId)?.let {
                constructionFailureStatus(it)
            } ?: "queued"
            building.phase == BuildingPhase.SITE -> "site"
            else -> {
                val workers = city.workers.filter { worker -> worker.assignment?.let(city::building)?.let { assigned ->
                    if (order != null) CivsRules.isBuilder(city, assigned) && CivsRules.builderCanWork(city, assigned)
                    else assigned.id == buildingId
                } == true }
                if (workers.isEmpty()) {
                    if (order != null) "builder-required" else if (CivsRules.slots(city, building) > 0) "no-workers" else "active"
                } else workers.map { workerStatus(it.id).removePrefix("workers.status.") }.let { states ->
                    val selected = states.firstOrNull { it in setOf("working", "moving-to-work", "moving-to-storage") } ?: states.first()
                    when (selected) {
                        "waiting", "delivering" -> "working"
                        "world-unloaded", "chunk-unloaded", "capacity", "closed" -> "sleeping"
                        "construction-paused", "building-unavailable", "unavailable" -> "paused"
                        else -> selected
                    }
                }
            }
        }
        return "building-status.$status"
    }

    private fun constructionFailureStatus(reason: String?): String = when (reason) {
        "builder-required", "storage-full", "storage-unavailable", "route-unreachable",
        "work-route-too-long", "lands-denied", "world-changed", "protected-terrain", "building-overlap",
        "outside-city", "protected-area", "placement-failed", "placement-cancelled" -> reason
        "control-position-missing", "control-missing", "control-obstructed" -> "control-missing"
        "world-unavailable", "chunk-unloaded", "chunks-unloaded" -> "sleeping"
        else -> "paused"
    }
    override fun activeNpcCount(): Int = citizens.activeCount()

    override fun preview(player: Player): CivsPreviewView? = previews[player.uniqueId]?.takeIf { !it.upgrading && it.expires > now() }?.let {
        val b = it.blueprint.building
        CivsPreviewView(b.type, b.level, it.style, it.cityName, b.anchor, b.rotation,
            b.bounds.max.x - b.bounds.min.x + 1, b.bounds.max.y - b.bounds.min.y + 1,
            b.bounds.max.z - b.bounds.min.z + 1, CivsRules.cost(b.type, b.level), it.blueprint.snapshot.nonBarrierCount,
            it.id, it.blueprint.worldId, b.entrance, b.bounds, !it.upgrading, player.uniqueId in previewLoads, it.blueprint.materials,
            b.control ?: b.anchor, it.autonomous)
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

    override fun beginAutonomousSelection(player: Player, name: String, style: String): CompletableFuture<Unit> =
        action("begin_autonomous_selection", player, "city_name" to name, "style" to style) {
            requireReady(player)
            requireFounder(player, autonomous = true)
            rejectIf(name.trim().isBlank() || name.trim().length > 48 || name.any { it.isISOControl() }, "invalid-name")
            beginSelection(player, null, name.trim(), style, BuildingType.TOWN_HALL, autonomous = true)
        }

    override fun setAutonomousPaused(player: Player, cityId: UUID, paused: Boolean): CompletableFuture<Unit> =
        action("autonomy_pause", player, "city" to cityId, "paused" to paused) {
            requireReady(player)
            rejectIf(!player.hasPermission("arccivs.admin"), "not-authorized")
            afterCityAvailable(cityId) {
                rejectIf(!player.isOnline || !player.hasPermission("arccivs.admin"), "not-authorized")
                mutate(cityId) { CivsAutonomy.setPaused(it, paused) }.thenApply {
                    autonomousDevelopment.paused(cityId)
                    CivsDiagnostics.event("autonomous_paused", "city" to cityId, "paused" to paused)
                    Unit
                }
            }
        }

    override fun nextAutonomyGoal(cityId: UUID): CivAutonomyGoal? =
        cities[cityId]?.let { CivsAutonomy.nextGoalAfterCurrent(it, blueprints::maximumLevel) }

    override fun autonomyStatus(cityId: UUID): CivsAutonomyView? {
        val city = cities[cityId]?.takeIf { it.autonomy != null } ?: return null
        if (city.autonomy?.paused == true) return CivsAutonomyView("paused")
        if (city.id in unhealthy) return CivsAutonomyView("blocked", reason = "storage-unavailable")
        city.construction?.let { order ->
            val target = order.plannedBuilding ?: city.building(order.buildingId)
            return CivsAutonomyView(if (order.phase == ConstructionPhase.PAUSED) "blocked" else "building",
                target?.type, order.targetLevel, order.failure)
        }
        val goal = CivsAutonomy.nextGoal(city, blueprints::maximumLevel) ?: return CivsAutonomyView("complete")
        if (city.constructionQueue.orEmpty().isNotEmpty()) {
            val failure = construction.queueFailure(cityId, city.constructionQueue?.firstOrNull())
            return CivsAutonomyView(if (failure == null) "queued" else "blocked", goal.type, goal.targetLevel, failure)
        }
        if (!goal.ready) return CivsAutonomyView("resources", goal.type, goal.targetLevel, missing = goal.missing)
        return autonomousDevelopment.progress(cityId)?.takeIf { it.target == goal.type && it.level == goal.targetLevel }
            ?: CivsAutonomyView("planning", goal.type, goal.targetLevel)
    }

    private fun researchCity(player: Player, cityId: UUID, mutation: Boolean): Settlement {
        requireReady(player)
        val city = cities[cityId] ?: throw CivsRejection("no-city")
        if (city.autonomy != null) {
            rejectIf(player.world.uid != city.worldId || mutation && !player.hasPermission("arccivs.admin"), "not-authorized")
        } else rejectIf(!city.canManage(player.uniqueId), "not-authorized")
        rejectIf(city.id in unhealthy, "storage-unavailable")
        rejectIf(city.pendingDeletion != null, "deletion-pending")
        return city
    }

    override fun research(player: Player, cityId: UUID): CivResearchView = CivsResearch.view(researchCity(player, cityId, false))
    override fun industry(player: Player, cityId: UUID): CivIndustryView = CivsIndustry.view(researchCity(player, cityId, false))

    override fun beginResearch(player: Player, cityId: UUID, research: CivResearchId): CompletableFuture<Unit> =
        action("research_begin", player, "city" to cityId, "research" to research) {
            researchCity(player, cityId, true)
            afterCityAvailable(cityId) {
                rejectIf(!player.isOnline, "player-offline")
                researchCity(player, cityId, true)
                mutate(cityId) { current -> CivsResearch.begin(current, research, UUID.randomUUID(), now()) }.thenApply { Unit }
            }
        }

    override fun setResearchPaused(player: Player, cityId: UUID, projectId: UUID, paused: Boolean): CompletableFuture<Unit> =
        action("research_pause", player, "city" to cityId, "project" to projectId, "paused" to paused) {
            researchCity(player, cityId, true)
            afterCityAvailable(cityId) {
                rejectIf(!player.isOnline, "player-offline")
                researchCity(player, cityId, true)
                mutate(cityId) { CivsResearch.setPaused(it, projectId, paused, now()) }.thenApply { Unit }
            }
        }

    override fun beginBuildingSelection(player: Player, type: BuildingType): CompletableFuture<Unit> =
        beginBuildingSelectionWithStyle(player, type, null)

    override fun beginBuildingSelection(player: Player, type: BuildingType, style: String): CompletableFuture<Unit> =
        beginBuildingSelectionWithStyle(player, type, style)

    override fun beginBlueprintSelection(player: Player, type: BuildingType, style: String, entryId: String): CompletableFuture<Unit> =
        action("begin_blueprint_selection", player, "type" to type, "style" to style, "entry" to entryId) {
            val city = managed(player)
            rejectIf(type == BuildingType.TOWN_HALL || type.isInfrastructure, "invalid-building")
            rejectIf(blueprintChoices(type, style).none { it.id == entryId }, "blueprint-unavailable")
            CivsResearch.required(type, 1)?.let { rejectIf(!CivsResearch.has(city, it), "research-required") }
            rejectIf(player.world.uid != city.worldId, "outside-city")
            beginSelection(player, city.id, city.name, style, type, entryId = entryId)
        }

    private fun beginBuildingSelectionWithStyle(player: Player, type: BuildingType, style: String?): CompletableFuture<Unit> = action("begin_building_selection", player, "type" to type, "style" to style) {
        val city = managed(player)
        rejectIf(type == BuildingType.TOWN_HALL || type.isInfrastructure, "invalid-building")
        CivsResearch.required(type, 1)?.let { rejectIf(!CivsResearch.has(city, it), "research-required") }
        rejectIf(player.world.uid != city.worldId, "outside-city")
        beginSelection(player, city.id, city.name, style ?: city.style, type)
    }

    override fun beginPlanBoardSelection(player: Player, cityId: UUID): CompletableFuture<Unit> =
        action("begin_plan_board_selection", player, "city" to cityId, "type" to BuildingType.PLAN_BOARD) {
            val city = managedPlacement(player, cityId, BuildingType.PLAN_BOARD)
            beginSelection(player, city.id, city.name, city.style, BuildingType.PLAN_BOARD, autonomous = city.autonomy != null)
        }

    private fun beginSelection(player: Player, cityId: UUID?, name: String, style: String, type: BuildingType, autonomous: Boolean = false,
        entryId: String? = null): CompletableFuture<Unit> {
        requirePlacementWorld(player)
        rejectIf(type != BuildingType.PLAN_BOARD && style !in styles(type), "invalid-style")
        requirePreviewSlot(player)
        if (player.uniqueId in previews || player.uniqueId in selections || player.uniqueId in requests)
            cancelPreview(player.uniqueId, "replaced")
        val selection = Selection(UUID.randomUUID(), player.world.uid, cityId, name, style, type,
            now() + settings.previewSeconds * 1_000L, autonomous, entryId)
        selections[player.uniqueId] = selection
        refreshBoundary(player)
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
                requireFounder(player, selection.autonomous)
                checkSettlementSpace(player.world.uid, point)
            } else {
                val city = managedPlacement(player, selection.cityId, selection.type)
                rejectIf(!city.contains(point.x, point.z), "outside-city")
            }
            CivsDiagnostics.event("selection_selected", *CivsDiagnostics.actor(player), "selection" to selection.id,
                "block" to target.block, "placement" to point, "type" to selection.type)
            prepare(player, selection.cityId, selection.name, selection.style, selection.type, 1, UUID.randomUUID(),
                point, false, selectionId = selection.id, autonomous = selection.autonomous, entryId = selection.entryId)
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

    private fun requireFounder(player: Player, autonomous: Boolean = false) {
        rejectIf(autonomous && !player.hasPermission("arccivs.admin"), "not-authorized")
        rejectIf(!autonomous && (city(player) != null || remoteCity(player) != null) || player.uniqueId in pendingFounders, "already-member")
    }

    override fun upgradeBuilding(player: Player, buildingId: UUID): CompletableFuture<Unit> = action("upgrade_building", player, "building" to buildingId) {
        val city = managed(player)
        val key = city.id to buildingId
        pendingBuildingOrders[key]?.takeUnless { it.isDone }?.let { return@action it }
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        rejectIf(city.construction?.buildingId == buildingId, "construction-busy")
        rejectIf(building.phase != BuildingPhase.ACTIVE, "invalid-building")
        rejectIf(building.level >= maximumBuildingLevel(building), "invalid-level")
        CivsResearch.required(building.type, building.level + 1)?.let { rejectIf(!CivsResearch.has(city, it), "research-required") }
        rejectIf(building.type != BuildingType.TOWN_HALL && building.level >= city.level(), "town-hall-level-required")
        near(player, city.worldId, building.entrance)
        if (buildingId in city.constructionQueue.orEmpty()) return@action CompletableFuture.completedFuture(Unit)
        rejectIf(pendingConfirmations[player.uniqueId]?.isDone == false, "city-busy")
        cancelPreview(player.uniqueId, "upgrade-started")
        // Upgrades reuse immutable preparation and the durable order transaction, without a placement preview.
        val result = prepare(player, city.id, city.name, CivsBlueprints.styleOf(building) ?: city.style, building.type, building.level + 1, building.id,
            building.anchor, true, building.rotation, fixedEntrance = building.entrance)
            .thenCompose { confirmPreview(player) }
        pendingBuildingOrders[key] = result
        result.whenCompleteSync(tasks) { _, failure ->
            if (pendingBuildingOrders[key] === result) {
                pendingBuildingOrders.remove(key)
                if (failure != null && previews[player.uniqueId]?.let { it.upgrading && it.blueprint.building.id == buildingId } == true)
                    cancelPreview(player.uniqueId, "upgrade-failed")
            }
        }
        result
    }

    override fun rotatePreview(player: Player, degrees: Int): CompletableFuture<Unit> = action("rotate_preview", player, "degrees" to degrees) {
        val old = currentPreview(player)
        rejectIf(old.upgrading, "upgrade-position-fixed")
        require(degrees == -90 || degrees == 90)
        val b = old.blueprint.building
        prepare(player, old.cityId, old.cityName, old.style, b.type, b.level, b.id, b.anchor, false,
            Math.floorMod(b.rotation + degrees, 360), old.movementOrigin, autonomous = old.autonomous,
            entryId = b.schematicId.takeIf { it.startsWith("civs/v2/") })
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
        return prepare(player, old.cityId, old.cityName, old.style, b.type, b.level, b.id, placement, false, b.rotation, autonomous = old.autonomous,
            entryId = b.schematicId.takeIf { it.startsWith("civs/v2/") })
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
        prepare(player, old.cityId, old.cityName, old.style, b.type, b.level, b.id, anchor, false, b.rotation, old.movementOrigin, autonomous = old.autonomous,
            entryId = b.schematicId.takeIf { it.startsWith("civs/v2/") })
    }

    private fun prepare(
        player: Player, cityId: UUID?, name: String, style: String, type: BuildingType, level: Int,
        buildingId: UUID, placement: CivPosition, upgrading: Boolean, rotation: Int? = null, movementOrigin: CivPosition? = null,
        selectionId: UUID? = null, fixedEntrance: CivPosition? = null, autonomous: Boolean = false,
        entryId: String? = null,
    ): CompletableFuture<Unit> {
        requireReady(player)
        rejectIf(!upgrading && type != BuildingType.PLAN_BOARD && style !in styles(type), "invalid-style")
        requirePreviewSlot(player)
        val worldId = player.world.uid
        if (cityId != null) managedPlacement(player, cityId, type).also {
            rejectIf((it.autonomy != null) != autonomous, "stale-city")
        }
        val request = UUID.randomUUID()
        requests[player.uniqueId] = request
        CivsDiagnostics.event("preview_load", *CivsDiagnostics.actor(player), "request" to request,
            "city" to cityId, "city_name" to name, "style" to style, "type" to type,
            "placement" to placement, "fixed_entrance" to fixedEntrance, "rotation" to rotation)
        previewLoads[player.uniqueId] = !upgrading
        if (!upgrading) refreshBoundary(player)
        // Keep the last usable position visible until the replacement is ready.
        val result = tracked<Unit>()
        val preparedBlueprint = if (upgrading) blueprints.prepareUpgrade(
            requireNotNull(cityId?.let { cities[it]?.building(buildingId) }), level, worldId)
        else if (entryId != null) blueprints.prepareEntry(entryId, buildingId, worldId, placement, facing(player), rotation)
        else blueprints.prepare(style, type, level, buildingId, worldId, placement, facing(player), rotation, fixedEntrance)
        preparedBlueprint
            .whenCompleteSync(tasks) { blueprint, failure ->
                previewLoads.remove(player.uniqueId)
                if (requests[player.uniqueId] != request || !player.isOnline || player.world.uid != worldId ||
                    selectionId != null && selections[player.uniqueId]?.let { it.id == selectionId && it.expires > now() } != true) {
                    result.completeExceptionally(CivsRejection("preview-expired"))
                } else if (failure != null) {
                    log("prepare preview", failure)
                    result.completeExceptionally(failure)
                } else runCatching {
                    val source = requireNotNull(blueprint)
                    val city = cityId?.let { managedPlacement(player, it, source.building.type) }
                    if (city != null) rejectIf((city.autonomy != null) != autonomous, "stale-city")
                    val prepared = source.copy(building = source.building.copy(production = city?.building(buildingId)?.production))
                    if (city != null) rejectIf(!city.contains(prepared.building.bounds.min.x, prepared.building.bounds.min.z) ||
                        !city.contains(prepared.building.bounds.max.x, prepared.building.bounds.max.z), "outside-city")
                    if (!upgrading) renderer.show(player, prepared.building, prepared.surface)
                    previews[player.uniqueId] = Preview(request, cityId, name, style, prepared, upgrading,
                        now() + settings.previewSeconds * 1_000L, movementOrigin ?: prepared.building.anchor, autonomous)
                    if (selectionId != null) selections.remove(player.uniqueId)
                    if (!upgrading) refreshBoundary(player)
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
        pendingConfirmations[player.uniqueId]?.takeUnless { it.isDone }?.let { return@action it }
        val result = if (preview.cityId == null) confirmPreview(player, preview)
        else afterCityAvailable(preview.cityId) { confirmPreview(player, preview) }
        pendingConfirmations[player.uniqueId] = result
        result.whenCompleteSync(tasks) { _, _ ->
            if (pendingConfirmations[player.uniqueId] === result) pendingConfirmations.remove(player.uniqueId)
        }
        result
    }

    private fun confirmPreview(player: Player, preview: Preview): CompletableFuture<Unit> {
        rejectIf(currentPreview(player) !== preview || !player.isOnline, "preview-expired")
        val blueprint = preview.blueprint
        rejectIf(blueprint.building.type.isInfrastructure, "invalid-building")
        val cityCenter = blueprint.building.control ?: blueprint.building.entrance
        near(player, blueprint.worldId, blueprint.building.entrance)
        val cityId = preview.cityId ?: UUID.randomUUID()
        val previousCity = preview.cityId?.let { managedPlacement(player, it, blueprint.building.type) }
        val queuedUpgrade = preview.upgrading && previousCity?.let {
            it.construction != null || it.constructionQueue.orEmpty().isNotEmpty()
        } == true
        if (preview.upgrading) {
            val existing = previousCity?.building(blueprint.building.id) ?: throw CivsRejection("building-not-found")
            rejectIf(existing.phase != BuildingPhase.ACTIVE || existing.level + 1 != blueprint.building.level, "invalid-building")
            rejectIf(previousCity.construction?.buildingId == existing.id, "construction-busy")
            if (existing.id in previousCity.constructionQueue.orEmpty()) {
                cancelPreview(player.uniqueId)
                return CompletableFuture.completedFuture(Unit)
            }
        }
        if (preview.upgrading && !queuedUpgrade && previousCity?.building(blueprint.building.id)?.control != blueprint.building.control) {
            return relocateControl(player, blueprint.building.id, requireNotNull(blueprint.building.control))
                .thenCompose { afterCityAvailable(cityId) { confirmPreview(player, preview) } }
        }
        rejectIf(cityId in busy || cityId in unhealthy, "city-busy")
        if (previousCity == null) {
            requireFounder(player, preview.autonomous)
            checkSettlementSpace(blueprint.worldId, cityCenter)
            pendingFounders[player.uniqueId] = FoundingReservation(cityId, blueprint.worldId, cityCenter)
        }
        busy += cityId
        val result = tracked<Unit>()
        val captureOwner = previousCity?.owner ?: player.uniqueId
        val previous = previousCity?.building(blueprint.building.id)
        val adminPlacement = previousCity == null && player.hasPermission("arccivs.admin")
        val capture = if (preview.upgrading && !queuedUpgrade) world.capture(cityId, captureOwner, blueprint.worldId,
            blueprint.building, blueprint.cells, previous)
        else if (queuedUpgrade) world.captureSite(cityId, captureOwner, blueprint.worldId,
            blueprint.building, blueprint.cells, previous)
        else world.captureManualFoundation(cityId, captureOwner, blueprint.worldId, blueprint.building,
            blueprint.cells, executor, previous, autonomous = preview.autonomous, adminPlacement = adminPlacement).thenCompose { terrain ->
            if (preview.autonomous && !adminPlacement) world.captureAutonomousSite(cityId, captureOwner, blueprint.worldId,
                blueprint.building, blueprint.cells, terrain)
            else world.captureSite(cityId, captureOwner, blueprint.worldId, blueprint.building,
                blueprint.cells, previous, terrain, adminPlacement = adminPlacement)
        }
        capture.whenCompleteSync(tasks) { captured, captureFailure ->
            if (captureFailure != null) return@whenCompleteSync finishReservation(cityId, player.uniqueId, result, captureFailure)
            runCatching {
                rejectIf(!player.isOnline || previews[player.uniqueId] !== preview, "preview-expired")
                near(player, blueprint.worldId, blueprint.building.entrance)
                val placementCity = previousCity?.let {
                    managedPlacement(player, it.id, blueprint.building.type).also { current -> rejectIf(current != it, "stale-city") }
                }
                val capturedPlan = requireNotNull(captured).copy(capturedSha256 = blueprint.snapshot.fileSha256,
                    adminPlacement = adminPlacement)
                if (preview.upgrading && !queuedUpgrade) {
                    startOrder(player.uniqueId, cities.getValue(cityId), capturedPlan, blueprint.sourceBytes).whenCompleteSync(tasks) { _, failure ->
                        if (failure == null) cancelPreview(player.uniqueId)
                        finishReservation(cityId, player.uniqueId, result, failure)
                    }
                } else {
                    val control = if (queuedUpgrade || blueprint.building.type == BuildingType.DECORATION) null else prepareControl(cityId, blueprint.worldId,
                        previousCity?.owner ?: player.uniqueId, blueprint.building, prepareApproach = capturedPlan.terrain == null)
                    // Queued upgrades have a separate immutable capture; the original SITE payload stays owned by its building.
                    val plan = capturedPlan.copy(id = if (queuedUpgrade) queuedUpgradeId(blueprint.building.id, blueprint.building.level)
                        else blueprint.building.id)
                    val placed = if (queuedUpgrade) CivsRules.enqueue(cities.getValue(cityId), player.uniqueId, blueprint.building.id)
                    else if (previousCity == null) {
                        checkSettlementSpace(blueprint.worldId, cityCenter, player.uniqueId)
                        requireFounderAfterCapture(player, preview.autonomous, adminPlacement)
                        CivsMembership.remember((if (preview.autonomous) CivsAutonomy::found else CivsRules::found).invoke(cityId, player.uniqueId, preview.cityName, blueprint.worldId,
                            cityCenter, settings.radius, preview.style, blueprint.building, now()), mapOf(player.uniqueId to player.name))
                    } else if (placementCity?.autonomy != null) {
                        // Only managedPlacement's explicit Auto board branch may use the domain owner.
                        val site = CivsRules.placeSite(placementCity, placementCity.owner, blueprint.building)
                        CivsRules.enqueue(site, placementCity.owner, blueprint.building.id).copy(revision = placementCity.revision + 1)
                    } else CivsRules.placeSite(requireNotNull(placementCity), player.uniqueId, blueprint.building)
                    val next = if (control == null) placed else CivsRules.validate(placed.copy(pendingControls = placed.pendingControls.orEmpty() + control))
                    // File writes may finish after permissions or the selected city changed. Recheck on Main
                    // immediately before the city commit; other capture owners keep their existing save path.
                    store.saveSchematic(plan.id, blueprint.sourceBytes).thenCompose { store.savePlan(plan) }.thenCompose {
                        tasks.callSync {
                            rejectIf(!player.isOnline || previews[player.uniqueId] !== preview, "preview-expired")
                            requireReady(player)
                            near(player, blueprint.worldId, blueprint.building.entrance)
                            if (placementCity == null) requireFounderAfterCapture(player, preview.autonomous, adminPlacement)
                            else rejectIf(managedPlacement(player, cityId, blueprint.building.type) != placementCity, "stale-city")
                            store.save(next)
                        }.thenCompose { it }
                    }.whenCompleteSync(tasks) { saved, saveFailure ->
                            if (saveFailure != null) {
                                val rejected = CivsDiagnostics.rejection(saveFailure)
                                if (rejected != null) {
                                    if (queuedUpgrade) cleanupCaptured(plan.id).whenCompleteSync(tasks) { _, _ ->
                                        finishReservation(cityId, player.uniqueId, result, rejected)
                                    } else finishReservation(cityId, player.uniqueId, result, rejected)
                                }
                                else {
                                    // Unknown commit outcomes keep the site capture for canonical recovery.
                                    unhealthy += cityId; initialized = false
                                    log("commit building design $cityId", saveFailure, cityId)
                                    finishReservation(cityId, player.uniqueId, result, CivsRejection("storage-unavailable"))
                                }
                            } else publish(requireNotNull(saved)).whenCompleteSync(tasks) { _, publishFailure ->
                                if (publishFailure == null) {
                                    cancelPreview(player.uniqueId)
                                    if (queuedUpgrade) {
                                        CivsDiagnostics.event("construction_enqueued", "city" to cityId, "building" to plan.building.id,
                                            "position" to (saved.constructionQueue.orEmpty().indexOf(plan.building.id) + 1), "upgrade" to true)
                                        finishReservation(cityId, player.uniqueId, result, null)
                                        return@whenCompleteSync
                                    }
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
        return result
    }

    private fun requireFounderAfterCapture(player: Player, autonomous: Boolean = false, adminPlacement: Boolean = false) {
        rejectIf((autonomous || adminPlacement) && !player.hasPermission("arccivs.admin"), "not-authorized")
        rejectIf(!autonomous && (city(player) != null || remoteCity(player) != null), "already-member")
    }

    override fun buildingMaterials(player: Player, buildingId: UUID): CompletableFuture<Map<String, Long>> = action("building_materials", player, "building" to buildingId) {
        val city = managed(player)
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        if (city.construction?.buildingId == buildingId) CompletableFuture.completedFuture(CivsMaterials.unpaid(city.construction.materials.orEmpty()))
        else if (building.phase != BuildingPhase.SITE && buildingId !in city.constructionQueue.orEmpty()) CompletableFuture.completedFuture(emptyMap())
        else store.loadPlan(queuedDesignId(building)).thenApplyAsync({ plan ->
            requireQueuedDesign(requireNotNull(plan), city, building)
            CivsMaterials.required(plan)
        }, executor)
    }

    override fun supplyConstruction(player: Player, buildingId: UUID): CompletableFuture<CivsSupplyResult> =
        cityAction("construction_supply", player, "building" to buildingId) { supplyConstruction(player, buildingId, false) }

    override fun supplyConstruction(player: Player, buildingId: UUID, hutId: UUID): CompletableFuture<CivsSupplyResult> =
        cityAction("construction_supply", player, "building" to buildingId, "hut" to hutId) { supplyConstruction(player, buildingId, false, hutId) }

    override fun grantConstructionMaterials(player: Player, buildingId: UUID): CompletableFuture<CivsSupplyResult> =
        cityAction("construction_grant", player, "building" to buildingId) { supplyConstruction(player, buildingId, true) }

    override fun grantConstructionMaterials(player: Player, buildingId: UUID, hutId: UUID): CompletableFuture<CivsSupplyResult> =
        cityAction("construction_grant", player, "building" to buildingId, "hut" to hutId) { supplyConstruction(player, buildingId, true, hutId) }

    private fun mayGrantMaterials(player: Player): Boolean = player.hasPermission("arccivs.admin") || player.gameMode == org.bukkit.GameMode.CREATIVE

    private fun constructionDeficit(required: Map<String, Long>, available: Map<String, Long>): Map<String, Long> =
        required.mapValues { (material, count) -> (count - (available[material] ?: 0L)).coerceAtLeast(0) }.filterValues { it > 0 }

    private fun constructionStore(city: Settlement, buildingId: UUID, hutId: UUID?): CivBuilding =
        CivsRules.constructionStores(city, buildingId).let { stores ->
            if (hutId != null) stores.firstOrNull { it.id == hutId }
            else stores.firstOrNull { store -> city.workers.any { it.assignment == store.id } } ?: stores.firstOrNull()
        } ?: throw CivsRejection("builder-required")

    private fun supplyConstruction(player: Player, buildingId: UUID, grant: Boolean, hutId: UUID? = null): CompletableFuture<CivsSupplyResult> {
        rejectIf(grant && !mayGrantMaterials(player), "not-authorized")
        val city = managed(player)
        rejectIf(city.id in busy, "city-busy")
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        rejectIf(city.inventory?.mode != CivStorageMode.PHYSICAL, "invalid-storage-mode")
        val order = city.construction?.takeIf { it.buildingId == buildingId }
        rejectIf(order == null && building.phase != BuildingPhase.SITE && buildingId !in city.constructionQueue.orEmpty(), "invalid-building")
        // Legacy orders paid their complete bill when reserved; their estimate must never mint another payment.
        if (order != null && order.payment == null) return CompletableFuture.completedFuture(CivsSupplyResult(0, 0, false))
        val destination = constructionStore(city, buildingId, hutId)
        val grantMode = player.gameMode
        near(player, city.worldId, if (hutId == null) building.entrance else destination.entrance)
        val required = if (order != null) CompletableFuture.completedFuture(CivsMaterials.unpaid(order.materials.orEmpty()))
        else store.loadPlan(queuedDesignId(building)).thenApplyAsync({ stored ->
            val plan = requireNotNull(stored) { "Missing site design $buildingId" }
            requireQueuedDesign(plan, city, building)
            CivsMaterials.required(plan)
        }, executor)
        busy += city.id
        val result = tracked<CivsSupplyResult>()
        required.whenCompleteSync(tasks) { materials, failure ->
            busy -= city.id
            if (failure != null) { result.completeExceptionally(failure); return@whenCompleteSync }
            runCatching {
                rejectIf(!player.isOnline || grant && !mayGrantMaterials(player), "not-authorized")
                val current = managed(player)
                rejectIf(current.id != city.id || current.building(buildingId) != building || current.construction != city.construction ||
                    constructionStore(current, buildingId, destination.id) != destination, "invalid-building")
                near(player, current.worldId, if (hutId == null) building.entrance else destination.entrance)
                var supplied: CivStorageSupplyPlan? = null
                inventoryMutation(current.id, { lockedCity ->
                    val missing = constructionDeficit(requireNotNull(materials), storage.constructionAvailable(lockedCity, buildingId))
                    val local = storage.buildingAvailable(lockedCity, destination.id)
                    val desired = missing.mapValues { (material, count) -> count + (local[material] ?: 0L) }
                    storage.prepareConstructionSupply(lockedCity, destination.id, desired, if (grant) null else player)
                        .also { supplied = it }.plan
                }, sourcePlayer = if (grant) null else player) { before, inventory ->
                    if (requireNotNull(supplied).plan.changes.isEmpty()) before
                    else before.copy(inventory = inventory, revision = Math.addExact(before.revision, 1))
                }.whenCompleteSync(tasks) { _, applyFailure ->
                    if (applyFailure != null) result.completeExceptionally(applyFailure)
                    else {
                        val outcome = requireNotNull(supplied).result
                        val moved = requireNotNull(supplied).plan.changes.groupBy { it.after?.material ?: it.before?.material }.mapValues { (_, changes) ->
                            changes.sumOf { (it.after?.amount ?: 0).toLong() - (it.before?.amount ?: 0) }
                        }.filterValues { it > 0 }
                        CivsDiagnostics.event("construction_supplied", "city" to city.id, "building" to buildingId, "hut" to destination.id,
                            "player" to player.uniqueId, "grant" to grant, "game_mode" to grantMode, "materials" to moved, "moved" to outcome.moved,
                            "remaining" to outcome.remaining, "storage_full" to outcome.storageFull)
                        result.complete(outcome)
                    }
                }
            }.onFailure(result::completeExceptionally)
        }
        return result
    }

    override fun supplyBuilderMaterials(player: Player, workerId: UUID): CompletableFuture<CivsSupplyResult> =
        supplyBuilderMaterials(player, workerId, false)

    override fun grantBuilderMaterials(player: Player, workerId: UUID): CompletableFuture<CivsSupplyResult> =
        supplyBuilderMaterials(player, workerId, true)

    private fun supplyBuilderMaterials(player: Player, workerId: UUID, grant: Boolean): CompletableFuture<CivsSupplyResult> =
        cityAction("builder_materials_supply", player, "worker" to workerId, "grant" to grant) { city ->
            rejectIf(grant && !mayGrantMaterials(player), "not-authorized")
            rejectIf(city.inventory?.mode != CivStorageMode.PHYSICAL, "invalid-storage-mode")
            val order = city.construction ?: throw CivsRejection("no-construction")
            if (order.payment == null) return@cityAction CompletableFuture.completedFuture(CivsSupplyResult(0, 0, false))
            val worker = CivsRules.constructionWorkers(city, order.buildingId).firstOrNull { it.id == workerId }
                ?: throw CivsRejection("builder-required")
            val points = listOfNotNull(worker.assignment?.let(city::building)?.entrance, order.plannedBuilding?.entrance)
            rejectIf(player.world.uid != city.worldId || points.none { point ->
                player.location.toVector().distanceSquared(org.bukkit.util.Vector(point.x.toDouble(), point.y.toDouble(), point.z.toDouble())) <= 96.0 * 96.0
            }, "too-far")
            var updated: CivWorker? = null
            val grantMode = player.gameMode
            var moved: Map<String, Long> = emptyMap()
            var outcome = CivsSupplyResult(0, 0, false)
            inventoryMutation(city.id, { before ->
                rejectIf(grant && !mayGrantMaterials(player), "not-authorized")
                rejectIf(before.construction?.id != order.id || before.construction.workDone != order.workDone, "stale-construction")
                val current = CivsRules.constructionWorkers(before, order.buildingId).firstOrNull { it.id == workerId }
                    ?: throw CivsRejection("builder-required")
                val missing = constructionDeficit(CivsMaterials.unpaid(requireNotNull(before.construction).materials.orEmpty()),
                    storage.constructionAvailable(before, order.buildingId))
                val offered = if (grant) missing else missing.mapValues { (material, count) -> minOf(count,
                    player.inventory.storageContents.filterNotNull().filter { it.type.name == material && it.isSimilar(org.bukkit.inventory.ItemStack(it.type)) }
                        .sumOf { it.amount.toLong() }) }.filterValues { it > 0 }
                rejectIf(missing.isNotEmpty() && offered.isEmpty(), "construction-supplies-empty")
                val prepared = if (grant) {
                    val (issued, amounts) = CivsEquipment.grantSupplies(current, missing)
                    updated = issued
                    moved = amounts
                    CivStoragePlan(requireNotNull(before.inventory), emptyList())
                } else storage.prepareResidentTransfer(before, workerId, player, null, null,
                    org.bukkit.event.inventory.ClickType.LEFT, requestedSupplies = missing).also { transfer ->
                    updated = transfer.worker
                    moved = missing.mapValues { (material, _) ->
                        (CivsEquipment.plainCount(transfer.worker, material) - CivsEquipment.plainCount(current, material)).toLong()
                    }.filterValues { it > 0 }
                }.plan
                val count = moved.values.sum()
                outcome = CivsSupplyResult(count, missing.values.sum() - count, offered.values.sum() > count)
                prepared
            }, sourcePlayer = if (grant) null else player, keepViewersOpen = true) { before, inventory ->
                if (outcome.moved == 0L) before else before.copy(inventory = inventory,
                    workers = before.workers.map { if (it.id == workerId) requireNotNull(updated) else it }, revision = before.revision + 1)
            }.thenApply { saved ->
                CivsDiagnostics.event("builder_materials_supplied", "city" to saved.id, "building" to order.buildingId,
                    "worker" to workerId, "player" to player.uniqueId, "grant" to grant, "game_mode" to grantMode,
                    "materials" to moved, "moved" to outcome.moved, "remaining" to outcome.remaining, "storage_full" to outcome.storageFull)
                outcome
            }
        }

    override fun startBuilding(player: Player, buildingId: UUID): CompletableFuture<Unit> = action("construction_order", player, "building" to buildingId) {
        val offered = managed(player)
        val cityId = offered.id
        val key = cityId to buildingId
        pendingBuildingOrders[key]?.takeUnless { it.isDone }?.let { return@action it }
        val offeredBuilding = offered.building(buildingId) ?: throw CivsRejection("building-not-found")
        rejectIf(offeredBuilding.phase != BuildingPhase.SITE, "invalid-building")
        val result = afterCityAvailable(cityId) {
            val city = managed(player)
            rejectIf(city.id != cityId || !player.isOnline, "not-authorized")
            val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
            rejectIf(building.phase != BuildingPhase.SITE, "invalid-building")
            near(player, city.worldId, building.entrance)
            construction.clearQueueFailure(city.id)
            if (city.construction != null || city.constructionQueue.orEmpty().isNotEmpty()) {
                mutate(city.id) { CivsRules.enqueue(it, player.uniqueId, buildingId) }.thenApply { saved ->
                    if (saved.revision != city.revision) CivsDiagnostics.event("construction_enqueued", "city" to city.id,
                        "building" to buildingId, "position" to (saved.constructionQueue.orEmpty().indexOf(buildingId) + 1))
                    Unit
                }
            } else activateBuilding(city, building, player)
        }
        pendingBuildingOrders[key] = result
        result.whenCompleteSync(tasks) { _, _ ->
            if (pendingBuildingOrders[key] === result) pendingBuildingOrders.remove(key)
        }
        result
    }

    /** Main-thread reservation wait; callers re-read and validate state after every handoff. */
    private fun <T> afterCityAvailable(cityId: UUID, action: () -> CompletableFuture<T>): CompletableFuture<T> {
        checkMain()
        rejectIf(!ready(), "not-ready")
        rejectIf(cityId in unhealthy, "storage-unavailable")
        if (cityId !in busy && cityAvailability[cityId].isNullOrEmpty()) return action()
        val result = tracked<T>()
        val available = tracked<Unit>()
        cityAvailability.getOrPut(cityId) { ArrayDeque() }.addLast(available)
        available.whenComplete { _, availabilityFailure ->
            if (result.isDone) return@whenComplete
            if (availabilityFailure != null) {
                result.completeExceptionally(availabilityFailure)
                return@whenComplete
            }
            runCatching {
                rejectIf(!ready(), "not-ready")
                rejectIf(cityId in unhealthy, "storage-unavailable")
                action().whenComplete { value, failure ->
                    if (failure == null) result.complete(value) else result.completeExceptionally(failure)
                }
            }.onFailure(result::completeExceptionally)
        }
        return result
    }

    private fun resumeCityActions() {
        // Release one waiter in arrival order; CompletableFuture sibling callbacks do not guarantee FIFO.
        cityAvailability.keys.filter { !ready() || it !in busy || it in unhealthy }.forEach { cityId ->
            val waiting = cityAvailability[cityId] ?: return@forEach
            val available = waiting.removeFirst()
            if (waiting.isEmpty()) cityAvailability.remove(cityId)
            available.complete(Unit)
        }
    }

    private fun cleanupCaptured(id: UUID): CompletableFuture<Unit> =
        runCatching { store.deletePlan(id).thenCompose { store.deleteSchematic(id) }.handle { _, failure ->
            if (failure != null) log("clean captured plan $id", failure)
            Unit
        } }.getOrElse { log("clean captured plan $id", it); CompletableFuture.completedFuture(Unit) }

    private fun cleanupQueuedUpgrades(city: Settlement, buildingIds: Set<UUID>) {
        city.buildings.filter { it.id in buildingIds && it.id in city.constructionQueue.orEmpty() && it.phase == BuildingPhase.ACTIVE }
            .forEach { cleanupCaptured(queuedDesignId(it)) }
    }

    private fun activateBuilding(city: Settlement, building: CivBuilding, player: Player? = null): CompletableFuture<Unit> {
        val buildingId = building.id
        val actor = player?.uniqueId ?: city.owner
        rejectIf(city.pendingDeletion != null, "deletion-pending")
        rejectIf(city.construction != null || city.id in busy, "city-busy")
        if (building.phase == BuildingPhase.SITE) requireControlBarrel(city.worldId, building)
        requireAssignedBuilder(city, building.id)
        busy += city.id
        val result = tracked<Unit>()
        val designId = queuedDesignId(building)
        store.loadPlan(designId).thenCompose { plan ->
            requireNotNull(plan) { "Missing queued design $buildingId" }
            requireQueuedDesign(plan, city, building)
            blueprints.recover(plan).thenCombine(store.loadSchematic(designId)) { snapshot, bytes ->
                Triple(plan, snapshot, requireNotNull(bytes))
            }
        }.whenCompleteSync(tasks) { loaded, failure ->
            if (failure != null) return@whenCompleteSync finishReservation(city.id, actor, result, failure)
            runCatching {
                val (sitePlan, snapshot, bytes) = requireNotNull(loaded)
                val target = if (building.phase == BuildingPhase.SITE) building.copy(phase = BuildingPhase.CONSTRUCTING)
                    else sitePlan.building.copy(production = building.production)
                if (target.control != building.control) {
                    busy -= city.id
                    relocateControl(city, building, requireNotNull(target.control)).whenCompleteSync(tasks) { relocated, relocationFailure ->
                        if (relocationFailure != null) finishReservation(city.id, actor, result, relocationFailure)
                        else runCatching { activateBuilding(requireNotNull(relocated), requireNotNull(relocated.building(buildingId)), player) }
                            .onSuccess { activated -> activated.whenCompleteSync(tasks) { _, activationFailure ->
                                finishReservation(city.id, actor, result, activationFailure)
                            } }.onFailure { finishReservation(city.id, actor, result, it) }
                    }
                    return@whenCompleteSync
                }
                requireControlBarrel(city.worldId, building)
                CompletableFuture.supplyAsync({ CivsBlueprints.worldCells(snapshot, target.anchor,
                    decoration = target.type == BuildingType.DECORATION) }, executor).whenCompleteSync(tasks) cellsReady@ { cells, cellsFailure ->
                    if (cellsFailure != null) return@cellsReady finishReservation(city.id, actor, result, cellsFailure)
                    // Waiting designs reserve against fresh before-images, never the terrain at confirmation.
                    val terrain = if (building.phase == BuildingPhase.SITE &&
                        (sitePlan.adminPlacement || city.autonomy == null || sitePlan.terrain?.route?.isEmpty() == true))
                        world.captureManualFoundation(city.id, city.owner, city.worldId, target,
                            requireNotNull(cells), executor, building, adminPlacement = sitePlan.adminPlacement)
                    else if (city.autonomy != null && building.phase == BuildingPhase.SITE)
                        autonomousPlacement.prepareExisting(city, CivBlueprint(target, city.worldId, snapshot,
                            requireNotNull(cells), emptyList(), bytes)).thenApply { it.terrain }
                    else CompletableFuture.completedFuture(sitePlan.terrain)
                    terrain.thenCompose { preparation -> world.capture(city.id, city.owner, city.worldId,
                        target, requireNotNull(cells), building, preparation, adminPlacement = sitePlan.adminPlacement) }
                        .whenCompleteSync(tasks) capturedReady@ { captured, captureFailure ->
                            if (captureFailure != null) return@capturedReady finishReservation(city.id, actor, result, captureFailure)
                            runCatching {
                                rejectIf(!ready() || cities[city.id]?.pendingDeletion != null, "not-ready")
                                if (player != null) {
                                    rejectIf(!player.isOnline, "not-authorized")
                                    near(player, city.worldId, building.entrance)
                                }
                                requireControlBarrel(city.worldId, building)
                                startOrder(actor, cities.getValue(city.id), requireNotNull(captured).copy(capturedSha256 = sitePlan.capturedSha256), bytes)
                                    .whenCompleteSync(tasks) { _, orderFailure ->
                                        if (orderFailure == null && building.phase == BuildingPhase.ACTIVE) cleanupCaptured(designId)
                                        finishReservation(city.id, actor, result, orderFailure)
                                    }
                            }.onFailure { finishReservation(city.id, actor, result, it) }
                        }
                }
            }.onFailure { finishReservation(city.id, actor, result, it) }
        }
        return result
    }

    /** Starting an order reserves the immutable design; each worked row pays independently later. */
    private fun startOrder(actor: UUID, city: Settlement, plan: CivPlacementPlan, bytes: ByteArray): CompletableFuture<Settlement> {
        rejectIf(city.construction != null, "construction-busy")
        rejectIf(city.inventory == null && city.autonomy == null, "materials-migration-pending")
        rejectIf(!city.canManage(actor), "not-authorized")
        val building = plan.building
        requireAssignedBuilder(city, building.id)
        if (building.type == BuildingType.TOWN_HALL && building.level > city.level()) {
            rejectIf(cities.values.any { it.id != city.id && it.worldId == city.worldId &&
                areasOverlap(city.center, city.radius + 32, it.center, it.radius) }, "city-overlap")
        }
        rejectIf(city.pendingControls.orEmpty().any { it.buildingId == building.id }, "city-busy")
        val approach = if (plan.terrain != null || building.type.isInfrastructure || building.type == BuildingType.DECORATION) null
            else prepareControl(city.id, city.worldId, city.owner, building).takeIf { !it.approach.isNullOrEmpty() }
        val result = tracked<Settlement>()
        CompletableFuture.supplyAsync({
            val materials = CivsMaterials.required(plan)
            val terrainBatches = civsTerraformBatches(plan)
            val automaticPreparation = terrainBatches.size.takeIf { it > 0 && plan.terrain?.route?.isEmpty() == true }
            val batches = CivConstructionBatches(terrainBatches.size + civsMaterialBatches(plan, atomicPairs = true).size, 16, atomicPairs = true)
            CivConstruction(plan.id, building.id, building.level, batches.total, 0,
                if (automaticPreparation != null) ConstructionPhase.READY_TO_PLACE else ConstructionPhase.CONSTRUCTING,
                if (city.autonomy != null && !CivsPlanBoardBlueprint.refit(city.building(building.id), building))
                    CivsAutonomy.cost(building.type, building.level, materials) else Stock(),
                plannedBuilding = building, batches = batches, materials = materials,
                payment = if (city.autonomy != null) null else CivConstructionPayment(),
                blocksRequired = plan.blocks.count { it.before != it.after } + plan.terrain?.blocks.orEmpty().count { it.before != it.after },
                blocksPlaced = 0, preparationWorkplace = terrainBatches.firstOrNull()?.workplace ?: plan.terrain?.route?.lastOrNull(),
                automaticPreparationBatches = automaticPreparation)
        }, executor).thenCompose { order ->
            store.saveSchematic(plan.id, bytes).thenCompose { store.savePlan(plan) }.thenCompose {
                // Order captures are deleted on completion; keep the admitted access road with its building.
                if (city.autonomy != null && plan.terrain != null)
                    store.saveSchematic(building.id, bytes).thenCompose { store.savePlan(plan.copy(id = building.id)) }
                else CompletableFuture.completedFuture(plan)
            }.thenApply { order }
        }.whenCompleteSync(tasks) { preparedOrder, failure ->
            if (failure != null) { busy -= city.id; result.completeExceptionally(failure) }
            else runCatching {
                requireAssignedBuilder(cities.getValue(city.id), building.id)
                val order = requireNotNull(preparedOrder)
                val materials = requireNotNull(order.materials)
                val batches = requireNotNull(order.batches)
                busy -= city.id
                mutate(city.id) { before ->
                    val reserved = CivsRules.reserveBuild(before, actor, building, order)
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
            }.onFailure { busy -= city.id; result.completeExceptionally(it) }
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
        if (pendingFounders[playerId]?.cityId == cityId) pendingFounders.remove(playerId)
        if (failure == null) future.complete(Unit) else future.completeExceptionally(failure)
        updateHealth()
    }

    override fun recruit(player: Player): CompletableFuture<Unit> = cityAction("recruit", player) {
        throw CivsRejection("population-natural")
    }
    override fun assign(player: Player, workerId: UUID, buildingId: UUID?): CompletableFuture<Unit> = cityAction("assign", player, "worker" to workerId, "building" to buildingId) { city ->
        mutate(city.id) { CivsRules.assign(it, player.uniqueId, workerId, buildingId, now()) }.thenApply { Unit }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun rememberInjury(event: CivsResidentDamageEvent) {
        val source = (event.source as? org.bukkit.entity.Projectile)?.shooter as? org.bukkit.entity.Entity ?: event.source
        val location = citizens.residentFeet(event.workerId) ?: event.location
        val killerName = (source as? Player)?.name ?: source?.customName()?.let {
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it)
                .filterNot(Char::isISOControl).take(64).takeIf(String::isNotBlank)
        }
        lastInjury[event.workerId] = CivDeathContext(CivPosition(location.blockX, location.blockY, location.blockZ), now(),
            killerName, source?.type?.name, event.cause.name, location.y)
    }

    internal fun hurtResident(cityId: UUID, workerId: UUID, damage: Double): CompletableFuture<Settlement> {
        val death = lastInjury.remove(workerId) ?: CivDeathContext(cities[cityId]?.center ?: CivPosition(0, 0, 0), now())
        return action("resident_injury", null, "city" to cityId, "worker" to workerId, "damage" to damage,
            "damage_cause" to death.cause, "source" to death.killerType, "position" to death.position) {
            afterCityAvailable(cityId) {
                mutate(cityId) { CivsRules.hurtResident(it, it.owner, workerId, damage, death,
                    immortal = it.settings?.immortalResidents == true && testingAllowed(it)) }.thenApply { saved ->
                    saved.corpses.orEmpty().firstOrNull { it.worker.id == workerId }?.let { corpse ->
                        CivsDiagnostics.event("resident_death", "city" to cityId, "worker" to workerId,
                            "cause" to corpse.cause, "killer" to corpse.killerType, "position" to corpse.position,
                            "expires_at" to corpse.expiresAt)
                    }
                    saved
                }
            }
        }
    }

    private fun corpseCity(player: Player, cityId: UUID): Settlement {
        requireReady(player)
        val city = cities[cityId] ?: throw CivsRejection("no-city")
        rejectIf(!(if (city.autonomy != null) player.hasPermission("arccivs.admin") else city.canManage(player.uniqueId)), "not-authorized")
        rejectIf(city.id in unhealthy, "storage-unavailable")
        rejectIf(city.pendingDeletion != null, "deletion-pending")
        return city
    }

    private fun corpse(player: Player, city: Settlement, workerId: UUID, diedAt: Long? = null): CivCorpse {
        val corpse = city.corpses.orEmpty().firstOrNull { it.worker.id == workerId && it.expiresAt > now() }
            ?: throw CivsRejection("worker-not-found")
        rejectIf(diedAt != null && corpse.diedAt != diedAt, "worker-not-found")
        rejectIf(player.world.uid != city.worldId, "wrong-world")
        val location = corpseLocation(city.id, workerId, corpse.diedAt)
            ?: throw CivsRejection("corpse-unavailable")
        rejectIf(location.world?.uid != city.worldId || !civsCorpseStandingSafe(location), "corpse-unavailable")
        rejectIf(player.location.distanceSquared(location) > 36.0, "worker-too-far")
        return corpse
    }

    override fun corpseItems(player: Player, workerId: UUID): List<org.bukkit.inventory.ItemStack?> =
        corpseItems(player, managed(player).id, workerId)

    override fun corpseItems(player: Player, cityId: UUID, workerId: UUID): List<org.bukkit.inventory.ItemStack?> =
        CivsEquipment.slots(corpse(player, corpseCity(player, cityId), workerId).worker).map { it?.stack() }

    override fun transferCorpseItem(player: Player, workerId: UUID, npcSlot: Int,
        click: org.bukkit.event.inventory.ClickType): CompletableFuture<Unit> =
        action("corpse_loot", player) { transferCorpseItem(player, managed(player).id, workerId, npcSlot, click) }

    override fun transferCorpseItem(player: Player, cityId: UUID, workerId: UUID, npcSlot: Int,
        click: org.bukkit.event.inventory.ClickType): CompletableFuture<Unit> =
        action("corpse_loot", player, "city" to cityId, "worker" to workerId, "slot" to npcSlot) {
            corpseCity(player, cityId)
            nativePlayerMutation(cityId, player, keepViewersOpen = true) { before, id ->
                corpse(player, corpseCity(player, cityId), workerId)
                val prepared = storage.prepareCorpseTransfer(before, workerId, player, npcSlot, click)
                val transfer = prepared.transfer ?: return@nativePlayerMutation null
                val after = before.copy(corpses = before.corpses.orEmpty().map {
                    if (it.worker.id == workerId) it.copy(worker = prepared.worker) else it
                }, revision = before.revision + 1)
                CivInventoryTransaction(id, before.id, before.revision, after, emptyList(), residentTransfer = transfer)
            }.thenApply { Unit }
        }

    fun resurrect(player: Player, cityId: UUID, workerId: UUID, diedAt: Long): CompletableFuture<Settlement> =
        action("resident_resurrect", player, "city" to cityId, "worker" to workerId, "died_at" to diedAt) {
            corpseCity(player, cityId)
            nativePlayerMutation(cityId, player) { before, id ->
                val corpse = corpse(player, corpseCity(player, cityId), workerId, diedAt)
                val at = now()
                val after = CivsRules.resurrect(before, player.uniqueId, workerId, diedAt, at,
                    administrator = player.hasPermission("arccivs.admin"))
                val location = corpseLocation(cityId, workerId, diedAt) ?: throw CivsRejection("corpse-unavailable")
                val debit = storage.prepareResurrection(before, player, corpse, at, location)
                CivInventoryTransaction(id, cityId, before.revision, after, emptyList(), resurrection = debit)
            }
        }

    override fun supplyWorker(player: Player, workerId: UUID, material: String): CompletableFuture<Unit> =
        residentItem(player, workerId, material, returning = false)

    override fun returnWorkerItem(player: Player, workerId: UUID, material: String): CompletableFuture<Unit> =
        residentItem(player, workerId, material, returning = true)

    private fun residentItem(player: Player, workerId: UUID, material: String, returning: Boolean): CompletableFuture<Unit> =
        cityAction("resident_item", player, "worker" to workerId, "material" to material, "returning" to returning) { city ->
            rejectIf(city.inventory == null, "materials-required")
            CivsRules.supplyResident(city, player.uniqueId, workerId, material, returning)
            inventoryMutation(city.id, { current ->
                if (returning) storage.prepareCredit(current, material, 1,
                    current.buildings.firstOrNull(CivsRules::isDepot)?.id ?: throw CivsRejection("storage-unavailable"))
                else storage.prepareDebit(current, mapOf(material to 1L))
            }) { before, inventory ->
                CivsRules.supplyResident(before, player.uniqueId, workerId, material, returning).copy(inventory = inventory)
            }.thenApply { Unit }
        }

    override fun feedWorker(player: Player, workerId: UUID): CompletableFuture<Unit> =
        cityAction("resident_feed", player, "worker" to workerId) { city ->
            mutate(city.id) { CivsRules.feedResident(it, player.uniqueId, workerId) }.thenApply { Unit }
        }

    override fun residentItems(player: Player, workerId: UUID): List<org.bukkit.inventory.ItemStack?> {
        val city = managed(player)
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        return CivsEquipment.slots(worker).map { it?.stack() }
    }

    override fun inspectResident(player: Player, cityId: UUID, workerId: UUID): Settlement {
        requireReady(player)
        val city = cities[cityId] ?: throw CivsRejection("no-city")
        rejectIf(if (city.autonomy != null) !player.hasPermission("arccivs.admin")
            else !city.canManage(player.uniqueId), "not-authorized")
        rejectIf(city.id in unhealthy, "storage-unavailable")
        rejectIf(city.pendingDeletion != null, "deletion-pending")
        rejectIf(city.workers.none { it.id == workerId }, "worker-not-found")
        return city
    }

    override fun residentItems(player: Player, cityId: UUID, workerId: UUID): List<org.bukkit.inventory.ItemStack?> {
        val city = inspectResident(player, cityId, workerId)
        return CivsEquipment.slots(city.workers.first { it.id == workerId }).map { it?.stack() }
    }

    override fun toolRequest(player: Player, workerId: UUID): CivsToolRequest? {
        val city = managed(player)
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        val tool = CivsEquipment.requestedTool(city, worker) ?: return null
        return CivsToolRequest(tool, CivsEquipment.canEquipRequestedTool(worker) &&
            (player.gameMode == org.bukkit.GameMode.CREATIVE || CivsEquipment.cheapestTool(player, tool) != null))
    }

    override fun giveRequestedTool(player: Player, workerId: UUID): CompletableFuture<Unit> =
        transferResidentItems(player, workerId, null, null, org.bukkit.event.inventory.ClickType.RIGHT, true)

    override fun workerRequest(player: Player, workerId: UUID): CivsBuildingRequest? {
        val city = managed(player)
        val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
        if (CivsResidents.isChild(worker)) return null
        toolRequest(player, workerId)?.let { return CivsBuildingRequest(it.material, 1, it.canGive) }
        val building = worker.assignment?.let(city::building) ?: return null
        return supplyRequest(player, city, building, worker)?.let { request ->
            request.copy(canGive = request.canGive && (building.type == BuildingType.UNIVERSITY || CivsEquipment.supplyRoom(worker, request.material) > 0))
        }
    }

    override fun giveWorkerRequest(player: Player, workerId: UUID, material: String): CompletableFuture<Unit> =
        cityAction("resident_supply", player, "worker" to workerId, "material" to material) { city ->
            val request = workerRequest(player, workerId) ?: throw CivsRejection("resident-item-missing")
            rejectIf(request.material.name != material, "resident-item-missing")
            val worker = city.workers.first { it.id == workerId }
            if (CivsEquipment.requestedTool(city, worker)?.name == material) giveRequestedTool(player, workerId)
            else giveRequestedSupply(player, city, requireNotNull(worker.assignment), material,
                workerId.takeUnless { city.building(requireNotNull(worker.assignment))?.type == BuildingType.UNIVERSITY })
        }

    override fun buildingRequest(player: Player, buildingId: UUID): CivsBuildingRequest? {
        val city = managed(player)
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        val workers = city.workers.filter { it.assignment == building.id && !CivsResidents.isChild(it) }
        for (worker in workers) {
            toolRequest(player, worker.id)?.let { return CivsBuildingRequest(it.material, 1, it.canGive) }
        }
        val request = if (building.type == BuildingType.SAWMILL) supplyRequest(player, city, building, null)
            else workers.firstNotNullOfOrNull { supplyRequest(player, city, building, it) }
                ?: if (workers.isEmpty()) supplyRequest(player, city, building, null) else null
        return request?.let {
                it.copy(canGive = it.canGive && city.inventory?.mode == CivStorageMode.PHYSICAL)
            }
    }

    private fun supplyRequest(player: Player, city: Settlement, building: CivBuilding, worker: CivWorker?): CivsBuildingRequest? {
        if (city.inventory == null || building.phase != BuildingPhase.ACTIVE) return null
        val available = storage.buildingAvailable(city, building.id).let { stock ->
            if (building.type == BuildingType.UNIVERSITY) stock else worker?.let { CivsEquipment.available(it, stock) } ?: stock }
        val orderedInputs = if (building.type in CivsCarpentry.workshops) {
            if (worker == null) craftRequired(city, building.id).takeIf { it.isNotEmpty() }
            else craftPlan(city, worker)?.inputs
        } else null
        val productionInputs = orderedInputs ?: if (building.type == BuildingType.SAWMILL) emptyMap()
            else CivsProduction.inputs(city, building)
        val requirements = worker?.let { worksites.requirement(city, it) }?.let { listOf(it) }
            ?: productionInputs.entries.sortedBy { (material, count) -> (available[material] ?: 0) >= count }
                .map { (material, count) -> material to if (orderedInputs != null || building.type == BuildingType.UNIVERSITY) count
                    else maxOf(count, Material.matchMaterial(material)?.maxStackSize?.toLong() ?: 1) }
        for ((material, need) in requirements) {
            val missing = need - (available[material] ?: 0)
            if (missing <= 0) continue
            val item = Material.matchMaterial(material) ?: continue
            val canGive = player.gameMode == org.bukkit.GameMode.CREATIVE ||
                player.inventory.storageContents.any { it?.type == item && it.amount > 0 && it.isSimilar(org.bukkit.inventory.ItemStack(item)) }
            return CivsBuildingRequest(item, missing.coerceAtMost(item.maxStackSize.toLong()), canGive)
        }
        return null
    }

    override fun giveBuildingRequest(player: Player, buildingId: UUID, material: String): CompletableFuture<Unit> =
        cityAction("building_supply", player, "building" to buildingId, "material" to material) { city ->
            val request = buildingRequest(player, buildingId) ?: throw CivsRejection("resident-item-missing")
            rejectIf(request.material.name != material, "resident-item-missing")
            val worker = city.workers.firstOrNull {
                it.assignment == buildingId && CivsEquipment.requestedTool(city, it)?.name == material
            }
            if (worker != null) giveRequestedTool(player, worker.id)
            else giveRequestedSupply(player, city, buildingId, material)
        }

    /** Both request screens fulfil the current deficit through the same durable supply operation. */
    private fun giveRequestedSupply(player: Player, city: Settlement, buildingId: UUID, material: String,
        workerId: UUID? = null): CompletableFuture<Unit> {
        val creative = player.gameMode == org.bukkit.GameMode.CREATIVE
        var updated: CivWorker? = null
        var moved = 0L
        return inventoryMutation(city.id, { before ->
            rejectIf(!player.isOnline || creative && player.gameMode != org.bukkit.GameMode.CREATIVE, "not-authorized")
            val request = (if (workerId == null) buildingRequest(player, buildingId) else workerRequest(player, workerId))
                ?: throw CivsRejection("resident-item-missing")
            rejectIf(request.material.name != material, "resident-item-missing")
            if (workerId == null) {
                rejectIf(before.inventory?.mode != CivStorageMode.PHYSICAL, "materials-required")
                val have = storage.buildingAvailable(before, buildingId)[material] ?: 0L
                storage.prepareConstructionSupply(before, buildingId, mapOf(material to have + request.amount),
                    if (creative) null else player).also { moved = it.result.moved }.plan
            } else {
                val worker = before.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
                if (creative) {
                    val (supplied, amounts) = CivsEquipment.grantSupplies(worker, mapOf(material to request.amount))
                    moved = amounts[material] ?: 0L
                    rejectIf(moved == 0L, "resident-inventory-full")
                    updated = supplied
                    CivStoragePlan(requireNotNull(before.inventory), emptyList())
                } else storage.prepareResidentTransfer(before, workerId, player, null, null,
                    org.bukkit.event.inventory.ClickType.LEFT, requestedSupply = request.material to request.amount).also {
                    updated = it.worker
                    moved = (CivsEquipment.plainCount(it.worker, material) - CivsEquipment.plainCount(worker, material)).toLong()
                }.plan
            }
        }, sourcePlayer = if (creative) null else player, keepViewersOpen = true) { before, inventory ->
            if (moved == 0L) before else before.copy(inventory = inventory, revision = before.revision + 1,
                workers = if (workerId == null) before.workers else before.workers.map {
                    if (it.id == workerId) requireNotNull(updated) else it
                })
        }.thenApply { saved ->
            CivsDiagnostics.event(if (workerId == null) "building_supply_committed" else "resident_supply_committed",
                "city" to saved.id, "building" to buildingId, "worker" to workerId, "player" to player.uniqueId,
                "material" to material, "amount" to moved, "grant" to creative, "revision" to saved.revision)
            Unit
        }
    }

    override fun summonWorker(player: Player, buildingId: UUID): CompletableFuture<Unit> =
        cityAction("resident_summon", player, "building" to buildingId) { city ->
            rejectIf(player.world.uid != city.worldId, "wrong-world")
            rejectIf(city.building(buildingId) == null, "building-not-found")
            rejectIf(!citizens.summon(city, buildingId), "worker-summon-unavailable")
            CompletableFuture.completedFuture(Unit)
        }

    override fun transferResidentItem(player: Player, workerId: UUID, npcSlot: Int?, playerSlot: Int?, click: org.bukkit.event.inventory.ClickType): CompletableFuture<Unit> =
        transferResidentItems(player, workerId, npcSlot, playerSlot, click, false)

    private fun transferResidentItems(player: Player, workerId: UUID, npcSlot: Int?, playerSlot: Int?, click: org.bukkit.event.inventory.ClickType, requested: Boolean): CompletableFuture<Unit> =
        cityAction("resident_transfer", player, "worker" to workerId, "resident_slot" to npcSlot, "player_slot" to playerSlot) { city ->
            var updated: CivWorker? = null
            val creativeGrant = requested && player.gameMode == org.bukkit.GameMode.CREATIVE
            inventoryMutation(city.id, { before ->
                val worker = before.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
                val tool = if (requested) CivsEquipment.requestedTool(before, worker)
                    ?: throw CivsRejection("resident-item-missing") else null
                if (creativeGrant) {
                    rejectIf(player.gameMode != org.bukkit.GameMode.CREATIVE || !player.isOnline, "not-authorized")
                    updated = CivsEquipment.equipRequestedTool(worker, CivResidentItem(requireNotNull(tool).name, 1))
                    CivStoragePlan(before.inventory ?: throw CivsRejection("materials-required"), emptyList())
                } else storage.prepareResidentTransfer(before, workerId, player, npcSlot, playerSlot, click, tool)
                    .also { updated = it.worker }.plan
            }, sourcePlayer = if (creativeGrant) null else player, keepViewersOpen = true) { before, inventory ->
                before.copy(workers = before.workers.map { if (it.id == workerId) requireNotNull(updated) else it },
                    inventory = inventory, revision = before.revision + 1)
            }.thenApply { Unit }
        }
    override fun addMember(player: Player, name: String): CompletableFuture<Unit> = cityAction("add_member", player, "member_name" to name) { city ->
        val member = plugin.server.getPlayerExact(name.trim()) ?: throw CivsRejection("player-offline")
        rejectIf(cities.values.any { it.autonomy == null && it.id != city.id && it.canManage(member.uniqueId) } || member.uniqueId in pendingFounders, "already-member")
        mutate(city.id) { before ->
            val added = CivsRules.addMember(before, player.uniqueId, member.uniqueId)
            val named = CivsMembership.remember(added, mapOf(player.uniqueId to player.name, member.uniqueId to member.name))
            if (added == before && named != before) named.copy(revision = before.revision + 1) else named
        }.thenApply { Unit }
    }
    override fun removeMember(player: Player, memberId: UUID): CompletableFuture<Unit> = cityAction("remove_member", player, "member" to memberId) { city ->
        mutate(city.id) { CivsMembership.remember(CivsRules.removeMember(it, player.uniqueId, memberId), emptyMap()) }.thenApply { Unit }
    }
    override fun retryConstruction(player: Player, cityId: UUID): CompletableFuture<Unit> = action("retry_construction", player, "city" to cityId) {
        afterCityAvailable(cityId) {
            requireReady(player)
            rejectIf(!player.isOnline, "player-offline")
            val city = cities[cityId] ?: throw CivsRejection("no-city")
            rejectIf(!city.canManage(player.uniqueId) && !player.hasPermission("arccivs.admin"), "not-authorized")
            val order = city.construction ?: throw CivsRejection("no-construction")
            mutate(cityId) { CivsRules.retryConstruction(it, it.owner, order.id) }.thenApply { Unit }
        }
    }
    /** Console maintenance follows the same order state machine and native revalidation as the menu. */
    fun retryConstruction(cityId: UUID): CompletableFuture<Unit> = action("retry_construction_console", null, "city" to cityId) {
        checkMain()
        rejectIf(!ready(), "not-ready")
        afterCityAvailable(cityId) {
            val city = cities[cityId] ?: throw CivsRejection("no-city")
            rejectIf(city.pendingDeletion != null, "deletion-pending")
            val order = city.construction ?: throw CivsRejection("no-construction")
            mutate(cityId) { CivsRules.retryConstruction(it, it.owner, order.id) }.thenApply { Unit }
        }
    }
    override fun reload(player: Player): CompletableFuture<Unit> = action("reload", player) {
        rejectIf(!player.hasPermission("arccivs.admin"), "not-authorized")
        rejectIf(busy.isNotEmpty() || construction.activeCount != 0 || previewLoads.isNotEmpty(), "city-busy")
        plugin.reloadConfig()
        loadCities(CivsSettings.chunkLoadingMaximum(plugin.config, plugin.logger::warning))
    }

    private fun deliver(cityId: UUID, workerId: UUID, assignment: UUID, cycle: Long, nextAt: Long, constructionId: UUID?, at: Long): CompletableFuture<Settlement> = action("deliver", null, "city" to cityId, "worker" to workerId, "cycle" to cycle) {
        afterCityAvailable(cityId) {
            val city = cities[cityId] ?: throw CivsRejection("no-city")
            val building = city.building(assignment) ?: throw CivsRejection("building-not-found")
            if (citizens.pendingStockDelivery(cityId, workerId) != null) {
                val token = CitizenToken(assignment, cycle, nextAt, constructionId)
                val pending = citizens.requirePendingStockDelivery(cityId, workerId, token)
                return@afterCityAvailable mutate(cityId) { current ->
                    citizens.requirePendingStockDelivery(cityId, workerId, token)
                    CivsAutonomousLogistics.unloadPending(current, workerId, assignment, cycle, nextAt, pending, at)
                }.thenApply { saved ->
                    CivsDiagnostics.event("autonomous_pending_stock_delivered", "city" to cityId, "worker" to workerId,
                        "pending_before" to pending, "pending_after" to saved.workers.first { it.id == workerId }.pendingStock,
                        "stock_before" to city.stock, "stock_after" to saved.stock, "revision" to saved.revision)
                    saved
                }
            }
            if (CivsRules.isBuilder(city, building)) {
                if (constructionId == null) {
                    val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
                    rejectIf(worker.assignment != assignment || worker.completedCycles != cycle || worker.nextDeliveryAt != nextAt,
                        "stale-delivery")
                    val work = roadMaintenance.plan(city, worker)?.takeIf { it.upkeep != null }
                        ?: throw CivsRejection("stale-worksite")
                    citizens.requireWorldWorkArrival(cityId, workerId, work)
                    autonomousUpkeepMutation(city, work, at)
                } else {
                    rejectIf(city.construction?.id != constructionId, "stale-delivery")
                    citizens.requireConstructionArrival(cityId, workerId, constructionId)
                    mutate(cityId) { CivsRules.deliver(it, it.owner, workerId, assignment, cycle, nextAt, at) }
                        .thenApply { saved ->
                            tasks.runLater(1) { construction.pulse() }
                            saved
                        }
                }
            } else if (building.type == BuildingType.UNIVERSITY) {
                rejectIf(constructionId != null, "stale-delivery")
                val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
                val plan = citizenPlan(city, worker) ?: throw CivsRejection("stale-delivery")
                rejectIf(plan.token.completedCycles != cycle || plan.token.nextDeliveryAt != nextAt, "stale-delivery")
                val projectId = plan.token.researchProjectId ?: throw CivsRejection("stale-research")
                if (city.autonomy != null) citizens.requireAutonomousDelivery(cityId, workerId, plan.token)
                else citizens.requireSelectedWorkDelivery(cityId, workerId, plan.token)
                mutate(cityId) { CivsResearch.complete(it, workerId, cycle, nextAt, projectId, at) }.thenApply { saved ->
                    CivsDiagnostics.event("research_session_committed", "city" to cityId, "worker" to workerId, "cycle" to cycle,
                        "project" to projectId, "research" to city.research?.project?.research,
                        "work_done" to (saved.research?.project?.workDone ?: city.research?.project?.workRequired),
                        "completed" to (saved.research?.project == null), "revision" to saved.revision)
                    saved
                }
            } else if (city.autonomy != null && building.type != BuildingType.FORESTER) {
                rejectIf(constructionId != null || city.autonomy.paused, "stale-delivery")
                val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
                rejectIf(worker.completedCycles != cycle || worker.nextDeliveryAt != nextAt, "stale-delivery")
                citizens.autonomousVisualWork(cityId, workerId)?.let { work ->
                    rejectIf(!CivsAutonomousWorksites.supports(city, building), "stale-worksite")
                    val pending = try {
                        autonomousVisualMutation(city, work)
                    } catch (failure: Exception) { worksites.invalidate(workerId); throw failure }
                    return@afterCityAvailable pending.whenCompleteSync(tasks) { saved, failure ->
                        worksites.invalidate(workerId, completedWork = work.takeIf { failure == null && saved != null })
                    }.toCompletableFuture()
                }
                if (CivsAutonomousWorksites.supports(city, building)) citizens.requireAutonomousObservedSession(cityId, workerId)
                val plan = citizens.requireAutonomousDelivery(cityId, workerId, CitizenToken(assignment, cycle, nextAt, null))
                plan.industryWork?.let { work ->
                    return@afterCityAvailable mutate(cityId) { CivsIndustry.complete(it, workerId, cycle, nextAt, work, at) }.thenApply { saved ->
                        CivsDiagnostics.event("autonomous_industry_committed", "city" to cityId, "worker" to workerId,
                            "cycle" to cycle, "recipe" to work.recipe.id, "inputs_stock" to work.recipe.stock,
                            "inputs_products" to work.recipe.products, "product" to work.recipe.product,
                            "amount" to work.recipe.amount, "products" to saved.autonomy?.products, "revision" to saved.revision)
                        saved
                    }
                }
                mutate(cityId) { CivsRules.deliver(it, it.owner, workerId, assignment, cycle, nextAt, at) }.thenApply { saved ->
                    CivsDiagnostics.event("autonomous_resource_delivered", "city" to cityId, "worker" to workerId,
                        "cycle" to cycle, "resource" to CivsRules.resource(building.type),
                        "amount" to CivsRules.deliveryAmount(building), "stock" to saved.stock,
                        "pending_stock" to saved.workers.first { it.id == workerId }.pendingStock, "revision" to saved.revision)
                    saved
                }
            } else if (building.type in CivsWorksites.TYPES) {
                val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
                val work = worksites.plan(city, worker) ?: throw CivsRejection("stale-worksite")
                rejectIf(work.cycle != cycle || work.buildingId != assignment, "stale-delivery")
                if (citizenWorkFocus(work) == null) citizens.requireStorageArrival(cityId, workerId, assignment)
                else citizens.requireWorldWorkArrival(cityId, workerId, work)
                try { worksites.validate(city, work) } catch (failure: Exception) { worksites.invalidate(workerId); throw failure }
                var completedWork: Settlement? = null
                (if (city.autonomy != null) autonomousForestryMutation(city, work, at) else inventoryMutation(cityId, { current ->
                    worksites.validate(current, work)
                    if (citizenWorkFocus(work) == null) citizens.requireStorageArrival(cityId, workerId, assignment)
                    else citizens.requireWorldWorkArrival(cityId, workerId, work)
                    val currentWorker = current.workers.first { it.id == workerId }
                    completedWork = try { CivsRules.finishWorldWork(current, work, at) } catch (failure: CivsRejection) {
                        if (failure.reason == "resident-inventory-full") throw CivsRejection("storage-full") else throw failure
                    }
                    storage.prepareWork(current, assignment, work.storageInputs(currentWorker), emptyMap(), work.changes, work)
                }, keepViewersOpen = true) { _, inventory ->
                    requireNotNull(completedWork).copy(inventory = inventory)
                }).whenCompleteSync(tasks) { saved, failure ->
                    if (failure == null && saved != null) {
                        citizens.terrainChanged(city.worldId, work.changes.map { it.position })
                        val completed = saved.workers.first { it.id == workerId }
                        CivsDiagnostics.event("world_work_committed", "city" to cityId, "worker" to workerId,
                            "building" to assignment, "cycle" to cycle, "revision" to saved.revision,
                            "status" to work.status, "duration_ms" to work.durationMillis,
                            "point" to work.focus, "blocks" to work.changes.size, "inputs" to work.inputs,
                            "carried_before" to work.inputs.mapValues { (material, _) -> CivsEquipment.plainCount(worker, material) },
                            "carried_after" to work.inputs.mapValues { (material, _) -> CivsEquipment.plainCount(completed, material) },
                            "storage_inputs" to if (city.autonomy == null) work.storageInputs(worker) else emptyMap<String, Long>(),
                            "autonomous_wood_delta" to if (city.autonomy != null) saved.stock.wood - city.stock.wood else null,
                            "seed_deferred" to work.forestry?.job?.seedDeferred,
                            "initial_seed_kit" to (city.autonomy != null && work.forestry == null && !CivsForestry.care(work) && city.stock.wood == 0L),
                            "outputs" to work.outputs,
                            "mining_step" to work.miningStep)
                    }
                    worksites.invalidate(workerId, completedWork = work.takeIf { failure == null && saved != null })
                }.toCompletableFuture()
            } else {
                rejectIf(constructionId != null, "stale-delivery")
                // Automatic carpentry must carry its immutable accepted batch through the actor callback.
                rejectIf(building.type == BuildingType.SAWMILL || craftPlan(city, city.workers.first { it.id == workerId }) != null, "stale-delivery")
                if (building.type in setOf(BuildingType.SMELTERY, BuildingType.CRUSHER) ||
                    building.type == BuildingType.BLACKSMITH && CivsProduction.material(building) in setOf("BUCKET", "SHEARS")) {
                    val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
                    val plan = citizenPlan(city, worker) ?: throw CivsRejection("stale-delivery")
                    rejectIf(plan.token.completedCycles != cycle || plan.token.nextDeliveryAt != nextAt, "stale-delivery")
                    citizens.requireSelectedWorkDelivery(cityId, workerId, plan.token)
                }
                citizens.requireStorageArrival(cityId, workerId, assignment)
                if (city.inventory == null) return@afterCityAvailable mutate(cityId) { CivsRules.deliver(it, it.owner, workerId, assignment, cycle, nextAt, at) }
                // Validate the observed trip before reserving space; repeated callbacks cannot mint again.
                CivsRules.deliver(city, city.owner, workerId, assignment, cycle, nextAt, at, outputHandled = true)
                inventoryMutation(cityId, { storage.prepareProduction(it, it.building(assignment)
                    ?: throw CivsRejection("building-not-found"), it.workers.first { worker -> worker.id == workerId }) }, keepViewersOpen = true) { before, inventory ->
                    CivsRules.deliver(before, before.owner, workerId, assignment, cycle, nextAt, at, outputHandled = true).copy(inventory = inventory)
                }
            }
        }
    }

    /** Auto forestry uses the same native journal while retaining its nontransferable stock contract. */
    private fun autonomousForestryMutation(city: Settlement, work: CivWorldWork, at: Long): CompletableFuture<Settlement> {
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val tx = try {
            worksites.validate(city, work)
            val worker = city.workers.single { it.id == work.workerId }
            val after = CivsRules.finishWorldWork(city, work, at)
            val receipt = CivForestryReceipt(worker, city.stock, city.forestryJobs?.get(work.buildingId.toString()),
                city.forestryJobs == null, work, at, city.forestrySeeded)
            CivInventoryTransaction(id, city.id, city.revision, after, emptyList(), worldChanges = work.changes,
                forestry = receipt).also { storage.seal(it, keepViewersOpen = true) }
        } catch (failure: Exception) { storage.unlock(id); throw failure }
        return inventoryJournal.submit(tx)
    }

    /** Scenery has no item or economic debit. A stale cursor rediscovers the actual blocks after restart. */
    private fun autonomousVisualMutation(city: Settlement, work: CivWorldWork): CompletableFuture<Settlement> =
        mutate(city.id) { current ->
            citizens.requireWorldWorkArrival(current.id, work.workerId, work)
            val next = CivsRules.finishAutonomousVisual(current, work)
            worksites.applyAutonomousVisual(current, work)
            citizens.terrainChanged(current.worldId, work.changes.map { it.position })
            next
        }.thenApply { saved ->
            physics.enqueue(saved.worldId, work.changes)
            CivsDiagnostics.event("autonomous_world_work_committed", "city" to saved.id,
                "worker" to work.workerId, "cycle" to work.cycle, "revision" to saved.revision,
                "status" to work.status, "blocks" to work.changes.size, "mining_step" to work.miningStep,
                "native_outputs" to 0, "economic_credit" to false)
            saved
        }

    private fun autonomousUpkeepMutation(city: Settlement, work: CivWorldWork, at: Long): CompletableFuture<Settlement> {
        rejectIf(!CivsRoadMaintenance.eligible(city), "stale-worksite")
        rejectIf(city.stock.stone <= (CivsAutonomy.nextGoal(city, blueprints::maximumLevel)?.cost?.stone ?: 0L), "materials-missing")
        val worker = city.workers.firstOrNull { it.id == work.workerId } ?: throw CivsRejection("worker-not-found")
        rejectIf(worker.completedCycles != work.cycle || worker.assignment != work.buildingId, "stale-delivery")
        rejectIf(at < worker.nextDeliveryAt, "delivery-too-early")
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val tx = try {
            citizens.requireWorldWorkArrival(city.id, work.workerId, work)
            rejectIf(!world.roadUpkeepPending(city, work), "stale-worksite")
            world.validateRoadUpkeep(city, work)
            val after = CivsRoadMaintenance.complete(city, work, at)
            CivInventoryTransaction(id, city.id, city.revision, after, emptyList(), worldChanges = work.changes,
                upkeep = CivRoadUpkeepReceipt(worker, city.stock, work, at)).also { storage.seal(it, keepViewersOpen = true) }
        } catch (failure: Exception) { storage.unlock(id); throw failure }
        return inventoryJournal.submit(tx)
    }

    /** The same native authority fence covers fresh application and restart recovery. */
    private fun validateUpkeepNative(tx: CivInventoryTransaction): CivInventoryApplyResult? {
        val receipt = tx.upkeep ?: return null
        val current = cities[tx.cityId] ?: return CivInventoryApplyResult.Conflict(mapOf("reason" to "city-unavailable"))
        return try {
            world.validateRoadUpkeep(current, receipt.work)
            null
        } catch (failure: CivsRejection) {
            val details = failure.values + ("reason" to failure.reason)
            if (failure.reason in setOf("world-unavailable", "world-unloaded", "chunk-unloaded", "chunks-unloaded"))
                CivInventoryApplyResult.Retry(details) else CivInventoryApplyResult.Conflict(details)
        }
    }

    /** Ordinary mine receipts retain their captured progress, but always recheck current native authority. */
    private fun validateMineNative(tx: CivInventoryTransaction): CivInventoryApplyResult? {
        val receipt = tx.mine ?: return null
        return try {
            worksites.validateMineReceiptNative(receipt.before(tx.after), receipt.work)
            null
        } catch (failure: CivsRejection) {
            val details = failure.values + ("reason" to failure.reason)
            if (failure.reason in setOf("world-unavailable", "world-unloaded", "chunk-unloaded", "chunks-unloaded"))
                CivInventoryApplyResult.Retry(details) else CivInventoryApplyResult.Conflict(details)
        }
    }

    internal fun craftPlan(city: Settlement, worker: CivWorker): CivCraftJob? =
        supply.craftJobs(city.id)[worker.id]?.takeIf { CivsCarpentry.valid(city, worker, it) }

    private fun craftObservationCurrent(city: Settlement): Boolean {
        val snapshot = supply.craftObservation(city.id) ?: return false
        val observation = snapshot.inputKey as? CivSupplyObservation ?: return false
        return observation.inputs == civSupplyInputs(city) &&
            storage.supplyCaptureCurrent(city, observation.capture, snapshot.stores.keys)
    }

    private fun requireCraftJob(city: Settlement, worker: CivWorker, id: UUID?): CivCraftJob {
        val job = craftPlan(city, worker)?.takeIf { it.id == id } ?: throw CivsRejection("stale-delivery")
        rejectIf(!craftObservationCurrent(city), "stale-delivery")
        return job
    }

    private fun craftRequired(city: Settlement, buildingId: UUID): Map<String, Long> {
        val required = linkedMapOf<String, Long>()
        city.workers.filter { it.assignment == buildingId }.forEach { worker ->
            craftPlan(city, worker)?.inputs?.let { CivsEquipment.storageInputs(worker, it) }?.forEach { (material, count) ->
                required.merge(material, count, Math::addExact)
            }
        }
        return required
    }

    private fun craftRequests(city: Settlement, stock: (UUID) -> Map<String, Long>): List<CivSupplyRequest> =
        city.buildings.filter { it.type in CivsCarpentry.workshops }.flatMap { building ->
            craftRequired(city, building.id).mapNotNull { (material, count) ->
                val needed = (count - (stock(building.id)[material] ?: 0L)).coerceAtLeast(0)
                val incoming = city.workers.mapNotNull(CivWorker::cargo).filter {
                    it.material == material && CivsLogistics.destination(city, it)?.id == building.id
                }.sumOf(CivCargo::amount)
                if (needed > 0 || incoming > 0) CivSupplyRequest(building.id, material, needed, incoming) else null
            }
        }

    private fun deliverCraft(cityId: UUID, workerId: UUID, assignment: UUID, cycle: Long, nextAt: Long,
        jobId: UUID, at: Long): CompletableFuture<Settlement> = action("craft", null,
        "city" to cityId, "worker" to workerId, "job" to jobId, "cycle" to cycle) {
        afterCityAvailable(cityId) {
            var committedJob: CivCraftJob? = null
            var completed: Settlement? = null
            inventoryMutation(cityId, { current ->
                val worker = current.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
                val job = requireCraftJob(current, worker, jobId)
                rejectIf(job.buildingId != assignment || job.cycle != cycle, "stale-delivery")
                citizens.requireStorageArrival(cityId, workerId, assignment)
                val equipment = if (requireNotNull(CivsCarpentry.recipe(job)).stripping) CivsEquipment.strippedWithAxe(worker) else null
                completed = CivsRules.deliver(current, current.owner, workerId, assignment, cycle, nextAt, at,
                    outputHandled = true, craftJob = job, equipmentAfter = equipment)
                committedJob = job
                storage.prepareProduction(current, requireNotNull(current.building(assignment)), worker, job)
            }, keepViewersOpen = true) { _, inventory -> requireNotNull(completed).copy(inventory = inventory) }
                .whenCompleteSync(tasks) { saved, failure ->
                    if (failure == null && saved != null) {
                        supply.releaseCraft(cityId, workerId, jobId)
                        val job = requireNotNull(committedJob)
                        CivsDiagnostics.event("craft_committed", "city" to cityId, "worker" to workerId,
                            "job" to jobId, "recipe" to job.recipeId, "recipe_version" to job.version,
                            "cycle" to cycle, "inputs" to job.inputs, "material" to job.material, "amount" to job.amount,
                            "demand" to job.demandMaterial, "target" to job.targetBuildingId, "revision" to saved.revision)
                    }
                }.toCompletableFuture()
        }
    }

    private fun localStocks(city: Settlement): (UUID) -> Map<String, Long> {
        val cached = mutableMapOf<UUID, Map<String, Long>>()
        return { id -> cached.getOrPut(id) {
            try { storage.buildingAvailable(city, id) } catch (failure: CivsRejection) {
                if (failure.reason in setOf("storage-unavailable", "storage-container-invalid", "building-not-found")) emptyMap()
                else throw failure
            }
        } }
    }

    /** Local stock permits the trip to storage; work still requires the subsequent physical pickup. */
    internal fun productionReady(city: Settlement, worker: CivWorker): Boolean {
        val building = worker.assignment?.let(city::building) ?: return true
        if (building.type == BuildingType.UNIVERSITY) return CivsRules.productionAvailable(city, building)
        if (city.autonomy != null) return (citizens.acceptedIndustryWork(city, worker) ?: CivsIndustry.autonomousWork(city, building))
            ?.let { CivsIndustry.inputsReady(city, it) && CivsIndustry.room(city, it) }
            ?: (building.type !in CivsIndustry.processorTypes)
        if (building.type in CivsIndustry.processorTypes && CivsProduction.material(building) !in CivsProduction.options(city, building)) return false
        if (CivsRules.isBuilder(city, building) || building.type in CivsWorksites.TYPES) return true
        val inputs = craftPlan(city, worker)?.inputs ?: if (building.type == BuildingType.SAWMILL) return false
            else CivsProduction.inputs(city, building)
        return CivsEquipment.inputsReady(worker, inputs, localStocks(city)(building.id))
    }

    internal fun workInputsReady(city: Settlement, worker: CivWorker): Boolean {
        val building = worker.assignment?.let(city::building) ?: return true
        if (building.type == BuildingType.UNIVERSITY) return CivsRules.productionAvailable(city, building)
        if (city.autonomy != null) return (citizens.acceptedIndustryWork(city, worker) ?: CivsIndustry.autonomousWork(city, building))
            ?.let { CivsIndustry.inputsReady(city, it) }
            ?: (building.type !in CivsIndustry.processorTypes)
        if (CivsRules.isBuilder(city, building)) {
            val order = city.construction ?: return true
            if (order.payment == null || order.payment.reservedBatch != null) return true
            val required = order.payment.awaiting ?: construction.supplyNeed(city)
            // The first captured row may only remove blocks; its exact debit is prepared before placement.
            return required == null || CivsRules.constructionStorageInputs(city, order.buildingId, required).isEmpty()
        }
        if (building.type in CivsWorksites.TYPES) return true
        val inputs = craftPlan(city, worker)?.inputs ?: if (building.type == BuildingType.SAWMILL) return false
            else CivsProduction.inputs(city, building)
        return CivsEquipment.inputsReady(worker, inputs, emptyMap())
    }

    internal fun residentSupplySource(city: Settlement, worker: CivWorker): UUID? {
        // Physical food uses its exact reachable native container rather than a whole-building debit.
        if (city.inventory?.mode != CivStorageMode.VIRTUAL) return null
        if (city.inventory == null || CivsResidents.hunger(worker) > 14 || CivsEquipment.usableCount(worker, "BREAD") > 0 ||
            CivsEquipment.supplyRoom(worker, Material.BREAD) == 0) return null
        return city.buildings.sortedBy { if (CivsRules.staffedTavern(city, it)) 0 else 1 }.firstOrNull {
            (it.type == BuildingType.TOWN_HALL || CivsRules.staffedTavern(city, it)) && it.control != null &&
            (localStocks(city)(it.id)["BREAD"] ?: 0L) > 0L }?.id
    }

    private fun citizensFeetForFood(workerId: UUID): org.bukkit.Location? = citizens.residentFeet(workerId)

    internal fun pickupResidentFood(cityId: UUID, workerId: UUID, source: CivResidentFoodSource): CompletableFuture<Settlement> =
        action("resident_food_pickup", null, "city" to cityId, "worker" to workerId, "building" to source.buildingId) {
            collectedMutation(cityId, workerId) { city, feet -> storage.prepareFoodPickup(city, workerId, source, feet) }
        }

    internal fun materializeResidentFood(cityId: UUID, workerId: UUID): CompletableFuture<Settlement> =
        action("resident_food_materialize", null, "city" to cityId, "worker" to workerId) {
            collectedMutation(cityId, workerId, materializingFood = true) { city, feet ->
                storage.prepareFoodMaterialization(city, workerId, feet)
            }
        }

    private var nextResidentFoodAt = 0L
    private var residentFoodCursor = 0
    private fun pulseResidentFood() {
        if (now() < nextResidentFoodAt) return
        val candidates = cities.values.filter { it.autonomy?.paused == false && it.stock.food > 0 &&
            it.pendingDeletion == null && collectionAvailable(it.id) && settlementActive(it) && it.workers.any { worker ->
                citizens.residentFeet(worker.id) != null
            } }
        if (candidates.isEmpty()) return
        val city = candidates[Math.floorMod(residentFoodCursor++, candidates.size)]
        val worker = city.workers.firstOrNull { citizens.residentFeet(it.id) != null } ?: return
        nextResidentFoodAt = now() + 5_000
        runCatching { materializeResidentFood(city.id, worker.id) }.onSuccess { future ->
            future.whenComplete { _, failure -> if (failure != null && CivsDiagnostics.rejection(failure)?.reason !in
                    setOf("city-busy", "resident-food-missing", "worker-too-far")) log("materialize resident food ${city.id}", failure, city.id) }
        }.onFailure { if (CivsDiagnostics.rejection(it)?.reason !in setOf("city-busy", "resident-food-missing", "worker-too-far"))
            log("materialize resident food ${city.id}", it, city.id) }
    }

    internal fun prepareResidentSupplies(cityId: UUID, workerId: UUID, buildingId: UUID, craftJobId: UUID? = null): CompletableFuture<Settlement> =
        action("resident_supplies", null, "city" to cityId, "worker" to workerId, "building" to buildingId) {
            afterCityAvailable(cityId) {
                var supplied: CivWorker? = null
                var supplyContext = emptyArray<Pair<String, Any?>>()
                var carriedBefore = emptyMap<String, Long>()
                var required: Map<String, Long>? = null
                var reportSupply = false
                var supplyChanged = false
                inventoryMutation(cityId, { city ->
                    val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
                    val source = city.building(buildingId) ?: throw CivsRejection("building-not-found")
                    citizens.requireStorageArrival(cityId, workerId, buildingId)
                    carriedBefore = CivsEquipment.available(worker, emptyMap())
                    val food = (source.type == BuildingType.TOWN_HALL || CivsRules.staffedTavern(city, source)) && CivsResidents.hunger(worker) <= 14 &&
                        CivsEquipment.usableCount(worker, "BREAD") == 0
                    val builder = !food && CivsRules.isBuilder(city, source)
                    reportSupply = worker.assignment?.let(city::building)?.let { CivsRules.isBuilder(city, it) } == true
                    val order = city.construction.takeIf { reportSupply }
                    supplyContext = arrayOf("city" to cityId, "worker" to workerId, "source" to buildingId,
                        "target" to source.control, "order" to order?.id, "batch" to order?.workDone,
                        "construction_phase" to order?.phase, "reserved_batch" to order?.payment?.reservedBatch,
                        "reason" to if (food) "food" else if (order?.payment?.awaiting != null) "next-required-type" else "work-materials")
                    val prepared = if (food) {
                        val count = minOf(4L, storage.buildingAvailable(city, buildingId)["BREAD"] ?: 0L,
                            CivsEquipment.supplyRoom(worker, Material.BREAD).toLong())
                        rejectIf(count == 0L, "resident-food-missing")
                        required = mapOf("BREAD" to count)
                        storage.prepareResidentPickup(city, workerId, buildingId, mapOf("BREAD" to count))
                    } else {
                        rejectIf(worker.assignment != buildingId || worker.cargo != null || CivsResidents.isChild(worker), "invalid-assignment")
                        if (builder) {
                            required = city.construction?.payment?.awaiting ?: construction.supplyNeed(city)
                            storage.prepareBuilderPickup(city, workerId, required.orEmpty())
                        } else {
                            rejectIf(!CivsRules.productionAvailable(city, source) || source.type in CivsWorksites.TYPES, "invalid-assignment")
                            required = if (craftJobId != null || source.type == BuildingType.SAWMILL) requireCraftJob(city, worker, craftJobId).inputs
                                else CivsProduction.inputs(city, source)
                            storage.prepareResidentPickup(city, workerId, buildingId,
                                CivsEquipment.storageInputs(worker, requireNotNull(required)))
                        }
                    }
                    supplied = prepared.worker
                    supplyChanged = prepared.worker != worker || prepared.plan.changes.isNotEmpty() || prepared.plan.inventoryAfter != city.inventory
                    if (supplyChanged) prepared.plan else null
                }, keepViewersOpen = true) { before, inventory ->
                    val worker = requireNotNull(supplied)
                    before.copy(workers = before.workers.map { if (it.id == workerId) worker else it },
                        inventory = inventory, revision = Math.addExact(before.revision, 1))
                }.thenApply { saved ->
                    construction.materialsChanged()
                    if (!reportSupply) return@thenApply saved
                    val worker = requireNotNull(supplied)
                    val carriedAfter = CivsEquipment.available(worker, emptyMap())
                    val picked = carriedAfter.mapValues { (material, count) -> count - carriedBefore.getOrDefault(material, 0) }.filterValues { it > 0 }
                    val returned = carriedBefore.mapValues { (material, count) -> count - carriedAfter.getOrDefault(material, 0) }.filterValues { it > 0 }
                    val fields = supplyContext + arrayOf("outcome" to if (supplyChanged) "committed" else "no-op",
                        "required_known" to (required != null), "required" to required,
                        "missing_before" to required?.mapValues { (material, count) -> (count - carriedBefore.getOrDefault(material, 0)).coerceAtLeast(0) }?.filterValues { it > 0 },
                        "missing_after" to required?.let { CivsEquipment.storageInputs(worker, it) },
                        "carried_before" to carriedBefore, "carried_after" to carriedAfter,
                        "picked" to picked, "returned" to returned, "amount" to picked.values.sum())
                    val noOp = !supplyChanged
                    val signature = if (noOp) CivsDiagnostics.line("resident_supplies_result", *fields) else null
                    if (!noOp || supplyNoopDiagnostics.put(workerId, requireNotNull(signature)) != signature)
                        CivsDiagnostics.event("resident_supplies_result", *fields, "revision" to saved.revision)
                    if (!noOp) supplyNoopDiagnostics.remove(workerId)
                    // Only coalesce unchanged no-ops; real committed transfers are always reported.
                    if (supplyNoopDiagnostics.size > 128) supplyNoopDiagnostics.remove(supplyNoopDiagnostics.keys.first())
                    saved
                }
            }
        }

    internal fun courierPlan(city: Settlement, worker: CivWorker): CivCourierPlan? {
        worker.cargo?.let { cargo ->
            val target = CivsLogistics.destination(city, cargo) ?: return null
            return CivCourierPlan(cargo, target.id, target.entrance, true)
        }
        return supply.plan(city.id, worker.id)?.takeIf { plan ->
            worker.assignment == plan.cargo.warehouseId &&
                CivsLogistics.shipmentId(city.id, worker.id, worker.completedCycles, plan.cargo.sourceBuildingId,
                    plan.cargo.targetBuildingId, plan.cargo.material, plan.cargo.sourceWorkerId) == plan.cargo.id
        }
    }

    private fun pulseSupply() {
        val relevant = cities.values.filter { city -> city.pendingDeletion == null &&
            (city.inventory != null || city.autonomy?.paused == false) &&
            city.id !in unhealthy && city.workers.any { worker ->
                worker.cargo != null || worker.assignment?.let(city::building)?.type.let {
                    it == BuildingType.WAREHOUSE || it in CivsCarpentry.workshops
                }
            } }
        val ids = relevant.mapTo(hashSetOf(), Settlement::id)
        // An unknown receipt outcome keeps its accepted batch reserved until the existing
        // recovery owner publishes or definitively rejects that receipt.
        ids += supply.cityIds().filter { inventoryJournal.pending(it) }
        (supply.cityIds() - ids).forEach { supply.remove(it); supplyObservations.remove(it) }
        for (city in relevant) {
            val revision = if (city.autonomy != null) city.revision else storage.supplyRevision(city.id)
            val previous = supplyObservations[city.id]
            if (previous == null || revision != previous.first || now() - previous.second >= 5_000L) {
                supply.request(city.id)
                supplyObservations[city.id] = revision to now()
            }
        }
    }

    private fun captureSupply(cityId: UUID, retained: Map<UUID, CivCourierPlan>): () -> CivSupplySnapshot? {
        val city = cities[cityId]?.takeIf { ready() && cityId !in unhealthy && it.pendingDeletion == null &&
            (it.inventory != null || it.autonomy?.paused == false) }
            ?: throw CivsRejection("storage-unavailable")
        if (cityId in busy) throw CivsRejection("city-busy")
        if (city.autonomy != null) return {
            if (!ready() || cityId in unhealthy) throw CivsRejection("storage-unavailable")
            if (cityId in busy) throw CivsRejection("city-busy")
            CivsAutonomousLogistics.snapshot(city, retained, { citizens.courierPosition(cityId, it) },
                citizens.courierSupplySources(cityId, retained.values.mapNotNullTo(hashSetOf()) { it.cargo.sourceWorkerId }),
                { citizens.courierUnavailable(cityId, it) })
        }
        val capture = CivsSupplyPlanning(city, storage.startSupplyCapture(city), retained,
            { citizens.courierPosition(cityId, it) }, { citizens.courierSupplySources(cityId,
                retained.values.filter { !it.carrying }.mapNotNullTo(hashSetOf()) { it.cargo.sourceWorkerId }) },
            { citizens.courierUnavailable(cityId, it) }, supply.craftJobs(cityId), residentFoods)
        return {
            if (!ready() || cityId in unhealthy) throw CivsRejection("storage-unavailable")
            if (cityId in busy) throw CivsRejection("city-busy")
            capture.step()
        }
    }

    private fun validateSupply(snapshot: CivSupplySnapshot, plans: Map<UUID, CivCourierPlan>): Boolean {
        val city = cities[snapshot.cityId]?.takeIf { ready() && it.id !in busy && it.id !in unhealthy } ?: return false
        if (snapshot.autonomousStock) {
            val observation = snapshot.inputKey as? CivAutonomousSupplyObservation ?: return false
            return city.autonomy?.paused == false && observation.inputs == civSupplyInputs(city) &&
                plans.all { (workerId, plan) -> plan.carrying ||
                    !citizens.courierUnavailable(city.id, workerId) && citizens.courierSourceCurrent(city.id, plan.cargo) }
        }
        val observation = snapshot.inputKey as? CivSupplyObservation ?: return false
        if (observation.inputs != civSupplyInputs(city)) return false
        if (plans.any { (workerId, plan) -> !plan.carrying && citizens.courierUnavailable(city.id, workerId) }) return false
        if (plans.values.any { !it.carrying && it.cargo.sourceWorkerId != null &&
                !citizens.courierSourceCurrent(city.id, it.cargo) }) return false
        val buildings = if (snapshot.craftWorkers.isNotEmpty()) snapshot.stores.keys else plans.values.flatMap { plan ->
            if (plan.carrying || plan.cargo.sourceWorkerId != null) listOf(plan.destinationBuildingId)
            else listOf(plan.cargo.sourceBuildingId, plan.destinationBuildingId)
        }.toSet()
        return storage.supplyCaptureCurrent(city, observation.capture, buildings)
    }

    internal fun pickupCargo(cityId: UUID, workerId: UUID, requested: CivCargo): CompletableFuture<Settlement> =
        action("cargo_pickup", null, "city" to cityId, "worker" to workerId, "shipment" to requested.id) {
            afterCityAvailable(cityId) {
                val city = cities[cityId] ?: throw CivsRejection("no-city")
                val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
                worker.cargo?.let { cargo ->
                    rejectIf(cargo.id != requested.id, "stale-delivery")
                    return@afterCityAvailable CompletableFuture.completedFuture(city)
                }
                val accepted = courierPlan(city, worker)?.takeUnless { it.carrying }?.cargo ?: throw CivsRejection("stale-delivery")
                rejectIf(accepted.id != requested.id || accepted.sourceEntrance != requested.sourceEntrance, "stale-delivery")
                accepted.stockResource?.let { resource ->
                    val source = city.workers.firstOrNull { it.id == accepted.sourceWorkerId } ?: throw CivsRejection("stale-delivery")
                    val amount = minOf(accepted.amount, requested.amount, source.pendingStock?.get(resource) ?: 0L)
                    rejectIf(amount <= 0L, "stale-delivery")
                    val cargo = accepted.copy(amount = amount)
                    citizens.requireCargoPickupArrival(cityId, workerId, cargo)
                    return@afterCityAvailable mutate(cityId) { current ->
                        citizens.requireCargoPickupArrival(cityId, workerId, cargo)
                        CivsLogistics.pickup(current, workerId, cargo, foods = residentFoods)
                    }.thenApply { saved ->
                        supply.release(cityId, workerId)
                        CivsDiagnostics.event("cargo_pickup_committed", "city" to cityId, "worker" to workerId,
                            "shipment" to cargo.id, "stock_resource" to resource, "amount" to amount,
                            "source_worker" to source.id, "source_before" to source.pendingStock?.get(resource),
                            "source_after" to (saved.workers.first { it.id == source.id }.pendingStock?.get(resource) ?: 0L),
                            "carried_after" to saved.workers.first { it.id == workerId }.cargo?.amount, "revision" to saved.revision)
                        saved
                    }
                }
                val available = if (accepted.sourceWorkerId != null) {
                    val source = city.workers.firstOrNull { it.id == accepted.sourceWorkerId } ?: throw CivsRejection("stale-delivery")
                    CivsLogistics.workerExcess(city, source, craftPlan(city, source)?.inputs.orEmpty(), residentFoods)[accepted.material] ?: 0L
                } else {
                    val count = storage.buildingAvailable(city, accepted.sourceBuildingId)[accepted.material] ?: 0L
                    val reserve = if (accepted.sourceBuildingId == accepted.warehouseId) 0L else
                        (city.building(accepted.sourceBuildingId)?.let { CivsLogistics.desiredStock(city, it)[accepted.material] } ?: 0L) +
                            (craftRequired(city, accepted.sourceBuildingId)[accepted.material] ?: 0L)
                    (count - reserve).coerceAtLeast(0)
                }
                val needed = if (accepted.sourceBuildingId != accepted.warehouseId) CivsLogistics.MAX_CARGO else {
                    val local = localStocks(city)
                    (CivsLogistics.requests(city, local) + craftRequests(city, local)).firstOrNull { request ->
                        request.material == accepted.material && (request.buildingId == accepted.targetBuildingId ||
                            request.construction && city.construction?.let { order ->
                                CivsRules.constructionStores(city, order.buildingId).any { it.id == accepted.targetBuildingId }
                            } == true)
                    }?.let { (it.needed - it.inTransit).coerceAtLeast(0) } ?: 0L
                }
                val amount = minOf(accepted.amount, requested.amount, available, needed,
                    CivsEquipment.supplyRoom(worker, Material.valueOf(accepted.material)).toLong(),
                    storage.cargoRoom(city, accepted.targetBuildingId, accepted.material))
                rejectIf(amount <= 0L, "stale-delivery")
                val cargo = accepted.copy(amount = amount)
                rejectIf(!CivsLogistics.validCargo(city, cargo), "stale-delivery")
                if (cargo.sourceWorkerId != null) citizens.requireCargoPickupArrival(cityId, workerId, cargo)
                else citizens.requireStorageArrival(cityId, workerId, cargo.sourceBuildingId)
                val sourceBefore = cargo.sourceWorkerId?.let { sourceId -> city.workers.first { it.id == sourceId } }
                    ?.let { CivsEquipment.plainCount(it, cargo.material) }
                val carriedBefore = CivsEquipment.plainCount(worker, cargo.material)
                inventoryMutation(cityId, { storage.prepareCargoPickup(it, cargo) }, keepViewersOpen = true) { before, inventory ->
                    CivsLogistics.pickup(before, workerId, cargo, supply.craftJobs(cityId).mapValues { it.value.inputs }, residentFoods).copy(inventory = inventory)
                }.thenApply { saved ->
                    CivsDiagnostics.event("cargo_pickup_committed", "city" to cityId, "worker" to workerId,
                        "shipment" to cargo.id, "material" to cargo.material, "amount" to cargo.amount,
                        "source" to cargo.sourceBuildingId, "source_worker" to cargo.sourceWorkerId,
                        "source_before" to sourceBefore, "source_after" to cargo.sourceWorkerId?.let { sourceId ->
                            saved.workers.firstOrNull { it.id == sourceId }?.let { CivsEquipment.plainCount(it, cargo.material) }
                        }, "carried_before" to carriedBefore,
                        "carried_after" to CivsEquipment.plainCount(saved.workers.first { it.id == workerId }, cargo.material),
                        "revision" to saved.revision)
                    supply.release(cityId, workerId)
                    saved
                }
            }
        }.whenComplete { _, failure ->
            val current = if (!closed) supply.plan(cityId, workerId)?.cargo else null
            if (!closed && failure != null && cityId !in unhealthy && current?.id == requested.id &&
                current.sourceEntrance == requested.sourceEntrance &&
                CivsDiagnostics.rejection(failure)?.reason in
                setOf("stale-delivery", "materials-missing", "storage-full", "route-unreachable", "building-not-found", "control-missing"))
                supply.release(cityId, workerId, failed = true)
        }

    internal fun dropoffCargo(cityId: UUID, workerId: UUID, requested: CivCargo, destinationId: UUID): CompletableFuture<Settlement> =
        action("cargo_dropoff", null, "city" to cityId, "worker" to workerId, "shipment" to requested.id) {
            afterCityAvailable(cityId) {
                val city = cities[cityId] ?: throw CivsRejection("no-city")
                val cargo = city.workers.firstOrNull { it.id == workerId }?.cargo ?: throw CivsRejection("stale-delivery")
                rejectIf(cargo.id != requested.id || CivsLogistics.destination(city, cargo)?.id != destinationId, "stale-delivery")
                citizens.requireStorageArrival(cityId, workerId, destinationId)
                cargo.stockResource?.let { resource ->
                    return@afterCityAvailable mutate(cityId) { current ->
                        citizens.requireStorageArrival(cityId, workerId, destinationId)
                        rejectIf(CivsLogistics.destination(current, cargo)?.id != destinationId, "stale-delivery")
                        CivsLogistics.unload(current, workerId, cargo.id, now())
                    }.thenApply { saved ->
                        supply.release(cityId, workerId)
                        construction.materialsChanged()
                        CivsDiagnostics.event("cargo_dropoff_committed", "city" to cityId, "worker" to workerId,
                            "shipment" to cargo.id, "stock_resource" to resource, "amount" to cargo.amount,
                            "source_worker" to cargo.sourceWorkerId, "destination" to destinationId,
                            "stock_before" to city.stock[resource], "stock_after" to saved.stock[resource], "revision" to saved.revision)
                        saved
                    }
                }
                val carriedBefore = CivsEquipment.plainCount(city.workers.first { it.id == workerId }, cargo.material)
                inventoryMutation(cityId, { storage.prepareCargoDropoff(it, cargo, destinationId) }, keepViewersOpen = true) { before, inventory ->
                    CivsLogistics.unload(before, workerId, cargo.id, now()).copy(inventory = inventory)
                }.whenComplete { saved, failure ->
                    if (failure == null) {
                        supply.release(cityId, workerId)
                        construction.materialsChanged()
                        CivsDiagnostics.event("cargo_dropoff_committed", "city" to cityId, "worker" to workerId,
                            "shipment" to cargo.id, "material" to cargo.material, "amount" to cargo.amount,
                            "source_worker" to cargo.sourceWorkerId, "carried_before" to carriedBefore,
                            "carried_after" to CivsEquipment.plainCount(saved.workers.first { it.id == workerId }, cargo.material),
                            "destination" to destinationId, "revision" to saved.revision)
                    }
                }
            }
        }

    internal fun collectionAvailable(cityId: UUID): Boolean {
        val city = cities[cityId] ?: return false
        return ready() && city.pendingDeletion == null && city.id !in busy && city.id !in unhealthy &&
            inventoryJournal.collectionAvailable(city.worldId)
    }

    private fun collectionReserve(city: Settlement, worker: CivWorker): Map<String, Long> =
        CivsLogistics.workerReserve(city, worker, craftPlan(city, worker)?.inputs.orEmpty(), residentFoods)

    internal fun collectedHome(city: Settlement, worker: CivWorker): CivCollectedHome? {
        if (!collectionAvailable(city.id) || worker.hasCollectedItems != true) return null
        val home = civsCollectionHome(city, worker) ?: return null
        val reserve = collectionReserve(city, worker)
        val surplus = CivsEquipment.homeSurplus(worker, reserve).isNotEmpty()
        return CivCollectedHome(home.id, if (surplus) storage.homeDestination(city, worker, reserve) else null, surplus)
    }

    internal fun pickupGround(cityId: UUID, workerId: UUID, entities: List<UUID>): CompletableFuture<Settlement> =
        action("resident_ground_pickup", null, "city" to cityId, "worker" to workerId, "entities" to entities.size) {
            collectedMutation(cityId, workerId) { city, feet -> storage.prepareGroundPickup(city, workerId, entities, feet) }
        }

    internal fun depositCollected(cityId: UUID, workerId: UUID, destination: CivPosition?): CompletableFuture<Settlement> =
        action("resident_home_deposit", null, "city" to cityId, "worker" to workerId) {
            val city = cities[cityId] ?: throw CivsRejection("no-city")
            rejectIf(!collectionAvailable(cityId), "city-busy")
            val worker = city.workers.firstOrNull { it.id == workerId } ?: throw CivsRejection("worker-not-found")
            val reserve = collectionReserve(city, worker)
            if (CivsEquipment.homeSurplus(worker, reserve).isEmpty()) {
                if (worker.hasCollectedItems != true) CompletableFuture.completedFuture(city)
                else mutate(cityId) { current -> CivsRules.validate(current.copy(workers = current.workers.map {
                    if (it.id == workerId) it.copy(hasCollectedItems = false) else it
                }, revision = current.revision + 1)) }
            } else collectedMutation(cityId, workerId, allowNight = true) { current, feet -> storage.prepareHomeDeposit(current, workerId,
                destination ?: throw CivsRejection("storage-unavailable"), feet, collectionReserve(current,
                    current.workers.first { it.id == workerId })) }
        }

    private fun collectedMutation(cityId: UUID, workerId: UUID, allowNight: Boolean = false,
        materializingFood: Boolean = false,
        prepare: (Settlement, org.bukkit.Location) -> CivCollectedPlan): CompletableFuture<Settlement> {
        checkMain()
        rejectIf(!collectionAvailable(cityId), "city-busy") // Throttle before reserving a city or any native source.
        val city = cities.getValue(cityId)
        val feet = if (materializingFood) citizens.residentFeet(workerId) else citizens.residentCollectionFeet(workerId, allowNight)
        feet ?: throw CivsRejection("worker-too-far")
        rejectIf(feet.world?.uid != city.worldId, "wrong-world")
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        val tx = try {
            val prepared = prepare(city, feet)
            val transfer = prepared.transfer ?: run { storage.unlock(id); return CompletableFuture.completedFuture(city) }
            val after = CivsRules.validate(city.copy(stock = prepared.stock ?: city.stock,
                workers = city.workers.map { if (it.id == workerId) prepared.worker else it },
                revision = city.revision + 1))
            CivInventoryTransaction(id, cityId, city.revision, after, emptyList(), collected = transfer).also {
                storage.seal(it)
                inventoryJournal.reserveCollection(city.worldId, id)
                busy += cityId // The immutable after-state must remain current during the first asynchronous disk fence.
            }
        } catch (failure: Exception) {
            storage.unlock(id)
            inventoryJournal.releaseCollection(city.worldId, id)
            busy -= cityId
            throw failure
        }
        return inventoryJournal.submitCollected(city, tx)
    }

    private fun validateNativeCollected(tx: CivInventoryTransaction) {
        val collected = requireNotNull(tx.collected)
        rejectIf(storage.collectedBeforeState(tx) != CivInventoryApplyResult.Applied, "stale-delivery")
        if (collected.food?.materialized?.let { it > 0 } == true) return
        val feet = citizens.residentCollectionFeet(collected.workerId, allowNight = collected.homeId != null)
            ?: throw CivsRejection("worker-too-far")
        val world = plugin.server.getWorld(tx.after.worldId) ?: throw CivsRejection("storage-unavailable")
        rejectIf(feet.world?.uid != world.uid, "wrong-world")
        collected.ground.forEach { change ->
            val item = world.getEntity(change.entityId) as? org.bukkit.entity.Item ?: throw CivsRejection("stale-delivery")
            rejectIf(!civsCanCollect(feet, item, collected.workerId), "worker-too-far")
        }
        collected.containers.forEach { rejectIf(!civsCanReachContainer(feet, it.container), "worker-too-far") }
    }

    private fun inventoryMutation(
        cityId: UUID, prepare: (Settlement) -> CivStoragePlan?,
        sourcePlayer: Player? = null,
        keepViewersOpen: Boolean = false,
        transition: (Settlement, CivInventory) -> Settlement,
    ): CompletableFuture<Settlement> {
        checkMain()
        rejectIf(!ready(), "not-ready")
        rejectIf(cityId in busy || cityId in unhealthy, "city-busy")
        val city = cities[cityId] ?: throw CivsRejection("no-city")
        rejectIf(city.pendingDeletion != null, "deletion-pending")
        val id = UUID.randomUUID()
        storage.reserve(city, id, sourcePlayer)
        val tx = try {
            val prepared = prepare(city) ?: run {
                storage.unlock(id)
                return CompletableFuture.completedFuture(city)
            }
            val after = CivsRules.validate(transition(city, prepared.inventoryAfter))
            if (after == city && prepared.changes.isEmpty() && prepared.residentTransfer == null && prepared.worldChanges.orEmpty().isEmpty()) {
                storage.unlock(id)
                return CompletableFuture.completedFuture(city)
            }
            require(after.revision == city.revision + 1)
            CivInventoryTransaction(id, city.id, city.revision, after, prepared.changes, prepared.playerDebit,
                prepared.residentTransfer, prepared.worldChanges, mine = prepared.mine)
                .also { storage.seal(it, keepViewersOpen) }
        } catch (failure: Exception) { storage.unlock(id); throw failure }
        return inventoryJournal.submit(tx)
    }

    /** Native player-only operations share the existing exact receipt without inventing town stock. */
    private fun nativePlayerMutation(cityId: UUID, player: Player, keepViewersOpen: Boolean = false,
        prepare: (Settlement, UUID) -> CivInventoryTransaction?): CompletableFuture<Settlement> {
        checkMain()
        rejectIf(!ready(), "not-ready")
        rejectIf(cityId in busy || cityId in unhealthy, "city-busy")
        val city = cities[cityId] ?: throw CivsRejection("no-city")
        rejectIf(city.pendingDeletion != null, "deletion-pending")
        val id = UUID.randomUUID()
        storage.reserve(city, id, player)
        val tx = try {
            val prepared = prepare(city, id) ?: run { storage.unlock(id); return CompletableFuture.completedFuture(city) }
            prepared.also { storage.seal(it, keepViewersOpen) }
        } catch (failure: Exception) { storage.unlock(id); throw failure }
        return inventoryJournal.submit(tx)
    }

    private fun requestResurrectionAdmission(tx: CivInventoryTransaction) {
        val debit = tx.resurrection ?: return
        val current = cities[tx.cityId] ?: return
        plugin.server.getWorld(current.worldId)?.let { world ->
            if (current.workers.any { it.id == debit.corpse.worker.id }) citizens.requestAdmissionAt(current.id,
                debit.corpse.worker.id, org.bukkit.Location(world, debit.position.x + .5, debit.feetY, debit.position.z + .5))
        }
    }

    private fun resurrectionRejection(tx: CivInventoryTransaction, current: Settlement, fresh: Boolean): Throwable? {
        val debit = requireNotNull(tx.resurrection)
        val accepted = org.bukkit.Location(plugin.server.getWorld(current.worldId), debit.position.x + .5,
            debit.feetY, debit.position.z + .5)
        return runCatching {
            rejectIf(current.pendingDeletion != null, "deletion-pending")
            rejectIf(current.corpses.orEmpty().firstOrNull { it.worker.id == debit.corpse.worker.id } != debit.corpse,
                "resurrection-changed")
            CivsRules.resurrect(current, debit.playerId, debit.corpse.worker.id, debit.corpse.diedAt, now(),
                administrator = current.autonomy != null)
            rejectIf(!civsCorpseStandingSafe(accepted), "corpse-unavailable")
            if (fresh) {
                val player = plugin.server.getPlayer(debit.playerId) ?: throw CivsRejection("player-offline")
                corpse(player, corpseCity(player, current.id), debit.corpse.worker.id, debit.corpse.diedAt)
                val actual = corpseLocation(current.id, debit.corpse.worker.id, debit.corpse.diedAt)
                rejectIf(actual == null || actual.world?.uid != current.worldId ||
                    actual.distanceSquared(accepted) > .0001, "corpse-unavailable")
            }
        }.exceptionOrNull()
    }

    private fun inventoryRetired(tx: CivInventoryTransaction, recoveryPath: Boolean): Settlement {
        tx.upkeep?.let { receipt ->
            val work = receipt.work
            val upkeep = requireNotNull(work.upkeep)
            if (upkeep.routeOverride != null) loadAutonomousPlans(tx.after).whenCompleteSync(tasks) { _, failure ->
                if (failure != null) log("reload repaired autonomous road ${tx.cityId}", failure, tx.cityId)
            }
            val surfaceChange = work.changes.firstOrNull { it.position == upkeep.floor }
            footTraffic.registerSurface(tx.cityId, upkeep.floor)
            CivsDiagnostics.event("road_upkeep_committed", "city" to tx.cityId, "worker" to work.workerId,
                "cycle" to work.cycle, "transaction" to tx.id, "revision" to tx.after.revision,
                "source_building" to upkeep.source.id, "floor" to upkeep.floor,
                "before" to surfaceChange?.before, "after" to upkeep.after,
                "surface_changed" to (surfaceChange != null),
                "cleared_cover" to work.changes.count { it.position != upkeep.floor },
                "stone_spent" to receipt.stockBefore.stone - tx.after.stock.stone,
                "recovery_path" to recoveryPath)
        }
        tx.autonomousVisual?.let { receipt ->
            CivsDiagnostics.event("autonomous_world_work_committed", "city" to tx.cityId,
                "worker" to receipt.work.workerId, "cycle" to receipt.work.cycle, "transaction" to tx.id,
                "revision" to tx.after.revision, "status" to receipt.work.status,
                "blocks" to receipt.work.changes.size, "mining_step" to receipt.work.miningStep,
                "native_outputs" to 0, "economic_credit" to false, "recovery_path" to recoveryPath)
        }
        // Recovery publication can still be hidden by unhealthy; retry after releasing that gate.
        requestResurrectionAdmission(tx)
        physics.enqueue(tx.after.worldId, tx.worldChanges.orEmpty())
        CivsDiagnostics.event("inventory_committed", "city" to tx.cityId, "transaction" to tx.id, "revision" to tx.after.revision)
        tx.collected?.let { transfer ->
            val items = if (transfer.ground.isNotEmpty()) transfer.ground.sumOf {
                it.before.amount - (it.after?.amount ?: 0)
            } else transfer.containers.sumOf { (it.after?.amount ?: 0) - (it.before?.amount ?: 0) }
            val event = when {
                transfer.food?.materialized?.let { it > 0 } == true -> "resident_food_materialized"
                transfer.food != null -> "resident_food_picked_up"
                transfer.ground.isNotEmpty() -> "resident_ground_collected"
                else -> "resident_home_deposited"
            }
            CivsDiagnostics.event(event,
                "city" to tx.cityId, "worker" to transfer.workerId, "transaction" to tx.id,
                "revision" to tx.after.revision, "homeId" to transfer.homeId, "items" to kotlin.math.abs(items),
                "food_stock_before" to transfer.food?.stockBefore?.food, "food_stock_after" to tx.after.stock.food,
                "building" to transfer.food?.buildingId,
                "material" to transfer.food?.material,
                "slots" to if (transfer.ground.isNotEmpty()) transfer.ground.size else transfer.containers.size)
        }
        tx.residentTransfer?.let { transfer ->
            val before = transfer.before.filterNotNull().groupingBy { it.material }.fold(0) { total, item -> total + item.amount }
            val resident = tx.after.workers.firstOrNull { it.id == transfer.workerId }
                ?: tx.after.corpses.orEmpty().first { it.worker.id == transfer.workerId }.worker
            val after = CivsEquipment.materials(resident)
            for (material in (before.keys + after.keys).sorted()) {
                val previous = before[material]?.toLong() ?: 0L
                val current = after[material] ?: 0L
                if (previous != current) CivsDiagnostics.event("resident_transfer_committed",
                    "city" to tx.cityId, "transaction" to tx.id, "worker" to transfer.workerId,
                    "player" to transfer.playerId, "material" to material, "before" to previous,
                    "after" to current, "delta" to current - previous)
            }
        }
        val current = cities.getValue(tx.cityId)
        tx.resurrection?.let { debit ->
            CivsDiagnostics.event("resident_resurrected", "city" to tx.cityId, "worker" to debit.corpse.worker.id,
                "died_at" to debit.corpse.diedAt, "transaction" to tx.id, "player" to debit.playerId)
        }
        return current
    }

    override fun availableMaterials(player: Player): Map<String, Long> = storage.available(managed(player))
    override fun constructionAvailable(player: Player, buildingId: UUID): Map<String, Long> =
        storage.constructionAvailable(managed(player).also { rejectIf(it.building(buildingId) == null, "building-not-found") }, buildingId)
    override fun localMaterials(player: Player, buildingId: UUID): Map<String, Long> =
        managed(player).let { localStocks(it)(buildingId) }
    override fun supplyRequests(player: Player): List<CivSupplyRequest> =
        managed(player).let { city -> localStocks(city).let { CivsLogistics.requests(city, it) + craftRequests(city, it) } }

    override fun carpentry(player: Player, buildingId: UUID): CivCarpentryView? {
        val city = managed(player)
        val building = city.building(buildingId)?.takeIf { it.type in CivsCarpentry.workshops } ?: return null
        val snapshot = supply.craftObservation(city.id)
        val orders = city.workers.filter { it.assignment == buildingId }.mapNotNull { worker ->
            craftPlan(city, worker)?.let { job ->
                val carriedMissing = CivsEquipment.storageInputs(worker, job.inputs)
                val stock = snapshot?.stores?.get(buildingId)?.stock.orEmpty()
                CivCarpentryOrderView(worker.id, worker.name, job.material, job.amount, job.inputs,
                    carriedMissing.mapValues { (material, count) -> (count - (stock[material] ?: 0L)).coerceAtLeast(0) }.filterValues { it > 0 },
                    job.demandMaterial, job.targetBuildingId, if (carriedMissing.isEmpty()) "crafting" else "waiting-materials")
            }
        }
        return CivCarpentryView(building.level, !CivsRules.productionAvailable(city, building),
            snapshot == null || !craftObservationCurrent(city), orders, CivsCarpentry.views(building.type))
    }

    override fun setStorageMode(player: Player, mode: CivStorageMode): CompletableFuture<Unit> = cityAction("storage_mode", player, "mode" to mode) { city ->
        if (mode == CivStorageMode.PHYSICAL) materialize(city.id).thenApply { Unit }
        else mutate(city.id) { current ->
            val inventory = current.inventory ?: throw CivsRejection("materials-migration-pending")
            if (inventory.mode == mode) current else CivsRules.validate(current.copy(inventory = inventory.copy(mode = mode), revision = current.revision + 1))
        }.thenApply { Unit }
    }

    override fun materializeStorage(player: Player): CompletableFuture<Unit> = cityAction("storage_materialize", player) { city ->
        rejectIf(city.inventory?.mode != CivStorageMode.PHYSICAL, "invalid-storage-mode")
        materialize(city.id).thenApply { Unit }
    }

    private fun materialize(cityId: UUID, background: Boolean = false): CompletableFuture<Settlement> {
        val before = cities[cityId] ?: throw CivsRejection("no-city")
        return inventoryMutation(cityId, { storage.prepareMaterialization(it) }, keepViewersOpen = background) { current, inventory ->
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

    override fun inspectBuilding(player: Player, cityId: UUID, buildingId: UUID): Settlement {
        requireReady(player)
        val city = cities[cityId] ?: throw CivsRejection("no-city")
        rejectIf(city.autonomy == null && !city.canManage(player.uniqueId), "not-authorized")
        rejectIf(city.id in unhealthy, "storage-unavailable")
        rejectIf(city.pendingDeletion != null, "deletion-pending")
        inventoryBuilding(player, city, buildingId)
        return city
    }

    override fun openBuildingInventory(player: Player, cityId: UUID, buildingId: UUID): CompletableFuture<Unit> =
        action("inventory_inspect", player, "city" to cityId, "building" to buildingId) {
            val city = inspectBuilding(player, cityId, buildingId)
            if (city.autonomy == null) openBuildingInventory(player, buildingId)
            else {
                storage.openControl(player, city, requireNotNull(city.building(buildingId)))
                CompletableFuture.completedFuture(Unit)
            }
        }

    override fun openBuildingInventory(player: Player, buildingId: UUID): CompletableFuture<Unit> = cityAction("inventory_open", player, "building" to buildingId) { city ->
        inventoryBuilding(player, city, buildingId)
        val prepared = if (city.inventory?.mode == CivStorageMode.PHYSICAL && pendingMaterials(city) > 0)
            materialize(city.id) else CompletableFuture.completedFuture(city)
        prepared.thenApply {
            rejectIf(!player.isOnline, "player-offline")
            val current = managed(player)
            rejectIf(current.id != city.id, "no-city")
            storage.openControl(player, current, inventoryBuilding(player, current, buildingId))
        }
    }

    override fun buildingControl(player: Player, buildingId: UUID): CompletableFuture<CivPosition> = action("control_locate", player, "building" to buildingId) {
        val city = managed(player)
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        val result = tracked<CivPosition>()
        blueprints.controlPosition(building).whenCompleteSync(tasks) { control, failure ->
                if (failure != null) result.completeExceptionally(failure)
                else runCatching {
                    rejectIf(managed(player).let { it.id != city.id || it.building(buildingId) != building }, "control-location-changed")
                    val point = requireNotNull(control)
                    CivsDiagnostics.event("control_located", "city" to city.id, "building" to buildingId,
                        "previous" to building.control, "control" to point)
                    point
                }.onSuccess(result::complete).onFailure(result::completeExceptionally)
            }
        result
    }

    /** Install the authored marker once; the previous native container and its contents stay untouched. */
    override fun relocateControl(player: Player, buildingId: UUID, expectedTarget: CivPosition): CompletableFuture<Unit> =
        cityAction("control_relocate", player, "building" to buildingId, "expected_control" to expectedTarget) { before ->
            val building = before.building(buildingId) ?: throw CivsRejection("building-not-found")
            rejectIf(before.id in busy || construction.isPlacing(before.id) || inventoryJournal.pending(before.id), "city-busy")
            rejectIf(before.construction?.buildingId == buildingId, "construction-busy")
            rejectIf(building.phase != BuildingPhase.ACTIVE, "control-relocation-site")
            val result = tracked<Unit>()
            buildingControl(player, buildingId).whenCompleteSync(tasks) { target, failure ->
                if (failure != null) result.completeExceptionally(failure)
                else runCatching { afterCityAvailable(before.id) {
                    rejectIf(!player.isOnline, "player-offline")
                    val point = requireNotNull(target)
                    rejectIf(point != expectedTarget, "control-location-changed")
                    val current = managed(player)
                    rejectIf(current.id != before.id || current.building(buildingId) != building, "control-location-changed")
                    rejectIf(current.construction?.buildingId == buildingId || construction.isPlacing(current.id), "construction-busy")
                    rejectIf(inventoryJournal.pending(current.id), "inventory-recovery")
                    near(player, current.worldId, point)
                    relocateControl(current, building, point).thenApply { Unit }
                }.whenCompleteSync(tasks) { _, admissionFailure ->
                    if (admissionFailure == null) result.complete(Unit) else result.completeExceptionally(admissionFailure)
                }
                }.onFailure(result::completeExceptionally)
            }
            result
        }

    private fun relocateControl(current: Settlement, building: CivBuilding, point: CivPosition): CompletableFuture<Settlement> {
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
        return mutate(current.id) { city ->
            rejectIf(city.building(building.id) != building || city.construction?.buildingId == building.id, "control-location-changed")
            if (point == old) city else {
                val inventory = city.inventory
                rejectIf(inventory == null && city.autonomy == null, "materials-migration-pending")
                val containers = (inventory?.containers.orEmpty() - point).let { if (retained == null || retained in it) it else it + retained }
                rejectIf(containers.size > CivInventory.MAX_CONTAINERS, "container-limit")
                CivsRules.validate(city.copy(buildings = city.buildings.map {
                    if (it.id == building.id) it.copy(control = point) else it
                }, inventory = inventory?.copy(containers = containers), revision = city.revision + 1,
                    pendingControls = city.pendingControls.orEmpty().filterNot { it.buildingId == building.id } + placement))
            }
        }.thenCompose { _ ->
            controlFailures.remove(building.id)
            CivsDiagnostics.event("control_relocated", "city" to current.id, "building" to building.id,
                "previous" to old, "control" to point, "retained_container" to retained)
            afterCityAvailable(current.id) { installControl(current.id, building.id) }
        }
    }

    override fun depositMaterials(player: Player, buildingId: UUID): CompletableFuture<Unit> = cityAction("inventory_deposit", player, "building" to buildingId) { city ->
        val building = inventoryBuilding(player, city, buildingId)
        inventoryMutation(city.id, { storage.depositFromControl(it, building) }) { before, inventory ->
            before.copy(inventory = inventory, revision = before.revision + 1)
        }.thenApply { Unit }
    }

    override fun withdrawMaterial(player: Player, buildingId: UUID, material: String): CompletableFuture<Unit> = cityAction("inventory_withdraw", player, "building" to buildingId, "material" to material) { city ->
        val building = inventoryBuilding(player, city, buildingId)
        inventoryMutation(city.id, { storage.withdrawToControl(it, building, material) }) { before, inventory ->
            before.copy(inventory = inventory, revision = before.revision + 1)
        }.thenApply { Unit }
    }

    override fun registerContainer(player: Player): CompletableFuture<Unit> = action("container_register", player) {
        val cityId = managed(player).id
        val block = player.rayTraceBlocks(6.0, FluidCollisionMode.NEVER)?.hitBlock ?: throw CivsRejection("look-at-container")
        val point = CivPosition(block.x, block.y, block.z)
        afterCityAvailable(cityId) {
            val city = managed(player)
            rejectIf(!player.isOnline || city.id != cityId, "not-authorized")
            rejectIf(player.world.uid != city.worldId || block.world.uid != city.worldId || !city.contains(point.x, point.z), "outside-city")
            rejectIf(player.eyeLocation.distanceSquared(block.location) > 64, "too-far")
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
    }

    override fun unregisterContainer(player: Player, position: CivPosition): CompletableFuture<Unit> = cityAction("container_unregister", player, "position" to position) { managedCity ->
        mutate(managedCity.id) { city ->
            val inventory = city.inventory ?: throw CivsRejection("materials-migration-pending")
            if (position !in inventory.containers) city else CivsRules.validate(city.copy(
                inventory = inventory.copy(containers = inventory.containers - position), revision = city.revision + 1))
        }.thenApply { Unit }
    }

    override fun setProduction(player: Player, buildingId: UUID, material: String): CompletableFuture<Unit> = cityAction("production_select", player, "building" to buildingId, "material" to material) { managedCity ->
        mutate(managedCity.id) { city ->
            CivsRules.setProduction(city, player.uniqueId, buildingId, material, now())
        }.thenApply { Unit }
    }

    override fun setForesterSpecies(player: Player, cityId: UUID, buildingId: UUID, material: String): CompletableFuture<Unit> =
        action("forester_species_select", player, "city" to cityId, "building" to buildingId, "material" to material) {
            researchCity(player, cityId, true)
            afterCityAvailable(cityId) {
                rejectIf(!player.isOnline, "player-offline")
                researchCity(player, cityId, true)
                mutate(cityId) { city ->
                    rejectIf(city.building(buildingId)?.type != BuildingType.FORESTER, "invalid-production")
                    CivsRules.setProduction(city, if (city.autonomy != null) city.owner else player.uniqueId, buildingId, material, now())
                }.thenApply { Unit }
            }
        }

    override fun deleteCity(player: Player, expectedCityId: UUID, confirmationName: String): CompletableFuture<Unit> =
        cityAction("delete_city", player, "city" to expectedCityId, allowDeletion = true) { city ->
            CivsRules.requireDeletion(city, player.uniqueId, expectedCityId, confirmationName)
            beginDeletion(city, player.uniqueId, null)
        }

    override fun deleteBuilding(player: Player, expectedCityId: UUID, buildingId: UUID): CompletableFuture<Unit> =
        cityAction("delete_building", player, "city" to expectedCityId, "building" to buildingId, allowDeletion = true) { city ->
            rejectIf(city.id != expectedCityId, "delete-city-changed")
            beginDeletion(city, player.uniqueId, buildingId)
        }

    private val deletionFailures = mutableMapOf<UUID, String>()
    private val deletionRequests = mutableSetOf<UUID>()
    private var nextDeletionAt = 0L
    private var deletionCursor = 0

    private fun beginDeletion(city: Settlement, actor: UUID, buildingId: UUID?): CompletableFuture<Unit> {
        rejectIf(city.id in deletionRequests, "deletion-pending")
        rejectIf(city.id in busy || inventoryJournal.pending(city.id), "city-busy")
        val proposed = CivsRules.requestDeletion(city, actor, buildingId)
        if (city.pendingDeletion != null) return finishDeletion(city)
        busy += city.id
        val result = tracked<Unit>()
        // The durable save releases busy before its continuation runs; recovery must not steal that handoff.
        deletionRequests += city.id
        result.whenCompleteSync(tasks) { _, _ -> deletionRequests -= city.id }
        val loaded = tracked<Unit>()
        runCatching { construction.stop(city.id) }.onSuccess { stopped -> stopped.whenCompleteSync(tasks) { _, stopFailure ->
            if (stopFailure != null) loaded.completeExceptionally(stopFailure)
            else runCatching { loadControlChunks(city, deletionBuildings(proposed)) }.onSuccess { chunks ->
                chunks.whenComplete { _, chunkFailure ->
                    if (chunkFailure == null) loaded.complete(Unit) else loaded.completeExceptionally(chunkFailure)
                }
            }.onFailure(loaded::completeExceptionally)
        } }.onFailure(loaded::completeExceptionally)
        loaded.whenCompleteSync(tasks) { _, failure ->
            if (failure != null) {
                busy -= city.id
                result.completeExceptionally(failure)
            } else runCatching {
                rejectIf(!ready() || cities[city.id] != city, "stale-city")
                controlCleanup.prepare(city, deletionBuildings(proposed))
                busy -= city.id
                mutate(city.id) { CivsRules.requestDeletion(it, actor, buildingId) }.whenCompleteSync(tasks) { saved, saveFailure ->
                    if (saveFailure != null) result.completeExceptionally(saveFailure)
                    else {
                        CivsDiagnostics.event("deletion_reserved", "city" to city.id, "building" to buildingId,
                            "construction" to city.construction?.id)
                        runCatching { finishDeletion(requireNotNull(saved)) }.onSuccess { finished ->
                            finished.whenCompleteSync(tasks) { _, deleteFailure ->
                                if (deleteFailure == null) result.complete(Unit) else result.completeExceptionally(deleteFailure)
                            }
                        }.onFailure(result::completeExceptionally)
                    }
                }
            }.onFailure { busy -= city.id; result.completeExceptionally(it) }
        }
        return result
    }

    private fun deletionBuildings(city: Settlement): List<CivBuilding> {
        val intent = requireNotNull(city.pendingDeletion)
        return intent.buildingId?.let { listOf(requireNotNull(city.building(it))) } ?: city.buildings
    }

    /** Explicit deletion may load only the already-authored control chunks; gameplay pulses never scan/load a region. */
    private fun loadControlChunks(city: Settlement, buildings: List<CivBuilding>): CompletableFuture<Unit> {
        checkMain()
        val target = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("world-unavailable")
        val loads = buildings.mapNotNull(CivBuilding::control).map { (it.x shr 4) to (it.z shr 4) }.distinct()
            .filterNot { (x, z) -> target.isChunkLoaded(x, z) }.map { (x, z) -> target.getChunkAtAsync(x, z, false) }
        return CompletableFuture.allOf(*loads.toTypedArray()).thenApply { Unit }
    }

    private fun finishDeletion(city: Settlement): CompletableFuture<Unit> {
        rejectIf(city.id in busy || inventoryJournal.pending(city.id), "city-busy")
        val buildings = deletionBuildings(city)
        busy += city.id
        val result = tracked<Unit>()
        val loading = runCatching { loadControlChunks(city, buildings) }.getOrElse {
            busy -= city.id
            return CompletableFuture.failedFuture(it)
        }
        loading.whenCompleteSync(tasks) { _, failure ->
            if (failure != null) { busy -= city.id; result.completeExceptionally(failure) }
            else runCatching {
                rejectIf(!ready() || cities[city.id] != city, "stale-city")
                val controls = controlCleanup.prepare(city, buildings)
                val removed = controlCleanup.apply(city, controls)
                CivsDiagnostics.event("controls_deleted", "city" to city.id, "building" to city.pendingDeletion?.buildingId,
                    "registered" to controls.size, "removed" to removed)
                if (city.pendingDeletion?.buildingId == null) {
                    deleteCityRecord(city).whenCompleteSync(tasks) { _, deleteFailure ->
                        if (deleteFailure == null) result.complete(Unit) else result.completeExceptionally(deleteFailure)
                    }
                } else {
                    busy -= city.id
                    mutate(city.id, allowDeletion = true) { CivsRules.finishBuildingDeletion(it, it.owner) }.whenCompleteSync(tasks) { saved, deleteFailure ->
                        if (deleteFailure != null) result.completeExceptionally(deleteFailure)
                        else {
                            val id = requireNotNull(city.pendingDeletion.buildingId)
                            construction.clearQueueFailure(city.id)
                            deletionFailures.remove(city.id)
                            controlFailures.remove(id)
                            storage.refreshBuilding(requireNotNull(saved))
                            cleanupQueuedUpgrades(city, setOf(id))
                            CivsDiagnostics.event("building_deleted", "city" to city.id, "building" to id,
                                "construction_cancelled" to city.construction?.takeIf { it.buildingId == id }?.id)
                            tasks.runLater(1) { if (ready()) construction.pulseQueue() }
                            result.complete(Unit)
                        }
                    }
                }
            }.onFailure { busy -= city.id; result.completeExceptionally(it) }
        }
        return result
    }

    private fun pulseDeletions() {
        if (now() < nextDeletionAt) return
        nextDeletionAt = now() + 5_000L
        val pending = cities.values.filter { it.pendingDeletion != null && it.id !in busy && it.id !in unhealthy &&
            !inventoryJournal.pending(it.id) && it.id !in deletionRequests }
        if (pending.isEmpty()) return
        val city = pending[Math.floorMod(deletionCursor++, pending.size)]
        runCatching { finishDeletion(city) }.onSuccess { future -> future.whenCompleteSync(tasks) { _, failure ->
            if (failure == null) deletionFailures.remove(city.id)
            else {
                val reason = CivsDiagnostics.rejection(failure)?.reason ?: "storage-unavailable"
                if (deletionFailures.put(city.id, reason) != reason) log("recover deletion ${city.id}", failure, city.id)
            }
        } }.onFailure { failure ->
            val reason = CivsDiagnostics.rejection(failure)?.reason ?: "storage-unavailable"
            if (deletionFailures.put(city.id, reason) != reason) log("recover deletion ${city.id}", failure, city.id)
        }
    }

    private fun deleteCityRecord(city: Settlement): CompletableFuture<Unit> {
        val result = tracked<Unit>()
        store.delete(city).whenCompleteSync(tasks) { _, failure ->
            if (failure != null) {
                busy -= city.id
                val rejection = CivsDiagnostics.rejection(failure)
                if (rejection != null) result.completeExceptionally(rejection)
                else {
                    unhealthy += city.id
                    log("delete settlement ${city.id}", failure, city.id)
                    result.completeExceptionally(CivsRejection("storage-unavailable"))
                }
                updateHealth()
            } else publish(city.id, null).whenCompleteSync(tasks) { _, publishFailure ->
                busy -= city.id
                if (publishFailure != null) {
                    unhealthy += city.id
                    log("publish settlement deletion ${city.id}", publishFailure, city.id)
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
                    cleanupQueuedUpgrades(city, city.buildings.mapTo(mutableSetOf(), CivBuilding::id))
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
        return result
    }

    private fun mutate(cityId: UUID, allowDeletion: Boolean = false, transform: (Settlement) -> Settlement): CompletableFuture<Settlement> {
        checkMain()
        rejectIf(closed, "not-ready")
        rejectIf(cityId in unhealthy, "storage-unavailable")
        rejectIf(cityId in busy, "city-busy")
        val city = cities[cityId] ?: throw CivsRejection("no-city")
        rejectIf(city.pendingDeletion != null && !allowDeletion, "deletion-pending")
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
                    log("save settlement $cityId", failure, cityId)
                    result.completeExceptionally(CivsRejection("storage-unavailable"))
                }
            }
            updateHealth()
        }
        return result
    }

    private fun loadCities(maximumLoadedChunks: Int = settings.maximumLoadedChunks): CompletableFuture<Unit> {
        checkMain()
        rejectIf(loading || closed, "not-ready")
        loading = true
        planBoardSnapshots.retain(emptySet())
        planBoards.pulse(emptyList())
        autonomousDevelopment.reset()
        forestryRoutes.clear()
        construction.resetSites()
        roadMaintenance.clear()
        supply.clear()
        supplyObservations.clear()
        loadFailure = null
        storage.cancelCollectedPersistence()
        chunkLoading.reset(maximumLoadedChunks)
        (previews.keys + selections.keys + requests.keys).toList().forEach { cancelPreview(it, "reload") }
        citizens.pulse(now()) // Provider is empty while loading: despawn previous runtime actors.
        val result = tracked<Unit>()
        val materialized = tracked<List<Settlement>>()
        inventoryJournal.bootstrap().thenCompose { store.load() }.whenCompleteSync(tasks) { records, failure ->
            if (failure != null) materialized.completeExceptionally(failure)
            else if (closed) materialized.completeExceptionally(CivsRejection("not-ready"))
            else runCatching {
                // A pending immutable receipt must finish its own revision before a cargo migration can follow it.
                val migrated = requireNotNull(records).map { if (inventoryJournal.pending(it.id)) it else CivsLogistics.materializeCargo(it) }
                val writes = migrated.zip(records).filter { (after, before) -> after != before }.map { (after, _) -> store.save(after) }
                CompletableFuture.allOf(*writes.toTypedArray()).whenComplete { _, writeFailure ->
                    if (writeFailure == null) materialized.complete(migrated) else materialized.completeExceptionally(writeFailure)
                }
            }.onFailure(materialized::completeExceptionally)
        }
        materialized.thenApplyAsync({ records ->
            val members = records.filter { it.autonomy == null }.flatMap { it.members + it.owner }
            require(members.distinct().size == members.size) { "A player belongs to more than one settlement" }
            val missingNames = records.flatMap { city ->
                (city.members + city.owner).filter { it.toString() !in city.memberNames.orEmpty() }
            }
            val recovered = offlinePlayerData?.names(missingNames)
            recovered?.failures?.forEach { (id, reason) ->
                CivsDiagnostics.event("member_name_unavailable", "player" to id, "reason" to reason)
            }
            Triple(records.associateByTo(linkedMapOf(), Settlement::id), CivsProtection.prepare(records),
                recovered?.names?.mapNotNull { (id, name) -> name?.let { id to it } }?.toMap().orEmpty())
        }, executor).whenCompleteSync(tasks) { loaded, failure ->
            if (closed) { result.completeExceptionally(CivsRejection("not-ready")); return@whenCompleteSync }
            loading = false
            if (failure != null) {
                initialized = false
                loadFailure = "storage-unavailable"
                log("load settlements", failure)
                result.completeExceptionally(failure)
            } else runCatching {
                val (records, prepared, recovered) = requireNotNull(loaded)
                storage.replaceCities(records.values)
                cities = records
                construction.loadLegacyRetries(records.values)
                recoveredMemberNames = recovered
                residentLife.resetMinuteClock()
                citizens.reconcileResidents()
                protection.publish(prepared)
                geometryGeneration++
                unhealthy.clear()
                unhealthy.addAll(inventoryJournal.pendingCityIds())
                construction.clearQueueFailures()
                deletionFailures.clear()
                initialized = true
                result.complete(Unit)
                autonomousDevelopment.pulseAccessRepair()
                construction.pulse()
            }.onFailure { initialized = false; result.completeExceptionally(it); log("validate settlements", it) }
            updateHealth()
        }
        return result
    }

    /** Publish geometry together with its prepared protection index; concurrent cities retry a stale candidate. */
    private fun publish(city: Settlement): CompletableFuture<Unit> = publish(city.id, city)

    private fun publish(cityId: UUID, city: Settlement?): CompletableFuture<Unit> {
        if (closed) return CompletableFuture.failedFuture(CivsRejection("not-ready"))
        val previous = cities[cityId]
        if (city != null && previous?.buildings == city.buildings && previous?.construction?.plannedBuilding == city.construction?.plannedBuilding && previous.members == city.members &&
            previous.inventory?.containers == city.inventory?.containers && previous.pendingControls == city.pendingControls && previous.pendingDeletion == city.pendingDeletion) {
            cities[city.id] = city
            if (city.autonomy == null && civSupplyInputs(previous) != civSupplyInputs(city)) supply.request(city.id)
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
                if (city == null) { supply.remove(cityId); supplyObservations.remove(cityId); forestryRoutes.remove(cityId) }
                else if (city.autonomy == null) supply.request(cityId)
                protection.publish(requireNotNull(prepared))
                geometryGeneration++
            }.onSuccess { result.complete(Unit) }.onFailure(result::completeExceptionally)
        }
        return result
    }

    private fun refreshBoundary(player: Player) {
        val preview = previews[player.uniqueId]?.takeUnless { it.upgrading }
        val selection = selections[player.uniqueId]
        val selectedCity = selection?.cityId ?: preview?.cityId
        val type = if (selection?.cityId != null) selection.type else preview?.blueprint?.building?.type
        val city = if (!ready()) null else if (selectedCity != null && type != null)
            runCatching { managedPlacement(player, selectedCity, type) }.getOrNull() else city(player)
        val authorized = selectedCity == null || city != null
        val visiblePreview = preview?.takeIf { authorized }
        boundary.pulse(player, city,
            authorized && (player.uniqueId in selections || preview != null || previewLoads[player.uniqueId] == true),
            visiblePreview?.blueprint?.building?.bounds, visiblePreview?.blueprint?.building?.id,
            city?.buildings?.associate { it.id to buildingStatus(it.id) }.orEmpty())
    }

    private fun pulse() {
        if (closed) return
        construction.retain()
        selections.filterValues { it.expires <= now() }.keys.toList().forEach { cancelSelection(it, "expired") }
        previews.filterValues { it.expires <= now() }.keys.toList().forEach { cancelPreview(it, "expired") }
        citizens.pulse(now())
        plugin.server.onlinePlayers.forEach(::refreshBoundary)
        if (ready()) {
            inventoryJournal.recover()
            pulseCorpses()
            pulseDeletions()
            pulseControls()
            residentLife.pulseImmigration()
            pulseResearch()
            residentLife.pulseAutoAssignments()
            residentLife.pulsePopulation()
            pulseMemberNames()
            pulseStorage()
            pulseResidentFood()
            pulseTestingSupplies()
            autonomousDevelopment.pulseAccessRepair()
            autonomousDevelopment.pulse()
            construction.pulseQueue()
        }
        construction.pulse()
        val visibleCities = if (ready()) cities.values.filter { it.id !in unhealthy && it.pendingDeletion == null } else emptyList()
        planBoardSnapshots.retain(visibleCities.mapTo(hashSetOf(), Settlement::id))
        planBoards.pulse(visibleCities)
        updateHealth()
    }

    private fun testingConstructionNeed(city: Settlement): Map<String, Long>? {
        val order = city.construction ?: return null
        return order.payment?.awaiting ?: construction.supplyNeed(city)
    }

    /** One bounded journal per pulse. Recovery replays this saved receipt, never a second issuance. */
    internal fun prepareTestingConstructionSupplies(cityId: UUID): CompletableFuture<Settlement> {
        var grant: CivTestingConstructionGrant? = null
        return inventoryMutation(cityId, { city ->
            if (city.settings?.autoConstructionMaterials != true || !testingAllowed(city)) return@inventoryMutation null
            val required = testingConstructionNeed(city) ?: return@inventoryMutation null
            val order = city.construction ?: return@inventoryMutation null
            grant = civsTestingConstructionGrant(city, required, storage.constructionAvailable(city, order.buildingId))
            if (grant == null) null else CivStoragePlan(requireNotNull(city.inventory), emptyList())
        }, keepViewersOpen = true) { before, inventory ->
            before.copy(workers = requireNotNull(grant).workers, inventory = inventory, revision = Math.addExact(before.revision, 1))
        }.thenApply { saved ->
            grant?.issued?.forEach { (worker, materials) ->
                CivsDiagnostics.event("testing_construction_materials_granted", "city" to saved.id, "worker" to worker,
                    "order" to saved.construction?.id, "batch" to saved.construction?.workDone, "materials" to materials,
                    "revision" to saved.revision)
            }
            saved
        }
    }

    private fun pulseTestingSupplies() {
        val candidates = cities.values.filter { city -> city.settings?.autoConstructionMaterials == true &&
            city.id !in busy && city.id !in unhealthy && city.pendingDeletion == null && city.inventory != null &&
            settlementActive(city) && testingAllowed(city) && testingConstructionNeed(city) != null }
        if (candidates.isEmpty()) return
        val city = candidates[Math.floorMod(testingSupplyCursor++, candidates.size)]
        runCatching { prepareTestingConstructionSupplies(city.id) }.onSuccess { future ->
            future.whenComplete { _, failure -> if (failure != null) log("issue testing construction supplies ${city.id}", failure, city.id) }
        }.onFailure { log("issue testing construction supplies ${city.id}", it, city.id) }
    }

    private fun pulseCorpses() {
        lastInjury.keys.retainAll(cities.values.flatMap { it.workers }.mapTo(hashSetOf(), CivWorker::id))
        val city = cities.values.firstOrNull { it.id !in busy && it.id !in unhealthy &&
            it.corpses.orEmpty().any { corpse -> corpse.expiresAt <= now() } } ?: return
        mutate(city.id) { current -> current.copy(corpses = current.corpses.orEmpty().filter { it.expiresAt > now() },
            revision = current.revision + 1) }.whenComplete { _, error ->
            if (error != null) log("expire resident corpses ${city.id}", error, city.id)
        }
    }

    private val controlFailures = mutableSetOf<UUID>()
    private var controlCursor = 0
    private var nextStorageTransferAt = 0L
    private var storageCursor = 0

    private fun pulseStorage() {
        if (now() < nextStorageTransferAt) return
        val candidates = cities.values.filter { city ->
            city.id !in busy && city.id !in unhealthy && city.pendingDeletion == null && city.inventory?.mode == CivStorageMode.PHYSICAL && pendingMaterials(city) > 0 &&
                settlementActive(city)
        }
        if (candidates.isEmpty()) return
        val city = candidates[Math.floorMod(storageCursor++, candidates.size)]
        nextStorageTransferAt = now() + 5_000L
        runCatching { materialize(city.id, background = true) }
            .onSuccess { future -> future.whenComplete { _, failure ->
                if (failure != null)
                    log("materialize stored items ${city.id}", failure, city.id)
            } }
            .onFailure { log("materialize stored items ${city.id}", it, city.id) }
    }

    /** Missing controls after their one-time grant require repair, never a repeating free grant. */
    private fun requireControlBarrel(worldId: UUID, building: CivBuilding) {
        if ((building.type.isInfrastructure || building.type == BuildingType.DECORATION) && building.control == null) return
        val point = building.control ?: throw CivsRejection("control-missing")
        val target = plugin.server.getWorld(worldId) ?: throw CivsRejection("world-unavailable")
        rejectIf(point.y !in target.minHeight until target.maxHeight, "outside-world")
        rejectIf(!target.isChunkLoaded(point.x shr 4, point.z shr 4), "chunk-unloaded")
        if (target.getBlockAt(point.x, point.y, point.z).type != Material.BARREL) throw CivsRejection("control-position-missing",
            mapOf("x" to point.x.toString(), "y" to point.y.toString(), "z" to point.z.toString()))
    }

    private fun prepareControl(cityId: UUID, worldId: UUID, owner: UUID, building: CivBuilding,
        prepareApproach: Boolean = true): CivControlPlacement {
        val point = building.control ?: throw CivsRejection("control-missing")
        val target = plugin.server.getWorld(worldId) ?: throw CivsRejection("world-unavailable")
        rejectIf(point.y !in target.minHeight until target.maxHeight, "outside-world")
        rejectIf(!target.isChunkLoaded(point.x shr 4, point.z shr 4), "chunk-unloaded")
        val block = target.getBlockAt(point.x, point.y, point.z)
        rejectIf(block.type != Material.BARREL && (block.type == Material.BEDROCK || block.state is org.bukkit.block.TileState), "control-obstructed")
        rejectIf(!world.canModify(owner, block, Material.BARREL), "lands-denied")
        val approach = (if (prepareApproach) (-1..1).toList() else emptyList()).mapNotNull { dy ->
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
        val nearby = cities.values.filter { city -> chunkLoading.active(city.id) ||
            plugin.server.onlinePlayers.any { it.world.uid == city.worldId && city.contains(it.location.blockX, it.location.blockZ) } &&
                (city.autonomy != null || plugin.server.onlinePlayers.any { city.canManage(it.uniqueId) }) }
        // Existing paid orders finish with their original receipt before conversion to actual item storage.
        nearby.firstOrNull { it.autonomy == null && it.inventory == null && it.construction == null && it.id !in busy && it.id !in unhealthy }?.let { old ->
            runCatching { mutate(old.id) { city ->
                val buildings = city.buildings.map { if (it.type == BuildingType.DECORATION || it.type.isInfrastructure) it
                    else it.copy(control = it.control ?: it.anchor) }
                val controls = buildings.filter { it.control != null && city.building(it.id)?.control == null }.map {
                    prepareControl(city.id, city.worldId, city.owner, it)
                }
                CivsRules.validate(city.copy(stock = Stock(), inventory = CivInventory.starting(city.stock),
                    buildings = buildings, pendingControls = city.pendingControls.orEmpty() + controls, revision = city.revision + 1))
            } }.onFailure { log("migrate city materials ${old.id}", it, old.id) }
        }
        val controls = nearby.filter { it.id !in busy && it.id !in unhealthy }.flatMap { city -> city.pendingControls.orEmpty().map { city to it } }
        if (controls.isEmpty()) return
        repeat(minOf(16, controls.size)) {
            val (city, pending) = controls[Math.floorMod(controlCursor++, controls.size)]
            if (city.id in busy) return@repeat
            runCatching { installControl(city.id, pending.buildingId) }.onSuccess { future ->
                future.whenComplete { _, failure ->
                    if (failure != null && controlFailures.add(pending.buildingId)) log("install control ${pending.buildingId}", failure, city.id)
                }
            }.onFailure {
                if (controlFailures.add(pending.buildingId)) log("install control ${pending.buildingId}", it, city.id)
            }
        }
    }

    private fun settlementActive(city: Settlement): Boolean = chunkLoading.active(city.id) ||
        plugin.server.onlinePlayers.any { it.world.uid == city.worldId && city.contains(it.location.blockX, it.location.blockZ) }

    private fun rememberedMemberNames(city: Settlement): Settlement = CivsMembership.remember(city,
        (city.members + city.owner).mapNotNull { id ->
            (plugin.server.getPlayer(id)?.name ?: city.memberNames?.get(id.toString())
                ?: recoveredMemberNames[id])?.let { id to it }
        }.toMap())

    /** Recovered playerdata was read on the store executor; live names override it on Paper. */
    private fun pulseMemberNames() {
        val city = cities.values.firstOrNull { it.id !in memberNamesPending && it.id !in unhealthy &&
            it.pendingDeletion == null && rememberedMemberNames(it) != it } ?: return
        memberNamesPending += city.id
        runCatching { afterCityAvailable(city.id) {
            if (cities[city.id]?.pendingDeletion != null || city.id !in cities)
                return@afterCityAvailable CompletableFuture.completedFuture(Unit)
            mutate(city.id) { before ->
                rememberedMemberNames(before).let { if (it == before) before else it.copy(revision = before.revision + 1) }
            }.thenApply { Unit }
        } }.onSuccess { future -> future.whenCompleteSync(tasks) { _, failure ->
            memberNamesPending -= city.id
            if (failure != null) log("remember city member names ${city.id}", failure, city.id)
        } }.onFailure { memberNamesPending -= city.id; log("remember city member names ${city.id}", it, city.id) }
    }

    private fun pulseResearch() {
        val candidates = cities.values.filter { city -> city.id !in busy && city.id !in unhealthy && city.pendingDeletion == null &&
            city.autonomy?.paused != true && settlementActive(city) && CivsResearch.university(city) != null &&
            (CivsResearch.canFund(city) || city.autonomy != null && city.research?.project == null &&
                CivResearchId.entries.any { CivsResearch.reason(city, it) == null }) }
        if (candidates.isEmpty()) return
        val city = candidates[Math.floorMod(researchCursor++, candidates.size)]
        val project = city.research?.project
        val future = runCatching {
            if (project == null) {
                val research = CivResearchId.entries.first { CivsResearch.reason(city, it) == null }
                mutate(city.id) { CivsResearch.begin(it, research, UUID.randomUUID(), now()) }
            } else if (city.autonomy != null) mutate(city.id) { CivsResearch.fund(it, project.id, now()) }
            else inventoryMutation(city.id, { current ->
                CivsResearch.project(current, project.id)
                rejectIf(!CivsResearch.canFund(current), "research-unfunded")
                val university = CivsResearch.university(current) ?: throw CivsRejection("university-required")
                storage.prepareWork(current, university.id, project.cost.materials, emptyMap(), emptyList())
            }, keepViewersOpen = true) { current, inventory ->
                CivsResearch.fund(current, project.id, now(), nativePaid = true).copy(inventory = inventory)
            }
        }
        future.onSuccess { accepted -> accepted.whenCompleteSync(tasks) { saved, failure ->
            if (failure != null) {
                if (CivsDiagnostics.rejection(failure)?.reason !in setOf("materials-missing", "research-unfunded", "storage-unavailable", "chunk-unloaded"))
                    log("advance research funding ${city.id}", failure, city.id)
            } else {
                val current = requireNotNull(saved).research?.project
                CivsDiagnostics.event(if (project == null) "research_started" else "research_funded", "city" to city.id,
                    "project" to current?.id, "research" to current?.research, "cost" to current?.cost,
                    "work_required" to current?.workRequired, "revision" to saved.revision)
            }
        } }.onFailure { failure ->
            if (CivsDiagnostics.rejection(failure)?.reason !in setOf("materials-missing", "research-unfunded", "storage-unavailable", "chunk-unloaded"))
                log("prepare research funding ${city.id}", failure, city.id)
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = false)
    fun controlClicked(event: org.bukkit.event.player.PlayerInteractEvent) {
        if (event.action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return
        val player = event.player
        if (!ready() || selection(player) != null || preview(player) != null) return
        val block = event.clickedBlock ?: return
        val point = CivPosition(block.x, block.y, block.z)
        val autonomous = cities.values.firstOrNull { it.autonomy != null && it.worldId == block.world.uid &&
            it.buildings.any { b -> b.control == point } }
        if (autonomous != null) {
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY)
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY)
            if (!player.hasPermission("arccivs.use") || !::ui.isInitialized) return
            val building = autonomous.buildings.first { it.control == point }
            tasks.runSync { if (player.isOnline && ready()) ui.openBuildingInspection(player, autonomous.id, building.id) }
            return
        }
        val city = city(player)?.takeIf { it.worldId == block.world.uid } ?: return
        val building = city.buildings.firstOrNull { it.control == point } ?: return
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY)
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY)
        if (!player.hasPermission("arccivs.use") || !::ui.isInitialized) return
        tasks.runSync {
            if (player.isOnline && ready()) {
                if (building.type == BuildingType.TOWN_HALL) ui.open(player)
                else ui.openBuilding(player, city.id, building.id)
            }
        }
    }

    private fun saveCapturedCity(plan: CivPlacementPlan, bytes: ByteArray, city: Settlement): CompletableFuture<Settlement> =
        store.saveSchematic(plan.id, bytes).thenCompose { store.savePlan(plan) }.thenCompose { store.save(city) }

    /** Completed building captures retain their actual road grades across later development. */
    private fun loadAutonomousPlans(city: Settlement): CompletableFuture<List<CivPlacementPlan>> {
        require(city.buildings.size <= CivsRules.MAX_SITES)
        val buildings = city.buildings.filter { it.phase == BuildingPhase.ACTIVE }
        val plans = buildings.map { store.loadPlan(it.id) }
        val result = tracked<List<CivPlacementPlan>>()
        CompletableFuture.allOf(*plans.toTypedArray()).thenApplyAsync({
            val saved = plans.mapNotNull { it.join() }
            Triple(saved, CivAutonomousAccess.from(city, saved).routes.values.flatten().mapTo(hashSetOf()) { it.x to it.z },
                CivsRoadMaintenance.prepare(city, saved))
        }, executor).whenCompleteSync(tasks) { captured, failure ->
            if (closed) result.completeExceptionally(CivsRejection("not-ready"))
            else if (failure != null) result.completeExceptionally(failure)
            else {
                val (saved, columns, upkeep) = requireNotNull(captured)
                if (cities[city.id]?.buildings == city.buildings && cities[city.id]?.roadRouteOverrides == city.roadRouteOverrides) {
                    forestryRoutes[city.id] = city.worldId to columns
                    roadMaintenance.publish(city, upkeep)
                }
                result.complete(saved)
            }
        }
        return result
    }

    private fun loadAutonomousAccess(city: Settlement): CompletableFuture<CivAutonomousAccess> =
        loadAutonomousPlans(city).thenApplyAsync({ CivAutonomousAccess.from(city, it) }, executor)

    /** Queued upgrade captures are exact future geometry, never retained built access roads. */
    private fun loadPlanBoardPlans(city: Settlement): CompletableFuture<List<CivPlacementPlan>> {
        require(city.constructionQueue.orEmpty().size <= CivsRules.MAX_SITES)
        val upgrades = city.constructionQueue.orEmpty().mapNotNull(city::building)
            .filter { it.phase == BuildingPhase.ACTIVE }.map { store.loadPlan(queuedDesignId(it)) }
        val active = loadAutonomousPlans(city)
        return active.thenCombineAsync(CompletableFuture.allOf(*upgrades.toTypedArray()), { retained, _ ->
            retained + upgrades.mapNotNull { it.join() }
        }, executor)
    }

    private fun commitAutonomousDesign(city: Settlement, design: CivAutonomousDesign): CompletableFuture<Unit> {
        val blueprint = design.blueprint
        val previous = city.building(blueprint.building.id)
        val result = tracked<Unit>()
        busy += city.id
        val capture = runCatching {
            if (previous == null) world.captureAutonomousSite(city.id, city.owner, city.worldId, blueprint.building, blueprint.cells, design.terrain)
            else world.captureSite(city.id, city.owner, city.worldId, blueprint.building, blueprint.cells, previous)
        }
            .getOrElse { busy -= city.id; return CompletableFuture.failedFuture(it) }
        capture.whenCompleteSync(tasks) { captured, captureFailure ->
            if (captureFailure != null) { busy -= city.id; result.completeExceptionally(captureFailure); return@whenCompleteSync }
            runCatching {
                rejectIf(!ready() || cities[city.id] != city || city.autonomy?.paused != false, "stale-development")
                val control = if (previous == null && !blueprint.building.type.isInfrastructure && blueprint.building.type != BuildingType.DECORATION)
                    prepareControl(city.id, city.worldId, city.owner, blueprint.building,
                        prepareApproach = design.terrain == null) else null
                val placed = if (previous == null) CivsRules.placeSite(city, city.owner, blueprint.building) else city
                val queued = CivsRules.enqueue(placed, city.owner, blueprint.building.id)
                val next = CivsRules.validate(queued.copy(revision = city.revision + 1,
                    autonomy = queued.autonomy?.let { it.copy(layoutHalfWidth = it.layoutHalfWidth ?: CivsSettlementLayout.frame(city).halfWidth) },
                    pendingControls = if (control == null) queued.pendingControls else queued.pendingControls.orEmpty() + control))
                val plan = requireNotNull(captured).copy(id = if (previous == null) blueprint.building.id
                    else queuedUpgradeId(previous.id, blueprint.building.level), capturedSha256 = blueprint.snapshot.fileSha256)
                saveCapturedCity(plan, blueprint.sourceBytes, next).whenCompleteSync(tasks) { saved, saveFailure ->
                    if (saveFailure != null) {
                        busy -= city.id
                        if (CivsDiagnostics.rejection(saveFailure) == null) unhealthy += city.id
                        result.completeExceptionally(saveFailure)
                    } else publish(requireNotNull(saved)).whenCompleteSync(tasks) { _, publishFailure ->
                        busy -= city.id
                        if (publishFailure != null) { unhealthy += city.id; result.completeExceptionally(publishFailure) }
                        else {
                            CivsDiagnostics.event("autonomous_goal_queued", "city" to city.id, "building" to plan.building.id,
                                "type" to plan.building.type, "level" to plan.building.level,
                                "entrance" to plan.building.entrance, "rotation" to plan.building.rotation, "revision" to saved.revision)
                            result.complete(Unit) // The existing controls/queue recovery finishes after this durable intent.
                        }
                    }
                }
            }.onFailure { busy -= city.id; result.completeExceptionally(it) }
        }
        return result
    }

    private fun payBatch(city: Settlement, order: CivConstruction, required: Map<String, Long>): CompletableFuture<Settlement> =
        inventoryMutation(city.id, { current ->
            if (CivsRules.constructionStorageInputs(current, order.buildingId, required).isNotEmpty()) null
            else CivStoragePlan(requireNotNull(current.inventory), emptyList())
        }, keepViewersOpen = true) { before, inventory ->
            CivsRules.payBatch(before, before.owner, order.id, order.workDone, required).copy(inventory = inventory)
        }.whenComplete { saved, failure ->
            if (failure == null && saved?.construction?.payment?.reservedBatch == order.workDone)
                CivsDiagnostics.event("construction_batch_paid", "city" to city.id, "order" to order.id,
                "batch" to order.workDone, "materials" to required, "revision" to saved.revision)
        }

    private fun currentPreview(player: Player): Preview {
        requireReady(player)
        rejectIf(player.uniqueId in previewLoads, "city-busy")
        return (previews[player.uniqueId]?.takeIf { it.expires > now() && it.blueprint.worldId == player.world.uid }
            ?: throw CivsRejection("preview-expired")).also { preview ->
            preview.cityId?.let { managedPlacement(player, it, preview.blueprint.building.type) }
        }
    }
    /** Normal personal authority, with one explicit administrative placement for an existing Auto city. */
    private fun managedPlacement(player: Player, cityId: UUID, type: BuildingType): Settlement {
        requireReady(player)
        val selected = cities[cityId] ?: throw CivsRejection("no-city")
        val city = if (selected.autonomy != null) {
            rejectIf(type != BuildingType.PLAN_BOARD || !player.hasPermission("arccivs.admin"), "not-authorized")
            selected
        } else managed(player).also { rejectIf(it.id != cityId, "not-authorized") }
        rejectIf(city.worldId != player.world.uid, "outside-city")
        rejectIf(city.id in unhealthy, "storage-unavailable")
        rejectIf(city.pendingDeletion != null, "deletion-pending")
        return city
    }
    private fun managed(player: Player, allowDeletion: Boolean = false): Settlement {
        requireReady(player)
        val city = city(player) ?: throw remoteCity(player)?.let {
            CivsRejection("remote-city", mapOf("server" to it.server.value))
        } ?: CivsRejection("no-city")
        rejectIf(city.id in unhealthy, "storage-unavailable")
        rejectIf(city.pendingDeletion != null && !allowDeletion, "deletion-pending")
        return city
    }
    private fun requireReady(player: Player) {
        checkMain()
        rejectIf(!ready(), "not-ready")
        rejectIf(!player.hasPermission("arccivs.use"), "not-authorized")
    }
    private fun requirePreviewSlot(player: Player) {
        rejectIf(player.uniqueId in previewLoads, "city-busy")
        val occupied = previews.keys + selections.keys + previewLoads.keys
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
            pendingFounders.any { (owner, place) -> owner != ignoreFounder && place.worldId == worldId && areasOverlap(center, settings.radius + CivsRules.MAX_TERRITORY_GROWTH, place.center, settings.radius + CivsRules.MAX_TERRITORY_GROWTH) }, "city-overlap")
    }
    private fun updateHealth() {
        health = RuntimeHealthContribution(
            state = when { closed || !initialized && !loading -> RuntimeHealthState.DOWN; loading -> RuntimeHealthState.STARTING; unhealthy.isNotEmpty() -> RuntimeHealthState.DEGRADED; else -> RuntimeHealthState.UP },
            recoveryBacklog = cities.values.count { it.construction != null } + unhealthy.count { it !in cities },
            activeLeases = previews.size + selections.size + construction.activeCount + chunkLoading.retainedChunks,
            schemas = mapOf("city" to 1, "plan" to 1),
            dependencies = mapOf("storage" to (initialized && unhealthy.isEmpty())),
        )
    }
    private fun <T> tracked(): CompletableFuture<T> = CompletableFuture<T>().also(::track)
    private fun track(future: CompletableFuture<*>) {
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
            if (failure != null && rejection == null) CivsDiagnostics.record(Level.WARNING,
                fields.firstOrNull { it.first == "city" }?.second as? UUID,
                "ArcCivs operation $name failed (request=$id)", plugin.logger, failure)
            CivsDiagnostics.event("operation_end", "operation" to name, "request" to id, *actor, *fields,
                "outcome" to if (failure == null) "success" else if (rejection != null) "rejected" else "failed",
                "reason" to rejection?.reason, "detail" to rejection?.values,
                "cause" to CivsDiagnostics.cause(failure)?.javaClass?.simpleName,
                "duration_ms" to (System.nanoTime() - started) / 1_000_000)
        }
    }

    /** Queue intent, then re-read authority, position and stock in the action's normal entrypoint. */
    private fun <T> cityAction(name: String, player: Player, vararg fields: Pair<String, Any?>,
        allowDeletion: Boolean = false, block: (Settlement) -> CompletableFuture<T>): CompletableFuture<T> {
        val inspected = runCatching { managed(player, allowDeletion).id }.getOrNull()
        val context = if (fields.none { it.first == "city" } && inspected != null) arrayOf("city" to inspected, *fields) else fields
        return action(name, player, *context) {
            val cityId = managed(player, allowDeletion).id
            afterCityAvailable(cityId) {
                rejectIf(!player.isOnline, "player-offline")
                val current = managed(player, allowDeletion)
                rejectIf(current.id != cityId, "stale-city")
                block(current)
            }
        }
    }
    private fun rejectIf(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
    private fun now() = clock()
    private fun log(context: String, error: Throwable, cityId: UUID? = null) =
        CivsDiagnostics.record(Level.WARNING, cityId, "ArcCivs could not $context", plugin.logger, error)
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
        layoutDebug.close()
        planBoardSnapshots.close()
        planBoardDrain = runCatching { planBoards.closeAsync() }
            .getOrElse { CompletableFuture.failedFuture(it) }
        autonomousDevelopment.cancel()
        roadMaintenance.clear()
        supply.close()
        HandlerList.unregisterAll(this)
        lastInjury.clear()
        construction.close()
        citizenDrain = runCatching { citizens.closeAsync() }
            .getOrElse { CompletableFuture.failedFuture(it) }
        storage.cancelCollectedPersistence()
        runCatching { chunkLoading.close() }.onFailure { log("release settlement chunks", it) }
        testingPermissions.close()
        physics.close()
        autonomousPlacement.close()
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
        cityAvailability.clear()
        pendingConfirmations.clear()
        pendingBuildingOrders.clear()
        updateHealth()
    }

    fun drained(): CompletableFuture<Void> = CompletableFuture.allOf(renderer.drained(), boundary.drained(), citizenDrain,
        supply.drained(), layoutDebug.drained(), planBoardDrain, planBoardSnapshots.drained())

    companion object {
        internal fun areasOverlap(a: CivPosition, ar: Int, b: CivPosition, br: Int): Boolean =
            kotlin.math.abs(a.x.toLong() - b.x) <= ar.toLong() + br && kotlin.math.abs(a.z.toLong() - b.z) <= ar.toLong() + br
    }
}
