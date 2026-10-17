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
)

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
    fun rotatePreview(player: Player): CompletableFuture<Unit>
    fun movePreview(player: Player): CompletableFuture<Unit>
    fun confirmPreview(player: Player): CompletableFuture<Unit>
    fun cancelPreview(player: Player)
    fun recruit(player: Player): CompletableFuture<Unit>
    fun assign(player: Player, workerId: UUID, buildingId: UUID?): CompletableFuture<Unit>
    fun addMember(player: Player, name: String): CompletableFuture<Unit>
    fun removeMember(player: Player, memberId: UUID): CompletableFuture<Unit>
    fun retryConstruction(player: Player, cityId: UUID): CompletableFuture<Unit>
    fun reload(player: Player): CompletableFuture<Unit>
}
