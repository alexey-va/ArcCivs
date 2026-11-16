package ru.arc.civs

import com.google.gson.GsonBuilder
import org.bukkit.Bukkit
import ru.arc.persistence.AtomicFileStore
import java.io.Writer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import kotlin.math.floor

/** One operator-selected intent. Neither actor/native objects nor the complete Settlement enter the capture. */
internal data class CitizenNavigationDebugIdentity(val city: UUID, val worker: UUID, val actor: UUID,
    val world: UUID, val token: CitizenToken, val goal: CivPosition)

internal data class CitizenNavigationDebugArm(val request: UUID, val identity: CitizenNavigationDebugIdentity,
    val expiresAtMillis: Long, val completion: CompletableFuture<Map<String, Any?>>) {
    fun receipt(): Map<String, Any?> = mapOf("schema" to 1, "state" to "armed", "request" to request,
        "city" to identity.city, "worker" to identity.worker, "actor" to identity.actor, "world" to identity.world,
        "cycle" to identity.token.completedCycles, "shipment" to identity.token.shipmentId,
        "goal" to identity.goal, "expires_at_ms" to expiresAtMillis, "next_request_only" to true)
}

/** Whitelisted immutable policy values; the writer derives field columns off Main. */
internal data class CitizenNavigationDebugBuilding(val id: UUID, val type: BuildingType, val level: Int,
    val anchor: CivPosition, val rotation: Int, val schematic: String, val bounds: CivBox,
    val entrance: CivPosition, val phase: BuildingPhase, val control: CivPosition?,
    val transit: List<CivPosition>, val farmFieldVersion: Int?) {
    fun building() = CivBuilding(id, type, level, anchor, rotation, schematic, bounds, entrance, phase,
        control = control, transit = transit, farmFieldVersion = farmFieldVersion)

    companion object {
        fun of(value: CivBuilding) = CitizenNavigationDebugBuilding(value.id, value.type, value.level,
            value.anchor, value.rotation, value.schematicId, value.bounds, value.entrance, value.phase,
            value.control, value.transit.orEmpty().toList(), value.farmFieldVersion)
    }
}

internal data class CitizenNavigationDebugPolicy(val center: CivPosition, val radius: Int,
    val buildings: List<CitizenNavigationDebugBuilding>, val allowedBuildings: Set<UUID>,
    val preferred: Set<CivPosition>, val retainedSupports: Set<CivPosition>, val preferCourierRoads: Boolean,
    val canopyMaterials: Set<String>)

internal data class CitizenNavigationDebugContext(val identity: CitizenNavigationDebugIdentity,
    val cityRevision: Long, val pluginVersion: String, val origin: CitizenPoint, val target: CitizenPoint,
    val startedAtMillis: Long, val policy: CitizenNavigationDebugPolicy)

/** The exact input and output of one completed advance call; the initial input is empty. */
internal data class CitizenNavigationDebugRound(val captures: List<CitizenSearchCapture>,
    val probes: List<CitizenSearchProbe>, val nodes: Int)

internal data class CitizenNavigationDebugCursor(val required: Int, val next: Int,
    val probes: List<CitizenSearchProbe>)

/** Ownership transfers only after the selected worker/capture is terminal; these maps are never changed again. */
internal data class CitizenNavigationDebugFrame(val request: UUID, val generation: UUID, val context: CitizenNavigationDebugContext,
    val settings: CivsNavigationSettings, val minimumY: Int, val maximumY: Int,
    val rounds: List<CitizenNavigationDebugRound>, val terminal: CitizenSearchResult,
    val budgetCall: String?, val reportedBlocks: Int, val terrain: CitizenSnapshotTerrain,
    val cursor: CitizenNavigationDebugCursor?, val capturedAtMillis: Long)

/** One bounded private artifact on the existing storage executor, never a console-sized terrain dump. */
internal class CivsNavigationDebugFile(private val root: Path, private val executor: Executor,
    private val maximumBytes: Int = MAX_BYTES) {
    fun save(frame: CitizenNavigationDebugFrame): CompletableFuture<Map<String, Any?>> = try {
        CompletableFuture.supplyAsync({
            check(!Bukkit.isPrimaryThread()) { "Navigation debug serialization and IO must run off Main" }
            val document = document(frame)
            val text = BoundedWriter(maximumBytes).also { gson.toJson(document, it) }.toString()
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size > maximumBytes) throw CivsRejection("navigation-file-too-large")
            prepareDirectory()
            AtomicFileStore(root, FILE, maximumBytes.toLong(), { value: ByteArray -> value }, { it }).write(bytes)
            mapOf<String, Any?>("schema" to 1, "state" to "saved", "request" to frame.request,
                "city" to frame.context.identity.city, "worker" to frame.context.identity.worker,
                "cycle" to frame.context.identity.token.completedCycles, "goal" to frame.context.identity.goal,
                "path" to FILE.toString(), "bytes" to bytes.size,
                "sha256" to sha256(bytes), "reported_blocks" to frame.reportedBlocks,
                "actual_blocks" to frame.terrain.blocks.size, "nodes" to frame.terminal.nodes)
        }, executor)
    } catch (failure: Throwable) { CompletableFuture.failedFuture(failure) }

    private fun document(frame: CitizenNavigationDebugFrame): Map<String, Any?> {
        if (frame.rounds.isEmpty() || frame.rounds.size > MAX_ROUNDS ||
            frame.terrain.blocks.size > minOf(frame.settings.maximumCapturedBlocks, MAX_CELLS) ||
            frame.terminal.failure !in setOf("capture-budget", "search-budget") ||
            frame.terminal.path != null || frame.terminal.continuation)
            throw CivsRejection("unsupported-navigation-capture")
        var probeCount = 0
        val palette = linkedMapOf<CitizenTerrainBlock, Int>()
        fun block(value: CitizenTerrainBlock): Int {
            if (value.cost.isNaN() || value.cost == Double.NEGATIVE_INFINITY ||
                value.boxes.any { box -> listOf(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ).any { !it.isFinite() } })
                throw CivsRejection("invalid-navigation-capture")
            return palette.getOrPut(value) { palette.size }
        }
        fun probe(value: CitizenSearchProbe): Map<String, Any?> {
            if (++probeCount > MAX_PROBES || value.corridor != null)
                throw CivsRejection("unsupported-navigation-probes")
            return mapOf("node" to value.node, "from" to value.from, "incoming" to value.incoming,
                "initial_bounds" to (value.bounds != null))
        }
        fun capture(value: CitizenSearchCapture): Map<String, Any?> {
            val node = value.probe.node
            val y = floor(node.y).toInt()
            if (value.blocks.size != 63) throw CivsRejection("unsupported-navigation-neighbourhood")
            val cells = buildList {
                for (x in node.x - 1..node.x + 1) for (z in node.z - 1..node.z + 1) for (by in y - 3..y + 3)
                    add(block(value.blocks[CivPosition(x, by, z)] ?: throw CivsRejection("incomplete-navigation-neighbourhood")))
            }
            return mapOf("probe" to probe(value.probe), "cells" to cells, "edges" to value.edges)
        }
        val rounds = frame.rounds.map { round -> mapOf("captures" to round.captures.map(::capture),
            "probes" to round.probes.map(::probe), "nodes" to round.nodes) }
        val cells = frame.terrain.blocks.entries.map { (point, value) ->
            listOf(point.x, point.y, point.z, block(value)) }
        val resolvedCosts = frame.terrain.costs.map { (point, cost) ->
            if (cost.isNaN() || cost == Double.NEGATIVE_INFINITY) throw CivsRejection("invalid-navigation-cost")
            mapOf("point" to point, "cost" to cost.takeIf(Double::isFinite)) }
        val cursor = frame.cursor?.let { value ->
            if (value.required !in 0..value.probes.size || value.next !in 0..value.probes.size)
                throw CivsRejection("invalid-navigation-cursor")
            mapOf("required" to value.required, "next" to value.next, "probes" to value.probes.map(::probe)) }
        val policy = frame.context.policy
        if (policy.buildings.size > CivsRules.MAX_SITES || policy.preferred.size > MAX_CELLS ||
            policy.retainedSupports.size > MAX_CELLS) throw CivsRejection("navigation-policy-too-large")
        var fieldArea = 0L
        val fields = policy.buildings.mapNotNull { entry ->
            val building = entry.building()
            val bounds = CivsWorksiteLayouts.fieldBounds(building) ?: return@mapNotNull null
            fieldArea += (bounds.max.x.toLong() - bounds.min.x + 1) * (bounds.max.z.toLong() - bounds.min.z + 1)
            if (fieldArea > MAX_CELLS)
                throw CivsRejection("navigation-field-too-large")
            val columns = buildList {
                for (x in bounds.min.x..bounds.max.x) for (z in bounds.min.z..bounds.max.z)
                    if (CivsWorksiteLayouts.isField(building, x, z)) add(listOf(x, z))
            }
            mapOf("building" to entry.id, "columns" to columns)
        }
        val policyData = mapOf("geometry" to policy, "field_columns" to fields,
            "step_policy" to "CitizenRouteControls.stepClear", "cell_policy" to "CivRouteTerritory.allows+CitizenRouteControls.clear",
            "cost_policy" to "CivsPaths.Weights.detached", "mine_bottom" to null,
            "autonomous_soil" to CivsTerrainPolicy.AUTONOMOUS_SOIL.map { it.name }.sorted(),
            "ordinary_cost" to CivsPaths.ORDINARY_COST, "observed_corridor_cost" to 1.5, "recognized_surface_provider" to false)
        val costsKnown = frame.terrain.costs.keys
        // BlockData is from already detached, already decoded snapshot sections. No World or native capture is accessed.
        val states = frame.terrain.blocks.keys.mapNotNull { point ->
            val chunk = frame.terrain.chunks[CitizenSnapshotKey(point.x shr 4, point.z shr 4)] ?: return@mapNotNull null
            if (point.y !in frame.minimumY until frame.maximumY) return@mapNotNull null
            mapOf("point" to point, "block_data" to chunk.data(point).asString,
                "support_cost_resolved" to (point in costsKnown))
        }
        return linkedMapOf("schema" to 1, "source" to "selected-detached-failed-search", "request" to frame.request,
            "navigation_owner" to frame.generation, "context" to mapOf("identity" to frame.context.identity,
                "city_revision" to frame.context.cityRevision, "plugin_version" to frame.context.pluginVersion,
                "origin" to frame.context.origin, "target" to frame.context.target, "started_at_ms" to frame.context.startedAtMillis),
            "settings" to frame.settings, "avoiding" to emptyList<Any>(), "preferred_corridor" to null, "minimum_y" to frame.minimumY, "maximum_y" to frame.maximumY,
            "captured_at_ms" to frame.capturedAtMillis, "native_reads_added" to 0,
            "reported_blocks" to frame.reportedBlocks, "actual_blocks" to frame.terrain.blocks.size,
            "terminal" to mapOf("failure" to frame.terminal.failure, "nodes" to frame.terminal.nodes,
                "path" to frame.terminal.path, "continuation" to frame.terminal.continuation,
                "continuation_reason" to frame.terminal.continuationReason, "budget_call" to frame.budgetCall,
                "blocked_positions" to frame.terminal.blockedPositions, "search_nanos" to frame.terminal.searchNanos),
            "chunk_generations" to frame.terrain.chunks.map { (key, chunk) -> mapOf("chunk" to key, "revision" to chunk.revision) },
            "unavailable_chunks" to frame.terrain.unavailable,
            "sections" to frame.terrain.sections.map { (section, revision) -> mapOf("section" to section, "revision" to revision) },
            "policy" to policyData, "policy_sha256" to sha256(gson.toJson(policyData).toByteArray(Charsets.UTF_8)),
            "palette" to palette.keys.map { value -> mapOf("loaded" to value.loaded, "liquid" to value.liquid,
                "door" to value.door, "climbable" to value.climbable, "hot" to value.hot, "allowed" to value.allowed,
                "cost" to value.cost.takeIf(Double::isFinite), "boxes" to value.boxes) },
            "cells" to cells, "resolved_costs" to resolvedCosts, "block_states" to states,
            "rounds" to rounds, "pending_cursor" to cursor,
            "cell_order" to "x,z,y; each capture has x/z +/-1 and floor(y) +/-3",
            "missing_cell" to "not-captured; never air or native-unloaded", "null_cost" to "positive-infinity",
            "unresolved_cost" to "absent from resolved_costs; raw cell cost is not a support-cost proof")
    }

    private fun prepareDirectory() {
        Files.createDirectories(root)
        var directory = root.toRealPath()
        for (name in listOf("debug", "navigation")) {
            directory = directory.resolve(name)
            if (!Files.exists(directory, NOFOLLOW_LINKS)) Files.createDirectory(directory)
            if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || Files.isSymbolicLink(directory))
                throw CivsRejection("navigation-file-unsafe-path")
        }
    }

    private class BoundedWriter(private val maximum: Int) : Writer() {
        private val value = StringBuilder()
        override fun write(chars: CharArray, offset: Int, length: Int) {
            if (length > maximum - value.length) throw CivsRejection("navigation-file-too-large")
            value.append(chars, offset, length)
        }
        override fun write(text: String, offset: Int, length: Int) {
            if (length > maximum - value.length) throw CivsRejection("navigation-file-too-large")
            value.append(text, offset, offset + length)
        }
        override fun flush() = Unit
        override fun close() = Unit
        override fun toString(): String = value.toString()
    }

    companion object {
        const val MAX_CELLS = 32_768
        const val MAX_ROUNDS = 4097
        const val MAX_PROBES = 65_536
        private const val MAX_BYTES = 16 * 1024 * 1024
        private val FILE = Path.of("debug", "navigation", "latest.json")
        private val gson = GsonBuilder().serializeNulls().create()
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
