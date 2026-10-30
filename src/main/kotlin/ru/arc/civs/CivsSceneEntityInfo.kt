package ru.arc.civs

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.paper.api.ArcSceneBounds
import ru.arc.paper.api.ArcSceneEntityInfo
import ru.arc.paper.api.ArcSceneInfoField
import java.util.Locale

/** Literal public nameplate data only. The carrier and canonical live worker must still agree. */
internal fun civsSceneEntityInfo(text: CivsText, bounds: ArcSceneBounds, entityIds: Set<String>,
                               npc: CivsNpcHologramSnapshot, city: Settlement): ArcSceneEntityInfo? {
    val actor = npc.actor ?: return null
    val entityId = "packet-npc:${actor.uniqueId}"
    if (!actor.isValid || !actor.isVisibleByDefault || actor.uniqueId != npc.workerId || entityId !in entityIds ||
        city.pendingDeletion != null || city.worldId != npc.worldId ||
        !bounds.contains(npc.worldId, npc.x, npc.y, npc.z) || !npc.health.isFinite() || npc.health <= 0 ||
        !npc.maxHealth.isFinite() || npc.maxHealth <= 0 || npc.health > npc.maxHealth || npc.hunger !in 0..20) return null
    val resident = city.workers.firstOrNull { it.id == npc.workerId } ?: return null
    val plain = PlainTextComponentSerializer.plainText()
    fun localized(key: String) = plain.serialize(text.render(key, "ru"))
    fun number(value: Double) = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
    val fields = ArrayList<ArcSceneInfoField>(7)
    fun field(key: String, label: String, value: String?) {
        if (!value.isNullOrBlank()) fields += ArcSceneInfoField(key, localized(label), value.take(256))
    }
    fun building(id: java.util.UUID?): String? = id?.let(city::building)?.let {
        "${localized("building.${it.type.name.lowercase(Locale.ROOT)}")} · ${localized("label.level")} ${it.level}"
    }
    field("settlement", "label.city", city.name)
    val profession = if (npc.child) "child" else npc.profession.ifBlank { "unassigned" }
    "profession.$profession".takeIf { it in text.keys("ru") }?.let { field("profession", "label.profession", localized(it)) }
    val status = npc.status.takeIf { it.startsWith("workers.status.") } ?: "workers.status.${npc.status}"
    status.takeIf { it in text.keys("ru") }?.let {
        field("status", "label.state", plain.serialize(text.status(null, it, npc.statusInputs, locale = "ru")))
    }
    field("home", "label.home", building(resident.homeId))
    field("workplace", "label.workplace", building(resident.assignment))
    field("health", "workers.health-label", "${number(npc.health)} / ${number(npc.maxHealth)}")
    field("hunger", "workers.hunger-label", "${npc.hunger} / 20")
    return ArcSceneEntityInfo(entityId, "resident", npc.name.take(128), fields)
}
