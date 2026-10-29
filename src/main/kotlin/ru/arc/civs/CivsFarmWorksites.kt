package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.Ageable
import java.util.UUID
import java.util.logging.Level
import ru.arc.civs.requireWorksite as requireCivs

/** Owns all farmers' retained fields, action priority and native crop admission. */
internal class CivsFarmWorksites(context: CivsWorksiteContext, approaches: CivsWorksiteApproaches) :
    CivsProfessionWorksite(context, approaches) {
    override val type = BuildingType.FARM
    override val toolSuffix = "_HOE"
    override fun pausedStatus(city: Settlement, worker: CivWorker): String? = context.currentNeed(city, worker)?.status?.takeIf {
        CivsEquipment.slots(worker)[4]?.material?.endsWith(toolSuffix) == true
    }
    val scans = hashMapOf<UUID, CivsFarmWorksite>()
    private val reportedFarmSelections = hashMapOf<UUID, Int>()

    override fun hasBudget(city: Settlement, worker: CivWorker, building: CivBuilding, current: CivWorldWork?): Boolean =
        context.discoveryBudget.columns > 0 || scans[worker.id]?.harvest?.any { !farmClaimed(city, worker, building, it) } == true

    override fun propose(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        current: CivWorldWork?): CivWorksiteDiscovery {
        val known = scans[worker.id]?.takeIf { it.building == building }
        val scan = if (current == null && known?.harvest?.any { !farmClaimed(city, worker, building, it) } == true) known
            else scanFarm(city, worker, building, world)
        if (current != null && (!current.cropCare || scan.harvest.none { !farmClaimed(city, worker, building, it) }))
            return CivWorksiteDiscovery.Retained
        return result(toolWork(city, worker, building, world, current)
            ?: farm(city, worker, building, world, scan, harvestOnly = current != null), current)
    }
    override fun selected(workerId: UUID, work: CivWorldWork) { scans[workerId]?.selected(work.focus) }
    override fun validatePlan(city: Settlement, worker: CivWorker, building: CivBuilding, world: World, work: CivWorldWork) {
        super.validatePlan(city, worker, building, world, work)
        if (work.cropCare) {
            val focus = work.focus ?: throw CivsRejection("stale-worksite")
            val crop = context.read(city, building, world, focus) ?: throw CivsRejection("protected-area")
            val soil = context.read(city, building, world, focus.copy(y = focus.y - 1)) ?: throw CivsRejection("protected-area")
            val age = crop.blockData as? Ageable
            requireCivs(soil.type == Material.FARMLAND && crop.type == CivsFarmWorksite.cropAndSeed(building).first &&
                age != null && age.age < age.maximumAge, "stale-worksite")
            requireCivs(work.status != "watering" || !world.hasStorm() || world.getHighestBlockYAt(focus.x, focus.z) > focus.y,
                "stale-worksite")
            CivsWorksiteLayouts.irrigation(building).forEach { point ->
                val water = context.read(city, building, world, point) ?: throw CivsRejection("protected-area")
                requireCivs(water.type == Material.WATER, "stale-worksite")
            }
        }
    }
    override fun changed(workerId: UUID, building: CivBuilding, point: CivPosition) { scans[workerId]?.changed(point) }
    override fun protectionChanged(workerId: UUID, building: CivBuilding?, affected: (CivPosition, Int) -> Boolean) {
        scans[workerId]?.protectionChanged { affected(it, 0) }
    }
    override fun forget(workerId: UUID, building: CivBuilding?, buildingStillUsed: Boolean) {
        scans.remove(workerId); reportedFarmSelections.remove(workerId)
    }
    override fun retain(workerIds: Set<UUID>, buildingIds: Set<UUID>) {
        scans.keys.retainAll(workerIds); reportedFarmSelections.keys.retainAll(workerIds)
    }
    override fun containsChunk(workerId: UUID, building: CivBuilding, chunk: Pair<Int, Int>): Boolean =
        chunk in scans[workerId]?.chunks.orEmpty()
    override fun reportSelected(city: Settlement, worker: CivWorker, building: CivBuilding, work: CivWorldWork) {
            scans[worker.id]?.takeIf { Bukkit.getCurrentTick() - reportedFarmSelections.getOrDefault(worker.id, -100) >= 100 }?.let { scan ->
                reportedFarmSelections[worker.id] = Bukkit.getCurrentTick()
                CivsDiagnostics.record(Level.INFO, city.id, CivsDiagnostics.line("farm_work_selected", "city" to city.id, "worker" to worker.id,
                    "building" to building.id, "cycle" to worker.completedCycles, "status" to work.status,
                    "point" to work.focus, "ripe_known" to scan.harvest.size,
                    "ripe_available" to scan.harvest.count { !farmClaimed(city, worker, building, it) },
                    "field_examined" to scan.examined, "field_size" to scan.plots.size, "scan_cursor" to scan.next), context.plugin.logger)
            }
    }

    private fun scanFarm(city: Settlement, worker: CivWorker, building: CivBuilding, world: World): CivsFarmWorksite {
        val scan = scans[worker.id]?.takeIf { it.building == building }
            ?: CivsFarmWorksite(building).also { scans[worker.id] = it }
        val autonomous = CivsAutonomousWorksites.supports(city, building)
        scan.refresh(world, autonomous,
            available = { if (autonomous) emptyMap() else CivsEquipment.available(worker, context.stock(city, building.id)) },
            maximumColumns = context.discoveryBudget.columns,
            admitColumn = {
                if (context.nanoTime() >= context.discoveryBudget.deadline) false else { context.discoveryBudget.columns--; true }
            }, read = { context.read(city, building, world, it) })
        return scan
    }

    /** Every farmer can harvest the whole field; admitted targets keep coworkers from duplicating it. */
    fun farmClaimed(city: Settlement, worker: CivWorker, building: CivBuilding, soil: CivPosition): Boolean = city.workers.any { other ->
        other.id != worker.id && other.assignment == building.id && context.plan(city, other)?.focus?.let { focus ->
            focus == soil || focus == soil.copy(y = soil.y + 1)
        } == true
    }

    private fun farm(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        scan: CivsFarmWorksite, harvestOnly: Boolean): CivWorldWork? {
        val autonomous = CivsAutonomousWorksites.supports(city, building)
        val selection = scan.select(worker, world, autonomous, harvestOnly,
            available = { if (autonomous) mapOf(CivsFarmWorksite.cropAndSeed(building).second to 1L)
                else CivsEquipment.available(worker, context.stock(city, building.id)) },
            pending = approaches.workSpots[worker.id]?.target,
            claimed = { farmClaimed(city, worker, building, it) },
            read = { point, report -> context.read(city, building, world, point, worker = worker.takeIf { report }) },
            blocked = { need -> context.blocked(worker, need.status, need.material, point = need.point,
                reason = need.reason, blockMaterial = need.blockMaterial) },
            admit = { point ->
                val standing = approaches.beside(city, worker, building, world, point)
                CivsFarmWorksite.Attempt(standing?.let { approach -> { action ->
                    context.work(worker, building, approach, action.changes, action.inputs, action.outputs,
                        action.status, action.duration, focus = action.focus)
                } }, context.spotSearchPending)
            })
        return when (selection) {
            is CivsFarmWorksite.Selection.Ready -> selection.work
            CivsFarmWorksite.Selection.Exhausted -> {
                if (context.needs.getValue(worker.id).status == "searching-work") context.blocked(worker, "waiting-growth")
                null
            }
            CivsFarmWorksite.Selection.Unavailable, CivsFarmWorksite.Selection.Pending -> null
        }
    }

}
