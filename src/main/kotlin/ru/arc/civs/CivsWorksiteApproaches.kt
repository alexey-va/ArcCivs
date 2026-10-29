package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.logging.Level
import kotlin.math.roundToInt
import ru.arc.civs.CivWorkCached as Cached
import ru.arc.civs.CivWorkNeedState as Need
import ru.arc.civs.CivsMineApproach.mineShaftApproach
import ru.arc.civs.CivsMineApproach.mineStandingConnected
import ru.arc.civs.CivsMineApproach.mineLadderConnected
import ru.arc.civs.CivsMineApproach.mineBuiltStanding
import ru.arc.civs.CivsMineApproach.shaftStandingConnected

/** Bounded native stance/aim discovery and retained failures, including deferred forest approaches. */
internal class CivsWorksiteApproaches(
    private val context: CivsWorksiteContext,
    private val forestry: () -> CivsForestryWorksite,
    private val invalidate: (UUID) -> Unit,
) {
    data class RemoteWorkProbe(var candidate: CivWorldWork? = null, var nextAim: Int = 0,
        val origins: MutableSet<CivPosition> = linkedSetOf()) {
        fun observed(origin: CivPosition) {
            origins += origin
            if (origins.size > 512) origins.remove(origins.first())
        }
    }
    data class WorkSpotSearch(val buildingId: UUID, val target: CivPosition, val allowOwn: Boolean,
        val ladder: Boolean, val offsets: List<CivPosition>,
        var next: Int = 0, var nextAim: Int = 0, var sawStanding: Boolean = false,
        val remote: RemoteWorkProbe = RemoteWorkProbe(), var captured: Cached? = null,
        var ordinaryAttempted: Boolean = false, var remoteFinished: Boolean = false)
    val workSpots = hashMapOf<UUID, WorkSpotSearch>()
    data class WorkSpotKey(val building: CivBuilding, val step: Long?, val focus: CivPosition? = null)
    data class RejectedWorkSpots(val key: WorkSpotKey, var lastFocus: CivPosition,
        val positions: LinkedHashMap<CivPosition, Long> = linkedMapOf(), var retryAt: Long? = null,
        var pending: Cached? = null, var checkedChanges: Int = 0, var remoteChecked: Boolean = false,
        val remote: RemoteWorkProbe = RemoteWorkProbe())
    val rejectedWorkSpots = hashMapOf<UUID, RejectedWorkSpots>()
    val deferredForestApproaches = hashMapOf<UUID, MutableMap<CivPosition, RejectedWorkSpots>>()
    fun retireAction(workerId: UUID) { invalidate(workerId) }

    fun invalidate(workerId: UUID, completedWork: CivWorldWork?) {
        if (completedWork?.let(CivsMineFallback::matches) != true) workSpots.remove(workerId)
    }
    fun forget(workerId: UUID) {
        workSpots.remove(workerId); rejectedWorkSpots.remove(workerId); deferredForestApproaches.remove(workerId)
    }
    fun retain(workerIds: Set<UUID>) {
        workSpots.keys.retainAll(workerIds); rejectedWorkSpots.keys.retainAll(workerIds); deferredForestApproaches.keys.retainAll(workerIds)
    }
    fun changed(workerId: UUID, building: CivBuilding, point: CivPosition) {
        refreshRejected(workerId) { rejected ->
            val reach = if (building.type == BuildingType.QUARRY) 6 else 4
            fun nearby(p: CivPosition, radius: Int) = kotlin.math.abs(p.x - point.x) <= radius &&
                kotlin.math.abs(p.z - point.z) <= radius && point.y in p.y - 3..p.y + 3
            val focus = rejected.lastFocus
            nearby(focus, reach) || rejected.positions.keys.any { nearby(it, 1) } ||
                building.type == BuildingType.FORESTER && rejected.remote.origins.any { origin ->
                    point.x in minOf(origin.x, focus.x) - 1..maxOf(origin.x, focus.x) + 1 &&
                        point.z in minOf(origin.z, focus.z) - 1..maxOf(origin.z, focus.z) + 1 &&
                        point.y in minOf(origin.y - 1, focus.y)..maxOf(origin.y + 2, focus.y + 1)
                }
        }
    }
    fun protectionChanged(workerId: UUID, affected: (CivPosition, Int) -> Boolean) {
        refreshRejected(workerId) { rejected ->
            affected(rejected.lastFocus, 6) || rejected.positions.keys.any { affected(it, 1) } ||
                rejected.key.building.type == BuildingType.FORESTER && rejected.remote.origins.any { affected(it, 1) }
        }
        workSpots[workerId]?.takeIf { affected(it.target, 6) }?.let { workSpots.remove(workerId) }
    }
    fun deferredRoots(worker: CivWorker, building: CivBuilding): Set<CivPosition> =
        deferredForestApproaches.getOrPut(worker.id) { linkedMapOf() }.also { deferred ->
            deferred.entries.removeIf { it.value.key.building != building }
        }.keys
    fun refreshForestApproaches(workerId: UUID, feet: Location, deferred: Set<CivPosition>) {
        if (rejectedWorkSpots[workerId] != null) return
        val origin = CivPosition(feet.blockX, feet.y.roundToInt(), feet.blockZ)
        val eye = feet.clone().add(0.0, 1.62, 0.0)
        val reach = CITIZEN_REMOTE_WORK_REACH
        val roots = deferredForestApproaches[workerId] ?: return
        val reopened = roots.entries.firstOrNull { (focus, rejected) ->
            focus in deferred && origin !in rejected.remote.origins &&
                citizenBlockAimPoints(eye, focus).any { aim ->
                    eye.distanceSquared(aim) <= reach * reach
                }
        } ?: return
        // Preserve failed ordinary stances while a new actor cell tries the remote approach.
        val focus = reopened.key
        val rejected = reopened.value
        roots.remove(focus)
        rejected.retryAt = null; rejected.remoteChecked = false
        rejected.remote.candidate = null; rejected.remote.nextAim = 0
        rejectedWorkSpots[workerId] = rejected
        workSpots.remove(workerId)
        forestry().approachReopened(workerId, focus)
    }
    fun deferForestRoot(worker: CivWorker, building: CivBuilding, root: CivPosition) {
        val key = requireNotNull(standingKey(worker, building, null, root))
        val rejected = rejectedWorkSpots[worker.id]?.takeIf { it.key == key }
            ?.also { rejectedWorkSpots.remove(worker.id) } ?: RejectedWorkSpots(key, root)
        deferForest(worker.id, rejected)
    }

    private fun refreshRejected(workerId: UUID, affected: (RejectedWorkSpots) -> Boolean) {
        rejectedWorkSpots[workerId]?.takeIf(affected)?.let { rejected ->
            // Keep a pending immutable batch; only its geometry evidence expired.
            rejected.positions.clear(); rejected.retryAt = null; rejected.checkedChanges = 0
            rejected.remoteChecked = false; rejected.remote.candidate = null; rejected.remote.nextAim = 0
            rejected.remote.origins.clear()
            workSpots.remove(workerId)
        }
        deferredForestApproaches[workerId]?.entries?.removeIf { (_, rejected) ->
            affected(rejected).also { changed ->
                if (changed) forestry().approachReopened(workerId, rejected.lastFocus)
            }
        }
    }

    /** A failed approach excludes its stance, never a durable job, accepted batch or its inputs. */
    fun rejectStanding(city: Settlement, worker: CivWorker, work: CivWorldWork): Boolean {
        check(Bukkit.isPrimaryThread())
        val building = worker.assignment?.let(city::building) ?: return false
        val focus = citizenWorkFocus(work) ?: return false
        if (city.workers.none { it == worker } || context.plan(city, worker) != work) return false
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
        rejected.pending = context.cached.remove(worker.id)
        rejected.checkedChanges = 0
        rejected.remoteChecked = work.remoteWork
        if (building.type == BuildingType.FORESTER && work.remoteWork) rejected.remote.observed(work.standing)
        rejected.remote.candidate = null
        rejected.remote.nextAim = 0
        workSpots.remove(worker.id)
        context.needs[worker.id] = Need(building, worker.completedCycles, "searching-work")
        return true
    }

    fun standingKey(worker: CivWorker, building: CivBuilding, step: Long?, focus: CivPosition?, ordinal: Int? = null): WorkSpotKey? = when {
        building.type == BuildingType.QUARRY && step != null -> WorkSpotKey(building, ordinal?.toLong() ?: step,
            null)
        building.type in setOf(BuildingType.FORESTER, BuildingType.FARM) && focus != null ->
            WorkSpotKey(building, null, focus)
        else -> null
    }

    private fun rejectedStanding(worker: CivWorker, building: CivBuilding, step: Long?, focus: CivPosition,
                                 standing: CivPosition, ordinal: Int?): Boolean {
        val rejected = rejectedWorkSpots[worker.id] ?: return false
        return rejected.key == standingKey(worker, building, step, focus, ordinal) &&
            standing in rejected.positions
    }

    /** One rejected approach earns a bounded attempt from the actor's real safe position, never a teleport. */
    fun remoteWork(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        rejected: RejectedWorkSpots, pending: Cached): CivWorldWork? {
        if (!context.cachedMatches(city, worker, building, pending) || pending.work.remoteWork) return null
        return remoteApproach(city, worker, building, world, pending.work, rejected.remote)?.also {
            reportRemoteWork(city, worker, building, world, it, "rejected-work-position", rejected.positions.size)
        }
    }

    /** Shared bounded geometry probe; the supplied work is not admitted or committed here. */
    private fun remoteApproach(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        work: CivWorldWork, probe: RemoteWorkProbe, focus: CivPosition? = citizenWorkFocus(work)): CivWorldWork? {
        focus ?: return null
        val feet = context.residentFeet(worker.id)?.takeIf { it.world == world } ?: return null
        if (context.spotChecksRemaining <= 0 || context.nanoTime() >= context.discoveryBudget.deadline) { context.spotSearchPending = true; return null }
        context.spotChecksRemaining--
        val actual = CitizenPoint(feet.x, feet.y, feet.z)
        val standing = CivPosition(feet.blockX, feet.y.roundToInt(), feet.blockZ)
        if (building.type == BuildingType.FORESTER) probe.observed(standing)
        if (!citizenPacketSupported(world, actual) || !citizenPacketBodyClear(world, actual, false)) return null
        val candidate = work.copy(standing = standing, remoteWork = true)
        if (candidate != probe.candidate) { probe.candidate = candidate; probe.nextAim = 0 }
        if (!workStandingAllowed(city, building, world, candidate, focus)) return null
        val eye = feet.clone().add(0.0, 1.62, 0.0)
        val reach = citizenWorkReach(building.type, candidate)
        val aims = citizenBlockAimPoints(eye, focus).filter { eye.distanceSquared(it) <= reach * reach }
        while (probe.nextAim < aims.size) {
            if (context.spotRaysRemaining <= 0 || context.nanoTime() >= context.discoveryBudget.deadline) { context.spotSearchPending = true; return null }
            context.spotRaysRemaining--
            if (citizenCanReachBlockPoint(world, eye, focus, aims[probe.nextAim++], reach, candidate)) return candidate
        }
        return null
    }

    fun reportRemoteWork(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        work: CivWorldWork, reason: String, rejectedStances: Int) {
        val feet = remoteFeet(world, work) ?: return
        val focus = citizenWorkFocus(work) ?: return
        val eye = feet.clone().add(0.0, 1.62, 0.0)
        CivsDiagnostics.record(Level.WARNING, city.id, CivsDiagnostics.line("worksite_remote_fallback",
            "city" to city.id, "worker" to worker.id, "building" to building.id, "cycle" to worker.completedCycles,
            "reason" to reason, "focus" to focus, "standing" to work.standing,
            "distance" to eye.distance(Location(world, focus.x + .5, focus.y + .5, focus.z + .5)),
            "reach" to citizenWorkReach(building.type, work), "rejected_stances" to rejectedStances), context.plugin.logger)
    }

    private fun deferForest(workerId: UUID, rejected: RejectedWorkSpots) {
        val deferred = deferredForestApproaches.getOrPut(workerId) { linkedMapOf() }
        deferred[rejected.lastFocus] = rejected
        if (deferred.size > 128) deferred.remove(deferred.keys.first())
    }

    /** A route rejection changes only the approach; its captured goods and native batch stay immutable. */
    fun retryForestApproach(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        pending: Cached): CivWorldWork? {
        val rejected = rejectedWorkSpots.getValue(worker.id)
        val work = pending.work
        fun changed(): CivWorldWork? {
            rejectedWorkSpots.remove(worker.id)
            invalidate(worker.id)
            context.needs[worker.id] = Need(building, worker.completedCycles, "searching-work")
            return null
        }
        if (!context.cachedMatches(city, worker, building, pending)) return changed()
        val focus = citizenWorkFocus(work) ?: return changed()
        if (!citizenChunkReady(world, focus.x shr 4, focus.z shr 4))
            return context.blocked(worker, "worksite-unloaded", point = focus, reason = "chunk-unloaded")
        try { CivsForestry.validateWork(city, work) } catch (_: IllegalArgumentException) { return changed() }
        val timber = requireNotNull(CivsProduction.material(building))
        val roots = work.forestry?.job?.roots ?: if (CivsForestry.care(work))
            forestry().careRoots(city, building, world, focus, timber) ?: return changed()
            else CivsForestry.plantingRoots(work)
        val paths = CivsForestry.paths(city, context.forestryPaths(city))
        val clear = if (work.forestry != null || CivsForestry.care(work)) CivsForestry.rootsClear(city, roots, paths)
            else CivsForestry.plantingClear(city, roots, paths)
        if (!clear) return changed()
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
                return context.blocked(worker, "worksite-no-access", point = focus, reason = "approaches-exhausted")
            rejected.retryAt = null
            rejected.checkedChanges = 0
            workSpots.remove(worker.id)
        }
        if (!CivsAutonomy.legacyStock(city) && work.inputs.any { (material, amount) ->
                (CivsEquipment.available(worker, context.stock(city, building.id))[material] ?: 0) < amount })
            return context.blocked(worker, "waiting-saplings", work.inputs.keys.first(), work.inputs.values.first())
        while (rejected.checkedChanges < work.changes.size) {
            if (!context.discoveryBudget.forestAvailable()) return context.blocked(worker, "searching-work")
            val cell = work.changes[rejected.checkedChanges]
            if (!citizenChunkReady(world, cell.position.x shr 4, cell.position.z shr 4))
                return context.blocked(worker, "worksite-unloaded", point = cell.position, reason = "chunk-unloaded")
            context.discoveryBudget.forestUnits--
            val block = context.read(city, building, world, cell.position, allowOwn = false, worker = worker) ?: return null
            if (block.blockData.asString != cell.before) return changed()
            rejected.checkedChanges++
        }
        val standing = beside(city, worker, building, world, focus, allowOwn = false) ?: return null
        return work.copy(standing = standing.standing, remoteWork = standing.remoteWork)
    }

    fun workAccessible(city: Settlement, building: CivBuilding, world: World, work: CivWorldWork,
        budgeted: Boolean = false): Boolean? {
        val focus = citizenWorkFocus(work) ?: return true
        val p = work.standing
        if (budgeted && (context.spotChecksRemaining <= 0 || context.nanoTime() >= context.discoveryBudget.deadline)) return null
        if (budgeted) context.spotChecksRemaining--
        if (!workStandingAllowed(city, building, world, work)) return false
        val eye = if (work.remoteWork) (remoteFeet(world, work) ?: return false).clone().add(0.0, 1.62, 0.0)
            else Location(world, p.x + .5, p.y + 1.62, p.z + .5)
        val reach = citizenWorkReach(building.type, work)
        for (aim in citizenBlockAimPoints(eye, focus).filter { eye.distanceSquared(it) <= reach * reach }) {
            if (budgeted && (context.spotRaysRemaining <= 0 || context.nanoTime() >= context.discoveryBudget.deadline)) return null
            if (budgeted) context.spotRaysRemaining--
            if (citizenCanReachBlockPoint(world, eye, focus, aim, reach, work)) return true
        }
        return false
    }

    fun workStandingAllowed(city: Settlement, building: CivBuilding, world: World, work: CivWorldWork,
        focus: CivPosition? = citizenWorkFocus(work)): Boolean {
        focus ?: return false
        val p = work.standing
        if (work.remoteWork && building.type !in context.professionTypes) return false
        val actual = if (work.remoteWork) (remoteFeet(world, work) ?: return false).let { CitizenPoint(it.x, it.y, it.z) }
            else CitizenPoint(p.x + .5, p.y.toDouble(), p.z + .5)
        if (building.type == BuildingType.QUARRY && work.miningStep != null &&
            (!mineBuiltStanding(building, p, work.miningAccessStep ?: work.miningStep, work.miningAccessSections.orEmpty(),
                city.minePlans?.get(building.id.toString())) ||
                !(if (work.remoteWork) mineLadderConnected(building, world, p) else mineStandingConnected(building, world, focus, p))))
            return false
        return world.isChunkLoaded(p.x shr 4, p.z shr 4) && world.isChunkLoaded(focus.x shr 4, focus.z shr 4) &&
            city.contains(p.x, p.z) && citizenControlClear(city, p) && !context.locked(city.worldId, p) &&
            civsProtectedBuildings(city).none { (building.type == BuildingType.FORESTER || it.id != building.id) &&
                worksiteProtectedByBuilding(building, it, p, 1) } &&
            (if (work.remoteWork) citizenPacketSupported(world, actual) else citizenStandable(world, p)) &&
            citizenPacketBodyClear(world, actual, false)
    }

    private fun remoteFeet(world: World, work: CivWorldWork): Location? = context.residentFeet(work.workerId)?.takeIf {
        it.world == world && it.blockX == work.standing.x && it.blockZ == work.standing.z &&
            kotlin.math.abs(it.y - work.standing.y) <= .500001
    }

    fun beside(city: Settlement, worker: CivWorker, building: CivBuilding, world: World, target: CivPosition,
                       allowOwn: Boolean = true, ladder: Boolean = false, miningStep: Long? = null,
                       preferred: CivPosition? = null, miningAccessStep: Long? = miningStep,
                       capture: (() -> CivWorldWork?)? = null, allowRemote: Boolean = true,
        execution: CivMineExecution? = null): CivWorkApproach? {
        val rejected = rejectedWorkSpots[worker.id]?.takeIf {
            it.key == standingKey(worker, building, miningStep, target, execution?.ordinal)
        }
        rejected?.retryAt?.let { retryAt ->
            if (Bukkit.getCurrentTick().toLong() < retryAt) {
                context.blocked(worker, "worksite-no-access", point = target, reason = "approaches-exhausted")
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
            context.residentFeet(worker.id)?.takeIf { it.world == world }?.let { feet ->
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
        val remoteAvailable = allowRemote && context.residentFeet(worker.id)?.world == world
        fun pending(): CivWorkApproach? {
            context.spotSearchPending = true
            context.blocked(worker, "searching-work")
            return null
        }
        fun tryRemote(): CivWorkApproach? {
            if (search.remoteFinished) return null
            if (rejected?.remoteChecked == true) {
                search.remoteFinished = true
                return null
            }
            // Retain a tree's actual batch while its crown-aware probe resumes.
            // Other actions carry only their already inspected target here; their
            // caller seals native changes before the returned approach is admitted.
            val intent = if (capture != null) {
                search.captured?.takeIf { context.cachedMatches(city, worker, building, it) }?.work
                    ?: (capture() ?: run {
                        if (context.currentNeed(city, worker)?.status == "searching-work") pending()
                        return null
                    }).also { search.captured = Cached(building, worker, it) }
            } else CivWorldWork(worker.id, worker.completedCycles, building.id, target, emptyList(),
                status = "searching-work", miningStep = miningStep,
                miningAccessStep = execution?.prefix ?: miningAccessStep,
                miningAccessSections = execution?.sections?.toMap(),
                miningOrdinal = execution?.ordinal, focus = target)
            val remote = remoteApproach(city, worker, building, world, intent, search.remote, target)
            if (context.spotSearchPending) return pending()
            search.remoteFinished = true
            if (remote == null) return null
            workSpots.remove(worker.id)
            return CivWorkApproach(remote.standing, remoteWork = true, focus = target, capturedWork = search.captured?.work)
        }
        // One ordinary slice earns one remote probe on its next admission. A failed
        // probe resumes these exact native/aim cursors instead of restarting either search.
        if (remoteAvailable && search.ordinaryAttempted && !search.remoteFinished) {
            val remote = tryRemote()
            if (remote != null || !search.remoteFinished) return remote
        }
        fun nextPosition() { search.next++; search.nextAim = 0 }
        while (search.next < offsets.size) {
            if (context.nanoTime() >= context.discoveryBudget.deadline) return pending()
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
                    execution?.prefix ?: miningAccessStep, execution?.sections.orEmpty(),
                    city.minePlans?.get(building.id.toString()))
                    else !CivsMinePlan.workStandingAllowed(building, p))) { nextPosition(); continue }
            if (rejectedStanding(worker, building, miningStep, target, p, execution?.ordinal)) { nextPosition(); continue }
            val shaft = shaftApproach
            if (shaft != null && (kotlin.math.abs(p.x.toLong() - shaft.x) > 1 ||
                    kotlin.math.abs(p.z.toLong() - shaft.z) > 1)) { nextPosition(); continue }
            if (!city.contains(p.x, p.z) || !citizenControlClear(city, p) || context.locked(city.worldId, p) ||
                civsProtectedBuildings(city).any { (!allowOwn || it.id != building.id) && worksiteProtectedByBuilding(building, it, p, 1) })
                { nextPosition(); continue }
            if (context.spotChecksRemaining <= 0) return pending()
            context.spotChecksRemaining--
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
                if (context.spotRaysRemaining <= 0 || context.nanoTime() >= context.discoveryBudget.deadline) return pending()
                context.spotRaysRemaining--
                if (citizenCanReachBlockPoint(world, eye, target, aims[search.nextAim++], reach)) {
                    workSpots.remove(worker.id)
                    return CivWorkApproach(p, capturedWork = search.captured?.takeIf {
                        context.cachedMatches(city, worker, building, it)
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
        if (building.type == BuildingType.FORESTER) {
            val key = requireNotNull(standingKey(worker, building, null, target))
            val closed = rejected ?: RejectedWorkSpots(key, target)
            search.remote.origins.forEach(closed.remote::observed)
            rejectedWorkSpots[worker.id] = closed
        }
        // A completed sweep may retry after a bounded pause; elapsed time never
        // discards failures in the middle of trying its remaining alternatives.
        if (rejected != null && rejected.positions.isNotEmpty()) rejected.retryAt = Bukkit.getCurrentTick().toLong() + 100
        context.blocked(worker, "worksite-no-access", point = target,
            reason = if (search.sawStanding) "line-of-sight-blocked" else "no-supported-position",
            blockMaterial = world.getBlockAt(target.x, target.y, target.z).type.name)
        return null
    }

    companion object {
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

internal object CivsMineApproach {
    fun mineShaftApproach(building: CivBuilding, target: CivPosition): CivPosition? {
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

    fun mineStandingConnected(building: CivBuilding, world: World, target: CivPosition, standing: CivPosition): Boolean =
        mineLadderConnected(building, world, standing) &&
            (mineShaftApproach(building, target)?.let { shaftStandingConnected(world, standing, it) } ?: true)

    /** A lower floor is usable only after its real ladder connects to the authored entrance. */
    fun mineLadderConnected(building: CivBuilding, world: World, standing: CivPosition,
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

    fun mineBuiltStanding(building: CivBuilding, point: CivPosition, prefix: Long,
        sections: Map<String, Long> = emptyMap(), state: CivMinePlanState? = null): Boolean =
        CivsMinePlan.forBuilding(building)?.let { it.builtStanding(CivsMinePlan.localPoint(building, point), prefix, sections) } == true ||
            CivsMineProgress.priorStanding(building, state, point)

    fun shaftStandingConnected(world: World, p: CivPosition, shaft: CivPosition,
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

}
