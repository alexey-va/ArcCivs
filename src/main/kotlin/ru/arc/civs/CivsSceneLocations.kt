package ru.arc.civs

import ru.arc.paper.api.ArcSceneLocation

/** Public saved anchors only; no world lookup, membership, stock or inventory crosses the bridge. */
internal fun civsSceneLocations(cities: Collection<Settlement>, limit: Int): List<ArcSceneLocation> =
    cities.asSequence().filter { it.pendingDeletion == null }.take(limit.coerceIn(0, 32)).map { city ->
        ArcSceneLocation("civs:${city.id}", city.name, city.worldId,
            city.center.x.toDouble(), city.center.y.toDouble(), city.center.z.toDouble(), city.radius.toDouble())
    }.toList()
