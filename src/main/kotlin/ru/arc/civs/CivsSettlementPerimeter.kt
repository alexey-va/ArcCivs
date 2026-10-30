package ru.arc.civs

import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs

/** A bounded separator around adopted parcels. Produces ordinary wall/gate rectangles, never world writes. */
internal object CivsSettlementPerimeter {
    private val exterior = CivsSettlementLayout.exteriorTypes
    private val neighbours = listOf(-1 to -1, 0 to -1, 1 to -1, -1 to 0, 1 to 0, -1 to 1, 0 to 1, 1 to 1)

    fun adapt(city: Settlement, frame: CivSettlementFrame, surface: CivTerrainSurface,
        routes: Map<UUID, List<CivPosition>>, retainedRoutes: Collection<List<CivPosition>>): CivSettlementFrame {
        val box = frame.core
        val width = box.max.x - box.min.x + 1
        val height = box.max.z - box.min.z + 1
        require(width in 1..113 && height in 1..113)
        val area = width * height
        fun index(x: Int, z: Int): Int? = if (x in box.min.x..box.max.x && z in box.min.z..box.max.z)
            (z - box.min.z) * width + x - box.min.x else null
        fun point(i: Int) = CivPosition(box.min.x + i % width, city.center.y, box.min.z + i / width)
        val inner = city.buildings.filter { !it.type.isInfrastructure && it.type !in exterior }
        val occupied = city.buildings.filterNot { it.type.isInfrastructure }.map { it.bounds }
        val externalRoutes = routes.filterKeys { city.building(it)?.type in exterior }.values.toList()
        val externalColumns = externalRoutes.flatten().mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }
        val retainedFloors = retainedRoutes.flatten().associate { civsTerrainKey(it.x, it.z) to it.y - 1 }
        fun clear(p: CivPosition) = surface.at(p.x, p.z)?.let { it.loaded &&
            (it.natural || it.waterTopY != null && it.waterBottomY != null && it.topY == it.waterTopY &&
                it.groundY == it.waterTopY && it.waterTopY.toLong() - it.waterBottomY in 1L..CivsWorld.MAXIMUM_SURFACE_WATER_DEPTH.toLong() ||
                retainedFloors[civsTerrainKey(p.x, p.z)] == it.groundY && it.topY == it.groundY) } == true &&
            occupied.none { CivsAutonomousGeometry.horizontalContains(it, p) }
        fun gateClear(p: CivPosition) = (-1..1).all { dx -> (-1..1).all { dz ->
            clear(p.copy(x = p.x + dx, z = p.z + dz))
        } }
        val seeds = BooleanArray(area)
        fun seed(p: CivPosition) { index(p.x, p.z)?.let { seeds[it] = true } }
        inner.forEach { building ->
            for (x in building.bounds.min.x..building.bounds.max.x)
                for (z in building.bounds.min.z..building.bounds.max.z) seed(CivPosition(x, 0, z))
            CivsAutonomousGeometry.approach(building).forEach(::seed)
            routes[building.id].orEmpty().forEach(::seed)
        }
        val source = area * 2
        val sink = source + 1
        val cut = Cut(sink + 1)
        for (i in 0 until area) {
            val p = point(i)
            val road = civsTerrainKey(p.x, p.z) in externalColumns
            val ground = surface.at(p.x, p.z)?.groundY
            val gentle = ground != null && listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1).all { (dx, dz) ->
                surface.at(p.x + dx, p.z + dz)?.let { !it.natural || abs(it.groundY - ground) <= 1 } ?: false
            }
            val eligible = !seeds[i] && clear(p) && if (road) gateClear(p) else gentle
            // A small grid preference removes arbitrary one-cell zigzags among equally short cuts.
            val cost = if (!eligible) Cut.INFINITE else if (road) 100 else
                10 + (if (surface.at(p.x, p.z)?.waterTopY != null) 10 else 0) +
                    if (Math.floorMod(p.x, 4) == 0 || Math.floorMod(p.z, 4) == 0) 0 else 2
            cut.edge(i * 2, i * 2 + 1, cost)
            if (seeds[i]) cut.edge(source, i * 2, Cut.INFINITE)
            if (i % width == 0 || i % width == width - 1 || i / width == 0 || i / width == height - 1)
                cut.edge(i * 2 + 1, sink, Cut.INFINITE)
            for ((dx, dz) in neighbours) index(p.x + dx, p.z + dz)?.let { neighbour ->
                cut.edge(i * 2 + 1, neighbour * 2, Cut.INFINITE)
            }
        }
        val reachable = cut.separate(source, sink)
        val wallCells = (0 until area).filter { reachable[it * 2] && !reachable[it * 2 + 1] }
            .mapTo(linkedSetOf()) { point(it) }
        if (wallCells.isEmpty()) throw CivsRejection("autonomy-layout-obstructed", mapOf("stage" to "empty-dry-perimeter"))
        val crossing = externalRoutes.flatMap { route -> route.filter { p ->
            index(p.x, p.z)?.let { reachable[it * 2] && !reachable[it * 2 + 1] } == true
        } }.distinctBy { civsTerrainKey(it.x, it.z) }.toMutableList()
        val gates = mutableListOf<CivSettlementGate>()
        while (crossing.isNotEmpty()) {
            val first = crossing.removeAt(0)
            val group = mutableListOf(first)
            var grew: Boolean
            do {
                val adjacent = crossing.filter { p -> group.any { abs(it.x - p.x) <= 2 && abs(it.z - p.z) <= 2 } }
                group += adjacent; crossing.removeAll(adjacent.toSet()); grew = adjacent.isNotEmpty()
            } while (grew)
            val passage = CivBox(CivPosition(group.minOf { it.x } - 1, city.center.y, group.minOf { it.z } - 1),
                CivPosition(group.maxOf { it.x } + 1, city.center.y, group.maxOf { it.z } + 1))
            for (x in passage.min.x..passage.max.x) for (z in passage.min.z..passage.max.z)
                if (!clear(CivPosition(x, city.center.y, z)))
                    throw CivsRejection("autonomy-layout-obstructed", mapOf("stage" to "gate-clearance", "x" to "$x", "z" to "$z"))
            val route = externalRoutes.first { first in it }
            val at = route.indexOf(first)
            val before = route.getOrElse((at - 1).coerceAtLeast(0)) { first }
            val after = route.getOrElse(at + 1) { first }
            val worldDirection = if (abs(after.x - before.x) > abs(after.z - before.z)) 1 else 0
            gates += CivSettlementGate(Math.floorMod(worldDirection - frame.rotation / 90, 4),
                first.copy(x = (passage.min.x + passage.max.x) / 2, z = (passage.min.z + passage.max.z) / 2), passage)
            wallCells.removeAll { CivsAutonomousGeometry.horizontalContains(passage, it) }
        }
        if (gates.isEmpty() || gates.size > 8) throw CivsRejection("autonomy-layout-obstructed", mapOf("stage" to "dry-gates"))
        val walls = mutableListOf<CivBox>()
        while (wallCells.isNotEmpty()) {
            val start = wallCells.minWith(compareBy<CivPosition> { it.z }.thenBy { it.x })
            var lastX = start.x
            while (start.copy(x = lastX + 1) in wallCells) lastX++
            var lastZ = start.z
            while ((start.x..lastX).all { start.copy(x = it, z = lastZ + 1) in wallCells }) lastZ++
            val bounds = CivBox(start, start.copy(x = lastX, z = lastZ))
            walls += bounds
            wallCells.removeAll { CivsAutonomousGeometry.horizontalContains(bounds, it) }
        }
        if (walls.size + gates.size > CivsRules.MAX_INFRASTRUCTURE)
            throw CivsRejection("autonomy-layout-obstructed", mapOf("stage" to "dry-perimeter-budget", "walls" to "${walls.size}"))
        return frame.copy(wallObstacles = walls, gates = gates)
    }

    /** Bounded integral node cut; iterative augmenting paths avoid recursion on a long shoreline. */
    private class Cut(size: Int) {
        data class Edge(val to: Int, var capacity: Int, val reverse: Int)
        private val graph = Array(size) { mutableListOf<Edge>() }
        private var work = 0
        fun edge(from: Int, to: Int, capacity: Int) {
            val forward = Edge(to, capacity, graph[to].size)
            graph[to] += Edge(from, 0, graph[from].size)
            graph[from] += forward
        }
        fun separate(source: Int, sink: Int): BooleanArray {
            val level = IntArray(graph.size)
            val cursor = IntArray(graph.size)
            val path = IntArray(graph.size)
            val selected = IntArray(graph.size)
            val flow = IntArray(graph.size)
            fun spend() {
                if (++work > 50_000_000) throw CivsRejection("autonomy-search-budget", mapOf("stage" to "dry-perimeter"))
            }
            var total = 0
            while (true) {
                level.fill(-1); level[source] = 0
                val queue = ArrayDeque<Int>(); queue += source
                while (queue.isNotEmpty()) {
                    val v = queue.removeFirst()
                    for (edge in graph[v]) {
                        spend()
                        if (edge.capacity > 0 && level[edge.to] < 0) { level[edge.to] = level[v] + 1; queue += edge.to }
                    }
                }
                if (level[sink] < 0) return BooleanArray(graph.size) { level[it] >= 0 }
                cursor.fill(0)
                while (true) {
                    var depth = 0; path[0] = source; flow[0] = INFINITE
                    while (depth >= 0 && path[depth] != sink) {
                        val v = path[depth]
                        while (cursor[v] < graph[v].size) {
                            spend()
                            val edge = graph[v][cursor[v]]
                            if (edge.capacity > 0 && level[edge.to] == level[v] + 1) break
                            cursor[v]++
                        }
                        if (cursor[v] == graph[v].size) {
                            level[v] = -1; depth--
                            if (depth >= 0) cursor[path[depth]]++
                        } else {
                            selected[depth] = cursor[v]
                            val edge = graph[v][cursor[v]]
                            flow[depth + 1] = minOf(flow[depth], edge.capacity)
                            path[++depth] = edge.to
                        }
                    }
                    if (depth < 0) break
                    val sent = flow[depth]
                    total += sent
                    if (total >= INFINITE) throw CivsRejection("autonomy-layout-obstructed", mapOf("stage" to "no-dry-perimeter"))
                    for (i in 0 until depth) {
                        val edge = graph[path[i]][selected[i]]
                        edge.capacity -= sent; graph[edge.to][edge.reverse].capacity += sent
                    }
                }
            }
        }
        companion object { const val INFINITE = 100_000_000 }
    }
}
