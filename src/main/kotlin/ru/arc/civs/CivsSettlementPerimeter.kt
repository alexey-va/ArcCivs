package ru.arc.civs

import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.abs

/** A bounded separator around adopted parcels. Produces ordinary wall/gate rectangles, never world writes. */
internal object CivsSettlementPerimeter {
    private val exterior = CivsSettlementLayout.exteriorTypes
    private val neighbours = listOf(-1 to -1, 0 to -1, 1 to -1, -1 to 0, 1 to 0, -1 to 1, 0 to 1, 1 to 1)

    fun adapt(city: Settlement, frame: CivSettlementFrame, surface: CivTerrainSurface,
        routes: Map<UUID, List<CivPosition>>, retainedRoutes: Collection<List<CivPosition>>,
        fortifications: Map<UUID, CivFortificationProof> = emptyMap()): CivSettlementFrame {
        val box = frame.core
        val width = box.max.x - box.min.x + 1
        val height = box.max.z - box.min.z + 1
        val maximumSize = CivsSettlementLayout.MAXIMUM_HALF_WIDTH * 2 + 1
        require(width in 1..maximumSize && height in 1..maximumSize)
        val area = width * height
        fun index(x: Int, z: Int): Int? = if (x in box.min.x..box.max.x && z in box.min.z..box.max.z)
            (z - box.min.z) * width + x - box.min.x else null
        fun point(i: Int) = CivPosition(box.min.x + i % width, city.center.y, box.min.z + i / width)
        val inner = city.buildings.filter { !it.type.isInfrastructure && it.type !in exterior && it.id !in fortifications }
        val occupied = city.buildings.filterNot { it.type.isInfrastructure }.map { it.bounds }
        val fortificationGround = fortifications.values.flatMap { it.groundBarrier }
            .associateBy { civsTerrainKey(it.x, it.z) }
        val externalRoutes = routes.filterKeys { city.building(it)?.type in exterior }.values.toList()
        val externalPoints = externalRoutes.flatten().distinctBy { civsTerrainKey(it.x, it.z) }
        val externalColumns = externalPoints.mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }
        val externalFootprints = mutableMapOf<Long, MutableList<CivPosition>>()
        if (frame.fortified) for (p in externalPoints) for (dx in -2..2) for (dz in -2..2)
            externalFootprints.getOrPut(civsTerrainKey(p.x + dx, p.z + dz)) { mutableListOf() } += p
        val retainedFloors = retainedRoutes.flatten().associate { civsTerrainKey(it.x, it.z) to it.y - 1 }
        fun clear(p: CivPosition) = surface.at(p.x, p.z)?.let { it.loaded &&
            (it.natural || it.waterTopY != null && it.waterBottomY != null && it.topY == it.waterTopY &&
                it.groundY == it.waterTopY && it.waterTopY.toLong() - it.waterBottomY in 1L..CivsWorld.MAXIMUM_SURFACE_WATER_DEPTH.toLong() ||
                retainedFloors[civsTerrainKey(p.x, p.z)] == it.groundY && it.topY == it.groundY) } == true &&
            occupied.none { CivsAutonomousGeometry.horizontalContains(it, p) }
        val gateRadius = if (frame.fortified) 2 else 1
        fun gateClear(p: CivPosition) = (-gateRadius..gateRadius).all { dx -> (-gateRadius..gateRadius).all { dz ->
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
        fortifications.forEach { (id, proof) ->
            proof.interiorWitnesses.forEach(::seed)
            city.building(id)?.let(CivsAutonomousGeometry::approach)?.forEach(::seed)
            routes[id].orEmpty().forEach(::seed)
        }
        fun ground(x: Int, z: Int): Int? = fortificationGround[civsTerrainKey(x, z)]?.y ?: surface.at(x, z)?.groundY
        fun gentle(p: CivPosition): Boolean {
            val y = ground(p.x, p.z) ?: return false
            return listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1).all { (dx, dz) ->
                val native = surface.at(p.x + dx, p.z + dz) ?: return@all false
                !native.natural || ground(p.x + dx, p.z + dz)?.let { abs(it - y) <= 1 } == true
            }
        }
        fun wideClear(p: CivPosition): Boolean = (-2..2).all { dx -> (-2..2).all { dz ->
            val q = p.copy(x = p.x + dx, z = p.z + dz)
            val key = civsTerrainKey(q.x, q.z)
            if (key in fortificationGround) true else {
                val at = index(q.x, q.z)
                at != null && !seeds[at] && clear(q) && gentle(q) &&
                    listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1).all { (sx, sz) ->
                        fortificationGround[civsTerrainKey(q.x + sx, q.z + sz)]?.let { it.y == ground(q.x, q.z) } != false
                    }
            }
        } }
        val source = area * 2
        val sink = source + 1
        val cut = Cut(sink + 1)
        var firstSteepSeed: CivPosition? = null
        for (i in 0 until area) {
            val p = point(i)
            val roadPoints = externalFootprints[civsTerrainKey(p.x, p.z)].orEmpty()
            val road = if (frame.fortified) roadPoints.isNotEmpty() else civsTerrainKey(p.x, p.z) in externalColumns
            val ground = ground(p.x, p.z)
            val gentle = gentle(p)
            if (seeds[i] && !gentle && firstSteepSeed == null) firstSteepSeed = p.copy(y = ground ?: p.y)
            val fortification = civsTerrainKey(p.x, p.z) in fortificationGround
            val gateRoom = if (!road) true else if (!frame.fortified) gateClear(p) else roadPoints.all { crossing ->
                gateClear(crossing) && (-2..2).all { dx -> (-2..2).all { dz ->
                    index(crossing.x + dx, crossing.z + dz)?.let { !seeds[it] } != false
                } }
            }
            val eligible = !seeds[i] && (fortification || clear(p) && (if (road) gateRoom else gentle) &&
                (!frame.fortified || wideClear(p)))
            // A small grid preference removes arbitrary one-cell zigzags among equally short cuts.
            val cost = if (!eligible) Cut.INFINITE else if (fortification) 0 else if (road) 100 else
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
        val reachable = try { cut.separate(source, sink) } catch (failure: CivsRejection) {
            if (failure.values["stage"] != "no-dry-perimeter") throw failure
            val boundarySeeds = (0 until area).filter { seeds[it] &&
                (it % width == 0 || it % width == width - 1 || it / width == 0 || it / width == height - 1) }
            val routeBoundary = inner.asSequence().flatMap { building -> routes[building.id].orEmpty().asSequence()
                .map { building to it } }.firstOrNull { (_, p) ->
                    p.x !in box.min.x + 1 until box.max.x || p.z !in box.min.z + 1 until box.max.z }
            throw CivsRejection(failure.reason, failure.values + mapOf(
                "inner_boundary_seeds" to boundarySeeds.size.toString(),
                "first_boundary_seed" to (boundarySeeds.firstOrNull()?.let(::point)?.toString() ?: "none"),
                "first_steep_seed" to (firstSteepSeed?.toString() ?: "none"),
                "inner_route_boundary" to (routeBoundary?.let { (b, p) -> "${b.type}:${b.id}:$p" } ?: "none")))
                .also { it.initCause(failure) }
        }
        val cutCells = (0 until area).filter { reachable[it * 2] && !reachable[it * 2 + 1] }
            .mapTo(linkedSetOf()) { point(it) }
        val wallCells = if (!frame.fortified) cutCells else buildSet {
            cutCells.filter { civsTerrainKey(it.x, it.z) !in fortificationGround }.forEach { p ->
                for (dx in -2..2) for (dz in -2..2) {
                    val q = p.copy(x = p.x + dx, z = p.z + dz)
                    if (civsTerrainKey(q.x, q.z) !in fortificationGround) add(q)
                }
            }
        }.toMutableSet()
        if (wallCells.isEmpty()) throw CivsRejection("autonomy-layout-obstructed", mapOf("stage" to "empty-dry-perimeter"))
        val crossing = externalRoutes.flatMap { route -> route.filter { p ->
            if (frame.fortified) p.copy(y = city.center.y) in wallCells else
                index(p.x, p.z)?.let { reachable[it * 2] && !reachable[it * 2 + 1] } == true
        } }.distinctBy { civsTerrainKey(it.x, it.z) }.toMutableList()
        val gates = mutableListOf<CivSettlementGate>()
        while (crossing.isNotEmpty()) {
            val first = crossing.removeAt(0)
            val group = mutableListOf(first)
            var grew: Boolean
            do {
                val adjacent = crossing.filter { p -> group.any {
                    abs(it.x - p.x) <= gateRadius * 2 && abs(it.z - p.z) <= gateRadius * 2 } }
                group += adjacent; crossing.removeAll(adjacent.toSet()); grew = adjacent.isNotEmpty()
            } while (grew)
            val passage = CivBox(CivPosition(group.minOf { it.x } - gateRadius, city.center.y, group.minOf { it.z } - gateRadius),
                CivPosition(group.maxOf { it.x } + gateRadius, city.center.y, group.maxOf { it.z } + gateRadius))
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
                    if (level[sink] >= 0 && level[v] >= level[sink]) break
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
                        if (level[v] >= level[sink]) cursor[v] = graph[v].size
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
