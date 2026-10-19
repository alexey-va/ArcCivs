package ru.arc.civs

import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.ruscrafting.trails.api.TrailsFootTrafficService
import java.util.UUID
import java.util.concurrent.CompletionStage

/** Optional integration; Trails alone owns counters, terrain changes and protection probes. */
internal class CivsFootTraffic(private val plugin: Plugin, private val cities: () -> Collection<Settlement>) {
    private var incompatible: Plugin? = null
    private var nextFailureLog = 0L

    fun record(cityId: UUID, workerId: UUID, entity: Entity, from: Location, to: Location) {
        val provider = plugin.server.pluginManager.getPlugin("Trails")?.takeIf { it.isEnabled } ?: return
        if (incompatible === provider) return
        val city = cities().firstOrNull { it.id == cityId && it.pendingDeletion == null } ?: return
        if (city.worldId != from.world?.uid || city.worldId != to.world?.uid ||
            city.workers.none { it.id == workerId } || !city.contains(from.blockX, from.blockZ) ||
            !city.contains(to.blockX, to.blockZ)) return
        // Buildings keep their authored floors. Even a valid destination never receives trail wear.
        if (city.buildings.any { it.bounds.contains(from.blockX, from.blockY - 1, from.blockZ) ||
                it.bounds.contains(to.blockX, to.blockY - 1, to.blockZ) || CivsWorksiteLayouts.isField(it, from.blockX, from.blockZ) ||
                CivsWorksiteLayouts.isField(it, to.blockX, to.blockZ) }) return
        val authority = (sequenceOf(city.owner) + city.members.asSequence()).mapNotNull(plugin.server::getPlayer)
            .firstOrNull { it.isOnline && !it.hasMetadata("NPC") && it.world.uid == city.worldId } ?: return
        try {
            // The separate adapter is resolved only with an enabled Trails provider, never on other backends.
            CivsTrailsAdapter.record(plugin, entity, authority, from, to)?.whenComplete { _, failure ->
                if (failure != null) logFailure(failure)
            }
        } catch (failure: LinkageError) {
            incompatible = provider // Older Trails has no typed API; optional integration waits for its upgrade.
            logFailure(failure)
        } catch (failure: Exception) {
            logFailure(failure)
        }
    }

    fun speed(ground: org.bukkit.block.Block): Double {
        val provider = plugin.server.pluginManager.getPlugin("Trails")?.takeIf { it.isEnabled } ?: return 1.0
        if (incompatible === provider) return 1.0
        return try { CivsTrailsAdapter.speed(plugin, ground).coerceIn(0.2, 5.0) }
        catch (failure: LinkageError) { incompatible = provider; logFailure(failure); 1.0 }
    }

    private fun logFailure(failure: Throwable) {
        val now = System.currentTimeMillis()
        if (now < nextFailureLog) return
        nextFailureLog = now + 60_000L
        plugin.logger.log(java.util.logging.Level.WARNING, "NPC footstep could not be processed by Trails", failure)
    }
}

private object CivsTrailsAdapter {
    fun speed(plugin: Plugin, ground: org.bukkit.block.Block): Double =
        plugin.server.servicesManager.load(TrailsFootTrafficService::class.java)?.speedMultiplier(ground) ?: 1.0
    fun record(plugin: Plugin, entity: Entity, authority: Player, from: Location, to: Location): CompletionStage<*>? =
        plugin.server.servicesManager.load(TrailsFootTrafficService::class.java)?.recordStep(entity, authority, from, to)
}
