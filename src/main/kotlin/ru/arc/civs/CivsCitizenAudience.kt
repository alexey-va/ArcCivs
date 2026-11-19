package ru.arc.civs

import org.bukkit.plugin.Plugin
import java.util.UUID

internal fun citizenViewers(plugin: Plugin, excluding: UUID? = null): List<CitizenViewer> = plugin.server.onlinePlayers.asSequence()
    .filter { it.isOnline && it.uniqueId != excluding && !it.hasMetadata("NPC") }
    .map { CitizenViewer(it.world.uid, it.location.point()) }.toList()

/** Resident viewer eligibility and retirement when an audience leaves. */
internal class CivsCitizenAudience(
    private val runtime: CitizenRuntime,
    private val viewerRange: Int,
    private val backgroundActive: (UUID) -> Boolean,
    private val backgroundHydrating: (UUID) -> Boolean,
    private val remove: (CitizenActor, String) -> Unit,
) {

    fun viewers(excluding: UUID? = null): List<CitizenViewer> = citizenViewers(runtime.plugin, excluding)

    fun pruneViewers(excluding: UUID? = null): List<CitizenViewer> {
        val viewers = viewers(excluding)
        runtime.actors.values.toList().filter { !it.npc.isValid ||
            !hydrating(it.cityId) && !activeAt(it.cityId, it.worldId, it.npc.location.point(), viewers) }
            .forEach { remove(it, "sleeping") }
        return viewers
    }

    fun activeAt(cityId: UUID, worldId: UUID, point: CitizenPoint, viewers: List<CitizenViewer>): Boolean =
        !hydrating(cityId) && (backgroundActive(cityId) || citizenHasViewer(worldId, point, viewers, viewerRange))

    fun hydrating(cityId: UUID): Boolean = backgroundHydrating(cityId)

}
