package ru.arc.civs

import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.api.ArcSchematicSnapshot
import kotlin.math.abs

/** The authored marker is a construction anchor; active buildings use an accessible interior barrel. */
internal object CivsBuildingControls {
    fun supported(type: BuildingType): Boolean = !type.isInfrastructure &&
        type != BuildingType.DECORATION && type != BuildingType.PLAN_BOARD

    private val furniture = setOf("barrel", "chest", "trapped_chest", "hopper", "furnace", "blast_furnace",
        "smoker", "crafting_table", "smithing_table", "fletching_table", "stonecutter", "grindstone",
        "anvil", "chipped_anvil", "damaged_anvil", "lectern", "bookshelf", "chiseled_bookshelf")
    private val directions = listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)

    /** Detached source geometry only. It never edits captured plans, walls, furniture or native contents. */
    fun authored(building: CivBuilding, snapshot: ArcSchematicSnapshot): List<CivControlCandidate> {
        if (!supported(building.type)) return emptyList()
        // Barrier cells retain native terrain; they are conditional route space, never authored walls to replace.
        val interior = CivsAuthoredInterior(building, snapshot, furniture, retainedGround = true)
        val old = building.control?.let { point ->
            CivsBlueprintGeometry.rotate(ArcSchematicPoint(point.x - building.anchor.x,
                point.y - building.anchor.y, point.z - building.anchor.z), -building.rotation / 90).let {
                it.copy(x = it.x + snapshot.origin.x, y = it.y + snapshot.origin.y, z = it.z + snapshot.origin.z)
            }
        }
        // A previously selected authored barrel is furniture. A later correction must retain it and its contents.
        val retire = old == null || old.x !in snapshot.minimum.x..snapshot.maximum.x ||
            old.y !in snapshot.minimum.y..snapshot.maximum.y || old.z !in snapshot.minimum.z..snapshot.maximum.z ||
            interior.name(old) in setOf("air", "cave_air", "void_air", "structure_void", "barrier")
        fun awayFromFront(p: ArcSchematicPoint): Boolean = p.x > snapshot.minimum.x && p.x < snapshot.maximum.x &&
            p.z > snapshot.minimum.z && p.z < snapshot.maximum.z &&
            abs(p.x - interior.entrance.x) + abs(p.z - interior.entrance.z) >= 4
        // A post or a stacked rack above one floor tile does not turn an open work yard into a room.
        val roofed = interior.reachable.count(interior::interior) >= 4
        fun standing(p: ArcSchematicPoint): Boolean = awayFromFront(p) &&
            (if (roofed) interior.interior(p) else interior.operatingFloor(p)) &&
            !interior.name(p).endsWith("_door") && !interior.name(p).endsWith("_fence_gate")
        val order = compareBy<ArcSchematicPoint>({ if (interior.world(it) == building.control) 0 else 1 }, { abs(it.y - interior.entrance.y) },
            { abs(it.x - (snapshot.minimum.x + snapshot.maximum.x) / 2) +
                abs(it.z - (snapshot.minimum.z + snapshot.maximum.z) / 2) }, { it.x }, { it.z })
        val existing = interior.cells.asSequence().filter { cell ->
            interior.name(cell.position) == "barrel" || interior.name(cell.position) == "structure_void" &&
                interior.world(cell.position) == building.control
        }
            .map { it.position }.filter { awayFromFront(it) && (!roofed || interior.sheltered(it)) }
            .sortedWith(order).flatMap { point ->
                directions.asSequence().flatMap { (dx, dz) -> sequenceOf(0, -1, 1).map { dy ->
                    point.copy(x = point.x + dx, y = point.y + dy, z = point.z + dz)
                } }.filter(::standing).mapNotNull { stance ->
                    interior.routeTo(stance)?.let { route ->
                        CivControlCandidate(interior.world(point), interior.world(stance), true, retire, route)
                    }
                }
            }.distinctBy(CivControlCandidate::position).take(16).toList()
        val work = CivsWorkstations.authored(building, snapshot).mapNotNull(CivWorkstation::focus).distinct()
        val possible = interior.reachable.asSequence().filter { p -> standing(p) && interior.name(p) in setOf("air", "barrier") &&
            interior.name(p.copy(y = p.y - 1)) != "farmland" &&
            (roofed || p.x > snapshot.minimum.x + 1 && p.x < snapshot.maximum.x - 1 &&
                p.z > snapshot.minimum.z + 1 && p.z < snapshot.maximum.z - 1) &&
            interior.wallScore(p) > 0 && work.none { focus -> interior.world(p).let {
                abs(it.y.toLong() - focus.y) <= 1 && abs(it.x.toLong() - focus.x) + abs(it.z.toLong() - focus.z) < 2
            } } }.sortedWith(compareBy<ArcSchematicPoint>({ abs(it.y - interior.entrance.y) },
                { -interior.wallScore(it) }).then(order)).take(64)
        val spare = possible.mapNotNull { point ->
            val stance = interior.neighbors(point).firstOrNull { it != point && standing(it) }
                ?: return@mapNotNull null
            val route = interior.routeTo(stance, point) ?: return@mapNotNull null
            CivControlCandidate(interior.world(point), interior.world(stance), false, retire, route)
        }.take(16).toList()
        return (existing + spare).take(CivsWorkstations.MAXIMUM_POINTS)
    }
}
