package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.TileState
import org.bukkit.block.data.Ageable
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.logging.Level
import kotlin.math.roundToInt

/** One observed physical action, committed by the existing item/world receipt. No timed resource grant. */
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
) {
    val cropCare: Boolean get() = status == "watering" || status == "fertilizing"

    // Equipment-only actions explicitly pick up goods from storage; ordinary work uses carried supplies first.
    fun storageInputs(worker: CivWorker): Map<String, Long> = if (equipmentAfter != null && changes.isEmpty()) inputs
        else CivsEquipment.storageInputs(worker, inputs)
}

/** Small live work cursor. Only real changed blocks and goods enter durable state, through CivsStorage. */
internal class CivsWorksites(private val plugin: Plugin, private val worldAccess: CivsWorld,
    private val stock: (Settlement, UUID) -> Map<String, Long>,
    private val locked: (UUID, CivPosition) -> Boolean,
    private val isPaused: (UUID) -> Boolean = { false },
    private val forestryPaths: (Settlement) -> Set<Pair<Int, Int>> = { emptySet() },
    private val nanoTime: () -> Long = System::nanoTime,
    private val settings: CivsWorksiteSettings = CivsWorksiteSettings()) {
    private data class Cached(val building: CivBuilding, val worker: CivWorker, val work: CivWorldWork)
    private val cached = hashMapOf<UUID, Cached>()
    private val cursors = hashMapOf<UUID, Int>()
    private data class FarmScan(val building: CivBuilding, val plots: List<CivPosition>,
        var next: Int = 0, var examined: Int = 0, var lastFocus: CivPosition? = null,
        val harvest: MutableSet<CivPosition> = linkedSetOf(), val field: MutableSet<CivPosition> = linkedSetOf(),
        val care: MutableSet<CivPosition> = linkedSetOf(), val dirty: MutableSet<CivPosition> = linkedSetOf()) {
        val columns = plots.associateBy { it.x to it.z }
        val chunks = plots.mapTo(hashSetOf()) { (it.x shr 4) to (it.z shr 4) }
    }
    private val farmScans = hashMapOf<UUID, FarmScan>()
    private val reportedFarmSelections = hashMapOf<UUID, Int>()
    private val reportedMineSelections = hashMapOf<UUID, Int>()
    private data class Need(val building: CivBuilding, val cycle: Long, val status: String,
        val material: String? = null, val amount: Long = 1, val point: CivPosition? = null,
        val reason: String? = null, val blockMaterial: String? = null)
    private val needs = hashMapOf<UUID, Need>()
    private data class WorkApproach(val standing: CivPosition, val remoteWork: Boolean = false,
        val focus: CivPosition? = null, val capturedWork: CivWorldWork? = null)
    private data class RemoteWorkProbe(var candidate: CivWorldWork? = null, var nextAim: Int = 0)
    private data class WorkSpotSearch(val buildingId: UUID, val target: CivPosition, val allowOwn: Boolean,
        val ladder: Boolean, val offsets: List<CivPosition>,
        var next: Int = 0, var nextAim: Int = 0, var sawStanding: Boolean = false,
        val remote: RemoteWorkProbe = RemoteWorkProbe(), var captured: Cached? = null,
        var ordinaryAttempted: Boolean = false, var remoteFinished: Boolean = false)
    private val workSpots = hashMapOf<UUID, WorkSpotSearch>()
    private data class WorkSpotKey(val building: CivBuilding, val step: Long?, val focus: CivPosition? = null)
    private data class RejectedWorkSpots(val key: WorkSpotKey, var lastFocus: CivPosition,
        val positions: LinkedHashMap<CivPosition, Long> = linkedMapOf(), var retryAt: Long? = null,
        var pending: Cached? = null, var checkedChanges: Int = 0, var remoteChecked: Boolean = false,
        val remote: RemoteWorkProbe = RemoteWorkProbe())
    private val rejectedWorkSpots = hashMapOf<UUID, RejectedWorkSpots>()
    private val deferredForestApproaches = hashMapOf<UUID, MutableMap<CivPosition, RejectedWorkSpots>>()
    private data class ReportedNeed(val need: Need, val tick: Int)
    private val reportedNeeds = hashMapOf<UUID, ReportedNeed>()
    private data class MineSearch(val city: UUID, val building: CivBuilding, val cycle: Long,
        val plan: CivMinePlanState?, val legacyStep: Long?, var activeTicks: Long = 0, var sampledAt: Long? = null,
        var warnedAt: Long? = null, val beganTick: Long = Bukkit.getCurrentTick().toLong(),
        var admissions: Long = 0, var target: CivPosition? = null, var targetSwitches: Long = 0)
    private val mineSearches = hashMapOf<UUID, MineSearch>()
    private data class ForestScan(val building: CivBuilding, val scan: CivsForestry.Scan, val roots: List<CivPosition>)
    private val forestScans = hashMapOf<UUID, ForestScan>()
    private data class ForestPlant(val building: CivBuilding, val roots: List<CivPosition>, val timber: String, var next: Int = 0)
    private val forestPlants = hashMapOf<UUID, ForestPlant>()
    private data class ForestRetry(val atTick: Long, val planting: Boolean)
    private data class ForestSearch(val building: CivBuilding, var saplings: Long, val columns: List<CivPosition>,
        var next: Int = 0, var y: Int? = null, var surface: Int? = null, var refresh: Int = 0,
        var column: CivPosition? = null, var scanningDirty: Boolean = false,
        val care: MutableSet<CivPosition> = linkedSetOf(), val planting: MutableSet<CivPosition> = linkedSetOf(),
        val mature: MutableSet<CivPosition> = linkedSetOf(), val unsuitable: MutableSet<CivPosition> = linkedSetOf(),
        val unfunded: MutableSet<CivPosition> = linkedSetOf(), val unplantable: MutableSet<CivPosition> = linkedSetOf(),
        val dirty: MutableSet<CivPosition> = linkedSetOf(), val retryRoots: MutableMap<CivPosition, ForestRetry> = linkedMapOf(),
        var retryAt: Long? = null, var plantNext: Boolean = false, var residentOrigin: CivPosition? = null) {
        val byColumn = columns.associateBy { it.x to it.z }
        val chunks = columns.mapTo(hashSetOf()) { (it.x shr 4) to (it.z shr 4) }
        fun remember(points: MutableSet<CivPosition>, point: CivPosition) {
            points += point
            if (points.size > columns.size) points.remove(points.first())
        }
        fun retry(point: CivPosition, planting: Boolean = false) {
            retryRoots[point] = ForestRetry(Bukkit.getCurrentTick().toLong() + 100, planting)
            if (retryRoots.size > columns.size) retryRoots.remove(retryRoots.keys.first())
        }
    }
    private val forestSearches = hashMapOf<UUID, ForestSearch>()
    private val workplaces = hashMapOf<UUID, Pair<UUID, CivBuilding>>()
    private val mineFrontierDirty = hashSetOf<UUID>()
    private val mineBounds = hashMapOf<UUID, CivBox>()
    private data class ZoneProtection(val world: UUID, val boundary: CivBox,
        val areas: Set<Pair<BuildingType, CivBox>>, val paths: Set<Pair<Int, Int>>)
    private val zoneProtections = hashMapOf<UUID, ZoneProtection>()
    private var forestChecksRemaining = 0
    private var forestDeadline = 0L
    private var spotChecksRemaining = 0
    private var spotRaysRemaining = 0
    private var mineChecksRemaining = 0
    private var columnsRemaining = 0
    private var lastAdmittedWorker: UUID? = null
    private var spotSearchPending = false
    private var cursor = 0
    internal var mutationBlocked: (java.util.UUID, CivPosition) -> Boolean = { _, _ -> false }
    internal var residentFeet: (UUID) -> Location? = { null }
    private val mineCursors = hashMapOf<UUID, Triple<Int, Long, Int>>()
    private data class MineExecution(val building: CivBuilding, val saved: CivMinePlanState?, val legacy: Long?,
        val basePrefix: Long, var prefix: Long, val sections: MutableMap<String, Long>,
        var selected: String? = null, var cursor: Int = 0, var ordinal: Int = 0, var checkedExcavation: Boolean = false,
        val retryAt: MutableMap<String, Long> = hashMapOf(), var lastDenial: Need? = null,
        var lastDeniedSection: String? = null,
        var unsealedExcavation: CivPosition? = null,
        val legacyBlocks: Map<CivPosition, String?> = emptyMap())
    private val mineExecutions = hashMapOf<UUID, MineExecution>()
    private val mineCovers = hashMapOf<UUID, CivMineCover>()
    private data class MineSafetyScan(val building: CivBuilding, val target: CivPosition, val progress: Long,
        val excavation: CivPosition?, val accessProgress: Long, val frontier: List<MineProtection>,
        val sections: Map<String, Long> = emptyMap(),
        var next: Int = 0, val deferredAirWalls: MutableSet<CivPosition> = hashSetOf())
    private val mineSafety = hashMapOf<UUID, MineSafetyScan>()
    private data class MineProtection(val exposed: CivPosition, val block: CivPosition, val material: Material?,
        val transferFooting: Boolean = false)

    fun plan(city: Settlement, worker: CivWorker): CivWorldWork? {
        val building = worker.assignment?.let(city::building) ?: return null
        return cached[worker.id]?.takeIf { cachedMatches(city, worker, building, it) }?.work
    }

    private fun cachedMatches(city: Settlement, worker: CivWorker, building: CivBuilding, it: Cached): Boolean =
        it.worker.completedCycles == worker.completedCycles && it.building == building &&
            it.worker.items == worker.items && it.worker.inventory == worker.inventory &&
            (city.autonomy == null || building.type != BuildingType.FORESTER || it.work.forestry != null || CivsForestry.care(it.work) ||
                city.stock.wood > 0 || building.id.toString() !in city.forestrySeeded.orEmpty()) &&
            (it.work.forestry?.job?.let { job -> city.forestryJobs?.get(building.id.toString())?.let { saved -> saved == job }
                ?: (job.next == 0 && job.seedDeferred == (!job.thinning && city.autonomy != null && city.stock.wood == 0L)) } != false)

    /** Owner cache only: does not revalidate terrain, consume scan budgets or expire exclusions. */
    internal fun debug(workerIds: Set<UUID>? = null, buildingIds: Set<UUID>? = null): Map<String, Any?> {
        check(Bukkit.isPrimaryThread())
        val tick = Bukkit.getCurrentTick().toLong()
        fun point(p: CivPosition?) = p?.let { "${it.x},${it.y},${it.z}" }
        fun <T> sample(values: Collection<T>, describe: (T) -> Any?): Map<String, Any?> = mapOf(
            "total" to values.size, "truncated" to (values.size > 8), "entries" to values.take(8).map(describe))
        val ids = (cached.keys + needs.keys + workSpots.keys + farmScans.keys + mineSafety.keys +
            rejectedWorkSpots.keys + cursors.keys + mineSearches.keys + forestSearches.keys + forestScans.keys +
            forestPlants.keys + deferredForestApproaches.keys).filter { workerIds == null || it in workerIds }
        val buildings = buildingIds ?: ids.mapNotNullTo(hashSetOf()) { id -> cached[id]?.building?.id ?: needs[id]?.building?.id ?:
            workSpots[id]?.buildingId ?: farmScans[id]?.building?.id ?: mineSafety[id]?.building?.id ?:
            rejectedWorkSpots[id]?.key?.building?.id ?: forestSearches[id]?.building?.id ?:
            forestScans[id]?.building?.id ?: forestPlants[id]?.building?.id }
        return sample(ids) { id ->
            val item = cached[id]
            val need = needs[id]
            val spot = workSpots[id]
            val farm = farmScans[id]
            val safety = mineSafety[id]
            val rejected = rejectedWorkSpots[id]
            mapOf("worker" to id.toString(), "cursor" to cursors[id],
                "cachedWork" to item?.work?.let { work -> mapOf("building" to work.buildingId.toString(),
                    "cycle" to work.cycle, "status" to work.status, "focus" to point(citizenWorkFocus(work)),
                    "standing" to point(work.standing), "miningStep" to work.miningStep,
                    "minePlanVersion" to work.miningPlanVersion, "accessStep" to work.miningAccessStep,
                    "generator" to work.miningGenerator,
                    "remoteWork" to work.remoteWork,
                    "forestry" to work.forestry?.let { mapOf("id" to it.job.id.toString(), "next" to it.job.next,
                        "until" to it.next, "cells" to it.job.cells.size, "roots" to it.job.roots.size,
                        "harvestedLogs" to it.job.harvestedLogs, "seedDeferred" to it.job.seedDeferred) },
                    "changes" to sample(work.changes) { mapOf("position" to point(it.position),
                        "before" to it.before, "after" to it.after) },
                    "inputs" to sample(work.inputs.entries) { mapOf("material" to it.key, "amount" to it.value) },
                    "outputs" to sample(work.outputs.entries) { mapOf("material" to it.key, "amount" to it.value) }) },
                "need" to need?.let { mapOf("building" to it.building.id.toString(), "cycle" to it.cycle,
                    "status" to it.status, "reason" to it.reason, "material" to it.material,
                    "amount" to it.amount, "point" to point(it.point), "blockMaterial" to it.blockMaterial) },
                "lastStatusAgeTicks" to reportedNeeds[id]?.let { (tick - it.tick).coerceAtLeast(0) },
                "mineSearch" to mineSearches[id]?.let { mapOf("activeTicks" to it.activeTicks, "activeMillis" to it.activeTicks * 50,
                    "lastWarningActiveMillis" to it.warnedAt?.times(50), "lastSampleTick" to it.sampledAt) },
                "discovery" to mineSearches[id]?.let { mapOf("beganTick" to it.beganTick,
                    "elapsedTicks" to (tick - it.beganTick), "activeTicks" to it.activeTicks,
                    "ownerAdmissions" to it.admissions, "targetSwitches" to it.targetSwitches, "target" to point(it.target)) },
                "pendingApproach" to rejected?.pending?.let { mapOf("focus" to point(citizenWorkFocus(it.work)),
                    "status" to it.work.status, "checkedChanges" to rejected.checkedChanges,
                    "changes" to it.work.changes.size, "forestryJob" to it.work.forestry?.job?.id?.toString()) },
                "deferredForestApproaches" to sample(deferredForestApproaches[id].orEmpty().values) {
                    mapOf("focus" to point(it.lastFocus), "rejectedStances" to it.positions.size) },
                "candidateSearch" to spot?.let { mapOf("building" to it.buildingId.toString(),
                    "target" to point(it.target), "candidate" to it.next, "aim" to it.nextAim,
                    "sawStanding" to it.sawStanding, "ladder" to it.ladder, "allowOwn" to it.allowOwn) },
                "farmScan" to farm?.let { mapOf("building" to it.building.id.toString(), "next" to it.next,
                    "examined" to it.examined, "plots" to it.plots.size, "harvest" to it.harvest.size,
                    "field" to it.field.size, "care" to it.care.size, "lastFocus" to point(it.lastFocus)) },
                "forestSearch" to forestSearches[id]?.let { mapOf("building" to it.building.id.toString(),
                    "column" to it.next, "columns" to it.columns.size, "refreshColumn" to it.refresh,
                    "dirtyColumns" to it.dirty.size, "mature" to it.mature.size,
                    "unsuitable" to it.unsuitable.size, "unplantable" to it.unplantable.size, "retryRoots" to it.retryRoots.size, "y" to it.y, "surface" to it.surface, "saplings" to it.saplings,
                    "care" to it.care.size, "planting" to it.planting.size, "retryAtTick" to it.retryAt) },
                "forestScan" to forestScans[id]?.let { mapOf("building" to it.building.id.toString(),
                    "root" to point(it.scan.root), "roots" to it.roots.size, "phase" to it.scan.phase,
                    "examined" to it.scan.examined, "pending" to it.scan.pending, "cells" to it.scan.size,
                    "groundedRoots" to it.scan.groundedRoots, "retainedCrownReason" to it.scan.retainedCrownReason,
                    "failureReason" to it.scan.failureReason, "failurePoint" to point(it.scan.failurePoint)) },
                "forestPlant" to forestPlants[id]?.let { mapOf("building" to it.building.id.toString(),
                    "roots" to it.roots.map(::point), "timber" to it.timber, "checkedColumns" to it.next) },
                "mineSafety" to safety?.let { mapOf("building" to it.building.id.toString(),
                    "target" to point(it.target), "progress" to it.progress, "next" to it.next,
                    "excavation" to point(it.excavation), "deferredAirWalls" to it.deferredAirWalls.size,
                    "nextTask" to it.frontier.getOrNull(it.next)?.let { task -> mapOf(
                        "point" to point(CivsWorksiteLayouts.point(it.building, task.block)),
                        "placement" to task.material?.name) }) },
                "failedStances" to rejected?.let { state -> mapOf("building" to state.key.building.id.toString(),
                    "focus" to point(state.lastFocus), "miningStep" to state.key.step,
                    "exhausted" to (state.retryAt != null), "retryAtTick" to state.retryAt,
                    "positions" to sample(state.positions.entries) { mapOf("standing" to point(it.key),
                        "rejectedAtTick" to it.value) }) })
        } + mapOf("source" to "owner-cache", "tick" to tick,
            "remainingPulseBudget" to mapOf("scope" to "shared-service-pulse", "lastAdmittedWorker" to lastAdmittedWorker?.toString(),
                "standingChecks" to spotChecksRemaining, "columns" to columnsRemaining,
                "rays" to spotRaysRemaining, "mineChecks" to mineChecksRemaining, "forestWorkUnits" to forestChecksRemaining,
                "searchPending" to spotSearchPending),
            "mineCursors" to sample(mineCursors.entries.filter { workerIds == null || it.key in buildings }) {
                mapOf("building" to it.key.toString(), "level" to it.value.first,
                    "committedStep" to it.value.second, "scanStep" to it.value.third) })
    }

    /** Discard a receipt, not the worker's knowledge of its assigned zone. */
    fun invalidate(workerId: UUID, completedWork: CivWorldWork? = null) {
        val previous = cached.remove(workerId)
        if (completedWork == null && previous == null) {
            // Explicitly retiring a blocked observation rechecks that exact section once.
            // Other backoffs, admitted section knowledge and paid progress remain intact.
            workplaces[workerId]?.second?.takeIf { it.type == BuildingType.QUARRY }?.let { building ->
                mineExecutions[building.id]?.let { execution ->
                    execution.lastDeniedSection?.takeIf { it in CivsMinePlan.forBuilding(building)?.sections.orEmpty() }?.let { section ->
                        execution.retryAt.remove(section)
                        execution.selected = section
                    }
                }
            }
        }
        needs.remove(workerId); workSpots.remove(workerId)
        forestScans.remove(workerId); forestPlants.remove(workerId)
        if (completedWork != null) {
            completedWork.changes.forEach { cell ->
                workplaces[workerId]?.first?.let { changed(it, cell.position) }
            }
            val building = workplaces[workerId]?.second ?: previous?.building
            if (building?.type == BuildingType.QUARRY && completedWork.miningStep != null) {
                mineCursors[building.id]?.let { cursor ->
                    mineCursors[building.id] = Triple(building.level, completedWork.miningStep,
                        maxOf(cursor.third, completedWork.miningStep.toInt()))
                }
                if (completedWork.status != "securing-mine") {
                    mineFrontierDirty += workerId
                    mineSafety.remove(workerId)
                }
            }
            forestSearches[workerId]?.let { search ->
                search.plantNext = completedWork.status != "planting-tree"
                completedWork.forestry?.job?.roots?.forEach { search.mature.remove(it) }
            }
        }
    }

    /** Main-thread native changes invalidate only overlapping observations and approach failures. */
    internal fun changed(worldId: UUID, point: CivPosition) {
        check(Bukkit.isPrimaryThread())
        mineCovers.entries.removeIf { (_, cover) -> cover.world == worldId && cover.x == point.x && cover.z == point.z &&
            point.y >= (cover.ground ?: cover.next) }
        workplaces.forEach { (workerId, place) ->
            if (place.first != worldId) return@forEach
            val building = place.second
            if (mineBounds[building.id]?.contains(point.x, point.y, point.z) == true)
                mineExecutions[building.id]?.let { execution ->
                    // Reopen deferred sections without preempting an admitted safe branch.
                    execution.retryAt.clear()
                }
            farmScans[workerId]?.let { scan ->
                scan.columns[point.x to point.z]?.let { scan.dirty += it }
            }
            forestSearches[workerId]?.let { search ->
                search.byColumn[point.x to point.z]?.let { search.dirty += it }
                fun nearby(root: CivPosition) = kotlin.math.abs(root.x - point.x) <= CivsForestry.MAX_RADIUS &&
                    kotlin.math.abs(root.z - point.z) <= CivsForestry.MAX_RADIUS &&
                    point.y in root.y - 1..root.y + CivsForestry.MAX_HEIGHT
                search.unsuitable.removeIf(::nearby)
                search.retryRoots.keys.removeIf(::nearby)
                search.unplantable.removeIf(::nearby)
                search.unfunded.removeIf(::nearby)
                forestScans[workerId]?.takeIf { nearby(it.scan.root) }?.let { forestScans.remove(workerId) }
                forestPlants[workerId]?.takeIf { it.roots.any(::nearby) }?.let { it.next = 0 }
            }
            mineSafety[workerId]?.let { scan ->
                val index = scan.frontier.indexOfFirst { task ->
                    point == CivsWorksiteLayouts.point(building, task.block) || point == CivsWorksiteLayouts.point(building, task.exposed)
                }
                if (index >= 0) scan.next = minOf(scan.next, index)
            }
            fun affectsApproach(rejected: RejectedWorkSpots): Boolean {
                val reach = if (building.type == BuildingType.QUARRY) 6 else 4
                fun nearby(p: CivPosition, radius: Int) = kotlin.math.abs(p.x - point.x) <= radius &&
                    kotlin.math.abs(p.z - point.z) <= radius && point.y in p.y - 3..p.y + 3
                return nearby(rejected.lastFocus, reach) || rejected.positions.keys.any { nearby(it, 1) }
            }
            refreshRejected(workerId, ::affectsApproach)
        }
    }

    private fun refreshRejected(workerId: UUID, affected: (RejectedWorkSpots) -> Boolean) {
        rejectedWorkSpots[workerId]?.takeIf(affected)?.let { rejected ->
            // Keep a pending immutable batch; only its geometry evidence expired.
            rejected.positions.clear(); rejected.retryAt = null; rejected.checkedChanges = 0
            rejected.remoteChecked = false; rejected.remote.candidate = null; rejected.remote.nextAim = 0
            workSpots.remove(workerId)
        }
        deferredForestApproaches[workerId]?.entries?.removeIf { (_, rejected) ->
            affected(rejected).also { changed ->
                if (changed) forestSearches[workerId]?.let { search ->
                    search.byColumn[rejected.lastFocus.x to rejected.lastFocus.z]?.let { search.dirty += it }
                    search.retryAt = null
                }
            }
        }
    }

    /** Protection metadata can change without a block event; compare it once per active city. */
    private fun refreshProtection(city: Settlement, workerIds: List<UUID>) {
        fun bounds(points: List<CivPosition>) = CivBox(
            CivPosition(points.minOf { it.x }, points.minOf { it.y }, points.minOf { it.z }),
            CivPosition(points.maxOf { it.x }, points.maxOf { it.y }, points.maxOf { it.z }))
        val current = ZoneProtection(city.worldId,
            CivBox(CivPosition(city.center.x - city.radius, 0, city.center.z - city.radius),
                CivPosition(city.center.x + city.radius, 0, city.center.z + city.radius)),
            buildSet {
                civsProtectedBuildings(city).forEach { building ->
                    add(building.type to building.bounds)
                    CivsWorksiteLayouts.field(building).takeIf { it.isNotEmpty() }?.let { add(BuildingType.FARM to bounds(it)) }
                }
            }, CivsForestry.paths(city, forestryPaths(city)))
        val previous = zoneProtections.put(city.id, current) ?: return
        if (current == previous) return
        val changedAreas = (current.areas - previous.areas) + (previous.areas - current.areas)
        val changedPaths = (current.paths - previous.paths) + (previous.paths - current.paths)
        fun affected(point: CivPosition, margin: Int): Boolean {
            fun inside(box: CivBox) = point.x.toLong() - margin >= box.min.x && point.x.toLong() + margin <= box.max.x &&
                point.z.toLong() - margin >= box.min.z && point.z.toLong() + margin <= box.max.z
            return previous.world != current.world || inside(previous.boundary) != inside(current.boundary) ||
                changedAreas.any { (_, box) -> point.x.toLong() in box.min.x.toLong() - margin..box.max.x.toLong() + margin &&
                    point.z.toLong() in box.min.z.toLong() - margin..box.max.z.toLong() + margin } ||
                changedPaths.any { (x, z) -> kotlin.math.abs(x.toLong() - point.x) <= margin && kotlin.math.abs(z.toLong() - point.z) <= margin }
        }
        for (workerId in workerIds) {
            farmScans[workerId]?.let { scan -> scan.plots.filterTo(scan.dirty) { affected(it, 0) } }
            forestSearches[workerId]?.let { search ->
                search.columns.filterTo(search.dirty) { affected(it, 0) }
                search.retryRoots.keys.removeIf { affected(it, CivsForestry.MAX_RADIUS) }
                listOf(search.unsuitable, search.unplantable, search.unfunded).forEach { points ->
                    points.removeIf { affected(it, CivsForestry.MAX_RADIUS) }
                }
                forestScans[workerId]?.takeIf { affected(it.scan.root, CivsForestry.MAX_RADIUS) }?.let { forestScans.remove(workerId) }
                forestPlants[workerId]?.takeIf { it.roots.any { root -> affected(root, CivsForestry.MAX_RADIUS) } }?.let { it.next = 0 }
            }
            refreshRejected(workerId) { rejected -> affected(rejected.lastFocus, 6) || rejected.positions.keys.any { affected(it, 1) } }
            workSpots[workerId]?.takeIf { affected(it.target, 6) }?.let { workSpots.remove(workerId) }
            mineSafety[workerId]?.let { scan ->
                if (scan.frontier.any { affected(CivsWorksiteLayouts.point(scan.building, it.block), 0) }) scan.next = 0
            }
        }
    }

    private fun forget(workerId: UUID) {
        val building = workplaces.remove(workerId)?.second
        cached.remove(workerId); needs.remove(workerId); workSpots.remove(workerId)
        rejectedWorkSpots.remove(workerId); deferredForestApproaches.remove(workerId)
        farmScans.remove(workerId); forestSearches.remove(workerId); forestScans.remove(workerId); forestPlants.remove(workerId)
        mineSafety.remove(workerId); mineFrontierDirty.remove(workerId); mineSearches.remove(workerId); mineCovers.remove(workerId)
        cursors.remove(workerId); reportedNeeds.remove(workerId)
        reportedFarmSelections.remove(workerId); reportedMineSelections.remove(workerId)
        building?.takeIf { old -> workplaces.values.none { it.second.id == old.id } }?.let {
            mineCursors.remove(it.id); mineExecutions.remove(it.id); mineBounds.remove(it.id)
        }
    }

    internal fun forgetWorld(worldId: UUID) {
        check(Bukkit.isPrimaryThread())
        workplaces.filterValues { it.first == worldId }.keys.toList().forEach(::forget)
        zoneProtections.entries.removeIf { it.value.world == worldId }
    }

    internal fun forgetChunk(worldId: UUID, chunkX: Int, chunkZ: Int) {
        check(Bukkit.isPrimaryThread())
        val chunk = chunkX to chunkZ
        workplaces.filter { (id, place) -> place.first == worldId &&
            (chunk in forestSearches[id]?.chunks.orEmpty() || chunk in farmScans[id]?.chunks.orEmpty() ||
                mineBounds[place.second.id]?.let { bounds ->
                    chunkX in (bounds.min.x shr 4)..(bounds.max.x shr 4) && chunkZ in (bounds.min.z shr 4)..(bounds.max.z shr 4)
                } == true) }.keys.toList().forEach(::forget)
    }

    /** A failed approach excludes its stance, never a durable job, accepted batch or its inputs. */
    fun rejectStanding(city: Settlement, worker: CivWorker, work: CivWorldWork): Boolean {
        check(Bukkit.isPrimaryThread())
        val building = worker.assignment?.let(city::building) ?: return false
        val focus = citizenWorkFocus(work) ?: return false
        if (city.workers.none { it == worker } || plan(city, worker) != work) return false
        val key = standingKey(worker, building, work.miningStep, focus, work.miningOrdinal) ?: return false
        val rejected = rejectedWorkSpots[worker.id]?.takeIf { it.key == key }
            ?: RejectedWorkSpots(key, focus).also { rejectedWorkSpots[worker.id] = it }
        rejected.lastFocus = focus
        // The finite authored stance domain bounds this set. Time alone must not make
        // a failed point eligible before the remaining alternatives have been tried.
        rejected.positions[work.standing] = Bukkit.getCurrentTick().toLong()
        // Authored candidates are finite; a moving actor may also supply one live position.
        while (rejected.positions.size > 512) rejected.positions.remove(rejected.positions.keys.first())
        rejected.retryAt = null
        rejected.pending = cached.remove(worker.id)
        rejected.checkedChanges = 0
        rejected.remoteChecked = work.remoteWork
        rejected.remote.candidate = null
        rejected.remote.nextAim = 0
        workSpots.remove(worker.id)
        needs[worker.id] = Need(building, worker.completedCycles, "searching-work")
        return true
    }

    private fun standingKey(worker: CivWorker, building: CivBuilding, step: Long?, focus: CivPosition?, ordinal: Int? = null): WorkSpotKey? = when {
        building.type == BuildingType.QUARRY && step != null -> WorkSpotKey(building, ordinal?.toLong() ?: step,
            null)
        building.type in setOf(BuildingType.FORESTER, BuildingType.FARM) && focus != null ->
            WorkSpotKey(building, null, focus)
        else -> null
    }

    private fun rejectedStanding(worker: CivWorker, building: CivBuilding, step: Long?, focus: CivPosition,
                                 standing: CivPosition): Boolean {
        val rejected = rejectedWorkSpots[worker.id] ?: return false
        return rejected.key == standingKey(worker, building, step, focus, mineExecutions[building.id]?.ordinal) &&
            standing in rejected.positions
    }

    /** Round-robin admissions share one pulse: 16 columns, 192 forest units, 32 native stances, eight rays and 2ms. */
    fun pulse(cities: Collection<Settlement>) {
        check(Bukkit.isPrimaryThread())
        val jobs = cities.filter { plugin.server.getWorld(it.worldId) != null }.flatMap { city -> city.workers.mapNotNull { worker ->
            val building = worker.assignment?.let(city::building)
            if (building?.type in TYPES && building?.phase == BuildingPhase.ACTIVE && city.pendingDeletion == null &&
                city.construction?.buildingId != building?.id) Triple(city, worker, requireNotNull(building)) else null
        } }
        val workerIds = jobs.mapTo(hashSetOf()) { it.second.id }
        workplaces.keys.filter { it !in workerIds }.forEach(::forget)
        jobs.forEach { (city, worker, building) ->
            val place = city.worldId to building
            if (workplaces[worker.id]?.let { it != place } == true) forget(worker.id)
            workplaces[worker.id] = place
        }
        val cityJobs = jobs.groupBy { it.first.id }
        zoneProtections.keys.retainAll(cityJobs.keys)
        cityJobs.values.forEach { cityWorkers -> refreshProtection(cityWorkers.first().first, cityWorkers.map { it.second.id }) }
        cached.keys.retainAll(workerIds)
        needs.keys.retainAll(workerIds)
        workSpots.keys.retainAll(workerIds)
        rejectedWorkSpots.keys.retainAll(workerIds)
        deferredForestApproaches.keys.retainAll(workerIds)
        reportedNeeds.keys.retainAll(workerIds)
        cursors.keys.retainAll(workerIds)
        farmScans.keys.retainAll(workerIds)
        reportedFarmSelections.keys.retainAll(workerIds)
        reportedMineSelections.keys.retainAll(workerIds)
        mineSearches.keys.retainAll(workerIds)
        forestScans.keys.retainAll(workerIds)
        forestPlants.keys.retainAll(workerIds)
        forestSearches.keys.retainAll(workerIds)
        mineCursors.keys.retainAll(jobs.mapTo(hashSetOf()) { it.third.id })
        mineExecutions.keys.retainAll(jobs.mapTo(hashSetOf()) { it.third.id })
        mineSafety.keys.retainAll(workerIds)
        mineCovers.keys.retainAll(workerIds)
        jobs.forEach { (city, worker, building) -> tickMineSearch(city, worker, building) }
        // A persistence pause stops admission, not resident liveness or its bounded scan progress.
        val availableJobs = jobs.filterNot { isPaused(it.first.id) }
        if (availableJobs.isEmpty()) return
        spotChecksRemaining = settings.standingChecksPerPulse
        spotRaysRemaining = settings.rayChecksPerPulse
        mineChecksRemaining = settings.mineChecksPerPulse
        columnsRemaining = settings.maximumForestColumnsPerPulse
        forestChecksRemaining = settings.forestWorkUnitsPerPulse
        forestDeadline = nanoTime() + (settings.nativeBudgetMillis * 1_000_000).toLong()
        val start = Math.floorMod(cursor, availableJobs.size)
        // Independent quota classes must not let a later cheap job repeatedly
        // move the next start past an earlier worker whose quota ran out.
        cursor = (start + 1) % availableJobs.size
        for (offset in availableJobs.indices) {
            if (nanoTime() >= forestDeadline) break
            val index = (start + offset) % availableJobs.size
            val (city, worker, building) = availableJobs[index]
            val world = plugin.server.getWorld(city.worldId) ?: continue
            if (world.time in 12_000L..23_000L || city.inventory == null &&
                !(city.autonomy?.paused == false && (building.type == BuildingType.FORESTER ||
                    CivsAutonomousWorksites.supports(city, building)))) continue
            val current = plan(city, worker)
            val budgetAvailable = when {
                building.type == BuildingType.FARM -> columnsRemaining > 0 ||
                    farmScans[worker.id]?.harvest?.any { !farmClaimed(city, worker, building, it) } == true
                current != null -> spotChecksRemaining > 0 && spotRaysRemaining > 0
                building.type == BuildingType.FORESTER -> forestChecksRemaining > 0 &&
                    (columnsRemaining > 0 || forestScans[worker.id] != null || forestPlants[worker.id] != null ||
                        rejectedWorkSpots[worker.id]?.pending != null || city.forestryJobs?.containsKey(building.id.toString()) == true)
                else -> columnsRemaining > 0 && mineChecksRemaining > 0 && spotChecksRemaining > 0 && spotRaysRemaining > 0
            }
            if (!budgetAvailable) continue
            lastAdmittedWorker = worker.id
            pulseWorker(city, worker, building, world, current)
        }
    }

    private fun pulseWorker(city: Settlement, worker: CivWorker, building: CivBuilding, world: World, previous: CivWorldWork?) {
        var current = previous
        spotSearchPending = false
        mineSearches[worker.id]?.let { it.admissions++ }
        if (current != null && (current.remoteWork || building.type in setOf(BuildingType.QUARRY, BuildingType.FORESTER))) {
            val focus = citizenWorkFocus(current)
            val p = current.standing
            if (!citizenChunkReady(world, p.x shr 4, p.z shr 4) || focus != null &&
                !citizenBlockTraceReady(world, Location(world, p.x + .5, p.y + 1.62, p.z + .5), focus)) return
            // A route is temporary: growth or falling blocks may occupy an admitted stance.
            // Receipt pauses above keep accepted native batches immutable during persistence.
            val accessible = workAccessible(city, building, world, current, budgeted = true) ?: return
            if (accessible && building.type == BuildingType.QUARRY) return
            if (!accessible) {
                if (building.type != BuildingType.FORESTER || !rejectStanding(city, worker, current)) invalidate(worker.id)
                current = null
            }
        }
        needs[worker.id] = Need(building, worker.completedCycles,
            "searching-work")
        val rejected = rejectedWorkSpots[worker.id]
        val retry = rejected?.pending
        if (rejected != null && retry != null) {
            var replacement = if (!rejected.remoteChecked) remoteWork(city, worker, building, world, rejected, retry) else null
            if (spotSearchPending) return
            rejected.remoteChecked = true
            if (replacement == null && building.type == BuildingType.FORESTER) {
                replacement = retryForestApproach(city, worker, building, world, retry)
            } else if (replacement == null) {
                // Discard only the failed candidate; the zone cursor and other exclusions survive.
                cached[worker.id] = retry
                rejected.pending = null
                invalidate(worker.id)
                // The rejected receipt retired; this same admitted zone search is still active.
                needs[worker.id] = Need(building, worker.completedCycles, "searching-work")
            }
            if (replacement != null) {
                cached[worker.id] = Cached(building, worker, replacement)
                rejectedWorkSpots[worker.id]?.pending = null
                needs.remove(worker.id)
            }
            if (replacement != null || rejectedWorkSpots[worker.id]?.pending != null) {
                reportWorksite(city, worker, building, replacement)
                return
            }
        }
        val fieldScan = if (building.type == BuildingType.FARM) {
            val known = farmScans[worker.id]?.takeIf { it.building == building }
            // A known ripe target earns the next action before an optional background
            // refresh can spend this pulse's native budget. farm() rechecks it live.
            if (current == null && known?.harvest?.any { !farmClaimed(city, worker, building, it) } == true) known
            else scanFarm(city, worker, building, world)
        } else null
        // Refresh while travelling/working, so the next harvest is already known and newly
        // ripe crops can supersede care. A paused receipt keeps its admitted target intact.
        if (current != null && (building.type == BuildingType.FARM &&
                (!current.cropCare || fieldScan!!.harvest.none { !farmClaimed(city, worker, building, it) }) ||
                building.type == BuildingType.FORESTER && !CivsForestry.care(current))) return
        val proposed = (if (current == null && city.autonomy == null) equipTool(city, worker, building, world) else null) ?: when (building.type) {
            BuildingType.FARM -> farm(city, worker, building, world, requireNotNull(fieldScan), harvestOnly = current != null)
            BuildingType.FORESTER -> forest(city, worker, building, world, harvestOnly = current != null)
            BuildingType.QUARRY -> mine(city, worker, building, world)
            else -> null
        }
        val work = proposed?.let { CivsAutonomousWorksites.visual(city, building, it) }
        if (work != null) {
            needs.remove(worker.id)
            workSpots.remove(worker.id)
            fieldScan?.lastFocus = work.focus
            cached[worker.id] = Cached(building, worker, work)
            if (work.remoteWork) reportRemoteWork(city, worker, building, world, work, "ordinary-approach-unavailable", 0)
            rejectedWorkSpots[worker.id]?.takeIf {
                it.key != standingKey(worker, building, work.miningStep, citizenWorkFocus(work), work.miningOrdinal)
            }?.let { rejectedWorkSpots.remove(worker.id) }
        }
        if (current == null || work != null) reportWorksite(city, worker, building, work)
    }

    /** One rejected approach earns a bounded attempt from the actor's real safe position, never a teleport. */
    private fun remoteWork(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        rejected: RejectedWorkSpots, pending: Cached): CivWorldWork? {
        if (!cachedMatches(city, worker, building, pending) || pending.work.remoteWork) return null
        return remoteApproach(city, worker, building, world, pending.work, rejected.remote)?.also {
            reportRemoteWork(city, worker, building, world, it, "rejected-work-position", rejected.positions.size)
        }
    }

    /** Shared bounded geometry probe; the supplied work is not admitted or committed here. */
    private fun remoteApproach(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        work: CivWorldWork, probe: RemoteWorkProbe, focus: CivPosition? = citizenWorkFocus(work)): CivWorldWork? {
        focus ?: return null
        val feet = residentFeet(worker.id)?.takeIf { it.world == world } ?: return null
        if (spotChecksRemaining <= 0 || nanoTime() >= forestDeadline) { spotSearchPending = true; return null }
        spotChecksRemaining--
        val actual = CitizenPoint(feet.x, feet.y, feet.z)
        if (!citizenPacketSupported(world, actual) || !citizenPacketBodyClear(world, actual, false)) return null
        val standing = CivPosition(feet.blockX, feet.y.roundToInt(), feet.blockZ)
        val candidate = work.copy(standing = standing, remoteWork = true)
        if (candidate != probe.candidate) { probe.candidate = candidate; probe.nextAim = 0 }
        if (!workStandingAllowed(city, building, world, candidate, focus)) return null
        val eye = feet.clone().add(0.0, 1.62, 0.0)
        val reach = citizenWorkReach(building.type, candidate)
        val aims = citizenBlockAimPoints(eye, focus).filter { eye.distanceSquared(it) <= reach * reach }
        while (probe.nextAim < aims.size) {
            if (spotRaysRemaining <= 0 || nanoTime() >= forestDeadline) { spotSearchPending = true; return null }
            spotRaysRemaining--
            if (citizenCanReachBlockPoint(world, eye, focus, aims[probe.nextAim++], reach, candidate)) return candidate
        }
        return null
    }

    private fun reportRemoteWork(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        work: CivWorldWork, reason: String, rejectedStances: Int) {
        val feet = remoteFeet(world, work) ?: return
        val focus = citizenWorkFocus(work) ?: return
        val eye = feet.clone().add(0.0, 1.62, 0.0)
        CivsDiagnostics.record(Level.WARNING, city.id, CivsDiagnostics.line("worksite_remote_fallback",
            "city" to city.id, "worker" to worker.id, "building" to building.id, "cycle" to worker.completedCycles,
            "reason" to reason, "focus" to focus, "standing" to work.standing,
            "distance" to eye.distance(Location(world, focus.x + .5, focus.y + .5, focus.z + .5)),
            "reach" to citizenWorkReach(building.type, work), "rejected_stances" to rejectedStances), plugin.logger)
    }

    private fun deferForest(workerId: UUID, rejected: RejectedWorkSpots) {
        val deferred = deferredForestApproaches.getOrPut(workerId) { linkedMapOf() }
        deferred[rejected.lastFocus] = rejected
        if (deferred.size > 128) deferred.remove(deferred.keys.first())
    }

    /** A route rejection changes only the approach; its captured goods and native batch stay immutable. */
    private fun retryForestApproach(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        pending: Cached): CivWorldWork? {
        val rejected = rejectedWorkSpots.getValue(worker.id)
        val work = pending.work
        fun changed(): CivWorldWork? {
            rejectedWorkSpots.remove(worker.id)
            invalidate(worker.id)
            needs[worker.id] = Need(building, worker.completedCycles, "searching-work")
            return null
        }
        if (!cachedMatches(city, worker, building, pending)) return changed()
        val focus = citizenWorkFocus(work) ?: return changed()
        if (!citizenChunkReady(world, focus.x shr 4, focus.z shr 4))
            return blocked(worker, "worksite-unloaded", point = focus, reason = "chunk-unloaded")
        try { CivsForestry.validateWork(city, work) } catch (_: IllegalArgumentException) { return changed() }
        val timber = requireNotNull(CivsProduction.material(building))
        val roots = work.forestry?.job?.roots ?: if (CivsForestry.care(work))
            forestCareRoots(city, building, world, focus, timber) ?: return changed()
            else work.changes.map { it.position }
        if (!CivsForestry.crownClear(city, roots, timber, CivsForestry.paths(city, forestryPaths(city)))) return changed()
        rejected.retryAt?.let { retryAt ->
            if (city.forestryJobs?.containsKey(building.id.toString()) != true) {
                // No receipt owns this candidate. Continue the existing field cursor;
                // this root stays deferred until the whole field has had its turn.
                rejected.pending = null
                deferForest(worker.id, rejected)
                rejectedWorkSpots.remove(worker.id)
                CivsDiagnostics.event("forestry_approach_deferred", "city" to city.id, "worker" to worker.id,
                    "building" to building.id, "cycle" to worker.completedCycles, "focus" to focus,
                    "forestry_job" to work.forestry?.job?.id, "accepted" to false,
                    "rejected_stances" to rejected.positions.size)
                return null
            }
            if (Bukkit.getCurrentTick().toLong() < retryAt)
                return blocked(worker, "worksite-no-access", point = focus, reason = "approaches-exhausted")
            rejected.retryAt = null
            rejected.checkedChanges = 0
            workSpots.remove(worker.id)
        }
        if (city.autonomy == null && work.inputs.any { (material, amount) ->
                (CivsEquipment.available(worker, stock(city, building.id))[material] ?: 0) < amount })
            return blocked(worker, "waiting-saplings", work.inputs.keys.first(), work.inputs.values.first())
        while (rejected.checkedChanges < work.changes.size) {
            if (!forestBudget()) return blocked(worker, "searching-work")
            val cell = work.changes[rejected.checkedChanges]
            if (!citizenChunkReady(world, cell.position.x shr 4, cell.position.z shr 4))
                return blocked(worker, "worksite-unloaded", point = cell.position, reason = "chunk-unloaded")
            forestChecksRemaining--
            val block = read(city, building, world, cell.position, allowOwn = false, worker = worker) ?: return null
            if (block.blockData.asString != cell.before) return changed()
            rejected.checkedChanges++
        }
        val standing = beside(city, worker, building, world, focus, allowOwn = false) ?: return null
        return work.copy(standing = standing.standing, remoteWork = standing.remoteWork)
    }

    private fun workAccessible(city: Settlement, building: CivBuilding, world: World, work: CivWorldWork,
        budgeted: Boolean = false): Boolean? {
        val focus = citizenWorkFocus(work) ?: return true
        val p = work.standing
        if (budgeted && (spotChecksRemaining <= 0 || nanoTime() >= forestDeadline)) return null
        if (budgeted) spotChecksRemaining--
        if (!workStandingAllowed(city, building, world, work)) return false
        val eye = if (work.remoteWork) (remoteFeet(world, work) ?: return false).clone().add(0.0, 1.62, 0.0)
            else Location(world, p.x + .5, p.y + 1.62, p.z + .5)
        val reach = citizenWorkReach(building.type, work)
        for (aim in citizenBlockAimPoints(eye, focus).filter { eye.distanceSquared(it) <= reach * reach }) {
            if (budgeted && (spotRaysRemaining <= 0 || nanoTime() >= forestDeadline)) return null
            if (budgeted) spotRaysRemaining--
            if (citizenCanReachBlockPoint(world, eye, focus, aim, reach, work)) return true
        }
        return false
    }

    private fun workStandingAllowed(city: Settlement, building: CivBuilding, world: World, work: CivWorldWork,
        focus: CivPosition? = citizenWorkFocus(work)): Boolean {
        focus ?: return false
        val p = work.standing
        if (work.remoteWork && building.type !in TYPES) return false
        val actual = if (work.remoteWork) (remoteFeet(world, work) ?: return false).let { CitizenPoint(it.x, it.y, it.z) }
            else CitizenPoint(p.x + .5, p.y.toDouble(), p.z + .5)
        if (building.type == BuildingType.QUARRY && work.miningStep != null &&
            (!mineBuiltStanding(building, p, work.miningAccessStep ?: work.miningStep, work.miningAccessSections.orEmpty(),
                city.minePlans?.get(building.id.toString())) ||
                !(if (work.remoteWork) mineLadderConnected(building, world, p) else mineStandingConnected(building, world, focus, p))))
            return false
        return world.isChunkLoaded(p.x shr 4, p.z shr 4) && world.isChunkLoaded(focus.x shr 4, focus.z shr 4) &&
            city.contains(p.x, p.z) && citizenControlClear(city, p) && !locked(city.worldId, p) &&
            civsProtectedBuildings(city).none { (building.type == BuildingType.FORESTER || it.id != building.id) &&
                protectedByBuilding(building, it, p, 1) } &&
            (if (work.remoteWork) citizenPacketSupported(world, actual) else citizenStandable(world, p)) &&
            citizenPacketBodyClear(world, actual, false)
    }

    private fun remoteFeet(world: World, work: CivWorldWork): Location? = residentFeet(work.workerId)?.takeIf {
        it.world == world && it.blockX == work.standing.x && it.blockZ == work.standing.z &&
            kotlin.math.abs(it.y - work.standing.y) <= .500001
    }

    /** Recheck a sealed receipt without a transient planner cache or another actor admission. */
    internal fun validateAutonomousVisualReceiptNative(city: Settlement, work: CivWorldWork) {
        CivsAutonomousWorksites.validate(city, work)
        val building = city.building(work.buildingId) ?: throw CivsRejection("building-not-found")
        val world = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("world-unloaded")
        if (building.type == BuildingType.QUARRY) {
            validateMineReceiptNative(city, work)
            return
        }
        val reserved = work.changes.mapTo(hashSetOf()) { it.position }
        fun readReceipt(point: CivPosition): Block {
            requireCivs(point.y in world.minHeight until world.maxHeight, "outside-world")
            requireCivs(world.isChunkLoaded(point.x shr 4, point.z shr 4), "chunks-unloaded")
            return read(city, building, world, point, reserved = reserved) ?: throw CivsRejection("protected-area")
        }
        work.changes.forEach { cell ->
            val block = readReceipt(cell.position)
            val after = Bukkit.createBlockData(cell.after)
            val applied = CivsAutonomousWorksites.nativeAfterMatches(cell, block.blockData.asString)
            requireCivs(block.blockData.asString == cell.before || applied, "world-changed")
            requireCivs(worldAccess.canModify(city.owner, block, after.material), "lands-denied")
            if (!applied && after.material.isSolid) {
                val box = org.bukkit.util.BoundingBox(cell.position.x.toDouble(), cell.position.y.toDouble(), cell.position.z.toDouble(),
                    cell.position.x + 1.0, cell.position.y + 1.0, cell.position.z + 1.0)
                requireCivs(!mutationBlocked(city.worldId, cell.position) && world.players.none { it.boundingBox.overlaps(box) }, "worksite-occupied")
            }
        }
        if (building.type == BuildingType.QUARRY) {
            val excavations = work.changes.filter { Bukkit.createBlockData(it.after).material.isAir }
                .mapTo(hashSetOf()) { CivsMinePlan.localPoint(building, it.position) }
            if (excavations.isNotEmpty()) {
                val geometry = CivsMinePlan.forBuilding(building) ?: throw CivsRejection("stale-worksite")
                val focus = excavations.singleOrNull() ?: throw CivsRejection("stale-worksite")
                val frontier = mineFrontier(city, building, world, geometry, focus, excavations,
                    accessStep = work.miningAccessStep ?: city.minePlans?.get(building.id.toString())?.nextStep ?: 0,
                    sectionSteps = work.miningAccessSections.orEmpty(),
                    reserved = reserved) ?: throw CivsRejection("protected-area")
                for (task in frontier) {
                    if (task.transferFooting) continue
                    val opening = readReceipt(CivsWorksiteLayouts.point(building, task.exposed))
                    if (!mineExposed(task, excavations, opening)) continue
                    val block = if (task.material == null) observeMineHazard(world, CivsWorksiteLayouts.point(building, task.block))
                        ?: throw CivsRejection("chunks-unloaded") else readReceipt(CivsWorksiteLayouts.point(building, task.block))
                    requireCivs(!mineProtectionNeeded(block, task.material), "stale-worksite")
                }
            }
        }
    }

    internal fun applyAutonomousVisual(city: Settlement, work: CivWorldWork) {
        check(Bukkit.isPrimaryThread())
        requireCivs(work.autonomousVisual, "stale-worksite")
        validate(city, work)
        val world = requireNotNull(plugin.server.getWorld(city.worldId))
        val changes = work.changes.map { cell ->
            val after = Bukkit.createBlockData(cell.after)
            if (after.material.isSolid) {
                val box = org.bukkit.util.BoundingBox(cell.position.x.toDouble(), cell.position.y.toDouble(), cell.position.z.toDouble(),
                    cell.position.x + 1.0, cell.position.y + 1.0, cell.position.z + 1.0)
                requireCivs(!mutationBlocked(city.worldId, cell.position) && world.players.none { it.boundingBox.overlaps(box) },
                    "worksite-occupied")
            }
            world.getBlockAt(cell.position.x, cell.position.y, cell.position.z) to after
        }
        changes.forEach { (block, after) -> block.setBlockData(after, false) }
    }

    fun validate(city: Settlement, work: CivWorldWork) {
        requireCivs(!isPaused(city.id), "city-busy")
        val worker = city.workers.firstOrNull { it.id == work.workerId } ?: throw CivsRejection("worker-not-found")
        requireCivs(worker.assignment == work.buildingId && worker.completedCycles == work.cycle, "stale-delivery")
        requireCivs(plan(city, worker) == work, "stale-worksite")
        val building = city.building(work.buildingId) ?: throw CivsRejection("building-not-found")
        requireCivs(building.phase == BuildingPhase.ACTIVE && city.construction?.buildingId != building.id, "invalid-assignment")
        requireCivs(building.type != BuildingType.QUARRY || work.miningStep == null && work.miningPlanVersion == null ||
            work.miningPlanVersion == CivsMinePlan.VERSION, "stale-worksite")
        requireCivs(work.autonomousVisual == CivsAutonomousWorksites.supports(city, building), "stale-worksite")
        if (work.autonomousVisual) CivsAutonomousWorksites.validate(city, work)
        val world = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("world-unavailable")
        if (building.type == BuildingType.FORESTER && (work.forestry != null || work.changes.isNotEmpty())) {
            try { CivsForestry.validateWork(city, work) { point ->
                read(city, building, world, point, allowOwn = false)?.blockData?.asString
            } } catch (_: IllegalArgumentException) { throw CivsRejection("stale-worksite") }
            if (work.forestry == null || city.forestryJobs?.get(building.id.toString()) == null) {
                val timber = work.forestry?.job?.timber ?: requireNotNull(CivsProduction.material(building))
                val roots = work.forestry?.job?.roots ?: if (CivsForestry.care(work))
                    forestCareRoots(city, building, world, requireNotNull(work.focus), timber)
                        ?: throw CivsRejection("stale-worksite") else work.changes.map { it.position }
                requireCivs(CivsForestry.crownClear(city, roots, timber, CivsForestry.paths(city, forestryPaths(city))), "stale-worksite")
            }
        }
        if (work.remoteWork || building.type == BuildingType.QUARRY && work.miningStep != null ||
            building.type == BuildingType.FORESTER && CivsForestry.care(work))
            requireCivs(workAccessible(city, building, world, work) == true, "stale-worksite")
        if (work.cropCare && building.type == BuildingType.FARM) {
            val focus = work.focus ?: throw CivsRejection("stale-worksite")
            val crop = read(city, building, world, focus) ?: throw CivsRejection("protected-area")
            val soil = read(city, building, world, focus.copy(y = focus.y - 1)) ?: throw CivsRejection("protected-area")
            val age = crop.blockData as? Ageable
            requireCivs(soil.type == Material.FARMLAND && crop.type == cropAndSeed(building).first &&
                age != null && age.age < age.maximumAge, "stale-worksite")
            requireCivs(work.status != "watering" || !world.hasStorm() || world.getHighestBlockYAt(focus.x, focus.z) > focus.y,
                "stale-worksite")
            CivsWorksiteLayouts.irrigation(building).forEach { point ->
                val water = read(city, building, world, point) ?: throw CivsRejection("protected-area")
                requireCivs(water.type == Material.WATER, "stale-worksite")
            }
        }
        work.changes.forEach { cell ->
            val block = read(city, building, world, cell.position) ?: throw CivsRejection("protected-area")
            requireCivs(block.blockData.asString == cell.before, "stale-worksite")
            requireCivs(worldAccess.canModify(city.owner, block, Bukkit.createBlockData(cell.after).material), "lands-denied")
        }
        if (building.type == BuildingType.QUARRY && work.miningStep != null) validateMineReceiptNative(city, work)
        // A wall can change during the real two-second action while its target
        // remains unchanged. Recheck the same bounded frontier at receipt admission.
        if (work.miningPlanVersion == CivsMinePlan.VERSION && work.changes.any {
                Bukkit.createBlockData(it.after).material.isAir && !Bukkit.createBlockData(it.before).material.isAir
            }) {
            val geometry = CivsMinePlan.forBuilding(building) ?: throw CivsRejection("stale-worksite")
            val scan = mineSafety[worker.id] ?: throw CivsRejection("stale-worksite")
            val step = geometry.steps.getOrNull(scan.progress.toInt()) ?: throw CivsRejection("stale-worksite")
            val excavations = work.changes.filter { Bukkit.createBlockData(it.after).material.isAir }
                .mapTo(hashSetOf()) { CivsMinePlan.localPoint(building, it.position) }
            requireCivs(scan.target == step.point && scan.excavation == excavations.singleOrNull(), "stale-worksite")
            for (task in mineFrontier(city, building, world, geometry, step.point, excavations, accessStep = work.miningAccessStep ?: scan.progress,
                sectionSteps = work.miningAccessSections.orEmpty())
                ?: throw CivsRejection("protected-area")) {
                // Early transfer footing supplies a stance; the required shell/liquid
                // frontier remains the admission condition for an actual excavation.
                if (task.transferFooting) continue
                val exposed = CivsWorksiteLayouts.point(building, task.exposed)
                val opening = read(city, building, world, exposed) ?: throw CivsRejection("protected-area")
                if (!mineExposed(task, excavations, opening)) continue
                val block = readMineProtection(city, building, world, task)
                    ?: throw CivsRejection("protected-area")
                if (task.block in scan.deferredAirWalls && task.material == Material.COBBLESTONE && block.type.isAir) continue
                requireCivs(!mineProtectionNeeded(block, task.material), "stale-worksite")
            }
        }
        if (work.miningGenerator) {
            val geometry = CivsMinePlan.forBuilding(building) ?: throw CivsRejection("stale-worksite")
            val standing = requireNotNull(geometry.generatorStanding)
            requireCivs(building.type == BuildingType.QUARRY && work.miningPlanVersion == CivsMinePlan.VERSION &&
                work.miningStep == geometry.steps.size.toLong() &&
                work.focus == CivsWorksiteLayouts.point(building, requireNotNull(geometry.generator)) &&
                workAccessible(city, building, world, work) == true, "stale-worksite")
            for (task in mineFrontier(city, building, world, geometry, standing.copy(y = standing.y - 1), emptySet(), accessStep = geometry.steps.size.toLong())
                ?: throw CivsRejection("protected-area")) {
                val block = readMineProtection(city, building, world, task)
                    ?: throw CivsRejection("protected-area")
                requireCivs(!mineProtectionNeeded(block, task.material), "stale-worksite")
            }
            for (torch in geometry.floors.last().torches.filter {
                kotlin.math.abs(it.x - standing.x) + kotlin.math.abs(it.z - standing.z) == 1
            }) requireCivs(read(city, building, world, CivsWorksiteLayouts.point(building, torch))?.type == Material.TORCH,
                "stale-worksite")
        }
    }

    fun idleReason(city: Settlement, worker: CivWorker): String {
        val building = worker.assignment?.let(city::building) ?: return "unassigned"
        val suffix = toolSuffix(building.type)
        if (isPaused(city.id)) return currentNeed(city, worker)?.status?.takeIf {
            building.type == BuildingType.FARM && CivsEquipment.slots(worker)[4]?.material?.endsWith(suffix) == true
        } ?: "searching-work"
        return if (city.autonomy == null && CivsEquipment.slots(worker)[4]?.material?.endsWith(suffix) != true) "waiting-tool"
        else currentNeed(city, worker)?.status ?:
            "searching-work"
    }

    private fun currentNeed(city: Settlement, worker: CivWorker): Need? = needs[worker.id]?.takeIf {
        it.building == worker.assignment?.let(city::building) && it.cycle == worker.completedCycles
    }

    fun requirement(city: Settlement, worker: CivWorker): Pair<String, Long>? {
        val required = currentNeed(city, worker)?.let { need -> need.material?.let { it to need.amount } }
            ?: plan(city, worker)?.takeUnless { it.changes.isEmpty() && it.equipmentAfter != null }
                ?.inputs?.entries?.firstOrNull()?.let { it.key to it.value } ?: return null
        val quarryBatch = worker.assignment?.let(city::building)?.takeIf { it.type == BuildingType.QUARRY }
            ?.let { CivsLogistics.quarrySupplies(it)[required.first] }
        if (quarryBatch != null && currentNeed(city, worker)?.material == null) return null
        return required.first to (quarryBatch ?: maxOf(required.second, Material.matchMaterial(required.first)?.maxStackSize?.toLong() ?: 1))
    }

    private fun blocked(worker: CivWorker, status: String, material: String? = null, amount: Long = 1,
        point: CivPosition? = null, reason: String? = null, blockMaterial: String? = null): CivWorldWork? {
        needs[worker.id] = needs.getValue(worker.id).copy(status = status, material = material, amount = amount,
            point = point, reason = reason, blockMaterial = blockMaterial)
        return null
    }

    private fun reportWorksite(city: Settlement, worker: CivWorker, building: CivBuilding, work: CivWorldWork?) {
        reportMineSearch(city, worker, building, work)
        val previous = reportedNeeds[worker.id]
        if (work != null) {
            farmScans[worker.id]?.takeIf { building.type == BuildingType.FARM &&
                Bukkit.getCurrentTick() - reportedFarmSelections.getOrDefault(worker.id, -100) >= 100 }?.let { scan ->
                reportedFarmSelections[worker.id] = Bukkit.getCurrentTick()
                CivsDiagnostics.record(Level.INFO, city.id, CivsDiagnostics.line("farm_work_selected", "city" to city.id, "worker" to worker.id,
                    "building" to building.id, "cycle" to worker.completedCycles, "status" to work.status,
                    "point" to work.focus, "ripe_known" to scan.harvest.size,
                    "ripe_available" to scan.harvest.count { !farmClaimed(city, worker, building, it) },
                    "field_examined" to scan.examined, "field_size" to scan.plots.size, "scan_cursor" to scan.next), plugin.logger)
            }
            if (building.type == BuildingType.QUARRY && work.miningPlanVersion == CivsMinePlan.VERSION &&
                Bukkit.getCurrentTick() - reportedMineSelections.getOrDefault(worker.id, -100) >= 100) {
                reportedMineSelections[worker.id] = Bukkit.getCurrentTick()
                val progress = city.minePlans?.get(building.id.toString())
                val step = CivsMinePlan.forBuilding(building)?.steps?.getOrNull(
                    mineCursors[building.id]?.takeIf { it.first == building.level && it.second == (progress?.nextStep ?: 0) }
                        ?.third ?: (progress?.nextStep ?: 0).toInt())
                CivsDiagnostics.record(Level.INFO, city.id, CivsDiagnostics.line("mine_work_selected", "city" to city.id, "worker" to worker.id,
                    "building" to building.id, "version" to work.miningPlanVersion,
                    "next_step" to work.miningStep, "committed_step" to (progress?.nextStep ?: 0),
                    "legacy_step" to city.mineProgress?.get(building.id.toString()), "stage" to step?.stage,
                    "floor" to step?.floor, "branch" to step?.branch, "inputs" to work.inputs,
                    "status" to work.status, "point" to work.focus, "standing" to work.standing), plugin.logger)
            }
            if (reportedNeeds.remove(worker.id) != null) CivsDiagnostics.record(Level.INFO, city.id, CivsDiagnostics.line("worksite_recovered",
                "city" to city.id, "worker" to worker.id, "building" to building.id,
                "previous_reason" to previous?.need?.reason, "point" to work.focus, "standing" to work.standing), plugin.logger)
            return
        }
        val need = needs[worker.id]?.takeIf { it.reason != null } ?: return
        val tick = Bukkit.getCurrentTick()
        val structural = building.type == BuildingType.QUARRY && need.status in
            setOf("worksite-no-access", "worksite-obstacle", "worksite-layout")
        if (previous != null && (previous.need.status == need.status && previous.need.point == need.point &&
                previous.need.reason == need.reason && previous.need.blockMaterial == need.blockMaterial ||
                !structural && tick - previous.tick < 100)) return
        reportedNeeds[worker.id] = ReportedNeed(need, tick)
        val line = CivsDiagnostics.line("worksite_blocked", "city" to city.id, "worker" to worker.id,
            "building" to building.id, "cycle" to worker.completedCycles, "status" to need.status,
            "reason" to need.reason, "point" to need.point, "material" to (need.blockMaterial ?: "unknown"),
            "mine_version" to city.minePlans?.get(building.id.toString())?.version,
            "mine_step" to city.minePlans?.get(building.id.toString())?.nextStep)
        CivsDiagnostics.record(if (structural) Level.WARNING else Level.INFO, city.id, line, plugin.logger)
    }

    private fun mineSearchEligible(city: Settlement, worker: CivWorker): Boolean {
        val building = worker.assignment?.let(city::building) ?: return false
        val world = plugin.server.getWorld(city.worldId) ?: return false
        if (isPaused(city.id) || city.autonomy?.paused == true || world.time in 12_000L..23_000L ||
            plan(city, worker) != null || currentNeed(city, worker)?.status != "searching-work") return false
        val target = workSpots[worker.id]?.target ?: rejectedWorkSpots[worker.id]?.pending?.work?.let(::citizenWorkFocus)
        if (target != null && !citizenChunkReady(world, target.x shr 4, target.z shr 4)) return false
        if (building.type == BuildingType.FORESTER) return city.autonomy != null ||
            CivsEquipment.slots(worker)[4]?.material?.endsWith("_AXE") == true
        return building.type == BuildingType.QUARRY && (CivsAutonomousWorksites.supports(city, building) ||
            city.inventory != null && CivsEquipment.slots(worker)[4]?.material?.endsWith("_PICKAXE") == true &&
                CivsLogistics.QUARRY_SUPPLIES.keys.all { CivsEquipment.plainCount(worker, it) > 0 })
    }

    /** Only consecutive eligible samples accrue time; a missed global pulse contributes at most one second. */
    private fun tickMineSearch(city: Settlement, worker: CivWorker, building: CivBuilding) {
        val search = mineSearches[worker.id] ?: return
        if (search.city != city.id || search.building != building || search.cycle != worker.completedCycles ||
            search.plan != city.minePlans?.get(building.id.toString()) ||
            search.legacyStep != city.mineProgress?.get(building.id.toString())) {
            mineSearches.remove(worker.id)
            return
        }
        if (!mineSearchEligible(city, worker)) { search.sampledAt = null; return }
        val tick = Bukkit.getCurrentTick().toLong()
        search.sampledAt?.let { search.activeTicks += (tick - it).coerceIn(0, 20) }
        search.sampledAt = tick
        if (search.activeTicks >= 1_200 && search.activeTicks - (search.warnedAt ?: 0) >= 1_200) {
            search.warnedAt = search.activeTicks
            CivsDiagnostics.record(Level.WARNING, city.id, mineSearchLine(
                if (building.type == BuildingType.QUARRY) "mine_search_slow" else "worksite_search_slow",
                city, worker, building, search), plugin.logger)
        }
    }

    private fun reportMineSearch(city: Settlement, worker: CivWorker, building: CivBuilding, work: CivWorldWork?) {
        if (building.type !in setOf(BuildingType.QUARRY, BuildingType.FORESTER)) return
        if (work != null) {
            mineSearches.remove(worker.id)?.takeIf { it.warnedAt != null }?.let { search ->
                CivsDiagnostics.record(Level.INFO, city.id, mineSearchLine(
                    if (building.type == BuildingType.QUARRY) "mine_search_work_selected" else "worksite_search_work_selected",
                    city, worker, building, search, work), plugin.logger)
            }
        } else if (mineSearchEligible(city, worker)) {
            val search = mineSearches.getOrPut(worker.id) { MineSearch(city.id, building, worker.completedCycles,
                city.minePlans?.get(building.id.toString()), city.mineProgress?.get(building.id.toString()), admissions = 1) }
            search.sampledAt = Bukkit.getCurrentTick().toLong()
            val target = workSpots[worker.id]?.target ?: forestScans[worker.id]?.scan?.root ?:
                mineSafety[worker.id]?.target?.let { CivsWorksiteLayouts.point(building, it) }
            if (target != search.target) { search.target = target; search.targetSwitches++ }
        } else mineSearches[worker.id]?.sampledAt = null
    }

    private fun mineSearchLine(event: String, city: Settlement, worker: CivWorker, building: CivBuilding,
        search: MineSearch, work: CivWorldWork? = null): String {
        val safety = mineSafety[worker.id]
        val candidate = workSpots[worker.id]
        val plan = city.minePlans?.get(building.id.toString())
        return CivsDiagnostics.line(event, "city" to city.id, "worker" to worker.id, "building" to building.id,
            "cycle" to worker.completedCycles, "mine_version" to plan?.version, "committed_step" to plan?.nextStep,
            "legacy_step" to city.mineProgress?.get(building.id.toString()),
            "search_version" to CivsMinePlan.VERSION, "scan_step" to mineCursors[building.id]?.third,
            "active_ticks" to search.activeTicks, "active_ms" to search.activeTicks * 50, "active_time_source" to "server-ticks",
            "began_tick" to search.beganTick, "owner_admissions" to search.admissions,
            "target_switches" to search.targetSwitches, "discovery_target" to search.target,
            "frontier_target" to safety?.target?.let { CivsWorksiteLayouts.point(building, it) },
            "frontier_excavation" to safety?.excavation?.let { CivsWorksiteLayouts.point(building, it) },
            "frontier_next" to safety?.next, "frontier_deferred" to safety?.deferredAirWalls?.size,
            "candidate_target" to candidate?.target, "candidate" to candidate?.next, "aim" to candidate?.nextAim,
            "outcome" to if (work == null) "searching-work" else "work_selected",
            "selected_status" to work?.status, "selected_step" to work?.miningStep,
            "selected_focus" to work?.let(::citizenWorkFocus), "selected_standing" to work?.standing)
    }

    private fun equipTool(city: Settlement, worker: CivWorker, building: CivBuilding, world: World): CivWorldWork? {
        val slots = CivsEquipment.slots(worker)
        val suffix = toolSuffix(building.type)
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
        return work(worker, building, WorkApproach(standing), emptyList(), mapOf(material to 1), status = "waiting-tool", duration = 1_000)
            .copy(equipmentAfter = next)
    }

    private fun toolSuffix(type: BuildingType) = when (type) {
        BuildingType.FARM -> "_HOE"; BuildingType.FORESTER -> "_AXE"; else -> "_PICKAXE"
    }

    private fun prepareQuarry(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
                              needed: String? = null): CivWorldWork? {
        val available = stock(city, building.id)
        val batches = CivsLogistics.quarrySupplies(building)
        // Carry available future framing supplies together, but do not stop an unrelated
        // cut for timber that is only needed by a later paid construction step.
        val required = batches.mapValues { (material, batch) ->
            (batch - CivsEquipment.plainCount(worker, material)).coerceAtLeast(0)
        }.filter { (material, amount) -> amount > 0 &&
            (material in CivsLogistics.QUARRY_SUPPLIES || material == needed || (available[material] ?: 0) >= amount) }
        required.entries.firstOrNull { (material, amount) -> (available[material] ?: 0) < amount }?.let { (material, _) ->
            return blocked(worker, "waiting-${material.lowercase()}", material, batches.getValue(material))
        }
        val supplied = try { CivsEquipment.collectSupplies(worker, required) } catch (failure: CivsRejection) {
            if (failure.reason != "resident-inventory-full") throw failure
            return blocked(worker, "resident-inventory-full", point = building.control, reason = failure.reason)
        }
        val standing = citizenStorageTarget(city, building, world)
            ?: return blocked(worker, "storage-unavailable", point = building.control, reason = "quarry-supplies-inaccessible")
        return work(worker, building, WorkApproach(standing), emptyList(), required, status = "collecting-supplies", duration = 1_000)
            .copy(equipmentAfter = CivsEquipment.slots(supplied))
    }

    private fun scanFarm(city: Settlement, worker: CivWorker, building: CivBuilding, world: World): FarmScan {
        val scan = farmScans[worker.id]?.takeIf { it.building == building }
            ?: FarmScan(building, CivsWorksiteLayouts.field(building)).also { farmScans[worker.id] = it }
        val autonomous = CivsAutonomousWorksites.supports(city, building)
        val available by lazy { if (autonomous) emptyMap() else CivsEquipment.available(worker, stock(city, building.id)) }
        val crop = cropAndSeed(building).first
        repeat(minOf(columnsRemaining, scan.plots.size)) {
            if (nanoTime() >= forestDeadline) return scan
            columnsRemaining--
            val dirty = scan.dirty.firstOrNull()
            val point = dirty ?: scan.plots[scan.next]
            if (dirty != null) scan.dirty.remove(dirty) else {
                scan.next = (scan.next + 1) % scan.plots.size
                scan.examined = minOf(scan.examined + 1, scan.plots.size)
            }
            scan.harvest.remove(point); scan.field.remove(point); scan.care.remove(point)
            val soil = read(city, building, world, point) ?: return@repeat
            val top = read(city, building, world, point.copy(y = point.y + 1)) ?: return@repeat
            if (soil.type !in SOILS || top.type != crop && !top.type.isAir && top.type !in WEEDS) return@repeat
            if (soil.type == Material.FARMLAND && top.type == crop) {
                val age = top.blockData as? Ageable ?: return@repeat
                if (age.age == age.maximumAge) scan.harvest += point
                else if (cropCareChanges(world, top, soil, age,
                        autonomous || (available["BONE_MEAL"] ?: 0) > 0).isNotEmpty()) scan.care += point
            } else if (top.type != crop) scan.field += point
        }
        return scan
    }

    /** Every farmer can harvest the whole field; admitted targets keep coworkers from duplicating it. */
    private fun farmClaimed(city: Settlement, worker: CivWorker, building: CivBuilding, soil: CivPosition): Boolean = city.workers.any { other ->
        other.id != worker.id && other.assignment == building.id && plan(city, other)?.focus?.let { focus ->
            focus == soil || focus == soil.copy(y = soil.y + 1)
        } == true
    }

    private fun farm(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        scan: FarmScan, harvestOnly: Boolean): CivWorldWork? {
        val autonomous = CivsAutonomousWorksites.supports(city, building)
        if (!autonomous && CivsEquipment.slots(worker)[4]?.material?.endsWith("_HOE") != true) return null
        val (crop, seed) = cropAndSeed(building)
        // A paid Auto field owns its seeds; this planning allowance never creates a carried item.
        val available by lazy { if (autonomous) mapOf(seed to 1L) else CivsEquipment.available(worker, stock(city, building.id)) }
        for (point in CivsWorksiteLayouts.irrigation(building)) {
            val block = read(city, building, world, point, worker = worker) ?: return null
            if (block.type == Material.WATER) continue
            if (harvestOnly) return null
            if (block.type !in SOILS) return blocked(worker, "worksite-soil", point = point,
                reason = "irrigation-ground", blockMaterial = block.type.name)
            val standing = beside(city, worker, building, world, point) ?: return null
            return work(worker, building, standing, listOf(change(block, Material.WATER.createBlockData().asString)),
                status = "working-field")
        }
        val pending = workSpots[worker.id]?.target
        val from = scan.lastFocus ?: building.entrance
        val nearest = compareBy<CivPosition> { if (pending == it || pending == it.copy(y = it.y + 1)) 0 else 1 }
            .thenBy { val dx = from.x.toDouble() - it.x; val dz = from.z.toDouble() - it.z; dx * dx + dz * dz }
            .thenBy { it.x }.thenBy { it.z }
        val candidates = scan.harvest.sortedWith(nearest).asSequence() + sequence {
            if (harvestOnly || scan.examined != scan.plots.size) return@sequence
            // Visit the next authored plot instead of repeatedly boosting the plant we just left.
            val after = scan.plots.indexOfFirst { it == scan.lastFocus || it.copy(y = it.y + 1) == scan.lastFocus } + 1
            val care = scan.plots.indices.map { scan.plots[(after + it) % scan.plots.size] }
                .filter { it in scan.care }
                .sortedBy { if (pending == it || pending == it.copy(y = it.y + 1)) 0 else 1 }
            yieldAll(scan.field.sortedWith(nearest))
            yieldAll(care)
        }
        var checked = 0
        for (soilPoint in candidates) {
            if (farmClaimed(city, worker, building, soilPoint)) continue
            if (checked++ == 16) return blocked(worker, "searching-work")
            val wasHarvest = soilPoint in scan.harvest
            scan.harvest.remove(soilPoint); scan.field.remove(soilPoint); scan.care.remove(soilPoint)
            val soil = read(city, building, world, soilPoint) ?: continue
            val top = read(city, building, world, soilPoint.copy(y = soilPoint.y + 1)) ?: continue
            if (soil.type !in SOILS || top.type != crop && !top.type.isAir && top.type !in WEEDS) continue
            if (soil.type == Material.FARMLAND && top.type == crop) {
                val age = top.blockData as? Ageable ?: continue
                if (age.age < age.maximumAge) {
                    if (harvestOnly || wasHarvest) continue
                    val care = careCrop(city, worker, building, world, position(top), available)
                    if (care != null || spotSearchPending) {
                        scan.care += soilPoint
                        return care
                    }
                    continue
                }
                scan.harvest += soilPoint
            } else {
                if (harvestOnly || wasHarvest) continue
                scan.field += soilPoint
            }
            val focus = if (soil.type == Material.FARMLAND) position(top) else soilPoint
            val standing = beside(city, worker, building, world, focus)
            if (standing == null) {
                if (spotSearchPending) return null
                scan.harvest.remove(soilPoint); scan.field.remove(soilPoint)
                continue
            }
            if (soil.type != Material.FARMLAND) {
                if (top.type == crop) continue
                return work(worker, building, standing,
                    listOf(change(soil, "minecraft:farmland[moisture=7]")) +
                        if (top.type in WEEDS) listOf(change(top, "minecraft:air")) else emptyList(), status = "working-field")
            }
            if (top.type == crop) {
                val age = top.blockData as? Ageable ?: continue
                if (age.age != age.maximumAge) continue
                val drops = (if (autonomous) emptyMap() else drops(top, tool(worker))).toMutableMap()
                val seedCount = drops[seed] ?: 0
                val replant = seedCount > 0 || (available[seed] ?: 0) > 0
                if (seedCount > 0) put(drops, seed, seedCount - 1)
                val inputs = if (replant && seedCount == 0L) mapOf(seed to 1L) else emptyMap()
                return work(worker, building, standing,
                    listOf(change(top, if (replant) crop.createBlockData().asString else "minecraft:air")), inputs, drops, "working-field")
            }
            if ((available[seed] ?: 0) > 0) return work(worker, building, standing,
                listOf(change(top, crop.createBlockData().asString)), mapOf(seed to 1), status = "working-field")
            blocked(worker, "waiting-seeds", seed)
        }
        if (scan.examined < scan.plots.size) return blocked(worker, "searching-work")
        if (needs.getValue(worker.id).status == "searching-work") blocked(worker, "waiting-growth")
        return null
    }

    private fun cropAndSeed(building: CivBuilding): Pair<Material, String> = when (CivsProduction.material(building)) {
        "CARROT" -> Material.CARROTS to "CARROT"
        "POTATO" -> Material.POTATOES to "POTATO"
        else -> Material.WHEAT to "WHEAT_SEEDS"
    }

    private fun careCrop(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        focus: CivPosition, available: Map<String, Long>): CivWorldWork? {
        val crop = read(city, building, world, focus) ?: return null
        val soil = read(city, building, world, focus.copy(y = focus.y - 1)) ?: return null
        val age = crop.blockData as? Ageable ?: return null
        if (soil.type != Material.FARMLAND || crop.type != cropAndSeed(building).first || age.age == age.maximumAge) return null
        val autonomous = CivsAutonomousWorksites.supports(city, building)
        val fertilizing = autonomous || (available["BONE_MEAL"] ?: 0) > 0
        val changes = cropCareChanges(world, crop, soil, age, fertilizing)
        if (changes.isEmpty()) return null
        val standing = beside(city, worker, building, world, focus) ?: return null
        return work(worker, building, standing, changes, if (fertilizing && !autonomous) mapOf("BONE_MEAL" to 1L) else emptyMap(),
            status = if (fertilizing) "fertilizing" else "watering", duration = 4_000, focus = focus)
    }

    private fun cropCareChanges(world: World, crop: Block, soil: Block, age: Ageable, fertilizing: Boolean): List<CivPlanBlock> = buildList {
        val rainExposed = world.hasStorm() && world.getHighestBlockYAt(crop.x, crop.z) <= crop.y
        if (!rainExposed && soil.blockData.asString != "minecraft:farmland[moisture=7]")
            add(change(soil, "minecraft:farmland[moisture=7]"))
        // Exposed rain needs no watering; accepted fertilizer still advances exactly one immature stage.
        if (!rainExposed || fertilizing)
            add(change(crop, (age.clone() as Ageable).also { it.age++ }.asString))
    }

    private fun forest(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        harvestOnly: Boolean): CivWorldWork? {
        if (city.autonomy == null && CivsEquipment.slots(worker)[4]?.material?.endsWith("_AXE") != true) return null
        val paths = CivsForestry.paths(city, forestryPaths(city))
        city.forestryJobs?.get(building.id.toString())?.let { job ->
            return forestBatch(city, worker, building, world, job)
        }
        // One worker owns a mill's candidate until its first durable receipt. Other mills remain independent.
        if (city.workers.filter { it.assignment == building.id }.minByOrNull { it.id.toString() }?.id != worker.id) return null
        val timber = requireNotNull(CivsProduction.material(building))
        val sapling = timber.removeSuffix("_LOG") + "_SAPLING"
        val supplies = forestSupply(city, worker, building, sapling)
        val deferred = deferredForestApproaches.getOrPut(worker.id) { linkedMapOf() }
        deferred.entries.removeIf { it.value.key.building != building }
        val feet = residentFeet(worker.id)?.takeIf { it.world == world }
        val search = forestSearches[worker.id]?.takeIf { it.building == building }
            ?: run {
                val layout = CivsWorksiteLayouts.site(building)?.getAsJsonObject("forester") ?: return null
                val box = layout.getAsJsonObject("searchBounds")
                val min = CivsWorksiteLayouts.vector(box["min"])
                val max = CivsWorksiteLayouts.vector(box["max"])
                val columns = (min.z..max.z).flatMap { z -> (min.x..max.x).map { x ->
                    CivsWorksiteLayouts.point(building, CivPosition(x, 0, z))
                } }
                ForestSearch(building, supplies, if (feet == null) columns else columns.sortedBy {
                    val dx = it.x + .5 - feet.x; val dz = it.z + .5 - feet.z
                    dx * dx + dz * dz
                })
            }.also { forestSearches[worker.id] = it }
        val origin = feet?.let { CivPosition(it.blockX, it.y.roundToInt(), it.blockZ) }
        if (origin != search.residentOrigin) {
            search.residentOrigin = origin
            if (feet != null) {
                val eye = feet.clone().add(0.0, 1.62, 0.0)
                val reach = CITIZEN_REMOTE_WORK_REACH
                refreshRejected(worker.id) { rejected ->
                    rejected.lastFocus in deferred && citizenBlockAimPoints(eye, rejected.lastFocus).any { aim ->
                        eye.distanceSquared(aim) <= reach * reach
                    }
                }
            }
        }
        if (search.saplings != supplies) {
            search.saplings = supplies
            search.unfunded.clear()
            search.retryAt = null
        }
        forestScans[worker.id]?.takeIf { it.building == building }?.let { scan ->
            return finishForestScan(city, worker, building, world, scan)
        }
        forestPlants[worker.id]?.takeIf { it.building == building }?.let { plant ->
            return finishForestPlant(city, worker, building, world, plant, paths)
        }
        // Permission/operation-lock release has no native geometry event. Revisit one
        // unavailable root after a short pause; retain the rest of the zone and its cursor.
        search.retryRoots.entries.firstOrNull { it.value.atTick <= Bukkit.getCurrentTick().toLong() }?.let { (point, retry) ->
            search.retryRoots.remove(point)
            if (retry.planting) search.planting += point else search.mature += point
        }
        fun matureCandidate(point: CivPosition): CivWorldWork? {
            if (point in deferred || point in search.unsuitable || point in search.unfunded || point in search.retryRoots ||
                CivsForestry.rootClaimed(city, building.id, point)) return null
            if (!forestBudget()) return null
            val base = forestRead(city, building, world, point)
            val below = forestRead(city, building, world, point.copy(y = point.y - 1))
            if (!forestBudget()) return null
            if (base == null || below == null) { search.retry(point); return null }
            if (base.type.name != timber || below.type.name !in CivsForestry.soils) {
                search.mature.remove(point); return null
            }
            val roots = if (CivsForestry.large(timber)) forestPlot(city, building, world, point, Material.valueOf(timber), budgeted = true)
                ?: if (timber == "DARK_OAK_LOG") emptyList() else listOf(point) else listOf(point)
            if (!forestBudget()) return null
            if (roots.isEmpty() || !CivsForestry.crownClear(city, roots, timber, paths)) {
                search.remember(search.unsuitable, point); return null
            }
            val scan = ForestScan(building, CivsForestry.Scan(point, timber, roots), roots)
            forestScans[worker.id] = scan
            return finishForestScan(city, worker, building, world, scan)
        }
        // The initial sweep gives existing trees priority. Once covered, alternate an
        // available planting action with harvest/care so natural growth cannot starve it.
        val plantingTurn = search.next == search.columns.size && search.plantNext && search.planting.isNotEmpty() && !harvestOnly
        if (!plantingTurn) for (point in search.mature.toList()) {
            val result = matureCandidate(point)
            if (result != null || forestScans[worker.id] != null || spotSearchPending || !forestBudget()) return result
        }
        // Keep one resumable column and a rolling refresh cursor. Completed coverage,
        // known roots and safe-site candidates survive receipts, inventory and cycles.
        while (columnsRemaining > 0) {
            if (!forestBudget()) return blocked(worker, "searching-work")
            columnsRemaining--
            val column = search.column ?: (search.dirty.firstOrNull() ?: search.columns[
                if (search.next < search.columns.size) search.next else search.refresh]).also {
                search.column = it; search.scanningDirty = it in search.dirty
                search.care.removeIf { p -> p.x == it.x && p.z == it.z }
                search.planting.removeIf { p -> p.x == it.x && p.z == it.z }
                search.mature.removeIf { p -> p.x == it.x && p.z == it.z }
            }
            fun nextColumn() {
                if (search.scanningDirty) search.dirty.remove(column)
                else if (search.next < search.columns.size) search.next++
                else search.refresh = (search.refresh + 1) % search.columns.size
                search.column = null; search.y = null; search.surface = null
            }
            if (!world.isChunkLoaded(column.x shr 4, column.z shr 4) || CivsForestry.blockedColumn(city, column.x, column.z, paths)) {
                nextColumn(); continue
            }
            val surface = search.surface ?: world.getHighestBlockYAt(column.x, column.z, HeightMap.WORLD_SURFACE)
                .also { search.surface = it }
            for (y in (search.y ?: building.anchor.y - 4)..minOf(surface, building.anchor.y + 5)) {
                if (!forestBudget()) return blocked(worker, "searching-work")
                val base = forestRead(city, building, world, column.copy(y = y))
                search.y = y + 1
                if (base?.blockData?.asString == CivsForestry.saplingState(sapling)) search.care += position(base)
                if (base?.type?.name != timber) continue
                val below = forestRead(city, building, world, column.copy(y = y - 1))
                if (!forestBudget()) { search.y = y; return blocked(worker, "searching-work") }
                if (below?.type?.name !in CivsForestry.soils) continue
                val point = position(base)
                search.mature += point
                if (!plantingTurn) {
                    val result = matureCandidate(point)
                    if (result != null || forestScans[worker.id] != null || spotSearchPending || !forestBudget()) return result
                }
            }
            if (!forestBudget()) return blocked(worker, "searching-work")
            val ground = forestRead(city, building, world, column.copy(y = surface))
            val air = forestRead(city, building, world, column.copy(y = surface + 1))
            if (!forestBudget()) return blocked(worker, "searching-work")
            nextColumn()
            if (ground?.type?.name in CivsForestry.soils && ground?.type != Material.COARSE_DIRT && air?.type?.isAir == true)
                search.planting += position(air)
            // Do not spend a second full sweep before using the retained candidates.
            if (search.next == search.columns.size && !harvestOnly) break
        }
        if (search.next < search.columns.size) return blocked(worker, "searching-work")
        if (harvestOnly) return null
        fun plant(): CivWorldWork? {
            // Stable pseudo-random ranks scatter sites across the whole field instead
            // of laying a grid or filling adjacent columns beside the entrance.
            for (point in search.planting.sortedBy { plantingRank(building.id, it) }) {
                if (!forestBudget()) return blocked(worker, "searching-work")
                if (point in deferred || point in search.unplantable || point in search.retryRoots) continue
                val roots = if (CivsForestry.large(timber)) forestPlot(city, building, world, point, Material.AIR, budgeted = true)
                    else listOf(point)
                if (!forestBudget()) return blocked(worker, "searching-work")
                if (roots == null || !CivsForestry.crownClear(city, roots, timber, paths)) { search.planting.remove(point); continue }
                if (forestSupply(city, worker, building, sapling) < roots.size)
                    return blocked(worker, "waiting-saplings", sapling, roots.size.toLong())
                search.planting.remove(point)
                val pending = ForestPlant(building, roots, timber)
                forestPlants[worker.id] = pending
                return finishForestPlant(city, worker, building, world, pending, paths)
            }
            return null
        }
        if (plantingTurn) {
            val result = plant()
            if (result != null || forestPlants[worker.id] != null || spotSearchPending || !forestBudget()) return result
            search.plantNext = false
        }
        for (point in search.care.toList()) {
            if (!forestBudget()) return blocked(worker, "searching-work")
            if (point in deferred) continue
            val roots = forestCareRoots(city, building, world, point, timber, budgeted = true)
            if (!forestBudget()) return blocked(worker, "searching-work")
            if (roots == null || roots.any { CivsForestry.rootClaimed(city, building.id, it) } ||
                !CivsForestry.crownClear(city, roots, timber, paths)) { search.care.remove(point); continue }
            val saplingBlock = forestRead(city, building, world, point)
            if (!forestBudget()) return blocked(worker, "searching-work")
            if (saplingBlock?.blockData?.asString != CivsForestry.saplingState(sapling)) { search.care.remove(point); continue }
            val standing = beside(city, worker, building, world, point, allowOwn = false)
            if (standing == null) {
                if (spotSearchPending) return null
                search.care.remove(point); continue
            }
            search.care.remove(point)
            return work(worker, building, standing, listOf(change(saplingBlock, CivsForestry.saplingState(sapling, 1))),
                status = "watering", duration = 4_000, focus = point)
        }
        val planted = plant()
        if (planted != null || forestPlants[worker.id] != null || spotSearchPending || !forestBudget()) return planted
        if (deferred.isNotEmpty()) {
            search.retryAt = Bukkit.getCurrentTick().toLong() + 100
            return blocked(worker, "worksite-no-access", point = deferred.keys.first(), reason = "approaches-exhausted")
        }
        if (search.unfunded.isNotEmpty()) return blocked(worker, "waiting-saplings", sapling)
        if (search.retryRoots.isNotEmpty()) return blocked(worker, "searching-work", point = search.retryRoots.keys.first(),
            reason = "tree-observation-pending")
        if (currentNeed(city, worker)?.status == "searching-work") return blocked(worker, "waiting-growth")
        return null
    }

    private fun plantingRank(building: UUID, point: CivPosition): Long {
        var value = building.mostSignificantBits xor building.leastSignificantBits xor (point.x.toLong() shl 32) xor point.z.toLong()
        value = (value xor (value ushr 33)) * -49064778989728563L
        value = (value xor (value ushr 33)) * -4265267296055464877L
        return value xor (value ushr 33)
    }

    private fun forestBudget(): Boolean = forestChecksRemaining > 0 && nanoTime() < forestDeadline

    private fun forestRead(city: Settlement, building: CivBuilding, world: World, point: CivPosition): Block? {
        if (!forestBudget()) return null
        forestChecksRemaining--
        return read(city, building, world, point, allowOwn = false)
    }

    private fun forestCareRoots(city: Settlement, building: CivBuilding, world: World, focus: CivPosition,
        timber: String, budgeted: Boolean = false): List<CivPosition>? {
        val sapling = timber.removeSuffix("_LOG") + "_SAPLING"
        fun at(point: CivPosition) = if (budgeted) forestRead(city, building, world, point)
            else read(city, building, world, point, allowOwn = false)
        if (at(focus)?.blockData?.asString != CivsForestry.saplingState(sapling)) return null
        return if (CivsForestry.large(timber)) forestPlot(city, building, world, focus, Material.valueOf(sapling), budgeted)
        else listOf(focus).takeIf { at(focus.copy(y = focus.y - 1))?.type?.name in CivsForestry.soils }
    }

    private fun forestSupply(city: Settlement, worker: CivWorker, building: CivBuilding, sapling: String): Long =
        if (city.autonomy != null) if (city.stock.wood > 0 || building.id.toString() !in city.forestrySeeded.orEmpty()) 4L else 0L
        else CivsEquipment.available(worker, stock(city, building.id))[sapling] ?: 0

    private fun finishForestScan(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        pending: ForestScan): CivWorldWork? {
        while (!pending.scan.complete && !pending.scan.failed && forestBudget()) {
            forestChecksRemaining -= pending.scan.advance(1) { point ->
                read(city, building, world, point, allowOwn = false)?.blockData?.asString
            }
        }
        if (!pending.scan.complete && !pending.scan.failed) return blocked(worker, "searching-work")
        // Only connected, separately grounded trunks prove a surviving neighbour.
        // Shared foliage can also touch a placed log and is not a thinning witness.
        val thinning = pending.scan.retainedCrownReason == "shared-trunks"
        val job = pending.scan.job(pending.roots)?.copy(thinning = thinning,
            seedDeferred = !thinning && city.autonomy != null && city.stock.wood == 0L)
        if (job == null || !CivsForestry.claimClear(city, building.id, job)) {
            forestScans.remove(worker.id)
            forestSearches[worker.id]?.let { search ->
                // Only observed structural failures survive until geometry changes.
                // Missing reads and a competing tree receipt can become available with no block event.
                if (pending.scan.failureReason in setOf("tree-root-changed", "tree-no-natural-crown", "tree-trunk-bounds",
                        "tree-trunk-too-large", "tree-state-limit")) search.remember(search.unsuitable, pending.scan.root)
                else search.retry(pending.scan.root)
            }
            return blocked(worker, "searching-work", point = pending.scan.failurePoint ?: pending.scan.root,
                reason = if (job == null) pending.scan.failureReason ?: "tree-scan-incomplete" else "tree-already-claimed")
        }
        val work = forestBatch(city, worker, building, world, job)
        val status = currentNeed(city, worker)?.status
        if (work != null || !spotSearchPending && status in
            setOf("worksite-no-access", "worksite-obstacle", "worksite-protected", "waiting-saplings")) {
            forestScans.remove(worker.id)
            if (work == null && status == "waiting-saplings") forestSearches[worker.id]?.let { it.remember(it.unfunded, job.root) }
            else if (work == null && status == "worksite-no-access") {
                val key = requireNotNull(standingKey(worker, building, null, job.root))
                deferForest(worker.id, rejectedWorkSpots.remove(worker.id) ?: RejectedWorkSpots(key, job.root))
            }
        }
        return work
    }

    private fun finishForestPlant(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        pending: ForestPlant, paths: Set<Pair<Int, Int>>): CivWorldWork? {
        fun unavailable(temporary: Boolean = false): CivWorldWork? {
            forestSearches[worker.id]?.let { search ->
                if (temporary) search.retry(pending.roots.first(), planting = true)
                else search.remember(search.unplantable, pending.roots.first())
            }
            forestPlants.remove(worker.id)
            return null
        }
        val crown = CivsForestry.crownBounds(pending.roots, pending.timber)
        // Include the largest neighbouring species, then compare both actual
        // crown reserves. Birch must not use oak's spacing for another birch.
        val spacing = 7
        val bounds = CivBox(crown.min.copy(x = crown.min.x - spacing, z = crown.min.z - spacing),
            crown.max.copy(x = crown.max.x + spacing, z = crown.max.z + spacing))
        val width = bounds.max.x - bounds.min.x + 1
        val count = width * (bounds.max.z - bounds.min.z + 1)
        if (!CivsForestry.crownClear(city, pending.roots, pending.timber, paths)) {
            return unavailable()
        }
        // Three observations per column find both mature trunks beneath leaves
        // and passable saplings above ground. An overhanging leaf is not a tree root.
        while (pending.next < count && forestChecksRemaining >= 3 && forestBudget()) {
            val point = CivPosition(bounds.min.x + pending.next % width, bounds.min.y, bounds.min.z + pending.next / width)
            val inCrown = point.x in crown.min.x..crown.max.x && point.z in crown.min.z..crown.max.z
            if (!world.isChunkLoaded(point.x shr 4, point.z shr 4)) {
                if (inCrown) return unavailable(temporary = true)
                pending.next++; continue
            }
            forestChecksRemaining -= 3
            val top = world.getHighestBlockYAt(point.x, point.z, HeightMap.MOTION_BLOCKING_NO_LEAVES)
            val block = read(city, building, world, point.copy(y = top), allowOwn = false)
            val above = read(city, building, world, point.copy(y = top + 1), allowOwn = false)
            val timber = when {
                above?.type?.name?.endsWith("_SAPLING") == true -> above.type.name.removeSuffix("_SAPLING") + "_LOG"
                block?.type?.name?.endsWith("_LOG") == true -> block.type.name.removePrefix("STRIPPED_")
                block?.type?.name?.endsWith("_WOOD") == true -> block.type.name.removePrefix("STRIPPED_").removeSuffix("_WOOD") + "_LOG"
                else -> null
            }
            val neighbourRadius = timber?.let(CivsForestry::crownRadius)
            val crowded = neighbourRadius != null &&
                point.x in crown.min.x - neighbourRadius..crown.max.x + neighbourRadius &&
                point.z in crown.min.z - neighbourRadius..crown.max.z + neighbourRadius
            if (inCrown && (block == null || above == null)) return unavailable(temporary = true)
            if (crowded || inCrown && block?.type in setOf(Material.DIRT_PATH, Material.COARSE_DIRT)) {
                return unavailable()
            }
            pending.next++
        }
        if (pending.next < count) return blocked(worker, "searching-work")
        val sapling = pending.timber.removeSuffix("_LOG") + "_SAPLING"
        if (forestSupply(city, worker, building, sapling) < pending.roots.size) {
            if (city.autonomy != null) forestPlants.remove(worker.id)
            return blocked(worker, "waiting-saplings", sapling, pending.roots.size.toLong())
        }
        val blocks = pending.roots.map { point ->
            val block = read(city, building, world, point, allowOwn = false) ?: return unavailable(temporary = true)
            val soil = read(city, building, world, point.copy(y = point.y - 1), allowOwn = false)
                ?: return unavailable(temporary = true)
            if (!block.type.isAir || soil.type.name !in CivsForestry.soils) return unavailable()
            for (height in 1..4) {
                val headroom = read(city, building, world, point.copy(y = point.y + height), allowOwn = false)
                    ?: return unavailable(temporary = true)
                if (!headroom.type.isAir && headroom.type !in WEEDS && !headroom.type.name.endsWith("_LEAVES")) return unavailable()
            }
            block
        }
        val standing = beside(city, worker, building, world, pending.roots.first(), allowOwn = false) ?: run {
            if (!spotSearchPending) forestPlants.remove(worker.id)
            return null
        }
        forestPlants.remove(worker.id)
        return work(worker, building, standing, blocks.map { change(it, CivsForestry.saplingState(sapling)) },
            mapOf(sapling to pending.roots.size.toLong()), status = "planting-tree", focus = pending.roots.first())
            .let { if (city.autonomy != null) it.copy(equipmentAfter = null) else it }
    }

    private fun forestBatch(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        job: CivForestryJob): CivWorldWork? {
        val existing = city.forestryJobs?.get(building.id.toString())
        if (existing == null && !job.thinning && city.autonomy == null && forestSupply(city, worker, building, job.sapling) < job.roots.size)
            return blocked(worker, "waiting-saplings", job.sapling, job.roots.size.toLong())
        val standing = beside(city, worker, building, world, job.root, allowOwn = false,
            capture = { captureForestBatch(city, worker, building, world, job, WorkApproach(job.root)) }) ?: return null
        return standing.capturedWork?.copy(standing = standing.standing, remoteWork = standing.remoteWork)
            ?: captureForestBatch(city, worker, building, world, job, standing)
    }

    private fun captureForestBatch(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        job: CivForestryJob, standing: WorkApproach): CivWorldWork? {
        val existing = city.forestryJobs?.get(building.id.toString())
        if (!forestBudget()) return blocked(worker, "searching-work")
        // ponytail: one existing <=128-cell captured batch owns the rest of this
        // pulse; a second worker must not multiply its atomic native validation.
        forestChecksRemaining = 0
        val next = CivsForestry.batchEnd(job)
        val changes = mutableListOf<CivPlanBlock>()
        val outputs = linkedMapOf<String, Long>()
        val matcher = CivsForestry.Matcher(job)
        var logs = 0
        for (code in job.cells.subList(job.next, next)) {
            val point = job.position(code)
            val block = read(city, building, world, point, allowOwn = false) ?: return blocked(worker, "worksite-protected", point = point)
            if (!block.type.isAir && !matcher.matches(code, block.blockData.asString) { support ->
                    read(city, building, world, support, allowOwn = false)?.blockData?.asString
                })
                return blocked(worker, "worksite-obstacle", point = point, reason = "tree-cell-changed", blockMaterial = block.type.name)
            if (!block.type.isAir) {
                if (block.type.name == job.timber) logs++
                if (city.autonomy == null) drops(block, tool(worker)).forEach { (material, amount) -> outputs.merge(material, amount, Math::addExact) }
            }
            val after = if (!job.thinning && point in job.roots) CivsForestry.saplingState(job.sapling) else "minecraft:air"
            if (!block.type.isAir || point in job.roots) changes += change(block, after)
        }
        val progress = CivForestryProgress(job, next, outputs, logs)
        val output = if (city.autonomy == null) outputs else emptyMap()
        return work(worker, building, standing, changes,
            if (existing == null && !job.seedDeferred && !job.thinning) mapOf(job.sapling to job.roots.size.toLong()) else emptyMap(), output,
            status = "felling-tree", duration = maxOf(if (job.next == 0) 6_000L else 1_000L, logs * 1_000L), focus = job.root)
            .copy(forestry = progress).let { if (city.autonomy != null) it.copy(equipmentAfter = null) else it }
    }

    /** Spruce, jungle and dark oak use four real, supported saplings for native giant variants. */
    private fun forestPlot(city: Settlement, building: CivBuilding, world: World, root: CivPosition, material: Material,
        budgeted: Boolean = false): List<CivPosition>? {
        fun at(point: CivPosition) = if (budgeted) forestRead(city, building, world, point)
            else read(city, building, world, point, allowOwn = false)
        for (dx in listOf(0, -1)) for (dz in listOf(0, -1)) {
            val cells = (0..1).flatMap { x -> (0..1).map { z -> root.copy(x = root.x + dx + x, z = root.z + dz + z) } }
            if (cells.all { point -> at(point)?.type == material &&
                    at(point.copy(y = point.y - 1))?.type?.name in CivsForestry.soils }) return cells
        }
        return null
    }

    private fun mineOwnedBlock(city: Settlement, building: CivBuilding, local: CivPosition, data: String): Boolean {
        val state = city.minePlans?.get(building.id.toString())
        fun owned(mine: CivBuilding, proof: CivMinePlanState?, legacyProgress: Long?, at: CivPosition): Boolean {
            if (proof?.seals?.get(CivsMineProgress.key(at)) == data) return true
            val current = CivsMinePlan.forBuilding(mine, proof?.version ?: CivsMinePlan.VERSION)
            if (proof != null && current?.expectedAt(at, proof.nextStep,
                    proof.sectionSteps.orEmpty(), mine.rotation) == data) return true
            CivsMineProgress.v5State(proof)?.let { old ->
                if (old.seals?.get(CivsMineProgress.key(at)) == data || CivsMinePlan.forBuilding(mine, 5)?.expectedAt(
                        at, old.nextStep, old.sectionSteps.orEmpty(), mine.rotation) == data) return true
            }
            val legacy = current?.legacyCompleted(legacyProgress ?: 0L)?.get(at)
            if (legacy != null && Bukkit.createBlockData(legacy).also { value ->
                value.rotate(when (Math.floorMod(mine.rotation / 90, 4)) {
                    1 -> org.bukkit.block.structure.StructureRotation.CLOCKWISE_90
                    2 -> org.bukkit.block.structure.StructureRotation.CLOCKWISE_180
                    3 -> org.bukkit.block.structure.StructureRotation.COUNTERCLOCKWISE_90
                    else -> org.bukkit.block.structure.StructureRotation.NONE
                })
            }.asString == data) return true
            val versions = listOf(2 to (proof?.takeIf { it.version == 2 }?.nextStep ?: proof?.v2Step),
                3 to (proof?.takeIf { it.version == 3 }?.nextStep ?: proof?.v3Step),
                4 to (proof?.takeIf { it.version == 4 }?.nextStep ?: proof?.v4Step),
                5 to (proof?.takeIf { it.version == 5 && it.sectionSteps == null }?.nextStep ?: proof?.v5Step))
            return versions.any { (version, prefix) -> prefix != null && prefix > 0 &&
                CivsMinePlan.forBuilding(mine, version)?.expectedAt(at, prefix, emptyMap(), mine.rotation) == data }
        }
        if (owned(building, state, city.mineProgress?.get(building.id.toString()), local)) return true
        val worldPoint = CivsWorksiteLayouts.point(building, local)
        return state?.levels.orEmpty().any { witness -> owned(witness.building, witness.state(), witness.legacyProgress,
            CivsMinePlan.localPoint(witness.building, worldPoint)) }
    }

    /** Same pending-mutation authority at selection, admission, saved receipt and recovery. */
    private fun mineMutationAllowed(city: Settlement, building: CivBuilding, world: World, cell: CivPlanBlock,
        ordinal: Int, worker: CivWorker? = null, budgeted: Boolean = false, generator: Boolean = false,
        sealing: Boolean = false, planVersion: Int = CivsMinePlan.VERSION): Boolean? {
        fun denied(reason: String): Boolean {
            if (worker == null) throw CivsRejection(reason)
            blocked(worker, "worksite-protected", point = cell.position, reason = reason,
                blockMaterial = Bukkit.createBlockData(cell.before).material.name)
            return false
        }
        val plan = CivsMinePlan.forBuilding(building, planVersion) ?: return denied("mine-layout-missing")
        if (ordinal !in plan.steps.indices) return denied("mine-progress-invalid")
        val point = cell.position
        if (!world.isChunkLoaded(point.x shr 4, point.z shr 4)) return denied("chunks-unloaded")
        val block = world.getBlockAt(point.x, point.y, point.z)
        if (!worldAccess.canMine(block)) return denied("mine-claimed")
        val local = CivsMinePlan.localPoint(building, point)
        val before = Bukkit.createBlockData(cell.before).material
        val generatorFace = generator && local == plan.generator &&
            city.minePlans?.get(building.id.toString())?.nextStep == plan.steps.size.toLong() &&
            before in setOf(Material.STONE, Material.COBBLESTONE)
        val paidLiquidSeal = sealing && cell.after == "minecraft:cobblestone" && before in setOf(Material.WATER, Material.LAVA)
        if (!before.isAir && before !in WEEDS && !CivsMineSafety.natural(before) && !generatorFace && !paidLiquidSeal &&
            !mineOwnedBlock(city, building, local, cell.before)) return denied("mine-foreign-block")
        val saved = city.minePlans?.get(building.id.toString())
        val restoredSolid = Bukkit.createBlockData(cell.after).material.isSolid && saved?.let {
            CivsMinePlan.forBuilding(building, it.version)?.expectedAt(local, it.nextStep, it.sectionSteps.orEmpty(), building.rotation)
        } == cell.after
        if (generatorFace || restoredSolid) return true
        val required = CivsMineSafety.requiredCover(plan, ordinal, local)
        if (required == 0) return true
        val cap = civsProtectedBuildings(city).filter { it.id != building.id && CivsAutonomousGeometry.horizontalContains(it.bounds, point) }
            .minOfOrNull { it.bounds.min.y - 1 } ?: world.maxHeight - 1
        val highest = minOf(cap, world.getHighestBlockYAt(point.x, point.z, HeightMap.MOTION_BLOCKING_NO_LEAVES))
        val probe = if (budgeted && worker != null) mineCovers[worker.id]?.takeIf {
            it.world == city.worldId && it.building == building.id && it.x == point.x && it.z == point.z && it.top == highest
        } ?: CivMineCover(city.worldId, building.id, point.x, point.z, highest, world.minHeight)
            .also { mineCovers[worker.id] = it }
        else CivMineCover(city.worldId, building.id, point.x, point.z, highest, world.minHeight)
        while (!probe.finished) {
            if (budgeted && (mineChecksRemaining <= 0 || nanoTime() >= forestDeadline)) {
                spotSearchPending = true
                if (worker != null) blocked(worker, "searching-work")
                return null
            }
            if (budgeted) mineChecksRemaining--
            probe.step { y -> world.getBlockAt(point.x, y, point.z).type }
        }
        val ceiling = CivsWorksiteLayouts.point(building,
            local.copy(y = CivsMineSafety.ceiling(plan, ordinal, local))).y
        if (probe.ground?.let { it - ceiling >= required } != true) return denied("mine-thin-cover")
        return true
    }

    /** No transient cache or actor is authority for a durable mine write. */
    internal fun validateMineReceiptNative(city: Settlement, work: CivWorldWork) {
        try { validateMineReceiptBlocks(city, work) }
        catch (failure: CivsRejection) {
            // A failed final observation supersedes an earlier completed scan. Keep
            // its exact action/approach proof but re-read this frontier next pulse.
            mineSafety[work.workerId]?.next = 0
            mineCovers.remove(work.workerId)
            throw failure
        }
    }

    private fun validateMineReceiptBlocks(city: Settlement, work: CivWorldWork) {
        val building = city.building(work.buildingId)?.takeIf { it.type == BuildingType.QUARRY }
            ?: throw CivsRejection("building-not-found")
        val world = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("world-unloaded")
        val plan = CivsMinePlan.forBuilding(building, work.miningPlanVersion ?: CivsMinePlan.VERSION)
            ?: throw CivsRejection("mine-layout-missing")
        val reserved = work.changes.mapTo(hashSetOf()) { it.position }
        val pending = work.changes.filter { cell ->
            requireCivs(cell.position.y in world.minHeight until world.maxHeight, "outside-world")
            requireCivs(world.isChunkLoaded(cell.position.x shr 4, cell.position.z shr 4), "chunks-unloaded")
            val actual = world.getBlockAt(cell.position.x, cell.position.y, cell.position.z).blockData.asString
            requireCivs(actual == cell.before || actual == cell.after, "world-changed")
            actual != cell.after
        }
        // Already applied paid blocks may be retired even if protection subsequently changes.
        if (pending.isEmpty()) return
        for (index in CivsMineProgress.initialSpineAccess(building, city.minePlans?.get(building.id.toString()), work)) {
            val step = plan.steps[index]
            val point = CivsWorksiteLayouts.point(building, step.point)
            val block = read(city, building, world, point) ?: throw CivsRejection("protected-area")
            val expected = plan.expectedAfter(index, building.rotation)
            val soundBacking = step.shaftSupport && expected == "minecraft:cobblestone" &&
                block.type.isOccluding && !block.type.hasGravity() && block.type !in UNBREAKABLE
            requireCivs(block.blockData.asString == expected || soundBacking, "stale-worksite")
            requireCivs(worldAccess.canModify(city.owner, block, Bukkit.createBlockData(expected).material), "lands-denied")
            requireCivs(mineMutationAllowed(city, building, world, change(block, expected), index,
                planVersion = plan.version) == true, "protected-area")
        }
        val ordinal = work.miningOrdinal ?: work.focus?.let { focus ->
            plan.steps.indexOfFirst { it.point == CivsMinePlan.localPoint(building, focus) }.takeIf { it >= 0 }
        } ?: 0
        pending.forEach { cell ->
            val block = read(city, building, world, cell.position, reserved = reserved) ?: throw CivsRejection("protected-area")
            requireCivs(worldAccess.canModify(city.owner, block, Bukkit.createBlockData(cell.after).material), "lands-denied")
            val sealing = work.status == "securing-mine"
            if (sealing) {
                val origins = work.miningFrontier?.let(::listOf) ?: listOfNotNull(
                    plan.steps.getOrNull((work.miningAccessStep ?: work.miningStep ?: 0).toInt())?.point,
                    plan.steps.getOrNull((work.miningAccessStep ?: work.miningStep ?: 0).toInt() - 1)?.point)
                val local = CivsMinePlan.localPoint(building, cell.position)
                requireCivs(origins.any { origin -> mineFrontier(city, building, world, plan, origin,
                    work.miningExcavation?.let(::setOf).orEmpty(), accessStep = work.miningAccessStep ?: work.miningStep ?: 0,
                    reserved = reserved, sectionSteps = work.miningAccessSections.orEmpty())?.any {
                        it.block == local && it.material?.createBlockData()?.asString == cell.after
                    } == true }, "stale-worksite")
            }
            requireCivs(mineMutationAllowed(city, building, world, cell, ordinal, generator = work.miningGenerator,
                sealing = sealing, planVersion = plan.version) == true, "protected-area")
        }
        val excavations = pending.filter { Bukkit.createBlockData(it.after).material.isAir }
            .mapTo(hashSetOf()) { CivsMinePlan.localPoint(building, it.position) }
        if (excavations.isEmpty()) return
        val around = excavations.singleOrNull() ?: throw CivsRejection("stale-worksite")
        val frontier = mineFrontier(city, building, world, plan, around, excavations,
            accessStep = work.miningAccessStep ?: work.miningStep ?: 0, reserved = reserved,
            sectionSteps = work.miningAccessSections.orEmpty()) ?: throw CivsRejection("protected-area")
        val deferred = work.miningDeferredSeals.orEmpty()
        requireCivs(deferred.size <= 54 && deferred.all { p -> frontier.any { it.block == p && it.material == Material.COBBLESTONE } }, "stale-worksite")
        for (task in frontier) {
            if (task.transferFooting) continue
            val exposed = observeMineHazard(world, CivsWorksiteLayouts.point(building, task.exposed))
                ?: throw CivsRejection("chunks-unloaded")
            if (!mineExposed(task, excavations, exposed)) continue
            val neighbour = observeMineHazard(world, CivsWorksiteLayouts.point(building, task.block))
                ?: throw CivsRejection("chunks-unloaded")
            if (task.block in deferred && neighbour.type.isAir) continue
            requireCivs(!mineProtectionNeeded(neighbour, task.material), "stale-worksite")
        }
    }

    private fun mine(city: Settlement, worker: CivWorker, building: CivBuilding, world: World): CivWorldWork? {
        val result = mineNext(city, worker, building, world)
        if (result == null && !spotSearchPending && currentNeed(city, worker)?.status?.let {
                it != "searching-work" && !it.startsWith("waiting-") } == true) {
            mineExecutions[building.id]?.let { execution -> execution.selected?.let { section ->
                execution.lastDenial = currentNeed(city, worker)
                execution.lastDeniedSection = section
                execution.retryAt[section] = Bukkit.getCurrentTick().toLong() + 100
                execution.selected = null
            } }
        }
        return result
    }

    private fun mineNext(city: Settlement, worker: CivWorker, building: CivBuilding, world: World): CivWorldWork? {
        val autonomous = CivsAutonomousWorksites.supports(city, building)
        if (!autonomous && CivsEquipment.slots(worker)[4]?.material?.endsWith("_PICKAXE") != true) return null
        val geometry = CivsMinePlan.forBuilding(building)
            ?: return blocked(worker, "worksite-layout", point = building.anchor, reason = "mine-layout-missing")
        // A seal's hazard observation can use the last native unit in a slice.
        // Resume its cover phase first, so even a one-unit budget makes progress.
        mineCovers[worker.id]?.takeIf { it.world == city.worldId && it.building == building.id && !it.finished }?.let { cover ->
            if (!world.isChunkLoaded(cover.x shr 4, cover.z shr 4))
                return blocked(worker, "worksite-unloaded", point = CivPosition(cover.x, cover.next, cover.z))
            while (!cover.finished && mineChecksRemaining > 0 && nanoTime() < forestDeadline) {
                mineChecksRemaining--
                cover.step { y -> world.getBlockAt(cover.x, y, cover.z).type }
            }
            if (!cover.finished || mineChecksRemaining == 0) {
                spotSearchPending = true
                return blocked(worker, "searching-work")
            }
        }
        val steps = geometry.steps
        mineBounds.getOrPut(building.id) {
            // Static coordinates once per assignment, never a native world snapshot.
            val a = CivsWorksiteLayouts.point(building, CivPosition(steps.minOf { it.point.x } - 1, geometry.lowest - 1, steps.minOf { it.point.z } - 1))
            val b = CivsWorksiteLayouts.point(building, CivPosition(steps.maxOf { it.point.x } + 1, geometry.bottom.y + 3, steps.maxOf { it.point.z } + 1))
            CivBox(CivPosition(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z)),
                CivPosition(maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z)))
        }
        // A geometry's ordinal is never reinterpreted as another version's cursor.
        // The first real receipt preserves the older prefixes and starts the new plan.
        val state = city.minePlans?.get(building.id.toString())
        val progress = state?.takeIf { it.version == CivsMinePlan.VERSION }?.nextStep ?: 0L
        if (state?.version?.let { it !in 2..CivsMinePlan.VERSION } == true || progress !in 0..steps.size.toLong())
            return blocked(worker, "worksite-layout", point = building.anchor, reason = "mine-progress-invalid")
        val v2Step = state?.takeIf { it.version == 2 }?.nextStep ?: state?.v2Step ?: 0L
        val v2Plan = CivsMinePlan.forBuilding(building, 2)
        val execution = mineExecutions[building.id]?.takeIf { it.building == building && it.saved == state &&
            it.legacy == city.mineProgress?.get(building.id.toString()) } ?: run {
            val previous = mineExecutions[building.id]
            val v1 = geometry.legacyCompleted(city.mineProgress?.get(building.id.toString()) ?: 0L)
                ?: return blocked(worker, "worksite-layout", point = building.anchor, reason = "legacy-mine-progress-invalid")
            val v2 = v2Plan?.completed(v2Step)
                ?: return blocked(worker, "worksite-layout", point = building.anchor, reason = "v2-mine-progress-invalid")
            val v3Step = state?.takeIf { it.version == 3 }?.nextStep ?: state?.v3Step ?: 0L
            val v3 = CivsMinePlan.forBuilding(building, 3)?.completed(v3Step)
                ?: return blocked(worker, "worksite-layout", point = building.anchor, reason = "v3-mine-progress-invalid")
            val v4Step = state?.takeIf { it.version == 4 }?.nextStep ?: state?.v4Step ?: 0L
            val v4 = CivsMinePlan.forBuilding(building, 4)?.completed(v4Step)
                ?: return blocked(worker, "worksite-layout", point = building.anchor, reason = "v4-mine-progress-invalid")
            val v5 = CivsMineProgress.v5State(state)?.let { old ->
                requireNotNull(CivsMinePlan.forBuilding(building, 5)).let { plan -> plan.steps.withIndex()
                    .filter { plan.completed(it.index, old.nextStep, old.sectionSteps.orEmpty()) }
                    .associate { it.value.point to it.value.after } }
            }.orEmpty()
            val initial = geometry.normalizedProgress(progress,
                if (state?.version == geometry.version) state.sectionSteps.orEmpty()
                else CivsMineProgress.retainedSpine(building, state))
            MineExecution(building, state, city.mineProgress?.get(building.id.toString()), progress, initial.first,
                initial.second.toMutableMap(),
                selected = previous?.selected, cursor = previous?.cursor ?: 0,
                retryAt = previous?.retryAt?.toMutableMap() ?: hashMapOf(), lastDenial = previous?.lastDenial,
                lastDeniedSection = previous?.lastDeniedSection, legacyBlocks = v1 + v2 + v3 + v4 + v5)
                .also { mineExecutions[building.id] = it }
        }
        val legacy = execution.legacyBlocks
        val sectionKeys = geometry.sections.keys.toList()
        fun nextOrdinal(section: String): Int? = geometry.sections.getValue(section)
            .getOrNull(geometry.sectionCount(section, execution.prefix, execution.sections).toInt())
        if (nextOrdinal("spine") != null) execution.selected = "spine"
        if (execution.selected?.let { nextOrdinal(it) == null ||
                execution.retryAt.getOrDefault(it, 0) > Bukkit.getCurrentTick() } == true) execution.selected = null
        if (execution.selected == null) {
            for (offset in sectionKeys.indices) {
                val index = Math.floorMod(execution.cursor + offset, sectionKeys.size)
                val key = sectionKeys[index]
                if (nextOrdinal(key) == null || execution.retryAt.getOrDefault(key, 0) > Bukkit.getCurrentTick() ||
                    !geometry.sectionReady(key, execution.prefix, execution.sections)) continue
                execution.selected = key
                execution.cursor = (index + 1) % sectionKeys.size
                break
            }
        }
        val selected = execution.selected
        val scanStart = selected?.let(::nextOrdinal) ?: steps.size
        execution.ordinal = minOf(scanStart, steps.lastIndex)
        mineCursors[building.id] = Triple(building.level, progress, scanStart)
        // Take a useful batch before descending, then use it until a supply runs out.
        if (!autonomous && scanStart < steps.size && CivsLogistics.QUARRY_SUPPLIES.keys.any { CivsEquipment.plainCount(worker, it) == 0 })
            return prepareQuarry(city, worker, building, world)
        // A committed cut can expose a cave that was unreachable before it. Derive
        // this repair from the durable cursor, including after restart or the last step.
        if (!execution.checkedExcavation || worker.id in mineFrontierDirty) {
            val previous = state?.excavation?.let { point -> CivsMinePlan.Step(point, null, "shaft") }
                ?: steps.getOrNull(progress.toInt() - 1)
            previous?.let {
                val protection = secureMine(city, worker, building, world, geometry, it, execution.ordinal.toLong(), emptySet())
                if (protection != null || spotSearchPending || currentNeed(city, worker)?.status != "searching-work") return protection
                // Keep the oldest opening until its actual AIR shell can be reached.
                // Lower ladder clearance must not replace this durable repair target.
                execution.unsealedExcavation = it.point.takeIf { mineSafety[worker.id]?.deferredAirWalls?.isNotEmpty() == true }
            }
            // Keep a completed previous check across bounded pulses. A receipt's
            // excavation invalidate clears it even when progress did not move.
            execution.checkedExcavation = true
            mineFrontierDirty.remove(worker.id)
        }
        if (scanStart == steps.size) return if (execution.prefix == steps.size.toLong())
            generateStone(city, worker, building, world, geometry, execution.prefix) else {
                val denial = execution.lastDenial
                blocked(worker, denial?.status ?: "worksite-protected", point = denial?.point,
                    reason = denial?.reason ?: "mine-sections-blocked", blockMaterial = denial?.blockMaterial)
            }
        val indices = geometry.sections.getValue(requireNotNull(selected))
        val startOffset = geometry.sectionCount(selected, execution.prefix, execution.sections).toInt()
        for (offset in startOffset until minOf(indices.size, startOffset + columnsRemaining)) {
            if (nanoTime() >= forestDeadline) return blocked(worker, "searching-work")
            columnsRemaining--
            val ordinal = indices[offset]
            execution.ordinal = ordinal
            mineCursors[building.id] = Triple(building.level, progress, ordinal)
            val step = steps[ordinal]
            val local = step.point
            val after = step.after
            val point = CivsWorksiteLayouts.point(building, local)
            val block = read(city, building, world, point, worker = worker) ?: return null
            val target = after?.let { Bukkit.createBlockData(it).also { data ->
                data.rotate(when (Math.floorMod(building.rotation / 90, 4)) {
                    1 -> org.bukkit.block.structure.StructureRotation.CLOCKWISE_90
                    2 -> org.bukkit.block.structure.StructureRotation.CLOCKWISE_180
                    3 -> org.bukkit.block.structure.StructureRotation.COUNTERCLOCKWISE_90
                    else -> org.bukkit.block.structure.StructureRotation.NONE
                })
            } }
            val next = ordinal.toLong() + 1
            // Existing sound shaft walls already support a ladder; replacing them first can
            // deadlock a miner who must dig the first stone to obtain cobblestone.
            val shaftSupport = step.shaftSupport
            val soundSupport = block.type.isOccluding && !block.type.hasGravity() && block.type !in UNBREAKABLE
            val soundWall = shaftSupport && target?.material == Material.COBBLESTONE && soundSupport
            val retainedLight = block.type == Material.TORCH &&
                (listOfNotNull(geometry.entranceLanding) + geometry.floors).any { local in it.torches }
            if (soundWall || target?.matches(block.blockData) == true || target == null && (block.type.isAir || retainedLight)) {
                // Already-dug/legacy cells and unchanged-cursor prerequisites still
                // need their currently open frontier secured before skipping ahead.
                val protection = secureMine(city, worker, building, world, geometry, step, progress, emptySet(), next)
                if (protection != null || spotSearchPending || currentNeed(city, worker)?.status != "searching-work") return protection
                execution.sections[selected] = offset.toLong() + 1
                execution.prefix = geometry.normalizedProgress(execution.basePrefix, execution.sections).first
                // Record skipped already-dug cells with the next actual work, without a save per empty cell.
                continue
            }
            // A legacy completed cell that now contains a new solid object is not a
            // license to remodel it. Gravity falls and missing supports use real repairs.
            val previousMaterial = legacy[local]?.let { Bukkit.createBlockData(it).material }
            if (legacy.containsKey(local) && !block.type.isAir && !block.type.hasGravity() && block.type != Material.TORCH &&
                block.type != previousMaterial) {
                // V2 could retain a sound native wall instead of placing its planned
                // cobblestone. Only that final completed support step carries this
                // historical permission; paid floors and later replacements do not.
                val previousV2 = v2Plan?.steps.orEmpty().asSequence().take(v2Step.toInt()).lastOrNull { it.point == local }
                // Cobblestone lining is also placed by our paid safety frontier in
                // formerly empty legacy cells. The new ordered cut may remove that
                // ordinary lining; block entities, protection and native receipts
                // are still checked. Other changed legacy materials remain blocked.
                val mineLining = after == null && legacy[local] == null && block.type == Material.COBBLESTONE
                if (!mineLining && (previousV2?.shaftSupport != true || previousV2.after != "minecraft:cobblestone" || !soundSupport))
                    return blocked(worker, "worksite-obstacle", point = point, reason = "legacy-cell-changed", blockMaterial = block.type.name)
            }
            if (block.isLiquid) return blocked(worker, "worksite-liquid", point = point,
                reason = "liquid", blockMaterial = block.type.name)
            if (block.type in UNBREAKABLE) return blocked(worker, "worksite-unbreakable", point = point,
                reason = "unbreakable", blockMaterial = block.type.name)
            // Identify the actual physical clearance before checking its frontier. A
            // fallen block or central shaft prerequisite is not the paid support step.
            var prerequisite: Block? = null
            if (!shaftSupport && target?.material == Material.COBBLESTONE) {
                for (height in 1..3) {
                    val fallen = read(city, building, world, point.copy(y = point.y + height), worker = worker) ?: return null
                    if (fallen.type.hasGravity()) { prerequisite = fallen; break }
                }
            }
            if (shaftSupport) {
                val shaftPoint = CivsWorksiteLayouts.point(building, geometry.ladderForBacking(local))
                val shaft = read(city, building, world, shaftPoint, worker = worker) ?: return null
                if (shaft.isLiquid) return blocked(worker, "worksite-liquid", point = shaftPoint,
                    reason = "liquid", blockMaterial = shaft.type.name)
                if (shaft.type in UNBREAKABLE) return blocked(worker, "worksite-unbreakable", point = shaftPoint,
                    reason = "unbreakable", blockMaterial = shaft.type.name)
                if (!shaft.type.isAir && shaft.type != Material.LADDER) prerequisite = shaft
            }
            val actualExcavation = prerequisite ?: block.takeIf { target == null ||
                target.material == Material.LADDER && !block.type.isAir && block.type != Material.LADDER }
            val excavations = actualExcavation?.let { setOf(CivsMinePlan.localPoint(building, position(it))) }.orEmpty()
            val mutation = if (actualExcavation != null) change(actualExcavation, "minecraft:air")
                else change(block, requireNotNull(target).asString)
            if (mineMutationAllowed(city, building, world, mutation, ordinal, worker, budgeted = true) != true) return null
            val protection = secureMine(city, worker, building, world, geometry, step, next - 1, excavations)
            if (protection != null || spotSearchPending || currentNeed(city, worker)?.status != "searching-work") return protection
            if (prerequisite != null) {
                val standing = beside(city, worker, building, world, position(prerequisite), ladder = true,
                    miningStep = next - 1, preferred = step.approach?.let { CivsWorksiteLayouts.point(building, it) }) ?: return null
                return work(worker, building, standing, listOf(change(prerequisite, "minecraft:air")),
                    outputs = if (autonomous) emptyMap() else drops(prerequisite, tool(worker)), status = "digging-mine", duration = 2_000,
                    miningStep = next - 1, miningAccessStep = next - 1)
            }
            val supportWork = target?.material?.takeIf {
                it in setOf(Material.COBBLESTONE, Material.LADDER, Material.TORCH) || it.name in geometry.structuralMaterials
            }
            // Keep the same durable step until the paid ladder is installed.
            val excavating = target?.material == Material.LADDER && !block.type.isAir && block.type != Material.LADDER
            val costs = if (excavating) emptyMap() else supportWork?.let { mapOf(it.name to 1L) }.orEmpty()
            costs.keys.firstOrNull { !autonomous && CivsEquipment.plainCount(worker, it) < 1 }?.let { material ->
                return prepareQuarry(city, worker, building, world, material)
            }
            val standing = beside(city, worker, building, world, point, ladder = true,
                miningStep = if (excavating) next - 1 else next, miningAccessStep = next - 1,
                preferred = step.approach?.let { CivsWorksiteLayouts.point(building, it) }) ?: return null
            val outputs = if (autonomous || block.type.isAir) emptyMap() else drops(block, tool(worker))
            return work(worker, building, standing, listOf(change(block, if (excavating) "minecraft:air" else target?.asString ?: "minecraft:air")), costs, outputs,
                "digging-mine", duration = 2_000, miningStep = if (excavating) next - 1 else next,
                miningAccessStep = next - 1)
        }
        if (execution.prefix == steps.size.toLong()) return generateStone(city, worker, building, world, geometry, execution.prefix)
        return null
    }

    private fun generateStone(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        geometry: CivsMinePlan, progress: Long): CivWorldWork? {
        val autonomous = CivsAutonomousWorksites.supports(city, building)
        val local = requireNotNull(geometry.generator)
        val point = CivsWorksiteLayouts.point(building, local)
        val block = read(city, building, world, point, worker = worker) ?: return null
        if (block.type != Material.COBBLESTONE && block.type != Material.STONE) return blocked(worker, "worksite-obstacle", point = point,
            reason = "generator-face-changed", blockMaterial = block.type.name)
        val nextFace = if (block.type == Material.COBBLESTONE) "minecraft:stone" else "minecraft:cobblestone"
        if (mineMutationAllowed(city, building, world, change(block, nextFace), geometry.steps.lastIndex,
                worker, budgeted = true, generator = true) != true) return null
        val localStanding = requireNotNull(geometry.generatorStanding)
        val support = localStanding.copy(y = localStanding.y - 1)
        val protection = secureMine(city, worker, building, world, geometry,
            CivsMinePlan.Step(support, "minecraft:cobblestone", "gallery", geometry.floors.last().index, "generator"),
            progress, emptySet())
        if (protection != null || spotSearchPending || currentNeed(city, worker)?.status != "searching-work") return protection
        val floor = geometry.floors.last()
        for (torch in floor.torches.filter { kotlin.math.abs(it.x - localStanding.x) + kotlin.math.abs(it.z - localStanding.z) == 1 }) {
            val light = read(city, building, world, CivsWorksiteLayouts.point(building, torch), worker = worker) ?: return null
            if (light.type == Material.TORCH) continue
            if (!light.type.isAir) return blocked(worker, "worksite-obstacle", point = position(light),
                reason = "generator-light-changed", blockMaterial = light.type.name)
            if (!autonomous && CivsEquipment.plainCount(worker, "TORCH") == 0) return prepareQuarry(city, worker, building, world)
            val standing = beside(city, worker, building, world, position(light), ladder = true, miningStep = progress) ?: return null
            return work(worker, building, standing, listOf(change(light, "minecraft:torch")),
                inputs = mapOf("TORCH" to 1L), status = "securing-mine", duration = 2_000, miningStep = progress)
                .copy(miningFrontier = torch, miningExcavation = null)
        }
        val standing = beside(city, worker, building, world, point, ladder = true, miningStep = geometry.steps.size.toLong(),
            preferred = CivsWorksiteLayouts.point(building, localStanding)) ?: return null
        val outputs = if (autonomous) emptyMap() else {
            val roll = java.util.concurrent.ThreadLocalRandom.current().nextInt(100)
            val output = generatorOutput(roll, roll != 99 || Bukkit.createBlockData("minecraft:iron_ore").isPreferredTool(tool(worker)))
            mapOf(output to 1L)
        }
        // This permanent face is worked physically; the existing native receipt
        // atomically captures one item, one tool use and the completed work cycle.
        val work = work(worker, building, standing, listOf(change(block, nextFace)),
            outputs = outputs, status = "digging-mine", duration = 6_000, miningStep = geometry.steps.size.toLong(),
            miningGenerator = true)
        when (workAccessible(city, building, world, work, budgeted = true)) {
            null -> return blocked(worker, "searching-work")
            false -> return blocked(worker, "worksite-no-access", point = point,
                reason = "generator-no-supported-position", blockMaterial = block.type.name)
            true -> Unit
        }
        return work
    }

    /** Fixed-size shaft/access frontier; native checks are still shared across bounded pulses. */
    private fun mineFrontier(city: Settlement, building: CivBuilding, world: World,
        geometry: CivsMinePlan, around: CivPosition, excavations: Set<CivPosition>,
        worker: CivWorker? = null, accessStep: Long, reserved: Set<CivPosition> = emptySet(),
        sectionSteps: Map<String, Long> = emptyMap()): List<MineProtection>? {
        val sides = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        val effect = excavations.singleOrNull() ?: around
        val connected = hashSetOf<CivPosition>()
        val landings = listOfNotNull(geometry.entranceLanding) + geometry.floors
        // Deep access is a narrow ladder until its spine is paid; enclose that passage locally.
        // The frozen V5 rows retain their wider frontier, including the upper boundary row.
        val oldLowest = geometry.takeIf { it.version == 6 &&
            it.sectionCount("spine", accessStep, sectionSteps) < it.sections.getValue("spine").size }
            ?.forVersion(5)?.lowest
        val narrowColumns = geometry.ladderAt(effect.y).let { setOf(it.x to it.z) }
        fun columnsAt(y: Int) = if (oldLowest != null && y < oldLowest) narrowColumns else geometry.shaftColumns
        if ((effect.x to effect.z) in geometry.shaftColumns) {
            val open = hashSetOf<CivPosition>()
            // Only the current two-row air component belongs to this frontier. A
            // prospective cut must not connect a cave before its real receipt does.
            for (y in maxOf(geometry.lowest, effect.y)..minOf(geometry.bottom.y, effect.y + 1)) {
                for ((x, z) in columnsAt(y)) {
                    val local = CivPosition(x, y, z)
                    val observed = CivsWorksiteLayouts.point(building, local)
                    val block = observeMineHazard(world, observed) ?: run {
                        if (worker == null) throw CivsRejection("chunks-unloaded")
                        blocked(worker, "worksite-unloaded", point = observed, reason = "hazard-neighbour-unavailable")
                        return null
                    }
                    if (block.type.isAir || block.type == Material.LADDER || block.type == Material.TORCH) open += local
                }
            }
            val pending = ArrayDeque(open.filter { local ->
                val ladder = geometry.ladderAt(local.y)
                local.x == ladder.x && local.z == ladder.z ||
                    local.y >= geometry.transferFeetY && local.x == geometry.bottom.x && local.z == geometry.bottom.z ||
                    local.y == geometry.bottom.y && (local.x to local.z) in geometry.openingColumns
            })
            while (pending.isNotEmpty()) {
                val point = pending.removeFirst()
                if (!connected.add(point)) continue
                val neighbours = sides.map { (dx, dz) -> point.copy(x = point.x + dx, z = point.z + dz) } +
                    listOf(point.copy(y = point.y - 1), point.copy(y = point.y + 1))
                pending.addAll(neighbours.filter { it in open && it !in connected })
            }
        }
        return buildList {
            if ((effect.x to effect.z) in geometry.shaftColumns) {
                if (effect.y == geometry.transferFeetY) {
                    // Legacy air can expose this row before its later paid floor steps.
                    // Bridge only already-open body cells, starting at the existing ladder.
                    geometry.transferFloor.filter { support ->
                        val feet = support.copy(y = support.y + 1)
                        geometry.containsWorkStanding(feet) && feet in connected && feet.copy(y = feet.y + 1) in connected
                    }.sortedWith(compareBy<CivPosition> {
                        kotlin.math.abs(it.x - geometry.bottom.x) + kotlin.math.abs(it.z - geometry.bottom.z)
                    }.thenBy { it.x }.thenBy { it.z }).forEach { support ->
                        add(MineProtection(support.copy(y = support.y + 1), support, Material.COBBLESTONE, transferFooting = true))
                    }
                }
                val central = geometry.ladderAt(effect.y).let { effect.x == it.x && effect.z == it.z } ||
                    effect.y >= geometry.transferFeetY && effect.x == geometry.bottom.x && effect.z == geometry.bottom.z
                for (y in maxOf(geometry.lowest, effect.y)..minOf(geometry.bottom.y, effect.y + 1)) {
                    for ((x, z) in columnsAt(y)) {
                        if (CivPosition(x, y, z) !in connected) continue
                        // Opening the central column touches only the previous outer ring.
                        // Widening touches this ring and the preceding face of its own column.
                        if (excavations.isNotEmpty() &&
                            (central && y == effect.y || !central && y > effect.y && (x != effect.x || z != effect.z))) continue
                        for ((dx, dz) in sides) {
                            val wall = CivPosition(x + dx, y, z + dz)
                            if ((wall.x to wall.z) in columnsAt(y)) continue
                            if (landings.any { floor -> floor.cells.any {
                                    it.x == wall.x && it.z == wall.z && wall.y in it.y + 1..it.y + floor.height
                                } } && (geometry.builtStanding(wall, accessStep, sectionSteps) ||
                                    geometry.builtStanding(wall.copy(y = wall.y - 1), accessStep, sectionSteps))) continue
                            add(MineProtection(CivPosition(x, y, z), wall, Material.COBBLESTONE))
                        }
                    }
                }
            } else {
                for (floor in landings) {
                    val support = floor.cells.firstOrNull { it.x == effect.x && it.z == effect.z && effect.y in it.y..it.y + floor.height }
                        ?: continue
                    val feet = support.copy(y = support.y + 1)
                    add(MineProtection(feet, support, Material.COBBLESTONE))
                    add(MineProtection(support.copy(y = support.y + floor.height), support.copy(y = support.y + floor.height + 1), Material.COBBLESTONE))
                    for ((dx, dz) in sides) {
                        val x = support.x + dx; val z = support.z + dz
                        if ((x to z) in geometry.shaftColumns || floor.cells.any { it.x == x && it.z == z }) continue
                        for (height in 1..floor.height) add(MineProtection(support.copy(y = support.y + height),
                            CivPosition(x, support.y + height, z), Material.COBBLESTONE))
                    }
                    if (feet in floor.torches) add(MineProtection(feet, feet, Material.TORCH))
                }
            }
            for (excavated in excavations) {
                val neighbours = sides.map { (dx, dz) -> excavated.copy(x = excavated.x + dx, z = excavated.z + dz) } +
                    listOf(excavated.copy(y = excavated.y - 1), excavated.copy(y = excavated.y + 1))
                for (adjacent in neighbours) {
                    // An outer wall already has an ordinary paid sealing task. A liquid
                    // within the planned opening must block; temporary inner walls would
                    // immediately be dug again and could never make this opening safe.
                    if (none { it.block == adjacent && !it.transferFooting }) add(MineProtection(excavated, adjacent, null))
                }
            }
        }.distinct().also { check(it.size <= 54 + geometry.transferFloor.size) }
    }

    private fun mineExposed(task: MineProtection, excavations: Set<CivPosition>, opening: Block): Boolean {
        val aboutToOpen = task.exposed in excavations && task.block != task.exposed
        // Even a one-block opening can admit liquid; it need not fit a resident.
        return aboutToOpen || opening.type.isAir || opening.type == Material.LADDER || opening.type == Material.TORCH
    }

    private fun mineProtectionNeeded(block: Block, material: Material?): Boolean {
        if (material == null) return block.isLiquid
        val safe = if (material == Material.TORCH) block.type == Material.TORCH
            else block.type.isOccluding && !block.type.hasGravity()
        return !safe && (block.type.isAir || block.isLiquid || block.type in WEEDS)
    }

    /** Repair only this frontier. Earlier completed columns never enter another branch's work. */
    private fun secureMine(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        geometry: CivsMinePlan, step: CivsMinePlan.Step, progress: Long, excavations: Set<CivPosition>,
        effectiveProgress: Long = progress): CivWorldWork? {
        val around = step.point
        val execution = mineExecutions[building.id]
        val access = execution?.prefix ?: effectiveProgress
        val sections = execution?.sections?.toMap().orEmpty()
        val tasks = mineFrontier(city, building, world, geometry, around, excavations, worker, access,
            sectionSteps = sections) ?: return null
        val scan = mineSafety[worker.id]?.takeIf { it.building == building && it.target == around && it.progress == progress &&
            it.excavation == excavations.singleOrNull() && it.accessProgress == access && it.sections == sections && it.frontier == tasks }
            ?: MineSafetyScan(building, around, progress, excavations.singleOrNull(), access, tasks, sections)
                .also { mineSafety[worker.id] = it }
        repeat(mineChecksRemaining) {
            if (nanoTime() >= forestDeadline) {
                spotSearchPending = true
                return blocked(worker, "searching-work")
            }
            val task = tasks.getOrNull(scan.next) ?: return null
            mineChecksRemaining--
            val exposed = CivsWorksiteLayouts.point(building, task.exposed)
            val opening = observeMineHazard(world, exposed) ?: return blocked(worker, "worksite-unloaded", point = exposed)
            if (!mineExposed(task, excavations, opening)) { scan.next++; return@repeat }
            val point = CivsWorksiteLayouts.point(building, task.block)
            val block = observeMineHazard(world, point) ?: run {
                if (!task.transferFooting) return null
                scan.next++
                blocked(worker, "searching-work")
                return@repeat
            }
            if (task.transferFooting && !block.type.isAir) { scan.next++; return@repeat }
            if (!mineProtectionNeeded(block, task.material)) { scan.next++; return@repeat }
            if (task.material == null) return blocked(worker, "worksite-liquid", point = point,
                reason = "adjacent-liquid", blockMaterial = block.type.name)
            if (read(city, building, world, point, worker = worker) == null) return null
            if (!CivsAutonomousWorksites.supports(city, building) && CivsEquipment.plainCount(worker, task.material.name) == 0)
                return prepareQuarry(city, worker, building, world)
            if (mineMutationAllowed(city, building, world, change(block, task.material.createBlockData().asString),
                    execution?.ordinal ?: progress.toInt(), worker, budgeted = true, sealing = true) != true) return null
            val miningStep = city.minePlans?.get(building.id.toString())?.takeIf { it.version == CivsMinePlan.VERSION }?.nextStep ?: 0L
            val standing = beside(city, worker, building, world, point, ladder = true, miningStep = miningStep,
                miningAccessStep = effectiveProgress,
                preferred = step.approach?.let { CivsWorksiteLayouts.point(building, it) }) ?: run {
                if (!spotSearchPending && task.transferFooting && currentNeed(city, worker)?.status == "worksite-no-access") {
                    scan.next++
                    blocked(worker, "searching-work")
                    return@repeat
                }
                // A solid current cut can hide an AIR shell from every native ray.
                // Finish that single cut, then the durable previous-step check repairs
                // its frontier before another cut, including after a restart.
                val failure = currentNeed(city, worker)
                val hiddenByCurrentCut = task.exposed in excavations && task.block != task.exposed &&
                    opening.type.isOccluding && !opening.type.hasGravity() && failure?.reason == "line-of-sight-blocked"
                // A one-high shaft cut can also expose a far corner before its ordered lower row.
                val lowerRowEnd = geometry.lowerClearanceEnd(task.exposed)
                if (!spotSearchPending && failure?.status == "worksite-no-access" &&
                    task.material == Material.COBBLESTONE && block.type.isAir &&
                    (hiddenByCurrentCut || lowerRowEnd > 0 && !geometry.completed(lowerRowEnd - 1, access, sections))) {
                    scan.deferredAirWalls += task.block
                    scan.next++
                    blocked(worker, "searching-work")
                    return@repeat
                }
                return null
            }
            return work(worker, building, standing, listOf(change(block, task.material.createBlockData().asString)),
                inputs = mapOf(task.material.name to 1L), status = "securing-mine", duration = 2_000,
                miningStep = miningStep, miningAccessStep = effectiveProgress)
        }
        if (scan.next < tasks.size) { spotSearchPending = true; blocked(worker, "searching-work") }
        return null
    }

    private fun requireCivs(condition: Boolean, reason: String) { if (!condition) throw CivsRejection(reason) }

    /** Hazard probes never authorize changing their neighbour (which may belong to another building). */
    private fun observeMineHazard(world: World, point: CivPosition): Block? =
        if (point.y in world.minHeight until world.maxHeight && world.isChunkLoaded(point.x shr 4, point.z shr 4))
            world.getBlockAt(point.x, point.y, point.z) else null

    private fun readMineProtection(city: Settlement, building: CivBuilding, world: World, task: MineProtection,
        worker: CivWorker? = null): Block? {
        val point = CivsWorksiteLayouts.point(building, task.block)
        // This is observation, including an existing solid shell in another
        // claim. secureMine/read and the saved receipt separately guard writes.
        return observeMineHazard(world, point) ?: run {
            if (worker != null) blocked(worker, "worksite-unloaded", point = point, reason = "hazard-neighbour-unavailable")
            null
        }
    }

    private fun read(city: Settlement, building: CivBuilding, world: World, point: CivPosition, allowOwn: Boolean = true,
        worker: CivWorker? = null, reserved: Set<CivPosition> = emptySet()): Block? {
        fun denied(status: String, reason: String, material: Material? = null): Block? {
            if (worker != null) blocked(worker, status, point = point, reason = reason, blockMaterial = material?.name)
            return null
        }
        if (!city.contains(point.x, point.z)) return denied("worksite-protected", "outside-city")
        if (point.y !in world.minHeight until world.maxHeight) return denied("worksite-protected", "outside-world-height")
        if (!world.isChunkLoaded(point.x shr 4, point.z shr 4)) return denied("worksite-unloaded", "chunk-unloaded")
        if (point !in reserved && locked(city.worldId, point)) return denied("worksite-protected", "world-operation-locked")
        if (civsProtectedBuildings(city).any { (!allowOwn || it.id != building.id) && protectedByBuilding(building, it, point) })
            return denied("worksite-protected", "other-building")
        val block = world.getBlockAt(point.x, point.y, point.z)
        if (block.getState(false) is TileState) return denied("worksite-obstacle", "block-entity", block.type)
        if (!world.worldBorder.isInside(block.location)) return denied("worksite-protected", "world-border", block.type)
        if (!worldAccess.canModify(city.owner, block)) return denied("worksite-protected", "protection-denied", block.type)
        return block
    }

    private fun mineShaftApproach(building: CivBuilding, target: CivPosition): CivPosition? {
        val geometry = CivsMinePlan.forBuilding(building) ?: return null
        val local = CivsMinePlan.localPoint(building, target)
        val gallery = (local.x to local.z) !in geometry.shaftColumns && geometry.floors.any { floor ->
            floor.cells.any { it.x == local.x && it.z == local.z && local.y in it.y..it.y + 4 }
        }
        if (local.y !in geometry.lowest until geometry.bottom.y || gallery ||
            geometry.shaftColumns.none { (x, z) -> kotlin.math.abs(local.x.toLong() - x) <= 1 &&
                kotlin.math.abs(local.z.toLong() - z) <= 1 }) return null
        val approach = if (local.y >= geometry.transferFeetY || local in geometry.transferFloor)
            geometry.bottom else geometry.extensionLadder
        // This two-leg local check covers only the immediate ladder neighborhood.
        // Wider transfer/central cuts use the authored passage mask and native route.
        if (kotlin.math.abs(local.x.toLong() - approach.x) > 2 ||
            kotlin.math.abs(local.z.toLong() - approach.z) > 2) return null
        return CivsWorksiteLayouts.point(building, approach)
    }

    private fun mineStandingConnected(building: CivBuilding, world: World, target: CivPosition, standing: CivPosition): Boolean =
        mineLadderConnected(building, world, standing) &&
            (mineShaftApproach(building, target)?.let { shaftStandingConnected(world, standing, it) } ?: true)

    /** A lower floor is usable only after its real ladder connects to the authored entrance. */
    private fun mineLadderConnected(building: CivBuilding, world: World, standing: CivPosition,
        checkedHeights: MutableMap<Int, Boolean> = hashMapOf()): Boolean {
        val geometry = CivsMinePlan.forBuilding(building) ?: return false
        val top = CivsWorksiteLayouts.point(building, geometry.bottom)
        if (standing.y >= top.y) return true
        return checkedHeights.getOrPut(standing.y) {
            (standing.y..top.y).all { y ->
                citizenChunkReady(world, top.x shr 4, top.z shr 4) &&
                    world.getBlockAt(top.x, y, top.z).type == Material.LADDER
            }
        }
    }

    private fun mineBuiltStanding(building: CivBuilding, point: CivPosition, prefix: Long,
        sections: Map<String, Long> = emptyMap(), state: CivMinePlanState? = null): Boolean =
        CivsMinePlan.forBuilding(building)?.let { it.builtStanding(CivsMinePlan.localPoint(building, point), prefix, sections) } == true ||
            CivsMineProgress.priorStanding(building, state, point)

    private fun shaftStandingConnected(world: World, p: CivPosition, shaft: CivPosition,
        centralStanding: MutableMap<Int, Boolean> = hashMapOf()): Boolean {
        if (kotlin.math.abs(p.x.toLong() - shaft.x) > 1 || kotlin.math.abs(p.z.toLong() - shaft.z) > 1) return false
        val centre = shaft.copy(y = p.y)
        fun at(point: CivPosition) = CitizenPoint(point.x + .5, point.y.toDouble(), point.z + .5)
        val from = at(centre)
        val to = at(p)
        return centralStanding.getOrPut(p.y) { citizenStandable(world, centre) && citizenPacketBodyClear(world, from, false) } &&
            (citizenPacketStepClear(world, from, to, false) ||
                listOf(centre.copy(z = p.z), centre.copy(x = p.x)).any { middle ->
                    citizenStandable(world, middle) && citizenPacketBodyClear(world, at(middle), false) &&
                        citizenPacketStepClear(world, from, at(middle), false) && citizenPacketStepClear(world, at(middle), to, false)
                })
    }

    private fun beside(city: Settlement, worker: CivWorker, building: CivBuilding, world: World, target: CivPosition,
                       allowOwn: Boolean = true, ladder: Boolean = false, miningStep: Long? = null,
                       preferred: CivPosition? = null, miningAccessStep: Long? = miningStep,
                       capture: (() -> CivWorldWork?)? = null): WorkApproach? {
        val rejected = rejectedWorkSpots[worker.id]?.takeIf {
            it.key == standingKey(worker, building, miningStep, target, mineExecutions[building.id]?.ordinal)
        }
        rejected?.retryAt?.let { retryAt ->
            if (Bukkit.getCurrentTick().toLong() < retryAt) {
                blocked(worker, "worksite-no-access", point = target, reason = "approaches-exhausted")
                return null
            }
            rejected.retryAt = null
        }
        val shaftApproach = if (ladder) mineShaftApproach(building, target) else null
        val reach = citizenWorkReach(building.type)
        val search = workSpots[worker.id]?.takeIf {
            it.buildingId == building.id && it.target == target && it.allowOwn == allowOwn && it.ladder == ladder
        } ?: run {
            var offsets = if (ladder) LADDER_WORK_OFFSETS else WORK_OFFSETS
            preferred?.let { offsets = (listOf(CivPosition(it.x - target.x, it.y - target.y, it.z - target.z)) + offsets).distinct() }
            if (shaftApproach != null) {
                val (central, other) = offsets.partition { target.x.toLong() + it.x == shaftApproach.x.toLong() &&
                    target.z.toLong() + it.z == shaftApproach.z.toLong() }
                offsets = central + other
            }
            residentFeet(worker.id)?.takeIf { it.world == world }?.let { feet ->
                val current = CivPosition(feet.blockX - target.x, feet.blockY - target.y, feet.blockZ - target.z)
                // Keep this order for the whole bounded search: actor movement must
                // not reinterpret a resumed candidate/aim cursor on the next pulse.
                offsets = (listOf(current) + offsets.sortedBy {
                    val dx = target.x + it.x + .5 - feet.x
                    val dy = target.y + it.y - feet.y
                    val dz = target.z + it.z + .5 - feet.z
                    dx * dx + dy * dy + dz * dz
                }).distinct()
            }
            if (shaftApproach != null) offsets = offsets.filter {
                kotlin.math.abs(target.x.toLong() + it.x - shaftApproach.x) <= 1 &&
                    kotlin.math.abs(target.z.toLong() + it.z - shaftApproach.z) <= 1
            }
            WorkSpotSearch(building.id, target, allowOwn, ladder, offsets).also { workSpots[worker.id] = it }
        }
        val offsets = search.offsets
        val centralStanding = hashMapOf<Int, Boolean>()
        val connectedHeights = hashMapOf<Int, Boolean>()
        // Worksite pulses can precede NPC creation. No actor is not a failed
        // approach: keep ordinary progress and reserve the remote probe for spawn.
        val remoteAvailable = residentFeet(worker.id)?.world == world
        fun pending(): WorkApproach? {
            spotSearchPending = true
            blocked(worker, "searching-work")
            return null
        }
        fun tryRemote(): WorkApproach? {
            if (search.remoteFinished) return null
            if (rejected?.remoteChecked == true) {
                search.remoteFinished = true
                return null
            }
            // Retain a tree's actual batch while its crown-aware probe resumes.
            // Other actions carry only their already inspected target here; their
            // caller seals native changes before the returned approach is admitted.
            val intent = if (capture != null) {
                search.captured?.takeIf { cachedMatches(city, worker, building, it) }?.work
                    ?: (capture() ?: run {
                        if (currentNeed(city, worker)?.status == "searching-work") pending()
                        return null
                    }).also { search.captured = Cached(building, worker, it) }
            } else CivWorldWork(worker.id, worker.completedCycles, building.id, target, emptyList(),
                status = "searching-work", miningStep = miningStep,
                miningAccessStep = mineExecutions[building.id]?.prefix ?: miningAccessStep,
                miningAccessSections = mineExecutions[building.id]?.sections?.toMap(),
                miningOrdinal = mineExecutions[building.id]?.ordinal, focus = target)
            val remote = remoteApproach(city, worker, building, world, intent, search.remote, target)
            if (spotSearchPending) return pending()
            search.remoteFinished = true
            if (remote == null) return null
            workSpots.remove(worker.id)
            return WorkApproach(remote.standing, remoteWork = true, focus = target, capturedWork = search.captured?.work)
        }
        // One ordinary slice earns one remote probe on its next admission. A failed
        // probe resumes these exact native/aim cursors instead of restarting either search.
        if (remoteAvailable && search.ordinaryAttempted && !search.remoteFinished) {
            val remote = tryRemote()
            if (remote != null || !search.remoteFinished) return remote
        }
        fun nextPosition() { search.next++; search.nextAim = 0 }
        while (search.next < offsets.size) {
            if (nanoTime() >= forestDeadline) return pending()
            val offset = offsets[search.next]
            val coordinates = listOf(target.x.toLong() + offset.x, target.y.toLong() + offset.y, target.z.toLong() + offset.z)
            if (coordinates.any { it !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }) { nextPosition(); continue }
            val p = CivPosition(coordinates[0].toInt(), coordinates[1].toInt(), coordinates[2].toInt())
            val eye = Location(world, p.x + .5, p.y + 1.62, p.z + .5)
            val aims = citizenBlockAimPoints(eye, target).filter { eye.distanceSquared(it) <= reach * reach }
            if (aims.isEmpty()) { nextPosition(); continue }
            // Integer domain tests are not native reads. Future gallery/cave points
            // must not spend a new service slot before a real stance is examined.
            if (building.type == BuildingType.QUARRY && (if (miningAccessStep != null) !mineBuiltStanding(building, p,
                    mineExecutions[building.id]?.prefix ?: miningAccessStep, mineExecutions[building.id]?.sections.orEmpty(),
                    city.minePlans?.get(building.id.toString()))
                    else !CivsMinePlan.workStandingAllowed(building, p))) { nextPosition(); continue }
            if (rejectedStanding(worker, building, miningStep, target, p)) { nextPosition(); continue }
            val shaft = shaftApproach
            if (shaft != null && (kotlin.math.abs(p.x.toLong() - shaft.x) > 1 ||
                    kotlin.math.abs(p.z.toLong() - shaft.z) > 1)) { nextPosition(); continue }
            if (!city.contains(p.x, p.z) || !citizenControlClear(city, p) || locked(city.worldId, p) ||
                civsProtectedBuildings(city).any { (!allowOwn || it.id != building.id) && protectedByBuilding(building, it, p, 1) })
                { nextPosition(); continue }
            if (spotChecksRemaining <= 0) return pending()
            spotChecksRemaining--
            search.ordinaryAttempted = true
            // An optional unloaded stance cannot pin the sweep ahead of loaded
            // alternatives or the actor's supported remote position.
            if (!citizenChunkReady(world, p.x shr 4, p.z shr 4)) { nextPosition(); continue }
            if (building.type == BuildingType.QUARRY && !mineLadderConnected(building, world, p, connectedHeights)) {
                nextPosition(); continue
            }
            if (!citizenStandable(world, p)) { nextPosition(); continue }
            if (offset.x == 0 && offset.z == 0 && world.getBlockAt(p.x, p.y, p.z).type != Material.LADDER) { nextPosition(); continue }
            if (!citizenPacketBodyClear(world, CitizenPoint(p.x + .5, p.y.toDouble(), p.z + .5), false)) { nextPosition(); continue }
            if (shaft != null && !shaftStandingConnected(world, p, shaft, centralStanding)) { nextPosition(); continue }
            search.sawStanding = true
            while (search.nextAim < aims.size) {
                if (spotRaysRemaining <= 0 || nanoTime() >= forestDeadline) return pending()
                spotRaysRemaining--
                if (citizenCanReachBlockPoint(world, eye, target, aims[search.nextAim++], reach)) {
                    workSpots.remove(worker.id)
                    return WorkApproach(p, capturedWork = search.captured?.takeIf {
                        cachedMatches(city, worker, building, it)
                    }?.work)
                }
            }
            nextPosition()
        }
        if (remoteAvailable && !search.remoteFinished) {
            val remote = tryRemote()
            if (remote != null || !search.remoteFinished) return remote
        }
        workSpots.remove(worker.id)
        // A completed sweep may retry after a bounded pause; elapsed time never
        // discards failures in the middle of trying its remaining alternatives.
        if (rejected != null && rejected.positions.isNotEmpty()) rejected.retryAt = Bukkit.getCurrentTick().toLong() + 100
        blocked(worker, "worksite-no-access", point = target,
            reason = if (search.sawStanding) "line-of-sight-blocked" else "no-supported-position",
            blockMaterial = world.getBlockAt(target.x, target.y, target.z).type.name)
        return null
    }

    private fun work(worker: CivWorker, building: CivBuilding, approach: WorkApproach, changes: List<CivPlanBlock>,
        inputs: Map<String, Long> = emptyMap(), outputs: Map<String, Long> = emptyMap(), status: String,
        duration: Long = 3_000, miningStep: Long? = null, focus: CivPosition? = null, miningGenerator: Boolean = false,
        miningAccessStep: Long? = miningStep): CivWorldWork {
        val standing = approach.standing
        val execution = mineExecutions[building.id]?.takeIf { miningStep != null && building.type == BuildingType.QUARRY }
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
        val after = if (meta != null && changes.isNotEmpty() && status != "watering" && status != "fertilizing" && !meta.isUnbreakable) {
            val unbreaking = hand.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.UNBREAKING).coerceAtLeast(0)
            val uses = changes.count { !Bukkit.createBlockData(it.before).material.isAir }
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
            miningDeferredSeals = execution?.let { mineSafety[worker.id]?.deferredAirWalls?.takeIf { it.isNotEmpty() }?.toSet() },
            miningFrontier = execution?.let { mineSafety[worker.id]?.target },
            miningExcavation = execution?.let { if (status == "securing-mine") mineSafety[worker.id]?.excavation
                else it.unsealedExcavation ?: mineSafety[worker.id]?.excavation })
    }

    private fun drops(block: Block, tool: ItemStack): Map<String, Long> = block.getDrops(tool).filter { !it.type.isAir }
        .groupingBy { it.type.name }.fold(0L) { count, item -> count + item.amount }
    private fun tool(worker: CivWorker): ItemStack = CivsEquipment.slots(worker)[4]?.stack() ?: ItemStack(Material.AIR)
    private fun change(block: Block, after: String) = CivPlanBlock(position(block), block.blockData.asString, after)
    private fun position(block: Block) = CivPosition(block.x, block.y, block.z)
    private fun put(map: MutableMap<String, Long>, key: String, value: Long) { if (value > 0) map[key] = value else map.remove(key) }

    private fun protectedByBuilding(workplace: CivBuilding, other: CivBuilding, point: CivPosition, headroom: Int = 0): Boolean =
        (point.x in other.bounds.min.x..other.bounds.max.x && point.z in other.bounds.min.z..other.bounds.max.z ||
            CivsWorksiteLayouts.isField(other, point.x, point.z)) &&
            (workplace.type != BuildingType.QUARRY || !CivsMinePlan.belowFoundation(other, point.y, headroom))

    companion object {
        internal fun generatorOutput(roll: Int, canMineIron: Boolean): String {
            require(roll in 0..99)
            return when { roll < 95 -> "COBBLESTONE"; roll < 99 -> "COAL"; canMineIron -> "RAW_IRON"; else -> "COBBLESTONE" }
        }
        val TYPES = setOf(BuildingType.FARM, BuildingType.FORESTER, BuildingType.QUARRY)
        private val SOILS = setOf(Material.DIRT, Material.GRASS_BLOCK, Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.FARMLAND)
        private val WEEDS = setOf(Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN)
        private val UNBREAKABLE = setOf(Material.BEDROCK, Material.BARRIER, Material.END_PORTAL_FRAME, Material.END_PORTAL, Material.NETHER_PORTAL)
        // Keep the four old adjacent sides first, then examine the rest of the two-block
        // ring. These 80 integer standing centres already satisfy the adult eye reach.
        private val WORK_OFFSETS = buildList {
            val adjacent = listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)
            val wider = (listOf(2 to 0, 0 to 2, -2 to 0, 0 to -2) +
                (-2..2).flatMap { x -> (-2..2).map { z -> x to z } })
                .distinct().filter { it != (0 to 0) && it !in adjacent }
            for (ring in listOf(adjacent, wider)) for (dy in listOf(0, 1, -1, -2)) for ((dx, dz) in ring)
                if (dx * dx + dz * dz + (dy + 1.12) * (dy + 1.12) <= CITIZEN_BLOCK_REACH * CITIZEN_BLOCK_REACH)
                    add(CivPosition(dx, dy, dz))
            // Keep the old choices first, then include every point whose adult eye
            // reaches a real block face. A lower third-ring slope can reach under a
            // low crown even when every old head position is occupied by leaves.
            val radius = kotlin.math.ceil(CITIZEN_BLOCK_REACH).toInt()
            val target = CivPosition(0, 0, 0)
            for (dy in (-radius - 1)..(radius - 1)) for (dx in -radius..radius) for (dz in -radius..radius) {
                val offset = CivPosition(dx, dy, dz)
                if (dx == 0 && dz == 0 || offset in this) continue
                val eye = Location(null, dx + .5, dy + 1.62, dz + .5)
                if (citizenBlockAimPoints(eye, target).any { aim ->
                        eye.toVector().distanceSquared(aim.toVector()) <= CITIZEN_BLOCK_REACH * CITIZEN_BLOCK_REACH
                    }) add(offset)
            }
        }
        private val LADDER_WORK_OFFSETS = listOf(1, 0, 2, -1, 3, -2, -3).map { CivPosition(0, it, 0) } + WORK_OFFSETS +
            buildList {
                for (dy in listOf(0, 1, -1, 2, -2, -3)) for (dx in -4..4) for (dz in -4..4)
                    if ((dx != 0 || dz != 0) && dx * dx + dz * dz + (dy + 1.12) * (dy + 1.12) <= CITIZEN_MINE_REACH * CITIZEN_MINE_REACH)
                        add(CivPosition(dx, dy, dz))
            }.filter { it !in WORK_OFFSETS }
    }
}
