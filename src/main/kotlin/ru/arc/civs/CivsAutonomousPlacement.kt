package ru.arc.civs

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Selects one connected, supported plot. The service still owns durable SITE admission. */
internal class CivsAutonomousPlacement(
    private val blueprints: CivsBlueprints,
    private val world: CivsWorld,
    private val executor: Executor,
) : AutoCloseable {
    private val jobs = linkedMapOf<UUID, Search>()
    private var closed = false

    fun search(city: Settlement, type: BuildingType, level: Int, buildingId: UUID): CompletableFuture<CivBlueprint> =
        synchronized(jobs) {
            if (closed) return@synchronized CompletableFuture.failedFuture(CivsRejection("world-closed"))
            if (city.id in jobs || jobs.size >= 2)
                return@synchronized CompletableFuture.failedFuture(CivsRejection("world-busy"))
            val job = Search(city, type, level, buildingId)
            jobs[city.id] = job
            job.result.whenComplete { _, _ ->
                job.pending?.cancel(false)
                synchronized(jobs) { jobs.remove(city.id, job) }
            }
            job.start()
            job.result
        }

    /** Cached progress only; this never starts a scan or a schematic lookup. */
    fun debug(cityId: UUID? = null): Map<String, Any?> = synchronized(jobs) {
        mapOf("closed" to closed, "jobs" to jobs.size, "maximum_jobs" to 2,
            "entries" to jobs.values.filter { cityId == null || it.city.id == cityId }.take(2).map {
                mapOf("city" to it.city.id.toString(), "building" to it.buildingId.toString(),
                    "type" to it.type.name, "candidate" to it.next, "candidates" to it.candidates.size,
                    "surface_columns" to it.columns, "phase" to it.phase,
                    "rejections" to it.rejections.toMap())
            })
    }

    override fun close() {
        val active = synchronized(jobs) { closed = true; jobs.values.toList() }
        active.forEach { it.result.cancel(false) }
    }

    private inner class Search(val city: Settlement, val type: BuildingType, val level: Int, val buildingId: UUID) {
        val result = CompletableFuture<CivBlueprint>()
        @Volatile var pending: CompletableFuture<*>? = null
        @Volatile var candidates = emptyList<CivsAutonomousCandidate>()
        @Volatile var next = 0
        @Volatile var columns = 0
        @Volatile var phase = "catalog"
        val rejections = java.util.concurrent.ConcurrentHashMap<String, Int>()

        fun start() {
            val current = blueprints.prepare(city.style, type, level, buildingId, city.worldId,
                CivPosition(0, 0, 0), 2, 0)
            val future = if (level == 3) current else blueprints.prepare(city.style, type, 3, buildingId,
                city.worldId, CivPosition(0, 0, 0), 2, 0)
            await(current.thenCombineAsync(future, { a, b ->
                CivsAutonomousGeometry.candidates(city, a.building, b.building)
            }, executor)) { choices -> candidates = choices; attempt() }
        }

        private fun attempt() {
            if (result.isDone) return
            if (next >= candidates.size) {
                val reason = when {
                    rejections.containsKey("chunks-unloaded") -> "chunks-unloaded"
                    rejections.containsKey("autonomy-search-budget") -> "autonomy-search-budget"
                    else -> "autonomy-no-site"
                }
                result.completeExceptionally(CivsRejection(reason, rejections.mapValues { it.value.toString() }))
                return
            }
            val candidate = candidates[next++]
            val footprint = candidate.building.bounds
            val area = (footprint.max.x - footprint.min.x + 1) * (footprint.max.z - footprint.min.z + 1)
            if (columns + area > MAXIMUM_SURFACE_COLUMNS) {
                result.completeExceptionally(CivsRejection("autonomy-search-budget"))
                return
            }
            columns += area
            phase = "surface"
            await(world.autonomousSurface(city, candidate.building), ::retry) { ground ->
                val building = CivsAutonomousGeometry.atHeight(candidate.building, ground)
                phase = "terrain"
                await(world.validateAutonomousSite(city, building), ::retry) {
                    val source = city.buildings.filter(CivsRules::isDepot).minByOrNull {
                        val dx = it.entrance.x.toLong() - building.entrance.x
                        val dz = it.entrance.z.toLong() - building.entrance.z
                        dx * dx + dz * dz
                    } ?: city.buildings.first { it.type == BuildingType.TOWN_HALL }
                    phase = "route"
                    await(world.autonomousRoute(city, building, source.entrance, executor), ::retry) {
                        phase = "blueprint"
                        await(blueprints.prepare(city.style, type, level, buildingId, city.worldId,
                            building.anchor, 2, building.rotation)) { blueprint ->
                            if (!civsContainsBox(building.bounds, blueprint.building.bounds) ||
                                blueprint.building.entrance != building.entrance || blueprint.building.control != building.control) {
                                result.completeExceptionally(CivsRejection("autonomy-catalog-changed"))
                            } else result.complete(blueprint)
                        }
                    }
                }
            }
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
        const val MAXIMUM_SURFACE_COLUMNS = 32_768
        private val CANDIDATE_REJECTIONS = setOf("autonomy-terrain", "autonomy-slope", "autonomy-no-path",
            "autonomy-search-budget", "building-overlap", "outside-city", "outside-world", "outside-border",
            "lands-denied", "protected-terrain", "chunks-unloaded", "city-overlap")
        private fun unwrap(failure: Throwable): Throwable =
            if (failure is CompletionException && failure.cause != null) unwrap(failure.cause!!) else failure
    }
}

internal data class CivsAutonomousCandidate(val building: CivBuilding)

/** Authored entrances face the centre; footprints grow outward around an open central approach. */
internal object CivsAutonomousGeometry {
    fun candidates(city: Settlement, current: CivBuilding, future: CivBuilding): List<CivsAutonomousCandidate> {
        val bounds = union(withField(current.bounds, current), withField(future.bounds, future))
        val occupied = city.buildings.map { futureBounds(city, it) } + listOfNotNull(city.construction?.plannedBuilding?.bounds)
        val entries = city.buildings.flatMap(::approach)
        val fields = city.buildings.flatMap { CivsWorksiteLayouts.field(it) + CivsWorksiteLayouts.irrigation(it) }
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
                occupied.any { overlaps(box, it, 2) } || (entries + fields).any { horizontalContains(box, it) } ||
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
        val front = rotate(CivPosition(0, 0, 1), building.rotation / 90)
        return (0..3).flatMap { distance -> (-1..1).map { side ->
            CivPosition(building.entrance.x + front.x * distance + front.z * side, building.entrance.y,
                building.entrance.z + front.z * distance - front.x * side)
        } }
    }

    fun futureBounds(city: Settlement, building: CivBuilding): CivBox {
        if (city.style !in CivsBlueprints.STYLES) return withField(building.bounds, building)
        val target = building.copy(schematicId = CivsBlueprints.schematicId(city.style, building.type, 3))
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
