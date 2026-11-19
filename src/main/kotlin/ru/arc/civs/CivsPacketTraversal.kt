package ru.arc.civs

import org.bukkit.World
import kotlin.math.abs
import kotlin.math.floor

/** One live hook joins the low column to the adjacent cap. Both directions use the same corner and anchor. */
internal class CitizenWallGrapple(start: CitizenPoint, end: CitizenPoint) {
    val low = if (start.y < end.y) start else end
    private val high = if (start.y < end.y) end else start
    val anchor = citizenGrappleAnchor(low, high)
    val corner = CitizenPoint(low.x, high.y, low.z)
    val descending = start == high
    var cornerReached = false
    var castTicks = 0
    var pullTicks = 0
    var feet = start
    val stroke: Int get() = if (pullTicks == 0) 0 else 1 + (pullTicks - 1) / 10
    // One hand-over-hand pull travels .35 blocks in ten ticks, including a two-tick regrip.
    val stride: Double get() = CITIZEN_GRAPPLE_STRIDE * when (pullTicks % 10) {
        0, 7 -> .8
        8, 9 -> 0.0
        else -> 1.4
    }
    private val wallX = floor(high.x).toInt()
    private val wallZ = floor(high.z).toInt()
    private val bottomRow = floor(low.y + .001).toInt()
    private val capRow = citizenSupportY(high.y)
    private val dx = wallX - floor(low.x).toInt()
    private val dz = wallZ - floor(low.z).toInt()

    fun wallClear(world: World, at: CitizenPoint, permitted: (CivPosition) -> Boolean): Boolean {
        if (abs(dx) + abs(dz) != 1 || abs(high.x - low.x - dx) > .000001 ||
            abs(high.z - low.z - dz) > .000001 || citizenStepHeightAllowed(low.y, high.y) || high.y - low.y > CITIZEN_GRAPPLE_MAX_RISE ||
            bottomRow < world.minHeight || capRow >= world.maxHeight || !citizenChunkReady(world, wallX shr 4, wallZ shr 4)) return false
        fun face(row: Int): Boolean {
            val position = CivPosition(wallX, row, wallZ)
            if (!permitted(position)) return false
            val block = world.getBlockAt(wallX, row, wallZ)
            if (block.isLiquid || citizenRouteOpenable(block.blockData) != null || citizenCampfireLit(block)) return false
            val top = if (row == capRow) high.y - row else 1.0
            return block.collisionShape.boundingBoxes.any { box ->
                abs(box.maxY - top) < .000001 &&
                    citizenHookFace(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, dx, dz)
            }
        }
        // Casting validates at most one wall of the allowed rise. In flight only the attachment and current row matter.
        if (castTicks == 0) return (bottomRow..capRow).all(::face)
        val row = floor(at.y + .001).toInt().coerceIn(bottomRow, capRow)
        return face(capRow) && (row == capRow || face(row))
    }
}
