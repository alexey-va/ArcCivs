package ru.arc.civs

import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.EmptyConfig
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.core.whenCompleteSync
import ru.arc.logging.ArcLogging
import ru.arc.logging.LoggingConfigSource
import ru.arc.logging.paper.PaperLoggingPlatform
import ru.arc.paper.api.ArcSchematicService
import ru.arc.paper.runtime.PaperPluginRuntime
import java.util.concurrent.Executor
import java.util.logging.Level

open class ArcCivsPlugin : JavaPlugin() {
    private var runtime: PaperPluginRuntime? = null

    override fun onEnable() {
        val unavailable = pluginMeta.pluginDependencies.filterNot(server.pluginManager::isPluginEnabled)
        if (unavailable.isNotEmpty()) {
            logger.severe("ArcCivs startup refused: mandatory dependencies are not enabled: ${unavailable.joinToString()}")
            server.pluginManager.disablePlugin(this)
            return
        }
        saveDefaultConfig()
        PaperArcRuntime.installScheduling(this)
        ArcLogging.install(PaperLoggingPlatform("ArcCivs", "ArcCivs"), LoggingConfigSource { EmptyConfig })
        val lifecycle = PaperPluginRuntime(this, "arc-civs").also { runtime = it; it.start("version" to pluginMeta.version) }
        try {
            val settings = CivsSettings.read(config)
            val schematicService = requireNotNull(server.servicesManager.load(ArcSchematicService::class.java)) {
                "ArcCore schematic service is not available"
            }
            val executor = Executor { runnable -> Tasks.scheduler.runAsync(runnable) }
            val store = lifecycle.own(CivsStore(dataPath.resolve("settlements"), executor, settings.maximumCities))
            val blueprints = lifecycle.own(CivsBlueprints(schematicService, settings.schematicRoot, store.schematicRoot(), executor, settings.maximumVolume))
            val service = lifecycle.own(CivsService(this, lifecycle.tasks, settings, store, blueprints, executor))
            val ui = lifecycle.own(CivsUi(this, service, lifecycle.tasks))
            service.ui = ui
            val command = CivsCommand(service, ui, CivsText.load(this), lifecycle.tasks)
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
        } catch (failure: Exception) {
            lifecycle.health.markDown()
            logger.log(Level.SEVERE, "ArcCivs could not initialize", failure)
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        runCatching { runtime?.close() }.onFailure { logger.log(Level.SEVERE, "ArcCivs shutdown failed", it) }
        runtime = null
        Tasks.reset()
    }
}
