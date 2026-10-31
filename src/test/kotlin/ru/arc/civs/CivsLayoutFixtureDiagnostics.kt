package ru.arc.civs

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path

/** Opt-in detached failure evidence for the small synthetic layout regressions. */
internal fun layoutFixtureDiagnostics(name: String, city: Settlement,
    templates: Map<BuildingType, Pair<CivBuilding, CivBuilding>>, surface: CivTerrainSurface):
    ((CivSettlementPlan, CivSettlementInfrastructure) -> Unit)? {
    val directory = System.getenv("CIVS_LAYOUT_DIAGNOSTIC_DIR") ?: return null
    val output = Path.of(directory)
    Files.createDirectories(output)
    val gson = GsonBuilder().setPrettyPrinting().create()
    Files.writeString(output.resolve("$name-fixture.json"), gson.toJson(mapOf("city" to city, "surface" to surface,
        "templates" to templates.flatMap { (type, pair) -> listOf("$type-1" to pair.first, "$type-${type.maxLevel}" to pair.second) }.toMap(),
        "worksites" to emptyMap<String, Any>())))
    return { plan, infrastructure ->
        val encoded = gson.toJson(mapOf("plan" to plan, "infrastructure" to infrastructure))
        Files.writeString(output.resolve("$name-candidate.json"), encoded)
        val forts = plan.plots.count { it.fortification != null }
        if (forts > 0) Files.writeString(output.resolve("$name-fort$forts-candidate.json"), encoded)
    }
}
