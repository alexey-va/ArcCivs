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

    /** Import only paid, contiguous actions of the same narrow spine; no ordinal or gallery translation. */
    fun retainedSpine(building: CivBuilding, previous: CivMinePlanState?): Map<String, Long> {
        if (previous?.version != 5) return emptyMap()
        val current = requireNotNull(CivsMinePlan.forBuilding(building, 6))
        val old = requireNotNull(current.forVersion(5))
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
        if (previous?.version != 5 || work.miningPlanVersion != 6) return emptyList()
        val plan = requireNotNull(CivsMinePlan.forBuilding(building, 6))
        val access = requireNotNull(work.miningAccessSections)
        val ordinal = requireNotNull(work.miningOrdinal)
        require(plan.sectionValid(access) && ordinal in plan.steps.indices && plan.section(ordinal) == "spine")
        require(plan.sections.keys.all { it == "spine" || plan.sectionCount(it, 0, access) == 0L })
        val proven = retainedSpine(building, previous)["spine"] ?: 0
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
            state?.v5Proof?.detached())
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
                witness.sectionSteps == null && witness.seals == null && witness.excavation == null && witness.v5Proof == null && witness.legacyProgress != null
            val plan = CivsMinePlan.forBuilding(old, previous.version) ?: return@all false
            fun bounded(version: Int, prefix: Long?) = prefix == null || previous.version >= version + 1 &&
                CivsMinePlan.forBuilding(old, version)?.steps?.size?.let { prefix in 0..it.toLong() } == true
            previous.legacyStep == witness.legacyProgress && previous.nextStep in 0..plan.steps.size.toLong() &&
                bounded(2, previous.v2Step) && bounded(3, previous.v3Step) && bounded(4, previous.v4Step) && valid(plan, previous, old)
        }
    }

    fun detached(state: CivMinePlanState): CivMinePlanState = state.copy(
        sectionSteps = state.sectionSteps?.toMap(), seals = state.seals?.toMap(), v5Proof = state.v5Proof?.detached(), levels = state.levels?.map {
            it.copy(building = it.building.copy(transit = it.building.transit?.toList()),
                sectionSteps = it.sectionSteps?.toMap(), seals = it.seals?.toMap(), v5Proof = it.v5Proof?.detached())
        })

    fun priorStanding(building: CivBuilding, state: CivMinePlanState?, world: CivPosition): Boolean {
        fun known(mine: CivBuilding, old: CivMinePlanState?, legacyProgress: Long?): Boolean {
            val local = CivsMinePlan.localPoint(mine, world)
            val versions = listOf(2 to (old?.takeIf { it.version == 2 }?.nextStep ?: old?.v2Step),
                3 to (old?.takeIf { it.version == 3 }?.nextStep ?: old?.v3Step),
                4 to (old?.takeIf { it.version == 4 }?.nextStep ?: old?.v4Step),
                5 to (old?.takeIf { it.version == 5 }?.nextStep ?: old?.v5Proof?.nextStep ?: old?.v5Step),
                6 to old?.takeIf { it.version == 6 }?.nextStep)
            return versions.any { (version, prefix) -> prefix != null && CivsMinePlan.forBuilding(mine, version)?.builtStanding(
                local, prefix, if (version == old?.version) old?.sectionSteps.orEmpty()
                else if (version == 5) old?.v5Proof?.sectionSteps.orEmpty() else emptyMap()) == true } ||
                CivsMinePlan.forBuilding(mine)?.legacyCompleted(legacyProgress ?: 0)?.let { cells ->
                    fun open(p: CivPosition) = p in cells && cells[p].let { it == null || it.startsWith("minecraft:ladder[") || it == "minecraft:torch" }
                    open(local) && open(local.copy(y = local.y + 1))
                } == true
        }
        val predecessor = if (state?.version != CivsMinePlan.VERSION) state else v5State(state)
        return predecessor?.let { known(building, it, it.legacyStep) } == true || state?.levels.orEmpty().any {
            known(it.building, it.state(), it.legacyProgress)
        }
    }

    fun valid(plan: CivsMinePlan, state: CivMinePlanState, building: CivBuilding? = null): Boolean {
        if (state.version != plan.version || state.nextStep !in 0..plan.steps.size.toLong() || state.v5Proof != null && state.version != 6 ||
            state.version == 6 && state.v5Step != null && state.v5Proof == null) return false
        val predecessor = state.v5Proof?.let { v5State(state) ?: return false }
        if (predecessor != null && !valid(requireNotNull(plan.forVersion(5)), predecessor, building)) return false
        if (state.sectionSteps == null) return state.excavation == null && state.seals == null && state.v5Step == null && state.v5Proof == null
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
        var excavation = old?.excavation ?: previous?.takeIf { version == 6 && it.version == 5 }?.excavation
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
                if (cell.after == "minecraft:air") excavation = work.miningExcavation?.takeIf { it == excavation } ?: local
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
            if (version == 6 && previous?.version == 5) CivMineV5Proof(previous.nextStep,
                previous.sectionSteps?.toMap(), previous.excavation, previous.seals?.toMap()) else previous?.v5Proof?.detached())
            .also { require(valid(plan, it, building) && validLevels(building, it)) }
    }
}

/** Native authority retained for new ordinary mine receipts, including restart and partial application. */
data class CivMineReceipt(val buildingBefore: CivBuilding, val planBefore: CivMinePlanState?,
    val legacyBefore: Long?, val work: CivWorldWork) {
    fun before(after: Settlement): Settlement {
        val key = buildingBefore.id.toString()
        return after.copy(minePlans = if (planBefore == null) after.minePlans?.minus(key)
            else after.minePlans.orEmpty() + (key to planBefore),
            mineProgress = if (legacyBefore == null) after.mineProgress?.minus(key)
            else after.mineProgress.orEmpty() + (key to legacyBefore))
    }

    fun validated(after: Settlement) {
        require(buildingBefore != null && work != null && after.building(buildingBefore.id) == buildingBefore &&
            buildingBefore.type == BuildingType.QUARRY && work.buildingId == buildingBefore.id && !work.autonomousVisual &&
            work.miningSections != null && work.miningPlanVersion in 5..CivsMinePlan.VERSION)
        require(after.minePlans?.get(buildingBefore.id.toString()) == CivsMineProgress.after(buildingBefore, planBefore, legacyBefore, work))
    }
}
