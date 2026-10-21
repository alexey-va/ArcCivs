package ru.arc.civs

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID

internal data class CitizenSkin(val name: String, val texture: String, val signature: String, val sex: CivSex = CivSex.MALE)

/** Bundled signed snapshots are read once when the scene starts, never from the spawn/pulse path. */
internal fun loadCitizenSkins(): List<CitizenSkin> {
    val stream = checkNotNull(CivsCitizens::class.java.getResourceAsStream("/citizen-skins.yml")) {
        "ArcCivs citizen-skins.yml is missing"
    }
    val config = stream.reader(Charsets.UTF_8).use(YamlConfiguration::loadConfiguration)
    val skins = config.getMapList("skins").map { entry ->
        fun field(key: String) = checkNotNull((entry[key] as? String)?.takeIf(String::isNotBlank)) {
            "ArcCivs citizen skin has no $key"
        }
        CitizenSkin(field("name"), field("texture"), field("signature"), CivSex.valueOf(field("sex")))
    }
    check(CivSex.entries.all { sex -> skins.any { it.sex == sex } }) { "ArcCivs citizen skins must cover both sexes" }
    check(skins.isNotEmpty() && skins.map { it.name }.distinct().size == skins.size) {
        "ArcCivs citizen skins must have distinct names and at least one signed texture"
    }
    return skins
}

/** A resident keeps the same appearance across assignment changes, viewer exits and restarts. */
internal fun citizenSkin(skins: List<CitizenSkin>, workerId: UUID): CitizenSkin =
    skins[Math.floorMod(workerId.hashCode(), skins.size)]

internal fun citizenSkin(skins: List<CitizenSkin>, worker: CivWorker): CitizenSkin =
    citizenSkin(skins.filter { it.sex == CivsResidents.sex(worker) }.also { check(it.isNotEmpty()) }, worker.id)

internal fun citizenScale(worker: CivWorker): Double = if (CivsResidents.isChild(worker)) .5 else 1.0

/** Optional scene props only. Cargo has one attached parcel; work tools come from the actual HAND slot. */
internal fun citizenTool(plan: CitizenPlan): Material? = when (plan.type) {
    BuildingType.TOWN_HALL -> Material.BOOK
    BuildingType.LIBRARY -> Material.FEATHER
    else -> null
}

internal fun citizenWorking(plan: CitizenPlan, phase: CitizenPhase): Boolean =
    plan.type != BuildingType.BUILDER_HUT && plan.idleReason == null &&
        (phase == CitizenPhase.WORKING || plan.worldWork != null && phase in setOf(CitizenPhase.READY, CitizenPhase.DELIVERING))

/** A watering prop represents the farm's irrigation; fertilizer must belong to the accepted work inputs. */
internal fun citizenFieldCareItem(plan: CitizenPlan): Material? {
    val work = plan.worldWork ?: return null
    if (plan.type != BuildingType.FARM || plan.idleReason != null || work.focus == null || citizenWorkFocus(work) == null) return null
    return when (work.status) {
        "watering" -> Material.WATER_BUCKET
        "fertilizing" -> Material.BONE_MEAL.takeIf { (work.inputs["BONE_MEAL"] ?: 0) > 0 }
        else -> null
    }
}

internal fun citizenWorkStatus(plan: CitizenPlan, phase: CitizenPhase): String? =
    plan.worldWork?.status?.takeIf { phase in setOf(CitizenPhase.WORKING, CitizenPhase.READY, CitizenPhase.DELIVERING) }

internal fun citizenConstructionWorking(plan: CitizenPlan, phase: CitizenPhase, orderId: UUID): Boolean =
    plan.type == BuildingType.BUILDER_HUT && plan.token.constructionId == orderId &&
        plan.idleReason in listOf(null, "placing") &&
        phase in setOf(CitizenPhase.WORKING, CitizenPhase.READY, CitizenPhase.DELIVERING)

internal fun citizenPlacementItem(material: Material): Material? = when {
    material.isAir -> null
    material.isItem -> material
    material == Material.WATER -> Material.WATER_BUCKET
    material == Material.LAVA -> Material.LAVA_BUCKET
    else -> null
}

internal fun citizenWorkBuilding(city: Settlement, plan: CitizenPlan): CivBuilding? =
    plan.courier?.let { city.building(it.cargo.sourceBuildingId) } ?: when (plan.type) {
    BuildingType.BUILDER_HUT ->
        city.construction?.let { it.plannedBuilding ?: city.building(it.buildingId) }
    else -> plan.token.assignmentId?.let(city::building)
}

/** At most one native swing per second; a late pulse never replays missed gestures. */
internal class CitizenWorkAnimation {
    private var nextSwingAt = Long.MIN_VALUE

    fun swing(now: Long): Boolean {
        if (now < nextSwingAt) return false
        nextSwingAt = if (now > Long.MAX_VALUE - 1_000) Long.MAX_VALUE else now + 1_000
        return true
    }
}
