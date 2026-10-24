package ru.arc.civs

import com.google.gson.GsonBuilder
import ru.arc.core.LifecycleTaskScope
import ru.arc.persistence.AtomicFileStore
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Detached operator export. Native capture uses CivsWorld's shared scan budget; IO never runs on Main. */
internal class CivsLayoutDebug(
    private val root: Path,
    private val tasks: LifecycleTaskScope,
    private val executor: Executor,
    private val loadPlan: (UUID) -> CompletableFuture<CivPlacementPlan?>,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private class Export(val city: UUID) {
        val cancelled = AtomicBoolean()
        val result = CompletableFuture<Map<String, Any?>>()
        var terrain: CompletableFuture<CivTerrainSurface>? = null
        var completion: CompletableFuture<*> = CompletableFuture.completedFuture(Unit)
        fun check() { if (cancelled.get()) throw CancellationException("Layout export cancelled") }
    }
    private val monitor = Any()
    private val pending = linkedMapOf<UUID, Export>()
    private var closed = false
    private var drain = CompletableFuture.completedFuture<Void>(null)

    /** Called on Main with an immutable city snapshot and a Main-only completion lookup. */
    fun export(city: Settlement, worldName: String?, pluginVersion: String,
        runtimeSnapshot: Map<String, Any?>,
        terrain: (() -> CompletableFuture<CivTerrainSurface>)?, current: () -> Settlement?): CompletableFuture<Map<String, Any?>> {
        val operation = synchronized(monitor) {
            if (closed) return CompletableFuture.failedFuture(CivsRejection("layout-export-closed"))
            if (city.id in pending) return CompletableFuture.failedFuture(CivsRejection("layout-export-city-busy"))
            if (pending.size >= MAX_PENDING) return CompletableFuture.failedFuture(CivsRejection("layout-export-busy"))
            Export(city.id).also { pending[city.id] = it }
        }
        val started = clock()
        val captured = snapshot(city)
        val runtime = runtimeSnapshot.mapValues { detached(it.value) }
        val surface: CompletableFuture<out CivTerrainSurface?> = runCatching { terrain?.invoke()?.also { operation.terrain = it } }
            .getOrElse { CompletableFuture.failedFuture(it) }
            ?: CompletableFuture.completedFuture(null)
        operation.result.whenComplete { _, _ ->
            if (operation.result.isCancelled) {
                operation.cancelled.set(true)
                operation.terrain?.cancel(false)
            }
        }
        val plans = mutableListOf<Map<String, Any?>>()
        val requests = captured.buildings.map { it.id to
            if (it.id == captured.construction?.id) "current-construction" else "retained-building" } +
            listOfNotNull(captured.construction?.id?.takeUnless { id -> captured.buildings.any { it.id == id } }
                ?.let { it to "current-construction" })
        // ponytail: one journal at a time; retaining every full plan can consume hundreds of MiB.
        var chain = CompletableFuture.completedFuture(Unit)
        requests.forEach { (id, source) ->
            chain = chain.thenComposeAsync({
                operation.check()
                loadPlan(id).handleAsync({ plan, failure ->
                    operation.check()
                    plans += if (failure == null) planRow(captured, id, source, plan)
                    else mapOf("requested_id" to id, "source" to source, "status" to "unreadable",
                        "error" to (CivsDiagnostics.rejection(failure)?.reason ?: "plan-read-failed"))
                    Unit
                }, executor)
            }, executor)
        }
        val prepared = chain.thenCombineAsync(surface, { _, field ->
            operation.check()
            val terrainCompleted = clock()
            val terrainRow = field?.let { surfaceRow(it, started, terrainCompleted) }
            val rows = document(captured, worldName, pluginVersion, started, plans.toList(), terrainRow, runtime)
            val bytes = gson.toJson(rows).toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_BYTES) throw CivsRejection("layout-export-too-large")
            operation.check()
            val relative = Path.of("debug", "layout", "${captured.id}.json")
            prepareDirectory(root)
            val file = AtomicFileStore(root, relative, MAX_BYTES.toLong(), { value: ByteArray -> value }, { it })
            file.write(bytes)
            cleanup(file.path.parent, captured.id)
            mapOf<String, Any?>("export_schema" to SCHEMA, "city" to captured.id, "path" to relative.toString(),
                "sha256" to MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
                "bytes" to bytes.size, "records" to mapOf("buildings" to captured.buildings.size,
                    "workers" to captured.workers.size, "plans" to plans.size, "surface_columns" to (field?.columns?.size ?: 0)),
                "source_revision" to captured.revision, "capture_started_at_ms" to started,
                "capture_completed_at_ms" to terrainCompleted)
        }, executor).thenCompose { receipt ->
            tasks.callSync {
                operation.check()
                val latest = current()
                receipt + mapOf("completed_at_ms" to clock(), "completion_revision" to latest?.revision,
                    "layout_unchanged" to (latest != null && sameLayout(captured, latest)))
            }
        }
        operation.completion = prepared
        prepared.whenComplete { receipt, failure ->
            synchronized(monitor) { pending.remove(city.id, operation) }
            if (failure == null) operation.result.complete(receipt) else operation.result.completeExceptionally(failure)
        }
        return operation.result
    }

    override fun close() {
        val accepted = synchronized(monitor) {
            if (closed) return
            closed = true
            pending.values.toList().also { operations ->
                drain = CompletableFuture.allOf(*operations.map { it.completion.handle { _, _ -> Unit } }.toTypedArray())
            }
        }
        accepted.forEach {
            it.cancelled.set(true)
            it.terrain?.cancel(false)
            it.result.completeExceptionally(CivsRejection("layout-export-closed"))
        }
    }

    fun drained(): CompletableFuture<Void> = synchronized(monitor) { drain.copy() }

    companion object {
        const val SCHEMA = 1
        const val MAX_BYTES = 16 * 1024 * 1024
        private const val MAX_PENDING = 2
        private const val MAX_FILES = 32
        private const val USAGE = "civs debug layout <city UUID> [terrain]"
        private val gson = GsonBuilder().serializeNulls().create()

        fun request(args: List<String>, export: (UUID, Boolean) -> CompletableFuture<Map<String, Any?>>): CompletableFuture<Map<String, Any?>> {
            if (args.size !in 1..2 || args.getOrNull(1)?.let { it != "terrain" } == true)
                return CompletableFuture.failedFuture(CivsRejection("invalid-layout-arguments"))
            val id = runCatching { UUID.fromString(args[0]) }.getOrNull()
                ?.takeIf { it.toString().equals(args[0], true) }
                ?: return CompletableFuture.failedFuture(CivsRejection("invalid-city-id"))
            return runCatching { export(id, args.size == 2) }.getOrElse { CompletableFuture.failedFuture(it) }
        }

        fun line(receipt: Map<String, Any?>? = null, failure: Throwable? = null): String =
            "ARCCIVS_DEBUG " + gson.toJson(linkedMapOf<String, Any?>("schema" to 1, "system" to "layout",
                "status" to if (failure == null) "completed" else "failed",
                "error" to failure?.let { CivsDiagnostics.rejection(it)?.reason ?: "layout-export-failed" },
                "usage" to USAGE) + receipt.orEmpty())

        private fun snapshot(city: Settlement): Settlement = city.copy(
            buildings = city.buildings.map { it.copy(transit = it.transit?.toList()) },
            workers = city.workers.map { it.copy(skills = null, inventory = null, items = null, cargo = null, parentIds = null) },
            construction = city.construction?.let { order -> order.copy(
                plannedBuilding = order.plannedBuilding?.let { it.copy(transit = it.transit?.toList()) },
                materials = order.materials?.toMap(), payment = order.payment?.let { it.copy(awaiting = it.awaiting?.toMap()) }) },
            constructionQueue = city.constructionQueue?.toList())

        private fun detached(value: Any?): Any? = when (value) {
            is Map<*, *> -> value.entries.associate { it.key to detached(it.value) }
            is Collection<*> -> value.map(::detached)
            else -> value // Existing debug leaves are scalars and immutable position/snapshot records.
        }

        internal fun sameLayout(before: Settlement, after: Settlement): Boolean =
            before.id == after.id && before.worldId == after.worldId && before.center == after.center &&
                before.radius == after.radius && before.style == after.style && before.buildings == after.buildings &&
                before.autonomy?.layoutHalfWidth == after.autonomy?.layoutHalfWidth &&
                before.construction?.plannedBuilding == after.construction?.plannedBuilding &&
                before.constructionQueue.orEmpty() == after.constructionQueue.orEmpty()

        internal fun document(city: Settlement, worldName: String?, pluginVersion: String, capturedAt: Long,
            plans: List<Map<String, Any?>>, surface: Map<String, Any?>?, runtime: Map<String, Any?> = emptyMap()): Map<String, Any?> = linkedMapOf(
            "schema" to SCHEMA, "system" to "layout", "plugin_version" to pluginVersion,
            "source_revision" to city.revision, "captured_at_ms" to capturedAt,
            "evidence" to mapOf("layout" to "immutable-city-record", "frame" to "derived-layout-reservations",
                "saved_plans" to "journal-after-states-not-native-observation", "native_connectivity_proven" to false,
                "runtime" to "bounded-owner-debug-captured-on-main",
                "surface" to if (surface == null) "not-requested" else "loaded-only-non-atomic-native-capture"),
            "city" to mapOf("id" to city.id, "name" to city.name, "owner" to city.owner, "world" to city.worldId,
                "world_name" to worldName, "center" to city.center, "radius" to city.radius, "style" to city.style,
                "revision" to city.revision, "autonomy" to city.autonomy),
            "frame" to CivsSettlementLayout.frame(city),
            "buildings" to city.buildings.map { buildingRow(city, it) },
            "workers" to city.workers.map { mapOf("id" to it.id, "name" to it.name, "assignment" to it.assignment, "home" to it.homeId) },
            "construction" to city.construction, "construction_queue" to city.constructionQueue.orEmpty(),
            "runtime" to runtime,
            "placed_infrastructure" to city.buildings.filter { it.type.isInfrastructure && it.phase == BuildingPhase.ACTIVE }.map {
                mapOf("id" to it.id, "type" to it.type, "bounds" to it.bounds, "entrance" to it.entrance,
                    "transit" to it.transit.orEmpty(), "evidence" to "active-city-record-not-native-revalidated")
            },
            "saved_plans" to plans,
            "missing_plan_ids" to plans.filter { it["status"] == "missing" }.map { it["requested_id"] },
            "unvalidated_plan_ids" to plans.filter { it["status"] != "validated" && it["status"] != "missing" }.map { it["requested_id"] },
            "access_routes" to plans.mapNotNull { plan -> (plan["validated_route"] as? List<*>)?.let {
                mapOf("plan" to plan["id"], "building" to plan["building"], "positions" to it,
                    "evidence" to "validated-retained-journal-not-native-connectivity")
            } },
            "surface" to surface,
        )

        private fun buildingRow(city: Settlement, building: CivBuilding): Map<String, Any?> {
            val future = runCatching {
                if (building.type.isInfrastructure) mapOf("status" to "maximum-level", "bounds" to building.bounds)
                else {
                    val target = building.copy(schematicId = CivsBlueprints.schematicId(city.style, building.type, building.type.maxLevel))
                    if (CivsWorksiteLayouts.site(target)?.has("schematicBounds") != true)
                        mapOf("status" to "unavailable", "reason" to "catalog-bounds-unavailable")
                    else mapOf("status" to "catalog-reservation", "level" to building.type.maxLevel,
                        "bounds" to CivsAutonomousGeometry.futureBounds(city, building), "includes_worksite" to true)
                }
            }.getOrElse { mapOf("status" to "unavailable", "reason" to "catalog-bounds-unavailable") }
            return mapOf("id" to building.id, "type" to building.type, "phase" to building.phase, "level" to building.level,
                "rotation" to building.rotation, "schematic" to building.schematicId, "anchor" to building.anchor,
                "entrance" to building.entrance, "control" to building.control, "actual_bounds" to building.bounds,
                "transit" to building.transit.orEmpty(), "production" to building.production, "future_upgrade" to future,
                "workers" to city.workers.filter { it.assignment == building.id }.map { it.id })
        }

        internal fun planRow(city: Settlement, requested: UUID, source: String, plan: CivPlacementPlan?): Map<String, Any?> {
            if (plan == null) return mapOf("requested_id" to requested, "source" to source, "status" to "missing")
            val building = city.building(if (source == "retained-building") requested else plan.building.id)
            val expected = if (source == "current-construction") city.construction?.plannedBuilding ?: building else building
            val identity = plan.id == requested && plan.cityId == city.id && plan.worldId == city.worldId && expected != null &&
                plan.building.id == expected.id && plan.building.type == expected.type && plan.building.anchor == expected.anchor &&
                plan.building.bounds == expected.bounds && plan.building.rotation == expected.rotation &&
                plan.building.entrance == expected.entrance
            val route = if (identity && source == "retained-building") CivAutonomousAccess.from(city, listOf(plan)).routes[building!!.id] else null
            val status = if (identity && source == "retained-building" && building?.phase == BuildingPhase.ACTIVE &&
                (plan.terrain?.route.isNullOrEmpty() || route != null)) "validated" else "unvalidated"
            return linkedMapOf("requested_id" to requested, "source" to source, "status" to status,
                "id" to plan.id, "city" to plan.cityId, "world" to plan.worldId, "building" to plan.building.id,
                "captured_sha256" to plan.capturedSha256, "blocks" to plan.blocks.size,
                "building_snapshot" to plan.building, "terrain_bounds" to plan.terrain?.bounds,
                "terrain_changed_cells" to plan.terrain?.blocks?.size, "terrain_ground_y" to plan.terrain?.groundY,
                "route_validation" to when { !identity -> "identity-mismatch"; source != "retained-building" -> "construction-draft";
                    building?.phase != BuildingPhase.ACTIVE -> "building-not-active"; plan.terrain?.route.isNullOrEmpty() -> "no-saved-route";
                    route == null -> "invalid-saved-route"; else -> "validated-retained-route" },
                "journal_route" to plan.terrain?.route.orEmpty(), "validated_route" to route)
        }

        internal fun surfaceRow(surface: CivTerrainSurface, started: Long, completed: Long): Map<String, Any?> {
            val columns = surface.columns.entries.sortedBy { it.key }.map { (key, column) ->
                mapOf("x" to (key shr 32).toInt(), "z" to key.toInt(), "ground_y" to column.groundY,
                    "top_y" to column.topY, "loaded" to column.loaded, "natural" to column.natural)
            }
            val loaded = surface.columns.values.count { it.loaded }
            if (loaded == 0) throw CivsRejection("terrain-chunks-unloaded")
            return mapOf("capture_started_at_ms" to started, "capture_completed_at_ms" to completed,
                "atomic" to false, "loaded_only" to true, "chunks_loaded_by_export" to 0,
                "min_height" to surface.minHeight, "max_height" to surface.maxHeight,
                "bounds" to mapOf("min_x" to surface.columns.keys.minOf { (it shr 32).toInt() },
                    "max_x" to surface.columns.keys.maxOf { (it shr 32).toInt() },
                    "min_z" to surface.columns.keys.minOf { it.toInt() }, "max_z" to surface.columns.keys.maxOf { it.toInt() }),
                "coverage" to mapOf("columns" to columns.size, "loaded" to loaded, "unloaded" to columns.size - loaded,
                    "natural" to surface.columns.values.count { it.loaded && it.natural }, "fraction_loaded" to loaded.toDouble() / columns.size),
                "columns" to columns)
        }

        private fun prepareDirectory(root: Path) {
            Files.createDirectories(root)
            var directory = root.toRealPath()
            for (name in listOf("debug", "layout")) {
                directory = directory.resolve(name)
                if (!Files.exists(directory, NOFOLLOW_LINKS)) Files.createDirectory(directory)
                if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || Files.isSymbolicLink(directory))
                    throw CivsRejection("layout-export-unsafe-path")
            }
        }

        private fun cleanup(directory: Path, current: UUID) {
            val files = mutableListOf<Path>()
            Files.newDirectoryStream(directory).use { entries ->
                var scanned = 0
                for (file in entries) {
                    require(++scanned <= 1024) { "Layout debug directory has too many entries" }
                    val id = file.fileName.toString().removeSuffix(".json")
                    if (file.fileName.toString() == "$id.json" && runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false) &&
                        Files.isRegularFile(file, NOFOLLOW_LINKS)) files.add(file)
                }
            }
            files.filter { it.fileName.toString() != "$current.json" }.sortedBy { Files.getLastModifiedTime(it, NOFOLLOW_LINKS).toMillis() }
                .take((files.size - MAX_FILES).coerceAtLeast(0)).forEach(Files::deleteIfExists)
        }
    }
}
