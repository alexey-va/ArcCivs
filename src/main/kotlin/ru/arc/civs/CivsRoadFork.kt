package ru.arc.civs

import java.util.UUID

/** A short level landing where two paid streets fork below a change of grade. */
internal object CivsRoadFork {
    const val PATH = "minecraft:dirt_path"
    data class Landing(val floors: Set<CivPosition>, val main: List<CivPosition>, val branch: List<CivPosition>)

    fun replaceable(before: String) = before == CivsEntrancePath.GRASS || before == "minecraft:dirt" || before == "minecraft:air"

    private fun indices(route: List<CivPosition>) = route.withIndex().associate { it.value to it.index }

    fun landing(main: List<CivPosition>, branch: List<CivPosition>, branchAt: Map<CivPosition, Int> = indices(branch)): Landing? {
        for (i in 2 until main.lastIndex) {
            val root = main[i]
            val j = branchAt[root] ?: continue
            if (j !in 2 until branch.lastIndex || main[i - 1] != branch[j - 1] || main[i - 2] != branch[j - 2]) continue
            val dx = main[i + 1].x - root.x
            val dz = main[i + 1].z - root.z
            val sx = branch[j + 1].x - root.x
            val sz = branch[j + 1].z - root.z
            if (dx * sx + dz * sz != 0 || root.x - main[i - 1].x != dx || root.z - main[i - 1].z != dz) continue
            fun point(lane: Int, depth: Int) = root.copy(x = root.x + lane * dx + depth * sx, z = root.z + lane * dz + depth * sz)
            val width = (1..5).takeWhile { main.getOrNull(i + it) == point(it, 0) }.size
            if (width !in 4..5 || main.getOrNull(i + width + 1) != point(width, 1)) continue
            var sideIndex = j
            var lane = 0
            val sideLanes = mutableListOf(0)
            var splitGrade = false
            for (depth in 1..9) {
                val trunk = main.getOrNull(i + width + depth) ?: break
                val side = branch.getOrNull(sideIndex + 1) ?: break
                if (trunk.copy(y = root.y) != point(width, depth) || side.copy(y = root.y) != point(lane, depth)) break
                if (trunk.y != root.y || side.y != root.y) {
                    splitGrade = trunk.y != side.y
                    break
                }
                sideIndex++
                if (lane == 0 && branch.getOrNull(sideIndex + 1) == point(1, depth)) {
                    lane++
                    sideIndex++
                }
                sideLanes += lane
            }
            // Keep the row immediately below the first tread clear. The two entrances retain their own grades.
            if (!splitGrade || sideLanes.size < 5) continue
            val end = sideLanes.lastIndex - 1
            val floors = buildSet {
                for (depth in 2..end) for (s in sideLanes[depth] + 2..width - 2)
                    add(point(s, depth).copy(y = root.y - 1))
            }
            return Landing(floors, main.subList(i, i + width + end + 1), branch.subList(j, sideIndex + 1))
        }
        return null
    }

    fun current(city: Settlement, access: CivAutonomousAccess, target: CivRoadUpkeep): Boolean {
        val partner = target.forkPartner ?: return false
        return city.construction == null && city.constructionQueue.isNullOrEmpty() &&
            city.building(partner.source.id) == partner.source && target.source.id in access.sharedRoadCaptures &&
            partner.source.id in access.sharedRoadCaptures && access.routes[target.source.id] == target.route &&
            access.routes[partner.source.id] == partner.route
    }

    /** Run only while rebuilding the detached paid network. Native selection uses the ordinary per-tick builder budget. */
    fun targets(city: Settlement, access: CivAutonomousAccess, context: CivAutonomousAccessContext, profiles: Map<UUID, Map<Long, CivPosition>>,
        occupied: Set<Long>, excluded: (CivPosition) -> Boolean): List<CivRoadUpkeep> {
        if (city.construction != null || !city.constructionQueue.isNullOrEmpty()) return emptyList()
        val roads = access.sharedRoadCaptures.values.filter { !it.source.type.isInfrastructure && it.source.id in access.routes }
        if (roads.size < 2 || !CivsWallApproachCleanup.claimsComplete(city, access.routes.keys, context::adoptedRoute)) return emptyList()
        val reserved = occupied.toMutableSet()
        for (building in city.buildings) {
            building.transit.orEmpty().forEach { reserved += civsTerrainKey(it.x, it.z) }
            if (!building.type.isInfrastructure) CivsAutonomousGeometry.approach(building).forEach { reserved += civsTerrainKey(it.x, it.z) }
            if (building.id !in profiles) context.adoptedRoute(building.id)?.let { reserved += CivsAutonomousTerraform.roadProfile(it).keys }
        }
        val indices = roads.associate { it.source.id to indices(access.routes.getValue(it.source.id)) }
        val result = linkedMapOf<Long, CivRoadUpkeep>()
        for (main in roads) for (side in roads) {
            if (main === side || main.source.entrance.y == side.source.entrance.y) continue
            val route = access.routes.getValue(main.source.id)
            val branch = access.routes.getValue(side.source.id)
            val landing = landing(route, branch, indices.getValue(side.source.id)) ?: continue
            if (landing.floors.any { p ->
                    val key = civsTerrainKey(p.x, p.z)
                    key in reserved || excluded(p) || profiles.any { (id, profile) ->
                        val prior = profile[key]
                        prior != null && (id != main.source.id && id != side.source.id || prior.y != p.y)
                    } || access.reservedFortificationPorts.any { CivsAutonomousGeometry.horizontalContains(it, p) }
                }) continue
            val partner = CivRoadJunctionRoute(side.source, branch)
            for (p in landing.floors) result.putIfAbsent(civsTerrainKey(p.x, p.z),
                CivRoadUpkeep(city.id, city.worldId, city.owner, main.source.id, main.source, p, PATH, route, emptyList(), forkPartner = partner))
        }
        return result.values.toList()
    }
}
