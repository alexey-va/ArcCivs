package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicException
import ru.arc.paper.api.ArcSchematicFailure
import ru.arc.paper.api.ArcSchematicOperation
import ru.arc.paper.api.ArcSchematicSnapshot
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.logging.Level

/** Main-thread construction queue, native-write fences and captured batch lifecycle. */
internal class CivsConstructionRuntime(
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val world: CivsWorld,
    private val blueprints: CivsBlueprints,
    private val store: CivsStore,
    private val executor: Executor,
    private val physics: CivsPhysics,
    private val storage: CivsStorage,
    private val citizens: CivsCitizens,
    private val footTraffic: CivsFootTraffic,
    private val settlements: () -> Map<UUID, Settlement>,
    private val busy: Set<UUID>,
    private val unhealthy: Set<UUID>,
    private val ready: () -> Boolean,
    private val isClosed: () -> Boolean,
    private val settlementActive: (Settlement) -> Boolean,
    private val autonomousAccessReady: (Settlement) -> Boolean,
    private val afterCityAvailable: (UUID, () -> CompletableFuture<Settlement>) -> CompletableFuture<Settlement>,
    private val mutate: (UUID, (Settlement) -> Settlement) -> CompletableFuture<Settlement>,
    private val activateBuilding: (Settlement, CivBuilding) -> CompletableFuture<Unit>,
    private val payBatch: (Settlement, CivConstruction, Map<String, Long>) -> CompletableFuture<Settlement>,
    private val updateHealth: () -> Unit,
    private val clock: () -> Long,
) : AutoCloseable {
    private val cities get() = settlements()
    private val closed get() = isClosed()
    private class PlacementAccess(val plan: CivPlacementPlan, val materials: Map<CivPosition, Material>,
                                  val containersChanged: Boolean, val physicsCells: List<CivPlanBlock>,
                                  val expectedCells: Map<CivPosition, CivPlanBlock>,
                                  val batch: CivBuildBatch? = null, val nextWorkplace: CivPosition? = null,
                                  val roadSurfaces: Set<CivPosition> = emptySet()) {
        var repairing = false
        var feedbackFailed = false
    }
    private data class ConstructionFocus(val orderId: UUID, val position: CivPosition, val tick: Int)
    private data class ConstructionSupplyNeed(val orderId: UUID, val batch: Int, val materials: Map<String, Long>)
    private data class RecoveredPlacement(val plan: CivPlacementPlan, val snapshot: ArcSchematicSnapshot,
        val access: PlacementAccess, val batch: CivBuildBatch?, val validationPlan: CivPlacementPlan)
    private val placing = mutableSetOf<UUID>()
    private val placementRuns = mutableMapOf<UUID, UUID>()
    private val placementAccess = mutableMapOf<UUID, PlacementAccess>()
    private val constructionFocus = mutableMapOf<UUID, ConstructionFocus>()
    private val constructionSupplyNeeds = mutableMapOf<UUID, ConstructionSupplyNeed>()
    private val constructionSites = mutableMapOf<UUID, CompletableFuture<CivConstructionSite>>()
    private val operations = mutableMapOf<UUID, ArcSchematicOperation>()
    private val terraformOperations = mutableMapOf<UUID, CompletableFuture<Unit>>()
    private val constructionRetryAfter = mutableMapOf<UUID, Long>()
    private val legacyPlacementRetries = mutableSetOf<UUID>()
    private var nextMaterialCheckAt = 0L
    private var materialCursor = 0
    private val queueFailures = mutableMapOf<UUID, Pair<UUID, String>>()
    private var nextQueueAt = 0L
    private var queueCursor = 0

    val activeCount: Int get() = placing.size
    fun isPlacing(cityId: UUID): Boolean = cityId in placing
    fun focus(cityId: UUID, orderId: UUID): CivPosition? =
        constructionFocus[cityId]?.takeIf { it.orderId == orderId }?.position

    fun supplyNeed(city: Settlement): Map<String, Long>? = constructionSupplyNeeds[city.id]?.takeIf {
        it.orderId == city.construction?.id && it.batch == city.construction?.workDone
    }?.materials

    fun materialsChanged() { nextMaterialCheckAt = 0L }
    fun queueFailure(cityId: UUID, buildingId: UUID?): String? =
        queueFailures[cityId]?.takeIf { it.first == buildingId }?.second
    fun clearQueueFailure(cityId: UUID) { queueFailures.remove(cityId) }
    fun clearQueueFailures() { queueFailures.clear() }
    fun resetSites() { constructionSites.clear() }

    fun loadLegacyRetries(records: Collection<Settlement>) {
        legacyPlacementRetries.clear()
        legacyPlacementRetries.addAll(records.mapNotNull { city -> city.construction?.takeIf {
            it.phase == ConstructionPhase.PAUSED && it.failure in setOf("placement-failed", "chunk-unloaded", "chunks-unloaded", "world-unavailable")
        }?.id })
    }

    fun retain() {
        constructionFocus.entries.removeIf { (cityId, focus) -> cities[cityId]?.construction?.id != focus.orderId }
        constructionSupplyNeeds.entries.removeIf { (cityId, needs) -> cities[cityId]?.construction?.id != needs.orderId }
        constructionSites.keys.retainAll(cities.values.mapNotNull { it.construction?.id }.toSet())
    }

    fun debug(city: Settlement): Map<String, Any?> {
        val order = city.construction
        return mapOf("city" to city.id, "revision" to city.revision, "busy" to (city.id in busy),
            "unhealthy" to (city.id in unhealthy), "placing" to (city.id in placing),
            "operation_pending" to (city.id in operations || city.id in terraformOperations),
            "terrain_batch" to (placementAccess[city.id]?.batch?.terrainStart != null),
            "native_run" to placementRuns[city.id],
            "focus" to constructionFocus[city.id]?.position,
            "supply_need" to constructionSupplyNeeds[city.id]?.materials?.entries?.take(8)?.associate { it.toPair() },
            "queue_total" to city.constructionQueue.orEmpty().size, "queue" to city.constructionQueue.orEmpty().take(8),
            "order" to order?.let { mapOf("id" to it.id, "building" to it.buildingId, "phase" to it.phase,
                "done" to it.workDone, "required" to it.workRequired, "credit" to it.workCredit,
                "blocks_placed" to it.blocksPlaced, "blocks_required" to it.blocksRequired,
                "preparation_workplace" to it.preparationWorkplace,
                "failure" to it.failure, "reserved_batch" to it.payment?.reservedBatch,
                "awaiting" to it.payment?.awaiting?.entries?.take(8)?.associate { row -> row.toPair() }) })
    }

    fun permitsSchematicMutation(worldId: UUID, x: Int, y: Int, z: Int): Boolean {
        if (!ready()) return false
        val position = CivPosition(x, y, z)
        val access = placementAccess.values.firstOrNull { access ->
            val box = access.plan.building.bounds
            access.plan.worldId == worldId && if (access.repairing) {
                x.toLong() in (box.min.x.toLong() - 1)..(box.max.x.toLong() + 1) &&
                    y.toLong() in (box.min.y.toLong() - 1)..(box.max.y.toLong() + 1) &&
                    z.toLong() in (box.min.z.toLong() - 1)..(box.max.z.toLong() + 1)
            } else box.contains(x, y, z) && position in access.materials
        } ?: return false
        val city = cities[access.plan.cityId] ?: return false
        if (city.id !in placing || city.id in unhealthy || city.worldId != worldId) return false
        val targetWorld = plugin.server.getWorld(worldId) ?: return false
        if (y !in targetWorld.minHeight until targetWorld.maxHeight || !targetWorld.isChunkLoaded(x shr 4, z shr 4)) return false
        val block = targetWorld.getBlockAt(x, y, z)
        if (!access.repairing &&
            (block.getState(false) as? org.bukkit.block.Container)?.inventory?.isEmpty == false) return false
        if (!access.repairing && city.autonomy != null) {
            val expected = access.expectedCells[position] ?: return false
            val current = block.blockData
            if (!CivsWorld.sameConstructionState(current, Bukkit.createBlockData(expected.before)) &&
                !CivsWorld.sameConstructionState(current, Bukkit.createBlockData(expected.after))) {
                CivsDiagnostics.record(Level.WARNING, city.id, CivsDiagnostics.line("construction_write_denied", "city" to city.id,
                    "order" to access.plan.id, "batch" to city.construction?.workDone, "reason" to "autonomy-terrain",
                    "world" to worldId, "x" to x, "y" to y, "z" to z,
                    "before" to expected.before, "after" to expected.after, "actual" to current.asString), plugin.logger)
                return false
            }
        }
        // Preflight can precede this write by many ticks; Lands authority must still be current.
        return world.canModify(city.owner, block, if (access.repairing) block.type else access.materials.getValue(position))
    }

    fun stop(cityId: UUID): CompletableFuture<Unit> {
        val operation = operations[cityId]
        releasePlacement(cityId) // Fence every outstanding prepare/revalidate/paste callback before cancelling native work.
        if (operation == null) return CompletableFuture.completedFuture(Unit)
        operation.cancel()
        return operation.completion.handle { _, _ -> Unit }
    }

    fun pulseQueue() {
        if (!ready() || now() < nextQueueAt) return
        nextQueueAt = now() + 5_000L
        val candidates = cities.values.filter { city ->
            city.autonomy?.paused != true && city.construction == null && city.pendingDeletion == null && city.constructionQueue.orEmpty().isNotEmpty() &&
                autonomousAccessReady(city) &&
                city.id !in busy && city.id !in unhealthy && city.id !in placing &&
                settlementActive(city)
        }
        if (candidates.isEmpty()) return
        val city = candidates[Math.floorMod(queueCursor++, candidates.size)]
        val building = city.building(city.constructionQueue!!.first()) ?: return
        val target = plugin.server.getWorld(city.worldId) ?: return
        if (!target.isChunkLoaded(building.entrance.x shr 4, building.entrance.z shr 4)) return
        fun failed(failure: Throwable) {
            val reason = CivsDiagnostics.rejection(failure)?.reason ?: "placement-failed"
            if (queueFailures.put(city.id, building.id to reason) != (building.id to reason)) {
                CivsDiagnostics.event("construction_queue_waiting", "city" to city.id, "building" to building.id, "reason" to reason)
                if (CivsDiagnostics.rejection(failure) == null) log("start queued building ${building.id}", failure, city.id)
            }
        }
        runCatching { activateBuilding(city, building) }.onSuccess { future -> future.whenCompleteSync(tasks) { _, failure ->
            if (failure != null) failed(failure)
            else {
                queueFailures.remove(city.id)
                CivsDiagnostics.event("construction_dequeued", "city" to city.id, "building" to building.id)
            }
        } }.onFailure(::failed)
    }

    fun pulse() {
        if (!ready()) return
        if (placing.size < 2 && now() >= nextMaterialCheckAt) {
            nextMaterialCheckAt = now() + 5_000L
            val waiting = cities.values.filter { city ->
                city.construction?.phase == ConstructionPhase.WAITING_MATERIALS && now() >= (constructionRetryAfter[city.id] ?: 0L) && city.id !in busy && city.id !in unhealthy && city.pendingDeletion == null &&
                    autonomousAccessReady(city) &&
                    city.id !in placing && city.pendingControls.orEmpty().none { it.buildingId == city.construction.buildingId } &&
                    plugin.server.getWorld(city.worldId)?.let { target ->
                        city.construction.plannedBuilding?.entrance?.let { target.isChunkLoaded(it.x shr 4, it.z shr 4) }
                    } == true
            }
            if (waiting.isNotEmpty()) {
                val city = waiting[Math.floorMod(materialCursor++, waiting.size)]
                val order = requireNotNull(city.construction)
                // Reprice the unpaid row from its immutable plan, including old free-water requests.
                startPlacement(city, order)
            }
        }
        for (city in cities.values.toList()) {
            if (placing.size >= 2) break
            val order = city.construction ?: continue
            if (city.autonomy?.paused == true) continue
            if (!autonomousAccessReady(city)) continue
            if (city.id in busy || city.id in unhealthy || city.id in placing || city.pendingDeletion != null) continue
            if (city.pendingControls.orEmpty().any { it.buildingId == order.buildingId }) continue
            if (now() < (constructionRetryAfter[city.id] ?: 0L)) continue
            // A reloaded city sleeps until its destination chunks are already available.
            val b = order.plannedBuilding ?: continue
            val w = plugin.server.getWorld(city.worldId) ?: continue
            if (!w.isChunkLoaded(b.entrance.x shr 4, b.entrance.z shr 4)) continue
            if (order.phase == ConstructionPhase.PAUSED && order.id in legacyPlacementRetries) {
                // Older releases flattened temporary chunk unload into this generic failure. Try its current row once.
                legacyPlacementRetries -= order.id
                mutate(city.id) { CivsRules.retryConstruction(it, it.owner, order.id) }
                    .whenComplete { _, failure -> if (failure != null) log("resume legacy construction ${order.id}", failure, city.id) }
                continue
            }
            if (order.phase !in setOf(ConstructionPhase.READY_TO_PLACE, ConstructionPhase.PLACING)) continue
            startPlacement(city, order)
        }
    }

    /** Read once per order off-thread; retain only geometry needed to keep a builder outside future writes. */
    fun site(cityId: UUID, orderId: UUID): CivConstructionSite? {
        if (!ready()) return null
        val city = cities[cityId] ?: return null
        val order = city.construction?.takeIf { it.id == orderId } ?: return null
        val pending = constructionSites[orderId] ?: store.loadPlan(orderId).thenApplyAsync({ plan ->
            requireNotNull(plan) { "Missing captured construction plan $orderId" }
            require(plan.cityId == cityId && plan.worldId == city.worldId && plan.building == order.plannedBuilding)
            civsConstructionSite(plan)
        }, executor).also { load ->
            constructionSites[orderId] = load
            load.whenCompleteSync(tasks) { _, failure ->
                if (closed || constructionSites[orderId] !== load || cities[cityId]?.construction?.id != orderId) return@whenCompleteSync
                if (failure != null) {
                    log("load construction workplace $orderId", failure, cityId)
                    tasks.runLater(100) { if (constructionSites[orderId] === load) constructionSites.remove(orderId) }
                }
            }
        }
        return if (pending.isDone && !pending.isCompletedExceptionally) pending.getNow(null) else null
    }

    private fun startPlacement(city: Settlement, order: CivConstruction) {
        placing += city.id
        val run = UUID.randomUUID().also { placementRuns[city.id] = it }
        store.loadPlan(order.id).thenCompose { plan ->
            requireNotNull(plan) { "Missing captured construction plan ${order.id}" }
            require(plan.cityId == city.id && plan.worldId == city.worldId && plan.building == order.plannedBuilding)
            blueprints.recover(plan).thenApplyAsync({ snapshot ->
                require(plan.blocks.size in 1..CivsStore.MAX_PLAN_BLOCKS) { "Invalid construction plan size" }
                val palette = mutableMapOf<String, Material>()
                val batches = order.batches?.let { civsConstructionBatches(plan, order) }
                val batch = order.batches?.let { config ->
                    requireNotNull(batches)
                    require(batches.size == config.total && order.workDone in batches.indices)
                    batches[order.workDone]
                }
                val cells = civsBatchBlocks(plan, batch)
                val materials = cells.associate { cell ->
                    val name = cell.after.substringBefore('[')
                    cell.position to palette.getOrPut(name) {
                        Material.matchMaterial(name)?.takeIf { it.isBlock } ?: throw CivsRejection("invalid-plan")
                    }
                }
                val containerTypes = setOf("minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel")
                val containersChanged = cells.any { it.before.substringBefore('[') in containerTypes || it.after.substringBefore('[') in containerTypes }
                RecoveredPlacement(plan, snapshot, PlacementAccess(plan, materials.filterKeys { p ->
                    batch == null || p.x in batch.minimum.x..batch.maximum.x && p.y in batch.minimum.y..batch.maximum.y && p.z in batch.minimum.z..batch.maximum.z
                }, containersChanged, cells.filter { it.before != it.after }, cells.associateBy(CivPlanBlock::position),
                    batch, batches?.getOrNull(order.workDone + 1)?.workplace,
                    plan.terrain?.preparedRouteSurfaces()?.mapTo(hashSetOf(), CivPlanBlock::position).orEmpty()), batch, plan.copy(blocks = cells))
            }, executor)
        }.whenCompleteSync(tasks) { recovered, failure ->
            if (closed || placementRuns[city.id] != run) return@whenCompleteSync
            if (failure != null) return@whenCompleteSync placementFailed(city.id, order.id, failure)
            val (plan, snapshot, access, batch, batchPlan) = requireNotNull(recovered)
            val validation = if (batch?.terrainStart != null || batchPlan.blocks.isEmpty()) CompletableFuture.completedFuture(Unit)
                else world.revalidate(batchPlan, city.owner)
            validation.whenCompleteSync(tasks) { _, validationFailure ->
                if (closed || placementRuns[city.id] != run) return@whenCompleteSync
                if (validationFailure != null) return@whenCompleteSync placementFailed(city.id, order.id, validationFailure)
                runCatching {
                    val automaticPreparation = batch?.terrainStart != null && plan.terrain?.route?.isEmpty() == true &&
                        order.workDone < (order.automaticPreparationBatches ?: 0)
                    val required = if (order.payment != null && order.payment.reservedBatch == null)
                        if (automaticPreparation) emptyMap() else CivsMaterials.required(batchPlan) else null
                    if (required != null) constructionSupplyNeeds[city.id] = ConstructionSupplyNeed(order.id, order.workDone, required)
                    val begin = afterCityAvailable(city.id) {
                        rejectIf(placementRuns[city.id] != run, "stale-construction")
                        val current = cities[city.id] ?: throw CivsRejection("no-city")
                        rejectIf(current.construction?.id != order.id || current.construction.workDone != order.workDone,
                            "stale-construction")
                        if (required != null) payBatch(current, requireNotNull(current.construction), required)
                        else mutate(city.id) { CivsRules.beginPlacement(it, it.owner, order.id) }
                    }
                    begin
                        .whenCompleteSync(tasks) { paid, beginFailure ->
                            if (closed || placementRuns[city.id] != run) return@whenCompleteSync
                            if (beginFailure != null) return@whenCompleteSync placementFailed(city.id, order.id, beginFailure)
                            if (required != null && paid?.construction?.payment?.reservedBatch != order.workDone) {
                                releasePlacement(city.id)
                                runCatching {
                                    afterCityAvailable(city.id) {
                                        mutate(city.id) { CivsRules.awaitMaterials(it, it.owner, order.id, order.workDone, required) }
                                    }
                                        .whenComplete { _, waitFailure ->
                                            if (waitFailure != null) log("wait for construction materials ${order.id}", waitFailure, city.id)
                                            else CivsDiagnostics.event("construction_waiting_materials", "city" to city.id,
                                                "order" to order.id, "batch" to order.workDone, "materials" to required)
                                        }
                                }.onFailure { placementFailed(city.id, order.id, it) }
                                return@whenCompleteSync
                            }
                            if (batch?.terrainStart != null) {
                                runCatching { pasteTerraform(city, order, plan, snapshot, access, batchPlan.blocks, run) }
                                    .onFailure { placementFailed(city.id, order.id, it) }
                                return@whenCompleteSync
                            }
                            blueprints.pasteParts(snapshot, plan.building, batch).whenCompleteSync(tasks) { parts, cropFailure ->
                                if (closed || placementRuns[city.id] != run) return@whenCompleteSync
                                if (cropFailure != null) placementFailed(city.id, order.id, cropFailure)
                                else {
                                    CivsDiagnostics.event("construction_parts_prepared", "city" to city.id, "order" to order.id,
                                        "batch" to order.workDone, "parts" to requireNotNull(parts).size,
                                        "control" to plan.building.control, "captured_sha256" to plan.capturedSha256)
                                    pasteCaptured(city.id, order, plan, parts, snapshot, access)
                                }
                            }
                        }
                }.onFailure { placementFailed(city.id, order.id, it) }
            }
        }
    }

    private fun pasteCaptured(cityId: UUID, order: CivConstruction, plan: CivPlacementPlan, parts: List<ArcSchematicSnapshot>,
        fullSnapshot: ArcSchematicSnapshot, access: PlacementAccess, partIndex: Int = 0) {
        val orderId = order.id
        runCatching {
            rejectIf(!ready() || cityId !in placing || cityId in unhealthy, "not-ready")
            require(parts.isNotEmpty() && partIndex in parts.indices) { "Empty construction tile" }
            placementAccess[cityId] = access
            CivsDiagnostics.event("construction_part_started", "city" to cityId, "order" to orderId,
                "batch" to order.workDone, "part" to partIndex + 1, "parts" to parts.size)
            val operation = blueprints.paste(parts[partIndex], plan.worldId, plan.building.anchor,
                java.util.function.Consumer { cell -> constructionPlaced(cityId, orderId, access, cell) },
                java.util.function.BooleanSupplier {
                    ready() && cities[cityId]?.autonomy?.paused != true && placementAccess[cityId] === access && !access.repairing &&
                        cities[cityId]?.construction?.let { it.id == orderId && it.workDone == order.workDone } == true &&
                        constructionFocus[cityId]?.let { it.orderId != orderId || Bukkit.getCurrentTick() - it.tick >= 5 } != false &&
                        citizens.constructionReady(cityId, orderId)
                })
            operations[cityId] = operation
            operation.completion.whenCompleteSync(tasks) { mutation, failure ->
                if (placementAccess[cityId] !== access) return@whenCompleteSync
                operations.remove(cityId)
                CivsDiagnostics.event("construction_part_finished", "city" to cityId, "order" to orderId,
                    "batch" to order.workDone, "part" to partIndex + 1, "changed" to mutation?.changed,
                    "skipped" to mutation?.skipped, "cancelled" to mutation?.cancelled,
                    "reason" to CivsDiagnostics.rejection(failure)?.reason)
                if (failure != null || mutation?.cancelled != false) {
                    placementFailed(cityId, orderId, failure ?: CivsRejection("placement-cancelled"))
                } else if (partIndex + 1 < parts.size) {
                    pasteCaptured(cityId, order, plan, parts, fullSnapshot, access, partIndex + 1)
                } else completePlacementBatch(cityId, order, plan, fullSnapshot, access)
            }
        }.onFailure { placementFailed(cityId, orderId, it) }
    }

    private fun pasteTerraform(city: Settlement, order: CivConstruction, plan: CivPlacementPlan,
        snapshot: ArcSchematicSnapshot, access: PlacementAccess, cells: List<CivPlanBlock>, run: UUID) {
        placementAccess[city.id] = access
        CivsDiagnostics.event("terraform_batch_started", "city" to city.id, "order" to order.id,
            "batch" to order.workDone, "cells" to cells.size, "workplace" to order.preparationWorkplace)
        var lastPlacedTick = Int.MIN_VALUE
        val automaticPreparation = plan.terrain?.route?.isEmpty() == true &&
            order.workDone < (order.automaticPreparationBatches ?: 0)
        val operation = world.applyTerraform(plan, city.owner, cells, requireNotNull(access.batch?.terrainStart), {
            ready() && placementRuns[city.id] == run && placementAccess[city.id] === access &&
                cities[city.id]?.autonomy?.paused != true &&
                cities[city.id]?.construction?.let { it.id == order.id && it.workDone == order.workDone } == true &&
                (lastPlacedTick == Int.MIN_VALUE || Bukkit.getCurrentTick() - lastPlacedTick >= 1) &&
                (automaticPreparation || citizens.constructionReady(city.id, order.id))
        }, { cell ->
            lastPlacedTick = Bukkit.getCurrentTick()
            if (cell.position in access.roadSurfaces) footTraffic.registerSurface(city.id, cell.position)
            constructionFocus[city.id] = ConstructionFocus(order.id, cell.position, lastPlacedTick)
            constructionCosmetic(access, order.id, cell.position) {
                citizens.constructionPlaced(city.id, order.id, cell.position,
                    Material.matchMaterial(cell.after.substringBefore('[')) ?: Material.AIR)
            }
        })
        terraformOperations[city.id] = operation
        operation.whenCompleteSync(tasks) { _, failure ->
            if (placementRuns[city.id] != run || placementAccess[city.id] !== access) return@whenCompleteSync
            terraformOperations.remove(city.id)
            if (failure != null) placementFailed(city.id, order.id, failure)
            else {
                CivsDiagnostics.event("terraform_batch_applied", "city" to city.id, "order" to order.id,
                    "batch" to order.workDone, "cells" to cells.size, "workplace" to order.preparationWorkplace)
                completePlacementBatch(city.id, order, plan, snapshot, access)
            }
        }
    }

    private fun completePlacementBatch(cityId: UUID, order: CivConstruction, plan: CivPlacementPlan,
        snapshot: ArcSchematicSnapshot, access: PlacementAccess) {
        val orderId = order.id
        // The prefix and its next reachable work front are committed together; restart never restarts the terrace.
        val completedCells = access.physicsCells.size
        runCatching {
            afterCityAvailable(cityId) {
                rejectIf(placementAccess[cityId] !== access, "stale-construction")
                mutate(cityId) { current ->
                    rejectIf(current.construction?.id != orderId || current.construction.workDone != order.workDone,
                        "stale-construction")
                    if (order.batches == null) CivsRules.completeConstruction(current, current.owner, orderId)
                    else CivsRules.completeBatch(current, current.owner, orderId).let { completed ->
                        completed.construction?.let { remaining -> completed.copy(construction = remaining.copy(
                            preparationWorkplace = access.nextWorkplace,
                            blocksPlaced = remaining.blocksRequired?.let { (order.blocksPlaced ?: 0) + completedCells }))
                        } ?: completed
                    }
                }
            }.whenCompleteSync(tasks) { saved, failure ->
                if (failure != null) placementFailed(cityId, orderId, failure)
                else {
                    CivsDiagnostics.event("construction_batch_committed", "city" to cityId, "order" to orderId,
                        "completed" to (order.workDone + 1), "total" to order.workRequired,
                        "terrain" to (access.batch?.terrainStart != null), "blocks" to completedCells)
                    if (access.containersChanged) saved?.let { storage.refreshBuilding(it, plan.building) }
                    if (saved?.construction == null) repairCommitted(cityId, plan, snapshot)
                    else {
                        releasePlacement(cityId)
                        // Native neighbour updates can move sand or alter leaf states in later terrain batches.
                        // Earthworks own the full ordered cut/fill prefix; schematic physics remains unchanged.
                        if (access.batch?.terrainStart == null) physics.enqueue(plan.worldId, access.physicsCells)
                        tasks.runLater(1) { pulse() }
                    }
                }
            }
        }.onFailure { placementFailed(cityId, orderId, it) }
    }

    private fun constructionPlaced(cityId: UUID, orderId: UUID, access: PlacementAccess, cell: ArcSchematicBlock) {
        world.constructionChunkChanged(access.plan.worldId, cell)
        if (!ready() || placementAccess[cityId] !== access || access.repairing ||
            cities[cityId]?.construction?.id != orderId) return
        val point = CivPosition(cell.position.x, cell.position.y, cell.position.z)
        val material = access.materials[point] ?: return
        val previous = constructionFocus.put(cityId, ConstructionFocus(orderId, point, Bukkit.getCurrentTick()))
        if (cell.isAir || cell.isBarrier || material.isAir) return
        if (access.plan.building.type == BuildingType.ROAD) footTraffic.registerSurface(cityId, point)
        // A paired native placement is one gesture and one sound; retain the final accepted focus for the next pulse.
        if (previous?.orderId == orderId && previous.tick == Bukkit.getCurrentTick()) return
        constructionCosmetic(access, orderId, point) { citizens.constructionPlaced(cityId, orderId, point, material) }
        constructionCosmetic(access, orderId, point) { world.constructionFeedback(access.plan.worldId, cell) }
    }

    private fun constructionCosmetic(access: PlacementAccess, orderId: UUID, point: CivPosition, action: () -> Unit) {
        runCatching(action).onFailure { failure ->
            if (!access.feedbackFailed) {
                access.feedbackFailed = true
                log("present construction block $orderId at $point", failure, access.plan.cityId)
            }
        }
    }

    private fun repairCommitted(cityId: UUID, plan: CivPlacementPlan, snapshot: ArcSchematicSnapshot) {
        // Shape repair is cosmetic and happens only after the durable build commit.
        val access = placementAccess[cityId] ?: return
        access.repairing = true
        runCatching {
            CompletableFuture.supplyAsync({ CivsBlueprints.worldCells(snapshot, plan.building.anchor,
                decoration = plan.building.type == BuildingType.DECORATION).filterNot {
                it.blockData.substringBefore('[') == "minecraft:structure_void" &&
                    CivPosition(it.position.x, it.position.y, it.position.z) == plan.building.control
            } }, executor)
                .whenCompleteSync(tasks) { cells, failure ->
                    if (placementAccess[cityId] !== access) return@whenCompleteSync
                    if (failure != null) { releasePlacement(cityId); log("prepare shape repair $cityId", failure, cityId); return@whenCompleteSync }
                    if (!ready()) { releasePlacement(cityId); return@whenCompleteSync }
                    runCatching {
                        val repair = blueprints.repair(plan.worldId, requireNotNull(cells))
                        operations[cityId] = repair
                        repair.completion.whenCompleteSync(tasks) { _, repairFailure ->
                            if (placementAccess[cityId] !== access) return@whenCompleteSync
                            releasePlacement(cityId)
                            physics.enqueue(plan.worldId, access.physicsCells)
                            if (repairFailure != null) log("repair shapes $cityId", repairFailure, cityId)
                            else store.deletePlan(plan.id).thenCompose { store.deleteSchematic(plan.id) }
                                .whenComplete { _, cleanupFailure -> if (cleanupFailure != null) log("clean captured plan ${plan.id}", cleanupFailure, cityId) }
                            updateHealth()
                        }
                    }.onFailure { releasePlacement(cityId); log("repair shapes $cityId", it, cityId) }
                }
        }.onFailure { releasePlacement(cityId); log("prepare shape repair $cityId", it, cityId) }
    }

    private fun releasePlacement(cityId: UUID) {
        placementRuns.remove(cityId)
        operations.remove(cityId)
        terraformOperations.remove(cityId)?.cancel(false)
        placementAccess.remove(cityId)
        placing -= cityId
    }

    private fun placementFailed(cityId: UUID, orderId: UUID, failure: Throwable) {
        releasePlacement(cityId)
        if (closed || cityId in unhealthy || cities[cityId]?.pendingDeletion != null || cities[cityId]?.construction?.id != orderId) return
        val causes = generateSequence(failure) { it.cause }.take(16).toList()
        if (causes.filterIsInstance<ArcSchematicException>().any { it.reason == ArcSchematicFailure.WORLD_UNAVAILABLE } ||
            causes.filterIsInstance<CivsRejection>().any { it.reason in setOf("chunk-unloaded", "chunks-unloaded", "world-unavailable") }) {
            constructionRetryAfter[cityId] = now() + 5_000
            CivsDiagnostics.event("construction_waiting_world", "city" to cityId, "order" to orderId,
                "batch" to cities[cityId]?.construction?.workDone, "reason" to "chunk-unloaded")
            return
        }
        log("place construction $orderId", failure, cityId)
        val reason = causes.filterIsInstance<CivsRejection>().firstOrNull()?.reason
            ?: if (causes.filterIsInstance<ArcSchematicException>().any { it.reason == ArcSchematicFailure.PROTECTED_AREA }) "protected-area" else "placement-failed"
        CivsDiagnostics.record(Level.WARNING, cityId, CivsDiagnostics.line("construction_paused", "city" to cityId, "order" to orderId,
            "batch" to cities[cityId]?.construction?.workDone, "reason" to reason,
            *causes.filterIsInstance<CivsRejection>().firstOrNull()?.values.orEmpty().map { it.key to it.value }.toTypedArray()), plugin.logger)
        runCatching {
            afterCityAvailable(cityId) { mutate(cityId) { CivsRules.pauseConstruction(it, it.owner, orderId, reason) } }
        }
            .onFailure { log("pause construction $orderId", it, cityId) }
    }

    override fun close() {
        placementAccess.clear()
        constructionFocus.clear()
        constructionSupplyNeeds.clear()
        constructionSites.clear()
        placementRuns.clear()
        placing.clear()
        operations.values.toList().forEach { it.cancel() }
        operations.clear()
        terraformOperations.values.toList().forEach { it.cancel(false) }
        terraformOperations.clear()
    }

    private fun rejectIf(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
    private fun now() = clock()
    private fun log(context: String, error: Throwable, cityId: UUID) =
        CivsDiagnostics.record(Level.WARNING, cityId, "ArcCivs could not $context", plugin.logger, error)
}
