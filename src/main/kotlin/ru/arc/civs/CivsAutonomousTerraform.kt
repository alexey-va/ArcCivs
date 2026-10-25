package ru.arc.civs

import java.util.PriorityQueue
import kotlin.math.abs

/** Detached height field: no Bukkit object crosses into the planning executor. */
internal data class CivTerrainSurface(
    val minHeight: Int,
    val maxHeight: Int,
    val columns: Map<Long, CivTerrainSurfaceColumn>,
) {
    fun at(x: Int, z: Int): CivTerrainSurfaceColumn? = columns[civsTerrainKey(x, z)]
}

internal data class CivTerrainSurfaceColumn(val groundY: Int, val natural: Boolean, val loaded: Boolean = true,
    val topY: Int = groundY)

/** Native capture covers [supportY, maxOf(surfaceY, groundY) + 2]. */
internal data class CivTerraformColumn(val x: Int, val z: Int, val groundY: Int, val floorY: Int,
    val surfaceY: Int, val preserve: Boolean = false,
    val supportY: Int = floorY - CivsAutonomousTerraform.MAXIMUM_CHANGE)

internal data class CivTerraformDraft(val building: CivBuilding, val columns: List<CivTerraformColumn>,
    val route: List<CivPosition>, val groundY: Int, val access: CivAutonomousAccess = CivAutonomousAccess())

/** Exact native state; [supportSafe] permits retaining a solid block, never changing it. */
internal data class CivTerraformCell(val position: CivPosition, val data: String, val safe: Boolean,
    val solid: Boolean, val passable: Boolean, val supportSafe: Boolean = safe)

internal fun civsTerrainKey(x: Int, z: Int): Long = (x.toLong() shl 32) xor (z.toLong() and 0xffff_ffffL)

internal data class CivTerraformOption(val building: CivBuilding, val effort: Long)

/** Finite cut/fill terraces and a graded, three-block-wide connection to an existing entrance. */
internal object CivsAutonomousTerraform {
    const val MAXIMUM_CHANGE = 24
    const val MAXIMUM_SURFACE_COLUMNS = 65_536
    const val MAXIMUM_CAPTURE_CELLS = 131_072
    const val MAXIMUM_CHANGED_CELLS = 32_768
    const val MAXIMUM_ROUTE_NODES = 32_768
    const val MAXIMUM_ROUTE_LENGTH = 256

    /** A median terrace minimises excavation; nearby levels allow a less costly graded connection. */
    fun options(city: Settlement, candidates: List<CivsAutonomousCandidate>, surface: CivTerrainSurface,
        fixed: Boolean = false, rejected: (String, CivPosition) -> Unit = { _, _ -> }): List<CivTerraformOption> = candidates.flatMap { candidate ->
        val b = candidate.building
        val heights = ArrayList<Int>()
        for (x in b.bounds.min.x..b.bounds.max.x) for (z in b.bounds.min.z..b.bounds.max.z) {
            val c = surface.at(x, z)
            if (c == null || !c.loaded) {
                rejected("chunks-unloaded", CivPosition(x, c?.groundY ?: b.anchor.y, z))
                return@flatMap emptyList()
            }
            val ownMarker = fixed && b.control?.let { it.x == x && it.z == z } == true
            if (!c.natural && !ownMarker) {
                rejected("autonomy-terrain", CivPosition(x, c.groundY, z))
                return@flatMap emptyList()
            }
            heights += if (ownMarker) b.anchor.y else c.groundY
        }
        if (heights.isEmpty()) return@flatMap emptyList()
        heights.sort()
        val median = heights[heights.size / 2]
        val levels = if (fixed) listOf(b.anchor.y) else listOf(median, median - 4, median + 4).distinct()
        levels.mapNotNull { y ->
            val building = CivsAutonomousGeometry.atHeight(b, y)
            if (heights.any { abs(it - y) > MAXIMUM_CHANGE }) {
                rejected("autonomy-slope", building.anchor)
                return@mapNotNull null
            }
            if (building.bounds.min.y <= surface.minHeight || building.bounds.max.y >= surface.maxHeight - 2) {
                rejected("outside-world", building.anchor)
                return@mapNotNull null
            }
            val nearest = sources(city, building).minOfOrNull { source ->
                abs(source.x - building.entrance.x) + abs(source.z - building.entrance.z) +
                    abs(source.y - building.entrance.y) * 3
            } ?: return@mapNotNull null
            CivTerraformOption(building, heights.sumOf { abs(it - y).toLong() } + nearest * 4L)
        }
    }.sortedWith(compareBy<CivTerraformOption> { it.effort }.thenBy { it.building.entrance.x }
        .thenBy { it.building.entrance.z }.thenBy { it.building.anchor.y })

    fun draft(city: Settlement, option: CivTerraformOption, surface: CivTerrainSurface,
        access: CivAutonomousAccess = CivAutonomousAccess(), additionalObstacles: List<CivBox> = emptyList(),
        infrastructureFloors: Map<Long, Int> = emptyMap()): CivTerraformDraft {
        val building = option.building
        val standing = access.standing(city, building.id)
        if (!building.type.isInfrastructure && standing.values.any { CivsAutonomousGeometry.horizontalContains(building.bounds, it) })
            throw CivsRejection("building-overlap")
        val obstacles = (city.buildings + listOfNotNull(city.construction?.plannedBuilding))
            .filter { it.id != building.id && !civsPublicTransit(it) }.distinctBy { it.id }
            .map { CivsAutonomousGeometry.futureBounds(city, it) }
        val routeObstacles = obstacles + additionalObstacles
        val route = sources(city, building).asSequence().mapNotNull {
            route(city, building, surface, routeObstacles, standing, it)
        }.firstOrNull() ?: throw CivsRejection("autonomy-no-path")
        val columns = linkedMapOf<Long, CivTerraformColumn>()
        val centerline = route.associateBy { civsTerrainKey(it.x, it.z) }
        val profile = roadProfile(route)
        fun add(x: Int, z: Int, ground: Int, floor: Int, preserve: Boolean = false) {
            if (!city.contains(x, z) || obstacles.any { CivsAutonomousGeometry.horizontalContains(it, CivPosition(x, ground, z)) })
                throw CivsRejection("building-overlap")
            val native = surface.at(x, z) ?: throw CivsRejection("autonomy-no-path")
            if (!native.loaded) throw CivsRejection("chunks-unloaded")
            val key = civsTerrainKey(x, z)
            val protected = standing[key]
            if (protected != null && ground != protected.y - 1) throw CivsRejection("autonomy-no-path")
            val retained = preserve || protected != null
            val control = building.control?.let { it.x == x && it.z == z } == true && city.building(building.id) != null
            if (!retained && !native.natural && !control) throw CivsRejection("autonomy-terrain")
            if (!retained && abs(native.groundY - ground) > MAXIMUM_CHANGE) throw CivsRejection("autonomy-slope")
            if (floor <= surface.minHeight || ground + 2 >= surface.maxHeight) throw CivsRejection("outside-world")
            val existing = columns[key]
            // Existing entrances and roads keep their whole column, including support below the surface.
            if (existing?.preserve == true) return
            columns[key] = CivTerraformColumn(x, z, ground, floor, native.topY, retained)
        }
        // Centreline first establishes the exact grade; shoulders follow the nearest route cell.
        // Both are interleaved by progress, so a worker never needs a future terrace to start work.
        route.forEachIndexed { index, point ->
            val ground = point.y - 1
            add(point.x, point.z, ground, ground, index == 0)
            for (dx in -1..1) for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                val x = point.x + dx; val z = point.z + dz
                val key = civsTerrainKey(x, z)
                if (key in centerline || key in columns || key in standing ||
                    CivsAutonomousGeometry.horizontalContains(building.bounds, CivPosition(x, ground, z)) ||
                    routeObstacles.any { CivsAutonomousGeometry.horizontalContains(it, CivPosition(x, ground, z)) }) continue
                // Unavailable shoulder terrain does not invalidate a safe centre corridor. It is
                // left untouched; native body/step validation remains authoritative.
                val native = surface.at(x, z)
                val shoulderGround = profile.getValue(key).y
                if (native == null || !native.loaded || !native.natural || abs(native.groundY - shoulderGround) > MAXIMUM_CHANGE) continue
                add(x, z, shoulderGround, shoulderGround)
            }
        }
        // Short bumps return to the same grade at both ends. Ease only their cut banks;
        // the next ring rises by one block, while long hills and all paving keep their width.
        var start = 0
        while (start < route.size) {
            val point = route[start]
            val ground = point.y - 1
            if (surface.at(point.x, point.z)!!.groundY <= ground) { start++; continue }
            var end = start + 1
            while (end < route.size && route[end].y == point.y &&
                surface.at(route[end].x, route[end].z)!!.groundY > ground) end++
            val before = route.getOrNull(start - 1)
            val after = route.getOrNull(end)
            if (end - start <= 5 && before != null && after != null && before.y == point.y && after.y == point.y &&
                surface.at(before.x, before.z)!!.groundY <= ground &&
                surface.at(after.x, after.z)!!.groundY <= ground &&
                (start until end).all { surface.at(route[it].x, route[it].z)!!.groundY <= ground + 3 }) {
                for (index in start until end) for (dx in -3..3) for (dz in -3..3) {
                    val x = route[index].x + dx; val z = route[index].z + dz
                    val key = civsTerrainKey(x, z)
                    val shoulder = ground + (maxOf(abs(dx), abs(dz)) - 2).coerceAtLeast(0)
                    if (key in profile || key in standing || !city.contains(x, z) ||
                        CivsAutonomousGeometry.horizontalContains(building.bounds, CivPosition(x, shoulder, z)) ||
                        routeObstacles.any { CivsAutonomousGeometry.horizontalContains(it, CivPosition(x, shoulder, z)) }) continue
                    val native = surface.at(x, z)
                    if (native == null || !native.loaded || !native.natural || native.groundY !in shoulder + 1..ground + 3 ||
                        columns[key]?.groundY?.let { it <= shoulder } == true) continue
                    add(x, z, shoulder, shoulder)
                }
            }
            start = end
        }
        CivsAutonomousGeometry.approach(building).forEach { point ->
            add(point.x, point.z, building.entrance.y - 1, building.entrance.y - 1,
                point.x == route.first().x && point.z == route.first().z)
        }
        val floor = minOf(building.anchor.y, building.bounds.min.y - 1)
        for (x in building.bounds.min.x..building.bounds.max.x) for (z in building.bounds.min.z..building.bounds.max.z)
            if (building.type.isInfrastructure) {
                val ground = infrastructureFloors[civsTerrainKey(x, z)] ?: throw CivsRejection("invalid-plan")
                add(x, z, ground, ground)
            } else add(x, z, building.anchor.y, floor)
        val count = columns.values.sumOf {
            (maxOf(it.surfaceY, it.groundY) + 2 - maxOf(surface.minHeight, it.floorY - MAXIMUM_CHANGE) + 1).toLong()
        }
        if (count > MAXIMUM_CAPTURE_CELLS) throw CivsRejection("autonomy-search-budget")
        return CivTerraformDraft(building, columns.values.toList(), route, building.anchor.y, access)
    }

    /** Exact before-images, ordered approach-first, with cuts top-down and fill from real support up. */
    fun materialize(draft: CivTerraformDraft, cells: Map<CivPosition, CivTerraformCell>): CivTerraformPlan {
        val result = linkedMapOf<CivPosition, CivPlanBlock>()
        val routeColumns = draft.route.mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }
        fun at(x: Int, y: Int, z: Int) = cells[CivPosition(x, y, z)] ?: throw CivsRejection("invalid-plan")
        fun rejectTerrain(cell: CivTerraformCell, stage: String): Nothing = throw CivsRejection("autonomy-terrain",
            mapOf("x" to cell.position.x.toString(), "y" to cell.position.y.toString(),
                "z" to cell.position.z.toString(), "block" to cell.data, "stage" to stage))
        fun change(cell: CivTerraformCell, after: String) {
            if (cell.position == draft.building.control || cell.data == after) return
            if (!cell.safe) rejectTerrain(cell, "change")
            result[cell.position] = CivPlanBlock(cell.position, cell.data, after)
            if (result.size > MAXIMUM_CHANGED_CELLS) throw CivsRejection("autonomy-search-budget")
        }
        draft.columns.forEach { column ->
            val x = column.x; val z = column.z; val ground = column.groundY
            if (column.preserve) {
                if (!at(x, ground, z).solid || !at(x, ground + 1, z).passable || !at(x, ground + 2, z).passable)
                    throw CivsRejection("autonomy-no-path")
                return@forEach
            }
            val support = (column.floorY downTo column.supportY).firstOrNull { y ->
                cells[CivPosition(x, y, z)]?.let { it.solid && it.data.substringBefore('[') !in FALLING_GROUND } == true
            } ?: throw CivsRejection("autonomy-foundation")
            val base = at(x, support, z)
            if (!base.safe && !(draft.route.isEmpty() && base.supportSafe) &&
                base.data != "minecraft:bedrock" && base.position != draft.building.control)
                rejectTerrain(base, "support")
            // Clearing first also makes ascent headroom usable before the next route column is reached.
            for (y in maxOf(column.surfaceY, ground) + 2 downTo ground + 1) {
                val cell = at(x, y, z)
                if (!air(cell.data)) change(cell, "minecraft:air")
            }
            for (y in support + 1..ground) {
                val cell = at(x, y, z)
                if (cell.position == draft.building.control) continue
                if (draft.route.isEmpty() && cell.solid && cell.supportSafe) continue
                if (!cell.safe) rejectTerrain(cell, "fill")
                if (!cell.solid) change(cell, when {
                    draft.route.isEmpty() -> "minecraft:dirt"
                    y == ground -> if (civsTerrainKey(x, z) in routeColumns ||
                        CivsAutonomousGeometry.horizontalContains(draft.building.bounds, cell.position)) "minecraft:coarse_dirt"
                        else "minecraft:grass_block[snowy=false]"
                    y >= ground - 2 -> "minecraft:dirt"
                    else -> "minecraft:stone"
                })
            }
            if (civsTerrainKey(x, z) in routeColumns) change(at(x, ground, z), "minecraft:coarse_dirt")
            else if (draft.route.isNotEmpty() && column.surfaceY > ground &&
                !CivsAutonomousGeometry.horizontalContains(draft.building.bounds, CivPosition(x, ground, z)))
                change(at(x, ground, z), "minecraft:grass_block[snowy=false]")
        }
        val points = result.keys + draft.route
        if (points.isEmpty()) return CivTerraformPlan(draft.building.bounds, emptyList(), emptyList(), draft.groundY, 0, 0)
        val bounds = CivBox(CivPosition(points.minOf { it.x }, points.minOf { it.y }, points.minOf { it.z }),
            CivPosition(points.maxOf { it.x }, points.maxOf { it.y }, points.maxOf { it.z }))
        val blocks = result.values.toList()
        return CivTerraformPlan(bounds, blocks, draft.route.toList(), draft.groundY,
            blocks.count { air(it.after) }, blocks.count { !air(it.after) })
    }

    /** Three-wide road surface. Equal-distance shoulders use the lower grade, never a raised curb. */
    fun roadProfile(route: List<CivPosition>): Map<Long, CivPosition> {
        val owners = linkedMapOf<Long, CivPosition>()
        for (point in route) for (dx in -1..1) for (dz in -1..1) {
            val x = point.x + dx; val z = point.z + dz
            val key = civsTerrainKey(x, z)
            val previous = owners[key]
            val distance = abs(dx) + abs(dz)
            val previousDistance = previous?.let { abs(it.x - x) + abs(it.z - z) }
            if (previous == null || distance < requireNotNull(previousDistance) ||
                distance == previousDistance && point.y < previous.y) owners[key] = point
        }
        return owners.mapValues { (key, point) -> CivPosition((key shr 32).toInt(), point.y - 1, key.toInt()) }
    }

    private fun sources(city: Settlement, building: CivBuilding): List<CivPosition> =
        city.buildings.filter { CivsRules.isDepot(it) || it.type == BuildingType.TOWN_HALL }
            .map { it.entrance }.distinct().sortedBy {
                abs(it.x - building.entrance.x) + abs(it.z - building.entrance.z)
            }

    private data class Node(val x: Int, val y: Int, val z: Int)
    private data class Open(val node: Node, val cost: Int, val score: Int, val ordinal: Int)

    private fun route(city: Settlement, building: CivBuilding, surface: CivTerrainSurface, obstacles: List<CivBox>,
        standing: Map<Long, CivPosition>, from: CivPosition): List<CivPosition>? {
        val start = Node(from.x, from.y - 1, from.z)
        val to = building.entrance
        val target = Node(to.x, to.y - 1, to.z)
        val goalApproach = CivsAutonomousGeometry.approach(building).map { civsTerrainKey(it.x, it.z) }.toSet()
        fun distance(n: Node) = (abs(n.x - target.x) + abs(n.z - target.z)) * 10 + abs(n.y - target.y) * 4
        fun allowed(x: Int, y: Int, z: Int): Boolean {
            if (!city.contains(x, z) || y <= surface.minHeight || y + 2 >= surface.maxHeight) return false
            val p = CivPosition(x, y, z)
            if ((!civsPublicTransit(building, allowPlanned = true) && CivsAutonomousGeometry.horizontalContains(building.bounds, p)) ||
                obstacles.any { CivsAutonomousGeometry.horizontalContains(it, p) }) return false
            val key = civsTerrainKey(x, z)
            if (key in goalApproach && y != target.y) return false
            val native = surface.at(x, z) ?: return false
            standing[key]?.let { return native.loaded && y == it.y - 1 }
            return native.loaded && native.natural && abs(native.groundY - y) <= MAXIMUM_CHANGE
        }
        if (start == target) return listOf(from)
        val open = PriorityQueue(compareBy<Open> { it.score }.thenBy { it.ordinal })
        val costs = hashMapOf(start to 0)
        val parents = hashMapOf<Node, Node>()
        val closed = hashSetOf<Node>()
        var ordinal = 0
        open += Open(start, 0, distance(start), ordinal++)
        var expanded = 0
        while (open.isNotEmpty() && expanded++ < MAXIMUM_ROUTE_NODES) {
            val current = open.remove()
            if (costs[current.node] != current.cost || !closed.add(current.node)) continue
            val n = current.node
            if (n == target) {
                val reversed = mutableListOf(n)
                var cursor = n
                while (cursor != start) { cursor = parents[cursor] ?: return null; reversed += cursor }
                if (reversed.size > MAXIMUM_ROUTE_LENGTH || reversed.map { civsTerrainKey(it.x, it.z) }.distinct().size != reversed.size)
                    return null
                return reversed.asReversed().map { CivPosition(it.x, it.y + 1, it.z) }
            }
            for ((dx, dz) in DIRECTIONS) for (dy in 0..2) {
                val candidate = Node(n.x + dx, n.y + when (dy) { 1 -> -1; 2 -> 1; else -> 0 }, n.z + dz)
                if (candidate in closed || !allowed(candidate.x, candidate.y, candidate.z) ||
                    candidate.x == start.x && candidate.z == start.z) continue
                // A column has exactly one finished ground height. A cheap 3-D route can otherwise
                // climb by zigzagging through the same two columns at successively higher levels.
                // Exclude those ancestors while searching, so the solver finds an actual detour.
                var ancestor: Node? = n
                var repeatedColumn = false
                while (ancestor != null) {
                    if (ancestor.x == candidate.x && ancestor.z == candidate.z) { repeatedColumn = true; break }
                    ancestor = parents[ancestor]
                }
                if (repeatedColumn) continue
                val native = requireNotNull(surface.at(candidate.x, candidate.z))
                // A brief hummock or hollow should be cut/filled instead of adding two gratuitous climbs.
                val cost = current.cost + 10 + abs(candidate.y - n.y) * 8 + abs(native.groundY - candidate.y) * 2
                if (cost >= costs.getOrDefault(candidate, Int.MAX_VALUE)) continue
                costs[candidate] = cost; parents[candidate] = n
                open += Open(candidate, cost, cost + distance(candidate), ordinal++)
            }
        }
        return null
    }

    private fun air(data: String) = data.substringBefore('[') in setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air")
    private val DIRECTIONS = listOf(0 to 1, 1 to 0, 0 to -1, -1 to 0)
    private val FALLING_GROUND = setOf("minecraft:sand", "minecraft:red_sand", "minecraft:gravel")
}

/** Only exact journaled centreline surfaces qualify; similarly coloured foundation fill does not. */
internal fun CivTerraformPlan.preparedRouteSurfaces(): List<CivPlanBlock> {
    val surfaces = route.mapTo(hashSetOf()) { it.copy(y = it.y - 1) }
    return blocks.filter { it.position in surfaces && it.after == "minecraft:coarse_dirt" }
}
