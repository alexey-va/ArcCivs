package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.inventory.ItemStack
import java.util.UUID
import org.bukkit.block.TileState
import org.bukkit.plugin.Plugin
import ru.arc.civs.CivWorkCached as Cached
import ru.arc.civs.CivWorkNeedState as Need

/** One observed resident action, committed by the existing item/world receipt; never a background resource grant. */
data class CivWorldWork(
    val workerId: UUID, val cycle: Long, val buildingId: UUID, val standing: CivPosition,
    val changes: List<CivPlanBlock>, val inputs: Map<String, Long> = emptyMap(),
    val outputs: Map<String, Long> = emptyMap(), val durationMillis: Long = 3_000,
    val status: String, val miningStep: Long? = null,
    val equipmentAfter: List<CivResidentItem?>? = null,
    val focus: CivPosition? = null,
    val miningPlanVersion: Int? = null,
    val miningGenerator: Boolean = false,
    /** Already inspected passage prefix, distinct from the cursor this receipt will commit. */
    val miningAccessStep: Long? = null,
    val forestry: CivForestryProgress? = null,
    val upkeep: CivRoadUpkeep? = null,
    /** A paid Auto worksite's native subaction; never a resource, item, skill or worker-cycle receipt. */
    val autonomousVisual: Boolean = false,
    /** Explicit degraded approach after ordinary access fails; goods and native changes stay unchanged. */
    val remoteWork: Boolean = false,
    /** Sparse V5 proof; null is the exact legacy linear-receipt representation. */
    val miningSections: Map<String, Long>? = null,
    val miningAccessSections: Map<String, Long>? = null,
    /** Immutable zero-based plan target; prerequisite/sealing work does not complete it. */
    val miningOrdinal: Int? = null,
    /** Exact AIR shell faces hidden by this cut; the persisted excavation repairs them before another cut. */
    val miningDeferredSeals: Set<CivPosition>? = null,
    /** Exact local origin of a paid frontier seal, independent of the currently selected section. */
    val miningFrontier: CivPosition? = null,
    val miningExcavation: CivPosition? = null,
    /** Exact untouched reachable rock face for an authorized one-cell adjacent lava clearance. */
    val miningFrontierBefore: String? = null,
) {
    val cropCare: Boolean get() = status == "watering" || status == "fertilizing"

    // Equipment-only actions explicitly pick up goods from storage; ordinary work uses carried supplies first.
    fun storageInputs(worker: CivWorker): Map<String, Long> = if (equipmentAfter != null && changes.isEmpty()) inputs
        else CivsEquipment.storageInputs(worker, inputs)
}

internal data class CivWorkCached(val building: CivBuilding, val worker: CivWorker, val work: CivWorldWork)
internal data class CivWorkNeedState(val building: CivBuilding, val cycle: Long, val status: String,
        val material: String? = null, val amount: Long = 1, val point: CivPosition? = null,
        val reason: String? = null, val blockMaterial: String? = null)

/** Shared owner-thread discovery budget, admitted work and native read authority. */
internal class CivsWorksiteContext(
    val plugin: Plugin,
    val worldAccess: CivsWorld,
    val stock: (Settlement, UUID) -> Map<String, Long>,
    val locked: (UUID, CivPosition) -> Boolean,
    val forestryPaths: (Settlement) -> Set<Pair<Int, Int>>,
    val nanoTime: () -> Long,
) {
    lateinit var professionTypes: Set<BuildingType>
    val discoveryBudget = CivWorkDiscoveryBudget { nanoTime() }
    var spotChecksRemaining = 0
    var spotRaysRemaining = 0
    var mineChecksRemaining = 0
    var spotSearchPending = false
    var mutationBlocked: (UUID, CivPosition) -> Boolean = { _, _ -> false }
    var residentFeet: (UUID) -> Location? = { null }
    var forestrySurplus: (Settlement, String) -> Boolean = { _, _ -> false }
    val cached = hashMapOf<UUID, Cached>()
    val needs = hashMapOf<UUID, Need>()

    fun plan(city: Settlement, worker: CivWorker): CivWorldWork? {
        val building = worker.assignment?.let(city::building) ?: return null
        return cached[worker.id]?.takeIf { cachedMatches(city, worker, building, it) }?.work
    }

    fun cachedMatches(city: Settlement, worker: CivWorker, building: CivBuilding, it: Cached): Boolean =
        it.worker.completedCycles == worker.completedCycles && it.building == building &&
            it.worker.items == worker.items && it.worker.inventory == worker.inventory &&
            (!CivsAutonomy.legacyStock(city) || building.type != BuildingType.FORESTER || it.work.forestry != null || CivsForestry.care(it.work) ||
                city.stock.wood > 0 || building.id.toString() !in city.forestrySeeded.orEmpty()) &&
            (it.work.forestry?.job?.let { job -> city.forestryJobs?.get(building.id.toString())?.let { saved -> saved == job }
                ?: (job.next == 0 && job.seedDeferred == (!job.thinning && CivsAutonomy.legacyStock(city) && city.stock.wood == 0L)) } != false)

    fun currentNeed(city: Settlement, worker: CivWorker): Need? = needs[worker.id]?.takeIf {
        it.building == worker.assignment?.let(city::building) && it.cycle == worker.completedCycles
    }

    fun blocked(worker: CivWorker, status: String, material: String? = null, amount: Long = 1,
        point: CivPosition? = null, reason: String? = null, blockMaterial: String? = null): CivWorldWork? {
        needs[worker.id] = needs.getValue(worker.id).copy(status = status, material = material, amount = amount,
            point = point, reason = reason, blockMaterial = blockMaterial)
        return null
    }

    fun read(city: Settlement, building: CivBuilding, world: World, point: CivPosition, allowOwn: Boolean = true,
        worker: CivWorker? = null, reserved: Set<CivPosition> = emptySet(), observeBuilding: Boolean = false): Block? {
        fun denied(status: String, reason: String, material: Material? = null): Block? {
            if (worker != null) blocked(worker, status, point = point, reason = reason, blockMaterial = material?.name)
            return null
        }
        if (!city.contains(point.x, point.z)) return denied("worksite-protected", "outside-city")
        if (point.y !in world.minHeight until world.maxHeight) return denied("worksite-protected", "outside-world-height")
        if (!world.isChunkLoaded(point.x shr 4, point.z shr 4)) return denied("worksite-unloaded", "chunk-unloaded")
        if (point !in reserved && locked(city.worldId, point)) return denied("worksite-protected", "world-operation-locked")
        if (!observeBuilding && civsProtectedBuildings(city).any { (!allowOwn || it.id != building.id) && worksiteProtectedByBuilding(building, it, point) })
            return denied("worksite-protected", "other-building")
        val block = world.getBlockAt(point.x, point.y, point.z)
        if (!observeBuilding && block.getState(false) is TileState) return denied("worksite-obstacle", "block-entity", block.type)
        if (!world.worldBorder.isInside(block.location)) return denied("worksite-protected", "world-border", block.type)
        if (!worldAccess.canModify(city.owner, block)) return denied("worksite-protected", "protection-denied", block.type)
        return block
    }

    fun equipTool(city: Settlement, worker: CivWorker, building: CivBuilding, world: World, suffix: String): CivWorldWork? {
        val slots = CivsEquipment.slots(worker)
        if (slots[4]?.material?.endsWith(suffix) == true) return null
        val stock = stock(city, building.id)
        val material = listOf("IRON", "COPPER", "STONE", "WOODEN", "DIAMOND", "NETHERITE", "GOLDEN")
            .map { it + suffix }.firstOrNull { (stock[it] ?: 0) > 0 } ?: return null
        val next = slots.toMutableList()
        next[4]?.let { previous ->
            val free = (CivsEquipment.BAG until CivsEquipment.SLOTS).firstOrNull { next[it] == null } ?: return null
            next[free] = previous
        }
        next[4] = CivResidentItem(material, 1)
        val standing = citizenStorageTarget(city, building, world) ?: return null
        return work(worker, building, CivWorkApproach(standing), emptyList(), mapOf(material to 1), status = "waiting-tool", duration = 1_000)
            .copy(equipmentAfter = next)
    }

    fun work(worker: CivWorker, building: CivBuilding, approach: CivWorkApproach, changes: List<CivPlanBlock>,
        inputs: Map<String, Long> = emptyMap(), outputs: Map<String, Long> = emptyMap(), status: String,
        duration: Long = 3_000, miningStep: Long? = null, focus: CivPosition? = null, miningGenerator: Boolean = false,
        miningAccessStep: Long? = miningStep, toolUses: Int? = null,
        mineExecution: CivMineExecution? = null, mineScan: CivMineSafetyScan? = null): CivWorldWork {
        val standing = approach.standing
        val execution = mineExecution?.takeIf { miningStep != null && building.type == BuildingType.QUARRY }
        val geometry = execution?.let { CivsMinePlan.forBuilding(building) }
        val accessSections = execution?.sections?.toMap()
        val completedSections = if (execution != null && geometry != null && status != "securing-mine" &&
            !miningGenerator && miningStep == execution.ordinal.toLong() + 1)
            geometry.advanceSection(execution.ordinal, requireNotNull(accessSections)) else accessSections
        val completedPrefix = if (execution != null && geometry != null)
            geometry.normalizedProgress(execution.basePrefix, requireNotNull(completedSections)).first else miningStep
        val slots = CivsEquipment.slots(worker)
        val hand = slots[4]?.stack()
        val meta = hand?.itemMeta as? org.bukkit.inventory.meta.Damageable
        val after = if (meta != null && (changes.isNotEmpty() || toolUses != null) && status != "watering" && status != "fertilizing" &&
            status != "planting-tree" && !meta.isUnbreakable) {
            val unbreaking = hand.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.UNBREAKING).coerceAtLeast(0)
            val uses = toolUses ?: changes.count { !Bukkit.createBlockData(it.before).material.isAir }
            val wear = (0 until uses).count { java.util.concurrent.ThreadLocalRandom.current().nextLong(unbreaking.toLong() + 1) == 0L }
            val damage = meta.damage + wear
            val worn = if (damage >= hand.type.maxDurability.toInt()) null else {
                meta.damage = damage
                hand.itemMeta = meta
                CivResidentItem.of(hand)
            }
            slots.toMutableList().also { it[4] = worn }
        } else null
        return CivWorldWork(worker.id, worker.completedCycles, building.id, standing, changes, inputs, outputs,
            if (status == "working-field") 1_500 else duration, status, completedPrefix, after,
            focus ?: approach.focus ?: changes.minByOrNull { cell ->
                val dx = cell.position.x.toDouble() - standing.x
                val dy = cell.position.y.toDouble() - standing.y
                val dz = cell.position.z.toDouble() - standing.z
                dx * dx + dy * dy + dz * dz
            }?.position, miningPlanVersion = miningStep?.let { CivsMinePlan.VERSION }, miningGenerator = miningGenerator,
            miningAccessStep = execution?.prefix ?: miningAccessStep, remoteWork = approach.remoteWork,
            miningSections = completedSections, miningAccessSections = accessSections, miningOrdinal = execution?.ordinal,
            miningDeferredSeals = execution?.let { mineScan?.deferredAirWalls?.takeIf { it.isNotEmpty() }?.toSet() },
            miningFrontier = execution?.let { mineScan?.target },
            miningExcavation = execution?.let { if (status == "securing-mine") mineScan?.excavation
                else it.unsealedExcavation ?: mineScan?.excavation })
    }

}

internal fun worksiteProtectedByBuilding(workplace: CivBuilding, other: CivBuilding, point: CivPosition, headroom: Int = 0): Boolean =
    if (workplace.type == BuildingType.FORESTER) other.bounds.contains(point.x, point.y, point.z) ||
        CivsWorksiteLayouts.field(other).any { it.x == point.x && it.z == point.z && point.y in it.y..it.y + 1 }
    else (point.x in other.bounds.min.x..other.bounds.max.x && point.z in other.bounds.min.z..other.bounds.max.z ||
        CivsWorksiteLayouts.isField(other, point.x, point.z)) &&
        (workplace.type != BuildingType.QUARRY || !CivsMinePlan.belowFoundation(other, point.y, headroom))

internal fun requireWorksite(condition: Boolean, reason: String) { if (!condition) throw CivsRejection(reason) }
