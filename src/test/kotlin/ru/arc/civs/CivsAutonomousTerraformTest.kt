package ru.arc.civs

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.kotest.assertions.withClue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.math.abs
import org.bukkit.Bukkit
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.civs.CivsAutonomousTerraform.Node

class CivsAutonomousTerraformTest : FunSpec({
    test("a unanimous paid Hall lead-out keeps the source and shoulders while a new farm can use its actual grade") {
        val original = terraceCity()
        val entrance = CivPosition(0, 66, 0)
        val hall = original.buildings.single().copy(anchor = entrance.copy(y = 65), entrance = entrance,
            phase = BuildingPhase.ACTIVE)
        // Auto4 x11506/z9790 translated to the origin. All three retained routes rise at z9791.
        val leadOut = listOf(entrance) + (1..4).map { CivPosition(0, 67, it) }
        val owners = listOf(Triple(BuildingType.BUILDER_HUT, -8, 6), Triple(BuildingType.FORESTER, 8, 9),
            Triple(BuildingType.QUARRY, -8, 12)).mapIndexed { index, (type, x, z) ->
            val edge = if (x < 0) -1 else 1
            CivBuilding(UUID(230, index.toLong()), type, 1, CivPosition(x, 66, z), if (edge < 0) 270 else 90,
                "fixture", CivBox(CivPosition(minOf(x + edge, x + 3 * edge), 66, z - 1),
                    CivPosition(maxOf(x + edge, x + 3 * edge), 70, z + 1)), CivPosition(x, 67, z), BuildingPhase.ACTIVE)
        }
        val city = original.copy(buildings = listOf(hall) + owners)
        val journalBounds = CivBox(CivPosition(-12, 64, 0), CivPosition(12, 68, 13))
        val plans = owners.map { owner ->
            val end = owner.entrance
            val direction = end.x.compareTo(0)
            val route = leadOut + (5..end.z).map { CivPosition(0, 67, it) } +
                (1..abs(end.x)).map { CivPosition(it * direction, 67, end.z) }
            CivPlacementPlan(owner.id, city.id, city.worldId, owner, emptyList(), terrain =
                CivTerraformPlan(journalBounds, emptyList(), route, 66, 0, 0))
        }
        val farm = terraceBuilder(city, ground = 66).copy(type = BuildingType.FARM)
        val option = CivTerraformOption(farm, 0)
        // Loaded height capture reported surface Y65 at the source and Y66 over the next three rows.
        val surface = terraceSurface { _, z -> if (z <= 0) 65 else 66 }
        val access = CivAutonomousAccess.from(city, plans)
        access.routes.keys shouldBe owners.mapTo(hashSetOf()) { it.id }
        val draft = CivsAutonomousTerraform.draft(city, option, surface, access)
        val cells = terraceCapture(draft, surface)
        val prepared = CivsAutonomousTerraform.materialize(draft, cells)
        prepared.route.take(4) shouldBe leadOut.take(4)
        prepared.route.last() shouldBe farm.entrance
        checkTerrace(prepared, draft, cells)
        prepared.blocks.none { it.position.x in -1..1 && it.position.z in 0..3 } shouldBe true
        val standing = access.standing(city, farm.id)
        standing[civsTerrainKey(0, 0)] shouldBe entrance
        for (x in listOf(-1, 1)) for (z in 1..3)
            standing[civsTerrainKey(x, z)] shouldBe CivPosition(x, 66, z)

        fun rejectsUnownedLeadOut(journals: List<CivPlacementPlan>) {
            val guarded = CivAutonomousAccess.from(city, journals)
            guarded.routes.keys shouldBe access.routes.keys
            val refusal = shouldThrow<CivsRejection> {
                CivsAutonomousTerraform.draft(city, option, surface, guarded, reservedRoute = prepared.route)
            }
            refusal.reason shouldBe "autonomy-no-path"
            refusal.values["stage"] shouldBe "retained-grade"
            val retained = guarded.standing(city, farm.id)
            (0..3).forEach { z -> retained[civsTerrainKey(0, z)] shouldBe CivPosition(0, 66, z) }
        }
        // A disagreement at the first cell cannot be bypassed to accept later agreement.
        val first = plans.first()
        val terrain = requireNotNull(first.terrain)
        rejectsUnownedLeadOut(listOf(first.copy(terrain = terrain.copy(route = terrain.route.mapIndexed { index, feet ->
            if (index == 1) feet.copy(y = 66) else feet
        }))) + plans.drop(1))
        // A structurally valid journal is no lead-out witness if it starts above the actual Hall entrance.
        rejectsUnownedLeadOut(plans.map { plan ->
            val path = requireNotNull(plan.terrain)
            plan.copy(terrain = path.copy(route = listOf(entrance.copy(y = 67)) + path.route.drop(1)))
        })
    }

    test("a reconnected ordinary road preserves the reserved fortification port and its shoulder") {
        val city = terraceCity()
        val building = terraceBuilder(city)
        val surface = terraceSurface { _, _ -> 64 }
        // The shortest centerline at x=0 clears the raw port, but its right shoulder does not.
        val port = CivBox(CivPosition(1, 64, 6), CivPosition(3, 72, 8))
        val reservedApproach = CivBox(port.min.copy(x = 0, z = 5), port.max.copy(x = 4, z = 9))
        val access = CivAutonomousAccess(reservedFortificationPorts = listOf(port))
        val route = CivsAutonomousTerraform.connection(city, building, surface, access, emptyList())
        route.first() shouldBe city.buildings.single().entrance
        route.last() shouldBe building.entrance
        route.none { CivsAutonomousGeometry.horizontalContains(reservedApproach, it) } shouldBe true
        CivsAutonomousTerraform.roadProfile(route).values.none {
            CivsAutonomousGeometry.horizontalContains(port, it)
        } shouldBe true
        val draft = CivsAutonomousTerraform.draft(city, CivTerraformOption(building, 0), surface,
            access, reservedRoute = route)
        draft.route shouldBe route
        val cells = terraceCapture(draft, surface)
        checkTerrace(CivsAutonomousTerraform.materialize(draft, cells), draft, cells)
    }

    test("a frontage connector keeps the real side apron flat before materializing the reserved route") {
        val original = terraceCity()
        val hall = original.buildings.single().copy(anchor = CivPosition(-35, 65, 3),
            entrance = CivPosition(-35, 66, 3), bounds = CivBox(CivPosition(-34, 65, 5), CivPosition(-30, 72, 10)),
            control = null)
        val city = original.copy(buildings = listOf(hall))
        val building = CivBuilding(UUID(0, 220), BuildingType.FORESTER, 1, CivPosition(-39, 64, 0), 0, "fixture",
            CivBox(CivPosition(-42, 64, -7), CivPosition(-36, 72, -1)), CivPosition(-39, 65, 0), BuildingPhase.CONSTRUCTING)
        val join = CivPosition(-39, 65, 4)
        val apron = CivsAutonomousGeometry.approach(building).mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }
        val surface = CivTerrainSurface(-64, 320, buildMap {
            for (x in building.bounds.min.x..building.bounds.max.x) for (z in building.bounds.min.z..building.bounds.max.z)
                put(civsTerrainKey(x, z), CivTerrainSurfaceColumn(64, true))
            apron.forEach { put(it, CivTerrainSurfaceColumn(64, true)) }
            put(civsTerrainKey(join.x, join.z), CivTerrainSurfaceColumn(64, true))
            // The only captured incoming corridor reaches a raised side-apron cell. Without
            // the real apron constraint, the route stays at feet66 here and descends onto the stub.
            for (x in -38..-35) put(civsTerrainKey(x, 3), CivTerrainSurfaceColumn(65, true))
        })
        val searches = mutableListOf<CivRouteSearchStats>()
        val route = CivsAutonomousTerraform.connection(city, building, surface, CivAutonomousAccess(),
            emptyList(), searches::add, join)
        route.last() shouldBe building.entrance
        route.any { it.x == -38 && it.z == 3 } shouldBe true
        route.filter { civsTerrainKey(it.x, it.z) in apron }.all { it.y == building.entrance.y } shouldBe true
        searches.size shouldBe 1
        searches.single().to shouldBe join
        val draft = CivsAutonomousTerraform.draft(city, CivTerraformOption(building, 0), surface, reservedRoute = route)
        val cells = terraceCapture(draft, surface)
        val materialized = CivsAutonomousTerraform.materialize(draft, cells)
        materialized.route shouldBe route
        checkTerrace(materialized, draft, cells)
    }

    for (rotation in listOf(0, 90, 180, 270))
    test("a composite farm reserves its field padding but reaches its declared apron at rotation $rotation") {
        CivsWorksiteLayouts.installCatalog(mapOf(COMPOSITE_FARM to compositeFarmSite()))
        try {
            val city = terraceCity()
            val building = compositeFarm(city, rotation)
            val surface = terraceSurface { _, _ -> 64 }
            val searches = mutableListOf<CivRouteSearchStats>()
            val draft = CivsAutonomousTerraform.draft(city, CivTerraformOption(building, 0), surface,
                inspectRoute = searches::add)
            draft.route.last() shouldBe building.entrance
            searches.single().outcome shouldBe "found"
            val inside = draft.route.filter { CivsAutonomousGeometry.horizontalContains(building.bounds, it) }
            inside.isNotEmpty() shouldBe true
            val apron = CivsAutonomousGeometry.approach(building)
            inside.all { it in apron && !CivsWorksiteLayouts.isField(building, it.x, it.z) } shouldBe true
            val cells = terraceCapture(draft, surface)
            checkTerrace(CivsAutonomousTerraform.materialize(draft, cells), draft, cells)
        } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
    }

    for (blocked in listOf("structure", "soil", "irrigation", "wall", "other-building", "outside-city",
        "unloaded", "nonnatural", "missing-metadata", "different-entrance"))
    test("a padded farm apron still rejects $blocked before route expansion") {
        val site = compositeFarmSite()
        when (blocked) {
            "structure" -> site.getAsJsonObject("schematicBounds").add("max", JsonParser.parseString("[1,2,0]"))
            "soil" -> site.getAsJsonObject("farm").getAsJsonArray("soilCells").add(JsonParser.parseString("[0,0,0]"))
            "irrigation" -> site.getAsJsonObject("farm").getAsJsonArray("irrigationCells").add(JsonParser.parseString("[0,0,0]"))
            "different-entrance" -> site.add("entrance", JsonParser.parseString("[1,1,0]"))
        }
        CivsWorksiteLayouts.installCatalog(mapOf(COMPOSITE_FARM to site))
        try {
            val original = terraceCity()
            val building = compositeFarm(original)
            val entry = building.entrance
            val obstruction = CivBox(entry.copy(y = 64), entry.copy(y = 68))
            val city = when (blocked) {
                "outside-city" -> original.copy(radius = 8)
                "other-building" -> original.copy(buildings = original.buildings + original.buildings.single().copy(
                    id = UUID(0, 206), type = BuildingType.HOUSE, bounds = obstruction, entrance = entry))
                else -> original
            }
            val surface = terraceSurface { _, _ -> 64 }.let { field -> when (blocked) {
                "unloaded", "nonnatural" -> field.copy(columns = field.columns + (civsTerrainKey(entry.x, entry.z) to
                    CivTerrainSurfaceColumn(64, natural = blocked != "nonnatural", loaded = blocked != "unloaded")))
                else -> field
            } }
            if (blocked == "missing-metadata") CivsWorksiteLayouts.installCatalog(emptyMap())
            val searches = mutableListOf<CivRouteSearchStats>()
            shouldThrow<CivsRejection> { CivsAutonomousTerraform.connection(city, building, surface,
                CivAutonomousAccess(), if (blocked == "wall") listOf(obstruction) else emptyList(), searches::add)
            }.reason shouldBe "autonomy-no-path"
            searches.single().outcome shouldBe "route-target-blocked"
            searches.single().expanded shouldBe 0
        } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
    }

    test("a farm apron does not open the rest of its empty merged reservation") {
        CivsWorksiteLayouts.installCatalog(mapOf(COMPOSITE_FARM to compositeFarmSite()))
        try {
            val original = terraceCity()
            val building = compositeFarm(original)
            val hall = original.buildings.single().copy(anchor = CivPosition(-14, 64, 18),
                entrance = CivPosition(-14, 65, 18), bounds = CivBox(CivPosition(-20, 64, 10), CivPosition(-15, 70, 16)))
            val city = original.copy(buildings = listOf(hall))
            val surface = CivTerrainSurface(-64, 320, (-14..0).associate {
                civsTerrainKey(it, 18) to CivTerrainSurfaceColumn(64, true)
            })
            val searches = mutableListOf<CivRouteSearchStats>()
            shouldThrow<CivsRejection> { CivsAutonomousTerraform.connection(city, building, surface,
                CivAutonomousAccess(), emptyList(), searches::add) }.reason shouldBe "autonomy-no-path"
            searches.single().outcome shouldBe "route-disconnected"
            searches.single().expanded shouldBe 0
        } finally { CivsWorksiteLayouts.installCatalog(emptyMap()) }
    }

    for (barrier in listOf("nonnatural", "unloaded", "structure"))
    test("a complete horizontal field rejects a disconnected $barrier strip without expanding heights") {
        val city = terraceCity()
        val building = terraceBuilder(city)
        val surface = terraceSurface { _, _ -> 64 }.let { original -> original.copy(columns = original.columns.mapValues { (key, c) ->
            if (key.toInt() != 8) c else c.copy(natural = barrier != "nonnatural", loaded = barrier != "unloaded")
        }) }
        val obstacles = if (barrier == "structure") listOf(CivBox(CivPosition(-48, 64, 8), CivPosition(48, 70, 8))) else emptyList()
        val searches = mutableListOf<CivRouteSearchStats>()
        shouldThrow<CivsRejection> { CivsAutonomousTerraform.connection(city, building, surface,
            CivAutonomousAccess(), obstacles, searches::add) }.reason shouldBe "autonomy-no-path"
        searches.single().outcome shouldBe "route-disconnected"
        searches.single().expanded shouldBe 0
    }

    test("a source absent from the reverse field keeps the existing legal first step") {
        val city = terraceCity()
        val building = terraceBuilder(city)
        val surface = terraceSurface { _, _ -> 64 }.let {
            it.copy(columns = it.columns - civsTerrainKey(0, 0))
        }
        val searches = mutableListOf<CivRouteSearchStats>()
        val route = CivsAutonomousTerraform.connection(city, building, surface, CivAutonomousAccess(), emptyList(), searches::add)
        searches.map { it.outcome } shouldBe listOf("found")
        route.first() shouldBe city.buildings.single().entrance
        route.last() shouldBe building.entrance
        route.drop(1).all { surface.at(it.x, it.z)?.let { c -> c.loaded && c.natural } == true } shouldBe true
    }

    test("a shared street preference cannot open a protected or unloaded native barrier") {
        val city = terraceCity()
        val building = terraceBuilder(city)
        val street = CivRouteStreet(CivPosition(0, 65, 8), alongX = false)
        for (loaded in listOf(false, true)) {
            val surface = terraceSurface { _, _ -> 64 }.let { flat -> flat.copy(columns = flat.columns.mapValues { (key, column) ->
                if (key.toInt() == 8) column.copy(loaded = loaded, natural = !loaded) else column
            }) }
            val searches = mutableListOf<CivRouteSearchStats>()
            shouldThrow<CivsRejection> { CivsAutonomousTerraform.connection(city, building, surface,
                CivAutonomousAccess(), emptyList(), searches::add, streets = listOf(street))
            }.reason shouldBe "autonomy-no-path"
            searches.single().outcome shouldBe "route-disconnected"
            searches.single().expanded shouldBe 0
        }
    }

    test("an excluded source cannot jump to a component whose only first column has an unreachable grade") {
        val city = terraceCity()
        val building = terraceBuilder(city).copy(anchor = CivPosition(1, 67, 0), entrance = CivPosition(1, 68, 0),
            rotation = 90, bounds = CivBox(CivPosition(2, 67, -2), CivPosition(8, 75, 2)))
        val surface = CivTerrainSurface(-64, 320, mapOf(civsTerrainKey(1, 0) to CivTerrainSurfaceColumn(67, true)))
        val searches = mutableListOf<CivRouteSearchStats>()
        shouldThrow<CivsRejection> { CivsAutonomousTerraform.connection(city, building, surface,
            CivAutonomousAccess(), emptyList(), searches::add) }.reason shouldBe "autonomy-no-path"
        searches.single().outcome shouldBe "route-disconnected"
        searches.single().expanded shouldBe 0
    }

    test("route preparation checks each static public column once across candidate heights including blocked columns") {
        for (permitted in listOf(true, false)) {
            val original = terraceCity()
            val hall = original.buildings.single().copy(anchor = CivPosition(0, 0, 0), entrance = CivPosition(0, 1, 0),
                bounds = CivBox(CivPosition(-4, 0, 2), CivPosition(-2, 5, 5)), control = null)
            val city = original.copy(center = CivPosition(0, 0, 0), buildings = listOf(hall))
            var transitReads = 0
            val transit = object : AbstractList<CivPosition>() {
                override val size: Int get() = 1
                override fun get(index: Int): CivPosition {
                    require(index == 0)
                    transitReads++
                    return CivPosition(if (permitted) 1 else 7, 1, 0)
                }
            }
            val target = hall.copy(id = UUID(0, 194), type = BuildingType.GATE,
                anchor = CivPosition(1, 0, 0), entrance = CivPosition(3, 3, 0),
                bounds = CivBox(CivPosition(1, 0, 0), CivPosition(1, 5, 0)), transit = transit)
            val surface = CivTerrainSurface(-64, 320, (0..3).associate { x ->
                civsTerrainKey(x, 0) to CivTerrainSurfaceColumn((x - 1).coerceAtLeast(0), natural = true)
            })
            val searches = mutableListOf<CivRouteSearchStats>()
            if (permitted) {
                val route = CivsAutonomousTerraform.connection(city, target, surface, CivAutonomousAccess(), emptyList(), searches::add)
                route shouldBe listOf(CivPosition(0, 1, 0), CivPosition(1, 1, 0), CivPosition(2, 2, 0), CivPosition(3, 3, 0))
                // A one-column-wide corridor has no alternate horizontal arrival: extra states are different heights.
                (searches.single().enqueued > route.size) shouldBe true
            } else shouldThrow<CivsRejection> {
                CivsAutonomousTerraform.connection(city, target, surface, CivAutonomousAccess(), emptyList(), searches::add)
            }.reason shouldBe "autonomy-no-path"
            transitReads shouldBe 1
        }
    }

    test("connection shares one detached surface preparation across source depots") {
        val original = terraceCity()
        val hall = original.buildings.single().copy(anchor = CivPosition(-3, 0, 0),
            entrance = CivPosition(-3, 1, 0), phase = BuildingPhase.ACTIVE, control = null,
            bounds = CivBox(CivPosition(-5, 0, 2), CivPosition(-1, 5, 5)))
        val depot = hall.copy(id = UUID(0, 193), type = BuildingType.WAREHOUSE,
            anchor = CivPosition(8, 0, 0), entrance = CivPosition(8, 1, 0),
            bounds = CivBox(CivPosition(7, 0, 2), CivPosition(10, 5, 5)))
        val city = original.copy(center = CivPosition(0, 0, 0), buildings = listOf(hall, depot))
        val target = hall.copy(id = UUID(0, 194), type = BuildingType.GATE,
            anchor = CivPosition(0, 0, 0), entrance = CivPosition(0, 1, 0),
            bounds = CivBox(CivPosition(-1, 0, -4), CivPosition(1, 5, -2)))
        val cells = ((-4..-2) + (0..8)).associate {
            civsTerrainKey(it, 0) to CivTerrainSurfaceColumn(0, natural = true)
        }
        var fullSurfaceIterations = 0
        val columns = object : AbstractMap<Long, CivTerrainSurfaceColumn>() {
            override val entries: Set<Map.Entry<Long, CivTerrainSurfaceColumn>>
                get() = object : AbstractSet<Map.Entry<Long, CivTerrainSurfaceColumn>>() {
                    override val size: Int get() = cells.size
                    override fun iterator(): Iterator<Map.Entry<Long, CivTerrainSurfaceColumn>> {
                        fullSurfaceIterations++
                        return cells.entries.iterator()
                    }
                }
        }
        val searches = mutableListOf<CivRouteSearchStats>()
        val route = CivsAutonomousTerraform.connection(city, target, CivTerrainSurface(-64, 320, columns),
            CivAutonomousAccess(), emptyList(), searches::add)
        route shouldBe (8 downTo 0).map { CivPosition(it, 1, 0) }
        searches.map { it.outcome } shouldBe listOf("route-disconnected", "found")
        searches.first().expanded shouldBe 0
        fullSurfaceIterations shouldBe 1
    }

    test("route node hashing avoids repeated equality probes for graded coordinate collisions") {
        val nodes = buildList {
            for (x in -16..15) for (z in -15..15) for (rise in -4..4)
                for (direction in 0..3) for (climbed in listOf(false, true))
                    add(Node(x, 64 + rise, z - 31 * rise, direction, climbed))
        }
        nodes.size shouldBe 71_424
        nodes.toSet().size shouldBe nodes.size
        val a = Node(-7, 64, -33, 1, false)
        val b = a.copy(y = a.y + 1, z = a.z - 31)
        (a == b) shouldBe false
        legacyTerraformNodeHash(a) shouldBe legacyTerraformNodeHash(b)
        fun probes(legacy: Boolean): Long {
            val counter = longArrayOf(0)
            val table = HashMap<TerraformNodeProbe, Int>(nodes.size * 2)
            nodes.forEachIndexed { index, node -> table[TerraformNodeProbe(node, legacy, counter)] = index }
            counter[0] = 0
            nodes.forEachIndexed { index, node ->
                table[TerraformNodeProbe(node.copy(), legacy, counter)] shouldBe index
            }
            return counter[0]
        }
        val legacy = probes(true)
        val current = probes(false)
        withClue("equality probes: legacy=$legacy current=$current nodes=${nodes.size}") {
            (current * 2 < legacy) shouldBe true
        }
    }

    test("route node identity retains every full integer and arrival history field") {
        val edge = Node(Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, -1, false)
        val nodes = listOf(edge, edge.copy(x = Int.MAX_VALUE), edge.copy(y = Int.MIN_VALUE),
            edge.copy(z = Int.MAX_VALUE), edge.copy(direction = 3), edge.copy(climbed = true))
        val table = nodes.withIndex().associate { it.value to it.index }
        table.size shouldBe nodes.size
        nodes.forEachIndexed { index, node -> table[node.copy()] shouldBe index }
    }

    test("a negative retained graded corridor keeps its exact route and search counters") {
        val original = terraceCity()
        val start = CivPosition(-160, 1, -320)
        val points = (0..40).map { step -> start.copy(x = start.x + step,
            y = start.y + when { step >= 36 -> 2; step >= 31 -> 1; else -> 0 }) }
        val hall = original.buildings.single().copy(anchor = start.copy(y = 0), entrance = start,
            bounds = CivBox(start.copy(x = start.x - 4, y = 0, z = start.z - 8),
                start.copy(x = start.x + 4, y = 6, z = start.z - 1)), control = null)
        val city = original.copy(center = start.copy(y = 0), buildings = listOf(hall))
        val target = hall.copy(id = UUID(0, 192), type = BuildingType.GATE,
            anchor = points.last().copy(y = points.last().y - 1), entrance = points.last(),
            bounds = CivBox(points.last().copy(x = points.last().x + 1, y = 2),
                points.last().copy(x = points.last().x + 1, y = 6)), phase = BuildingPhase.CONSTRUCTING)
        val surface = CivTerrainSurface(-64, 320, points.associate {
            civsTerrainKey(it.x, it.z) to CivTerrainSurfaceColumn(it.y - 1, natural = false)
        })
        val searches = mutableListOf<CivRouteSearchStats>()
        val route = CivsAutonomousTerraform.connection(city, target, surface,
            CivAutonomousAccess(routes = mapOf(hall.id to points)), emptyList(), searches::add)
        route shouldBe points
        searches.single() shouldBe CivRouteSearchStats(41, 41, 1, 41,
            searches.single().elapsedMillis, "found", points.first(), points.last())
    }

    test("reserved optional shoulders never omit mandatory centerline body or apron guards") {
        val city = terraceCity()
        val building = terraceBuilder(city)
        val option = CivTerraformOption(building, 0)
        val surface = terraceSurface { _, _ -> 64 }
        val baseline = CivsAutonomousTerraform.draft(city, option, surface)
        val targets = listOf(baseline.route[4], building.bounds.min.copy(y = building.anchor.y),
            building.entrance.copy(x = building.entrance.x + 1))
        val reserved = targets.mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }
        val guarded = CivsAutonomousTerraform.draft(city, option, surface,
            reservedRoute = baseline.route, reservedShoulders = reserved)
        targets.forEach { feet ->
            val column = guarded.columns.single { it.x == feet.x && it.z == feet.z }
            column shouldBe baseline.columns.single { it.x == feet.x && it.z == feet.z }
            column.preserve shouldBe false
            val position = CivPosition(column.x, column.groundY, column.z)
            val cells = terraceCapture(guarded, surface).toMutableMap()
            cells[position] = CivTerraformCell(position, "minecraft:coal_ore", safe = false,
                solid = true, passable = false, supportSafe = false)
            val rejection = shouldThrow<CivsRejection> { CivsAutonomousTerraform.materialize(guarded, cells) }
            rejection.reason shouldBe "autonomy-terrain"
            rejection.values["x"] shouldBe "${position.x}"
            rejection.values["y"] shouldBe "${position.y}"
            rejection.values["z"] shouldBe "${position.z}"
        }
        listOf(targets.first(), targets.last()).forEach { feet ->
            val retained = CivAutonomousAccess(routes = mapOf(city.buildings.single().id to listOf(feet.copy(y = feet.y + 1))))
            val rejection = shouldThrow<CivsRejection> {
                CivsAutonomousTerraform.draft(city, option, surface, retained,
                    reservedRoute = baseline.route, reservedShoulders = reserved)
            }
            rejection.reason shouldBe "autonomy-no-path"
            rejection.values["stage"] shouldBe "retained-grade"
        }
    }

    test("a mild graded detour around a long barrier stays within a small expanded-state budget") {
        val fixture = mildBarrierFixture()
        val searches = mutableListOf<CivRouteSearchStats>()
        val route = CivsAutonomousTerraform.connection(fixture.city, fixture.target, fixture.surface,
            CivAutonomousAccess(), listOf(fixture.barrier), searches::add)
        checkMildBarrierRoute(fixture, route)
        searches.single().outcome shouldBe "found"
        withClue(searches.single()) { (searches.single().expanded <= 4_096) shouldBe true }
    }

    test("housing can borrow a paid road at its grade without raising its shoulder or building over it") {
        val city = terraceCity()
        val building = terraceBuilder(city)
        val option = CivTerraformOption(building, 0)
        val surface = terraceSurface { _, _ -> 64 }
        val baseline = CivsAutonomousTerraform.draft(city, option, surface)
        val lane = baseline.route[4]
        val shoulder = CivsAutonomousGeometry.approach(building).first {
            it !in baseline.route && !CivsAutonomousGeometry.horizontalContains(building.bounds, it)
        }
        val road = setOf(civsTerrainKey(lane.x, lane.z), civsTerrainKey(shoulder.x, shoulder.z))
        val lower = surface.copy(columns = surface.columns + (civsTerrainKey(shoulder.x, shoulder.z) to
            CivTerrainSurfaceColumn(62, natural = true)))
        val draft = CivsAutonomousTerraform.draft(city, option, lower, reservedRoute = baseline.route, protectedRoadColumns = road)
        draft.route shouldBe baseline.route
        draft.columns.single { it.x == lane.x && it.z == lane.z }.preserve shouldBe true
        draft.columns.any { it.x == shoulder.x && it.z == shoulder.z } shouldBe false
        val capture = terraceCapture(draft, lower)
        CivsAutonomousTerraform.materialize(draft, capture).blocks.any {
            civsTerrainKey(it.position.x, it.position.z) in road
        } shouldBe false
        val obstruction = lane.copy(y = lane.y + 1)
        shouldThrow<CivsRejection> {
            CivsAutonomousTerraform.materialize(draft, capture + (obstruction to
                CivTerraformCell(obstruction, "minecraft:stone", safe = false, solid = true, passable = false)))
        }.reason shouldBe "autonomy-no-path"
        shouldThrow<CivsRejection> {
            CivsAutonomousTerraform.draft(city, option, lower, reservedRoute = baseline.route,
                protectedRoadColumns = road + civsTerrainKey(building.bounds.min.x, building.bounds.min.z))
        }.reason shouldBe "building-overlap"
    }

    test("collision heavy translated height fields preserve the exact route and search counters including zero floors") {
        val fixture = mildBarrierFixture()
        val baselineSearches = mutableListOf<CivRouteSearchStats>()
        val baseline = CivsAutonomousTerraform.connection(fixture.city, fixture.target, fixture.surface,
            CivAutonomousAccess(), listOf(fixture.barrier), baselineSearches::add)
        checkMildBarrierRoute(fixture, baseline)
        for ((offset, vertical) in listOf(128 to 0, -128 to -64)) {
            fun translate(p: CivPosition) = p.copy(x = p.x + offset, y = p.y + vertical, z = p.z + offset)
            fun translateBox(box: CivBox) = CivBox(translate(box.min), translate(box.max))
            fun translateBuilding(b: CivBuilding) = b.copy(anchor = translate(b.anchor), bounds = translateBox(b.bounds),
                entrance = translate(b.entrance), control = b.control?.let { translate(it) })
            val city = fixture.city.copy(center = translate(fixture.city.center),
                buildings = fixture.city.buildings.map { translateBuilding(it) })
            val surface = CivTerrainSurface(-64 + vertical, 320 + vertical, buildMap {
                for (x in -128..127) for (z in -128..127) put(civsTerrainKey(x + offset, z + offset),
                    CivTerrainSurfaceColumn(64 + vertical + (x.coerceAtLeast(0) / 4).coerceAtMost(2), true))
            })
            surface.columns.size shouldBe CivsAutonomousTerraform.MAXIMUM_SURFACE_COLUMNS
            // Both quadrants contain 65,536 columns but only 256 distinct ordinary Long hash codes.
            surface.columns.keys.map { it.hashCode() }.distinct().size shouldBe 256
            val searches = mutableListOf<CivRouteSearchStats>()
            val route = CivsAutonomousTerraform.connection(city, translateBuilding(fixture.target), surface,
                CivAutonomousAccess(), listOf(translateBox(fixture.barrier)), searches::add)
            route shouldBe baseline.map { translate(it) }
            route.map { civsTerrainKey(it.x, it.z) }.distinct().size shouldBe route.size
            val expected = baselineSearches.single()
            searches.single() shouldBe expected.copy(elapsedMillis = searches.single().elapsedMillis,
                from = translate(expected.from), to = translate(expected.to))
        }
    }

    test("an oversized height field retains the original bounded search instead of truncating its route domain") {
        val fixture = mildBarrierFixture()
        val smallSearches = mutableListOf<CivRouteSearchStats>()
        val small = CivsAutonomousTerraform.connection(fixture.city, fixture.target, fixture.surface,
            CivAutonomousAccess(), listOf(fixture.barrier), smallSearches::add)
        checkMildBarrierRoute(fixture, small)
        val large = fixture.surface.copy(columns = buildMap {
            for (x in -128..128) for (z in -128..128)
                put(civsTerrainKey(x, z), CivTerrainSurfaceColumn(64 + (x.coerceAtLeast(0) / 4).coerceAtMost(2), true))
        })
        large.columns.size shouldBe 66_049 // Outside-city padding must trigger the bounded-precomputation fallback.
        val largeSearches = mutableListOf<CivRouteSearchStats>()
        val route = CivsAutonomousTerraform.connection(fixture.city, fixture.target, large,
            CivAutonomousAccess(), listOf(fixture.barrier), largeSearches::add)
        checkMildBarrierRoute(fixture.copy(surface = large), route)
        largeSearches.single().outcome shouldBe "found"
        withClue("small=${smallSearches.single()} large=${largeSearches.single()}") {
            (largeSearches.single().expanded > smallSearches.single().expanded) shouldBe true
            (largeSearches.single().expanded <= CivsAutonomousTerraform.MAXIMUM_ROUTE_NODES) shouldBe true
        }
    }

    test("a permanently blocked connection target is rejected without expanding the route search") {
        val city = terraceCity()
        val building = terraceBuilder(city)
        val surface = terraceSurface { _, _ -> 64 }.let { original -> original.copy(columns =
            original.columns + (civsTerrainKey(building.entrance.x, building.entrance.z) to
                CivTerrainSurfaceColumn(64, natural = false))) }
        val searches = mutableListOf<CivRouteSearchStats>()
        shouldThrow<CivsRejection> {
            CivsAutonomousTerraform.connection(city, building, surface, CivAutonomousAccess(), emptyList(), searches::add)
        }.reason shouldBe "autonomy-no-path"
        searches.single().outcome shouldBe "route-target-blocked"
        searches.single().expanded shouldBe 0
    }

    test("a matching retained standing height still admits a nonnatural connection target") {
        val city = terraceCity()
        val building = terraceBuilder(city)
        val surface = terraceSurface { _, _ -> 64 }.let { original -> original.copy(columns =
            original.columns + (civsTerrainKey(building.entrance.x, building.entrance.z) to
                CivTerrainSurfaceColumn(64, natural = false))) }
        val access = CivAutonomousAccess(mapOf(city.buildings.single().id to listOf(building.entrance)))
        val searches = mutableListOf<CivRouteSearchStats>()
        val route = CivsAutonomousTerraform.connection(city, building, surface, access, emptyList(), searches::add)
        route.first() shouldBe city.buildings.single().entrance
        route.last() shouldBe building.entrance
        searches.single().outcome shouldBe "found"
    }

    test("an existing zero length connection retains its exception to target admission") {
        val city = terraceCity()
        val building = city.buildings.single()
        val searches = mutableListOf<CivRouteSearchStats>()
        val route = CivsAutonomousTerraform.connection(city, building, CivTerrainSurface(-64, 320, emptyMap()),
            CivAutonomousAccess(), emptyList(), searches::add)
        route shouldBe listOf(building.entrance)
        searches.single().outcome shouldBe "found"
        searches.single().expanded shouldBe 0
    }

    test("a graded junction retains its viable later arrival without reusing columns") {
        val original = terraceCity()
        val start = original.buildings.single().entrance
        val junction = CivPosition(2, 66, 4)
        val shortcut = listOf(start, CivPosition(0, 65, 1), CivPosition(0, 65, 2),
            CivPosition(1, 65, 2), CivPosition(2, 65, 2), CivPosition(2, 65, 3), junction)
        val detour = listOf(CivPosition(-1, 65, 0), CivPosition(-2, 65, 0), CivPosition(-2, 65, 1),
            CivPosition(-2, 66, 2), CivPosition(-2, 66, 3), CivPosition(-2, 66, 4),
            CivPosition(-1, 66, 4), CivPosition(0, 66, 4), CivPosition(1, 66, 4))
        val finish = listOf(CivPosition(3, 67, 4), CivPosition(4, 67, 4))
        val feet = (shortcut + detour + finish).distinct()
        val roads = feet.map { point -> original.buildings.single().copy(id = UUID.randomUUID(), type = BuildingType.ROAD,
            anchor = point.copy(y = point.y - 1), entrance = point,
            bounds = CivBox(point.copy(y = point.y - 1), point.copy(y = point.y - 1)),
            phase = BuildingPhase.ACTIVE, control = null, transit = listOf(point)) }
        val city = original.copy(buildings = original.buildings + roads)
        val target = roads.last().copy(id = UUID.randomUUID(), type = BuildingType.GATE, phase = BuildingPhase.CONSTRUCTING)
        val surface = CivTerrainSurface(-64, 320, feet.associate {
            civsTerrainKey(it.x, it.z) to CivTerrainSurfaceColumn(it.y - 1, true)
        })
        // The shorter arrival climbs north into the junction and has no straight landing there.
        // The longer western arrival stays level before the final eastward climb.
        val route = CivsAutonomousTerraform.connection(city, target, surface, CivAutonomousAccess(), emptyList())
        route shouldBe listOf(start) + detour + junction + finish
        route.map { civsTerrainKey(it.x, it.z) }.distinct().size shouldBe route.size
    }

    test("a new north branch beyond remote preferred roads fits the unchanged search budget") {
        val original = terraceCity()
        val east = original.buildings.single().copy(id = UUID.randomUUID(), type = BuildingType.FORESTER,
            anchor = CivPosition(80, 64, 0), entrance = CivPosition(80, 65, 0), rotation = 90,
            bounds = CivBox(CivPosition(81, 64, -5), CivPosition(96, 74, 9)), control = null)
        val south = east.copy(id = UUID.randomUUID(), type = BuildingType.QUARRY,
            anchor = CivPosition(0, 64, 80), entrance = CivPosition(0, 65, 80), rotation = 180,
            bounds = CivBox(CivPosition(-10, 58, 81), CivPosition(10, 81, 102)))
        val sides = listOf(-11, 11).map { x -> east.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE,
            anchor = CivPosition(x, 64, -4), entrance = CivPosition(x, 65, -4), rotation = 0,
            bounds = CivBox(CivPosition(x - 4, 64, -13), CivPosition(x + 4, 76, -5))) }
        val city = original.copy(radius = 128, buildings = original.buildings + east + south + sides)
        val routes = mapOf(east.id to (0..80).map { CivPosition(it, 65, 0) },
            south.id to (0..80).map { CivPosition(0, 65, it) })
        val access = CivAutonomousAccess(routes)
        val building = east.copy(id = UUID.randomUUID(), type = BuildingType.HOUSE,
            anchor = CivPosition(0, 64, -80), entrance = CivPosition(0, 65, -80), rotation = 0,
            bounds = CivBox(CivPosition(-4, 64, -89), CivPosition(4, 76, -81)))
        val surface = CivTerrainSurface(-64, 320, buildMap {
            for (x in -127..127) for (z in -127..127)
                put(civsTerrainKey(x, z), CivTerrainSurfaceColumn(64, true))
        })
        val searches = mutableListOf<CivRouteSearchStats>()
        val draft = CivsAutonomousTerraform.draft(city, CivTerraformOption(building, 0), surface, access,
            preferredRoads = routes.values.flatten().mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }, inspectRoute = searches::add)
        draft.route.last() shouldBe building.entrance
        searches.single().outcome shouldBe "found"
        (searches.single().expanded < CivsAutonomousTerraform.MAXIMUM_ROUTE_NODES) shouldBe true
        val protected = access.standing(city, building.id)
        draft.route.filter { civsTerrainKey(it.x, it.z) in protected }.all {
            it == protected.getValue(civsTerrainKey(it.x, it.z))
        } shouldBe true
        city.buildings shouldBe original.buildings + east + south + sides
    }

    test("grading rechecks three dimensional depot reach before reserving a terrain height") {
        val city = terraceCity().copy(radius = 128)
        val entry = CivPosition(80, 65, 36)
        val building = CivBuilding(UUID.randomUUID(), BuildingType.FORESTER, 1, entry.copy(y = 64), 90, "fixture",
            CivBox(CivPosition(81, 64, 31), CivPosition(85, 72, 40)), entry, BuildingPhase.CONSTRUCTING)
        CivsRules.workRouteWithinReach(city, building.entrance) shouldBe true
        fun hill(ground: Int) = CivTerrainSurface(-64, 320, buildMap {
            for (x in building.bounds.min.x..building.bounds.max.x) for (z in building.bounds.min.z..building.bounds.max.z)
                put(civsTerrainKey(x, z), CivTerrainSurfaceColumn(ground, true))
        })
        val rejected = mutableListOf<String>()
        CivsAutonomousTerraform.options(city, listOf(CivsAutonomousCandidate(building)), hill(76),
            rejected = { reason, _ -> rejected += reason }) shouldBe emptyList()
        rejected shouldBe List(3) { "work-route-too-long" }
        val choices = CivsAutonomousTerraform.options(city, listOf(CivsAutonomousCandidate(building)), hill(74))
        choices.map { it.building.anchor.y } shouldBe listOf(70)
        choices.all { CivsRules.workRouteWithinReach(city, it.building.entrance) } shouldBe true
        city.buildings.size shouldBe 1
        building.anchor.y shouldBe 64
    }

    test("a parcel four blocks from a two block higher street can use a guarded side approach") {
        val original = terraceCity()
        val forester = original.buildings.single().copy(id = UUID.randomUUID(), type = BuildingType.FORESTER,
            anchor = CivPosition(20, 66, 0), entrance = CivPosition(20, 67, 0), rotation = 90,
            bounds = CivBox(CivPosition(21, 66, -3), CivPosition(30, 74, 3)), control = CivPosition(22, 67, 0))
        val city = original.copy(buildings = original.buildings + forester)
        val street = listOf(CivPosition(0, 65, 0), CivPosition(1, 65, 0), CivPosition(2, 66, 0)) +
            (3..20).map { CivPosition(it, 67, 0) }
        val access = CivAutonomousAccess(mapOf(forester.id to street))
        val originalBuilding = terraceBuilder(city)
        fun shift(p: CivPosition) = p.copy(x = p.x + 12, z = p.z - 14)
        val building = originalBuilding.copy(anchor = shift(originalBuilding.anchor), entrance = shift(originalBuilding.entrance),
            bounds = CivBox(shift(originalBuilding.bounds.min), shift(originalBuilding.bounds.max)),
            control = originalBuilding.control?.let(::shift))
        val protected = access.standing(city, building.id)
        val surface = terraceSurface { x, z -> protected[civsTerrainKey(x, z)]?.y?.minus(1) ?: 64 }
        val before = Gson().toJson(city to access)
        val searches = mutableListOf<CivRouteSearchStats>()
        val draft = CivsAutonomousTerraform.draft(city, CivTerraformOption(building, 0, streetFrontage = true), surface, access,
            preferredRoads = street.mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }, inspectRoute = searches::add)
        draft.route.last() shouldBe building.entrance
        draft.route.zipWithNext().all { (a, b) -> abs(a.x - b.x) + abs(a.z - b.z) == 1 && abs(a.y - b.y) <= 1 } shouldBe true
        val apron = CivsAutonomousGeometry.approach(building).mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }
        draft.route.filter { civsTerrainKey(it.x, it.z) in apron }.all { it.y == building.entrance.y } shouldBe true
        draft.route.filter { civsTerrainKey(it.x, it.z) in protected }.all {
            it == protected.getValue(civsTerrainKey(it.x, it.z))
        } shouldBe true
        searches.all { it.expanded <= CivsAutonomousTerraform.MAXIMUM_ROUTE_NODES } shouldBe true
        val cells = terraceCapture(draft, surface)
        val plan = CivsAutonomousTerraform.materialize(draft, cells)
        plan.blocks.none { civsTerrainKey(it.position.x, it.position.z) in protected } shouldBe true
        checkTerrace(plan, draft, cells)
        Gson().toJson(city to access) shouldBe before
    }

    test("a new hillside route reserves a flat turn between flights that walk in both directions") {
        val city = terraceCity()
        val original = terraceBuilder(city, 70)
        fun east(p: CivPosition) = p.copy(x = p.x + 8)
        val building = original.copy(anchor = east(original.anchor), entrance = east(original.entrance),
            bounds = CivBox(east(original.bounds.min), east(original.bounds.max)), control = original.control?.let(::east))
        val surface = terraceSurface { _, z -> 64 + (z.coerceAtLeast(0) / 3).coerceAtMost(6) }
        val route = CivsAutonomousTerraform.draft(city, CivTerraformOption(building, 0), surface).route
        CivsSettlementLayout.turnFloors(route).isNotEmpty() shouldBe true
        (route.maxOf { it.y } > route.minOf { it.y }) shouldBe true
        val profile = CivsAutonomousTerraform.roadProfile(route)
        val heights = profile.mapValues { it.value.y }
        val turns = CivsSettlementLayout.turnFloors(route)
        val states = profile.values.associateWith { p -> CivsSettlementLayout.pathPavement(p, heights, city.style,
            atTurn = p in turns, downhill = CivsSettlementLayout.routeDownhill(p, route)) }
        MockBukkitTestRuntime.open().use { paper ->
            val world = CivsWorksiteWorldFixture("planned-road-turn", collisionForData = { data ->
                if (CivsRoadMaintenance.surface(data.asString)) roadUpkeepBoxes(data).map { it.at(CivPosition(0, 0, 0)) } else null
            }).also(paper.server::addWorld)
            profile.values.forEach { p -> world.loadChunk(p.x shr 4, p.z shr 4) }
            states.forEach { (p, data) -> world.getBlockAt(p.x, p.y, p.z).setBlockData(Bukkit.createBlockData(data), false) }
            (roadHalfStepWalk(world, route) != null) shouldBe true
            (roadHalfStepWalk(world, route.reversed()) != null) shouldBe true
        }
    }

    for ((name, delta) in listOf("short-hump" to 2, "short-hollow" to -2))
    test("$name is graded to nearby ground instead of copying a short detour in height") {
        val city = terraceCity()
        val building = terraceBuilder(city)
        val surface = terraceSurface { x, z -> if (delta < 0) {
            if (z in 6..8) 64 + delta else 64
        } else 64 + when {
            abs(x) <= 1 && z in 6..8 -> 2
            abs(x) <= 2 && z in 5..9 && abs(x) + abs(z - 7) <= 3 -> 1
            else -> 0
        } }
        val draft = CivsAutonomousTerraform.draft(city, CivTerraformOption(building, 0), surface)
        val cells = terraceCapture(draft, surface)
        val plan = CivsAutonomousTerraform.materialize(draft, cells)
        plan.route.map { it.y }.distinct() shouldBe listOf(65)
        plan.route.map { it.x }.distinct() shouldBe listOf(0)
        plan.preparedRouteSurfaces().map { it.position }.toSet() shouldBe plan.route.drop(1)
            .map { it.copy(y = it.y - 1) }.toSet()
        val foundation = CivPlanBlock(CivPosition(2, 64, 22), "minecraft:air", "minecraft:coarse_dirt")
        plan.copy(blocks = plan.blocks + foundation).preparedRouteSurfaces() shouldBe plan.preparedRouteSurfaces()
        draft.columns.filter { it.z in 6..8 && it.x in -1..1 }.all { it.groundY == 64 } shouldBe true
        checkTerrace(plan, draft, cells)
        if (delta > 0) {
            (plan.cutCount > 0) shouldBe true
            val after = cells.mapValues { it.value.data } + plan.blocks.associate { it.position to it.after }
            for (x in -2..2) for (z in 5..9) {
                if (surface.at(x, z)!!.groundY <= 64) continue
                draft.columns.single { it.x == x && it.z == z }.groundY shouldBe 64
                after.getValue(CivPosition(x, 65, z)) shouldBe "minecraft:air"
                after.getValue(CivPosition(x, 64, z)) shouldBe
                    if (x == 0) "minecraft:coarse_dirt" else "minecraft:grass_block[snowy=false]"
            }
        } else {
            (plan.fillCount > 0) shouldBe true
            plan.blocks.filter { it.position.y == 64 && it.position.z in 6..8 }.forEach { block ->
                block.after shouldBe if (block.position.x == 0) "minecraft:coarse_dirt" else "minecraft:grass_block[snowy=false]"
            }
        }
        exportRoadSection(name, surface, plan.route, plan.blocks)
    }

    test("a short transverse ridge has a level verge and one block banks without widening the cut beyond radius three") {
        val city = terraceCity()
        val building = terraceBuilder(city)
        val surface = terraceSurface { _, z -> if (z in 6..8) 66 else 64 }
        val draft = CivsAutonomousTerraform.draft(city, CivTerraformOption(building, 0), surface)
        val cells = terraceCapture(draft, surface)
        val plan = CivsAutonomousTerraform.materialize(draft, cells)
        plan.route.map { it.y }.distinct() shouldBe listOf(65)
        for (side in listOf(-1, 1)) for (z in 6..8) {
            draft.columns.single { it.x == side * 2 && it.z == z }.groundY shouldBe 64
            draft.columns.single { it.x == side * 3 && it.z == z }.groundY shouldBe 65
            draft.columns.none { it.x == side * 4 && it.z == z } shouldBe true
        }
        plan.blocks.filter { it.position.z in 6..8 && abs(it.position.x) >= 2 }.all {
            abs(it.position.x) <= 3 && it.before != "minecraft:air"
        } shouldBe true
        checkTerrace(plan, draft, cells)
        exportRoadSection("short-ridge", surface, plan.route, plan.blocks)
    }

    for ((name, span, height) in listOf(Triple("long", 6..11, 2), Triple("tall", 6..8, 4)))
    test("a $name ridge keeps its terrain outside the original three wide corridor") {
        val city = terraceCity()
        val building = terraceBuilder(city)
        val surface = terraceSurface { _, z -> if (z in span) 64 + height else 64 }
        val draft = CivsAutonomousTerraform.draft(city, CivTerraformOption(building, 0), surface)
        draft.columns.filter { it.z in span }.all { abs(it.x) <= 1 } shouldBe true
    }

    test("cut feathering preserves unavailable terrain accepted roads and obstacles and rechecks exact native cells") {
        val city = terraceCity()
        val building = terraceBuilder(city)
        val unavailable = setOf(civsTerrainKey(-2, 6), civsTerrainKey(2, 6))
        val surface = terraceSurface { _, z -> if (z in 6..8) 66 else 64 }.let { s ->
            s.copy(columns = s.columns.mapValues { (key, column) -> when (key) {
                civsTerrainKey(-2, 6) -> column.copy(natural = false)
                civsTerrainKey(2, 6) -> column.copy(loaded = false)
                else -> column
            } })
        }
        val retained = CivPosition(2, 67, 7)
        val access = CivAutonomousAccess(mapOf(city.buildings.single().id to listOf(retained)))
        val obstacle = CivBox(CivPosition(-3, 64, 7), CivPosition(-2, 68, 7))
        val draft = CivsAutonomousTerraform.draft(city, CivTerraformOption(building, 0), surface, access, listOf(obstacle))
        draft.columns.none { civsTerrainKey(it.x, it.z) in unavailable || it.x == retained.x && it.z == retained.z ||
            CivsAutonomousGeometry.horizontalContains(obstacle, CivPosition(it.x, it.groundY, it.z)) } shouldBe true
        val cells = terraceCapture(draft, surface).toMutableMap()
        val position = CivPosition(-2, 65, 8)
        cells[position] = CivTerraformCell(position, "minecraft:bricks", false, true, false)
        shouldThrow<CivsRejection> { CivsAutonomousTerraform.materialize(draft, cells) }.reason shouldBe "autonomy-terrain"
    }

    test("a sustained hillside keeps a connected grade to its high destination") {
        val city = terraceCity()
        val building = terraceBuilder(city, 70)
        val surface = terraceSurface { _, z -> 64 + (z.coerceAtLeast(0) / 3).coerceAtMost(6) }
        val draft = CivsAutonomousTerraform.draft(city, CivTerraformOption(building, 0), surface)
        val cells = terraceCapture(draft, surface)
        val plan = CivsAutonomousTerraform.materialize(draft, cells)
        plan.route.first().y shouldBe 65
        plan.route.last().y shouldBe 71
        plan.route.zipWithNext().all { (a, b) -> b.y - a.y in 0..1 } shouldBe true
        checkTerrace(plan, draft, cells)
        exportRoadSection("sustained-slope", surface, plan.route, plan.blocks)
    }

    test("shoulder grade follows the nearest centre and equal distance never adds a high curb") {
        val route = listOf(CivPosition(0, 65, 0), CivPosition(0, 66, 1), CivPosition(0, 66, 2),
            CivPosition(1, 65, 2), CivPosition(2, 65, 2))
        val profile = CivsAutonomousTerraform.roadProfile(route)
        profile shouldBe CivsAutonomousTerraform.roadProfile(route.reversed())
        route.forEach { profile.getValue(civsTerrainKey(it.x, it.z)).y shouldBe it.y - 1 }
        profile.getValue(civsTerrainKey(-1, 1)).y shouldBe 65
        profile.getValue(civsTerrainKey(1, 1)).y shouldBe 64
    }

    test("single floor membership matches the full road profile including ties and integer boundaries") {
        val cases = listOf(
            "empty" to emptyList(),
            "single" to listOf(CivPosition(0, 65, 0)),
            "straight" to listOf(CivPosition(0, 65, 0), CivPosition(0, 66, 1), CivPosition(0, 67, 2)),
            "bend" to listOf(CivPosition(0, 65, 0), CivPosition(0, 66, 1), CivPosition(0, 66, 2),
                CivPosition(1, 65, 2), CivPosition(2, 65, 2)),
            "nearest before lower" to listOf(CivPosition(0, 80, 0), CivPosition(1, 20, 0)),
            "equal distance lower grade" to listOf(CivPosition(-1, 90, 0), CivPosition(1, 65, 0)),
            "equal distance and grade" to listOf(CivPosition(-1, 65, 0), CivPosition(1, 65, 0)),
            "duplicate columns" to listOf(CivPosition(0, 65, 0), CivPosition(0, 64, 0),
                CivPosition(0, 64, 0), CivPosition(0, 66, 0)),
            "negative coordinates" to listOf(CivPosition(-7, 80, -3), CivPosition(-7, 79, -2), CivPosition(-6, 78, -2)),
            "integer boundaries" to listOf(CivPosition(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE),
                CivPosition(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE), CivPosition(Int.MIN_VALUE + 1, 0, Int.MAX_VALUE - 1)),
            "extreme height tie" to listOf(CivPosition(-1, Int.MAX_VALUE, 0), CivPosition(1, Int.MIN_VALUE, 0)),
        )
        for ((name, route) in cases) for (ordered in listOf(route, route.reversed())) {
            val profile = CivsAutonomousTerraform.roadProfile(ordered)
            for (point in ordered.ifEmpty { listOf(CivPosition(0, 0, 0)) })
                for (dx in -2..2) for (dz in -2..2) for (dy in -1..1) {
                    val floor = CivPosition(point.x + dx, point.y - 1 + dy, point.z + dz)
                    withClue("$name route=$ordered floor=$floor") {
                        CivsAutonomousTerraform.roadProfileContains(ordered, floor) shouldBe
                            (profile[civsTerrainKey(floor.x, floor.z)] == floor)
                    }
                }
        }
    }

    test("real 13 by 13 builder footprint receives supported cut and fill on a twelve block hillside") {
        val city = terraceCity()
        val building = terraceBuilder(city)
        val surface = terraceSurface { x, z -> 64 + (z.coerceAtLeast(0) / 3).coerceAtMost(12) + x.coerceIn(-6, 6) / 2 }
        val choices = CivsAutonomousTerraform.options(city, listOf(CivsAutonomousCandidate(building)), surface)
        choices.isNotEmpty() shouldBe true
        val draft = CivsAutonomousTerraform.draft(city, choices.first(), surface)
        val cells = terraceCapture(draft, surface)
        val plan = CivsAutonomousTerraform.materialize(draft, cells)
        (plan.cutCount > 0) shouldBe true
        (plan.fillCount > 0) shouldBe true
        plan.blocks.map { it.position }.distinct().size shouldBe plan.blocks.size
        plan.blocks.none { it.position.x == plan.route.first().x && it.position.z == plan.route.first().z } shouldBe true
        val control = requireNotNull(draft.building.control)
        cells.getValue(control).solid shouldBe true
        plan.blocks.none { it.position == control } shouldBe true
        checkTerrace(plan, draft, cells)
        // Replaying the journal consumes each exact original once, including terrain below the schematic.
        val replay = cells.mapValues { it.value.data }.toMutableMap()
        plan.blocks.forEach { cell -> replay[cell.position] shouldBe cell.before; replay[cell.position] = cell.after }
        plan.blocks.forEach { cell -> replay[cell.position] shouldBe cell.after }
    }

    test("an eighteen block escarpment gets a graded connected approach instead of a slope rejection") {
        val city = terraceCity()
        val building = terraceBuilder(city, 82)
        val surface = terraceSurface { _, z -> if (z >= 10) 82 else 64 }
        val fixed = CivsAutonomousTerraform.options(city, listOf(CivsAutonomousCandidate(building)), surface, fixed = true)
        fixed.size shouldBe 1
        val draft = CivsAutonomousTerraform.draft(city, fixed.single(), surface)
        val cells = terraceCapture(draft, surface)
        val plan = CivsAutonomousTerraform.materialize(draft, cells)
        plan.route.first() shouldBe city.buildings.single().entrance
        plan.route.last() shouldBe building.entrance
        (plan.route.size > 18) shouldBe true
        (plan.fillCount > 0) shouldBe true
        checkTerrace(plan, draft, cells)
    }

    test("pinned legacy SITE preserves its anchor its existing marker and initially walkable entrance") {
        val original = terraceCity()
        val hall = terraceBuilder(original, 72).copy(type = BuildingType.TOWN_HALL, phase = BuildingPhase.SITE)
        val city = original.copy(buildings = listOf(hall))
        val marker = requireNotNull(hall.control)
        val surface = terraceSurface { x, z -> when {
            x == hall.entrance.x && z == hall.entrance.z -> 72
            x == marker.x && z == marker.z -> marker.y
            else -> 70 + x.coerceIn(-6, 6) / 2
        } }.let { it.copy(columns = it.columns + (civsTerrainKey(marker.x, marker.z) to
            CivTerrainSurfaceColumn(marker.y, natural = false))) }
        val options = CivsAutonomousTerraform.options(city, listOf(CivsAutonomousCandidate(hall)), surface, fixed = true)
        options.single().building.anchor shouldBe hall.anchor
        val draft = CivsAutonomousTerraform.draft(city, options.single(), surface)
        val cells = terraceCapture(draft, surface).toMutableMap()
        cells[marker] = CivTerraformCell(marker, "minecraft:barrel[facing=north,open=false]", false, true, false)
        val plan = CivsAutonomousTerraform.materialize(draft, cells)
        plan.route shouldBe listOf(hall.entrance)
        plan.blocks.none { it.position == marker } shouldBe true
        plan.blocks.none { it.position.x == hall.entrance.x && it.position.z == hall.entrance.z } shouldBe true
        plan.groundY shouldBe 72
    }

    test("a range exceeding the finite cut fill allowance is rejected rather than excavated unboundedly") {
        val city = terraceCity()
        val b = terraceBuilder(city)
        val surface = terraceSurface { x, _ -> 64 + (x + 6).coerceIn(0, 12) * 5 }
        CivsAutonomousTerraform.options(city, listOf(CivsAutonomousCandidate(b)), surface) shouldBe emptyList()
    }

    test("a continuous unavailable water barrier is not manufactured into a bridge") {
        val city = terraceCity().copy(radius = 24)
        val b = terraceBuilder(city).copy(bounds = CivBox(CivPosition(-6, 60, 19), CivPosition(6, 76, 24)))
        val surface = terraceSurface { _, _ -> 64 }.let { s -> s.copy(columns = s.columns.mapValues { (key, c) ->
            if (key.toInt() == 10) c.copy(natural = false) else c
        }) }
        val option = CivTerraformOption(b, 0)
        shouldThrow<CivsRejection> { CivsAutonomousTerraform.draft(city, option, surface) }.reason shouldBe "autonomy-no-path"
    }

    test("protected rock base may remain but protected cells in an excavation or fill cannot be consumed") {
        val city = terraceCity()
        val b = terraceBuilder(city)
        val p = CivPosition(2, 64, 22)
        val column = CivTerraformColumn(p.x, p.z, 64, 63, 66)
        val draft = CivTerraformDraft(b, listOf(column), listOf(city.buildings.single().entrance, b.entrance), 64)
        val surface = terraceSurface { _, _ -> 66 }
        val cells = terraceCapture(draft, surface).toMutableMap()
        val wall = p.copy(y = 65)
        cells[wall] = CivTerraformCell(wall, "minecraft:bricks", false, true, false)
        shouldThrow<CivsRejection> { CivsAutonomousTerraform.materialize(draft, cells) }.reason shouldBe "autonomy-terrain"
        cells[wall] = CivTerraformCell(wall, "minecraft:water[level=0]", false, false, false)
        shouldThrow<CivsRejection> { CivsAutonomousTerraform.materialize(draft, cells) }.reason shouldBe "autonomy-terrain"
        cells[wall] = CivTerraformCell(wall, "minecraft:bedrock", false, true, false)
        shouldThrow<CivsRejection> { CivsAutonomousTerraform.materialize(draft, cells) }.reason shouldBe "autonomy-terrain"
    }

    test("foundation must find solid support within twenty four blocks and cannot float over a deep void") {
        val city = terraceCity()
        val b = terraceBuilder(city)
        val column = CivTerraformColumn(2, 22, 64, 59, 64)
        val draft = CivTerraformDraft(b, listOf(column), listOf(b.entrance), 64)
        val cells = (35..66).associate { y -> CivPosition(2, y, 22).let {
            it to CivTerraformCell(it, "minecraft:air", true, false, true)
        } }.toMutableMap()
        shouldThrow<CivsRejection> { CivsAutonomousTerraform.materialize(draft, cells) }.reason shouldBe "autonomy-foundation"
        val base = CivPosition(2, 35, 22)
        cells[base] = CivTerraformCell(base, "minecraft:bedrock", false, true, false)
        val plan = CivsAutonomousTerraform.materialize(draft, cells)
        plan.blocks.none { it.position == base } shouldBe true
        plan.blocks.first().position shouldBe base.copy(y = 36)
        plan.blocks.last().position shouldBe base.copy(y = 64)
    }

    for (ground in listOf(63, 64))
    test("manual leaf litter planning preserves the exact captured native state at ground $ground") {
        val building = terraceBuilder(terraceCity())
        val position = CivPosition(2, 64, 22)
        val litter = "minecraft:leaf_litter[facing=south,segment_amount=3]"
        val draft = CivTerraformDraft(building,
            listOf(CivTerraformColumn(2, 22, ground, 63, 64, supportY = 63)), emptyList(), ground)
        val cells = (63..66).associate { y -> CivPosition(2, y, 22).let {
            it to CivTerraformCell(it, when (y) { 63 -> "minecraft:dirt"; 64 -> litter; else -> "minecraft:air" },
                safe = true, solid = y == 63, passable = y != 63)
        } }
        CivsAutonomousTerraform.materialize(draft, cells).blocks shouldBe
            listOf(CivPlanBlock(position, litter, if (ground == 63) "minecraft:air" else "minecraft:dirt"))
    }

    for ((stage, y) in listOf("support" to 63, "fill" to 64))
    test("unsafe $stage materialization reports the exact captured block and position") {
        val city = terraceCity()
        val building = terraceBuilder(city)
        val draft = CivTerraformDraft(building, listOf(CivTerraformColumn(2, 22, 64, 63, 64, supportY = 63)),
            emptyList(), 64)
        val cells = (63..66).associate { height -> CivPosition(2, height, 22).let {
            it to CivTerraformCell(it, if (height == 63) "minecraft:stone" else "minecraft:air", true,
                height == 63, height != 63)
        } }.toMutableMap()
        val position = CivPosition(2, y, 22)
        val data = if (stage == "support") "minecraft:coal_ore" else "minecraft:water[level=0]"
        cells[position] = CivTerraformCell(position, data, false, stage == "support", false)
        val failure = shouldThrow<CivsRejection> { CivsAutonomousTerraform.materialize(draft, cells) }
        failure.reason shouldBe "autonomy-terrain"
        failure.values shouldBe mapOf("x" to "2", "y" to y.toString(), "z" to "22", "block" to data, "stage" to stage)
    }

    test("an obstructed source is rejected rather than clearing the worker standing column") {
        val city = terraceCity()
        val b = terraceBuilder(city)
        val entry = city.buildings.single().entrance
        val draft = CivTerraformDraft(b, listOf(CivTerraformColumn(entry.x, entry.z, 64, 64, 64, preserve = true)),
            listOf(entry), 64)
        val surface = terraceSurface { _, _ -> 64 }
        val cells = terraceCapture(draft, surface).toMutableMap()
        cells[entry] = CivTerraformCell(entry, "minecraft:stone", true, true, false)
        shouldThrow<CivsRejection> { CivsAutonomousTerraform.materialize(draft, cells) }.reason shouldBe "autonomy-no-path"
    }

    test("a retained road column replaced by water fails native support proof without creating a bridge") {
        val city = terraceCity()
        val entry = city.buildings.single().entrance
        val draft = CivTerraformDraft(terraceBuilder(city),
            listOf(CivTerraformColumn(entry.x, entry.z, 64, 64, 64, preserve = true)), listOf(entry), 64)
        val cells = terraceCapture(draft, terraceSurface { _, _ -> 64 }).toMutableMap()
        val floor = entry.copy(y = 64)
        cells[floor] = CivTerraformCell(floor, "minecraft:water[level=0]", false, false, false)
        shouldThrow<CivsRejection> { CivsAutonomousTerraform.materialize(draft, cells) }.reason shouldBe "autonomy-no-path"
    }

    test("candidate effort and route grading remain deterministic including negative world columns") {
        val city = terraceCity()
        val b = terraceBuilder(city)
        val surface = terraceSurface { x, z -> 64 + ((x + z) / 5).coerceIn(-8, 8) }
        val options = CivsAutonomousTerraform.options(city, listOf(CivsAutonomousCandidate(b)), surface)
        options shouldBe CivsAutonomousTerraform.options(city, listOf(CivsAutonomousCandidate(b)), surface)
        val draft = CivsAutonomousTerraform.draft(city, options.first(), surface)
        draft shouldBe CivsAutonomousTerraform.draft(city, options.first(), surface)
        listOf(civsTerrainKey(-1, -1), civsTerrainKey(-1, 1), civsTerrainKey(1, -1), civsTerrainKey(1, 1))
            .distinct().size shouldBe 4
    }

    test("verified tree crown is cleared while the terrace height follows soil rather than the canopy") {
        val city = terraceCity()
        val b = terraceBuilder(city)
        val surface = terraceSurface { _, _ -> 64 }.let { s -> s.copy(columns = s.columns +
            (civsTerrainKey(2, 22) to CivTerrainSurfaceColumn(64, natural = true, topY = 74))) }
        val option = CivsAutonomousTerraform.options(city, listOf(CivsAutonomousCandidate(b)), surface).first()
        option.building.anchor.y shouldBe 64
        val draft = CivsAutonomousTerraform.draft(city, option, surface)
        draft.columns.single { it.x == 2 && it.z == 22 }.surfaceY shouldBe 74
        val cells = terraceCapture(draft, surface).toMutableMap()
        for (y in 65..74) {
            val p = CivPosition(2, y, 22)
            cells[p] = CivTerraformCell(p, if (y < 73) "minecraft:oak_log[axis=y]" else
                "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]", true, true, false)
        }
        val plan = CivsAutonomousTerraform.materialize(draft, cells)
        plan.cutCount shouldBe 10
        plan.blocks.filter { it.position.y > 64 }.all { it.after == "minecraft:air" } shouldBe true
        plan.blocks.filter { it.position.y == 64 } shouldBe plan.preparedRouteSurfaces()
        plan.blocks.filter { it.position.x == 2 && it.position.z == 22 }.map { it.position.y } shouldBe (74 downTo 65).toList()
    }

    test("full-plan branches reuse a nearby paid road without changing its grade or crossing footprints") {
        val original = terraceCity()
        val existing = original.buildings.single().copy(id = UUID.randomUUID(), type = BuildingType.FORESTER,
            anchor = CivPosition(3, 64, 16), entrance = CivPosition(3, 65, 16), phase = BuildingPhase.ACTIVE, rotation = 90,
            bounds = CivBox(CivPosition(4, 64, 11), CivPosition(8, 71, 15)), control = CivPosition(5, 65, 14))
        val city = original.copy(buildings = original.buildings + existing)
        val road = (0..3).map { CivPosition(it, 65, 0) } + (1..16).map { CivPosition(3, 65, it) }
        val access = CivAutonomousAccess(mapOf(existing.id to road))
        val before = Gson().toJson(access)
        val building = terraceBuilder(city)
        val surface = terraceSurface { _, _ -> 64 }
        val draft = CivsAutonomousTerraform.draft(city, CivTerraformOption(building, 0), surface, access,
            preferredRoads = road.mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) })
        (draft.route.count { it in road } >= 15) shouldBe true
        draft.route.all { point -> city.buildings.none { CivsAutonomousGeometry.horizontalContains(it.bounds, point) } } shouldBe true
        val plan = CivsAutonomousTerraform.materialize(draft, terraceCapture(draft, surface))
        val paidColumns = road.mapTo(hashSetOf()) { civsTerrainKey(it.x, it.z) }
        plan.blocks.none { civsTerrainKey(it.position.x, it.position.z) in paidColumns } shouldBe true
        draft.route.filter { civsTerrainKey(it.x, it.z) in paidColumns }.forEach { it.y shouldBe 65 }
        Gson().toJson(access) shouldBe before
    }

    test("a new graded road cannot raise the neighbouring forester entrance or its approach") {
        val original = terraceCity()
        val forester = original.buildings.single().copy(id = UUID.randomUUID(), type = BuildingType.FORESTER,
            anchor = CivPosition(2, 64, 8), entrance = CivPosition(2, 65, 8), phase = BuildingPhase.ACTIVE,
            bounds = CivBox(CivPosition(1, 64, 4), CivPosition(4, 71, 7)), control = CivPosition(2, 65, 6))
        val city = original.copy(buildings = original.buildings + forester)
        val b = terraceBuilder(city, 68)
        val access = CivAutonomousAccess()
        val protected = access.standing(city, b.id)
        val surface = terraceSurface { x, z -> protected[civsTerrainKey(x, z)]?.y?.minus(1)
            ?: (64 + (z.coerceAtLeast(0) / 3).coerceAtMost(4)) }
        val draft = CivsAutonomousTerraform.draft(city, CivTerraformOption(b, 0), surface, access)
        val plan = CivsAutonomousTerraform.materialize(draft, terraceCapture(draft, surface))
        plan.route.last() shouldBe b.entrance
        (plan.fillCount + plan.cutCount > 0) shouldBe true
        plan.blocks.none { civsTerrainKey(it.position.x, it.position.z) in protected } shouldBe true
        draft.columns.filter { civsTerrainKey(it.x, it.z) in protected }.all { it.preserve } shouldBe true
        plan.route.filter { civsTerrainKey(it.x, it.z) in protected }.forEach { point ->
            point shouldBe protected.getValue(civsTerrainKey(point.x, point.z))
        }
    }

    test("an established road can be crossed only at its existing grade without altering its support") {
        val original = terraceCity()
        val forester = original.buildings.single().copy(id = UUID.randomUUID(), type = BuildingType.FORESTER,
            anchor = CivPosition(44, 64, 10), entrance = CivPosition(44, 65, 10), phase = BuildingPhase.ACTIVE,
            bounds = CivBox(CivPosition(45, 64, 7), CivPosition(48, 71, 13)), control = CivPosition(46, 65, 10),
            rotation = 90)
        val city = original.copy(buildings = original.buildings + forester)
        val b = terraceBuilder(city, 68)
        // This retained spine crosses the entire finite search field, so a detour cannot avoid it.
        val road = (-48..44).map { CivPosition(it, 65, 10) }
        val access = CivAutonomousAccess(mapOf(forester.id to road))
        val protected = access.standing(city, b.id)
        val surface = terraceSurface { x, z -> protected[civsTerrainKey(x, z)]?.y?.minus(1)
            ?: if (z > 10) 68 else 64 }
        val draft = CivsAutonomousTerraform.draft(city, CivTerraformOption(b, 0), surface, access)
        val plan = CivsAutonomousTerraform.materialize(draft, terraceCapture(draft, surface))
        plan.route.filter { it.z == 10 }.isNotEmpty() shouldBe true
        plan.route.filter { it.z == 10 }.all { it.y == 65 } shouldBe true
        plan.blocks.none { civsTerrainKey(it.position.x, it.position.z) in protected } shouldBe true
        checkTerrace(plan, draft, terraceCapture(draft, surface))
    }

    test("a footprint cannot consume an older retained road even outside every existing building") {
        val city = terraceCity()
        val b = terraceBuilder(city)
        val road = (0..24).map { CivPosition(0, 65, it) }
        val access = CivAutonomousAccess(mapOf(city.buildings.single().id to road))
        shouldThrow<CivsRejection> {
            CivsAutonomousTerraform.draft(city, CivTerraformOption(b, 0), terraceSurface { _, _ -> 64 }, access)
        }.reason shouldBe "building-overlap"
    }
})

/** Small source-derived sections for the existing voxel preview; no native-client claim. */
internal fun exportRoadSection(name: String, surface: CivTerrainSurface, route: List<CivPosition>,
    terrain: List<CivPlanBlock> = emptyList(), paving: List<CivSettlementBlock> = emptyList()) {
    val output = System.getenv("CIVS_ROAD_PREVIEW_OUT")?.let(Path::of) ?: return
    Files.createDirectories(output)
    val minX = route.minOf { it.x } - 5; val maxX = route.maxOf { it.x } + 5
    val minZ = route.minOf { it.z } - 5; val maxZ = route.maxOf { it.z } + 5
    val bottom = minOf(route.minOf { it.y - 1 },
        (minX..maxX).minOf { x -> (minZ..maxZ).minOf { z -> requireNotNull(surface.at(x, z)).groundY } }) - 2
    val before = linkedMapOf<CivPosition, String>()
    for (x in minX..maxX) for (z in minZ..maxZ) {
        val top = requireNotNull(surface.at(x, z)).groundY
        for (y in bottom..top) before[CivPosition(x, y, z)] = when {
            y == top -> "minecraft:grass_block[snowy=false]"
            y >= top - 2 -> "minecraft:dirt"
            else -> "minecraft:stone"
        }
    }
    val after = before.toMutableMap()
    (terrain.map { CivSettlementBlock(it.position, it.after) } + paving).forEach { cell ->
        if (cell.position.x in minX..maxX && cell.position.z in minZ..maxZ && cell.position.y >= bottom) {
            if (cell.after == "minecraft:air") after.remove(cell.position) else after[cell.position] = cell.after
        }
    }
    fun rows(blocks: Map<CivPosition, String>) = blocks.map { (p, data) -> listOf(p.x, p.y, p.z, data) }
    val hashes = listOf("CivsAutonomousTerraform.kt", "CivsSettlementLayout.kt").associateWith { source ->
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of("src/main/kotlin/ru/arc/civs/$source")))
            .joinToString("") { "%02x".format(it) }
    }
    Files.writeString(output.resolve("$name.json"), Gson().toJson(mapOf("name" to name,
        "sourceSha256" to hashes, "route" to route, "before" to rows(before), "after" to rows(after))))
}

/** Kotlin data-class hash used by the frozen 191 Node, before component mixing. */
private fun legacyTerraformNodeHash(node: Node): Int {
    var result = node.x
    result = result * 31 + node.y
    result = result * 31 + node.z
    result = result * 31 + node.direction
    return result * 31 + node.climbed.hashCode()
}

private class TerraformNodeProbe(private val node: Node, private val legacy: Boolean,
    private val counter: LongArray) {
    override fun hashCode(): Int = if (legacy) legacyTerraformNodeHash(node) else node.hashCode()
    override fun equals(other: Any?): Boolean {
        counter[0]++
        return other is TerraformNodeProbe && node == other.node
    }
}

private data class MildBarrierFixture(val city: Settlement, val target: CivBuilding,
    val surface: CivTerrainSurface, val barrier: CivBox)

private fun mildBarrierFixture(): MildBarrierFixture {
    val city = terraceCity()
    val target = CivBuilding(UUID(0, 900), BuildingType.BUILDER_HUT, 1, CivPosition(25, 66, 0), 90, "fixture",
        CivBox(CivPosition(25, 66, -2), CivPosition(29, 72, 2)), CivPosition(24, 67, 0), BuildingPhase.CONSTRUCTING)
    return MildBarrierFixture(city, target,
        terraceSurface { x, _ -> 64 + (x.coerceAtLeast(0) / 4).coerceAtMost(2) },
        CivBox(CivPosition(12, 60, -20), CivPosition(12, 80, 20)))
}

private fun checkMildBarrierRoute(fixture: MildBarrierFixture, route: List<CivPosition>) {
    route.first() shouldBe fixture.city.buildings.single().entrance
    route.last() shouldBe fixture.target.entrance
    route.map { civsTerrainKey(it.x, it.z) }.distinct().size shouldBe route.size
    (route.size <= CivsAutonomousTerraform.MAXIMUM_ROUTE_LENGTH) shouldBe true
    route.any { abs(it.z) >= 21 } shouldBe true
    (route.maxOf { it.y } - route.minOf { it.y } >= 2) shouldBe true
    route.all { point -> fixture.city.contains(point.x, point.z) &&
        !CivsAutonomousGeometry.horizontalContains(fixture.barrier, point) &&
        abs(requireNotNull(fixture.surface.at(point.x, point.z)).groundY - (point.y - 1)) <= CivsAutonomousTerraform.MAXIMUM_CHANGE
    } shouldBe true
    route.zipWithNext().all { (a, b) -> abs(a.x - b.x) + abs(a.z - b.z) == 1 && abs(a.y - b.y) <= 1 } shouldBe true
}

private const val COMPOSITE_FARM = "civs/v2/fixture/side-field/0123456789abcdef0123/level1"

private fun compositeFarmSite() = JsonParser.parseString("""{
    "schematicBounds":{"min":[-2,0,-4],"max":[1,2,-1]},"entrance":[0,1,0],
    "farm":{"soilCells":[[4,0,-7],[12,0,1]],"irrigationCells":[[8,0,-3]]}
}""").asJsonObject

private fun compositeFarm(city: Settlement, rotation: Int = 180): CivBuilding {
    val anchor = when (rotation) {
        0 -> CivPosition(0, 64, -18); 90 -> CivPosition(18, 64, 0)
        180 -> CivPosition(0, 64, 18); else -> CivPosition(-18, 64, 0)
    }
    val template = CivBuilding(UUID(0, 205), BuildingType.FARM, 1, CivPosition(0, 0, 0), 0, COMPOSITE_FARM,
        CivBox(CivPosition(-2, 0, -4), CivPosition(1, 2, -1)), CivPosition(0, 1, 0), BuildingPhase.CONSTRUCTING)
    val building = CivsSettlementPlans.at(template, anchor, rotation, template.id)
    return building.copy(bounds = CivsAutonomousGeometry.futureBounds(city, building))
}

private fun terraceCity(): Settlement {
    val hall = CivBuilding(UUID.randomUUID(), BuildingType.TOWN_HALL, 1, CivPosition(0, 64, 0), 0, "fixture",
        CivBox(CivPosition(-4, 64, -8), CivPosition(4, 70, -1)), CivPosition(0, 65, 0), BuildingPhase.SITE,
        CivPosition(0, 65, -2))
    return Settlement(UUID.randomUUID(), UUID.randomUUID(), "Terrace", UUID.randomUUID(), CivPosition(0, 64, 0),
        48, "fixture", Stock(), listOf(hall), emptyList(), autonomy = CivAutonomy())
}

/** Caledonia builder family header: 13 x 17 x 13, offset (-6,-4,-13), turned to face the town hall. */
private fun terraceBuilder(city: Settlement, ground: Int = 64): CivBuilding = CivBuilding(UUID.randomUUID(),
    BuildingType.BUILDER_HUT, 1, CivPosition(0, ground, 18), 180, "fixture",
    CivBox(CivPosition(-6, ground - 4, 19), CivPosition(6, ground + 12, 31)), CivPosition(0, ground + 1, 18),
    BuildingPhase.CONSTRUCTING, CivPosition(0, ground + 1, 22))

private fun terraceSurface(height: (Int, Int) -> Int): CivTerrainSurface = CivTerrainSurface(-64, 320,
    buildMap { for (x in -48..48) for (z in -48..48) put(civsTerrainKey(x, z), CivTerrainSurfaceColumn(height(x, z), true)) })

private fun terraceCapture(draft: CivTerraformDraft, surface: CivTerrainSurface): Map<CivPosition, CivTerraformCell> = buildMap {
    draft.columns.forEach { c ->
        val top = requireNotNull(surface.at(c.x, c.z)).groundY
        for (y in maxOf(surface.minHeight, c.floorY - CivsAutonomousTerraform.MAXIMUM_CHANGE)..maxOf(c.groundY, c.surfaceY) + 2) {
            val p = CivPosition(c.x, y, c.z)
            val data = when {
                y == top -> "minecraft:grass_block[snowy=false]"
                y in top - 2 until top -> "minecraft:dirt"
                y < top -> "minecraft:stone"
                else -> "minecraft:air"
            }
            put(p, CivTerraformCell(p, data, true, y <= top, y > top))
        }
    }
}

/** Independent occupancy oracle on journal-after states, without relying on planner score or traversal. */
private fun checkTerrace(plan: CivTerraformPlan, draft: CivTerraformDraft, cells: Map<CivPosition, CivTerraformCell>) {
    val overlay = plan.blocks.associate { it.position to it.after }
    fun solid(p: CivPosition) = overlay[p]?.let { it != "minecraft:air" } ?: cells.getValue(p).solid
    fun passable(p: CivPosition) = overlay[p]?.let { it == "minecraft:air" } ?: cells.getValue(p).passable
    plan.route.map { civsTerrainKey(it.x, it.z) }.distinct().size shouldBe plan.route.size
    plan.route.zipWithNext().forEach { (a, b) ->
        (abs(a.x - b.x) + abs(a.z - b.z)) shouldBe 1
        (abs(a.y - b.y) <= 1) shouldBe true
    }
    plan.route.forEach { feet ->
        solid(feet.copy(y = feet.y - 1)) shouldBe true
        passable(feet) shouldBe true
        passable(feet.copy(y = feet.y + 1)) shouldBe true
    }
    val b = draft.building.bounds
    for (x in b.min.x..b.max.x) for (z in b.min.z..b.max.z) {
        solid(CivPosition(x, b.min.y - 1, z)) shouldBe true
        solid(CivPosition(x, draft.groundY, z)) shouldBe true
        for (dy in 1..2) {
            val point = CivPosition(x, draft.groundY + dy, z)
            if (point != draft.building.control || draft.prepareControl) passable(point) shouldBe true
        }
    }
}
