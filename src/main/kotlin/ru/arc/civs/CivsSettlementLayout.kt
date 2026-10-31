package ru.arc.civs

import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.abs

/** Detached, stable hall-oriented composition. Heights and native safety remain Terraform's job. */
internal data class CivSettlementFrame(
    val origin: CivPosition,
    val rotation: Int,
    val halfWidth: Int,
    val core: CivBox,
    val court: CivBox,
    val gates: List<CivSettlementGate>,
    val wallObstacles: List<CivBox>,
    val streets: List<CivSettlementStreet>,
    val fortified: Boolean = false,
)

internal data class CivSettlementGate(val side: Int, val center: CivPosition, val passage: CivBox)
internal data class CivSettlementStreet(val from: CivPosition, val to: CivPosition)
internal data class CivSettlementBlock(val position: CivPosition, val after: String)

/** A short normal building order, never a second placement engine or a completed world mutation. */
internal data class CivSettlementPiece(
    val id: UUID,
    val type: BuildingType,
    val bounds: CivBox,
    val entrance: CivPosition,
    val blocks: List<CivSettlementBlock>,
    val transit: List<CivPosition> = emptyList(),
    val waterFoundations: List<CivWaterFoundation> = emptyList(),
)

internal data class CivSettlementInfrastructure(
    val pieces: List<CivSettlementPiece>,
    val rejected: Map<String, Int>,
    val perimeterClosed: Boolean,
    val blockers: List<CivSettlementBlocker> = emptyList(),
    val rejectionSamples: Map<String, List<CivPosition>> = emptyMap(),
)

internal data class CivSettlementBlocker(val buildingId: UUID, val bounds: CivBox, val at: CivPosition)

/**
 * One finite layout algorithm for runtime placement and offline exports. All inputs are detached.
 * Existing buildings and entrances are pinned; no method moves, removes or grants world blocks.
 */
internal object CivsSettlementLayout {
    const val VERSION = 1
    const val MAXIMUM_HALF_WIDTH = 72
    const val MAXIMUM_PIECES = CivsRules.MAX_INFRASTRUCTURE
    private const val WALL_HALF_WIDTH = 1
    private const val GATE_HALF_WIDTH = 2
    private const val MAXIMUM_PIECE_LENGTH = 24
    private const val MAXIMUM_ROAD_COLUMNS = 96
    val exteriorTypes = setOf(BuildingType.FORESTER, BuildingType.QUARRY, BuildingType.FARM)

    /** Before the first infrastructure receipt, prefer the largest clear bounded ring. */
    fun frame(city: Settlement, halfWidth: Int? = city.autonomy?.layoutHalfWidth): CivSettlementFrame {
        val hall = city.buildings.first { it.type == BuildingType.TOWN_HALL }
        val turns = hall.rotation / 90
        fun local(p: CivPosition) = rotate(CivPosition(p.x - city.center.x, p.y, p.z - city.center.z), -turns)
        fun world(x: Int, z: Int): CivPosition = rotate(CivPosition(x, city.center.y, z), turns).let {
            CivPosition(city.center.x + it.x, it.y, city.center.z + it.z)
        }
        fun box(x0: Int, z0: Int, x1: Int, z1: Int): CivBox = box(listOf(world(x0, z0), world(x1, z1)))
        require(halfWidth == null || halfWidth in 24..MAXIMUM_HALF_WIDTH)
        val adopted = CivsSettlementPlans.current(city)
        // Pre-frame-journal infrastructure used the former 56-block limit. New designs persist their actual width.
        val maximum = if (city.buildings.any { it.type.isInfrastructure }) 56 else MAXIMUM_HALF_WIDTH
        val half = halfWidth ?: adopted?.halfWidth ?: minOf(maximum, city.radius - 24).coerceAtLeast(24)
        val entry = local(hall.entrance)
        val plazaX = entry.x.coerceIn(-half + 12, half - 12)
        val plazaZ = (entry.z + 8).coerceIn(-half + 12, half - 12)
        val hallBack = corners(hall.bounds).minOf { local(it).z }
        val backStreet = (hallBack - 5).coerceIn(-half + 16, plazaZ - 12)
        val sideStreet = half - 8
        val northernFrontage = half > 56 && adopted == null
        val gates = listOf(
            CivSettlementGate(1, world(half, plazaZ), box(half - 2, plazaZ - GATE_HALF_WIDTH, half + 2, plazaZ + GATE_HALF_WIDTH)),
            CivSettlementGate(2, world(plazaX, half), box(plazaX - GATE_HALF_WIDTH, half - 2, plazaX + GATE_HALF_WIDTH, half + 2)),
            CivSettlementGate(3, world(-half, plazaZ), box(-half - 2, plazaZ - GATE_HALF_WIDTH, -half + 2, plazaZ + GATE_HALF_WIDTH)),
        ) + if (northernFrontage) listOf(CivSettlementGate(0, world(plazaX, -half),
            box(plazaX - GATE_HALF_WIDTH, -half - 2, plazaX + GATE_HALF_WIDTH, -half + 2))) else emptyList()
        val walls = mutableListOf<CivBox>()
        fun side(horizontal: Boolean, fixed: Int, gap: Int?) {
            // Horizontal strips own the corners; vertical strips end before them.
            val low = if (horizontal) -half - 1 else -half + 2
            val high = if (horizontal) half + 1 else half - 2
            val ranges = if (gap == null) listOf(low..high) else
                listOf(low until gap - GATE_HALF_WIDTH, gap + GATE_HALF_WIDTH + 1..high)
            for (range in ranges) {
                if (horizontal) walls += box(range.first, fixed - WALL_HALF_WIDTH, range.last, fixed + WALL_HALF_WIDTH)
                else walls += box(fixed - WALL_HALF_WIDTH, range.first, fixed + WALL_HALF_WIDTH, range.last)
            }
        }
        side(true, -half, plazaX.takeIf { northernFrontage })
        side(true, half, plazaX)
        side(false, -half, plazaZ)
        side(false, half, plazaZ)
        val streets = listOf(
            CivSettlementStreet(world(-half, plazaZ), world(half, plazaZ)),
            CivSettlementStreet(world(plazaX, plazaZ), world(plazaX, half)),
            CivSettlementStreet(world(-sideStreet, backStreet), world(sideStreet, backStreet)),
            CivSettlementStreet(world(-sideStreet, backStreet), world(-sideStreet, plazaZ)),
            CivSettlementStreet(world(sideStreet, backStreet), world(sideStreet, plazaZ)),
        ) + if (northernFrontage) listOf(CivSettlementStreet(world(plazaX, backStreet), world(plazaX, -half))) else emptyList()
        val result = CivSettlementFrame(city.center, hall.rotation, half, box(-half, -half, half, half),
            box(plazaX - 8, entry.z + 1, plazaX + 8, plazaZ + 5), gates, walls, streets,
            adopted?.plots?.any { it.fortification != null } == true)
        adopted?.takeIf { it.halfWidth == half }?.let { plan ->
            val sides = plan.gateSides ?: listOf(1, 2, 3)
            return result.copy(wallObstacles = plan.walls, gates = plan.gates.mapIndexed { i, passage ->
                CivSettlementGate(sides[i], CivPosition((passage.min.x + passage.max.x) / 2, city.center.y,
                    (passage.min.z + passage.max.z) / 2), passage)
            })
        }
        if (halfWidth != null || city.buildings.any { it.type.isInfrastructure }) return result
        val occupied = city.buildings.map { CivsAutonomousGeometry.futureBounds(city, it) }
        val approaches = city.buildings.flatMap(CivsAutonomousGeometry::approach)
        fun clear(frame: CivSettlementFrame): Boolean = (frame.wallObstacles + frame.gates.map { it.passage }).all { edge ->
            occupied.none { CivsAutonomousGeometry.overlaps(edge, it) } && approaches.none { contains(edge, it) }
        }
        if (clear(result)) return result
        // A legacy town may have no safe ring in the supported territory. Retain the
        // preferred frame and report its exact blockers; never cut a house or a field.
        return (half - 1 downTo 24).asSequence().map { frame(city, it) }.firstOrNull(::clear) ?: result
    }

    fun candidates(city: Settlement, current: CivBuilding, future: CivBuilding,
        access: CivAutonomousAccess = CivAutonomousAccess(), frame: CivSettlementFrame = frame(city),
        eligible: (CivBuilding) -> Boolean = { true }): List<CivsAutonomousCandidate> {
        val exterior = current.type in exteriorTypes
        val choices = mutableListOf<Pair<CivPosition, Int>>()
        if (exterior) {
            val offsets = if (frame.halfWidth > 56) listOf(0) + (1..5).flatMap { step ->
                val span = (frame.halfWidth - 12) * step / 5
                listOf(-span, span)
            } else listOf(0, -8, 8, -16, 16, -24, 24, -32, 32, -36, 36)
            val preferred = when (current.type) { BuildingType.FORESTER -> 3; BuildingType.QUARRY -> 1; else -> 2 }
            for (gate in frame.gates.sortedBy { if (it.side == preferred) 0 else 1 }) {
                val outward = rotate(CivPosition(0, 0, -1), gate.side)
                val sideways = CivPosition(-outward.z, 0, outward.x)
                val worldSideways = rotate(sideways, frame.rotation / 90)
                val depot = city.buildings.filter(CivsRules::isDepot).minByOrNull { manhattan(it.entrance, gate.center) }
                val alignment = depot?.entrance?.let { (it.x - gate.center.x) * worldSideways.x +
                    (it.z - gate.center.z) * worldSideways.z } ?: 0
                for (distance in listOf(8, 14, 20)) for (offset in offsets) {
                    val local = CivPosition(outward.x * distance + sideways.x * (offset + alignment), 0,
                        outward.z * distance + sideways.z * (offset + alignment))
                    val delta = rotate(local, frame.rotation / 90)
                    choices += gate.center.copy(x = gate.center.x + delta.x, z = gate.center.z + delta.z) to
                        Math.floorMod(gate.side + frame.rotation / 90, 4)
                }
            }
        } else {
            // The accepted street owns frontage; the ideal sketch is only a fallback for a new quarter.
            streetFrontages(access, frame).forEach { (p, turns) ->
                val front = rotate(CivPosition(0, 0, 1), turns)
                choices += p.copy(x = p.x - front.x * 4, z = p.z - front.z * 4) to turns
            }
            for (street in frame.streets) {
                val points = streetPoints(street)
                val dx = (street.to.x - street.from.x).compareTo(0)
                val dz = (street.to.z - street.from.z).compareTo(0)
                for (index in 6 until points.size - 5 step 4) for (side in listOf(-1, 1)) {
                    val p = points[index]
                    val frontX = -dz * side
                    val frontZ = dx * side
                    val turns = when { frontX == -1 -> 1; frontX == 1 -> 3; frontZ == -1 -> 2; else -> 0 }
                    choices += p.copy(x = p.x - frontX * 4, z = p.z - frontZ * 4) to turns
                }
            }
        }
        return selectCandidates(city, current, future, access, frame, choices, reserveUnbuiltStreets = true, eligible)
    }

    /** Interior straight street segments only; exterior resource approaches never become housing frontage. */
    private fun streetFrontages(access: CivAutonomousAccess, frame: CivSettlementFrame): List<Pair<CivPosition, Int>> {
        val points = (access.routes.values.flatten() + access.infrastructure.filter { it.type == BuildingType.ROAD }
            .flatMap { it.transit }).distinctBy(::key).associateBy(::key)
        return points.values.filter { insideCore(frame, it, 6) }.flatMap { p ->
            buildList {
                if (key(p.x - 1, p.z) in points && key(p.x + 1, p.z) in points) {
                    add(p to 0); add(p to 2)
                }
                if (key(p.x, p.z - 1) in points && key(p.x, p.z + 1) in points) {
                    add(p to 1); add(p to 3)
                }
            }
        }
    }

    /**
     * A mature town may have no safe frontage on the ideal street sketch. Try finite inner yards
     * only after those plots fail. Existing roads, approaches, buildings and the pinned perimeter
     * remain protected; the normal graded route connects an accepted yard to the actual settlement.
     */
    fun infill(city: Settlement, current: CivBuilding, future: CivBuilding,
        access: CivAutonomousAccess = CivAutonomousAccess(), frame: CivSettlementFrame = frame(city),
        eligible: (CivBuilding) -> Boolean = { true }): List<CivsAutonomousCandidate> {
        if (current.type in exteriorTypes || current.type.isInfrastructure)
            return emptyList()
        // At the largest frame: 69 * 69 * 4 detached placements, with at most 64 results.
        val choices = buildList {
            for (x in frame.core.min.x + 4..frame.core.max.x - 4 step 2)
                for (z in frame.core.min.z + 4..frame.core.max.z - 4 step 2)
                    for (turns in 0..3) add(CivPosition(x, city.center.y, z) to turns)
        }
        return selectCandidates(city, current, future, access, frame, choices, reserveUnbuiltStreets = false, eligible)
    }

    private fun selectCandidates(city: Settlement, current: CivBuilding, future: CivBuilding,
        access: CivAutonomousAccess, frame: CivSettlementFrame, choices: List<Pair<CivPosition, Int>>,
        reserveUnbuiltStreets: Boolean, eligible: (CivBuilding) -> Boolean): List<CivsAutonomousCandidate> {
        val envelope = (listOf(current.bounds.min, current.bounds.max, future.bounds.min, future.bounds.max) +
            CivsWorksiteLayouts.field(current) + CivsWorksiteLayouts.irrigation(current) +
            CivsWorksiteLayouts.field(future) + CivsWorksiteLayouts.irrigation(future)).let(::box)
        val occupied = city.buildings.filter { it.id != current.id }.map { CivsAutonomousGeometry.futureBounds(city, it) } +
            listOfNotNull(city.construction?.plannedBuilding?.takeIf { it.id != current.id }?.bounds) + access.reservedFortificationPorts
        val protected = (city.buildings.filter { it.id != current.id }.flatMap(CivsAutonomousGeometry::approach) +
            access.standing(city, current.id).values).mapTo(hashSetOf(), ::key)
        val frontages = streetFrontages(access, frame)
        val streetCells = access.routes.values.flatMap { CivsAutonomousTerraform.roadProfile(it).values }.mapTo(hashSetOf(), ::key)
        if (reserveUnbuiltStreets && frontages.isEmpty())
            frame.streets.flatMap { CivsAutonomousTerraform.roadProfile(streetPoints(it)).values }.mapTo(streetCells, ::key)
        val frontageKeys = frontages.groupBy({ it.second }, { key(it.first) }).mapValues { it.value.toHashSet() }
        fun streetDistance(b: CivBuilding): Int? {
            val turns = b.rotation / 90
            val front = rotate(CivPosition(0, 0, 1), turns)
            return (2..6).firstOrNull { distance ->
                key(b.entrance.x + front.x * distance, b.entrance.z + front.z * distance) in frontageKeys[turns].orEmpty()
            }
        }
        val exterior = current.type in exteriorTypes
        // Small raw worksites use an available flank while the new quarter still needs wide farm parcels.
        // This only ranks the existing finite candidates; terrain, spacing and depot reach still admit them.
        val preserveWideBay = current.type in setOf(BuildingType.FORESTER, BuildingType.QUARRY) && frame.halfWidth > 56 &&
            CivsSettlementPlans.current(city) == null && city.buildings.count { it.type == BuildingType.FARM } <
            CivsAutonomy.completeProfile.count { it == BuildingType.FARM }
        fun wideBayPenalty(b: CivBuilding): Int {
            if (!preserveWideBay) return 0
            val gate = frame.gates.minBy { manhattan(it.center, b.entrance) }
            val outward = rotate(CivPosition(0, 0, -1), gate.side + frame.rotation / 90)
            val center = city.buildings.filter(CivsRules::isDepot).minByOrNull { manhattan(it.entrance, gate.center) }
                ?.entrance ?: gate.center
            val lateral = abs((b.entrance.x - center.x) * -outward.z + (b.entrance.z - center.z) * outward.x)
            return if (lateral >= frame.halfWidth / 2) 0 else 1
        }
        fun placed(entry: CivPosition, turns: Int): CivBuilding {
            val entryOffset = rotate(current.entrance, turns)
            val anchor = CivPosition(entry.x - entryOffset.x, city.center.y, entry.z - entryOffset.z)
            fun point(p: CivPosition) = rotate(p, turns).let { CivPosition(anchor.x + it.x, anchor.y + it.y, anchor.z + it.z) }
            return current.copy(anchor = anchor, rotation = turns * 90, bounds = box(corners(envelope).map(::point)),
                entrance = point(current.entrance), control = current.control?.let(::point))
        }
        fun nearGate(b: CivBuilding) = frame.gates.minOf { manhattan(it.center, b.entrance) }
        val depots = city.buildings.filter { it.type in setOf(BuildingType.TOWN_HALL, BuildingType.WAREHOUSE) }
        val remoteResource = if (current.type == BuildingType.WAREHOUSE && city.buildings.any { it.type == BuildingType.WAREHOUSE })
            city.buildings.filter { it.type in exteriorTypes }.maxByOrNull { resource ->
                depots.minOf { manhattan(it.entrance, resource.entrance) }
            } else null
        val operating = if (current.type in setOf(BuildingType.FORESTER, BuildingType.QUARRY)) operatingBounds(future) else null
        val operatingPeers = if (operating == null) emptyList() else city.buildings.filter { it.type == current.type && it.id != current.id }
            .mapNotNull { b -> operatingBounds(b)?.let { local -> box(corners(local).map { CivsWorksiteLayouts.point(b, it) }) } }
        fun operatingConflicts(b: CivBuilding): Int {
            if (operating == null || operatingPeers.isEmpty()) return 0
            val bounds = box(corners(operating).map { CivsWorksiteLayouts.point(b, it) })
            return operatingPeers.count { CivsAutonomousGeometry.overlaps(bounds, it, 2) }
        }
        fun score(b: CivBuilding): Int = when {
            exterior -> {
                val side = when (current.type) { BuildingType.FORESTER -> 3; BuildingType.QUARRY -> 1; else -> 2 }
                frame.gates.firstOrNull { it.side == side }?.let { manhattan(it.center, b.entrance) } ?: nearGate(b)
            }
            current.type == BuildingType.GUARD_TOWER -> nearGate(b) * 4
            current.type == BuildingType.WAREHOUSE -> remoteResource?.let { manhattan(it.entrance, b.entrance) }
                ?: (nearGate(b) + manhattan(city.buildings.first().entrance, b.entrance))
            else -> manhattan(center(frame.court), b.entrance)
        }
        return choices.map { (entry, turns) -> placed(entry, turns) }.distinctBy { it.anchor to it.rotation }.filter { b ->
            val bounds = b.bounds
            city.contains(bounds.min.x, bounds.min.z) && city.contains(bounds.max.x, bounds.max.z) &&
                CivsAutonomousGeometry.approach(b).all { city.contains(it.x, it.z) } &&
                occupied.none { CivsAutonomousGeometry.overlaps(bounds, it, 2) } &&
                (bounds.min.x..bounds.max.x).none { x -> (bounds.min.z..bounds.max.z).any { z -> key(x, z) in protected } } &&
                CivsAutonomousGeometry.approach(b).none { p -> occupied.any { contains(it, p) } } &&
                CivsAutonomousGeometry.approach(b).none { p -> frame.wallObstacles.any { contains(it, p) } } &&
                frame.wallObstacles.none { CivsAutonomousGeometry.overlaps(bounds, it, 2) } &&
                (if (exterior) !CivsAutonomousGeometry.overlaps(bounds, frame.core, 3) else
                    corners(bounds).all { insideCore(frame, it, 4) } &&
                        !CivsAutonomousGeometry.overlaps(bounds, frame.court) &&
                        (bounds.min.x..bounds.max.x).none { x -> (bounds.min.z..bounds.max.z).any { z -> key(x, z) in streetCells } }) &&
                // Frontage is preferred by ranking. Infill may extend a short
                // connected lane when every existing street plot is unavailable.
                CivsRules.workRouteWithinReach(city, b.entrance)
        }.sortedWith(compareBy<CivBuilding> { if (exterior || frontages.isEmpty()) 0 else streetDistance(it) ?: 100 }
            .thenBy(::operatingConflicts).thenBy(::wideBayPenalty).thenBy(::score)
            .thenBy { it.entrance.x }.thenBy { it.entrance.z }.thenBy { it.rotation })
            .asSequence().filter(eligible).take(64).map { CivsAutonomousCandidate(it,
                !exterior && streetDistance(it) != null, operatingConflicts(it) * 2 + wideBayPenalty(it)) }.toList()
    }

    /** A generated route may enter the reserved wall only through one of its explicit gates. */
    fun acceptsRoute(frame: CivSettlementFrame, route: List<CivPosition>): Boolean = route.all { p ->
        frame.wallObstacles.none { contains(it, p) }
    }

    /** Reuse the existing captured plan journal; this is a detached view, not another registry. */
    fun accepted(city: Settlement, plans: List<CivPlacementPlan>): List<CivSettlementPiece> = plans.mapNotNull { plan ->
        val building = city.building(plan.building.id) ?: return@mapNotNull null
        if (!building.type.isInfrastructure || plan.cityId != city.id || plan.worldId != city.worldId ||
            plan.building.type != building.type || plan.building.anchor != building.anchor ||
            plan.building.bounds != building.bounds || plan.building.rotation != building.rotation) return@mapNotNull null
        CivSettlementPiece(building.id, building.type, building.bounds, building.entrance,
            plan.blocks.map { CivSettlementBlock(it.position, it.after) }, building.transit.orEmpty(),
            plan.terrain?.waterFoundations.orEmpty())
    }.distinctBy { it.id }

    /**
     * Exact after-states for normal captured construction orders. Native before-images, ownership,
     * exact materials and recovery are deliberately supplied by the existing construction owner.
     */
    fun infrastructure(city: Settlement, frame: CivSettlementFrame, surface: CivTerrainSurface,
        routes: List<List<CivPosition>>, accepted: List<CivSettlementPiece> = emptyList(),
        pavingRoutes: List<List<CivPosition>> = routes,
        retainedRoutes: Collection<List<CivPosition>> = emptyList(),
        acceptedFortifications: Set<UUID> = emptySet(),
        forecastFortifications: Set<UUID> = emptySet()): CivSettlementInfrastructure {
        val rejected = sortedMapOf<String, Int>()
        val samples = linkedMapOf<String, MutableList<CivPosition>>()
        fun reject(reason: String, at: CivPosition? = null) {
            rejected.merge(reason, 1, Int::plus)
            if (at != null) samples.getOrPut(reason) { mutableListOf() }.let { if (it.size < 4) it += at }
        }
        val fortifications = CivsSettlementPlans.current(city)?.plots.orEmpty().filter { it.fortification != null }
        if (frame.fortified && (fortifications.isEmpty() || fortifications.any { plot ->
                plot.building.id !in forecastFortifications && (plot.building.id !in acceptedFortifications ||
                    city.building(plot.building.id)?.let { it.phase == BuildingPhase.ACTIVE &&
                        CivsSettlementPlans.sameFortificationGeometry(it, plot.building) } != true)
            })) {
            reject("fortification-not-active")
            return CivSettlementInfrastructure(emptyList(), rejected, false)
        }
        val fortificationGround = if (!frame.fortified) emptyMap() else fortifications.flatMap {
            requireNotNull(it.fortification).proof.groundBarrier }.associateBy(::key)
        val wallDomain = fortificationGround.keys.toMutableSet().apply {
            for (edge in frame.wallObstacles + frame.gates.map { it.passage })
                for (x in edge.min.x..edge.max.x) for (z in edge.min.z..edge.max.z) add(key(x, z))
        }
        fun parapet(x: Int, z: Int): Boolean = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
            .any { (dx, dz) -> key(x + dx, z + dz) !in wallDomain }
        require(accepted.map { it.id }.distinct().size == accepted.size)
        require(accepted.all { piece -> city.building(piece.id)?.let {
            it.type == piece.type && it.type.isInfrastructure && it.bounds == piece.bounds
        } == true })
        val pieces = accepted.toMutableList()
        val used = accepted.flatMapTo(hashSetOf()) { piece -> piece.blocks.map { key(it.position) } + piece.transit.map(::key) }
        val acceptedRoads = accepted.filter { it.type == BuildingType.ROAD }.flatMapTo(hashSetOf()) { it.transit.map(::key) }
        val oldCrossings = routes.flatten().filter { p -> frame.wallObstacles.any { contains(it, p) } }.mapTo(hashSetOf(), ::key)
        if (oldCrossings.isNotEmpty()) reject("route-crosses-wall")
        val protectedBuildings = city.buildings.filter { !it.type.isInfrastructure }
            .associate { it.id to CivsAutonomousGeometry.futureBounds(city, it) }
        val occupied = protectedBuildings.values
        val blockers = linkedMapOf<UUID, CivSettlementBlocker>()
        val acceptedTransit = accepted.flatMap { it.transit }.associateBy(::key)
        val routeFloors = linkedMapOf<Long, Int>().apply {
            acceptedTransit.forEach { (key, feet) -> put(key, feet.y - 1) }
            routes.flatten().forEach { feet -> putIfAbsent(key(feet), feet.y - 1) }
        }
        val preservedRoads = buildMap<Long, Int> {
            for (feet in retainedRoutes.flatten()) {
                val floor = feet.y - 1
                surface.at(feet.x, feet.z)?.takeIf { it.loaded && it.groundY == floor && it.topY == floor }
                    ?.let { putIfAbsent(key(feet), floor) }
                // The recorded route is its centreline. Preserve only actual dirt-path shoulders,
                // not arbitrary non-natural blocks near the route.
                for (dx in -1..1) for (dz in -1..1) {
                    val x = feet.x + dx; val z = feet.z + dz
                    val c = surface.at(x, z) ?: continue
                    if (c.loaded && c.dirtPath && c.topY == c.groundY && abs(c.groundY - floor) <= 1)
                        putIfAbsent(key(x, z), c.groundY)
                }
            }
        }
        val retainedFloors = accepted.flatMap { it.blocks }.groupBy { key(it.position) }
            .mapValues { (_, cells) -> cells.minOf { it.position.y } } +
            accepted.flatMap { it.waterFoundations }.associate { key(it.x, it.z) to it.floorY } +
            fortificationGround.mapValues { it.value.y }
        val wood = wood(city.style)
        val stone = if (city.style == "nordic") "minecraft:cobblestone" else "minecraft:stone_bricks"
        val path = "minecraft:cobblestone"
        fun ground(x: Int, z: Int): Int? {
            val c = surface.at(x, z)
            if (c == null || !c.loaded) { reject("chunks-unloaded"); return null }
            val water = c.waterTopY != null && c.waterBottomY != null && c.topY == c.waterTopY &&
                c.groundY == c.waterTopY && c.waterTopY.toLong() - c.waterBottomY in 1L..CivsWorld.MAXIMUM_SURFACE_WATER_DEPTH.toLong()
            if (!c.natural && !water && key(x, z) !in preservedRoads) { reject("protected-terrain"); return null }
            val point = CivPosition(x, c.groundY, z)
            val obstructing = protectedBuildings.filterValues { contains(it, point) }
            if (obstructing.isNotEmpty()) {
                obstructing.forEach { (id, bounds) -> blockers.putIfAbsent(id, CivSettlementBlocker(id, bounds, point)) }
                reject("building-overlap"); return null
            }
            return routeFloors[key(x, z)] ?: c.groundY
        }
        fun add(type: BuildingType, cells: List<CivSettlementBlock>, hint: CivPosition, transit: List<CivPosition> = emptyList(),
            reportRejection: Boolean = true): Boolean {
            if (cells.isEmpty()) return false
            val bounds = box(cells.map { it.position })
            val floors = cells.groupBy { key(it.position) }.mapValues { (column, group) ->
                transit.firstOrNull { key(it) == column }?.y?.minus(1)
                    ?: surface.at((column shr 32).toInt(), column.toInt())?.waterTopY
                    ?: group.minOf { it.position.y }
            }
            for ((column, y) in floors) {
                val x = (column shr 32).toInt(); val z = column.toInt()
                for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val neighbour = key(x + dx, z + dz)
                    val other = floors[neighbour] ?: retainedFloors[neighbour] ?: routeFloors[neighbour] ?:
                        if (type == BuildingType.WALL && frame.wallObstacles.any { contains(it, CivPosition(x + dx, y, z + dz)) })
                            surface.at(x + dx, z + dz)?.groundY else null
                    if (other != null && abs(other - y) > 1) {
                        if (reportRejection) reject("infrastructure-slope", CivPosition(x, y, z))
                        return false
                    }
                }
            }
            val approaches = buildList {
                for (x in bounds.min.x..bounds.max.x) {
                    add(CivPosition(x, 0, bounds.min.z - 1)); add(CivPosition(x, 0, bounds.max.z + 1))
                }
                for (z in bounds.min.z..bounds.max.z) {
                    add(CivPosition(bounds.min.x - 1, 0, z)); add(CivPosition(bounds.max.x + 1, 0, z))
                }
            }.mapNotNull { p ->
                val native = surface.at(p.x, p.z) ?: return@mapNotNull null
                if (!city.contains(p.x, p.z) || !native.loaded ||
                    !native.natural && key(p) !in acceptedTransit && key(p) !in preservedRoads ||
                    occupied.any { contains(it, p) } || frame.wallObstacles.any { contains(it, p) } ||
                    pieces.any { contains(it.bounds, p) && it.transit.none { feet -> key(feet) == key(p) } }) return@mapNotNull null
                val y = acceptedTransit[key(p)]?.y ?: preservedRoads[key(p)]?.plus(1) ?: native.groundY + 1
                val edge = key(p.x.coerceIn(bounds.min.x, bounds.max.x), p.z.coerceIn(bounds.min.z, bounds.max.z))
                if (abs(y - 1 - requireNotNull(floors[edge])) > 1) return@mapNotNull null
                p.copy(y = y)
            }
            val entrance = approaches.minWithOrNull(compareBy<CivPosition> { manhattan(it, frame.origin) }
                .thenBy { manhattan(it, hint) }.thenBy { it.x }.thenBy { it.z })
            if (entrance == null) {
                if (reportRejection) reject("infrastructure-no-approach", bounds.min)
                return false
            }
            val identity = "${city.id}:layout:$VERSION:${type.name}:${bounds.min.x}:${bounds.min.z}:${bounds.max.x}:${bounds.max.z}"
            val waterFoundations = floors.mapNotNull { (column, floor) ->
                val x = (column shr 32).toInt(); val z = column.toInt()
                val c = surface.at(x, z) ?: return@mapNotNull null
                val top = c.waterTopY ?: return@mapNotNull null
                val bottom = c.waterBottomY ?: return@mapNotNull null
                CivWaterFoundation(x, z, floor, top, bottom)
            }
            pieces += CivSettlementPiece(UUID.nameUUIDFromBytes(identity.toByteArray(StandardCharsets.UTF_8)),
                type, bounds, entrance, cells, transit, waterFoundations)
            cells.forEach { used += key(it.position) }
            return true
        }
        // Gates own their threshold. Adjacent roads stop at it, avoiding overlapping building boxes.
        for (gate in frame.gates) {
            if (accepted.any { it.type == BuildingType.GATE && sameColumns(it.bounds, gate.passage) }) continue
            val cells = mutableListOf<CivSettlementBlock>()
            val transit = mutableListOf<CivPosition>()
            val crossing = routes.flatten().filter { contains(gate.passage, it) }.distinctBy(::key)
            var valid = true
            for (x in gate.passage.min.x..gate.passage.max.x) for (z in gate.passage.min.z..gate.passage.max.z) {
                val nativeY = ground(x, z)
                if (nativeY == null) { valid = false; continue }
                val y = preservedRoads[key(x, z)] ?: routeFloors[key(x, z)] ?: crossing.minWithOrNull(compareBy<CivPosition> {
                    abs(it.x - x) + abs(it.z - z)
                }.thenBy { it.x }.thenBy { it.z })?.let { routeFloors.getValue(key(it)) } ?: nativeY
                val water = surface.at(x, z)?.takeIf { it.waterTopY != null && it.waterBottomY != null }
                if (abs(y - nativeY) > CivsAutonomousTerraform.MAXIMUM_CHANGE || water != null &&
                    (y < requireNotNull(water.waterTopY) || y - requireNotNull(water.waterBottomY) > CivsWorld.MAXIMUM_SURFACE_WATER_DEPTH)) {
                    reject("infrastructure-slope"); valid = false; continue
                }
                if (water != null) for (fillY in requireNotNull(water.waterBottomY) + 1 until y)
                    cells += CivSettlementBlock(CivPosition(x, fillY, z), stone)
                cells += CivSettlementBlock(CivPosition(x, y, z), if (key(x, z) in preservedRoads) "minecraft:barrier" else path)
                for (dy in 1..4) cells += CivSettlementBlock(CivPosition(x, y + dy, z), "minecraft:air")
                transit += CivPosition(x, y + 1, z)
            }
            if (valid && cells.isNotEmpty()) {
                val level = transit.maxOf { it.y - 1 }
                // A visible stone lintel sits above the complete four-block passage clearance.
                val horizontal = rotate(CivPosition(0, 0, -1), gate.side + frame.rotation / 90).z != 0
                for (x in gate.passage.min.x..gate.passage.max.x) for (z in gate.passage.min.z..gate.passage.max.z) {
                    if (if (horizontal) z == gate.center.z else x == gate.center.x)
                        cells += CivSettlementBlock(CivPosition(x, level + 5, z), stone)
                    else if (frame.fortified) cells += CivSettlementBlock(CivPosition(x, level + 5, z), "minecraft:air")
                    if (frame.fortified) {
                        cells += CivSettlementBlock(CivPosition(x, level + 6, z), "minecraft:air")
                        cells += CivSettlementBlock(CivPosition(x, level + 7, z), stone)
                        cells += CivSettlementBlock(CivPosition(x, level + 8, z), if (parapet(x, z)) stone else "minecraft:air")
                        cells += CivSettlementBlock(CivPosition(x, level + 9, z), "minecraft:air")
                    }
                }
                // Open leaves preserve the complete public transit rectangle and are usable
                // by both citizens and players. The surrounding wall owns the side supports.
                val leaves = transit.filter { if (horizontal) it.z == gate.center.z else it.x == gate.center.x }.toSet()
                val facing = if (horizontal) "north" else "east"
                val openGate = "minecraft:${wood}_fence_gate[facing=$facing,in_wall=false,open=true,powered=false]"
                for (i in cells.indices) if (cells[i].position in leaves && cells[i].after == "minecraft:air")
                    cells[i] = cells[i].copy(after = openGate)
                add(BuildingType.GATE, cells, gate.center.copy(y = level + 1), transit)
            }
        }
        // Keep clear 24-column orders stable; split failed dry runs without spanning paid pieces or holes.
        for (wall in frame.wallObstacles) {
            val alongX = wall.max.x - wall.min.x >= wall.max.z - wall.min.z
            val first = if (alongX) wall.min.x else wall.min.z
            val last = if (alongX) wall.max.x else wall.max.z
            fun addWall(columns: List<List<CivSettlementBlock>>) {
                val cells = columns.flatten()
                val p = cells.first().position
                val direction = if (alongX) CivPosition(0, 0, if (p.z < frame.origin.z) 1 else -1)
                    else CivPosition(if (p.x < frame.origin.x) 1 else -1, 0, 0)
                val added = add(BuildingType.WALL, cells,
                    p.copy(x = p.x + direction.x * 3, y = p.y + 1, z = p.z + direction.z * 3), reportRejection = columns.size == 1)
                // Every smaller order still passes the same validation; only final failures are reported.
                if (!added && columns.size > 1) {
                    val middle = columns.size / 2
                    addWall(columns.take(middle))
                    addWall(columns.drop(middle))
                }
            }
            for (start in first..last step MAXIMUM_PIECE_LENGTH) {
                val finish = minOf(last, start + MAXIMUM_PIECE_LENGTH - 1)
                val columns = mutableListOf<List<CivSettlementBlock>>()
                fun flush() {
                    if (columns.isEmpty()) return
                    addWall(columns.toList())
                    columns.clear()
                }
                for (axis in start..finish) {
                    val cells = mutableListOf<CivSettlementBlock>()
                    var valid = true
                    for (cross in (if (alongX) wall.min.z..wall.max.z else wall.min.x..wall.max.x)) {
                        val x = if (alongX) axis else cross
                        val z = if (alongX) cross else axis
                        if (key(x, z) in acceptedRoads || key(x, z) in oldCrossings) {
                            reject("route-crosses-wall"); valid = false; continue
                        }
                        if (key(x, z) in used) { valid = false; continue }
                        val y = ground(x, z)
                        if (y == null) { valid = false; continue }
                        val water = surface.at(x, z)?.takeIf { it.waterTopY != null && it.waterBottomY != null }
                        if (water != null) for (fillY in requireNotNull(water.waterBottomY) + 1 until y)
                            cells += CivSettlementBlock(CivPosition(x, fillY, z), stone)
                        val middle = if (alongX) (wall.min.z + wall.max.z) / 2 else (wall.min.x + wall.max.x) / 2
                        for (dy in 0..(if (frame.fortified) 7 else 3)) cells += CivSettlementBlock(CivPosition(x, y + dy, z),
                            if (cross == middle && Math.floorMod(axis, 6) == 0) "minecraft:${wood}_log[axis=y]" else stone)
                        if (frame.fortified) {
                            cells += CivSettlementBlock(CivPosition(x, y + 8, z), if (parapet(x, z)) stone else "minecraft:air")
                            cells += CivSettlementBlock(CivPosition(x, y + 9, z), "minecraft:air")
                        } else if (cross == middle || Math.floorMod(axis, 3) == 0)
                            cells += CivSettlementBlock(CivPosition(x, y + 4, z), if (cross == middle)
                                "minecraft:${wood}_fence[east=false,north=false,south=false,waterlogged=false,west=false]" else stone)
                    }
                    if (valid) columns += cells else flush()
                }
                flush()
            }
        }
        // New work paves a compact centreline. The wider canonical graded corridor remains
        // walkable ground; existing accepted ROAD/GATE rectangles above are unchanged.
        val roads = linkedMapOf<Long, CivPosition>()
        for (route in pavingRoutes) {
            if (!acceptsRoute(frame, route)) { reject("route-crosses-wall"); continue }
            for (feet in route) {
                val column = key(feet)
                val shoulder = feet.copy(y = feet.y - 1)
                if (!city.contains(shoulder.x, shoulder.z) || key(shoulder) in used ||
                    frame.gates.any { contains(it.passage, shoulder) } || occupied.any { contains(it, shoulder) }) continue
                val terrain = surface.at(shoulder.x, shoulder.z)
                val floor = routeFloors[column] ?: shoulder.y
                if (terrain?.loaded != true || !terrain.natural && column !in preservedRoads ||
                    abs(terrain.groundY - floor) > CivsAutonomousTerraform.MAXIMUM_CHANGE) continue
                roads.putIfAbsent(key(shoulder), shoulder.copy(y = floor))
            }
        }
        val pavedFloors = pieces.filter { it.type == BuildingType.ROAD || it.type == BuildingType.GATE }
            .flatMap { it.transit }.associate { key(it) to it.y - 1 } + roads.mapValues { it.value.y }
        val turns = pavingRoutes.flatMap { turnFloors(it) }.toSet()
        val routeOwners = linkedMapOf<CivPosition, List<CivPosition>>().apply {
            pavingRoutes.forEach { route -> route.forEach { feet -> putIfAbsent(feet.copy(y = feet.y - 1), route) } }
        }
        // Fixed horizontal rectangles retain each column's own grade, including stepped roads.
        // Accepted rectangles were removed above, so later routes never repartition earlier orders.
        while (roads.isNotEmpty()) {
            val p = roads.values.minWith(compareBy<CivPosition> { it.z }.thenBy { it.x })
            fun has(x: Int, z: Int) = key(x, z) in roads
            // The widest first row can strand thin diagonal tails. Choose the largest bounded
            // rectangle at the same deterministic origin; retained rectangles remain untouched.
            var width = 1; var depth = 1
            var candidateWidth = 1
            while (candidateWidth <= MAXIMUM_PIECE_LENGTH && has(p.x + candidateWidth - 1, p.z)) {
                var candidateDepth = 1
                while (candidateDepth < MAXIMUM_PIECE_LENGTH && candidateWidth * (candidateDepth + 1) <= MAXIMUM_ROAD_COLUMNS &&
                    (0 until candidateWidth).all { has(p.x + it, p.z + candidateDepth) }) candidateDepth++
                if (candidateWidth * candidateDepth > width * depth) { width = candidateWidth; depth = candidateDepth }
                candidateWidth++
            }
            val cells = mutableListOf<CivSettlementBlock>()
            val transit = mutableListOf<CivPosition>()
            for (x in p.x until p.x + width) for (z in p.z until p.z + depth) {
                val floor = requireNotNull(roads.remove(key(x, z)))
                if (key(x, z) in preservedRoads) {
                    // A verified paid spine remains entirely untouched, but can join both new shoulders
                    // in one ordinary rectangular order. Native retained-route checks still apply.
                    for (dy in 0..2) cells += CivSettlementBlock(floor.copy(y = floor.y + dy), "minecraft:barrier")
                } else {
                    cells += CivSettlementBlock(floor, pathPavement(floor, pavedFloors, city.style, atTurn = floor in turns,
                        downhill = routeOwners[floor]?.let { routeDownhill(floor, it) }))
                    for (dy in 1..2) cells += CivSettlementBlock(floor.copy(y = floor.y + dy), "minecraft:air")
                }
                transit += floor.copy(y = floor.y + 1)
            }
            if (cells.any { it.after != "minecraft:barrier" }) add(BuildingType.ROAD, cells, p.copy(y = p.y + 1), transit)
        }
        if (pieces.size > MAXIMUM_PIECES) reject("infrastructure-budget")
        val all = pieces.flatMap { it.blocks }.associate { it.position to it.after }
        if (frame.fortified) for (plot in fortifications) for (port in requireNotNull(plot.fortification).proof.wallEndPorts) {
            for (base in port.ground) {
                val outside = base.copy(x = base.x + port.outward.x, z = base.z + port.outward.z)
                val floor = outside.copy(y = outside.y + 7)
                if (all[floor]?.substringBefore('[') !in setOf(stone, "minecraft:${wood}_log", path) ||
                    all[outside.copy(y = outside.y + 8)] != "minecraft:air" ||
                    all[outside.copy(y = outside.y + 9)] != "minecraft:air") reject("fortification-port-unjoined", floor)
            }
        }
        if (frame.fortified && !fortificationWalkConnected(pieces, fortifications.map { requireNotNull(it.fortification).proof }))
            reject("fortification-walk-disconnected")
        val perimeter = pieces.filter { it.type == BuildingType.WALL || it.type == BuildingType.GATE }
            .flatMapTo(hashSetOf()) { piece -> piece.blocks.map { key(it.position) } }.apply { addAll(fortificationGround.keys) }
        val closed = (frame.wallObstacles + frame.gates.map { it.passage }).all { edge ->
            (edge.min.x..edge.max.x).all { x -> (edge.min.z..edge.max.z).all { z -> key(x, z) in perimeter } }
        } && fortificationGround.keys.all { it in perimeter } && "fortification-port-unjoined" !in rejected &&
            "fortification-walk-disconnected" !in rejected
        val repaired = pieces.map { piece -> if (piece in accepted) piece else piece.copy(blocks = piece.blocks.map { cell ->
            if (!cell.after.substringBefore('[').endsWith("_fence")) cell else {
                fun connects(dx: Int, dz: Int): Boolean = all[cell.position.copy(x = cell.position.x + dx, z = cell.position.z + dz)]
                    ?.let { it != "minecraft:air" } == true
                cell.copy(after = cell.after.substringBefore('[') + "[east=${connects(1, 0)},north=${connects(0, -1)}," +
                    "south=${connects(0, 1)},waterlogged=false,west=${connects(-1, 0)}]")
            }
        }) }
        return CivSettlementInfrastructure(repaired, rejected, closed, blockers.values.toList(), samples.mapValues { it.value.toList() })
    }

    internal fun wood(style: String): String = when (style) { "nordic" -> "spruce"; "caledonia" -> "dark_oak"; else -> "oak" }

    /** Native vertices are compiler-proven, including ordinary openable doors; generated decks require two explicit air cells. */
    internal fun fortificationWalkConnected(pieces: List<CivSettlementPiece>, proofs: List<CivFortificationProof>): Boolean {
        if (proofs.isEmpty() || proofs.any { it.wallWalkFloors.isEmpty() }) return false
        val walks = proofs.flatMapTo(hashSetOf()) { it.wallWalkFloors }
        val cells = pieces.filter { it.type in setOf(BuildingType.WALL, BuildingType.GATE) }.flatMap { it.blocks }
        val states = cells.associate { it.position to it.after }
        val full = setOf("minecraft:stone", "minecraft:cobblestone", "minecraft:stone_bricks", "minecraft:oak_log",
            "minecraft:spruce_log", "minecraft:dark_oak_log")
        cells.groupBy { key(it.position) }.values.forEach { column ->
            val top = column.filter { it.after.substringBefore('[') in full }.maxByOrNull { it.position.y }?.position
            if (top != null && states[top.copy(y = top.y + 1)] == "minecraft:air" &&
                states[top.copy(y = top.y + 2)] == "minecraft:air") walks += top
        }
        val start = proofs.first().wallEndPorts.first().floor.first()
        if (start !in walks) return false
        val visited = hashSetOf(start)
        val queue = ArrayDeque<CivPosition>().apply { addLast(start) }
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            for ((dx, dz) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) for (dy in -1..1) {
                val next = p.copy(x = p.x + dx, y = p.y + dy, z = p.z + dz)
                if (next in walks && visited.add(next)) queue.addLast(next)
            }
        }
        return visited.size == walks.size && proofs.all { proof -> proof.wallEndPorts.all { port -> port.floor.all { it in visited } } }
    }

    internal fun turnFloors(route: List<CivPosition>): Set<CivPosition> = route.windowed(3)
        .filter { (before, _, after) -> before.x != after.x && before.z != after.z }
        .mapTo(hashSetOf()) { (_, at, _) -> at.copy(y = at.y - 1) }

    internal fun routeDownhill(floor: CivPosition, route: List<CivPosition>): CivPosition? {
        val index = route.indexOf(floor.copy(y = floor.y + 1))
        if (index < 0) return null
        return listOfNotNull(route.getOrNull(index - 1), route.getOrNull(index + 1))
            .singleOrNull { it.y == floor.y }?.let { it.copy(y = it.y - 1) }
    }

    /** A full intermediate block separates straight stair flights when the continuing exit turns. */
    internal fun pathPavement(floor: CivPosition, floors: Map<Long, Int>, style: String,
        atTurn: Boolean = false, downhill: CivPosition? = null, legacyStairAtTurn: Boolean = false): String {
        val directions = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        if (!legacyStairAtTurn && atTurn && directions.any { (dx, dz) ->
                floors[key(floor.x + dx, floor.z + dz)]?.let { it == floor.y - 1 || it == floor.y + 1 } == true })
            return "minecraft:${wood(style)}_planks"
        val ordered = downhill?.takeUnless { legacyStairAtTurn }?.let {
            require(abs(it.x.toLong() - floor.x) + abs(it.z.toLong() - floor.z) == 1L && it.y == floor.y - 1)
            require(floors[key(it)] == it.y)
            (it.x - floor.x) to (it.z - floor.z)
        }
        val lower = ordered ?: directions.filter { (dx, dz) -> floors[key(floor.x + dx, floor.z + dz)] == floor.y - 1 }
            .singleOrNull() ?: return "minecraft:dirt_path"
        if (directions.none { (dx, dz) -> (dx to dz) != lower &&
                floors[key(floor.x + dx, floor.z + dz)]?.let { it in floor.y..floor.y + 1 } == true })
            return "minecraft:dirt_path"
        val (dx, dz) = lower
        val facing = when { dx == 1 -> "west"; dx == -1 -> "east"; dz == 1 -> "north"; else -> "south" }
        return "minecraft:${wood(style)}_stairs[facing=$facing,half=bottom,shape=straight,waterlogged=false]"
    }

    /** Legacy authored road finish, retained for construction receipts accepted before path upkeep. */
    internal fun pavement(floor: CivPosition, floors: Map<Long, Int>): String {
        val lower = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).filter { (dx, dz) ->
            floors[key(floor.x + dx, floor.z + dz)] == floor.y - 1
        }.singleOrNull() ?: return "minecraft:cobblestone"
        val (dx, dz) = lower
        val next = floors[key(floor.x - dx, floor.z - dz)] ?: return "minecraft:cobblestone"
        if (next !in floor.y..floor.y + 1) return "minecraft:cobblestone"
        val facing = when { dx == 1 -> "west"; dx == -1 -> "east"; dz == 1 -> "north"; else -> "south" }
        return "minecraft:cobblestone_stairs[facing=$facing,half=bottom,shape=straight,waterlogged=false]"
    }

    /** Authored search volume only; this ranks new plots and never authorizes a native mutation. */
    private fun operatingBounds(building: CivBuilding): CivBox? {
        if (building.type == BuildingType.FORESTER) {
            val bounds = CivsWorksiteLayouts.site(building)?.getAsJsonObject("forester")?.getAsJsonObject("searchBounds") ?: return null
            return CivBox(CivsWorksiteLayouts.vector(bounds["min"]), CivsWorksiteLayouts.vector(bounds["max"]))
        }
        if (building.type != BuildingType.QUARRY || CivsMinePlan.forBuilding(building) == null) return null
        val positions = CivsMinePlan.sealPositions(building)
        if (positions.isEmpty()) return null
        return CivBox(CivPosition(positions.minOf { it.x }, positions.minOf { it.y }, positions.minOf { it.z }),
            CivPosition(positions.maxOf { it.x }, positions.maxOf { it.y }, positions.maxOf { it.z }))
    }

    private fun insideCore(frame: CivSettlementFrame, p: CivPosition, inset: Int): Boolean =
        p.x in frame.core.min.x + inset..frame.core.max.x - inset && p.z in frame.core.min.z + inset..frame.core.max.z - inset
    private fun contains(box: CivBox, p: CivPosition): Boolean = p.x in box.min.x..box.max.x && p.z in box.min.z..box.max.z
    private fun sameColumns(a: CivBox, b: CivBox) = a.min.x == b.min.x && a.min.z == b.min.z && a.max.x == b.max.x && a.max.z == b.max.z
    private fun key(p: CivPosition) = key(p.x, p.z)
    private fun key(x: Int, z: Int) = civsTerrainKey(x, z)
    private fun center(box: CivBox) = CivPosition((box.min.x + box.max.x) / 2, box.min.y, (box.min.z + box.max.z) / 2)
    private fun manhattan(a: CivPosition, b: CivPosition) = abs(a.x - b.x) + abs(a.z - b.z)
    private fun streetPoints(street: CivSettlementStreet): List<CivPosition> {
        val dx = (street.to.x - street.from.x).compareTo(0)
        val dz = (street.to.z - street.from.z).compareTo(0)
        require(dx == 0 || dz == 0)
        return (0..manhattan(street.from, street.to)).map { d -> street.from.copy(x = street.from.x + dx * d, z = street.from.z + dz * d) }
    }
    private fun rotate(p: CivPosition, turns: Int): CivPosition = when (Math.floorMod(turns, 4)) {
        0 -> p; 1 -> CivPosition(-p.z, p.y, p.x); 2 -> CivPosition(-p.x, p.y, -p.z); else -> CivPosition(p.z, p.y, -p.x)
    }
    private fun corners(box: CivBox) = listOf(box.min, box.max,
        CivPosition(box.min.x, box.min.y, box.max.z), CivPosition(box.max.x, box.max.y, box.min.z))
    private fun box(points: List<CivPosition>) = CivBox(
        CivPosition(points.minOf { it.x }, points.minOf { it.y }, points.minOf { it.z }),
        CivPosition(points.maxOf { it.x }, points.maxOf { it.y }, points.maxOf { it.z }))
}
