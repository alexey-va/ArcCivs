package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Leaves
import org.bukkit.entity.LivingEntity
import org.bukkit.util.BoundingBox
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.UUID
import java.util.concurrent.CompletableFuture

private fun requireVegetation(allowed: Boolean, reason: String) {
    if (!allowed) throw CivsRejection(reason)
}

/** One bounded projection of the current verified source; never retained in the protection cache. */
internal data class CivAuthoredVegetationSource(val sha256: String, val cells: List<CivPlanBlock>)

/** Explicit AIR-only restoration; no inventory, worker, construction or production credit. */
data class CivAuthoredVegetationReceipt(
    val building: CivBuilding, val region: CivBox, val sourceSha256: String, val cells: List<CivPlanBlock>,
) {
    internal fun detached() = copy(building = building.copy(transit = building.transit?.toList()), cells = cells.toList())

    internal fun validated(after: Settlement) {
        validateRegion(building, region)
        require(sourceSha256.matches(Regex("[0-9a-f]{64}")))
        require(after.pendingDeletion == null && after.building(building.id) == building &&
            after.construction?.buildingId != building.id && building.id !in after.constructionQueue.orEmpty())
        require(cells.size in 1..128 && cells.map { it.position }.distinct().size == cells.size)
        require(cells.all { region.contains(it.position.x, it.position.y, it.position.z) && air(it.before) && tree(it.after) })
        requireVegetation(cells.none { waterlogged(it.after) }, "vegetation-repair-waterlogged")
    }

    internal companion object {
        const val MAX_SOURCE_CELLS = 2048
        fun air(state: String) = state in setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air")
        fun waterlogged(state: String) = "waterlogged=true" in state.substringAfter('[', "").removeSuffix("]").split(',')
        fun tree(state: String): Boolean {
            val name = Material.matchMaterial(state.substringBefore('['))?.name ?: return false
            return name.endsWith("_LOG") || name.endsWith("_WOOD") || name.endsWith("_LEAVES")
        }

        fun validateRegion(building: CivBuilding, region: CivBox) {
            requireVegetation(building.phase == BuildingPhase.ACTIVE && building.rotation in setOf(0, 90, 180, 270), "invalid-building")
            requireVegetation(region.min.x <= region.max.x && region.min.y <= region.max.y && region.min.z <= region.max.z &&
                building.bounds.contains(region.min.x, region.min.y, region.min.z) &&
                building.bounds.contains(region.max.x, region.max.y, region.max.z), "vegetation-repair-region")
        }

        /** Vanilla recomputes distance after logs arrive. Every other leaf property remains exact. */
        fun afterMatches(actual: BlockData, expected: BlockData): Boolean {
            if (actual.asString == expected.asString) return true
            if (actual !is Leaves || expected !is Leaves || actual.material != expected.material) return false
            val normalized = actual.clone() as Leaves
            normalized.distance = expected.distance
            return normalized.asString == expected.asString
        }

        fun ordered(cells: List<CivPlanBlock>) = cells.sortedWith(compareBy<CivPlanBlock>(
            { it.after.substringBefore('[').endsWith("_leaves") },
            { if (it.after.substringBefore('[').endsWith("_leaves"))
                it.after.substringAfter("distance=", "7").substringBefore(',').substringBefore(']').toIntOrNull() ?: 7 else 0 },
            { it.position.y }, { it.position.x }, { it.position.z }))
    }
}

/** Console-only caller; one captured list is visited once. Restart recovery finishes only its accepted batch. */
internal class CivsAuthoredVegetationRepair(
    private val authority: CivsService,
    private val records: CivsCityRecords,
    private val tasks: LifecycleTaskScope,
    private val project: (CivBuilding, CivBox) -> CompletableFuture<CivAuthoredVegetationSource>,
    private val world: CivsWorld,
    private val storage: CivsStorage,
    private val journal: CivsInventoryJournal,
    private val placing: (UUID) -> Boolean,
    private val occupied: (UUID, CivPosition) -> Boolean,
) {
    fun request(cityId: UUID, buildingId: UUID, region: CivBox): CompletableFuture<Map<String, Any?>> {
        authority.checkMain()
        available(cityId)
        val city = records.cities[cityId] ?: throw CivsRejection("no-city")
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        CivAuthoredVegetationReceipt.validateRegion(building, region)
        requireVegetation(city.pendingDeletion == null && city.construction?.buildingId != buildingId &&
            buildingId !in city.constructionQueue.orEmpty(), "construction-busy")
        val result = authority.tracked<Map<String, Any?>>()
        var reserved = false
        fun reserve() { authority.reserveCity(cityId); reserved = true }
        fun release() { if (reserved) { reserved = false; authority.releaseCity(cityId) } }
        fun fail(error: Throwable) { release(); result.completeExceptionally(error) }
        reserve()
        runCatching { project(building, region) }.getOrElse { fail(it); return result }
            .whenCompleteSync(tasks) projected@ { source, error ->
                if (error != null) { fail(error); return@projected }
                val projection = source ?: run { fail(CivsRejection("vegetation-source-unavailable")); return@projected }
                runCatching {
                    requireVegetation(records.cities[cityId] == city && !records.closed && authority.ready(), "stale-city")
                    world.captureAuthoredVegetation(city, building, projection.cells)
                }.getOrElse { fail(it); return@projected }.whenCompleteSync(tasks) captured@ { native, captureError ->
                    if (captureError != null) { fail(captureError); return@captured }
                    runCatching {
                        requireVegetation(records.cities[cityId] == city && !records.closed && authority.ready(), "stale-city")
                        val all = requireNotNull(native)
                        val targets = CivAuthoredVegetationReceipt.ordered(all.filter { CivAuthoredVegetationReceipt.air(it.before) })
                        val batches = targets.chunked(128)
                        val transactions = mutableListOf<UUID>()
                        val summary = mapOf("city" to cityId, "building" to buildingId, "source_sha256" to projection.sha256,
                            "checked_cells" to all.size, "preserved_cells" to (all.size - targets.size), "changed_cells" to targets.size)
                        release()
                        fun next(index: Int, expected: Settlement) {
                            if (index == batches.size) {
                                result.complete(summary + mapOf("state" to if (targets.isEmpty()) "current" else "committed",
                                    "transactions" to transactions.toList(), "revision" to expected.revision))
                                return
                            }
                            var accepted: CivInventoryTransaction? = null
                            val submitted = runCatching {
                                // Retirement releases the city before this scheduled callback. Ordinary resident work
                                // may own it now; queue normally and preserve that work in the next exact revision.
                                authority.afterCityAvailable(cityId) {
                                    try {
                                        available(cityId)
                                        val current = records.cities[cityId] ?: throw CivsRejection("no-city")
                                        requireVegetation(current.worldId == city.worldId && current.owner == city.owner &&
                                            current.pendingDeletion == null, "stale-city")
                                        requireVegetation(current.building(buildingId) == building &&
                                            current.construction?.buildingId != buildingId &&
                                            buildingId !in current.constructionQueue.orEmpty(), "vegetation-stale-building")
                                        reserve()
                                        val receipt = CivAuthoredVegetationReceipt(building, region, projection.sha256, batches[index]).detached()
                                        val id = UUID.randomUUID()
                                        val tx = CivInventoryTransaction(id, cityId, current.revision,
                                            current.copy(revision = current.revision + 1), emptyList(),
                                            worldChanges = receipt.cells, authoredVegetation = receipt).validated()
                                        requireNative(validateNative(tx))
                                        storage.reserve(current, id)
                                        try { storage.seal(tx, keepViewersOpen = true) }
                                        catch (failure: Exception) { storage.unlock(id); throw failure }
                                        accepted = tx
                                        release()
                                        journal.submit(tx)
                                    } catch (failure: Throwable) {
                                        release()
                                        throw failure
                                    }
                                }
                            }.getOrElse { fail(it); return }
                            submitted.whenCompleteSync(tasks) { saved, journalError ->
                                if (journalError != null) fail(journalError)
                                else {
                                    val tx = requireNotNull(accepted)
                                    transactions += tx.id
                                    CivsDiagnostics.event("authored_vegetation_repaired", "city" to cityId, "building" to buildingId,
                                        "transaction" to tx.id, "changed_cells" to batches[index].size, "source_sha256" to projection.sha256)
                                    next(index + 1, requireNotNull(saved))
                                }
                            }
                        }
                        next(0, city)
                    }.onFailure(::fail)
                }
            }
        return result
    }

    /** The same verified current source and transform authorize fresh admission and restart completion. */
    fun verifySource(tx: CivInventoryTransaction): CompletableFuture<Unit> {
        val receipt = requireNotNull(tx.authoredVegetation)
        receipt.validated(tx.after)
        return project(receipt.building, receipt.region).thenApply { source ->
            requireVegetation(source.sha256 == receipt.sourceSha256, "vegetation-source-changed")
            val expected = source.cells.associate { it.position to it.after }
            requireVegetation(receipt.cells.all { expected[it.position] == it.after }, "vegetation-source-changed")
            Unit
        }
    }

    fun validateNative(tx: CivInventoryTransaction): CivInventoryApplyResult? {
        authority.checkMain()
        val receipt = tx.authoredVegetation ?: return conflict("vegetation-receipt-missing")
        val city = records.cities[tx.cityId] ?: return conflict("no-city")
        if (city != tx.after && city != tx.after.copy(revision = tx.beforeRevision)) return conflict("vegetation-stale-city")
        val native = world.scanWorld(city.worldId) ?: return retry("world-unloaded")
        for (cell in receipt.cells) {
            val point = cell.position
            if (point.y !in native.minHeight until native.maxHeight) return conflict("outside-world")
            if (!native.isChunkLoaded(point.x shr 4, point.z shr 4)) return retry("chunks-unloaded")
            val block = native.getBlockAt(point.x, point.y, point.z)
            val target = Bukkit.createBlockData(cell.after)
            val done = CivAuthoredVegetationReceipt.afterMatches(block.blockData, target)
            if (!done && block.blockData.asString != cell.before) return conflict("vegetation-native-drift")
            val rejection = runCatching { world.checkAuthoredVegetationCell(city, receipt.building, block, target.material) }.exceptionOrNull()
            if (rejection != null) return conflict(CivsDiagnostics.rejection(rejection)?.reason ?: "vegetation-obstructed")
            if (!done && (occupied(city.worldId, point) || native.getNearbyEntities(BoundingBox(
                    point.x.toDouble(), point.y.toDouble(), point.z.toDouble(),
                    point.x + 1.0, point.y + 1.0, point.z + 1.0)).any { it is LivingEntity })) return retry("vegetation-occupied")
        }
        return null
    }

    private fun available(cityId: UUID) {
        requireVegetation(authority.ready() && !records.closed, "not-ready")
        requireVegetation(cityId !in records.busy && cityId !in records.unhealthy && !journal.pending(cityId) && !placing(cityId), "city-busy")
    }
    private fun conflict(reason: String) = CivInventoryApplyResult.Conflict(mapOf("reason" to reason))
    private fun retry(reason: String) = CivInventoryApplyResult.Retry(mapOf("reason" to reason))
    private fun requireNative(value: CivInventoryApplyResult?) {
        when (value) {
            is CivInventoryApplyResult.Retry -> throw CivsRejection(value.values.getValue("reason"))
            is CivInventoryApplyResult.Conflict -> throw CivsRejection(value.values.getValue("reason"))
            else -> Unit
        }
    }
}
