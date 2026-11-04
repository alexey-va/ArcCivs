package ru.arc.civs

import java.util.UUID
import kotlin.math.abs

/** Authored fortification stages reuse ordinary captured, paid resident construction. */
internal object CivsInfrastructureRebuild {
    const val PREFIX = "civs/infrastructure/v5/"
    private const val PREVIOUS_PREFIX = "civs/infrastructure/v4/"

    fun stage(city: Settlement): Int = if (CivsSettlementPlans.current(city)?.plots?.any { it.fortification != null } == true)
        3 else city.level().coerceIn(1, 3)
    fun version(building: CivBuilding): Int = when {
        building.schematicId.startsWith(PREFIX) -> 5
        building.schematicId.startsWith(PREVIOUS_PREFIX) -> 4
        else -> 0
    }
    fun stage(building: CivBuilding): Int {
        val prefix = when (version(building)) { 5 -> PREFIX; 4 -> PREVIOUS_PREFIX; else -> return 0 }
        val parts = building.schematicId.removePrefix(prefix).split('/')
        return if (parts.size == 3 && parts[0] == building.type.name.lowercase() &&
            runCatching { UUID.fromString(parts[2]) }.isSuccess) parts[1].toIntOrNull()?.takeIf { it in 1..3 } ?: 0 else 0
    }

    fun owned(building: CivBuilding): Boolean = building.type in setOf(BuildingType.WALL, BuildingType.GATE) &&
        (stage(building) > 0 || building.schematicId == "civs/infrastructure/v1/${building.type.name.lowercase()}/${building.id}" ||
            building.type == BuildingType.GATE && CivsGateRefit.profileVersion(building) in 2..3)

    // Gates retain the existing queued-design key even at their final stage: a proved close pair can retire one later.
    fun needsRefit(building: CivBuilding): Boolean = building.phase == BuildingPhase.ACTIVE && owned(building) &&
        (version(building) < 5 || stage(building) < 3 || building.type == BuildingType.GATE)
    fun needsProfile(city: Settlement, building: CivBuilding): Boolean = needsRefit(building) &&
        (version(building) < 5 || stage(building) < stage(city))
    fun needsRefit(city: Settlement, building: CivBuilding): Boolean = city.autonomy != null &&
        (needsProfile(city, building) || pairs(city).any { building.id in listOf(it.first.id, it.second.id) })
    fun schematicId(type: BuildingType, stage: Int, capture: UUID): String = "$PREFIX${type.name.lowercase()}/$stage/$capture"
    fun queuedId(building: CivBuilding): UUID = UUID.nameUUIDFromBytes(
        "arccivs-infrastructure-refit/${building.id}/${building.schematicId}".toByteArray(Charsets.UTF_8))

    fun refit(previous: CivBuilding?, next: CivBuilding): Boolean = previous != null && needsRefit(previous) &&
        previous.id == next.id && previous.level == next.level && previous.anchor == next.anchor &&
        previous.bounds == next.bounds && previous.entrance == next.entrance && previous.rotation == next.rotation &&
        next.phase == BuildingPhase.CONSTRUCTING && version(next) >= version(previous) && stage(next) >= stage(previous) &&
        (previous.type == next.type && (version(next) > version(previous) || stage(next) > stage(previous)) ||
            previous.type == BuildingType.GATE && next.type == BuildingType.WALL && version(next) == 5 && next.transit == null)

    /** A type change is only this completed pair's final ordinary construction order. */
    fun refit(city: Settlement, previous: CivBuilding?, next: CivBuilding): Boolean = refit(previous, next) &&
        (previous!!.type == next.type || closureSurvivor(city, previous, next) != null)

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
        return captured.blocks.all { sameOwnedState(expected[it.position] ?: "minecraft:air", it.before) }
    }

    /** Native fence neighbours are derived by Minecraft; every other material/property remains exact. */
    internal fun sameOwnedState(expected: String, current: String): Boolean {
        if (expected == current) return true
        val material = expected.substringBefore('[')
        if (material != current.substringBefore('[') || !material.endsWith("_fence")) return false
        fun properties(state: String): Map<String, String>? {
            if (!state.startsWith("$material[") || !state.endsWith(']')) return null
            val entries = state.substringAfter('[').dropLast(1).split(',').map { it.split('=') }
            if (entries.any { it.size != 2 } || entries.map { it[0] }.distinct().size != entries.size) return null
            return entries.associate { it[0] to it[1] }
        }
        val a = properties(expected) ?: return false
        val b = properties(current) ?: return false
        val neighbours = setOf("north", "east", "south", "west")
        return neighbours.all { a[it] in setOf("true", "false") && b[it] in setOf("true", "false") } &&
            a.filterKeys { it !in neighbours } == b.filterKeys { it !in neighbours }
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
        if (version(building) == 5) return listOf(true, false).any { alongX ->
            val width = if (alongX) box.max.x - box.min.x + 1 else box.max.z - box.min.z + 1
            val depth = if (alongX) box.max.z - box.min.z + 1 else box.max.x - box.min.x + 1
            width in 3..16 && depth in 3..(if (stage(building) == 3) 9 else 5) &&
                transit.mapTo(hashSetOf()) { it.x to it.z } == openColumns(box, alongX) &&
                transit.all { it.y in box.min.y + 1..box.max.y - 4 }
        }
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

    internal fun openColumns(box: CivBox, alongX: Boolean): Set<Pair<Int, Int>> {
        val width = if (alongX) box.max.x - box.min.x + 1 else box.max.z - box.min.z + 1
        val depth = if (alongX) box.max.z - box.min.z + 1 else box.max.x - box.min.x + 1
        val low = (width - (if (width >= 5) 3 else 1)) / 2
        val high = low + if (width >= 5) 2 else 0
        return (0 until width).flatMap { a -> (0 until depth).filter { d ->
            a in low..high || depth > 3 && d in setOf(0, depth - 1)
        }.map { d -> if (alongX) box.min.x + a to box.min.z + d else box.min.x + d to box.min.z + a } }.toSet()
    }

    internal fun axis(building: CivBuilding): Boolean {
        val box = building.bounds
        val open = building.transit.orEmpty().mapTo(hashSetOf()) { it.x to it.z }
        if (version(building) == 5) listOf(true, false).firstOrNull { open == openColumns(box, it) }?.let { return it }
        val missing = (box.min.x..box.max.x).flatMap { x -> (box.min.z..box.max.z).map { z -> x to z } }.toSet() - open
        if (missing.isNotEmpty()) listOf(true, false).firstOrNull { alongX -> missing.all { (x, z) ->
            if (alongX) x in setOf(box.min.x, box.max.x) && z in box.min.z + 1 until box.max.z
            else z in setOf(box.min.z, box.max.z) && x in box.min.x + 1 until box.max.x
        } }?.let { return it }
        return when {
            building.entrance.x !in box.min.x..box.max.x -> false
            building.entrance.z !in box.min.z..box.max.z -> true
            else -> box.max.x - box.min.x >= box.max.z - box.min.z
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
        val open = openColumns(box, alongX)
        val posts = (0 until width).flatMap { across -> (0 until depth).map { d -> at(across, 0, d) } }
            .mapTo(linkedSetOf()) { it.x to it.z } - open
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
                else if (abs((if (alongX) p.x else p.z) - (if (alongX) (box.min.x + box.max.x) / 2
                    else (box.min.z + box.max.z) / 2)) == (if (width >= 5) 2 else 1)) "minecraft:${wood}_log[axis=y]"
                else "minecraft:${wood}_planks"
        val fortified = stage == 3 && box.max.y >= ground + 9
        requireGate(depth <= 5 || fortified)
        val roofDepth = if (fortified || depth == 3) 0 until depth else 1 until depth - 1
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
    fun proposal(city: Settlement, source: CivPlacementPlan, witnesses: List<CivPosition>,
        survivor: CivBuilding? = null): CivPlacementPlan {
        val old = city.building(source.building.id) ?: throw CivsRejection("building-not-found")
        requireGate(needsRefit(city, old) && source.id == CivsGateRefit.retainedPlanId(old) &&
            source.cityId == city.id && source.worldId == city.worldId && !source.adminPlacement &&
            source.building.copy(phase = BuildingPhase.ACTIVE) == old && source.capturedSha256 != null)
        val stage = maxOf(stage(city), stage(old))
        val targetType = if (survivor == null) old.type else BuildingType.WALL
        val capture = captureId(source.id, stage, survivor?.id)
        var piece = CivSettlementPiece(old.id, old.type, old.bounds, old.entrance,
            source.blocks.map { CivSettlementBlock(it.position, it.after) }, old.transit.orEmpty())
        if (old.type == BuildingType.GATE && survivor == null) {
            val axis = axis(old)
            piece = gate(piece, city.style, stage, axis, witnesses)
        } else {
            val alongX = if (old.type == BuildingType.GATE) axis(old) else wallAxis(city, old)
            val middle = if (alongX) (old.bounds.min.z + old.bounds.max.z) / 2 else (old.bounds.min.x + old.bounds.max.x) / 2
            val water = source.terrain?.waterFoundations.orEmpty().associateBy { it.x to it.z }
            val joins = wallJoins(city.buildings.filter { it.type == BuildingType.WALL }.map { it.bounds })
            val cells = source.blocks.associate { it.position to it.after }.toMutableMap()
            for ((column, authored) in source.blocks.groupBy { it.position.x to it.position.z }) {
                val floor = water[column]?.floorY ?: authored.minOf { it.position.y }
                val top = authored.maxOf { it.position.y } - floor
                requireGate(top in 3..16)
                // The retired gate's front/back rows remain service ground, never a second public passage.
                val innerHalfDepth = if (old.type == BuildingType.GATE && stage == 3)
                    ((if (alongX) old.bounds.max.z - old.bounds.min.z else old.bounds.max.x - old.bounds.min.x) / 2 - 1).coerceAtLeast(1)
                    else 1
                if (old.type == BuildingType.GATE && abs((if (alongX) column.second else column.first) - middle) > innerHalfDepth) {
                    authored.filter { it.position.y > floor }.forEach { cells[it.position] = "minecraft:air" }
                    continue
                }
                val edge = if (old.type == BuildingType.GATE)
                    abs((if (alongX) column.second else column.first) - middle) == innerHalfDepth
                    else if (alongX) column.second in setOf(old.bounds.min.z, old.bounds.max.z)
                    else column.first in setOf(old.bounds.min.x, old.bounds.max.x)
                wallColumn(city.style, stage, column.first, floor, column.second, alongX, middle, top, edge, column in joins)
                    .forEach { cells[it.position] = it.after }
            }
            piece = piece.copy(blocks = cells.entries.sortedWith(compareBy({ it.key.y }, { it.key.z }, { it.key.x }))
                .map { CivSettlementBlock(it.key, it.value) }, profileStage = stage)
        }
        val target = old.copy(type = targetType, schematicId = schematicId(targetType, stage, capture), phase = BuildingPhase.CONSTRUCTING,
            transit = piece.transit.takeIf { targetType == BuildingType.GATE })
        requireGate(refit(city, old, target) && (targetType != BuildingType.GATE || validTransit(target)))
        val after = piece.blocks.associate { it.position to it.after }
        requireGate(witnesses.all { p -> (0..1).all { dy -> after[p.copy(y = p.y + dy)] == "minecraft:air" } },
            "gate-refit-route-conflict")
        val before = source.blocks.associate { it.position to it.after }
        return source.copy(id = capture, building = target, blocks = piece.blocks.map {
            CivPlanBlock(it.position, before[it.position] ?: "minecraft:air", it.after)
        }, terrain = source.terrain?.copy(blocks = emptyList(), cutCount = 0, fillCount = 0, waterFoundations = null), capturedSha256 = null)
    }

    private fun captureId(source: UUID, stage: Int, survivor: UUID? = null): UUID = UUID.nameUUIDFromBytes(
        "arccivs-infrastructure-v5/$source/$stage/${survivor ?: "profile"}".toByteArray(Charsets.UTF_8))

    internal data class Pairing(val first: CivBuilding, val second: CivBuilding, val alongX: Boolean)
    internal data class Work(val building: CivBuilding, val survivor: CivBuilding? = null,
        val infill: CivWallInfillTarget? = null)

    private fun low(b: CivBuilding, alongX: Boolean) = if (alongX) b.bounds.min.x else b.bounds.min.z
    private fun high(b: CivBuilding, alongX: Boolean) = if (alongX) b.bounds.max.x else b.bounds.max.z
    private fun middle(b: CivBuilding, alongX: Boolean) = if (alongX)
        (b.bounds.min.z + b.bounds.max.z) / 2 else (b.bounds.min.x + b.bounds.max.x) / 2

    /** Only a short continuous strip on the same perimeter layer can consolidate its adjacent exits. */
    internal fun pairs(city: Settlement): List<Pairing> {
        val gates = city.buildings.filter { it.type == BuildingType.GATE && it.phase == BuildingPhase.ACTIVE && owned(it) }
            .sortedBy { it.id.toString() }
        return gates.flatMapIndexed { i, a -> gates.drop(i + 1).mapNotNull { b ->
            val alongX = axis(a)
            if (axis(b) != alongX || middle(a, alongX) != middle(b, alongX)) return@mapNotNull null
            val (first, second) = listOf(a, b).sortedBy { low(it, alongX) }
            val gap = low(second, alongX).toLong() - high(first, alongX) - 1
            if (gap !in 0L..6L || abs((low(a, alongX).toLong() + high(a, alongX)) -
                    (low(b, alongX).toLong() + high(b, alongX))) > 32L) return@mapNotNull null
            val pair = Pairing(first, second, alongX)
            val walls = walls(city, pair)
            val missing = (high(first, alongX) + 1 until low(second, alongX)).filter { at ->
                walls.none { at in low(it, alongX)..high(it, alongX) }
            }
            pair.takeIf { walls.isNotEmpty() && missing.size <= 2 &&
                (missing.isEmpty() || missing.last() - missing.first() + 1 == missing.size) }
        } }
    }

    private fun wallAxis(city: Settlement, wall: CivBuilding): Boolean = wallAxis(city, wall.bounds)
    internal fun wallAxis(city: Settlement, box: CivBox): Boolean = city.buildings.asSequence()
        .filter { it.type == BuildingType.GATE && it.phase == BuildingPhase.ACTIVE && owned(it) }
        .map { gate -> gate to axis(gate) }.firstOrNull { (gate, axis) ->
            middle(gate, axis) == (if (axis) (box.min.z + box.max.z) / 2 else (box.min.x + box.max.x) / 2) &&
                (if (axis) box.min.x else box.min.z) <= high(gate, axis) + 3 &&
                (if (axis) box.max.x else box.max.z) >= low(gate, axis) - 3
        }?.second ?: (box.max.x - box.min.x >= box.max.z - box.min.z)

    internal fun walls(city: Settlement, pair: Pairing): List<CivBuilding> = city.buildings.filter {
        it.type == BuildingType.WALL && it.phase == BuildingPhase.ACTIVE && owned(it) &&
            middle(it, pair.alongX) == middle(pair.first, pair.alongX) &&
            low(it, pair.alongX) <= high(pair.second, pair.alongX) + 3 &&
            high(it, pair.alongX) >= low(pair.first, pair.alongX) - 3 &&
            (if (pair.alongX) it.bounds.max.z - it.bounds.min.z else it.bounds.max.x - it.bounds.min.x) in 0..4
    }.sortedBy { low(it, pair.alongX) }

    internal fun witnesses(city: Settlement, plans: List<CivPlacementPlan>): List<CivPosition> {
        val access = CivAutonomousAccess.from(city, plans)
        for (b in city.buildings.filter { it.phase == BuildingPhase.ACTIVE }) {
            val p = plans.singleOrNull { it.building.id == b.id }
            requireGate(p != null && p.cityId == city.id && p.worldId == city.worldId &&
                p.building.type == b.type && p.building.bounds == b.bounds && p.building.entrance == b.entrance &&
                p.building.anchor == b.anchor && p.building.rotation == b.rotation && p.capturedSha256 != null,
                "gate-refit-source-missing")
            requireGate(p!!.terrain?.route.isNullOrEmpty() || b.id in access.routes, "gate-refit-source-missing")
        }
        return (access.routes.values.flatten() + city.buildings.flatMap(CivsAutonomousGeometry::approach) +
            city.buildings.filter { it.type == BuildingType.ROAD }.flatMap { it.transit.orEmpty() } +
            CivsSettlementPlans.current(city)?.routes.orEmpty().values.flatten()).distinct()
    }

    internal fun next(city: Settlement, plans: List<CivPlacementPlan>, available: Set<UUID>): Work? {
        val pairs = pairs(city)
        val protected = pairs.flatMapTo(hashSetOf()) { listOf(it.first.id, it.second.id) + walls(city, it).map { b -> b.id } }
        val routes = if (pairs.isEmpty()) emptyList() else witnesses(city, plans)
        fun through(gate: CivBuilding): Int = routes.count { p ->
            CivsAutonomousGeometry.horizontalContains(gate.bounds, p) &&
                (if (axis(gate)) p.z else p.x) == middle(gate, axis(gate))
        }
        for (pair in pairs) {
            if (pair.first.id !in available || pair.second.id !in available) continue
            val candidates = listOf(pair.first, pair.second).sortedWith(compareByDescending<CivBuilding>(::through)
                .thenBy { low(it, pair.alongX) }.thenBy { it.id.toString() })
            val survivor = candidates.first()
            val closing = candidates.last()
            // A retained crossing through each gate needs a separate connector design; leave both exits intact.
            if (through(closing) != 0 || !city.roadRouteOverrides?.get(closing.id.toString())?.gateDetours.isNullOrEmpty()) continue
            if (needsProfile(city, survivor)) return Work(survivor)
            val walls = walls(city, pair)
            walls.firstOrNull { needsProfile(city, it) && it.id in available }?.let { return Work(it) }
            if (walls.any { needsProfile(city, it) }) continue
            for (gate in candidates) for (wall in walls) {
                val infill = runCatching { CivsWallInfill.target(city, gate.id, wall.id) }.getOrNull() ?: continue
                if (city.buildings.none { CivsAutonomousGeometry.overlaps(it.bounds, infill.footprint) })
                    return Work(gate, infill = infill)
            }
            if (readyPair(city, pair, survivor)) return Work(closing, survivor)
        }
        return city.buildings.filter { it.id in available && it.id !in protected && needsProfile(city, it) }
            .minWithOrNull(compareBy<CivBuilding> { if (it.type == BuildingType.GATE) 0 else 1 }
                .thenBy { it.bounds.min.x }.thenBy { it.bounds.min.z })?.let(::Work)
    }

    private fun readyPair(city: Settlement, pair: Pairing, survivor: CivBuilding): Boolean {
        if (version(survivor) != 5 || !validTransit(survivor) || needsProfile(city, survivor)) return false
        val walls = walls(city, pair)
        return walls.isNotEmpty() && walls.all { version(it) == 5 && !needsProfile(city, it) } &&
            (high(pair.first, pair.alongX) + 1 until low(pair.second, pair.alongX)).all { at ->
                walls.any { at in low(it, pair.alongX)..high(it, pair.alongX) }
            }
    }

    internal fun closureSurvivor(city: Settlement, previous: CivBuilding, next: CivBuilding): CivBuilding? {
        if (previous.type != BuildingType.GATE || next.type != BuildingType.WALL || !refit(previous, next) ||
            !city.roadRouteOverrides?.get(previous.id.toString())?.gateDetours.isNullOrEmpty()) return null
        return pairs(city).asSequence().filter { previous.id in listOf(it.first.id, it.second.id) }.mapNotNull { pair ->
            val survivor = if (pair.first.id == previous.id) pair.second else pair.first
            survivor.takeIf { readyPair(city, pair, it) &&
                next.schematicId == schematicId(BuildingType.WALL, stage(next),
                    captureId(CivsGateRefit.retainedPlanId(previous), stage(next), it.id)) }
        }.singleOrNull()
    }

    /** Each line includes the full central passage and the immediately adjacent real approach on both sides. */
    internal fun readyLines(gate: CivBuilding): List<List<CivPosition>> {
        requireGate(version(gate) == 5 && validTransit(gate))
        val alongX = axis(gate)
        val box = gate.bounds
        val lo = low(gate, alongX); val hi = high(gate, alongX)
        val coreLow = lo + (hi - lo + 1 - if (hi - lo + 1 >= 5) 3 else 1) / 2
        val width = if (hi - lo + 1 >= 5) 3 else 1
        val minDepth = if (alongX) box.min.z else box.min.x
        val maxDepth = if (alongX) box.max.z else box.max.x
        val feet = gate.transit.orEmpty().associateBy { it.x to it.z }
        return (coreLow until coreLow + width).map { axis ->
            val line = (minDepth..maxDepth).map { depth ->
                feet.getValue(if (alongX) axis to depth else depth to axis)
            }
            listOf(line.first().let { if (alongX) it.copy(z = it.z - 1) else it.copy(x = it.x - 1) }) + line +
                line.last().let { if (alongX) it.copy(z = it.z + 1) else it.copy(x = it.x + 1) }
        }
    }

    private fun requireGate(value: Boolean, reason: String = "gate-refit-unsupported") {
        if (!value) throw CivsRejection(reason)
    }
}
