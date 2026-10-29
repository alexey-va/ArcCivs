package ru.arc.civs

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Stairs
import org.bukkit.util.BoundingBox
import kotlin.math.abs
import kotlin.math.floor

/** A native chair plus its checked, adjacent standing approach; no world entity or mutation. */
internal data class CitizenDiningSeat(val block: CivPosition, val state: String, val approach: CivPosition,
    val feet: Location, val scale: Double, val body: List<BoundingBox>, val legs: BoundingBox,
    val table: CivPosition, val tableState: String, val tableContact: Boolean, val occupiedLegs: BoundingBox,
    val furnitureId: String? = null) {
    val bounds: BoundingBox get() = body.fold(occupiedLegs.clone()) { box, part -> box.union(part) }
}

/** At most six authored anchors; old layouts probe a bounded, rotating slice near their control. */
internal fun citizenDiningCandidates(building: CivBuilding): List<CivPosition> {
    val furniture = CivsTavernFurniture.records(building)
    if (furniture.isNotEmpty()) return furniture.filter { it.kind == "chair" }
        .take(6).map { CivsWorksiteLayouts.point(building, it.local) }
    val authored = CivsWorksiteLayouts.points(building, "tavern", "dining")
    if (authored.isNotEmpty()) return authored.take(6)
    val origin = building.control ?: building.entrance
    return buildList {
        for (radius in 1..8) for (dy in listOf(0, 1, -1)) for (dx in -radius..radius) for (dz in -radius..radius) {
            if (maxOf(abs(dx), abs(dz)) != radius) continue
            val point = CivPosition(origin.x + dx, origin.y + dy, origin.z + dz)
            if (building.bounds.contains(point.x, point.y, point.z)) add(point)
        }
    }
}

internal fun citizenDiningSeat(world: World, building: CivBuilding, point: CivPosition, scale: Double,
                               available: (BoundingBox) -> Boolean): CitizenDiningSeat? {
    if (!building.bounds.contains(point.x, point.y, point.z) || !citizenChunkReady(world, point.x shr 4, point.z shr 4) ||
        point.y !in world.minHeight + 1 until world.maxHeight - 2) return null
    val block = world.getBlockAt(point.x, point.y, point.z)
    val stair = block.blockData as? Stairs ?: return null
    if (stair.half != Bisected.Half.BOTTOM || stair.shape != Stairs.Shape.STRAIGHT || stair.isWaterlogged ||
        !citizenPacketSupported(world, CitizenPoint(point.x + .5, point.y.toDouble(), point.z + .5))) return null
    val front = stair.facing.oppositeFace
    if (front !in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) return null
    val furniture = CivsTavernFurniture.records(building)
    val chair = furniture.firstOrNull { it.kind == "chair" && CivsWorksiteLayouts.point(building, it.local) == point }
    if (furniture.isNotEmpty() && (chair == null || CivsTavernFurniture.state(building, chair) != block.blockData.asString)) return null
    val authoredTable = chair?.let { value -> furniture.singleOrNull { it.kind == "table" && it.id == value.tableId } }
    var table: CivPosition? = null
    if ((1..2).none { distance ->
        val oppositeX = point.x + front.modX * distance * 2
        val oppositeZ = point.z + front.modZ * distance * 2
        if (!citizenChunkReady(world, oppositeX shr 4, oppositeZ shr 4)) return@none false
        val opposite = world.getBlockAt(oppositeX, point.y, oppositeZ).blockData as? Stairs ?: return@none false
        if (opposite.half != Bisected.Half.BOTTOM || opposite.shape != Stairs.Shape.STRAIGHT ||
            opposite.facing != front || opposite.isWaterlogged) return@none false
        val tableX = point.x + front.modX * distance; val tableZ = point.z + front.modZ * distance
        val candidate = if (world.getBlockAt(tableX, point.y, tableZ).collisionShape.boundingBoxes.any { it.maxY >= .9 })
            CivPosition(tableX, point.y, tableZ) else CivPosition(tableX, point.y + 1, tableZ)
        val native = world.getBlockAt(candidate.x, candidate.y, candidate.z)
        if (native.collisionShape.boundingBoxes.isEmpty() || authoredTable != null &&
            (CivsWorksiteLayouts.point(building, authoredTable.local) != candidate ||
                CivsTavernFurniture.state(building, authoredTable) != native.blockData.asString)) return@none false
        table = candidate
        true
    }) return null
    val tablePoint = requireNotNull(table)
    val tableBlock = world.getBlockAt(tablePoint.x, tablePoint.y, tablePoint.z)
    val tableContact = tablePoint == CivPosition(point.x + front.modX, point.y, point.z + front.modZ) &&
        tableBlock.collisionShape.boundingBoxes.any { it.minX <= 0 && it.minY <= 0 && it.minZ <= 0 && it.maxX >= 1 && it.maxY >= 1 && it.maxZ >= 1 }
    // Native bottom stair's low half supplies the seat surface; derive it from the current shape.
    val forwardOffset = maxOf(.2 * scale, .15) // Child head also has to clear the native high stair half.
    val x = point.x + .5 + front.modX * forwardOffset
    val z = point.z + .5 + front.modZ * forwardOffset
    val local = block.collisionShape.boundingBoxes
    val surface = local.filter { x - point.x in it.minX..it.maxX && z - point.z in it.minZ..it.maxZ }
        .maxOfOrNull { it.maxY }?.takeIf { it in 0.49..0.51 } ?: return null
    val yaw = when (front) { BlockFace.SOUTH -> 0f; BlockFace.WEST -> 90f; BlockFace.NORTH -> 180f; else -> 270f }
    val feet = Location(world, x, point.y + surface - .7040625 * scale, z, yaw, 0f)
    fun box(left: Double, bottom: Double, back: Double, right: Double, top: Double, forward: Double): BoundingBox {
        val sideX = front.modZ; val sideZ = -front.modX
        val corners = listOf(left, right).flatMap { side -> listOf(back, forward).map { depth ->
            (x + (sideX * side + front.modX * depth) * scale) to
                (z + (sideZ * side + front.modZ * depth) * scale)
        } }
        return BoundingBox(corners.minOf { it.first }, feet.y + bottom * scale, corners.minOf { it.second },
            corners.maxOf { it.first }, feet.y + top * scale, corners.maxOf { it.second })
    }
    // Exact 1.21.11 AvatarRenderer scales .9375 after LivingEntityRenderer's 1.501
    // origin shift. The seated HumanoidModel leaves torso/head upright, with hips at
    // (1.501 - 12/16) * .9375 = .7040625, and bends its legs -1.4137167 radians.
    // These padded torso/head and lower-leg envelopes exclude swinging hands/tools.
    // The hip plane rests on the surface. Core independently adds its .6 * scale
    // vehicle attachment offset; that native mount point is not the rendered hip.
    val body = listOf(box(-.30, .705, -.18, .30, 1.43, .18), box(-.29, 1.38, -.29, .29, 1.91, .29))
    // Native riding rotations plus the pants layer give side +/- .46740,
    // y .46916..84615 and depth -.07411..73459. Keep > .02 padding without
    // the old .55-side box falsely entering a perpendicular neighbouring chair.
    val legs = box(-.49, .44, -.10, .49, .87, .76)
    // Riding legs project forward. A verified adjacent full table occludes only
    // that lower-leg contact; torso/head and the standing approach remain clear.
    val occupiedLegs = BoundingBox(
        if (tableContact && front == BlockFace.WEST) maxOf(legs.minX, tablePoint.x + 1.0) else legs.minX, legs.minY,
        if (tableContact && front == BlockFace.NORTH) maxOf(legs.minZ, tablePoint.z + 1.0) else legs.minZ,
        if (tableContact && front == BlockFace.EAST) minOf(legs.maxX, tablePoint.x.toDouble()) else legs.maxX, legs.maxY,
        if (tableContact && front == BlockFace.SOUTH) minOf(legs.maxZ, tablePoint.z.toDouble()) else legs.maxZ)
    if (body.any { !available(it) || !citizenDiningVolumeClear(world, it) } || !available(legs) ||
        !citizenDiningVolumeClear(world, legs, point, tablePoint.takeIf { tableContact })) return null
    val sides = listOf(BlockFace.WEST, BlockFace.EAST, BlockFace.NORTH, BlockFace.SOUTH)
        .filter { it != stair.facing }.sortedBy { if (it == front) 1 else 0 }
    for (side in sides) for (dy in listOf(0, 1, -1)) {
        val approach = CivPosition(point.x + side.modX, point.y + dy, point.z + side.modZ)
        val at = Location(world, approach.x + .5, approach.y.toDouble(), approach.z + .5)
        if (!building.bounds.contains(approach.x, approach.y, approach.z) || !citizenStandable(world, approach)) continue
        // The integer route target may carry a thin carpet. Arrival records the actual
        // supported feet; admission checks that same raised surface instead of its base.
        val raised = world.getBlockAt(approach.x, approach.y, approach.z).collisionShape.boundingBoxes.maxOfOrNull { it.maxY } ?: 0.0
        if (raised > .125) continue
        at.y += raised
        if (!available(diningStandingBounds(at, scale))) continue
        val seat = CitizenDiningSeat(point, block.blockData.asString, approach, feet, scale, body, legs,
            tablePoint, tableBlock.blockData.asString, tableContact, occupiedLegs, chair?.id)
        if (citizenDiningTransitionClear(world, seat, at, scale)) return seat
    }
    return null
}

internal fun citizenDiningSeatClear(world: World, seat: CitizenDiningSeat): Boolean =
    citizenChunkReady(world, seat.block.x shr 4, seat.block.z shr 4) &&
        world.getBlockAt(seat.block.x, seat.block.y, seat.block.z).blockData.asString == seat.state &&
        citizenChunkReady(world, seat.table.x shr 4, seat.table.z shr 4) &&
        world.getBlockAt(seat.table.x, seat.table.y, seat.table.z).blockData.asString == seat.tableState &&
        citizenPacketSupported(world, CitizenPoint(seat.block.x + .5, seat.block.y.toDouble(), seat.block.z + .5)) &&
        seat.body.all { citizenDiningVolumeClear(world, it) } &&
        citizenDiningVolumeClear(world, seat.legs, seat.block, seat.table.takeIf { seat.tableContact })

/** A sit/stand adjustment is confined to the adjacent chair cell and a clear swept body. */
internal fun citizenDiningTransitionClear(world: World, seat: CitizenDiningSeat, standing: Location, scale: Double): Boolean {
    if (standing.world != world || abs(standing.x - seat.feet.x) > 1.4 || abs(standing.z - seat.feet.z) > 1.4 ||
        abs(standing.y - seat.block.y) > 1.13) return false
    val atSeat = seat.feet.clone().also { it.y = standing.y }
    val swept = diningStandingBounds(standing, scale).union(diningStandingBounds(atSeat, scale))
    // Only the verified chair may intersect the body during lowering/rising. A wall,
    // adjacent chair or a table in the approach never receives this exception.
    return citizenDiningVolumeClear(world, swept, seat.block)
}

internal fun citizenDiningStandingClear(world: World, at: Location, scale: Double): Boolean =
    citizenPacketSupported(world, CitizenPoint(at.x, at.y, at.z)) &&
        citizenDiningVolumeClear(world, diningStandingBounds(at, scale))

internal fun diningStandingBounds(at: Location, scale: Double): BoundingBox = BoundingBox(
    at.x - .299 * scale, at.y + .001, at.z - .299 * scale,
    at.x + .299 * scale, at.y + 1.799 * scale, at.z + .299 * scale)

private fun citizenDiningVolumeClear(world: World, box: BoundingBox, chair: CivPosition? = null,
    tableContact: CivPosition? = null): Boolean {
    for (x in floor(box.minX).toInt()..floor(box.maxX).toInt()) for (z in floor(box.minZ).toInt()..floor(box.maxZ).toInt()) {
        if (!citizenChunkReady(world, x shr 4, z shr 4)) return false
        for (y in floor(box.minY).toInt() - 1..floor(box.maxY).toInt()) {
            if (y !in world.minHeight until world.maxHeight) return false
            if (chair?.let { it.x == x && it.y == y && it.z == z } == true) continue
            val block = world.getBlockAt(x, y, z)
            if (block.isLiquid && box.overlaps(BoundingBox(x.toDouble(), y.toDouble(), z.toDouble(), x + 1.0, y + 1.0, z + 1.0)) ||
                block.type in CITIZEN_LEISURE_HAZARDS ||
                tableContact?.let { it.x == x && it.y == y && it.z == z } != true &&
                    block.collisionShape.boundingBoxes.any { it.clone().shift(x.toDouble(), y.toDouble(), z.toDouble()).overlaps(box) }) return false
        }
    }
    return true
}
