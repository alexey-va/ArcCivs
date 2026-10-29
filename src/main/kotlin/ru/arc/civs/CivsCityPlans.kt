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

    fun forestryPath(worldId: UUID, x: Int, z: Int): Boolean =
        forestryRoutes.values.any { it.first == worldId && (x to z) in it.second }
    fun forestryRoute(cityId: UUID): Set<Pair<Int, Int>> = forestryRoutes[cityId]?.second.orEmpty()
    fun clear() = forestryRoutes.clear()
    fun remove(cityId: UUID) { forestryRoutes.remove(cityId) }

    /** Completed building captures retain their actual road grades across later development. */
    fun loadAutonomousPlans(city: Settlement): CompletableFuture<List<CivPlacementPlan>> {
        require(city.buildings.size <= CivsRules.MAX_SITES)
        val buildings = city.buildings.filter { it.phase == BuildingPhase.ACTIVE }
        val plans = buildings.map { store.loadPlan(it.id) }
        val result = authority.tracked<List<CivPlacementPlan>>()
        CompletableFuture.allOf(*plans.toTypedArray()).thenApplyAsync({
            val saved = plans.mapNotNull { it.join() }
            Triple(saved, CivAutonomousAccess.from(city, saved).routes.values.flatten().mapTo(hashSetOf()) { it.x to it.z },
                CivsRoadMaintenance.prepare(city, saved))
        }, executor).whenCompleteSync(tasks) { captured, failure ->
            if (records.closed) result.completeExceptionally(CivsRejection("not-ready"))
            else if (failure != null) result.completeExceptionally(failure)
            else {
                val (saved, columns, upkeep) = requireNotNull(captured)
                if (records.cities[city.id]?.buildings == city.buildings && records.cities[city.id]?.roadRouteOverrides == city.roadRouteOverrides) {
                    forestryRoutes[city.id] = city.worldId to columns
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
