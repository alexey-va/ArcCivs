package ru.arc.civs

/** Sparse execution proof over the immutable V5 step list; no skipped ordinal is paid work. */
internal object CivsMineProgress {
    const val MAXIMUM_SEALS = 512

    fun key(point: CivPosition) = "${point.x},${point.y},${point.z}"
    private fun point(encoded: String): CivPosition? = encoded.split(',').takeIf { it.size == 3 }?.let { values ->
        val numbers = values.map { it.toIntOrNull() ?: return null }
        CivPosition(numbers[0], numbers[1], numbers[2]).takeIf { key(it) == encoded }
    }

    fun upgraded(previous: CivBuilding, finished: CivBuilding, state: CivMinePlanState?, legacy: Long?): CivMinePlanState? {
        if (state == null && legacy == null) return null
        val witness = CivMineLevelWitness(previous.copy(transit = previous.transit?.toList()), state?.version,
            state?.nextStep ?: 0, state?.legacyStep, state?.v2Step, state?.v3Step, state?.v4Step,
            state?.sectionSteps?.toMap(), state?.excavation, state?.seals?.toMap(), state?.v5Step, legacy)
        val plan = requireNotNull(CivsMinePlan.forBuilding(finished))
        val excavation = state?.excavation?.let { old -> CivsMinePlan.localPoint(finished,
            CivsWorksiteLayouts.point(previous, old)) }?.takeIf { it in plan.positions }
        return CivMinePlanState(CivsMinePlan.VERSION, 0, sectionSteps = emptyMap(), excavation = excavation,
            levels = state?.levels.orEmpty() + witness)
    }

    fun validLevels(building: CivBuilding, state: CivMinePlanState): Boolean {
        val levels = state.levels ?: return true
        if (state.version != CivsMinePlan.VERSION || state.sectionSteps == null || levels.size !in 1..2 ||
            levels.map { it.building.level }.distinct().size != levels.size) return false
        return levels.all { witness ->
            val old = witness.building
            if (old.id != building.id || old.type != BuildingType.QUARRY || old.level !in 1 until building.level ||
                old.phase != BuildingPhase.ACTIVE || old.rotation !in setOf(0, 90, 180, 270) ||
                witness.legacyProgress?.let { it !in 0..100_000 } == true) return@all false
            val previous = witness.state() ?: return@all witness.nextStep == 0L && witness.legacyStep == null &&
                witness.v2Step == null && witness.v3Step == null && witness.v4Step == null && witness.v5Step == null &&
                witness.sectionSteps == null && witness.seals == null && witness.excavation == null && witness.legacyProgress != null
            val plan = CivsMinePlan.forBuilding(old, previous.version) ?: return@all false
            fun bounded(version: Int, prefix: Long?) = prefix == null || previous.version >= version + 1 &&
                CivsMinePlan.forBuilding(old, version)?.steps?.size?.let { prefix in 0..it.toLong() } == true
            previous.legacyStep == witness.legacyProgress && previous.nextStep in 0..plan.steps.size.toLong() &&
                bounded(2, previous.v2Step) && bounded(3, previous.v3Step) && bounded(4, previous.v4Step) && valid(plan, previous, old)
        }
    }

    fun detached(state: CivMinePlanState): CivMinePlanState = state.copy(
        sectionSteps = state.sectionSteps?.toMap(), seals = state.seals?.toMap(), levels = state.levels?.map {
            it.copy(building = it.building.copy(transit = it.building.transit?.toList()),
                sectionSteps = it.sectionSteps?.toMap(), seals = it.seals?.toMap())
        })

    fun priorStanding(state: CivMinePlanState?, world: CivPosition): Boolean = state?.levels.orEmpty().any { witness ->
        val local = CivsMinePlan.localPoint(witness.building, world)
        val old = witness.state()
        val versions = listOf(2 to (old?.takeIf { it.version == 2 }?.nextStep ?: old?.v2Step),
            3 to (old?.takeIf { it.version == 3 }?.nextStep ?: old?.v3Step),
            4 to (old?.takeIf { it.version == 4 }?.nextStep ?: old?.v4Step),
            5 to (old?.takeIf { it.version == 5 }?.nextStep ?: old?.v5Step))
        versions.any { (version, prefix) -> prefix != null && CivsMinePlan.forBuilding(witness.building, version)?.builtStanding(
            local, prefix, if (version == old?.version) old?.sectionSteps.orEmpty() else emptyMap()) == true } ||
            CivsMinePlan.forBuilding(witness.building)?.legacyCompleted(witness.legacyProgress ?: 0)?.let { cells ->
                fun open(p: CivPosition) = p in cells && cells[p].let { it == null || it.startsWith("minecraft:ladder[") || it == "minecraft:torch" }
                open(local) && open(local.copy(y = local.y + 1))
            } == true
    }

    fun valid(plan: CivsMinePlan, state: CivMinePlanState, building: CivBuilding? = null): Boolean {
        if (state.sectionSteps == null) return state.excavation == null && state.seals == null && state.v5Step == null
        if (state.version != CivsMinePlan.VERSION || !plan.sectionValid(state.sectionSteps)) return false
        val normalized = plan.normalizedProgress(state.nextStep, state.sectionSteps)
        val sealPositions = building?.let(CivsMinePlan::sealPositions) ?: plan.plannedCuts
        return normalized.first == state.nextStep && normalized.second == state.sectionSteps &&
            (state.v5Step == null || state.v5Step in 0..state.nextStep) &&
            (state.excavation == null || state.excavation in plan.positions) &&
            state.seals.orEmpty().let { seals -> seals.size <= MAXIMUM_SEALS && seals.all { (key, data) ->
                point(key) in sealPositions && data == "minecraft:cobblestone"
            } }
    }

    fun after(building: CivBuilding, previous: CivMinePlanState?, legacy: Long?, work: CivWorldWork): CivMinePlanState {
        val version = requireNotNull(work.miningPlanVersion)
        val next = requireNotNull(work.miningStep)
        val plan = requireNotNull(CivsMinePlan.forBuilding(building, version))
        val old = previous?.takeIf { it.version == version }
        val prefix = old?.nextStep ?: 0
        require(next in prefix..plan.steps.size.toLong())
        val sections = work.miningSections
        var normalized: Map<String, Long>? = null
        var excavation = old?.excavation
        var seals = old?.seals
        if (sections != null) {
            require(version == CivsMinePlan.VERSION && work.changes.size == 1 && plan.sectionValid(sections))
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
            val futurePositions = CivsMinePlan.futureSealPositions(building)
            work.changes.forEach { cell ->
                val local = CivsMinePlan.localPoint(building, cell.position)
                val key = key(local)
                if (proof[key] == cell.before) proof.remove(key)
                if (cell.after == "minecraft:air") excavation = work.miningExcavation?.takeIf { it == old?.excavation } ?: local
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
            previous?.takeIf { it.version == version && it.sectionSteps == null && sections != null }?.nextStep ?: previous?.v5Step,
            previous?.levels)
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
            work.miningSections != null && work.miningPlanVersion == CivsMinePlan.VERSION)
        require(after.minePlans?.get(buildingBefore.id.toString()) == CivsMineProgress.after(buildingBefore, planBefore, legacyBefore, work))
    }
}
