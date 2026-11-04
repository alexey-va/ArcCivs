package ru.arc.civs

import java.util.UUID
import kotlin.math.abs

/** Authored fortification stages reuse ordinary captured, paid resident construction. */
internal object CivsInfrastructureRebuild {
    const val PREFIX = "civs/infrastructure/v4/"

    fun stage(city: Settlement): Int = if (CivsSettlementPlans.current(city)?.plots?.any { it.fortification != null } == true)
        3 else city.level().coerceIn(1, 3)
    fun stage(building: CivBuilding): Int = if (!building.schematicId.startsWith(PREFIX)) 0 else
        building.schematicId.removePrefix(PREFIX).split('/').let { parts ->
            if (parts.size == 3 && parts[0] == building.type.name.lowercase() &&
                runCatching { UUID.fromString(parts[2]) }.isSuccess) parts[1].toIntOrNull()?.takeIf { it in 1..3 } ?: 0 else 0
        }

    fun owned(building: CivBuilding): Boolean = building.type in setOf(BuildingType.WALL, BuildingType.GATE) &&
        (stage(building) > 0 || building.schematicId == "civs/infrastructure/v1/${building.type.name.lowercase()}/${building.id}" ||
            building.type == BuildingType.GATE && CivsGateRefit.profileVersion(building) in 2..3)

    fun needsRefit(building: CivBuilding): Boolean = building.phase == BuildingPhase.ACTIVE && owned(building) && stage(building) < 3
    fun needsRefit(city: Settlement, building: CivBuilding): Boolean = city.autonomy != null && needsRefit(building) && stage(building) < stage(city)
    fun schematicId(type: BuildingType, stage: Int, capture: UUID): String = "$PREFIX${type.name.lowercase()}/$stage/$capture"
    fun queuedId(building: CivBuilding): UUID = UUID.nameUUIDFromBytes(
        "arccivs-infrastructure-refit/${building.id}/${building.schematicId}".toByteArray(Charsets.UTF_8))

    fun refit(previous: CivBuilding?, next: CivBuilding): Boolean = previous != null && needsRefit(previous) &&
        previous.id == next.id && previous.type == next.type && previous.level == next.level &&
        previous.anchor == next.anchor && previous.bounds == next.bounds && previous.entrance == next.entrance &&
        previous.rotation == next.rotation && next.phase == BuildingPhase.CONSTRUCTING && stage(next) > stage(previous)

    fun finishedRoutes(city: Settlement, previous: CivBuilding, finished: CivBuilding): Map<String, CivRoadRouteOverride>? {
        if (previous.type != BuildingType.GATE || !refit(previous, finished.copy(phase = BuildingPhase.CONSTRUCTING)))
            return city.roadRouteOverrides
        return city.roadRouteOverrides?.mapValues { (owner, route) ->
            if (owner != previous.id.toString() || route.gateDetours.isNullOrEmpty()) route else route.copy(
                gateDetours = route.gateDetours.map { it.copy(captureId = CivsGateRefit.retainedPlanId(finished)) })
        }
    }

    fun matchesSource(source: CivPlacementPlan, captured: CivPlacementPlan): Boolean {
        val expected = source.blocks.associate { it.position to it.after }
        return captured.blocks.all { it.before == (expected[it.position] ?: "minecraft:air") }
    }

    /** Three-wide strips own corner shoulders; join their thin barriers inside that existing domain. */
    fun wallJoins(walls: List<CivBox>): Set<Pair<Int, Int>> {
        val line = hashSetOf<Pair<Int, Int>>()
        val ends = mutableListOf<Pair<Pair<Int, Int>, Pair<Int, Int>>>()
        for (wall in walls) {
            if (wall.max.x - wall.min.x >= wall.max.z - wall.min.z) {
                val z = (wall.min.z + wall.max.z) / 2
                (wall.min.x..wall.max.x).forEach { line += it to z }
                ends += (wall.min.x to z) to (-1 to 0); ends += (wall.max.x to z) to (1 to 0)
            } else {
                val x = (wall.min.x + wall.max.x) / 2
                (wall.min.z..wall.max.z).forEach { line += x to it }
                ends += (x to wall.min.z) to (0 to -1); ends += (x to wall.max.z) to (0 to 1)
            }
        }
        return ends.mapNotNullTo(hashSetOf()) { (end, direction) ->
            val next = end.first + direction.first to end.second + direction.second
            next.takeIf { it !in line && walls.any { wall ->
                it.first in wall.min.x..wall.max.x && it.second in wall.min.z..wall.max.z
            } && listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1).any { (dx, dz) ->
                val neighbour = it.first + dx to it.second + dz
                neighbour != end && neighbour in line
            } }
        }
    }

    /** Gate columns reserve only the actual piers; every paid through-route is kept at its original grade. */
    fun validTransit(building: CivBuilding): Boolean {
        if (building.type != BuildingType.GATE || stage(building) == 0) return false
        val box = building.bounds
        val transit = building.transit.orEmpty()
        if (transit.isEmpty() || transit.size > 256 || transit.map { it.x to it.z }.distinct().size != transit.size) return false
        return listOf(true, false).any { alongX ->
            val width = if (alongX) box.max.x - box.min.x + 1 else box.max.z - box.min.z + 1
            val depth = if (alongX) box.max.z - box.min.z + 1 else box.max.x - box.min.x + 1
            if (width !in 3..16 || depth !in 3..(if (stage(building) == 3) 9 else 5)) return@any false
            val columns = (box.min.x..box.max.x).flatMap { x -> (box.min.z..box.max.z).map { z -> x to z } }.toSet()
            val open = transit.mapTo(hashSetOf()) { it.x to it.z }
            val posts = columns - open
            posts.size in 2..6 && posts.all { (x, z) ->
                (if (alongX) x in setOf(box.min.x, box.max.x) else z in setOf(box.min.z, box.max.z)) &&
                    (if (alongX) z in box.min.z + 1 until box.max.z else x in box.min.x + 1 until box.max.x)
            } && posts.any { (x, z) -> if (alongX) x == box.min.x else z == box.min.z } &&
                posts.any { (x, z) -> if (alongX) x == box.max.x else z == box.max.z } &&
                open.all { it in columns } && transit.all { it.y in box.min.y + 1..box.max.y - 4 }
        }
    }

    fun wallColumn(style: String, stage: Int, x: Int, ground: Int, z: Int, alongX: Boolean,
        middle: Int, top: Int, outerEdge: Boolean = if (alongX) z != middle else x != middle,
        join: Boolean = false): List<CivSettlementBlock> {
        val wood = CivsSettlementLayout.wood(style)
        val across = if (alongX) z else x
        val barrier = across == middle || join
        val axis = if (alongX) x else z
        val post = Math.floorMod(axis, 4) == 0
        val stone = if (style == "nordic") "minecraft:cobblestone" else "minecraft:stone_bricks"
        val walk = if (top >= 9) 7 else 3
        return (0..top).map { dy ->
            val block = when {
                // A missing seam in an already-paid legacy wall keeps that wall's authored profile.
                stage == 0 && dy <= 3 -> if (across == middle && Math.floorMod(axis, 6) == 0)
                    "minecraft:${wood}_log[axis=y]" else stone
                stage == 0 && dy == 4 && across == middle ->
                    "minecraft:${wood}_fence[east=false,north=false,south=false,waterlogged=false,west=false]"
                stage == 0 && dy == 4 && Math.floorMod(axis, 3) == 0 -> stone
                stage == 0 -> "minecraft:air"
                dy == 0 -> "minecraft:cobblestone"
                stage == 1 && barrier && dy <= 2 -> "minecraft:${wood}_${if (post) "log[axis=y]" else "planks"}"
                stage == 1 && barrier && dy == 3 ->
                    if (post) "minecraft:${wood}_log[axis=y]" else "minecraft:${wood}_slab[type=bottom,waterlogged=false]"
                stage == 2 && dy <= 2 && (barrier || post) -> stone
                stage == 2 && barrier && dy == 3 -> "minecraft:${wood}_log[axis=${if (alongX) "x" else "z"}]"
                stage == 2 && barrier && dy == 4 -> "minecraft:${wood}_slab[type=bottom,waterlogged=false]"
                stage == 3 && dy <= walk -> stone
                stage == 3 && dy == walk + 1 && outerEdge && Math.floorMod(axis, 3) != 1 -> stone
                stage == 3 && dy == walk + 1 && across == middle && top < 9 && Math.floorMod(axis, 3) == 0 -> stone
                else -> "minecraft:air"
            }
            CivSettlementBlock(CivPosition(x, ground + dy, z), block)
        }
    }

    fun gate(piece: CivSettlementPiece, style: String, stage: Int, alongX: Boolean,
        witnesses: Collection<CivPosition>): CivSettlementPiece {
        val box = piece.bounds
        val width = if (alongX) box.max.x - box.min.x + 1 else box.max.z - box.min.z + 1
        val depth = if (alongX) box.max.z - box.min.z + 1 else box.max.x - box.min.x + 1
        requireGate(piece.type == BuildingType.GATE && width in 3..16 && stage in 1..3 &&
            depth in 3..(if (stage == 3) 9 else 5))
        fun at(across: Int, y: Int, d: Int) = if (alongX) CivPosition(box.min.x + across, y, box.min.z + d)
            else CivPosition(box.min.x + d, y, box.min.z + across)
        val source = piece.blocks.associate { it.position to it.after }
        requireGate(source.size == piece.blocks.size)
        val feet = piece.transit.associateBy { it.x to it.z }.toMutableMap()
        val posts = linkedSetOf<Pair<Int, Int>>()
        val blocked = witnesses.mapTo(hashSetOf()) { it.x to it.z }
        for (across in listOf(0, width - 1)) {
            val old = (1 until depth - 1).map { at(across, 0, it) }.filter { (it.x to it.z) !in feet }
            val chosen = if (old.isNotEmpty()) old else listOfNotNull((1 until depth - 1)
                .sortedBy { abs(it - depth / 2) }.map { at(across, 0, it) }.firstOrNull { (it.x to it.z) !in blocked })
            requireGate(chosen.isNotEmpty(), "gate-refit-route-conflict")
            chosen.forEach { posts += it.x to it.z }
        }
        val full = (0 until width).flatMap { across -> (0 until depth).map { d ->
            val column = at(across, 0, d)
            feet[column.x to column.z] ?: source.entries.filter { (p, state) ->
                p.x == column.x && p.z == column.z && p.y in piece.transit.minOf { it.y }..piece.transit.maxOf { it.y } &&
                    state in setOf("minecraft:stone_bricks", "minecraft:cobblestone")
            }.minByOrNull { it.key.y }?.key ?: throw CivsRejection("gate-refit-unsupported")
        } }
        val ground = full.maxOf { it.y } - 1
        val beam = ground + 4
        requireGate(beam + 1 <= box.max.y && full.all { it.y >= box.min.y + 1 })
        val wood = CivsSettlementLayout.wood(style)
        val stone = if (style == "nordic") "minecraft:cobblestone" else "minecraft:stone_bricks"
        val cells = source.toMutableMap()
        for (p in full) for (y in p.y..box.max.y) cells[p.copy(y = y)] = "minecraft:air"
        for (p in full.filter { (it.x to it.z) in posts }) for (y in p.y until beam)
            cells[p.copy(y = y)] = if (stage == 3 || y < p.y + if (stage == 2) 2 else 1) stone
                else "minecraft:${wood}_log[axis=y]"
        val fortified = stage == 3 && box.max.y >= ground + 9
        requireGate(depth <= 5 || fortified)
        val roofDepth = if (fortified) 0 until depth else if (depth == 3) 0..2 else 1..3
        for (across in 0 until width) for (d in roofDepth) {
            cells[at(across, beam, d)] = if (stage == 3) stone else "minecraft:${wood}_log[axis=${if (alongX) "x" else "z"}]"
            if (fortified) {
                for (y in beam + 1..ground + 7) cells[at(across, y, d)] = stone
                cells[at(across, ground + 8, d)] = if (d in setOf(roofDepth.first, roofDepth.last) &&
                    Math.floorMod(across, 3) != 1) stone else "minecraft:air"
            } else {
                val facing = if (alongX) { if (d == roofDepth.first) "south" else "north" }
                    else { if (d == roofDepth.first) "east" else "west" }
                cells[at(across, beam + 1, d)] = if (d == depth / 2) "minecraft:${wood}_slab[type=top,waterlogged=false]"
                    else "minecraft:${wood}_stairs[facing=$facing,half=bottom,shape=straight,waterlogged=false]"
            }
        }
        val transit = full.filterNot { (it.x to it.z) in posts }
        requireGate(witnesses.all { p -> transit.any { it == p } }, "gate-refit-route-conflict")
        return piece.copy(blocks = cells.entries.sortedWith(compareBy({ it.key.y }, { it.key.z }, { it.key.x }))
            .map { CivSettlementBlock(it.key, it.value) }, transit = transit, profileStage = stage)
    }

    /** Exact saved source is the only authority to replace a previously paid fortification. */
    fun proposal(city: Settlement, source: CivPlacementPlan, witnesses: List<CivPosition>): CivPlacementPlan {
        val old = city.building(source.building.id) ?: throw CivsRejection("building-not-found")
        requireGate(needsRefit(city, old) && source.id == CivsGateRefit.retainedPlanId(old) &&
            source.cityId == city.id && source.worldId == city.worldId && !source.adminPlacement &&
            source.building.copy(phase = BuildingPhase.ACTIVE) == old && source.capturedSha256 != null)
        val stage = stage(city)
        val capture = UUID.nameUUIDFromBytes("arccivs-infrastructure-capture/${source.id}/$stage".toByteArray(Charsets.UTF_8))
        var piece = CivSettlementPiece(old.id, old.type, old.bounds, old.entrance,
            source.blocks.map { CivSettlementBlock(it.position, it.after) }, old.transit.orEmpty())
        if (old.type == BuildingType.GATE) {
            val missing = (old.bounds.min.x..old.bounds.max.x).flatMap { x ->
                (old.bounds.min.z..old.bounds.max.z).map { z -> x to z }
            }.toSet() - old.transit.orEmpty().map { it.x to it.z }.toSet()
            val axis = if (missing.isNotEmpty()) missing.all { it.first in setOf(old.bounds.min.x, old.bounds.max.x) }
            else source.blocks.firstOrNull { it.after.contains("_fence_gate[") }?.after?.let {
                "facing=north" in it || "facing=south" in it
            } ?: if (old.bounds.max.x - old.bounds.min.x != old.bounds.max.z - old.bounds.min.z)
                old.bounds.max.x - old.bounds.min.x > old.bounds.max.z - old.bounds.min.z
            else CivsGateRefit.profileAxis(old.bounds, old.transit.orEmpty()) ?: true
            piece = gate(piece, city.style, stage, axis, witnesses)
        } else {
            val alongX = old.bounds.max.x - old.bounds.min.x >= old.bounds.max.z - old.bounds.min.z
            val middle = if (alongX) (old.bounds.min.z + old.bounds.max.z) / 2 else (old.bounds.min.x + old.bounds.max.x) / 2
            val water = source.terrain?.waterFoundations.orEmpty().associateBy { it.x to it.z }
            val joins = wallJoins(city.buildings.filter { it.type == BuildingType.WALL }.map { it.bounds })
            val cells = source.blocks.associate { it.position to it.after }.toMutableMap()
            for ((column, authored) in source.blocks.groupBy { it.position.x to it.position.z }) {
                val floor = water[column]?.floorY ?: authored.minOf { it.position.y }
                val top = authored.maxOf { it.position.y } - floor
                requireGate(top in 3..16)
                val edge = if (alongX) column.second in setOf(old.bounds.min.z, old.bounds.max.z)
                    else column.first in setOf(old.bounds.min.x, old.bounds.max.x)
                wallColumn(city.style, stage, column.first, floor, column.second, alongX, middle, top, edge, column in joins)
                    .forEach { cells[it.position] = it.after }
            }
            piece = piece.copy(blocks = cells.entries.sortedWith(compareBy({ it.key.y }, { it.key.z }, { it.key.x }))
                .map { CivSettlementBlock(it.key, it.value) }, profileStage = stage)
        }
        val target = old.copy(schematicId = schematicId(old.type, stage, capture), phase = BuildingPhase.CONSTRUCTING,
            transit = piece.transit.takeIf { old.type == BuildingType.GATE })
        requireGate(refit(old, target) && (old.type != BuildingType.GATE || validTransit(target)))
        val before = source.blocks.associate { it.position to it.after }
        return source.copy(id = capture, building = target, blocks = piece.blocks.map {
            CivPlanBlock(it.position, before[it.position] ?: "minecraft:air", it.after)
        }, terrain = source.terrain?.copy(blocks = emptyList(), cutCount = 0, fillCount = 0, waterFoundations = null), capturedSha256 = null)
    }

    private fun requireGate(value: Boolean, reason: String = "gate-refit-unsupported") {
        if (!value) throw CivsRejection(reason)
    }
}
