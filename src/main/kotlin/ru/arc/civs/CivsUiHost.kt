package ru.arc.civs

import org.bukkit.entity.Player
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import java.util.UUID
import java.util.concurrent.CompletableFuture

data class CivsPreviewView(
    val type: BuildingType,
    val level: Int,
    val style: String,
    val cityName: String,
    val anchor: CivPosition,
    val rotation: Int,
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val cost: Stock,
    val blocks: Int,
    val id: UUID,
    val worldId: UUID,
    val entrance: CivPosition,
    val bounds: CivBox,
    val movable: Boolean,
    val busy: Boolean,
)

/** Coordinates from the interaction, never a second ray cast after the click. */
data class CivsGroundTarget(val worldId: UUID, val block: CivPosition)

/** Share the same bounded ground resolution between the aim marker and the clicked input. */
internal fun civsGroundSupport(hit: Block): CivPosition? {
    var block = hit
    repeat(3) { depth ->
        if (block.type.isSolid && !block.isLiquid) return CivPosition(block.x, block.y, block.z)
        // Short grass, flowers and two-block ferns resolve to their support; air and water are not ground.
        if (depth == 2 || block.type.isAir || block.isLiquid || !block.isPassable || block.y <= block.world.minHeight) return null
        block = block.getRelative(BlockFace.DOWN)
    }
    return null
}

data class CivsSelectionView(
    val id: UUID, val worldId: UUID, val type: BuildingType, val expiresAt: Long, val busy: Boolean,
)

/** One block relative to Bukkit yaw: 0 south, 1 west, 2 north, 3 east. */
enum class CivsPreviewMove { UP, DOWN, LEFT, RIGHT, TOWARD, AWAY }

internal fun civsMovedEntrance(point: CivPosition, direction: CivsPreviewMove, facing: Int): CivPosition {
    require(facing in 0..3)
    val (forwardX, forwardZ) = listOf(0 to 1, -1 to 0, 0 to -1, 1 to 0)[facing]
    val (x, y, z) = when (direction) {
        CivsPreviewMove.UP -> Triple(0, 1, 0)
        CivsPreviewMove.DOWN -> Triple(0, -1, 0)
        CivsPreviewMove.LEFT -> Triple(forwardZ, 0, -forwardX)
        CivsPreviewMove.RIGHT -> Triple(-forwardZ, 0, forwardX)
        CivsPreviewMove.TOWARD -> Triple(-forwardX, 0, -forwardZ)
        CivsPreviewMove.AWAY -> Triple(forwardX, 0, forwardZ)
    }
    return CivPosition(Math.addExact(point.x, x), Math.addExact(point.y, y), Math.addExact(point.z, z))
}

/** Main-thread UI port. Every mutation validates authority in its owning service. */
interface CivsUiHost {
    fun ready(): Boolean
    fun unavailableReason(): String?
    fun styles(): List<String>
    fun settlements(): List<Settlement>
    fun city(player: Player): Settlement?
    fun refreshCity(player: Player): CompletableFuture<Unit>
    fun remoteCity(player: Player): CivsNetworkCity?
    fun foundingAllowed(): Boolean
    fun transferHome(player: Player): CompletableFuture<Unit>
    fun workerStatus(workerId: UUID): String
    fun activeNpcCount(): Int
    fun preview(player: Player): CivsPreviewView?
    fun selection(player: Player): CivsSelectionView?
    fun beginFoundingSelection(player: Player, name: String, style: String): CompletableFuture<Unit>
    fun beginBuildingSelection(player: Player, type: BuildingType): CompletableFuture<Unit>
    fun selectGround(player: Player, selectionId: UUID, target: CivsGroundTarget): CompletableFuture<Unit>
    fun cancelSelection(player: Player, reason: String)
    fun prepareUpgrade(player: Player, buildingId: UUID): CompletableFuture<Unit>
    fun rotatePreview(player: Player, degrees: Int = 90): CompletableFuture<Unit>
    fun movePreview(player: Player): CompletableFuture<Unit>
    fun movePreview(player: Player, target: CivsGroundTarget): CompletableFuture<Unit>
    fun nudgePreview(player: Player, direction: CivsPreviewMove): CompletableFuture<Unit>
    fun confirmPreview(player: Player): CompletableFuture<Unit>
    fun cancelPreview(player: Player)
    fun recruit(player: Player): CompletableFuture<Unit>
    fun assign(player: Player, workerId: UUID, buildingId: UUID?): CompletableFuture<Unit>
    fun addMember(player: Player, name: String): CompletableFuture<Unit>
    fun removeMember(player: Player, memberId: UUID): CompletableFuture<Unit>
    fun retryConstruction(player: Player, cityId: UUID): CompletableFuture<Unit>
    fun reload(player: Player): CompletableFuture<Unit>
}
