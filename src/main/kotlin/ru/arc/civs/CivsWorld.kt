package ru.arc.civs

import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.flags.type.Flags
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.SoundCategory
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
import ru.arc.paper.chunk.PaperChunkKey
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import ru.arc.civs.CivsTerrainPolicy.naturalTerraform
import ru.arc.civs.CivsTerrainPolicy.fullTerraformSupport
import ru.arc.civs.CivsTerrainPolicy.requireTerrainRoute
import ru.arc.civs.CivsTerrainPolicy.TREE_GROUND
import ru.arc.civs.CivsTerrainPolicy.terraformMaterial

/**
 * Settlement-specific placement policy; Core remains the sole schematic mutation owner.
 * Requests may arrive on any thread. Native scan steps run in one lifecycle-owned timer,
 * sharing a time-bounded 2048-cell budget across at most four scans. The existing chunk
 * lifecycle prepares each scan's temporary footprint before its first native read.
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
    internal var onTerrainChanged: (UUID, Collection<CivPosition>) -> Unit = { _, _ -> }
    internal var scanChunks: ((UUID, UUID, Set<PaperChunkKey>) -> CivsChunkDemand)? = null
    private val roadWorld = CivsRoadWorld(plugin, cities, ::canModify) { closed }
    private val monitor = Any()
    private val pending = LinkedHashSet<CivsWorldScan<*>>()
    private val scanFootprints = mutableMapOf<CivsWorldScan<*>, Set<PaperChunkKey>>()
    private val scanDemands = mutableMapOf<CivsWorldScan<*>, CivsChunkDemand>()
    private var closed = false
    private var nextScan = 0
    private var tickSequence = 0L
    private var accessRepairChangedTick = Long.MIN_VALUE
    private var lands: CivsLandsAccess? = null
    private val timer = tasks.runTimer(1, 1, ::tick)

    fun capture(
        cityId: UUID, owner: UUID, worldId: UUID, building: CivBuilding,
        cells: List<ArcSchematicBlock>, previous: CivBuilding?, terrain: CivTerraformPlan? = null,
        adminPlacement: Boolean = false, constructionStorage: Boolean = false, acceptedStorage: List<CivPlanBlock>? = null,
    ): CompletableFuture<CivPlacementPlan> = admit(terrainPoints(terrain)) {
        requireCivs(cells.size in 1..CivsStore.MAX_PLAN_BLOCKS, "invalid-plan")
        CivsPlacementScan.Capture(this, ::includeScanColumns, cityId, owner, worldId, building, cells.toList(), previous,
            terrain = terrain, adminPlacement = adminPlacement, constructionStorage = constructionStorage,
            acceptedStorage = acceptedStorage)
    }

    /** A new site reserves its marker; an owned queued upgrade only records a proposal until fresh activation. */
    fun captureSite(cityId: UUID, owner: UUID, worldId: UUID, building: CivBuilding,
        cells: List<ArcSchematicBlock>, previous: CivBuilding?,
        terrain: CivTerraformPlan? = null, adminPlacement: Boolean = false): CompletableFuture<CivPlacementPlan> = admit(terrainPoints(terrain)) {
        requireCivs(cells.size in 1..CivsStore.MAX_PLAN_BLOCKS, "invalid-plan")
        CivsPlacementScan.Capture(this, ::includeScanColumns, cityId, owner, worldId, building, cells.toList(), previous,
            allowControlPlacement = true, terrain = terrain, adminPlacement = adminPlacement, upgradeProposal = true)
    }

    internal fun captureWallRefit(building: CivBuilding, cells: List<ArcSchematicBlock>, previous: CivBuilding,
        terrain: CivTerraformPlan, approach: CivWallRefitApproach, proposal: Boolean,
        acceptedStorage: List<CivPlanBlock>? = null): CompletableFuture<CivPlacementPlan> =
        admit(terrainPoints(terrain)) {
            val city = approach.city
            requireCivs(cells.size in 1..CivsStore.MAX_PLAN_BLOCKS, "invalid-plan")
            CivsPlacementScan.Capture(this, ::includeScanColumns, city.id, city.owner, city.worldId, building, cells.toList(), previous,
                allowControlPlacement = proposal, terrain = terrain, upgradeProposal = proposal, wallApproach = approach,
                constructionStorage = !proposal, acceptedStorage = acceptedStorage)
        }

    /** Automatic admission never treats the creator's edit permission as permission to clear buildings. */
    fun captureAutonomousSite(cityId: UUID, owner: UUID, worldId: UUID, building: CivBuilding,
        cells: List<ArcSchematicBlock>, terrain: CivTerraformPlan? = null,
        previous: CivBuilding? = null): CompletableFuture<CivPlacementPlan> = admit(terrainPoints(terrain)) {
        requireCivs(cells.size in 1..CivsStore.MAX_PLAN_BLOCKS, "invalid-plan")
        CivsPlacementScan.Capture(this, ::includeScanColumns, cityId, owner, worldId, building, cells.toList(), previous,
            allowControlPlacement = true, strictAutonomous = true, terrain = terrain, constructionStorage = true)
    }

    /** Only explicitly requested columns survey water; ordinary building searches retain their dry-only scan. */
    internal fun autonomousHeightField(city: Settlement, envelope: CivBuilding,
        previous: CivBuilding? = null, waterColumns: Set<Long> = emptySet(),
        localFootprint: Boolean = false, markWaterTop: Boolean = false): CompletableFuture<CivTerrainSurface> =
        admit(area = if (localFootprint) null else surveyArea(city)) {
        requireCivs(waterColumns.size <= CivsAutonomousTerraform.MAXIMUM_SURFACE_COLUMNS, "autonomy-search-budget")
        // Keep an owned snapshot without rebuilding packed coordinates in a boxed Long hash set.
        AutonomousHeightField(this, city, envelope, previous, LongOpenHashSet(waterColumns), localFootprint = localFootprint,
            markWaterTop = markWaterTop)
    }

    /** Repeat only the selected tops with the ordinary bounded probe, without loading new chunks. */
    internal fun autonomousShoreField(city: Settlement, hall: CivBuilding,
        columns: List<CivPosition>): CompletableFuture<CivTerrainSurface> {
        if (columns.size !in 1..CivsSettlementPerimeter.MAXIMUM_SHORE_COLUMNS)
            return CompletableFuture.failedFuture(CivsRejection("autonomy-search-budget"))
        val samples = columns.toList()
        val keys = samples.mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }
        return admit(loadedOnly = true) {
            requireCivs(city.building(hall.id) == hall && hall.type == BuildingType.TOWN_HALL &&
                hall.phase == BuildingPhase.ACTIVE && city.pendingDeletion == null, "stale-building")
            requireCivs(keys.size == samples.size && samples.all { city.contains(it.x, it.z) }, "autonomy-search-budget")
            AutonomousHeightField(this, city, hall, hall, keys, requested = samples, markWaterTop = true)
        }
    }

    /** Administrative discovery retains the same explicit bounded survey footprint until its result retires. */
    internal fun prospectiveHeightField(city: Settlement, envelope: CivBuilding,
        columns: List<CivPosition>? = null): CompletableFuture<CivTerrainSurface> {
        val samples = columns?.toList()
        return admit(area = if (samples == null) surveyArea(city) else null, sampledColumns = samples) {
            requireCivs(city.autonomy != null && city.buildings.singleOrNull()?.id == envelope.id &&
                envelope.type == BuildingType.TOWN_HALL && envelope.level == 1, "invalid-building")
            samples?.let { requested ->
                requireCivs(requested.size in 1..CivsAutonomousTerraform.MAXIMUM_SURFACE_COLUMNS &&
                    requested.map { it.x to it.z }.distinct().size == requested.size &&
                    requested.all { city.contains(it.x, it.z) }, "autonomy-search-budget")
            }
            AutonomousHeightField(this, city, envelope, null, emptySet(), prospective = true, requested = samples)
        }
    }

    internal fun captureTerraform(city: Settlement, draft: CivTerraformDraft, executor: Executor,
        previous: CivBuilding? = null): CompletableFuture<CivTerraformPlan> = admit(
        (draft.columns.asSequence().map { CivPosition(it.x, it.floorY, it.z) } + draft.route.asSequence()).asIterable()) {
        TerraformCapture(this, city, draft, executor, previous)
    }

    /** Manual sites receive the same journaled earthworks without requiring an existing graded road. */
    internal fun captureManualFoundation(cityId: UUID, owner: UUID, worldId: UUID, building: CivBuilding,
        cells: List<ArcSchematicBlock>, executor: Executor,
        previous: CivBuilding? = null, autonomous: Boolean = false,
        adminPlacement: Boolean = false): CompletableFuture<CivTerraformPlan?> {
        val capture = admit {
            requireCivs(cells.size.toLong() == civsBoxVolume(building.bounds), "invalid-plan")
            ManualFoundationCapture(this, cityId, owner, worldId, building, cells.size, executor, previous, autonomous)
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
        AutonomousSurface(this, city, envelope)
    }

    internal fun validateAutonomousSite(city: Settlement, envelope: CivBuilding): CompletableFuture<Unit> = admit {
        AutonomousTerrain(this, city, envelope)
    }

    internal fun autonomousRoute(city: Settlement, envelope: CivBuilding, from: CivPosition,
        executor: Executor, terrain: CivTerraformPlan? = null,
        previous: CivBuilding? = null): CompletableFuture<Unit> =
        admit(terrainPoints(terrain) + from, CivsChunkArea.of(city)) {
        AutonomousRoute(this, city, envelope, from, executor, terrain, previous, navigationSettings)
    }

    /** The service journals the batch and fences worker arrival before permitting each native write. */
    fun applyTerraform(plan: CivPlacementPlan, owner: UUID, batchBlocks: List<CivPlanBlock>, terrainStart: Int,
        canAdvance: () -> Boolean, onPlaced: (CivPlanBlock) -> Unit): CompletableFuture<Unit> =
        admit(terrainPoints(plan.terrain) + batchBlocks.map { it.position }) {
        requireCivs(batchBlocks.size in 1..16, "invalid-plan")
        TerraformApply(plan, owner, batchBlocks.toList(), terrainStart, canAdvance, onPlaced)
    }

    internal fun repairStorageRacks(city: Settlement, building: CivBuilding, cells: List<CivPlanBlock>): CompletableFuture<Map<String, Int>> =
        admit(cells.map { it.position }) {
        requireCivs(cells.size in 1..512 && cells.groupBy { it.position }.values.all { alternatives ->
            alternatives.size in 1..2 && alternatives.map { it.before }.distinct().size == alternatives.size &&
                alternatives.map { it.after }.distinct().size == 1
        }, "invalid-plan")
        StorageRackRepair(city.id, city.owner, city.worldId, building, cells.toList())
    }

    /** Read-only mouth capture. The inventory journal, never this scan, owns the eventual native cuts. */
    internal fun captureMineOpening(city: Settlement, building: CivBuilding,
        initialBlock: (CivPosition, String) -> Boolean): CompletableFuture<List<CivPlanBlock>> =
        admit(CivsMineOpening.positions(building), loadedOnly = true) {
            MineOpeningCapture(city, building, initialBlock)
        }

    /** The shared scan budget captures exact native before-images; only the durable journal may place them. */
    internal fun captureAuthoredVegetation(city: Settlement, building: CivBuilding,
        cells: List<CivPlanBlock>): CompletableFuture<List<CivPlanBlock>> = admit(cells.map { it.position }, loadedOnly = true) {
        requireCivs(cells.size in 1..CivAuthoredVegetationReceipt.MAX_SOURCE_CELLS &&
            cells.map { it.position }.distinct().size == cells.size && cells.all { CivAuthoredVegetationReceipt.tree(it.after) }, "invalid-plan")
        AuthoredVegetationCapture(city, building, cells.toList())
    }

    internal fun checkAuthoredVegetationCell(city: Settlement, building: CivBuilding, block: Block, after: Material) {
        val point = CivPosition(block.x, block.y, block.z)
        requireCivs(building.bounds.contains(point.x, point.y, point.z) && city.contains(point.x, point.z) &&
            block.world.worldBorder.isInside(block.location), "outside-city")
        requireCivs(scanCities().filter { it.worldId == city.worldId }.none { other ->
            other.id != city.id && other.contains(point.x, point.z) ||
                other.inventory?.containers?.contains(point) == true ||
                civsProtectedBuildings(other).any { protected ->
                    protected.control == point || (other.id != city.id || protected.id != building.id) &&
                        protected.bounds.contains(point.x, point.y, point.z)
                } || other.pendingControls.orEmpty().any { pending -> pending.position == point ||
                    (other.id != city.id || pending.buildingId != building.id) && pending.approach.orEmpty().any { it.position == point }
                }
        }, "building-overlap")
        requireCivs(block.getState(false) !is TileState && !block.isLiquid &&
            (block.blockData as? Waterlogged)?.isWaterlogged != true, "vegetation-obstructed")
        requireCivs(canModify(city.owner, block, after), "lands-denied")
    }

    internal fun checkMineOpeningCell(city: Settlement, building: CivBuilding, block: Block, initial: Boolean) {
        val point = CivPosition(block.x, block.y, block.z)
        requireCivs(city.contains(point.x, point.z) && block.world.worldBorder.isInside(block.location), "outside-city")
        requireCivs(scanCities().filter { it.worldId == city.worldId }.none { other ->
            other.id != city.id && other.contains(point.x, point.z) ||
                civsProtectedBuildings(other).any { protected ->
                    (other.id != city.id || protected.id != building.id) &&
                        (protected.bounds.contains(point.x, point.y, point.z) || protected.control == point)
                } || other.pendingControls.orEmpty().any { pending ->
                    (other.id != city.id || pending.buildingId != building.id) &&
                        pending.approach.orEmpty().any { it.position == point }
                }
        }, "building-overlap")
        requireCivs(block.getState(false) !is TileState && !block.isLiquid &&
            (block.blockData as? Waterlogged)?.isWaterlogged != true, "mine-opening-obstructed")
        requireCivs(block.type.isAir || CivsMineOpening.removable(block.blockData.asString, initial), "mine-opening-obstructed")
        requireCivs(canMine(block) && canModify(city.owner, block, Material.AIR), "lands-denied")
    }

    /** Inverse journal cells restore an older access route; no construction credit, drops or resources. */
    internal fun repairAutonomousAccess(city: Settlement,
        repairs: List<CivAutonomousAccessRepair>): CompletableFuture<Map<String, Int>> = admit(repairs.map { it.cell.position }) {
        requireCivs(repairs.size in 1..512 && repairs.map { it.cell.position }.distinct().size == repairs.size, "invalid-plan")
        val protected = city.building(repairs.first().protectedBuildingId) ?: throw CivsRejection("stale-building")
        AutonomousAccessRepair(city, protected, repairs.toList())
    }

    fun revalidate(plan: CivPlacementPlan, owner: UUID, terrainBatch: Boolean = false): CompletableFuture<Unit> =
        admit(terrainPoints(plan.terrain)) {
            val paidGate = !plan.terrain?.paidGateFloors.isNullOrEmpty()
            requireCivs(plan.blocks.size <= CivsStore.MAX_PLAN_BLOCKS &&
                (plan.blocks.isNotEmpty() || paidGate) && (!terrainBatch || paidGate), "invalid-plan")
            CivsPlacementScan.Revalidation(this, ::includeScanColumns, plan.copy(blocks = plan.blocks.toList()),
                owner, terrainBatch)
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

    /** The caller already owns the current paid batch; guard its exact water cell again at each native write. */
    internal fun permitsWaterFoundationMutation(plan: CivPlacementPlan, cell: CivPlanBlock, block: Block): Boolean {
        check(Bukkit.isPrimaryThread()) { "Civs world access requires the server thread" }
        val point = cell.position
        val foundation = plan.terrain?.waterFoundations.orEmpty().firstOrNull { it.contains(point) }
        val paid = plan.terrain?.paidWaterFloors.orEmpty().any { it.x == point.x && it.z == point.z && point.y <= it.y }
        if (foundation == null && !paid) return true
        val city = cities().firstOrNull { it.id == plan.cityId && it.worldId == plan.worldId && it.pendingDeletion == null } ?: return false
        val after = Material.matchMaterial(cell.after.substringBefore("[")) ?: return false
        if (closed || !civsPaidWaterFoundationBlock(cell.after) ||
            block.world.uid != plan.worldId || point != CivPosition(block.x, block.y, block.z) ||
            !block.world.isChunkLoaded(block.x shr 4, block.z shr 4)) return false
        val current = block.blockData.asString
        if (paid) return cell.before == cell.after && current == cell.after && canModify(city.owner, block, after)
        requireNotNull(foundation)
        if (!civsGeneratedWaterInfrastructure(plan.building) ||
            foundation.bottomY !in block.world.minHeight until block.world.maxHeight) return false
        val state = block.state
        if (current != cell.before && current != cell.after || state is TileState ||
            (state as? InventoryHolder)?.inventory?.any { it != null && !it.type.isAir } == true ||
            !canModify(city.owner, block, after)) return false
        val bed = block.world.getBlockAt(foundation.x, foundation.bottomY, foundation.z)
        return naturalTerraform(bed) && fullTerraformSupport(bed) && canModify(city.owner, bed)
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
    internal fun captureRoadUpkeepAvoiding(city: Settlement, worker: CivWorker, upkeep: CivRoadUpkeep,
        registerSurface: ((UUID, CivPosition) -> Unit)?, rejectedStandings: Set<CivPosition>): CivWorldWork? =
        roadWorld.captureRoadUpkeep(city, worker, upkeep, registerSurface, rejectedStandings)
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
        terrainChanged(worldId, listOf(CivPosition(cell.position.x, cell.position.y, cell.position.z)))
    }

    /** Native success precedes the optional passive hint; no observer can authorize a write. */
    private fun writeObservedBlock(block: Block, after: BlockData) {
        block.setBlockData(after, false)
        sceneChunkChanged(block.world.uid, block.x shr 4, block.z shr 4)
        terrainChanged(block.world.uid, listOf(CivPosition(block.x, block.y, block.z)))
    }

    internal fun terrainChanged(worldId: UUID, positions: Collection<CivPosition>) = onTerrainChanged(worldId, positions)

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

    private fun terrainPoints(terrain: CivTerraformPlan?): List<CivPosition> =
        terrain?.let { listOf(it.bounds.min, it.bounds.max) + it.route }.orEmpty()

    private fun surveyArea(city: Settlement): CivsChunkArea = CivsChunkArea.of(city.copy(radius = city.radius.coerceIn(0, 127)))

    private fun <T> admit(extra: Iterable<CivPosition> = emptyList(), area: CivsChunkArea? = null,
        sampledColumns: List<CivPosition>? = null, loadedOnly: Boolean = false,
        create: () -> CivsWorldScan<T>): CompletableFuture<T> = synchronized(monitor) {
        if (closed || timer == null) return@synchronized CompletableFuture.failedFuture(CivsRejection("world-closed"))
        pending.removeIf { it.future.isDone }
        if (pending.size >= MAX_SCANS) return@synchronized CompletableFuture.failedFuture(CivsRejection("world-busy"))
        try {
            create().also { scan ->
                // Coarse founding reads only the columns its loader already retained.
                scanFootprints[scan] = if (loadedOnly) emptySet() else sampledColumns?.mapTo(linkedSetOf()) {
                    PaperChunkKey(scan.worldId, it.x shr 4, it.z shr 4)
                } ?: run {
                    val building = scan.building
                    var footprint = requireNotNull(CivsChunkArea.around(scan.worldId,
                        listOfNotNull(building.bounds.min, building.bounds.max, building.entrance, building.control)))
                    CivsChunkArea.around(scan.worldId, extra)?.let { footprint = footprint.union(it) }
                    area?.let { footprint = footprint.union(it) }
                    footprint.keys()
                }
                pending += scan
                // Keep native ownership during detached planning and the final context check.
                // Cancellation may arrive off-thread; close only marks demand for the loader's next pulse.
                scan.future.whenComplete { _, _ -> synchronized(monitor) {
                    scanDemands.remove(scan)?.close()
                    scanFootprints.remove(scan)
                    pending.remove(scan)
                } }
            }.future
        } catch (failure: RuntimeException) {
            CompletableFuture.failedFuture(failure)
        }
    }

    /** A validated gate replacement can extend its footprint only before the loader receives the demand. */
    private fun includeScanColumns(scan: CivsWorldScan<*>, columns: List<CivPosition>) {
        synchronized(monitor) {
            requireCivs(scanDemands[scan] == null, "stale-building")
            val extra = requireNotNull(CivsChunkArea.around(scan.worldId, columns)).keys()
            scanFootprints[scan] = scanFootprints.getValue(scan) + extra
        }
    }

    private fun chunksReady(scan: CivsWorldScan<*>): Boolean {
        val request = scanChunks ?: return true
        val demand = synchronized(monitor) {
            if (scan.future.isDone) return false
            val footprint = scanFootprints.getValue(scan)
            if (footprint.isEmpty()) return true
            scanDemands.getOrPut(scan) { request(scan.owner, scan.worldId, footprint) }
        }
        return demand.ready()
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
        val loaded = scans.filter { scan ->
            var ready = false
            scan.guarded { scan.validateContext(); ready = chunksReady(scan) }
            ready && !scan.future.isDone
        }
        if (loaded.isEmpty()) return
        var remaining = CELLS_PER_TICK
        // Keep the next participant across ticks even when one expensive cell exhausts the time budget.
        while (remaining > 0 && loaded.any { !it.future.isDone && it.ready() }) {
            if (remaining < CELLS_PER_TICK && nanoTime() - started >= NANOS_PER_TICK) break
            val scan = loaded[nextScan.mod(loaded.size)]
            nextScan = (nextScan + 1).mod(loaded.size)
            if (scan.future.isDone || !scan.ready()) continue
            scan.guarded { scan.step() }
            remaining--
        }
    }

    private inner class AuthoredVegetationCapture(private val snapshot: Settlement, building: CivBuilding,
        private val expected: List<CivPlanBlock>) :
        CivsWorldScan<List<CivPlanBlock>>(this@CivsWorld, snapshot.id, snapshot.owner, snapshot.worldId, building) {
        private val cells = mutableListOf<CivPlanBlock>()
        override fun validateIdentity(city: Settlement?) {
            requireCivs(city != null && city == snapshot && city.pendingDeletion == null && city.building(building.id) == building &&
                city.construction?.buildingId != building.id && building.id !in city.constructionQueue.orEmpty(), "stale-building")
        }
        override fun step() {
            if (index == expected.size) return finish(cells.toList())
            val cell = expected[index++]
            val block = read(cell.position)
            if (block.type.isAir) checkAuthoredVegetationCell(snapshot, building, block, parse(cell.after).material)
            cells += cell.copy(before = block.blockData.asString)
        }
    }

    private inner class MineOpeningCapture(private val snapshot: Settlement, building: CivBuilding,
        private val initialBlock: (CivPosition, String) -> Boolean) :
        CivsWorldScan<List<CivPlanBlock>>(this@CivsWorld, snapshot.id, snapshot.owner, snapshot.worldId, building) {
        private val positions = CivsMineOpening.positions(building)
        private val cells = mutableListOf<CivPlanBlock>()

        override fun validateIdentity(city: Settlement?) {
            requireCivs(city != null && city == snapshot && city.pendingDeletion == null && city.building(building.id) == building &&
                city.construction?.buildingId != building.id && building.id !in city.constructionQueue.orEmpty(), "stale-building")
        }

        override fun step() {
            if (index == positions.size) return finish(cells.toList())
            val point = positions[index++]
            val block = read(point)
            val before = block.blockData.asString
            checkMineOpeningCell(snapshot, building, block, initialBlock(point, before))
            if (!block.type.isAir) cells += CivPlanBlock(point, before, "minecraft:air")
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
                !terraformMaterial(before.material) ||
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

    private inner class TerraformApply(private val plan: CivPlacementPlan, owner: UUID,
        private val cells: List<CivPlanBlock>, private val terrainStart: Int, private val canAdvance: () -> Boolean,
        private val onPlaced: (CivPlanBlock) -> Unit) : CivsWorldScan<Unit>(this@CivsWorld, plan.cityId, owner, plan.worldId, plan.building) {
        private val terraform = plan.terrain ?: throw CivsRejection("invalid-plan")
        private val paidGateFloors = terraform.paidGateFloors.orEmpty()
        private var paidGateCursor = 0
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
            val protected = civsValidatePaidGateFloors(plan)
            requireCivs(cells.none { it.position in protected }, "invalid-plan")
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
            val checkingGate = paidGateCursor < paidGateFloors.size * 3
            // Check builder presence before the proof and again before the write, not on each proof cell.
            if ((!checkingGate || paidGateCursor == 0) && !canAdvance()) {
                if (index == 0) paidGateCursor = 0
                blockedTick = tickSequence
                return
            }
            if (checkingGate) {
                checkPaidGateCell(paidGateFloors, paidGateCursor++)
                return
            }
            validateContext()
            checkPreservedControl()
            val cell = cells[index]
            checkTerraformCell(cell, terraform)
            val block = read(cell.position)
            val after = parse(cell.after)
            if (cell.position == building.control && retargetSource != null) {
                if (block.blockData.asString !in setOf(cell.before, cell.after)) rejectAt("world-changed", block)
                if (!naturalTerraform(block)) rejectAt("autonomy-terrain", block)
            }
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

    companion object {
        /** At most twelve water cells and one bed probe, each consuming the shared native scan budget. */
        internal const val MAXIMUM_SURFACE_WATER_DEPTH = 12
        const val CELLS_PER_TICK = 2048
        const val NANOS_PER_TICK = 2_000_000L
        const val MAX_SCANS = 4
        internal fun sameConstructionState(actual: BlockData, expected: BlockData): Boolean =
            CivsTerrainPolicy.sameConstructionState(actual, expected)
    }
}

/** Pure final classification after a contiguous native water probe; no mutation authority is implied. */
internal fun civsWaterSurfaceColumn(topY: Int, bottomY: Int, bottom: Material, naturalBottom: Boolean): CivTerrainSurfaceColumn? {
    if (topY.toLong() - bottomY !in 1L..CivsWorld.MAXIMUM_SURFACE_WATER_DEPTH.toLong() ||
        !bottom.isSolid || !terraformMaterial(bottom) || !naturalBottom) return null
    return CivTerrainSurfaceColumn(topY, natural = false, waterTopY = topY, waterBottomY = bottomY)
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
