package ru.arc.civs

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.plugin.Plugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.chunk.PaperChunkKey
import ru.arc.paper.chunk.PaperChunkTicketLease
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.logging.Level
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class CivVillageSiteAdmission(val city: Settlement, val blueprint: CivBlueprint,
    val terrain: CivTerraformPlan?, val current: () -> Boolean)

internal data class CivVillageSiteReceipt(val city: UUID, val name: String, val style: String,
    val center: CivPosition, val score: Long, val plots: Int, val dryColumns: Int,
    val treeColumns: Int, val existing: Boolean, val fallbackStyles: Map<String, Int> = emptyMap())

internal data class CivVillageSitesResult(val operation: UUID, val reference: UUID, val world: UUID,
    val requested: Int, val created: List<CivVillageSiteReceipt>, val rejected: Map<String, Int>,
    val examined: Int, val complete: Boolean, val reason: String?)

internal data class CivVillageTerrainScore(val score: Long, val columns: Int, val dryColumns: Int,
    val connectedColumns: Int, val treeColumns: Int, val relief: Int, val groundY: Int) {
    fun rejection(full: Boolean): String? = when {
        columns == 0 || dryColumns.toLong() * 100 < columns.toLong() * 80 -> "village-wet-or-obstructed"
        connectedColumns.toLong() * 100 < columns.toLong() * 60 -> "village-disconnected"
        relief > 12 -> "village-steep"
        full && treeColumns < 8 -> "village-no-trees"
        else -> null
    }
}

/** Detached bounded policy. Natural block classification and final mutation authority stay with CivsWorld. */
internal object CivsVillageSiteScoring {
    const val MAXIMUM_CANDIDATES = 48
    const val MAXIMUM_SHORTLIST = 16
    const val SAMPLE_STEP = 56

    fun id(reference: UUID, prefix: String, ordinal: Int): UUID =
        UUID.nameUUIDFromBytes("arccivs-village/$reference/$prefix/$ordinal".toByteArray(Charsets.UTF_8))

    fun candidates(reference: Settlement, prefix: String): List<CivPosition> {
        val seed = id(reference.id, prefix, 0)
        val phase = ((seed.leastSignificantBits ushr 11).toDouble() / (1L shl 53).toDouble()) * PI * 2
        return (0 until MAXIMUM_CANDIDATES).map { index ->
            val ring = index / 8
            val angle = phase + (index % 8) * PI / 4 + (ring % 2) * PI / 8
            val distance = 768 + ring * 512
            CivPosition(Math.addExact(reference.center.x, (cos(angle) * distance / 16).roundToInt() * 16),
                reference.center.y, Math.addExact(reference.center.z, (sin(angle) * distance / 16).roundToInt() * 16))
        }
    }

    fun samples(center: CivPosition): List<CivPosition> = (-2..2).flatMap { x -> (-2..2).map { z ->
        center.copy(x = Math.addExact(center.x, x * SAMPLE_STEP), z = Math.addExact(center.z, z * SAMPLE_STEP))
    } }

    fun score(surface: CivTerrainSurface, step: Int = 1): CivVillageTerrainScore {
        require(step > 0)
        val dry = surface.columns.filterValues { it.loaded && it.natural }
        val heights = dry.values.map { it.groundY }.sorted()
        val relief = if (heights.isEmpty()) Int.MAX_VALUE else
            heights[(heights.lastIndex * 9) / 10] - heights[heights.lastIndex / 10]
        val remaining = dry.keys.toMutableSet()
        var connected = 0
        val queue = ArrayDeque<Long>()
        while (remaining.isNotEmpty()) {
            val first = remaining.first()
            remaining.remove(first)
            queue.add(first)
            var size = 0
            while (queue.isNotEmpty()) {
                val key = queue.removeFirst()
                size++
                val x = (key shr 32).toInt()
                val z = key.toInt()
                val y = dry.getValue(key).groundY
                for ((dx, dz) in listOf(step to 0, -step to 0, 0 to step, 0 to -step)) {
                    val neighbour = civsTerrainKey(x + dx, z + dz)
                    if (neighbour in remaining && abs(dry.getValue(neighbour).groundY.toLong() - y) <=
                        if (step == 1) 2 else 8) {
                        remaining.remove(neighbour)
                        queue.add(neighbour)
                    }
                }
            }
            connected = maxOf(connected, size)
        }
        val trees = dry.values.count { it.topY.toLong() - it.groundY >= 2 }
        val total = surface.columns.size
        val score = if (total == 0) Long.MIN_VALUE else
            dry.size * 1_000_000L / total + connected * 1_000_000L / total - relief.toLong() * 10_000 +
                minOf(trees, total / 10) * 100_000L / total
        return CivVillageTerrainScore(score, total, dry.size, connected, trees, relief,
            heights.getOrElse(heights.size / 2) { surface.minHeight })
    }

    fun preparedSurface(surface: CivTerrainSurface, hall: CivBuilding): CivTerrainSurface {
        val columns = LinkedHashMap(surface.columns)
        for (x in hall.bounds.min.x..hall.bounds.max.x) for (z in hall.bounds.min.z..hall.bounds.max.z) {
            val key = civsTerrainKey(x, z)
            columns[key]?.let { columns[key] = it.copy(groundY = hall.anchor.y, topY = hall.anchor.y, natural = true) }
        }
        val key = civsTerrainKey(hall.entrance.x, hall.entrance.z)
        columns[key]?.let { columns[key] = it.copy(groundY = hall.entrance.y - 1,
            topY = hall.entrance.y - 1, natural = true) }
        return surface.copy(columns = columns)
    }

    fun completeProfile(plan: CivSettlementPlan): Boolean {
        val actual = plan.plots.groupingBy { it.building.type }.eachCount()
        return CivsAutonomy.completeProfile.groupingBy { it }.eachCount().all { (type, count) ->
            (actual[type] ?: 0) >= count
        }
    }

    fun sameRequest(existing: Settlement, proposed: Settlement): Boolean = existing.id == proposed.id &&
        existing.owner == proposed.owner && existing.worldId == proposed.worldId && existing.name == proposed.name &&
        existing.style == proposed.style && existing.autonomy != null && existing.pendingDeletion == null
}

/**
 * One explicitly requested administrative search owns generation, detached planning and ordinary founding.
 * Its loader has two outstanding requests and two admissions per tick; shared Core leases are released in slices.
 * Cancellation stops new work; an already entered native generation or durable commit cannot be undone.
 */
internal class CivsVillageSites(
    private val plugin: Plugin,
    private val tasks: LifecycleTaskScope,
    private val settings: CivsSettings,
    private val blueprints: CivsBlueprints,
    private val world: CivsWorld,
    private val executor: Executor,
    private val cities: () -> Collection<Settlement>,
    private val ready: () -> Boolean,
    private val checkSpace: (UUID, CivPosition) -> Unit,
    private val retainLoaded: (PaperChunkKey) -> PaperChunkTicketLease,
    private val found: (CivVillageSiteAdmission) -> CompletableFuture<Settlement>,
    private val load: (World, Int, Int) -> CompletableFuture<Chunk?> = { target, x, z -> target.getChunkAtAsync(x, z, true) },
    private val nanoTime: () -> Long = System::nanoTime,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private data class Candidate(val point: CivPosition, val score: CivVillageTerrainScore)
    private data class LoadReply(val operation: UUID, val group: UUID, val key: PaperChunkKey,
        val present: Boolean, val failure: Throwable?)
    private class LoadGroup(val keys: List<PaperChunkKey>, val done: () -> Unit, val failed: (Throwable) -> Unit) {
        val id = UUID.randomUUID()
        var next = 0
        val pending = hashSetOf<PaperChunkKey>()
        var failure: Throwable? = null
    }
    private class Search(val reference: Settlement, val count: Int, val prefix: String, val forcedStyle: String?,
        val token: LifecycleTaskScope.Token) {
        val id = UUID.randomUUID()
        val result = CompletableFuture<CivVillageSitesResult>()
        val rejected = sortedMapOf<String, Int>()
        val created = sortedMapOf<Int, CivVillageSiteReceipt>()
        val selectedStyles = sortedMapOf<Int, String>()
        val templates = mutableMapOf<Int, Map<BuildingType, Pair<CivBuilding, CivBuilding>>>()
        val invalidStyles = mutableSetOf<String>()
        val shortlist = mutableListOf<Candidate>()
        val candidates = CivsVillageSiteScoring.candidates(reference, prefix)
        val leases = linkedMapOf<PaperChunkKey, PaperChunkTicketLease>()
        var phase = "templates"
        var examined = 0
        var nextCandidate = 0
        var nextShortlist = 0
        var requests = 0
        var ticks = 0
        var pending: CompletableFuture<*>? = null
        var group: LoadGroup? = null
        var afterRelease: (() -> Unit)? = null
        var finishing = false
        var founding = false
        var stopReason: String? = null
        var unexpectedLogged = false
        var releaseFailed = false
        var point: CivPosition? = null
        fun missing(): Int? = (1..count).firstOrNull { it !in created }
    }

    private val replies = ConcurrentLinkedQueue<LoadReply>()
    private var active: Search? = null
    private var last: CivVillageSitesResult? = null
    private var closed = false
    private val timer = tasks.runTimer(1, 1, ::pulse)

    fun supportedStyles(): List<String> {
        val preferred = listOf("nordic", "medievaloak", "birch", "original", "medievaldarkoak")
        return blueprints.availableStyles(BuildingType.TOWN_HALL).filter { style ->
            style !in setOf("underwater", "cavern", "spacewars") &&
                THEME_TYPES.all { style in blueprints.availableStyles(it) }
        }.sortedWith(compareBy<String> { preferred.indexOf(it).takeIf { index -> index >= 0 } ?: preferred.size }
            .thenBy { it })
    }

    fun search(reference: Settlement, count: Int, prefix: String, style: String? = null): CompletableFuture<CivVillageSitesResult> {
        check(Bukkit.isPrimaryThread())
        val cleanPrefix = prefix.trim()
        active?.let {
            if (!it.finishing && it.reference.id == reference.id && it.count == count && it.prefix == cleanPrefix &&
                it.forcedStyle == style) return it.result
            return CompletableFuture.failedFuture(CivsRejection("world-busy"))
        }
        if (closed || timer == null || !ready()) return CompletableFuture.failedFuture(CivsRejection("not-ready"))
        if (count !in 1..5 || cleanPrefix.isEmpty() || cleanPrefix.length > 40 || cleanPrefix.any(Char::isISOControl))
            return CompletableFuture.failedFuture(CivsRejection("invalid-name"))
        if (reference.autonomy == null || reference.pendingDeletion != null ||
            cities().none { it.id == reference.id && it.owner == reference.owner && it.worldId == reference.worldId })
            return CompletableFuture.failedFuture(CivsRejection("city-not-found"))
        if (plugin.server.getWorld(reference.worldId)?.name !in settings.worlds)
            return CompletableFuture.failedFuture(CivsRejection("world-not-allowed"))
        val search = Search(reference, count, cleanPrefix, style, tasks.token())
        active = search
        runCatching {
            for (ordinal in 1..count) {
                val id = CivsVillageSiteScoring.id(reference.id, cleanPrefix, ordinal)
                val name = "$cleanPrefix$ordinal"
                val existing = cities().firstOrNull { it.id == id }
                if (cities().any { it.id != id && it.name.equals(name, ignoreCase = true) }) throw CivsRejection("city-name-conflict")
                if (existing != null) {
                    if (existing.owner != reference.owner || existing.worldId != reference.worldId || existing.name != name ||
                        existing.autonomy == null || existing.pendingDeletion != null || style != null && existing.style != style)
                        throw CivsRejection("city-conflict")
                    search.created[ordinal] = receipt(existing, null, true)
                    search.selectedStyles[ordinal] = existing.style
                }
            }
            if (search.missing() == null) finish(search, null) else prepareStyle(search)
        }.onFailure { finish(search, rejection(it)) }
        return search.result
    }

    fun status(): Map<String, Any?> = active?.let { search -> mapOf(
        "active" to true, "operation" to search.id, "reference" to search.reference.id, "phase" to search.phase,
        "requested" to search.count, "created" to search.created.values.toList(), "styles" to search.selectedStyles,
        "examined" to search.examined, "candidates" to search.candidates.size, "shortlist" to search.shortlist.size,
        "shortlist_attempted" to search.nextShortlist, "point" to search.point, "chunk_requests" to search.requests,
        "pending_loads" to (search.group?.pending?.size ?: 0), "leases" to search.leases.size,
        "maximum_pending_loads" to 2, "maximum_chunk_requests" to MAXIMUM_CHUNK_REQUESTS,
        "rejected" to search.rejected.toMap(), "finishing" to search.finishing,
    ) } ?: mapOf("active" to false, "last" to last)

    fun cancel(reason: String = "cancelled") {
        check(Bukkit.isPrimaryThread())
        active?.let { finish(it, reason) }
    }

    private fun context(search: Search): Boolean = !closed && active === search && !search.finishing &&
        !search.result.isDone && tasks.isCurrent(search.token) && ready() && cities().any {
            it.id == search.reference.id && it.owner == search.reference.owner && it.worldId == search.reference.worldId &&
                it.pendingDeletion == null && it.autonomy != null
        }

    private fun prepareStyle(search: Search) {
        if (!context(search)) return finish(search, "not-ready")
        val ordinal = (1..search.count).firstOrNull { it !in search.created && it !in search.templates }
            ?: return coarse(search)
        val style = search.forcedStyle ?: supportedStyles().filter { it !in search.invalidStyles }
            .minByOrNull { candidate -> search.selectedStyles.values.count { it == candidate } }
            ?: return finish(search, "village-insufficient-styles")
        if (style in search.invalidStyles) return finish(search, "blueprint-unavailable")
        search.phase = "templates:$style"
        await(search, templates(search, style), failed = { failure ->
            search.invalidStyles += style
            reject(search, rejection(failure))
            prepareStyle(search)
        }) { value ->
            search.selectedStyles[ordinal] = style
            search.templates[ordinal] = value
            prepareStyle(search)
        }
    }

    private fun templates(search: Search, style: String): CompletableFuture<Map<BuildingType, Pair<CivBuilding, CivBuilding>>> {
        val requests = CivsAutonomy.profile.associateWith { type ->
            val id = UUID.nameUUIDFromBytes("arccivs-village-template/${search.id}/$style/$type".toByteArray(Charsets.UTF_8))
            val entry = blueprints.autonomousSource(style, type)
            val first = if (entry == null) blueprints.prepare(style, type, 1, id, search.reference.worldId,
                CivPosition(0, 0, 0), 2, 0) else blueprints.prepareEntry(entry, id, search.reference.worldId, CivPosition(0, 0, 0), 2, 0)
            first.thenCompose { initial ->
                val maximum = blueprints.maximumLevel(initial.building)
                val last = if (maximum == 1) CompletableFuture.completedFuture(initial)
                    else blueprints.prepareUpgrade(initial.building, maximum, search.reference.worldId)
                last.thenApply { initial.building to it.building }
            }
        }
        return CompletableFuture.allOf(*requests.values.toTypedArray()).thenApply {
            requests.mapValues { it.value.join() }.also { templates ->
                if (THEME_TYPES.any { CivsBlueprints.styleOf(templates.getValue(it).first) != style })
                    throw CivsRejection("village-theme-unavailable")
            }
        }
    }

    private fun draft(search: Search, ordinal: Int, anchor: CivPosition): Settlement {
        val id = CivsVillageSiteScoring.id(search.reference.id, search.prefix, ordinal)
        val hallId = UUID.nameUUIDFromBytes("arccivs-village-hall/$id".toByteArray(Charsets.UTF_8))
        val hall = CivsSettlementPlans.at(search.templates.getValue(ordinal).getValue(BuildingType.TOWN_HALL).first,
            anchor, 0, hallId)
        return CivsAutonomy.found(id, search.reference.owner, "${search.prefix}$ordinal", search.reference.worldId,
            hall.control ?: hall.entrance, settings.radius, search.selectedStyles.getValue(ordinal), hall, clock())
            .copy(settings = CivSettlementSettings(chunkLoading = true, immortalResidents = true, autoConstructionMaterials = false))
    }

    private fun coarse(search: Search) {
        if (!context(search)) return finish(search, "not-ready")
        val ordinal = search.missing() ?: return finish(search, null)
        while (search.nextCandidate < search.candidates.size) {
            val anchor = search.candidates[search.nextCandidate++]
            val city = draft(search, ordinal, anchor)
            search.point = city.center
            val failure = runCatching { checkSpace(city.worldId, city.center) }.exceptionOrNull()
            if (failure != null) { reject(search, rejection(failure)); continue }
            val points = CivsVillageSiteScoring.samples(city.center)
            search.phase = "coarse-loading"
            load(search, points.map { PaperChunkKey(city.worldId, it.x shr 4, it.z shr 4) }.distinct(),
                failed = { error -> reject(search, rejection(error)); release(search) { coarse(search) } }) {
                search.phase = "coarse-survey"
                await(search, world.prospectiveHeightField(city, city.buildings.single(), points), failed = { error ->
                    reject(search, rejection(error)); release(search) { coarse(search) }
                }) { surface ->
                    search.examined++
                    val score = CivsVillageSiteScoring.score(surface, CivsVillageSiteScoring.SAMPLE_STEP)
                    val refused = score.rejection(full = false)
                    if (refused != null) reject(search, refused) else {
                        search.shortlist += Candidate(anchor.copy(y = score.groundY), score)
                        search.shortlist.sortByDescending { it.score.score }
                        while (search.shortlist.size > CivsVillageSiteScoring.MAXIMUM_SHORTLIST) search.shortlist.removeLast()
                    }
                    release(search) { coarse(search) }
                }
            }
            return
        }
        full(search)
    }

    private fun full(search: Search) {
        if (!context(search)) return finish(search, "not-ready")
        val ordinal = search.missing() ?: return finish(search, null)
        while (search.nextShortlist < search.shortlist.size) {
            val candidate = search.shortlist[search.nextShortlist++]
            val city = draft(search, ordinal, candidate.point)
            search.point = city.center
            val failure = runCatching { checkSpace(city.worldId, city.center) }.exceptionOrNull()
            if (failure != null) { reject(search, rejection(failure)); continue }
            search.phase = "growth-area-loading"
            load(search, CivsChunkArea.of(city).keys().toList(), failed = { error ->
                reject(search, rejection(error)); release(search) { full(search) }
            }) {
                search.phase = "growth-area-survey"
                await(search, world.prospectiveHeightField(city, city.buildings.single()), failed = { error ->
                    reject(search, rejection(error)); release(search) { full(search) }
                }) { surface -> prove(search, ordinal, city, surface) }
            }
            return
        }
        finish(search, "autonomy-no-site")
    }

    private fun prove(search: Search, ordinal: Int, city: Settlement, surface: CivTerrainSurface) {
        search.phase = "growth-area-ranking"
        await(search, CompletableFuture.supplyAsync({ CivsVillageSiteScoring.score(surface) }, executor), failed = {
            finish(search, rejection(it))
        }) { score ->
            score.rejection(full = true)?.let { reject(search, it); release(search) { full(search) }; return@await }
            val hall = city.buildings.single()
            val prepared = if (hall.schematicId.startsWith("civs/v2/")) blueprints.prepareEntry(hall.schematicId,
                hall.id, city.worldId, hall.anchor, hall.rotation / 90, hall.rotation)
                else blueprints.prepare(city.style, BuildingType.TOWN_HALL, 1, hall.id, city.worldId, hall.anchor,
                    hall.rotation / 90, hall.rotation)
            search.phase = "town-hall-foundation"
            await(search, prepared, failed = { finish(search, rejection(it)) }) { blueprint ->
                if (blueprint.worldId != city.worldId || blueprint.building.copy(phase = hall.phase) != hall)
                    throw CivsRejection("blueprint-changed")
                await(search, world.captureManualFoundation(city.id, city.owner, city.worldId, blueprint.building,
                    blueprint.cells, executor, autonomous = true, adminPlacement = false), failed = { error ->
                    reject(search, rejection(error)); release(search) { full(search) }
                }) { terrain ->
                    search.phase = "complete-layout-proof"
                    val templates = search.templates.getValue(ordinal)
                    val proof = blueprints.fortification(city.style).thenApplyAsync({ fortification ->
                        val plan = CivsSettlementPlans.prepare(city, templates,
                            CivsVillageSiteScoring.preparedSurface(surface, blueprint.building), CivAutonomousAccess(),
                            fortification = fortification)
                        if (!CivsVillageSiteScoring.completeProfile(plan)) throw CivsRejection("autonomy-no-site")
                        plan
                    }, executor)
                    await(search, proof, failed = { error ->
                        reject(search, rejection(error)); release(search) { full(search) }
                    }) { plan ->
                        search.phase = "founding"
                        val planned = CivsRules.validate(city.copy(autonomy = city.autonomy!!.copy(plan = plan,
                            layoutHalfWidth = plan.halfWidth)))
                        found(search, ordinal, planned, blueprint, terrain, score)
                    }
                }
            }
        }
    }

    private fun found(search: Search, ordinal: Int, city: Settlement, blueprint: CivBlueprint,
        terrain: CivTerraformPlan?, score: CivVillageTerrainScore) {
        search.founding = true
        val future = runCatching { found(CivVillageSiteAdmission(city, blueprint, terrain) { context(search) }) }
            .getOrElse { search.founding = false; finish(search, rejection(it)); return }
        search.pending = future
        // A cancellation fences the final commit through admission.current, then waits for any entered commit.
        // Its canonical publication/control recovery must finish before temporary chunks are handed back.
        future.whenComplete { saved, failure ->
            tasks.callSync(search.token) {
                search.founding = false
                if (search.pending === future) search.pending = null
                val committed = saved ?: cities().firstOrNull { it.id == city.id }
                if (committed != null && CivsVillageSiteScoring.sameRequest(committed, city))
                    search.created[ordinal] = receipt(committed, score, false)
                if (active !== search) return@callSync
                if (search.finishing) release(search) { complete(search, search.stopReason) }
                else if (failure != null) finish(search, rejection(failure))
                else if (!context(search)) finish(search, "not-ready")
                else release(search) { full(search) }
            }
        }
    }

    private fun load(search: Search, keys: List<PaperChunkKey>, failed: (Throwable) -> Unit, done: () -> Unit) {
        check(search.group == null && search.leases.isEmpty())
        search.group = LoadGroup(keys, done, failed)
    }

    private fun pulse() {
        val search = active ?: return
        if (search.afterRelease != null) {
            val started = nanoTime()
            repeat(8) {
                if (nanoTime() - started >= 1_000_000L) return@repeat
                val entry = search.leases.entries.firstOrNull() ?: return@repeat
                search.leases.remove(entry.key)
                runCatching { entry.value.close() }.onFailure {
                    search.releaseFailed = true
                    plugin.logger.log(Level.WARNING, "ArcCivs automatic village search could not release ${entry.key}", it)
                }
            }
            if (search.leases.isEmpty()) {
                val continuation = search.afterRelease
                search.afterRelease = null
                if (search.releaseFailed && !search.finishing) finish(search, "chunk-release-failed") else continuation?.invoke()
            }
            return
        }
        if (!context(search)) return finish(search, "not-ready")
        if (++search.ticks > MAXIMUM_TICKS) return finish(search, "village-search-timeout")
        val group = search.group ?: return
        val target = plugin.server.getWorld(search.reference.worldId) ?: return finish(search, "world-unavailable")
        val started = nanoTime()
        var admissions = 0
        while (admissions < 2 && nanoTime() - started < 1_000_000L) {
            val reply = replies.poll() ?: break
            if (reply.operation != search.id || reply.group != group.id || !group.pending.remove(reply.key)) continue
            if (!reply.present || reply.failure != null) group.failure = reply.failure ?: CivsRejection("chunk-unavailable")
            else if (!target.isChunkLoaded(reply.key.x, reply.key.z)) group.failure = CivsRejection("chunks-unloaded")
            else runCatching { retainLoaded(reply.key) }.onSuccess {
                search.leases[reply.key] = it
                admissions++
            }.onFailure { group.failure = it }
        }
        while (group.failure == null && group.next < group.keys.size && group.pending.size < 2 && admissions < 2 &&
            nanoTime() - started < 1_000_000L) {
            if (search.requests >= MAXIMUM_CHUNK_REQUESTS) { group.failure = CivsRejection("village-chunk-budget"); break }
            val key = group.keys[group.next++]
            search.requests++
            admissions++
            if (target.isChunkLoaded(key.x, key.z)) runCatching { retainLoaded(key) }
                .onSuccess { search.leases[key] = it }.onFailure { group.failure = it }
            else {
                group.pending += key
                runCatching { load(target, key.x, key.z) }.getOrElse { CompletableFuture.failedFuture(it) }
                    .whenComplete { chunk, failure ->
                        if (!closed) replies += LoadReply(search.id, group.id, key, chunk != null, failure)
                    }
            }
        }
        if (group.pending.isEmpty() && (group.failure != null || group.next == group.keys.size)) {
            search.group = null
            val failure = group.failure
            runCatching { if (failure != null) group.failed(failure) else group.done() }
                .onFailure { finish(search, rejection(it)) }
        }
    }

    private fun <T> await(search: Search, future: CompletableFuture<T>, failed: (Throwable) -> Unit, done: (T) -> Unit) {
        search.pending = future
        future.whenComplete { value, failure ->
            tasks.callSync(search.token) {
                if (search.pending === future) search.pending = null
                if (context(search)) runCatching {
                    if (failure == null) done(value) else failed(failure)
                }.onFailure { finish(search, rejection(it)) }
            }
        }
    }

    private fun release(search: Search, done: () -> Unit) {
        search.phase = "releasing"
        search.afterRelease = done
    }

    private fun reject(search: Search, reason: String) { search.rejected.merge(reason, 1, Int::plus) }
    private fun rejection(failure: Throwable): String {
        CivsDiagnostics.rejection(failure)?.let { return it.reason }
        active?.let { search -> if (!search.unexpectedLogged) {
            search.unexpectedLogged = true
            plugin.logger.log(Level.WARNING, "ArcCivs automatic village search failed operation=${search.id} " +
                "phase=${search.phase} point=${search.point}", failure)
        } }
        return "village-search-failed"
    }

    private fun receipt(city: Settlement, score: CivVillageTerrainScore?, existing: Boolean) = CivVillageSiteReceipt(
        city.id, city.name, city.style, city.center, score?.score ?: 0,
        CivsSettlementPlans.current(city)?.plots?.count { it.building.type != BuildingType.DECORATION } ?: city.buildings.size,
        score?.dryColumns ?: 0, score?.treeColumns ?: 0, existing,
        CivsSettlementPlans.current(city)?.plots.orEmpty().mapNotNull { CivsBlueprints.styleOf(it.building) }
            .filter { it != city.style }.groupingBy { it }.eachCount())

    private fun finish(search: Search, reason: String?) {
        if (active !== search || search.finishing) return
        search.finishing = true
        search.stopReason = reason
        search.group = null
        if (search.founding) {
            search.phase = "cancelling-founding"
            return
        }
        search.pending?.cancel(false)
        search.pending = null
        release(search) { complete(search, reason) }
    }

    private fun complete(search: Search, reason: String?) {
        for (ordinal in 1..search.count) if (ordinal !in search.created) {
            val id = CivsVillageSiteScoring.id(search.reference.id, search.prefix, ordinal)
            cities().firstOrNull { it.id == id && it.owner == search.reference.owner &&
                it.worldId == search.reference.worldId && it.autonomy != null && it.pendingDeletion == null }?.let {
                search.created[ordinal] = receipt(it, null, true)
            }
        }
        val result = CivVillageSitesResult(search.id, search.reference.id, search.reference.worldId, search.count,
            search.created.values.toList(), search.rejected.toMap(), search.examined,
            search.created.size == search.count, if (search.releaseFailed) "chunk-release-failed" else reason)
        last = result
        if (active === search) active = null
        replies.clear()
        search.result.complete(result)
    }

    override fun close() {
        if (closed) return
        check(Bukkit.isPrimaryThread())
        closed = true
        timer?.cancel()
        active?.let { search ->
            search.finishing = true
            search.pending?.cancel(false)
            search.group = null
            search.leases.values.forEach { lease -> runCatching { lease.close() }.onFailure {
                search.releaseFailed = true
                plugin.logger.log(Level.WARNING, "ArcCivs automatic village search shutdown could not release ${lease.key}", it)
            } }
            search.leases.clear()
            complete(search, "closed")
        }
        replies.clear()
    }

    companion object {
        const val MAXIMUM_CHUNK_REQUESTS = 8_192
        const val MAXIMUM_TICKS = 36_000
        private val THEME_TYPES = listOf(BuildingType.TOWN_HALL, BuildingType.HOUSE, BuildingType.BUILDER_HUT,
            BuildingType.FORESTER, BuildingType.FARM)
    }
}
