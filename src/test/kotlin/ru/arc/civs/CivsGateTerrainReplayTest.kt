package ru.arc.civs

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Optional operator replay of a bounded loaded-only export; never reads or changes a live world. */
class CivsGateTerrainReplayTest : FunSpec({
    test("replay the pinned gate approach against its captured surface and retained routes") {
        val input = System.getenv("CIVS_GATE_REPLAY_INPUT") ?: return@test
        val output = Path.of(requireNotNull(System.getenv("CIVS_GATE_REPLAY_OUT")))
        val gson = GsonBuilder().setPrettyPrinting().create()
        val document = JsonParser.parseString(Files.readString(Path.of(input))).asJsonObject
        val row = document.getAsJsonObject("city")
        val buildings = document.getAsJsonArray("buildings").map { entry ->
            val value = entry.asJsonObject.deepCopy()
            value.add("bounds", value.remove("actual_bounds"))
            value.add("schematicId", value.remove("schematic"))
            gson.fromJson(value, CivBuilding::class.java)
        }
        val city = Settlement(UUID.fromString(row["id"].asString), UUID.fromString(row["owner"].asString),
            row["name"].asString, UUID.fromString(row["world"].asString),
            gson.fromJson(row["center"], CivPosition::class.java), row["radius"].asInt,
            row["style"].asString, Stock(), buildings, emptyList(),
            autonomy = gson.fromJson(row["autonomy"], CivAutonomy::class.java), inventory = CivInventory(CivStorageMode.PHYSICAL))
        val field = document.getAsJsonObject("surface")
        val surface = CivTerrainSurface(field["min_height"].asInt, field["max_height"].asInt,
            field.getAsJsonArray("columns").associate { entry ->
                val c = entry.asJsonObject
                fun integer(key: String) = c[key]?.takeUnless { it.isJsonNull }?.asInt
                civsTerrainKey(c["x"].asInt, c["z"].asInt) to CivTerrainSurfaceColumn(c["ground_y"].asInt,
                    c["natural"].asBoolean, c["loaded"].asBoolean, c["top_y"].asInt,
                    integer("water_top_y"), integer("water_bottom_y"), c["dirt_path"]?.asBoolean ?: false)
            })
        val access = CivAutonomousAccess(document.getAsJsonArray("access_routes").associate { entry ->
            val r = entry.asJsonObject
            UUID.fromString(r["building"].asString) to r.getAsJsonArray("positions").map {
                gson.fromJson(it, CivPosition::class.java)
            }
        })
        val catalog = System.getenv("CIVS_ACTUAL_CATALOG_ROOT")?.let { CivBlueprintCatalog.load(Path.of(it)) }
        CivsWorksiteLayouts.installCatalog(catalog?.worksiteLayouts.orEmpty())
        try {
            val gate = buildings.single { it.type == BuildingType.GATE && it.phase == BuildingPhase.SITE }
            val frame = CivsSettlementLayout.frame(city)
            val proof = linkedMapOf<String, Any?>("gate" to gate, "frame" to frame,
                "surfaceEvidence" to document["evidence"], "retainedRouteOwners" to access.routes.keys)
            for ((label, obstacles) in listOf("current-frame" to frame.wallObstacles, "retained-buildings-only" to emptyList())) {
                proof[label] = try {
                    mapOf("route" to CivsAutonomousTerraform.connection(city, gate, surface, access, obstacles))
                } catch (failure: CivsRejection) { mapOf("reason" to failure.reason, "details" to failure.values) }
            }
            val lowest = buildMap {
                for (x in gate.bounds.min.x..gate.bounds.max.x) for (z in gate.bounds.min.z..gate.bounds.max.z)
                    put(civsTerrainKey(x, z), gate.bounds.min.y)
            }
            val transitFloors = lowest + gate.transit.orEmpty().associate { civsTerrainKey(it.x, it.z) to it.y - 1 }
            for ((label, floors) in listOf("lowest-captured-cell" to lowest, "exact-transit-floor" to transitFloors)) {
                proof["draft-$label"] = try {
                    val draft = CivsAutonomousTerraform.draft(city, CivTerraformOption(gate, 0), surface, access,
                        frame.wallObstacles, floors)
                    mapOf("route_nodes" to draft.route.size, "columns" to draft.columns.size)
                } catch (failure: CivsRejection) { mapOf("reason" to failure.reason, "details" to failure.values) }
            }
            Files.writeString(output, gson.toJson(proof))
        } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
    }
})
