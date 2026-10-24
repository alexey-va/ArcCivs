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
    private var nativeClose = CompletableFuture.completedFuture(Unit)
    private var installedScheduler: TaskScheduler? = null
    internal fun drained(): CompletableFuture<Unit> = nativeClose.copy()

    override fun onEnable() {
        val unavailable = pluginMeta.pluginDependencies.filterNot(server.pluginManager::isPluginEnabled)
        if (unavailable.isNotEmpty()) {
            logger.severe("ArcCivs startup refused: mandatory dependencies are not enabled: ${unavailable.joinToString()}")
            server.pluginManager.disablePlugin(this)
            return
        }
        val lifecycle = PaperPluginRuntime(this, "arc-civs", BukkitTaskScheduler(this)).also {
            runtime = it
            it.start("version" to pluginMeta.version)
        }
        try {
            check(nativeClose.isDone) { "Previous native cleanup is still pending; retry enable after it finishes" }
            nativeClose.join() // Complete already: propagate cleanup failure without blocking Main.
            PaperArcRuntime.installScheduling(this)
            installedScheduler = Tasks.scheduler
            ArcLogging.install(PaperLoggingPlatform("ArcCivs", "ArcCivs"), LoggingConfigSource { EmptyConfig })
            val queue = ru.arc.core.BudgetedTaskQueue(lifecycle.tasks, 512, 4_000_000L, System::nanoTime, 4096).also { nativeWork = it }
            prepare(lifecycle, queue, server.servicesManager.load(ArcSchematicService::class.java), dataPath)
        } catch (failure: Throwable) {
            lifecycle.health.markDown()
            logger.log(Level.SEVERE, "ArcCivs could not initialize", failure)
            server.pluginManager.disablePlugin(this)
        }
    }

    private fun prepare(lifecycle: PaperPluginRuntime, queue: ru.arc.core.BudgetedTaskQueue,
        schematicService: ArcSchematicService?, root: Path) {
        val terminal = CompletableFuture<Unit>()
        lifecycle.own(PaperSqlServices.installConsumer(this, terminal))
        val executor = BoundedAsyncExecutor("ArcCivs-storage", 1, 256)
        val nativeDrains = mutableListOf<CompletableFuture<*>>()
        var network: CivsNetworkStore? = null
        lifecycle.own(AutoCloseable {
            val storage = executor.closeAsync().thenCompose {
                network?.closeAsync() ?: CompletableFuture.completedFuture(Unit)
            }
            nativeClose = CompletableFuture.allOf(storage, *nativeDrains.toTypedArray()).thenApply { Unit }
            nativeClose.whenComplete { _, failure ->
                if (failure == null) terminal.complete(Unit) else {
                    terminal.completeExceptionally(failure)
                    logger.log(Level.SEVERE, "ArcCivs terminal storage drain failed", failure)
                }
            }
            storage.get() // Runtime closes native scopes first; only detached file IO remains.
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
            val text = CivsText.load(this, configuration)
            val dataRoot = root
            val provider = requireNotNull(schematicService) { "ArcCore schematic service is not available" }
            network = CivsNetworkStore.open(dataRoot, serverId, settings.maximumCities, executor)
            val store = lifecycle.own(CivsStore(dataRoot.resolve("settlements"), executor, settings.maximumCities, network))
            val transfer = lifecycle.own(BungeeBackendTransfer(this) { failure -> logger.log(Level.WARNING, "ArcCivs backend transfer request failed", failure) })
            val blueprints = lifecycle.own(CivsBlueprints(provider, settings.schematicRoot, store.schematicRoot(), executor, settings.maximumVolume))
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
            val service = lifecycle.own(CivsService(this, lifecycle.tasks, settings, store, blueprints, executor,
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
                }))
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
                service::repairBuildingStorage, service::retryConstruction, service::debugLayout)
            requireNotNull(getCommand("civs")).apply { setExecutor(command); tabCompleter = command }
            lifecycle.registerHealth("settlements", service::health)
            lifecycle.reportHealthEvery(1_200)
            service.start().whenCompleteSync(lifecycle.tasks) { _, failure ->
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

    override fun onDisable() {
        val lifecycle = runtime
        runtime = null
        val queue = nativeWork
        nativeWork = null
        val scheduling = installedScheduler
        installedScheduler = null
        queue?.close()
        runCatching { lifecycle?.close() }.onFailure { logger.log(Level.SEVERE, "ArcCivs shutdown failed", it) }
        if (scheduling != null && Tasks.scheduler === scheduling) Tasks.reset()
    }
}
