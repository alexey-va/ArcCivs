package ru.arc.civs

import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Barrel
import org.bukkit.block.Chest
import org.bukkit.block.TileState
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.Inventory
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/** Read-only preparation; the existing exact-state journal owns installation and restart recovery. */
internal class CivsWarehouseMaintenance(
    private val plugin: Plugin,
    private val authority: CivsService,
    private val records: CivsCityRecords,
    private val tasks: LifecycleTaskScope,
    private val executor: Executor,
    private val store: CivsStore,
    private val blueprints: CivsBlueprints,
    private val world: CivsWorld,
    private val storage: CivsStorage,
    private val journal: CivsInventoryJournal,
    private val loadPlans: (Settlement) -> CompletableFuture<List<CivPlacementPlan>>,
    private val placing: (UUID) -> Boolean,
    private val occupied: (UUID, CivPosition) -> Boolean,
) {
    private data class Native(val cells: List<CivPlanBlock>, val containers: List<CivRelocatedContainer>)
    private data class Prepared(val receipt: CivWarehouseRefitReceipt, val bytes: ByteArray, val proof: String)

    fun request(cityId: UUID, buildingId: UUID, applyProof: String?): CompletableFuture<Map<String, Any?>> {
        authority.checkMain()
        rejectWarehouse(!authority.ready(), "not-ready")
        rejectWarehouse(cityId in records.busy || cityId in records.unhealthy || journal.pending(cityId) || placing(cityId), "city-busy")
        val city = records.cities[cityId] ?: throw CivsRejection("no-city")
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        rejectWarehouse(!CivsWarehouseRefit.supported(building) || building.phase != BuildingPhase.ACTIVE, "warehouse-refit-unsupported")
        rejectWarehouse(city.pendingDeletion != null || city.pendingControls.orEmpty().any { it.buildingId == buildingId }, "city-busy")
        rejectWarehouse(city.construction?.buildingId == buildingId || buildingId in city.constructionQueue.orEmpty(), "construction-busy")
        rejectWarehouse(applyProof != null && !applyProof.matches(Regex("[0-9a-f]{64}")), "warehouse-refit-invalid-proof")
        val dataVersion = plugin.server.unsafe.dataVersion
        val result = authority.tracked<Map<String, Any?>>()
        authority.reserveCity(cityId)
        var reserved = true
        fun release() { if (reserved) { reserved = false; authority.releaseCity(cityId) } }
        fun current() {
            rejectWarehouse(!authority.ready() || records.closed, "not-ready")
            rejectWarehouse(records.cities[cityId] != city || placing(cityId), "stale-city")
        }
        fun fail(error: Throwable) { release(); result.completeExceptionally(error); authority.updateHealth() }
        val loaded = runCatching { loadPlans(city).thenCombine(blueprints.interiorControls(building)) { plans, controls ->
            val source = plans.singleOrNull { it.building.id == buildingId } ?: throw CivsRejection("warehouse-refit-source-missing")
            // This loads the hash-verified current family too; a stale exterior control must finish its own journal first.
            val control = controls.firstOrNull { it.position == building.control }
                ?: throw CivsRejection("warehouse-refit-control-pending")
            val access = CivAutonomousAccess.from(city, plans)
            val routes = sections(access.routes.values.toList() + listOf(control.route) +
                city.buildings.flatMap { CivsAutonomousGeometry.approach(it).map(::listOf) }, building.bounds)
            Triple(source, routes, control)
        }.thenCompose { prepared -> blueprints.recover(prepared.first).thenApply { prepared } } }
            .getOrElse { CompletableFuture.failedFuture(it) }
        loaded.whenCompleteSync(tasks) loaded@ { input, loadFailure ->
            if (loadFailure != null) { fail(loadFailure); return@loaded }
            runCatching {
                current()
                val (source, routes) = requireNotNull(input)
                val native = capture(city, building)
                CompletableFuture.supplyAsync({
                    val draft = CivsWarehouseRefit.proposal(city, source, native.cells, routes, native.containers, UUID.randomUUID())
                    val bytes = CivsWarehouseRefit.schematic(draft, dataVersion)
                    val replacement = draft.copy(capturedSha256 = CivsGateRefit.sha256(bytes))
                    val receipt = CivWarehouseRefitReceipt(building, source, replacement, native.cells, routes, native.containers)
                    Prepared(receipt, bytes, CivsWarehouseRefit.proof(receipt))
                }, executor).whenCompleteSync(tasks) prepared@ { value, failure ->
                    if (failure != null) { fail(failure); return@prepared }
                    runCatching {
                        current()
                        val prepared = requireNotNull(value)
                        val receipt = prepared.receipt
                        requireNative(checkNative(city, receipt, initial = true))
                        val summary = linkedMapOf<String, Any?>("city" to cityId, "warehouse" to buildingId,
                            "proof" to prepared.proof, "changed_cells" to receipt.changes().size,
                            "materials" to receipt.changes().groupingBy { it.after.substringBefore('[').removePrefix("minecraft:").uppercase() }.eachCount().toSortedMap(),
                            "bounds" to building.bounds, "bounds_changed" to false, "control_changed" to false,
                            "containers" to receipt.containers.size, "slots_preserved" to receipt.containers.size * STORAGE_SLOTS,
                            "route_cells" to receipt.routes.flatten().distinct().size, "source_capture" to source.id,
                            "source_sha256" to source.capturedSha256, "replacement_sha256" to receipt.replacement.capturedSha256)
                        if (receipt.changes().isEmpty()) {
                            rejectWarehouse(applyProof != null && applyProof != prepared.proof, "warehouse-refit-stale-preview")
                            release(); result.complete(summary + mapOf("state" to "current", "capture" to source.id, "revision" to city.revision))
                            return@prepared
                        }
                        val after = CivsRules.validate(receipt.after(city))
                        receipt.validated(after)
                        if (applyProof == null) {
                            release(); result.complete(summary + ("state" to "preview")); return@prepared
                        }
                        rejectWarehouse(applyProof != prepared.proof, "warehouse-refit-stale-preview")
                        store.saveSchematic(receipt.replacement.id, prepared.bytes).thenCompose { store.savePlan(receipt.replacement) }
                            .whenCompleteSync(tasks) saved@ { _, saveFailure ->
                                if (saveFailure != null) { fail(saveFailure); return@saved }
                                runCatching {
                                    current()
                                    requireNative(checkNative(city, receipt, initial = true))
                                    val id = UUID.randomUUID()
                                    storage.refreshBuilding(city, building)
                                    storage.reserve(city, id)
                                    val transaction = try {
                                        CivInventoryTransaction(id, city.id, city.revision, after, emptyList(),
                                            worldChanges = receipt.changes(), warehouseRefit = receipt).also { storage.seal(it) }
                                    } catch (error: Exception) { storage.unlock(id); throw error }
                                    release()
                                    journal.submit(transaction).whenCompleteSync(tasks) { committed, journalFailure ->
                                        if (journalFailure != null) result.completeExceptionally(journalFailure)
                                        else result.complete(summary + mapOf("state" to "committed", "transaction" to id,
                                            "revision" to requireNotNull(committed).revision, "capture" to receipt.replacement.id))
                                    }
                                }.onFailure(::fail)
                            }
                    }.onFailure(::fail)
                }
            }.onFailure(::fail)
        }
        return result
    }

    fun verifyCaptures(tx: CivInventoryTransaction): CompletableFuture<Unit> {
        val receipt = tx.warehouseRefit ?: return CompletableFuture.completedFuture(Unit)
        return store.loadPlan(receipt.source.id).thenCombine(store.loadPlan(receipt.replacement.id)) { source, replacement ->
            require(source == receipt.source && replacement == receipt.replacement &&
                CivsWarehouseRefit.matchesCapture(requireNotNull(tx.after.building(receipt.beforeBuilding.id)), replacement)) {
                "Warehouse refit capture changed"
            }
        }.thenCompose { blueprints.recover(receipt.source) }.thenCompose { blueprints.recover(receipt.replacement) }.thenApply { Unit }
    }

    fun validateNative(tx: CivInventoryTransaction): CivInventoryApplyResult? {
        authority.checkMain()
        val receipt = tx.warehouseRefit ?: return conflict("warehouse-refit-missing")
        val city = records.cities[tx.cityId] ?: return conflict("no-city")
        if (city != receipt.before(tx.after) && city != tx.after) return conflict("warehouse-refit-stale-city")
        return checkNative(city, receipt, initial = false)
    }

    private fun capture(city: Settlement, building: CivBuilding): Native {
        val native = plugin.server.getWorld(city.worldId) ?: throw CivsRejection("world-unloaded")
        val box = building.bounds
        val cells = ArrayList<CivPlanBlock>(805)
        val containers = mutableListOf<CivRelocatedContainer>()
        for (y in box.min.y..box.max.y) for (z in box.min.z..box.max.z) for (x in box.min.x..box.max.x) {
            rejectWarehouse(y !in native.minHeight until native.maxHeight || !native.isChunkLoaded(x shr 4, z shr 4), "warehouse-refit-unloaded")
            val block = native.getBlockAt(x, y, z); val state = block.state; val data = block.blockData.asString
            val p = CivPosition(x, y, z)
            rejectWarehouse(block.type == Material.BARRIER || block.type == Material.STRUCTURE_VOID, "warehouse-refit-native-drift")
            cells += CivPlanBlock(p, data, data)
            val inventory = inventory(state)
            rejectWarehouse(state is TileState && inventory == null, "warehouse-refit-foreign-block-entity")
            if (inventory != null) {
                rejectWarehouse(inventory.viewers.isNotEmpty(), "storage-busy")
                val contents = inventory.storageContents.map(CivResidentItem::of)
                rejectWarehouse(contents.size != STORAGE_SLOTS, "warehouse-refit-container-size")
                containers += CivRelocatedContainer(p, block.type.name, data, contents, contents)
            }
        }
        return Native(cells, containers)
    }

    private fun checkNative(city: Settlement, receipt: CivWarehouseRefitReceipt, initial: Boolean): CivInventoryApplyResult? {
        val native = plugin.server.getWorld(city.worldId) ?: return retry("world-unloaded")
        val final = receipt.replacement.blocks.associate { it.position to it.after }
        val holders = receipt.containers.associateBy { it.position }
        for (cell in receipt.beforeCells) {
            val p = cell.position
            if (p.y !in native.minHeight until native.maxHeight || !native.isChunkLoaded(p.x shr 4, p.z shr 4)) return retry("chunk-unloaded")
            val block = native.getBlockAt(p.x, p.y, p.z)
            val actual = block.blockData.asString
            val after = final[p] ?: cell.before
            if (actual != cell.before && (initial || actual != after)) return conflict("warehouse-refit-native-drift")
            val state = block.state
            if (state is TileState) {
                val holder = holders[p] ?: return conflict("warehouse-refit-foreign-block-entity")
                val inventory = inventory(state) ?: return conflict("warehouse-refit-container-drift")
                if (inventory.size != STORAGE_SLOTS || inventory.storageContents.map(CivResidentItem::of) != holder.before)
                    return conflict("warehouse-refit-inventory-drift")
            } else if (p in holders) return conflict("warehouse-refit-container-drift")
            if (cell.before == after || actual == after) continue
            val material = Material.matchMaterial(after.substringBefore('[')) ?: return conflict("invalid-world-state")
            if (!world.canModify(city.owner, block, material)) return conflict("lands-denied")
            if (occupied(city.worldId, p) || native.getNearbyEntities(BoundingBox(p.x.toDouble(), p.y.toDouble(), p.z.toDouble(),
                    p.x + 1.0, p.y + 1.0, p.z + 1.0)).any { it is LivingEntity }) return retry("warehouse-refit-occupied")
            if (p.y == receipt.beforeBuilding.bounds.min.y + 1) {
                val floor = native.getBlockAt(p.x, p.y - 1, p.z)
                if (floor.state is TileState || floor.isLiquid || floor.type in CITIZEN_LEISURE_HAZARDS ||
                    floor.collisionShape.boundingBoxes.none { .5 in it.minX..it.maxX && .5 in it.minZ..it.maxZ && it.maxY in .5..1.0 })
                    return retry("warehouse-refit-floor-unsupported")
            }
        }
        for (route in receipt.routes) {
            val points = route.map { p -> CivsControlRouteProof.standing(native, p, allowDoors = true)
                ?: return retry("warehouse-refit-route-blocked") }
            if (points.zipWithNext().any { (a, b) -> !citizenPacketStepClear(native, a, b, true) })
                return retry("warehouse-refit-route-blocked")
        }
        return null
    }

    private fun inventory(state: org.bukkit.block.BlockState): Inventory? = when (state) {
        is Chest -> state.blockInventory
        is Barrel -> state.inventory
        else -> null
    }

    /** Keep the current owned route section and its adjoining endpoints, including the control approach. */
    private fun sections(routes: List<List<CivPosition>>, box: CivBox): List<List<CivPosition>> = routes.flatMap { route ->
        val selected = route.indices.filter { box.contains(route[it].x, route[it].y, route[it].z) }
        if (selected.isEmpty()) emptyList() else {
            val slice = route.subList(maxOf(0, selected.first() - 1), minOf(route.size, selected.last() + 2))
            (slice.indices step 63).map { start -> slice.subList(start, minOf(slice.size, start + 64)).toList() }
        }
    }.filter { it.isNotEmpty() }.distinct()

    private fun requireNative(result: CivInventoryApplyResult?) {
        when (result) {
            is CivInventoryApplyResult.Conflict -> throw CivsRejection("warehouse-refit-native-conflict", result.values)
            is CivInventoryApplyResult.Retry -> throw CivsRejection("warehouse-refit-native-unavailable", result.values)
            else -> Unit
        }
    }
    private fun conflict(reason: String) = CivInventoryApplyResult.Conflict(mapOf("reason" to reason))
    private fun retry(reason: String) = CivInventoryApplyResult.Retry(mapOf("reason" to reason))
}

private fun rejectWarehouse(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
