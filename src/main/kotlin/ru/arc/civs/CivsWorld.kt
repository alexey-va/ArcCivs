package ru.arc.civs

import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.flags.type.Flags
import org.bukkit.Bukkit
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.SoundCategory
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.TileState
import org.bukkit.inventory.InventoryHolder
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.Waterlogged
import org.bukkit.block.data.type.Door
import org.bukkit.block.data.type.Gate
import org.bukkit.block.data.type.Fence
import org.bukkit.block.data.type.Leaves
import org.bukkit.block.data.type.Stairs
import org.bukkit.block.data.type.Chest as ChestData
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.api.ArcSchematicBlock
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.floor

/**
 * Settlement-specific placement policy; Core remains the sole schematic mutation owner.
 * Requests may arrive on any thread. All Bukkit reads run in one lifecycle-owned timer,
 * sharing a time-bounded 2048-cell budget across at most four scans. No scan loads a chunk.
 * Explicit storage rack repair is the guarded maintenance mutation in the same native budget.
 * Final revalidation must follow publication of the durable, protected construction footprint.
 */
class CivsWorld(
    private val plugin: Plugin,
    tasks: LifecycleTaskScope,
    private val nanoTime: () -> Long = System::nanoTime,
    private val navigationSettings: CivsNavigationSettings = CivsNavigationSettings(),
    private val cities: () -> Collection<Settlement>,
) : AutoCloseable {
    private val monitor = Any()
    private val pending = LinkedHashSet<Scan<*>>()
    private var closed = false
    private var nextScan = 0
    private var tickSequence = 0L
    private var accessRepairChangedTick = Long.MIN_VALUE
    private var lands: CivsLandsAccess? = null
    private val timer = tasks.runTimer(1, 1, ::tick)

    fun capture(
        cityId: UUID, owner: UUID, worldId: UUID, building: CivBuilding,
        cells: List<ArcSchematicBlock>, previous: CivBuilding?, terrain: CivTerraformPlan? = null,
    ): CompletableFuture<CivPlacementPlan> = admit {
        requireCivs(cells.size in 1..CivsStore.MAX_PLAN_BLOCKS, "invalid-plan")
        Capture(cityId, owner, worldId, building, cells.toList(), previous, terrain = terrain)
    }

    /** A new site reserves its marker before the one-time control barrel is installed. */
    fun captureSite(cityId: UUID, owner: UUID, worldId: UUID, building: CivBuilding,
        cells: List<ArcSchematicBlock>, previous: CivBuilding?,
        terrain: CivTerraformPlan? = null): CompletableFuture<CivPlacementPlan> = admit {
        requireCivs(cells.size in 1..CivsStore.MAX_PLAN_BLOCKS, "invalid-plan")
        Capture(cityId, owner, worldId, building, cells.toList(), previous, allowControlPlacement = true, terrain = terrain)
    }

    /** Automatic admission never treats the creator's edit permission as permission to clear buildings. */
    fun captureAutonomousSite(cityId: UUID, owner: UUID, worldId: UUID, building: CivBuilding,
        cells: List<ArcSchematicBlock>, terrain: CivTerraformPlan? = null,
        previous: CivBuilding? = null): CompletableFuture<CivPlacementPlan> = admit {
        requireCivs(cells.size in 1..CivsStore.MAX_PLAN_BLOCKS, "invalid-plan")
        Capture(cityId, owner, worldId, building, cells.toList(), previous,
            allowControlPlacement = true, strictAutonomous = true, terrain = terrain)
    }

    internal fun autonomousHeightField(city: Settlement, envelope: CivBuilding,
        previous: CivBuilding? = null): CompletableFuture<CivTerrainSurface> = admit {
        AutonomousHeightField(city, envelope, previous)
    }

    internal fun captureTerraform(city: Settlement, draft: CivTerraformDraft, executor: Executor,
        previous: CivBuilding? = null): CompletableFuture<CivTerraformPlan> = admit {
        TerraformCapture(city, draft, executor, previous)
    }

    /** Manual sites receive the same journaled earthworks without requiring an existing graded road. */
    internal fun captureManualFoundation(cityId: UUID, owner: UUID, worldId: UUID, building: CivBuilding,
        cells: List<ArcSchematicBlock>, executor: Executor,
        previous: CivBuilding? = null, autonomous: Boolean = false): CompletableFuture<CivTerraformPlan?> = admit {
        requireCivs(cells.size.toLong() == civsBoxVolume(building.bounds), "invalid-plan")
        ManualFoundationCapture(cityId, owner, worldId, building, cells.size, executor, previous, autonomous)
    }

    internal fun autonomousSurface(city: Settlement, envelope: CivBuilding): CompletableFuture<Int> = admit {
        AutonomousSurface(city, envelope)
    }

    internal fun validateAutonomousSite(city: Settlement, envelope: CivBuilding): CompletableFuture<Unit> = admit {
        AutonomousTerrain(city, envelope)
    }

    internal fun autonomousRoute(city: Settlement, envelope: CivBuilding, from: CivPosition,
        executor: Executor, terrain: CivTerraformPlan? = null,
        previous: CivBuilding? = null): CompletableFuture<Unit> = admit {
        AutonomousRoute(city, envelope, from, executor, terrain, previous)
    }

    /** The service journals the batch and fences worker arrival before permitting each native write. */
    fun applyTerraform(plan: CivPlacementPlan, owner: UUID, batchBlocks: List<CivPlanBlock>, terrainStart: Int,
        canAdvance: () -> Boolean, onPlaced: (CivPlanBlock) -> Unit): CompletableFuture<Unit> = admit {
        requireCivs(batchBlocks.size in 1..16, "invalid-plan")
        TerraformApply(plan, owner, batchBlocks.toList(), terrainStart, canAdvance, onPlaced)
    }

    internal fun repairStorageRacks(city: Settlement, building: CivBuilding, cells: List<CivPlanBlock>): CompletableFuture<Map<String, Int>> = admit {
        requireCivs(cells.size in 1..512 && cells.groupBy { it.position }.values.all { alternatives ->
            alternatives.size in 1..2 && alternatives.map { it.before }.distinct().size == alternatives.size &&
                alternatives.map { it.after }.distinct().size == 1
        }, "invalid-plan")
        StorageRackRepair(city.id, city.owner, city.worldId, building, cells.toList())
    }

    /** Inverse journal cells restore an older access route; no construction credit, drops or resources. */
    internal fun repairAutonomousAccess(city: Settlement,
        repairs: List<CivAutonomousAccessRepair>): CompletableFuture<Map<String, Int>> = admit {
        requireCivs(repairs.size in 1..512 && repairs.map { it.cell.position }.distinct().size == repairs.size, "invalid-plan")
        val protected = city.building(repairs.first().protectedBuildingId) ?: throw CivsRejection("stale-building")
        AutonomousAccessRepair(city, protected, repairs.toList())
    }

    fun revalidate(plan: CivPlacementPlan, owner: UUID): CompletableFuture<Unit> = admit {
        requireCivs(plan.blocks.size in 1..CivsStore.MAX_PLAN_BLOCKS, "invalid-plan")
        Revalidation(plan.copy(blocks = plan.blocks.toList()), owner)
    }

    /** Exact owner identity, including material-specific Lands role flags. Server thread only. */
    fun canModify(owner: UUID, block: Block, placing: Material? = null): Boolean {
        check(Bukkit.isPrimaryThread()) { "Civs world access requires the server thread" }
        val provider = plugin.server.pluginManager.getPlugin("Lands") ?: return true
        if (!provider.isEnabled) return false
        return try {
            (lands ?: CivsLandsAccess(plugin).also { lands = it }).canModify(owner, block, placing)
        } catch (_: RuntimeException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    /** Mine expansion never enters a Lands claim, including an owner's or trusted member's area. */
    internal fun canMine(block: Block): Boolean {
        check(Bukkit.isPrimaryThread()) { "Civs world access requires the server thread" }
        val provider = plugin.server.pluginManager.getPlugin("Lands") ?: return true
        if (!provider.isEnabled) return false
        return try {
            (lands ?: CivsLandsAccess(plugin).also { lands = it }).unclaimed(block)
        } catch (_: RuntimeException) { false } catch (_: LinkageError) { false }
    }

    /** Exact pre-receipt probe. A healthy column never consumes another stone. */
    internal fun roadUpkeepPending(city: Settlement, work: CivWorldWork): Boolean {
        check(Bukkit.isPrimaryThread())
        val upkeep = work.upkeep ?: return false
        if (city.id != upkeep.cityId || city.worldId != upkeep.worldId || city.owner != upkeep.owner ||
            city.building(upkeep.source.id) != upkeep.source) return false
        val cell = work.changes.firstOrNull() ?: return false
        val world = plugin.server.getWorld(city.worldId) ?: return false
        if (!world.isChunkLoaded(cell.position.x shr 4, cell.position.z shr 4) ||
            cell.position.y !in world.minHeight until world.maxHeight) return false
        if (work.changes.none { it.position == upkeep.floor } &&
            !sameConstructionState(world.getBlockAt(upkeep.floor.x, upkeep.floor.y, upkeep.floor.z).blockData,
                Bukkit.createBlockData(upkeep.after))) return false
        return work.changes.all { change -> change.position.y in world.minHeight until world.maxHeight &&
            world.getBlockAt(change.position.x, change.position.y, change.position.z).blockData.asString == change.before }
    }

    /** Discovery is externally budgeted. This method never loads chunks or writes a block. */
    internal fun captureRoadUpkeep(city: Settlement, worker: CivWorker, upkeep: CivRoadUpkeep): CivWorldWork? {
        check(Bukkit.isPrimaryThread())
        val world = plugin.server.getWorld(city.worldId) ?: return null
        val p = upkeep.floor
        if (!world.isChunkLoaded(p.x shr 4, p.z shr 4) ||
            p.y !in world.minHeight + 1 until world.maxHeight - CivsRoadMaintenance.MAX_CLEARANCE) return null
        val block = world.getBlockAt(p.x, p.y, p.z)
        val after = Bukkit.createBlockData(upkeep.after)
        val change = if (sameConstructionState(block.blockData, after)) null else {
            if (!roadUpkeepReplaceable(block)) return null
            CivPlanBlock(p, block.blockData.asString, after.asString)
        }
        val cover = CivsRoadMaintenance.covers(p, (1..CivsRoadMaintenance.MAX_CLEARANCE).map { dy ->
            world.getBlockAt(p.x, p.y + dy, p.z).blockData.asString
        }, CivsRoadMaintenance.ownedPavement(city, upkeep)) ?: return null
        val changes = listOfNotNull(change) + cover
        if (changes.isEmpty()) return null
        val assignment = worker.assignment ?: return null
        for (dy in listOf(1, 0, 2, 3)) for ((dx, dz) in listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)) {
            val standing = CivPosition(p.x + dx, p.y + dy, p.z + dz)
            if (!city.contains(standing.x, standing.z) || !citizenControlClear(city, standing) ||
                !citizenStandable(world, standing) || !citizenPacketBodyClear(world,
                    CitizenPoint(standing.x + .5, standing.y.toDouble(), standing.z + .5), false)) continue
            if (civsProtectedBuildings(city).any { it.type != BuildingType.ROAD &&
                    (CivsAutonomousGeometry.horizontalContains(CivsAutonomousGeometry.futureBounds(city, it), standing) ||
                        CivsWorksiteLayouts.isField(it, standing.x, standing.z)) }) continue
            val eye = Location(world, standing.x + .5, standing.y + 1.62, standing.z + .5)
            val focus = changes.asReversed().firstOrNull { citizenCanReachBlock(world, eye, it.position) }?.position ?: continue
            val work = CivWorldWork(worker.id, worker.completedCycles, assignment, standing, changes,
                status = "repairing-road", focus = focus, upkeep = upkeep)
            try { validateRoadUpkeep(city, work); return work }
            catch (_: CivsRejection) { return null }
        }
        return null
    }

    /** Sealed receipts recheck native authority on both first application and recovery. No arrival fiction. */
    internal fun validateRoadUpkeep(city: Settlement, work: CivWorldWork) {
        check(Bukkit.isPrimaryThread())
        requireCivs(!closed, "not-ready")
        val upkeep = work.upkeep ?: throw CivsRejection("stale-worksite")
        try { upkeep.validated(city) } catch (_: IllegalArgumentException) { throw CivsRejection("stale-worksite") }
        requireCivs(city.autonomy != null && city.pendingDeletion == null, "not-authorized")
        try { CivsRoadMaintenance.validateChanges(city, upkeep, work.changes) }
        catch (_: IllegalArgumentException) { throw CivsRejection("stale-worksite") }
        val cell = work.changes.firstOrNull { it.position == upkeep.floor }
        val world = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("world-unloaded")
        fun read(p: CivPosition): Block {
            requireCivs(p.y in world.minHeight until world.maxHeight, "outside-world")
            requireCivs(world.isChunkLoaded(p.x shr 4, p.z shr 4), "chunks-unloaded")
            return world.getBlockAt(p.x, p.y, p.z)
        }
        val p = upkeep.floor
        requireCivs(world.worldBorder.isInside(Location(world, p.x + .5, p.y.toDouble(), p.z + .5)), "outside-border")
        val all = cities().filter { it.id != city.id } + city
        for (other in all) {
            if (other.worldId != city.worldId) continue
            requireCivs(other.id == city.id || !other.contains(p.x, p.z), "city-overlap")
            val positions = work.changes.map { it.position } + p
            requireCivs(positions.none { other.inventory?.containers?.contains(it) == true }, "registered-container")
            requireCivs(other.pendingControls.orEmpty().none { it.position in positions || it.approach.orEmpty().any { c -> c.position in positions } },
                "building-overlap")
            for (building in civsProtectedBuildings(other)) {
                requireCivs(building.control !in positions, "building-overlap")
                val ownRoad = other.id == city.id && building == upkeep.source && building.type == BuildingType.ROAD &&
                    building.transit.orEmpty().contains(p.copy(y = p.y + 1))
                requireCivs(ownRoad || !CivsAutonomousGeometry.horizontalContains(CivsAutonomousGeometry.futureBounds(other, building), p) &&
                    !CivsWorksiteLayouts.isField(building, p.x, p.z), "building-overlap")
            }
        }
        val block = read(p)
        val after = Bukkit.createBlockData(upkeep.after)
        val already = sameConstructionState(block.blockData, after)
        requireCivs(cell?.let { block.blockData.asString == it.before } == true || already, "world-changed")
        requireCivs(block.state !is TileState && !block.isLiquid &&
            (block.blockData as? Waterlogged)?.isWaterlogged != true, "protected-terrain")
        requireCivs(already || roadUpkeepReplaceable(block), "protected-terrain")
        // A new half step removes side support. Do not make adjacent torches, ladders or decor pop as loot.
        if (!already && (after is Stairs || after.material == Material.DIRT_PATH)) for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            val beside = read(p.copy(x = p.x + dx, z = p.z + dz))
            requireCivs(roadUpkeepSideSafe(beside), "protected-terrain")
        }
        val support = read(p.copy(y = p.y - 1))
        val retainedStepSupport = CivsRoadMaintenance.surface(support.blockData.asString) &&
            civsRetainedRouteStep(support.blockData) && support.collisionShape.boundingBoxes.any {
                it.maxY == 1.0 && it.maxX > it.minX && it.maxZ > it.minZ
            }
        requireCivs(roadUpkeepGround(support) && fullTerraformSupport(support) || retainedStepSupport, "autonomy-no-path")
        requireCivs(canModify(city.owner, block, after.material), "lands-denied")
        val cover = work.changes.filter { it.position != p }.associateBy { it.position }
        for (dy in 1..maxOf(2, cover.keys.maxOfOrNull { it.y - p.y } ?: 0)) {
            val head = read(p.copy(y = p.y + dy))
            val clear = cover[CivPosition(head.x, head.y, head.z)]
            if (clear == null) requireCivs(head.type.isAir, "world-changed")
            else {
                requireCivs(head.blockData.asString == clear.before || head.blockData.asString == clear.after, "world-changed")
                requireCivs(head.state !is TileState && !head.isLiquid &&
                    (head.blockData as? Waterlogged)?.isWaterlogged != true, "protected-terrain")
                requireCivs(canModify(city.owner, head, Material.AIR), "lands-denied")
                // Removing a bank must not spill fluid or later detach an unjournalled supported block.
                if (CivsRoadMaintenance.clearanceGround(clear.before) || CivsRoadMaintenance.surface(clear.before)) {
                    val above = head.y + 1
                    if (p.copy(y = above) !in cover) {
                        val top = read(p.copy(y = above))
                        requireCivs(top.type.isAir, "protected-terrain")
                    }
                    for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                        val beside = read(p.copy(x = p.x + dx, y = head.y, z = p.z + dz))
                        requireCivs(roadUpkeepSideSafe(beside), "protected-terrain")
                    }
                }
            }
        }

        // Stairs have two levels, not a fictional full cube. Actual neighbours retain their native shapes.
        val localAfter = roadUpkeepBoxes(if (already) block.blockData else after).map { it.at(CivPosition(0, 0, 0)) }
        val collisions = hashMapOf<CivPosition, CitizenCollisionSnapshot>()
        fun collision(x: Int, y: Int, z: Int): CitizenCollisionSnapshot = collisions.getOrPut(CivPosition(x, y, z)) {
            val at = CivPosition(x, y, z)
            val native = read(at)
            val local = if (at == p) localAfter else if (at in cover) emptyList()
                else native.collisionShape.boundingBoxes.map { it.clone() }
            CitizenCollisionSnapshot(if (at == p || at in cover) false else native.isLiquid,
                at != p && citizenRouteOpenable(native.blockData) != null, local,
                local.map { it.clone().shift(x.toDouble(), y.toDouble(), z.toDouble()) })
        }
        val top = localAfter.filter { .5 in it.minX..it.maxX && .5 in it.minZ..it.maxZ }.maxOf { it.maxY }
        val future = CitizenPoint(p.x + .5, p.y + top, p.z + .5)
        requireCivs(citizenPacketBodyClear(world, future, false, collision = ::collision), "autonomy-no-path")
        requireCivs(citizenPacketCampfireClear(world, future), "autonomy-no-path")
        val repair = upkeep.routeOverride ?: city.roadRouteOverrides?.get(upkeep.source.id.toString())
        repair?.runs()?.forEach { run ->
            val section = repair.route.subList(run.first - 1, minOf(repair.route.size, run.last + 2))
            if (section.none { abs(it.x.toLong() - p.x) <= 1L && abs(it.z.toLong() - p.z) <= 1L }) return@forEach
            if (repair.hump(run)) {
                validateRoadCut(city, upkeep, repair, run, world, all)
            } else {
                if (upkeep.routeOverride != null) {
                    for (index in run) requireCivs(read(repair.originalRoute[index].let { it.copy(y = it.y - 1) }).type.isAir, "world-changed")
                    for (feet in section) {
                        val surface = read(feet.copy(y = feet.y - 1))
                        val support = read(feet.copy(y = feet.y - 2))
                        requireCivs(surface.state !is TileState && !surface.isLiquid &&
                            (surface.blockData as? Waterlogged)?.isWaterlogged != true &&
                            (roadUpkeepGround(surface) || CivsRoadMaintenance.surface(surface.blockData.asString)), "protected-terrain")
                        requireCivs(roadUpkeepGround(support) && fullTerraformSupport(support) ||
                            CivsRoadMaintenance.surface(support.blockData.asString) && civsRetainedRouteStep(support.blockData), "autonomy-no-path")
                    }
                }
                requireCivs(roadHalfStepWalk(world, section, ::collision) != null, "autonomy-no-path")
            }
        }

        for (neighbour in upkeep.neighbours) {
            val floor = read(neighbour)
            if (!roadUpkeepGround(floor) && !civsRetainedRouteStep(floor.blockData) &&
                !CivsRoadMaintenance.landing(floor.blockData.asString)) continue
            val top = floor.collisionShape.boundingBoxes.filter { .5 in it.minX..it.maxX && .5 in it.minZ..it.maxZ }
                .maxOfOrNull { it.maxY } ?: continue
            val beside = CitizenPoint(neighbour.x + .5, neighbour.y + top, neighbour.z + .5)
            // Adjacent unfinished banks are future jobs, not mandatory traversal edges of this job.
            if (!citizenPacketBodyClear(world, beside, false)) continue
            requireCivs(citizenPacketBodyClear(world, beside, false, collision = ::collision) &&
                citizenPacketStepClear(world, beside, future, false, ::collision) &&
                citizenPacketStepClear(world, future, beside, false, ::collision), "autonomy-no-path")
        }
    }

    /** Preflight only: bounded final cut geometry never supplies navigation arrival or work credit. */
    private fun validateRoadCut(city: Settlement, upkeep: CivRoadUpkeep, repair: CivRoadRouteOverride,
        run: IntRange, world: World, settlements: List<Settlement>) {
        val section = repair.route.subList(run.first - 1, run.last + 2)
        val profile = CivsAutonomousTerraform.roadProfile(section)
        val levels = profile.mapValues { it.value.y }
        val pavement = CivsRoadMaintenance.ownedPavement(city, upkeep)
        val projected = hashMapOf<CivPosition, BlockData>()
        fun read(p: CivPosition): Block {
            requireCivs(p.y in world.minHeight until world.maxHeight, "outside-world")
            requireCivs(world.isChunkLoaded(p.x shr 4, p.z shr 4), "chunks-unloaded")
            return world.getBlockAt(p.x, p.y, p.z)
        }
        for (floor in profile.values) {
            requireCivs(city.contains(floor.x, floor.z) && world.worldBorder.isInside(
                Location(world, floor.x + .5, floor.y.toDouble(), floor.z + .5)), "outside-border")
            for (other in settlements.filter { it.worldId == city.worldId }) {
                requireCivs(other.id == city.id || !other.contains(floor.x, floor.z), "city-overlap")
                fun column(p: CivPosition) = p.x == floor.x && p.z == floor.z
                requireCivs(other.inventory?.containers.orEmpty().none(::column) &&
                    other.pendingControls.orEmpty().none { column(it.position) || it.approach.orEmpty().any { c -> column(c.position) } },
                    "building-overlap")
                requireCivs(civsProtectedBuildings(other).none {
                    CivsAutonomousGeometry.horizontalContains(CivsAutonomousGeometry.futureBounds(other, it), floor) ||
                        CivsWorksiteLayouts.isField(it, floor.x, floor.z) || it.control?.let(::column) == true
                }, "building-overlap")
            }
            val block = read(floor)
            val finish = Bukkit.createBlockData(CivsSettlementLayout.pathPavement(floor, levels, city.style))
            requireCivs(roadUpkeepReplaceable(block) || sameConstructionState(block.blockData, finish), "protected-terrain")
            requireCivs(block.state !is TileState && !block.isLiquid && (block.blockData as? Waterlogged)?.isWaterlogged != true,
                "protected-terrain")
            val support = read(floor.copy(y = floor.y - 1))
            requireCivs(roadUpkeepGround(support) && fullTerraformSupport(support), "autonomy-no-path")
            val cover = CivsRoadMaintenance.covers(floor, (1..CivsRoadMaintenance.MAX_CLEARANCE).map {
                read(floor.copy(y = floor.y + it)).blockData.asString
            }, pavement) ?: throw CivsRejection("protected-terrain")
            requireCivs(canModify(city.owner, block, finish.material), "lands-denied")
            for (change in cover) {
                val head = read(change.position)
                requireCivs(head.state !is TileState && !head.isLiquid && (head.blockData as? Waterlogged)?.isWaterlogged != true,
                    "protected-terrain")
                requireCivs(canModify(city.owner, head, Material.AIR), "lands-denied")
                val above = change.position.copy(y = change.position.y + 1)
                requireCivs(cover.any { it.position == above } || read(above).type.isAir, "protected-terrain")
                for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1))
                    requireCivs(roadUpkeepSideSafe(read(change.position.copy(x = floor.x + dx, z = floor.z + dz))), "protected-terrain")
                projected[change.position] = Bukkit.createBlockData(Material.AIR)
            }
            projected[floor] = finish
        }
        fun collision(x: Int, y: Int, z: Int): CitizenCollisionSnapshot {
            val p = CivPosition(x, y, z)
            val data = projected[p]
            val block = if (data == null) read(p) else null
            val boxes = if (data?.material?.isAir == true) emptyList()
                else if (data != null) roadUpkeepBoxes(data).map { it.at(CivPosition(0, 0, 0)) }
                else requireNotNull(block).collisionShape.boundingBoxes.map { it.clone() }
            return CitizenCollisionSnapshot(block?.isLiquid == true, block?.let { citizenRouteOpenable(it.blockData) != null } == true,
                boxes, boxes.map { it.clone().shift(x.toDouble(), y.toDouble(), z.toDouble()) })
        }
        requireCivs(roadHalfStepWalk(world, section, ::collision) != null, "autonomy-no-path")
    }

    private fun roadUpkeepGround(block: Block): Boolean =
        (block.type in TERRAFORM_GROUND || block.type == Material.DIRT_PATH || block.type == Material.COBBLESTONE) &&
            block.state !is TileState && !block.isLiquid && (block.blockData as? Waterlogged)?.isWaterlogged != true

    /** These ordinary plants attach below, never to the side of the changed column. */
    private fun roadUpkeepSideSafe(block: Block): Boolean = block.type.isAir || roadUpkeepGround(block) ||
        civsRetainedRouteStep(block.blockData) || CivsRoadMaintenance.landing(block.blockData.asString) ||
        block.type == Material.SHORT_GRASS || block.type == Material.FERN

    private fun roadUpkeepReplaceable(block: Block): Boolean = block.type.isAir || roadUpkeepGround(block) ||
        CivsRoadMaintenance.surface(block.blockData.asString) && civsRetainedRouteStep(block.blockData)

    /** Called only for Core's accepted writes; no synthetic barrel impacts or block mutations. */
    internal fun constructionFeedback(worldId: UUID, cell: ArcSchematicBlock) {
        check(Bukkit.isPrimaryThread()) { "Civs construction feedback requires the server thread" }
        if (cell.isAir || cell.isBarrier) return
        val target = plugin.server.getWorld(worldId) ?: return
        val p = cell.position
        if (p.y !in target.minHeight until target.maxHeight || !target.isChunkLoaded(p.x shr 4, p.z shr 4)) return
        val data = target.getBlockAt(p.x, p.y, p.z).blockData
        if (data.asString != cell.blockData || data.material.isAir) return
        val at = Location(target, p.x + 0.5, p.y + 0.5, p.z + 0.5)
        val sound = data.soundGroup
        target.playSound(at, sound.placeSound, SoundCategory.BLOCKS, (sound.volume * 0.4f).coerceAtMost(0.6f), sound.pitch)
        target.spawnParticle(Particle.BLOCK, at, 4, 0.20, 0.18, 0.20, 0.02, data)
    }

    override fun close() {
        val cancelled = synchronized(monitor) {
            if (closed) return
            closed = true
            pending.toList().also { pending.clear() }
        }
        timer?.cancel()
        cancelled.forEach { it.future.cancel(false) }
    }

    private fun <T> admit(create: () -> Scan<T>): CompletableFuture<T> = synchronized(monitor) {
        if (closed || timer == null) return@synchronized CompletableFuture.failedFuture(CivsRejection("world-closed"))
        pending.removeIf { it.future.isDone }
        if (pending.size >= MAX_SCANS) return@synchronized CompletableFuture.failedFuture(CivsRejection("world-busy"))
        try {
            create().also { pending += it }.future
        } catch (failure: RuntimeException) {
            CompletableFuture.failedFuture(failure)
        }
    }

    private fun tick() {
        check(Bukkit.isPrimaryThread()) { "Civs world scans require the server thread" }
        tickSequence++
        val scans = synchronized(monitor) {
            if (closed) return
            pending.removeIf { it.future.isDone }
            pending.toList()
        }
        if (scans.isEmpty()) return
        val started = nanoTime()
        scans.forEach { scan -> scan.guarded { scan.validateContext() } }
        var remaining = CELLS_PER_TICK
        // Keep the next participant across ticks even when one expensive cell exhausts the time budget.
        while (remaining > 0 && scans.any { !it.future.isDone && it.ready() }) {
            if (remaining < CELLS_PER_TICK && nanoTime() - started >= NANOS_PER_TICK) break
            val scan = scans[nextScan.mod(scans.size)]
            nextScan = (nextScan + 1).mod(scans.size)
            if (scan.future.isDone || !scan.ready()) continue
            scan.guarded { scan.step() }
            remaining--
        }
    }

    private abstract inner class Scan<T>(
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
            world = plugin.server.getWorld(worldId) ?: throw CivsRejection("world-unavailable")
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
            val all = cities()
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
            val all = cities()
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
            allowUnpublished: Boolean = false) {
            checkTerrainOwnership(CivPosition(block.x, block.y, block.z), allowUnpublished = allowUnpublished)
            val state = if (block.type.isAir) null else block.state
            if ((state as? InventoryHolder)?.inventory?.any { it != null && !it.type.isAir } == true)
                rejectAt("container-not-empty", block)
            if (block.type == Material.BEDROCK || state is TileState) rejectAt("protected-terrain", block)
            if (!naturalTerraform(block, tree)) rejectAt("autonomy-terrain", block)
            if (Tag.LOGS.isTagged(block.type)) {
                val support = treeSupports[civsTerrainKey(block.x, block.z)] ?: rejectAt("autonomy-terrain", block)
                val ground = read(support)
                if (ground.type !in TREE_GROUND || !naturalTerraform(ground) || !fullTerraformSupport(ground))
                    rejectAt("autonomy-terrain", ground)
            }
            requireCivs(after.material.isAir || after.material in TERRAFORM_GROUND, "invalid-plan")
            if (!canModify(owner, block, after.material)) rejectAt("lands-denied", block)
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

        /** Natural grass and leaf distance may advance before an admitted earthwork reaches its cell. */
        protected fun matchesTerraformBefore(expected: String, current: BlockData): Boolean {
            if (expected == current.asString) return true
            if (expected == "minecraft:dirt" && current.material == Material.GRASS_BLOCK) return true
            val before = parse(expected) as? Leaves ?: return false
            val leaves = current as? Leaves ?: return false
            if (before.material != leaves.material || before.isPersistent || leaves.isPersistent ||
                (before as? Waterlogged)?.isWaterlogged == true || (leaves as? Waterlogged)?.isWaterlogged == true) return false
            val normalized = leaves.clone() as Leaves
            normalized.distance = before.distance
            return normalized.asString == before.asString
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
            requireCivs(cities().firstOrNull { it.id == cityId }?.contains(position.x, position.z) == true, "outside-city")
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
            if (!canModify(owner, block, Material.BARREL)) rejectAt("lands-denied", block)
        }

        /** Only the preserved control needs a separate read; approach terrain is not part of construction. */
        protected fun checkPreservedControl() {
            val position = building.control ?: return
            val block = read(position)
            if (authoredControl != null) return checkControl(block)
            // Legacy controls can lie outside the old footprint and still must not replace player data.
            if (block.type != Material.BARREL && (block.state is TileState || block.type == Material.BEDROCK))
                rejectAt("control-obstructed", block)
            if (!canModify(owner, block, Material.BARREL)) rejectAt("lands-denied", block)
        }

        protected fun finish(value: T) {
            validateContext()
            synchronized(monitor) { pending.remove(this) }
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
            synchronized(monitor) { pending.remove(this) }
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

    private inner class AutonomousHeightField(private val citySnapshot: Settlement, envelope: CivBuilding,
        private val previous: CivBuilding?) : Scan<CivTerrainSurface>(citySnapshot.id, citySnapshot.owner, citySnapshot.worldId, envelope) {
        private val radius = citySnapshot.radius.coerceIn(0, 127)
        private val width = radius * 2 + 1
        private val area = width * width
        private val columns = linkedMapOf<Long, CivTerrainSurfaceColumn>()
        private var topY: Int? = null
        private var probeY = 0
        private var probes = 0
        private var leavesSeen = false

        init { requireCivs(area <= CivsAutonomousTerraform.MAXIMUM_SURFACE_COLUMNS, "autonomy-search-budget") }
        override fun validateIdentity(city: Settlement?) {
            requireCivs(city != null && city.pendingDeletion == null && city.building(building.id) == previous, "stale-building")
        }
        override fun step() {
            if (index == area) return finish(CivTerrainSurface(world.minHeight, world.maxHeight, columns))
            val x = Math.addExact(citySnapshot.center.x, index % width - radius)
            val z = Math.addExact(citySnapshot.center.z, index / width - radius)
            val key = civsTerrainKey(x, z)
            fun complete(ground: Int, natural: Boolean, loaded: Boolean = true) {
                columns[key] = CivTerrainSurfaceColumn(ground, natural, loaded, topY ?: ground)
                index++; topY = null; probes = 0; leavesSeen = false
            }
            if (!world.isChunkLoaded(x shr 4, z shr 4) ||
                !world.worldBorder.isInside(Location(world, x + .5, building.anchor.y.toDouble(), z + .5))) {
                complete(world.minHeight, false, false)
                return
            }
            if (topY == null) {
                probeY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING)
                topY = probeY
            }
            if (probeY !in world.minHeight until world.maxHeight) {
                complete(probeY, false)
                return
            }
            val block = read(CivPosition(x, probeY, z))
            probes++
            if (block.type in TERRAFORM_GROUND && naturalTerraform(block)) {
                complete(probeY, true)
                return
            }
            val leaves = naturalLeaves(block.blockData)
            if (leaves) leavesSeen = true
            val clearable = block.type.isAir || block.type in AUTONOMOUS_PLANTS || leaves ||
                leavesSeen && Tag.LOGS.isTagged(block.type)
            if (!clearable || probes >= MAXIMUM_TREE_READS) complete(probeY, false) else probeY--
        }
    }

    /** A downward probe is one queue unit; deep foundations share every other scan's tick budget. */
    private inner class ManualFoundationCapture(cityId: UUID, owner: UUID, worldId: UUID, building: CivBuilding,
        private val schematicCells: Int, private val executor: Executor, private val previous: CivBuilding?,
        private var strictAutonomous: Boolean) :
        Scan<CivTerraformPlan?>(cityId, owner, worldId, building) {
        private val width = building.bounds.max.x - building.bounds.min.x + 1
        private val footprintColumns = width * (building.bounds.max.z - building.bounds.min.z + 1)
        private val entranceOutside = !CivsAutonomousGeometry.horizontalContains(building.bounds, building.entrance)
        private val totalColumns = footprintColumns + if (entranceOutside) 1 else 0
        private val captured = linkedMapOf<CivPosition, CivTerraformCell>()
        private val columns = ArrayList<CivTerraformColumn>()
        private var columnIndex = 0
        private var y: Int? = null
        private var topY = 0
        private var leafY: Int? = null
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
                planned = CompletableFuture.supplyAsync({
                    val draft = CivTerraformDraft(building, columns, emptyList(), building.anchor.y)
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
            val floor = if (footprint) minOf(ground, building.bounds.min.y - 1) else ground
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
            if (position == building.control && !(previous?.control == position && block.type == Material.BARREL) &&
                (if (strictAutonomous) !naturalTerraform(block) else block.isLiquid ||
                    (data as? Waterlogged)?.isWaterlogged == true || block.state is TileState || block.type == Material.BEDROCK))
                rejectAt("autonomy-terrain", block)
            if (block.type in TERRAFORM_GROUND) leafY = null
            if (naturalLeaves(data)) leafY = currentY
            val tree = leafY?.let { it.toLong() - currentY in 1..MAXIMUM_TREE_READS.toLong() } == true
            // Air above the terrace stays untouched; schematic capture still validates its own cells.
            val accessFailure = if (currentY > ground && block.type.isAir) null
                else if (!canModify(owner, block)) "lands-denied"
                else try {
                    checkTerrainOwnership(position, allowUnpublished = true); null
                } catch (failure: CivsRejection) { failure.reason }
            val accessible = accessFailure == null
            val safe = naturalTerraform(block, tree) && accessible
            val solid = !Tag.LOGS.isTagged(block.type) && !naturalLeaves(data) && fullTerraformSupport(block)
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
                columnIndex++; y = null; leafY = null
            } else {
                if (currentY == world.minHeight) rejectAt("autonomy-foundation", block)
                y = currentY - 1
            }
        }
    }

    private inner class TerraformCapture(city: Settlement, private val draft: CivTerraformDraft,
        private val executor: Executor, private val previous: CivBuilding?) :
        Scan<CivTerraformPlan>(city.id, city.owner, city.worldId, draft.building) {
        private val captured = linkedMapOf<CivPosition, CivTerraformCell>()
        private val columns = draft.columns.toList()
        private val protectedAccess = draft.access.standing(city, draft.building.id)
        private var columnIndex = 0
        private var y: Int? = null
        private var leafY: Int? = null
        private var planned: CompletableFuture<CivTerraformPlan>? = null

        init {
            requireCivs(columns.size in 1..CivsAutonomousTerraform.MAXIMUM_SURFACE_COLUMNS &&
                columns.map { civsTerrainKey(it.x, it.z) }.distinct().size == columns.size, "invalid-plan")
            requireTerrainRoute(draft.route, building)
            requireCivs(columns.sumOf { (maxOf(it.surfaceY, it.groundY).toLong() + 2 -
                (it.floorY.toLong() - CivsAutonomousTerraform.MAXIMUM_CHANGE) + 1).coerceAtLeast(0) } <=
                CivsAutonomousTerraform.MAXIMUM_CAPTURE_CELLS, "autonomy-search-budget")
            future.whenComplete { _, _ -> if (future.isCancelled) planned?.cancel(false) }
        }
        override fun validateIdentity(city: Settlement?) {
            requireCivs(city != null && city.pendingDeletion == null && city.building(building.id) == previous, "stale-building")
        }
        override fun ready(): Boolean = planned?.isDone != false
        override fun step() {
            planned?.let { return finish(it.join()) } // ready() only resumes completed detached work.
            if (columnIndex == columns.size) {
                planned = CompletableFuture.supplyAsync({ CivsAutonomousTerraform.materialize(draft, captured) }, executor)
                return
            }
            val column = columns[columnIndex]
            val low = maxOf(world.minHeight, column.floorY - CivsAutonomousTerraform.MAXIMUM_CHANGE)
            val high = minOf(world.maxHeight - 1, maxOf(column.surfaceY, column.groundY) + 2)
            requireCivs(low <= high, "outside-world")
            val currentY = y ?: high
            val position = CivPosition(column.x, currentY, column.z)
            val block = read(position)
            if (block.type in TERRAFORM_GROUND) leafY = null
            if (naturalLeaves(block.blockData)) leafY = currentY
            val tree = leafY?.let { it.toLong() - currentY in 1..MAXIMUM_TREE_READS.toLong() } == true
            val safe = (column.preserve || civsTerrainKey(position.x, position.z) !in protectedAccess) &&
                naturalTerraform(block, tree) && canModify(owner, block) && try {
                checkTerrainOwnership(position); true
            } catch (_: CivsRejection) { false }
            captured[position] = CivTerraformCell(position, block.blockData.asString, safe,
                !Tag.LOGS.isTagged(block.type) && !naturalLeaves(block.blockData) &&
                    (if (column.preserve) routeTerraformSupport(block) else fullTerraformSupport(block)), passableTerraform(block))
            index++
            if (currentY == low) { columnIndex++; y = null; leafY = null } else y = currentY - 1
        }
    }

    private inner class AutonomousSurface(city: Settlement, envelope: CivBuilding) :
        Scan<Int>(city.id, city.owner, city.worldId, envelope) {
        private val width = building.bounds.max.x - building.bounds.min.x + 1
        private val area = width * (building.bounds.max.z - building.bounds.min.z + 1)
        private var low = Int.MAX_VALUE
        private var high = Int.MIN_VALUE
        override fun validateIdentity(city: Settlement?) {
            requireCivs(city != null && city.pendingDeletion == null && city.building(building.id) == null, "stale-building")
        }
        override fun step() {
            if (index == area) return finish(low)
            val x = building.bounds.min.x + index % width
            val z = building.bounds.min.z + index / width
            index++
            requireCivs(world.isChunkLoaded(x shr 4, z shr 4), "chunks-unloaded")
            // Heightmap and one block read are a fixed native unit, never a downward unbounded scan.
            val y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES)
            val surface = read(CivPosition(x, y, z))
            if (surface.type !in AUTONOMOUS_SOIL || surface.isLiquid) rejectAt("autonomy-terrain", surface)
            low = minOf(low, y); high = maxOf(high, y)
            requireCivs(high - low <= 1, "autonomy-slope")
        }
    }

    private inner class AutonomousTerrain(city: Settlement, envelope: CivBuilding) :
        Scan<Unit>(city.id, city.owner, city.worldId, envelope) {
        private val width = building.bounds.max.x - building.bounds.min.x + 1
        private val depth = building.bounds.max.z - building.bounds.min.z + 1
        private val area = width * depth
        private val volume = civsBoxVolume(building.bounds).toInt()
        private val approach = CivsAutonomousGeometry.approach(building)
        override fun validateIdentity(city: Settlement?) {
            requireCivs(city != null && city.pendingDeletion == null && city.building(building.id) == null, "stale-building")
        }
        override fun step() {
            val cursor = index++
            if (cursor < area) {
                val block = read(CivPosition(building.bounds.min.x + cursor % width, building.bounds.min.y - 1,
                    building.bounds.min.z + cursor / width))
                if (!block.type.isSolid || block.isLiquid || block.collisionShape.boundingBoxes.none { it.maxY >= 1.0 })
                    rejectAt("autonomy-terrain", block)
                return
            }
            val cell = cursor - area
            if (cell < volume) {
                val block = read(CivPosition(building.bounds.min.x + cell % width,
                    building.bounds.min.y + cell / area, building.bounds.min.z + cell / width % depth))
                checkAutonomousTerrain(block, building.anchor.y)
                if (!canModify(owner, block)) rejectAt("lands-denied", block)
                return
            }
            val entry = cell - volume
            if (entry >= approach.size * 3) return finish(Unit)
            val p = approach[entry / 3]
            val offset = entry % 3 - 1
            val block = read(p.copy(y = p.y + offset))
            if (offset == -1) {
                if (!block.type.isSolid || block.isLiquid || block.collisionShape.boundingBoxes.none { it.maxY >= 1.0 })
                    rejectAt("autonomy-terrain", block)
            } else if (!block.type.isAir && block.type !in AUTONOMOUS_PLANTS) rejectAt("autonomy-terrain", block)
        }
    }

    /** Same detached A* and collision capture as resident movement, sharing the world scan budget. */
    private inner class AutonomousRoute(private val citySnapshot: Settlement, envelope: CivBuilding,
        private val from: CivPosition, private val executor: Executor,
        private val terraform: CivTerraformPlan?, private val previous: CivBuilding?) :
        Scan<Unit>(citySnapshot.id, citySnapshot.owner, citySnapshot.worldId, envelope) {
        private var route: CitizenPacketRoute? = null
        private var search: CitizenNavigationSearch? = null
        private var searching: CompletableFuture<CitizenSearchResult>? = null
        private var capture: CitizenNavigationCapture? = null
        private val terrain = hashMapOf<CivPosition, CitizenTerrainBlock>()
        private val obstacles = citySnapshot.buildings.filter { it.id != previous?.id && !civsPublicTransit(it) }
            .map { CivsAutonomousGeometry.futureBounds(citySnapshot, it) } +
            if (civsPublicTransit(building, allowPlanned = true)) emptyList() else listOf(building.bounds)
        private var overlayIndex = 0
        private var routeIndex = 0
        private val overlay = terraform?.blocks.orEmpty().associateBy { it.position }
        private val routeColumns = terraform?.route.orEmpty().flatMap { p ->
            (-1..1).flatMap { dx -> (-1..1).map { dz -> civsTerrainKey(p.x + dx, p.z + dz) } }
        }.toSet()

        init {
            terraform?.let {
                requireTerrainRoute(it.route, building)
                requireCivs(it.route.first() == from, "invalid-plan")
            }
            future.whenComplete { _, _ -> search?.cancel() }
        }
        override fun validateIdentity(city: Settlement?) {
            requireCivs(city != null && city.pendingDeletion == null && city.building(building.id) == previous, "stale-building")
        }
        override fun ready(): Boolean = searching?.isDone != false
        override fun step() {
            index++
            terraform?.let { plan ->
                if (overlayIndex < plan.blocks.size) {
                    val cell = plan.blocks[overlayIndex]
                    val tree = treeLedgerProof(plan.blocks, overlayIndex)
                    if (tree && !treeSupportReady(cell.position)) return
                    overlayIndex++
                    checkTerraformCell(cell, plan)
                    val block = read(cell.position)
                    val after = parse(cell.after)
                    checkTerraformBlock(block, after, tree)
                    if (!matchesTerraformBefore(cell.before, block.blockData)) rejectAt("world-changed", block)
                    if (civsTerrainKey(cell.position.x, cell.position.z) in routeColumns) {
                        terrain[cell.position] = CitizenTerrainBlock(true, false, false, false, false,
                            citySnapshot.contains(cell.position.x, cell.position.z) && obstacles.none {
                                CivsAutonomousGeometry.horizontalContains(it, cell.position)
                            }, 1.0, if (after.material.isAir) emptyList() else listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)))
                    }
                    return
                }
                if (routeIndex < plan.route.size * 3) {
                    checkTerrainRouteCell(plan, overlay, routeIndex++)
                    return
                }
            }
            if (route == null) {
                val endpoint = building.entrance
                val path = CitizenPacketRoute(world, Location(world, from.x + .5, from.y.toDouble(), from.z + .5),
                    Location(world, endpoint.x + .5, endpoint.y.toDouble(), endpoint.z + .5),
                    { p -> citySnapshot.contains(p.x, p.z) && obstacles.none { CivsAutonomousGeometry.horizontalContains(it, p) } &&
                        citySnapshot.buildings.none { CivsWorksiteLayouts.isField(it, p.x, p.z) } },
                    { a, b -> citizenControlStepClear(citySnapshot, a, b) }, { 1.0 }, settings = navigationSettings)
                route = path
                search = path.newSearch()
                dispatch(emptyList())
                return
            }
            searching?.let { pending ->
                if (!pending.isDone) return
                searching = null
                val result = pending.join() // Observed complete; no gameplay-thread waiting.
                result.failure?.let {
                    throw CivsRejection(when (it) {
                        "chunk-unloaded" -> "chunks-unloaded"
                        "search-budget" -> "autonomy-search-budget"
                        else -> "autonomy-no-path"
                    })
                }
                // A movement segment does not prove access to a future building entrance.
                if (result.continuation) throw CivsRejection("autonomy-search-budget")
                if (result.path != null) return finish(Unit)
                capture = CitizenNavigationCapture(requireNotNull(route), result.probes, terrain)
            }
            val cursor = capture ?: return
            val complete = try { cursor.step() } catch (_: CitizenNavigationCaptureLimit) {
                throw CivsRejection("autonomy-search-budget")
            }
            if (complete) {
                capture = null
                dispatch(cursor.result)
            }
        }
        private fun dispatch(captured: List<CitizenSearchCapture>) {
            val solver = requireNotNull(search)
            searching = CompletableFuture.supplyAsync({ solver.advance(captured) }, executor)
        }
    }

    private fun checkAutonomousTerrain(block: Block, ground: Int) {
        if (block.type.isAir) return
        if (block.isLiquid || block.state is TileState ||
            block.type !in AUTONOMOUS_PLANTS &&
            !(block.type in AUTONOMOUS_SOIL && block.y <= ground + 1 || block.type in TERRAFORM_GROUND && block.y <= ground))
            throw CivsRejection("autonomy-terrain", mapOf("block" to block.type.name,
                "x" to block.x.toString(), "y" to block.y.toString(), "z" to block.z.toString()))
    }

    private fun naturalLeaves(data: BlockData): Boolean = (data as? Leaves)?.isPersistent == false

    private fun naturalTerraform(block: Block, tree: Boolean = false): Boolean = block.type.isAir ||
        (block.type in TERRAFORM_GROUND || block.type in AUTONOMOUS_PLANTS || naturalLeaves(block.blockData) ||
            tree && Tag.LOGS.isTagged(block.type)) && !block.isLiquid &&
        (block.blockData as? Waterlogged)?.isWaterlogged != true && block.state !is TileState

    private fun fullTerraformSupport(block: Block): Boolean = block.type.isSolid && !block.isLiquid &&
        (block.blockData as? Waterlogged)?.isWaterlogged != true && block.collisionShape.boundingBoxes.any {
            it.minX <= 0.0 && it.minZ <= 0.0 && it.maxX >= 1.0 && it.maxZ >= 1.0 &&
                it.maxY >= if (block.type in setOf(Material.DIRT_PATH, Material.FARMLAND)) 15.0 / 16.0 else 1.0
        }

    /** Retained paved steps are walkable; foundations still require a full supporting top face. */
    private fun routeTerraformSupport(block: Block): Boolean = fullTerraformSupport(block) ||
        civsRetainedRouteStep(block.blockData)

    private fun passableTerraform(block: Block): Boolean = block.type.isAir ||
        block.type in AUTONOMOUS_PLANTS && !block.isLiquid &&
        (block.blockData as? Waterlogged)?.isWaterlogged != true && block.collisionShape.boundingBoxes.isEmpty()

    private fun requireTerrainRoute(route: List<CivPosition>, building: CivBuilding, allowManualFoundation: Boolean = false) {
        if (allowManualFoundation && route.isEmpty()) return
        requireCivs(route.size in 1..CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH && route.last() == building.entrance &&
            route.all { it.y in -4_095..4_095 } &&
            route.map { civsTerrainKey(it.x, it.z) }.distinct().size == route.size, "invalid-plan")
        requireCivs(route.zipWithNext().all { (a, b) ->
            kotlin.math.abs(a.x.toLong() - b.x) + kotlin.math.abs(a.z.toLong() - b.z) == 1L &&
                kotlin.math.abs(a.y.toLong() - b.y) <= 1
        }, "autonomy-no-path")
    }

    private inner class StorageRackRepair(cityId: UUID, owner: UUID, worldId: UUID, building: CivBuilding,
        cells: List<CivPlanBlock>) : Scan<Map<String, Int>>(cityId, owner, worldId, building) {
        private val alternatives = cells.groupBy { it.position }
        private val positions = alternatives.keys.toList()
        private val counts = linkedMapOf("changed" to 0, "unchanged" to 0, "conflicts" to 0, "occupied" to 0, "denied" to 0)
        init {
            cells.forEach { cell ->
                val before = parse(cell.before)
                val after = parse(cell.after)
                val shelf = before.material in setOf(Material.BOOKSHELF, Material.CHISELED_BOOKSHELF)
                val oldChest = building.type == BuildingType.WAREHOUSE && before is ChestData &&
                    before.material == Material.CHEST && before.type == ChestData.Type.SINGLE && !before.isWaterlogged &&
                    after is ChestData && after.material == Material.CHEST && !after.isWaterlogged
                requireCivs((shelf || oldChest) && after.material in setOf(Material.CHEST, Material.BARREL, Material.AIR), "invalid-plan")
                if (after is ChestData && after.type != ChestData.Type.SINGLE) {
                    val neighbor = partner(cell.position, after)
                    val other = alternatives[neighbor]?.firstOrNull()?.after?.let(::parse) as? ChestData
                    requireCivs(other != null && other.material == after.material && other.facing == after.facing &&
                        other.type != after.type && other.type != ChestData.Type.SINGLE &&
                        partner(neighbor, other) == cell.position, "invalid-plan")
                }
            }
        }
        override fun validateIdentity(city: Settlement?) {
            requireCivs(city?.pendingDeletion == null && city?.building(building.id) == building &&
                building.phase == BuildingPhase.ACTIVE &&
                city.construction?.buildingId != building.id, "stale-building")
        }
        override fun step() {
            if (index == positions.size) return finish(counts.toMap())
            val position = positions[index++]
            if (position in seen) return
            val after = parse(alternatives.getValue(position).first().after)
            val group = if (after is ChestData && after.type != ChestData.Type.SINGLE)
                listOf(position, partner(position, after)) else listOf(position)
            group.forEach(::checkCell)
            val blocks = group.map(::read)
            val targets = group.map { parse(alternatives.getValue(it).first().after) }
            fun count(key: String, amount: Int = group.size) { counts[key] = counts.getValue(key) + amount }
            if (blocks.indices.all { blocks[it].blockData.asString == targets[it].asString }) { count("unchanged"); return }
            if (blocks.indices.any { i -> blocks[i].blockData.asString != targets[i].asString &&
                alternatives.getValue(group[i]).none { parse(it.before).asString == blocks[i].blockData.asString } }) {
                count("conflicts"); return
            }
            if (blocks.any { block ->
                val state = block.state
                val inventory = if (state is org.bukkit.block.Chest) state.blockInventory else (state as? InventoryHolder)?.inventory
                inventory?.any { it != null && !it.type.isAir } == true ||
                    (state as? InventoryHolder)?.inventory?.viewers?.isNotEmpty() == true ||
                    (state as? TileState)?.persistentDataContainer?.keys?.isNotEmpty() == true
            }) { count("occupied"); return }
            if (blocks.indices.any { i -> !canModify(owner, blocks[i], targets[i].material) ||
                targets[i] is ChestData && (hasForeignChestLink(group[i], group) ||
                    (targets[i] as ChestData).type != ChestData.Type.SINGLE &&
                        !read(group[i].copy(y = group[i].y + 1)).type.isAir) }) { count("denied"); return }
            // Both halves pass their native guards in the same step before either empty block changes.
            blocks.indices.forEach { i ->
                if (blocks[i].blockData.asString == targets[i].asString) count("unchanged", 1)
                else { blocks[i].setBlockData(targets[i], false); count("changed", 1) }
            }
        }

        private fun partner(position: CivPosition, data: ChestData): CivPosition {
            val sign = if (data.type == ChestData.Type.LEFT) 1 else -1
            return CivPosition(position.x - data.facing.modZ * sign, position.y, position.z + data.facing.modX * sign)
        }

        private fun hasForeignChestLink(position: CivPosition, group: List<CivPosition>): Boolean {
            for (face in listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)) {
                val neighbor = CivPosition(position.x + face.modX, position.y, position.z + face.modZ)
                if (neighbor in group) continue
                val data = read(neighbor).blockData as? ChestData ?: continue
                if (data.type != ChestData.Type.SINGLE && partner(neighbor, data) == position) return true
            }
            return false
        }
    }

    private inner class AutonomousAccessRepair(citySnapshot: Settlement, protected: CivBuilding,
        private val repairs: List<CivAutonomousAccessRepair>) :
        Scan<Map<String, Int>>(citySnapshot.id, citySnapshot.owner, citySnapshot.worldId, protected) {
        private val counts = linkedMapOf("changed" to 0, "unchanged" to 0, "conflicts" to 0, "denied" to 0)
        private val involved = repairs.flatMap { listOf(it.protectedBuildingId, it.offendingBuildingId) }.distinct()
            .associateWith { id -> citySnapshot.building(id) ?: throw CivsRejection("stale-building") }

        init {
            requireCivs(citySnapshot.autonomy != null && citySnapshot.pendingDeletion == null &&
                involved.values.all { it.phase == BuildingPhase.ACTIVE }, "stale-building")
            requireCivs(repairs.all { repair ->
                repair.protectedBuildingId != repair.offendingBuildingId &&
                    repair.cell.position.x == repair.standing.x && repair.cell.position.z == repair.standing.z &&
                    repair.cell.position.y.toLong() in repair.standing.y.toLong() - 1..repair.standing.y.toLong() + 1
            }, "invalid-plan")
        }

        override fun validateIdentity(city: Settlement?) {
            requireCivs(city != null && city.autonomy != null && city.pendingDeletion == null &&
                involved.all { (id, snapshot) -> city.building(id) == snapshot } &&
                city.construction?.buildingId?.let { it in involved } != true, "stale-building")
        }

        override fun ready(): Boolean = accessRepairChangedTick != tickSequence

        override fun step() {
            if (index == repairs.size) return finish(counts.toMap())
            val repair = repairs[index++]
            val cell = repair.cell
            val before = parse(cell.before)
            val after = parse(cell.after)
            fun count(key: String) { counts[key] = counts.getValue(key) + 1 }
            val support = cell.position.y.toLong() == repair.standing.y.toLong() - 1
            if (before.asString != cell.before || after.asString != cell.after ||
                !before.material.isAir && before.material !in TERRAFORM_GROUND ||
                (if (support) after.material !in TREE_GROUND else !after.material.isAir) ||
                (after as? Waterlogged)?.isWaterlogged == true) { count("denied"); return }
            val block = try {
                checkTerrainOwnership(cell.position, repair)
                read(cell.position)
            } catch (_: CivsRejection) { count("denied"); return }
            val current = block.blockData.asString
            if (current == cell.after) { count("unchanged"); return }
            if (current != cell.before) { count("conflicts"); return }
            val state = if (block.type.isAir) null else block.state
            if (state is TileState || state is InventoryHolder || block.isLiquid ||
                (block.blockData as? Waterlogged)?.isWaterlogged == true || !naturalTerraform(block) ||
                !canModify(owner, block, after.material)) { count("denied"); return }
            validateContext()
            // Exact inverse only: no breakNaturally, item transfer, physics or builder receipt.
            block.setBlockData(after, false)
            accessRepairChangedTick = tickSequence
            count("changed")
            if (index == repairs.size) finish(counts.toMap())
        }
    }

    private inner class Capture(
        cityId: UUID, owner: UUID, worldId: UUID, building: CivBuilding,
        private val cells: List<ArcSchematicBlock>, private val previous: CivBuilding?,
        private val allowControlPlacement: Boolean = false,
        private var strictAutonomous: Boolean = false,
        terrain: CivTerraformPlan? = null,
    ) : Scan<CivPlacementPlan>(cityId, owner, worldId, building) {
        private val blocks = ArrayList<CivPlanBlock>()
        private val terraform = terrain?.copy(blocks = terrain.blocks.toList(), route = terrain.route.toList())
        private val terrainOverlay = terraform?.blocks.orEmpty().associateBy { it.position }
        private val terrainBlocks = ArrayList<CivPlanBlock>()
        private val infrastructureGround = if (building.type.isInfrastructure) cells.filterNot { it.isBarrier || it.isAir }
            .groupBy { civsTerrainKey(it.position.x, it.position.z) }.mapValues { (_, values) -> values.minOf { it.position.y } }
            else emptyMap()
        private var terrainIndex = 0
        private var routeIndex = 0

        init {
            requireCivs(cells.size.toLong() == civsBoxVolume(building.bounds), "invalid-plan")
            terraform?.let {
                requireCivs(cells.size.toLong() + it.blocks.size <= CivsStore.MAX_PLAN_BLOCKS, "invalid-plan")
                requireTerrainRoute(it.route, building, allowManualFoundation = true)
                if (it.route.isNotEmpty()) strictAutonomous = true
            }
        }

        override fun checkControl(block: Block) {
            // Only an already accepted building owns this marker; an unrelated barrel is terrain.
            if (strictAutonomous && (previous == null || previous.control != building.control))
                checkControlTerrain(block)
            if (!allowControlPlacement || block.type == Material.BARREL) return super.checkControl(block)
            if (strictAutonomous) checkControlTerrain(block)
            if (block.type == Material.BEDROCK || block.state is TileState) rejectAt("control-obstructed", block)
            if (!canModify(owner, block, Material.BARREL)) rejectAt("lands-denied", block)
        }

        private fun checkControlTerrain(block: Block) {
            if (allowControlPlacement && terraform?.route.isNullOrEmpty()) {
                if (!naturalTerraform(block)) rejectAt("autonomy-terrain", block)
            } else checkAutonomousTerrain(block, building.anchor.y)
        }

        override fun validateIdentity(city: Settlement?) {
            if (city?.autonomy != null) strictAutonomous = true
            requireCivs(city?.building(building.id) == previous, "invalid-building")
            if (previous != null) requireCivs(previous.id == building.id && previous.type == building.type &&
                previous.entrance == building.entrance && previous.rotation == building.rotation &&
                (previous.phase == BuildingPhase.ACTIVE && (previous.level + 1 == building.level ||
                    CivsPlanBoardBlueprint.refit(previous, building)) ||
                    previous.level == building.level && previous.phase == BuildingPhase.SITE), "invalid-building")
            if (previous != null) requireCivs(civsContainsBox(building.bounds, previous.bounds), "upgrade-footprint-changed")
        }

        override fun step() {
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
                    if (!matchesTerraformBefore(cell.before, block.blockData)) rejectAt("world-changed", block)
                    terrainBlocks += cell.copy(before = block.blockData.asString, after = after.asString)
                    return
                }
                if (routeIndex < plan.route.size * 3) {
                    checkTerrainRouteCell(plan, terrainOverlay, routeIndex++)
                    return
                }
            }
            if (index == cells.size) {
                checkPreservedControl()
                requireCivs(blocks.isNotEmpty(), "invalid-plan")
                finish(CivPlacementPlan(UUID.randomUUID(), cityId, worldId, building, blocks.toList(),
                    terrain = terraform?.copy(blocks = terrainBlocks.toList())))
                return
            }
            val cell = cells[index++]
            val position = CivPosition(cell.position.x, cell.position.y, cell.position.z)
            checkCell(position)
            // Even skipped barrier cells must belong to loaded chunks; Core skips these exact cells.
            val block = read(position)
            if (cell.isBarrier) {
                requireCivs(parse(cell.blockData).material == Material.BARRIER, "invalid-plan")
                if (!canModify(owner, block)) rejectAt("lands-denied", block)
                return
            }
            if (position == building.control && parse(cell.blockData).material == Material.STRUCTURE_VOID) {
                requireCivs(!cell.isAir, "invalid-plan")
                checkControl(block)
                return
            }
            val state = if (block.type.isAir) null else block.state
            if (strictAutonomous && position !in terrainOverlay && (previous?.phase != BuildingPhase.ACTIVE ||
                    !previous.bounds.contains(position.x, position.y, position.z)))
                checkAutonomousTerrain(block, infrastructureGround[civsTerrainKey(position.x, position.z)] ?: building.anchor.y)
            if (block.type == Material.BEDROCK || previous?.bounds?.contains(position.x, position.y, position.z) != true &&
                state is TileState) rejectAt("protected-terrain", block)
            // Never replace a player's inventory, including chests installed while a SITE was waiting.
            if ((state as? org.bukkit.block.Container)?.inventory?.isEmpty == false) rejectAt("container-not-empty", block)
            if (cities().firstOrNull { it.id == cityId }?.inventory?.containers?.contains(position) == true)
                rejectAt("registered-container", block)
            val after = parse(cell.blockData)
            requireCivs(after.material != Material.BARRIER && cell.isAir == after.material.isAir, "invalid-plan")
            if (!canModify(owner, block, after.material)) rejectAt("lands-denied", block)
            blocks += CivPlanBlock(position, terrainOverlay[position]?.after ?: block.blockData.asString, after.asString)
        }
    }

    private inner class TerraformApply(private val plan: CivPlacementPlan, owner: UUID,
        private val cells: List<CivPlanBlock>, private val terrainStart: Int, private val canAdvance: () -> Boolean,
        private val onPlaced: (CivPlanBlock) -> Unit) : Scan<Unit>(plan.cityId, owner, plan.worldId, plan.building) {
        private val terraform = plan.terrain ?: throw CivsRejection("invalid-plan")
        private var membershipIndex = 0
        private var matched = 0
        private var admitted = false
        private var blockedTick = Long.MIN_VALUE
        private var changed = 0
        private var unchanged = 0

        init {
            requireCivs(terraform.blocks.size in 1..CivsStore.MAX_PLAN_BLOCKS &&
                plan.blocks.size.toLong() + terraform.blocks.size <= CivsStore.MAX_PLAN_BLOCKS &&
                terrainStart >= 0 && terrainStart.toLong() + cells.size <= terraform.blocks.size, "invalid-plan")
            requireTerrainRoute(terraform.route, building, allowManualFoundation = true)
        }
        override fun validateIdentity(city: Settlement?) {
            val construction = city?.construction
            requireCivs(city != null && city.pendingDeletion == null && (if (terraform.route.isEmpty())
                construction?.automaticPreparationBatches?.let { construction.workDone in 0 until it } == true
                else city.autonomy != null) &&
                construction?.let { it.id == plan.id && it.buildingId == building.id &&
                    it.plannedBuilding == building && it.phase == ConstructionPhase.PLACING } == true, "stale-construction")
        }
        override fun ready(): Boolean = blockedTick != tickSequence
        override fun step() {
            // The durable cursor selects the exact next slice; admission never rescans its completed prefix.
            if (!admitted) {
                val expected = terraform.blocks[terrainStart + membershipIndex++]
                requireCivs(expected == cells[matched++], "invalid-plan")
                admitted = matched == cells.size
                return
            }
            if (index == cells.size) return completeBatch()
            if (!canAdvance()) { blockedTick = tickSequence; return }
            validateContext()
            checkPreservedControl()
            val cell = cells[index]
            val tree = treeLedgerProof(terraform.blocks, terrainStart + index)
            if (tree && !treeSupportReady(cell.position)) return
            checkTerraformCell(cell, terraform)
            val block = read(cell.position)
            val after = parse(cell.after)
            checkTerraformBlock(block, after, tree)
            val applied = sameConstructionState(block.blockData, after)
            if (!applied && !matchesTerraformBefore(cell.before, block.blockData)) rejectAt("world-changed", block)
            if (!applied) {
                // No breakNaturally, inventory transfer or physics: earthwork never drops items.
                block.setBlockData(after, false)
                changed++
                blockedTick = tickSequence
            } else unchanged++
            index++
            onPlaced(cell)
            if (index == cells.size) completeBatch()
        }
        private fun completeBatch() {
            finish(Unit)
            CivsDiagnostics.event("terraform_native_batch", "city" to cityId, "order" to plan.id,
                "terrain_start" to terrainStart, "changed" to changed, "unchanged" to unchanged)
        }
    }

    private inner class Revalidation(private val plan: CivPlacementPlan, owner: UUID) :
        Scan<Unit>(plan.cityId, owner, plan.worldId, plan.building) {
        private var autonomous = false
        init {
            requireCivs(!CivsBlueprints.hasAuthoredControl(building) || plan.blocks.none { it.position == building.control }, "invalid-plan")
        }
        override fun validateIdentity(city: Settlement?) {
            autonomous = city?.autonomy != null
            val current = city?.building(building.id)
            requireCivs(current == null || current == building || current.type == building.type &&
                current.entrance == building.entrance && current.rotation == building.rotation &&
                (current.level + 1 == building.level || CivsPlanBoardBlueprint.refit(current, building)) &&
                current.phase == BuildingPhase.ACTIVE, "invalid-building")
            if (current != null) requireCivs(civsContainsBox(building.bounds, current.bounds), "upgrade-footprint-changed")
            city?.construction?.let {
                requireCivs(it.id == plan.id && it.buildingId == building.id && it.plannedBuilding == building, "invalid-plan")
            }
        }

        override fun step() {
            if (index == plan.blocks.size) {
                checkPreservedControl()
                finish(Unit)
                return
            }
            val cell = plan.blocks[index++]
            checkCell(cell.position)
            val block = read(cell.position)
            val actual = block.blockData
            if (autonomous && !sameConstructionState(actual, parse(cell.before)) && !sameConstructionState(actual, parse(cell.after)))
                rejectAt("autonomy-terrain", block, mapOf("before" to cell.before, "after" to cell.after, "actual" to actual.asString))
            if (block.type == Material.BEDROCK) rejectAt("protected-terrain", block)
            if ((block.state as? org.bukkit.block.Container)?.inventory?.isEmpty == false) rejectAt("container-not-empty", block)
            // Players may clear or remodel a cell before this one-pass construction batch reaches it.
            val after = parse(cell.after)
            requireCivs(after.material != Material.BARRIER, "invalid-plan")
            if (!canModify(owner, block, after.material)) rejectAt("lands-denied", block)
        }
    }

    companion object {
        /** Natural grass, opening passages and neighbour connections may change an otherwise intact cell. */
        internal fun sameConstructionState(actual: BlockData, expected: BlockData): Boolean {
            if (actual.asString == expected.asString) return true
            if (expected.material == Material.DIRT && actual.material == Material.GRASS_BLOCK) return true
            if (actual.material != expected.material) return false
            if (actual is Door && expected is Door)
                return (actual.clone() as Door).apply { isOpen = expected.isOpen }.asString == expected.asString
            if (actual is Gate && expected is Gate)
                return (actual.clone() as Gate).apply { isOpen = expected.isOpen }.asString == expected.asString
            if (actual is org.bukkit.block.data.type.Stairs && expected is org.bukkit.block.data.type.Stairs)
                return (actual.clone() as org.bukkit.block.data.type.Stairs).apply { shape = expected.shape }.asString == expected.asString
            if (actual is Fence && expected is Fence)
                return (actual.clone() as Fence).apply {
                    for (face in listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST))
                        setFace(face, expected.hasFace(face))
                }.asString == expected.asString
            return false
        }

        const val CELLS_PER_TICK = 2048
        const val NANOS_PER_TICK = 2_000_000L
        const val MAX_SCANS = 4
        private const val MAXIMUM_TREE_READS = 24
        private const val MAXIMUM_TREE_LEDGER_PROBE = 56
        private val AUTONOMOUS_SOIL = setOf(Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT,
            Material.ROOTED_DIRT, Material.PODZOL, Material.MYCELIUM, Material.MOSS_BLOCK, Material.CLAY)
        private val AUTONOMOUS_ROCK = setOf(Material.STONE, Material.DEEPSLATE, Material.GRANITE,
            Material.DIORITE, Material.ANDESITE, Material.TUFF)
        private val TREE_GROUND = AUTONOMOUS_SOIL + AUTONOMOUS_ROCK
        private val TERRAFORM_GROUND = TREE_GROUND + setOf(Material.SAND, Material.RED_SAND, Material.GRAVEL)
        private val FALLING_GROUND = setOf(Material.SAND, Material.RED_SAND, Material.GRAVEL)
        private val AUTONOMOUS_PLANTS = setOf(Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN,
            Material.LARGE_FERN, Material.DEAD_BUSH, Material.SNOW, Material.MOSS_CARPET,
            Material.LEAF_LITTER, Material.WILDFLOWERS, Material.PINK_PETALS, Material.BUSH, Material.FIREFLY_BUSH,
            Material.SHORT_DRY_GRASS, Material.TALL_DRY_GRASS, Material.CLOSED_EYEBLOSSOM, Material.OPEN_EYEBLOSSOM,
            Material.CACTUS_FLOWER, Material.PALE_MOSS_CARPET, Material.PALE_HANGING_MOSS, Material.DANDELION,
            Material.POPPY, Material.BLUE_ORCHID, Material.ALLIUM, Material.AZURE_BLUET, Material.RED_TULIP,
            Material.ORANGE_TULIP, Material.WHITE_TULIP, Material.PINK_TULIP, Material.OXEYE_DAISY,
            Material.CORNFLOWER, Material.LILY_OF_THE_VALLEY, Material.SUNFLOWER, Material.LILAC,
            Material.ROSE_BUSH, Material.PEONY)
    }
}

/** Collision classification only; replacing or supporting a road cell also requires its bounded palette and receipt. */
internal fun civsRetainedRouteStep(data: BlockData): Boolean =
    data is Stairs && data.half == Bisected.Half.BOTTOM && !data.isWaterlogged

/** Walk the actual tread contacts with a resident-width body, not a one-block jumping envelope. */
internal fun roadHalfStepWalk(world: World, route: List<CivPosition>,
    collision: ((Int, Int, Int) -> CitizenCollisionSnapshot)? = null): List<CitizenPoint>? {
    require(route.size in 2..CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH)
    val cache = hashMapOf<CivPosition, CitizenCollisionSnapshot>()
    fun state(x: Int, y: Int, z: Int): CitizenCollisionSnapshot = cache.getOrPut(CivPosition(x, y, z)) {
        collision?.invoke(x, y, z) ?: world.getBlockAt(x, y, z).let { block ->
            val boxes = block.collisionShape.boundingBoxes.map { it.clone() }
            CitizenCollisionSnapshot(block.isLiquid, citizenRouteOpenable(block.blockData) != null, boxes,
                boxes.map { it.clone().shift(x.toDouble(), y.toDouble(), z.toDouble()) })
        }
    }
    val walk = mutableListOf<CitizenPoint>()
    for ((a, b) in route.zipWithNext()) {
        require(abs(a.x.toLong() - b.x) + abs(a.z.toLong() - b.z) == 1L)
        for (index in 0..4) {
            if (walk.isNotEmpty() && index == 0) continue
            val t = index / 4.0
            val x = a.x + .5 + (b.x - a.x) * t
            val z = a.z + .5 + (b.z - a.z) * t
            val expected = a.y + (b.y - a.y) * t
            var top: Double? = null
            for (bx in floor(x - .299).toInt()..floor(x + .299).toInt())
                for (bz in floor(z - .299).toInt()..floor(z + .299).toInt()) {
                    if (!citizenChunkReady(world, bx shr 4, bz shr 4)) return null
                    for (by in floor(expected).toInt() - 2..floor(expected).toInt()) {
                        if (by !in world.minHeight until world.maxHeight) return null
                        val cell = state(bx, by, bz)
                        if (cell.liquid || cell.door) return null
                        for (box in cell.boxes) if (box.maxX > x - .299 && box.minX < x + .299 &&
                            box.maxZ > z - .299 && box.minZ < z + .299)
                            top = maxOf(top ?: box.maxY, box.maxY)
                    }
                }
            val point = CitizenPoint(x, top ?: return null, z)
            if (!citizenPacketBodyClear(world, point, false, collision = ::state)) return null
            walk.lastOrNull()?.let { before ->
                val rise = abs(point.y - before.y)
                val lower = minOf(point.y, before.y)
                val pathLip = rise <= 9.0 / 16.0 + .0001 && abs(lower - floor(lower) - 15.0 / 16.0) < .0001
                if (rise > .5001 && !pathLip || !citizenPacketStepClear(world, before, point, false, ::state) ||
                    !citizenPacketStepClear(world, point, before, false, ::state)) return null
            }
            walk += point
        }
    }
    return walk
}

/** Exact collision union for the deliberately small paving palette, including native corner shapes. */
internal fun roadUpkeepBoxes(data: BlockData): List<CitizenTerrainBox> {
    if (data.material == Material.COBBLESTONE || CivsRoadMaintenance.landing(data.asString))
        return listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0))
    if (data.material == Material.DIRT_PATH) return listOf(CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, 15.0 / 16.0, 1.0))
    require(CivsRoadMaintenance.surface(data.asString) && data is Stairs &&
        data.half == Bisected.Half.BOTTOM && !data.isWaterlogged)
    val (dx, dz) = when (data.facing) {
        BlockFace.NORTH -> 0 to -1
        BlockFace.SOUTH -> 0 to 1
        BlockFace.EAST -> 1 to 0
        BlockFace.WEST -> -1 to 0
        else -> throw IllegalArgumentException("Road stairs require a horizontal facing")
    }
    fun half(x: Int, z: Int) = CitizenTerrainBox(if (x == 1) .5 else 0.0, .5,
        if (z == 1) .5 else 0.0, if (x == -1) .5 else 1.0, 1.0, if (z == -1) .5 else 1.0)
    val front = half(dx, dz)
    val left = data.shape == Stairs.Shape.INNER_LEFT || data.shape == Stairs.Shape.OUTER_LEFT
    val side = if (left) half(dz, -dx) else half(-dz, dx)
    val base = CitizenTerrainBox(0.0, 0.0, 0.0, 1.0, .5, 1.0)
    return when (data.shape) {
        Stairs.Shape.STRAIGHT -> listOf(base, front)
        Stairs.Shape.INNER_LEFT, Stairs.Shape.INNER_RIGHT -> listOf(base, front, side)
        Stairs.Shape.OUTER_LEFT, Stairs.Shape.OUTER_RIGHT -> listOf(base, CitizenTerrainBox(
            maxOf(front.minX, side.minX), .5, maxOf(front.minZ, side.minZ),
            minOf(front.maxX, side.maxX), 1.0, minOf(front.maxZ, side.maxZ)))
    }
}

/** Loaded only when the optional Lands provider is present; no reflective integration. */
private class CivsLandsAccess(plugin: Plugin) {
    private val integration = LandsIntegration.of(plugin)
    fun unclaimed(block: Block): Boolean = integration.getWorld(block.world)?.getArea(block.location) == null
    fun canModify(owner: UUID, block: Block, placing: Material?): Boolean {
        val world = integration.getWorld(block.world) ?: return true
        val location = block.location
        if (world.getArea(location) == null) return true
        val player = integration.getLandPlayer(owner) ?: return false
        return (block.type.isAir || world.hasRoleFlag(player, location, Flags.BLOCK_BREAK, block.type, false)) &&
            (placing == null || world.hasRoleFlag(player, location, Flags.BLOCK_PLACE, placing, false))
    }
}

internal fun civsBoxVolume(box: CivBox): Long {
    val lengths = listOf(box.max.x.toLong() - box.min.x + 1, box.max.y.toLong() - box.min.y + 1, box.max.z.toLong() - box.min.z + 1)
    if (lengths.any { it !in 1..CivsStore.MAX_PLAN_BLOCKS.toLong() }) return 0
    return lengths.fold(1L) { volume, length -> (volume * length).coerceAtMost(CivsStore.MAX_PLAN_BLOCKS.toLong() + 1) }
}

internal fun civsContainsBox(outer: CivBox, inner: CivBox): Boolean =
    outer.contains(inner.min.x, inner.min.y, inner.min.z) && outer.contains(inner.max.x, inner.max.y, inner.max.z)

/** Radius separates city plans; event protection below uses building boxes only. */
internal fun civsIntersectsCity(box: CivBox, city: Settlement): Boolean =
    box.min.x.toLong() <= city.center.x.toLong() + city.radius && box.max.x.toLong() >= city.center.x.toLong() - city.radius &&
        box.min.z.toLong() <= city.center.z.toLong() + city.radius && box.max.z.toLong() >= city.center.z.toLong() - city.radius

internal fun civsProtectedBuildings(city: Settlement): Sequence<CivBuilding> = sequence {
    yieldAll(city.buildings)
    city.construction?.plannedBuilding?.let { yield(it) }
}

private fun requireCivs(condition: Boolean, reason: String) { if (!condition) throw CivsRejection(reason) }
