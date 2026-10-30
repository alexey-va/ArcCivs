package ru.arc.civs

/** Select and reconcile one resident production or travel assignment. */
internal class CivsCitizenPlanning(
    private val runtime: CitizenRuntime,
    private val routesOwner: () -> CivsCitizenRoutes,
    private val couriersOwner: () -> CivsCitizenCouriers,
    private val leisureOwner: () -> CivsCitizenLeisure,
    private val workOwner: () -> CivsCitizenWork,
    private val logisticsOwner: () -> CivsCitizenLogistics,
    private val equipmentOwner: () -> CivsCitizenEquipment,
    private val lifeOwner: () -> CivsCitizenLife,
    private val courierPlan: (Settlement, CivWorker) -> CivCourierPlan?,
    private val workPlan: (Settlement, CivWorker) -> CivWorldWork?,
    private val workIdleReason: (Settlement, CivWorker) -> String,
    private val productionReady: (Settlement, CivWorker) -> Boolean,
    private val workInputsReady: (Settlement, CivWorker) -> Boolean,
    private val craftPlan: (Settlement, CivWorker) -> CivCraftJob?,
    private val workstation: ((Settlement, CivWorker) -> CivWorkstation?)?,
    private val remove: (CitizenActor, String) -> Unit,
) {
    private val routes get() = routesOwner()
    private val couriers get() = couriersOwner()
    private val leisure get() = leisureOwner()
    private val workRuntime get() = workOwner()
    private val logistics get() = logisticsOwner()
    private val equipment get() = equipmentOwner()
    private val life get() = lifeOwner()

    /** Native transfers keep their observed plan; an approaching courier never owns a producer's work. */
    fun currentPlan(actor: CitizenActor, city: Settlement, worker: CivWorker): CitizenPlan? {
        if (city.inventory != null && actor.plan.courier?.let { !it.carrying && it.cargo.sourceWorkerId != null } == true)
            return plan(city, worker)
        val outputPaused = workRuntime.outputFullPause(actor, city, worker) != null
        val acceptedDetour = (actor.workDetour != null || actor.mealVisit != null || actor.tavernService != null ||
            actor.outputReturn != null || actor.foodSource != null || outputPaused) &&
            worker.assignment == actor.plan.token.assignmentId && worker.completedCycles == actor.plan.token.completedCycles
        return if (actor.supplyPickup != null || logistics.collectedPending(actor) || acceptedDetour) actor.plan
            else citizenCurrentPlan(actor.plan, actor.cycle.phase) { plan(city, worker) }
    }

    fun reconcilePlan(actor: CitizenActor, nextPlan: CitizenPlan, city: Settlement): Boolean {
        val mineEpisode = actor.mineRouteEpisode.key
        val mineActive = nextPlan.type == BuildingType.QUARRY && nextPlan.courier == null &&
            !actor.sheltering && !actor.returningToWarehouse && actor.attacker == null && actor.foodSource == null &&
            actor.social.visit == null
        val discoveringMineWork = mineActive && nextPlan.idleReason == "searching-work" &&
            mineEpisode?.buildingId == nextPlan.token.assignmentId && mineEpisode?.cycle == nextPlan.token.completedCycles
        actor.mineRouteEpisode.retain(if (discoveringMineWork) mineEpisode else if (mineActive)
            nextPlan.worldWork?.let { CivsMineRouteEpisode.key(it) } else null)
        val restartVisual = actor.cycle.phase == CitizenPhase.CLOSED && nextPlan.worldWork?.autonomousVisual == true
        if (restartVisual || actor.plan != nextPlan && (!citizenPlansContinue(actor.plan, nextPlan) ||
                actor.plan.courier?.carrying != nextPlan.courier?.carrying)) {
            if (citizenStorageNeedsReset(actor.plan.storage, nextPlan.storage, actor.npc.location.point(),
                    CivsLogistics.maximumTransferDistance(city))) {
                remove(actor, "waiting")
                return false
            }
            couriers.releaseCourierSource(actor, runtime.residentObservedAt, nextPlan.courier?.carrying == true)
            life.stopMeal(actor)
            routes.cancelRoute(actor)
            leisure.stopCamp(actor, "plan-changed")
            actor.cycle.close()
            actor.workDetour = null
            actor.outputReturn = null
            actor.mentorVisit = null
            if (actor.plan.storage != nextPlan.storage || actor.plan.workplace != nextPlan.workplace) actor.targets.clear()
            actor.plan = nextPlan
            actor.cycle = nextPlan.cycle()
            if (nextPlan.worldWork == null && nextPlan.autonomousWork != null &&
                actor.autonomousVisualSession?.let { it.token == nextPlan.token && it.ready } == true)
                actor.cycle.returnWithObservedWork()
            actor.returningToWarehouse = false
            actor.wander = CitizenWander(actor.workerId)
            actor.equipmentItems = null
            equipment.setEquipment(actor.npc, nextPlan)
        } else actor.plan = nextPlan
        workRuntime.reconcileMineVisit(actor, city)
        return true
    }

    fun plan(city: Settlement, worker: CivWorker): CitizenPlan? {
        val resident = runtime.actors[worker.id]
        val retained = resident?.plan?.token
        val finishingCycle = retained?.assignmentId == worker.assignment && retained?.completedCycles == worker.completedCycles &&
            retained?.nextDeliveryAt == worker.nextDeliveryAt && resident?.cycle?.phase !in setOf(null, CitizenPhase.IDLE, CitizenPhase.CLOSED)
        if (!CivsResidents.isChild(worker) && (worker.life?.adultMinutes ?: 0) >= CivsResidentLife.lifespanMinutes(worker) &&
            worker.cargo == null && !finishingCycle) {
            val home = citizenHomeBuilding(city, worker) ?: return null
            // Finish an accepted cycle, then leave a visible idle boundary for the durable life owner.
            return CitizenPlan(CitizenToken(worker.assignment, worker.completedCycles, worker.nextDeliveryAt, null),
                worker.assignment?.let(city::building)?.control ?: home.entrance, null,
                worker.assignment?.let(city::building)?.type, 0, "old-age-retirement")
        }
        val courier = if (CivsResidents.isChild(worker)) null else courierPlan(city, worker)?.takeUnless {
            city.inventory != null && !it.carrying && it.cargo.sourceWorkerId != null
        }
        var base = citizenPlan(city, worker, courier) ?: return null
        if (city.autonomy?.paused == true) return base.copy(idleReason = "autonomy-paused")
        if (base.pendingStockDelivery != null) {
            runtime.actors[worker.id]?.autonomousVisualSession = null
            return base
        }
        val building = worker.assignment?.let(city::building)
        val actor = runtime.actors[worker.id]
        // A full output holder is a known pause, not a request for another mine/worksite search.
        if (actor != null && workRuntime.outputFullPause(actor, city, worker) != null)
            return base.copy(idleReason = "storage-full")
        val nativeAuto = building?.let { CivsAutonomousWorksites.supports(city, it) } == true && base.autonomousWork != null
        val session = if (nativeAuto && actor != null) {
            val required = CivsResidents.workDuration(worker, base.workMillis)
            actor.autonomousVisualSession?.takeIf { it.token == base.token && it.building == building }?.also {
                it.required(required)
            } ?: CivAutonomousVisualSession(base.token, requireNotNull(building), required).also {
                actor.autonomousVisualSession = it
            }
        } else null
        if (!nativeAuto) actor?.autonomousVisualSession = null
        if (session?.ready == true) {
            if (!citizenHasCapacity(city, base)) return base.copy(idleReason = "storage-full")
            // Complete the already observed native subaction before taking its economic trip.
            val currentActor = requireNotNull(actor)
            if (currentActor.plan.worldWork?.autonomousVisual == true && currentActor.cycle.phase in setOf(
                    CitizenPhase.TO_WORK, CitizenPhase.WORKING, CitizenPhase.READY, CitizenPhase.DELIVERING)) return currentActor.plan
            return base.copy(workMillis = 0, workplace = if (base.completeAtWorkplace)
                currentActor.plan.workplace ?: base.workplace else base.workplace)
        }
        if (base.type == BuildingType.BUILDER_HUT && base.idleReason in setOf("no-construction", "queued-construction") &&
            CivsRoadMaintenance.eligible(city)) {
            workPlan(city, worker)?.takeIf { it.upkeep != null }?.let { work ->
                base = base.copy(workplace = work.standing, workMillis = work.durationMillis, idleReason = null,
                    completeAtWorkplace = true, worldWork = work)
            }
        }
        if (base.type == BuildingType.BUILDER_HUT) {
            base.token.constructionId?.let { orderId ->
                val workplace = workRuntime.constructionWorkplace(city, orderId)
                if (workplace != null) base = base.copy(workplace = workplace)
                else if (base.idleReason in listOf(null, "placing")) return base.copy(idleReason = "construction-paused")
            }
        }
        if (!CivsAutonomy.legacyStock(city) && base.type in CivsCarpentry.workshops && base.idleReason == null) {
            val job = craftPlan(city, worker)
            base = if (job == null && base.type == BuildingType.SAWMILL) base.copy(idleReason = "waiting-orders")
                else if (job != null) base.copy(token = base.token.copy(craftJobId = job.id),
                    suppliesNeeded = !CivsEquipment.inputsReady(worker, job.inputs, emptyMap())) else base
        }
        if (workstation != null && !CivsAutonomy.legacyStock(city) && base.type in CivsWorkstations.TYPES &&
            base.courier == null && base.worldWork == null) {
            // A selected machine belongs to this exact cycle, including its supply/meal detours.
            val accepted = actor?.plan?.takeIf { it.token == base.token }
            val station = accepted?.workstation ?: if (accepted == null || actor.cycle.phase in setOf(
                    CitizenPhase.IDLE, CitizenPhase.PAUSED, CitizenPhase.CLOSED, CitizenPhase.TO_STORAGE))
                workstation(city, worker) else null
            if (station != null) base = base.copy(workplace = station.standing, workstation = station, completeAtWorkplace = false)
            else if (accepted == null || actor.cycle.phase in setOf(CitizenPhase.IDLE, CitizenPhase.PAUSED, CitizenPhase.CLOSED,
                    CitizenPhase.TO_STORAGE)) base = base.copy(idleReason = base.idleReason ?: "workstation-unavailable")
        }
        val admitted = if (base.idleReason == null && !productionReady(city, worker)) base.copy(idleReason = "waiting-inputs") else base
        val planned = if (CivsAutonomy.legacyStock(city) && admitted.type != BuildingType.FORESTER && !nativeAuto || admitted.courier != null ||
            admitted.type !in CivsWorksites.TYPES || admitted.idleReason != null) admitted else {
            val work = workPlan(city, worker)
            if (work == null) {
                val reason = workIdleReason(city, worker)
                if (nativeAuto && reason == "waiting-growth") base else base.copy(idleReason = reason)
            } else base.copy(workplace = work.standing, workMillis = work.durationMillis,
                completeAtWorkplace = true, worldWork = work, autonomousWork = if (work.autonomousVisual) null else base.autonomousWork)
        }
        return planned.copy(workMillis = if (nativeAuto && planned.worldWork == null && session != null)
            session.remainingMillis else CivsResidents.workDuration(worker, planned.workMillis),
            suppliesNeeded = !workInputsReady(city, worker))
    }
}
