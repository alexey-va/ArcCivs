package ru.arc.civs

import java.util.UUID
import kotlin.math.abs

/** One bounded outer outline and at most three existing exit positions, after ordinary growth fails. */
internal object CivsRetainedGrowthPerimeter {
    fun geometric(failure: CivsRejection): Boolean =
        failure.reason in setOf("autonomy-no-site", "autonomy-no-path", "autonomy-layout-obstructed") &&
            failure.values.none { (key, value) ->
                key.contains("budget") && (value.toLongOrNull() ?: 0) > 0 ||
                    key == "stage" && value.endsWith("-budget") || key.contains("invalid-plan")
            }

    fun candidates(city: Settlement, nominal: CivSettlementFrame, surface: CivTerrainSurface,
        retained: CivAutonomousAccess, envelopes: Map<UUID, CivBox>): List<CivSettlementFrame> {
        val adopted = CivsSettlementPlans.current(city) ?: return emptyList()
        val old = CivsSettlementLayout.frame(city)
        if (nominal.halfWidth <= old.halfWidth || nominal.fortified || old.fortified ||
            adopted.plots.any { it.fortification != null } || retained.infrastructure.isEmpty() ||
            city.construction != null || city.constructionQueue.orEmpty().isNotEmpty() ||
            city.buildings.any { it.phase != BuildingPhase.ACTIVE } ||
            city.buildings.filter { it.type.isInfrastructure }.map { it.id }.toSet() !=
            retained.infrastructure.map { it.id }.toSet()) return emptyList()
        val nominalEdges = nominal.wallObstacles + nominal.gates.map { it.passage }
        if (envelopes.values.none { box -> nominalEdges.any { overlaps(it, box) } }) return emptyList()

        var west = nominal.core.min.x; var east = nominal.core.max.x
        var north = nominal.core.min.z; var south = nominal.core.max.z
        // Only an intersected side moves. Each iteration moves at least one integer column;
        // the eight-column canvas bounds the whole loop, including secondary collisions.
        while (true) {
            val previous = listOf(west, east, north, south)
            val bands = ring(west, north, east, south, city.center.y, null, nominal.rotation)
            for ((side, band) in bands.withIndex()) {
                val hits = envelopes.values.filter { overlaps(band, it) }
                if (hits.isEmpty()) continue
                when (side) {
                    0 -> north = minOf(north, hits.minOf { it.min.z } - 4)
                    1 -> east = maxOf(east, hits.maxOf { it.max.x } + 4)
                    2 -> south = maxOf(south, hits.maxOf { it.max.z } + 4)
                    3 -> west = minOf(west, hits.minOf { it.min.x } - 4)
                }
            }
            if (west - 1 < nominal.core.min.x - 8 || east + 1 > nominal.core.max.x + 8 ||
                north - 1 < nominal.core.min.z - 8 || south + 1 > nominal.core.max.z + 8) return emptyList()
            if (previous == listOf(west, east, north, south)) break
        }
        // A blocked exit is closed by the complete new wall. Never drag its approach behind
        // the obstructing building; try an unchanged side's original exit instead.
        return nominal.gates.filter { gate -> when (Math.floorMod(gate.side + nominal.rotation / 90, 4)) {
            0 -> north == nominal.core.min.z
            1 -> east == nominal.core.max.x
            2 -> south == nominal.core.max.z
            else -> west == nominal.core.min.x
        } }.take(3).mapNotNull { gate ->
            val candidate = nominal.copy(gates = listOf(gate), wallObstacles =
                ring(west, north, east, south, city.center.y, gate, nominal.rotation))
            val crossed = CivsPerimeterCrossings.adapt(candidate, retained.routes.values, retained.infrastructure)
            try {
                validate(city, crossed, surface, retained, envelopes.values)
                crossed
            } catch (failure: CivsRejection) {
                if (!geometric(failure)) throw failure
                null
            }
        }
    }

    /** All retained owners participate, including paid WALL approaches absent from the plot map. */
    fun validate(city: Settlement, frame: CivSettlementFrame, surface: CivTerrainSurface,
        retained: CivAutonomousAccess, ordinary: Collection<CivBox>) {
        fun admit(condition: Boolean, stage: String) {
            if (!condition) throw CivsRejection("autonomy-layout-obstructed", mapOf("stage" to "retained-growth-$stage"))
        }
        val edges = frame.wallObstacles + frame.gates.map { it.passage }
        admit(frame.gates.size in 1..8 && frame.wallObstacles.isNotEmpty(), "exits")
        admit(edges.all { it.min.x >= frame.core.min.x - 8 && it.max.x <= frame.core.max.x + 8 &&
            it.min.z >= frame.core.min.z - 8 && it.max.z <= frame.core.max.z + 8 }, "canvas")
        admit(edges.none { edge -> ordinary.any { overlaps(edge, it) } ||
            retained.infrastructure.any { overlaps(edge, it.bounds) } }, "paid-overlap")
        admit(edges.all { box -> columns(box).all { (x, z) ->
            city.contains(x, z) && surface.at(x, z)?.loaded == true
        } }, "capture")
        val interior = CivsSettlementGrowth.interior(frame)
        val oldInterior = CivsSettlementGrowth.interior(CivsSettlementLayout.frame(city))
        admit(oldInterior.isNotEmpty() && interior.containsAll(oldInterior), "old-interior")
        admit(retained.infrastructure.filter { it.type in setOf(BuildingType.WALL, BuildingType.GATE) }
            .all { piece -> columns(piece.bounds).all { (x, z) -> civsTerrainKey(x, z) in interior } }, "paid-quarter")

        val paid = retained.routes.values.flatten()
        admit(paid.none { p -> frame.wallObstacles.any { contains(it, p) } }, "paid-road")
        val paidGrades = paid.groupBy { civsTerrainKey(it.x, it.z) }
        admit(paidGrades.values.all { points -> points.map { it.y }.distinct().size == 1 }, "paid-grade")
        val paidFloors = buildMap<Long, Int> {
            for (p in paid) for (dx in -1..1) for (dz in -1..1) {
                val x = p.x + dx; val z = p.z + dz
                val native = surface.at(x, z) ?: continue
                if (native.loaded && native.topY == native.groundY &&
                    (dx == 0 && dz == 0 && native.groundY == p.y - 1 ||
                        native.dirtPath && abs(native.groundY - (p.y - 1)) <= 1))
                    putIfAbsent(civsTerrainKey(x, z), native.groundY)
            }
        }
        val west = frame.wallObstacles.minOf { it.min.x } + 1
        val east = frame.wallObstacles.maxOf { it.max.x } - 1
        val north = frame.wallObstacles.minOf { it.min.z } + 1
        val south = frame.wallObstacles.maxOf { it.max.z } - 1
        for ((index, gate) in frame.gates.withIndex()) {
            val acrossX = Math.floorMod(gate.side + frame.rotation / 90, 2) == 0
            admit(gate.passage.max.x - gate.passage.min.x == 4 && gate.passage.max.z - gate.passage.min.z == 4 &&
                (if (acrossX) gate.center.z in listOf(north, south) && gate.center.x in west + 3..east - 3
                else gate.center.x in listOf(west, east) && gate.center.z in north + 3..south - 3), "crossing")
            val others = frame.gates.drop(index + 1).map { it.center } + retained.infrastructure
                .filter { it.type == BuildingType.GATE }.map { piece -> CivPosition(
                    (piece.bounds.min.x + piece.bounds.max.x) / 2, gate.center.y,
                    (piece.bounds.min.z + piece.bounds.max.z) / 2) }
            admit(others.none { abs(it.x - gate.center.x) + abs(it.z - gate.center.z) <= 16 }, "gate-spacing")
            val open = CivsInfrastructureRebuild.openColumns(gate.passage, acrossX)
            admit(paid.filter { contains(gate.passage, it) }.all { (it.x to it.z) in open } &&
                columns(gate.passage).none { (x, z) -> civsTerrainKey(x, z) in paidFloors && (x to z) !in open }, "gate-paid-road")
            val apron = if (acrossX) CivBox(gate.center.copy(x = gate.center.x - 1, z = gate.passage.min.z - 4),
                gate.center.copy(x = gate.center.x + 1, z = gate.passage.max.z + 4))
                else CivBox(gate.center.copy(x = gate.passage.min.x - 4, z = gate.center.z - 1),
                    gate.center.copy(x = gate.passage.max.x + 4, z = gate.center.z + 1))
            admit(ordinary.none { overlaps(apron, it) } && retained.infrastructure.none { overlaps(apron, it.bounds) } &&
                columns(apron).all { (x, z) -> city.contains(x, z) && surface.at(x, z)?.let { native ->
                    native.loaded && (native.natural || native.topY == native.groundY &&
                        paidFloors[civsTerrainKey(x, z)] == native.groundY)
                } == true }, "gate-approach")
            for (offset in listOf(-3, 3)) {
                val wing = if (acrossX) gate.center.copy(x = gate.center.x + offset) else gate.center.copy(z = gate.center.z + offset)
                admit(frame.wallObstacles.any { contains(it, wing) }, "gate-joint")
            }
        }
    }

    fun validateResult(frame: CivSettlementFrame, retained: CivAutonomousAccess,
        infrastructure: CivSettlementInfrastructure, routes: Collection<List<CivPosition>>) {
        check(retained.infrastructure.all { old -> infrastructure.pieces.singleOrNull { it.id == old.id } == old })
        val paidIds = retained.infrastructure.mapTo(hashSetOf()) { it.id }
        val fresh = infrastructure.pieces.filter { it.id !in paidIds }
        check(fresh.none { next -> retained.infrastructure.any { overlaps(next.bounds, it.bounds) } })
        val perimeter = fresh.filter { it.type in setOf(BuildingType.WALL, BuildingType.GATE) }
        val grades = routes.flatten().groupBy { civsTerrainKey(it.x, it.z) }
        val transit = fresh.flatMap { it.transit }.groupBy { civsTerrainKey(it.x, it.z) }
        if (!closed(frame, perimeter) || grades.values.any { points -> points.map { it.y }.distinct().size != 1 } ||
            retained.routes.values.flatten().any { point ->
                transit[civsTerrainKey(point.x, point.z)]?.any { it.y != point.y } == true
            }) throw CivsRejection("autonomy-layout-obstructed", mapOf("stage" to "retained-growth-result"))
    }

    /** Detached authored-body proof for just the members of this perimeter. */
    fun closed(frame: CivSettlementFrame, perimeter: List<CivSettlementPiece>): Boolean {
        val coverage = perimeter
            .flatMapTo(hashSetOf()) { piece -> piece.blocks.map { civsTerrainKey(it.position.x, it.position.z) } }
        val covered = (frame.wallObstacles + frame.gates.map { it.passage }).all { box ->
            columns(box).all { (x, z) -> civsTerrainKey(x, z) in coverage }
        }
        // Detached proof of the generated palette's two full body cubes, not native collision QA.
        // A footprint, its floor and explicit AIR cannot close the new outer quarter.
        val full = setOf("minecraft:stone_bricks", "minecraft:cobblestone") +
            listOf("oak", "spruce", "dark_oak").flatMap { wood -> listOf("minecraft:${wood}_log", "minecraft:${wood}_planks") }
        val barrier = perimeter.flatMapTo(hashSetOf()) { piece ->
            val water = piece.waterFoundations.associateBy { civsTerrainKey(it.x, it.z) }
            piece.blocks.groupBy { civsTerrainKey(it.position.x, it.position.z) }.mapNotNull { (key, column) ->
                val floor = water[key]?.floorY ?: column.minOf { it.position.y }
                key.takeIf { (1..2).all { height -> column.any {
                    it.position.y == floor + height && it.after.substringBefore('[') in full
                } } }
            }
        }
        val gateBodies = frame.gates.all { gate ->
            val alongX = Math.floorMod(gate.side + frame.rotation / 90, 2) == 0
            val open = CivsInfrastructureRebuild.openColumns(gate.passage, alongX)
            val piece = perimeter.singleOrNull { it.type == BuildingType.GATE &&
                it.bounds.min.x == gate.passage.min.x && it.bounds.max.x == gate.passage.max.x &&
                it.bounds.min.z == gate.passage.min.z && it.bounds.max.z == gate.passage.max.z }
            piece != null && piece.transit.mapTo(hashSetOf()) { it.x to it.z } == open &&
                columns(gate.passage).filter { it !in open }.all { (x, z) -> civsTerrainKey(x, z) in barrier }
        }
        val bodyFrame = frame.copy(wallObstacles = barrier.map { key ->
            val p = CivPosition((key shr 32).toInt(), frame.origin.y, key.toInt())
            CivBox(p, p)
        })
        val interior = CivsSettlementGrowth.interior(frame)
        // Only this ring's body columns participate in the flood.
        return covered && gateBodies && interior.isNotEmpty() &&
            CivsSettlementGrowth.interior(bodyFrame).containsAll(interior)
    }

    private fun ring(west: Int, north: Int, east: Int, south: Int, y: Int,
        gate: CivSettlementGate?, rotation: Int): List<CivBox> = buildList {
        val gateSide = gate?.let { Math.floorMod(it.side + rotation / 90, 4) }
        for (side in 0..3) {
            val horizontal = side % 2 == 0
            val low = if (horizontal) west - 1 else north + 2
            val high = if (horizontal) east + 1 else south - 2
            val fixed = when (side) { 0 -> north; 1 -> east; 2 -> south; else -> west }
            val ranges = if (gateSide != side) listOf(low..high) else {
                val passage = requireNotNull(gate).passage
                val gapLow = if (horizontal) passage.min.x else passage.min.z
                val gapHigh = if (horizontal) passage.max.x else passage.max.z
                listOf(low until gapLow, gapHigh + 1..high)
            }
            for (range in ranges.filterNot { it.isEmpty() }) add(if (horizontal)
                CivBox(CivPosition(range.first, y, fixed - 1), CivPosition(range.last, y, fixed + 1))
                else CivBox(CivPosition(fixed - 1, y, range.first), CivPosition(fixed + 1, y, range.last)))
        }
    }

    private fun columns(box: CivBox): Sequence<Pair<Int, Int>> = (box.min.x..box.max.x).asSequence()
        .flatMap { x -> (box.min.z..box.max.z).asSequence().map { z -> x to z } }
    private fun overlaps(a: CivBox, b: CivBox) = CivsAutonomousGeometry.overlaps(a, b)
    private fun contains(box: CivBox, p: CivPosition) = CivsAutonomousGeometry.horizontalContains(box, p)
}
