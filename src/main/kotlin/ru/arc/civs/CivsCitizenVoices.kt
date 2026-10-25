package ru.arc.civs

import org.bukkit.SoundCategory
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import ru.arc.paper.npc.PacketPlayerNpc
import ru.arc.paper.npc.PacketNpcPose
import java.util.UUID

internal enum class CitizenVoiceEvent(val key: String) { IDLE("idle"), WORK("work"), GREET("greet") }

/** Optional pack events, stable voice per resident, with one shared server-tick speaking budget. */
internal class CivsCitizenVoices(config: ConfigurationSection) {
    private val enabled = config.getBoolean("npc-voices.enabled", false)
    private val prefix = config.getString("npc-voices.event-prefix", "arccivs:citizen")!!.also {
        require(it.matches(Regex("[a-z0-9_.-]+:[a-z0-9/._-]+"))) { "Invalid npc-voices.event-prefix" }
    }
    private val clock = CitizenVoiceClock()

    fun say(cityId: UUID, workerId: UUID, actor: PacketPlayerNpc, event: CitizenVoiceEvent, tick: Int, viewers: Collection<Player>, sex: CivSex? = null) {
        if (!enabled || !actor.isValid || actor.pose == PacketNpcPose.SLEEPING) return
        val at = actor.location.add(0.0, actor.eyeHeight, 0.0)
        val audience = viewers.filter { it.isOnline && !it.hasMetadata("NPC") && it.world == actor.world &&
            it.location.distanceSquared(at) <= 12.0 * 12.0 }
        if (audience.isEmpty() || !clock.admit(workerId, tick)) return
        emit(cityId, workerId, actor, event, audience, sex)
    }

    /** An intentional interaction always gets a reply, without spending the ambient speech budget. */
    fun greet(cityId: UUID, workerId: UUID, actor: PacketPlayerNpc, player: Player, sex: CivSex? = null) {
        if (!enabled || !actor.isValid || !player.isOnline || player.world != actor.world) return
        emit(cityId, workerId, actor, CitizenVoiceEvent.GREET, listOf(player), sex, interaction = true)
    }

    private fun emit(cityId: UUID, workerId: UUID, actor: PacketPlayerNpc, event: CitizenVoiceEvent, audience: Collection<Player>, sex: CivSex?,
        interaction: Boolean = false) {
        val at = actor.location.add(0.0, actor.eyeHeight, 0.0)
        val sound = "$prefix.${citizenVoice(workerId, sex)}.${event.key}"
        val volume = if (interaction) 0.85f else 0.65f
        audience.forEach { it.playSound(at, sound, SoundCategory.NEUTRAL, volume, 1f) }
        CivsDiagnostics.event("citizen_voice", "city" to cityId, "worker" to workerId, "world" to actor.world.uid,
            "sound" to sound, "voice_event" to event.key, "trigger" to if (interaction) "interaction" else "ambient",
            "audience" to audience.size)
    }

    fun retain(workers: Set<UUID>) = clock.retain(workers)
    fun clear() = clock.clear()
}

internal fun citizenVoice(workerId: UUID, sex: CivSex? = null): String =
    if ((sex ?: CivsResidents.legacySex(workerId)) == CivSex.MALE) "male1" else "female1"

internal class CitizenVoiceClock {
    private var lastGlobal: Int? = null
    private val lastWorker = mutableMapOf<UUID, Int>()

    fun admit(workerId: UUID, tick: Int): Boolean {
        if (lastGlobal?.let { tick - it < 60 } == true) return false
        val delay = 300 + Math.floorMod(workerId.hashCode(), 201)
        if (lastWorker[workerId]?.let { tick - it < delay } == true) return false
        lastGlobal = tick
        lastWorker[workerId] = tick
        return true
    }

    fun retain(workers: Set<UUID>) { lastWorker.keys.retainAll(workers) }
    fun clear() { lastGlobal = null; lastWorker.clear() }
}
