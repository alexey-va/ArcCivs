package ru.arc.civs

import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

internal class CivsBuildingRequests(
    private val authority: CivsService,
    private val records: CivsCityRecords,
    private val placementOwner: () -> CivsPlacementSession,
    private val storageActionsOwner: () -> CivsStorageActions,
    private val membershipOwner: () -> CivsCityMembership,
    private val tasks: LifecycleTaskScope,
    private val settings: CivsSettings,
    private val store: CivsStore,
    private val blueprints: CivsBlueprints,
    private val executor: Executor,
    private val world: CivsWorld,
    private val autonomousPlacement: CivsAutonomousPlacement,
    private val controlInstallations: CivsControlInstallations,
    private val construction: CivsConstructionRuntime,
) {
    private val placement get() = placementOwner()
    private val storageActions get() = storageActionsOwner()
    private val membership get() = membershipOwner()
    private val pendingConfirmations = mutableMapOf<UUID, CompletableFuture<Unit>>()
    private val pendingBuildingOrders = mutableMapOf<Pair<UUID, UUID>, CompletableFuture<Unit>>()
    private val pendingFounders = mutableMapOf<UUID, FoundingReservation>()
    private data class FoundingReservation(val cityId: UUID, val worldId: UUID, val center: CivPosition)

    fun foundingPending(playerId: UUID): Boolean = playerId in pendingFounders
    val founderCount get() = pendingFounders.size
    val confirmationCount get() = pendingConfirmations.size
    val orderCount get() = pendingBuildingOrders.size
    fun clearAccepted() { pendingConfirmations.clear(); pendingBuildingOrders.clear() }

    fun requireFounder(player: Player, autonomous: Boolean = false) {
        rejectIf(autonomous && !player.hasPermission("arccivs.admin"), "not-authorized")
        rejectIf(!autonomous && (authority.city(player) != null || membership.remoteCity(player) != null) || player.uniqueId in pendingFounders, "already-member")
    }

    fun upgradeBuilding(player: Player, buildingId: UUID): CompletableFuture<Unit> = authority.action("upgrade_building", player, "building" to buildingId) {
        val city = authority.managed(player)
        val key = city.id to buildingId
        pendingBuildingOrders[key]?.takeUnless { it.isDone }?.let { return@action it }
        val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
        rejectIf(city.construction?.buildingId == buildingId, "construction-busy")
        rejectIf(building.phase != BuildingPhase.ACTIVE, "invalid-building")
        rejectIf(building.level >= authority.maximumBuildingLevel(building), "invalid-level")
        CivsResearch.required(building.type, building.level + 1)?.let { rejectIf(!CivsResearch.has(city, it), "research-required") }
        rejectIf(building.type != BuildingType.TOWN_HALL && building.level >= city.level(), "town-hall-level-required")
        authority.near(player, city.worldId, building.entrance)
        if (buildingId in city.constructionQueue.orEmpty()) return@action CompletableFuture.completedFuture(Unit)
        rejectIf(pendingConfirmations[player.uniqueId]?.isDone == false, "city-busy")
        placement.cancelPreview(player.uniqueId, "upgrade-started")
        // Upgrades reuse immutable preparation and the durable order transaction, without a placement preview.
        val result = placement.prepare(player, city.id, city.name, CivsBlueprints.styleOf(building) ?: city.style, building.type, building.level + 1, building.id,
            building.anchor, true, building.rotation, fixedEntrance = building.entrance)
            .thenCompose { confirmPreview(player) }
        pendingBuildingOrders[key] = result
        result.whenCompleteSync(tasks) { _, failure ->
            if (pendingBuildingOrders[key] === result) {
                pendingBuildingOrders.remove(key)
                if (failure != null && placement.draft(player.uniqueId)?.let { it.upgrading && it.blueprint.building.id == buildingId } == true)
                    placement.cancelPreview(player.uniqueId, "upgrade-failed")
            }
        }
        result
    }

    fun confirmPreview(player: Player): CompletableFuture<Unit> = authority.action("confirm_preview", player) {
        val preview = placement.currentPreview(player)
        pendingConfirmations[player.uniqueId]?.takeUnless { it.isDone }?.let { return@action it }
        val result = if (preview.cityId == null) confirmPreview(player, preview)
        else authority.afterCityAvailable(preview.cityId) { confirmPreview(player, preview) }
        pendingConfirmations[player.uniqueId] = result
        result.whenCompleteSync(tasks) { _, _ ->
            if (pendingConfirmations[player.uniqueId] === result) pendingConfirmations.remove(player.uniqueId)
        }
        result
    }

    fun confirmPreview(player: Player, preview: CivsPlacementPreview): CompletableFuture<Unit> = confirmPreview(player, preview, false)

    private fun confirmPreview(player: Player, preview: CivsPlacementPreview, storageChecked: Boolean): CompletableFuture<Unit> {
        rejectIf(placement.currentPreview(player) !== preview || !player.isOnline, "preview-expired")
        val blueprint = preview.blueprint
        rejectIf(blueprint.building.type.isInfrastructure, "invalid-building")
        val cityCenter = blueprint.building.control ?: blueprint.building.entrance
        authority.near(player, blueprint.worldId, blueprint.building.entrance)
        val cityId = preview.cityId ?: UUID.randomUUID()
        val previousCity = preview.cityId?.let { placement.managedPlacement(player, it, blueprint.building.type) }
        val queuedUpgrade = preview.upgrading && previousCity?.let {
            it.construction != null || it.constructionQueue.orEmpty().isNotEmpty()
        } == true
        if (preview.upgrading) {
            val existing = previousCity?.building(blueprint.building.id) ?: throw CivsRejection("building-not-found")
            rejectIf(existing.phase != BuildingPhase.ACTIVE || existing.level + 1 != blueprint.building.level, "invalid-building")
            rejectIf(previousCity.construction?.buildingId == existing.id, "construction-busy")
            if (existing.id in previousCity.constructionQueue.orEmpty()) {
                placement.cancelPreview(player.uniqueId)
                return CompletableFuture.completedFuture(Unit)
            }
        }
        if (preview.upgrading && !queuedUpgrade && previousCity?.building(blueprint.building.id)?.control != blueprint.building.control) {
            return storageActions.relocateControl(player, blueprint.building.id, requireNotNull(blueprint.building.control))
                .thenCompose { authority.afterCityAvailable(cityId) { confirmPreview(player, preview) } }
        }
        if (preview.upgrading && !queuedUpgrade && !storageChecked && previousCity != null && !blueprint.building.type.isInfrastructure)
            return withUpgradeStorage(previousCity, blueprint.building, blueprint.cells, freshAdmission = {
                rejectIf(!player.isOnline || placement.currentPreview(player) !== preview, "preview-expired")
                authority.near(player, blueprint.worldId, blueprint.building.entrance)
                rejectIf(placement.managedPlacement(player, cityId, blueprint.building.type) != previousCity, "stale-city")
            }) { confirmPreview(player, preview, true) }
        rejectIf(cityId in records.busy || cityId in records.unhealthy, "city-busy")
        if (previousCity == null) {
            requireFounder(player, preview.autonomous)
            checkSettlementSpace(blueprint.worldId, cityCenter)
            pendingFounders[player.uniqueId] = FoundingReservation(cityId, blueprint.worldId, cityCenter)
        }
        authority.reserveCity(cityId)
        val result = authority.tracked<Unit>()
        val captureOwner = previousCity?.owner ?: player.uniqueId
        val previous = previousCity?.building(blueprint.building.id)
        val adminPlacement = previousCity == null && player.hasPermission("arccivs.admin")
        val capture = if (preview.upgrading && !queuedUpgrade) world.capture(cityId, captureOwner, blueprint.worldId,
            blueprint.building, blueprint.cells, previous)
        else if (queuedUpgrade) world.captureSite(cityId, captureOwner, blueprint.worldId,
            blueprint.building, blueprint.cells, previous)
        else world.captureManualFoundation(cityId, captureOwner, blueprint.worldId, blueprint.building,
            blueprint.cells, executor, previous, autonomous = preview.autonomous, adminPlacement = adminPlacement).thenCompose { terrain ->
            if (preview.autonomous && !adminPlacement) world.captureAutonomousSite(cityId, captureOwner, blueprint.worldId,
                blueprint.building, blueprint.cells, terrain)
            else world.captureSite(cityId, captureOwner, blueprint.worldId, blueprint.building,
                blueprint.cells, previous, terrain, adminPlacement = adminPlacement)
        }
        capture.whenCompleteSync(tasks) { captured, captureFailure ->
            if (captureFailure != null) return@whenCompleteSync finishReservation(cityId, player.uniqueId, result, captureFailure)
            runCatching {
                rejectIf(!player.isOnline || placement.draft(player.uniqueId) !== preview, "preview-expired")
                authority.near(player, blueprint.worldId, blueprint.building.entrance)
                val placementCity = previousCity?.let {
                    placement.managedPlacement(player, it.id, blueprint.building.type).also { current -> rejectIf(current != it, "stale-city") }
                }
                val capturedPlan = requireNotNull(captured).copy(capturedSha256 = blueprint.snapshot.fileSha256,
                    adminPlacement = adminPlacement)
                if (preview.upgrading && !queuedUpgrade) {
                    startOrder(player.uniqueId, records.cities.getValue(cityId), capturedPlan, blueprint.sourceBytes).whenCompleteSync(tasks) { _, failure ->
                        if (failure == null) placement.cancelPreview(player.uniqueId)
                        finishReservation(cityId, player.uniqueId, result, failure)
                    }
                } else {
                    val control = if (queuedUpgrade || blueprint.building.type == BuildingType.DECORATION) null else controlInstallations.prepare(cityId, blueprint.worldId,
                        previousCity?.owner ?: player.uniqueId, blueprint.building, prepareApproach = capturedPlan.terrain == null)
                    // Queued upgrades have a separate immutable capture; the original SITE payload stays owned by its building.
                    val plan = capturedPlan.copy(id = if (queuedUpgrade) queuedUpgradeId(blueprint.building.id, blueprint.building.level)
                        else blueprint.building.id)
                    val placed = if (queuedUpgrade) CivsRules.enqueue(records.cities.getValue(cityId), player.uniqueId, blueprint.building.id)
                    else if (previousCity == null) {
                        checkSettlementSpace(blueprint.worldId, cityCenter, player.uniqueId)
                        requireFounderAfterCapture(player, preview.autonomous, adminPlacement)
                        CivsMembership.remember((if (preview.autonomous) CivsAutonomy::found else CivsRules::found).invoke(cityId, player.uniqueId, preview.cityName, blueprint.worldId,
                            cityCenter, settings.radius, preview.style, blueprint.building, authority.now()), mapOf(player.uniqueId to player.name))
                    } else if (placementCity?.autonomy != null) {
                        // Only managedPlacement's explicit Auto board branch may use the domain owner.
                        val site = CivsRules.placeSite(placementCity, placementCity.owner, blueprint.building)
                        CivsRules.enqueue(site, placementCity.owner, blueprint.building.id).copy(revision = placementCity.revision + 1)
                    } else CivsRules.placeSite(requireNotNull(placementCity), player.uniqueId, blueprint.building)
                    val next = if (control == null) placed else CivsRules.validate(placed.copy(pendingControls = placed.pendingControls.orEmpty() + control))
                    // File writes may finish after permissions or the selected city changed. Recheck on Main
                    // immediately before the city commit; other capture owners keep their existing save path.
                    store.saveSchematic(plan.id, blueprint.sourceBytes).thenCompose { store.savePlan(plan) }.thenCompose {
                        tasks.callSync {
                            rejectIf(!player.isOnline || placement.draft(player.uniqueId) !== preview, "preview-expired")
                            authority.requireReady(player)
                            authority.near(player, blueprint.worldId, blueprint.building.entrance)
                            if (placementCity == null) requireFounderAfterCapture(player, preview.autonomous, adminPlacement)
                            else rejectIf(placement.managedPlacement(player, cityId, blueprint.building.type) != placementCity, "stale-city")
                            authority.commitCapturedRecord(next)
                        }.thenCompose { it }
                    }.whenCompleteSync(tasks) { saved, saveFailure ->
                            if (saveFailure != null) {
                                val rejected = CivsDiagnostics.rejection(saveFailure)
                                if (rejected != null) {
                                    if (queuedUpgrade) cleanupCaptured(plan.id).whenCompleteSync(tasks) { _, _ ->
                                        finishReservation(cityId, player.uniqueId, result, rejected)
                                    } else finishReservation(cityId, player.uniqueId, result, rejected)
                                }
                                else {
                                    // Unknown commit outcomes keep the site capture for canonical recovery.
                                    authority.quarantineCity(cityId); authority.invalidateReadiness()
                                    authority.log("commit building design $cityId", saveFailure, cityId)
                                    finishReservation(cityId, player.uniqueId, result, CivsRejection("storage-unavailable"))
                                }
                            } else authority.publish(requireNotNull(saved)).whenCompleteSync(tasks) { _, publishFailure ->
                                if (publishFailure == null) {
                                    placement.cancelPreview(player.uniqueId)
                                    if (queuedUpgrade) {
                                        CivsDiagnostics.event("construction_enqueued", "city" to cityId, "building" to plan.building.id,
                                            "position" to (saved.constructionQueue.orEmpty().indexOf(plan.building.id) + 1), "upgrade" to true)
                                        finishReservation(cityId, player.uniqueId, result, null)
                                        return@whenCompleteSync
                                    }
                                    CivsDiagnostics.event("site_committed", "city" to cityId, "building" to plan.building.id,
                                        "control" to saved.building(plan.building.id)?.control)
                                    authority.releaseCity(cityId)
                                    runCatching { controlInstallations.install(cityId, plan.building.id) }.onSuccess { installed ->
                                        installed.whenCompleteSync(tasks) { _, controlFailure ->
                                            finishReservation(cityId, player.uniqueId, result, controlFailure)
                                        }
                                    }.onFailure { finishReservation(cityId, player.uniqueId, result, it) }
                                } else {
                                    authority.quarantineCity(cityId)
                                    finishReservation(cityId, player.uniqueId, result, publishFailure)
                                }
                            }
                        }
                }
            }.onFailure { finishReservation(cityId, player.uniqueId, result, it) }
        }
        return result
    }

    private fun requireFounderAfterCapture(player: Player, autonomous: Boolean = false, adminPlacement: Boolean = false) {
        rejectIf((autonomous || adminPlacement) && !player.hasPermission("arccivs.admin"), "not-authorized")
        rejectIf(!autonomous && (authority.city(player) != null || membership.remoteCity(player) != null), "already-member")
    }

    /** A searched town uses the ordinary SITE/grant/control transaction, with no manual administrator terrain bypass. */
    fun foundAutomatic(admission: CivVillageSiteAdmission): CompletableFuture<Settlement> {
        authority.checkMain()
        val proposed = admission.city
        val blueprint = admission.blueprint
        val hall = blueprint.building
        fun current() {
            rejectIf(!authority.ready() || !admission.current(), "not-ready")
            rejectIf(world.scanWorld(proposed.worldId)?.name !in settings.worlds, "world-not-allowed")
            rejectIf(!authority.testingAllowed(proposed), "not-authorized")
            rejectIf(proposed.id in records.unhealthy, "storage-unavailable")
        }
        current()
        records.cities[proposed.id]?.let { existing ->
            rejectIf(!CivsVillageSiteScoring.sameRequest(existing, proposed), "city-conflict")
            return CompletableFuture.completedFuture(existing)
        }
        rejectIf(proposed.autonomy == null || proposed.pendingDeletion != null || proposed.workers.isNotEmpty() ||
            proposed.construction != null || proposed.buildings.singleOrNull()?.id != hall.id ||
            proposed.worldId != blueprint.worldId || proposed.owner in pendingFounders || proposed.id in records.busy ||
            proposed.center != (hall.control ?: hall.entrance) || proposed.radius != settings.radius ||
            proposed.settings != CivSettlementSettings(chunkLoading = true, immortalResidents = true,
                autoConstructionMaterials = false), "invalid-state")
        val layout = proposed.autonomy?.plan ?: throw CivsRejection("invalid-plan")
        CivsSettlementPlans.validate(proposed, layout)
        rejectIf(!CivsVillageSiteScoring.completeProfile(layout), "autonomy-no-site")
        rejectIf(records.cities.values.any { it.name.equals(proposed.name, ignoreCase = true) }, "city-name-conflict")
        checkSettlementSpace(proposed.worldId, proposed.center)
        pendingFounders[proposed.owner] = FoundingReservation(proposed.id, proposed.worldId, proposed.center)
        authority.reserveCity(proposed.id)
        val result = authority.tracked<Settlement>()
        var commitAttempted = false
        fun finish(failure: Throwable?, saved: Settlement? = null) {
            authority.releaseCity(proposed.id)
            if (pendingFounders[proposed.owner]?.cityId == proposed.id) pendingFounders.remove(proposed.owner)
            if (failure == null) result.complete(requireNotNull(saved)) else result.completeExceptionally(failure)
            authority.updateHealth()
        }
        val capture = runCatching { world.captureAutonomousSite(proposed.id, proposed.owner, proposed.worldId,
            hall, blueprint.cells, admission.terrain) }.getOrElse {
            finish(it)
            return result
        }
        capture.whenCompleteSync(tasks) { captured, captureFailure ->
            if (captureFailure != null) { finish(captureFailure); return@whenCompleteSync }
            runCatching {
                current()
                rejectIf(records.cities.containsKey(proposed.id) || records.cities.values.any {
                    it.name.equals(proposed.name, ignoreCase = true)
                }, "city-name-conflict")
                checkSettlementSpace(proposed.worldId, proposed.center, proposed.owner)
                val control = controlInstallations.prepare(proposed.id, proposed.worldId, proposed.owner, hall,
                    prepareApproach = admission.terrain == null)
                val next = CivsRules.validate(proposed.copy(pendingControls = listOf(control)))
                val plan = requireNotNull(captured).copy(id = hall.id, capturedSha256 = blueprint.snapshot.fileSha256,
                    adminPlacement = false)
                store.saveSchematic(plan.id, blueprint.sourceBytes).thenCompose { store.savePlan(plan) }.thenCompose {
                    tasks.callSync {
                        current()
                        rejectIf(records.cities.containsKey(proposed.id) || records.cities.values.any {
                            it.name.equals(proposed.name, ignoreCase = true)
                        }, "city-name-conflict")
                        checkSettlementSpace(proposed.worldId, proposed.center, proposed.owner)
                        commitAttempted = true
                        authority.commitCapturedRecord(next)
                    }.thenCompose { it }
                }.whenCompleteSync(tasks) { saved, saveFailure ->
                    if (saveFailure != null) {
                        if (commitAttempted && CivsDiagnostics.rejection(saveFailure) == null) {
                            // An unknown SQL outcome keeps its exact source/capture for canonical reload recovery.
                            authority.quarantineCity(proposed.id)
                            authority.invalidateReadiness()
                            authority.log("commit automatic founding ${proposed.id}", saveFailure, proposed.id)
                            finish(CivsRejection("storage-unavailable"))
                        } else cleanupCaptured(plan.id).whenCompleteSync(tasks) { _, _ -> finish(saveFailure) }
                    } else authority.publish(requireNotNull(saved)).whenCompleteSync(tasks) { _, publishFailure ->
                        if (publishFailure != null) {
                            authority.quarantineCity(proposed.id)
                            finish(publishFailure)
                        } else {
                            CivsDiagnostics.event("autonomous_village_founded", "city" to saved.id, "owner" to saved.owner,
                                "name" to saved.name, "style" to saved.style, "center" to saved.center,
                                "plots" to layout.plots.size)
                            // The existing pending-control recovery also owns interrupted installation.
                            authority.releaseCity(proposed.id)
                            runCatching { controlInstallations.install(proposed.id, hall.id) }.onSuccess { installed ->
                                installed.whenCompleteSync(tasks) { _, failure ->
                                    if (failure != null) authority.log("install automatic founding control ${proposed.id}", failure, proposed.id)
                                }
                            }.onFailure { authority.log("install automatic founding control ${proposed.id}", it, proposed.id) }
                            finish(null, saved)
                        }
                    }
                }
            }.onFailure { finish(it) }
        }
        return result
    }

    fun startBuilding(player: Player, buildingId: UUID): CompletableFuture<Unit> = authority.action("construction_order", player, "building" to buildingId) {
        val offered = authority.managed(player)
        val cityId = offered.id
        val key = cityId to buildingId
        pendingBuildingOrders[key]?.takeUnless { it.isDone }?.let { return@action it }
        val offeredBuilding = offered.building(buildingId) ?: throw CivsRejection("building-not-found")
        rejectIf(offeredBuilding.phase != BuildingPhase.SITE, "invalid-building")
        val result = authority.afterCityAvailable(cityId) {
            val city = authority.managed(player)
            rejectIf(city.id != cityId || !player.isOnline, "not-authorized")
            val building = city.building(buildingId) ?: throw CivsRejection("building-not-found")
            rejectIf(building.phase != BuildingPhase.SITE, "invalid-building")
            authority.near(player, city.worldId, building.entrance)
            construction.clearQueueFailure(city.id)
            if (city.construction != null || city.constructionQueue.orEmpty().isNotEmpty()) {
                authority.mutate(city.id) { CivsRules.enqueue(it, player.uniqueId, buildingId) }.thenApply { saved ->
                    if (saved.revision != city.revision) CivsDiagnostics.event("construction_enqueued", "city" to city.id,
                        "building" to buildingId, "position" to (saved.constructionQueue.orEmpty().indexOf(buildingId) + 1))
                    Unit
                }
            } else activateBuilding(city, building, player)
        }
        pendingBuildingOrders[key] = result
        result.whenCompleteSync(tasks) { _, _ ->
            if (pendingBuildingOrders[key] === result) pendingBuildingOrders.remove(key)
        }
        result
    }

    private fun cleanupCaptured(id: UUID): CompletableFuture<Unit> =
        runCatching { store.deletePlan(id).thenCompose { store.deleteSchematic(id) }.handle { _, failure ->
            if (failure != null) authority.log("clean captured plan $id", failure)
            Unit
        } }.getOrElse { authority.log("clean captured plan $id", it); CompletableFuture.completedFuture(Unit) }

    fun cleanupQueuedUpgrades(city: Settlement, buildingIds: Set<UUID>) {
        city.buildings.filter { it.id in buildingIds && it.id in city.constructionQueue.orEmpty() && it.phase == BuildingPhase.ACTIVE }
            .forEach { cleanupCaptured(queuedDesignId(it)) }
    }

    fun activateBuilding(city: Settlement, building: CivBuilding, player: Player? = null): CompletableFuture<Unit> =
        activateBuilding(city, building, player, false)

    private fun activateBuilding(city: Settlement, building: CivBuilding, player: Player?, storageChecked: Boolean): CompletableFuture<Unit> {
        val buildingId = building.id
        val actor = player?.uniqueId ?: city.owner
        rejectIf(city.pendingDeletion != null, "deletion-pending")
        rejectIf(city.construction != null || city.id in records.busy, "city-busy")
        if (building.phase == BuildingPhase.SITE) controlInstallations.requirePresent(city.worldId, building)
        requireAssignedBuilder(city, building.id)
        authority.reserveCity(city.id)
        val result = authority.tracked<Unit>()
        var ownsReservation = true
        fun completeActivation(failure: Throwable?) {
            if (ownsReservation) { ownsReservation = false; authority.releaseCity(city.id) }
            if (failure == null) result.complete(Unit) else result.completeExceptionally(failure)
            authority.updateHealth()
        }
        val designId = queuedDesignId(building)
        store.loadPlan(designId).thenCompose { plan ->
            requireNotNull(plan) { "Missing queued design $buildingId" }
            requireQueuedDesign(plan, city, building)
            blueprints.recover(plan).thenCombine(store.loadSchematic(designId)) { snapshot, bytes ->
                Triple(plan, snapshot, requireNotNull(bytes))
            }
        }.whenCompleteSync(tasks) { loaded, failure ->
            if (failure != null) return@whenCompleteSync completeActivation(failure)
            runCatching {
                val (sitePlan, snapshot, bytes) = requireNotNull(loaded)
                val target = if (building.phase == BuildingPhase.SITE) building.copy(phase = BuildingPhase.CONSTRUCTING)
                    else sitePlan.building.copy(production = building.production)
                if (target.control != building.control) {
                    ownsReservation = false
                    authority.releaseCity(city.id)
                    storageActions.relocateControl(city, building, requireNotNull(target.control)).whenCompleteSync(tasks) { relocated, relocationFailure ->
                        if (relocationFailure != null) completeActivation(relocationFailure)
                        else runCatching { activateBuilding(requireNotNull(relocated), requireNotNull(relocated.building(buildingId)), player) }
                            .onSuccess { activated -> activated.whenCompleteSync(tasks) { _, activationFailure ->
                                completeActivation(activationFailure)
                            } }.onFailure { completeActivation(it) }
                    }
                    return@whenCompleteSync
                }
                controlInstallations.requirePresent(city.worldId, building)
                CompletableFuture.supplyAsync({ CivsBlueprints.worldCells(snapshot, target.anchor,
                    decoration = target.type == BuildingType.DECORATION) }, executor).whenCompleteSync(tasks) cellsReady@ { cells, cellsFailure ->
                    if (cellsFailure != null) return@cellsReady completeActivation(cellsFailure)
                    if (!storageChecked && building.phase == BuildingPhase.ACTIVE && !building.type.isInfrastructure) {
                        if (!authority.ready() || records.cities[city.id] != city || city.id !in records.busy) {
                            completeActivation(CivsRejection("stale-city"))
                            return@cellsReady
                        }
                        ownsReservation = false
                        authority.releaseCity(city.id)
                        withUpgradeStorage(city, target, requireNotNull(cells), freshAdmission = {
                            if (player != null) {
                                rejectIf(!player.isOnline, "not-authorized")
                                rejectIf(placement.managedPlacement(player, city.id, building.type) != city, "stale-city")
                                authority.near(player, city.worldId, building.entrance)
                            }
                        }) { refreshed ->
                            activateBuilding(refreshed, requireNotNull(refreshed.building(buildingId)), player, true)
                        }.whenCompleteSync(tasks) { _, relocationFailure -> completeActivation(relocationFailure) }
                        return@cellsReady
                    }
                    // Waiting designs reserve against fresh before-images, never the terrain at confirmation.
                    val terrain = if (building.phase == BuildingPhase.SITE &&
                        (sitePlan.adminPlacement || city.autonomy == null || sitePlan.terrain?.route?.isEmpty() == true))
                        world.captureManualFoundation(city.id, city.owner, city.worldId, target,
                            requireNotNull(cells), executor, building, adminPlacement = sitePlan.adminPlacement)
                    else if (city.autonomy != null && building.phase == BuildingPhase.SITE)
                        autonomousPlacement.prepareExisting(city, CivBlueprint(target, city.worldId, snapshot,
                            requireNotNull(cells), emptyList(), bytes), sitePlan.terrain?.waterFoundations.orEmpty()).thenApply { it.terrain }
                    else CompletableFuture.completedFuture(sitePlan.terrain)
                    terrain.thenCompose { preparation -> world.capture(city.id, city.owner, city.worldId,
                        target, requireNotNull(cells), building, preparation, adminPlacement = sitePlan.adminPlacement) }
                        .whenCompleteSync(tasks) capturedReady@ { captured, captureFailure ->
                            if (captureFailure != null) return@capturedReady completeActivation(captureFailure)
                            runCatching {
                                rejectIf(!authority.ready() || records.cities[city.id]?.pendingDeletion != null, "not-ready")
                                if (player != null) {
                                    rejectIf(!player.isOnline, "not-authorized")
                                    authority.near(player, city.worldId, building.entrance)
                                }
                                controlInstallations.requirePresent(city.worldId, building)
                                startOrder(actor, records.cities.getValue(city.id), requireNotNull(captured).copy(capturedSha256 = sitePlan.capturedSha256), bytes)
                                    .whenCompleteSync(tasks) { _, orderFailure ->
                                        if (orderFailure == null && building.phase == BuildingPhase.ACTIVE) cleanupCaptured(designId)
                                        completeActivation(orderFailure)
                                    }
                            }.onFailure { completeActivation(it) }
                        }
                }
            }.onFailure { completeActivation(it) }
        }
        return result
    }

    /** Starting an order reserves the immutable design; each worked row pays independently later. */
    private fun startOrder(actor: UUID, city: Settlement, plan: CivPlacementPlan, bytes: ByteArray): CompletableFuture<Settlement> {
        rejectIf(city.construction != null, "construction-busy")
        rejectIf(!CivsAutoStorageMigration.ready(city), "materials-migration-pending")
        rejectIf(city.inventory == null && city.autonomy == null, "materials-migration-pending")
        rejectIf(!city.canManage(actor), "not-authorized")
        val building = plan.building
        requireAssignedBuilder(city, building.id)
        if (building.type == BuildingType.TOWN_HALL && building.level > city.level()) {
            rejectIf(records.cities.values.any { it.id != city.id && it.worldId == city.worldId &&
                CivsService.areasOverlap(city.center, city.radius + 32, it.center, it.radius) }, "city-overlap")
        }
        rejectIf(city.pendingControls.orEmpty().any { it.buildingId == building.id }, "city-busy")
        val approach = if (plan.terrain != null || building.type.isInfrastructure || building.type == BuildingType.DECORATION) null
            else controlInstallations.prepare(city.id, city.worldId, city.owner, building).takeIf { !it.approach.isNullOrEmpty() }
        val result = authority.tracked<Settlement>()
        CompletableFuture.supplyAsync({
            val materials = CivsMaterials.required(plan)
            val terrainBatches = civsTerraformBatches(plan)
            val automaticPreparation = terrainBatches.size.takeIf { it > 0 && plan.terrain?.route?.isEmpty() == true }
            val batches = CivConstructionBatches(terrainBatches.size + civsMaterialBatches(plan, atomicPairs = true).size, 16, atomicPairs = true)
            CivConstruction(plan.id, building.id, building.level, batches.total, 0,
                if (automaticPreparation != null) ConstructionPhase.READY_TO_PLACE else ConstructionPhase.CONSTRUCTING,
                if (CivsAutonomy.legacyStock(city) && !CivsPlanBoardBlueprint.refit(city.building(building.id), building))
                    CivsAutonomy.cost(building.type, building.level, materials) else Stock(),
                plannedBuilding = building, batches = batches, materials = materials,
                payment = if (CivsAutonomy.legacyStock(city)) null else CivConstructionPayment(),
                blocksRequired = plan.blocks.count { it.before != it.after } + plan.terrain?.blocks.orEmpty().count { it.before != it.after },
                blocksPlaced = 0, preparationWorkplace = terrainBatches.firstOrNull()?.workplace ?: plan.terrain?.route?.lastOrNull(),
                automaticPreparationBatches = automaticPreparation)
        }, executor).thenCompose { order ->
            store.saveSchematic(plan.id, bytes).thenCompose { store.savePlan(plan) }.thenCompose {
                // Order captures are deleted on completion; keep the admitted access road with its building.
                if (city.autonomy != null && plan.terrain != null)
                    store.saveSchematic(building.id, bytes).thenCompose { store.savePlan(plan.copy(id = building.id)) }
                else CompletableFuture.completedFuture(plan)
            }.thenApply { order }
        }.whenCompleteSync(tasks) { preparedOrder, failure ->
            if (failure != null) { authority.releaseCity(city.id); result.completeExceptionally(failure) }
            else runCatching {
                requireAssignedBuilder(records.cities.getValue(city.id), building.id)
                val order = requireNotNull(preparedOrder)
                val materials = requireNotNull(order.materials)
                val batches = requireNotNull(order.batches)
                authority.releaseCity(city.id)
                authority.mutate(city.id) { before ->
                    val reserved = CivsRules.reserveBuild(before, actor, building, order)
                    if (approach == null) reserved else CivsRules.validate(reserved.copy(
                        pendingControls = reserved.pendingControls.orEmpty() + approach))
                }
                    .whenCompleteSync(tasks) { saved, reserveFailure ->
                    if (reserveFailure != null) result.completeExceptionally(reserveFailure)
                    else {
                        CivsDiagnostics.event("construction_reserved", "city" to city.id, "order" to order.id,
                            "building" to building.id, "batches" to batches.total, "materials" to materials, "payment" to "per_batch")
                        if (approach == null) result.complete(saved)
                        else runCatching { controlInstallations.install(city.id, building.id) }.onSuccess { installed ->
                            installed.whenCompleteSync(tasks) { applied, installFailure ->
                                if (installFailure == null) result.complete(applied) else result.completeExceptionally(installFailure)
                            }
                        }.onFailure(result::completeExceptionally)
                    }
                }
            }.onFailure { authority.releaseCity(city.id); result.completeExceptionally(it) }
        }
        return result
    }

    private fun requireAssignedBuilder(city: Settlement, buildingId: UUID) {
        rejectIf(city.workers.none { worker -> worker.assignment?.let(city::building)?.let { assigned ->
            assigned.type == BuildingType.BUILDER_HUT && (assigned.phase == BuildingPhase.ACTIVE || assigned.id == buildingId) ||
                assigned.type == BuildingType.TOWN_HALL && CivsRules.legacyHallBuilder(city)
        } == true }, "builder-required")
    }

    private fun finishReservation(cityId: UUID, playerId: UUID, future: CompletableFuture<Unit>, failure: Throwable?) {
        authority.releaseCity(cityId)
        if (pendingFounders[playerId]?.cityId == cityId) pendingFounders.remove(playerId)
        if (failure == null) future.complete(Unit) else future.completeExceptionally(failure)
        authority.updateHealth()
    }

    fun commitAutonomousDesign(city: Settlement, design: CivAutonomousDesign): CompletableFuture<Unit> {
        val blueprint = design.blueprint
        val previous = city.building(blueprint.building.id)
        val result = authority.tracked<Unit>()
        authority.reserveCity(city.id)
        val capture = runCatching {
            if (previous == null) world.captureAutonomousSite(city.id, city.owner, city.worldId, blueprint.building, blueprint.cells, design.terrain)
            else world.captureSite(city.id, city.owner, city.worldId, blueprint.building, blueprint.cells, previous)
        }
            .getOrElse { authority.releaseCity(city.id); return CompletableFuture.failedFuture(it) }
        capture.whenCompleteSync(tasks) { captured, captureFailure ->
            if (captureFailure != null) { authority.releaseCity(city.id); result.completeExceptionally(captureFailure); return@whenCompleteSync }
            runCatching {
                rejectIf(!authority.ready() || records.cities[city.id] != city || city.autonomy?.paused != false, "stale-development")
                val control = if (previous == null && !blueprint.building.type.isInfrastructure && blueprint.building.type != BuildingType.DECORATION)
                    controlInstallations.prepare(city.id, city.worldId, city.owner, blueprint.building,
                        prepareApproach = design.terrain == null) else null
                val placed = if (previous == null) CivsRules.placeSite(city, city.owner, blueprint.building) else city
                val queued = CivsRules.enqueue(placed, city.owner, blueprint.building.id)
                val next = CivsRules.validate(queued.copy(revision = city.revision + 1,
                    autonomy = queued.autonomy?.let { it.copy(layoutHalfWidth = it.layoutHalfWidth ?: CivsSettlementLayout.frame(city).halfWidth) },
                    pendingControls = if (control == null) queued.pendingControls else queued.pendingControls.orEmpty() + control))
                val plan = requireNotNull(captured).copy(id = if (previous == null) blueprint.building.id
                    else queuedUpgradeId(previous.id, blueprint.building.level), capturedSha256 = blueprint.snapshot.fileSha256)
                authority.saveCapturedCity(plan, blueprint.sourceBytes, next).whenCompleteSync(tasks) { saved, saveFailure ->
                    if (saveFailure != null) {
                        authority.releaseCity(city.id)
                        if (CivsDiagnostics.rejection(saveFailure) == null) authority.quarantineCity(city.id)
                        result.completeExceptionally(saveFailure)
                    } else authority.publish(requireNotNull(saved)).whenCompleteSync(tasks) { _, publishFailure ->
                        authority.releaseCity(city.id)
                        if (publishFailure != null) { authority.quarantineCity(city.id); result.completeExceptionally(publishFailure) }
                        else {
                            CivsDiagnostics.event("autonomous_goal_queued", "city" to city.id, "building" to plan.building.id,
                                "type" to plan.building.type, "level" to plan.building.level,
                                "entrance" to plan.building.entrance, "rotation" to plan.building.rotation, "revision" to saved.revision)
                            result.complete(Unit) // The existing controls/queue recovery finishes after this durable intent.
                        }
                    }
                }
            }.onFailure { authority.releaseCity(city.id); result.completeExceptionally(it) }
        }
        return result
    }

    private fun withUpgradeStorage(city: Settlement, target: CivBuilding, cells: List<ru.arc.paper.api.ArcSchematicBlock>,
        freshAdmission: () -> Unit = {},
        resume: (Settlement) -> CompletableFuture<Unit>): CompletableFuture<Unit> {
        val result = authority.tracked<Unit>()
        runCatching { authority.relocateUpgradeStorage(city, target, cells, freshAdmission) }.onSuccess { moving ->
            moving.whenCompleteSync(tasks) { saved, failure ->
                if (failure != null) result.completeExceptionally(failure)
                else runCatching { resume(requireNotNull(saved)) }.onSuccess { resumed ->
                    resumed.whenComplete { _, error -> if (error == null) result.complete(Unit) else result.completeExceptionally(error) }
                }.onFailure { result.completeExceptionally(it) }
            }
        }.onFailure { result.completeExceptionally(it) }
        return result
    }

    fun payBatch(city: Settlement, order: CivConstruction, required: Map<String, Long>): CompletableFuture<Settlement> =
        authority.inventoryMutation(city.id, { current ->
            if (CivsRules.constructionStorageInputs(current, order.buildingId, required).isNotEmpty()) null
            else CivStoragePlan(requireNotNull(current.inventory), emptyList())
        }, keepViewersOpen = true) { before, inventory ->
            CivsRules.payBatch(before, before.owner, order.id, order.workDone, required).copy(inventory = inventory)
        }.whenComplete { saved, failure ->
            if (failure == null && saved?.construction?.payment?.reservedBatch == order.workDone)
                CivsDiagnostics.event("construction_batch_paid", "city" to city.id, "order" to order.id,
                "batch" to order.workDone, "materials" to required, "revision" to saved.revision)
        }
    fun checkSettlementSpace(worldId: UUID, center: CivPosition, ignoreFounder: UUID? = null) {
        rejectIf(records.cities.size + pendingFounders.keys.count { it != ignoreFounder } >= settings.maximumCities, "settlement-limit")
        rejectIf(records.cities.values.any { it.worldId == worldId && CivsService.areasOverlap(center, settings.radius + CivsRules.MAX_TERRITORY_GROWTH, it.center, it.radius + (3 - it.level()) * 32) } ||
            pendingFounders.any { (owner, place) -> owner != ignoreFounder && place.worldId == worldId && CivsService.areasOverlap(center, settings.radius + CivsRules.MAX_TERRITORY_GROWTH, place.center, settings.radius + CivsRules.MAX_TERRITORY_GROWTH) }, "city-overlap")
    }

    private fun rejectIf(condition: Boolean, reason: String) { if (condition) throw CivsRejection(reason) }
}
