package ru.arc.civs

import java.util.UUID

/** An explicit, short-lived owner-thread diagnostic run. Never part of the saved city. */
internal class CivsLabGrowth(private val clock: () -> Long) {
    private data class Run(val id: UUID, val cityId: UUID, val worldId: UUID, val owner: UUID,
        val startedAt: Long, val expiresAt: Long) {
        fun matches(city: Settlement) = city.id == cityId && city.worldId == worldId && city.owner == owner
    }

    private var run: Run? = null

    private fun current(): Run? {
        val selected = run ?: return null
        return selected.takeIf { clock() in it.startedAt until it.expiresAt }
    }

    fun cityId(): UUID? = current()?.cityId

    fun active(city: Settlement): Boolean = current()?.matches(city) == true

    fun start(city: Settlement): Boolean {
        current()?.let {
            if (!it.matches(city)) throw CivsRejection("lab-growth-other-city-active")
            return false
        }
        val at = clock()
        run = Run(UUID.randomUUID(), city.id, city.worldId, city.owner, at, Math.addExact(at, RUN_MILLIS))
        return true
    }

    fun stop(cityId: UUID): Boolean {
        if (run?.cityId != cityId) return false
        run = null
        return true
    }

    fun clear() { run = null }

    fun debug(city: Settlement): Map<String, Any?> {
        val active = current()
        val selected = active?.takeIf { it.matches(city) }
        return mapOf("run" to selected?.id, "started_at" to selected?.startedAt, "expires_at" to selected?.expiresAt,
            "selected_city" to active?.cityId, "work_rate" to WORK_RATE)
    }

    companion object {
        private const val RUN_MILLIS = 600_000L
        private const val WORK_RATE = 4

        fun workMillis(base: Long): Long = minOf(base, maxOf(500L, base / WORK_RATE))

        fun paidConstruction(city: Settlement, orderId: UUID?): Boolean =
            orderId != null && city.autonomy?.paused == false && city.inventory?.mode == CivStorageMode.PHYSICAL &&
                city.construction?.let { it.id == orderId && it.payment != null } == true
    }
}
