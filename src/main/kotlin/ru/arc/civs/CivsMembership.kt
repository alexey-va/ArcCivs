package ru.arc.civs

import java.util.UUID

/** Names are presentation metadata; UUIDs remain the only authority for membership. */
internal object CivsMembership {
    fun remember(city: Settlement, known: Map<UUID, String>): Settlement {
        val participants = (city.members + city.owner).associateBy(UUID::toString)
        val names = city.memberNames.orEmpty().filterKeys { it in participants }.toMutableMap()
        known.forEach { (id, name) ->
            if (id.toString() in participants && name.isNotBlank() && name.length <= 32 &&
                name.none(Char::isISOControl)) names[id.toString()] = name
        }
        return city.copy(memberNames = names.takeIf { it.isNotEmpty() }?.toMap())
    }
}
