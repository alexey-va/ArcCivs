package ru.arc.civs

import org.bukkit.entity.LivingEntity
import org.bukkit.util.BoundingBox
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.UUID
import java.util.concurrent.CompletableFuture

private fun requireCivs(allowed: Boolean, reason: String) {
    if (!allowed) throw CivsRejection(reason)
}

/** Mouth construction is independent of miner scheduling; the ordinary inventory journal owns its cuts. */
internal class CivsMineMaintenance(
    private val authority: CivsService,
    private val records: CivsCityRecords,
    private val tasks: LifecycleTaskScope,
    private val world: CivsWorld,
    private val storage: CivsStorage,
    private val journal: CivsInventoryJournal,
    private val initialBlock: (Settlement, CivBuilding, CivPosition, String) -> Boolean,
    private val placing: (UUID) -> Boolean,
    private val occupied: (UUID, CivPosition) -> Boolean,
) {
    private data class Identity(val city: UUID, val world: UUID, val owner: UUID, val building: CivBuilding)
    private val checked = mutableMapOf<UUID, Identity>()
    private val retryAt = mutableMapOf<UUID, Long>()
    private val pendingCities = mutableSetOf<UUID>()

    /** One bounded check per active construction identity, also repeated after restart or upgrade. */
    fun pulse() {
        val candidates = records.cities.values.flatMap { city -> city.buildings.filter {
            it.type == BuildingType.QUARRY && it.phase == BuildingPhase.ACTIVE && CivsWorksiteLayouts.site(it)?.has("mine") == true
        }.map { city to it } }
        val ids = candidates.mapTo(hashSetOf()) { it.second.id }
        checked.keys.retainAll(ids)
        retryAt.keys.retainAll(ids)
        val now = authority.now()
        val (city, building) = candidates.firstOrNull { (city, building) ->
            city.id !in pendingCities && checked[building.id] != identity(city, building) &&
                now >= retryAt.getOrDefault(building.id, 0) &&
                city.id !in records.busy && city.id !in records.unhealthy && city.pendingDeletion == null &&
                !journal.pending(city.id) && !placing(city.id) && city.construction?.buildingId != building.id &&
                building.id !in city.constructionQueue.orEmpty()
        } ?: return
        pendingCities += city.id
        runCatching { request(city.id, building.id) }.getOrElse { CompletableFuture.failedFuture(it) }
            .whenCompleteSync(tasks) { _, failure ->
                pendingCities -= city.id
                if (failure != null) {
                    retryAt[building.id] = authority.now() + 30_000
                    CivsDiagnostics.event("mine_opening_waiting", "city" to city.id, "building" to building.id,
                        "reason" to (CivsDiagnostics.rejection(failure)?.reason ?: failure.javaClass.simpleName))
                }
            }
    }

    fun request(cityId: UUID, buildingId: UUID): CompletableFuture<Map<String, Any?>> {
        authority.checkMain()
        requireCivs(authority.ready(), "not-ready")
        requireCivs(cityId !in records.busy && cityId !in records.unhealthy && !journal.pending(cityId) &&
            !placing(cityId), "city-busy")
        val city = records.cities[cityId] ?: throw CivsRejection("no-city")
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        requireCivs(city.pendingDeletion == null && city.construction?.buildingId != buildingId &&
            buildingId !in city.constructionQueue.orEmpty(), "construction-busy")
        CivsMineOpening.positions(building)
        val result = authority.tracked<Map<String, Any?>>()
        authority.reserveCity(cityId)
        var reserved = true
        fun release() { if (reserved) { reserved = false; authority.releaseCity(cityId) } }
        fun fail(error: Throwable) { release(); result.completeExceptionally(error) }
        runCatching {
            world.captureMineOpening(city, building) { point, before -> initialBlock(city, building, point, before) }
        }.getOrElse { fail(it); return result }.whenCompleteSync(tasks) captured@ { cells, failure ->
            if (failure != null) { fail(failure); return@captured }
            runCatching {
                requireCivs(authority.ready() && !records.closed && records.cities[cityId] == city && !placing(cityId), "stale-city")
                val changes = requireNotNull(cells)
                val summary = mapOf("city" to cityId, "building" to buildingId,
                    "checked_cells" to CivsMineOpening.positions(building).size, "changed_cells" to changes.size)
                if (changes.isEmpty()) {
                    checked[buildingId] = identity(city, building)
                    release()
                    result.complete(summary + mapOf("state" to "current", "revision" to city.revision))
                    return@captured
                }
                val receipt = CivMineOpeningReceipt(building, changes).detached()
                val after = city.copy(revision = city.revision + 1)
                val id = UUID.randomUUID()
                val tx = CivInventoryTransaction(id, city.id, city.revision, after, emptyList(),
                    worldChanges = receipt.cells, mineOpening = receipt).validated()
                requireNative(checkNative(city, tx))
                storage.reserve(city, id)
                try { storage.seal(tx, keepViewersOpen = true) }
                catch (error: Exception) { storage.unlock(id); throw error }
                release()
                journal.submit(tx).whenCompleteSync(tasks) { committed, commitFailure ->
                    if (commitFailure != null) result.completeExceptionally(commitFailure)
                    else {
                        checked[buildingId] = identity(requireNotNull(committed), building)
                        retryAt.remove(buildingId)
                        CivsDiagnostics.event("mine_opening_repaired", "city" to cityId, "building" to buildingId,
                            "transaction" to id, "changed_cells" to changes.size, "revision" to committed.revision)
                        result.complete(summary + mapOf("state" to "committed", "transaction" to id, "revision" to committed.revision))
                    }
                }
            }.onFailure(::fail)
        }
        return result
    }

    fun validateNative(tx: CivInventoryTransaction): CivInventoryApplyResult? {
        authority.checkMain()
        val city = records.cities[tx.cityId] ?: return conflict("no-city")
        if (city != tx.after && city != tx.after.copy(revision = tx.beforeRevision)) return conflict("mine-opening-stale-city")
        return checkNative(city, tx)
    }

    private fun checkNative(city: Settlement, tx: CivInventoryTransaction): CivInventoryApplyResult? {
        val receipt = tx.mineOpening ?: return conflict("mine-opening-missing")
        val native = world.scanWorld(city.worldId) ?: return retry("world-unloaded")
        val changes = receipt.cells.associateBy { it.position }
        for (point in CivsMineOpening.positions(receipt.building)) {
            if (point.y !in native.minHeight until native.maxHeight) return conflict("outside-world")
            if (!native.isChunkLoaded(point.x shr 4, point.z shr 4)) return retry("chunks-unloaded")
            val block = native.getBlockAt(point.x, point.y, point.z)
            val current = block.blockData.asString
            val cell = changes[point]
            if (!block.type.isAir && current != cell?.before) return conflict("mine-opening-native-drift")
            val rejection = runCatching {
                world.checkMineOpeningCell(city, receipt.building, block, initialBlock(city, receipt.building, point, current))
            }.exceptionOrNull()
            if (rejection != null) return conflict(CivsDiagnostics.rejection(rejection)?.reason ?: "mine-opening-obstructed")
            if (block.type.isAir) continue
            if (occupied(city.worldId, point) || occupied(city.worldId, point.copy(y = point.y + 1)) ||
                native.getNearbyEntities(BoundingBox(point.x.toDouble(), point.y.toDouble(), point.z.toDouble(),
                    point.x + 1.0, point.y + 3.0, point.z + 1.0)).any { it is LivingEntity }) return retry("mine-opening-occupied")
        }
        return null
    }

    private fun identity(city: Settlement, building: CivBuilding) = Identity(city.id, city.worldId, city.owner,
        building.copy(control = null, production = null))
    private fun conflict(reason: String) = CivInventoryApplyResult.Conflict(mapOf("reason" to reason))
    private fun retry(reason: String) = CivInventoryApplyResult.Retry(mapOf("reason" to reason))
    private fun requireNative(result: CivInventoryApplyResult?) {
        when (result) {
            is CivInventoryApplyResult.Retry -> throw CivsRejection(result.values.getValue("reason"))
            is CivInventoryApplyResult.Conflict -> throw CivsRejection(result.values.getValue("reason"))
            else -> Unit
        }
    }
}
