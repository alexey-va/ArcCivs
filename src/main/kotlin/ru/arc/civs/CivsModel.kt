package ru.arc.civs

import java.util.UUID

enum class Resource { WOOD, STONE, FOOD }
enum class BuildingType { TOWN_HALL, WAREHOUSE, HOUSE, FORESTER, QUARRY, FARM }
enum class BuildingPhase { CONSTRUCTING, ACTIVE }
enum class ConstructionPhase { CONSTRUCTING, READY_TO_PLACE, PLACING, PAUSED }

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
)

data class CivWorker(
    val id: UUID,
    val name: String,
    val assignment: UUID?,
    val completedCycles: Long = 0,
    val nextDeliveryAt: Long = 0,
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
)

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
