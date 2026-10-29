package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.TileState
import org.bukkit.inventory.InventoryHolder
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Leaves
import java.util.UUID
import java.util.concurrent.CompletableFuture

import ru.arc.civs.CivsTerrainPolicy.naturalLeaves
import ru.arc.civs.CivsTerrainPolicy.naturalTerraform
import ru.arc.civs.CivsTerrainPolicy.fullTerraformSupport
import ru.arc.civs.CivsTerrainPolicy.routeTerraformSupport
import ru.arc.civs.CivsTerrainPolicy.passableTerraform
import ru.arc.civs.CivsTerrainPolicy.MAXIMUM_TREE_READS
import ru.arc.civs.CivsTerrainPolicy.MAXIMUM_TREE_LEDGER_PROBE
import ru.arc.civs.CivsTerrainPolicy.TREE_GROUND
import ru.arc.civs.CivsTerrainPolicy.TERRAFORM_GROUND
import ru.arc.civs.CivsTerrainPolicy.AUTONOMOUS_PLANTS

/** Per-request native scan state. The host alone admits, schedules and retires scans. */
internal abstract class CivsWorldScan<T>(
    private val access: CivsWorld,
    val cityId: UUID, val owner: UUID, val worldId: UUID, val building: CivBuilding,
) {
    val future = CompletableFuture<T>()
    protected lateinit var world: World
    protected val seen = HashSet<CivPosition>()
    private val seenTerrain = HashSet<CivPosition>()
    private val treeSupports = HashMap<Long, CivPosition>()
    private var treeProbe: CivPosition? = null
    private var treeProbeDepth = 0
    private val palette = HashMap<String, BlockData>()
    private val standingColumns = HashMap<UUID, Pair<List<CivBuilding>, Map<Long, CivPosition>>>()
    private val authoredControl = building.control.takeIf { CivsBlueprints.hasAuthoredControl(building) }
    protected var index = 0
    private val startedAt = System.nanoTime()

    init {
        requireCivs(civsBoxVolume(building.bounds) in 1..CivsStore.MAX_PLAN_BLOCKS.toLong(), "invalid-building")
        requireCivs(building.entrance.y in -4_095..4_095, "invalid-building")
        authoredControl?.let { requireCivs(building.bounds.contains(it.x, it.y, it.z), "invalid-building") }
    }

    fun validateContext() {
        world = access.scanWorld(worldId) ?: throw CivsRejection("world-unavailable")
        requireCivs(building.bounds.min.y >= world.minHeight && building.bounds.max.y < world.maxHeight &&
            building.entrance.y in world.minHeight until world.maxHeight &&
            (authoredControl?.y?.let { it in world.minHeight until world.maxHeight } != false), "outside-world")
        val border = world.worldBorder
        val min = building.bounds.min
        val max = building.bounds.max
        requireCivs(border.isInside(Location(world, min.x.toDouble(), min.y.toDouble(), min.z.toDouble())) &&
            border.isInside(Location(world, max.x + 1.0 - 1e-6, max.y.toDouble(), max.z + 1.0 - 1e-6)) &&
            border.isInside(Location(world, building.entrance.x + 0.5, building.entrance.y.toDouble(), building.entrance.z + 0.5)) &&
            (authoredControl?.let { border.isInside(Location(world, it.x + 0.5, it.y.toDouble(), it.z + 0.5)) } != false),
            "outside-border")
        val all = access.scanCities()
        val city = all.firstOrNull { it.id == cityId }
        requireCivs(city == null || city.owner == owner && city.worldId == worldId, "not-authorized")
        requireCivs(city != null || building.type == BuildingType.TOWN_HALL && building.level == 1, "not-authorized")
        if (city != null) {
            requireCivs(city.contains(min.x, min.z) && city.contains(max.x, max.z) &&
                city.contains(building.entrance.x, building.entrance.z), "outside-city")
        }
        requireCivs(all.none { it.id != cityId && it.worldId == worldId && civsIntersectsCity(building.bounds, it) }, "city-overlap")
        requireCivs(all.filter { it.worldId == worldId }.none { other ->
            civsProtectedBuildings(other).any {
                (other.id != cityId || it.id != building.id) && (it.bounds.overlaps(building.bounds) ||
                    it.control?.let { p -> building.bounds.contains(p.x, p.y, p.z) || p == building.control } == true ||
                    building.control?.let { p -> it.bounds.contains(p.x, p.y, p.z) } == true)
            } || other.pendingControls.orEmpty().any { receipt ->
                (other.id != cityId || receipt.buildingId != building.id) && receipt.approach.orEmpty().any { cell ->
                    building.bounds.contains(cell.position.x, cell.position.y, cell.position.z) || cell.position == building.control
                }
            }
        }, "building-overlap")
        validateIdentity(city)
    }

    abstract fun validateIdentity(city: Settlement?)
    abstract fun step()
    open fun ready(): Boolean = true

    protected fun read(position: CivPosition): Block {
        requireCivs(position.y in world.minHeight until world.maxHeight, "outside-world")
        requireCivs(world.isChunkLoaded(position.x shr 4, position.z shr 4), "chunks-unloaded")
        return world.getBlockAt(position.x, position.y, position.z)
    }

    /** Terrain extends beyond the schematic; check the exact position rather than its broad route box. */
    protected fun checkTerrainOwnership(position: CivPosition, repair: CivAutonomousAccessRepair? = null,
        allowUnpublished: Boolean = false) {
        requireCivs(position.y in world.minHeight until world.maxHeight, "outside-world")
        requireCivs(world.worldBorder.isInside(Location(world, position.x + .5, position.y.toDouble(), position.z + .5)),
            "outside-border")
        val all = access.scanCities()
        val city = all.firstOrNull { it.id == cityId }
        requireCivs(city != null || allowUnpublished && building.type == BuildingType.TOWN_HALL && building.level == 1,
            "not-authorized")
        if (city != null) {
            requireCivs(city.owner == owner && city.worldId == worldId && city.pendingDeletion == null, "not-authorized")
            requireCivs(city.contains(position.x, position.z), "outside-city")
        }
        requireCivs(position != building.control, "protected-terrain")
        for (other in all) {
            if (other.worldId != worldId) continue
            requireCivs(other.id == cityId || !other.contains(position.x, position.z), "city-overlap")
            requireCivs(other.inventory?.containers?.contains(position) != true, "registered-container")
            for (occupied in civsProtectedBuildings(other)) {
                requireCivs(position != occupied.control, "building-overlap")
                val retained = other.id != cityId || occupied.id != building.id || occupied.phase == BuildingPhase.ACTIVE
                requireCivs(!retained || !occupied.bounds.contains(position.x, position.y, position.z) &&
                    !CivsWorksiteLayouts.isField(occupied, position.x, position.z), "building-overlap")
            }
            val repairedPair = repair?.takeIf { other.id == cityId && position == it.cell.position }
            val approachCity = if (repairedPair == null) other else other.copy(buildings = other.buildings.filter {
                it.id != repairedPair.protectedBuildingId && it.id != repairedPair.offendingBuildingId
            })
            val standing = if (repairedPair == null) retainedStanding(other)
                else CivAutonomousAccess().standing(approachCity, repairedPair.protectedBuildingId)
            requireCivs(civsTerrainKey(position.x, position.z) !in standing, "building-overlap")
            requireCivs(other.pendingControls.orEmpty().none { receipt ->
                receipt.position == position || (other.id != cityId || receipt.buildingId != building.id) &&
                    receipt.approach.orEmpty().any { it.position == position }
            }, "building-overlap")
        }
    }

    /** Only detached building lists are cached; a published geometry change invalidates its city's entry. */
    private fun retainedStanding(city: Settlement): Map<Long, CivPosition> {
        standingColumns[city.id]?.let { if (it.first === city.buildings) return it.second }
        return CivAutonomousAccess().standing(city, building.id).also {
            standingColumns[city.id] = city.buildings to it
        }
    }

    protected fun checkTerraformBlock(block: Block, after: BlockData, tree: Boolean = false,
        allowUnpublished: Boolean = false, acceptedPlan: Boolean = false) {
        checkTerrainOwnership(CivPosition(block.x, block.y, block.z), allowUnpublished = allowUnpublished)
        val state = if (block.type.isAir) null else block.state
        if ((state as? InventoryHolder)?.inventory?.any { it != null && !it.type.isAir } == true)
            rejectAt("container-not-empty", block)
        if (block.type == Material.BEDROCK || state is TileState) rejectAt("protected-terrain", block)
        if (!acceptedPlan && !naturalTerraform(block, tree)) rejectAt("autonomy-terrain", block)
        if (!acceptedPlan && Tag.LOGS.isTagged(block.type)) {
            val support = treeSupports[civsTerrainKey(block.x, block.z)] ?: rejectAt("autonomy-terrain", block)
            val ground = read(support)
            if (ground.type !in TREE_GROUND || !naturalTerraform(ground) || !fullTerraformSupport(ground))
                rejectAt("autonomy-terrain", ground)
        }
        requireCivs(after.material.isAir || after.material in TERRAFORM_GROUND, "invalid-plan")
        if (!access.canModify(owner, block, after.material)) rejectAt("lands-denied", block)
    }

    /** Nearby original canopy evidence survives top-down cuts, bottom-up fill and a partial crash. */
    protected fun treeLedgerProof(cells: List<CivPlanBlock>, cursor: Int): Boolean {
        val cell = cells[cursor]
        if (!Tag.LOGS.isTagged(parse(cell.before).material)) return false
        for (i in maxOf(0, cursor - MAXIMUM_TREE_LEDGER_PROBE)..minOf(cells.lastIndex, cursor + MAXIMUM_TREE_LEDGER_PROBE)) {
            val above = cells[i]
            if (above.position.x != cell.position.x || above.position.z != cell.position.z) continue
            val rise = above.position.y.toLong() - cell.position.y
            val after = parse(above.after).material
            if (rise !in 1..MAXIMUM_TREE_READS.toLong() || !after.isAir && after !in TERRAFORM_GROUND) continue
            val leaves = parse(above.before) as? Leaves
            if (leaves != null && !leaves.isPersistent) return true
        }
        return false
    }

    /** One support probe per queue unit; no downward native loop on a write. */
    protected fun treeSupportReady(position: CivPosition): Boolean {
        val key = civsTerrainKey(position.x, position.z)
        treeSupports[key]?.let { if (it.y < position.y) return true else treeSupports.remove(key) }
        if (treeProbe != position) { treeProbe = position; treeProbeDepth = 0 }
        requireCivs(treeProbeDepth < MAXIMUM_TREE_READS, "autonomy-terrain")
        val below = read(position.copy(y = position.y - ++treeProbeDepth))
        if (below.type in TREE_GROUND && naturalTerraform(below) && fullTerraformSupport(below)) {
            treeSupports[key] = CivPosition(below.x, below.y, below.z)
            return true
        }
        if (!below.type.isAir && below.type !in AUTONOMOUS_PLANTS && !Tag.LOGS.isTagged(below.type) &&
            !naturalLeaves(below.blockData)) rejectAt("autonomy-terrain", below)
        return false
    }

    protected fun checkTerraformCell(cell: CivPlanBlock, terrain: CivTerraformPlan) {
        requireCivs(terrain.bounds.contains(cell.position.x, cell.position.y, cell.position.z) &&
            seenTerrain.add(cell.position) && cell.position != building.control, "invalid-plan")
        val source = terrain.route.firstOrNull()
        if (source == null) {
            val p = cell.position
            val footprint = CivsAutonomousGeometry.horizontalContains(building.bounds, p)
            val entrance = p.x == building.entrance.x && p.z == building.entrance.z
            val ground = if (entrance && !footprint) building.entrance.y - 1 else building.anchor.y
            requireCivs(footprint || entrance, "invalid-plan")
            requireCivs(if (parse(cell.after).material.isAir) p.y > ground
                else cell.after == "minecraft:dirt" && p.y <= ground, "invalid-plan")
        } else requireCivs(cell.before == cell.after || cell.position.x != source.x || cell.position.z != source.z ||
            cell.position.y !in source.y - 1..source.y + 1, "autonomy-no-path")
        requireCivs(parse(cell.before).asString == cell.before && parse(cell.after).asString == cell.after &&
            (parse(cell.after).material.isAir || parse(cell.after).material in TERRAFORM_GROUND), "invalid-plan")
    }

    /** Standing coordinates are proven against the future earthworks, never a teleported endpoint. */
    protected fun checkTerrainRouteCell(terrain: CivTerraformPlan, overlay: Map<CivPosition, CivPlanBlock>, cursor: Int) {
        val standing = terrain.route[cursor / 3]
        val offset = cursor % 3 - 1
        val position = standing.copy(y = standing.y + offset)
        val block = read(position)
        requireCivs(world.worldBorder.isInside(Location(world, position.x + .5, position.y.toDouble(), position.z + .5)),
            "outside-border")
        requireCivs(access.scanCities().firstOrNull { it.id == cityId }?.contains(position.x, position.z) == true, "outside-city")
        val change = overlay[position]
        if (cursor < 3) requireCivs(change == null || change.before == change.after, "autonomy-no-path")
        val after = change?.let { parse(it.after) }
        if (offset == -1) {
            val support = if (after == null) routeTerraformSupport(block) else after.material in TERRAFORM_GROUND
            if (!support) rejectAt("autonomy-no-path", block)
        } else {
            val clear = if (after == null) passableTerraform(block) else after.material.isAir
            if (!clear) rejectAt("autonomy-no-path", block)
        }
    }

    protected fun rejectAt(reason: String, block: Block, states: Map<String, String> = emptyMap()): Nothing = throw CivsRejection(reason, mapOf(
        "block" to block.type.name,
        "x" to block.x.toString(), "y" to block.y.toString(), "z" to block.z.toString(),
        "world" to block.world.uid.toString(), "world_name" to block.world.name,
    ) + states)

    protected fun checkCell(position: CivPosition) {
        requireCivs(building.bounds.contains(position.x, position.y, position.z) && seen.add(position), "invalid-plan")
    }

    protected fun parse(text: String): BlockData = palette.getOrPut(text) {
        requireCivs(text.length in 1..CivsStore.MAX_BLOCK_STATE_CHARS, "invalid-plan")
        try { Bukkit.createBlockData(text) } catch (_: IllegalArgumentException) { throw CivsRejection("invalid-plan") }
    }

    protected open fun checkControl(block: Block) {
        if (block.type != Material.BARREL) rejectAt("control-position-missing", block)
        if (!access.canModify(owner, block, Material.BARREL)) rejectAt("lands-denied", block)
    }

    /** Only the preserved control needs a separate read; approach terrain is not part of construction. */
    protected fun checkPreservedControl() {
        val position = building.control ?: return
        val block = read(position)
        if (authoredControl != null) return checkControl(block)
        // Legacy controls can lie outside the old footprint and still must not replace player data.
        if (block.type != Material.BARREL && (block.state is TileState || block.type == Material.BEDROCK))
            rejectAt("control-obstructed", block)
        if (!access.canModify(owner, block, Material.BARREL)) rejectAt("lands-denied", block)
    }

    protected fun finish(value: T) {
        validateContext()
        access.retireScan(this)
        CivsDiagnostics.event("world_scan", "city" to cityId, "building" to building.id,
            "phase" to javaClass.simpleName.lowercase(), "cells" to index,
            "elapsed_ms" to (System.nanoTime() - startedAt) / 1_000_000, "outcome" to "completed")
        future.complete(value)
    }

    fun guarded(action: () -> Unit) {
        if (future.isDone) return
        try { action() } catch (failure: Exception) {
            fail(failure)
        } catch (failure: LinkageError) {
            fail(failure)
        }
    }

    private fun fail(failure: Throwable) {
        access.retireScan(this)
        val rejection = CivsDiagnostics.rejection(failure)
        CivsDiagnostics.event("world_scan", "city" to cityId, "building" to building.id,
            "phase" to javaClass.simpleName.lowercase(), "cells" to index,
            "elapsed_ms" to (System.nanoTime() - startedAt) / 1_000_000,
            "outcome" to if (rejection == null) "failed" else "rejected",
            "reason" to (rejection?.reason ?: CivsDiagnostics.cause(failure)?.javaClass?.simpleName),
            "detail" to rejection?.values)
        future.completeExceptionally(failure)
    }
}


private fun requireCivs(condition: Boolean, reason: String) { if (!condition) throw CivsRejection(reason) }
