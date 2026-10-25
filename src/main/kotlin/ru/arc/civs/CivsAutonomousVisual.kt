package ru.arc.civs

/** A native subaction advances scenery, never the worker's economic token or items. */
data class CivAutonomousVisualReceipt(
    val workerBefore: CivWorker,
    val stockBefore: Stock,
    val autonomyBefore: CivAutonomy,
    val mineProgressBefore: Map<String, Long>?,
    val minePlansBefore: Map<String, CivMinePlanState>?,
    val work: CivWorldWork,
    val acceptedAt: Long,
    val cityId: java.util.UUID,
    val worldId: java.util.UUID,
    val ownerId: java.util.UUID,
    val buildingBefore: CivBuilding,
) {
    fun before(after: Settlement): Settlement = after.copy(
        id = cityId, worldId = worldId, owner = ownerId,
        workers = after.workers.map { if (it.id == workerBefore.id) workerBefore else it },
        stock = stockBefore, autonomy = autonomyBefore,
        mineProgress = mineProgressBefore, minePlans = minePlansBefore, revision = after.revision - 1,
    )

    fun validated(after: Settlement) {
        require(acceptedAt >= 0 && work.workerId == workerBefore.id && work.autonomousVisual)
        require(cityId != null && worldId != null && ownerId != null && buildingBefore != null &&
            after.building(work.buildingId) == buildingBefore)
        require(CivsRules.finishAutonomousVisual(before(after), work) == after)
    }
}

/** Runtime-only observed work. Restart/death can lose unfinished work, never earn offline time. */
internal class CivAutonomousVisualSession(
    val token: CitizenToken,
    val building: CivBuilding,
    requiredMillis: Long,
) {
    var requiredMillis: Long = requiredMillis
        private set
    var workedMillis: Long = 0
        private set
    val ready: Boolean get() = workedMillis >= requiredMillis
    val remainingMillis: Long get() = (requiredMillis - workedMillis).coerceAtLeast(0)

    fun observe(millis: Long) {
        require(millis in 0..2_500)
        workedMillis += minOf(millis, remainingMillis)
    }

    init { require(requiredMillis > 0) }

    fun required(millis: Long) {
        require(millis > 0)
        requiredMillis = millis
        workedMillis = minOf(workedMillis, millis)
    }
}
