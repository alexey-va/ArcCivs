package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

internal class CivsCityDiagnostics(
    private val authority: CivsService,
    private val records: CivsCityRecords,
    private val placementOwner: () -> CivsPlacementSession,
    private val buildingRequestsOwner: () -> CivsBuildingRequests,
    private val plugin: Plugin,
    private val blueprints: CivsBlueprints,
    private val world: CivsWorld,
    private val layoutDebug: CivsLayoutDebug,
    private val autonomousDevelopment: CivsAutonomousDevelopment,
    private val storage: CivsStorage,
    private val worksites: CivsWorksites,
    private val chunkLoading: CivsChunkLoading,
    private val testingPermissions: CivsChunkPermissions,
    private val citizens: CivsCitizens,
    private val supply: CivsSupplyDispatcher,
    private val construction: CivsConstructionRuntime,
    private val inventoryJournal: CivsInventoryJournal,
    private val residentLife: CivsResidentLifeRuntime,
    private val lastInjury: Map<UUID, CivDeathContext>,
) {
    private val placement get() = placementOwner()
    private val buildingRequests get() = buildingRequestsOwner()

    /** Main captures city identity; plans, serialization and atomic file IO remain on the bounded worker. */
    fun debugLayout(cityId: UUID, terrain: Boolean): CompletableFuture<Map<String, Any?>> {
        check(Bukkit.isPrimaryThread())
        if (records.closed || !authority.ready()) return CompletableFuture.failedFuture(CivsRejection("not-ready"))
        val city = records.cities[cityId] ?: return CompletableFuture.failedFuture(CivsRejection("city-not-found"))
        val nativeWorld = plugin.server.getWorld(city.worldId)
        if (terrain && nativeWorld == null) return CompletableFuture.failedFuture(CivsRejection("terrain-world-not-loaded"))
        val hall = city.buildings.firstOrNull { it.type == BuildingType.TOWN_HALL }
            ?: return CompletableFuture.failedFuture(CivsRejection("layout-town-hall-missing"))
        val runtime = mapOf("captured_at_ms" to authority.now(), "coverage" to "existing-bounded-owner-debug-samples-with-total-and-truncated",
            "citizens" to debugState("citizens", cityId, null), "worksites" to debugState("worksites", cityId, null),
            "supply" to debugState("supply", cityId, null))
        return layoutDebug.export(city, nativeWorld?.name, plugin.pluginMeta.version, runtime,
            if (terrain) ({ world.autonomousHeightField(city, hall, hall) }) else null) { records.cities[cityId] }
            .whenComplete { _, failure ->
                if (failure != null && CivsDiagnostics.rejection(failure) == null &&
                    CivsDiagnostics.cause(failure) !is java.util.concurrent.CancellationException)
                    CivsDiagnostics.record(Level.WARNING, cityId, "ArcCivs layout export failed city=$cityId terrain=$terrain", plugin.logger, failure)
            }
    }

    internal fun debugTerrainAt(cityId: UUID, workerId: UUID, origin: CivPosition): Map<String, Any?> {
        check(Bukkit.isPrimaryThread())
        val header = mapOf("source" to "fresh-native-command", "city" to cityId, "worker" to workerId,
            "focus_origin" to "operator-request", "capture_center" to origin)
        if (records.closed || !authority.ready()) return header + ("error" to "not-ready")
        val city = records.cities[cityId] ?: return header + ("error" to "city-not-found")
        val worker = city.workers.firstOrNull { it.id == workerId } ?: return header + ("error" to "worker-not-found")
        return CivsTerrainDebug.capture(city, worker, citizens.residentFeet(workerId), worksites.plan(city, worker), authority.now(), origin)
    }

    /** Cached snapshots by default; explicit terrain alone captures a bounded loaded native neighbourhood. */
    fun debugState(system: String, cityId: UUID?, workerId: UUID?): Map<String, Any?> {
        check(Bukkit.isPrimaryThread())
        val selected = if (cityId == null) records.cities.values.take(8) else listOfNotNull(records.cities[cityId])
        if (cityId != null && selected.isEmpty()) return mapOf("error" to "city-not-found")
        if (workerId != null && selected.none { city -> city.workers.any { it.id == workerId } })
            return mapOf("error" to "worker-not-found")
        val total = if (cityId == null) records.cities.size else selected.size
        val selectedWorkers = if (cityId == null) null else selected.flatMap { city ->
            city.workers.filter { workerId == null || it.id == workerId }
        }
        fun rows(transform: (Settlement) -> Map<String, Any?>): Map<String, Any?> = mapOf(
            "total" to total, "truncated" to (total > selected.size), "entries" to selected.map(transform))
        return when (system) {
            "research" -> rows { city -> mapOf("city" to city.id, "revision" to city.revision, "view" to CivsResearch.view(city)) }
            "industry" -> rows { city -> mapOf("city" to city.id, "revision" to city.revision, "view" to CivsIndustry.view(city)) }
            "autonomy" -> rows { city -> mapOf("city" to city.id, "enabled" to (city.autonomy != null),
                "state" to city.autonomy, "progress" to autonomyStatus(city.id), "stock" to city.stock) +
                autonomousDevelopment.debug(city.id) }
            "terrain" -> {
                if (cityId == null || workerId == null) return mapOf("error" to "terrain-requires-city-and-worker")
                val city = selected.single()
                val worker = city.workers.first { it.id == workerId }
                CivsTerrainDebug.capture(city, worker, citizens.residentFeet(workerId), worksites.plan(city, worker), authority.now())
            }
            "citizens" -> citizens.debug(cityId, workerId)
            "worksites" -> worksites.debug(selectedWorkers?.mapTo(hashSetOf()) { it.id },
                selectedWorkers?.mapNotNullTo(hashSetOf()) { it.assignment })
            "supply" -> supply.debug(cityId, workerId) + ("forestrySurplus" to authority.forestrySurplusDebug(cityId))
            "storage" -> storage.debug(cityId)
            "chunks" -> chunkLoading.debug(cityId, if (cityId == null) null else selected.mapTo(hashSetOf()) { it.owner })
            "construction" -> rows(construction::debug)
            "resources" -> rows { city ->
                val workers = city.workers.filter { workerId == null || it.id == workerId }
                mapOf("city" to city.id, "revision" to city.revision, "mode" to city.inventory?.mode,
                    "autonomous_storage" to city.autonomy?.storage,
                    "stock" to city.stock, "total_workers" to workers.size, "truncated_workers" to (workers.size > 8),
                    "workers" to workers.take(8).map { worker -> mapOf("worker" to worker.id,
                        "assignment" to worker.assignment, "shipment" to worker.cargo?.id, "pending_stock" to worker.pendingStock,
                        "cargo" to worker.cargo?.let { mapOf("material" to it.material, "amount" to it.amount,
                            "source" to it.sourceBuildingId, "destination" to it.targetBuildingId, "source_worker" to it.sourceWorkerId,
                            "stock_resource" to it.stockResource) },
                        "items" to CivsEquipment.slots(worker).take(CivsEquipment.MAX_SLOTS).mapIndexedNotNull { slot, item ->
                            item?.let { mapOf("slot" to slot, "material" to it.material, "amount" to it.amount,
                                "custom_data" to (it.data != null)) }
                        }) })
            }
            "residents" -> rows { city ->
                val workers = city.workers.filter { workerId == null || it.id == workerId }
                mapOf("city" to city.id, "settings" to (city.settings ?: CivSettlementSettings()),
                    "testing_permissions" to testingPermissions.debug(setOf(city.owner)), "population_pending" to (residentLife.pending(city.id)),
                    "last_population_minute" to residentLife.nextMinuteAt(city.id), "immigration" to city.immigration,
                    "total_workers" to workers.size, "truncated_workers" to (workers.size > 8),
                    "workers" to workers.take(8).map { mapOf("worker" to it.id, "name" to it.name,
                        "assignment" to it.assignment, "cycles" to it.completedCycles, "health" to it.health,
                        "hunger" to it.hunger, "home" to it.homeId, "sex" to it.sex, "child_minutes" to it.childMinutes,
                        "skills" to it.skills, "injury" to lastInjury[it.id]) },
                    "families_total" to city.families.orEmpty().size, "families" to city.families.orEmpty().take(8),
                    "corpses_total" to city.corpses.orEmpty().size, "corpses" to city.corpses.orEmpty().take(8).map {
                        mapOf("worker" to it.worker.id, "position" to it.position, "died_at" to it.diedAt,
                            "expires_at" to it.expiresAt, "killer" to it.killerName, "killer_type" to it.killerType, "cause" to it.cause)
                    })
            }
            "overview" -> mapOf("ready" to authority.ready(), "loading" to records.loading, "closed" to records.closed,
                "health" to mapOf("state" to authority.health().state, "recovery_backlog" to authority.health().recoveryBacklog,
                    "active_leases" to authority.health().activeLeases, "dependencies" to authority.health().dependencies),
                "load_failure" to records.loadFailure, "cities_total" to records.cities.size, "city_rows_truncated" to (total > selected.size),
                "active_npcs" to citizens.activeCount(), "pending_operations" to records.pendingOperations,
                "inventory_recovery_total" to inventoryJournal.pendingCount, "blocked_recovery_total" to inventoryJournal.blockedCount,
                "previews" to placement.previewCount, "selections" to placement.selectionCount, "preview_loads" to placement.loadingCount,
                "pending_founders" to buildingRequests.founderCount, "pending_confirmations" to buildingRequests.confirmationCount,
                "pending_building_orders" to buildingRequests.orderCount, "geometry_generation" to records.geometryGeneration,
                "cities" to selected.map { city -> mapOf("city" to city.id, "name" to city.name, "owner" to city.owner,
                    "world" to city.worldId, "revision" to city.revision, "workers" to city.workers.size,
                    "buildings" to city.buildings.size, "busy" to (city.id in records.busy), "unhealthy" to (city.id in records.unhealthy),
                    "building_catalog_truncated" to (city.buildings.size > 32),
                    "building_catalog" to city.buildings.take(32).map { building -> mapOf(
                        "id" to building.id, "type" to building.type, "phase" to building.phase, "level" to building.level,
                        "assigned_adults" to city.workers.filter { it.assignment == building.id && !CivsResidents.isChild(it) }.map { it.id },
                        "serving_tavern" to CivsRules.staffedTavern(city, building)) },
                    "inventory_recovery" to inventoryJournal.pendingCount(city.id),
                    "deletion_pending" to (city.pendingDeletion != null),
                    "pending_controls" to city.pendingControls.orEmpty().take(8).map { mapOf("building" to it.buildingId, "position" to it.position) }) })
            else -> mapOf("error" to "unknown-system")
        }
    }
    fun workerStatus(workerId: UUID): String {
        val city = records.cities.values.firstOrNull { it.workers.any { worker -> worker.id == workerId } }
            ?: return "workers.status.unavailable"
        val worker = city.workers.first { it.id == workerId }
        if (city.pendingDeletion != null) return "workers.status.waiting"
        if (city.autonomy?.paused == true) return "workers.status.autonomy-paused"
        if (city.id in records.unhealthy && inventoryJournal.pending(city.id)) return "workers.status.inventory-recovery"
        if (city.id in records.unhealthy) return "workers.status.storage-unavailable"
        citizenPlan(city, worker)?.idleReason?.let {
            val reason = if (it == "construction-paused") constructionFailureStatus(city.construction?.failure) else it
            return "workers.status.$reason"
        }
        val status = citizens.status(workerId)
        return if (status in setOf("workers.status.unassigned", "workers.status.no-construction", "workers.status.building-unavailable",
                "workers.status.construction-paused", "workers.status.waiting-materials", "workers.status.placing")) "workers.status.waiting" else status
    }

    fun buildingStatus(buildingId: UUID): String {
        val city = records.cities.values.firstOrNull { it.building(buildingId) != null } ?: return "building-status.paused"
        val building = requireNotNull(city.building(buildingId))
        val order = city.construction?.takeIf { it.buildingId == buildingId }
        val status = when {
            city.pendingDeletion != null -> "deleting"
            city.id in records.unhealthy && inventoryJournal.pending(city.id) -> "inventory-recovery"
            city.id in records.unhealthy -> "storage-unavailable"
            order?.phase == ConstructionPhase.WAITING_MATERIALS -> "waiting-materials"
            order?.phase == ConstructionPhase.PAUSED -> constructionFailureStatus(order.failure)
            order?.phase in setOf(ConstructionPhase.PLACING, ConstructionPhase.READY_TO_PLACE) -> "placing"
            buildingId in city.constructionQueue.orEmpty() -> construction.queueFailure(city.id, buildingId)?.let {
                constructionFailureStatus(it)
            } ?: "queued"
            building.phase == BuildingPhase.SITE -> "site"
            else -> {
                val workers = city.workers.filter { worker -> worker.assignment?.let(city::building)?.let { assigned ->
                    if (order != null) CivsRules.isBuilder(city, assigned) && CivsRules.builderCanWork(city, assigned)
                    else assigned.id == buildingId
                } == true }
                if (workers.isEmpty()) {
                    if (order != null) "builder-required" else if (CivsRules.slots(city, building) > 0) "no-workers" else "active"
                } else workers.map { workerStatus(it.id).removePrefix("workers.status.") }.let { states ->
                    val selected = states.firstOrNull { it in setOf("working", "moving-to-work", "moving-to-storage") } ?: states.first()
                    when (selected) {
                        "waiting", "delivering" -> "working"
                        "world-unloaded", "chunk-unloaded", "capacity", "closed" -> "sleeping"
                        "construction-paused", "building-unavailable", "unavailable" -> "paused"
                        else -> selected
                    }
                }
            }
        }
        return "building-status.$status"
    }

    private fun constructionFailureStatus(reason: String?): String = when (reason) {
        "builder-required", "storage-full", "storage-unavailable", "route-unreachable",
        "work-route-too-long", "lands-denied", "world-changed", "protected-terrain", "building-overlap",
        "outside-city", "protected-area", "placement-failed", "placement-cancelled" -> reason
        "control-position-missing", "control-missing", "control-obstructed" -> "control-missing"
        "world-unavailable", "chunk-unloaded", "chunks-unloaded" -> "sleeping"
        else -> "paused"
    }

    fun nextAutonomyGoal(cityId: UUID): CivAutonomyGoal? =
        records.cities[cityId]?.let { CivsAutonomy.nextGoalAfterCurrent(it, blueprints::maximumLevel) }

    fun autonomyStatus(cityId: UUID): CivsAutonomyView? {
        val city = records.cities[cityId]?.takeIf { it.autonomy != null } ?: return null
        if (city.autonomy?.paused == true) return CivsAutonomyView("paused")
        if (city.id in records.unhealthy) return CivsAutonomyView("blocked", reason = "storage-unavailable")
        city.construction?.let { order ->
            val target = order.plannedBuilding ?: city.building(order.buildingId)
            return CivsAutonomyView(if (order.phase == ConstructionPhase.PAUSED) "blocked" else "building",
                target?.type, order.targetLevel, order.failure)
        }
        val goal = CivsAutonomy.nextGoal(city, blueprints::maximumLevel) ?: return CivsAutonomyView("complete")
        if (city.constructionQueue.orEmpty().isNotEmpty()) {
            val failure = construction.queueFailure(cityId, city.constructionQueue?.firstOrNull())
            return CivsAutonomyView(if (failure == null) "queued" else "blocked", goal.type, goal.targetLevel, failure)
        }
        if (!goal.ready) return CivsAutonomyView("resources", goal.type, goal.targetLevel, missing = goal.missing)
        return autonomousDevelopment.progress(cityId)?.takeIf { it.target == goal.type && it.level == goal.targetLevel }
            ?: CivsAutonomyView("planning", goal.type, goal.targetLevel)
    }

}
