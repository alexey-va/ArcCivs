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
    /** Detached projections of the existing saved terrain routes. */
    private data class RetainedRoutes(val worldId: UUID, val buildings: List<CivBuilding>,
        val overrides: Map<String, CivRoadRouteOverride>?, val columns: Set<Pair<Int, Int>>,
        val courierRoads: CivsPaths.RetainedRoads) {
        fun matches(city: Settlement): Boolean = worldId == city.worldId && civsSameBuildingLayout(buildings, city.buildings) &&
            overrides == city.roadRouteOverrides
    }
    private val retainedRoutes = mutableMapOf<UUID, RetainedRoutes>()
    private val routeChunkPositions = mutableMapOf<UUID, List<CivPosition>>()

    internal data class InitialMineKey(val city: UUID, val owner: UUID, val world: UUID, val plan: UUID,
        val building: UUID, val type: BuildingType, val level: Int, val schematic: String,
        val anchor: CivPosition, val rotation: Int, val bounds: CivBox, val entrance: CivPosition)
    internal data class InitialMineProof(val key: InitialMineKey, val cuts: Map<CivPosition, String>)
    private val initialMines = mutableMapOf<UUID, List<InitialMineProof>>()

    private fun initialMineKey(city: Settlement, building: CivBuilding) = InitialMineKey(city.id, city.owner,
        city.worldId, CivsWarehouseRefit.retainedPlanId(building), building.id, building.type, building.level,
        building.schematicId, building.anchor, building.rotation, building.bounds, building.entrance)

    private fun initialMineBuildings(city: Settlement) = city.buildings.filter {
        it.phase == BuildingPhase.ACTIVE && it.type == BuildingType.QUARRY &&
            (city.inventory != null || CivsAutonomousWorksites.supports(city, it))
    }

    private fun initialMineKeys(city: Settlement) = initialMineBuildings(city).map { initialMineKey(city, it) }

    fun initialMinesUnchanged(before: Settlement?, after: Settlement): Boolean =
        before != null && initialMineKeys(before) == initialMineKeys(after)

    /** Only the original 27 mouth cuts; control/production changes never rewrite the retained capture. */
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
                    initialMineKey(city, plan.building) != key) return@thenApplyAsync null
                val mine = CivsMinePlan.forBuilding(building) ?: return@thenApplyAsync null
                if (mine.version != 7) return@thenApplyAsync null
                val targets = mine.sections["0/shaft/opening"].orEmpty().asSequence()
                    .filter { mine.steps[it].after == null }
                    .map { CivsWorksiteLayouts.point(building, mine.steps[it].point) }.toSet()
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
        retainedRoutes.values.any { it.worldId == worldId && (x to z) in it.columns }
    fun forestryRoute(cityId: UUID): Set<Pair<Int, Int>> = retainedRoutes[cityId]?.columns.orEmpty()
    fun courierRoads(city: Settlement): CivsPaths.RetainedRoads =
        retainedRoutes[city.id]?.takeIf { it.matches(city) }?.courierRoads ?: CivsPaths.RetainedRoads.EMPTY
    fun chunkPositions(cityId: UUID): List<CivPosition> = routeChunkPositions[cityId].orEmpty()
    fun clear() { retainedRoutes.clear(); routeChunkPositions.clear(); initialMines.clear() }
    fun remove(cityId: UUID) { retainedRoutes.remove(cityId); routeChunkPositions.remove(cityId); initialMines.remove(cityId) }

    /** Completed building captures retain their actual road grades across later development. */
    fun loadAutonomousPlans(city: Settlement): CompletableFuture<List<CivPlacementPlan>> {
        require(city.buildings.size <= CivsRules.MAX_SITES)
        val buildings = city.buildings.filter { it.phase == BuildingPhase.ACTIVE }
        val plans = buildings.map { store.loadPlan(CivsWarehouseRefit.retainedPlanId(it)) }
        val result = authority.tracked<List<CivPlacementPlan>>()
        CompletableFuture.allOf(*plans.toTypedArray()).thenApplyAsync({
            val saved = plans.mapNotNull { it.join() }
            buildings.filter { it.warehouseCaptureId != null }.forEach { building ->
                require(CivsWarehouseRefit.matchesCapture(building, saved.singleOrNull { it.id == building.warehouseCaptureId })) {
                    "Warehouse retained capture does not match its building"
                }
            }
            val access = CivAutonomousAccess.from(city, saved)
            val routes = RetainedRoutes(city.worldId, city.buildings.toList(), city.roadRouteOverrides,
                access.routes.values.asSequence().flatten().mapTo(hashSetOf()) { it.x to it.z },
                CivsPaths.RetainedRoads.from(city, access))
            Triple(saved, routes, CivsRoadMaintenance.prepare(city, saved))
        }, executor).whenCompleteSync(tasks) { captured, failure ->
            if (records.closed) result.completeExceptionally(CivsRejection("not-ready"))
            else if (failure != null) result.completeExceptionally(failure)
            else {
                val (saved, routes, upkeep) = requireNotNull(captured)
                if (records.cities[city.id]?.let { it.buildings == city.buildings && routes.matches(it) } == true) {
                    retainedRoutes[city.id] = routes
                    val columns = routes.columns
                    routeChunkPositions[city.id] = if (columns.isEmpty()) emptyList() else listOf(
                        CivPosition(columns.minOf { it.first }, city.center.y, columns.minOf { it.second }),
                        CivPosition(columns.maxOf { it.first }, city.center.y, columns.maxOf { it.second }))
                    roadMaintenance.publish(city, upkeep)
                }
                result.complete(saved)
            }
        }
        return result
    }

    fun loadAutonomousAccess(city: Settlement): CompletableFuture<CivAutonomousAccess> =
        loadAutonomousPlans(city).thenApplyAsync({ CivAutonomousAccess.from(city, it) }, executor)

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
