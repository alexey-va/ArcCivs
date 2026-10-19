package ru.arc.civs

import org.bukkit.entity.Player
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.inventory.ItemStack
import org.bukkit.event.inventory.ClickType
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
    val materials: Map<String, Long> = emptyMap(),
    val control: CivPosition = anchor,
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

data class CivsSupplyResult(val moved: Long, val remaining: Long, val storageFull: Boolean)

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
    fun cityBusy(player: Player): Boolean
    fun styles(): List<String>
    fun settlements(): List<Settlement>
    fun city(player: Player): Settlement?
    fun refreshCity(player: Player): CompletableFuture<Unit>
    fun remoteCity(player: Player): CivsNetworkCity?
    fun foundingAllowed(): Boolean
    fun transferHome(player: Player): CompletableFuture<Unit>
    fun workerStatus(workerId: UUID): String
    fun buildingStatus(buildingId: UUID): String
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
    fun supplyWorker(player: Player, workerId: UUID, material: String): CompletableFuture<Unit>
    fun returnWorkerItem(player: Player, workerId: UUID, material: String): CompletableFuture<Unit>
    fun feedWorker(player: Player, workerId: UUID): CompletableFuture<Unit>
    fun residentItems(player: Player, workerId: UUID): List<ItemStack?>
    fun transferResidentItem(player: Player, workerId: UUID, npcSlot: Int?, playerSlot: Int?, click: ClickType): CompletableFuture<Unit>
    fun addMember(player: Player, name: String): CompletableFuture<Unit>
    fun removeMember(player: Player, memberId: UUID): CompletableFuture<Unit>
    fun deleteCity(player: Player, expectedCityId: UUID, confirmationName: String): CompletableFuture<Unit>
    fun deleteBuilding(player: Player, expectedCityId: UUID, buildingId: UUID): CompletableFuture<Unit>
    fun retryConstruction(player: Player, cityId: UUID): CompletableFuture<Unit>
    fun startBuilding(player: Player, buildingId: UUID): CompletableFuture<Unit>
    fun buildingMaterials(player: Player, buildingId: UUID): CompletableFuture<Map<String, Long>>
    fun supplyConstruction(player: Player, buildingId: UUID): CompletableFuture<CivsSupplyResult>
    fun grantConstructionMaterials(player: Player, buildingId: UUID): CompletableFuture<CivsSupplyResult>
    fun availableMaterials(player: Player): Map<String, Long>
    fun localMaterials(player: Player, buildingId: UUID): Map<String, Long>
    fun supplyRequests(player: Player): List<CivSupplyRequest>
    fun openBuildingInventory(player: Player, buildingId: UUID): CompletableFuture<Unit>
    fun buildingControl(player: Player, buildingId: UUID): CompletableFuture<CivPosition>
    fun relocateControl(player: Player, buildingId: UUID, expectedTarget: CivPosition): CompletableFuture<Unit>
    fun setStorageMode(player: Player, mode: CivStorageMode): CompletableFuture<Unit>
    fun materializeStorage(player: Player): CompletableFuture<Unit>
    fun depositMaterials(player: Player, buildingId: UUID): CompletableFuture<Unit>
    fun withdrawMaterial(player: Player, buildingId: UUID, material: String): CompletableFuture<Unit>
    fun registerContainer(player: Player): CompletableFuture<Unit>
    fun unregisterContainer(player: Player, position: CivPosition): CompletableFuture<Unit>
    fun setProduction(player: Player, buildingId: UUID, material: String): CompletableFuture<Unit>
    fun reload(player: Player): CompletableFuture<Unit>
}
