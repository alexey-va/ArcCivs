package ru.arc.civs

import java.util.UUID

/** One blocked physical mine action, independent of its candidate focus and replacement routes. */
internal class CivsMineRouteEpisode {
    data class Key(val buildingId: UUID, val planVersion: Int, val cycle: Long, val miningStep: Long)
    data class Snapshot(val key: Key, val failures: Int, val elapsedMillis: Long,
        val walkedDistance: Double, val suppressed: Int)

    private class State(val key: Key, val startedAt: Long) {
        var failures = 0
        var reportedAt = startedAt
        var walkedDistance = 0.0
        var suppressed = 0

        fun snapshot(now: Long) = Snapshot(key, failures, (now - startedAt).coerceAtLeast(0), walkedDistance, suppressed)
    }

    private var state: State? = null
    val key: Key? get() = state?.key

    /** Assignment/step/phase cancellation clears state; a route replacement does not call this. */
    fun retain(key: Key?) {
        if (state?.key != key) state = null
    }

    /** Called once per terminal route. The first failure and stall threshold are always visible. */
    fun failed(key: Key, now: Long): Snapshot? {
        val episode = state?.takeIf { it.key == key } ?: State(key, now).also { state = it }
        if (episode.failures < Int.MAX_VALUE) episode.failures++
        if (episode.failures != 1 && episode.failures != STALL_FAILURES &&
            (episode.failures < STALL_FAILURES || now - episode.reportedAt < REPORT_INTERVAL_MILLIS)) {
            if (episode.suppressed < Int.MAX_VALUE) episode.suppressed++
            return null
        }
        val report = episode.snapshot(now)
        episode.reportedAt = now
        episode.suppressed = 0
        return report
    }

    /** Only accepted forward movement may supply this distance, never a changed observed position. */
    fun walked(distance: Double, now: Long): Snapshot? {
        val episode = state ?: return null
        if (distance.isFinite() && distance > 0.0) episode.walkedDistance += distance
        return if (episode.walkedDistance >= .5) recovered(now) else null
    }

    /** A safe teleport retains the failure episode and starts a fresh real-walk observation. */
    fun teleported() { state?.walkedDistance = 0.0 }

    fun recovered(now: Long): Snapshot? = state?.snapshot(now)?.also { state = null }
    fun snapshot(now: Long): Snapshot? = state?.snapshot(now)

    companion object {
        const val STALL_FAILURES = 3
        private const val REPORT_INTERVAL_MILLIS = 60_000L

        fun key(work: CivWorldWork): Key? = work.miningStep?.let {
            Key(work.buildingId, work.miningPlanVersion ?: CivsMinePlan.VERSION, work.cycle, it)
        }
    }
}
