package ru.arc.civs

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

/** Optional private-input export. Selection comes from the production catalog, never a fixture sorter. */
class CivsActualCatalogSelectionTest : FunSpec({
    test("export runtime-selected catalog templates and detached radius128 terrain fixtures") {
        val source = System.getenv("CIVS_ACTUAL_CATALOG_ROOT") ?: return@test
        val root = Path.of(source).toAbsolutePath().normalize()
        val output = System.getenv("CIVS_ACTUAL_CATALOG_OUT")?.let { Path.of(it).toAbsolutePath().normalize() }
        val terrainRoot = System.getenv("CIVS_ACTUAL_CATALOG_TERRAIN_ROOT")?.let(Path::of)
        val style = System.getenv("CIVS_ACTUAL_CATALOG_STYLE") ?: "caledonia"
        val gson = GsonBuilder().setPrettyPrinting().create()
        val catalog = CivBlueprintCatalog.load(root)
        check(catalog.present && style in catalog.styles())
        val templates = linkedMapOf<String, CivBuilding>()
        val selected = linkedMapOf<BuildingType, Map<String, Any?>>()
        val entries = linkedMapOf<String, CivBlueprintCatalogEntry>()

        for (type in CivsAutonomy.profile.distinct()) {
            val first = catalog.autonomous(style, type)
            val id = UUID.nameUUIDFromBytes("arccivs-runtime-template/$style/$type".toByteArray(StandardCharsets.UTF_8))
            if (type == BuildingType.PLAN_BOARD) {
                // Blueprints.autonomousSource also bypasses the catalog for this built-in type.
                check(first == null)
                val board = actualCatalogBoardTemplate(id)
                templates["$type-1"] = board
                selected[type] = mapOf("sourceKind" to "builtin", "schematicId" to board.schematicId,
                    "firstTemplate" to board, "maximumTemplate" to board)
                continue
            }
            checkNotNull(first) { "No production autonomous source for $style/$type" }
            val maximum = checkNotNull(catalog.upgrade(first.id, first.maxLevel))
            check(first.type == type && first.level == 1 && maximum.level == first.maxLevel &&
                maximum.familyKey == first.familyKey)
            for (level in 1..first.maxLevel) {
                val entry = checkNotNull(catalog.upgrade(first.id, level))
                val payload = Files.readAllBytes(root.resolve(entry.id + ".schem"))
                val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
                check(hash == entry.hash) { "Catalog payload hash mismatch: ${entry.id}" }
                entries[entry.id] = entry
                templates["$type-$level"] = actualCatalogTemplate(entry, id)
            }
            val lastTemplate = actualCatalogTemplate(maximum, id)
            // The existing replay consumes TYPE-type.maxLevel; its DTO still records the real supported level.
            templates["$type-${type.maxLevel}"] = lastTemplate
            selected[type] = mapOf("sourceKind" to "runtime-selected", "first" to first, "maximum" to maximum,
                "firstTemplate" to templates.getValue("$type-1"), "maximumTemplate" to lastTemplate)
        }
        check(selected.keys == CivsAutonomy.profile.toSet())
        val worksites = entries.mapValues { (_, entry) -> checkNotNull(entry.worksite).deepCopy() }
        val manifest = entries.values.map { entry -> mapOf("schematicId" to entry.id, "sha256" to entry.hash,
            "sourceId" to entry.sourceId, "style" to entry.style, "family" to entry.family, "level" to entry.level,
            "maxSupportedLevel" to entry.maxLevel, "size" to entry.size, "origin" to entry.origin, "control" to entry.control) }
        val policy = mapOf("maximumAdults" to CivsRules.MAX_POPULATION,
            "nurseryPlaces" to CivsPopulation.NURSERY_PLACES, "maximumResidents" to CivsRules.MAX_RESIDENTS)
        output?.let {
            Files.createDirectories(it.parent)
            Files.writeString(it, gson.toJson(mapOf("templateMode" to "runtime-selected:$style",
                "selector" to "CivBlueprintCatalog.load.autonomous + upgrade(first.id, first.maxLevel)",
                "catalogRoot" to root.toString(), "selected" to selected, "templates" to templates,
                "worksites" to worksites, "sourceManifest" to manifest, "runtimePolicy" to policy)))
        }

        if (terrainRoot != null) {
            val directory = checkNotNull(output) { "Terrain export requires CIVS_ACTUAL_CATALOG_OUT" }.parent
            for (kind in listOf("flat", "hill", "shore")) {
                val input = terrainRoot.resolve("generator-fixture-auto2-mixed-$kind.json").toAbsolutePath().normalize()
                val document = JsonParser.parseString(Files.readString(input)).asJsonObject
                check(document["kind"].asString == "detached-production-prepare-input")
                val before = gson.fromJson(document["city"], Settlement::class.java)
                check(before.buildings.size == 1 && before.buildings.single().type == BuildingType.TOWN_HALL)
                val previous = before.buildings.single()
                val hall = CivsSettlementPlans.at(templates.getValue("TOWN_HALL-1"), previous.anchor,
                    previous.rotation, previous.id).copy(phase = BuildingPhase.ACTIVE)
                val city = before.copy(style = style, radius = 128, buildings = listOf(hall),
                    autonomy = requireNotNull(before.autonomy).copy(layoutHalfWidth = null, plan = null))
                val columns = document.getAsJsonObject("surface").getAsJsonObject("columns")
                columns.entrySet().removeIf { (key, _) ->
                    val encoded = key.toLong()
                    abs((encoded shr 32) - city.center.x) > city.radius ||
                        abs(encoded.toInt().toLong() - city.center.z) > city.radius
                }
                check(columns.size() == (city.radius * 2 + 1) * (city.radius * 2 + 1))
                document.addProperty("templateMode", "runtime-selected:$style")
                document.addProperty("sourceFixture", input.toString())
                document.addProperty("evidence", "Production catalog selection; unchanged synthetic terrain columns cropped to radius128; no native world observation")
                document.add("city", gson.toJsonTree(city))
                document.add("templates", gson.toJsonTree(templates))
                document.add("worksites", gson.toJsonTree(worksites))
                document.add("sourceManifest", gson.toJsonTree(manifest))
                document.add("runtimePolicy", gson.toJsonTree(policy))
                document.add("expect", gson.toJsonTree(mapOf("homes" to CivsAutonomy.HOUSE_COUNT,
                    "ordinary" to CivsAutonomy.completeProfile.size, "halfWidth" to CivsSettlementLayout.frame(city).halfWidth)))
                val destination = directory.resolve("generator-fixture-runtime-selected-$style-r128-$kind.json")
                check(destination != input)
                Files.writeString(destination, gson.toJson(document))
            }
        }
    }
})

internal fun actualCatalogTemplate(entry: CivBlueprintCatalogEntry, id: UUID): CivBuilding {
    val site = checkNotNull(entry.worksite) { "Missing detached bounds for ${entry.id}" }
    val bounds = site.getAsJsonObject("schematicBounds")
    val marker = entry.control.zip(entry.origin) { cell, origin -> cell - origin }
    return CivBuilding(id, entry.type, entry.level, CivPosition(0, 0, 0), 0, entry.id,
        CivBox(actualCatalogPoint(bounds, "min"), actualCatalogPoint(bounds, "max")), actualCatalogPoint(site, "entrance"),
        BuildingPhase.CONSTRUCTING, control = CivPosition(marker[0], marker[1], marker[2]))
}

private fun actualCatalogPoint(value: JsonObject, key: String): CivPosition = value.getAsJsonArray(key).let {
    CivPosition(it[0].asInt, it[1].asInt, it[2].asInt)
}

internal fun actualCatalogBoardTemplate(id: UUID): CivBuilding {
    val origin = CivsPlanBoardBlueprint.origin
    fun local(p: CivPosition) = CivPosition(p.x - origin.x, p.y - origin.y, p.z - origin.z)
    val marker = CivsPlanBoardBlueprint.body(CivsPlanBoardBlueprint.SCHEMATIC_ID).entries
        .single { it.value.substringBefore('[') == "minecraft:structure_void" }.key
    return CivBuilding(id, BuildingType.PLAN_BOARD, 1, CivPosition(0, 0, 0), 0, CivsPlanBoardBlueprint.SCHEMATIC_ID,
        CivBox(local(CivsPlanBoardBlueprint.bounds.min), local(CivsPlanBoardBlueprint.bounds.max)), CivPosition(0, 1, 0),
        BuildingPhase.CONSTRUCTING, control = local(marker))
}
