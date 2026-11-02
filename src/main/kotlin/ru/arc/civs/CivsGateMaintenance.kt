package ru.arc.civs

import org.bukkit.Material
import org.bukkit.block.TileState
import org.bukkit.entity.LivingEntity
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/** Explicit one-gate maintenance. Preparation is read-only; the existing receipt owner performs every write. */
internal class CivsGateMaintenance(
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
    private data class Prepared(val receipt: CivGateRefitReceipt, val bytes: ByteArray, val proof: String)

    fun request(cityId: UUID, gateId: UUID, applyProof: String?): CompletableFuture<Map<String, Any?>> {
        authority.checkMain()
        rejectGate(!authority.ready(), "not-ready")
        rejectGate(cityId in records.busy || cityId in records.unhealthy || journal.pending(cityId) || placing(cityId), "city-busy")
        val city = records.cities[cityId] ?: throw CivsRejection("no-city")
        rejectGate(city.pendingDeletion != null, "deletion-pending")
        val gate = city.building(gateId) ?: throw CivsRejection("building-not-found")
        val dataVersion = plugin.server.unsafe.dataVersion
        rejectGate(city.construction?.buildingId == gateId || gateId in city.constructionQueue.orEmpty(), "construction-busy")
        rejectGate(applyProof != null && !applyProof.matches(Regex("[0-9a-f]{64}")), "gate-refit-invalid-proof")
        val result = authority.tracked<Map<String, Any?>>()
        authority.reserveCity(cityId)
        var reserved = true
        fun release() { if (reserved) { reserved = false; authority.releaseCity(cityId) } }
        fun current() {
            rejectGate(!authority.ready() || records.closed, "not-ready")
            rejectGate(records.cities[cityId] != city || placing(cityId), "stale-city")
        }
        fun fail(error: Throwable) { release(); result.completeExceptionally(error); authority.updateHealth() }
        val preparation = runCatching { loadPlans(city).thenCompose { plans ->
            val source = plans.singleOrNull { it.building.id == gateId } ?: throw CivsRejection("gate-refit-source-missing")
            blueprints.recover(source).thenApplyAsync({
                val box = gate.bounds
                val access = CivAutonomousAccess.from(city, plans)
                val captureId = UUID.randomUUID()
                val shape = CivsGateRefit.proposal(city, source, emptyList(), captureId)
                val route = CivsGateRoutes.refit(city, gate, shape.building, source.terrain?.route.orEmpty())
                val routeOverrides = route?.let { mapOf(gate.id.toString() to it) }
                val effectiveRoutes = if (route == null) access.routes else access.routes + (gate.id to route.route)
                val witnesses = (effectiveRoutes.values.flatten() +
                    city.buildings.flatMap { CivsAutonomousGeometry.approach(it) } +
                    city.buildings.filter { it.type == BuildingType.ROAD }.flatMap { it.transit.orEmpty() }).filter {
                    it.x in box.min.x..box.max.x && it.z in box.min.z..box.max.z
                }.distinct().sortedWith(compareBy({ it.x }, { it.y }, { it.z }))
                val draft = CivsGateRefit.proposal(city, source, witnesses, captureId)
                val piece = CivSettlementPiece(gate.id, gate.type, gate.bounds, gate.entrance,
                    draft.blocks.map { CivSettlementBlock(it.position, it.after) }, draft.building.transit.orEmpty(),
                    draft.terrain?.waterFoundations.orEmpty())
                val bytes = CivsInfrastructure.schematic(piece, dataVersion)
                val replacement = draft.copy(capturedSha256 = CivsGateRefit.sha256(bytes))
                val receipt = CivGateRefitReceipt(gate, source, replacement, witnesses, routeOverrides,
                    route?.let { city.roadRouteOverrides?.filterKeys { id -> id == gate.id.toString() }?.takeIf { it.isNotEmpty() } })
                Prepared(receipt, bytes, CivsGateRefit.proof(source, replacement, witnesses, routeOverrides))
            }, executor)
        } }.getOrElse { CompletableFuture.failedFuture(it) }
        preparation.whenCompleteSync(tasks) prepared@ { value, failure ->
            if (failure != null) { fail(failure); return@prepared }
            runCatching {
                current()
                val prepared = requireNotNull(value)
                val receipt = prepared.receipt
                val after = after(city, receipt)
                requireNative(checkNative(city, receipt, initial = true))
                val summary = linkedMapOf<String, Any?>("city" to cityId, "gate" to gateId,
                    "proof" to prepared.proof, "changed_cells" to receipt.changes().size,
                    "transit_before" to gate.transit.orEmpty().size, "transit_after" to receipt.replacement.building.transit.orEmpty().size,
                    "clear_width" to (maxOf(gate.bounds.max.x - gate.bounds.min.x, gate.bounds.max.z - gate.bounds.min.z) - 1),
                    "clear_height" to 3, "bounds_changed" to false,
                    "rerouted_owners" to receipt.routeOverrides.orEmpty().count { (id, route) ->
                        route.route != (receipt.previousRouteOverrides?.get(id)?.route ?: sourceRoute(receipt)) },
                    "route_references_updated" to receipt.routeOverrides.orEmpty().size,
                    "route_cells_before" to (receipt.previousRouteOverrides?.get(gate.id.toString())?.route ?: sourceRoute(receipt)).size,
                    "route_cells_after" to (receipt.routeOverrides?.get(gate.id.toString())?.route ?: sourceRoute(receipt)).size,
                    "route_witnesses" to receipt.witnesses.size, "source_sha256" to receipt.source.capturedSha256,
                    "replacement_sha256" to receipt.replacement.capturedSha256)
                if (receipt.changes().isEmpty()) {
                    rejectGate(applyProof != null && applyProof != prepared.proof, "gate-refit-stale-preview")
                    release()
                    result.complete(summary + mapOf("state" to "current", "capture" to receipt.source.id, "revision" to city.revision))
                    return@prepared
                }
                receipt.validated(after)
                if (applyProof == null) {
                    release(); result.complete(summary + ("state" to "preview")); return@prepared
                }
                rejectGate(applyProof != prepared.proof, "gate-refit-stale-preview")
                // Both immutable captures precede the intent. Publishing only the city reference is the atomic swap.
                store.saveSchematic(receipt.replacement.id, prepared.bytes).thenCompose {
                    store.savePlan(receipt.replacement)
                }.whenCompleteSync(tasks) saved@ { _, saveFailure ->
                    if (saveFailure != null) { fail(saveFailure); return@saved }
                    runCatching {
                        current()
                        requireNative(checkNative(city, receipt, initial = true))
                        val id = UUID.randomUUID()
                        storage.reserve(city, id)
                        val tx = try {
                            CivInventoryTransaction(id, city.id, city.revision, after, emptyList(),
                                worldChanges = receipt.changes(), gateRefit = receipt).also { storage.seal(it, keepViewersOpen = true) }
                        } catch (error: Exception) { storage.unlock(id); throw error }
                        // There is no asynchronous gap between releasing preparation and journal admission.
                        release()
                        journal.submit(tx).whenCompleteSync(tasks) { committed, journalFailure ->
                            if (journalFailure != null) result.completeExceptionally(journalFailure)
                            else result.complete(summary + mapOf("state" to "committed", "transaction" to id,
                                "revision" to requireNotNull(committed).revision, "capture" to receipt.replacement.id))
                        }
                    }.onFailure(::fail)
                }
            }.onFailure(::fail)
        }
        return result
    }

    /** Recovery verifies the same immutable bytes before it can apply or publish a saved intent. */
    fun verifyCaptures(tx: CivInventoryTransaction): CompletableFuture<Unit> {
        val receipt = tx.gateRefit ?: return CompletableFuture.completedFuture(Unit)
        return store.loadPlan(receipt.source.id).thenCombine(store.loadPlan(receipt.replacement.id)) { source, replacement ->
            require(source == receipt.source && replacement == receipt.replacement) { "Gate refit capture changed" }
        }.thenCompose { blueprints.recover(receipt.source) }.thenCompose { blueprints.recover(receipt.replacement) }.thenApply { Unit }
    }

    fun validateNative(tx: CivInventoryTransaction): CivInventoryApplyResult? {
        authority.checkMain()
        val receipt = tx.gateRefit ?: return CivInventoryApplyResult.Conflict(mapOf("reason" to "gate-refit-missing"))
        val city = records.cities[tx.cityId] ?: return CivInventoryApplyResult.Conflict(mapOf("reason" to "no-city"))
        if (city != receipt.before(tx.after) && city != tx.after)
            return CivInventoryApplyResult.Conflict(mapOf("reason" to "gate-refit-stale-city"))
        return checkNative(city, receipt, initial = false)
    }

    private fun after(city: Settlement, receipt: CivGateRefitReceipt): Settlement = CivsRules.validate(receipt.after(city))

    private fun sourceRoute(receipt: CivGateRefitReceipt) = receipt.source.terrain?.route.orEmpty()

    /** Bounded loaded-only reads include unchanged captured cells and the detour's actual paid floor. */
    private fun checkNative(city: Settlement, receipt: CivGateRefitReceipt, initial: Boolean): CivInventoryApplyResult? {
        val native = plugin.server.getWorld(city.worldId)
            ?: return CivInventoryApplyResult.Retry(mapOf("reason" to "world-unloaded"))
        for (cell in receipt.replacement.blocks) {
            val p = cell.position
            if (p.y !in native.minHeight until native.maxHeight)
                return CivInventoryApplyResult.Conflict(mapOf("reason" to "outside-world"))
            if (!native.isChunkLoaded(p.x shr 4, p.z shr 4))
                return CivInventoryApplyResult.Retry(mapOf("reason" to "chunk-unloaded"))
            val block = native.getBlockAt(p.x, p.y, p.z)
            if (block.state is TileState) return CivInventoryApplyResult.Conflict(mapOf("reason" to "world-block-entity"))
            val actual = block.blockData.asString
            if (actual != cell.before && (initial || actual != cell.after))
                return CivInventoryApplyResult.Conflict(mapOf("reason" to "gate-refit-native-drift"))
            if (cell.before == cell.after || actual == cell.after) continue
            val material = Material.matchMaterial(cell.after.substringBefore('['))
                ?: return CivInventoryApplyResult.Conflict(mapOf("reason" to "invalid-world-state"))
            if (!world.canModify(city.owner, block, material))
                return CivInventoryApplyResult.Conflict(mapOf("reason" to "lands-denied"))
            if (!material.isAir && (occupied(city.worldId, p) || native.getNearbyEntities(BoundingBox(
                    p.x.toDouble(), p.y.toDouble(), p.z.toDouble(), p.x + 1.0, p.y + 1.0, p.z + 1.0)).any { it is LivingEntity }))
                return CivInventoryApplyResult.Retry(mapOf("reason" to "gate-refit-occupied"))
        }
        // KEEP_NATIVE floors were deliberately omitted from some old captures. They must still
        // support both the new posts and the public passage; this never grants a replacement floor.
        for (feet in receipt.beforeBuilding.transit.orEmpty()) {
            val floor = native.getBlockAt(feet.x, feet.y - 1, feet.z)
            if (floor.state is TileState || floor.isLiquid || floor.type in CITIZEN_LEISURE_HAZARDS ||
                floor.collisionShape.boundingBoxes.none { .5 in it.minX..it.maxX && .5 in it.minZ..it.maxZ && it.maxY in .5..1.0 })
                return CivInventoryApplyResult.Retry(mapOf("reason" to "gate-refit-floor-unsupported"))
        }
        for (route in receipt.routeOverrides.orEmpty().values) for (detour in route.gateDetours.orEmpty()) {
            val at = route.route.indexOf(detour.after.first())
            val section = route.route.subList(maxOf(0, at - 1), minOf(route.route.size, at + detour.after.size + 1))
            val points = section.map { feet ->
                CivsControlRouteProof.standing(native, feet)
                    ?: return CivInventoryApplyResult.Retry(mapOf("reason" to "gate-refit-route-blocked"))
            }
            if (points.any { !citizenPacketSupported(native, it) || !citizenPacketBodyClear(native, it, false) ||
                    !citizenPacketCampfireClear(native, it) } || points.zipWithNext().any { (a, b) ->
                    !citizenPacketStepClear(native, a, b, false) })
                return CivInventoryApplyResult.Retry(mapOf("reason" to "gate-refit-route-blocked"))
        }
        return null
    }

    private fun requireNative(result: CivInventoryApplyResult?) {
        when (result) {
            is CivInventoryApplyResult.Conflict -> throw CivsRejection("gate-refit-native-conflict", result.values)
            is CivInventoryApplyResult.Retry -> throw CivsRejection("gate-refit-native-unavailable", result.values)
            else -> Unit
        }
    }
}

private fun rejectGate(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
