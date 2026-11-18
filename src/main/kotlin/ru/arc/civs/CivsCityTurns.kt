package ru.arc.civs

import java.util.UUID

/** Each background job owns its own turns. Pass the full city map to clean up removed cities. */
internal class CivsCityTurns {
    private val dueAt = hashMapOf<UUID, Long>()

    /** Advance the oldest eligible city's deadline as soon as its attempt starts. */
    fun next(cities: Map<UUID, Settlement>, now: Long, intervalMillis: Long, eligible: (Settlement) -> Boolean): Settlement? {
        dueAt.keys.retainAll(cities.keys)
        var selected: Settlement? = null
        var oldestDueAt = Long.MAX_VALUE
        for (city in cities.values) {
            val deadline = dueAt[city.id] ?: Long.MIN_VALUE
            if (deadline > now || deadline >= oldestDueAt || !eligible(city)) continue
            selected = city
            if (deadline == Long.MIN_VALUE) break
            oldestDueAt = deadline
        }
        return selected?.also { defer(it.id, now, intervalMillis) }
    }

    fun defer(cityId: UUID, now: Long, intervalMillis: Long) {
        dueAt[cityId] = now + intervalMillis
    }

    fun clear(cityId: UUID) {
        dueAt.remove(cityId)
    }

    fun clear() = dueAt.clear()
}
