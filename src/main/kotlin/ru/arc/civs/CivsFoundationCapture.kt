package ru.arc.civs

import org.bukkit.HeightMap
import org.bukkit.Material
import org.bukkit.block.TileState
import org.bukkit.block.data.Waterlogged
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import ru.arc.civs.CivsTerrainPolicy.naturalLeaves
import ru.arc.civs.CivsTerrainPolicy.naturalTerraform
import ru.arc.civs.CivsTerrainPolicy.fullTerraformSupport
import ru.arc.civs.CivsTerrainPolicy.routeTerraformSupport
import ru.arc.civs.CivsTerrainPolicy.passableTerraform
import ru.arc.civs.CivsTerrainPolicy.requireTerrainRoute
import ru.arc.civs.CivsTerrainPolicy.PROTECTED_TERRAIN
import ru.arc.civs.CivsTerrainPolicy.FALLING_GROUND

/** A downward probe is one queue unit; deep foundations share every other scan's tick budget. */
internal class ManualFoundationCapture(access: CivsWorld, cityId: UUID, owner: UUID, worldId: UUID, building: CivBuilding,
    private val schematicCells: Int, private val executor: Executor, private val previous: CivBuilding?,
    private var strictAutonomous: Boolean) :
    CivsWorldScan<CivTerraformPlan?>(access, cityId, owner, worldId, building) {
    private val width = building.bounds.max.x - building.bounds.min.x + 1
    private val footprintColumns = width * (building.bounds.max.z - building.bounds.min.z + 1)
    private val entranceOutside = !CivsAutonomousGeometry.horizontalContains(building.bounds, building.entrance)
    private val totalColumns = footprintColumns + if (entranceOutside) 1 else 0
    private val captured = linkedMapOf<CivPosition, CivTerraformCell>()
    private val columns = ArrayList<CivTerraformColumn>()
    private var columnIndex = 0
    private var y: Int? = null
    private var topY = 0
    private var planned: CompletableFuture<CivTerraformPlan?>? = null

    init { future.whenComplete { _, _ -> if (future.isCancelled) planned?.cancel(false) } }

    override fun validateIdentity(city: Settlement?) {
        if (city?.autonomy != null) strictAutonomous = true
        requireCivs(city?.pendingDeletion == null && city?.building(building.id) == previous, "stale-building")
    }

    override fun ready(): Boolean = planned?.isDone != false

    override fun step() {
        planned?.let { return finish(it.join()) }
        if (columnIndex == totalColumns) {
            val prepareControl = retargetSource != null
            planned = CompletableFuture.supplyAsync({
                val draft = CivTerraformDraft(building, columns, emptyList(), building.anchor.y,
                    prepareControl = prepareControl)
                CivsAutonomousTerraform.materialize(draft, captured).takeIf { it.blocks.isNotEmpty() }.also {
                    requireCivs(schematicCells.toLong() + (it?.blocks?.size ?: 0) <= CivsStore.MAX_PLAN_BLOCKS, "invalid-plan")
                }
            }, executor)
            return
        }
        val footprint = columnIndex < footprintColumns
        val x = if (footprint) building.bounds.min.x + columnIndex % width else building.entrance.x
        val z = if (footprint) building.bounds.min.z + columnIndex / width else building.entrance.z
        // Completed buildings retain their authored structure and already accepted foundations.
        if (previous?.phase == BuildingPhase.ACTIVE &&
            (CivsAutonomousGeometry.horizontalContains(previous.bounds, CivPosition(x, 0, z)) || !footprint)) {
            columnIndex++
            return
        }
        val ground = if (footprint) building.anchor.y else building.entrance.y - 1
        val floor = minOf(ground, building.bounds.min.y - 1)
        requireCivs(floor >= world.minHeight && ground + 2 < world.maxHeight, "outside-world")
        val currentY = y ?: run {
            requireCivs(world.isChunkLoaded(x shr 4, z shr 4), "chunks-unloaded")
            topY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING)
            requireCivs(maxOf(topY, ground) + 2 < world.maxHeight, "outside-world")
            maxOf(topY, ground) + 2
        }
        requireCivs(index < CivsAutonomousTerraform.MAXIMUM_CAPTURE_CELLS, "autonomy-search-budget")
        val position = CivPosition(x, currentY, z)
        val block = read(position)
        val data = block.blockData
        if (position == previous?.control && previous?.control != building.control && retargetSource != null)
            rejectAt("hall-source-overlap", block)
        if (position == building.control && !(previous?.control == position && block.type == Material.BARREL) &&
            (if (strictAutonomous) !naturalTerraform(block) else block.isLiquid ||
                (data as? Waterlogged)?.isWaterlogged == true || block.state is TileState || block.type in PROTECTED_TERRAIN))
            rejectAt(if (retargetSource != null) "hall-target-control-incompatible" else "autonomy-terrain", block)
        // Air above the terrace stays untouched; schematic capture still validates its own cells.
        val accessFailure = if (currentY > ground && block.type.isAir) null
            else if (!ownerCanModify(block)) "lands-denied"
            else try {
                checkTerrainOwnership(position, allowUnpublished = true); null
            } catch (failure: CivsRejection) { failure.reason }
        val accessible = accessFailure == null
        val safe = naturalTerraform(block) && accessible
        val solid = !block.type.hasGravity() && !naturalLeaves(data) && fullTerraformSupport(block)
        val supportSafe = safe || accessible && solid && block.state !is TileState
        captured[position] = CivTerraformCell(position, data.asString, safe, solid, passableTerraform(block), supportSafe)
        index++
        val supportingBase = currentY <= floor && solid && block.type !in FALLING_GROUND
        // A proved obstruction must not wait for all remaining columns and the planning executor.
        if (position != building.control && !safe && (if (currentY > ground) !block.type.isAir
                else !supportSafe && !(supportingBase && block.type == Material.BEDROCK)))
            rejectAt(accessFailure ?: "autonomy-terrain", block)
        if (supportingBase) {
            columns += CivTerraformColumn(x, z, ground, floor, topY, supportY = currentY)
            columnIndex++; y = null
        } else {
            if (currentY == world.minHeight) rejectAt("autonomy-foundation", block)
            y = currentY - 1
        }
    }
}

/** Captures the proposed grades and route supports before materializing the detached draft. */
internal class TerraformCapture(access: CivsWorld, city: Settlement, private val draft: CivTerraformDraft,
    private val executor: Executor, private val previous: CivBuilding?) :
    CivsWorldScan<CivTerraformPlan>(access, city.id, city.owner, city.worldId, draft.building) {
    private val captured = linkedMapOf<CivPosition, CivTerraformCell>()
    private val columns = draft.columns.toList()
    private val protectedAccess = draft.access.standing(city, draft.building.id)
    private var columnIndex = 0
    private var y: Int? = null
    private var planned: CompletableFuture<CivTerraformPlan>? = null

    init {
        requireCivs(columns.size in 1..CivsAutonomousTerraform.MAXIMUM_SURFACE_COLUMNS &&
            columns.map { civsTerrainKey(it.x, it.z) }.distinct().size == columns.size, "invalid-plan")
        requireTerrainRoute(draft.route, building)
        requireCivs(columns.sumOf { (maxOf(it.surfaceY, it.groundY).toLong() + 3 - it.supportY).coerceAtLeast(0) } <=
            CivsAutonomousTerraform.MAXIMUM_CAPTURE_CELLS, "autonomy-search-budget")
        future.whenComplete { _, _ -> if (future.isCancelled) planned?.cancel(false) }
    }
    override fun validateIdentity(city: Settlement?) = requireUnchangedBuilding(city, previous)
    override fun ready(): Boolean = planned?.isDone != false
    override fun step() {
        planned?.let { return finish(it.join()) } // ready() only resumes completed detached work.
        if (columnIndex == columns.size) {
            planned = CompletableFuture.supplyAsync({ CivsAutonomousTerraform.materialize(draft, captured) }, executor)
            return
        }
        val column = columns[columnIndex]
        val low = maxOf(world.minHeight, column.supportY)
        val high = minOf(world.maxHeight - 1, maxOf(column.surfaceY, column.groundY) + 2)
        requireCivs(low <= high, "outside-world")
        val currentY = y ?: high
        val position = CivPosition(column.x, currentY, column.z)
        val block = read(position)
        val shoulderFill = column.shoulderFill(currentY)?.let { parse(it).material }
        val safe = (column.preserve || civsTerrainKey(position.x, position.z) !in protectedAccess) &&
            naturalTerraform(block) && ownerCanModify(block, shoulderFill) && try {
            checkTerrainOwnership(position); true
        } catch (_: CivsRejection) { false }
        captured[position] = CivTerraformCell(position, block.blockData.asString, safe,
            !naturalLeaves(block.blockData) && (column.preserve || !block.type.hasGravity()) &&
                (if (column.preserve) routeTerraformSupport(block) else fullTerraformSupport(block)), passableTerraform(block))
        index++
        if (currentY == low) { columnIndex++; y = null } else y = currentY - 1
    }
}
