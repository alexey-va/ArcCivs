package ru.arc.civs

import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

internal class CivsCityPlans(
    private val authority: CivsService,
    private val records: CivsCityRecords,
    private val tasks: LifecycleTaskScope,
    private val store: CivsStore,
    private val executor: Executor,
    private val roadMaintenance: CivsRoadMaintenance,
) {
    /** Access and upkeep depend on geometry and pending construction, never on worker/stock revisions. */
    private data class RetainedInputs(val cityId: UUID, val owner: UUID, val name: String, val worldId: UUID,
        val center: CivPosition, val radius: Int, val style: String, val buildings: List<CivBuilding>,
        val constructionId: UUID?, val constructionBuilding: UUID?, val plannedBuilding: CivBuilding?,
        val queue: List<UUID>?, val controls: List<CivControlPlacement>?, val deletion: CivDeletion?,
        val autonomous: Boolean, val layoutHalfWidth: Int?, val adopted: CivSettlementPlan?,
        val discoveries: Set<CivResearchId>, val overrides: Map<String, CivRoadRouteOverride>?) {
        companion object {
            fun from(city: Settlement) = RetainedInputs(city.id, city.owner, city.name, city.worldId,
                city.center, city.radius, city.style, city.buildings, city.construction?.id,
                city.construction?.buildingId, city.construction?.plannedBuilding, city.constructionQueue,
                city.pendingControls, city.pendingDeletion, city.autonomy != null, city.autonomy?.layoutHalfWidth,
                city.autonomy?.plan, city.research?.completed.orEmpty(), city.roadRouteOverrides)
        }
    }

    /** Detached projections of the existing saved terrain routes. */
    private data class RetainedRoutes(val inputs: RetainedInputs, val columns: Set<Pair<Int, Int>>,
        val courierRoads: CivsPaths.RetainedRoads,
        val generation: Long, val epoch: Long, val access: CivAutonomousAccess, val upkeep: CivRoadUpkeepNetwork) {
        fun matches(city: Settlement): Boolean = inputs.worldId == city.worldId && civsSameBuildingLayout(inputs.buildings, city.buildings) &&
            inputs.overrides == city.roadRouteOverrides
        fun matchesAccess(city: Settlement, generation: Long, epoch: Long): Boolean =
            this.generation == generation && this.epoch == epoch && inputs == RetainedInputs.from(city)
    }
    private data class RetainedSources(val ids: List<UUID>, val generation: Long, val epoch: Long,
        val sources: List<CivRetainedPlanSource>)
    private val retainedRoutes = mutableMapOf<UUID, RetainedRoutes>()
    private val retainedSources = mutableMapOf<UUID, RetainedSources>()
    private val routeChunkPositions = mutableMapOf<UUID, List<CivPosition>>()
    private var retainedEpoch = 0L

    internal data class InitialMineBuildingIdentity(val building: UUID, val type: BuildingType, val level: Int, val schematic: String,
        val anchor: CivPosition, val rotation: Int, val bounds: CivBox, val entrance: CivPosition)
    internal data class InitialMineKey(val city: UUID, val owner: UUID, val world: UUID, val plan: UUID,
        val current: InitialMineBuildingIdentity, val sources: List<InitialMineBuildingIdentity>)
    internal data class InitialMineProof(val key: InitialMineKey, val cuts: Map<CivPosition, String>)
    private val initialMines = mutableMapOf<UUID, List<InitialMineProof>>()

    private fun initialMineIdentity(building: CivBuilding) = InitialMineBuildingIdentity(building.id, building.type,
        building.level, building.schematicId, building.anchor, building.rotation, building.bounds, building.entrance)

    private fun initialMineKey(city: Settlement, building: CivBuilding): InitialMineKey {
        val current = initialMineIdentity(building)
        // The retained construction capture may predate an upgrade. Only accepted ACTIVE predecessors are source owners;
        // their procedural cursor is not construction authority and ordinary mining does not change this cache key.
        val saved = city.minePlans?.get(building.id.toString())
        val historical = saved?.takeIf { CivsMineProgress.validLevels(building, it) }?.levels.orEmpty()
            .filter { it.building.phase == BuildingPhase.ACTIVE }.sortedBy { it.building.level }
            .map { initialMineIdentity(it.building) }
        return InitialMineKey(city.id, city.owner, city.worldId, CivsWarehouseRefit.retainedPlanId(building),
            current, listOf(current) + historical)
    }

    private fun initialMineBuildings(city: Settlement) = city.buildings.filter {
        it.phase == BuildingPhase.ACTIVE && it.type == BuildingType.QUARRY &&
            (city.inventory != null || CivsAutonomousWorksites.supports(city, it))
    }

    private fun initialMineKeys(city: Settlement) = initialMineBuildings(city).map { initialMineKey(city, it) }

    fun initialMinesUnchanged(before: Settlement?, after: Settlement): Boolean =
        before != null && initialMineKeys(before) == initialMineKeys(after)

    /** Only the current shallow mouth; control/production changes never rewrite the retained capture. */
    fun initialMineBlock(city: Settlement, building: CivBuilding, point: CivPosition, before: String): Boolean {
        if (building.phase != BuildingPhase.ACTIVE || building.type != BuildingType.QUARRY) return false
        val key = initialMineKey(city, building)
        return initialMines[city.id]?.any { it.key == key && it.cuts[point] == before } == true
    }

    /** Detached projection only. Store IO and schematic-local geometry never run from a work pulse. */
    fun prepareInitialMines(city: Settlement): CompletableFuture<List<InitialMineProof>> {
        val buildings = initialMineBuildings(city)
        require(buildings.size <= CivsRules.MAX_SITES)
        val plans = buildings.map { building ->
            store.loadPlan(CivsWarehouseRefit.retainedPlanId(building)).thenApplyAsync({ plan ->
                val key = initialMineKey(city, building)
                if (plan == null || plan.id != key.plan || plan.cityId != city.id || plan.worldId != city.worldId ||
                    plan.building.phase !in setOf(BuildingPhase.CONSTRUCTING, BuildingPhase.ACTIVE) ||
                    initialMineIdentity(plan.building) !in key.sources) return@thenApplyAsync null
                val mine = CivsMinePlan.forBuilding(building) ?: return@thenApplyAsync null
                if (mine.version != 7) return@thenApplyAsync null
                val targets = CivsMineOpening.positions(building).toSet()
                // Construction applies terrain first; the authored schematic has the final word at an overlap.
                val cuts = buildMap {
                    for (cell in plan.terrain?.blocks.orEmpty()) if (cell.position in targets) put(cell.position, cell.after)
                    for (cell in plan.blocks) if (cell.position in targets) put(cell.position, cell.after)
                }
                InitialMineProof(key, cuts)
            }, executor)
        }
        return CompletableFuture.allOf(*plans.toTypedArray()).thenApply { plans.mapNotNull { it.join() } }
    }

    fun publishInitialMines(city: UUID, proofs: List<InitialMineProof>) {
        if (proofs.isEmpty()) initialMines.remove(city) else initialMines[city] = proofs
    }

    fun forestryPath(worldId: UUID, x: Int, z: Int): Boolean =
        retainedRoutes.values.any { it.inputs.worldId == worldId && (x to z) in it.columns }
    fun forestryRoute(cityId: UUID): Set<Pair<Int, Int>> = retainedRoutes[cityId]?.columns.orEmpty()
    fun courierRoads(city: Settlement): CivsPaths.RetainedRoads =
        retainedRoutes[city.id]?.takeIf { it.matches(city) }?.courierRoads ?: CivsPaths.RetainedRoads.EMPTY
    fun chunkPositions(cityId: UUID): List<CivPosition> = routeChunkPositions[cityId].orEmpty()
    fun clear() { retainedEpoch++; retainedRoutes.clear(); retainedSources.clear(); routeChunkPositions.clear(); initialMines.clear() }
    fun remove(cityId: UUID) {
        retainedEpoch++; retainedRoutes.remove(cityId); retainedSources.remove(cityId)
        routeChunkPositions.remove(cityId); initialMines.remove(cityId)
    }

    private fun retainedIds(city: Settlement): List<UUID> {
        require(city.buildings.size <= CivsRules.MAX_SITES)
        return city.buildings.filter { it.phase == BuildingPhase.ACTIVE }.map(CivsWarehouseRefit::retainedPlanId)
    }

    private fun prepareRetained(city: Settlement, loading: CompletableFuture<RetainedSources>): CompletableFuture<CivAutonomousAccess> {
        val inputs = RetainedInputs.from(city)
        val result = authority.tracked<CivAutonomousAccess>()
        loading.thenApplyAsync({ sources ->
            city.buildings.filter { it.phase == BuildingPhase.ACTIVE && it.warehouseCaptureId != null }.forEach { building ->
                val source = sources.sources.singleOrNull { it.id == building.warehouseCaptureId }
                require(source != null && CivsWarehouseRefit.supported(source.building) &&
                    source.building.warehouseCaptureId == source.id &&
                    source.building.copy(control = building.control, production = building.production) == building) {
                    "Warehouse retained capture does not match its building"
                }
            }
            val access = CivAutonomousAccess.fromSources(city, sources.sources)
            val upkeep = CivsRoadMaintenance.prepare(city, access)
            val routes = RetainedRoutes(inputs,
                access.routes.values.asSequence().flatten().mapTo(hashSetOf()) { it.x to it.z },
                CivsPaths.RetainedRoads.from(city, access), sources.generation, sources.epoch, access, upkeep)
            sources to routes
        }, executor).whenCompleteSync(tasks) { captured, failure ->
            if (records.closed) result.completeExceptionally(CivsRejection("not-ready"))
            else if (failure != null) result.completeExceptionally(failure)
            else {
                val (sources, routes) = requireNotNull(captured)
                if (records.cities[city.id]?.let {
                    routes.matchesAccess(it, store.planGeneration(sources.ids), retainedEpoch)
                } == true) {
                    retainedSources[city.id] = sources
                    retainedRoutes[city.id] = routes
                    val columns = routes.columns
                    routeChunkPositions[city.id] = if (columns.isEmpty()) emptyList() else listOf(
                        CivPosition(columns.minOf { it.first }, city.center.y, columns.minOf { it.second }),
                        CivPosition(columns.maxOf { it.first }, city.center.y, columns.maxOf { it.second }))
                    roadMaintenance.publish(city, routes.upkeep)
                }
                result.complete(routes.access)
            }
        }
        return result
    }

    /** Callers needing exact construction before-images still receive the complete validated journals. */
    fun loadAutonomousPlans(city: Settlement): CompletableFuture<List<CivPlacementPlan>> {
        val ids = retainedIds(city)
        val generation = store.planGeneration(ids)
        val epoch = retainedEpoch
        val plans = ids.map(store::loadPlan)
        val loading = CompletableFuture.allOf(*plans.toTypedArray()).thenApplyAsync({
            val saved = plans.mapNotNull { it.join() }
            RetainedSources(ids, generation, epoch, saved.map(CivRetainedPlanSource::from))
        }, executor)
        return prepareRetained(city, loading).thenApply { plans.mapNotNull { it.join() } }
    }

    fun loadAutonomousAccess(city: Settlement): CompletableFuture<CivAutonomousAccess> {
        if (records.closed) return CompletableFuture.failedFuture(CivsRejection("not-ready"))
        val retained = retainedSources[city.id]
        val retainedGeneration = store.planGeneration(retained?.ids.orEmpty())
        val cached = retainedRoutes[city.id]?.takeIf { routes -> routes.matchesAccess(city, retainedGeneration, retainedEpoch) &&
            records.cities[city.id]?.let { routes.matchesAccess(it, retainedGeneration, retainedEpoch) } == true
        }
        if (cached != null) {
            // A repeated grade-refresh receipt still owns its wait; publish the same network without rebuilding it.
            roadMaintenance.publish(city, cached.upkeep)
            return CompletableFuture.completedFuture(cached.access)
        }
        val ids = retainedIds(city)
        val generation = store.planGeneration(ids)
        val epoch = retainedEpoch
        val sources = retained?.takeIf { it.ids == ids && it.generation == generation && it.epoch == epoch }
        if (sources != null) return prepareRetained(city, CompletableFuture.completedFuture(sources))
        // Project each read immediately. allOf must not keep every full building's block graph alive.
        val loading = ids.map(store::loadRetainedPlanSource)
        return prepareRetained(city, CompletableFuture.allOf(*loading.toTypedArray()).thenApply {
            RetainedSources(ids, generation, epoch, loading.mapNotNull { it.join() })
        })
    }

    fun loadVegetationPlans(city: Settlement): CompletableFuture<CivVegetationPlanCapture> {
        val ids = retainedIds(city)
        val generation = store.planGeneration(ids)
        val epoch = retainedEpoch
        return loadAutonomousPlans(city).thenApply { plans ->
            CivVegetationPlanCapture(plans) {
                !records.closed && epoch == retainedEpoch && generation == store.planGeneration(ids)
            }
        }
    }

    /** Reuse the sealed projection for a fresh receipt; a restarted server reopens the retained plan journals. */
    fun verifyHallCaptures(tx: CivInventoryTransaction): CompletableFuture<Unit> {
        val upkeep = requireNotNull(tx.upkeep?.work?.upkeep)
        val before = requireNotNull(tx.upkeep).before(tx.after)
        fun verify(access: CivAutonomousAccess) {
            val shared = CivsGateSharedApproaches.repair(before, upkeep)
            if (shared != null) {
                require(CivsGateSharedApproaches.current(before, shared, access))
                upkeep.wallCleanup?.let { cleanup ->
                    require(cleanup.authored in requireNotNull(access.sharedRoadCaptures[upkeep.source.id]).pavement)
                }
            } else {
                val proof = requireNotNull(upkeep.hall)
                require(access.hallWitness == proof && access.hallApproach(before)?.surfaces?.get(upkeep.floor) == upkeep.after)
            }
        }
        val generation = store.planGeneration(retainedSources[before.id]?.ids.orEmpty())
        val cached = retainedRoutes[before.id]?.takeIf { it.matchesAccess(before, generation, retainedEpoch) }
        if (cached != null) return try { verify(cached.access); CompletableFuture.completedFuture(Unit) }
            catch (failure: IllegalArgumentException) { CompletableFuture.failedFuture(failure) }
        return loadAutonomousAccess(before).thenApply(::verify)
    }

    /** Queued upgrade captures are exact future geometry, never retained built access roads. */
    fun loadPlanBoardPlans(city: Settlement): CompletableFuture<List<CivPlacementPlan>> {
        require(city.constructionQueue.orEmpty().size <= CivsRules.MAX_SITES)
        val upgrades = city.constructionQueue.orEmpty().mapNotNull(city::building)
            .filter { it.phase == BuildingPhase.ACTIVE }.map { store.loadPlan(queuedDesignId(it)) }
        val active = loadAutonomousPlans(city)
        return active.thenCombineAsync(CompletableFuture.allOf(*upgrades.toTypedArray()), { retained, _ ->
            retained + upgrades.mapNotNull { it.join() }
        }, executor)
    }

}
