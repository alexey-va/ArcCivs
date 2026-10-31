package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.sin

class CivsSettlementLayoutTest : FunSpec({
    test("new footways pave only their centreline while an accepted wide road remains exact") {
        val baseCity = layoutFixtureCity()
        val old = CivSettlementPiece(layoutId("paid-wide-road"), BuildingType.ROAD,
            CivBox(CivPosition(10, 64, 10), CivPosition(12, 66, 14)), CivPosition(11, 65, 9),
            buildList {
                for (x in 10..12) for (z in 10..14) for (y in 64..66) add(CivSettlementBlock(CivPosition(x, y, z),
                    if (y == 64) "minecraft:cobblestone" else "minecraft:air"))
            }, (10..12).flatMap { x -> (10..14).map { z -> CivPosition(x, 65, z) } })
        val city = baseCity.copy(buildings = baseCity.buildings + CivsInfrastructure.building(old).copy(phase = BuildingPhase.ACTIVE))
        val route = (11..20).map { x -> CivPosition(x, 65 + (x - 11) / 3, 12) }
        val surface = layoutSurface(city, "flat").let { base -> base.copy(columns = base.columns +
            route.associate { civsTerrainKey(it.x, it.z) to CivTerrainSurfaceColumn(it.y - 1, true) } +
            old.transit.associate { civsTerrainKey(it.x, it.z) to CivTerrainSurfaceColumn(it.y - 1, false) }) }
        val plan = CivsSettlementLayout.infrastructure(city, CivsSettlementLayout.frame(city), surface, listOf(route), listOf(old))
        plan.rejected shouldBe emptyMap()
        plan.pieces.single { it.id == old.id } shouldBe old
        val fresh = plan.pieces.filter { it.type == BuildingType.ROAD && it.id != old.id }
        fresh.flatMap { it.blocks }.map { civsTerrainKey(it.position.x, it.position.z) }.toSet() shouldBe
            route.filter { it.x > 12 }.mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }
        fresh.flatMap { it.transit }.toSet() shouldBe route.filter { it.x > 12 }.toSet()
        route.zipWithNext().all { (a, b) -> kotlin.math.abs(a.y - b.y) <= 1 } shouldBe true
    }

    for ((facing, direction) in listOf("east" to (1 to 0), "west" to (-1 to 0),
        "south" to (0 to 1), "north" to (0 to -1)))
    test("paved $facing rises use style wood stairs and flat landings become dirt path") {
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
            cells[surfacePoint] shouldBe "minecraft:${CivsSettlementLayout.wood(city.style)}_stairs[facing=$facing,half=bottom,shape=straight,waterlogged=false]"
            cells[surfacePoint.copy(y = surfacePoint.y + 1)] shouldBe "minecraft:air"
            cells[surfacePoint.copy(y = surfacePoint.y + 2)] shouldBe "minecraft:air"
            roads.flatMap { it.transit }.contains(route[index]) shouldBe true
        }
        for (index in listOf(1, 4, 7))
            cells[route[index].copy(y = route[index].y - 1)] shouldBe "minecraft:dirt_path"
        exportRoadSection("paved-slope-$facing", surface, route, paving = roads.flatMap { it.blocks })
    }

    test("new road construction separates a rising right angle with a full wood intermediate block") {
        val city = layoutFixtureCity()
        val route = listOf(CivPosition(12, 65, 12), CivPosition(12, 66, 13), CivPosition(12, 66, 14),
            CivPosition(13, 67, 14), CivPosition(14, 67, 14))
        val profile = CivsAutonomousTerraform.roadProfile(route)
        val surface = layoutSurface(city, "flat").let { base -> base.copy(columns = base.columns +
            profile.mapValues { (_, p) -> CivTerrainSurfaceColumn(p.y, true) }) }
        val roads = CivsSettlementLayout.infrastructure(city, CivsSettlementLayout.frame(city), surface, listOf(route))
            .pieces.filter { it.type == BuildingType.ROAD }.flatMap { it.blocks }.associate { it.position to it.after }
        roads[CivPosition(12, 65, 14)] shouldBe "minecraft:${CivsSettlementLayout.wood(city.style)}_planks"
        roads[CivPosition(12, 65, 13)] shouldBe
            "minecraft:${CivsSettlementLayout.wood(city.style)}_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]"
        roads[CivPosition(13, 66, 14)] shouldBe
            "minecraft:${CivsSettlementLayout.wood(city.style)}_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"
        roads[CivPosition(14, 66, 14)] shouldBe "minecraft:dirt_path"
        MockBukkitTestRuntime.open().use { paper ->
            val world = CivsWorksiteWorldFixture("new-road-landing", collisionForData = { data ->
                if (CivsRoadMaintenance.surface(data.asString)) roadUpkeepBoxes(data).map { it.at(CivPosition(0, 0, 0)) } else null
            }).also(paper.server::addWorld)
            world.loadChunk(0, 0)
            roads.forEach { (p, data) -> world.getBlockAt(p.x, p.y, p.z).setBlockData(Bukkit.createBlockData(data), false) }
            (roadHalfStepWalk(world, route) != null) shouldBe true
            (roadHalfStepWalk(world, route.reversed()) != null) shouldBe true
        }
    }

    test("the new hall frame has four explicit passages and disjoint perimeter strips in every rotation") {
        for (rotation in listOf(0, 90, 180, 270)) {
            val city = layoutFixtureCity(rotation)
            val frame = CivsSettlementLayout.frame(city)
            frame shouldBe CivsSettlementLayout.frame(city)
            frame.gates.size shouldBe 4
            frame.gates.map { it.side }.toSet() shouldBe setOf(0, 1, 2, 3)
            val northernStem = frame.streets.last()
            layoutAxisRoute(northernStem.from, northernStem.to).none {
                city.buildings.first().bounds.contains(it.x, it.y, it.z)
            } shouldBe true
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
        val city = layoutFixtureCity().copy(radius = 128)
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

    test("mixed civic plots face the retained street with short links and full upgrade clearance") {
        var city = layoutFixtureCity().copy(autonomy = CivAutonomy())
        val source = layoutFixtureBuilding(BuildingType.WAREHOUSE, CivPosition(40, 64, 24), 90)
        city = city.copy(buildings = city.buildings + source)
        val street = (0..24).map { CivPosition(0, 65, it) } + (1..40).map { CivPosition(it, 65, 24) }
        var access = CivAutonomousAccess(mapOf(source.id to street))
        val frame = CivsSettlementLayout.frame(city)
        val surface = layoutSurface(city, "flat")
        val accepted = city.buildings.toList()
        val selected = mutableListOf<CivBuilding>()
        val links = mutableListOf<List<CivPosition>>()
        for (type in listOf(BuildingType.HOUSE, BuildingType.TAVERN, BuildingType.SMELTERY)) {
            val current = layoutFixtureBuilding(type).copy(id = layoutId("frontage-$type"))
            val future = current.copy(level = type.maxLevel, bounds = CivBox(CivPosition(-6, -2, -14), CivPosition(6, 14, -1)))
            val choices = CivsSettlementLayout.candidates(city, current, future, access, frame)
            check(choices.any { it.streetFrontage }) { "No real street frontage for $type" }
            val options = CivsAutonomousTerraform.options(city, choices, surface)
            options.first().streetFrontage shouldBe true
            val preferred = access.routes.values.flatten().mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }
            val draft = options.asSequence().filter { it.streetFrontage }.mapNotNull {
                runCatching { CivsAutonomousTerraform.draft(city, it, surface, access, frame.wallObstacles, preferredRoads = preferred) }.getOrNull()
            }.firstOrNull() ?: error("No short facing route for $type")
            val b = draft.building
            val (dx, dz) = when (b.rotation) { 90 -> -1 to 0; 180 -> 0 to -1; 270 -> 1 to 0; else -> 0 to 1 }
            val join = draft.route.indexOfLast { civsTerrainKey(it.x, it.z) in preferred }
            val link = draft.route.drop(join)
            (link.size in 3..7) shouldBe true
            link.first().x shouldBe b.entrance.x + dx * (link.size - 1)
            link.first().z shouldBe b.entrance.z + dz * (link.size - 1)
            link.last() shouldBe b.entrance
            draft.route.first() shouldBe city.buildings.first().entrance
            selected.none { CivsAutonomousGeometry.overlaps(it.bounds, b.bounds, 2) } shouldBe true
            CivsAutonomousGeometry.overlaps(b.bounds, frame.core) shouldBe true
            (b.bounds.max.x - b.bounds.min.x >= 12 && b.bounds.max.z - b.bounds.min.z >= 12) shouldBe true
            city = city.copy(buildings = city.buildings + b.copy(phase = BuildingPhase.ACTIVE))
            access = access.copy(routes = access.routes + (b.id to draft.route))
            selected += b
            links += link
        }
        accepted.forEach { city.building(it.id) shouldBe it }
        access.routes.getValue(source.id) shouldBe street
        for (type in CivsSettlementLayout.exteriorTypes) {
            val template = layoutFixtureBuilding(type)
            val choices = CivsSettlementLayout.candidates(city, template, template.copy(level = 3), access, frame)
            check(choices.isNotEmpty())
            choices.all { !it.streetFrontage && !CivsAutonomousGeometry.overlaps(it.building.bounds, frame.core) } shouldBe true
        }
        System.getenv("CIVS_LAYOUT_PREVIEW_OUT")?.let { directory ->
            val output = Path.of(directory).also { Files.createDirectories(it) }
            Files.writeString(output.resolve("mixed-frontage.json"), Gson().toJson(mapOf(
                "kind" to "source-backed-offline-planning-fixture", "frame" to frame, "before" to accepted,
                "buildings" to city.buildings, "retainedRoute" to street, "routes" to access.routes.values, "shortLinks" to links)))
        }
    }

    test("paid road frontage without an old approach journal proves its shared connection before the short link") {
        val street = (-32..32).map { CivPosition(it, 65, 24) }
        val piece = CivSettlementPiece(layoutId("legacy-road-only"), BuildingType.ROAD,
            CivBox(CivPosition(-32, 64, 24), CivPosition(32, 66, 24)), street.first(),
            street.map { CivSettlementBlock(it.copy(y = 64), "minecraft:dirt_path") }, street)
        val city = layoutFixtureCity().let { it.copy(buildings = it.buildings +
            CivsInfrastructure.building(piece).copy(phase = BuildingPhase.ACTIVE)) }
        val access = CivAutonomousAccess(infrastructure = listOf(piece))
        val frame = CivsSettlementLayout.frame(city)
        val building = layoutFixtureBuilding(BuildingType.HOUSE, CivPosition(16, 64, 20))
        val surface = layoutSurface(city, "flat").let { it.copy(columns = it.columns +
            street.associate { point -> civsTerrainKey(point.x, point.z) to CivTerrainSurfaceColumn(64, false) }) }
        CivsSettlementLayout.candidates(city, building, building, access, frame).any { it.streetFrontage } shouldBe true
        val searches = mutableListOf<CivRouteSearchStats>()
        val option = CivTerraformOption(building, 0, streetFrontage = true)
        val draft = CivsAutonomousTerraform.draft(city, option, surface, access, frame.wallObstacles,
            inspectRoute = searches::add)
        val join = CivPosition(16, 65, 24)
        draft.route.first() shouldBe city.buildings.first().entrance
        draft.route.takeLast(5) shouldBe (24 downTo 20).map { CivPosition(16, 65, it) }
        searches.map { it.to } shouldBe listOf(join, building.entrance)
        searches.last().from shouldBe join
        (draft.route.size <= CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH) shouldBe true
        draft.columns.filter { civsTerrainKey(it.x, it.z) in street.map { p -> civsTerrainKey(p.x, p.z) } }
            .all { it.preserve && it.groundY == 64 } shouldBe true
        access.infrastructure.single() shouldBe piece
        access.routes shouldBe emptyMap()

        val start = city.buildings.first().entrance
        val blocked = surface.copy(columns = surface.columns + listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).associate { (dx, dz) ->
            civsTerrainKey(start.x + dx, start.z + dz) to CivTerrainSurfaceColumn(64, true, loaded = false)
        })
        searches.clear()
        (runCatching { CivsAutonomousTerraform.draft(city, option, blocked, access, frame.wallObstacles,
            inspectRoute = searches::add) }.exceptionOrNull() as? CivsRejection)?.reason shouldBe "autonomy-no-path"
        searches.size shouldBe 2
        searches.all { it.from == start && it.expanded == 1 && it.outcome == "route-exhausted" } shouldBe true
    }

    test("an unstarted legacy ring fits around pinned buildings and a persisted ring never shifts") {
        val city = layoutFixtureCity()
        val house = layoutFixtureBuilding(BuildingType.HOUSE, CivPosition(-CivsSettlementLayout.MAXIMUM_HALF_WIDTH, 64, 25))
        val legacy = city.copy(buildings = city.buildings + house, autonomy = CivAutonomy())
        val selected = CivsSettlementLayout.frame(legacy)
        (selected.halfWidth < CivsSettlementLayout.MAXIMUM_HALF_WIDTH) shouldBe true
        (selected.wallObstacles + selected.gates.map { it.passage }).none {
            CivsAutonomousGeometry.overlaps(it, house.bounds)
        } shouldBe true
        legacy.building(house.id) shouldBe house
        val pinned = legacy.copy(autonomy = CivAutonomy(layoutHalfWidth = selected.halfWidth))
        CivsSettlementLayout.frame(pinned.copy(radius = 128, buildings = pinned.buildings +
            layoutFixtureBuilding(BuildingType.TAVERN, selected.wallObstacles.first().min))) shouldBe selected
    }

    test("legacy infrastructure without a width journal retains its old frame while new explicit frames remain larger") {
        val base = layoutFixtureCity()
        val point = base.center.copy(z = base.center.z + 20)
        val road = CivBuilding(layoutId("pre-frame-journal-road"), BuildingType.ROAD, 1, point, 0, "civs/generated/road",
            CivBox(point, point), point, BuildingPhase.ACTIVE, transit = listOf(point))
        val legacy = base.copy(buildings = base.buildings + road, autonomy = CivAutonomy())
        CivsSettlementLayout.frame(legacy).halfWidth shouldBe 56
        CivsSettlementLayout.frame(legacy).gates.size shouldBe 3
        val current = legacy.copy(autonomy = CivAutonomy(layoutHalfWidth = CivsSettlementLayout.MAXIMUM_HALF_WIDTH))
        CivsSettlementLayout.frame(current).halfWidth shouldBe CivsSettlementLayout.MAXIMUM_HALF_WIDTH
        current.building(road.id) shouldBe road
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
                val source = frame.gates.single { it.passage.min.x == piece.bounds.min.x && it.passage.min.z == piece.bounds.min.z }
                val horizontal = Math.floorMod(source.side + frame.rotation / 90, 2) == 0
                val facing = if (horizontal) "north" else "east"
                val openGate = "minecraft:${CivsSettlementLayout.wood(city.style)}_fence_gate[facing=$facing,in_wall=false,open=true,powered=false]"
                for (feet in piece.transit) for (dy in 0..3) {
                    val leaf = if (horizontal) feet.z == source.center.z else feet.x == source.center.x
                    cells[feet.copy(y = feet.y + dy)] shouldBe if (dy == 0 && leaf) openGate else "minecraft:air"
                }
                val expectedLeaves = piece.transit.count { if (horizontal) it.z == source.center.z else it.x == source.center.x }
                val captured = CivPlacementPlan(piece.id, city.id, city.worldId, CivsInfrastructure.building(piece),
                    piece.blocks.filter { it.after != "minecraft:barrier" }.map { CivPlanBlock(it.position, "minecraft:air", it.after) })
                ru.arc.paper.testing.MockBukkitTestRuntime.open().use {
                    CivsMaterials.required(captured)["${CivsSettlementLayout.wood(city.style).uppercase()}_FENCE_GATE"] shouldBe expectedLeaves.toLong()
                }
            }
        }
        val wet = frame.wallObstacles.first().min
        val denied = surface.copy(columns = surface.columns + (civsTerrainKey(wet.x, wet.z) to CivTerrainSurfaceColumn(64, false)))
        val rejection = CivsSettlementLayout.infrastructure(city, frame, denied, routes)
        rejection.perimeterClosed shouldBe false
        (rejection.rejected.getValue("protected-terrain") > 0) shouldBe true
        rejection.pieces.flatMap { it.blocks }.none { it.position.x == wet.x && it.position.z == wet.z } shouldBe true
    }

    test("a protected column splits a dry wall section and paid fragments replay exactly") {
        val city = layoutFixtureCity()
        val frame = CivsSettlementLayout.frame(city)
        val surface = layoutSurface(city, "flat")
        val start = city.buildings.first().entrance
        val routes = frame.gates.map { gate ->
            val corner = CivPosition(start.x, start.y, gate.center.z)
            layoutAxisRoute(start, corner) + layoutAxisRoute(corner, gate.center.copy(y = start.y)).drop(1)
        }
        val intact = CivsSettlementLayout.infrastructure(city, frame, surface, routes)
        val section = intact.pieces.first { it.type == BuildingType.WALL && it.bounds.max.x - it.bounds.min.x == 23 }
        val blocked = CivPosition(section.bounds.min.x + 11, 64, (section.bounds.min.z + section.bounds.max.z) / 2)
        val protected = surface.copy(columns = surface.columns +
            (civsTerrainKey(blocked.x, blocked.z) to CivTerrainSurfaceColumn(64, false)))
        val result = CivsSettlementLayout.infrastructure(city, frame, protected, routes)
        val fragments = result.pieces.filter { it.type == BuildingType.WALL &&
            CivsAutonomousGeometry.overlaps(it.bounds, section.bounds) }.sortedBy { it.bounds.min.x }
        fragments.map { it.bounds.min.x..it.bounds.max.x } shouldBe
            listOf(section.bounds.min.x until blocked.x, blocked.x + 1..section.bounds.max.x)
        fragments.forEach { piece ->
            piece.bounds.min.z shouldBe section.bounds.min.z
            piece.bounds.max.z shouldBe section.bounds.max.z
        }
        fragments.flatMap { it.blocks }.map { civsTerrainKey(it.position.x, it.position.z) }.distinct().size shouldBe 23 * 3
        result.perimeterClosed shouldBe false
        result.rejected shouldBe mapOf("protected-terrain" to 1)
        result.rejectionSamples shouldBe emptyMap()
        intact.pieces.filter { it.id != section.id }.forEach { piece -> result.pieces.single { it.id == piece.id } shouldBe piece }
        result shouldBe CivsSettlementLayout.infrastructure(city, frame, protected, routes)

        val paid = listOf(fragments.first(), result.pieces.first { it.type == BuildingType.GATE },
            result.pieces.first { it.type == BuildingType.ROAD })
        val completed = city.copy(buildings = city.buildings + paid.map { piece ->
            CivBuilding(piece.id, piece.type, 1, piece.bounds.min, 0, "fixture/infrastructure/${piece.id}",
                piece.bounds, piece.entrance, BuildingPhase.ACTIVE, transit = piece.transit.takeIf { it.isNotEmpty() })
        })
        val ownColumns = paid.flatMap { it.blocks }.groupBy { civsTerrainKey(it.position.x, it.position.z) }
        val mutated = protected.copy(columns = protected.columns + ownColumns.mapValues { (_, cells) ->
            CivTerrainSurfaceColumn(cells.filter { it.after != "minecraft:air" }.maxOf { it.position.y }, false)
        })
        val replay = CivsSettlementLayout.infrastructure(completed, frame, mutated, routes, paid)
        replay.pieces.associateBy { it.id } shouldBe result.pieces.associateBy { it.id }
        replay.perimeterClosed shouldBe false
        replay.pieces.forEachIndexed { index, piece ->
            replay.pieces.drop(index + 1).none { CivsAutonomousGeometry.overlaps(piece.bounds, it.bounds) } shouldBe true
        }
    }

    test("wall subdivision reports only final unsafe slope columns and their samples") {
        val city = layoutFixtureCity()
        val strip = CivBox(CivPosition(0, 64, 30), CivPosition(23, 64, 32))
        val frame = CivsSettlementLayout.frame(city).copy(wallObstacles = listOf(strip), gates = emptyList())
        val flat = layoutSurface(city, "flat")
        val intact = CivsSettlementLayout.infrastructure(city, frame, flat, emptyList()).pieces.single()
        intact.id shouldBe layoutId("${city.id}:layout:${CivsSettlementLayout.VERSION}:WALL:0:30:23:32")
        val cliff = flat.copy(columns = flat.columns.mapValues { (column, value) ->
            if ((column shr 32).toInt() >= 12) value.copy(groundY = 82) else value
        })
        val result = CivsSettlementLayout.infrastructure(city, frame, cliff, emptyList())
        result.rejected shouldBe mapOf("infrastructure-slope" to 2)
        result.rejectionSamples shouldBe mapOf("infrastructure-slope" to
            listOf(CivPosition(11, 64, 30), CivPosition(12, 82, 30)))
        result.perimeterClosed shouldBe false
        result.pieces.flatMap { it.blocks }.map { civsTerrainKey(it.position.x, it.position.z) }.toSet() shouldBe
            (0..23).filter { it !in 11..12 }.flatMap { x -> (30..32).map { z -> civsTerrainKey(x, z) } }.toSet()
    }

    test("generated gate leaves face the crossing and start open for every rotation and supported wood") {
        for (rotation in listOf(0, 90, 180, 270)) for (style in listOf("caledonia", "nordic", "medievaloak")) {
            val city = layoutFixtureCity(rotation).copy(style = style)
            val frame = CivsSettlementLayout.frame(city)
            val surface = layoutSurface(city, "flat")
            val plan = CivsSettlementLayout.infrastructure(city, frame, surface, emptyList())
            for (gate in frame.gates) {
                val piece = plan.pieces.single { it.type == BuildingType.GATE &&
                    it.bounds.min.x == gate.passage.min.x && it.bounds.min.z == gate.passage.min.z }
                val horizontal = Math.floorMod(gate.side + frame.rotation / 90, 2) == 0
                val facing = if (horizontal) "north" else "east"
                val expected = "minecraft:${CivsSettlementLayout.wood(style)}_fence_gate[facing=$facing,in_wall=false,open=true,powered=false]"
                val leaves = piece.blocks.filter { it.after.contains("_fence_gate[") }
                leaves.size shouldBe if (horizontal) gate.passage.max.x - gate.passage.min.x + 1
                    else gate.passage.max.z - gate.passage.min.z + 1
                leaves.forEach { leaf ->
                    leaf.after shouldBe expected
                    piece.transit.any { it == leaf.position } shouldBe true
                }
            }
        }
    }

    test("a supported water wall plans paid foundation down to the surveyed bottom") {
        val city = layoutFixtureCity()
        val original = CivsSettlementLayout.frame(city)
        val wall = CivBox(CivPosition(30, 64, 30), CivPosition(35, 64, 32))
        val frame = original.copy(wallObstacles = listOf(wall), gates = emptyList())
        val base = layoutSurface(city, "flat")
        val wet = (33..35).flatMap { x -> (30..32).map { z -> civsTerrainKey(x, z) } }.toSet()
        val surface = base.copy(columns = base.columns.mapValues { (key, value) ->
            if (key in wet) CivTerrainSurfaceColumn(64, false, waterTopY = 64, waterBottomY = 57) else value
        })
        val planned = CivsSettlementLayout.infrastructure(city, frame, surface, emptyList())
        val wetPieces = planned.pieces.filter { it.waterFoundations.isNotEmpty() }
        wetPieces.flatMap { it.waterFoundations }.size shouldBe wet.size
        val cells = wetPieces.flatMap { it.blocks }.associateBy { it.position }
        for (x in 33..35) for (z in 30..32) for (y in 58..63)
            cells[CivPosition(x, y, z)]?.after shouldBe "minecraft:stone_bricks"
        val invalid = surface.copy(columns = surface.columns + (civsTerrainKey(33, 30) to
            CivTerrainSurfaceColumn(64, false, waterTopY = 64, waterBottomY = 51)))
        val rejected = CivsSettlementLayout.infrastructure(city, frame, invalid, emptyList())
        rejected.perimeterClosed shouldBe false
        rejected.pieces.flatMap { it.waterFoundations }.none { it.x == 33 && it.z == 30 } shouldBe true
    }

    test("gate keeps the three-wide native path around a retained route") {
        val city = layoutFixtureCity()
        val frame = CivsSettlementLayout.frame(city)
        val gate = frame.gates.first()
        val route = layoutAxisRoute(gate.center.copy(x = gate.center.x - 6, y = 65),
            gate.center.copy(x = gate.center.x + 6, y = 65))
        val pathColumns = route.flatMap { point -> (-1..1).map { dz -> civsTerrainKey(point.x, point.z + dz) } }.toSet()
        val original = layoutSurface(city, "flat")
        val surface = original.copy(columns = original.columns.mapValues { (key, value) ->
            if (key in pathColumns) value.copy(natural = false, dirtPath = true) else value
        })
        val result = CivsSettlementLayout.infrastructure(city, frame, surface, listOf(route), retainedRoutes = listOf(route))
        val passage = result.pieces.single { it.type == BuildingType.GATE &&
            it.bounds.min.x == gate.passage.min.x && it.bounds.min.z == gate.passage.min.z }
        val blocks = passage.blocks.associate { it.position to it.after }
        for (x in gate.passage.min.x..gate.passage.max.x) for (z in gate.passage.min.z..gate.passage.max.z) {
            if (civsTerrainKey(x, z) in pathColumns)
                blocks[CivPosition(x, 64, z)] shouldBe "minecraft:barrier"
        }
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

    test("road paving retains a later raised route centre and passes the actual terraform draft") {
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

    test("a verified paid road spine joins a new extension without authoring central writes or shoulders") {
        val city = layoutFixtureCity()
        val route = layoutAxisRoute(CivPosition(0, 65, 6), CivPosition(0, 65, 20))
        val base = layoutSurface(city, "flat")
        val artificial = base.copy(columns = base.columns + route.associate {
            civsTerrainKey(it.x, it.z) to CivTerrainSurfaceColumn(64, false)
        })
        val frame = CivsSettlementLayout.frame(city)
        val extended = route + (21..24).map { CivPosition(0, 65, it) }
        val planned = CivsSettlementLayout.infrastructure(city, frame, artificial, listOf(extended), retainedRoutes = listOf(route))
        val roads = planned.pieces.filter { it.type == BuildingType.ROAD }
        roads.size shouldBe 1
        route.forEach { point ->
            (point in roads.single().transit) shouldBe true
            roads.single().blocks.filter { it.position.x == point.x && it.position.z == point.z }
                .map { it.after } shouldBe List(3) { "minecraft:barrier" }
        }
        roads.single().blocks.all { it.position.x == 0 } shouldBe true
        roads.single().transit shouldBe extended
        CivsSettlementLayout.infrastructure(city, frame, artificial, listOf(route), retainedRoutes = listOf(route))
            .pieces.none { it.type == BuildingType.ROAD } shouldBe true
        val unknown = CivsSettlementLayout.infrastructure(city, frame, artificial, listOf(extended))
        unknown.pieces.filter { it.type == BuildingType.ROAD }.forEach { piece ->
            route.none { CivsAutonomousGeometry.horizontalContains(piece.bounds, it) } shouldBe true
        }
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
        // Old paid captures retain their exact cobble material after the new default changes.
        val first = CivsSettlementLayout.infrastructure(city, frame, surface, routes).let { fresh ->
            fresh.copy(pieces = fresh.pieces.map { piece -> if (piece.type != BuildingType.ROAD) piece else
                piece.copy(blocks = piece.blocks.map { cell -> cell.copy(after = when {
                    cell.after == "minecraft:dirt_path" -> "minecraft:cobblestone"
                    cell.after.contains("_stairs[") -> cell.after.replace("minecraft:${CivsSettlementLayout.wood(city.style)}_", "minecraft:cobblestone_")
                    else -> cell.after
                }) }) })
        }
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
