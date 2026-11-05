package ru.arc.civs

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/** Exact adopted Nordic plan and saved SITE route; earlier complete native field/voxel epochs are recorded separately. */
class CivsNativePaidForesterApproachTest : FunSpec({
    for (existingSite in listOf(false, true))
    test("native paid hut prefix serves the forester through the same three-wide gate existingSite=$existingSite")
        .config(enabled = System.getenv("CIVS_PAID_APPROACH_INPUT") != null) {
        val root = Path.of(requireNotNull(System.getenv("CIVS_PAID_APPROACH_INPUT")))
        val out = Files.createDirectories(Path.of(requireNotNull(System.getenv("CIVS_PAID_APPROACH_OUT"))))
        val gson = GsonBuilder().serializeNulls().create()
        fun read(glob: String) = Files.newDirectoryStream(root, glob).use { paths ->
            Files.newBufferedReader(paths.single()).use { JsonParser.parseReader(it).asJsonObject }
        }
        val city = gson.fromJson(read("*213*auto1-settlement*.json"), Settlement::class.java)
        val layout = read("*213*auto1-layout*.json")
        val survey = read("surface.json")
        val surface = paidApproachSurface(survey.getAsJsonObject("surface"))
        val catalog = CivBlueprintCatalog.parse(read("catalog.json"))
        val plan = requireNotNull(CivsSettlementPlans.current(city))
        val hut = city.buildings.single { it.type == BuildingType.BUILDER_HUT }
        val site = city.buildings.single { it.type == BuildingType.FORESTER }
        val rows = layout.getAsJsonArray("saved_plans").map { it.asJsonObject }.associateBy { it["building"].asString }
        val hutRow = rows.getValue(hut.id.toString())
        val siteRow = rows.getValue(site.id.toString())
        val hutJournal = gson.fromJson(read("hut-journal.json")["plan"], CivPlacementPlan::class.java)
        val siteJournal = gson.fromJson(read("forester-journal.json")["plan"], CivPlacementPlan::class.java)
        check(layout["source_revision"].asLong == city.revision && layout.getAsJsonObject("city")["world"].asString == city.worldId.toString() &&
            survey.getAsJsonObject("city")["world"].asString == city.worldId.toString() && surface.columns.size == 37_249 &&
            surface.columns.values.all { it.loaded } && city.construction == null && city.constructionQueue == listOf(site.id))
        check(hut.phase == BuildingPhase.ACTIVE && site.phase == BuildingPhase.SITE &&
            hutRow["route_validation"].asString == "validated-retained-route" && siteRow["route_validation"].asString == "building-not-active")
        val paid = hutRow.getAsJsonArray("validated_route").map { gson.fromJson(it, CivPosition::class.java) }
        val old = siteRow.getAsJsonArray("journal_route").map { gson.fromJson(it, CivPosition::class.java) }
        check(paid.size == 30 && old.size == 80 && paid == plan.routes[hut.id.toString()] && old == plan.routes[site.id.toString()])
        requireQueuedDesign(siteJournal, city, site)
        check(hutJournal.id == hut.id && hutJournal.capturedSha256 == hutRow["captured_sha256"].asString &&
            siteJournal.capturedSha256 == siteRow["captured_sha256"].asString && siteJournal.blocks.size == siteRow["blocks"].asInt &&
            siteJournal.terrain?.route == old && siteJournal.terrain?.blocks?.size == siteRow["terrain_changed_cells"].asInt)
        check(old[12] == paid[4] && old.first() == paid.first() && old[13] == CivPosition(12258,67,9651))
        val desired = paid.take(5) + old.drop(13)
        val access = CivAutonomousAccess.from(city, listOf(hutJournal,siteJournal))
        access.routes shouldBe mapOf(hut.id to paid)
        CivsWorksiteLayouts.installCatalog(catalog.worksiteLayouts)
        try {
            // Read the production piece, including the actual roof/posts/grade. The complete measured
            // footprint through the gate is identical in the old and proposed route.
            val frame = CivsSettlementLayout.frame(city)
            val infrastructure = CivsSettlementLayout.infrastructure(city, frame, surface, plan.routes.values.toList(),
                retainedRoutes = access.routes.values)
            val gate = infrastructure.pieces.single { it.type == BuildingType.GATE }
            val cells = gate.blocks.associate { it.position to it.after }
            val passage = plan.gates.single()
            val opening = desired.filter { CivsAutonomousGeometry.horizontalContains(passage, it) }
            opening shouldBe old.filter { CivsAutonomousGeometry.horizontalContains(passage, it) }
            opening shouldBe (12258..12262).map { CivPosition(it,67,9651) }
            opening.forEach { p ->
                cells[p.copy(y = 66)] shouldBe "minecraft:cobblestone"
                (67..69).forEach { y -> cells[p.copy(y = y)] shouldBe "minecraft:air" }
            }
            for (x in 12259..12261) for (z in listOf(9649,9653)) {
                (cells[CivPosition(x,67,z)] !in setOf(null,"minecraft:air")) shouldBe true
            }
            (cells[CivPosition(12260,70,9651)] !in setOf(null,"minecraft:air")) shouldBe true
            checkPaidVoxelBody(read("voxels.json"), city.worldId, paid.take(5) + opening)
            if (!existingSite) {
                val base = city.copy(buildings = city.buildings.filter { it.id != site.id }, constructionQueue = emptyList(),
                    autonomy = requireNotNull(city.autonomy).copy(plan = null, layoutHalfWidth = CivsSettlementGrowth.initialHalfWidth(city)))
                val profile = CivsSettlementGrowth.profile(base)
                profile shouldBe listOf(BuildingType.TOWN_HALL, BuildingType.BUILDER_HUT, BuildingType.FORESTER)
                fun template(entry: CivBlueprintCatalogEntry, id: UUID): CivBuilding {
                    val meta = requireNotNull(entry.worksite)
                    val box = meta.getAsJsonObject("schematicBounds")
                    return CivBuilding(id, entry.type, entry.level, CivPosition(0,0,0),0,entry.id,
                        CivBox(CivsWorksiteLayouts.vector(box["min"]),CivsWorksiteLayouts.vector(box["max"])),
                        CivsWorksiteLayouts.vector(meta["entrance"]),BuildingPhase.CONSTRUCTING,
                        CivPosition(entry.control[0]-entry.origin[0],entry.control[1]-entry.origin[1],entry.control[2]-entry.origin[2]))
                }
                val templates = profile.associateWith { type ->
                    val first = requireNotNull(catalog.autonomous(city.style,type))
                    val id = UUID.nameUUIDFromBytes("arccivs-plan-template/${city.id}/$type".toByteArray(Charsets.UTF_8))
                    template(first,id) to template(requireNotNull(catalog.upgrade(first.id,first.maxLevel)),id)
                }
                val actual = CivsSettlementPlans.prepare(base,templates,surface,access,profile = profile,halfWidth = plan.halfWidth)
                Files.writeString(out.resolve("future-plan.json"), gson.toJson(mapOf("before" to old,"expected" to desired,
                    "plan" to actual,"paid" to paid,"gate_piece" to gate,"survey_ms" to survey["captured_at_ms"])))
                actual.gates shouldBe plan.gates
                actual.routes[hut.id.toString()] shouldBe paid
                actual.routes[site.id.toString()] shouldBe desired
            } else {
                val world = mockk<CivsWorld>()
                var draft: CivTerraformDraft? = null
                every { world.autonomousHeightField(city, any(), site, any()) } returns CompletableFuture.completedFuture(surface)
                every { world.captureTerraform(city, any(), any(), site) } answers {
                    draft = arg(1)
                    CompletableFuture<CivTerraformPlan>()
                }
                CivsAutonomousPlacement(mockk(), world, Executor(Runnable::run), { CompletableFuture.completedFuture(access) }).use { placement ->
                    // The active SITE enters prepareExisting with the target geometry and its queue journal.
                    val blueprint = CivBlueprint(site.copy(phase = BuildingPhase.CONSTRUCTING), city.worldId,
                        mockk(relaxed = true), emptyList(), emptyList(), byteArrayOf())
                    val pending = placement.prepareExisting(city, blueprint)
                    if (pending.isDone) pending.join()
                    val captured = requireNotNull(draft) { "No native capture request: ${placement.debug(city.id)}" }
                    Files.writeString(out.resolve("site-capture.json"), gson.toJson(mapOf("before" to old,
                        "expected" to desired,"draft" to captured,"gate_piece" to gate,"survey_ms" to survey["captured_at_ms"],
                        "evidence" to "fresh capture requested; native world placement and its new bill require live admission")))
                    captured.building.anchor shouldBe site.anchor
                    captured.route shouldBe desired
                    captured.columns.filter { c -> paid.take(5).any { it.x == c.x && it.z == c.z } }.all { it.preserve } shouldBe true
                    pending.isDone shouldBe false
                }
            }
        } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
    }
})

private fun paidApproachSurface(row: JsonObject): CivTerrainSurface = CivTerrainSurface(row["min_height"].asInt,
    row["max_height"].asInt, row.getAsJsonArray("columns").associate { item ->
        val c = item.asJsonObject
        fun optional(key: String) = c[key]?.takeUnless { it.isJsonNull }?.asInt
        civsTerrainKey(c["x"].asInt,c["z"].asInt) to CivTerrainSurfaceColumn(c["ground_y"].asInt,c["natural"].asBoolean,
            c["loaded"].asBoolean,c["top_y"].asInt,optional("water_top_y"),optional("water_bottom_y"),c["dirt_path"].asBoolean)
    })

private fun checkPaidVoxelBody(row: JsonObject, world: UUID, feet: List<CivPosition>) {
    check(row["world"].asString == world.toString() && row["unavailable_count"].asInt == 0)
    val base = row.getAsJsonObject("bounds").getAsJsonObject("min")
    val palette = row.getAsJsonArray("palette")
    val blocks = row.getAsJsonArray("voxels").associate { item ->
        val cell = item.asJsonArray
        CivPosition(base["x"].asInt+cell[0].asInt,base["y"].asInt+cell[1].asInt,base["z"].asInt+cell[2].asInt) to
            palette[cell[3].asInt].asJsonObject.getAsJsonArray("collision")
    }
    for (p in feet) {
        blocks.getValue(p.copy(y = p.y-1)).single().asJsonArray.map { it.asInt } shouldBe listOf(0,0,0,1,1,1)
        blocks.getValue(p).size() shouldBe 0
        blocks.getValue(p.copy(y = p.y+1)).size() shouldBe 0
    }
}
