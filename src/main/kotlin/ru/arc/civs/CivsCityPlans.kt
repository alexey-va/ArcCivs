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
    /** Detached columns from the existing saved terrain routes; never a second road journal. */
    private val forestryRoutes = mutableMapOf<UUID, Pair<UUID, Set<Pair<Int, Int>>>>()
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
        forestryRoutes.values.any { it.first == worldId && (x to z) in it.second }
    fun forestryRoute(cityId: UUID): Set<Pair<Int, Int>> = forestryRoutes[cityId]?.second.orEmpty()
    fun chunkPositions(cityId: UUID): List<CivPosition> = routeChunkPositions[cityId].orEmpty()
    fun clear() { forestryRoutes.clear(); routeChunkPositions.clear(); initialMines.clear() }
    fun remove(cityId: UUID) { forestryRoutes.remove(cityId); routeChunkPositions.remove(cityId); initialMines.remove(cityId) }

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
            Triple(saved, CivAutonomousAccess.from(city, saved).routes.values.flatten().mapTo(hashSetOf()) { it.x to it.z },
                CivsRoadMaintenance.prepare(city, saved))
        }, executor).whenCompleteSync(tasks) { captured, failure ->
            if (records.closed) result.completeExceptionally(CivsRejection("not-ready"))
            else if (failure != null) result.completeExceptionally(failure)
            else {
                val (saved, columns, upkeep) = requireNotNull(captured)
                if (records.cities[city.id]?.buildings == city.buildings && records.cities[city.id]?.roadRouteOverrides == city.roadRouteOverrides) {
                    forestryRoutes[city.id] = city.worldId to columns
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
