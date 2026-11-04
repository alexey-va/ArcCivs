package ru.arc.civs

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.api.ArcSchematicBlock
import ru.arc.paper.api.ArcSchematicPoint
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class CivsGateConsolidationTest : FunSpec({
    test("v5 has one complete core and solid full depth flanks at all stages and both axes") {
        MockBukkitTestRuntime.open().use {
            for (width in listOf(3, 5, 7)) for (stage in 1..3) for (alongX in listOf(false, true)) {
                val f = consolidationFixture(width = width)
                val original = f.plans.getValue(f.north).let { source -> if (width != 3) source else {
                    val b = source.building.copy(anchor = source.building.anchor.copy(x = 21),
                        bounds = source.building.bounds.copy(min = source.building.bounds.min.copy(x = 21),
                            max = source.building.bounds.max.copy(x = 23)), entrance = source.building.entrance.copy(x = 20),
                        transit = source.building.transit!!.filter { it.x in 21..23 })
                    source.copy(building = b, blocks = source.blocks.filter { it.position.x in 21..23 })
                } }
                fun p(v: CivPosition) = if (alongX) CivPosition(v.z, v.y, v.x) else v
                val old = original.building.copy(bounds = CivBox(p(original.building.bounds.min), p(original.building.bounds.max)),
                    anchor = p(original.building.anchor), entrance = p(original.building.entrance),
                    transit = original.building.transit!!.map(::p))
                val source = original.copy(building = old, blocks = original.blocks.map { it.copy(position = p(it.position)) },
                    terrain = null)
                val city = f.city.copy(buildings = f.city.buildings.filter { !it.type.isInfrastructure } +
                    old.copy(phase = BuildingPhase.ACTIVE)).let { c -> c.copy(buildings = c.buildings.map { b ->
                        if (b.type == BuildingType.TOWN_HALL) b.copy(level = stage) else b }) }
                val plan = CivsInfrastructureRebuild.proposal(city, source, emptyList())
                val b = plan.building
                b.bounds shouldBe old.bounds
                b.anchor shouldBe old.anchor
                CivsInfrastructureRebuild.version(b) shouldBe 5
                CivsGateRefit.validTransit(b) shouldBe true
                val cells = plan.blocks.associate { it.position to it.after }
                val open = b.transit!!.mapTo(hashSetOf()) { it.x to it.z }
                val middle = if (alongX) (b.bounds.min.z + b.bounds.max.z) / 2 else (b.bounds.min.x + b.bounds.max.x) / 2
                open.count { (x, z) -> if (alongX) z == middle else x == middle } shouldBe if (width == 3) 1 else 3
                for (x in b.bounds.min.x..b.bounds.max.x) for (z in b.bounds.min.z..b.bounds.max.z) {
                    if ((x to z) in open) for (y in 65..67) cells[CivPosition(x, y, z)] shouldBe "minecraft:air"
                    else for (y in 65..67) (cells[CivPosition(x, y, z)] != "minecraft:air") shouldBe true
                }
            }
        }
    }

    test("pair selection refits the survivor and only its walls then the paid seam before retiring the second gate") {
        MockBukkitTestRuntime.open().use {
            val f = consolidationFixture()
            val selected = mutableListOf<CivsInfrastructureRebuild.Work>()
            repeat(7) {
                val work = requireNotNull(CivsInfrastructureRebuild.next(f.city, f.plans.values.toList(), f.city.buildings.mapTo(hashSetOf()) { it.id }))
                selected += work
                f.complete(work)
            }
            selected.first().building.id shouldBe f.north
            selected.drop(1).take(4).map { it.building.id }.toSet() shouldBe f.walls.toSet()
            selected[5].infill?.footprint?.let { it.min.z == 27 && it.max.z == 27 } shouldBe true
            selected.last().building.id shouldBe f.south
            selected.last().survivor?.id shouldBe f.north
            f.city.building(f.south)!!.type shouldBe BuildingType.WALL
            f.city.building(f.south)!!.transit shouldBe null
            f.city.building(f.north)!!.type shouldBe BuildingType.GATE
            CivsInfrastructureRebuild.next(f.city, f.plans.values.toList(), f.city.buildings.mapTo(hashSetOf()) { it.id }) shouldBe null
        }
    }

    test("distinct perimeter layers and routes through both gates are never silently consolidated") {
        val f = consolidationFixture()
        fun pairs(city: Settlement) = CivsInfrastructureRebuild.pairs(city).size
        pairs(f.city) shouldBe 1
        pairs(f.city.copy(buildings = f.city.buildings.map { b -> if (b.id != f.south) b else b.copy(
            bounds = b.bounds.copy(min = b.bounds.min.copy(x = b.bounds.min.x + 8), max = b.bounds.max.copy(x = b.bounds.max.x + 8)),
            anchor = b.anchor.copy(x = b.anchor.x + 8), entrance = b.entrance.copy(x = b.entrance.x + 8),
            transit = b.transit!!.map { it.copy(x = it.x + 8) }) })) shouldBe 0
        val s = f.plans.getValue(f.south)
        val route = (30 downTo 19).map { CivPosition(it, 65, 35) }
        f.plans[f.south] = s.copy(terrain = CivTerraformPlan(CivBox(route.last(), route.first()), emptyList(), route, 64, 0, 0))
        CivsInfrastructureRebuild.next(f.city, f.plans.values.toList(), f.city.buildings.mapTo(hashSetOf()) { it.id }) shouldBe null
    }

    test("v4 stage three upgrades once and same material fence neighbour flags are the sole source tolerance") {
        val f = consolidationFixture()
        val source = f.plans.getValue(f.north).let { p -> p.copy(building = p.building.copy(
            schematicId = p.building.schematicId.replace("/gate/1/", "/gate/3/"))) }
        val city = f.city.copy(buildings = f.city.buildings.map { if (it.id == f.north) source.building.copy(phase = BuildingPhase.ACTIVE) else it })
        CivsGateRefit.validTransit(source.building) shouldBe true
        val target = CivsInfrastructureRebuild.proposal(city, source, emptyList())
        CivsInfrastructureRebuild.stage(target.building) shouldBe 3
        CivsInfrastructureRebuild.version(target.building) shouldBe 5
        CivsInfrastructureRebuild.needsProfile(city, target.building.copy(phase = BuildingPhase.ACTIVE)) shouldBe false
        val fence = "minecraft:oak_fence[east=false,north=false,south=true,waterlogged=false,west=false]"
        CivsInfrastructureRebuild.sameOwnedState(fence, fence.replace("north=false", "north=true")) shouldBe true
        for (changed in listOf(fence.replace("waterlogged=false", "waterlogged=true"), fence.replace("oak_fence", "spruce_fence"),
            fence.replace("north=false", "north=side"), "minecraft:air"))
            CivsInfrastructureRebuild.sameOwnedState(fence, changed) shouldBe false
        CivsInfrastructureRebuild.sameOwnedState("minecraft:oak_log[axis=y]", "minecraft:oak_log[axis=x]") shouldBe false
    }

    test("the original v4 stone footing is required rather than inferred from an arbitrary wood block") {
        val f = consolidationFixture()
        val source = f.plans.getValue(f.north)
        val malformed = source.copy(blocks = source.blocks.map { cell ->
            if (cell.position == CivPosition(22, 65, 20)) cell.copy(after = "minecraft:oak_log[axis=y]") else cell
        })
        shouldThrow<CivsRejection> { CivsInfrastructureRebuild.proposal(f.city, malformed, emptyList()) }
            .reason shouldBe "gate-refit-unsupported"
    }

    test("one column seam uses the retained plinth grade before slope checks without rewriting the observed surface") {
        MockBukkitTestRuntime.open().use {
            val f = consolidationFixture()
            val old = f.plans.getValue(f.north)
            fun up(p: CivPosition) = p.copy(y = p.y + 1)
            val raised = old.copy(building = old.building.copy(anchor = up(old.building.anchor),
                bounds = CivBox(up(old.building.bounds.min), up(old.building.bounds.max)), entrance = up(old.building.entrance),
                transit = old.building.transit!!.map(::up)), blocks = old.blocks.map { it.copy(position = up(it.position)) },
                terrain = old.terrain?.let { it.copy(bounds = CivBox(up(it.bounds.min), up(it.bounds.max)), route = it.route.map(::up)) })
            f.plans[f.north] = raised
            f.city = f.city.copy(buildings = f.city.buildings.map { if (it.id == f.north) raised.building.copy(phase = BuildingPhase.ACTIVE) else it })
            repeat(5) { f.complete(requireNotNull(CivsInfrastructureRebuild.next(f.city, f.plans.values.toList(), f.available()))) }
            val target = requireNotNull(CivsInfrastructureRebuild.next(f.city, f.plans.values.toList(), f.available())!!.infill)
            val observed = f.surface.copy(columns = f.surface.columns + (21..23).associate { x ->
                civsTerrainKey(x, 27) to CivTerrainSurfaceColumn(63, true)
            })
            val (piece, retainedSurface) = CivsWallInfill.prepare(f.city, target, observed,
                CivAutonomousAccess.from(f.city, f.plans.values.toList()), emptyList())
            piece.bounds.min.y shouldBe 64
            piece.blocks.filter { it.position.y == 64 }.map { it.after } shouldBe List(3) { "minecraft:cobblestone" }
            retainedSurface shouldBe observed
            (21..23).forEach { x -> retainedSurface.at(x, 27)!!.groundY shouldBe 63 }
        }
    }

    test("fortified depth nine closure retains seven solid inner rows and the full high wall deck within the same bounds") {
        MockBukkitTestRuntime.open().use {
            val f = consolidationFixture(depth = 9)
            repeat(5) { f.complete(requireNotNull(CivsInfrastructureRebuild.next(f.city, f.plans.values.toList(), f.available()))) }
            val work = requireNotNull(CivsInfrastructureRebuild.next(f.city, f.plans.values.toList(), f.available()))
            work.building.id shouldBe f.south
            work.survivor?.id shouldBe f.north
            val plan = f.proposal(work)
            plan.building.bounds shouldBe work.building.bounds
            CivsInfrastructureRebuild.stage(plan.building) shouldBe 3
            val cells = plan.blocks.associate { it.position to it.after }
            for (z in 33..37) for (x in 19..25) for (y in 65..71)
                cells[CivPosition(x, y, z)] shouldBe "minecraft:stone_bricks"
            for (z in 33..37) for (x in listOf(18, 26)) for (y in 65..73)
                cells[CivPosition(x, y, z)] shouldBe "minecraft:air"
            plan.blocks.all { work.building.bounds.contains(it.position.x, it.position.y, it.position.z) } shouldBe true
            f.complete(work)
            CivsInfrastructureRebuild.next(f.city, f.plans.values.toList(), f.available()) shouldBe null
        }
    }

    test("conversion is an ordinary durable paid order with unchanged routes and type changing only at native finish") {
        MockBukkitTestRuntime.open().use {
            val f = consolidationFixture()
            repeat(6) { f.complete(requireNotNull(CivsInfrastructureRebuild.next(f.city, f.plans.values.toList(), f.available()))) }
            val old = f.city.building(f.south)!!
            val plan = f.proposal(requireNotNull(CivsInfrastructureRebuild.next(f.city, f.plans.values.toList(), f.available())))
            val queued = CivsRules.enqueue(f.city, f.city.owner, old.id)
            requireQueuedDesign(plan.copy(id = queuedDesignId(old)), queued, old)
            val materials = CivsMaterials.required(plan)
            val order = CivConstruction(UUID(291, 99), old.id, 1, 1, 0, ConstructionPhase.CONSTRUCTING,
                CivsAutonomy.cost(BuildingType.WALL, 1, materials), plannedBuilding = plan.building,
                batches = CivConstructionBatches(1, 16, atomicPairs = true), materials = materials)
            val reserved = CivsRules.reserveBuild(queued, queued.owner, plan.building, order)
            reserved.building(old.id) shouldBe old
            val gson = GsonBuilder().create()
            val restored = CivsRules.validate(gson.fromJson(gson.toJson(reserved), Settlement::class.java))
            val finished = CivsRules.completeBatch(restored.copy(construction = restored.construction!!.copy(
                phase = ConstructionPhase.PLACING)), restored.owner, order.id)
            finished.building(old.id) shouldBe plan.building.copy(phase = BuildingPhase.ACTIVE)
            finished.roadRouteOverrides shouldBe f.city.roadRouteOverrides
            finished.workers shouldBe f.city.workers
            CivsInfrastructureRebuild.refit(f.city, old, plan.building.copy(bounds = plan.building.bounds.copy(
                max = plan.building.bounds.max.copy(x = plan.building.bounds.max.x + 1)))) shouldBe false
        }
    }

    test("ordinary native capture and restart validation require the current supported survivor and both approaches") {
        MockBukkitTestRuntime.open().use { paper ->
            val native = CivsWorksiteWorldFixture("gate-consolidation").also(paper.server::addWorld)
            for (x in -1..3) for (z in -1..3) native.loadChunk(x, z)
            val f = consolidationFixture()
            repeat(6) { f.complete(requireNotNull(CivsInfrastructureRebuild.next(f.city, f.plans.values.toList(), f.available()))) }
            f.city = f.city.copy(worldId = native.uid)
            f.plans.replaceAll { _, p -> p.copy(worldId = native.uid) }
            val proposal = f.proposal(requireNotNull(CivsInfrastructureRebuild.next(f.city, f.plans.values.toList(), f.available())))
            val old = f.city.building(f.south)!!
            for (x in 17..31) for (z in 16..45) native.getBlockAt(x, 64, z).type = Material.STONE
            f.plans.values.filter { it.building.type.isInfrastructure }.flatMap { it.blocks }.forEach { c ->
                native.getBlockAt(c.position.x, c.position.y, c.position.z).blockData = Bukkit.createBlockData(c.after)
            }
            val scheduler = TestTaskScheduler()
            LifecycleTaskScope(scheduler, true).use { tasks ->
                CivsWorld(paper.createSimplePlugin("GateConsolidationTest"), tasks, { 0L }) { listOf(f.city) }.use { world ->
                    fun cells() = with(proposal.building.bounds) {
                        val states = proposal.blocks.associate { it.position to it.after }
                        (min.y..max.y).flatMap { y -> (min.z..max.z).flatMap { z -> (min.x..max.x).map { x ->
                            val data = states[CivPosition(x, y, z)] ?: "minecraft:barrier"
                            ArcSchematicBlock(ArcSchematicPoint(x, y, z), data, data == "minecraft:air", data == "minecraft:barrier")
                        } } }
                    }
                    fun <T> finish(future: java.util.concurrent.CompletableFuture<T>): T {
                        repeat(200) { if (!future.isDone) scheduler.tick() }
                        return future.join()
                    }
                    val captured = finish(world.captureSite(f.city.id, f.city.owner, native.uid, proposal.building, cells(), old))
                    CivsInfrastructureRebuild.matchesSource(f.plans.getValue(f.south), captured) shouldBe true
                    finish(world.revalidate(captured, f.city.owner))
                    // The fixture has an implicit stone plane at63. Remove it too, otherwise the
                    // permitted one-block descent is a real supported approach on every lane.
                    for (z in 22..24) for (y in 63..64) native.getBlockAt(25, y, z).type = Material.AIR
                    for (z in 22..24) for (y in 63..64) native.getBlockAt(25, y, z).type shouldBe Material.AIR
                    val failure = shouldThrow<java.util.concurrent.CompletionException> { finish(world.revalidate(captured, f.city.owner)) }
                    (failure.cause as CivsRejection).reason shouldBe "gate-refit-exit-not-ready"
                    native.getBlockAt(25, 63, 23).type = Material.STONE // One real one-block descent suffices.
                    finish(world.revalidate(captured, f.city.owner))
                    native.getBlockAt(22, 64, 22).type = Material.AIR // Every core lane still needs support.
                    val coreFailure = shouldThrow<java.util.concurrent.CompletionException> { finish(world.captureSite(
                        f.city.id, f.city.owner, native.uid, proposal.building, cells(), old)) }
                    (coreFailure.cause as CivsRejection).reason shouldBe "gate-refit-exit-not-ready"
                }
            }
        }
    }
})

private class ConsolidationFixture(var city: Settlement, val north: UUID, val south: UUID, val walls: List<UUID>,
    val plans: MutableMap<UUID, CivPlacementPlan>, val surface: CivTerrainSurface) {
    fun available() = city.buildings.mapTo(hashSetOf()) { it.id }
    fun proposal(work: CivsInfrastructureRebuild.Work): CivPlacementPlan {
        val witnesses = CivsInfrastructureRebuild.witnesses(city, plans.values.toList())
            .filter { CivsAutonomousGeometry.horizontalContains(work.building.bounds, it) }
        return CivsInfrastructureRebuild.proposal(city, plans.getValue(work.building.id), witnesses, work.survivor)
    }
    fun complete(work: CivsInfrastructureRebuild.Work): CivPlacementPlan {
        val plan = if (work.infill == null) proposal(work) else {
            val piece = CivsWallInfill.prepare(city, work.infill, surface, CivAutonomousAccess.from(city, plans.values.toList()), emptyList()).first
            val building = CivsInfrastructure.building(piece)
            CivPlacementPlan(piece.id, city.id, city.worldId, building, piece.blocks.map {
                CivPlanBlock(it.position, if (it.position.y == 64) "minecraft:grass_block[snowy=false]" else "minecraft:air", it.after)
            })
        }
        val active = plan.building.copy(phase = BuildingPhase.ACTIVE)
        city = city.copy(buildings = city.buildings.filterNot { it.id == active.id } + active)
        val bytes = CivsInfrastructure.schematic(CivSettlementPiece(active.id, active.type, active.bounds, active.entrance,
            plan.blocks.map { CivSettlementBlock(it.position, it.after) }, active.transit.orEmpty()), 4671)
        return plan.copy(capturedSha256 = CivsGateRefit.sha256(bytes)).also { plans[active.id] = it }
    }
}

private fun consolidationFixture(width: Int = 7, depth: Int = 5): ConsolidationFixture {
    val deep = depth == 9
    val base = settlementPlanCity().copy(style = "medievaloak", stock = Stock(wood = 100000, stone = 100000, food = 100000)).let { city ->
        if (!deep) city else city.copy(buildings = city.buildings.map { it.copy(level = 3) })
    }
    val firstX = 22 - depth / 2
    val lastX = 22 + depth / 2
    val top = if (deep) 73 else 69
    val north = UUID(291, 1); val south = UUID(291, 2)
    fun gate(id: UUID, start: Int, span: Int): CivPlacementPlan {
        val box = CivBox(CivPosition(firstX, 64, start), CivPosition(lastX, top, start + span - 1))
        val posts = setOf(CivPosition(22, 65, start), CivPosition(22, 65, start + span - 1))
        val transit = (firstX..lastX).flatMap { x -> (start until start + span).map { z -> CivPosition(x, 65, z) } }.filter { it !in posts }
        val b = CivBuilding(id, BuildingType.GATE, 1, box.min, 0, "civs/infrastructure/v4/gate/${if (deep) 3 else 1}/$id", box,
            CivPosition(firstX - 1, 65, start + span / 2), BuildingPhase.CONSTRUCTING, transit = transit)
        val blocks = (64..top).flatMap { y -> (start until start + span).flatMap { z -> (firstX..lastX).map { x ->
            val at = CivPosition(x, y, z)
            val state = when {
                y == 64 -> "minecraft:cobblestone"
                y == 65 && CivPosition(x, 65, z) in posts -> "minecraft:stone_bricks"
                y in 66..67 && CivPosition(x, 65, z) in posts -> if (deep) "minecraft:stone_bricks" else "minecraft:oak_log[axis=y]"
                deep && y in 68..71 -> "minecraft:stone_bricks"
                deep && y == 72 && x in setOf(firstX, lastX) && (z - start) % 3 != 1 -> "minecraft:stone_bricks"
                !deep && y == 68 && x in 21..23 -> "minecraft:oak_log[axis=z]"
                else -> "minecraft:air"
            }
            CivPlanBlock(at, "minecraft:air", state)
        } } }
        val route = if (id == north) (30 downTo firstX - 1).map { CivPosition(it, 65, start + span / 2) } else emptyList()
        return CivPlacementPlan(id, base.id, base.worldId, b, blocks, capturedSha256 = "a".repeat(64),
            terrain = route.takeIf { it.isNotEmpty() }?.let { CivTerraformPlan(CivBox(it.last(), it.first()), emptyList(), it, 64, 0, 0) })
    }
    fun wall(id: UUID, first: Int, last: Int): CivPlacementPlan {
        val box = CivBox(CivPosition(if (deep) 20 else 21, 64, first), CivPosition(if (deep) 24 else 23, if (deep) 73 else 68, last))
        val b = CivBuilding(id, BuildingType.WALL, 1, box.min, 0,
            if (deep) "civs/infrastructure/v4/wall/3/$id" else "civs/infrastructure/v1/wall/$id", box,
            CivPosition(box.min.x - 1, 65, first), BuildingPhase.CONSTRUCTING)
        val blocks = (64..box.max.y).flatMap { y -> (first..last).flatMap { z -> (box.min.x..box.max.x).map { x ->
            CivPlanBlock(CivPosition(x, y, z), "minecraft:air", when {
                deep && y == 64 -> "minecraft:cobblestone"
                y <= if (deep) 71 else 67 -> "minecraft:stone_bricks"
                else -> "minecraft:air"
            })
        } } }
        return CivPlacementPlan(id, base.id, base.worldId, b, blocks, capturedSha256 = "a".repeat(64))
    }
    val walls = (3L..6L).map { UUID(291, it) }
    val plans = listOf(gate(north, 20, width), gate(south, 33, 5), wall(walls[0], 16, 19), wall(walls[1], if (deep) 27 else 28, 29),
        wall(walls[2], 30, 32), wall(walls[3], 38, 44))
    val city = base.copy(buildings = base.buildings + plans.map { it.building.copy(phase = BuildingPhase.ACTIVE) })
    val sources = (plans + base.buildings.map { CivPlacementPlan(it.id, city.id, city.worldId,
        it.copy(phase = BuildingPhase.CONSTRUCTING), emptyList(), capturedSha256 = "a".repeat(64)) }).associateBy { it.building.id }.toMutableMap()
    val surface = CivTerrainSurface(-64, 320, (-4..50).flatMap { x -> (-4..50).map { z ->
        civsTerrainKey(x, z) to CivTerrainSurfaceColumn(64, true, true)
    } }.toMap())
    return ConsolidationFixture(city, north, south, walls, sources, surface)
}

/** Called by the existing optional private-input replay class; full source journals, no world writes. */
internal fun replayGateConsolidation(document: JsonObject, output: Path) {
    MockBukkitTestRuntime.open().use {
        val gson = GsonBuilder().setPrettyPrinting().create()
        val sites = document.getAsJsonObject("catalog_sites")?.entrySet()?.associate { it.key to it.value.asJsonObject }
            ?: System.getenv("CIVS_ACTUAL_CATALOG_ROOT")?.let { CivBlueprintCatalog.load(Path.of(it)).worksiteLayouts }.orEmpty()
        CivsWorksiteLayouts.installCatalog(sites)
        try {
            var city = gson.fromJson(document["settlement"], Settlement::class.java)
            require(city.minePlans.isNullOrEmpty() || city.buildings.filter { it.id.toString() in city.minePlans.orEmpty() }
                .all { CivsWorksiteLayouts.site(it)?.has("mine") == true }) {
                "The actual saved city requires its exact catalog_sites (or CIVS_ACTUAL_CATALOG_ROOT); mine state is never discarded"
            }
            CivsRules.validate(city)
            val plans = document.getAsJsonArray("retained_plans").map { gson.fromJson(it, CivPlacementPlan::class.java) }
                .associateBy { it.building.id }.toMutableMap()
            val native = document.getAsJsonArray("native_cells").associate { cell ->
                val c = cell.asJsonObject
                val properties = c.getAsJsonObject("properties").entrySet().sortedBy { it.key }
                    .joinToString(",") { (k, v) -> "$k=${v.asString}" }.takeIf { it.isNotEmpty() }?.let { "[$it]" }.orEmpty()
                CivPosition(c["x"].asInt, c["y"].asInt, c["z"].asInt) to "minecraft:${c["name"].asString}$properties"
            }.toMutableMap()
            val baseline = native.toMap()
            val rows = mutableListOf<Map<String, Any?>>()
            val closed = UUID.fromString(document["retired_gate"].asString)
            repeat(7) {
                val work = requireNotNull(CivsInfrastructureRebuild.next(city, plans.values.toList(), city.buildings.mapTo(hashSetOf()) { it.id }))
                val witnesses = CivsInfrastructureRebuild.witnesses(city, plans.values.toList())
                val proposal = if (work.infill == null) CivsInfrastructureRebuild.proposal(city, plans.getValue(work.building.id),
                    witnesses.filter { CivsAutonomousGeometry.horizontalContains(work.building.bounds, it) }, work.survivor)
                else {
                    val columns = native.entries.groupBy { it.key.x to it.key.z }.map { (column, cells) ->
                        val ground = cells.filter { Material.matchMaterial(it.value.substringBefore('['))!!.isSolid }.maxBy { it.key.y }
                        val material = Material.matchMaterial(ground.value.substringBefore('['))!!
                        civsTerrainKey(column.first, column.second) to CivTerrainSurfaceColumn(ground.key.y,
                            material in setOf(Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.STONE), true,
                            dirtPath = material == Material.DIRT_PATH)
                    }.toMap()
                    val piece = try { CivsWallInfill.prepare(city, work.infill, CivTerrainSurface(-64, 320, columns),
                        CivAutonomousAccess.from(city, plans.values.toList()), emptyList()).first }
                    catch (e: CivsRejection) { throw AssertionError("step=${rows.size} seam=${work.infill}: ${e.reason} ${e.values}", e) }
                    CivPlacementPlan(piece.id, city.id, city.worldId, CivsInfrastructure.building(piece), piece.blocks.map { cell ->
                        CivPlanBlock(cell.position, native.getValue(cell.position), cell.after)
                    })
                }
                rows += mapOf("building" to proposal.building, "source_plan" to plans[work.building.id]?.id,
                    "seam" to work.infill, "blocks" to proposal.blocks, "materials" to CivsMaterials.required(proposal),
                    "retained_witnesses" to witnesses.filter { CivsAutonomousGeometry.horizontalContains(proposal.building.bounds, it) })
                val active = proposal.building.copy(phase = BuildingPhase.ACTIVE)
                city = city.copy(buildings = city.buildings.filterNot { it.id == active.id } + active,
                    roadRouteOverrides = if (work.infill == null) CivsInfrastructureRebuild.finishedRoutes(city, work.building, active)
                        else city.roadRouteOverrides)
                CivsRules.validate(city)
                plans[active.id] = proposal.copy(capturedSha256 = "b".repeat(64))
                proposal.blocks.forEach { cell -> if (cell.position in native) native[cell.position] = cell.after }
            }
            city.building(closed)!!.type shouldBe BuildingType.WALL
            val expected = document.getAsJsonArray("expected_changes").associate { change ->
                val c = change.asJsonObject
                gson.fromJson(c["position"], CivPosition::class.java) to c["after"].asString
            }
            val actual = native.filter { (p, state) -> baseline[p] != state }
            Files.createDirectories(output.parent)
            Files.writeString(output, gson.toJson(mapOf("pieces" to rows, "changed_crop" to actual.entries.map { (p, s) ->
                mapOf("position" to p, "before" to baseline[p], "after" to s) }, "final_city" to city)))
            withClue("Final Kotlin whole-piece proposals must reproduce the reviewed corrected native crop: ") { actual shouldBe expected }
        } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
    }
}
