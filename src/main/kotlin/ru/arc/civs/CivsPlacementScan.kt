package ru.arc.civs

import org.bukkit.HeightMap
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.TileState
import org.bukkit.inventory.InventoryHolder
import org.bukkit.block.data.Waterlogged
import ru.arc.paper.api.ArcSchematicBlock
import java.util.UUID
import ru.arc.civs.CivsTerrainPolicy.checkAutonomousTerrain
import ru.arc.civs.CivsTerrainPolicy.naturalTerraform
import ru.arc.civs.CivsTerrainPolicy.fullTerraformSupport
import ru.arc.civs.CivsTerrainPolicy.routeTerraformSupport
import ru.arc.civs.CivsTerrainPolicy.passableTerraform
import ru.arc.civs.CivsTerrainPolicy.requireTerrainRoute
import ru.arc.civs.CivsTerrainPolicy.PROTECTED_TERRAIN

/** Native evidence required both when capturing a placement and when revalidating its durable plan. */
internal sealed class CivsPlacementScan<T>(
    access: CivsWorld, private val includeColumns: (CivsWorldScan<*>, List<CivPosition>) -> Unit,
    cityId: UUID, owner: UUID, worldId: UUID, building: CivBuilding,
) : CivsWorldScan<T>(access, cityId, owner, worldId, building) {
    private var gateReadiness: GateReadiness? = null
    private var gateReady = false

    protected fun validateGateReplacement(city: Settlement?, previous: CivBuilding?) {
        if (previous?.type != BuildingType.GATE || building.type != BuildingType.WALL) return
        val survivor = requireNotNull(CivsInfrastructureRebuild.closureSurvivor(requireNotNull(city), previous, building))
        val existing = gateReadiness
        if (existing != null) {
            requireCivs(existing.gate == survivor, "stale-building")
            return
        }
        gateReadiness = GateReadiness(survivor).also { includeColumns(this, it.columns) }
    }

    final override fun step() {
        val proof = gateReadiness
        if (proof != null && !gateReady) {
            gateReady = proof.step()
            return
        }
        scanPlacement()
    }

    protected abstract fun scanPlacement()

    /** A small cursor inside ordinary capture/revalidation, never a separate planner or world writer. */
    private inner class GateReadiness(val gate: CivBuilding) {
        private val lines = CivsInfrastructureRebuild.readyLines(gate)
        private val core = lines.flatMap { it.drop(1).dropLast(1) }.distinct()
        private val approaches = lines.flatMap { listOf(it.first(), it.last()) }.flatMap { p ->
            (-1..1).map { dy -> p.copy(y = p.y + dy) }
        }.distinct()
        private val samples = (core.flatMap { p -> (-1..2).map { dy -> p.copy(y = p.y + dy) to (dy == -1) } } +
            approaches.flatMap { p -> (-1..1).map { dy -> p.copy(y = p.y + dy) to (dy == -1) } }).distinct()
        private val observed = hashMapOf<Pair<CivPosition, Boolean>, Boolean>()
        private var cursor = 0
        val columns: List<CivPosition> get() = lines.flatten()

        fun step(): Boolean {
            if (cursor < samples.size) {
                val sample = samples[cursor++]
                val block = read(sample.first)
                observed[sample] = if (sample.second) routeTerraformSupport(block) else passableTerraform(block)
                return false
            }
            fun stands(p: CivPosition, head: Int = 1) = (-1..head).all { dy ->
                observed[p.copy(y = p.y + dy) to (dy == -1)] == true
            }
            requireCivs(core.all { stands(it, 2) }, "gate-refit-exit-not-ready")
            requireCivs(lines.any { line ->
                line.drop(1).dropLast(1).zipWithNext().all { (a, b) -> kotlin.math.abs(a.y - b.y) <= 1 } &&
                    listOf(line.first(), line.last()).all { p -> (-1..1).any { dy -> stands(p.copy(y = p.y + dy)) } }
            }, "gate-refit-exit-not-ready")
            return true
        }
    }

    class Capture(
        access: CivsWorld, includeColumns: (CivsWorldScan<*>, List<CivPosition>) -> Unit,
        cityId: UUID, owner: UUID, worldId: UUID, building: CivBuilding,
        private val cells: List<ArcSchematicBlock>, private val previous: CivBuilding?,
        private val allowControlPlacement: Boolean = false,
        private var strictAutonomous: Boolean = false,
        terrain: CivTerraformPlan? = null,
        private val adminPlacement: Boolean = false,
        private val upgradeProposal: Boolean = false,
        private val constructionStorage: Boolean = false,
        acceptedStorage: List<CivPlanBlock>? = null,
        private val wallApproach: CivWallRefitApproach? = null,
    ) : CivsPlacementScan<CivPlacementPlan>(access, includeColumns, cityId, owner, worldId, building) {
        private val acceptedHolders = acceptedStorage?.associateBy { it.position }
        private val blocks = ArrayList<CivPlanBlock>()
        private var terraform = terrain?.copy(blocks = terrain.blocks.toList(), route = terrain.route.toList(),
            waterFoundations = terrain.waterFoundations?.toList(), requiredGrass = terrain.requiredGrass?.toList(),
            paidGateFloors = terrain.paidGateFloors?.toList(), paidWaterFloors = terrain.paidWaterFloors?.toList())
        private val paidGateFloors = terraform?.paidGateFloors.orEmpty().toSet()
        private val paidGateCells = civsPaidGateFloorCells(building, terraform?.paidGateFloors.orEmpty())
        private val terrainOverlay = terraform?.blocks.orEmpty().associateBy { it.position }
        private val requiredGrass = terraform?.requiredGrass.orEmpty().toSet()
        private val terrainBlocks = ArrayList<CivPlanBlock>()
        private val waterColumns = civsWaterFoundationColumns(building, terraform?.waterFoundations.orEmpty(), terraform?.blocks.orEmpty())
        private val waterFoundations = waterColumns.values.toList()
        private val waterCells = if (waterColumns.isEmpty()) emptyMap() else cells.filterNot { it.isBarrier }
            .associateBy { CivPosition(it.position.x, it.position.y, it.position.z) }
        private val infrastructureGround = (if (building.type.isInfrastructure) cells.filterNot { it.isBarrier || it.isAir }
            .groupBy { civsTerrainKey(it.position.x, it.position.z) }.mapValues { (_, values) -> values.minOf { it.position.y } }
            else emptyMap()) + waterColumns.mapValues { it.value.floorY }
        private var waterIndex = 0
        private var waterY: Int? = null
        private var terrainIndex = 0
        private var routeIndex = 0
        private var loweredWallStep: Int? = null

        init {
            requireCivs(cells.size.toLong() == civsBoxVolume(building.bounds), "invalid-plan")
            waterFoundations.forEach { f -> for (y in f.bottomY + 1..f.floorY) {
                requireCivs(waterCells[CivPosition(f.x, y, f.z)]?.let { !it.isAir && civsPaidWaterFoundationBlock(it.blockData) } == true,
                    "invalid-plan")
            } }
            terraform?.let {
                requireCivs(cells.size.toLong() + it.blocks.size <= CivsStore.MAX_PLAN_BLOCKS, "invalid-plan")
                requireTerrainRoute(it.route, building, allowManualFoundation = true)
                requireCivs(requiredGrass.isEmpty() || building.type == BuildingType.WALL && it.route.isNotEmpty() &&
                    requiredGrass.all { p -> infrastructureGround[civsTerrainKey(p.x, p.z)] == p.y }, "invalid-plan")
                requireCivs(paidGateCells.isEmpty() || !adminPlacement && it.route.isNotEmpty() &&
                    paidGateCells.none { p -> p in terrainOverlay } &&
                    (previous?.phase == BuildingPhase.ACTIVE || building.schematicId ==
                        CivsInfrastructureRebuild.schematicId(building.type, CivsInfrastructureRebuild.stage(building), building.id)),
                    "invalid-plan")
                if (it.route.isNotEmpty()) strictAutonomous = true
            }
            wallApproach?.let { approach ->
                val plan = requireNotNull(terraform)
                requireCivs(previous?.type == BuildingType.WALL && building.type == BuildingType.WALL &&
                    CivsInfrastructureRebuild.refit(previous, building) && !adminPlacement && plan.blocks.isEmpty() &&
                    plan.waterFoundations.isNullOrEmpty() && plan.requiredGrass.isNullOrEmpty() &&
                    plan.cutCount == 0 && plan.fillCount == 0 && plan.route.size == approach.original.size, "invalid-plan")
                requireTerrainRoute(approach.original, building)
                if (upgradeProposal) requireCivs(plan.route == approach.original, "invalid-plan")
                else {
                    val at = plan.route.indices.singleOrNull { plan.route[it] != approach.original[it] }
                        ?: throw CivsRejection("invalid-plan")
                    val original = approach.original[at]
                    requireCivs(at in 1 until plan.route.lastIndex && plan.route[at] == original.copy(y = original.y - 1),
                        "invalid-plan")
                    loweredWallStep = at
                }
            }
        }

        override fun checkControl(block: Block) {
            val oldControl = retargetSource?.control
            if (oldControl != null && oldControl != building.control) {
                val preparation = terrainOverlay[building.control]
                if (!block.type.isAir && (preparation == null || preparation.after != "minecraft:air" ||
                        preparation.before != block.blockData.asString)) rejectAt("hall-target-control-incompatible", block)
                if (preparation != null) checkTerraformBlock(block, parse(preparation.after))
                if (!ownerCanModify(block, Material.BARREL)) rejectAt("lands-denied", block)
                return
            }
            if (adminPlacement && previous?.control != building.control &&
                civsContainerHasItems(block.state))
                rejectAt("container-not-empty", block)
            // Only an already accepted building owns this marker; an unrelated barrel is terrain.
            if (strictAutonomous && (previous == null || previous.control != building.control))
                checkControlTerrain(block)
            if (!allowControlPlacement || block.type == Material.BARREL) return super.checkControl(block)
            if (strictAutonomous) checkControlTerrain(block)
            if (block.type in PROTECTED_TERRAIN || block.state is TileState) rejectAt("control-obstructed", block)
            if (!ownerCanModify(block, Material.BARREL)) rejectAt("lands-denied", block)
        }

        private fun checkControlTerrain(block: Block) {
            if (allowControlPlacement && terraform?.route.isNullOrEmpty()) {
                if (!naturalTerraform(block)) rejectAt("autonomy-terrain", block)
            } else checkAutonomousTerrain(block, building.anchor.y)
        }

        override fun validateIdentity(city: Settlement?) {
            if (city?.autonomy != null && !adminPlacement) strictAutonomous = true
            requireCivs(city?.building(building.id) == previous, "invalid-building")
            wallApproach?.let { proof ->
                requireCivs(city != null && city.buildings == proof.city.buildings &&
                    city.roadRouteOverrides == proof.city.roadRouteOverrides, "stale-building")
            }
            val retarget = retargetSource != null && !adminPlacement && city?.construction == null
            if (previous != null && !retarget) requireCivs(previous.id == building.id &&
                (previous.type == building.type || city != null && CivsInfrastructureRebuild.refit(city, previous, building)) &&
                previous.entrance == building.entrance && previous.rotation == building.rotation &&
                (previous.phase == BuildingPhase.ACTIVE && (previous.level + 1 == building.level ||
                    CivsPlanBoardBlueprint.refit(previous, building) || CivsInfrastructureRebuild.refit(previous, building)) ||
                    previous.level == building.level && previous.phase == BuildingPhase.SITE), "invalid-building")
            if (previous != null && !retarget) requireCivs(civsContainsBox(building.bounds, previous.bounds), "upgrade-footprint-changed")
            validateGateReplacement(city, previous)
        }

        /** At most one existing paid road cell can replace an unsupported interior wall step. */
        private fun selectWallApproach(plan: CivTerraformPlan, approach: CivWallRefitApproach, at: Int) {
            if (!upgradeProposal || loweredWallStep != null || at !in 1 until plan.route.lastIndex) return
            val original = plan.route[at]
            val lower = original.copy(y = original.y - 1)
            if (lower.copy(y = lower.y - 1) !in approach.paidFloors ||
                (at - 1..at + 1).any { kotlin.math.abs(plan.route[it].y - lower.y) > 1 } ||
                !read(lower).type.isAir) return
            terraform = plan.copy(route = plan.route.toMutableList().also { it[at] = lower })
            loweredWallStep = at
        }

        private fun validateWallApproach(plan: CivTerraformPlan, approach: CivWallRefitApproach, at: Int) {
            // None of the two proved steps can intersect cells this refit is about to write.
            requireCivs((at - 1..at + 1).none {
                CivsAutonomousGeometry.horizontalContains(building.bounds, plan.route[it])
            }, "autonomy-no-path")
            val lower = plan.route[at]
            val lowerFloor = lower.copy(y = lower.y - 1)
            val removedFloor = read(lower)
            if (!removedFloor.type.isAir) rejectAt("autonomy-no-path", removedFloor)
            val road = read(lowerFloor)
            if (lowerFloor !in approach.paidFloors || !CivsRoadMaintenance.surface(road.blockData.asString))
                rejectAt("autonomy-no-path", road)

            fun standing(position: CivPosition): CitizenPoint {
                val floor = read(position.copy(y = position.y - 1))
                val top = floor.collisionShape.boundingBoxes
                    .filter { .5 in it.minX..it.maxX && .5 in it.minZ..it.maxZ }.maxOfOrNull { it.maxY }
                if (top == null || !routeTerraformSupport(floor)) rejectAt("autonomy-no-path", floor)
                return CitizenPoint(position.x + .5, floor.y + top, position.z + .5)
            }
            val centre = standing(lower)
            val collision = hashMapOf<CivPosition, CitizenCollisionSnapshot>()
            fun shape(x: Int, y: Int, z: Int) = collision.getOrPut(CivPosition(x, y, z)) {
                citizenCollisionSnapshot(world, x, y, z)
            }
            for (neighbour in listOf(plan.route[at - 1], plan.route[at + 1])) {
                val beside = standing(neighbour)
                if (!citizenPacketStepClear(world, centre, beside, false, ::shape) ||
                    !citizenPacketStepClear(world, beside, centre, false, ::shape))
                    rejectAt("autonomy-no-path", road, mapOf("stage" to "wall-refit-approach"))
            }
        }

        override fun scanPlacement() {
            if (waterIndex < waterFoundations.size) {
                val f = waterFoundations[waterIndex]
                val y = waterY ?: f.bottomY
                val block = read(CivPosition(f.x, y, f.z))
                checkTerrainOwnership(CivPosition(f.x, y, f.z))
                if (!ownerCanModify(block)) rejectAt("lands-denied", block)
                if ((block.state as? InventoryHolder)?.inventory?.any { it != null && !it.type.isAir } == true)
                    rejectAt("container-not-empty", block)
                if (y == f.bottomY) {
                    if (!naturalTerraform(block) || !fullTerraformSupport(block))
                        rejectAt("autonomy-foundation", block)
                } else if (y <= f.floorY) {
                    if (if (y <= f.waterTopY) block.type != Material.WATER else !block.type.isAir)
                        rejectAt("autonomy-terrain", block)
                } else if (block.isLiquid || (block.blockData as? Waterlogged)?.isWaterlogged == true)
                    rejectAt("autonomy-terrain", block)
                if (y == f.floorY + 1) { waterIndex++; waterY = null } else waterY = y + 1
                return
            }
            terraform?.let { plan ->
                if (terrainIndex < plan.blocks.size) {
                    val cell = plan.blocks[terrainIndex]
                    val tree = treeLedgerProof(plan.blocks, terrainIndex)
                    if (tree && !treeSupportReady(cell.position)) return
                    terrainIndex++
                    checkTerraformCell(cell, plan)
                    val block = read(cell.position)
                    val after = parse(cell.after)
                    checkTerraformBlock(block, after, tree, allowUnpublished = plan.route.isEmpty())
                    // Capture the current permitted natural ground. Earlier analysis is a proposal,
                    // not an exact mutation receipt; ordinary terrain drift must not cancel the site.
                    terrainBlocks += cell.copy(before = block.blockData.asString, after = after.asString)
                    return
                }
                if (routeIndex < plan.route.size * 3) {
                    if (routeIndex % 3 == 0) wallApproach?.let { selectWallApproach(plan, it, routeIndex / 3) }
                    checkTerrainRouteCell(requireNotNull(terraform), terrainOverlay, routeIndex++)
                    return
                }
            }
            if (index == cells.size) {
                checkPreservedControl()
                loweredWallStep?.let { validateWallApproach(requireNotNull(terraform), requireNotNull(wallApproach), it) }
                requireCivs(blocks.isNotEmpty(), "invalid-plan")
                val captured = CivPlacementPlan(UUID.randomUUID(), cityId, worldId, building, blocks.toList(),
                    terrain = terraform?.copy(blocks = terrainBlocks.toList()), adminPlacement = adminPlacement)
                civsValidateWaterFoundations(captured)
                finish(captured)
                return
            }
            val cell = cells[index++]
            val position = CivPosition(cell.position.x, cell.position.y, cell.position.z)
            checkCell(position)
            // Even skipped barrier cells must belong to loaded chunks; Core skips these exact cells.
            val block = read(position)
            if (position in requiredGrass && (block.type != Material.GRASS_BLOCK ||
                    world.getHighestBlockYAt(position.x, position.z, HeightMap.MOTION_BLOCKING) != position.y))
                rejectAt("world-changed", block)
            requireCivs(position !in paidGateCells || cell.isBarrier, "invalid-plan")
            if (cell.isBarrier) {
                requireCivs(parse(cell.blockData).material == Material.BARRIER, "invalid-plan")
                if (position in paidGateCells) checkPaidGateCell(block, position in paidGateFloors)
                else if (!ownerCanModify(block)) rejectAt("lands-denied", block)
                return
            }
            if (position == building.control && parse(cell.blockData).material == Material.STRUCTURE_VOID) {
                requireCivs(!cell.isAir, "invalid-plan")
                checkControl(block)
                return
            }
            if (position == retargetSource?.control && position != building.control)
                rejectAt("hall-source-overlap", block)
            val state = if (block.type.isAir) null else block.state
            val waterFoundation = waterColumns[civsTerrainKey(position.x, position.z)]?.takeIf { it.contains(position) }
            if (waterFoundation != null) {
                requireCivs(civsPaidWaterFoundationBlock(cell.blockData), "invalid-plan")
                if (if (position.y <= waterFoundation.waterTopY) block.type != Material.WATER else !block.type.isAir)
                    rejectAt("autonomy-terrain", block)
            }
            val after = parse(cell.blockData)
            val holder = if ((constructionStorage || upgradeProposal) && state is org.bukkit.block.Container &&
                block.type.name in CIVS_STORAGE_TYPES && position !in terrainOverlay && waterFoundation == null) {
                val captured = CivPlanBlock(position, block.blockData.asString, after.asString)
                val city = currentCity()
                val accepted = acceptedHolders?.get(position) == captured
                val custody = city?.inventory?.constructionBuffers?.get(building.id.toString())
                val evacuated = custody != null && civsSameBuildingGeometry(custody.owner.target, building) &&
                    custody.parts.any { it.cell == captured }
                val acceptedEmpty = (accepted || evacuated) && !civsContainerHasItems(state)
                city != null && civsStorageChangingCell(captured) && civsConstructionStorageOwns(city, previous, building,
                    position, acceptedEmpty) && (previous?.phase != BuildingPhase.SITE || acceptedHolders == null || accepted)
            } else false
            if (!holder && strictAutonomous && waterFoundation == null && position !in terrainOverlay && (previous?.phase != BuildingPhase.ACTIVE ||
                    !previous.bounds.contains(position.x, position.y, position.z)))
                checkAutonomousTerrain(block, infrastructureGround[civsTerrainKey(position.x, position.z)] ?: building.anchor.y)
            if (block.type in PROTECTED_TERRAIN || !holder && previous?.bounds?.contains(position.x, position.y, position.z) != true &&
                state is TileState) rejectAt("protected-terrain", block)
            val retained = previous?.phase == BuildingPhase.ACTIVE && previous.bounds.contains(position.x, position.y, position.z) &&
                block.blockData.asString == after.asString && civsRetainContainerState(after.asString)
            if (!retained && !holder && civsContainerHasItems(state)) rejectAt("container-not-empty", block,
                mapOf("before" to block.blockData.asString, "after" to after.asString))
            if (!retained && !holder && currentCity()?.inventory?.containers?.contains(position) == true)
                rejectAt("registered-container", block)
            requireCivs(after.material != Material.BARRIER && cell.isAir == after.material.isAir, "invalid-plan")
            if (!ownerCanModify(block, after.material)) rejectAt("lands-denied", block)
            blocks += CivPlanBlock(position, terrainOverlay[position]?.after ?: block.blockData.asString, after.asString)
        }
    }

    class Revalidation(
        access: CivsWorld, includeColumns: (CivsWorldScan<*>, List<CivPosition>) -> Unit,
        private val plan: CivPlacementPlan, owner: UUID, private val terrainBatch: Boolean,
    ) : CivsPlacementScan<Unit>(access, includeColumns, plan.cityId, owner, plan.worldId, plan.building) {
        private val paidGateFloors = plan.terrain?.paidGateFloors.orEmpty()
        private var paidGateCursor = 0
        private val waterColumns = civsWaterFoundationColumns(building, plan.terrain?.waterFoundations.orEmpty(), plan.terrain?.blocks.orEmpty())
        private val paidWaterFloors = plan.terrain?.paidWaterFloors.orEmpty().associateBy { civsTerrainKey(it.x, it.z) }
        private val waterFoundations = if (waterColumns.isEmpty()) emptyList() else plan.blocks.mapNotNull { cell ->
            waterColumns[civsTerrainKey(cell.position.x, cell.position.z)]?.takeIf { it.contains(cell.position) }
        }.distinctBy { civsTerrainKey(it.x, it.z) }
        private var waterIndex = 0
        init {
            requireCivs(!CivsBlueprints.hasAuthoredControl(building) || plan.blocks.none { it.position == building.control }, "invalid-plan")
            civsValidateWaterFoundations(plan, complete = false)
            civsValidatePaidGateFloors(plan)
        }
        override fun validateIdentity(city: Settlement?) {
            val current = city?.building(building.id)
            val retarget = retargetSource != null && city?.construction?.id == plan.id
            requireCivs(current == null || current == building || retarget ||
                (current.type == building.type || CivsInfrastructureRebuild.refit(city, current, building)) &&
                current.entrance == building.entrance && current.rotation == building.rotation &&
                (current.level + 1 == building.level || CivsPlanBoardBlueprint.refit(current, building) ||
                    CivsInfrastructureRebuild.refit(current, building)) &&
                current.phase == BuildingPhase.ACTIVE, "invalid-building")
            if (current != null && !retarget) requireCivs(civsContainsBox(building.bounds, current.bounds), "upgrade-footprint-changed")
            validateGateReplacement(city, current)
            city?.construction?.let {
                requireCivs(it.id == plan.id && it.buildingId == building.id && it.plannedBuilding == building, "invalid-plan")
            }
        }

        override fun scanPlacement() {
            if (waterIndex < waterFoundations.size) {
                val f = waterFoundations[waterIndex++]
                val bed = read(CivPosition(f.x, f.bottomY, f.z))
                if (!ownerCanModify(bed)) rejectAt("lands-denied", bed)
                if (!naturalTerraform(bed) || !fullTerraformSupport(bed))
                    rejectAt("autonomy-foundation", bed)
                return
            }
            if (paidGateCursor < paidGateFloors.size * 3) {
                checkPaidGateCell(paidGateFloors, paidGateCursor++)
                return
            }
            if (terrainBatch || index == plan.blocks.size) {
                checkPreservedControl()
                finish(Unit)
                return
            }
            val cell = plan.blocks[index++]
            checkCell(cell.position)
            val block = read(cell.position)
            if (block.type in PROTECTED_TERRAIN) rejectAt("protected-terrain", block)
            val column = civsTerrainKey(cell.position.x, cell.position.z)
            val water = waterColumns[column]?.contains(cell.position) == true ||
                paidWaterFloors[column]?.let { cell.position.y <= it.y } == true
            if (water && block.blockData.asString != cell.before && block.blockData.asString != cell.after) rejectAt("world-changed", block)
            val retained = civsRetainedContainer(cell)
            if (retained && block.blockData.asString != cell.before) rejectAt("world-changed", block)
            if (!retained && civsContainerHasItems(block.state)) rejectAt("container-not-empty", block)
            // Players may clear or remodel a cell before this one-pass construction batch reaches it.
            val after = parse(cell.after)
            requireCivs(after.material != Material.BARRIER, "invalid-plan")
            if (!ownerCanModify(block, after.material)) rejectAt("lands-denied", block)
        }
    }
}
