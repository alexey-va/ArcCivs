package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.sin

class CivsSettlementLayoutTest : FunSpec({
    for ((facing, direction) in listOf("east" to (1 to 0), "west" to (-1 to 0),
        "south" to (0 to 1), "north" to (0 to -1)))
    test("paved $facing rises use dry bottom cobblestone stairs and flat landings remain full blocks") {
        val city = layoutFixtureCity()
        val route = (0..8).map { index -> CivPosition(12 + direction.first * index,
            65 + index / 3, 12 + direction.second * index) }
        val profile = CivsAutonomousTerraform.roadProfile(route)
        val surface = layoutSurface(city, "flat").let { base -> base.copy(columns = base.columns +
            profile.mapValues { (_, p) -> CivTerrainSurfaceColumn(p.y, true) }) }
        val result = CivsSettlementLayout.infrastructure(city, CivsSettlementLayout.frame(city), surface, listOf(route))
        val roads = result.pieces.filter { it.type == BuildingType.ROAD }
        val cells = roads.flatMap { it.blocks }.associate { it.position to it.after }
        for (index in listOf(3, 6)) {
            val surfacePoint = route[index].copy(y = route[index].y - 1)
            cells[surfacePoint] shouldBe "minecraft:cobblestone_stairs[facing=$facing,half=bottom,shape=straight,waterlogged=false]"
            cells[surfacePoint.copy(y = surfacePoint.y + 1)] shouldBe "minecraft:air"
            cells[surfacePoint.copy(y = surfacePoint.y + 2)] shouldBe "minecraft:air"
            roads.flatMap { it.transit }.contains(route[index]) shouldBe true
        }
        for (index in listOf(1, 4, 7))
            cells[route[index].copy(y = route[index].y - 1)] shouldBe "minecraft:cobblestone"
        exportRoadSection("paved-slope-$facing", surface, route, paving = roads.flatMap { it.blocks })
    }

    test("the hall frame has three explicit passages and disjoint perimeter strips in every rotation") {
        for (rotation in listOf(0, 90, 180, 270)) {
            val city = layoutFixtureCity(rotation)
            val frame = CivsSettlementLayout.frame(city)
            frame shouldBe CivsSettlementLayout.frame(city)
            frame.gates.size shouldBe 3
            frame.wallObstacles.forEachIndexed { index, box ->
                frame.wallObstacles.drop(index + 1).none { CivsAutonomousGeometry.overlaps(box, it) } shouldBe true
            }
            frame.gates.forEach { gate ->
                CivsSettlementLayout.acceptsRoute(frame, listOf(gate.center)) shouldBe true
                frame.wallObstacles.none { CivsAutonomousGeometry.overlaps(gate.passage, it) } shouldBe true
            }
            CivsSettlementLayout.acceptsRoute(frame, listOf(frame.wallObstacles.first().min)) shouldBe false
            CivsSettlementLayout.frame(city.copy(radius = 128), frame.halfWidth) shouldBe frame
        }
    }

    test("street frontage reserves final envelopes and keeps all new producers outside the core") {
        val city = layoutFixtureCity()
        val frame = CivsSettlementLayout.frame(city)
        for (type in listOf(BuildingType.HOUSE, BuildingType.TAVERN, BuildingType.GUARD_TOWER,
            BuildingType.FORESTER, BuildingType.QUARRY, BuildingType.FARM)) {
            val current = layoutFixtureBuilding(type)
            val future = current.copy(level = 3, bounds = CivBox(CivPosition(-6, -2, -18), CivPosition(6, 14, -1)))
            val choices = CivsSettlementLayout.candidates(city, current, future)
            check(choices.isNotEmpty()) { "No candidate for $type" }
            choices shouldBe CivsSettlementLayout.candidates(city, current, future)
            for ((building) in choices) {
                (building.bounds.max.x - building.bounds.min.x >= 12) shouldBe true
                city.contains(building.bounds.min.x, building.bounds.min.z) shouldBe true
                city.contains(building.bounds.max.x, building.bounds.max.z) shouldBe true
                frame.wallObstacles.none { CivsAutonomousGeometry.overlaps(building.bounds, it) } shouldBe true
                if (type in setOf(BuildingType.FORESTER, BuildingType.QUARRY, BuildingType.FARM))
                    CivsAutonomousGeometry.overlaps(building.bounds, frame.core) shouldBe false
                else civsContainsBox(frame.core.copy(min = frame.core.min.copy(y = -4095), max = frame.core.max.copy(y = 4095)), building.bounds) shouldBe true
            }
        }
    }

    test("an unstarted legacy ring fits around pinned buildings and a persisted ring never shifts") {
        val city = layoutFixtureCity()
        val house = layoutFixtureBuilding(BuildingType.HOUSE, CivPosition(-56, 64, 25))
        val legacy = city.copy(buildings = city.buildings + house, autonomy = CivAutonomy())
        val selected = CivsSettlementLayout.frame(legacy)
        (selected.halfWidth < 56) shouldBe true
        (selected.wallObstacles + selected.gates.map { it.passage }).none {
            CivsAutonomousGeometry.overlaps(it, house.bounds)
        } shouldBe true
        legacy.building(house.id) shouldBe house
        val pinned = legacy.copy(autonomy = CivAutonomy(layoutHalfWidth = selected.halfWidth))
        CivsSettlementLayout.frame(pinned.copy(radius = 128, buildings = pinned.buildings +
            layoutFixtureBuilding(BuildingType.TAVERN, selected.wallObstacles.first().min))) shouldBe selected
    }

    test("real infrastructure owns nonoverlapping boxes and explicit open gate thresholds") {
        val city = layoutFixtureCity()
        val frame = CivsSettlementLayout.frame(city)
        val surface = layoutSurface(city, "flat")
        val routes = frame.gates.map { gate ->
            val start = city.buildings.first().entrance
            layoutAxisRoute(start, CivPosition(start.x, start.y, gate.center.z)) +
                layoutAxisRoute(CivPosition(start.x, start.y, gate.center.z), gate.center.copy(y = start.y)).drop(1)
        }
        val plan = CivsSettlementLayout.infrastructure(city, frame, surface, routes)
        plan.rejected shouldBe emptyMap()
        plan.perimeterClosed shouldBe true
        plan shouldBe CivsSettlementLayout.infrastructure(city, frame, surface, routes)
        (plan.pieces.size <= CivsSettlementLayout.MAXIMUM_PIECES) shouldBe true
        plan.pieces.forEachIndexed { index, piece ->
            piece.blocks.map { it.position }.distinct().size shouldBe piece.blocks.size
            plan.pieces.drop(index + 1).none { CivsAutonomousGeometry.overlaps(piece.bounds, it.bounds) } shouldBe true
            CivsAutonomousGeometry.horizontalContains(piece.bounds, piece.entrance) shouldBe false
            val approach = requireNotNull(surface.at(piece.entrance.x, piece.entrance.z))
            approach.loaded shouldBe true
            approach.natural shouldBe true
            piece.entrance.y shouldBe approach.groundY + 1
            if (piece.type == BuildingType.WALL) piece.transit shouldBe emptyList()
            else {
                piece.transit.map { civsTerrainKey(it.x, it.z) }.distinct().size shouldBe piece.transit.size
                piece.transit.size shouldBe (piece.bounds.max.x - piece.bounds.min.x + 1) * (piece.bounds.max.z - piece.bounds.min.z + 1)
                (piece.transit.size <= 256) shouldBe true
            }
            if (piece.type == BuildingType.GATE) {
                val cells = piece.blocks.associate { it.position to it.after }
                for (feet in piece.transit) for (dy in 0..3)
                    cells[feet.copy(y = feet.y + dy)] shouldBe "minecraft:air"
            }
        }
        val wet = frame.wallObstacles.first().min
        val denied = surface.copy(columns = surface.columns + (civsTerrainKey(wet.x, wet.z) to CivTerrainSurfaceColumn(64, false)))
        val rejection = CivsSettlementLayout.infrastructure(city, frame, denied, routes)
        rejection.perimeterClosed shouldBe false
        (rejection.rejected.getValue("protected-terrain") > 0) shouldBe true
        rejection.pieces.flatMap { it.blocks }.none { it.position.x == wet.x && it.position.z == wet.z } shouldBe true
    }

    test("non-paving retained approaches still protect wall crossings and gate grades") {
        val city = layoutFixtureCity()
        val frame = CivsSettlementLayout.frame(city)
        val surface = layoutSurface(city, "flat")
        val gate = frame.gates.first()
        val gateRoute = layoutAxisRoute(gate.center.copy(x = gate.center.x - 6, y = 66),
            gate.center.copy(x = gate.center.x + 6, y = 66))
        val wall = frame.wallObstacles.first()
        val crossing = CivPosition((wall.min.x + wall.max.x) / 2, 65, (wall.min.z + wall.max.z) / 2)
        val retained = layoutAxisRoute(crossing.copy(z = wall.min.z - 2), crossing.copy(z = wall.max.z + 2))
        val result = CivsSettlementLayout.infrastructure(city, frame, surface, listOf(gateRoute, retained), pavingRoutes = emptyList())
        result.pieces.none { it.type == BuildingType.ROAD } shouldBe true
        (result.rejected.getValue("route-crosses-wall") > 0) shouldBe true
        result.perimeterClosed shouldBe false
        result.pieces.filter { it.type == BuildingType.WALL }.none {
            CivsAutonomousGeometry.horizontalContains(it.bounds, crossing)
        } shouldBe true
        val passage = result.pieces.single { it.type == BuildingType.GATE &&
            it.bounds.min.x == gate.passage.min.x && it.bounds.min.z == gate.passage.min.z }
        passage.transit.single { it.x == gate.center.x && it.z == gate.center.z }.y shouldBe 66
    }

    test("gate floors retain route grades and steep infrastructure has an explicit rejection") {
        val city = layoutFixtureCity()
        val frame = CivsSettlementLayout.frame(city)
        val surface = layoutSurface(city, "flat")
        val gate = frame.gates.first()
        val route = layoutAxisRoute(gate.center.copy(x = gate.center.x - 6, y = 65), gate.center.copy(x = gate.center.x + 6, y = 65))
        val uneven = surface.copy(columns = surface.columns + (civsTerrainKey(gate.center.x, gate.center.z) to CivTerrainSurfaceColumn(63, true)))
        val result = CivsSettlementLayout.infrastructure(city, frame, uneven, listOf(route))
        result.pieces.single { it.type == BuildingType.GATE && it.bounds.min.x == gate.passage.min.x && it.bounds.min.z == gate.passage.min.z }
            .transit.forEach { it.y shouldBe 65 }
        val cliff = surface.copy(columns = surface.columns.mapValues { (column, value) ->
            if ((column shr 32).toInt() >= 0) value.copy(groundY = 82) else value
        })
        val rejected = CivsSettlementLayout.infrastructure(city, frame, cliff, emptyList())
        rejected.perimeterClosed shouldBe false
        (rejected.rejected.getValue("infrastructure-slope") > 0) shouldBe true
        rejected.pieces.forEach { piece ->
            val floors = piece.blocks.groupBy { civsTerrainKey(it.position.x, it.position.z) }.mapValues { (_, cells) -> cells.minOf { it.position.y } }
            for ((column, y) in floors) for ((dx, dz) in listOf(1 to 0, 0 to 1)) {
                val neighbour = civsTerrainKey((column shr 32).toInt() + dx, column.toInt() + dz)
                floors[neighbour]?.let { (kotlin.math.abs(it - y) <= 1) shouldBe true }
            }
        }
    }

    test("road shoulders retain a later raised route centre and pass the actual terraform draft") {
        val city = layoutFixtureCity()
        val frame = CivsSettlementLayout.frame(city)
        val route = layoutAxisRoute(CivPosition(0, 65, 0), CivPosition(0, 65, 4)) +
            listOf(CivPosition(1, 66, 4), CivPosition(2, 66, 4))
        val access = CivAutonomousAccess(mapOf(city.buildings.first().id to route.reversed()))
        val surface = layoutSurface(city, "flat").let { base -> base.copy(columns = base.columns +
            route.associate { civsTerrainKey(it.x, it.z) to CivTerrainSurfaceColumn(it.y - 1, true) }) }
        val plan = CivsSettlementLayout.infrastructure(city, frame, surface, listOf(route))
        val piece = plan.pieces.single { it.type == BuildingType.ROAD && CivPosition(1, 66, 4) in it.transit }
        val floors = piece.blocks.groupBy { civsTerrainKey(it.position.x, it.position.z) }
            .mapValues { (_, cells) -> cells.minOf { it.position.y } }
        floors[civsTerrainKey(1, 4)] shouldBe 65
        val draft = CivsAutonomousTerraform.draft(city,
            CivTerraformOption(CivsInfrastructure.building(piece), 0), surface, access,
            frame.wallObstacles, floors)
        draft.columns.single { it.x == 1 && it.z == 4 }.groundY shouldBe 65
        draft.columns.single { it.x == 1 && it.z == 4 }.preserve shouldBe true
    }

    test("completed infrastructure replays from the captured journal after its surface becomes artificial") {
        val city = layoutFixtureCity()
        val frame = CivsSettlementLayout.frame(city)
        val surface = layoutSurface(city, "flat")
        val start = city.buildings.first().entrance
        val routes = frame.gates.map { gate ->
            val corner = CivPosition(start.x, start.y, gate.center.z)
            layoutAxisRoute(start, corner) + layoutAxisRoute(corner, gate.center.copy(y = start.y)).drop(1)
        }
        val first = CivsSettlementLayout.infrastructure(city, frame, surface, routes)
        val buildings = first.pieces.map { piece ->
            CivBuilding(piece.id, piece.type, 1, piece.bounds.min, 0, "fixture/infrastructure/${piece.id}",
                piece.bounds, piece.entrance, BuildingPhase.ACTIVE, transit = piece.transit.takeIf { it.isNotEmpty() })
        }
        val completed = city.copy(buildings = city.buildings + buildings)
        val plans = first.pieces.map { piece ->
            CivPlacementPlan(layoutId("plan-${piece.id}"), city.id, city.worldId,
                requireNotNull(completed.building(piece.id)), piece.blocks.map { CivPlanBlock(it.position, "minecraft:air", it.after) })
        }
        val ownColumns = first.pieces.flatMap { it.blocks }.groupBy { civsTerrainKey(it.position.x, it.position.z) }
        val mutated = surface.copy(columns = surface.columns + ownColumns.mapValues { (_, cells) ->
            CivTerrainSurfaceColumn(cells.filter { it.after != "minecraft:air" }.maxOf { it.position.y }, false)
        })
        val accepted = CivsSettlementLayout.accepted(completed, plans)
        accepted shouldBe first.pieces
        val replay = CivsSettlementLayout.infrastructure(completed, frame, mutated, routes, accepted)
        replay shouldBe first
        replay.perimeterClosed shouldBe true

        val extension = layoutAxisRoute(CivPosition(20, start.y, frame.gates.first().center.z), CivPosition(20, start.y, 36))
        val extended = CivsSettlementLayout.infrastructure(completed, frame, mutated, routes + listOf(extension), accepted)
        first.pieces.forEach { piece -> extended.pieces.single { it.id == piece.id } shouldBe piece }
        extended.pieces.map { it.id }.distinct().size shouldBe extended.pieces.size
        extended.pieces.forEachIndexed { index, piece ->
            extended.pieces.drop(index + 1).none { CivsAutonomousGeometry.overlaps(piece.bounds, it.bounds) } shouldBe true
        }

        val raised = surface.copy(columns = surface.columns.mapValues { (_, column) -> column.copy(groundY = column.groundY + 2) })
        val raisedRoutes = routes.map { route -> route.map { it.copy(y = it.y + 2) } }
        CivsSettlementLayout.infrastructure(city, frame, raised, raisedRoutes).pieces.map { it.id } shouldBe first.pieces.map { it.id }
    }

    test("export production planner and real family headers on flat hill cliff water and existing-town fixtures") {
        val source = System.getenv("CIVS_LAYOUT_FRAMES") ?: return@test
        val output = Path.of(System.getenv("CIVS_LAYOUT_OUTPUT") ?: "build/settlement-layout-preview")
        Files.createDirectories(output)
        val frames = JsonParser.parseString(Files.readString(Path.of(source))).asJsonObject.getAsJsonArray("families")
        fun building(style: String, type: BuildingType, level: Int, id: UUID,
            anchor: CivPosition = CivPosition(0, 0, 0), rotation: Int = 0): CivBuilding {
            val row = frames.map { it.asJsonObject }.single {
                it["style"].asString == style && it["type"].asString == type.name && it["level"].asInt == level
            }
            fun vector(name: String): CivPosition = row.getAsJsonArray(name).let { CivPosition(it[0].asInt, it[1].asInt, it[2].asInt) }
            fun point(p: CivPosition) = layoutRotate(p, rotation / 90).let { CivPosition(anchor.x + it.x, anchor.y + it.y, anchor.z + it.z) }
            val a = point(vector("min")); val b = point(vector("max"))
            return CivBuilding(id, type, level, anchor, rotation, row["schematicId"].asString,
                CivBox(CivPosition(minOf(a.x, b.x), minOf(a.y, b.y), minOf(a.z, b.z)),
                    CivPosition(maxOf(a.x, b.x), maxOf(a.y, b.y), maxOf(a.z, b.z))),
                point(CivPosition(0, 1, 0)), BuildingPhase.ACTIVE, point(vector("control")))
        }
        val summaries = mutableListOf<Map<String, Any>>()
        for ((style, terrain) in listOf("medievaloak" to "flat", "medievaloak" to "hill", "medievaloak" to "cliff",
            "medievaloak" to "water", "medievaloak" to "existing_auto", "nordic" to "flat", "caledonia" to "flat")) {
            val hall = building(style, BuildingType.TOWN_HALL, 1, layoutId("$style-$terrain-hall"), CivPosition(0, 64, 0))
            var city = Settlement(layoutId("$style-$terrain"), layoutId("owner"), "Layout $terrain", layoutId("world"),
                requireNotNull(hall.control), 96, style, Stock(100_000, 100_000, 100_000), listOf(hall), emptyList(), autonomy = CivAutonomy())
            var surface = layoutSurface(city, terrain)
            var access = CivAutonomousAccess()
            val drafts = mutableListOf<CivTerraformDraft>()
            val failures = mutableListOf<Map<String, Any>>()
            val pinned = mutableListOf<CivBuilding>()
            val base = listOf(BuildingType.BUILDER_HUT, BuildingType.FORESTER, BuildingType.QUARRY,
                BuildingType.FARM, BuildingType.WAREHOUSE, BuildingType.HOUSE)
            if (terrain == "existing_auto") {
                for ((index, type) in base.withIndex()) {
                    val id = layoutId("legacy-$type")
                    val current = building(style, type, 1, id)
                    val future = building(style, type, 3, id)
                    val choices = CivsAutonomousGeometry.candidates(city, current, future, access)
                    val options = CivsAutonomousTerraform.options(city, choices, surface)
                    val draft = options.asSequence().mapNotNull { runCatching { CivsAutonomousTerraform.draft(city, it, surface, access) }.getOrNull() }.firstOrNull()
                        ?: error("Legacy fixture could not place $type at $index")
                    val placed = building(style, type, 1, id, draft.building.anchor, draft.building.rotation)
                    city = city.copy(buildings = city.buildings + placed)
                    pinned += placed
                    access = CivAutonomousAccess(access.routes + (id to draft.route))
                    surface = layoutGraded(surface, draft)
                    drafts += draft
                }
            }
            val frame = CivsSettlementLayout.frame(city)
            val goals = (if (terrain == "existing_auto") emptyList() else base) +
                listOf(BuildingType.TAVERN, BuildingType.GUARD_TOWER, BuildingType.HOUSE, BuildingType.HOUSE)
            for ((index, type) in goals.withIndex()) {
                val id = layoutId("$style-$terrain-$type-$index")
                val current = building(style, type, 1, id)
                val future = building(style, type, 3, id)
                val choices = CivsSettlementLayout.candidates(city, current, future, access, frame)
                val reasons = mutableMapOf<String, Int>()
                var selected: CivTerraformDraft? = null
                for (option in CivsAutonomousTerraform.options(city, choices, surface).take(64)) {
                    val attempt = runCatching { CivsAutonomousTerraform.draft(city, option, surface, access, frame.wallObstacles) }
                    val draft = attempt.getOrNull()
                    if (draft != null && CivsSettlementLayout.acceptsRoute(frame, draft.route)) { selected = draft; break }
                    val reason = (attempt.exceptionOrNull() as? CivsRejection)?.reason ?: "route-crosses-wall"
                    reasons.merge(reason, 1, Int::plus)
                }
                val draft = selected
                if (draft == null) {
                    failures += mapOf("type" to type.name, "candidates" to choices.size, "reasons" to reasons)
                    continue
                }
                val placed = building(style, type, 1, id, draft.building.anchor, draft.building.rotation)
                city = city.copy(buildings = city.buildings + placed)
                access = CivAutonomousAccess(access.routes + (id to draft.route))
                surface = layoutGraded(surface, draft)
                drafts += draft
            }
            pinned.forEach { city.building(it.id) shouldBe it }
            val infrastructure = CivsSettlementLayout.infrastructure(city, frame, surface, access.routes.values.toList())
            val summary = mapOf("style" to style, "terrain" to terrain, "buildings" to city.buildings.size,
                "pieces" to infrastructure.pieces.size, "closed" to infrastructure.perimeterClosed,
                "failures" to failures, "rejections" to infrastructure.rejected, "blockers" to infrastructure.blockers)
            summaries += summary
            Files.writeString(output.resolve("$style-$terrain.json"), Gson().toJson(mapOf(
                "plannerVersion" to CivsSettlementLayout.VERSION, "summary" to summary, "city" to city,
                "frame" to frame, "terrain" to surface.columns.map { (key, value) ->
                    mapOf("x" to (key shr 32).toInt(), "z" to key.toInt(), "y" to value.groundY, "natural" to value.natural) },
                "drafts" to drafts, "infrastructure" to infrastructure, "pinned" to pinned)))
        }
        Files.writeString(output.resolve("summary.json"), Gson().toJson(summaries))
        summaries.filter { it["terrain"] == "flat" }.forEach { summary ->
            check((summary["buildings"] as Int) >= 10) { summary.toString() }
        }
        summaries.filter { it["terrain"] in setOf("flat", "hill", "water") }.forEach { summary ->
            check(summary["closed"] == true && (summary["pieces"] as Int) <= CivsSettlementLayout.MAXIMUM_PIECES) { summary.toString() }
        }
    }
})

private fun layoutId(value: String) = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8))
private fun layoutRotate(p: CivPosition, turns: Int) = when (Math.floorMod(turns, 4)) {
    0 -> p; 1 -> CivPosition(-p.z, p.y, p.x); 2 -> CivPosition(-p.x, p.y, -p.z); else -> CivPosition(p.z, p.y, -p.x)
}
private fun layoutFixtureBuilding(type: BuildingType, anchor: CivPosition = CivPosition(0, 0, 0), rotation: Int = 0): CivBuilding {
    val a = layoutRotate(CivPosition(-5, 0, -12), rotation / 90)
    val b = layoutRotate(CivPosition(5, 10, -1), rotation / 90)
    return CivBuilding(layoutId(type.name), type, 1, anchor, rotation, "fixture/${type.name}",
        CivBox(CivPosition(anchor.x + minOf(a.x, b.x), anchor.y, anchor.z + minOf(a.z, b.z)),
            CivPosition(anchor.x + maxOf(a.x, b.x), anchor.y + 10, anchor.z + maxOf(a.z, b.z))),
        anchor.copy(y = anchor.y + 1), BuildingPhase.ACTIVE)
}
private fun layoutFixtureCity(rotation: Int = 0): Settlement {
    val hall = layoutFixtureBuilding(BuildingType.TOWN_HALL, CivPosition(0, 64, 0), rotation)
    return Settlement(layoutId("city"), layoutId("owner"), "Fixture", layoutId("world"),
        CivPosition(0, 64, 0), 96, "fixture", Stock(), listOf(hall), emptyList())
}
private fun layoutSurface(city: Settlement, kind: String): CivTerrainSurface = CivTerrainSurface(-64, 320, buildMap {
    for (x in city.center.x - city.radius..city.center.x + city.radius)
        for (z in city.center.z - city.radius..city.center.z + city.radius) {
            val dx = x - city.center.x; val dz = z - city.center.z
            val y = when (kind) {
                "hill" -> 64 + (5 * sin(dx / 25.0) + 4 * sin(dz / 21.0)).roundToInt()
                "cliff" -> if (dx >= 22) 82 else 64
                else -> 64
            }
            put(civsTerrainKey(x, z), CivTerrainSurfaceColumn(y,
                kind != "water" || dx !in -82..-62 || dz !in -48..48))
        }
})
private fun layoutGraded(surface: CivTerrainSurface, draft: CivTerraformDraft): CivTerrainSurface =
    surface.copy(columns = surface.columns + draft.columns.associate { c ->
        civsTerrainKey(c.x, c.z) to CivTerrainSurfaceColumn(c.groundY, true)
    })
private fun layoutAxisRoute(from: CivPosition, to: CivPosition): List<CivPosition> {
    require(from.x == to.x || from.z == to.z)
    val dx = (to.x - from.x).compareTo(0); val dz = (to.z - from.z).compareTo(0)
    return (0..(kotlin.math.abs(to.x - from.x) + kotlin.math.abs(to.z - from.z))).map {
        CivPosition(from.x + dx * it, from.y, from.z + dz * it)
    }
}
