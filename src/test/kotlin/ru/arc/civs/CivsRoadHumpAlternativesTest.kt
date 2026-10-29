package ru.arc.civs

import com.google.gson.Gson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class CivsRoadHumpAlternativesTest : FunSpec({
    test("the actual shared guard corner agrees after the later hill replacement") {
        val (city, plans) = foresterHumpTown()
        val before = Gson().toJson(plans)
        val targets = CivsRoadMaintenance.prepare(city, plans).targets
        targets.any { it.floor == SECOND_HUMP && it.routeOverride != null } shouldBe true
        val target = targets.single { it.floor == SECOND_HUMP && it.routeOverride != null }
        target.linkedRouteOverrides!!.keys shouldBe setOf(GUARD.toString(), GATE.toString(), WALL.toString())
        val changed = target.routeOverride!!.changed()
        changed.size shouldBe 7
        target.routeOverride!!.route[changed.first()].y shouldBe 64
        val routes = target.overrides().values.map { CivsAutonomousTerraform.roadProfile(it.route) }
        routes.map { it[civsTerrainKey(-1869, -2709)]!!.y }.distinct() shouldBe listOf(63)
        val owned = CivsRoadMaintenance.ownedPavement(city, target)
        ("minecraft:dirt_path" in owned[CivPosition(-1869, 64, -2709)].orEmpty()) shouldBe true
        ("minecraft:dirt_path" in owned[CivPosition(-1869, 63, -2709)].orEmpty()) shouldBe false
        Gson().toJson(plans) shouldBe before
    }

    for (conflict in listOf("crossing", "different-final-shoulder", "authored-road"))
    test("a later hill still refuses $conflict instead of ignoring an old ambiguous grade") {
        val (base, plans) = foresterHumpTown()
        val forester = base.building(FORESTER)!!
        val prefix = plans.single { it.building.id == FORESTER }.terrain!!.route.take(52)
        val route = if (conflict == "different-final-shoulder") prefix + listOf(
            CivPosition(-1869, 64, -2708), CivPosition(-1870, 63, -2708), CivPosition(-1870, 63, -2709),
            CivPosition(-1871, 63, -2709), CivPosition(-1872, 63, -2709))
        else (-1871..-1865).map { CivPosition(it, 67, -2712) }
        val type = when (conflict) {
            "authored-road" -> BuildingType.ROAD
            "different-final-shoulder" -> BuildingType.GATE
            else -> BuildingType.HOUSE
        }
        val other = forester.copy(id = UUID(0, 99), type = type, level = 1,
            anchor = when (type) {
                BuildingType.ROAD -> route.first().copy(y = 66)
                BuildingType.GATE -> route.last().copy(y = 62)
                else -> CivPosition(-1820, 63, -2690)
            }, bounds = when (type) {
                BuildingType.ROAD -> CivBox(route.first().copy(y = 66), route.last().copy(y = 68))
                BuildingType.GATE -> CivBox(route.last().copy(y = 62), route.last().copy(y = 66))
                else -> CivBox(CivPosition(-1820, 63, -2690), CivPosition(-1815, 70, -2685))
            },
            entrance = route.last(), schematicId = "fixture/conflicting-route", control = null,
            transit = when (type) { BuildingType.ROAD -> route; BuildingType.GATE -> listOf(route.last()); else -> null })
        val city = CivsRules.validate(base.copy(buildings = base.buildings + other))
        val plan = foresterHumpApproach(city, other, route)
        val retained = if (conflict == "different-final-shoulder") {
            // Isolate this cut's owner: the real guard and another ordinary producer can propose different footprints.
            val original = plans.single { it.building.id == FORESTER }
            fun lower(points: List<CivPosition>) = points.map { point ->
                if (point.x == -1868 && point.z in -2715..-2709) point.copy(y = 64) else point
            }
            val key = civsTerrainKey(-1869, -2709)
            val before = CivsAutonomousTerraform.roadProfile(original.terrain!!.route)
            val after = CivsAutonomousTerraform.roadProfile(lower(original.terrain!!.route))
            before.getValue(key).y shouldBe 64
            after.getValue(key).y shouldBe 63
            CivsAutonomousTerraform.roadProfile(lower(route)).getValue(key).y shouldBe 62
            route.none { it.x == -1869 && it.z == -2709 } shouldBe true
            original.terrain!!.route.none { it.x == -1869 && it.z == -2709 } shouldBe true
            // Retained centrelines agree on every changed column; the wider shoulder alone differs.
            lower(route).all { feet ->
                val column = civsTerrainKey(feet.x, feet.z)
                before[column] == after[column] || after[column]?.y?.let { it == feet.y - 1 } != false
            } shouldBe true
            other.type.isInfrastructure shouldBe true // No second primary hump source or noninfra centreline ambiguity.
            listOf(original)
        } else plans
        CivsRoadMaintenance.prepare(city, retained + plan).targets.none { it.floor == SECOND_HUMP && it.routeOverride != null } shouldBe true
    }

    test("a native planks support refusal leaves the later shared hill available and all twenty one columns finish") {
        MockBukkitTestRuntime.open().use { paper ->
            val world = CivsWorksiteWorldFixture("forester-hump-alternatives", collisionForData = { data ->
                if (CivsRoadMaintenance.surface(data.asString)) roadUpkeepBoxes(data).map { it.at(CivPosition(0, 0, 0)) } else null
            }).also(paper.server::addWorld)
            for (x in -118..-116) for (z in -173..-168) world.loadChunk(x, z)
            val (base, plans) = foresterHumpTown(world.uid)
            var city = base
            val routes = plans.map { it.terrain!!.route }
            // Captured centreline heights and paving recipe; surrounding uncaptured ground is a stone-plane model.
            val floors = linkedMapOf<Long, CivPosition>()
            routes.forEach { route -> route.forEach { feet -> floors.putIfAbsent(civsTerrainKey(feet.x, feet.z), feet.copy(y = feet.y - 1)) } }
            routes.forEach { route -> CivsAutonomousTerraform.roadProfile(route).forEach { (key, floor) -> floors.putIfAbsent(key, floor) } }
            val levels = floors.mapValues { it.value.y }
            floors.values.forEach { floor ->
                for (y in 63..floor.y) world.getBlockAt(floor.x, y, floor.z).type = Material.DIRT
                world.getBlockAt(floor.x, floor.y, floor.z).blockData = Bukkit.createBlockData(
                    CivsSettlementLayout.pathPavement(floor, levels, city.style))
            }
            val blockedSupport = CivPosition(-1867, 63, -2745)
            world.getBlockAt(blockedSupport.x, blockedSupport.y, blockedSupport.z).type = Material.DARK_OAK_PLANKS
            world.getBlockAt(blockedSupport.x, blockedSupport.y + 1, blockedSupport.z).type = Material.AIR
            val corner = CivPosition(-1869, 64, -2709)
            world.getBlockAt(corner.x, corner.y, corner.z).type = Material.DIRT_PATH
            val workerBefore = city.workers.single()
            val savedPlans = Gson().toJson(plans)
            val plugin = paper.createSimplePlugin("ForesterHumpAlternatives")
            LifecycleTaskScope(BukkitTaskScheduler(plugin)).use { tasks ->
                CivsWorld(plugin, tasks, { 0L }) { listOf(city) }.use { native ->
                    val initial = CivsRoadMaintenance.prepare(city, plans)
                    val first = initial.targets.single { it.floor == FIRST_HUMP && it.routeOverride != null }
                    native.captureRoadUpkeep(city, city.workers.single(), first) shouldBe null
                    initial.targets.firstOrNull { it.floor == SECOND_HUMP && it.routeOverride != null }?.let { later ->
                        // A new path at another height has no accepted former pavement proof.
                        val foreign = world.getBlockAt(-1868, 64, -2713)
                        val before = foreign.blockData
                        foreign.type = Material.DIRT_PATH
                        native.captureRoadUpkeep(city, city.workers.single(), later) shouldBe null
                        foreign.blockData = before
                        (native.captureRoadUpkeep(city, city.workers.single(), later) != null) shouldBe true
                    }
                    var tick = 0
                    var cuts = 0
                    var development = false
                    val maintenance = CivsRoadMaintenance(native) { tick }
                    maintenance.publish(city, initial)
                    maintenance.admitDevelopment(city) shouldBe false
                    repeat(160) {
                        if (development) return@repeat
                        tick++
                        val work = maintenance.plan(city, city.workers.single())
                        maintenance.backfillSurfaces(city)
                        if (work == null) { development = maintenance.admitDevelopment(city); return@repeat }
                        if (cuts == 0) work.upkeep!!.floor shouldBe SECOND_HUMP
                        val at = 3_000L + cuts * 3_000L
                        val after = CivsRoadMaintenance.complete(city, work, at)
                        CivRoadUpkeepReceipt(city.workers.single(), city.stock, work, at).validated(after)
                        work.changes.asReversed().forEach { change -> world.getBlockAt(change.position.x, change.position.y,
                            change.position.z).blockData = Bukkit.createBlockData(change.after) }
                        native.validateRoadUpkeep(city, work)
                        city = after
                        cuts++
                        if (work.upkeep!!.routeOverride != null) maintenance.publish(city, CivsRoadMaintenance.prepare(city, plans))
                        else maintenance.upkeepRetired(city, work.upkeep!!)
                    }
                    development shouldBe true
                    cuts shouldBe 21
                    city.workers.single().completedCycles shouldBe workerBefore.completedCycles + cuts
                    city.stock.stone shouldBe base.stock.stone - cuts
                    for (x in -1869..-1867) for (z in -2715..-2709) {
                        world.getBlockAt(x, 63, z).type shouldBe Material.DIRT_PATH
                        for (y in 64..67) world.getBlockAt(x, y, z).type.isAir shouldBe true
                    }
                    world.getBlockAt(blockedSupport.x, blockedSupport.y, blockedSupport.z).type shouldBe Material.DARK_OAK_PLANKS
                    Gson().toJson(plans) shouldBe savedPlans
                }
            }
        }
    }
})

private val FORESTER = UUID.fromString("be480ebd-4dff-4dfc-a740-4e78d331558e")
private val GUARD = UUID.fromString("91c3b44f-c569-4490-b1dc-79820636605d")
private val GATE = UUID.fromString("2725b17d-e1ea-3130-83e2-06959b964a84")
private val WALL = UUID.fromString("b025187e-5b8c-3ae8-94cb-e7481d224e1f")
private val FIRST_HUMP = CivPosition(-1868, 64, -2743)
private val SECOND_HUMP = CivPosition(-1868, 63, -2715)

/** Exact 2026-10-29 Auto2 retained route coordinates; unrelated buildings are omitted outside both road footprints. */
private fun foresterHumpTown(worldId: UUID = UUID(0, 12)): Pair<Settlement, List<CivPlacementPlan>> {
    val heights = List(14) { 64 } + listOf(65, 65) + List(8) { 66 } + listOf(65) + List(4) { 64 } +
        List(11) { 63 } + List(4) { 64 } + listOf(65, 66, 67, 67, 67, 66, 65) + List(13) { 64 }
    val foresterRoute = heights.mapIndexed { index, y -> CivPosition(-1868, y, -2759 + index) }
    val forester = spatialBuilding("caledonia", "lumberjack", 2, CivPosition(-1868, 63, -2696), 180).copy(id = FORESTER)
    fun building(id: UUID, type: BuildingType, anchor: CivPosition, bounds: CivBox, route: List<CivPosition>) =
        forester.copy(id = id, type = type, anchor = anchor, bounds = bounds, entrance = route.last(),
            schematicId = "fixture/${type.name.lowercase()}", level = 1, rotation = 0, control = null)
    val guardRoute = foresterRoute.take(52) + listOf(CivPosition(-1869, 64, -2708), CivPosition(-1870, 64, -2708),
        CivPosition(-1870, 64, -2709), CivPosition(-1871, 64, -2709), CivPosition(-1871, 64, -2710)) +
        (-2710 downTo -2715).map { CivPosition(-1872, 64, it) } + (-1872 downTo -1878).map { CivPosition(it, 63, -2716) }
    val gateRoute = foresterRoute.take(53) + listOf(CivPosition(-1867, 64, -2707), CivPosition(-1866, 63, -2707),
        CivPosition(-1866, 63, -2706)) + (-1865..-1863).map { CivPosition(it, 63, -2706) }
    val wallRoute = gateRoute.dropLast(2)
    val guard = building(GUARD, BuildingType.GUARD_TOWER, CivPosition(-1878, 62, -2716),
        CivBox(CivPosition(-1884, 62, -2735), CivPosition(-1872, 103, -2717)), guardRoute)
    val gate = building(GATE, BuildingType.GATE, CivPosition(-1862, 62, -2706),
        CivBox(CivPosition(-1862, 62, -2706), CivPosition(-1858, 68, -2702)), gateRoute).copy(
        transit = (-1862..-1858).flatMap { x -> (-2706..-2702).map { z ->
            CivPosition(x, if (z == -2702 && x >= -1859) 64 else 63, z)
        } })
    val wall = building(WALL, BuildingType.WALL, CivPosition(-1865, 62, -2705),
        CivBox(CivPosition(-1865, 62, -2705), CivPosition(-1863, 66, -2703)), wallRoute)
    val hall = building(UUID(0, 1), BuildingType.TOWN_HALL, CivPosition(-1883, 63, -2760),
        CivBox(CivPosition(-1887, 63, -2769), CivPosition(-1869, 98, -2751)), listOf(CivPosition(-1868, 64, -2759))).copy(level = 2)
    val builder = building(UUID(0, 2), BuildingType.BUILDER_HUT, CivPosition(-1896, 63, -2754),
        CivBox(CivPosition(-1909, 59, -2760), CivPosition(-1897, 75, -2748)), listOf(CivPosition(-1896, 64, -2754)))
    val city = CivsRules.validate(Settlement(UUID(0, 10), UUID(0, 11), "Forester hills", worldId,
        CivPosition(-1883, 67, -2760), 128, "caledonia", Stock(1_000, 1_000, 1_000),
        listOf(hall, builder, forester, guard, gate, wall),
        listOf(CivWorker(UUID(0, 13), "Builder", builder.id, nextDeliveryAt = 1)), autonomy = CivAutonomy()))
    return city to listOf(forester to foresterRoute, guard to guardRoute, gate to gateRoute, wall to wallRoute).map { (building, route) ->
        foresterHumpApproach(city, building, route)
    }
}

private fun foresterHumpApproach(city: Settlement, building: CivBuilding, route: List<CivPosition>) =
    CivPlacementPlan(building.id, city.id, city.worldId, building, emptyList(), terrain = CivTerraformPlan(
        CivBox(CivPosition(route.minOf { it.x }, route.minOf { it.y } - 1, route.minOf { it.z }),
            CivPosition(route.maxOf { it.x }, route.maxOf { it.y } + 1, route.maxOf { it.z })),
        emptyList(), route, building.anchor.y, 0, 0))
