package ru.arc.civs

import java.util.UUID

/** Resident viewer eligibility and retirement when an audience leaves. */
internal class CivsCitizenAudience(
    private val runtime: CitizenRuntime,
    private val viewerRange: Int,
    private val backgroundActive: (UUID) -> Boolean,
    private val remove: (CitizenActor, String) -> Unit,
) {

    fun viewers(excluding: UUID? = null): List<CitizenViewer> = runtime.plugin.server.onlinePlayers.asSequence()
        .filter { it.isOnline && it.uniqueId != excluding && !it.hasMetadata("NPC") }
        .map { CitizenViewer(it.world.uid, it.location.point()) }.toList()

    fun pruneViewers(excluding: UUID? = null) {
        val viewers = viewers(excluding)
        runtime.actors.values.toList().filter { !it.npc.isValid ||
            !activeAt(it.cityId, it.worldId, it.npc.location.point(), viewers) }
            .forEach { remove(it, "sleeping") }
    }

    fun activeAt(cityId: UUID, worldId: UUID, point: CitizenPoint, viewers: List<CitizenViewer>): Boolean =
        backgroundActive(cityId) || citizenHasViewer(worldId, point, viewers, viewerRange)

}
