package ru.arc.civs

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class CivAutonomousDesign(val blueprint: CivBlueprint, val terrain: CivTerraformPlan? = null)

/** Detached earthworks selection; the service owns durable SITE admission and construction. */
internal class CivsAutonomousPlacement(
    private val blueprints: CivsBlueprints,
    private val world: CivsWorld,
    private val executor: Executor,
    private val loadAccess: (Settlement) -> CompletableFuture<CivAutonomousAccess> = {
        CompletableFuture.completedFuture(CivAutonomousAccess())
    },
    private val writeCapture: (UUID, ByteArray) -> CompletableFuture<Unit> = { _, _ ->
        CompletableFuture.failedFuture(CivsRejection("storage-unavailable"))
    },
    private val deleteCapture: (UUID) -> CompletableFuture<Boolean> = { CompletableFuture.completedFuture(false) },
    private val dataVersion: Int = 0,
) : AutoCloseable {
    private val jobs = linkedMapOf<UUID, Search>()
    private val planning = linkedMapOf<UUID, CompletableFuture<CivSettlementPlan>>()
    private val terminal = linkedMapOf<UUID, Map<String, Any?>>()
    private var closed = false

    fun search(city: Settlement, type: BuildingType, level: Int, buildingId: UUID): CompletableFuture<CivAutonomousDesign> =
        admit(Search(city, type, level, buildingId))

    fun decoration(city: Settlement, entryId: String, buildingId: UUID): CompletableFuture<CivAutonomousDesign> =
        admit(Search(city, BuildingType.DECORATION, 1, buildingId, catalogEntry = entryId))

    fun infrastructure(city: Settlement): CompletableFuture<CivAutonomousDesign> =
        admit(Search(city, BuildingType.ROAD, 1, UUID.randomUUID(), infrastructure = true))

    /** One adopted plan, using the same detached height field, catalogue and finite solvers. */
    fun plan(city: Settlement): CompletableFuture<CivSettlementPlan> {
        val result = CompletableFuture<CivSettlementPlan>()
        synchronized(jobs) {
            if (closed) return CompletableFuture.failedFuture(CivsRejection("world-closed"))
            if (city.id in jobs || city.id in planning || jobs.size + planning.size >= 2)
                return CompletableFuture.failedFuture(CivsRejection("world-busy"))
            planning[city.id] = result
        }
        val prepared = runCatching {
            val hall = city.buildings.first { it.type == BuildingType.TOWN_HALL }
            val templates = CivsAutonomy.profile.associateWith { type ->
                val id = UUID.nameUUIDFromBytes("arccivs-plan-template/${city.id}/$type".toByteArray(Charsets.UTF_8))
                val entry = blueprints.autonomousSource(city.style, type)
                val first = if (entry == null) blueprints.prepare(city.style, type, 1, id, city.worldId, CivPosition(0, 0, 0), 2, 0)
                    else blueprints.prepareEntry(entry, id, city.worldId, CivPosition(0, 0, 0), 2, 0)
                val last = if (entry != null) first.thenCompose { blueprint ->
                    val maximum = blueprints.maximumLevel(blueprint.building)
                    if (maximum == 1) CompletableFuture.completedFuture(blueprint)
                    else blueprints.prepareUpgrade(blueprint.building, maximum, city.worldId)
                } else if (type.maxLevel == 1) first else
                    blueprints.prepare(city.style, type, type.maxLevel, id, city.worldId, CivPosition(0, 0, 0), 2, 0)
                first.thenCombine(last) { x, y -> x.building to y.building }
            }
            val inputs = loadAccess(city).thenCombine(world.autonomousHeightField(city, hall, hall)) { access, surface -> access to surface }
            CompletableFuture.allOf(*templates.values.toTypedArray()).thenCombineAsync(inputs, { _, input ->
                CivsSettlementPlans.prepare(city, templates.mapValues { it.value.join() }, input.second, input.first)
            }, executor)
        }.getOrElse { CompletableFuture.failedFuture(it) }
        prepared.whenComplete { value, failure ->
            synchronized(jobs) { planning.remove(city.id, result) }
            if (failure == null) result.complete(value) else result.completeExceptionally(failure)
        }
        return result
    }

    fun reserved(city: Settlement, plot: CivSettlementPlot): CompletableFuture<CivAutonomousDesign> =
        admit(Search(city, plot.building.type, 1, plot.building.id, reservation = plot))

    /** Legacy, player-pinned SITE plans retain their exact anchor and receive the same durable earthworks. */
    fun prepareExisting(city: Settlement, blueprint: CivBlueprint): CompletableFuture<CivAutonomousDesign> {
        val previous = city.building(blueprint.building.id)
        if (previous?.phase != BuildingPhase.SITE || previous.entrance != blueprint.building.entrance ||
            previous.rotation != blueprint.building.rotation)
            return CompletableFuture.failedFuture(CivsRejection("stale-building"))
        val adopted = CivsSettlementPlans.current(city)
        val reserved = adopted?.plots?.firstOrNull { it.building.id == blueprint.building.id &&
            it.building.anchor == blueprint.building.anchor && it.building.rotation == blueprint.building.rotation &&
            it.building.entrance == blueprint.building.entrance }
        return admit(Search(city, blueprint.building.type, blueprint.building.level, blueprint.building.id, blueprint, previous,
            infrastructure = blueprint.building.type.isInfrastructure, reservation = reserved,
            pinnedRoute = adopted?.routes?.get(blueprint.building.id.toString())))
    }

    private fun admit(job: Search): CompletableFuture<CivAutonomousDesign> = synchronized(jobs) {
        if (closed) return@synchronized CompletableFuture.failedFuture(CivsRejection("world-closed"))
        if (job.city.id in jobs || job.city.id in planning || jobs.size + planning.size >= 2)
            return@synchronized CompletableFuture.failedFuture(CivsRejection("world-busy"))
        jobs[job.city.id] = job
        job.result.whenComplete { design, failure ->
            job.pending?.cancel(false)
            if (job.temporaryCapture) deleteCapture(job.buildingId)
            synchronized(jobs) {
                jobs.remove(job.city.id, job)
                terminal.remove(job.city.id)
                terminal[job.city.id] = job.debug() + mapOf("outcome" to when {
                    job.result.isCancelled -> "cancelled"
                    failure != null -> ((unwrap(failure) as? CivsRejection)?.reason ?: "failed")
                    else -> "prepared"
                }, "cut" to design?.terrain?.cutCount, "fill" to design?.terrain?.fillCount,
                    "route_length" to design?.terrain?.route?.size)
                while (terminal.size > 16) terminal.remove(terminal.keys.first())
            }
        }
        job.start()
        job.result
    }

    /** Cached active and terminal diagnostics; no native reads or new schematic requests. */
    fun debug(cityId: UUID? = null): Map<String, Any?> = synchronized(jobs) {
        mapOf("closed" to closed, "jobs" to jobs.size + planning.size, "maximum_jobs" to 2,
            "planning" to planning.size, "planning_cities" to planning.keys.filter { cityId == null || cityId == it }.map(UUID::toString),
            "entries" to jobs.values.filter { cityId == null || it.city.id == cityId }.take(2).map { it.debug() },
            "last" to terminal.filterKeys { cityId == null || it == cityId }.values.toList().takeLast(2))
    }

    override fun close() {
        val active = synchronized(jobs) { closed = true; jobs.values.toList() }
        active.forEach { it.result.cancel(false) }
        synchronized(jobs) { planning.values.toList() }.forEach { it.cancel(false) }
    }

    private inner class Search(val city: Settlement, val type: BuildingType, val level: Int, val buildingId: UUID,
        private val pinned: CivBlueprint? = null, private val previous: CivBuilding? = null,
        private val infrastructure: Boolean = false, private val reservation: CivSettlementPlot? = null,
        private val pinnedRoute: List<CivPosition>? = null, private val catalogEntry: String? = null) {
        private val adopted = CivsSettlementPlans.current(city)
        private var resolvedCatalogEntry: String? = catalogEntry
        val result = CompletableFuture<CivAutonomousDesign>()
        @Volatile var temporaryCapture = false
        @Volatile var pending: CompletableFuture<*>? = null
        @Volatile var candidates = emptyList<CivsAutonomousCandidate>()
        @Volatile var options = emptyList<CivTerraformOption>()
        @Volatile var next = 0
        @Volatile var columns = 0
        @Volatile var phase = "catalog"
        @Volatile var selected: CivBuilding? = null
        private lateinit var surface: CivTerrainSurface
        private var access = CivAutonomousAccess()
        private var pieces = emptyMap<UUID, CivSettlementPiece>()
        private var templates: Pair<CivBuilding, CivBuilding>? = null
        @Volatile private var infillTried = false
        @Volatile private var frontageCandidates = 0
        @Volatile private var infillCandidates = 0
        @Volatile private var candidateSource = "frontage"
        @Volatile private var gradingSamples = emptyList<Map<String, Any>>()
        val rejections = java.util.concurrent.ConcurrentHashMap<String, Int>()
        @Volatile private var blockers = emptyList<CivSettlementBlocker>()

        fun debug(): Map<String, Any?> = mapOf("city" to city.id.toString(), "building" to buildingId.toString(),
            "type" to type.name, "candidate" to next, "candidates" to candidates.size, "options" to options.size,
            "surface_columns" to columns, "phase" to phase, "rejections" to rejections.toSortedMap(),
            "selected_anchor" to selected?.anchor, "fixed" to (pinned != null), "layout_blockers" to blockers,
            "candidate_source" to candidateSource, "frontage_candidates" to frontageCandidates,
            "infill_candidates" to infillCandidates, "grading_samples" to gradingSamples)

        fun start() {
            phase = "access"
            await(loadAccess(city)) { snapshot -> access = snapshot; catalog() }
        }

        private fun catalog() {
            phase = "catalog"
            if (infrastructure && pinned == null) {
                val hall = city.buildings.first { it.type == BuildingType.TOWN_HALL }
                phase = "surface"
                await(world.autonomousHeightField(city, hall, hall)) { field ->
                    surface = field; columns = field.columns.size
                    val pavingRoutes = (access.routes.filterKeys { id -> city.building(id)?.type?.isInfrastructure == false }.values +
                        adopted?.routes.orEmpty().values).distinct()
                    val reservedCity = city.copy(buildings = city.buildings + adopted?.plots.orEmpty()
                        .filter { city.building(it.building.id) == null }.map { it.building.copy(bounds = it.envelope) })
                    val plan = CivsSettlementLayout.infrastructure(reservedCity, CivsSettlementLayout.frame(city), field,
                        (access.routes.values + pavingRoutes).distinct(), access.infrastructure, pavingRoutes, access.routes.values)
                    rejections.putAll(plan.rejected)
                    blockers = plan.blockers
                    if (blockers.isNotEmpty()) CivsDiagnostics.record(java.util.logging.Level.WARNING, city.id,
                        CivsDiagnostics.line("autonomous_layout_obstructed", "city" to city.id,
                            "perimeter_closed" to plan.perimeterClosed, "blockers" to blockers.take(4), "count" to blockers.size))
                    val missing = plan.pieces.filter { city.building(it.id) == null }
                        .sortedWith(compareBy<CivSettlementPiece> { when (it.type) {
                            BuildingType.GATE -> 0; BuildingType.WALL -> 1; else -> 2
                        } }.thenBy { it.bounds.min.x }.thenBy { it.bounds.min.z })
                    if (missing.isEmpty()) {
                        fail(if (plan.rejected.isEmpty()) "autonomy-layout-complete" else "autonomy-no-site")
                        return@await
                    }
                    pieces = missing.associateBy { it.id }
                    candidates = missing.map { CivsAutonomousCandidate(CivsInfrastructure.building(it)) }
                    options = candidates.map { CivTerraformOption(it.building, 0) }.take(MAXIMUM_OPTIONS)
                    attempt()
                }
                return
            }
            if (reservation != null) {
                candidates = listOf(CivsAutonomousCandidate(reservation.building.copy(bounds = reservation.envelope)))
                captureSurface()
                return
            }
            if (pinned != null) {
                if (infrastructure) pieces = mapOf(pinned.building.id to CivSettlementPiece(pinned.building.id,
                    pinned.building.type, pinned.building.bounds, pinned.building.entrance,
                    pinned.cells.filterNot { it.isBarrier }.map { CivSettlementBlock(
                        CivPosition(it.position.x, it.position.y, it.position.z), it.blockData) }, pinned.building.transit.orEmpty()))
                candidates = listOf(CivsAutonomousCandidate(pinned.building.copy(bounds =
                    CivsAutonomousGeometry.futureBounds(city, pinned.building))))
                captureSurface()
                return
            }
            resolvedCatalogEntry = catalogEntry ?: reservation?.building?.schematicId?.takeIf { it.startsWith("civs/v2/") }
                ?: blueprints.autonomousSource(city.style, type)
            val entry = resolvedCatalogEntry
            val current = if (entry != null) blueprints.prepareEntry(entry, buildingId, city.worldId,
                CivPosition(0, 0, 0), 2, 0) else blueprints.prepare(city.style, type, level, buildingId, city.worldId,
                CivPosition(0, 0, 0), 2, 0)
            val future = if (entry != null) current.thenCompose { blueprint ->
                val maximum = blueprints.maximumLevel(blueprint.building)
                if (maximum == level) CompletableFuture.completedFuture(blueprint)
                else blueprints.prepareUpgrade(blueprint.building, maximum, city.worldId)
            } else if (level == type.maxLevel) current else blueprints.prepare(city.style, type, type.maxLevel, buildingId,
                city.worldId, CivPosition(0, 0, 0), 2, 0)
            await(current.thenCombineAsync(future, { a, b -> a.building to b.building }, executor)) { pair ->
                templates = pair
                candidates = CivsSettlementLayout.candidates(city, pair.first, pair.second, access)
                frontageCandidates = candidates.size
                if (candidates.isEmpty()) candidates = infill()
                captureSurface()
            }
        }

        private fun infill(): List<CivsAutonomousCandidate> {
            val pair = templates ?: return emptyList()
            if (infillTried) return emptyList()
            val terrainKnown = this::surface.isInitialized
            infillTried = terrainKnown
            candidateSource = "infill"
            val seen = candidates.mapTo(hashSetOf()) { it.building.anchor to it.building.rotation }
            // Filter the detached surface before the 64-result cap. A shoreline must
            // not let the first unavailable yards hide a connected dry inner plot.
            return CivsSettlementLayout.infill(city, pair.first, pair.second, access, eligible = { building ->
                (building.anchor to building.rotation) !in seen && (!terrainKnown ||
                    CivsAutonomousTerraform.options(city, listOf(CivsAutonomousCandidate(building)), surface).isNotEmpty())
            })
                .also { infillCandidates += it.size }
        }

        private fun grade(choices: List<CivsAutonomousCandidate>): List<CivTerraformOption> =
            CivsAutonomousTerraform.options(city, choices, surface, pinned != null || reservation != null) { reason, at ->
                rejections.merge(reason, 1, Int::plus)
                if (gradingSamples.size < 4) gradingSamples = gradingSamples +
                    mapOf("source" to candidateSource, "reason" to reason, "at" to at)
            }

        private fun captureSurface() {
            val first = candidates.firstOrNull() ?: return fail("autonomy-no-site")
            phase = "surface"
            await(world.autonomousHeightField(city, first.building, previous)) { field ->
                surface = field
                columns = field.columns.size
                phase = "grading"
                options = if (infrastructure && pinned != null) listOf(CivTerraformOption(pinned.building, 0))
                    else grade(candidates).take(MAXIMUM_OPTIONS)
                attempt()
            }
        }

        private fun attempt() {
            if (result.isDone) return
            if (next >= options.size) {
                // Reuse this detached surface. The two tiers share the original 64 native-attempt budget.
                if (!infillTried && templates != null && next < MAXIMUM_OPTIONS) {
                    val extra = infill()
                    candidates = candidates + extra
                    options = options + grade(extra).take(MAXIMUM_OPTIONS - next)
                    if (next < options.size) { attempt(); return }
                }
                val reason = when {
                    rejections.containsKey("chunks-unloaded") && rejections.size == 1 -> "chunks-unloaded"
                    rejections.containsKey("autonomy-search-budget") -> "autonomy-search-budget"
                    else -> "autonomy-no-site"
                }
                return fail(reason)
            }
            val option = options[next++]
            selected = option.building
            phase = "grading"
            val piece = pieces[option.building.id]
            val futureObstacles = adopted?.plots.orEmpty().filter {
                it.building.id != option.building.id && city.building(it.building.id) == null
            }.map { it.envelope }
            val infrastructureFloors = buildMap {
                piece?.blocks?.groupBy { civsTerrainKey(it.position.x, it.position.z) }?.forEach { (column, cells) ->
                    put(column, cells.minOf { it.position.y })
                }
                // A captured SITE omits skipped cells from its writes. Its immutable transit
                // retains the exact standing grade for those already-paid road columns.
                piece?.transit.orEmpty().forEach { putIfAbsent(civsTerrainKey(it.x, it.z), it.y - 1) }
            }
            val draft = try { CivsAutonomousTerraform.draft(city, option, surface, access,
                (if (pinned != null && !infrastructure && pinnedRoute == null) emptyList()
                    else CivsSettlementLayout.frame(city).wallObstacles) + futureObstacles,
                infrastructureFloors,
                reservedRoute = if (reservation == null) pinnedRoute else adopted?.routes?.get(buildingId.toString())
                    ?: throw CivsRejection("invalid-plan"),
                preferredRoads = access.routes.values.flatten().mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }) }
            catch (failure: RuntimeException) { retry(failure); return }
            phase = "earthworks"
            await(world.captureTerraform(city, draft, executor, previous), ::retry) { terrain ->
                phase = "route"
                await(world.autonomousRoute(city, draft.building, terrain.route.first(), executor, terrain, previous), ::retry) {
                    phase = "blueprint"
                    val prepared = when {
                        pinned != null -> CompletableFuture.completedFuture(pinned)
                        piece != null -> {
                            val bytes = CivsInfrastructure.schematic(piece, dataVersion)
                            temporaryCapture = true
                            writeCapture(buildingId, bytes).thenCompose {
                                blueprints.prepareInfrastructure(piece, city.worldId, bytes, buildingId)
                            }
                        }
                        resolvedCatalogEntry != null -> blueprints.prepareEntry(requireNotNull(resolvedCatalogEntry), buildingId, city.worldId,
                            draft.building.anchor, 2, draft.building.rotation)
                        else -> blueprints.prepare(city.style, type, level, buildingId, city.worldId,
                            draft.building.anchor, 2, draft.building.rotation)
                    }
                    await(prepared) { blueprint ->
                        if (!civsContainsBox(draft.building.bounds, blueprint.building.bounds) ||
                            blueprint.building.entrance != draft.building.entrance || blueprint.building.control != draft.building.control) {
                            fail("autonomy-catalog-changed")
                        } else result.complete(CivAutonomousDesign(blueprint, terrain))
                    }
                }
            }
        }

        private fun fail(reason: String) {
            result.completeExceptionally(CivsRejection(reason, rejections.mapValues { it.value.toString() } +
                mapOf("attempted" to next.toString(), "options" to options.size.toString())))
        }

        private fun retry(failure: Throwable) {
            val cause = unwrap(failure)
            if (cause !is CivsRejection || cause.reason !in CANDIDATE_REJECTIONS) {
                result.completeExceptionally(cause)
                return
            }
            rejections.merge(cause.reason, 1, Int::plus)
            attempt()
        }

        private fun <T> await(future: CompletableFuture<T>, failed: (Throwable) -> Unit = {
            result.completeExceptionally(unwrap(it)); Unit
        }, completed: (T) -> Unit) {
            pending = future
            if (result.isDone) { future.cancel(false); return }
            future.whenComplete { value, failure ->
                try {
                    executor.execute {
                        if (!result.isDone) try {
                            if (failure != null) failed(failure) else completed(value)
                        } catch (error: Throwable) { result.completeExceptionally(error) }
                    }
                } catch (error: RuntimeException) { result.completeExceptionally(error) }
            }
        }
    }

    companion object {
        const val MAXIMUM_SURFACE_COLUMNS = CivsAutonomousTerraform.MAXIMUM_SURFACE_COLUMNS
        const val MAXIMUM_OPTIONS = 64
        private val CANDIDATE_REJECTIONS = setOf("autonomy-terrain", "autonomy-slope", "autonomy-no-path", "autonomy-foundation",
            "autonomy-search-budget", "building-overlap", "outside-city", "outside-world", "outside-border",
            "lands-denied", "protected-terrain", "chunks-unloaded", "city-overlap", "world-changed")
        private fun unwrap(failure: Throwable): Throwable =
            if (failure is CompletionException && failure.cause != null) unwrap(failure.cause!!) else failure
    }
}

internal data class CivsAutonomousCandidate(val building: CivBuilding, val streetFrontage: Boolean = false)

/** Authored entrances face the centre; footprints grow outward around an open central approach. */
internal object CivsAutonomousGeometry {
    fun candidates(city: Settlement, current: CivBuilding, future: CivBuilding,
        access: CivAutonomousAccess = CivAutonomousAccess()): List<CivsAutonomousCandidate> {
        val bounds = union(withField(current.bounds, current), withField(future.bounds, future))
        val occupied = city.buildings.map { futureBounds(city, it) } + listOfNotNull(city.construction?.plannedBuilding?.bounds)
        val entries = city.buildings.flatMap(::approach)
        val fields = city.buildings.flatMap { CivsWorksiteLayouts.field(it) + CivsWorksiteLayouts.irrigation(it) }
        val roads = access.standing(city, current.id).values
        val result = mutableListOf<CivsAutonomousCandidate>()
        // ponytail: 64 compact candidates; add terrain-aware infill only if this finite profile cannot fit.
        for (radius in listOf(18, 32, 46, 60)) for (sector in 0 until 16) {
            val angle = sector * Math.PI / 8
            val dx = (sin(angle) * radius).roundToInt()
            val dz = (cos(angle) * radius).roundToInt()
            val turns = when { abs(dx) > abs(dz) -> if (dx > 0) 1 else 3; dz > 0 -> 2; else -> 0 }
            val entry = rotate(current.entrance, turns)
            val anchor = CivPosition(city.center.x + dx - entry.x, city.center.y, city.center.z + dz - entry.z)
            fun point(local: CivPosition): CivPosition = rotate(local, turns).let {
                CivPosition(Math.addExact(anchor.x, it.x), Math.addExact(anchor.y, it.y), Math.addExact(anchor.z, it.z))
            }
            val corners = listOf(point(bounds.min), point(bounds.max), point(CivPosition(bounds.min.x, bounds.min.y, bounds.max.z)),
                point(CivPosition(bounds.max.x, bounds.max.y, bounds.min.z)))
            val box = CivBox(CivPosition(corners.minOf { it.x }, corners.minOf { it.y }, corners.minOf { it.z }),
                CivPosition(corners.maxOf { it.x }, corners.maxOf { it.y }, corners.maxOf { it.z }))
            val building = current.copy(anchor = anchor, rotation = turns * 90, bounds = box,
                entrance = point(current.entrance), control = current.control?.let(::point))
            if (!city.contains(box.min.x, box.min.z) || !city.contains(box.max.x, box.max.z) ||
                approach(building).any { !city.contains(it.x, it.z) } ||
                occupied.any { overlaps(box, it, 2) } || (entries + fields + roads).any { horizontalContains(box, it) } ||
                approach(building).any { p -> occupied.any { horizontalContains(it, p) } } ||
                !CivsRules.workRouteWithinReach(city, building.entrance)) continue
            result += CivsAutonomousCandidate(building)
        }
        return result
    }

    fun atHeight(building: CivBuilding, ground: Int): CivBuilding {
        val delta = Math.subtractExact(ground, building.anchor.y)
        fun point(p: CivPosition) = p.copy(y = Math.addExact(p.y, delta))
        return building.copy(anchor = point(building.anchor), entrance = point(building.entrance),
            control = building.control?.let(::point), bounds = CivBox(point(building.bounds.min), point(building.bounds.max)))
    }

    fun approach(building: CivBuilding): List<CivPosition> {
        if (building.type.isInfrastructure) return listOf(building.entrance)
        val front = rotate(CivPosition(0, 0, 1), building.rotation / 90)
        return (0..3).flatMap { distance -> (-1..1).map { side ->
            CivPosition(building.entrance.x + front.x * distance + front.z * side, building.entrance.y,
                building.entrance.z + front.z * distance - front.x * side)
        } }
    }

    fun futureBounds(city: Settlement, building: CivBuilding): CivBox {
        if (building.type == BuildingType.PLAN_BOARD) return CivsPlanBoardBlueprint.futureBounds(building)
        if (building.type.isInfrastructure) return building.bounds
        if (building.schematicId.startsWith("civs/v2/") || building.type.maxLevel == 1) return withField(building.bounds, building)
        val style = CivsBlueprints.styleOf(building) ?: city.style
        if (style !in CivsBlueprints.STYLES) return withField(building.bounds, building)
        val target = building.copy(schematicId = CivsBlueprints.schematicId(style, building.type, building.type.maxLevel))
        val bounds = CivsWorksiteLayouts.site(target)?.getAsJsonObject("schematicBounds") ?: return withField(building.bounds, building)
        val a = CivsWorksiteLayouts.point(target, CivsWorksiteLayouts.vector(bounds["min"]))
        val b = CivsWorksiteLayouts.point(target, CivsWorksiteLayouts.vector(bounds["max"]))
        return withField(union(building.bounds, CivBox(CivPosition(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z)),
            CivPosition(maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z)))), target)
    }

    fun horizontalContains(box: CivBox, point: CivPosition): Boolean = point.x in box.min.x..box.max.x && point.z in box.min.z..box.max.z
    fun overlaps(a: CivBox, b: CivBox, margin: Int = 0): Boolean =
        a.min.x.toLong() - margin <= b.max.x && a.max.x.toLong() + margin >= b.min.x &&
            a.min.z.toLong() - margin <= b.max.z && a.max.z.toLong() + margin >= b.min.z

    private fun rotate(p: CivPosition, turns: Int): CivPosition = when (Math.floorMod(turns, 4)) {
        0 -> p; 1 -> CivPosition(-p.z, p.y, p.x); 2 -> CivPosition(-p.x, p.y, -p.z); else -> CivPosition(p.z, p.y, -p.x)
    }
    private fun union(a: CivBox, b: CivBox) = CivBox(
        CivPosition(minOf(a.min.x, b.min.x), minOf(a.min.y, b.min.y), minOf(a.min.z, b.min.z)),
        CivPosition(maxOf(a.max.x, b.max.x), maxOf(a.max.y, b.max.y), maxOf(a.max.z, b.max.z)))
    private fun withField(bounds: CivBox, building: CivBuilding): CivBox =
        (CivsWorksiteLayouts.field(building) + CivsWorksiteLayouts.irrigation(building)).fold(bounds) { box, point ->
            union(box, CivBox(point, point))
        }
}
