package ru.arc.civs

import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.util.BoundingBox
import java.util.UUID

/** Owning-thread admission state, retry history and bounded native spawn selection. */
internal class CivsCitizenAdmissions(
    private val activeAt: (UUID, UUID, CitizenPoint, List<CitizenViewer>) -> Boolean,
    private val occupied: (UUID, BoundingBox) -> Boolean,
) {
    private val spawnRetries = mutableMapOf<UUID, Pair<CitizenPlan, CitizenRetry>>()
    private val admissions = mutableMapOf<UUID, CitizenAdmission>()
    private val admissionOrigins = mutableMapOf<UUID, Pair<UUID, CivPosition>>()

    fun retain(workers: Set<UUID>, actors: Set<UUID>) {
        spawnRetries.keys.retainAll(workers)
        admissions.keys.retainAll(workers - actors)
        admissionOrigins.keys.retainAll(workers - actors)
    }

    fun origin(city: Settlement, workerId: UUID, plan: CitizenPlan): CivPosition =
        admissionOrigins[workerId]?.takeIf { it.first == city.id }?.second ?: plan.spawnOrigin

    fun requestAt(cityId: UUID, workerId: UUID, point: CivPosition) {
        admissionOrigins[workerId] = cityId to point
        spawnRetries.remove(workerId)
    }

    fun retry(workerId: UUID, plan: CitizenPlan?): CitizenRetry =
        spawnRetries[workerId]?.takeIf { it.first == plan }?.second
            ?: CitizenRetry(exhaustedCooldownMillis = 60_000)

    fun sleeping(workerId: UUID) { spawnRetries.remove(workerId) }

    fun failed(workerId: UUID, plan: CitizenPlan?, retry: CitizenRetry, at: Long) {
        retry.fail(at)
        spawnRetries[workerId] = requireNotNull(plan) to retry
    }

    fun accepted(workerId: UUID) {
        spawnRetries.remove(workerId)
        admissions.remove(workerId)
        admissionOrigins.remove(workerId)
    }

    fun clear() {
        spawnRetries.clear()
        admissions.clear()
        admissionOrigins.clear()
    }

    fun debug(cityId: UUID?, workerId: UUID?): Map<String, Any?> {
        val absent = admissions.values.filter { (cityId == null || it.cityId == cityId) && (workerId == null || it.workerId == workerId) }
        return mapOf("source" to "last-resident-pulse", "total" to absent.size,
                "truncated" to (absent.size > 8), "entries" to absent.take(8).map { admission -> mapOf(
                    "city" to admission.cityId, "worker" to admission.workerId, "reason" to admission.reason,
                    "observed_at_ms" to admission.at, "origin" to admission.plan?.spawnOrigin,
                    "origin_chunk" to admission.chunk, "required_load_level" to Chunk.LoadLevel.ENTITY_TICKING,
                    "retry_attempts" to admission.retry?.attempts, "retry_exhausted" to admission.retry?.exhausted,
                    "next_retry_at_ms" to admission.retry?.nextAttemptAt,
                    "spawn" to admission.spawn?.let { search -> mapOf("observed_at_ms" to search.at,
                        "candidate_limit" to CITIZEN_SPAWN_CANDIDATE_LIMIT, "inspected" to search.inspected,
                        "rejections" to search.rejections, "sample_limit" to CITIZEN_SPAWN_SAMPLE_LIMIT,
                        "truncated" to (search.inspected > search.samples.size), "samples" to search.samples) }) })
    }

    fun record(city: Settlement, worker: CivWorker, plan: CitizenPlan?, world: World?,
                                reason: String, at: Long, retry: CitizenRetry? = null, spawn: CitizenSpawnSearch? = null) {
        val previous = admissions[worker.id]?.takeIf { it.plan == plan }
        val origin = plan?.spawnOrigin
        val level = if (world == null || origin == null) null else citizenChunkLoadLevel(world, origin.x shr 4, origin.z shr 4)
        admissions[worker.id] = CitizenAdmission(city.id, worker.id, plan, reason, at,
            origin?.let { mapOf("world" to city.worldId, "x" to (it.x shr 4), "z" to (it.z shr 4),
                "loaded" to (level != null), "load_level" to level) },
            retry ?: spawnRetries[worker.id]?.takeIf { it.first == plan }?.second, spawn ?: previous?.spawn)
    }

    fun spawnPoint(city: Settlement, world: World, storage: CivPosition, ordinal: Int,
                           viewers: List<CitizenViewer>, at: Long): CitizenSpawnSearch {
        val samples = mutableListOf<CitizenSpawnSample>()
        val rejections = linkedMapOf<String, Int>()
        var inspected = 0
        // Two admissions per pulse give at most 234 candidate checks across all cities.
        for (point in citizenSpawnCandidates(storage, ordinal)) {
            inspected++
            val location = point.location(world)
            val reason = when {
                !city.contains(point.x, point.z) -> "outside-city"
                !citizenControlClear(city, point) -> "control-obstructed"
                !citizenStandable(world, point) -> "not-standable"
                !activeAt(city.id, world.uid, point.point(), viewers) -> "sleeping"
                else -> {
                    val body = citizenStandingBounds(location, 1.0)
                    if (occupied(world.uid, body)) "occupied" else null
                }
            }
            if (samples.size < CITIZEN_SPAWN_SAMPLE_LIMIT || reason == null) {
                val level = citizenChunkLoadLevel(world, point.x shr 4, point.z shr 4)
                val blocks = if (level != null && point.y > world.minHeight && point.y + 1 < world.maxHeight)
                    listOf(-1, 0, 1).map { world.getBlockAt(point.x, point.y + it, point.z).type.name } else null
                val sample = CitizenSpawnSample(point, reason ?: "accepted", level, blocks?.get(0), blocks?.get(1), blocks?.get(2))
                if (samples.size == CITIZEN_SPAWN_SAMPLE_LIMIT) samples[samples.lastIndex] = sample else samples += sample
            }
            if (reason == null) return CitizenSpawnSearch(point, at, inspected, rejections, samples)
            rejections[reason] = (rejections[reason] ?: 0) + 1
        }
        return CitizenSpawnSearch(null, at, inspected, rejections, samples)
    }

}

private const val CITIZEN_SPAWN_SAMPLE_LIMIT = 16

private data class CitizenAdmission(val cityId: UUID, val workerId: UUID, val plan: CitizenPlan?, val reason: String,
    val at: Long, val chunk: Map<String, Any?>?, val retry: CitizenRetry?, val spawn: CitizenSpawnSearch?)
internal data class CitizenSpawnSample(val point: CivPosition, val reason: String, val loadLevel: Chunk.LoadLevel?,
    val support: String?, val feet: String?, val head: String?)
internal data class CitizenSpawnSearch(val point: CivPosition?, val at: Long, val inspected: Int,
    val rejections: Map<String, Int>, val samples: List<CitizenSpawnSample>)

internal fun citizenHasViewer(worldId: UUID, point: CitizenPoint, viewers: List<CitizenViewer>, range: Int): Boolean =
    viewers.any { it.worldId == worldId && it.point.distanceSquared(point) <= range.toDouble() * range }
internal fun citizenSpawnBudget(active: Int, maximum: Int): Int =
    (maximum.coerceIn(0, CivsSettings.MAXIMUM_NPCS) - active).coerceIn(0, 2)

internal const val CITIZEN_SPAWN_CANDIDATE_LIMIT = 13 * 9

/** Preserve the nearest floor first, then inspect only four blocks above and below it. */
internal fun citizenSpawnCandidates(origin: CivPosition, ordinal: Int): Sequence<CivPosition> = sequence {
    val offsets = listOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, -1 to 1,
        1 to -1, -1 to -1, 2 to 0, -2 to 0, 0 to 2, 0 to -2)
    for (dy in listOf(0, 1, -1, 2, -2, 3, -3, 4, -4)) for (index in offsets.indices) {
        val (dx, dz) = offsets[Math.floorMod(index + ordinal, offsets.size)]
        val x = origin.x.toLong() + dx
        val y = origin.y.toLong() + dy
        val z = origin.z.toLong() + dz
        if (x in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() && y in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
            z in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) yield(CivPosition(x.toInt(), y.toInt(), z.toInt()))
    }
}

/** Failed native target checks consume an admission too, so they must not monopolize the next pulse. */
internal fun <T> citizenRotateAfterAdmission(actors: MutableMap<UUID, T>, workerId: UUID) {
    val actor = actors.remove(workerId) ?: return
    actors[workerId] = actor
}
