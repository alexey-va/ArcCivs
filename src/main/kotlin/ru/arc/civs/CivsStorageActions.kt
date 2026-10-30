package ru.arc.civs

import org.bukkit.FluidCollisionMode
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.CompletableFuture

internal class CivsStorageActions(
    private val authority: CivsService,
    private val records: CivsCityRecords,
    private val residentSuppliesOwner: () -> CivsResidentSupplies,
    private val cargoRequestsOwner: () -> CivsCargoRequests,
    private val researchOwner: () -> CivsCityResearch,
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val store: CivsStore,
    private val executor: Executor,
    private val blueprints: CivsBlueprints,
    private val world: CivsWorld,
    private val roadMaintenance: CivsRoadMaintenance,
    private val storage: CivsStorage,
    private val worksites: CivsWorksites,
    private val controlInstallations: CivsControlInstallations,
    private val supply: CivsSupplyDispatcher,
    private val construction: CivsConstructionRuntime,
    private val inventoryJournal: CivsInventoryJournal,
) {
    private val residentSupplies get() = residentSuppliesOwner()
    private val cargoRequests get() = cargoRequestsOwner()
    private val cityResearch get() = researchOwner()
    private var nextStorageTransferAt = 0L
    private var storageCursor = 0
    private var nextAutoMigrationAt = 0L
    private var autoMigrationCursor = 0

    /** Explicit console maintenance only; never restores a chest again after a player removes it. */
    internal fun repairBuildingStorage(cityId: UUID, buildingId: UUID): CompletableFuture<Map<String, Int>> =
        authority.action("building_storage_repair", null, "city" to cityId, "building" to buildingId) {
            authority.afterCityAvailable(cityId) {
                val city = records.cities[cityId] ?: throw CivsRejection("no-city")
                val building = city.building(buildingId)?.takeIf { it.phase == BuildingPhase.ACTIVE }
                    ?: throw CivsRejection("invalid-building")
                val result = authority.tracked<Map<String, Int>>()
                blueprints.buildingStorageRepair(building).whenCompleteSync(tasks) { cells, failure ->
                    if (failure != null) { result.completeExceptionally(failure); return@whenCompleteSync }
                    runCatching {
                        authority.afterCityAvailable<Map<String, Int>>(cityId) {
                            rejectIf(records.cities[cityId]?.building(buildingId) != building, "stale-building")
                            authority.reserveCity(cityId)
                            val repair = runCatching {
                                world.repairStorageRacks(requireNotNull(records.cities[cityId]), building, requireNotNull(cells))
                            }.onFailure { authority.releaseCity(cityId) }.getOrThrow()
                            repair.whenCompleteSync(tasks) { counts, repairFailure ->
                                authority.releaseCity(cityId)
                                val refreshFailure = runCatching {
                                    records.cities[cityId]?.let { storage.refreshBuilding(it, building) }
                                    supply.request(cityId)
                                }.exceptionOrNull()
                                if (repairFailure != null || refreshFailure != null)
                                    result.completeExceptionally(repairFailure ?: refreshFailure!!)
                                else {
                                    CivsDiagnostics.event("building_storage_repaired", "city" to cityId, "building" to buildingId,
                                        "counts" to counts)
                                    result.complete(counts)
                                }
                            }.toCompletableFuture()
                        }.exceptionally { result.completeExceptionally(it); null }
                    }.onFailure(result::completeExceptionally)
                }
                result
            }
        }

    fun buildingMaterials(player: Player, buildingId: UUID): CompletableFuture<Map<String, Long>> = authority.action("building_materials", player, "building" to buildingId) {
        val city = authority.managed(player)
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        if (city.construction?.buildingId == buildingId) CompletableFuture.completedFuture(CivsMaterials.unpaid(city.construction.materials.orEmpty()))
        else if (building.phase != BuildingPhase.SITE && buildingId !in city.constructionQueue.orEmpty()) CompletableFuture.completedFuture(emptyMap())
        else store.loadPlan(queuedDesignId(building)).thenApplyAsync({ plan ->
            requireQueuedDesign(requireNotNull(plan), city, building)
            CivsMaterials.required(plan)
        }, executor)
    }

    fun supplyConstruction(player: Player, buildingId: UUID): CompletableFuture<CivsSupplyResult> =
        authority.cityAction("construction_supply", player, "building" to buildingId) { supplyConstruction(player, buildingId, false) }

    fun supplyConstruction(player: Player, buildingId: UUID, hutId: UUID): CompletableFuture<CivsSupplyResult> =
        authority.cityAction("construction_supply", player, "building" to buildingId, "hut" to hutId) { supplyConstruction(player, buildingId, false, hutId) }

    fun grantConstructionMaterials(player: Player, buildingId: UUID): CompletableFuture<CivsSupplyResult> =
        authority.cityAction("construction_grant", player, "building" to buildingId) { supplyConstruction(player, buildingId, true) }

    fun grantConstructionMaterials(player: Player, buildingId: UUID, hutId: UUID): CompletableFuture<CivsSupplyResult> =
        authority.cityAction("construction_grant", player, "building" to buildingId, "hut" to hutId) { supplyConstruction(player, buildingId, true, hutId) }

    private fun mayGrantMaterials(player: Player): Boolean = player.hasPermission("arccivs.admin") || player.gameMode == org.bukkit.GameMode.CREATIVE

    private fun constructionDeficit(required: Map<String, Long>, available: Map<String, Long>): Map<String, Long> =
        required.mapValues { (material, count) -> (count - (available[material] ?: 0L)).coerceAtLeast(0) }.filterValues { it > 0 }

    private fun constructionStore(city: Settlement, buildingId: UUID, hutId: UUID?): CivBuilding =
        CivsRules.constructionStores(city, buildingId).let { stores ->
            if (hutId != null) stores.firstOrNull { it.id == hutId }
            else stores.firstOrNull { store -> city.workers.any { it.assignment == store.id } } ?: stores.firstOrNull()
        } ?: throw CivsRejection("builder-required")

    fun supplyConstruction(player: Player, buildingId: UUID, grant: Boolean, hutId: UUID? = null): CompletableFuture<CivsSupplyResult> {
        rejectIf(grant && !mayGrantMaterials(player), "not-authorized")
        val city = authority.managed(player)
        rejectIf(city.id in records.busy, "city-busy")
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        rejectIf(city.inventory?.mode != CivStorageMode.PHYSICAL, "invalid-storage-mode")
        val order = city.construction?.takeIf { it.buildingId == buildingId }
        rejectIf(order == null && building.phase != BuildingPhase.SITE && buildingId !in city.constructionQueue.orEmpty(), "invalid-building")
        // Legacy orders paid their complete bill when reserved; their estimate must never mint another payment.
        if (order != null && order.payment == null) return CompletableFuture.completedFuture(CivsSupplyResult(0, 0, false))
        val destination = constructionStore(city, buildingId, hutId)
        val grantMode = player.gameMode
        authority.near(player, city.worldId, if (hutId == null) building.entrance else destination.entrance)
        val required = if (order != null) CompletableFuture.completedFuture(CivsMaterials.unpaid(order.materials.orEmpty()))
        else store.loadPlan(queuedDesignId(building)).thenApplyAsync({ stored ->
            val plan = requireNotNull(stored) { "Missing site design $buildingId" }
            requireQueuedDesign(plan, city, building)
            CivsMaterials.required(plan)
        }, executor)
        authority.reserveCity(city.id)
        val result = authority.tracked<CivsSupplyResult>()
        required.whenCompleteSync(tasks) { materials, failure ->
            authority.releaseCity(city.id)
            if (failure != null) { result.completeExceptionally(failure); return@whenCompleteSync }
            runCatching {
                rejectIf(!player.isOnline || grant && !mayGrantMaterials(player), "not-authorized")
                val current = authority.managed(player)
                rejectIf(current.id != city.id || current.building(buildingId) != building || current.construction != city.construction ||
                    constructionStore(current, buildingId, destination.id) != destination, "invalid-building")
                authority.near(player, current.worldId, if (hutId == null) building.entrance else destination.entrance)
                var supplied: CivStorageSupplyPlan? = null
                authority.inventoryMutation(current.id, { lockedCity ->
                    val missing = constructionDeficit(requireNotNull(materials), storage.constructionAvailable(lockedCity, buildingId))
                    val local = storage.buildingAvailable(lockedCity, destination.id)
                    val desired = missing.mapValues { (material, count) -> count + (local[material] ?: 0L) }
                    storage.prepareConstructionSupply(lockedCity, destination.id, desired, if (grant) null else player)
                        .also { supplied = it }.plan
                }, sourcePlayer = if (grant) null else player) { before, inventory ->
                    if (requireNotNull(supplied).plan.changes.isEmpty()) before
                    else before.copy(inventory = inventory, revision = Math.addExact(before.revision, 1))
                }.whenCompleteSync(tasks) { _, applyFailure ->
                    if (applyFailure != null) result.completeExceptionally(applyFailure)
                    else {
                        val outcome = requireNotNull(supplied).result
                        val moved = requireNotNull(supplied).plan.changes.groupBy { it.after?.material ?: it.before?.material }.mapValues { (_, changes) ->
                            changes.sumOf { (it.after?.amount ?: 0).toLong() - (it.before?.amount ?: 0) }
                        }.filterValues { it > 0 }
                        CivsDiagnostics.event("construction_supplied", "city" to city.id, "building" to buildingId, "hut" to destination.id,
                            "player" to player.uniqueId, "grant" to grant, "game_mode" to grantMode, "materials" to moved, "moved" to outcome.moved,
                            "remaining" to outcome.remaining, "storage_full" to outcome.storageFull)
                        result.complete(outcome)
                    }
                }
            }.onFailure(result::completeExceptionally)
        }
        return result
    }

    fun supplyBuilderMaterials(player: Player, workerId: UUID): CompletableFuture<CivsSupplyResult> =
        supplyBuilderMaterials(player, workerId, false)

    fun grantBuilderMaterials(player: Player, workerId: UUID): CompletableFuture<CivsSupplyResult> =
        supplyBuilderMaterials(player, workerId, true)

    fun supplyBuilderMaterials(player: Player, workerId: UUID, grant: Boolean): CompletableFuture<CivsSupplyResult> =
        authority.cityAction("builder_materials_supply", player, "worker" to workerId, "grant" to grant) { city ->
            rejectIf(grant && !mayGrantMaterials(player), "not-authorized")
            rejectIf(city.inventory?.mode != CivStorageMode.PHYSICAL, "invalid-storage-mode")
            val order = city.construction ?: throw CivsRejection("no-construction")
            if (order.payment == null) return@cityAction CompletableFuture.completedFuture(CivsSupplyResult(0, 0, false))
            val worker = CivsRules.constructionWorkers(city, order.buildingId).firstOrNull { it.id == workerId }
                ?: throw CivsRejection("builder-required")
            val points = listOfNotNull(worker.assignment?.let(city::building)?.entrance, order.plannedBuilding?.entrance)
            rejectIf(player.world.uid != city.worldId || points.none { point ->
                player.location.toVector().distanceSquared(org.bukkit.util.Vector(point.x.toDouble(), point.y.toDouble(), point.z.toDouble())) <= 96.0 * 96.0
            }, "too-far")
            var updated: CivWorker? = null
            val grantMode = player.gameMode
            var moved: Map<String, Long> = emptyMap()
            var outcome = CivsSupplyResult(0, 0, false)
            authority.inventoryMutation(city.id, { before ->
                rejectIf(grant && !mayGrantMaterials(player), "not-authorized")
                rejectIf(before.construction?.id != order.id || before.construction.workDone != order.workDone, "stale-construction")
                val current = CivsRules.constructionWorkers(before, order.buildingId).firstOrNull { it.id == workerId }
                    ?: throw CivsRejection("builder-required")
                val missing = constructionDeficit(CivsMaterials.unpaid(requireNotNull(before.construction).materials.orEmpty()),
                    storage.constructionAvailable(before, order.buildingId))
                val offered = if (grant) missing else missing.mapValues { (material, count) -> minOf(count,
                    player.inventory.storageContents.filterNotNull().filter { it.type.name == material && it.isSimilar(org.bukkit.inventory.ItemStack(it.type)) }
                        .sumOf { it.amount.toLong() }) }.filterValues { it > 0 }
                rejectIf(missing.isNotEmpty() && offered.isEmpty(), "construction-supplies-empty")
                val prepared = if (grant) {
                    val (issued, amounts) = CivsEquipment.grantSupplies(current, missing)
                    updated = issued
                    moved = amounts
                    CivStoragePlan(requireNotNull(before.inventory), emptyList())
                } else storage.prepareResidentTransfer(before, workerId, player, null, null,
                    org.bukkit.event.inventory.ClickType.LEFT, requestedSupplies = missing).also { transfer ->
                    updated = transfer.worker
                    moved = missing.mapValues { (material, _) ->
                        (CivsEquipment.plainCount(transfer.worker, material) - CivsEquipment.plainCount(current, material)).toLong()
                    }.filterValues { it > 0 }
                }.plan
                val count = moved.values.sum()
                outcome = CivsSupplyResult(count, missing.values.sum() - count, offered.values.sum() > count)
                prepared
            }, sourcePlayer = if (grant) null else player, keepViewersOpen = true) { before, inventory ->
                if (outcome.moved == 0L) before else before.copy(inventory = inventory,
                    workers = before.workers.map { if (it.id == workerId) requireNotNull(updated) else it }, revision = before.revision + 1)
            }.thenApply { saved ->
                CivsDiagnostics.event("builder_materials_supplied", "city" to saved.id, "building" to order.buildingId,
                    "worker" to workerId, "player" to player.uniqueId, "grant" to grant, "game_mode" to grantMode,
                    "materials" to moved, "moved" to outcome.moved, "remaining" to outcome.remaining, "storage_full" to outcome.storageFull)
                outcome
            }
        }

    fun availableMaterials(player: Player): Map<String, Long> = storage.available(authority.managed(player))
    fun constructionAvailable(player: Player, buildingId: UUID): Map<String, Long> =
        storage.constructionAvailable(authority.managed(player).also { rejectIf(it.building(buildingId) == null, "building-not-found") }, buildingId)
    fun localMaterials(player: Player, buildingId: UUID): Map<String, Long> =
        authority.managed(player).let { residentSupplies.localStocks(it)(buildingId) }
    fun supplyRequests(player: Player): List<CivSupplyRequest> =
        authority.managed(player).let { city -> residentSupplies.localStocks(city).let { CivsLogistics.requests(city, it) + residentSupplies.workSupplyRequests(city, it) } }

    fun carpentry(player: Player, buildingId: UUID): CivCarpentryView? {
        val city = authority.managed(player)
        val building = city.building(buildingId)?.takeIf { it.type in CivsCarpentry.workshops } ?: return null
        val snapshot = supply.craftObservation(city.id)
        val orders = city.workers.filter { it.assignment == buildingId }.mapNotNull { worker ->
            residentSupplies.craftPlan(city, worker)?.let { job ->
                val carriedMissing = CivsEquipment.storageInputs(worker, job.inputs)
                val stock = snapshot?.stores?.get(buildingId)?.stock.orEmpty()
                CivCarpentryOrderView(worker.id, worker.name, job.material, job.amount, job.inputs,
                    carriedMissing.mapValues { (material, count) -> (count - (stock[material] ?: 0L)).coerceAtLeast(0) }.filterValues { it > 0 },
                    job.demandMaterial, job.targetBuildingId, if (carriedMissing.isEmpty()) "crafting" else "waiting-materials")
            }
        }
        return CivCarpentryView(building.level, !CivsRules.productionAvailable(city, building),
            snapshot == null || !residentSupplies.craftObservationCurrent(city), orders, CivsCarpentry.views(building.type))
    }

    fun setStorageMode(player: Player, mode: CivStorageMode): CompletableFuture<Unit> = authority.cityAction("storage_mode", player, "mode" to mode) { city ->
        if (mode == CivStorageMode.PHYSICAL) materialize(city.id).thenApply { Unit }
        else authority.mutate(city.id) { current ->
            val inventory = current.inventory ?: throw CivsRejection("materials-migration-pending")
            if (inventory.mode == mode) current else CivsRules.validate(current.copy(inventory = inventory.copy(mode = mode), revision = current.revision + 1))
        }.thenApply { Unit }
    }

    fun materializeStorage(player: Player): CompletableFuture<Unit> = authority.cityAction("storage_materialize", player) { city ->
        rejectIf(city.inventory?.mode != CivStorageMode.PHYSICAL, "invalid-storage-mode")
        materialize(city.id).thenApply { Unit }
    }

    private fun materialize(cityId: UUID, background: Boolean = false): CompletableFuture<Settlement> {
        val before = records.cities[cityId] ?: throw CivsRejection("no-city")
        return authority.inventoryMutation(cityId, { storage.prepareMaterialization(it) }, keepViewersOpen = background) { current, inventory ->
            if (inventory == current.inventory) current else current.copy(inventory = inventory, revision = current.revision + 1)
        }.whenComplete { saved, failure ->
            if (failure == null && saved != before) CivsDiagnostics.event("storage_materialized", "city" to cityId,
                "revision" to saved.revision, "moved_items" to pendingMaterials(before) - pendingMaterials(saved),
                "pending_items" to pendingMaterials(saved))
        }
    }

    private fun pendingMaterials(city: Settlement): Long = city.inventory?.let { inventory ->
        inventory.materials.values.sum() + inventory.buildingMaterials.orEmpty().values.sumOf { it.values.sum() }
    } ?: 0L

    private fun inventoryBuilding(player: Player, city: Settlement, buildingId: UUID): CivBuilding {
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        val control = building.control ?: throw CivsRejection("control-missing")
        rejectIf(player.world.uid != city.worldId || player.location.toVector().distanceSquared(
            org.bukkit.util.Vector(control.x + .5, control.y + .5, control.z + .5)) > 64.0, "too-far")
        return building
    }

    fun inspectBuilding(player: Player, cityId: UUID, buildingId: UUID): Settlement {
        authority.requireReady(player)
        val city = records.cities[cityId] ?: throw CivsRejection("no-city")
        rejectIf(city.autonomy == null && !city.canManage(player.uniqueId), "not-authorized")
        rejectIf(city.id in records.unhealthy, "storage-unavailable")
        rejectIf(city.pendingDeletion != null, "deletion-pending")
        inventoryBuilding(player, city, buildingId)
        return city
    }

    fun openBuildingInventory(player: Player, cityId: UUID, buildingId: UUID): CompletableFuture<Unit> =
        authority.action("inventory_inspect", player, "city" to cityId, "building" to buildingId) {
            val city = inspectBuilding(player, cityId, buildingId)
            if (city.autonomy == null) openBuildingInventory(player, buildingId)
            else {
                storage.openControl(player, city, requireNotNull(city.building(buildingId)))
                CompletableFuture.completedFuture(Unit)
            }
        }

    fun inspectionMaterials(player: Player, cityId: UUID, buildingId: UUID): Map<String, Long> =
        storage.buildingAvailable(inspectBuilding(player, cityId, buildingId), buildingId)

    fun openBuildingInventory(player: Player, buildingId: UUID): CompletableFuture<Unit> = authority.cityAction("inventory_open", player, "building" to buildingId) { city ->
        inventoryBuilding(player, city, buildingId)
        val prepared = if (city.inventory?.mode == CivStorageMode.PHYSICAL && pendingMaterials(city) > 0)
            materialize(city.id) else CompletableFuture.completedFuture(city)
        prepared.thenApply {
            rejectIf(!player.isOnline, "player-offline")
            val current = authority.managed(player)
            rejectIf(current.id != city.id, "no-city")
            storage.openControl(player, current, inventoryBuilding(player, current, buildingId))
        }
    }

    fun buildingControl(player: Player, buildingId: UUID): CompletableFuture<CivPosition> = authority.action("control_locate", player, "building" to buildingId) {
        val city = authority.managed(player)
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        val result = authority.tracked<CivPosition>()
        blueprints.controlPosition(building).whenCompleteSync(tasks) { control, failure ->
                if (failure != null) result.completeExceptionally(failure)
                else runCatching {
                    rejectIf(authority.managed(player).let { it.id != city.id || it.building(buildingId) != building }, "control-location-changed")
                    val point = requireNotNull(control)
                    CivsDiagnostics.event("control_located", "city" to city.id, "building" to buildingId,
                        "previous" to building.control, "control" to point)
                    point
                }.onSuccess(result::complete).onFailure(result::completeExceptionally)
            }
        result
    }

    /** Install the authored marker once; the previous native container and its contents stay untouched. */
    fun relocateControl(player: Player, buildingId: UUID, expectedTarget: CivPosition): CompletableFuture<Unit> =
        authority.cityAction("control_relocate", player, "building" to buildingId, "expected_control" to expectedTarget) { before ->
            val building = before.building(buildingId) ?: throw CivsRejection("building-not-found")
            rejectIf(before.id in records.busy || construction.isPlacing(before.id) || inventoryJournal.pending(before.id), "city-busy")
            rejectIf(before.pendingControls.orEmpty().any { it.buildingId == buildingId && it.retire != null }, "city-busy")
            rejectIf(before.construction?.buildingId == buildingId, "construction-busy")
            rejectIf(building.phase != BuildingPhase.ACTIVE, "control-relocation-site")
            val result = authority.tracked<Unit>()
            buildingControl(player, buildingId).whenCompleteSync(tasks) { target, failure ->
                if (failure != null) result.completeExceptionally(failure)
                else runCatching { authority.afterCityAvailable(before.id) {
                    rejectIf(!player.isOnline, "player-offline")
                    val point = requireNotNull(target)
                    rejectIf(point != expectedTarget, "control-location-changed")
                    val current = authority.managed(player)
                    rejectIf(current.id != before.id || current.building(buildingId) != building, "control-location-changed")
                    rejectIf(current.construction?.buildingId == buildingId || construction.isPlacing(current.id), "construction-busy")
                    rejectIf(inventoryJournal.pending(current.id), "inventory-recovery")
                    authority.near(player, current.worldId, point)
                    relocateControl(current, building, point).thenApply { Unit }
                }.whenCompleteSync(tasks) { _, admissionFailure ->
                    if (admissionFailure == null) result.complete(Unit) else result.completeExceptionally(admissionFailure)
                }
                }.onFailure(result::completeExceptionally)
            }
            result
        }

    fun relocateControl(current: Settlement, building: CivBuilding, point: CivPosition): CompletableFuture<Settlement> {
        rejectIf(current.pendingControls.orEmpty().any { it.buildingId == building.id && it.retire != null }, "city-busy")
        if (point == building.control) controlInstallations.requirePresent(current.worldId, building)
        val placement = controlInstallations.prepare(current.id, current.worldId, current.owner, building.copy(control = point))
        val nativeWorld = requireNotNull(plugin.server.getWorld(current.worldId))
        rejectIf(!world.canModify(current.owner, nativeWorld.getBlockAt(point.x, point.y, point.z), Material.BARREL), "lands-denied")
        rejectIf(civsProtectedBuildings(current).any { it.id != building.id &&
            (it.control == point || it.bounds.contains(point.x, point.y, point.z)) }, "building-overlap")
        val old = building.control
        val retained = old?.takeIf { it != point }?.let {
            rejectIf(!nativeWorld.isChunkLoaded(it.x shr 4, it.z shr 4), "chunk-unloaded")
            val block = nativeWorld.getBlockAt(it.x, it.y, it.z)
            rejectIf(!world.canModify(current.owner, block), "lands-denied")
            it.takeIf { block.type == Material.BARREL }
        }
        return authority.mutate(current.id) { city ->
            rejectIf(city.building(building.id) != building || city.construction?.buildingId == building.id, "control-location-changed")
            if (point == old) city else {
                val inventory = city.inventory
                rejectIf(inventory == null && city.autonomy == null, "materials-migration-pending")
                val containers = (inventory?.containers.orEmpty() - point).let { if (retained == null || retained in it) it else it + retained }
                rejectIf(containers.size > CivInventory.MAX_CONTAINERS, "container-limit")
                CivsRules.validate(city.copy(buildings = city.buildings.map {
                    if (it.id == building.id) it.copy(control = point) else it
                }, inventory = inventory?.copy(containers = containers), revision = city.revision + 1,
                    pendingControls = city.pendingControls.orEmpty().filterNot { it.buildingId == building.id } + placement))
            }
        }.thenCompose { _ ->
            controlInstallations.forgetFailure(building.id)
            CivsDiagnostics.event("control_relocated", "city" to current.id, "building" to building.id,
                "previous" to old, "control" to point, "retained_container" to retained)
            authority.afterCityAvailable(current.id) { controlInstallations.install(current.id, building.id) }
        }
    }

    fun depositMaterials(player: Player, buildingId: UUID): CompletableFuture<Unit> = authority.cityAction("inventory_deposit", player, "building" to buildingId) { city ->
        val building = inventoryBuilding(player, city, buildingId)
        authority.inventoryMutation(city.id, { storage.depositFromControl(it, building) }) { before, inventory ->
            before.copy(inventory = inventory, revision = before.revision + 1)
        }.thenApply { Unit }
    }

    fun withdrawMaterial(player: Player, buildingId: UUID, material: String): CompletableFuture<Unit> = authority.cityAction("inventory_withdraw", player, "building" to buildingId, "material" to material) { city ->
        val building = inventoryBuilding(player, city, buildingId)
        authority.inventoryMutation(city.id, { storage.withdrawToControl(it, building, material) }) { before, inventory ->
            before.copy(inventory = inventory, revision = before.revision + 1)
        }.thenApply { Unit }
    }

    fun registerContainer(player: Player): CompletableFuture<Unit> = authority.action("container_register", player) {
        val cityId = authority.managed(player).id
        val block = player.rayTraceBlocks(6.0, FluidCollisionMode.NEVER)?.hitBlock ?: throw CivsRejection("look-at-container")
        val point = CivPosition(block.x, block.y, block.z)
        authority.afterCityAvailable(cityId) {
            val city = authority.managed(player)
            rejectIf(!player.isOnline || city.id != cityId, "not-authorized")
            rejectIf(player.world.uid != city.worldId || block.world.uid != city.worldId || !city.contains(point.x, point.z), "outside-city")
            rejectIf(player.eyeLocation.distanceSquared(block.location) > 64, "too-far")
            rejectIf(block.type !in setOf(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL), "look-at-container")
            rejectIf(!world.canModify(city.owner, block), "lands-denied")
            rejectIf(civsProtectedBuildings(city).any { it.phase == BuildingPhase.CONSTRUCTING && it.bounds.contains(point.x, point.y, point.z) } ||
                city.construction?.plannedBuilding?.bounds?.contains(point.x, point.y, point.z) == true, "construction-busy")
            authority.mutate(city.id) { before ->
                val inventory = before.inventory ?: throw CivsRejection("materials-migration-pending")
                if (point in inventory.containers || before.buildings.any { it.control == point }) before
                else {
                    rejectIf(inventory.containers.size >= CivInventory.MAX_CONTAINERS, "container-limit")
                    CivsRules.validate(before.copy(inventory = inventory.copy(containers = inventory.containers + point), revision = before.revision + 1))
                }
            }.thenApply { Unit }
        }
    }

    fun unregisterContainer(player: Player, position: CivPosition): CompletableFuture<Unit> = authority.cityAction("container_unregister", player, "position" to position) { managedCity ->
        authority.mutate(managedCity.id) { city ->
            val inventory = city.inventory ?: throw CivsRejection("materials-migration-pending")
            if (position !in inventory.containers) city else CivsRules.validate(city.copy(
                inventory = inventory.copy(containers = inventory.containers - position), revision = city.revision + 1))
        }.thenApply { Unit }
    }

    fun setProduction(player: Player, buildingId: UUID, material: String): CompletableFuture<Unit> = authority.cityAction("production_select", player, "building" to buildingId, "material" to material) { managedCity ->
        authority.mutate(managedCity.id) { city ->
            CivsRules.setProduction(city, player.uniqueId, buildingId, material, authority.now())
        }.thenApply { Unit }
    }

    fun setForesterSpecies(player: Player, cityId: UUID, buildingId: UUID, material: String): CompletableFuture<Unit> =
        authority.action("forester_species_select", player, "city" to cityId, "building" to buildingId, "material" to material) {
            cityResearch.researchCity(player, cityId, true)
            authority.afterCityAvailable(cityId) {
                rejectIf(!player.isOnline, "player-offline")
                cityResearch.researchCity(player, cityId, true)
                authority.mutate(cityId) { city ->
                    rejectIf(city.building(buildingId)?.type != BuildingType.FORESTER, "invalid-production")
                    CivsRules.setProduction(city, if (city.autonomy != null) city.owner else player.uniqueId, buildingId, material, authority.now())
                }.thenApply { Unit }
            }
        }

    fun pulseAutoStorage() {
        if (authority.now() < nextAutoMigrationAt) return
        val candidates = records.cities.values.filter { CivsAutoStorageMigration.canMigrate(it) && it.id !in records.busy &&
            it.id !in records.unhealthy && it.pendingDeletion == null && !inventoryJournal.pending(it.id) }
        if (candidates.isEmpty()) return
        val city = candidates[Math.floorMod(autoMigrationCursor++, candidates.size)]
        nextAutoMigrationAt = authority.now() + 1_000L
        runCatching { authority.mutate(city.id, transform = CivsAutoStorageMigration::migrate) }.onSuccess { accepted ->
            accepted.whenCompleteSync(tasks) { saved, failure ->
                if (failure == null && saved != city) {
                    city.workers.forEach { worksites.invalidate(it.id); roadMaintenance.invalidate(it.id) }
                    supply.remove(city.id)
                    cargoRequests.forgetObservation(city.id)
                    CivsDiagnostics.event("autonomous_storage_migrated", "city" to city.id,
                        "pending_items" to pendingMaterials(requireNotNull(saved)), "revision" to saved.revision)
                } else if (failure != null) authority.log("migrate autonomous storage ${city.id}", failure, city.id)
            }
        }.onFailure { authority.log("migrate autonomous storage ${city.id}", it, city.id) }
    }

    fun pulseStorage() {
        if (authority.now() < nextStorageTransferAt) return
        val candidates = records.cities.values.filter { city ->
            city.id !in records.busy && city.id !in records.unhealthy && city.pendingDeletion == null && city.inventory?.mode == CivStorageMode.PHYSICAL && pendingMaterials(city) > 0 &&
                authority.settlementActive(city)
        }
        if (candidates.isEmpty()) return
        val city = candidates[Math.floorMod(storageCursor++, candidates.size)]
        nextStorageTransferAt = authority.now() + 5_000L
        runCatching { materialize(city.id, background = true) }
            .onSuccess { future -> future.whenComplete { _, failure ->
                if (failure != null)
                    authority.log("materialize stored items ${city.id}", failure, city.id)
            } }
            .onFailure { authority.log("materialize stored items ${city.id}", it, city.id) }
    }

    private fun rejectIf(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
}
