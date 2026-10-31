package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.plugin.Plugin
import ru.arc.civs.CivWorkNeedState as Need
import ru.arc.civs.requireWorksite as requireCivs
import java.util.UUID
import java.util.logging.Level

/** Small live work cursor. Only real changed blocks and goods enter durable state, through CivsStorage. */
internal class CivsWorksites(private val plugin: Plugin, private val worldAccess: CivsWorld,
    private val stock: (Settlement, UUID) -> Map<String, Long>,
    private val locked: (UUID, CivPosition) -> Boolean,
    private val isPaused: (UUID) -> Boolean = { false },
    private val forestryPaths: (Settlement) -> Set<Pair<Int, Int>> = { emptySet() },
    private val nanoTime: () -> Long = System::nanoTime,
    private val settings: CivsWorksiteSettings = CivsWorksiteSettings()) {
    private val context: CivsWorksiteContext = CivsWorksiteContext(plugin, worldAccess, stock, locked, forestryPaths, { nanoTime() })
    private val approaches: CivsWorksiteApproaches = CivsWorksiteApproaches(context, { forestry }, { invalidate(it) })
    private val mine: CivsMineWorksite = CivsMineWorksite(context, approaches)
    private val forestry: CivsForestryWorksite = CivsForestryWorksite(context, approaches)
    private val farm = CivsFarmWorksites(context, approaches)
    private val professions: Map<BuildingType, CivsProfessionWorksite> = listOf(farm, forestry, mine).associateBy { it.type }
    init { context.professionTypes = professions.keys }
    private val cached = context.cached
    private val needs = context.needs
    private val workSpots = approaches.workSpots
    private val rejectedWorkSpots = approaches.rejectedWorkSpots
    private val deferredForestApproaches = approaches.deferredForestApproaches
    private val mineExecutions get() = mine.mineExecutions
    private val mineCursors get() = mine.mineCursors
    private val mineSafety get() = mine.mineSafety
    private val mineBounds get() = mine.mineBounds
    private val cursors = hashMapOf<UUID, Int>()
    private val farmScans get() = farm.scans
    private data class ReportedNeed(val need: Need, val tick: Int)
    private val reportedNeeds = hashMapOf<UUID, ReportedNeed>()
    private data class MineSearch(val city: UUID, val building: CivBuilding, val cycle: Long,
        val plan: CivMinePlanState?, val legacyStep: Long?, var activeTicks: Long = 0, var sampledAt: Long? = null,
        var warnedAt: Long? = null, val beganTick: Long = Bukkit.getCurrentTick().toLong(),
        var admissions: Long = 0, var target: CivPosition? = null, var targetSwitches: Long = 0)
    private val mineSearches = hashMapOf<UUID, MineSearch>()
    private val workplaces = hashMapOf<UUID, Pair<UUID, CivBuilding>>()
    private data class ZoneProtection(val world: UUID, val boundary: CivBox,
        val areas: Set<Pair<BuildingType, CivBox>>, val paths: Set<Pair<Int, Int>>)
    private val zoneProtections = hashMapOf<UUID, ZoneProtection>()
    private var lastAdmittedWorker: UUID? = null
    private var cursor = 0
    internal var mutationBlocked: (UUID, CivPosition) -> Boolean
        get() = context.mutationBlocked
        set(value) { context.mutationBlocked = value }
    internal var residentFeet: (UUID) -> Location?
        get() = context.residentFeet
        set(value) { context.residentFeet = value }

    internal var forestrySurplus: (Settlement, String) -> Boolean
        get() = context.forestrySurplus
        set(value) { context.forestrySurplus = value }

    fun plan(city: Settlement, worker: CivWorker): CivWorldWork? = context.plan(city, worker)
    fun rejectStanding(city: Settlement, worker: CivWorker, work: CivWorldWork): Boolean = approaches.rejectStanding(city, worker, work)
    internal fun validateMineReceiptNative(city: Settlement, work: CivWorldWork) = mine.validateMineReceiptNative(city, work)
    internal fun validateMineFallbackReceiptNative(city: Settlement, work: CivWorldWork) = mine.validateMineFallbackReceiptNative(city, work)

    /** Owner cache only: does not revalidate terrain, consume scan budgets or expire exclusions. */
    internal fun debug(workerIds: Set<UUID>? = null, buildingIds: Set<UUID>? = null): Map<String, Any?> {
        check(Bukkit.isPrimaryThread())
        val tick = Bukkit.getCurrentTick().toLong()
        fun point(p: CivPosition?) = p?.let { "${it.x},${it.y},${it.z}" }
        fun <T> sample(values: Collection<T>, describe: (T) -> Any?): Map<String, Any?> = mapOf(
            "total" to values.size, "truncated" to (values.size > 8), "entries" to values.take(8).map(describe))
        val ids = (cached.keys + needs.keys + workSpots.keys + farmScans.keys + mineSafety.keys +
            rejectedWorkSpots.keys + cursors.keys + mineSearches.keys + forestry.workerIds() + deferredForestApproaches.keys).filter { workerIds == null || it in workerIds }
        val buildings = buildingIds ?: ids.mapNotNullTo(hashSetOf()) { id -> cached[id]?.building?.id ?: needs[id]?.building?.id ?:
            workSpots[id]?.buildingId ?: farmScans[id]?.building?.id ?: mineSafety[id]?.building?.id ?:
            rejectedWorkSpots[id]?.key?.building?.id ?: forestry.building(id)?.id }
        return sample(ids) { id ->
            val item = cached[id]
            val need = needs[id]
            val spot = workSpots[id]
            val farm = farmScans[id]
            val safety = mineSafety[id]
            val rejected = rejectedWorkSpots[id]
            val forest = forestry.debug(id)
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
                "mineSections" to workplaces[id]?.second?.let { mineExecutions[it.id] }?.let { execution ->
                    val plan = requireNotNull(CivsMinePlan.forBuilding(execution.building))
                    val ready = plan.sections.keys.filter { section ->
                        plan.sectionCount(section, execution.prefix, execution.sections) < plan.sections.getValue(section).size &&
                            plan.sectionReady(section, execution.prefix, execution.sections)
                    }
                    mapOf("exhausted" to (ready.isNotEmpty() && ready.all { it in execution.blockedSections }),
                        "blockedCount" to execution.blockedSections.size,
                        "blocked" to sample(execution.blockedSections.entries) { (section, denial) ->
                            mapOf("section" to section, "reason" to denial.reason, "point" to point(denial.point)) })
                },
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
                "forestSearch" to forest["forestSearch"], "plantingRejections" to forest["plantingRejections"],
                "forestScan" to forest["forestScan"], "forestPlant" to forest["forestPlant"],
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
                "standingChecks" to context.spotChecksRemaining, "columns" to context.discoveryBudget.columns,
                "rays" to context.spotRaysRemaining, "mineChecks" to context.mineChecksRemaining, "forestWorkUnits" to context.discoveryBudget.forestUnits,
                "searchPending" to context.spotSearchPending),
            "mineCursors" to sample(mineCursors.entries.filter { workerIds == null || it.key in buildings }) {
                mapOf("building" to it.key.toString(), "level" to it.value.first,
                    "committedStep" to it.value.second, "scanStep" to it.value.third) })
    }

    /** Discard a receipt, not the worker's knowledge of its assigned zone. */
    fun invalidate(workerId: UUID, completedWork: CivWorldWork? = null) {
        val previous = cached.remove(workerId)
        val building = workplaces[workerId]?.second ?: previous?.building
        val profession = building?.let { professions[it.type] }
        profession?.invalidate(workerId, building, completedWork, previous != null)
        needs.remove(workerId)
        approaches.invalidate(workerId, completedWork)
        if (completedWork != null) {
            completedWork.changes.forEach { cell ->
                workplaces[workerId]?.first?.let { changed(it, cell.position) }
            }
            if (building != null) profession?.completed(workerId, building, completedWork)
        }
    }

    /** Main-thread native changes invalidate only overlapping observations and approach failures. */
    internal fun changed(worldId: UUID, point: CivPosition) {
        check(Bukkit.isPrimaryThread())
        professions.values.forEach { it.worldChanged(worldId, point) }
        workplaces.forEach { (workerId, place) ->
            if (place.first != worldId) return@forEach
            val building = place.second
            professions.getValue(building.type).changed(workerId, building, point)
            approaches.changed(workerId, building, point)
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
            workplaces[workerId]?.second?.let { building ->
                professions.getValue(building.type).protectionChanged(workerId, building, ::affected)
            }
            approaches.protectionChanged(workerId, ::affected)
        }
    }

    private fun forget(workerId: UUID) {
        val building = workplaces.remove(workerId)?.second
        cached.remove(workerId); needs.remove(workerId); approaches.forget(workerId)
        mineSearches.remove(workerId)
        cursors.remove(workerId); reportedNeeds.remove(workerId)
        building?.let { professions.getValue(it.type).forget(workerId, it, workplaces.values.any { place -> place.second.id == it.id }) }
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
            professions.getValue(place.second.type).containsChunk(id, place.second, chunk) }.keys.toList().forEach(::forget)
    }

    /** Round-robin admissions share one pulse: 16 columns, 192 forest units, 32 native stances, eight rays and 2ms. */
    fun pulse(cities: Collection<Settlement>) {
        check(Bukkit.isPrimaryThread())
        val jobs = cities.filter { plugin.server.getWorld(it.worldId) != null }.flatMap { city -> city.workers.mapNotNull { worker ->
            val building = worker.assignment?.let(city::building)
            if (building != null && building.type in professions && CivsRules.workplaceAvailable(city, building) && city.pendingDeletion == null)
                Triple(city, worker, building) else null
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
        approaches.retain(workerIds)
        reportedNeeds.keys.retainAll(workerIds)
        cursors.keys.retainAll(workerIds)
        mineSearches.keys.retainAll(workerIds)
        professions.values.forEach { it.retain(workerIds, jobs.mapTo(hashSetOf()) { job -> job.third.id }) }
        jobs.forEach { (city, worker, building) -> tickMineSearch(city, worker, building) }
        // A persistence pause stops admission, not resident liveness or its bounded scan progress.
        val availableJobs = jobs.filterNot { isPaused(it.first.id) }
        if (availableJobs.isEmpty()) return
        context.spotChecksRemaining = settings.standingChecksPerPulse
        context.spotRaysRemaining = settings.rayChecksPerPulse
        context.mineChecksRemaining = settings.mineChecksPerPulse
        context.discoveryBudget.columns = settings.maximumForestColumnsPerPulse
        context.discoveryBudget.forestUnits = settings.forestWorkUnitsPerPulse
        context.discoveryBudget.deadline = nanoTime() + (settings.nativeBudgetMillis * 1_000_000).toLong()
        val start = Math.floorMod(cursor, availableJobs.size)
        // Independent quota classes must not let a later cheap job repeatedly
        // move the next start past an earlier worker whose quota ran out.
        cursor = (start + 1) % availableJobs.size
        for (offset in availableJobs.indices) {
            if (nanoTime() >= context.discoveryBudget.deadline) break
            val index = (start + offset) % availableJobs.size
            val (city, worker, building) = availableJobs[index]
            val world = plugin.server.getWorld(city.worldId) ?: continue
            val profession = professions.getValue(building.type)
            if (world.time in 12_000L..23_000L || !profession.active(city, building)) continue
            val current = plan(city, worker)
            if (!profession.hasBudget(city, worker, building, current)) continue
            lastAdmittedWorker = worker.id
            pulseWorker(city, worker, building, world, current)
        }
    }

    private fun pulseWorker(city: Settlement, worker: CivWorker, building: CivBuilding, world: World, previous: CivWorldWork?) {
        context.spotSearchPending = false
        mineSearches[worker.id]?.let { it.admissions++ }
        when (val result = professions.getValue(building.type).discover(city, worker, building, world, previous)) {
            CivWorksiteDiscovery.Pending, CivWorksiteDiscovery.Retained -> Unit
            CivWorksiteDiscovery.Unavailable -> reportWorksite(city, worker, building, null)
            is CivWorksiteDiscovery.Selected -> reportWorksite(city, worker, building, result.work)
        }
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
            return context.read(city, building, world, point, reserved = reserved) ?: throw CivsRejection("protected-area")
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
        requireCivs(CivsRules.workplaceAvailable(city, building), "invalid-assignment")
        val profession = professions.getValue(building.type)
        profession.validateVersion(work)
        requireCivs(work.autonomousVisual == CivsAutonomousWorksites.supports(city, building), "stale-worksite")
        if (work.autonomousVisual) CivsAutonomousWorksites.validate(city, work)
        val world = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("world-unavailable")
        profession.validatePlan(city, worker, building, world, work)
        work.changes.forEach { cell ->
            val block = context.read(city, building, world, cell.position) ?: throw CivsRejection("protected-area")
            requireCivs(block.blockData.asString == cell.before, "stale-worksite")
            requireCivs(worldAccess.canModify(city.owner, block, Bukkit.createBlockData(cell.after).material), "lands-denied")
        }
        profession.validateNativeProof(city, worker, building, world, work)
    }

    fun idleReason(city: Settlement, worker: CivWorker): String {
        val building = worker.assignment?.let(city::building) ?: return "unassigned"
        val suffix = professions[building.type]?.toolSuffix ?: "_PICKAXE"
        if (isPaused(city.id)) return professions[building.type]?.pausedStatus(city, worker) ?: "searching-work"
        return if (!CivsAutonomy.legacyStock(city) && CivsEquipment.slots(worker)[4]?.material?.endsWith(suffix) != true) "waiting-tool"
        else context.currentNeed(city, worker)?.status ?:
            "searching-work"
    }

    fun requirement(city: Settlement, worker: CivWorker): Pair<String, Long>? {
        val required = context.currentNeed(city, worker)?.let { need -> need.material?.let { it to need.amount } }
            ?: plan(city, worker)?.takeUnless { it.changes.isEmpty() && it.equipmentAfter != null }
                ?.inputs?.entries?.firstOrNull()?.let { it.key to it.value } ?: return null
        val building = worker.assignment?.let(city::building) ?: return null
        return professions.getValue(building.type).requirement(building, required, context.currentNeed(city, worker)?.material != null)
    }

    private fun reportWorksite(city: Settlement, worker: CivWorker, building: CivBuilding, work: CivWorldWork?) {
        reportMineSearch(city, worker, building, work)
        val previous = reportedNeeds[worker.id]
        if (work != null) {
            professions.getValue(building.type).reportSelected(city, worker, building, work)
            if (reportedNeeds.remove(worker.id) != null) CivsDiagnostics.record(Level.INFO, city.id, CivsDiagnostics.line("worksite_recovered",
                "city" to city.id, "worker" to worker.id, "building" to building.id,
                "previous_reason" to previous?.need?.reason, "point" to work.focus, "standing" to work.standing), plugin.logger)
            return
        }
        val need = needs[worker.id]?.takeIf { it.reason != null } ?: return
        val tick = Bukkit.getCurrentTick()
        val structural = professions.getValue(building.type).structuralNeed(need)
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
            plan(city, worker) != null || context.currentNeed(city, worker)?.status != "searching-work") return false
        val target = workSpots[worker.id]?.target ?: rejectedWorkSpots[worker.id]?.pending?.work?.let(::citizenWorkFocus)
        if (target != null && !citizenChunkReady(world, target.x shr 4, target.z shr 4)) return false
        return professions.getValue(building.type).searchEligible(city, worker, building)
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
                "${professions.getValue(building.type).discoveryName}_search_slow",
                city, worker, building, search), plugin.logger)
        }
    }

    private fun reportMineSearch(city: Settlement, worker: CivWorker, building: CivBuilding, work: CivWorldWork?) {
        if (work != null) {
            mineSearches.remove(worker.id)?.takeIf { it.warnedAt != null }?.let { search ->
                CivsDiagnostics.record(Level.INFO, city.id, mineSearchLine(
                    "${professions.getValue(building.type).discoveryName}_search_work_selected",
                    city, worker, building, search, work), plugin.logger)
            }
        } else if (mineSearchEligible(city, worker)) {
            val search = mineSearches.getOrPut(worker.id) { MineSearch(city.id, building, worker.completedCycles,
                city.minePlans?.get(building.id.toString()), city.mineProgress?.get(building.id.toString()), admissions = 1) }
            search.sampledAt = Bukkit.getCurrentTick().toLong()
            val target = workSpots[worker.id]?.target ?: forestry.scanRoot(worker.id) ?:
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

    companion object {
        /** Existing domain consumers classify native work before a live coordinator is constructed. */
        val TYPES = BuildingType.entries.filter(BuildingType::isWorldWork).toSet()
        internal fun generatorOutput(roll: Int, level: Int, canMineOre: (String) -> Boolean): String {
            require(roll in 0..9_999 && level in 1..3)
            val material = when {
                roll < level -> "DIAMOND"
                roll < 11 * level -> "REDSTONE"
                roll < 16 * level -> "RAW_GOLD"
                roll < 116 * level -> "RAW_IRON"
                roll < 516 * level -> "COAL"
                else -> "COBBLESTONE"
            }
            return if (material == "COBBLESTONE" || material == "COAL" || canMineOre(material)) material else "COBBLESTONE"
        }
    }
}
