package ru.arc.civs

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.UUID
import java.util.concurrent.CompletableFuture

internal class CivsDeletionRequests(
    private val authority: CivsService,
    private val records: CivsCityRecords,
    private val placementOwner: () -> CivsPlacementSession,
    private val buildingRequestsOwner: () -> CivsBuildingRequests,
    private val membershipOwner: () -> CivsCityMembership,
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val world: CivsWorld,
    private val controlCleanup: CivsControlCleanup,
    private val boundary: CivsBoundaryRenderer,
    private val storage: CivsStorage,
    private val controlInstallations: CivsControlInstallations,
    private val citizens: CivsCitizens,
    private val construction: CivsConstructionRuntime,
    private val inventoryJournal: CivsInventoryJournal,
) {
    private val placement get() = placementOwner()
    private val buildingRequests get() = buildingRequestsOwner()
    private val membership get() = membershipOwner()
    private val deletionFailures = mutableMapOf<UUID, String>()
    private val deletionRequests = mutableSetOf<UUID>()
    private var nextDeletionAt = 0L
    private var deletionCursor = 0

    fun clearFailures() = deletionFailures.clear()

    fun deleteCity(player: Player, expectedCityId: UUID, confirmationName: String): CompletableFuture<Unit> =
        authority.cityAction("delete_city", player, "city" to expectedCityId, allowDeletion = true) { city ->
            CivsRules.requireDeletion(city, player.uniqueId, expectedCityId, confirmationName)
            beginDeletion(city, player.uniqueId, null)
        }

    fun deleteBuilding(player: Player, expectedCityId: UUID, buildingId: UUID): CompletableFuture<Unit> =
        authority.cityAction("delete_building", player, "city" to expectedCityId, "building" to buildingId, allowDeletion = true) { city ->
            rejectIf(city.id != expectedCityId, "delete-city-changed")
            beginDeletion(city, player.uniqueId, buildingId)
        }

    private fun beginDeletion(city: Settlement, actor: UUID, buildingId: UUID?): CompletableFuture<Unit> {
        rejectIf(city.id in deletionRequests, "deletion-pending")
        rejectIf(city.id in records.busy || inventoryJournal.pending(city.id), "city-busy")
        val proposed = CivsRules.requestDeletion(city, actor, buildingId)
        if (city.pendingDeletion != null) return finishDeletion(city)
        authority.reserveCity(city.id)
        val result = authority.tracked<Unit>()
        // The durable save releases records.busy before its continuation runs; recovery must not steal that handoff.
        deletionRequests += city.id
        result.whenCompleteSync(tasks) { _, _ -> deletionRequests -= city.id }
        val loaded = authority.tracked<Unit>()
        runCatching { construction.stop(city.id) }.onSuccess { stopped -> stopped.whenCompleteSync(tasks) { _, stopFailure ->
            if (stopFailure != null) loaded.completeExceptionally(stopFailure)
            else runCatching { loadControlChunks(city, deletionBuildings(proposed)) }.onSuccess { chunks ->
                chunks.whenComplete { _, chunkFailure ->
                    if (chunkFailure == null) loaded.complete(Unit) else loaded.completeExceptionally(chunkFailure)
                }
            }.onFailure(loaded::completeExceptionally)
        } }.onFailure(loaded::completeExceptionally)
        loaded.whenCompleteSync(tasks) { _, failure ->
            if (failure != null) {
                authority.releaseCity(city.id)
                result.completeExceptionally(failure)
            } else runCatching {
                rejectIf(!authority.ready() || records.cities[city.id] != city, "stale-city")
                controlCleanup.prepare(city, deletionBuildings(proposed))
                authority.releaseCity(city.id)
                authority.mutate(city.id) { CivsRules.requestDeletion(it, actor, buildingId) }.whenCompleteSync(tasks) { saved, saveFailure ->
                    if (saveFailure != null) result.completeExceptionally(saveFailure)
                    else {
                        CivsDiagnostics.event("deletion_reserved", "city" to city.id, "building" to buildingId,
                            "construction" to city.construction?.id)
                        runCatching { finishDeletion(requireNotNull(saved)) }.onSuccess { finished ->
                            finished.whenCompleteSync(tasks) { _, deleteFailure ->
                                if (deleteFailure == null) result.complete(Unit) else result.completeExceptionally(deleteFailure)
                            }
                        }.onFailure(result::completeExceptionally)
                    }
                }
            }.onFailure { authority.releaseCity(city.id); result.completeExceptionally(it) }
        }
        return result
    }

    private fun deletionBuildings(city: Settlement): List<CivBuilding> {
        val intent = requireNotNull(city.pendingDeletion)
        return intent.buildingId?.let { listOf(requireNotNull(city.building(it))) } ?: city.buildings
    }

    /** Explicit deletion may load only the already-authored control chunks; gameplay pulses never scan/load a region. */
    private fun loadControlChunks(city: Settlement, buildings: List<CivBuilding>): CompletableFuture<Unit> {
        authority.checkMain()
        val target = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("world-unavailable")
        val loads = buildings.mapNotNull(CivBuilding::control).map { (it.x shr 4) to (it.z shr 4) }.distinct()
            .filterNot { (x, z) -> target.isChunkLoaded(x, z) }.map { (x, z) -> target.getChunkAtAsync(x, z, false) }
        return CompletableFuture.allOf(*loads.toTypedArray()).thenApply { Unit }
    }

    private fun finishDeletion(city: Settlement): CompletableFuture<Unit> {
        rejectIf(city.id in records.busy || inventoryJournal.pending(city.id), "city-busy")
        val buildings = deletionBuildings(city)
        authority.reserveCity(city.id)
        val result = authority.tracked<Unit>()
        val loading = runCatching { loadControlChunks(city, buildings) }.getOrElse {
            authority.releaseCity(city.id)
            return CompletableFuture.failedFuture(it)
        }
        loading.whenCompleteSync(tasks) { _, failure ->
            if (failure != null) { authority.releaseCity(city.id); result.completeExceptionally(failure) }
            else runCatching {
                rejectIf(!authority.ready() || records.cities[city.id] != city, "stale-city")
                val controls = controlCleanup.prepare(city, buildings)
                val removed = controlCleanup.apply(city, controls)
                CivsDiagnostics.event("controls_deleted", "city" to city.id, "building" to city.pendingDeletion?.buildingId,
                    "registered" to controls.size, "removed" to removed)
                if (city.pendingDeletion?.buildingId == null) {
                    deleteCityRecord(city).whenCompleteSync(tasks) { _, deleteFailure ->
                        if (deleteFailure == null) result.complete(Unit) else result.completeExceptionally(deleteFailure)
                    }
                } else {
                    authority.releaseCity(city.id)
                    authority.mutate(city.id, allowDeletion = true) { CivsRules.finishBuildingDeletion(it, it.owner) }.whenCompleteSync(tasks) { saved, deleteFailure ->
                        if (deleteFailure != null) result.completeExceptionally(deleteFailure)
                        else {
                            val id = requireNotNull(city.pendingDeletion.buildingId)
                            construction.clearQueueFailure(city.id)
                            deletionFailures.remove(city.id)
                            controlInstallations.forgetFailure(id)
                            storage.refreshBuilding(requireNotNull(saved))
                            buildingRequests.cleanupQueuedUpgrades(city, setOf(id))
                            CivsDiagnostics.event("building_deleted", "city" to city.id, "building" to id,
                                "construction_cancelled" to city.construction?.takeIf { it.buildingId == id }?.id)
                            tasks.runLater(1) { if (authority.ready()) construction.pulseQueue() }
                            result.complete(Unit)
                        }
                    }
                }
            }.onFailure { authority.releaseCity(city.id); result.completeExceptionally(it) }
        }
        return result
    }

    fun pulseDeletions() {
        if (authority.now() < nextDeletionAt) return
        nextDeletionAt = authority.now() + 5_000L
        val pending = records.cities.values.filter { it.pendingDeletion != null && it.id !in records.busy && it.id !in records.unhealthy &&
            !inventoryJournal.pending(it.id) && it.id !in deletionRequests }
        if (pending.isEmpty()) return
        val city = pending[Math.floorMod(deletionCursor++, pending.size)]
        runCatching { finishDeletion(city) }.onSuccess { future -> future.whenCompleteSync(tasks) { _, failure ->
            if (failure == null) deletionFailures.remove(city.id)
            else {
                val reason = CivsDiagnostics.rejection(failure)?.reason ?: "storage-unavailable"
                if (deletionFailures.put(city.id, reason) != reason) authority.log("recover deletion ${city.id}", failure, city.id)
            }
        } }.onFailure { failure ->
            val reason = CivsDiagnostics.rejection(failure)?.reason ?: "storage-unavailable"
            if (deletionFailures.put(city.id, reason) != reason) authority.log("recover deletion ${city.id}", failure, city.id)
        }
    }

    private fun deleteCityRecord(city: Settlement): CompletableFuture<Unit> {
        val result = authority.tracked<Unit>()
        authority.deleteRecord(city).whenCompleteSync(tasks) { _, failure ->
            if (failure != null) {
                authority.releaseCity(city.id)
                val rejection = CivsDiagnostics.rejection(failure)
                if (rejection != null) result.completeExceptionally(rejection)
                else {
                    authority.quarantineCity(city.id)
                    authority.log("delete settlement ${city.id}", failure, city.id)
                    result.completeExceptionally(CivsRejection("storage-unavailable"))
                }
                authority.updateHealth()
            } else authority.publish(city.id, null).whenCompleteSync(tasks) { _, publishFailure ->
                authority.releaseCity(city.id)
                if (publishFailure != null) {
                    authority.quarantineCity(city.id)
                    authority.log("publish settlement deletion ${city.id}", publishFailure, city.id)
                    result.completeExceptionally(CivsRejection("storage-unavailable"))
                } else {
                    // Invalidate old drafts and membership callbacks before a new city can be founded.
                    (city.members + city.owner).forEach { member ->
                        membership.forget(member)
                        placement.cancelPreview(member, "city-deleted")
                        boundary.clear(member)
                    }
                    membership.forgetCity(city)
                    city.buildings.forEach { controlInstallations.forgetFailure(it.id) }
                    buildingRequests.cleanupQueuedUpgrades(city, city.buildings.mapTo(mutableSetOf(), CivBuilding::id))
                    citizens.pulse(authority.now())
                    CivsDiagnostics.event("city_deleted", "city" to city.id, "owner" to city.owner,
                        "revision" to city.revision, "buildings_preserved" to city.buildings.size,
                        "discarded_shared_items" to city.inventory?.materials.orEmpty().values.sum(),
                        "discarded_building_items" to city.inventory?.buildingMaterials.orEmpty().values.sumOf { it.values.sum() },
                        "discarded_legacy_stock" to city.stock,
                        "construction_cancelled" to city.construction?.id)
                    result.complete(Unit)
                }
                authority.updateHealth()
            }
        }
        return result
    }

    private fun rejectIf(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
}
