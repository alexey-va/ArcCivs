package ru.arc.civs

import org.bukkit.entity.Player
import ru.arc.logging.ArcLogging
import ru.arc.observability.StructuredDebugLine

/** Source-owned events only. No inventory, SQL credentials, raw packets or tick/frame traces. */
internal object CivsDiagnostics {
    private val format = StructuredDebugLine("ARC_CIVS")

    fun line(event: String, vararg fields: Pair<String, Any?>): String = format.line(
        listOf("event" to event) + fields.map { (key, value) -> key to value?.toString()?.take(240)?.map {
            if (it.isISOControl()) ' ' else it
        }?.joinToString("") },
    )
    // PaperLoggingPlatform renders MiniMessage; player-provided tags must remain literal.
    fun event(event: String, vararg fields: Pair<String, Any?>) = ArcLogging.info(ArcLogging.escapeMM(line(event, *fields)))

    /** Capture on Main; completions may log the detached fields on SQL/file workers. */
    fun actor(player: Player): Array<Pair<String, Any?>> {
        val location = player.location
        return arrayOf("player" to player.uniqueId, "player_name" to player.name,
            "world" to player.world.name, "x" to location.blockX, "y" to location.blockY, "z" to location.blockZ)
    }

    fun rejection(failure: Throwable?): CivsRejection? =
        failure?.let { generateSequence(it) { cause -> cause.cause }.take(16).filterIsInstance<CivsRejection>().firstOrNull() }

    fun cause(failure: Throwable?): Throwable? = failure?.let { generateSequence(it) { cause -> cause.cause }.take(16).last() }
}
