package ru.arc.civs

import org.bukkit.entity.Player
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
    fun styles(): List<String>
    fun settlements(): List<Settlement>
    fun city(player: Player): Settlement?
    fun workerStatus(workerId: UUID): String
    fun activeNpcCount(): Int
    fun preview(player: Player): CivsPreviewView?
    fun prepareFounding(player: Player, name: String, style: String): CompletableFuture<Unit>
    fun prepareBuilding(player: Player, type: BuildingType): CompletableFuture<Unit>
    fun prepareUpgrade(player: Player, buildingId: UUID): CompletableFuture<Unit>
    fun rotatePreview(player: Player, degrees: Int = 90): CompletableFuture<Unit>
    fun movePreview(player: Player): CompletableFuture<Unit>
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
