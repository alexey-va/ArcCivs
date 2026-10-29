package ru.arc.civs

import org.bukkit.Location
import java.util.UUID
import kotlin.math.abs

/** Consecutive native positions only; a route, spawn or teleport never fabricates footsteps. */
internal class CitizenFootsteps {
    private var previous: Location? = null

    fun reset() { previous = null }

    /** Seed only from the real pre-move position, including the first boundary crossed after a reset. */
    fun prime(position: Location, onGround: Boolean) {
        if (previous == null) observe(position, onGround)
    }

    fun observe(position: Location, onGround: Boolean): Pair<Location, Location>? {
        if (!onGround || position.world == null || !position.x.isFinite() || !position.y.isFinite() || !position.z.isFinite()) {
            reset()
            return null
        }
        val from = previous
        previous = position.clone()
        if (from == null || from.world?.uid != position.world?.uid) return null
        val dx = position.x - from.x
        val dy = position.y - from.y
        val dz = position.z - from.z
        val horizontal = dx * dx + dz * dz
        if (horizontal <= 0.000001 || horizontal + dy * dy > 4.0 || abs(dy) > 1.25) return null
        return from to position.clone()
    }
}

internal data class CitizenPoint(val x: Double, val y: Double, val z: Double) {
    fun distanceSquared(other: CitizenPoint): Double = (x - other.x) * (x - other.x) +
        (y - other.y) * (y - other.y) + (z - other.z) * (z - other.z)
}
internal data class CitizenViewer(val worldId: UUID, val point: CitizenPoint)

/** Null completion means no callback; a received null reason means native completion, not verified arrival. */
internal data class CitizenNavigationCompletion(val cancelReason: String?)

/** Owning-thread route clock: preparation/replanning never spends the allowance for walking. */
internal class CitizenRouteTiming(private val startedAt: Long, private val timeoutMillis: Long) {
    private var preparingSince = startedAt
    private var walkingSince: Long? = null
    private var walkedMillis = 0L
    private var observedElapsed = 0L

    fun ready(now: Long) {
        if (walkingSince == null) walkingSince = now
    }

    fun prepare(now: Long) {
        walkedMillis = elapsed(now)
        walkingSince = null
        preparingSince = now
    }

    fun elapsed(now: Long): Long {
        val current = walkedMillis + (walkingSince?.let { (now - it).coerceAtLeast(0) } ?: 0L)
        observedElapsed = maxOf(observedElapsed, current)
        return observedElapsed
    }
    fun preparationElapsed(now: Long): Long = if (walkingSince == null) (now - preparingSince).coerceAtLeast(0) else 0L
    fun timedOut(now: Long, searching: Boolean): Boolean = !searching && walkingSince != null && elapsed(now) > timeoutMillis
}

internal enum class CitizenNavigationOutcome { PENDING, ARRIVED, CANCELLED, COMPLETED_OUTSIDE_ARRIVAL, TIMED_OUT, STOPPED }

internal data class CitizenPathSnapshot(val nodes: Int, val end: CitizenPoint, val truncated: Boolean)
