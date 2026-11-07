package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
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

    for (retainedGrade in listOf<Int?>(null, 65))
    test("road paving ignores a downhill hint outside the final floor domain retainedGrade=$retainedGrade") {
        val baseCity = layoutFixtureCity()
        val route = listOf(CivPosition(12, 65, 12), CivPosition(13, 66, 12), CivPosition(14, 66, 12))
        val lower = route.first().copy(y = route.first().y - 1)
        val paid = retainedGrade?.let { grade ->
            val floor = lower.copy(y = grade)
            CivSettlementPiece(layoutId("retained-downhill"), BuildingType.ROAD,
                CivBox(floor, floor.copy(y = grade + 2)), floor.copy(y = grade + 1, z = floor.z - 1),
                listOf(CivSettlementBlock(floor, "minecraft:dirt_path"),
                    CivSettlementBlock(floor.copy(y = grade + 1), "minecraft:air"),
                    CivSettlementBlock(floor.copy(y = grade + 2), "minecraft:air")), listOf(floor.copy(y = grade + 1)))
        }
        val city = if (paid == null) baseCity else baseCity.copy(buildings = baseCity.buildings +
            CivsInfrastructure.building(paid).copy(phase = BuildingPhase.ACTIVE))
        val surface = layoutSurface(city, "flat").let { base -> base.copy(columns = base.columns +
            route.associate { civsTerrainKey(it.x, it.z) to CivTerrainSurfaceColumn(it.y - 1, true) } +
            (civsTerrainKey(lower.x, lower.z) to CivTerrainSurfaceColumn(retainedGrade ?: lower.y, false))) }
        val frame = CivsSettlementLayout.frame(city).copy(wallObstacles = emptyList(), gates = emptyList())
        val result = CivsSettlementLayout.infrastructure(city, frame, surface, listOf(route), listOfNotNull(paid))
        result.rejected shouldBe emptyMap()
        val fresh = result.pieces.filter { it.type == BuildingType.ROAD && it.id != paid?.id }
        fresh.flatMap { it.transit }.toSet() shouldBe route.drop(1).toSet()
        val cells = fresh.flatMap { it.blocks }.associate { it.position to it.after }
        cells[route[1].copy(y = route[1].y - 1)] shouldBe "minecraft:dirt_path"
        cells.keys.none { it.x == lower.x && it.z == lower.z } shouldBe true
        if (paid != null) result.pieces.single { it.id == paid.id } shouldBe paid
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

    test("only an autonomous farm may use bounded inner yards while keeping the full future envelope and reservations") {
        val base = layoutFixtureCity().copy(radius = 128, autonomy = CivAutonomy(layoutHalfWidth = 74))
        val neighbor = layoutFixtureBuilding(BuildingType.HOUSE, CivPosition(28, 64, 24))
        val city = base.copy(buildings = base.buildings + neighbor)
        val frame = CivsSettlementLayout.frame(city)
        val road = (0..45).map { CivPosition(0, 65, it) }
        val port = CivBox(CivPosition(-30, 64, -30), CivPosition(-20, 72, -20))
        val access = CivAutonomousAccess(mapOf(base.buildings.single().id to road), reservedFortificationPorts = listOf(port))
        val farm = layoutFixtureBuilding(BuildingType.FARM)
        val future = farm.copy(level = 3, bounds = CivBox(CivPosition(-10, -2, -30), CivPosition(10, 14, -1)))
        val choices = CivsSettlementLayout.infill(city, farm, future, access, frame)
        (choices.size in 1..64) shouldBe true
        choices shouldBe CivsSettlementLayout.infill(city, farm, future, access, frame)
        val occupied = city.buildings.map { CivsAutonomousGeometry.futureBounds(city, it) } + port
        val protected = city.buildings.flatMap(CivsAutonomousGeometry::approach) + road
        for ((building) in choices) {
            val bounds = building.bounds
            (minOf(bounds.max.x - bounds.min.x, bounds.max.z - bounds.min.z) >= 20) shouldBe true
            (maxOf(bounds.max.x - bounds.min.x, bounds.max.z - bounds.min.z) >= 29) shouldBe true
            (bounds.min.x >= frame.core.min.x + 4 && bounds.max.x <= frame.core.max.x - 4 &&
                bounds.min.z >= frame.core.min.z + 4 && bounds.max.z <= frame.core.max.z - 4) shouldBe true
            occupied.none { CivsAutonomousGeometry.overlaps(bounds, it, 2) } shouldBe true
            protected.none { CivsAutonomousGeometry.horizontalContains(bounds, it) } shouldBe true
            frame.wallObstacles.none { CivsAutonomousGeometry.overlaps(bounds, it, 2) } shouldBe true
            CivsAutonomousGeometry.overlaps(bounds, frame.court) shouldBe false
            CivsRules.workRouteWithinReach(city, building.entrance) shouldBe true
        }
        CivsSettlementLayout.infill(city.copy(autonomy = null), farm, future, access, frame) shouldBe emptyList()
        for (type in listOf(BuildingType.FORESTER, BuildingType.QUARRY, BuildingType.ROAD)) {
            val template = layoutFixtureBuilding(type)
            CivsSettlementLayout.infill(city, template, template, access, frame) shouldBe emptyList()
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
        searches.all { it.from == start && it.expanded == 0 && it.outcome == "route-disconnected" } shouldBe true
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

    test("real infrastructure owns nonoverlapping boxes and clear supported gate thresholds") {
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
                if (piece.type != BuildingType.GATE) piece.transit.size shouldBe
                    (piece.bounds.max.x - piece.bounds.min.x + 1) * (piece.bounds.max.z - piece.bounds.min.z + 1)
                (piece.transit.size <= 256) shouldBe true
            }
            if (piece.type == BuildingType.GATE) {
                val cells = piece.blocks.associate { it.position to it.after }
                val building = CivsInfrastructure.building(piece)
                CivsInfrastructureRebuild.stage(building) shouldBe 1
                CivsGateRefit.validTransit(building) shouldBe true
                piece.blocks.none { it.after.contains("_fence_gate[") } shouldBe true
                for (feet in piece.transit) for (dy in 0..2)
                    cells[feet.copy(y = feet.y + dy)] shouldBe "minecraft:air"
                val captured = CivPlacementPlan(piece.id, city.id, city.worldId, building,
                    piece.blocks.filter { it.after != "minecraft:barrier" }.map { CivPlanBlock(it.position, "minecraft:air", it.after) })
                ru.arc.paper.testing.MockBukkitTestRuntime.open().use {
                    CivsMaterials.required(captured)["${CivsSettlementLayout.wood(city.style).uppercase()}_FENCE_GATE"] shouldBe null
                    (CivsMaterials.required(captured).getValue("${CivsSettlementLayout.wood(city.style).uppercase()}_LOG") > 0L) shouldBe true
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

    test("low walls close all reserved corner shoulders when generated or rebuilt at any hall rotation") {
        fun shifted(p: CivPosition) = p.copy(x = p.x + 101, z = p.z - 57)
        for (rotation in listOf(0, 90, 180, 270)) for (stage in 1..2) {
            val base = layoutFixtureCity(rotation)
            val city = base.copy(center = shifted(base.center), autonomy = CivAutonomy(layoutHalfWidth = 32),
                buildings = base.buildings.map { it.copy(level = stage, anchor = shifted(it.anchor),
                    bounds = CivBox(shifted(it.bounds.min), shifted(it.bounds.max)), entrance = shifted(it.entrance)) })
            val frame = CivsSettlementLayout.frame(city)
            val fresh = CivsSettlementLayout.infrastructure(city, frame, layoutSurface(city, "flat"), emptyList())
            fresh.rejected shouldBe emptyMap()
            val walls = fresh.pieces.filter { it.type == BuildingType.WALL }
            val cells = walls.flatMap { it.blocks }.associate { it.position to it.after }
            val legacy = walls.associate { piece -> piece.id to CivsInfrastructure.building(piece.copy(profileStage = null))
                .copy(phase = BuildingPhase.ACTIVE) }
            val oldCity = city.copy(buildings = city.buildings + legacy.values)
            for (x in listOf(-32, 32)) for (z in listOf(-31, 31)) {
                val local = when (rotation) {
                    90 -> CivPosition(-z, 65, x); 180 -> CivPosition(-x, 65, -z)
                    270 -> CivPosition(z, 65, -x); else -> CivPosition(x, 65, z)
                }
                val feet = shifted(local)
                val piece = walls.single { CivsAutonomousGeometry.horizontalContains(it.bounds, feet) }
                val before = CivPlacementPlan(piece.id, city.id, city.worldId,
                    legacy.getValue(piece.id).copy(phase = BuildingPhase.CONSTRUCTING), piece.blocks.map {
                        CivPlanBlock(it.position, "minecraft:air", if (it.position.y <= 67) "minecraft:stone_bricks" else "minecraft:air")
                    }, capturedSha256 = "a".repeat(64))
                val rebuilt = CivsInfrastructureRebuild.proposal(oldCity, before, emptyList())
                rebuilt.building.bounds shouldBe piece.bounds
                val after = rebuilt.blocks.associate { it.position to it.after }
                for (dy in 0..1) {
                    (cells[feet.copy(y = feet.y + dy)]?.substringBefore('[') in
                        setOf("minecraft:oak_planks", "minecraft:oak_log", "minecraft:stone_bricks")) shouldBe true
                    after[feet.copy(y = feet.y + dy)] shouldBe cells[feet.copy(y = feet.y + dy)]
                }
            }
            if (rotation == 0) System.getenv("CIVS_LAYOUT_DIAGNOSTIC_DIR")?.let { directory ->
                val corner = walls.flatMap { it.blocks }.filter { it.position.x >= city.center.x + 26 && it.position.z >= city.center.z + 26 }
                val box = CivBox(CivPosition(corner.minOf { it.position.x }, 64, corner.minOf { it.position.z }),
                    CivPosition(corner.maxOf { it.position.x }, 68, corner.maxOf { it.position.z }))
                val path = Path.of(directory).resolve("wall-corner-stage$stage.json")
                Files.createDirectories(path.parent)
                Files.writeString(path, Gson().toJson(mapOf("name" to "wall-corner-stage$stage", "bounds" to box, "after" to corner)))
            }
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

    test("generated gate profiles keep clear crossings and supported roofs for every rotation and wood") {
        for (rotation in listOf(0, 90, 180, 270)) for (style in listOf("caledonia", "nordic", "medievaloak")) {
            val city = layoutFixtureCity(rotation).copy(style = style)
            val frame = CivsSettlementLayout.frame(city)
            val surface = layoutSurface(city, "flat")
            val plan = CivsSettlementLayout.infrastructure(city, frame, surface, emptyList())
            plan.rejected shouldBe emptyMap()
            for (gate in frame.gates) {
                val piece = plan.pieces.single { it.type == BuildingType.GATE &&
                    it.bounds.min.x == gate.passage.min.x && it.bounds.min.z == gate.passage.min.z }
                val horizontal = Math.floorMod(gate.side + frame.rotation / 90, 2) == 0
                val wood = CivsSettlementLayout.wood(style)
                val span = if (horizontal) gate.passage.max.x - gate.passage.min.x + 1
                    else gate.passage.max.z - gate.passage.min.z + 1
                val cells = piece.blocks.associate { it.position to it.after }
                val floorY = piece.bounds.min.y
                fun point(across: Int, y: Int, depth: Int) = if (horizontal)
                    CivPosition(piece.bounds.min.x + across, y, piece.bounds.min.z + depth) else
                    CivPosition(piece.bounds.min.x + depth, y, piece.bounds.min.z + across)
                val fullTransit = (0 until span).flatMap { across -> (0..4).map { depth -> point(across, floorY + 1, depth) } }.toSet()
                fullTransit.forEach { cells[it.copy(y = floorY)] shouldBe "minecraft:cobblestone" }
                val building = CivsInfrastructure.building(piece)
                CivsInfrastructureRebuild.stage(building) shouldBe 1
                CivsGateRefit.validTransit(building) shouldBe true
                piece.blocks.none { it.after.contains("_fence_gate[") } shouldBe true
                val posts = (0 until span).filter { it !in (span - 3) / 2..(span - 3) / 2 + 2 }
                    .flatMap { across -> (1..3).map { depth -> point(across, floorY + 1, depth) } }.toSet()
                piece.transit.toSet() shouldBe fullTransit - posts
                for (feet in piece.transit) for (dy in 0..2)
                    cells[feet.copy(y = feet.y + dy)] shouldBe "minecraft:air"
                posts.forEach { foot ->
                    cells[foot] shouldBe if (style == "nordic") "minecraft:cobblestone" else "minecraft:stone_bricks"
                    for (y in foot.y + 1 until piece.bounds.max.y - 1)
                        cells[foot.copy(y = y)] shouldBe "minecraft:${wood}_log[axis=y]"
                }
                for (across in 0 until span) for (depth in 1..3) {
                    cells[point(across, piece.bounds.max.y - 1, depth)] shouldBe
                        "minecraft:${wood}_log[axis=${if (horizontal) "x" else "z"}]"
                    val roofFacing = if (horizontal) { if (depth == 1) "south" else "north" }
                        else { if (depth == 1) "east" else "west" }
                    cells[point(across, piece.bounds.max.y, depth)] shouldBe if (depth == 2)
                        "minecraft:${wood}_slab[type=top,waterlogged=false]" else
                        "minecraft:${wood}_stairs[facing=$roofFacing,half=bottom,shape=straight,waterlogged=false]"
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

    test("a deferred wall joins across bare grass beside its retained work approach") {
        val base = layoutFixtureCity()
        val hall = base.buildings.single()
        val west = CivBox(CivPosition(-31, 64, 4), CivPosition(-29, 64, 16))
        val gate = CivsSettlementLayout.frame(base, 32).gates.first()
        val accepted = listOf(4..9, 11..16).map { span ->
            val bounds = CivBox(west.min.copy(z = span.first), west.max.copy(y = 68, z = span.last))
            val cells = (-31..-29).flatMap { x -> span.flatMap { z ->
                CivsInfrastructureRebuild.wallColumn(base.style, 1, x, 64, z, false, -30, 4)
            } }
            CivSettlementPiece(layoutId("west-wall-${span.first}"), BuildingType.WALL, bounds,
                CivPosition(-28, 65, span.last), cells, profileStage = 1)
        }
        val intent = CivSettlementPlan(CivsSettlementPlans.VERSION, 7, 32,
            listOf(CivSettlementPlot(hall, hall.bounds)), emptyMap(), listOf(west), listOf(gate.passage),
            listOf(gate.side), CivDeferredPerimeter("infrastructure", hall.id, hall.anchor))
        val city = base.copy(buildings = base.buildings + accepted.map {
            CivsInfrastructure.building(it).copy(phase = BuildingPhase.ACTIVE)
        }, autonomy = CivAutonomy(layoutHalfWidth = 32, plan = intent))
        val frame = CivsSettlementLayout.frame(city)
        val corner = hall.entrance.copy(z = 9)
        val approach = layoutAxisRoute(hall.entrance, corner) + layoutAxisRoute(corner, accepted.first().entrance).drop(1)
        val left = city.building(accepted.first().id)!!
        val routePlan = CivTerraformPlan(CivBox(CivPosition(-28, 64, 0), CivPosition(0, 65, 10)),
            emptyList(), approach, 64, 0, 0)
        val journal = CivPlacementPlan(left.id, city.id, city.worldId, left, emptyList(), "a".repeat(64), routePlan)
        val access = CivAutonomousAccess.from(city, listOf(journal))
        val shoulder = CivPosition(-29, 64, 10)
        val key = civsTerrainKey(shoulder.x, shoulder.z)
        val grass = CivTerrainSurfaceColumn(64, true, cleanGrass = true)
        val flat = layoutSurface(city, "flat")
        val surface = flat.copy(columns = flat.columns + (key to grass))
        fun generate(terrain: CivTerrainSurface = surface, retained: CivAutonomousAccess = access) =
            CivsSettlementLayout.infrastructure(city, frame, terrain, retained.routes.values.toList(), accepted,
                pavingRoutes = emptyList(), retainedRoutes = retained.routes.values, retainedAccess = retained)
        val generated = generate()
        val seam = generated.pieces.filterNot { it in accepted }.single()
        seam.bounds shouldBe CivBox(CivPosition(-31, 64, 10), CivPosition(-29, 68, 10))
        seam.requiredGrass shouldBe listOf(shoulder)
        generated.pieces.take(accepted.size) shouldBe accepted
        generated.rejected["deferred-gate"] shouldBe 1
        generated.perimeterClosed shouldBe false

        val floors = seam.blocks.groupBy { civsTerrainKey(it.position.x, it.position.z) }
            .mapValues { (_, cells) -> cells.minOf { it.position.y } }
        val draft = CivsAutonomousTerraform.draft(city, CivTerraformOption(CivsInfrastructure.building(seam), 0),
            surface, access, frame.wallObstacles, floors).copy(requiredGrass = seam.requiredGrass)
        draft.columns.filter { it.x in -31..-29 && it.z == 10 }.map { it.floorY to it.preserve } shouldBe
            List(3) { 64 to false }
        val cells = buildMap {
            for (column in draft.columns) for (y in column.supportY..(maxOf(column.surfaceY, column.groundY) + 2)) {
                val p = CivPosition(column.x, y, column.z)
                val material = when {
                    y > column.surfaceY -> "minecraft:air"
                    y == column.surfaceY -> "minecraft:grass_block[snowy=false]"
                    else -> "minecraft:dirt"
                }
                put(p, CivTerraformCell(p, material, true, y <= column.surfaceY, y > column.surfaceY))
            }
        }
        val earthworks = CivsAutonomousTerraform.materialize(draft, cells)
        earthworks.requiredGrass shouldBe listOf(shoulder)
        val newlyPaved = CivTerraformCell(shoulder, "minecraft:dirt_path", safe = true, solid = true, passable = false)
        shouldThrow<CivsRejection> {
            CivsAutonomousTerraform.materialize(draft, cells + (shoulder to newlyPaved))
        }.values["stage"] shouldBe "wall-approach-grass"

        for (native in listOf(grass.copy(cleanGrass = false), grass.copy(loaded = false),
            grass.copy(groundY = 65, topY = 65), grass.copy(topY = 65),
            CivTerrainSurfaceColumn(64, false, dirtPath = true))) {
            val protected = generate(surface.copy(columns = surface.columns + (key to native)))
            protected.pieces shouldBe accepted
            protected.rejected["route-crosses-wall"] shouldBe (generated.rejected["route-crosses-wall"] ?: 0) + 1
        }
        generate(retained = access.copy(routes = access.routes + (hall.id to approach))).pieces shouldBe accepted
        val paidTerrain = routePlan.copy(blocks = listOf(CivPlanBlock(shoulder, "minecraft:dirt", "minecraft:grass_block[snowy=false]")))
        val paidGrass = CivAutonomousAccess.from(city, listOf(journal.copy(terrain = paidTerrain)))
        generate(retained = paidGrass).pieces shouldBe accepted
        val centerline = listOf(shoulder.copy(y = 65), approach.last().copy(z = 10), approach.last())
        val centerAccess = CivAutonomousAccess.from(city, listOf(journal.copy(terrain = routePlan.copy(route = centerline))))
        generate(retained = centerAccess).pieces shouldBe accepted

        val built = CivsInfrastructure.building(seam).copy(phase = BuildingPhase.ACTIVE)
        val source = journal.copy(id = built.id, building = built, terrain = earthworks, blocks = seam.blocks.map {
            CivPlanBlock(it.position, cells[it.position]?.data ?: "minecraft:air", it.after)
        })
        val upgradedCity = city.copy(buildings = listOf(hall.copy(level = 2)) + city.buildings.drop(1) + built)
        val refit = CivsInfrastructureRebuild.proposal(upgradedCity, source, emptyList())
        refit.terrain?.requiredGrass shouldBe null
        refit.terrain?.route shouldBe earthworks.route
        val rebuilt = refit.building.copy(phase = BuildingPhase.ACTIVE)
        val rebuiltCity = upgradedCity.copy(buildings = upgradedCity.buildings.dropLast(1) + rebuilt)
        val rebuiltAccess = CivAutonomousAccess.from(rebuiltCity, listOf(refit.copy(capturedSha256 = "b".repeat(64))))
        rebuiltAccess.routes[rebuilt.id] shouldBe earthworks.route
        rebuiltAccess.wallApproachWrites.containsKey(rebuilt.id) shouldBe false
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
            CivsInfrastructure.building(piece).copy(phase = BuildingPhase.ACTIVE)
        }
        val completed = city.copy(buildings = city.buildings + buildings)
        val plans = first.pieces.map { piece ->
            CivPlacementPlan(piece.id, city.id, city.worldId,
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

    test("export production planner and real family headers on flat hill cliff water and existing-town fixtures").config(
        enabled = System.getenv("CIVS_LAYOUT_FRAMES") != null,
    ) {
        val source = requireNotNull(System.getenv("CIVS_LAYOUT_FRAMES"))
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
