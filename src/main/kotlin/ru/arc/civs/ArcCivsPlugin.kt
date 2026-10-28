package ru.arc.civs

import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.EmptyConfig
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.TaskScheduler
import ru.arc.core.whenCompleteSync
import ru.arc.logging.ArcLogging
import ru.arc.logging.LoggingConfigSource
import ru.arc.logging.paper.PaperLoggingPlatform
import ru.arc.paper.api.ArcSchematicService
import ru.arc.paper.api.ArcSceneObservationService
import ru.arc.paper.api.ArcSceneEntityInfoService
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.paper.sql.PaperSqlServices
import ru.arc.paper.network.BungeeBackendTransfer
import ru.arc.network.BackendServerId
import ru.arc.core.BoundedAsyncExecutor
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

open class ArcCivsPlugin : JavaPlugin() {
    private var runtime: PaperPluginRuntime? = null
    private var nativeWork: ru.arc.core.BudgetedTaskQueue? = null
    // Core may finish retiring captured native handles on its next tick; IO closes inline.
    private var storageClose = CompletableFuture.completedFuture(Unit)
    private var nativeRetirementClose = CompletableFuture.completedFuture(Unit)
    private var nativeClose = CompletableFuture.completedFuture(Unit)
    private var historyClose = CompletableFuture.completedFuture(Unit)
    private var stopClose: CompletableFuture<Unit>? = null
    private var installedScheduler: TaskScheduler? = null
    internal fun drained(): CompletableFuture<Unit> = stopClose?.copy()
        ?: CompletableFuture.allOf(nativeClose, historyClose).thenApply { Unit }

    override fun onEnable() {
        val previous = stopClose
        if (previous != null) {
            if (!previous.isDone) {
                logger.severe("ArcCivs startup refused: previous cleanup is still pending; retry enable after it finishes")
                server.pluginManager.disablePlugin(this)
                return
            }
            try { previous.join() } // Complete already: never wait for native work on Main.
            catch (failure: Throwable) {
                logger.log(Level.SEVERE, "ArcCivs startup refused: previous cleanup failed", failure)
                server.pluginManager.disablePlugin(this)
                return
            }
        }
        val unavailable = pluginMeta.pluginDependencies.filterNot(server.pluginManager::isPluginEnabled)
        if (unavailable.isNotEmpty()) {
            logger.severe("ArcCivs startup refused: mandatory dependencies are not enabled: ${unavailable.joinToString()}")
            server.pluginManager.disablePlugin(this)
            return
        }
        check(runtime == null) { "ArcCivs runtime is already active" }
        storageClose = CompletableFuture.completedFuture(Unit)
        nativeRetirementClose = CompletableFuture.completedFuture(Unit)
        nativeClose = CompletableFuture.completedFuture(Unit)
        historyClose = CompletableFuture.completedFuture(Unit)
        stopClose = null
        val lifecycle = PaperPluginRuntime(this, "arc-civs", BukkitTaskScheduler(this)).also {
            runtime = it
            it.start("version" to pluginMeta.version)
        }
        try {
            PaperArcRuntime.installScheduling(this)
            installedScheduler = Tasks.scheduler
            ArcLogging.install(PaperLoggingPlatform("ArcCivs", "ArcCivs"), LoggingConfigSource { EmptyConfig })
            val history = CivsCityHistory(dataPath.resolve("logs"), logger::warning)
            CivsDiagnostics.install(history, logger)
            lifecycle.own(AutoCloseable {
                historyClose = history.closeAfter(nativeClose).whenComplete { _, failure ->
                    CivsDiagnostics.detach(history)
                    if (failure != null) logger.log(Level.SEVERE, "ArcCivs city history drain failed", failure)
                }
            })
            lifecycle.tasks.runTimer(1_200L, 1_200L, history::maintenance)
            prepare(lifecycle, server.servicesManager.load(ArcSchematicService::class.java), dataPath)
        } catch (failure: Throwable) {
            lifecycle.health.markDown()
            logger.log(Level.SEVERE, "ArcCivs could not initialize", failure)
            server.pluginManager.disablePlugin(this)
        }
    }

    private fun prepare(lifecycle: PaperPluginRuntime, schematicService: ArcSchematicService?, root: Path) {
        val terminal = CompletableFuture<Unit>()
        lifecycle.own(PaperSqlServices.installConsumer(this, terminal))
        val executor = BoundedAsyncExecutor("ArcCivs-storage", 1, 256)
        val nativeDrains = mutableListOf<CompletableFuture<*>>()
        var network: CivsNetworkStore? = null
        lifecycle.own(AutoCloseable {
            storageClose = executor.closeAsync().thenCompose {
                network?.closeAsync() ?: CompletableFuture.completedFuture(Unit)
            }
            nativeRetirementClose = CompletableFuture.allOf(*nativeDrains.toTypedArray()).thenApply { Unit }
            nativeClose = CompletableFuture.allOf(storageClose, nativeRetirementClose).thenApply { Unit }
            storageClose.whenComplete { _, failure ->
                if (failure != null) logger.log(Level.SEVERE, "ArcCivs storage drain failed", failure)
            }
            nativeRetirementClose.whenComplete { _, failure ->
                if (failure != null) logger.log(Level.SEVERE, "ArcCivs native retirement failed", failure)
            }
            nativeClose.whenComplete { _, failure ->
                if (failure == null) terminal.complete(Unit) else terminal.completeExceptionally(failure)
            }
            storageClose.get() // Explicit maintenance: detached file/SQL IO only, never native retirement.
        })
        try {
            val file = root.resolve("config.yml")
            Files.createDirectories(root)
            if (!Files.exists(file)) requireNotNull(getResource("config.yml")).use { Files.copy(it, file) }
            val configuration = YamlConfiguration().apply { load(file.toFile()) }
            if (!configuration.contains("chunk-loading.maximum-chunks")) {
                configuration.set("chunk-loading.maximum-chunks", 2048)
                configuration.save(file.toFile())
            }
            CivsSettings.chunkLoadingMaximum(configuration, logger::warning)
            val serverId = networkServer(configuration)
            val settings = CivsSettings.read(configuration)
            val defaults = requireNotNull(getResource("config.yml")).use {
                YamlConfiguration.loadConfiguration(it.reader(Charsets.UTF_8))
            }
            var updated = false
            for (section in listOf("navigation", "native-work", "worksites")) {
                for ((key, value) in defaults.getConfigurationSection(section)?.getValues(false).orEmpty()) {
                    if (!configuration.contains("$section.$key")) {
                        configuration.set("$section.$key", value)
                        updated = true
                    }
                }
            }
            if (updated) configuration.save(file.toFile())
            val queue = ru.arc.core.BudgetedTaskQueue(lifecycle.tasks, settings.navigation.nativeOperationsPerTick,
                (settings.navigation.nativeBudgetMillis * 1_000_000).toLong(), System::nanoTime, 4096).also { nativeWork = it }
            val text = CivsText.load(this, configuration)
            val dataRoot = root
            val provider = requireNotNull(schematicService) { "ArcCore schematic service is not available" }
            network = CivsNetworkStore.open(dataRoot, serverId, settings.maximumCities, executor)
            val store = lifecycle.own(CivsStore(dataRoot.resolve("settlements"), executor, settings.maximumCities, network))
            val transfer = lifecycle.own(BungeeBackendTransfer(this) { failure -> logger.log(Level.WARNING, "ArcCivs backend transfer request failed", failure) })
            val blueprints = lifecycle.own(CivsBlueprints(provider, settings.schematicRoot, store.schematicRoot(), executor,
                settings.maximumVolume, dataVersion = server.unsafe.dataVersion))
            val preview = CivsPreviewRenderer(this, lifecycle.tasks, queue)
            lifecycle.own(AutoCloseable {
                val failure = runCatching { preview.close() }.exceptionOrNull()
                nativeDrains += preview.drained()
                if (failure != null) nativeDrains += CompletableFuture.failedFuture<Unit>(failure)
            })
            val boundary = CivsBoundaryRenderer(this, lifecycle.tasks, queue, text)
            lifecycle.own(AutoCloseable {
                val failure = runCatching { boundary.close() }.exceptionOrNull()
                nativeDrains += boundary.drained()
                if (failure != null) nativeDrains += CompletableFuture.failedFuture<Unit>(failure)
            })
            val service = CivsService(this, lifecycle.tasks, settings, store, blueprints, executor,
                offlinePlayerData = ru.arc.paper.player.PaperOfflinePlayerData.capture(server),
                previewRenderer = preview, boundaryRenderer = boundary, nativeWork = queue, backendTransfer = transfer, onCitizensCreated = { citizens ->
                    val collisions = lifecycle.own(CivsResidentCollisions(this, citizens::collisionActors, citizens::nudgeResident))
                    citizens.residentStepAllowed = collisions::canResidentMove
                    val labels = CivsHolograms(this, lifecycle.tasks, text, citizens::hologramSnapshots, queue, citizens::workplaceSnapshots)
                    lifecycle.own(AutoCloseable {
                        labels.close()
                        nativeDrains += labels.drained()
                    })
                    // Capture before the rest of CivsService's constructor can fail.
                    lifecycle.own(AutoCloseable {
                        nativeDrains += runCatching { citizens.closeAsync() }
                            .getOrElse { CompletableFuture.failedFuture<Void>(it) }
                    })
                    val entityInfo = requireNotNull(server.servicesManager.load(ArcSceneEntityInfoService::class.java)) {
                        "ArcCore scene entity info service is not available"
                    }
                    lifecycle.own(entityInfo.registerSource(this, "civs-residents") { bounds, entityIds, limit ->
                        citizens.sceneEntityInformation(text, bounds, entityIds, limit)
                    })
                })
            lifecycle.own(AutoCloseable {
                val failure = runCatching { service.close() }.exceptionOrNull()
                nativeDrains += service.drained()
                if (failure != null) nativeDrains += CompletableFuture.failedFuture<Unit>(failure)
            })
            val observations = requireNotNull(server.servicesManager.load(ArcSceneObservationService::class.java)) {
                "ArcCore scene observation service is not available"
            }
            lifecycle.own(observations.registerLocationSource(this, "civs-settlements") { limit ->
                if (service.ready()) civsSceneLocations(service.settlements(), limit) else emptyList()
            })
            val ui = lifecycle.own(CivsUi(this, service, lifecycle.tasks, text))
            service.ui = ui
            val corpses = CivsCorpseRenderer(this, lifecycle.tasks, text, service::corpseSettlements, ui::openCorpse,
                interact = { player, cityId, workerId, diedAt ->
                    if (CivsResurrectionScroll.matches(player.inventory.itemInMainHand)) {
                        service.resurrect(player, cityId, workerId, diedAt).whenCompleteSync(lifecycle.tasks) { _, failure ->
                            if (player.isOnline) player.sendMessage(if (failure == null) text.info(player, "resurrection.complete")
                                else text.failure(player, failure))
                        }
                    } else ui.openCorpse(player, cityId, workerId)
                })
            service.corpseLocation = corpses::resolvedLocation
            lifecycle.own(AutoCloseable {
                val failure = runCatching { corpses.close() }.exceptionOrNull()
                nativeDrains += corpses.drained()
                if (failure != null) nativeDrains += CompletableFuture.failedFuture<Unit>(failure)
            })
            val controls = CivsPlacementControls(this, service, ui, lifecycle.tasks, text, nativeWork = queue)
            lifecycle.own(AutoCloseable {
                val failure = runCatching { controls.close() }.exceptionOrNull()
                nativeDrains += controls.drained()
                if (failure != null) nativeDrains += CompletableFuture.failedFuture<Unit>(failure)
            })
            val command = CivsCommand(service, ui, text, lifecycle.tasks, service::debugState, service::configureSettlementSetting,
                service::repairBuildingStorage, service::retryConstruction, service::debugLayout,
                this::prepareHotload, this::hotloadStatus, service::debugTerrainAt,
                CivsTerrainDebugFile(dataRoot, executor)::save, CivsSettlementDebugFile(dataRoot, executor)::save)
            requireNotNull(getCommand("civs")).apply { setExecutor(command); tabCompleter = command }
            lifecycle.registerHealth("settlements", service::health)
            lifecycle.reportHealthEvery(1_200)
            val startup = CompletableFuture<Unit>()
            blueprints.catalogReady().whenCompleteSync(lifecycle.tasks) { _, catalogFailure ->
                if (catalogFailure != null) startup.completeExceptionally(catalogFailure)
                else runCatching { service.start() }.onSuccess { loaded -> loaded.whenComplete { _, failure ->
                    if (failure == null) startup.complete(Unit) else startup.completeExceptionally(failure)
                } }.onFailure(startup::completeExceptionally)
            }
            startup.whenCompleteSync(lifecycle.tasks) { _, failure ->
                if (failure == null) {
                    lifecycle.ready("settlements" to service.settlements().size)
                    CivsDiagnostics.event("startup_ready", "server" to serverId.value, "settlements" to service.settlements().size,
                        "allowed_worlds" to settings.worlds.sorted().joinToString())
                } else {
                    lifecycle.health.markDown()
                    lifecycle.emitHealth()
                    logger.log(Level.SEVERE, "ArcCivs storage failed closed; repair the recorded error. Initial SQL connection/migration failure requires restart; /civs reload only rereads city data", failure)
                    CivsDiagnostics.event("startup_failed", "server" to serverId.value, "cause" to CivsDiagnostics.cause(failure)?.javaClass?.simpleName)
                }
            }
        } catch (failure: Throwable) {
            lifecycle.health.markDown()
            logger.log(Level.SEVERE, "ArcCivs could not initialize", failure)
            server.pluginManager.disablePlugin(this)
        }
    }

    internal fun networkServer(configuration: YamlConfiguration): BackendServerId {
        val id = configuration.getString("server-id")?.trim().orEmpty()
        require(id.isNotEmpty()) { "ArcCivs requires explicit server-id matching its proxy backend and shared mysql configuration before activation; no local fallback" }
        for (key in listOf("host", "database", "username")) require(!configuration.getString("mysql.$key").isNullOrBlank()) {
            "ArcCivs requires mysql.$key in config.yml; deploy the shared network SQL configuration before activation"
        }
        require(configuration.contains("mysql.password")) { "ArcCivs requires mysql.password in config.yml" }
        return BackendServerId.of(id)
    }

    /** Main-thread maintenance entrypoint. ArcCore remains enabled to finish detached native cleanup. */
    internal fun prepareHotload(): Map<String, Any?> {
        stopRuntime()
        return hotloadStatus()
    }

    internal fun hotloadStatus(): Map<String, Any?> {
        val completion = stopClose
        fun stage(future: CompletableFuture<*>): Map<String, Any?> = linkedMapOf(
            "state" to if (completion == null) "active" else when {
                !future.isDone -> "pending"
                future.isCompletedExceptionally -> "failed"
                else -> "complete"
            },
            "failure" to completedFailure(future)?.javaClass?.simpleName,
        )
        return linkedMapOf(
            "schema" to 1,
            "version" to pluginMeta.version,
            "state" to when {
                completion == null -> "active"
                !completion.isDone -> "preparing"
                completion.isCompletedExceptionally -> "failed"
                else -> "ready"
            },
            "storage" to stage(storageClose),
            "native" to stage(nativeRetirementClose),
            "history" to stage(historyClose),
            "failure" to completion?.let(::completedFailure)?.javaClass?.simpleName,
        )
    }

    private fun completedFailure(future: CompletableFuture<*>): Throwable? {
        if (!future.isCompletedExceptionally) return null
        return try { future.join(); null } // Exceptional completion is already terminal; this cannot wait.
        catch (failure: Throwable) { CivsDiagnostics.cause(failure) ?: failure }
    }

    private fun stopRuntime() {
        if (stopClose != null) return
        val completion = CompletableFuture<Unit>().also { stopClose = it }
        val lifecycle = runtime
        runtime = null
        val queue = nativeWork
        nativeWork = null
        val scheduling = installedScheduler
        installedScheduler = null
        val failures = mutableListOf<CompletableFuture<*>>()
        for (close in listOf<() -> Unit>({ queue?.close() }, { lifecycle?.close() }, {
            if (scheduling != null && Tasks.scheduler === scheduling) Tasks.reset()
        })) {
            try { close() }
            catch (failure: Throwable) {
                failures += CompletableFuture.failedFuture<Unit>(failure)
                logger.log(Level.SEVERE, "ArcCivs shutdown failed", failure)
            }
        }
        CompletableFuture.allOf(nativeClose, historyClose, *failures.toTypedArray()).whenComplete { _, failure ->
            if (failure == null) completion.complete(Unit) else completion.completeExceptionally(failure)
        }
    }

    override fun onDisable() {
        stopRuntime()
        getCommand("civs")?.apply { setExecutor(null); tabCompleter = null }
    }
}
