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
        fortifications: Map<UUID, CivFortificationProof> = emptyMap(),
        envelopes: Map<UUID, CivBox> = emptyMap(), insidePaid: List<CivBox> = emptyList()): CivSettlementFrame {
        val box = frame.core
        val maximumSize = CivsSettlementLayout.MAXIMUM_HALF_WIDTH * 2 + 1
        require(box.max.x - box.min.x + 1 in 1..maximumSize && box.max.z - box.min.z + 1 in 1..maximumSize)
        val inner = city.buildings.filter { !it.type.isInfrastructure && it.type !in exterior && it.id !in fortifications }
        val interior = InteriorSeeds(insidePaid + inner.map { envelopes[it.id] ?: it.bounds }, buildList {
            inner.forEach { building ->
                addAll(CivsAutonomousGeometry.approach(building))
                addAll(routes[building.id].orEmpty())
            }
            fortifications.forEach { (id, proof) ->
                addAll(proof.interiorWitnesses)
                city.building(id)?.let { addAll(CivsAutonomousGeometry.approach(it)) }
                addAll(routes[id].orEmpty())
            }
        })
        val occupied = city.buildings.filterNot { it.type.isInfrastructure }.map { envelopes[it.id] ?: it.bounds } + insidePaid
        val budget = CutBudget()
        return try { separate(city, frame, surface, routes, retainedRoutes, fortifications, box, maximumSize, budget, occupied, interior, inner) }
        catch (original: CivsRejection) {
            if (frame.fortified || original.reason != "autonomy-layout-obstructed" ||
                original.values["stage"] != "no-dry-perimeter" || surface.columns.isEmpty()) throw original
            val paidBoundaryRetry = original.values["inner_boundary_seeds"] != "0"
            if (paidBoundaryRetry && insidePaid.none { paid ->
                    CivsAutonomousGeometry.overlaps(paid, box) && (paid.min.x <= box.min.x || paid.max.x >= box.max.x ||
                        paid.min.z <= box.min.z || paid.max.z >= box.max.z)
                }) throw original
            val radius = city.radius.coerceIn(0, 127)
            val expanded = CivBox(box.min.copy(
                x = maxOf(box.min.x - 8, city.center.x - radius, surface.columns.keys.minOf { (it shr 32).toInt() }),
                z = maxOf(box.min.z - 8, city.center.z - radius, surface.columns.keys.minOf { it.toInt() })),
                box.max.copy(
                    x = minOf(box.max.x + 8, city.center.x + radius, surface.columns.keys.maxOf { (it shr 32).toInt() }),
                    z = minOf(box.max.z + 8, city.center.z + radius, surface.columns.keys.maxOf { it.toInt() })))
            // Retained boxes may already extend beyond the nominal tile. Require every
            // input seed, including that unseen part, strictly inside the loaded retry tile.
            if (expanded.min.x > box.min.x || expanded.min.z > box.min.z || expanded.max.x < box.max.x ||
                expanded.max.z < box.max.z || expanded == box ||
                paidBoundaryRetry && !interior.allColumns { x, z ->
                    x > expanded.min.x && x < expanded.max.x && z > expanded.min.z && z < expanded.max.z &&
                        surface.at(x, z)?.loaded == true
                }) throw original
            val adapted = separate(city, frame, surface, routes, retainedRoutes, fortifications,
                expanded, 165, budget, occupied, interior, inner)
            if (paidBoundaryRetry) {
                val enclosed = CivsSettlementGrowth.interior(adapted)
                // The gate's closed footprint may cover route or apron seed cells. Native
                // transit and grades are validated separately; the reserved parcels stay inside.
                if (!interior.boxes.all { it.allColumns { x, z -> civsTerrainKey(x, z) in enclosed } } ||
                    !singleInterior(enclosed))
                    throw CivsRejection("autonomy-layout-obstructed", mapOf("stage" to "retained-boundary-interior"))
                if (city.buildings.any { building ->
                        building.type == BuildingType.QUARRY &&
                            !(envelopes[building.id] ?: building.bounds).allColumns { x, z -> civsTerrainKey(x, z) !in enclosed }
                    }) throw CivsRejection("autonomy-layout-obstructed", mapOf("stage" to "retained-boundary-quarry"))
            }
            adapted
        }
    }

    private class InteriorSeeds(val boxes: List<CivBox>, val points: List<CivPosition>) {
        fun allColumns(test: (Int, Int) -> Boolean): Boolean =
            boxes.all { it.allColumns(test) } && points.all { test(it.x, it.z) }
    }

    private fun CivBox.allColumns(test: (Int, Int) -> Boolean): Boolean =
        (min.x..max.x).all { x -> (min.z..max.z).all { z -> test(x, z) } }

    private fun singleInterior(interior: Set<Long>): Boolean {
        val first = interior.firstOrNull() ?: return false
        val remaining = interior.toHashSet()
        val queue = ArrayDeque<Long>()
        fun visit(x: Int, z: Int) {
            val key = civsTerrainKey(x, z)
            if (remaining.remove(key)) queue.addLast(key)
        }
        visit((first shr 32).toInt(), first.toInt())
        while (queue.isNotEmpty()) {
            val key = queue.removeFirst()
            val x = (key shr 32).toInt()
            val z = key.toInt()
            visit(x - 1, z); visit(x + 1, z); visit(x, z - 1); visit(x, z + 1)
        }
        return remaining.isEmpty()
    }

    private fun separate(city: Settlement, frame: CivSettlementFrame, surface: CivTerrainSurface,
        routes: Map<UUID, List<CivPosition>>, retainedRoutes: Collection<List<CivPosition>>,
        fortifications: Map<UUID, CivFortificationProof>, box: CivBox, maximumSize: Int,
        budget: CutBudget, occupied: List<CivBox>, interior: InteriorSeeds, inner: List<CivBuilding>): CivSettlementFrame {
        val width = box.max.x - box.min.x + 1
        val height = box.max.z - box.min.z + 1
        require(width in 1..maximumSize && height in 1..maximumSize)
        val area = width * height
        fun index(x: Int, z: Int): Int? = if (x in box.min.x..box.max.x && z in box.min.z..box.max.z)
            (z - box.min.z) * width + x - box.min.x else null
        fun point(i: Int) = CivPosition(box.min.x + i % width, city.center.y, box.min.z + i / width)
        val fortificationGround = fortifications.values.flatMap { it.groundBarrier }
            .associateBy { civsTerrainKey(it.x, it.z) }
        val externalRoutes = routes.filterKeys { city.building(it)?.type in exterior }.values.toList()
        val externalPoints = externalRoutes.flatten().distinctBy { civsTerrainKey(it.x, it.z) }
        val externalColumns = externalPoints.mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }
        val externalFootprints = mutableMapOf<Long, MutableList<CivPosition>>()
        if (frame.fortified) for (p in externalPoints) for (dx in -2..2) for (dz in -2..2)
            externalFootprints.getOrPut(civsTerrainKey(p.x + dx, p.z + dz)) { mutableListOf() } += p
        val retainedFloors = buildMap<Long, Int> {
            for (feet in retainedRoutes.flatten()) {
                val floor = feet.y - 1
                surface.at(feet.x, feet.z)?.takeIf { it.loaded && it.groundY == floor && it.topY == floor }
                    ?.let { putIfAbsent(civsTerrainKey(feet.x, feet.z), floor) }
                // A retained centreline also owns its captured dirt-path shoulders, as infrastructure does.
                for (dx in -1..1) for (dz in -1..1) {
                    val x = feet.x + dx; val z = feet.z + dz
                    val column = surface.at(x, z) ?: continue
                    if (column.loaded && column.dirtPath && column.topY == column.groundY && abs(column.groundY - floor) <= 1)
                        putIfAbsent(civsTerrainKey(x, z), column.groundY)
                }
            }
        }
        fun clear(p: CivPosition) = surface.at(p.x, p.z)?.let { it.loaded &&
            (it.natural || it.waterTopY != null && it.waterBottomY != null && it.topY == it.waterTopY &&
                it.groundY == it.waterTopY && it.waterTopY.toLong() - it.waterBottomY in 1L..CivsWorld.MAXIMUM_SURFACE_WATER_DEPTH.toLong() ||
                retainedFloors[civsTerrainKey(p.x, p.z)] == it.groundY && it.topY == it.groundY) } == true &&
            occupied.none { CivsAutonomousGeometry.horizontalContains(it, p) }
        // A new ordinary gate reserves two solid flanks around its actual three-wide core.
        // Paid/adopted footprints never pass through this new separator branch.
        val gateRadius = 2
        fun gateClear(p: CivPosition) = (-gateRadius..gateRadius).all { dx -> (-gateRadius..gateRadius).all { dz ->
            clear(p.copy(x = p.x + dx, z = p.z + dz))
        } }
        val seeds = BooleanArray(area)
        fun seed(p: CivPosition) { index(p.x, p.z)?.let { seeds[it] = true } }
        fun reserve(box: CivBox) {
            for (x in box.min.x..box.max.x) for (z in box.min.z..box.max.z) seed(CivPosition(x, 0, z))
        }
        interior.boxes.forEach(::reserve)
        interior.points.forEach(::seed)
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
        val cut = Cut(sink + 1, budget)
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
        val retainedPoints = retainedRoutes.flatten()
        val retainedColumns = retainedPoints.mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }
        val retainedNativeColumns = retainedFloors.keys.mapTo(hashSetOf()) { (it shr 32).toInt() to it.toInt() }
        if (!frame.fortified) crossing.sortBy { if (civsTerrainKey(it.x, it.z) in retainedColumns) 0 else 1 }
        fun collar(center: CivPosition, alongX: Boolean): Pair<Set<CivPosition>, Set<CivPosition>>? {
            fun at(across: Int, normal: Int) = center.copy(
                x = center.x + if (alongX) across else normal,
                z = center.z + if (alongX) normal else across)
            fun across(p: CivPosition) = if (alongX) p.x - center.x else p.z - center.z
            fun normal(p: CivPosition) = if (alongX) p.z - center.z else p.x - center.x
            val patch = (-3..3).flatMap { a -> (-3..3).map { n -> at(a, n) } }.toSet()
            if (patch.any { index(it.x, it.z) == null }) return null
            val old = cutCells.intersect(patch)
            val ends = old.filter { p -> neighbours.any { (dx, dz) ->
                val q = p.copy(x = p.x + dx, z = p.z + dz)
                q !in patch && q in cutCells
            } }.groupBy(::across)
            // Every unchanged attachment must meet one of the two solid-wing ends. A branch
            // leaving through an open approach needs a larger redesign and is refused here.
            if (ends.keys != setOf(-3, 3)) return null
            val caps = buildSet {
                for (a in listOf(-3, 3)) {
                    val positions = ends.getValue(a).map(::normal)
                    for (n in minOf(0, positions.min())..maxOf(0, positions.max())) add(at(a, n))
                }
            }
            if (caps.any { !clear(it) || !gentle(it) || seeds[index(it.x, it.z)!!] ||
                    civsTerrainKey(it.x, it.z) in retainedFloors }) return null
            val passage = CivBox(at(-2, -2), at(2, 2))
            val open = CivsInfrastructureRebuild.openColumns(passage, alongX)
            val solid = patch.filterTo(hashSetOf()) { CivsAutonomousGeometry.horizontalContains(passage, it) &&
                (it.x to it.z) !in open }
            if (solid.any { seeds[index(it.x, it.z)!!] || civsTerrainKey(it.x, it.z) in retainedFloors }) return null
            // Seal only the intended core for this local topology proof. Flood at most 49 cells;
            // outside labels come from the existing cut, not another world or route search.
            val sealed = solid + caps + (-1..1).map { at(it, 0) }
            val unseen = (patch - sealed).toMutableSet()
            while (unseen.isNotEmpty()) {
                val start = unseen.first(); unseen.remove(start)
                val queue = ArrayDeque<CivPosition>(); queue += start
                var inside = false; var outside = false; var seed = false
                while (queue.isNotEmpty()) {
                    val p = queue.removeFirst()
                    seed = seed || seeds[index(p.x, p.z)!!]
                    for ((dx, dz) in neighbours) {
                        val q = p.copy(x = p.x + dx, z = p.z + dz)
                        if (q in patch) {
                            if (unseen.remove(q)) queue += q
                        } else if (q !in cutCells) {
                            val i = index(q.x, q.z) ?: return null
                            if (reachable[i * 2]) inside = true else outside = true
                        }
                    }
                }
                if (inside && outside || seed && !inside) return null
            }
            return old to (caps + (-2..2).map { at(it, 0) })
        }
        val gates = mutableListOf<CivSettlementGate>()
        while (crossing.isNotEmpty()) {
            val first = crossing.removeAt(0)
            val group = mutableListOf(first)
            if (frame.fortified) {
                var grew: Boolean
                do {
                    val adjacent = crossing.filter { p -> group.any {
                        abs(it.x - p.x) <= gateRadius * 2 && abs(it.z - p.z) <= gateRadius * 2 } }
                    group += adjacent; crossing.removeAll(adjacent.toSet()); grew = adjacent.isNotEmpty()
                } while (grew)
            } else {
                // Keep one local entry, not a wide opening spanning every nearby road. Anchoring
                // distance to the chosen crossing prevents chains from merging distant exits.
                val nearby = crossing.filter { abs(it.x - first.x) + abs(it.z - first.z) <= 16 }
                group += nearby
                crossing.removeAll(nearby.toSet())
            }
            var selectedCollar: Pair<Set<CivPosition>, Set<CivPosition>>? = null
            val ordinary = if (frame.fortified) null else run {
                val reasons = sortedMapOf<String, Int>()
                val paidGroup = group.any { civsTerrainKey(it.x, it.z) in retainedColumns }
                val collars = mutableListOf<Pair<CivSettlementGate, Boolean>>()
                fun reject(reason: String) { reasons.merge(reason, 1, Int::plus) }
                fun candidate(center: CivPosition): CivSettlementGate? {
                    if (!gateClear(center)) { reject("clearance"); return null }
                    if (gates.any { abs(it.center.x - center.x) + abs(it.center.z - center.z) <= 16 }) {
                        reject("spacing"); return null
                    }
                    val passage = CivBox(center.copy(x = center.x - 2, z = center.z - 2),
                        center.copy(x = center.x + 2, z = center.z + 2))
                    for (direction in 0..1) {
                        val alongX = direction == 0
                        fun at(across: Int, normal: Int) = center.copy(
                            x = center.x + if (alongX) across else normal,
                            z = center.z + if (alongX) normal else across)
                        val a = at(0, -3); val b = at(0, 3)
                        val ai = index(a.x, a.z) ?: continue
                        val bi = index(b.x, b.z) ?: continue
                        if (!clear(a) || !clear(b) || reachable[ai * 2] != reachable[ai * 2 + 1] ||
                            reachable[bi * 2] != reachable[bi * 2 + 1] || reachable[ai * 2] == reachable[bi * 2]) {
                            reject("approach"); continue
                        }
                        val open = CivsInfrastructureRebuild.openColumns(passage, alongX)
                        val nearby = group + crossing.filter { abs(it.x - center.x) + abs(it.z - center.z) <= 16 }
                        if (nearby.any { civsTerrainKey(it.x, it.z) in retainedColumns &&
                                !CivsAutonomousGeometry.horizontalContains(passage, it) } ||
                            retainedPoints.any { CivsAutonomousGeometry.horizontalContains(passage, it) && (it.x to it.z) !in open } ||
                            retainedNativeColumns.any { (x, z) -> x in passage.min.x..passage.max.x &&
                                z in passage.min.z..passage.max.z && (x to z) !in open }) {
                            reject("retained-route"); continue
                        }
                        val gate = CivSettlementGate(Math.floorMod(direction - frame.rotation / 90, 4), center, passage)
                        // Prefer an unchanged straight separator. Only a paid crossing can require
                        // the bounded endcap correction after every strict candidate was tried.
                        if ((-3..3).any { at(it, 0) !in cutCells } ||
                            (-2..2).any { a -> (-2..2).any { n -> n != 0 && at(a, n) in cutCells } }) {
                            if (paidGroup) collars += gate to alongX
                            reject("junction"); continue
                        }
                        return gate
                    }
                    return null
                }
                val candidates = cutCells.sortedWith(compareBy<CivPosition> {
                    abs(it.x - first.x) + abs(it.z - first.z) }.thenBy { it.x }.thenBy { it.z })
                // A cramped crossing may need a longer ordinary approach to the nearest sound
                // junction. Search this already bounded cut once; never widen the aperture.
                candidates.firstNotNullOfOrNull(::candidate) ?: collars.firstNotNullOfOrNull { (gate, alongX) ->
                    collar(gate.center, alongX)?.let { selectedCollar = it; gate }
                } ?: run {
                    throw CivsRejection("autonomy-layout-obstructed", mapOf("stage" to "gate-junction",
                        "first" to "${first.x},${first.z}", "group" to group.joinToString(";") { "${it.x},${it.z}" },
                        "retained_crossing" to paidGroup.toString(),
                        "selected" to gates.joinToString(";") { "${it.center.x},${it.center.z}" },
                        "near_cut" to candidates.asSequence().filter { abs(it.x - first.x) + abs(it.z - first.z) <= 8 }
                            .take(64).joinToString(";") { "${it.x},${it.z}" }) +
                        reasons.mapKeys { "candidate_${it.key}" }.mapValues { it.value.toString() })
                }
            }
            selectedCollar?.let { (old, replacement) ->
                cutCells.removeAll(old); cutCells.addAll(replacement)
                wallCells.removeAll(old); wallCells.addAll(replacement)
            }
            val passage = ordinary?.passage ?: CivBox(
                CivPosition(group.minOf { it.x } - gateRadius, city.center.y, group.minOf { it.z } - gateRadius),
                CivPosition(group.maxOf { it.x } + gateRadius, city.center.y, group.maxOf { it.z } + gateRadius))
            for (x in passage.min.x..passage.max.x) for (z in passage.min.z..passage.max.z)
                if (!clear(CivPosition(x, city.center.y, z)))
                    throw CivsRejection("autonomy-layout-obstructed", mapOf("stage" to "gate-clearance", "x" to "$x", "z" to "$z"))
            val route = externalRoutes.first { first in it }
            val at = route.indexOf(first)
            val before = route.getOrElse((at - 1).coerceAtLeast(0)) { first }
            val after = route.getOrElse(at + 1) { first }
            val worldDirection = if (abs(after.x - before.x) > abs(after.z - before.z)) 1 else 0
            gates += ordinary ?: CivSettlementGate(Math.floorMod(worldDirection - frame.rotation / 90, 4),
                first.copy(x = (passage.min.x + passage.max.x) / 2, z = (passage.min.z + passage.max.z) / 2), passage)
            if (ordinary != null) crossing.removeAll { abs(it.x - ordinary.center.x) + abs(it.z - ordinary.center.z) <= 16 }
            // The other local crossings remain cut cells and become ordinary walls. Only their
            // new unpaid routes may be reconnected by the caller, through this fixed aperture.
            wallCells.removeAll { CivsAutonomousGeometry.horizontalContains(passage, it) }
        }
        if (gates.isEmpty() || gates.size > 8) throw CivsRejection("autonomy-layout-obstructed", mapOf("stage" to "dry-gates"))
        val roadConflict = if (frame.fortified) null else wallCells.firstOrNull { civsTerrainKey(it.x, it.z) in retainedFloors }
        if (roadConflict != null)
            throw CivsRejection("autonomy-layout-obstructed", mapOf("stage" to "retained-road-wall",
                "first" to "${roadConflict.x},${roadConflict.z}",
                "retained_floor" to retainedFloors.getValue(civsTerrainKey(roadConflict.x, roadConflict.z)).toString()))
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

    /** Both canvases share the original finite work allowance. */
    private class CutBudget {
        private var work = 0
        fun spend() {
            if (++work > 50_000_000) throw CivsRejection("autonomy-search-budget", mapOf("stage" to "dry-perimeter"))
        }
    }

    /** Bounded integral node cut; iterative augmenting paths avoid recursion on a long shoreline. */
    private class Cut(size: Int, private val budget: CutBudget) {
        data class Edge(val to: Int, var capacity: Int, val reverse: Int)
        private val graph = Array(size) { mutableListOf<Edge>() }
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
            fun spend() = budget.spend()
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
