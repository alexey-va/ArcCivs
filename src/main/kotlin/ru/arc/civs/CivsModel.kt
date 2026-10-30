package ru.arc.civs

import java.util.UUID

enum class Resource { WOOD, STONE, FOOD }
enum class BuildingType {
    TOWN_HALL, BUILDER_HUT, WAREHOUSE, HOUSE, FORESTER, QUARRY, FARM, SAWMILL, BLACKSMITH, STONEMASON, LIBRARY,
    TAVERN, BAKERY, GUARD_TOWER, WALL, GATE, ROAD, PLAN_BOARD, UNIVERSITY, SMELTERY, CRUSHER, DECORATION;

    val isInfrastructure: Boolean get() = this == WALL || this == GATE || this == ROAD
    val isResidence: Boolean get() = this == HOUSE || this == TAVERN
    val isWorldWork: Boolean get() = this == FARM || this == FORESTER || this == QUARRY
    val maxLevel: Int get() = if (isInfrastructure || this == PLAN_BOARD || this == DECORATION) 1 else 3
}
enum class BuildingPhase { SITE, CONSTRUCTING, ACTIVE }
enum class ConstructionPhase { CONSTRUCTING, READY_TO_PLACE, WAITING_MATERIALS, PLACING, PAUSED }
enum class CivSex { MALE, FEMALE }

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
    /** Explicit public walking columns of a constructed road or gate. Native collisions still apply. */
    val transit: List<CivPosition>? = null,
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
    /** Accepted native items or closed autonomous stock, already debited from its source. */
    val cargo: CivCargo? = null,
    /** Six equipment slots followed by 27 bag slots; null migrates the old plain personal store. */
    val items: List<CivResidentItem?>? = null,
    /** Nullable additions keep old snapshots readable; migration never changes an existing name or skin. */
    val sex: CivSex? = null,
    val homeId: UUID? = null,
    val parentIds: Set<UUID>? = null,
    /** Null means adult. Only active city minutes advance a child's age from zero to adulthood. */
    val childMinutes: Int? = null,
    /** Twenty is fully fed. Legacy null is fully fed, without granting food items. */
    val hunger: Int? = null,
    /** A real ground pickup requests a home visit; quantities remain solely in the native bag. */
    val hasCollectedItems: Boolean? = null,
    /** Earned autonomous goods waiting for a courier; never native or extractable items. */
    val pendingStock: Stock? = null,
    /** Small observed life history; missing keeps pre-life JSON semantically unchanged. */
    val life: CivResidentLife? = null,
)

/** A stable pair occupies one house. Progress records active minutes, never wall-clock catch-up. */
data class CivFamily(val firstParentId: UUID, val secondParentId: UUID, val homeId: UUID, val activeMinutes: Int)

/** A dead resident's exact belongings remain lootable until this durable expiry. */
data class CivCorpse(
    val worker: CivWorker,
    val position: CivPosition,
    val diedAt: Long,
    val expiresAt: Long,
    val killerName: String?,
    val killerType: String?,
    val cause: String,
    val feetY: Double? = null,
)

data class CivDeathContext(val position: CivPosition, val at: Long,
    val killerName: String? = null, val killerType: String? = null, val cause: String = "CUSTOM",
    val feetY: Double? = null)

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
    val blocksRequired: Int? = null,
    val blocksPlaced: Int? = null,
    /** Saved earthwork cursor and prepared entrance; resident construction stance is selected separately. */
    val preparationWorkplace: CivPosition? = null,
    /** Free, journaled manual foundation rows run before the builder can reach the raised entrance. */
    val automaticPreparationBatches: Int? = null,
)

data class CivConstructionPayment(val reservedBatch: Int? = null, val awaiting: Map<String, Long>? = null)

/** The city commit protects the target before this one-time native block grant is applied. */
data class CivControlPlacement(val buildingId: UUID, val position: CivPosition, val before: String,
    val approach: List<CivPlanBlock>? = null)

/** Next recurring immigration admission; remaining is the current shortfall below five residents. */
data class CivImmigration(val remaining: Int, val nextArrivalAt: Long)

/** Null buildingId retires the whole city. Native control cleanup follows this durable intent. */
data class CivDeletion(val buildingId: UUID?)

/** Versioned physical construction cursor; legacyStep records the untouched pre-cutover ordinal. */
data class CivMinePlanState(val version: Int, val nextStep: Long, val legacyStep: Long? = null,
    /** V2's exact saved prefix is retained when a first real V3 receipt starts its independent cursor. */
    val v2Step: Long? = null,
    /** V3's offset-shaft ordinal remains provenance when V4 starts its own single-shaft plan. */
    val v3Step: Long? = null,
    /** Completed V4 geometry remains an escape/protection prefix after V5 starts. */
    val v4Step: Long? = null,
    /** Exact section-local prefixes outside nextStep; missing keeps old receipts unchanged. */
    val sectionSteps: Map<String, Long>? = null,
    /** Last real excavation, rechecked after restart before another opening. Schematic-local. */
    val excavation: CivPosition? = null,
    /** Outstanding paid safety blocks at future plan cuts, consumed by their actual removal. */
    val seals: Map<String, String>? = null,
    /** Frozen V5 linear prefix when its first sparse receipt is admitted. */
    val v5Step: Long? = null,
    /** Exact paid geometry from at most two lower building levels; never a current-level prefix. */
    val levels: List<CivMineLevelWitness>? = null,
    /** Exact same-level V5 authority, frozen only by the first real V6 receipt. */
    val v5Proof: CivMineV5Proof? = null,
    /** Exact same-level V6 authority; V7 appends a lower floor without claiming its unfinished branches. */
    val v6Proof: CivMineV6Proof? = null)

/** One bounded predecessor, never a recursive plan history or another building level. */
data class CivMineV5Proof(val nextStep: Long, val sectionSteps: Map<String, Long>? = null,
    val excavation: CivPosition? = null, val seals: Map<String, String>? = null)

/** Flat predecessor; older V5 authority remains in its existing sibling field. */
data class CivMineV6Proof(val nextStep: Long, val sectionSteps: Map<String, Long>? = null,
    val excavation: CivPosition? = null, val seals: Map<String, String>? = null)

/** Nonrecursive old-level proof, including the bounded flat V5 and V6 version witnesses. */
data class CivMineLevelWitness(val building: CivBuilding, val version: Int?, val nextStep: Long,
    val legacyStep: Long? = null, val v2Step: Long? = null, val v3Step: Long? = null, val v4Step: Long? = null,
    val sectionSteps: Map<String, Long>? = null, val excavation: CivPosition? = null,
    val seals: Map<String, String>? = null, val v5Step: Long? = null, val legacyProgress: Long? = null,
    val v5Proof: CivMineV5Proof? = null, val v6Proof: CivMineV6Proof? = null) {
    fun state(): CivMinePlanState? = version?.let {
        CivMinePlanState(it, nextStep, legacyStep, v2Step, v3Step, v4Step, sectionSteps, excavation, seals, v5Step,
            v5Proof = v5Proof, v6Proof = v6Proof)
    }
}

enum class CivSettlementSetting { CHUNK_LOADING, IMMORTAL_RESIDENTS, AUTO_CONSTRUCTION_MATERIALS }

/** Existing towns retain permission-controlled chunk loading; testing never turns on by migration. */
data class CivSettlementSettings(
    val chunkLoading: Boolean = true,
    val immortalResidents: Boolean = false,
    val autoConstructionMaterials: Boolean = false,
) {
    fun enabled(setting: CivSettlementSetting): Boolean = when (setting) {
        CivSettlementSetting.CHUNK_LOADING -> chunkLoading
        CivSettlementSetting.IMMORTAL_RESIDENTS -> immortalResidents
        CivSettlementSetting.AUTO_CONSTRUCTION_MATERIALS -> autoConstructionMaterials
    }
    fun with(setting: CivSettlementSetting, enabled: Boolean): CivSettlementSettings = when (setting) {
        CivSettlementSetting.CHUNK_LOADING -> copy(chunkLoading = enabled)
        CivSettlementSetting.IMMORTAL_RESIDENTS -> copy(immortalResidents = enabled)
        CivSettlementSetting.AUTO_CONSTRUCTION_MATERIALS -> copy(autoConstructionMaterials = enabled)
    }
}

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
    /** Legacy V1 ordinal retained for decoding and cutover audit; V2 never reinterprets it. */
    val mineProgress: Map<String, Long>? = null,
    val families: List<CivFamily>? = null,
    val memberNames: Map<String, String>? = null,
    val corpses: List<CivCorpse>? = null,
    val minePlans: Map<String, CivMinePlanState>? = null,
    val settings: CivSettlementSettings? = null,
    /** Null retains the player-city contract of every existing snapshot. */
    val autonomy: CivAutonomy? = null,
    /** One funded native tree per lumbermill; the cursor and exact crown survive restart. */
    val forestryJobs: Map<String, CivForestryJob>? = null,
    /** Auto mills have one initial planting kit included in their paid construction. */
    val forestrySeeded: Set<String>? = null,
    /** Null preserves pre-research settlements and grants neither discoveries nor products. */
    val research: CivResearchState? = null,
    /** Paid local road corrections; the original building placement journals stay immutable. */
    val roadRouteOverrides: Map<String, CivRoadRouteOverride>? = null,
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
/** Paid generated infrastructure fills this exact water column; the natural bed itself is never written. */
data class CivWaterFoundation(val x: Int, val z: Int, val floorY: Int, val waterTopY: Int, val bottomY: Int) {
    fun contains(position: CivPosition): Boolean = position.x == x && position.z == z &&
        position.y > bottomY && position.y <= floorY
}

/** Journaled earthworks precede the authored schematic; route positions are standing coordinates. */
data class CivTerraformPlan(
    val bounds: CivBox,
    val blocks: List<CivPlanBlock>,
    val route: List<CivPosition>,
    val groundY: Int,
    val cutCount: Int,
    val fillCount: Int,
    val waterFoundations: List<CivWaterFoundation>? = null,
)
data class CivPlacementPlan(
    val id: UUID,
    val cityId: UUID,
    val worldId: UUID,
    val building: CivBuilding,
    val blocks: List<CivPlanBlock>,
    val capturedSha256: String? = null,
    val terrain: CivTerraformPlan? = null,
    /** Explicit administrator founding keeps its selected point when the waiting site is recaptured. */
    val adminPlacement: Boolean = false,
)
