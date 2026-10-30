package ru.arc.civs

import kotlin.math.abs

/** Derive openings from retained routes without moving any already accepted infrastructure. */
internal object CivsPerimeterCrossings {
    fun adapt(frame: CivSettlementFrame, routes: Collection<List<CivPosition>>,
        accepted: List<CivSettlementPiece>): CivSettlementFrame {
        var walls = frame.wallObstacles
        val gates = frame.gates.toMutableList()
        fun horizontal(box: CivBox) = box.max.x - box.min.x >= box.max.z - box.min.z
        fun contains(box: CivBox, p: CivPosition) = p.x in box.min.x..box.max.x && p.z in box.min.z..box.max.z
        fun intersects(a: CivBox, b: CivBox) = a.min.x <= b.max.x && a.max.x >= b.min.x &&
            a.min.z <= b.max.z && a.max.z >= b.min.z
        fun flat(box: CivBox) = CivBox(box.min.copy(y = frame.origin.y), box.max.copy(y = frame.origin.y))
        fun subtract(passage: CivBox) {
            walls = walls.flatMap { wall ->
                if (!intersects(wall, passage)) listOf(wall) else buildList {
                    val overlapMinX = maxOf(wall.min.x, passage.min.x)
                    val overlapMaxX = minOf(wall.max.x, passage.max.x)
                    if (wall.min.x < overlapMinX) add(wall.copy(max = wall.max.copy(x = overlapMinX - 1)))
                    if (wall.max.x > overlapMaxX) add(wall.copy(min = wall.min.copy(x = overlapMaxX + 1)))
                    if (wall.min.z < passage.min.z) add(CivBox(wall.min.copy(x = overlapMinX),
                        wall.max.copy(x = overlapMaxX, z = passage.min.z - 1)))
                    if (wall.max.z > passage.max.z) add(CivBox(wall.min.copy(x = overlapMinX, z = passage.max.z + 1),
                        wall.max.copy(x = overlapMaxX)))
                }
            }
        }
        // Previously accepted gates own their original passage even if the originating route later changes.
        accepted.filter { it.type == BuildingType.GATE }.forEach { piece ->
            val passage = flat(piece.bounds)
            if (gates.none { flat(it.passage) == passage }) {
                val wall = walls.firstOrNull { intersects(it, passage) } ?: return@forEach
                val direction = if (horizontal(wall)) 0 else 1
                gates += CivSettlementGate(Math.floorMod(direction - frame.rotation / 90, 4),
                    CivPosition((passage.min.x + passage.max.x) / 2, frame.origin.y,
                        (passage.min.z + passage.max.z) / 2), passage)
            }
            subtract(passage)
        }
        val crossing = routes.flatten().filter { p -> walls.any { contains(it, p) } }
            .distinctBy { civsTerrainKey(it.x, it.z) }
            .sortedWith(compareBy<CivPosition> { it.x }.thenBy { it.z }).toMutableList()
        while (crossing.isNotEmpty()) {
            val group = mutableListOf(crossing.removeAt(0))
            var added: Boolean
            do {
                val adjacent = crossing.filter { p -> group.any { abs(it.x - p.x) <= 2 && abs(it.z - p.z) <= 2 } }
                group += adjacent
                crossing.removeAll(adjacent.toSet())
                added = adjacent.isNotEmpty()
            } while (added)
            val first = group.first()
            val wall = walls.firstOrNull { contains(it, first) } ?: continue
            val alongX = horizontal(wall)
            val minX = group.minOf { it.x } - if (alongX) 2 else 1
            val maxX = group.maxOf { it.x } + if (alongX) 2 else 1
            val minZ = group.minOf { it.z } - if (alongX) 1 else 2
            val maxZ = group.maxOf { it.z } + if (alongX) 1 else 2
            val passage = CivBox(CivPosition(minX, frame.origin.y, minZ), CivPosition(maxX, frame.origin.y, maxZ))
            // Existing paid structures stay untouched. An impossible crossing remains visibly rejected.
            if (gates.size >= 8 || gates.any { intersects(it.passage, passage) } || accepted.any {
                    it.type != BuildingType.GATE && intersects(it.bounds, passage)
                }) continue
            val direction = if (alongX) 0 else 1
            gates += CivSettlementGate(Math.floorMod(direction - frame.rotation / 90, 4),
                CivPosition((minX + maxX) / 2, frame.origin.y, (minZ + maxZ) / 2), passage)
            subtract(passage)
        }
        return frame.copy(wallObstacles = walls, gates = gates)
    }
}
