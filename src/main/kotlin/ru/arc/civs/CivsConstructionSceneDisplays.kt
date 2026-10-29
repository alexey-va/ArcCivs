package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.paper.api.ArcSceneBounds
import ru.arc.paper.api.ArcSceneDisplay
import ru.arc.paper.api.ArcSceneDisplayContent
import ru.arc.paper.api.ArcSceneDisplayPose
import ru.arc.paper.api.ArcSceneEntity
import ru.arc.paper.api.ArcSceneEntitySource
import ru.arc.paper.api.ArcSceneItem
import ru.arc.paper.api.ArcSceneQuaternion
import ru.arc.paper.api.ArcSceneVector3

/** Explicit public construction fences only; private boundary/placement guidance stays native. */
internal class CivsConstructionSceneDisplays(
    private val cities: () -> Collection<Settlement>, private val ready: () -> Boolean,
) : ArcSceneEntitySource {
    override fun snapshot(bounds: ArcSceneBounds, limit: Int): List<ArcSceneEntity> {
        check(Bukkit.isPrimaryThread()) { "Construction scene capture requires the server thread" }
        return emptyList()
    }

    override fun displays(bounds: ArcSceneBounds, limit: Int): List<ArcSceneDisplay> {
        check(Bukkit.isPrimaryThread()) { "Construction scene capture requires the server thread" }
        return if (!ready() || limit <= 0) emptyList() else civsConstructionSceneDisplays(cities(), bounds, limit)
    }
}

/** Reuse the native fence's exact cuboids, with no entity, chunk or block lookup. */
internal fun civsConstructionSceneDisplays(cities: Collection<Settlement>, bounds: ArcSceneBounds,
    limit: Int): List<ArcSceneDisplay> {
    val maximum = limit.coerceIn(0, 128)
    if (maximum == 0) return emptyList()
    val sites = cities.asSequence().filter { it.worldId == bounds.worldId && it.pendingDeletion == null }
        .flatMap { city ->
            val selected = city.buildings.filter { it.phase != BuildingPhase.ACTIVE || it.id == city.construction?.buildingId }
                .associateByTo(linkedMapOf()) { it.id }
            city.construction?.plannedBuilding?.let { selected[it.id] = it }
            selected.values.asSequence().filter { building ->
                val box = building.bounds
                box.min.x - .125 < bounds.maxX && box.max.x.toLong() + 1.125 >= bounds.minX &&
                    box.min.z - .125 < bounds.maxZ && box.max.z.toLong() + 1.125 >= bounds.minZ &&
                    building.entrance.y < bounds.maxY && building.entrance.y + 1.25 >= bounds.minY
            }.map { city to it }
        }.distinctBy { (city, site) -> city.id to site.id }.take(16)
    val ids = hashSetOf<String>()
    return buildList {
        for ((city, site) in sites) {
            val selected = city.copy(buildings = listOf(site), construction = city.construction?.takeIf { it.buildingId == site.id })
            val x = (site.bounds.min.x.toDouble() + site.bounds.max.x + 1.0) / 2
            val z = (site.bounds.min.z.toDouble() + site.bounds.max.z + 1.0) / 2
            for (part in civsConstructionFenceGeometry(selected, x, site.entrance.y.toDouble(), z)) {
                val cuboid = part.cuboid
                if (cuboid.material != Material.OAK_PLANKS) continue
                val cx = cuboid.x + cuboid.sx / 2.0
                val cy = cuboid.y + cuboid.sy / 2.0
                val cz = cuboid.z + cuboid.sz / 2.0
                if (!bounds.contains(city.worldId, cx, cy, cz)) continue
                val key = part.key
                val id = "civs-fence:${city.id}:${key.buildingId}:${key.side}:${key.segment}:${key.post}:${key.layer}"
                if (!ids.add(id)) continue
                add(ArcSceneDisplay(city.worldId, id,
                    ArcSceneDisplayPose(cx, cy, cz, 0f, 0f, ArcSceneVector3(0f, 0f, 0f),
                        ArcSceneVector3(cuboid.sx, cuboid.sy, cuboid.sz),
                        ArcSceneQuaternion(0f, 0f, 0f, 1f), ArcSceneQuaternion(0f, 0f, 0f, 1f)),
                    "FIXED", null, null, 1f, 0f, 0f,
                    ArcSceneDisplayContent.Item(ArcSceneItem("minecraft:oak_planks", 1, null, null, null), "NONE"), null))
                if (size == maximum) return@buildList
            }
        }
    }
}
