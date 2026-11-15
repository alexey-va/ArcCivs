package ru.arc.civs

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.plugin.Plugin
import ru.ruscrafting.trails.api.TrailsFootTrafficService
import java.util.UUID
import java.util.concurrent.CompletionStage

/** Optional Trails integration for road surface registration and speed lookup. */
internal class CivsFootTraffic(private val plugin: Plugin, private val cities: () -> Collection<Settlement>) : (Block) -> Double {
    private var incompatible: Plugin? = null
    private var nextFailureLog = 0L

    fun detachedSupportCost(weights: CivsPaths.Weights, logs: Set<Material>, leaves: Set<Material>):
        ((CivPosition, BlockData) -> Double)? =
        if (plugin.server.pluginManager.isPluginEnabled("Trails")) null else weights.detached(logs, leaves)

    override fun invoke(ground: Block): Double {
        val provider = plugin.server.pluginManager.getPlugin("Trails")?.takeIf { it.isEnabled } ?: return 1.0
        if (incompatible === provider) return 1.0
        return try { CivsTrailsAdapter.speed(plugin, ground).coerceIn(0.2, 5.0) }
        catch (failure: LinkageError) { incompatible = provider; logFailure(failure); 1.0 }
    }

    /** Only the accepted road writer calls this; ordinary foundations are never registered. */
    fun registerSurface(cityId: UUID, position: CivPosition) {
        val provider = plugin.server.pluginManager.getPlugin("Trails")?.takeIf { it.isEnabled } ?: return
        if (incompatible === provider) return
        val city = cities().firstOrNull { it.id == cityId && it.pendingDeletion == null } ?: return
        if (!city.contains(position.x, position.z)) return
        val world = plugin.server.getWorld(city.worldId) ?: return
        if (!world.isChunkLoaded(position.x shr 4, position.z shr 4)) return
        try {
            CivsTrailsAdapter.registerSurface(plugin, world.getBlockAt(position.x, position.y, position.z))
                ?.whenComplete { _, failure -> if (failure != null) logFailure(failure) }
        } catch (failure: LinkageError) {
            incompatible = provider
            logFailure(failure)
        } catch (failure: Exception) { logFailure(failure) }
    }

    private fun logFailure(failure: Throwable) {
        val now = System.currentTimeMillis()
        if (now < nextFailureLog) return
        nextFailureLog = now + 60_000L
        plugin.logger.log(java.util.logging.Level.WARNING, "Trails road surface integration failed", failure)
    }
}

private object CivsTrailsAdapter {
    fun registerSurface(plugin: Plugin, ground: org.bukkit.block.Block): CompletionStage<Boolean>? =
        plugin.server.servicesManager.load(TrailsFootTrafficService::class.java)?.registerSurface(ground)
    fun speed(plugin: Plugin, ground: org.bukkit.block.Block): Double =
        plugin.server.servicesManager.load(TrailsFootTrafficService::class.java)?.speedMultiplier(ground) ?: 1.0
}
