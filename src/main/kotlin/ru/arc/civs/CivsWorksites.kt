package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.TileState
import org.bukkit.block.data.Ageable
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.UUID

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
    private val isPaused: (UUID) -> Boolean = { false }) {
    private data class Cached(val building: CivBuilding, val worker: CivWorker, val work: CivWorldWork)
    private val cached = hashMapOf<UUID, Cached>()
    private val cursors = hashMapOf<UUID, Int>()
    private data class FarmScan(val building: CivBuilding, val plots: List<CivPosition>,
        var next: Int = 0, var examined: Int = 0, var lastFocus: CivPosition? = null,
        val harvest: MutableSet<CivPosition> = linkedSetOf(), val field: MutableSet<CivPosition> = linkedSetOf(),
        val care: MutableSet<CivPosition> = linkedSetOf())
    private val farmScans = hashMapOf<UUID, FarmScan>()
    private val reportedFarmSelections = hashMapOf<UUID, Int>()
    private val reportedMineSelections = hashMapOf<UUID, Int>()
    private data class Need(val building: CivBuilding, val cycle: Long, val status: String,
        val material: String? = null, val amount: Long = 1, val point: CivPosition? = null,
        val reason: String? = null, val blockMaterial: String? = null)
    private val needs = hashMapOf<UUID, Need>()
    private data class WorkSpotSearch(val buildingId: UUID, val target: CivPosition, val allowOwn: Boolean,
        val ladder: Boolean, var next: Int = 0, var nextAim: Int = 0, var sawStanding: Boolean = false)
    private val workSpots = hashMapOf<UUID, WorkSpotSearch>()
    private data class MineSpotKey(val building: CivBuilding, val cycle: Long, val step: Long)
    private data class RejectedMineSpots(val key: MineSpotKey, var lastFocus: CivPosition,
        val positions: LinkedHashMap<CivPosition, Long> = linkedMapOf())
    private val rejectedMineSpots = hashMapOf<UUID, RejectedMineSpots>()
    private data class ReportedNeed(val need: Need, val tick: Int)
    private val reportedNeeds = hashMapOf<UUID, ReportedNeed>()
    private var spotChecksRemaining = 0
    private var spotRaysRemaining = 0
    private var mineChecksRemaining = 0
    private var spotSearchPending = false
    private var cursor = 0
    private val mineCursors = hashMapOf<UUID, Triple<Int, Long, Int>>()
    private data class MineSafetyScan(val building: CivBuilding, val target: CivPosition, val progress: Long,
        val excavation: CivPosition?, var next: Int = 0, val deferredAirWalls: MutableSet<CivPosition> = hashSetOf())
    private val mineSafety = hashMapOf<UUID, MineSafetyScan>()
    private data class MineProtection(val exposed: CivPosition, val block: CivPosition, val material: Material?,
        val transferFooting: Boolean = false)

    fun plan(city: Settlement, worker: CivWorker): CivWorldWork? {
        val building = worker.assignment?.let(city::building) ?: return null
        return cached[worker.id]?.takeIf { it.worker.completedCycles == worker.completedCycles && it.building == building &&
            it.worker.items == worker.items && it.worker.inventory == worker.inventory }?.work
    }

    /** Owner cache only: does not revalidate terrain, consume scan budgets or expire exclusions. */
    internal fun debug(workerIds: Set<UUID>? = null, buildingIds: Set<UUID>? = null): Map<String, Any?> {
        check(Bukkit.isPrimaryThread())
        val tick = Bukkit.getCurrentTick().toLong()
        fun point(p: CivPosition?) = p?.let { "${it.x},${it.y},${it.z}" }
        fun <T> sample(values: Collection<T>, describe: (T) -> Any?): Map<String, Any?> = mapOf(
            "total" to values.size, "truncated" to (values.size > 8), "entries" to values.take(8).map(describe))
        val ids = (cached.keys + needs.keys + workSpots.keys + farmScans.keys + mineSafety.keys +
            rejectedMineSpots.keys + cursors.keys).filter { workerIds == null || it in workerIds }
        val buildings = buildingIds ?: ids.mapNotNullTo(hashSetOf()) { id -> cached[id]?.building?.id ?: needs[id]?.building?.id ?:
            workSpots[id]?.buildingId ?: farmScans[id]?.building?.id ?: mineSafety[id]?.building?.id ?:
            rejectedMineSpots[id]?.key?.building?.id }
        return sample(ids) { id ->
            val item = cached[id]
            val need = needs[id]
            val spot = workSpots[id]
            val farm = farmScans[id]
            val safety = mineSafety[id]
            val rejected = rejectedMineSpots[id]
            mapOf("worker" to id.toString(), "cursor" to cursors[id],
                "cachedWork" to item?.work?.let { work -> mapOf("building" to work.buildingId.toString(),
                    "cycle" to work.cycle, "status" to work.status, "focus" to point(citizenWorkFocus(work)),
                    "standing" to point(work.standing), "miningStep" to work.miningStep,
                    "minePlanVersion" to work.miningPlanVersion, "accessStep" to work.miningAccessStep,
                    "generator" to work.miningGenerator,
                    "changes" to sample(work.changes) { mapOf("position" to point(it.position),
                        "before" to it.before, "after" to it.after) },
                    "inputs" to sample(work.inputs.entries) { mapOf("material" to it.key, "amount" to it.value) },
                    "outputs" to sample(work.outputs.entries) { mapOf("material" to it.key, "amount" to it.value) }) },
                "need" to need?.let { mapOf("building" to it.building.id.toString(), "cycle" to it.cycle,
                    "status" to it.status, "reason" to it.reason, "material" to it.material,
                    "amount" to it.amount, "point" to point(it.point), "blockMaterial" to it.blockMaterial) },
                "lastStatusAgeTicks" to reportedNeeds[id]?.let { (tick - it.tick).coerceAtLeast(0) },
                "candidateSearch" to spot?.let { mapOf("building" to it.buildingId.toString(),
                    "target" to point(it.target), "candidate" to it.next, "aim" to it.nextAim,
                    "sawStanding" to it.sawStanding, "ladder" to it.ladder, "allowOwn" to it.allowOwn) },
                "farmScan" to farm?.let { mapOf("building" to it.building.id.toString(), "next" to it.next,
                    "examined" to it.examined, "plots" to it.plots.size, "harvest" to it.harvest.size,
                    "field" to it.field.size, "care" to it.care.size, "lastFocus" to point(it.lastFocus)) },
                "mineSafety" to safety?.let { mapOf("building" to it.building.id.toString(),
                    "target" to point(it.target), "progress" to it.progress, "next" to it.next,
                    "excavation" to point(it.excavation), "deferredAirWalls" to it.deferredAirWalls.size) },
                "failedStances" to rejected?.let { state -> mapOf("building" to state.key.building.id.toString(),
                    "cycle" to state.key.cycle, "focus" to point(state.lastFocus), "miningStep" to state.key.step,
                    "exhausted" to (state.positions.size >= 16),
                    "positions" to sample(state.positions.entries) { mapOf("standing" to point(it.key),
                        "expiresAtTick" to it.value, "remainingTicks" to (it.value - tick).coerceAtLeast(0)) }) })
        } + mapOf("source" to "owner-cache", "tick" to tick,
            "remainingPulseBudget" to mapOf("standingChecks" to spotChecksRemaining,
                "rays" to spotRaysRemaining, "mineChecks" to mineChecksRemaining, "searchPending" to spotSearchPending),
            "mineCursors" to sample(mineCursors.entries.filter { workerIds == null || it.key in buildings }) {
                mapOf("building" to it.key.toString(), "level" to it.value.first,
                    "committedStep" to it.value.second, "scanStep" to it.value.third) })
    }

    fun invalidate(workerId: UUID) {
        cached.remove(workerId)?.let {
            // Paid shell placement closes an opening; retain an already checked
            // legacy prefix. Cuts must revisit the frontier even at the same cursor.
            if (it.work.status != "securing-mine") mineCursors.remove(it.building.id)
        }
        needs.remove(workerId); workSpots.remove(workerId); mineSafety.remove(workerId)
    }

    /** A failed native route excludes only this work stance, never the mine action or its inputs. */
    fun rejectStanding(city: Settlement, worker: CivWorker, work: CivWorldWork): Boolean {
        check(Bukkit.isPrimaryThread())
        val building = worker.assignment?.let(city::building) ?: return false
        val focus = citizenWorkFocus(work) ?: return false
        val step = work.miningStep ?: return false
        if (building.type != BuildingType.QUARRY || city.workers.none { it == worker } || plan(city, worker) != work) return false
        val key = MineSpotKey(building, worker.completedCycles, step)
        val rejected = rejectedMineSpots[worker.id]?.takeIf { it.key == key }
            ?: RejectedMineSpots(key, focus).also { rejectedMineSpots[worker.id] = it }
        rejected.lastFocus = focus
        rejected.positions.entries.removeIf { it.value <= Bukkit.getCurrentTick().toLong() }
        if (work.standing in rejected.positions || rejected.positions.size < 16)
            rejected.positions[work.standing] = Bukkit.getCurrentTick().toLong() + 1_200
        invalidate(worker.id)
        return true
    }

    private fun rejectedStanding(worker: CivWorker, building: CivBuilding, step: Long?, standing: CivPosition): Boolean {
        val rejected = rejectedMineSpots[worker.id] ?: return false
        rejected.positions.entries.removeIf { it.value <= Bukkit.getCurrentTick().toLong() }
        if (rejected.positions.isEmpty()) { rejectedMineSpots.remove(worker.id); return false }
        return step != null && rejected.key == MineSpotKey(building, worker.completedCycles, step) &&
            (rejected.positions.size >= 16 || standing in rejected.positions)
    }

    /** One worker, 16 field observations/candidate checks each, 32 standing probes and eight short rays per shared pulse. */
    fun pulse(cities: Collection<Settlement>) {
        check(Bukkit.isPrimaryThread())
        val jobs = cities.flatMap { city -> city.workers.mapNotNull { worker ->
            val building = worker.assignment?.let(city::building)
            if (building?.type in TYPES && building?.phase == BuildingPhase.ACTIVE && city.pendingDeletion == null &&
                city.construction?.buildingId != building?.id) Triple(city, worker, requireNotNull(building)) else null
        } }
        val workerIds = jobs.mapTo(hashSetOf()) { it.second.id }
        cached.keys.retainAll(workerIds)
        needs.keys.retainAll(workerIds)
        workSpots.keys.retainAll(workerIds)
        rejectedMineSpots.keys.retainAll(workerIds)
        reportedNeeds.keys.retainAll(workerIds)
        cursors.keys.retainAll(workerIds)
        farmScans.keys.retainAll(workerIds)
        reportedFarmSelections.keys.retainAll(workerIds)
        reportedMineSelections.keys.retainAll(workerIds)
        mineCursors.keys.retainAll(jobs.mapTo(hashSetOf()) { it.third.id })
        mineSafety.keys.retainAll(workerIds)
        // A persistence pause stops admission, not resident liveness or its bounded scan progress.
        val availableJobs = jobs.filterNot { isPaused(it.first.id) }
        if (availableJobs.isEmpty()) return
        val (city, worker, building) = availableJobs[Math.floorMod(cursor++, availableJobs.size)]
        var current = plan(city, worker)
        if (current != null && building.type !in setOf(BuildingType.FARM, BuildingType.QUARRY)) return
        val world = plugin.server.getWorld(city.worldId) ?: return
        if (world.time in 12_000L..23_000L || city.inventory == null) return
        spotChecksRemaining = 32
        spotRaysRemaining = 8
        mineChecksRemaining = 24
        spotSearchPending = false
        if (current != null && building.type == BuildingType.QUARRY) {
            // Falling blocks can cover an admitted floor while its worker travels.
            // Keep receipt pauses above this revalidation; reuse the normal planner
            // to select the real prerequisite cut before charging the floor input.
            if (quarryWorkAccessible(city, building, world, current)) return
            invalidate(worker.id)
            current = null
        }
        needs[worker.id] = Need(building, worker.completedCycles,
            if (building.type in setOf(BuildingType.FARM, BuildingType.QUARRY)) "searching-work" else "waiting-growth")
        val fieldScan = if (building.type == BuildingType.FARM) scanFarm(city, worker, building, world) else null
        // Refresh while travelling/working, so the next harvest is already known and newly
        // ripe crops can supersede care. A paused receipt keeps its admitted target intact.
        if (current != null && (!current.cropCare || fieldScan!!.harvest.none { !farmClaimed(city, worker, building, it) })) return
        val work = (if (current == null) equipTool(city, worker, building, world) else null) ?: when (building.type) {
            BuildingType.FARM -> farm(city, worker, building, world, requireNotNull(fieldScan), harvestOnly = current != null)
            BuildingType.FORESTER -> forest(city, worker, building, world)
            BuildingType.QUARRY -> mine(city, worker, building, world)
            else -> null
        }
        if (work != null) {
            needs.remove(worker.id)
            workSpots.remove(worker.id)
            fieldScan?.lastFocus = work.focus
            cached[worker.id] = Cached(building, worker, work)
            rejectedMineSpots[worker.id]?.takeIf {
                it.key != MineSpotKey(building, worker.completedCycles, work.miningStep ?: -1)
            }?.let { rejectedMineSpots.remove(worker.id) }
        }
        if (current == null || work != null) reportWorksite(city, worker, building, work)
    }

    private fun quarryWorkAccessible(city: Settlement, building: CivBuilding, world: World, work: CivWorldWork): Boolean {
        val focus = citizenWorkFocus(work) ?: return true
        val p = work.standing
        if (work.miningStep != null && (!CivsMinePlan.workStandingAllowed(building, p) ||
                !mineBuiltStanding(building, p, work.miningAccessStep ?: work.miningStep) ||
                !mineStandingConnected(building, world, focus, p))) return false
        spotChecksRemaining--
        if (!city.contains(p.x, p.z) || !citizenControlClear(city, p) || locked(city.worldId, p) ||
            civsProtectedBuildings(city).any { it.id != building.id && protectedByBuilding(building, it, p, 1) } ||
            !citizenStandable(world, p) ||
            !citizenPacketBodyClear(world, CitizenPoint(p.x + .5, p.y.toDouble(), p.z + .5), false)) return false
        val eye = Location(world, p.x + .5, p.y + 1.62, p.z + .5)
        return citizenBlockAimPoints(eye, focus).any { aim ->
            spotRaysRemaining--
            citizenCanReachBlockPoint(world, eye, focus, aim, citizenWorkReach(building.type))
        }
    }

    fun validate(city: Settlement, work: CivWorldWork) {
        requireCivs(!isPaused(city.id), "city-busy")
        val worker = city.workers.firstOrNull { it.id == work.workerId } ?: throw CivsRejection("worker-not-found")
        requireCivs(worker.assignment == work.buildingId && worker.completedCycles == work.cycle, "stale-delivery")
        requireCivs(plan(city, worker) == work, "stale-worksite")
        val building = city.building(work.buildingId) ?: throw CivsRejection("building-not-found")
        requireCivs(building.phase == BuildingPhase.ACTIVE && city.construction?.buildingId != building.id, "invalid-assignment")
        val world = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("world-unavailable")
        if (building.type == BuildingType.QUARRY && work.miningStep != null)
            requireCivs(quarryWorkAccessible(city, building, world, work), "stale-worksite")
        if (work.cropCare) {
            requireCivs(building.type == BuildingType.FARM, "invalid-assignment")
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
            for (task in mineFrontier(city, building, world, geometry, step.point, excavations, accessStep = work.miningAccessStep ?: scan.progress)
                ?: throw CivsRejection("protected-area")) {
                // Early transfer footing supplies a stance; the required shell/liquid
                // frontier remains the admission condition for an actual excavation.
                if (task.transferFooting) continue
                val exposed = CivsWorksiteLayouts.point(building, task.exposed)
                val opening = read(city, building, world, exposed) ?: throw CivsRejection("protected-area")
                if (!mineExposed(task, excavations, opening)) continue
                val block = read(city, building, world, CivsWorksiteLayouts.point(building, task.block))
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
                quarryWorkAccessible(city, building, world, work), "stale-worksite")
            for (task in mineFrontier(city, building, world, geometry, standing.copy(y = standing.y - 1), emptySet(), accessStep = geometry.steps.size.toLong())
                ?: throw CivsRejection("protected-area")) {
                val block = read(city, building, world, CivsWorksiteLayouts.point(building, task.block))
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
        return if (CivsEquipment.slots(worker)[4]?.material?.endsWith(suffix) != true) "waiting-tool"
        else currentNeed(city, worker)?.status ?:
            if (building.type in setOf(BuildingType.FARM, BuildingType.QUARRY)) "searching-work" else "waiting-growth"
    }

    private fun currentNeed(city: Settlement, worker: CivWorker): Need? = needs[worker.id]?.takeIf {
        it.building == worker.assignment?.let(city::building) && it.cycle == worker.completedCycles
    }

    fun requirement(city: Settlement, worker: CivWorker): Pair<String, Long>? {
        val required = currentNeed(city, worker)?.let { need -> need.material?.let { it to need.amount } }
            ?: plan(city, worker)?.takeUnless { it.changes.isEmpty() && it.equipmentAfter != null }
                ?.inputs?.entries?.firstOrNull()?.let { it.key to it.value } ?: return null
        val quarryBatch = worker.assignment?.let(city::building)?.takeIf { it.type == BuildingType.QUARRY }
            ?.let { CivsLogistics.QUARRY_SUPPLIES[required.first] }
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
        val previous = reportedNeeds[worker.id]
        if (work != null) {
            farmScans[worker.id]?.takeIf { building.type == BuildingType.FARM &&
                Bukkit.getCurrentTick() - reportedFarmSelections.getOrDefault(worker.id, -100) >= 100 }?.let { scan ->
                reportedFarmSelections[worker.id] = Bukkit.getCurrentTick()
                plugin.logger.info(CivsDiagnostics.line("farm_work_selected", "city" to city.id, "worker" to worker.id,
                    "building" to building.id, "cycle" to worker.completedCycles, "status" to work.status,
                    "point" to work.focus, "ripe_known" to scan.harvest.size,
                    "ripe_available" to scan.harvest.count { !farmClaimed(city, worker, building, it) },
                    "field_examined" to scan.examined, "field_size" to scan.plots.size, "scan_cursor" to scan.next))
            }
            if (building.type == BuildingType.QUARRY && work.miningPlanVersion == CivsMinePlan.VERSION &&
                Bukkit.getCurrentTick() - reportedMineSelections.getOrDefault(worker.id, -100) >= 100) {
                reportedMineSelections[worker.id] = Bukkit.getCurrentTick()
                val progress = city.minePlans?.get(building.id.toString())
                val step = CivsMinePlan.forBuilding(building)?.steps?.getOrNull(
                    mineCursors[building.id]?.takeIf { it.first == building.level && it.second == (progress?.nextStep ?: 0) }
                        ?.third ?: (progress?.nextStep ?: 0).toInt())
                plugin.logger.info(CivsDiagnostics.line("mine_work_selected", "city" to city.id, "worker" to worker.id,
                    "building" to building.id, "version" to work.miningPlanVersion,
                    "next_step" to work.miningStep, "committed_step" to (progress?.nextStep ?: 0),
                    "legacy_step" to city.mineProgress?.get(building.id.toString()), "stage" to step?.stage,
                    "floor" to step?.floor, "branch" to step?.branch, "inputs" to work.inputs,
                    "status" to work.status, "point" to work.focus, "standing" to work.standing))
            }
            if (reportedNeeds.remove(worker.id) != null) plugin.logger.info(CivsDiagnostics.line("worksite_recovered",
                "city" to city.id, "worker" to worker.id, "building" to building.id,
                "previous_reason" to previous?.need?.reason, "point" to work.focus, "standing" to work.standing))
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
        if (structural) plugin.logger.warning(line)
        else plugin.logger.info(line)
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
        return work(worker, building, standing, emptyList(), mapOf(material to 1), status = "waiting-tool", duration = 1_000)
            .copy(equipmentAfter = next)
    }

    private fun toolSuffix(type: BuildingType) = when (type) {
        BuildingType.FARM -> "_HOE"; BuildingType.FORESTER -> "_AXE"; else -> "_PICKAXE"
    }

    private fun prepareQuarry(city: Settlement, worker: CivWorker, building: CivBuilding, world: World): CivWorldWork? {
        val required = CivsLogistics.QUARRY_SUPPLIES.mapValues { (material, batch) ->
            (batch - CivsEquipment.plainCount(worker, material)).coerceAtLeast(0)
        }.filterValues { it > 0 }
        val available = stock(city, building.id)
        required.entries.firstOrNull { (material, amount) -> (available[material] ?: 0) < amount }?.let { (material, _) ->
            return blocked(worker, "waiting-${material.lowercase()}", material, CivsLogistics.QUARRY_SUPPLIES.getValue(material))
        }
        val supplied = try { CivsEquipment.collectSupplies(worker, required) } catch (failure: CivsRejection) {
            if (failure.reason != "resident-inventory-full") throw failure
            return blocked(worker, "resident-inventory-full", point = building.control, reason = failure.reason)
        }
        val standing = citizenStorageTarget(city, building, world)
            ?: return blocked(worker, "storage-unavailable", point = building.control, reason = "quarry-supplies-inaccessible")
        return work(worker, building, standing, emptyList(), required, status = "collecting-supplies", duration = 1_000)
            .copy(equipmentAfter = CivsEquipment.slots(supplied))
    }

    private fun scanFarm(city: Settlement, worker: CivWorker, building: CivBuilding, world: World): FarmScan {
        val scan = farmScans[worker.id]?.takeIf { it.building == building }
            ?: FarmScan(building, CivsWorksiteLayouts.field(building)).also { farmScans[worker.id] = it }
        val available by lazy { CivsEquipment.available(worker, stock(city, building.id)) }
        val crop = cropAndSeed(building).first
        repeat(minOf(16, scan.plots.size)) {
            val point = scan.plots[scan.next]
            scan.next = (scan.next + 1) % scan.plots.size
            scan.examined = minOf(scan.examined + 1, scan.plots.size)
            scan.harvest.remove(point); scan.field.remove(point); scan.care.remove(point)
            val soil = read(city, building, world, point) ?: return@repeat
            val top = read(city, building, world, point.copy(y = point.y + 1)) ?: return@repeat
            if (soil.type !in SOILS || top.type != crop && !top.type.isAir && top.type !in WEEDS) return@repeat
            if (soil.type == Material.FARMLAND && top.type == crop) {
                val age = top.blockData as? Ageable ?: return@repeat
                if (age.age == age.maximumAge) scan.harvest += point
                else if (cropCareChanges(world, top, soil, age,
                        age.age == 5 && (available["BONE_MEAL"] ?: 0) > 0).isNotEmpty()) scan.care += point
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
        if (!tool(worker).type.name.endsWith("_HOE")) return null
        val available by lazy { CivsEquipment.available(worker, stock(city, building.id)) }
        val (crop, seed) = cropAndSeed(building)
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
        val candidates = scan.harvest.sortedWith(nearest) + if (!harvestOnly && scan.examined == scan.plots.size)
            scan.field.sortedWith(nearest) + scan.care.sortedWith(nearest) else emptyList()
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
                val drops = drops(top, tool(worker)) .toMutableMap()
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
        val fertilizing = age.age == 5 && (available["BONE_MEAL"] ?: 0) > 0
        val changes = cropCareChanges(world, crop, soil, age, fertilizing)
        if (changes.isEmpty()) return null
        val standing = beside(city, worker, building, world, focus) ?: return null
        return work(worker, building, standing, changes, if (fertilizing) mapOf("BONE_MEAL" to 1L) else emptyMap(),
            status = if (fertilizing) "fertilizing" else "watering", duration = 4_000, focus = focus)
    }

    private fun cropCareChanges(world: World, crop: Block, soil: Block, age: Ageable, fertilizing: Boolean): List<CivPlanBlock> = buildList {
        val rainExposed = world.hasStorm() && world.getHighestBlockYAt(crop.x, crop.z) <= crop.y
        if (!rainExposed && soil.blockData.asString != "minecraft:farmland[moisture=7]")
            add(change(soil, "minecraft:farmland[moisture=7]"))
        // Rain needs no manual watering; otherwise keep the existing one free and one paid extra stage.
        if (!rainExposed && age.age == 3 || fertilizing)
            add(change(crop, (age.clone() as Ageable).also { it.age++ }.asString))
    }

    private fun forest(city: Settlement, worker: CivWorker, building: CivBuilding, world: World): CivWorldWork? {
        if (!tool(worker).type.name.endsWith("_AXE")) return null
        val layout = CivsWorksiteLayouts.site(building)?.getAsJsonObject("forester") ?: return null
        val box = layout.getAsJsonObject("searchBounds")
        val min = CivsWorksiteLayouts.vector(box["min"])
        val max = CivsWorksiteLayouts.vector(box["max"])
        val width = max.x - min.x + 1
        val depth = max.z - min.z + 1
        val timber = Material.valueOf(requireNotNull(CivsProduction.material(building)))
        val sapling = timber.name.removeSuffix("_LOG") + "_SAPLING"
        repeat(16) {
            val index = Math.floorMod(cursors.getOrDefault(worker.id, 0), width * depth)
            cursors[worker.id] = cursors.getOrDefault(worker.id, 0) + 1
            val column = CivsWorksiteLayouts.point(building, CivPosition(min.x + index % width, 0, min.z + index / width))
            if (!world.isChunkLoaded(column.x shr 4, column.z shr 4)) return@repeat
            // Surface queries stay in loaded columns; city buildings are never logging candidates.
            val surface = world.getHighestBlockYAt(column.x, column.z)
            val roots = (building.anchor.y - 4..minOf(surface, building.anchor.y + 5)).mapNotNull { y ->
                read(city, building, world, column.copy(y = y), allowOwn = false)?.takeIf { it.type == timber }
            }
            val root = roots.firstOrNull { base ->
                val below = read(city, building, world, CivPosition(base.x, base.y - 1, base.z), allowOwn = false)
                below?.type in TREE_SOILS
            }
            if (root != null) {
                val standing = beside(city, worker, building, world, position(root), allowOwn = false) ?: run {
                    if (spotSearchPending) { cursors[worker.id] = cursors.getValue(worker.id) - 1; return null }
                    return@repeat
                }
                val tree = ArrayDeque<Block>(); tree += root
                val logs = linkedMapOf<CivPosition, Block>()
                var leaves = false
                while (tree.isNotEmpty() && logs.size < 128) {
                    val log = tree.removeFirst()
                    val p = position(log)
                    if (p in logs || log.type != timber) continue
                    logs[p] = log
                    for (dx in -1..1) for (dy in 0..1) for (dz in -1..1) {
                        val q = CivPosition(p.x + dx, p.y + dy, p.z + dz)
                        if (q.y > root.y + 16) continue
                        val neighbor = read(city, building, world, q, allowOwn = false) ?: continue
                        if (neighbor.type.name == timber.name.removeSuffix("_LOG") + "_LEAVES" &&
                            (neighbor.blockData as? org.bukkit.block.data.type.Leaves)?.isPersistent == false) leaves = true
                        if (neighbor.type == timber && q !in logs) tree += neighbor
                    }
                }
                if (!leaves || tree.isNotEmpty()) return@repeat // Large trees remain intact rather than losing their inaccessible top.
                val outputs = linkedMapOf<String, Long>()
                logs.values.forEach { block -> drops(block, tool(worker)).forEach { (key, amount) -> outputs.merge(key, amount, Long::plus) } }
                val available = CivsEquipment.available(worker, stock(city, building.id))
                val roots = if (timber == Material.DARK_OAK_LOG) darkOakPlot(city, building, world, position(root), timber) else listOf(position(root))
                val replant = roots != null && (available[sapling] ?: 0) >= roots.size
                val changes = logs.values.sortedByDescending { it.y }.map { block ->
                    change(block, if (replant && position(block) in roots!!) Material.valueOf(sapling).createBlockData().asString else "minecraft:air")
                }
                return work(worker, building, standing, changes, if (replant) mapOf(sapling to roots!!.size.toLong()) else emptyMap(), outputs,
                    "felling-tree", duration = maxOf(6_000, logs.size * 1_000L))
            }
            // Fill genuine empty ground only, using a delivered sapling.
            val ground = read(city, building, world, column.copy(y = surface), allowOwn = false) ?: return@repeat
            if (ground.type !in TREE_SOILS) return@repeat
            val air = read(city, building, world, column.copy(y = surface + 1), allowOwn = false) ?: return@repeat
            if (!air.type.isAir) return@repeat
            val planting = if (timber == Material.DARK_OAK_LOG) darkOakPlot(city, building, world, position(air), Material.AIR)
                ?: return@repeat else listOf(position(air))
            if ((CivsEquipment.available(worker, stock(city, building.id))[sapling] ?: 0) < planting.size) {
                blocked(worker, "waiting-saplings", sapling, planting.size.toLong())
                return@repeat
            }
            val standing = beside(city, worker, building, world, position(air), allowOwn = false) ?: run {
                if (spotSearchPending) { cursors[worker.id] = cursors.getValue(worker.id) - 1; return null }
                return@repeat
            }
            return work(worker, building, standing, planting.map { p ->
                change(requireNotNull(read(city, building, world, p, allowOwn = false)), Material.valueOf(sapling).createBlockData().asString)
            }, mapOf(sapling to planting.size.toLong()), status = "felling-tree")
        }
        return null
    }

    /** Dark oak needs four paid saplings on a supported 2×2 square. */
    private fun darkOakPlot(city: Settlement, building: CivBuilding, world: World, root: CivPosition, material: Material): List<CivPosition>? {
        for (dx in listOf(0, -1)) for (dz in listOf(0, -1)) {
            val cells = (0..1).flatMap { x -> (0..1).map { z -> root.copy(x = root.x + dx + x, z = root.z + dz + z) } }
            if (cells.all { p -> read(city, building, world, p, allowOwn = false)?.type == material &&
                    read(city, building, world, p.copy(y = p.y - 1), allowOwn = false)?.type in TREE_SOILS }) return cells
        }
        return null
    }

    private fun mine(city: Settlement, worker: CivWorker, building: CivBuilding, world: World): CivWorldWork? {
        if (!tool(worker).type.name.endsWith("_PICKAXE")) return null
        val geometry = CivsMinePlan.forBuilding(building)
            ?: return blocked(worker, "worksite-layout", point = building.anchor, reason = "mine-layout-missing")
        val steps = geometry.steps
        // A geometry's ordinal is never reinterpreted as another version's cursor.
        // The first real receipt preserves the older prefixes and starts the new plan.
        val state = city.minePlans?.get(building.id.toString())
        val progress = state?.takeIf { it.version == CivsMinePlan.VERSION }?.nextStep ?: 0L
        if (state?.version?.let { it !in 2..CivsMinePlan.VERSION } == true || progress !in 0..steps.size.toLong())
            return blocked(worker, "worksite-layout", point = building.anchor, reason = "mine-progress-invalid")
        val v1 = geometry.legacyCompleted(city.mineProgress?.get(building.id.toString()) ?: 0L)
            ?: return blocked(worker, "worksite-layout", point = building.anchor, reason = "legacy-mine-progress-invalid")
        val v2Step = state?.takeIf { it.version == 2 }?.nextStep ?: state?.v2Step ?: 0L
        val v2Plan = CivsMinePlan.forBuilding(building, 2)
        val v2 = v2Plan?.completed(v2Step)
            ?: return blocked(worker, "worksite-layout", point = building.anchor, reason = "v2-mine-progress-invalid")
        val v3Step = state?.takeIf { it.version == 3 }?.nextStep ?: state?.v3Step ?: 0L
        val v3 = CivsMinePlan.forBuilding(building, 3)?.completed(v3Step)
            ?: return blocked(worker, "worksite-layout", point = building.anchor, reason = "v3-mine-progress-invalid")
        val legacy = v1 + v2 + v3
        val scan = mineCursors[building.id]?.takeIf { it.first == building.level && it.second == progress }
        val scanStart = (scan?.third ?: progress.toInt()).coerceAtMost(steps.size)
        // Take a useful batch before descending, then use it until a supply runs out.
        if (scanStart < steps.size && CivsLogistics.QUARRY_SUPPLIES.keys.any { CivsEquipment.plainCount(worker, it) == 0 })
            return prepareQuarry(city, worker, building, world)
        // A committed cut can expose a cave that was unreachable before it. Derive
        // this repair from the durable cursor, including after restart or the last step.
        if (scan == null) {
            steps.getOrNull(progress.toInt() - 1)?.let { previous ->
                val protection = secureMine(city, worker, building, world, geometry, previous, progress, emptySet())
                if (protection != null || spotSearchPending || currentNeed(city, worker)?.status != "searching-work") return protection
            }
            // Keep a completed previous check across bounded pulses. A receipt's
            // excavation invalidate clears it even when progress did not move.
            mineCursors[building.id] = Triple(building.level, progress, scanStart)
        }
        if (scanStart == steps.size) return generateStone(city, worker, building, world, geometry, progress)
        val pending = steps.drop(scanStart)
        for ((offset, step) in pending.take(16).withIndex()) {
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
            val next = scanStart.toLong() + offset + 1
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
                mineCursors[building.id] = Triple(building.level, progress, next.toInt())
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
            val protection = secureMine(city, worker, building, world, geometry, step, next - 1, excavations)
            if (protection != null || spotSearchPending || currentNeed(city, worker)?.status != "searching-work") return protection
            if (prerequisite != null) {
                val standing = beside(city, worker, building, world, position(prerequisite), ladder = true,
                    miningStep = next - 1, preferred = step.approach?.let { CivsWorksiteLayouts.point(building, it) }) ?: return null
                return work(worker, building, standing, listOf(change(prerequisite, "minecraft:air")),
                    outputs = drops(prerequisite, tool(worker)), status = "digging-mine", duration = 2_000,
                    miningStep = next - 1, miningAccessStep = next - 1)
            }
            val supportWork = target?.material?.takeIf { it in setOf(Material.COBBLESTONE, Material.LADDER, Material.TORCH) }
            // Keep the same durable step until the paid ladder is installed.
            val excavating = target?.material == Material.LADDER && !block.type.isAir && block.type != Material.LADDER
            val costs = if (excavating) emptyMap() else supportWork?.let { mapOf(it.name to 1L) }.orEmpty()
            costs.keys.firstOrNull { CivsEquipment.plainCount(worker, it) < 1 }?.let { material ->
                return blocked(worker, "waiting-${material.lowercase()}", material)
            }
            val standing = beside(city, worker, building, world, point, ladder = true,
                miningStep = if (excavating) next - 1 else next, miningAccessStep = next - 1,
                preferred = step.approach?.let { CivsWorksiteLayouts.point(building, it) }) ?: return null
            val outputs = if (block.type.isAir) emptyMap() else drops(block, tool(worker))
            return work(worker, building, standing, listOf(change(block, if (excavating) "minecraft:air" else target?.asString ?: "minecraft:air")), costs, outputs,
                "digging-mine", duration = 2_000, miningStep = if (excavating) next - 1 else next,
                miningAccessStep = next - 1)
        }
        if (mineCursors[building.id]?.third == steps.size) return generateStone(city, worker, building, world, geometry, progress)
        return null
    }

    private fun generateStone(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        geometry: CivsMinePlan, progress: Long): CivWorldWork? {
        val local = requireNotNull(geometry.generator)
        val point = CivsWorksiteLayouts.point(building, local)
        val block = read(city, building, world, point, worker = worker) ?: return null
        if (block.type != Material.COBBLESTONE && block.type != Material.STONE) return blocked(worker, "worksite-obstacle", point = point,
            reason = "generator-face-changed", blockMaterial = block.type.name)
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
            if (CivsEquipment.plainCount(worker, "TORCH") == 0) return prepareQuarry(city, worker, building, world)
            val standing = beside(city, worker, building, world, position(light), ladder = true, miningStep = progress) ?: return null
            return work(worker, building, standing, listOf(change(light, "minecraft:torch")),
                inputs = mapOf("TORCH" to 1L), status = "securing-mine", duration = 2_000, miningStep = progress)
        }
        val standing = beside(city, worker, building, world, point, ladder = true, miningStep = geometry.steps.size.toLong(),
            preferred = CivsWorksiteLayouts.point(building, localStanding)) ?: return null
        val roll = java.util.concurrent.ThreadLocalRandom.current().nextInt(100)
        val output = generatorOutput(roll, roll != 99 || Bukkit.createBlockData("minecraft:iron_ore").isPreferredTool(tool(worker)))
        // This permanent face is worked physically; the existing native receipt
        // atomically captures one item, one tool use and the completed work cycle.
        val nextFace = if (block.type == Material.COBBLESTONE) "minecraft:stone" else "minecraft:cobblestone"
        val work = work(worker, building, standing, listOf(change(block, nextFace)),
            outputs = mapOf(output to 1L), status = "digging-mine", duration = 6_000, miningStep = geometry.steps.size.toLong(),
            miningGenerator = true)
        if (!quarryWorkAccessible(city, building, world, work)) return blocked(worker, "worksite-no-access", point = point,
            reason = "generator-no-supported-position", blockMaterial = block.type.name)
        return work
    }

    /** Fixed-size shaft/access frontier; native checks are still shared across bounded pulses. */
    private fun mineFrontier(city: Settlement, building: CivBuilding, world: World,
        geometry: CivsMinePlan, around: CivPosition, excavations: Set<CivPosition>,
        worker: CivWorker? = null, accessStep: Long): List<MineProtection>? {
        val sides = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        val effect = excavations.singleOrNull() ?: around
        val connected = hashSetOf<CivPosition>()
        val landings = listOfNotNull(geometry.entranceLanding) + geometry.floors
        if ((effect.x to effect.z) in geometry.shaftColumns) {
            val open = hashSetOf<CivPosition>()
            // Only the current two-row air component belongs to this frontier. A
            // prospective cut must not connect a cave before its real receipt does.
            for (y in maxOf(geometry.lowest, effect.y)..minOf(geometry.bottom.y, effect.y + 1)) {
                for ((x, z) in geometry.shaftColumns) {
                    val local = CivPosition(x, y, z)
                    val block = read(city, building, world, CivsWorksiteLayouts.point(building, local), worker = worker)
                        ?: return null
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
                    for ((x, z) in geometry.shaftColumns) {
                        if (CivPosition(x, y, z) !in connected) continue
                        // Opening the central column touches only the previous outer ring.
                        // Widening touches this ring and the preceding face of its own column.
                        if (excavations.isNotEmpty() &&
                            (central && y == effect.y || !central && y > effect.y && (x != effect.x || z != effect.z))) continue
                        for ((dx, dz) in sides) {
                            val wall = CivPosition(x + dx, y, z + dz)
                            if ((wall.x to wall.z) in geometry.shaftColumns) continue
                            if (landings.any { floor -> floor.cells.any {
                                    it.x == wall.x && it.z == wall.z && wall.y in it.y + 1..it.y + floor.height
                                } } && (geometry.builtStanding(wall, accessStep) ||
                                    geometry.builtStanding(wall.copy(y = wall.y - 1), accessStep))) continue
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
        val tasks = mineFrontier(city, building, world, geometry, around, excavations, worker, effectiveProgress) ?: return null
        val scan = mineSafety[worker.id]?.takeIf { it.building == building && it.target == around && it.progress == progress &&
            it.excavation == excavations.singleOrNull() }
            ?: MineSafetyScan(building, around, progress, excavations.singleOrNull()).also { mineSafety[worker.id] = it }
        repeat(minOf(24, mineChecksRemaining)) {
            val task = tasks.getOrNull(scan.next) ?: return null
            mineChecksRemaining--
            val exposed = CivsWorksiteLayouts.point(building, task.exposed)
            val opening = read(city, building, world, exposed, worker = worker) ?: return null
            if (!mineExposed(task, excavations, opening)) { scan.next++; return@repeat }
            val point = CivsWorksiteLayouts.point(building, task.block)
            val block = read(city, building, world, point, worker = worker) ?: run {
                if (!task.transferFooting) return null
                scan.next++
                blocked(worker, "searching-work")
                return@repeat
            }
            if (task.transferFooting && !block.type.isAir) { scan.next++; return@repeat }
            if (!mineProtectionNeeded(block, task.material)) { scan.next++; return@repeat }
            if (task.material == null) return blocked(worker, "worksite-liquid", point = point,
                reason = "adjacent-liquid", blockMaterial = block.type.name)
            if (CivsEquipment.plainCount(worker, task.material.name) == 0)
                return prepareQuarry(city, worker, building, world)
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
                    (hiddenByCurrentCut || effectiveProgress < lowerRowEnd)) {
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

    private fun read(city: Settlement, building: CivBuilding, world: World, point: CivPosition, allowOwn: Boolean = true,
        worker: CivWorker? = null): Block? {
        fun denied(status: String, reason: String, material: Material? = null): Block? {
            if (worker != null) blocked(worker, status, point = point, reason = reason, blockMaterial = material?.name)
            return null
        }
        if (!city.contains(point.x, point.z)) return denied("worksite-protected", "outside-city")
        if (point.y !in world.minHeight until world.maxHeight) return denied("worksite-protected", "outside-world-height")
        if (!world.isChunkLoaded(point.x shr 4, point.z shr 4)) return denied("worksite-unloaded", "chunk-unloaded")
        if (locked(city.worldId, point)) return denied("worksite-protected", "world-operation-locked")
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

    private fun mineBuiltStanding(building: CivBuilding, point: CivPosition, prefix: Long): Boolean =
        CivsMinePlan.forBuilding(building)?.let { it.builtStanding(CivsMinePlan.localPoint(building, point), prefix) } == true

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
                       preferred: CivPosition? = null, miningAccessStep: Long? = miningStep): CivPosition? {
        val search = workSpots[worker.id]?.takeIf {
            it.buildingId == building.id && it.target == target && it.allowOwn == allowOwn && it.ladder == ladder
        } ?: WorkSpotSearch(building.id, target, allowOwn, ladder).also { workSpots[worker.id] = it }
        var offsets = if (ladder) LADDER_WORK_OFFSETS else WORK_OFFSETS
        preferred?.let { offsets = (listOf(CivPosition(it.x - target.x, it.y - target.y, it.z - target.z)) + offsets).distinct() }
        val shaftApproach = if (ladder) mineShaftApproach(building, target) else null
        if (shaftApproach != null) {
            val shaft = shaftApproach
            offsets = offsets.filter { kotlin.math.abs(target.x.toLong() + it.x - shaft.x) <= 1 &&
                kotlin.math.abs(target.z.toLong() + it.z - shaft.z) <= 1 }
            val (central, other) = offsets.partition { target.x.toLong() + it.x == shaft.x.toLong() &&
                target.z.toLong() + it.z == shaft.z.toLong() }
            offsets = central + other
        }
        val centralStanding = hashMapOf<Int, Boolean>()
        val connectedHeights = hashMapOf<Int, Boolean>()
        val reach = citizenWorkReach(building.type)
        fun pending(): CivPosition? {
            spotSearchPending = true
            blocked(worker, "searching-work")
            return null
        }
        fun nextPosition() { search.next++; search.nextAim = 0 }
        while (search.next < offsets.size) {
            if (spotChecksRemaining == 0) return pending()
            spotChecksRemaining--
            val offset = offsets[search.next]
            val coordinates = listOf(target.x.toLong() + offset.x, target.y.toLong() + offset.y, target.z.toLong() + offset.z)
            if (coordinates.any { it !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }) { nextPosition(); continue }
            val p = CivPosition(coordinates[0].toInt(), coordinates[1].toInt(), coordinates[2].toInt())
            if (building.type == BuildingType.QUARRY && (!CivsMinePlan.workStandingAllowed(building, p) ||
                    miningAccessStep != null && !mineBuiltStanding(building, p, miningAccessStep) ||
                    !mineLadderConnected(building, world, p, connectedHeights) ||
                    rejectedStanding(worker, building, miningStep, p))) { nextPosition(); continue }
            val shaft = shaftApproach
            if (shaft != null && (kotlin.math.abs(p.x.toLong() - shaft.x) > 1 ||
                    kotlin.math.abs(p.z.toLong() - shaft.z) > 1)) { nextPosition(); continue }
            if (!city.contains(p.x, p.z) || !citizenControlClear(city, p) || locked(city.worldId, p) ||
                civsProtectedBuildings(city).any { (!allowOwn || it.id != building.id) && protectedByBuilding(building, it, p, 1) })
                { nextPosition(); continue }
            if (!citizenStandable(world, p)) { nextPosition(); continue }
            if (offset.x == 0 && offset.z == 0 && world.getBlockAt(p.x, p.y, p.z).type != Material.LADDER) { nextPosition(); continue }
            if (!citizenPacketBodyClear(world, CitizenPoint(p.x + .5, p.y.toDouble(), p.z + .5), false)) { nextPosition(); continue }
            if (shaft != null && !shaftStandingConnected(world, p, shaft, centralStanding)) { nextPosition(); continue }
            search.sawStanding = true
            val eye = Location(world, p.x + .5, p.y + 1.62, p.z + .5)
            val aims = citizenBlockAimPoints(eye, target)
            while (search.nextAim < aims.size) {
                if (spotRaysRemaining == 0) return pending()
                spotRaysRemaining--
                if (citizenCanReachBlockPoint(world, eye, target, aims[search.nextAim++], reach)) {
                    workSpots.remove(worker.id)
                    return p
                }
            }
            nextPosition()
        }
        workSpots.remove(worker.id)
        blocked(worker, "worksite-no-access", point = target,
            reason = if (search.sawStanding) "line-of-sight-blocked" else "no-supported-position",
            blockMaterial = world.getBlockAt(target.x, target.y, target.z).type.name)
        return null
    }

    private fun work(worker: CivWorker, building: CivBuilding, standing: CivPosition, changes: List<CivPlanBlock>,
        inputs: Map<String, Long> = emptyMap(), outputs: Map<String, Long> = emptyMap(), status: String,
        duration: Long = 3_000, miningStep: Long? = null, focus: CivPosition? = null, miningGenerator: Boolean = false,
        miningAccessStep: Long? = miningStep): CivWorldWork {
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
            if (status == "working-field") 1_500 else duration, status, miningStep, after,
            focus ?: changes.minByOrNull { cell ->
                val dx = cell.position.x.toDouble() - standing.x
                val dy = cell.position.y.toDouble() - standing.y
                val dz = cell.position.z.toDouble() - standing.z
                dx * dx + dy * dy + dz * dz
            }?.position, miningPlanVersion = miningStep?.let { CivsMinePlan.VERSION }, miningGenerator = miningGenerator,
            miningAccessStep = miningAccessStep)
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
        private val TREE_SOILS = SOILS + Material.PODZOL + Material.MYCELIUM + Material.MOSS_BLOCK
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
        }
        private val LADDER_WORK_OFFSETS = listOf(1, 0, 2, -1, 3, -2, -3).map { CivPosition(0, it, 0) } + WORK_OFFSETS +
            buildList {
                for (dy in listOf(0, 1, -1, 2, -2, -3)) for (dx in -4..4) for (dz in -4..4)
                    if ((dx != 0 || dz != 0) && dx * dx + dz * dz + (dy + 1.12) * (dy + 1.12) <= CITIZEN_MINE_REACH * CITIZEN_MINE_REACH)
                        add(CivPosition(dx, dy, dz))
            }.filter { it !in WORK_OFFSETS }
    }
}
