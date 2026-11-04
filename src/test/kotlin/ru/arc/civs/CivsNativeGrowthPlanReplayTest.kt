package ru.arc.civs

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/** Private native capture: the real two-building town, its paid approach and only its next ordinary goal. */
class CivsNativeGrowthPlanReplayTest : FunSpec({
    test("replay the native first forester forecast with its completed hut approach")
        .config(enabled = System.getenv("CIVS_NATIVE_GROWTH_INPUT") != null) {
        val root = Path.of(requireNotNull(System.getenv("CIVS_NATIVE_GROWTH_INPUT")))
        val out = Files.createDirectories(Path.of(requireNotNull(System.getenv("CIVS_NATIVE_GROWTH_OUT"))))
        val gson = GsonBuilder().serializeNulls().create()
        fun input(glob: String): JsonObject = Files.newDirectoryStream(root, glob).use { paths ->
            Files.newBufferedReader(paths.single()).use { JsonParser.parseReader(it).asJsonObject }
        }
        fun write(file: String, value: Any) = Files.writeString(out.resolve(file), gson.toJson(value))
        val layout = input("*auto3-layout*.json")
        val city = civsPlanBoardCapture(gson.fromJson(input("*auto3-settlement*.json"), Settlement::class.java))
        val catalog = CivBlueprintCatalog.parse(input("*catalog*.json"))
        val cityRow = layout.getAsJsonObject("city")
        check(cityRow["id"].asString == city.id.toString() && cityRow["world"].asString == city.worldId.toString() &&
            cityRow["radius"].asInt == city.radius && cityRow["style"].asString == city.style &&
            gson.fromJson(cityRow["center"], CivPosition::class.java) == city.center)
        check(city.autonomy?.plan == null && city.autonomy?.layoutHalfWidth ==
            cityRow.getAsJsonObject("autonomy")["layoutHalfWidth"].asInt && city.construction == null &&
            city.constructionQueue.orEmpty().isEmpty())
        val profile = CivsSettlementGrowth.profile(city)
        check(profile == listOf(BuildingType.TOWN_HALL, BuildingType.BUILDER_HUT, BuildingType.FORESTER))
        val buildings = layout.getAsJsonArray("buildings").map { it.asJsonObject }.associateBy { it["id"].asString }
        val journals = layout.getAsJsonArray("saved_plans").map { it.asJsonObject }.associateBy { it["building"].asString }
        check(buildings.size == city.buildings.size && layout.getAsJsonArray("placed_infrastructure").size() == 0)
        for (b in city.buildings) {
            val row = buildings.getValue(b.id.toString())
            val source = requireNotNull(catalog.entry(b.schematicId))
            val native = CivsSettlementPlans.at(replayTemplate(source, b.id), b.anchor, b.rotation, b.id)
            check(row["schematic"].asString == b.schematicId && row["phase"].asString == b.phase.name &&
                gson.fromJson(row["actual_bounds"], CivBox::class.java) == b.bounds &&
                gson.fromJson(row["anchor"], CivPosition::class.java) == b.anchor &&
                gson.fromJson(row["entrance"], CivPosition::class.java) == b.entrance &&
                gson.fromJson(row["control"], CivPosition::class.java) == b.control)
            check(native.bounds == b.bounds && native.entrance == b.entrance &&
                source.hash == journals.getValue(b.id.toString())["captured_sha256"].asString)
        }
        val surfaceRow = layout.getAsJsonObject("surface")
        val columns = surfaceRow.getAsJsonArray("columns").associate { item ->
            val row = item.asJsonObject
            fun optional(key: String) = row[key]?.takeUnless { it.isJsonNull }?.asInt
            civsTerrainKey(row["x"].asInt, row["z"].asInt) to CivTerrainSurfaceColumn(row["ground_y"].asInt,
                row["natural"].asBoolean, row["loaded"].asBoolean, row["top_y"].asInt,
                optional("water_top_y"), optional("water_bottom_y"), row["dirt_path"].asBoolean)
        }
        val surface = CivTerrainSurface(surfaceRow["min_height"].asInt, surfaceRow["max_height"].asInt, columns)
        check(surfaceRow["chunks_loaded_by_export"].asInt == 0 && columns.size == (city.radius * 2 + 1).let { it * it } &&
            columns.values.all { it.loaded } && layout.getAsJsonArray("missing_plan_ids").size() == 0)
        val hut = city.buildings.single { it.type == BuildingType.BUILDER_HUT }
        val access = CivAutonomousAccess(layout.getAsJsonArray("access_routes").associate { item ->
            val row = item.asJsonObject
            val id = UUID.fromString(row["building"].asString)
            val journal = journals.getValue(id.toString())
            check(journal["id"].asString == row["plan"].asString && journal["status"].asString == "validated" &&
                journal["route_validation"].asString == "validated-retained-route" && journal["validated_route"] == row["positions"])
            id to row.getAsJsonArray("positions").map { gson.fromJson(it, CivPosition::class.java) }
        })
        check(hut.phase == BuildingPhase.ACTIVE && access.routes.keys == setOf(hut.id) &&
            access.routes.getValue(hut.id).last() == hut.entrance)
        val templates = profile.associateWith { type ->
            val first = requireNotNull(catalog.autonomous(city.style, type))
            val last = requireNotNull(catalog.upgrade(first.id, first.maxLevel))
            val id = UUID.nameUUIDFromBytes("arccivs-plan-template/${city.id}/$type".toByteArray(Charsets.UTF_8))
            replayTemplate(first, id) to replayTemplate(last, id)
        }
        val widths = CivsSettlementGrowth.halfWidths(city)
        write("inputs.json", mapOf("city" to city.id, "world" to city.worldId, "style" to city.style,
            "city_revision" to city.revision, "capture_revision" to layout["source_revision"],
            "layout_key" to civsPlanBoardLayoutKey(city), "surface_coverage" to surfaceRow["coverage"],
            "profile" to profile, "templates" to templates, "retained" to access.routes, "widths" to widths))
        CivsWorksiteLayouts.installCatalog(catalog.worksiteLayouts)
        try {
            ordinaryGrowthCapture(city, catalog, surface, access) { draft, status ->
                write("ordinary-forester-draft.json", mapOf("evidence" to "real placement search at native capture boundary; no world writes",
                    "draft" to draft, "placement" to status))
            }
            // prepareGrowth tries these same widths and these same geometry refusals; capture its existing
            // prepare callbacks here so the rejected plot/gate and final route search are not lost.
            var last: CivsRejection? = null
            for (width in widths) {
                val searches = mutableListOf<CivRouteSearchStats>()
                try {
                    val plan = CivsSettlementPlans.prepare(city, templates, surface, access, profile = profile,
                        halfWidth = width, inspectRoute = searches::add) { candidate, infrastructure ->
                        write("width-$width-candidate.json", mapOf("plan" to candidate, "infrastructure" to infrastructure))
                    }
                    check(plan.routes[hut.id.toString()] == access.routes[hut.id])
                    write("result.json", mapOf("outcome" to "prepared", "width" to width, "plan" to plan))
                    last = null
                    break
                } catch (failure: CivsRejection) {
                    write("width-$width-failure.json", mapOf("reason" to failure.reason, "values" to failure.values))
                    if (failure.reason !in setOf("autonomy-no-site", "autonomy-layout-obstructed", "autonomy-no-path")) throw failure
                    last = failure
                } finally { write("width-$width-searches.json", searches) }
            }
            last?.let { failure ->
                check(failure.reason == "autonomy-no-path")
                withOrdinaryGrowthPlacement(city, catalog, surface, access) { placement, captured ->
                    InitialPlanHarness().use { h ->
                        h.city = city
                        var forecast: CivsRejection? = null
                        var requests = 0
                        every { h.placement.plan(any(), null) } answers {
                            requests++
                            try { CompletableFuture.completedFuture(CivsSettlementPlans.prepareGrowth(firstArg(),
                                templates, surface, access, profile)) }
                            catch (declined: CivsRejection) {
                                forecast = declined
                                CompletableFuture.failedFuture(declined)
                            }
                        }
                        every { h.placement.search(any(), BuildingType.FORESTER, 1, any(), false, null, 0) } answers {
                            placement.search(firstArg(), BuildingType.FORESTER, 1, arg(3))
                        }
                        h.start(); h.time = 5_001; h.pulse()
                        val draft = captured()
                        write("result.json", mapOf("forecast" to forecast?.reason, "details" to forecast?.values,
                            "requests" to requests, "progress" to h.development.progress(city.id),
                            "ordinary_native_capture_requested" to (draft != null), "draft" to draft))
                        check(requests == 1 && forecast?.reason == "autonomy-layout-obstructed" &&
                            forecast?.values?.get("stage") == "infrastructure" && draft != null &&
                            h.development.debug(city.id)["request"] != null && h.city == city && h.saves == 0 && h.commits == 0) {
                            "No ordinary FORESTER native capture after unadopted forecast: ${h.development.progress(city.id)}"
                        }
                        requireOrdinaryGrowthDraft(city, access, draft)
                    }
                }
            }
        } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
    }
})

/** The curated manifest validates these bounds and entrance against origin/size for every supported source. */
private fun replayTemplate(entry: CivBlueprintCatalogEntry, id: UUID): CivBuilding {
    val site = requireNotNull(entry.worksite)
    val bounds = site.getAsJsonObject("schematicBounds")
    return CivBuilding(id, entry.type, entry.level, CivPosition(0, 0, 0), 0, entry.id,
        CivBox(CivsWorksiteLayouts.vector(bounds["min"]), CivsWorksiteLayouts.vector(bounds["max"])),
        CivsWorksiteLayouts.vector(site["entrance"]), BuildingPhase.CONSTRUCTING,
        CivPosition(entry.control[0] - entry.origin[0], entry.control[1] - entry.origin[1], entry.control[2] - entry.origin[2]))
}

/** Keep the genuine one-goal search and all its candidate/route guards. The height field is not a volume
 * witness: stop at the first requested fresh capture without pretending it succeeded. */
private fun ordinaryGrowthCapture(city: Settlement, catalog: CivBlueprintCatalog, surface: CivTerrainSurface,
    access: CivAutonomousAccess, inspect: (CivTerraformDraft?, Map<String, Any?>) -> Unit) {
    withOrdinaryGrowthPlacement(city, catalog, surface, access) { placement, captured ->
        val id = UUID.nameUUIDFromBytes("native-growth-forester/${city.id}".toByteArray(Charsets.UTF_8))
        val pending = placement.search(city, BuildingType.FORESTER, 1, id)
        val status = placement.debug(city.id)
        inspect(captured(), status)
        if (pending.isDone) pending.join()
        check(!pending.isDone)
        requireOrdinaryGrowthDraft(city, access, requireNotNull(captured()) { "No independent native capture requested: $status" })
    }
}

private fun withOrdinaryGrowthPlacement(city: Settlement, catalog: CivBlueprintCatalog, surface: CivTerrainSurface,
    access: CivAutonomousAccess, use: (CivsAutonomousPlacement, () -> CivTerraformDraft?) -> Unit) {
    val blueprints = mockk<CivsBlueprints>()
    val world = mockk<CivsWorld>()
    val type = BuildingType.FORESTER
    every { blueprints.autonomousSource(city.style, type) } returns requireNotNull(catalog.autonomous(city.style, type)).id
    every { blueprints.autonomousAlternatives(city.style, type) } returns catalog.autonomousAlternatives(city.style, type).map { it.id }
    every { blueprints.maximumLevel(any()) } answers { requireNotNull(catalog.entry(firstArg<CivBuilding>().schematicId)).maxLevel }
    fun blueprint(entry: CivBlueprintCatalogEntry, id: UUID, at: CivPosition, rotation: Int): CivBlueprint =
        CivBlueprint(CivsSettlementPlans.at(replayTemplate(entry, id), at, rotation, id), city.worldId,
            mockk(relaxed = true), emptyList(), emptyList(), byteArrayOf())
    every { blueprints.prepareEntry(any(), any(), city.worldId, any(), any(), any()) } answers {
        CompletableFuture.completedFuture(blueprint(requireNotNull(catalog.entry(firstArg())), arg(1), arg(3), arg(5)))
    }
    every { blueprints.prepareUpgrade(any(), any(), city.worldId) } answers {
        val b = firstArg<CivBuilding>()
        CompletableFuture.completedFuture(blueprint(requireNotNull(catalog.upgrade(b.schematicId, arg(1))), b.id, b.anchor, b.rotation))
    }
    every { world.autonomousHeightField(city, any(), null, any()) } returns CompletableFuture.completedFuture(surface)
    var draft: CivTerraformDraft? = null
    every { world.captureTerraform(city, any(), any(), null) } answers {
        draft = arg(1)
        CompletableFuture<CivTerraformPlan>()
    }
    CivsAutonomousPlacement(blueprints, world, Executor(Runnable::run), { CompletableFuture.completedFuture(access) }).use { placement ->
        use(placement) { draft }
    }
}

private fun requireOrdinaryGrowthDraft(city: Settlement, access: CivAutonomousAccess, found: CivTerraformDraft) {
    val paid = access.routes.values.flatten().associateBy { civsTerrainKey(it.x, it.z) }
    check(found.building.type == BuildingType.FORESTER && found.route.first() == city.buildings.first().entrance &&
        found.route.last() == found.building.entrance && found.route.all { p -> paid[civsTerrainKey(p.x, p.z)]?.let { it == p } != false })
}
