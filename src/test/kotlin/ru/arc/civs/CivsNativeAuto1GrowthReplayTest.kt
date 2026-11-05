package ru.arc.civs

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/** Metadata and a native height field replay the complete pure planner, not native voxel capture. */
class CivsNativeAuto1GrowthReplayTest : FunSpec({
    test("the adopted Auto-1 quarry grows at width 48 through the paid central gate approach")
        .config(enabled = System.getenv("CIVS_AUTO1_GROWTH_INPUT") != null) {
        val root = Path.of(requireNotNull(System.getenv("CIVS_AUTO1_GROWTH_INPUT")))
        val out = Files.createDirectories(Path.of(requireNotNull(System.getenv("CIVS_AUTO1_GROWTH_OUT"))))
        val gson = GsonBuilder().serializeNulls().create()
        val inputHashes = linkedMapOf<String, String>()
        fun input(glob: String): JsonObject = Files.newDirectoryStream(root, glob).use { paths ->
            val path = paths.single()
            val bytes = Files.readAllBytes(path)
            inputHashes[path.fileName.toString()] = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        }
        fun write(file: String, value: Any) = Files.writeString(out.resolve(file), gson.toJson(value))
        val geometry = input("*217-auto1-growth-geometry*.json")
        val adoptedInput = input("*217-auto1-adopted-plan*.json")
        val metadata = input("*217-auto1-worksite-template-metadata*.json")
        val row = geometry.getAsJsonObject("city")
        check(geometry["schema"].asInt == 1 && geometry["plugin_version"].asString == "0.1.217")
        check(geometry["source_sha256"] == adoptedInput["capture_sha256"] &&
            geometry["captured_at_ms"] == adoptedInput["captured_at_ms"] &&
            geometry["source_revision"] == adoptedInput["source_revision"] &&
            row["revision"] == geometry["source_revision"])
        check(adoptedInput["construction_is_null"].asBoolean &&
            adoptedInput.getAsJsonArray("construction_queue").isEmpty &&
            adoptedInput["placed_infrastructure_count"].asInt == 0)
        check(!metadata["licensed_source_voxels_export"].asBoolean && !metadata["private_history_export"].asBoolean)
        check(metadata["catalog_sha256"].asString == "099af920879ad7a0fdd5da21a793c94fd3123214a6973db84b01a4ed179b7d75")
        val adopted = gson.fromJson(adoptedInput["adopted_plan"], CivSettlementPlan::class.java)
        val buildings = geometry.getAsJsonArray("buildings").map { item ->
            val b = item.asJsonObject
            CivBuilding(UUID.fromString(b["id"].asString), BuildingType.valueOf(b["type"].asString), b["level"].asInt,
                gson.fromJson(b["anchor"], CivPosition::class.java), b["rotation"].asInt, b["schematic"].asString,
                gson.fromJson(b["actual_bounds"], CivBox::class.java), gson.fromJson(b["entrance"], CivPosition::class.java),
                BuildingPhase.valueOf(b["phase"].asString), gson.fromJson(b["control"], CivPosition::class.java),
                transit = b.getAsJsonArray("transit").map { gson.fromJson(it, CivPosition::class.java) })
        }
        // Ownership, stock and residents do not enter prepare; the observed next goal is explicit below.
        val city = Settlement(UUID.fromString(row["id"].asString), UUID(0, 0), "Auto-1 replay",
            UUID.fromString(row["world"].asString), gson.fromJson(row["center"], CivPosition::class.java),
            row["radius"].asInt, row["style"].asString, Stock(), buildings, emptyList(),
            revision = row["revision"].asLong, constructionQueue = emptyList(),
            autonomy = CivAutonomy(layoutHalfWidth = adopted.halfWidth, plan = adopted))
        check(city.id.toString() == "8b86f3c5-6f07-3409-9a62-2aba1b51bdc9" && city.radius == 96 && city.style == "nordic")
        val actualTypes = listOf(BuildingType.TOWN_HALL, BuildingType.BUILDER_HUT, BuildingType.FORESTER)
        check(buildings.map { it.type } == actualTypes && buildings.map { it.phase } ==
            listOf(BuildingPhase.SITE, BuildingPhase.ACTIVE, BuildingPhase.ACTIVE))
        check(adopted.revision == 1L && adopted.halfWidth == 48 && adopted.plots.size == 3 &&
            adopted.plots.all { it.fortification == null } &&
            adopted.plots.map { it.building.id }.toSet() == buildings.map { it.id }.toSet())
        CivsSettlementPlans.validate(city, adopted)
        check(CivsSettlementPlans.current(city) == adopted)
        val profile = actualTypes + BuildingType.QUARRY
        val surfaceRow = geometry.getAsJsonObject("surface")
        check(geometry.getAsJsonArray("column_fields").map { it.asString } == listOf(
            "x", "z", "ground_y", "top_y", "loaded", "natural", "dirt_path", "water_top_y", "water_bottom_y"))
        val columns = geometry.getAsJsonArray("columns").associate { item ->
            val c = item.asJsonArray
            check(c.size() == 9)
            civsTerrainKey(c[0].asInt, c[1].asInt) to CivTerrainSurfaceColumn(c[2].asInt, c[5].asBoolean,
                c[4].asBoolean, c[3].asInt, c[7].takeUnless { it.isJsonNull }?.asInt,
                c[8].takeUnless { it.isJsonNull }?.asInt, c[6].asBoolean)
        }
        val surface = CivTerrainSurface(surfaceRow["min_height"].asInt, surfaceRow["max_height"].asInt, columns)
        check(surfaceRow["loaded_only"].asBoolean && !surfaceRow["atomic"].asBoolean &&
            surfaceRow["chunks_loaded_by_export"].asInt == 0 && surface.minHeight == -64 && surface.maxHeight == 320 &&
            columns.size == 37_249 && geometry.getAsJsonArray("columns").size() == columns.size && columns.values.all { it.loaded })
        for (x in city.center.x - city.radius..city.center.x + city.radius)
            for (z in city.center.z - city.radius..city.center.z + city.radius) check(surface.at(x, z) != null)
        check(surfaceRow.getAsJsonObject("coverage")["natural"].asInt == columns.values.count { it.natural })
        val access = CivAutonomousAccess(geometry.getAsJsonArray("access_routes").associate { item ->
            val route = item.asJsonObject
            check(route["evidence"].asString == "validated-retained-journal-not-native-connectivity" && route["plan"] == route["building"])
            UUID.fromString(route["building"].asString) to route.getAsJsonArray("positions").map {
                gson.fromJson(it, CivPosition::class.java)
            }
        })
        check(access.routes.keys == buildings.filter { it.phase == BuildingPhase.ACTIVE }.map { it.id }.toSet())
        CivsSettlementPlans.validate(city, adopted.copy(routes = access.routes.mapKeys { it.key.toString() }))
        access.routes.forEach { (id, route) -> check(route.first() == buildings.first().entrance && route.last() == city.building(id)?.entrance) }
        val entries = metadata.getAsJsonArray("metadata_rows").map { it.asJsonObject }
        val byId = entries.associateBy { it["schematicId"].asString }
        check(entries.size == 12 && byId.size == entries.size)
        val templates = entries.groupBy { BuildingType.valueOf(it["type"].asString) }.mapValues { (type, family) ->
            val first = family.single { it["level"].asInt == 1 }
            val last = family.single { it["level"].asInt == first["maxSupportedLevel"].asInt }
            check(family.size == 2 && first["maxSupportedLevel"].asInt == 3 && first["style"].asString == city.style)
            check(listOf("style", "family", "revision", "size", "origin", "control", "maxSupportedLevel").all { first[it] == last[it] })
            check(first.getAsJsonObject("worksite").getAsJsonObject("upgradeSchematics")["3"] == last["schematicId"])
            val id = UUID.nameUUIDFromBytes("arccivs-plan-template/${city.id}/$type".toByteArray(Charsets.UTF_8))
            auto1ReplayTemplate(first, id) to auto1ReplayTemplate(last, id)
        }
        check(templates.keys == (profile + listOf(BuildingType.FARM, BuildingType.WAREHOUSE)).toSet())
        for (building in buildings) {
            val template = auto1ReplayTemplate(byId.getValue(building.schematicId), building.id)
            val placed = CivsSettlementPlans.at(template, building.anchor, building.rotation, building.id)
            check(placed.bounds == building.bounds && placed.entrance == building.entrance)
            check(template.schematicId == templates.getValue(building.type).first.schematicId)
        }
        val widths = CivsSettlementGrowth.halfWidths(city)
        check(widths == listOf(48, 56, 64, 72))
        val before = gson.toJson(city)
        val outcomes = mutableListOf<Map<String, Any?>>()
        var firstSuccess: Int? = null
        var plan48: CivSettlementPlan? = null
        CivsWorksiteLayouts.installCatalog(byId.mapValues { it.value.getAsJsonObject("worksite") })
        try {
            check(CivsSettlementLayout.frame(city) == gson.fromJson(geometry["frame"], CivSettlementFrame::class.java))
            val quarry = templates.getValue(BuildingType.QUARRY).second
            // No existing quarry peer: operatingBounds uses only this L3 template, whose lineage ends here.
            check(quarry.level == 3 && CivsWorksiteLayouts.mineFamily(quarry).all { it.first in byId })
            check(CivsMinePlan.forBuilding(quarry) != null)
            write("inputs.json", mapOf("input_sha256" to inputHashes, "source_revision" to city.revision,
                "captured_at_ms" to geometry["captured_at_ms"], "profile" to profile, "widths" to widths,
                "templates" to templates, "retained_routes" to access.routes, "adopted_revision" to adopted.revision,
                "surface" to surfaceRow, "boundary" to "Pure prepare with selected catalog metadata; no source loading, native voxel capture, payment or live navigation."))
            for (width in widths) {
                val started = System.nanoTime()
                val searches = mutableListOf<CivRouteSearchStats>()
                var result: Map<String, Any?> = emptyMap()
                try {
                    val plan = CivsSettlementPlans.prepare(city, templates, surface, access, profile = profile,
                        halfWidth = width, inspectRoute = searches::add) { candidate, infrastructure ->
                        write("width-$width-candidate.json", mapOf("plan" to candidate,
                            "infrastructure" to mapOf("closed" to infrastructure.perimeterClosed,
                                "rejected" to infrastructure.rejected, "blockers" to infrastructure.blockers,
                                "rejection_samples" to infrastructure.rejectionSamples,
                                "pieces_by_type" to infrastructure.pieces.groupingBy { it.type }.eachCount())))
                    }
                    access.routes.forEach { (id, paid) -> check(plan.routes[id.toString()] == paid) }
                    write("width-$width-plan.json", plan)
                    if (width == 48) plan48 = plan
                    result = mapOf("outcome" to "prepared")
                } catch (failure: CivsRejection) {
                    result = mapOf("outcome" to "rejected", "reason" to failure.reason, "details" to failure.values)
                    if (failure.reason !in setOf("autonomy-no-site", "autonomy-layout-obstructed", "autonomy-no-path")) throw failure
                } finally {
                    val record = result + mapOf("half_width" to width, "runtime_ms" to (System.nanoTime() - started) / 1_000_000,
                        "production_reached" to (firstSuccess == null))
                    write("width-$width-result.json", record)
                    write("width-$width-searches.json", searches)
                    outcomes += record
                }
                if (result["outcome"] == "prepared" && firstSuccess == null) firstSuccess = width
            }
            check(gson.toJson(city) == before && outcomes.size == widths.size)
            write("result.json", mapOf("outcomes" to outcomes, "production_first_success_width" to firstSuccess,
                "production_terminal_failure" to outcomes.last().takeIf { firstSuccess == null },
                "native_capture_proven" to false, "diagnostic_only" to false, "expected_first_success_width" to 48))
            check(firstSuccess == 48) { "Expected adopted growth at width 48; actual outcomes: $outcomes" }
            val prepared = requireNotNull(plan48)
            check(prepared.gates == adopted.gates && prepared.gateSides == adopted.gateSides) { "Accepted gate geometry moved" }
            val gate = adopted.gates.single()
            val forester = access.routes.getValue(buildings.single { it.type == BuildingType.FORESTER }.id)
            val crossing = forester.indices.filter { CivsAutonomousGeometry.horizontalContains(gate, forester[it]) }
            check(crossing.size == 5 && crossing == (crossing.first()..crossing.last()).toList())
            val paidAxis = forester.subList(crossing.first(), crossing.last() + 5)
            check(paidAxis.map { it.x } == (gate.min.x..gate.max.x + 4).toList() && paidAxis.all { it.z == 9651 })
            val quarryRoute = prepared.routes.getValue(prepared.plots.single { it.building.type == BuildingType.QUARRY }.building.id.toString())
            check(quarryRoute.filter { CivsAutonomousGeometry.horizontalContains(gate, it) } == paidAxis.take(5)) {
                "Quarry must cross only the paid central gate columns"
            }
            check(quarryRoute.windowed(paidAxis.size).any { it == paidAxis }) {
                "Quarry must share the five central gate columns and four outward steps at their exact paid heights: $paidAxis"
            }
        } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
    }
})

/** Same origin/size alignment as the existing native replay; no schematic blocks or AIR substitution. */
private fun auto1ReplayTemplate(entry: JsonObject, id: UUID): CivBuilding {
    val site = entry.getAsJsonObject("worksite")
    val origin = CivsWorksiteLayouts.vector(entry["origin"])
    val size = CivsWorksiteLayouts.vector(entry["size"])
    val control = CivsWorksiteLayouts.vector(entry["control"])
    val bounds = site.getAsJsonObject("schematicBounds")
    val box = CivBox(CivsWorksiteLayouts.vector(bounds["min"]), CivsWorksiteLayouts.vector(bounds["max"]))
    check(box == CivBox(CivPosition(-origin.x, -origin.y, -origin.z),
        CivPosition(size.x - origin.x - 1, size.y - origin.y - 1, size.z - origin.z - 1)))
    check(site["schematicId"] == entry["schematicId"] && site["type"] == entry["type"] && site["level"] == entry["level"] &&
        site["schematicSha256"].asString.matches(Regex("[0-9a-f]{64}")))
    val entrance = CivsWorksiteLayouts.vector(site["entrance"])
    check(entrance == CivPosition(0, 1, 0))
    return CivBuilding(id, BuildingType.valueOf(entry["type"].asString), entry["level"].asInt, CivPosition(0, 0, 0), 0,
        entry["schematicId"].asString, box, entrance, BuildingPhase.CONSTRUCTING,
        CivPosition(control.x - origin.x, control.y - origin.y, control.z - origin.z))
}
