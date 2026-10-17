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
import ru.arc.core.BoundedAsyncExecutor
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

open class ArcCivsPlugin : JavaPlugin() {
    private var runtime: PaperPluginRuntime? = null
    private var nativeWork: ru.arc.core.BudgetedTaskQueue? = null
    private var predecessor = CompletableFuture.completedFuture(Unit)
    private var installedScheduler: TaskScheduler? = null
    internal fun drained(): CompletableFuture<Unit> = predecessor.copy()

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
        predecessor.whenCompleteSync(lifecycle.tasks) { _, failure ->
            if (runtime !== lifecycle) return@whenCompleteSync
            if (failure != null) {
                lifecycle.health.markDown()
                logger.log(Level.SEVERE, "ArcCivs previous storage generation did not drain", failure)
                server.pluginManager.disablePlugin(this)
            } else {
                PaperArcRuntime.installScheduling(this)
                installedScheduler = Tasks.scheduler
                ArcLogging.install(PaperLoggingPlatform("ArcCivs", "ArcCivs"), LoggingConfigSource { EmptyConfig })
                val queue = ru.arc.core.BudgetedTaskQueue(lifecycle.tasks, 128, 2_000_000L, System::nanoTime, 4096).also { nativeWork = it }
                prepare(lifecycle, queue, server.servicesManager.load(ArcSchematicService::class.java), dataPath)
            }
        }
    }

    private fun prepare(lifecycle: PaperPluginRuntime, queue: ru.arc.core.BudgetedTaskQueue,
        schematicService: ArcSchematicService?, root: Path) {
        val executor = BoundedAsyncExecutor("ArcCivs-storage", 1, 256)
        val nativeDrains = mutableListOf<CompletableFuture<*>>()
        lifecycle.own(AutoCloseable {
            predecessor = CompletableFuture.allOf(executor.closeAsync(), *nativeDrains.toTypedArray()).thenApply { Unit }
            predecessor.whenComplete { _, failure ->
                if (failure != null) logger.log(Level.SEVERE, "ArcCivs terminal storage drain failed", failure)
            }
        })
        val token = lifecycle.tasks.token()
        executor.submit {
            val file = root.resolve("config.yml")
            Files.createDirectories(root)
            if (!Files.exists(file)) requireNotNull(getResource("config.yml")).use { Files.copy(it, file) }
            val configuration = YamlConfiguration().apply { load(file.toFile()) }
            Triple(CivsSettings.read(configuration), CivsText.load(this, configuration), root)
        }.whenCompleteSync(lifecycle.tasks, token) { prepared, preparationFailure ->
            if (runtime !== lifecycle) return@whenCompleteSync
            try {
                if (preparationFailure != null) throw preparationFailure
                val (settings, text, dataRoot) = requireNotNull(prepared)
                val provider = requireNotNull(schematicService) { "ArcCore schematic service is not available" }
                val store = lifecycle.own(CivsStore(dataRoot.resolve("settlements"), executor, settings.maximumCities))
                val blueprints = lifecycle.own(CivsBlueprints(provider, settings.schematicRoot, store.schematicRoot(), executor, settings.maximumVolume))
                val preview = CivsPreviewRenderer(this, lifecycle.tasks, queue)
                lifecycle.own(AutoCloseable {
                    val failure = runCatching { preview.close() }.exceptionOrNull()
                    nativeDrains += preview.drained()
                    if (failure != null) nativeDrains += CompletableFuture.failedFuture<Unit>(failure)
                })
                val service = lifecycle.own(CivsService(this, lifecycle.tasks, settings, store, blueprints, executor,
                    previewRenderer = preview, nativeWork = queue, onCitizensCreated = { citizens ->
                        // Capture before the rest of CivsService's constructor can fail.
                        lifecycle.own(AutoCloseable {
                            nativeDrains += runCatching { citizens.closeAsync() }
                                .getOrElse { CompletableFuture.failedFuture<Void>(it) }
                        })
                    }))
                val ui = lifecycle.own(CivsUi(this, service, lifecycle.tasks, text))
                service.ui = ui
                val controls = CivsPlacementControls(this, service, ui, lifecycle.tasks, text, nativeWork = queue)
                lifecycle.own(AutoCloseable {
                    val failure = runCatching { controls.close() }.exceptionOrNull()
                    nativeDrains += controls.drained()
                    if (failure != null) nativeDrains += CompletableFuture.failedFuture<Unit>(failure)
                })
                val command = CivsCommand(service, ui, text, lifecycle.tasks)
                requireNotNull(getCommand("civs")).apply { setExecutor(command); tabCompleter = command }
                lifecycle.registerHealth("settlements", service::health)
                lifecycle.reportHealthEvery(1_200)
                service.start().whenCompleteSync(lifecycle.tasks) { _, failure ->
                    if (failure == null) {
                        lifecycle.ready("settlements" to service.settlements().size)
                        ArcLogging.info("ArcCivs ready: {} settlements", service.settlements().size)
                    } else {
                        lifecycle.health.markDown()
                        lifecycle.emitHealth()
                        logger.log(Level.SEVERE, "ArcCivs storage failed closed; repair the recorded error before /civs reload", failure)
                    }
                }
            } catch (failure: Throwable) {
                lifecycle.health.markDown()
                logger.log(Level.SEVERE, "ArcCivs could not initialize", failure)
                server.pluginManager.disablePlugin(this)
            }
        }
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
