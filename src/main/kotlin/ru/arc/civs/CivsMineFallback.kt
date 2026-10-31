package ru.arc.civs

import java.util.WeakHashMap

/** Owner-authorized Auto production while physical excavation remains unsafe or unavailable. */
internal object CivsMineFallback {
    const val DISCOVERY_ADMISSIONS = 16
    const val DURATION_MILLIS = 6_000L
    val OUTPUTS = setOf("COBBLESTONE", "COAL", "RAW_IRON", "RAW_GOLD", "REDSTONE", "DIAMOND")

    fun eligible(city: Settlement, building: CivBuilding, worker: CivWorker): Boolean = city.autonomy?.paused == false &&
        city.inventory?.mode == CivStorageMode.PHYSICAL && city.pendingDeletion == null && building.type == BuildingType.QUARRY &&
        CivsRules.workplaceAvailable(city, building) &&
        worker.assignment == building.id && !CivsResidents.isChild(worker) && worker.cargo == null &&
        CivsEquipment.slots(worker)[4]?.material?.endsWith("_PICKAXE") == true

    // Transient discovery evidence, deliberately outside the economic work/receipt schema.
    // Keys stay alive only while the owner retains that exact reserve action; saturation skips the optional visit.
    private val causes = WeakHashMap<CivWorldWork, CivMineVisitCause>()
    fun cause(work: CivWorldWork): CivMineVisitCause? = causes[work]
    fun withCause(work: CivWorldWork, need: CivWorkNeedState?): CivWorldWork = work.also {
        if (causes.size < 4_096) causes[it] = CivMineVisitCause(need?.status ?: "searching-work",
            need?.reason ?: "physical-work-unavailable", need?.point, need?.blockMaterial)
    }

    fun matches(work: CivWorldWork): Boolean = work.status == "digging-mine" && work.changes.isEmpty() &&
        work.inputs.isEmpty() && work.outputs.size == 1 && work.outputs.entries.single().let { it.key in OUTPUTS && it.value == 1L } &&
        work.durationMillis == DURATION_MILLIS && work.miningStep == null && work.miningPlanVersion == null &&
        work.miningSections == null && work.miningAccessSections == null && work.miningAccessStep == null &&
        work.miningOrdinal == null && !work.miningGenerator && !work.autonomousVisual && !work.remoteWork &&
        work.forestry == null && work.upkeep == null && work.focus == null
}
