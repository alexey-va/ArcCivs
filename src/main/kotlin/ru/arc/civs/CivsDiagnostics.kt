package ru.arc.civs

import org.bukkit.entity.Player
import ru.arc.logging.ArcLogging
import ru.arc.observability.StructuredDebugLine
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/** Source-owned events and committed material counts; no item metadata, credentials, packets or frame traces. */
internal object CivsDiagnostics {
    private val format = StructuredDebugLine("ARC_CIVS")
    private class Sink(val history: CivsCityHistory, val logger: Logger)
    @Volatile private var sink: Sink? = null

    fun install(history: CivsCityHistory, logger: Logger) { sink = Sink(history, logger) }
    fun detach(history: CivsCityHistory) { if (sink?.history === history) sink = null }

    fun line(event: String, vararg fields: Pair<String, Any?>): String = format.line(
        listOf("event" to event) + fields.map { (key, value) -> key to value?.toString()?.take(240)?.map {
            if (it.isISOControl()) ' ' else it
        }?.joinToString("") },
    )
    // PaperLoggingPlatform renders MiniMessage; player-provided tags must remain literal.
    fun event(event: String, vararg fields: Pair<String, Any?>) = record(Level.INFO,
        fields.firstOrNull { it.first == "city" }?.second as? UUID, line(event, *fields))

    fun record(logger: Logger, level: Level, city: UUID?, message: String, failure: Throwable? = null) =
        record(level, city, message, logger, failure)

    /** Raw structured navigation/debug lines must carry typed identity, never a parsed path or city name. */
    fun record(level: Level, city: UUID?, message: String, logger: Logger? = null, failure: Throwable? = null) {
        val bounded = message.take(16_384).map { if (it.isISOControl()) ' ' else it }.joinToString("")
        val historyLine = if (failure == null) bounded else {
            // Arbitrary exception messages can contain SQL/item payloads. The global logger retains
            // the original Throwable; detached city history keeps only bounded types and locations.
            val locations = generateSequence(failure) { it.cause }.take(4).joinToString(" <- ") { cause ->
                cause.javaClass.name.take(160) + cause.stackTrace.take(8).joinToString(";", "[", "]") { frame ->
                    frame.className.take(160) + "." + frame.methodName.take(96) + "(" +
                        frame.fileName.orEmpty().take(96) + ":" + frame.lineNumber + ")"
                }
            }.take(4_096)
            bounded.take(8_000) + " exception_locations=" + locations
        }
        val installed = sink
        if (installed != null) {
            installed.history.record(level, city, historyLine)
            if (level.intValue() >= Level.WARNING.intValue()) installed.logger.log(level, bounded, failure)
        } else if (logger != null || failure != null) (logger ?: Logger.getLogger("ArcCivs")).log(level, bounded, failure)
        else if (level.intValue() >= Level.SEVERE.intValue()) ArcLogging.error(ArcLogging.escapeMM(bounded))
        else if (level.intValue() >= Level.WARNING.intValue()) ArcLogging.warn(ArcLogging.escapeMM(bounded))
        else ArcLogging.info(ArcLogging.escapeMM(bounded))
    }

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
