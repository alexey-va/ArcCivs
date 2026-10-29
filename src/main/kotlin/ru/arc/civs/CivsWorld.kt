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
import org.bukkit.block.data.Waterlogged
import org.bukkit.block.data.type.Chest as ChestData
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSceneObservationService
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import kotlin.math.floor

import ru.arc.civs.CivsTerrainPolicy.checkAutonomousTerrain
import ru.arc.civs.CivsTerrainPolicy.naturalLeaves
import ru.arc.civs.CivsTerrainPolicy.naturalTerraform
import ru.arc.civs.CivsTerrainPolicy.fullTerraformSupport
import ru.arc.civs.CivsTerrainPolicy.routeTerraformSupport
import ru.arc.civs.CivsTerrainPolicy.passableTerraform
import ru.arc.civs.CivsTerrainPolicy.requireTerrainRoute
import ru.arc.civs.CivsTerrainPolicy.MAXIMUM_TREE_READS
import ru.arc.civs.CivsTerrainPolicy.AUTONOMOUS_SOIL
import ru.arc.civs.CivsTerrainPolicy.TREE_GROUND
import ru.arc.civs.CivsTerrainPolicy.TERRAFORM_GROUND
import ru.arc.civs.CivsTerrainPolicy.FALLING_GROUND
import ru.arc.civs.CivsTerrainPolicy.AUTONOMOUS_PLANTS
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
    private val roadWorld = CivsRoadWorld(plugin, cities, ::canModify) { closed }
    private val monitor = Any()
    private val pending = LinkedHashSet<CivsWorldScan<*>>()
    private var closed = false
    private var nextScan = 0
    private var tickSequence = 0L
    private var accessRepairChangedTick = Long.MIN_VALUE
    private var lands: CivsLandsAccess? = null
    private val timer = tasks.runTimer(1, 1, ::tick)

    fun capture(
        cityId: UUID, owner: UUID, worldId: UUID, building: CivBuilding,
        cells: List<ArcSchematicBlock>, previous: CivBuilding?, terrain: CivTerraformPlan? = null,
        adminPlacement: Boolean = false,
    ): CompletableFuture<CivPlacementPlan> = admit {
        requireCivs(cells.size in 1..CivsStore.MAX_PLAN_BLOCKS, "invalid-plan")
        Capture(cityId, owner, worldId, building, cells.toList(), previous, terrain = terrain, adminPlacement = adminPlacement)
    }

    /** A new site reserves its marker; an owned queued upgrade only records a proposal until fresh activation. */
    fun captureSite(cityId: UUID, owner: UUID, worldId: UUID, building: CivBuilding,
        cells: List<ArcSchematicBlock>, previous: CivBuilding?,
        terrain: CivTerraformPlan? = null, adminPlacement: Boolean = false): CompletableFuture<CivPlacementPlan> = admit {
        requireCivs(cells.size in 1..CivsStore.MAX_PLAN_BLOCKS, "invalid-plan")
        Capture(cityId, owner, worldId, building, cells.toList(), previous, allowControlPlacement = true,
            terrain = terrain, adminPlacement = adminPlacement, upgradeProposal = true)
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
        previous: CivBuilding? = null, autonomous: Boolean = false,
        adminPlacement: Boolean = false): CompletableFuture<CivTerraformPlan?> {
        val capture = admit {
            requireCivs(cells.size.toLong() == civsBoxVolume(building.bounds), "invalid-plan")
            ManualFoundationCapture(cityId, owner, worldId, building, cells.size, executor, previous, autonomous)
        }
        if (!adminPlacement) return capture
        val result = capture.exceptionally { failure ->
            // Optional earthworks must not relocate or refuse an explicitly selected administrator site.
            if (CivsDiagnostics.rejection(failure)?.reason !in
                setOf("autonomy-terrain", "autonomy-foundation", "autonomy-search-budget")) throw failure
            null
        }
        result.whenComplete { _, _ -> if (result.isCancelled) capture.cancel(false) }
        return result
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

    internal fun roadUpkeepPending(city: Settlement, work: CivWorldWork): Boolean = roadWorld.roadUpkeepPending(city, work)
    internal fun captureRoadUpkeep(city: Settlement, worker: CivWorker, upkeep: CivRoadUpkeep,
        registerSurface: ((UUID, CivPosition) -> Unit)? = null): CivWorldWork? =
        roadWorld.captureRoadUpkeep(city, worker, upkeep, registerSurface)
    internal fun validateRoadUpkeep(city: Settlement, work: CivWorldWork) = roadWorld.validateRoadUpkeep(city, work)

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

    /** One cheap hint per accepted Core write, including air and paired construction cells. */
    internal fun constructionChunkChanged(worldId: UUID, cell: ArcSchematicBlock) {
        check(Bukkit.isPrimaryThread()) { "Civs construction observation requires the server thread" }
        sceneChunkChanged(worldId, cell.position.x shr 4, cell.position.z shr 4)
    }

    /** Native success precedes the optional passive hint; no observer can authorize a write. */
    private fun writeObservedBlock(block: Block, after: BlockData) {
        block.setBlockData(after, false)
        sceneChunkChanged(block.world.uid, block.x shr 4, block.z shr 4)
    }

    private fun sceneChunkChanged(worldId: UUID, chunkX: Int, chunkZ: Int) {
        plugin.server.servicesManager.load(ArcSceneObservationService::class.java)?.chunkChanged(worldId, chunkX, chunkZ)
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

    internal fun scanWorld(worldId: UUID): World? = plugin.server.getWorld(worldId)
    internal fun scanCities(): Collection<Settlement> = cities()
    internal fun retireScan(scan: CivsWorldScan<*>) { synchronized(monitor) { pending.remove(scan) } }

    private fun <T> admit(create: () -> CivsWorldScan<T>): CompletableFuture<T> = synchronized(monitor) {
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

    private inner class AutonomousHeightField(private val citySnapshot: Settlement, envelope: CivBuilding,
        private val previous: CivBuilding?) : CivsWorldScan<CivTerrainSurface>(this@CivsWorld, citySnapshot.id, citySnapshot.owner, citySnapshot.worldId, envelope) {
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
        CivsWorldScan<CivTerraformPlan?>(this@CivsWorld, cityId, owner, worldId, building) {
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
        CivsWorldScan<CivTerraformPlan>(this@CivsWorld, city.id, city.owner, city.worldId, draft.building) {
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
        CivsWorldScan<Int>(this@CivsWorld, city.id, city.owner, city.worldId, envelope) {
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
        CivsWorldScan<Unit>(this@CivsWorld, city.id, city.owner, city.worldId, envelope) {
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
        CivsWorldScan<Unit>(this@CivsWorld, citySnapshot.id, citySnapshot.owner, citySnapshot.worldId, envelope) {
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

    private inner class StorageRackRepair(cityId: UUID, owner: UUID, worldId: UUID, building: CivBuilding,
        cells: List<CivPlanBlock>) : CivsWorldScan<Map<String, Int>>(this@CivsWorld, cityId, owner, worldId, building) {
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
                else { writeObservedBlock(blocks[i], targets[i]); count("changed", 1) }
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
        CivsWorldScan<Map<String, Int>>(this@CivsWorld, citySnapshot.id, citySnapshot.owner, citySnapshot.worldId, protected) {
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
            writeObservedBlock(block, after)
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
        private val adminPlacement: Boolean = false,
        private val upgradeProposal: Boolean = false,
    ) : CivsWorldScan<CivPlacementPlan>(this@CivsWorld, cityId, owner, worldId, building) {
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
            if (adminPlacement && previous?.control != building.control &&
                (block.state as? org.bukkit.block.Container)?.inventory?.isEmpty == false)
                rejectAt("container-not-empty", block)
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
            if (city?.autonomy != null && !adminPlacement) strictAutonomous = true
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
                    // Capture the current permitted natural ground. Earlier analysis is a proposal,
                    // not an exact mutation receipt; ordinary terrain drift must not cancel the site.
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
                    terrain = terraform?.copy(blocks = terrainBlocks.toList()), adminPlacement = adminPlacement))
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
            val after = parse(cell.blockData)
            val retained = previous?.phase == BuildingPhase.ACTIVE && previous.bounds.contains(position.x, position.y, position.z) &&
                block.blockData.asString == after.asString && civsRetainContainerState(after.asString)
            val proposal = upgradeProposal && previous?.phase == BuildingPhase.ACTIVE && !previous.type.isInfrastructure &&
                building.level == previous.level + 1 && previous.bounds.contains(position.x, position.y, position.z)
            // Full changing sources must be evacuated by the exact inventory receipt before this capture.
            if (!retained && !proposal && (state as? org.bukkit.block.Container)?.inventory?.isEmpty == false) rejectAt("container-not-empty", block)
            if (!retained && cities().firstOrNull { it.id == cityId }?.inventory?.containers?.contains(position) == true)
                rejectAt("registered-container", block)
            requireCivs(after.material != Material.BARRIER && cell.isAir == after.material.isAir, "invalid-plan")
            if (!canModify(owner, block, after.material)) rejectAt("lands-denied", block)
            blocks += CivPlanBlock(position, terrainOverlay[position]?.after ?: block.blockData.asString, after.asString)
        }
    }

    private inner class TerraformApply(private val plan: CivPlacementPlan, owner: UUID,
        private val cells: List<CivPlanBlock>, private val terrainStart: Int, private val canAdvance: () -> Boolean,
        private val onPlaced: (CivPlanBlock) -> Unit) : CivsWorldScan<Unit>(this@CivsWorld, plan.cityId, owner, plan.worldId, plan.building) {
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
            checkTerraformCell(cell, terraform)
            val block = read(cell.position)
            val after = parse(cell.after)
            checkTerraformBlock(block, after, acceptedPlan = true)
            val applied = sameConstructionState(block.blockData, after)
            if (!applied) {
                // No breakNaturally, inventory transfer or physics: earthwork never drops items.
                writeObservedBlock(block, after)
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
        CivsWorldScan<Unit>(this@CivsWorld, plan.cityId, owner, plan.worldId, plan.building) {
        init {
            requireCivs(!CivsBlueprints.hasAuthoredControl(building) || plan.blocks.none { it.position == building.control }, "invalid-plan")
        }
        override fun validateIdentity(city: Settlement?) {
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
            if (block.type == Material.BEDROCK) rejectAt("protected-terrain", block)
            val retained = civsRetainedContainer(cell)
            if (retained && block.blockData.asString != cell.before) rejectAt("world-changed", block)
            if (!retained && (block.state as? org.bukkit.block.Container)?.inventory?.isEmpty == false) rejectAt("container-not-empty", block)
            // Players may clear or remodel a cell before this one-pass construction batch reaches it.
            val after = parse(cell.after)
            requireCivs(after.material != Material.BARRIER, "invalid-plan")
            if (!canModify(owner, block, after.material)) rejectAt("lands-denied", block)
        }
    }

    companion object {
        const val CELLS_PER_TICK = 2048
        const val NANOS_PER_TICK = 2_000_000L
        const val MAX_SCANS = 4
        internal fun sameConstructionState(actual: BlockData, expected: BlockData): Boolean =
            CivsTerrainPolicy.sameConstructionState(actual, expected)
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

private fun requireCivs(condition: Boolean, reason: String) { if (!condition) throw CivsRejection(reason) }
