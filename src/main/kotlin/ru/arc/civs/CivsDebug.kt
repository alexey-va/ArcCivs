package ru.arc.civs

import com.google.gson.GsonBuilder
import java.util.UUID

/** Bounded, read-only operator snapshots. Each section is a separate parseable console line. */
internal object CivsDebug {
    val systems = listOf("overview", "autonomy", "citizens", "worksites", "supply", "storage", "chunks", "construction", "resources", "residents")
    val explicitSystems = listOf("terrain")
    private const val USAGE = "civs debug [all|system] [city UUID] [worker UUID]"
    private val gson = GsonBuilder().serializeNulls().create()

    fun lines(args: List<String>, state: (String, UUID?, UUID?) -> Map<String, Any?>): List<String> {
        val system = args.firstOrNull() ?: "overview"
        fun error(reason: String) = listOf("ARCCIVS_DEBUG " + gson.toJson(mapOf(
            "schema" to 1, "error" to reason, "usage" to USAGE, "systems" to systems, "explicit_systems" to explicitSystems)))
        if (args.size > 3) return error("too-many-arguments")
        if (system != "all" && system !in systems && system !in explicitSystems) return error("unknown-system")
        fun id(index: Int) = args.getOrNull(index)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val city = id(1)
        val worker = id(2)
        if (args.size > 1 && city == null) return error("invalid-city-id")
        if (args.size > 2 && worker == null) return error("invalid-worker-id")
        if (system == "terrain" && (city == null || worker == null)) return error("terrain-requires-city-and-worker")
        return (if (system == "all") systems else listOf(system)).map { section ->
            ("ARCCIVS_DEBUG " + gson.toJson(linkedMapOf(
                "schema" to 1, "system" to section, "generated_at_ms" to System.currentTimeMillis(),
                "city" to city, "worker" to worker, "state" to state(section, city, worker)))).also { line ->
                CivsDiagnostics.record(java.util.logging.Level.INFO, city, line)
            }
        }
    }
}
