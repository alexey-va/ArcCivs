package ru.arc.civs

import org.bukkit.Bukkit
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
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.network.BackendTransfer
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
    planningExecutor: Executor = executor,
) : CivsUiHost, Listener, AutoCloseable {
    private var cities = linkedMapOf<UUID, Settlement>()
    private val busy = mutableSetOf<UUID>()
    private val cityAvailability = linkedMapOf<UUID, ArrayDeque<CompletableFuture<Unit>>>()
    private val unhealthy = mutableSetOf<UUID>()
    private val completions = mutableSetOf<CompletableFuture<*>>()
    private val lastInjury = mutableMapOf<UUID, CivDeathContext>()
    private var initialized = false
    private var closed = false
    private var citizenDrain = CompletableFuture.completedFuture<Void>(null)
    private var planBoardDrain = CompletableFuture.completedFuture<Void>(null)
    private var loading = false
    private var loadFailure: String? = null
    private var geometryGeneration = 0L
    private val records = CivsCityRecords({ cities }, busy, unhealthy, { closed }, { loading },
        { loadFailure }, { geometryGeneration }, { completions.size })
    private val world = worldAdapter ?: CivsWorld(plugin, tasks, navigationSettings = settings.navigation) { cities.values.toList() }
    private val workstations = CivsWorkstations(blueprints::workstations, plugin.logger)
    private val roadMaintenance = CivsRoadMaintenance(world,
        registerSurface = { cityId, position -> footTraffic.registerSurface(cityId, position) },
        paymentPossible = ::roadUpkeepPaymentPossible)
    private val layoutDebug = CivsLayoutDebug(plugin.dataFolder.toPath(), tasks, executor, { id ->
        store.loadPlan(id).whenComplete { _, failure ->
            if (failure != null) plugin.logger.log(Level.WARNING, "ArcCivs layout export could not read plan=$id", failure)
        }
    }, clock, blueprints::fortification)
    private val autonomousPlacement = CivsAutonomousPlacement(blueprints, world, executor, { city -> cityPlans.loadAutonomousAccess(city) },
        store::saveSchematic, store::deleteSchematic, plugin.server.unsafe.dataVersion)
    private val autonomousDevelopment = CivsAutonomousDevelopment(plugin, tasks, world, blueprints,
        autonomousPlacement, executor, { cities }, busy, unhealthy, { construction.isPlacing(it) }, ::ready, { closed },
        { city -> cityPlans.loadAutonomousPlans(city) }, ::settlementActive, { id, action -> afterCityAvailable(id, action) },
        { id, transform -> mutate(id, transform = transform) },
        { id, transform -> afterCityAvailable(id) { mutate(id, transform = transform) } },
        { city, design -> admitRoadDevelopment(city) { buildingRequests.commitAutonomousDesign(city, design) } }, { construction.pulse() }, clock,
        developmentFinished = roadMaintenance::developmentFinished,
        storageCapacityPressure = ::storageCapacityPressure)
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
    }, { city -> cityPlans.loadPlanBoardPlans(city) })
    private val planBoards = CivsPlanBoardRenderer(plugin, tasks, nativeWork, executor, planBoardSnapshots::snapshot)
    private val protection = CivsProtection(cities = { cities.values.toList() }, available = { ready() }, protectedWorlds = settings.worlds,
        worldLocked = storage::isWorldLocked, forestryPath = { worldId, x, z ->
            cityPlans.forestryPath(worldId, x, z)
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
        storage::isWorldLocked, { it in busy || it in unhealthy }, { cityPlans.forestryRoute(it.id) }, settings = settings.worksites)
    private val chunkLoading = CivsChunkLoading(plugin, settings.maximumLoadedChunks,
        permissions = CivsChunkPermissions(plugin, clock), clock = clock).also { storage.collectedChunkLease = it::retainLoaded }
    private val testingPermissions = CivsChunkPermissions(plugin, clock, CIVS_TESTING_PERMISSION)
    private val controlInstallations = CivsControlInstallations(plugin, world, storage, chunkLoading, { cities },
        busy, unhealthy, ::ready) { id, transform -> mutate(id, transform = transform) }
    private val citizens = (citizenRuntime ?: CivsCitizens(plugin, { if (ready()) cities.values.filter { it.id !in unhealthy && it.pendingDeletion == null } else emptyList() },
        ::deliver, { player, cityId, workerId ->
            if (cities[cityId]?.autonomy != null && player.hasPermission("arccivs.admin")) ui.openWorker(player, cityId, workerId)
            else if (city(player)?.id == cityId) ui.openWorker(player, cityId, workerId)
        }, settings.maximumNpcs, settings.viewerRange, hurt = ::hurtResident, footstep = footTraffic::record,
        surfaceSpeed = footTraffic::speed, workPlan = { city, worker -> roadMaintenance.plan(city, worker) ?: worksites.plan(city, worker) },
        workIdleReason = worksites::idleReason, courierPlan = ::courierPlan, pickupCargo = ::pickupCargo, dropoffCargo = ::dropoffCargo,
        doorBlocked = { worldId, point -> !ready() || storage.isWorldLocked(worldId, point) },
        productionReady = ::productionReady, workInputsReady = ::workInputsReady, workStatusInputs = ::workStatusInputs,
        workstation = workstations::select,
        backgroundActive = chunkLoading::active,
        navigationTasks = tasks, navigationWork = nativeWork, navigationSettings = settings.navigation,
        courierFailed = { cityId, workerId, cargo ->
            if (supply.plan(cityId, workerId)?.cargo == cargo) supply.release(cityId, workerId, failed = true)
        },
        workPositionFailed = { city, worker, work ->
            if (work.upkeep != null) { roadMaintenance.invalidate(worker.id); true }
            else worksites.rejectStanding(city, worker, work)
        },
        residentSupplySource = ::residentSupplySource,
        prepareSupplies = { c, w, b -> residentSupplies.prepareResidentSupplies(c, w, b) },
        residentFoodSource = { city, worker -> residentSupplies.citizensFeetForFood(worker.id)?.let {
            storage.residentFoodSource(city, worker, it)
        } },
        tavernFoodSource = { city, worker -> residentSupplies.citizensFeetForFood(worker.id)?.let {
            storage.residentFoodSource(city, worker, it, tavernService = true)
        } },
        pickupResidentFood = ::pickupResidentFood,
        residentFoods = residentFoods,
        craftPlan = ::craftPlan,
        craftDelivery = { cityId, workerId, assignment, cycle, nextAt, jobId, at ->
            residentSupplies.deliverCraft(cityId, workerId, assignment, cycle, nextAt, jobId, at)
        },
        craftSupplies = { c, w, b, job -> residentSupplies.prepareResidentSupplies(c, w, b, job) },
        constructionFocus = { cityId, orderId -> construction.focus(cityId, orderId) },
        constructionSite = { cityId, orderId -> construction.site(cityId, orderId) },
        collectionAvailable = ::collectionAvailable, collectionHome = ::collectedHome,
        pickupGround = ::pickupGround, depositCollected = ::depositCollected,
        immortalResidents = { id -> cities[id]?.let(::immortalResidents) == true })).also(onCitizensCreated)
    @Volatile private var health = RuntimeHealthContribution(state = RuntimeHealthState.STARTING)
    private val supply = CivsSupplyDispatcher(tasks, nativeWork,
        { cityId, retained -> cargoRequests.captureSupply(cityId, retained) },
        { snapshot, plans -> cargoRequests.validateSupply(snapshot, plans) }, supplyExecutor, clock)

    private val construction: CivsConstructionRuntime = CivsConstructionRuntime(plugin, tasks, world,
        blueprints, store, executor, physics, storage, citizens, footTraffic, { cities }, busy, unhealthy,
        ::ready, { closed }, ::settlementActive, autonomousDevelopment::accessReady,
        { id, action -> afterCityAvailable(id, action) }, { id, transform -> mutate(id, transform = transform) },
        { city, building -> admitRoadDevelopment(city) { buildingRequests.activateBuilding(city, building) } },
        { city, order, required -> buildingRequests.payBatch(city, order, required) }, ::updateHealth, clock)

    private val inventoryJournal = CivsInventoryJournal(plugin, tasks, store, storage, { cities }, busy,
        unhealthy, { closed }, { city -> publish(city) }, { id, transform -> mutate(id, transform = transform) },
        ::track, { tx -> cargoRequests.validateNativeCollected(tx) }, ::resurrectionRejection, ::requestResurrectionAdmission,
        ::inventoryRetired, ::updateHealth, clock)

    private val controlRelocations = CivsControlRelocations(plugin, tasks, world, storage, controlInstallations,
        blueprints::interiorControls, { cities.values }, { city -> ready() && city.id !in busy && city.id !in unhealthy &&
            !inventoryJournal.pending(city.id) && settlementActive(city) }, ::relocateControlStorage,
        { id, transform -> afterCityAvailable(id) { mutate(id, transform = transform) } }, clock,
        blocked = citizens::growthBlocked)

    private val workerCommits = CivsWorkerCommits(this, tasks, storage, worksites, citizens, world,
        physics, inventoryJournal, roadMaintenance, construction, blueprints, { cities })

    private val residentManagement = CivsResidentManagement(this, storage, worksites, citizens)

    private val forestryLeafLoot = CivsForestryLeafLoot({ cities }, ::ready, { city, point -> storage.foodCanModify(city, point) },
        { cityId -> cargoRequests.collectionAvailable(cityId) }, { cityId, workerId, entities -> cargoRequests.collectForestryLeafLoot(cityId, workerId, entities) })

    private val residentLife = CivsResidentLifeRuntime(plugin, tasks, citizens, chunkLoading, settings.viewerRange,
        { cities }, busy, unhealthy, ::settlementActive,
        { id, action -> afterCityAvailable(id, action) }, { id, action -> afterCityAvailable(id, action) },
        { id, transform -> mutate(id, transform = transform) }, clock, residentFoods)

    private val placement: CivsPlacementSession
    private val buildingRequests: CivsBuildingRequests
    private val residentSupplies: CivsResidentSupplies
    private val cargoRequests: CivsCargoRequests
    private val storageActions: CivsStorageActions
    private val deletion: CivsDeletionRequests
    private val diagnostics: CivsCityDiagnostics
    private val membership: CivsCityMembership
    private val cityResearch: CivsCityResearch
    private val cityPlans: CivsCityPlans
    private val villageSites: CivsVillageSites

    init {
        controlInstallations.controlOccupied = citizens::growthBlocked
        placement = CivsPlacementSession(this,
            records,
            buildingRequestsOwner = { buildingRequests },
            diagnosticsOwner = { diagnostics },
            plugin = plugin,
            tasks = tasks,
            settings = settings,
            blueprints = blueprints,
            renderer = renderer,
            boundary = boundary)
        buildingRequests = CivsBuildingRequests(this,
            records,
            placementOwner = { placement },
            storageActionsOwner = { storageActions },
            membershipOwner = { membership },
            tasks = tasks,
            settings = settings,
            store = store,
            blueprints = blueprints,
            executor = executor,
            world = world,
            autonomousPlacement = autonomousPlacement,
            controlInstallations = controlInstallations,
            construction = construction)
        residentSupplies = CivsResidentSupplies(this,
            records,
            cargoRequestsOwner = { cargoRequests },
            plugin = plugin,
            tasks = tasks,
            residentFoods = residentFoods,
            world = world,
            roadMaintenance = roadMaintenance,
            storage = storage,
            citizens = citizens,
            supply = supply,
            construction = construction,
            inventoryJournal = inventoryJournal)
        cargoRequests = CivsCargoRequests(this,
            records,
            residentSuppliesOwner = { residentSupplies },
            plugin = plugin,
            tasks = tasks,
            residentFoods = residentFoods,
            world = world,
            storage = storage,
            citizens = citizens,
            supply = supply,
            construction = construction,
            inventoryJournal = inventoryJournal)
        storageActions = CivsStorageActions(this,
            records,
            residentSuppliesOwner = { residentSupplies },
            cargoRequestsOwner = { cargoRequests },
            researchOwner = { cityResearch },
            plugin = plugin,
            tasks = tasks,
            store = store,
            executor = executor,
            blueprints = blueprints,
            world = world,
            roadMaintenance = roadMaintenance,
            storage = storage,
            worksites = worksites,
            controlInstallations = controlInstallations,
            supply = supply,
            construction = construction,
            inventoryJournal = inventoryJournal)
        deletion = CivsDeletionRequests(this,
            records,
            placementOwner = { placement },
            buildingRequestsOwner = { buildingRequests },
            membershipOwner = { membership },
            plugin = plugin,
            tasks = tasks,
            world = world,
            controlCleanup = controlCleanup,
            boundary = boundary,
            storage = storage,
            controlInstallations = controlInstallations,
            citizens = citizens,
            construction = construction,
            inventoryJournal = inventoryJournal)
        diagnostics = CivsCityDiagnostics(this,
            records,
            placementOwner = { placement },
            buildingRequestsOwner = { buildingRequests },
            plugin = plugin,
            blueprints = blueprints,
            world = world,
            layoutDebug = layoutDebug,
            autonomousDevelopment = autonomousDevelopment,
            storage = storage,
            worksites = worksites,
            chunkLoading = chunkLoading,
            testingPermissions = testingPermissions,
            citizens = citizens,
            supply = supply,
            construction = construction,
            inventoryJournal = inventoryJournal,
            residentLife = residentLife,
            lastInjury = lastInjury)
        membership = CivsCityMembership(this,
            records,
            buildingRequestsOwner = { buildingRequests },
            plugin = plugin,
            tasks = tasks,
            store = store,
            executor = executor,
            backendTransfer = backendTransfer)
        cityResearch = CivsCityResearch(this,
            records,
            tasks = tasks,
            storage = storage,
            researchSupply = { city ->
                val snapshot = supply.observation(city.id)
                val observation = snapshot?.inputKey as? CivSupplyObservation
                snapshot?.takeIf { observation != null && storage.supplyCaptureCurrent(city, observation.capture) }
            })
        cityPlans = CivsCityPlans(this,
            records,
            tasks = tasks,
            store = store,
            executor = executor,
            roadMaintenance = roadMaintenance)
        villageSites = CivsVillageSites(plugin, tasks, settings, blueprints, world, planningExecutor,
            cities = { cities.values.toList() }, ready = ::ready,
            checkSpace = { worldId, center -> buildingRequests.checkSettlementSpace(worldId, center) },
            retainLoaded = chunkLoading::retainLoaded, found = buildingRequests::foundAutomatic,
            chunksReady = chunkLoading::handoffReady)
        storage.residentFoods = residentFoods
        storage.forestryLeafCurrentCity = { cities[it] }
        storage.relocationCurrentCity = { cities[it] }
        storage.foodCanModify = { city, position ->
            plugin.server.getWorld(city.worldId)?.takeIf { position.y in it.minHeight until it.maxHeight &&
                it.isChunkLoaded(position.x shr 4, position.z shr 4) }
                ?.getBlockAt(position.x, position.y, position.z)?.let { world.canModify(city.owner, it) } == true
        }
        worksites.forestrySurplus = residentSupplies::forestrySurplus
        worksites.mutationBlocked = citizens::growthBlocked
        worksites.residentFeet = citizens::residentFeet
        citizens.residentLifeEnabled = true
        citizens.returnResidentOutputs = { cityId, workerId, buildingId -> residentSupplies.returnResidentOutputs(cityId, workerId, buildingId) }
        citizens.finishResidentMeal = residentLife::finishMeal
        citizens.deliverTavernMeal = { cityId, delivery -> residentSupplies.deliverTavernMeal(cityId, delivery) }
        citizens.finishResidentRest = residentLife::finishRest
        citizens.finishResidentLesson = residentLife::finishLesson
        citizens.worksiteTerrainChanged = worksites::changed
        citizens.worksiteWorldUnloaded = worksites::forgetWorld
        citizens.worksiteChunkUnloaded = worksites::forgetChunk
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.pluginManager.registerEvents(protection, plugin)
        plugin.server.pluginManager.registerEvents(forestryLeafLoot, plugin)
        plugin.server.servicesManager.register(ArcBlockProtectionService::class.java, sharedProtection, plugin, ServicePriority.Normal)
        tasks.runTimer(1, 1) {
            chunkLoading.pulse(if (ready()) cities.values.filter { it.id !in unhealthy } else emptyList(),
                if (ready()) inventoryJournal.pendingNativeChunks() else emptyList())
            testingPermissions.pulse(if (ready()) cities.values.filter { it.pendingDeletion == null }.mapNotNull { city ->
                plugin.server.getWorld(city.worldId)?.let { city.owner to it.name }
            } else emptyList())
            resumeCityActions()
            if (ready()) supply.pulse()
            if (ready()) forestryLeafLoot.pulse()
            if (ready()) citizens.activateNavigating()
        }
        tasks.runTimer(5, 5) {
            if (ready()) {
                cargoRequests.pulseSupply()
                worksites.pulse(cities.values)
                citizens.pulseFieldWork(now())
            }
        }
        tasks.runTimer(20, 20) { pulse() }
    }

    fun start(): CompletableFuture<Unit> = loadCities()
    fun health(): RuntimeHealthContribution = health

    internal fun foundVillages(referenceId: UUID, count: Int, prefix: String, batch: Int = 0): CompletableFuture<Any> {
        checkMain()
        rejectIf(!ready(), "not-ready")
        val reference = cities[referenceId] ?: throw CivsRejection("city-not-found")
        rejectIf(reference.autonomy == null || reference.pendingDeletion != null, "invalid-reference-city")
        rejectIf(!testingAllowed(reference), "not-authorized")
        return villageSites.search(reference, count, prefix, batch = batch).thenApply { it }
    }

    internal fun villageSearchStatus(): Map<String, Any?> = villageSites.status()

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

    private fun changedSettlementSetting(city: Settlement, setting: CivSettlementSetting, enabled: Boolean): Settlement {
        val before = city.settings ?: CivSettlementSettings()
        val after = before.with(setting, enabled)
        return if (before == after) city else CivsRules.validate(city.copy(settings = after, revision = Math.addExact(city.revision, 1)))
    }

    internal fun testingAllowed(city: Settlement): Boolean = plugin.server.getWorld(city.worldId)?.let {
        testingPermissions.allowed(city.owner, it.name)
    } == true

    internal fun immortalResidents(city: Settlement): Boolean = CivsResidentLife.autonomousImmortality(city) ||
        city.settings?.immortalResidents == true && testingAllowed(city)
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
    /** UI admission is blocked only by recovery/deletion, never by an ordinary in-flight save. */
    override fun cityBusy(player: Player): Boolean = city(player)?.let { it.id in unhealthy || it.pendingDeletion != null } == true
    override fun foundingAllowed(): Boolean = settings.worlds.isNotEmpty()
    override fun activeNpcCount(): Int = citizens.activeCount()

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

    /** Main-thread reservation wait; callers re-read and validate state after every handoff. */
    internal fun <T> afterCityAvailable(cityId: UUID, action: () -> CompletableFuture<T>): CompletableFuture<T> {
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
                    immortal = immortalResidents(it)) }.thenApply { saved ->
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

    internal fun corpseCity(player: Player, cityId: UUID): Settlement {
        requireReady(player)
        val city = cities[cityId] ?: throw CivsRejection("no-city")
        rejectIf(!(if (city.autonomy != null) player.hasPermission("arccivs.admin") else city.canManage(player.uniqueId)), "not-authorized")
        rejectIf(city.id in unhealthy, "storage-unavailable")
        rejectIf(city.pendingDeletion != null, "deletion-pending")
        return city
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

    override fun corpseItems(player: Player, workerId: UUID) = residentManagement.corpseItems(player, workerId)
    override fun corpseItems(player: Player, cityId: UUID, workerId: UUID) = residentManagement.corpseItems(player, cityId, workerId)
    override fun transferCorpseItem(player: Player, workerId: UUID, npcSlot: Int, click: org.bukkit.event.inventory.ClickType) =
        residentManagement.transferCorpseItem(player, workerId, npcSlot, click)
    override fun transferCorpseItem(player: Player, cityId: UUID, workerId: UUID, npcSlot: Int, click: org.bukkit.event.inventory.ClickType) =
        residentManagement.transferCorpseItem(player, cityId, workerId, npcSlot, click)
    fun resurrect(player: Player, cityId: UUID, workerId: UUID, diedAt: Long) = residentManagement.resurrect(player, cityId, workerId, diedAt)
    override fun supplyWorker(player: Player, workerId: UUID, material: String) = residentManagement.supplyWorker(player, workerId, material)
    override fun returnWorkerItem(player: Player, workerId: UUID, material: String) = residentManagement.returnWorkerItem(player, workerId, material)
    override fun feedWorker(player: Player, workerId: UUID) = residentManagement.feedWorker(player, workerId)
    override fun residentItems(player: Player, workerId: UUID) = residentManagement.residentItems(player, workerId)
    override fun residentItems(player: Player, cityId: UUID, workerId: UUID) = residentManagement.residentItems(player, cityId, workerId)
    override fun toolRequest(player: Player, workerId: UUID) = residentManagement.toolRequest(player, workerId)
    override fun giveRequestedTool(player: Player, workerId: UUID) = residentManagement.giveRequestedTool(player, workerId)
    override fun workerRequest(player: Player, workerId: UUID) = residentManagement.workerRequest(player, workerId)
    override fun giveWorkerRequest(player: Player, workerId: UUID, material: String) = residentManagement.giveWorkerRequest(player, workerId, material)
    override fun buildingRequest(player: Player, buildingId: UUID) = residentManagement.buildingRequest(player, buildingId)
    override fun giveBuildingRequest(player: Player, buildingId: UUID, material: String) = residentManagement.giveBuildingRequest(player, buildingId, material)
    override fun summonWorker(player: Player, buildingId: UUID) = residentManagement.summonWorker(player, buildingId)
    override fun transferResidentItem(player: Player, workerId: UUID, npcSlot: Int?, playerSlot: Int?, click: org.bukkit.event.inventory.ClickType) =
        residentManagement.transferResidentItem(player, workerId, npcSlot, playerSlot, click)
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
        rejectIf(busy.isNotEmpty() || construction.activeCount != 0 || placement.loadingCount != 0, "city-busy")
        plugin.reloadConfig()
        loadCities(CivsSettings.chunkLoadingMaximum(plugin.config, plugin.logger::warning))
    }

    private fun deliver(cityId: UUID, workerId: UUID, assignment: UUID, cycle: Long, nextAt: Long, constructionId: UUID?, at: Long): CompletableFuture<Settlement> =
        workerCommits.deliver(cityId, workerId, assignment, cycle, nextAt, constructionId, at)
    private fun validateUpkeepNative(tx: CivInventoryTransaction): CivInventoryApplyResult? = workerCommits.validateUpkeepNative(tx)
    private fun validateMineNative(tx: CivInventoryTransaction): CivInventoryApplyResult? = workerCommits.validateMineNative(tx)

    internal fun upkeepRequirements(city: Settlement): Map<UUID, Map<String, Long>> = roadMaintenance.requirements(city)

    private fun roadUpkeepPaymentPossible(city: Settlement): Boolean? {
        val snapshot = supply.observation(city.id)
        val possible = CivsRoadMaintenance.paymentPossible(city, snapshot)
        if (possible != false) return possible
        val observation = snapshot?.inputKey as? CivSupplyObservation ?: return null
        return if (storage.supplyCaptureCurrent(city, observation.capture)) false else null
    }

    private fun admitRoadDevelopment(city: Settlement, action: () -> CompletableFuture<Unit>): CompletableFuture<Unit> {
        if (!roadMaintenance.admitDevelopment(city)) return CompletableFuture.failedFuture(CivsRejection("city-busy"))
        val result = runCatching(action).getOrElse {
            roadMaintenance.developmentFinished(city.id)
            return CompletableFuture.failedFuture(it)
        }
        result.whenCompleteSync(tasks) { _, _ -> roadMaintenance.developmentFinished(city.id) }
        return result
    }

    /** Move existing exact items before upgrade capture; no order or world change is authorized by this receipt. */
    internal fun relocateUpgradeStorage(city: Settlement, target: CivBuilding,
        cells: List<ArcSchematicBlock>, freshAdmission: () -> Unit = {}): CompletableFuture<Settlement> {
        checkMain()
        rejectIf(!ready(), "not-ready")
        rejectIf(cities[city.id] != city || city.id in busy || city.id in unhealthy, "city-busy")
        val previous = city.building(target.id)
        if (previous == null || previous.phase != BuildingPhase.ACTIVE || previous.type.isInfrastructure || target.level != previous.level + 1)
            return CompletableFuture.completedFuture(city)
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        busy += city.id
        val result = tracked<Settlement>()
        fun abort(failure: Throwable?) {
            storage.unlock(id)
            busy -= city.id
            if (failure == null) result.complete(city) else result.completeExceptionally(failure)
        }
        CompletableFuture.supplyAsync({
            require(cells.size in 1..CivsStore.MAX_PLAN_BLOCKS)
            cells.associate { CivPosition(it.position.x, it.position.y, it.position.z) to it.blockData }
                .also { require(it.size == cells.size) }
        }, executor).whenCompleteSync(tasks) { future, failure ->
            if (failure != null) return@whenCompleteSync abort(failure)
            val tx = runCatching {
                rejectIf(!ready() || cities[city.id] != city || city.id !in busy, "stale-city")
                freshAdmission()
                val move = storage.prepareUpgradeRelocation(city, target, requireNotNull(future))
                    ?: run { abort(null); return@whenCompleteSync }
                CivInventoryTransaction(id, city.id, city.revision, city.copy(revision = city.revision + 1),
                    emptyList(), containerRelocation = move).also { storage.seal(it) }
            }.getOrElse { abort(it); return@whenCompleteSync }
            inventoryJournal.submitRelocation(city, tx, freshAdmission).whenCompleteSync(tasks) { saved, journalFailure ->
                if (journalFailure == null) result.complete(requireNotNull(saved)) else result.completeExceptionally(journalFailure)
            }
        }
        return result
    }

    /** Same-level control moves use the same exact slot receipt as upgrade evacuation. */
    private fun relocateControlStorage(city: Settlement, building: CivBuilding, from: CivPosition,
        to: CivPosition): CompletableFuture<Settlement> {
        checkMain()
        rejectIf(!ready(), "not-ready")
        rejectIf(cities[city.id] != city || city.id in busy || city.id in unhealthy, "city-busy")
        val id = UUID.randomUUID()
        storage.reserve(city, id)
        busy += city.id
        val tx = try {
            val move = storage.prepareControlRelocation(city, building, from, to)
            if (move == null) {
                storage.unlock(id)
                busy -= city.id
                return CompletableFuture.completedFuture(city)
            }
            CivInventoryTransaction(id, city.id, city.revision, city.copy(revision = city.revision + 1),
                emptyList(), containerRelocation = move).also(storage::seal)
        } catch (failure: Throwable) {
            storage.unlock(id)
            busy -= city.id
            return CompletableFuture.failedFuture(failure)
        }
        val result = inventoryJournal.submitRelocation(city, tx) {
            rejectIf(!ready() || cities[city.id] != city || city.id !in busy, "stale-city")
        }
        track(result)
        return result
    }

    internal fun inventoryMutation(
        cityId: UUID, prepare: (Settlement) -> CivStoragePlan?,
        sourcePlayer: Player? = null,
        keepViewersOpen: Boolean = false,
        freshAdmission: ((Settlement) -> Unit)? = null,
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
            if (prepared.cargoPickup != null) requireNotNull(freshAdmission)
            CivInventoryTransaction(id, city.id, city.revision, after, prepared.changes, prepared.playerDebit,
                prepared.residentTransfer, prepared.worldChanges, mine = prepared.mine,
                autoSupplyBuildingId = prepared.autoSupplyBuildingId, cargoDropoff = prepared.cargoDropoff,
                cargoPickup = prepared.cargoPickup)
                .also { storage.seal(it, keepViewersOpen) }
        } catch (failure: Exception) { storage.unlock(id); throw failure }
        return if (tx.cargoPickup != null)
            inventoryJournal.submitCargoPickup(city, tx) { requireNotNull(freshAdmission)(city) }
        else inventoryJournal.submit(tx)
    }

    /** Native player-only operations share the existing exact receipt without inventing town stock. */
    internal fun nativePlayerMutation(cityId: UUID, player: Player, keepViewersOpen: Boolean = false,
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
                residentManagement.corpse(player, corpseCity(player, current.id), debit.corpse.worker.id, debit.corpse.diedAt)
                val actual = corpseLocation(current.id, debit.corpse.worker.id, debit.corpse.diedAt)
                rejectIf(actual == null || actual.world?.uid != current.worldId ||
                    actual.distanceSquared(accepted) > .0001, "corpse-unavailable")
            }
        }.exceptionOrNull()
    }

    private fun inventoryRetired(tx: CivInventoryTransaction, recoveryPath: Boolean): Settlement {
        tx.containerRelocation?.let { move ->
            storage.refreshBuilding(tx.after, move.building)
            CivsDiagnostics.event("upgrade_storage_relocated", "city" to tx.cityId, "transaction" to tx.id,
                "building" to move.building.id, "containers" to move.containers.size, "slots" to move.changes().size,
                "recovery_path" to recoveryPath)
        }
        tx.upkeep?.let { receipt ->
            val work = receipt.work
            val upkeep = requireNotNull(work.upkeep)
            if (upkeep.routeOverride != null) {
                val gradeLoad = roadMaintenance.awaitGradeNetwork(tx.after)
                cityPlans.loadAutonomousPlans(tx.after).whenCompleteSync(tasks) { _, failure ->
                    roadMaintenance.gradeNetworkCompleted(tx.cityId, gradeLoad)
                    if (failure != null) {
                        log("reload repaired autonomous road ${tx.cityId}", failure, tx.cityId)
                    }
                }
            } else roadMaintenance.upkeepRetired(tx.after, upkeep)
            val surfaceChange = work.changes.firstOrNull { it.position == upkeep.floor }
            footTraffic.registerSurface(tx.cityId, upkeep.floor)
            CivsDiagnostics.event("road_upkeep_committed", "city" to tx.cityId, "worker" to work.workerId,
                "cycle" to work.cycle, "transaction" to tx.id, "revision" to tx.after.revision,
                "source_building" to upkeep.source.id, "floor" to upkeep.floor,
                "before" to surfaceChange?.before, "after" to upkeep.after,
                "surface_changed" to (surfaceChange != null),
                "cleared_cover" to work.changes.count { it.position != upkeep.floor },
                "stone_spent" to receipt.stockBefore.stone - tx.after.stock.stone,
                "native_cobblestone_spent" to (work.inputs["COBBLESTONE"] ?: 0L),
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
                transfer.forestryLeafLoot != null -> "forestry_leaf_loot_collected"
                transfer.food?.materialized?.let { it > 0 } == true -> "resident_food_materialized"
                transfer.food != null -> "resident_food_picked_up"
                transfer.ground.isNotEmpty() -> "resident_ground_collected"
                else -> "resident_home_deposited"
            }
            CivsDiagnostics.event(event,
                "city" to tx.cityId, "worker" to transfer.workerId, "transaction" to tx.id,
                "revision" to tx.after.revision, "homeId" to transfer.homeId, "items" to kotlin.math.abs(items),
                "food_stock_before" to transfer.food?.stockBefore?.food, "food_stock_after" to tx.after.stock.food,
                "building" to (transfer.forestryLeafLoot?.buildingId ?: transfer.food?.buildingId),
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

    internal fun mutate(cityId: UUID, allowDeletion: Boolean = false, transform: (Settlement) -> Settlement): CompletableFuture<Settlement> {
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
        villageSites.cancel("reload")
        loading = true
        planBoardSnapshots.retain(emptySet())
        planBoards.pulse(emptyList())
        autonomousDevelopment.reset()
        cityPlans.clear()
        construction.resetSites()
        roadMaintenance.clear()
        supply.clear()
        cargoRequests.clearObservations()
        loadFailure = null
        storage.cancelCollectedPersistence()
        chunkLoading.reset(maximumLoadedChunks)
        placement.cancelAll("reload")
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
                membership.recoveredNames(recovered)
                residentLife.resetMinuteClock()
                citizens.reconcileResidents()
                protection.publish(prepared)
                geometryGeneration++
                unhealthy.clear()
                unhealthy.addAll(inventoryJournal.pendingCityIds())
                construction.clearQueueFailures()
                deletion.clearFailures()
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
    internal fun publish(city: Settlement): CompletableFuture<Unit> = publish(city.id, city)

    internal fun publish(cityId: UUID, city: Settlement?): CompletableFuture<Unit> {
        if (closed) return CompletableFuture.failedFuture(CivsRejection("not-ready"))
        val previous = cities[cityId]
        if (city != null && previous?.buildings == city.buildings && previous?.construction?.plannedBuilding == city.construction?.plannedBuilding && previous.members == city.members &&
            previous.inventory?.containers == city.inventory?.containers && previous.pendingControls == city.pendingControls && previous.pendingDeletion == city.pendingDeletion) {
            cities[city.id] = city
            if (!CivsAutonomy.legacyStock(city) && civSupplyInputs(previous) != civSupplyInputs(city)) supply.request(city.id)
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
                if (city == null) { supply.remove(cityId); cargoRequests.forgetObservation(cityId); cityPlans.remove(cityId) }
                else if (!CivsAutonomy.legacyStock(city)) supply.request(cityId)
                protection.publish(requireNotNull(prepared))
                geometryGeneration++
            }.onSuccess { result.complete(Unit) }.onFailure(result::completeExceptionally)
        }
        return result
    }

    private fun pulse() {
        if (closed) return
        construction.retain()
        placement.expire()
        citizens.pulse(now())
        plugin.server.onlinePlayers.forEach({ player -> placement.refreshBoundary(player) })
        if (ready()) {
            cities.values.asSequence().filter { it.id !in busy && it.id !in unhealthy && it.pendingDeletion == null }
                .forEach(roadMaintenance::backfillSurfaces)
            inventoryJournal.recover()
            pulseCorpses()
            deletion.pulseDeletions()
            controlInstallations.pulse()
            controlRelocations.pulse()
            residentLife.pulseImmigration()
            cityResearch.pulseResearch()
            residentLife.pulseAutoAssignments()
            residentLife.pulsePopulation()
            membership.pulseMemberNames()
            storageActions.pulseStorage()
            storageActions.pulseAutoStorage()
            residentSupplies.pulseAutoSupplies()
            residentSupplies.pulseResidentFood()
            residentSupplies.pulseTestingSupplies()
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

    private fun pulseCorpses() {
        lastInjury.keys.retainAll(cities.values.flatMap { it.workers }.mapTo(hashSetOf(), CivWorker::id))
        val city = cities.values.firstOrNull { it.id !in busy && it.id !in unhealthy &&
            it.corpses.orEmpty().any { corpse -> corpse.expiresAt <= now() } } ?: return
        mutate(city.id) { current -> current.copy(corpses = current.corpses.orEmpty().filter { it.expiresAt > now() },
            revision = current.revision + 1) }.whenComplete { _, error ->
            if (error != null) log("expire resident corpses ${city.id}", error, city.id)
        }
    }

    internal fun settlementActive(city: Settlement): Boolean = chunkLoading.active(city.id) ||
        plugin.server.onlinePlayers.any { it.world.uid == city.worldId && city.contains(it.location.blockX, it.location.blockZ) }
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = false)
    fun controlClicked(event: org.bukkit.event.player.PlayerInteractEvent) {
        if (event.action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return
        val player = event.player
        if (!ready() || placement.selection(player) != null || placement.preview(player) != null) return
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

    internal fun saveCapturedCity(plan: CivPlacementPlan, bytes: ByteArray, city: Settlement): CompletableFuture<Settlement> =
        store.saveSchematic(plan.id, bytes).thenCompose { store.savePlan(plan) }.thenCompose { store.save(city) }
    internal fun managed(player: Player, allowDeletion: Boolean = false): Settlement {
        requireReady(player)
        val city = city(player) ?: throw membership.remoteCity(player)?.let {
            CivsRejection("remote-city", mapOf("server" to it.server.value))
        } ?: CivsRejection("no-city")
        rejectIf(city.id in unhealthy, "storage-unavailable")
        rejectIf(city.pendingDeletion != null && !allowDeletion, "deletion-pending")
        return city
    }
    internal fun requireReady(player: Player) {
        checkMain()
        rejectIf(!ready(), "not-ready")
        rejectIf(!player.hasPermission("arccivs.use"), "not-authorized")
    }
    internal fun near(player: Player, worldId: UUID, point: CivPosition) {
        rejectIf(player.world.uid != worldId || player.location.toVector().distanceSquared(org.bukkit.util.Vector(point.x.toDouble(), point.y.toDouble(), point.z.toDouble())) > 96.0 * 96.0, "too-far")
    }
    internal fun updateHealth() {
        health = RuntimeHealthContribution(
            state = when { closed || !initialized && !loading -> RuntimeHealthState.DOWN; loading -> RuntimeHealthState.STARTING; unhealthy.isNotEmpty() -> RuntimeHealthState.DEGRADED; else -> RuntimeHealthState.UP },
            recoveryBacklog = cities.values.count { it.construction != null } + unhealthy.count { it !in cities },
            activeLeases = placement.previewCount + placement.selectionCount + construction.activeCount + chunkLoading.retainedChunks,
            schemas = mapOf("city" to 1, "plan" to 1),
            dependencies = mapOf("storage" to (initialized && unhealthy.isEmpty())),
        )
    }
    internal fun <T> tracked(): CompletableFuture<T> = CompletableFuture<T>().also(::track)
    internal fun track(future: CompletableFuture<*>) {
        completions += future
        future.whenComplete { _, _ -> if (Bukkit.isPrimaryThread()) completions -= future else tasks.runSync { completions -= future } }
    }
    internal fun <T> action(name: String, player: Player? = null, vararg fields: Pair<String, Any?>,
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
    internal fun <T> cityAction(name: String, player: Player, vararg fields: Pair<String, Any?>,
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
    internal fun now() = clock()
    internal fun log(context: String, error: Throwable, cityId: UUID? = null) =
        CivsDiagnostics.record(Level.WARNING, cityId, "ArcCivs could not $context", plugin.logger, error)
    internal fun checkMain() = check(Bukkit.isPrimaryThread()) { "ArcCivs authority must run on Paper's main thread" }

    @EventHandler fun quit(event: PlayerQuitEvent) {
        placement.cancelPreview(event.player.uniqueId, "logout")
        boundary.clear(event.player.uniqueId)
        membership.forget(event.player.uniqueId)
    }
    @EventHandler fun worldChanged(event: PlayerChangedWorldEvent) { placement.cancelPreview(event.player.uniqueId, "world-changed"); boundary.clear(event.player.uniqueId) }

    override fun close() {
        if (closed) return
        closed = true
        initialized = false
        villageSites.close()
        layoutDebug.close()
        planBoardSnapshots.close()
        planBoardDrain = runCatching { planBoards.closeAsync() }
            .getOrElse { CompletableFuture.failedFuture(it) }
        autonomousDevelopment.cancel()
        workstations.close()
        controlRelocations.close()
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
        HandlerList.unregisterAll(forestryLeafLoot)
        plugin.server.servicesManager.unregister(ArcBlockProtectionService::class.java, sharedProtection)
        renderer.close()
        boundary.close()
        storage.close()
        controlInstallations.clear()
        placement.clearPreviewsAndSelections()
        membership.clear()
        placement.clearLoads()
        completions.toList().forEach { it.completeExceptionally(CivsRejection("not-ready")) }
        completions.clear()
        cityAvailability.clear()
        buildingRequests.clearAccepted()
        updateHealth()
    }

    fun drained(): CompletableFuture<Void> = CompletableFuture.allOf(renderer.drained(), boundary.drained(), citizenDrain,
        supply.drained(), layoutDebug.drained(), planBoardDrain, planBoardSnapshots.drained(), controlRelocations.drained())

    internal fun reserveCity(cityId: UUID) { busy += cityId }
    internal fun releaseCity(cityId: UUID) { busy -= cityId }
    internal fun quarantineCity(cityId: UUID) { unhealthy += cityId }
    internal fun invalidateReadiness(reason: String? = null) {
        initialized = false
        if (reason != null) loadFailure = reason
    }
    internal fun commitCapturedRecord(city: Settlement): CompletableFuture<Settlement> = store.save(city)
    internal fun deleteRecord(city: Settlement): CompletableFuture<Unit> = store.delete(city)

    internal fun repairBuildingStorage(cityId: UUID, buildingId: UUID): CompletableFuture<Map<String, Int>> = storageActions.repairBuildingStorage(cityId, buildingId)

    fun debugLayout(cityId: UUID, terrain: Boolean): CompletableFuture<Map<String, Any?>> = diagnostics.debugLayout(cityId, terrain)

    internal fun debugTerrainAt(cityId: UUID, workerId: UUID, origin: CivPosition): Map<String, Any?> = diagnostics.debugTerrainAt(cityId, workerId, origin)

    fun debugState(system: String, cityId: UUID?, workerId: UUID?): Map<String, Any?> = diagnostics.debugState(system, cityId, workerId)

    override fun remoteCity(player: Player): CivsNetworkCity? = membership.remoteCity(player)

    override fun memberName(city: Settlement, memberId: UUID): String = membership.memberName(city, memberId)

    override fun refreshCity(player: Player): CompletableFuture<Unit> = membership.refreshCity(player)

    override fun transferHome(player: Player): CompletableFuture<Unit> = membership.transferHome(player)

    override fun workerStatus(workerId: UUID): String = diagnostics.workerStatus(workerId)

    override fun workerStatusInputs(workerId: UUID): List<String> = diagnostics.workerStatusInputs(workerId)

    override fun buildingStatus(buildingId: UUID): String = diagnostics.buildingStatus(buildingId)

    override fun preview(player: Player): CivsPreviewView? = placement.preview(player)

    override fun selection(player: Player): CivsSelectionView? = placement.selection(player)

    override fun beginFoundingSelection(player: Player, name: String, style: String): CompletableFuture<Unit> = placement.beginFoundingSelection(player, name, style)

    override fun beginAutonomousSelection(player: Player, name: String, style: String): CompletableFuture<Unit> = placement.beginAutonomousSelection(player, name, style)

    override fun nextAutonomyGoal(cityId: UUID): CivAutonomyGoal? = diagnostics.nextAutonomyGoal(cityId)

    override fun autonomyStatus(cityId: UUID): CivsAutonomyView? = diagnostics.autonomyStatus(cityId)

    override fun research(player: Player, cityId: UUID): CivResearchView = cityResearch.research(player, cityId)

    override fun industry(player: Player, cityId: UUID): CivIndustryView = cityResearch.industry(player, cityId)

    override fun beginResearch(player: Player, cityId: UUID, research: CivResearchId): CompletableFuture<Unit> = cityResearch.beginResearch(player, cityId, research)

    override fun setResearchPaused(player: Player, cityId: UUID, projectId: UUID, paused: Boolean): CompletableFuture<Unit> = cityResearch.setResearchPaused(player, cityId, projectId, paused)

    override fun beginBuildingSelection(player: Player, type: BuildingType): CompletableFuture<Unit> = placement.beginBuildingSelection(player, type)

    override fun beginBuildingSelection(player: Player, type: BuildingType, style: String): CompletableFuture<Unit> = placement.beginBuildingSelection(player, type, style)

    override fun beginBlueprintSelection(player: Player, type: BuildingType, style: String, entryId: String): CompletableFuture<Unit> = placement.beginBlueprintSelection(player, type, style, entryId)

    override fun beginPlanBoardSelection(player: Player, cityId: UUID): CompletableFuture<Unit> = placement.beginPlanBoardSelection(player, cityId)

    override fun selectGround(player: Player, selectionId: UUID, target: CivsGroundTarget): CompletableFuture<Unit> = placement.selectGround(player, selectionId, target)

    override fun cancelSelection(player: Player, reason: String) = placement.cancelSelection(player, reason)

    override fun upgradeBuilding(player: Player, buildingId: UUID): CompletableFuture<Unit> = buildingRequests.upgradeBuilding(player, buildingId)

    override fun rotatePreview(player: Player, degrees: Int): CompletableFuture<Unit> = placement.rotatePreview(player, degrees)

    override fun movePreview(player: Player): CompletableFuture<Unit> = placement.movePreview(player)

    override fun movePreview(player: Player, target: CivsGroundTarget): CompletableFuture<Unit> = placement.movePreview(player, target)

    override fun nudgePreview(player: Player, direction: CivsPreviewMove): CompletableFuture<Unit> = placement.nudgePreview(player, direction)

    override fun cancelPreview(player: Player) = placement.cancelPreview(player)

    override fun confirmPreview(player: Player): CompletableFuture<Unit> = buildingRequests.confirmPreview(player)

    override fun buildingMaterials(player: Player, buildingId: UUID): CompletableFuture<Map<String, Long>> = storageActions.buildingMaterials(player, buildingId)

    override fun supplyConstruction(player: Player, buildingId: UUID): CompletableFuture<CivsSupplyResult> = storageActions.supplyConstruction(player, buildingId)

    override fun supplyConstruction(player: Player, buildingId: UUID, hutId: UUID): CompletableFuture<CivsSupplyResult> = storageActions.supplyConstruction(player, buildingId, hutId)

    override fun grantConstructionMaterials(player: Player, buildingId: UUID): CompletableFuture<CivsSupplyResult> = storageActions.grantConstructionMaterials(player, buildingId)

    override fun grantConstructionMaterials(player: Player, buildingId: UUID, hutId: UUID): CompletableFuture<CivsSupplyResult> = storageActions.grantConstructionMaterials(player, buildingId, hutId)

    override fun supplyBuilderMaterials(player: Player, workerId: UUID): CompletableFuture<CivsSupplyResult> = storageActions.supplyBuilderMaterials(player, workerId)

    override fun grantBuilderMaterials(player: Player, workerId: UUID): CompletableFuture<CivsSupplyResult> = storageActions.grantBuilderMaterials(player, workerId)

    override fun startBuilding(player: Player, buildingId: UUID): CompletableFuture<Unit> = buildingRequests.startBuilding(player, buildingId)

    override fun addMember(player: Player, name: String): CompletableFuture<Unit> = membership.addMember(player, name)

    override fun removeMember(player: Player, memberId: UUID): CompletableFuture<Unit> = membership.removeMember(player, memberId)

    internal fun forestrySurplusDebug(cityId: UUID?): Map<String, Any?> = residentSupplies.forestrySurplusDebug(cityId)

    internal fun craftPlan(city: Settlement, worker: CivWorker): CivCraftJob? = residentSupplies.craftPlan(city, worker)

    internal fun craftRequired(city: Settlement, buildingId: UUID): Map<String, Long> = residentSupplies.craftRequired(city, buildingId)

    internal fun upkeepSupplyRequests(city: Settlement, stock: (UUID) -> Map<String, Long>): List<CivSupplyRequest> = residentSupplies.upkeepSupplyRequests(city, stock)

    internal fun productionReady(city: Settlement, worker: CivWorker): Boolean = residentSupplies.productionReady(city, worker)

    internal fun workStatusInputs(city: Settlement, worker: CivWorker): List<String> = residentSupplies.workStatusInputs(city, worker)

    /** Fresh accepted native capacity, never inferred from an empty/stale supply result. */
    private fun storageCapacityPressure(city: Settlement): Boolean {
        val snapshot = supply.observation(city.id)
        val capture = (snapshot?.inputKey as? CivSupplyObservation)?.capture
        val age = capture?.startedAtMillis?.let { now() - it }
        if (!CivsAutoSupplies.observed(city, snapshot) || capture?.complete != true || age == null || age !in 0..10_000L) {
            supply.request(city.id)
            return false
        }
        val warehouses = city.buildings.filter { it.type == BuildingType.WAREHOUSE && it.phase == BuildingPhase.ACTIVE }
            .mapTo(hashSetOf()) { it.id }
        if (warehouses.isEmpty() || warehouses.any { id ->
                capture.stores[id]?.let { it.unavailable != 0 || it.capacity.emptySlots > 2 } != false
            }) return false
        return storage.supplyCaptureCurrent(city, capture, warehouses, requireComplete = true).also { current ->
            if (!current) supply.request(city.id)
        }
    }

    internal fun requireAutonomousStorage(city: Settlement, buildingId: UUID? = null) = residentSupplies.requireAutonomousStorage(city, buildingId)

    internal fun workInputsReady(city: Settlement, worker: CivWorker): Boolean = residentSupplies.workInputsReady(city, worker)

    internal fun residentSupplySource(city: Settlement, worker: CivWorker): UUID? = residentSupplies.residentSupplySource(city, worker)

    internal fun pickupResidentFood(cityId: UUID, workerId: UUID, source: CivResidentFoodSource): CompletableFuture<Settlement> = residentSupplies.pickupResidentFood(cityId, workerId, source)

    internal fun deliverTavernMeal(cityId: UUID, delivery: CivTavernDelivery): CompletableFuture<Settlement> = residentSupplies.deliverTavernMeal(cityId, delivery)

    internal fun materializeResidentFood(cityId: UUID, workerId: UUID): CompletableFuture<Settlement> = residentSupplies.materializeResidentFood(cityId, workerId)

    internal fun returnResidentOutputs(cityId: UUID, workerId: UUID, buildingId: UUID): CompletableFuture<Settlement> = residentSupplies.returnResidentOutputs(cityId, workerId, buildingId)

    internal fun prepareResidentSupplies(cityId: UUID, workerId: UUID, buildingId: UUID, craftJobId: UUID? = null): CompletableFuture<Settlement> = residentSupplies.prepareResidentSupplies(cityId, workerId, buildingId, craftJobId)

    internal fun courierPlan(city: Settlement, worker: CivWorker): CivCourierPlan? = cargoRequests.courierPlan(city, worker)

    internal fun pickupCargo(cityId: UUID, workerId: UUID, requested: CivCargo): CompletableFuture<Settlement> = cargoRequests.pickupCargo(cityId, workerId, requested)

    internal fun dropoffCargo(cityId: UUID, workerId: UUID, requested: CivCargo, destinationId: UUID): CompletableFuture<Settlement> = cargoRequests.dropoffCargo(cityId, workerId, requested, destinationId)

    internal fun collectionAvailable(cityId: UUID): Boolean = cargoRequests.collectionAvailable(cityId)

    internal fun collectedHome(city: Settlement, worker: CivWorker): CivCollectedHome? = cargoRequests.collectedHome(city, worker)

    internal fun pickupGround(cityId: UUID, workerId: UUID, entities: List<UUID>): CompletableFuture<Settlement> = cargoRequests.pickupGround(cityId, workerId, entities)

    internal fun depositCollected(cityId: UUID, workerId: UUID, destination: CivPosition?): CompletableFuture<Settlement> = cargoRequests.depositCollected(cityId, workerId, destination)

    override fun availableMaterials(player: Player): Map<String, Long> = storageActions.availableMaterials(player)

    override fun constructionAvailable(player: Player, buildingId: UUID): Map<String, Long> = storageActions.constructionAvailable(player, buildingId)

    override fun localMaterials(player: Player, buildingId: UUID): Map<String, Long> = storageActions.localMaterials(player, buildingId)

    override fun supplyRequests(player: Player): List<CivSupplyRequest> = storageActions.supplyRequests(player)

    override fun carpentry(player: Player, buildingId: UUID): CivCarpentryView? = storageActions.carpentry(player, buildingId)

    override fun setStorageMode(player: Player, mode: CivStorageMode): CompletableFuture<Unit> = storageActions.setStorageMode(player, mode)

    override fun materializeStorage(player: Player): CompletableFuture<Unit> = storageActions.materializeStorage(player)

    override fun inspectBuilding(player: Player, cityId: UUID, buildingId: UUID): Settlement = storageActions.inspectBuilding(player, cityId, buildingId)

    override fun openBuildingInventory(player: Player, cityId: UUID, buildingId: UUID): CompletableFuture<Unit> = storageActions.openBuildingInventory(player, cityId, buildingId)

    override fun inspectionMaterials(player: Player, cityId: UUID, buildingId: UUID): Map<String, Long> = storageActions.inspectionMaterials(player, cityId, buildingId)

    override fun openBuildingInventory(player: Player, buildingId: UUID): CompletableFuture<Unit> = storageActions.openBuildingInventory(player, buildingId)

    override fun buildingControl(player: Player, buildingId: UUID): CompletableFuture<CivPosition> = storageActions.buildingControl(player, buildingId)

    override fun relocateControl(player: Player, buildingId: UUID, expectedTarget: CivPosition): CompletableFuture<Unit> = storageActions.relocateControl(player, buildingId, expectedTarget)

    override fun depositMaterials(player: Player, buildingId: UUID): CompletableFuture<Unit> = storageActions.depositMaterials(player, buildingId)

    override fun withdrawMaterial(player: Player, buildingId: UUID, material: String): CompletableFuture<Unit> = storageActions.withdrawMaterial(player, buildingId, material)

    override fun registerContainer(player: Player): CompletableFuture<Unit> = storageActions.registerContainer(player)

    override fun unregisterContainer(player: Player, position: CivPosition): CompletableFuture<Unit> = storageActions.unregisterContainer(player, position)

    override fun setProduction(player: Player, buildingId: UUID, material: String): CompletableFuture<Unit> = storageActions.setProduction(player, buildingId, material)

    override fun setForesterSpecies(player: Player, cityId: UUID, buildingId: UUID, material: String): CompletableFuture<Unit> = storageActions.setForesterSpecies(player, cityId, buildingId, material)

    override fun deleteCity(player: Player, expectedCityId: UUID, confirmationName: String): CompletableFuture<Unit> = deletion.deleteCity(player, expectedCityId, confirmationName)

    override fun deleteBuilding(player: Player, expectedCityId: UUID, buildingId: UUID): CompletableFuture<Unit> = deletion.deleteBuilding(player, expectedCityId, buildingId)

    internal fun prepareTestingConstructionSupplies(cityId: UUID): CompletableFuture<Settlement> = residentSupplies.prepareTestingConstructionSupplies(cityId)

    companion object {
        internal fun areasOverlap(a: CivPosition, ar: Int, b: CivPosition, br: Int): Boolean =
            kotlin.math.abs(a.x.toLong() - b.x) <= ar.toLong() + br && kotlin.math.abs(a.z.toLong() - b.z) <= ar.toLong() + br
    }
}
