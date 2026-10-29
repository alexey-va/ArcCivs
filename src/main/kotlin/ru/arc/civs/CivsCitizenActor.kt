package ru.arc.civs

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Monster
import ru.arc.paper.npc.PacketPlayerNpc
import java.util.UUID

/** Mutable runtime identity and in-flight activity state for one admitted resident. */
internal class CitizenActor(val cityId: UUID, val workerId: UUID, val worldId: UUID, val npc: PacketPlayerNpc, var plan: CitizenPlan) {
    val instanceId: UUID = UUID.randomUUID()
    var cycle = plan.cycle()
    val targets = mutableMapOf<CivPosition, CivPosition>()
    var wander = CitizenWander(workerId)
    val animation = CitizenWorkAnimation()
    val footsteps = CitizenFootsteps()
    var route: CitizenRoute? = null
    var collisionResident: UUID? = null
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
    var sleeping = false
    var sleepOrigin: Location? = null
    var sleepPose: CitizenSleepPose? = null
    var shelter: CivPosition? = null
    var shelterPose: CitizenSleepPose? = null
    var shelterSearchIndex = 0
    var nextPlayAt = 0L
    var supplyPickup: CitizenSupplyPickup? = null
    var outputReturn: UUID? = null
    var supplyFailure: Pair<UUID, String>? = null
    var supplyRetryAt = 0L
    var collectionCycle: Long? = null
    var collectionRetryAt = 0L
    var collectionSeek: UUID? = null
    var collectionHome: CivPosition? = null
    var foodSource: UUID? = null
    var foodPickupSource: CivResidentFoodSource? = null
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
}
