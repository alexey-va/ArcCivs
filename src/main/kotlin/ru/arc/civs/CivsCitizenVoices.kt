package ru.arc.civs

import org.bukkit.SoundCategory
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import java.util.UUID

internal enum class CitizenVoiceEvent(val key: String) { IDLE("idle"), WORK("work"), GREET("greet") }

/** Optional pack events, stable voice per resident, with one shared server-tick speaking budget. */
internal class CivsCitizenVoices(config: ConfigurationSection) {
    private val enabled = config.getBoolean("npc-voices.enabled", false)
    private val prefix = config.getString("npc-voices.event-prefix", "arccivs:citizen")!!.also {
        require(it.matches(Regex("[a-z0-9_.-]+:[a-z0-9/._-]+"))) { "Invalid npc-voices.event-prefix" }
    }
    private val clock = CitizenVoiceClock()

    fun say(workerId: UUID, actor: Player, event: CitizenVoiceEvent, tick: Int, viewers: Collection<Player>) {
        if (!enabled || !actor.isValid || actor.isSleeping) return
        val at = actor.eyeLocation
        val audience = viewers.filter { it.isOnline && !it.hasMetadata("NPC") && it.world == actor.world &&
            it.location.distanceSquared(at) <= 12.0 * 12.0 }
        if (audience.isEmpty() || !clock.admit(workerId, tick)) return
        val sound = "$prefix.${citizenVoice(workerId)}.${event.key}"
        audience.forEach { it.playSound(at, sound, SoundCategory.NEUTRAL, 0.45f, 1f) }
        CivsDiagnostics.event("citizen_voice", "worker" to workerId, "world" to actor.world.uid,
            "sound" to sound, "audience" to audience.size)
    }

    fun retain(workers: Set<UUID>) = clock.retain(workers)
    fun clear() = clock.clear()
}

internal fun citizenVoice(workerId: UUID): String = if (Math.floorMod(workerId.hashCode(), 2) == 0) "male1" else "female1"

internal class CitizenVoiceClock {
    private var lastGlobal: Int? = null
    private val lastWorker = mutableMapOf<UUID, Int>()

    fun admit(workerId: UUID, tick: Int): Boolean {
        if (lastGlobal?.let { tick - it < 160 } == true) return false
        val delay = 900 + Math.floorMod(workerId.hashCode(), 601)
        if (lastWorker[workerId]?.let { tick - it < delay } == true) return false
        lastGlobal = tick
        lastWorker[workerId] = tick
        return true
    }

    fun retain(workers: Set<UUID>) { lastWorker.keys.retainAll(workers) }
    fun clear() { lastGlobal = null; lastWorker.clear() }
}
