package ru.arc.civs

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.logging.Level
import java.util.logging.Logger

/** Constructed on the source executor; retained values contain only plain coordinates. */
class CivSleepingFloorMask(points: Collection<CivPosition>) {
    val points: List<CivPosition> = points.toList()
    internal val cells: Set<CivPosition> = points.toHashSet()
}

/** Like workstation sources: bounded home identities and plain coordinates, never decoded schematics or native scans. */
internal class CivsSleepingFloors(
    private val load: (CivBuilding) -> CompletableFuture<CivSleepingFloorMask>,
    private val logger: Logger,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private data class Geometry(val type: BuildingType, val level: Int, val schematic: String,
        val anchor: CivPosition, val rotation: Int, val bounds: CivBox, val entrance: CivPosition) {
        constructor(building: CivBuilding) : this(building.type, building.level, building.schematicId,
            building.anchor, building.rotation, building.bounds, building.entrance)
    }
    private class Entry(val geometry: Geometry, val result: CompletableFuture<CivSleepingFloorMask>) {
        var retryAt = 0L
    }
    private val entries = linkedMapOf<Pair<UUID, UUID>, Entry>()
    private var closed = false

    /** Null means source pending; an empty result never authorizes outdoor sleep. */
    fun get(city: Settlement, building: CivBuilding): CivSleepingFloorMask? {
        checkCitizenMainThread()
        if (closed || building.phase != BuildingPhase.ACTIVE || city.building(building.id) != building) return null
        val key = city.worldId to building.id
        val geometry = Geometry(building)
        val now = clock()
        var entry = entries[key]
        if (entry == null || entry.geometry != geometry || entry.retryAt != 0L && now >= entry.retryAt) {
            entries.remove(key)?.result?.cancel(false)
            if (entries.size >= 64) entries.entries.first().let { oldest ->
                oldest.value.result.cancel(false)
                entries.remove(oldest.key)
            }
            val future = try { load(building) } catch (failure: Exception) { CompletableFuture.failedFuture(failure) }
            entry = Entry(geometry, future)
            entries[key] = entry
        }
        if (!entry.result.isDone || entry.retryAt != 0L) return null
        return try { entry.result.getNow(null) } catch (failure: Exception) {
            entry.retryAt = now + 30_000L
            logger.log(Level.WARNING, "ArcCivs sleeping floor source failed city=${city.id} building=${building.id} " +
                "schematic=${building.schematicId} retry_ms=30000", (failure as? CompletionException)?.cause ?: failure)
            null
        }
    }

    override fun close() {
        checkCitizenMainThread()
        closed = true
        entries.values.forEach { it.result.cancel(false) }
        entries.clear()
    }
}
