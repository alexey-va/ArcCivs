package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executor

class CivsWallInfillTest : FunSpec({
    test("a bounded two-column seam uses the normal wall recipe on both axes and either side") {
        MockBukkitTestRuntime.open().use {
            for (acrossX in listOf(false, true)) for (positive in listOf(false, true)) {
                val f = wallInfillFixture(acrossX, positive)
                val target = CivsWallInfill.target(f.city, f.gate.id, f.wall.id)
                val (piece, surface) = CivsWallInfill.prepare(f.city, target, f.surface, f.access, emptyList())
                piece.blocks.all { CivsAutonomousGeometry.horizontalContains(target.footprint, it.position) } shouldBe true
                piece.blocks.map { it.position.x to it.position.z }.toSet().size shouldBe 6
                piece.id shouldBe CivsWallInfill.prepare(f.city, target, f.surface, f.access, emptyList()).first.id
                f.surface.at(f.path.x, f.path.z)!!.natural shouldBe false
                surface.at(f.path.x, f.path.z)!!.natural shouldBe true
                surface.columns.filterKeys { it != civsTerrainKey(f.path.x, f.path.z) } shouldBe
                    f.surface.columns.filterKeys { it != civsTerrainKey(f.path.x, f.path.z) }
                val plan = f.plan(piece)
                if (positive) CivsMaterials.required(plan) shouldBe mapOf("DARK_OAK_FENCE" to 2L, "STONE_BRICKS" to 26L)
                f.city.building(f.gate.id) shouldBe f.gate
                f.city.building(f.wall.id) shouldBe f.wall
            }
        }
    }

    test("current routes accepted future work missing owners and different gaps fail before site admission") {
        val f = wallInfillFixture()
        val target = CivsWallInfill.target(f.city, f.gate.id, f.wall.id)
        fun rejected(access: CivAutonomousAccess = f.access, work: List<CivPlacementPlan> = emptyList()) =
            shouldThrow<CivsRejection> { CivsWallInfill.prepare(f.city, target, f.surface, access, work) }
        rejected(f.access.copy(routes = f.access.routes + (f.wall.id to listOf(f.path.copy(y = 66))))).reason shouldBe "wall-infill-route-conflict"
        rejected(f.access.copy(infrastructure = f.access.infrastructure.filterNot { it.id == f.wall.id })).reason shouldBe "wall-infill-source-missing"
        val future = f.plan(f.access.infrastructure.first()).copy(building = f.wall.copy(id = UUID(183, 900),
            bounds = target.footprint.copy(max = target.footprint.max.copy(y = 72))))
        rejected(work = listOf(future)).reason shouldBe "building-overlap"
        val route = future.copy(building = f.wall, terrain = CivTerraformPlan(target.footprint, emptyList(), listOf(f.path), 64, 0, 0))
        rejected(work = listOf(route)).reason shouldBe "wall-infill-route-conflict"
        shouldThrow<CivsRejection> { CivsWallInfill.target(f.city.copy(buildings = f.city.buildings.map {
            if (it.id == f.wall.id) it.copy(bounds = it.bounds.copy(min = it.bounds.min.copy(z = it.bounds.min.z + 1))) else it
        }), f.gate.id, f.wall.id) }.reason shouldBe "wall-infill-unsupported"
    }

    test("normal queued SITE keeps the old order and immutable owners and retains scoped path admission after reload") {
        MockBukkitTestRuntime.open().use {
            val f = wallInfillFixture()
            val target = CivsWallInfill.target(f.city, f.gate.id, f.wall.id)
            val (piece, _) = CivsWallInfill.prepare(f.city, target, f.surface, f.access, emptyList())
            val active = wallInfillExistingOrder(f.city)
            val building = CivsInfrastructure.building(piece)
            val queued = CivsRules.enqueue(CivsRules.placeSite(active, active.owner, building), active.owner, piece.id)
            queued.construction shouldBe active.construction
            queued.inventory shouldBe active.inventory
            queued.stock shouldBe active.stock
            queued.building(piece.id)!!.phase shouldBe BuildingPhase.SITE
            queued.constructionQueue shouldBe listOf(piece.id)
            val persisted = queued.copy(revision = 1)
            val root = Files.createTempDirectory("wall-infill-site-")
            try {
                CivsStore(root, Executor { it.run() }).use { store ->
                    store.save(active.copy(revision = 0)).join(); store.save(persisted).join()
                    store.savePlan(f.gatePlan).join(); store.savePlan(f.wallPlan).join(); store.savePlan(f.plan(piece)).join()
                }
                CivsStore(root, Executor { it.run() }).use { store ->
                    val loaded = store.load().join().single()
                    loaded shouldBe persisted
                    store.loadPlan(f.gatePlan.id).join() shouldBe f.gatePlan
                    store.loadPlan(f.wallPlan.id).join() shouldBe f.wallPlan
                    val recovered = CivsWallInfill.acceptedSurface(loaded, building, f.surface, f.access)
                    recovered.at(f.path.x, f.path.z)!!.natural shouldBe true
                    CivsWallInfill.acceptedSurface(loaded, building.copy(id = UUID(183, 901)), f.surface, f.access) shouldBe f.surface
                    val movedRoute = f.access.copy(routes = f.access.routes + (f.wall.id to listOf(f.path)))
                    shouldThrow<CivsRejection> { CivsWallInfill.acceptedSurface(loaded, building, f.surface, movedRoute) }
                }
            } finally { root.toFile().deleteRecursively() }
        }
    }

    test("exact before image and material drift invalidate a preview without depending on worker ticks") {
        val f = wallInfillFixture()
        val target = CivsWallInfill.target(f.city, f.gate.id, f.wall.id)
        val plan = f.plan(CivsWallInfill.prepare(f.city, target, f.surface, f.access, emptyList()).first)
        val proof = CivsWallInfill.proof(f.city, target, plan)
        CivsWallInfill.proof(f.city.copy(revision = f.city.revision + 1), target, plan) shouldBe proof
        (CivsWallInfill.proof(f.city, target, plan.copy(blocks = plan.blocks.mapIndexed { i, b ->
            if (i == 0) b.copy(before = "minecraft:stone") else b
        })) == proof) shouldBe false
    }

    test("the actual Gate13 seam is generated from surveyed ground without changing any accepted piece")
        .config(enabled = System.getenv("CIVS_WALL_INFILL_CITY") != null) {
            MockBukkitTestRuntime.open().use {
                val gson = Gson()
                fun document(name: String) = JsonParser.parseString(Files.readString(Path.of(requireNotNull(System.getenv(name))))).asJsonObject
                CivsWorksiteLayouts.installCatalog(CivBlueprintCatalog.parse(document("CIVS_GATE_VARIANTS_CATALOG")).worksiteLayouts)
                try {
                    val city = gson.fromJson(document("CIVS_WALL_INFILL_CITY"), Settlement::class.java).let(CivsRules::validate)
                    val old = document("CIVS_WALL_INFILL_LAYOUT")
                    val plans = document("CIVS_WALL_INFILL_PLANS").getAsJsonArray("plans").map {
                        gson.fromJson(it.asJsonObject["plan"], CivPlacementPlan::class.java)
                    }.filterNot { it.building.type == BuildingType.GATE } + document("CIVS_GATE_CLEAN_INPUT").getAsJsonArray("plans").map {
                        gson.fromJson(it.asJsonObject.getAsJsonObject("stored")["plan"], CivPlacementPlan::class.java)
                    }
                    val routes = old.getAsJsonArray("access_routes").associate { row ->
                        val r = row.asJsonObject; UUID.fromString(r["building"].asString) to
                            r.getAsJsonArray("positions").map { gson.fromJson(it, CivPosition::class.java) }
                    }.mapValues { (id, route) -> city.roadRouteOverrides?.get(id.toString())?.route ?: route }
                    val access = CivAutonomousAccess(routes, CivsSettlementLayout.accepted(city, plans))
                    val columns = old.getAsJsonObject("surface").getAsJsonArray("columns").associate { row ->
                        val c = row.asJsonObject
                        civsTerrainKey(c["x"].asInt, c["z"].asInt) to CivTerrainSurfaceColumn(c["ground_y"].asInt,
                            c["natural"].asBoolean, c["loaded"].asBoolean, c["top_y"].asInt, dirtPath = c["dirt_path"].asBoolean)
                    }.toMutableMap()
                    val nativeDocument = document("CIVS_WALL_INFILL_NATIVE")
                    val native = nativeDocument.getAsJsonArray("cells").map { it.asJsonObject }
                    native.groupBy { civsTerrainKey(it["x"].asInt, it["z"].asInt) }.forEach { (key, cells) ->
                        val top = cells.filter { it["name"].asString != "air" }.maxBy { it["y"].asInt }
                        columns[key] = CivTerrainSurfaceColumn(top["y"].asInt, top["name"].asString != "dirt_path",
                            dirtPath = top["name"].asString == "dirt_path")
                    }
                    val surveyed = gson.fromJson(nativeDocument["requested"], CivBox::class.java)
                    val target = city.buildings.filter { it.type == BuildingType.GATE }.flatMap { gate ->
                        city.buildings.filter { it.type == BuildingType.WALL }.mapNotNull { wall ->
                            runCatching { CivsWallInfill.target(city, gate.id, wall.id) }.getOrNull()?.takeIf {
                                it.footprint.min.x == surveyed.min.x && it.footprint.max.x == surveyed.max.x &&
                                    it.footprint.min.z == surveyed.min.z && it.footprint.max.z == surveyed.max.z
                            }
                        }
                    }.single()
                    val (piece, _) = CivsWallInfill.prepare(city, target, CivTerrainSurface(-64, 320, columns), access, emptyList())
                    val plan = CivPlacementPlan(piece.id, city.id, city.worldId, CivsInfrastructure.building(piece),
                        piece.blocks.map { CivPlanBlock(it.position, "minecraft:air", it.after) })
                    CivsMaterials.required(plan) shouldBe mapOf("STONE_BRICKS" to 26L, "DARK_OAK_FENCE" to 2L)
                    System.getenv("CIVS_WALL_INFILL_OUT")?.let { Files.writeString(Path.of(it), gson.toJson(mapOf(
                        "evidence" to "Source geometry with old retained route fixture plus current native gap ground; runtime preview must load current routes and accepted work.",
                        "target" to target, "piece" to piece, "materials" to CivsMaterials.required(plan)))) }
                } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
            }
        }
})

internal data class WallInfillFixture(val city: Settlement, val gate: CivBuilding, val wall: CivBuilding,
    val gatePlan: CivPlacementPlan, val wallPlan: CivPlacementPlan, val access: CivAutonomousAccess,
    val surface: CivTerrainSurface, val path: CivPosition) {
    fun plan(piece: CivSettlementPiece) = CivPlacementPlan(piece.id, city.id, city.worldId, CivsInfrastructure.building(piece),
        piece.blocks.map { CivPlanBlock(it.position, "minecraft:air", it.after) }, "a".repeat(64))
}

internal fun wallInfillFixture(acrossX: Boolean = false, positive: Boolean = true): WallInfillFixture {
    val f = gateVariantFixture(width = 6, alongX = acrossX)
    val gate = requireNotNull(f.after.building(f.gate.id))
    val first = if (positive) 28 else 12
    fun p(axis: Int, y: Int, cross: Int) = if (acrossX) CivPosition(axis, y, cross) else CivPosition(cross, y, axis)
    val cells = buildList { for (a in first..first + 5) for (c in 21..23) {
        for (y in 64..67) add(CivSettlementBlock(p(a, y, c), "minecraft:stone_bricks"))
        if (c == 22) add(CivSettlementBlock(p(a, 68, c), "minecraft:dark_oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]"))
    } }
    val piece = CivSettlementPiece(UUID(183, 100), BuildingType.WALL, CivBox(p(first, 64, 21), p(first + 5, 68, 23)),
        p(first, 65, 20), cells)
    val wall = CivsInfrastructure.building(piece).copy(phase = BuildingPhase.ACTIVE)
    val city = CivsRules.validate(f.after.copy(buildings = f.after.buildings + wall))
    val wallPlan = CivPlacementPlan(wall.id, city.id, city.worldId, wall.copy(phase = BuildingPhase.CONSTRUCTING),
        cells.map { CivPlanBlock(it.position, "minecraft:air", it.after) }, "a".repeat(64))
    val gatePlan = f.receipt.replacement.let { original ->
        val present = original.blocks.map { it.position }.toSet()
        original.copy(blocks = original.blocks + f.gate.transit.orEmpty().map { it.copy(y = it.y - 1) }
            .filterNot { it in present }.map { CivPlanBlock(it, "minecraft:air", "minecraft:cobblestone") })
    }
    val access = CivAutonomousAccess(mapOf(wall.id to listOf(wall.entrance)), CivsSettlementLayout.accepted(city,
        listOf(gatePlan, wallPlan)))
    val target = CivsWallInfill.target(city, gate.id, wall.id)
    val path = if (acrossX) target.footprint.min.copy(z = target.footprint.max.z, y = 65)
        else target.footprint.min.copy(x = target.footprint.max.x, y = 65)
    val surface = settlementPlanSurface(city).let { it.copy(columns = it.columns +
        (civsTerrainKey(path.x, path.z) to CivTerrainSurfaceColumn(65, false, dirtPath = true))) }
    return WallInfillFixture(city, gate, wall, gatePlan, wallPlan, access, surface, path)
}

internal fun wallInfillExistingOrder(city: Settlement): Settlement {
    val b = CivBuilding(UUID(183, 500), BuildingType.HOUSE, 1, CivPosition(-20, 64, 20), 0, "fixture/house",
        CivBox(CivPosition(-21, 64, 20), CivPosition(-19, 68, 22)), CivPosition(-20, 65, 19), BuildingPhase.SITE)
    val site = CivsRules.placeSite(city.copy(stock = Stock(), inventory = CivInventory(CivStorageMode.PHYSICAL)), city.owner, b)
    return CivsRules.startSite(site, site.owner, b.copy(phase = BuildingPhase.CONSTRUCTING),
        CivConstruction(UUID(183, 501), b.id, 1, 2, 0, ConstructionPhase.CONSTRUCTING, Stock(),
            plannedBuilding = b.copy(phase = BuildingPhase.CONSTRUCTING), batches = CivConstructionBatches(2, 16),
            materials = mapOf("STONE" to 2L), payment = CivConstructionPayment()))
}
