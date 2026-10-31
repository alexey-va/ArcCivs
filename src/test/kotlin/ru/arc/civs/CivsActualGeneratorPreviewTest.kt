package ru.arc.civs

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import ru.arc.paper.api.ArcSchematicBlock

/** Optional detached private catalog replay. The production planner owns every exported placement. */
class CivsActualGeneratorPreviewTest : FunSpec({
    test("export the actual maximum-envelope plan on captured detached terrain") {
        val input = System.getenv("CIVS_ACTUAL_LAYOUT_FIXTURE") ?: return@test
        val output = Path.of(requireNotNull(System.getenv("CIVS_ACTUAL_LAYOUT_OUT")))
        Files.createDirectories(output)
        val gson = GsonBuilder().setPrettyPrinting().create()
        val document = JsonParser.parseString(Files.readString(Path.of(input))).asJsonObject
        val city = gson.fromJson(document["city"], Settlement::class.java)
        val sources = document.getAsJsonObject("templates")
        val templates = CivsAutonomy.profile.associateWith { type ->
            gson.fromJson(sources["$type-1"], CivBuilding::class.java) to
                gson.fromJson(sources["$type-${type.maxLevel}"], CivBuilding::class.java)
        }
        val surface = gson.fromJson(document["surface"], CivTerrainSurface::class.java)
        val worksites = document.getAsJsonObject("worksites").entrySet().associate { it.key to it.value.asJsonObject }
        val searches = mutableListOf<CivRouteSearchStats>()
        val fortification = System.getenv("CIVS_ACTUAL_LAYOUT_FORTIFICATION")?.let { file ->
            val row = JsonParser.parseString(Files.readString(Path.of(file))).asJsonObject
                .getAsJsonObject("fortification_template")
            check(row["status"].asString == "compiled")
            val bytes = Base64.getDecoder().decode(row["sourceBytesBase64"].asString)
            check(CivsFortifications.sha256(bytes) == row["sourceSha256"].asString)
            CivFortificationTemplate(gson.fromJson(row["building"], CivBuilding::class.java),
                row.getAsJsonArray("cells").map { gson.fromJson(it, ArcSchematicBlock::class.java) }, bytes,
                row["sourceSha256"].asString, row["recipeSha256"].asString,
                row.getAsJsonArray("sources").map { gson.fromJson(it, CivFortificationSource::class.java) },
                gson.fromJson(row["proof"], CivFortificationProof::class.java))
        }
        CivsWorksiteLayouts.installCatalog(worksites)
        try {
            val started = System.nanoTime()
            val plan = try {
                CivsSettlementPlans.prepare(city, templates, surface, CivAutonomousAccess(), fortification = fortification,
                    inspectRoute = searches::add) { candidate, infrastructure ->
                    Files.writeString(output.resolve("generator-candidate.json"), gson.toJson(mapOf(
                        "evidence" to "Unadopted production planner candidate", "plan" to candidate, "infrastructure" to infrastructure)))
                }
            } catch (failure: Exception) {
                Files.writeString(output.resolve("generator-failure.json"), gson.toJson(mapOf(
                    "reason" to (failure as? CivsRejection)?.reason, "details" to (failure as? CivsRejection)?.values,
                    "message" to failure.message, "stackTrace" to failure.stackTraceToString(), "searches" to searches)))
                throw failure
            }
            val adopted = city.copy(autonomy = requireNotNull(city.autonomy).copy(plan = plan, layoutHalfWidth = plan.halfWidth))
            val buildings = plan.plots.map { plot ->
                (if (plot.fortification != null) plot.building else CivsSettlementPlans.at(templates.getValue(plot.building.type).second,
                    plot.building.anchor, plot.building.rotation, plot.building.id)).copy(phase = BuildingPhase.ACTIVE)
            }
            val upgraded = adopted.copy(buildings = buildings)
            check(plan.plots.count { it.building.type != BuildingType.DECORATION } == CivsRules.MAX_BUILDINGS)
            check(plan.plots.count { it.building.type == BuildingType.HOUSE } == CivsAutonomy.HOUSE_COUNT)
            check(plan.halfWidth == CivsSettlementLayout.MAXIMUM_HALF_WIDTH)
            check(buildings.all { CivsRules.workRouteWithinReach(upgraded, it.entrance) })
            check(searches.all { it.expanded <= CivsAutonomousTerraform.MAXIMUM_ROUTE_NODES })
            val maximumCaptureRadius = 127
            val reservedPoints = plan.plots.flatMap { listOf(it.envelope.min, it.envelope.max) } +
                (plan.walls + plan.gates).flatMap { listOf(it.min, it.max) } + plan.routes.values.flatten()
            check(reservedPoints.all { kotlin.math.abs(it.x - city.center.x) <= maximumCaptureRadius &&
                kotlin.math.abs(it.z - city.center.z) <= maximumCaptureRadius })
            Files.writeString(output.resolve("generator-plan.json"), gson.toJson(mapOf(
                "evidence" to "Production prepare result on detached terrain; no native world observation or mutation",
                "inputFixture" to input, "templateMode" to document["templateMode"],
                "runtimePolicy" to document["runtimePolicy"],
                "fortification" to fortification?.let { mapOf("building" to it.building, "cells" to it.cells,
                    "sourceSha256" to it.sourceSha256, "recipeSha256" to it.recipeSha256, "sources" to it.sources) },
                "sourceManifest" to document["sourceManifest"], "previewTerrain" to document["previewTerrain"],
                "prepareMillis" to (System.nanoTime() - started) / 1_000_000,
                "city" to city, "plan" to plan, "frame" to CivsSettlementLayout.frame(adopted), "maxLevelBuildings" to buildings,
                "maxLevelFarmFields" to buildings.filter { it.type == BuildingType.FARM }.flatMap(CivsWorksiteLayouts::field),
                "maxLevelFarmIrrigation" to buildings.filter { it.type == BuildingType.FARM }.flatMap(CivsWorksiteLayouts::irrigation),
                "searches" to searches)))
        } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
    }
})
