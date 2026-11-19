package ru.arc.civs

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Monster
import ru.arc.paper.npc.PacketPlayerNpc
import java.util.UUID

internal data class CitizenOutputFullPause(val buildingId: UUID, val retryAt: Long, var firstRejection: Boolean = true)
internal data class CitizenBodyContact(val token: CitizenToken, val at: CitizenPoint, var walked: Double = 0.0)
internal data class CitizenFoodRouteFailure(val token: CitizenToken, val source: CivResidentFoodSource,
    val feet: CitizenPoint, var lastFailureAt: Long) {
    private data class StandingFailure(val attempts: Int, val at: Long)
    private val standings = linkedMapOf<CivPosition, StandingFailure>()
    private var firstRecoveryFailureAt: Long? = null
    private var lastRecoveryFailureAt: Long? = null

    fun record(standing: CivPosition, clock: Long) {
        val previous = standings[standing]
        // The existing food selector has at most six levels, four sides and one preferred point.
        if (previous != null || standings.size < 25)
            standings[standing] = StandingFailure(minOf(2, (previous?.attempts ?: 0) + 1), clock)
        lastFailureAt = clock
    }

    fun untried(standing: CivPosition): Boolean = standing !in standings

    fun retryAllowed(standing: CivPosition, clock: Long): Boolean = standings[standing]?.let {
        it.attempts < 2 || clock - it.at >= 15_000L
    } != false

    /** Unknown terrain may defer one stance, but never starts or renews relocation evidence. */
    fun recoveryDue(attemptAt: Long, clock: Long): Boolean {
        val previous = lastRecoveryFailureAt
        if (previous == null || attemptAt - previous !in 0L..30_000L) firstRecoveryFailureAt = clock
        lastRecoveryFailureAt = clock
        return clock - requireNotNull(firstRecoveryFailureAt) >= 60_000L
    }

    fun matches(actor: CitizenActor, current: CivResidentFoodSource, clock: Long): Boolean =
        token == actor.plan.token && current.copy(standing = source.standing) == source &&
            clock - lastFailureAt in 0L..30_000L && feet.distanceSquared(actor.npc.location.point()) <= .25
}

/** Mutable runtime identity and in-flight activity state for one admitted resident. */
internal class CitizenActor(val cityId: UUID, val workerId: UUID, val worldId: UUID, val npc: PacketPlayerNpc, var plan: CitizenPlan) {
    val instanceId: UUID = UUID.randomUUID()
    var cycle = plan.cycle()
    val targets = mutableMapOf<CivPosition, CivPosition>()
    var wander = CitizenWander(workerId)
    val animation = CitizenWorkAnimation()
    val footsteps = CitizenFootsteps()
    var route: CitizenRoute? = null
    /** A requested endpoint owns chunk demand before a native-ready route can be admitted. */
    var pendingChunkTarget: CivPosition? = null
    var courierReturnHint: CitizenCourierReturnHint? = null
    var courierInboundPrefix: CitizenCourierInboundPrefix? = null
    var collisionResident: UUID? = null
    var bodyContact: CitizenBodyContact? = null
    var residentYield: CitizenResidentYield? = null
    var yieldRetryAfter = 0L
    // Physical yielding grants no progress; only subsequent accepted route travel replenishes this allowance.
    var yieldProgress = 2.0
    var lookYaw: Float? = null
    var lookPitch = 0f
    var observedYaw: Float? = null
    var observedSleeping = false
    var navigationEpisode: String? = null
    var navigationEpisodeProgress = 0.0
    var navigationEpisodeTick = 0L
    var navigationReportAfter = 0L
    var navigationSuppressed = 0
    val mineRouteEpisode = CivsMineRouteEpisode()
    var mineVisit: CitizenMineVisit? = null
    // Real shaft access survives route/cycle replacement until the resident physically returns to the surface.
    var mineAccess: CivBuilding? = null
    var mineAccessRequest: CitizenMineAccessRequest? = null
    var mineContendedAt: Long? = null
    var mineYieldPin: CitizenToken? = null
    var turnOrigin: CitizenPoint? = null
    var turnSinceTick = 0L
    var turnAmount = 0f
    var workHandoff: CitizenWorkHandoff? = null
    var autonomousVisualSession: CivAutonomousVisualSession? = null
    var reportedFailure = false
    var damageAfter = 0L
    var harmLogAfter = 0L
    var reportHarmCompletion = false
    var terrainObstructedAt: Long? = null
    var arrivalWait: CitizenArrivalWait? = null
    var equipmentItems: List<CivResidentItem?>? = null
    var equipmentType: BuildingType? = null
    var careItem: Material? = null
    var sheltering = false
    var returningToWarehouse = false
    val social = CitizenCampRoutine(workerId)
    var idleNap: CitizenIdleNap? = null
    var nextIdleNapAt: Long? = null
    var sleeping = false
    var sleepOrigin: Location? = null
    var sleepPose: CitizenSleepPose? = null
    var shelter: CivPosition? = null
    var shelterPose: CitizenSleepPose? = null
    var shelterHome: UUID? = null
    var shelterFeet: Location? = null
    var shelterLastReason: String? = null
    var shelterSearchIndex = 0
    val shelterHomeSearch = mutableMapOf<UUID, Int>()
    val nightHomeProgress = CitizenNightHomeProgress()
    var nextPlayAt = 0L
    var supplyPickup: CitizenSupplyPickup? = null
    var outputReturn: UUID? = null
    var outputFullPause: CitizenOutputFullPause? = null
    var supplyFailure: Pair<UUID, String>? = null
    var transferRemark: CitizenTransferRemark? = null
    var lastCourierRemark: Pair<UUID, UUID>? = null
    var supplyRetryAt = 0L
    var collectionCycle: Long? = null
    var collectionRetryAt = 0L
    var collectionSeek: UUID? = null
    var collectionHome: CivPosition? = null
    var foodSource: UUID? = null
    var foodPickupSource: CivResidentFoodSource? = null
    var foodRouteFailure: CitizenFoodRouteFailure? = null
    var tavernService: CitizenTavernService? = null
    var tavernServiceFailure: CitizenTavernServiceFailure? = null
    var workDetour: CitizenToken? = null
    var mealVisit: CitizenMealVisit? = null
    var mealVenueRetryAt = 0L
    var mealOutcome: Pair<String, Long>? = null
    var diningSearchIndex = 0
    var mentorVisit: CitizenMentorVisit? = null
    var lifeRetryAt = 0L
    var restVisit: Int? = null
    var nextRecoveryAt: Long? = null
    var attacker: Monster? = null
    var alarmSheltering = false
    var patrolTarget: CivPosition? = null
    var patrolIndex = 0
    var patrolAfter = 0L
    var combatUntil = 0L
    var combatDestination: CivPosition? = null
    var combatStatus: String? = null
    var combatRouteAfter = 0L
    var combatAttackAfter = 0L
    var courierPickupStartedAt: Long? = null
    var courierSourceObserved: CitizenPoint? = null
    var courierTargetAfter = 0L
    var courierRetry = CitizenRetry()
    var courierRetryAfter = 0L

    fun occupiedBody() = idleNap?.bounds ?: mealVisit?.seat?.takeIf { npc.isSeated }?.bounds
        ?: sleepPose?.takeIf { sleeping }?.bounds ?: citizenStandingBounds(npc.location, npc.scale)
}
