package ru.arc.civs

import java.util.UUID

/** Ephemeral observed time: retirement, pause, reload and missed pulses never bank healing. */
internal class CivsResidentRegenerationClock {
    private data class Observation(val startedAt: Long, val lastAt: Long)
    private val observations = mutableMapOf<UUID, MutableMap<UUID, Observation>>()

    fun retain(cityIds: Set<UUID>) { observations.keys.retainAll(cityIds) }
    fun clear() { observations.clear() }

    fun observe(city: Settlement, awake: Set<UUID>, at: Long): Set<UUID> {
        val eligible = city.workers.filter { it.id in awake && CivsResidents.canRegenerate(it) }
        if (eligible.isEmpty()) {
            observations.remove(city.id)
            return emptySet()
        }
        val current = observations.getOrPut(city.id) { mutableMapOf() }
        current.keys.retainAll(eligible.mapTo(hashSetOf(), CivWorker::id))
        return eligible.mapNotNullTo(hashSetOf()) { worker ->
            // Resident life pulses every second; use the existing meal/rest observation gap allowance.
            val previous = current[worker.id]?.takeIf { at >= it.lastAt && at - it.lastAt <= 2_000L }
            val observed = previous?.copy(lastAt = at) ?: Observation(at, at)
            current[worker.id] = observed
            worker.id.takeIf { at - observed.startedAt >= 4_000L }
        }
    }

    /** A slow city write cannot accumulate a second heal for the residents in that write. */
    fun consumed(cityId: UUID, workerIds: Set<UUID>, at: Long) {
        val current = observations[cityId] ?: return
        workerIds.filter { it in current }.forEach { current[it] = Observation(at, at) }
    }
}
