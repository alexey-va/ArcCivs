package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import java.util.UUID
import ru.arc.civs.requireWorksite as requireCivs

/** Discovery changes no durable city, inventory or world state. Selected work uses the existing receipt. */
internal sealed interface CivWorksiteDiscovery {
    data object Retained : CivWorksiteDiscovery
    data object Pending : CivWorksiteDiscovery
    data object Unavailable : CivWorksiteDiscovery
    data class Selected(val work: CivWorldWork) : CivWorksiteDiscovery
}

/** Shared action admission and zone lifecycle, implemented by the three native professions. */
internal abstract class CivsProfessionWorksite(
    protected val context: CivsWorksiteContext,
    protected val approaches: CivsWorksiteApproaches,
) {
    abstract val type: BuildingType
    abstract val toolSuffix: String
    open val discoveryName: String = "worksite"

    open fun active(city: Settlement, building: CivBuilding): Boolean = city.inventory != null ||
        city.autonomy?.paused == false && CivsAutonomousWorksites.supports(city, building)
    abstract fun hasBudget(city: Settlement, worker: CivWorker, building: CivBuilding, current: CivWorldWork?): Boolean
    protected abstract fun propose(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        current: CivWorldWork?): CivWorksiteDiscovery

    protected fun toolWork(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        current: CivWorldWork?): CivWorldWork? =
        if (current == null && !CivsAutonomy.legacyStock(city)) context.equipTool(city, worker, building, world, toolSuffix) else null

    protected fun approachAccessible(city: Settlement, building: CivBuilding, world: World, work: CivWorldWork): Boolean? {
        val focus = citizenWorkFocus(work)
        val p = work.standing
        if (!citizenChunkReady(world, p.x shr 4, p.z shr 4) || focus != null &&
            !citizenBlockTraceReady(world, Location(world, p.x + .5, p.y + 1.62, p.z + .5), focus)) return null
        return approaches.workAccessible(city, building, world, work, budgeted = true)
    }

    protected open fun inspectCurrent(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        work: CivWorldWork): CivWorksiteDiscovery? {
        if (!work.remoteWork) return null
        when (approachAccessible(city, building, world, work)) {
            null -> return CivWorksiteDiscovery.Pending
            false -> approaches.retireAction(worker.id)
            true -> Unit
        }
        return null
    }

    protected open fun recoverRejected(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        pending: CivWorkCached): CivWorldWork? {
        context.cached[worker.id] = pending
        approaches.rejectedWorkSpots.getValue(worker.id).pending = null
        approaches.retireAction(worker.id)
        context.needs[worker.id] = CivWorkNeedState(building, worker.completedCycles, "searching-work")
        return null
    }

    fun discover(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        previous: CivWorldWork?): CivWorksiteDiscovery {
        var current = previous
        if (current != null) {
            inspectCurrent(city, worker, building, world, current)?.let { return it }
            if (context.plan(city, worker) != current) current = null
        }
        context.needs[worker.id] = CivWorkNeedState(building, worker.completedCycles, "searching-work")
        val rejected = approaches.rejectedWorkSpots[worker.id]
        val retry = rejected?.pending
        if (rejected != null && retry != null) {
            var replacement = if (!rejected.remoteChecked) approaches.remoteWork(city, worker, building, world, rejected, retry) else null
            if (context.spotSearchPending) return CivWorksiteDiscovery.Pending
            rejected.remoteChecked = true
            if (replacement == null) replacement = recoverRejected(city, worker, building, world, retry)
            if (replacement != null) {
                context.cached[worker.id] = CivWorkCached(building, worker, replacement)
                approaches.rejectedWorkSpots[worker.id]?.pending = null
                context.needs.remove(worker.id)
            }
            if (replacement != null) return CivWorksiteDiscovery.Selected(replacement)
            if (approaches.rejectedWorkSpots[worker.id]?.pending != null) return CivWorksiteDiscovery.Unavailable
        }
        val proposal = propose(city, worker, building, world, current)
        if (proposal !is CivWorksiteDiscovery.Selected) return proposal
        val work = CivsAutonomousWorksites.visual(city, building, proposal.work)
        context.needs.remove(worker.id)
        if (!CivsMineFallback.matches(work)) approaches.workSpots.remove(worker.id)
        selected(worker.id, work)
        context.cached[worker.id] = CivWorkCached(building, worker, work)
        if (work.remoteWork) approaches.reportRemoteWork(city, worker, building, world, work, "ordinary-approach-unavailable", 0)
        approaches.rejectedWorkSpots[worker.id]?.takeIf {
            it.key != approaches.standingKey(worker, building, work.miningStep, citizenWorkFocus(work), work.miningOrdinal)
        }?.let { approaches.rejectedWorkSpots.remove(worker.id) }
        return CivWorksiteDiscovery.Selected(work)
    }

    protected fun result(work: CivWorldWork?, current: CivWorldWork?): CivWorksiteDiscovery =
        work?.let { CivWorksiteDiscovery.Selected(it) } ?: if (current == null) CivWorksiteDiscovery.Unavailable else CivWorksiteDiscovery.Retained
    protected open fun selected(workerId: UUID, work: CivWorldWork) = Unit

    open fun validateVersion(work: CivWorldWork) = Unit
    open fun validatePlan(city: Settlement, worker: CivWorker, building: CivBuilding, world: World, work: CivWorldWork) {
        if (work.remoteWork) requireCivs(approaches.workAccessible(city, building, world, work) == true, "stale-worksite")
    }
    open fun validateNativeProof(city: Settlement, worker: CivWorker, building: CivBuilding, world: World, work: CivWorldWork) = Unit
    open fun requirement(building: CivBuilding, required: Pair<String, Long>, explicit: Boolean): Pair<String, Long>? =
        required.first to maxOf(required.second, Material.matchMaterial(required.first)?.maxStackSize?.toLong() ?: 1)
    open fun searchEligible(city: Settlement, worker: CivWorker, building: CivBuilding): Boolean = false
    open fun pausedStatus(city: Settlement, worker: CivWorker): String? = null
    open fun structuralNeed(need: CivWorkNeedState): Boolean = false
    open fun reportSelected(city: Settlement, worker: CivWorker, building: CivBuilding, work: CivWorldWork) = Unit

    open fun invalidate(workerId: UUID, building: CivBuilding?, completedWork: CivWorldWork?, hadCached: Boolean) = Unit
    open fun completed(workerId: UUID, building: CivBuilding, work: CivWorldWork) = Unit
    open fun worldChanged(worldId: UUID, point: CivPosition) = Unit
    abstract fun changed(workerId: UUID, building: CivBuilding, point: CivPosition)
    abstract fun protectionChanged(workerId: UUID, building: CivBuilding?, affected: (CivPosition, Int) -> Boolean)
    abstract fun forget(workerId: UUID, building: CivBuilding?, buildingStillUsed: Boolean)
    abstract fun retain(workerIds: Set<UUID>, buildingIds: Set<UUID>)
    abstract fun containsChunk(workerId: UUID, building: CivBuilding, chunk: Pair<Int, Int>): Boolean
}
