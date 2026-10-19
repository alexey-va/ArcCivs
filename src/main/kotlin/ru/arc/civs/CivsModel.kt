package ru.arc.civs

import java.util.UUID

enum class Resource { WOOD, STONE, FOOD }
enum class BuildingType { TOWN_HALL, BUILDER_HUT, WAREHOUSE, HOUSE, FORESTER, QUARRY, FARM, SAWMILL, BLACKSMITH, STONEMASON, LIBRARY }
enum class BuildingPhase { SITE, CONSTRUCTING, ACTIVE }
enum class ConstructionPhase { CONSTRUCTING, READY_TO_PLACE, WAITING_MATERIALS, PLACING, PAUSED }

data class Stock(val wood: Long = 0, val stone: Long = 0, val food: Long = 0) {
    operator fun get(resource: Resource): Long = when (resource) {
        Resource.WOOD -> wood
        Resource.STONE -> stone
        Resource.FOOD -> food
    }
    fun with(resource: Resource, value: Long): Stock = when (resource) {
        Resource.WOOD -> copy(wood = value)
        Resource.STONE -> copy(stone = value)
        Resource.FOOD -> copy(food = value)
    }
    fun covers(cost: Stock): Boolean = wood >= cost.wood && stone >= cost.stone && food >= cost.food
    operator fun minus(cost: Stock) = Stock(wood - cost.wood, stone - cost.stone, food - cost.food)
}

data class CivPosition(val x: Int, val y: Int, val z: Int)
data class CivBox(val min: CivPosition, val max: CivPosition) {
    fun contains(x: Int, y: Int, z: Int): Boolean = x in min.x..max.x && y in min.y..max.y && z in min.z..max.z
    fun overlaps(other: CivBox): Boolean = min.x <= other.max.x && max.x >= other.min.x &&
        min.y <= other.max.y && max.y >= other.min.y && min.z <= other.max.z && max.z >= other.min.z
}

data class CivBuilding(
    val id: UUID,
    val type: BuildingType,
    val level: Int,
    val anchor: CivPosition,
    val rotation: Int,
    val schematicId: String,
    val bounds: CivBox,
    val entrance: CivPosition,
    val phase: BuildingPhase,
    val control: CivPosition? = null,
    val production: String? = null,
)

data class CivWorker(
    val id: UUID,
    val name: String,
    val assignment: UUID?,
    val completedCycles: Long = 0,
    val nextDeliveryAt: Long = 0,
    /** Null fields preserve pre-resident snapshots without granting items or invented experience. */
    val health: Double? = null,
    val skills: Map<String, Long>? = null,
    val inventory: Map<String, Long>? = null,
    /** A separately journalled shipment; these items have already left their source container. */
    val cargo: CivCargo? = null,
)

/** Captured placement bytes live under this order's UUID, independently of mutable source files. */
data class CivConstruction(
    val id: UUID,
    val buildingId: UUID,
    val targetLevel: Int,
    val workRequired: Int,
    val workDone: Int,
    val phase: ConstructionPhase,
    val cost: Stock,
    val failure: String? = null,
    val plannedBuilding: CivBuilding? = null,
    val batches: CivConstructionBatches? = null,
    val materials: Map<String, Long>? = null,
    /** Null identifies an old order whose complete price was already paid. */
    val payment: CivConstructionPayment? = null,
    /** Remaining rows authorized by observed builder sessions; null is a legacy single-row session. */
    val workCredit: Int? = null,
)

data class CivConstructionPayment(val reservedBatch: Int? = null, val awaiting: Map<String, Long>? = null)

/** The city commit protects the target before this one-time native block grant is applied. */
data class CivControlPlacement(val buildingId: UUID, val position: CivPosition, val before: String,
    val approach: List<CivPlanBlock>? = null)

/** A durable remaining grant. Null in legacy snapshots means the pioneers already arrived. */
data class CivImmigration(val remaining: Int, val nextArrivalAt: Long)

/** Null buildingId retires the whole city. Native control cleanup follows this durable intent. */
data class CivDeletion(val buildingId: UUID?)

data class Settlement(
    val id: UUID,
    val owner: UUID,
    val name: String,
    val worldId: UUID,
    val center: CivPosition,
    val radius: Int,
    val style: String,
    val stock: Stock,
    val buildings: List<CivBuilding>,
    val workers: List<CivWorker>,
    val members: Set<UUID> = emptySet(),
    val construction: CivConstruction? = null,
    val revision: Long = 0,
    val createdAt: Long = 0,
    val inventory: CivInventory? = null,
    val immigration: CivImmigration? = null,
    val pendingControls: List<CivControlPlacement>? = null,
    val constructionQueue: List<UUID>? = null,
    val pendingDeletion: CivDeletion? = null,
) {
    fun canManage(player: UUID): Boolean = player == owner || player in members
    fun building(id: UUID): CivBuilding? = buildings.firstOrNull { it.id == id }
    fun contains(x: Int, z: Int): Boolean = kotlin.math.abs(x.toLong() - center.x) <= radius &&
        kotlin.math.abs(z.toLong() - center.z) <= radius
    fun level(): Int = buildings.firstOrNull { it.type == BuildingType.TOWN_HALL }?.level?.coerceAtLeast(1) ?: 1
}

/** Domain rejection keys are localized by the UI; no platform side effects happen in rules. */
class CivsRejection(val reason: String, val values: Map<String, String> = emptyMap()) : IllegalArgumentException(reason)

data class BuildingOffer(val type: BuildingType, val level: Int, val schematicId: String, val cost: Stock)

/** World coordinates; before and after are immutable canonical Bukkit block-data strings. */
data class CivPlanBlock(val position: CivPosition, val before: String, val after: String)
data class CivPlacementPlan(
    val id: UUID,
    val cityId: UUID,
    val worldId: UUID,
    val building: CivBuilding,
    val blocks: List<CivPlanBlock>,
    val capturedSha256: String? = null,
)
