package ru.arc.civs

import java.security.MessageDigest
import java.util.UUID

/** Grounded 5/6-wide gates inside their paid footprint; old captures remain immutable. */
internal object CivsGateRefit {
    const val PREFIX = "civs/infrastructure/v3/gate/"
    private const val POSTED_PREFIX = "civs/infrastructure/v2/gate/"
    private const val LEGACY_PREFIX = "civs/infrastructure/v1/gate/"

    fun profileVersion(building: CivBuilding): Int = if (building.type != BuildingType.GATE) 0 else when {
        building.schematicId.startsWith(PREFIX) -> 3
        building.schematicId.startsWith(POSTED_PREFIX) -> 2
        else -> 0
    }
    fun versioned(building: CivBuilding): Boolean = profileVersion(building) != 0
    fun retainedPlanId(building: CivBuilding): UUID = if (versioned(building))
        UUID.fromString(building.schematicId.substringAfterLast('/')) else building.id

    fun validTransit(building: CivBuilding): Boolean {
        if (!versioned(building) || runCatching { retainedPlanId(building) }.isFailure) return false
        return profileAxis(building.bounds, building.transit.orEmpty()) != null ||
            profileVersion(building) == 3 && compactAxis(building.bounds, building.transit.orEmpty()) != null
    }

    fun generatedProfile(box: CivBox, transit: List<CivPosition>): Boolean =
        profileAxis(box, transit) != null || compactAxis(box, transit) != null

    private fun compactDimensions(box: CivBox): Boolean =
        box.max.x.toLong() - box.min.x == 2L && box.max.z.toLong() - box.min.z == 2L &&
            box.max.y.toLong() - box.min.y in 5L..6L

    private fun compactSupports(box: CivBox, alongX: Boolean): Set<Pair<Int, Int>> =
        listOf(0, 2).mapTo(hashSetOf()) { across -> point(box, alongX, across, 1, 1).let { it.x to it.z } }

    private fun compactAxis(box: CivBox, transit: List<CivPosition>): Boolean? =
        if (!compactDimensions(box)) null else listOf(true, false).firstOrNull { alongX ->
            transit.size == 7 && transit.map { it.x to it.z }.toSet() ==
                (box.min.x..box.max.x).flatMap { x -> (box.min.z..box.max.z).map { z -> x to z } }.toSet() -
                compactSupports(box, alongX) && transit.all { it.y in box.min.y + 1..box.max.y - 4 }
        }

    fun profileAxis(box: CivBox, transit: List<CivPosition>): Boolean? = listOf(true, false).firstOrNull { alongX ->
        dimensions(box, alongX) && transit.size == width(box, alongX) * 5 - 6 &&
            transit.map { it.x to it.z }.toSet() == fullTransit(box).map { it.x to it.z }.toSet() - supports(box, alongX) &&
            transit.all { it.y in box.min.y + 1..box.max.y - 4 }
    }

    fun fullTransit(box: CivBox): List<CivPosition> = buildList {
        requireCivs(dimensions(box, true) || dimensions(box, false), "gate-refit-unsupported")
        for (x in box.min.x..box.max.x) for (z in box.min.z..box.max.z) add(CivPosition(x, box.min.y + 1, z))
    }

    private fun width(box: CivBox, alongX: Boolean): Int =
        if (alongX) box.max.x - box.min.x + 1 else box.max.z - box.min.z + 1

    private fun dimensions(box: CivBox, alongX: Boolean): Boolean =
        (if (alongX) box.max.x.toLong() - box.min.x + 1 else box.max.z.toLong() - box.min.z + 1) in 5L..6L &&
        (if (alongX) box.max.z.toLong() - box.min.z else box.max.x.toLong() - box.min.x) == 4L &&
        box.max.y.toLong() - box.min.y in 5L..6L

    private fun point(box: CivBox, alongX: Boolean, across: Int, y: Int, depth: Int): CivPosition =
        if (alongX) CivPosition(box.min.x + across, box.min.y + y, box.min.z + depth)
        else CivPosition(box.min.x + depth, box.min.y + y, box.min.z + across)

    private fun supports(box: CivBox, alongX: Boolean): Set<Pair<Int, Int>> = buildSet {
        for (across in listOf(0, width(box, alongX) - 1)) for (depth in 1..3)
            point(box, alongX, across, 1, depth).let { add(it.x to it.z) }
    }

    /** The dry-perimeter planner also emits compact 3x3 openings. Existing-gate maintenance stays 5/6x5. */
    fun generatedShape(piece: CivSettlementPiece, alongX: Boolean, witnesses: Collection<CivPosition>): CivSettlementPiece {
        val box = piece.bounds
        if (!compactDimensions(box)) return shape(piece, alongX, witnesses)
        val columns = (box.min.x..box.max.x).flatMap { x -> (box.min.z..box.max.z).map { z -> x to z } }.toSet()
        requireCivs(piece.type == BuildingType.GATE && piece.transit.size == 9 &&
            piece.transit.map { it.x to it.z }.toSet() == columns &&
            piece.transit.minOf { it.y } == box.min.y + 1 && piece.transit.maxOf { it.y } == box.max.y - 4,
            "gate-refit-unsupported")
        val posts = compactSupports(box, alongX)
        requireCivs(witnesses.all { it in piece.transit && (it.x to it.z) !in posts }, "gate-refit-route-conflict")
        val cells = piece.blocks.associate { it.position to it.after }.toMutableMap()
        requireCivs(cells.size == piece.blocks.size, "invalid-plan")
        for (feet in piece.transit) if (cells[feet] == leaf(alongX)) cells[feet] = "minecraft:air"
        for (feet in piece.transit.filter { (it.x to it.z) in posts }) {
            cells[feet] = "minecraft:stone_bricks"
            for (y in feet.y + 1 until box.max.y - 1) cells[feet.copy(y = y)] = "minecraft:dark_oak_log[axis=y]"
        }
        for (across in 0..2) for (depth in 0..2) {
            cells[point(box, alongX, across, box.max.y - box.min.y - 1, depth)] =
                "minecraft:dark_oak_log[axis=${if (alongX) "x" else "z"}]"
            val facing = if (alongX) { if (depth == 0) "south" else "north" } else { if (depth == 0) "east" else "west" }
            cells[point(box, alongX, across, box.max.y - box.min.y, depth)] = if (depth == 1)
                "minecraft:dark_oak_slab[type=top,waterlogged=false]" else
                "minecraft:dark_oak_stairs[facing=$facing,half=bottom,shape=straight,waterlogged=false]"
        }
        return piece.copy(blocks = cells.entries.sortedWith(compareBy({ it.key.y }, { it.key.z }, { it.key.x }))
            .map { CivSettlementBlock(it.key, it.value) }, transit = piece.transit.filterNot { (it.x to it.z) in posts })
    }

    /** Future construction and maintenance use these same blocks and walking columns. */
    fun shape(piece: CivSettlementPiece, alongX: Boolean, witnesses: Collection<CivPosition>, profileVersion: Int = 3): CivSettlementPiece {
        val box = piece.bounds
        requireCivs(profileVersion in 2..3 && piece.type == BuildingType.GATE && dimensions(box, alongX) &&
            piece.transit.size == width(box, alongX) * 5 &&
            piece.transit.map { it.x to it.z }.toSet() == fullTransit(box).map { it.x to it.z }.toSet() &&
            piece.transit.minOf { it.y } == box.min.y + 1 && piece.transit.maxOf { it.y } == box.max.y - 4,
            "gate-refit-unsupported")
        val posts = supports(box, alongX)
        requireCivs(witnesses.none { (it.x to it.z) in posts }, "gate-refit-route-conflict")
        requireCivs(witnesses.all { it in piece.transit }, "gate-refit-route-conflict")
        val cells = piece.blocks.associate { it.position to it.after }.toMutableMap()
        requireCivs(cells.size == piece.blocks.size, "invalid-plan")
        // Separate open fence-gate leaves have no hinges on this wide arch. V2 is frozen
        // for old receipt replay; V3 keeps the opening clear instead of leaving fence stubs.
        if (profileVersion >= 3) for (feet in piece.transit) if (cells[feet] == leaf(alongX)) cells[feet] = "minecraft:air"
        for (feet in piece.transit.filter { (it.x to it.z) in posts }) {
            cells[feet] = "minecraft:stone_bricks"
            for (y in feet.y + 1 until box.max.y - 1) cells[feet.copy(y = y)] = "minecraft:dark_oak_log[axis=y]"
        }
        for (across in 0 until width(box, alongX)) for (depth in 1..3) {
            cells[point(box, alongX, across, box.max.y - box.min.y - 1, depth)] = "minecraft:dark_oak_log[axis=${if (alongX) "x" else "z"}]"
            val roof = if (depth == 2) "minecraft:dark_oak_slab[type=top,waterlogged=false]" else {
                val facing = if (alongX) { if (depth == 1) "south" else "north" } else { if (depth == 1) "east" else "west" }
                "minecraft:dark_oak_stairs[facing=$facing,half=bottom,shape=straight,waterlogged=false]"
            }
            cells[point(box, alongX, across, box.max.y - box.min.y, depth)] = roof
        }
        return piece.copy(blocks = cells.entries.sortedWith(compareBy({ it.key.y }, { it.key.z }, { it.key.x }))
            .map { CivSettlementBlock(it.key, it.value) }, transit = piece.transit.filterNot { (it.x to it.z) in posts })
    }

    private fun leaf(alongX: Boolean) =
        "minecraft:dark_oak_fence_gate[facing=${if (alongX) "north" else "east"},in_wall=false,open=true,powered=false]"

    private fun legacyCells(box: CivBox, alongX: Boolean, transit: List<CivPosition>, actual: Map<CivPosition, String>) = buildMap {
        for (feet in transit) {
            val floor = feet.copy(y = feet.y - 1)
            if (floor in actual) put(floor, "minecraft:cobblestone")
            val center = if (alongX) feet.z == box.min.z + 2 else feet.x == box.min.x + 2
            for (dy in 0..3) put(feet.copy(y = feet.y + dy),
                if (dy == 0 && center && actual[feet] == leaf(alongX)) leaf(alongX) else "minecraft:air")
        }
        for (across in 0 until width(box, alongX)) put(point(box, alongX, across, box.max.y - box.min.y, 2), "minecraft:stone_bricks")
    }

    fun proposal(city: Settlement, source: CivPlacementPlan, witnesses: List<CivPosition>, captureId: UUID,
        profileVersion: Int = 3): CivPlacementPlan {
        val old = city.building(source.building.id) ?: throw CivsRejection("building-not-found")
        val oldVersion = this.profileVersion(old)
        requireCivs(city.style == "caledonia" && city.autonomy != null && city.pendingDeletion == null &&
            old.type == BuildingType.GATE && old.phase == BuildingPhase.ACTIVE && old.level == 1 && old.rotation == 0 &&
            (old.schematicId == LEGACY_PREFIX + old.id || oldVersion in 2..3) &&
            profileVersion in 2..3 && (oldVersion == 0 || profileVersion == 3) &&
            !source.adminPlacement && source.id == retainedPlanId(old) && source.cityId == city.id && source.worldId == city.worldId &&
            source.building.copy(phase = BuildingPhase.ACTIVE) == old && captureId != source.id &&
            old.anchor == old.bounds.min, "gate-refit-unsupported")
        val box = old.bounds
        requireCivs((dimensions(box, true) || dimensions(box, false)) &&
            source.blocks.map { it.position }.distinct().size == source.blocks.size, "gate-refit-unsupported")
        val actual = source.blocks.associate { it.position to it.after }
        requireCivs(witnesses.size <= 256 && witnesses.distinct().size == witnesses.size && witnesses.all {
            it.x in box.min.x..box.max.x && it.z in box.min.z..box.max.z
        }, "invalid-plan")
        if (oldVersion != 0) {
            val axis = profileAxis(box, old.transit.orEmpty()) ?: throw CivsRejection("gate-refit-unsupported")
            // Recover only the six omitted support feet from their exact stone bases. This
            // proves the whole old profile without changing a paid floor or inventing height.
            val full = fullTransit(box).map { column ->
                old.transit.orEmpty().singleOrNull { it.x == column.x && it.z == column.z } ?: actual.keys.singleOrNull {
                    it.x == column.x && it.z == column.z && it.y in box.min.y + 1..box.max.y - 4 && actual[it] == "minecraft:stone_bricks"
                } ?: throw CivsRejection("gate-refit-unsupported")
            }
            val legacy = legacyCells(box, axis, full, actual)
            val expected = shape(CivSettlementPiece(old.id, old.type, box, old.entrance,
                legacy.map { CivSettlementBlock(it.key, it.value) }, full), axis, emptyList(), oldVersion)
            requireCivs(expected.blocks.associate { it.position to it.after } == actual, "gate-refit-unsupported")
            requireCivs(witnesses.all { it in old.transit.orEmpty() }, "gate-refit-route-conflict")
            val target = old.copy(schematicId = PREFIX + captureId, phase = BuildingPhase.CONSTRUCTING)
            return source.copy(id = captureId, building = target, blocks = source.blocks.map { cell ->
                cell.copy(before = cell.after, after = if (cell.position in old.transit.orEmpty() && cell.after == leaf(axis))
                    "minecraft:air" else cell.after)
            }, capturedSha256 = null)
        }
        val axes = listOf(true, false).filter { dimensions(box, it) }.filter { alongX ->
            actual == legacyCells(box, alongX, old.transit.orEmpty(), actual)
        }
        requireCivs(axes.size == 1, "gate-refit-unsupported")
        val piece = shape(CivSettlementPiece(old.id, old.type, old.bounds, old.entrance,
            source.blocks.map { CivSettlementBlock(it.position, it.after) }, old.transit.orEmpty(),
            source.terrain?.waterFoundations.orEmpty()), axes.single(), witnesses, profileVersion)
        val target = old.copy(schematicId = (if (profileVersion == 2) POSTED_PREFIX else PREFIX) + captureId,
            transit = piece.transit.toList(), phase = BuildingPhase.CONSTRUCTING)
        return source.copy(id = captureId, building = target, blocks = piece.blocks.map {
            CivPlanBlock(it.position, actual[it.position] ?: "minecraft:air", it.after)
        }, capturedSha256 = null)
    }

    fun proof(source: CivPlacementPlan, replacement: CivPlacementPlan, witnesses: List<CivPosition>,
        routeOverrides: Map<String, CivRoadRouteOverride>? = null): String {
        val value = buildString {
            append(source.cityId); append(source.worldId); append(source.id); append(source.capturedSha256)
            append(source.building)
            replacement.blocks.forEach { append(it.position); append(it.before); append(it.after) }
            witnesses.sortedWith(compareBy({ it.x }, { it.y }, { it.z })).forEach { append(it) }
            routeOverrides.orEmpty().toSortedMap().forEach { (id, route) ->
                append(id); append(route.originalRoute); append(route.route)
                // Each preview creates fresh immutable capture bytes; that random ID is not design identity.
                route.gateDetours.orEmpty().forEach { append(it.gateId); append(it.before); append(it.after) }
            }
        }
        return sha256(value.toByteArray(Charsets.UTF_8))
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

private fun requireCivs(condition: Boolean, reason: String) { if (!condition) throw CivsRejection(reason) }

/** Durable native maintenance intent; it changes one gate's authored shape, never stock or worker credit. */
data class CivGateRefitReceipt(
    val beforeBuilding: CivBuilding,
    val source: CivPlacementPlan,
    val replacement: CivPlacementPlan,
    val witnesses: List<CivPosition>,
    val routeOverrides: Map<String, CivRoadRouteOverride>? = null,
    val previousRouteOverrides: Map<String, CivRoadRouteOverride>? = null,
) {
    fun before(after: Settlement): Settlement = after.copy(buildings = after.buildings.map {
        if (it.id == beforeBuilding.id) beforeBuilding else it
    }, roadRouteOverrides = if (routeOverrides == null) after.roadRouteOverrides else
        (after.roadRouteOverrides.orEmpty() - routeOverrides.keys + previousRouteOverrides.orEmpty()).takeIf { it.isNotEmpty() },
        revision = after.revision - 1)

    fun after(before: Settlement): Settlement = before.copy(buildings = before.buildings.map {
        if (it.id == beforeBuilding.id) replacement.building.copy(phase = BuildingPhase.ACTIVE) else it
    }, roadRouteOverrides = if (routeOverrides == null) before.roadRouteOverrides else before.roadRouteOverrides.orEmpty() + routeOverrides,
        revision = Math.addExact(before.revision, 1))

    fun validated(after: Settlement) {
        require(beforeBuilding != null && source != null && replacement != null && witnesses != null)
        val before = before(after)
        CivsRules.validate(before)
        require(after(before) == after)
        require(beforeBuilding == source.building.copy(phase = BuildingPhase.ACTIVE))
        require(after.building(beforeBuilding.id) == replacement.building.copy(phase = BuildingPhase.ACTIVE))
        require(replacement.capturedSha256?.matches(Regex("[0-9a-f]{64}")) == true &&
            source.capturedSha256?.matches(Regex("[0-9a-f]{64}")) == true)
        require(CivsGateRefit.proposal(before, source, witnesses, replacement.id,
            CivsGateRefit.profileVersion(replacement.building)) == replacement.copy(capturedSha256 = null))
        val rerouted = CivsGateRoutes.refit(before, beforeBuilding, replacement.building, source.terrain?.route.orEmpty())
        require(routeOverrides == rerouted?.let { mapOf(beforeBuilding.id.toString() to it) })
        require(previousRouteOverrides == if (rerouted == null) null else
            before.roadRouteOverrides?.filterKeys { it == beforeBuilding.id.toString() }?.takeIf { it.isNotEmpty() })
        require(changes().size in 1..128)
    }

    fun changes(): List<CivPlanBlock> = replacement.blocks.filter { it.before != it.after }

    fun detached(): CivGateRefitReceipt {
        fun plan(p: CivPlacementPlan) = p.copy(building = p.building.copy(transit = p.building.transit?.toList()),
            blocks = p.blocks.toList(), terrain = p.terrain?.let { it.copy(blocks = it.blocks.toList(), route = it.route.toList(),
                waterFoundations = it.waterFoundations?.toList()) })
        return copy(beforeBuilding = beforeBuilding.copy(transit = beforeBuilding.transit?.toList()),
            source = plan(source), replacement = plan(replacement), witnesses = witnesses.toList(),
            routeOverrides = routeOverrides?.mapValues { it.value.detached() },
            previousRouteOverrides = previousRouteOverrides?.mapValues { it.value.detached() })
    }
}
