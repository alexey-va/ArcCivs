package ru.arc.civs

/** Sparse execution proof over a frozen plan version; no skipped ordinal is paid work. */
internal object CivsMineProgress {
    const val MAXIMUM_SEALS = 512

    /** Same-level V5 proof is independent of the current cursor and of lower building levels. */
    fun v5State(state: CivMinePlanState?): CivMinePlanState? = state?.let { saved ->
        if (saved.version == 5) saved else saved.v5Proof?.let { old ->
            CivMinePlanState(5, old.nextStep, saved.legacyStep, saved.v2Step, saved.v3Step, saved.v4Step,
                old.sectionSteps, old.excavation, old.seals, saved.v5Step, saved.levels)
        }
    }

    private fun CivMineV5Proof.detached() = copy(sectionSteps = sectionSteps?.toMap(), seals = seals?.toMap())
    private fun CivMineV6Proof.detached() = copy(sectionSteps = sectionSteps?.toMap(), seals = seals?.toMap())

    fun v6State(state: CivMinePlanState?): CivMinePlanState? = state?.let { saved ->
        if (saved.version == 6) saved else saved.v6Proof?.let { old ->
            CivMinePlanState(6, old.nextStep, saved.legacyStep, saved.v2Step, saved.v3Step, saved.v4Step,
                old.sectionSteps, old.excavation, old.seals, saved.v5Step, saved.levels, saved.v5Proof)
        }
    }

    /** Import only paid, contiguous actions of the same narrow spine; no ordinal or gallery translation. */
    fun retainedSpine(building: CivBuilding, previous: CivMinePlanState?, version: Int = CivsMinePlan.VERSION): Map<String, Long> {
        if (previous == null || previous.version !in 5 until version) return emptyMap()
        val current = requireNotNull(CivsMinePlan.forBuilding(building, version))
        val old = requireNotNull(current.forVersion(previous.version))
        require(valid(old, previous, building))
        val paid = old.sections.getValue("spine").takeWhile {
            old.completed(it, previous.nextStep, previous.sectionSteps.orEmpty())
        }.associateBy { old.steps[it] }
        val count = current.sections.getValue("spine").takeWhile { ordinal ->
            paid[current.steps[ordinal]]?.let { before ->
                old.section(before) == current.section(ordinal) &&
                    old.expectedAfter(before, building.rotation) == current.expectedAfter(ordinal, building.rotation)
            } == true
        }.size
        return if (count == 0) emptyMap() else mapOf("spine" to count.toLong())
    }

    /** Additional initial access is never trusted as paid: its exact native cells must pass the receipt gate. */
    fun initialSpineAccess(building: CivBuilding, previous: CivMinePlanState?, work: CivWorldWork): List<Int> {
        val version = work.miningPlanVersion ?: return emptyList()
        if (previous == null || previous.version !in 5 until version) return emptyList()
        val plan = requireNotNull(CivsMinePlan.forBuilding(building, version))
        val access = requireNotNull(work.miningAccessSections)
        val ordinal = requireNotNull(work.miningOrdinal)
        require(plan.sectionValid(access) && ordinal in plan.steps.indices && plan.section(ordinal) == "spine")
        require(plan.sections.keys.all { it == "spine" || plan.sectionCount(it, 0, access) == 0L })
        val proven = retainedSpine(building, previous, version)["spine"] ?: 0
        val spine = plan.sections.getValue("spine")
        val claimed = plan.sectionCount("spine", 0, access)
        require(claimed in proven..spine.size.toLong() && claimed == spine.indexOf(ordinal).toLong())
        return spine.subList(proven.toInt(), claimed.toInt())
    }

    fun key(point: CivPosition) = "${point.x},${point.y},${point.z}"
    private fun point(encoded: String): CivPosition? = encoded.split(',').takeIf { it.size == 3 }?.let { values ->
        val numbers = values.map { it.toIntOrNull() ?: return null }
        CivPosition(numbers[0], numbers[1], numbers[2]).takeIf { key(it) == encoded }
    }

    fun upgraded(previous: CivBuilding, finished: CivBuilding, state: CivMinePlanState?, legacy: Long?): CivMinePlanState? {
        if (state == null && legacy == null) return null
        val witness = CivMineLevelWitness(previous.copy(transit = previous.transit?.toList()), state?.version,
            state?.nextStep ?: 0, state?.legacyStep, state?.v2Step, state?.v3Step, state?.v4Step,
            state?.sectionSteps?.toMap(), state?.excavation, state?.seals?.toMap(), state?.v5Step, legacy,
            state?.v5Proof?.detached(), state?.v6Proof?.detached())
        val plan = requireNotNull(CivsMinePlan.forBuilding(finished))
        val excavation = state?.excavation?.let { old -> CivsMinePlan.localPoint(finished,
            CivsWorksiteLayouts.point(previous, old)) }?.takeIf { it in plan.positions }
        return CivMinePlanState(CivsMinePlan.VERSION, 0, sectionSteps = emptyMap(), excavation = excavation,
            levels = state?.levels.orEmpty() + witness)
    }

    fun validLevels(building: CivBuilding, state: CivMinePlanState): Boolean {
        val levels = state.levels ?: return true
        if (state.version !in 5..CivsMinePlan.VERSION || state.sectionSteps == null || levels.size !in 1..2 ||
            levels.map { it.building.level }.distinct().size != levels.size) return false
        return levels.all { witness ->
            val old = witness.building
            if (old.id != building.id || old.type != BuildingType.QUARRY || old.level !in 1 until building.level ||
                old.phase != BuildingPhase.ACTIVE || old.rotation !in setOf(0, 90, 180, 270) ||
                witness.legacyProgress?.let { it !in 0..100_000 } == true) return@all false
            val previous = witness.state() ?: return@all witness.nextStep == 0L && witness.legacyStep == null &&
                witness.v2Step == null && witness.v3Step == null && witness.v4Step == null && witness.v5Step == null &&
                witness.sectionSteps == null && witness.seals == null && witness.excavation == null && witness.v5Proof == null &&
                witness.v6Proof == null && witness.legacyProgress != null
            val plan = CivsMinePlan.forBuilding(old, previous.version) ?: return@all false
            fun bounded(version: Int, prefix: Long?) = prefix == null || previous.version >= version + 1 &&
                CivsMinePlan.forBuilding(old, version)?.steps?.size?.let { prefix in 0..it.toLong() } == true
            previous.legacyStep == witness.legacyProgress && previous.nextStep in 0..plan.steps.size.toLong() &&
                bounded(2, previous.v2Step) && bounded(3, previous.v3Step) && bounded(4, previous.v4Step) && valid(plan, previous, old)
        }
    }

    fun detached(state: CivMinePlanState): CivMinePlanState = state.copy(
        sectionSteps = state.sectionSteps?.toMap(), seals = state.seals?.toMap(), v5Proof = state.v5Proof?.detached(),
        v6Proof = state.v6Proof?.detached(), levels = state.levels?.map {
            it.copy(building = it.building.copy(transit = it.building.transit?.toList()),
                sectionSteps = it.sectionSteps?.toMap(), seals = it.seals?.toMap(), v5Proof = it.v5Proof?.detached(),
                v6Proof = it.v6Proof?.detached())
        })

    fun priorStanding(building: CivBuilding, state: CivMinePlanState?, world: CivPosition): Boolean {
        fun known(mine: CivBuilding, old: CivMinePlanState?, legacyProgress: Long?): Boolean {
            val local = CivsMinePlan.localPoint(mine, world)
            val versions = listOf(2 to (old?.takeIf { it.version == 2 }?.nextStep ?: old?.v2Step),
                3 to (old?.takeIf { it.version == 3 }?.nextStep ?: old?.v3Step),
                4 to (old?.takeIf { it.version == 4 }?.nextStep ?: old?.v4Step),
                5 to (old?.takeIf { it.version == 5 }?.nextStep ?: old?.v5Proof?.nextStep ?: old?.v5Step),
                6 to (old?.takeIf { it.version == 6 }?.nextStep ?: old?.v6Proof?.nextStep),
                7 to old?.takeIf { it.version == 7 }?.nextStep)
            return versions.any { (version, prefix) -> prefix != null && CivsMinePlan.forBuilding(mine, version)?.builtStanding(
                local, prefix, if (version == old?.version) old?.sectionSteps.orEmpty()
                else if (version == 5) old?.v5Proof?.sectionSteps.orEmpty()
                else if (version == 6) old?.v6Proof?.sectionSteps.orEmpty() else emptyMap()) == true } ||
                CivsMinePlan.forBuilding(mine)?.legacyCompleted(legacyProgress ?: 0)?.let { cells ->
                    fun open(p: CivPosition) = p in cells && cells[p].let { it == null || it.startsWith("minecraft:ladder[") || it == "minecraft:torch" }
                    open(local) && open(local.copy(y = local.y + 1))
                } == true
        }
        val predecessor = if (state?.version != CivsMinePlan.VERSION) state else v6State(state) ?: v5State(state)
        return predecessor?.let { known(building, it, it.legacyStep) } == true || state?.levels.orEmpty().any {
            known(it.building, it.state(), it.legacyProgress)
        }
    }

    fun valid(plan: CivsMinePlan, state: CivMinePlanState, building: CivBuilding? = null): Boolean {
        if (state.version != plan.version || state.nextStep !in 0..plan.steps.size.toLong() ||
            state.v5Proof != null && state.version !in 6..7 || state.v6Proof != null && state.version != 7 ||
            state.version >= 6 && state.v5Step != null && state.v5Proof == null) return false
        val predecessor = state.v5Proof?.let { v5State(state) ?: return false }
        if (predecessor != null && !valid(requireNotNull(plan.forVersion(5)), predecessor, building)) return false
        val v6 = state.v6Proof?.let { v6State(state) ?: return false }
        if (v6 != null && !valid(requireNotNull(plan.forVersion(6)), v6, building)) return false
        if (state.sectionSteps == null) return state.excavation == null && state.seals == null && state.v5Step == null &&
            state.v5Proof == null && state.v6Proof == null
        if (state.version !in 5..CivsMinePlan.VERSION || !plan.sectionValid(state.sectionSteps)) return false
        val normalized = plan.normalizedProgress(state.nextStep, state.sectionSteps)
        val sealPositions = building?.let { CivsMinePlan.sealPositions(it, plan.version) } ?: plan.plannedCuts
        return normalized.first == state.nextStep && normalized.second == state.sectionSteps &&
            (state.v5Step == null || state.v5Step in 0..(predecessor?.nextStep ?: state.nextStep)) &&
            (state.excavation == null || state.excavation in plan.positions) &&
            state.seals.orEmpty().let { seals -> seals.size <= MAXIMUM_SEALS && seals.all { (key, data) ->
                point(key) in sealPositions && data == "minecraft:cobblestone"
            } }
    }

    fun after(building: CivBuilding, previous: CivMinePlanState?, legacy: Long?, work: CivWorldWork): CivMinePlanState {
        val version = requireNotNull(work.miningPlanVersion)
        require(previous == null || version >= previous.version)
        val next = requireNotNull(work.miningStep)
        val plan = requireNotNull(CivsMinePlan.forBuilding(building, version))
        val old = previous?.takeIf { it.version == version }
        val prefix = old?.nextStep ?: 0
        require(next in prefix..plan.steps.size.toLong())
        val sections = work.miningSections
        initialSpineAccess(building, previous, work)
        var normalized: Map<String, Long>? = null
        var excavation = old?.excavation ?: previous?.takeIf { version >= 6 && it.version in 5 until version }?.excavation
        var seals = old?.seals
        if (sections != null) {
            require(version in 5..CivsMinePlan.VERSION && work.changes.size == 1 && plan.sectionValid(sections))
            val access = requireNotNull(work.miningAccessSections)
            require(plan.sectionValid(access) && work.miningOrdinal?.let { it in plan.steps.indices } == true)
            val accessPrefix = requireNotNull(work.miningAccessStep)
            require(plan.normalizedProgress(prefix, access).first == accessPrefix)
            require(plan.normalizedProgress(prefix, sections).first == next)
            require(plan.sections.keys.all { key ->
                plan.sectionCount(key, prefix, old?.sectionSteps.orEmpty()) <= plan.sectionCount(key, accessPrefix, access) &&
                    plan.sectionCount(key, accessPrefix, access) <= plan.sectionCount(key, next, sections)
            })
            val ordinal = requireNotNull(work.miningOrdinal)
            val cell = work.changes.single()
            val clearingLava = CivsMineSafety.clearingLava(work)
            if (cell.before.substringBefore('[') == "minecraft:lava" && cell.after == "minecraft:air")
                require(clearingLava && CivsMinePlan.localPoint(building, cell.position) in plan.positions)
            val completes = work.status == "digging-mine" && !work.miningGenerator &&
                cell.position == CivsWorksiteLayouts.point(building, plan.steps[ordinal].point) &&
                cell.after == plan.expectedAfter(ordinal, building.rotation)
            val allowed = if (completes) plan.advanceSection(ordinal, access) else access
            require(plan.sections.keys.all { key ->
                plan.sectionCount(key, accessPrefix, sections) <= plan.sectionCount(key, accessPrefix, allowed)
            })
            normalized = plan.normalizedProgress(prefix, sections).second
            val proof = seals.orEmpty().toMutableMap()
            val futurePositions = CivsMinePlan.futureSealPositions(building, version)
            work.changes.forEach { cell ->
                val local = CivsMinePlan.localPoint(building, cell.position)
                val key = key(local)
                if (proof[key] == cell.before) proof.remove(key)
                if (cell.after == "minecraft:air" && !clearingLava) excavation = work.miningExcavation?.takeIf { it == excavation } ?: local
                if (work.status == "securing-mine" && cell.after == "minecraft:cobblestone" && (local in futurePositions || plan.pendingMutationAt(local, next, requireNotNull(normalized)))) proof[key] = cell.after
            }
            // A cut may already be AIR when its exact native section is inspected.
            // Retire that now-useless proof with this real receipt, without keeping
            // an unbounded history or scanning unrelated plan steps.
            proof.entries.removeIf { (encoded, _) -> point(encoded).let { p -> p !in futurePositions &&
                (p == null || !plan.pendingMutationAt(p, next, requireNotNull(normalized))) } }
            require(proof.size <= MAXIMUM_SEALS)
            seals = proof.takeIf { it.isNotEmpty() }
        } else require(work.miningAccessSections == null && work.miningOrdinal == null && old?.sectionSteps == null)
        return CivMinePlanState(version, next, previous?.legacyStep ?: legacy,
            previous?.takeIf { it.version == 2 && version >= 3 }?.nextStep ?: previous?.v2Step,
            previous?.takeIf { it.version == 3 && version >= 4 }?.nextStep ?: previous?.v3Step,
            previous?.takeIf { it.version == 4 && version >= 5 }?.nextStep ?: previous?.v4Step,
            normalized, excavation, seals,
            previous?.takeIf { it.version == 5 && version == 5 && it.sectionSteps == null && sections != null }?.nextStep ?: previous?.v5Step,
            previous?.levels,
            if (version >= 6 && previous?.version == 5) CivMineV5Proof(previous.nextStep,
                previous.sectionSteps?.toMap(), previous.excavation, previous.seals?.toMap()) else previous?.v5Proof?.detached(),
            if (version == 7 && previous?.version == 6) CivMineV6Proof(previous.nextStep,
                previous.sectionSteps?.toMap(), previous.excavation, previous.seals?.toMap()) else previous?.v6Proof?.detached())
            .also { require(valid(plan, it, building) && validLevels(building, it)) }
    }
}

/** Native authority retained for new ordinary mine receipts, including restart and partial application. */
data class CivMineReceipt(val buildingBefore: CivBuilding, val planBefore: CivMinePlanState?,
    val legacyBefore: Long?, val work: CivWorldWork,
    /** Null is the original paid excavation proof; a fallback must retain its exact item/tool owner. */
    val workerBefore: CivWorker? = null) {
    fun before(after: Settlement): Settlement {
        val key = buildingBefore.id.toString()
        val before = after.copy(minePlans = if (planBefore == null) after.minePlans?.minus(key)
            else after.minePlans.orEmpty() + (key to planBefore),
            mineProgress = if (legacyBefore == null) after.mineProgress?.minus(key)
            else after.mineProgress.orEmpty() + (key to legacyBefore))
        return workerBefore?.let { worker -> before.copy(
            workers = before.workers.map { if (it.id == worker.id) worker else it }, revision = after.revision - 1) } ?: before
    }

    fun validated(after: Settlement) {
        workerBefore?.let { worker ->
            val before = before(after)
            require(after.building(buildingBefore.id) == buildingBefore && worker.id == work.workerId &&
                CivsMineFallback.eligible(before, buildingBefore, worker) && CivsMineFallback.matches(work))
            val at = after.workers.single { it.id == worker.id }.nextDeliveryAt - 1_000
            require(at >= worker.nextDeliveryAt && CivsRules.finishWorldWork(before, work, at) == after)
            return
        }
        require(buildingBefore != null && work != null && after.building(buildingBefore.id) == buildingBefore &&
            buildingBefore.type == BuildingType.QUARRY && work.buildingId == buildingBefore.id && !work.autonomousVisual &&
            work.miningSections != null && work.miningPlanVersion in 5..CivsMinePlan.VERSION)
        require(after.minePlans?.get(buildingBefore.id.toString()) == CivsMineProgress.after(buildingBefore, planBefore, legacyBefore, work))
    }
}
