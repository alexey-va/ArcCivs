package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.type.Fence
import org.bukkit.inventory.ItemStack
import java.util.UUID
import org.bukkit.HeightMap
import java.util.logging.Level
import ru.arc.civs.requireWorksite as requireCivs
import ru.arc.civs.CivMineExecution as MineExecution
import ru.arc.civs.CivMineSafetyScan as MineSafetyScan
import ru.arc.civs.CivMineProtection as MineProtection
import ru.arc.civs.CivWorkNeedState as Need

internal data class CivMineExecution(val building: CivBuilding, val saved: CivMinePlanState?, val legacy: Long?,
    val basePrefix: Long, var prefix: Long, val sections: MutableMap<String, Long>,
    var selected: String? = null, var cursor: Int = 0, var ordinal: Int = 0, var checkedExcavation: Boolean = false,
    val retryAt: MutableMap<String, Long> = hashMapOf(), var lastDenial: Need? = null,
    var lastDeniedSection: String? = null,
    val blockedSections: MutableMap<String, Need> = linkedMapOf(),
    var discoveryAdmissions: Int = 0,
    var unsealedExcavation: CivPosition? = null,
    val legacyBlocks: Map<CivPosition, String?> = emptyMap())
internal data class CivMineSafetyScan(val building: CivBuilding, val target: CivPosition, val progress: Long,
    val excavation: CivPosition?, val accessProgress: Long, val frontier: List<MineProtection>,
    val sections: Map<String, Long> = emptyMap(),
    var next: Int = 0, val deferredAirWalls: MutableSet<CivPosition> = hashSetOf())
internal data class CivMineProtection(val exposed: CivPosition, val block: CivPosition, val material: Material?,
    val transferFooting: Boolean = false)


/** Retained mine execution, cover probes, paid safety frontier and receipt authority. */
internal class CivsMineWorksite(context: CivsWorksiteContext, approaches: CivsWorksiteApproaches) :
    CivsProfessionWorksite(context, approaches) {
    override val type = BuildingType.QUARRY
    override val toolSuffix = "_PICKAXE"
    override val discoveryName = "mine"
    override fun worldChanged(worldId: UUID, point: CivPosition) { changedCover(worldId, point) }
    private val reportedMineSelections = hashMapOf<UUID, Int>()

    override fun hasBudget(city: Settlement, worker: CivWorker, building: CivBuilding, current: CivWorldWork?): Boolean =
        if (current != null) context.spotChecksRemaining > 0 && context.spotRaysRemaining > 0
        else context.discoveryBudget.columns > 0 && context.mineChecksRemaining > 0 && context.spotChecksRemaining > 0 && context.spotRaysRemaining > 0
    override fun inspectCurrent(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        work: CivWorldWork): CivWorksiteDiscovery? = when (approachAccessible(city, building, world, work)) {
        null -> CivWorksiteDiscovery.Pending
        true -> CivWorksiteDiscovery.Retained
        false -> { approaches.retireAction(worker.id); null }
    }
    override fun propose(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        current: CivWorldWork?): CivWorksiteDiscovery = result(toolWork(city, worker, building, world, current)
            ?: mine(city, worker, building, world), current)
    override fun requirement(building: CivBuilding, required: Pair<String, Long>, explicit: Boolean): Pair<String, Long>? {
        val batch = CivsLogistics.quarrySupplies(building)[required.first]
        if (batch != null && !explicit) return null
        return if (batch == null) super.requirement(building, required, explicit) else required.first to batch
    }
    override fun searchEligible(city: Settlement, worker: CivWorker, building: CivBuilding): Boolean =
        CivsAutonomousWorksites.supports(city, building) || city.inventory != null &&
            CivsEquipment.slots(worker)[4]?.material?.endsWith(toolSuffix) == true &&
            CivsLogistics.QUARRY_SUPPLIES.keys.all { CivsEquipment.plainCount(worker, it) > 0 }
    override fun structuralNeed(need: Need): Boolean = need.status in setOf("worksite-no-access", "worksite-obstacle", "worksite-layout")
    override fun validateVersion(work: CivWorldWork) {
        requireCivs(work.miningStep == null && work.miningPlanVersion == null || work.miningPlanVersion == CivsMinePlan.VERSION, "stale-worksite")
    }
    override fun validatePlan(city: Settlement, worker: CivWorker, building: CivBuilding, world: World, work: CivWorldWork) {
        if (work.status == "digging-mine" && work.miningStep == null) {
            requireCivs(CivsMineFallback.eligible(city, building, worker) && CivsMineFallback.matches(work) &&
                mineFallbackStanding(city, worker, building, world, work.standing), "stale-worksite")
        }
        if (work.remoteWork || work.miningStep != null)
            requireCivs(approaches.workAccessible(city, building, world, work) == true, "stale-worksite")
    }
    override fun validateNativeProof(city: Settlement, worker: CivWorker, building: CivBuilding, world: World, work: CivWorldWork) {
        if (work.miningStep != null) validateMineReceiptNative(city, work)
        // A wall can change during the real two-second action while its target
        // remains unchanged. Recheck the same bounded frontier at receipt admission.
        if (work.miningPlanVersion == CivsMinePlan.VERSION && !CivsMineSafety.clearingLava(work) && work.changes.any {
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
                val opening = context.read(city, building, world, exposed) ?: throw CivsRejection("protected-area")
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
                approaches.workAccessible(city, building, world, work) == true, "stale-worksite")
            for (task in mineFrontier(city, building, world, geometry, standing.copy(y = standing.y - 1), emptySet(), accessStep = geometry.steps.size.toLong())
                ?: throw CivsRejection("protected-area")) {
                val block = readMineProtection(city, building, world, task)
                    ?: throw CivsRejection("protected-area")
                requireCivs(!mineProtectionNeeded(block, task.material), "stale-worksite")
            }
            for (torch in geometry.floors.last().torches.filter {
                kotlin.math.abs(it.x - standing.x) + kotlin.math.abs(it.z - standing.z) == 1
            }) requireCivs(context.read(city, building, world, CivsWorksiteLayouts.point(building, torch))?.type == Material.TORCH,
                "stale-worksite")
        }
    }
    override fun invalidate(workerId: UUID, building: CivBuilding?, completedWork: CivWorldWork?, hadCached: Boolean) {
        if (completedWork == null && !hadCached && building != null) recheckBlockedSection(building)
    }
    override fun containsChunk(workerId: UUID, building: CivBuilding, chunk: Pair<Int, Int>): Boolean = mineBounds[building.id]?.let { bounds ->
        chunk.first in (bounds.min.x shr 4)..(bounds.max.x shr 4) && chunk.second in (bounds.min.z shr 4)..(bounds.max.z shr 4)
    } == true
    override fun reportSelected(city: Settlement, worker: CivWorker, building: CivBuilding, work: CivWorldWork) {
            if (work.miningPlanVersion == CivsMinePlan.VERSION &&
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
                    "status" to work.status, "point" to work.focus, "standing" to work.standing), context.plugin.logger)
            }
    }

    val mineExecutions = hashMapOf<UUID, MineExecution>()
    val mineCursors = hashMapOf<UUID, Triple<Int, Long, Int>>()
    val mineCovers = hashMapOf<UUID, CivMineCover>()
    val mineSafety = hashMapOf<UUID, MineSafetyScan>()
    val mineBounds = hashMapOf<UUID, CivBox>()
    private val mineFrontierDirty = hashSetOf<UUID>()

    fun recheckBlockedSection(building: CivBuilding) {
        mineExecutions[building.id]?.let { execution ->
            execution.lastDeniedSection?.takeIf { it in CivsMinePlan.forBuilding(building)?.sections.orEmpty() }?.let { section ->
                execution.retryAt.remove(section)
                execution.blockedSections.remove(section)
                execution.selected = section
            }
        }
    }
    override fun completed(workerId: UUID, building: CivBuilding, completedWork: CivWorldWork) {
        if (building.type != BuildingType.QUARRY || completedWork.miningStep == null) return
        mineCursors[building.id]?.let { cursor ->
            mineCursors[building.id] = Triple(building.level, completedWork.miningStep,
                maxOf(cursor.third, completedWork.miningStep.toInt()))
        }
        if (completedWork.status != "securing-mine") { mineFrontierDirty += workerId; mineSafety.remove(workerId) }
    }
    fun changedCover(worldId: UUID, point: CivPosition) {
        mineCovers.entries.removeIf { (_, cover) -> cover.world == worldId && cover.x == point.x && cover.z == point.z &&
            point.y >= (cover.ground ?: cover.next) }
    }
    override fun changed(workerId: UUID, building: CivBuilding, point: CivPosition) {
        mineExecutions[building.id]?.let { execution ->
            if (mineBounds[building.id]?.contains(point.x, point.y, point.z) == true) {
                execution.retryAt.clear(); execution.blockedSections.clear()
            } else execution.blockedSections.entries.removeIf { (_, denial) ->
                denial.reason == "mine-thin-cover" && denial.point?.let {
                    point.x == it.x && point.z == it.z && point.y >= it.y
                } == true
            }
        }
        mineSafety[workerId]?.let { scan ->
            val index = scan.frontier.indexOfFirst { task ->
                point == CivsWorksiteLayouts.point(building, task.block) || point == CivsWorksiteLayouts.point(building, task.exposed)
            }
            if (index >= 0) scan.next = minOf(scan.next, index)
        }
    }
    override fun protectionChanged(workerId: UUID, building: CivBuilding?, affected: (CivPosition, Int) -> Boolean) {
        refreshSafetyProtection(workerId, affected)
        building?.let { mineExecutions[it.id] }?.blockedSections?.entries?.removeIf { (_, denial) ->
            denial.point?.let { affected(it, 0) } == true
        }
    }
    fun refreshSafetyProtection(workerId: UUID, affected: (CivPosition, Int) -> Boolean) {
        mineSafety[workerId]?.let { scan ->
            if (scan.frontier.any { affected(CivsWorksiteLayouts.point(scan.building, it.block), 0) }) scan.next = 0
        }
    }
    override fun forget(workerId: UUID, building: CivBuilding?, buildingStillUsed: Boolean) {
        mineSafety.remove(workerId); mineFrontierDirty.remove(workerId); mineCovers.remove(workerId); reportedMineSelections.remove(workerId)
        if (building != null && !buildingStillUsed) {
            mineCursors.remove(building.id); mineExecutions.remove(building.id); mineBounds.remove(building.id)
        }
    }
    override fun retain(workerIds: Set<UUID>, buildingIds: Set<UUID>) {
        reportedMineSelections.keys.retainAll(workerIds)
        mineCursors.keys.retainAll(buildingIds); mineExecutions.keys.retainAll(buildingIds)
        mineSafety.keys.retainAll(workerIds); mineCovers.keys.retainAll(workerIds)
    }

    private fun prepareQuarry(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
                              needed: String? = null): CivWorldWork? {
        val available = context.stock(city, building.id)
        val batches = CivsLogistics.quarrySupplies(building)
        // Carry available future framing supplies together, but do not stop an unrelated
        // cut for timber that is only needed by a later paid construction step.
        val required = batches.mapValues { (material, batch) ->
            (batch - CivsEquipment.plainCount(worker, material)).coerceAtLeast(0)
        }.filter { (material, amount) -> amount > 0 &&
            (material in CivsLogistics.QUARRY_SUPPLIES || material == needed || (available[material] ?: 0) >= amount) }
        required.entries.firstOrNull { (material, amount) -> (available[material] ?: 0) < amount }?.let { (material, _) ->
            return context.blocked(worker, "waiting-${material.lowercase()}", material, batches.getValue(material))
        }
        val supplied = try { CivsEquipment.collectSupplies(worker, required) } catch (failure: CivsRejection) {
            if (failure.reason != "resident-inventory-full") throw failure
            return context.blocked(worker, "resident-inventory-full", point = building.control, reason = failure.reason)
        }
        val standing = citizenStorageTarget(city, building, world)
            ?: return context.blocked(worker, "storage-unavailable", point = building.control, reason = "quarry-supplies-inaccessible")
        return work(worker, building, CivWorkApproach(standing), emptyList(), required, status = "collecting-supplies", duration = 1_000)
            .copy(equipmentAfter = CivsEquipment.slots(supplied))
    }

    private fun mineOwnedBlock(city: Settlement, building: CivBuilding, local: CivPosition, data: String): Boolean {
        val state = city.minePlans?.get(building.id.toString())
        val fence = Bukkit.createBlockData(data) as? Fence
        fun matches(expected: String?): Boolean {
            if (expected == data) return true
            if (expected == null || fence == null) return false
            val paid = Bukkit.createBlockData(expected) as? Fence ?: return false
            if (paid.material != fence.material) return false
            // Neighbour changes update connections; the paid cell's kind and remaining state stay exact.
            paid.allowedFaces.forEach { paid.setFace(it, fence.hasFace(it)) }
            return paid.asString == fence.asString
        }
        fun owned(mine: CivBuilding, proof: CivMinePlanState?, legacyProgress: Long?, at: CivPosition): Boolean {
            if (matches(proof?.seals?.get(CivsMineProgress.key(at)))) return true
            val current = CivsMinePlan.forBuilding(mine, proof?.version ?: CivsMinePlan.VERSION)
            if (proof != null && matches(current?.expectedAt(at, proof.nextStep,
                    proof.sectionSteps.orEmpty(), mine.rotation))) return true
            CivsMineProgress.v5State(proof)?.let { old ->
                if (matches(old.seals?.get(CivsMineProgress.key(at))) || matches(CivsMinePlan.forBuilding(mine, 5)?.expectedAt(
                        at, old.nextStep, old.sectionSteps.orEmpty(), mine.rotation))) return true
            }
            CivsMineProgress.v6State(proof)?.let { old ->
                if (matches(old.seals?.get(CivsMineProgress.key(at))) || matches(CivsMinePlan.forBuilding(mine, 6)?.expectedAt(
                        at, old.nextStep, old.sectionSteps.orEmpty(), mine.rotation))) return true
            }
            val legacy = current?.legacyCompleted(legacyProgress ?: 0L)?.get(at)
            if (legacy != null && matches(Bukkit.createBlockData(legacy).also { value ->
                value.rotate(when (Math.floorMod(mine.rotation / 90, 4)) {
                    1 -> org.bukkit.block.structure.StructureRotation.CLOCKWISE_90
                    2 -> org.bukkit.block.structure.StructureRotation.CLOCKWISE_180
                    3 -> org.bukkit.block.structure.StructureRotation.COUNTERCLOCKWISE_90
                    else -> org.bukkit.block.structure.StructureRotation.NONE
                })
            }.asString)) return true
            val versions = listOf(2 to (proof?.takeIf { it.version == 2 }?.nextStep ?: proof?.v2Step),
                3 to (proof?.takeIf { it.version == 3 }?.nextStep ?: proof?.v3Step),
                4 to (proof?.takeIf { it.version == 4 }?.nextStep ?: proof?.v4Step),
                5 to (proof?.takeIf { it.version == 5 && it.sectionSteps == null }?.nextStep ?: proof?.v5Step))
            return versions.any { (version, prefix) -> prefix != null && prefix > 0 &&
                matches(CivsMinePlan.forBuilding(mine, version)?.expectedAt(at, prefix, emptyMap(), mine.rotation)) }
        }
        if (owned(building, state, city.mineProgress?.get(building.id.toString()), local)) return true
        val worldPoint = CivsWorksiteLayouts.point(building, local)
        return state?.levels.orEmpty().any { witness -> owned(witness.building, witness.state(), witness.legacyProgress,
            CivsMinePlan.localPoint(witness.building, worldPoint)) }
    }

    /** Existing lower-level landings retain their depth reference, never ownership of new rock. */
    private fun historicalLandingAuthority(city: Settlement, building: CivBuilding, plan: CivsMinePlan,
        ordinal: Int, local: CivPosition): List<CivMineLevelWitness>? {
        val step = plan.steps[ordinal]
        if (step.stage != "shaft" || step.branch != "landing" || step.point != local) return null
        val saved = city.minePlans?.get(building.id.toString()) ?: return emptyList()
        return saved.levels.orEmpty().takeIf { CivsMineProgress.validLevels(building, saved) }.orEmpty()
    }

    private fun historicalLandingFloors(authority: List<CivMineLevelWitness>?, point: CivPosition): Set<Int> = buildSet {
        for (witness in authority.orEmpty()) {
            val saved = witness.state() ?: continue
            val local = CivsMinePlan.localPoint(witness.building, point)
            for (proof in listOfNotNull(saved, CivsMineProgress.v5State(saved), CivsMineProgress.v6State(saved)).distinctBy { it.version }) {
                val old = CivsMinePlan.forBuilding(witness.building, proof.version) ?: continue
                for (floor in old.floors) {
                    if ("${floor.index}/shaft/landing" !in old.sections) continue
                    val support = local.copy(y = floor.feetY - 1)
                    // floor.cells also contains galleries: only its initial landing section is authority.
                    if (floor.cells.asSequence().take(floor.sections.first().second).none { it == support }) continue
                    if (old.expectedAt(support, proof.nextStep, proof.sectionSteps.orEmpty(), witness.building.rotation) ==
                        "minecraft:cobblestone") add(CivsWorksiteLayouts.point(witness.building, support).y)
                }
            }
        }
    }

    private fun stepMineCover(world: World, probe: CivMineCover) = probe.step(
        historicalFloorCurrent = { y -> world.getBlockAt(probe.x, y, probe.z).blockData.asString == "minecraft:cobblestone" },
        read = { y -> world.getBlockAt(probe.x, y, probe.z).type })

    /** Same pending-mutation authority at selection, admission, saved receipt and recovery. */
    private fun mineMutationAllowed(city: Settlement, building: CivBuilding, world: World, cell: CivPlanBlock,
        ordinal: Int, worker: CivWorker? = null, budgeted: Boolean = false, generator: Boolean = false,
        sealing: Boolean = false, planVersion: Int = CivsMinePlan.VERSION, clearingLava: Boolean = false): Boolean? {
        fun denied(reason: String): Boolean {
            if (worker == null) throw CivsRejection(reason)
            context.blocked(worker, "worksite-protected", point = cell.position, reason = reason,
                blockMaterial = Bukkit.createBlockData(cell.before).material.name)
            return false
        }
        val plan = CivsMinePlan.forBuilding(building, planVersion) ?: return denied("mine-layout-missing")
        if (ordinal !in plan.steps.indices) return denied("mine-progress-invalid")
        val point = cell.position
        if (!world.isChunkLoaded(point.x shr 4, point.z shr 4)) return denied("chunks-unloaded")
        val block = world.getBlockAt(point.x, point.y, point.z)
        if (!context.worldAccess.canMine(block)) return denied("mine-claimed")
        val local = CivsMinePlan.localPoint(building, point)
        val before = Bukkit.createBlockData(cell.before).material
        val generatorFace = generator && local == plan.generator &&
            city.minePlans?.get(building.id.toString())?.nextStep == plan.steps.size.toLong() &&
            before in setOf(Material.STONE, Material.COBBLESTONE)
        val paidLiquidSeal = sealing && cell.after == "minecraft:cobblestone" && before in setOf(Material.WATER, Material.LAVA)
        val lavaClearance = clearingLava && before == Material.LAVA && cell.after == "minecraft:air" && local in plan.positions
        val ownedBefore = !before.isAir && mineOwnedBlock(city, building, local, cell.before)
        if (!before.isAir && before !in WEEDS && !CivsMineSafety.natural(before) && !generatorFace && !paidLiquidSeal && !lavaClearance &&
            !ownedBefore) return denied("mine-foreign-block")
        val saved = city.minePlans?.get(building.id.toString())
        val after = Bukkit.createBlockData(cell.after).material
        val restoredSolid = after.isSolid && saved?.let {
            CivsMinePlan.forBuilding(building, it.version)?.expectedAt(local, it.nextStep, it.sectionSteps.orEmpty(), building.rotation)
        } == cell.after
        // Removing an exact paid block reuses its existing mine opening instead of requiring untouched ground above it.
        if (generatorFace || restoredSolid || ownedBefore && after.isAir) return true
        val required = CivsMineSafety.requiredCover(plan, ordinal, local)
        if (required == 0) return true
        val cap = civsProtectedBuildings(city).filter { it.id != building.id && CivsAutonomousGeometry.horizontalContains(it.bounds, point) }
            .minOfOrNull { it.bounds.min.y - 1 } ?: world.maxHeight - 1
        val highest = minOf(cap, world.getHighestBlockYAt(point.x, point.z, HeightMap.MOTION_BLOCKING_NO_LEAVES))
        val historical = historicalLandingAuthority(city, building, plan, ordinal, local)
        val probe = if (budgeted && worker != null) mineCovers[worker.id]?.takeIf {
            it.world == city.worldId && it.building == building.id && it.x == point.x && it.z == point.z && it.top == highest &&
                it.historicalAuthority == historical
        } ?: CivMineCover(city.worldId, building.id, point.x, point.z, highest, world.minHeight,
            historical, historicalLandingFloors(historical, point))
            .also { mineCovers[worker.id] = it }
        else CivMineCover(city.worldId, building.id, point.x, point.z, highest, world.minHeight,
            historical, historicalLandingFloors(historical, point))
        while (!probe.finished) {
            if (budgeted && (context.mineChecksRemaining <= 0 || context.nanoTime() >= context.discoveryBudget.deadline)) {
                context.spotSearchPending = true
                if (worker != null) context.blocked(worker, "searching-work")
                return null
            }
            if (budgeted) context.mineChecksRemaining--
            stepMineCover(world, probe)
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
            mineSafety.remove(work.workerId)
            mineCovers.remove(work.workerId)
            throw failure
        }
    }

    /** A durable fallback remains subject to native authority without requiring an old actor/cache after restart. */
    internal fun validateMineFallbackReceiptNative(city: Settlement, work: CivWorldWork) {
        val worker = city.workers.singleOrNull { it.id == work.workerId } ?: throw CivsRejection("worker-not-found")
        val building = city.building(work.buildingId) ?: throw CivsRejection("building-not-found")
        requireCivs(CivsMineFallback.eligible(city, building, worker) && worker.completedCycles == work.cycle &&
            CivsMineFallback.matches(work), "stale-worksite")
        val world = context.plugin.server.getWorld(city.worldId) ?: throw CivsRejection("world-unloaded")
        val control = building.control ?: throw CivsRejection("stale-worksite")
        requireCivs(world.isChunkLoaded(control.x shr 4, control.z shr 4) &&
            world.isChunkLoaded(work.standing.x shr 4, work.standing.z shr 4), "chunks-unloaded")
        requireCivs(mineFallbackStanding(city, worker, building, world, work.standing), "stale-worksite")
    }

    private fun validateMineReceiptBlocks(city: Settlement, work: CivWorldWork) {
        val building = city.building(work.buildingId)?.takeIf { it.type == BuildingType.QUARRY }
            ?: throw CivsRejection("building-not-found")
        val world = context.plugin.server.getWorld(city.worldId) ?: throw CivsRejection("world-unloaded")
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
            val block = context.read(city, building, world, point) ?: throw CivsRejection("protected-area")
            val expected = plan.expectedAfter(index, building.rotation)
            val soundBacking = step.shaftSupport && expected == "minecraft:cobblestone" &&
                block.type.isOccluding && !block.type.hasGravity() && block.type !in UNBREAKABLE
            requireCivs(block.blockData.asString == expected || soundBacking, "stale-worksite")
            requireCivs(context.worldAccess.canModify(city.owner, block, Bukkit.createBlockData(expected).material), "lands-denied")
            requireCivs(mineMutationAllowed(city, building, world, change(block, expected), index,
                planVersion = plan.version) == true, "protected-area")
        }
        val ordinal = work.miningOrdinal ?: work.focus?.let { focus ->
            plan.steps.indexOfFirst { it.point == CivsMinePlan.localPoint(building, focus) }.takeIf { it >= 0 }
        } ?: 0
        val clearingLava = CivsMineSafety.clearingLava(work)
        CivsMineSafety.adjacentLavaFrontier(work)?.let { focus ->
            val local = requireNotNull(work.miningFrontier)
            val step = plan.steps.getOrNull(ordinal) ?: throw CivsRejection("stale-worksite")
            val access = requireNotNull(work.miningAccessStep)
            val sections = work.miningAccessSections.orEmpty()
            val section = plan.section(ordinal)
            requireCivs(step.point == local && step.after == null &&
                focus == CivsWorksiteLayouts.point(building, local) &&
                plan.sectionReady(section, access, sections) &&
                plan.sections.getValue(section).getOrNull(plan.sectionCount(section, access, sections).toInt()) == ordinal,
                "stale-worksite")
            val frontier = context.read(city, building, world, focus) ?: throw CivsRejection("protected-area")
            requireCivs(frontier.blockData.asString == work.miningFrontierBefore, "stale-worksite")
            requireCivs(mineMutationAllowed(city, building, world, change(frontier, "minecraft:air"), ordinal,
                planVersion = plan.version) == true, "protected-area")
            requireCivs(approaches.workStandingAllowed(city, building, world, work, focus) && approaches.workAccessible(city, building, world, work) == true,
                "stale-worksite")
        }
        pending.forEach { cell ->
            val block = context.read(city, building, world, cell.position, reserved = reserved) ?: throw CivsRejection("protected-area")
            requireCivs(context.worldAccess.canModify(city.owner, block, Bukkit.createBlockData(cell.after).material), "lands-denied")
            val sealing = work.status == "securing-mine"
            if (sealing) {
                val origins = work.miningFrontier?.let(::listOf) ?: listOfNotNull(
                    plan.steps.getOrNull((work.miningAccessStep ?: work.miningStep ?: 0).toInt())?.point,
                    plan.steps.getOrNull((work.miningAccessStep ?: work.miningStep ?: 0).toInt() - 1)?.point)
                val local = CivsMinePlan.localPoint(building, cell.position)
                requireCivs(origins.any { origin -> clearingLava && local == origin && local in plan.positions ||
                    mineFrontier(city, building, world, plan, origin,
                    work.miningExcavation?.let(::setOf).orEmpty(), accessStep = work.miningAccessStep ?: work.miningStep ?: 0,
                    reserved = reserved, sectionSteps = work.miningAccessSections.orEmpty())?.any {
                        it.block == local && (it.material?.createBlockData()?.asString == cell.after ||
                            clearingLava && it.material == null && local in plan.positions)
                    } == true }, "stale-worksite")
            }
            requireCivs(mineMutationAllowed(city, building, world, cell, ordinal, generator = work.miningGenerator,
                sealing = sealing, planVersion = plan.version, clearingLava = clearingLava) == true, "protected-area")
        }
        val oldFrontier = validateLandingClearanceFrontier(city, building, world, plan, work, ordinal)
        // Removing liquid opens no new rock face; adjacent flows may drain after this exact source is removed.
        if (clearingLava) return
        val excavations = pending.filter { Bukkit.createBlockData(it.after).material.isAir }
            .mapTo(hashSetOf()) { CivsMinePlan.localPoint(building, it.position) }
        if (excavations.isEmpty()) return
        val around = excavations.singleOrNull() ?: throw CivsRejection("stale-worksite")
        val frontier = mineFrontier(city, building, world, plan, around, excavations,
            accessStep = work.miningAccessStep ?: work.miningStep ?: 0, reserved = reserved,
            sectionSteps = work.miningAccessSections.orEmpty()) ?: throw CivsRejection("protected-area")
        requireCivs((oldFrontier + frontier).distinct().size <= 54, "stale-worksite")
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

    /** Only the two body cuts of this exact landing column can unlock an occluded AIR seal. */
    private fun pendingLandingClearance(plan: CivsMinePlan, exposed: CivPosition, access: Long,
        sections: Map<String, Long>): String? {
        val floor = plan.floors.firstOrNull { exposed.y in it.feetY..it.feetY + 1 } ?: return null
        val section = "${floor.index}/shaft/landing"
        if (section !in plan.sections || !plan.sectionReady(section, access, sections)) return null
        val bodyCuts = (0..1).map { height ->
            plan.cutOrdinals[exposed.copy(y = floor.feetY + height)].orEmpty()
                .firstOrNull { plan.section(it) == section } ?: return null
        }
        return section.takeIf { bodyCuts.any { !plan.completed(it, access, sections) } }
    }

    /** The old opening remains a receipt fence while its own body clearance makes the seal reachable. */
    private fun validateLandingClearanceFrontier(city: Settlement, building: CivBuilding, world: World,
        plan: CivsMinePlan, work: CivWorldWork, ordinal: Int): List<MineProtection> {
        val previous = city.minePlans?.get(building.id.toString())?.excavation ?: return emptyList()
        if (work.status != "digging-mine" || work.miningExcavation != previous ||
            plan.steps[ordinal].stage != "shaft" || plan.steps[ordinal].branch != "landing") return emptyList()
        val floor = plan.floors.firstOrNull { it.index == plan.steps[ordinal].floor } ?: return emptyList()
        if (previous.y !in floor.feetY..floor.feetY + 1) return emptyList()
        val access = work.miningAccessStep ?: work.miningStep ?: 0
        val sections = work.miningAccessSections.orEmpty()
        val tasks = mineFrontier(city, building, world, plan, previous, emptySet(),
            accessStep = access, sectionSteps = sections) ?: throw CivsRejection("protected-area")
        requireCivs(tasks.size <= 54, "stale-worksite")
        for (task in tasks) {
            if (task.transferFooting) continue
            val opening = observeMineHazard(world, CivsWorksiteLayouts.point(building, task.exposed))
                ?: throw CivsRejection("chunks-unloaded")
            if (!mineExposed(task, emptySet(), opening)) continue
            val block = observeMineHazard(world, CivsWorksiteLayouts.point(building, task.block))
                ?: throw CivsRejection("chunks-unloaded")
            val dependent = task.material == Material.COBBLESTONE &&
                pendingLandingClearance(plan, task.exposed, access, sections) == plan.section(ordinal)
            if (dependent) requireCivs(!block.isLiquid && (block.type.isAir || block.type == Material.COBBLESTONE ||
                CivsMineSafety.natural(block.type) || mineOwnedBlock(city, building, task.block, block.blockData.asString)), "stale-worksite")
            requireCivs(!mineProtectionNeeded(block, task.material) || dependent && block.type.isAir, "stale-worksite")
        }
        return tasks
    }

    fun mine(city: Settlement, worker: CivWorker, building: CivBuilding, world: World): CivWorldWork? {
        val result = mineNext(city, worker, building, world)
        if (result == null && !context.spotSearchPending && context.currentNeed(city, worker)?.status?.let {
                it != "searching-work" && !it.startsWith("waiting-") } == true) {
            mineExecutions[building.id]?.let { execution -> execution.selected?.let { section ->
                execution.lastDenial = context.currentNeed(city, worker)
                execution.lastDeniedSection = section
                val denial = requireNotNull(execution.lastDenial)
                val invisibleLoadedLava = denial.reason == "line-of-sight-blocked" && denial.blockMaterial == "LAVA" &&
                    mineBounds[building.id]?.let { bounds ->
                        ((bounds.min.x shr 4)..(bounds.max.x shr 4)).all { x ->
                            ((bounds.min.z shr 4)..(bounds.max.z shr 4)).all { z -> world.isChunkLoaded(x, z) }
                        }
                    } == true
                if (denial.reason in setOf("mine-thin-cover", "liquid", "adjacent-liquid", "mine-foreign-block") || invisibleLoadedLava) {
                    // Native safety cannot improve with time. World/protection changes and explicit invalidation reopen it.
                    execution.blockedSections[section] = denial
                } else execution.retryAt[section] = Bukkit.getCurrentTick().toLong() + 100
                execution.selected = null
            } }
        }
        val execution = mineExecutions[building.id]
        if (result != null) { execution?.discoveryAdmissions = 0; return result }
        if (execution != null && CivsMineFallback.eligible(city, building, worker)) {
            execution.discoveryAdmissions++
            val denial = context.currentNeed(city, worker)
            val geometry = requireNotNull(CivsMinePlan.forBuilding(building))
            val ready = geometry.sections.keys.filter { section ->
                geometry.sectionCount(section, execution.prefix, execution.sections) < geometry.sections.getValue(section).size &&
                    geometry.sectionReady(section, execution.prefix, execution.sections)
            }
            val alternativesRefused = ready.all { it in execution.blockedSections ||
                execution.retryAt.getOrDefault(it, 0) > Bukkit.getCurrentTick() }
            val refused = !context.spotSearchPending && denial?.status in setOf("worksite-protected", "worksite-liquid",
                "worksite-no-access", "worksite-obstacle", "worksite-unbreakable") && alternativesRefused
            if (refused || execution.discoveryAdmissions >= CivsMineFallback.DISCOVERY_ADMISSIONS) {
                val standing = citizenStorageTarget(city, building, world) {
                    mineFallbackStanding(city, worker, building, world, it)
                } ?: return null
                val roll = java.util.concurrent.ThreadLocalRandom.current().nextInt(100)
                val output = CivsWorksites.generatorOutput(roll, roll != 99 || Bukkit.createBlockData("minecraft:iron_ore").isPreferredTool(tool(worker)))
                execution.discoveryAdmissions = 0
                return work(worker, building, CivWorkApproach(standing), emptyList(), outputs = mapOf(output to 1L),
                    status = "digging-mine", duration = CivsMineFallback.DURATION_MILLIS, toolUses = 1)
            }
        }
        return null
    }

    /** The reserve action uses the real owned workplace and the ordinary observed six-second session. */
    fun mineFallbackStanding(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        point: CivPosition): Boolean {
        val control = building.control ?: return false
        if (!world.isChunkLoaded(control.x shr 4, control.z shr 4) || context.locked(city.worldId, control) ||
            !citizenStorageReady(world, control) || !context.worldAccess.canMine(world.getBlockAt(control.x, control.y, control.z)) ||
            !context.worldAccess.canModify(city.owner, world.getBlockAt(control.x, control.y, control.z)) ||
            kotlin.math.abs(point.x.toLong() - control.x) + kotlin.math.abs(point.z.toLong() - control.z) != 1L ||
            kotlin.math.abs(point.y.toLong() - control.y) > 1L || !citizenControlClear(city, point)) return false
        if (context.read(city, building, world, point, worker = worker) == null ||
            context.read(city, building, world, point.copy(y = point.y + 1), worker = worker) == null ||
            context.read(city, building, world, point.copy(y = point.y - 1), worker = worker) == null) return false
        return context.worldAccess.canMine(world.getBlockAt(point.x, point.y, point.z)) && citizenStandable(world, point) &&
            citizenPacketBodyClear(world, CitizenPoint(point.x + .5, point.y.toDouble(), point.z + .5), false) &&
            citizenCanReachBlock(world, point.location(world).add(.5, 1.62, .5), control)
    }

    private fun mineNext(city: Settlement, worker: CivWorker, building: CivBuilding, world: World): CivWorldWork? {
        val autonomous = CivsAutonomousWorksites.supports(city, building)
        if (!autonomous && CivsEquipment.slots(worker)[4]?.material?.endsWith("_PICKAXE") != true) return null
        val geometry = CivsMinePlan.forBuilding(building)
            ?: return context.blocked(worker, "worksite-layout", point = building.anchor, reason = "mine-layout-missing")
        // A seal's hazard observation can use the last native unit in a slice.
        // Resume its cover phase first, so even a one-unit budget makes progress.
        mineCovers[worker.id]?.takeIf { cover -> cover.historicalAuthority != null &&
            cover.historicalAuthority != city.minePlans?.get(building.id.toString())?.levels.orEmpty() }
            ?.let { mineCovers.remove(worker.id) }
        mineCovers[worker.id]?.takeIf { it.world == city.worldId && it.building == building.id && !it.finished }?.let { cover ->
            if (!world.isChunkLoaded(cover.x shr 4, cover.z shr 4))
                return context.blocked(worker, "worksite-unloaded", point = CivPosition(cover.x, cover.next, cover.z))
            while (!cover.finished && context.mineChecksRemaining > 0 && context.nanoTime() < context.discoveryBudget.deadline) {
                context.mineChecksRemaining--
                stepMineCover(world, cover)
            }
            if (!cover.finished || context.mineChecksRemaining == 0) {
                context.spotSearchPending = true
                return context.blocked(worker, "searching-work")
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
            return context.blocked(worker, "worksite-layout", point = building.anchor, reason = "mine-progress-invalid")
        val v2Step = state?.takeIf { it.version == 2 }?.nextStep ?: state?.v2Step ?: 0L
        val v2Plan = CivsMinePlan.forBuilding(building, 2)
        val execution = mineExecutions[building.id]?.takeIf { it.building == building && it.saved == state &&
            it.legacy == city.mineProgress?.get(building.id.toString()) } ?: run {
            val previous = mineExecutions[building.id]
            val v1 = geometry.legacyCompleted(city.mineProgress?.get(building.id.toString()) ?: 0L)
                ?: return context.blocked(worker, "worksite-layout", point = building.anchor, reason = "legacy-mine-progress-invalid")
            val v2 = v2Plan?.completed(v2Step)
                ?: return context.blocked(worker, "worksite-layout", point = building.anchor, reason = "v2-mine-progress-invalid")
            val v3Step = state?.takeIf { it.version == 3 }?.nextStep ?: state?.v3Step ?: 0L
            val v3 = CivsMinePlan.forBuilding(building, 3)?.completed(v3Step)
                ?: return context.blocked(worker, "worksite-layout", point = building.anchor, reason = "v3-mine-progress-invalid")
            val v4Step = state?.takeIf { it.version == 4 }?.nextStep ?: state?.v4Step ?: 0L
            val v4 = CivsMinePlan.forBuilding(building, 4)?.completed(v4Step)
                ?: return context.blocked(worker, "worksite-layout", point = building.anchor, reason = "v4-mine-progress-invalid")
            val v5 = CivsMineProgress.v5State(state)?.let { old ->
                requireNotNull(CivsMinePlan.forBuilding(building, 5)).let { plan -> plan.steps.withIndex()
                    .filter { plan.completed(it.index, old.nextStep, old.sectionSteps.orEmpty()) }
                    .associate { it.value.point to it.value.after } }
            }.orEmpty()
            val v6 = CivsMineProgress.v6State(state)?.let { old ->
                requireNotNull(CivsMinePlan.forBuilding(building, 6)).let { plan -> plan.steps.withIndex()
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
                lastDeniedSection = previous?.lastDeniedSection, legacyBlocks = v1 + v2 + v3 + v4 + v5 + v6)
                .also { mineExecutions[building.id] = it }
        }
        val legacy = execution.legacyBlocks
        val sectionKeys = geometry.sections.keys.toList()
        fun nextOrdinal(section: String): Int? = geometry.sections.getValue(section)
            .getOrNull(geometry.sectionCount(section, execution.prefix, execution.sections).toInt())
        if (nextOrdinal("spine") != null) execution.selected = "spine"
        if (execution.selected?.let { nextOrdinal(it) == null || it in execution.blockedSections ||
                execution.retryAt.getOrDefault(it, 0) > Bukkit.getCurrentTick() } == true) execution.selected = null
        if (execution.selected == null) {
            for (offset in sectionKeys.indices) {
                val index = Math.floorMod(execution.cursor + offset, sectionKeys.size)
                val key = sectionKeys[index]
                if (nextOrdinal(key) == null || key in execution.blockedSections ||
                    execution.retryAt.getOrDefault(key, 0) > Bukkit.getCurrentTick() ||
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
                if (protection != null || context.spotSearchPending || context.currentNeed(city, worker)?.status != "searching-work") return protection
                // Keep the oldest opening until its actual AIR shell can be reached.
                // Lower ladder clearance must not replace this durable repair target.
                execution.unsealedExcavation = it.point.takeIf { mineSafety[worker.id]?.deferredAirWalls?.isNotEmpty() == true }
                val scan = mineSafety[worker.id]
                val clearance = scan?.let { old -> old.frontier.asSequence().filter { task -> task.block in old.deferredAirWalls }
                    .mapNotNull { task -> pendingLandingClearance(geometry, task.exposed, access = execution.prefix,
                        sections = execution.sections) }.firstOrNull() }
                if (clearance != null && execution.selected != clearance) {
                    // Resume the named dependency on the next bounded pulse, before any unrelated section.
                    execution.selected = clearance
                    context.spotSearchPending = true
                    return context.blocked(worker, "searching-work")
                }
            }
            // Keep a completed previous check across bounded pulses. A receipt's
            // excavation invalidate clears it even when progress did not move.
            execution.checkedExcavation = true
            mineFrontierDirty.remove(worker.id)
        }
        if (scanStart == steps.size) return if (execution.prefix == steps.size.toLong())
            generateStone(city, worker, building, world, geometry, execution.prefix) else {
                val denial = execution.lastDenial
                context.blocked(worker, denial?.status ?: "worksite-protected", point = denial?.point,
                    reason = denial?.reason ?: "mine-sections-blocked", blockMaterial = denial?.blockMaterial)
            }
        val indices = geometry.sections.getValue(requireNotNull(selected))
        val startOffset = geometry.sectionCount(selected, execution.prefix, execution.sections).toInt()
        for (offset in startOffset until minOf(indices.size, startOffset + context.discoveryBudget.columns)) {
            if (context.nanoTime() >= context.discoveryBudget.deadline) return context.blocked(worker, "searching-work")
            context.discoveryBudget.columns--
            val ordinal = indices[offset]
            execution.ordinal = ordinal
            mineCursors[building.id] = Triple(building.level, progress, ordinal)
            val step = steps[ordinal]
            val local = step.point
            val after = step.after
            val point = CivsWorksiteLayouts.point(building, local)
            val block = context.read(city, building, world, point, worker = worker) ?: return null
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
                if (protection != null || context.spotSearchPending || context.currentNeed(city, worker)?.status != "searching-work") return protection
                execution.sections[selected] = offset.toLong() + 1
                execution.prefix = geometry.normalizedProgress(execution.basePrefix, execution.sections).first
                // Record skipped already-dug cells with the next actual work, without a save per empty cell.
                continue
            }
            // A legacy completed cell that now contains a new solid object is not a
            // license to remodel it. Gravity falls and missing supports use real repairs.
            val previousMaterial = legacy[local]?.let { Bukkit.createBlockData(it).material }
            if (legacy.containsKey(local) && !block.type.isAir && !block.type.hasGravity() && block.type != Material.TORCH && block.type != Material.LAVA &&
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
                    return context.blocked(worker, "worksite-obstacle", point = point, reason = "legacy-cell-changed", blockMaterial = block.type.name)
            }
            if (block.type == Material.LAVA) return clearMineLava(city, worker, building, world, geometry, step, block)
            if (block.isLiquid) return context.blocked(worker, "worksite-liquid", point = point,
                reason = "liquid", blockMaterial = block.type.name)
            if (block.type in UNBREAKABLE) return context.blocked(worker, "worksite-unbreakable", point = point,
                reason = "unbreakable", blockMaterial = block.type.name)
            // Identify the actual physical clearance before checking its frontier. A
            // fallen block or central shaft prerequisite is not the paid support step.
            var prerequisite: Block? = null
            if (!shaftSupport && target?.material == Material.COBBLESTONE) {
                for (height in 1..3) {
                    val fallen = context.read(city, building, world, point.copy(y = point.y + height), worker = worker) ?: return null
                    if (fallen.type.hasGravity()) { prerequisite = fallen; break }
                }
            }
            if (shaftSupport) {
                val shaftPoint = CivsWorksiteLayouts.point(building, geometry.ladderForBacking(local))
                val shaft = context.read(city, building, world, shaftPoint, worker = worker) ?: return null
                if (shaft.isLiquid) return context.blocked(worker, "worksite-liquid", point = shaftPoint,
                    reason = "liquid", blockMaterial = shaft.type.name)
                if (shaft.type in UNBREAKABLE) return context.blocked(worker, "worksite-unbreakable", point = shaftPoint,
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
            if (protection != null || context.spotSearchPending || context.currentNeed(city, worker)?.status != "searching-work") return protection
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
        val block = context.read(city, building, world, point, worker = worker) ?: return null
        if (block.type != Material.COBBLESTONE && block.type != Material.STONE) return context.blocked(worker, "worksite-obstacle", point = point,
            reason = "generator-face-changed", blockMaterial = block.type.name)
        val nextFace = if (block.type == Material.COBBLESTONE) "minecraft:stone" else "minecraft:cobblestone"
        if (mineMutationAllowed(city, building, world, change(block, nextFace), geometry.steps.lastIndex,
                worker, budgeted = true, generator = true) != true) return null
        val localStanding = requireNotNull(geometry.generatorStanding)
        val support = localStanding.copy(y = localStanding.y - 1)
        val protection = secureMine(city, worker, building, world, geometry,
            CivsMinePlan.Step(support, "minecraft:cobblestone", "gallery", geometry.floors.last().index, "generator"),
            progress, emptySet())
        if (protection != null || context.spotSearchPending || context.currentNeed(city, worker)?.status != "searching-work") return protection
        val floor = geometry.floors.last()
        for (torch in floor.torches.filter { kotlin.math.abs(it.x - localStanding.x) + kotlin.math.abs(it.z - localStanding.z) == 1 }) {
            val light = context.read(city, building, world, CivsWorksiteLayouts.point(building, torch), worker = worker) ?: return null
            if (light.type == Material.TORCH) continue
            if (!light.type.isAir) return context.blocked(worker, "worksite-obstacle", point = position(light),
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
            val output = CivsWorksites.generatorOutput(roll, roll != 99 || Bukkit.createBlockData("minecraft:iron_ore").isPreferredTool(tool(worker)))
            mapOf(output to 1L)
        }
        // This permanent face is worked physically; the existing native receipt
        // atomically captures one item, one tool use and the completed work cycle.
        val work = work(worker, building, standing, listOf(change(block, nextFace)),
            outputs = outputs, status = "digging-mine", duration = 6_000, miningStep = geometry.steps.size.toLong(),
            miningGenerator = true)
        when (approaches.workAccessible(city, building, world, work, budgeted = true)) {
            null -> return context.blocked(worker, "searching-work")
            false -> return context.blocked(worker, "worksite-no-access", point = point,
                reason = "generator-no-supported-position", blockMaterial = block.type.name)
            true -> Unit
        }
        return work
    }

    /** Fixed-size shaft/access frontier; native checks are still shared across bounded pulses. */
    fun mineFrontier(city: Settlement, building: CivBuilding, world: World,
        geometry: CivsMinePlan, around: CivPosition, excavations: Set<CivPosition>,
        worker: CivWorker? = null, accessStep: Long, reserved: Set<CivPosition> = emptySet(),
        sectionSteps: Map<String, Long> = emptyMap()): List<MineProtection>? {
        val sides = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        val effect = excavations.singleOrNull() ?: around
        val connected = hashSetOf<CivPosition>()
        val landings = listOfNotNull(geometry.entranceLanding) + geometry.floors
        // Deep access is a narrow ladder until its spine is paid; enclose that passage locally.
        // The frozen V5 rows retain their wider frontier, including the upper boundary row.
        val spineCount = geometry.sectionCount("spine", accessStep, sectionSteps)
        val oldLowest = geometry.takeIf { it.version >= 6 && spineCount < it.sections.getValue("spine").size }?.let {
            // An interrupted V6 spine retains its own narrow frontier while V7 finishes it.
            val predecessor = if (it.version == 7 && spineCount < requireNotNull(it.forVersion(6)).sections.getValue("spine").size)
                5 else it.version - 1
            requireNotNull(it.forVersion(predecessor)).lowest
        }
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
                        context.blocked(worker, "worksite-unloaded", point = observed, reason = "hazard-neighbour-unavailable")
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
                            val landingOpening = landings.any { floor -> floor.cells.any {
                                    it.x == wall.x && it.z == wall.z && wall.y in it.y + 1..it.y + floor.height
                                } }
                            if (landingOpening && (geometry.builtStanding(wall, accessStep, sectionSteps) ||
                                    geometry.builtStanding(wall.copy(y = wall.y - 1), accessStep, sectionSteps))) continue
                            if (landingOpening && geometry.version == 7 &&
                                (CivsMineProgress.priorStanding(building, city.minePlans?.get(building.id.toString()),
                                    CivsWorksiteLayouts.point(building, wall)) ||
                                    CivsMineProgress.priorStanding(building, city.minePlans?.get(building.id.toString()),
                                        CivsWorksiteLayouts.point(building, wall.copy(y = wall.y - 1))))) {
                                // Retain a paid predecessor opening; native liquid still blocks, never becomes an inner seal.
                                add(MineProtection(CivPosition(x, y, z), wall, null))
                                continue
                            }
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

    fun mineExposed(task: MineProtection, excavations: Set<CivPosition>, opening: Block): Boolean {
        val aboutToOpen = task.exposed in excavations && task.block != task.exposed
        // Even a one-block opening can admit liquid; it need not fit a resident.
        return aboutToOpen || opening.type.isAir || opening.type == Material.LADDER || opening.type == Material.TORCH
    }

    fun mineProtectionNeeded(block: Block, material: Material?): Boolean {
        if (material == null) return block.isLiquid
        val safe = if (material == Material.TORCH) block.type == Material.TORCH
            else block.type.isOccluding && !block.type.hasGravity()
        return !safe && (block.type.isAir || block.isLiquid || block.type in WEEDS)
    }

    private fun clearMineLava(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        geometry: CivsMinePlan, step: CivsMinePlan.Step, block: Block): CivWorldWork? {
        val point = position(block)
        if (block.type != Material.LAVA || CivsMinePlan.localPoint(building, point) !in geometry.positions)
            return context.blocked(worker, "worksite-liquid", point = point, reason = "adjacent-liquid", blockMaterial = block.type.name)
        val cell = change(block, "minecraft:air")
        val ordinal = mineExecutions[building.id]?.ordinal ?: return null
        if (mineMutationAllowed(city, building, world, cell, ordinal, worker, budgeted = true, clearingLava = true) != true) return null
        val progress = city.minePlans?.get(building.id.toString())?.takeIf { it.version == geometry.version }?.nextStep ?: 0
        val frontierPoint = CivsWorksiteLayouts.point(building, step.point)
        val adjacent = kotlin.math.abs(point.x.toLong() - frontierPoint.x) + kotlin.math.abs(point.y.toLong() - frontierPoint.y) +
            kotlin.math.abs(point.z.toLong() - frontierPoint.z) == 1L &&
            geometry.steps[ordinal].point == step.point && geometry.steps[ordinal].after == null
        val frontier = if (adjacent) context.read(city, building, world, frontierPoint, worker = worker) ?: return null else null
        val frontierBefore = frontier?.takeIf { CivsMineSafety.natural(it.type) && it.type.isOccluding && !it.type.hasGravity() }?.blockData?.asString
        if (frontierBefore != null && mineMutationAllowed(city, building, world, change(requireNotNull(frontier), "minecraft:air"),
                ordinal, worker, budgeted = true) != true) return null
        val focus = if (frontierBefore != null) frontierPoint else point
        val standing = beside(city, worker, building, world, focus, ladder = true, miningStep = progress,
            preferred = step.approach?.let { CivsWorksiteLayouts.point(building, it) }, allowRemote = frontierBefore == null) ?: return null
        return work(worker, building, standing, listOf(cell), status = "securing-mine", duration = 2_000, miningStep = progress, focus = focus)
            .copy(equipmentAfter = null, miningFrontier = step.point, miningExcavation = step.point, miningFrontierBefore = frontierBefore)
    }

    /** Repair only this frontier. Earlier completed columns never enter another branch's work. */
    private fun secureMine(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        geometry: CivsMinePlan, step: CivsMinePlan.Step, progress: Long, excavations: Set<CivPosition>,
        effectiveProgress: Long = progress): CivWorldWork? {
        val around = step.point
        val execution = mineExecutions[building.id]
        val access = execution?.prefix ?: effectiveProgress
        val sections = execution?.sections?.toMap().orEmpty()
        // Resume before recapturing the component: that bounded preamble can itself spend a slice.
        // A completed frontier survives only while this exact current action's approach is pending.
        val scan = mineSafety[worker.id]?.takeIf { previous ->
            previous.building == building && previous.target == around && previous.progress == progress &&
                excavations.size <= 1 && previous.excavation == excavations.singleOrNull() &&
                previous.accessProgress == access && previous.sections == sections &&
                (previous.next < previous.frontier.size || execution?.ordinal?.toLong() == progress &&
                    geometry.steps.getOrNull(progress.toInt()) == step &&
                    approaches.workSpots[worker.id]?.let { search ->
                        search.buildingId == building.id && search.ladder && search.allowOwn &&
                            search.target == CivsWorksiteLayouts.point(building, previous.excavation ?: previous.target)
                    } == true)
        } ?: run {
            val tasks = mineFrontier(city, building, world, geometry, around, excavations, worker, access,
                sectionSteps = sections) ?: return null
            MineSafetyScan(building, around, progress, excavations.singleOrNull(), access, tasks, sections)
                .also { mineSafety[worker.id] = it }
        }
        val tasks = scan.frontier
        repeat(context.mineChecksRemaining) {
            val task = tasks.getOrNull(scan.next) ?: return null
            if (context.nanoTime() >= context.discoveryBudget.deadline) {
                context.spotSearchPending = true
                return context.blocked(worker, "searching-work")
            }
            context.mineChecksRemaining--
            val exposed = CivsWorksiteLayouts.point(building, task.exposed)
            val opening = observeMineHazard(world, exposed) ?: return context.blocked(worker, "worksite-unloaded", point = exposed)
            if (!mineExposed(task, excavations, opening)) { scan.next++; return@repeat }
            val point = CivsWorksiteLayouts.point(building, task.block)
            val block = observeMineHazard(world, point) ?: run {
                if (!task.transferFooting) return null
                scan.next++
                context.blocked(worker, "searching-work")
                return@repeat
            }
            if (task.transferFooting && !block.type.isAir) { scan.next++; return@repeat }
            if (!mineProtectionNeeded(block, task.material)) { scan.next++; return@repeat }
            if (task.material == null) {
                if (block.type == Material.LAVA) return clearMineLava(city, worker, building, world, geometry, step, block)
                return context.blocked(worker, "worksite-liquid", point = point, reason = "adjacent-liquid", blockMaterial = block.type.name)
            }
            if (context.read(city, building, world, point, worker = worker) == null) return null
            if (!CivsAutonomousWorksites.supports(city, building) && CivsEquipment.plainCount(worker, task.material.name) == 0)
                return prepareQuarry(city, worker, building, world)
            if (mineMutationAllowed(city, building, world, change(block, task.material.createBlockData().asString),
                    execution?.ordinal ?: progress.toInt(), worker, budgeted = true, sealing = true) != true) return null
            val miningStep = city.minePlans?.get(building.id.toString())?.takeIf { it.version == CivsMinePlan.VERSION }?.nextStep ?: 0L
            val standing = beside(city, worker, building, world, point, ladder = true, miningStep = miningStep,
                miningAccessStep = effectiveProgress,
                preferred = step.approach?.let { CivsWorksiteLayouts.point(building, it) }) ?: run {
                if (!context.spotSearchPending && task.transferFooting && context.currentNeed(city, worker)?.status == "worksite-no-access") {
                    scan.next++
                    context.blocked(worker, "searching-work")
                    return@repeat
                }
                // A solid current cut can hide an AIR shell from every native ray.
                // Finish that single cut, then the durable previous-step check repairs
                // its frontier before another cut, including after a restart.
                val failure = context.currentNeed(city, worker)
                val hiddenByCurrentCut = task.exposed in excavations && task.block != task.exposed &&
                    opening.type.isOccluding && !opening.type.hasGravity() && failure?.reason == "line-of-sight-blocked"
                // A one-high shaft cut can also expose a far corner before its ordered lower row.
                val lowerRowEnd = geometry.lowerClearanceEnd(task.exposed)
                if (!context.spotSearchPending && failure?.status == "worksite-no-access" &&
                    task.material == Material.COBBLESTONE && block.type.isAir &&
                    (hiddenByCurrentCut || lowerRowEnd > 0 && !geometry.completed(lowerRowEnd - 1, access, sections) ||
                        pendingLandingClearance(geometry, task.exposed, access, sections) != null)) {
                    scan.deferredAirWalls += task.block
                    scan.next++
                    context.blocked(worker, "searching-work")
                    return@repeat
                }
                return null
            }
            return work(worker, building, standing, listOf(change(block, task.material.createBlockData().asString)),
                inputs = mapOf(task.material.name to 1L), status = "securing-mine", duration = 2_000,
                miningStep = miningStep, miningAccessStep = effectiveProgress)
        }
        if (scan.next < tasks.size) { context.spotSearchPending = true; context.blocked(worker, "searching-work") }
        return null
    }

    /** Hazard probes never authorize changing their neighbour (which may belong to another building). */
    fun observeMineHazard(world: World, point: CivPosition): Block? =
        if (point.y in world.minHeight until world.maxHeight && world.isChunkLoaded(point.x shr 4, point.z shr 4))
            world.getBlockAt(point.x, point.y, point.z) else null

    fun readMineProtection(city: Settlement, building: CivBuilding, world: World, task: MineProtection,
        worker: CivWorker? = null): Block? {
        val point = CivsWorksiteLayouts.point(building, task.block)
        // This is observation, including an existing solid shell in another
        // claim. secureMine/read and the saved receipt separately guard writes.
        return observeMineHazard(world, point) ?: run {
            if (worker != null) context.blocked(worker, "worksite-unloaded", point = point, reason = "hazard-neighbour-unavailable")
            null
        }
    }

    private fun beside(city: Settlement, worker: CivWorker, building: CivBuilding, world: World, target: CivPosition,
        allowOwn: Boolean = true, ladder: Boolean = false, miningStep: Long? = null,
        preferred: CivPosition? = null, miningAccessStep: Long? = miningStep,
        capture: (() -> CivWorldWork?)? = null, allowRemote: Boolean = true): CivWorkApproach? =
        approaches.beside(city, worker, building, world, target, allowOwn, ladder, miningStep, preferred,
            miningAccessStep, capture, allowRemote, mineExecutions[building.id])

    private fun work(worker: CivWorker, building: CivBuilding, approach: CivWorkApproach, changes: List<CivPlanBlock>,
        inputs: Map<String, Long> = emptyMap(), outputs: Map<String, Long> = emptyMap(), status: String,
        duration: Long = 3_000, miningStep: Long? = null, focus: CivPosition? = null, miningGenerator: Boolean = false,
        miningAccessStep: Long? = miningStep, toolUses: Int? = null): CivWorldWork =
        context.work(worker, building, approach, changes, inputs, outputs, status, duration, miningStep, focus,
            miningGenerator, miningAccessStep, toolUses, mineExecutions[building.id], mineSafety[worker.id])

    private fun drops(block: Block, tool: ItemStack): Map<String, Long> = block.getDrops(tool).filter { !it.type.isAir }
        .groupingBy { it.type.name }.fold(0L) { count, item -> count + item.amount }
    private fun tool(worker: CivWorker): ItemStack = CivsEquipment.slots(worker)[4]?.stack() ?: ItemStack(Material.AIR)
    private fun change(block: Block, after: String) = CivPlanBlock(position(block), block.blockData.asString, after)
    private fun position(block: Block) = CivPosition(block.x, block.y, block.z)

    companion object {
        private val WEEDS = setOf(Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN)
        private val UNBREAKABLE = setOf(Material.BEDROCK, Material.BARRIER, Material.END_PORTAL_FRAME, Material.END_PORTAL, Material.NETHER_PORTAL)
    }
}
