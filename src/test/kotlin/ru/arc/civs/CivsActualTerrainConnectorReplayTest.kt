package ru.arc.civs

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Optional exact detached replay of the final rejected parcel in a planner diagnostic. */
class CivsActualTerrainConnectorReplayTest : FunSpec({
    test("a rejected terrain frontage still has an ordinary guarded connector") {
        val fixturePath = System.getenv("CIVS_CONNECTOR_FIXTURE") ?: return@test
        val partialPath = requireNotNull(System.getenv("CIVS_CONNECTOR_PARTIAL"))
        val output = Path.of(requireNotNull(System.getenv("CIVS_CONNECTOR_OUT")))
        val gson = GsonBuilder().setPrettyPrinting().create()
        val fixture = JsonParser.parseString(Files.readString(Path.of(fixturePath))).asJsonObject
        val partial = JsonParser.parseString(Files.readString(Path.of(partialPath))).asJsonObject
        val city = gson.fromJson(fixture["city"], Settlement::class.java)
        val plan = gson.fromJson(partial["plan"], CivSettlementPlan::class.java)
        val surface = gson.fromJson(fixture["surface"], CivTerrainSurface::class.java)
        val target = plan.plots.last().let { it.building.copy(bounds = it.envelope) }
        check(target.id.toString() !in plan.routes)
        val projected = city.copy(construction = null, constructionQueue = emptyList(), buildings = plan.plots.dropLast(1).map {
            it.building.copy(bounds = it.envelope, phase = if (city.building(it.building.id) == null)
                BuildingPhase.SITE else BuildingPhase.ACTIVE)
        })
        val access = CivAutonomousAccess(plan.routes.mapKeys { UUID.fromString(it.key) })
        val preferred = access.routes.values.flatten().mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }
        val worksites = fixture.getAsJsonObject("worksites").entrySet().associate { it.key to it.value.asJsonObject }
        val searches = mutableListOf<CivRouteSearchStats>()
        CivsWorksiteLayouts.installCatalog(worksites)
        try {
            val ordinary = CivsAutonomousTerraform.draft(projected, CivTerraformOption(target, 0), surface,
                access, preferredRoads = preferred, inspectRoute = searches::add)
            val frontage = runCatching { CivsAutonomousTerraform.draft(projected, CivTerraformOption(target, 0, true),
                surface, access, preferredRoads = preferred, inspectRoute = searches::add) }
            check(ordinary.route.last() == target.entrance)
            check(ordinary.route.size <= CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH)
            check(searches.all { it.expanded <= CivsAutonomousTerraform.MAXIMUM_ROUTE_NODES })
            Files.createDirectories(output.parent)
            Files.writeString(output, gson.toJson(mapOf("ordinaryRoute" to ordinary.route,
                "frontageRoute" to frontage.getOrNull()?.route, "frontageFailure" to frontage.exceptionOrNull()?.message,
                "target" to target, "searches" to searches)))
        } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
    }
})
