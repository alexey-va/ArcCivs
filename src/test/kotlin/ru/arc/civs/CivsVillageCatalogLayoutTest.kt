package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Optional private metadata preflight; no source voxels or captured world state are exported. */
class CivsVillageCatalogLayoutTest : FunSpec({
    val source = System.getenv("CIVS_VILLAGE_CATALOG")
    test("requested styles fit complete profiles on flat and mild hill terrain using CIVS_VILLAGE_CATALOG")
        .config(enabled = source != null) {
            val path = Path.of(checkNotNull(source)).toAbsolutePath().normalize()
            check(Files.isRegularFile(path) && Files.size(path) in 1..64_000_000L) { "Invalid private catalog path or size" }
            val catalog = Files.newBufferedReader(path).use { CivBlueprintCatalog.parse(JsonParser.parseReader(it).asJsonObject) }
            val styles = System.getenv("CIVS_VILLAGE_STYLES")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.distinct()
                ?: listOf("nordic", "medievaloak", "birch", "original", "medievaldarkoak")
            check(styles.size in 1..16 && styles.all { it in catalog.styles() }) { "Invalid requested catalog styles" }
            val expected = CivsAutonomy.completeProfile.groupingBy { it }.eachCount()
            CivsAutonomy.completeProfile.size shouldBe 34
            val results = mutableListOf<Map<String, Any?>>()
            val gson = Gson()
            CivsWorksiteLayouts.installCatalog(catalog.worksiteLayouts)
            try {
                for (terrainMode in listOf("flat", "mild-hills")) for (style in styles) {
                    val started = System.nanoTime()
                    val result = linkedMapOf<String, Any?>("style" to style, "terrain" to terrainMode)
                    val routeOutcomes = sortedMapOf<String, Int>()
                    var expanded = 0L
                    var maximumExpanded = 0
                    try {
                        val templates = CivsAutonomy.profile.distinct().associateWith { type ->
                            val id = UUID.nameUUIDFromBytes("village-catalog-preflight/$style/$type".toByteArray(Charsets.UTF_8))
                            if (type == BuildingType.PLAN_BOARD) {
                                val board = actualCatalogBoardTemplate(id)
                                board to board
                            } else {
                                val first = checkNotNull(catalog.autonomous(style, type)) { "Missing autonomous source $style/$type" }
                                val maximum = checkNotNull(catalog.upgrade(first.id, first.maxLevel)) { "Missing maximum frame ${first.id}" }
                                check(first.type == type && first.level == 1 && maximum.familyKey == first.familyKey)
                                actualCatalogTemplate(first, id) to actualCatalogTemplate(maximum, id)
                            }
                        }
                        check(listOf(BuildingType.TOWN_HALL, BuildingType.HOUSE, BuildingType.BUILDER_HUT,
                            BuildingType.FORESTER, BuildingType.FARM).all {
                            CivsBlueprints.styleOf(templates.getValue(it).first) == style
                        }) { "A required theme building fell back from $style" }
                        result["fallback_styles"] = templates.values.mapNotNull { CivsBlueprints.styleOf(it.first) }
                            .filter { it != style }.groupingBy { it }.eachCount()
                        val baseGround = if (terrainMode == "flat") 63 else 67
                        val hall = CivsSettlementPlans.at(templates.getValue(BuildingType.TOWN_HALL).first,
                            CivPosition(0, baseGround, 0), 0).copy(phase = BuildingPhase.SITE)
                        fun id(role: String) = UUID.nameUUIDFromBytes("village-catalog-preflight/$style/$terrainMode/$role".toByteArray(Charsets.UTF_8))
                        val city = CivsAutonomy.found(id("city"), id("owner"), "Preflight $style", id("world"),
                            checkNotNull(hall.control), 128, style, hall, 1_000)
                            .copy(settings = CivSettlementSettings(immortalResidents = true))
                        val before = gson.toJson(city)
                        val surface = CivTerrainSurface(-64, 320, buildMap {
                            for (x in city.center.x - 127..city.center.x + 127)
                                for (z in city.center.z - 127..city.center.z + 127) {
                                    val ground = baseGround + if (terrainMode == "flat") 0
                                        else (3.0 * sin(x / 24.0) * cos(z / 31.0)).roundToInt()
                                    put(civsTerrainKey(x, z), CivTerrainSurfaceColumn(ground, true))
                                }
                        })
                        val plan = CivsSettlementPlans.prepare(city, templates,
                            CivsVillageSiteScoring.preparedSurface(surface, hall), CivAutonomousAccess(), inspectRoute = { stats ->
                                routeOutcomes.merge(stats.outcome, 1, Int::plus)
                                expanded += stats.expanded
                                maximumExpanded = maxOf(maximumExpanded, stats.expanded)
                            }, inspect = { partial, _ ->
                                result["last_attempt_half_width"] = partial.halfWidth
                                result["last_attempt_counts"] = partial.plots.groupingBy { it.building.type }.eachCount()
                            })
                        val counts = plan.plots.filter { !it.building.type.isInfrastructure && it.building.type != BuildingType.DECORATION }
                            .groupingBy { it.building.type }.eachCount()
                        result["ordinary_plots"] = counts.values.sum()
                        result["counts"] = counts
                        result["half_width"] = plan.halfWidth
                        counts shouldBe expected
                        CivsVillageSiteScoring.completeProfile(plan) shouldBe true
                        val wooded = surface.copy(columns = surface.columns.mapValues { (_, column) ->
                            column.copy(topY = column.groundY + 5)
                        })
                        val usableTrees = CivsVillageSiteScoring.usableTreeColumns(city, plan, wooded)
                        result["usable_forester_tree_columns"] = usableTrees
                        withClue("Authored forester work area for $style/$terrainMode") { (usableTrees >= 8) shouldBe true }
                        gson.toJson(city) shouldBe before
                        result["success"] = true
                    } catch (failure: Exception) {
                        result["success"] = false
                        result["failure"] = if (failure is CivsRejection) failure.reason else failure.javaClass.simpleName
                        result["details"] = if (failure is CivsRejection) failure.values else failure.message?.take(256)
                    } catch (failure: AssertionError) {
                        result["success"] = false
                        result["failure"] = "incomplete-profile"
                        result["details"] = failure.message?.take(256)
                    }
                    result["route_outcomes"] = routeOutcomes
                    result["expanded_route_states"] = expanded
                    result["maximum_route_states"] = maximumExpanded
                    result["elapsed_ms"] = (System.nanoTime() - started) / 1_000_000
                    results += result
                }
            } finally {
                CivsWorksiteLayouts.installCatalog(emptyMap())
            }
            val report = linkedMapOf("selector" to "CivBlueprintCatalog.autonomous + maximum family frame",
                "terrain" to "synthetic flat and mild hills, natural+loaded 255x255",
                "mild_hills" to "ground=67+round(3*sin(x/24)*cos(z/31))", "radius" to 128, "initial_hall" to "SITE",
                "resource_probe" to "synthetic woods in the authored forester area excluding planned roads and envelopes",
                "expected_ordinary_plots" to 34, "results" to results)
            val json = gson.toJson(report)
            System.getenv("CIVS_VILLAGE_LAYOUT_OUT")?.let { destination ->
                val output = Path.of(destination).toAbsolutePath().normalize()
                check(output != path) { "Preflight report must not overwrite its private catalog" }
                Files.createDirectories(output.parent)
                Files.writeString(output, json)
            }
            println("ARCCIVS_VILLAGE_CATALOG_LAYOUT $json")
            withClue(json) { results.all { it["success"] == true } shouldBe true }
        }
})
