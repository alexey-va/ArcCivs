package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.inventory.ItemStack
import java.util.UUID
import kotlin.math.roundToInt

/** Owns the retained zone, pending tree/plant observations and captured native forestry batches. */
internal class CivsForestryWorksite(
    private val budget: CivWorkDiscoveryBudget,
    private val stock: (Settlement, UUID) -> Map<String, Long>,
    private val forestryPaths: (Settlement) -> Set<Pair<Int, Int>>,
    private val residentFeet: (UUID) -> Location?,
    private val readBlock: (Settlement, CivBuilding, World, CivPosition, Boolean) -> Block?,
    private val approach: (Settlement, CivWorker, CivBuilding, World, CivPosition, (() -> CivWorldWork?)?) -> CivWorkAccess,
    private val makeWork: (CivWorker, CivBuilding, CivWorkApproach, CivWorkAction) -> CivWorldWork,
    private val recordNeed: (CivWorker, CivWorkNeed) -> CivWorldWork?,
    private val needStatus: (Settlement, CivWorker) -> String?,
    private val deferredRoots: (CivWorker, CivBuilding) -> Set<CivPosition>,
    private val refreshApproaches: (UUID, Location, Set<CivPosition>) -> Unit,
    private val deferApproach: (CivWorker, CivBuilding, CivPosition) -> Unit,
) {
    private var spotSearchPending = false
    private data class ForestScan(val building: CivBuilding, val scan: CivsForestry.Scan, val roots: List<CivPosition>)
    private val forestScans = hashMapOf<UUID, ForestScan>()
    private data class ForestPlant(val building: CivBuilding, val roots: List<CivPosition>, val timber: String, var next: Int = 0,
        val hints: List<CivPosition> = emptyList(), var hintNext: Int = 0) {
        fun reset() { next = 0; hintNext = 0 }
    }
    private val forestPlants = hashMapOf<UUID, ForestPlant>()
    private data class ForestRetry(val atTick: Long, val planting: Boolean)
    private data class ForestSearch(val building: CivBuilding, var saplings: Long, val columns: List<CivPosition>,
        var next: Int = 0, var y: Int? = null, var surface: Int? = null, var refresh: Int = 0,
        var column: CivPosition? = null, var scanningDirty: Boolean = false,
        val care: MutableSet<CivPosition> = linkedSetOf(), val planting: MutableSet<CivPosition> = linkedSetOf(),
        val mature: MutableSet<CivPosition> = linkedSetOf(), val unsuitable: MutableSet<CivPosition> = linkedSetOf(),
        val treeColumns: MutableSet<CivPosition> = linkedSetOf(),
        val unfunded: MutableMap<CivPosition, Pair<String, Long>> = linkedMapOf(), val unplantable: MutableSet<CivPosition> = linkedSetOf(),
        val dirty: MutableSet<CivPosition> = linkedSetOf(), val retryRoots: MutableMap<CivPosition, ForestRetry> = linkedMapOf(),
        var retryAt: Long? = null, var plantNext: Boolean = false, var residentOrigin: CivPosition? = null,
        var plantingRefusals: Long = 0, val plantingReasons: MutableMap<String, Long> = linkedMapOf(),
        var lastPlantingCandidate: CivPosition? = null, var lastPlantingPoint: CivPosition? = null,
        var lastPlantingReasons: List<String> = emptyList(), var lastPlantingNeighbour: String? = null,
        var availableSaplings: Map<String, Long> = emptyMap()) {
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
        fun refused(candidate: CivPosition, reasons: List<String>, point: CivPosition = candidate, neighbour: String? = null) {
            plantingRefusals++
            reasons.forEach { plantingReasons[it] = (plantingReasons[it] ?: 0) + 1 }
            lastPlantingCandidate = candidate; lastPlantingPoint = point
            lastPlantingReasons = reasons; lastPlantingNeighbour = neighbour
        }
    }
    private val forestSearches = hashMapOf<UUID, ForestSearch>()

    fun workerIds(): Set<UUID> = forestSearches.keys + forestScans.keys + forestPlants.keys
    fun building(workerId: UUID): CivBuilding? = forestSearches[workerId]?.building ?:
        forestScans[workerId]?.building ?: forestPlants[workerId]?.building
    fun containsChunk(workerId: UUID, chunk: Pair<Int, Int>): Boolean = chunk in forestSearches[workerId]?.chunks.orEmpty()
    fun hasPending(workerId: UUID): Boolean = forestScans[workerId] != null || forestPlants[workerId] != null
    fun scanRoot(workerId: UUID): CivPosition? = forestScans[workerId]?.scan?.root

    fun invalidate(workerId: UUID) { forestScans.remove(workerId); forestPlants.remove(workerId) }
    fun completed(workerId: UUID, work: CivWorldWork) {
        forestSearches[workerId]?.let { search ->
            search.plantNext = work.status != "planting-tree"
            work.forestry?.job?.roots?.forEach { search.mature.remove(it) }
        }
    }
    fun forget(workerId: UUID) { forestSearches.remove(workerId); invalidate(workerId) }
    fun retain(workerIds: Set<UUID>) {
        forestScans.keys.retainAll(workerIds); forestPlants.keys.retainAll(workerIds); forestSearches.keys.retainAll(workerIds)
    }
    fun approachReopened(workerId: UUID, focus: CivPosition) {
        forestSearches[workerId]?.let { search ->
            search.byColumn[focus.x to focus.z]?.let { search.dirty += it }
            search.retryAt = null
        }
    }
    fun changed(workerId: UUID, point: CivPosition) {
        forestSearches[workerId]?.let { search ->
            search.byColumn[point.x to point.z]?.let { search.dirty += it }
            search.treeColumns.removeIf { it.x == point.x && it.z == point.z }
            fun nearby(root: CivPosition) = kotlin.math.abs(root.x - point.x) <= CivsForestry.MAX_RADIUS &&
                kotlin.math.abs(root.z - point.z) <= CivsForestry.MAX_RADIUS &&
                point.y in root.y - 1..root.y + CivsForestry.MAX_HEIGHT
            search.unsuitable.removeIf(::nearby)
            search.retryRoots.keys.removeIf(::nearby)
            search.unplantable.removeIf(::nearby)
            search.unfunded.keys.removeIf(::nearby)
            forestScans[workerId]?.takeIf { nearby(it.scan.root) }?.let { forestScans.remove(workerId) }
            forestPlants[workerId]?.takeIf { it.roots.any(::nearby) }?.reset()
        }
    }
    fun protectionChanged(workerId: UUID, affected: (CivPosition, Int) -> Boolean) {
        forestSearches[workerId]?.let { search ->
            search.columns.filterTo(search.dirty) { affected(it, 0) }
            search.retryRoots.keys.removeIf { affected(it, CivsForestry.MAX_RADIUS) }
            listOf(search.unsuitable, search.unplantable, search.unfunded.keys).forEach { points ->
                points.removeIf { affected(it, CivsForestry.MAX_RADIUS) }
            }
            forestScans[workerId]?.takeIf { affected(it.scan.root, CivsForestry.MAX_RADIUS) }?.let { forestScans.remove(workerId) }
            forestPlants[workerId]?.takeIf { it.roots.any { root -> affected(root, CivsForestry.MAX_RADIUS) } }?.reset()
        }
    }

    /** Owner observations only: no native reads, budget debit or expiry. */
    fun debug(id: UUID): Map<String, Any?> {
        fun point(p: CivPosition?) = p?.let { "${it.x},${it.y},${it.z}" }
        return mapOf(
                "forestSearch" to forestSearches[id]?.let { mapOf("building" to it.building.id.toString(),
                    "column" to it.next, "columns" to it.columns.size, "refreshColumn" to it.refresh,
                    "dirtyColumns" to it.dirty.size, "mature" to it.mature.size,
                    "observedTreeColumns" to it.treeColumns.size,
                    "unsuitable" to it.unsuitable.size, "unplantable" to it.unplantable.size, "retryRoots" to it.retryRoots.size, "y" to it.y, "surface" to it.surface, "saplings" to it.saplings,
                    "care" to it.care.size, "planting" to it.planting.size, "retryAtTick" to it.retryAt) },
                "plantingRejections" to forestSearches[id]?.let { mapOf("scope" to "retained-zone-search",
                    "total" to it.plantingRefusals, "reasons" to it.plantingReasons.toMap(),
                    "last" to it.lastPlantingCandidate?.let { candidate -> mapOf("candidate" to point(candidate),
                        "point" to point(it.lastPlantingPoint), "reasons" to it.lastPlantingReasons,
                        "neighbourTimber" to it.lastPlantingNeighbour) }) },
                "forestScan" to forestScans[id]?.let { mapOf("building" to it.building.id.toString(),
                    "root" to point(it.scan.root), "roots" to it.roots.size, "phase" to it.scan.phase,
                    "examined" to it.scan.examined, "pending" to it.scan.pending, "cells" to it.scan.size,
                    "groundedRoots" to it.scan.groundedRoots, "retainedCrownReason" to it.scan.retainedCrownReason,
                    "failureReason" to it.scan.failureReason, "failurePoint" to point(it.scan.failurePoint)) },
                "forestPlant" to forestPlants[id]?.let { mapOf("building" to it.building.id.toString(),
                    "roots" to it.roots.map(::point), "timber" to it.timber, "checkedColumns" to it.next,
                    "checkedHints" to it.hintNext, "hintColumns" to it.hints.size) })
    }

    fun plan(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        harvestOnly: Boolean): CivWorldWork? {
        spotSearchPending = false
        if (!CivsAutonomy.legacyStock(city) && CivsEquipment.slots(worker)[4]?.material?.endsWith("_AXE") != true) return null
        val paths = CivsForestry.paths(city, forestryPaths(city))
        city.forestryJobs?.get(building.id.toString())?.let { job ->
            return forestBatch(city, worker, building, world, job)
        }
        // One worker owns a mill's candidate until its first durable receipt. Other mills remain independent.
        if (city.workers.filter { it.assignment == building.id }.minByOrNull { it.id.toString() }?.id != worker.id) return null
        val timber = requireNotNull(CivsProduction.material(building))
        val sapling = timber.removeSuffix("_LOG") + "_SAPLING"
        val availableSaplings = if (!CivsAutonomy.legacyStock(city))
            CivsEquipment.available(worker, stock(city, building.id)).filterKeys { it.endsWith("_SAPLING") } else emptyMap()
        val supplies = if (!CivsAutonomy.legacyStock(city)) availableSaplings[sapling] ?: 0L else forestSupply(city, worker, building, sapling)
        val deferred = deferredRoots(worker, building)
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
                refreshApproaches(worker.id, feet, deferred)
            }
        }
        if (search.saplings != supplies || search.availableSaplings != availableSaplings) {
            search.saplings = supplies
            search.availableSaplings = availableSaplings
            search.unfunded.clear()
            search.retryAt = null
        }
        forestScans[worker.id]?.takeIf { it.building == building }?.let { scan ->
            return finishForestScan(city, worker, building, world, scan)
        }
        forestPlants[worker.id]?.takeIf { it.building == building &&
            forestSupply(city, worker, building, it.timber.removeSuffix("_LOG") + "_SAPLING") >= it.roots.size }?.let { plant ->
            val result = finishForestPlant(city, worker, building, world, plant, paths)
            if (result != null || forestPlants[worker.id] != null || spotSearchPending || !forestBudget()) return result
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
            if (!CivsForestry.supportedTimber(base.type.name) || below.type.name !in CivsForestry.soils) {
                search.mature.remove(point); return null
            }
            val treeTimber = base.type.name
            val roots = if (CivsForestry.large(treeTimber)) forestPlot(city, building, world, point, Material.valueOf(treeTimber), budgeted = true)
                ?: if (treeTimber == "DARK_OAK_LOG") emptyList() else listOf(point) else listOf(point)
            if (!forestBudget()) return null
            if (roots.isEmpty() || !CivsForestry.rootsClear(city, roots, paths)) {
                search.remember(search.unsuitable, point); return null
            }
            val scan = ForestScan(building, CivsForestry.Scan(point, treeTimber, roots) { p ->
                civsProtectedBuildings(city).any { protectedTreeCell(it, p) }
            }, roots)
            forestScans[worker.id] = scan
            return finishForestScan(city, worker, building, world, scan)
        }
        // The initial sweep gives existing trees priority. Once covered, alternate an
        // available planting action with harvest/care so natural growth cannot starve it.
        val plantingTurn = search.next == search.columns.size && search.plantNext && search.planting.isNotEmpty() &&
            supplies >= (if (CivsForestry.large(timber)) 4L else 1L) && !harvestOnly
        if (!plantingTurn) for (point in search.mature.toList()) {
            val result = matureCandidate(point)
            if (result != null || forestScans[worker.id] != null || spotSearchPending || !forestBudget()) return result
        }
        // Keep one resumable column and a rolling refresh cursor. Completed coverage,
        // known roots and safe-site candidates survive receipts, inventory and cycles.
        while (budget.columns > 0) {
            if (!forestBudget()) return blocked(worker, "searching-work")
            budget.columns--
            val column = search.column ?: (search.dirty.firstOrNull() ?: search.columns[
                if (search.next < search.columns.size) search.next else search.refresh]).also {
                search.column = it; search.scanningDirty = it in search.dirty
                search.care.removeIf { p -> p.x == it.x && p.z == it.z }
                search.planting.removeIf { p -> p.x == it.x && p.z == it.z }
                search.mature.removeIf { p -> p.x == it.x && p.z == it.z }
                search.treeColumns.remove(it)
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
                if (base?.type?.name?.let { it.endsWith("_SAPLING") || it.endsWith("_LOG") || it.endsWith("_WOOD") } == true)
                    search.treeColumns += column
                if (base?.blockData?.asString == CivsForestry.saplingState(sapling)) search.care += position(base)
                if (base?.type?.name?.let(CivsForestry::supportedTimber) != true) continue
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
            var ground = forestRead(city, building, world, column.copy(y = surface))
            var planting = forestRead(city, building, world, column.copy(y = surface + 1))
            // WORLD_SURFACE sees grass; the planting receipt owns its exact replacement, including both tall halves.
            if (ground?.blockData?.asString == "minecraft:tall_grass[half=upper]") {
                planting = forestRead(city, building, world, column.copy(y = surface - 1))
                    ?.takeIf { it.blockData.asString == "minecraft:tall_grass[half=lower]" }
                ground = forestRead(city, building, world, column.copy(y = surface - 2))
            } else if (ground?.type == Material.SHORT_GRASS) {
                planting = ground
                ground = forestRead(city, building, world, column.copy(y = surface - 1))
            }
            if (!forestBudget()) return blocked(worker, "searching-work")
            nextColumn()
            if (ground?.type?.name in CivsForestry.soils && ground?.type != Material.COARSE_DIRT &&
                planting?.blockData?.asString?.let(CivsForestry::plantingRootState) == true)
                search.planting += position(planting)
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
                if (roots == null || !CivsForestry.plantingClear(city, roots, paths)) {
                    search.refused(point, if (roots == null) listOf("planting-plot") else plantingCrownReasons(city, roots, timber, paths))
                    search.planting.remove(point); continue
                }
                if (forestSupply(city, worker, building, sapling) < roots.size)
                    return blocked(worker, "waiting-saplings", sapling, roots.size.toLong())
                search.planting.remove(point)
                val area = CivsForestry.plantingBounds(roots)
                val hints = search.treeColumns.filter { it.x in area.min.x..area.max.x && it.z in area.min.z..area.max.z }
                val pending = ForestPlant(building, roots, timber, hints = hints)
                forestPlants[worker.id] = pending
                val result = finishForestPlant(city, worker, building, world, pending, paths)
                if (result != null || forestPlants[worker.id] != null || spotSearchPending || !forestBudget()) return result
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
            val roots = careRoots(city, building, world, point, timber, budgeted = true)
            if (!forestBudget()) return blocked(worker, "searching-work")
            if (roots == null || roots.any { CivsForestry.rootClaimed(city, building.id, it) } ||
                !CivsForestry.rootsClear(city, roots, paths)) { search.care.remove(point); continue }
            val saplingBlock = forestRead(city, building, world, point)
            if (!forestBudget()) return blocked(worker, "searching-work")
            if (saplingBlock?.blockData?.asString != CivsForestry.saplingState(sapling)) { search.care.remove(point); continue }
            val standing = beside(city, worker, building, world, point)
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
            return blocked(worker, "worksite-no-access", point = deferred.first(), reason = "approaches-exhausted")
        }
        search.unfunded.values.firstOrNull()?.let { (material, amount) ->
            return blocked(worker, "waiting-saplings", material, amount)
        }
        if (search.retryRoots.isNotEmpty()) return blocked(worker, "searching-work", point = search.retryRoots.keys.first(),
            reason = "tree-observation-pending")
        if (needStatus(city, worker) == "searching-work") return blocked(worker, "waiting-growth")
        return null
    }

    private fun plantingRank(building: UUID, point: CivPosition): Long {
        var value = building.mostSignificantBits xor building.leastSignificantBits xor (point.x.toLong() shl 32) xor point.z.toLong()
        value = (value xor (value ushr 33)) * -49064778989728563L
        value = (value xor (value ushr 33)) * -4265267296055464877L
        return value xor (value ushr 33)
    }

    private fun forestBudget(): Boolean = budget.forestAvailable()

    private fun forestRead(city: Settlement, building: CivBuilding, world: World, point: CivPosition): Block? {
        if (!forestBudget()) return null
        budget.forestUnits--
        return read(city, building, world, point)
    }

    fun careRoots(city: Settlement, building: CivBuilding, world: World, focus: CivPosition,
        timber: String, budgeted: Boolean = false): List<CivPosition>? {
        val sapling = timber.removeSuffix("_LOG") + "_SAPLING"
        fun at(point: CivPosition) = if (budgeted) forestRead(city, building, world, point)
            else read(city, building, world, point)
        if (at(focus)?.blockData?.asString != CivsForestry.saplingState(sapling)) return null
        return if (CivsForestry.large(timber)) forestPlot(city, building, world, focus, Material.valueOf(sapling), budgeted)
        else listOf(focus).takeIf { at(focus.copy(y = focus.y - 1))?.type?.name in CivsForestry.soils }
    }

    private fun forestSupply(city: Settlement, worker: CivWorker, building: CivBuilding, sapling: String): Long =
        if (CivsAutonomy.legacyStock(city)) if (city.stock.wood > 0 || building.id.toString() !in city.forestrySeeded.orEmpty()) 4L else 0L
        else CivsEquipment.available(worker, stock(city, building.id))[sapling] ?: 0

    private fun finishForestScan(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        pending: ForestScan): CivWorldWork? {
        while (!pending.scan.complete && !pending.scan.failed && forestBudget()) {
            budget.forestUnits -= pending.scan.advance(1) { point ->
                read(city, building, world, point)?.blockData?.asString
            }
        }
        if (!pending.scan.complete && !pending.scan.failed) return blocked(worker, "searching-work")
        // Only connected, separately grounded trunks prove a surviving neighbour.
        // Shared foliage can also touch a placed log and is not a thinning witness.
        // Harvest and planting are independent jobs. Missing a replacement kit must not strand a mature tree.
        val thinning = pending.scan.retainedCrownReason == "shared-trunks" || !CivsAutonomy.legacyStock(city) &&
            forestSupply(city, worker, building, pending.scan.timber.removeSuffix("_LOG") + "_SAPLING") < pending.roots.size
        val job = pending.scan.job(pending.roots)?.copy(thinning = thinning,
            seedDeferred = !thinning && CivsAutonomy.legacyStock(city) && city.stock.wood == 0L)
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
        val status = needStatus(city, worker)
        if (work != null || !spotSearchPending && status in
            setOf("worksite-no-access", "worksite-obstacle", "worksite-protected", "waiting-saplings")) {
            forestScans.remove(worker.id)
            if (work == null && status == "waiting-saplings") forestSearches[worker.id]?.let { search ->
                search.unfunded[job.root] = job.sapling to job.roots.size.toLong()
                if (search.unfunded.size > search.columns.size) search.unfunded.remove(search.unfunded.keys.first())
            }
            else if (work == null && status == "worksite-no-access") {
                deferApproach(worker, building, job.root)
            }
        }
        return work
    }

    private fun finishForestPlant(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        pending: ForestPlant, paths: Set<Pair<Int, Int>>): CivWorldWork? {
        fun unavailable(temporary: Boolean = false, reasons: List<String> = listOf("unavailable-block"),
            point: CivPosition = pending.roots.first(), neighbour: String? = null): CivWorldWork? {
            forestSearches[worker.id]?.let { search ->
                search.refused(pending.roots.first(), reasons, point, neighbour)
                // One refused plot used its planting turn; let already observed trees run next.
                search.plantNext = false
                if (temporary) search.retry(pending.roots.first(), planting = true)
                else search.remember(search.unplantable, pending.roots.first())
            }
            forestPlants.remove(worker.id)
            return null
        }
        val crown = CivsForestry.plantingBounds(pending.roots)
        val bounds = crown
        val width = bounds.max.x - bounds.min.x + 1
        val count = width * (bounds.max.z - bounds.min.z + 1)
        if (!CivsForestry.plantingClear(city, pending.roots, paths)) {
            return unavailable(reasons = plantingCrownReasons(city, pending.roots, pending.timber, paths))
        }
        var temporary = false
        var reasons = listOf<String>()
        var refusedPoint = pending.roots.first()
        var neighbour: String? = null
        fun inspect(point: CivPosition): Boolean {
            val inCrown = point.x in crown.min.x..crown.max.x && point.z in crown.min.z..crown.max.z
            if (!world.isChunkLoaded(point.x shr 4, point.z shr 4)) {
                temporary = inCrown
                reasons = listOf("unloaded-chunk"); refusedPoint = point; neighbour = null
                return !inCrown
            }
            budget.forestUnits -= 3
            val top = world.getHighestBlockYAt(point.x, point.z, HeightMap.MOTION_BLOCKING_NO_LEAVES)
            // Neighbouring village cells are observations, never mutation authority.
            // Keep native locks/Lands checks and do not mistake a building's timber for a tree.
            val villageColumn = pending.roots.none { it.x == point.x && it.z == point.z } &&
                civsProtectedBuildings(city).any { point.x in it.bounds.min.x..it.bounds.max.x &&
                    point.z in it.bounds.min.z..it.bounds.max.z }
            val block = read(city, building, world, point.copy(y = top), observeBuilding = villageColumn)
            val above = read(city, building, world, point.copy(y = top + 1), observeBuilding = villageColumn)
            val timber = when {
                above?.type?.name?.endsWith("_SAPLING") == true -> above.type.name.removeSuffix("_SAPLING") + "_LOG"
                villageColumn -> null
                block?.type?.name?.endsWith("_LOG") == true -> block.type.name.removePrefix("STRIPPED_")
                block?.type?.name?.endsWith("_WOOD") == true -> block.type.name.removePrefix("STRIPPED_").removeSuffix("_WOOD") + "_LOG"
                else -> null
            }
            val crowded = timber != null && inCrown
            forestSearches[worker.id]?.let { search -> search.byColumn[point.x to point.z]?.let { column ->
                if (timber == null) search.treeColumns.remove(column) else search.treeColumns += column
            } }
            if (inCrown && (block == null || above == null)) {
                temporary = true
                reasons = listOf("unavailable-block"); refusedPoint = point
                return false
            }
            reasons = buildList {
                if (crowded) add("native-neighbour")
                if (inCrown && block?.type in setOf(Material.DIRT_PATH, Material.COARSE_DIRT)) add("native-road-surface")
            }
            refusedPoint = point.copy(y = if (above?.type?.name?.endsWith("_SAPLING") == true) top + 1 else top)
            neighbour = timber
            return reasons.isEmpty()
        }
        // Discovery hints can only reject a plot after the same budgeted native observation.
        // Every accepted plot still passes the complete spacing/road scan below.
        while (pending.hintNext < pending.hints.size && budget.forestUnits >= 3 && forestBudget()) {
            if (!inspect(pending.hints[pending.hintNext])) return unavailable(temporary, reasons, refusedPoint, neighbour)
            pending.hintNext++
        }
        if (pending.hintNext < pending.hints.size) return blocked(worker, "searching-work")
        // Three observations per column find both mature trunks beneath leaves
        // and passable saplings above ground. An overhanging leaf is not a tree root.
        while (pending.next < count && budget.forestUnits >= 3 && forestBudget()) {
            val point = CivPosition(bounds.min.x + pending.next % width, bounds.min.y, bounds.min.z + pending.next / width)
            if (!inspect(point)) return unavailable(temporary, reasons, refusedPoint, neighbour)
            pending.next++
        }
        if (pending.next < count) return blocked(worker, "searching-work")
        val sapling = pending.timber.removeSuffix("_LOG") + "_SAPLING"
        if (forestSupply(city, worker, building, sapling) < pending.roots.size) {
            if (CivsAutonomy.legacyStock(city)) forestPlants.remove(worker.id)
            return blocked(worker, "waiting-saplings", sapling, pending.roots.size.toLong())
        }
        val cleared = mutableListOf<CivPlanBlock>()
        val blocks = pending.roots.map { point ->
            val block = read(city, building, world, point) ?: return unavailable(temporary = true)
            val soil = read(city, building, world, point.copy(y = point.y - 1))
                ?: return unavailable(temporary = true)
            if (!CivsForestry.plantingRootState(block.blockData.asString) || soil.type.name !in CivsForestry.soils)
                return unavailable(reasons = listOf("planting-root-changed"), point = point)
            val tall = block.blockData.asString == "minecraft:tall_grass[half=lower]"
            for (height in 1..4) {
                val headroom = read(city, building, world, point.copy(y = point.y + height))
                    ?: return unavailable(temporary = true)
                if (tall && height == 1) {
                    if (headroom.blockData.asString != "minecraft:tall_grass[half=upper]")
                        return unavailable(reasons = listOf("tall-grass-changed"), point = position(headroom))
                    cleared += change(headroom, "minecraft:air")
                    continue
                }
                if (!headroom.type.isAir && headroom.type !in WEEDS && !headroom.type.name.endsWith("_LEAVES"))
                    return unavailable(reasons = listOf("planting-headroom"), point = position(headroom))
            }
            block
        }
        val standing = beside(city, worker, building, world, pending.roots.first()) ?: run {
            if (!spotSearchPending) forestPlants.remove(worker.id)
            return null
        }
        forestPlants.remove(worker.id)
        return work(worker, building, standing, blocks.map { change(it, CivsForestry.saplingState(sapling)) } + cleared,
            mapOf(sapling to pending.roots.size.toLong()), status = "planting-tree", focus = pending.roots.first())
            .let { if (CivsAutonomy.legacyStock(city)) it.copy(equipmentAfter = null) else it }
    }

    /** Diagnostics only: crownClear remains the admission authority; overlapping reasons are counted separately. */
    private fun plantingCrownReasons(city: Settlement, roots: List<CivPosition>, timber: String,
        paths: Set<Pair<Int, Int>>): List<String> {
        val crown = CivsForestry.plantingBounds(roots)
        fun contains(x: Int, z: Int) = x in crown.min.x..crown.max.x && z in crown.min.z..crown.max.z
        val protected = civsProtectedBuildings(city)
        return buildList {
            if (!city.contains(crown.min.x, crown.min.z) || !city.contains(crown.max.x, crown.max.z)) add("crown-boundary")
            if (paths.any { contains(it.first, it.second) }) add("crown-road")
            if (protected.any { building -> roots.any { p -> p.x in building.bounds.min.x..building.bounds.max.x &&
                    p.z in building.bounds.min.z..building.bounds.max.z } }) add("crown-building")
            if (protected.any { building -> roots.any { CivsWorksiteLayouts.isField(building, it.x, it.z) } }) add("crown-field")
        }
    }

    private fun forestBatch(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        job: CivForestryJob): CivWorldWork? {
        val existing = city.forestryJobs?.get(building.id.toString())
        if (existing == null && !job.thinning && !CivsAutonomy.legacyStock(city) && forestSupply(city, worker, building, job.sapling) < job.roots.size)
            return blocked(worker, "waiting-saplings", job.sapling, job.roots.size.toLong())
        val standing = beside(city, worker, building, world, job.root,
            capture = { captureForestBatch(city, worker, building, world, job, CivWorkApproach(job.root)) }) ?: return null
        return standing.capturedWork?.copy(standing = standing.standing, remoteWork = standing.remoteWork)
            ?: captureForestBatch(city, worker, building, world, job, standing)
    }

    private fun captureForestBatch(city: Settlement, worker: CivWorker, building: CivBuilding, world: World,
        job: CivForestryJob, standing: CivWorkApproach): CivWorldWork? {
        val existing = city.forestryJobs?.get(building.id.toString())
        if (!forestBudget()) return blocked(worker, "searching-work")
        // ponytail: one existing <=128-cell captured batch owns the rest of this
        // pulse; a second worker must not multiply its atomic native validation.
        budget.forestUnits = 0
        val next = CivsForestry.batchEnd(job)
        val changes = mutableListOf<CivPlanBlock>()
        val outputs = linkedMapOf<String, Long>()
        val matcher = CivsForestry.Matcher(job)
        var logs = 0
        for (code in job.cells.subList(job.next, next)) {
            val point = job.position(code)
            val block = read(city, building, world, point) ?: return blocked(worker, "worksite-protected", point = point)
            if (!block.type.isAir && !matcher.matches(code, block.blockData.asString) { support ->
                    read(city, building, world, support)?.blockData?.asString
                })
                return blocked(worker, "worksite-obstacle", point = point, reason = "tree-cell-changed", blockMaterial = block.type.name)
            if (!block.type.isAir) {
                if (block.type.name == job.timber) logs++
                if (!CivsAutonomy.legacyStock(city)) drops(block, tool(worker)).forEach { (material, amount) -> outputs.merge(material, amount, Math::addExact) }
            }
            val after = if (!job.thinning && point in job.roots) CivsForestry.saplingState(job.sapling) else "minecraft:air"
            if (!block.type.isAir || point in job.roots) changes += change(block, after)
        }
        val progress = CivForestryProgress(job, next, outputs, logs)
        val output = if (!CivsAutonomy.legacyStock(city)) outputs else emptyMap()
        return work(worker, building, standing, changes,
            if (existing == null && !job.seedDeferred && !job.thinning) mapOf(job.sapling to job.roots.size.toLong()) else emptyMap(), output,
            status = "felling-tree", duration = maxOf(if (job.next == 0) 6_000L else 1_000L, logs * 1_000L), focus = job.root)
            .copy(forestry = progress).let { if (CivsAutonomy.legacyStock(city)) it.copy(equipmentAfter = null) else it }
    }

    /** Spruce, jungle and dark oak use four real, supported saplings for native giant variants. */
    private fun forestPlot(city: Settlement, building: CivBuilding, world: World, root: CivPosition, material: Material,
        budgeted: Boolean = false): List<CivPosition>? {
        fun at(point: CivPosition) = if (budgeted) forestRead(city, building, world, point)
            else read(city, building, world, point)
        for (dx in listOf(0, -1)) for (dz in listOf(0, -1)) {
            val cells = (0..1).flatMap { x -> (0..1).map { z -> root.copy(x = root.x + dx + x, z = root.z + dz + z) } }
            if (cells.all { point -> at(point)?.let { if (material == Material.AIR)
                    CivsForestry.plantingRootState(it.blockData.asString) else it.type == material } == true &&
                    at(point.copy(y = point.y - 1))?.type?.name in CivsForestry.soils }) return cells
        }
        return null
    }

    private fun read(city: Settlement, building: CivBuilding, world: World, point: CivPosition,
        observeBuilding: Boolean = false): Block? = readBlock(city, building, world, point, observeBuilding)

    private fun beside(city: Settlement, worker: CivWorker, building: CivBuilding, world: World, point: CivPosition,
        capture: (() -> CivWorldWork?)? = null): CivWorkApproach? = approach(city, worker, building, world, point, capture)
        .also { spotSearchPending = it.pending }.approach

    private fun work(worker: CivWorker, building: CivBuilding, approach: CivWorkApproach, changes: List<CivPlanBlock>,
        inputs: Map<String, Long> = emptyMap(), outputs: Map<String, Long> = emptyMap(), status: String,
        duration: Long = 3_000, focus: CivPosition? = null): CivWorldWork =
        makeWork(worker, building, approach, CivWorkAction(changes, inputs, outputs, status, duration, focus))

    private fun blocked(worker: CivWorker, status: String, material: String? = null, amount: Long = 1,
        point: CivPosition? = null, reason: String? = null, blockMaterial: String? = null): CivWorldWork? =
        recordNeed(worker, CivWorkNeed(status, material, amount, point, reason, blockMaterial))

    private fun protectedTreeCell(other: CivBuilding, point: CivPosition): Boolean =
        other.bounds.contains(point.x, point.y, point.z) ||
            CivsWorksiteLayouts.field(other).any { it.x == point.x && it.z == point.z && point.y in it.y..it.y + 1 }
    private fun drops(block: Block, tool: ItemStack): Map<String, Long> = block.getDrops(tool).filter { !it.type.isAir }
        .groupingBy { it.type.name }.fold(0L) { count, item -> count + item.amount }
    private fun tool(worker: CivWorker): ItemStack = CivsEquipment.slots(worker)[4]?.stack() ?: ItemStack(Material.AIR)
    private fun change(block: Block, after: String) = CivPlanBlock(position(block), block.blockData.asString, after)
    private fun position(block: Block) = CivPosition(block.x, block.y, block.z)
    private val WEEDS = setOf(Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN)
}
